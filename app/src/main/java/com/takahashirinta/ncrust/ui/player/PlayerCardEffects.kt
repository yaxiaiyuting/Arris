/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.1 · P0：从 `PlayerCard` 拆出的**全部副作用**（5 个 `LaunchedEffect`）。
 *
 * ## 为什么单独成文件
 *
 * `PlayerCard` 拆分后（release dex 实测）剩下的主体里，这一组 effect 的**调用点 +
 * 闭包捕获**仍占数百 code unit。它们的共同点是：**不产生任何 UI 节点**，
 * 只驱动状态机（写 `ui.*`）与动画轴 —— 所以可以整块搬走而不改变组合结构。
 *
 * ## 行为等价（铁律 25）：key 与顺序逐条保持
 *
 * | # | 原来的 key | 现在的 key |
 * |---|---|---|
 * | 1 | `LaunchedEffect(Unit)`（收起时复位控制栏） | 同 |
 * | 2 | `LaunchedEffect(song?.id, lyricsReady, lyricsLoading, showQueue)` | 同（`song?.id` → `songId`） |
 * | 3 | `LaunchedEffect(bigScreenActive)` | 同 |
 * | 4 | `LaunchedEffect(showLyrics, showQueue)` | 同（`ui.showLyrics` / `ui.showQueue`） |
 * | 5 | `LaunchedEffect(Unit)`（展开时强制歌词定位） | 同 |
 *
 * `autoSwitchedForSong` 是**唯一**随本文件搬走的状态：它原本是
 * `remember(song?.id) { mutableStateOf(false) }`，本函数被 `PlayerCard` **无条件**调用，
 * 所以「每首歌只自动切一次歌词」的语义逐帧不变。
 */

package com.takahashirinta.ncrust.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * `PlayerCard` 的 5 个副作用（逐行搬移，见文件 KDoc 的 key 对照表）。
 *
 * @param songId 当前曲目 id（`song?.id`，**`Long?`**：冷启动恢复的曲目 id 可以是 null，
 *   与拆分前 `remember(song?.id)` 的键域逐值相同）—— 只作为 **key** 使用，不读内容。
 * @param lyricsReady 歌词**属于当前歌**且非加载中（旧歌词残留不算就绪）。
 * @param lyricsLoading 歌词正在加载。
 * @param bigScreenActive 大屏模式生效态（用户意图 ∧ 窗口已横过来）。
 */
@Composable
internal fun PlayerCardEffects(
    ui: PlayerCardUiState,
    progress: Animatable<Float, AnimationVector1D>,
    songId: Long?,
    lyricsReady: Boolean,
    lyricsLoading: Boolean,
    bigScreenActive: Boolean,
) {
    // 收起状态不持久化：离开全屏（卡片回到折叠态）即复位为展开。
    LaunchedEffect(Unit) {
        snapshotFlow { progress.value <= 0.01f }
            .distinctUntilChanged()
            .collect { collapsed -> if (collapsed) ui.controlsCollapse.snapTo(0f) }
    }

    // ---- 歌词可达性驱动的「大封面 ↔ 歌词视图」自动切换 ----
    // 每首歌只自动切到歌词视图一次；用户手动关掉歌词后，不再被自动打开
    // （否则关歌词时 showLyrics 被 effect 立刻改回 true，wideSplit 回不到 0，
    //  表现为"封面/控件都不动"）。
    var autoSwitchedForSong by remember(songId) { mutableStateOf(false) }

    LaunchedEffect(songId, lyricsReady, lyricsLoading, ui.showQueue) {
        when {
            ui.showQueue -> {
                // 用户在队列：不打扰, 放弃自动回切
            }
            // 歌词就绪 → 首次自动切到歌词视图（封面缩小/左移）
            lyricsReady -> {
                if (!autoSwitchedForSong) {
                    autoSwitchedForSong = true
                    if (!ui.showLyrics) ui.showLyrics = true
                }
            }
            // 加载中：保持当前视图，不清空也不切回大封面 —— 切歌时封面完全不动。
            lyricsLoading -> {
            }
            // 确无歌词（加载完成且为空）→ 回大封面
            else -> if (ui.showLyrics) ui.showLyrics = false
        }
    }

    // P1：大屏模式右栏常驻面板。若用户此前两个面板都没开（例如该曲无歌词、或手动关了
    // 歌词），进大屏后右栏会是一整块黑。这里只在"大屏 + 两个面板都关"时打开歌词面板
    // （歌词为空时面板自己显示「暂无歌词」，比空白可读）。退出大屏不改回 —— 与既有的
    // "歌词就绪自动切歌词"行为一致，不引入第二套面板状态。
    LaunchedEffect(bigScreenActive) {
        if (bigScreenActive && !ui.showLyrics && !ui.showQueue) ui.showLyrics = true
    }

    LaunchedEffect(ui.showLyrics, ui.showQueue) {
        when {
            ui.showLyrics -> {
                // 与封面缩小并行
                launch {
                    ui.lyricAnimProgress.animateTo(
                        1f, tween(190, easing = FastOutSlowInEasing)
                    )
                }
                // 若当前 queueSlideProgress > 0（来自列表模式），横滑回歌词位置
                if (ui.queueSlideProgress.value > 0.01f) {
                    ui.queueSlideProgress.animateTo(
                        0f, tween(260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                    )
                }
            }
            ui.showQueue -> {
                launch {
                    ui.lyricAnimProgress.animateTo(
                        1f, tween(190, easing = FastOutSlowInEasing)
                    )
                }
                when {
                    // 已在列表位置（或正在返回），无需再动
                    ui.queueSlideProgress.value > 0.99f -> {}
                    // 来自稳定歌词模式（lyricAnimProgress 已是 1）→ 横滑
                    ui.lyricAnimProgress.value > 0.95f -> {
                        ui.queueSlideProgress.animateTo(
                            1f, tween(260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                        )
                    }
                    // 来自大封面模式 → 不横滑，仅随封面缩小淡入
                    else -> ui.queueSlideProgress.snapTo(1f)
                }
            }
            else -> {
                // 切回大封面：等封面展开完成，内容随 lyricAnimProgress 自然淡出
                ui.lyricAnimProgress.animateTo(0f, tween(300, easing = LinearOutSlowInEasing))
                // 封面展开后重置滑动位置，为下次 b→c 准备
                ui.queueSlideProgress.snapTo(0f)
            }
        }
    }

    // 展开动作触发一次歌词定位: 面板常挂载, isVisible 不会翻转, 若不做强制定位,
    // 从 mini bar 拉起后歌词停在旧位置(或用户上次手动滚动的位置)
    LaunchedEffect(Unit) {
        snapshotFlow { progress.value }
            .distinctUntilChanged { a, b -> (a > 0.9f) == (b > 0.9f) }
            .collect { p ->
                if (p > 0.9f) ui.lyricLocateTrigger++
            }
    }
}
