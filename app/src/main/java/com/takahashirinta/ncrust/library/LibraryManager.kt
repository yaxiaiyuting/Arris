/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v1.x（Bug2「我喜欢的歌无法全部加载 / 加入播放列表」）：
 *     ① 分页游标改为独立的 likedIdCursor（已消费 id 前缀长度），不再用 cachedSongs.size；
 *     ② refreshFromCloud 由「整体替换」改为「按 likedIds 顺序合并去重」；
 *     ⑤ 新增 loadAllLikedSongs：为「播放全部」一次性补齐全部详情，分批 + 限流。
 *   - v2.6.0 · P0（QQ 入库丢失）：**内存真源从 `List<SongItem>` 换成
 *     `List<SavedSongEntry>`**（每条带 origin / tombstoned），同步改走
 *     `SavedSongSync` 的七条规则。旧的 refreshFromCloud 是「以云端 likedIds
 *     重建整张表」—— 它**在结构上无法区分**「云端删掉的」与「用户手动加入库的」，
 *     于是把后者一起丢掉，再 flush 回磁盘。QQ 曲目因为 bit62 合成 id 永远不会
 *     出现在 ncm 的 likedIds 里，所以 100% 命中这条路径。
 *     根因链与证据：`docs/verification/v2.6.0/probe-qq-import-loss.md`。
 */

