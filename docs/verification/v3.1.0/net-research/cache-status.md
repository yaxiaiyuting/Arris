# 缓存现状（逐张表）

> 采集时间：2026-09-28 02:24 CST ｜ 代码基线：a86d97b ｜ 采集方式：源码审计（无运行时数据）
> **⚠️ 行号口径（重要）**：本文所有行号均按声明的基线 **`a86d97b`** 核对。
> 采集期间工作区被**并行改动**（未提交）：`PlayerViewModel.kt` 在旧 `:334` 处插入了 4 行 `MusicSource.BILIBILI` 分支，
> 因此该文件在工作区里**整体下移 +4 行**（例：`CACHE_TTL_MS` 基线 `:419` → 工作区 `:423`；`playSong` `:1078` → `:1082`；
> `preloadNextSong` `:1540` → `:1544`；`fetchLyrics` `:1820` → `:1824`）。
> **引用 `PlayerViewModel.kt` 时：工作区行号 = 本文行号 + 4（仅该文件、且行号 > 338），或直接按符号名检索。**
> 其余被本文引用的文件（`PlaybackService.kt` / `RetrofitClient.kt` / `SongUrlFetcher.kt` / `PreloadSlot.kt` / `LyricsCache.kt` /
> `ContentCache.kt` / `HomeSnapshot.kt` / `AppWarmup.kt` / `QqClient.kt` / `MainActivity.kt` …）在采集期间**未被改动**，行号可直接定位。


本文只描述**现状**，不含建议。凡代码不能直接证明的，显式标注「（推断）」。

**关于「命中率」这一列**：全仓检索 `cache hit` / `命中` 相关的打点，只找到**逐条事件日志**，**没有任何命中率计数器**。
下表一律标注「未采集」，并在 §7 列出仅有的那几处逐条日志与被动计时器。

---

## 1. 歌词缓存 —— `lyric/LyricsCache.kt`

| 项 | 值 | 证据 |
|---|---|---|
| 缓存名 / 落点 | SharedPreferences 文件 `ncrust_lyrics_cache`，键 `entries`（**一张 JSON map**，`songId -> CachedLyrics`） | `lyric/LyricsCache.kt:63-64`、`:88-100`、`:112-116` |
| 内存镜像 | `@Volatile private var memory: MutableMap<String, CachedLyrics>?`，避免重复读盘 | `:85-86`、`:88-90` |
| 容量上限 | `MAX_ENTRIES = 200` —— **LRC 正式条目与 TTML 暂存条目共用同一个名额池** | `:65`、`:102-110` |
| 淘汰策略 | 超限时按 `timestamp` 升序淘汰最旧（`sortedBy { it.value.timestamp }`） | `:103-109` |
| TTL | **LRC / tlyric / yrc / romalrc 一律没有 TTL**（只有 200 条 LRU）；**只有 TTML 有 TTL**：`TTML_TTL_MS = 7L * 24*60*60*1000`（7 天） | `:78-79`、`:259-260`（`isTtmlFresh`，`ttmlAt > 0 && now - ttmlAt < ttlMs`，边界「正好第 7 天」算过期） |
| 命中判据 | `map[songId.toString()]`（`:118-120`）；TTML 另走 `getTtml`（要求新鲜、非空白，`:205-206`、`:281-287`） | 同上 |
| 缺字段重取 | `needsRomalrcRefetch(entry) = entry != null && entry.romalrc == null` —— **「字段缺失」按 miss 处理、重取一次**，判据刻意不用「空串」 | `:273`（消费点 `PlayerViewModel.kt:1882-1892`） |
| 键空间特例 | 还没有正式条目时 TTML 暂存在 `ttml:<songId>` 前缀键下，`put()` 时并回正式条目并删除暂存 | `:76`、`:146-154`、`:240-243` |
| 写入门槛 | **只缓存服务端 `code == 200` 的权威结果**（含「确无歌词」的空串）；失败不写 | `:56-57`、`PlayerViewModel.kt:1926-1932` |
| 跨版本持久化 | ✅ 是。SharedPreferences 跨版本存活，文件头 KDoc 与迁移规则成文（「加字段 = 加迁移逻辑 = 加单测」，新字段必须可空 + 有默认值） | `:17-34`、`:262-273` |
| 命中率埋点 | **未采集**。只有逐条 `Log.d`（见 §7），且是 **debug 级** | `PlayerViewModel.kt:1884` |

