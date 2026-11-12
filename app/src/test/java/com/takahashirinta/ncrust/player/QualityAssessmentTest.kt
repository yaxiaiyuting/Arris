package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A3 音质状态判定的回归测试。
 *
 * 阈值与场景都来自真机/接口实测（2026-09，登录态 + PC 身份）：
 * 母带 4.7–5.8 Mbps、jyeffect 2.8–3.1 Mbps、Hi-Res 1.69 Mbps、无损 0.87–0.92 Mbps，
 * 均为 flac；320 kbps 为 mp3。
 */
class QualityAssessmentTest {

    private val levels = QualityLadder.LEVELS
    private fun idx(level: String) = levels.indexOf(level)

    /** 场景 1：标签写 lossless，实际文件是 Hi-Res —— 不能报降级，且要显示为高解析。 */
    @Test
    fun `lossless label with hires bitrate is normal and shown as hires`() {
        val v = QualityAssessment.assess(
            requested = "hires", granted = "lossless",
            br = 1_685_762, type = "flac", songMaxLevel = "hires",
        )
        assertEquals(QualityStatus.NORMAL, v.status)
        assertEquals(idx("hires"), v.displayIndex)
    }

    /** 场景 2：该曲有无损，但账号只拿到 320k —— 提示「无权限」。 */
    @Test
    fun `exhigh with lossless capable song is no entitlement`() {
        val v = QualityAssessment.assess(
            requested = "lossless", granted = "exhigh",
            br = 320_000, type = "mp3", songMaxLevel = "lossless",
            access = QualityAccessEvidence(accountRevision = 1L, playableLevel = "exhigh"),
        )
        assertEquals(QualityStatus.NO_ENTITLEMENT, v.status)
    }

    /** 场景 3：正常无损，不加任何角标。 */
    @Test
    fun `plain lossless is normal`() {
        val v = QualityAssessment.assess(
            requested = "lossless", granted = "lossless",
            br = 920_600, type = "flac", songMaxLevel = "lossless",
        )
        assertEquals(QualityStatus.NORMAL, v.status)
        assertEquals(idx("lossless"), v.displayIndex)
    }

    /** 场景 4：请求母带但该曲最高只有 Hi-Res —— 提示「该曲无此档位」。 */
    @Test
    fun `jymaster request on hires only song is song lacks tier`() {
        val v = QualityAssessment.assess(
            requested = "jymaster", granted = "hires",
            br = 1_685_762, type = "flac", songMaxLevel = "hires",
        )
        assertEquals(QualityStatus.SONG_LACKS_TIER, v.status)
    }

    /**
     * 场景 5：请求杜比、实际给 jyeffect 的 2.8 Mbps FLAC —— 换格式不是降级。
     *
     * v2.1.4 的**关键取舍**在这里可见：展示档位按实测 br 算，
     * 而 2.8 Mbps 落在 `hires` 的量级锚点里（母带阈值 4 Mbps 是给 4.7–5.8 Mbps 的母带留的），
     * 所以同一个「jyeffect 文件」在不同曲目上会显示成 jyeffect 或 hires。
     * 状态判据（NORMAL）不受影响 —— 展示名字的精度上限就是 br 锚点，
     * 我们选择**宁可少写一档沉浸声的名字，也不把 2.8 Mbps 的文件说成母带**。
     * 那些确实 >= 4 Mbps 的沉浸声文件会走同一条规则显示成 jymaster，同样成立。
     */
    @Test
    fun `dolby request answered with immersive flac is normal`() {
        val v = QualityAssessment.assess(
            requested = "dolby", granted = "jyeffect",
            br = 2_798_469, type = "flac", songMaxLevel = "jymaster",
        )
        assertEquals(QualityStatus.NORMAL, v.status)
        assertEquals(idx("hires"), v.displayIndex)
    }

    /** 高码率的沉浸声文件按 br 锚点落在母带量级 —— 同样是 NORMAL，展示 jymaster。 */
    @Test
    fun `dolby request answered with 5Mbps immersive flac shows jymaster`() {
        val v = QualityAssessment.assess(
            requested = "dolby", granted = "jyeffect",
            br = 5_100_000, type = "flac", songMaxLevel = "jymaster",
        )
        assertEquals(QualityStatus.NORMAL, v.status)
        assertEquals(idx("jymaster"), v.displayIndex)
    }

