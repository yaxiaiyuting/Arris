# 探针 · 队列预加载（v3.1.0 · P0-B）

> 采集时间：2026-09-28 02:51 CST ｜ 代码基线：0b4ed2c ｜ 方式：源码审计 + 实测（无臆测）

**判决先行**：预加载的仍然是**下一首**（唯一入口 `preloadNextSong`），本版把「预加载什么」
从 **URL + TTML** 扩到 **URL + LRC + TTML + 封面**；URL 的过期判据收成一个纯函数并支持
两种 TTL 模型；**这四样没有一样进 ExoPlayer 播放列表**，待播槽位不变量没有被触碰
（`PreloadSlot.kt` 与 v3.0.0 逐字节相同，见 §4.1）。

> 行号口径：本文引用的 `PlayerViewModel.kt` / `PreloadCachePolicy.kt` / `PreloadSlot.kt` /
  `MainActivity.kt` / `LyricsCache.kt` 在 `59f3d6c → 0b4ed2c` 之间**一行未动**
> （`0b4ed2c` 只改 `bili/` 下三个源文件与两个测试文件），行号在两个基线上都成立。

---

## 1. 预加载第几首：**下一首**，且「下一首是谁」只有一处定义

入口唯一：`PlayerViewModel.preloadNextSong`（PlayerViewModel.kt:1632-1810）。调用点在 `MainActivity.kt`
里共 **5 处**（`grep -n "preloadNextSong("`）：

| # | 触发时机 | 落点 | 备注 |
|---|---|---|---|
| 1 | 用户点歌 / 切歌瞬间（`playFromQueue`） | MainActivity.kt:1240 | 主路径 |
| 2 | INFINITY 续接一批之后 | MainActivity.kt:1293 | 续接后的第一首之后 |
| 3 | 进入当前曲**最后 60 秒**（`needsPreload` collect） | MainActivity.kt:1449 | `PRELOAD_THRESHOLD_MS = 60_000L`（PlayerViewModel.kt:382）。⚠️ 同处注释仍写「最后 20 秒」（MainActivity.kt:1400），是**过期注释**，代码是 60s |
| 4 | 上一首（`playPrevious`） | MainActivity.kt:1526 | 反向切歌后同样预载 |
| 5 | 队列插入下一首（`insertNext`） | MainActivity.kt:1708 | 插入后重新判定 |

同一首会被 #1 与 #3 各触发一次，**第二次必须幂等**（这正是 v1.5.2 串台修复的形状，
PlayerViewModel.kt:1656-1661 的三道判重：`currentlyPreloadingSongId` :1652、
`nextTrack == currentTrack` :1655、`nextTrack == preloadedTrack` :1661）。

---

## 2. 预加载什么：四样、各自落点与 TTL

| 内容 | 落点 | 存储 / TTL | 代码行 |
|---|---|---|---|
| **URL** | `preloadCache[songId]`（内存 map） | `PreloadCachePolicy`：默认 **5 分钟**固定 TTL，或 **服务端绝对过期时刻**（见 §3）。**不落盘** | 写入 PlayerViewModel.kt:1721-1725；map 声明 :418；TTL 常量 `CACHE_TTL_MS = PreloadCachePolicy.DEFAULT_TTL_MS` :419 |
| **LRC**（含 `tlyric` / `yrc` / `romalrc`） | `LyricsCache.put`（SharedPreferences `ncrust_lyrics_cache`） | LRC 条目**没有 TTL**，只有 200 条 LRU 上限（`LyricsCache.kt:265` 注释） | `prefetchNeteaseLyrics` PlayerViewModel.kt:1601-1617（网络 :1604，只写 `code == 200` :1605-1613） |
| **TTML** | `AmllTtmlClient.prefetch` → 同一张 `LyricsCache` | **7 天 TTL**：`TTML_TTL_MS = 7L * 24 * 60 * 60 * 1000`（`LyricsCache.kt:78-79`），判据 `isTtmlFresh`（:259-260，边界左闭右开） | PlayerViewModel.kt:1682-1687（受 `lyricsTtmlEnabled` 门控 :1682） |
| **封面** | Coil 的 image loader（内存 + 磁盘缓存，尺寸 320px） | 由 Coil 自身策略决定，**本应用不设显式 TTL** | PlayerViewModel.kt:1692-1695 → `ListPrefetch.prefetchCover`（ListPrefetch.kt:159-167） |

