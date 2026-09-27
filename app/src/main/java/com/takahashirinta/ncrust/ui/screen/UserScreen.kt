/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（Bug1「音质切换」）：
 *   - A：音质下拉 onSelect 后调用 onQualityPreferenceChanged()，正在播放时立即生效。
 *   - C：API < 27 无系统 FLAC 解码器时，对 lossless/hires/jyeffect 档位给出
 *        「本机不支持该档位，将自动降级」提示（MetroDropdownRow 新增 hint 参数）。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroSelectorFlyout
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.KeepScreenOnSetting
import com.takahashirinta.ncrust.RotationSetting
import com.takahashirinta.ncrust.cache.OfflineAudioCache
import com.takahashirinta.ncrust.reco.ArtistReco
import com.takahashirinta.ncrust.ui.player.VisualizerSetting
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import coil.compose.AsyncImage
import androidx.lifecycle.viewmodel.compose.viewModel
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.cache.ContentCache
import android.widget.Toast
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.player.SongUrlFetcher
import com.takahashirinta.ncrust.qq.QqAuthStore
import com.takahashirinta.ncrust.qq.QqProfile
import com.takahashirinta.ncrust.power.BackgroundActivity
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.components.QrAuthorizeScreen
import com.takahashirinta.ncrust.ui.components.CacheUsageLine
import com.takahashirinta.ncrust.ui.components.MetroDropdownRow
import com.takahashirinta.ncrust.ui.components.SectionTitle
import com.takahashirinta.ncrust.ui.components.SettingSwitchRow
import com.takahashirinta.ncrust.ui.components.appCoverFrame
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.takahashirinta.ncrust.ui.components.QrLoginDialog
import com.takahashirinta.ncrust.ui.theme.BackgroundImageManager
import com.takahashirinta.ncrust.ui.theme.AppShapes
import com.takahashirinta.ncrust.lyric.LyricsDisplayPrefs
import com.takahashirinta.ncrust.lyric.LyricsSweepQuality
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.formatCacheBytes
import com.takahashirinta.ncrust.ui.i18n.LanguagePreset
import com.takahashirinta.ncrust.ui.i18n.getSavedLanguageCode
import com.takahashirinta.ncrust.ui.i18n.languagePresets
import com.takahashirinta.ncrust.ui.theme.AccentSource
import com.takahashirinta.ncrust.ui.theme.AccentSourceSelector
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors
import com.takahashirinta.ncrust.ui.theme.PageTransitionSetting
import com.takahashirinta.ncrust.ui.theme.ThemeColorSelector
import com.takahashirinta.ncrust.ui.theme.systemAccentSupported
import com.takahashirinta.ncrust.ui.theme.ThemeMode
import com.takahashirinta.ncrust.ui.theme.themeColorPresets
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UserScreen(
    onOpenAbout: () -> Unit = {},
    themeIndex: Int = 0,
    onThemeChange: (Int) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    accentSource: AccentSource = AccentSource.PRESET,
    onAccentSourceChange: (AccentSource) -> Unit = {},
    onRefreshSystemAccent: () -> Unit = {},
    onShowWebLogin: () -> Unit = {},
    /** v2.1.0 · C：打开 QQ 音乐的登录 WebView（点击卡片时调用）。 */
    onShowQqLogin: () -> Unit = {},
    /** v2.1.1：打开 QQ 音乐的手机号验证码登录（微信用户走不了 QQ 扫码时用）。 */
    onShowQqPhoneLogin: () -> Unit = {},
    refreshTrigger: Int = 0,
    onLanguageChange: (String) -> Unit = {},
    /**
     * v2.5.1 · F：「页面切换动效」的**当前值**。
     *
     * 由 `MainScreen` 持有（它是导航宿主与设置页的共同祖先），这里只读不存 ——
     * 自己在本地再 `remember` 一份就会出现「设置页显示关、转场还在」的状态分裂。
     */
    pageTransitionEnabled: Boolean = PageTransitionSetting.DEFAULT_ENABLED,
    /** v2.5.1 · F：切换「页面切换动效」。写盘 + 更新 MainScreen 的状态，**下一次导航即生效**。 */
    onPageTransitionChange: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val coroutineScope = rememberCoroutineScope()
    var showAccountDialog by remember { mutableStateOf(false) }
    var showQrLogin by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    // v2.0.0 · T3：离线缓存管理（全屏 Dialog，见 OfflineCacheOverlay.kt 的 KDoc 说明为什么不是导航页）。
    var showOfflineCacheManager by remember { mutableStateOf(false) }
    // v2.0.0 · T3：缓存占用改成三项分账（音频 / 图片 / 其他 cacheDir）。
    // 旧口径把图片缓存算了两遍（Coil 的磁盘缓存目录就是 cacheDir/image_cache，
    // 而 folderSize(cacheDir) 已经递归含它）—— 详见 CacheUsage 的 KDoc。
    var cacheUsage by remember { mutableStateOf(CacheUsage.ZERO) }
    // 缓存占用要递归遍历 cacheDir，放 IO 线程算，避免组合期主线程卡顿。
    LaunchedEffect(Unit) {
        cacheUsage = withContext(Dispatchers.IO) { measureCacheUsage(context) }
    }
    var hasCookie by remember { mutableStateOf(CookieManager.hasCookie(context)) }
    // 扫码登录为平板 / 大屏独占：手机端未登录点头像仍直接进 WebView 官方登录页。
    val isWideLayout = LocalConfiguration.current.screenWidthDp >= 600

    var userProfile by remember { mutableStateOf<PlaylistApi.UserProfile?>(null) }
    var isLoadingProfile by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("ncrust_settings", 0) }
    val playerViewModel: PlayerViewModel = viewModel()
    var wifiQuality by remember { mutableIntStateOf(prefs.getInt("wifi_quality", 3)) }
    var mobileQuality by remember { mutableIntStateOf(prefs.getInt("mobile_quality", 1)) }
    var gaplessEnabled by remember { mutableStateOf(prefs.getBoolean("gapless_playback", true)) }
    // v1.8.0 · T4：应用内「自动旋转」。走 RotationSetting（唯一读写入口）——
    // 播放器里的旋转图标与这里共享同一份状态，"改一处另一处立刻同步"。
    var autoRotateEnabled by remember { mutableStateOf(RotationSetting.read(context)) }
    // v1.8.0 · T3：大屏模式音频可视化（默认开；关掉后可视化整块不挂载，零开销）。
    var audioVisualizerEnabled by remember { mutableStateOf(VisualizerSetting.read(context)) }
    // v2.0.0 · T2：播放时禁止熄屏（默认开）。走 KeepScreenOnSetting（唯一读写入口）。
    var keepScreenOnEnabled by remember { mutableStateOf(KeepScreenOnSetting.read(context)) }
    var lyricsTranslation by remember { mutableStateOf(prefs.getBoolean("lyrics_translation", true)) }
    // v1.4.0 · 音乐人推荐开关
    var artistRecoEnabled by remember { mutableStateOf(ArtistReco.isEnabled(context)) }
    // v1.5.0 · B / v1.5.1 · A 逐字动画模式（0 渐变扫过 / 1 逐字硬切 / 2 关闭逐字）。
    // 读的时候顺带完成 v1.5.0 布尔开关 lyrics_word_by_word 的一次性迁移。
    var lyricsWordAnimation by remember { mutableIntStateOf(LyricsDisplayPrefs.readWordAnimation(prefs)) }
    // v1.5.1 · D 媒体面板歌词（默认关：开启后 ARTIST 会被改写成「艺人 · 歌词行」）。
    var lyricsInMediaSession by remember {
        mutableStateOf(prefs.getBoolean("lyrics_in_media_session", false))
    }
    // v1.5.1 · E 歌词字号倍率（0.7x ~ 1.5x，默认 1.0x）。
    var lyricsFontScale by remember { mutableStateOf(LyricsDisplayPrefs.readFontScale(prefs)) }
    // v1.5.2 逐字扫过绘制质量（0 自动 / 1 高级软边 / 2 兼容硬边，默认自动）。
    var lyricsSweepQuality by remember { mutableIntStateOf(LyricsDisplayPrefs.readSweepQuality(prefs)) }
    // v1.9.0：AMLL TTML 歌词源总开关 + 「TTML 优先」，默认都开。
    // 两者都只是歌词源的选择，改完由 ViewModel 对当前歌重新 fetch（缓存里那份未必是最终源）。
    var lyricsTtmlEnabled by remember { mutableStateOf(LyricsDisplayPrefs.readTtmlEnabled(prefs)) }
    var lyricsTtmlFirst by remember { mutableStateOf(LyricsDisplayPrefs.readTtmlFirst(prefs)) }
    // v1.9.3：音译显示（默认关）。只影响显示，不改歌词源、不重取歌词。
    var lyricsRomanization by remember { mutableStateOf(LyricsDisplayPrefs.readRomanization(prefs)) }
    // v2.0.0 · T4：动态字号（实验性，默认关）。
    var dynamicFontEnabled by remember { mutableStateOf(LyricsDisplayPrefs.readDynamicFont(prefs)) }

    var selectedLanguageCode by remember { mutableStateOf(getSavedLanguageCode(context)) }

    fun loadProfile() {
        if (!CookieManager.hasCookie(context)) {
            userProfile = null
            hasCookie = false
            return
        }
        coroutineScope.launch {
            isLoadingProfile = true
            try {
                val profile = PlaylistApi.getUserProfile()
                // 服务端对失效 cookie 会返回空 account/profile → userId=0。
                // 此时判定为已过期，主动清除本地 cookie，避免 UI 卡在 "UID: 0"。
                if (profile.userId == 0L) {
                    CookieManager.clearCookie(context)
                    RetrofitClient.updateCookie(null)
                    // v2.1.4：cookie 判定过期时会员缓存同样失效（理由见 onLogout 处）。
                    com.takahashirinta.ncrust.auth.NeteaseVipStore.clear(context)
                    hasCookie = false
                    userProfile = null
                } else {
                    userProfile = profile
                    hasCookie = true
                }
            } catch (_: Exception) {
                userProfile = null
            } finally {
                isLoadingProfile = false
            }
        }
    }

    LaunchedEffect(Unit) { loadProfile() }
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            hasCookie = CookieManager.hasCookie(context)
            loadProfile()
        }
    }
    // v2.1.4：登录态变化后重查一次会员状态（它决定聚合搜索的排序）。
    // 判据用 needsRefresh：未登录时恒为 false，不会发注定 401 的请求。
    LaunchedEffect(hasCookie) {
        if (com.takahashirinta.ncrust.auth.NeteaseVipStore.needsRefresh(context)) {
            com.takahashirinta.ncrust.auth.NeteaseVipStore.refresh(context)
        }
    }

    if (showAccountDialog) AccountDialog(
        userProfile = userProfile,
        onDismiss = { showAccountDialog = false },
        onScanAuthorize = {
            showAccountDialog = false
            showScanner = true
        },
        onLogout = {
            CookieManager.clearCookie(context)
            RetrofitClient.updateCookie(null)
            // v2.1.4：会员缓存也必须一起清 —— 它决定搜索排序，
            // 留着会让下一个登录的账号按**上一个账号**的会员状态排序。
            com.takahashirinta.ncrust.auth.NeteaseVipStore.clear(context)
            hasCookie = false
            userProfile = null
            showAccountDialog = false
        }
    )

    // 手机端扫码授权：扫平板登录二维码 → 解析 unikey → 局域网回传本机 cookie。
    // 连接/成功/失败状态都在扫码页内呈现，不再扫到即关闭。
    if (showScanner) QrAuthorizeScreen(
        onAuthorized = { showScanner = false },
        onClose = { showScanner = false }
    )

    if (showQrLogin) QrLoginDialog(
        onLoginSuccess = { cookie ->
            CookieManager.saveCookie(context, cookie)
            RetrofitClient.updateCookie(cookie)
            hasCookie = true
            showQrLogin = false
            loadProfile()
        },
        onGenericLogin = {
            showQrLogin = false
            onShowWebLogin()
        },
        onDismiss = { showQrLogin = false }
    )

    if (showOfflineCacheManager) {
        val playingSongId by playerViewModel.currentSongId.collectAsState()
        OfflineCacheManagerDialog(
            onDismiss = { showOfflineCacheManager = false },
            currentSongId = playingSongId ?: -1L,
        )
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

    // 宽屏设置/资料内容居中限宽（上限 720dp），避免设置行横跨平板。
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxHeight(),
        contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
        flingBehavior = rememberMetroFlingBehavior()
    ) {
        // Groove 大字页头。
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
            ) {
                MetroText(
                    strings.tabUser,
                    color = LocalMetroColors.current.onBackground,
                    style = LocalMetroTypography.current.pageHeading,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Profile 块。整块可点：已登录 → 账户管理；未登录 → 直接进 WebView 登录。
        item {
            ProfileBlock(
                isLoading = isLoadingProfile,
                profile = userProfile,
                notLoggedInText = strings.notLoggedIn,
                loginHintText = strings.loginHint,
                uidLabel = strings.uidLabel(userProfile?.userId?.toString() ?: ""),
                onClick = {
                    when {
                        hasCookie -> showAccountDialog = true
                        // 平板/大屏：原生扫码（官方 App 扫），下面再给「通用登录」退回 WebView
                        isWideLayout -> showQrLogin = true
                        else -> onShowWebLogin()
                    }
                }
            )
            Spacer(Modifier.height(24.dp))
        }

        // v2.1.0 · C：QQ 音乐账号（**独立于网易云**：各自登录、各自失效，互不影响）。
        item {
            QqAccountBlock(
                accountTitle = strings.sourceQqAccount,
                brand = strings.sourceQqMusic,
                notLoggedInText = strings.notLoggedIn,
                loginActionText = strings.sourceQqLoginAction,
                logoutText = strings.logoutButton,
                availabilityNote = strings.sourceQrAvailabilityNote,
                phoneLoginText = strings.sourceQqPhoneTitle,
                onLogin = onShowQqLogin,
                onPhoneLogin = onShowQqPhoneLogin,
                // v2.5.4 · C：第二个**仅 debug 包**的诊断入口 —— QQ 兜底统计的读出。
                // 与上面那条同形：release 里为 null ⇒ 整行不挂载（不是 alpha=0）。
                // 它做两件事：把快照写进 logcat（`QqProbe` tag）**并落盘**到
                // `ncrust_qq_probe`，所以真机上既能实时看、也能事后 pull。
                // **不上报**：这条路径不引用任何网络类型（铁律：本地统计不上报）。
                onProbeStats = if (BuildConfig.DEBUG) {
                    {
                        val stats = com.takahashirinta.ncrust.qq.QqProbeStore
                            .snapshotAndFlush(context)
                        android.util.Log.i(
                            "QqProbe",
                            "qq fallback stats = " + com.google.gson.Gson().toJson(stats),
                        )
                        android.util.Log.i("QqProbe", "verdict: " + stats.verdict())
                        // v2.5.5 · B：同一个诊断入口顺带把**跨源上报闸门**的计数读出并落盘
                        // （`ncrust_report_gate`）。两条统计的落盘时机与纪律完全一致：
                        // 只在用户主动点开这个入口（release 里整行不挂载）与 Activity.onStop。
                        // 它读的是「拦了几次 QQ id → 网易云 webLog」，同样**不上报**。
                        runCatching {
                            val gate = com.takahashirinta.ncrust.player.ReportGateStore
                                .snapshotAndFlush(context)
                            android.util.Log.i(
                                "ReportGate",
                                "cross-source report gate = " + com.google.gson.Gson().toJson(gate),
                            )
                            android.util.Log.i("ReportGate", "verdict: " + gate.verdict())
                        }
                    }
                } else null,
                // v2.1.4：release 包里为 null ⇒ 这一行不挂载，用户看不到、也点不到。
                onDiagnose = if (BuildConfig.DEBUG) {
                    {
                        val song = playerViewModel.currentSongForDiagnostics()
                        coroutineScope.launch {
                            if (song == null) {
                                android.util.Log.w("QqDiag", "vkey.diag 没有正在播放的曲目")
                            } else {
                                android.util.Log.i(
                                    "QqDiag",
                                    "vkey.diag 曲目=${song.name} source=${song.source} " +
                                        "sourceId=${song.sourceId} mediaId=${song.mediaId}",
                                )
                                // 先报「播放器认为当前是什么档位」，再问服务端 —— 两行对不上就是显示在撒谎。
                                android.util.Log.i(
                                    "QqDiag",
                                    "vkey.diag 档位快照 requested=" + playerViewModel.lastVerdictRequestedForDiagnostics() +
                                        " granted=" + playerViewModel.lastPlayedLevelForDiagnostics() +
                                        " br=" + playerViewModel.lastVerdictResultForDiagnostics()?.br +
                                        " type=" + playerViewModel.lastVerdictResultForDiagnostics()?.type +
                                        " songMax=" + playerViewModel.lastVerdictResultForDiagnostics()?.songMaxLevel,
                                )
                                com.takahashirinta.ncrust.qq.QqApi.diagnoseQuality(song)
                            }
                        }
                    }
                } else null,
            )
            Spacer(Modifier.height(24.dp))
        }

        // 音质
        item {
            SectionTitle(strings.qualitySectionTitle)
            // API < 27 没有系统 FLAC 解码器：选中 lossless / hires / jyeffect 会在取链阶段
            // 被跳过、实际拿到 mp3。这里显式提示，避免"选了无损却没无损"（Bug1-C）。
            val flacUnsupported = !SongUrlFetcher.deviceSupportsFlac
            MetroDropdownRow(
                label = strings.wifiQualityLabel,
                selectedIndex = wifiQuality,
                options = strings.qualityOptions,
                hint = if (flacUnsupported && isFlacTierIndex(wifiQuality)) {
                    strings.qualityFlacUnsupportedHint
                } else null,
                onSelect = {
                    wifiQuality = it
                    prefs.edit().putInt("wifi_quality", it).apply()
                    // Bug1-A：设置立即生效——正在播放时按新档位重新取链，而不是等下一首。
                    playerViewModel.onQualityPreferenceChanged()
                }
            )
            MetroDropdownRow(
                label = strings.mobileQualityLabel,
                selectedIndex = mobileQuality,
                options = strings.qualityOptions,
                hint = if (flacUnsupported && isFlacTierIndex(mobileQuality)) {
                    strings.qualityFlacUnsupportedHint
                } else null,
                onSelect = {
                    mobileQuality = it
                    prefs.edit().putInt("mobile_quality", it).apply()
                    playerViewModel.onQualityPreferenceChanged()
                }
            )
            Spacer(Modifier.height(24.dp))
        }

        // 播放
        item {
            SectionTitle(strings.playbackSectionTitle)
            // 标题与 Switch 同行居中，描述另起一行——描述不参与对齐，否则 Switch
            // 会被顶到与描述顶部对齐，看起来像挂在描述上。
            SettingSwitchRow(
                title = strings.gaplessSectionTitle,
                description = strings.gaplessDescription,
                checked = gaplessEnabled,
                onCheckedChange = {
                    gaplessEnabled = it
                    prefs.edit().putBoolean("gapless_playback", it).apply()
                    // 即时生效: VM 缓存的 gaplessEnabled 不刷新的话,
                    // 本首歌的预载状态与开关不一致, 要等下一首歌才对上
                    playerViewModel.refreshGaplessSetting()
                }
            )
            // v2.0.0 · T2：播放时禁止熄屏。只在 **isPlaying** 且**在播放器界面**时生效；
            // 进后台由 KeepScreenOnEffect 的 ON_PAUSE 摘掉窗口 flag（不后台常亮）。
            SettingSwitchRow(
                title = strings.keepScreenOnLabel,
                description = strings.keepScreenOnHint,
                checked = keepScreenOnEnabled,
                onCheckedChange = {
                    keepScreenOnEnabled = it
                    KeepScreenOnSetting.write(context, it)
                }
            )
            // v1.8.0 · T4：自动旋转（双向）。
            // ⚠️ 这是**应用内**开关，与系统设置里的"自动旋转"互相独立：应用既不读也不改
            // Settings.System.ACCELEROMETER_ROTATION，只决定自己的 requestedOrientation。
            SettingSwitchRow(
                title = strings.autoRotateLabel,
                description = strings.autoRotateDescription,
                checked = autoRotateEnabled,
                onCheckedChange = {
                    autoRotateEnabled = it
                    RotationSetting.write(context, it)
                }
            )
            // v1.8.0 · T3：大屏模式音频可视化。
            SettingSwitchRow(
                title = strings.audioVisualizerLabel,
                description = strings.audioVisualizerDescription,
                checked = audioVisualizerEnabled,
                onCheckedChange = {
                    audioVisualizerEnabled = it
                    VisualizerSetting.write(context, it)
                }
            )
            // v1.4.0 · 音乐人推荐卡片开关。默认关；目标艺人与锚点配置存在 prefs 且默认空，
            // 因此其他用户安装后既看不到卡片、也不会为它发任何请求。
            SettingSwitchRow(
                title = strings.artistRecoTitle,
                checked = artistRecoEnabled,
                onCheckedChange = {
                    artistRecoEnabled = it
                    ArtistReco.setEnabled(context, it)
                }
            )
            // v2.5.1 · F：页面切换动效（默认**开**）。**改完立即生效**，不需要重启：
            // 状态提升在 MainScreen，这里通过 onPageTransitionChange 上报；
            // NavGraph 的四个转场 lambda 在**下一次导航**时求值，所以返回列表再点进详情
            // 就已经是新行为。关掉 = 四个转场挂 EnterTransition.None，逐字节回到 v2.5.0。
            SettingSwitchRow(
                title = strings.motion.pageTransitionLabel,
                description = strings.motion.pageTransitionDescription,
                checked = pageTransitionEnabled,
                onCheckedChange = onPageTransitionChange
            )
            // 歌词翻译开关(Spotify 式双语:原句下方小号译文)。切了立即生效,播放器常挂载无需重进。
            SettingSwitchRow(
                title = strings.lyricsTranslationLabel,
                checked = lyricsTranslation,
                onCheckedChange = {
                    lyricsTranslation = it
                    prefs.edit().putBoolean("lyrics_translation", it).apply()
                    playerViewModel.setLyricsTranslation(it)
                }
            )
            // v1.5.0 · B / v1.5.1 · A 逐字动画三选一。默认「渐变扫过」；没有 yrc 逐字数据的
            // 歌不受影响（整行渲染与 v1.4.1 一致）。
            MetroDropdownRow(
                label = strings.lyricsWordAnimationLabel,
                selectedIndex = lyricsWordAnimation,
                options = strings.lyricsWordAnimationOptions,
                onSelect = {
                    lyricsWordAnimation = it
                    playerViewModel.setLyricsWordAnimation(it)
                }
            )
            // v1.5.2 逐字扫过质量三选一。只影响渐变带磨不磨圆 —— 两种档位的光标位置算法
            // 完全相同（都走 SweepTrack），所以切到「兼容」也不会退回按词跳变。
            MetroDropdownRow(
                label = strings.lyricsSweepQualityLabel,
                selectedIndex = lyricsSweepQuality,
                options = strings.lyricsSweepQualityOptions,
                onSelect = {
                    lyricsSweepQuality = it
                    playerViewModel.setLyricsSweepQuality(it)
                }
            )
            // v1.5.1 · E 歌词字号（0.7x~1.5x）。改完立即生效，无需重进播放器。
            MetroDropdownRow(
                label = strings.lyricsFontScaleLabel,
                selectedIndex = LyricsDisplayPrefs.fontScaleStepIndex(lyricsFontScale),
                options = LyricsDisplayPrefs.FONT_SCALE_LABELS,
                onSelect = {
                    val scale = LyricsDisplayPrefs.FONT_SCALE_STEPS[it]
                    lyricsFontScale = scale
                    playerViewModel.setLyricsFontScale(scale)
                }
            )
            // v1.5.1 · D 媒体面板歌词。默认关 —— 开启后系统媒体卡片的第二行会从
            // 「歌手」变成「歌手 · 当前歌词行」（Android 13+ 的面板第二行只认 ARTIST）。
            SettingSwitchRow(
                title = strings.lyricsInMediaSessionLabel,
                description = strings.lyricsInMediaSessionHint,
                checked = lyricsInMediaSession,
                onCheckedChange = {
                    lyricsInMediaSession = it
                    playerViewModel.setLyricsInMediaSession(it)
                }
            )
            // v1.9.0：AMLL TTML（逐字）歌词源。关掉后一个 TTML 请求都不发，与 v1.8.1 行为一致。
            SettingSwitchRow(
                title = strings.lyricsTtmlEnabledLabel,
                checked = lyricsTtmlEnabled,
                onCheckedChange = {
                    lyricsTtmlEnabled = it
                    playerViewModel.setLyricsTtmlEnabled(it)
                }
            )
            // 「TTML 优先」只在 TTML 开着时才有意义（关掉 TTML 时它不影响任何结果），
            // 因此**整行不挂载**而不是 alpha 隐藏 —— 见 AGENTS.md「Compose 触摸陷阱」第 1 条：
            // alpha=0 的节点照样参与命中测试，会在播放器死带里变成一个看不见的开关。
            if (lyricsTtmlEnabled) {
                SettingSwitchRow(
                    title = strings.lyricsTtmlFirstLabel,
                    checked = lyricsTtmlFirst,
                    onCheckedChange = {
                        lyricsTtmlFirst = it
                        playerViewModel.setLyricsTtmlFirst(it)
                    }
                )
            }
            // v1.9.3：音译（罗马音 / 粤拼）显示开关，**默认关**。开启后原文下方多一行音译小字
            // （有译文时排在译文下面）；没有音译数据的歌不受影响，不会多出空行。
            SettingSwitchRow(
                title = strings.lyricsRomanizationLabel,
                description = strings.lyricsRomanizationHint,
                checked = lyricsRomanization,
                onCheckedChange = {
                    lyricsRomanization = it
                    playerViewModel.setLyricsRomanization(it)
                }
            )
            // v2.0.0 · T4：动态字号（**实验性，默认关**）。开启后短句字号大、长句字号小
            // （按每句估算折行数，纯逻辑见 lyric/DynamicLyricFont.kt）。
            // 说明文字里必须明确提示可能引起视觉抖动 —— 这是任务书要求的诚实标注。
            SettingSwitchRow(
                title = strings.dynamicFontLabel,
                description = strings.dynamicFontHint,
                checked = dynamicFontEnabled,
                onCheckedChange = {
                    dynamicFontEnabled = it
                    playerViewModel.setDynamicLyricFont(it)
                }
            )
            Spacer(Modifier.height(24.dp))
        }

        // 外观：主题模式 + 主题色 + 语言
        item {
            SectionTitle(strings.themeModeSectionTitle)
            ThemeModeSelector(
                selected = themeMode,
                labels = Triple(
                    strings.themeModeSystem,
                    strings.themeModeDark,
                    strings.themeModeLight
                ),
                onSelect = onThemeModeChange
            )
            Spacer(Modifier.height(24.dp))

            SectionTitle(strings.themeSectionTitle)
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                ThemeColorSelector(
                    selectedIndex = themeIndex,
                    presets = themeColorPresets,
                    onSelect = onThemeChange
                )
            }
            Spacer(Modifier.height(24.dp))

            // B2-C：主题色来源三选一。跟随封面/跟随系统都是"动态来源"，拿不到就回落预设色。
            SectionTitle(strings.accentSourceSectionTitle)
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
            Spacer(Modifier.height(24.dp))

            SectionTitle(strings.languageSectionTitle)
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
            Spacer(Modifier.height(32.dp))
        }

        // 后台运行：跳转系统"允许后台活动 / 忽略电池优化"设置（与首次启动弹窗同一入口）。
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching {
                            context.startActivity(BackgroundActivity.requestIntent(context))
                        }.onFailure {
                            runCatching {
                                context.startActivity(BackgroundActivity.appDetailsIntent(context))
                            }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    strings.batteryTitle,
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
                MetroIcon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = LocalMetroColors.current.onSurfaceVariant,
                    sizeDp = 20.dp,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        // 自定义背景图（v1.2.0 · B3）。
        // 选图走 SAF 只读打开，取到后立刻降采样拷进私有目录 —— 因为不再需要回读原文件，
        // 所以**不**申请 persistable URI 权限（那会让应用长期持有用户文件的访问权）。
        item {
            SectionTitle(strings.bgSectionTitle)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { imagePicker.launch(arrayOf("image/*")) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    if (hasCustomBg) strings.bgChange else strings.bgPick,
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
            }
            if (hasCustomBg) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { BackgroundImageManager.clear(context) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MetroText(
                        strings.bgRemove,
                        color = LocalMetroColors.current.primary,
                        style = TextStyle(fontSize = 15.sp),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
        }

        // 存储与缓存：显示当前占用，点击后弹窗确认再清除。
        item {
            SectionTitle(strings.storageSectionTitle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showClearCacheConfirm = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    strings.cacheSizeLabel(cacheUsage.totalBytes),
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
                MetroText(
                    strings.clearCache,
                    color = LocalMetroColors.current.primary,
                    style = TextStyle(fontSize = 15.sp)
                )
            }
            // v2.0.0 · T3：占用拆成三项。数字与「清除缓存」能清掉的范围一一对应
            // （音频 = filesDir/offline/audio；图片 = cacheDir/image_cache；其他 = cacheDir 其余子项），
            // 任何一项都不与另一项重叠 —— 旧口径的图片缓存双计就是在这里被拆掉的。
            CacheUsageLine(strings.cacheUsageAudio, formatCacheBytes(cacheUsage.audioBytes))
            CacheUsageLine(strings.cacheUsageImage, formatCacheBytes(cacheUsage.imageBytes))
            CacheUsageLine(strings.cacheUsageOther, formatCacheBytes(cacheUsage.otherCacheBytes))
            // v2.0.0 · T3：离线缓存的单曲管理与容量上限。这一行只是入口，
            // 「清除缓存」那一行的行为一个字都没改。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showOfflineCacheManager = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    strings.offlineCacheManageLabel,
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
                MetroIcon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = LocalMetroColors.current.onSurfaceVariant,
                    sizeDp = 20.dp,
                )
            }
            Spacer(Modifier.height(32.dp))
        }

        // 关于：单行条目，右侧带箭头。
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAbout)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    strings.aboutButton,
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
                MetroIcon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = LocalMetroColors.current.onSurfaceVariant,
                    sizeDp = 20.dp,
                )
            }
        }
    }
    }
}