---

## 2. URL 缓存（三层，互不替代）

### 2.1 `PlayerViewModel.preloadCache`（内存）

| 项 | 值 | 证据 |
|---|---|---|
| 落点 | `private val preloadCache = mutableMapOf<Long, PreloadCacheEntry>()` —— **纯内存，进程内单例（ViewModel 级）** | `PlayerViewModel.kt:418` |
| 条目字段 | `url / actualLevel / requestedLevel / br / type / songMaxLevel / timestamp` | `:406-417` |
| 容量上限 | **没有上限、也没有淘汰逻辑**：写入只有 `:1611` 一处，读取只有 `:1167`、`:1593` 两处，**没有任何 `remove`/`clear`**（对象随 ViewModel 一起被回收） | `:418`、`:1167`、`:1593`、`:1611`（`grep` 全量，仅这 4 处引用 `preloadCache`） |
| TTL | `CACHE_TTL_MS = 5 * 60 * 1_000L` = **5 分钟** | `:419` |
| 命中判据 | `requestedLevel == selectedQuality && now - timestamp <= CACHE_TTL_MS`（**档位必须一致**，降档重试绝不命中上一档） | `:1167-1169`（开播）、`:1593-1596`（预载） |
| 跨版本持久化 | ❌ 否（进程死即失） | — |
| 命中率埋点 | **未采集** | — |

### 2.2 `cache/OfflineUrlStore`（持久化，key → URL）

| 项 | 值 | 证据 |
|---|---|---|
| 落点 | SharedPreferences 文件 `ncrust_offline`，键 `urls`；内存镜像 `@Volatile index` + `synchronized` 双检 | `cache/OfflineUrlStore.kt:107-108`、`:110-122` |
| 索引结构 | `OfflineUrlIndex`：`LinkedHashMap` 插入序 + put 前先 `remove` ⇒ **LRU** | `:22-39` |
| 容量上限 | `MAX_ENTRIES = 300` | `:84`（淘汰循环 `:30-38`） |
| TTL | **无 TTL**。网易 URL 约 20 分钟过期这件事由服务端决定，客户端不做时间判定 | 全文件无 TTL 常量（对照 `:15-20` 的 KDoc 说明） |
| 命中判据 | `recall(preferredLevels, songId)`：先按 `OfflineKeys.key(songId, level)` 精确匹配，**再退化成「这首歌的任意档位」** | `:71-77`、`:161-164` |
| 写入门槛 | 只在**真正开始播放**时写：`PlaybackService.playUrl` → `OfflineUrlStore.rememberFromUrl` | `PlaybackService.kt:926`、`OfflineUrlStore.kt:149-151` |
| 联动删除 | 删曲目必删 URL（`forgetSong`，只由 `OfflineLibrary.remove` 调用） | `:154-158`、`:44-60` |
| 跨版本持久化 | ✅ 是（SharedPreferences） | — |
| 命中率埋点 | **未采集**。有逐条日志：命中 `Log.i`（`PlayerViewModel.kt:1299`）、命中但已失败过 `Log.w`（`:1296`） | 同左 |

### 2.3 `cache/OfflineAudioCache`（持久化，音频字节）

