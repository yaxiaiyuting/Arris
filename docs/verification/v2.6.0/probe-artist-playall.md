# 探针 · 歌手页「全部播放」（P1）跨源去重可行性

> 对象：`ncrust-gpl/Ncrust`，HEAD = `7a4e33b`（v2.5.6），分支 `master`。
> 本探针**只读**，未修改任何源码；唯一产物是本文件。
> 所有 `file:line` 均指 HEAD `7a4e33b`（依据见 §2.1：本报告引用的文件全部与 HEAD 逐字节相同）。

---

## 1. 结论先行

| # | 问题 | 结论 | 关键证据 |
|---|---|---|---|
| 1 | 歌手页歌单的数据源 | `CatalogAggregator.loadArtist()` 的返回 **`ArtistPage.songs: List<AggregatedSong>`**，活在 `ArtistDetailScreen` 的 `page` 局部状态里；再按 `SourceFilter` 过滤一次得到真正渲染的 `songs`。**无歌单级缓存** | `ArtistDetailScreen.kt:99,136,157-159`；`CatalogAggregator.kt:57-65,123-165` |
| 2 | 既有 play-all 如何建队并装队 | 三条入口最终**全部**汇到 `MainActivity.replaceQueueAndPlay(songs)`；列表级 ▶ 直接换队（无弹窗），专辑/歌单详情页多一层 `PlayAllDialog`（现在播放 / 插播） | `MainActivity.kt:1706,2282-2304,1785-1796,1802-1820,2394`；`PlayAllDialog.kt:24-29,48,77` |
| 3 | 既有模式是 replace 还是 append | **列表级 ▶ = replace**（`playbackQueue = songs; currentQueueIndex = 0; playFromQueue(0)`）；`PlayAllDialog` 的「插播」才走 `insertAllNext`。**`replaceQueueAndPlay` 内部一句去重都没有** | `MainActivity.kt:1706-1714`（全文 8 行，见 §4.2）；`:1738,1761` |
| 4 | 跨源匹配现状（v2.4.0） | 判据 = **曲名（归一化）+ 艺人集合交集 + 时长差 ≤2s + 版本标记**；置信度 `EXACT/HIGH/MEDIUM/LOW/NONE`，`mergeable` 阈值 = **≥MEDIUM**。**单曲级身份已存在**（`MergedTrack` / `AggregatedSong.mergedKeys`），`AggregatedSong` **确实携带**与另一源 `TrackKey` 的配对，但**只带 key、不带对端 `SongItem`**。置信度不足时**两侧各留一行** | `CrossSourceMatcher.kt:249-280,291-314`；`CrossSourceModels.kt:37-65,186-197,214-226`；`CatalogAggregator.kt:364-374,397-420` |
| 5 | 队列身份规则 | 规范入口 `QueueKeys.keyOf(song)` → `SongItem.trackKeyOf()` → `TrackKey.ofSong(song)`（带 bit62 回落）；`SongItem.dedupeKey = trackKeyOf().tag`。`TrackKey.equals` **只看 `(source, id)`**。`QueueInsert.plan` 签名 = `plan(queueKeys: List<TrackKey>, currentIndex: Int, newKey: TrackKey): Plan` | `QueueKeys.kt:74,77`；`SongSourceExt.kt:68-69,77`；`TrackKey.kt:75-78,148-149`；`QueueInsert.kt:155` |
| 6 | 播放时能否同步查到「同曲另一源」 | **不能。** `MatchCacheStore.track()` / `putTrack()` 两个 API 存在但**零调用**（`grep` 退出码 1，正对照 `putArtist/putAlbum` 各有命中）——单曲配对缓存是死代码。全应用只有**聚合网络过程中**算过一次配对，且结果只以 `TrackKey` 留在内存页面状态里 | `MatchCacheStore.kt:129-155`；`CatalogAggregator.kt:136,187`；§2.2、§5.6 |
| 7 | 空列表 / 加载中处置 | 详情页把「空」渲染成占位文案、把 ▶ 的**可空回调传 `null`** 从而不渲染；库页把 ▶ 那一行放进 `LazyColumn`，空列表时整行不存在；网络兜底用 `Toast(loading)` | `ArtistDetailScreen.kt:250-255`；`AlbumDetailScreen.kt:224`；`PlaylistDetailScreen.kt:254`；`DetailScaffold.kt:345-347`；`LibraryScreen.kt:287-291,314`；`MainActivity.kt:1791,1808,2297` |
| 8 | 当前排序键与计算位置 | **按版权可用性稳定排序**：`PLAYABLE(0) > UNKNOWN(1) > MEMBER_ONLY(2) > NO_COPYRIGHT(3)`，同档保持接口原顺序（ncm 行在前、QQ 未吸收行在后）。计算位置：`CatalogAggregator.assembleSongsWithProbe()` 末尾 | `CatalogAggregator.kt:377-426`；`CrossSourceMatcher.kt:333-347` |

**给施工者的一句话**：P1 的「跨源去重」在**当前聚合器里已经做完了**（mergeable 对合成一行、低置信度对保留两行）。
P1 真正缺的是两件小事：① `replaceQueueAndPlay` **不判重**（同源同号重复会原样进队列）；
② 歌手页没有 ▶ 入口，两条 artist 路由也没接 `onReplaceAndPlay`。

---

## 2. 探针方法（跑过的命令 + 原始输出）

全部在 `/home/duanjb666/deepseek/ncrust-gpl/Ncrust` 下执行，只读。

### 2.1 仓库状态（**任务书前提不符，见 §10-1**）

```
$ git rev-parse --short HEAD      → 7a4e33b
$ git status --porcelain
 M app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt
?? app/src/main/java/com/takahashirinta/ncrust/library/SavedSongCodec.kt
?? app/src/main/java/com/takahashirinta/ncrust/library/SavedSongModels.kt
?? app/src/test/java/com/takahashirinta/ncrust/library/SavedSongCodecTest.kt
?? app/src/test/java/com/takahashirinta/ncrust/library/SavedSongSyncTest.kt
?? docs/verification/v2.6.0/
```
`stat` 显示这批 library 文件 mtime = `2026-09-26 22:00:xx`（= 探针执行当刻，另有并发写入者）。
**本报告引用的文件都不在这份清单里 ⇒ 与 HEAD 逐字节相同 ⇒ 行号可信。**

