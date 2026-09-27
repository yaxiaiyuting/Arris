# PreloadSlot 现状（v1.5.2 起）

> 采集时间：2026-09-28 02:24 CST ｜ 代码基线：a86d97b ｜ 采集方式：源码审计（无运行时数据）
> **⚠️ 行号口径（重要）**：本文所有行号均按声明的基线 **`a86d97b`** 核对。
> 采集期间工作区被**并行改动**（未提交）：`PlayerViewModel.kt` 在旧 `:334` 处插入了 4 行 `MusicSource.BILIBILI` 分支，
> 因此该文件在工作区里**整体下移 +4 行**（例：`CACHE_TTL_MS` 基线 `:419` → 工作区 `:423`；`playSong` `:1078` → `:1082`；
> `preloadNextSong` `:1540` → `:1544`；`fetchLyrics` `:1820` → `:1824`）。
> **引用 `PlayerViewModel.kt` 时：工作区行号 = 本文行号 + 4（仅该文件、且行号 > 338），或直接按符号名检索。**
> 其余被本文引用的文件（`PlaybackService.kt` / `RetrofitClient.kt` / `SongUrlFetcher.kt` / `PreloadSlot.kt` / `LyricsCache.kt` /
> `ContentCache.kt` / `HomeSnapshot.kt` / `AppWarmup.kt` / `QqClient.kt` / `MainActivity.kt` …）在采集期间**未被改动**，行号可直接定位。


本文只描述**现状**，不含建议。凡代码不能直接证明的，显式标注「（推断）」。

---

## 1. `player/PreloadSlot.kt` 现在做什么（全文 134 行精读）

它是一个**纯判定对象**，自己不持有播放器、不改播放列表。文件头的 KDoc 把它的职责写死了：

> 「本对象只做判定，不碰 player；调用方（`PlaybackService`）负责按 Decision 增删项。」——`player/PreloadSlot.kt:29`

不变量定义在同处（`PreloadSlot.kt:17-19`）：

> 「ExoPlayer 的播放列表里，当前项之后**至多允许一首**预载项，且 `PlaybackService` 的 `pendingNext*` 元数据必须与它一一对应。」

它对外只暴露 5 组纯函数：

| 成员 | 行 | 语义 |
|---|---|---|
| `MEDIA_ID_PREFIX = "song:"` | `:33` | 预载项 mediaId 的前缀常量 |
| `enum Decision { APPEND, REPLACE, IGNORE }` | `:36-45` | 一次 preload 请求相对槽位的取舍：空槽追加 / 换歌或换 URL 先删后加 / 完全相同则幂等忽略 |
| `mediaIdFor(source, songId, url)` / `mediaIdFor(songId, url)` | `:60-61` / `:64-65` | 构造 mediaId；**带音源**（v2.1.5）。`songId <= 0` 时退回 URL 当 id（`:61`） |
| `identityFromMediaId(mediaId)` / `songIdFromMediaId(mediaId)` | `:71-72` / `:74` | 从 mediaId 反解 `(音源, id)`；**解析不出音源返回 null，不猜成网易云**（`:68-69`） |
| `decide(pendingSongId, pendingUrl, incomingSongId, incomingUrl)` | `:80-93` | 槽位占用**只看 `pendingUrl == null`**（`:78-79`、`:86`）；两侧都有 id 时按 `(id, url)` 全等判 IGNORE（`:87-88`），否则退化成只比 URL（`:89-91`） |
| `transitionMatches(pendingSource, pendingSongId, pendingUrl, itemSource, itemSongId, itemUrl)` | `:105-119` | 自动过渡守卫：槽位带 id 时**只认 `(音源, id)`**，起播项没 id 一律拒绝（`:114-116`）；老路径才退回 URL 比对（`:117-118`） |
| `transitionMatches(pendingSongId, pendingUrl, itemSongId, itemUrl)`（网易云重载） | `:125-133` | 仅为不破坏既有单测保留；生产代码走带音源的版本（`:122-123`） |

