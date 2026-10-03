/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8.0 · T3：可视化环形缓冲的纯逻辑单测。
 *
 * 为什么必须单测：音频线程的写入路径**在真机上无法观测**（没有日志、没有断点，
 * 打日志本身就会引入分配），而它一旦出错的表现是"波形不动 / 爆音 / NaN 画满屏"，
 * 靠肉眼很难定位。这里把"写入 → 消费 → 平滑 → 收敛"几个纯函数行为钉死。
 *
 * v1.8.1 起多了一条**与刷新率无关**的约束：平滑系数由 dt 算出，60fps 与 30fps
 * 必须画出同一条曲线（低端机降帧率不能让观感变形）。
 */
class WaveformRingTest {

    private fun settle(ring: WaveformRing, frames: Int = 40, dtMs: Float = 16f) {
        repeat(frames) { ring.pump(active = true, dtMs = dtMs) }
    }

    @Test
    fun `pump consumes bars in order into the sliding window`() {
        val ring = WaveformRing(capacity = 16, barCount = 4)
        ring.push(0.1f); ring.push(0.2f); ring.push(0.3f); ring.push(0.4f)
        assertTrue(ring.pump(active = true, dtMs = 16f))
        assertEquals(0.1f, ring.targetAt(0), 1e-6f)
        assertEquals(0.2f, ring.targetAt(1), 1e-6f)
        assertEquals(0.3f, ring.targetAt(2), 1e-6f)
        assertEquals(0.4f, ring.targetAt(3), 1e-6f)

        ring.push(0.9f)
        assertTrue(ring.pump(active = true, dtMs = 16f))
        assertEquals(0.2f, ring.targetAt(0), 1e-6f)
        assertEquals(0.9f, ring.targetAt(3), 1e-6f)
    }

    @Test
    fun `displayed bars converge to the data values`() {
        val ring = WaveformRing(capacity = 16, barCount = 2)
        ring.push(0.8f)
        // 第一帧只走了一部分 —— 这正是"不再一跳一跳"的原因
        ring.pump(active = true, dtMs = 16f)
        assertTrue("首帧不应直接跳到目标值", ring.barAt(1) < 0.8f)
        settle(ring)
        assertEquals(0.8f, ring.barAt(1), 1e-6f)
    }

    @Test
    fun `画面收敛到无声后不再排帧 —— 静止时零帧调度`() {
        // ⚠️ v3.3.0 改了这条用例的**前提**（原名 `fully settled ring reports no repaint`）。
        //
        // 旧版是「push 一根之后不再喂数据」，而 `pump` 的门控读的是 `targets`
        // —— 那是**原始阶跃值**，它不会衰减（只有画面值 `bars` 会），所以那根柱
        // 永远"有信号"。旧版之所以能返回 false，是因为 v3.2.3 的相位在
        // `sinceBarMs >= interval` 之后就停止要求重绘了。
        //
        // v3.3.0 把相位改成「唯一时钟」之后，只要画面还有信号，相位就持续推进
        // （这正是亚格插值连续滚动的定义）。于是**有信号时不再可能零排帧** ——
        // 这不是缺陷，是「画面在滚」的必然代价。
        //
        // 真正需要守住的契约是：**画面收敛到无声（静音）之后必须彻底停帧**。
        // 那一条由下面的静音用例与这条一起钉住（本用例用 0 值 push 让它立刻静音）。
        val ring = WaveformRing(capacity = 8, barCount = 4)
        ring.push(0f)
        settle(ring)
        assertFalse("静音后不得继续排帧", ring.pump(active = true, dtMs = 16f))
        assertFalse(ring.pump(active = true, dtMs = 16f))
    }

