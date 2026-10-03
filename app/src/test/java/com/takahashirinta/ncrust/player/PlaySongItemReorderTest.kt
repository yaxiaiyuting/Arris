package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `MainActivity.playSongItem` 里**队列重排**那一段的判定（逐行复刻，纯逻辑）。
 *
 * ## 为什么单独立这一份
 *
 * 用户实测报告「播放一首歌时点击其他歌曲不会切换，无论怎么点击都一直播放原来的歌」。
 * 排查时第一个怀疑对象就是这段重排 —— 它同时做三件事：
 * 去重、算出「被点那首」的新位置、重定位 `currentQueueIndex`。任何一步算错，
 * 表现都可能是「点了没反应」。
 *
 * 这条用例的作用是**把这个怀疑对象排除掉**（或抓住它）：
 * 把那段算法逐行复刻成纯函数，断言「被点的那首最终落在哪个下标」。
 * 实测结论：**这段算法是对的** —— 四种情形都指向正确的下标。
 * 因此那个缺陷在**下游**（`PlayerViewModel.playSong` 的取链/快路径、
 * 或 `PlaybackService` 的 intent 处理），不在队列重排。
 *
 * ⚠️ 复刻是**有意**的：真实那段代码在 `MainActivity` 的 composable 作用域里，
 * 依赖 `playbackQueue` / `currentQueueIndex` / `PlayMode` 等一大堆可变状态，无法直接单测。
 * 复刻的代价是「可能与真实代码漂移」—— 所以下面每个用例的期望值都写成
 * **「被点的那首」的语义**（而不是硬编码下标），漂移时更容易被发现。
 */
class PlaySongItemReorderTest {

    private fun song(id: Long, name: String) = SongItem(
        id = id, name = name, artists = null, album = null, duration = 60_000L,
        source = MusicSource.NETEASE.key, sourceId = null, mediaId = null,
    )

    /**
     * 逐行复刻 `MainActivity.playSongItem`（`songKey != currentKey` 那一支 + 末尾定位）。
     *
     * @return 重排后的队列、以及**真实代码会传给 `playFromQueue` 的下标**。
     */
    private fun playSongItem(
        queue: List<SongItem>,
        currentIndex: Int,
        clicked: SongItem,
    ): Pair<List<SongItem>, Int> {
        val currentKey = queue.getOrNull(currentIndex)?.let { QueueKeys.keyOf(it) }
        val songKey = QueueKeys.keyOf(clicked)
        if (songKey == currentKey) return queue to currentIndex

        val filtered = QueueKeys.dedupe(QueueKeys.keysOf(queue), songKey).toMutableList()
        val newCurrentIndex = QueueKeys.indexOfCurrent(filtered, currentKey).coerceAtLeast(0)
            .let { if (currentKey == null) -1 else it }
        val insertPos = (newCurrentIndex + 1).coerceIn(0, filtered.size)
        filtered.add(insertPos, songKey)
        val rebuilt = QueueKeys.rebuild(queue, filtered, listOf(clicked)) ?: queue
        // 真实代码：val idx = playbackQueue.indexOfFirst { QueueKeys.keyOf(it) == songKey }
        val playIdx = rebuilt.indexOfFirst { QueueKeys.keyOf(it) == songKey }
        return rebuilt to playIdx
    }

    /** 判据统一表述为「最终起播的是不是被点的那首」。 */
    private fun assertPlaysClicked(queue: List<SongItem>, currentIndex: Int, clicked: SongItem) {
        val (rebuilt, playIdx) = playSongItem(queue, currentIndex, clicked)
        assertEquals(
            "重排后必须起播被点的那首（队列=${rebuilt.map { it.name }} idx=$playIdx）",
            QueueKeys.keyOf(clicked),
            rebuilt.getOrNull(playIdx)?.let { QueueKeys.keyOf(it) },
        )
    }

    @Test
    fun `队列里已存在的另一首 —— 被点的那首必须起播`() {
        val a = song(1, "A"); val b = song(2, "B"); val c = song(3, "C")
        assertPlaysClicked(listOf(a, b, c), 0, b)
    }

    @Test
    fun `队列里最后一首 —— 被点的那首必须起播`() {
        val a = song(1, "A"); val b = song(2, "B"); val c = song(3, "C")
        assertPlaysClicked(listOf(a, b, c), 0, c)
    }

    @Test
    fun `队列外的新歌 —— 被点的那首必须起播`() {
        val a = song(1, "A"); val b = song(2, "B"); val d = song(4, "D")
        assertPlaysClicked(listOf(a, b), 0, d)
    }

    @Test
    fun `点当前正在播的那首 —— 仍是它`() {
        val a = song(1, "A"); val b = song(2, "B")
        val (rebuilt, playIdx) = playSongItem(listOf(a, b), 0, a)
        assertEquals("点当前曲不应改动队列", listOf(a, b), rebuilt)
        assertEquals("应指向它自己", 0, playIdx)
    }

    @Test
    fun `当前下标指向队尾时点更靠前的一首`() {
        // 边界：currentKey 在 filtered 里的位置不是 0，插入点算错就会落到别处。
        val a = song(1, "A"); val b = song(2, "B"); val c = song(3, "C")
        assertPlaysClicked(listOf(a, b, c), 2, a)
    }

    @Test
    fun `队列只有一个元素时点另一首`() {
        val a = song(1, "A"); val b = song(2, "B")
        assertPlaysClicked(listOf(a), 0, b)
    }

    @Test
    fun `重排后队列里不出现重复身份`() {
        val a = song(1, "A"); val b = song(2, "B")
        val (rebuilt, _) = playSongItem(listOf(a, b), 0, b)
        assertEquals(
            "队列里同一首歌只能有一份（否则 ExoPlayer 会播两遍）",
            rebuilt.size,
            rebuilt.map { QueueKeys.keyOf(it) }.toHashSet().size,
        )
    }
}
