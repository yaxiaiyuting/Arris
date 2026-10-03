package com.takahashirinta.ncrust.warmup

import android.content.Context
import coil.Coil
import coil.request.ImageRequest
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.cache.HomeSnapshot
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.ConnectionWarmup
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.NetworkAvailability
import com.takahashirinta.ncrust.network.PlaylistApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 冷启动一次性预热：Splash 遮挡期间跑完所有可提前的 IO，等首页真的进入时数据+封面都在本地。
 *
 * 目的：把"进入 Home 后 loader + 图片解码 + 首次 Composition"堆在一起的爆发抹平——
 * 让 splash 期间的 CPU/网络/磁盘齐上，用户看到的第一帧 Home 是完成态。
 *
 * ready 由 splash 订阅：为 true 时才允许淡出（配合 splash 侧的最短驻留时间）。
 * 总兜底 [TIMEOUT_MS]：无论网络多慢，超时后强制置 ready，防止启动被卡死。
 */
/**
 * 同 `runCatching`，但**绝不吞 [CancellationException]**。
 *
 * 这是 v1.5.2 修掉「冷启动十秒到半分钟」的关键一行：Kotlin 的 `runCatching` 捕获 `Throwable`，
 * 会把协程取消异常一起吃掉。于是 `withTimeoutOrNull` 的超时取消被吞 ⇒ 代码继续往下跑 ⇒
 * **超时形同虚设**，坏网下预热会一路挂到 OkHttp 的 connectTimeout(30s)。
 * 凡是 `suspend` 调用外面套 `runCatching`，都必须换成这个版本。
 */
private suspend fun <T> runCatchingCancellable(block: suspend () -> T): T? =
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (t: Throwable) {
        null
    }

object AppWarmup {

    /** splash 的闸门。**只反映本地阶段**（快照回灌），不含任何网络等待。 */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * v1.5.2：后台首页预取是否已收尾（成功 / 失败 / 超时都算）。
     *
     * HomeScreen 用它避开「冷启动同一批接口打两遍」：预热还在跑就等它一下，跑完了再决定要不要自己发。
     */
    private val _homeFetchDone = MutableStateFlow(false)
    val homeFetchDone: StateFlow<Boolean> = _homeFetchDone.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    /**
     * 后台首页预取的硬预算。
     *
     * v1.5.2 之前这里是 3s 且**根本不生效**（见 [runCatchingCancellable] 的注释），坏网下会一路挂到
     * OkHttp 的 connectTimeout(30s)，而 splash 正等着这批请求 —— 用户看到的就是「冷启动十秒到半分钟」。
     * 现在它对 splash 已经完全没有影响，只用来给后台预取收个尾，所以可以给得宽一点。
     */
    private const val NETWORK_BUDGET_MS = 8_000L
    // Home 里 tile 显示尺寸约 160dp，取 320px 覆盖 xxhdpi 单张封面
    private const val COVER_PX = 320
    // 每类内容预取多少张封面——盖住首屏可见部分即可
    private const val PREFETCH_PER_SECTION = 6

    // 全部 SharedPreferences 文件名——IO 线程一次性触碰，让主线程 getSharedPreferences 命中缓存。
    // 与各 Manager 里的 PREFS_NAME 常量保持同步。
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

        // 阶段零：立即触碰所有 SharedPreferences 文件，把 XML 解析从主线程拉走。
        // SharedPreferences 按 name 单例，首次 getSharedPreferences 会同步读磁盘+解析 XML
        // （eMMC 上单文件 30~100ms）。这里 IO 线程先跑一遍，后续主线程 get* 就都是内存命中。
        // 独立 launch 不进 withTimeout：这些操作本身很快，即使个别慢也不该阻塞 ready 判定。
        scope.launch {
            for (name in PREFS_FILES) {
                runCatching { app.getSharedPreferences(name, Context.MODE_PRIVATE) }
            }
        }

        // 预解析收藏库（IO）：避免首次在组合期主线程 getSavedSongs/isSongSaved 卡顿。
        LibraryManager.preload(app)

