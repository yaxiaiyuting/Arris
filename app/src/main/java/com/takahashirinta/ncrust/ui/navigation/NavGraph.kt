package com.takahashirinta.ncrust.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.screen.*
import com.takahashirinta.ncrust.ui.settings.SettingsGroup
import com.takahashirinta.ncrust.ui.settings.SettingsGroupRoute
import com.takahashirinta.ncrust.ui.theme.AppMotion
import com.takahashirinta.ncrust.ui.theme.PageTransitionSetting
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * 路由参数的**编解码边界**（v3.8.0 · H08）。
 *
 * 导航库（navigation-common 2.8.5）在把路径参数放进 `arguments` **之前**就已经解码过了：
 * `NavDeepLink.getMatchingArguments` 对每个路径参数执行 `android.net.Uri.decode(...)`，
 * 然后才交给 `parseArgument`。本机 AAR 的 `classes.jar` 反汇编可以逐条看到那次调用。
 *
 * 所以：
 *  - **写**路由时编码一次（[NavRoutes] 里的 `encodePathSegment`）；
 *  - **读**参数时**不再解码**（`backStackEntry.arguments?.getString(...)` 直接就是原文）。
 *
 * 旧实现在读侧又调了一次 `URLDecoder.decode`，于是：
 *  - 名字里带 `%`（如 `100% Love`）时，第一次解码得到 `100% Love`，
 *    第二次把 `% L` 当成转义序列 ⇒ 抛 `IllegalArgumentException`（导航直接崩）；
 *  - 名字里带 `+`（如 `A+B`）时，form 解码器把 `+` 当空格 ⇒ 变成 `A B`。
 *
 * 这也是写侧必须用**路径**编码而不是 form 编码的原因：`URLEncoder.encode` 把空格
 * 编成 `+`，而 `Uri.decode` 不认 `+` ⇒ `A B` 会读回 `A+B`。见 [NavRouteCodec.encodePathSegment]。
 */
internal object NavRouteCodec {

    /**
     * 路径段编码：`URLEncoder` 的 `%XX` + UTF-8 语义是对的，只有空格被编成 `+`
     * 这一条不适配 `Uri.decode`。所以只把 `+` 换成 `%20`。
     *
     * 纯 JVM（不碰 `android.net.Uri`），因此编解码契约可以在普通单测里钉住。
     */
    fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
}

object NavRoutes {
    const val HOME = "home"
    const val ALBUM = "album/{albumId}"
    const val ARTIST = "artist/{artistId}"
    const val PLAYLIST = "playlist/{playlistId}/{playlistName}/{playlistCoverUrl}"
    const val SONG_DETAIL = "song/{songId}"

    /**
     * v2.2.0：qm 歌单详情。
     *
     * 详情路由把**身份三元组**编进路径：`playlistId`(tid) + `ownerId`(uin) + `dirId`。
     * ownerId 必须进路由 —— 否则「A 账号点进歌单 → 返回 → 切到 B 账号 → 系统恢复同一条
     * 路由」会拿 A 的 playlistId 去 B 的账号下查，运气好是 10004，运气不好是另一个歌单。
     *
     * v2.3.0 · A：**`QQ_PLAYLISTS` 列表路由与 `QqPlaylistScreen` 已删除** ——
     * 歌单列表现在平铺在库页的「歌单」tab 里（`LibraryPlaylistsTab`），
     * 「一步可达」之后那个二级列表页就没有存在意义了（留着它等于留一条没有入口的死路由）。
     */
    const val QQ_PLAYLIST_DETAIL = "qqplaylist/{playlistId}/{ownerId}/{dirId}/{playlistName}"

    /**
     * v2.3.0 · B：**本地歌单**详情 / 编辑页。
     *
     * 路由把 [PlaylistKey] 的三元组原样编进路径（与 QQ 详情同一条纪律：
     * 归属账号必须进路由，否则「A 账号点进去 → 返回 → 切到 B 账号 → 系统恢复同一条路由」
     * 会拿 A 的歌单 id 去 B 的账号下查）。
     *
     * 这里**没有** `dirId`：本地歌单的 `dirId` 是载荷、存在本地记录里
     * （`LocalPlaylist.dirId`），请求详情时由仓库读出来 —— 路由里再带一份就成了第二处真相。
     */
    const val LOCAL_PLAYLIST_DETAIL = "localplaylist/{source}/{ownerId}/{playlistId}"

