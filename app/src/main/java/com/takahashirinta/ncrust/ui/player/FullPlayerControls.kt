/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（Bug1「音质切换」/ A3「降级语义」）：
 *   - 新增 qualityStatusFlow：音质标签按**实际文件参数**（br/type）追加角标 ——
 *     「已降级」/「无权限」/「该曲无此档位」，不再把"其实在播 Hi-Res"误报成降级。
 */

package com.takahashirinta.ncrust.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.formatDuration
import com.takahashirinta.ncrust.player.QualityStatus
import com.takahashirinta.ncrust.player.SongUrlFetcher
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import io.github.takahashirinta.kanesumi.controls.MetroSelectorFlyout
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.flow.StateFlow

@Composable
fun FullPlayerControls(
    isPlaying: Boolean,
    showLyrics: Boolean,
    showQueue: Boolean,
    progressFlow: StateFlow<Float>,
    positionFlow: StateFlow<Long>,
    durationFlow: StateFlow<Long>,
    qualityIndexFlow: StateFlow<Int>,
    qualityStatusFlow: StateFlow<QualityStatus>,
    qualityOptions: List<String>,
    onPlayPause: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onAddToLibrary: () -> Unit = {},
    // 当前歌是否已在收藏库: true 显示对号, 按下走"移出库"分支
    isInLibrary: Boolean = false,
    isBufferingFlow: StateFlow<Boolean>,
    onSeek: (Float) -> Unit = {},
    lyricsUnavailable: Boolean = false,
    previousEnabled: Boolean = true,
    // 宽屏左栏用紧凑尺寸：缩小按钮/间距，把纵向空间让给封面区。
    compact: Boolean = false,
    // 宽屏横向布局：进度行 + 操作行扁平铺开，替代手机的竖向大按钮堆叠。
    landscape: Boolean = false,
    // P1：大屏模式把音质控件搬到左栏（就地选择器）后，控制条右端不再重复画一个
    // 「点一下跳设置页」的音质角标 —— 同一屏里两个音质入口会互相打架。
    showQuality: Boolean = true,
    // P1：大屏幕模式（横屏桌面播放器布局）。true 时按钮图标换成"退出大屏"。
    bigScreen: Boolean = false,
    /**
     * P1：大屏幕模式入口/出口（同一个按钮、同一个槽位）。
     *
     * 为什么放在这条操作行里而不是屏幕右上角：
     *  - 这一行在展开态子树里（折叠态整棵不挂载，符合 B-1 的命中区契约），
     *    并且整块控制条共用 progress>0.7 的淡入，入口天然"只在展开且有歌时出现"；
     *  - 右上角在横屏大屏下是歌词面板的 A-/A+ 字号按钮，放那里会互相抢命中区。
     */
    onToggleBigScreen: () -> Unit = {},
    /**
     * v2.5.5 · E：**平板**上要不要在横向控件条里补一个大屏幕模式入口（⤢）。
     *
     * 默认 `false` ⇒ 手机横屏与既有调用点一行都不用改；判据在
     * [PlayerLayout.bigScreenEntrySlot]（纯函数 + A/B 矩阵，有单测）。
     *
     * 为什么挂在**左组末尾**（歌词 / 队列 / 收藏 之后）而不是 `trailing` 槽位：
     * `trailing` 在 `showQuality = true` 时被音质选择器占着，而横向控件条上
     * `showQuality` 恒为真（大屏模式才把它搬走）；挂在 `trailing` 会与音质入口抢同一个位置。
     */
    showBigScreenEntry: Boolean = false,
    /**
     * v1.8.0 · T4：应用内「自动旋转」开关的当前值。
     *
     * 图标语义只有两种状态（跟随传感器 / 锁定方向），所以这里传布尔而不是三态 ——
     * 事实源是 RotationSetting，播放器图标与设置页开关读写同一份值。
     */
    autoRotate: Boolean = false,
    /** v1.8.0 · T4：切换「自动旋转」。 */
    onToggleAutoRotate: () -> Unit = {},
    /**
     * v1.8.0 · T2：音质选择器打开那一刻**现读**的偏好档位下标。
     *
     * 必须现读、不能读 preferredQualityIndex 这个 StateFlow：冷启动后那个流还是默认值，
     * 已实测过"高亮错档"（见 PlayerCard 里 qualityPicker 的注释）。
     */
    preferredQualityIndexProvider: () -> Int = { 0 },
    /** v1.8.0 · T2：选中档位（→ PlayerViewModel.setQualityPreference）。 */
    onQualitySelect: (Int) -> Unit = {},
    /**
     * 横向控制条右端的替代槽位（仅 [showQuality] = false 时使用）。
     *
     * 大屏模式用它放「退出大屏」：**不能**放进左端那组按钮 —— 横向控制条是
     * 「左组贴左 + 传输组居中 + 右组贴右」的三段式，左组加到 4 个按钮后（4×40dp）
     * 在窄的右栏里会顶到居中的传输组，实测 PCL110 横屏下与「上一首」重叠。
     * 右端正好是音质角标让出来的空位。
     */
    trailing: (@Composable () -> Unit)? = null
) {
    val strings = LocalStrings.current
    // v1.8.0 · T2：音质选择器弹层的高度上限。横屏可用高只有 ~360dp，8 档 × 44dp = 352dp
    // 会顶出屏幕（PCL110 实测第 8 档被裁）；竖屏按 60% 屏高留白，不把播放器上下文全遮住。
    // 与 PlayerCard 大屏那份同一口径（那里另有更精确的窗口高取值）。
    val qualityPickerMaxHeightDp =
        minOf(400.dp, LocalConfiguration.current.screenHeightDp.dp * 0.6f)
    // 触觉反馈:播放/暂停/切歌/开关面板给一个轻振,补足无 ripple 时代的确认感
    val haptic = LocalHapticFeedback.current
    fun tick() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)

    val hPad = if (compact) 16.dp else 24.dp
    val bottomPad = if (compact) 8.dp else 32.dp
    val gapTight = if (compact) 4.dp else 8.dp
    val gapLoose = if (compact) 8.dp else 16.dp
    val ctrlRowPad = if (compact) 2.dp else 16.dp
    val sideBtn = if (compact) 44.dp else 60.dp
    val sideIcon = if (compact) 30.dp else 40.dp
    val playBtn = if (compact) 58.dp else 84.dp
    val playIcon = if (compact) 38.dp else 56.dp
    val sideGap = if (compact) 20.dp else 32.dp
    val toggleBtn = if (compact) 44.dp else 56.dp
    val toggleIcon = if (compact) 26.dp else 32.dp

    if (landscape) {
        // 宽屏横向控件条：进度行（位置 · 进度 · 时长）+ 操作行（面板开关 · 音质 · 传输），
        // 扁平铺开，不再照搬手机的竖向大按钮堆叠。
        val lPad = if (compact) 12.dp else 20.dp
        val lTop = if (compact) 2.dp else 4.dp
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = lPad, vertical = lTop)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PositionText(positionFlow = positionFlow, modifier = Modifier)
                Spacer(Modifier.width(8.dp))
                SlimProgressBar(
                    progressFlow = progressFlow,
                    durationFlow = durationFlow,
                    isBufferingFlow = isBufferingFlow,
                    onSeek = onSeek,
                    modifier = Modifier.weight(1f),
                    horizontalPadding = 0.dp,
                )
                Spacer(Modifier.width(8.dp))
                DurationText(durationFlow = durationFlow, modifier = Modifier)
            }
            Spacer(Modifier.height(6.dp))
            // v2.5.6 · P1：三段式控制条。
            //
            // ## 为什么从 `Box` + 三个 `align` 的 Row 改成**一条顺序 `Row`**
            //
            // 旧写法（v2.5.5）是：
            //     Box(fillMaxWidth) {
            //         Row(align(CenterStart)) { 左组 }   // 歌词/队列/收藏/[⤢]
            //         Row(align(Center))      { 传输三键 }
            //         if (showQuality) PlayerQualityChip(align(CenterEnd))
            //     }
            // 三个子节点**互相独立定位**，容器一窄就必然叠在一起，而 Compose 里
            // **后声明的赢命中测试** ⇒ 居中的传输组盖住左组第 4 个按钮（⤢）。
            //
            // WGR-W09 竖屏真机实测（`docs/verification/v2.5.6/probe-tablet-rotate.md`）：
            // 容器 352dp、左组 8~168dp、传输组 103~247dp，重叠 65dp，
            // ⤢ 的槽位（128~168dp）100% 在重叠区里 —— 点它触发的是「播放/暂停」。
            // 横屏容器 563dp 不重叠，所以这个缺陷**只在竖屏出现**。
            //
            // 顺序 `Row` 的子节点是**依次摆放**的，结构上不可能重叠 ——
            // 这比「算准一个阈值别让它叠」可靠：阈值会随按钮增减/字号/语言漂移，
            // 而顺序布局的这条性质不随任何东西漂移。
            //
            // 让位规则见 [PlayerLayout.qualityChipFits]：左组（含 ⤢）与传输组不可让，
            // 让的是音质片。真机上平板竖屏会因此不挂音质片（它在竖屏控制条里仍在），
            // 平板横屏（563dp）则照旧挂载。
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val bigScreenEntryVisible = showBigScreenEntry
                val qualityFits = PlayerLayout.qualityChipFits(
                    availableWidthDp = maxWidth.value,
                    sideButtonCount = PlayerLayout.sideButtonCount(bigScreenEntryVisible),
                )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable(enabled = !lyricsUnavailable) {
                                tick()
                                onToggleLyrics()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = Icons.Default.Lyrics,
                            contentDescription = strings.lyricsButton,
                            tint = if (lyricsUnavailable) LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.5f)
                            else if (showLyrics) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                            sizeDp = 24.dp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable {
                                tick()
                                onToggleQueue()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                            contentDescription = strings.queueButton,
                            tint = if (showQueue) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                            sizeDp = 24.dp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clickable {
                                tick()
                                onAddToLibrary()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = if (isInLibrary) Icons.Default.Check else Icons.Default.Add,
                            contentDescription = strings.addToLibraryButton,
                            tint = if (isInLibrary) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                            sizeDp = 24.dp
                        )
                    }
                    // v2.5.5 · E：平板上的大屏幕模式入口。判据见 PlayerLayout.bigScreenEntrySlot
                    // （只有「平板 + 不在大屏」两格挂载；手机横屏逐格不变）。
                    // 40dp 与同一行另外三个按钮一致；图标语义与竖屏那一处逐字相同
                    // （进入 ⤢ / 退出 ⤡，退出态在 `trailing`，不会同时出现两个）。
                    if (showBigScreenEntry) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clickable {
                                    tick()
                                    onToggleBigScreen()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            MetroIcon(
                                imageVector = Icons.Default.OpenInFull,
                                contentDescription = strings.bigScreenEnter,
                                tint = LocalMetroColors.current.onBackground,
                                sizeDp = 24.dp
                            )
                        }
                    }
                }
                // 中组：吃掉左组与右组之外的剩余空间，三键在**这段空间内**居中。
                // 用 `weight(1f)`（参与顺序布局、拿到的是一段确定的空间）而不是
                // `align(Center)`（相对整个 Box 独立定位）—— 后者正是重叠的来源。
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable(enabled = previousEnabled) {
                                tick()
                                onPlayPrevious()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = strings.prevButton,
                            tint = if (previousEnabled) LocalMetroColors.current.onBackground else LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.5f),
                            sizeDp = 28.dp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clickable {
                                tick()
                                onPlayPause()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) strings.pauseButton else strings.playButton,
                            tint = LocalMetroColors.current.onBackground,
                            sizeDp = 36.dp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clickable {
                                tick()
                                onPlayNext()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        MetroIcon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = strings.nextButton,
                            tint = LocalMetroColors.current.onBackground,
                            sizeDp = 28.dp
                        )
                    }
                }
                // 右组。v2.5.6 · P1：音质片只在**放得下**时挂载，判据见
                // `PlayerLayout.qualityChipFits`。放不下时让位的是它 ——
                // 左组（含 ⤢）与传输三键都不可让（后者是铁律 19 点名的核心功能）。
                if (showQuality && qualityFits) {
                    // v1.8.0 · T2：宽屏竖屏（平板/折叠展开）的控制条右端也改成就地选择器，
                    // 与竖屏 / 横屏大屏两处共用同一个组件。
                    PlayerQualityChip(
                        qualityIndexFlow = qualityIndexFlow,
                        qualityStatusFlow = qualityStatusFlow,
                        options = qualityOptions,
                        preferredIndexProvider = preferredQualityIndexProvider,
                        onSelect = onQualitySelect,
                        maxHeightDp = qualityPickerMaxHeightDp,
                        horizontalAlignment = Alignment.End,
                    )
                } else if (trailing != null) {
                    Box { trailing() }
                }
            }
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = bottomPad)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = hPad)
        ) {
            // 位置文本抽出为叶子 Composable：仅它随 250ms 位置更新重组，
            // 不牵连按钮/进度条区域
            PositionText(
                positionFlow = positionFlow,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            // v1.8.0 · T2：竖屏也做成**就地选择器**（此前这个 chip 点了是"跳设置页"，
            // 用户得离开播放器才能换档）。菜单形态与横屏大屏共用同一个组件，
            // 档位表、降级角标语义、"档位真变了才重取播放链"的行为天然一致。
            PlayerQualityChip(
                qualityIndexFlow = qualityIndexFlow,
                qualityStatusFlow = qualityStatusFlow,
                options = qualityOptions,
                preferredIndexProvider = preferredQualityIndexProvider,
                onSelect = onQualitySelect,
                modifier = Modifier.align(Alignment.Center),
                maxHeightDp = qualityPickerMaxHeightDp,
                horizontalAlignment = Alignment.CenterHorizontally,
            )
            DurationText(
                durationFlow = durationFlow,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        Spacer(Modifier.height(gapTight))
        SlimProgressBar(
            progressFlow = progressFlow,
            durationFlow = durationFlow,
            isBufferingFlow = isBufferingFlow,
            onSeek = onSeek
        )
        Spacer(Modifier.height(gapLoose))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = ctrlRowPad),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(sideBtn)
                    .clickable(enabled = previousEnabled) {
                        tick()
                        onPlayPrevious()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = strings.prevButton,
                    tint = if (previousEnabled) LocalMetroColors.current.onBackground else LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.5f),
                    sizeDp = sideIcon
                )
            }
            Spacer(Modifier.width(sideGap))
            Box(
                modifier = Modifier
                    .size(playBtn)
                    .clickable {
                        tick()
                        onPlayPause()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) strings.pauseButton else strings.playButton,
                    tint = LocalMetroColors.current.onBackground,
                    sizeDp = playIcon
                )
            }
            Spacer(Modifier.width(sideGap))
            Box(
                modifier = Modifier
                    .size(sideBtn)
                    .clickable {
                        tick()
                        onPlayNext()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = strings.nextButton,
                    tint = LocalMetroColors.current.onBackground,
                    sizeDp = sideIcon
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Box(
                modifier = Modifier
                    .size(toggleBtn)
                    .clickable(enabled = !lyricsUnavailable) {
                        tick()
                        onToggleLyrics()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = Icons.Default.Lyrics,
                    contentDescription = strings.lyricsButton,
                    // 无歌词/未加载时置灰, 语义是该歌不可展开歌词
                    tint = if (lyricsUnavailable) LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.5f)
                    else if (showLyrics) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                    sizeDp = toggleIcon
                )
            }
            Box(
                modifier = Modifier
                    .size(toggleBtn)
                    .clickable {
                        tick()
                        onToggleQueue()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = strings.queueButton,
                    tint = if (showQueue) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                    sizeDp = toggleIcon
                )
            }
            Box(
                modifier = Modifier
                    .size(toggleBtn)
                    .clickable {
                        tick()
                        onAddToLibrary()
                    },
                contentAlignment = Alignment.Center
            ) {
                // 已在库 → 对号(按动移出); 不在库 → 加号(按动收藏)
                MetroIcon(
                    imageVector = if (isInLibrary) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = strings.addToLibraryButton,
                    tint = if (isInLibrary) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                    sizeDp = toggleIcon
                )
            }
            // P1：大屏幕模式入口。同一槽位在横屏大屏下变成"退出大屏"。
            Box(
                modifier = Modifier
                    .size(toggleBtn)
                    .clickable {
                        tick()
                        onToggleBigScreen()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = if (bigScreen) Icons.Default.CloseFullscreen else Icons.Default.OpenInFull,
                    contentDescription = if (bigScreen) strings.bigScreenExit else strings.bigScreenEnter,
                    tint = if (bigScreen) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                    sizeDp = toggleIcon
                )
            }
            // v1.8.0 · T4：自动旋转开关（竖屏入口）。
            // 开 = 跟随传感器（在播放器里转横屏 → 自动进大屏；转回竖屏 → 自动退出）；
            // 关 = 锁定竖屏，进出大屏只走 ⤢ 按钮。图标用"两态"而不是"高亮/置灰"：
            // 高亮只能表达开/关，表达不了"开着的时候会跟随旋转"。
            RotationToggleButton(
                autoRotate = autoRotate,
                onToggle = {
                    tick()
                    onToggleAutoRotate()
                },
                size = toggleBtn,
                iconSize = toggleIcon,
                contentDescription = if (autoRotate) strings.autoRotateOn else strings.autoRotateOff,
            )
        }
    }
}

