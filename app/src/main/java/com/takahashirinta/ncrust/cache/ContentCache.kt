package com.takahashirinta.ncrust.cache

import android.os.SystemClock
import androidx.collection.LruCache
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumDetailResponse
import com.takahashirinta.ncrust.network.model.ArtistAlbumsResponse

// In-memory 缓存网络加载内容。进程存活期间常驻，进程被杀后失效。
//
// 目的：消除"空屏 -> spinner -> 内容跳变"的 UX。
// 新的加载策略：
//   1. 屏幕进入时读缓存作为初始 state，有则直接渲染。
//   2. 无论是否命中缓存，后台都发起刷新请求。
//   3. 请求返回后写回缓存，UI 层用 Crossfade 平滑替换。
//
// 与 LibraryManager 的区别：LibraryManager 是用户主动收藏的持久化数据（SharedPreferences），
// ContentCache 是网络加载的临时数据快照，不需要持久化。
//
// 详情页 cache 用 LruCache(32) 封顶，避免低端机无界增长导致 OOM。
// 首页三个字段是单值，不会增长，无需 LRU。
object ContentCache {
    // ── 首页 ─────────────────────────────────────────────────────────
    private data class DailySongs(val songs: List<SongItem>, val dayKey: String?)
    private data class HomeSection<T>(val items: List<T>, val fetchedAt: Long? = null) {
        fun fresh(ttlMs: Long): List<T>? =
            items.takeIf { fetchedAt != null && SystemClock.elapsedRealtime() - fetchedAt in 0 until ttlMs }
    }

    private const val HOME_FRESH_MS = 15_000L

    @Volatile private var dailySnapshot: DailySongs? = null
    var homeDailySongs: List<SongItem>?
        get() = dailySnapshot?.songs
        set(value) { dailySnapshot = value?.let { DailySongs(it, dayKey = null) } }

    internal fun putHomeDailySongs(songs: List<SongItem>, dayKey: String?) {
        dailySnapshot = DailySongs(songs, dayKey)
    }

    internal fun freshHomeDailySongs(owner: String?, dayKey: String): List<SongItem>? {
        val snapshot = dailySnapshot ?: return null
        if (!ownerMatches(owner)) return null
        return snapshot.songs.takeIf { snapshot.dayKey == dayKey && it.isNotEmpty() }
    }

    @Volatile private var recommendedSnapshot: HomeSection<PlaylistApi.PlaylistCard>? = null
    @Volatile private var newSongsSnapshot: HomeSection<SongItem>? = null
    var homeRecommendPlaylists: List<PlaylistApi.PlaylistCard>?
        get() = recommendedSnapshot?.items
        set(value) { recommendedSnapshot = value?.let { HomeSection(it) } }
    var homeNewSongs: List<SongItem>?
        get() = newSongsSnapshot?.items
        set(value) { newSongsSnapshot = value?.let { HomeSection(it) } }

    internal fun putHomeRecommendPlaylists(items: List<PlaylistApi.PlaylistCard>) {
        recommendedSnapshot = HomeSection(items, SystemClock.elapsedRealtime())
    }

    internal fun putHomeNewSongs(items: List<SongItem>) {
        newSongsSnapshot = HomeSection(items, SystemClock.elapsedRealtime())
    }

    internal fun freshHomeRecommendPlaylists(owner: String?) =
        recommendedSnapshot?.takeIf { ownerMatches(owner) }?.fresh(HOME_FRESH_MS)

    internal fun freshHomeNewSongs(owner: String?) =
        newSongsSnapshot?.takeIf { ownerMatches(owner) }?.fresh(HOME_FRESH_MS)

    /**
     * 首页个性化缓存归属的账号指纹（S14）。
     *
     * 换账号时清除个性化栏目和资料，公共榜单可以保留。
     */
    @Volatile private var homeOwner: String? = null

    fun bindOwner(owner: String?) {
        if (homeOwner != owner) {
            // 归属变了：个性化数据立即作废，公共榜单可以保留。
            homeDailySongs = null
            homeRecommendPlaylists = null
            homeNewSongs = null
            userProfile = null
        }
        homeOwner = owner
    }

    fun ownerMatches(owner: String?): Boolean = homeOwner == owner

    // ── 榜单（E：首页卡片与搜索页共用同一份快照，避免两处各拉一遍） ──
    @Volatile var toplistItems: List<PlaylistApi.PlaylistCard>? = null
    @Volatile private var toplistFetchedAt: Long = 0L

    /** 榜单快照是否在 [ttlMs] 内取过（沿用首页的 15s freshness 窗口）。 */
    fun isToplistFresh(ttlMs: Long = 15_000L): Boolean =
        toplistFetchedAt > 0L && System.currentTimeMillis() - toplistFetchedAt < ttlMs

    fun putToplist(items: List<PlaylistApi.PlaylistCard>) {
        toplistItems = items
        toplistFetchedAt = System.currentTimeMillis()
    }

    // ── 详情页（按 ID 缓存，LRU 32 项封顶） ──────────────────────────
    private val albumCache = LruCache<Long, AlbumDetailResponse>(32)
    private val playlistCache = LruCache<Long, List<SongItem>>(32)
    private val artistCache = LruCache<Long, ArtistAlbumsResponse>(32)

    fun getAlbum(id: Long): AlbumDetailResponse? = albumCache[id]
    fun putAlbum(id: Long, data: AlbumDetailResponse) { albumCache.put(id, data) }

    fun getPlaylistSongs(id: Long): List<SongItem>? = playlistCache[id]
    fun putPlaylistSongs(id: Long, data: List<SongItem>) { playlistCache.put(id, data) }

    /**
     * v1.3.0 · B4：歌单发生写操作（增删曲/改名/删除）后丢弃该 id 的快照。
     * 服务端的 detail 端点本身有陈旧缓存，客户端这份再不丢就会出现「改了名进详情还是旧名」。
     */
    fun invalidatePlaylist(id: Long) { playlistCache.remove(id) }

    fun getArtistAlbums(id: Long): ArtistAlbumsResponse? = artistCache[id]
    fun putArtistAlbums(id: Long, data: ArtistAlbumsResponse) { artistCache.put(id, data) }

    // ── 用户 ─────────────────────────────────────────────────────────
    @Volatile var userProfile: PlaylistApi.UserProfile? = null

    // 清空所有缓存（切换账号 / 内存压力 / 进程重启场景）。
    fun clearAll() {
        homeOwner = null
        homeDailySongs = null
        homeRecommendPlaylists = null
        homeNewSongs = null
        toplistItems = null
        toplistFetchedAt = 0L
        albumCache.evictAll()
        playlistCache.evictAll()
        artistCache.evictAll()
        userProfile = null
    }
}
