package com.takahashirinta.ncrust.ui.i18n

/**
 * 全部 UI 文案的根容器（8 种语言各给一份实参）。
 *
 * ## 参数预算：为什么文案必须往嵌套组里放
 *
 * dex 的 `invoke-*` 指令寄存器是 8 位 ⇒ **单个方法最多 255 个参数寄存器**。
 * 而本类是「一个 data class 装全部 UI 文案」的结构，主构造器参数一多，
 * `zh_CN.kt` 顶层 `val zhCN = Strings(...)` 的 `<clinit>` 里那条 invoke 就会越界。
 *
 * ```
 * 槽位 = this(1) + N + ceil(N/32) 个默认值 mask + DefaultConstructorMarker(1)   ≤ 255
 * N = 245 ⇒ 1 + 245 + 8 + 1 = 255   ← v2.3.0 ～ v2.5.2 的真实状态：**一个空位都没有**
 * N = 246 ⇒ 1 + 246 + 8 + 1 = 256   ← 溢出
 * ```
 *
 * 溢出的表现是 **编译照过、真机启动即崩**（`ClassFormatError: Too many arguments in
 * method signature` / `VerifyError: Verifier rejected class …`），
 * v2.0.0 · HF1 与 v2.3.0 各踩过一次。
 *
 * ## v2.5.3：一次真正的拆分（245 → 128）
 *
 * 前面四版都是「腾 2 花 1」式的挪腾，余量始终在 0~1 之间 —— 每加一条文案都要先搬家。
 * 本版按**功能面**把 120 条搬进三个组：
 *
 * | 组 | 条数 | 功能面 | 主要消费文件 |
 * |---|---|---|---|
 * | [SettingsStrings] | 64 → **78** | 设置页及其卫星对话框（v2.8.0 加 14 条分组卡片文案） | `ui/screen/UserScreen.kt` 等 |
 * | [AboutStrings] | 25 | 关于页 | `ui/screen/AboutScreen.kt` |
 * | [PlayerUiStrings] | 31 | 播放器界面（传输控件 / 歌词页 / 队列面板） | `ui/player/` 下的若干文件 |
 * | [WaveformStrings] | **16** | 波形效果分级（v2.8.0 新开一组，理由见该类 KDoc） | `ui/player/waveform/` |
 *
 * 净效果：`245 - 120 + 3 = 128` 个主构造参数 ⇒ `1 + 128 + 4 + 1 = 134` 个 dex 槽，
 * **余量 121**。分组依据与调用点分布见 `docs/verification/v2.5.3/probe-strings.md`。
 *
 * v2.8.0 起：[SettingsStrings] 涨到 78（+14 条分组卡片文案），另开 [WaveformStrings]（16 条），
 * 外层主构造参数 128 → **136** ⇒ `1 + 136 + 5 + 1 = 143` 个 dex 槽，**余量 112**。
 *
 * ## API 兼容：老调用点一行都没改
 *
 * 搬走的每一条都在本类**类体**里留了一条同名转发属性
 * （`val qualitySectionTitle: String get() = settings.qualitySectionTitle`）。
 * 转发属性不进构造函数，所以既不占 dex 槽，又让 `strings.qualitySectionTitle`
 * 这种写法继续可用 —— 与 v2.0.0 · HF1 给 [OfflineStrings] 的做法完全一致。
 *
 * 新代码可以直接写 `strings.settings.qualitySectionTitle`；
 * 旧写法 `strings.qualitySectionTitle` **不会**被移除（36 个消费文件、
 * 290 个调用点依赖它，见 `StringsMigrationTest`）。
 *
 * ## 长期防线
 *
 * `StringsConstructorBudgetTest` 会加载本类并断言主构造器参数 **< 150**，
 * 同时对**每一个**嵌套组做同样的监控。加文案请走「往组里加字段」，
 * 需要新组就在类体里补转发属性。**不要**直接往主构造器加参数。
 */
