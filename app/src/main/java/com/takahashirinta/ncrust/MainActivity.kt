/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（Bug2「我喜欢的歌无法全部加入播放列表」）：
 *   - ④ 接线 LibraryScreen 的 onPlayAllLiked：v1.3.0 用 loadAllLikedSongs 补齐**整个**红心歌单
 *        后弹 PlayAllDialog 二次确认；v1.4.0 改为「本地缓存立即开播 + 后台补齐追加队尾」，
 *        点击即出声，不再出现"数秒无反馈 + 冗余二次确认"。
 */

package com.takahashirinta.ncrust
import com.takahashirinta.ncrust.ui.player.TrayLayout
import com.takahashirinta.ncrust.ui.player.VisualizerSetting
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors

import android.Manifest
import android.widget.Toast
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.OrientationEventListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
// v2.5.0 · D：「添加到下一首播放」。**不能**复用 PlaylistAdd / PlaylistPlay ——
// 那两个图标在本菜单里已经分别是「加入歌单」与「插播」，同一个菜单里两个不同动作
// 用同一个图标是实打实的误导。QueuePlayNext 的语义（排队并下一个播）正好。
import androidx.compose.material.icons.filled.QueuePlayNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.PlaylistEditApi
import com.takahashirinta.ncrust.network.PlaylistWriteResult
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.reco.ArtistReco
import com.takahashirinta.ncrust.source.AlbumNav
import com.takahashirinta.ncrust.source.AlbumNavigator
import com.takahashirinta.ncrust.source.ArtistNav
import com.takahashirinta.ncrust.source.ArtistNavigator
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.musicSource
import com.takahashirinta.ncrust.source.trackKey
import com.takahashirinta.ncrust.player.PlayOrigin
import com.takahashirinta.ncrust.player.PlaybackStateManager
import com.takahashirinta.ncrust.player.QueueKeys
import com.takahashirinta.ncrust.player.ShuffleRound
import com.takahashirinta.ncrust.player.ShuffleNav
import com.takahashirinta.ncrust.power.BackgroundActivity
import com.takahashirinta.ncrust.ui.components.BackgroundActivityDialog
import io.github.takahashirinta.kanesumi.structure.bottomnav.MetroBottomNav
import io.github.takahashirinta.kanesumi.structure.bottomnav.MetroBottomNavItem
import io.github.takahashirinta.kanesumi.structure.sidebar.MetroSidebar
import io.github.takahashirinta.kanesumi.structure.sidebar.MetroSidebarItem
import com.takahashirinta.ncrust.ui.components.AddToPlaylistResult
import com.takahashirinta.ncrust.local.LocalPlaylistRepository
import com.takahashirinta.ncrust.local.LocalPlaylistStore
import com.takahashirinta.ncrust.ui.components.AddToPlaylistSheet
import com.takahashirinta.ncrust.ui.components.LocalPlaylistPickerDialog
import com.takahashirinta.ncrust.ui.components.CreatePlaylistDialog
import com.takahashirinta.ncrust.ui.components.PlaylistCreateOutcome
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.components.SongMenuSheet
import com.takahashirinta.ncrust.ui.components.TopScrimIconButton
// v2.5.0 · D：应用级 Snackbar（本仓库此前没有任何 Snackbar 设施）。
import com.takahashirinta.ncrust.ui.components.AppSnackbarHost
import com.takahashirinta.ncrust.ui.components.rememberAppSnackbarState
// v2.5.0 · D：「添加到下一首播放」的队列边界判定（纯逻辑 + 单测）。
import com.takahashirinta.ncrust.player.QueueInsert
// v2.5.0 · D：Snackbar 要浮在底部导航/mini bar 之上，位置复用既有的叠层留白常量。
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.navigation.MainNavGraph
import com.takahashirinta.ncrust.ui.navigation.NavRoutes
import com.takahashirinta.ncrust.ui.player.PlayerCardOverlay
import com.takahashirinta.ncrust.ui.screen.*
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.getSavedLanguageCode
import com.takahashirinta.ncrust.ui.i18n.saveLanguageCode
import com.takahashirinta.ncrust.ui.i18n.stringsForCode
import com.takahashirinta.ncrust.ui.CustomBackgroundLayer
import com.takahashirinta.ncrust.ui.theme.NcrustTheme
import com.takahashirinta.ncrust.ui.theme.ThemeMode
import com.takahashirinta.ncrust.ui.theme.AccentSource
import com.takahashirinta.ncrust.ui.theme.getSavedAccentSource
import com.takahashirinta.ncrust.ui.theme.saveAccentSource
import com.takahashirinta.ncrust.ui.theme.getSavedThemeIndex
import com.takahashirinta.ncrust.ui.theme.processAccentColor
import com.takahashirinta.ncrust.ui.theme.systemAccentColor
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.viewmodel.compose.viewModel
import com.takahashirinta.ncrust.ui.theme.getSavedThemeMode
import com.takahashirinta.ncrust.ui.theme.PageTransitionSetting
import com.takahashirinta.ncrust.ui.theme.saveThemeIndex
import com.takahashirinta.ncrust.ui.theme.saveThemeMode
import com.takahashirinta.ncrust.ui.theme.themeColorForIndex
import com.takahashirinta.ncrust.ui.theme.toMetroColors
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import io.github.takahashirinta.kanesumi.anim.sokuou.metroViewConfiguration
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroTheme
import com.takahashirinta.ncrust.warmup.AppWarmup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /**
     * B2-D：系统强调色会在用户换壁纸/换主题后变化，系统此时会重发 Configuration。
     * 这里只维护一个"第几次读取"的计数器 —— 颜色本身永远从当前 Context 现读，
     * 绝不缓存成 Activity 级字段/单例，否则换壁纸后拿到的还是旧色。
     */
    private val systemAccentTick = mutableIntStateOf(0)

    // ---------- P1 · 大屏幕模式（横屏桌面播放器布局） ----------
    /**
     * 大屏模式总开关（用户意图）。布局侧只读这个值 + 当前窗口方向，见
     * [com.takahashirinta.ncrust.ui.player.PlayerLayout.isBigScreenActive]。
     *
     * 放在 Activity 而不是 MainScreen：方向策略在 Activity 层
     * （[applyOrientationPolicy] / [onConfigurationChanged]），两边必须看同一个事实源 ——
     * 只用 Compose 状态的话，onConfigurationChanged 里读不到最新值，会把横屏锁回竖屏。
     */
    private val bigScreenMode = mutableStateOf(false)

    /** 进入大屏后是否已把方向从「强制横屏」放宽成 SENSOR（避免重复设置 requestedOrientation）。 */
    private var bigScreenOrientationRelaxed = false

    /** 物理朝向门控：只有设备真的横过来了才放宽成 SENSOR，见 [startBigScreenOrientationGate]。 */
    private var bigScreenOrientationGate: OrientationEventListener? = null

    // ---------- v1.8.0 · T4：自动旋转（双向） ----------
    /**
     * 应用内「自动旋转」开关的镜像。**唯一事实源是 [RotationSetting]**，这里只是
     * 让非 Compose 的 [applyOrientationPolicy] 能同步读到值（onConfigurationChanged 里
     * 读不到 Compose 状态）。
     */
    private var autoRotateEnabled = RotationSetting.DEFAULT_ENABLED

    /**
     * 全屏播放器是否处于展开态。由 `MainScreen` 的 progress 派生后回传（见那里的
     * snapshotFlow）—— **自动进大屏的触发范围必须限定在播放器界面**，
     * 首页 / 库 / 搜索转横屏不能进大屏。
     */
    private val playerExpanded = mutableStateOf(false)

    /** T1：大屏模式是否已经进入沉浸式（避免重复 hide/show 触发无谓的 inset 重算）。 */
    private var immersiveApplied = false

    /** T1：期望的沉浸式状态。用于窗口重新获得焦点后幂等重放（见 [onWindowFocusChanged]）。 */
    private var immersiveDesired = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手机锁竖屏、大屏(平板/折叠展开/车机)放开方向。见 applyOrientationPolicy。
        applyOrientationPolicy()
        // v1.8.0：设置项的进程内镜像要在任何 UI 读它之前就位（同步读 SharedPreferences，
        // 之后只走内存）。缺了这一步，播放器里的旋转图标/可视化会先按默认值渲染一帧。
        VisualizerSetting.read(this)
        // v2.0.0 · T2（HF2）：禁止熄屏开关同理 —— KeepScreenOnSetting.state 是进程内镜像，
        // 只在**设置页**里读过盘。用户改完关掉之后如果不再进设置页（或进程重启后直接开播），
        // 播放器读到的还是默认值 true，开关等于失效。真机（S6）实测确认过这个 bug：
        // prefs 里 keep_screen_on=false，但播放时 flag 照样被挂上。
        KeepScreenOnSetting.read(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        RetrofitClient.init(this)
        // v2.1.0 · C：接线 QQ 音乐音源（注册 Provider + 初始化它自己的 HTTP 通道）。
        // 与 RetrofitClient.init 并列，幂等。
        com.takahashirinta.ncrust.qq.QqMusicSourceProvider.install(this)
        // v2.2.0：QQ 歌单仓库（只读同步 + 私有目录缓存）。与上面并列、幂等。
        // **它自己不发起任何请求**，只是拿一个 application context 用来读写缓存；
        // 真正的网络调用只在用户打开歌单页/点刷新时发生（本版不做自动刷新）。
        com.takahashirinta.ncrust.qq.QqPlaylistRepository.init(this)
        // 冷启动即刻并发启动预热：Home 三条网络 + 封面 Coil 预取。
        // splash 期间跑完，进入主页时 ContentCache 已就位，无 loader 闪烁。
        AppWarmup.start(this)
        enableEdgeToEdge()
        setContent {
            // ---------- v1.8.0 · T4：自动旋转 / 双向进出大屏 ----------
            // 订阅范围刻意收在一个小组件里（见 AutoRotateWatcher）：把
            // 「播放器展开态」这种每次展开/收起都会翻转的状态读在根作用域，会让整个
            // Activity 内容（含 MainScreen）跟着重组一次。
            val autoRotate = RotationSetting.state.value
            AutoRotateWatcher(
                playerExpanded = playerExpanded,
                onAutoEnter = { enterBigScreenMode(auto = true) },
            )

            // 开关一变立刻作用到方向策略：用户不需要重启 App，也不用手动重进播放器。
            LaunchedEffect(autoRotate) {
                autoRotateEnabled = autoRotate
                // v2.0.0 · T1-A：**在大屏模式里**拨这个开关时，朝向门控必须跟着收敛，
                // 否则两个方向都会错：
                //  · 关 → 开：门控没在跑（进大屏时 auto-rotate 关，见 enterBigScreenMode），
                //    relaxed 永远停在 false ⇒ 方向被钉死在 SENSOR_LANDSCAPE，
                //    用户明明开了"跟随手机"却转不回竖屏；
                //  · 开 → 关：门控还在跑，设备横向时它会**直接**把 requestedOrientation
                //    写成 SENSOR（绕过 applyOrientationPolicy），正好抵消"保持横屏"。
                if (bigScreenMode.value) {
                    if (autoRotate && !bigScreenOrientationRelaxed) startBigScreenOrientationGate()
                    else if (!autoRotate) stopBigScreenOrientationGate()
                }
                applyOrientationPolicy()
                Log.i(TAG, "auto-rotate setting = $autoRotate")
            }

            // ---------- v1.8.0 · T1：横屏大屏模式沉浸式（隐藏状态栏） ----------
            // 判定条件与 PlayerCard 的 bigScreenActive 完全一致（意图 + 窗口真的横过来），
            // 避免"按钮刚点、窗口还没转"的那一两百毫秒里状态栏先消失。
            ImmersiveEffect(
                bigScreen = bigScreenMode.value,
                onImmersiveChange = { applyImmersive(it) },
            )

            var themeIndex by remember {
                mutableIntStateOf(getSavedThemeIndex(this@MainActivity))
            }
            var themeMode by remember {
                mutableStateOf(getSavedThemeMode(this@MainActivity))
            }
            var languageCode by remember {
                mutableStateOf(getSavedLanguageCode(this@MainActivity))
            }
            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }

            // B2-C：主题色来源三选一（预设 / 封面 / 系统）。封面色由 PlaybackService 的
            // Palette 结果静态推送到 ViewModel；系统色仅 API 31+ 可用。
            val playerViewModel: PlayerViewModel = viewModel()
            var accentSource by remember {
                mutableStateOf(getSavedAccentSource(this@MainActivity))
            }
            val coverAccentRgb by playerViewModel.coverAccentRgb.collectAsState()
            // v2.5.0 · A（色调）：封面取色的多角色调色板（深/浅两套，后台已算好）。
            val coverTheme by playerViewModel.coverTheme.collectAsState()
            // B2-D：配置变化或手动刷新都会让 tick +1，触发重新读取系统色。
            val accentTick = systemAccentTick.intValue
            val composeContext = androidx.compose.ui.platform.LocalContext.current
            // 现读：Activity 重建后 composeContext 是新实例，拿到的就是新 Resource 值。
            val systemAccent = remember(composeContext, accentTick) { systemAccentColor(composeContext) }
            // 优先级合并：可用的动态来源覆盖预设色，任一环节拿不到都回落 themeColorForIndex。
            // remember 键刻意含 isDark —— 深/浅色的亮度锚定区间不同，同一张封面必须重算。
            val accentColor = remember(accentSource, themeIndex, coverAccentRgb, isDark, systemAccent) {
                when (accentSource) {
                    AccentSource.COVER -> coverAccentRgb?.let { processAccentColor(it, isDark) }
                        ?: themeColorForIndex(themeIndex)
                    // systemAccentColor 在 API < 31 返回 null（跨设备同步 prefs 的防御），
                    // 拿不到就回落预设色 —— 不静默改变现有颜色。
                    AccentSource.SYSTEM -> systemAccent?.let { processAccentColor(it.toArgb(), isDark) }
                        ?: themeColorForIndex(themeIndex)
                    AccentSource.PRESET -> themeColorForIndex(themeIndex)
                }
            }

            // v2.5.0 · A（色调）：只有「跟随封面」这一档才把调色板交给主题。
            // 预设档与系统档传 null ⇒ NcrustTheme 用固定的、经过 WCAG 校验的默认色板，
            // 行为与本版之前**逐字节一致**（所以这条改动对没开"跟随封面"的用户是零影响）。
            //
            // `forMode(isDark)` 是一次字段选择，不是计算 —— 两套变体在 PlaybackService
            // 的后台线程上就算完了，这里只是挑一套（所以切深浅色立即正确，不用等换歌）。
            val coverPalette = if (accentSource == AccentSource.COVER) coverTheme?.forMode(isDark) else null

            val baseViewConfig = LocalViewConfiguration.current
            val metroConfig = remember(baseViewConfig) { metroViewConfiguration(baseViewConfig) }
            CompositionLocalProvider(
                LocalStrings provides stringsForCode(languageCode),
                // Metro 起始阈值：touchSlop ≈8dp → ≈12dp。按下时的手指微抖不会立即滚动，
                // 消除"神经质"输入印象。配合 MetroFlingBehavior 覆盖 fling 阶段。
                LocalViewConfiguration provides metroConfig,
            ) {
                NcrustTheme(
                    primaryColor = accentColor,
                    isDark = isDark,
                    coverPalette = coverPalette,
                ) {
                    // Kanesumi Metro* 组件读 LocalMetroColors / LocalMetroTypography,
                    // 并通过 MetroTheme 注入的 LocalIndication -> MetroIndication 拿到直角
                    // 闪切反馈。这里从 NcrustColors 派生 MetroColors,让两套主题源共享同一
                    // 组配色 -- Ncrust* 与 Metro* 组件可以并存,视觉一致。
                    val ncrust = LocalNcrustColors.current
                    val metroColors = remember(ncrust, isDark) { ncrust.toMetroColors(isDark) }
                    MetroTheme(colors = metroColors) {
                    var showSplash by remember { mutableStateOf(true) }
                    Box(modifier = Modifier.fillMaxSize()) {
                        MainScreen(
                            themeIndex = themeIndex,
                            onThemeChange = { newIndex ->
                                themeIndex = newIndex
                                saveThemeIndex(this@MainActivity, newIndex)
                            },
                            themeMode = themeMode,
                            onThemeModeChange = { newMode ->
                                themeMode = newMode
                                saveThemeMode(this@MainActivity, newMode)
                            },
                            accentSource = accentSource,
                            onAccentSourceChange = { newSource ->
                                accentSource = newSource
                                saveAccentSource(this@MainActivity, newSource)
                            },
                            onRefreshSystemAccent = { systemAccentTick.intValue++ },
                            onLanguageChange = { newCode ->
                                saveLanguageCode(this@MainActivity, newCode)
                                languageCode = newCode
                                showSplash = true
                            },
                            // P1：大屏模式状态在 Activity（方向策略要用），这里只做双向透传。
                            bigScreen = bigScreenMode.value,
                            onToggleBigScreen = {
                                if (bigScreenMode.value) exitBigScreenMode() else enterBigScreenMode()
                            },
                            // T4：自动旋转开关（设置页与播放器图标共享同一份状态，见 RotationSetting）。
                            autoRotate = autoRotate,
                            onToggleAutoRotate = { RotationSetting.write(this@MainActivity, !autoRotate) },
                            // T4：播放器展开态回传 —— 自动进大屏必须限定在播放器界面。
                            onPlayerExpandedChange = { playerExpanded.value = it },
                            // v2.0.0 · T2：播放中禁止熄屏（窗口 flag 只能由 Activity 持有）。
                            onKeepScreenOnChange = { applyKeepScreenOn(it) }
                        )
                        if (showSplash) {
                            SplashScreen(onFinished = { showSplash = false })
                        }
                    }

                    // 首次启动（splash 结束后）申请后台活动白名单：ColorOS/MIUI 等
                    // 会在熄屏后清理后台，导致播放服务被杀、重进又走一遍 splash。
                    var showBatteryPrompt by remember { mutableStateOf(false) }
                    val batteryPrefs = remember {
                        getSharedPreferences("ncrust_settings", 0)
                    }
                    LaunchedEffect(showSplash) {
                        if (!showSplash &&
                            !batteryPrefs.getBoolean("battery_prompt_done", false) &&
                            !BackgroundActivity.isUnrestricted(this@MainActivity)
                        ) {
                            showBatteryPrompt = true
                        }
                    }
                    if (showBatteryPrompt) {
                        BackgroundActivityDialog(
                            onAllow = {
                                batteryPrefs.edit().putBoolean("battery_prompt_done", true).apply()
                                showBatteryPrompt = false
                                runCatching {
                                    startActivity(BackgroundActivity.requestIntent(this@MainActivity))
                                }.onFailure {
                                    runCatching {
                                        startActivity(BackgroundActivity.appDetailsIntent(this@MainActivity))
                                    }
                                }
                            },
                            onLater = {
                                batteryPrefs.edit().putBoolean("battery_prompt_done", true).apply()
                                showBatteryPrompt = false
                            }
                        )
                    }
                    }  // MetroTheme
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // P1：大屏模式优先。**旋转回竖屏 = 退出大屏模式**（需求里的第二条退出路径），
        // 所以这一支绝不能走 applyOrientationPolicy() 的"按形态锁回去"分支 ——
        // 那正是"横屏被自己锁回竖屏"的根因（大屏期间窗口方向由大屏模式负责）。
        if (BigScreenOrientation.shouldExitOnConfiguration(
                bigScreen = bigScreenMode.value,
                orientationLandscape = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE,
            )
        ) {
            exitBigScreenMode()
        } else {
            // 折叠展开/合拢会改变 smallestScreenWidthDp，需重新判定手机/大屏。
            applyOrientationPolicy()
        }
        // B2-D：配置变化后重新读取系统强调色（部分 ROM 换壁纸只发配置变更，不重启进程）。
        systemAccentTick.intValue++
    }

    override fun onDestroy() {
        stopBigScreenOrientationGate()
        super.onDestroy()
    }

    /**
     * v2.5.4 · C：把 QQ 兜底统计落盘一次。
     *
     * ## 为什么是 `onStop` 而不是每个埋点点位
     *
     * 埋点点位在 `Dispatchers.IO`（解析/取链）与 ExoPlayer 主线程（2Hz 心跳）上，
     * 在那里落盘等于给播放关键路径加 IO（铁律 4）。
     * `onStop` 是「用户离开了这个界面」的时刻：进程随时可能被杀，而它**不在**播放链路上，
     * 一次 `apply()`（异步落盘）成本可忽略。
     *
     * 用 `onStop` 而不是 `onDestroy`：后者在配置变化（旋转）时也会走，
     * 而旋转在本应用里是高频动作；`onStop` 只覆盖「真的到后台了」。
     */
    override fun onStop() {
        runCatching { com.takahashirinta.ncrust.qq.QqProbeStore.snapshotAndFlush(this) }
        // v2.5.5 · B：跨源上报闸门的拦截计数（本地私有目录，绝不上报）。
        // 与 QQ 兜底统计同一时机、同一条纪律：埋点点位只做内存自增，落盘只在
        // 「用户离开界面」与「诊断入口」两处，两处都不在播放关键路径上。
        runCatching { com.takahashirinta.ncrust.player.ReportGateStore.snapshotAndFlush(this) }
        super.onStop()
    }

    /**
     * v1.8.0 · T1：窗口重新获得焦点时把沉浸式状态补回去。
     *
     * 部分 ROM（以及从最近任务/锁屏回来时）会重置窗口的 systemUi 标志，只靠进入大屏那一次
     * hide() 会出现"切出去再回来状态栏又冒出来了"。这里按**期望值**重放一次，幂等。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 强制重放：把"已应用"标记反相，绕过 applyImmersive 的幂等短路
            // （否则从最近任务回来时状态栏被系统恢复、这里却因为"标记说已经藏好了"而不补）。
            immersiveApplied = !immersiveDesired
            applyImmersive(immersiveDesired)
        }
    }

    /**
     * v1.8.0 · T1：横屏大屏模式的沉浸式（隐藏**状态栏**）。
     *
     * 设计取舍（调研结论，写在这里防止被后续"顺手优化"掉）：
     *
     *  - **只隐藏状态栏，不隐藏导航栏**。横屏下状态栏是一条 ~24-32dp 的通栏，
     *    藏掉它封面能多拿这段高度；而导航栏是**两条退出路径的载体** ——
     *    S6（API 24）的三大金刚键里有返回键，PCL110 的手势条是返回手势的起手边。
     *    把导航栏也藏了，用户在大屏里就只剩 ⤢ 按钮一条明确的退出路径。
     *    底部控制条（进度条横向拖拽）也贴着屏幕下沿，隐藏导航栏后从边缘起手的滑动
     *    会先被系统拿去"临时唤出系统栏"，与手势冲突。
     *  - **sticky（BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE）而非 full**：用户从顶部下拉
     *    仍能临时看到状态栏（看时间/电量），几秒后自动收回；不会出现"拉出来就赖着不走、
     *    把大屏布局挤矮一截"。
     *  - **API 24 与 API 30+ 走同一条代码**：`WindowInsetsControllerCompat` 在
     *    androidx.core 内部按版本分派（API 30+ 用 `WindowInsetsController`，
     *    API 24~29 落到 `View.setSystemUiVisibility` + `SYSTEM_UI_FLAG_IMMERSIVE_STICKY`），
     *    不需要在业务代码里手写 `Build.VERSION.SDK_INT` 分支 —— 手写反而容易在某个版本上漏掉。
     */
    private fun applyImmersive(immersive: Boolean) {
        immersiveDesired = immersive
        if (immersiveApplied == immersive) return
        immersiveApplied = immersive
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (immersive) {
            controller.hide(WindowInsetsCompat.Type.statusBars())
            Log.i(TAG, "immersive: status bar hidden")
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
            Log.i(TAG, "immersive: status bar shown")
        }
    }

    /**
     * v2.0.0 · T2：窗口级「禁止熄屏」flag。
     *
     * 用 \`FLAG_KEEP_SCREEN_ON\` 而不是 \`PowerManager.WakeLock\`：
     *  - **零权限**（WakeLock 要 \`WAKE_LOCK\`，本 fork 的红线是不新增敏感权限）；
     *  - flag 挂在 Activity 窗口上，窗口不可见时系统自然不会保持常亮，
     *    **没有"忘记释放就持续耗电"这条路**；
     *  - API 24 完全支持（该 flag 自 API 1 就有）。
     *
     * 幂等：重复 add/clear 同一个 flag 无副作用，所以调用方不必去重。
     */
    private fun applyKeepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.i(TAG, "keep-screen-on: flag added (playing on player screen)")
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.i(TAG, "keep-screen-on: flag cleared")
        }
    }

    /**
     * 方向策略：以与方向无关的 smallestScreenWidthDp 判定形态。
     *  - 手机（< 600dp）：锁竖屏，行为与旧版一致；
     *  - 大屏（平板 / 折叠展开 / 车机，>= 600dp）：不限制方向，避免大屏信箱模式黑边。
     *
     * 用 smallestScreenWidthDp 而非当前宽度：手机横屏时当前宽度可能 >= 600dp，
     * 会误判成大屏。该值随折叠形态变化，故在 onConfigurationChanged 里重跑。
     *
     * P1：大屏模式期间直接让路。onConfigurationChanged 在「刚转成横屏」那一次也会调用
     * 本方法，而手机的 smallestScreenWidthDp 恒为竖屏宽（PCL110 实测 363dp < 600）——
     * 照旧判定就会在同一帧把刚转过去的横屏锁回竖屏，大屏模式等于进不去。
     */
    private fun applyOrientationPolicy() {
        autoRotateEnabled = RotationSetting.read(this)
        val desired = BigScreenOrientation.orientationFor(
            autoRotate = autoRotateEnabled,
            bigScreen = bigScreenMode.value,
            bigScreenRelaxed = bigScreenOrientationRelaxed,
            isLargeScreen = resources.configuration.smallestScreenWidthDp >= 600,
        )
        val requested = when (desired) {
            BigScreenOrientation.DesiredOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            BigScreenOrientation.DesiredOrientation.SENSOR_LANDSCAPE ->
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            BigScreenOrientation.DesiredOrientation.SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            BigScreenOrientation.DesiredOrientation.UNSPECIFIED ->
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (requestedOrientation != requested) {
            requestedOrientation = requested
        }
    }

    /**
     * P1：进入大屏幕模式（横屏桌面播放器布局）。
     *
     * 方向两步走（实测依据见 [startBigScreenOrientationGate]）：
     *  ① 立刻 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` 强制横屏 —— 用户此刻还竖着拿手机
     *     （按钮在竖屏播放器里），只有强制横屏能让窗口马上转过去；
     *  ② 设备一旦物理横过来就放宽成 `SCREEN_ORIENTATION_SENSOR`，此后方向完全交给用户，
     *     **绝不长期强制锁横屏**；转回竖屏即退出大屏模式。
     *
     * ⚠️ v2.0.0 · T1-A 分叉：**auto-rotate 关**时不走② —— 那种情况下用户是显式按 ⤢ 进来的，
     * 意图是"我要横屏播放器"，方向策略由 `orientationFor` 的第 1 档固定在
     * `SENSOR_LANDSCAPE`（保持横屏），放宽门控**不启动**（它一旦把 requestedOrientation
     * 改成 SENSOR，手机一歪就翻竖屏 ⇒ 退出大屏 ⇒ 又被锁回竖屏，用户再也回不到横屏）。
     */
    private fun enterBigScreenMode(auto: Boolean = false) {
        if (bigScreenMode.value) return
        bigScreenMode.value = true
        if (auto) {
            // v1.8.0 · T4：自动进入的**触发源就是"配置已经变成横屏"**，所以不存在
            // "手机还竖着"的问题 —— 直接标记已放宽，省掉一次 SENSOR_LANDSCAPE 设置
            // （那一次设置会触发一次方向重算，在快速来回转时是多余抖动）。
            bigScreenOrientationRelaxed = true
            stopBigScreenOrientationGate()
            Log.i(TAG, "big screen: auto-entered (auto-rotate on, player expanded, window landscape)")
        } else if (autoRotateEnabled) {
            // auto-rotate 开：用户的意图是"跟随手机方向"，所以需要②那道放宽门控。
            bigScreenOrientationRelaxed = false
            startBigScreenOrientationGate()
        } else {
            // v2.0.0 · T1-A：auto-rotate 关 ⇒ 保持横屏。**不启动**放宽门控：
            // 它会在设备物理横向时直接把 requestedOrientation 改成 SENSOR，
            // 而 SENSOR 会在手机一歪时翻回竖屏（用户报告的 bug 正是这个）。
            bigScreenOrientationRelaxed = false
            stopBigScreenOrientationGate()
            Log.i(TAG, "big screen: manual enter, holding landscape (auto-rotate off)")
        }
        applyOrientationPolicy()
    }

    /** P1：退出大屏模式，并恢复既有的方向策略（手机锁竖屏 / 大屏不限制）。 */
    private fun exitBigScreenMode() {
        if (!bigScreenMode.value) return
        bigScreenMode.value = false
        stopBigScreenOrientationGate()
        bigScreenOrientationRelaxed = false
        applyOrientationPolicy()
    }

    /**
     * P1：物理朝向门控 —— 设备真的横过来了才把方向「放宽」成 SENSOR。
     *
     * 为什么不能无条件立刻放宽：`SCREEN_ORIENTATION_SENSOR` 是**跟随传感器**的
     * （它不理会系统的自动旋转锁），而进入大屏时手机通常还竖着/平放在桌上 ——
     * 系统会在下一帧就把窗口转回竖屏，大屏模式当场自我退出（"点了按钮闪一下就回来"）。
     * 门控只决定**什么时候**放宽，不改变"一定会放宽"：用户一把手机转横，方向立刻交割。
     *
     * 退出路径因此有三条：同一个按钮、系统返回键、旋转回竖屏（配置回到竖屏）。
     * 没有方向传感器的设备（车机 / 部分平板）本就没有"转回竖屏"这条路径，
     * 直接放宽，避免把用户永久锁在横屏里。
     */
    private fun startBigScreenOrientationGate() {
        stopBigScreenOrientationGate()
        val listener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (BigScreenOrientation.shouldRelaxToSensor(
                        bigScreen = bigScreenMode.value,
                        alreadyRelaxed = bigScreenOrientationRelaxed,
                        degrees = orientation,
                    )
                ) {
                    bigScreenOrientationRelaxed = true
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
                    Log.i(TAG, "big screen: relaxed to SENSOR (device landscape $orientation°)")
                }
            }
        }
        if (listener.canDetectOrientation()) {
            listener.enable()
            bigScreenOrientationGate = listener
        } else {
            bigScreenOrientationRelaxed = true
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            Log.i(TAG, "big screen: no orientation sensor, relaxed to SENSOR immediately")
        }
    }

    private fun stopBigScreenOrientationGate() {
        bigScreenOrientationGate?.disable()
        bigScreenOrientationGate = null
    }

    private companion object {
        const val TAG = "NcrustBigScreen"
    }
}

