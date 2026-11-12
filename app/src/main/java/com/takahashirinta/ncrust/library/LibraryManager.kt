/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.library

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.auth.NeteaseSessionSnapshot
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * 本地收藏和网易云同步。编号表供红心状态与分页使用，详情表保留手动添加和取消记录。
 * 用户编辑先更新内存，再落盘和同步。云端读取遵守 [SavedSongSync] 的合并规则。
 */
object LibraryManager {
    private const val PREFS_NAME = "ncrust_library"
    private const val KEY_SONGS = "saved_songs"
    private const val KEY_ALBUMS = "saved_albums"
    private const val KEY_LIKED_IDS = "liked_ids"

    // 收藏单曲分页加载：进页先拉首屏 BATCH 首详情渲染，滚动到底再补下一批。
    const val LIKED_BATCH_SIZE = 50

    private val gson = Gson()
    private val idListType = object : TypeToken<List<Long>>() {}.type

    // 可见列表和落盘 JSON 共享同一份不可变快照。
    private class SavedSongTable(entries: List<SavedSongEntry>) {
        val entries: List<SavedSongEntry> = Collections.unmodifiableList(entries)
        val visibleSongs: List<SongItem> by lazy {
            Collections.unmodifiableList(this.entries.mapNotNull { if (it.isVisible) it.song else null })
        }
        val encoded: String by lazy { SavedSongCodec.encode(this.entries) }
    }

    @Volatile private var savedSongs: SavedSongTable? = null
    private val songsLock = Any()

    private class SavedAlbumTable(entries: List<AlbumInfo>) {
        val entries: List<AlbumInfo> = Collections.unmodifiableList(entries)
        val encoded: String by lazy { SavedAlbumCodec.encode(this.entries) }
    }

    @Volatile private var cachedAlbums: SavedAlbumTable? = null
    private val albumsLock = Any()
    // 分页读原顺序，播放器红心查询用同一快照的二分索引。
    private class LikedIds(ids: List<Long>) {
        val ordered: List<Long> = Collections.unmodifiableList(ids)
        val encoded: String by lazy { gson.toJson(ordered, idListType) }
        private val sorted by lazy {
            val values = LongArray(ordered.size)
            var count = 0
            // Gson 的旧列表中可能含 null。
            for (id: Long? in ordered) if (id != null) values[count++] = id
            val present = if (count == values.size) values else values.copyOf(count)
            present.apply { sort() }
        }

        operator fun contains(id: Long): Boolean =
            if (ordered.size < LIKED_BATCH_SIZE) id in ordered else sorted.binarySearch(id) >= 0
    }

    @Volatile private var cachedLikedIds: LikedIds? = null

    /** 已请求或已有详情的编号前缀，和编号快照一起在 [songsLock] 内读写。 */
    private var likedIdCursor = 0
    private val likedPageLock = Mutex()
    private var likedEditRevision = 0L
    private var appliedLikedRefresh = 0L
    private var appliedAlbumRefresh = 0L
    private val cloudRefreshSequence = AtomicLong()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null
    private var pendingAppContext: Context? = null
    private val flushLock = Any()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读盘 → 内存。**走 [SavedSongCodec]**：它同时认 v1 裸 `SongItem` 数组
     * （v2.6.0 之前的落盘形状）与 v2 信封，并逐条容错。
     */
    private fun ensureSongsLoaded(context: Context): SavedSongTable {
        val current = savedSongs
        if (current != null) return current
        return synchronized(songsLock) {
            savedSongs ?: run {
                val parsed = SavedSongCodec.decode(prefs(context).getString(KEY_SONGS, null))
                SavedSongTable(parsed).also { savedSongs = it }
            }
        }
    }

    private fun ensureAlbumsLoaded(context: Context): SavedAlbumTable {
        val current = cachedAlbums
        if (current != null) return current
        return synchronized(albumsLock) {
            cachedAlbums ?: run {
                // v2.5.5 · A：读路径走 SavedAlbumCodec（认稳定名 / 旧单字母 / 声明顺序），
                // 且**逐条**容错 —— 旧写法 `runCatching { gson.fromJson(...) }` 是
                // 「一个字节坏了，用户收藏的整张专辑表消失」。
                val parsed = SavedAlbumCodec.decode(prefs(context).getString(KEY_ALBUMS, null))
                SavedAlbumTable(parsed).also { cachedAlbums = it }
            }
        }
    }

