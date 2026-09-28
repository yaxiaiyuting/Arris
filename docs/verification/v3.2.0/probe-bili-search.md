# 原始证据 · v3.2.0 P0-C / P0-D 探针（B 站搜不到歌 / 切「只搜 B 站」卡死）

**本文件先于代码改动落盘**（仓库纪律：探针先行）。代码改动见文末 §6 与提交记录。

- 分支 / HEAD：`master` / `08e2641`
- 探针文件（新增，不改旧探针）：`app/src/androidTest/java/com/takahashirinta/ncrust/probe/BiliV320ProbeTest.kt`
- 旧探针基线：`docs/verification/v3.1.0/verification/EVIDENCE-bili-probe.md`
- 涉及的生产代码：`bili/BiliApi.kt`、`bili/BiliSourceProvider.kt`、`ui/viewmodel/SearchViewModel.kt`、
  `ui/screen/SearchScreen.kt`、`ui/components/SourceFilter.kt`、`ui/components/SourceCounts.kt`

## 0. 口径（先说清哪些是实测、哪些是静态、哪些没测到）

| 类别 | 本文件里的标记 | 可信度 |
|---|---|---|
| 父任务已完成的 host 侧实测（2026-09-28） | 「host 实测」 | 接口/签名/解析层**已证伪**的那部分，直接引用，不重复劳动 |
| v3.1.0 已落盘的设备侧实测 | 「v3.1.0 实测」 | `PROBE-BILI-*`，含 10 条真实 bvid |
| 本次源码逐行审计（带 file:line） | 「静态」 | 行号可复核；结论是「代码必然这样做」，不是推断 |
| 本次设备侧新增实测 | 「v3.2.0 实测」 | 见 §5 |
| 明确没测到的 | 「**未验证**」+ 原因 | 不包装成通过 |

---

## 1. 结论先行

### P0-C（B 站搜不到歌）：**根因是「B 站那条腿的同步阻塞网络调用落在主线程上」**

不是签名错、不是接口错、不是解析错、不是映射错、不是跨源匹配误过滤。链路是：

1. `SearchViewModel.onQueryChanged` → `viewModelScope.launch { … }`
   （`viewModelScope` = `SupervisorJob + Dispatchers.Main.immediate`）—— `SearchViewModel.kt:111`、`:125`
2. 同一个 `Main.immediate` 上下文里 `coroutineScope { … }` —— `SearchViewModel.kt:221`
3. B 站那条腿 `async { withTimeoutOrNull(4000) { SourceRouter.searchSongs(BILIBILI, …) } }`
   —— `SearchViewModel.kt:267-282`（`async` 默认 `start = DEFAULT`，在 `Main.immediate` 上
   `isDispatchNeeded == false` ⇒ **内联启动**，不是「排队等主线程」）
4. → `BiliSourceProvider.searchSongs`（`BiliSourceProvider.kt:71-81`）→ `BiliApi.searchVideos`
   （`BiliSourceProvider.kt:77`；音频区关键词走 `:74` 的 `BiliApi.audioInfo`）
5. → `BiliApi.signedGet`（`BiliApi.kt:212-224`）→ `wbiKeys`（`:162-176`）→ `get`（`:180-196`）
   → **`client.newCall(request).execute()`（`BiliApi.kt:191`）在主线程上做 socket IO**
6. Android 对 `targetSdk >= 11` 的进程在主线程上启用 `StrictMode.enableDeathOnNetwork()`
   （`ActivityThread.handleBindApplication`），任何 socket `connect/read/write` 都会抛
   `NetworkOnMainThreadException`（平台事实，设备侧判据见 §5 的 `PROBE-BILI320-SOCKET`）
7. 该异常被 **`wbiKeys()` 的 `runCatching { get(NAV_URL) }.getOrNull()`（`BiliApi.kt:165`）静默吞掉**
   ⇒ `wbiKeys` 返回 `null` ⇒ 只留下一条 `Log.w("BiliApi", "nav 没有返回 wbi_img（响应长度=0）")`（`:167-170`）
8. ⇒ `signedGet` 返回**空串**（`:214`），`-352/412` 的重签逻辑根本没机会跑（`:219`）
9. ⇒ `BiliParse.parseSearchTracks("")` 解析空串 ⇒ `emptyList()` ⇒ `searchVideos` 记
   `"search '…' -> 0 条（正文长度=0）"`（`:300`）
10. ⇒ `SourceCounts(biliCount=0, biliStatus=DONE)`（`SearchViewModel.kt:362-363`、`:413-414`）
    ⇒ 统计行显示 `网易云 N 首 · QQ 音乐 M 首 · B站 0 首`（`SourceCounts.kt:108-114`）

**一句话**：HTTP 请求**从未发出**，所以「0 条」既不是接口返回的 0，也不是解析丢弃的 0。
用户看到的「B站 0 首」是一句由**被吞掉的平台异常**伪造出来的结论，全程没有任何错误提示。

### P0-D（切「只搜 B 站」卡死）：**根因是 UI 结构 —— 筛选档被自己过滤掉，页面变成死胡同**

1. 筛选档（SourceFilter chips）渲染在结果 `LazyColumn` 的**第一个 item** 里，且只在
   `songs.isNotEmpty()` 时才挂载 —— `SearchScreen.kt:500-525`
2. 结果区的外层判据是 `if (visibleSongs.isEmpty() && !isLoading) { 空态 Box } else { LazyColumn { … } }`
   —— `SearchScreen.kt:478-491`
3. `visibleSongs = sourceFilter.filter(songs)` —— `SearchScreen.kt:128-130`（`SourceFilter.kt:62-63`）
4. 用户点「只看 B 站」（`:518-520`）后，若本轮 `songs` 里没有 B 站行（P0-C 的直接后果就是 0 行）
   ⇒ `visibleSongs` 为空 ⇒ **整条 `LazyColumn`（连同筛选档与统计行）被替换成空态 Box**
   ⇒ 筛选档消失、**没有任何路径点回「双源」** ⇒ 用户描述为「卡住 / 无法退出」
