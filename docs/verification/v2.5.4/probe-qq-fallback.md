# qm「无 songid 兜底」探针（v2.5.4）

> 只读调研。本文**没有改动任何源码**；所有 `file:line` 都用 read/grep 工具在
> `/home/duanjb666/deepseek/ncrust-gpl/Ncrust` 当前工作区（HEAD = `da5c9f8`）上核对过，
> 引用的是文件里的真实代码。凡属推断而非读到的，一律标在文末「未确认 / 不确定」。

---

## 0. 结论先行

**1）不存在「因为没有 songid 所以取不到播放 URL」这条兜底 —— 取链从头到尾不看 songid。**

`QqApi.fetchPlayUrl` 的第一行就是 `val songMid = song.sourceId ?: return null`
（`app/src/main/java/com/takahashirinta/ncrust/qq/QqApi.kt:134`）。
取链要的是 **songmid**（拼 vkey 请求）与 **media_mid**（拼文件名），
数字 songid 在这条链路上**一次都没被读过**。所以「QQ 曲目没有 songid ⇒ 需要兜底取 URL」
这个命题在本仓库里**不成立**。

**2）真正存在的「无 songid 兜底」在解析期，是 id 生成兜底，不是取链兜底。**

`SourceIds.qqId(rawSongId, sourceId)` 在 `rawSongId <= 0`（或缺席、或已占标志位）时，
用 **FNV-1a 64 位散列对 songmid** 造一个确定性的内部 id：

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/source/MusicSource.kt:198-201
fun qqId(rawSongId: Long, sourceId: String): Long {
    val raw = if (rawSongId > 0L && rawSongId < QQ_ID_FLAG) rawSongId else hashSourceId(sourceId)
    return QQ_ID_FLAG or raw
}
```

触发它的唯一入口是 `QqSongMapper.fromSongObject`：

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/qq/QqSongMapper.kt:93-94
val rawId = item.optLong("id", 0L)
val syntheticId = SourceIds.qqId(rawId, mid)
```

`optLong("id", 0L)` —— **字段缺席与字段为 `0` 在这里不可区分**，两者都进散列兜底。
空串不会发生（这是数字字段）。负数、≥`2^62` 的值同样进兜底（`MusicSource.kt:199` 的判据）。
这条兜底**已经实现、已经有单测**（`app/src/test/java/com/takahashirinta/ncrust/source/MusicSourceTest.kt:163/172/182`），
**但没有任何计数器、没有任何持久化、也没有任何 UI 能读出它发生了多少次** —— 这正是本次调研要补的缺口。

**3）取链路上另有一条相似但不同的兜底：`media_mid` 缺失时回落 songmid。**

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/qq/QqApi.kt:134-136
val songMid = song.sourceId ?: return null
// 没有 media_id（v2.1.0 之前落盘的队列条目）时才退回 songmid
val mediaMid = song.mediaId?.takeIf { it.isNotEmpty() } ?: songMid
```

触发条件是 `mediaId == null` **或 `mediaId == ""`**（空串被 `takeIf` 挡掉，与 null 等效）。
代码注释明确写了**不做**「media_mid 失败再用 mid 试一次」的兜底（`QqApi.kt:129-131`），
原因实测过：用错 mid 时服务端**照样返回 purl + vkey 且不报错**，坏链接一路走到 CDN 才 404
（`QqApi.kt:126-128`）。所以这里只有「缺失才回落」，没有「失败再试」。

**4）songmid 本身缺失 = 硬失败，没有兜底、没有重试。**

两条独立闸门，都在发请求之前：

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/source/SongSourceExt.kt:31-32
val SongItem.isResolvable: Boolean
    get() = !musicSource.requiresSourceId || !sourceId.isNullOrEmpty()
```

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/source/SourceRouter.kt:61-64
if (!song.isResolvable) {
    Log.w(TAG, "unresolvable song source=${song.musicSource.key} id=${song.id} (missing sourceId)")
    return null
}
```

注意 `!sourceId.isNullOrEmpty()` —— **空串同样算缺失**（这点与 media_mid 判据一致）。
`QqApi.kt:134` 是第二道（防绕过 Router 直连 Provider 的调用方）。

**5）音质降级是有界的：一次 HTTP 请求覆盖全部档位，档位数上界 6，无循环重试。**

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/qq/QqApi.kt:138-139
val types = QqQuality.attemptsFor(level)
val info = requestVkeyBatch(songMid, mediaMid, types, requestedLevel = level) ?: return null
```

随后 `QqApi.kt:143-174` 那个 `for (fileType in types)` **是纯内存筛选循环，不再发网络**
（`requestVkeyBatch` 内部只调用一次 `QqClient.musicu`，`QqApi.kt:246`）。
`QqQuality.attemptsFor` 是「首选段 ∪ 严格低于首选段的兜底段」的去重保序集合
（`QqQuality.kt:117-129`），上限为 `dolby` 档的 6 个（`Q001,Q000,AI00` + `F000,M800,M500`）。
这是**有界（6）** 且**单次请求**的，不是 circuit-breaker 意义上的熔断，而是「容量上界」。

真正的熔断在上层，且与 QQ 无关：`QualityRetryGuard.MAX_ATTEMPTS_PER_SONG = 3`
（`PlaybackGuard.kt:179`）、`AutoSkipGuard.MAX_CONSECUTIVE_AUTO_SKIPS = 5`（`PlaybackGuard.kt:299`）。

**6）今天能测什么、不能测什么。**

| 能不能测 | 项 | 依据 |
|---|---|---|
| ❌ 完全测不到 | 「无 songid 兜底」发生了几次 | 全仓 grep：`SourceIds.qqId` 只有 `QqSongMapper.kt:94` 与 `QqCatalog.kt:351` 两个产出点，两处都无计数、无日志 |
| ⚠️ 只能从 logcat 人肉数 | 取链成功/失败、实际档位 | `QqApi.kt:152` 的 `Log.i`（release 也打）与 `QqApi.kt:175` 的 `Log.w` |
| ⚠️ 只能人肉对照 | 逐档位的 `result` / `tips` | `QqApi.kt:318-358` 的 `logVkeyDiagnostics`，**仅 `BuildConfig.DEBUG`** |
| ❌ 无 | QQ 侧任何计数器 / 统计 / 上报 | 全仓无 analytics SDK；`QqMusicSourceProvider.kt:40` 明写「不把 QQ 的播放行为上报给任何一方」 |
| ✅ 有（但未与兜底关联） | 「真的播出声了」 | `PlayerViewModel.kt:545-547`（进度 ≥ 3000ms 清熔断计数） |

**7）一句话给实施者：** 要量的是「解析期散列兜底的发生率」与「它之后播放是否真的成功」，
埋点应落在 `QqSongMapper.fromSongObject`（解析期）+ `QqApi.fetchPlayUrl`（取链期）+
`PlayerViewModel` 已有的「确实播出声了」判据上，**计数器纯内存 O(1)、落盘只在 debug 页打开与
Activity `onStop`**，绝不进 2Hz 心跳以外的热路径，也绝不上传。

---

## 1. 取链与兜底的精确位置

### 1.1 QQ 曲目在哪里拿到可播放 URL（完整分支）

`QqApi.fetchPlayUrl` 是唯一出口（`QqApi.kt:133`）：

| 行 | 代码 | 语义 |
|---|---|---|
| 134 | `val songMid = song.sourceId ?: return null` | ★ **songmid 缺失 = 立即 null，无兜底** |
| 136 | `val mediaMid = song.mediaId?.takeIf { it.isNotEmpty() } ?: songMid` | ☆ **唯一的取链兜底**（null 或空串） |
| 138 | `val types = QqQuality.attemptsFor(level)` | 档位尝试序列（≤6，有界） |
| 139 | `requestVkeyBatch(songMid, mediaMid, types, requestedLevel = level) ?: return null` | **一次** HTTP；`null` = 请求本身失败 |
| 143-149 | `for (fileType in types) { ... if (purl == null) { lastUrlFailure = ...; continue } }` | 纯内存筛选；逐档记录失败原因 |
| 150-152 | `val url = buildUrl(purl)` / `Log.i(TAG, "vkey ok: ...")` | 成功出口 |
| 156-173 | `return SongUrlResult(url = OfflineKeys.withKey(...), actualLevel, levelFromFile = true, br, type, songMaxLevel = null)` | 挂离线缓存 key |
| 175 | `Log.w(TAG, "no playable url for ${SourceIds.trackKey(...)} at level=$level")` | 全档失败出口 |

