/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · C：乱序模式的「上一首」落点判定。纯逻辑，JVM 可单测。
 */

package com.takahashirinta.ncrust.player

/**
 * 乱序（SHUFFLE）模式下「上一首 / 下一首」在**这一轮乱序序列**里的落点。
 *
 * ## 它修的是什么（真机实测发现的）
 *
 * v2.5.5 在 PLC110 上做托盘「上一首」按钮的受控复测（5 次），成功率只有 **1/5** ——
 * 而同一个按钮在非乱序模式下每次都成功。查 `playbackQueue` 的落盘状态发现
 * 当时 `play_mode = 2`（SHUFFLE），再看 `MainActivity.playPrevious()`：
 *
 * ```kotlin
 * QueueModes.SHUFFLE -> {
 *     if (shuffledPosition > 0) {          // ← 没有 else
 *         shuffledPosition--
 *         playFromQueue(shuffledIndices[shuffledPosition])
 *     }
 * }
 * ```
 *
 * **游标在轮首（`shuffledPosition == 0`）时整个分支什么都不做** ——
 * 按钮点了没有任何反馈、没有 Toast、没有日志。用户感知就是
 * 「上一首按钮是坏的 / 时灵时不灵」，而它其实**只在轮首坏**。
 *
 * 这不是本版引入的缺陷（`playNext` 在轮末会重洗一轮，`playPrevious` 从来没有过回绕），
 * 但它与铁律 19（播放控制按钮是核心功能）直接冲突：一个在特定模式下**静默无效**的
 * 上一首按钮，与「缺失」在用户视角是同一件事。
 *
 * ## 落点规则
 *
 * 与同一文件里 `playNext` 的「队尾回绕到队首」对称：**轮首回绕到轮末**。
 * 也与 CYCLE / LINE 的上一首（`if (currentQueueIndex > 0) -1 else size - 1`）同一条约定 ——
 * 「上一首」在边界上回绕是这个播放器里已经存在三处的行为，不是本函数发明的。
 *
 * ## 有意**不**做「重洗一轮」
 *
 * `playNext` 在轮末会重洗（v2.0.0 · T1-C），看起来对称的做法是让 `playPrevious`
 * 在轮首也重洗。**不做**，两个理由：
 * 1. 「上一首」的语义是「回到我刚听过的那一首」，重洗后 `size-1` 位上是一首**从没播过的歌**；
 * 2. 重洗会改掉 `shuffledIndices` 本身 —— 而 `playNext` 依赖它推进。用户在轮首点一下
 *    「上一首」就能让整轮顺序变掉，是「随机还会重复」那类难以归因的问题的温床。
 */
object ShuffleNav {

    /**
     * 乱序序列里「上一首」的目标下标；无可去之处时返回 `null`（调用方保持原状）。
     *
     * @param current 当前游标（`shuffledPosition`）。
     * @param size 本轮乱序序列的长度（`shuffledIndices.size`）。
     * @return `0..size-1` 内的下标；序列为空、或 [current] 越界时返回 `null`。
     *
     * 越界**不做钳制**而是返回 `null`：`shuffledPosition` 与 `shuffledIndices` 应当始终同源
     * （`playFromQueue` 会在两者不同步时重新生成）。这里悄悄钳制会把「状态已经不一致」
     * 这件事盖住，而那种不一致的另一个表现是「按上一首跳到了一首没听过的歌」。
     */
    fun previousPosition(current: Int, size: Int): Int? {
        if (size <= 0) return null
        if (current !in 0 until size) return null
        return if (current > 0) current - 1 else size - 1
    }
}