    private fun ensureLikedIdsLoaded(context: Context): LikedIds {
        val current = cachedLikedIds
        if (current != null) return current
        return synchronized(songsLock) {
            cachedLikedIds ?: run {
                val parsed = runCatching {
                    val json = prefs(context).getString(KEY_LIKED_IDS, null)
                    if (json.isNullOrEmpty()) emptyList<Long>()
                    else (gson.fromJson<List<Long>>(json, idListType) ?: emptyList())
                }.getOrDefault(emptyList())
                LikedIds(parsed).also { cachedLikedIds = it }
            }
        }
    }

    private fun scheduleFlush(context: Context) {
        synchronized(flushLock) {
            pendingAppContext = context.applicationContext
            flushJob?.cancel()
            flushJob = ioScope.launch {
                delay(300L)
                flushToDisk()
            }
        }
    }

    /**
     * 立即落盘（**跳过 300ms 去抖**）。
     *
     * 「加入库」这类**用户明确动作**走这条路：去抖窗口内进程被杀是真实场景
     * （用户加完歌立刻从最近任务里划掉），而 300ms 的去抖是为高频批量写
     * （分页补详情）设计的，用在单次用户动作上只买到「一次可能的数据丢失」。
     * 仍然在 IO 线程上、仍然不阻塞调用方。
     */
    private fun flushNow(context: Context) {
        synchronized(flushLock) {
            pendingAppContext = context.applicationContext
            flushJob?.cancel()
            flushJob = ioScope.launch { flushToDisk() }
        }
    }

    /** 抓取已加载表的快照，尚未加载的偏好键保留原值。 */
    private fun flushToDisk() {
        // A canceled IO job may already be saving: serialize snapshot + write, not just ctx handoff.
        synchronized(flushLock) {
            val ctx = pendingAppContext ?: return
            pendingAppContext = null
            val (songsSnapshot, likedIdsSnapshot) = synchronized(songsLock) { savedSongs to cachedLikedIds }
            val albumsSnapshot = synchronized(albumsLock) { cachedAlbums }
            runCatching {
                val editor = prefs(ctx).edit()
                songsSnapshot?.let { editor.putString(KEY_SONGS, it.encoded) }
                albumsSnapshot?.let { editor.putString(KEY_ALBUMS, it.encoded) }
                likedIdsSnapshot?.let { editor.putString(KEY_LIKED_IDS, it.encoded) }
                editor.apply()
            }
        }
    }

    /**
     * 预热内存缓存：在 IO 线程把收藏单曲/专辑/红心 id 从 SharedPreferences 解析进内存。
     * 避免首次在组合期（主线程）调用 [getSavedSongs] / [isSongSaved] 时同步读盘 + Gson 卡顿。
     */
    fun preload(context: Context) {
        val app = context.applicationContext
        ioScope.launch {
            ensureSongsLoaded(app)
            ensureAlbumsLoaded(app)
            ensureLikedIdsLoaded(app)
        }
    }

    private fun isLoggedIn(context: Context) = CookieManager.hasCookie(context)