`decide` 的判据细节：`pendingUrl == null` ⇒ `APPEND`（`:86`）；两侧 id 都 > 0 时 `same = (id 相等 && url 相等)`（`:87-88`），否则 `same = (url 相等)`（`:89-91`）；`same ⇒ IGNORE`，否则 `REPLACE`（`:92`）。

**注意 `REPLACE` 的语义是「同一首歌但 URL 更新（降档重试）也要替换」**（`:40-41`）—— 所以「同 id」并不自动等于 `IGNORE`。

---

## 2. 现在是否已预加载下一首？预加载了哪些内容？

**是，已经预载下一首 —— 但只预载了「URL（+ 播放列表项）」和「TTML 歌词」两样，另外顺带把通知栏要用的封面位图取回来了。**

| 内容 | 是否预载 | 落点 |
|---|---|---|
| **播放 URL** | ✅ | `PlayerViewModel.preloadNextSong`（`:1540`） |
| **歌词** | ⚠️ **只有 AMLL TTML**，且受开关控制 | `PlayerViewModel.kt:1585-1587` |
| **封面** | ⚠️ 有，但目的是通知栏位图，不是 UI 列表/播放器封面 | `PlaybackService.kt:645-647` → `:1169-1189` |

### 2.1 URL：`PlayerViewModel.preloadNextSong` + `PreloadCacheEntry`

主流程（`PlayerViewModel.kt:1540-1703`）：

| 步骤 | 行 |
|---|---|
| 判定键换成 `TrackKey`（避免「网易云 123」与「QQ 123」互吞） | `:1559` |
| 幂等闸门 1：同一首正在取 → return | `:1560` |
| 幂等闸门 2：要预载的就是当前正在播的歌 → return（`allowCurrent` 显式放行单曲循环） | `:1563` |
| 幂等闸门 3（v1.5.2 串台修复）：这一首**已经在待播槽位里** → return，绝不重复 `preload_next` | `:1569` |
| 取消上一次 `preloadJob`，在 IO 上起协程 | `:1572-1574` |
| 无缝开关关掉就直接退出 | `:1577-1580` |
| **TTML 预取**（独立 `launch`，故意不挤占取链） | `:1585-1587` |
| 按当前网络选档位（WiFi / 移动） | `:1588-1591` |
| `preloadCache` 命中（同档 + TTL 内）则跳过网络 | `:1593-1596` |
| 否则 `fetchUrlOfflineFirst(...)` | `:1599-1602` |
| 失败即放弃（等当前歌播完由 `songEnded` 跳歌） | `:1603-1607` |
| 成功：先**无条件写缓存** | `:1611-1613` |
| 版本已前进：同曲接管起播 / 否则丢弃 | `:1614-1668` |
| 正常路径：写槽位镜像 + 发 `preload_next` Intent 给服务 | `:1670-1696` |

`PreloadCacheEntry`（`PlayerViewModel.kt:406-417`）字段：`url`、`actualLevel`、`requestedLevel`（`:411`，「只有档位一致才允许命中缓存」）、`br`、`type`、`songMaxLevel`、`timestamp`（默认 `System.currentTimeMillis()`，`:416`）。

**TTL 常量：`private val CACHE_TTL_MS = 5 * 60 * 1_000L` = 5 分钟，定义在 `PlayerViewModel.kt:419`**（不是 `const`，是实例属性）。两处消费：`playSong` 的命中判定 `:1168`、`preloadNextSong` 的命中判定 `:1595`。

它在 `PlaybackService` 侧的落地：`pendingNext*` 一段字段（`PlaybackService.kt:265-287`），`preload_next` 分支（`:609-654`），最终 `player.addMediaItem(buildPreloadMediaItem(nextUrl, nextSongId))`（`:648`，构造函数 `:1023`）。

### 2.2 歌词：只有 TTML，LRC / yrc **没有**预取

全仓 `AmllTtmlClient.prefetch` **只有一个调用点**：`PlayerViewModel.kt:1586`，且被 `if (lyricsTtmlEnabled.value)` 包住（`:1585`）：

```kotlin
if (lyricsTtmlEnabled.value) {
    launch { AmllTtmlClient.prefetch(getApplication(), songId) }
}
```

