/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 审查修复回归：R3（部分同步漏接提交失败）、H09（新建歌单写盘失败仍算成功）、
 * H10（读取失败与不完整无法区分）。
 *
 * 三条都用**可注入 commit 失败**的 prefs 夹具，而不是真实磁盘：
 * 缺陷本身就是「commit 返回 false 时代码仍然宣布成功」，所以夹具要能确定性地制造 false。
 * 夹具语义刻意与真机一致：**commit 失败时内存改动已经生效**，只有磁盘没落 ——
 * 否则「失败必须回滚内存」这条根本不需要被测试。
 */

package com.takahashirinta.ncrust.local

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.playlist.PlaylistDegradation
import com.takahashirinta.ncrust.playlist.PlaylistLoadOutcome
import com.takahashirinta.ncrust.qq.QqPlaylistRepository
import com.takahashirinta.ncrust.qq.QqPlaylistStore
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.PlaylistKey
import com.takahashirinta.ncrust.source.SourceIds
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.testsupport.MemoryPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalSyncCommitOutcomeTest {

    /** 可注入 commit 失败 / 读后钩子的 prefs。 */
    private class FaultyPrefs(val memory: MemoryPrefs = MemoryPrefs()) : SharedPreferences by memory {

        /** 下一次 `commit()` 报告失败（只影响一次）。 */
        @Volatile
        var failNextCommit = false

        /** 读到 `tracks:` key 之后执行一次 —— 模拟「同步在途时用户动手编辑」。 */
        @Volatile
        var afterTracksRead: (() -> Unit)? = null

        override fun getString(key: String, defValue: String?): String? {
            val snapshot = memory.getString(key, defValue)
            if (key.startsWith(LocalPlaylistStore.PREFIX_TRACKS)) {
                afterTracksRead?.let { hook -> afterTracksRead = null; hook() }
            }
            return snapshot
        }

        override fun edit(): SharedPreferences.Editor = FaultingEditor(memory.edit(), this)
    }

    /**
     * 只改 `commit()` 的 Editor：其它方法全部委派给内存实现。
     *
     * 注意 `commit()` 里**先 apply 再返回 false**：真实 `SharedPreferences` 在 commit 失败时
     * 内存表已经是新值，本应用正是靠"失败后回滚内存"来避免「本进程看到、冷启动没有」。
     */
    private class FaultingEditor(
        private val delegate: SharedPreferences.Editor,
        private val owner: FaultyPrefs,
    ) : SharedPreferences.Editor by delegate {
        override fun commit(): Boolean {
            val ok = !owner.failNextCommit
            owner.failNextCommit = false
            delegate.apply()
            return ok
        }

        override fun apply() = delegate.apply()
    }

    private class FixtureContext : ContextWrapper(null) {
        val local = FaultyPrefs()
        private val others = mutableMapOf<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            synchronized(others) {
                if (name == LocalPlaylistStore.PREFS) local else others.getOrPut(name) { MemoryPrefs() }
            }
    }

    private val key = PlaylistKey(MusicSource.QQMUSIC, "333", "10001")

    private fun qqSong(n: Long): SongItem = SongItem(
        id = SourceIds.qqId(n, "mid-$n"),
        name = "fixture-$n",
        artists = null,
        album = null,
        duration = 200_000,
        source = MusicSource.QQMUSIC.key,
        sourceId = "mid-$n",
        mediaId = "file-$n",
    )

    /** `QqPlaylistRepository.appContext` 是单例字段：用完必须还原，否则会影响别的测试。 */
    private suspend fun <T> withQqRepository(context: Context, block: suspend () -> T): T {
        val field = QqPlaylistRepository::class.java.getDeclaredField("appContext")
            .apply { isAccessible = true }
        val previous = field.get(QqPlaylistRepository)
        QqPlaylistRepository.init(context)
        return try {
            block()
        } finally {
            field.set(QqPlaylistRepository, previous)
        }
    }

    private fun seedPlaylist(context: Context): LocalPlaylist =
        LocalPlaylistStore.createPlaylist(context, key, "fixture", 1)
            ?: error("夹具歌单没建成")

    /** 一份「降级缓存」：complete=false ⇒ `loadDetail` 带 TRUNCATED，走部分同步分支。 */
    private fun seedDegradedCache(context: Context, songs: List<SongItem>, total: Int) {
        QqPlaylistStore.writeDetail(
            context = context,
            key = key,
            songs = songs,
            savedAt = System.currentTimeMillis(),
            complete = false,
            total = total,
        )
    }

    // ------------------------------------------------------------------ R3 ----

    /**
     * R3 的原始缺陷：部分同步分支**丢掉** `writePlaylistWithTracksIfUnchanged` 的返回值，
     * 写盘失败时仍返回 `Partial(added=N)`，界面就报了一个从未提交的新增数量。
     */
    @Test
    fun failedPartialCommitReturnsFailedInsteadOfPartial() = runBlocking {
        val context = FixtureContext()
        val playlist = seedPlaylist(context)
        seedDegradedCache(context, listOf(qqSong(1)), total = 2)

        withQqRepository(context) {
            context.local.failNextCommit = true
            val outcome = LocalPlaylistRepository.sync(context, playlist, force = false, now = 2000)

            assertEquals("写盘失败 = 失败，不能是 Partial", LocalSyncOutcome.Failed, outcome)
            assertTrue(
                "失败时不得留下未提交的曲目",
                LocalPlaylistStore.readTracks(context, key).isEmpty(),
            )
            assertEquals(
                "失败时 lastSyncedAt 不得推进",
                0L,
                LocalPlaylistStore.readPlaylists(context).single().lastSyncedAt,
            )
        }
    }

    /** 反向对照：提交成功时才是 Partial，且仍然**不推进** lastSyncedAt。 */
    @Test
    fun successfulPartialCommitReportsPartialWithCauseAndKeepsTtl() = runBlocking {
        val context = FixtureContext()
        val playlist = seedPlaylist(context)
        seedDegradedCache(context, listOf(qqSong(1)), total = 2)

        withQqRepository(context) {
            val outcome = LocalPlaylistRepository.sync(context, playlist, force = false, now = 2000)

            assertTrue("降级读取只能是 Partial，拿到 $outcome", outcome is LocalSyncOutcome.Partial)
            outcome as LocalSyncOutcome.Partial
            assertEquals(1, outcome.added)
            assertEquals(1, outcome.total)
            assertEquals(
                "成因必须带回上层，界面才能区分'没网'与'没取全'",
                LocalPartialCause.TRUNCATED,
                outcome.cause,
            )
            assertEquals(1, LocalPlaylistStore.readTracks(context, key).size)
            assertEquals(
                "不完整同步不推进 TTL",
                0L,
                LocalPlaylistStore.readPlaylists(context).single().lastSyncedAt,
            )
        }
    }

    /**
     * 部分同步分支同样要有修订号闸门：同步在途时用户移除了一首，晚到的提交必须被拒。
     *
     * 修复前这条路径**连返回值都不看**，会拿着旧快照把 tombstone 抹掉。
     */
    @Test
    fun userEditDuringPartialSyncIsRejected() = runBlocking {
        val context = FixtureContext()
        val playlist = seedPlaylist(context)
        val first = qqSong(1)
        LocalPlaylistRepository.addTrack(context, key, first, 2)
        seedDegradedCache(context, listOf(first, qqSong(2)), total = 2)

        withQqRepository(context) {
            val captured = CountDownLatch(1)
            val release = CountDownLatch(1)
            // 读到曲目表（即"网络已返回、准备合并"）之后，让用户移除第一首。
            context.local.afterTracksRead = {
                captured.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                LocalPlaylistRepository.removeTrack(context, key, TrackKey.ofSong(first), 3000)
            }
            val syncing = async(Dispatchers.Default) {
                LocalPlaylistRepository.sync(context, playlist, force = false, now = 4000)
            }
            try {
                assertTrue(captured.await(4, TimeUnit.SECONDS))
                release.countDown()
                val outcome = withTimeout(5000) { syncing.await() }
                assertEquals("期间的编辑让提交被拒 ⇒ Failed", LocalSyncOutcome.Failed, outcome)
                val after = LocalPlaylistStore.readTracks(context, key)
                assertTrue(
                    "用户的删除标记必须活下来",
                    after.single { it.trackKey == TrackKey.ofSong(first) }.tombstoned,
                )
                assertTrue("新曲目不得被写入", after.none { it.trackKey.id == SourceIds.qqId(2, "mid-2") })
            } finally {
                release.countDown()
                runCatching { syncing.cancelAndJoin() }
            }
        }
    }

    // ----------------------------------------------------------------- H09 ----

    /**
     * H09：新建入口没有接上写盘失败。
     *
     * 修复前：commit 失败仍返回创建对象，本进程能读到歌单，冷启动（磁盘）里没有。
     * 修复后：返回 null，并且**内存也被回滚** —— 本进程立刻读也读不到，不会自欺。
     */
    @Test
    fun createLocalOnlyReturnsNullAndRollsBackMemoryWhenCommitFails() {
        val context = FixtureContext()
        context.local.failNextCommit = true

        val created = LocalPlaylistRepository.createLocalOnly(context, "fixture", 1000)

        assertNull("写盘失败必须返回 null，调用方据此提示失败", created)
        assertTrue(
            "内存必须回滚：本进程也不该看到这条只在内存里的歌单",
            LocalPlaylistStore.readPlaylists(context).isEmpty(),
        )
        // 同一份存储代表"冷启动后读到的磁盘内容"，它同样是空的。
        assertTrue(LocalPlaylistStore.readPlaylists(context).isEmpty())
    }

    @Test
    fun createLocalOnlyReturnsTheCreatedPlaylistWhenCommitSucceeds() {
        val context = FixtureContext()

        val created = LocalPlaylistRepository.createLocalOnly(context, "fixture", 1000)

        assertNotNull(created)
        assertEquals("fixture", created?.name)
        assertEquals(listOf(created?.key), LocalPlaylistStore.readPlaylists(context).map { it.key })
    }

    /** 仓库层与 Store 层是同一个判据（Store 也不再吞掉 commit 结果）。 */
    @Test
    fun storeCreatePlaylistReturnsNullWhenCommitFails() {
        val context = FixtureContext()
        context.local.failNextCommit = true

        assertNull(LocalPlaylistStore.createPlaylist(context, key, "fixture", 1))
        assertTrue(LocalPlaylistStore.readPlaylists(context).isEmpty())
    }

    // ----------------------------------------------------------------- H10 ----

    /**
     * H10：读取失败必须能和"读到 0 首"区分开。
     *
     * 这里用一个不可能解析成数字的网易 id 让读取在**不发请求**的情况下必然失败：
     * `loadRemoteSongs` 返回 null（失败），而"读到空表"会返回空 List —— 两者形状不同。
     * 同步侧同样必须是 Failed，而不是 Success(added=0)。
     */
    @Test
    fun unreadableNeteaseKeyIsAFailureNotAnEmptySuccess() = runBlocking {
        val context = FixtureContext()
        val neteaseKey = PlaylistKey(MusicSource.NETEASE, "not-a-number", "10001")

        assertNull(
            "失败要为 null（读到空表才是 emptyList）",
            LocalPlaylistRepository.loadRemoteSongs(neteaseKey, 0L, forceRefresh = false),
        )

        val playlist = LocalPlaylistStore.createPlaylist(context, neteaseKey, "fixture", 1)!!
        val outcome = LocalPlaylistRepository.sync(context, playlist, force = false, now = 2000)
        assertEquals(LocalSyncOutcome.Failed, outcome)
        assertEquals(
            "失败不得推进 lastSyncedAt（旧实现会把它当完整成功）",
            0L,
            LocalPlaylistStore.readPlaylists(context).single().lastSyncedAt,
        )
    }

    /** H10 / H21 的映射：QQ 侧的降级标记要变成上层可判的成因，不许回落成"完整"。 */
    @Test
    fun partialCauseMapsEveryDegradation() {
        fun outcome(
            fresh: Boolean,
            degradation: PlaylistDegradation?,
        ) = PlaylistLoadOutcome(Unit, fromCache = !fresh, fresh = fresh, degradation = degradation)

        assertEquals(
            LocalPartialCause.CACHE_FALLBACK,
            LocalPlaylistRepository.partialCauseOf(outcome(false, PlaylistDegradation.OFFLINE)),
        )
        assertEquals(
            LocalPartialCause.NEED_LOGIN,
            LocalPlaylistRepository.partialCauseOf(outcome(false, PlaylistDegradation.NEED_LOGIN)),
        )
        assertEquals(
            LocalPartialCause.TRUNCATED,
            LocalPlaylistRepository.partialCauseOf(outcome(true, PlaylistDegradation.TRUNCATED)),
        )
        assertEquals(
            "不新鲜又没有降级标记 = 上游没说清，仍然不是完整",
            LocalPartialCause.CACHE_FALLBACK,
            LocalPlaylistRepository.partialCauseOf(outcome(false, null)),
        )
        assertEquals(
            LocalPartialCause.UNKNOWN,
            LocalPlaylistRepository.partialCauseOf(outcome(true, null)),
        )
    }
}
