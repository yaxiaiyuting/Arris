# 请求编排现状（点歌 → 出声 / 取链 · 歌词 · 封面 · 详情）

> 采集时间：2026-09-28 02:24 CST ｜ 代码基线：a86d97b ｜ 采集方式：源码审计（无运行时数据）
> **⚠️ 行号口径（重要）**：本文所有行号均按声明的基线 **`a86d97b`** 核对。
> 采集期间工作区被**并行改动**（未提交）：`PlayerViewModel.kt` 在旧 `:334` 处插入了 4 行 `MusicSource.BILIBILI` 分支，
> 因此该文件在工作区里**整体下移 +4 行**（例：`CACHE_TTL_MS` 基线 `:419` → 工作区 `:423`；`playSong` `:1078` → `:1082`；
> `preloadNextSong` `:1540` → `:1544`；`fetchLyrics` `:1820` → `:1824`）。
> **引用 `PlayerViewModel.kt` 时：工作区行号 = 本文行号 + 4（仅该文件、且行号 > 338），或直接按符号名检索。**
> 其余被本文引用的文件（`PlaybackService.kt` / `RetrofitClient.kt` / `SongUrlFetcher.kt` / `PreloadSlot.kt` / `LyricsCache.kt` /
> `ContentCache.kt` / `HomeSnapshot.kt` / `AppWarmup.kt` / `QqClient.kt` / `MainActivity.kt` …）在采集期间**未被改动**，行号可直接定位。


本文只描述**现状**，不含建议。所有结论都指到 `文件:行号`；凡是代码不能直接证明的，一律显式标注「（推断）」。

---

## 1. 用户在列表里点一首歌，到「听到声音」之间经过哪几步

入口在 `MainScreen`（`MainActivity.kt`）里，列表项的点击回调把所有页面都接到同一个函数：

| # | 步骤 | 落点 |
|---|---|---|
| 1 | 列表项点击 → `onSongClick = { playSongItem(it) }` | `MainActivity.kt:2380`（另有 `:2409`、`:2463`、`:2538`，四个页面共用同一回调） |
| 2 | `playSongItem(song)`：队列为空则建单曲队列；否则去重后插到当前歌之后，再定位下标 | `MainActivity.kt:1566`（空队列分支 `:1567-1577`；去重/插入 `:1583-1598`；`playFromQueue(idx)` `:1599`） |
| 3 | `playFromQueue(idx)` → `playerViewModel.playSong(...)` | `MainActivity.kt:1599` → `MainActivity.kt:1194` → **`MainActivity.kt:1205`** |
| 4 | `PlayerViewModel.playSong` 主线程前置工作：版本号自增、`TrackKey` 落定、歌词协调器换曲、档位判定、续播位置读取 | `PlayerViewModel.kt:1078`（`:1097-1163`） |
| 5 | 查 `preloadCache` 快速路径（同档 + TTL 内即命中，直接进第 6′ 步） | `PlayerViewModel.kt:1167-1169` |
| 6 | 未命中 → 取消上一次 `playJob`，在 `Dispatchers.IO` 上起协程取链 | `PlayerViewModel.kt:1209-1210` |
| 7 | `fetchUrlOfflineFirst(ref, selectedQuality)`：先问系统有没有网，再按音源路由取链，失败回落离线清单 | `PlayerViewModel.kt:1215` → `:1326-1337` |
| 8 | 版本号校验（期间又点过歌就丢弃本次结果）→ 清预载槽位 → 清歌词 → **发起歌词请求** | `PlayerViewModel.kt:1231`、`:1233`、`:1234`、**`:1238`** |
| 9 | 切主线程写状态、构造 Intent、`startForegroundService` | `PlayerViewModel.kt:1239-1262` |
| 10 | `PlaybackService.onStartCommand`：`action` 为空 ⇒ 走起播分支，落盘状态后 `playUrl(url, pos)` | `PlaybackService.kt:604`、`:711-727` |
| 11 | `playUrl`：记离线 URL → 清槽位 → 建 MediaItem → `setMediaItem` / `prepare` / `playWhenReady = true` | `PlaybackService.kt:922-936`（`:926` 落盘、`:933-935`） |
| 12 | ExoPlayer 经缓存数据源拉流并解码出声 | `PlaybackService.kt:392`（`DefaultMediaSourceFactory(OfflineAudioCache.dataSourceFactory(this), extractorsFactory)`） |

