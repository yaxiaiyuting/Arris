package com.takahashirinta.ncrust.ui.components

import com.takahashirinta.ncrust.network.CoverUrls
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.theme.AppShapes
import io.github.takahashirinta.kanesumi.anim.sokuou.MetroDefault
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import io.github.takahashirinta.kanesumi.structure.MetroTopScrim
import com.takahashirinta.ncrust.ui.i18n.LocalStrings

/**
 * 无边框详情页骨架。
 *
 * 与旧版差异：
 *  - 没有 M3 TopAppBar 的实体 Surface。顶部使用向下渐隐的深色 scrim 提供图标可读性，
 *    避免以往"半透明黑色方块"堆在角落的突兀感——scrim 与内容边缘自然融合，仍无任何 border。
 *  - 返回箭头是裸图标，无背板，触控区为 48dp。
 *  - 内容 LazyColumn 从屏幕顶部开始（延伸到 status bar 下），第一项 header() 会填满整个可视区宽度。
 *  - title 参数已弃用（Groove 风：页面标题由 header 本身承担），保留以兼容签名。
 */
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    isLoading: Boolean = false,
    // 缓存兜底：调用方已有可立即渲染的内容时传 true，跳过全屏 loader。
    hasCachedContent: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    header: @Composable () -> Unit,
    /**
     * v1.3.0：顶部 scrim 右上角的可选动作（如歌单详情页的「编辑歌单」）。
     *
     * ⚠️ 详情页的**页面内**操作按钮必须避开「播放器死带」——折叠态播放器卡片
     * （[com.takahashirinta.ncrust.ui.player.PlayerCardOverlay] 的 fillMaxSize +
     * translationY = collapsedOffsetY）会在屏幕 y≈collapsedOffsetY 以下形成一条
     * **不可见但仍参与命中测试、且会先于本页拿到事件**的死带；落在带内的按钮点不动
     * （事件连页面级的 Initial pass 都收不到）。所以详情页的底部/低位操作一律走
     * 顶部 scrim 或列表行，不要放进 headerActions 的下半部分。
     */
    onTopEndAction: (() -> Unit)? = null,
    topEndIcon: ImageVector = Icons.Default.MoreVert,
    topEndContentDescription: String = "",
    /**
     * v2.3.0 · B：把内部 `LazyColumn` 的 `LazyListState` 暴露给调用方。
     *
     * 存在的唯一理由是**下拉刷新**：`nestedScroll` 需要一个「列表在不在顶部」的判据，
     * 而那个判据只有 `LazyListState` 能回答（`firstVisibleItemIndex == 0 &&
     * firstVisibleItemScrollOffset == 0`）。默认值让所有既有调用点一个字节都不用改。
     */
    listState: LazyListState = rememberLazyListState(),
    /** v2.3.0 · B：挂在内容 `LazyColumn` 上的修饰符（下拉刷新手势从这儿进去）。 */
    contentModifier: Modifier = Modifier,
    /** 设置等纯色页面使用主题表面渐隐；封面详情仍需深色遮罩保护白色图标。 */
    navigationOverArtwork: Boolean = true,
    content: LazyListScope.() -> Unit
) {
    val strings = LocalStrings.current
    Box(
        // 必须 opaque：nav popExit 动画期间详情页会 slide + 部分透明，
        // 若无 bg 会透视到下方主 tab 屏（返回时 mount/绘制未完成 → 用户看到"低一层残影"）。
        // 主 tab 屏一直在下层挂载，DetailScaffold 显示期间就是它遮住 tab 屏。
        modifier = Modifier
            .fillMaxSize()
            .background(LocalMetroColors.current.background)
    ) {
        val stateKey = when {
            error != null -> DetailScaffoldState.Error
            isLoading && !hasCachedContent -> DetailScaffoldState.Loading
            else -> DetailScaffoldState.Content
        }
        Crossfade(
            targetState = stateKey,
            animationSpec = SokuouTweens.CoverFade,
            modifier = Modifier.fillMaxSize(),
            label = "DetailScaffoldCrossfade"
        ) { state ->
            when (state) {
                DetailScaffoldState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        MetroProgressIndicator(color = LocalMetroColors.current.primary)
                    }
                }
                DetailScaffoldState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            MetroText(error ?: "", color = Color.Red, style = TextStyle(fontSize = 16.sp))
                            if (onRetry != null) {
                                Spacer(Modifier.height(16.dp))
                                Box(
                                    modifier = Modifier
                                        .background(LocalMetroColors.current.primary)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = onRetry
                                        )
                                        .padding(horizontal = 24.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    MetroText(strings.retry, color = LocalMetroColors.current.onPrimary)
                                }
                            }
                        }
                    }
                }
                DetailScaffoldState.Content -> {
                    // 入场级联：内容分支从下方 12dp 微滑上 + 淡入。方向与 NavGraph 横向推入正交。
                    val density = LocalDensity.current
                    val slideOffsetPx = with(density) { 12.dp.roundToPx() }
                    val cascadeState = remember {
                        MutableTransitionState(false).apply { targetState = true }
                    }
                    AnimatedVisibility(
                        visibleState = cascadeState,
                        enter = fadeIn(animationSpec = tween(220, easing = MetroDefault)) +
                            slideInVertically(
                                animationSpec = tween(220, easing = MetroDefault),
                                initialOffsetY = { slideOffsetPx }
                            )
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().then(contentModifier),
                            state = listState,
                            contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                            flingBehavior = rememberMetroFlingBehavior()
                        ) {
                            item { header() }
                            content()
                        }
                    }
                }
            }
        }

        TopScrimIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = strings.back,
            onClick = onBack,
            overArtwork = navigationOverArtwork
        )
        // 右上角动作与返回箭头同处顶部 scrim：这一带（y≈208–400）实测不在播放器死带内。
        if (onTopEndAction != null) {
            TopScrimIconButton(
                icon = topEndIcon,
                contentDescription = topEndContentDescription,
                onClick = onTopEndAction,
                alignment = Alignment.TopEnd,
                overArtwork = navigationOverArtwork
            )
        }
    }
}

