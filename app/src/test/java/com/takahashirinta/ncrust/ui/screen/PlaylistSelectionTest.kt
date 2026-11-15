/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 歌单详情「长按进入多选」的状态机单测。
 *
 * 覆盖任务书点名的 5 条迁移（进入 / 切换 / 全选 / 退出 / 批量移出后清空），
 * 外加 3 条只在边界上才想得到的：点掉最后一个**不**退出模式、非多选态下所有迁移
 * 都是空操作、列表刷新后剔除已消失的 id。
 *
 * 这些都是纯逻辑，不需要 Robolectric —— 与 `PlayerDragSnapTest` 同一手法：
 * 把「点几下才成立」的判断从 Composable 里搬出来，让它在 JVM 上有断言。
 */

package com.takahashirinta.ncrust.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistSelectionTest {

    // ---------------------------------------------------------------- 初始态

    @Test
    fun `初始态不是多选且没有选中`() {
        val none = PlaylistSelection.None
        assertFalse(none.active)
        assertTrue(none.ids.isEmpty())
        assertEquals(0, none.count)
        // 默认构造 == None：`remember { mutableStateOf(PlaylistSelection()) }` 同样成立。
        assertEquals(none, PlaylistSelection())
    }

    // ---------------------------------------------------------------- 进入

    @Test
    fun `长按进入多选并选中该行`() {
        val s = PlaylistSelection.None.enter(42L)
        assertTrue(s.active)
        assertEquals(setOf(42L), s.ids)
        assertEquals(1, s.count)
        assertTrue(s.isSelected(42L))
        assertFalse(s.isSelected(43L))
    }

    @Test
    fun `已经处于多选时再进入等价于只选中这一行`() {
        // UI 在多选态下不派发长按，但状态机自己也不能靠「调用方守规矩」才正确：
        // 长按别的行 = 把选中集换成那一行（与「退出后再长按」结果相同，可幂等重放）。
        val s = PlaylistSelection.None.enter(1L).toggle(2L).enter(3L)
        assertEquals(setOf(3L), s.ids)
        assertTrue(s.active)
    }

    // ---------------------------------------------------------------- 切换

    @Test
    fun `多选态点击行在选中与取消之间来回切换`() {
        val s = PlaylistSelection.None.enter(1L).toggle(2L)   // {1, 2}
        assertEquals(setOf(1L), s.toggle(2L).ids)             // 点已选行 → 去掉
        assertEquals(setOf(1L, 2L, 3L), s.toggle(3L).ids)     // 点未选行 → 加上
        assertEquals(emptySet<Long>(), s.toggle(1L).toggle(2L).ids)  // 逐个点掉 → 空
        assertEquals(setOf(1L, 2L), s.toggle(1L).toggle(1L).ids)     // 点两次 = 还原
        // 每次切换都是对**同一个起点**的迁移：s 本身没被改过。
        assertEquals(setOf(1L, 2L), s.ids)
    }

    @Test
    fun `点掉最后一个不会退出多选，只把选中清空`() {
        // Android 列表的惯例：取消最后一个选中 ≠ 结束多选。出口只有 exit / afterRemove。
        val s = PlaylistSelection.None.enter(7L).toggle(7L)
        assertTrue(s.active)
        assertEquals(0, s.count)
        assertTrue(s.ids.isEmpty())
    }

    @Test
    fun `非多选态的切换是空操作，点击仍然走播放`() {
        // 这是「不在多选时点行 = 播放」的那条路径：状态机不能悄悄把人拖进多选。
        assertEquals(PlaylistSelection.None, PlaylistSelection.None.toggle(1L))
    }

    // ---------------------------------------------------------------- 全选

    @Test
    fun `全选把列表里的全部 id 收进选中集`() {
        val all = listOf(1L, 2L, 3L)
        val s = PlaylistSelection.None.enter(1L).selectAll(all)
        assertEquals(all.toSet(), s.ids)
        assertEquals(3, s.count)
        assertTrue(s.isAllSelected(all))
    }

    @Test
    fun `全选是幂等的，重复点不会累加也不会翻车`() {
        val all = listOf(1L, 2L)
        val once = PlaylistSelection.None.enter(1L).selectAll(all)
        assertEquals(once, once.selectAll(all))
        assertEquals(2, once.count)
    }

    @Test
    fun `取消全选清空选中但不退出多选`() {
        val all = listOf(1L, 2L, 3L)
        val s = PlaylistSelection.None.enter(1L).selectAll(all).clearSelection()
        assertTrue(s.active)
        assertTrue(s.ids.isEmpty())
        assertFalse(s.isAllSelected(all))
        // 选完再取消全选，再全选仍然成立（按钮是同一个入口来回切）。
        assertEquals(all.toSet(), s.selectAll(all).ids)
    }

    @Test
    fun `空歌单不认为已全选`() {
        // 否则选择条上会显示「取消全选」，点下去毫无变化 —— 一个骗人的按钮。
        assertFalse(PlaylistSelection.None.enter(1L).isAllSelected(emptyList()))
        assertFalse(PlaylistSelection.None.selectAll(emptyList()).isAllSelected(emptyList()))
    }

    @Test
    fun `部分选中不算全选`() {
        val all = listOf(1L, 2L, 3L)
        val s = PlaylistSelection.None.enter(1L).toggle(2L)
        assertFalse(s.isAllSelected(all))
        assertEquals(setOf(1L, 2L), s.ids)
    }

    // ---------------------------------------------------------------- 退出

    @Test
    fun `退出把多选标记与选中集一起清空`() {
        // 不变式 1：残留的选中集会在下次进入时以「已选 N 首」的形式复活。
        val s = PlaylistSelection.None.enter(1L).toggle(2L).exit()
        assertFalse(s.active)
        assertTrue(s.ids.isEmpty())
        assertEquals(0, s.count)
    }

    @Test
    fun `退出是幂等的，且非多选态退出不改变状态`() {
        assertEquals(PlaylistSelection.None, PlaylistSelection.None.exit())
        assertEquals(PlaylistSelection.None, PlaylistSelection.None.exit().exit())
    }

    // ---------------------------------------------------------------- 批量移出

    @Test
    fun `批量移出全部选中后退出多选并清空`() {
        // 任务书点名的那条：移出成功后不能留一个「已选 3 首」的空壳。
        val all = listOf(1L, 2L, 3L)
        val s = PlaylistSelection.None.enter(1L).selectAll(all).afterRemove(all)
        assertEquals(PlaylistSelection.None, s)
        assertFalse(s.active)
        assertTrue(s.ids.isEmpty())
    }

    @Test
    fun `只移出一部分时剩下的保持选中`() {
        // UI 现在是一次性删完，这条是给「将来做分批/失败重试」留的语义：
        // 删掉的那些必须从选中集里消失，剩下的继续可操作。
        val s = PlaylistSelection.None.enter(1L).toggle(2L).toggle(3L).afterRemove(listOf(2L, 3L))
        assertTrue(s.active)
        assertEquals(setOf(1L), s.ids)
    }

    @Test
    fun `非多选态下移出回调不改变状态`() {
        assertEquals(PlaylistSelection.None, PlaylistSelection.None.afterRemove(listOf(1L, 2L)))
    }

    // ---------------------------------------------------------------- 列表刷新

    @Test
    fun `刷新后剔除已经不在列表里的 id`() {
        // 进详情页先渲染缓存、再拉网络：刷新回来少了歌，选中集不能留幽灵。
        val s = PlaylistSelection.None.enter(1L).toggle(2L).toggle(3L).retainExisting(listOf(1L, 3L))
        assertEquals(setOf(1L, 3L), s.ids)
        assertTrue(s.active)
    }

    @Test
    fun `刷新后一首都不剩时保持多选但选中清空`() {
        // 与「点掉最后一个不退出」同一语义：模式由用户显式关闭，不因数据变化被动退出。
        val s = PlaylistSelection.None.enter(1L).toggle(2L).retainExisting(emptyList())
        assertTrue(s.active)
        assertEquals(0, s.count)
    }

    // ---------------------------------------------------------------- 值语义

    @Test
    fun `每次迁移都返回新值，原状态不被修改`() {
        val start = PlaylistSelection.None.enter(1L)
        val after = start.toggle(2L).selectAll(listOf(9L)).clearSelection().exit()
        // start 是一个不可变值：上面那串全都没碰它。
        assertEquals(setOf(1L), start.ids)
        assertTrue(start.active)
        assertEquals(PlaylistSelection.None, after)
    }

    @Test
    fun `选中集与顺序无关`() {
        val a = PlaylistSelection.None.enter(1L).toggle(2L).toggle(3L)
        val b = PlaylistSelection.None.enter(3L).toggle(2L).toggle(1L)
        assertEquals(a, b)
        assertEquals(a.ids, b.ids)
    }
}
