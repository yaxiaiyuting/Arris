/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player.motion

import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerPrefs
import com.takahashirinta.ncrust.ui.player.waveform.VisualizerTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.0：统一「动效强度」的**读写 + v2.8.0 迁移**（内存版 SharedPreferences）。
 * v3.0.0：新增五个独立开关的读写，并**验证自动降级机制真的不存在了**。
 *
 * 覆盖：
 *  1. 键名是持久化契约（逐字钉住 10 个键）；
 *  2. 缺 key 取（解析出来的）默认、非法值回落默认、脏类型不抛异常；
 *  3. **旧 key → 新档位**的搬运规则逐格正确（含「炫技档但炫技效果关着」这一格）；
 *  4. 迁移幂等，且**既有键一个不动**（回滚安装不丢数据）；
 *  5. v3.0.0 的五个开关：缺 key = 开，显式关掉会被尊重；
 *  6. **没有自动降级**：公开 API 里不存在任何"推进水位"的入口。
 */
class MotionPrefsTest {

    /** 高端机的解析默认档（精致）。 */
    private val modernDefault = MotionIntensity.REFINED

    /** 3GB 级老机的解析默认档（简洁）。 */
    private val lowEndDefault = MotionIntensity.defaultFor(
        isLowRamDevice = false,
        totalMemBytes = 3L * 1024 * 1024 * 1024,
        sdkInt = 34,
    )

    /** 盘上写着「水位 = v2.9.0」的老盘（= 已经搬过 v2.8.0 那一轮）。 */
    private fun v290Disk(): MemoryPrefs = MemoryPrefs().apply {
        putRaw(MotionPrefs.KEY_VERSION, MotionPrefs.VERSION_V290)
    }

    // ── 1. 键名是持久化契约 ─────────────────────────────────────────────────────────

    @Test
    fun `键名字面量被钉住`() {
        assertEquals("ncrust_settings", MotionPrefs.PREFS)
        assertEquals("motion_tier", MotionPrefs.KEY_TIER)
        assertEquals("ui_motion_enabled", MotionPrefs.KEY_UI_MOTION)
        assertEquals("motion_degrade_level", MotionPrefs.KEY_DEGRADE_LEVEL)
        assertEquals("motion_degrade_log", MotionPrefs.KEY_DEGRADE_LOG)
        assertEquals("motion_version", MotionPrefs.KEY_VERSION)
        // v3.0.0：五个独立开关
        assertEquals("motion_shockwave", MotionPrefs.KEY_SHOCKWAVE)
        assertEquals("motion_halo", MotionPrefs.KEY_HALO)
        assertEquals("motion_particles", MotionPrefs.KEY_PARTICLES)
        assertEquals("motion_wave_bands", MotionPrefs.KEY_WAVE_BANDS)
        assertEquals("motion_breathing", MotionPrefs.KEY_BREATHING)
    }

    @Test
    fun `迁移读的旧键名与 VisualizerPrefs 逐字一致`() {
        // 迁移是一份历史契约：改名等于静默把老用户的档位丢掉。两边各声明一次是有意的
        // （历史键名与历史键名放在一起），这条断言保证它们不会分叉。
        assertEquals(VisualizerPrefs.KEY_TIER, MotionPrefs.LegacyKeys.KEY_TIER)
        assertEquals(VisualizerPrefs.KEY_SHOWCASE, MotionPrefs.LegacyKeys.KEY_SHOWCASE)
        assertEquals(
            VisualizerPrefs.KEY_AUTO_DOWNGRADED,
            MotionPrefs.LegacyKeys.KEY_AUTO_DOWNGRADED,
        )
    }