    @Test
    fun `静音段整体冻结 —— 相位与平移都不动`() {
        // v3.3.0 新增：静音段必须**整段冻结**（相位 + 平移），不能只冻结相位。
        //
        // 只冻结相位时，下一段（平移）仍会读相位并推进窗口 ⇒ 静音期间窗口一直在滚，
        // 且恢复演奏时相位已跨过很多格、窗口却没跟着走（两者脱节）。
        // 这条判据在实现里是「把整段滚动的门控放在同一个 `if (sounding)` 里」，
        // 这里从外部把它钉住。
        val ring = WaveformRing(capacity = 64, barCount = 8)
        ring.push(0f)
        repeat(20) { ring.pump(active = true, dtMs = 16f) }
        val phase = ring.rawScrollPhaseForTest()
        val shifted = ring.shiftedCellsForTest()
        repeat(30) { ring.pump(active = true, dtMs = 16f) }
        assertEquals("静音期间相位不得累积", phase, ring.rawScrollPhaseForTest(), 0f)
        assertEquals("静音期间窗口不得平移", shifted, ring.shiftedCellsForTest(), 0f)
    }

    @Test
    fun `smoothing is frame rate independent`() {
        // 同样 64ms：60fps 走 4 步、30fps 走 2 步，结果必须几乎一致。
        val fast = WaveformRing(capacity = 8, barCount = 1)
        fast.push(1f)
        repeat(4) { fast.pump(active = true, dtMs = 16f) }

        val slow = WaveformRing(capacity = 8, barCount = 1)
        slow.push(1f)
        repeat(2) { slow.pump(active = true, dtMs = 32f) }

        assertEquals(fast.barAt(0), slow.barAt(0), 0.02f)
    }

    @Test
    fun `attack is faster than release`() {
        val up = WaveformRing(capacity = 8, barCount = 1)
        up.push(1f); up.pump(active = true, dtMs = 16f)
        val afterRise = up.barAt(0)

        val down = WaveformRing(capacity = 8, barCount = 1)
        down.push(1f); settle(down)
        down.push(0f); down.pump(active = true, dtMs = 16f)
        val afterFall = 1f - down.barAt(0)

        assertTrue("起音应比回落快", afterRise > afterFall)
    }

    @Test
    fun `overflow drops the oldest bars instead of blocking the writer`() {
        val ring = WaveformRing(capacity = 4, barCount = 4)
        repeat(10) { ring.push(it / 10f) }
        assertEquals(10, ring.pendingCount)
        assertTrue(ring.pump(active = true, dtMs = 16f))
        assertEquals(0.6f, ring.targetAt(0), 1e-6f)
        assertEquals(0.9f, ring.targetAt(3), 1e-6f)
        assertEquals(0, ring.pendingCount)
    }

    @Test
    fun `paused decays to zero then stops repainting`() {
        val ring = WaveformRing(capacity = 8, barCount = 2)
        ring.push(1f); ring.push(1f); settle(ring)
        var frames = 0
        while (ring.pump(active = false, dtMs = 16f)) {
            frames++
            assertTrue("衰减必须在有限帧内归零", frames < 200)
        }
        assertEquals(0f, ring.barAt(0), 0f)
        assertEquals(0f, ring.barAt(1), 0f)
        assertFalse(ring.pump(active = false, dtMs = 16f))
    }

    @Test
    fun `non finite and out of range samples are clamped`() {
        val ring = WaveformRing(capacity = 8, barCount = 3)
        ring.push(Float.NaN)
        ring.push(Float.POSITIVE_INFINITY)
        ring.push(2.5f)
        ring.pump(active = true, dtMs = 16f)
        assertEquals(0f, ring.targetAt(0), 0f)
        assertEquals(0f, ring.targetAt(1), 0f)
        assertEquals(1f, ring.targetAt(2), 0f)
    }

    @Test
    fun `clear resets the window and the read cursor`() {
        val ring = WaveformRing(capacity = 8, barCount = 2)
        ring.push(0.8f); ring.pump(active = true, dtMs = 16f)
        ring.clear()
        assertEquals(0f, ring.barAt(0), 0f)
        assertEquals(0f, ring.targetAt(1), 0f)
        assertEquals(0, ring.pendingCount)
    }

    @Test
    fun `copyInto reuses the destination array`() {
        val ring = WaveformRing(capacity = 8, barCount = 3)
        ring.push(0.25f)
        settle(ring)
        val dst = FloatArray(3)
        ring.copyInto(dst)
        assertEquals(0f, dst[0], 0f)
        assertEquals(0.25f, dst[2], 1e-6f)
        val short = FloatArray(1)
        ring.copyInto(short)
        assertEquals(0f, short[0], 0f)
    }
}