三条**有意**的边界：

1. **LRC 预取只对 ncm 做**：`if (track.source != MusicSource.NETEASE || track.id <= 0L) return`
   （PlayerViewModel.kt:1602）。QQ 与 B 站的歌词字段形状不同、不进 `LyricsCache`
   （:1596-1597 注释；B 站那条见 `loadBiliLyrics` 的 KDoc :1932-1935）。
2. **TTML 预取受开关门控**（:1682）：关掉 TTML 时那一条腿根本不加入任务表，不是「发了再丢」。
3. **预取失败完全静默**：三条腿都包在 `runCatching { BoundedParallel.runAll(aux) }`（:1696）里；
   `prefetchNeteaseLyrics` 自身也不抛（:1603）。预取失败不该在日志里伪装成播放故障。

### 2.1 预取的启动方式

- 三条辅助腿在 `preloadNextSong` 内部**独立 `launch`**（PlayerViewModel.kt:1680），
  与下面的取链（:1709-1712）**同跑**，不占用它的时间；
- 并发上限走 `BoundedParallel.DEFAULT_MAX_CONCURRENCY`（= 4，BoundedParallel.kt:86）；
- 取链本身的缓存命中判据是同档 + TTL 内（:1704-1706）。

---

## 3. URL TTL：两种模型与**唯一**判据落点

### 3.1 两种模型

| 模型 | 谁在用 | 判据 | 代码 |
|---|---|---|---|
| **显式过期时刻**（`expiresAtMs != null`） | B 站（及将来任何给 TTL 的音源） | `nowMs < expiresAtMs`（**边界取过期**） | `PreloadCachePolicy.isFresh` :70-73 |
| **固定 TTL**（`expiresAtMs == null`） | ncm / qm（**行为与 v3.0.0 逐字相同**） | `nowMs - entry.timestamp <= defaultTtlMs` | 同上 :74-75 |

- 默认 TTL：**5 分钟**。常量 `DEFAULT_TTL_MS: Long = 5 * 60 * 1000L`（PreloadCachePolicy.kt:50），
  注释明确要求「值必须与 v3.0.0 的 `CACHE_TTL_MS` 逐字相同」。
- 档位判据在两模型之前：`entry.requestedLevel != requestedLevel` ⇒ 不可用（:69）。
  理由（:56-58）：降档重试时把上一档（可能已证明播不出声）的 URL 喂回播放器，是 v2.2.1 修过的 P0。
- 过期条目**不删**，只是判为不可用（:41-43）——`isFresh` 是纯函数，不产生副作用。

### 3.2 显式过期时刻从哪来（B 站）

`SongUrlResult.expiresAtMs`（`SongUrlFetcher.kt:75`）由 Provider 产出：B 站那条从 URL 的
`deadline` 反推并减 60s 安全边距（`BiliParse.expiryFromUrl` BiliModels.kt:244-259、
`SAFETY_MARGIN_MS = 60_000L` :285），`preloadNextSong` 把它原样写进缓存条目
（PlayerViewModel.kt:1723-1724）。

### 3.3 判据的唯一落点：全仓只有两处调用

```
PlayerViewModel.kt:1195   playSong 的快速路径
PlayerViewModel.kt:1705   preloadNextSong 的缓存命中判定
```

两处都传 `defaultTtlMs = CACHE_TTL_MS`（:419）。v3.0.0 是三处各写一遍内联表达式，
本版收成一个纯函数（PreloadCachePolicy.kt:16-31 的 KDoc 记录了原因）。

守卫单测（`player/PreloadCachePolicyTest.kt`，9 个用例全绿）：

