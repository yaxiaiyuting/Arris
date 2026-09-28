package com.takahashirinta.ncrust.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.takahashirinta.ncrust.network.SongItem

@Composable
fun PlayerCardOverlay(
    song: SongItem?,
    isPlaying: Boolean,
    progress: Animatable<Float, AnimationVector1D>,
    collapsedOffsetY: Float,
    screenHeightPx: Float,
    totalDragDistancePx: Float = 0f,
    playbackQueue: List<SongItem> = emptyList(),
    currentQueueIndex: Int = -1,
    playMode: Int = 0,
    onPlayPause: () -> Unit,
    onDismiss: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onRemoveFromQueue: (Int) -> Unit = {},
    onPlayFromQueue: (Int) -> Unit = {},
    onMoveInQueue: (Int, Int) -> Unit = { _, _ -> },
    onTogglePlayMode: () -> Unit = {},
    onPlayNothing: () -> Unit = {},
    onSongInfoClick: () -> Unit = {},
    // v2.5.4 · E：托盘第二行「作者」那一段的点击（纯透传，见 PlayerCard 的同名参数）。
    // v2.6.1 · P0：形参从 `Long` 改成整首 [SongItem] —— 只传数值 id 会让调用方
    // 无法判断它属于哪个音源（那正是「周杰伦跳到马洪波」的成因）。
    onArtistClick: (SongItem) -> Unit = {},
    onClearQueue: () -> Unit = {},
    onSavePlaylist: () -> Unit = {},
    // P1：大屏幕模式（横屏桌面播放器布局）开关与入口/出口按钮回调，纯透传。
    bigScreen: Boolean = false,
    onToggleBigScreen: () -> Unit = {},
    // v1.8.0 · T4：自动旋转开关与切换回调（纯透传，状态事实源在 RotationSetting）。
    autoRotate: Boolean = false,
    onToggleAutoRotate: () -> Unit = {}
) {
    // v3.2.1 · P0：15 个回调打成一包再转交（`PlayerCard` 的签名实测占 1366 code unit）。
    // 这里用 `remember(15 个回调)` 缓存 —— 保证「回调没变时对象是同一个」，
    // `PlayerCard` 因此仍然可以跳过不必要的重组（与拆分前逐帧相同的跳过语义）。
    val callbacks = remember(
        onPlayPause, onDismiss, onPlayPrevious, onPlayNext, onRemoveFromQueue,
        onPlayFromQueue, onMoveInQueue, onTogglePlayMode, onPlayNothing, onSongInfoClick,
        onArtistClick, onClearQueue, onSavePlaylist, onToggleBigScreen, onToggleAutoRotate,
    ) {
        PlayerCardCallbacks(
            onPlayPause = onPlayPause,
            onDismiss = onDismiss,
            onPlayPrevious = onPlayPrevious,
            onPlayNext = onPlayNext,
            onRemoveFromQueue = onRemoveFromQueue,
            onPlayFromQueue = onPlayFromQueue,
            onMoveInQueue = onMoveInQueue,
            onTogglePlayMode = onTogglePlayMode,
            onPlayNothing = onPlayNothing,
            onSongInfoClick = onSongInfoClick,
            onArtistClick = onArtistClick,
            onClearQueue = onClearQueue,
            onSavePlaylist = onSavePlaylist,
            onToggleBigScreen = onToggleBigScreen,
            onToggleAutoRotate = onToggleAutoRotate,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = collapsedOffsetY + (0f - collapsedOffsetY) * progress.value
            }
    ) {
        PlayerCard(
            song = song,
            isPlaying = isPlaying,
            screenHeightPx = screenHeightPx,
            progress = progress,
            totalDragDistancePx = totalDragDistancePx,
            playbackQueue = playbackQueue,
            currentQueueIndex = currentQueueIndex,
            playMode = playMode,
            callbacks = callbacks,
            bigScreen = bigScreen,
            autoRotate = autoRotate,
        )
    }
}