### 2.2 证据块 E（负结论 · 单曲配对缓存的调用点）

```
$ grep -rn "MatchCacheStore.putTrack\|MatchCacheStore.track(" --include=*.kt app/src
exit=1                      ← 零命中（无 stdout、无 stderr）

# 正对照（同类 API，应命中）
$ grep -rn "MatchCacheStore.put" --include=*.kt app/src
.../crosssource/CatalogAggregator.kt:136:  runCatching { MatchCacheStore.putArtist(context, merged) }
.../crosssource/CatalogAggregator.kt:187:  runCatching { MatchCacheStore.putAlbum(context, merged) }
exit=0
```

### 2.3 证据块 N（负结论 · 「铁律 23」不存在）

```
$ grep -rn "铁律 *23" --include=*.md --include=*.kt .          → exit=1（零命中）
$ grep -rn "铁律 *2[3-9]" --include=*.md --include=*.kt .      → exit=1（零命中）

# 正对照 1：铁律 22 存在（5 处命中，样例 3 条）
./app/src/main/java/com/takahashirinta/ncrust/network/RetrofitClient.kt:105:  * ## 教训（已写进 `AGENTS.md` 新铁律 22 的配套条款）
./docs/verification/v2.5.5/EVIDENCE.md:332:⇒ 已写进 `AGENTS.md` 新铁律 22 的配套条款：
./docs/verification/v2.5.6/PROBE-SUMMARY.md:21:本版因此按探针结论施工，并按新铁律 22「无数据不声称优化」逐条留数字。
# 正对照 2：铁律 18 存在
./app/src/main/java/com/takahashirinta/ncrust/player/PlaybackStateManager.kt:146:  * v2.5.5 · A：**字段名必须显式声明**（AGENTS.md v2.5.4 规则 1 / 本版铁律 18）。

# 全仓出现过的铁律编号（去重升序，2-4 条样例见上）
铁律 2 3 4 5 6 7 8 9 11 12 15 16 17 18 19 20 21 22      ← 最大到 22
```
⇒ **仓库里没有 23 号铁律。** 任务书说的那条规则实际是 `AGENTS.md` 的
**「v2.4.0 三条新规则」第 1、2 条**，原文见 §5.1。

### 2.4 证据块 R（归一化实测 · 复用仓库自带的 Python 副本）

```
$ cd docs/verification/v2.4.0 && python3 -c "import sys;sys.path.insert(0,'.');import probe_lib as L;..."
A                          B                     norm(A)         norm(B)         sameName
晴天                        晴天（含前后空白）         晴天             晴天             True
晴天                        晴天！                   晴天             晴天             True
Do You Ever Shine          Do You Ever Shine?    doyouevershine  doyouevershine  True
ＡＢＣ                       ABC                   abc             abc             True
叶惠美（Deluxe）              叶惠美                   叶惠美           叶惠美            True
晴天                        晴天 (Remix)            晴天             晴天             True
你过得好吗                    你过得好吗(伴奏)            你过得好吗        你过得好吗         True
周杰伦                       周杰倫                   周杰伦            周杰倫           False
周杰伦,袁咏琳                  周杰伦                   周杰伦袁咏琳       周杰伦            False
Jay Chou                   jaychou               jaychou         jaychou         True
Secret (加长快板)             Secret (慢板)           secret          secret          True
```
`probe_lib.py` 是仓库自带的 Kotlin 归一化副本，文件头写明「与 `crosssource/NameNormalizer.kt`
**必须逐字对应**」（`probe_lib.py:22-25`）。**这是执行得到的证据**，但它是 Python 副本、不是 Kotlin 运行
（见 §10-4；`hasEditionMarker` 两边已漂移，故本报告只用两边都有的标记词）。

### 2.5 其它

`grep -n` / `sed -n` 逐点核对行号；`git log --oneline -- <file>` 核对历史；`wc -l` 核对规模。均为只读。

---

## 3. 歌手页数据来源与排序

### 3.1 数据源：聚合结果，不是网络原始响应

```kotlin
// ArtistDetailScreen.kt:97-99
val anchor = remember(source, artistId, cachedName) { ArtistKey(source, artistId, cachedName) }
var page by remember(anchor) { mutableStateOf<ArtistPage?>(null) }
// ArtistDetailScreen.kt:136
page = CatalogAggregator.loadArtist(context, effective)
```
```kotlin
// ArtistDetailScreen.kt:157-159  ← 屏幕上真正的列表
val songs = remember(loaded, filter) {
    loaded?.songs.orEmpty().filter { filter.accepts(it.song.musicSource) }
}
```

- `ArtistPage`：`CatalogAggregator.kt:57-65`（`merged` / `albums` / `songs` / `preferredSource` / …）。
- 行类型 **`AggregatedSong`**（`CrossSourceModels.kt:214-226`）：`song` / `availability` /
  `confidence` / `matchReason` / `mergedKeys: List<TrackKey>`。
- 渲染用 `row.song`，LazyColumn key 用 `row.key.tag`（`ArtistDetailScreen.kt:280-302`）。
- **没有歌手歌单缓存**：`ContentCache` 只有 `artistCache`（专辑列表，`ContentCache.kt:56,70,71`），
  歌手页只从它读**艺人名**（`ArtistDetailScreen.kt:90-96`）⇒ 每次进入都是 `page == null` 起步（Loading）。

### 3.2 排序键：按版权可用性稳定排序

