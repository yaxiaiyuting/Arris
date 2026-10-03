package com.takahashirinta.ncrust.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.1.0 · B：QQ 音质档位链单测。
 *
 * 这一层看起来只是「一张表」，但它决定了三件事：用户选无损时能不能拿到无损、
 * 会员档拿不到时会不会退到能放的那一档、以及文件名拼得对不对。
 * 尤其是「**每一条链都必须收敛到 M500**」—— 少一档的后果是整首歌放不出来。
 */
class QqQualityTest {

    @Test
    fun `每一条档位链都收敛到 128k mp3`() {
        for (level in listOf(
            "standard", "higher", "exhigh", "lossless", "hires",
            "jyeffect", "jymaster", "dolby", "未知档位", "",
        )) {
            val attempts = QqQuality.attemptsFor(level)
            assertTrue("$level 的链不能为空", attempts.isNotEmpty())
            assertEquals("$level 的链尾必须是 M500", QqFileType.M500, attempts.last())
        }
    }

    @Test
    fun `链内不重复——重复只会白白多一次往返`() {
        for (level in listOf("lossless", "hires", "jymaster", "dolby", "jyeffect", "standard")) {
            val attempts = QqQuality.attemptsFor(level)
            assertEquals("$level 有重复档位: $attempts", attempts.size, attempts.toSet().size)
        }
    }

    @Test
    fun `高一档的链不会比低一档更差——顺序单调不升`() {
        // 粗略但有效的性质：用户选 hires 时，第一个尝试的档位不能比选 lossless 时更低。
        val hiresFirst = QqQuality.attemptsFor("hires").first()
        val losslessFirst = QqQuality.attemptsFor("lossless").first()
        assertEquals(QqFileType.RS01, hiresFirst)
        assertEquals(QqFileType.F000, losslessFirst)
        assertNotNull(QqQuality.attemptsFor("dolby").first())
    }

    @Test
    fun `已知档位的首选顺序符合实测结论`() {
        assertEquals(listOf(QqFileType.M500), QqQuality.ladderFor("standard"))
        assertEquals(listOf(QqFileType.M800), QqQuality.ladderFor("higher"))
        assertEquals(listOf(QqFileType.C400, QqFileType.M800), QqQuality.ladderFor("exhigh"))
        assertEquals(listOf(QqFileType.F000), QqQuality.ladderFor("lossless"))
        assertEquals(listOf(QqFileType.RS01), QqQuality.ladderFor("hires"))
    }

    @Test
    fun `未知档位从无损往下试——与ncm侧同样保守`() {
        assertEquals(listOf(QqFileType.F000), QqQuality.ladderFor("sky"))
        assertEquals(listOf(QqFileType.F000), QqQuality.ladderFor(""))
    }

    @Test
    fun `文件名用 media_mid 拼——这是实测最容易踩的一个坑`() {
        assertEquals("M800003Qui1q2u1Zho.mp3", QqQuality.fileNameFor(QqFileType.M800, "003Qui1q2u1Zho"))
        assertEquals("F000003Qui1q2u1Zho.flac", QqQuality.fileNameFor(QqFileType.F000, "003Qui1q2u1Zho"))
        assertEquals("RS01003Qui1q2u1Zho.flac", QqQuality.fileNameFor(QqFileType.RS01, "003Qui1q2u1Zho"))
        assertEquals("C400003Qui1q2u1Zho.m4a", QqQuality.fileNameFor(QqFileType.C400, "003Qui1q2u1Zho"))
    }

    @Test
    fun `从文件名前缀能反查回档位——响应挑选依赖它，不能依赖响应顺序`() {
        for (t in QqFileType.values()) {
            assertEquals(t, QqQuality.fileTypeOfFileName(QqQuality.fileNameFor(t, "abc")))
        }
        assertEquals(null, QqQuality.fileTypeOfFileName("XXXXabc.mp3"))
        assertEquals(null, QqQuality.fileTypeOfFileName(""))
    }

    @Test
    fun `档位名能反推回本应用的统一档位`() {
        assertEquals("standard", QqQuality.ncrustLevelOf(QqFileType.M500))
        assertEquals("higher", QqQuality.ncrustLevelOf(QqFileType.M800))
        assertEquals("exhigh", QqQuality.ncrustLevelOf(QqFileType.C400))
        assertEquals("lossless", QqQuality.ncrustLevelOf(QqFileType.F000))
        assertEquals("hires", QqQuality.ncrustLevelOf(QqFileType.RS01))
    }

    @Test
    fun `FLAC 档位被标记出来——设备门控要用它`() {
        assertTrue(QqFileType.F000.isFlac)
        assertTrue(QqFileType.RS01.isFlac)
        assertTrue(QqFileType.AI00.isFlac)
        assertTrue(!QqFileType.M500.isFlac)
        assertTrue(!QqFileType.C400.isFlac)
    }

    @Test
    fun `档位链里不含 O801——它是 VIP 专享的 AI 伴奏轨，用作兜底等于绕过付费墙`() {
        // 调研实测：VIP 专享曲的 O801 档匿名也能拿到可下载 purl。
        // 把它放进兜底链会让「无会员用户也能听到会员内容」，属于合规红线。
        val all = QqFileType.values().map { it.prefix }
        assertTrue("不得引入 O801", all.none { it == "O801" })
        for (level in listOf("dolby", "jymaster", "hires", "lossless", "exhigh", "higher", "standard")) {
            assertTrue(
                "$level 的链里出现了 O801",
                QqQuality.attemptsFor(level).none { it.prefix == "O801" },
            )
        }
    }

    // ===== v2.1.4：已知档位的确定码率 =====
    // 界面靠它把「请求超清母带、实际退回 320k mp3」如实显示成「更好」而不是继续挂着母带。

    @Test
    fun `已知压缩档位的码率是档位定义本身`() {
        assertEquals(128_000L, QqQuality.knownBitrateOf(QqFileType.M500))
        assertEquals(320_000L, QqQuality.knownBitrateOf(QqFileType.M800))
        assertEquals(96_000L, QqQuality.knownBitrateOf(QqFileType.C400))
    }

    @Test
    fun `FLAC 档位不编码率——同一档位在不同曲目上差异太大，编了会污染实测判定`() {
        for (t in listOf(
            QqFileType.F000, QqFileType.RS01, QqFileType.AI00,
            QqFileType.Q000, QqFileType.Q001,
        )) {
            assertEquals("${t.prefix} 必须是 0（未知）", 0L, QqQuality.knownBitrateOf(t))
        }
    }

    /** 320k mp3 的已知码率必须刚好落在 QualityAssessment 的 exhigh 量级里，否则降级仍会显示错。 */
    @Test
    fun `320k 的已知码率在实测判定里落在极高档`() {
        assertEquals(
            "exhigh",
            com.takahashirinta.ncrust.player.QualityAssessment
                .measuredLevel(QqQuality.knownBitrateOf(QqFileType.M800), QqFileType.M800.ext),
        )
        assertEquals(
            "standard",
            com.takahashirinta.ncrust.player.QualityAssessment
                .measuredLevel(QqQuality.knownBitrateOf(QqFileType.M500), QqFileType.M500.ext),
        )
    }
}
