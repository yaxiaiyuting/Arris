/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.4.8：B 站**大会员 Hi-Res 无损**的取流选档（问题 1）+ 用户音质参数（问题 3）。
 */

package com.takahashirinta.ncrust.bili

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.QualityAssessment
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import java.util.Collections
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v3.4.8 · 问题 1：**「B 站有大会员 Hi-Res，但我们永远播放普通版本」**。
 *
 * ## 根因（一行代码）
 *
 * 修复前 `BiliParse.parseDashAudio` **只读 `dash.audio[]`**，从中取带宽最高的一条。
 * 而 B 站的大会员 Hi-Res 无损**不在那一支里** —— 它在 `dash.flac.audio`（单对象）。
 * 于是同一个视频同时有「3 条 AAC + 1 条 2.2 Mbps 的 96 kHz FLAC」时，
 * 客户端永远挑走那条 192K 的 AAC，**而且不报任何错**。
 *
 * ## 这份样本是**实测原文**，不是构造的
 *
 * `VIP_DASH` 抄自 2026-10-07 的线上响应（登录态 + 年度大会员，
 * `GET /x/player/playurl?bvid=BV1EC4y1R7ax&cid=25931154534&fnval=4048&fnver=0&fourk=1`），
 * 只做了两处删减：`backupUrl` 只留第一个、所有 URL 的签名查询串截掉。
 * 字段名、条数、`id`、`bandwidth`、`codecs` **逐字保留**：
 *
 * | 来源 | id | bandwidth | codecs |
 * |---|---|---|---|
 * | `flac.audio` | 30251 | **2247494** | `fLaC` |
 * | `audio[0]` | 30232 | 109633 | `mp4a.40.2` |
 * | `audio[1]` | 30280 | 213610 | `mp4a.40.2` |
 * | `audio[2]` | 30216 | 53781 | `mp4a.40.5` |
 *
 * 同一份请求**匿名**发出去时 `flac` 恒为 `null`（同一 cid 实测），
 * 那个形状由 [ANON_DASH] 覆盖。
 *
 * ## 还有一条更早的缺陷：视频轨**完全不看请求档位**
 *
 * `resolveUrl` 写的是 `stream.toResult(null)` —— 请求 `lossless` 与请求 `standard`
 * 走的是同一条路。所以就算读到了 flac 也没人用。本文件的端到端用例
 * （[取链按档位拿到 FLAC]、[有损档位不去碰 FLAC]）把这条一起钉住。
 */
class BiliHiResTest {