5. 叠加因素：同一个「0 行」也发生在 B 站正常返回 0 条时 —— **这是设计缺口，不是网络问题**：
   一个「只看 X」的筛选档在 X 为空时必须仍然可见可点。

次要但同向的三条（都在本 P0 的修复面内）：

- 空态文案不区分「本次搜索没有结果」与「当前筛选下没有结果」——
  `SourceFilter.kt:33` 的 KDoc 把 `emptyHint` 写成**承诺**，但**全仓库没有这个字符串**
  （`grep -rn "emptyHint" app/src/main` 只命中那条注释），两处都渲染 `strings.searchSongsEmpty`（`SearchScreen.kt:484`）
- B 站的 TIMEOUT / ERROR **没有重试入口**：`SourceCounts.qqUnavailable`（`SourceCounts.kt:88-89`）
  只覆盖 QQ；`biliStatus` 没有任何对应的 `biliUnavailable`
- 搜索页**没有下拉刷新**（仓库有组件，见 §3-D5）

---

## 2. P0-C 八问（逐条）

### C1. B 站搜索实际调用的是哪个接口？音频区接口还是视频搜索？

**视频搜索 `/x/web-interface/wbi/search/type?search_type=video`，需要 Wbi 签名。**
音频区**没有**搜索接口（v3.1.0 穷举 10 个候选端点证伪：8 个 404、`/web/menu/search` 200 但空壳、
`search_type=audio|music` 返回 `-1200 被降级过滤的请求`；见 `AGENTS.md` v3.1.0 坑 1）。

- 常量：`BiliApi.kt:71`（`SEARCH_URL`）；参数：`BiliApi.kt:291-296`
  （`search_type=video` / `keyword` / `page=1` / `page_size`）
- 唯一的例外是「关键词本身是一个 auid」：`BiliSourceProvider.searchSongs` 先试
  `parseAuidKeyword`（`BiliSourceProvider.kt:73`、`:179-185`），命中则走
  `BiliApi.audioInfo`（音频区**详情**，`BiliApi.kt:339-344`）—— 那是直链语义，不是搜索。
  v3.2.0 的**这个例外也有主线程问题**（见 §4 矩阵），本次一并覆盖。
- 静态证据：`SourceRouter.searchSongs(BILIBILI, …)`（`SourceRouter.kt:82-87`）
  → `BiliSourceProvider.searchSongs`（`:71-81`）→ `BiliApi.searchVideos`（`:77`）

### C2. Wbi 签名是否正确实现？（`img_key`/`sub_key`、`w_rid`/`wts`、412 还是别的错误码）

**实现正确，且被服务端接受过 —— 但 App 内这条链在 v3.1.0 里从没跑到签名那一步。**

| 主张 | 证据 | 判决 |
|---|---|---|
| 密钥来源正确（`nav` 的 `data.wbi_img`） | `BiliApi.kt:165` + `BiliParse.parseNavWbiKeys`（`BiliModels.kt:299`） | ✅ |
| `w_rid`/`wts` 拼接正确（含中文关键词、空格、敏感字符） | `BiliApi.kt:215`；`BiliWbiTest` 四条**固定向量**（AGENTS v3.1.0 纪律 2） | ✅ |
| 服务端接受签名 | v3.1.0 实测 `PROBE-BILI-SEARCH n=10` + 10 条真实 bvid（`EVIDENCE-bili-probe.md:39-44`）；host 实测 `code:0/result 20 条` | ✅ |
| **412 出现过吗** | **App 内一次都没有**。412 只会由一次真实 HTTP 响应产生，而 `execute()` 在主线程上先抛了；`-352`/`412` 的重签分支（`BiliApi.kt:219-222`）是死代码 | — |

「签名算法错」这条**已被证伪**（父任务 host 侧实测 + v3.1.0 设备侧实测双重证据）。
本 P0 的真实形状是：**签名请求根本没发出去**。

### C3. 搜索请求的原始响应（HTTP 状态码 / body / 是否被风控）

**App 内没有原始响应可看 —— 请求在 socket 层就被平台拦掉了**（§1 步骤 5-7）。
唯一的痕迹是 `BiliApi.kt:168` 的 `Log.w(TAG, "nav 没有返回 wbi_img（响应长度=0）")`
（响应长度 0 = 一个字节都没收到）。

- 静态：`BiliApi.get()` 无 try/catch（`:180-196`），`wbiKeys` 的 `runCatching`（`:165`）是唯一的兜底，
  它把「网络异常」与「nav 没给 wbi_img」折叠成同一个 `null` ⇒ **风控 / 断网 / 主线程政策三种原因不可区分**。
- 对照（IO 线程上同一份代码）：v3.1.0 实测 HTTP 200 + `code:0` + 10 条；host 实测 200 + 20 条；
  本次设备侧（模拟器 IO 线程）见 §5。
- **未验证**：App 内是否会被 B 站风控（412 / -352）。修好调度之后才第一次有机会观测。

### C4. 搜索结果解析是否失败？返回结构是否与解析代码匹配？是否被异常吞掉？

**解析没有失败，映射也没有失败 —— 是「异常被吞掉」让 0 条失去了原因。**

- 解析代码与真实结构**逐字段匹配**：`BiliParse.parseSearchTracks`（`BiliModels.kt:314-337`）
  读 `data.result[]`、跳过 `type != "video"`、取 `bvid`/`aid`/`title`/`author`/`pic`/`duration`；
  这与 host 实测拿到的 20 条真实响应同形（父任务事实 1），也与 v3.1.0 探针的 10 条同形。
- 空串进 `parseSearchTracks` 必然是 0 条：`JSONObject("")` 在 `runCatching` 里失败（`BiliModels.kt:315`）。
  也就是说**「接口没返回」与「解析失败」在返回值上不可区分**（都是空表）。
- 吞异常有两层：`BiliApi.kt:165`（`wbiKeys`，吞掉的是 HTTP 异常）与 `:302-306`（`searchVideos`，吞掉一切 `Exception`）。
  两层都**没有区分 `CancellationException`**，这对第 3 步引入的挂起点是新的风险（§6 修复里一并处理）。