`requestVkeyBatch`（`QqApi.kt:222-276`）内部：`QqRequests.vkey(...)` 拼请求（`:230`）→
`QqClient.musicu(request, appIdentity = true)`（`:246`）→ 按响应条目自带 `filename` 反查档位
（`:256-260`，不依赖响应顺序）→ 返回 `Map<QqFileType, JSONObject>`。

`composeUrl`（`QqApi.kt:291-296`）另有一条**与 songid 无关**的容错：`sip` 数组为空时回落固定 CDN
`https://ws.stream.qqmusic.qq.com/`（`:301`）。这条不是「无 songid 兜底」，但同属「取链容错」，
统计时不要混入同一组计数（见 §9.1）。

### 1.2 「无 songid」的三条不同命运（必须分开计）

| # | 触发条件 | 行为 | 有界性 | 是否算「兜底」 |
|---|---|---|---|---|
| A | `SongItem.id` 的无 songid（`rawSongId <= 0`） | `SourceIds.qqId` 用 **FNV-1a(mid)** 造 id | 纯函数、O(len(mid)) | ✅ **这就是所谓 no-songid fallback** |
| B | `SongItem.mediaId` 为 null / 空串 | 回落用 songmid 拼文件名 | 单次 | ✅ 取链兜底（`QqApi.kt:136`） |
| C | `SongItem.sourceId` 为 null / 空串 | `isResolvable == false` → `return null` | — | ❌ 硬失败（`SongSourceExt.kt:31`、`SourceRouter.kt:61`、`QqApi.kt:134`） |

三者互相独立：**有 songid 不代表有 songmid；没有 songid 完全不影响取链**。

### 1.3 散列兜底的下游影响（为什么它是「有后果的」）

散列出来的 id 会进入所有以裸 `Long` 为键的持久化结构（`MusicSource.kt:141-157` 的 KDoc 列了全部：
队列判重与持久化、离线音频缓存 key、离线曲目索引、续播进度、歌词缓存 key、ExoPlayer mediaId）。
它**确定性**（同一 mid 永远同一 id），所以不会漂移；但它**不是真实 songid**，
而 `QqApi.fetchLyric` 会把反解出来的值当 `songId` 发给服务端：

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/qq/QqApi.kt:381-383
val songMid = song.sourceId ?: return null
val rawId = SourceIds.qqRawId(song.id) ?: 0L
val request = QqRequests.lyric(songMid, rawId)
```

即：走 A 兜底的曲目，歌词请求里的 `songId` 是一个**散列值**而不是真 songid
（`QqRequests.kt:108-126` 里 `songId` 与 `songMid` 同时发送）。
服务端是否只认 `songMid` 而忽略 `songId` —— **未验证**，见 §12。
这是「量兜底发生率」之外的第二个理由：兜底不仅影响计数器，还可能影响歌词。

---

## 2. 触发条件一览（谁为空、谁为 0、谁为空串）

| 字段 | 触发兜底的取值 | 代码判据 | 位置 |
|---|---|---|---|
| `id`（songid） | 缺席 / `0` / 负数 / `>= 2^62` | `rawSongId > 0L && rawSongId < QQ_ID_FLAG` 为假 | `MusicSource.kt:199` |
| `file.media_mid` | 缺席 / 空串 | `optString(...)?.takeIf { it.isNotEmpty() }` 为 null | `QqSongMapper.kt:141` |
| `SongItem.mediaId` | `null` / 空串 | `?.takeIf { it.isNotEmpty() } ?: songMid` | `QqApi.kt:136` |
| `mid` / `songmid` | 缺席 / 空串 | 两个都为空 ⇒ **该条直接丢弃，不产出 SongItem** | `QqSongMapper.kt:90-92` |
| `SongItem.sourceId` | `null` / 空串 | `isResolvable` 为假 | `SongSourceExt.kt:32`、`QqApi.kt:134` |

注意 `QqSongMapper.kt:90-92` 的 `?: return null`：**没有 mid 的条目根本不会变成 SongItem**，
所以「QQ 曲目没有 songmid」在 `SourceRouter` 层面的出现只可能来自**绕过映射器的构造路径**
（见 §3.3 的 `songRefOf` 调用点），而不是来自服务端响应。

---

## 3. songid / songmid / media_mid 的分工与产地

### 3.1 各自用途（读码即可穷举）

| 标识 | 用途 | 唯一产出点 | 消费点（全仓） |
|---|---|---|---|
| `songid`（数字，`id`） | (a) 经 `SourceIds.qqId` 变成**内部 id**（带 `1L shl 62` 标志位）；(b) 作为 `songId` 参数发**歌词**请求；(c) 作为 `trackKey`/`mediaId`/离线 key 的数值部分 | `QqSongMapper.kt:93`、`QqCatalog.kt:351` | `MusicSource.kt:198`、`QqApi.kt:382` |
| `songmid`（base62 串，`mid`） | (a) `param.songmid[]`（vkey）；(b) 歌词 `songMid`；(c) `SongItem.sourceId`（身份 / 判重 / 取链前置判据）；(d) 歌曲**列表**（非文件名）相关的一切 | `QqSongMapper.kt:90-92` | `QqApi.kt:134`、`QqRequests.kt:56`、`SourceRouter.kt:61` |
| `media_mid`（base62 串） | **只用于拼取链文件名**：`<前缀><media_mid>.<扩展名>` | `QqSongMapper.kt:140-143` | `QqQuality.fileNameFor`（`QqQuality.kt:139-140`）、`QqApi.kt:136` |

关键：**文件名按 media_mid 拼，列表/身份按 songmid**；实测两者经常不同
（《晴天》`mid=0039MnYb0qxYhV` / `media_mid=003Qui1q2u1Zho`，`QqSongMapper.kt:36-38`），
用错的症状是「服务端不报错、CDN 404、播放器缓冲一会儿报错」（`QqApi.kt:126-128`）。

### 3.2 各入口的字段来源

| 入口 | 响应路径 | songid | songmid | media_mid |
|---|---|---|---|---|
| 搜索（旧版 GET，主通道） | `data.song.list[]` → `songsFromLegacySearch` | `id` | `mid`（回落 `songmid`） | `file.media_mid`（回落 `mid`） |
| 搜索（`musicu`，兜底通道） | `req.data.body.item_song.items[]` 或 `body.song.list[]` → `songsFromSearchResponse` | 同上（`new_json=1` 下字段同构） | 同上 | 同上 |
| 艺人热门曲 | `data.songlist[]` → `songsFromSingerDetail`（`QqCatalog.kt:266-269`） | 同上 | 同上 | 同上 |
| 专辑曲目 | `data.songList[].songInfo` → `albumDetailFromSongList`（`QqCatalog.kt:272-289`） | 同上 | 同上 | 同上 |
| QQ 歌单详情 | `data.songlist[]` → `parseDetailPage`（`QqPlaylistParser.kt:258`） | 同上 | 同上 | 同上 |
| 路由 → 锚点曲目 | `routeAnchorSong`（`SongDetailScreen.kt:254-261`） | 路由里只有 `(source, id)` | **null** | **null** |
| 车机 browse tree | `PlaybackService.resolveMediaItem`（`PlaybackService.kt:873`） | 从 mediaId 反解 | **null** | **null** |
| 队列恢复 | `ncrust_playback_state` 里的 `SongItem` JSON | 持久化保留 | 持久化保留 | 持久化保留 |

四条网络入口**共用同一个 `QqSongMapper.fromSongObject`**（`QqCatalog.kt:185-187`、`QqPlaylistParser.kt:256-258`
的注释把这条写成了纪律：少一套映射就少一处会漂移的地方）。所以「解析期兜底」的埋点只需加一处。

### 3.3 哪些入口能产出**没有 songid** 的 QQ 曲目

`QqSongMapper.fromSongObject` 里 `id` 是**可选**的：`item.optLong("id", 0L)`（`QqSongMapper.kt:93`）
没有任何非空/非零校验，而 `mid` 是**必需**的（`:90-92` 缺则 `return null`）。
因此：

- **能**：任何一条上面的网络入口，只要该条响应里的 `id` 缺席或为 `0`
  → `rawId = 0` → 散列兜底（A 路径）。**这是唯一的「无 songid」来源**。
- **能**（另一含义的「无 songid」）：`songRefOf(source, id, sourceId = null, mediaId = null)`
  的调用点产出的是「合成 id 有、songmid 没有」的曲目，走的是 C 路径（硬失败），
  不是 A 路径。已知调用点：
  - `ui/screen/SongDetailScreen.kt:260`（`routeAnchorSong`，注释明写「标志位缺省时的 songmid
    只能用 id 的字符串形式兜底……因为我们手上没有 songmid」）；
  - `player/PlaybackService.kt:873`（车机 `resolveMediaItem`，只从 mediaId 反解出 `(source, id)`）；
  - `ui/viewmodel/PlayerViewModel.kt:1023`（诊断用，会带上 `currentSongSourceId`）。
- **不能**：`QqCatalogMapper.syntheticId`（`QqCatalog.kt:348-352`）在 mid 缺失时返回 `null`
  而不是造 id —— 与 `fromSongObject` 的取舍一致。

**结论：A 路径（无 songid 兜底）只可能由服务端响应的 `id` 字段缺席/为 0 触发，
不可能由客户端代码构造触发。**

---

## 4. 调用流（用户点一首 QQ 歌 → URL 解析成功 / 失败）

```
【解析期 · 产出入队对象】
QQ 服务端响应（搜索 / 艺人 / 专辑 / 歌单）
  └─ QqSongMapper.fromSongObject(item)                         QqSongMapper.kt:88
       ├─ mid 缺席/空串 ⇒ return null（该条丢弃，不产出 SongItem）  :90-92
       ├─ rawId = item.optLong("id", 0L)                          :93
       └─ SourceIds.qqId(rawId, mid)                              :94
            └─ rawId <= 0 ⇒ FNV-1a64(mid)  ← ★★★「无 songid 兜底」  MusicSource.kt:199
       └─ mediaId = mediaMidOf(item, mid)                         :126 → :140-143
            └─ file.media_mid 缺席 ⇒ 回落 mid  ☆ 取链兜底(1)