    @Test
    fun `新键与 v2_8_0 的旧键不重名`() {
        val fresh = setOf(
            MotionPrefs.KEY_TIER,
            MotionPrefs.KEY_UI_MOTION,
            MotionPrefs.KEY_DEGRADE_LEVEL,
            MotionPrefs.KEY_DEGRADE_LOG,
            MotionPrefs.KEY_VERSION,
            MotionPrefs.KEY_SHOCKWAVE,
            MotionPrefs.KEY_HALO,
            MotionPrefs.KEY_PARTICLES,
            MotionPrefs.KEY_WAVE_BANDS,
            MotionPrefs.KEY_BREATHING,
        )
        assertEquals("必须正好 10 个键（多一个就是夹带）", 10, fresh.size)
        val legacy = setOf(
            VisualizerPrefs.KEY_TIER,
            VisualizerPrefs.KEY_SHOWCASE,
            VisualizerPrefs.KEY_SHOCKWAVE,
            VisualizerPrefs.KEY_PARTICLES,
            VisualizerPrefs.KEY_PERSPECTIVE,
            VisualizerPrefs.KEY_DRAG,
            VisualizerPrefs.KEY_AUTO_DOWNGRADED,
            VisualizerPrefs.KEY_TIER_VERSION,
        )
        assertTrue("新旧键不得重名：${fresh intersect legacy}", (fresh intersect legacy).isEmpty())
    }

    // ── 2. 纯读 ────────────────────────────────────────────────────────────────────

    @Test
    fun `缺 key 时用解析出来的设备默认档`() {
        val prefs = MemoryPrefs()
        assertEquals(lowEndDefault, MotionPrefs.readTier(prefs, lowEndDefault))
        assertEquals(modernDefault, MotionPrefs.readTier(prefs, modernDefault))
        assertEquals(MotionIntensity.SIMPLE, lowEndDefault)
        assertEquals(MotionIntensity.REFINED, modernDefault)
        assertFalse("缺 key 不等于用户选过", MotionPrefs.hasExplicitTier(prefs))
        assertTrue("界面动效默认开", MotionPrefs.readUiMotionEnabled(prefs))
        assertEquals(MotionDegrade.NONE, MotionPrefs.readDegradeLevel(prefs))
        assertEquals("缺 key = 未迁移（不是「搬到 0」）", MotionPrefs.VERSION_PRE_V290, MotionPrefs.readVersion(prefs))
    }

    @Test
    fun `缺 key 绝不写回盘`() {
        val prefs = MemoryPrefs()
        MotionPrefs.readTier(prefs, modernDefault)
        MotionPrefs.readUiMotionEnabled(prefs)
        MotionPrefs.readSwitches(prefs)
        assertTrue("读路径不得有任何写盘", prefs.snapshot().isEmpty())
    }

    @Test
    fun `显式选择优先于设备默认档`() {
        val prefs = MemoryPrefs()
        MotionPrefs.writeTier(prefs, MotionIntensity.SIMPLE)
        assertTrue(MotionPrefs.hasExplicitTier(prefs))
        assertEquals(
            "低端机上也必须尊重用户显式选的精致档",
            MotionIntensity.SIMPLE,
            MotionPrefs.readTier(prefs, modernDefault),
        )
    }

    @Test
    fun `越界档位回落调用方给的默认档`() {
        for (bad in listOf(-1, 3, 99)) {
            val prefs = MemoryPrefs()
            prefs.putRaw(MotionPrefs.KEY_TIER, bad)
            assertEquals("坏值 $bad 必须回落低端默认", lowEndDefault, MotionPrefs.readTier(prefs, lowEndDefault))
            assertEquals("坏值 $bad 必须回落高端默认", modernDefault, MotionPrefs.readTier(prefs, modernDefault))
        }
    }

    @Test
    fun `类型不符的脏数据不抛异常`() {
        val prefs = MemoryPrefs()
        prefs.putRaw(MotionPrefs.KEY_TIER, "2")
        prefs.putRaw(MotionPrefs.KEY_UI_MOTION, 1)
        prefs.putRaw(MotionPrefs.KEY_DEGRADE_LEVEL, "x")
        prefs.putRaw(MotionPrefs.KEY_VERSION, "v1")
        prefs.putRaw(MotionPrefs.KEY_SHOCKWAVE, "true")
        prefs.putRaw(MotionPrefs.KEY_HALO, 7)
        assertEquals(modernDefault, MotionPrefs.readTier(prefs, modernDefault))
        assertTrue(MotionPrefs.readUiMotionEnabled(prefs))
        assertEquals(MotionDegrade.NONE, MotionPrefs.readDegradeLevel(prefs))
        assertEquals(MotionPrefs.VERSION_PRE_V290, MotionPrefs.readVersion(prefs))
        val switches = MotionPrefs.readSwitches(prefs)
        assertTrue("脏类型必须回落到「开」，不是「关」", switches.shockwave && switches.halo)
    }