- 结论：`B站 0 首` 的**权威原因**无法从返回值得到，只能从源码链路得到（§1）。

### C5. 搜索结果映射到 `TrackKey` 是否正确？

**正确，且 `id` 在三个音源之间结构性唯一（LazyColumn 的 key 不会撞）。**

- `BiliTrack.toSongItem()`（`BiliModels.kt:95-110`）：`id = numericId`、
  `source = "bilibili"`、`sourceId = "bv:<bvid>:<cid>"`、`mediaId = null`
- `numericId = SourceIds.biliId(auid ?: aid ?: 0)`（`BiliModels.kt:73`）= `1L shl 61 or rawAuid/aid`
  （`MusicSource.kt:221`、`:236-238`）；视频搜索结果没有 auid ⇒ 用 `aid`
- `trackKey = SourceIds.trackKey(musicSource, id)` = `"bilibili:<id>"`（`SongSourceExt.kt:35-36`、`MusicSource.kt:121`）
- `SongItem.musicSource` 读的是 `source` 字符串（`SongSourceExt.kt:20-21`），而 `toSongItem` 写死了
  `MusicSource.BILIBILI.key` ⇒ 音源不会回落成网易云
- **key 唯一性**：三个音源的值域互不重叠（网易云裸 songId < 2^40；QQ = 位 62；B站 = 位 61）
  ⇒ `itemsIndexed(visibleSongs, key = { _, item -> item.id })`（`SearchScreen.kt:559`）
  不会抛 `IllegalArgumentException: Key was already used`；
  `publish()` 的 `distinctBy { it.trackKey }`（`SearchViewModel.kt:175`）只去掉 (source,id) 完全相同的行。
- 本次新增设备侧断言（真实响应）：`PROBE-BILI320-KEYS idDistinct=…/… trackKeyDistinct=…/…`（§5）

### C6. 是否被 v2.4.0 跨源匹配误过滤？

**不是。搜索路径根本不经过跨源匹配。**

- 跨源匹配的唯一实现是 `crosssource/CatalogAggregator`，使用点是专辑/歌手详情
  （`AlbumDetailScreen.kt:31-32`、`:157`）；AGENTS.md 明确它在 v3.1.0 的 5 处 B 站分支一律返回空列表。
- 搜索路径只做两件事：`SearchRanking.order(netease, qq, bili, …)`（`SearchViewModel.kt:149-175`）
  与 `distinctBy { it.trackKey }`（`:175`）。三源重载把 B 站**追加在最后**（`SearchRanking.kt:217-229`），
  不做任何跨源配对、不做任何「同曲去重」。
- 因此「B 站结果被跨源匹配吃掉」这条**被证伪**。

### C7. 结果数为 0 是「接口没返回」还是「返回了被丢弃」？

**两者都不是：请求从未发出（HTTP 从未发生）。**

- 判据链见 §1 步骤 5-9；`BiliApi.kt:300` 打出的 `正文长度=0` 就是「一个字节都没收到」。
- 也**不是**被 `distinctBy`/排序丢掉：那一步的输入本来就是空表。
- 反向对照（同一份代码、只换线程）见 §5 `PROBE-BILI320-SEARCH on=main … / on=io …`：
  **IO 线程上有条数** ⇒ 接口、签名、解析、映射全好。
- 附带结论（可观测性缺陷，本次不扩大修复面）：返回值无法区分
  「密钥拿不到 / 网络异常 / 真的 0 条」，唯一的线索是 `Log.w`/`Log.i` 三行
  （`BiliApi.kt:168`、`:300`、`SearchViewModel.kt:424-431`）。

### C8. 匿名 vs 登录状态下搜索结果是否不同？

**本 App 内不存在这个变量**：B 站音源在本版**没有登录态**（`BiliSourceProvider.kt:59`
`isLoggedIn = false` 恒），请求不带任何 Cookie（`BiliApi.kt:187-189`；只有旧版 `playurl` 带
匿名指纹 `buvid3`，那不是账号态）。

- 匿名可用性由 v3.1.0 实测覆盖（`PROBE-BILI-SEARCH n=10` 无 Cookie）。
- 设备侧的 `PROBE-BILI320-SEARCH` 同样是匿名 —— 所以它证明的是「匿名也能搜到」，与 P0-C 无关。
- **未验证**：带登录 Cookie 的差异（本版明确不做 B 站登录，见 AGENTS.md「本版明确不做」）。

---

## 3. P0-D 九问（逐条）

### D1. 「只搜 B 站」筛选器触发什么逻辑？

**纯本地筛选：一个 `mutableStateOf` 赋值 + 一次保序 `filter`，不发请求、不取消任何协程。**

- 点击：`SearchScreen.kt:518-520` `Modifier.clickable { sourceFilter = filter }`
- 重算：`SearchScreen.kt:128-130` `visibleSongs = remember(songs, sourceFilter) { sourceFilter.filter(songs) { it.musicSource } }`
- 语义：`SourceFilter.filter`（`SourceFilter.kt:62-63`）`if (this == ALL) items else items.filter { accepts(sourceOf(it)) }`；
  `accepts` 见 `:54-59`
- 档位集合：`SourceFilter.visible(biliEnabled)`（`:78-80`）—— B 站档只在音源启用时出现（`:66`）

### D2. 筛选器切换后其他音源的请求是否被取消？

**不取消，设计如此。** `searchJob?.cancel()` 只出现在 `onQueryChanged`（`SearchViewModel.kt:110`）
与 `onTypeChanged`（`:124`）。筛选完全不碰 ViewModel —— `SourceFilter.kt:27-34` 写明了理由
（重新请求会清掉另外两源已经拿到的结果；用户切筛选不该产生新流量）。

### D3. B 站请求发起后 UI 状态机处于什么状态？

