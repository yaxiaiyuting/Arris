/*
 * Ncrust —— ncm 第三方客户端
 * 本文件属于本 Fork（Arris）的修改部分，Copyright (c) 2026 yaxiaiyuting，GPLv3。
 */

package com.takahashirinta.ncrust.network

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/** 一条路由用的是哪一族地址。`null`（解析没拿到地址）不参与判定。 */
enum class AddressFamily {
    V4,
    V6;

    /** 另一族。只有两族，所以是对合运算。 */
    fun opposite(): AddressFamily = if (this == V4) V6 else V4

    fun label(): String = if (this == V4) "v4" else "v6"

    companion object {
        fun of(ip: InetAddress?): AddressFamily? = when (ip) {
            null -> null
            is Inet4Address -> V4
            is Inet6Address -> V6
            // 既不是 v4 也不是 v6（理论上不存在）时按「不知道」处理，绝不伪造一族。
            else -> null
        }
    }
}

/**
 * 一次事件是否构成「回退成功 / 回退也失败」的证据。
 *
 * - [NONE]：没有回退可判（本 host 之前没有失败记录，或这次就是那次失败本身）；
 * - [OK]：先前在 A 族上传输失败，随后 B 族**建连成功** ⇒ 换族救回来了；
 * - [FAIL]：先前在 A 族上失败，换到 B 族**仍然传输失败** ⇒ 换族没救回来。
 */
enum class FailoverVerdict {
    NONE,
    OK,
    FAIL;

    /** 日志字段：`failover=ok|fail|none`。 */
    fun label(): String = when (this) {
        NONE -> "none"
        OK -> "ok"
        FAIL -> "fail"
    }
}

/**
 * v4.1.10：**按 host 记住「哪一族最近失败 / 成功」**（内存状态，纯逻辑，时间注入）。
 *
 * ## 它补的是哪一格
 *
 * v4.1.9 的回退只有一个**全局**指针 + 3 分钟窗口（[AddressPreference]）：
 *
 * - 它回答「现在该不该优先 IPv6」，但**不回答**「谁救回来了」「救回来几次」；
 * - 窗口内一旦切到 IPv6，就是无条件 IPv6 优先 —— 哪怕这个 host 上一秒刚在 IPv6 上失败；
 * - 于是「回退胜率」根本无从统计：日志里只有 `preferIpv6=true`，没有结果。
 *
 * 本类只做三件事，且**都不改变窗口语义**（窗口仍然是 3 分钟、仍然由
 * [AddressPreference.onTransportFailure] 开关）：
 *
 * 1. **窗口内**优先「这个 host 最近成功的那一族」（[preferredWithinWindow]）；
 * 2. 记录回退的可测量结果：`attempts / recovered / failed`，以及每次事件的
 *    [FailoverVerdict] —— 供日志打 `failover=ok|fail`；
 * 3. 换网时整体清空（[clear]），上一张网的记忆不得带进下一张网。
 *
 * ## 为什么不延长窗口
 *
 * 反向故障（v6 黑洞、v4 正常）同样存在。窗口只是「允许换族」的许可，本类只是
 * 在许可内**选择顺序**。如果本类延长窗口，等于把「允许」变成「长期偏置」，
 * 那正是 v4.1.9 KDoc 明确否掉的形态（两个方向各最多付一次超时）。
 *
 * ## 纯逻辑
 *
 * 所有方法都接受 `nowMs`，不读系统时钟；状态表有上限（[MAX_HOSTS]），
 * 满了就整表丢弃 —— 比 LRU 简单，且不需要额外的数据结构。
 */
object AddressHealth {

    /** 记忆窗口：超过这个时长的观测不再参与「优先哪一族」。 */
    const val MEMORY_TTL_MS = 10 * 60_000L

    /**
     * 「等待回退结果」的有效期：一次失败之后 [PENDING_TTL_MS] 内到达的反向事件才算回退证据。
     * 取 120s 而不是无限：一次失败之后隔了十分钟才来的成功，不是回退的功劳。
     */
    const val PENDING_TTL_MS = 120_000L

    private const val MAX_HOSTS = 32

    private data class Observation(val family: AddressFamily, val atMs: Long)

    private class Entry {
        @Volatile var lastOk: Observation? = null
        @Volatile var lastFail: Observation? = null

        /** 刚失败、正在等另一族结果的那一族。 */
        @Volatile var pending: Observation? = null

        var attempts = 0
        var recovered = 0
        var failed = 0

        fun fresh(nowMs: Long): Boolean = true
    }

    private val hosts = ConcurrentHashMap<String, Entry>()

    /** 单测用：清空全部记忆。生产路径由 [onNetworkRouteChanged] 调用。 */
    fun clear() {
        hosts.clear()
    }

