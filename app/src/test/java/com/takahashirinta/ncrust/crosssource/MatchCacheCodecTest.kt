/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.4.0 · E：跨源匹配缓存的迁移与容量裁剪回归单测。
 *
 * 这一套用例的重点不是「编码能解回来」（那是显然的），而是三件容易在版本演进中丢掉的事：
 * 1. **落盘 key 名是对外契约** —— 改名会让老用户的数据静默消失；
 * 2. **算法版本一变，旧结论必须整体作废** —— 否则用户会看到「按旧规则合并的两条」；
 * 3. **低于阈值的结果根本不进缓存** —— 铁律 17。
 */

package com.takahashirinta.ncrust.crosssource

import com.google.gson.Gson
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertThrows

class MatchCacheCodecTest {

    private val now = 1_800_000_000_000L
    private val aliases = listOf(MatchCacheCodec.aliasOf(MusicSource.QQMUSIC, "0025NhlN2yWrP4", "周杰伦"))

    // ------------------------------------------------------------------ 基本 ----

    @Test
    fun `编码再解码能拿回同样的结论`() {
        val raw = MatchCacheCodec.encode(MatchConfidence.EXACT, "同名 + 专辑重合 33 张", aliases, 33, now)
        val read = MatchCacheCodec.decode(raw, now)
        assertTrue(read is MatchCacheCodec.Read.Ok)
        val ok = read as MatchCacheCodec.Read.Ok
        assertEquals(MatchConfidence.EXACT, ok.confidence)
        assertEquals(33, ok.overlap)
        assertEquals("同名 + 专辑重合 33 张", ok.reason)
        assertEquals(1, ok.aliases.size)
        assertTrue(ok.fresh)
    }