    /** 回归：未知档位标签（如未来的 sky）不得退化成索引 0（旧逻辑会显示成「压缩」）。 */
    @Test
    fun `unknown granted label never falls back to index zero`() {
        val v = QualityAssessment.assess(
            requested = "hires", granted = "sky",
            br = 1_685_762, type = "flac", songMaxLevel = "hires",
        )
        assertEquals(QualityStatus.NORMAL, v.status)
        assertEquals(idx("hires"), v.displayIndex)
    }

    // ===== sky（沉浸环绕声）上限曲目：v1.3.0 实测 =====
    // 服务端没有独立的沉浸声音频文件，maxBrLevel=sky 的曲子最高源就是 exhigh 320k mp3
    // （level=sky 与 exhigh 逐字节同文件）。因此 sky 这个上限标签要归一化成 exhigh 再比较，
    // 否则 sky 不在档位表里 → capIdx=-1 → 落到兜底的「已降级」，把「该曲没有更高档位」
    // 误报成服务端降级。

    /** sky 上限 + 请求 exhigh 320k mp3：拿到该曲最高源，不加任何角标。 */
    @Test
    fun `sky capped song at exhigh request is normal`() {
        val v = QualityAssessment.assess(
            requested = "exhigh", granted = "exhigh",
            br = 320_000, type = "mp3", songMaxLevel = "sky",
        )
        assertEquals(QualityStatus.NORMAL, v.status)
        assertEquals(idx("exhigh"), v.displayIndex)
    }

    /** sky 上限 + 请求 hires：该曲根本没有更高档位，不是降级、更不是无权限。 */
    @Test
    fun `sky capped song at hires request lacks tier`() {
        val v = QualityAssessment.assess(
            requested = "hires", granted = "exhigh",
            br = 320_000, type = "mp3", songMaxLevel = "sky",
        )
        assertEquals(QualityStatus.SONG_LACKS_TIER, v.status)
        assertEquals(idx("exhigh"), v.displayIndex)
    }

    /** sky 上限 + 请求无损：同上，唯一正确语义是「该曲无此档位」。 */
    @Test
    fun `sky capped song at lossless request lacks tier`() {
        val v = QualityAssessment.assess(
            requested = "lossless", granted = "exhigh",
            br = 320_000, type = "mp3", songMaxLevel = "sky",
        )
        assertEquals(QualityStatus.SONG_LACKS_TIER, v.status)
    }

    /** 归一化只认「别名 = 更高档位的等价物」：sky 等价 exhigh，落到 exhigh 是正常，绝不报无权限。 */
    @Test
    fun `sky cap never reports no entitlement when granted matches its real ceiling`() {
        val v = QualityAssessment.assess(
            requested = "hires", granted = "exhigh",
            br = 320_000, type = "mp3", songMaxLevel = "sky",
        )
        assertNotEquals(QualityStatus.NO_ENTITLEMENT, v.status)
        assertNotEquals(QualityStatus.DOWNGRADED, v.status)
    }

    /** 曲库有无损，当前账号只能播到极高。 */
    @Test
    fun `hires capped song granted only exhigh is still no entitlement`() {
        val v = QualityAssessment.assess(
            requested = "lossless", granted = "exhigh",
            br = 320_000, type = "mp3", songMaxLevel = "hires",
            access = QualityAccessEvidence(accountRevision = 1L, playableLevel = "exhigh"),
        )
        assertEquals(QualityStatus.NO_ENTITLEMENT, v.status)
        assertEquals(idx("exhigh"), v.displayIndex)
    }

    /** 拿不到 br/type 时保持安静，不误报。 */
    @Test
    fun `missing file parameters stay normal`() {
        val v = QualityAssessment.assess(
            requested = "hires", granted = "hires",
            br = 0, type = "", songMaxLevel = null,
        )
        assertEquals(QualityStatus.NORMAL, v.status)
    }