    // ── 3. v3.0.0：五个独立开关 ─────────────────────────────────────────────────────

    @Test
    fun `缺 key 时五个开关都是开的`() {
        val prefs = MemoryPrefs()
        val s = MotionPrefs.readSwitches(prefs)
        assertTrue(s.shockwave && s.halo && s.particles && s.waveBands && s.breathing)
        assertTrue("默认值常量", MotionPrefs.DEFAULT_SWITCH)
    }

    @Test
    fun `显式关掉的开关会被读回来`() {
        val prefs = MemoryPrefs()
        MotionPrefs.writeSwitch(prefs, MotionPrefs.KEY_SHOCKWAVE, false)
        MotionPrefs.writeSwitch(prefs, MotionPrefs.KEY_WAVE_BANDS, false)
        val s = MotionPrefs.readSwitches(prefs)
        assertFalse(s.shockwave)
        assertFalse(s.waveBands)
        assertTrue("没动过的仍然是开", s.halo && s.particles && s.breathing)
        assertTrue("halo / particles / breathing 仍然开着 ⇒ 还有音频绑定", s.anyBinding)
    }

    @Test
    fun `readEffects 把开关一起读进能力位`() {
        val prefs = MemoryPrefs()
        prefs.putRaw(MotionPrefs.KEY_TIER, MotionIntensity.SHOWCASE)
        MotionPrefs.writeSwitch(prefs, MotionPrefs.KEY_HALO, false)
        val e = MotionPrefs.readEffects(prefs, modernDefault)
        assertEquals(MotionIntensity.SHOWCASE, e.tier)
        assertFalse("关掉的项不许出现在能力位里", e.haloBloom)
        assertTrue(e.shockwave && e.particles)
    }

    // ── 4. 迁移（v2.8.0 档位 → 统一档位）────────────────────────────────────────────

    @Test
    fun `有效的炫技档原样搬过来`() {
        assertEquals(
            MotionIntensity.SHOWCASE,
            MotionPrefs.migratedTier(
                legacyTier = MotionIntensity.SHOWCASE,
                legacyShowcase = true,
                deviceDefault = modernDefault,
            ),
        )
    }

    @Test
    fun `炫技档但炫技效果关着时搬到精致档`() {
        // 这一格是 v2.9.0 迁移的**关键决定**：v2.8.0 的 C 档要求 tier==2 **且** showcase==true，
        // 而 showcase 默认 false ⇒ 这些老盘在 v2.8.0 下渲染出来就是精致档。
        // 照抄 2 会让他们突然多出冲击波与粒子 —— 那是语义变化，不是"保留用户选择"。
        assertEquals(
            MotionIntensity.REFINED,
            MotionPrefs.migratedTier(
                legacyTier = MotionIntensity.SHOWCASE,
                legacyShowcase = false,
                deviceDefault = modernDefault,
            ),
        )
    }

    @Test
    fun `简洁与精致档不受炫技开关影响`() {
        for (tier in listOf(MotionIntensity.SIMPLE, MotionIntensity.REFINED)) {
            for (showcase in listOf(true, false)) {
                assertEquals(
                    "tier=$tier showcase=$showcase",
                    tier,
                    MotionPrefs.migratedTier(tier, showcase, modernDefault),
                )
            }
        }
    }

    @Test
    fun `没选过档位的老盘不写 motion_tier`() {
        assertNull(
            "旧键缺失 ⇒ 返回 null（= 不要写盘），让新版本继续用解析默认档",
            MotionPrefs.migratedTier(legacyTier = null, legacyShowcase = false, deviceDefault = modernDefault),
        )
    }