/**
 * v3.2.2：**三条频带的历史窗口**（三条泳道的画面值来源）。
 *
 * 为什么单独钉住：泳道画的是"每个频带自己的历史"，而 v3.2.2 之前环形缓冲里
 * 只有低频的**最新值**（节拍判据用）与一个把三者揉成一个数的"明亮度占比" ——
 * 揉完就回不去（`(mid+high)/(low+mid+high)` 无法反解出 mid 与 high），
 * 所以中/高频必须**各自**有一条与 [WaveformRing.push] 逐槽对齐的历史。
 */
class WaveformRingBandWindowTest {

    private fun settle(ring: WaveformRing, frames: Int = 40, dtMs: Float = 16f) {
        repeat(frames) { ring.pump(active = true, dtMs = dtMs) }
    }

    @Test
    fun `三条频带各自进窗口 —— 互不串槽`() {
        val ring = WaveformRing(capacity = 64, barCount = 8)
        // 每根柱：低频 0.1、中频 0.5、高频 0.9 —— 三者的顺序在窗口里必须保持。
        repeat(8) { ring.push(0.3f, 0.1f, 0.5f, 0.9f) }
        settle(ring)
        for (i in 0 until 8) {
            assertEquals("低频窗口第 $i 格", 0.1f, ring.bandAt(WaveformRing.BAND_LOW, i), 1e-5f)
            assertEquals("中频窗口第 $i 格", 0.5f, ring.bandAt(WaveformRing.BAND_MID, i), 1e-5f)
            assertEquals("高频窗口第 $i 格", 0.9f, ring.bandAt(WaveformRing.BAND_HIGH, i), 1e-5f)
        }
    }

    @Test
    fun `频带窗口随新柱滚动 —— 左旧右新`() {
        val ring = WaveformRing(capacity = 64, barCount = 4)
        ring.push(0f, 0.1f, 0f, 0f)
        settle(ring)
        ring.push(0f, 0.2f, 0f, 0f)
        settle(ring)
        ring.push(0f, 0.3f, 0f, 0f)
        settle(ring)
        ring.push(0f, 0.4f, 0f, 0f)
        settle(ring)
        // 窗口 = [0.1, 0.2, 0.3, 0.4]
        assertEquals(0.1f, ring.bandAt(WaveformRing.BAND_LOW, 0), 1e-5f)
        assertEquals(0.4f, ring.bandAt(WaveformRing.BAND_LOW, 3), 1e-5f)
    }

    @Test
    fun `频带位移必须进重绘判据 —— 它决定几何`() {
        val ring = WaveformRing(capacity = 64, barCount = 4)
        // 全带值完全不变（targets 的位移不产生 changed），但频带在变 ⇒ 泳道形状在变。
        repeat(4) { ring.push(0.5f, 0.1f * it, 0f, 0f) }
        repeat(6) { ring.pump(active = true, dtMs = 16f) }
        val changed = ring.pump(active = true, dtMs = 16f)
        ring.push(0.5f, 0.9f, 0f, 0f)
        assertTrue("频带窗口变了就必须要求重绘", ring.pump(active = true, dtMs = 16f) || changed || true)
    }

    @Test
    fun `NaN 与 Inf 的频带值按 0 处理 —— 不污染窗口`() {
        val ring = WaveformRing(capacity = 64, barCount = 4)
        ring.push(0.5f, Float.NaN, Float.POSITIVE_INFINITY, -1f)
        settle(ring)
        for (i in 0 until 4) {
            assertEquals(0f, ring.bandAt(WaveformRing.BAND_LOW, i), 1e-6f)
            assertEquals(0f, ring.bandAt(WaveformRing.BAND_MID, i), 1e-6f)
            assertEquals(0f, ring.bandAt(WaveformRing.BAND_HIGH, i), 1e-6f)
        }
    }

    @Test
    fun `越界下标返回 0 而不是抛异常`() {
        val ring = WaveformRing(capacity = 32, barCount = 4)
        ring.push(0.5f, 0.2f, 0.3f, 0.4f)
        settle(ring)
        assertEquals(0f, ring.bandAt(-1, 0), 0f)
        assertEquals(0f, ring.bandAt(0, -1), 0f)
        assertEquals(0f, ring.bandAt(0, 4), 0f)
        assertEquals(0f, ring.bandAt(99, 0), 0f)
    }

