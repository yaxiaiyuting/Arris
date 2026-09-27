/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」阶段 4：二级页内容（6 个分组共用一套骨架 + 逐项渲染）。
 */

package com.takahashirinta.ncrust.ui.screen

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
    onOpenAbout: () -> Unit,
) {
    // 账号组自带一整套覆盖层（账号弹窗 / 二维码登录 / 手机扫码授权），单独一页更清楚。
    if (group == SettingsGroup.ACCOUNT) {
        SettingsAccountPage(
            playerViewModel = playerViewModel,
            cookieRefreshTrigger = cookieRefreshTrigger,
            onShowWebLogin = onShowWebLogin,
            onShowQqLogin = onShowQqLogin,
            onShowQqPhoneLogin = onShowQqPhoneLogin,
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

    // ── 存储与缓存 ────────────────────────────────────────────────────────────────
    var offlineCacheMb by remember { mutableIntStateOf(OfflineAudioCache.maxMb(context)) }
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
        "lyrics_romanization" -> lyricsRomanization
        "lyrics_dynamic_font" -> dynamicFontEnabled
        "ui_motion_enabled" -> uiMotionEnabled
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
                    // 账号两块在 SettingsAccountPage（本页永不渲染它们，分组已在上面分流）
                    SettingsRowKind.ACCOUNT_PROFILE, SettingsRowKind.ACCOUNT_QQ,
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
                        // 后台运行：跳转系统"允许后台活动 / 忽略电池优化"设置（与首次启动弹窗同一入口）。
                        SettingActionRow(
                            label = rowTitle(strings, entry),
                            onClick = {
                                runCatching {
                                    context.startActivity(BackgroundActivity.requestIntent(context))
                                }.onFailure {
                                    runCatching {
                                        context.startActivity(BackgroundActivity.appDetailsIntent(context))
                                    }
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