    /** 换网（Wi-Fi ↔ 蜂窝 / 断网重连）：上一张网的地址族可达性整份作废。 */
    fun onNetworkRouteChanged() = clear()

    private fun entryOf(host: String): Entry {
        hosts[host]?.let { return it }
        if (hosts.size >= MAX_HOSTS) hosts.clear()
        return hosts.computeIfAbsent(host) { Entry() }
    }

    /**
     * 一次**新建连接成功**（连接复用不算：复用不证明这一族现在可达）。
     *
     * 返回 [FailoverVerdict.OK] 当且仅当本 host 之前有一笔未结的失败、而这次成功的
     * 是**另一族** —— 这就是「换族救回来了」的定义。
     */
    fun onConnectSuccess(host: String, family: AddressFamily?, nowMs: Long): FailoverVerdict {
        if (family == null) return FailoverVerdict.NONE
        val entry = entryOf(host)
        val pending = entry.pending?.takeIf { nowMs - it.atMs <= PENDING_TTL_MS }
        entry.lastOk = Observation(family, nowMs)
        entry.pending = null
        if (pending == null) return FailoverVerdict.NONE
        return if (pending.family != family) {
            entry.recovered++
            FailoverVerdict.OK
        } else {
            // 同一族又成功了：说明那次失败是瞬时的，不是「换族救回来的」。不计入胜率。
            FailoverVerdict.NONE
        }
    }

    /**
     * 一次**传输层失败**（建连超时 / 连接重置 / 断流）。
     *
     * 返回 [FailoverVerdict.FAIL] 当且仅当本 host 之前有一笔未结的失败、而这次失败的
     * 是**另一族** —— 两族都不通，换族无效。否则返回 [FailoverVerdict.NONE]，
     * 并登记一笔「正在等另一族结果」（`attempts++`）。
     */
    fun onTransportFailure(host: String, family: AddressFamily?, nowMs: Long): FailoverVerdict {
        if (family == null) return FailoverVerdict.NONE
        val entry = entryOf(host)
        val pending = entry.pending?.takeIf { nowMs - it.atMs <= PENDING_TTL_MS }
        entry.lastFail = Observation(family, nowMs)
        return if (pending != null && pending.family != family) {
            entry.failed++
            entry.pending = null
            FailoverVerdict.FAIL
        } else {
            // 新的失败：从这一刻开始等另一族的结果。
            entry.attempts++
            entry.pending = Observation(family, nowMs)
            FailoverVerdict.NONE
        }
    }

    /**
     * **窗口内**该优先哪一族；`null` = 没有可用记忆，保持窗口的默认顺序（IPv6 优先）。
     *
     * 判据只有一条：最近一次观测是成功 → 优先那一族；最近一次是失败 → 优先另一族。
     * 两者都超过 [MEMORY_TTL_MS] 就当作没有记忆。
     */
    fun preferredWithinWindow(host: String, nowMs: Long): AddressFamily? {
        val entry = hosts[host] ?: return null
        val ok = entry.lastOk?.takeIf { nowMs - it.atMs <= MEMORY_TTL_MS }
        val fail = entry.lastFail?.takeIf { nowMs - it.atMs <= MEMORY_TTL_MS }
        return when {
            ok != null && (fail == null || ok.atMs >= fail.atMs) -> ok.family
            fail != null -> fail.family.opposite()
            else -> null
        }
    }

    /** 诊断一行：`host=… ok=v4 fail=v6 attempts=3 recovered=2 failed=1`。 */
    fun describe(host: String, nowMs: Long): String {
        val entry = hosts[host] ?: return "host=$host none"
        return "host=$host ok=${entry.lastOk?.family?.label() ?: "-"}" +
            " fail=${entry.lastFail?.family?.label() ?: "-"}" +
            " attempts=${entry.attempts} recovered=${entry.recovered} failed=${entry.failed}" +
            " prefer=${preferredWithinWindow(host, nowMs)?.label() ?: "-"}"
    }

    /** 全表回退胜率（诊断 / 报告用）：`recovered / (recovered + failed)`，没有样本时为 null。 */
    fun failoverWinRate(): Pair<Int, Int>? {
        var recovered = 0
        var failed = 0
        hosts.values.forEach { recovered += it.recovered; failed += it.failed }
        val total = recovered + failed
        return if (total == 0) null else recovered to total
    }

    /** 单测用：某 host 的回退计数。 */
    internal fun counters(host: String): Triple<Int, Int, Int> {
        val entry = hosts[host] ?: return Triple(0, 0, 0)
        return Triple(entry.attempts, entry.recovered, entry.failed)
    }
}