    /** 网易云收藏按曲目串行，只发送原登录下仍有效的最新意图。 */
    private fun pushLike(songId: Long, like: Boolean) {
        if (!SavedSongSync.isRemoteLikeEligible(songId)) {
            Log.w(TAG, "pushLike blocked: cross-source id=$songId like=$like")
            return
        }
        // 归属判据是**登录代次**，不是 cookie 字节：同账号续期只换票（revision 变、
        // 代次不变），那条意图仍然属于同一个账号，不能被当成"换号"丢掉。
        latestLikeIntent[songId] = LikeIntent(
            like = like,
            seq = likeIntentSeq.incrementAndGet(),
            ownerGeneration = CookieManager.snapshot().loginGeneration,
        )
        ioScope.launch {
            val mutex = likeMutexes.getOrPut(songId) { Mutex() }
            mutex.withLock {
                while (true) {
                    val intent = latestLikeIntent[songId] ?: return@withLock
                    // 每轮都取当前快照：同账号续期后要拿新票发，旧票会被服务端 301。
                    val live = CookieManager.snapshot()
                    if (live.loginGeneration != intent.ownerGeneration) {
                        // 换号/登出：只条件删除"自己领取的那一条"。期间别人写过新意图 ⇒
                        // remove 失败（值不同），那条留给它自己的 worker，不在这里陪葬。
                        latestLikeIntent.remove(songId, intent)
                        Log.w(TAG, "pushLike aborted: session changed id=$songId like=${intent.like}")
                        return@withLock
                    }
                    val sent = runCatching { PlaylistApi.likeSong(songId, intent.like, live) }
                        .getOrDefault(false)
                    // 自己仍是最新一条 ⇒ 收工；否则说明期间又点了（或换了账号又点），继续补发。
                    if (latestLikeIntent.remove(songId, intent)) {
                        if (!sent) Log.w(TAG, "pushLike failed id=$songId like=${intent.like}")
                        return@withLock
                    }
                }
            }
        }
    }

    /**
     * 一条待写的收藏意图。`seq` 全局递增 ⇒ 两条意图永不相等，
     * `ConcurrentHashMap.remove(key, value)` 才能精确表达"删掉我领取的那一条"。
     */
    private data class LikeIntent(val like: Boolean, val seq: Long, val ownerGeneration: Long)

    /** 每首歌的写入门闩（串行化同一曲目的收藏/取消）。 */
    private val likeMutexes = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
    /** 每首歌的最新意图（带归属）；被覆盖的旧意图不再发往云端。 */
    private val latestLikeIntent = java.util.concurrent.ConcurrentHashMap<Long, LikeIntent>()
    /** 意图发布序号（R1）：让条件删除成为"按值判定"，而不是"按 key 清空"。 */
    private val likeIntentSeq = AtomicLong()

    /**
     * 把「收藏/取消专辑」推到云端 —— **与 [pushLike] 同构**（H01）。
     *
     * 旧实现每次独立 `ioScope.launch`：先 sub 后 unsub 时两条请求并发，迟到的那条
     * （可能是旧的 sub）最后落地，云端最终状态与用户最后一次点击相反。这里按专辑串行 +
     * 最新意图覆盖 + 钉住登录代次，三条不变量与单曲收藏完全一致。
     */
    private fun pushSubAlbum(albumId: Long, sub: Boolean) {
        latestAlbumIntent[albumId] = AlbumSubIntent(
            sub = sub,
            seq = albumIntentSeq.incrementAndGet(),
            ownerGeneration = CookieManager.snapshot().loginGeneration,
        )
        ioScope.launch {
            val mutex = albumMutexes.getOrPut(albumId) { Mutex() }
            mutex.withLock {
                while (true) {
                    val intent = latestAlbumIntent[albumId] ?: return@withLock
                    val live = CookieManager.snapshot()
                    if (live.loginGeneration != intent.ownerGeneration) {
                        latestAlbumIntent.remove(albumId, intent)
                        Log.w(TAG, "pushSubAlbum aborted: session changed albumId=$albumId sub=${intent.sub}")
                        return@withLock
                    }
                    val sent = runCatching { PlaylistApi.subAlbum(albumId, intent.sub, live) }
                        .getOrDefault(false)
                    if (latestAlbumIntent.remove(albumId, intent)) {
                        if (!sent) Log.w(TAG, "pushSubAlbum failed albumId=$albumId sub=${intent.sub}")
                        return@withLock
                    }
                }
            }
        }
    }

    /** 一条待写的专辑订阅意图（H01），字段语义同 [LikeIntent]。 */
    private data class AlbumSubIntent(val sub: Boolean, val seq: Long, val ownerGeneration: Long)

    private val albumMutexes = java.util.concurrent.ConcurrentHashMap<Long, Mutex>()
    private val latestAlbumIntent = java.util.concurrent.ConcurrentHashMap<Long, AlbumSubIntent>()
    private val albumIntentSeq = AtomicLong()

    // ==================== 可观察修订（v3.8.0 · K05 根治） ====================

    private val savedRevisionState = MutableStateFlow(0)

