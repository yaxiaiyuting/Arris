/*
 * Arris —— 本 Fork（https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * J16 回归：下拉刷新松手不归零，两次独立短拉会累计触发。
 */

package com.takahashirinta.ncrust.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.takahashirinta.ncrust.playlist.PullToRefresh
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * J16：`onPostScroll` 只在上滑 / 不在顶部时归零，**松手路径不归零**。
 *
 * 后果（审查的调用序列：`onPostScroll` → `onPreFling` → `onPostFling`）：
 * 第一次 80px 没过阈值就松手，位移留在 80；第二次再拉 80px 累到 160px，
 * 于是**两次独立的半个动作凑成一次刷新**，而且松手后指示器一直挂着。
 *
 * 这里驱动的是真实 [PullToRefreshConnection]（不是复制一份公式），断言的是
 * 「手势结束 ⇒ 累计归零 ⇒ 第二次仍然不触发」。
 */
class PullToRefreshConnectionTest {

    private var refreshCount = 0
    private var atTop = true

    // `PullToRefreshState` 只是两个 Compose 状态字段（`dragPx` / `refreshing`），
    // JVM 单测里可直接构造，不需要 Compose 运行时。
    private fun newState() = PullToRefreshState()

    private fun connection(state: PullToRefreshState) =
        PullToRefreshConnection(state, { atTop }, { refreshCount++ })

    private fun PullToRefreshConnection.dragDown(px: Float) {
        onPostScroll(Offset.Zero, Offset(0f, px), NestedScrollSource.Drag)
    }

    // `onPreFling` / `onPostFling` 在 Compose 1.7 是 suspend 的；单测用 runBlocking 直接驱动。
    private fun PullToRefreshConnection.release() = runBlocking {
        onPreFling(Velocity.Zero)
        onPostFling(Velocity.Zero, Velocity.Zero)
    }

    /** 两次独立短拉（各 80px，都不到 160px 阈值）不得凑成一次刷新。 */
    @Test
    fun `twoSeparateShortPullsDoNotAddUpToARefresh()`() {
        val state = newState()
        val connection = connection(state)

        connection.dragDown(80f)
        assertEquals(80f, state.dragPx, 0.001f)
        assertFalse("80px 未过阈值，不该触发", state.refreshing)

        // 松手：本次下拉结束。
        connection.release()
        assertEquals("松手必须把累计位移归零", 0f, state.dragPx, 0.001f)

        connection.dragDown(80f)
        assertEquals(80f, state.dragPx, 0.001f)
        connection.release()

        assertEquals("两次 80px 的独立手势都不该触发刷新", 0, refreshCount)
        assertFalse(state.refreshing)
    }

    /** 单次手势内累计越过阈值仍要触发（修复不能把正常路径一起关掉）。 */
    @Test
    fun `oneLongPullStillTriggersOnce()`() {
        val state = newState()
        val connection = connection(state)

        connection.dragDown(100f)
        connection.dragDown(100f)

        assertEquals("单次手势内 200px 必须触发", 1, refreshCount)
        assertTrue(state.refreshing)
        assertEquals("触发后位移归零，指示器交给 refreshing 维持", 0f, state.dragPx, 0.001f)
    }

    /** 松手归零只看位移，不能把「正在刷新」这个状态一起清掉。 */
    @Test
    fun `releaseKeepsTheRefreshingFlag()`() {
        val state = newState()
        val connection = connection(state)

        connection.dragDown(PullToRefresh.THRESHOLD_PX)
        connection.release()

        assertTrue("刷新已触发，松手不许撤销", state.refreshing)
    }

    /** 不在顶部 / 上滑的既有归零行为保持原样。 */
    @Test
    fun `upwardDragAndNonTopListStillResetAccumulation()`() {
        val state = newState()
        val connection = connection(state)

        connection.dragDown(50f)
        connection.onPreScroll(Offset(0f, -10f), NestedScrollSource.Drag)
        assertEquals("上滑立刻放弃本次下拉", 0f, state.dragPx, 0.001f)

        atTop = false
        connection.dragDown(200f)
        assertEquals("列表不在顶部时不得累计", 0f, state.dragPx, 0.001f)
        assertEquals(0, refreshCount)
    }

    /** 惯性阶段的位移不参与累计（只有 Drag 才算下拉）。 */
    @Test
    fun `flingScrollDoesNotAccumulate()`() {
        val state = newState()
        val connection = connection(state)

        connection.onPostScroll(
            consumed = Offset.Zero,
            available = Offset(0f, 500f),
            source = NestedScrollSource.Fling,
        )
        assertEquals(0f, state.dragPx, 0.001f)
        assertEquals(0, refreshCount)
    }
}
