/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.TrackKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌词加载协调器单测（v2.1.5）。
 *
 * 覆盖的全部是**真机用户报障**对应的时序，不是构造出来的边界：
 *
 * | 用例 | 对应的真机现象 |
 * |---|---|
 * | [stale_response_after_track_change_must_not_land] | 快速切歌时旧歌词盖掉新歌词 |
 * | [cross_source_response_must_not_land] | **QQ 播完自动切 ncm，歌词仍是 QQ 那首** |
 * | [auto_next_marks_loading_before_any_request] | 自动接续瞬间旧歌词残留在屏幕上 |
 * | [shuffle_switch_between_sources_never_lands_old] | 随机播放跨源切歌 |
 * | [offline_cache_cross_source_switch] | 离线缓存曲目跨源切 |
 * | [qq_and_netease_cache_keys_are_disjoint] | 缓存 key 串台 |
 */
class LyricLoadCoordinatorTest {

    private val neteaseA = TrackKey(MusicSource.NETEASE, 5257138L)
    private val neteaseB = TrackKey(MusicSource.NETEASE, 287035L)
    private val qqC = TrackKey(MusicSource.QQMUSIC, SourceIds.qqId(456L, "mid-c"), "mid-c")
    private val qqD = TrackKey(MusicSource.QQMUSIC, SourceIds.qqId(789L, "mid-d"), "mid-d")

    private fun coordinator() = LyricLoadCoordinator()

    // ---------- 1. 旧响应后到，不能覆盖新歌词 ----------

    @Test
    fun stale_response_after_track_change_must_not_land() {
        val c = coordinator()
        c.onTrackChanged(neteaseA)
        val loadA = c.begin(neteaseA)      // A 的请求在飞

        // 用户切到 B：切歌路径先调 onTrackChanged。
        c.onTrackChanged(neteaseB)
        val loadB = c.begin(neteaseB)
        assertTrue("B 的请求必须是当前请求", c.isCurrent(loadB))

        // B 先回来，落地。
        assertTrue(c.accept(loadB, 30))
        assertEquals(LyricLoadCoordinator.State.Loaded(neteaseB, 30), c.state)

        // A 的响应**后到** —— 必须整包丢弃，且不能改动任何状态。
        assertFalse("A 的响应已经过期", c.isCurrent(loadA))
        assertFalse("过期响应不得落地", c.accept(loadA, 55))
        assertEquals(
            "状态必须仍是 B 的",
            LyricLoadCoordinator.State.Loaded(neteaseB, 30), c.state,
        )
    }

    @Test
    fun same_track_retry_invalidates_the_earlier_in_flight_request() {
        val c = coordinator()
        val first = c.begin(neteaseA)
        val second = c.begin(neteaseA)   // 用户点了「重试」
        assertTrue(c.isCurrent(second))
        assertFalse("同一首歌的第一次请求也必须作废（先发的可能后到）", c.isCurrent(first))
        assertFalse(c.accept(first, 10))
        assertTrue(c.accept(second, 12))
    }

    // ---------- 2. 跨源：QQ 播完自动切 ncm ----------

    /**
     * 本版修复的核心场景，也是用户报障的原文：
     * 「qm 歌曲播放完，自动切到 ncm 歌曲，音频已变，但歌词仍显示 QQ 那首」。
     *
     * 关键在于这次响应**不是过期的旧请求** —— 它是当次请求的真实响应，序号新鲜、
     * `currentSongId` 也已经是新歌。唯一错的是它回答的是**另一个音源**的曲目。
     * 所以判据必须带音源，只比序号或只比 id 都拦不住。
     */
    @Test
    fun cross_source_response_must_not_land() {
        val c = coordinator()
        // 正在放 QQ 的 C，歌词已就绪。
        c.onTrackChanged(qqC)
        val loadC = c.begin(qqC)
        assertTrue(c.accept(loadC, 40))

        // C 自然播完 → 自动接续到 ncm 的 B。
        c.onTrackChanged(neteaseB)
        val loadB = c.begin(neteaseB)

        // 此时 C 的响应哪怕「后到」，也必须被拒。
        assertFalse(c.isCurrent(loadC))
        assertFalse(c.accept(loadC, 40))
        assertEquals(LyricLoadCoordinator.State.Loading(neteaseB), c.state)

        assertTrue(c.accept(loadB, 28))
        assertEquals(LyricLoadCoordinator.State.Loaded(neteaseB, 28), c.state)
    }