data class Strings(
    // Navigation tabs
    val tabHome: String,
    val tabLibrary: String,
    val tabSearch: String,
    val tabUser: String,

    // Common
    val cancel: String,
    val close: String,
    val retry: String,
    val loading: String,
    val back: String,

    // v2.5.3 · P0：设置页那一组（64 条）搬进 [SettingsStrings]。
    // 理由见本类 KDoc「参数预算」一节：主构造器当时是 245 = 255 个 dex 槽用满。
    // 调用点由类体里的转发属性原样保住 —— `strings.xxx` 一行都不用改。
    val settings: SettingsStrings,
    // v2.8.0：波形效果分级那一组（16 条）落进 [WaveformStrings]。
    // 为什么不塞进 [SettingsStrings]：那边已经是 78，而组预警线是 80 —— 16 条进去会到 94，
    // 每一轮测试都打 WARN，违背「再加字段请拆组」的纪律（组规模见 StringsConstructorBudgetTest）。
    // 组名故意叫 `waveform` 而不是 `visualizer`：避免与
    // `ui/player/waveform/VisualizerStrings.kt`（那边是**属性名常量**，不是文案）混淆。
    val waveform: WaveformStrings,
    // v2.5.3 · P0：播放器界面那一组（31 条）搬进 [PlayerUiStrings]。
    // 理由见本类 KDoc「参数预算」一节：主构造器当时是 245 = 255 个 dex 槽用满。
    // 调用点由类体里的转发属性原样保住 —— `strings.xxx` 一行都不用改。
    val playerUi: PlayerUiStrings,

    /** E：榜单区块标题。 */
    val toplistSectionTitle: String,
    // v1.4.0 · 音乐人推荐卡片（首页分节标题 + 卡片副标题）
    val artistRecoTitle: String,
    val artistRecoDesc: String,
    /**
     * v2.0.0 · HF1：离线 / 缓存相关的 19 条文案**收进一个嵌套组**，不再是 [Strings] 的构造参数。
     *
     * 为什么必须这么做（真机启动崩溃的根因）：dex 的 `invoke-*` 指令寄存器数是 8 位 ⇒
     * **单个方法最多 255 个参数寄存器**。本文件是「一个 data class 装全部 UI 文案」的结构，
     * v1.9.3 时已有 **240** 个构造参数；v2.0.0 一次性加了 21 条（T2 两条、T4 两条、T3 十七条）
     * 后变成 **261** 个 ⇒ `zh_CN.kt` 顶层 `val zhCN = Strings(...)` 的 `<clinit>` 里那条 invoke
     * 越过上限，ART 校验**直接拒绝整个类**：PCL110（API 36 / release）与 S6（API 24 / debug）
     * 都表现为**启动即崩**：
     * `VerifyError: Verifier rejected class …Zh_CNKt: <clinit>() … expected 6 argument registers,
     * method signature has 7 or more`。
     *
     * ⚠️ **JVM 单测与编译都发现不了它** —— 那是 dex/ART 层面的限制，只有真机（或模拟器）能暴露。
     *
     * 拆分口径：只挪「离线 / 缓存」这一组（同一个功能面、内聚），其余属性一个都没动；
     * [Strings] 类体里保留 19 个**成员转发属性**（`val xxx get() = offline.xxx`），
     * 所以全仓库 `strings.offlineCacheXxx` / `strings.cacheSizeLabel` 的调用点一个字都不用改。
     *
     * ⚠️ 给后来者：`Strings` 的构造参数现在是 **243** 个；dex 单个方法最多 254 个参数寄存器，
     * 而带默认参数的类还会生成一个「参数 + 2」的合成构造函数 ⇒ **实际余量只剩 ~9 个**。
     * 再加字段请继续拆组（嵌套 data class + 转发属性），不要硬加到构造参数上。
     */
    val offline: OfflineStrings,

    /**
     * v2.1.0 · C：多音源（qm）那一组。
     *
     * 为什么又拆一组：上面那条注释说得清楚 —— `Strings` 的构造参数已经贴着 dex 255 上限，
     * 再加字段**必须**继续拆组。这一组只有 3 条，但同样走分组，避免下次有人顺手往
     * 构造参数上直接加而踩上限（v2.0.0 · HF1 就是这么崩的）。
     */
    val source: SourceStrings,
    /**
     * v2.2.0：QQ 歌单文案组。
     *
     * **必须做成嵌套组，不能摊平成 [Strings] 的构造参数。** 这正是 v2.0.0 · HF1 踩过的坑：
     * `Strings` 的构造参数一旦逼近 dex 单方法 255 个参数寄存器上限，**编译能过、真机启动崩**
     * （构造函数是单个 `<init>` 方法，参数寄存器用满即 `VerifyError`）。
     * 所以新增文案一律进嵌套组，给 `Strings` 只加**一个**参数。
     */
    val playlists: PlaylistsStrings,
    /**
     * v2.3.0 · C/D：**曲目标签文案组**（版权可用性 + 原唱/翻唱）。
     *
     * 同样必须拆组（理由见上一条）。这一组承载的是「每一行歌曲都可能在渲染的角标」，
     * 8 种语言 × 7 条 = 56 个字符串 —— 摊平进构造参数会直接撞 dex 上限。
     */
    val tags: TagsStrings,
    /**
     * v2.3.0 · B：**本地歌单**文案组（列表 / 详情 / 增删 / 同步 / 清空）。
     *
     * 与 [playlists]（远程歌单镜像）分开是有意的：两者是**不同的功能面** ——
     * 远程歌单只读、按源隔离；本地歌单可编辑、可混装音源。
     * 文案放一起会让「移除」这类词在两处指不同的东西。
     */
    val localPlaylist: LocalPlaylistStrings,
    /**
     * v2.5.0 · D：**「添加到下一首播放」文案组**（[QueueStrings]）。
     *
     * **为什么必须拆组**：`Strings` 的构造参数在 245 个时**一个空位都没有** ——
     * `1 (this) + 245 + 8 (默认值 mask) + 1 (DefaultConstructorMarker) = 255`，正好用满；
     * 再加**一个**参数就会在类加载期抛
     * `ClassFormatError: Too many arguments in method signature`（编译照过，真机启动即崩）。
     *
     * 本组同时**吸收**了原先直接挂在构造参数上的 `actionInsertNext` / `actionAppendToQueue`
     * （语义上本来就是「队列动作」，与本版新增的 `actionAddToNext` 同一个面）：
     * **腾出 2 个位置、花掉 1 个，净余 1 个** ⇒ 构造参数由 245 降到 **244**。
     * 类体里的转发属性保住了全部既有调用点，一个字都不用改。
     */
    val queue: QueueStrings,

    /**
     * v2.5.1 · F：**页面转场（动效）文案组**（[MotionStrings]）。
     *
     * **为什么必须拆组、而且这是最后一个能拆的位置**：`Strings` 的构造参数在 245 个时
     * `1 (this) + 245 + 8 (默认值 mask) + 1 (DefaultConstructorMarker) = 255`，**正好用满**；
     * 再加一个参数就会在类加载期抛
     * `ClassFormatError: Too many arguments in method signature`（编译照过，真机启动即崩）。
     * v2.5.0 · D 把构造参数从 245 降到 **244**（腾 2 花 1），本组用掉**最后一个空位** ⇒ 回到 **245**。
     *
     * ⚠️ **下一个要加文案的人：余量现在是 0。**
     * 必须**先腾出一个位置**（把一条既有文案搬进某个语义相符的嵌套组、并在类体里留转发属性），
     * 然后才能加第二组 —— 不能顺手直接加。`StringsConstructorBudgetTest` 会挡住越界的那一次。
     */
    val motion: MotionStrings,

    // v1.5.1 · C：无网络时的首页降级空态（标题 / 提示）。
    //
    // ★ v2.3.0：这两条**从构造参数搬进了 [OfflineStrings]**（语义上本来就属于「离线」那一组），
    //   调用点由下面的转发属性原样保住。搬家的原因是硬约束，不是审美：
    //   `Strings` 的构造参数在 **245** 个时就已经顶到 JVM/dex 的 255 槽上限
    //   （245 + 8 个默认值 mask + 1 个 DefaultConstructorMarker + this = 255），
    //   再加**一个**参数就会在类加载期抛 `ClassFormatError: Too many arguments in method signature`。
    //   本版需要两个新文案组，所以必须**先腾出两个位置**。细节与实测见 [Strings] 的 KDoc 与
    //   `StringsConstructorBudgetTest`。

    // Home screen
    val dailySongsTitle: String,
    val recommendPlaylistTitle: String,
    val newSongsTitle: String,
    val refreshLabel: String,
    val noMoreContent: String,
    val trackCountSongs: (Int) -> String,
    // 主页推荐歌单里首位的私人 FM 电台卡（标题用用户昵称, 不是 uid）
    val fmRadioTitle: (String) -> String,
    // 拿不到用户资料时电台卡仍常驻的通用标题
    val fmRadioTitleGeneric: String,
    val fmRadioSubtitle: String,

    // Library screen
    val categoryTracks: String,
    val categoryAlbums: String,
    val categoryPlaylists: String,
    val noSavedSongs: String,
    val noSavedAlbums: String,
    val noPlaylists: String,
    val notLoggedInForPlaylists: String,
    val loadFailed: (String?) -> String,
    val trackCount: (Int) -> String,
    val albumArtistAndCount: (String, Int) -> String,

    // Search screen
    val searchCategoryTracks: String,
    val searchCategoryAlbums: String,
    val searchCategoryArtists: String,
    /**
     * 按歌词搜索（v3.3.0）—— 搜索页的第 4 个 tab。
     *
     * 与其它 `searchCategory*` 一样是**必需**属性（无默认值）：加它会让 8 个语言文件
     * 在编译期全部报错，从而不可能漏翻译。构造器预算不受影响 ——
     * `StringsConstructorBudgetTest` 断言的是参数个数上限，当前余量充足。
     */
    val searchCategoryLyrics: String,
    val searchPlaceholder: String,
    val searchSongsEmpty: String,
    val searchAlbumsEmpty: String,
    val searchArtistsEmpty: String,

    // Song / queue actions
    //
    // ★ v2.5.0 · D：原来的 `actionInsertNext` / `actionAppendToQueue` 两条**搬进了 [QueueStrings]**
    //   （它们和本版新增的 `actionAddToNext` 是同一个功能面）。搬家的原因与 v2.3.0 那两条相同：
    //   构造参数已经是 245 = 用满 255 槽，必须先腾位置。调用点由类体里的转发属性原样保住，
    //   细节见 [Strings.queue] 的 KDoc。
    val actionAddToLibrary: String,
    val actionGoToArtist: String,
    val actionGoToAlbum: String,

    // Native QR login (tablet / large screen)
    val qrLoginTitle: String,
    val qrScanHint: String,
    val qrScannedHint: String,
    val qrExpiredHint: String,
    val qrLoadFailed: String,
    val qrGenericLogin: String,
    val scanPrompt: String,
    val scanPermissionNeeded: String,
    val scanNoCookie: String,
    val scanSuccess: String,
    val scanFailed: String,
    val scanConnecting: String,
    val actionAddToPlaylist: String,
    val actionRemoveFromLibrary: String,
    val actionSaveAlbum: String,
    // 专辑已收藏时的按钮文案(取消收藏)
    val actionUnsaveAlbum: String,
    val unknownArtist: String,
    val playAllButton: String,

    // Play-all dialog
    val songCountFormat: (Int) -> String,
    /** 断点续播提示，参数是已格式化的时间点（如 "1:23"）。 */
    val resumeFromFormat: (String) -> String,
    val playNowTitle: String,
    val playNowDesc: String,
    val insertNextTitle: String,
    val insertNextDesc: String,

    // v2.5.3 · P0：关于页那一组（25 条）搬进 [AboutStrings]。
    // 理由见本类 KDoc「参数预算」一节：主构造器当时是 245 = 255 个 dex 槽用满。
    // 调用点由类体里的转发属性原样保住 —— `strings.xxx` 一行都不用改。
    val about: AboutStrings,

    // Detail page titles and content
    val artistDetailTitle: String,
    val albumDetailTitle: String,
    val playlistDetailTitle: String,
    val unknownArtistName: String,
    val noAlbums: String,
    val noHotSongs: String,
    val artistAlbumCount: (Int) -> String,
    val artistSongCount: (Int) -> String,
    val albumReleaseDate: (String) -> String,
    val albumLabel: (String) -> String,
    val artistDataLoadFailed: (Int) -> String,
    val artistStats: (Int, Int) -> String,

    // Accessibility content descriptions
    val coverDesc: String,
    val albumCoverDesc: String,
    val artistAvatarDesc: String,
    val playlistCoverDesc: String,

    // Search
    val clearSearchButton: String,

    // Song detail screen
    val songDetailTitle: String,
    val unknownAlbum: String,

    // Search history
    val searchHistoryClear: String,
    val searchHistoryEmpty: String,
    val searchHistoryDelete: String,

    /**
     * v2.5.4 · B：点了一条「信息不完整」的老搜索历史时给的提示。
     *
     * 什么算不完整：音源是 QQ、但记录里没有 songmid（v2.5.4 之前这张表只存裸 id，
     * 而 songmid 不可逆 —— 见 `SearchHistoryMigration`）。这种条目点下去必然取不到链，
     * 所以不静默入队，而是把标题填回搜索框让用户重新点一次带 songmid 的结果。
     */
    val searchHistoryLegacyHint: String,

    // Feedback toasts
    val addedToLibrary: String,
    val removedFromLibrary: String,

    // Playlist management (v1.3.0 · B2)
    val playlistCreateTitle: String,
    val playlistNameHint: String,
    val playlistPrivacy: String,
    val playlistPrivacyPublic: String,
    val playlistPrivacyPrivate: String,
    val playlistCreateConfirm: String,
    /** 含歌单名，如「已创建「我的歌单」」。 */
    val playlistCreated: (String) -> String,
    /** 创建成功后往歌单里塞歌的结果，count = 实际提交的曲目数。 */
    val playlistSongsAdded: (Int) -> String,
    val playlistCreateFailed: String,
    /** 服务端 405 限流，冷却可能超过 10 分钟。 */
    val playlistOpTooFrequent: String,

    // Add-to-playlist sheet (v1.3.0 · B3)
    val addToPlaylistTitle: String,
    val playlistNew: String,
    val playlistNoOwned: String,
    val addToPlaylistSuccess: String,
    /** 502 = 歌曲已在歌单里，按幂等成功提示。 */
    val addToPlaylistDuplicate: String,
    val addToPlaylistFailed: String,
    /** 歌曲长按菜单里的入口名（v1.3.0 · B3 起是真正的功能，此前只被当成队列按钮的 contentDescription）。 */
    val actionAddToPlaylistSheet: String,

    // Playlist edit / delete (v1.3.0 · B4)
    val playlistEditTitle: String,
    val playlistDescHint: String,
    val playlistSave: String,
    val playlistUpdated: String,
    /** 含歌单名，如「确定删除「我的歌单」？」 */
    val playlistDeleteConfirm: (String) -> String,
    val playlistDeleteWarning: String,
    val playlistDeleted: String,
    val playlistDeleteFailed: String,
    val playlistUnsubscribe: String,
    /** 从歌单里移除这首歌（仅自建歌单显示）。 */
    val removeFromPlaylist: String,
    /** 移除成功的提示（与 removeFromPlaylist 这个动作名分开，避免出现「从歌单移除」当反馈）。 */
    val removedFromPlaylist: String,
    val playlistDelete: String,

    // ---------------------------------------------------------------- v2.5.5 · G：聚合搜索的加载态 ----
    //
    // 这一组修的是「搜『晴天』先显示『qm 0 首』、5 秒后才变成真实数字」——
    // 那 5 秒里 0 是**假话**（它把"还没回来"显示成了"真的没有"）。
    // 根因是统计量的类型只能表达计数，不能表达「未知」；这五条给它一个合法的表示。
    //
    // ⚠️ 放在**主构造器**（而不是 SourceStrings 组）是有意的：`SourceStrings` 当时是 57 个参数、
    // 上限 60，只剩 3 个槽位；而主构造器 v2.5.4 只到 129，预算 150。
    // 加完之后主构造器是 134，仍然显著低于预算（`StringsConstructorBudgetTest` 会钉住）。

    /** 某一源还在检索中（**不显示 0**）。 */
    val searchSourcePending: String,
    /** 某一源**超时**（预算用完）。与「搜索中」分开：前者会自动有结果，后者需要用户动一下。 */
    val searchSourceTimeout: String,
    /**
     * 某一源**失败**（网络错误 / 服务端报错）。
     *
     * 与 [searchSourceTimeout] 分开是探针给的（`probe-search-qq-latency.md` §3 遗留①）：
     * 把「连不上」显示成「超时」等于替用户编了一个原因。
     */
    val searchSourceError: String,
    /** 这一轮没有发起该源的请求（未登录且不允许匿名）。同样不能显示 0。 */
    val searchSourceSkipped: String,
    /** 计数文案：`(条数) -> "30 首"`。 */
    val searchSourceCount: (Int) -> String,
    /**
     * 统计行：`(ncm侧文案, QQ 侧文案) -> "ncm %s · qm %s"`。
     *
     * 与 [sourceSummary] 的关系：那个只收两个**整数**，因此无法表达「未知」。
     * 两源都返回时两条路径必须产出**逐字相同**的文案（`SourceCountsTest` 在 8 种语言上钉住）。
     */
    val searchSourceSummaryWithStatus: (String, String) -> String,

    /** v3.2.0 · P0：取链失败的分类文案（见 [PlaybackFailureStrings]）。 */
    val playbackFailure: PlaybackFailureStrings,

    /**
     * v3.3.0 · 用户需求第 9 条：歌词复制 / 分享 / 生成图片的文案（见 [ShareStrings]）。
     *
     * 与 [PlaybackFailureStrings] 同一范式：**外层只为这个组加 1 个参数**
     * （138 → 139，预算 150），20 条文案全部落在组里。往主构造器直接加这 20 条
     * 会把它推到 158 —— 那就越过预警线了（见本类 KDoc 的 dex 槽算式）。
     */
    val share: ShareStrings,

    /**
     * v3.3.0 · 用户需求第 4 / 8 条：**桌面播放卡片（App Widget）**的文案（见 [WidgetStrings]）。
     *
     * 与 [PlaybackFailureStrings] / [ShareStrings] 同一范式：外层只为这个组加 1 个参数
     * （139 → 140，预算 150），8 条文案全部落在组里。
     *
     * 这一组的 8 条里有 5 条是**无障碍内容描述**（播放/暂停/上一首/下一首/打开应用）——
     * 桌面卡片上的按钮是纯图标，TalkBack 靠 `RemoteViews.setContentDescription` 念出它们，
     * 而在 XML 里写死中文会让另外 7 种语言的用户听到中文（本应用是运行时 i18n，
     * 卡片文案必须与 `LocalStrings` 同源，见 `NcrustWidgetProvider.widgetStrings`）。
     */
    val widget: WidgetStrings,

    /**
     * v3.3.0 · 用户需求第 10 条：**播放统计页**的文案（见 [StatsStrings]）。
     *
     * 与 [PlaybackFailureStrings] / [ShareStrings] / [WidgetStrings] 同一范式：
     * 外层只为这一组加 **1** 个参数（140 → 141，预算 150），
     * 37 条文案全部落在组里。
     *
     * ⚠️ **余量只剩 9 个**（`StringsConstructorBudgetTest` 的预警线附近）。
     * 下一个要加文案的人必须先把既有文案搬进语义相符的组、
     * 并在类体里留转发属性，不要再往主构造器直接加参数。
     */
    val stats: StatsStrings,

    /**
     * v3.4.8：**B 站音质与字幕**的文案（见 [BiliStrings]）。
     *
     * 与 [PlaybackFailureStrings] / [ShareStrings] / [WidgetStrings] / [StatsStrings]
     * 同一范式：外层只为这一组加 **1** 个参数（141 → 142，预算 150），
     * 17 条文案全部落在组里。
     *
     * ## 为什么单开一组而不是塞进 SettingsStrings
     *
     * `SettingsStrings` 已经是 **86**，而组**预警线是 80**、硬上限 120 ——
     * 它是唯一一个已经越过预警线的组。再往里加 17 条 = 103，逼近硬上限；
     * 而且这 17 条里没有一条是「设置页通用文案」：它们全部只服务于 B 站这一个音源，
     * 与 `bilibiliEnabledLabel` 那种「一个开关配一句话」的规模完全不同。
     * 按仓库纪律「再加字段请拆组」，这里单开一组。
     *
     * ⚠️ **余量只剩 8 个**（预算 150）。下一个要加文案的人必须先把既有文案搬进
     * 语义相符的组、并在类体里留转发属性，不要再往主构造器直接加参数。
     */
    val bili: BiliStrings,
    /**
     * v3.4.9：**「QQ 音乐 App 扫码」**登录的专属文案（3 条）。
     *
     * ## 为什么单开一组，而不是塞进已有的 `source` 组
     *
     * `SourceStrings` 已经 **77 个参数**，而本仓库给它定的预警线是 80
     * （`StringsConstructorBudgetTest` 与 `AggregateStringsTest` 各钉了一半）。
     * 加这三条就是 80 —— 正好撞线，两条测试都会红。而它们红得**有道理**：
     * 那条注释写着「真正该拆的信号是『本组越过 80』」，这里就是那个信号。
     *
     * ## 为什么这组不是「为了绕开测试而拆」
     *
     * 这 3 条与 `source` 组**不是同一条业务线**：`source` 组是「音源与账号」
     * （ncm/qm/B 站的登录入口、会员、搜索结果计数），而这三条只服务
     * **一条具体的登录方式**（官方 App 扫码）—— 它自己的状态机、自己的浮层、
     * 自己的「这条路可续期」语义。下一个加「扫描登录」相关文案的人，
     * 在这里加才对。
     *
     * 代价是 `Strings` 主构造器 +1 个槽位（139 → 140，硬上限 255、预算 150）——
     * 那是拆组本来就要付的成本，也是 `StringsConstructorBudgetTest` 记录过的账。
     */
    val qqScan: QqScanStrings,
) {
    // ---------- 转发属性（v2.0.0 · HF1）----------
    // 离线 / 缓存那一组（19 条）的构造参数已经挪进 [OfflineStrings]，这里用**成员**转发属性把
    // 调用点（strings.offlineCacheXxx / strings.cacheSizeLabel / strings.cacheCleared）原样保住。
    // 刻意不用顶层扩展属性：扩展属性在别的包里要逐条 import，而成员属性对 `LocalStrings.current.x`
    // 天然可见。成员属性不进构造函数，所以不会再撑大那个已经贴着 dex 255 上限的参数表。
    // v3.1.0 · B：B 站音源开关的两条文案走**转发属性**（与 HF1 的三组同一手法）：
    // 它们住在 `SettingsStrings`（分组），而 registry 的 titleKey 走的是 `Strings` 上的
    // 可达路径（`SettingsRegistryTest.everyTitleKeyResolvesToARealStringsAccessorPath`）。
    // 成员属性不进构造函数 ⇒ 主构造器预算一个槽都不占。
    val bilibiliEnabledLabel: String get() = settings.bilibiliEnabledLabel
    val bilibiliEnabledDescription: String get() = settings.bilibiliEnabledDescription
    val aggFilterBili: String get() = source.aggFilterBili
    val offlineCacheUsage: (String, String, String) -> String get() = offline.offlineCacheUsage
    val offlineCacheListTitle: (Int) -> String get() = offline.offlineCacheListTitle
    val offlineCachePartial: String get() = offline.offlineCachePartial
    val offlineCacheLimitHint: String get() = offline.offlineCacheLimitHint
    val offlineCachePlayingLocked: String get() = offline.offlineCachePlayingLocked
    val offlineCacheFragmentNotice: String get() = offline.offlineCacheFragmentNotice
    val offlineCacheDeleteConfirm: (String) -> String get() = offline.offlineCacheDeleteConfirm
    val cacheSizeLabel: (Long) -> String get() = offline.cacheSizeLabel
    val offlineCacheManageLabel: String get() = offline.offlineCacheManageLabel
    val offlineCacheTitle: String get() = offline.offlineCacheTitle
    val offlineCacheLimitLabel: String get() = offline.offlineCacheLimitLabel
    val offlineCacheEmpty: String get() = offline.offlineCacheEmpty
    val offlineCacheDeleteTrack: String get() = offline.offlineCacheDeleteTrack
    val offlineCacheDeleteTitle: String get() = offline.offlineCacheDeleteTitle
    val offlineCacheDeleted: String get() = offline.offlineCacheDeleted
    val categoryOffline: String get() = offline.categoryOffline

    // v2.3.0：这两条随字段一起搬进 [offline] 组，调用点（MainScreen / HomeScreen 的离线空态）
    // 读的仍是 `strings.networkOfflineTitle`，一个字都不用改。
    val networkOfflineTitle: String get() = offline.networkOfflineTitle
    val networkOfflineHint: String get() = offline.networkOfflineHint

    // ---------- v2.1.0 · C：多音源那一组的转发属性 ----------
    val sourceQqMusic: String get() = source.sourceQqMusic
    val sourceQqAccount: String get() = source.sourceQqAccount
    val sourceQqLoginAction: String get() = source.sourceQqLoginAction
    val sourceSummary: (Int, Int) -> String get() = source.sourceSummary
    val sourceQrWaiting: String get() = source.sourceQrWaiting
    val sourceQrScanned: String get() = source.sourceQrScanned
    val sourceQrExpired: String get() = source.sourceQrExpired
    val sourceQrFailed: String get() = source.sourceQrFailed
    val sourceQrLoadFailed: String get() = source.sourceQrLoadFailed
    val sourceQrNetworkHint: String get() = source.sourceQrNetworkHint
    val sourceQrServiceUnavailable: String get() = source.sourceQrServiceUnavailable
    val sourceQrSwitchingToWeb: String get() = source.sourceQrSwitchingToWeb
    val sourceQrAvailabilityNote: String get() = source.sourceQrAvailabilityNote
    val sourceQrRefresh: String get() = source.sourceQrRefresh
    val sourceWebLogin: String get() = source.sourceWebLogin

    // v2.1.1：播放页音源角标 + 手机号验证码登录（都在 `source` 分组里，理由见那边的注释）
    val sourceNetease: String get() = source.sourceNetease
    // v3.4.9：扫码（QQ 音乐 App）那三条在 [QqScanStrings] 组里 —— 见那边的 KDoc，
    // 它们与 `source` 组不是同一条业务线（那条是「账号与音源」，这条是「一条登录方式」），
    // 而且 `source` 组已经撞到了它自己的预警线。
    val sourceQqScanLoginAction: String get() = qqScan.qqScanLoginAction
    val sourceQqScanTitle: String get() = qqScan.qqScanTitle
    val sourceQqScanNote: String get() = qqScan.qqScanNote
    val sourceQqPhoneTitle: String get() = source.sourceQqPhoneTitle
    val sourceQqPhoneLabel: String get() = source.sourceQqPhoneLabel
    val sourceQqPhoneHint: String get() = source.sourceQqPhoneHint
    val sourceQqSendCode: String get() = source.sourceQqSendCode
    val sourceQqResendCode: String get() = source.sourceQqResendCode
    val sourceQqCodeLabel: String get() = source.sourceQqCodeLabel
    val sourceQqCodeHint: String get() = source.sourceQqCodeHint
    val sourceQqPhoneSubmit: String get() = source.sourceQqPhoneSubmit
    val sourceQqCodeSent: String get() = source.sourceQqCodeSent
    val sourceQqPhoneBadNumber: String get() = source.sourceQqPhoneBadNumber
    val sourceQqCodeWrong: String get() = source.sourceQqCodeWrong
    val sourceQqPhoneTooFrequent: String get() = source.sourceQqPhoneTooFrequent
    val sourceQqPhoneNeedCaptcha: String get() = source.sourceQqPhoneNeedCaptcha
    val sourceQqPhoneNote: String get() = source.sourceQqPhoneNote
    val sourceQqCaptchaHint: String get() = source.sourceQqCaptchaHint
    val sourceQqCaptchaRetry: String get() = source.sourceQqCaptchaRetry
    val sourceQqCodeSendFailed: String get() = source.sourceQqCodeSendFailed
    val sourceQqLoginFailed: String get() = source.sourceQqLoginFailed
    val sourceQqAccountRestricted: String get() = source.sourceQqAccountRestricted
    val sourceQqDeviceLimit: String get() = source.sourceQqDeviceLimit
    val sourceQqLoginRateLimited: String get() = source.sourceQqLoginRateLimited

    // ---- v3.2.0：B 站扫码登录 + 筛选空态（转发到 source 组，见那边的 KDoc）----
    val sourceBiliAccount: String get() = source.sourceBiliAccount
    val biliLoginTitle: String get() = source.biliLoginTitle
    val biliLoginWaiting: String get() = source.biliLoginWaiting
    val biliLoginScanned: String get() = source.biliLoginScanned
    val biliLoginExpired: String get() = source.biliLoginExpired
    val biliLoginRefresh: String get() = source.biliLoginRefresh
    val biliLoginFailed: String get() = source.biliLoginFailed
    val biliLoginSuccess: (String) -> String get() = source.biliLoginSuccess
    val biliLogout: String get() = source.biliLogout
    val biliLoginRiskNote: String get() = source.biliLoginRiskNote
    val biliQualityNote: String get() = source.biliQualityNote
    val searchFilterEmpty: (String) -> String get() = source.searchFilterEmpty
    val cacheUsageAudio: String get() = offline.cacheUsageAudio
    val cacheUsageImage: String get() = offline.cacheUsageImage
    val cacheUsageOther: String get() = offline.cacheUsageOther
    val cacheCleared: String get() = offline.cacheCleared

    // ---- v2.2.0 · QQ 歌单（全部转发到 playlists 组，见 Strings.playlists 的 KDoc）----
    val qqPlaylistsTitle: String get() = playlists.qqPlaylistsTitle
    val playlistsSectionOwned: String get() = playlists.sectionOwned
    val playlistsSectionFav: String get() = playlists.sectionFav
    val playlistFavorite: String get() = playlists.favorite
    val playlistRefresh: String get() = playlists.refresh
    val playlistEmpty: String get() = playlists.empty
    val playlistEmptyTracks: String get() = playlists.emptyTracks
    val playlistLoginExpired: String get() = playlists.loginExpired
    val playlistRelogin: String get() = playlists.relogin
    val playlistOffline: String get() = playlists.offline
    val playlistTruncated: String get() = playlists.truncated
    val playlistLoadFailed: String get() = playlists.loadFailed
    val playlistRetry: String get() = playlists.retry
    val playlistNotFound: String get() = playlists.notFound
    val playlistsEntryHint: String get() = playlists.entryHint
    val playlistLoginRequired: String get() = playlists.loginRequired
    val playlistTrackCount: (Int) -> String get() = playlists.trackCount

    // ---- v2.3.0 · C/D：曲目标签（版权可用性 + 原唱/翻唱）----
    val tagPlayable: String get() = tags.playable
    val tagMemberOnly: String get() = tags.memberOnly
    val tagNoCopyright: String get() = tags.noCopyright
    val tagOriginal: String get() = tags.original
    val tagCover: String get() = tags.cover
    /** 翻唱行的副标题：「原唱：<艺人> · <曲名>」。 */
    val tagCoverOrigin: (String, String) -> String get() = tags.coverOrigin
    /** 播放失败且另一音源有候选时的提示。 */
    val tagSwitchSourceHint: String get() = tags.switchSourceHint

    // ---- v2.3.0 · B：本地歌单 ----
    val localPlaylistSectionTitle: String get() = localPlaylist.sectionTitle
    val localPlaylistNew: String get() = localPlaylist.newPlaylist
    val localPlaylistEmpty: String get() = localPlaylist.empty
    val localPlaylistEmptyTracks: String get() = localPlaylist.emptyTracks
    val localPlaylistNameHint: String get() = localPlaylist.nameHint
    val localPlaylistCreate: String get() = localPlaylist.create
    val localPlaylistCreated: (String) -> String get() = localPlaylist.created
    val localPlaylistAddTrack: String get() = localPlaylist.addTrack
    val localPlaylistAdded: String get() = localPlaylist.added
    val localPlaylistRemoveTrack: String get() = localPlaylist.removeTrack
    val localPlaylistRemoved: String get() = localPlaylist.removed
    val localPlaylistClear: String get() = localPlaylist.clear
    val localPlaylistClearConfirm: (String) -> String get() = localPlaylist.clearConfirm
    val localPlaylistCleared: String get() = localPlaylist.cleared
    val localPlaylistSync: String get() = localPlaylist.sync
    val localPlaylistSyncing: String get() = localPlaylist.syncing
    val localPlaylistSynced: (Int, Int) -> String get() = localPlaylist.synced
    val localPlaylistSyncFailed: String get() = localPlaylist.syncFailed
    val localPlaylistSyncNoSource: String get() = localPlaylist.syncNoSource
    val localPlaylistLocalBadge: String get() = localPlaylist.localBadge
    val localPlaylistDelete: String get() = localPlaylist.delete
    val localPlaylistDeleteConfirm: (String) -> String get() = localPlaylist.deleteConfirm
    val localPlaylistDeleted: String get() = localPlaylist.deleted
    val localPlaylistChoose: String get() = localPlaylist.choose
    val localPlaylistAdopt: String get() = localPlaylist.adopt
    val localPlaylistAdopted: (String) -> String get() = localPlaylist.adopted

    // ---------- v2.5.0 · D：队列动作那一组的转发属性 ----------
    // 前两条随字段一起搬进 [queue] 组（腾出构造参数位置，见 [Strings.queue] 的 KDoc），
    // 调用点（各 Screen 的歌曲长按菜单）读的仍是 `strings.actionInsertNext` / `strings.actionAppendToQueue`，
    // 一个字都不用改 —— 与 v2.3.0 的 [networkOfflineTitle] 同一套做法。
    // 第三条是本版新文案；它同样走转发属性，是为了让「插播 / 添加到下一首播放 / 最后播放」
    // 这三个**同一个菜单里**的动作在调用点长得一样，不会有人漏掉 queue. 前缀而写错分组。
    val actionInsertNext: String get() = queue.actionInsertNext
    val actionAppendToQueue: String get() = queue.actionAppendToQueue
    val actionAddToNext: String get() = queue.actionAddToNext

    // ---------- 转发属性（v2.5.3 · P0）：设置页 → [SettingsStrings] ----------
    // 与 v2.0.0 · HF1 的 [OfflineStrings] 同一套做法：搬家不改调用点。
    val qualitySectionTitle: String get() = settings.qualitySectionTitle
    val wifiQualityLabel: String get() = settings.wifiQualityLabel
    val mobileQualityLabel: String get() = settings.mobileQualityLabel
    val qualityOptions: List<String> get() = settings.qualityOptions
    val qualityFlacUnsupportedHint: String get() = settings.qualityFlacUnsupportedHint
    val accentSourceSectionTitle: String get() = settings.accentSourceSectionTitle
    val accentSourcePreset: String get() = settings.accentSourcePreset
    val accentSourceCover: String get() = settings.accentSourceCover
    val accentSourceSystem: String get() = settings.accentSourceSystem
    val accentSourceSystemHint: String get() = settings.accentSourceSystemHint
    val accentSystemRefresh: String get() = settings.accentSystemRefresh
    val playbackSectionTitle: String get() = settings.playbackSectionTitle
    val gaplessSectionTitle: String get() = settings.gaplessSectionTitle
    val gaplessDescription: String get() = settings.gaplessDescription
    val lyricsTranslationLabel: String get() = settings.lyricsTranslationLabel
    val lyricsTranslationHint: String get() = settings.lyricsTranslationHint
    val lyricsWordByWordLabel: String get() = settings.lyricsWordByWordLabel
    val lyricsWordAnimationLabel: String get() = settings.lyricsWordAnimationLabel
    val lyricsWordAnimationOptions: List<String> get() = settings.lyricsWordAnimationOptions
    val lyricsInMediaSessionLabel: String get() = settings.lyricsInMediaSessionLabel
    val lyricsInMediaSessionHint: String get() = settings.lyricsInMediaSessionHint
    val lyricsSweepQualityLabel: String get() = settings.lyricsSweepQualityLabel
    val lyricsSweepQualityOptions: List<String> get() = settings.lyricsSweepQualityOptions
    val lyricsTtmlEnabledLabel: String get() = settings.lyricsTtmlEnabledLabel
    val lyricsTtmlFirstLabel: String get() = settings.lyricsTtmlFirstLabel
    val lyricsRomanizationLabel: String get() = settings.lyricsRomanizationLabel
    val lyricsRomanizationHint: String get() = settings.lyricsRomanizationHint
    val keepScreenOnLabel: String get() = settings.keepScreenOnLabel
    val keepScreenOnHint: String get() = settings.keepScreenOnHint
    val dynamicFontLabel: String get() = settings.dynamicFontLabel
    val dynamicFontHint: String get() = settings.dynamicFontHint
    val lyricsFontScaleLabel: String get() = settings.lyricsFontScaleLabel
    val autoRotateLabel: String get() = settings.autoRotateLabel
    val autoRotateDescription: String get() = settings.autoRotateDescription
    val audioVisualizerLabel: String get() = settings.audioVisualizerLabel
    val audioVisualizerDescription: String get() = settings.audioVisualizerDescription
    val themeSectionTitle: String get() = settings.themeSectionTitle
    val themeModeSectionTitle: String get() = settings.themeModeSectionTitle
    val themeModeSystem: String get() = settings.themeModeSystem
    val themeModeDark: String get() = settings.themeModeDark
    val themeModeLight: String get() = settings.themeModeLight
    val themeColorNames: List<String> get() = settings.themeColorNames
    val languageSectionTitle: String get() = settings.languageSectionTitle
    val aboutButton: String get() = settings.aboutButton
    val storageSectionTitle: String get() = settings.storageSectionTitle
    val clearCache: String get() = settings.clearCache
    val clearCacheConfirm: String get() = settings.clearCacheConfirm
    // v3.3.0：清除缓存的两种后果要写清 —— 旧确认框只写「确定清除全部缓存？」，
    // 而它会永久删掉离线音频（用户点一下几百 MB 就没了，事后无从得知）。
    val clearCacheAudioNote: String get() = settings.clearCacheAudioNote
    val clearCacheEverything: String get() = settings.clearCacheEverything
    val bgSectionTitle: String get() = settings.bgSectionTitle
    val bgPick: String get() = settings.bgPick
    val bgChange: String get() = settings.bgChange
    val bgRemove: String get() = settings.bgRemove
    val bgImportFailed: String get() = settings.bgImportFailed
    val accountDialogTitle: String get() = settings.accountDialogTitle
    val nicknameLabel: (String) -> String get() = settings.nicknameLabel
    val uidLabel: (String) -> String get() = settings.uidLabel
    val logoutButton: String get() = settings.logoutButton
    val notLoggedIn: String get() = settings.notLoggedIn
    val loginHint: String get() = settings.loginHint
    val scanEntryTitle: String get() = settings.scanEntryTitle
    val userAvatarDesc: String get() = settings.userAvatarDesc
    val userIconDesc: String get() = settings.userIconDesc
    val batteryTitle: String get() = settings.batteryTitle
    val batteryMessage: String get() = settings.batteryMessage
    val batteryAllow: String get() = settings.batteryAllow
    val batteryLater: String get() = settings.batteryLater
    // v3.3.0：后台运行行的两态回显（用户反馈第 5 条）。走 settings 分组，
    // 不进外层主构造器（预算纪律）。
    val batteryStatusAllowed: String get() = settings.batteryStatusAllowed
    val batteryStatusDenied: String get() = settings.batteryStatusDenied
    val batteryAlreadyAllowed: String get() = settings.batteryAlreadyAllowed
    val batteryJumpFailed: String get() = settings.batteryJumpFailed

    // ---------- 转发属性（v2.8.0）：二级菜单分组文案 → [SettingsStrings] ----------
    // 这 14 条是**新文案**（没有老调用点要保住），补转发属性是为了让
    // `SettingsRegistry` 里 `SettingsGroup.titleKey` / `subtitleKey` 存的**裸路径**
    // （`"settingsGroupAccountTitle"`）与组路径（`"settings.settingsGroupAccountTitle"`）
    // 都能解析 —— 该文件只存字符串、不做编译期校验，少一条就会渲染成空白卡片。
    val settingsGroupAccountTitle: String get() = settings.settingsGroupAccountTitle
    val settingsGroupAccountSubtitle: String get() = settings.settingsGroupAccountSubtitle
    val settingsGroupGeneralTitle: String get() = settings.settingsGroupGeneralTitle
    val settingsGroupGeneralSubtitle: String get() = settings.settingsGroupGeneralSubtitle
    val settingsGroupAppearanceTitle: String get() = settings.settingsGroupAppearanceTitle
    val settingsGroupAppearanceSubtitle: String get() = settings.settingsGroupAppearanceSubtitle
    val settingsGroupPlaybackTitle: String get() = settings.settingsGroupPlaybackTitle
    val settingsGroupPlaybackSubtitle: String get() = settings.settingsGroupPlaybackSubtitle
    val settingsGroupLyricsTitle: String get() = settings.settingsGroupLyricsTitle
    val settingsGroupLyricsSubtitle: String get() = settings.settingsGroupLyricsSubtitle
    val settingsGroupStorageTitle: String get() = settings.settingsGroupStorageTitle
    val settingsGroupStorageSubtitle: String get() = settings.settingsGroupStorageSubtitle
    val settingsGroupAboutTitle: String get() = settings.settingsGroupAboutTitle
    val settingsGroupAboutSubtitle: String get() = settings.settingsGroupAboutSubtitle

    // ---------- 转发属性（v2.8.0）：波形效果分级 → [WaveformStrings] ----------
    // 属性名逐字等于 `ui/player/waveform/VisualizerStrings.kt` 的 `Property.*` 常量
    // （那是全仓库唯一的字面量落点，设置页消费方按这些名字取文案）。
    val visualizerTierLabel: String get() = waveform.visualizerTierLabel
    val visualizerTierDescription: String get() = waveform.visualizerTierDescription
    val visualizerTierSimple: String get() = waveform.visualizerTierSimple
    val visualizerTierRefined: String get() = waveform.visualizerTierRefined
    val visualizerTierShowcase: String get() = waveform.visualizerTierShowcase
    val visualizerShowcaseLabel: String get() = waveform.visualizerShowcaseLabel
    val visualizerShowcaseDescription: String get() = waveform.visualizerShowcaseDescription
    val visualizerShockwaveLabel: String get() = waveform.visualizerShockwaveLabel
    val visualizerShockwaveDescription: String get() = waveform.visualizerShockwaveDescription
    val visualizerParticlesLabel: String get() = waveform.visualizerParticlesLabel
    val visualizerParticlesDescription: String get() = waveform.visualizerParticlesDescription
    val visualizerPerspectiveLabel: String get() = waveform.visualizerPerspectiveLabel
    val visualizerPerspectiveDescription: String get() = waveform.visualizerPerspectiveDescription
    val visualizerDragLabel: String get() = waveform.visualizerDragLabel
    val visualizerDragDescription: String get() = waveform.visualizerDragDescription
    val visualizerNotSpectrumHint: String get() = waveform.visualizerNotSpectrumHint

    // ---------- 转发属性（v2.9.0 / v3.0.0）：统一「动效强度」与独立开关 ----------
    // 与上面同一套做法：搬家不改调用点。**计算属性不占构造参数**，
    // 所以这里加多少条都不会动 `StringsConstructorBudgetTest` 钉住的那个数。
    val motionIntensityLabel: String get() = waveform.motionIntensityLabel
    val motionIntensityDescription: String get() = waveform.motionIntensityDescription
    val uiMotionLabel: String get() = waveform.uiMotionLabel
    val uiMotionDescription: String get() = waveform.uiMotionDescription
    // v3.0.0：五个「每个动效独立开关」的标题与说明（铁律 26）。
    val motionShockwaveLabel: String get() = waveform.motionShockwaveLabel
    val motionShockwaveDescription: String get() = waveform.motionShockwaveDescription
    val motionHaloLabel: String get() = waveform.motionHaloLabel
    val motionHaloDescription: String get() = waveform.motionHaloDescription
    val motionParticlesLabel: String get() = waveform.motionParticlesLabel
    val motionParticlesDescription: String get() = waveform.motionParticlesDescription
    val motionWaveBandsLabel: String get() = waveform.motionWaveBandsLabel
    val motionWaveBandsDescription: String get() = waveform.motionWaveBandsDescription
    val motionBreathingLabel: String get() = waveform.motionBreathingLabel
    val motionBreathingDescription: String get() = waveform.motionBreathingDescription
    val motionRhythmLabel: String get() = waveform.motionRhythmLabel
    val motionRhythmDescription: String get() = waveform.motionRhythmDescription
    val motionCoverFloatLabel: String get() = waveform.motionCoverFloatLabel
    val motionCoverFloatDescription: String get() = waveform.motionCoverFloatDescription
    val motionLyricPulseLabel: String get() = waveform.motionLyricPulseLabel
    val motionLyricPulseDescription: String get() = waveform.motionLyricPulseDescription
    val motionBarPulseLabel: String get() = waveform.motionBarPulseLabel
    val motionBarPulseDescription: String get() = waveform.motionBarPulseDescription

    // ---------- 转发属性（v2.5.3 · P0）：关于页 → [AboutStrings] ----------
    // 与 v2.0.0 · HF1 的 [OfflineStrings] 同一套做法：搬家不改调用点。
    val aboutTitle: String get() = about.aboutTitle
    val aboutAppSubtitle: String get() = about.aboutAppSubtitle
    val aboutSectionProject: String get() = about.aboutSectionProject
    val aboutVersion: String get() = about.aboutVersion
    val aboutDeveloperOriginal: String get() = about.aboutDeveloperOriginal
    val aboutDeveloperFork: String get() = about.aboutDeveloperFork
    val aboutLicense: String get() = about.aboutLicense
    val aboutLicenseGplWithMit: String get() = about.aboutLicenseGplWithMit
    val aboutRepositoryFork: String get() = about.aboutRepositoryFork
    val aboutRepositoryOriginal: String get() = about.aboutRepositoryOriginal
    val aboutSectionTechStack: String get() = about.aboutSectionTechStack
    val aboutLangLabel: String get() = about.aboutLangLabel
    val aboutUIFrameworkLabel: String get() = about.aboutUIFrameworkLabel
    val aboutDesignSystemLabel: String get() = about.aboutDesignSystemLabel
    val aboutAudioEngineLabel: String get() = about.aboutAudioEngineLabel
    val aboutNetworkLabel: String get() = about.aboutNetworkLabel
    val aboutImageLabel: String get() = about.aboutImageLabel
    val aboutSectionTeam: String get() = about.aboutSectionTeam
    val aboutRoleDev: String get() = about.aboutRoleDev
    val aboutRoleTester: String get() = about.aboutRoleTester
    val aboutRoleForkMaintainer: String get() = about.aboutRoleForkMaintainer
    val aboutSectionCredits: String get() = about.aboutSectionCredits
    val aboutCreditCli: String get() = about.aboutCreditCli
    val aboutCreditAnim: String get() = about.aboutCreditAnim
    val aboutCreditDesign: String get() = about.aboutCreditDesign

    // ---------- 转发属性（v2.5.3 · P0）：播放器界面 → [PlayerUiStrings] ----------
    // 与 v2.0.0 · HF1 的 [OfflineStrings] 同一套做法：搬家不改调用点。
    val prevButton: String get() = playerUi.prevButton
    val playButton: String get() = playerUi.playButton
    val pauseButton: String get() = playerUi.pauseButton
    val nextButton: String get() = playerUi.nextButton
    val lyricsButton: String get() = playerUi.lyricsButton
    val queueButton: String get() = playerUi.queueButton
    val addToLibraryButton: String get() = playerUi.addToLibraryButton
    val qualityDowngradedBadge: String get() = playerUi.qualityDowngradedBadge
    val qualityNoEntitlementBadge: String get() = playerUi.qualityNoEntitlementBadge
    val qualitySongLacksTierBadge: String get() = playerUi.qualitySongLacksTierBadge
    val lyricsFontSmaller: String get() = playerUi.lyricsFontSmaller
    val lyricsFontLarger: String get() = playerUi.lyricsFontLarger
    val controlsHandleLabel: String get() = playerUi.controlsHandleLabel
    val bigScreenEnter: String get() = playerUi.bigScreenEnter
    val bigScreenExit: String get() = playerUi.bigScreenExit
    val autoRotateOn: String get() = playerUi.autoRotateOn
    val autoRotateOff: String get() = playerUi.autoRotateOff
    val noLyrics: String get() = playerUi.noLyrics
    val emptyQueue: String get() = playerUi.emptyQueue
    val collapsePlayer: String get() = playerUi.collapsePlayer
    val lyricsLabel: String get() = playerUi.lyricsLabel
    val queueTitle: String get() = playerUi.queueTitle
    val playModeButton: String get() = playerUi.playModeButton
    val saveAsPlaylist: String get() = playerUi.saveAsPlaylist
    val noSongPlaying: String get() = playerUi.noSongPlaying
    val queueSectionPast: String get() = playerUi.queueSectionPast
    val queueSectionNow: String get() = playerUi.queueSectionNow
    val queueSectionUpcoming: String get() = playerUi.queueSectionUpcoming
    val queueInfinityPlaceholder: String get() = playerUi.queueInfinityPlaceholder
    val queueClearAll: String get() = playerUi.queueClearAll
    val clearQueue: String get() = playerUi.clearQueue

}