【点播期】
用户点一首 QQ 歌
  └─ MainActivity.playSongItem(song)                              MainActivity.kt:1488
       └─ playFromQueue(idx, PlayOrigin.USER)                      MainActivity.kt:1128
            └─ PlayerViewModel.playSong(...)                       PlayerViewModel.kt:1075
                 ├─ TrackKey.of(sourceKey, songId, sourceId, mediaId)   :1100
                 ├─ 【快路径】preloadCache 命中同档位 ⇒ 直接起播       :1154-1194
                 └─ playJob (Dispatchers.IO)                           :1197
                      └─ fetchUrlOfflineFirst(ref, level)              :1313
                           ├─ 明确离线 ⇒ recallOfflineCache()          :1273（只认 OfflineUrlStore）
                           └─ SourceRouter.resolveUrl(ref, level)      :1323 → SourceRouter.kt:60
                                ├─ !song.isResolvable ⇒ null  ✖C 硬失败  SourceRouter.kt:61-64
                                │     （songmid 为 null/空串；无兜底、无重试，只打一条 Log.w）
                                └─ QqMusicSourceProvider.resolveUrl     QqMusicSourceProvider.kt:78
                                     └─ QqApi.fetchPlayUrl(song, level)  QqApi.kt:133
                                          ├─ songMid = song.sourceId ?: return null  ✖C   :134
                                          ├─ mediaMid = song.mediaId ?: songMid  ☆B      :136
                                          ├─ types = QqQuality.attemptsFor(level)  ≤6 档 :138
                                          ├─ requestVkeyBatch(...)  仅 1 次 HTTP（无重试）:139
                                          │     └─ QqClient.musicu()  execute() 单次    :246
                                          └─ for (fileType in types) 纯内存选择，0 网络    :143
                                               ├─ purl 非空 ⇒ SongUrlResult  Log.i         :152 ✅
                                               └─ 全档 purl 空 ⇒ null        Log.w         :175 ❌
                      ├─ 拿到 URL ⇒ Intent("url"/sourceKey/sourceId/mediaId)  :1233-1248
                      └─ null ⇒ onUrlUnavailable(songId, level, origin)      :1334
                           ├─ 音质路径 ⇒ 回落 lastGoodLevel，绝不跳歌         :1345-1359
                           └─ 播放路径 ⇒ 有界跳歌（≤5）或停下等用户           :1363-1380

【播放期】
PlaybackService.onStartCommand → url extra                          PlaybackService.kt:680-711
  └─ playUrl(url, startPositionMs)                                  :922
       ├─ OfflineUrlStore.rememberFromUrl(this, url)                :926（唯一「成功 URL」落盘点）
       └─ player.setMediaItem / prepare / play                      :933-935
  └─ 2Hz ticker（delay(500)，仅 isPlaying 时广播）                    :1497-1511
       └─ onProgressUpdate → PlayerViewModel                        PlayerViewModel.kt:537
            ├─ 进度 ≥ 3000ms ⇒ autoSkipGuard.onProgressConfirmed()  :545  ← ✅「真的播出声了」
            ├─ 进度 ≥ 80%   ⇒ PlayReporter.reportPlay(...)          :549-554 ⚠ 无音源闸门
            └─ gapless 预载窗口判定                                  :563-568
  └─ 错误：onPlayerError → onPlaybackError?.invoke(songId, failure)  :441-465
           onAudioSinkError → onPlaybackError?.invoke(...)          :565-575
       └─ PlayerViewModel.handlePlaybackError → 降档重试（≤3）/ 停下 :1396-1457