```kotlin
// CatalogAggregator.kt:422-426
val ranked = CrossSourceMatcher.rankByAvailability(rows) { it.availability }
```
```kotlin
// CrossSourceMatcher.kt:333-347
fun availabilityOrder(availability: TrackAvailability): Int = when (availability) {
    TrackAvailability.PLAYABLE -> 0
    TrackAvailability.UNKNOWN -> 1
    TrackAvailability.MEMBER_ONLY -> 2
    TrackAvailability.NO_COPYRIGHT -> 3
}
fun <T> rankByAvailability(items: List<T>, availabilityOf: (T) -> TrackAvailability): List<T> =
    items.sortedWith(compareBy { availabilityOrder(availabilityOf(it)) })   // 稳定排序
```
装配顺序（`CatalogAggregator.kt:377-420`）：① 先按**ncm 接口原顺序**出行（行内可能被换成 QQ 那份，
`:389-394 preferOther`）；② 再追加**未被吸收**的 QQ 行（`if (key in absorbedQq) continue`，`:413`）；
③ 整体按可用性稳定排序 ⇒ 同档内「ncm 在前、QQ 在后」。

### 3.3 列表的来源与规模（影响 P1 的边界）

| 侧 | 取数路径 | 上限 |
|---|---|---|
| ncm | `api.search(keyword = anchor.name, type = 1, limit = 30)` + `artists.any { it.name == anchor.name }` 精确过滤 | **≤30**（`CatalogAggregator.kt:508-511`） |
| QQ | `QqCatalogApi.artistSongs(singerMID)` ← `musicu` `get_singer_detail_info`，`take(TRACK_LIMIT)` | **200**（`QqCatalogApi.kt:85-94`，`:47`） |
| 可用性探测 | `probe()` 只探前 `PROBE_LIMIT = 60` 首 | 60（`CatalogAggregator.kt:113-114,471-479`） |

⚠️ **非对称**：`neSongs` 只在**主源是 ncm**时才拉（`CatalogAggregator.kt:150-152`），
即「QQ 主源」形态下歌单**只有 QQ 一侧**（§10-2）。

---

## 4. 既有「播放全部」的调用链（含替换队列的确切函数）

### 4.1 三条既有入口 → 同一个终点

| 入口 | 触发点 | 到 `replaceQueueAndPlay` 的路径 |
|---|---|---|
| 库页·红心歌单 ▶ | `LibraryScreen.kt:314` `PlayAllButton(onClick = onPlayAllLiked)` | `LibraryScreen.kt:89` → `MainActivity.kt:2282` → `:2285`（有缓存）/ `:2301`（无缓存） |
| 库页·歌单/专辑格子 ▶ | `LibraryScreen.kt:468-471,499-502` | `MainActivity.playPlaylistNow` `:1785-1796` / `playAlbumNow` `:1802-1820` |
| 歌单详情页 ▶（经弹窗） | `PlaylistDetailScreen.kt:254` → `:127-134` | `:131 onReplaceAndPlay(songs)` → `NavGraph.kt:257` → `MainActivity.kt:2394` |
| 专辑详情页 ▶（经弹窗） | `AlbumDetailScreen.kt:224` → `:192-199` | `:196 onReplaceAndPlay(songItems)` → `NavGraph.kt:162,187` → `MainActivity.kt:2394` |

```kotlin
// MainActivity.kt:2391-2395   ← 全局唯一接线
MainNavGraph(
    navController = navController,
    onSongClick = { playSongItem(it) },
    onReplaceAndPlay = { replaceQueueAndPlay(it) },
    onInsertNext = { insertAllNext(it) },
```

### 4.2 替换队列 + 起播的**确切函数**：`replaceQueueAndPlay`

```kotlin
// MainActivity.kt:1706-1714（全文，8 行）
fun replaceQueueAndPlay(songs: List<SongItem>) {
    if (songs.isEmpty()) return
    fmMode = false
    playbackQueue = songs
    currentQueueIndex = 0
    if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
    playFromQueue(0)
    expandCard()
}
```
三点必须知道：① **空列表 = 静默 return**（`:1707`）；② **`playbackQueue = songs` 原样装队，一句去重都没有**
（对比 `insertNext` `:1692-1703`、`insertAllNext` `:1744-1758`、`appendAllToQueue` `:1767-1775` 全部走
`QueueKeys.dedupe/missing/rebuild`）；③ `currentQueueIndex = 0` ⇒ 播的必然是入参第 0 项。

### 4.3 起播细节

```kotlin
// MainActivity.kt:1156-1177（节选）
fun playFromQueue(index: Int, origin: PlayOrigin = PlayOrigin.USER) {
    if (index in playbackQueue.indices) {
        currentQueueIndex = index
        if (playMode == QueueModes.SHUFFLE) { /* 重定位乱序游标 :1161-1164 */ }
        val song = playbackQueue[index]
        val (title, artist, artwork) = songParams(song)
        playerViewModel.playSong(song.id, title, artist, artwork,
            sourceKey = song.source, sourceId = song.sourceId, mediaId = song.mediaId, origin = origin)  // :1167
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)                          // :1177
        // :1195-1205 预载下一首 preloadNextSong(...)
```
`expandCard()` = `MainActivity.kt:1516-1520`（400ms 展开播放器卡片）。

### 4.4 完整链（P1 目标形态）与当前缺口

```
PlayAllButton.onClick
  → ArtistDetailScreen 新增 (List<SongItem>) -> Unit 参数
  → NavGraph.kt artist 两段路由（:196-210 / :212-231）透传 onReplaceAndPlay
  → MainActivity.kt:2394  { replaceQueueAndPlay(it) }
  → MainActivity.kt:1706  playbackQueue = songs; currentQueueIndex = 0
  → MainActivity.kt:1712  playFromQueue(0) → :1167 playerViewModel.playSong(...)
  → MainActivity.kt:1177  PlaybackStateManager.saveQueue(...)
  → MainActivity.kt:1713  expandCard()
```
**当前缺口**：`ArtistDetailScreen` 参数表（`:69-79`）**没有** `onReplaceAndPlay`；
`NavGraph.kt:200-209` 与 `:221-230` 也没把它传进去（两段都只有 `onSongClick` /
`onSongInsertNext` / `onSongAppendToQueue` / `onShowSongMenu`）。

### 4.5 最接近 P1 的既有先例（建议照抄）