**「听到声音」的关键一步在服务侧**：`playSong` 本身只负责把一个 URL 通过 Intent 交给 `PlaybackService`（`PlayerViewModel.kt:1246-1260`），真正 `prepare()` + `playWhenReady = true` 在 `PlaybackService.kt:934-935`。所以从点按到出声，跨了一次**进程内的 startForegroundService + onStartCommand**（`PlaybackService.kt:604`）。

**耗时的构成（推断）**：`fetchUrlOfflineFirst` 里 `NetworkAvailability.isOnline()`（`PlayerViewModel.kt:1329`）是纯系统查询；真正的耗时几乎全在 `SourceRouter.resolveUrl`（`:1336`）→ `SongUrlFetcher.fetch`（`SongUrlFetcher.kt:128`）这条网上往返上，且它是**串行降级**的（见 §4）。代码里**没有**任何取链耗时的埋点 —— 唯一的被动计时是 `HttpTimingListener`（`network/HttpTimingListener.kt:64-146`），它按请求打一行 `path/ttfb/body/total`，不聚合、不落盘。

---

## 2. URL / 歌词 / 封面 / 详情：串行还是并行

**结论：这四样在 `playSong` 里没有任何一处是「并发发起」的 —— 不存在 `async`/`awaitAll`。**

| 数据 | 谁发起 | 确切行 | 结构 | 相对取链的位置 |
|---|---|---|---|---|
| **URL（取链）** | `PlayerViewModel.playSong` | `PlayerViewModel.kt:1215` | `playJob = viewModelScope.launch(Dispatchers.IO) { ... }`（`:1210`），内部 `suspend` 调用，**无 `async`** | 自身；是后面所有步骤的**前置** |
| **歌词** | `PlayerViewModel.playSong` | 未命中路径 `PlayerViewModel.kt:1238`；`preloadCache` 命中路径 `:1201` | `viewModelScope.launch { fetchLyrics(track) }` —— **独立协程，但不早于取链完成** | **在取链结束之后**（见下） |
| **封面** | 没有人发起 | `PlayerViewModel.kt:1183`（命中路径）/ `:1244`（未命中路径）只是 `currentSongArtwork.value = artworkUrl` | 纯赋值，**零网络请求** | 与取链无关 |
| **详情（`songDetail`）** | **不在播放路径上** | 见 §2.3 | — | — |

### 2.1 歌词请求是不是在取链完成之后才发起？——**是（成立）**

未命中路径的原始顺序（`PlayerViewModel.kt:1210-1238`）：

```
1210  playJob = viewModelScope.launch(Dispatchers.IO) {
1215      var result = fetchUrlOfflineFirst(ref, selectedQuality)   // ← 取链（挂起点）
...
1231      if (fetchVersion != songPlayVersion) return@launch
1233      clearPreloadedState()
1234      resetLyricsForNewSong()
1238      viewModelScope.launch { fetchLyrics(track) }              // ← 歌词在这里才发出
```

`:1238` 位于 `:1215` 之后的同一协程体内，中间没有任何「提前 launch」的分支。`fetchLyrics` 的全部调用点如下（`grep` 全量，共 9 处）：

| 行 | 所属 | 与取链的关系 |
|---|---|---|
| `:650` | `init` 里注册的 `PlaybackService.onSongTransitioned` 回调（自动接续） | 不经过取链（用的是预载槽位里已有的 URL） |
| `:708` | `init` 里的冷启动状态恢复分支 | 不经过取链 |
| `:738` | `adoptTrackIdentity`（队列身份补齐后重发） | 不经过取链 |
| `:1201` | `playSong` 的 `preloadCache` 命中路径 | 无取链（URL 已在内存） |
| **`:1238`** | **`playSong` 的未命中路径** | **在 `:1215` 取链之后** |
| `:1658` | `preloadNextSong` 的「预载接管」路径 | 用的是预载已取回的 URL |
| `:1726` | `fetchLyricsForSong`（`MainActivity.kt:1500` 在切歌后调用） | 不经过取链 |
| `:2184` | `retryLyrics()` | 不经过取链 |
| `:2207` | `prepareSongWithoutPlay(...)` | 不经过取链 |

因此，就**用户点按一首未预载的歌**这条路径而言：**取链没有返回之前，歌词请求一次都不会发出。**（其余 8 个调用点都不会「重开一次取链」，所以不构成反例。预载过的歌会走 `:1201` / `:650`，那里本来就是零取链。）