/** Profile 块：96dp 方形头像 + 昵称 titleLarge + UID/登录状态 bodySmall。整块可点。 */
@Composable
private fun ProfileBlock(
    isLoading: Boolean,
    profile: PlaylistApi.UserProfile?,
    notLoggedInText: String,
    loginHintText: String,
    uidLabel: String,
    onClick: () -> Unit
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                // v2.5.0 · B：用户头像是**人像** ⇒ 圆形（AppShapes.full），不套用方封面的
                // 「≥160dp → large / <160dp → small」尺寸规则。底板一起裁圆，
                // 否则方形底会从圆形头像的四角露出来（「未登录」的人像图标也随之为圆形）。
                .clip(AppShapes.full)
                .background(LocalMetroColors.current.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (profile?.avatarUrl?.isNotEmpty() == true) {
                AsyncImage(
                    model = profile.avatarUrl,
                    contentDescription = strings.userAvatarDesc,
                    // 圆形 + 1dp 描边；同一 shape 落在图片本身。
                    modifier = Modifier
                        .fillMaxSize()
                        .appCoverFrame(shape = AppShapes.full),
                    contentScale = ContentScale.Crop
                )
            } else {
                MetroIcon(
                    Icons.Default.Person,
                    strings.userIconDesc,
                    tint = LocalMetroColors.current.onSurfaceVariant,
                    sizeDp = 52.dp,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        // 文字列 weight(1f)：任何语言的昵称/提示都能换行不撑破。
        Column(modifier = Modifier.weight(1f)) {
            when {
                isLoading -> MetroText(
                    strings.loading,
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = TextStyle(fontSize = 20.sp),
                )
                profile != null -> {
                    MetroText(
                        profile.nickname,
                        color = LocalMetroColors.current.onBackground,
                        style = LocalMetroTypography.current.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    MetroText(
                        uidLabel,
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                else -> {
                    MetroText(
                        notLoggedInText,
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    MetroText(
                        loginHintText,
                        color = LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.6f),
                        style = LocalMetroTypography.current.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
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

/** 档位索引是否落在"依赖 FLAC 解码器"的档位（与 SongUrlFetcher 的判定同源）。 */
private fun isFlacTierIndex(index: Int): Boolean {
    val level = PlayerViewModel.QUALITY_LEVELS.getOrNull(index) ?: return false
    return SongUrlFetcher.isFlacTier(level)
}

@Composable
private fun AccountDialog(
    userProfile: PlaylistApi.UserProfile?,
    onDismiss: () -> Unit,
    onScanAuthorize: () -> Unit,
    onLogout: () -> Unit
) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // P2：同上，账号弹窗也抬到 surfaceContainerHigh。
                .background(LocalNcrustColors.current.surfaceContainerHigh)
                .padding(24.dp)
        ) {
            MetroText(
                strings.accountDialogTitle,
                color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(16.dp))

            if (userProfile != null) {
                MetroText(
                    strings.nicknameLabel(userProfile.nickname),
                    color = LocalMetroColors.current.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                MetroText(
                    strings.uidLabel(userProfile.userId.toString()),
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = TextStyle(fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(20.dp))
            }

            // 扫码授权其他设备：扫平板的登录二维码，经局域网把本机账号授权过去。
            FullWidthDialogButton(
                text = strings.scanEntryTitle,
                accent = false,
                borderColor = LocalMetroColors.current.primary,
                textColor = LocalMetroColors.current.primary,
                onClick = onScanAuthorize
            )
            Spacer(Modifier.height(12.dp))

            // 全宽按钮：容器 fillMaxWidth，文字 Center + 换行——极长翻译最多多占一行，不会撑破对话框。
            FullWidthDialogButton(
                text = strings.logoutButton,
                accent = false,
                borderColor = Color.Red.copy(alpha = 0.5f),
                textColor = Color.Red,
                onClick = onLogout
            )

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                DialogButton(
                    text = strings.close,
                    accent = false,
                    onClick = onDismiss
                )
            }
        }
    }
}

/** 短按钮：内容包裹式（wrap content）。用于对话框右下"取消/关闭/保存"。 */
@Composable
private fun DialogButton(
    text: String,
    accent: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .then(
                if (accent) Modifier.background(LocalMetroColors.current.primary)
                else Modifier.border(1.dp, LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.4f))
            )
            .clickable(onClick = onClick)
            // P2：10→14dp 垂直 padding，触控高度 ≈40dp → 48dp。
            .padding(horizontal = 20.dp, vertical = 14.dp)
    ) {
        MetroText(
            text,
            color = if (accent) LocalMetroColors.current.onPrimary else LocalMetroColors.current.onSurfaceVariant,
            // P2：显式 20sp 行框，触控高度可算（14+20+14 = 48dp）。
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 全宽按钮：填满可用宽度，文字居中，多行安全。用于对话框内的"更新 Cookie/退出登录"。 */
@Composable
private fun FullWidthDialogButton(
    text: String,
    accent: Boolean,
    borderColor: Color = LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.4f),
    textColor: Color = LocalMetroColors.current.onBackground,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (accent) Modifier.background(LocalMetroColors.current.primary)
                else Modifier.border(1.dp, borderColor)
            )
            .clickable(onClick = onClick)
            // P2：12→14dp 垂直 padding，单行触控高度 ≈44dp → 48dp。
            .padding(horizontal = 12.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        MetroText(
            text,
            color = if (accent) LocalMetroColors.current.onPrimary else textColor,
            // P2：显式 20sp 行框，触控高度可算（14+20+14 = 48dp，单行）。
            style = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 语言下拉。闭合态显示当前选中语言名 + 箭头；点击弹出所有语言。
 *
 * 多语言鲁棒：闭合态 Text 设 maxLines=1 + Ellipsis + weight(1f)。极长名如
 * "Советский русский"/"Middle English" 最多截断，绝不换行撑破箭头位置。
 */
@Composable
fun MetroLanguageDropdown(
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


// v2.0.0 · T3：统计逻辑搬到 CacheUsage.kt（纯逻辑 + JVM 单测），这里不再自己算。
// 旧实现 = Coil diskCache.size + folderSize(cacheDir) + 离线音频 size，其中
// folderSize(cacheDir) 已经递归包含 cacheDir/image_cache ⇒ 图片缓存被算了两遍。
// 现在统一走 CacheUsage.measure：三项互不重叠，且与「清除缓存」能清掉的范围一一对应。

/**
 * v2.1.0 · C：QQ 音乐账号卡片。
 *
 * 只做三件事：显示登录态、显示会员角标、提供登录/登出。
 * **刻意不做**「哪些音质可用」的细表：会员权益的权威判据在服务端
 * （详见 QqApi.fetchProfile 的注释 —— 登录态下的 VIP 字段没有实测过），
 * 界面上多写一行就多一行可能撒谎的文案。
 *
 * 登录态读的是 [QqAuthStore]（`ncrust_qq_prefs`），与网易云的 cookie 完全隔离：
 * 在这里登出**不会**影响网易云，反之亦然。
 *
 * 视觉沿用 [ProfileBlock] 的既有语言（整块可点 + 一行标题 + 一行状态），
 * 不引新组件、不加圆角（Kanesumi：直角、信息优先）。
 */
@Composable
private fun QqAccountBlock(
    accountTitle: String,
    brand: String,
    notLoggedInText: String,
    loginActionText: String,
    logoutText: String,
    /** v2.1.1：扫码登录的可用性说明。只在未登录时显示 —— 已登录的人不需要看它。 */
    availabilityNote: String,
    /** v2.1.1：手机号验证码登录的入口文案。 */
    phoneLoginText: String,
    onLogin: () -> Unit,
    onPhoneLogin: () -> Unit,
    /**
     * v2.1.4：**仅 debug 包**显示的取链诊断入口 —— 对当前播放的 QQ 曲目一次性问全档位。
     *
     * 为什么需要它：开发侧没有 QQ 音乐账号，而「超清母带只出极高」这件事**只有登录态才有区分度**
     * （匿名态所有档位都是 `104003`，见 PHASE0 报告 §7.2/§8）。没有这个入口，
     * 用户要复现就只能靠「碰巧在播放 QQ 曲目时抓 logcat」，日志里还未必有高档位的结果。
     * release 包里它整行不挂载（不是 `alpha=0`，见 AGENTS.md「Compose 触摸陷阱」第 1 条）。
     */
    onDiagnose: (() -> Unit)? = null,
    /** v2.5.4 · C：debug-only 的 QQ 兜底统计读出（release 里传 null ⇒ 整行不挂载）。 */
    onProbeStats: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var loggedIn by remember { mutableStateOf(QqAuthStore.isLoggedIn(context)) }
    var profile by remember { mutableStateOf(QqAuthStore.profile(context)) }

    SectionTitle(accountTitle)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { if (!loggedIn) onLogin() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            MetroText(text = brand, style = LocalMetroTypography.current.bodyLarge)
            Spacer(Modifier.height(4.dp))
            val status = when {
                !loggedIn -> notLoggedInText
                // 会员状态只是**显示**：判据来自服务端，取不到就不显示角标
                // （宁可不显示，也不要写一个猜出来的「非会员」）。
                profile.isVip() -> "VIP"
                QqAuthStore.uin(context) != null -> "uin " + QqAuthStore.uin(context)
                else -> ""
            }
            if (status.isNotEmpty()) {
                MetroText(
                    text = status,
                    style = LocalMetroTypography.current.bodySmall,
                    color = LocalMetroColors.current.onSurfaceVariant,
                )
            }
        }
        if (loggedIn) {
            MetroText(
                text = logoutText,
                style = LocalMetroTypography.current.bodyLarge,
                color = LocalMetroColors.current.primary,
                modifier = Modifier
                    .clickable {
                        QqAuthStore.clear(context)
                        loggedIn = false
                        profile = QqProfile()
                    }
                    .padding(8.dp),
            )
        } else {
            MetroText(
                text = loginActionText,
                style = LocalMetroTypography.current.bodyLarge,
                color = LocalMetroColors.current.primary,
                modifier = Modifier
                    .clickable(onClick = onLogin)
                    .padding(8.dp),
            )
        }
    }
    // v2.1.1：把「扫码登录依赖腾讯服务」这件事写在用户能看见的地方。
    // 起因是真机上扫码轮询被恒定拒绝时，界面只说「网络不稳定」，用户既不知道
    // 是服务端的问题，也不知道还有网页登录这条路。只在未登录时显示。
    //
    // v2.1.4：下面还挂了一个**仅 debug 包**的取链诊断入口（release 里 onDiagnose 为 null，
    // 整行不挂载 —— 不是 alpha=0，见 AGENTS.md「Compose 触摸陷阱」第 1 条）。
    if (!loggedIn) {
        // 手机号登录单独给一个入口：它解决的是「微信用户没有 QQ 号、也没法同机扫码」
        // 这个场景，藏在二维码浮层里等于让最需要它的人找不到。
        MetroText(
            text = phoneLoginText,
            style = LocalMetroTypography.current.bodyLarge,
            color = LocalMetroColors.current.primary,
            modifier = Modifier
                .clickable(onClick = onPhoneLogin)
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        )
        MetroText(
            text = availabilityNote,
            style = LocalMetroTypography.current.bodySmall,
            color = LocalMetroColors.current.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
    if (loggedIn && onDiagnose != null) {
        MetroText(
            text = "取链诊断（debug）：对当前播放的 QQ 曲目问全档位，见 logcat 的 vkey.diag",
            style = LocalMetroTypography.current.bodySmall,
            color = LocalMetroColors.current.primary,
            modifier = Modifier
                .clickable(onClick = onDiagnose)
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
    // v2.5.4 · C：debug-only 的兜底统计读出。**不依赖登录态**（未登录也能解析搜索
    // 结果、也会走散列兜底，样本照样有），所以判据只看 onProbeStats 是否为 null。
    // 文案直接写死中文：这一行在 release 包里根本不存在，补 8 个 locale 是无意义的
    // —— 与上面那条既有诊断入口同一条约定。
    if (onProbeStats != null) {
        MetroText(
            text = "兜底统计（debug）：读出 QQ 无 songid 兜底计数，见 logcat 的 QqProbe 并落盘",
            style = LocalMetroTypography.current.bodySmall,
            color = LocalMetroColors.current.primary,
            modifier = Modifier
                .clickable(onClick = onProbeStats)
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}