```kotlin
// AlbumDetailScreen.kt:183-188
// 口径过滤：**两段都过滤**，且「播放全部」用的就是这一份 —— 用户选了「只看某源」
// 却把另一源的歌也塞进队列，等于把这个口径当摆设。
val songs = remember(loaded, filter) { loaded?.songs.orEmpty().filter { filter.accepts(it.song.musicSource) } }
val songItems = remember(songs) { songs.map { it.song } }
```
**是否弹 `PlayAllDialog`**：两种先例都在仓库里 —— 列表级 ▶（库页红心、首页/库页卡片）**不弹窗直接 replace**
（理由见 `MainActivity.kt:2406-2409` 与 `:1778-1784`「▶ 就是立即播放」）；详情页 ▶ **弹窗二选一**。
P1 描述的是「点了就 replace 并起播」，与**列表级**先例一致，且歌手页曲目区与库页红心同形
（都是「页内一个 tab 的列表」）⇒ 建议**不弹窗**。若要给「插播」，`PlayAllDialog`（`PlayAllDialog.kt:24-29`）是现成的。

---

## 5. 跨源匹配现状（v2.4.0 复用评估）

### 5.1 仓库里那条「不确定就不合并」的规则原文

任务书称之为「铁律 23」，实际是 `AGENTS.md` 的 **v2.4.0 三条新规则**第 1、2 条：

> 1. **匹配不准时宁可分开展示，也不能给错专辑 / 错单曲。** …任何跨源合并都必须有**结构判据**
>    （专辑列表重合、曲目名集合重合、时长差），名字只能用来**召回**。
> 2. **跨源身份匹配必须有置信度分级，低于阈值不自动合并。** `MatchConfidence` 的 `mergeable`
>    是**全应用唯一的合并阈值**（`>= MEDIUM`），不要在调用方写 `confidence >= HIGH` 这类比较。
>    **低于阈值的行必须两侧各出现一次**

### 5.2 匹配键与置信度

单曲定级 `CrossSourceMatcher.gradeTrack`（`CrossSourceMatcher.kt:249-280`）：

| 置信度 | 判据（逐条来自代码） | 可否合并 |
|---|---|---|
| `EXACT` | `normalizeName(曲名)` 相等 **且** `artistsOverlap(艺人)` **且** `|Δ时长| ≤ 2000ms` **且** `hasEditionMarker` 一致 | ✅ |
| `HIGH` | 曲名 + 艺人 + 时长一致，**仅版本标记不同** | ✅ |
| `MEDIUM` | 曲名 + 艺人一致，**一侧没有时长**（不可校验） | ✅ |
| `LOW` | 仅曲名 +（时长一致）但**艺人不同**；或 曲名 + 艺人一致但**时长差 >2s** | ❌ |
| `NONE` | 曲名归一化后**不相等** | ❌ |

阈值唯一落点：`CrossSourceModels.kt:55-61`（`mergeable = EXACT || HIGH || MEDIUM`）；
容差常量：`CrossSourceMatcher.kt:102 DURATION_TOLERANCE_MS = 2_000L`。
**文档与代码有一处分歧**：`docs/verification/v2.4.0/probe-song-mapping.md` 规则 4 写「同名 + 艺人不同 ⇒
最多 `MEDIUM`」，代码给的是 **`LOW`**（`CrossSourceMatcher.kt:259-267`）——以代码为准（更严，更符合规则 1）。

### 5.3 单曲级跨源身份**已经存在**

| 结构 | 位置 | 内容 |
|---|---|---|
| `MergedTrack` | `CrossSourceModels.kt:186-197` | `primary: TrackKey` + `aliases: List<TrackKey>` + `confidence` + `reason`；`isDual` |
| `AggregatedSong.mergedKeys` | `CrossSourceModels.kt:214-226` | 「与它合并的**另一源**的 `TrackKey`」；`hasOtherSource` |

**`AggregatedSong` 确实携带 NE↔QQ 配对，但只带 `TrackKey`、不带对端 `SongItem`**：
`assembleSongsWithProbe` 只把 `displaySong` 存进行里（`CatalogAggregator.kt:394-409`），对端那份被
`absorbedQq` 吸收后**从列表消失**（`:413`），其 `SongItem` **没有任何地方留** ⇒ 点击时**无法**改成播对端那份。

### 5.4 置信度不足时今天怎么做：**两侧各留一行**

```kotlin
// CatalogAggregator.kt:368-374
// ★ 只有**真的合并了**（mergeable）的次源曲目才从列表里消失。
//   第一版把「配上了但置信度不够」的也算进去，结果是：一首 LOW 匹配的 QQ 曲目
//   既没有作为合并结果出现、也没有作为独立行出现 —— **凭空少了一首歌**。
val mergeablePairs = pairs.filter { it.verdict.mergeable }
val absorbedQq = mergeablePairs.map { it.other.key }.toHashSet()
```
⇒ **≥MEDIUM 的对 → 合成 1 行**（QQ 行被吸收）；**LOW/NONE 的对 → 2 行都在**。
已有单测钉住：`CatalogAggregatorTest.kt:168-175`（低置信度不许合并）、`:117-127`（未合并的次源曲目单独成行）。

### 5.5 能复用 / 不能复用

| 能力 | 能否复用 | 说明 |
|---|---|---|
| `NameNormalizer.normalizeName / toHalfWidth / stripBrackets / artistsOverlap / hasEditionMarker` | ✅ **必须复用** | 纯逻辑、JVM 可测（`NameNormalizer.kt:33-161`） |
| `CrossSourceMatcher.gradeTrack` / `pairTracks` | ✅ **必须复用** | 「唯一的算法落点」；再写一份就是第二份会漂移的口径 |
| `MatchConfidence.mergeable` 阈值 | ✅ | 不许在调用点写 `>= HIGH`（`CrossSourceModels.kt:59`） |
| `CrossSourceMatcher.availabilityOrder / pickPlayable` | ✅ | tie-break 的现成判据（`:333-338,375-388`） |
| `AggregatedSong.mergedKeys` 反查对端 `SongItem` | ❌ | 对端 `SongItem` 已被丢弃（§5.3） |
| `MatchCacheStore.track/putTrack`（离线同步配对） | ❌ | **零调用**（§5.6） |
| 把聚合结果**降级**成「两条都留」 | ❌ | 违反规则 2（≥MEDIUM 就该合并成一行） |

### 5.6 任务书 Q6：播放时有没有「同步、离线」的配对查询？

