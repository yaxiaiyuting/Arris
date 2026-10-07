/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.8.0「设置界面二级菜单重构」阶段 4：「账号与登录」二级页。
 *
 * 本文件里的 `ProfileBlock` / `QqAccountBlock` / `AccountDialog` / `DialogButton` /
 * `FullWidthDialogButton` 全部**逐字搬运**自 `UserScreen.kt`（`private` → `internal`），
 * 行为一个字节都没改；账号相关的状态与副作用（加载资料、扫码授权、二维码登录、
 * 登录态变化后重查会员缓存）也一并搬进来 —— 它们原先就在 UserScreen 顶部。
 *
 * 为什么这一组单独一个文件、不塞进 `SettingsGroupScreen.kt`：
 * 它是唯一一组**带自己的一整套覆盖层**（账号弹窗 / 二维码登录 / 手机扫码授权）的分组，
 * 而那三个覆盖层必须挂在 `LazyColumn` **之外**（`QrAuthorizeScreen` 是全屏 composable，
 * 塞进列表项里会被列表测量裁掉）。把它独立成页，这些覆盖层的挂载点就一目了然。
 */

package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
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
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.qq.QqAuthStore
import com.takahashirinta.ncrust.qq.QqProfile
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.QrAuthorizeScreen
import com.takahashirinta.ncrust.ui.components.QrLoginDialog
import com.takahashirinta.ncrust.ui.components.SectionTitle
import com.takahashirinta.ncrust.ui.components.appCoverFrame
import com.takahashirinta.ncrust.ui.components.settingsGroupTitle
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.settings.SettingsGroup
import com.takahashirinta.ncrust.ui.settings.SettingsRenderPlan
import com.takahashirinta.ncrust.ui.settings.SettingsRowKind
import com.takahashirinta.ncrust.ui.theme.AppShapes
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.launch

/**
 * 「账号与登录」二级页。
 *
 * 覆盖层（账号弹窗 / 二维码登录 / 手机扫码授权）挂在 `DetailScaffold` **之上**的 Box 里：
 * `QrAuthorizeScreen` 是全屏 composable、不是 `Dialog`，塞进 `LazyColumn` 的 item 里
 * 会被列表的测量约束裁掉（旧实现里它也在列表之外，这是原样保留的挂载点）。
 *
 * ⚠️ 这里**没有** `viewModel()`：本页组合在 `NavHost` 里，`LocalViewModelStoreOwner` 是
 * NavBackStackEntry，`viewModel()` 会造出第二个 `PlayerViewModel`（诊断入口读的就是它，
 * 会读到一份没人更新的状态）。所以 VM 由 `MainScreen` 显式传进来。
 */
