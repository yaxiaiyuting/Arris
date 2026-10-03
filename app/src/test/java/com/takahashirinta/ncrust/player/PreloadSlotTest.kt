/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「待播槽位」不变量单测（v1.5.2 串台修复）。
 *
 * 覆盖的失败场景全部来自真机 logcat 复现（tools/evidence/desync-before.log）：
 * 同一首下一曲被预载两次 → 播放列表 [当前, 下一首, 下一首'] → 播到那个重复项时
 * 元数据已经指向下下首，用户听感是「UI 显示下一首、耳朵还在上一首」。
 */
class PreloadSlotTest {

    // ---------- decide：绝不重复入队 ----------

    @Test
    fun append_when_slot_empty() {
        assertEquals(
            PreloadSlot.Decision.APPEND,
            PreloadSlot.decide(-1L, null, 42L, "u42"),
        )
    }

    @Test
    fun ignore_when_same_song_and_same_url() {
        // 真机复现里的第二次预载：切歌瞬间已入队，最后 60s 窗口又来一次。
        assertEquals(
            PreloadSlot.Decision.IGNORE,
            PreloadSlot.decide(42L, "u42", 42L, "u42"),
        )
    }

    @Test
    fun replace_when_same_song_but_new_url() {
        // 降档重试会拿到新 URL；旧的（可能播不出声）不能留在槽位里。
        assertEquals(
            PreloadSlot.Decision.REPLACE,
            PreloadSlot.decide(42L, "u42-old", 42L, "u42-new"),
        )
    }

    @Test
    fun replace_when_different_song() {
        assertEquals(
            PreloadSlot.Decision.REPLACE,
            PreloadSlot.decide(42L, "u42", 43L, "u43"),
        )
    }

    @Test
    fun fall_back_to_url_when_no_song_id() {
        assertEquals(PreloadSlot.Decision.IGNORE, PreloadSlot.decide(-1L, "u", -1L, "u"))
        assertEquals(PreloadSlot.Decision.REPLACE, PreloadSlot.decide(-1L, "u-old", -1L, "u-new"))
    }

    // ---------- 不变量：重复预载不会让待播项累积 ----------

    /**
     * 用最小模型模拟「当前项 + 待播槽位」的播放列表长度。
     * 只依赖 PreloadSlot.decide —— 与 PlaybackService.preload_next 的接线保持同构。
     */
    private class SlotModel {
        var pendingId = -1L
        var pendingUrl: String? = null
        var playlistSize = 1 // 当前正在播的那一项

        fun preload(id: Long, url: String) {
            when (PreloadSlot.decide(pendingId, pendingUrl, id, url)) {
                PreloadSlot.Decision.APPEND -> playlistSize++
                PreloadSlot.Decision.REPLACE -> Unit // 先移除旧待播项(长度 -1)再追加(+1)
                PreloadSlot.Decision.IGNORE -> return
            }
            pendingId = id
            pendingUrl = url
        }

        /** 当前项自然播完，ExoPlayer 自动切到待播项，并把已播项清出列表。 */
        fun consumePending() {
            playlistSize -= 1
            pendingId = -1L
            pendingUrl = null
        }
    }

    @Test
    fun double_preload_does_not_grow_playlist() {
        val m = SlotModel()
        m.preload(2L, "u2") // 切歌瞬间
        m.preload(2L, "u2") // 进入最后 60s
        assertEquals(2, m.playlistSize)
        m.consumePending()
        assertEquals(1, m.playlistSize)
    }

    @Test
    fun long_playback_sequence_never_accumulates() {
        val m = SlotModel()
        for (id in 2L..6L) {
            val url = "u" + id
            m.preload(id, url) // 切歌瞬间预载
            m.preload(id, url) // 最后 60s 再预载一次
            assertEquals(2, m.playlistSize)
            m.consumePending()
            assertEquals(1, m.playlistSize)
        }
    }

    @Test
    fun replacing_slot_keeps_playlist_length() {
        val m = SlotModel()
        m.preload(2L, "u2")
        m.preload(3L, "u3") // 队列被改动：下一首变成 3
        assertEquals(2, m.playlistSize)
        assertTrue(PreloadSlot.transitionMatches(m.pendingId, m.pendingUrl, 3L, "u3"))
    }

    // ---------- transitionMatches：串台守卫 ----------

    @Test
    fun transition_ok_when_item_matches_slot() {
        assertTrue(PreloadSlot.transitionMatches(42L, "u42", 42L, "u42"))
    }

    @Test
    fun transition_rejected_when_item_is_the_duplicate_previous_song() {
        // 真机最后一条 TRANSITION：itemId=song:25640004(刀马旦) 而槽位是 139774。
        assertFalse(
            PreloadSlot.transitionMatches(139774L, "u139774", 25640004L, "u25640004")
        )
    }

    @Test
    fun transition_rejected_when_slot_empty() {
        assertFalse(PreloadSlot.transitionMatches(-1L, null, 42L, "u42"))
    }

    @Test
    fun transition_url_fallback_without_ids() {
        assertTrue(PreloadSlot.transitionMatches(-1L, "u", null, "u"))
        assertFalse(PreloadSlot.transitionMatches(-1L, "u", null, "other"))
        assertFalse(PreloadSlot.transitionMatches(42L, "u42", null, "u42"))
    }

    // ---------- mediaId 往返 ----------

    @Test
    fun media_id_round_trip() {
        assertEquals("song:42", PreloadSlot.mediaIdFor(42L, "u42"))
        assertEquals(42L, PreloadSlot.songIdFromMediaId("song:42"))
        // 无 id 的预载项退化成 URL，绝不能被解析成歌曲 id。
        assertEquals("u42", PreloadSlot.mediaIdFor(-1L, "u42"))
        assertNull(PreloadSlot.songIdFromMediaId("u42"))
        assertNull(PreloadSlot.songIdFromMediaId(null))
        assertNull(PreloadSlot.songIdFromMediaId("song:0"))
    }

    // ---------- v2.1.5：跨源 ----------

    /** ncm 一侧的 mediaId 形状**逐字节不变**，老媒体项与持久化数据不受影响。 */
    @Test
    fun netease_media_id_shape_is_unchanged() {
        assertEquals("song:42", PreloadSlot.mediaIdFor(MusicSource.NETEASE, 42L, "u42"))
        assertEquals(
            MusicSource.NETEASE to 42L,
            PreloadSlot.identityFromMediaId("song:42"),
        )
    }

    /**
     * QQ 曲目的预载项 mediaId 必须带音源。
     *
     * 旧实现写 `song:<qqId>`，而 qqId 带 `1L shl 62` 标志位 —— 那会被解析成
     * 「ncm 的一首巨大 id 的歌」。后果从「车机按 ncm 取链 404」一直到
     * 「自动接续时无法从 item 回答这是哪个音源的歌」，后者正是跨源歌词串台的源头。
     */
    @Test
    fun qq_media_id_carries_the_source() {
        val qqId = SourceIds.qqId(456L, "0039MnYb0qxYhV")
        val mediaId = PreloadSlot.mediaIdFor(MusicSource.QQMUSIC, qqId, "u456")
        assertEquals("song:qqmusic:$qqId", mediaId)
        assertEquals(
            MusicSource.QQMUSIC to qqId,
            PreloadSlot.identityFromMediaId(mediaId),
        )
        // 绝不能退化成「ncm 的那个大 id」。
        assertNotEquals(
            MusicSource.NETEASE to qqId,
            PreloadSlot.identityFromMediaId(mediaId),
        )
    }

    /** 同号不同源：`netease:123` 与 `qqmusic:123` 在 transition 判定里必须分开。 */
    @Test
    fun transition_rejects_same_number_on_other_source() {
        val qqId = SourceIds.qqId(123L, "mid")
        // 槽位里是 QQ 的 123，起播项却是 ncm 的 123 —— 不是同一首，必须拒绝。
        assertFalse(
            PreloadSlot.transitionMatches(
                MusicSource.QQMUSIC, qqId, "u",
                MusicSource.NETEASE, 123L, "u",
            )
        )
        assertTrue(
            PreloadSlot.transitionMatches(
                MusicSource.QQMUSIC, qqId, "u",
                MusicSource.QQMUSIC, qqId, "u",
            )
        )
    }

    /**
     * 跨源自动接续：QQ 槽位 → 起播项就是槽位里那一首。
     *
     * 这是 v2.1.5 的**核心回归用例**。旧判定只比裸 id，而
     * `songIdFromMediaId("song:qqmusic:…")` 返回 null（`"qqmusic:456".toLongOrNull()` 为 null），
     * 于是 QQ 的预载项**永远过不了守卫** —— gapless 静默失效、元数据停在上一首。
     */
    @Test
    fun cross_source_transition_is_accepted_when_it_matches() {
        val qqId = SourceIds.qqId(456L, "mid-c")
        val qqMediaId = PreloadSlot.mediaIdFor(MusicSource.QQMUSIC, qqId, "uqq")
        val identity = PreloadSlot.identityFromMediaId(qqMediaId)!!
        assertTrue(
            PreloadSlot.transitionMatches(
                MusicSource.QQMUSIC, qqId, "uqq",
                identity.first, identity.second, "uqq",
            )
        )
    }

    /** 起播项反解不出音源（老形状 / 车机插入项）时，带 id 的槽位一律拒绝 —— 宁可不更新也不显示错歌。 */
    @Test
    fun transition_rejects_unresolvable_item_when_slot_has_id() {
        assertFalse(
            PreloadSlot.transitionMatches(
                MusicSource.NETEASE, 42L, "u",
                null, null, "u",
            )
        )
    }

    /** 无 id 的槽位仍退回 URL 比对（既有行为，不因加音源维度而改变）。 */
    @Test
    fun transition_url_fallback_still_works_with_source() {
        assertTrue(
            PreloadSlot.transitionMatches(MusicSource.NETEASE, -1L, "u", null, null, "u")
        )
        assertFalse(
            PreloadSlot.transitionMatches(MusicSource.NETEASE, -1L, "u", null, null, "other")
        )
        assertFalse(
            PreloadSlot.transitionMatches(MusicSource.NETEASE, -1L, null, null, null, "u")
        )
    }
}