| 项 | 值 | 证据 |
|---|---|---|
| 落点 | media3 `SimpleCache`，目录 `filesDir/offline/audio`（**刻意不用 cacheDir**：系统清理会清 cacheDir） | `cache/OfflineAudioCache.kt:48`、`:33-35`（KDoc）、`:94-108` |
| 容量上限 | 默认 `DEFAULT_MAX_BYTES = 512 MiB`；可由 prefs 改，合法区间 `MIN_MB = 64` .. `MAX_MB = 8192`，非法值回落默认 | `:51`、`:54-55`、`:65-73` |
| 淘汰策略 | `LeastRecentlyUsedCacheEvictor(maxBytes)`（media3 负责） | `:100-104`、`:45-46`（KDoc） |
| TTL | 无 TTL，纯 LRU + 字节上限 | 全文件无 TTL 常量 |
| 命中判据 | key 由 `OfflineKeys.keyOf(url)` 决定（URL 带 `QUERY_KEY` 才走缓存）；`contains(context, key)` 是离线可播判据；`CacheDataSource.Factory().setCacheKeyFactory { spec -> OfflineKeys.keyOf(...) ?: uri }` | `:111-127`、`:125-127`、`OfflineKeys`（`cache/OfflineKeys.kt`） |
| 接入点 | ExoPlayer 的 MediaSourceFactory 就是它：`DefaultMediaSourceFactory(OfflineAudioCache.dataSourceFactory(this), extractorsFactory)` | `PlaybackService.kt:392` |
| 写入时机 | ExoPlayer 边播边写（`CacheDataSink`，`DEFAULT_FRAGMENT_SIZE`）；**只缓存「播过的那几段」**，seek 会留洞 —— 因此按 span 求和才是真实占用 | `:118-121`、`:147-152`（`bytesForSong` 的 KDoc 与实现） |
| 跨版本持久化 | ✅ 是（filesDir 下的文件，跨版本存活） | — |
| 命中率埋点 | **未采集**。只有容量类查询：`sizeBytes`（`:129-130`）、`keys`（`:138-140`）、`songKeys`（`:142-143`）、`bytesForSong`（`:154`），供设置页/离线管理页显示用量，**没有「命中/未命中」计数** | 同左 |

---

## 3. 封面缓存 —— `NcrustApplication` 的 Coil ImageLoader

| 项 | 值 | 证据 |
|---|---|---|
| 落点 | Coil 全局单例（`Application implements ImageLoaderFactory`） | `NcrustApplication.kt:32`、`:43-58` |
| 内存缓存 | `MemoryCache.maxSizeBytes(...)`，按**设备物理内存**分三档：`totalMem ≤ 3072MB → 16MB`；`≤ 6144MB → 24MB`；否则 `32MB` | `:46-50`、`:60-70` |
| 磁盘缓存 | `DiskCache.maxSizeBytes(100L * 1024 * 1024)`（**100MB**），目录 `cacheDir/image_cache` | `:51-56` |
| TTL | 无项目自定义 TTL（Coil 的缓存策略由库决定：磁盘按 URL 键、内存按「URL + 尺寸 + 变换」键）——**后半句是库行为（推断），本项目没有任何显式配置** | `:43-58`（只有 maxSize 与 directory 两项配置） |
| 命中判据 | 由 Coil 内部决定，项目未自定义 `Keyer` | 同上（全文件无 `keyer` / `diskCachePolicy`） |
| 清理 | `onTrimMemory`：`≥ RUNNING_LOW` 时清 `ContentCache` + Coil 内存缓存 + 背景图；`≥ RUNNING_MODERATE` 时只清 `ContentCache`；`onLowMemory` 清 `ContentCache` + Coil 内存缓存 | `:72-86`、`:88-93` |
| 跨版本持久化 | 磁盘份 ✅（`cacheDir/image_cache`，但 cacheDir 可被系统/清理类工具清除，**不是**「一定跨版本存活」）；内存份 ❌ | `:51-56` |
| 命中率埋点 | **未采集**（Coil 的统计未接出；项目里没有任何 `memoryCache.hitCount` 之类的读取） | — |

**预取与键不一致的现状（与 `preload-slot-status.md` §2.3 呼应）**：

| 预取 | 使用的 URL | 目标尺寸 | 消费者 |
|---|---|---|---|
| `AppWarmup` 冷启动预取 18 张 | `CoverUrls.small`（`?param=640y640`） | `COVER_PX = 320` | 首页 tile（`ui/screen/HomeScreen.kt:546`、`:591` 用 `small`） |
| `PlaybackService.preloadArtwork` 下一首封面 | `CoverUrls.large`（`?param=1080y1080`） | `1024×1024` | 通知栏位图（`:1173-1183`）；顺带进 Coil 缓存 |
| 列表项 | `CoverUrls.small` | 布局决定 | `ui/components/SongCard.kt:111`、`:210` |
| 播放器大图 | `CoverUrls.large` | 布局决定 | `ui/player/PlayerCard.kt:1770` |

`CoverUrls.kt:21-27` 只在 URL 没有 `?param=` 时追加尺寸参数；已有参数的原样保留（`:25`）。**尺寸不匹配是否导致 Coil 内存缓存未命中属于推断**（内存缓存键含尺寸是 Coil 2 的既有行为，本项目未验证）。