    @Test
    fun `迁移把老盘搬成新键并留下水位`() {
        val prefs = MemoryPrefs()
        prefs.putRaw(MotionPrefs.LegacyKeys.KEY_TIER, MotionIntensity.REFINED)
        prefs.putRaw(MotionPrefs.LegacyKeys.KEY_SHOWCASE, true)
        val before = prefs.snapshot()

        assertTrue(MotionPrefs.migrate(prefs, modernDefault))
        assertEquals(MotionIntensity.REFINED, prefs.getInt(MotionPrefs.KEY_TIER, -1))
        assertEquals(MotionPrefs.CURRENT_VERSION, prefs.getInt(MotionPrefs.KEY_VERSION, -1))
        // 旧键一个不动：回滚安装 v2.8.0 时用户的选择还在。
        assertEquals(before[MotionPrefs.LegacyKeys.KEY_TIER], prefs.snapshot()[MotionPrefs.LegacyKeys.KEY_TIER])
        assertEquals(before[MotionPrefs.LegacyKeys.KEY_SHOWCASE], prefs.snapshot()[MotionPrefs.LegacyKeys.KEY_SHOWCASE])
    }

    @Test
    fun `迁移是幂等的且第二次不写盘`() {
        val prefs = MemoryPrefs()
        prefs.putRaw(MotionPrefs.LegacyKeys.KEY_TIER, MotionIntensity.SIMPLE)
        prefs.putRaw(MotionPrefs.LegacyKeys.KEY_AUTO_DOWNGRADED, true)

        assertTrue("第一次必须写盘", MotionPrefs.migrate(prefs, modernDefault))
        val afterFirst = prefs.snapshot()
        assertFalse("第二次必须是严格 no-op", MotionPrefs.migrate(prefs, modernDefault))
        assertEquals("盘必须逐键不变", afterFirst, prefs.snapshot())
    }

    @Test
    fun `迁移不碰任何无关键`() {
        val prefs = MemoryPrefs()
        val unrelated = mapOf(
            "audio_visualizer" to true,
            "wifi_quality" to 3,
            "lyrics_ttml_enabled" to false,
            "theme_mode" to "DARK",
            "visualizer_tier_version" to 1,
        )
        unrelated.forEach { (k, v) -> prefs.putRaw(k, v) }
        MotionPrefs.migrate(prefs, modernDefault)
        unrelated.forEach { (k, v) -> assertEquals("无关键 $k 被改了", v, prefs.snapshot()[k]) }
    }

    @Test
    fun `全新安装只写水位一个键`() {
        val prefs = MemoryPrefs()
        assertTrue("水位要从 0 补到当前值，所以会写一次", MotionPrefs.migrate(prefs, modernDefault))
        val keys = prefs.snapshot().keys
        assertEquals("只写水位一个键（五个开关刻意不写：缺 key = 开）", setOf(MotionPrefs.KEY_VERSION), keys)
        assertFalse("绝不能凭空写入 motion_tier", prefs.contains(MotionPrefs.KEY_TIER))
        assertFalse("也绝不能凭空写入任何开关", prefs.contains(MotionPrefs.KEY_SHOCKWAVE))
    }

    @Test
    fun `低端机的老盘按设备默认档兜底越界值`() {
        val prefs = MemoryPrefs()
        prefs.putRaw(MotionPrefs.LegacyKeys.KEY_TIER, 7)
        MotionPrefs.migrate(prefs, lowEndDefault)
        assertEquals(MotionIntensity.SIMPLE, prefs.getInt(MotionPrefs.KEY_TIER, -1))
    }

    // ── 5. v3.0.0 的纠正迁移：自动降级被删除 ────────────────────────────────────────

    @Test
    fun `v2_9_0 的水位是 3 而当前是 4`() {
        assertEquals("加语义必须 +1 并在 migrate 里补一段逻辑", 4, MotionPrefs.CURRENT_VERSION)
        assertEquals(3, MotionPrefs.VERSION_V290)
        assertEquals(0, MotionPrefs.VERSION_PRE_V290)
    }

