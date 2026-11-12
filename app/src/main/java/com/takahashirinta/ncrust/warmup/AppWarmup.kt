package com.takahashirinta.ncrust.warmup

import android.content.Context
import coil.Coil
import coil.request.ImageRequest
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.auth.NeteaseSessionSnapshot
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.cache.HomeSnapshot
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.ConnectionWarmup
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.NetworkAvailability
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.qq.QqAuthStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal const val HOME_NEW_SONGS_LIMIT = 20

/** 忽略预热失败，保留取消以执行网络阶段的超时。 */
private suspend fun <T> runCatchingCancellable(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        null
    }

/** 冷启动先恢复本地首屏数据，再在后台预取首页、封面和收藏。 */
object AppWarmup {

    /** 首页快照和 QQ 会员状态恢复后允许启动页退出。 */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /** 首页可短暂等待这一批同会话请求。为 null 时由首页自行加载。 */
    @Volatile
    var homeLoads: HomeLoads? = null
        private set

    class HomeLoads(
        val daily: Deferred<List<SongItem>?>,
        val playlists: Deferred<List<PlaylistApi.PlaylistCard>?>,
        val newSongs: Deferred<List<SongItem>?>,
        private val session: NeteaseSessionSnapshot,
        val owner: String?,
    ) {
        fun isCurrent(): Boolean = CookieManager.isCurrent(session) && CookieManager.accountOwner() == owner
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    /** 首页接口和封面共用后台预取预算。 */
    private const val NETWORK_BUDGET_MS = 8_000L
    // Home 里 tile 显示尺寸约 160dp，取 320px 覆盖 xxhdpi 单张封面
    private const val COVER_PX = 320
    // 每类内容预取多少张封面——盖住首屏可见部分即可
    private const val PREFETCH_PER_SECTION = 6

    // 提前加载常用偏好文件，名称与各管理器的存储键保持一致。
    private val PREFS_FILES = arrayOf(
        "ncrust_prefs",           // CookieManager
        "ncrust_settings",        // ThemeManager / LanguageManager / PlayerViewModel
        "ncrust_library",         // LibraryManager
        "ncrust_playback_state",  // PlaybackStateManager
        "ncrust_lyrics_cache",    // LyricsCache
        "search_history",         // SearchHistoryManager
        "ncrust_home_cache"       // HomeSnapshot（v1.5.1 · C）
    )

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext

        scope.launch {
            for (name in PREFS_FILES) {
                runCatching { app.getSharedPreferences(name, Context.MODE_PRIVATE) }
            }
        }

        LibraryManager.preload(app)

        scope.launch {
            runCatchingCancellable { HomeSnapshot.restoreIntoCache(app) }
            // 库页可以先于搜索读取 QQ 会员状态，预先恢复进程内的快照。
            runCatching { QqAuthStore.profile(app) }
            _ready.value = true

            if (!NetworkAvailability.isOnline(app)) return@launch

            // 取链连接与首页并行预热，首屏加载期间点歌也能复用连接。
            scope.launch { runCatchingCancellable { ConnectionWarmup.warmUp() } }

            withTimeoutOrNull(NETWORK_BUDGET_MS) {
                warmHomeData(app)
                warmHomeCovers(app)
            }

            if (CookieManager.hasCookie(app)) {
                scope.launch { runCatchingCancellable { LibraryManager.refreshFromCloud(app) } }
            }
        }
    }

    private suspend fun warmHomeData(app: Context) = coroutineScope {
        val session = CookieManager.snapshot()
        val owner = CookieManager.accountOwner()
        val loads = HomeLoads(
            daily = async { runCatchingCancellable { PlaylistApi.getDailyRecommendSongs() } },
            playlists = async { runCatchingCancellable { PlaylistApi.getRecommendPlaylists() } },
            newSongs = async { runCatchingCancellable { PlaylistApi.getTopSongs(limit = HOME_NEW_SONGS_LIMIT, offset = 0) } },
            session = session,
            owner = owner,
        )
        homeLoads = loads
        try {
            val daily = loads.daily.await()
            val playlists = loads.playlists.await()
            val newSongs = loads.newSongs.await()
            CookieManager.ifCurrent(session) {
                if (CookieManager.accountOwner() != owner) return@ifCurrent
                ContentCache.bindOwner(owner)
                daily?.let { ContentCache.homeDailySongs = it }
                playlists?.let { ContentCache.homeRecommendPlaylists = it }
                newSongs?.let { ContentCache.homeNewSongs = it }
                if (daily != null && playlists != null && newSongs != null) ContentCache.markHomeWarmed()
                HomeSnapshot.save(app, owner = owner, daily = daily, playlists = playlists, newSongs = newSongs)
            }
        } finally {
            if (homeLoads === loads) homeLoads = null
        }
    }

    private suspend fun warmHomeCovers(app: Context) = coroutineScope {
        val loader = Coil.imageLoader(app)
        val urls = buildSet {
            ContentCache.homeDailySongs?.take(PREFETCH_PER_SECTION)?.forEach {
                CoverUrls.small(it.album?.picUrl?.takeIf(String::isNotBlank))?.let(::add)
            }
            ContentCache.homeRecommendPlaylists?.take(PREFETCH_PER_SECTION)?.forEach {
                CoverUrls.small(it.coverUrl.takeIf(String::isNotBlank))?.let(::add)
            }
            ContentCache.homeNewSongs?.take(PREFETCH_PER_SECTION)?.forEach {
                CoverUrls.small(it.album?.picUrl?.takeIf(String::isNotBlank))?.let(::add)
            }
        }
        for (chunk in urls.chunked(4)) {
            chunk.map { url ->
                async {
                    runCatchingCancellable {
                        loader.execute(
                            ImageRequest.Builder(app)
                                .data(url)
                                .size(COVER_PX, COVER_PX)
                                .build()
                        )
                    }
                }
            }.awaitAll()
        }
    }
}