---

## 4. 歌曲信息缓存

### 4.1 `cache/ContentCache`（纯内存）

| 项 | 值 | 证据 |
|---|---|---|
| 落点 | `object ContentCache`，全 `@Volatile` 字段 | `cache/ContentCache.kt:22-26`、`:41`、`:74` |
| 内容 | 首页三块（每日推荐 / 推荐歌单 / 新歌）、榜单快照、详情页 LRU-32 三张表（专辑 / 歌单曲目 / 艺人专辑）、用户资料 | `:24-26`、`:41`、`:54-56`、`:74` |
| 容量上限 | 详情页三张表 `LruCache<Long, …>(32)`；首页/榜单/用户资料是单值，不增长 | `:54-56`、`:20-21`（KDoc） |
| TTL / 新鲜窗口 | `isHomeFresh(ttlMs = 15_000L)`（首页）、`isToplistFresh(ttlMs = 15_000L)`（榜单）—— **15 秒**，语义是「刚被 warmup 预取过，进屏不必再拉一遍」 | `:37-38`、`:45-46`；写入点 `markHomeWarmed()` `:32-34`、`putToplist` `:48-51` |
| 命中判据 | 各 getter 直接读字段/LRU：`getAlbum` `:58`、`getPlaylistSongs` `:61`、`getArtistAlbums` `:70`；`isHomeFresh` 用的是时间戳而非「字段非空」 | 同左 |
| 失效 | `clearAll()`（内存压力 / 切账号）；歌单写操作后 `invalidatePlaylist(id)` | `:77-87`、`:64-68`；调用方 `NcrustApplication.kt:76`、`:83`、`:90` |
| 跨版本持久化 | ❌ 否（内存；持久化那一层是 `HomeSnapshot` / `LibraryManager`） | `:17-18`（KDoc 明说） |
| 命中率埋点 | **未采集** | — |

### 4.2 `cache/HomeSnapshot`（首页磁盘快照）

| 项 | 值 | 证据 |
|---|---|---|
| 落点 | SharedPreferences 文件 `ncrust_home_cache`，键 `daily_songs` / `recommend_playlists` / `new_songs` / `toplists` / `saved_at` | `cache/HomeSnapshot.kt:39-44` |
| 容量上限 | 每块 `MAX_ITEMS = 60` 条（写盘时 `take(MAX_ITEMS)`）；KDoc 记「整包 JSON 约百 KB 量级」 | `:47`、`:78-85`、`:34` |
| TTL | **无 TTL**。只记 `saved_at` 时间戳（`:86`），并提供了 `savedAt()` 读取器（`:58-60`），但**没有任何调用点** —— `grep -rn "HomeSnapshot.savedAt\|HomeSnapshot.KEY_SAVED_AT"` 在 `HomeSnapshot.kt` 之外 0 命中，即这份时间戳被写下来却从未被读 | 同左 |
| 命中判据 | `restoreIntoCache`：**只在 `ContentCache` 对应块为 null 时才灌**（避免把 warmup 刚拿到的更新数据覆盖回旧快照）；`readList` 对空列表返回 null | `:98-113`、`:116-119` |
| 写入门槛 | 传 null 的块保持原值不变；空列表不写 | `:73`、`:78-85` |
| 跨版本持久化 | ✅ 是（SharedPreferences，跨版本存活；且与代码里的 `MAX_ITEMS` 强绑定） | — |
| 命中率埋点 | **未采集** | — |

### 4.3 相邻：歌曲详情（`SongDetail`）

播放路径上没有任何「歌曲详情缓存」。唯一的详情缓存是 `ContentCache` 的详情页 LRU-32（专辑 / 歌单 / 艺人），以及单曲信息页自己的 `SongViewModel`（`ui/viewmodel/SongViewModel.kt:13-14`，**无缓存，每次进页面都发请求**）。`songMaxLevelCache`（`SongUrlFetcher.kt:230`）是本文件之外唯一与「详情」相关的内存缓存，见 `request-orchestration.md` §4。

### 4.4 其他网络响应缓存（同样是本项目自建的持久化缓存）