缓存命中路径同理：`:1201` 在 `startForegroundService`（`:1197`）之后 —— 那条路径上一个网络字节都不发。

**一处需要如实说明的次序细节**：`:1238` 的 `launch` 与 `:1239` 的 `withContext(Dispatchers.Main)` 都在 IO 线程上被调用，两者都会被投递到主线程队列；`launch` 在前，所以「歌词协程开始跑」**先于**「起播 Intent 送达服务」入队（推断，依据是两行代码的书写顺序与 Main.immediate 的投递语义）。这不改变上面的结论 —— 它是「取链之后」，不是「取链之中」。

**为什么值得记一笔**：`PlayerViewModel.kt:1235-1237` 的注释明确写了这里**曾经**是顺序等待歌词（"旧实现在这里顺序等待(失败退避最坏 3s+), 开播被歌词请求拖住"）。也就是说，当前实现的语义是「取链 → 立刻起播，歌词并发去取、不阻塞开播」，但**歌词请求的发起时刻仍然被取链串行挡住** —— 取链慢（最坏 6 档 × 超时，见 §4）时，歌词要等取链结束才开始飞。

### 2.2 封面来自哪里

- **列表项已有的 `artworkUrl`，无需额外请求**：`playSong` 的形参 `artworkUrl`（`PlayerViewModel.kt:1082`）由调用方从 `playbackQueue` 里的 `SongItem` 取出 —— `MainActivity.kt:1204` 的 `songParams(song)`（`playFromQueue` 内），再原样写进状态（`:1183` / `:1244`）与 Intent（`:1188` / `:1250`）。
- 真正的图片下载发生在 **Coil + 服务侧**：`PlaybackService` 收到 `artwork` 后调 `loadArtwork`（`PlaybackService.kt:700-702` → `:1191`），走 `Coil.imageLoader(...)`（`:1198`）、尺寸 1024×1024、URL 经 `CoverUrls.large`（`:1200`）。
- 列表里显示的缩略图走 `CoverUrls.small`（640px，`ui/components/SongCard.kt:111`、`:210`），播放器大图走 `CoverUrls.large`（1080px，`ui/player/PlayerCard.kt:1770`）。两者是**不同的 URL**（`CoverUrls.kt:16` / `:19`），因此列表封面命中不了大图的缓存 —— 这一条是**代码直读的结论**，不是推断。

### 2.3 详情（`songDetail`）在什么路径上被调用

全仓检索 `songDetail` / `getSongDetail` / `SONG_DETAIL_PATH` 只有四处：

| 位置 | 性质 | 是否在播放路径上 |
|---|---|---|
| `SongUrlFetcher.kt:67` `SONG_DETAIL_PATH = "/eapi/v3/song/detail"` + `:204-228 fetchSongMaxLevel` | 取链的**附带**请求（只问 `privileges[0].maxBrLevel`） | ✅ 在，但**只在条件命中时**才发（见 §4.2） |
| `ui/viewmodel/SongViewModel.kt:30-31` `getSongDetail` | 单曲信息页（`SongDetailScreen.kt:94-95` 触发），点击路径是长按菜单最后一项 `MainActivity.kt:2698-2701` | ❌ 不在播放路径上 |
| `source/NeteaseSourceProvider.kt:56-59` / `qq/QqMusicSourceProvider.kt:89` `songDetail` | Provider 契约实现 | ❌ **全仓零调用点**（`MusicSourceProvider.kt:73` 只有声明与实现，没有任何生产代码调用它）。`MusicSourceProvider.kt:31` 的契约表把它描述为「队列里只有 id 没有元数据时无法补齐」，但当前没有任何路径用到它 |

---

## 3. 两条路径的步数与耗时构成

### 3.1 `preloadCache` 命中路径（`PlayerViewModel.kt:1170-1207`）

**6 步**（口径：从点按算起、到 `playUrl` 为止的环节数；末尾的「出声」是 `playUrl` 的结果，不计入）：`onSongClick` → `playSongItem` → `playFromQueue`/`playSong` → 命中判定（`:1167-1169`）→ 主线程 Intent + `startForegroundService`（`:1184-1199`）→ `PlaybackService.playUrl`（`PlaybackService.kt:922-936`）→ 出声。