package com.takahashirinta.ncrust.library

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * 云同步收藏库。
 *
 * 语义（与 ncm 官方一致）：
 *  - 「收藏单曲」 = ncm「收藏/我喜欢」（weapi /api/radio/like）；
 *  - 「收藏专辑」 = ncm「我收藏的专辑」（weapi /api/album/sub），与单曲解耦。
 *
 * 本地用 SharedPreferences 缓存云端状态（收藏单曲 + 收藏专辑），保证：
 *   - 进收藏页/登录时后台拉取（refreshFromCloud）刷新，云端为真源；
 *   - 未拉取/未登录/失败时直接显示本地缓存，绝不出现空白+加载动画；
 *   - 收藏/取消动作先落本地缓存即时生效，再异步推到云端。
 *
 * 该对象保持公开 API 不变，所有既有调用点（收藏按钮、各详情页「加入收藏」）无需改动。
 *
 * ## v2.6.0 · P0：同步口径从「云端为真源」改为「只加不减 + tombstone」
 *
 * | # | 规则 | 落点 |
 * |---|---|---|
 * | 1 | 云端有 + 本地无 + 不在 tombstone → 添加，origin = REMOTE | [SavedSongSync.merge] |
 * | 2 | 云端有 + 本地有 → 保留，不动 | 同上 |
 * | 3 | 云端无 + 本地有 → 保留（只加不减） | 同上 |
 * | 4 | 云端有 + 在 tombstone → 跳过 | 同上 |
 * | 5 | 本地手动新增 → origin = LOCAL，同步永远保留 | [saveSong] |
 * | 6 | 用户手动删除 → 设 tombstoned = true | [removeSong] |
 * | 7 | 用户重新添加 → 清除 tombstone | [saveSong] |
 *
 * **为什么不去「修」规则 3**：它看起来像「云端取消收藏后本地还在」的不一致，
 * 但那正是本版要的语义 —— 用户在本机删掉一首歌是**一个明确的动作**（规则 6 记账），
 * 而「云端列表里没有」还可以是网络截断、分页上限、账号切换。用后者去删本地数据
 * 就是本版修的 bug。
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

    /**
     * **收藏库的唯一内存真源**（v2.6.0 · P0）。每条带 origin / tombstoned。
     *
     * 之所以不再同时维护一份 `List<SongItem>` 缓存：两份视图必然漂移
     * （一处改了另一处忘改），而这里漂移的后果是**用户的收藏被静默删掉**。
     */
    @Volatile private var cachedEntries: MutableList<SavedSongEntry>? = null
    private val songsLock = Any()
    @Volatile private var cachedAlbums: MutableList<AlbumInfo>? = null
    private val albumsLock = Any()
    // 红心歌单全部单曲 id（有序），作为分页加载的底表。
    @Volatile private var cachedLikedIds: List<Long>? = null
    private val idsLock = Any()

    /**
     * 分页游标：cachedLikedIds 中**已被消费（已请求过详情）的前缀长度**（Bug2-①）。
     *
     * 绝不能用「已解析歌曲数」当游标 —— 某些 id 取不到详情（下架 / 无版权）时，
     * 游标会随之错位：同一段 id 被反复请求，且终止条件可能永远不成立，
     * 尾部歌曲再也加载不出来。
     *
     * v2.6.0 起「已覆盖」的判据是「**表里有这个 trackKey 的条目**」，
     * 而**不是**「它可见」—— tombstone 的条目也算已消费，否则删除过的歌
     * 会在每一轮刷新时被重新请求一次详情（恒定浪费一次网络往返）。
     *
     * 只在 songsLock 或 IO 协程内写；@Volatile 保证可见性即可，无需嵌套加锁。
     */
    @Volatile private var likedIdCursor = 0

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null
    private var pendingAppContext: Context? = null
    private val flushLock = Any()

    /**
     * 被闸门拦下的**跨源写请求**次数（v2.6.0 · P0）。
     *
     * **只在内存**，不落盘：这个数的用途是「本地排查时能一眼看出闸门有没有在拦」，
     * 而它落在**用户主动动作**的路径上（点一次「加入库」），不像播放上报那样
     * 需要事后统计。落盘会引入一个新的 prefs 文件 + 迁移 + 单测，
     * 而收益只是「重启后还能看到历史拦截次数」—— 如实记在这里，不假装有持久化。
     */
    private val blockedLikeWrites = AtomicLong()

    /** 诊断入口（debug / 本地排查用）。 */
    fun blockedCrossSourceLikeCount(): Long = blockedLikeWrites.get()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读盘 → 内存。**走 [SavedSongCodec]**：它同时认 v1 裸 `SongItem` 数组
     * （v2.6.0 之前的落盘形状）与 v2 信封，并逐条容错。
     */
    private fun ensureSongsLoaded(context: Context): MutableList<SavedSongEntry> {
        val current = cachedEntries
        if (current != null) return current
        return synchronized(songsLock) {
            cachedEntries ?: run {
                val parsed = SavedSongCodec.decode(prefs(context).getString(KEY_SONGS, null))
                cachedEntries = parsed.toMutableList()
                parsed.toMutableList()
            }
        }
    }

    private fun ensureAlbumsLoaded(context: Context): MutableList<AlbumInfo> {
        val current = cachedAlbums
        if (current != null) return current
        return synchronized(albumsLock) {
            cachedAlbums ?: run {
                // v2.5.5 · A：读路径走 SavedAlbumCodec（认稳定名 / 旧单字母 / 声明顺序），
                // 且**逐条**容错 —— 旧写法 `runCatching { gson.fromJson(...) }` 是
                // 「一个字节坏了，用户收藏的整张专辑表消失」。
                val parsed = SavedAlbumCodec.decode(prefs(context).getString(KEY_ALBUMS, null))
                cachedAlbums = parsed.toMutableList()
                parsed.toMutableList()
            }
        }
    }

    private fun ensureLikedIdsLoaded(context: Context): List<Long> {
        val current = cachedLikedIds
        if (current != null) return current
        return synchronized(idsLock) {
            cachedLikedIds ?: run {
                val parsed = runCatching {
                    val json = prefs(context).getString(KEY_LIKED_IDS, null)
                    if (json.isNullOrEmpty()) emptyList<Long>()
                    else (gson.fromJson<List<Long>>(json, idListType) ?: emptyList())
                }.getOrDefault(emptyList())
                cachedLikedIds = parsed
                parsed
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

    /**
     * 把内存快照写回磁盘。
     *
     * ## v2.6.0 · P0 修掉的第二个数据丢失路径：**不许用空表覆盖「还没加载过」的键**
     *
     * 旧实现是 `cachedSongs?.toList() ?: emptyList()` —— 「内存里还没有」与
     * 「内存里确实是空的」被压成同一个取值，于是**任何一次在没有加载过收藏单曲的
     * 情况下触发的 flush 都会把 `saved_songs` 写成 `[]`**。
     * 这条路真的可达：`subscribeAlbum`（收藏一张专辑）直接 `scheduleFlush`，
     * 既不读也不写 `cachedSongs`；用户冷启后第一件事是去专辑页点收藏，
     * 300ms 后整张收藏单曲表就被清空了。
     *
     * 现在三个键各自**独立**判断：`null`（没加载过）⇒ 这个键**原样不动**
     * （`edit()` 里不 put 就是不动），只有非 null 才写。
     */
    private fun flushToDisk() {
        val ctx: Context
        val songsSnapshot: List<SavedSongEntry>?
        val albumsSnapshot: List<AlbumInfo>?
        val likedIdsSnapshot: List<Long>?
        synchronized(flushLock) {
            ctx = pendingAppContext ?: return
            pendingAppContext = null
        }

        synchronized(songsLock) { songsSnapshot = cachedEntries?.toList() }
        synchronized(albumsLock) { albumsSnapshot = cachedAlbums?.toList() }
        synchronized(idsLock) { likedIdsSnapshot = cachedLikedIds?.toList() }
        runCatching {
            val editor = prefs(ctx).edit()
            // v2.6.0 · P0：写路径走 codec（显式字段名 + origin/tombstone），
            // 不再依赖 R8 后的类结构，也不再落一份没有来源信息的裸 SongItem 数组。
            songsSnapshot?.let { editor.putString(KEY_SONGS, SavedSongCodec.encode(it)) }
            // v2.5.5 · A：写路径走 codec（显式字段名），不再依赖 R8 后的类结构。
            albumsSnapshot?.let { editor.putString(KEY_ALBUMS, SavedAlbumCodec.encode(it)) }
            likedIdsSnapshot?.let { editor.putString(KEY_LIKED_IDS, gson.toJson(it)) }
            editor.apply()
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

    /**
     * 把「收藏」动作推给 ncm。
     *
     * ## v2.6.0 · P0：跨源 id 不许发给 ncm 的写接口
     *
     * qm 的 id 由 `SourceIds.qqId` 合成（`bit62` 恒置位，是**正数**），
     * 所以旧代码里没有任何一条卫语句能拦住它 —— 一首 QQ 曲目的合成 id 会被
     * POST 到 `/api/radio/like`。这与 v2.5.5 修掉的 `PlayReporter` 跨源上报
     * 是**同一个形状**（`ReportGate` 的 KDoc 记了完整根因链）。
     *
     * 闸门挂在这里（like 的**唯一出口**），判据是 [SavedSongSync.isRemoteLikeEligible]
     * （底层 = `SourceIds.isQqId`，与 `ReportGate` 共用同一个谓词）。
     * 被拦下时**只记本地计数**，不发请求。
     */
    private fun pushLike(songId: Long, like: Boolean) {
        if (!SavedSongSync.isRemoteLikeEligible(songId)) {
            blockedLikeWrites.incrementAndGet()
            Log.w(TAG, "pushLike blocked: cross-source id=$songId like=$like")
            return
        }
        ioScope.launch { runCatching { PlaylistApi.likeSong(songId, like) } }
    }

    private fun pushSubAlbum(albumId: Long, sub: Boolean) {
        ioScope.launch { runCatching { PlaylistApi.subAlbum(albumId, sub) } }
    }

    // ==================== 单曲（收藏） ====================

    /**
     * 收藏（加入库）单曲：先落本地缓存并**立即落盘**，再异步同步到云端。
     *
     * 规则 5 / 7（[SavedSongSync.addManual]）：不存在则插到最前、`origin = LOCAL`；
     * 已存在但被 tombstone 过则**清除 tombstone**（用户重新添加 ⇒ 这条歌回来了）；
     * 已存在且可见则**幂等**（顺序与 origin 都不动）。
     *
     * ## 为什么返回值从 `Unit` 改成 [SavedSongOutcome]
     *
     * 旧实现返回 `Unit`，于是 8 个调用点**全部无条件**弹「已加入库」——
     * 用户看到的成败与真实成败完全脱钩（探针
     * `docs/verification/v2.6.0/probe-qq-import-loss.md` §7 把它列为「UI 说谎」）。
     * 改成返回值之后，「什么时候该说成功」就只有调用点一处判断，
     * 而不再依赖「它反正不会失败」这个（错的）假设。
     */
    fun saveSong(context: Context, song: SongItem): SavedSongOutcome {
        if (song.id <= 0L) {
            // 不是任何真实曲目：它连身份都不成立（`SourceIds.mediaId` 同样会拒绝
            // `id <= 0`）。**不写入、不弹成功**。
            Log.w(TAG, "saveSong rejected: id=${song.id} name=${song.name}")
            return SavedSongOutcome.REJECTED
        }
        val key = TrackKey.ofSong(song)
        val changed: Boolean
        synchronized(songsLock) {
            val before = cachedEntries ?: mutableListOf()
            val after = SavedSongSync.addManual(before, song, System.currentTimeMillis())
            changed = after !== before
            if (changed) cachedEntries = after.toMutableList()
        }
        if (changed) {
            flushNow(context)
            if (isLoggedIn(context)) pushLike(key.id, true)
            return SavedSongOutcome.ADDED
        }
        // 零变化有两种：本来就在可见表里（幂等成功），或表里没有但它……
        // 后者不可能（addManual 对「不存在」一定返回新列表）。所以这里就是幂等。
        return SavedSongOutcome.ALREADY_SAVED
    }

    fun saveSongs(context: Context, newSongs: List<SongItem>) {
        if (newSongs.isEmpty()) return
        val added = mutableListOf<SongItem>()
        synchronized(songsLock) {
            val before = cachedEntries ?: mutableListOf()
            // 判断「这次到底哪些真的进了可见表」的判据：**可见**条目的 trackKey 集合。
            // 用「可见」而不是「全部」是为了让「重新添加一首被 tombstone 过的歌」
            // 也算一次新增（它需要推一次 like(true) 给云端）。
            val visibleBefore = before.asSequence()
                .filter { it.isVisible }
                .mapTo(HashSet(before.size * 2)) { it.trackKey }
            val after = SavedSongSync.addManualAll(before, newSongs, System.currentTimeMillis())
            if (after !== before) {
                cachedEntries = after.toMutableList()
                added += newSongs.filter { TrackKey.ofSong(it) !in visibleBefore }
            }
        }
        if (added.isNotEmpty()) {
            flushNow(context)
            if (isLoggedIn(context)) added.forEach { pushLike(TrackKey.ofSong(it).id, true) }
        }
    }

    /**
     * 取消收藏（从库中移除）单曲：**设 tombstone，不物理删除**（规则 6）。
     *
     * 物理删除会让下一次 [refreshFromCloud] 把它复活 —— 那正是「无 tombstone 的
     * 只加不减不成立」（铁律 4）。移除后仍然异步同步云端。
     *
     * 返回**是否真的移除了**（原本就不在库里 / 已经被 tombstone 过时为 false）——
     * 与 [saveSong] 同理：调用点需要一个能判断「要不要提示用户」的返回值。
     */
    fun removeSong(context: Context, songId: Long): Boolean {
        // 调用点传的是裸 `song.id`（SongItem 的 id 自带 bit62 标志），
        // 所以音源可以直接从 id 推出来，不需要额外参数。
        val key = TrackKey.of(null, songId)
        val changed: Boolean
        synchronized(songsLock) {
            val before = cachedEntries ?: mutableListOf()
            val after = SavedSongSync.remove(before, key)
            changed = after !== before
            if (changed) cachedEntries = after.toMutableList()
        }
        if (changed) {
            flushNow(context)
            if (isLoggedIn(context)) pushLike(songId, false)
        }
        return changed
    }

    /** 可见（未被 tombstone）的收藏单曲，按表内顺序。 */
    fun getSavedSongs(context: Context): List<SongItem> {
        val entries = ensureSongsLoaded(context)
        return synchronized(songsLock) { entries.filter { it.isVisible }.map { it.song } }
    }

    /** 带来源的只读视图（诊断 / 单测 / 将来的 UI 角标用）。 */
    fun getSavedSongEntries(context: Context): List<SavedSongEntry> {
        val entries = ensureSongsLoaded(context)
        return synchronized(songsLock) { entries.toList() }
    }

    fun isSongSaved(context: Context, songId: Long): Boolean {
        val entries = ensureSongsLoaded(context)
        return synchronized(songsLock) { entries.any { it.isVisible && it.trackKey.id == songId } }
    }

    fun getSongsByAlbumId(context: Context, albumId: Long): List<SongItem> {
        val entries = ensureSongsLoaded(context)
        return synchronized(songsLock) {
            entries.filter { it.isVisible && it.song.album?.id == albumId }.map { it.song }
        }
    }

    // ==================== 专辑（云端收藏） ====================

    /** 收藏页「专辑」栏：返回云端「收藏的专辑」（album_sublist 缓存）。纯云端，不派生。 */
    fun getSavedAlbums(context: Context): List<AlbumInfo> {
        val albums = ensureAlbumsLoaded(context)
        return synchronized(albumsLock) { albums.toList() }
    }

    /** 收藏专辑（订阅云端）：先落本地缓存，再异步同步云端。 */
    fun subscribeAlbum(context: Context, album: AlbumInfo) {
        val albums = ensureAlbumsLoaded(context)
        synchronized(albumsLock) {
            albums.removeAll { it.albumId == album.albumId }
            albums.add(0, album)
        }
        scheduleFlush(context)
        if (isLoggedIn(context)) pushSubAlbum(album.albumId, true)
    }

    /** 取消收藏专辑：仅移除专辑订阅（不影响收藏单曲，二者解耦）。 */
    fun removeAlbum(context: Context, albumId: Long) {
        val albums = ensureAlbumsLoaded(context)
        val removed: Boolean
        synchronized(albumsLock) {
            removed = albums.removeAll { it.albumId == albumId }
        }
        if (removed) {
            scheduleFlush(context)
            if (isLoggedIn(context)) pushSubAlbum(albumId, false)
        }
    }

    // ==================== 云端同步 ====================

    /**
     * 从云端拉取收藏单曲 + 收藏专辑刷新本地缓存。
     * 未登录返回 false。返回是否成功拉取（供调用方判断是否需要提示）。
     *
     * ## v2.6.0 · P0：这里不再「以云端为真源重建整张表」
     *
     * 旧实现把 `cachedSongs` 换成「按 likedIds 顺序、只用云端能提供的详情」重建的列表，
     * 于是**任何不在云端 likedIds 里的本地条目都被丢掉**，紧接着 `scheduleFlush`
     * 把丢掉的结果写回磁盘 —— 不可恢复。QQ 曲目的合成 id（bit62）永远不会出现在
     * ncm 的 likedIds 里，所以**每一次进库页都会丢一次**。
     *
     * 新实现走 [SavedSongSync.merge] 的七条规则：云端只负责**追加**，
     * 删除只能由用户动作（规则 6）产生。
     */
    suspend fun refreshFromCloud(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!CookieManager.hasCookie(context)) {
            Log.w(TAG, "refreshFromCloud: no cookie, skip")
            return@withContext false
        }
        val uid: Long = try {
            PlaylistApi.getCurrentUserId()
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromCloud: getCurrentUserId failed: ${e.message}")
            return@withContext false
        }

        // 单曲与专辑各自独立尝试、独立提交：任一步失败不致整体放弃。
        var anySuccess = false

        // 单曲：先拉全量有序 trackIds 作底表，只需首屏分批详情即可渲染(懒加载)。
        //
        // v3.4.5 · P0（移植自上游 `6b5b470`）：读失败**一律保留现有缓存**。
        // 旧写法把 `Failure`（业务码非 200 / 结构异常）与「服务端确认收藏为空」
        // 折叠成同一个 `emptyList()`，于是 `cachedLikedIds` 被清空**并落盘**
        // （见下方 `scheduleFlush` → `KEY_LIKED_IDS`）—— 表头显示「0 首」、
        // 分页底表变空，且重启后仍是空。收藏单曲本体不受影响（merge 只加不减）。
        try {
            when (val likedResult = PlaylistApi.getLikedTrackIds(uid)) {
                is PlaylistApi.LikedIdsResult.Failure -> {
                    // 关键：不写 cachedLikedIds、也不置 anySuccess
                    // （后者会触发一次落盘，把「没变」写成「变空」的时机提前）。
                    Log.w(
                        TAG,
                        "refreshFromCloud: liked songs 读取失败，保留本地 ids=" +
                            "${cachedLikedIds?.size ?: 0}：${likedResult.reason}"
                    )
                }

                is PlaylistApi.LikedIdsResult.Success -> {
                    val likedIds = likedResult.ids
                    Log.i(TAG, "refreshFromCloud: likedIds=${likedIds.size}")
                    synchronized(idsLock) { cachedLikedIds = likedIds }
                    val firstBatch = if (likedIds.size > LIKED_BATCH_SIZE) likedIds.take(LIKED_BATCH_SIZE) else likedIds
                    val firstSongs = if (firstBatch.isNotEmpty()) PlaylistApi.getSongsByIds(firstBatch) else emptyList()
                    val freshById = firstSongs.associateBy { it.id }
                    synchronized(songsLock) {
                        val before = cachedEntries ?: mutableListOf()
                        val after = SavedSongSync.merge(before, likedIds, freshById, System.currentTimeMillis())
                        cachedEntries = after.toMutableList()
                        // 游标 = 已连续覆盖的 id 前缀长度：刷新后既不重复请求也不漏请求（Bug2-①）。
                        // 「已覆盖」= **表里有这个 trackKey 的条目**（tombstone 也算），
                        // 见 likedIdCursor 的 KDoc。
                        val coveredIds = after.asSequence()
                            .filter { it.trackKey.source == MusicSource.NETEASE }
                            .map { it.trackKey.id }
                            .toHashSet()
                        likedIdCursor = likedIds.takeWhile { it in coveredIds }.size
                    }
                    anySuccess = true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromCloud: liked songs failed: ${e.message}")
        }

        try {
            val cloudAlbums = PlaylistApi.getSubscribedAlbums()
            Log.i(TAG, "refreshFromCloud: albums=${cloudAlbums.size}")
            synchronized(albumsLock) {
                cachedAlbums = cloudAlbums.map {
                    AlbumInfo(
                        albumId = it.albumId,
                        name = it.name,
                        picUrl = it.picUrl,
                        artist = it.artist,
                        songCount = it.songCount
                    )
                }.toMutableList()
            }
            anySuccess = true
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromCloud: albums failed: ${e.message}")
        }

        if (anySuccess) scheduleFlush(context)
        true
    }

    /** 红心歌单全部单曲 id（有序），供收藏页做分页懒加载的底表。 */
    fun getLikedSongIds(context: Context): List<Long> = ensureLikedIdsLoaded(context)

    /**
     * 分页拉取下一批收藏单曲详情（滚动到底时调用）。会追加进本地缓存并返回新渲染项。
     */
    suspend fun loadMoreLikedSongs(context: Context): List<SongItem> = withContext(Dispatchers.IO) {
        val allIds = ensureLikedIdsLoaded(context)
        // Bug2-①：游标是「已消费的 id 数」，与「已解析歌曲数」解耦；
        // 因此个别 id 取不到详情也不会让加载停滞。
        val start = likedIdCursor
        if (allIds.isEmpty() || start >= allIds.size) return@withContext emptyList()
        val sliceEnd = minOf(start + LIKED_BATCH_SIZE, allIds.size)
        val slice = allIds.subList(start, sliceEnd)
        // 先推进游标再发请求：即使本批部分 id 取不到详情，游标也必须前进，
        // 否则下一轮会重复请求同一段，终止条件也永远不成立（死循环）。
        likedIdCursor = sliceEnd
        val fetched = try {
            PlaylistApi.getSongsByIds(slice)
        } catch (e: Exception) {
            // 只有整批失败（网络/风控）才回滚游标，允许用户再次滚动时重试这一段。
            Log.e(TAG, "loadMoreLikedSongs failed: ${e.message}")
            likedIdCursor = start
            return@withContext emptyList()
        }
        synchronized(songsLock) {
            val before = cachedEntries ?: mutableListOf()
            // 纯追加（不是 merge）：分页只拿到一段 slice，用 merge 会把不在这一批里的
            // 条目重排到后面 —— 每翻一页列表顺序就抖一次。
            val after = SavedSongSync.appendRemote(before, fetched, System.currentTimeMillis())
            cachedEntries = after.toMutableList()
            after.filter { it.isVisible }.map { it.song }
        }.also { scheduleFlush(context) }
    }

    /**
     * 「播放全部」用：按红心歌单顺序返回**全部**收藏单曲详情（Bug2-⑤）。
     *
     * 与 loadMoreLikedSongs 的分页懒加载不同，这里一次性补齐所有缺失详情，
     * 因此按 PLAY_ALL_BATCH_SIZE 分批、批间 delay(PLAY_ALL_BATCH_DELAY_MS) 限流，
     * 避免收藏量很大时触发服务端风控。已在缓存中的歌曲直接复用，不重复请求。
     * 返回结果按红心歌单顺序且已去重；服务端已删除 / 无版权的 id 自然被跳过。
     *
     * v2.6.0：tombstone 的 id **跳过**（用户删掉的歌不该被「播放全部」播出来），
     * 并且补到的详情只**追加**进表里、不触发重排。
     */
    suspend fun loadAllLikedSongs(context: Context): List<SongItem> = withContext(Dispatchers.IO) {
        val allIds = ensureLikedIdsLoaded(context).distinct()
        if (allIds.isEmpty()) return@withContext emptyList()
        val byId = LinkedHashMap<Long, SongItem>()
        val tombstoned = HashSet<Long>()
        synchronized(songsLock) {
            cachedEntries?.forEach {
                if (it.trackKey.source == MusicSource.NETEASE) {
                    if (it.tombstoned) tombstoned.add(it.trackKey.id) else byId[it.trackKey.id] = it.song
                }
            }
        }
        val wanted = allIds.filter { it !in tombstoned }
        val missing = wanted.filter { it !in byId }
        for (start in missing.indices step PLAY_ALL_BATCH_SIZE) {
            val batch = missing.subList(start, minOf(start + PLAY_ALL_BATCH_SIZE, missing.size))
            val fetched = try {
                PlaylistApi.getSongsByIds(batch)
            } catch (e: Exception) {
                Log.e(TAG, "loadAllLikedSongs batch failed: ${e.message}")
                emptyList()
            }
            // 去重：同 id 覆盖写入，不会产生重复项。
            for (s in fetched) byId[s.id] = s
            if (fetched.isNotEmpty()) {
                synchronized(songsLock) {
                    val before = cachedEntries ?: mutableListOf()
                    cachedEntries = SavedSongSync
                        .appendRemote(before, fetched, System.currentTimeMillis())
                        .toMutableList()
                }
            }
            if (start + PLAY_ALL_BATCH_SIZE < missing.size) delay(PLAY_ALL_BATCH_DELAY_MS)
        }
        wanted.mapNotNull { byId[it] }
    }

    /** 「播放全部」补齐详情时的批大小与批间隔（限流，见 loadAllLikedSongs）。 */
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