/**
 * 顶部渐隐 scrim + 裸图标。封面上使用深色遮罩和白色图标；设置、关于等纯色页
 * 使用主题背景渐隐和对应前景色，避免浅色页面出现灰黑色阴影。
 *
 * 实现委托给库的 MetroTopScrim（同语义：渐隐 scrim + 48dp 触控区 + 直角闪切），
 * 保留本签名以便现有调用方不改。
 */
@Composable
fun TopScrimIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    alignment: Alignment = Alignment.TopStart,
    overArtwork: Boolean = true
) {
    val colors = LocalMetroColors.current
    MetroTopScrim(
        icon = icon,
        contentDescription = contentDescription,
        onClick = onClick,
        alignment = alignment,
        modifier = if (overArtwork) Modifier else Modifier.background(
            Brush.verticalGradient(
                0f to colors.background,
                0.5f to colors.background,
                1f to colors.background.copy(alpha = 0f)
            )
        ),
        scrimAlphaTop = if (overArtwork) 0.55f else 0f,
        iconColor = if (overArtwork) Color.White else colors.onBackground
    )
}

private enum class DetailScaffoldState { Loading, Error, Content }

/**
 * 无边框详情页头部。
 *
 * 窄屏：封面全宽通铺（fillMaxWidth + aspectRatio 1:1），下方是标题 / 副标题 / info
 * / 右侧大圆播放按钮；封面贴屏幕边缘、无 padding。
 *
 * 宽屏（>=600dp）：改为 Apple Music 式两栏——左侧固定 220dp 方封面，右侧信息列
 * 底部对齐。避免封面在平板上铺成一整屏的正方形。
 */
@Composable
fun DetailHeader(
    coverUrl: String?,
    title: String,
    subtitle: String? = null,
    infoLines: List<String> = emptyList(),
    onPlayAll: (() -> Unit)? = null,
    // 副标题可点击(如专辑页的作曲者 → 跳歌手页), 且无按动反馈
    onSubtitleClick: (() -> Unit)? = null,
    headerActions: @Composable ColumnScope.() -> Unit = {}
) {
    val strings = LocalStrings.current
    val isWide = LocalConfiguration.current.screenWidthDp >= 600

    if (isWide) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // 顶部留出返回按钮触控区的高度（系统栏 inset 已由外层内容容器处理）。
                .padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 20.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            AsyncImage(
                model = CoverUrls.large(coverUrl),
                contentDescription = strings.coverDesc,
                placeholder = androidx.compose.ui.graphics.painter.ColorPainter(LocalMetroColors.current.surfaceVariant),
                // v2.5.0 · B：220dp ≥ 160dp ⇒ AppShapes.large（尺寸→形状规则见下方窄屏封面）。
                modifier = Modifier
                    .size(220.dp)
                    .appCoverFrame(shape = AppShapes.large),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(24.dp))
            Column(modifier = Modifier.weight(1f)) {
                DetailHeaderInfoRow(
                    title = title,
                    subtitle = subtitle,
                    infoLines = infoLines,
                    onPlayAll = onPlayAll,
                    onSubtitleClick = onSubtitleClick,
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    content = headerActions
                )
            }
        }
        return
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // v2.5.0 · B：封面圆角 + 1dp 描边。形状按**渲染边长**选（≥160dp → large，
        // <160dp → small）；窄屏这里是 fillMaxWidth 的方封面，手机上 ≥320dp ⇒ large。
        AsyncImage(
            model = CoverUrls.large(coverUrl),
            contentDescription = strings.coverDesc,
            // 纯色占位:详情首开封面解码完成前不闪空块(Metro 直接落图,不做 crossfade)
            placeholder = androidx.compose.ui.graphics.painter.ColorPainter(LocalMetroColors.current.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .appCoverFrame(shape = AppShapes.large),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(20.dp))
        DetailHeaderInfoRow(
            title = title,
            subtitle = subtitle,
            infoLines = infoLines,
            onPlayAll = onPlayAll,
            onSubtitleClick = onSubtitleClick,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        // 右侧附加操作（如"收藏专辑"按钮），左对齐、无 divider。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            content = headerActions
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** 标题 / 副标题 / info 行 + 右侧大圆播放按钮。宽窄屏共用。 */
@Composable
private fun DetailHeaderInfoRow(
    title: String,
    subtitle: String?,
    infoLines: List<String>,
    onPlayAll: (() -> Unit)?,
    onSubtitleClick: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            MetroText(
                title,
                color = LocalMetroColors.current.onBackground,
                style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.Normal),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                MetroText(
                    subtitle,
                    color = LocalMetroColors.current.primary,
                    style = TextStyle(fontSize = 14.sp),
                    modifier = if (onSubtitleClick != null)
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onSubtitleClick
                        )
                    else Modifier
                )
            }
            infoLines.forEach { line ->
                Spacer(Modifier.height(3.dp))
                MetroText(line, color = LocalMetroColors.current.onSurfaceVariant, style = TextStyle(fontSize = 13.sp))
            }
        }
        if (onPlayAll != null) {
            Spacer(Modifier.width(12.dp))
            PlayAllButton(size = 48.dp, onClick = onPlayAll)
        }
    }
}