耗时构成（推断）：**无取链网络往返**；只剩下 SharedPreferences 读（`:1124` 设置、`:1150` 续播位置）、主线程状态写入、一次进程内服务启动、ExoPlayer `prepare()` 的解析与首段缓冲。注意即使 URL 命中，**音频字节不一定在本地** —— `OfflineAudioCache` 是 `SimpleCache`（`cache/OfflineAudioCache.kt:94-115`），只有播过的片段才在，未播过时 ExoPlayer 仍要联网拉（`PlaybackService.kt:392`）。URL 命中省掉的是「取链」这一次往返，不是「拉流」。

### 3.2 未命中路径（`PlayerViewModel.kt:1209-1273`）

**9 步**（同一口径）：点按 → `playSongItem` → `playFromQueue`/`playSong` → 起 IO 协程 → `fetchUrlOfflineFirst` → 版本校验/清槽位/清歌词 → **歌词 launch** → 主线程 Intent + `startForegroundService` → `playUrl` → 出声。

耗时构成（推断）：**几乎全部落在取链**。`NetworkAvailability.isOnline`（`:1329`）是瞬时系统查询；`SourceRouter.resolveUrl`（`:1336`）在 ncm 一侧就是 `SongUrlFetcher.fetch`，它是**逐档串行**的 eapi POST（`SongUrlFetcher.kt:142-191`），每一档都要等一次完整往返（成功即 `return`，失败才下一档），每档的客户端超时是 30s connect / 30s read（`network/RetrofitClient.kt:38-39`，见 `connection-layer.md`）。最坏情况（全部档位失败）在 `jymaster` 起手是 6 档，`dolby` 也是 6 档（`SongUrlFetcher.kt:131-132`）。

---

## 4. ncm「超清母带」是否需要多级取链

### 4.1 `fetch()` 的主体是「档位降级阶梯」，不是「多级取链」

`SongUrlFetcher.fetch`（`:128`）先由请求档位算出候选序列（`:130-140`）：

```
jymaster -> jymaster, hires, lossless, exhigh, higher, standard
dolby    -> dolby, hires, lossless, exhigh, higher, standard
...
```

然后 `for (tryLevel in fallbackLevels)`（`:142`）**逐档串行请求同一个端点** `SONG_URL_PATH = "/eapi/song/enhance/player/url/v1"`（`:66`、`:151`），每档一个 `RetrofitClient.eapiPost(..., useInterface = true, extraCookie = ClientIdentity.extraCookieFor(...))`（`:150-155`）。成功（`code == 200` 且 `url` 非空）立刻 `return`（`:171-186`），否则 `continue` 下一档。**同一首歌唱一次链的语义是「同一端点多档试探」，不是「多级取链」。**

### 4.2 真正会「多发一次」的是 `SONG_DETAIL_PATH`

`:176-180`：

```kotlin
// A3：只有"实际文件可能低于请求档位"时才多问一次该曲的档位上限 ——
val songMaxLevel =
    if (QualityAssessment.needsSongCapability(level, br, type)) fetchSongMaxLevel(songId)
    else null
```

`needsSongCapability(requested, br, type)`（`player/QualityAssessment.kt:113-122`）的判据是三条同时成立才返回 true：

1. `measuredLevel(br, type)` 能算出实测档位（`QualityAssessment.kt:92-107`：`mp4`→dolby；`flac` 按码率阈值→jymaster/hires/lossless；`mp3` 按码率→exhigh/higher/standard；否则 null）；
2. `measuredIdx < requestedIdx` —— **实际文件低于请求档位**（`:115`、`:117`）；
3. 不是「沉浸声换格式本来就不算降级」的豁免情形（`:120`：请求 `dolby`/`jyeffect` 等且实测 ≥ lossless 时跳过）。

命中时 `fetchSongMaxLevel(songId)`（`:204-228`）会**额外发一次** `RetrofitClient.eapiPost(SONG_DETAIL_PATH, mapOf("c" to [{"id": songId}]))`（`:207-212`，注意**这一条没有 `useInterface = true`**，走默认 `API_URL`），只取 `privileges[0].maxBrLevel`（`:214-217`），并把结果放进内存缓存 `songMaxLevelCache`（`:230`，超过 256 条整体清空 `:219-220`，所以每曲**每进程最多一次**）。