| 时刻 | `isLoading` | `sourceCounts.biliStatus` | 统计行第三段 |
|---|---|---|---|
| 请求发出后、首次 publish 前 | `true`（`:179`） | 未写入（`_sourceCounts` 还是上一轮 / null） | 不显示 |
| 首次 publish（任一源先回，`:343-368`） | `false`（`:366`） | `PENDING`（`:362-363` + `biliStatusOf` `:502-508`） | `B站 搜索中…`（`SourceCounts.kt:131`） |
| 第二次 publish（`:407-416`） | `false` | `DONE` / `TIMEOUT` / `ERROR` / `SKIPPED` | `B站 N 首` / `搜索超时` / `搜索失败` |

**状态机本身是对的**（B 站与 QQ 逐条同构，`biliStatusOf` `:502-508` ↔ `qqStatusOf` `:490-496`），
问题在**渲染位置**：统计行是 `LazyColumn` 的一个 item（`SearchScreen.kt:527`），
而筛选后 `visibleSongs` 为空时整条列表不挂载（`:478-491`）⇒ 「B站 搜索中…」在用户最需要它的那一刻消失。

### D4. 为什么没有加载动画？（`SourceSearchStatus` 是否覆盖 B 站？PENDING 是否正确渲染？）

**覆盖了、也渲染对了，但渲染在一个会消失的位置上；另外 `isLoading` 与 B 站那条腿无关。**

1. `SourceSearchStatus` **确实覆盖 B 站**：`biliStatus`（`SourceCounts.kt:77-78`）、
   `biliText`（`:127`）、`sideText` 的 `PENDING -> strings.searchSourcePending`（`:129-136`）；
   `hasPending` 也把它算进去（`:82-85`）。
2. PENDING 的文案是「搜索中…」（`zh_CN.kt:491`），渲染在统计行里（`SearchScreen.kt:534`、`:548-554`）。
3. **但它会消失**（D3）。
4. 结果区**没有任何加载态**：唯一的进度指示器是搜索框里那个 24dp 的
   `MetroProgressIndicator`（`SearchScreen.kt:241`），由 `isLoading` 驱动；
   而 `isLoading` 在**第一次 publish** 就被置 false（`SearchViewModel.kt:366`）——
   B 站那条腿此时可能还在飞（预算 4s，`:106`）。
5. 结论：把「B 站还在飞」画出来的正确做法是 **PENDING 渲染在永远存在的位置**（本次修复方向），
   以及**加载中也能切筛选**。

### D5. 为什么没有下拉刷新？（筛选器切换后是否刷新？刷新逻辑是否被跳过？）

**仓库有下拉刷新组件，但搜索页从来没接过 —— 不是「刷新被跳过」。**

探测结果（按要求 grep）：

| 检索 | 命中 |
|---|---|
| `PullRefresh` / `pullRefresh` / `SwipeRefresh` / `Modifier.nestedScroll` | `ui/components/PullToRefreshBox.kt`（`Modifier.pullToRefresh` `:71`、`PullToRefreshIndicator`；`nestedScroll` `:75`）、`playlist/PullToRefresh.kt`（纯阈值逻辑，`:27`）、`ui/components/DetailScaffold.kt:82` |
| 唯一使用点 | `ui/screen/LocalPlaylistDetailScreen.kt:121`（`rememberPullToRefreshState`）、`:206`（`contentModifier = Modifier.pullToRefresh(...)`） |
| 搜索页是否使用 | **没有**（`SearchScreen.kt` 全文无 `pullToRefresh`） |

- 组件的语义（`PullToRefreshBox.kt:56-70`）：位移 ≥ `PullToRefresh.THRESHOLD_PX`（160px）才触发、
  只在列表顶部累计、一个像素都不消费（不抢滚动）。
- 筛选切换**不刷新**、也**不该**刷新（纯本地筛选，`SourceFilter.kt:27-34`）。
- 既有替代入口：统计行在 `counts.qqUnavailable` 时给一条可点的「· 重试」
  （`SearchScreen.kt:535-546`）—— **但 B 站不在这条判据里**（`SourceCounts.kt:88-89` 只看 QQ）。

### D6. 卡死是「请求未返回」还是「UI 未更新」？

**主因是 UI 未更新（结构性死胡同），并且叠加了一个「主线程可能被阻塞」的风险源。**

- UI 未更新（**100% 静态可证**）：筛选档与统计行都在 `LazyColumn` 内，`visibleSongs` 为空 ⇒
  列表不挂载（`SearchScreen.kt:478-491`）⇒ 筛选档消失、没有回头路。用户的「卡死」在字面上
  就是「点了筛选档之后它不见了、页面也不动了（因为没有可点的东西）」。
- 请求侧：B 站那条腿在主线程上发起（§1），若平台未立即抛异常（例如某些 ROM 的策略差异），
  主线程会被 30s 级 read timeout 冻住 ⇒ 重组/动画/触摸全停 = 真·卡死。
  **本次设备侧的判据见 §5 `PROBE-BILI320-SOCKET`**：它直接回答「这台设备上主线程能不能做 socket IO」。
- 两者**不是互斥的**：P0-C 让 B 站恒 0 条，P0-D 让用户在 0 条时无路可退 —— 修一个不修另一个，
  用户仍然会看到同一个现象。

### D7. 能否退出搜索界面？

- **静态结论：能。** 被替换的子树只有结果区（`SearchScreen.kt:266-746` 的 `Crossfade`）；
  搜索输入框（`:206-256`，含清空按钮 `:245`）与底部导航（`MainScreen`）都在它之外，
  清空输入框即可回到历史/空态，切 tab 也不受影响。
- **但「退出筛选档」不能** —— 这正是用户报告的那一层：筛选档本身没有出口。
- **未在设备上做 UI 自动化复核**（v3.1.0 已实测 `uiautomator` 定位不到底部导航「搜索」入口，
  记录在 `docs/verification/v3.1.0/verification/ui-automation-attempt/`）。

### D8. 是否与 v3.1.0 的并行请求编排有关？

**有关，是间接但必然的：v3.1.0 把 B 站作为第三个 `async` 加进了同一个 `Main.immediate` 作用域。**