    /** 实际文件与请求档位相符：不需要为判定再去问一次该曲的档位上限。 */
    @Test
    fun `capability not fetched when measured matches request`() {
        assertFalse(QualityAssessment.needsSongCapability("hires", 1_685_762, "flac"))
    }

    /** 实际文件低于请求档位：要问一次档位上限，才能区分「无权限」与「该曲无此档位」。 */
    @Test
    fun `capability fetched when measured below request`() {
        assertTrue(QualityAssessment.needsSongCapability("hires", 920_600, "flac"))
    }

    /** 沉浸声换格式：判定必然 NORMAL，不必多花一次请求。 */
    @Test
    fun `capability skipped for immersive substitution`() {
        assertFalse(QualityAssessment.needsSongCapability("dolby", 2_798_469, "flac"))
    }

    /** 沉浸声却只给到 320k：这是真降级，要问档位上限。 */
    @Test
    fun `capability fetched when immersive degrades to mp3`() {
        assertTrue(QualityAssessment.needsSongCapability("dolby", 320_000, "mp3"))
    }

    /** sky 上限的曲子只给到 320k：仍要问一次上限，判定才能落到「该曲无此档位」。 */
    @Test
    fun `capability fetched for sky capped song measured below request`() {
        assertTrue(QualityAssessment.needsSongCapability("hires", 320_000, "mp3"))
        assertTrue(QualityAssessment.needsSongCapability("lossless", 320_000, "mp3"))
    }

    /** 已拿到该曲最高源（320k mp3）而偏好就是 exhigh：无需再问上限。 */
    @Test
    fun `capability skipped when exhigh preference already served`() {
        assertFalse(QualityAssessment.needsSongCapability("exhigh", 320_000, "mp3"))
    }

    /** 请求母带、实际给 320k（该曲其实有母带）—— 属于无权限。 */
    @Test
    fun `jymaster request answered with mp3 is no entitlement`() {
        val v = QualityAssessment.assess(
            requested = "jymaster", granted = "exhigh",
            br = 320_000, type = "mp3", songMaxLevel = "jymaster",
            access = QualityAccessEvidence(accountRevision = 1L, playableLevel = "exhigh"),
        )
        assertEquals(QualityStatus.NO_ENTITLEMENT, v.status)
    }

    // ===== v2.1.4：展示档位必须以**实测文件**为准，标签不许把它抬上去 =====
    // 起因是真机实测到的用户报障形态：账号权益只到 HQ（服务端 music_lev_sq=0），
    // 选「超清母带」被如实降级到 320k，而界面仍然挂着「超清母带」——
    // 用户看到的名字与实际听到的东西不符，只能得出「开了母带和免费用户没区别」。

    /** 标签谎报母带、文件是 320k mp3：展示必须是极高，且要报「无权限」。 */
    @Test
    fun `lying jymaster label with 320k mp3 shows exhigh not jymaster`() {
        val v = QualityAssessment.assess(
            requested = "jymaster", granted = "jymaster",
            br = 320_000, type = "mp3", songMaxLevel = "jymaster",
            access = QualityAccessEvidence(accountRevision = 1L, playableLevel = "exhigh"),
        )
        assertEquals(idx("exhigh"), v.displayIndex)
        assertEquals(QualityStatus.NO_ENTITLEMENT, v.status)
    }

    /** 标签谎报母带、文件是无损 flac：展示无损，不能报母带。 */
    @Test
    fun `lying jymaster label with lossless flac shows lossless`() {
        val v = QualityAssessment.assess(
            requested = "jymaster", granted = "jymaster",
            br = 900_000, type = "flac", songMaxLevel = "jymaster",
        )
        assertEquals(idx("lossless"), v.displayIndex)
    }

    /** 反向仍成立：标签写 lossless、文件其实是 Hi-Res ⇒ 按实测显示 Hi-Res（不许写低）。 */
    @Test
    fun `understated lossless label with hires flac still shows hires`() {
        val v = QualityAssessment.assess(
            requested = "hires", granted = "lossless",
            br = 1_685_762, type = "flac", songMaxLevel = "hires",
        )
        assertEquals(idx("hires"), v.displayIndex)
    }