/**
 * v1.8.0 · T4：「自动旋转」图标开关（竖屏控制栏 / 大屏左栏共用）。
 *
 * 抽成独立 composable 的原因：两处的尺寸不同、位置不同，但图标与配色语义必须一致
 * —— 同一功能出现两种样子，用户会以为是两个开关。
 *
 * 触摸契约（AGENTS.md 第 7 条）：视觉是 [iconSize] 的图标，命中盒是 [size]，
 * 调用方传的竖屏值 56dp / 26dp —— **命中区只增不减**。外层显式加 semantics：
 * MetroIcon 只画图，不给语义的话 TalkBack 摸不到这个开关。
 */
@Composable
fun RotationToggleButton(
    autoRotate: Boolean,
    onToggle: () -> Unit,
    size: Dp,
    iconSize: Dp,
    contentDescription: String,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clickable { onToggle() }
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        MetroIcon(
            imageVector = if (autoRotate) Icons.Default.ScreenRotation else Icons.Default.ScreenLockPortrait,
            contentDescription = null, // 语义在外层 Box 上，避免 TalkBack 读两遍
            tint = if (autoRotate) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
            sizeDp = iconSize
        )
    }
}

/**
 * v1.8.0 · T2：音质 chip + 二级选择器（竖屏控制栏 / 宽屏控制条 / 横屏大屏左栏三处共用）。
 *
 * 为什么抽出来：三处要展示的是同一件事 —— 「当前实际档位 + 降级角标」，点开是
 * 「按**偏好**档位高亮的 8 档选择器」。分开实现必然出现「某处角标语义不同 / 某处高亮错档」
 * 这类漂移（v1.0.4 修过一次，见 PlayerQualityLabel 的注释）。
 *
 * 两个容易写错的点：
 *  1. [preferredIndexProvider] 必须在**打开那一刻**现读。冷启动后偏好 StateFlow 还是默认值，
 *     直接拿它当 selectedIndex 会高亮错档（PCL110 实测踩过）。
 *  2. FLAC 设备门控提示放在这里而不是调用点：三处都要，放调用点必然漏一处
 *     （API < 27 没有系统 FLAC 解码器时选无损会被取链阶段静默跳过）。
 *
 * 触摸契约：外层是 >= [minHitHeightDp] 的**命中盒**（视觉 chip 仍是 ~22dp 的小色块，
 * 与 v1.7.0 逐像素一致），并以 semantics 暴露给 TalkBack；菜单走 Kanesumi 的 Popup
 * 选择器，是独立窗口，不受播放器卡片「展开态吞事件」的影响。
 */