/**
 * v1.3.0 · B2：新建歌单的默认名。用日期而不是「我的歌单」——同名歌单在收藏页里
 * 完全无法区分，而服务端允许重名。用户可以随时改名。
 */
fun defaultPlaylistName(): String =
    "歌单 " + java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date())

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** 播放模式：0=顺序循环, 1=单曲循环, 2=乱序, 3=顺序线性(播完停), 4=相似无限(FM 电台)。 */
object QueueModes {
    const val CYCLE = 0
    const val SINGLE = 1
    const val SHUFFLE = 2
    const val LINE = 3
    const val INFINITY = 4
}

/**
 * v2.4.0 · E：单曲信息路由（`song/{source}/{songId}`）里的 id 字符串。
 *
 * ## 为什么 QQ 要反解一次
 *
 * 路由的参数类型是 `StringType`（QQ 的身份本来就是字符串），但 **QQ 的 songmid 不在
 * `SongItem.id` 里** —— `id` 是 [`SourceIds.qqId`] 造出来的**合成数字 id**（带 `1L shl 62`
 * 标志位），真正的 songid 要用 [`SourceIds.qqRawId`] 掩码取回。把合成 id 直接写进路由，
 * 单曲页就得再反解一次，而且合成 id 的十进制是 `46xxxxxxxxxxxxxxxxx` 这种量级，
 * 肉眼完全无法与网易云的 songId 区分 —— 路由里带**裸 songid** + `source` 段才是自解释的。
 *
 * 反解不出来（理论上是「这不是个 QQ 合成 id」）时退回原 id：路由至少是确定的，
 * 单曲页一定拿得到同一首歌的身份，不会因为一次反解失败跳去别的曲子。
 */
private fun songIdForRoute(song: SongItem): String =
    if (song.musicSource == MusicSource.QQMUSIC) {
        SourceIds.qqRawId(song.id)?.toString() ?: song.id.toString()
    } else {
        song.id.toString()
    }

/**
 * v2.6.1 · P0：底部导航里「搜索」是第 2 个 tab（首页 0 / 库 1 / 搜索 2 / 用户 3）。
 *
 * 抽成常量而不是就地写 `2`：这条索引同时被「跳搜索兜底」和导航栏本身消费，
 * 两处各写一个字面量，将来插入一个 tab 时**只有一处会跟着改**，
 * 表现是「点转到歌手跳到了用户页」—— 一个只在特定入口复现的错页。
 */
private const val SEARCH_TAB_INDEX = 2

/** v2.6.1 · P0：艺人跳转的日志标签。降级为搜索时必须留痕，否则线上只能看到「没反应」。 */
private const val TAG_ARTIST_NAV = "ArtistNav"

/**
 * v2.6.2 · P0：专辑跳转的日志标签。
 *
 * 与 [TAG_ARTIST_NAV] 分成两个 tag 而不是共用一个：两类跳转的降级原因不同
 * （艺人看 `singerMID`、专辑看 `albumMID` 与 `pmid` 的区分），
 * 合并之后 `adb logcat -s` 过滤不掉噪声，线上排查要先人肉分流。
 */
private const val TAG_ALBUM_NAV = "AlbumNav"