**这算不算多级取链？** 按代码语义：它**不是**取链的一级 —— 它拿不到任何播放 URL，只拿「这首歌的档位上限」，用途是把「账号无权限」与「该曲根本没这个档位」在 UI 上分开（`QualityAssessment.kt:124-159` 的 `assess` 消费 `songMaxLevel`）。取链本身仍然是「同端点、逐档试探」的一级结构。**它是「取链成功之后、判定降级原因时」的一次条件性追加请求**，且只在这首歌确实「实际文件低于请求档位」时发生。同时它**在开播关键路径上**（在 `fetch()` 内、`return` 之前），所以会直接加到点按到出声的耗时里。

---

## 5. 搜索路径的编排（`searchByType(1)`）

文件：`ui/viewmodel/SearchViewModel.kt`。

| 项 | 现状 | 落点 |
|---|---|---|
| 触发 | 输入 500ms 防抖（`delay(500)`）后 `searchByType(_currentType.value)`；切 tab 立即搜 | `:97-108`（`:101` delay）、`:110-118` |
| 分支 | `searchByType(type)` 的 `1 ->` 分支 = 聚合搜索（ncm + QQ） | `:151`、`:156` |
| 并发结构 | `coroutineScope { val neteaseDeferred = async {...}; val qqDeferred = if (qqAllowed) async {...} }` —— **两个 `async` 默认 `start = DEFAULT`，立即开跑** | `:191`、`:195`、`:214` |
| QQ 预算 | `QQ_SEARCH_BUDGET_MS = 5_000L`，包在 `withTimeoutOrNull(QQ_SEARCH_BUDGET_MS)` 里；返回 null 记 `timedOut = true`，异常记 `failed = true` | `:95`、`:216-231` |
| ncm 预算 | **无预算**（没有 `callTimeout`，参见 `RetrofitClient.kt:75-116` 的撤销注释） | `:195-208` |
| 先到先发布 | `select { neteaseDeferred.onAwait {...}; qqDeferred.onAwait {...} }`：谁先完成谁先上屏；发布后 `_isLoading.value = false` 结束转圈 | `:263-274`（发布 `:290-309`，`_isLoading = false` 在 `:307`） |
| 后半程 | 等另一条腿（QQ 已被预算卡死）→ 内容真的会变时才二次合并发布 | `:311-322`、`:334-342` |
| 无 QQ 时 | 退化回 `neteaseDeferred.await()`（`else` 分支） | `:275-280` |
| 取消安全 | 用户改关键词/离开页面时 `CancellationException` 原样抛出，不记成失败 | `:222-225`（上游 `:99`、`:113` 的 `searchJob?.cancel()`） |
| 埋点 | `SearchLatencyTrace`：`dispatch`（`:189`）→ `netease_done`/`qq_done`（`:268`/`:272`）→ `first_publish`（`:308`）→ `merged_publish`（`:341`）→ `done`（`:357`），末行 `Log.i` 打印 `elapsed` | `:183-189`、`:358-363`；实现见 `search/SearchLatencyTrace.kt:41-60` |

**网络层唯一的分段计时**在 `network/HttpTimingListener.kt`（`:64-146`）：它在 `plainClient` 与 `restClient` 上都注册（`RetrofitClient.kt:42`、`:62`），按请求输出 `path=/… ttfb=… body=… total=…`（`:140-144`），Release 包也打（`:49-55`）。它**不聚合、不上报、不落盘**，因此拿不到分位数。

---

## 6. 附：本次审计中发现的与文档不符之处（只陈述事实）

1. `AGENTS.md` 称 `PlaybackService` 的 ExoPlayer「**no on-disk media cache**」，与代码不符：`PlaybackService.kt:392` 明确装配了 `OfflineAudioCache.dataSourceFactory(this)`（`cache/OfflineAudioCache.kt:116-127`，`filesDir/offline/audio`，默认 512MB）。
2. `AGENTS.md` 称「TTL = 5 min。Gapless preload window = 60 s（`PRELOAD_THRESHOLD_MS`），despite some older comments saying 20 s」——代码一致（`PlayerViewModel.kt:419`、`:373`），但 `MainActivity.kt:1396` 的注释仍写着「最后 20 秒」，是过期注释。
3. `MusicSourceProvider.songDetail`（`source/MusicSourceProvider.kt:73`）**没有任何生产调用点**（`NeteaseSourceProvider.kt:56`、`QqMusicSourceProvider.kt:89` 只有实现）。