- `coroutineScope { … }`（`SearchViewModel.kt:221`）继承 `viewModelScope` 的 `Dispatchers.Main.immediate`
  （`:111`、`:125`）
- 三个 `async`（`:225`、`:244`、`:267`）都在这个作用域里；`async` 的 `start = DEFAULT` +
  `Main.immediate` ⇒ **调用点内联执行**，于是 `BiliApi.execute()` 在主线程上跑
- `withTimeoutOrNull(BILI_SEARCH_BUDGET_MS)`（`:269`）**救不了**：它的超时任务排在同一个
  （被阻塞的）主线程 `Handler` 上，超时回调要等阻塞返回才能执行；即使超时先触发，
  也无法中断一个已经在 `execute()` 里阻塞的线程
- 「先到先发布」的编排（`:284-368`）本身与 P0-D 无关：它只影响 `isLoading` 何时变 false

### D9. 并发上限是否导致 B 站请求被排队？

**不是。搜索路径没有使用并发上限原语。**

- `BoundedParallel`（硬上限 4）的使用者只有：`ConnectionWarmup.kt:99`、
  `PlayerViewModel.kt:1570`（`startAuxiliaryLoad`）、`PlayerViewModel.kt:1696`（`preloadNextSong`）、
  `ListPrefetch.kt:154`
- `SearchViewModel` 里三个腿是**裸 `async`**，没有信号量、没有队列（`grep BoundedParallel` 在
  `SearchViewModel.kt`/`SearchViewScreen` 里 0 命中）
- 所以 B 站是**与另外两条腿同时发出**的，不存在「被排队等到超时」。

---

## 4. 任务书要求：`BiliApi` 对外方法 × 调用点 × dispatcher 全矩阵

`BiliApi` 的**每一个**网络方法都是同步阻塞的（`client.newCall(request).execute()`）：
`get`（`BiliApi.kt:191`）、`post`（`:206`）。下表逐个登记「谁在哪个 dispatcher 上调它」：

| `BiliApi` 方法 | 调用点（文件:行） | 该调用点的 dispatcher | 是否会落在 Main |
|---|---|---|---|
| `searchVideos` `:289` | `BiliSourceProvider.searchSongs:77` | `SearchViewModel:267` 的 `async` ← `Main.immediate` | ❌ **是（P0-C 主犯）** |
| `audioInfo` `:339` | `BiliSourceProvider.searchSongs:74`（au 关键词） | 同上 | ❌ **是** |
| `audioLyric` `:275` | `BiliSourceProvider.fetchLyric:162` | `PlayerViewModel.fetchLyrics:1981` ← `startAuxiliaryLoad:1566` 的 `viewModelScope.launch`（**无 dispatcher** = `Main.immediate`）；另 `:660` / `:718` / `:748` / `:1770` / `:1838` / `:2352` / `:2375` 同形 | ❌ **是（播放路径同一个 P0）** |
| `videoCid` `:312` | `BiliSourceProvider.resolveUrl:109` | ① `PlayerViewModel.playJob:1238` = `Dispatchers.IO` ② `preloadJob:1666` = `Dispatchers.IO` ③ `PlaybackService.resolveMediaItem:874` ← `scope.launch` `PlaybackService.kt:815`，`scope = CoroutineScope(Dispatchers.Main + SupervisorJob())` `PlaybackService.kt:114` | ⚠️ ① ② 安全；**③ 是**（车机点播路径） |
| `videoAudioStream` `:324` | `BiliSourceProvider.resolveUrl:114` | 同上 | ⚠️ 同上 |
| `audioStream` `:358` | `BiliSourceProvider.resolveUrl:101` | 同上 | ⚠️ 同上 |
| `audioInfo` `:339` | `BiliSourceProvider.songDetail:130` | ① `playJob`/`preloadJob` = IO ✅ ② `ListPrefetch.detailOf:171` ← `prefetchBlocking` ← `scope`（`ListPrefetch.kt:74` = `SupervisorJob + Dispatchers.IO`）✅ | ✅ 现状安全（但不是结构性的） |
| `probeReachable` `:417` | 仅诊断（`androidTest`） | — | — |

**这张表就是「为什么必须在 `BiliApi` 内部换线程」的答案**：调用点分散在 3 个 ViewModel/Service、
9 处以上，且**今天有 2 处已经在 Main 上**（搜索、歌词），第 3 处（车机）也在 Main 上。
在调用点逐个包 `withContext(IO)` 是「改 9 处、漏 1 处就复发」的形状；
把保证放进 `BiliApi` 内部则**结构上只有一处**（§6）。

---

## 5. 设备侧取证

### 5.1 真机 S6（`0715f763f54c023a`，SM-G9209 / Android 7.0 / 已装 release `3.1.0-gpl` vc54）

| 尝试 | 结果 |
|---|---|
| `adb logcat -d` 过滤 App 自身 tag | **取不到**。按 pid（`8569`）过滤 `logcat -d -v time` 得到 **0 行**；main 缓冲区只用了 105KB，属于 ROM/`logd` 对三方 App 日志的过滤。时间窗内只有 `MediaSessionRecord` / `vol.MediaSessions` 等系统侧记录 |
| `run-as com.takahashirinta.ncrust` | 失败：`Package 'com.takahashirinta.ncrust' is not debuggable`（release 包，符合预期；未安装任何包，登录态未受影响） |
| 因此「真机 logcat 里那条 `NetworkOnMainThreadException`」 | **未取到**（如实记录）。机制判据改由 §5.2 的平台级探针 + §1 的源码链路给出 |

### 5.2 模拟器（`emulator-5554`，API 33 / google_apis / x86_64，本次新建 AVD `ncrust33`）

探针：`BiliV320ProbeTest`（新增文件，未改 `BiliV310ProbeTest`）。运行方式：

```
ANDROID_SERIAL=emulator-5554 ./gw.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.takahashirinta.ncrust.probe.BiliV320ProbeTest
```