    /** 收藏状态更新后通知播放器红心等读者。 */
    val savedRevision: StateFlow<Int> = savedRevisionState.asStateFlow()

    /** 在内存更新完成后、锁外调用。 */
    private fun bumpSavedRevision() {
        savedRevisionState.update { it + 1 }
    }

    // ==================== 单曲（收藏） ====================

    /** 添加或恢复收藏。重复添加保持条目原有顺序和来源。 */
    fun saveSong(context: Context, song: SongItem): SavedSongOutcome {
        if (song.id <= 0L) {
            // 不是任何真实曲目：它连身份都不成立（`SourceIds.mediaId` 同样会拒绝
            // `id <= 0`）。**不写入、不弹成功**。
            Log.w(TAG, "saveSong rejected: id=${song.id} name=${song.name}")
            return SavedSongOutcome.REJECTED
        }
        val key = TrackKey.ofSong(song)
        val changed = synchronized(songsLock) {
            val before = ensureSongsLoaded(context).entries
            val after = SavedSongSync.addManual(before, song, System.currentTimeMillis())
            if (after === before) return@synchronized false
            savedSongs = SavedSongTable(after)
            if (key.source == MusicSource.NETEASE) {
                val ids = ensureLikedIdsLoaded(context)
                if (key.id !in ids) prependLikedIds(ids, listOf(key.id))
                likedEditRevision++
            }
            true
        }
        if (changed) {
            bumpSavedRevision()
            flushNow(context)
            if (isLoggedIn(context)) pushLike(key.id, true)
            return SavedSongOutcome.ADDED
        }
        return SavedSongOutcome.ALREADY_SAVED
    }

    fun saveSongs(context: Context, newSongs: List<SongItem>) {
        if (newSongs.isEmpty()) return
        val added = mutableListOf<SongItem>()
        synchronized(songsLock) {
            val before = ensureSongsLoaded(context).entries
            val after = SavedSongSync.addManualAll(before, newSongs, System.currentTimeMillis())
            if (after !== before) {
                // 恢复此前已取消的收藏也要同步给云端。
                val visibleBefore = before.asSequence()
                    .filter { it.isVisible }
                    .mapTo(HashSet(before.size)) { it.trackKey }
                savedSongs = SavedSongTable(after)
                added += newSongs.filter { TrackKey.ofSong(it) !in visibleBefore }
                val ncmIds = added.mapNotNull {
                    val key = TrackKey.ofSong(it)
                    if (key.source == MusicSource.NETEASE) key.id else null
                }
                if (ncmIds.isNotEmpty()) {
                    val ids = ensureLikedIdsLoaded(context)
                    val fresh = ncmIds.filterNot { it in ids }
                    if (fresh.isNotEmpty()) prependLikedIds(ids, fresh)
                    likedEditRevision++
                }
            }
        }
        if (added.isNotEmpty()) {
            // K05：只有**可见表真的多了条目**才推进修订。
            bumpSavedRevision()
            flushNow(context)
            if (isLoggedIn(context)) added.forEach { pushLike(TrackKey.ofSong(it).id, true) }
        }
    }

    // 调用方持有 songsLock。手动加入的编号已有详情，未读部分随前缀一起后移。
    private fun prependLikedIds(ids: LikedIds, fresh: List<Long>) {
        cachedLikedIds = LikedIds(fresh + ids.ordered)
        likedIdCursor += fresh.size
    }

