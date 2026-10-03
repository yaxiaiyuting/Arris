/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.5.5 · C：乱序模式「上一首」落点的单测。
 */

package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [ShuffleNav] 的守卫。
 *
 * 这一组用例对应的**真机现象**：托盘「上一首」按钮在 PLC110 上受控复测 5 次只成功 1 次，
 * 而同按钮在非乱序模式下每次成功 —— 查落盘状态确认当时 `play_mode = 2`（SHUFFLE）。
 * 根因是 `playPrevious` 的乱序分支只有 `if (shuffledPosition > 0)`、**没有 else**。
 */
class ShuffleNavTest {

    /** ★ **轮首要回绕到轮末** —— 这是本版修的那一条。 */
    @Test
    fun `轮首的上一首回绕到轮末`() {
        assertEquals(4, ShuffleNav.previousPosition(current = 0, size = 5))
        assertEquals(1, ShuffleNav.previousPosition(current = 0, size = 2))
        assertEquals(0, ShuffleNav.previousPosition(current = 0, size = 1))
    }

    /** 非轮首就是单纯减一（与旧行为**逐值相同**，本版没有改这一段的语义）。 */
    @Test
    fun `非轮首的上一首是减一`() {
        for (size in 2..10) {
            for (cur in 1 until size) {
                assertEquals(cur - 1, ShuffleNav.previousPosition(cur, size))
            }
        }
    }

    /** 空序列 ⇒ `null`（调用方保持原状），不是 0 也不是 -1。 */
    @Test
    fun `空序列返回 null`() {
        assertNull(ShuffleNav.previousPosition(current = 0, size = 0))
        assertNull(ShuffleNav.previousPosition(current = 3, size = 0))
    }

    /**
     * 游标越界返回 `null`（**不钳制**）。
     *
     * `shuffledPosition` 与 `shuffledIndices` 应当始终同源。悄悄钳制会把
     * 「状态已经不一致」这件事盖住，而那种不一致的另一个表现是
     * 「按上一首跳到了一首没听过的歌」—— 宁可什么都不做。
     */
    @Test
    fun `游标越界返回 null`() {
        assertNull(ShuffleNav.previousPosition(current = -1, size = 5))
        assertNull(ShuffleNav.previousPosition(current = 5, size = 5))
        assertNull(ShuffleNav.previousPosition(current = 99, size = 5))
    }

    /** 结果永远落在 `0 until size` 内（调用方直接拿它索引 `shuffledIndices`）。 */
    @Test
    fun `结果永远在合法下标范围内`() {
        for (size in 1..12) {
            for (cur in 0 until size) {
                val p = ShuffleNav.previousPosition(cur, size)
                assertEquals(true, p != null && p in 0 until size)
            }
        }
    }

    /**
     * ★ **与 `playNext` 的队尾回绕对称**：连点 `size` 次「上一首」会走遍整轮且不重复。
     *
     * 这条同时证明「回绕不会把游标卡住」—— 旧实现在轮首卡死，连点多少次都不动。
     */
    @Test
    fun `连点 size 次上一首走遍整轮且回到起点`() {
        val size = 6
        var cur = 0
        val visited = mutableListOf(cur)
        repeat(size) {
            cur = ShuffleNav.previousPosition(cur, size)!!
            visited.add(cur)
        }
        assertEquals("走遍整轮后必须回到起点", 0, cur)
        assertEquals("经过的下标应当互不重复", size + 1, visited.size)
        assertEquals(size, visited.dropLast(1).distinct().size)
    }

    /** 单元素序列：上一首回到它自己（与 CYCLE 下 `playFromQueue(0)` 的行为一致）。 */
    @Test
    fun `单元素序列的上一首是自己`() {
        assertEquals(0, ShuffleNav.previousPosition(current = 0, size = 1))
    }
}
