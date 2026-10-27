/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.library

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v3.8.0 · K05 根治：**收藏变更的修订号必须对「外部页面」也可见**。
 *
 * ## 修的是什么（现场）
 *
 * 播放器红心原先由组件局部的 `ui.libraryTick` 驱动重查：
 * `PlayerCard.kt` 的 `remember(song?.id, ui.libraryTick, ui.cardExpandedForInput)`，
 * 而 `libraryTick` **只有** `PlayerCardExpanded.kt` 里播放器自己的收藏按钮会 `++`。
 * 库页 / 搜索页长按收藏改的是同一份数据，却推不动那个 tick —— 播放器缩成 mini bar 时
 * 仍然挂载，于是「在库页取消收藏当前歌 → 展开播放器」会继续显示旧红心。
 *
 * ## 断言的对象
 *
 * `LibraryManager.savedRevision`（`StateFlow<Int>`）：任何写路径改完表都会推进它，
 * 与「谁点的」无关。这里全部用**真实业务入口**（[LibraryManager.saveSong] /
 * [LibraryManager.removeSong]，正是库页 / 搜索页调的那两个）+ 内存 prefs，
 * 不 mock 内部状态。
 *
 * 相关既有用例：[LibrarySessionOwnershipTest]（写意图归属）、
 * [LibraryBoundaryRegressionTest]（跨源边界）、[SavedSongSyncTest]（同步规则）。
 */
class LibrarySavedRevisionTest {

    private class MemoryContext : ContextWrapper(null) {
        private val stores = mutableMapOf<String, MemoryPrefs>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            synchronized(stores) { stores.getOrPut(name) { MemoryPrefs() } }
    }

    private lateinit var ctx: MemoryContext

    private fun field(owner: Any, name: String) =
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    private fun stopFlush() = runBlocking {
        (field(LibraryManager, "flushJob").get(LibraryManager) as? Job)?.let { it.cancel(); it.join() }
    }

    /**
     * 还原 `object LibraryManager` 的内存状态（与本包其它用例同一套手法）：
     * 真源缓存置 null = 「没加载过」⇒ 下一个用例重新读它自己的空盘。
     */
    private fun resetLibrary() {
        stopFlush()
        listOf(
            "cachedEntries", "cachedAlbums", "cachedLikedIds", "pendingAppContext", "flushJob",
        ).forEach { field(LibraryManager, it).set(LibraryManager, null) }
        field(LibraryManager, "likedIdCursor").setInt(LibraryManager, 0)
    }

    @Before
    fun setup() {
        ctx = MemoryContext()
        resetLibrary()
        // 未登录态：`saveSong` / `removeSong` 只写本地表，不发任何云端请求
        // （`isLoggedIn` 为 false ⇒ 不进 pushLike），用例因此是纯本地、可重复的。
        field(CookieManager, "app").set(CookieManager, null)
        CookieManager.init(ctx)
    }

    @After
    fun cleanup() {
        stopFlush()
        CookieManager.clearCookie(ctx)
        field(CookieManager, "app").set(CookieManager, null)
        resetLibrary()
    }

    private fun song(id: Long) =
        SongItem(id = id, name = "fixture-$id", artists = null, album = null, duration = 200_000)

    // ==================== 外部修改 ⇒ 修订推进 ====================

    /**
     * **核心用例**：库页 / 搜索页收藏当前歌（完全不经过播放器）⇒ 修订推进，
     * 且按身份查得到。订阅方（红心叶子）据此重查就能看到新值。
     */
    @Test
    fun `external save from another screen advances the revision`() {
        val before = LibraryManager.savedRevision.value
        assertEquals(SavedSongOutcome.ADDED, LibraryManager.saveSong(ctx, song(9_001)))
        assertTrue(
            "外部收藏没有推进修订（before=$before, now=${LibraryManager.savedRevision.value}）",
            LibraryManager.savedRevision.value > before,
        )
        // 红心真正读的是这两个入口：裸 id 与身份必须同时为真。
        assertTrue(LibraryManager.isSongSaved(ctx, 9_001L))
        assertTrue(LibraryManager.isSongSaved(ctx, TrackKey.of(null, 9_001L)))
    }