#### 4.4.1 歌单缓存 —— `qq/QqPlaylistStore`（键按音源参数化，目前消费者是 `QqPlaylistRepository`）

| 项 | 值 | 证据 |
|---|---|---|
| 落点 | SharedPreferences 文件 `ncrust_qq_playlists`，键形如 `list:<source>:<ownerId>`、`detail:<source>:<ownerId>:<playlistId>`，另有 `schema_version` | `qq/QqPlaylistStore.kt:52`、`:62-72`、`:187` |
| 容量上限 | 列表条目无上限；**详情条目**裁剪到 `MAX_DETAIL_ENTRIES = 30`（按 `savedAt` 最旧优先） | `:55`、`:141-155` |
| TTL | `PlaylistCacheCodec.DEFAULT_TTL_MS = 10 * 60 * 1000L`（**10 分钟**）；判定 `isFresh` 左闭右开、`savedAt <= 0` 一律过期 | `playlist/PlaylistCacheCodec.kt:65`、`:389-391`；仓储侧可覆盖 `ttlMs`（`qq/QqPlaylistRepository.kt:49`） |
| 命中判据 | 编码层返回 `ListRead.Ok` / `DetailRead.Ok`（含 `fresh` 标志）与 `DropReason`（如 `OWNER_MISMATCH`）；**owner 不匹配即丢弃**，换账号不会串数据 | `PlaylistCacheCodec.kt:300-330`、`QqPlaylistStore.kt:41`、`:81-84` |
| 跨版本持久化 | ✅ 是（带 `SCHEMA_VERSION = 2` 与显式 drop reason，`PlaylistCacheCodec.kt:62`） | — |
| 命中率埋点 | **未采集** | — |

#### 4.4.2 跨源匹配缓存 —— `crosssource/MatchCacheStore`

| 项 | 值 | 证据 |
|---|---|---|
| 落点 | SharedPreferences 文件 `ncrust_match_cache`，键前缀 `artist:` / `album:` / `track:`（`:261-263`） | `crosssource/MatchCacheStore.kt:45`、`MatchCacheCodec.kt:261-263` |
| 容量上限 | `MAX_ENTRIES = 400`，超出按 `(savedAt, key)` 排序裁掉最旧 | `MatchCacheStore.kt:51`、`:192`、`:220-223` |
| TTL | `MatchCacheCodec.TTL_MS = 7L * 24*60*60*1000`（**7 天**） | `MatchCacheCodec.kt:62`、`:140-142` |
| 命中判据 | `decode(raw, now, ttlMs)`：`savedAt <= 0` 或超龄即判过期（返回不新鲜） | `MatchCacheCodec.kt:179`、`:137-142`、`:208-215` |
| 消费方 | `crosssource/CatalogAggregator`：读 `:554`、`:611`，写 `:136`、`:187`（单曲信息页的两源版本对比） | 同左 |
| 跨版本持久化 | ✅ 是（`SCHEMA_VERSION = 1` + `ALGORITHM_VERSION = 1`，算法版本变化可整体作废） | `MatchCacheCodec.kt:51`、`:59` |
| 命中率埋点 | **未采集** | — |

---

## 5. `library/LibraryManager`（持久化，非缓存语义）

| 项 | 值 | 证据 |
|---|---|---|
| 落点 | SharedPreferences 文件 `ncrust_library`，键 `saved_songs` / `saved_albums` / `liked_ids` | `library/LibraryManager.kt:75-78`、`:134` |
| 容量上限 | **无**（用户收藏集，服务端为准） | 全文件无上限常量 |
| TTL | **无**（云端状态镜像；`refreshFromCloud` 主动刷新，不是过期判定） | `:50-53`（KDoc）、`:206`（warmup 调用点 `AppWarmup.kt:206`） |
| 命中判据 | 内存缓存优先，`preload(context)` 在 IO 线程把三份数据解析进内存 | `:251`（KDoc 与实现） |
| 跨版本持久化 | ✅ 是 | — |
| 命中率埋点 | **未采集**（不适用：这是持久数据而非缓存） | — |

## 6. `player/PlaybackStateManager`（持久化，非缓存语义）