运行结果（**实测**，`BUILD SUCCESSFUL`，6 个用例全过）：

```
PROBE-BILI320-SOCKET  on=main connected=false elapsedMs=0    error=android.os.NetworkOnMainThreadException: null
PROBE-BILI320-SOCKET  on=io   connected=true  elapsedMs=502  error=null

PROBE-BILI320-RAWSYNC on=main bodyLen=-1  elapsedMs=8    error=android.os.NetworkOnMainThreadException: null
PROBE-BILI320-RAWSYNC on=io   bodyLen=249 elapsedMs=1527 error=null

PROBE-BILI320-SEARCH  on=main n=5 elapsedMs=396 error=null
PROBE-BILI320-SEARCH  on=io   n=5 elapsedMs=414 error=null

PROBE-BILI320-AUDIO   on=main n=1 elapsedMs=594 error=null
PROBE-BILI320-AUDIO   on=io   n=1 elapsedMs=97  error=null

PROBE-BILI320-KEYS    n=20
PROBE-BILI320-KEYS    item id=2305958725514433579 source=bilibili trackKey=bilibili:2305958725514433579 sourceId=bv:BV1QgmiBMErs:0
PROBE-BILI320-KEYS    idDistinct=20/20 trackKeyDistinct=20/20

PROBE-BILI320-TOGGLE  off=true  n=0 elapsedMs=1
PROBE-BILI320-TOGGLE  off=false n=5 elapsedMs=1506
```

同一次运行里的 App 自身日志（`logcat-*.txt`，**没有任何 `search failed` / `nav 没有返回 wbi_img`**）：

```
I BiliApi : wbi keys refreshed: mixin=ea1db124…
I BiliApi : search 'miku' -> 20 条（正文长度=28765）
I BiliApi : search 'miku' -> 5 条（正文长度=7364）
I BiliApi : search 'miku' -> 5 条（正文长度=7363）
I BiliApi : search 'miku' -> 5 条（正文长度=7363）
```

**判决**：

| 主张 | 判据 | 判决 |
|---|---|---|
| 本设备/本 targetSdk 下，**主线程不能做 socket IO** | `on=main connected=false elapsedMs=1 error=android.os.NetworkOnMainThreadException` vs `on=io connected=true` | ✅ **实测**（**1 毫秒**被平台拒绝，不是「卡 30 秒」）|
| **v3.1.0 `BiliApi.get()` 的调用形状**在主线程上的表现 | `PROBE-BILI320-RAWSYNC on=main bodyLen=-1 elapsedMs=8 error=NetworkOnMainThreadException` vs `on=io bodyLen=249` | ✅ **实测**（同一个 OkHttp、同一个 URL/UA/Referer/超时口径：主线程 **8ms** 无正文，IO 线程拿到 249 字节）|
| 修复后，**从主线程发起**的 B 站搜索能拿到条数 | `PROBE-BILI320-SEARCH on=main n=5`（与 `on=io n=5` 一致） | ✅ 实测（阻塞段已在 IO 上）|
| 修复后，音频区那条腿同样成立 | `PROBE-BILI320-AUDIO on=main n=1` | ✅ 实测 |
| 真实响应的 `id` / `trackKey` 唯一（LazyColumn key 不会撞） | `idDistinct=20/20 trackKeyDistinct=20/20` | ✅ 实测（20 条真实结果）|
| 关掉开关时一个请求都不发 | `off=true n=0 elapsedMs=1` vs `off=false n=5 elapsedMs=1506` | ✅ 实测（铁律 24 未被调度修复动摇）|

**口径（必须一起读）**：

1. 探针跑在**本次新建的 API 33 模拟器**（`emulator-5554` / google_apis / x86_64）上，
   不是真机 —— 但 `NetworkOnMainThreadException` 是**平台政策**（`targetSdk >= 11` 恒成立），
   与设备型号无关；真机 S6 上的直接 logcat 取证**未取到**（§5.1）。
2. 探针的构建来自**仓库外沙箱**（`git archive HEAD` + 本 P0 的 patch + 为编译借入的
   3 个 P1 新文件 `BiliAuthStore/BiliAuthApi/BiliQrLogin`）。理由见 §8 的事故记录：
   共享工作区在本轮被并行 agent 的 `git stash` + 硬重置清空过一次，
   且随后 i18n / 测试源集在别的 agent 手里处于编辑中。**被测的生产代码与共享工作区里的一致**
   （同一份 patch 生成，sha 由 §8 记录）。
3. **「修复前的 v3.1.0 原代码」没有在设备上直接跑过** —— 它的构建已被覆盖。所以
   「修复前会怎样」由**两条实测 + 一条静态链路**共同给出：
   ① 平台事实（上表 SOCKET 行，实测）；② 同形状 OkHttp 调用在主线程上的表现（RAWSYNC 行，实测）；
   ③ §1 的源码链路（静态，带行号：主线程 → `execute()` → `runCatching` 吞掉 → 0 条）。
   三者合起来足以判定机制，但本文件**不声称**「旧构建的实测数字」。

### 5.3 静态 vs 实测的边界（本节的诚实说明）

- 「主线程上 `BiliApi.execute()` 必然拿不到字节」这条在 §5.2 用**两个互补的判据**验证：
  ① 平台级裸 socket（`PROBE-BILI320-SOCKET`）；② 生产代码在主线程 vs IO 线程的 A/B
  （`PROBE-BILI320-SEARCH` / `PROBE-BILI320-AUDIO`）。
- 「v3.1.0 的原代码在真机上」这一条**没有**直接取到 logcat 证据（§5.1）——
  报告里不以「真机已验证」的口径陈述它。

---

## 6. 根因清单与修复方向