★ = 解析期兜底（FNV-1a 散列，无计数、无日志）
☆ = 取链兜底（media_mid → songmid，无计数、只有一条 Log.i 不区分来源）
✖ = 硬失败（songmid 缺失；无兜底、无重试）
```

---

## 5. 断言 | 证据（file:line） | 方法

路径一律相对 `app/src/main/java/com/takahashirinta/ncrust/`（测试相对 `app/src/test/java/com/takahashirinta/ncrust/`）。
方法列：**读**=read 工具逐行读；**grep**=全仓正则检索；**既有单测**=仓库里已有的测试；
**反向检索**=由定义反查所有调用点。

| # | 断言 | 证据（file:line） | 方法 |
|---|---|---|---|
| 1 | QQ 取链**不读 songid**，第一行就按 songmid 取 | `qq/QqApi.kt:133-136` | 读 |
| 2 | songmid 缺失 ⇒ 立即 `null`，无兜底无重试 | `qq/QqApi.kt:134`；`source/SongSourceExt.kt:31-32`；`source/SourceRouter.kt:61-64` | 读 |
| 3 | **无 songid 兜底 = FNV-1a(mid) 散列造 id** | `source/MusicSource.kt:198-201`、`:216-223` | 读 |
| 4 | 触发点唯一：`optLong("id", 0L)` 缺席/0 不可区分 | `qq/QqSongMapper.kt:93-94` | 读 |
| 5 | mid 必需、id 可选（mapper 字段可选性的唯一差异） | `qq/QqSongMapper.kt:90-94` | 读 |
| 6 | media_mid 缺失 ⇒ 回落 mid（取链唯一兜底） | `qq/QqSongMapper.kt:140-143`；`qq/QqApi.kt:136` | 读 |
| 7 | 明确**不做**「media_mid 失败再试 mid」 | `qq/QqApi.kt:126-131`（注释）+ `:143-174`（循环里无第二 mid 变量） | 读 |
| 8 | 档位尝试 ≤6 且只发 1 次 HTTP | `qq/QqQuality.kt:107-129`、`qq/QqApi.kt:138-139`、`:246` | 读 + 既有单测 `qq/QqQualityTest.kt:18-40` |
| 9 | 上层熔断：每曲重试 ≤3、连续跳歌 ≤5 | `player/PlaybackGuard.kt:179`、`:299` | grep |
| 10 | 「真的播出声了」判据 = 进度 ≥ 3000ms | `player/PlaybackGuard.kt:302`；`ui/viewmodel/PlayerViewModel.kt:545-547` | 读 |
| 11 | 4 条网络入口共用同一 mapper | `qq/QqCatalog.kt:185-187`、`:292-299`；`qq/QqPlaylistParser.kt:256-258`；`qq/QqSongMapper.kt:52-85` | 读 + 反向检索 |
| 12 | 存在「有 id 无 songmid」的构造路径（硬失败） | `ui/screen/SongDetailScreen.kt:254-261`；`player/PlaybackService.kt:867-877` | grep + 读 |
| 13 | QQ 播放链无任何计数器 | grep `AtomicLong|AtomicInteger` → 仅 `qq/QqApi.kt:99`（搜索 id 生成器）与 `lyric/LyricRequestGate.kt:31` | grep |
| 14 | 无 analytics / 上报 SDK | grep `analytics|Analytics|Firebase|Bugly|sentry` → 仅 media3 的 `AnalyticsListener`（`player/PlaybackService.kt:52/564`） | grep |
| 15 | QQ Provider 明写不做任何播放上报 | `qq/QqMusicSourceProvider.kt:22-41`（KDoc「不把 QQ 的播放行为上报给任何一方」） | 读 |
| 16 | **但** `PlayReporter` 无音源闸门，会被喂 QQ 合成 id | `ui/viewmodel/PlayerViewModel.kt:549-554`、`:570-580`（`sid = currentSongId.value`，无 source 判断）；`player/PlayReporter.kt:39-48`（只判 `MUSIC_U` 与 `songId > 0`） | 读（关键发现） |
| 17 | 现有 QQ 日志：成功 1 条 `Log.i`、全败 1 条 `Log.w` | `qq/QqApi.kt:152`、`:175` | 读 |
| 18 | 逐档诊断日志仅 DEBUG | `qq/QqApi.kt:237-245`、`:262-274`、`:318-358` | 读 |
| 19 | 唯一的 debug-only 诊断 UI 在设置页 QQ 账号块 | `ui/screen/UserScreen.kt:367-392`、`:1511-1520`、`:1422-1430` | 读 |
| 20 | 本地持久化约定：SharedPreferences + Gson + 进程内单例 + `apply()` | `cache/OfflineUrlStore.kt:106-141`；`cache/OfflineLibrary.kt:175-234` | 读 |
| 21 | LRU 上限 300 的纯逻辑索引 + `MAX_ENTRIES` 常量 | `cache/OfflineUrlStore.kt:22-39/84`；`cache/OfflineLibrary.kt:69-96/148` | 读 |
| 22 | 全屏诊断页的既定形态：Dialog + `LaunchedEffect` + `withContext(IO)` | `ui/screen/OfflineCacheOverlay.kt:88-140`；调用点 `ui/screen/UserScreen.kt:268-274` | 读 |
| 23 | `BuildConfig.DEBUG` 的既定用法：只 gate 日志级别与 debug UI | grep → 17 处；代表：`network/RetrofitClient.kt:47`、`qq/QqApi.kt:237`、`ui/screen/UserScreen.kt:367` | grep |
| 24 | 成功 URL 的唯一落盘点 | `player/PlaybackService.kt:922-936`（`rememberFromUrl` 在 `:926`） | 读 |
| 25 | 「播起来了」的另一个（较粗）信号：OfflineLibrary 写入 | `player/PlaybackService.kt:1300-1328`（`player.isPlaying` 闸门在 `:1303`） | 读 |
| 26 | 散列兜底已有单测（确定性、非法值、不溢出标志位） | `source/MusicSourceTest.kt:163/172/182` | 既有单测 |
| 27 | 散列 id 会被当**歌词** `songId` 发给服务端 | `qq/QqApi.kt:380-383`；`qq/QqRequests.kt:108-126` | 读 |
| 28 | `OfflineKeys.key` 形状是 `song:<id>:<level>`，QQ 靠 `2^62` 标志位区分而非 `q` 段 | `cache/OfflineKeys.kt:34/66-76`；`source/MusicSource.kt:82-83`（`OFFLINE_QQ_TAG` **无任何使用点**，grep 命中仅定义行） | grep |

---

## 6. 现有可观测性（问题 4 的完整回答）

### 6.1 QQ 播放链上的全部 `Log.*`（grep 结果穷举）

| 位置 | 级别 | tag | 消息 | 闸门 |
|---|---|---|---|---|
| `qq/QqApi.kt:74` | d | `QqApi` | `search(legacy) '$kw' -> N` | DEBUG |
| `qq/QqApi.kt:80` | d | `QqApi` | `search(musicu) '$kw' -> N` | DEBUG |
| `qq/QqApi.kt:152` | i | `QqApi` | `vkey ok: requested=$level actual=$actualLevel prefix=${fileType.prefix} mid=$mediaMid` | 无（release 可见） |
| `qq/QqApi.kt:175` | w | `QqApi` | `no playable url for qqmusic:<id> at level=$level` | 无 |
| `qq/QqApi.kt:237-245` | d | `QqApi` | `vkey.diag request requested=… uin=… guid=… authstInjected=… filenames=…` | DEBUG |
| `qq/QqApi.kt:262-274` → `:318-358` | d | `QqApi` | `vkey.diag requested=… songMid=… mediaMid=… cookieFields=… sip=… retcode=… chosen=…` + 每个档位一行 `result/purl(空|长度)/tips/echo/mid` | DEBUG |
| `qq/QqApi.kt:198-213` | i/w | `QqApi` | 诊断探针开始 / 结束 / 最高可用档位 | 由 debug UI 触发 |
| `qq/QqApi.kt:386` | w | `QqApi` | `lyric code=… for mid=…` | 无 |
| `qq/QqApi.kt:402` | d | `QqApi` | `lyric mid=… qrc=N trans=N roma=N` | DEBUG |
| `qq/QqMusicSourceProvider.kt:52` | w | `QqMusicSource` | `search failed` | 无 |
| `qq/QqMusicSourceProvider.kt:66-75` | d | `QqMusicSource` | `search '$kw' -> N hits: qqmusic:<id> mid=… media=… '<name>'` | DEBUG |
| `qq/QqMusicSourceProvider.kt:80` | w | `QqMusicSource` | `resolveUrl failed for id=<id>` | 无（异常路径） |
| `qq/QqClient.kt:234-242` | d | `QqClient` | `musicu cookie fields=… loggedIn=… uin=12****89 identityInjected=…` | DEBUG |
| `qq/QqClient.kt:251` | d | `QqClient` | `musicu $module http=$code len=$len` | DEBUG |
| `qq/QqClient.kt:259` | w | `QqClient` | `musicu failed: $module` | 无 |
| `qq/QqClient.kt:289` | d | `QqClient` | `legacyGet http=… len=…` | DEBUG |
| `qq/QqClient.kt:294` | w | `QqClient` | `legacyGet failed` | 无 |
| `source/SourceRouter.kt:62` | w | `SourceRouter` | `unresolvable song source=qqmusic id=<id> (missing sourceId)` | 无 |
| `source/SourceRouter.kt:67` | w | `SourceRouter` | `no provider registered for source=qqmusic` | 无 |
| `ui/viewmodel/PlayerViewModel.kt:1104-1107` | i | `NcrustTrack` | `playSong -> currentTrack=… cacheKey=…` | 无 |
| `ui/viewmodel/PlayerViewModel.kt:1335-1339` | w | `PlayerViewModel` | `no playable url songId=… requested=… origin=… (retryAttempts=…)` | 无 |
| `player/PlaybackService.kt:923` | d | `PlaybackService` | `Playing: $url startPositionMs=…` | 无 |

**没有任何一条日志记录「这次 id 是散列兜底来的」**，也没有任何一条记录「media_mid 缺失、用了 songmid」
（`QqApi.kt:152` 打的是最终采用的 `mid=$mediaMid`，不说明它来自哪一步）。

### 6.2 现有计数器 / 统计

| 检索 | 结果 |
|---|---|
| `AtomicLong` / `AtomicInteger` | 全仓 2 处：`qq/QqApi.kt:99`（搜索 id 生成器，纯工具）、`lyric/LyricRequestGate.kt:31`（歌词限流） |
| 任何「累计次数」持久化 | **无**。`ncrust_offline`（URL 清单 / 离线索引）、`ncrust_lyrics_cache`（歌词）、`ncrust_playback_state`（队列/进度）、`ncrust_settings` 全部是状态，不是计数 |
| QQ 侧统计 | **无** |
| 内存探针计数 | 有，但只在 `PlaybackService` 内部且与 QQ 无关：`probeGateNotStarted` / `probeNotifyBuilds` / `lastPublishedPanelKeys`（`PlaybackService.kt:1255-1264`，媒体面板探针，只打印不落盘） |

### 6.3 是否上传到服务器？—— **没有 QQ 专用上报，但存在一条跨音源泄漏路径**

结论分三层，逐层给证据：

1. **没有分析/上报 SDK**：grep `analytics|Firebase|Bugly|umeng|sentry` 只命中 media3 自己的
   `AnalyticsListener`（`PlaybackService.kt:52/564`，进程内事件回调，不发网络）。
2. **QQ 侧刻意不做上报**，且写进了 KDoc：`qq/QqMusicSourceProvider.kt:40`
   「不把 QQ 的播放行为上报给任何一方（QQ 侧没有对应的 webLog 机制，也不该伪造）」。
3. **⚠️ 但唯一年上报通道 `PlayReporter` 没有音源闸门。** 它是 ncm 的 webLog
   （`player/PlayReporter.kt:24-25`，POST 到 `clientlogusf.music.163.com/api/feedback/weblog`），
   gate 只有两条：

   ```kotlin
   // app/src/main/java/com/takahashirinta/ncrust/player/PlayReporter.kt:47-48
   val cookie = RetrofitClient.getCookie() ?: return
   if (!cookie.contains("MUSIC_U") || songId <= 0) return
   ```

   调用点喂进去的是 `currentSongId.value`：

   ```kotlin
   // app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt:549-554
   val sid = currentSongId.value ?: -1L
   if (sid > 0 && sid != lastReportedSongId && PlayReporter.reachedCompletion(pos, dur)) {
       lastReportedSongId = sid
       PlayReporter.reportPlay(sid, pos, dur, end = "playend", isWifi = isOnWifi())
   }
   ```

   QQ 曲目的 `id` 是 `2^62 | raw`（恒为正），所以**当用户同时登录了 ncm 时，一首 QQ 曲目的
   合成 id 与收听时长会被 POST 到 ncm 的 weblog 端点**。第二个调用点同理
   （`PlayerViewModel.kt:570-580` 的 `onPlaybackEnded`）。

   **这是既有的、非预期的跨音源泄漏，不是本次埋点要引入的东西。**
   影响面（ncm 是丢弃还是记账）未验证，见 §12。给实施者的纪律：
   **QQ 探针一律本地，绝不新增任何网络出口；顺手修不修 PlayReporter 是另一个决策，不在本报告范围。**

---

## 7. 可复用的本地持久化约定（问题 5）

`OfflineUrlStore` 与 `OfflineLibrary` 是同一套写法的两个实例，新计数器照抄即可：

| 约定 | 出处 |
|---|---|
| SharedPreferences 文件名 + 单 key 存一个 Gson JSON 串 | `cache/OfflineUrlStore.kt:107-108`、`cache/OfflineLibrary.kt:176-177` |
| `@Volatile private var index` + `synchronized(this)` 双检装载 | `OfflineUrlStore.kt:110-122`、`OfflineLibrary.kt:179-191` |
| 写盘走 `.edit().putString(...).apply()`，外面套 `runCatching` | `OfflineUrlStore.kt:124-129`、`OfflineLibrary.kt:193-198` |
| 「纯逻辑索引类」与「Android 落盘 object」**分成两个类型**，索引类 JVM 可单测 | `OfflineUrlIndex`（`:22`）/ `OfflineUrlStore`（`:106`）；`OfflineLibraryIndex`（`OfflineLibrary.kt:69`）/ `OfflineLibrary`（`:175`） |
| 有界 LRU：`LinkedHashMap` + `put` 前 `remove` | `OfflineUrlStore.kt:24-39` |
| 上限常量 `MAX_ENTRIES`（300）放在 companion | `OfflineUrlStore.kt:84`、`OfflineLibrary.kt:148` |
| 所有写路径收敛到一个 `mutate(context) { }`（锁内改 + 落盘） | `OfflineUrlStore.kt:136-141` |
| 坏 JSON 一律 `runCatching` 回落空索引，不抛 | `OfflineUrlStore.kt:86-95`、`OfflineLibrary.kt:150-159` |
| 跨版本存活的数据：**新字段可空 + 默认值**，判「老条目」只看 null | `OfflineLibrary.kt:25-34`（把 v1.9.3 的迁移纪律写在类型头） |

**现有 debug/诊断页面：**

| 页面 | 位置 | 形态 |
|---|---|---|
| QQ 取链诊断（唯一 debug-only 入口） | `ui/screen/UserScreen.kt:367-392`（挂载）+ `:1422-1430`（参数 KDoc）+ `:1511-1520`（渲染） | 设置页 QQ 账号块下一行文字，点击 → `QqApi.diagnoseQuality(song)` → 只打 logcat |
| 离线缓存管理（诊断型全屏页的样板） | `ui/screen/OfflineCacheOverlay.kt:106-140`，调用点 `ui/screen/UserScreen.kt:268-274`，入口 `:794` | 全屏 `Dialog(usePlatformDefaultWidth = false)`，`LaunchedEffect` + `withContext(Dispatchers.IO)` 读取，组合期不阻塞 |

`OfflineCacheOverlay.kt:88-92` 的 KDoc 说明了为什么诊断页用 Dialog 而不是导航页：
**Dialog 是独立窗口，天然在播放器卡片之上，不会撞上「播放器死带」那类命中测试问题**
（AGENTS.md「Compose 触摸陷阱」第 2 条）。新探针页照这个形态做。

---

## 8. `BuildConfig.DEBUG` 的既有用法与 debug-only 诊断面的既定做法（问题 6）

grep 全仓 `BuildConfig.DEBUG` 共 **17 处**，只有两种用法：

1. **gate 日志级别/内容**（14 处）—— 代表：`network/RetrofitClient.kt:47`（`HttpLoggingInterceptor`
   只在 debug 挂）、`qq/QqApi.kt:74/80/237/262/401`、`qq/QqClient.kt:233/250/289`、
   `qq/QqMusicSourceProvider.kt:66`、`player/SongUrlFetcher.kt:246`、`player/PlaybackService.kt:1582`。
2. **gate 诊断 UI 的挂载**（1 处，唯一）—— `ui/screen/UserScreen.kt:367`：

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/ui/screen/UserScreen.kt:366-392
// v2.1.4：release 包里为 null ⇒ 这一行不挂载，用户看不到、也点不到。
onDiagnose = if (BuildConfig.DEBUG) {
    { ... QqApi.diagnoseQuality(song) }
} else null,
```