    /** 取消收藏（tombstone）同样是一次真表变更 ⇒ 修订推进、红心必须灭。 */
    @Test
    fun `external removal advances the revision and clears the probe`() {
        assertEquals(SavedSongOutcome.ADDED, LibraryManager.saveSong(ctx, song(9_002)))
        val before = LibraryManager.savedRevision.value
        assertTrue(LibraryManager.removeSong(ctx, 9_002L))
        assertTrue(
            "外部取消收藏没有推进修订（before=$before, now=${LibraryManager.savedRevision.value}）",
            LibraryManager.savedRevision.value > before,
        )
        assertFalse(LibraryManager.isSongSaved(ctx, 9_002L))
    }

    /**
     * 幂等写入**不**推进修订：否则「已在库」的重复点击会让每个订阅点白白重算一次
     * （播放器红心、将来的库页角标都在订阅它）。
     */
    @Test
    fun `idempotent save does not advance the revision`() {
        assertEquals(SavedSongOutcome.ADDED, LibraryManager.saveSong(ctx, song(9_003)))
        val afterFirst = LibraryManager.savedRevision.value
        assertEquals(SavedSongOutcome.ALREADY_SAVED, LibraryManager.saveSong(ctx, song(9_003)))
        assertEquals(
            "幂等写入不该推进修订（这才是「变更」的语义）",
            afterFirst,
            LibraryManager.savedRevision.value,
        )
    }

    /**
     * 流本身是可观察的：订阅在改动**之前**建立，改动之后必须收到一次新值。
     * 这条把「播放器的 tick」换成「仓库的流」的**信号通路**钉死 ——
     * 只断言 `value` 变大还不足以证明 `collect` 会被唤醒。
     */
    @Test
    fun `a collector is woken by an edit made outside the player`() = runBlocking {
        val flow = LibraryManager.savedRevision
        val before = flow.value
        val seen = async(Dispatchers.Default) { withTimeout(5_000) { flow.first { it > before } } }
        // 这里扮演「库页」：走仓库入口，与播放器红心毫无关系。
        assertEquals(SavedSongOutcome.ADDED, LibraryManager.saveSong(ctx, song(9_004)))
        val revision = seen.await()
        assertTrue("外部修改后修订没有前进（收到 $revision, before=$before）", revision > before)
        assertTrue(LibraryManager.isSongSaved(ctx, 9_004L))
    }

    /**
     * 按**身份**查，而不是按裸 id：同一串数字放到另一个音源上必须查不到。
     *
     * 判据与 `removeSong` 同源（那边也是 `TrackKey.of(null, songId)`），
     * 所以「亮着的红心点得掉」这件事是结构性的，不是巧合。
     */
    @Test
    fun `identity probe does not leak across sources`() {
        val qqRawId = 4_242L
        val qqId = SourceIds.qqId(qqRawId, "0039MnYb0qxYhV")
        val qqSong = SongItem(
            id = qqId,
            name = "fixture-qq",
            artists = null,
            album = null,
            duration = 200_000,
            source = MusicSource.QQMUSIC.key,
            sourceId = "0039MnYb0qxYhV",
        )
        assertEquals(SavedSongOutcome.ADDED, LibraryManager.saveSong(ctx, qqSong))

        assertTrue("QQ 曲目看不到自己", LibraryManager.isSongSaved(ctx, TrackKey.of(null, qqId)))
        assertFalse(
            "同一串数字被认成了别的音源（身份必须含 source）",
            LibraryManager.isSongSaved(ctx, TrackKey(MusicSource.NETEASE, qqId)),
        )
        // 合成 id 的原始 id 是另一首歌，绝不能命中。
        assertFalse(LibraryManager.isSongSaved(ctx, TrackKey.of(null, qqRawId)))
        // 非正数不是任何真实曲目：拒绝而不是扫表（与 saveSong 的 REJECTED 对称）。
        assertFalse(LibraryManager.isSongSaved(ctx, TrackKey.of(null, 0L)))
    }
}