    private companion object {
        const val BVID = "BV1EC4y1R7ax"
        const val CID = 25931154534L

        /** 真实 flac 流的 URL 前缀（签名串已在样本里截掉）。 */
        const val FLAC_URL = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/34/45/25931154534/25931154534-1-30251.m4s"
        const val AAC320_URL = "https://upos-sz-mirrorhwo1.bilivideo.com/upgcxcode/34/45/25931154534/25931154534-1-30280.m4s"
        const val AAC132_URL = "https://upos-sz-mirrorcoso1.bilivideo.com/upgcxcode/34/45/25931154534/25931154534-1-30232.m4s"
        const val AAC64_URL = "https://upos-sz-mirrorcos.bilivideo.com/upgcxcode/34/45/25931154534/25931154534-1-30216.m4s"

        /** 实测响应（登录 + 年度大会员）。`?deadline=…` 用于验证 TTL 解析仍然生效。 */
        val VIP_DASH = """
        {"code":0,"message":"0","data":{"dash":{"duration":2728,
          "audio":[
            {"id":30232,"baseUrl":"$AAC132_URL?deadline=1791394130","bandwidth":109633,"mimeType":"audio/mp4","codecs":"mp4a.40.2"},
            {"id":30280,"baseUrl":"$AAC320_URL?deadline=1791394130","bandwidth":213610,"mimeType":"audio/mp4","codecs":"mp4a.40.2"},
            {"id":30216,"baseUrl":"$AAC64_URL?deadline=1791394130","bandwidth":53781,"mimeType":"audio/mp4","codecs":"mp4a.40.5"}
          ],
          "flac":{"display":true,"audio":{"id":30251,"baseUrl":"$FLAC_URL?deadline=1791394130","bandwidth":2247494,"mimeType":"audio/mp4","codecs":"fLaC"}},
          "dolby":{"type":0,"audio":null},
          "video":[{"id":80,"baseUrl":"https://upos.example/video.m4s","bandwidth":9999999}]
        }}}
        """.trimIndent()

        /** 同一请求**匿名**时的实测形状：`flac` 为 null、`dolby.audio` 为 null。 */
        val ANON_DASH = """
        {"code":0,"data":{"dash":{
          "audio":[
            {"id":30232,"baseUrl":"$AAC132_URL?deadline=1791394130","bandwidth":109633,"mimeType":"audio/mp4","codecs":"mp4a.40.2"},
            {"id":30280,"baseUrl":"$AAC320_URL?deadline=1791394130","bandwidth":213610,"mimeType":"audio/mp4","codecs":"mp4a.40.2"}
          ],
          "flac":null,"dolby":{"type":0,"audio":null}
        }}}
        """.trimIndent()

        /**
         * 杜比全景声的样本。**这一份是构造的，不是实测原文** —— 两次真实响应的
         * `dolby` 都是 `{"type":0,"audio":null}`，形状无法从它反推。
         * 所以解析层两种形状都认，这里两条都测（见 `杜比在 dash_dolby_audio 里两种形状都认`）。
         */
        val DOLBY_DASH = """
        {"code":0,"data":{"dash":{
          "audio":[{"id":30280,"baseUrl":"$AAC320_URL?deadline=1791394130","bandwidth":213610,"mimeType":"audio/mp4","codecs":"mp4a.40.2"}],
          "flac":null,
          "dolby":{"type":0,"audio":[{"id":30250,"baseUrl":"https://upos.example/dolby.m4s?deadline=1791394130","bandwidth":448000,"mimeType":"audio/mp4","codecs":"ec-3"}]}
        }}}
        """.trimIndent()

        const val NOW = 1_790_540_000_000L
    }

    // ---------------------------------------------------------------- 解析层

    @Test
    fun `登录态样本解析出四条候选 顺序是 FLAC 优先`() {
        val all = BiliParse.parseDashAudios(VIP_DASH, nowMs = NOW)
        assertEquals("3 条 AAC + 1 条 FLAC", 4, all.size)
        assertEquals(BiliAudioKind.FLAC, all[0].kind)
        assertTrue("FLAC 必须排在最前：${all[0].url}", all[0].url.startsWith(FLAC_URL))
        assertEquals(2_247_494L, all[0].br)
        assertEquals("flac", all[0].container)
        // AAC 三档按带宽降序（30280 213610 > 30232 109633 > 30216 53781）。
        assertEquals(
            listOf(AAC320_URL, AAC132_URL, AAC64_URL),
            all.drop(1).map { it.url.substringBefore('?') },
        )
        assertEquals(listOf(213_610L, 109_633L, 53_781L), all.drop(1).map { it.br })
        assertTrue("绝不能取视频流", all.none { it.url.contains("video.m4s") })
    }

    @Test
    fun `修复前的语义（只读 dash_audio 取最高带宽）会漏掉 FLAC —— 这就是那条缺陷`() {
        // ★ 这条用例是**对照实验**，不是重复断言：它把「修复前那条路会选什么」写下来。
        //   旧实现 = `dash.audio[]` 里 bandwidth 最大的一条 = 30280 = 192K AAC。
        val legacy = BiliParse.parseDashAudio(VIP_DASH, nowMs = NOW)
        assertNotNull(legacy)
        assertTrue(
            "旧语义必须选 192K AAC（这正是用户听到的『普通版本』）：${legacy!!.url}",
            legacy.url.startsWith(AAC320_URL),
        )
        assertFalse("旧语义永远拿不到 FLAC", legacy.url == FLAC_URL)

        // 新语义：同一份响应里 FLAC 是可选的。
        val now = BiliParse.parseDashAudios(VIP_DASH, nowMs = NOW).first()
        assertTrue("修复后同一份响应里 FLAC 到手：${now.url}", now.url.startsWith(FLAC_URL))
    }