配套的既定契约（照抄这三条即符合本项目风格）：

- **用「不挂载」而不是「透明/禁用」**：`onDiagnose` 为 `null` 时整行不进 Composition
  （`UserScreen.kt:1428`、`:1491-1492` 反复引用 AGENTS.md 触摸陷阱第 1 条：`alpha = 0` 不退出命中测试）；
- **release 里没有调用方**：`PlayerViewModel.currentSongForDiagnostics()` 的 KDoc 明写
  「release 包里没有调用方（入口整行不挂载）」（`PlayerViewModel.kt:1019`）——
  即「debug 专用访问器」可以留在 main 源码里，只要它的唯一调用点被 DEBUG 包住；
- **诊断只读、不改播放状态**：`diagnoseQuality` 不挑档、不降级、不改变任何播放行为
  （`QqApi.kt:180-192` 的 KDoc）。

---

## 9. 播放结果信号：怎样把「用了兜底」与「真的播成功了」关联（问题 7）

按可靠性从强到弱，三个可用信号：

| 信号 | 位置 | 语义 | 可靠性 |
|---|---|---|---|
| **S1 进度前进 ≥ 3000ms** | `PlayerViewModel.kt:545-547`（判据常量 `PlaybackGuard.kt:302`） | 项目自己定义的「确实播出声了」，已用于清零自动跳歌计数 | ★★★ 最强，且**已在 2Hz 热路径上**，复用它不新增调度 |
| **S2 OfflineLibrary 写入** | `PlaybackService.kt:1300-1328`（`updatePlaybackState` → `maybeRecordOfflineLibrary`，`:1333`） | 起播后 500ms 内第一次心跳写一次；闸门是 `player.isPlaying` + URL 带 `ncrustkey`（`:1303-1307`） | ★★ 幂等、已持久化，但只覆盖带离线 key 的 URL（QQ 走 `QqApi.kt:157` 的 `OfflineKeys.withKey`，**覆盖得到**） |
| **S3 播放错误** | `PlaybackService.kt:441-465`（`onPlayerError`）、`:565-575`（`onAudioSinkError`）→ `PlayerViewModel.kt:592-594` → `handlePlaybackError`（`:1396`） | 「这次没播成」的权威信号，带 `PlaybackFailure(errorCode/causeClass/causeMessage)` | ★★★ 用于扣减/标记失败 |