@Composable
fun PlayerQualityChip(
    qualityIndexFlow: StateFlow<Int>,
    qualityStatusFlow: StateFlow<QualityStatus>,
    options: List<String>,
    preferredIndexProvider: () -> Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxHeightDp: Dp = 400.dp,
    horizontalAlignment: Alignment.Horizontal = Alignment.End,
    minHitHeightDp: Dp = 48.dp,
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var preferredIndex by remember { mutableIntStateOf(0) }

    Box(
        modifier = modifier.heightIn(min = minHitHeightDp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .background(LocalMetroColors.current.surfaceVariant)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) {
                    // 每次打开都现读一次：网络在 Wi-Fi/移动之间切换过、或刚冷启动时，
                    // 偏好 StateFlow 里的值可能已过期。
                    preferredIndex = preferredIndexProvider()
                    expanded = true
                }
                .padding(horizontal = 8.dp, vertical = 3.dp)
                .semantics { contentDescription = strings.qualitySectionTitle },
        ) {
            PlayerQualityLabel(
                qualityIndexFlow = qualityIndexFlow,
                qualityStatusFlow = qualityStatusFlow,
                options = options
            )
        }
        MetroSelectorFlyout(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            options = options,
            selectedIndex = preferredIndex,
            onSelect = { index ->
                onSelect(index)
                if (shouldHintFlacUnsupported(index)) {
                    Toast.makeText(context, strings.qualityFlacUnsupportedHint, Toast.LENGTH_SHORT)
                        .show()
                }
                expanded = false
            },
            horizontalAlignment = horizontalAlignment,
            maxHeightDp = maxHeightDp,
        )
    }
}