    @Test
    fun `匿名样本没有 FLAC —— 读到空不是缺陷 是身份拿不到`() {
        val all = BiliParse.parseDashAudios(ANON_DASH, nowMs = NOW)
        assertEquals(2, all.size)
        assertTrue("匿名没有 flac 这一支", all.none { it.kind == BiliAudioKind.FLAC })
        assertNull(
            "匿名样本里没有任何 FLAC 候选",
            BiliParse.parseDashAudios(ANON_DASH, nowMs = NOW).firstOrNull { it.kind == BiliAudioKind.FLAC },
        )
    }

    @Test
    fun `杜比在 dash_dolby_audio 里两种形状都认（数组与单对象）`() {
        val all = BiliParse.parseDashAudios(DOLBY_DASH, nowMs = NOW)
        assertEquals(2, all.size)
        val dolby = all.first { it.kind == BiliAudioKind.DOLBY }
        assertEquals(448_000L, dolby.br)
        // 容器给 `mp4`：它进 `QualityAssessment.measuredLevel(br, "mp4")` 会得到 dolby。
        assertEquals("mp4", dolby.container)
        assertEquals("dolby", BiliQuality.levelOf(dolby))

        // ★ 单对象形状也要认：真实响应里 `audio` 恒为 null，**没有实测依据**证明
        //   它非空时是数组。赌一种形状的失败模式是「这条支路静默为空」，
        //   而那与「这个视频没有杜比」在日志里分不出来。
        val asObject = BiliParse.parseDashAudios(
            """{"code":0,"data":{"dash":{"dolby":{"type":0,"audio":""" +
                """{"id":30250,"baseUrl":"https://x/d.m4s?deadline=1791394130","bandwidth":448000,"codecs":"ec-3","mimeType":"audio/mp4"}}}}}""",
            nowMs = NOW,
        )
        assertEquals(1, asObject.size)
        assertEquals(BiliAudioKind.DOLBY, asObject.single().kind)
        assertEquals(448_000L, asObject.single().br)

        // FLAC 那一支同理（实测是单对象，但数组形状也认）。
        val flacAsArray = BiliParse.parseDashAudios(
            """{"code":0,"data":{"dash":{"flac":{"audio":[""" +
                """{"id":30251,"baseUrl":"https://x/f.m4s?deadline=1791394130","bandwidth":2247494,"codecs":"fLaC","mimeType":"audio/mp4"}]}}}}""",
            nowMs = NOW,
        )
        assertEquals(1, flacAsArray.size)
        assertEquals(BiliAudioKind.FLAC, flacAsArray.single().kind)
    }

    @Test
    fun `TTL 仍然按 deadline 解析（flac 与 aac 同一条规则）`() {
        val all = BiliParse.parseDashAudios(VIP_DASH, nowMs = NOW)
        // `deadline=1791394130` 减掉 60 秒安全边距（`BiliParse.SAFETY_MARGIN_MS`）。
        // 这条断言防的是「改成读 flac 之后把 deadline 解析那条路走丢了」。
        val expected = 1_791_394_130_000L - 60_000L
        all.forEach { assertEquals("${it.url} 的过期时刻", expected, it.expiresAtMs) }
    }

    // ---------------------------------------------------------------- 选流

    private fun vipCandidates() = BiliParse.parseDashAudios(VIP_DASH, nowMs = NOW)