    /** 移除收藏并留下取消记录。返回本次操作是否改变收藏状态。 */
    fun removeSong(context: Context, songId: Long): Boolean {
        // 调用点传的是裸 `song.id`（SongItem 的 id 自带 bit62 标志），
        // 所以音源可以直接从 id 推出来，不需要额外参数。
        val key = TrackKey.of(null, songId)
        val changed = synchronized(songsLock) {
            val before = ensureSongsLoaded(context).entries
            val ids = if (key.source == MusicSource.NETEASE) ensureLikedIdsLoaded(context) else null
            val changedInIds = ids != null && key.id in ids
            var after = SavedSongSync.remove(before, key)
            if (ids != null && changedInIds) {
                likedIdCursor -= ids.ordered.subList(0, likedIdCursor).count { it == key.id }
                cachedLikedIds = LikedIds(ids.ordered.filterNot { it == key.id })
                if (after === before && before.none { it.trackKey == key }) {
                    // 底表有编号但详情未加载时，仍保留一条只用于判重的取消记录。
                    val removed = SavedSongEntry(
                        trackKey = key,
                        origin = SavedSongOrigin.REMOTE,
                        addedAt = System.currentTimeMillis(),
                        tombstoned = true,
                        song = SongItem(key.id, "", null, null, null),
                    )
                    after = SavedSongSync.boundTombstones(before + removed)
                }
            }
            if (after !== before) savedSongs = SavedSongTable(after)
            val changed = after !== before || changedInIds
            if (changed && ids != null) likedEditRevision++
            changed
        }
        if (changed) {
            // K05：取消收藏同样是一次真表变更（tombstone），红心必须跟着灭。
            bumpSavedRevision()
            flushNow(context)
            if (isLoggedIn(context)) pushLike(songId, false)
        }
        return changed
    }

    /** 可见（未被 tombstone）的收藏单曲，按表内顺序。 */
    fun getSavedSongs(context: Context): List<SongItem> = ensureSongsLoaded(context).visibleSongs

    /** 带来源的只读视图（诊断 / 单测 / 将来的 UI 角标用）。 */
    fun getSavedSongEntries(context: Context): List<SavedSongEntry> = ensureSongsLoaded(context).entries

    /** 网易云先查完整编号，再查已加载详情。其他音源按本地详情判断。 */
    fun isSongSaved(context: Context, key: TrackKey): Boolean {
        // `id <= 0` 不是任何真实曲目（[saveSong] 同样拒绝），不必进锁扫表。
        if (key.id <= 0L) return false
        if (key.source == MusicSource.NETEASE) {
            val ids = ensureLikedIdsLoaded(context)
            if (ids.contains(key.id)) return true
        }
        return ensureSongsLoaded(context).entries.any { it.isVisible && it.trackKey == key }
    }

    fun isSongSaved(context: Context, songId: Long): Boolean =
        isSongSaved(context, TrackKey.of(null, songId))

    fun getSongsByAlbumId(context: Context, albumId: Long): List<SongItem> =
        getSavedSongs(context).filter { it.album?.id == albumId }

    // ==================== 专辑（云端收藏） ====================

    /** 收藏页「专辑」栏：返回云端「收藏的专辑」（album_sublist 缓存）。纯云端，不派生。 */
    fun getSavedAlbums(context: Context): List<AlbumInfo> = ensureAlbumsLoaded(context).entries

    /**
     * 专辑表的**本地编辑修订号**（R2）。
     *
     * `refreshFromCloud` 的专辑段在锁外用 `PlaylistApi.getSubscribedAlbums()` 取快照，
     * 那份快照**可能老于**读取期间发生的 subscribe / remove。旧实现拿它整体替换
     * `cachedAlbums`，于是「刚收藏成功的专辑」被一份旧的空列表撤销，内存与 `saved_albums`
     * 一起丢 —— 锁只保护赋值，不保证快照比用户操作新。
     *
     * 修订号与改表在同一把 [albumsLock] 内完成，落库前复核「读出时的修订号 == 当前修订号」：
     * 不等就保留本地真相并记日志，旧快照永远无权撤销新编辑。取消收藏走同一条判据（对称）。
     */
    private val albumEditRevision = AtomicLong()

    /** 收藏专辑（订阅云端）：先落本地缓存，再异步同步云端。 */
    fun subscribeAlbum(context: Context, album: AlbumInfo) {
        synchronized(albumsLock) {
            // 先推进修订号再改表：refreshFromCloud 的复核读的是同一个计数。
            albumEditRevision.incrementAndGet()
            val previous = ensureAlbumsLoaded(context).entries
            val albums = ArrayList<AlbumInfo>(previous.size + 1)
            albums.add(album)
            for (saved in previous) if (saved.albumId != album.albumId) albums.add(saved)
            cachedAlbums = SavedAlbumTable(albums)
        }
        scheduleFlush(context)
        if (isLoggedIn(context)) pushSubAlbum(album.albumId, true)
    }

