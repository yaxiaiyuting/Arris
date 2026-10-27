/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * H14 残余单测：`SourceIds.migrateBiliAvPartition` —— **纯函数 + 幂等**。
 *
 * 这一层是所有调用点（收藏 codec / 队列 / 单曲状态）共用的唯一判据，
 * 所以「AU 不动」「只补一次」「值域仍在 bili 区间」这三条只需要在这里钉一次；
 * 上层要钉的是「有没有真的调用它」（见
 * `library.SavedSongCodecIdentityMigrationTest` 与
 * `player.PlaybackQueueIdentityMigrationTest`）。
 */

package com.takahashirinta.ncrust.source

import com.takahashirinta.ncrust.bili.BiliTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliAvPartitionMigrationTest {

    /** 真实样本量级：`av117336711367421`（见 `BiliApiDispatchTest` 的取证条目）。 */
    private val aid = 117336711367421L

    /** 音频区样本量级：`au22760301`（见 `SourceIds.BILI_ID_FLAG` 的 KDoc）。 */
    private val auid = 22760301L

    /** 旧版本落盘的视频腿 id：**没有**位 60。 */
    private fun legacyAvId(aid: Long = this.aid): Long = SourceIds.biliId(aid)

    /** 新版本写出的视频腿 id：`biliId(aid or AV_PARTITION_FLAG)`。 */
    private fun modernAvId(aid: Long = this.aid): Long =
        BiliTrack.numericIdOf(auid = null, aid = aid)

    private fun avPayload(bvid: String = "BV1EC4y1R7ax", cid: Long = 25931154534L) = "bv:$bvid:$cid"

    // ------------------------------------------------------------ 常量一致性 ----

    /**
     * `source` 包不能依赖 `bili` 包（那是包级循环），所以分区位在 [SourceIds] 里
     * **镜像**了一份。这条用例就是那份镜像的锁：谁改了 `BiliTrack.AV_PARTITION_FLAG`
     * 而没同步，测试立刻红，而不是让迁移静默失效。
     */
    @Test
    fun `分区位镜像与 BiliTrack 的权威定义逐位相同`() {
        assertEquals(BiliTrack.AV_PARTITION_FLAG, SourceIds.BILI_AV_PARTITION_FLAG)
        assertEquals(1L shl 60, SourceIds.BILI_AV_PARTITION_FLAG)
        assertNotEquals(
            "分区位不能撞上 B 站或 QQ 的标志位",
            SourceIds.BILI_ID_FLAG,
            SourceIds.BILI_AV_PARTITION_FLAG,
        )
        assertNotEquals(SourceIds.QQ_ID_FLAG, SourceIds.BILI_AV_PARTITION_FLAG)
    }

    // ------------------------------------------------------------------ 补位 ----

    @Test
    fun `旧视频腿 id 补上位 60 后与新版解析出的 id 逐位相同`() {
        val legacy = legacyAvId()
        val migrated = SourceIds.migrateBiliAvPartition(legacy, avPayload())

        assertEquals(SourceIds.BILI_AV_PARTITION_FLAG, migrated and SourceIds.BILI_AV_PARTITION_FLAG)
        assertEquals(
            "补位结果必须等于「现在搜同一首视频曲目」得到的 id，否则同一首歌还是两条身份",
            modernAvId(),
            migrated,
        )
        assertNotEquals("旧数据本来就没有这一位", legacy, migrated)
    }

    @Test
    fun `补位只补一次 —— 重复读取不再改写`() {
        val once = SourceIds.migrateBiliAvPartition(legacyAvId(), avPayload())
        val twice = SourceIds.migrateBiliAvPartition(once, avPayload())
        val thrice = SourceIds.migrateBiliAvPartition(twice, avPayload())

        assertEquals(once, twice)
        assertEquals(once, thrice)
    }

    @Test
    fun `补位后仍是合法 B 站 id，且反解回的是分区后的原值`() {
        val migrated = SourceIds.migrateBiliAvPartition(legacyAvId(), avPayload())

        assertTrue(SourceIds.isBiliId(migrated))
        assertEquals(MusicSource.BILIBILI, SourceIds.sourceOfId(migrated))
        assertEquals(aid or SourceIds.BILI_AV_PARTITION_FLAG, SourceIds.biliRawId(migrated))
        assertTrue(
            "补位后反解出的原值必须仍在 biliId 的值域内（< BILI_ID_FLAG）",
            (SourceIds.biliRawId(migrated) ?: Long.MAX_VALUE) < SourceIds.BILI_ID_FLAG,
        )
        assertEquals(SourceIds.BILI_AV_PARTITION_FLAG, migrated and SourceIds.BILI_AV_PARTITION_FLAG)
    }

    // ---------------------------------------------------------------- 不许动 ----

    /** 音频区的 id 历史上就是这个值 —— 改它等于让旧收藏里的 AU 曲目全部失联。 */
    @Test
    fun `AU 载荷一律不动`() {
        val auId = SourceIds.biliId(auid)
        assertEquals(auId, SourceIds.migrateBiliAvPartition(auId, "au:$auid"))
        assertTrue((SourceIds.migrateBiliAvPartition(auId, "au:$auid") and SourceIds.BILI_AV_PARTITION_FLAG) == 0L)
    }

    /** 已经带位 60 的 AU（构造上不会出现，但读外部数据时要原样返回，绝不「纠正」）。 */
    @Test
    fun `已经带分区位的 id 不会被反向剥掉`() {
        val odd = SourceIds.biliId(auid or SourceIds.BILI_AV_PARTITION_FLAG)
        assertEquals(odd, SourceIds.migrateBiliAvPartition(odd, "au:$auid"))
        assertEquals(odd, SourceIds.migrateBiliAvPartition(odd, avPayload()))
    }

    @Test
    fun `载荷缺失或形状不认识时保持原样 —— 宁可不迁也不猜腿`() {
        val legacy = legacyAvId()
        assertEquals(legacy, SourceIds.migrateBiliAvPartition(legacy, null))
        assertEquals(legacy, SourceIds.migrateBiliAvPartition(legacy, ""))
        assertEquals(legacy, SourceIds.migrateBiliAvPartition(legacy, "   "))
        assertEquals(legacy, SourceIds.migrateBiliAvPartition(legacy, "BV1EC4y1R7ax:1"))
        assertEquals(legacy, SourceIds.migrateBiliAvPartition(legacy, "song:1"))
    }

    @Test
    fun `非 B 站 id 一个都不动`() {
        val ncm = 1_959_528_822L
        val qq = SourceIds.qqId(97_773L, "0039MnYb0qxYhV")
        listOf(ncm, qq, 0L, -1L, Long.MAX_VALUE).forEach { id ->
            assertEquals(id, SourceIds.migrateBiliAvPartition(id, avPayload()))
        }
    }

    @Test
    fun `取值域边界：aid 极大时补位后反解仍在 biliId 值域内`() {
        // 位 60 为 0 ⇒ 原值 < 2^60；用最贴近边界的一个值自证「不需要额外守卫」。
        val maxAid = SourceIds.BILI_AV_PARTITION_FLAG - 1L
        val migrated = SourceIds.migrateBiliAvPartition(SourceIds.biliId(maxAid), avPayload())
        val raw = SourceIds.biliRawId(migrated)

        assertEquals(maxAid or SourceIds.BILI_AV_PARTITION_FLAG, raw)
        assertTrue("反解出的原值不能侵占位 61", (raw ?: Long.MAX_VALUE) < SourceIds.BILI_ID_FLAG)
        assertTrue(SourceIds.isBiliId(migrated))
    }

    @Test
    fun `载荷带首尾空白也认（与 BiliTrack parseSourceId 的 trim 一致）`() {
        val legacy = legacyAvId()
        assertEquals(modernAvId(), SourceIds.migrateBiliAvPartition(legacy, "  ${avPayload()}  "))
    }
}
