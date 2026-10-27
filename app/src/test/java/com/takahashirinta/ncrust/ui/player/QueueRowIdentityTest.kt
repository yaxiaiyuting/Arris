/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.8.0 · E11：队列行的 key / 拖拽身份必须**含音源**（跨源同号不许撞）。
 */

package com.takahashirinta.ncrust.ui.player

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E11 的回归契约：**视觉行的 key 是「哪一首歌」，而「哪一首」必须含音源**。
 *
 * ## 缺陷本体
 *
 * 旧实现是 `RowInfo.key = "s-${song.id}"`，而队列的身份契约是 `TrackKey(source, id)`：
 * `MainActivity` 的 `replaceQueueAndPlay` / 追加路径都**按含音源的 key 去重**，
 * 所以「网易云 12345」与「QQ 12345」**允许同时在场**。于是：
 *
 * | 消费者 | 旧行为 |
 * |---|---|
 * | `LazyColumn(key = …)` | 两行拿到同一个 key（重复 key） |
 * | 拖拽时的隐藏态（`hiddenSongId == song.id`） | 同 id 的**多行同时透明**（只看得出一行"凭空消失"） |
 * | 悬浮卡（`queue.firstOrNull { it.id == id }`） | 可能取到**另一源**那首的图文 |
 *
 * ## 现在的形状
 *
 * 行 key = `s-<TrackKey.tag>#<同曲序号>`：`TrackKey.tag` 是 `netease:12345` /
 * `qqmusic:12345`（身份的唯一格式），序号保证「队列里真的有两首一模一样的歌」也不撞。
 * 拖拽身份（`draggedIdentity` / 隐藏态 `hiddenRowKey`）与提交重定位都换成同一个身份。
 *
 * 本文件直接调用**生产**的 `buildQueueRows`（它为此从 `private` 放宽到 `internal`），
 * 不复制行模型。
 */
class QueueRowIdentityTest {

    private fun song(
        source: MusicSource,
        id: Long,
        name: String,
        mid: String? = null,
    ): SongItem = SongItem(
        id = id,
        name = name,
        artists = null,
        album = null,
        duration = null,
        source = source.key,
        sourceId = mid,
    )

    @Test
    fun `跨源同号的两首歌必须拿到不同的行 key`() {
        val ne = song(MusicSource.NETEASE, 12345L, "网易云的那首")
        val qq = song(MusicSource.QQMUSIC, 12345L, "QQ 的那首", mid = "0039MnYb0qxYhV")
        val rows = buildQueueRows(listOf(ne, qq), currentIndex = 0, infinityActive = false)

        val songRows = rows.filter { it.kind == RowKind.SONG }
        assertEquals("两首都要在行模型里", 2, songRows.size)
        assertNotEquals(
            "同为 12345 但音源不同 ⇒ 必须是两行（旧实现两行都叫 s-12345）",
            songRows[0].key,
            songRows[1].key,
        )
        assertEquals("行 key 必须含音源", "s-netease:12345#0", songRows[0].key)
        assertEquals("行 key 必须含音源", "s-qqmusic:12345#0", songRows[1].key)
    }

    @Test
    fun `整张表的 key 必须唯一 —— LazyColumn 的重复 key 契约`() {
        val queue = listOf(
            song(MusicSource.NETEASE, 1L, "过去"),
            song(MusicSource.NETEASE, 2L, "现在"),
            song(MusicSource.QQMUSIC, 2L, "同号的另一源"),
            song(MusicSource.NETEASE, 3L, "将来"),
            song(MusicSource.QQMUSIC, 3L, "同号的另一源 2"),
        )
        val rows = buildQueueRows(queue, currentIndex = 1, infinityActive = true)
        val keys = rows.map { it.key }
        assertEquals("行 key 不许重复：$keys", keys.size, keys.toSet().size)
    }

    @Test
    fun `同一首歌重复出现时靠序号区分`() {
        val a = song(MusicSource.NETEASE, 7L, "重复的歌")
        val b = song(MusicSource.NETEASE, 7L, "重复的歌")
        val rows = buildQueueRows(listOf(a, b), currentIndex = 0, infinityActive = false)
        val songRows = rows.filter { it.kind == RowKind.SONG }
        assertEquals(listOf("s-netease:7#0", "s-netease:7#1"), songRows.map { it.key })
    }

    @Test
    fun `段标题与占位符的 key 不与歌曲行相撞`() {
        val rows = buildQueueRows(
            listOf(song(MusicSource.NETEASE, 1L, "a"), song(MusicSource.NETEASE, 2L, "b")),
            currentIndex = 0,
            infinityActive = true,
        )
        val headerKeys = rows.filter { it.kind != RowKind.SONG }.map { it.key }
        assertTrue("必须存在段标题", headerKeys.isNotEmpty())
        assertTrue(
            "歌曲行 key 都以 s- 开头，段标题不许落进同一个命名空间",
            headerKeys.none { it.startsWith("s-") },
        )
    }

    @Test
    fun `空队列没有任何行`() {
        assertTrue(buildQueueRows(emptyList(), 0, false).isEmpty())
    }

    @Test
    fun `越界的 currentIndex 仍然收敛到合法行`() {
        val queue = listOf(song(MusicSource.NETEASE, 1L, "a"), song(MusicSource.NETEASE, 2L, "b"))
        val rows = buildQueueRows(queue, currentIndex = 99, infinityActive = false)
        assertTrue("越界索引必须被夹回合法范围", rows.any { it.kind == RowKind.NOW_HEAD && it.queueIndex == 1 })
    }
}