/**
 * v1.8.0 · T4：自动进入大屏的**观察者**（无 UI，只做判定与回调）。
 *
 * 为什么单独抽成一个 composable：它要订阅「应用内开关」「播放器展开态」「窗口方向」三个状态，
 * 其中"播放器展开态"每次展开/收起都会翻转 —— 写在外层 setContent 的作用域里，
 * 等于让整个 Activity 内容（含 MainScreen）跟着重组一次。这里把订阅范围收进小组件内，
 * MainScreen 只在它自己的参数变化时才重组。
 *
 * 触发源刻意只有三个值：**用户手动退出大屏（⤢ 按钮 / 返回键）不会改变其中任何一个**，
 * 所以不会立刻又被自动拽回大屏 —— 这就是"手动退出"语义的实现方式（不需要额外的抑制标志，
 * 也就不会出现"抑制标志忘了清、自动进入从此失效"的经典 bug）。转回竖屏再转横、
 * 或收起播放器再展开，都是新的显式意图 → 重新允许自动进入。
 */
@Composable
private fun AutoRotateWatcher(
    playerExpanded: State<Boolean>,
    onAutoEnter: () -> Unit,
) {
    val windowLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val autoRotate = RotationSetting.state.value
    val expanded = playerExpanded.value
    val currentOnAutoEnter = rememberUpdatedState(onAutoEnter)

    LaunchedEffect(autoRotate, expanded, windowLandscape) {
        if (!BigScreenOrientation.shouldAutoEnterBigScreen(
                autoRotate = autoRotate,
                playerExpanded = expanded,
                windowLandscape = windowLandscape,
                bigScreen = false,
            )
        ) return@LaunchedEffect
        // 防抖：快速转来转去时把连续的方向变化**合并**成一次提交（不是丢弃 ——
        // 丢弃会把状态留在错误的一侧）。LaunchedEffect 在 key 变化时自动取消上一个协程，
        // 正好就是"重新计时"的语义。
        delay(BigScreenOrientation.AUTO_ENTER_SETTLE_MS)
        // 等待期间方向可能又变了：LaunchedEffect 已被取消，不会走到这里；
        // 能走到这里说明三个 key 都没再变过。
        currentOnAutoEnter.value()
    }
}

/**
 * v1.8.0 · T1：沉浸式（隐藏状态栏）的**条件订阅点**。
 *
 * 生效条件与 PlayerCard 的 bigScreenActive 完全一致（用户意图 + 窗口真的横过来）——
 * 少了后半句，会在"按钮刚点、窗口还没转"的那一两百毫秒里把状态栏先藏掉，
 * 视觉上是"整个屏幕抖一下"。
 */
/**
 * v2.0.0 · T2：把"是否禁止熄屏"落到窗口 flag 上，并**跟随 Activity 生命周期**。
 *
 * 三条不变量（每一条都有对应的实测项）：
 *  1. \`active\` 变化立即生效 —— 播放/暂停、进出播放器界面、设置开关都走这一条；
 *  2. **ON_PAUSE 必须立刻摘 flag**：窗口 flag 在 Activity 不可见时仍然挂着，
 *     只是"暂时不生效"；一旦用户回到前台（或系统把窗口重新可见化）就会立刻恢复常亮。
 *     显式清掉才是"后台绝不禁止熄屏"的保证，也是本任务书里点名的耗电风险点；
 *  3. ON_RESUME 幂等重放：部分 ROM 从最近任务回来会重置窗口标志（与 v1.8.0 沉浸式
 *     的处理一致，见 [applyImmersive]）。
 *
 * \`onDispose\` 也清一次：Activity 销毁 / 组合退出时绝不留残留。
 */