/** API < 27 无系统 FLAC 解码器时，选到无损以上要提示"选了也不会有"（与设置页同一条文案）。 */
private fun shouldHintFlacUnsupported(index: Int): Boolean {
    val level = PlayerViewModel.QUALITY_LEVELS.getOrNull(index) ?: return false
    return !SongUrlFetcher.deviceSupportsFlac && SongUrlFetcher.isFlacTier(level)
}

@Composable
private fun PositionText(positionFlow: StateFlow<Long>, modifier: Modifier) {
    val position by positionFlow.collectAsState()
    MetroText(
        formatDuration(position),
        color = LocalMetroColors.current.onSurfaceVariant,
        style = TextStyle(fontSize = 12.sp),
        modifier = modifier
    )
}

@Composable
private fun DurationText(durationFlow: StateFlow<Long>, modifier: Modifier) {
    val duration by durationFlow.collectAsState()
    MetroText(
        formatDuration(duration),
        color = LocalMetroColors.current.onSurfaceVariant,
        style = TextStyle(fontSize = 12.sp),
        modifier = modifier
    )
}

/**
 * 音质档位 + 状态角标。唯一实现，两处复用：
 *  - 控制条（窄屏底部居中 / 宽屏横向右端）：点一下跳设置页；
 *  - P1 大屏模式左栏：包一层可点盒子 + MetroSelectorFlyout，就地切换档位。
 * 拆成公开 Composable 而不是复制一份，是为了让「实际档位 vs 偏好档位」的角标语义
 * （已降级 / 无权限 / 该曲无此档位）只有一处真相。
 */