/** 字节数格式化为人类可读的 B/KB/MB/GB，供 cacheSizeLabel 复用。 */
fun formatCacheBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024.0) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}

/**
 * v2.0.0 · HF1：离线 / 缓存文案组。存在的唯一理由是 dex 单方法 255 参数寄存器上限，
 * 详见 [Strings.offline] 的 KDoc —— 语义上它们本来就是同一个功能面（离线缓存 + 存储占用）。
 *
 * 文案契约（v2.0.0 · T3，合规相关）：一律用「已缓存 / 缓存」，**不承诺「整曲完整」** ——
 * media3 SimpleCache 只保证「播放过的片段在本地」，不是「下载了一整首」。
 */
data class OfflineStrings(
    /** (已用, 上限, 剩余)，三个都已由 formatCacheBytes 格式化。 */
    val offlineCacheUsage: (String, String, String) -> String,
    /** 已缓存曲目列表标题。 */
    val offlineCacheListTitle: (Int) -> String,
    /** 量不到该曲占用时的占位文案（「已缓存片段」）。 */
    val offlineCachePartial: String,
    /** 上限「下次启动生效」的如实说明（淘汰器在启动时固化）。 */
    val offlineCacheLimitHint: String,
    /** 当前播放曲目禁用删除时的说明。 */
    val offlineCachePlayingLocked: String,
    /** 「不保证整曲完整」的常驻提示。 */
    val offlineCacheFragmentNotice: String,
    val offlineCacheDeleteConfirm: (String) -> String,
    val cacheSizeLabel: (Long) -> String,
    val offlineCacheManageLabel: String,
    val offlineCacheTitle: String,
    val offlineCacheLimitLabel: String,
    val offlineCacheEmpty: String,
    val offlineCacheDeleteTrack: String,
    val offlineCacheDeleteTitle: String,
    val offlineCacheDeleted: String,
    /**
     * v3.3.0 · 用户建议：库页的「离线」tab 名。
     *
     * 之前离线歌只能在「设置 → 存储与缓存 → 离线缓存管理」里看到，而那里是
     * **管理**入口（看占用、删曲目）。用户的原话是「在库里单独做一个界面，
     * 可以直接点击播放」—— 这条把它提到库页，与单曲/歌单/专辑并列。
     * 按纪律进 [OfflineStrings] 组，不进外层主构造器。
     */
    val categoryOffline: String,
    /**
     * v1.5.1 · C：无网络时的首页降级空态标题。
     *
     * v2.3.0 从 [Strings] 的构造参数搬到这里 —— 语义上它本来就是「离线」那一组的
     * （有缓存时直接显示缓存，只有一条都没有时才轮到它），搬过来同时腾出了 dex 槽位。
     */
    val networkOfflineTitle: String,
    /** [networkOfflineTitle] 的补充提示。 */
    val networkOfflineHint: String,
    val cacheUsageAudio: String,
    val cacheUsageImage: String,
    val cacheUsageOther: String,
    val cacheCleared: String,
)