**三者与「兜底」的连接点**：需要一个「本曲是否用了兜底」的**进程内小集合**，
key 用 `TrackKey`（`qqmusic:<合成id>`，散列 id 也是确定性的，直接可用）。
- 写入：`QqSongMapper.fromSongObject` 里发生 A 兜底时（解析期，早于点播，可能提前很久）；
- 消费：S1 的 tick 里用当前 `mediaSongId` 查一下即得「兜底曲目真的播出声了」。
- 注意：解析期到播放期之间可能隔很久（搜索列表里点一首），所以要**有界**（建议环形缓冲 64 条
  + 时间戳），本报告 §9.2 给出具体做法。

**还有一个不需要集合的粗略口径**（零额外状态）：
`QqApi.kt:157` 挂的 key 里就写着 `song:<id>:<level>`，而 A 兜底的曲目 id 落在
`2^62 | hash(mid)`。`SourceIds.isQqId(id)` 为真**不代表**走了兜底（正常 songid 也带标志位），
所以**不能用它当判据** —— 必须显式记录「raw 是否缺失」这一个 bit。
这是本设计里最容易写错的一处，写进单测（§11）。

---

## 10. 埋点设计方案

> 铁律：**非核心组件不得破坏核心播放链路。** 下面每个决定都以这条为准绳。

### 10.1 计数字段（建议全部 `Long`，纯 JVM 类型）

分成三组，**不要混成一组**（混了就无法回答「兜底之后到底播成功没有」）：

```kotlin
// 建议新文件：app/src/main/java/com/takahashirinta/ncrust/qq/QqFallbackStats.kt
// 纯逻辑，无 Android 依赖，JVM 可单测（与 OfflineUrlIndex / OfflineLibraryIndex 同一定位）

data class QqFallbackCounters(
    // —— 解析期（QqSongMapper.fromSongObject）——
    val parsedTotal: Long = 0L,            // 解析出的 QQ 曲目总数（分母）
    val parsedNoSongId: Long = 0L,         // ★ rawSongId <= 0 ⇒ 走了 FNV-1a 散列兜底（分子）
    val parsedNoMediaMid: Long = 0L,       // ☆ file.media_mid 缺失 ⇒ SongItem.mediaId 回落 songmid
    // —— 取链期（QqApi.fetchPlayUrl）——
    val resolveTotal: Long = 0L,           // fetchPlayUrl 被调用次数
    val resolveNoSongMid: Long = 0L,       // ✖ sourceId null/空串 ⇒ 立即 null（无兜底、无重试）
    val resolveMediaMidFallback: Long = 0L,// ☆ 实际用了 songmid 当 media_mid
    val resolveOk: Long = 0L,              // 拿到 purl（含降档）
    val resolveFail: Long = 0L,            // 全档 purl 空 ⇒ null
    val resolveSipFallback: Long = 0L,     // 额外：sip 为空、回落固定 CDN（QqApi.kt:291-301）
    // —— 结果关联（PlayerViewModel 的 2Hz 判据）——
    val fallbackPlaybackConfirmed: Long = 0L, // ★ 兜底曲目进度真的过了 3000ms
    val fallbackPlaybackFailed: Long = 0L,    // ★ 兜底曲目报了 onPlayerError / onAudioSinkError
    val fallbackNeverPlayed: Long = 0L,       // 兜底曲目解析后 24h 内既没确认也没失败（哑火率）
    // —— 元数据 ——
    val firstSeenAtMs: Long = 0L,
    val lastUpdatedAtMs: Long = 0L,
    val schemaVersion: Int = 1,
)
```

**为什么必须有 `fallbackNeverPlayed`**：只统计「兜底次数」会得出一个无法行动的数字。
真正要回答的是「兜底发生率 × 兜底曲目的可播率」——如果兜底出现 5% 但它们全都播不出来，
那是一个 P0 缺陷；如果兜底出现 5% 而可播率与正常曲目一致，那就只是一个统计事实。

### 10.2 埋点位置（精确到行，改动都是 1–3 行）

| # | 位置 | 改动 | 线程 | 频次 |
|---|---|---|---|---|
| I1 | `qq/QqSongMapper.kt:93-94` | 记下 `rawSongId <= 0`；同时把 `mediaMidOf(item, mid)` 提成局部变量，判 `file.media_mid` 是否缺失 | IO（解析响应） | 每首解析出的 QQ 曲目 1 次 |
| I2 | `qq/QqApi.kt:134` | `?: run { QqProbeCounters.onResolveMissingSongMid(); return null }` | IO | 每次取链 |
| I3 | `qq/QqApi.kt:136` | 走 `?: songMid` 那一支时 `onMediaMidFallback()` | IO | 每次取链 |
| I4 | `qq/QqApi.kt:152`（成功）/ `:175`（失败） | `onResolveOk()` / `onResolveFail()` | IO | 每次取链 |
| I5 | `ui/viewmodel/PlayerViewModel.kt:545-547` | 在 `autoSkipGuard.onProgressConfirmed()` **同一分支内**加一次 `onPlaybackConfirmed(currentSongId.value)` | **ExoPlayer 主线程（2Hz）** | 每 tick，但实现内部先用「本曲已确认」短路 ⇒ 实际每曲 1 次 |
| I6 | `ui/viewmodel/PlayerViewModel.kt:592-594` 的 lambda | 加 `onPlaybackFailed(sid)` | 主线程 | 每次失败 |

**I5 的开销论证（必须写进注释，防止后人「优化」掉判据）**：
该分支**已经在每 tick 执行**（`if (currentSongId.value != null && pos >= 3000)`），
新增的只是一次 `Long` 比较与一次 `@Volatile` 字段比较，**不新增集合查找、不新增分配、不新增调度**。
短路条件用「同一 songId 只记一次」的 `@Volatile var lastConfirmedSongId: Long`，
与既有 `lastReportedSongId`（`PlayerViewModel.kt:550`）完全同形。

### 10.3 并发模型（铁律：非核心组件不得破坏核心播放链路）