    @Test
    fun `从没被降级过的盘迁移时不写日志`() {
        // 没被降级过 = 用户不会看到任何观感变化 ⇒ 不该给他塞一条噪音日志。
        val prefs = v290Disk()
        assertTrue(MotionPrefs.migrate(prefs, modernDefault))
        assertEquals(MotionPrefs.CURRENT_VERSION, prefs.getInt(MotionPrefs.KEY_VERSION, -1))
        assertFalse("不该写日志", prefs.contains(MotionPrefs.KEY_DEGRADE_LOG))
    }

    @Test
    fun `被降级过的盘迁移时补一条可读的说明`() {
        // 这些用户会看到画面**多出**动效（降级不再生效），日志是唯一的解释来源。
        val prefs = v290Disk()
        prefs.putRaw(MotionPrefs.KEY_DEGRADE_LEVEL, MotionDegrade.UI_ALL_OFF)
        assertTrue(MotionPrefs.migrate(prefs, modernDefault))
        val log = MotionPrefs.readDegradeLog(prefs)
        assertTrue("必须留下说明：$log", log.contains("v3-auto-degrade-removed"))
        assertTrue("要写清旧水位是什么：$log", log.contains("ui-all-off"))
        // 历史水位**原样保留**（不删不改：回滚安装不丢数据）。
        assertEquals(MotionDegrade.UI_ALL_OFF, MotionPrefs.readDegradeLevel(prefs))
    }

    @Test
    fun `v2_8_0 的降级标记也会触发说明`() {
        val prefs = MemoryPrefs()
        prefs.putRaw(MotionPrefs.LegacyKeys.KEY_AUTO_DOWNGRADED, true)
        assertTrue(MotionPrefs.migrate(prefs, modernDefault))
        assertTrue(MotionPrefs.readDegradeLog(prefs).contains("v3-auto-degrade-removed"))
        // 旧标记为 true 而盘上还没有水位键 ⇒ 补写历史水位 1（v2.8.0 的语义）。
        assertEquals(MotionDegrade.UI_ADVANCED_OFF, MotionPrefs.readDegradeLevel(prefs))
    }

    @Test
    fun `已经有水位的盘不被迁移覆盖`() {
        val prefs = v290Disk()
        prefs.putRaw(MotionPrefs.LegacyKeys.KEY_AUTO_DOWNGRADED, true)
        prefs.putRaw(MotionPrefs.KEY_DEGRADE_LEVEL, MotionDegrade.WAVEFORM_DOWN)
        MotionPrefs.migrate(prefs, modernDefault)
        assertEquals(
            "真实历史不能被 v2.8.0 的标记覆盖",
            MotionDegrade.WAVEFORM_DOWN,
            MotionPrefs.readDegradeLevel(prefs),
        )
    }

    // ── 6. 写与「没有自动降级」的结构保证 ───────────────────────────────────────────

    @Test
    fun `用户改档位不再触碰任何降级键`() {
        // v3.0.0：机制整个删除 ⇒ 改档位只写 motion_tier 一个键。
        val prefs = MemoryPrefs()
        prefs.putRaw(MotionPrefs.KEY_DEGRADE_LEVEL, MotionDegrade.UI_ALL_OFF)
        MotionPrefs.writeTier(prefs, MotionIntensity.SHOWCASE)
        assertEquals(MotionIntensity.SHOWCASE, MotionPrefs.readTier(prefs, lowEndDefault))
        assertEquals(
            "历史水位保持原样（不再被「重置」——那个机制没了）",
            MotionDegrade.UI_ALL_OFF,
            MotionPrefs.readDegradeLevel(prefs),
        )
    }

    @Test
    fun `改总开关也只写一个键`() {
        val prefs = MemoryPrefs()
        MotionPrefs.writeUiMotionEnabled(prefs, false)
        assertFalse(MotionPrefs.readUiMotionEnabled(prefs))
        assertEquals(setOf(MotionPrefs.KEY_UI_MOTION), prefs.snapshot().keys)
    }