    /**
     * 反向：ncm → QQ。
     *
     * 这一侧用户不一定报障（症状是「没有歌词」而不是「错歌词」），
     * 但同一处接缝，必须一起钉死。
     */
    @Test
    fun cross_source_reverse_direction_must_not_land() {
        val c = coordinator()
        c.onTrackChanged(neteaseA)
        val loadA = c.begin(neteaseA)

        c.onTrackChanged(qqD)
        val loadD = c.begin(qqD)

        assertFalse(c.isCurrent(loadA))
        assertFalse(c.accept(loadA, 50))
        assertTrue(c.accept(loadD, 33))
        assertEquals(LyricLoadCoordinator.State.Loaded(qqD, 33), c.state)
    }

    /** 自动接续**必须先把状态置成 Loading**，否则旧歌词会残留到新歌词就绪。 */
    @Test
    fun auto_next_marks_loading_before_any_request() {
        val c = coordinator()
        c.onTrackChanged(qqC)
        assertTrue(c.accept(c.begin(qqC), 40))
        assertEquals(LyricLoadCoordinator.State.Loaded(qqC, 40), c.state)

        // 自动接续：只有「切歌了」这一个事实，此刻还没有发任何请求。
        c.onTrackChanged(neteaseB)
        assertEquals(
            "旧歌词不得残留：切歌瞬间就必须是 Loading",
            LyricLoadCoordinator.State.Loading(neteaseB), c.state,
        )
        assertFalse("还没就绪，渲染层不得接受", c.shouldRender(neteaseB))
        assertFalse("更不能接受上一首的", c.shouldRender(qqC))
    }

    // ---------- 3. 随机 / 连续切歌 ----------

    /**
     * 20 次跨源随机切歌（对齐验收标准「跨源队列连续切 20 次」）。
     *
     * 每次切歌后**先**放行一个「上一首的迟到响应」，再放行当前歌的响应 ——
     * 迟到的那个必须永远落不了地。
     */
    @Test
    fun shuffle_switch_between_sources_never_lands_old() {
        val c = coordinator()
        val tracks = listOf(neteaseA, qqC, neteaseB, qqD)
        var previousLoad: LyricLoadCoordinator.Load? = null
        var lastTrack: TrackKey = TrackKey.NONE

        repeat(20) { round ->
            val current = tracks[round % tracks.size]
            lastTrack = current
            c.onTrackChanged(current)
            val load = c.begin(current)

            // 上一首的响应「迟到」——必须被拒，且状态不能被改坏。
            previousLoad?.let { stale ->
                assertFalse("round=$round 迟到响应不得落地", c.accept(stale, 999))
            }
            assertEquals(
                "round=$round 状态必须仍是当前歌的 Loading",
                LyricLoadCoordinator.State.Loading(current), c.state,
            )

            assertTrue("round=$round 当前响应必须落地", c.accept(load, round))
            assertEquals(LyricLoadCoordinator.State.Loaded(current, round), c.state)
            assertTrue("round=$round 渲染判据", c.shouldRender(current))

            previousLoad = load
        }
        // 20 轮之后仍然是「最后一首 + 已就绪」，没有出现「切到最后卡在 Loading」。
        assertEquals(lastTrack, c.currentTrack)
        assertTrue(c.shouldRender(lastTrack))
    }

    // ---------- 4. 离线缓存跨源切 ----------

    /**
     * 离线缓存曲目跨源切换。
     *
     * 离线路径与在线路径共用同一个协调器（缓存命中只是让 [LyricLoadCoordinator.begin]
     * 到落地之间没有网络挂起点），所以这里额外验证「没有挂起点也不能让旧响应落地」：
     * 缓存命中是**同步**落地的，若判据写在挂起点之后就会被整段跳过。
     */
    @Test
    fun offline_cache_cross_source_switch() {
        val c = coordinator()

        // QQ 曲目走离线缓存起播（歌词不落这张表，于是永远没有 QQ 歌词缓存）。
        c.onTrackChanged(qqC)
        val loadC = c.begin(qqC)
        assertEquals(LyricLoadCoordinator.State.Loading(qqC), c.state)

        // 立刻跨源切到 ncm，ncm 那份命中本地缓存、同步落地。
        c.onTrackChanged(neteaseB)
        val loadB = c.begin(neteaseB)
        assertTrue(c.accept(loadB, 41))

        // QQ 那次（无论成功与否）不得改状态。
        assertFalse(c.accept(loadC, 0))
        assertFalse(c.markEmpty(loadC))
        assertEquals(LyricLoadCoordinator.State.Loaded(neteaseB, 41), c.state)
    }

    // ---------- 5. 缓存 key 按音源隔离 ----------

