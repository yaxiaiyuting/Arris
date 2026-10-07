/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」阶段 4：二级页内容（6 个分组共用一套骨架 + 逐项渲染）。
 */

package com.takahashirinta.ncrust.ui.screen

import com.takahashirinta.ncrust.bili.BiliPrefs
import com.takahashirinta.ncrust.bili.BiliQualityCap
import com.takahashirinta.ncrust.bili.BiliSubtitleLang
import com.takahashirinta.ncrust.bili.BiliSourceProvider
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.takahashirinta.ncrust.KeepScreenOnSetting
import com.takahashirinta.ncrust.RotationSetting
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.cache.OfflineAudioCache
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.lyric.LyricsDisplayPrefs
import com.takahashirinta.ncrust.lyric.LyricsWordAnimationMode
import com.takahashirinta.ncrust.power.BackgroundActivity
import com.takahashirinta.ncrust.reco.ArtistReco
import com.takahashirinta.ncrust.player.SongUrlFetcher
import com.takahashirinta.ncrust.ui.components.CacheUsageLine
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.MetroDropdownRow
import com.takahashirinta.ncrust.ui.components.SectionTitle
import com.takahashirinta.ncrust.ui.components.SettingActionRow
import com.takahashirinta.ncrust.ui.components.SettingSwitchRow
import com.takahashirinta.ncrust.ui.components.settingsGroupSubtitle
import com.takahashirinta.ncrust.ui.components.settingsGroupTitle
import com.takahashirinta.ncrust.ui.i18n.LanguagePreset
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.Strings
import com.takahashirinta.ncrust.ui.i18n.formatCacheBytes
import com.takahashirinta.ncrust.ui.i18n.getSavedLanguageCode
import com.takahashirinta.ncrust.ui.i18n.languagePresets
import com.takahashirinta.ncrust.ui.player.VisualizerSetting
import com.takahashirinta.ncrust.ui.settings.PREFS_SETTINGS
import com.takahashirinta.ncrust.ui.settings.SettingsEntry
import com.takahashirinta.ncrust.ui.settings.SettingsGroup
import com.takahashirinta.ncrust.ui.settings.SettingsRegistry
import com.takahashirinta.ncrust.ui.settings.SettingsRenderPlan
import com.takahashirinta.ncrust.ui.settings.SettingsRowKind
import com.takahashirinta.ncrust.ui.settings.SettingsVisibility
import com.takahashirinta.ncrust.ui.player.motion.MotionPrefs
import com.takahashirinta.ncrust.ui.theme.AccentSource
import com.takahashirinta.ncrust.ui.theme.AccentSourceSelector
import com.takahashirinta.ncrust.ui.theme.BackgroundImageManager
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors
import com.takahashirinta.ncrust.ui.theme.ThemeColorSelector
import com.takahashirinta.ncrust.ui.theme.ThemeMode
import com.takahashirinta.ncrust.ui.theme.systemAccentSupported
import com.takahashirinta.ncrust.ui.theme.themeColorPresets
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import io.github.takahashirinta.kanesumi.controls.MetroSelectorFlyout
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置二级页（一级分组卡片点进来的详情页）。
 *
 * ## 为什么是导航页而不是「设置页内部状态 + BackHandler」（结构探针 §4.2 方案①）
 *
 *  - 返回栈正确性交给导航库：系统返回、手势返回、进程重建后恢复都对；
 *  - 转场复用 `pageTransitionEnabled`（`NavGraph.kt` 的四个 lambda 是
 *    `AppMotion.pageTransitionSpec()` 的**唯一调用点**，再开一处就破坏了这个唯一性）；
 *  - 骨架直接用 [DetailScaffold]：返回箭头 / opaque 背景 / `BottomOverlayInsetDp` 都是现成的。
 *
 * ## 行从哪来
 *
 * 每张页面的行列表 = [SettingsRenderPlan.rowsOf]（= registry 声明顺序 ∩ 门控可见 ∩ 排除
 * 「由库页承载」的 4 项）。**页面与单测用的是同一个函数**，所以「某个既有设置项在旧的
 * 平铺页被删掉、但二级页忘了接」不可能悄悄发生（`SettingsRenderPlanTest` 双向等值断言）。
 *
 * ## 读写语义：一个字节都没改
 *
 * 每一项的读写仍然走**既有入口**（`KeepScreenOnSetting.write` / `RotationSetting.write` /
 * `VisualizerSetting.write` / `PageTransitionSetting.writeEnabled`（状态提升在 MainScreen）/
 * `ArtistReco.setEnabled` / `LyricsDisplayPrefs.*` 与 `PlayerViewModel.setXxx` /
 * `MotionPrefs.*` / `OfflineAudioCache.setMaxMb`），`wifi_quality` / `mobile_quality` /
 * `gapless_playback` / `lyrics_translation` / `lyrics_in_media_session` **继续用原来的裸
 * `prefs.edit()`**（迁移时**没有**顺手给它们补包装 —— 那等于新增第三套语义）。
 * `lyrics_word_animation` 的回显走 `LyricsDisplayPrefs.readWordAnimation`（读路径带一次性迁移），
 * **不是** registry 里的默认值。
 *
 * ⚠️ 本页在 `NavHost` 里组合，所以**不能**在这里调 `viewModel()`：`LocalViewModelStoreOwner`
 * 在 NavHost 目的地里是 NavBackStackEntry，`viewModel()` 会造出**第二个** `PlayerViewModel`
 * （Activity 作用域那个才是播放器在用的），歌词/音质开关就会写到一个没人听的实例上。
 * 所以 PlayerViewModel 由 `MainScreen` 显式传进来。
 */
@Composable
fun SettingsGroupScreen(
    group: SettingsGroup,
    onBack: () -> Unit,
    playerViewModel: PlayerViewModel,
    themeIndex: Int,
    onThemeChange: (Int) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    accentSource: AccentSource,
    onAccentSourceChange: (AccentSource) -> Unit,
    onRefreshSystemAccent: () -> Unit,
    onLanguageChange: (String) -> Unit,
    pageTransitionEnabled: Boolean,
    onPageTransitionChange: (Boolean) -> Unit,
    cookieRefreshTrigger: Int,
    onShowWebLogin: () -> Unit,
    onShowQqLogin: () -> Unit,
    onShowQqPhoneLogin: () -> Unit,
    /** v3.2.0 · P1：B 站扫码登录浮层（透传给 [SettingsAccountSection]）。 */
    onShowBiliLogin: () -> Unit,
    onOpenAbout: () -> Unit,
    /**
     * v3.3.0 · 用户建议：**离线列表的行可直接点播**。
     *
     * 为什么由外面传进来而不是在这里直接调播放：队列、`currentQueueIndex`、
     * `PlaybackStateManager` 的写入都归 `MainScreen` 所有（见 AGENTS.md「队列管理」），
     * 在这一层自己起播会造出第二条队列修改路径 —— 那正是「当前曲与队列不同步」
     * 那类缺陷的温床。
     *
     * 默认值 `{}` 让既有调用点零改动即可编译（本页在 UI 上是可复用的，
     * 「不给播放能力」应当是一个合法配置，而不是编译错误）。
     */
    onPlaySong: (SongItem) -> Unit = {},
) {
    // 账号组自带一整套覆盖层（账号弹窗 / 二维码登录 / 手机扫码授权），单独一页更清楚。
    if (group == SettingsGroup.ACCOUNT) {
        SettingsAccountPage(
            playerViewModel = playerViewModel,
            cookieRefreshTrigger = cookieRefreshTrigger,
            onShowWebLogin = onShowWebLogin,
            onShowQqLogin = onShowQqLogin,
            onShowQqPhoneLogin = onShowQqPhoneLogin,
            onShowBiliLogin = onShowBiliLogin,
            onBack = onBack,
        )
        return
    }

    SettingsPreferenceGroupPage(
        group = group,
        onBack = onBack,
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
        onPageTransitionChange = onPageTransitionChange,
        onOpenAbout = onOpenAbout,
        // v3.3.0：离线缓存列表的「点行即播」需要把意图交回 MainScreen（它拥有队列）。
        onPlaySong = onPlaySong,
    )
}