**没有。** 证据：

1. **写侧**：`grep -rn "MatchCacheStore.putTrack" app/src` → **exit 1 / 零命中**（§2.2）。
   `putTrack` 只在 `MatchCacheStore.kt:142` 定义、只在测试里被间接覆盖，**生产代码一次都没调用**；
   `putArtist/putAlbum` 各有命中（`CatalogAggregator.kt:136,187`）作正对照。
2. **读侧**：`grep -rn "MatchCacheStore.track(" app/src` → 同样零命中。
   `CatalogAggregator` 只读 `.artist(...)`（`:551`）与 `.album(...)`（`:605`）。
3. **配对只发生在网络聚合过程中**：`assembleSongsWithProbe` 里的
   `CrossSourceMatcher.pairTracks(anchor = neSongs, other = qqSongs)`（`CatalogAggregator.kt:364-367`），
   结果只以 `TrackKey` 留在 `ArtistPage.songs[i].mergedKeys`（内存、随页面销毁）。
4. **播放期唯一能拿到「另一源那一份」的路径是用户手点**：`SongDetailScreen.kt:170-176`
   （两源版本行 → `onPlay(version.song)`）→ `NavGraph.kt:340,357 onPlay = onSongClick` → `playSongItem`。

⇒ 点击「全部播放」那一刻，**只有当前页面已有的 `AggregatedSong` 列表可用**；任何「按曲名+艺人查另一源」
都必须**当场纯函数计算**，或退回网络（不可接受）。

---

## 6. 去重方案设计（纯函数）

### 6.1 定位：**幂等的第二道闸**，不是主路径

由 §5.4：**聚合器已把 mergeable 对合成一行**，所以对当前页面状态而言：

- **轴 A（跨源同曲）**今天通常是 **no-op**，价值是**幂等防线**（一旦聚合器改动 / 加了直出缓存 / 被绕过，
  它保证队列里不会出现两份）；
- **轴 B（同源同号）**今天**真的会干活** —— `replaceQueueAndPlay` 不判重（§4.2），
  而 `pairTracks` 是一对一贪心（`CrossSourceMatcher.kt:291-314` 的 `used`），同源侧若本身有重复
  （ncm 那侧是 `search(limit=30)` 的结果、QQ 那侧是接口原表），重复项会**原样进队列**，
  破坏「队列里同一首歌最多一份」这条事实不变量。

### 6.2 建议签名（落在 `crosssource/` 包，与 `NameNormalizer` 同级约定）

```kotlin
// 建议新文件：app/src/main/java/com/takahashirinta/ncrust/crosssource/PlayAllDedup.kt
// 纯逻辑、无 Android 依赖、JVM 可单测
object PlayAllDedup {

    /**
     * @property songs            去重后、**保序**的曲目列表 —— 直接喂给 replaceQueueAndPlay
     * @property mergedPairs      被合并掉的跨源配对 (留下的 key, 丢弃的 key)；诊断 + 单测断言
     * @property collapsedSameKeys 因「同源同号」被折叠掉的 key；诊断 + 单测断言
     */
    data class Plan(
        val songs: List<SongItem>,
        val mergedPairs: List<Pair<TrackKey, TrackKey>> = emptyList(),
        val collapsedSameKeys: List<TrackKey> = emptyList(),
    )

    /**
     * @param rows           当前页面**已经过 SourceFilter 过滤**的聚合行（顺序 = 界面顺序）
     * @param availabilityOf 该行当前可用性，只用于 tie-break；缺省全 UNKNOWN
     * @param preferred      平级时的偏好音源（传 ArtistPage.preferredSource 或锚点源）
     */
    fun plan(
        rows: List<SongItem>,
        availabilityOf: (TrackKey) -> TrackAvailability = { TrackAvailability.UNKNOWN },
        preferred: MusicSource? = null,
    ): Plan
}
```
调用点（歌手页 单曲 tab）：
```kotlin
val songItems = remember(songs) { songs.map { it.song } }
val availabilityByKey = remember(songs) { songs.associate { it.key to it.availability } }
val plan = remember(songItems) {
    PlayAllDedup.plan(songItems, { availabilityByKey[it] ?: TrackAvailability.UNKNOWN }, preferredSource)
}
if (plan.songs.isNotEmpty()) PlayAllButton(onClick = { onReplaceAndPlay(plan.songs) })
```
（`availability` 与 `preferredSource` 都在手边：`AggregatedSong.availability`、`ArtistPage.preferredSource`
= `ArtistDetailScreen.kt:162`。）

### 6.3 算法（两步，顺序固定）

**第 0 步 · 取身份**（不许内联 `song.id`）：`val key = QueueKeys.keyOf(song)`（= `TrackKey.ofSong`，带 bit62 回落）。

**第 1 步 · 轴 A：跨源配对（只跨源、一对一、可合并才合并）**
```kotlin
val ne = rows.filter { it.musicSource == MusicSource.NETEASE }
val qq = rows.filter { it.musicSource == MusicSource.QQMUSIC }
val pairs = CrossSourceMatcher
    .pairTracks(ne.map(CatalogAggregator::trackCandidateOf), qq.map(CatalogAggregator::trackCandidateOf))
    .filter { it.verdict.mergeable }          // ← 唯一合并闸门，不许自己写阈值
```
复用 `pairTracks` 的**同名分桶 + 贪心一对一 + 时长更近优先**；`mergeable == false` ⇒ **两侧都留**；
**同源两条永不合并**（哪怕名字/艺人/时长全一致）——聚合器的配对本就是跨源一对一，同源重复是数据事实。

**第 2 步 · 轴 B：同源同号折叠 + 保序输出**
```kotlin
val drop = HashSet<TrackKey>()            // 被合并掉的那一份
val seen = HashSet<TrackKey>(); val out = ArrayList<SongItem>(rows.size)
for (row in rows) {
    val k = QueueKeys.keyOf(row)
    if (k in drop) continue
    if (!seen.add(k)) { collapsed += k; continue }   // 同源同号：先出现者胜
    out += row
}
```

### 6.4 合并判据（精确到「哪一对会合」）