    /** 取消收藏专辑：仅移除专辑订阅（不影响收藏单曲，二者解耦）。 */
    fun removeAlbum(context: Context, albumId: Long) {
        val removed: Boolean
        synchronized(albumsLock) {
            val albums = ensureAlbumsLoaded(context).entries
            removed = albums.any { it.albumId == albumId }
            if (removed) {
                cachedAlbums = SavedAlbumTable(albums.filterNot { it.albumId == albumId })
                albumEditRevision.incrementAndGet()
            }
        }
        if (removed) {
            scheduleFlush(context)
            if (isLoggedIn(context)) pushSubAlbum(albumId, false)
        }
    }

    // ==================== 云端同步 ====================

    /** 单曲和专辑分别提交。读取失败时保留该栏目的本地数据。 */
    suspend fun refreshFromCloud(context: Context): Boolean = withContext(Dispatchers.IO) {
        CookieManager.init(context)
        val session = CookieManager.snapshot()
        if (session.cookie.isNullOrBlank()) return@withContext false
        val sequence = cloudRefreshSequence.incrementAndGet()
        val uid = try {
            PlaylistApi.getCurrentUserId()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromCloud: getCurrentUserId failed: ${e.message}")
            return@withContext false
        }

        if (CookieManager.snapshot().loginGeneration != session.loginGeneration) return@withContext false
        val songs = try {
            refreshCloudSongs(context, uid, session, sequence)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromCloud: liked songs failed: ${e.message}")
            false
        }
        if (songs) scheduleFlush(context)

        if (CookieManager.snapshot().loginGeneration != session.loginGeneration) return@withContext songs
        val albums = try {
            refreshCloudAlbums(session, sequence)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromCloud: albums failed: ${e.message}")
            false
        }
        if (albums) scheduleFlush(context)
        songs || albums
    }

    private suspend fun refreshCloudSongs(
        context: Context,
        uid: Long,
        session: NeteaseSessionSnapshot,
        sequence: Long,
    ): Boolean {
        val revision = synchronized(songsLock) { likedEditRevision }
        val remoteIds = when (val result = PlaylistApi.getLikedTrackIds(uid)) {
            is PlaylistApi.LikedIdsResult.Success -> result.ids
            is PlaylistApi.LikedIdsResult.Failure -> {
                Log.w(TAG, "refreshFromCloud: liked songs 读取失败：${result.reason}")
                return false
            }
        }

        val canFetchDetails = synchronized(songsLock) {
            revision == likedEditRevision && sequence > appliedLikedRefresh
        }
        if (!canFetchDetails || CookieManager.snapshot().loginGeneration != session.loginGeneration) return false

        val tombstoned = ensureSongsLoaded(context).entries.asSequence()
            .filter { it.tombstoned && it.trackKey.source == MusicSource.NETEASE }
            .map { it.trackKey.id }
            .toHashSet()
        val ids = if (tombstoned.isEmpty()) remoteIds else remoteIds.filterNot { it in tombstoned }
        val firstBatch = if (ids.size > LIKED_BATCH_SIZE) ids.take(LIKED_BATCH_SIZE) else ids
        val firstSongs = if (firstBatch.isEmpty()) emptyList() else PlaylistApi.getSongsByIds(firstBatch)
        val freshById = firstSongs.associateBy { it.id }
        val caller = currentCoroutineContext()

        val changed = CookieManager.ifSameLogin(session) {
            synchronized(songsLock) {
                caller.ensureActive()
                if (revision != likedEditRevision || sequence <= appliedLikedRefresh) return@synchronized null

                val table = ensureSongsLoaded(context)
                val after = SavedSongSync.merge(table.entries, ids, freshById, System.currentTimeMillis())
                if (after !== table.entries) savedSongs = SavedSongTable(after)
                val covered = after.asSequence()
                    .filter { it.trackKey.source == MusicSource.NETEASE }
                    .map { it.trackKey.id }
                    .toHashSet()
                val firstMissing = ids.indexOfFirst { it !in covered }
                val cursor = if (firstMissing < 0) ids.size else firstMissing
                val idsChanged = cachedLikedIds?.ordered != ids
                if (idsChanged || cursor != likedIdCursor) cachedLikedIds = LikedIds(ids)
                likedIdCursor = cursor
                appliedLikedRefresh = sequence
                after !== table.entries || idsChanged
            }
        } ?: return false

        if (changed) bumpSavedRevision()
        return true
    }

