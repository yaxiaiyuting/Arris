/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.7.9 · H16：续播记录的**撤销**语义。
 *
 * 复审（`docs/reviews/2026-10-27-0643-scheduled-review.md` H16）确认的错法是：
 * `saveSongPosition` 对「位置 < MIN_RESUMABLE_MS」直接 `return`，于是「上次记的 60 秒」
 * 一直留在表里，下次又从 60 秒续播；「3 分钟的记录配 20 秒的曲目」同理。
 *
 * 本用例走**真实持久化入口**（`saveSongPosition` / `getSongPosition` + 真实 prefs 落盘），
 * 不启动播放器、不联网、不模拟整段播放。
 */

package com.takahashirinta.ncrust.player

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class ResumePositionRevocationTest {

    private class MemoryContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
    }

    private lateinit var ctx: MemoryContext

    private fun field(name: String) =
        PlaybackStateManager::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Before
    fun setUp() {
        ctx = MemoryContext()
        // `PlaybackStateManager` 是 object：内存缓存与去抖 Job 都是进程级单例，
        // 每个用例必须拿到一个干净的起点，否则上一条用例的记录会被读进来。
        field("positionCache").set(PlaybackStateManager, null)
        (field("posFlushJob").get(PlaybackStateManager) as? Job)?.cancel()
    }

    @After
    fun tearDown() {
        (field("posFlushJob").get(PlaybackStateManager) as? Job)?.cancel()
        field("positionCache").set(PlaybackStateManager, null)
    }

    /** 等 1s 去抖真的落盘（H16 关心的是「下次启动还能读到」，所以要看到盘上的样子）。 */
    private fun awaitFlush() = runBlocking {
        val job = field("posFlushJob").get(PlaybackStateManager) as? Job
        withTimeoutOrNull(5_000) { job?.join() }
        Unit
    }

    private fun persistedPositions(): Map<Long, PlaybackStateManager.PositionEntry> =
        PlaybackPositionCodec.decode(
            ctx.getSharedPreferences("ncrust_playback_state", 0).getString("song_positions", null),
        )

    @Test
    fun `拖回起点区间会撤销旧记录而不是保留 60 秒`() {
        PlaybackStateManager.saveSongPosition(ctx, 101L, 60_000L)
        assertEquals(60_000L, PlaybackStateManager.getSongPosition(ctx, 101L))

        // 用户把进度拖回 1 秒：这是「显式回到不可续播区间」，旧记录必须被撤销。
        PlaybackStateManager.saveSongPosition(ctx, 101L, 1_000L)
        assertEquals(
            "回退到起点后读回旧续播点 —— 下次开播会从 60 秒跳起（H16 未修）",
            0L,
            PlaybackStateManager.getSongPosition(ctx, 101L),
        )

        // 盘上也不能留着：进程重启后走的是 getQueue/ensurePositionsLoaded 这条读盘路径。
        awaitFlush()
        assertFalse(
            "撤销只改了内存，盘上仍留着这条记录：${persistedPositions().keys}",
            persistedPositions().containsKey(101L),
        )
    }

    @Test
    fun `超出本曲真实时长的记录在写入时撤销（完整曲 3 分钟 → 试听 20 秒）`() {
        PlaybackStateManager.saveSongPosition(ctx, 101L, 180_000L)
        assertEquals(180_000L, PlaybackStateManager.getSongPosition(ctx, 101L))

        // 同一条 songId 这次解析出来的真实时长只有 20 秒：3 分钟的位置已经越界。
        PlaybackStateManager.saveSongPosition(ctx, 101L, 180_000L, durationMs = 20_000L)
        assertEquals(
            "越界记录必须撤销，而不是继续留着 3 分钟",
            0L,
            PlaybackStateManager.getSongPosition(ctx, 101L),
        )
    }

    @Test
    fun `读取时用真实时长复核并撤销越界记录`() {
        PlaybackStateManager.saveSongPosition(ctx, 101L, 180_000L)

        // 读侧复核：接进入口拿到真实时长时，越界记录不得被拿去 seek。
        assertEquals(0L, PlaybackStateManager.getSongPosition(ctx, 101L, durationMs = 20_000L))
        // 而且是真的撤销了 —— 不是「这一次忽略」。
        assertEquals(
            "读取侧的复核只返回了 0，记录还在表里 ⇒ 下一次没有时长信息时又会续播到 3 分钟",
            0L,
            PlaybackStateManager.getSongPosition(ctx, 101L),
        )
    }

    @Test
    fun `时长未知时不误杀正常记录`() {
        // durationMs = 0 = 未知：不做时长校验，只保留「起点区间」那条判据。
        PlaybackStateManager.saveSongPosition(ctx, 101L, 60_000L, durationMs = 0L)
        assertEquals(60_000L, PlaybackStateManager.getSongPosition(ctx, 101L))
        assertEquals(60_000L, PlaybackStateManager.getSongPosition(ctx, 101L, durationMs = 200_000L))
    }

    @Test
    fun `正常进度照旧写盘（撤销逻辑不能把正常记录一起抹掉）`() {
        PlaybackStateManager.saveSongPosition(ctx, 101L, 60_000L, durationMs = 200_000L)
        awaitFlush()
        assertEquals(60_000L, persistedPositions()[101L]?.posMs)
        assertEquals(60_000L, PlaybackStateManager.getSongPosition(ctx, 101L, durationMs = 200_000L))
    }
}