| 事实 | 结论 |
|---|---|
| 解析与取链在 `Dispatchers.IO`（`QqClient.musicu:137`、`legacyGet:275` 都是 `withContext(Dispatchers.IO)`；`PlayerViewModel.kt:1197` 的 `playJob` 也在 IO） | 计数必须线程安全 ⇒ 用 `AtomicLong`，**不要** `synchronized`（会与 2Hz 主线程争锁） |
| 确认信号在 ExoPlayer 主线程（2Hz ticker，`PlaybackService.kt:1497-1511`） | 同上；`AtomicLong.incrementAndGet()` 无锁、约纳秒级 |
| 计数器可能被 debug 页读取（主线程 Compose） | 读取走 `snapshot()` 返回不可变 `data class` 快照（6 个 `get()`），**不持锁** |
| 需要落盘 | **绝不在埋点点位落盘**。落盘只在两处：① debug 页打开时（用户主动）；② `MainActivity.onStop`（进程可能被杀） |

`AtomicLong` 而不是 `@Volatile var Long`：`+=` 非原子。`LyricRequestGate.kt:20` 的注释
已经把这个取舍写清楚了（「线程安全用 AtomicLong 而不是 @Volatile + synchronized：
这里的操作只有自增」），照抄这条既有结论。

### 10.4 持久化：文件 + key + 路径

**推荐（与仓库既有约定逐条对齐）**：

| 项 | 取值 | 理由 |
|---|---|---|
| 文件 | `ncrust_qq_probe`（新建，`Context.MODE_PRIVATE`） | **不要**塞进 `ncrust_offline`：那个文件的 `clear()` 与「清空离线缓存」是配对的不变量（`UserScreen.kt:297-300`、`OfflineAudioCache.clear`），诊断计数被用户清缓存顺手抹掉就白测了 |
| key | `stats`（**单 key，Gson JSON**） | 与 `ncrust_offline` 的 `urls` / `tracks` 完全同形；加字段时不用加 key |
| 类型 | `object QqProbeStore`（Android 落盘）+ `class QqFallbackCounter`（纯逻辑）+ `data class QqFallbackCounters`（快照） | 与 `OfflineUrlStore` / `OfflineUrlIndex` 的三段式完全一致 |
| 写盘时机 | debug 页打开时 + `MainActivity.onStop`（各一次 `apply()`） | `apply()` 本身异步落盘、不阻塞调用线程；且这两个时刻都不在播放关键路径上 |
| 上限 / LRU | **不需要**（固定字段数，不随曲目增长） | 与两个离线索引不同，这里是定长结构 |
| 版本迁移 | `schemaVersion: Int = 1` + 所有新字段**可空或带默认值**；读不到就是 0 | 照抄 `OfflineLibrary.kt:25-34` 的「加字段 = 加迁移逻辑 = 加单测」纪律；Gson 走 Unsafe、不调用构造函数 |

**备用方案（如果要求「崩溃也不丢」）**：`filesDir/qq-probe/counters.json` 原子写
（写 tmp + `renameTo`）。本仓库**没有**这个先例（`filesDir` 下只有 `offline/audio` 的
media3 SimpleCache），因此**不推荐**引入新约定；SharedPreferences 的 `apply()` 由框架保证
在进程正常退出时落盘，对「统计口径」这个精度足够。

**明确不上传**：新增代码**不得**引用 `RetrofitClient` / `QqClient` / 任何 `HttpURLConnection`。
这一点可以在评审时用一条 grep 证明（同 §6.3 的做法）。

### 10.5 debug 页面怎么读出来

照抄 `OfflineCacheManagerDialog` 的形态（`OfflineCacheOverlay.kt:106-140`）：

```
新文件：app/src/main/java/com/takahashirinta/ncrust/ui/screen/QqProbeOverlay.kt
  @Composable internal fun QqFallbackProbeDialog(onDismiss: () -> Unit)
      var stats by remember { mutableStateOf<QqFallbackCounters?>(null) }
      LaunchedEffect(Unit) {
          val s = withContext(Dispatchers.IO) { QqProbeStore.snapshotAndFlush(context) } // 读 + 落盘一次
          stats = s
      }
      // 纯展示：分母 / 分子 / 百分比，逐项一行；底部一个「清零」按钮（QqProbeStore.clear）
```

挂载点照 `UserScreen.kt:367` 的既定做法：

```kotlin
onShowQqProbe = if (BuildConfig.DEBUG) { { showQqProbe = true } } else null,
// 渲染处同 :1511 —— `if (loggedIn && onShowQqProbe != null)`
// （或独立于登录态：探针与登录无关，建议放在「音质」小节之上、不要求 loggedIn）
```

页面要展示的最小信息集（每行都必须能直接回答一个实施决策）：

```
QQ 解析总数 / 其中无 songid 兜底       : N / M  (M/N = x.x%)
QQ 解析中 media_mid 缺失               : K
取链请求 / 缺 songmid 硬失败 / 成功 / 全部档位失败 : a / b / c / d
取链时 media_mid 回落 songmid          : e
兜底曲目 → 播出声确认 / 播放失败 / 从未确认 : p / q / r
统计窗口：first seen … last updated …（schemaVersion=1）
```

**顺带一条零成本的诊断增强**（可选、非必须）：把 `QqApi.kt:152` 的 `Log.i` 与
`:175` 的 `Log.w` 各补一个 `hashId=<rawId<=0>` 字段，使 logcat 也能区分兜底。
这条**改变 release 日志内容**，是否要做由任务负责人决定（本报告不擅自扩大改动面）。

### 10.6 被否掉的方案（免得后人重走）

| 方案 | 为什么否 |
|---|---|
| 在 `fetchPlayUrl` 里落盘计数 | 落盘 IO 进播放关键路径（`QqApi.kt` 在取链链路上），违反铁律 |
| 用 `Log.i` 逐次打点、事后数日志 | release 包日志会被用户清掉、无时间窗口、无法算比率；且 2Hz 路径上打日志会刷爆 logcat |
| 用 `SourceIds.isQqId(id)` 判「是否兜底」 | **错的**：正常 songid 也带 `2^62` 标志位（`MusicSource.kt:200`）。判据只能是「raw 是否 <= 0」 |
| 把计数写进 `ncrust_settings` | 会被设置页/清缓存逻辑连带处理，且污染用户设置文件 |
| 复用 `OfflineLibrary` 记「哪些兜底曲目播过」 | 那会让离线缓存管理页出现用户看不懂的条目，破坏「列表里的每一行都必须真的能离线播」不变量（`OfflineCacheOverlay.kt:94-97`） |
| 上报到服务器 | 与 `QqMusicSourceProvider.kt:40` 的既有承诺冲突；本报告明确要求不上传 |

---

## 11. 单元测试清单（纯 JVM）

新增两个测试类，全部落在 `app/src/test/java/com/takahashirinta/ncrust/qq/`，
命名沿用仓库既有的反引号中文名风格（如 `qq/QqQualityTest.kt:18`）。

### T1 `QqFallbackCounterTest`（纯逻辑：`QqFallbackCounter` + `QqFallbackCounters` 快照/JSON）