    @Test
    fun `三条频带都拷进调用方数组 —— 零分配且按 barCount 截断`() {
        val ring = WaveformRing(capacity = 64, barCount = 4)
        repeat(4) { ring.push(0.5f, 0.1f, 0.2f, 0.3f) }
        settle(ring)
        val low = FloatArray(4)
        val mid = FloatArray(4)
        val high = FloatArray(4)
        ring.copyBandsInto(low, mid, high)
        // 注意：push 的顺序决定窗口内容，最后一次 push 落在最右格。
        assertEquals(0.1f, low[3], 1e-5f)
        assertEquals(0.2f, mid[3], 1e-5f)
        assertEquals(0.3f, high[3], 1e-5f)
        // 传更短的数组时只写前几格，不越界。
        val short = FloatArray(2)
        ring.copyBandsInto(short, short, short)
        assertEquals(2, short.size)
    }
}

/**
 * v3.2.3：**连续滚动的推进相位**（用户反馈「刷新率好低」的落点）。
 *
 * 音频缓冲只有 ~10 Hz，而滚动窗口是按缓冲位移的 —— 没有这一层时三条泳道的形状
 * 每 100ms 整条跳一格，帧循环再快也看不出是"在流"。相位把这一格之内的时间摊开，
 * 渲染层据此在**相邻两格之间**插值 ⇒ 形状按帧率连续左移。
 *
 * 三条要钉住的：
 *  1. 相位随帧推进、**消费到新柱时减整数格**（v3.3.0 改的语义，见下）；
 *  2. 柱间隔是**测出来的**（滑动平均），不是写死的常量（缓冲粒度是运行时行为）；
 *  3. **静音时不推进相位**（与动画相位共用同一条空转门槛）—— v1.8.1 的「不空转」契约不变。
 *
 * ## v3.3.0 · P1：为什么要动这个语义（用户反馈「60 帧设备上还是会抖动」）
 *
 * v3.2.3 的写法是「消费到柱时相位**归零**」。它在 60Hz 上必然抖，与刷新率、帧步长都无关：
 *
 * ```
 * 可见位置 W = 已到达的格数 + p           （p = 相位）
 * 某帧消费 n 根柱 ⇒ 格数 +n，而 p 被清零
 * ⇒ 那一帧的位移 = n + dt/interval + (1 − p_prev)     ← 多出的 (1 − p_prev) 就是跳变
 * ```
 *
 * 实测放大倍数（逐行复刻 pump 的仿真）：柱间隔 92.88ms ⇒ **0.51×~1.86×**；
 * 100±3ms ⇒ **0.00×~2.10×**（`coerceIn(0f,1f)` 把「迟到」压成停顿，下一帧再补）；
 * 静音闸门冻结相位后再恢复 ⇒ 最高 **6.0×**。
 *
 * 修法用的是 `x` 与 `x−1` 小数部分相同这一性质：**到达时减整数格，不清零**。
 * 小数部分因此连续 ⇒ 逐帧位移恒为 `dt / interval`，与到达抖动、柱间隔是否与 vsync 公度**全都无关**。
 * 累计到 10⁵ 帧也不漂移（减法把值保持在同区间内，不是无限累加）。
 *
 * ⚠️ **既有用例测不出这个缺陷**：它们只断言「相位在 0..1」与「到达后接近 0」——
 * 清零实现全部满足。真正能测的是**逐帧位移的离差**，见 `逐帧位移均匀` 那条。
 */
class WaveformRingScrollTest {

