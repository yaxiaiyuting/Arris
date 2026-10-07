/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.6.0 · P0 单元测试：`ncrust_library` / `saved_songs` 的落盘契约。
 *
 * ## 为什么这个文件里有一条**真机样本**用例
 *
 * 本版修的 bug 是「QQ 歌曲入库后刷新消失」。它的**表现形式**是数据消失，
 * 所以真正需要防的是「升级后老用户磁盘上那 147 条收藏读不出来」。
 * 那份数据长什么样不能靠回忆 —— 它是从 S6（SM-G9209 / Android 7.0）
 * 上 `su -c cat /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_library.xml`
 * 逐字取回来的，样本原样贴在下面。
 *
 * 真机实测形状（147 条，逐条同形）：
 * `[{"al":{…},"ar":[…],"dt":…,"id":…,"name":…}, …]`
 * —— **只有 5 个 key**，因为 Gson 把值为 null 的 `source`/`mid`/`media_id`/
 * `fee`/`member_only`/`privilege` 整条 key 省掉了。
 * 这正是 `OfflineTrackCodec` 那个「`a b e f g i` 缺 `h`」的同一形状，
 * 所以这里**不许**按位置读（见 `SavedSongCodec.decodeEnvelopeByDeclarationOrder`
 * 的 KDoc）。
 */