/**
 * v2.1.0 · C：多音源相关的文案。
 *
 * 只有品牌名与账号区块标题：其余交互（登录 / 登出 / 未登录）直接复用既有的
 * `loginHint` / `logoutButton` / `notLoggedIn`，不重复造一遍同义文案 ——
 * 8 种语言各多一条同义句，维护成本是实打实的。
 */
data class SourceStrings(
    /** 品牌名。**各语言保持一致**（专有名词不翻译）。 */
    val sourceQqMusic: String,
    /** 账号区块标题，例如「qm 账号」。 */
    val sourceQqAccount: String,
    /** 登录动作，例如「登录 qm」。 */
    val sourceQqLoginAction: String,
    /**
     * 搜索结果顶部那行小字：`(ncm条数, qm条数) -> 文案`。
     *
     * 为什么需要它：`SongCard` 只在**非 ncm**的行上标音源，于是纯 ncm 的结果
     * 在界面上看不出「来自哪里」——真机反馈正是「现在有了稻香，似乎是 ncm 的搜索结果？」。
     */
    val sourceSummary: (Int, Int) -> String,
    /** 二维码等待扫码。 */
    val sourceQrWaiting: String,
    /** 已扫码、等手机端确认。 */
    val sourceQrScanned: String,
    /** 二维码已过期。 */
    val sourceQrExpired: String,
    /** 扫码登录失败。 */
    val sourceQrFailed: String,
    /** 二维码获取失败。 */
    val sourceQrLoadFailed: String,
    /** 轮询拿不到响应时的提示（网络/风控）。 */
    val sourceQrNetworkHint: String,
    /** v2.1.1：服务端明确回绝（403/空 body）时的提示 —— 与「网络不稳定」是两回事。 */
    val sourceQrServiceUnavailable: String,
    /** v2.1.1：连续被拒、准备自动切到网页登录时的提示。 */
    val sourceQrSwitchingToWeb: String,
    /** v2.1.1：设置页 QQ 账号卡片上的可用性说明。 */
    val sourceQrAvailabilityNote: String,
    /** 刷新二维码。 */
    val sourceQrRefresh: String,
    /** 改用网页登录。 */
    val sourceWebLogin: String,

    // ---------- v2.1.1：播放页音源角标 + qm 手机号验证码登录 ----------
    //
    // ⚠️ 为什么这些**必须**放在本分组里，而不是加到外层 `Strings` 的构造参数上：
    // 外层构造函数当时有 **244 个参数 = 245 个 dex 寄存器**（含 this），上限是 255。
    // （v2.5.3 拆组后外层是 128 个参数 = 134 个寄存器 —— 本组自身仍未拆分。）
    // v2.0.0 · HF1 就是因为往它上面直接加字段而崩的（见外层那段注释）。
    // 本分组只有 15 个参数，随便加；外层一个都不加 —— 于是本版对那个上限的占用是 0。

    /** ncm（音源名，与 [sourceQqMusic] 对称）。只用在播放页的音源角标上。 */
    val sourceNetease: String,
    /** 手机号登录浮层标题。 */
    val sourceQqPhoneTitle: String,
    /** 手机号输入框标签。 */
    val sourceQqPhoneLabel: String,
    /** 手机号输入框占位。 */
    val sourceQqPhoneHint: String,
    /** 发送验证码按钮。 */
    val sourceQqSendCode: String,
    /** 重新发送验证码按钮（倒计时结束后）。 */
    val sourceQqResendCode: String,
    /** 验证码输入框标签。 */
    val sourceQqCodeLabel: String,
    /** 验证码输入框占位。 */
    val sourceQqCodeHint: String,
    /** 提交登录按钮。 */
    val sourceQqPhoneSubmit: String,
    /** 验证码已发送。 */
    val sourceQqCodeSent: String,
    /** 手机号格式不对（本地校验就拦下，不发请求）。 */
    val sourceQqPhoneBadNumber: String,
    /** 验证码不对或已过期。 */
    val sourceQqCodeWrong: String,
    /** 发得太频繁。 */
    val sourceQqPhoneTooFrequent: String,
    /** 服务端要图形验证码 —— 手机号这条路走不通，引导去网页登录。 */
    val sourceQqPhoneNeedCaptcha: String,
    /** 合规说明：验证码由腾讯下发、本应用不读短信也不存手机号。 */
    val sourceQqPhoneNote: String,
    /** v2.1.1：风控要求图形验证码时，验证页顶部的说明。 */
    val sourceQqCaptchaHint: String,
    /** v2.1.1：验证页的手动兜底按钮（cookie 变化检测没触发时用）。 */
    val sourceQqCaptchaRetry: String,

    // ---------- v2.1.3：手机号登录自己的失败文案 ----------
    //
    // ⚠️ 这一组是**用户报出来的 bug 的直接修复**：v2.1.2 的手机号登录在失败分支里
    // 复用了 `sourceQrFailed`（「扫码登录失败」），于是短信登录失败时界面显示
    // 「扫码登录失败」—— 用户原话「我短信验证码登陆为什么会显示扫码登录失败」。
    // 那句话在任何情况下都是错的：短信登录与扫码登录是两条完全不同的链路。
    // **教训：跨功能的文案不要复用**，哪怕字面上只是「登录失败」四个字。

    /** 验证码发送失败（认不出的服务端码）。 */
    val sourceQqCodeSendFailed: String,
    /** 登录失败（认不出的服务端码）。 */
    val sourceQqLoginFailed: String,
    /** 账号受限/封禁（20277/20278/20450）。 */
    val sourceQqAccountRestricted: String,
    /** 登录设备数超限（20279）。 */
    val sourceQqDeviceLimit: String,
    /** 登录过于频繁（104604）。 */
    val sourceQqLoginRateLimited: String,

    // ---------------------------------------------------------------- v2.4.0 · 双源聚合 ----
    // 这七条是「跨源聚合」的口径文案。放在 SourceStrings 而不是 Strings 主构造器里，
    // 原因见 `StringsConstructorBudgetTest`：主构造器的 245 个参数槽**已经用满**，
    // 再加一个就会在真机上类加载期抛 ClassFormatError。

    /** 「双源聚合」口径。 */
    // ---------- v3.2.0：B 站扫码登录（P1）----------
    // 归属 `source` 组而不是新开组：这一组本来就是「音源与账号」的文案，
    // 而 B 站登录的每一条都要拼音源名/账号名（`sourceBiliAccount` 与
    // `sourceQqAccount` 是同一个语义位置）。主构造器槽位已满，新组会再吃一个参数。
    /** 账号块的标题（与 [sourceQqAccount] 对称）。 */
    val sourceBiliAccount: String,
    val biliLoginTitle: String,
    val biliLoginWaiting: String,
    val biliLoginScanned: String,
    val biliLoginExpired: String,
    val biliLoginRefresh: String,
    val biliLoginFailed: String,
    /** 已登录时的昵称文案：`(昵称) -> "已登录：xxx"`。 */
    val biliLoginSuccess: (String) -> String,
    val biliLogout: String,
    val biliLoginRiskNote: String,
    val biliQualityNote: String,
    /** v3.2.0 · P0-D：`(筛选档名) -> "「只看 B 站」下没有结果"`。 */
    val searchFilterEmpty: (String) -> String,
    val aggFilterBoth: String,
    /**
     * v3.4.0 · 专辑页音源筛选的「全部」档。
     *
     * 与搜索页的 [aggFilterBoth]（「双源」）**不是同一句话**：搜索页那一档的语义是
     * 「两个源的结果混在一起看」，而专辑页这一档是「不过滤」。共用一个词会让
     * 专辑页出现一个语义不对的标签（那里并没有「双源合并」这件事）。
     *
     * 新调用点直接用 `strings.source.albumSourceFilterAll`，**不要**在主构造器或类体里
     * 加转发属性 —— 那会吃主构造器的槽位预算（[SourceStrings] 已 77/80）。
     */
    val albumSourceFilterAll: String,
    /**
     * v3.4.0 · 专辑页筛空文案，参数是**档位名**（如「只看 qm」）。
     *
     * 与 [searchFilterEmpty] 分开的理由：两者说的是不同的事 —— 搜索页是「没搜到结果」，
     * 专辑页是「这个来源下没有收藏的专辑」。后者要能指出**是哪个档位**空的，
     * 否则用户看到空列表会以为收藏丢了。
     */
    val albumFilterEmpty: (String) -> String,
    /**
     * v3.4.0 · 专辑详情页的音源行（`音源: ncm`）。
     *
     * 参数是音源显示名（来自 `SongTags.sourceLabel`），不是模板里写死某个源 ——
     * 这样将来接第三个源时不用改文案。
     */
    val albumSourceInfo: (String) -> String,
    /** 「只看 ncm」口径。 */
    val aggFilterNetease: String,
    /** 「只看 qm」口径。 */
    val aggFilterQq: String,
    /** v3.1.0： 「只看 B 站」口径。B 站音源关闭时这一档不出现在 UI 上（见 `SourceFilter`）。 */
    val aggFilterBili: String,
    /** 默认播放源的一行说明，(音源名) -> 文案。 */
    val aggPreferredSource: (String) -> String,
    /** 「为什么默认这一源」的补充说明（来自探测结果，不编理由），(说明) -> 文案。 */
    val aggAvailabilityNote: (String) -> String,
    /** 匹配置信度文案，(等级) -> 文案。等级由 MatchConfidence 决定，不是自由文本。 */
    val aggConfidence: (String) -> String,
    /** 可追溯：匹配依据，(依据) -> 文案。 */
    val aggMatchReason: (String) -> String,
    /** 另一源没有可校验的对应条目。 */
    val aggUnmatched: String,
    /** 正在探测版权可用性。 */
    val aggProbing: String,
    /** 单曲页的两源版本区标题。 */
    val aggVersionsTitle: String,
    /** 单曲信息页标题。 */
    val aggSongDetailTitle: String,
    /** 歌曲长按菜单里的「单曲信息」入口。 */
    val aggSongDetailAction: String,
    /** 已默认选中有版权的音源。 */
    val aggDefaultPlayable: String,
    /** 两源都未能确证可播放（如实说明，不是「无版权」）。 */
    val aggNoPlayable: String,
    /** 只在某一源有，(音源名) -> 文案。 */
    val aggOnlyOn: (String) -> String,
    /** 匹配等级名：完全一致。 */
    val aggConfidenceExact: String,
    /** 匹配等级名：高度一致。 */
    val aggConfidenceHigh: String,
    /** 匹配等级名：可能一致。 */
    val aggConfidenceMedium: String,
    /** 匹配等级名：仅同名（不合并）。 */
    val aggConfidenceLow: String,
    /** 匹配等级名：未匹配。 */
    val aggConfidenceNone: String,

    /**
     * v2.6.1 · P0：歌曲找不到可信的艺人身份时，提示已改为搜索（见 `ArtistNavigator`）。
     *
     * 为什么必须有这句提示：旧行为是**静默失败**（点了没反应，PCL110 真机复现），
     * 用户无法区分「应用坏了」与「这首曲子没有可用的艺人信息」。降级本身是正确处置，
     * 但降级必须被说出来 —— 这正是 AGENTS.md 铁律 21
     * 「找不到用户能理解，跳错会让用户以为数据错乱」的落点。
     */
    val artistNavSearchFallback: String,

    /**
     * v2.6.2 · P0：歌曲找不到可信的**专辑**身份时，提示已改为搜索（见 `AlbumNavigator`）。
     *
     * 与 [artistNavSearchFallback] 同一条理由，但这一版更必要：专辑那条路径在修复前
     * **连一次网络请求都不发**（`albumId == null` 直接 no-op），所以不仅界面上没反应，
     * **logcat 里也一条不留** —— 用户与开发者都拿不到任何线索。
     *
     * 文案上**不写"跳错了"**：这条提示只在"我们没有可靠身份"时出现，
     * 正确结果是"用户自己搜到那张专辑"。说清楚"已为你搜索"就够。
     */
    val albumNavSearchFallback: String,

    // ---------------------------------------------------------------- v3.1.0 · B 站音源 ----
    // 这两条进 [SourceStrings] 而不是外层主构造器：本组 59 个参数（预警线 80），
    // 外层已经到 134（预算 150）—— 按 v2.2.1 规则 5「新增文案必须往分组里放」。

    /**
     * B 站（音源名）。**各语言保持同一串**：它是专有名词，与 [sourceQqMusic] 同一条纪律。
     *
     * 用「B站」而不是「哔哩哔哩」：搜索统计行是 `ncm 30 首 · qm 0 首 · B站 5 首`，
     * 四字品牌名会把那一行撑到折行（手机上 360dp 宽只放得下约 22 个全角字符）。
     */
    val sourceBilibili: String,

    /**
     * 统计行的**第三段**：`(前两源那半句, B 站那半句) -> 整行`。
     *
     * ⚠️ 为什么是「拼在已有那行后面」而不是一条全新的三源模板：
     * B 站音源是**默认关闭**的，未启用它的用户（绝大多数）看到的统计行必须与
     * v3.0.0 **逐字相同**。做成「两源模板 + 可选第三段」之后，
     * [com.takahashirinta.ncrust.ui.components.SourceCounts.summary] 在
     * `biliStatus == SKIPPED` 时直接短路返回两源那行，连一次多余拼接都不做。
     */
    val sourceSummaryBili: (String, String) -> String,
)