| 用例 | 行 | 钉住什么 |
|---|---|---|
| `固定 TTL 内可用 超出不可用（v3_0_0 的既有模型逐字不变）` | :43 | 边界 `now == timestamp + ttl` **可用**（固定 TTL 是闭区间） |
| `默认 TTL 是 5 分钟` | :54 | 常量值 |
| `档位不一致一律不可用（降档重试不得复用上一档的 URL）` | :60 | v2.2.1 的 P0 |
| `没有条目一律不可用` | :69 | null 安全 |
| `显式过期时刻优先于固定 TTL` | :76 | 模型分派 |
| `显式过期时刻到了就不可用（边界取过期）` | :85 | `now == expiresAt` ⇒ 不可用（与固定 TTL 的边界方向**相反**，有意的保守） |
| `已经过期的条目在时间戳很新的情况下也不可用` | :97 | 显式模型不被 timestamp 干扰 |
| `显式过期时刻为 null 时退回固定 TTL（ncm与 QQ 的行为不变）` | :106 | 向后兼容 |
| `两种模型下档位判据都在最前面` | :116 | 判据顺序 |

---

## 4. 与 `PreloadSlot`：**复用，不是新建**

### 4.1 一句话：预加载的四样内容都不进 ExoPlayer 播放列表

| 内容 | 去了哪 | 在 ExoPlayer 播放列表里吗 |
|---|---|---|
| URL | `preloadCache`（内存 map） + `preload_next` Intent | **否**（只有 URL 字符串随 Intent 交给服务，服务侧 `PreloadSlot.decide` 决定是否入队） |
| LRC / TTML | `LyricsCache`（SharedPreferences） | **否** |
| 封面 | Coil 缓存 | **否** |

`PreloadSlot.kt` 与 `v3.0.0-gpl` tag **逐字节相同**（实测：`git diff --stat v3.0.0-gpl --
app/src/main/java/com/takahashirinta/ncrust/player/PreloadSlot.kt` 输出为空；
该文件最后一次改动是 `63916fc`（2026-09-25），早于本版所有提交）。
`decide`（PreloadSlot.kt:80-93）与 `transitionMatches`（:105-119）**一行未改**。

### 4.2 「待播槽位不变量为什么不会被破坏」推理链

不变量（PreloadSlot.kt:17-19）：**ExoPlayer 播放列表里当前项之后至多一首预载项**，
且 `PlaybackService.pendingNext*` 与它一一对应。

1. **唯一入队口没变**：向 ExoPlayer 追加待播项只发生在 `PlaybackService` 收到
   `action = preload_next` 时，而 PlayerViewModel 侧发这个 Intent 仍要穿过
   `preloadNextSong` 的三道判重（:1652 / :1655 / :1661）⇒ 「同一首下一曲预载两次」仍被幂等忽略。
2. **本版新增的三样内容不产生 `addMediaItem`**：LRC/TTML 写的是共享偏好，
   封面走 Coil；它们没有 `mediaId`、没有 `MediaItem`，结构上不可能让播放列表变长。
3. **URL 的预加载仍走原路径**：`preloadCache` 只是**复用**（:1704-1708 命中即不取链），
   不改变 Intent 的形状与顺序（:1792-1803）。
4. **新增的 `expiresAtMs` 只影响「能不能复用」**（判据在 `isFresh`），不影响「要不要入队」：
   即使缓存过期也只是多发一次取链，槽位仍是 `APPEND/REPLACE/IGNORE` 三选一。
5. **播放列表被替换时槽位镜像照旧作废**：`clearPreloadedState()` 在
   `playSong` 缓存命中路径（:1198）、取链成功路径（:1261）、预载接管路径（:1737）
   与 stopService（:2441）四处调用，语义与 v3.0.0 相同。

### 4.3 现有守卫单测清单（`player/PreloadSlotTest.kt`，19 个用例全绿）