    @Test
    fun `选流按档位分派 —— 无损档拿 FLAC 有损档拿 AAC`() {
        val all = vipCandidates()
        // 无损及以上的四个档位都要 FLAC。
        listOf("lossless", "hires", "jyeffect", "jymaster").forEach { level ->
            assertTrue("$level 必须拿 FLAC", BiliQuality.selectStream(all, level)?.url?.startsWith(FLAC_URL) == true)
        }
        // 有损档位**不去碰** FLAC（那是用户的省流量意图，偷偷升级等于违背意图）。
        listOf("standard", "higher", "exhigh").forEach { level ->
            assertTrue("$level 必须拿最高带宽的 AAC", BiliQuality.selectStream(all, level)?.url?.startsWith(AAC320_URL) == true)
        }
    }

    @Test
    fun `杜比档优先杜比 其次 FLAC`() {
        val withDolby = BiliParse.parseDashAudios(DOLBY_DASH, nowMs = NOW)
        assertEquals(
            "请求 dolby 时首选杜比全景声",
            BiliAudioKind.DOLBY,
            BiliQuality.selectStream(withDolby, "dolby")?.kind,
        )
        // 杜比缺失时必须退到 AAC（而不是判定「播不了」）。
        val aacOnly = withDolby.filter { it.kind == BiliAudioKind.AAC }
        assertTrue(
            "杜比缺失时退到 AAC",
            BiliQuality.selectStream(aacOnly, "dolby")?.url?.startsWith(AAC320_URL) == true,
        )
    }

    @Test
    fun `关掉优先 FLAC 时无损档退到 AAC —— 但只有 FLAC 时仍然播 FLAC`() {
        val all = vipCandidates()
        assertTrue(
            "preferFlac=false 时必须选 AAC（否则这个开关是死的）",
            BiliQuality.selectStream(all, "lossless", preferFlac = false)?.url?.startsWith(AAC320_URL) == true,
        )
        // 只有 FLAC 的时候（极端形状）：不能因为开关关着就判定「播不了」。
        val flacOnly = all.filter { it.kind == BiliAudioKind.FLAC }
        assertTrue(
            "只有 FLAC 时仍然播 FLAC",
            BiliQuality.selectStream(flacOnly, "lossless", preferFlac = false)?.url?.startsWith(FLAC_URL) == true,
        )
    }

    @Test
    fun `请求无损但只有 AAC 时如实降级 而不是判死`() {
        val anon = BiliParse.parseDashAudios(ANON_DASH, nowMs = NOW)
        val picked = BiliQuality.selectStream(anon, "lossless")
        assertTrue("只有 AAC 时退到它", picked?.url?.startsWith(AAC320_URL) == true)
        assertEquals("192K AAC 的实际档位是 higher", "higher", BiliQuality.levelOf(picked!!))
    }

    @Test
    fun `空候选返回 null（不抛）`() {
        assertNull(BiliQuality.selectStream(emptyList(), "lossless"))
        assertNull(BiliQuality.selectStream(BiliParse.parseDashAudios("""{"code":0,"data":{"dash":{"video":[]}}}"""), "lossless"))
        assertNull(BiliQuality.selectStream(BiliParse.parseDashAudios("not json"), "lossless"))
    }

    // ---------------------------------------------------------------- 档位反推

    @Test
    fun `实际档位由 kind 与码率反推 —— 与 QualityAssessment 的锚点同源`() {
        val all = vipCandidates()
        val flac = all.first { it.kind == BiliAudioKind.FLAC }
        // 2247494 ≥ 1_400_000 ⇒ hires（与 QualityAssessment.HIRES_BR 同一锚点）。
        assertEquals("hires", BiliQuality.levelOf(flac))
        assertEquals("hires", QualityAssessment.measuredLevel(flac.br, flac.container))
        // AAC 三档：只有 192K 那一档够得着 higher。
        assertEquals("higher", BiliQuality.levelOf(all.first { it.url.startsWith(AAC320_URL) }))
        assertEquals("standard", BiliQuality.levelOf(all.first { it.url.startsWith(AAC132_URL) }))
        assertEquals("standard", BiliQuality.levelOf(all.first { it.url.startsWith(AAC64_URL) }))
    }

