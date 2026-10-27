/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * J01：播放统计落盘的**提交序**（清空 / 落盘共用一条单调序号）。
 */

package com.takahashirinta.ncrust.stats

import android.content.SharedPreferences

/** 一次「清空」领到的票：代次用于识别"清空之后又清空了一次"，票号用于识别"清空之后已有新数据落盘"。 */
internal data class ClearTicket(val generation: Int, val ticket: Long)

/**
 * J01：统计快照的**持久化提交序**。
 *
 * ## 为什么光有「清空代次」还不够
 *
 * v3.7.8 之前只有一条 `generation`（清空 +1），`flushNow` 与 `clear` 各自校验它。它挡得住
 * 「清空之后旧快照写回」，但漏掉了同一代次内的两种反序：
 *
 * 1. **旧快照晚于新快照落盘**：`flushNow` 在 `Dispatchers.Default` 上序列化（几毫秒），
 *    这期间第二次 `flushNow` 可能已经拷贝到更新的快照并先写完盘；随后旧的那份最后提交，
 *    磁盘回退成旧值。全程没有 `clear`，代次一直没变 ⇒ 旧代码放行。
 * 2. **清空的删除晚于清空后的新落盘**：`clear` 的删除是异步协程，用户清空后立刻又听了几秒，
 *    `flushSoon` 会先把**清空后的新数据**写盘，然后那份迟到的删除再把它删掉 ——
 *    内存里有、磁盘上没有，冷启动即丢。旧的删除只校验代次，而「清空后新增采样」并不推进代次。
 *
 * ## 机制
 *
 * - **票号**：每份待落盘快照在**拷贝快照的同一把锁内**领一个全序递增的票号；
 *   `clear` 也领一张。票号顺序 == 内容的新旧顺序。
 * - `commit`：代次必须没变，且票号必须比"已落盘的最高票号"更新，否则丢弃
 *   （丢一次落盘是自愈的 —— 快照是累计量，下一次变更会整份重写）。
 * - `deleteAfterClear`：代次仍是这次清空的代次，且清空之后**没有**更新的票号落过盘，才允许删。
 *
 * 判定与磁盘写入在同一把锁里完成（就是旧代码的 `commitLock`），所以
 * 「校验通过 → 被清空 → 旧快照写回」这个窗口也不存在。
 */
internal class StatsCommitGate {

    /** 只保护本类的三个计数与磁盘提交；**不**保护（可能很慢的）JSON 序列化。 */
    private val lock = Any()

    private var generation = 0

    /** 票号发号器（单调递增）。 */
    private var ticketSeq = 0L

    /** 已经落到盘上的最高票号（清空的删除也算一次"落到最新状态"）。 */
    private var committedTicket = 0L

    /** 当前清空代次。`flushNow` 在 `StatsStore` 的锁内读它，与领票同序。 */
    fun currentGeneration(): Int = synchronized(lock) { generation }

    /** 给一份待落盘快照领票。票号越大 = 内容越新。 */
    fun newTicket(): Long = synchronized(lock) { ++ticketSeq }

    /** 清空：推进代次并领一张票，返回的票给 [deleteAfterClear] 用。 */
    fun beginClear(): ClearTicket = synchronized(lock) {
        generation += 1
        ticketSeq += 1
        ClearTicket(generation, ticketSeq)
    }

    /**
     * 落盘一份快照。返回是否真的写了盘（单测用；生产路径不关心返回值）。
     *
     * 代次变了 ⇒ 用户清空过，这份旧快照作废；
     * 票号不比已落盘的更新 ⇒ 有更新的快照先写完盘了，这份会把它覆盖回旧值，作废。
     */
    fun commit(
        prefs: SharedPreferences,
        generation: Int,
        ticket: Long,
        json: String,
    ): Boolean = synchronized(lock) {
        if (this.generation != generation) return false
        if (ticket <= committedTicket) return false
        val written = prefs.edit().putString(StatsStore.KEY_SNAPSHOT, json).commit()
        committedTicket = ticket
        written
    }

    /**
     * 执行清空的删除。返回是否真的删了。
     *
     * 代次变了 ⇒ 又清空过一次，交给更新那次处理；
     * 清空之后已有更新的票号落盘 ⇒ **那是清空后的新数据，不许删**（J01 的第二条反序）。
     */
    fun deleteAfterClear(prefs: SharedPreferences, ticket: ClearTicket): Boolean = synchronized(lock) {
        if (generation != ticket.generation) return false
        if (committedTicket > ticket.ticket) return false
        val written = prefs.edit().remove(StatsStore.KEY_SNAPSHOT).commit()
        committedTicket = ticket.ticket
        written
    }
}