/**
 * v2.2.0 · qm 用户歌单文案组。
 *
 * 单独成组的原因见 [Strings.playlists] 的 KDoc（dex 255 参数寄存器上限）。
 * 文案契约：
 * - 「离线」必须说清是**本地缓存**，不承诺「数据是最新的」；
 * - 「登录已过期」必须给**重新登录**出口，不能只显示一句话；
 * - 收藏/自建要分开说，且**不出现「合并」字样** —— 本版不做跨源合并。
 */
data class PlaylistsStrings(
    /** 页面标题。 */
    val qqPlaylistsTitle: String,
    /** 「自建歌单」分组标题。 */
    val sectionOwned: String,
    /** 「收藏歌单」分组标题。 */
    val sectionFav: String,
    /** 「我喜欢」这个特殊歌单的标记。 */
    val favorite: String,
    /** 手动刷新按钮。 */
    val refresh: String,
    /** 一个歌单都没有。 */
    val empty: String,
    /** 歌单里一首歌都没有。 */
    val emptyTracks: String,
    /** 登录态已过期。 */
    val loginExpired: String,
    /** 重新登录按钮。 */
    val relogin: String,
    /** 离线提示（显示的是本地缓存）。 */
    val offline: String,
    /** 歌单过大被截断的提示。 */
    val truncated: String,
    /** 加载失败。 */
    val loadFailed: String,
    /** 重试按钮。 */
    val retry: String,
    /** 歌单不存在或不属于当前账号。 */
    val notFound: String,
    /** 库页入口的副标题。 */
    val entryHint: String,
    /** 未登录时的空状态。 */
    val loginRequired: String,
    /** 「N 首」。 */
    val trackCount: (Int) -> String,
    // ---- v2.6.0 · P1/P2：库页歌单 tab 的布局切换与手动折叠 ----
    /** 布局切换：卡片式（大封面 2 列网格）。 */
    val layoutCard: String,
    /** 布局切换：列表式（小封面整行）。 */
    val layoutList: String,
    /** 区块展开后，标题右侧的「收起」动作。 */
    val sectionCollapse: String,
    /**
     * 区块收起后的整体提示（参数 = 该区块里的条目数）。
     *
     * 收起时**显示数量**而不是只显示「已收起」：用户要能判断
     * 「值不值得展开」，而一个不带数字的「已收起」回答不了这个问题。
     */
    val sectionExpandAll: (Int) -> String,
)

/**
 * v2.3.0 · C/D：曲目标签文案组。
 *
 * ## 为什么「可播放」这三个字必须谨慎
 *
 * v2.3.0 的探针（`docs/verification/v2.3.0/probe-copyright.md`）证明 ncm 的
 * `privilege.pl > 0` ⇒ 30/30 能取到链、假阳性 0，所以 [playable] 是一个**能被实测支撑**的断言。
 * 但它的语义是「**当前账号**可播放」，不是「有版权」——文案刻意写「可播放」而不是「正版」。
 *
 * [memberOnly] / [noCopyright] 同理：只在服务端给了判据时显示，
 * 判不出来时整组文案**一个都不出现**（`TrackAvailability.UNKNOWN` 的 UI 契约）。
 */
data class TagsStrings(
    /** 服务端确认当前账号能取到播放链。 */
    val playable: String,
    /** 当前账号拿不到链，判据指向会员墙。 */
    val memberOnly: String,
    /** 服务端显式声明无版权 / 已下架。 */
    val noCopyright: String,
    /** 服务端声明这条就是原曲本身（ncm `originCoverType == 1`）。 */
    val original: String,
    /** 服务端声明这条是翻唱（ncm `originCoverType == 2`）。 */
    val cover: String,
    /** 翻唱行的副标题：参数是 (原唱艺人, 原曲名)。 */
    val coverOrigin: (String, String) -> String,
    /** 播放失败、且这首歌在另一个音源上有候选时的提示。 */
    val switchSourceHint: String,
)

/**
 * v2.3.0 · B：本地歌单文案组。
 *
 * ## 「移除」与「清空」的语义差别必须体现在文案上
 *
 * 本地歌单是**只加不减 + tombstone**（铁律 4）：用户删掉的歌不会被同步复活，
 * 但那条记录**仍在**（只是被标记）。所以文案不能用「删除」——
 * 说「删除」而实际保留记录，与说「移除」而用户以为再也不会出现，都是撒谎。
 * 本组统一用「从歌单移除」（可见效果）/「清空歌单」（连删除记录一起清掉）。
 */