    private suspend fun refreshCloudAlbums(session: NeteaseSessionSnapshot, sequence: Long): Boolean {
        val revision = albumEditRevision.get()
        val albums = PlaylistApi.getSubscribedAlbums().map {
            AlbumInfo(
                albumId = it.albumId,
                name = it.name,
                picUrl = it.picUrl,
                artist = it.artist,
                songCount = it.songCount,
            )
        }
        val caller = currentCoroutineContext()
        return CookieManager.ifSameLogin(session) {
            synchronized(albumsLock) {
                caller.ensureActive()
                if (revision != albumEditRevision.get() || sequence <= appliedAlbumRefresh) return@synchronized false
                if (cachedAlbums?.entries != albums) cachedAlbums = SavedAlbumTable(albums)
                appliedAlbumRefresh = sequence
                true
            }
        } ?: false
    }

    /** 网易云收藏的有序编号，包含尚未加载详情的曲目。 */
    fun getLikedSongIds(context: Context): List<Long> = ensureLikedIdsLoaded(context).ordered

    /**
     * 分页拉取下一批收藏单曲详情（滚动到底时调用）。会追加进本地缓存并返回新渲染项。
     */
    suspend fun loadMoreLikedSongs(context: Context): List<SongItem> = withContext(Dispatchers.IO) {
        likedPageLock.withLock {
            val (ids, start) = synchronized(songsLock) { ensureLikedIdsLoaded(context) to likedIdCursor }
            if (start >= ids.ordered.size) return@withLock emptyList()
            val end = minOf(start + LIKED_BATCH_SIZE, ids.ordered.size)
            val slice = ids.ordered.subList(start, end).filterNotNull()
            val fetched = PlaylistApi.getSongsByIdsOrNull(slice) ?: return@withLock emptyList()

            val caller = currentCoroutineContext()
            val (table, changed) = synchronized(songsLock) {
                caller.ensureActive()
                if (cachedLikedIds !== ids) return@withLock emptyList()
                val before = ensureSongsLoaded(context)
                // 只追加详情，已展示的曲目保持原顺序。
                val after = SavedSongSync.appendRemote(before.entries, fetched, System.currentTimeMillis())
                likedIdCursor = end
                if (after === before.entries) before to false else {
                    SavedSongTable(after).also { savedSongs = it } to true
                }
            }
            if (changed) {
                bumpSavedRevision()
                scheduleFlush(context)
            }
            table.visibleSongs
        }
    }