    /**
     * v2.4.0 · E：**带音源的**详情路由（艺人 / 专辑 / 单曲）。
     *
     * ## 为什么必须新增一套而不是改旧的
     *
     * 旧路由的 id 参数是 [NavType.LongType]，而 **qm 的身份是字符串**：
     * 艺人是 `singerMID`、专辑是 albumMid、单曲是 songmid（形状 `0025NhlN2yWrP4`）。
     * 用 Long 装它只有两条路，两条都是错的：要么给 QQ 编一个假数字 id
     * （那正是「给错专辑 / 错单曲」的温床），要么把它当 ncm 的 id 用。
     *
     * 所以新的三段路由里 `source` 与 id **一律是 `StringType`**（QQ 的 id 天然是字符串，
     * ncm 的十进制 id 写成字符串也不丢信息），旧的单段 `Long` 路由**原样保留**：
     * 剪贴板识别、`resolveAndNavigate`、首页/库页那些「只有 ncm id」的老调用点
     * 一个都不用改（它们构造出的就是 ncm 身份，见下面的 composable）。
     *
     * 注册顺序上两套路由互不冲突：`album/{albumId}` 只吃一段路径，
     * `album/{source}/{albumId}` 吃两段，导航库的深链正则无法互相匹配。
     */
    const val ARTIST_SRC = "artist/{source}/{artistId}/{artistName}"
    const val ALBUM_SRC = "album/{source}/{albumId}"

    /**
     * v2.4.0 · E / v3.8.1 · H13：带音源的单曲路由（**可选载荷走查询段**）。
     *
     * ## 为什么载荷必须是可选段
     *
     * `song/{source}/{songId}` 是 QQ 单曲页唯一的信息来源，而 QQ 的取链与跨源召回都需要
     * 路由里**没有**的东西：
     *  - 取链：`QqApi.fetchPlayUrl` 首行 `if (songMid == null) return null` —— songmid 是硬依赖；
     *  - 召回：`CrossSourceMatcher` 没有共享 id 空间，关键词只能由「曲名 + 艺人」构成。
     *
     * 旧路由把这些全部丢掉，于是页面拿到的锚点既取不了链也搜不了（H13 的本体）。
     *
     * 载荷走**查询段**而不是新增路径段，是因为查询参数可以带默认值：老的
     * `song/{source}/{songId}`（一个载荷都不带）仍然匹配同一个目的地 —— 若换成
     * 更多路径段，就必须再注册一个 `composable`，两套页面会各自漂移。
     * 匹配语义有据可查：navigation-common 2.8.5 的 `NavDeepLink.parsePath` 在第一个
     * `?` 处切开，路径正则只覆盖 path 部分并允许 `($|(\?(.)*)|(\#(.)*))`；缺省的查询参数
     * 由 `navArgument(defaultValue = "")` 兜底（`missingRequiredArguments` 会跳过带默认值的参数）。
     */
    const val SONG_SRC =
        "song/{source}/{songId}?sourceId={sourceId}&mediaId={mediaId}&name={name}&artist={artist}"

    /**
     * v2.8.0：**设置二级页**（一级分组卡片点进来的详情页）。
     *
     * 路由模板与参数名取自 [SettingsGroupRoute]（纯逻辑、JVM 可单测）——
     * 这里只做引用，不再写第二份字面量：`"settings/{group}"` 与 `"group"` 各只有一处。
     *
     * 为什么走导航库而不是设置页内部状态 + BackHandler（结构探针 §4.2 选型）：
     * 返回栈正确性由库保证（进程重建后系统会把整条路由重新喂回来），转场复用
     * `pageTransitionEnabled` 那一处真相，骨架直接复用 [com.takahashirinta.ncrust.ui.components.DetailScaffold]。
     *
     * ⚠️ 进入时**不要**加 `launchSingleTop = true`：连续点两个不同分组时会复用栈顶条目、
     * 参数不更新（表现为「点 B 分组却还是 A 分组的内容」）。
     */
    const val SETTINGS_GROUP = SettingsGroupRoute.PATTERN