/**
 * 除「账号与登录」之外的 6 张二级页共用的骨架与状态。
 *
 * 进程内状态与迁移前的 `UserScreen` **逐项对应**（读的入口也一模一样）：
 * 这样「同一项在二级页显示的值」与「迁移前显示的值」是同一个来源，不存在第二套口径。
 */
@Composable
private fun SettingsPreferenceGroupPage(
    group: SettingsGroup,
    onBack: () -> Unit,
    playerViewModel: PlayerViewModel,
    themeIndex: Int,
    onThemeChange: (Int) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    accentSource: AccentSource,
    onAccentSourceChange: (AccentSource) -> Unit,
    onRefreshSystemAccent: () -> Unit,
    onLanguageChange: (String) -> Unit,
    pageTransitionEnabled: Boolean,
    onPageTransitionChange: (Boolean) -> Unit,
    onOpenAbout: () -> Unit,
    /** v3.3.0：离线缓存列表「点行即播」的出口（见 [SettingsGroupScreen] 的同名参数）。 */
    onPlaySong: (SongItem) -> Unit,
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val coroutineScope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS_SETTINGS, 0) }

    // ── 播放与音质 ────────────────────────────────────────────────────────────────
    var wifiQuality by remember { mutableIntStateOf(prefs.getInt("wifi_quality", 3)) }
    var mobileQuality by remember { mutableIntStateOf(prefs.getInt("mobile_quality", 1)) }
    var gaplessEnabled by remember { mutableStateOf(prefs.getBoolean("gapless_playback", true)) }
    // v2.0.0 · T2：播放时禁止熄屏（默认开）。走 KeepScreenOnSetting（唯一读写入口）。
    var keepScreenOnEnabled by remember { mutableStateOf(KeepScreenOnSetting.read(context)) }
    // v1.8.0 · T3：大屏模式音频可视化（默认开；关掉后可视化整块不挂载，零开销）。
    var audioVisualizerEnabled by remember { mutableStateOf(VisualizerSetting.read(context)) }

    // ── 通用 ─────────────────────────────────────────────────────────────────────
    // v1.8.0 · T4：应用内「自动旋转」。走 RotationSetting（唯一读写入口）——
    // 播放器里的旋转图标与这里共享同一份状态，"改一处另一处立刻同步"。
    var autoRotateEnabled by remember { mutableStateOf(RotationSetting.read(context)) }
    // v1.4.0 · 音乐人推荐卡片开关。默认关；目标艺人与锚点配置存在 prefs 且默认空。
    var artistRecoEnabled by remember { mutableStateOf(ArtistReco.isEnabled(context)) }
    var selectedLanguageCode by remember { mutableStateOf(getSavedLanguageCode(context)) }

    // ── 歌词 ─────────────────────────────────────────────────────────────────────
    var lyricsTranslation by remember { mutableStateOf(prefs.getBoolean("lyrics_translation", true)) }
    // v1.5.0 · B / v1.5.1 · A 逐字动画模式。读的时候顺带完成 v1.5.0 布尔开关
    // lyrics_word_by_word 的一次性迁移（**不能**用 registry 的默认值回显）。
    var lyricsWordAnimation by remember { mutableIntStateOf(LyricsDisplayPrefs.readWordAnimation(prefs)) }
    var lyricsSweepQuality by remember { mutableIntStateOf(LyricsDisplayPrefs.readSweepQuality(prefs)) }
    var lyricsFontScale by remember { mutableStateOf(LyricsDisplayPrefs.readFontScale(prefs)) }
    var lyricsInMediaSession by remember {
        mutableStateOf(prefs.getBoolean("lyrics_in_media_session", false))
    }
    /** v3.1.0 · B：B 站音源开关的进程内镜像（初值 = `BiliPrefs.read(context)`）。 */
    var biliEnabled by remember { mutableStateOf(BiliPrefs.read(context)) }
    // v3.4.8 · 问题 3：B 站音质上限 / 优先无损 FLAC。回显走**各自的 read\***（不是 registry 默认值）——
    // 盘上可能是一个非法值，而 `read*` 会按既有纪律回落默认；用 registry 默认值回显会让
    // 「盘上脏值」在界面上显示成「自动」，而实际取链用的是另一个值。
    var biliQualityCap by remember { mutableStateOf(BiliPrefs.readQualityCap(context)) }
    var biliPreferFlac by remember { mutableStateOf(BiliPrefs.readPreferFlac(context)) }
    // v3.4.8 · 问题 2：B 站字幕语言。
    var biliSubtitleLang by remember { mutableStateOf(BiliPrefs.readSubtitleLang(context)) }
    var lyricsTtmlEnabled by remember { mutableStateOf(LyricsDisplayPrefs.readTtmlEnabled(prefs)) }
    var lyricsTtmlFirst by remember { mutableStateOf(LyricsDisplayPrefs.readTtmlFirst(prefs)) }
    var lyricsRomanization by remember { mutableStateOf(LyricsDisplayPrefs.readRomanization(prefs)) }
    var dynamicFontEnabled by remember { mutableStateOf(LyricsDisplayPrefs.readDynamicFont(prefs)) }

    // ── 统一「动效强度」（v2.9.0 新增 4 键）──────────────────────────────────────
    // 档位回显 = 显式选择优先，否则用**设备静态判据解析出来的默认档**
    // （低端机 = 简洁档）。解析只读，绝不写回盘；「用户没选过」这个信息必须保留
    // （MotionPrefs.hasExplicitTier）。写入只发生在用户真的选了档位那一刻。
    //
    // v2.8.0 的 5 个炫技细分开关**不再是设置项**：它们已合并进档位，registry 里标了
    // `internal`（不渲染），所以这里不再需要它们的状态变量。
    var motionTier by remember {
        mutableIntStateOf(MotionPrefs.readTier(prefs, MotionPrefs.deviceDefaultTier(context)))
    }
    var uiMotionEnabled by remember { mutableStateOf(MotionPrefs.readUiMotionEnabled(prefs)) }
    // v3.0.0：五个「每个动效独立开关」（铁律 26）。初值走 MotionPrefs 的解析（缺 key = 开）。
    val motionSwitches = MotionPrefs.readSwitches(prefs)
    var motionShockwave by remember { mutableStateOf(motionSwitches.shockwave) }
    var motionHalo by remember { mutableStateOf(motionSwitches.halo) }
    var motionParticles by remember { mutableStateOf(motionSwitches.particles) }
    var motionWaveBands by remember { mutableStateOf(motionSwitches.waveBands) }
    var motionBreathing by remember { mutableStateOf(motionSwitches.breathing) }
    // v3.2.0：界面律动那一层的闸（总闸 + 三个律动类细粒度开关）。初值同样走 MotionPrefs。
    var motionRhythm by remember { mutableStateOf(motionSwitches.rhythm) }
    var motionCoverFloat by remember { mutableStateOf(motionSwitches.coverFloat) }
    var motionLyricPulse by remember { mutableStateOf(motionSwitches.lyricPulse) }
    var motionBarPulse by remember { mutableStateOf(motionSwitches.barPulse) }

    // ── 存储与缓存 ────────────────────────────────────────────────────────────────
    var offlineCacheMb by remember { mutableIntStateOf(OfflineAudioCache.maxMb(context)) }

    /**
     * v3.3.0：让「后台运行」那一行能在从系统设置返回后**重读白名单状态**。
     *
     * 没有它的话，用户点了「允许」回到应用，行上还写着「未允许」——
     * 那与本条反馈要修的「反馈缺失」是同一个病，只是换了个方向。
     */
    var backgroundBatteryRefresh by remember { mutableIntStateOf(0) }
    // v2.0.0 · T3：缓存占用三项分账（音频 / 图片 / 其他 cacheDir）。旧口径把图片缓存算了两遍
    // （Coil 的磁盘缓存目录就是 cacheDir/image_cache，而 folderSize(cacheDir) 已递归含它）。
    var cacheUsage by remember { mutableStateOf(CacheUsage.ZERO) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    // v2.0.0 · T3：离线缓存管理（全屏 Dialog，见 OfflineCacheOverlay.kt 的 KDoc
    // 说明为什么它不是导航页）。
    var showOfflineCacheManager by remember { mutableStateOf(false) }
    val playingSongId by playerViewModel.currentSongId.collectAsState()

    // 缓存占用要递归遍历 cacheDir，放 IO 线程算；只在存储页需要，其他页面不付这份开销。
    if (group == SettingsGroup.STORAGE) {
        LaunchedEffect(Unit) {
            cacheUsage = withContext(Dispatchers.IO) { measureCacheUsage(context) }
        }
        // 上限的唯一真相始终是 OfflineAudioCache.maxMb；管理弹窗里也能改它，
        // 所以弹窗关闭后重读一次，避免本页那一行显示旧值（同一 key 两个入口时的显示同步）。
        LaunchedEffect(showOfflineCacheManager) {
            if (!showOfflineCacheManager) offlineCacheMb = OfflineAudioCache.maxMb(context)
        }
    }

    // 自定义背景图（v1.2.0 · B3）。选图走 SAF 只读打开，取到后立刻降采样拷进私有目录 ——
    // 因为不再需要回读原文件，所以**不**申请 persistable URI 权限。
    // 选择器注册挂在页面级（不是 LazyColumn item 里）：item 被回收会反注册 launcher，
    // 而系统选图页打开期间正是不能丢注册的时候。
    val bgRevision by BackgroundImageManager.revision.collectAsState()
    val hasCustomBg = remember(bgRevision) { BackgroundImageManager.isActive(context) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                if (!BackgroundImageManager.importFromUri(context, uri)) {
                    Toast.makeText(context, strings.bgImportFailed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 门控查询函数（`SettingsVisibility` 的唯一输入）。
     *
     * 只把**页面内会变的那几个键**接到进程内状态上，其余走 prefs 快照 ——
     * 这样用户在本页改档位/开关时，被门控的行会**同帧**改变置灰状态。
     */
    val read: (String) -> Any? = { key ->
        when (key) {
            "motion_tier" -> motionTier
            "ui_motion_enabled" -> uiMotionEnabled
            "motion_shockwave" -> motionShockwave
            "motion_halo" -> motionHalo
            "motion_particles" -> motionParticles
            "motion_wave_bands" -> motionWaveBands
            "motion_breathing" -> motionBreathing
            // v3.2.0：界面律动那一层（总闸 + 三个细粒度开关）——门控要用到它们
            "motion_rhythm_enabled" -> motionRhythm
            "motion_cover_float" -> motionCoverFloat
            "motion_lyric_pulse" -> motionLyricPulse
            "motion_bar_pulse" -> motionBarPulse
            // v3.1.0 · B：B 站音源开关。读的是 `BiliPrefs` 的进程内镜像（初值来自盘）。
            "bilibili_enabled" -> BiliSourceProvider.isEnabled
            // v3.4.8：三项 B 站专属设置的门控只依赖上面那个总开关，这里不必再接自己的值。
            "lyrics_ttml_enabled" -> lyricsTtmlEnabled
            "lyrics_word_animation" -> lyricsWordAnimation
            else -> prefs.all[key]
        }
    }
    val rows = SettingsRenderPlan.rowsOf(group.id, read)

    /** 开关行的当前值（读进程内状态镜像；初值全部来自上面的既有 `read*`）。 */
    fun switchValueOf(id: String): Boolean = when (id) {
        "gapless_playback" -> gaplessEnabled
        "keep_screen_on" -> keepScreenOnEnabled
        "audio_visualizer" -> audioVisualizerEnabled
        "auto_rotate" -> autoRotateEnabled
        "artist_reco_enabled" -> artistRecoEnabled
        "page_transition_enabled" -> pageTransitionEnabled
        "lyrics_translation" -> lyricsTranslation
        "lyrics_in_media_session" -> lyricsInMediaSession
        "lyrics_ttml_enabled" -> lyricsTtmlEnabled
        "lyrics_ttml_first" -> lyricsTtmlFirst
        // v3.1.0 · B：B 站音源开关
        "bilibili_enabled" -> biliEnabled
        // v3.4.8 · 问题 3
        "bilibili_prefer_flac" -> biliPreferFlac
        "lyrics_romanization" -> lyricsRomanization
        "lyrics_dynamic_font" -> dynamicFontEnabled
        "ui_motion_enabled" -> uiMotionEnabled
        // v3.0.0：五个独立动效开关
        "motion_shockwave" -> motionShockwave
        "motion_halo" -> motionHalo
        "motion_particles" -> motionParticles
        "motion_wave_bands" -> motionWaveBands
        "motion_breathing" -> motionBreathing
        // v3.2.0：界面律动那一层
        "motion_rhythm_enabled" -> motionRhythm
        "motion_cover_float" -> motionCoverFloat
        "motion_lyric_pulse" -> motionLyricPulse
        "motion_bar_pulse" -> motionBarPulse
        else -> false
    }

    /**
     * 开关行的写入：**每个 key 一行，逐条对应既有单一入口**（file:line 见注释）。
     * 没有 else 分支的兜底写入 —— 不认识的 id 什么都不做（而不是往盘上写一个没定义过的键）。
     */
    fun writeSwitch(id: String, value: Boolean) {
        when (id) {
            // UserScreen.kt:473 裸 prefs + PlayerViewModel.refreshGaplessSetting()
            "gapless_playback" -> {
                gaplessEnabled = value
                prefs.edit().putBoolean("gapless_playback", value).apply()
                // 即时生效: VM 缓存的 gaplessEnabled 不刷新的话,
                // 本首歌的预载状态与开关不一致, 要等下一首歌才对上
                playerViewModel.refreshGaplessSetting()
            }
            // KeepScreenOnSetting.write（唯一读写入口）
            "keep_screen_on" -> {
                keepScreenOnEnabled = value
                KeepScreenOnSetting.write(context, value)
            }
            // VisualizerSetting.write（唯一读写入口）
            "audio_visualizer" -> {
                audioVisualizerEnabled = value
                VisualizerSetting.write(context, value)
            }
            // RotationSetting.write（唯一读写入口）
            "auto_rotate" -> {
                autoRotateEnabled = value
                RotationSetting.write(context, value)
            }
            // ArtistReco.setEnabled（唯一读写入口）
            "artist_reco_enabled" -> {
                artistRecoEnabled = value
                ArtistReco.setEnabled(context, value)
            }
            // 状态提升在 MainScreen：它自己写 PageTransitionSetting.writeEnabled
            "page_transition_enabled" -> onPageTransitionChange(value)
            // UserScreen.kt:538 裸 prefs + PlayerViewModel.setLyricsTranslation()
            "lyrics_translation" -> {
                lyricsTranslation = value
                prefs.edit().putBoolean("lyrics_translation", value).apply()
                playerViewModel.setLyricsTranslation(value)
            }
            // PlayerViewModel.setLyricsInMediaSession()（它是唯一写入口）
            "lyrics_in_media_session" -> {
                lyricsInMediaSession = value
                playerViewModel.setLyricsInMediaSession(value)
            }
            // BiliPrefs.setEnabled（唯一读写入口，它会刷新进程内镜像）
            "bilibili_enabled" -> {
                biliEnabled = value
                BiliPrefs.setEnabled(context, value)
            }
            // v3.4.8 · 问题 3：先落盘、再改内存（`BiliPrefs.set*` 内部就是这个顺序）。
            "bilibili_prefer_flac" -> {
                biliPreferFlac = value
                BiliPrefs.setPreferFlac(context, value)
            }
            "lyrics_ttml_enabled" -> {
                lyricsTtmlEnabled = value
                playerViewModel.setLyricsTtmlEnabled(value)
            }
            "lyrics_ttml_first" -> {
                lyricsTtmlFirst = value
                playerViewModel.setLyricsTtmlFirst(value)
            }
            "lyrics_romanization" -> {
                lyricsRomanization = value
                playerViewModel.setLyricsRomanization(value)
            }
            "lyrics_dynamic_font" -> {
                dynamicFontEnabled = value
                playerViewModel.setDynamicLyricFont(value)
            }
            // v2.9.0：「界面动效」总开关（MotionPrefs 是唯一读写入口，它会刷新进程内镜像）。
            "ui_motion_enabled" -> {
                uiMotionEnabled = value
                MotionPrefs.setUiMotionEnabled(context, value)
            }
            // v3.0.0：五个独立开关。写入口只有 `MotionPrefs.setSwitch`（它负责刷新进程内镜像，
            // 渲染层读的是那个镜像 —— 设置页与播放页不会出现两份状态）。
            "motion_shockwave" -> {
                motionShockwave = value
                MotionPrefs.setSwitch(context, MotionPrefs.KEY_SHOCKWAVE, value)
            }
            "motion_halo" -> {
                motionHalo = value
                MotionPrefs.setSwitch(context, MotionPrefs.KEY_HALO, value)
            }
            "motion_particles" -> {
                motionParticles = value
                MotionPrefs.setSwitch(context, MotionPrefs.KEY_PARTICLES, value)
            }
            "motion_wave_bands" -> {
                motionWaveBands = value
                MotionPrefs.setSwitch(context, MotionPrefs.KEY_WAVE_BANDS, value)
            }
            "motion_breathing" -> {
                motionBreathing = value
                MotionPrefs.setSwitch(context, MotionPrefs.KEY_BREATHING, value)
            }
            // v3.2.0：界面律动那一层。写入口同样只有 `MotionPrefs.setSwitch`。
            "motion_rhythm_enabled" -> {
                motionRhythm = value
                MotionPrefs.setSwitch(context, MotionPrefs.KEY_RHYTHM, value)
            }
            "motion_cover_float" -> {
                motionCoverFloat = value
                MotionPrefs.setSwitch(context, MotionPrefs.KEY_COVER_FLOAT, value)
            }
            "motion_lyric_pulse" -> {
                motionLyricPulse = value
                MotionPrefs.setSwitch(context, MotionPrefs.KEY_LYRIC_PULSE, value)
            }
            "motion_bar_pulse" -> {
                motionBarPulse = value
                MotionPrefs.setSwitch(context, MotionPrefs.KEY_BAR_PULSE, value)
            }
        }
    }

    val offlineCacheOptions = remember {
        SettingsRegistry.OFFLINE_CACHE_MB_CHOICES.map { "$it MB" }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        DetailScaffold(
            title = settingsGroupTitle(strings, group),
            onBack = onBack,
            header = { SettingsGroupHeader(group) },
        ) {
            rows.forEach { entry ->
                val availability = SettingsVisibility.availabilityOf(entry, read)
                when (SettingsRenderPlan.rowKindOf(entry)) {
                    // 账号三块在 SettingsAccountPage（本页永不渲染它们，分组已在上面分流）。
                    // v3.2.0：加上 B 站那一块（ACCOUNT_BILI）—— 漏掉它编译就会因 `when` 不穷举而红，
                    // 这正是当初把它做成枚举而不是 `else` 的理由。
                    SettingsRowKind.ACCOUNT_PROFILE, SettingsRowKind.ACCOUNT_QQ,
                    SettingsRowKind.ACCOUNT_BILI,
                    SettingsRowKind.INTERNAL, SettingsRowKind.HOSTED_ELSEWHERE -> Unit

                    SettingsRowKind.SWITCH -> item(key = entry.id) {
                        SettingSwitchRow(
                            title = rowTitle(strings, entry),
                            description = rowSubtitle(strings, entry),
                            checked = switchValueOf(entry.id),
                            // 「置灰 + 原因」而不是隐藏（隐藏会让用户以为这一项被删了）。
                            // C 档四个细分开关的「原因」写在各自的 description 里
                            // （i18n 的 visualizer*Description 已逐条写明生效条件）。
                            enabled = availability.enabled,
                            onCheckedChange = { writeSwitch(entry.id, it) },
                        )
                    }

                    SettingsRowKind.QUALITY_DROPDOWN -> item(key = entry.id) {
                        // API < 27 没有系统 FLAC 解码器：选中 lossless / hires / jyeffect 会在取链
                        // 阶段被跳过、实际拿到 mp3。这里显式提示，避免"选了无损却没无损"（Bug1-C）。
                        val flacUnsupported = !SongUrlFetcher.deviceSupportsFlac
                        val selected = if (entry.id == "wifi_quality") wifiQuality else mobileQuality
                        MetroDropdownRow(
                            label = rowTitle(strings, entry),
                            selectedIndex = selected,
                            options = strings.qualityOptions,
                            hint = if (flacUnsupported && isFlacTierIndex(selected)) {
                                strings.qualityFlacUnsupportedHint
                            } else null,
                            onSelect = {
                                if (entry.id == "wifi_quality") {
                                    wifiQuality = it
                                    prefs.edit().putInt("wifi_quality", it).apply()
                                } else {
                                    mobileQuality = it
                                    prefs.edit().putInt("mobile_quality", it).apply()
                                }
                                // Bug1-A：设置立即生效——正在播放时按新档位重新取链，而不是等下一首。
                                playerViewModel.onQualityPreferenceChanged()
                            }
                        )
                    }

                    SettingsRowKind.TIER_DROPDOWN -> item(key = entry.id) {
                        MetroDropdownRow(
                            // v2.9.0：标题 / 说明都改成「动效强度」——它现在同时驱动波形与界面动效。
                            // 三个候选名沿用 v2.8.0 的「简洁 / 精致 / 炫技」（语义没变，只是覆盖面变大）。
                            label = strings.waveform.motionIntensityLabel,
                            selectedIndex = motionTier,
                            options = listOf(
                                strings.waveform.visualizerTierSimple,
                                strings.waveform.visualizerTierRefined,
                                strings.waveform.visualizerTierShowcase,
                            ),
                            // 说明里写清「低端设备默认简洁档」：用户看到的「简洁」可能是设备判据
                            // 解析出来的默认值，不是他自己选的。
                            hint = strings.waveform.motionIntensityDescription,
                            onSelect = {
                                motionTier = it
                                // 走 MotionPrefs：它同时**重置自动降级水位**
                                // （用户显式改档位永远优先于自动降级，见 MotionDegrade 的 KDoc）。
                                MotionPrefs.setTier(context, it)
                            }
                        )
                    }

                    SettingsRowKind.LANGUAGE_DROPDOWN -> item(key = entry.id) {
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            MetroLanguageDropdown(
                                selectedCode = selectedLanguageCode,
                                presets = languagePresets,
                                onSelect = { code ->
                                    if (code != selectedLanguageCode) {
                                        selectedLanguageCode = code
                                        onLanguageChange(code)
                                    }
                                }
                            )
                        }
                    }

                    SettingsRowKind.DROPDOWN -> item(key = entry.id) {
                        when (entry.id) {
                            // PlayerViewModel.setLyricsWordAnimation()（写 LyricsDisplayPrefs）
                            "lyrics_word_animation" -> MetroDropdownRow(
                                label = strings.lyricsWordAnimationLabel,
                                selectedIndex = lyricsWordAnimation,
                                options = strings.lyricsWordAnimationOptions,
                                onSelect = {
                                    lyricsWordAnimation = it
                                    playerViewModel.setLyricsWordAnimation(it)
                                }
                            )
                            // PlayerViewModel.setLyricsSweepQuality()；软依赖「逐字动画 = 渐变扫过」
                            "lyrics_sweep_quality" -> MetroDropdownRow(
                                label = strings.lyricsSweepQualityLabel,
                                selectedIndex = lyricsSweepQuality,
                                options = strings.lyricsSweepQualityOptions,
                                enabled = availability.enabled,
                                // 软依赖不满足 ⇒ 置灰 + 把前置条件写出来。
                                // 文案由既有词条拼出（本任务禁止改 ui/i18n 目录，不能新增词条）。
                                hint = if (!availability.enabled) {
                                    strings.lyricsWordAnimationLabel + " = " +
                                        strings.lyricsWordAnimationOptions.getOrElse(
                                            LyricsWordAnimationMode.GRADIENT_SWEEP
                                        ) { "" }
                                } else null,
                                onSelect = {
                                    lyricsSweepQuality = it
                                    playerViewModel.setLyricsSweepQuality(it)
                                }
                            )
                            // PlayerViewModel.setLyricsFontScale()
                            "lyrics_font_scale" -> MetroDropdownRow(
                                label = strings.lyricsFontScaleLabel,
                                selectedIndex = LyricsDisplayPrefs.fontScaleStepIndex(lyricsFontScale),
                                options = LyricsDisplayPrefs.FONT_SCALE_LABELS,
                                onSelect = { index ->
                                    val scale = LyricsDisplayPrefs.FONT_SCALE_STEPS.getOrNull(index)
                                    if (scale != null) {
                                        lyricsFontScale = scale
                                        playerViewModel.setLyricsFontScale(scale)
                                    }
                                }
                            )
                            // v3.4.8 · 问题 3：B 站音质上限。
                            // 写入口 `BiliPrefs.setQualityCap`（唯一）。它**不需要**
                            // `playerViewModel.onQualityPreferenceChanged()`：
                            // 那是「立即按新档位重新取当前这首」的入口，而这里的上限
                            // 只影响 B 站，且下一次取链（切歌 / 重试）就会生效 ——
                            // 为它强插一次全源重取会让正在放的 ncm 歌也重启一遍。
                            "bilibili_quality_cap" -> MetroDropdownRow(
                                label = strings.bili.biliQualityCapLabel,
                                selectedIndex = BiliQualityCap.values()
                                    .indexOf(biliQualityCap).coerceAtLeast(0),
                                options = listOf(
                                    strings.bili.biliQualityCapAuto,
                                    strings.bili.biliQualityCapHires,
                                    strings.bili.biliQualityCapExhigh,
                                    strings.bili.biliQualityCapHigher,
                                ),
                                enabled = availability.enabled,
                                hint = strings.bili.biliQualityCapDescription,
                                onSelect = { index ->
                                    BiliQualityCap.values().getOrNull(index)?.let { cap ->
                                        biliQualityCap = cap
                                        BiliPrefs.setQualityCap(context, cap)
                                    }
                                }
                            )
                            // v3.4.8 · 问题 2：B 站字幕语言。
                            // 「不抓取字幕」是这一组的最后一档 —— 它同时就是用户要的
                            // 「抓取自选项」，所以不需要第二个开关（见 `BiliSubtitleLang.OFF`）。
                            "bilibili_subtitle_lang" -> MetroDropdownRow(
                                label = strings.bili.biliSubtitleLangLabel,
                                selectedIndex = BiliSubtitleLang.values()
                                    .indexOf(biliSubtitleLang).coerceAtLeast(0),
                                options = listOf(
                                    strings.bili.biliSubtitleLangAuto,
                                    strings.bili.biliSubtitleLangZhHans,
                                    strings.bili.biliSubtitleLangZhHant,
                                    strings.bili.biliSubtitleLangEn,
                                    strings.bili.biliSubtitleLangJa,
                                    strings.bili.biliSubtitleLangKo,
                                    strings.bili.biliSubtitleLangOff,
                                ),
                                enabled = availability.enabled,
                                hint = strings.bili.biliSubtitleLangDescription,
                                onSelect = { index ->
                                    BiliSubtitleLang.values().getOrNull(index)?.let { lang ->
                                        biliSubtitleLang = lang
                                        BiliPrefs.setSubtitleLang(context, lang)
                                    }
                                }
                            )
                            // OfflineAudioCache.setMaxMb()（唯一写入口；上限在**下次启动**生效，
                            // 淘汰器构造时固化 —— 提示文案见 offlineCacheLimitHint）
                            "offline_cache_mb" -> MetroDropdownRow(
                                label = strings.offlineCacheLimitLabel,
                                selectedIndex = SettingsRegistry.OFFLINE_CACHE_MB_CHOICES
                                    .indexOf(offlineCacheMb).coerceAtLeast(0),
                                options = offlineCacheOptions,
                                hint = strings.offlineCacheLimitHint,
                                onSelect = { index ->
                                    SettingsRegistry.OFFLINE_CACHE_MB_CHOICES.getOrNull(index)?.let { mb ->
                                        offlineCacheMb = mb
                                        OfflineAudioCache.setMaxMb(context, mb)
                                    }
                                }
                            )
                        }
                    }

                    SettingsRowKind.THEME_MODE_SEGMENT -> item(key = entry.id) {
                        SectionTitle(rowTitle(strings, entry))
                        ThemeModeSelector(
                            selected = themeMode,
                            labels = Triple(
                                strings.themeModeSystem,
                                strings.themeModeDark,
                                strings.themeModeLight
                            ),
                            onSelect = onThemeModeChange
                        )
                    }

                    SettingsRowKind.THEME_COLOR_SWATCHES -> item(key = entry.id) {
                        SectionTitle(rowTitle(strings, entry))
                        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                            ThemeColorSelector(
                                selectedIndex = themeIndex,
                                presets = themeColorPresets,
                                onSelect = onThemeChange
                            )
                        }
                    }

                    SettingsRowKind.ACCENT_SOURCE_SEGMENT -> item(key = entry.id) {
                        SectionTitle(rowTitle(strings, entry))
                        AccentSourceSelector(
                            selected = accentSource,
                            systemEnabled = systemAccentSupported,
                            labels = Triple(
                                strings.accentSourcePreset,
                                strings.accentSourceCover,
                                strings.accentSourceSystem
                            ),
                            systemHint = strings.accentSourceSystemHint,
                            onSelect = onAccentSourceChange
                        )
                        // B2-D：部分 ROM 换壁纸后不发配置变更，给一个手动重读入口。
                        if (accentSource == AccentSource.SYSTEM && systemAccentSupported) {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .border(1.dp, LocalMetroColors.current.divider)
                                    .clickable { onRefreshSystemAccent() }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                MetroText(
                                    strings.accentSystemRefresh,
                                    color = LocalMetroColors.current.primary,
                                    style = TextStyle(fontSize = 12.sp)
                                )
                            }
                        }
                    }

                    SettingsRowKind.BACKGROUND_PICKER -> item(key = entry.id) {
                        SectionTitle(strings.bgSectionTitle)
                        SettingActionRow(
                            label = if (hasCustomBg) strings.bgChange else strings.bgPick,
                            onClick = { imagePicker.launch(arrayOf("image/*")) }
                        )
                        if (hasCustomBg) {
                            SettingActionRow(
                                label = strings.bgRemove,
                                accent = true,
                                onClick = { BackgroundImageManager.clear(context) }
                            )
                        }
                    }

                    SettingsRowKind.ACTION_JUMP -> item(key = entry.id) {
                        // ── v3.3.0 · 用户反馈第 5 条「后台播放明明有一个可以打开的图标但是点不动」──
                        //
                        // 真根因**不是**回调失效。实测（emulator API 33 / v3.2.4-gpl，正在放歌）：
                        //   未白名单时点这一行 → `START …IGNORE_BATTERY…` + `Displayed …RequestIgnoreBatteryOptimizations`
                        //   点「允许」后 `dumpsys deviceidle whitelist` → `user,com.takahashirinta.ncrust,10183`
                        //   **已经在白名单内**时点同一行 → 同样 `START` 但 **无 Displayed**、
                        //   `ResumedActivity` 仍是 MainActivity、截图零变化
                        //
                        // 也就是「功能是好的，反馈是缺的」：应用一旦进过白名单（首启弹窗引导过
                        // 就是常态），系统页启动即 finish，屏幕**零变化**；而这一行既没有开关
                        // 也不显示状态，失败还被 `runCatching` 静默吞掉 —— 用户只能判定「点不动」。
                        //
                        // 修法三步：① 读 `isUnrestricted()` 做两态回显；② 已在白名单时改跳
                        // 电池优化**列表页**（必有界面，不再是一次空跳）；③ 两条路都失败时给可见提示。
                        val allowed = remember(backgroundBatteryRefresh) {
                            BackgroundActivity.isUnrestricted(context)
                        }
                        SettingActionRow(
                            label = rowTitle(strings, entry),
                            trailing = if (allowed) strings.batteryStatusAllowed else strings.batteryStatusDenied,
                            onClick = {
                                // 跳转与刷新都放进一个返回 Boolean 的块：`true` = 系统界面确实起来了。
                                val launched = runCatching {
                                    context.startActivity(
                                        if (allowed) BackgroundActivity.settingsListIntent()
                                        else BackgroundActivity.requestIntent(context)
                                    )
                                    true
                                }.getOrElse {
                                    // 直达弹窗被 ROM 拒绝 ⇒ 退到应用详情页（与首启弹窗同一降级链）。
                                    runCatching {
                                        context.startActivity(BackgroundActivity.appDetailsIntent(context))
                                        true
                                    }.getOrElse { false }
                                }
                                if (launched) {
                                    // 已在白名单时不会有任何视觉变化，所以必须明确说一句 ——
                                    // 这正是本条反馈的症结。
                                    if (allowed) {
                                        Toast.makeText(
                                            context,
                                            strings.batteryAlreadyAllowed,
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                    // 回来时重读白名单状态（用户可能刚点了「允许」）。
                                    backgroundBatteryRefresh++
                                } else {
                                    Toast.makeText(
                                        context,
                                        strings.batteryJumpFailed,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    Log.w(
                                        "SettingsGroup",
                                        "battery row: 直达弹窗与降级路径都失败 entry=${entry.id}",
                                    )
                                }
                            }
                        )
                    }

                    SettingsRowKind.CLEAR_CACHE -> item(key = entry.id) {
                        SectionTitle(strings.storageSectionTitle)
                        SettingActionRow(
                            label = strings.cacheSizeLabel(cacheUsage.totalBytes),
                            accent = true,
                            trailing = strings.clearCache,
                            onClick = { showClearCacheConfirm = true }
                        )
                        // v2.0.0 · T3：占用拆成三项。数字与「清除缓存」能清掉的范围一一对应
                        // （音频 = filesDir/offline/audio；图片 = cacheDir/image_cache；其他 = cacheDir
                        // 其余子项），任何一项都不与另一项重叠 —— 旧口径的图片缓存双计就是在这里被拆掉的。
                        CacheUsageLine(strings.cacheUsageAudio, formatCacheBytes(cacheUsage.audioBytes))
                        CacheUsageLine(strings.cacheUsageImage, formatCacheBytes(cacheUsage.imageBytes))
                        CacheUsageLine(strings.cacheUsageOther, formatCacheBytes(cacheUsage.otherCacheBytes))
                    }

                    SettingsRowKind.OFFLINE_MANAGER -> item(key = entry.id) {
                        SettingActionRow(
                            label = strings.offlineCacheManageLabel,
                            onClick = { showOfflineCacheManager = true }
                        )
                    }

                    SettingsRowKind.ABOUT_OPEN -> item(key = entry.id) {
                        SettingActionRow(
                            label = rowTitle(strings, entry),
                            onClick = onOpenAbout
                        )
                    }
                }
            }

            if (group == SettingsGroup.PLAYBACK) {
                // 「亮度表示时间新旧，不是频谱」——探针发现本版没有频域数据源，
                // 不写清楚用户一定会把按时序着色读成频谱（文案来自 i18n 的 WaveformStrings）。
                item(key = "hint.waveform_not_spectrum") {
                    MetroText(
                        strings.waveform.visualizerNotSpectrumHint,
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.caption,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                    )
                }
            }
        }

        if (showClearCacheConfirm) ClearCacheConfirmDialog(
            onConfirm = {
                showClearCacheConfirm = false
                coroutineScope.launch {
                    val usage = withContext(Dispatchers.IO) {
                        ContentCache.clearAll()
                        runCatching { coil.Coil.imageLoader(context).memoryCache?.clear() }
                        // 图片缓存走 Coil 自己的 API（它要维护 journal，绕过它直接删目录会让
                        // DiskCache 的状态与磁盘不一致）。这一份对应 CacheUsage.imageBytes。
                        runCatching { coil.Coil.imageLoader(context).diskCache?.clear() }
                        // 其余 cacheDir 子项全清 —— 口径与 CacheUsage.otherCacheBytes 一一对应：
                        // 「显示多少就能清掉多少」是 v1.6.0 起的不变量。cacheDir 里的东西按
                        // Android 的契约本来就可以被系统随时回收，全清是安全的。
                        // image_cache 跳过：交给上面的 Coil API，避免两边同时对同一个目录动手。
                        runCatching {
                            context.cacheDir?.let { dir ->
                                dir.listFiles()
                                    ?.filter { it.name != CacheUsage.IMAGE_CACHE_DIR }
                                    ?.forEach { it.deleteRecursively() }
                            }
                        }
                        // v1.6.0 · D1：离线音频缓存在 filesDir/offline/audio（不随系统清缓存消失），
                        // 用户点「清除缓存」时一并清掉，并作废离线 URL 清单与离线曲目索引，
                        // 避免留下死条目（v2.0.0 · T3 起索引也在清理范围内）。
                        runCatching { OfflineAudioCache.clear(context) }
                        measureCacheUsage(context)
                    }
                    cacheUsage = usage
                    Toast.makeText(context, strings.cacheCleared, Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showClearCacheConfirm = false }
        )

        if (showOfflineCacheManager) {
            OfflineCacheManagerDialog(
                onDismiss = { showOfflineCacheManager = false },
                currentSongId = playingSongId ?: -1L,
                // v3.3.0 · 用户建议：离线列表的行可直接点播。
                // 播放归 MainScreen 所有（队列 / currentQueueIndex / 持久化），
                // 所以这里只把「用户点了哪一首」交出去 —— 与从任意列表点歌**同一条链路**
                // （离线兜底 `recallOfflineCache` 会按 id 找回缓存里的 URL，不需要新路径）。
                onPlayTrack = { song -> song?.let { onPlaySong(it) } },
            )
        }
    }
}

/**
 * 二级页页头：大标题 + 副标题。
 *
 * `statusBarsPadding() + top = 56.dp`：顶部 48dp 是返回箭头的命中区
 * （`DetailScaffold` 的 `TopScrimIconButton` 浮在内容之上），留够才不会被箭头压住标题。
 */
@Composable
internal fun SettingsGroupHeader(group: SettingsGroup) {
    val strings = LocalStrings.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 8.dp)
    ) {
        MetroText(
            settingsGroupTitle(strings, group),
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.pageHeading,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        MetroText(
            settingsGroupSubtitle(strings, group),
            color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.caption,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────
// 行文案：`when (entry.id)` 的每个分支都读 **registry 里那条 entry 的 titleKey/subtitleKey
// 所指的同一个属性**（注释里逐条写出属性名）。本任务禁止改 ui/i18n，所以这里不能新增词条；
// 「路径真实存在」由 SettingsRegistryTest.everyTitleKeyResolvesToARealStringsAccessorPath
// 用 i18n 快照单独钉住。
// ─────────────────────────────────────────────────────────────────────────

/** 行标题（= `entry.titleKey` 指的那个属性）。 */
private fun rowTitle(strings: Strings, entry: SettingsEntry): String = when (entry.id) {
    "wifi_quality" -> strings.wifiQualityLabel                 // titleKey = wifiQualityLabel
    "mobile_quality" -> strings.mobileQualityLabel             // titleKey = mobileQualityLabel
    "gapless_playback" -> strings.gaplessSectionTitle          // titleKey = gaplessSectionTitle
    "keep_screen_on" -> strings.keepScreenOnLabel              // titleKey = keepScreenOnLabel
    "audio_visualizer" -> strings.audioVisualizerLabel         // titleKey = audioVisualizerLabel
    "auto_rotate" -> strings.autoRotateLabel                   // titleKey = autoRotateLabel
    "artist_reco_enabled" -> strings.artistRecoTitle           // titleKey = artistRecoTitle
    "bilibili_enabled" -> strings.bilibiliEnabledLabel         // titleKey = bilibiliEnabledLabel
    // v3.4.8：文案住在新的 `Strings.bili` 组（registry 的 titleKey 是 `bili.xxx` 裸路径）。
    // `bilibili_prefer_flac` 是**开关行**，开关行只能用 rowTitle（没有 MetroDropdownRow
    // 那种显式 label 参数）—— 所以这三条都必须在这里登记，否则标题会渲染成裸 id。
    "bilibili_quality_cap" -> strings.bili.biliQualityCapLabel
    "bilibili_prefer_flac" -> strings.bili.biliPreferFlacLabel
    "bilibili_subtitle_lang" -> strings.bili.biliSubtitleLangLabel
    "page_transition_enabled" -> strings.motion.pageTransitionLabel
    "lyrics_translation" -> strings.lyricsTranslationLabel
    "lyrics_word_animation" -> strings.lyricsWordAnimationLabel
    "lyrics_sweep_quality" -> strings.lyricsSweepQualityLabel
    "lyrics_font_scale" -> strings.lyricsFontScaleLabel
    "lyrics_in_media_session" -> strings.lyricsInMediaSessionLabel
    "lyrics_ttml_enabled" -> strings.lyricsTtmlEnabledLabel
    "lyrics_ttml_first" -> strings.lyricsTtmlFirstLabel
    "lyrics_romanization" -> strings.lyricsRomanizationLabel
    "lyrics_dynamic_font" -> strings.dynamicFontLabel
    "motion_tier" -> strings.waveform.motionIntensityLabel           // titleKey = waveform.motionIntensityLabel
    "ui_motion_enabled" -> strings.waveform.uiMotionLabel            // titleKey = waveform.uiMotionLabel
    // v3.0.0：五个独立动效开关（文案同样住在 Strings.waveform）
    "motion_shockwave" -> strings.waveform.motionShockwaveLabel
    "motion_halo" -> strings.waveform.motionHaloLabel
    "motion_particles" -> strings.waveform.motionParticlesLabel
    "motion_wave_bands" -> strings.waveform.motionWaveBandsLabel
    "motion_breathing" -> strings.waveform.motionBreathingLabel
    // v3.2.0：界面律动那一层（律动总闸 + 三个细粒度开关），文案住在 Strings.waveform。
    "motion_rhythm_enabled" -> strings.waveform.motionRhythmLabel
    "motion_cover_float" -> strings.waveform.motionCoverFloatLabel
    "motion_lyric_pulse" -> strings.waveform.motionLyricPulseLabel
    "motion_bar_pulse" -> strings.waveform.motionBarPulseLabel
    "theme_mode" -> strings.themeModeSectionTitle
    "theme_color_index" -> strings.themeSectionTitle
    "accent_source" -> strings.accentSourceSectionTitle
    "language_code" -> strings.languageSectionTitle
    "custom_bg_enabled" -> strings.bgSectionTitle
    "offline_cache_mb" -> strings.offlineCacheLimitLabel
    "action.background_activity" -> strings.batteryTitle
    "action.about_open" -> strings.aboutButton
    // 6 个波形项的文案住在 `Strings.waveform`（i18n 侧 v2.8.0 新增的组），
    // registry 里 titleKey 为 null 是**刻意**的（见 SettingsRenderPlanTest 的断言）。
    "visualizer_tier" -> strings.waveform.visualizerTierLabel
    "visualizer_showcase" -> strings.waveform.visualizerShowcaseLabel
    "visualizer_shockwave" -> strings.waveform.visualizerShockwaveLabel
    "visualizer_particles" -> strings.waveform.visualizerParticlesLabel
    "visualizer_perspective" -> strings.waveform.visualizerPerspectiveLabel
    "visualizer_drag" -> strings.waveform.visualizerDragLabel
    else -> entry.id
}

/** 行描述（= `entry.subtitleKey` 指的那个属性；没有副标题的项返回 null）。 */
private fun rowSubtitle(strings: Strings, entry: SettingsEntry): String? = when (entry.id) {
    "gapless_playback" -> strings.gaplessDescription
    "keep_screen_on" -> strings.keepScreenOnHint
    "audio_visualizer" -> strings.audioVisualizerDescription
    "auto_rotate" -> strings.autoRotateDescription
    "page_transition_enabled" -> strings.motion.pageTransitionDescription
    "lyrics_in_media_session" -> strings.lyricsInMediaSessionHint
    "lyrics_romanization" -> strings.lyricsRomanizationHint
    "lyrics_dynamic_font" -> strings.dynamicFontHint
    "ui_motion_enabled" -> strings.waveform.uiMotionDescription
    "motion_shockwave" -> strings.waveform.motionShockwaveDescription
    "motion_halo" -> strings.waveform.motionHaloDescription
    "motion_particles" -> strings.waveform.motionParticlesDescription
    "motion_wave_bands" -> strings.waveform.motionWaveBandsDescription
    "motion_breathing" -> strings.waveform.motionBreathingDescription
    "motion_rhythm_enabled" -> strings.waveform.motionRhythmDescription
    "motion_cover_float" -> strings.waveform.motionCoverFloatDescription
    "motion_lyric_pulse" -> strings.waveform.motionLyricPulseDescription
    "motion_bar_pulse" -> strings.waveform.motionBarPulseDescription
    "bilibili_enabled" -> strings.bilibiliEnabledDescription
    // v3.4.8：三条 B 站专属设置各自的说明（开关行必须给副标题 —— 只说标题的
    // 「允许无损与 Hi-Res」无法回答「要花多少流量」「需要什么账号」）。
    "bilibili_quality_cap" -> strings.bili.biliQualityCapDescription
    "bilibili_prefer_flac" -> strings.bili.biliPreferFlacDescription
    "bilibili_subtitle_lang" -> strings.bili.biliSubtitleLangDescription
    "lyrics_translation" -> strings.lyricsTranslationHint
    else -> null
}

/** 档位索引是否落在"依赖 FLAC 解码器"的档位（与 SongUrlFetcher 的判定同源）。 */
private fun isFlacTierIndex(index: Int): Boolean {
    val level = PlayerViewModel.QUALITY_LEVELS.getOrNull(index) ?: return false
    return SongUrlFetcher.isFlacTier(level)
}

/** 主题模式三选一：跟随系统 / 深色 / 浅色。 */
@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    labels: Triple<String, String, String>,
    onSelect: (ThemeMode) -> Unit
) {
    val options = listOf(
        ThemeMode.SYSTEM to labels.first,
        ThemeMode.DARK to labels.second,
        ThemeMode.LIGHT to labels.third
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (mode, label) ->
            val active = mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .border(
                        1.dp,
                        if (active) LocalMetroColors.current.primary
                        // P2：未选中态用 outline（组件边界）而不是 divider（分隔线）——
                        // 浅色下 #E2DACB 在 #F6F2E9 页面上几乎看不见，边界该更强一档。
                        else LocalNcrustColors.current.outline
                    )
                    .background(
                        if (active) LocalMetroColors.current.primary.copy(alpha = 0.14f)
                        else Color.Transparent
                    )
                    .clickable { onSelect(mode) }
                    // P2：10→14dp 垂直 padding，触控高度 ≈40dp → 48dp。
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                MetroText(
                    label,
                    color = if (active) LocalMetroColors.current.primary
                    else LocalMetroColors.current.onSurfaceVariant,
                    // P2：显式 20sp 行框，触控高度可算（14+20+14 = 48dp），不再跟字体度量走。
                    style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 清除缓存确认弹窗。 */
@Composable
private fun ClearCacheConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // P2：弹窗走 surfaceContainerHigh，比基准容器 surface 高一档 ——
                // 深色 #1A1A1A → #242424、浅色 #FFFDF8 → #F2EBDE，弹窗与卡背不再同色。
                .background(LocalNcrustColors.current.surfaceContainerHigh)
                .padding(24.dp)
        ) {
            MetroText(
                strings.clearCache,
                color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(12.dp))
            MetroText(
                strings.clearCacheConfirm,
                color = LocalMetroColors.current.onSurfaceVariant,
                style = LocalMetroTypography.current.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                DialogButton(text = strings.cancel, accent = false, onClick = onDismiss)
                Spacer(Modifier.width(12.dp))
                DialogButton(text = strings.clearCache, accent = true, onClick = onConfirm)
            }
        }
    }
}

/**
 * 语言下拉。闭合态显示当前选中语言名 + 箭头；点击弹出所有语言。
 *
 * 多语言鲁棒：闭合态 Text 设 maxLines=1 + Ellipsis + weight(1f)。极长名如
 * "Советский русский"/"Middle English" 最多截断，绝不换行撑破箭头位置。
 */
@Composable
internal fun MetroLanguageDropdown(
    selectedCode: String,
    presets: List<LanguagePreset>,
    onSelect: (String) -> Unit
) {
    val selected = presets.find { it.code == selectedCode } ?: presets.first()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.4f))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetroText(
                selected.displayName,
                color = LocalMetroColors.current.onBackground,
                style = TextStyle(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            MetroIcon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = LocalMetroColors.current.onSurfaceVariant,
                sizeDp = 20.dp,
            )
        }

        // UWP ComboBox 移植:选中项落回锚点原位,内部滚动到选中语言。
        val selectedIndex = presets.indexOfFirst { it.code == selectedCode }.coerceAtLeast(0)
        MetroSelectorFlyout(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            options = presets.map { it.displayName },
            selectedIndex = selectedIndex,
            onSelect = { index ->
                onSelect(presets[index].code)
                expanded = false
            },
        )
    }
}