    @Test
    fun `低于阈值的结果根本不允许编码（铁律 17 的硬保证）`() {
        // 用 require 而不是静默返回 null：那是调用方的逻辑错误，
        // 应该在单测里炸出来，而不是在真机上表现为「缓存里多了一条不该有的记录」。
        assertThrows(IllegalArgumentException::class.java) {
            MatchCacheCodec.encode(MatchConfidence.LOW, "仅同名", aliases, 0, now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MatchCacheCodec.encode(MatchConfidence.NONE, "无法匹配", aliases, 0, now)
        }
    }

    // ------------------------------------------------------- 落盘 key 名（契约） ----

    @Test
    fun `落盘的确切 key 名不会随重构漂移（R8 与改名的回归保护）`() {
        // v2.3.0 的教训：DTO 字段名被 R8 混淆成 {"a":…} 后同一 APK 内自洽、不崩，
        // 但下一次构建的映射一变，老数据一条都读不出来 ⇒ 用户数据静默消失。
        // 这条用例钉住**确切**的 key 名：字段改名会让它变红，而不是让用户的数据消失。
        val raw = MatchCacheCodec.encode(MatchConfidence.HIGH, "r", aliases, 3, now)
        val json = Gson().fromJson(raw, Map::class.java)
        assertTrue("缺少 schemaVersion", json.containsKey("schemaVersion"))
        assertTrue("缺少 algorithmVersion", json.containsKey("algorithmVersion"))
        assertTrue("缺少 savedAt", json.containsKey("savedAt"))
        assertTrue("缺少 confidence", json.containsKey("confidence"))
        assertTrue("缺少 reason", json.containsKey("reason"))
        assertTrue("缺少 overlap", json.containsKey("overlap"))
        assertTrue("缺少 aliasesJson", json.containsKey("aliasesJson"))
        // 数组存成 JSON 字符串而不是数组本身：字段泛型签名被 R8 丢掉时，
        // 裸 List 会被 Gson 反序列化成 LinkedTreeMap（v2.2.0 的 release 崩溃）。
        assertTrue("aliasesJson 必须是字符串", json["aliasesJson"] is String)
        assertEquals(7, json.size)
    }

    @Test
    fun `别名条目的确切 key 名也是契约`() {
        // ★ 这里必须用**五个字段全非空**的别名：Gson 默认不序列化 null 字段，
        //   用只有 source/id/name 的样本去断言 sourceId/mediaId 会永远失败 ——
        //   而那个失败会掩盖真正的契约（key 名被改名）。
        val full = listOf(
            MatchCacheCodec.AliasDto(
                source = "qqmusic", id = "97773", name = "晴天",
                sourceId = "0039MnYb0qxYhV", mediaId = "003Qui1q2u1Zho",
            ),
        )
        val raw = MatchCacheCodec.encode(MatchConfidence.HIGH, "r", full, 1, now)
        val env = Gson().fromJson(raw, Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val inner = Gson().fromJson(env["aliasesJson"] as String, List::class.java)
        @Suppress("UNCHECKED_CAST")
        val first = inner.first() as Map<String, Any?>
        assertTrue(first.containsKey("source"))
        assertTrue(first.containsKey("id"))
        assertTrue(first.containsKey("name"))
        assertTrue(first.containsKey("sourceId"))
        assertTrue(first.containsKey("mediaId"))
    }

    // ------------------------------------------------------------------ 版本 ----

    @Test
    fun `结构版本比本版高时拒绝解释（降级安装场景）`() {
        val raw = """
            {"schemaVersion":${MatchCacheCodec.SCHEMA_VERSION + 1},"algorithmVersion":
            ${MatchCacheCodec.ALGORITHM_VERSION},"savedAt":$now,"confidence":"EXACT",
            "reason":"r","overlap":1,"aliasesJson":"[]"}
        """.trimIndent().replace("\n", "")
        val read = MatchCacheCodec.decode(raw, now)
        assertEquals(MatchCacheCodec.DropReason.FUTURE_SCHEMA, (read as MatchCacheCodec.Read.Dropped).reason)
    }

    @Test
    fun `缺 schemaVersion 按裸载荷解释 而不是当成损坏（v0 兼容）`() {
        val raw = """
            {"algorithmVersion":${MatchCacheCodec.ALGORITHM_VERSION},"savedAt":$now,
            "confidence":"EXACT","reason":"r","overlap":2,
            "aliasesJson":"[{\"source\":\"qqmusic\",\"id\":\"aaa\",\"name\":\"x\"}]"}
        """.trimIndent().replace("\n", "")
        val read = MatchCacheCodec.decode(raw, now)
        assertTrue(read is MatchCacheCodec.Read.Ok)
    }

    @Test
    fun `低版本 schemaVersion 也按当前形状解释（只有 v1 时的前向空间）`() {
        val raw = """
            {"schemaVersion":0,"algorithmVersion":${MatchCacheCodec.ALGORITHM_VERSION},
            "savedAt":$now,"confidence":"HIGH","reason":"r","overlap":1,
            "aliasesJson":"[{\"source\":\"qqmusic\",\"id\":\"aaa\"}]"}
        """.trimIndent().replace("\n", "")
        assertTrue(MatchCacheCodec.decode(raw, now) is MatchCacheCodec.Read.Ok)
    }

    @Test
    fun `算法版本不一致时整条作废（旧阈值算出来的 EXACT 不可信）`() {
        val raw = """
            {"schemaVersion":1,"algorithmVersion":${MatchCacheCodec.ALGORITHM_VERSION + 1},
            "savedAt":$now,"confidence":"EXACT","reason":"r","overlap":1,
            "aliasesJson":"[{\"source\":\"qqmusic\",\"id\":\"aaa\"}]"}
        """.trimIndent().replace("\n", "")
        val read = MatchCacheCodec.decode(raw, now)
        assertEquals(MatchCacheCodec.DropReason.STALE_ALGORITHM,
            (read as MatchCacheCodec.Read.Dropped).reason)
    }

    @Test
    fun `缺 algorithmVersion 一律作废（那不是本算法算出来的）`() {
        val raw = """
            {"schemaVersion":1,"savedAt":$now,"confidence":"EXACT","reason":"r","overlap":1,
            "aliasesJson":"[{\"source\":\"qqmusic\",\"id\":\"aaa\"}]"}
        """.trimIndent().replace("\n", "")
        val read = MatchCacheCodec.decode(raw, now)
        assertEquals(MatchCacheCodec.DropReason.STALE_ALGORITHM,
            (read as MatchCacheCodec.Read.Dropped).reason)
    }

    // ------------------------------------------------------------------ 健壮 ----

    @Test
    fun `空串 坏 JSON 都当没有 绝不抛异常`() {
        assertEquals(MatchCacheCodec.DropReason.ABSENT,
            (MatchCacheCodec.decode(null, now) as MatchCacheCodec.Read.Dropped).reason)
        assertEquals(MatchCacheCodec.DropReason.ABSENT,
            (MatchCacheCodec.decode("  ", now) as MatchCacheCodec.Read.Dropped).reason)
        assertEquals(MatchCacheCodec.DropReason.MALFORMED,
            (MatchCacheCodec.decode("{ not json", now) as MatchCacheCodec.Read.Dropped).reason)
    }

    @Test
    fun `别名全解不出来时丢弃（空别名等于没有匹配）`() {
        val raw = MatchCacheCodec.encode(MatchConfidence.EXACT, "r", emptyList(), 0, now)
        assertEquals(MatchCacheCodec.DropReason.NO_ALIASES,
            (MatchCacheCodec.decode(raw, now) as MatchCacheCodec.Read.Dropped).reason)
    }

    @Test
    fun `未知音源的别名被逐条丢掉 而不是让整条失败`() {
        val mixed = listOf(
            MatchCacheCodec.aliasOf(MusicSource.QQMUSIC, "aaa", "A"),
            MatchCacheCodec.AliasDto(source = "spotify", id = "zzz", name = "S"),
        )
        val raw = MatchCacheCodec.encode(MatchConfidence.HIGH, "r", mixed, 1, now)
        val ok = MatchCacheCodec.decode(raw, now) as MatchCacheCodec.Read.Ok
        assertEquals(2, ok.aliases.size)
        assertEquals(1, ok.aliases.mapNotNull { MatchCacheCodec.artistAlias(it) }.size)
    }

    @Test
    fun `不可合并的 confidence 混进缓存时被拒（手改 prefs 的防线）`() {
        val raw = """
            {"schemaVersion":1,"algorithmVersion":${MatchCacheCodec.ALGORITHM_VERSION},
            "savedAt":$now,"confidence":"LOW","reason":"r","overlap":0,
            "aliasesJson":"[{\"source\":\"qqmusic\",\"id\":\"aaa\"}]"}
        """.trimIndent().replace("\n", "")
        assertEquals(MatchCacheCodec.DropReason.MALFORMED,
            (MatchCacheCodec.decode(raw, now) as MatchCacheCodec.Read.Dropped).reason)
    }

    // ------------------------------------------------------------------ TTL ----

    @Test
    fun `TTL 左闭右开 正好到期算过期`() {
        assertTrue(MatchCacheCodec.isFresh(now, now))
        assertTrue(MatchCacheCodec.isFresh(now, now + MatchCacheCodec.TTL_MS - 1))
        assertFalse(MatchCacheCodec.isFresh(now, now + MatchCacheCodec.TTL_MS))
        assertFalse(MatchCacheCodec.isFresh(now, now + MatchCacheCodec.TTL_MS + 1))
    }

    @Test
    fun `缺时间戳或时钟回拨一律算过期（否则老条目永远新鲜）`() {
        assertFalse(MatchCacheCodec.isFresh(0L, now))
        assertFalse(MatchCacheCodec.isFresh(-1L, now))
        assertFalse(MatchCacheCodec.isFresh(now, now - 1_000L))
    }

    @Test
    fun `过期的条目仍然能解出来 只是 fresh 为 false`() {
        val raw = MatchCacheCodec.encode(MatchConfidence.EXACT, "r", aliases, 1, now)
        val ok = MatchCacheCodec.decode(raw, now + MatchCacheCodec.TTL_MS + 1) as MatchCacheCodec.Read.Ok
        assertFalse(ok.fresh)
        assertEquals(MatchConfidence.EXACT, ok.confidence)
    }

    // ------------------------------------------------------------------ key ----

    @Test
    fun `缓存 key 里必须有音源（ncm的 6452 与 QQ 的 6452 是两个东西）`() {
        assertEquals("artist:netease:6452",
            MatchCacheKeys.artist(ArtistKey(MusicSource.NETEASE, "6452", "周杰伦")))
        assertEquals("artist:qqmusic:6452",
            MatchCacheKeys.artist(ArtistKey(MusicSource.QQMUSIC, "6452", "周杰伦")))
        assertEquals("album:qqmusic:000MkMni19ClKG",
            MatchCacheKeys.album(AlbumKey(MusicSource.QQMUSIC, "000MkMni19ClKG", "叶惠美")))
        assertEquals("track:qqmusic:97773",
            MatchCacheKeys.track(TrackKey(MusicSource.QQMUSIC, 97773)))
    }

    @Test
    fun `缓存 key 能被反解出种类与音源`() {
        assertEquals("artist" to "netease", MatchCacheKeys.parse("artist:netease:6452"))
        assertEquals("track" to "qqmusic", MatchCacheKeys.parse("track:qqmusic:97773"))
        assertEquals(null, MatchCacheKeys.parse("artist:netease"))
        assertEquals(null, MatchCacheKeys.parse("周杰伦"))
    }

    // ------------------------------------------------------------ 容量裁剪 ----

    @Test
    fun `容量裁剪按最旧优先 且绝不删刚写入的那一条`() {
        val existing = HashMap<String, Long>()
        for (i in 1..10) existing["track:netease:$i"] = i.toLong()
        // 刚写入的是**最旧**的那一条（savedAt 最小）—— 极端但合法：
        // 时钟回拨、或者调用方显式传了一个旧时间戳。
        existing["track:netease:999"] = 0L
        val dropped = MatchCacheStore.planTrim(existing, "track:netease:999", max = 5)
        assertEquals(6, dropped.size)
        assertFalse("绝不能删掉刚写入的那条", dropped.contains("track:netease:999"))
        // 剩下的 5 条 = 最新的 4 条（7..10）+ 刚写入的那条。
        // 注意 999 的 savedAt 是 0（最旧），按 LRU 它本该第一个被裁掉，
        // 但「刚写入的不能删」这条优先 —— 否则刚存进去的结果会在同一次调用里被自己抹掉，
        // 表现为「匹配缓存永远不生效」。
        val kept = existing.keys - dropped.toSet()
        assertEquals(setOf("track:netease:7", "track:netease:8", "track:netease:9",
            "track:netease:10", "track:netease:999"), kept)
    }

    @Test
    fun `没超限时不裁剪`() {
        val existing = mapOf("a:netease:1" to 1L, "b:netease:2" to 2L)
        assertTrue(MatchCacheStore.planTrim(existing, "a:netease:1", max = 5).isEmpty())
        assertTrue(MatchCacheStore.planTrim(existing, "a:netease:1", max = 2).isEmpty())
    }

    @Test
    fun `同一时间戳的裁剪结果稳定（同一份数据每次裁掉同一批）`() {
        val existing = LinkedHashMap<String, Long>()
        for (i in 1..6) existing["t:netease:$i"] = 100L
        existing["t:netease:new"] = 100L
        val first = MatchCacheStore.planTrim(existing, "t:netease:new", max = 3)
        val second = MatchCacheStore.planTrim(existing, "t:netease:new", max = 3)
        assertEquals(first, second)
        assertEquals(4, first.size)
    }

    @Test
    fun `容量上限是一个明确常量 不是隐式的`() {
        assertEquals(400, MatchCacheStore.MAX_ENTRIES)
    }
}