    /** v2.8.0：设置二级页路由（`settings/{SettingsGroup.id}`）。 */
    fun settingsGroup(groupId: String) = SettingsGroupRoute.route(groupId)

    fun album(albumId: Long) = "album/$albumId"
    fun artist(artistId: Long) = "artist/$artistId"
    fun playlist(id: Long, name: String = "", coverUrl: String = "") =
        "playlist/$id/${NavRouteCodec.encodePathSegment(name)}/${NavRouteCodec.encodePathSegment(coverUrl)}"
    fun song(songId: Long) = "song/$songId"

    /**
     * v2.4.0 · E：带音源的艺人路由（QQ 的 id 是 `singerMID`）。
     *
     * v2.6.1 · P0：**多了一段 `artistName`**。
     *
     * 为什么必须带上名字（这不是"顺手多传一个显示字段"）：
     * 艺人页拿锚点名字当**搜索关键词**去拉对端热门曲、并对端召回
     * （`CatalogAggregator.loadArtist`）。名字为空时搜索接口返回空列表 ——
     * 页面不会报错，只会**静默地什么都召不回**，标题也退化成「未知艺人」。
     *
     * 而 QQ 的 `singerMID` **没有**「按 mid 取艺人名」的既有接口
     * （mid 只出现在专辑/曲目列表里），所以这个名字在 QQ 侧**只能**由调用方带过来
     * —— 用户点的那首歌里明明就有 `artists[0].name`。
     * 本版之前 QQ 艺人页不可达，所以这个空白从未暴露；现在可达了，
     * 一个标题为「未知艺人」的页面会被读成"还是坏的"。
     *
     * 与 [PLAYLIST]、[QQ_PLAYLIST_DETAIL] 同一条纪律：路由里没有的信息，
     * 页面就只能靠猜；而这里能拿到，所以带过来。
     */
    fun artist(source: MusicSource, id: String, name: String = "") =
        "artist/${source.key}/$id/" + NavRouteCodec.encodePathSegment(name)

    /** v2.6.1：直接吃一个身份决策对象，避免调用方把三个字段拆开再拼错。 */
    fun artist(target: com.takahashirinta.ncrust.source.ArtistNav.Direct) =
        artist(target.source, target.id, target.name)

    /** v2.4.0 · E：带音源的专辑路由。 */
    fun album(source: MusicSource, id: String) = "album/${source.key}/$id"

    /**
     * v2.4.0 · E：带音源的单曲路由。
     *
     * v3.8.1 · H13：多了四个**可选载荷**（songmid / media_mid / 曲名 / 艺人）。
     *
     * 为什么不带它们页面就是坏的：单曲页的锚点原先只由 `(source, id)` 构造，而
     *  - QQ 取链硬依赖 songmid（`QqApi.fetchPlayUrl` 首行 `if (songMid == null) return null`）；
     *  - 跨源召回硬依赖「曲名 + 艺人」（[com.takahashirinta.ncrust.crosssource.CrossSourceMatcher]
     *    没有共享 id 空间）—— 名字为空时搜索关键词为空，静默零结果。
     *
     * 载荷**按值取舍**：空的字段不进查询串，所以
     *  - 老调用点（只给两个参数）产出的路由与 v3.8.0 **逐字节相同**；
     *  - ncm 侧 sourceId/mediaId 恒为 null，路由里也不会多出空段。
     * 编码一律走 [NavRouteCodec.encodePathSegment]（`%XX`，空格写成 `%20` 而不是 `+`），
     * 读侧**不再解码** —— 导航库已经解过一次（见 [NavRouteCodec] 的 H08 说明）。
     */
    fun song(
        source: MusicSource,
        id: String,
        sourceId: String? = null,
        mediaId: String? = null,
        name: String = "",
        artist: String = "",
    ): String {
        val base = "song/${source.key}/$id"
        val extras = ArrayList<String>(4)
        sourceId?.takeIf { it.isNotBlank() }
            ?.let { extras += "sourceId=" + NavRouteCodec.encodePathSegment(it) }
        mediaId?.takeIf { it.isNotBlank() }
            ?.let { extras += "mediaId=" + NavRouteCodec.encodePathSegment(it) }
        name.takeIf { it.isNotBlank() }
            ?.let { extras += "name=" + NavRouteCodec.encodePathSegment(it) }
        artist.takeIf { it.isNotBlank() }
            ?.let { extras += "artist=" + NavRouteCodec.encodePathSegment(it) }
        return if (extras.isEmpty()) base else base + "?" + extras.joinToString("&")
    }