`AmllTtmlClient.prefetch`（`lyric/AmllTtmlClient.kt:195-205`）的语义：`LyricsCache.getTtml(context, songId) != null` 就直接返回（幂等，不打网络，`:197`），否则 `fetch` → `LyricsCache.putTtml`（`:198-199`），异常静默（`:200-204`）。

**没有任何代码为下一首预取 LRC / tlyric / yrc / romalrc。** 那几样只在 `fetchLyrics` → `loadNeteaseLyrics` 的 `RetrofitClient.api.getLyric`（`PlayerViewModel.kt:1908`）里拿，而该路径只在**当前曲目**的 9 个触发点上被调用（见 `request-orchestration.md` §2.1）。

### 2.3 封面：有预取，但走的是 PlaybackService 的通知栏路径

`PlaybackService.kt:643-647`：

```kotlin
// 提前加载下一首封面: 无缝切换瞬间任务栏直接是新图,
// 不再出现"新歌标题 + 上一首封面"的过渡窗口
if (!pendingNextArtwork.isNullOrEmpty()) {
    preloadArtwork(pendingNextArtwork!!)
}
```

`preloadArtwork`（`:1169-1189`）用 `Coil.imageLoader(this@PlaybackService).execute(ImageRequest...data(CoverUrls.large(url)).size(1024, 1024))`（`:1173-1177`），结果 `downscaleArtwork` 成位图存进 `pendingNextArtworkBitmap`（`:1181-1183`），切换瞬间由 `onMediaItemTransition` 消费（`:517-519`）、必要时回落到 `loadArtwork(slotArtwork)`（`:545`）。

**走不走 Coil：走。** 且 `Coil.imageLoader(context)` 拿的是 `NcrustApplication.newImageLoader()` 那个全局单例（`NcrustApplication.kt:43-58`，内存缓存 16/24/32MB + 磁盘 100MB @ `cacheDir/image_cache`），所以这次预取会**顺带**写进 Coil 的磁盘/内存缓存。

两点必须如实标注的边界：

1. 预取的 URL 是 `CoverUrls.large`（1080 参数，`:1175`），目标尺寸 1024×1024（`:1176`）。播放器大封面也用 `CoverUrls.large`（`ui/player/PlayerCard.kt:1770`），**URL 与磁盘缓存键一致 ⇒ 磁盘缓存可命中**；但内存缓存的键含尺寸/变换，1024 目标与卡片自己的目标尺寸不一定相同 ⇒ **内存缓存是否命中属于推断，未验证**。列表项用的是 `CoverUrls.small`（640，`ui/components/SongCard.kt:111`、`:210`），**URL 不同，一定不命中**。
2. 这次预取的**触发条件是 `preload_next` 到达服务**（`:645`），也就是 URL 取链成功之后；取链失败（`:1603-1607` 直接 return）时**连封面也不会预取**。

---

## 3. TTL 策略

| 常量 | 值 | 定义处 | 含义 |
|---|---|---|---|
| `CACHE_TTL_MS` | `5 * 60 * 1_000L` = **300 000ms（5 分钟）** | `PlayerViewModel.kt:419` | `preloadCache`（`MutableMap<Long, PreloadCacheEntry>`，`:418`）里那条 URL 的**内存**有效期。命中要求「`requestedLevel == selectedQuality` 且 `now - timestamp <= TTL`」（`:1167-1169`、`:1593-1596`） |
| `PRELOAD_THRESHOLD_MS` | `60_000L` = **60 秒** | `PlayerViewModel.kt:373` | 触发预载的**窗口**：进入当前歌最后 60s 内即置 `needsPreload = true`。实际判定 `if (remaining in 1L..PRELOAD_THRESHOLD_MS)`（`:576`），前置条件是 `gaplessEnabled && dur > 0 && pos > 1_000L && !needsPreload.value`（`:574`） |
| `LyricsCache.TTML_TTL_MS` | 7 天 | `lyric/LyricsCache.kt:79` | TTML 缓存 TTL，与预载槽位无关，但预取走的就是这张表（`AmllTtmlClient.kt:197`） |

两处**过期注释**（事实记录）：`PlayerViewModel.kt:371-372` 的注释说「20s 碰到慢网络会来不及」，但常量值是 60s；`MainActivity.kt:1396` 的注释仍写「最后 20 秒」。