    @Test
    fun `低码率 FLAC 也诚实报 lossless 而不是 hires`() {
        val lowFlac = BiliParse.parseDashAudios(
            """{"code":0,"data":{"dash":{"flac":{"audio":{"baseUrl":"https://x/a.m4s","bandwidth":900000,"codecs":"fLaC","mimeType":"audio/mp4"}}}}}""",
            nowMs = NOW,
        ).first()
        assertEquals("lossless", BiliQuality.levelOf(lowFlac))
    }

    // ---------------------------------------------------------------- 用户参数（问题 3）

    @Test
    fun `音质上限换算 —— 四条规则各自的方向`() {
        // AUTO 是恒等：默认值对既有行为**零影响**。
        listOf("standard", "higher", "exhigh", "lossless", "hires", "jymaster", "dolby").forEach { level ->
            assertEquals("AUTO 必须恒等：$level", level, BiliQualityCapRules.applyCap(level, BiliQualityCap.AUTO))
        }
        // HIRES 是**抬升**（这是它存在的唯一理由：全局档位在移动网络下默认 192K）。
        assertEquals("hires", BiliQualityCapRules.applyCap("higher", BiliQualityCap.HIRES))
        assertEquals("hires", BiliQualityCapRules.applyCap("standard", BiliQualityCap.HIRES))
        // 已经更高的档位不被它降低。
        assertEquals("jymaster", BiliQualityCapRules.applyCap("jymaster", BiliQualityCap.HIRES))
        assertEquals("dolby", BiliQualityCapRules.applyCap("dolby", BiliQualityCap.HIRES))
        // EXHIGH / HIGHER 是**夹取**。
        assertEquals("exhigh", BiliQualityCapRules.applyCap("lossless", BiliQualityCap.EXHIGH))
        assertEquals("exhigh", BiliQualityCapRules.applyCap("jymaster", BiliQualityCap.EXHIGH))
        assertEquals("higher", BiliQualityCapRules.applyCap("exhigh", BiliQualityCap.HIGHER))
        assertEquals("standard", BiliQualityCapRules.applyCap("standard", BiliQualityCap.HIGHER))
    }

    @Test
    fun `音质上限幂等 —— 取链重试与预载会重入`() {
        val levels = listOf("standard", "higher", "exhigh", "lossless", "hires", "jymaster", "dolby", "unknown")
        BiliQualityCap.values().forEach { cap ->
            levels.forEach { level ->
                val once = BiliQualityCapRules.applyCap(level, cap)
                assertEquals("$cap · $level 不幂等", once, BiliQualityCapRules.applyCap(once, cap))
            }
        }
    }

    @Test
    fun `未知档位服从显式选择 而不是绕过上限`() {
        // 将来加了新档位（例如 ncm 的 sky）：拿不准时服从用户设定，不能静默放开。
        assertEquals("exhigh", BiliQualityCapRules.applyCap("sky", BiliQualityCap.EXHIGH))
        assertEquals("higher", BiliQualityCapRules.applyCap("sky", BiliQualityCap.HIGHER))
        assertEquals("hires", BiliQualityCapRules.applyCap("sky", BiliQualityCap.HIRES))
        assertEquals("sky", BiliQualityCapRules.applyCap("sky", BiliQualityCap.AUTO))
    }

    @Test
    fun `枚举 key 与设置注册表的候选逐字一致`() {
        assertEquals(listOf("auto", "hires", "exhigh", "higher"), BiliQualityCap.values().map { it.key })
        assertEquals(BiliQualityCap.AUTO, BiliQualityCap.of("AUTO"))
        assertEquals(BiliQualityCap.HIRES, BiliQualityCap.of(" hires "))
        assertNull(BiliQualityCap.of("lossless"))
        assertNull(BiliQualityCap.of(null))
    }