    fun qqPlaylistDetail(id: String, ownerId: String, dirId: Long, name: String) =
        "qqplaylist/$id/$ownerId/$dirId/" + NavRouteCodec.encodePathSegment(name)

    /** v2.3.0 · B：本地歌单路由。三段都做 URL 编码 —— `PlaylistKey.id` 可能是 `local:...`。 */
    fun localPlaylist(sourceKey: String, ownerId: String, playlistId: String): String =
        "localplaylist/" + NavRouteCodec.encodePathSegment(sourceKey) +
            "/" + NavRouteCodec.encodePathSegment(ownerId) +
            "/" + NavRouteCodec.encodePathSegment(playlistId)
}

@Composable
fun MainNavGraph(
    navController: NavHostController,
    onSongClick: (SongItem) -> Unit,
    onQqRelogin: () -> Unit = {},
    onReplaceAndPlay: (List<SongItem>) -> Unit = {},
    onInsertNext: (List<SongItem>) -> Unit = {},
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
    /**
     * v2.5.1 · F：页面转场开关（「页面切换动效」，默认启用）。
     *
     * 由 `MainScreen` 持有状态（设置页改一次 → 这里立刻拿到新值 → 下一次导航即生效，
     * **不需要重启**）。默认参数取 [PageTransitionSetting.DEFAULT_ENABLED] 而不是写死 `true`，
     * 是为了让「默认值只有一处真相」。
     */
    pageTransitionEnabled: Boolean = PageTransitionSetting.DEFAULT_ENABLED,
    /**
     * v2.8.0：设置**二级页**的内容。
     *
     * 为什么做成「内容 lambda」而不是给 [MainNavGraph] 加十几个设置回调参数：
     * 二级页要用到的状态全部提升在 `MainScreen` / `MainActivity`（主题、语言、页面转场、
     * cookie 刷新计数、PlayerViewModel…），而这些状态的持有者本来就是 `MainScreen` ——
     * 它就在 [MainNavGraph] 的调用点上。走一个 lambda，参数清单只在一处（`MainScreen`），
     * 导航图本身不必知道「设置页需要什么」。
     *
     * 默认值渲染空内容：这样 [MainNavGraph] 的既有调用点（含测试/预览）一个都不用改。
     */
    settingsGroupContent: @Composable (SettingsGroup, onBack: () -> Unit) -> Unit = { _, _ -> },
    startDestination: String = NavRoutes.HOME
) {
    // v2.5.1 · F：页面切换动效**用户可配，默认启用**。
    //
    // v2.5.0 的注释（「页面转场期间新旧两页同帧渲染, slide/fade 每帧都要全屏合成,
    // 低端机上是切换动作的主要掉帧源, 所以显式关掉」）描述的**代价依然成立**，
    // 本版没有推翻它 —— 推翻的是它的**处置方式**：
    // 从「一刀切不做」改成「默认启用 + 用户可关」，关掉时逐字节回到 v2.5.0 的行为。
    // 用户拍板见 TASK / v2.5.1 release notes；量化数据（release 包、两台真机、
    // dumpsys gfxinfo framestats）见 docs/verification/v2.5.1/。
    //
    // 四个转场都在**导航发生的那一刻**求值（lambda 体内），所以
    // 「设置页关掉 → 下一次点进详情页就已经没有动画」，无需重启、无需重建 NavHost。
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { pageEnterTransition(pageTransitionEnabled) },
        exitTransition = { pageExitTransition(pageTransitionEnabled) },
        popEnterTransition = { pagePopEnterTransition(pageTransitionEnabled) },
        popExitTransition = { pagePopExitTransition(pageTransitionEnabled) }
    ) {
        composable(NavRoutes.HOME) {
            // 不渲染任何内容，由 MainScreen 的 Scaffold 内容填充
        }

        /**
         * v2.8.0：设置二级页。
         *
         * **未知 group 的兜底**（结构探针 §4.3-4）：进程重建 / 版本升级删掉分组之后，
         * 系统可能把一条对不上的 `settings/xxx` 路由重新喂回来。解析失败必须
         * **安全回落一级页**：
         *  - 能弹栈就弹（回到设置 tab，用户看到的就是一级页）；
         *  - 弹不动（这条路由就是栈底，例如日后被当成深链入口）才 `navigate(HOME)`，
         *    并 `launchSingleTop` 避免压出一串 HOME。
         * 两条路都**不抛异常**，也不留白屏 —— 与 `AccentSource.getOrDefault(PRESET)` 同一条纪律。
         */
        composable(
            route = NavRoutes.SETTINGS_GROUP,
            arguments = listOf(navArgument(SettingsGroupRoute.ARG) { type = NavType.StringType })
        ) { backStackEntry ->
            val group = SettingsGroupRoute.resolve(
                backStackEntry.arguments?.getString(SettingsGroupRoute.ARG)
            )
            if (group == null) {
                LaunchedEffect(Unit) {
                    if (!navController.popBackStack()) {
                        navController.navigate(NavRoutes.HOME) { launchSingleTop = true }
                    }
                }
            } else {
                // 返回**只**用 popBackStack（与上面 Album 那一段同一条纪律）：
                // navigate(HOME) 会把栈压平、丢掉 tab 屏的状态。
                settingsGroupContent(group) { navController.popBackStack() }
            }
        }

        composable(
            route = NavRoutes.ALBUM,
            arguments = listOf(navArgument("albumId") { type = NavType.LongType })
        ) { backStackEntry ->
            val albumId = backStackEntry.arguments?.getLong("albumId") ?: return@composable
            // 旧路由只有 ncm 的十进制 id ⇒ 它构造出来的**就是 ncm 身份**。
            // 这里不猜、不查表：能走到这条路由的调用点（剪贴板识别、resolveAndNavigate、
            // 首页/库页）本来就只认识 ncm 的 id。
            AlbumDetailScreen(
                sourceKey = MusicSource.NETEASE.key,
                albumId = albumId.toString(),
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onArtistClick = { target -> navController.navigate(NavRoutes.artist(target)) },
                onReplaceAndPlay = onReplaceAndPlay,
                onInsertNext = onInsertNext,
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.ALBUM_SRC,
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("albumId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val sourceKey = backStackEntry.arguments?.getString("source") ?: return@composable
            val albumId = backStackEntry.arguments?.getString("albumId") ?: return@composable
            AlbumDetailScreen(
                sourceKey = sourceKey,
                albumId = albumId,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onArtistClick = { target -> navController.navigate(NavRoutes.artist(target)) },
                onReplaceAndPlay = onReplaceAndPlay,
                onInsertNext = onInsertNext,
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.ARTIST,
            arguments = listOf(navArgument("artistId") { type = NavType.LongType })
        ) { backStackEntry ->
            val artistId = backStackEntry.arguments?.getLong("artistId") ?: return@composable
            ArtistDetailScreen(
                sourceKey = MusicSource.NETEASE.key,
                artistId = artistId.toString(),
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onAlbumClick = { source, id -> navController.navigate(NavRoutes.album(source, id)) },
                // v2.6.0 · P1：歌手页「全部播放」。两段路由都透传 —— 漏一段就是
                // 「从聚合歌手页点得动、从老路由进来点不动」这种只在特定入口复现的缺陷。
                onPlayAllSongs = onReplaceAndPlay,
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.ARTIST_SRC,
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("artistId") { type = NavType.StringType },
                // v2.6.1：名字是可选的（空串 = 调用方也不知道），但**段必须在**
                // —— 路径参数没有"可省略"这回事，少一段就整条路由匹配不上。
                navArgument("artistName") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val sourceKey = backStackEntry.arguments?.getString("source") ?: return@composable
            val artistId = backStackEntry.arguments?.getString("artistId") ?: return@composable
            // 名字已被导航库解码过**一次**（见 [NavRouteCodec]），这里直接取用 —— 再解一次
            // 会把 `+` 变成空格、把含 `%` 的名字解崩。
            val artistName = backStackEntry.arguments?.getString("artistName") ?: ""
            ArtistDetailScreen(
                sourceKey = sourceKey,
                artistId = artistId,
                initialName = artistName,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onAlbumClick = { source, id -> navController.navigate(NavRoutes.album(source, id)) },
                // v2.6.0 · P1：歌手页「全部播放」。两段路由都透传 —— 漏一段就是
                // 「从聚合歌手页点得动、从老路由进来点不动」这种只在特定入口复现的缺陷。
                onPlayAllSongs = onReplaceAndPlay,
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.PLAYLIST,
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
                navArgument("playlistName") { type = NavType.StringType; defaultValue = "" },
                navArgument("playlistCoverUrl") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: return@composable
            // 名字与封面 URL 都由导航库解码过一次（见 [NavRouteCodec]）：直接取用。
            // 封面 URL 里必然带 `:` / `/` / `&`，二次解码会改坏它。
            val name = backStackEntry.arguments?.getString("playlistName") ?: ""
            val cover = backStackEntry.arguments?.getString("playlistCoverUrl") ?: ""
            PlaylistDetailScreen(
                playlistId = playlistId,
                playlistName = name,
                playlistCoverUrl = cover,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onReplaceAndPlay = onReplaceAndPlay,
                onInsertNext = onInsertNext,
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.QQ_PLAYLIST_DETAIL,
            arguments = listOf(
                navArgument("playlistId") { type = NavType.StringType },
                navArgument("ownerId") { type = NavType.StringType },
                navArgument("dirId") { type = NavType.LongType },
                navArgument("playlistName") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val pid = backStackEntry.arguments?.getString("playlistId") ?: return@composable
            val owner = backStackEntry.arguments?.getString("ownerId") ?: return@composable
            val dirId = backStackEntry.arguments?.getLong("dirId") ?: 0L
            // 已由导航库解码过一次（见 [NavRouteCodec]），不再解第二次。
            val name = backStackEntry.arguments?.getString("playlistName") ?: ""
            QqPlaylistDetailScreen(
                onRelogin = onQqRelogin,
                playlistId = pid,
                ownerId = owner,
                dirId = dirId,
                playlistName = name,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onReplaceAndPlay = onReplaceAndPlay,
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.LOCAL_PLAYLIST_DETAIL,
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("ownerId") { type = NavType.StringType },
                navArgument("playlistId") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            // 三段都是导航库解码过的值（见 [NavRouteCodec]）：`playlistId` 可能是
            // `local:...` 这类带冒号的串，二次解码会把它改坏。直接取用。
            val sourceKey = backStackEntry.arguments?.getString("source") ?: ""
            val owner = backStackEntry.arguments?.getString("ownerId") ?: ""
            val pid = backStackEntry.arguments?.getString("playlistId") ?: ""
            LocalPlaylistDetailScreen(
                playlistKey = com.takahashirinta.ncrust.source.PlaylistKey(
                    source = com.takahashirinta.ncrust.source.MusicSource.fromKey(sourceKey),
                    id = pid,
                    ownerId = owner,
                ),
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onReplaceAndPlay = onReplaceAndPlay,
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.SONG_DETAIL,
            arguments = listOf(navArgument("songId") { type = NavType.LongType })
        ) { backStackEntry ->
            val songId = backStackEntry.arguments?.getLong("songId") ?: return@composable
            // 旧路由 = ncm 身份（理由同 ALBUM 那一段）。
            SongDetailScreen(
                sourceKey = MusicSource.NETEASE.key,
                songId = songId.toString(),
                onBack = { navController.popBackStack() },
                onPlay = onSongClick,
                onAddToNext = onSongInsertNext
            )
        }

        composable(
            route = NavRoutes.SONG_SRC,
            arguments = listOf(
                navArgument("source") { type = NavType.StringType },
                navArgument("songId") { type = NavType.StringType },
                // v3.8.1 · H13：四个**可选**载荷。`defaultValue = ""` 是「老路由继续可用」的关键：
                // navigation-common 2.8.5 的 `NavDeepLink.getMatchingArguments` 只对**声明了**的参数
                // 逐个从 URI 取值（缺省时循环体不执行、不判失败），随后 `missingRequiredArguments`
                // 会跳过 `isDefaultValuePresent` 的参数 —— 所以不带查询串的
                // `song/qqmusic/123` 仍然匹配本目的地，四个参数取到空串（= 没有载荷）。
                navArgument("sourceId") { type = NavType.StringType; defaultValue = "" },
                navArgument("mediaId") { type = NavType.StringType; defaultValue = "" },
                navArgument("name") { type = NavType.StringType; defaultValue = "" },
                navArgument("artist") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            // 四个载荷都是导航库**已经解码过**的值（H08：读侧不再解码）。
            val sourceKey = backStackEntry.arguments?.getString("source") ?: return@composable
            val songId = backStackEntry.arguments?.getString("songId") ?: return@composable
            SongDetailScreen(
                sourceKey = sourceKey,
                songId = songId,
                sourceId = backStackEntry.arguments?.getString("sourceId").orEmpty(),
                mediaId = backStackEntry.arguments?.getString("mediaId").orEmpty(),
                songName = backStackEntry.arguments?.getString("name").orEmpty(),
                artistName = backStackEntry.arguments?.getString("artist").orEmpty(),
                onBack = { navController.popBackStack() },
                onPlay = onSongClick,
                // v3.8.1 · H13：「添加到下一首播放」此前默认空实现（点了没反应）。
                // 本页这一行的行内动作要能被"不可播放时禁用"，前提是它本来就能用。
                onAddToNext = onSongInsertNext
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────
// v2.5.1 · F：页面转场的四个方向
//
// 分散在四个 private 函数里，是为了让「关掉时到底挂的是什么」一眼可查：
// 四个函数的第一行**都是**同一个短路 `if (!enabled) …None`。
// 这样「关闭时不能有残留动画计算」不是靠调用点自觉，而是这四条路径的唯一形状。
//
// 位移取屏宽的 1/12（`PAGE_SLIDE_DIVISOR`）：足够读出方向，又不至于让新旧两页
// 各滑过半个屏幕（滑得越远，转场期间需要重绘的像素带越宽）。这个「小位移 + 淡入淡出」
// 的组合是低端机的折中：方向感来自位移，遮盖感来自 alpha，两者都不需要额外测量。
//
// 时长与曲线**只有一处真相**：`AppMotion.pageTransitionSpec()`（260ms + FastOutSlowInEasing）。
// 关掉时 `PageTransitionSetting.durationMs(false) == 0`，且这里连 spec 都不会构造。
// ─────────────────────────────────────────────────────────────────────────

/** 转场位移 = 屏宽 / 该除数。12 ⇒ 360dp 屏上约 30dp。 */
private const val PAGE_SLIDE_DIVISOR = 12

/** 前进（push）：新页从右侧滑入并淡入。 */
private fun pageEnterTransition(enabled: Boolean): EnterTransition =
    if (!enabled) {
        EnterTransition.None
    } else {
        slideInHorizontally(
            animationSpec = AppMotion.pageTransitionSpec(),
            initialOffsetX = { it / PAGE_SLIDE_DIVISOR }
        ) + fadeIn(animationSpec = AppMotion.pageTransitionSpec())
    }

/** 前进时的旧页：向左让位并淡出。 */
private fun pageExitTransition(enabled: Boolean): ExitTransition =
    if (!enabled) {
        ExitTransition.None
    } else {
        slideOutHorizontally(
            animationSpec = AppMotion.pageTransitionSpec(),
            targetOffsetX = { -it / PAGE_SLIDE_DIVISOR }
        ) + fadeOut(animationSpec = AppMotion.pageTransitionSpec())
    }

/** 返回（pop）：上一页从左侧滑回并淡入。 */
private fun pagePopEnterTransition(enabled: Boolean): EnterTransition =
    if (!enabled) {
        EnterTransition.None
    } else {
        slideInHorizontally(
            animationSpec = AppMotion.pageTransitionSpec(),
            initialOffsetX = { -it / PAGE_SLIDE_DIVISOR }
        ) + fadeIn(animationSpec = AppMotion.pageTransitionSpec())
    }

/** 返回时被关掉的那一页：向右退出并淡出。 */
private fun pagePopExitTransition(enabled: Boolean): ExitTransition =
    if (!enabled) {
        ExitTransition.None
    } else {
        slideOutHorizontally(
            animationSpec = AppMotion.pageTransitionSpec(),
            targetOffsetX = { it / PAGE_SLIDE_DIVISOR }
        ) + fadeOut(animationSpec = AppMotion.pageTransitionSpec())
    }