    // ------------------------------------------------------------------
    // v2.2.1 · P0：QQ 那条路拿不到 br（FLAC 档没有码率字段），但它的档位是**从真正
    // 取回的文件名前缀反推**的 —— 那本身就是证据。以前这类情况一律「保持安静」，
    // 于是「请求超清母带、实际 Hi-Res」在界面上既不是降级也没有任何提示。
    // ------------------------------------------------------------------

    /** 降级不得回显虚高：请求母带、QQ 只给 RS01 ⇒ 显示 hires（不是 jymaster），并标注已降级。 */
    @Test
    fun `qq trusted level downgrade shows the actual level and flags it`() {
        val v = QualityAssessment.assess(
            requested = "jymaster", granted = "hires",
            br = 0L, type = "flac", songMaxLevel = null,
            levelFromFile = true,
        )
        assertEquals(idx("hires"), v.displayIndex)
        assertEquals(QualityStatus.DOWNGRADED, v.status)
    }

    /** 反过来：档位是服务端**标签**（QQ 之外那条路）时不许下结论，行为与 v2.1.4 逐字一致。 */
    @Test
    fun `untrusted level stays silent when file params are unknown`() {
        val v = QualityAssessment.assess(
            requested = "jymaster", granted = "hires",
            br = 0L, type = "", songMaxLevel = null,
            levelFromFile = false,
        )
        assertEquals(idx("hires"), v.displayIndex)
        assertEquals(QualityStatus.NORMAL, v.status)
    }

    /** 档位是实测反推的、且不低于请求 ⇒ 仍然 NORMAL（不能把正常播放误报成降级）。 */
    @Test
    fun `trusted level at or above request is normal`() {
        val v = QualityAssessment.assess(
            requested = "hires", granted = "hires",
            br = 0L, type = "flac", songMaxLevel = null,
            levelFromFile = true,
        )
        assertEquals(idx("hires"), v.displayIndex)
        assertEquals(QualityStatus.NORMAL, v.status)
    }

    /** 有实测参数时，实测优先的老规则一字未动。 */
    @Test
    fun `measured params still win over trusted level string`() {
        val v = QualityAssessment.assess(
            requested = "jymaster", granted = "standard",
            br = 5_000_000L, type = "flac", songMaxLevel = null,
            levelFromFile = true,
        )
        assertEquals(idx("jymaster"), v.displayIndex)
    }
    @Test
    fun `one song can have a forbidden tier and lack a different tier`() {
        val account = QualityAccessEvidence(accountRevision = 1L, playableLevel = "exhigh")
        assertEquals(QualityStatus.NO_ENTITLEMENT, QualityAssessment.choiceStatus("lossless", "hires", account))
        assertEquals(QualityStatus.SONG_LACKS_TIER, QualityAssessment.choiceStatus("jymaster", "hires", account))
        assertEquals(QualityStatus.NORMAL, QualityAssessment.choiceStatus("exhigh", "hires", account))
    }

    @Test
    fun `a downgrade without account evidence stays a downgrade`() {
        val verdict = QualityAssessment.assess("lossless", "exhigh", 320_000L, "mp3", "hires")
        assertEquals(QualityStatus.DOWNGRADED, verdict.status)
        assertEquals(QualityStatus.NORMAL, QualityAssessment.choiceStatus("lossless", "hires", null))
    }

    @Test
    fun `observed rights rejection does not override known missing tier or a verified file`() {
        val account = QualityAccessEvidence(1L, observed = mapOf("hires" to QualityStatus.NO_ENTITLEMENT))
        assertEquals(QualityStatus.NO_ENTITLEMENT, QualityAssessment.choiceStatus("hires", null, account))
        assertEquals(QualityStatus.SONG_LACKS_TIER, QualityAssessment.choiceStatus("hires", "lossless", account))
        assertEquals(QualityStatus.NORMAL, QualityAssessment.choiceStatus("hires", "lossless", account, "hires"))
    }

}