@Composable
fun PlayerQualityLabel(
    qualityIndexFlow: StateFlow<Int>,
    qualityStatusFlow: StateFlow<QualityStatus>,
    options: List<String>
) {
    val qualityIndex by qualityIndexFlow.collectAsState()
    val status by qualityStatusFlow.collectAsState()
    val label = options.getOrElse(qualityIndex) { options.getOrElse(3) { "" } }
    // A3：状态按实际文件参数（br/type）判定，不再按 level 序号 —— 明确区分
    // 「无权限」「该曲无此档位」「真实降级」，避免把"其实在播 Hi-Res"或
    // "沉浸声换格式"误报成「已降级」。
    val badge = when (status) {
        QualityStatus.NORMAL -> null
        QualityStatus.DOWNGRADED -> LocalStrings.current.qualityDowngradedBadge
        QualityStatus.NO_ENTITLEMENT -> LocalStrings.current.qualityNoEntitlementBadge
        QualityStatus.SONG_LACKS_TIER -> LocalStrings.current.qualitySongLacksTierBadge
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        MetroText(
            label,
            color = LocalMetroColors.current.primary,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis
        )
        // 状态非 NORMAL 时才加后缀，否则用户改了设置却听不出变化，会以为"切换音质失败"。
        if (badge != null) {
            Spacer(Modifier.width(4.dp))
            MetroText(
                badge,
                color = LocalMetroColors.current.onSurfaceVariant,
                style = TextStyle(fontSize = 10.sp),
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