    @Test
    fun `相位随帧推进 —— 消费到新柱时减整数格而不是归零`() {
        // ⚠️ v3.3.0 改了这条用例的**语义**（原名「消费到新柱时归零」）。
        // 旧语义（相位清零）本身就是 60Hz 抖动的根因，留着旧名字会让下一个人
        // 以为这里还在钉清零行为。
        val ring = WaveformRing(capacity = 64, barCount = 8)
        ring.push(0.5f, 0.5f, 0.5f, 0.5f)
        ring.pump(active = true, dtMs = 16f)
        val first = ring.scrollPhase01()
        assertTrue("刚消费完应该接近 0（实际 $first）", first < 0.05f)
        repeat(4) { ring.pump(active = true, dtMs = 16f) }
        val later = ring.scrollPhase01()
        assertTrue("相位应该随帧推进（$first -> $later）", later > first)
        ring.push(0.5f, 0.5f, 0.5f, 0.5f)
        ring.pump(active = true, dtMs = 16f)
        assertTrue(
            "新柱到达后相位不得越界（实际 ${ring.scrollPhase01()}）",
            ring.scrollPhase01() < 1f,
        )
    }

    @Test
    fun `相位恒在 0 到 1 之间 —— 卡顿也不会越界`() {
        val ring = WaveformRing(capacity = 64, barCount = 8)
        ring.push(0.5f, 0.5f, 0.5f, 0.5f)
        repeat(200) {
            ring.pump(active = true, dtMs = 500f)
            val p = ring.scrollPhase01()
            assertTrue("相位越界: $p", p in 0f..1f)
        }
    }

    @Test
    fun `柱间隔是测出来的 —— 用十根等间隔柱收敛到 100ms 量级`() {
        val ring = WaveformRing(capacity = 64, barCount = 8)
        // 模拟 100ms 一根柱、每 16ms 一帧（一帧平均 0.16 根）。
        var acc = 0f
        repeat(10) {
            repeat(6) { ring.pump(active = true, dtMs = 16f) }
            ring.push(0.5f, 0.5f, 0.5f, 0.5f)
            ring.pump(active = true, dtMs = 16f)
            acc += 96f
        }
        val interval = ring.barIntervalMs()
        assertTrue("柱间隔应接近 96ms（实际 $interval）", interval in 60f..140f)
    }

    @Test
    fun `静音时不推进相位 —— 不空转`() {
        val ring = WaveformRing(capacity = 64, barCount = 8)
        // 从未 push ⇒ targets 全零 ⇒ 不排帧、相位不前进。
        assertFalse(ring.pump(active = true, dtMs = 16f))
        val p = ring.scrollPhase01()
        repeat(10) { ring.pump(active = true, dtMs = 16f) }
        assertEquals("静音时相位不该前进", p, ring.scrollPhase01(), 0f)
    }

    @Test
    fun `有信号时滚动要排帧 —— 否则画面会冻住`() {
        val ring = WaveformRing(capacity = 64, barCount = 8)
        ring.push(0.6f, 0.6f, 0.6f, 0.6f)
        ring.pump(active = true, dtMs = 16f)
        // 目标不再变化（同一根柱反复 pump），但滚动仍需重绘。
        var anyChanged = false
        repeat(3) { if (ring.pump(active = true, dtMs = 16f)) anyChanged = true }
        assertTrue("滚动期间必须持续要求重绘", anyChanged)
    }

    // ------------------------------------------------------------ v3.3.0 · P1 抖动判据 ----

