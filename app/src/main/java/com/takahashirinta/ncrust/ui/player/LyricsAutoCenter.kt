/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.3.0 · E：横屏歌词「5s 无触碰自动居中」的**纯判定**（无 Compose 依赖，JVM 可单测）。
 */

package com.takahashirinta.ncrust.ui.player

/**
 * 歌词面板「用户不动它就自己回正」的判定（v2.3.0 · E）。
 *
 * ## 这一版修的到底是什么（探针结论，见 `docs/verification/v2.3.0/probe-lyric-landscape.md`）
 *
 * v2.2.1 及以前，面板里已经有一句 `delay(5000)`，但它**只做了一半**：
 *
 * ```kotlin
 * } else if (!listState.isScrollInProgress && userScrolling) {
 *     delay(5000)
 *     userScrolling = false          // ← 只是把「别跟着我」的旗子放下
 *     lastAutoScrolledIndex = -1     // ← 只是让下一次跨行能重新跟着
 * }
 * ```
 *
 * 它**没有**在那一刻把列表滚回去。真正的重新定位只发生在
 * `LaunchedEffect(currentIndex)` 里 —— 也就是**必须等到换行**。
 * 于是用户在间奏里往上滑两下、或者暂停后翻看歌词，面板就**停在原地**：
 * 只要当前行不变，那条 effect 一次都不会跑。用户描述的原话就是
 * 「手动调整位置后，歌词留在原地，不回正」。
 *
 * ## 本版的做法：把「计时」与「回正」绑在**同一次**用户交互上
 *
 * 每次用户**碰**面板（按下 / 抬起）就推进一个 [nextGeneration] 世代号，
 * 世代号是唯一的计时 key。计时到点时：
 *
 * 1. 手指还按着（[shouldRecenter] 的 `pointerDown`）⇒ **放弃这一次**，
 *    等抬起时那个新世代重新计时 —— 这就是「不打断用户正在进行的拖拽」；
 * 2. 否则 ⇒ 真的把当前行滚回目标位置（横屏是视口正中），**并且**清掉
 *    「别跟着我」的旗子，让跨行跟随立刻恢复。
 *
 * ## 为什么用「世代号」而不是一个布尔量 + 反复重启协程
 *
 * Compose 的 `LaunchedEffect(key)` 在 key 变化时会**取消上一个协程**。
 * 用 `MutableState<Int>` 当 key、每次交互 +1，就天然得到「最后一次交互之后 5 秒」的语义，
 * 不需要手写 `cancel()`/`Job` 管理，也不会出现两个计时器同时在跑。
 *
 * ## 有界性（铁律 8）
 *
 * 计时器**不是循环**：一个世代只 `delay` 一次、只做一次决定，然后协程自然结束。
 * 世代号是 Int 且**刻意不回绕到 0**（[nextGeneration]）—— `0` 在本文件里是
 * 「用户从没碰过」的哨兵值，一旦回绕到 0，计时器就永远不会再被安排，
 * 功能会**静默失效**（而不是出错），这是最难发现的一类退化。
 */
object LyricsAutoCenter {

    /**
     * 无触碰多久之后回正：**5 秒**（任务书 7.2 的定值）。
     *
     * 与 v2.2.1 那句 `delay(5000)` 是同一个数 —— 本版没有改时长，
     * 改的是「到点之后真的做事」。
     */
    const val IDLE_TIMEOUT_MS = 5_000L

    /** 「用户从没碰过面板」。计时器在这个世代**不安排**（不能无缘无故把歌词拽走）。 */
    const val GENERATION_NEVER_TOUCHED = 0

    /**
     * 交互世代号 +1。
     *
     * 从 [GENERATION_NEVER_TOUCHED] 起步；`Int.MAX_VALUE` 之后回到 **1** 而不是 0 ——
     * 见类文档「有界性」一节。这个函数是**纯的**（同输入同输出），所以回绕边界可单测。
     */
    fun nextGeneration(current: Int): Int {
        if (current < GENERATION_NEVER_TOUCHED) return 1
        if (current >= Int.MAX_VALUE) return 1
        return current + 1
    }

    /**
     * 是否要为这个世代安排计时器。
     *
     * 只有「用户碰过」才安排。这一条挡住了「进播放器就自己滚一下」这种副作用：
     * 面板第一次出现时的定位已经由 `LaunchedEffect(lines)` / `isVisible` /
     * `forcedScrollTrigger` 三条既有路径负责，不需要第四条。
     */
    fun shouldSchedule(generation: Int): Boolean = generation > GENERATION_NEVER_TOUCHED

    /**
     * 计时到点，是否真的执行回正。
     *
     * @param pointerDown 到点这一刻用户的手指是否还按在面板上
     * @param lineCount 歌词行数（0 行无从定位）
     * @param enabled 面板是否允许交互（折叠态 / 不可见时为 false）
     * @return true = 执行回正
     */
    fun shouldRecenter(pointerDown: Boolean, lineCount: Int, enabled: Boolean): Boolean {
        if (!enabled) return false
        if (lineCount <= 0) return false
        // 手指还按着 ⇒ 放弃这一次。抬起时 nextGeneration 会重新安排，
        // 所以「拖拽中不打断」与「拖完 5 秒后仍然回正」两件事同时成立。
        if (pointerDown) return false
        return true
    }
}