    /**
     * 验收标准「缓存 key 按 source 隔离」。
     *
     * 隔离的来源是 v2.1.0 的**结构性**设计：QQ 的 id 被抬到 `1L shl 62` 以上，
     * 而 ncm 的 id 在十亿量级，两个 id 空间不相交。所以既有的
     * `songId.toString()` 形状本身就已经按音源隔离，不需要给缓存表加音源段
     * （那会让 200 条存量缓存全部失效，白付一次迁移成本）。
     *
     * 这条用例把「结构性隔离」从一句话变成可执行的判据。
     */
    @Test
    fun qq_and_netease_cache_keys_are_disjoint() {
        val c = coordinator()

        // ncm：可缓存，key 就是裸 id。
        assertEquals("5257138", c.cacheKeyOf(neteaseA))
        assertEquals("287035", c.cacheKeyOf(neteaseB))

        // QQ：不进这张表（字段形状是 ncm 的，塞进去要么加迁移要么污染语义）。
        assertNull(c.cacheKeyOf(qqC))
        assertNull(c.cacheKeyOf(qqD))

        // 关键：同号不同源不会算出同一个 key。
        val neteaseSameNumberAsQq = TrackKey(MusicSource.NETEASE, 456L)
        val qq456 = TrackKey(MusicSource.QQMUSIC, SourceIds.qqId(456L, "mid"))
        assertEquals("456", c.cacheKeyOf(neteaseSameNumberAsQq))
        assertNull(c.cacheKeyOf(qq456))
        assertNotEquals(
            c.cacheKeyOf(neteaseSameNumberAsQq),
            c.cacheKeyOf(qq456),
        )
    }

    @Test
    fun cache_key_rejects_non_positive_ids() {
        val c = coordinator()
        assertNull(c.cacheKeyOf(TrackKey.NONE))
        assertNull(c.cacheKeyOf(TrackKey(MusicSource.NETEASE, 0L)))
        assertNull(c.cacheKeyOf(TrackKey(MusicSource.NETEASE, -5L)))
    }

    // ---------- 6. Empty / Error 的区分 ----------

    @Test
    fun empty_and_error_are_different_states() {
        val c = coordinator()
        val load = c.begin(neteaseA)
        // 「确无歌词」是可置灰按钮的权威结论。
        assertTrue(c.markEmpty(load))
        assertEquals(LyricLoadCoordinator.State.Empty(neteaseA), c.state)
        // 它不是 Loaded —— 渲染层不得据此显示上一份歌词。
        assertFalse(c.shouldRender(neteaseA))

        val retry = c.begin(neteaseA)
        // 「这次没拿到」是可重试的，两者用户可见行为相反。
        assertTrue(c.fail(retry, "network"))
        assertEquals(LyricLoadCoordinator.State.Error(neteaseA, "network"), c.state)
    }

    @Test
    fun stale_empty_and_error_are_ignored() {
        val c = coordinator()
        val loadA = c.begin(neteaseA)
        c.onTrackChanged(neteaseB)
        assertFalse(c.markEmpty(loadA))
        assertFalse(c.fail(loadA, "boom"))
        assertEquals(LyricLoadCoordinator.State.Loading(neteaseB), c.state)
    }

    // ---------- 7. 渲梁判据 ----------

    @Test
    fun should_render_only_accepts_current_and_loaded() {
        val c = coordinator()
        assertFalse("还没有曲目时不得渲染", c.shouldRender(null))
        assertFalse(c.shouldRender(neteaseA))

        c.onTrackChanged(neteaseA)
        assertFalse("Loading 不得渲染", c.shouldRender(neteaseA))

        assertTrue(c.accept(c.begin(neteaseA), 3))
        assertTrue(c.shouldRender(neteaseA))
        assertFalse("别的曲目不得渲染", c.shouldRender(neteaseB))
        assertFalse("null 不得渲染", c.shouldRender(null))
    }

    // ---------- 8. invalidate（退出播放器） ----------

    @Test
    fun invalidate_kills_everything() {
        val c = coordinator()
        val load = c.begin(qqC)
        c.invalidate()
        assertEquals(LyricLoadCoordinator.State.Idle, c.state)
        assertNull(c.currentTrack)
        assertFalse(c.isCurrent(load))
        assertFalse(c.accept(load, 1))
    }

    @Test
    fun begin_after_invalidate_still_works() {
        val c = coordinator()
        c.invalidate()
        val load = c.begin(neteaseA)
        assertTrue("作废之后闸门不能卡死", c.isCurrent(load))
        assertTrue(c.accept(load, 7))
    }

    // ---------- 9. 世代单调 ----------

    @Test
    fun generation_is_strictly_monotonic() {
        val c = coordinator()
        val g1 = c.begin(neteaseA).generation
        val g2 = c.begin(neteaseA).generation
        c.onTrackChanged(neteaseB)
        val g3 = c.begin(neteaseB).generation
        val g4 = c.begin(neteaseB).generation
        c.invalidate()
        val g5 = c.begin(neteaseA).generation
        assertTrue(g1 < g2 && g2 < g3 && g3 < g4 && g4 < g5)
    }
}