`PRELOAD_THRESHOLD_MS` 只在**一个**地方被读（`:576`），消费方是 `MainScreen` 的 `needsPreload` 收集器（`MainActivity.kt:1402`）。

---

## 4. 待播槽位不变量由谁守

不变量：**ExoPlayer 播放列表里当前项之后至多一首预载项，且 `pendingNext*` 与它一一对应。**

守卫分三层，全在代码里：

| 层 | 位置 | 做什么 |
|---|---|---|
| ① 纯判定 | `player/PreloadSlot.kt:80-93`（`decide`）、`:105-119`（`transitionMatches`） | 给出 APPEND / REPLACE / IGNORE 与「起播项是否就是槽位项」 |
| ② 服务侧执行 | `PlaybackService.kt:616`（`when (PreloadSlot.decide(...))`）；`IGNORE → return`（`:617-623`）；`REPLACE → removePendingItems()`（`:624-630`，实现 `:1050-1054`：`player.removeMediaItems(currentIndex + 1, count)`）；`APPEND → Unit`（`:631`）；随后 `addMediaItem`（`:648`） | 真正增删播放列表项；`IGNORE` 分支**绝不** `addMediaItem` |
| ③ ViewModel 侧镜像 | `PlayerViewModel.kt:1569`（`if (songId > 0 && nextTrack == preloadedTrack) return`），槽位镜像字段 `:377-393`，清空 `clearPreloadedState()`（定义 `:1527-1538`；调用点 `:651`、`:1171`、`:1233`、`:1625`、`:2273`） | 在发 Intent 之前就把重复的 preload 请求挡掉；`setMediaItem` 会替换整个播放列表，所以每次开播都作废槽位镜像（`:1232-1233`） |

过渡守卫（消费端）：`PlaybackService.onMediaItemTransition`（`:467`）里 `reason != AUTO` 直接 return（`:472`），再 `PreloadSlot.transitionMatches(...)`（`:485-492`）；不匹配时**刻意不清槽位**，只拒绝把槽位元数据用在别的项上（`:497-503`）；匹配后把播完的项统一 `removeMediaItems` 掉，列表收敛回「当前 + 至多一首待播」（`:548-550`）。

### 覆盖它的单测

`app/src/test/java/com/takahashirinta/ncrust/player/PreloadSlotTest.kt`（`:27` 起）：

| 用例名 | 行 | 覆盖点 |
|---|---|---|
| `append_when_slot_empty` | `:32` | 空槽 ⇒ APPEND |
| `ignore_when_same_song_and_same_url` | `:40` | 同曲同 URL ⇒ IGNORE |
| `replace_when_same_song_but_new_url` | `:49` | 同曲新 URL（降档）⇒ REPLACE |
| `replace_when_different_song` | `:58` | 换歌 ⇒ REPLACE |
| `fall_back_to_url_when_no_song_id` | `:66` | 无 id 时退回 URL 判等 |
| `double_preload_does_not_grow_playlist` | `:101` | 同一首预载两次，播放列表长度不增长（v1.5.2 串台根因） |
| `long_playback_sequence_never_accumulates` | `:111` | 长序列播放不累积 |
| `replacing_slot_keeps_playlist_length` | `:124` | REPLACE 后长度不变 |
| `transition_ok_when_item_matches_slot` | `:135` | 起播项 == 槽位项 ⇒ 放行 |
| `transition_rejected_when_item_is_the_duplicate_previous_song` | `:140` | 重复项起播 ⇒ 拒绝（宁可停在原处也不显示错歌） |
| `transition_rejected_when_slot_empty` | `:148` | 空槽 ⇒ 拒绝 |
| `transition_url_fallback_without_ids` | `:153` | 无 id 时 URL 兜底 |
| `media_id_round_trip` | `:162` | mediaId 往返 |
| `netease_media_id_shape_is_unchanged` | `:176` | 网易云形状逐字节不变（`song:123`） |
| `qq_media_id_carries_the_source` | `:192` | QQ 形状带音源 |
| `transition_rejects_same_number_on_other_source` | `:209` | 跨源同号 ⇒ 拒绝 |
| `cross_source_transition_is_accepted_when_it_matches` | `:234` | 跨源匹配 ⇒ 放行 |
| `transition_rejects_unresolvable_item_when_slot_has_id` | `:248` | 槽位有 id 而起播项解析不出 ⇒ 拒绝 |
| `transition_url_fallback_still_works_with_source` | `:259` | 带音源版本下的 URL 兜底 |

