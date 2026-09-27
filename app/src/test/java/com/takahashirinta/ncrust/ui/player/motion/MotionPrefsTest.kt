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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.9.0：统一「动效强度」的**读写 + v2.8.0 迁移 + 有界降级**（内存版 SharedPreferences）。
 *
 * 覆盖任务书 §2.1 / §3.2 / §8.2 要求的四件事：
 *  1. 缺 key 取（解析出来的）默认、非法值回落默认；
 *  2. **旧 key → 新档位**的搬运规则逐格正确（含「炫技档但炫技效果关着」这一格）；
 *  3. 迁移幂等，且**既有键一个不动**（回滚安装不丢数据）；
 *  4. 降级阶梯有界、用户手动改档位会重置水位。
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

    // ── 1. 键名是持久化契约 ─────────────────────────────────────────────────────────

    @Test
    fun `键名字面量被钉住`() {
        assertEquals("ncrust_settings", MotionPrefs.PREFS)
        assertEquals("motion_tier", MotionPrefs.KEY_TIER)
        assertEquals("ui_motion_enabled", MotionPrefs.KEY_UI_MOTION)
        assertEquals("motion_degrade_level", MotionPrefs.KEY_DEGRADE_LEVEL)
        assertEquals("motion_degrade_log", MotionPrefs.KEY_DEGRADE_LOG)
        assertEquals("motion_version", MotionPrefs.KEY_VERSION)
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
            MotionPrefs.KEY_VERSION,
        )
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
        assertEquals(modernDefault, MotionPrefs.readTier(prefs, modernDefault))
        assertTrue(MotionPrefs.readUiMotionEnabled(prefs))
        assertEquals(MotionDegrade.NONE, MotionPrefs.readDegradeLevel(prefs))
        assertEquals(MotionPrefs.VERSION_PRE_V290, MotionPrefs.readVersion(prefs))
    }

    // ── 3. 迁移（旧 key → 新档位）───────────────────────────────────────────────────

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
        // 这一格是本版迁移的**关键决定**：v2.8.0 的 C 档要求 tier==2 **且** showcase==true，
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
    fun `自动降级过的老盘从第一级界面动效降级起跑`() {
        assertEquals(
            MotionDegrade.UI_ADVANCED_OFF,
            MotionPrefs.migratedDegradeLevel(legacyAutoDowngraded = true),
        )
        assertEquals(MotionDegrade.NONE, MotionPrefs.migratedDegradeLevel(legacyAutoDowngraded = false))
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
    fun `全新安装不写任何键`() {
        val prefs = MemoryPrefs()
        assertTrue("水位要从 0 补到 1，所以会写一次", MotionPrefs.migrate(prefs, modernDefault))
        val keys = prefs.snapshot().keys
        assertEquals("只写水位一个键", setOf(MotionPrefs.KEY_VERSION), keys)
        assertFalse("绝不能凭空写入 motion_tier", prefs.contains(MotionPrefs.KEY_TIER))
    }

    @Test
    fun `低端机的老盘按设备默认档兜底越界值`() {
        val prefs = MemoryPrefs()
        prefs.putRaw(MotionPrefs.LegacyKeys.KEY_TIER, 7)
        MotionPrefs.migrate(prefs, lowEndDefault)
        assertEquals(MotionIntensity.SIMPLE, prefs.getInt(MotionPrefs.KEY_TIER, -1))
    }

    // ── 4. 写与降级 ────────────────────────────────────────────────────────────────

    @Test
    fun `用户改档位会重置降级水位`() {
        val prefs = MemoryPrefs()
        MotionPrefs.writeDegradeLevel(prefs, MotionDegrade.UI_ALL_OFF)
        MotionPrefs.writeTier(prefs, MotionIntensity.SHOWCASE)
        assertEquals(
            "用户显式改档位永远优先于自动降级",
            MotionDegrade.NONE,
            MotionPrefs.readDegradeLevel(prefs),
        )
        assertEquals(MotionIntensity.SHOWCASE, MotionPrefs.readTier(prefs, lowEndDefault))
    }

    @Test
    fun `界面动效总开关也重置降级水位`() {
        // v2.9.0 真机反馈后改：原本「只有改档位才重置」在 S6 上害了用户 ——
        // 阶梯推到第 2 级把 A 档也砍了、播放页变回老样子且永不恢复，而用户在设置里
        // 唯一的抓手就是这个开关：关掉再打开却发现什么都没回来。那不是"两个生命周期不同"，
        // 那是用户没有任何可发现的恢复路径。
        val prefs = MemoryPrefs()
        MotionPrefs.writeDegradeLevel(prefs, MotionDegrade.UI_ALL_OFF)
        MotionPrefs.writeUiMotionEnabled(prefs, false)
        assertEquals(
            "用户对本组动效设置的任何显式操作都必须重置自动降级的结论",
            MotionDegrade.NONE,
            MotionPrefs.readDegradeLevel(prefs),
        )
    }

    @Test
    fun `用户没选过档位时阶梯可以走到第三级并写回盘`() {
        // 「没选过」= 盘上没有 motion_tier（档位是设备判据解析出来的）。
        // 那时档位是**应用自己**定的，应用当然可以自己调整 —— 这才允许走到第 3 级。
        val prefs = MemoryPrefs()
        MotionPrefs.writeUiMotionEnabled(prefs, true)
        assertFalse(MotionPrefs.hasExplicitTier(prefs))

        assertEquals(MotionDegrade.UI_ADVANCED_OFF, MotionPrefs.applyAutoDowngrade(prefs, modernDefault))
        assertEquals("第 1 级只砍界面动效", MotionPrefs.readTier(prefs, modernDefault), modernDefault)

        assertEquals(MotionDegrade.UI_ALL_OFF, MotionPrefs.applyAutoDowngrade(prefs, modernDefault))
        assertEquals(MotionDegrade.WAVEFORM_DOWN, MotionPrefs.applyAutoDowngrade(prefs, modernDefault))
        assertEquals(
            "第 3 级才把档位降一级，且写回 motion_tier（设置页显示的必须是实际渲染的那一档）",
            MotionIntensity.SIMPLE,
            MotionPrefs.readTier(prefs, modernDefault),
        )
        assertNull("到顶之后永不动作", MotionPrefs.applyAutoDowngrade(prefs, modernDefault))
    }

    @Test
    fun `用户显式选过档位时阶梯止步于第一级且绝不动档位`() {
        // 真机现场（第三轮反馈）：用户把档位调到「炫技」，进了一次横屏之后
        // 冲击波 / 粒子 / 3D 在竖屏和横屏里都消失了 —— 那不是画不出来，
        // 是阶梯走到第 3 级把 motion_tier 从 2 改成了 1。判据本身不知道是谁把帧顶起来的，
        // 拿它覆盖用户的显式选择不是省电，是违约。
        val prefs = MemoryPrefs()
        MotionPrefs.writeTier(prefs, MotionIntensity.SHOWCASE)
        MotionPrefs.writeUiMotionEnabled(prefs, true)
        assertTrue(MotionPrefs.hasExplicitTier(prefs))

        assertEquals(MotionDegrade.UI_ADVANCED_OFF, MotionPrefs.applyAutoDowngrade(prefs, modernDefault, severe = true))
        assertNull(
            "显式选过档位 ⇒ 不再往下推",
            MotionPrefs.applyAutoDowngrade(prefs, modernDefault, severe = true),
        )
        assertEquals(
            "波形档位必须原封不动 —— 用户点的炫技效果一个都不能少",
            MotionIntensity.SHOWCASE,
            MotionPrefs.readTier(prefs, modernDefault),
        )
        assertTrue(
            "A 档也必须留着（背景模糊 + 呼吸）",
            MotionPrefs.readEffects(prefs, modernDefault).anyBasic,
        )
    }

    @Test
    fun `已经在简洁档时第三级不再越界`() {
        val prefs = MemoryPrefs()
        MotionPrefs.writeUiMotionEnabled(prefs, true)
        prefs.putRaw(MotionPrefs.KEY_TIER, MotionIntensity.SIMPLE)
        prefs.putRaw(MotionPrefs.KEY_VERSION, MotionPrefs.CURRENT_VERSION)
        prefs.putRaw(MotionPrefs.KEY_DEGRADE_LEVEL, MotionDegrade.NONE)
        // 波形已经在最低档 ⇒ 第 3 级是**空操作** ⇒ `nextEffective` 不推进水位
        // （水位必须始终等价于"实际生效的削减"，否则水位说降了、画面没变）。
        assertNull(MotionPrefs.applyAutoDowngrade(prefs, modernDefault))
        assertEquals(MotionIntensity.SIMPLE, MotionPrefs.readTier(prefs, modernDefault))
    }

    @Test
    fun `空操作的级别会被跳过而不是白占一格水位`() {
        // 简洁档 + 界面动效开着：B/C 档**本来就是关的** ⇒ 第 1 级是空操作。
        // 走"没选过档位"这条路（否则阶梯本来就封在第 1 级，看不到跳级行为）。
        val prefs = MemoryPrefs()
        MotionPrefs.writeUiMotionEnabled(prefs, true)
        prefs.putRaw(MotionPrefs.KEY_TIER, MotionIntensity.SIMPLE)
        prefs.putRaw(MotionPrefs.KEY_VERSION, MotionPrefs.CURRENT_VERSION)
        prefs.putRaw(MotionPrefs.KEY_DEGRADE_LEVEL, MotionDegrade.NONE)
        // 注意：putRaw 写进去之后 hasExplicitTier 就是 true 了 —— 所以这里直接验证纯函数，
        // 而不是走 applyAutoDowngrade（那条路要求"没选过"才放行第 2 级）。
        assertEquals(
            MotionDegrade.UI_ALL_OFF,
            MotionDegrade.nextEffective(
                current = MotionDegrade.NONE,
                advancedUiOn = false, // 简洁档：B/C 本来就是关的
                basicUiOn = true,
                waveformAboveFloor = false,
                maxLevel = MotionDegrade.MAX,
            ),
        )
        assertEquals(
            "第 3 级在波形已到最低档时也必须跳过",
            null,
            MotionDegrade.nextEffective(
                current = MotionDegrade.UI_ALL_OFF,
                advancedUiOn = true,
                basicUiOn = true,
                waveformAboveFloor = false,
                maxLevel = MotionDegrade.MAX,
            ),
        )
    }

    @Test
    fun `静态判据已在最低档的设备止步于第一级`() {
        // S6（API 24）实测退化：静态判据判低端 ⇒ 起始档 = 简洁 ⇒ 第 1 级是空操作、
        // 第 2 级会砍掉这台设备**唯一**的动效（A 档），而砍了也白砍（它本来就慢）。
        // 所以 `maxLevelFor(atFloorTier = true)` 把阶梯封在第一级。
        val prefs = MemoryPrefs()
        MotionPrefs.writeTier(prefs, MotionIntensity.SHOWCASE)
        MotionPrefs.writeUiMotionEnabled(prefs, true)
        assertEquals(
            MotionDegrade.UI_ADVANCED_OFF,
            MotionPrefs.applyAutoDowngrade(prefs, MotionIntensity.SIMPLE, severe = true),
        )
        assertNull(
            "封顶之后不再推进（第 2 级会砍掉低端设备唯一的动效）",
            MotionPrefs.applyAutoDowngrade(prefs, MotionIntensity.SIMPLE, severe = true),
        )
    }

    @Test
    fun `轻微超标只砍 B 档界面动效`() {
        // PCL110 实测判据是 24/60（正好卡在 40% 门槛）—— 一台 144Hz 旗舰"偶尔抖"，
        // 不该因此永久失去唯一看得见的界面动效。非严重超标封在第 1 级。
        assertFalse(MotionDegrade.isSevere(24, 60))
        assertTrue("刚好越线不算严重", MotionDegrade.isSevere(42, 60))
        assertTrue(MotionDegrade.isSevere(54, 60))
        val prefs = MemoryPrefs()
        MotionPrefs.writeTier(prefs, MotionIntensity.SHOWCASE)
        MotionPrefs.writeUiMotionEnabled(prefs, true)
        assertEquals(
            MotionDegrade.UI_ADVANCED_OFF,
            MotionPrefs.applyAutoDowngrade(prefs, modernDefault, severe = false),
        )
        assertNull(
            "轻微超标不得再往下推（第 2 级会砍掉 A 档）",
            MotionPrefs.applyAutoDowngrade(prefs, modernDefault, severe = false),
        )
        assertTrue("A 档必须留着", MotionPrefs.readEffects(prefs, modernDefault).anyBasic)
    }

    @Test
    fun `严重超标才允许推过第一级`() {
        // 没选过档位（设备判据解析）+ 严重超标 ⇒ 才允许走到第 2 级。
        val prefs = MemoryPrefs()
        MotionPrefs.writeUiMotionEnabled(prefs, true)
        assertEquals(
            MotionDegrade.UI_ADVANCED_OFF,
            MotionPrefs.applyAutoDowngrade(prefs, modernDefault, severe = true),
        )
        assertEquals(
            MotionDegrade.UI_ALL_OFF,
            MotionPrefs.applyAutoDowngrade(prefs, modernDefault, severe = true),
        )
    }

    @Test
    fun `v2 纠正迁移把过量的降级夹回来`() {
        // 真机现场：v1 的阶梯把水位推到 2（A 档被砍），而当时判据只是刚好越线。
        // 升级到 v2 必须把那台设备救回来，而不是让它永远停在"老 UI"。
        val prefs = MemoryPrefs()
        prefs.putRaw(MotionPrefs.KEY_VERSION, 1)
        prefs.putRaw(MotionPrefs.KEY_DEGRADE_LEVEL, MotionDegrade.UI_ALL_OFF)
        prefs.putRaw(MotionPrefs.KEY_TIER, MotionIntensity.SHOWCASE)
        prefs.putRaw(MotionPrefs.KEY_UI_MOTION, true)

        assertTrue(MotionPrefs.migrate(prefs, modernDefault))
        assertEquals(
            "非严重超标的上界是第 1 级",
            MotionDegrade.UI_ADVANCED_OFF,
            MotionPrefs.readDegradeLevel(prefs),
        )
        assertEquals("纠正动作必须留痕", MotionPrefs.CURRENT_VERSION, MotionPrefs.readVersion(prefs))
        val log = MotionPrefs.readDegradeLog(prefs)
        assertTrue("日志要写清把什么夹成了什么：$log", log.contains("v2-migration-clamp"))
    }

    @Test
    fun `纠正迁移不会把本来就在第一级的盘改坏`() {
        val prefs = MemoryPrefs()
        prefs.putRaw(MotionPrefs.KEY_VERSION, 1)
        prefs.putRaw(MotionPrefs.KEY_DEGRADE_LEVEL, MotionDegrade.UI_ADVANCED_OFF)
        MotionPrefs.migrate(prefs, modernDefault)
        assertEquals(MotionDegrade.UI_ADVANCED_OFF, MotionPrefs.readDegradeLevel(prefs))
        assertEquals("没有需要夹的东西就不该写日志", "", MotionPrefs.readDegradeLog(prefs))
    }

    @Test
    fun `降级会写一条可解释的日志`() {
        val prefs = MemoryPrefs()
        MotionPrefs.writeTier(prefs, MotionIntensity.SHOWCASE)
        MotionPrefs.writeUiMotionEnabled(prefs, true)
        MotionPrefs.applyAutoDowngrade(prefs, modernDefault, reason = "over-budget 54/60", severe = true)
        val log = MotionPrefs.readDegradeLog(prefs)
        assertTrue("日志必须写清级别：$log", log.contains("level=1"))
        assertTrue("日志必须写清判据：$log", log.contains("over-budget 54/60"))
        assertTrue("日志必须写清当时的档位：$log", log.contains("tier=2"))
    }

    @Test
    fun `降级日志有界且从头部丢最旧的整行`() {
        var log = ""
        for (i in 1..12) log = MotionPrefs.appendDegradeLog(log, "entry-$i")
        val lines = log.lines()
        assertEquals("只保留最后几条", MotionPrefs.DEGRADE_LOG_KEEP, lines.size)
        assertEquals("最新的那条必须在", "entry-12", lines.last())
        assertTrue("不能出现空行（最新一条被切一半的形态）", lines.none { it.isEmpty() })
        assertTrue(log.length <= MotionPrefs.DEGRADE_LOG_MAX_CHARS)
    }

    @Test
    fun `降级之后读出来的能力位立刻反映新水位`() {
        val prefs = MemoryPrefs()
        MotionPrefs.writeTier(prefs, MotionIntensity.SHOWCASE)
        val before = MotionPrefs.readEffects(prefs, modernDefault)
        assertTrue(before.anyShowcase)

        MotionPrefs.applyAutoDowngrade(prefs)
        val after = MotionPrefs.readEffects(prefs, modernDefault)
        assertFalse("第 1 级之后 B/C 档界面动效必须关掉", after.anyAdvanced || after.anyShowcase)
        assertTrue("A 档留着", after.anyBasic)
        assertNotEquals(before.degradeLevel, after.degradeLevel)
    }

    @Test
    fun `当前水位常量与阶梯上界一致`() {
        assertEquals(MotionDegrade.WAVEFORM_DOWN, MotionDegrade.MAX)
        // v1 = v2.9.0 首版；v2/v3 = 两轮真机反馈后的纠正迁移
        // （把过量的降级夹回新规则的上界）。
        assertEquals(3, MotionPrefs.CURRENT_VERSION)
        assertEquals(VisualizerTier.REFINED, MotionIntensity.DEFAULT)
    }
}