| # | P0 | 根因 | 类别 | 修复 |
|---|---|---|---|---|
| 1 | C | `BiliApi` 的同步阻塞网络方法被 `Main.immediate` 作用域调用（`SearchViewModel.kt:267`、`PlayerViewModel.kt:1566`、`PlaybackService.kt:114`） | 调度 | `BiliApi` 的 7 个对外网络方法改 `suspend` + 内部 `withContext(Dispatchers.IO)`（**唯一**保证点） |
| 2 | C | 异常的**静默吞掉**：`wbiKeys` 的 `runCatching`（`BiliApi.kt:165`）与 `searchVideos` 的 `catch (Exception)`（`:302`）把「网络/主线程异常」与「0 条」折叠 | 可观测性 | 保持「绝不抛」契约，但①`wbiKeys` 的 nav 失败改为显式 `Log.w` ②区分 `CancellationException` |
| 3 | C | `SourceRouter.searchSongs`（`SourceRouter.kt:114`）的 `runCatching` 也会吞掉取消 —— 加了真挂起点之后它才会显形 | 取消语义 | 三行改成 `try/catch`：取消原样抛出，其余记日志回空表（契约与有界性不变） |
| 4 | D | 筛选档渲染在 `LazyColumn` 内 + `visibleSongs.isEmpty()` 时整条列表被替换 ⇒ 无出口 | UI 结构 | 筛选档与统计行**移出列表**（常驻）；可见性判据抽成纯函数 `SourceFilter.shouldShowFilterRow(...)` + 单测 |
| 5 | D | 空态文案不分「本次搜索无结果」与「当前筛选下无结果」（`emptyHint` 只存在于 KDoc） | i18n | 新增纯判据 `SourceFilter.emptyKind(...)`；文案先用两个既有字符串拼（`emptyText`），新 key 需求见 §7.2 |
| 6 | D | B 站 TIMEOUT/ERROR 没有重试入口（`qqUnavailable` 只看 QQ） | 有界失败处理 | 新增 `SourceCounts.biliUnavailable` / `anyUnavailable` + 复用既有 `strings.retry` 入口 |
| 7 | D | 结果区没有加载态；`isLoading` 在首次 publish 就变 false | UI 状态机 | 复用既有 `MetroProgressIndicator`（不新建组件）渲染「还有源在飞」（`isLoading \|\| hasPending`） |
| 8 | D | 搜索页没有下拉刷新（仓库**有**组件 `PullToRefreshBox`，唯一使用点是歌单详情） | UI 能力 | 复用既有 `Modifier.pullToRefresh` + `PullToRefreshIndicator`（不新造），`onRefresh` 走既有 `viewModel.onQueryChanged(query)` |

**修复面之外（明确不做）**：B 站登录、B 站音频区作为发现源、跨源匹配含 B 站、
翻页、把 `BiliApi` 改成 OkHttp 异步（`enqueue`）。

### 6.1 一处**没有**修的取舍（如实写）

`withTimeoutOrNull(BILI_SEARCH_BUDGET_MS)` 的语义仍然是「**到点丢弃结果**」，不是「掐断请求」：
`OkHttp.execute()` 阻塞在 socket read 上时不会被协程取消打断（取消是协作式的，只在挂起点生效），
所以预算到点后那条请求仍会跑完、只是结果被丢掉。要真正掐断得改成 `enqueue` + `Call.cancel()`
再加一个 `suspendCancellableCoroutine` 桥 —— 那是 L 级重构，超出本 P0 的面。
单测把这条语义钉住了：假传输层 sleep 400ms + 预算 60ms ⇒ 返回 `null`（TIMEOUT），但耗时仍 ~400ms
（`BiliApiDispatchTest.预算用完时返回 null 而不是空列表`）。

---

## 7. 未验证 / 未做（如实）

### 7.1 未验证

1. **真机 S6 的 logcat 取证未取到**（§5.1）：ROM 过滤三方 App 日志，按 pid 过滤 `logcat -d` 为 **0 行**。
   因此「v3.1.0 的原代码在**这台真机**上抛的异常」没有直接日志；机制判据来自
   ①§5.2 的平台级裸 socket 探针（实测 `NetworkOnMainThreadException`，**1ms**）
   ②§5.2 的同形状 OkHttp 探针（实测，**8ms**）③§1 的源码链路（静态，带行号）④修复后的主/IO A/B。
2. **「修复前的 v3.1.0 构建」没有在设备上直接跑过**（构建已被并行 agent 的重置覆盖，见 §8）——
   §5.2 的 RAWSYNC 行是**同形状**而非同一份旧代码。
3. **B 站 CDN 取流（ExoPlayer 播字节）仍未验证**：v3.1.0 的结论（出口级风控 403）本版**没有推翻**，
   也不在本 P0 的修复面内。
4. **App 内是否会被 B 站风控（412 / -352）**：调度修好之前 App 内从未真正发出过请求，
   这个问题的第一次观测要等本版之后的真机使用 —— 模拟器上这次拿到了 `HTTP 200 / code:0`
   （`正文长度=28765`，20 条），但那**只代表这次出口**。
5. **超时预算的语义仍是「丢弃结果」而不是「掐断请求」**（§6.1）：`execute()` 阻塞在 read 上时
   协程取消不会打断它（取消是协作式的）。单测把语义钉住了（返回 `null` = TIMEOUT），
   但耗时不会被压到预算以内。
6. **筛选档 / 空态 / 下拉刷新的「实际画出来什么样」没有自动化证据**：本仓库没有 Robolectric，
   判据全部抽成纯函数 + JVM 单测；Compose UI 层只做了编译期检查与人工阅读。
   **连一次真机/模拟器上的手点复核都没有做**（v3.1.0 已证实 `uiautomator` 定位不到底部导航
   「搜索」入口，记录在 `docs/verification/v3.1.0/verification/ui-automation-attempt/`；本版没有重试）。
7. **三源并发在弱网下的耗时分布未采集**：搜索路径不使用 `BoundedParallel`（§3-D9），
   本版只证明「B 站不再阻塞主线程」，没有采集「三源同时发出」的端到端延迟。
8. **`SourceCounts.anyUnavailable` 的 UI 效果未在设备上看过**（它是纯逻辑，有单测）。

### 7.2 未做（有意）