    /**
     * **结构性保证**：`MotionPrefs` 上不存在任何"推进降级水位"的入口。
     *
     * 用反射而不是注释：自动降级被删除之后，谁把它加回来（哪怕只是加一个方法），
     * 这条会立刻变红。它比"文档里写着不做"强得多 —— 文档不会让构建失败。
     */
    @Test
    fun `不存在任何推进降级水位的公开入口`() {
        val names = MotionPrefs::class.java.declaredMethods.map { it.name }
        val forbidden = names.filter { name ->
            // 只禁「推进/写水位」这一类的名字。**读**历史值的入口（readDegradeLevel /
            // readDegradeLog）与纯日志工具（appendDegradeLog / degradeLogEntry）必须保留 ——
            // 迁移说明与诊断还用得上。
            listOf("applyautodowngrade", "downgrade", "hasdecided", "writedegradelevel", "applydegrade")
                .any { name.lowercase().contains(it) }
        }
        assertTrue(
            "自动降级机制已取消，不该再有这些入口：$forbidden",
            forbidden.isEmpty(),
        )
        // 但**读**历史值的入口必须在（迁移与诊断要用）。
        assertNotNull(MotionPrefs::class.java.declaredMethods.firstOrNull { it.name == "readDegradeLevel" })
    }

    @Test
    fun `降级日志有界且从头部丢最旧的整行`() {
        var log = ""
        repeat(20) { i ->
            log = MotionPrefs.appendDegradeLog(log, MotionPrefs.degradeLogEntry(i, 1, true, "test-$i"))
        }
        val lines = log.lines().filter { it.isNotBlank() }
        assertTrue("最多保留 ${MotionPrefs.DEGRADE_LOG_KEEP} 条（实际 ${lines.size}）", lines.size <= MotionPrefs.DEGRADE_LOG_KEEP)
        assertTrue("最新一条必须完整", lines.last().endsWith("why=test-19"))
        assertTrue("总长必须有界", log.length <= MotionPrefs.DEGRADE_LOG_MAX_CHARS + 120)
    }

    @Test
    fun `降级日志只有一条时绝不截断`() {
        // 半条日志比没有更糟：只剩一行时宁可略超长，也不把它切一半。
        val long = "x".repeat(MotionPrefs.DEGRADE_LOG_MAX_CHARS + 50)
        val log = MotionPrefs.appendDegradeLog("", long)
        assertEquals(long, log)
    }

    // ── 7. 设备判据只决定**初始**档 ─────────────────────────────────────────────────

    @Test
    fun `设备判据只决定初始档且不写盘`() {
        val prefs = MemoryPrefs()
        val initial = MotionPrefs.readTier(prefs, lowEndDefault)
        assertEquals("低端机的初始档是简洁", VisualizerTier.SIMPLE, initial)
        assertTrue("读一次不许写盘", prefs.snapshot().isEmpty())
        // 用户一旦显式选过，设备判据就再也不参与。
        MotionPrefs.writeTier(prefs, VisualizerTier.SHOWCASE)
        assertEquals(
            "设备判据不得覆盖用户选择",
            VisualizerTier.SHOWCASE,
            MotionPrefs.readTier(prefs, lowEndDefault),
        )
    }

    @Test
    fun `设备判据的三条输入仍然是 v2_8_0 那三条`() {
        // 低内存 / 3.5GiB 级 / API<26，取并集 —— 与 v2.8.0 逐字同源。
        assertEquals(MotionIntensity.SIMPLE, MotionIntensity.defaultFor(true, 8L * 1024 * 1024 * 1024, 34))
        assertEquals(MotionIntensity.SIMPLE, MotionIntensity.defaultFor(false, 3L * 1024 * 1024 * 1024, 34))
        assertEquals(MotionIntensity.SIMPLE, MotionIntensity.defaultFor(false, 8L * 1024 * 1024 * 1024, 24))
        assertEquals(MotionIntensity.REFINED, MotionIntensity.defaultFor(false, 8L * 1024 * 1024 * 1024, 34))
        // 取不到内存信息（0）视为未知 ⇒ 给默认档，而不是把所有设备锁进简洁档。
        assertEquals(MotionIntensity.REFINED, MotionIntensity.defaultFor(false, 0L, 34))
    }
}
