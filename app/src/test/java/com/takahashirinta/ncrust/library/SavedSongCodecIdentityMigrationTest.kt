/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 收藏库**读时身份迁移**的单测（H14 残余 + J10 残余的边界）。
 *
 * - H14：旧 B 站视频腿 id 在解码出口补上位 60，且只补一次；AU 不动。
 * - J10：QQ 的旧散列 id **读不出来真实 songid**，所以解码路径**必须**原样保留
 *   （「宁可不迁移，也不能把两首歌合成一首」）。用例把这条边界钉住，
 *   防止有人「顺手」按 mid 散列去反推、把两条不同的歌合成一条。
 */

package com.takahashirinta.ncrust.library

import com.google.gson.JsonParser
import com.takahashirinta.ncrust.bili.BiliTrack
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SavedSongCodecIdentityMigrationTest {

    private val aid = 117336711367421L
    private val auid = 22760301L
    private val bvid = "BV1EC4y1R7ax"
    private val cid = 25931154534L
    private val bvPayload = "bv:$bvid:$cid"

    /** 旧版本落盘的视频腿 id（无位 60）。 */
    private fun legacyAvId() = SourceIds.biliId(aid)

    /** 现在搜同一首视频曲目会得到的 id（位 60 已置）。 */
    private fun modernAvId() = BiliTrack.numericIdOf(auid = null, aid = aid)

    private fun envelope(
        trackKey: String,
        songId: Long,
        source: String,
        sourceIdValue: String?,
        origin: String = "LOCAL",
    ): String = """
        [{"trackKey":"$trackKey","origin":"$origin","addedAt":1700000000000,"tombstoned":false,
          "song":{"id":$songId,"name":"曲名","dt":180000,"source":"$source",
                  ${if (sourceIdValue == null) "" else "\"mid\":\"$sourceIdValue\","}
                  "al":{"id":9,"name":"专辑"},"ar":[{"id":1,"name":"艺人"}]}}]
    """.trimIndent()

    private fun legacyRawSong(songId: Long, source: String, sourceIdValue: String?): String = """
        [{"id":$songId,"name":"曲名","dt":180000,"source":"$source",
          ${if (sourceIdValue == null) "" else "\"mid\":\"$sourceIdValue\","}
          "al":{"id":9,"name":"专辑"},"ar":[{"id":1,"name":"艺人"}]}]
    """.trimIndent()

    // ------------------------------------------------------------------ H14 ----

    @Test
    fun `H14 旧收藏里的视频腿 id 在解码出口补上位 60`() {
        val json = envelope("bilibili:${legacyAvId()}", legacyAvId(), "bilibili", bvPayload)
        val entry = SavedSongCodec.decode(json).single()

        assertEquals(modernAvId(), entry.trackKey.id)
        assertEquals(
            "载荷 id 必须一起搬，否则 TrackKey.ofSong(song) 会算出第二个身份",
            modernAvId(),
            entry.song.id,
        )
        assertEquals(
            "取链载荷一个字节都不能动（B 站取链只看它）",
            bvPayload,
            entry.song.sourceId,
        )
        assertEquals(MusicSource.BILIBILI, entry.trackKey.source)
    }

    @Test
    fun `H14 v1 裸 SongItem 形状（真实老数据）同样补位`() {
        val json = legacyRawSong(legacyAvId(), "bilibili", bvPayload)
        val entry = SavedSongCodec.decode(json).single()

        assertEquals(modernAvId(), entry.trackKey.id)
        assertEquals(modernAvId(), entry.song.id)
        assertEquals(SavedSongOrigin.REMOTE, entry.origin)
    }

    @Test
    fun `H14 补位只补一次 —— 编码回盘再解码结果不变`() {
        val once = SavedSongCodec.decode(
            envelope("bilibili:${legacyAvId()}", legacyAvId(), "bilibili", bvPayload),
        )
        val twice = SavedSongCodec.decode(SavedSongCodec.encode(once))
        val thrice = SavedSongCodec.decode(SavedSongCodec.encode(twice))

        assertEquals(once.map { it.trackKey }, twice.map { it.trackKey })
        assertEquals(twice.map { it.trackKey }, thrice.map { it.trackKey })
        assertEquals(modernAvId(), thrice.single().trackKey.id)
        assertTrue(
            "重复读不能把位 60 之外的位也改动",
            (thrice.single().trackKey.id and SourceIds.BILI_AV_PARTITION_FLAG) != 0L,
        )
    }

    @Test
    fun `H14 音频区 au 载荷不动`() {
        val auId = SourceIds.biliId(auid)
        val entry = SavedSongCodec.decode(
            envelope("bilibili:$auId", auId, "bilibili", "au:$auid"),
        ).single()

        assertEquals(auId, entry.trackKey.id)
        assertEquals(auId, entry.song.id)
        assertEquals("au:$auid", entry.song.sourceId)
    }

    @Test
    fun `H14 载荷缺失时保持原样 —— 判不出是哪条腿就不迁`() {
        val entry = SavedSongCodec.decode(
            envelope("bilibili:${legacyAvId()}", legacyAvId(), "bilibili", null),
        ).single()

        assertEquals(legacyAvId(), entry.trackKey.id)
        assertEquals(legacyAvId(), entry.song.id)
    }

    @Test
    fun `H14 其它音源的 id 一个都不动`() {
        val ncm = SavedSongCodec.decode(envelope("netease:42", 42L, "netease", null)).single()
        assertEquals(42L, ncm.trackKey.id)

        val qqId = SourceIds.qqId(97_773L, "0039MnYb0qxYhV")
        val qq = SavedSongCodec.decode(
            envelope("qqmusic:$qqId", qqId, "qqmusic", "0039MnYb0qxYhV"),
        ).single()
        assertEquals(qqId, qq.trackKey.id)
        assertEquals(qqId, qq.song.id)
    }

    // ------------------------------------------------------------ J10 边界 ----

    /**
     * J10 残余的**不成立面**：解出来的 QQ 条目里只有 `(合成 id, songmid)`，
     * 数字 songid 在落盘时**没有被保留**（`SongItem` 没有这个字段），
     * 而 `SourceIds.hashSourceId` 是单向散列 —— 真实 songid **不可反推**。
     *
     * 所以解码出口唯一正确的行为是「原样保留」。这条用例的作用是**钉住这个不变量**：
     * 谁要是把「按 mid 散列反推」写成迁移，这里会立刻红（那会把两条不同的歌合并）。
     */
    @Test
    fun `J10 QQ 旧散列 id 可判出但不可迁移 —— 解码必须原样保留`() {
        val mid = "0039MnYb0qxYhV"
        // songid 缺失时的兜底 id：`QQ_ID_FLAG or FNV(mid)`
        val legacy = SourceIds.qqId(0L, mid)
        assertTrue(
            "这确实是「旧散列 id」，判据本身是可用的",
            SourceIds.isSynthesizedQqId(legacy, mid),
        )

        val entry = SavedSongCodec.decode(
            envelope("qqmusic:$legacy", legacy, "qqmusic", mid),
        ).single()

        assertEquals("没有真实 songid 可写，就不许改身份", legacy, entry.trackKey.id)
        assertEquals(legacy, entry.song.id)
        assertEquals(mid, entry.song.sourceId)
    }

    @Test
    fun `J10 QQ 真实 songid 的条目不受影响`() {
        val mid = "0039MnYb0qxYhV"
        val real = SourceIds.qqId(97_773L, mid)
        assertEquals(false, SourceIds.isSynthesizedQqId(real, mid))

        val entry = SavedSongCodec.decode(
            envelope("qqmusic:$real", real, "qqmusic", mid),
        ).single()

        assertEquals(real, entry.trackKey.id)
        assertEquals(real, entry.song.id)
        assertNotEquals(TrackKey(MusicSource.QQMUSIC, SourceIds.qqId(0L, mid)), entry.trackKey)
    }

    @Test
    fun `J10 两条 QQ 条目不会被解码路径合并成一条`() {
        val mid = "0039MnYb0qxYhV"
        val legacy = SourceIds.qqId(0L, mid)
        val real = SourceIds.qqId(97_773L, mid)
        val json = """
            [{"trackKey":"qqmusic:$legacy","origin":"LOCAL","addedAt":1,"tombstoned":false,
              "song":{"id":$legacy,"name":"旧","source":"qqmusic","mid":"$mid"}},
             {"trackKey":"qqmusic:$real","origin":"LOCAL","addedAt":2,"tombstoned":false,
              "song":{"id":$real,"name":"新","source":"qqmusic","mid":"$mid"}}]
        """.trimIndent()

        val decoded = SavedSongCodec.decode(json)
        assertEquals("两条身份不同 ⇒ 必须保持两条，绝不合并", 2, decoded.size)
        assertEquals(listOf(legacy, real), decoded.map { it.trackKey.id })
    }

    /** 形状判别本身不受迁移影响：v1 裸数组仍然不会被误判成信封。 */
    @Test
    fun `迁移不影响形状判别`() {
        val v1 = JsonParser.parseString(
            """{"id":${legacyAvId()},"name":"n","source":"bilibili","mid":"$bvPayload"}""",
        ).asJsonObject
        assertTrue(!SavedSongCodec.isEnvelope(v1))
    }
}