一对 (NE 行, QQ 行) 合并 **当且仅当**：
1. 两者 `musicSource` 不同（跨源）；
2. `NameNormalizer.normalizeName(a.name) == normalizeName(b.name)` 且非空（分桶，`CrossSourceMatcher.kt:292`）；
3. `CrossSourceMatcher.gradeTrack(a, b).mergeable == true`，即命中 `EXACT`/`HIGH`/`MEDIUM`（§5.2）；
4. 同一同名桶里两者都还没被别的行用掉（一对一）。
否则：**两条都留在结果里，相对顺序不变**。

### 6.5 tie-break（合并后播哪一份）

| 顺位 | 判据 | 依据 |
|---|---|---|
| 1 | 可用性不同 ⇒ 取 `availabilityOrder` **更小**（更能播）的那份 | 与 `CatalogAggregator.kt:389-394 preferOther`、`CrossSourceMatcher.pickPlayable`（`:375-388`）同一规则 |
| 2 | 可用性平级 ⇒ 取 `preferred` 指定的源 | `CatalogAggregator.preferredSource`（`:441-453`，平级时 = 锚点源） |
| 3 | 仍平级 / `preferred == null` ⇒ **列表里先出现的那份** | 列表顺序已编码聚合器的决定（可用性排序 + ncm 行在前），且**稳定可复现** |

**注意**：顺位 1、2 在今天的页面状态下几乎总已被聚合器执行过（`displaySong` 就是那份），实际生效的是顺位 3；
顺位 1 只在「同一对的两份都出现在入参里」时有意义 —— 那正是幂等防线要处理的形态。
**不引入任何新阈值**：若产品要求「`HIGH` 也不合并」，那是改 §5.2 的全局阈值（独立决策），
不能在 P1 调用点偷偷写（`CrossSourceModels.kt:59` 明确禁止）。

---

## 7. 与 `TrackKey` 队列去重的关系（两个正交的轴）

| | **轴 B：`TrackKey` 轴** | **轴 A：跨源同曲轴** |
|---|---|---|
| 判据 | `TrackKey.equals` = `(source, id)`（`TrackKey.kt:75-78`） | 曲名归一化 + 艺人集合交集 + 时长 + 版本标记（`CrossSourceMatcher.kt:249-280`） |
| 回答 | 「这是不是队列里已有的**同一条记录**」 | 「这是不是**同一个作品**的另一个源录音」 |
| 会不会错 | **不会错**（结构性：`(source,id)` 就是身份） | **会错**（跨源无权威键，见 `probe-song-mapping.md` S1）⇒ 必须分级 + 不合并 |
| 谁负责 | `QueueKeys`（唯一落点） | `CrossSourceMatcher` + `NameNormalizer`（唯一落点） |
| 方向 | **保守**：不同源同 id **绝不**视为同一首 | **激进**：同名同艺人可能合并，置信度不足就放手 |
| 仓库硬规则 | 「队列身份必须用 `TrackKey`，不得用裸 `song.id`」（`AGENTS.md` v2.5.3 规则 2） | 「低于阈值不自动合并」（`AGENTS.md` v2.4.0 规则 2） |

互不替代：轴 B **挡不住**「NE《晴天》+ QQ《晴天》」（`TrackKey` 天然不同，跨源同 id 不去重是**设计**）；
轴 A **挡不住**「同一条 QQ 记录在列表里出现两次」（那是同一份数据重复，且违反「同源两条永不合并」）。

**与 `replaceQueueAndPlay` 的接口**：它内部**不调用** `QueueKeys`。所以正确姿势是**在调用点之前**跑
`PlayAllDedup.plan(...)`，把 `plan.songs` 交给它；**不要**改 `replaceQueueAndPlay` 去加去重 ——
那是 3 个详情页 + 首页/库页 + FM 电台共用的入口（`replaceQueueAndPlay` 共 14 处调用点），
改它等于改全部播放语义。

---

## 8. 单测用例清单（14 例，输入 → 期望）

约定：`NE(id, 名, [艺人], 时长ms)` / `QQ(id, mid, 名, [艺人], 时长ms)`；「输出」指
`PlayAllDedup.plan(...).songs`（`preferred = null`、`availabilityOf` 未给 ⇒ 全 `UNKNOWN`）。
归一化结论来自 §2.4 **实测**；定级结论来自 §5.2 代码规则。

