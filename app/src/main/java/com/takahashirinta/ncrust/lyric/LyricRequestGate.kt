/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import java.util.concurrent.atomic.AtomicLong

/**
 * 歌词请求序列号闸门（v1.9.0）：防止快速切歌时**旧请求的响应盖掉新歌的歌词**。
 *
 * 场景：A 歌的请求还在飞，用户切到 B 歌，B 的响应先回来渲染，随后 A 的响应才到 ——
 * 若直接写入歌词状态，屏幕上就会挂着 A 的歌词配 B 的播放进度。做法与 SPlayer 的 LyricManager 一致：
 * 每次为歌曲发起请求前领一个号，响应回来时先问「我还是当前号吗」，不是就整包丢弃。
 *
 * 线程安全用 [AtomicLong] 而不是 @Volatile + synchronized：这里的操作只有
 * 「自增取号」与「读比较」两种单一原子操作，没有需要复合保护的临界区，无锁实现更短也更快。
 * 播放器侧从多个协程并发调用（切歌、seek 后重拉、翻译开关重拉）是常态。
 */
class LyricRequestGate {

    /**
     * 当前有效号。初始 0 是**从未 begin 过的哨兵**：[begin] 从 1 开始发号，
     * 于是 [isCurrent] 对哨兵恒为 false —— 调用方那个「还没请求过」的默认字段（通常是 0）
     * 不会被误判成有效，省掉一类「首帧响应写坏状态」的隐患。
     */
    private val current = AtomicLong(0L)

    /** 开一次新请求：返回新号，并让所有更早的号立即作废。 */
    fun begin(): Long = current.incrementAndGet()

    /** 这个号是否仍是当前请求。注意同时只允许一个新号有效。 */
    fun isCurrent(seq: Long): Boolean = seq != 0L && current.get() == seq

    /**
     * 作废所有在途请求（如退出播放器、清空当前歌曲）。
     *
     * 实现与 [begin] 共用同一条自增：作废不需要额外标志位，老号因为不等于新号而天然失效，
     * 之后 [begin] 仍能拿到有效号，不存在「作废后闸门卡死」的状态。
     */
    fun invalidate() {
        current.incrementAndGet()
    }
}