        scope.launch {
            // ================= 阶段一：本地阶段（**唯一**决定 splash 何时结束的部分） =================
            // 上次的首页磁盘快照灌回内存缓存。无网冷启动时这几乎是首屏唯一的内容来源。
            runCatchingCancellable { HomeSnapshot.restoreIntoCache(app) }

            // v1.5.2：splash 到此为止。
            //
            // 以前 _ready 要等完整个网络阶段（三个 Home 请求 + 18 张封面）才置位，而那个阶段的
            // 超时因为 runCatching 吞取消**根本没生效** —— 坏网下启动页会一直挂到 OkHttp 的
            // connectTimeout(30s)。冷启动速度被网络绑架，用户看到的就是「十秒到半分钟打不开」。
            //
            // 现在：本地快照就绪 = 可以进首页。首页自己有完整的三段式（缓存直出 → 后台刷新 →
            // 超时降级），不需要启动页替它等网络。
            _ready.value = true

            // ================= 阶段二：网络阶段（纯后台，谁都不等它） =================
            if (!NetworkAvailability.isOnline(app)) {
                _homeFetchDone.value = true
                return@launch
            }

            // v3.1.0 · P1-B 引入 / **v3.3.2 提前到与首页预取并行**。
            //
            // 旧位置在首页预取 + 封面预取**之后**（原第 211 行），而那一整段被
            // `withTimeoutOrNull(NETWORK_BUDGET_MS = 8s)` 包着 —— 于是预热最晚可能在
            // 冷启动后 **8 秒**才开始。取链（`interface3.music.163.com`）是用户点歌时
            // 才会碰到的 host，用户完全可能在那 8 秒内就点了歌，
            // 那一刻预热还没跑 ⇒ 这条优化对「冷启动后第一首歌」等于不存在。
            //
            // 现在与首页预取**同时**发出：三通 GET `/` 是极小请求，不会挤掉首页那三条；
            // 而它换来的是一条已建好的取链连接。它仍然是**独立 launch、在 8 秒预算之外**，
            // 谁都不等它，它失败了也完全静默（铁律 4）。
            scope.launch { runCatchingCancellable { ConnectionWarmup.warmUp() } }

            // 预算现在**真的**生效（超时取消不会被吞）：到点就撤，剩下的交给 HomeScreen。
            withTimeoutOrNull(NETWORK_BUDGET_MS) {
                // 阶段一：三条 Home 请求并发写入 ContentCache
                coroutineScope {
                    val dailyDeferred = async {
                        runCatchingCancellable { PlaylistApi.getDailyRecommendSongs() }
                    }
                    val plsDeferred = async {
                        runCatchingCancellable { PlaylistApi.getRecommendPlaylists() }
                    }
                    val topDeferred = async {
                        // limit 与 HomeScreen NEW_SONGS_LIMIT 对齐——warmup 一次装够，
                        // 不留"缓存 10 条 → 刷新变 20 条"的可见 diff
                        runCatchingCancellable { PlaylistApi.getTopSongs(limit = 20, offset = 0) }
                    }
                    dailyDeferred.await()?.let { ContentCache.homeDailySongs = it }
                    plsDeferred.await()?.let { ContentCache.homeRecommendPlaylists = it }
                    topDeferred.await()?.let { ContentCache.homeNewSongs = it }
                }
                // 标记首页数据已预热：HomeScreen 进屏时据此跳过重复请求。
                ContentCache.markHomeWarmed()

                // 阶段一·五：封面预取到 Coil 全局 ImageLoader 的内存+磁盘缓存。
                // 必须紧跟在 Home 数据之后、其他网络任务之前——首页首帧的 AsyncImage
                // 依赖这批封面。原先收藏库刷新插在这里, 慢网络下它吃掉的预算会让
                // 封面一张都来不及预取, 首帧退化成现场加载(抖动 + 掉帧)。
                val loader = Coil.imageLoader(app)
                val urls = buildList {
                    ContentCache.homeDailySongs?.take(PREFETCH_PER_SECTION)?.forEach {
                        it.album?.picUrl?.takeIf(String::isNotBlank)?.let(::add)
                    }
                    ContentCache.homeRecommendPlaylists?.take(PREFETCH_PER_SECTION)?.forEach {
                        it.coverUrl.takeIf(String::isNotBlank)?.let(::add)
                    }
                    ContentCache.homeNewSongs?.take(PREFETCH_PER_SECTION)?.forEach {
                        it.album?.picUrl?.takeIf(String::isNotBlank)?.let(::add)
                    }
                }.distinct().mapNotNull { CoverUrls.small(it) }

                coroutineScope {
                    // 并发上限 4: 封面最多 3 段 × 6 张 = 18 个, 一次性全 async 会堆出
                    // 十几个阻塞在 OkHttp 队列上的协程抢 IO 线程。分块后峰值并发可控,
                    // 给同一启动阶段的其他工作留出带宽, 又不明显拖长预取。
                    urls.chunked(4).forEach { chunk ->
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

            // v1.5.1 · C：把刚拿到的首页数据落盘，供下次无网冷启动使用。
            runCatchingCancellable {
                HomeSnapshot.save(
                    app,
                    daily = ContentCache.homeDailySongs,
                    playlists = ContentCache.homeRecommendPlaylists,
                    newSongs = ContentCache.homeNewSongs,
                    toplists = ContentCache.toplistItems,
                )
            }

            // v3.1.0 · P1-B：连接预热已在上面（与首页预取并行）发出 —— 见那段注释。
            // 这里**不再**重复 launch：同一个 host 预热两次是纯粹的浪费，
            // 而池子共享之后第一次那三通就已经把连接建好并回池了。

            // 阶段三：收藏库刷新(已登录时)。3 次额外网络往返，同样谁都不等。
            if (CookieManager.hasCookie(app)) {
                scope.launch { runCatchingCancellable { LibraryManager.refreshFromCloud(app) } }
            }
            _homeFetchDone.value = true
        }
    }
}