| # | 测试名 | 断言 |
|---|---|---|
| 1 | `无 songid 判据与 SourceIds 完全同源——rawSongId 为 0 负数或超标志位都算` | 对 `0L, -1L, Long.MIN_VALUE, QQ_ID_FLAG, Long.MAX_VALUE` 各调一次 `onTrackParsed(rawSongId)`，`parsedNoSongId` 恰好 +5；对 `1L, 97773L, QQ_ID_FLAG-1` 各调一次，`parsedNoSongId` **不增** |
| 2 | `parsedTotal 是分母——无论有没有 songid 都自增` | 调 N 次，`parsedTotal == N` 且 `parsedNoSongId == M`（M<N） |
| 3 | `media_mid 缺失与 songid 缺失互不污染` | 只调 `onMediaMidMissing()` ⇒ `parsedNoMediaMid==1 && parsedNoSongId==0` |
| 4 | `取链计数与解析计数互相独立` | 只调 resolve 系列 ⇒ `parsedTotal==0`；只调 parse 系列 ⇒ `resolveTotal==0` |
| 5 | `解析期兜底与取链期兜底不是同一个数` | 一次解析兜底 + 一次取链 media_mid 回落 ⇒ 两个字段各为 1，且没有任何字段为 2 |
| 6 | `播放确认幂等——同一 songId 连续 tick 只记一次` | 同一 id 调 100 次 `onPlaybackConfirmed` ⇒ `fallbackPlaybackConfirmed==1`；换 id 再调 ⇒ 2 |
| 7 | `播放失败与播放确认互斥计数` | 同一 id 先确认后失败 ⇒ 两个字段各自 +1（不做互斥，但必须都可见；若实现选择互斥，改为断言失败不计）——**这条要在实现时定死一种语义再断言** |
| 8 | `并发自增不丢数——8 线程 × 1000 次` | 起 8 个线程各调 1000 次 `onPlaybackConfirmed`（不同 id），最终 `fallbackPlaybackConfirmed == 8000` |
| 9 | `快照 JSON 往返——所有字段与顺序都在` | `toJson()` → `fromJson()` 得到相等对象（照 `cache/OfflineLibraryTest.kt:158` 的写法） |
| 10 | `老 JSON 缺字段回落 0/默认值——加字段 = 加迁移逻辑` | 只给 `{"parsedTotal":5}` ⇒ 其余字段为 0、`schemaVersion==1`，且不抛（照 `OfflineLibraryTest.kt:198`） |
| 11 | `坏 JSON 回落空统计而不是抛异常` | `fromJson("{")` / `fromJson(null)` / `fromJson("[]")` ⇒ 全 0（照 `OfflineLibraryTest.kt:189`） |
| 12 | `计数器 API 不接受 Context——从类型上证明它不能落盘` | 编译期约束，写成注释级断言即可（或断言 `QqFallbackCounter` 无 `android.content` 引用：反射检查 `declaredMethods` 参数类型不含 `Context`） |

### T2 `QqSongMapperFallbackTest`（解析期判据，用真实响应片段）

| # | 测试名 | 断言 |
|---|---|---|
| 13 | `id 缺失时仍然产出 SongItem——mid 才是必需字段` | 传去掉 `"id"` 的真实《晴天》条目 ⇒ 非 null、`sourceId=="0039MnYb0qxYhV"`、`SourceIds.isQqId(id)` |
| 14 | `id 缺失时 id 等于 mid 的散列兜底——与 SourceIds 单一来源` | `assertEquals(SourceIds.qqId(0L, "0039MnYb0qxYhV"), song.id)` |
| 15 | `id 为 0 与 id 缺失得到同一个 id——服务端两种写法不可区分` | 两个 JSON（`"id":0` 与无 `id`）映射出的 `song.id` 相等 |
| 16 | `media_mid 缺失时 mediaId 回落 songmid 而不是 null` | 去掉 `file` ⇒ `assertEquals(song.sourceId, song.mediaId)` |
| 17 | `mid 与 songmid 都缺失时返回 null——不产出没有身份的曲目` | `fromSongObject(JSONObject("""{"name":"x"}""")) == null` |
| 18 | `兜底记录与 mapper 返回值同源——同一个对象只记一次` | 通过注入的假 counter 断言：一次 `fromSongObject` 恰好触发 1 次 `onTrackParsed`，且 `rawSongId==0` |

### T3 `QqAttemptsBoundedTest`（补足「有界性」的可执行证据）

| # | 测试名 | 断言 |
|---|---|---|
| 19 | `任何档位的尝试序列都不超过 6 个——一次请求的上界` | 对 `QualityLadder.LEVELS` 全部取值 + 未知值，`QqQuality.attemptsFor(level).size <= 6` |
| 20 | `尝试序列是集合语义——不含重复档位` | `attemptsFor(l).size == attemptsFor(l).toSet().size`（已有 `QqQualityTest.kt:30` 覆盖 `standard`，这里覆盖**全部**档位） |

### T4（可选，纯逻辑）`QqPlaybackOutcomeTest`

| # | 测试名 | 断言 |
|---|---|---|
| 21 | `失败分类只认 errorCode 不够——cause 参与判定` | 复用 `player/PlaybackGuardTest.kt` 的既有 `classifyFailure` 用例风格（避免重复造轮子，仅在需要「兜底曲目 vs 正常曲目」分组时新增） |

**测试纪律**：T1 全部不碰 Android（`QqFallbackCounter` 不依赖 `Context`、不 `Log`）；
T2 用真实响应片段（照 `qq/QqSongMapperTest.kt:22-38` 的夹具写法，只截取用到的字段）；
**T1-6/T1-8 是这套埋点能不能信的核心**：前者保证「每曲一次」不虚高，后者保证并发不丢数。

---

## 12. 未确认 / 不确定

按「影响面 × 不确定性」排序。凡本文用「**未验证**」标注的结论，实施前应真机复测。

1. **QQ 服务端是否真的会在某些接口下发 `id` 缺失或为 0 的曲目 —— 未验证（无真机数据）。**
   代码层面确认 mapper **接受**这种条目（`QqSongMapper.kt:93`），但本仓库没有任何一次实测记录显示
   某条真实响应缺 `id`。**这条决定了本次埋点的分子是不是恒为 0。**
   建议先做一次零改动的真机探针：hooks 放在 `QqSongMapper.fromSongObject`，
   只在 `id <= 0` 时打一条 `Log.w`，跑一遍搜索 / 艺人 / 专辑 / 歌单四条入口后数日志。
   *（这也是唯一一条「不改代码就没法回答」的问题。）*

2. **散列兜底的曲目，歌词请求里的 `songId` 是否被服务端误用 —— 未验证。**
   `QqApi.kt:381-383` 会把散列值当 `songId` 发出去（`songMid` 同时发送）。
   服务端若优先取 `songId`，则这类曲目的歌词会拿错或拿不到；若只认 `songMid`，则无影响。
   本仓库无 QQ 账号，无法实测。

3. **`PlayReporter` 把 QQ 合成 id 发到 ncm 后的服务端行为 —— 未验证。**
   代码事实是确定的（无音源闸门，`PlayerViewModel.kt:549-554` + `PlayReporter.kt:47-48`），
   但 ncm 是**丢弃未知 id** 还是**记成一首不存在的歌**，从客户端无法证明。
   影响：仅统计口径与「是否存在跨源数据泄漏」的判断，不影响本埋点方案。

4. **QQ `vkey` 响应里 `sip` 为空的真实频率 —— 未验证。**
   `QqApi.kt:280-289` 的注释说「真机实测三首免费曲目的 `sip` 数组是空的」，
   但没给占比。若把它计入 `resolveSipFallback`，这个数字只有实测价值、没有决策价值。

5. **`QualityLadder.LEVELS` 的完整取值集合 —— 未逐字核对。**
   本报告引用了 `QqQuality.attemptsFor` 的上界为 6（由 `FALLBACK_TAIL` 与各档 `minOf { rank }`
   推出，`QqQuality.kt:107-129`），并用「`dolby` ⇒ 6」作为最大。若 `QualityLadder.LEVELS`
   含本文未读到的档位，上界可能不同。T3 的测试 19 正是为此写的（它是穷举，不依赖本文的推理）。

6. **`OfflineLibrary.record` 是否覆盖全部 QQ 播放路径 —— 部分确认。**
   QQ 的 URL 会带 `ncrustkey`（`QqApi.kt:157`），所以 `maybeRecordOfflineLibrary`
   的 key 判据（`PlaybackService.kt:1307`）能过。但**车机路径**（`resolveMediaItem`，`:867-877`）
   在 `songRefOf(source, songId)` 时没有 songmid ⇒ 取链失败 ⇒ 这首根本不会起播，
   因此不会进入统计。属于「本来就不该起播」的分支，不影响埋点，但会**让车机路径的
   `resolveNoSongMid` 计数偏高** —— 分析比率时要按 `origin` 分组，别把车机混进手机口径。

7. **本文引用的行号对应 HEAD = `da5c9f8`。** 若实施前仓库前进，`QqApi.kt` / `QqSongMapper.kt` /
   `PlayerViewModel.kt` 的行号需重新核对（尤其 `PlayerViewModel.kt` 是 2279 行的大文件）。

8. **未做的事（明确声明）**：本报告**没有**运行 Gradle、没有编译、没有跑测试、没有连接真机，
   全部结论来自源码阅读与 grep。因此「现有单测通过」这类事实不在本文断言范围内
   （只引用测试文件里已存在的用例名与位置）。