| 项 | 值 | 证据 |
|---|---|---|
| 落点 | SharedPreferences 文件 `ncrust_playback_state`：`song_id` / `song_name` / `song_artist` / `song_artwork` / `song_source` / `song_source_id` / `song_media_id` / `is_playing` / `has_state` / `queue` / `queue_index` / `play_mode` / `song_positions` | `player/PlaybackStateManager.kt:30-53`、`:70-72` |
| 容量上限 | 逐曲进度表 `song_positions` 上限 `MAX_SONG_POSITIONS = 300`，超出按保存时间淘汰最旧 | `:53-54` |
| TTL | 无时间 TTL；但**有「可续播」阈值** `MIN_RESUMABLE_MS = 3_000L`（进度 < 3s 不视为可续播） | `:245` |
| 命中判据 | `getState(context)` / `getSongPosition(context, songId)` 直读 prefs；清理由 `clearSongPosition`（正常播完时）承担 | `PlaybackService.kt:585`（播完清除）、`:245`（阈值） |
| 跨版本持久化 | ✅ 是 | — |
| 命中率埋点 | **未采集**（不适用） | — |

## 7. 埋点现状总表（诚实标注）

| 手段 | 位置 | 覆盖什么 | 是否能算命中率 |
|---|---|---|---|
| `HttpTimingListener` | `network/HttpTimingListener.kt:64-146`；注册点 `RetrofitClient.kt:42`（plainClient）、`:62`（restClient） | 每通请求的 `dns`/`connect`/`ttfb`/`body`/`total`（`:41-43` 的语义表），Release 也打（`:49-55`） | ❌ 只有单请求分段耗时，无聚合、无分位数、不落盘 |
| `SearchLatencyTrace` | `search/SearchLatencyTrace.kt:41-60`；打点 `SearchViewModel.kt:189`、`:268`、`:272`、`:308`、`:341`、`:357` | 搜索的分段时刻，`timeToFirstResultMs = dispatch → first_publish`（`:31-33`） | ❌ 与缓存无关 |
| 逐条命中日志 | `PlayerViewModel.kt:1299`（离线 URL 命中，`Log.i`）、`:1296`（命中但本会话已失败，`Log.w`）、`:1884`（歌词缓存命中，**`Log.d`**） | 单次事件 | ❌ 不计数、debug 级在 release 通常不可见 |
| Coil 命中统计 | 无 | — | ❌ 未接出 |
| `ContentCache` / `HomeSnapshot` / `LyricsCache` 命中计数 | 无 | — | ❌ |

**结论（事实陈述）**：本项目当前**没有任何一张缓存有命中率埋点**。要拿到命中率，只能依赖 `HttpTimingListener` 的「请求条数」间接反推，而它本身不区分「这次请求是因为缓存未命中才发的」（推断：例如 `path=/api/song/lyric` 的行数可以近似歌词缓存未命中次数，但代码里没有任何地方做这个关联）。

---

## 8. 相邻持久化（不在上表要求范围内，列出以免遗漏）

| 文件 | 归属 | 内容 | 上限 / TTL | 证据 |
|---|---|---|---|---|
| `search_history` | `SearchHistoryManager` | `songs` / `albums` / `artists` | `MAX_ITEMS = 10`（各），`TTL_MS = 14L * 24*60*60*1000`（14 天） | `library/SearchHistoryManager.kt:37-38`、`:124`、`:128`、`:136` |
| `ncrust_prefs` | `CookieManager` | `user_cookie` | — | `auth/CookieManager.kt`（见 `AGENTS.md` 的 prefs 表） |
| `ncrust_settings` | 多模块 | 主题 / 语言 / 档位 / 歌词偏好 / `offline_cache_mb` 等 | — | `cache/OfflineAudioCache.kt:59-60`、`:86-90` |
| `ncrust_qq_prefs` | `QqAuthStore` | QQ cookie | — | `qq/QqAuthStore.kt`（`QqClient.kt:52-53` KDoc 提到） |
| `ncrust_offline` 的 `tracks` | `OfflineLibrary` | 离线曲目索引（≤300 LRU，与 URL 清单**必须一起删**） | `MAX_ENTRIES = 300` | `cache/OfflineLibrary.kt:149-150`、`:173`、`:183`、`:192` |

（另有两张自建持久化缓存 —— QQ/歌单缓存与跨源匹配缓存 —— 见 §4.4。）