另有 `app/src/test/java/com/takahashirinta/ncrust/player/QueueInsertTest.kt` 覆盖「插入队列后待播槽位重同步」这条相邻不变量（对应生产代码 `MainActivity.kt:1704` 的调用点与 `player/QueueInsert.kt`）。

---

## 5. 预加载的触发点（`MainActivity.kt` 内 `preloadNextSong` 的调用点）

共 **5 处**：

| # | 行 | 所属函数 | 触发时机 |
|---|---|---|---|
| 1 | `MainActivity.kt:1236` | `playFromQueue`（`:1194`） | **切歌瞬间**：用户点歌/切歌后立刻为「下一首」预载 |
| 2 | `MainActivity.kt:1289` | `launchInfinity`（`:1260`） | 无限模式拉到新一批曲目后，为 `startIdx + 1` 预载 |
| 3 | `MainActivity.kt:1445` | `needsPreload` 收集器（`LaunchedEffect(Unit)` `:1401`，`collect` `:1402`） | **进入当前歌最后 60s 窗口**（`needsPreload` 由 `PlayerViewModel.kt:576-578` 置位）；五种播放模式各算一遍「下一首是谁」（`:1404-1441`） |
| 4 | `MainActivity.kt:1522` | `playPrevious`（`:1356`） | 手动上一首之后 |
| 5 | `MainActivity.kt:1704` | `insertNext`（`:1628`） | 「添加到下一首播放」后重同步槽位（`QueueInsert.shouldPreloadAfterInsert` 门控） |

注意 #1 与 #3 会**对同一首下一曲各调一次** —— 这正是 `PreloadSlot` 的 IGNORE 分支与 `PlayerViewModel.kt:1569` 那道闸门存在的理由（`PreloadSlot.kt:21-27` 记录了这条复现链）。

---

## 6. 现在**没有**做的预加载（诚实清单）

1. **下一首的 LRC / tlyric / yrc / romalrc 一律不预取。** 唯一的歌词预取是 TTML（`PlayerViewModel.kt:1585-1587`，且仅有 `lyricsTtmlEnabled.value == true` 时才发）。开关关闭时，**下一首的歌词预取完全为零**。
2. **下一首封面没有为 UI 预取。** 唯一的预取在 `PlaybackService.preloadArtwork`（`:1169-1189`），目的是通知栏位图，用的是 `CoverUrls.large`；列表项需要的 `CoverUrls.small`（640）不在任何预取路径里。列表进入时也不会预取任何封面。
3. **进入列表页时不会预取前 N 首。** 全仓唯一的「批量封面预取」是冷启动的 `AppWarmup`（`warmup/AppWarmup.kt:159-190`），它只取**首页三个区块各 6 张**（`PREFETCH_PER_SECTION = 6`，`:80`；`COVER_PX = 320`，`:78`），且只在冷启动那一次（`start()` 有 `started` 幂等闸门，`:94-97`）。
4. **预取不含歌曲详情 / 版权信息。** `fetchSongMaxLevel` 的内存缓存（`SongUrlFetcher.kt:230`）只在真正取链命中降级条件时才被填充（`:178-180`），没有人在预载阶段提前问过。
5. **预载不跨进程存活。** `preloadCache` 是实例内 `mutableMapOf`（`PlayerViewModel.kt:418`），URL 的持久化只发生在别的路径（`OfflineUrlStore`，且只在**真正播放成功**时由 `PlaybackService.playUrl` 落盘 —— `PlaybackService.kt:926`）。
6. **预载不预热连接。** `preloadNextSong` 走 `fetchUrlOfflineFirst`，即一次普通 eapi POST；没有任何 TCP/TLS 预连接（详见 `connection-layer.md` §5）。