| 用例 | 行 | 守什么 |
|---|---|---|
| `append_when_slot_empty` | :32 | 空槽追加 |
| `ignore_when_same_song_and_same_url` | :40 | **幂等**（重复预载不涨列表） |
| `replace_when_same_song_but_new_url` | :49 | 降档重试换 URL |
| `replace_when_different_song` | :58 | 换歌替换 |
| `fall_back_to_url_when_no_song_id` | :66 | 无 id 的老路径 |
| `double_preload_does_not_grow_playlist` | :101 | **列表长度不增** |
| `long_playback_sequence_never_accumulates` | :111 | 长时间播放不累积 |
| `replacing_slot_keeps_playlist_length` | :124 | 替换不改变长度 |
| `transition_ok_when_item_matches_slot` | :135 | 起播项 == 槽位项 |
| `transition_rejected_when_item_is_the_duplicate_previous_song` | :140 | **重复项被拒**（串台根因） |
| `transition_rejected_when_slot_empty` | :148 | 空槽拒绝 |
| `transition_url_fallback_without_ids` | :153 | 无 id 回落 URL |
| `media_id_round_trip` | :162 | mediaId 往返 |
| `netease_media_id_shape_is_unchanged` | :176 | ncm 形状 `song:<id>` 不变 |
| `qq_media_id_carries_the_source` | :192 | 跨源 mediaId |
| `transition_rejects_same_number_on_other_source` | :209 | **跨源同号必须拒绝** |
| `cross_source_transition_is_accepted_when_it_matches` | :234 | 跨源匹配时接受 |
| `transition_rejects_unresolvable_item_when_slot_has_id` | :248 | 解析不出音源 ⇒ 拒绝（不猜 ncm） |
| `transition_url_fallback_still_works_with_source` | :259 | 带音源的 URL 回落 |

---

## 5. 用户手动切歌：预加载如何失效并重新触发

```
① 用户点另一首
   MainActivity.playFromQueue → PlayerViewModel.playSong
② 版本号前进（作废一切在途结果）
   songPlayVersion++                        PlayerViewModel.kt:1107
   val fetchVersion = songPlayVersion       :1108
③ 取消上一次取链
   playJob?.cancel()                        :1237
④ 起新的辅助腿（歌词 + 封面，兄弟协程）      :1146 startAuxiliaryLoad
⑤ 槽位镜像作废（替换播放列表的地方）
   clearPreloadedState()                    :1198（缓存命中）/ :1261（取链成功）
⑥ 上一首的预载协程取消，重新预载新的下一首
   preloadJob?.cancel()                     :1664（preloadNextSong 入口）
   capturedVersion = songPlayVersion        :1663
   …取链返回后：if (capturedVersion != songPlayVersion) 走接管或丢弃   :1726-1780
```

- **失效判据是版本号，不是时间戳**：`songPlayVersion`（:233 注释：每次显式 `playSong` 自增，
  让 `preloadNextSong` 能识别过期）。预载接管那一支在接管时会**再**自增一次（:1732）
  并 `playJob?.cancel()`（:1733），防止并发 `playSong` 的取链结果二次 `setMediaItem` 重播。
- **重新触发由 MainScreen 负责**：切歌路径 #1（MainActivity.kt:1240）与最后 60s 路径 #3（:1449）
  各自会再调一次 `preloadNextSong`，第二次对同一首是幂等忽略（:1661）。
- **`preloadedTrack` 也是槽位身份的一部分**（:402、:1791、:1629）：漏清它会让下一次自动接续
  用**上一首的音源**解释新起播的曲目（:1627-1628 注释）。

---

## 6. 未实测项与复现方式

| 未实测项 | 怎么复现 |
|---|---|
| 「切歌瞬间 LRC 已在盘上」的实际收益（毫秒） | 真机上对比：预取开 / 关时 `loadNeteaseLyrics` 的缓存命中日志（`fetchLyrics cache hit id=…`，PlayerViewModel.kt:2052）出现时刻 |
| B 站曲目的 URL 预加载是否真能命中 | B 站未接入登录、且本版**不给 B 站预取 LRC**（:1602）；需要一台设备启用 B 站开关后播放 `au:<id>`，观察 `preload enqueued: slotTrack=…`（:1808）与随后的 403/成功 |
| TTML 预取的命中率 | `LyricsCache.getTtml` 命中日志 + 7 天 TTL（LyricsCache.kt:202-260）需要跨天观察 |
| 封面 320px 预取对首屏的影响 | 用 macrobenchmark 的 `StartupBenchmark`/`HomeScrollBenchmark` 前后对照（`benchmark/run_benchmark.sh`） |

单测执行证据见 [EVIDENCE.md](EVIDENCE.md)。
