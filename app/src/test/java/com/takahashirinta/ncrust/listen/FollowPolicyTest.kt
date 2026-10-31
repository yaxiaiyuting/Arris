package com.takahashirinta.ncrust.listen

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 成员侧跟随判定（v3.8.1）。
 *
 * 这里钉住的是一个真实故障的语义：**本机没在播的歌时也必须跟随**。
 * 旧实现把 `local == null` 当成「无事可做」，导致成员连上房间后永远不开始播放。
 */
class FollowPolicyTest {

    private fun snap(title: String, id: Long? = null, pos: Long = 0, playing: Boolean = true) =
        PlaybackSnapshot(title = title, artist = "", positionMs = pos, playing = playing, songId = id)

    @Test
    fun `本地没有在播的歌时也要起播`() {
        assertFalse("必须判定为换歌 → 触发起播", FollowPolicy.isSameSong(null, snap("屋顶", 1)))
    }

    @Test
    fun `ID 相同就是同一首歌，即使标题不同`() {
        assertTrue(FollowPolicy.isSameSong(snap("屋顶", 42), snap("屋顶 (Live)", 42)))
    }

    @Test
    fun `ID 不同就是不同歌曲，即使标题相同`() {
        assertFalse(FollowPolicy.isSameSong(snap("晴天", 1), snap("晴天", 2)))
    }

    @Test
    fun `任一侧没有 ID 时退回标题比较`() {
        assertTrue(FollowPolicy.isSameSong(snap("晴天"), snap("晴天", 9)))
        assertFalse(FollowPolicy.isSameSong(snap("晴天"), snap("晴天 (Live)", 9)))
    }

    @Test
    fun `偏差在容差内不 seek，超过才 seek`() {
        val local = snap("歌", 1, pos = 10_000)
        assertFalse(FollowPolicy.needsSeek(local, snap("歌", 1, pos = 10_500), 800))
        assertTrue(FollowPolicy.needsSeek(local, snap("歌", 1, pos = 12_000), 800))
    }

    @Test
    fun `本地无歌时不触发 seek（留给起播后再对齐）`() {
        assertFalse(FollowPolicy.needsSeek(null, snap("歌", 1, pos = 60_000), 800))
    }

    @Test
    fun `播放态不一致需要纠正`() {
        assertTrue(FollowPolicy.needsTransportFix(snap("歌", 1, playing = true), snap("歌", 1, playing = false)))
        assertFalse(FollowPolicy.needsTransportFix(snap("歌", 1, playing = true), snap("歌", 1, playing = true)))
        assertFalse(FollowPolicy.needsTransportFix(null, snap("歌", 1, playing = false)))
    }
}