data class LocalPlaylistStrings(
    /** 库页「歌单」tab 里本地歌单分组的标题。 */
    val sectionTitle: String,
    /** 新建本地歌单。 */
    val newPlaylist: String,
    /** 一个本地歌单都没有。 */
    val empty: String,
    /** 本地歌单里一首歌都没有。 */
    val emptyTracks: String,
    /** 新建对话框的输入提示。 */
    val nameHint: String,
    /** 新建对话框的确认按钮。 */
    val create: String,
    /** 建好的提示，参数是歌单名。 */
    val created: (String) -> String,
    /** 把当前播放/歌曲加入本地歌单。 */
    val addTrack: String,
    /** 加入成功。 */
    val added: String,
    /** 从本地歌单移除（只打 tombstone，不物理删除）。 */
    val removeTrack: String,
    /** 移除成功的反馈。 */
    val removed: String,
    /** 清空歌单（连 tombstone 一起清）。 */
    val clear: String,
    /** 清空的二次确认，参数是歌单名。 */
    val clearConfirm: (String) -> String,
    /** 清空成功。 */
    val cleared: String,
    /** 手动同步。 */
    val sync: String,
    /** 同步中。 */
    val syncing: String,
    /** 同步结果，参数是 (新增, 跳过)。 */
    val synced: (Int, Int) -> String,
    /** 同步失败（网络）。 */
    val syncFailed: String,
    /** 这个歌单没有可同步的远程来源。 */
    val syncNoSource: String,
    /** 用户手动加入的曲目角标。 */
    val localBadge: String,
    /** 删除本地歌单。 */
    val delete: String,
    /** 删除本地歌单的二次确认，参数是歌单名。 */
    val deleteConfirm: (String) -> String,
    /** 删除成功。 */
    val deleted: String,
    /** 「加入本地歌单」选择器的标题。 */
    val choose: String,
    /** v2.3.0 · B：把一个远程歌单转存成可编辑的本地歌单（QQ 歌单页 / ncm 歌单页的顶部入口）。 */
    val adopt: String,
    /** 转存成功的反馈，参数是歌单名。 */
    val adopted: (String) -> String,
)

/**
 * v2.5.0 · D：「添加到下一首播放」文案组。
 *
 * ## 为什么又是一个嵌套组
 *
 * dex 的 `invoke-*` 指令寄存器是 8 位 ⇒ **单个方法最多 255 个参数寄存器**，而 [Strings] 是
 * 「一个 data class 装全部 UI 文案」的结构：构造参数在 **245** 个时就已经
 * `1 (this) + 245 + 8 (默认值 mask) + 1 (DefaultConstructorMarker) = 255` 用满，
 * 再加一个参数会在**类加载期**抛 `ClassFormatError: Too many arguments in method signature`
 * （编译照过，真机启动即崩 —— v2.0.0 · HF1 与 v2.3.0 各踩过一次）。
 *
 * 本组进场时把 `actionInsertNext` / `actionAppendToQueue` 两条**既有**文案从主构造器搬了进来，
 * **腾 2 花 1**，净腾出 1 个空位（245 → 244）。算术与回归防线见 [Strings.queue] 的 KDoc
 * 与 `StringsConstructorBudgetTest`。
 *
 * ## [actionAddToNext] 与 [actionInsertNext] 是**两个不同的用户动作**，文案不许写成同一句
 *
 * - [actionInsertNext]（插播）= **立刻打断当前播放**，把这首歌插到队首并起播；
 * - [actionAddToNext]（添加到下一首播放）= **不打断当前播放**，只把它排到当前歌之后，
 *   等这一首自然放完再放。
 *
 * 两者在队列上的落点相同（当前歌之后），但用户立刻听到的结果不同（一个马上响、一个不响），
 * 所以同一个菜单里必须是两句不同的话 —— 写成同一句，用户会以为自己点错了入口。
 * `StringsConstructorBudgetTest` 里有一条用例专门钉住 `actionAddToNext != actionInsertNext`。
 */
data class QueueStrings(
    /** 「插播」：**立刻**把这首歌插到队首并播放（既有语义，从 [Strings] 搬来，措辞未改）。 */
    val actionInsertNext: String,
    /** 「最后播放」：追加到队尾（既有语义，从 [Strings] 搬来，措辞未改）。 */
    val actionAppendToQueue: String,
    /** 「添加到下一首播放」：**不打断当前播放**，只把它排到当前歌之后。 */
    val actionAddToNext: String,
    /** 操作成功提示（插到当前歌之后；原本不在队列里）。 */
    val queueAddToNextDone: String,
    /** 这首歌原本已在队列别处、被**移动**过来的提示（不是新增一份）。 */
    val queueAddToNextMoved: String,
    /** 它已经在下一首位置上的**幂等**提示（队列一个字节没动）。 */
    val queueAddToNextAlreadyNext: String,
    /** 它就是正在播放的那一首时的提示（幂等忽略）。 */
    val queueAddToNextCurrent: String,
    /** 队列为空、因此直接起播的提示。 */
    val queueAddToNextStarted: String,
)

/**
 * v2.5.1 · F：**页面转场文案组**。
 *
 * 只有两条，但**必须成组**：它们是设置页里同一个开关的「标题 + 说明」，
 * 语义上不可分割，摊平进 [Strings] 的构造参数会直接顶穿 dex 的 255 槽上限
 * （理由与算式见 [Strings.motion] 的 KDoc 与 `StringsConstructorBudgetTest`）。
 *
 * 文案要求（写进这里，避免下一个改文案的人各改各的）：
 *  - [pageTransitionLabel] 是**名词短语**（「页面切换动效」），与设置页其它开关标题同构；
 *  - [pageTransitionDescription] 只说**关掉能得到什么**（「关闭可提升低端机流畅度」），
 *    不说「开启会掉帧」—— 默认是开，说明文字不该先劝退用户。
 */
/**
 * v3.2.0 · P0：**取链失败的分类文案**。
 *
 * ## 为什么必须为每一类失败单独写一句话
 *
 * v3.1.0 及以前，所有取链失败共用一句「此源无版权，可切另一源：<另一家>」——
 * 而那句话在绝大多数情况下是**假话**：实测（2026-09-28）QQ 对同一首 VIP 歌
 * 在匿名态返回 `result=104003`（需要登录/会员），与「版权」没有任何关系。
 * 把「需要会员」「登录过期」「网络抖动」全部说成「无版权」，
 * 用户的第一反应是「这个应用的版权数据是错的」，而不是「我该去开会员」。
 *
 * 所以本组的每一条都**只说这一类的用户能验证的事实**，并给出**可执行的动作**
 * （去登录 / 去开会员 / 去购买 / 稍后重试 / 换个音源）。
 *
 * ## 文案纪律（与铁律 20 配套）
 *
 * - `noCopyright` 那一条**只有**服务端显式声明无版权时才会被选中
 *   （ncm 的 `noCopyrightRcmd`；QQ 侧没有任何字段能证明这件事）；
 * - 服务端原始 `tips` **一律不回显** —— 那是外部平台的自由文本，
 *   既不本地化也不可信（「服务端标签不可信」在客户端对外说的话上同样成立）。
 */
data class PlaybackFailureStrings(
    /** 未登录该音源。 */
    val needLogin: String,
    /** 已登录但权益不足（会员专享 / 高音质需会员 / 票据失效 —— 客户端分不开，所以两条出路都给）。 */
    val needVip: String,
    /** 数字专辑 / 单曲付费。 */
    val needPurchase: String,
    /** 凭证过期（HTTP 401/403）。 */
    val authExpired: String,
    /** 服务端**显式声明**无版权（目前只有 ncm 产出这一档）。 */
    val copyrightGone: String,
    /** 地区限制。 */
    val regionLocked: String,
    /** 网络 / 超时。 */
    val network: String,
    /** 读不懂的失败 —— 不许编原因。 */
    val unknown: String,
    /** 「可以换个音源试试」的通用后缀（参数是另一个音源的名字）。 */
    val switchSource: (String) -> String,
)

/**
 * v3.3.0 · 用户需求第 4 / 8 条：**桌面播放卡片（App Widget）**的文案组（8 条）。
 *
 * ## 为什么自成一组
 *
 * 与 [PlaybackFailureStrings] 同一形状：这 8 条的消费点只有
 * `ui/widget/NcrustWidgetProvider.kt` 一个文件，而且它们的**可见性完全不同** ——
 * 卡片由桌面进程 inflate，应用侧能决定的只有「文本内容」与「无障碍描述」，
 * 加一条文案往往同时要动布局，所以它天然是一个独立的功能面。
 *
 * ## 这个组里 5 条是无障碍描述，不是「看着好看」
 *
 * 桌面卡片上的三个按钮是**纯图标**（自绘矢量，没有文字标签），
 * TalkBack 唯一能念出来的就是 `RemoteViews.setContentDescription` 写进去的东西。
 * 少一条 = 视障用户面对一个念不出名字的按钮（v1.5.0 · C2 的同一类问题）。
 *
 * ## 一条硬纪律：卡片文案必须与应用内语言同源
 *
 * 本应用是**运行时 i18n**（`ncrust_settings` 的 `language_code`，切语言不重建 Activity）。
 * 桌面卡片的文案因此**不能**走 Android 资源字符串（`res/values-xx/`）——
 * 用户切成日语后卡片还是中文，而这个 bug 只在「换语言 → 回桌面看卡片」时出现。
 * 所以这一组和别的组一样由 `Strings` 携带，卡片侧读 `stringsForCode(getSavedLanguageCode(ctx)).widget`。
 */
data class WidgetStrings(
    /** 空态那一行：桌面卡片上没有可播放内容。 */
    val widgetEmpty: String,
    /** 落盘状态里歌名是空串时的占位（ncm 有纯音乐曲目）。 */
    val widgetUnknownTitle: String,
    /** 歌手为空时的占位。 */
    val widgetUnknownArtist: String,
    /** 播放键的无障碍描述（当前是暂停态）。 */
    val widgetPlay: String,
    /** 播放键的无障碍描述（当前是播放态）。 */
    val widgetPause: String,
    /** 上一首的无障碍描述。 */
    val widgetPrevious: String,
    /** 下一首的无障碍描述。 */
    val widgetNext: String,
    /** 整卡点击（打开应用）的无障碍描述。 */
    val widgetOpenApp: String,
)

/**
 * v3.3.0 · 用户需求第 9 条：**歌词复制 / 分享 / 生成图片**的文案组（20 条）。
 *
 * ## 为什么自成一组
 *
 * 与 [PlaybackFailureStrings] 同一形状：这 20 条属于**同一个功能面**，
 * 消费点只有 `ui/components/LyricShareSheet.kt` 与 `ui/player/LyricsView.kt` 两个文件。
 * 塞进 `PlayerUiStrings`（播放器界面的控件文案）会让下一个人从「组里有什么」
 * 看不出这条功能线；而直接加在外层会把主构造器推到 158，越过 150 的预警线。
 *
 * ## 三条机械约束
 *
 * 1. **八种语言全部要填**（具名实参 + 默认值会让漏填静默通过，
 *    所以 `StringsConstructorBudgetTest` 的家族列表里也应把它加进去）；
 * 2. **`creditLine` 是印在分享图与复制文本上的署名**，
 *    它是这段内容唯一的出处标记 —— 翻译时不要把 "Arris" 这个名字改掉；
 * 3. **`copied` / `savedToGallery` 只在真的成功之后才说**（对应 v2.6.0 的
 *    「提示与事实脱钩」修复）；失败一律走 `imageFailed` / `saveFailed`。
 */
data class ShareStrings(
    /** 入口（歌词面板右上角图标）的无障碍描述，同时是弹层标题：「分享歌词」。 */
    val action: String,
    /** 范围：当前唱段（前后各若干行）。 */
    val scopeSection: String,
    /** 范围：整首歌词。 */
    val scopeWhole: String,
    /** 选项：把译文一起复制 / 出图。 */
    val optionTranslation: String,
    /** 选项：把音译一起复制 / 出图。 */
    val optionRomanization: String,
    /** 选项：每行前面加 `[mm:ss.xx]` 时间戳。 */
    val optionTimestamps: String,
    /** 动作：复制歌词到剪贴板。 */
    val copyLyrics: String,
    /** 动作：分享纯文本。 */
    val shareText: String,
    /** 动作：生成图片并调起分享。 */
    val shareImage: String,
    /** 动作：保存图片到相册（**仅 API 29+ 显示**，理由见 LyricShareActions.canSaveToGallery）。 */
    val saveImage: String,
    /** 出图进行中（按钮置灰时显示）。 */
    val generating: String,
    /** 复制成功。 */
    val copied: String,
    /** 这段歌词是空的（没歌词 / 纯音乐）。 */
    val nothingToShare: String,
    /** 出图失败后自动退回文本分享时的一句话（说明降级，不是纯报错）。 */
    val imageFailed: String,
    /** 出图彻底失败（没有文本可退回时）。 */
    val imageFailedOnly: String,
    /** 保存到相册成功。 */
    val savedToGallery: String,
    /** 保存到相册失败。 */
    val saveFailed: String,
    /** 系统分享弹窗的标题。 */
    val chooserTitle: String,
    /** 文本 / 图片末尾的署名行：「来自 Arris」（"Arris" 这个名字不翻译）。 */
    val creditLine: String,
    /** 设备上一个能接收 ACTION_SEND 的应用都没有。 */
    val shareUnavailable: String,
)

data class MotionStrings(
    /** 设置页开关标题：「页面切换动效」。 */
    val pageTransitionLabel: String,
    /** 设置页开关说明：「关闭可提升低端机流畅度」。 */
    val pageTransitionDescription: String,
)

/**
 * v2.5.3 · P0：**设置页（含其卫星对话框）**的文案组。
 *
 * ## 为什么又是一个嵌套组
 *
 * `Strings` 的主构造参数在 v2.5.2 时是 **245**，即
 * `this(1) + 245 + ceil(245/32)=8 个默认值 mask + DefaultConstructorMarker(1) = 255` ——
 * **正好用满 dex 单方法 255 个参数寄存器**（v2.0.0 · HF1 与 v2.3.0 各因此崩过一次：
 * 编译照过、真机启动抛 `ClassFormatError`）。本组把 64 条从主构造器搬出来，
 * 用 **1 个**组参数换掉 64 个 ⇒ 净腾出 **63** 个槽位。
 *
 * 老调用点（`strings.xxx`）由 [Strings] 类体里的转发属性保住，一条都不用改；
 * 新代码可以直接写 `strings.settings.xxx`。
 *
 * ## v2.8.0：二级菜单的 14 条分组文案（64 → 78）
 *
 * 设置界面改成「一级分组卡片 → 二级页」，本组开头新增 **7 组 × (标题 + 副标题)**。
 * 字段名逐字等于 `ui/settings/SettingsRegistry.kt` 里 `SettingsGroup.titleKey` /
 * `subtitleKey` 的取值（那边只存**路径字符串**、不 import 本文件，改名必须两边同步改，
 * 否则卡片会渲染成空白）；组顺序 = `SettingsGroup` 的声明顺序 =
 * ACCOUNT / GENERAL / APPEARANCE / PLAYBACK / LYRICS / STORAGE / ABOUT。
 *
 * 为什么进组而不是进主构造器：外层 135 被
 * `StringsConstructorBudgetTest` 的精确值断言钉住，而组预算 120 还剩 42 ——
 * 这正是 v2.5.3 拆组要买到的东西。加完是 `1(this) + 78 = 79` 个 dex 槽（距 255 还有 176）。
 *
 * 参数数量监控见 `StringsConstructorBudgetTest`。
 */