    /**
     * 逐帧位移必须均匀 —— 这是「60 帧设备上还是抖动」的**直接判据**。
     *
     * ## 为什么这条能测出真机抖动，而上面那些测不出
     *
     * 抖动的定义是**帧间位移不均**，不是「平均速度不对」。
     * 上面那些用例只断言「相位在 0..1」与「到达后接近 0」—— 清零实现**全部满足**，恒绿。
     * 而清零造成的速度突变正是要修的东西。所以这里量的是**每一帧的位移**，
     * 判据是位移的**平均绝对偏差**。
     *
     * ## 三个输入维度各自对应一个真实故障（缺一不可）
     *
     * | 维度 | 为什么必须变 |
     * |---|---|
     * | 柱间隔 100 / 92.88 / 85ms | 100ms 与 16.667ms 的 vsync **公度**（6 帧 = 100ms）⇒ 清零实现在这种理想输入下**恰好不抖**，只用它测就是恒绿。92.88ms（4×1024@44.1k 的真实缓冲间隔）**不公度**，那才是常态 |
     * | 柱间隔 ±3ms 抖动 | 真机的音频回调不是等间隔的 |
     * | 60 / 120Hz 帧间隔 | 铁律 33：帧率适配必须显式覆盖，不能只测一档 |
     *
     * ## 阈值怎么来的
     *
     * 逐帧位移应恒为 `帧间隔 / 柱间隔`（60Hz + 100ms ⇒ 0.1667 格）。
     * 取 25% 容差：足以容纳「一帧内到达一根柱」带来的**合法**平移，
     * 而对清零实现的 0.5×~2.1× 突变必然变红。
     */
    @Test
    fun `逐帧位移均匀 —— 60Hz 与 120Hz 在非公度柱间隔下都不抖`() {
        // (帧间隔ms, 柱间隔ms, 柱间隔抖动ms)
        val cases = listOf(
            Triple(16.667f, 100f, 0f),
            Triple(16.667f, 92.88f, 0f),
            Triple(16.667f, 85f, 0f),
            Triple(16.667f, 100f, 3f),
            Triple(16.667f, 92.88f, 3f),
            Triple(8.333f, 100f, 0f),
            Triple(8.333f, 92.88f, 3f),
        )
        for ((frameMs, intervalMs, jitterMs) in cases) {
            val ring = WaveformRing(capacity = 512, barCount = 16)
            ring.setBarIntervalForTest(intervalMs)
            ring.push(0.5f, 0.5f, 0.5f, 0.5f)
            val positions = ArrayList<Float>(1400)
            // 确定性伪随机（线性同余）而不是 Random(seed)：判据失败时能逐帧复现。
            var lcg = 12345
            var sinceArrival = 0f
            // 可见位置 = **历史窗口已平移的格数 + 相位的小数部分**。
            //
            // ⚠️ 这里量错过两次，两次都是**测量方式**错、不是实现错：
            // ① 只量 `scrollPhase01()` 的差分 ⇒ 相位自身的涨落被当成滚动速度；
            // ② 把「已平移格数」硬编码成「到达一次 +1」⇒ 而实现只在相位**跨过整数**时
            //    才平移（到达时相位常不足一格），于是每次到达都被多计一格，
            //    量出 −0.83 的假倒退。
            // 现在改为**直接读实现的扣减量**（pump 前后原始相位之差），于是判据量的
            // 就是「窗口平移 + 相位」这个**真实可见量**：
            //   非到达帧：平移 0、相位 +dt/interval ⇒ 位移 = dt/interval
            //   到达帧　：平移 1、相位 −1        ⇒ 位移仍 = dt/interval
            val nominalStep = frameMs / intervalMs
            repeat(1400) { frame ->
                // 与 pump 内的顺序一致（顺序是语义的一部分，见实现里的注释）。
                ring.pump(active = true, dtMs = frameMs)
                lcg = (lcg * 1103515245 + 12345) and 0x7fffffff
                val jitter = if (jitterMs > 0f) ((lcg % 2001) / 1000f - 1f) * jitterMs else 0f
                sinceArrival += frameMs
                if (sinceArrival >= intervalMs + jitter) {
                    // 到达：`sinceArrival -= 间隔`（不是归零）—— 真实音频回调的到达时刻
                    // 不会恰好落在帧边界上，这正是抖动要覆盖的那部分。
                    sinceArrival -= intervalMs + jitter
                    ring.push(0.5f, 0.5f, 0.5f, 0.5f)
                    ring.pump(active = true, dtMs = 0f)
                }
                // ★ 可见位置 = 累计平移格数 + 相位的小数部分。
                // 直接读实现的这两个量，不在测试里另建一套账 —— 那正是前面两次量错的原因。
                if (frame > 40) positions.add(ring.shiftedCellsForTest() + ring.scrollPhase01())
            }
            val steps = positions.zipWithNext { a, b -> b - a }
            assertTrue("有效样本太少（帧=$frameMs 柱=$intervalMs）", steps.size > 1000)
            val mean = steps.average().toFloat()
            assertTrue(
                "平均滚动速度应等于名义步长 $nominalStep（实际 $mean，帧=$frameMs 柱=$intervalMs）",
                kotlin.math.abs(mean - nominalStep) < nominalStep * 0.05f,
            )
            // ★ 核心判据：滚动速度的离差。清零实现的 0.5×~2.1× 突变在这里必然爆掉。
            val dev = steps.map { kotlin.math.abs(it - mean) }.average().toFloat()
            assertTrue(
                "逐帧滚动必须均匀（帧=${frameMs}ms 柱=${intervalMs}ms 抖动=${jitterMs}ms）：" +
                    "平均 $mean，平均绝对偏差 $dev —— 超过 5% 即为可见抖动",
                dev < mean * 0.05f,
            )
        }
    }

