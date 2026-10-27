/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.8.1 · E10（残余）：拖拽重排提交的**身份载荷**必须是一次性的。
 */

package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E10 残余的**载荷语义**契约。
 *
 * `QueueView` 的 `onMove` 回调类型是 `(Int, Int) -> Unit`（定义在 `PlayerCardState` 的
 * callbacks 里，要改它得同时动 `PlayerCardExpanded` / `PlayerCardOverlay` 两层转发），
 * 所以本轮用 [QueueMoveCommit] 把两端身份带过这一层：`QueueView` 提交前 `publish`，
 * `MainScreen.moveInQueue` 紧接着 `take`。
 *
 * 这个"握手"只有三条性质，但每一条错了都会变成**移动错歌**：
 *
 * | 性质 | 错了会怎样 |
 * |---|---|
 * | 一次性（take 后清空） | 下一次按下标提交会拿到上一次的身份 ⇒ 移动上一轮那首歌 |
 * | 两端都必须有 | 只有一端时 `MainScreen` 无从重定位，必须退回下标口径而不是半信半疑 |
 * | 后写覆盖先写 | 连续两次拖拽（第一次的回调没被消费）时用旧身份 ⇒ 又一次移动错歌 |
 */
class QueueMoveCommitTest {

    private val a = TrackKey(MusicSource.NETEASE, 1L)
    private val b = TrackKey(MusicSource.QQMUSIC, 2L)

    @Test
    fun `publish 之后 take 拿到两端身份并清空`() {
        QueueMoveCommit.publish(a, b)
        val taken = QueueMoveCommit.take()
        assertEquals("第一次 take 必须拿到两端身份", a to b, taken)
        assertNull("第二次 take 必须是 null —— 身份载荷是一次性的", QueueMoveCommit.take())
    }

    @Test
    fun `任一端为空时 take 返回 null`() {
        QueueMoveCommit.publish(null, b)
        assertNull("只有落点身份不够", QueueMoveCommit.take())
        QueueMoveCommit.publish(a, null)
        assertNull("只有被拖身份不够", QueueMoveCommit.take())
        // 取空之后也不该留下"半个身份"让下一次 take 误判。
        assertNull("空载荷不许残留", QueueMoveCommit.take())
    }

    @Test
    fun `后一次 publish 覆盖前一次`() {
        QueueMoveCommit.publish(a, b)
        val c = TrackKey(MusicSource.NETEASE, 3L)
        QueueMoveCommit.publish(b, c)
        assertEquals("最后一次登记的身份生效", b to c, QueueMoveCommit.take())
    }

    @Test
    fun `取空之后再 publish 仍然可用`() {
        QueueMoveCommit.publish(a, b)
        QueueMoveCommit.take()
        QueueMoveCommit.publish(b, a)
        assertEquals("清空不应破坏后续提交", b to a, QueueMoveCommit.take())
    }
}