data class SettingsStrings(

    // ---------- v2.8.0：设置界面二级菜单的 7 个一级分组（顺序 = SettingsGroup 声明顺序） ----------
    // 卡片标题＝xxxTitle、卡片副标题＝xxxSubtitle；副标题是**内容清单**（「语言、旋转、推荐」），
    // 不是标题的同义改写 —— 两行写成同一句话用户会看到重复的一行字（v2.1.3 规则 10）。
    /** 账号与登录：ncm / qm 两个互相独立的账号。 */
    val settingsGroupAccountTitle: String,
    val settingsGroupAccountSubtitle: String,
    /** 通用：语言、旋转、音乐人推荐、后台运行。 */
    val settingsGroupGeneralTitle: String,
    val settingsGroupGeneralSubtitle: String,
    /** 外观与动效：主题模式 / 主题色 / 主题色来源 / 页面切换动效 / 自定义背景。 */
    val settingsGroupAppearanceTitle: String,
    val settingsGroupAppearanceSubtitle: String,
    /** 播放与音质：音质档位、播放行为、波形可视化（含 v2.8.0 新增的波形分级）。 */
    val settingsGroupPlaybackTitle: String,
    val settingsGroupPlaybackSubtitle: String,
    /** 歌词：翻译、逐字、字号、TTML、音译、动态字号。 */
    val settingsGroupLyricsTitle: String,
    val settingsGroupLyricsSubtitle: String,
    /** 存储与缓存：离线缓存上限、库页显示偏好、清理入口。 */
    val settingsGroupStorageTitle: String,
    val settingsGroupStorageSubtitle: String,
    /** 关于：只读入口（本分组没有任何 prefs 键）。 */
    val settingsGroupAboutTitle: String,
    val settingsGroupAboutSubtitle: String,

    // User screen — sections
    val qualitySectionTitle: String,
    val wifiQualityLabel: String,
    val mobileQualityLabel: String,
    val qualityOptions: List<String>,
    /** API < 27 无系统 FLAC 解码器、且选中 FLAC 档位时的设置页提示（Bug1-C）。 */
    val qualityFlacUnsupportedHint: String,

    // B2-C：主题色来源三选一
    val accentSourceSectionTitle: String,
    val accentSourcePreset: String,
    val accentSourceCover: String,
    val accentSourceSystem: String,
    val accentSourceSystemHint: String,

    /** B2-D：手动重新读取系统强调色。 */
    val accentSystemRefresh: String,
    val playbackSectionTitle: String,
    val gaplessSectionTitle: String,
    val gaplessDescription: String,
    val lyricsTranslationLabel: String,
    /**
     * v3.4.8（问题 2）：这条开关**作用于哪些音源**。
     *
     * 用户的要求是「在所有音源都支持选择自己想要开启的字幕语言」。
     * ncm / qm 的服务端每条歌词只有**一轨译文**（`tlyric` / QQ 的 `trans`），
     * 没有语言元数据可选 —— 所以那两个音源的「语言选择」在事实上就是
     * 「要不要这一轨」。把这件事写进说明，用户才不会以为这里漏做了。
     */
    val lyricsTranslationHint: String,
    /**
     * v1.5.0 · B 的逐字歌词布尔开关文案。v1.5.1 · A 起设置页改成三选一
     * （[lyricsWordAnimationLabel]），这一项只为迁移路径保留，已无 UI 入口。
     */
    val lyricsWordByWordLabel: String,
    // v1.5.1 · A：逐字动画三选一（0 渐变扫过 / 1 逐字硬切 / 2 关闭逐字）。顺序必须与
    // LyricsWordAnimationMode 的常量一一对应。
    val lyricsWordAnimationLabel: String,
    val lyricsWordAnimationOptions: List<String>,
    // v1.5.1 · D：媒体面板歌词开关（默认关）。开启后 ARTIST 变成「艺人 · 当前歌词行」。
    val lyricsInMediaSessionLabel: String,
    val lyricsInMediaSessionHint: String,
    // v1.5.2：逐字扫过质量三选一（0 自动 / 1 高级软边 / 2 兼容硬边）。顺序必须与
    // LyricsSweepQuality 的常量一一对应。
    val lyricsSweepQualityLabel: String,
    val lyricsSweepQualityOptions: List<String>,
    // v1.9.0：AMLL TTML 歌词源总开关（默认开）+ 与 ncm 歌词同时可用时是否优先用 TTML（默认开）。
    // 两者都只在「用户开了 TTML」时才有意义；关掉后行为与 v1.8.1 完全一致（一个 TTML 请求都不发）。
    val lyricsTtmlEnabledLabel: String,
    val lyricsTtmlFirstLabel: String,
    // v1.9.3：音译（罗马音 / 粤拼）显示开关，**默认关**。只影响显示——
    // 没有音译数据的歌打开后也没有任何变化（不会多出空行）。
    val lyricsRomanizationLabel: String,
    val lyricsRomanizationHint: String,
    // v2.0.0 · T2：播放时禁止熄屏（默认开）。
    val keepScreenOnLabel: String,
    val keepScreenOnHint: String,
    // v2.0.0 · T4：动态字号（实验性，默认关）。
    val dynamicFontLabel: String,
    val dynamicFontHint: String,
    // v1.5.1 · E：歌词字号（倍率档位文案是纯数字，与语言无关，不进 i18n）。
    val lyricsFontScaleLabel: String,
    /** 设置页整行开关的标题。 */
    val autoRotateLabel: String,
    /** 设置页整行开关的说明。 */
    val autoRotateDescription: String,
    // v1.8.0 · T3：音频可视化（大屏幕模式左栏、封面下方的波形条）。
    /** 设置页整行开关的标题。 */
    val audioVisualizerLabel: String,
    /** 设置页整行开关的说明。 */
    val audioVisualizerDescription: String,
    val themeSectionTitle: String,
    val themeModeSectionTitle: String,
    val themeModeSystem: String,
    val themeModeDark: String,
    val themeModeLight: String,
    val themeColorNames: List<String>,
    val languageSectionTitle: String,
    val aboutButton: String,
    val storageSectionTitle: String,
    val clearCache: String,
    val clearCacheConfirm: String,
    /**
     * v3.3.0 · 用户反馈第 1 条的第四个缺陷：**「清除缓存」会把离线音频一起删掉，而确认框不说**。
     *
     * 旧行为是有意的（v1.6.0 · D1：避免留下指向空缓存的死条目），但确认框只写
     * 「确定清除全部缓存？」—— 用户点一下，几百 MB 离线音频永久消失，而且
     * 事后无从得知是这一步干的。这属于「危险操作没有如实告知」，不是功能缺陷。
     *
     * 现在给两个**明确**的选择：默认只清缓存（图片 + 临时文件），
     * 想连离线音频一起清必须走第二个按钮。两个按钮的文案都写清后果。
     */
    val clearCacheAudioNote: String,
    val clearCacheEverything: String,

    // User screen — 自定义背景（v1.2.0 · B3）
    val bgSectionTitle: String,
    val bgPick: String,
    val bgChange: String,
    val bgRemove: String,
    val bgImportFailed: String,

    // Auth / Account
    val accountDialogTitle: String,
    val nicknameLabel: (String) -> String,
    val uidLabel: (String) -> String,
    val logoutButton: String,
    val notLoggedIn: String,
    val loginHint: String,

    // Phone-side QR scan to authorize another device (LAN cookie handoff)
    val scanEntryTitle: String,
    val userAvatarDesc: String,

    // User screen
    val userIconDesc: String,

    // Background activity permission (battery optimization whitelist)
    val batteryTitle: String,
    val batteryMessage: String,
    val batteryAllow: String,
    val batteryLater: String,
    /**
     * v3.3.0：这一行的**两态回显**（用户反馈第 5 条「后台播放明明有一个可以打开的图标
     * 但是点不动」）。
     *
     * 真根因不是回调失效 —— 实测点它确实拉起了系统电池优化授权页
     * （`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`），点「允许」后
     * `dumpsys deviceidle whitelist` 从「不在白名单」变成
     * `user,com.takahashirinta.ncrust,10183`：**功能是好的**。
     *
     * 坏的是**反馈**。应用已在白名单内时（首启弹窗引导过就是常态），系统页启动后
     * 立刻 finish ⇒ 屏幕**零变化**；而这一行本身没有开关、没有状态，失败还被
     * `runCatching` 静默吞掉。用户看到的就只能是「点了没反应」。
     *
     * ⚠️ 措辞必须描述**权限状态**，不能说成「后台播放开着没有」——
     * 本应用的后台播放是**恒开**的（前台服务 + `WAKE_MODE_NETWORK`），
     * 用户唯一能影响的就是「在不在白名单」。
     */
    val batteryStatusAllowed: String,
    val batteryStatusDenied: String,
    /** 已在白名单时点击该行的提示（那时跳系统页零变化，必须给一句可见反馈）。 */
    val batteryAlreadyAllowed: String,
    /** 两条跳转路径都失败时的提示（原来是 `runCatching` 静默吞掉）。 */
    val batteryJumpFailed: String,
    // ---------- v3.1.0 · B：B 站音源开关 ----------
    // 按 v2.2.1 规则 5：新增文案必须往分组里放（外层主构造器预算 150，本组 64 → 66）。
    /** 设置页开关标题。 */
    val bilibiliEnabledLabel: String,
    /**
     * 开关的副标题。
     *
     * ⚠️ 必须写清三件事，否则「打开了会怎样」对用户是黑箱：
     * 它从**哪些地方**取内容（B 站音频区与视频音轨）、需要**联网到第三方**、
     * 以及它**不会**影响另外两个音源。
     */
    val bilibiliEnabledDescription: String,
)


/**
 * v2.8.0：**波形效果分级**的文案组（16 条）。
 *
 * ## 为什么单开一组
 *
 * 这批文案原本该进 [SettingsStrings]，但那边已经是 **78** 个参数，而
 * `StringsConstructorBudgetTest` 的组**预警线是 80**（硬上限 120）：16 条塞进去 = 94，
 * 每一轮测试都会打印 WARN。按仓库纪律「再加字段请拆组」，这里单开一组，
 * 外层 `Strings` 只多**一个**组参数（135 → **136**，仍 < 150 的预算）。
 *
 * ## 命中三条事实约束（写错就是骗用户）
 *
 * 1. [visualizerDragLabel] / [visualizerDragDescription]：本版**没有实现拖拽**，
 *    实际行为是**点一下**波形条在「渐变流动」与「按时序着色」之间切换；
 *    文案必须写「点按」，且在说明里写明拖拽未实现（手势与播放器的冲突未在真机验证）。
 * 2. [visualizerNotSpectrumHint]：必须写明**不是频谱** —— 本版没有频域数据源，
 *    亮度表达的是**时间新旧**，不是低/中/高频。
 * 3. [visualizerTierDescription]：必须写明**低端设备默认简洁档** —— 用户看到的
 *    「已选：简洁」可能是系统按设备判据解析出来的默认值，不是他自己选的。
 *
 * 属性名逐字等于 `ui/player/waveform/VisualizerStrings.kt` 的 `Property.*` 常量
 * （那是全仓库唯一的字面量落点，改名前先看那里）。参数数量监控见 `StringsConstructorBudgetTest`。
 */
data class WaveformStrings(

    // 三选一：档位本身（顺序 = visualizer_tier 的 0 / 1 / 2）
    val visualizerTierLabel: String,
    /** 档位说明。**必须**含「低端设备默认简洁档」。 */
    val visualizerTierDescription: String,
    val visualizerTierSimple: String,
    val visualizerTierRefined: String,
    val visualizerTierShowcase: String,

    // 炫技档总开关
    val visualizerShowcaseLabel: String,
    val visualizerShowcaseDescription: String,

    // 炫技档的三个子效果
    val visualizerShockwaveLabel: String,
    val visualizerShockwaveDescription: String,
    val visualizerParticlesLabel: String,
    val visualizerParticlesDescription: String,
    val visualizerPerspectiveLabel: String,
    val visualizerPerspectiveDescription: String,

    /** 点按切换着色。**不是**拖拽（本版未实现拖拽）。 */
    val visualizerDragLabel: String,
    val visualizerDragDescription: String,

    /** 「亮度 = 时间新旧，不是频谱」的说明。 */
    val visualizerNotSpectrumHint: String,

    // v2.9.0：统一「动效强度」——一个档位同时驱动波形与界面动效。
    /** 档位标题：v2.8.0 的「波形效果档位」升级为「动效强度」。 */
    val motionIntensityLabel: String,
    /** 档位说明。**必须**同时写清三件事：①三档各开什么（波形 + 界面动效）；②低端设备默认简洁档；③界面动效另有总开关。 */
    val motionIntensityDescription: String,
    /** 「界面动效」总开关标题（A 档基础界面动效默认开，这里可以整关）。 */
    val uiMotionLabel: String,
    /** 总开关说明。**必须**写明：关掉 = 背景回纯色、无呼吸/脉冲/粒子，性能最优；且**不影响波形**档位。 */
    val uiMotionDescription: String,

    // ── v3.0.0：音频特征驱动的动效，**每个都有自己的开关**（铁律 26）───────────────
    // 文案上的硬要求（三条都不是措辞偏好，是事实约束）：
    //  1. 冲击波 / 光晕的说明必须写明触发源是「鼓点 / 瞬态」，不是"随机"或"一直有"；
    //  2. 粒子的说明必须写明它跟的是**中高频**（人声 / 弦乐 / 镲片），
    //     否则用户会以为粒子跟鼓点走、进而觉得"没对上拍"；
    //  3. 多频段那一条**必须**点名「不是频谱」——它只是三个带宽很宽的频带包络。
    /** 冲击波开关标题。 */
    val motionShockwaveLabel: String,
    /** 冲击波说明。**必须**写明：由鼓点等瞬态触发，力度越大扩散越大。 */
    val motionShockwaveDescription: String,
    /** 光晕开关标题。 */
    val motionHaloLabel: String,
    /** 光晕说明。**必须**写明：与冲击波同一个触发源（瞬态），炫技档会多扩一圈。 */
    val motionHaloDescription: String,
    /** 粒子开关标题。 */
    val motionParticlesLabel: String,
    /** 粒子说明。**必须**写明：跟随**中高频**能量（人声 / 弦乐 / 镲片），能量越强生成越快。 */
    val motionParticlesDescription: String,
    /** 多频段波形开关标题。 */
    val motionWaveBandsLabel: String,
    /** 多频段波形说明。**必须**点名「不是频谱」，并说明横轴仍是时间。 */
    val motionWaveBandsDescription: String,
    /** 背景呼吸开关标题。 */
    val motionBreathingLabel: String,
    /** 背景呼吸说明。**必须**写明：跟随整体响度。 */
    val motionBreathingDescription: String,

    // ---------- v3.2.0 · P1：界面律动（节拍驱动）那一层的独立开关（铁律 22）----------
    // 归类判据：**驱动量是否来自 `MotionEnvelope` 的节拍 / 强拍 / 响度包络**。
    // 逐项表与判据出处见 `docs/verification/v3.2.0/probe-ui-jitter.md` §5。
    /** 律动总闸的标题。 */
    val motionRhythmLabel: String,
    /** 律动总闸的说明（必须写明「冲击波/光晕/粒子不受影响」，否则用户不敢关）。 */
    val motionRhythmDescription: String,
    /** 封面浮动的标题。 */
    val motionCoverFloatLabel: String,
    /** 封面浮动的说明（幅度 + 生效档位，两件都必须写）。 */
    val motionCoverFloatDescription: String,
    /** 歌词律动的标题。 */
    val motionLyricPulseLabel: String,
    /** 歌词律动的说明。 */
    val motionLyricPulseDescription: String,
    /** 控制条脉冲的标题。 */
    val motionBarPulseLabel: String,
    /** 控制条脉冲的说明。 */
    val motionBarPulseDescription: String,
)