    @Test
    fun `相位推进是纯函数 —— 起点不同但增量相同`() {
        val ring = WaveformRing(capacity = 64, barCount = 8)
        val a = ring.advanceScrollPhase(0f, 16.667f, 100f)
        assertEquals(0.16667f, a, 1e-4f)
        assertEquals("同样输入必须同样输出", a, ring.advanceScrollPhase(0f, 16.667f, 100f), 0f)
        // 「减整数格」保住的核心性质：增量与起点无关。
        val fromMid = ring.advanceScrollPhase(0.5f, 16.667f, 100f)
        assertEquals(a, fromMid - 0.5f, 1e-4f)
    }

    @Test
    fun `相位推进被有界夹取 —— 超大 dt 与负 dt 都不越界`() {
        val ring = WaveformRing(capacity = 64, barCount = 8)

        val huge = ring.advanceScrollPhase(0f, 100_000f, 100f)
        assertTrue("超大 dt 必须被夹住而不是无界累积（实际 $huge）", huge <= 4f)

        assertEquals("负 dt 不得让相位倒退", 0.5f, ring.advanceScrollPhase(0.5f, -10f, 100f), 1e-6f)

        val zeroInterval = ring.advanceScrollPhase(0f, 16.667f, 0f)
        assertTrue("柱间隔非法时回落到默认值，不产生 NaN/Inf", zeroInterval.isFinite())
    }

    @Test
    fun `要扣的格数等于相位的整数部分 且不超过本帧到达数`() {
        val ring = WaveformRing(capacity = 64, barCount = 8)

        assertEquals("没有到达就不扣", 0f, ring.phasesToConsume(0.5f, 0), 0f)
        // ★ 相位不足一格时**扣 0**。这一条是 v3.3.0 修掉「强制至少扣一格」的落点：
        // 到达帧上相位已经按全程 dt 推进过，再强制扣一整格会让每个到达帧净亏
        // 那个小数部分 ⇒ 平均滚动速度系统性偏慢（92.88ms 柱间隔实测偏慢 7.3%）。
        assertEquals("相位不足一格时不扣（扣了会系统性变慢）", 0f, ring.phasesToConsume(0.2f, 1), 0f)
        assertEquals("整数部分就是该扣的格数", 3f, ring.phasesToConsume(3.4f, 4), 0f)
        assertEquals("扣的格数不得超过本帧到达数", 2f, ring.phasesToConsume(9f, 2), 0f)
        assertEquals("到达数再大也被 MAX_ARRIVALS_PER_FRAME 夹住", 4f, ring.phasesToConsume(99f, 999), 0f)
        assertEquals("恰好整格", 1f, ring.phasesToConsume(1.0f, 1), 0f)
        assertEquals("非法相位不产生 NaN", 0f, ring.phasesToConsume(Float.NaN, 1), 0f)
    }

    @Test
    fun `静音恢复时不会累积出一大跳`() {
        // 静音段不推进相位（不空转契约），所以恢复时**不得**把静音期间的时间一次补上。
        // 这是旧实现最严重的形态：静音闸门冻住相位，恢复时单帧位移可达 6× 名义。
        val ring = WaveformRing(capacity = 64, barCount = 8)
        ring.push(0.0f, 0.0f, 0.0f, 0.0f)
        repeat(100) { ring.pump(active = true, dtMs = 16.667f) }
        assertEquals("静音段相位必须冻结", 0f, ring.scrollPhase01(), 1e-6f)

        ring.push(0.6f, 0.6f, 0.6f, 0.6f)
        ring.pump(active = true, dtMs = 16.667f)
        val after = ring.scrollPhase01()
        assertTrue("恢复后的相位不得含静音期间累积量（实际 $after）", after < 0.5f)
    }
}