    /**
     * 分批补齐「播放全部」所需的详情。按 [selectLikedPlayAll] 合并本地条目和网易云顺序，
     * 并把仍缺详情的编号返回给界面。
     */
    suspend fun loadAllLikedSongsResult(context: Context): LikedPlayAllSelection =
        withContext(Dispatchers.IO) {
            val (initialIds, initialEntries) = synchronized(songsLock) {
                ensureLikedIdsLoaded(context) to ensureSongsLoaded(context).entries
            }
            val tombstoned = HashSet<Long>()
            val byId = HashMap<Long, SongItem>()
            for (entry in initialEntries) {
                if (entry.trackKey.source != MusicSource.NETEASE) continue
                val id = entry.trackKey.id
                if (entry.tombstoned) tombstoned.add(id) else byId[id] = entry.song
            }
            val missing = initialIds.ordered.asSequence().filterNotNull()
                .filter { it !in tombstoned && it !in byId }.distinct().toList()
            for (start in missing.indices step PLAY_ALL_BATCH_SIZE) {
                val batch = missing.subList(start, minOf(start + PLAY_ALL_BATCH_SIZE, missing.size))
                val fetched = try {
                    PlaylistApi.getSongsByIds(batch)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "loadAllLikedSongs batch failed: ${e.message}")
                    emptyList()
                }
                if (fetched.isNotEmpty()) {
                    val caller = currentCoroutineContext()
                    val changed = synchronized(songsLock) {
                        caller.ensureActive()
                        val currentIds = ensureLikedIdsLoaded(context)
                        val accepted = if (currentIds === initialIds) fetched else fetched.filter { it.id in currentIds }
                        val before = ensureSongsLoaded(context).entries
                        val after = SavedSongSync.appendRemote(before, accepted, System.currentTimeMillis())
                        if (after !== before) savedSongs = SavedSongTable(after)
                        after !== before
                    }
                    if (changed) bumpSavedRevision()
                }
                if (start + PLAY_ALL_BATCH_SIZE < missing.size) delay(PLAY_ALL_BATCH_DELAY_MS)
            }
            val (currentIds, currentEntries) = synchronized(songsLock) {
                ensureLikedIdsLoaded(context) to ensureSongsLoaded(context).entries
            }
            val queueIds = if (currentIds === initialIds) initialIds.ordered else {
                initialIds.ordered.asSequence().filterNotNull().filter { it in currentIds }.toList()
            }
            if (currentEntries !== initialEntries) {
                tombstoned.clear()
                for (entry in currentEntries) {
                    if (entry.tombstoned && entry.trackKey.source == MusicSource.NETEASE) {
                        tombstoned.add(entry.trackKey.id)
                    }
                }
            }
            selectLikedPlayAll(
                localVisible = currentEntries,
                cloudIds = queueIds,
                details = if (currentEntries === initialEntries) byId else emptyMap(),
                tombstoned = tombstoned,
            )
        }

    /** 补详情的批大小与批间隔。 */
    private const val PLAY_ALL_BATCH_SIZE = 500
    private const val PLAY_ALL_BATCH_DELAY_MS = 250L

    private const val TAG = "LibraryManager"
}

/**
 * 收藏专辑（`ncrust_library` / `saved_albums`）的 **UI 模型**。
 *
 * 落盘契约在 [SavedAlbumCodec] 那一层（`AlbumDto` + 三种读法），本类保持干净、
 * 不加持久化注解 —— 与 `SearchHistoryManager.HistoryItem` 同一条纪律。
 *
 * @property albumId ncm 的十进制专辑 id。它是 `LibraryScreen` 的 LazyColumn key，
 *   也是「订阅 / 取消订阅」写接口的参数，**必须为正**（codec 会把非正的条目整条丢弃）。
 * @property mid v2.6.2 · P0：专辑在**音源内**的字符串身份 —— qm 的 `albumMID`
 *   （形如 `000MkMni19ClKG`）。ncm 侧恒为 `null`（它的十进制 [albumId] 就是身份）。
 *
 *   **可空 + 默认值**是硬要求：本字段出现之前落盘的每一条收藏都没有这个 key，
 *   而 Gson 走 Unsafe 反序列化、不调用构造函数。
 *   `null` 的语义是「**身份不可信**」—— 它同时覆盖「v2.6.2 之前的旧数据」与
 *   「这张专辑确实没有字符串身份」，两者的处置相同（不许拿它当跨源身份用），
 *   判据只看**字段缺失**、不看空串（见 [identityTrusted]）。
 *
 *   为什么收藏专辑也要它：这张表的每一条都是「用户明确收藏过的一张专辑」，
 *   而收藏页点进去走的是 `NavRoutes.album(source, id)` 那条**带音源**的两段路由 ——
 *   少了它，将来 QQ 专辑进这张表时只能靠数字 id 猜源，正是本版修掉的那个形状。
 */
@Immutable
data class AlbumInfo(
    val albumId: Long,
    val name: String,
    val picUrl: String,
    val artist: String,
    val songCount: Int,
    val mid: String? = null
) {
    /**
     * 这张专辑的**身份是否可信**（v2.6.2 · P0，铁律 14/15 的落点）。
     *
     * `mid == null` ⇒ 身份不可信 ⇒ 调用方应当**跳搜索**，绝不拿数字 id 去别的源猜。
     * 用属性而不是让调用方自己写 `!= null`，是为了让「判据只有一处定义」
     * （v2.4.0 铁律 2）—— 与 `MatchConfidence.mergeable` 同一个形状。
     */
    val identityTrusted: Boolean get() = !mid.isNullOrBlank()
}