/**
 * v2.5.3 · P0：**关于页**的文案组（项目信息 / 技术栈 / 名单 / 致谢）。
 *
 * ## 为什么又是一个嵌套组
 *
 * `Strings` 的主构造参数在 v2.5.2 时是 **245**，即
 * `this(1) + 245 + ceil(245/32)=8 个默认值 mask + DefaultConstructorMarker(1) = 255` ——
 * **正好用满 dex 单方法 255 个参数寄存器**（v2.0.0 · HF1 与 v2.3.0 各因此崩过一次：
 * 编译照过、真机启动抛 `ClassFormatError`）。本组把 25 条从主构造器搬出来，
 * 用 **1 个**组参数换掉 25 个 ⇒ 净腾出 **24** 个槽位。
 *
 * 老调用点（`strings.xxx`）由 [Strings] 类体里的转发属性保住，一条都不用改；
 * 新代码可以直接写 `strings.about.xxx`。
 *
 * 参数数量监控见 `StringsConstructorBudgetTest`。
 */
data class AboutStrings(

    // About
    val aboutTitle: String,
    val aboutAppSubtitle: String,
    val aboutSectionProject: String,
    val aboutVersion: String,
    val aboutDeveloperOriginal: String,
    val aboutDeveloperFork: String,
    val aboutLicense: String,
    val aboutLicenseGplWithMit: String,
    val aboutRepositoryFork: String,
    val aboutRepositoryOriginal: String,
    val aboutSectionTechStack: String,
    val aboutLangLabel: String,
    val aboutUIFrameworkLabel: String,
    val aboutDesignSystemLabel: String = "Design System",
    val aboutAudioEngineLabel: String,
    val aboutNetworkLabel: String,
    val aboutImageLabel: String,
    val aboutSectionTeam: String,
    val aboutRoleDev: String,
    val aboutRoleTester: String,
    val aboutRoleForkMaintainer: String,
    val aboutSectionCredits: String,
    val aboutCreditCli: String,
    val aboutCreditAnim: String,
    val aboutCreditDesign: String
)


/**
 * v2.5.3 · P0：**播放器界面**的文案组（传输控件 / 歌词页 / 队列面板）。
 *
 * ## 为什么又是一个嵌套组
 *
 * `Strings` 的主构造参数在 v2.5.2 时是 **245**，即
 * `this(1) + 245 + ceil(245/32)=8 个默认值 mask + DefaultConstructorMarker(1) = 255` ——
 * **正好用满 dex 单方法 255 个参数寄存器**（v2.0.0 · HF1 与 v2.3.0 各因此崩过一次：
 * 编译照过、真机启动抛 `ClassFormatError`）。本组把 31 条从主构造器搬出来，
 * 用 **1 个**组参数换掉 31 个 ⇒ 净腾出 **30** 个槽位。
 *
 * 老调用点（`strings.xxx`）由 [Strings] 类体里的转发属性保住，一条都不用改；
 * 新代码可以直接写 `strings.playerUi.xxx`。
 *
 * 参数数量监控见 `StringsConstructorBudgetTest`。
 */
data class PlayerUiStrings(

    // Player controls
    val prevButton: String,
    val playButton: String,
    val pauseButton: String,
    val nextButton: String,
    val lyricsButton: String,
    val queueButton: String,
    val addToLibraryButton: String,
    /** 实际档位低于偏好档位时，播放器音质标签后的角标（Bug1-B）。 */
    val qualityDowngradedBadge: String,

    /** A3：实际文件低于请求档位，但该曲有这个档位 —— 账号/版权没给到。 */
    val qualityNoEntitlementBadge: String,

    /** A3：该曲本身就没有请求的档位。 */
    val qualitySongLacksTierBadge: String,
    /** 歌词界面 A- / A+ 的无障碍描述。 */
    val lyricsFontSmaller: String,
    val lyricsFontLarger: String,
    // v1.5.0 · C2：控制栏把手（全屏播放器底部那条 40×3dp 小横条）的无障碍描述。
    // 它此前对 TalkBack 完全不可见 —— 视力障碍用户收起控制栏后再也拿不回来。
    val controlsHandleLabel: String,
    /** P1：大屏幕模式入口按钮（横屏桌面播放器布局）。 */
    val bigScreenEnter: String,
    /** P1：大屏幕模式出口按钮。与入口是同一个按钮，横屏大屏下图标与描述切换。 */
    val bigScreenExit: String,
    // v1.8.0 · T4：应用内「自动旋转」。⚠️ 它只控制本应用是否跟随传感器，
    // 与系统设置里的"自动旋转"互相独立（应用既不读也不改系统设置）。
    /** 开启态（跟随传感器旋转；在播放器里转横屏会自动进入大屏幕模式）。 */
    val autoRotateOn: String,
    /** 关闭态（锁定竖屏；进出大屏幕模式只走 ⤢ 按钮）。 */
    val autoRotateOff: String,

    // Player UI
    val noLyrics: String,
    val emptyQueue: String,
    val collapsePlayer: String,
    val lyricsLabel: String,

    // Player queue panel
    val queueTitle: String,
    val playModeButton: String,
    val saveAsPlaylist: String,
    val noSongPlaying: String,
    val queueSectionPast: String,
    val queueSectionNow: String,
    val queueSectionUpcoming: String,
    val queueInfinityPlaceholder: String,
    val queueClearAll: String,

    val clearQueue: String
)


/**
 * v3.3.0 · 用户需求第 10 条：**播放统计页**（`ui/screen/StatsScreen.kt`）的文案组。
 *
 * ## 为什么是嵌套组
 *
 * `Strings` 的主构造器每加 1 个参数就要占 1 个 dex 槽（算式见 [Strings] 的 KDoc）。
 * 37 条文案直接进主构造器会把它推到 178 —— 早已越过 `StringsConstructorBudgetTest`
 * 的 150 预算。放进组里，外层只花 1 个参数。
 *
 * ## 这一组里**没有**「曲风 / 语种」类文案，这是有意的
 *
 * 任务书要求「统计各个类歌曲」。查证结果：`network/SongItem.kt` 与 `network/model/`
 * 下**没有任何流派 / 曲风 / 语种类字段**（全仓库 grep `genre` / `曲风` / `流派` / `语种`
 * 零命中，2026-02 核对）。服务端本来就不下发，客户端**造不出**这个维度 ——
 * 所以统计页里**不显示**它，也不在这里预备一组永远为空的文案。
 * 页面的「统计口径」区块里有一条 `methodNoGenre` **如实告诉用户**为什么没有这个维度；
 * 那比一个空图例诚实。
 *
 * ## 格式化为什么用 lambda 而不是把单位写进模板
 *
 * 中文「3 小时 12 分」、英文「3 h 12 min」、日文「3 時間 12 分」的单位位置与数量都不同，
 * 所以时长由 `(时, 分)` / `(分, 秒)` / `(秒)` 三个 lambda 组装，
 * 判断「该显示到时还是到分」的逻辑在 `StatsScreen` 里只有一处（`statsDurationText`）。
 */
/**
 * 「QQ 音乐 App 扫码」登录的专属文案（v3.4.9）。
 *
 * 它与 [SourceStrings] 里那套 `sourceQr*`（**QQ 互联**扫码）是两条不同的路：
 * 互联那条只能拿到 cookie（到期要重登），这条能换到**完整凭证 JSON**
 * （含 `refreshKey`，到期自动续期）。所以文案必须能区分 ——
 * 混成一句话会让用户以为「扫码 = 可续期」，而那对互联那条不成立。
 *
 * 为什么单开一组（而不是并进 [SourceStrings]）：见 `Strings.qqScan` 的 KDoc ——
 * 那边的预警线已经撞上了，而且这两套文案不属于同一条业务线。
 */
data class QqScanStrings(
    /** 账号区块里的入口文案，例如「用 QQ 音乐 App 扫码」。 */
    val qqScanLoginAction: String,
    /** 扫码浮层的标题。 */
    val qqScanTitle: String,
    /**
     * 扫码浮层底部的说明。
     *
     * 这一条是**承重的**：它是用户判断「该选哪一条扫码路」的唯一依据 ——
     * 只有它说清了「这条到期会自动续期」。写成与互联扫码同一句，
     * 用户就没有任何理由选这一条，而选错就意味着继续一周重登一次。
     */
    val qqScanNote: String,
)

data class StatsStrings(
    /** 页面标题（Groove 风页头的大字）。 */
    val title: String,
    /** 页头副标题：一句话说明「统计范围是本机」。 */
    val subtitle: String,
    /** 「统计自 %s」（参数是 ISO 日期，如 `2026-02-14`）。 */
    val since: (String) -> String,

    // ---- 总览四格 ----
    val totalLabel: String,
    val playsLabel: String,
    val activeDaysLabel: String,
    val dailyAverageLabel: String,
    /** 日均的分母说明（必须有，否则用户会把它读成「按自然日平均」）。 */
    val dailyAverageHint: String,

    // ---- 柱状图 ----
    val recentDaysTitle: String,
    /** 「峰值 %s」（参数是格式化后的时长）。 */
    val peakLabel: (String) -> String,

    // ---- 各平台 ----
    val bySourceTitle: String,
    val sourceNetease: String,
    val sourceQq: String,
    val sourceBili: String,
    /** 未知音源的回退名（参数是音源 key）。将来加新音源时页面不会显示空白。 */
    val sourceOther: (String) -> String,

    // ---- Top 歌曲 ----
    val topSongsTitle: String,
    /** 「播放 %d 次」。 */
    val songPlays: (Long) -> String,
    val unknownSong: String,
    val unknownArtist: String,

    // ---- 空态 ----
    val empty: String,
    val emptyHint: String,

    // ---- 统计口径（把「为什么这个数是这样」写在页面上，而不是只写在代码注释里）----
    val methodTitle: String,
    val methodListen: String,
    val methodPlayThreshold: String,
    val methodFlush: String,
    /** 「按设备本地时区分天（当前 %s）」。 */
    val methodDay: (String) -> String,
    /** 如实说明「曲风 / 语种维度不存在」以及为什么。 */
    val methodNoGenre: String,

    // ---- 其它 ----
    /** 占比文案（0..100 的整数）。 */
    val sharePercent: (Int) -> String,
    val clearLabel: String,
    val clearTitle: String,
    val clearMessage: String,
    val clearConfirm: String,
    val clearCancel: String,
    val cleared: String,

    // ---- 时长格式（三个粒度，见类 KDoc）----
    val durationHm: (Int, Int) -> String,
    val durationMinSec: (Int, Int) -> String,
    val durationSec: (Int) -> String,
)

/**
 * v3.4.8：**B 站音质与字幕**的文案组（17 条）。
 *
 * ## 它对应哪两个用户问题
 *
 * | 用户问题 | 落在这里的文案 |
 * |---|---|
 * | ① 「B 站有大会员支持的 Hi-Res，但我们永远播放普通版本」 | [biliQualityCapLabel] / [biliPreferFlacLabel] 两组 |
 * | ② 「有些歌曲有多种语言歌词，希望能自己选要开启的字幕语言」 | [biliSubtitleLangLabel] 一组 |
 *
 * ## 三条事实约束（写错就是骗用户，与 `WaveformStrings` 的写法同源）
 *
 * 1. **无损 / Hi-Res 需要大会员，且不是每个视频都有** —— [biliQualityCapDescription]
 *    必须同时写出这两件事。实测：同一视频同一 cid，匿名请求 `dash.flac` 恒为 `null`，
 *    登录 + 年度大会员才有内容；而 12 个音乐视频里带 `flac` 的只是少数。
 *    只写「需要大会员」会让没开的用户以为「开了就有」。
 * 2. **FLAC 是有代价的** —— [biliPreferFlacDescription] 必须写出码率量级
 *    （实测 `30251` 是 **2247494 / 3154514 bps**，即 2~3 Mbps）。只说「音质更好」
 *    会让用户在一个看似无损的开关上花掉十倍流量。
 * 3. **[biliSubtitleLangOff] 是真的不发请求** —— [biliSubtitleLangDescription]
 *    必须写明这一点。实测代码路径：选中它之后 `fetchLyric` 在取 `cid` **之前**返回，
 *    一个字节都不发给 B 站。写成「不显示字幕」会让用户以为只是界面上看不见。
 *
 * ## 为什么语言档位用「语言名」而不是语言代码
 *
 * 用户看到的是 `zh-Hans` / `zh-Hant` 还是「简体中文 / 繁體中文」，决定了他能不能
 * 在设置页一眼选对。语言代码只在实现与日志里出现（[BiliSubtitleLang.key]）。
 *
 * 参数数量监控见 `StringsConstructorBudgetTest`（本组 17，组上限 120）。
 */
data class BiliStrings(
    // ---------- 音质上限（问题 3） ----------
    /** 音质上限下拉的标题。 */
    val biliQualityCapLabel: String,
    /**
     * 音质上限的说明。
     *
     * ⚠️ 必须写明「需要大会员」**与**「只有部分视频提供」两件事，见类文档约束 1。
     */
    val biliQualityCapDescription: String,
    /** 上限：跟随全局播放音质档位（默认，等价于没有这个开关）。 */
    val biliQualityCapAuto: String,
    /** 上限：放开到无损 / Hi-Res（大会员）。 */
    val biliQualityCapHires: String,
    /** 上限：320K（永不请求无损）。 */
    val biliQualityCapExhigh: String,
    /** 上限：192K（省流）。 */
    val biliQualityCapHigher: String,

    // ---------- 优先无损 FLAC（问题 3） ----------
    /** 「优先无损 FLAC」开关的标题。 */
    val biliPreferFlacLabel: String,
    /** 说明。必须写出无损的码率量级，见类文档约束 2。 */
    val biliPreferFlacDescription: String,

    // ---------- 字幕语言（问题 2） ----------
    /** 字幕语言下拉的标题。 */
    val biliSubtitleLangLabel: String,
    /** 说明。必须写明「不抓取」是真的不发请求，见类文档约束 3。 */
    val biliSubtitleLangDescription: String,
    /** 档位：跟随应用界面语言，其次中文。 */
    val biliSubtitleLangAuto: String,
    /** 档位：简体中文。 */
    val biliSubtitleLangZhHans: String,
    /** 档位：繁体中文。 */
    val biliSubtitleLangZhHant: String,
    /** 档位：英语。 */
    val biliSubtitleLangEn: String,
    /** 档位：日语。 */
    val biliSubtitleLangJa: String,
    /** 档位：韩语。 */
    val biliSubtitleLangKo: String,
    /** 档位：**不抓取字幕**（真的不发请求）。 */
    val biliSubtitleLangOff: String,
)