| # | 输入（同一桶） | 命中判据 | 期望输出 | 归属轴 |
|---|---|---|---|---|
| 1 | NE(1,"晴天",[周杰伦],269000) + QQ(2,"m2","晴天",[周杰伦],269000) | 名=、艺人=、Δ=0、无版本标记 ⇒ **EXACT** | **1 条**：NE(1)（先出现者胜） | 轴 A |
| 2 | NE(3,"Do You Ever Shine",[五月天],245000) + QQ(4,"m4","Do You Ever Shine？",[五月天],245000) | 归一化实测均 `doyouevershine`（全角 `？` 被 PUNCT 吃掉）⇒ **EXACT** | **1 条**：NE(3) | 轴 A |
| 3 | NE(5,"ＡＢＣ",[某],180000) + QQ(6,"m6","ABC",[某],180000) | NFKC 实测 `abc == abc` ⇒ **EXACT** | **1 条**：NE(5) | 轴 A |
| 4 | NE(7,"晴天",[周杰伦],269000) + QQ(8,"m8","晴天",[林俊杰],269000) | 艺人集合无交集 ⇒ **LOW**（`CrossSourceMatcher.kt:259-267`） | **2 条**，顺序不变 | 轴 A（拒绝） |
| 5 | NE(9,"晴天",[周杰伦],269000) + QQ(10,"m10","七里香",[周杰伦],299000) | 曲名归一化不等 ⇒ **NONE** | **2 条** | 轴 A（拒绝） |
| 6 | NE(11,"屋顶",[周杰伦,袁咏琳],320000) + QQ(12,"m12","屋顶",[周杰伦,袁咏琳],320000) | 艺人集合交集 `{周杰伦}` ⇒ **EXACT** | **1 条**：NE(11) | 轴 A（多艺人正例） |
| 7 | NE(13,"屋顶",[周杰伦,袁咏琳],320000) + QQ(14,"m14","屋顶",[周杰伦、袁咏琳],320000) | 对侧是**一条**含顿号串：`normalizeName` 吃掉顿号 ⇒ `周杰伦袁咏琳` ≠ `周杰伦` ⇒ 无交集 ⇒ **LOW** | **2 条**（**已知限制**，见 §10-5） | 轴 A（拒绝） |
| 8 | NE(15,"晴天",[周杰伦],269000) + QQ(16,"m16","晴天 (Remix)",[周杰伦],269000) | 归一化实测都 `晴天`；`hasEditionMarker` false/true；Δ=0 ⇒ **HIGH** | **1 条**：NE(15)（⚠️ 是「合并」不是「保留两份」——边界见 §10-6） | 轴 A |
| 9 | NE(17,"晴天",[周杰伦],269000) + QQ(18,"m18","晴天 (Live)",[周杰伦],312000) | 版本标记不同 **且** Δ=43000 > 2000 ⇒ **LOW** | **2 条** | 轴 A（拒绝） |
| 10 | NE(19,"晴天",[周杰伦],null) + QQ(20,"m20","晴天",[周杰伦],269000) | 一侧无时长 ⇒ `durationDelta == null` ⇒ **MEDIUM** | **1 条**：NE(19) | 轴 A（MEDIUM 真实存在） |
| 11 | NE(21,"夜曲",[周杰伦],227000) **两次（同 TrackKey）** | 轴 B：`TrackKey` 相等 | **1 条**（先出现者胜），`collapsedSameKeys == [netease:21]` | 轴 B |
| 12 | NE(22,"夜曲",…,227000) + NE(23,"夜曲",…,227000)（**同源不同号**） | 轴 A 只跨源不合并；轴 B 不同号不折叠 | **2 条**（宁可多留，不误合并） | 两轴皆不动 |
| 13 | 已聚合过的列表（例 1 的输出）再跑一次 `plan` | 无跨源对、无重复 key | **与输入逐字节相同**（幂等） | 幂等 |
| 14 | NE(24,"晴天",[周杰伦],269000) + QQ(25,"m25","晴天",[周杰倫],269000) | 繁简不归一（`NameNormalizer.kt:149-153`；实测 `周杰伦 ≠ 周杰倫`）⇒ 艺人无交集 ⇒ **LOW** | **2 条** | 轴 A（拒绝） |

补充断言（同批）：**空输入** ⇒ `songs` 与 `mergedPairs` 皆空（调用方据此不装队列，`replaceQueueAndPlay`
自己也会 return）；**顺序守恒**（未被合并/折叠的行相对顺序不变）；**纯函数不改输入**
（对齐 `CatalogAggregatorTest.kt:187-192` 的既有约定）；**跨源同号不去重**（NE(123,"A") + QQ(带 bit62 的不同名) ⇒ 2 条）。

测试类建议：`app/src/test/java/com/takahashirinta/ncrust/crosssource/PlayAllDedupTest.kt`
（同目录已有 `NameNormalizerTest` / `CrossSourceMatcherTest` / `CatalogAggregatorTest`，JUnit4 + `org.junit.Assert`，
无 Android 依赖；夹具照 `CatalogAggregatorTest.kt:31-46` 的 `neSong(...)` / `qqSong(...)`）。

---

## 9. 空列表 / 加载中状态的处置

### 9.1 详情页骨架的状态机（歌手页天然覆盖「加载中」）

```kotlin
// DetailScaffold.kt:100-104
val stateKey = when {
    error != null -> DetailScaffoldState.Error
    isLoading && !hasCachedContent -> DetailScaffoldState.Loading
    else -> DetailScaffoldState.Content
}
```
歌手页传 `hasCachedContent = loaded != null`（`ArtistDetailScreen.kt:169`），而**没有歌单缓存**（§3.1）
⇒ 首屏一定 `Loading`（转圈），**`content` lambda 根本不执行** ⇒ 放在列表里的 ▶ 天然不出现。
（`reloadTick` 重试时 `page` 不清空，所以重试期间是 Content 态、按钮仍在。）

### 9.2 空列表：仓库里两种既有写法

**写法 A —— 可空回调 ⇒ 不渲染（专辑/歌单详情页）**
```kotlin
// DetailScaffold.kt:345-347
if (onPlayAll != null) {
    Spacer(Modifier.width(12.dp))
    PlayAllButton(size = 48.dp, onClick = onPlayAll)
}
```
```kotlin
// AlbumDetailScreen.kt:224
onPlayAll = if (songItems.isNotEmpty()) ({ showPlayAllDialog = true }) else null,
// PlaylistDetailScreen.kt:254
onPlayAll = if (songs.isNotEmpty()) ({ showPlayAllDialog = true }) else null,
```

**写法 B —— ▶ 那一行放进 `LazyColumn`，空列表时整行不存在（库页红心）**
```kotlin
// LibraryScreen.kt:287-291
if (savedSongs.isEmpty()) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MetroText(strings.noSavedSongs, color = LocalMetroColors.current.onSurfaceVariant, ...)
    }
} else {
    LazyColumn(...) {
        item { Row { ...; PlayAllButton(onClick = onPlayAllLiked) } }   // :301-316
```

**歌手页已有的空态**（P1 应与它并存，不要另造文案）：
```kotlin
// ArtistDetailScreen.kt:250-255
if (songs.isEmpty()) {
    item { Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
        MetroText(strings.noHotSongs, color = ..., style = TextStyle(fontSize = 16.sp)) } }
} else { /* :256-328 列表 + agg-note */ }
```

### 9.3 建议

- ▶ 放在 `selectedTab == 1`（单曲 tab）分支内、`songs.isNotEmpty()` 那一支 —— 即**写法 B**，
  与 `LibraryScreen.kt:301-316` 同形，且**天然满足**「列表为空就隐藏」；
- 位置取列表顶部（`agg-note` 那一项附近），**不要**放进 `headerActions` 的下半部分 ——
  `DetailScaffold.kt:66-75` 记录了「播放器死带」：折叠态播放器卡片会在屏幕下半部形成**不可见但吃事件**的区域，
  落在带内的按钮点不动；
