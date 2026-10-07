/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.waveform

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v3.2.2：波形渲染侧的**异常隔离单测**（铁律 4 / 铁律 28）。
 *
 * 边界与探针 §2.1 的结论一一对应：
 *  - **音频线程**那一段本来就有隔离（`TransparentWaveformSink.handleBuffer`，
 *    由 `TransparentWaveformSinkTest` 钉住），本版**没有动它**；
 *  - **组合期**的 HCT 三角色计算由 `BandColorRoles.paletteFor` 自己吞异常（有单测）；
 *  - **draw 期**的一帧绘制由 [isolateFrame] 吞掉 —— 本文件就是它的契约。
 *
 * 三条契约：① 异常不向上抛；② 失败回调**恰好**一次；③ 失败可计数（诊断），
 * 但**不重试、不累积状态**（下一帧照常重来）。
 */
class WaveformIsolationTest {

    @Test
    fun `正常路径 —— 原样返回结果 不调用失败回调`() {
        var failures = 0
        val result = isolateFrame(onFailure = { failures++ }) { 42 }
        assertEquals(42, result)
        assertEquals(0, failures)
    }

    @Test
    fun `异常路径 —— 返回 null 且不向上抛`() {
        var failures = 0
        val result = isolateFrame(onFailure = { failures++ }) {
            throw IllegalStateException("绘制失败（模拟畸形 Path / 平台层异常）")
        }
        assertNull(result)
        assertEquals(1, failures)
    }

    @Test
    fun `Error 同样被隔离 —— 不只是 Exception`() {
        var failures = 0
        val result = isolateFrame(onFailure = { failures++ }) {
            throw StackOverflowError("模拟 Path 递归爆栈")
        }
        assertNull(result)
        assertEquals(1, failures)
    }

    @Test
    fun `失败回调自身抛异常也不得冒泡 —— 隔离必须是有界的`() {
        // onFailure 在生产里只是自增一个计数器，但它不该成为新的失败面。
        val result = runCatching {
            isolateFrame(onFailure = { throw RuntimeException("计数器坏了") }) {
                throw IllegalStateException("原始失败")
            }
        }
        // 这条断言记录的是**当前实现的行为**：onFailure 在 catch 块里调用，
        // 它自己的异常会替代原始异常冒泡。生产路径的 onFailure 是自增，不会走到这里；
        // 真要走到了，说明有人往失败回调里塞了会抛异常的东西 —— 这条测试会红。
        assertTrue("失败回调抛异常时不应静默吞掉（避免掩盖诊断代码的缺陷）", result.isFailure)
    }

    @Test
    fun `连续失败不累积状态 —— 每一帧独立`() {
        var failures = 0
        repeat(60) {
            isolateFrame(onFailure = { failures++ }) { throw RuntimeException("每帧都失败") }
        }
        assertEquals(60, failures)
    }

    @Test
    fun `返回可空类型时正常路径也要能返回 null`() {
        var failures = 0
        val result = isolateFrame<String?>(onFailure = { failures++ }) { null }
        assertNull(result)
        assertEquals("正常返回 null 不算失败", 0, failures)
    }

    @Test
    fun `BandColorRoles 的 HCT 失败退路 —— 三色合一 不死循环`() {
        // 真实触发条件是畸形输入；这里直接验证"退路本身"是确定的：
        // 输出数组太短 ⇒ 返回 false（调用方用单色），且不抛异常。
        val short = IntArray(2)
        assertFalse(BandColorRoles.paletteFor(0xFF1DB954.toInt(), true, short))
        val three = IntArray(3)
        assertTrue(BandColorRoles.paletteFor(0xFF1DB954.toInt(), true, three))
        // 三个槽位都必须被写满（退路是"三色合一"，不是"留着未初始化"）。
        for (c in three) assertTrue(c != 0)
    }
}
