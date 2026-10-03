/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（v1.2.0 · B3 自定义背景图）：
 *   - 本文件为新增：铺在根 Box 最底层的背景图层 + 半透明遮罩。
 */
package com.takahashirinta.ncrust.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.takahashirinta.ncrust.ui.theme.BackgroundImageManager
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors

/**
 * 自定义背景图层（v1.2.0 · B3）。
 *
 * 必须铺在**根 Box 的最底层**：各页面（DetailScaffold / PlayerCard / 各 Screen）
 * 都会自己用 `LocalMetroColors.current.background` 铺一层不透明底色，所以背景图
 * 只有垫在最下面才不会被盖住 —— 这也是不去改 MetroColors.background 的原因
 * （改了会让那十几处铺底全变成不透明色，图反而看不见）。
 *
 * 内存：图片在导入时已降采样到屏幕分辨率并以 RGB_565 解码（见 BackgroundImageManager），
 * 这里用 Coil 加载 + `allowRgb565(true)`，一张背景图约 4 MB 量级。
 */
@Composable
fun CustomBackgroundLayer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 版本号变化（导入 / 关闭 / 内存压力）都会重算，不缓存成单例状态。
    val revision by BackgroundImageManager.revision.collectAsState()
    val suppressed by BackgroundImageManager.suppressed.collectAsState()

    val active = remember(revision, suppressed) {
        !suppressed && BackgroundImageManager.isActive(context)
    }
    if (!active) return

    val file = remember(revision) { BackgroundImageManager.backgroundFile(context) }
    val isLightTheme = LocalMetroColors.current.background.luminance() > 0.5f

    // 内存压力下背景被隐藏（释放位图），回到前台时恢复显示 —— 自包含在图层里，
    // 不需要 MainActivity 再挂生命周期回调。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) BackgroundImageManager.onForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(file)
                // 背景图不需要 alpha：RGB_565 让这张位图内存减半（B3）
                .allowRgb565(true)
                // Metro 风格不做淡入，和全局 crossfade 关闭保持一致
                .crossfade(false)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // 半透明遮罩：保证浅色 / 深色模式下 onBackground 文字对比度。
        // 浅色主题用白罩提亮、深色主题用黑罩压暗，两边都往中间拉。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (isLightTheme) Color.White.copy(alpha = 0.35f)
                    else Color.Black.copy(alpha = 0.45f)
                )
        )
    }
}