@Composable
internal fun SettingsAccountPage(
    playerViewModel: PlayerViewModel,
    cookieRefreshTrigger: Int,
    onShowWebLogin: () -> Unit,
    onShowQqLogin: () -> Unit,
    /**
     * v3.2.0 · P1：打开 B 站扫码登录浮层。
     *
     * 浮层本体在 `ui/components/BiliQrLoginDialog.kt`（复用既有 `MetroDialog` 与
     * `generateQrBitmap`），本页只负责把入口摆在账号区里 —— 与 QQ 那一块同一个形状。
     */
    onShowBiliLogin: () -> Unit,
    onShowQqPhoneLogin: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val coroutineScope = rememberCoroutineScope()

    var showAccountDialog by remember { mutableStateOf(false) }
    var showQrLogin by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }

    var hasCookie by remember { mutableStateOf(CookieManager.hasCookie(context)) }
    var userProfile by remember { mutableStateOf<PlaylistApi.UserProfile?>(null) }
    var isLoadingProfile by remember { mutableStateOf(false) }
    // 扫码登录为平板 / 大屏独占：手机端未登录点头像仍直接进 WebView 官方登录页。
    val isWideLayout = LocalConfiguration.current.screenWidthDp >= 600

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
                    // v2.1.4：cookie 判定过期时会员缓存同样失效（理由见下方登出处）。
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
    LaunchedEffect(cookieRefreshTrigger) {
        if (cookieRefreshTrigger > 0) {
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

    Box(modifier = Modifier.fillMaxSize()) {
        DetailScaffold(
            title = settingsGroupTitle(strings, SettingsGroup.ACCOUNT),
            onBack = onBack,
            header = { SettingsGroupHeader(SettingsGroup.ACCOUNT) },
        ) {
            // 行列表由渲染计划产出（与单测断言的是同一个函数）。
            SettingsRenderPlan.rowsOf(SettingsGroup.ACCOUNT.id) { _ -> null }.forEach { entry ->
                when (SettingsRenderPlan.rowKindOf(entry)) {
                    SettingsRowKind.ACCOUNT_PROFILE -> item(key = entry.id) {
                        ProfileBlock(
                            isLoading = isLoadingProfile,
                            profile = userProfile,
                            notLoggedInText = strings.notLoggedIn,
                            loginHintText = strings.loginHint,
                            uidLabel = strings.uidLabel(userProfile?.userId?.toString() ?: ""),
                            onClick = {
                                when {
                                    hasCookie -> showAccountDialog = true
                                    // 平板/大屏：原生扫码（官方 App 扫），弹窗里再给「通用登录」退回 WebView
                                    isWideLayout -> showQrLogin = true
                                    else -> onShowWebLogin()
                                }
                            }
                        )
                    }

                    SettingsRowKind.ACCOUNT_BILI -> item(key = entry.id) {
                        com.takahashirinta.ncrust.ui.components.BiliAccountBlock(
                            accountTitle = strings.sourceBiliAccount,
                            brand = strings.source.sourceBilibili,
                            notLoggedInText = strings.notLoggedIn,
                            loginActionText = strings.biliLoginTitle,
                            logoutText = strings.biliLogout,
                            // 如实写「登录后能否无损未验证」——不许承诺本版没验证过的东西。
                            qualityNote = strings.biliQualityNote,
                            onLogin = onShowBiliLogin,
                        )
                    }

                    SettingsRowKind.ACCOUNT_QQ -> item(key = entry.id) {
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
                                    // 它读的是「拦了几次 QQ id → ncm webLog」，同样**不上报**。
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
                    }

                    else -> Unit // 账号组只有上面两种行（渲染计划里没有别的形态）
                }
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
    }
}

/** Profile 块：96dp 方形头像 + 昵称 titleLarge + UID/登录状态 bodySmall。整块可点。 */
@Composable
internal fun ProfileBlock(
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

@Composable
internal fun AccountDialog(
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
internal fun DialogButton(
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
internal fun FullWidthDialogButton(
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
 * v2.1.0 · C：qm 账号卡片。
 *
 * 只做三件事：显示登录态、显示会员角标、提供登录/登出。
 * **刻意不做**「哪些音质可用」的细表：会员权益的权威判据在服务端
 * （详见 QqApi.fetchProfile 的注释 —— 登录态下的 VIP 字段没有实测过），
 * 界面上多写一行就多一行可能撒谎的文案。
 *
 * 登录态读的是 [QqAuthStore]（`ncrust_qq_prefs`），与 ncm 的 cookie 完全隔离：
 * 在这里登出**不会**影响 ncm，反之亦然。
 *
 * 视觉沿用 [ProfileBlock] 的既有语言（整块可点 + 一行标题 + 一行状态），
 * 不引新组件、不加圆角（Kanesumi：直角、信息优先）。
 */
@Composable
internal fun QqAccountBlock(
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
     * 为什么需要它：开发侧没有 qm 账号，而「超清母带只出极高」这件事**只有登录态才有区分度**
     * （匿名态所有档位都是 `104003`，见 PHASE0 报告 §7.2/§8）。没有这个入口，
     * 用户要复现就只能靠「碰巧在播放 QQ 曲目时抓 logcat」，日志里未必有高档位的结果。
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