    @Test
    fun `回退判定只在真的更低时成立`() {
        assertNull("请求无损拿到 Hi-Res 是升级不是降级", BiliQuality.fallbackFrom("lossless", "hires"))
        assertEquals("lossless", BiliQuality.fallbackFrom("lossless", "exhigh"))
        assertNull("等价不算降级", BiliQuality.fallbackFrom("lossless", "lossless"))
        // 音频区的文件名标签要先归一化再比（两套词表不可直接比字符串）。
        assertEquals("lossless", BiliQuality.levelOfAudioLabel("FLAC"))
        assertEquals("exhigh", BiliQuality.levelOfAudioLabel("320K"))
        assertEquals("higher", BiliQuality.levelOfAudioLabel("192K"))
        assertEquals("standard", BiliQuality.levelOfAudioLabel("128K"))
        assertNull("未知标签不猜", BiliQuality.levelOfAudioLabel("未知"))
        assertNull(
            "曾经 `lossless != FLAC` 让每次成功拿到 FLAC 都被记成降级",
            BiliQuality.fallbackFrom("lossless", BiliQuality.levelOfAudioLabel("FLAC")!!),
        )
    }

    // ---------------------------------------------------------------- 端到端（假传输层）

    private val requested = Collections.synchronizedList(ArrayList<String>())