package com.takahashirinta.ncrust.library

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSongCodecTest {

    private val now = 1_700_000_000_000L

    private fun song(
        id: Long,
        name: String = "t$id",
        source: MusicSource = MusicSource.NETEASE,
        mid: String? = null,
    ) = SongItem(
        id = id,
        name = name,
        artists = listOf(ArtistItem(1L, "艺人")),
        album = AlbumItem(2L, "专辑", "https://x/y.jpg"),
        duration = 180_000L,
        source = if (source == MusicSource.NETEASE) null else source.key,
        sourceId = mid,
    )

    private fun entry(
        id: Long,
        source: MusicSource = MusicSource.NETEASE,
        origin: SavedSongOrigin = SavedSongOrigin.REMOTE,
        tombstoned: Boolean = false,
        addedAt: Long = now,
        mid: String? = null,
    ) = SavedSongEntry(
        trackKey = TrackKey(source, id),
        origin = origin,
        addedAt = addedAt,
        tombstoned = tombstoned,
        song = song(id, source = source, mid = mid),
    )

    // ------------------------------------------------------- 真机样本（v1 形状） ----

    /**
     * ★ **S6 真机 v2.5.6 的落盘样本**（逐字取自 `ncrust_library.xml` 的
     * `saved_songs`，只截了前 2 条、并把 `picUrl` 里的 `\u003d` 还原成 `=`）。
     *
     * 它证明两件事：
     * 1. v1 是**裸 `SongItem` 数组**，没有 `origin` / `tombstoned` / `trackKey`；
     * 2. QQ 合成 id（≥ 2⁶²）在那份数据里**一条都没有** —— 与根因一致
     *    （手动加入的条目活不过一次刷新，所以磁盘上留不下）。
     */
    private val REAL_DEVICE_V1_JSON = """
        [{"al":{"id":93737998,"name":"燕无歇","picUrl":"https://p2.music.126.net/Fa6AWEnh6fK1mSnlqmL74Q==/109951168772584141.jpg"},"ar":[{"id":8234,"name":"蒋雪儿Snow.J"}],"dt":201437,"id":1469825684,"name":"燕无歇"},
         {"al":{"id":148352363,"name":"Doja","picUrl":"https://p2.music.126.net/A4jjpwGxShaGfrUUQESscA==/109951167695868938.jpg"},"ar":[{"id":31996349,"name":"Central Cee"}],"dt":97392,"id":1965392316,"name":"Doja"}]
    """.trimIndent()

    @Test
    fun `真机 v1 样本必须完整读出来（升级不丢收藏）`() {
        val decoded = SavedSongCodec.decode(REAL_DEVICE_V1_JSON)
        assertEquals("S6 上的 2 条样本必须一条不少", 2, decoded.size)
        assertEquals(1469825684L, decoded[0].trackKey.id)
        assertEquals("燕无歇", decoded[0].song.name)
        assertEquals("蒋雪儿Snow.J", decoded[0].song.artists?.first()?.name)
        assertEquals(MusicSource.NETEASE, decoded[0].trackKey.source)
        assertEquals(1965392316L, decoded[1].trackKey.id)

        // v1 没有来源信息 ⇒ 唯一有证据支撑的解释是「同步来的」。
        decoded.forEach {
            assertEquals(SavedSongOrigin.REMOTE, it.origin)
            assertFalse(it.tombstoned)
            assertEquals("v1 没有 addedAt，必须用 0（『不知道』）而不是 now", 0L, it.addedAt)
            assertTrue(it.isVisible)
        }
    }

    @Test
    fun `v1 样本经编码再解码形状不变（写回之后仍然是同一批歌）`() {
        val once = SavedSongCodec.decode(REAL_DEVICE_V1_JSON)
        val twice = SavedSongCodec.decode(SavedSongCodec.encode(once))
        assertEquals(once.map { it.trackKey }, twice.map { it.trackKey })
        assertEquals(once.map { it.song.name }, twice.map { it.song.name })
        // 第二遍已经是 v2 信封，但 origin 必须还是 REMOTE（不能因为写回一次就变成 LOCAL）。
        twice.forEach { assertEquals(SavedSongOrigin.REMOTE, it.origin) }
    }

    /** **反向**判据：v1 的裸 SongItem **不会**被误判成 v2 信封。 */
    @Test
    fun `v1 裸数组不会被误判成信封`() {
        val obj = JsonParser.parseString(
            """{"al":{"id":1,"name":"a"},"ar":[],"dt":1,"id":123,"name":"n"}""",
        ).asJsonObject
        assertFalse("v1 形状被判成了信封 —— origin/tombstone 会一起丢失", SavedSongCodec.isEnvelope(obj))

        val envelope = JsonParser.parseString(
            """{"trackKey":"netease:123","origin":"LOCAL","addedAt":1,"tombstoned":false,"song":{"id":123,"name":"n"}}""",
        ).asJsonObject
        assertTrue(SavedSongCodec.isEnvelope(envelope))
    }

    // ---------------------------------------------------------------- 往返 ----

    @Test
    fun `v2 信封往返：身份 来源 tombstone 全部保真`() {
        val list = listOf(
            entry(1L, origin = SavedSongOrigin.LOCAL, addedAt = now + 1),
            entry(2L, origin = SavedSongOrigin.REMOTE, tombstoned = true, addedAt = now + 2),
            entry(
                SourceIds.qqId(1234567890L, "0039MnYb0qxYhV"),
                source = MusicSource.QQMUSIC,
                origin = SavedSongOrigin.LOCAL,
                addedAt = now + 3,
                mid = "0039MnYb0qxYhV",
            ),
        )
        val back = SavedSongCodec.decode(SavedSongCodec.encode(list))
        assertEquals(list.map { it.trackKey }, back.map { it.trackKey })
        assertEquals(list.map { it.origin }, back.map { it.origin })
        assertEquals(list.map { it.tombstoned }, back.map { it.tombstoned })
        assertEquals(list.map { it.addedAt }, back.map { it.addedAt })
        // QQ 的 songmid 是载荷，必须活着回来（丢了就取不到链）。
        assertEquals("0039MnYb0qxYhV", back[2].song.sourceId)
        assertEquals(MusicSource.QQMUSIC, back[2].trackKey.source)
    }

    /**
     * ★ 落盘 key 是**字段本名**，不是单字母。
     *
     * 这是 `PersistenceFieldNameContractTest` 的补充：那条防线检查
     * `@SerializedName` 的存在性与取值，这条检查**真正写出去的 JSON**。
     * 字段改名会让用例变红，而不是让用户的收藏消失。
     */
    @Test
    fun `写出去的 JSON key 是字段本名`() {
        val json = SavedSongCodec.encode(listOf(entry(7L, origin = SavedSongOrigin.LOCAL)))
        assertTrue("缺少 trackKey：$json", json.contains("\"trackKey\""))
        assertTrue("缺少 origin：$json", json.contains("\"origin\""))
        assertTrue("缺少 addedAt：$json", json.contains("\"addedAt\""))
        assertTrue("缺少 tombstoned：$json", json.contains("\"tombstoned\""))
        assertTrue("缺少 song：$json", json.contains("\"song\""))
        assertTrue("trackKey 的取值形状变了：$json", json.contains("\"netease:7\""))
        assertTrue("origin 应当是枚举名：$json", json.contains("\"LOCAL\""))
    }

    @Test
    fun `SerializedName 与 stableKeys 一致`() {
        val annotated = SavedSongCodec.SavedSongDto::class.java.declaredFields
            .mapNotNull { it.getAnnotation(SerializedName::class.java)?.value }
        assertEquals(SavedSongCodec.stableKeys().sorted(), annotated.sorted())
        assertEquals(5, SavedSongCodec.stableKeys().size)
    }

    /**
     * v2 信封当前**没有**已知的旧单字母形状 —— 这张表必须为空。
     *
     * 写成断言而不是留一句注释：将来若有人「顺手」往 `LEGACY_KEYS` 里填字母，
     * 他必须同时解释那份单字母数据是哪来的（本仓库到 v2.6.0 为止**没有**）。
     */
    @Test
    fun `v2 信封没有已知的旧单字母形状`() {
        assertTrue(
            "LEGACY_KEYS 非空 —— v2 信封从第一版起就带 @SerializedName，" +
                "不存在被 R8 混淆过的信封数据；填字母前请先给出取证。",
            SavedSongCodec.legacyKeys().isEmpty(),
        )
    }

    // ------------------------------------------------------- 形状 3 / 坏数据 ----

    /** 未知 key 集合 + 5 个字段齐全 ⇒ 按声明顺序兜底读出来。 */
    @Test
    fun `未知 key 集合按声明顺序兜底`() {
        val weird =
            """{"k1":"netease:9","k2":"LOCAL","k3":12345,"k4":true,"k5":{"id":9,"name":"九"}}"""
        val decoded = SavedSongCodec.decode("[$weird]")
        assertEquals(1, decoded.size)
        assertEquals(9L, decoded[0].trackKey.id)
        assertEquals(SavedSongOrigin.LOCAL, decoded[0].origin)
        assertEquals(12345L, decoded[0].addedAt)
        assertTrue(decoded[0].tombstoned)
        assertEquals("九", decoded[0].song.name)
    }

    /**
     * 未知 key 集合但**字段数不足** ⇒ 丢弃那一条，**不按位置硬读**。
     *
     * 按位置硬读一份缺字段的数据会造出「歌名变成歌手名」这类静默错位
     * （`OfflineTrackCodec` 的 `a b e f g i` 缺 `h` 就是同形状的坑）。
     */
    @Test
    fun `未知 key 集合但字段数不足时丢弃该条而不是错位读`() {
        val short = """{"k1":"netease:9","k2":"LOCAL","k3":12345}"""
        assertTrue(SavedSongCodec.decode("[$short]").isEmpty())
    }

    @Test
    fun `坏条目逐条丢弃 其余条目存活`() {
        val good = """{"trackKey":"netease:1","origin":"REMOTE","addedAt":5,"tombstoned":false,"song":{"id":1,"name":"好"}}"""
        val json = "[$good, 42, \"not an object\", {\"trackKey\":\"netease:2\"}, $good]"
        val decoded = SavedSongCodec.decode(json)
        // 第 4 条（只有 trackKey、没有 song）身份拿不到 ⇒ 丢弃；
        // 非对象元素跳过；重复的 good 保留两份（去重是 SavedSongSync 的职责，不是 codec 的）。
        assertEquals(2, decoded.size)
        decoded.forEach { assertEquals(1L, it.trackKey.id) }
    }

    @Test
    fun `顶层不是 JSON 数组时返回空列表而不是抛异常`() {
        assertTrue(SavedSongCodec.decode(null).isEmpty())
        assertTrue(SavedSongCodec.decode("").isEmpty())
        assertTrue(SavedSongCodec.decode("not json at all").isEmpty())
        assertTrue(SavedSongCodec.decode("{\"a\":1}").isEmpty())
        assertTrue(SavedSongCodec.decode("[]").isEmpty())
    }

    /**
     * `trackKey` 缺失/解析不出来时，身份回落到 `TrackKey.ofSong(song)` ——
     * 那里有 **bit62 兜底**，所以「song.source 字符串丢了」的 QQ 条目
     * 仍然会被认成 QQ，而不是被当成 ncm 的同号歌曲。
     */
    @Test
    fun `trackKey 缺失时用 song 回落 且认得出 QQ 的 bit62`() {
        val qqId = SourceIds.qqId(1234567890L, "0039MnYb0qxYhV")
        val json = """[{"origin":"LOCAL","addedAt":1,"tombstoned":false,"song":{"id":$qqId,"name":"晴天"}}]"""
        val decoded = SavedSongCodec.decode(json)
        assertEquals(1, decoded.size)
        assertEquals(MusicSource.QQMUSIC, decoded[0].trackKey.source)
        assertEquals(qqId, decoded[0].trackKey.id)
    }

    /** 未知 `origin` 取值按 `REMOTE` 解释（不是 LOCAL，理由见 `parseOrigin` 的 KDoc）。 */
    @Test
    fun `未知 origin 取值回落到 REMOTE`() {
        val json = """[{"trackKey":"netease:1","origin":"WHATEVER","addedAt":1,"tombstoned":false,"song":{"id":1,"name":"n"}}]"""
        assertEquals(SavedSongOrigin.REMOTE, SavedSongCodec.decode(json).single().origin)
    }

    /** 身份字段也认 `SourceIds.parseTrackKey` 的白名单：未知音源不许猜成 ncm。 */
    @Test
    fun `未知音源前缀的 trackKey 不解析 回落到 song`() {
        val json = """[{"trackKey":"spotify:1","origin":"LOCAL","addedAt":1,"tombstoned":false,"song":{"id":1,"name":"n"}}]"""
        val decoded = SavedSongCodec.decode(json).single()
        assertEquals("未知音源 key 不许猜", MusicSource.NETEASE, decoded.trackKey.source)
        assertEquals(1L, decoded.trackKey.id)
    }

    /** v1 里 `id <= 0` 的脏条目必须被丢掉（它会成为 LazyColumn 的重复 key）。 */
    @Test
    fun `v1 里非法 id 的条目被丢弃`() {
        val json = """[{"id":0,"name":"zero"},{"id":-5,"name":"neg"},{"id":8,"name":"ok"}]"""
        val decoded = SavedSongCodec.decode(json)
        assertEquals(1, decoded.size)
        assertEquals(8L, decoded.single().trackKey.id)
    }

    /** Gson 反序列化 `SongItem` 时不吃 `null` 字段 —— 与 `SongItem` 的 `@SerializedName` 契约同源。 */
    @Test
    fun `song 里缺失的可空字段读成 null 而不是崩`() {
        val json = """[{"trackKey":"netease:3","origin":"REMOTE","addedAt":1,"tombstoned":false,"song":{"id":3,"name":"n"}}]"""
        val decoded = SavedSongCodec.decode(json).single()
        assertNull(decoded.song.source)
        assertNull(decoded.song.sourceId)
        assertNotNull(decoded.song.name)
    }

    /** 空串与 `null` 等价：都当作「没有这份数据」。 */
    @Test
    fun `空串与 null 都返回空列表`() {
        assertEquals(emptyList<SavedSongEntry>(), SavedSongCodec.decode(null))
        assertEquals(emptyList<SavedSongEntry>(), SavedSongCodec.decode(""))
        assertEquals("[]", SavedSongCodec.encode(emptyList()))
        assertTrue(SavedSongCodec.decode("[]").isEmpty())
        // Gson 直读一遍，确认 encode 出来的确实是一个合法 JSON 数组。
        assertNotNull(Gson().fromJson(SavedSongCodec.encode(listOf(entry(1L))), Array<Any>::class.java))
    }
}