@Composable
private fun KeepScreenOnEffect(
    active: Boolean,
    onApply: (Boolean) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentApply = rememberUpdatedState(onApply)
    DisposableEffect(lifecycleOwner, active) {
        fun apply(on: Boolean) = currentApply.value(on)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> apply(active)
                Lifecycle.Event.ON_PAUSE -> apply(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // 首次进入：只有 Activity 真的可见（RESUMED）时才允许禁止熄屏。
        apply(active && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            apply(false)
        }
    }
}

@Composable
private fun ImmersiveEffect(
    bigScreen: Boolean,
    onImmersiveChange: (Boolean) -> Unit,
) {
    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val active = bigScreen && landscape
    val currentOnChange = rememberUpdatedState(onImmersiveChange)
    LaunchedEffect(active) { currentOnChange.value(active) }
}

@Composable
fun MainScreen(
    themeIndex: Int = 0,
    onThemeChange: (Int) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onLanguageChange: (String) -> Unit = {},
    // B2-C：主题色来源（设置页三选一）。状态由 MainActivity 持有（主题在更外层应用），
    // 这里只做透传给设置页。
    accentSource: AccentSource = AccentSource.PRESET,
    onAccentSourceChange: (AccentSource) -> Unit = {},
    // B2-D：手动重新读取系统色（应对部分 ROM 换壁纸后不发配置变更）。
    onRefreshSystemAccent: () -> Unit = {},
    // P1：大屏幕模式（横屏桌面播放器布局）。状态由 MainActivity 持有（方向策略要用），
    // 这里只负责把它透传给播放器 + 用它隐藏大屏下不该出现的导航层。
    bigScreen: Boolean = false,
    onToggleBigScreen: () -> Unit = {},
    /**
     * v1.8.0 · T4：应用内「自动旋转」开关。设置页与播放器图标共享同一份状态
     * （事实源是 [RotationSetting]），这里只做透传。
     */
    autoRotate: Boolean = false,
    onToggleAutoRotate: () -> Unit = {},
    /**
     * v1.8.0 · T4：把"全屏播放器是否展开"回传给 Activity。
     *
     * 自动进入大屏**必须限定在播放器界面**（首页 / 库 / 搜索转横屏不进大屏），
     * 而这个判断只有 MainScreen 知道（progress 是它持有的）。回传的是**布尔**而不是
     * progress 本身：布尔只在阈值穿越时翻转一次，不会让 Activity 跟着动画帧重组。
     */
    onPlayerExpandedChange: (Boolean) -> Unit = {},
    // v2.0.0 · T2：把"是否应该禁止熄屏"的最终判定结果交给 Activity 去改窗口 flag。
    onKeepScreenOnChange: (Boolean) -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(1) }

    /**
     * v2.6.1 · P0：待投递给搜索页的关键词（null = 没有待办）。
     *
     * 为什么需要它：搜索是**一个 tab**，不是一条导航路由（`NavRoutes` 里没有 search，
     * `SearchScreen` 也不接任何查询参数、自己 `viewModel()` 持有一个私有 VM）。
     * 于是「转到歌手时身份不可信 ⇒ 跳搜索」这条兜底**没有现成的落点**。
     *
     * 形状取「一次性待办」而不是「双向状态」：投递方设值 → 搜索页消费后回调清空。
     * 这样它不会变成一个跨页面的粘滞状态（下一次点搜索 tab 不会又被预填一次）。
     */
    var pendingSearchQuery by remember { mutableStateOf<String?>(null) }
    // 根布局实测高度(px)：车机会把窗口内容区 inset 到系统栏之间，但 WindowInsets
    // 全为 0、screenHeightDp 又是整屏高度，只有实测高度才准。
    var rootHeightPx by remember { mutableStateOf(0f) }
    val playerViewModel: PlayerViewModel = viewModel()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    var currentSong by remember { mutableStateOf<SongItem?>(null) }
    val vmCurrentSongId by playerViewModel.currentSongId.collectAsState()
    val context = LocalContext.current

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // v2.5.1 · F：页面切换动效开关（默认**开**）。
    //
    // 状态放在 MainScreen 而不是设置页内部：设置页（UserScreen）与导航宿主
    // （MainNavGraph）都在这里之下，两者必须看到**同一个**值 ——
    // 各存一份就会出现「设置页关了、转场还在」的状态分裂
    // （v1.8.0 把自动旋转抽成 RotationSetting 是同一条理由）。
    // 它是 Compose 状态 ⇒ 开关与 NavGraph 同帧更新，所以「切换后立即生效」不需要重启。
    var pageTransitionEnabled by remember {
        mutableStateOf(PageTransitionSetting.readEnabled(context))
    }

    // ---------- 系统栏高度 ----------
    // 正常设备从 WindowInsets 取；车机（Android Automotive）的 CarSystemUI 顶/底栏是
    // 独立窗口、不下发 WindowInsets（实测全为 0），但系统会把应用窗口内容区 inset
    // 到系统栏之间——所以车机上内容区高度靠根布局实测，而不是 screenHeightDp。
    val sysStatusPx = WindowInsets.statusBars.getTop(density).toFloat()
    val sysNavPx = WindowInsets.navigationBars.getBottom(density).toFloat()

    // 宽屏（平板/折叠展开/车机）：左侧常驻 sidebar 取代底部导航，内容右移。
    // 与方向无关地按当前窗口宽度判定；窄屏(<600dp)完全走原底部导航路径。
    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    val isWideLayout = windowWidthDp >= 600
    val sidebarWidthDp = 200.dp
    val sidebarWidthPx = with(density) { sidebarWidthDp.toPx() }

    // 是否运行在 Android Automotive(车机)。只有车机的 CarSystemUI 才需要"实测内容区
    // 高度"这套兜底——手机/平板若也用 rootHeightPx 会改变 miniBar 落点(实测偏高)，
    // 属于车机改动外溢。这里显式隔离：车机走实测，其余设备沿用 screenHeightDp。
    val isAutomotive =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
            Configuration.UI_MODE_TYPE_CAR

    // 卡片相关尺寸。宽屏无底部导航, navBar 高度记 0, miniBar 直接贴到系统栏之上。
    val navBarHeightPx = if (isWideLayout) 0f else with(density) { 56.dp.toPx() }
    // v2.5.5 · C：托盘高度改由 TrayLayout 唯一提供（旧实现是这里与 PlayerCard、与
    // BottomOverlayInset 各写一份 56.dp）。它是 collapsedOffsetY 的一项，写错的表现是
    // 「卡片收起后与托盘之间露出一条空白带」，同时让折叠态命中区判定（collapsedHitGate /
    // isOverCardVisibleArea）整体错位 —— 与「托盘高度」在代码上没有任何可见关联。
    val miniBarHeightPx = with(density) { TrayLayout.HEIGHT_DP.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // 内容区高度：车机用根布局实测（CarSystemUI 把内容区 inset 到系统栏之间，但
    // WindowInsets 全为 0、screenHeightDp 又对不上）；手机/平板沿用 screenHeightDp，
    // 保持车机改动之前的行为。实测前先用整屏过渡。
    val contentHeightPx = if (isAutomotive && rootHeightPx > 0f) rootHeightPx else screenHeightPx

    // miniBar 底 = navBar 顶。手机/平板系统栏由 WindowInsets 给出；车机内容区已被系统
    // inset（WindowInsets 为 0），公式一致，区别只在 contentHeightPx 的取法。
    val collapsedOffsetY =
        contentHeightPx - sysNavPx - navBarHeightPx - miniBarHeightPx - sysStatusPx

    val totalDragDistancePx = contentHeightPx * 0.85f

    val navBarHideOffset = if (isWideLayout) 0f else with(density) { 132.dp.toPx() }
    // ------------------------------------

    // 自 ViewModel 恢复 currentSong 之状态。
    LaunchedEffect(Unit) {
        val name = playerViewModel.currentSongName.value
        val artist = playerViewModel.currentSongArtist.value
        val artwork = playerViewModel.currentSongArtwork.value
        val songId = playerViewModel.currentSongId.value
        if (name != null && songId != null && songId > 0 && currentSong == null) {
            currentSong = SongItem(
                id = songId,
                name = name,
                artists = if (artist != null) listOf(ArtistItem(name = artist)) else null,
                album = AlbumItem(id = null, name = "", picUrl = artwork),
                duration = null,
                // v2.1.0 · C：把音源一起恢复 —— 少了它，冷启动后播 QQ 曲目会去网易云取链。
                source = PlaybackStateManager.getSourceKey(context),
                sourceId = PlaybackStateManager.getSourceId(context),
                mediaId = PlaybackStateManager.getMediaId(context),
            )
        }
    }

    val progress = remember { Animatable(0f) }

    // v1.8.0 · T4：把"播放器是否展开"回传给 Activity（自动进大屏的触发范围判定）。
    // 走 snapshotFlow 而不是在组合期读 progress —— 后者会让整个 MainScreen 跟着
    // 展开/收起动画逐帧重组（v1.7.0 在 BackHandler 那里已经踩过一次，见下方注释）。
    // 阈值 0.99f = 与 PlayerCard 的 cardExpandedForInput 同一口径（"真的全展开了"）。
    // v2.0.0 · T2：同一份展开态另存一份**给禁止熄屏用**。刻意不新增第二个 snapshotFlow ——
    // 两次订阅会在同一次展开/收起里各跑一遍比较；这里只在"真的跨过阈值"时写一次。
    // 写的是 State 而不是组合期读 progress，所以不会让 MainScreen 跟着动画逐帧重组。
    val playerOnScreen = remember { mutableStateOf(false) }
    LaunchedEffect(onPlayerExpandedChange) {
        snapshotFlow { progress.value > 0.99f }
            .distinctUntilChanged()
            .collect {
                playerOnScreen.value = it
                onPlayerExpandedChange(it)
            }
    }

    // v2.0.0 · T2：播放中禁止熄屏。三个条件缺一不可 ——
    //  ① 设置开关开着（默认开）；② **正在播放**（暂停/缓冲一律恢复系统策略）；
    //  ③ 在**播放器界面**（竖屏全屏播放器与大屏模式共用同一个展开进度；mini bar 不算）。
    // 进后台由 KeepScreenOnEffect 里的 ON_PAUSE 摘掉 flag，绝不后台常亮。
    KeepScreenOnEffect(
        active = KeepScreenOnSetting.state.value && isPlaying && playerOnScreen.value,
        onApply = onKeepScreenOnChange,
    )

    var menuSong by remember { mutableStateOf<SongItem?>(null) }
    var menuSongActions by remember { mutableStateOf<List<SongMenuAction>>(emptyList()) }

    // v2.5.0 · D：应用级 Snackbar。本仓库此前没有 Snackbar 设施，反馈一律走 Toast。
    // 「添加到下一首播放」有四种结果（插入 / 搬移 / 已在下一首 / 直接起播），
    // 用 Toast 连弹会互相覆盖，所以这里是**唯一**用 Snackbar 的地方。
    // 宿主是非交互浮层（见 AppSnackbarHost 的 KDoc：它刻意不挂任何指针输入，
    // 因此不会在本应用那套「屏幕底部死带」问题上新增一条）。
    val snackbar = rememberAppSnackbarState()

    // v1.3.0 · B2：保存为歌单。playlistSnapshot 是点按钮那一刻的队列快照。
    var showCreatePlaylist by remember { mutableStateOf(false) }
    var playlistSnapshot by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    // v1.3.0 · B3：加入歌单。pendingAddSongs = 走「新建歌单」时创建成功后要补加的歌曲。
    var showAddToPlaylist by remember { mutableStateOf(false) }
    var pendingAddSongs by remember { mutableStateOf<List<Long>>(emptyList()) }
    // v2.3.0 · B：「加入本地歌单」选择器是否打开（与云端的 showAddToPlaylist 分开）。
    var showAddToLocalPlaylist by remember { mutableStateOf(false) }
    // ★ 选择器要操作的那首歌**必须自己存一份**：`SongMenuSheet` 在动作点完之后会
    // 立刻 `onDismiss()`（见 SongMenuSheet.kt:100-101），而 `onDismiss` 会把
    // `menuSong` 置空 —— 所以「拿 menuSong 当选择器的输入」在真机上表现为
    // **点了「加入本地歌单」什么都不弹**（debug 阅读代码时看不出来）。
    // 与云端的 showAddToPlaylist 用 `pendingAddSongs` 存 id 是同一个道理。
    var pendingLocalSong by remember { mutableStateOf<SongItem?>(null) }
    // 本地歌单列表的快照：对话框打开时读一次，选完即失效。
    var localPickerList by remember { mutableStateOf<List<com.takahashirinta.ncrust.local.LocalPlaylist>>(emptyList()) }
    // 本地歌单存在 SharedPreferences 里、不是可观察数据源，所以在库页上给它一个
    // 显式的失效信号：从菜单里加过歌之后把库页的本地歌单重新读一次。
    var localPlaylistsTick by remember { mutableIntStateOf(0) }

    // 打开歌曲长按菜单的唯一入口：所有 Screen 共用，菜单关闭时把歌单相关状态一并清掉，
    // 避免上一次的待加歌曲泄漏到下一次「加入歌单」。
    fun showSongMenu(song: SongItem, actions: List<SongMenuAction>) {
        menuSong = song
        menuSongActions = actions
        showAddToPlaylist = false
        showAddToLocalPlaylist = false
        pendingLocalSong = null
        pendingAddSongs = emptyList()
    }


    var playbackQueue by remember { mutableStateOf<List<SongItem>>(emptyList()) }

    // B2/B3 共用：这次创建歌单后要往里放的歌曲 —— 队列入口用快照，加歌入口用待加列表。
    // 待加列表里的歌可能不在当前队列（如搜索结果），退化成只带 id 的占位 SongItem，
    // 因为这两个入口都只用 id 发请求。
    fun pendingSongs(): List<SongItem> = if (pendingAddSongs.isNotEmpty()) {
        val ids = pendingAddSongs.toSet()
        playbackQueue.filter { it.id in ids }.ifEmpty {
            pendingAddSongs.map { SongItem(it, "", null, null, null) }
        }
    } else {
        playlistSnapshot
    }
    var currentQueueIndex by remember { mutableIntStateOf(-1) }
    var songEnded by remember { mutableStateOf(false) }
    var songTransitioned by remember { mutableStateOf(false) }
    // 点击 lambda / 协程里不能读 CompositionLocal，提示文案在组合期取好。
    val mainStrings = LocalStrings.current
    // v1.4.0 · 音乐人推荐：每次回到首页 / 切换设置后重算一次口味命中（纯本地，无请求）。
    // 自动锚点（目标艺人热门曲 → simiSong 推导）只在需要时后台刷新，带 7 天 TTL。
    var artistRecoArtistId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(selectedTab) {
        artistRecoArtistId = if (ArtistReco.shouldShow(context)) ArtistReco.targetId(context) else null
        if (ArtistReco.isEnabled(context) && ArtistReco.targetId(context) != 0L &&
            ArtistReco.manualAnchors(context).isEmpty()
        ) {
            withContext(Dispatchers.IO) { ArtistReco.refreshAutoAnchors(context) }
            artistRecoArtistId = if (ArtistReco.shouldShow(context)) ArtistReco.targetId(context) else null
        }
    }
    // v2.0.0 · T1-C：播放模式必须跨 Activity 重建存活。原先只放在 remember 里，
    // 低内存回收 / "不保留活动" / 主题或语言切换都会让它静默回到 CYCLE（顺序循环），
    // 队列于是按原始顺序播 —— 网易歌单的原始顺序常按语种/地区成块，
    // 用户看到的就是"随机播放却十首日语连播"。
    var playMode by remember { mutableIntStateOf(PlaybackStateManager.getPlayMode(context)) }
    var shuffledIndices by remember { mutableStateOf<List<Int>>(emptyList()) }
    var shuffledPosition by remember { mutableIntStateOf(0) }
    // v2.0.0 · T1-C：轮末预排好的下一轮（见 needsPreload 的 SHUFFLE 分支）。
    // 之所以要"预排"，是因为无缝隙路径在轮末必须**预载**下一首，而下一首属于新一轮的随机结果：
    // 预载与过渡必须读同一份序列，否则要么重播刚播完的那首（旧行为），要么与预载的歌不一致。
    var pendingRound by remember { mutableStateOf<List<Int>?>(null) }

    fun songParams(s: SongItem) = Triple(
        s.name,
        s.artists?.joinToString("/") { it.name } ?: "",
        s.album?.picUrl ?: ""
    )

    /** v2.0.0 · T1-C：防扎堆用的分散键 —— 主艺人 id。缺艺人信息返回 null（不参与约束）。 */
    fun artistKeyOf(index: Int): Long? =
        playbackQueue.getOrNull(index)?.artists?.firstOrNull()?.id

    fun generateShuffledIndices() {
        if (playbackQueue.isEmpty()) {
            shuffledIndices = emptyList()
            shuffledPosition = 0
            pendingRound = null
            return
        }
        shuffledIndices = ShuffleRound.newRound(
            size = playbackQueue.size,
            currentIndex = currentQueueIndex,
            keyOf = ::artistKeyOf,
        )
        shuffledPosition = 0
        // 新的一轮作废掉已预排的下一轮（它属于旧队列/旧位置）。
        pendingRound = null
    }

    /** 切换播放模式：写盘 + 维护乱序序列（唯一入口，避免漏掉落盘）。 */
    fun applyPlayMode(mode: Int) {
        playMode = mode
        PlaybackStateManager.savePlayMode(context, mode)
        if (mode == QueueModes.SHUFFLE) {
            generateShuffledIndices()
        } else {
            shuffledIndices = emptyList()
            shuffledPosition = 0
            pendingRound = null
        }
    }

    // 恢复播放队列。
    LaunchedEffect(Unit) {
        val savedQueue = PlaybackStateManager.getQueue(context)
        if (savedQueue != null && savedQueue.first.isNotEmpty()) {
            playbackQueue = savedQueue.first.toMutableList()
            currentQueueIndex = savedQueue.second.coerceIn(0, playbackQueue.size - 1)
            if (playMode == QueueModes.SHUFFLE) {
                generateShuffledIndices()
            }
            // v2.2.1 · P0：把**队列里那一份**身份补给正在播的这首。
            //
            // 落盘的单曲状态只有 id + 音源（`getState()` 的既定契约），而 QQ 取链
            // 必须要 songmid / media_mid —— 它们只存在于队列的 SongItem 里。
            // 少这一步的实测表现：冷启动后 `SourceRouter: unresolvable song
            // source=qqmusic id=… (missing sourceId)`，QQ 曲目只能吃离线缓存里
            // **另一个档位**的旧 URL（选了母带播出来是无损），并可能引发循环重试。
            playbackQueue.getOrNull(currentQueueIndex)?.let { current ->
                if (current.id == playerViewModel.currentSongId.value) {
                    playerViewModel.adoptTrackIdentity(
                        current.source, current.id, current.sourceId, current.mediaId,
                    )
                }
            }
        }
    }

    // ViewModel 确认切歌后（URL fetch 完成或 gapless 快速路径），
    // 从队列中找到对应 SongItem 并更新 currentSong，保证 UI 与音频同步。
    // v2.5.3 · P1：这里只有裸 id（ViewModel 广播的是 `currentSongId`），
    // 所以按 **TrackKey.ofSong 推断出的音源**比对，而不是"id 相等即同一首"——
    // 后者会让「网易云 123」与「QQ 合成的 123」互相认领。
    LaunchedEffect(vmCurrentSongId) {
        val id = vmCurrentSongId ?: return@LaunchedEffect
        val vmSourceKey = playerViewModel.currentTrackKey?.source?.key
        val vmKey = TrackKey.of(vmSourceKey, id)
        if (currentSong?.let { QueueKeys.keyOf(it) == vmKey } == true) return@LaunchedEffect
        val found = playbackQueue.firstOrNull { QueueKeys.keyOf(it) == vmKey }
        if (found != null) currentSong = found
    }

    fun removeFromQueue(index: Int) {
        playbackQueue = playbackQueue.toMutableList().also { it.removeAt(index) }
        if (currentQueueIndex >= playbackQueue.size) currentQueueIndex = playbackQueue.size - 1
        if (playbackQueue.isEmpty()) currentQueueIndex = -1
        if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    // 队列拖拽排序：移动后修正 currentQueueIndex（当前播放项跟随其歌曲移动）。
    fun moveInQueue(from: Int, to: Int) {
        if (from !in playbackQueue.indices || to !in playbackQueue.indices || from == to) return
        val item = playbackQueue[from]
        val newQueue = playbackQueue.toMutableList().apply { removeAt(from); add(to, item) }
        // 当前播放项的索引修正：歌曲本身移动了，索引跟随歌曲。
        currentQueueIndex = when {
            from == currentQueueIndex -> to
            from < currentQueueIndex && to >= currentQueueIndex -> currentQueueIndex - 1
            from > currentQueueIndex && to <= currentQueueIndex -> currentQueueIndex + 1
            else -> currentQueueIndex
        }
        playbackQueue = newQueue
        if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    /**
     * @param origin v2.2.1 · P0：这次开播是不是**用户手点**的。
     *
     * 自动接续（`playNext()` / `onUnplayableCallback` / `songEnded`）必须传
     * [PlayOrigin.AUTO_NEXT]，否则播放器侧的「连续自动跳歌」计数会被每条路径上的
     * `playSong(origin=USER)` 冲掉，熔断永远不生效 —— 而这正是用户报的
     * 「不关应用就一直切」。默认 USER 保证既有调用点零改动。
     */
    fun playFromQueue(index: Int, origin: PlayOrigin = PlayOrigin.USER) {
        if (index in playbackQueue.indices) {
            currentQueueIndex = index
            // v2.0.0 · T1-C：乱序游标必须跟着走。从队列面板手动点歌原先只改 currentQueueIndex，
            // 之后的"下一首"仍按旧游标推进 ⇒ 已经播过的歌会再播一遍（"随机还会重复"的来源之一）。
            if (playMode == QueueModes.SHUFFLE) {
                val pos = shuffledIndices.indexOf(index)
                if (pos >= 0) shuffledPosition = pos else generateShuffledIndices()
            }
            val song = playbackQueue[index]
            val (title, artist, artwork) = songParams(song)
            playerViewModel.playSong(
                song.id,
                title = title,
                artist = artist,
                artworkUrl = artwork,
                sourceKey = song.source,
                sourceId = song.sourceId,
                mediaId = song.mediaId,
                origin = origin,
            )
            PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)

            // Immediately preload the next song so ExoPlayer has maximum time to buffer it.
            val nextIdx = when (playMode) {
                QueueModes.SINGLE -> index
                QueueModes.SHUFFLE -> shuffledIndices.getOrNull(
                    if (shuffledPosition < shuffledIndices.size - 1) shuffledPosition + 1 else 0
                ) ?: 0
                QueueModes.LINE ->
                    if (index < playbackQueue.size - 1) index + 1 else -1
                QueueModes.INFINITY ->
                    if (index < playbackQueue.size - 1) index + 1 else -1
                // CYCLE: 队尾循环到队首, 但单曲队列不预载自身(避免播完自动重播=假单曲循环)
                else ->
                    if (playbackQueue.size > 1) {
                        if (index < playbackQueue.size - 1) index + 1 else 0
                    } else -1
            }
            val nextSong = playbackQueue.getOrNull(nextIdx)
            if (nextSong != null) {
                val (nTitle, nArtist, nArtwork) = songParams(nextSong)
                playerViewModel.preloadNextSong(
                    nextSong.id, nTitle, nArtist, nArtwork,
                    allowCurrent = playMode == QueueModes.SINGLE,
                    sourceKey = nextSong.source,
                    sourceId = nextSong.sourceId,
                    mediaId = nextSong.mediaId,
                )
            }
        }
    }

    // ---------- Infinity 无限播放（FM 电台, 作为播放模式之一 INFINITY） ----------
    val infinityJob = remember { mutableStateOf<Job?>(null) }
    // FM 电台入口(true) 与 相似无限播放模式(false) 共用 INFINITY 播放模式,
    // 但续播数据源不同: FM 继续拉私人 FM 流, 相似无限拉相似歌曲。
    var fmMode by remember { mutableStateOf(false) }

    /**
     * 队尾续播。数据源取决于入口:
     *  - FM 电台(fmMode): 继续调私人 FM 流 getPersonalFm, 保持"电台"体验;
     *  - 相似无限(Infinity 播放模式): 以当前歌为种子拉相似歌曲。
     * 已入队的歌会被过滤, 防止环绕重复; 数据源为空时兜底每日推荐, 避免断播。
     * in-flight 防重入:预载心跳与播完路径可能几乎同时触发。
     */
    fun launchInfinity() {
        if (infinityJob.value?.isActive == true) return
        val seed = playbackQueue.getOrNull(currentQueueIndex) ?: return
        // v2.5.3 · P1：续播去重同样走身份（TrackKey），避免"跨源同号被当成已有"。
        val existingKeys = QueueKeys.keysOf(playbackQueue).toHashSet()
        val fromFm = fmMode
        infinityJob.value = coroutineScope.launch(Dispatchers.IO) {
            val primary = if (fromFm) {
                // 私人 FM 流: 一次只返回一小批, 队尾再拉一次即无限续播电台。
                runCatching { PlaylistApi.getPersonalFm() }.getOrDefault(emptyList())
            } else {
                // 相似歌曲（以当前歌为种子），听着听着往相似方向延伸。
                runCatching { PlaylistApi.getSimilarSongs(seed.id) }.getOrDefault(emptyList())
            }.filter { QueueKeys.keyOf(it) !in existingKeys }
            val continuation = if (primary.isNotEmpty()) primary else
                runCatching {
                    PlaylistApi.getDailyRecommendSongs()
                        .filter { QueueKeys.keyOf(it) !in existingKeys }
                }.getOrDefault(emptyList())
            if (continuation.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                playbackQueue = playbackQueue + continuation
                val startIdx = playbackQueue.size - continuation.size
                if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
                playFromQueue(startIdx)
                PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
                // 为无缝衔接立即预载 infinity 首曲之后的一首
                playbackQueue.getOrNull(startIdx + 1)?.let { next ->
                    val (t, a, w) = songParams(next)
                    playerViewModel.preloadNextSong(
                        next.id, t, a, w,
                        sourceKey = next.source,
                        sourceId = next.sourceId,
                        mediaId = next.mediaId,
                    )
                }
            }
        }
    }

    /**
     * @param origin v2.2.1 · P0：用户按「下一首」= [PlayOrigin.USER]（并**清零**连续自动跳歌计数）；
     *   曲终接续 / 「取不到链」触发的跳歌 = [PlayOrigin.AUTO_NEXT]（计入熔断）。
     */
    fun playNext(origin: PlayOrigin = PlayOrigin.USER) {
        if (playbackQueue.isEmpty()) return
        when (playMode) {
            QueueModes.SINGLE -> playerViewModel.seekTo(0)
            QueueModes.SHUFFLE -> {
                if (shuffledIndices.isEmpty() || shuffledPosition >= shuffledIndices.size - 1) {
                    // v2.0.0 · T1-C：一轮播完 ⇒ 开新一轮，**跳过下标 0**（那是刚播完的这首）。
                    // 旧实现在这里重洗整池后播 fresh[0]，可能立刻重播刚播完的歌；
                    // 无缝路径更糟：把当前曲钉在 0 位再播下标 0 = 直接重播。
                    val fresh = ShuffleRound.newRound(
                        size = playbackQueue.size,
                        currentIndex = currentQueueIndex,
                        keyOf = ::artistKeyOf,
                    )
                    shuffledIndices = fresh
                    shuffledPosition = if (fresh.size > 1) 1 else 0
                    pendingRound = null
                    playFromQueue(fresh.getOrElse(shuffledPosition) { 0 }, origin)
                } else {
                    shuffledPosition++
                    playFromQueue(shuffledIndices[shuffledPosition], origin)
                }
            }
            QueueModes.LINE -> {
                // 顺序线性：队列尾自然结束后不再循环, 定位到首曲(不自动续播)
                if (currentQueueIndex < playbackQueue.size - 1) {
                    playFromQueue(currentQueueIndex + 1, origin)
                } else {
                    currentQueueIndex = 0
                    currentSong = playbackQueue.firstOrNull()
                    PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
                    playerViewModel.pausePlayback()
                }
            }
            QueueModes.INFINITY -> {
                // 队列尾 → FM 电台式相似歌曲续播
                if (currentQueueIndex < playbackQueue.size - 1) {
                    playFromQueue(currentQueueIndex + 1, origin)
                } else {
                    launchInfinity()
                }
            }
            else -> { // CYCLE
                if (currentQueueIndex < playbackQueue.size - 1) {
                    playFromQueue(currentQueueIndex + 1, origin)
                } else {
                    playFromQueue(0, origin)
                }
            }
        }
    }

    fun playPrevious() {
        if (playbackQueue.isEmpty()) return
        // FM 电台(INFINITY)不可回退：上一首禁用
        if (playMode == QueueModes.INFINITY) return
        when (playMode) {
            QueueModes.SINGLE -> playerViewModel.seekTo(0)
            QueueModes.SHUFFLE -> {
                // ★ v2.5.5 · C：**轮首要回绕到轮末**。
                //
                // 旧写法是 `if (shuffledPosition > 0) { … }` —— **没有 else**：
                // 游标在轮首时整个分支什么都不做，按钮点了没有任何反馈。
                // 真机受控复测（PLC110，5 次）成功率 **1/5**，而同一按钮在非乱序下每次都成功；
                // 查落盘状态确认当时 `play_mode = 2`。这不影响「上一首按钮存在」这条 P0，
                // 但它让那个按钮在乱序模式下**静默无效**，与铁律 19 的意图冲突
                // （对用户来说「时灵时不灵」与「缺失」是同一件事）。
                //
                // 落点规则与 CYCLE/LINE 的上一首、以及 playNext 的队尾回绕对称，
                // 判定抽在 ShuffleNav.previousPosition（纯函数 + 单测）。
                val target = ShuffleNav.previousPosition(shuffledPosition, shuffledIndices.size)
                if (target != null) {
                    shuffledPosition = target
                    playFromQueue(shuffledIndices[target])
                }
            }
            else -> playFromQueue(
                if (currentQueueIndex > 0) currentQueueIndex - 1 else playbackQueue.size - 1
            )
        }
    }

    // 设置播放器回调。
    LaunchedEffect(Unit) {
        playerViewModel.setOnSongPreviousCallback { playPrevious() }
        playerViewModel.setOnSongEndedCallback { songEnded = true }
        playerViewModel.setOnSongTransitionedCallback { songTransitioned = true }
        // v2.2.1 · P0：这条回调是「这首歌确实取不到链」的自动跳歌入口，
        // 必须带 AUTO_NEXT，否则 5 次上限永远数不到（用户报的「一直切」）。
        playerViewModel.setOnUnplayableCallback { playNext(PlayOrigin.AUTO_NEXT) }
    }

    // 无缝播放：当进入当前歌曲的最后 20 秒时，预取下一首的 URL 并加入 ExoPlayer 队列。
    // NOTE: resetPreloadFlag() is intentionally NOT called here. Calling it resets the StateFlow
    // to false, which lets the 250ms progress ticker re-set it to true immediately, causing
    // preloadNextSong to be called every 250ms (each call cancelling the previous). The flag is
    // cleared naturally by playSong() and onSongTransitioned.
    LaunchedEffect(Unit) {
        playerViewModel.needsPreload.collect { needs ->
            if (!needs) return@collect
            val nextSong: SongItem? = when {
                playbackQueue.isEmpty() -> null
                playMode == QueueModes.SINGLE -> playbackQueue.getOrNull(currentQueueIndex)
                playMode == QueueModes.SHUFFLE -> {
                    if (shuffledPosition < shuffledIndices.size - 1) {
                        playbackQueue.getOrNull(shuffledIndices.getOrNull(shuffledPosition + 1) ?: 0)
                    } else {
                        // v2.0.0 · T1-C：轮末。旧实现回绕到 shuffledIndices[0] = 正在播的那一首
                        // ⇒ ExoPlayer 无缝重播当前曲。改成**先把下一轮排好**（当前曲钉 0 位），
                        // 预载它的下标 1；过渡路径读同一份 pendingRound，两边绝不会走岔。
                        val round = pendingRound ?: ShuffleRound.newRound(
                            size = playbackQueue.size,
                            currentIndex = currentQueueIndex,
                            keyOf = ::artistKeyOf,
                        ).also { pendingRound = it }
                        playbackQueue.getOrNull(round.getOrNull(1) ?: currentQueueIndex)
                    }
                }
                playMode == QueueModes.LINE ->
                    // 顺序线性: 队尾之后没有下一首, 不预载(否则队尾歌会 gapless 回绕队首,
                    // 单曲队列更是会 [A,A] 自动过渡 = 假单曲循环)。
                    playbackQueue.getOrNull(
                        if (currentQueueIndex < playbackQueue.size - 1) currentQueueIndex + 1 else -1
                    )
                playMode == QueueModes.INFINITY -> {
                    if (currentQueueIndex < playbackQueue.size - 1)
                        playbackQueue.getOrNull(currentQueueIndex + 1) else null
                }
                else -> {
                    // CYCLE: 队尾循环到队首；单曲队列不预载自身(避免播完自动重播=假单曲循环)
                    val nextIdx = when {
                        playbackQueue.size <= 1 -> -1
                        currentQueueIndex < playbackQueue.size - 1 -> currentQueueIndex + 1
                        else -> 0
                    }
                    playbackQueue.getOrNull(nextIdx)
                }
            }
            if (nextSong != null) {
                val (title, artist, artwork) = songParams(nextSong)
                // 单曲循环模式: 预载自身是实现无缝单曲循环的手段, 显式放行
                playerViewModel.preloadNextSong(
                    nextSong.id, title, artist, artwork,
                    allowCurrent = playMode == QueueModes.SINGLE,
                    sourceKey = nextSong.source,
                    sourceId = nextSong.sourceId,
                    mediaId = nextSong.mediaId,
                )
            } else if (playMode == QueueModes.INFINITY &&
                currentQueueIndex >= playbackQueue.size - 1 && playbackQueue.isNotEmpty()
            ) {
                // 队列尾 + Infinity: 提前拉相似歌追加, 追加完成时才来得及无缝预载——
                // 等到自然播完才发起, 相似接口的网络往返会让衔接出现空档
                launchInfinity()
            }
        }
    }

    // 无缝播放：ExoPlayer 自动切换曲目后，同步队列索引和歌词。
    LaunchedEffect(songTransitioned) {
        if (!songTransitioned) return@LaunchedEffect
        songTransitioned = false
        if (playbackQueue.isEmpty()) return@LaunchedEffect
        val nextIndex = when (playMode) {
            QueueModes.SINGLE -> currentQueueIndex
            QueueModes.SHUFFLE -> {
                if (shuffledPosition < shuffledIndices.size - 1) {
                    shuffledPosition++
                    shuffledIndices.getOrElse(shuffledPosition) { 0 }
                } else {
                    // v2.0.0 · T1-C：必须与预载读**同一份**序列，否则过渡会走到一首没被预载的歌。
                    // 跳过下标 0（刚播完的这首）—— 旧实现正好把下标 0 当下一首 = 重播。
                    val round = pendingRound ?: ShuffleRound.newRound(
                        size = playbackQueue.size,
                        currentIndex = currentQueueIndex,
                        keyOf = ::artistKeyOf,
                    )
                    pendingRound = null
                    shuffledIndices = round
                    shuffledPosition = if (round.size > 1) 1 else 0
                    round.getOrElse(shuffledPosition) { 0 }
                }
            }
            // 顺序线性: 队尾没有预载(见 needsPreload 分支), 正常不会走到这里;
            // 万一有竞态残留, 停在队尾而不是回绕队首。
            QueueModes.LINE ->
                if (currentQueueIndex < playbackQueue.size - 1) currentQueueIndex + 1
                else currentQueueIndex.coerceIn(0, playbackQueue.size - 1)
            QueueModes.INFINITY ->
                if (currentQueueIndex < playbackQueue.size - 1) currentQueueIndex + 1
                else if (playbackQueue.isNotEmpty()) 0 else currentQueueIndex
            else -> if (currentQueueIndex < playbackQueue.size - 1) currentQueueIndex + 1 else 0
        }
        currentQueueIndex = nextIndex
        currentSong = playbackQueue.getOrNull(nextIndex)
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
        currentSong?.id?.let { playerViewModel.fetchLyricsForSong(it) }

        // Immediately start preloading the song after this one.
        val nnIdx = when (playMode) {
            QueueModes.SINGLE -> nextIndex
            QueueModes.SHUFFLE -> shuffledIndices.getOrNull(
                if (shuffledPosition < shuffledIndices.size - 1) shuffledPosition + 1 else 0
            ) ?: 0
            QueueModes.LINE ->
                if (nextIndex < playbackQueue.size - 1) nextIndex + 1 else -1
            QueueModes.INFINITY -> {
                if (nextIndex < playbackQueue.size - 1) nextIndex + 1
                else { launchInfinity(); -1 }
            }
            // CYCLE: 单曲队列不预载自身(避免 ExoPlayer [A,A] 闭环=假单曲循环)
            else -> if (playbackQueue.size > 1) {
                if (nextIndex < playbackQueue.size - 1) nextIndex + 1 else 0
            } else -1
        }
        val songToPreload = playbackQueue.getOrNull(nnIdx)
        if (songToPreload != null) {
            val (pTitle, pArtist, pArtwork) = songParams(songToPreload)
            playerViewModel.preloadNextSong(
                songToPreload.id, pTitle, pArtist, pArtwork,
                allowCurrent = playMode == QueueModes.SINGLE,
                sourceKey = songToPreload.source,
                sourceId = songToPreload.sourceId,
                mediaId = songToPreload.mediaId,
            )
        }
    }

    // 在 Splash 遮挡期间渲染一帧全展开状态，提前编译 PlayerCard 所有 graphicsLayer 的 GPU Shader。
    LaunchedEffect(Unit) {
        // Shader 预热：只跨过 expandedEnough 阈值(0.05f)让 graphicsLayer 编译 GPU shader，
        // 但不要 snapTo(1f)，那会让 LyricsView / QueueView / FullPlayerControls 全部 mount 再 unmount，
        // 在低端机上撞在 warmup 阶段是数十毫秒的 layout burst。0.06f 只让展开态视觉属性触发一次绘制。
        delay(50L)
        progress.snapTo(0.06f)
        delay(32L)
        progress.snapTo(0f)
    }

    // 歌曲结束后自动播放下一首。
    LaunchedEffect(songEnded) {
        if (songEnded) {
            songEnded = false
            if (playbackQueue.isNotEmpty()) {
                // v2.2.1 · P0：曲终接续属于**自动**跳歌，必须计入熔断。
                playNext(PlayOrigin.AUTO_NEXT)
            }
        }
    }

    fun expandCard() {
        coroutineScope.launch {
            progress.animateTo(1f, tween(400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
        }
    }

    fun collapseCard() {
        coroutineScope.launch {
            progress.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
        }
    }

    fun playSongItem(song: SongItem) {
        // 单曲直接播放 → 插到当前播放的下一首并立即播放（不再是"排到队尾"）。
        // 队列未在播放（空/无当前曲）时, 该曲作为唯一一首。
        val isQueued = playbackQueue.isNotEmpty() && currentQueueIndex in playbackQueue.indices
        if (!isQueued) {
            playbackQueue = listOf(song)
            currentQueueIndex = 0
            if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
            PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
            playFromQueue(0)
            expandCard()
            return
        }
        // 内联 insertNext 语义：去重后把该曲插到当前歌的下一首，并让 currentQueueIndex 指向它。
        // v2.5.3 · P1：判重与重定位都改走 `TrackKey`（见 QueueKeys）。
        val currentKey = playbackQueue.getOrNull(currentQueueIndex)?.let { QueueKeys.keyOf(it) }
        val songKey = QueueKeys.keyOf(song)
        if (songKey != currentKey) {
            val filtered = QueueKeys.dedupe(QueueKeys.keysOf(playbackQueue), songKey).toMutableList()
            val newCurrentIndex = QueueKeys.indexOfCurrent(filtered, currentKey).coerceAtLeast(0)
                .let { if (currentKey == null) -1 else it }
            val insertPos = (newCurrentIndex + 1).coerceIn(0, filtered.size)
            filtered.add(insertPos, songKey)
            playbackQueue = QueueKeys.rebuild(
                songs = playbackQueue,
                keys = filtered,
                extra = listOf(song),
            ) ?: playbackQueue
            currentQueueIndex = if (newCurrentIndex < 0) 0 else newCurrentIndex
            if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
            PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
        }
        val idx = playbackQueue.indexOfFirst { QueueKeys.keyOf(it) == songKey }
        if (idx >= 0) playFromQueue(idx)
        expandCard()
    }

    /**
     * v2.5.0 · D：**「添加到下一首播放」**（铁律 16）。
     *
     * 与 [playSongItem]（立刻打断并播放）和 [appendToQueue]（排到队尾）都不一样的第三件事：
     * **不打断当前播放**，只把这首歌排到当前歌之后。
     *
     * ## 与 v2.5.0 之前的关系
     *
     * 这个函数在 v2.5.0 之前就存在，并且已经做对了最容易被写错的一步
     * （去重后**重新定位** `currentQueueIndex`）。本版把判定搬进 [QueueInsert]（可单测），
     * 并修掉三个真实缺陷：
     *
     *  1. **队列为空时只改队列、不起播** —— 用户点「添加到下一首播放」在空队列下
     *     完全没有反应（`currentQueueIndex` 被设成 0 但没有 `playFromQueue`）。
     *     现在走 [QueueInsert.Outcome.START_FRESH] 分支真的起播；
     *  2. **随机模式下功能静默失效** —— 只改线性队列、不改编排序列，
     *     下一首仍然是原来那首随机歌。现在用 [QueueInsert.shuffleAfterInsert] 修正；
     *  3. **不重新同步待播槽位** —— 这是最隐蔽的一条：ExoPlayer 的播放列表里
     *     已经预载了旧的「下一首」，插入新歌之后它**仍然会播那一首**，
     *     而队列面板显示的是新插入的歌。这就是 v1.5.2「串台」的形状。
     *     现在插入后立刻重新预载新的下一首，让 `PreloadSlot.decide` 走 REPLACE。
     *
     * 另外把「它就是当前歌」从**静默 return** 改成**有反馈的幂等**
     * （原先用户点下去什么都不会发生，看起来像按钮坏了）。
     */
    fun insertNext(song: SongItem) {
        // v2.5.3 · P1：身份从裸 `song.id` 换成 `TrackKey`（音源感知）。
        // 探针确认跨源裸 id 撞号的实际发生率是 0（QQ 的 id 带 bit62 标志位），
        // 但那 0 依赖「每个 id 生产者都记得走 SourceIds.qqId」这条纪律；
        // 换成 TrackKey 之后判重语义由类型承载，且与待播槽位/歌词闸门只剩一套规则。
        val oldKeys = QueueKeys.keysOf(playbackQueue)
        val newKey = QueueKeys.keyOf(song)
        val plan = QueueInsert.plan(oldKeys, currentQueueIndex, newKey)

        // ── 幂等路径：队列一个字节都不动，只给反馈 ──────────────────────────────
        if (!plan.queueChanged) {
            when (plan.outcome) {
                QueueInsert.Outcome.ALREADY_CURRENT ->
                    snackbar.show(mainStrings.queue.queueAddToNextCurrent)
                QueueInsert.Outcome.ALREADY_NEXT ->
                    snackbar.show(mainStrings.queue.queueAddToNextAlreadyNext)
                // 其余取值在 queueChanged == false 时不可能出现；真出现就静默，
                // 不要为了「分支齐全」而编一句没有意义的提示。
                else -> Unit
            }
            return
        }

        // ── 按身份重建队列 ────────────────────────────────────────────────────
        // `plan.keys` 只可能由「旧队列的身份」+「这首歌的身份」组成；
        // 任何一项装配不出来都说明输入不一致 —— 此时**放弃这次变更**，
        // 比让队列静默缺一首安全（队列缺项 = 某首歌再也播不到）。
        // 装配逻辑收敛在 `QueueKeys.rebuild`（纯函数、有单测），不再内联在这里。
        val rebuilt = QueueKeys.rebuild(playbackQueue, plan.keys, extra = listOf(song)) ?: return

        playbackQueue = rebuilt
        currentQueueIndex = plan.currentIndex

        // ── 乱序模式：把插入项接到当前歌的播放顺序之后 ─────────────────────────
        if (playMode == QueueModes.SHUFFLE) {
            val fixed = QueueInsert.shuffleAfterInsert(
                oldKeys = oldKeys,
                newKeys = plan.keys,
                shuffled = shuffledIndices,
                newCurrentIndex = plan.currentIndex,
                insertPos = plan.insertPos,
            )
            if (fixed != null) {
                shuffledIndices = fixed
                // 契约：shuffledIndices[shuffledPosition] 恒等于正在播的那一首。
                shuffledPosition = fixed.indexOf(plan.currentIndex).coerceAtLeast(0)
            } else {
                // 修正不了就重洗一轮 —— 宁可随机性变一次，也不给出一个错的播放顺序。
                generateShuffledIndices()
            }
        }

        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)

        // ── 空队列：真的起播（缺陷 1） ────────────────────────────────────────
        // playFromQueue 自己会预载下一首，所以这条分支直接返回，不走下面的重同步。
        if (plan.shouldStartPlayback) {
            snackbar.show(mainStrings.queue.queueAddToNextStarted)
            playFromQueue(0)
            expandCard()
            return
        }

        // ── 待播槽位重同步（缺陷 3） ─────────────────────────────────────────
        // 单曲循环模式跳过：它的无缝实现是预载**当前歌自己**，
        // 改成预载下一首等于静默把单曲循环变成顺序播放（见 QueueInsert 的 KDoc）。
        if (QueueInsert.shouldPreloadAfterInsert(playMode)) {
            val nextIdx = QueueInsert.nextIndexAfterInsert(
                playMode = playMode,
                size = playbackQueue.size,
                newCurrentIndex = currentQueueIndex,
                shuffledIndices = shuffledIndices,
            )
            val nextSong = playbackQueue.getOrNull(nextIdx)
            if (nextSong != null) {
                val (nTitle, nArtist, nArtwork) = songParams(nextSong)
                playerViewModel.preloadNextSong(
                    nextSong.id, nTitle, nArtist, nArtwork,
                    // 不 allowCurrent：这里预载的**必须**是别人；若插入的恰好等于当前歌，
                    // QueueInsert 已经在上面按 ALREADY_CURRENT 幂等返回了。
                    allowCurrent = false,
                    sourceKey = nextSong.source,
                    sourceId = nextSong.sourceId,
                    mediaId = nextSong.mediaId,
                )
            }
        }

        // ── 反馈 ──────────────────────────────────────────────────────────────
        // 「搬移」与「新增」对用户是两件事：后者才是他以为发生的事，
        // 前者意味着队列里本来就有这首歌（用户可能忘了）。
        snackbar.show(
            if (plan.outcome == QueueInsert.Outcome.MOVED_TO_NEXT)
                mainStrings.queue.queueAddToNextMoved
            else
                mainStrings.queue.queueAddToNextDone
        )
    }

    fun appendToQueue(song: SongItem) {
        // v2.5.3 · P1：身份走 TrackKey。跨源同号**不去重**（TrackKey 含 source），
        // 同源同号去重且幂等。
        val currentKey = playbackQueue.getOrNull(currentQueueIndex)?.let { QueueKeys.keyOf(it) }
        val songKey = QueueKeys.keyOf(song)
        if (songKey == currentKey) return
        // 去重 + 追加一次算完再赋值：`rebuild` 失败（理论上不可达）时**整条队列不变**，
        // 不会留下「去重成功但追加失败」的半成品状态。
        val target = QueueKeys.dedupe(QueueKeys.keysOf(playbackQueue), songKey) + songKey
        val rebuilt = QueueKeys.rebuild(playbackQueue, target, extra = listOf(song)) ?: return
        playbackQueue = rebuilt
        currentQueueIndex = QueueKeys.indexOfCurrent(QueueKeys.keysOf(rebuilt), currentKey)
            .coerceAtLeast(0)
        if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    fun replaceQueueAndPlay(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        fmMode = false
        playbackQueue = songs
        currentQueueIndex = 0
        if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
        playFromQueue(0)
        expandCard()
    }

    /**
     * 主页「我的电台」入口: 进入真正的私人 FM(INFINITY 播放模式)。
     * 主数据源是网易私人 FM 流(getPersonalFm), 失败时兜底每日推荐,
     * 保证入口点下去一定有歌。之后靠 INFINITY 的队尾续播机制继续拉 FM 流无限延伸。
     */
    fun startFm() {
        applyPlayMode(QueueModes.INFINITY)
        coroutineScope.launch(Dispatchers.IO) {
            val fm = runCatching { PlaylistApi.getPersonalFm() }.getOrDefault(emptyList())
            val songs = if (fm.isNotEmpty()) fm
                else runCatching { PlaylistApi.getDailyRecommendSongs() }.getOrDefault(emptyList())
            if (songs.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    replaceQueueAndPlay(songs)
                    // replaceQueueAndPlay 会清 fmMode, 电台标记须在其后置位。
                    fmMode = true
                    expandCard()
                }
            }
        }
    }

    fun insertAllNext(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        if (currentQueueIndex < 0) {
            replaceQueueAndPlay(songs)
            return
        }
        // v2.5.3 · P1：身份走 TrackKey。
        val currentKey = playbackQueue.getOrNull(currentQueueIndex)?.let { QueueKeys.keyOf(it) }
        // 不允许把当前歌本身"塞到下一首"——那会让当前歌在队列里被 filter 掉、
        // currentQueueIndex 指向的东西完全变了。
        val toInsert = QueueKeys.excludeCurrent(QueueKeys.keysOf(songs), currentKey)
        if (toInsert.isEmpty()) return
        val insertSet = toInsert.toHashSet()
        val filtered = QueueKeys.without(QueueKeys.keysOf(playbackQueue), insertSet).toMutableList()
        val newCurrentIndex = QueueKeys.indexOfCurrent(filtered, currentKey).coerceAtLeast(0)
        val insertPos = (newCurrentIndex + 1).coerceIn(0, filtered.size)
        filtered.addAll(insertPos, toInsert)
        playbackQueue = QueueKeys.rebuild(playbackQueue, filtered, extra = songs) ?: return
        currentQueueIndex = newCurrentIndex
        if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    fun appendAllToQueue(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        if (currentQueueIndex < 0) {
            replaceQueueAndPlay(songs)
            return
        }
        // v2.5.3 · P1：身份走 TrackKey（原先按裸 id 的 existingIds 过滤）。
        val existingKeys = QueueKeys.keysOf(playbackQueue)
        val newKeys = QueueKeys.missing(existingKeys, QueueKeys.keysOf(songs)).toHashSet()
        val newSongs = QueueKeys.selectSongs(songs, newKeys)
        if (newSongs.isEmpty()) return
        // 尾追加不动 currentQueueIndex 前面的项，无需修正索引。
        playbackQueue = playbackQueue + newSongs
        if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    /**
     * 「▶ 播放全部」（首页/库页的歌单·榜单卡片）统一入口：**先播、不等网络、不弹二次确认**。
     *
     * v1.3.0 实测问题：点 ▶ 后数秒毫无反馈，之后才弹出「共 N 首歌曲 / 现在播放 / 插播」。
     * 而 ▶ 的语义就是"立即播放"，二次确认对一键播放是冗余的（插播仍可在歌曲长按菜单里用）。
     * 现在：命中 [ContentCache] 直接开播（零等待、有反馈）；未命中先 Toast 再拉取后播放。
     */
    fun playPlaylistNow(playlistId: Long) {
        val cached = ContentCache.getPlaylistSongs(playlistId)
        if (!cached.isNullOrEmpty()) {
            replaceQueueAndPlay(cached)
            return
        }
        Toast.makeText(context, mainStrings.loading, Toast.LENGTH_SHORT).show()
        coroutineScope.launch {
            val songs = runCatching { PlaylistApi.getPlaylistDetail(playlistId) }.getOrDefault(emptyList())
            if (songs.isNotEmpty()) replaceQueueAndPlay(songs)
        }
    }

    /**
     * 「▶ 播放全部」（库页的专辑卡片）：专辑曲目通常在本地收藏单曲里，直接开播；
     * 云端收藏但本地没有时才回落网络（先 Toast 反馈，不再让点击"石沉大海"）。
     */
    fun playAlbumNow(albumId: Long) {
        val local = LibraryManager.getSongsByAlbumId(context, albumId)
        if (local.isNotEmpty()) {
            replaceQueueAndPlay(local)
            return
        }
        Toast.makeText(context, mainStrings.loading, Toast.LENGTH_SHORT).show()
        coroutineScope.launch {
            val songs = runCatching {
                RetrofitClient.api.getAlbumDetail(albumId).songs?.map {
                    SongItem(
                        id = it.id, name = it.name, artists = it.artists,
                        album = it.album, duration = it.getDurationMs()
                    )
                }.orEmpty()
            }.getOrDefault(emptyList())
            if (songs.isNotEmpty()) replaceQueueAndPlay(songs)
        }
    }

    // 切换播放模式：顺序循环 → 单曲 → 乱序 → 顺序线性 → 相似无限(FM)，循环。
    val onTogglePlayMode: () -> Unit = {
        fmMode = false
        applyPlayMode((playMode + 1) % 5)
    }

    // ============ 导航控制器 ============
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val isInMain = navBackStackEntry?.destination?.route == NavRoutes.HOME

    /**
     * 「转到歌手」的**唯一出口**（v2.6.1 · P0）。
     *
     * 三态处置，判据全部来自 [ArtistNavigator]（纯函数、有单测）：
     *
     * - [ArtistNav.Direct] → 带 source 的两段路由 `artist/{source}/{artistId}`，
     *   进**本源**艺人页。QQ 曲目走的是 `singerMID`，不再经过网易云。
     * - [ArtistNav.Search] → 切到搜索 tab 并预填艺人名。这是「**找不到但用户能理解**」
     *   的那条路：身份不可信时绝不猜一个艺人出来（AGENTS.md 铁律 20/21）。
     * - [ArtistNav.Unavailable] → 什么都不做（连名字都没有，跳搜索也搜不出东西）。
     *
     * 定义顺序在 [resolveAndNavigate] **之前**是硬要求：Kotlin 的局部函数不能前向引用
     * （写成反过来会直接编译不过，不会静默）。
     */
    fun navigateToArtist(song: SongItem) {
        when (val nav = ArtistNavigator.resolve(song)) {
            is ArtistNav.Direct -> {
                if (progress.value > 0.01f) collapseCard()
                navController.navigate(NavRoutes.artist(nav))
            }

            is ArtistNav.Search -> {
                if (progress.value > 0.01f) collapseCard()
                // 先切 tab 再投递关键词：SearchScreen 只在可见时才消费它
                // （见那里的 LaunchedEffect），顺序反了会丢掉这次预填。
                selectedTab = SEARCH_TAB_INDEX
                pendingSearchQuery = nav.keyword
                snackbar.show(mainStrings.source.artistNavSearchFallback)
                Log.i(
                    TAG_ARTIST_NAV,
                    "转到歌手降级为搜索 reason=${nav.reason} song=${song.trackKey} keyword=${nav.keyword}",
                )
            }

            ArtistNav.Unavailable -> {
                Log.w(TAG_ARTIST_NAV, "转到歌手：无艺人信息，忽略 song=${song.trackKey}")
            }
        }
    }

    /**
     * 「转到专辑」的**唯一出口**（v2.6.2 · P0）。
     *
     * 与 [navigateToArtist] 完全同构的三态处置，判据全部来自 [AlbumNavigator]
     * （纯函数、有单测）：
     *
     * - [AlbumNav.Direct] → 带 source 的两段路由 `album/{source}/{albumId}`，
     *   进**本源**专辑页。QQ 曲目走的是 `albumMID`，不再经过网易云。
     * - [AlbumNav.Search] → 切到搜索 tab 并预填关键词。这是「**找不到但用户能理解**」
     *   的那条路：身份不可信时绝不猜一张专辑出来（AGENTS.md 铁律 14/15）。
     * - [AlbumNav.Unavailable] → 什么都不做（连一个能当关键词的串都没有）。
     *
     * ## 与 v2.6.1 的艺人出口相比，这里少了两件事（都是**删掉**的）
     *
     * 1. **没有 `Dispatchers.IO` 协程**：旧实现把「读一个已经在手上的字段」包在一次
     *    后台调度里，只为了保持与艺人分支的形状一致。身份就在 `song` 里，
     *    跳转决策是纯函数 ⇒ 不需要线程切换，也不需要 `withContext(Dispatchers.Main)`。
     * 2. **没有任何"补 id"的回落**：旧实现 `albumId == null` 时直接静默 no-op
     *    （连一次网络请求都不发，所以 logcat 里什么都不留）—— 那正是本 P0 的第二种症状。
     *    现在这条情形走 [AlbumNav.Search]，**有提示、有日志**。
     *
     * 定义顺序在 [resolveAndNavigate] **之前**是硬要求：Kotlin 的局部函数不能前向引用。
     */
    fun navigateToAlbum(song: SongItem) {
        when (val nav = AlbumNavigator.resolve(song)) {
            is AlbumNav.Direct -> {
                if (progress.value > 0.01f) collapseCard()
                // 成功路径也留痕（**与艺人出口的一个有意的差异**）：本版的验收要求
                // logcat 里看得到**路由参数**，而修复前这条路径 release 包一条日志都没有
                // —— 出了"跳到某张莫名其妙的专辑"时，唯一能对上的就是这一行。
                // 艺人出口保持 v2.6.1 原样（只有降级留痕），不为对称去动它。
                Log.i(
                    TAG_ALBUM_NAV,
                    "转到专辑 source=${nav.source.key} id=${nav.id} song=${song.trackKey}",
                )
                navController.navigate(NavRoutes.album(nav.source, nav.id))
            }

            is AlbumNav.Search -> {
                if (progress.value > 0.01f) collapseCard()
                // 先切 tab 再投递关键词：SearchScreen 只在可见时才消费它
                // （见那里的 LaunchedEffect），顺序反了会丢掉这次预填。
                selectedTab = SEARCH_TAB_INDEX
                pendingSearchQuery = nav.keyword
                snackbar.show(mainStrings.source.albumNavSearchFallback)
                Log.i(
                    TAG_ALBUM_NAV,
                    "转到专辑降级为搜索 reason=${nav.reason} song=${song.trackKey} keyword=${nav.keyword}",
                )
            }

            AlbumNav.Unavailable -> {
                Log.w(TAG_ALBUM_NAV, "转到专辑：无专辑信息，忽略 song=${song.trackKey}")
            }
        }
    }

    /**
     * 从歌曲跳到歌手/专辑页(需求: 类 Apple Music 的来源回溯)。
     *
     * ## v2.6.1 · P0：艺人那一半
     *
     * 这条路以前会把 QQ 曲目送到**错误的艺人页**：补 id 的回落打的是**网易云**的
     * `/eapi/v3/song/detail`（QQ 的 bit62 合成 id 在那里必然查空 ⇒ 静默失败），
     * 而跳的是硬编码网易云的 `NavRoutes.artist(artistId: Long)`（周杰伦 `4558` → 马洪波）。
     * 现在身份判定收在 [ArtistNavigator] 一处，本函数只负责**执行**它的结论。
     *
     * ## v2.6.2 · P0：专辑那一半（本版修的）
     *
     * 专辑分支以前做两件事，两件都错：
     *
     * 1. 只读 `song.album?.id`（**QQ 域数字**），从不读 `song.musicSource`；
     * 2. 走 `NavRoutes.album(albumId: Long)` —— composable 里把 `sourceKey` **写死**成
     *    `MusicSource.NETEASE`。
     *
     * 于是 QQ 的数字专辑 id 被拿去查网易云：陈奕迅《What's Going On...?》`22276`
     * → **《百万金曲 陈小云2 苦恋梦 免失志》/ 陈小云**（PCL110 + S6 两台真机复现，
     * 页面渲染完全正常）；而 `album.id` 缺失时（老队列 / 冷启动恢复）**静默无反应**。
     * 现在同样收在 [AlbumNavigator] 一处。
     *
     * 本函数保留「一个布尔切换两个出口」的形状，是因为长按菜单就是这么调它的
     * （`resolveAndNavigate(song, toArtist = ...)`）；**判据一个字都不在这里**。
     */
    fun resolveAndNavigate(song: SongItem, toArtist: Boolean) {
        if (toArtist) {
            navigateToArtist(song)
        } else {
            navigateToAlbum(song)
        }
    }

    // ---------- 剪贴板分享链接识别 ----------
    // 别人发来的 music.163.com 链接(或 163cn.tv 短链)在回到 app 时自动打开;
    // 单曲则载入播放器但不播放, 由用户按播放键才开始。
    val lastHandledClip = remember { mutableStateOf("") }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event != androidx.lifecycle.Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val clip = try {
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.primaryClip?.getItemAt(0)?.text?.toString()
            } catch (_: Exception) { null } ?: return@LifecycleEventObserver
            if (clip == lastHandledClip.value || !clip.contains("163")) return@LifecycleEventObserver
            lastHandledClip.value = clip

            coroutineScope.launch(Dispatchers.IO) {
                var target = clip
                // 163cn.tv 短链: HEAD 跟随 302 拿真实地址
                if (target.contains("163cn.tv")) {
                    target = runCatching {
                        val client = okhttp3.OkHttpClient.Builder()
                            .followRedirects(false).build()
                        val resp = client.newCall(
                            okhttp3.Request.Builder().url(target).head().build()
                        ).execute()
                        resp.header("Location") ?: ""
                    }.getOrDefault("")
                }
                fun idOf(pattern: Regex): Long? =
                    pattern.find(target)?.groupValues?.get(1)?.toLongOrNull()

                val songId = idOf(Regex("music\\.163\\.com/song\\?id=(\\d+)"))
                val albumId = idOf(Regex("music\\.163\\.com/album\\?id=(\\d+)"))
                val playlistId = idOf(Regex("music\\.163\\.com/playlist\\?id=(\\d+)"))
                val artistId = idOf(Regex("music\\.163\\.com/artist\\?id=(\\d+)"))

                when {
                    albumId != null -> withContext(Dispatchers.Main) {
                        navController.navigate(NavRoutes.album(albumId))
                    }
                    playlistId != null -> withContext(Dispatchers.Main) {
                        navController.navigate(NavRoutes.playlist(playlistId))
                    }
                    artistId != null -> withContext(Dispatchers.Main) {
                        // v2.6.1 · P0：显式声明音源。这个 id 由 `music.163.com/artist?id=` 的
                        // 正则解析而来，**按构造**就是网易云身份 —— 而「按构造」正是本 P0 里
                        // 唯一没被写下来的东西。老的单参数重载会把 source 静默补成网易云，
                        // 于是「谁都可以用它」；写成两段路由之后，用错必须由作者显式写错。
                        navController.navigate(NavRoutes.artist(MusicSource.NETEASE, artistId.toString()))
                    }
                    songId != null -> {
                        // 单曲: 载入播放器但不自动播放, 等用户按下播放键
                        val detail = runCatching {
                            PlaylistApi.getSongsByIds(listOf(songId))
                        }.getOrDefault(emptyList()).firstOrNull()
                            ?: return@launch
                        withContext(Dispatchers.Main) {
                            currentSong = detail
                            playbackQueue = listOf(detail)
                            currentQueueIndex = 0
                            shuffledIndices = emptyList()
                            PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
                            val (t, a, w) = songParams(detail)
                            playerViewModel.prepareSongWithoutPlay(detail.id, t, a, w)
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) {
        AboutScreen(onBack = { showAbout = false })
        return
    }

    var showWebLogin by remember { mutableStateOf(false) }
    // v2.1.0 · C：QQ 音乐登录浮层（与网易云那个**完全独立**：两份 cookie、两条登录路径）。
    var showQqLogin by remember { mutableStateOf(false) }
    // v2.1.0 · C（hotfix 4）：QQ 登录改为「自绘二维码为主、网页登录兜底」。
    var showQqQr by remember { mutableStateOf(false) }
    // v2.1.1：手机号验证码登录。为**微信用户**而加 —— 他们多半没有 QQ 号，
    // 而网页版的微信登录是「网站应用扫码」，只能被另一台设备的微信扫（同一台手机
    // 扫不了自己的屏幕，微信也不认相册里的登录码）；官方那种一键微信登录要微信
    // 开放平台的**签名**配对，fork 不可能满足。详见 QqPhoneLogin 的注释。
    var showQqPhone by remember { mutableStateOf(false) }
    // 扫码确认后交给 WebView 的起始地址与 cookie（见 QqLoginOverlay 的参数说明）。
    var qqLoginStartUrl by remember { mutableStateOf<String?>(null) }
    var qqLoginCookies by remember { mutableStateOf<String?>(null) }
    var qqLoginTrigger by remember { mutableIntStateOf(0) }
    var cookieRefreshTrigger by remember { mutableIntStateOf(0) }

    // 登录成功后后台拉取一次云端收藏，供收藏页使用。
    LaunchedEffect(cookieRefreshTrigger) {
        if (cookieRefreshTrigger > 0) {
            LibraryManager.refreshFromCloud(context)
        }
    }
    // v2.1.0 · C：QQ 音乐登录。
    //
    // 登录浮层本体在 ui/components/QqLoginOverlay.kt —— 那里记录了「必须用桌面 UA」
    // 与「必须接管 window.open 弹窗」两条真机踩出来的结论（v2.1.0 hotfix 1：
    // 移动版 H5 页没有登录入口，用户报告「网页版会自动从 pc 跳到手机」）。
    LaunchedEffect(qqLoginTrigger) {
        if (qqLoginTrigger > 0) {
            // 登录成功后拉一次会员状态（失败不影响登录态本身：cookie 已经在本地了）。
            runCatching {
                com.takahashirinta.ncrust.qq.QqApi.fetchProfile()?.let {
                    com.takahashirinta.ncrust.qq.QqAuthStore.saveProfile(context, it)
                }
            }
        }
    }
    if (showQqPhone) {
        com.takahashirinta.ncrust.ui.components.QqPhoneLoginDialog(
            onLoggedIn = { cookie ->
                // cookie 由 QqApi 从 Login 的 data 拼好；落盘走的是与 WebView 登录
                // 同一条路（QqAuthStore.saveCookie → merge），所以两种登录方式拿到的
                // 登录态形状一致，业务侧不需要区分。
                com.takahashirinta.ncrust.qq.QqAuthStore.saveCookie(context, cookie)
                showQqPhone = false
                qqLoginTrigger++
            },
            onUseWebLogin = {
                qqLoginStartUrl = null
                qqLoginCookies = null
                showQqPhone = false
                showQqLogin = true
            },
            onDismiss = { showQqPhone = false },
        )
        return
    }
    if (showQqQr) {
        com.takahashirinta.ncrust.ui.components.QqQrLoginDialog(
            onConfirmed = { url, cookies ->
                // 扫码已确认：把跳转地址与 cookie 交给 WebView 完成最后一步换票
                // （换音乐票据那一步没有账号无法实测，而复用站点自己的脚本已被真机验证可登录）。
                qqLoginStartUrl = url
                qqLoginCookies = cookies
                showQqQr = false
                showQqLogin = true
            },
            onUseWebLogin = {
                // 轮询在部分出口 IP 上会被 WAF 拦；这条兜底路已实测可登。
                qqLoginStartUrl = null
                qqLoginCookies = null
                showQqQr = false
                showQqLogin = true
            },
            onDismiss = { showQqQr = false },
        )
        return
    }
    if (showQqLogin) {
        com.takahashirinta.ncrust.ui.components.QqLoginOverlay(
            onLoggedIn = {
                showQqLogin = false
                qqLoginStartUrl = null
                qqLoginCookies = null
                qqLoginTrigger++
            },
            onDismiss = {
                showQqLogin = false
                qqLoginStartUrl = null
                qqLoginCookies = null
            },
            startUrl = qqLoginStartUrl,
            injectCookies = qqLoginCookies,
        )
        return
    }

    if (showWebLogin) {
        Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
            AndroidView(
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        android.webkit.CookieManager.getInstance()
                            .setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(
                                view: android.webkit.WebView, url: String
                            ) {
                                val cookie = android.webkit.CookieManager.getInstance()
                                    .getCookie(url)
                                if (cookie != null && cookie.contains("MUSIC_U=")) {
                                    com.takahashirinta.ncrust.auth.CookieManager
                                        .saveCookie(ctx, cookie)
                                    RetrofitClient.updateCookie(cookie)
                                    showWebLogin = false
                                    cookieRefreshTrigger++
                                }
                            }
                        }
                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                        loadUrl("https://music.163.com/#/login")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            // 顶部渐隐 scrim + 裸叉号：与二级页面返回箭头共用 TopScrimIconButton，
            // scrim 覆盖白色登录页顶部区域相当于"浏览器 chrome"，图标不再是孤立的黑方块。
            TopScrimIconButton(
                icon = Icons.Default.Close,
                contentDescription = LocalStrings.current.close,
                onClick = { showWebLogin = false },
                alignment = Alignment.TopEnd
            )
        }
        return
    }

    val navStrings = LocalStrings.current
    // 导航项在底部导航(窄屏)与左侧 sidebar(宽屏)之间共用。
    val navTabs = listOf(
        Icons.Default.Home to navStrings.tabHome,
        Icons.Default.LibraryMusic to navStrings.tabLibrary,
        Icons.Default.Search to navStrings.tabSearch,
        Icons.Default.Person to navStrings.tabUser,
    )
    val onNavSelected: (Int) -> Unit = { tab ->
        selectedTab = tab
        if (!isInMain) navController.popBackStack(NavRoutes.HOME, false)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 车机把应用窗口内容区 inset 到系统栏之间（实测内容区 620px，但
            // WindowInsets 全为 0），所以这里不再自己 padding，只让背景铺满内容区；
            // 位置计算用 contentHeightPx（扣掉系统栏的资源兜底值）。
            .background(LocalMetroColors.current.background)
            .onSizeChanged { rootHeightPx = it.height.toFloat() }
    ) {
        // v1.2.0 · B3：自定义背景图铺在最底层。各页面自己会用
        // LocalMetroColors.current.background 铺不透明底色，所以背景图必须垫在最下面。
        CustomBackgroundLayer()

        // PlayerCardOverlay is FIRST child: processes first in Compose Main pass (siblings are
        // dispatched in composition order). Its inner consumer modifier in PlayerCard prevents
        // Scaffold's SongCards from receiving events when the player is fully expanded.
        // zIndex(1f) ensures it renders above Scaffold (default zIndex=0) despite being listed first.
        //
        // v1.3.0 · 死带修复 A：**没有歌就不挂载这一层**。
        // 这层是全屏 fillMaxSize + translationY(collapsedOffsetY)，以前无论有没有歌都常驻，
        // 于是「清空队列」只清了内部的 mini bar，外层命中区照旧留在屏幕下半部（实测
        // 详情页 y≥1872 的交互元素会被它吞掉）。条件挂载让清空队列/冷启动无歌时这一层
        // 彻底不存在。
        // 副作用（已与维护者确认可接受）：清空队列后不再有「暂无播放 + 一键开播」入口
        // （PlayerCard 里 hasSong=false 的那个播放键）。v1.3.1 计划以首页/库页空态替代。
        if (currentSong != null) {
        Box(modifier = Modifier.fillMaxSize().zIndex(1f)) {
        PlayerCardOverlay(
            song = currentSong,
            isPlaying = isPlaying,
            progress = progress,
            collapsedOffsetY = collapsedOffsetY,
            screenHeightPx = contentHeightPx,
            totalDragDistancePx = totalDragDistancePx,
            playbackQueue = playbackQueue,
            currentQueueIndex = currentQueueIndex,
            playMode = playMode,
            onPlayPause = { playerViewModel.togglePlayPause() },
            onDismiss = { collapseCard() },
            onPlayPrevious = { playPrevious() },
            onPlayNext = { playNext() },
            onRemoveFromQueue = { removeFromQueue(it) },
            onPlayFromQueue = { playFromQueue(it) },
            onMoveInQueue = ::moveInQueue,
            onTogglePlayMode = onTogglePlayMode,
            onPlayNothing = {
                // 暂无播放 → 一键开始: 优先缓存里的每日推荐, 否则现拉
                coroutineScope.launch(Dispatchers.IO) {
                    val songs = ContentCache.homeDailySongs?.takeIf { it.isNotEmpty() }
                        ?: runCatching { PlaylistApi.getDailyRecommendSongs() }.getOrDefault(emptyList())
                    if (songs.isNotEmpty()) {
                        withContext(Dispatchers.Main) { replaceQueueAndPlay(songs) }
                    }
                }
            },
            onClearQueue = {
                // 显式清空: 停播 + 回到暂无播放态。进程重建保留队列是特性(接着听),
                // 想清空时用户有明确入口, 不做隐式自动清空(打断连续收听习惯)。
                playbackQueue = emptyList()
                currentQueueIndex = -1
                currentSong = null
                shuffledIndices = emptyList()
                shuffledPosition = 0
                playerViewModel.stopService()
                PlaybackStateManager.clearQueue(context)
                collapseCard()
            },
            onSongInfoClick = {
                // 全屏播放器点歌名: 上拉"转到歌手/转到专辑"菜单(复用长按菜单 sheet)
                currentSong?.let { menuSong = it; menuSongActions = emptyList() }
            },
            // v2.5.4 · E：竖屏托盘第二行「作者」那一段 → 直接进艺人页。
            // 与 `onSongInfoClick` 的分工：那一个是「先在菜单里选转到歌手/转到专辑」，
            // 这一个已经是明确意图，不再多一次选择。
            //
            // v2.6.1 · P0：这里以前收一个裸 `Long` 再拼老路由（硬编码网易云），
            // 于是 QQ 曲目点作者名 = 跳到网易云的同号艺人（周杰伦 4558 → 马洪波）。
            // 现在整首歌交给 `navigateToArtist`，身份判定与「跳搜索」兜底都在那里，
            // 与长按菜单走**同一个出口** —— 两个入口两套判据正是本 P0 的形状。
            onArtistClick = { song -> navigateToArtist(song) },
            // B2：保存当前队列为云歌单（创建 + 批量加歌两步走，写操作由 PlaylistWriteGate 串行）。
            onSavePlaylist = {
                playlistSnapshot = playbackQueue
                showCreatePlaylist = true
            },
            // P1：大屏模式开关。退出大屏时进度保持 1（回到竖屏全屏播放器，不是收起态）。
            bigScreen = bigScreen,
            onToggleBigScreen = onToggleBigScreen,
            // T4：自动旋转开关（播放器里的旋转图标）。
            autoRotate = autoRotate,
            onToggleAutoRotate = onToggleAutoRotate
        )
        } // end PlayerCardOverlay wrapper
        } // end if (currentSong != null) —— 死带修复 A：无播放时不挂载

        // 宽屏左侧常驻导航（Apple Music 式）：背景铺满整高(含状态栏后)，内容自行
        // 避让系统栏；底部 miniBar 仍整宽叠加，自然盖住侧栏空余的底部。
        // P1：大屏模式下**不挂载**侧栏与底部导航。横屏窗口宽度必然 >= 600dp，若不排除
        // 就会把平板的左侧栏一起画出来：它 zIndex 0.5 在播放器卡片（1f）之下、视觉被卡片
        // 盖住，但左侧栏正好压在卡片左栏（封面/歌名/音质）底下 —— 卡片一旦有一处不参与
        // 命中测试，点下去就会误切 tab。隐藏是零风险的（大屏模式本来就是"只看播放器"）。
        if (isWideLayout && !bigScreen) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .zIndex(0.5f)
                    .width(sidebarWidthDp)
                    .fillMaxHeight()
                    .background(LocalMetroColors.current.surface)
            ) {
                MetroSidebar(
                    items = navTabs.map { MetroSidebarItem(it.first, it.second) },
                    selectedIndex = selectedTab,
                    onSelected = onNavSelected,
                    width = sidebarWidthDp,
                    backgroundColor = Color.Transparent,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
                )
            }
        }

        // 宽屏无底部导航，miniBar 下方的系统导航栏区域由 PlayerCard 折叠态卡背
        // 用 surface 填充（见 PlayerCard），这里不再重复铺。

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.systemBars.asPaddingValues())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 宽屏内容栏右移，给左侧 sidebar 让位（详情页与 tab 屏一起移）。
                    // P1：大屏模式下 sidebar 不挂载（见上），这里必须一起收掉 —— 否则
                    // 大屏里一旦播放器收起（progress→0），下层页面会凭空左空 200dp 死带。
                    .padding(start = if (isWideLayout && !bigScreen) sidebarWidthDp else 0.dp)
            ) {
                // 主 tab 屏一直挂载（下层）：以前用 if(isInMain) 条件挂载，导航返回时
                // tab 屏瞬间 mount + LaunchedEffect 立即触发，撞在 nav slide 动画的第一帧
                // → 主线程 gg，返回感觉卡。现在 tab 屏永远存在，nav 详情页通过 opaque bg
                // 从上层覆盖它，返回时 nav 只需 slide 详情页出去，tab 屏本来就在下面可见。
                // 副作用：NavGraph 空 HOME destination 内容为空且不占布局，pointer 天然穿透。
                Box(modifier = Modifier.fillMaxSize()) {
                    when (selectedTab) {
                        0 -> HomeScreen(
                            onSongClick = { playSongItem(it) },
                            onPlaylistClick = { playlistId ->
                                navController.navigate(NavRoutes.playlist(playlistId))
                            },
                            // ▶ 立即播放（缓存优先，未命中先提示再拉取）——不再弹二次确认。
                            onPlayPlaylist = { playlistId -> playPlaylistNow(playlistId) },
                            onPlayDailyAll = { songs ->
                                // 直接替换队列并从头播放。
                                // 旧实现循环 addToQueue → 每次都把 currentQueueIndex 推到队尾，
                                // 而 playSong 播的是 songs[0]，两者错位，导致下一首回绕 0 → 永远回放首曲。
                                replaceQueueAndPlay(songs)
                            },
                            onSongInsertNext = { insertNext(it) },
                            onSongAppendToQueue = { appendToQueue(it) },
                            onShowSongMenu = { song, actions -> showSongMenu(song, actions) },
                            // 主页「我的电台」入口: 之前一直没传, HomeScreen 的 FM 卡因此永不渲染
                            onPlayFm = { startFm() },
                            // v1.4.0 · 音乐人推荐：本地口味命中才给 id，否则 null（卡片整块不渲染）。
                            // 判定纯本地（收藏单曲艺人 ∩ 锚点），不发请求；配置默认空 → 其他用户看不到。
                            artistRecoArtistId = artistRecoArtistId,
                            // v2.6.1 · P0：推荐卡的 id 来自 `reco/ArtistReco.kt` 的
                            // `artist_reco_target_id`，是配置里写死的**网易云** id；
                            // 显式带音源，理由同上面的剪贴板入口。
                            onArtistRecoClick = { id ->
                                navController.navigate(NavRoutes.artist(MusicSource.NETEASE, id.toString()))
                            }
                        )

                        1 -> LibraryScreen(
                            onSongClick = { playSongItem(it) },
                            onAlbumClick = { albumId -> navController.navigate(NavRoutes.album(albumId)) },
                            // ▶ 立即播放：本地收藏单曲里有就直接播（秒开），否则提示后走网络。
                            onPlayAlbum = { albumId -> playAlbumNow(albumId) },
                            onPlaylistClick = { pl ->
                                navController.navigate(NavRoutes.playlist(pl.id, pl.name, pl.coverImgUrl))
                            },
                            onPlayPlaylist = { playlistId -> playPlaylistNow(playlistId) },
                            // v2.3.0 · A：QQ 歌单在「歌单」tab 里直接平铺，点击进入它的
                            // **只读**详情页（v2.2.0 既有页面，语义未变）。
                            onQqPlaylistClick = { pl ->
                                navController.navigate(
                                    NavRoutes.qqPlaylistDetail(pl.key.id, pl.key.ownerId, pl.dirId, pl.name)
                                )
                            },
                            // v2.3.0 · B：本地歌单 → 可编辑详情页。
                            onLocalPlaylistClick = { key ->
                                navController.navigate(NavRoutes.localPlaylist(key.source.key, key.ownerId, key.id))
                            },
                            // 收藏单曲「播放全部」：**先播、后补**。
                            // 用本地已加载的收藏单曲立即开播（点击即出声、有反馈），剩余详情在
                            // 后台补齐后按红心顺序追加到队尾，避免为了"共 N 首"空等数秒网络。
                            // 也不再弹「现在播放 / 插播」——▶ 就是立即播放。
                            onPlayAllLiked = {
                                val cached = LibraryManager.getSavedSongs(context)
                                if (cached.isNotEmpty()) {
                                    replaceQueueAndPlay(cached)
                                    coroutineScope.launch {
                                        val all = runCatching { LibraryManager.loadAllLikedSongs(context) }
                                            .getOrNull().orEmpty()
                                        // 期间用户若换了队列就不追加，避免把红心歌单灌进别的播放上下文。
                                        if (playbackQueue.firstOrNull()?.id == cached.first().id) {
                                            val known = playbackQueue.map { it.id }.toHashSet()
                                            val rest = all.filter { it.id !in known }
                                            if (rest.isNotEmpty()) appendAllToQueue(rest)
                                        }
                                    }
                                } else {
                                    Toast.makeText(context, mainStrings.loading, Toast.LENGTH_SHORT).show()
                                    coroutineScope.launch {
                                        val all = runCatching { LibraryManager.loadAllLikedSongs(context) }
                                            .getOrNull().orEmpty()
                                        if (all.isNotEmpty()) replaceQueueAndPlay(all)
                                    }
                                }
                            },
                            onSongInsertNext = { insertNext(it) },
                            onSongAppendToQueue = { appendToQueue(it) },
                            onShowSongMenu = { song, actions -> showSongMenu(song, actions) },
                            refreshTrigger = cookieRefreshTrigger,
                            localPlaylistsTick = localPlaylistsTick
                        )

                        2 -> SearchScreen(
                            onSongClick = { playSongItem(it) },
                            onAlbumClick = { albumId -> navController.navigate(NavRoutes.album(albumId)) },
                            // v2.6.1 · P0：搜索页的艺人 tab 至今**只**由网易云的
                            // `cloudsearch/pc type=100` 填充（见 SearchViewModel 的 100 分支），
                            // 所以这里的 id 恒是网易云十进制 id，走带 source 的两段路由同样正确
                            // —— 顺带把「老路由 = 网易云身份」这个隐式约定显式化。
                            onArtistClick = { artistId ->
                                navController.navigate(
                                    NavRoutes.artist(MusicSource.NETEASE, artistId.toString())
                                )
                            },
                            // v2.6.1 · P0：艺人跳转的搜索兜底投递口（见 pendingSearchQuery）。
                            externalQuery = pendingSearchQuery,
                            onExternalQueryConsumed = { pendingSearchQuery = null },
                            // E：空查询态榜单入口 → 复用歌单详情（榜单就是歌单）。
                            onPlaylistClick = { playlistId -> navController.navigate(NavRoutes.playlist(playlistId)) },
                            onInsertNext = { insertNext(it) },
                            onAppendToQueue = { appendToQueue(it) },
                            onShowSongMenu = { song, actions -> showSongMenu(song, actions) },
                            onAlbumBatch = { albumId, action ->
                                coroutineScope.launch {
                                    try {
                                        val response = RetrofitClient.api.getAlbumDetail(albumId)
                                        val songs = (response.songs ?: emptyList()).map { s ->
                                            SongItem(
                                                id = s.id,
                                                name = s.name,
                                                artists = s.artists,
                                                album = s.album,
                                                duration = s.getDurationMs()
                                            )
                                        }
                                        if (songs.isNotEmpty()) {
                                            when (action) {
                                                BatchQueueAction.PLAY_NOW -> replaceQueueAndPlay(songs)
                                                BatchQueueAction.INSERT_NEXT -> insertAllNext(songs)
                                                BatchQueueAction.APPEND -> appendAllToQueue(songs)
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            },
                            onArtistBatch = { artistName, action ->
                                coroutineScope.launch {
                                    try {
                                        val searchResponse = RetrofitClient.api.search(keyword = artistName, type = 1, limit = 30)
                                        val songs = (searchResponse.result?.songs ?: emptyList())
                                            .filter { it.artists?.any { a -> a.name == artistName } == true }
                                        if (songs.isNotEmpty()) {
                                            when (action) {
                                                BatchQueueAction.PLAY_NOW -> replaceQueueAndPlay(songs)
                                                BatchQueueAction.INSERT_NEXT -> insertAllNext(songs)
                                                BatchQueueAction.APPEND -> appendAllToQueue(songs)
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            },
                            themeIndex = themeIndex
                        )

                        3 -> UserScreen(
                            // v2.8.0：一级页只负责「点哪张卡片」，二级页的内容与所需状态
                            // 由下面的 settingsGroupContent 提供（状态全部提升在本函数里）。
                            onOpenSettingsGroup = { group ->
                                navController.navigate(NavRoutes.settingsGroup(group.id))
                            },
                        )
                    }
                }

                // NavGraph 在 tab 屏之上（上层）：空 HOME destination 不遮挡 tab 屏，
                // 打开详情页时详情页 opaque bg 从上层盖住 tab 屏；返回时 slide 出去露出 tab 屏。
                MainNavGraph(
                    navController = navController,
                    onSongClick = { playSongItem(it) },
                    onReplaceAndPlay = { replaceQueueAndPlay(it) },
                    onInsertNext = { insertAllNext(it) },
                    onSongInsertNext = { insertNext(it) },
                    onSongAppendToQueue = { appendToQueue(it) },
                    onShowSongMenu = { song, actions -> showSongMenu(song, actions) },
                    // v2.5.1 · F：页面转场开关（用户可配，默认启用）。
                    pageTransitionEnabled = pageTransitionEnabled,
                    // v2.8.0：设置二级页（路由 `settings/{group}`）的内容。
                    // 状态全部提升在 MainScreen（主题 / 语言 / 页面转场 / cookie 刷新计数 /
                    // PlayerViewModel），所以二级页由这里构造 —— 见 MainNavGraph 的参数 KDoc。
                    settingsGroupContent = { settingsGroup, onBackSettingsGroup ->
                        SettingsGroupScreen(
                            group = settingsGroup,
                            onBack = onBackSettingsGroup,
                            // ⚠️ 必须传 Activity 作用域的这个实例：二级页组合在 NavHost 里，
                            // 在那里调 viewModel() 会造出第二个 PlayerViewModel。
                            playerViewModel = playerViewModel,
                            themeIndex = themeIndex,
                            onThemeChange = onThemeChange,
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            accentSource = accentSource,
                            onAccentSourceChange = onAccentSourceChange,
                            onRefreshSystemAccent = onRefreshSystemAccent,
                            onLanguageChange = onLanguageChange,
                            pageTransitionEnabled = pageTransitionEnabled,
                            // v2.5.1 · F：页面切换动效的唯一写入口（状态 + 落盘都在这里）。
                            onPageTransitionChange = { enabled ->
                                pageTransitionEnabled = enabled
                                PageTransitionSetting.writeEnabled(context, enabled)
                            },
                            cookieRefreshTrigger = cookieRefreshTrigger,
                            onShowWebLogin = { showWebLogin = true },
                            // 二维码为主入口；网页登录是它内部的兜底按钮。
                            onShowQqLogin = { showQqQr = true },
                            // v2.1.1：手机号验证码登录（微信用户的可用路径）。
                            onShowQqPhoneLogin = { showQqPhone = true },
                            onOpenAbout = { showAbout = true },
                        )
                    },
                    startDestination = NavRoutes.HOME
                )
            }
        }

        // 注：首页/库页的「▶ 播放全部」已改为直接播放（见 playPlaylistNow / playAlbumNow /
        // onPlayAllLiked），不再经过全局的 PlayAllDialog —— 该二次确认在这几处既慢又冗余。
        // 专辑详情 / 歌单详情页仍保留各自的 PlayAllDialog（那里是"整张播放"的显式选择，
        // 且数据已在内存里、弹窗零等待）。

        // zIndex(1.5f) renders PivotNav above PlayerCardOverlay (zIndex=1f),
        // so it appears on top of the mini player bar when the player is collapsed.
        // background 放在 navigationBarsPadding 之外：surface 覆盖 56dp 视觉栏 + 系统栏预留
        // 一整段，一直涂到物理屏幕底；如果反过来，栏下方就是透明，露出后景空隙。
        // 宽屏改用左侧 sidebar，这里不再渲染底部导航。
        if (!isWideLayout && !bigScreen) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1.5f)
                    .graphicsLayer {
                        translationY = navBarHideOffset * progress.value
                    }
                    .fillMaxWidth()
                    .background(LocalMetroColors.current.surface)
                    .navigationBarsPadding()
                    .height(56.dp)
            ) {
                // autoReserveBottomStack=false: Ncrust 用自己的 BottomOverlayInsetDp 常量管
                // 底部预留,不接 MetroShell 的 MetroBottomStackScope,否则 rememberBottomStackReservation
                // 会因缺 CompositionLocal 而 crash。
                MetroBottomNav(
                    selectedIndex = selectedTab,
                    onSelected = onNavSelected,
                    items = navTabs.map { MetroBottomNavItem(it.first, it.second) },
                    autoReserveBottomStack = false,
                )
            }
        }

        // B3：把歌曲加入某个歌单。toastText 在组合期取好（suspend 上下文不能调 @Composable）。
        suspend fun addSongsToPlaylist(playlistId: Long, songIds: List<Long>, toastText: () -> String): AddToPlaylistResult {
            val count = PlaylistEditApi.addSongs(playlistId, songIds)
            val result = if (count != null) AddToPlaylistResult.SUCCESS else AddToPlaylistResult.FAILED
            Toast.makeText(context, toastText(), Toast.LENGTH_SHORT).show()
            return result
        }

        // v2.5.0 · D：应用级 Snackbar。
        //
        // zIndex 1.6f 的取舍：在播放器卡片（1f）与底部导航（1.5f）**之上**，
        // 在长按菜单（2f）**之下** —— 菜单打开时提示条不该压在菜单上。
        //
        // 位置用 BottomOverlayInsetDp（窄屏 144dp / 宽屏 64dp）再加 12dp 缓冲：
        // 这个常量本来就是「屏幕底部被浮层遮挡的总高度」，Snackbar 要坐在
        // mini bar 之上而不是压在它身上，所以直接复用它、不新造数字。
        //
        // ⚠️ 宿主**不挂任何指针输入**（详见 AppSnackbarHost 的 KDoc）。
        // 本应用因为「看不见的地方还能点」踩过一整类坑，一个画在底部、
        // 覆盖在内容之上的浮层如果可点击，就会造出只在提示出现的 2 秒内存在的死带。
        AppSnackbarHost(
            state = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(1.6f)
                .padding(bottom = BottomOverlayInsetDp + 12.dp),
        )

        menuSong?.let { song ->
            Box(Modifier.fillMaxSize().zIndex(2f)) {
                SongMenuSheet(
                    song = song,
                    // 统一在这里追加"转到歌手/转到专辑": 所有长按菜单(首页/歌单/专辑/
                    // 歌手/收藏/搜索)自动获得回调入口, 各 Screen 无需感知导航
                    actions = listOf(
                        // B3：加入歌单。放在最前面——它是本版本新增的主操作。
                        SongMenuAction(
                            Icons.Default.PlaylistAdd,
                            LocalStrings.current.actionAddToPlaylistSheet,
                        ) {
                            pendingAddSongs = listOf(song.id)
                            showAddToPlaylist = true
                        },
                        // v2.3.0 · B：**本地**歌单。与上一项并列放在这里，是因为
                        // 「加入歌单」对用户是一件事的两个去向（云端 / 本地），
                        // 而本仓库的这条菜单是所有 Screen 唯一的歌曲入口（全屏覆盖）。
                        SongMenuAction(
                            Icons.Default.PlaylistPlay,
                            LocalStrings.current.localPlaylistAddTrack,
                        ) {
                            pendingAddSongs = listOf(song.id)
                            localPickerList = LocalPlaylistStore.readPlaylists(context)
                            pendingLocalSong = song
                            showAddToLocalPlaylist = true
                        },
                    ) + menuSongActions + listOf(
                        // v2.5.0 · D：「添加到下一首播放」。放在全局菜单里（而不是各 Screen
                        // 各自加一条），是因为这里才是**全应用唯一的歌曲入口** ——
                        // 首页/歌单/专辑/歌手/收藏/搜索/本地歌单全部经由此处，
                        // 加一次就全覆盖，也避免同一个文案在 8 个语言文件里被抄 7 遍。
                        //
                        // 排在「转到歌手/转到专辑」**之前**：它是对队列的操作，
                        // 与上面的「加入歌单/插播」同属"对这首歌做什么"，
                        // 而后两项是"离开这里去看别的"。
                        SongMenuAction(
                            Icons.Default.QueuePlayNext,
                            LocalStrings.current.queue.actionAddToNext,
                        ) {
                            insertNext(song)
                        },
                        SongMenuAction(Icons.Default.Person, LocalStrings.current.actionGoToArtist) {
                            resolveAndNavigate(song, toArtist = true)
                        },
                        SongMenuAction(Icons.Default.LibraryMusic, LocalStrings.current.actionGoToAlbum) {
                            resolveAndNavigate(song, toArtist = false)
                        },
                        // v2.4.0 · E：单曲信息页（两源版本对比 + 歌词）。**放在最后** ——
                        // 它是最「重」的一个入口（会发聚合与版权探测请求），
                        // 不该挤在「转到歌手 / 转到专辑」这两个高频操作前面。
                        SongMenuAction(Icons.Default.Info, LocalStrings.current.source.aggSongDetailAction) {
                            if (progress.value > 0.01f) collapseCard()
                            navController.navigate(NavRoutes.song(song.musicSource, songIdForRoute(song)))
                        },
                    ),
                    onDismiss = { menuSong = null }
                )
            }
        }

        // v1.3.0 · B2：「保存为歌单」对话框。队列在打开时快照一次 —— 创建/加歌是两次
        // 网络往返（写闸门间隔 2s），期间用户可能换歌，快照保证存的是点按钮那一刻的队列。
        if (showCreatePlaylist) {
            // strings 必须在组合期取出：onCreate 是 suspend lambda，里面不能调 @Composable。
            val createStrings = LocalStrings.current
            CreatePlaylistDialog(
                defaultName = defaultPlaylistName(),
                songCount = pendingSongs().size,
                onDismiss = { showCreatePlaylist = false },
                onCreate = { name, privacy ->
                    if (CookieManager.getCookie(context).isNullOrBlank()) {
                        PlaylistCreateOutcome.FAILED
                    } else {
                        val playlistId = PlaylistEditApi.createPlaylist(name, privacy)
                        when {
                            playlistId == null -> {
                                val err = PlaylistEditApi.lastError
                                if (err is PlaylistWriteResult.RateLimited) {
                                    PlaylistCreateOutcome.RATE_LIMITED
                                } else {
                                    PlaylistCreateOutcome.FAILED
                                }
                            }
                            pendingSongs().isNotEmpty() -> {
                                val ids = pendingSongs().map { it.id }
                                val outcome = addSongsToPlaylist(playlistId, ids) {
                                    createStrings.playlistSongsAdded(ids.distinct().size)
                                }
                                if (outcome == AddToPlaylistResult.SUCCESS) {
                                    PlaylistCreateOutcome.SUCCESS
                                } else {
                                    PlaylistCreateOutcome.CREATED_SONGS_FAILED
                                }
                            }
                            else -> {
                                Toast.makeText(
                                    context,
                                    createStrings.playlistCreated(name),
                                    Toast.LENGTH_SHORT
                                ).show()
                                PlaylistCreateOutcome.SUCCESS
                            }
                        }
                    }
                }
            )
        }

        // v2.3.0 · B：加入**本地**歌单。列出现有本地歌单 + 一个「新建」出口；
        // 选择后走 LocalPlaylistRepository.addTrack（规则 5/7：origin=LOCAL，
        // 若这首曾被移除过则**清除 tombstone** —— 用户的重新添加就是撤销删除意图）。
        if (showAddToLocalPlaylist) {
            val pickStrings = LocalStrings.current
            val pickSong = pendingLocalSong
            LocalPlaylistPickerDialog(
                playlists = localPickerList,
                onDismiss = { showAddToLocalPlaylist = false },
                onPick = { pl ->
                    pickSong?.let { LocalPlaylistRepository.addTrack(context, pl.key, it) }
                    localPlaylistsTick++
                    showAddToLocalPlaylist = false
                    Toast.makeText(context, pickStrings.localPlaylistAdded, Toast.LENGTH_SHORT).show()
                },
                onCreateNew = { name ->
                    val created = LocalPlaylistRepository.createLocalOnly(context, name)
                    pickSong?.let { LocalPlaylistRepository.addTrack(context, created.key, it) }
                    localPlaylistsTick++
                    showAddToLocalPlaylist = false
                    Toast.makeText(context, pickStrings.localPlaylistAdded, Toast.LENGTH_SHORT).show()
                },
            )
        }

        // v1.3.0 · B3：加入歌单选择列表。成功后由 sheet 自行关闭（它才知道本次点了哪个歌单）。
        if (showAddToPlaylist) {
            val sheetStrings = LocalStrings.current
            AddToPlaylistSheet(
                songCount = pendingAddSongs.size,
                onDismiss = { showAddToPlaylist = false },
                onPick = { playlistId ->
                    addSongsToPlaylist(playlistId, pendingAddSongs) {
                        sheetStrings.addToPlaylistSuccess
                    }
                },
                onCreateNew = {
                    showAddToPlaylist = false
                    showCreatePlaylist = true
                }
            )
        }

        // 全屏播放器展开时拦截系统返回：先收起播放器而不是直接退出应用。
        // 播放器是独立 overlay 层不在 NavHost 里，必须自己处理 back（此前侧滑会穿透到
        // Activity 根部直接把 app finish 掉）。放在 NavHost 之后组合，OnBackPressedDispatcher
        // 按注册逆序回调，保证它比 Navigation 的 BackHandler 更优先命中。
        //
        // 关键: enabled 必须走 derivedStateOf。若直接写 `progress.value > 0.01f`,
        // 就是在组合阶段读 progress —— 展开/收起动画每一帧都会重组整个 MainScreen
        // (实测展开时 Compose:recompose 119 次/65ms)。derivedStateOf 只在布尔值
        // 真正翻转(阈值穿越)时才通知, 动画期间零重组。
        val backEnabled by remember { derivedStateOf { progress.value > 0.01f } }
        BackHandler(enabled = backEnabled || bigScreen) {
            // P1：大屏模式下 BACK = 退出大屏（回到竖屏全屏播放器），而不是收起播放器 ——
            // 这是需求要求的第三条退出路径，也是用户在大屏里最自然的"返回"预期。
            if (bigScreen) onToggleBigScreen() else collapseCard()
        }
    }
}