    @Before
    fun setUp() {
        requested.clear()
        BiliPrefs.setEnabledForTest(true)
        BiliPrefs.setQualityCapForTest(BiliQualityCap.AUTO)
        BiliPrefs.setPreferFlacForTest(true)
        BiliApi.clientForTest = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val url = chain.request().url.toString()
                requested += url
                val body = when {
                    url.contains("/x/frontend/finger/spi") -> """{"code":0,"data":{"b_3":"FAKEBUV"}}"""
                    url.contains("/x/player/playurl") -> VIP_DASH
                    else -> """{"code":0}"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    @After
    fun tearDown() {
        BiliApi.clientForTest = null
        BiliPrefs.setEnabledForTest(false)
        BiliPrefs.setQualityCapForTest(BiliQualityCap.AUTO)
        BiliPrefs.setPreferFlacForTest(true)
    }

    /** 一条 B 站**视频轨**曲目，`sourceId` 自带 cid（预载路径的真实形状）。 */
    private fun videoSong(): SongItem = SongItem(
        id = SourceIds.biliId(791_890_228L),
        name = "【HiRes】崔健 新长征路上的摇滚",
        artists = emptyList(),
        album = null,
        duration = 2_728_000L,
        source = MusicSource.BILIBILI.key,
        sourceId = "bv:$BVID:$CID",
        mediaId = null,
    )

    @Test
    fun `取链按档位拿到 FLAC —— 这是用户报障的那一条`() = runBlocking {
        val result = BiliSourceProvider.resolveUrl(videoSong(), "lossless")
        assertNotNull("必须取到链", result)
        assertTrue("★ 必须真的是 FLAC 流：${result!!.url}", result.url.startsWith(FLAC_URL))
        assertEquals("flac", result.type)
        assertEquals(2_247_494L, result.br)
        assertTrue("档位是从文件反推的", result.levelFromFile)
        assertNull("请求无损拿到 Hi-Res 不该被记成降级", result.fallbackFromLevel)
        // 界面会把它显示成「高解析」——2247494 bps ≥ HIRES_BR(1_400_000)。
        assertEquals("hires", result.actualLevel)
        val verdict = QualityAssessment.assess(
            requested = "lossless",
            granted = result.actualLevel,
            br = result.br,
            type = result.type,
            songMaxLevel = result.songMaxLevel,
            levelFromFile = result.levelFromFile,
        )
        assertEquals(
            "显示档位必须是实测出来的高解析",
            "hires",
            com.takahashirinta.ncrust.player.QualityLadder.LEVELS[verdict.displayIndex],
        )
        assertEquals(QualityStatusExpectation.NORMAL, verdict.status)
    }

    @Test
    fun `有损档位仍然拿到 AAC 且如实标出降级`() = runBlocking {
        val result = BiliSourceProvider.resolveUrl(videoSong(), "exhigh")
        assertNotNull(result)
        assertTrue("请求 320K 时不该偷偷给 FLAC：${result!!.url}", result.url.startsWith(AAC320_URL))
        assertEquals("m4a", result.type)
        // 192K AAC 够不上请求的 320K ⇒ 必须如实降级（修复前这里什么都不显示）。
        assertEquals("higher", result.actualLevel)
        assertEquals("exhigh", result.fallbackFromLevel)
        val verdict = QualityAssessment.assess(
            requested = "exhigh",
            granted = result.actualLevel,
            br = result.br,
            type = result.type,
            songMaxLevel = result.songMaxLevel,
            levelFromFile = result.levelFromFile,
        )
        assertEquals(QualityStatusExpectation.DOWNGRADED, verdict.status)
    }

    @Test
    fun `关掉优先 FLAC 之后无损档退到 AAC`() = runBlocking {
        BiliPrefs.setPreferFlacForTest(false)
        val result = BiliSourceProvider.resolveUrl(videoSong(), "lossless")
        assertTrue("关掉开关后必须给 AAC：${result?.url}", result?.url?.startsWith(AAC320_URL) == true)
        assertEquals("higher", result?.actualLevel)
        assertEquals("lossless", result?.fallbackFromLevel)
    }

    @Test
    fun `音质上限 仅192K 时即便请求无损也只拿 AAC`() = runBlocking {
        BiliPrefs.setQualityCapForTest(BiliQualityCap.HIGHER)
        val result = BiliSourceProvider.resolveUrl(videoSong(), "lossless")
        // 192K 封顶 ⇒ 有损档位 ⇒ 不去碰 FLAC。
        assertTrue("封顶后不该给 FLAC：${result?.url}", result?.url?.startsWith(AAC320_URL) == true)
        // 30280 是 192K ⇒ 恰好等于上限，不算降级。
        assertEquals("higher", result?.actualLevel)
        assertNull(result?.fallbackFromLevel)
    }

    @Test
    fun `音质上限 允许无损 时全局档位是 192K 也拿 FLAC`() = runBlocking {
        // 这是 HIRES 档存在的理由：全局档位（移动网络默认 higher）不该锁死大会员能力。
        BiliPrefs.setQualityCapForTest(BiliQualityCap.HIRES)
        val result = BiliSourceProvider.resolveUrl(videoSong(), "higher")
        assertTrue("抬升后应拿到 FLAC：${result?.url}", result?.url?.startsWith(FLAC_URL) == true)
        assertEquals("hires", result?.actualLevel)
    }

    @Test
    fun `播放地址请求里带上了 buvid3 指纹（旧路径的硬要求）`() = runBlocking {
        BiliSourceProvider.resolveUrl(videoSong(), "lossless")
        assertTrue(
            "必须问一次指纹：$requested",
            requested.any { it.contains("/x/frontend/finger/spi") },
        )
        assertTrue(
            "必须打 playurl：$requested",
            requested.any { it.contains("/x/player/playurl") },
        )
        assertTrue("fnval 仍须是实测过的 4048", requested.any { it.contains("fnval=4048") })
    }
}

/** `QualityStatus` 的期望值（避免本文件为了一个枚举把整个 import 列表撑长）。 */
private object QualityStatusExpectation {
    val NORMAL = com.takahashirinta.ncrust.player.QualityStatus.NORMAL
    val DOWNGRADED = com.takahashirinta.ncrust.player.QualityStatus.DOWNGRADED
}