- **不新建下拉刷新组件**：仓库**已有** `ui/components/PullToRefreshBox.kt`（v2.3.0 · B），
  本版**复用**它接进搜索页（此前唯一使用点是歌单详情页）。同理加载态复用既有 `MetroProgressIndicator`。
- **不动 `ui/i18n/**`**（编排者独占）⇒ 筛选为空的新文案用**两个既有字符串**拼
  （`SourceFilter.emptyText`，见 §7.3），**没有任何裸中文字面量**。
- **不做** B 站登录 / FLAC / 翻页 / 视频画面 / 跨源匹配含 B 站 —— v3.1.0 的「明确不做」清单一个字没改。
- **不把 `BiliApi` 改成 OkHttp 异步**（`enqueue` + `Call.cancel()`）：那是 L 级重构，
  会让 7 个方法的返回语义、超时口径与单测全部重写。

### 7.3 需要编排者补的 i18n key（**1 个**，可选）

| 项 | 内容 |
|---|---|
| key | `searchFilterEmpty: (String) -> String`（收「当前档位名」，返回整句） |
| 语义 | 「当前筛选（X）下没有结果」——与 `searchSongsEmpty`（本次搜索没有结果）是两句不同的话 |
| 当前占位 | `SourceFilter.emptyText(strings)` = `label(strings) + " · " + strings.searchSourceCount(0)` ⇒ 中文下渲染为 `只看 B 站 · 0 首`（由两个**已本地化**的既有字符串拼成，8 语言都不漏中文，有单测） |
| 换法 | 补好 key 后 `SourceFilter.emptyText` 改一行：`strings.searchFilterEmpty(label(strings))` |
| ⚠️ 落位约束 | 主构造器参数槽**已满**（`StringsConstructorBudgetTest` 会红）⇒ 必须放 **`SourceStrings`**（当时 57/60，剩 3 个槽，本 key 只用 1 个），或走成员转发属性 |
| 8 语言建议文案 | zh-CN `{ f -> "当前筛选（$f）下没有结果" }` · zh-TW `{ f -> "目前篩選（$f）下沒有結果" }` · en `{ f -> "No results under the current filter ($f)" }` · ja-JP `{ f -> "現在の絞り込み（$f）では結果がありません" }` · ja-MY `{ f -> "今 絞（$f）に 果 無" }`（请按 jp_MY 的万叶假名风格定稿）· ko-KP `{ f -> "현재 필터($f)에 결과가 없습니다" }` · de-DE `{ f -> "Keine Ergebnisse für den aktuellen Filter ($f)" }` · ru-RU `{ f -> "Нет результатов по текущему фильтру ($f)" }` |
| 不补也可以 | 占位文案是**诚实**的（指名档位 + 如实 0 首），只是不如整句自然。**不补不会造成任何错误行为** |

---

## 8. 事故记录（共享工作区被回滚过一次）与沙箱口径

本轮是在**多 agent 共享同一个工作区**的前提下跑的，过程中发生过一次全量回滚，如实记录：

| 时刻 | 事件 |
|---|---|
| 18:37:22 | 某个并行 agent 执行了 `git stash push -m v3.2.0-wip-probe` + 硬重置 ⇒ **所有 tracked 改动**（本 P0 的 8 个文件 + 并行任务的 qq/ player-motion/ i18n/ settings/ PlayerViewModel 改动）进入 `stash@{0}`，工作区回到 HEAD；untracked 新文件（探针、文档、`ResolveFailure*.kt` 等）留在原地 |
| 18:37:56 | 本 P0 把整份 stash 备份到仓库外：`/home/duanjb666/deepseek/.scratch/v320/stash0-full-183756.patch`（230KB），并**只**从 stash 恢复自己那 8 个文件（未 pop、未碰别人的文件），同时把本 P0 的改动另存 `P0CD-work-183805.patch` |
| 18:42 | 编排者裁决：P0-B 已全量 apply + 3-way merge 恢复并 drop 了 stash；随后定下**本轮禁止 `git stash` / `git reset --hard` / `git checkout -- .` / `git clean`** |
| 18:38 起 | 本 P0 的编译/单测在**仓库外沙箱**（`/home/duanjb666/deepseek/.scratch/v320/sandbox`）进行：`git archive HEAD` + 本 P0 的 patch +（为编译借入的 P1 新文件）|

沙箱同步脚本（可复现）：`/home/duanjb666/deepseek/.scratch/v320/sync-sandbox.sh`。
它只带**白名单内**的本 P0 文件，兄弟任务的改动**不会**被带进沙箱（`SourceRouter.kt` 例外：
沙箱里用 Python 只替换 `searchSongs` 那一个函数，因为它在本 P0 的 patch 里会连带兄弟任务的
`resolveUrlOutcome`）。

**沙箱结论与共享工作区的关系**：§5.2 的探针与沙箱单测都跑在沙箱上，而被测的生产代码与共享工作区
**逐字节一致**（patch 由共享工作区生成）。编排者要求的「至少一次共享工作区实跑」见 §9。

---

## 9. 自检命令与输出摘要

| 命令 | 结果 |
|---|---|
| `./gw.sh :app:testDebugUnitTest --tests '*Bili*' --tests '*SourceFilter*' --tests '*SourceCounts*'`（**共享工作区**） | 见下方「共享工作区实跑」 |
| 同一命令（**沙箱**） | `BUILD SUCCESSFUL`；14 个测试类 / **108 用例 / 0 失败**（XML 逐类统计） |
| `ANDROID_SERIAL=emulator-5554 ./gw.sh :app:connectedDebugAndroidTest -P…class=…BiliV320ProbeTest`（**沙箱**） | `BUILD SUCCESSFUL`；`Starting 6 tests on emulator-5554` / `Finished 6 tests`；输出见 §5.2 |
| `./gw.sh :app:lintDebug` | 见交付报告 |

**共享工作区实跑**（编排者要求的那一次）：见本文件同目录的 `REPORT-P0CD.md` §6 ——
它记录的是共享工作区当时的真实输出，包括「被并行编辑阻塞」这种如实结果与最终结果。