- 文案**复用既有 `strings.playAllButton`**（zh-CN = `"播放全部"`，`StringsZhCN.kt:258`；`PlayAllButton`
  本来就把它当 `contentDescription`，`SongCard.kt:310-312`）⇒ **不需要新增字符串**、不占构造器槽位
  （`StringsConstructorBudgetTest` 把主构造器钉在 129 / 预算 150；`SourceStrings` 余量按 AGENTS.md 只剩个位数）；
- 「点了没反应」的兜底：`replaceQueueAndPlay` 对空列表静默 return（§4.2），但按钮只在非空时存在 ⇒ 该路径不可达；
  若将来允许空点击，照 `MainActivity.kt:1791,1808,2297` 先例给 `Toast(mainStrings.loading)`。

### 9.4 「加载中」的另一种形态：库页网格里的 ▶ 是无条件渲染的

`LibraryScreen.kt:468-471` / `:499-502`（歌单/专辑格子）**不判空**，因为那里的 ▶ 会去网络拉
（`playPlaylistNow` / `playAlbumNow`，`MainActivity.kt:1785-1796,1802-1820`），未命中缓存时先
`Toast(loading)` 再拉。**歌手页不该走这条路**：它是同步 replace，数据已在内存里。

---

## 10. 未验证项

1. **任务书前提「branch master, clean tree」不符**：探针执行时工作区**不干净**（`LibraryManager.kt` 被改 +
   4 个 library 新文件，mtime = 探针当刻，另有并发写入者）。本报告引用的文件都不在其中（§2.1），行号按 HEAD 有效；
   但**任何**对 library 包的断言都要以干净树重跑为准。
2. **QQ 主源路线今天不可达（读码得出，未真机验证）**：`NavRoutes.artist(source, id)` 只被
   `NavGraph.kt:160,185` 调用，其 `source` 来自 `AlbumDetailScreen.onArtistClick`，而后者在
   `source != MusicSource.NETEASE` 时**恒不触发**（`AlbumDetailScreen.kt:173-181,214`）。⇒「歌手页双源合并」
   在今天只有**ncm 主源**一种形态；QQ 主源下 `neSongs == emptyList()`（`CatalogAggregator.kt:150`），歌单是单源的。
   *未在真机上点过 QQ 艺人入口确认它确实进不去。*
3. **`hasEditionMarker` 的 Python 探针副本与 Kotlin 原文已漂移**：`probe_lib.py:229-234` 的标记词集合是
   `NameNormalizer.kt:60-68` 的**真子集**（缺 `version`/`ver`/`off vocal`/`karaoke`/`加长`/`快板`/`慢板`/
   `重混`/`混音`/`演奏版`/`钢琴版` 等 30 余项）。实测差异：`"XX Version"`、`"Secret (加长快板)"` 在 Python 侧
   `False`、在 Kotlin 侧应为 `True`。本报告用例只用两边都有的标记词（Remix / Live / 伴奏），但这条漂移本身
   违反了 `NameNormalizer.kt:26-31` 与 `probe_lib.py:22-25` 的「必须逐字对应」约定，建议单开修缮项。**Kotlin 侧未运行验证**。
4. **`java.text.Normalizer` NFKC 与 Python `unicodedata.normalize('NFKC')` 的 Unicode 版本差异未验证**：
   用例 3（全角 `ＡＢＣ`）依赖两者行为一致；Android 各 API 级别是否一致也未测。**本探针没有跑过任何
   Kotlin/JVM 测试**（未执行 `./gradlew test`）。
5. **用例 7（顿号/逗号拼接的艺人串）是「推演」而非「实测」**：结论由 `NameNormalizer.artistsOverlap`
   （`NameNormalizer.kt:155-161`，按**列表元素**逐个归一化）+ §2.4 实测的 `周杰伦,袁咏琳 → 周杰伦袁咏琳` 推出，
   **未在 Kotlin 里跑过**。若产品要求「拼串 vs 单名」也能合并，必须先改 `artistsOverlap` 的切分口径
   （例如按 `[、,，/&]` 预切分）——那是改全局匹配口径的决策，不是 P1 能顺带做的。
6. **用例 8（`HIGH` = 版本标记不同 ⇒ 合并）是否可接受，未经产品确认**：代码语义如此（§5.2），但对「全部播放」
   而言把 `晴天` 与 `晴天 (Remix)` 合并成一首意味着**用户少听一首**。若要更严（只有 `EXACT`/`MEDIUM` 才合并），
   必须改 `MatchConfidence.mergeable`（唯一阈值定义）并同步重跑 v2.4.0 的三份探针。
7. **「同源重复」在真实接口里是否会出现未验证**：用例 11/12 是构造的。ncm 那侧是 `search(limit=30)` 的结果
   （`CatalogAggregator.kt:508-511`）、QQ 那侧是 `get_singer_detail_info` 原表 —— **没有实测过这两份数据里是否存在
   同 id 重复**。轴 B 的价值不依赖这个概率（结构性防线），但「今天会不会真踩到」未测。
8. **未跑任何构建/单测**：§8 的 14 条用例是**设计**（归一化部分有执行证据，其余为读码推演），
   落成 `PlayAllDedupTest.kt` 后才算「单测覆盖」。
9. **`ArtistDetailScreen` 列表 key 用的是 `TrackKey.fromSong`（非 `ofSong`）**：`ArtistDetailScreen.kt:280`
   `key = { it.key.tag }`，而 `AggregatedSong.key = TrackKey.fromSong(song)`（`CrossSourceModels.kt:222`），
   它信 `source` 字符串、缺失回落 ncm。理论上「source 丢失的 QQ 行」会与 ncm 行 key 相同 ⇒ `LazyColumn`
   key 冲突。**未验证该形态在歌手页是否可能出现**（同路径上 `QueueKeys.keyOf` 用的是 `ofSong`，两处口径不一致，
   值得单独记一笔）。
10. **并发写入者可能改动本报告引用的文件**：探针期间 library 包正在被写。若后续有人改了
    `crosssource/` / `player/` / `source/` 下的文件，§1 与各节行号需按新 HEAD 重核。
