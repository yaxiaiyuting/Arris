# probe-search-history.md · v2.5.4：搜索历史「音源丢失」的根因、可证明的推断规则与迁移设计空间

> **探针方法**：只读源码审计（`read`/`grep`）+ 仓库外 Gson 2.10.1 行为实测（`/tmp/gsonprobe/Probe.java`、`Probe2.java`）+ 位运算算术。
> **探针 HEAD**：`da5c9f8`（v2.5.3-gpl，`versionCode = 44`，`versionName = "2.5.3-gpl"`，工作区干净）。
> **本文件不含任何代码改动**，`app/src/**` 一个字节未动；所有结论都可由本文件的 `file:line` 复现。
> **铁律**：未在真机/模拟器上跑过 App（本探针是 U7 前的静态阶段），凡涉及「用户看到什么」的推断均标为**推断**并在 §13 列出边界。

---

## 0. 结论先行

| 问题 | 结论 |
|---|---|
| 搜索历史存了什么？ | 每段（songs/albums/artists）一个 `List<HistoryItem>` 的 JSON 串。`HistoryItem` **只有 5 个字段**：`id` / `title` / `coverUrl` / `subtitle` / `timestamp`（`SearchHistoryManager.kt:19-25`） |
| 音源在哪一步丢的？ | **两步都丢**：① 写：`addSong` 只把 `song.id/name/album?.picUrl/artists[0]?.name` 搬进 `HistoryItem`，`source`/`sourceId`/`mediaId` 三个字段**从未被读取**（`SearchHistoryManager.kt:27-35`）；② 读：`toSongItem()` 重建 `SongItem` 时只填 `id/name/artists/album/duration`，音源三件套全部落到声明默认值 `null`（`SearchScreen.kt:749-755`） |
| 是不是「QQ 歌被当成 ncm」？ | **是，但只在「字符串口径」上**。`SongItem.musicSource` 读 `source` 字符串，`null` ⇒ `NETEASE`（`SongSourceExt.kt:21-22`+`MusicSource.kt:47-48`）；这一口径被 `SongTags` 角标、`songIdForRoute` 路由、`LibraryManager` 收藏/点赞使用。**「结构口径」（`TrackKey.ofSong`）不会认错**：QQ 的 id 带 bit62，`TrackKey.of(null, id)` 仍推出 `QQMUSIC`（`TrackKey.kt:105-109`） |
| 那点了历史里的 QQ 歌会怎样？ | **认得出是 QQ、但没有 songmid ⇒ 取不到链**。`playSong` 用 `songRefOf(track.source=QQMUSIC, id, sourceId=null)` 造 ref（`PlayerViewModel.kt:1100-1103`），`isResolvable == false`（`SongSourceExt.kt:31-32`）⇒ `SourceRouter.resolveUrl` 直接返回 null（`SourceRouter.kt:61-64`）⇒ `onUrlUnavailable` ⇒ `PlayOrigin.USER` 可跳歌（`PlaybackGuard.kt:153-154`），音乐卡片上还会弹一条**方向错误**的「可切到 ncm」提示（`PlayerViewModel.kt:327-339`） |
| 唯一**可证明**的推断是什么？ | 「id 带 bit62 ⇒ 该曲目属于 qm」（`MusicSource.kt:188-189`）。它是**在「全部 QQ id 都由 `SourceIds.qqId()` 产出」这条不变量成立时的结构性结论**，不是抽样启发式；但它**推不出 songmid**，所以只解决「别问错平台」，不解决「能不能播」（`MusicSource.kt:184-186` 自己写明了这一点） |
| 老条目该怎么迁移？ | **加 `source: String? = null` + `sourceId: String? = null`（+ 可选 `mediaId`）**；写路径**一律写规范化的 `musicSource.key`**（ncm 也要显式写 `"netease"`）；读路径 `source == null` ⇒ 老条目 ⇒ 用 `SourceIds.sourceOfId(id)` 推断，**推断出 QQ 但没有 songmid 的条目按「信息不完整」处理**（不可直接播、UI 给标识），`sourceId` 缺字段**绝不用任何启发式补**。因为该表 **14 天 TTL、每段 ≤10 条**（`SearchHistoryManager.kt:12-13`），老数据会在 14 天内自然消失 —— **不需要破坏性迁移** |

---

## 1. 证据总表（断言 | 证据（file:line）| 方法）

| # | 断言 | 证据（file:line） | 方法 |
|---|---|---|---|
| 1 | SharedPreferences 文件名 `search_history`，三个 key：`songs`/`albums`/`artists` | `library/SearchHistoryManager.kt:11`、`:92-96` | 读码 |
| 2 | `HistoryItem` 只有 5 个字段，`timestamp` 有默认值 `System.currentTimeMillis()` | `library/SearchHistoryManager.kt:19-25` | 读码 |
| 3 | `MAX_ITEMS = 10`，头插 + 超限截断 | `library/SearchHistoryManager.kt:12`、`:78-79` | 读码 |
| 4 | `TTL_MS = 14 天`；`add` 与 `getAll` 两处都按 TTL 剪枝，`getAll` 剪完还会写回 | `library/SearchHistoryManager.kt:13`、`:76`、`:87-88` | 读码 |
| 5 | 写歌曲历史时**只**读 `id` / `name` / `album?.picUrl` / `artists[0]?.name` 四个值 | `library/SearchHistoryManager.kt:27-35` | 读码 |
| 6 | 读回时 `toSongItem()` 不传 `source`/`sourceId`/`mediaId` ⇒ 三者均为 `null` | `ui/screen/SearchScreen.kt:749-755` | 读码 |
| 7 | 历史记录的**唯一**消费者是 `SearchScreen`（无第二处调用点） | `grep -rn SearchHistoryManager app/src/main` 的全部 31 处命中，除 `baseline-prof.txt:30`、`AppWarmup.kt:90`（prefs 预热）、`TrackKey.kt:119`（注释）外全在 `SearchScreen.kt` | 读码 |
| 8 | 点历史条目 ⇒ `onSongClick(song)` ⇒ `MainActivity.playSongItem` | `SearchScreen.kt:253-257`、`:263`；`MainActivity.kt:2268-2269`、`:1488` | 读码 |
| 9 | 「添加到下一首」用同一个重建对象 | `SearchScreen.kt:268` | 读码 |
| 10 | 「加入库」用同一个重建对象 | `SearchScreen.kt:275` | 读码 |
| 11 | 播放时音源取自 `song.source`，id 取自 `song.id` | `MainActivity.kt:1139-1148`（`:1144 sourceKey = song.source`） | 读码 |
| 12 | `TrackKey.of(sourceKey=null, id)` 会先看 bit62 再回落 ncm | `source/TrackKey.kt:99-111`（`:105-109`） | 读码 |
| 13 | `TrackKey.fromSong` 信字符串、`ofSong` 走 `of`；新代码应用 `ofSong` | `source/TrackKey.kt:113-127`、`:129-149` | 读码 |
| 14 | `QueueKeys.keyOf` = `song.trackKeyOf()` = `TrackKey.ofSong`（队列判重已收敛） | `player/QueueKeys.kt:74`；`source/SongSourceExt.kt:77` | 读码 |
| 15 | QQ id 由 `qqId()` 产出：`QQ_ID_FLAG or raw`，`QQ_ID_FLAG = 1L shl 62` | `source/MusicSource.kt:164`、`:198-201` | 读码 |
| 16 | `sourceOfId` 的注释明确自称「结构性、不会误判，而不是启发式」 | `source/MusicSource.kt:170-189`（`:172-174`） | 读码 |
| 17 | ncm id 量级声明：`1e6~3e9`、`< 2^40`、`永远不可能触到 2^62` | `source/MusicSource.kt:149-150`；`player/QueueKeys.kt:46-48` | 读码 |
| 18 | ncm id 不触 bit62 的**边界值**被单测钉死（含 `QQ_ID_FLAG - 1`、`9_999_999_999`） | `app/src/test/.../source/TrackKeyTest.kt:169-182` | 读码 |
| 19 | 全部 QQ `SongItem` 的唯一生产点是 `QqSongMapper.fromSongObject`：`id = qqId(rawId, mid)` + `source = "qqmusic"` + `sourceId = mid` | `qq/QqSongMapper.kt:93-94`、`:115-131`（`:116`、`:124-126`） | 读码 |
| 20 | 另一处合成 id：`QqCatalog.syntheticId`（与 mapper 同一套规则） | `qq/QqCatalog.kt:347-352` | 读码 |
| 21 | 搜索结果是双源聚合：QQ 侧经 `SourceRouter.searchSongs(QQMUSIC, …)` 追加 | `ui/viewmodel/SearchViewModel.kt:175-188`、`:143-165`；`qq/QqMusicSourceProvider.kt:50-51` | 读码 |
| 22 | `SongItem` 的音源字段：`source: String? = null`、`sourceId`(`mid`)、`mediaId`(`media_id`)，**没有 `sourceLabel` 字段**（音源文案是函数 `SongTags.sourceLabel`） | `network/SearchResponse.kt:22-115`（`:36`、`:44`、`:54`）；`ui/components/SongTags.kt:62-65` | 读码 |
| 23 | `AlbumSearchItem`/`ArtistSearchItem` **没有**任何音源字段（今天 albums/artists 只搜 ncm，故无此 bug） | `network/SearchResponse.kt:163-183`；`SearchViewModel.kt:209-222` | 读码 |
| 24 | QQ 取链**强制**要 songmid：`val songMid = song.sourceId ?: return null` | `qq/QqApi.kt:133-136` | 读码 |
| 25 | 取链前的闸门：不可解析直接返回 null，**绝不退回 ncm 取链** | `source/SourceRouter.kt:51-71`（`:60-64`） | 读码 |
| 26 | 取链失败走 `onUrlUnavailable`；USER 来源**可跳歌**，并先弹「可切另一源」提示 | `PlayerViewModel.kt:1334-1380`（`:1367-1373`）；`player/PlaybackGuard.kt:153-154`；`PlayerViewModel.kt:327-339` | 读码 |
| 27 | 收藏路径也只用裸 id：`songs.none { it.id == song.id }` + `pushLike(song.id)` | `library/LibraryManager.kt:196-207`（`:200`、`:205`）、`:185-187` | 读码 |
| 28 | 单曲详情路由的 id 串按 `song.musicSource == QQMUSIC` 决定要不要 `qqRawId` 反解 —— 历史重建对象的 `musicSource` 是 NETEASE ⇒ 走 else 分支 | `MainActivity.kt:663-682` | 读码 |
| 29 | 历史去重键是**裸 id**：`items.removeAll { it.id == id }` / `items.removeAll { it.id == item.id }` | `SearchHistoryManager.kt:64`、`:77` | 读码 |
| 30 | LazyColumn 的 item key 也是裸 id：`key = { "s_${it.id}" }`（albums/artists 同理） | `SearchScreen.kt:252`、`:304`、`:339` | 读码 |
| 31 | Gson 默认 `serializeNulls=false` ⇒ **null 字段整条 key 都不写**；实测落盘形如 `{"id":…,"title":…,"timestamp":…}` | `/tmp/gsonprobe/Probe.java`（Gson 2.10.1，版本号见 `app/build.gradle.kts:324`） | **实测** |
| 32 | 真实 `HistoryItem` 形状（参数不全有默认值 ⇒ 没有无参构造器）⇒ Gson 走 UnsafeAllocator，**新增字段读出来是 JVM 默认值（对象 = null），声明处的初始值不生效** | `/tmp/gsonprobe/Probe2.java`（A 组 `source = null`；B 组有隐式无参构造器时 `source = NEW_FIELD_INITIALIZER`） | **实测** |
| 33 | 迁移规则（新字段可空+默认值；缺失=老条目；缺失按 miss 自愈一次；判据抽纯函数+单测；能不加就不加） | `AGENTS.md:400-419` | 读码 |
| 34 | 探针算术：`1L shl 62 = 4611686018427387904`；`qqId(97773,"…") = 4611686018427485677`；掩码反解无损；`3e9 < 2^62` | `python3`（见 §3 末） | **实测（算术）** |
| 35 | 既有单测已经**点名**「搜索结果进历史记录那条路只存得下 id」并钉住两种口径的分叉 | `app/src/test/.../player/QueueKeysTest.kt:90-101`（`:98-100`） | 读码 |
| 36 | 该表**没有**任何 JVM 单测（`app/src/test` 全文无 `SearchHistory*`） | `find app/src/test -name '*.kt'` 的 83 个文件清单（其中无 `SearchHistory*`） | 读码 |
| 37 | 落盘状态会被冷启动恢复并当队列用（历史重建对象还要能被 `adoptTrackIdentity` 接住） | `MainActivity.kt:1056-1079` | 读码 |

---

## 2. 当前存储结构（逐字段）

`library/SearchHistoryManager.kt:10-113` 全文即是全部实现。逐项：

| 项 | 值 | 证据 |
|---|---|---|
| 单例 | `object SearchHistoryManager` | `:10` |
| prefs 文件 | `"search_history"`（`Context.MODE_PRIVATE`） | `:11`、`:99-100`、`:110-111` |
| key | `TYPE_SONG(1) → "songs"`、`TYPE_ALBUM(10) → "albums"`、`TYPE_ARTIST(100) → "artists"`（`else` 分支兜底成 artists） | `:15-17`、`:92-96` |
| 每段上限 | `MAX_ITEMS = 10`；`items.add(0, …)` 头插后 `subList(MAX_ITEMS, size).clear()` | `:12`、`:78-79` |
| TTL | `14L * 24 * 60 * 60 * 1000`；`add` 时剪（`:76`）、`getAll` 时剪并**写回**（`:87-88`） | `:13` |
| 去重 | 写前 `items.removeAll { it.id == item.id }`（**裸 id**，不分音源） | `:77` |
| 删除单条 | `items.removeAll { it.id == id }`（**裸 id**） | `:64` |
| 清空分段 | 直接写空列表 | `:68-70` |
| 序列化 | `Gson()` 默认实例（无 `serializeNulls`、无自定义 `TypeAdapter`）；`TypeToken<MutableList<HistoryItem>>` | `:102-103`、`:111` |
| 反序列化失败 | 整段丢成空列表（`catch (_: Exception) → mutableListOf()`） | `:104-106` |
| 冷启动预热 | `AppWarmup.PREFS_FILES` 里就有 `"search_history"`（IO 线程先触碰、把 XML 解析挪出主线程） | `warmup/AppWarmup.kt:84-92` |

**写入的四个值**（`addSong`，`SearchHistoryManager.kt:27-35`）：

```kotlin
fun addSong(context: Context, song: SongItem) = add(
    context, TYPE_SONG,
    HistoryItem(
        id = song.id,                        // ← 唯一带身份的值（QQ 曲目这里是 bit62 合成 id）
        title = song.name,
        coverUrl = song.album?.picUrl,
        subtitle = song.artists?.firstOrNull()?.name
    )
)
```

`song.source`、`song.sourceId`（QQ 的 songmid）、`song.mediaId`（QQ 的 media_mid）**在这个函数里一次都没出现** —— 这是「音源丢失」的写入侧根因。`addAlbum`（`:37-45`）、`addArtist`（`:47-55`）同形，但它们的源对象本来也没有音源字段（§1 行 23）。

**读回**（`SearchScreen.kt:749-755`）：

```kotlin
private fun SearchHistoryManager.HistoryItem.toSongItem(): SongItem = SongItem(
    id = id,
    name = title,
    artists = subtitle?.let { listOf(ArtistItem(name = it)) },
    album = AlbumItem(id = null, name = null, picUrl = coverUrl),
    duration = null
)
```

`SongItem` 的 `source`/`sourceId`/`mediaId` 都有默认值 `null`（`network/SearchResponse.kt:36`、`:44`、`:54`），所以这里**编译通过且静默** —— 这是读取侧根因。附带损失：`album.id = null`、`duration = null`（历史条目连专辑 id 与时长都没有）。

---

## 3. 当前落盘 JSON 形状（Gson 实测）

`save` 用的是裸 `Gson()`（`SearchHistoryManager.kt:111`），Gson 默认 **`serializeNulls = false`** ⇒ **null 字段整条 key 都不写**。实测（`/tmp/gsonprobe/Probe.java`，Gson 2.10.1，字段声明顺序与 `HistoryItem` 完全一致）：

```
A. 当前落盘 JSON（Gson 默认 serializeNulls=false）：
   [{"id":5257138,"title":"屋顶","timestamp":1790404955091},
    {"id":4611686018427485677,"title":"晴天","coverUrl":"http://c/1.jpg","subtitle":"周杰伦","timestamp":1790404955091}]
```

即真实落盘形状是**两种 key 集合**：

| 情形 | key 集合 |
|---|---|
| 无封面、无歌手（ncm 常见） | `id`、`title`、`timestamp` |
| 有封面与歌手（QQ 常见，`QqSongMapper.kt:104-113` 会拼封面） | `id`、`title`、`coverUrl`、`subtitle`、`timestamp` |

**对迁移的两个直接推论**：

1. 「字段缺失」在这张表里**本来就是常态**（连今天已有的 `coverUrl`/`subtitle` 都经常缺），所以判「老条目」必须**按字段判**、不能按 key 数量判；
2. 如果新字段写成 `source = song.source`（ncm 那侧是 `null`），**ncm 条目也会缺这个 key**，于是「缺 key = 老条目」这条判据对新数据永远为真 —— 这是迁移里最容易写错的一步（正确写法见 §11.2）。

两条真实样本（QQ 合成 id 的十进制，算术实测）：

```
qqId(97773, "0039MnYb0qxYhV") = 4611686018427485677     # = 2^62 | 97773
qqRawId(4611686018427485677)  = 97773                   # 掩码 (QQ_ID_FLAG - 1) 无损
```

---

## 4. 全部调用点与「HistoryItem → 可播放对象」的路径

### 4.1 调用点清单（`grep -rn SearchHistoryManager app/src/main`，共 31 处命中，去重后如下）

| 位置 | 调用 | 拿到什么 / 做什么 |
|---|---|---|
| `SearchScreen.kt:140-142` | `getSongs/getAlbums/getArtists` | 初次组合时的三段历史（`remember` 初值） |
| `SearchScreen.kt:144-148` | 同上（`refreshHistory()`） | 删除/清空后重读 |
| `SearchScreen.kt:150-152` | `refreshHistory()` | `LaunchedEffect(query)`：query 变空时刷新 |
| `SearchScreen.kt:246` | `clearSection(TYPE_SONG)` | 清空「单曲」段 |
| `SearchScreen.kt:284` | `remove(TYPE_SONG, item.id)` | 删一条单曲历史（按 **裸 id**） |
| `SearchScreen.kt:298` / `:319` | `clearSection(TYPE_ALBUM)` / `remove(TYPE_ALBUM, item.id)` | 专辑段清空/删除 |
| `SearchScreen.kt:333` / `:354` | `clearSection(TYPE_ARTIST)` / `remove(TYPE_ARTIST, item.id)` | 歌手段清空/删除 |
| `SearchScreen.kt:438` | `addSong(context, item)` | **点搜索结果行**（`onClick`）时写入 |
| `SearchScreen.kt:444` | `addSong(context, item)` | 长按菜单「加入库」时写入 |
| `SearchScreen.kt:449` | `addSong(context, item)` | 长按菜单「下一首播放」时写入 |
| `SearchScreen.kt:453` | `addSong(context, item)` | 长按菜单「加入队列」时写入 |
| `SearchScreen.kt:486`/`:493`/`:501`/`:509` | `addAlbum(...)` | 专辑行点击 / 播放全部 / 下一首 / 加入队列 |
| `SearchScreen.kt:543`/`:550`/`:558`/`:566` | `addArtist(...)` | 歌手行点击 / 播放全部 / 下一首 / 加入队列 |
| `AppWarmup.kt:90` | （字符串常量） | 冷启动预热 `"search_history"` 这个 prefs 文件 |
| `TrackKey.kt:119` | （注释） | 源码里**自己点名**了这条路径会丢 `source` |
| `baseline-prof.txt:30` | （profile 规则） | 基线 profile 里的类名（与逻辑无关） |

**结论：历史记录的唯一读/写消费者是 `SearchScreen`，唯一「变成可播放对象」的落点是 `SearchScreen.kt:749-755` 的 `toSongItem()`。** 这也意味着修复面很小。

### 4.2 从「点一条 QQ 搜索历史」到「播不出来」的逐跳（每一跳都有证据）

| # | 发生了什么 | 证据 |
|---|---|---|
| 1 | 搜索是双源聚合，QQ 结果带 `source="qqmusic"`、`sourceId=songmid`、`id=qqId(songid, mid)` | `SearchViewModel.kt:175-188`、`QqSongMapper.kt:115-131` |
| 2 | 用户点这一行 ⇒ `addSong(item)` 写入历史，**只留 id** | `SearchScreen.kt:438` → `SearchHistoryManager.kt:29-34` |
| 3 | （同一动作）立刻 `onSongClick(item)` —— 注意**这次用的是原始 `SongItem`**，音源还在，所以**本次播放是好的** | `SearchScreen.kt:438-439` |
| 4 | 下次进搜索页、清空 query ⇒ 历史渲染，`toSongItem()` 重建 ⇒ `source=null, sourceId=null, mediaId=null` | `SearchScreen.kt:140-155`、`:253`、`:749-755` |
| 5 | 用户点这一行 ⇒ `onSongClick(song)` ⇒ `playSongItem(song)` | `SearchScreen.kt:257`、`MainActivity.kt:2268-2269`、`:1488-1523` |
| 6 | 入队/起播：`sourceKey = song.source`（= null）、`songId = song.id`（bit62） | `MainActivity.kt:1139-1148`（`:1144`） |
| 7 | `TrackKey.of(null, bit62Id)` ⇒ `QQMUSIC`（**这一步没认错**） | `PlayerViewModel.kt:1100` → `TrackKey.kt:105-109` |
| 8 | 但 ref 是 `songRefOf(QQMUSIC, id, sourceId = null, …)` ⇒ `isResolvable == false` | `PlayerViewModel.kt:1103`；`SongSourceExt.kt:31-32` |
| 9 | `SourceRouter.resolveUrl` 直接返回 null（**不会去问 ncm**，这是有意的） | `SourceRouter.kt:60-64` |
| 10 | 回落离线缓存也必然 miss（离线 key 同样来自 id+level，且这首歌从未成功播过） | `PlayerViewModel.kt:1313-1323` |
| 11 | `onUrlUnavailable` ⇒ USER 来源可跳歌 ⇒ **这首被跳过**；同时弹「可切另一源：ncm」的 Toast | `PlayerViewModel.kt:1334-1380`、`PlaybackGuard.kt:153-154`、`PlayerViewModel.kt:327-339` |
| 12 | 那条 Toast 对本案是**错的**：不是「此源无版权」，而是「老历史条目缺 songmid」；而且切到 ncm 也救不回来（合成 id 在 ncm 不存在） | `PlayerViewModel.kt:330-337` 只按 `SourceIds.sourceOfId(id)` 判音源、看不到载荷缺失 |

### 4.3 两条口径的分叉（「被当成 ncm」到底发生在哪）

| 口径 | 取值来源 | 对「source 丢了的 QQ 曲目」的判定 | 使用者 |
|---|---|---|---|
| **字符串口径** `SongItem.musicSource` | `MusicSource.fromKey(song.source)`，null/未知 ⇒ `NETEASE` | **认成 ncm**（错） | `SongTags` 角标（`SongTags.kt:112`）、`songIdForRoute`（`MainActivity.kt:677-682`）、`LibraryManager.saveSong` 落盘与 `pushLike`（`LibraryManager.kt:196-207`）、`isResolvable`/`mediaIdOrNull`（`SongSourceExt.kt:31-32`、`:42-43`） |
| **结构口径** `TrackKey.ofSong` / `SongItem.trackKeyOf()` | `TrackKey.of(song.source, song.id …)`，字符串空时先看 bit62 | **认出 QQ**（对） | 队列判重/重定位（`QueueKeys.kt:74`）、待播槽位、歌词闸门、`playSong` 的 `currentTrack`（`PlayerViewModel.kt:1100`） |

这条分叉**已经被既有单测点名并钉住**（`app/src/test/.../player/QueueKeysTest.kt:90-101`）：

```kotlin
val synthId = SourceIds.qqId(1234567L, "0039MnYb0qxYhV")
val sourceLost = SongItem(
    id = synthId, name = "丢了 source 的 QQ 曲目",
    artists = null, album = null, duration = null, source = null,
)
assertEquals(MusicSource.NETEASE, sourceLost.musicSource)          // ← 字符串口径：认成ncm
assertEquals(MusicSource.QQMUSIC, sourceLost.trackKeyOf().source)  // ← 结构性口径：认出 QQ
assertEquals(TrackKey.fromSong(sourceLost).source, MusicSource.NETEASE) // 记录旧口径的差异
```

而 `TrackKey.kt:116-121` 的 KDoc 更是直接写着：

> ⚠️ **新代码请优先用 [ofSong]**：… 而队列条目确实存在「id 带 QQ 标志位、source 字符串丢了」的形态（**搜索结果进历史记录那条路只存得下 id，见 `SearchHistoryManager.HistoryItem`**）…

**所以本 bug 的准确定性是**：不是「播放时把 QQ 歌当成 ncm 去取链」（bit62 从 v2.1.5 起已经挡住了），而是
**「历史记录只存 id ⇒ ① 所有字符串口径的消费者把 QQ 曲目当 ncm；② songmid/media_mid 不可恢复 ⇒ 该曲目从历史里永远无法取链播放」**。
两者同源（`HistoryItem` 的字段集），后者才是用户能直接感知到的那一面。

---

## 5. 身份规则：`TrackKey` / `SourceIds` / bit62（逐字引用）

### 5.1 三个构造函数的确切语义

| 函数 | 定义 | 语义 |
|---|---|---|
| `TrackKey.of(sourceKey, id, sourceId, mediaId)` | `TrackKey.kt:99-111` | `sourceKey` 为 null/空 ⇒ **先看 bit62**（`SourceIds.sourceOfId(id)`），否则 `MusicSource.fromKey`。显式传值以它为准 |
| `TrackKey.fromSong(song)` | `TrackKey.kt:126-127` | `TrackKey(song.musicSource, song.id, song.sourceId, song.mediaId)` —— **只信 `source` 字符串**，字符串缺失一律 ncm。KDoc 明确写着「新代码请优先用 ofSong」 |
| `TrackKey.ofSong(song)` | `TrackKey.kt:148-149` | `of(song.source, song.id, song.sourceId, song.mediaId)` —— **v2.5.3 · P1 起的唯一落点**（队列判重、待播槽位、随机模式的共同入口） |
| `TrackKey.fromMediaId(mediaId)` | `TrackKey.kt:162-163` | 解析 `song:<id>` / `song:qqmusic:<id>`；解析失败返回 **null**（「绝不猜成 ncm」） |
| 相等性 | `TrackKey.kt:75-78` | **只看 `(source, id)`**，`sourceId`/`mediaId` 是载荷、不参与判等 |

### 5.2 bit62 规则的字面代码

`source/MusicSource.kt:164`：

```kotlin
const val QQ_ID_FLAG: Long = 1L shl 62
```

`source/MusicSource.kt:167`：

```kotlin
fun isQqId(id: Long): Boolean = (id and QQ_ID_FLAG) != 0L
```

`source/MusicSource.kt:188-189`：

```kotlin
fun sourceOfId(id: Long): MusicSource =
    if (isQqId(id)) MusicSource.QQMUSIC else MusicSource.NETEASE
```

`source/MusicSource.kt:198-201`（`SourceIds.qqId()` 本体）：

```kotlin
fun qqId(rawSongId: Long, sourceId: String): Long {
    val raw = if (rawSongId > 0L && rawSongId < QQ_ID_FLAG) rawSongId else hashSourceId(sourceId)
    return QQ_ID_FLAG or raw
}
```

`source/MusicSource.kt:209`（无损反解）：

```kotlin
fun qqRawId(id: Long): Long? = if (isQqId(id)) id and (QQ_ID_FLAG - 1L) else null
```

### 5.3 这是**证明**还是**启发式**？—— 分三层，必须说清

| 层 | 命题 | 定性 | 依据 |
|---|---|---|---|
| L1 | 「`id` 的 bit62 = 1 ⇒ 这个 id 是 `qqId()` 造出来的」 | **在算术上恒真**：`qqId()` 的唯一返回值形态就是 `QQ_ID_FLAG or raw` | `MusicSource.kt:198-201`；算术 `1L shl 62 = 4611686018427387904`（**实测**） |
| L2 | 「`id` 的 bit62 = 1 ⇒ 这首歌属于 qm」 | **在「全部 QQ id 都由 `qqId()` 产出」这条不变量下成立 ⇒ 结构性结论，不是抽样启发式**。它的反面（ncm id 触到 bit62）**不可能**：ncm id 量级 `1e6~3e9`、远小于 `2^40` | `MusicSource.kt:149-150`、`:172-174`；`QueueKeys.kt:43-48`；`TrackKeyTest.kt:169-182`（把 `9_999_999_999` 与 `QQ_ID_FLAG - 1` 都断言成非 QQ） |
| L3 | 「bit62 = 0 ⇒ 这首歌属于 ncm」 | **这是启发式，不是证明**。它成立的前提同样是「所有 QQ id 都走 `qqId()`」；一旦某条路径造出一个 **raw 的 QQ songid**（不带标志位）并配上丢失的 `source`，L3 就会把它判成 ncm。今天 `qqId(` 在 `app/src/main` 只有 3 处调用：`QqSongMapper.kt:94`（生产者，§1 行 19）、`QqCatalog.kt:351`（生产者，§1 行 20）、`SongDetailScreen.kt:256`（从路由的**裸 songid** 重建 —— `rawId > 0` 时 `qqId` 直接取 rawId，所以与 mapper 产出**同一个** id，仍带标志位），**全部带标志位**，所以现实中 L3 未被破坏 —— 但它是**纪律**，不是结构 | `grep -rn "qqId(" app/src/main`（3 处调用 + 定义在 `MusicSource.kt:198`）；`SongDetailScreen.kt:255-260`；`QueueKeys.kt:52-55` 自己承认「今天是 0，但那个 0 靠的是纪律」 |

**并且 bit62 无论如何推不出 songmid**（`MusicSource.kt:184-186` 原文）：

> **注意它推不出 songmid**：QQ 取链与取词都需要 songmid，而那只能来自队列里的 `SongItem.sourceId`。所以本函数只负责「别问错平台」，不负责「能不能取到」。

---

## 6. `SongItem` 的音源身份字段（`network/SearchResponse.kt`）

| 字段 | 类型 / 默认 | 证据 | 说明 |
|---|---|---|---|
| `id` | `Long`（**无默认值**） | `:23` | QQ 曲目这里是 `qqId()` 合成 id |
| `source` | `String? = null` | `:36` | `"netease"` / `"qqmusic"`；可空是**硬要求**（老队列 JSON 缺 key）；判「有意留空」= ncm 用 `songRefOf`（`SongSourceExt.kt:96-98`） |
| `sourceId` | `String? = null`（JSON key `mid`） | `:44` | QQ 的 songmid；**取链/取词强制需要**（`QqApi.kt:134`） |
| `mediaId` | `String? = null`（JSON key `media_id`） | `:54` | QQ 的 `file.media_mid`，拼文件名用；缺省回落到 songmid（`QqApi.kt:135-136`） |
| `fee` / `memberOnly` / `privilege` / `noCopyright` / `originCoverType` / `originSong` | 全部可空 + 默认 null | `:66`、`:76`、`:90`、`:98`、`:107`、`:114` | 与音源身份无关，但同样遵循「可空 + 默认值」的持久化规矩 |

**没有 `sourceLabel` 字段。** 音源文案是**函数**：`SongTags.sourceLabel(source: MusicSource, strings: Strings)`（`ui/components/SongTags.kt:62-65`），由 `musicSource` 派生 ⇒ 对 `source=null` 的历史重建对象必然渲染成「ncm」（`SongTags.kt:112`）。

`AlbumSearchItem`（`:163-171`）与 `ArtistSearchItem`（`:174-183`）**没有任何音源字段**；今天 `SearchViewModel` 的 type=10/100 只调 ncm（`SearchViewModel.kt:209-222`），所以专辑/歌手历史**目前**不存在这个 bug —— 但一旦 QQ 专辑/歌手搜索落地，同一处会立刻复现。

---

## 7. 既有测试覆盖（`app/src/test`）

| 测试 | 覆盖了什么 | 与本 bug 的关系 |
|---|---|---|
| `source/TrackKeyTest.kt`（`app/src/test/.../source/TrackKeyTest.kt:26-215`，**16 个用例**） | `(source,id)` 判等、载荷不参与判等、`tag`/`toString`、`of()` 的 null/空串/未知回落、**`of_infers_qqmusic_from_flagged_id`**（`:128-135`）、`fromMediaId` 往返与拒绝坏输入、**`netease_ids_are_never_flagged`**（`:169-182`）、`fromSong` 四字段 | 已覆盖 bit62 推断与「ncm id 不触 bit62」的边界值 —— **迁移可以直接复用这套判据，不需要新造规则** |
| `source/MusicSourceTest.kt`（`MusicSourceTest.kt:21-189`，**21 个用例**） | `fromKey` 回落（`:22`、`:28`）、key 与 ordinal 解耦（`:41`）、`requiresSourceId`（`:48`）、`trackKey`/`mediaId` 往返（`:61`、`:99`）、`parseMediaId` 认历史形状 `song:123`（`:107`）、**`QQ id 落在ncm永远到不了的区间`**（`:129`）、**`QQ id 可无损反解回真实 songid`**（`:137`）、**`ncm id 不会被误判成 QQ id`**（`:145`）、**`两组 id 空间不相交`**（`:153`）、散列兜底确定性（`:163`、`:172`、`:182`） | 同上；另外 `QQ id 落在…区间` 与 `两组 id 空间不相交` 正是 §5.3 的 L1/L2 断言 |
| `source/SongSourceExtTest.kt`（`SongSourceExtTest.kt:29-98`，**8 个用例**） | `旧数据没有 source 字段时按ncm处理`（`:30`）、`qm曲目带 mid 时可取链`（`:38`）、**`qm曲目缺 mid 时不可取链`**（`:47`）、`未知 source 字符串按ncm处理而不是当成 QQ`（`:54`）、`同为ncm的新旧条目身份一致`（`:61`）、`同 id 不同音源的判重键不同`（`:73`）、`id 非正时没有 mediaId`（`:82`）、`songRefOf 往返保持音源与 mid`（`:88`） | `缺 mid 时不可取链` 正是历史重建对象今天命中的分支 |
| `player/QueueKeysTest.kt:90-101` | **`身份落点是 TrackKey_ofSong - source 字符串丢了也能靠 bit62 认出 QQ`** —— 注释原文「搜索结果进历史记录那条路只存得下 id」 | **已经在测试里点名本 bug 的路径**；`assertEquals(TrackKey.fromSong(sourceLost).source, MusicSource.NETEASE)` 是「旧口径的差异」的书面记录 |
| `qq/QqSongMapperTest.kt:53-83` | `isQqId(song.id)`、`qqRawId(song.id) == 97773`、缺 songid 时散列兜底仍带标志位 | 生产者一侧的 bit62 断言 |
| **搜索历史** | **没有任何单测**。`app/src/test` 的 83 个测试文件里没有 `SearchHistory*`（`find app/src/test -name '*.kt'` 全量清单） | 本报告 §12 的补测清单是空白区 |

---

## 8. 迁移设计空间：老条目（无 `source` 字段）能推断出什么

### 8.1 候选判据的枚举与定性

| 方案 | 能推断出 | 定性 | 依据 |
|---|---|---|---|
| **(a) bit62 推断** `SourceIds.sourceOfId(id)` | 音源 = QQ / ncm | **可证明**（L1 + L2；前提是「QQ id 一律走 `qqId()`」这条已成立的不变量）。**推不出 songmid** | `MusicSource.kt:167`、`:188-189`；`TrackKey.kt:105-109`；`TrackKeyTest.kt:169-182` |
| **(b) id 数值区间启发式** | 大概率的音源 | **纯猜测，本仓库没有可用区间**。代码里出现的数字都是**声明性量级**（「百万~十亿」「1e6~3e9」「远小于 2^40」「9~10 位十进制」），**不是**任何判定代码；`TrackKeyTest.kt:171-175` 明确把 `1_000_000_000`、`9_999_999_999` 当成「远超真实规模，仍未触到位 62」的**边界样本**，而不是「ncm 判据」。更要命的是：QQ 的 **raw** songid 也是 9~10 位十进制数 ⇒ 与 ncm 区间**重叠**，区间启发式在原理上就不可能区分二者 | `MusicSource.kt:149-150`、`:161-162`；`QueueKeys.kt:46`；`TrackKeyTest.kt:169-182` |
| **(c) 标 unknown（缺字段即老条目，按 miss 处理）** | 什么都不推断，把二义性显式化 | **唯一诚实的兜底**，且正是 `AGENTS.md:400-419` 固化的规矩 | `AGENTS.md:411-419` |
| (d) 用「用户偏好音源」兜底 | —— | **不可行**：本仓库没有「默认音源」这个持久化设置。`preferredSource` 是聚合结果对象上的**一次性字段**（由可播放性推导），不是用户偏好 | `crosssource/CatalogAggregator.kt:53-71`、`:441`；`AlbumDetailScreen.kt:189` |
| (e) 用标题+艺人重搜一次补 songmid | 可能补回 songmid | **猜测性网络行为**：QQ 侧**没有**「按 songid 取 mid」的端点（`QqApi` 只有 `searchSongs`/`fetchPlayUrl`/`fetchLyric`/`fetchProfile`/登录那几条），只能走关键词搜索再挑条目 ⇒ 需要 `NameNormalizer`/`CrossSourceMatcher` 那套模糊匹配 | `qq/QqApi.kt:63`、`:133`、`:380`、`:434`（`grep "suspend fun" QqApi.kt`）；`crosssource/` 包；**同版兄弟探针** `docs/verification/v2.5.4/probe-qq-fallback.md` §0 第 1/4 条（「取链从头到尾不看 songid」「songmid 缺失 = 硬失败，没有兜底、没有重试」） |

### 8.2 结论：只有 (a) 是可证明的，(b) 必须放弃

- **可证明**：(a) 音源（在 L2 的不变量下）。`RawId` 也能无损反解：`qqRawId(4611686018427485677) == 97773`（§3 实测）。
- **不可证明、也不可推断**：`sourceId`（songmid）、`mediaId`（media_mid）。二者与 `id` 之间**没有任何双向映射**（`qqId()` 是单向的：`raw → flagged`；`hashSourceId` 兜底甚至把「只给 mid 不给 songid」的曲目映射成 mid 的 FNV-1a 散列 —— 不可逆）。
- **数值区间**：本仓库**没有任何**代码用 id 区间判音源；能引用的只是量级声明（ncm `1e6~3e9`，QQ raw songid `9~10 位十进制`），且二者**重叠** ⇒ (b) 排除。

### 8.3 两个规模事实，决定迁移该多轻

| 事实 | 证据 |
|---|---|
| TTL = 14 天，`add` 与 `getAll` 都会剪 | `SearchHistoryManager.kt:13`、`:76`、`:87-88` |
| 每段 ≤ 10 条 | `SearchHistoryManager.kt:12`、`:79` |

⇒ 升级后**最多 14 天**，全部老条目会被 TTL 自然清掉；总量上限 **30 条**。**给一个「最多 30 条、14 天自毁」的表写复杂迁移是负收益** —— 与 `AGENTS.md:418-419`「能不加字段就不加」（迁移面越窄越好）同向。推荐：**加字段 + 只做 bit62 推断 + 老条目保持「信息不完整」直到过期**。

---

## 9. Gson 持久化风险（**实测**，不是理论）

`AGENTS.md:400-419`「歌词缓存字段迁移策略」的规矩在本表**完全适用**（同样是跨版本存活、同样 Gson 裸实例、同样 Gson 走 Unsafe）。实测（`/tmp/gsonprobe/Probe2.java`，Gson 2.10.1）：

```
A. 无无参构造器（= 真实 HistoryItem 的形状，Gson 走 Unsafe）：
   id=5257138 title=屋顶 timestamp=1700000000000
   新字段 source = null   <-- 声明初始值 NEW_FIELD_INITIALIZER 没生效
B. 有隐式/显式无参构造器（对照）：
   id=5257138 title=屋顶 timestamp=1700000000000
   新字段 source = NEW_FIELD_INITIALIZER   <-- 初始值生效
```

**为什么真实 `HistoryItem` 落在 A 组**：Kotlin 只在**全部**构造参数都有默认值时生成无参构造器；`HistoryItem(val id: Long, val title: String, val coverUrl: String?, val subtitle: String?, val timestamp: Long = …)` 里 `id`/`title`/`coverUrl`/`subtitle` **都没有默认值**（`SearchHistoryManager.kt:19-25`），所以只有带参构造器 ⇒ Gson 用 `UnsafeAllocator`（实测日志里那句 `Final field id … has been mutated reflectively` 就是它绕过构造器写 final 字段的证据）。

**因此**：

| 场景 | 老条目读出来的值 | 结论 |
|---|---|---|
| 新增 `val source: String? = null` | `null` | 恰好与「老条目」同形 ⇒ 符合规矩，但**不能**用「初始值会生效」来推理 |
| 新增 `val source: MusicSource = MusicSource.DEFAULT`（非空枚举） | **`null`**（JVM 默认值，绕过构造器与初始值）⇒ 读的地方若按非空类型用，**就是 NPE** | **绝不允许**非空类型的新字段（`AGENTS.md:413`） |
| 新增 `val sourceId: String? = null` | `null` | 同上 |
| 想让「初始值生效」而给所有参数补上默认值 | —— | **不要**：那会改变 `HistoryItem` 与 `Gson` 的构造路径（A→B），属于不必要的语义变更；迁移只需要「可空 + 默认值」，不需要「无参构造器」 |

**另一个必须避开的坑**：Gson 默认**不写 null**（§3 实测）⇒ 若写路径写成 `source = song.source`，ncm 条目会**缺 `source` key**，于是「缺 key = 老条目」对新写入的数据永远成立，`AGENTS.md:414` 的「缺失 vs 空串」二义性就被自己制造出来了。**写路径必须写规范化的 `song.musicSource.key`**（ncm 也显式写 `"netease"`），判「老条目」只看 `source == null`，判「字段为空串」（`"source": ""`）按**未知值**处理（`MusicSource.fromKey("")` = NETEASE，与既有回落一致，见 `MusicSourceTest.kt:22-33`）。

---

## 10. 是否还有「只靠 id」的顺序/身份逻辑（去重键要不要跟着改）

### 10.1 搜索历史内部（**要改的就在这三行**）

| 位置 | 代码 | 影响 |
|---|---|---|
| `SearchHistoryManager.kt:77` | `items.removeAll { it.id == item.id }` | 写入时去重。今天靠 bit62 使两源 id 空间不相交**而是安全的**，但这条安全性依赖「QQ id 一律走 `qqId()`」的纪律（同 `QueueKeys.kt:52-55` 的论证） |
| `SearchHistoryManager.kt:64` | `items.removeAll { it.id == id }` | `remove(type, id)` 的判据 —— **调用方传的就是 `item.id`**（`SearchScreen.kt:284`/`:319`/`:354`），所以即使判据升级成 `(source,id)`，`remove` 的签名也要跟着改，否则删不掉 |
| `SearchScreen.kt:252`（以及 `:304`、`:339`） | `items(songHistory, key = { "s_${it.id}" })` | LazyColumn 的 diff key。同一段里 id 唯一，今天是安全的；**若迁移后判重键改成 `(source,id)` 而 key 仍是 id**，两行同 id 会让 LazyColumn 抛 "Key was already used" —— 二者必须一起改 |

**结论：是的，去重键要跟着改，但改法有讲究** —— 不能简单地用 `(source, id)` 原始字段，因为老条目的 `source` 是 `null`。反例：老条目（`source=null, id=bit62`）与新条目（`source="qqmusic", id=同值`）在 `(source,id)` 下**不相等** ⇒ 用户重新搜到同一首 QQ 歌并点击后，历史里会出现**两行同一首歌**（一行老、一行新）。正确做法是**用「有效音源」参与判重**，即 §11 的 `effectiveSource(item)`，而不是裸字段：

```
dedupeKey(item) = SourceIds.trackKey(effectiveSource(item), item.id)   // "qqmusic:4611686018427485677"
```

（这也让「删一条」与「LazyColumn key」自动拿到同一个串，三处不会漂移。）

### 10.2 相邻的裸 id 身份逻辑（本次**不**在修复范围，但同源，列出以免误判影响面）

| 位置 | 代码 | 后果 |
|---|---|---|
| `LibraryManager.kt:200` / `:214` / `:231` | `songs.none { it.id == song.id }` / `songs.removeAll { it.id == songId }` | 从历史「加入库」的那个对象 `source=null` ⇒ 落盘成 ncm 条目；且判重按裸 id |
| `LibraryManager.kt:185-187`、`:205`、`:222` | `pushLike(song.id, like)` | 会拿 **bit62 合成 id** 去调 ncm 的 like 接口（`PlaylistApi.likeSong`）—— 必失败 |
| `MainActivity.kt:677-682` | `songIdForRoute`：`if (song.musicSource == QQMUSIC) qqRawId(...) else song.id` | 历史重建对象 `musicSource == NETEASE` ⇒ 路由里塞入 `4611686018427485677`，单曲页拿到的 id 在 ncm 不存在 |
| `SongTags.kt:112` | `out += SongTag(sourceLabel(song.musicSource, strings), …)` | 任何用 `SongTags` 渲染的地方都会给这类曲目打「ncm」角标 |
| `PlayerViewModel.kt:327-339` | `showSourceFallbackHint` 用 `SourceIds.sourceOfId(songId)` 推音源 | 提示方向正确（QQ）、但建议（切 ncm）解决不了「缺 songmid」 |

---

## 11. 迁移提案（具体到字段名、类型、默认值、读路径、写路径、UI 语义）

### 11.1 存储层：加两个字段（不动既有 5 个字段、不动已落盘形状）

```kotlin
data class HistoryItem(
    val id: Long,
    val title: String,
    val coverUrl: String?,
    val subtitle: String?,
    val timestamp: Long = System.currentTimeMillis(),
    // v2.5.4：音源身份两件套。可空 + 默认值是硬要求（Gson 走 Unsafe，见 §9 实测）。
    // null 的语义 = 「v2.5.4 之前写入的老条目，字段缺失」，不是「这首歌没有音源」。
    val source: String? = null,     // "netease" / "qqmusic"；写路径永远写规范值
    val sourceId: String? = null,   // QQ 的 songmid；ncm恒 null；老条目不可推断 ⇒ 保持 null
    // 可选第三个：QQ 的 media_mid。不加也能取链（QqApi.kt:135-136 会回落 songmid），
    // 但「用错 media_mid 会静默拿到坏链」（QqApi.kt:126-131）⇒ 建议一并存，避免回落带来的
    // 「同一首歌在历史里和搜索结果里取到不同文件」的隐性差异。
    val mediaId: String? = null,
)
```

**为什么是「加字段」而不是「整条 `SongItem` 存进 history」**：后者能一次解决所有载荷丢失，但会把每段 10 条的 JSON 放大到含 `privilege`/`noCopyright`/`originSong` 的全量对象（`network/SearchResponse.kt:66-114`），并且要同时改 albums/artists 两种类型的序列化形状（`HistoryItem` 是三段共用的一个类，`SearchHistoryManager.kt:19-25`）。收益（少一个字段）与代价（形状全变、体积、与 `SongItem` 演进耦合）不成比例。**结论：加字段；`SongItem` 整体落盘留给「本地歌单」那条已经这么做的路径。**

### 11.2 写路径（三个 `addXxx` + 三处调用点）

```kotlin
fun addSong(context: Context, song: SongItem) = add(
    context, TYPE_SONG,
    HistoryItem(
        id = song.id,                                  // 不变
        title = song.name,
        coverUrl = song.album?.picUrl,
        subtitle = song.artists?.firstOrNull()?.name,
        source = song.musicSource.key,                 // ★ 规范值：ncm也显式写 "netease"
        sourceId = song.sourceId,                      // ★ QQ 的 songmid
        mediaId = song.mediaId,                        // ★ QQ 的 media_mid
    )
)
```

- **必须用 `song.musicSource.key` 而不是 `song.source`**：后者对 ncm 是 `null`，会被 Gson 连 key 一起省掉（§9 实测），把新数据写成「老条目形状」。
- `addAlbum`/`addArtist` 保持现状（源对象没有音源字段）；**若将来 QQ 专辑/歌手搜索落地，必须同步加**。

### 11.3 迁移判定的**纯函数**（`AGENTS.md:417` 要求：抽纯函数 + 单测）

建议放在 `library/SearchHistoryMigration.kt`（或 `SearchHistoryManager` 内的 `object`，但**必须无 Android 依赖**，否则 JVM 单测够不着 —— 这也是 `QueueKeys`/`PlaybackGuard` 的既有做法）：

```kotlin
/**
 * 这条历史记录的**有效音源**。
 * - 显式 source（v2.5.4 起写入）以它为准 —— 与 TrackKey.of 的「声明优先于结构」一致；
 * - source == null ⇒ 老条目 ⇒ 只允许 bit62 这一条**可证明**的推断（SourceIds.sourceOfId）。
 * 注意：判「老条目」只看 null，不看空串（空串是「读不懂的未知值」，回落 NETEASE 由 fromKey 负责）。
 */
fun effectiveSource(item: SearchHistoryManager.HistoryItem): MusicSource =
    if (item.source == null) SourceIds.sourceOfId(item.id) else MusicSource.fromKey(item.source)

/** 去重键 = 有效音源 + id。老条目与新条目对同一首歌必须给同一个串。 */
fun dedupeKey(item: SearchHistoryManager.HistoryItem): String =
    SourceIds.trackKey(effectiveSource(item), item.id)

/** 这条记录是否**信息不完整**（音源是 QQ 但缺 songmid ⇒ 取链必然失败）。 */
fun isIncomplete(item: SearchHistoryManager.HistoryItem): Boolean =
    effectiveSource(item).requiresSourceId && item.sourceId.isNullOrEmpty()
```

`isIncomplete` 与 `SongItem.isResolvable`（`SongSourceExt.kt:31-32`）**必须是同一条规则的两种写法**（服务端取链的判据不能有两份，否则 UI 说能播、播放器说不能播）。

### 11.4 读路径（`toSongItem()`）

```kotlin
private fun SearchHistoryManager.HistoryItem.toSongItem(): SongItem {
    val source = SearchHistoryMigration.effectiveSource(this)   // 老条目也能推断出 QQ
    return SongItem(
        id = id,
        name = title,
        artists = subtitle?.let { listOf(ArtistItem(name = it)) },
        album = AlbumItem(id = null, name = null, picUrl = coverUrl),
        duration = null,
        // ncm一侧刻意写 null（与 songRefOf 同一条约定，SongSourceExt.kt:96-98）：
        // 这样它与 v2.1.0 之前持久化的条目在 data class 意义上仍然相等。
        source = if (source == MusicSource.NETEASE) null else source.key,
        sourceId = sourceId,          // QQ 老条目 = null ⇒ isResolvable = false（**不猜**）
        mediaId = mediaId,
    )
}
```

`toSongItem()` 目前是 `private fun HistoryItem.toSongItem()`（Composable 文件里的**纯函数**，没有 `@Composable`），**可以原样搬进单测**；建议搬到 `SearchHistoryMigration.kt` 或至少把它变成 `internal`，否则 §12 的路由用例够不着 —— 这是本次唯一需要的可测性改动。

### 11.5 UI 层：「信息不完整」这个状态做什么

| 状态 | 判定 | 建议的 UI 行为 |
|---|---|---|
| 音源已知且可解析（ncm 任意；QQ 带 songmid） | `!isIncomplete(item)` | 与今天完全一致（点击播放/下一首/加入库） |
| **老条目 + bit62 ⇒ QQ 但缺 songmid** | `isIncomplete(item)` | ① 行内加一个角标「旧记录」（复用 `SongTags` 的 `SongTagKind.SOURCE` 形状或新 kind；文案要按 `AGENTS.md` 的 i18n 规则补进 8 个 locale，`Strings.kt` 那种数据类字段 + 每个 locale 一份）；② **点击不直接入队播放**（今天会静默入队 → 取链失败 → 被跳歌 → 弹一条方向错误的「可切另一源」提示，§4.2）；改为把标题填进搜索框重搜一次，由用户重新点一次带 songmid 的结果；③ 「删除」照常可用 |
| 老条目 + bit62 ⇒ 推断为 QQ、但用户从不点 | 同上 | 不做任何后台行为；**等 14 天 TTL 自然过期**（`SearchHistoryManager.kt:13`、`:76`、`:87-88`），不写「启动时清库」这类破坏性迁移 |

三个明确的**不做**：
1. **不**用标题/艺人模糊匹配去补 songmid（§8.1(e)：QQ 侧没有按 songid 取 mid 的端点，只能搜索+模糊匹配；这是猜测性写操作，且会把「一次干净的失败」换成「一次诡异的播放错误」—— `QqApi.kt:126-131` 对同类兜底已有先例判决）；
2. **不**在读到老条目时立刻写回并丢掉「老」这个事实（那会把「不可解析」伪装成「正常条目」，让 §11.5 的第 ② 条失去判据）。若确实想减少重复推断，可以「读时推断、**保持 null 落盘**直到该条目被重新写入」（即只靠 TTL 换代）；
3. **不**给 `source` 设非空类型或非 null 默认值（§9 实测：Gson 走 Unsafe ⇒ 读到 `null` ⇒ NPE）。

### 11.6 去重键与删除签名（与 §10.1 对应）

```kotlin
// add(): items.removeAll { dedupeKey(it) == dedupeKey(item) }
// remove(context, type, id) → remove(context, type, key: String) 或 remove(context, type, item: HistoryItem)
// SearchScreen: items(songHistory, key = { "s_${SearchHistoryMigration.dedupeKey(it)}" })
```

三处必须**同一个函数**：判重、删除、LazyColumn key。只要有一处退回裸 id，就会出现「看着删了、重建后又回来」或「同 id 两行 → LazyColumn 抛异常」。

---

## 12. 应当补的单元测试（名字 + 断言什么）

> 位置建议：`app/src/test/java/com/takahashirinta/ncrust/library/SearchHistoryMigrationTest.kt` 与 `SearchHistoryCodecTest.kt`。
> 风格照 `QueueKeysTest`/`TrackKeyTest`：中文反引号用例名、每条用例一个断言主题、不碰 Android。
> 若要覆盖 `save`/`load` 的 Gson 往返，**不要**用 `SharedPreferences`（JVM 单测没有它）—— 把 `load/save` 的纯逻辑抽成 `String ↔ List<HistoryItem>`（`SearchHistoryCodec`），在测试里直接喂字符串。

| 用例名 | 断言什么 |
|---|---|
| `` `QQ 曲目写入历史后仍能还原出 songmid 与音源` `` | `HistoryItem(source="qqmusic", sourceId=mid, mediaId=media)` 经 `toSongItem()` 后 `isResolvable == true`、`trackKeyOf().source == QQMUSIC`、`sourceId == mid` |
| `` `ncm曲目的 source 显式写成 netease 而不是缺 key` `` | 写路径 `addSong(SongItem(id=5257138, source=null))` 产出的 `HistoryItem.source == "netease"`（防 §9 的「新数据长成老条目形状」） |
| `` `老条目缺 source 且 id 带 bit62 时推断为 QQ` `` | 喂 `{"id":<qqId>,"title":"…","timestamp":…}` ⇒ `effectiveSource == QQMUSIC` |
| `` `老条目缺 source 且 id 无标志位时按ncm处理` `` | 喂 `{"id":5257138,…}` ⇒ `effectiveSource == NETEASE`（= v2.1.0 之前数据的正确解释） |
| `` `老条目推断为 QQ 但不许猜 songmid` `` | `isIncomplete(item) == true`、`toSongItem().isResolvable == false`、`sourceId == null` —— 把「不可推断」钉成断言 |
| `` `字段缺失与空串是两种输入` `` | `source` 缺 key ⇒ 走推断；`"source":""` ⇒ `fromKey("") == NETEASE`；`"source":"spotify"` ⇒ `NETEASE`（与 `MusicSourceTest` 的回落口径一致） |
| `` `去重键用有效音源，老条目与新条目判为同一条` `` | `dedupeKey(legacy(source=null, id=qqId)) == dedupeKey(new(source="qqmusic", id=qqId))`，且 `SourceIds.parseTrackKey` 能解出 `(QQMUSIC, qqId)` |
| `` `显式 source 优先于 bit62` `` | `effectiveSource(HistoryItem(id=qqId, source="netease")) == NETEASE`（与 `TrackKey.of` 的「声明优先」一致） |
| `` `JSON 形状回归：null 字段不落盘` `` | 对固定 `HistoryItem` 断言 `Gson().toJson` 的**逐字节**输出（今天是 `{"id":…,"title":…,"timestamp":…}`）—— 形状变化必须显式可见 |
| `` `老 JSON 反序列化后新字段为 null（Gson 走 Unsafe）` `` | 喂老 JSON ⇒ `source == null && sourceId == null && mediaId == null`；**同时断言它不等于任何非空默认值**（防有人给字段加非空默认值后误以为会生效） |
| `` `TTL 14 天：过期条目被过滤且写回` `` | `timestamp = now - 15 天` ⇒ `getAll` 不返回它，且落盘列表里也没有（对应 `SearchHistoryManager.kt:87-88`） |
| `` `MAX_ITEMS 10 头插：第 11 条挤掉最旧的一条` `` | 加 11 条后 `size == 10`、`items[0]` 是最新那条、最旧的 id 不在列表里（`SearchHistoryManager.kt:78-79`） |
| `` `remove 删掉的是同音源同 id 的那一条` `` | 两源同号 id 场景下，`remove(QQ 那条)` 不能把 ncm 那条也删掉（若判据升级为 `dedupeKey`） |
| `` `HistoryItem 与 SongItem.isResolvable 判据一致` `` | 对同一组 `(source, sourceId)` 穷举：`!isIncomplete(item)` ⇔ `ref.isResolvable`（防两份规则漂移） |

**不需要写的测试**：`bit62` 本身（`TrackKeyTest` 16 个 + `MusicSourceTest` 21 个 = **37 个既有用例**已覆盖，§7）、`TrackKey.of` 的回落（已覆盖）。

---

## 13. 未确认 / 不确定（本探针**没有**证明的事）

1. **没有真机/模拟器实测。** 本报告是 U7 之前的静态阶段产物：QQ 曲目从历史点击后的**具体**表现（是否立刻跳歌、Toast 文案、队列面板是否已出现这一行、`onUnplayableCallback` 之后 UI 落在哪一首）是从代码推的（`PlayerViewModel.kt:1363-1380` + `PlaybackGuard.kt:153-154`），**未在设备上复现**。
2. **没有验证真机上 `search_history.xml` 的实际内容。** §3 的 JSON 形状是 Gson 2.10.1 行为实测（与 `app/build.gradle.kts:324` 的版本一致）+ `HistoryItem` 字段顺序读码推出，**不是**从设备 `adb` 拉取的 XML。差异风险点：若历史中存在 `coverUrl`/`subtitle` 非空的条目，形状会多两个 key（已给出两种形状）。
3. **没有实测 `LaunchedEffect(query)` 之外的历史刷新时机。** `refreshHistory()` 只在「query 变空」（`SearchScreen.kt:150-152`）与删除/清空后调用；**在搜索页停留期间插入历史（`addSong`）不会立刻刷新 UI**（写入发生在结果列表的 onClick 里，`SearchScreen.kt:438`；UI 要等下次 query 变空才重读）。这不影响本 bug 的结论，但会影响复现步骤（要先清空搜索框）。
4. **没有确认 QQ 搜索在未登录态下的历史行为。** `QqMusicSourceProvider.kt:30-34` 说匿名也能搜到歌、只有取链被拒；`SearchViewModel.kt:175` 的 `QqClient.isLoggedIn() || QqAccountAvailability.allowAnonymousSearch` 决定是否追加 QQ 结果。匿名态写入的历史条目同样是「只存 id」，症状叠加「未登录」，**未实测**两者的优先级。
5. **没有穷举所有 QQ `SongItem` 生产点。** 我按 `grep -rn "qqId(" app/src/main`（3 处调用 + 1 处定义）与 `grep -rn "SongItem(" app/src/main/java/.../qq`（1 处命中）判断生产者只有 `QqSongMapper`（+ `QqCatalog.syntheticId` 这个「只算 id 不造对象」的辅助，+ `SongDetailScreen` 那条从路由裸 songid 重建的分支）。若存在**反射/JSON 直转**构造 QQ `SongItem` 的路径（例如某个响应模型直接反序列化成 `SongItem`），L2/L3 的前提需要重查 —— 我**没有**审计所有 `@SerializedName("source")` 的赋值点。
6. **`sourceFallbackHintGate` 的节流参数没读。** 「可切另一源」提示对本案是错的方向（§4.2 第 11-12 跳），但它是否**每次**都弹、还是被节流掉，取决于 `sourceFallbackHintGate.shouldHint`（`PlayerViewModel.kt:329` 调用，未展开读）—— 因此「用户会看到一条错误提示」的**强度**未确认。
7. **没有评估「加入库」路径的完整后果。** `LibraryManager.saveSong`（`LibraryManager.kt:196-207`）会把 `source=null` 的对象落盘成 ncm 条目并 `pushLike(bit62 合成 id)`。落盘后 `ncrust_library` 里这条记录的后续表现（列表角标、云端 like 失败的重试/上报、取消收藏能否删掉）**未逐条追**。
8. **没有评估 albums/artists 段的未来形状。** 今天 QQ 只参与 type=1（`SearchViewModel.kt:209-222` 对 10/100 只调 ncm），所以 `SongTags`/`toSongItem` 的同类修复对 `HistoryItem` 的**共用**类是「加了字段但专辑/歌手段恒为 null」。等 QQ 专辑搜索落地时，`HistoryItem` 的三段共用是否会成为新的坑（尤其 `keyFor` 的 `else → artists` 兜底，`SearchHistoryManager.kt:92-96`）**未评估**。
9. **`baseline-prof.txt:30` 是否需要在字段变更后重新生成**未评估（只是 profile 规则，理论上与字段无关）。
10. **没有跑 `./gradlew test` 验证既有 83 个测试文件在当前 HEAD 全绿**（本探针只读代码，未执行构建 —— 这也是「没有源码改动」的一部分）。

---

## 附录 A：可复现命令

```bash
# 1. 调用点全集
grep -rn "SearchHistoryManager" app/src/main

# 2. QQ id 生产者（L2 不变量的证据面）
grep -rn "qqId(\|isQqId(\|qqRawId(\|sourceOfId(" app/src/main

# 3. 既有测试（本表空白面）
find app/src/test -name "*.kt" | wc -l      # 83
find app/src/test -name "SearchHistory*"    # 空 —— 没有搜索历史单测

# 4. Gson 行为（本报告 §3/§9 的实测）：把附录 B 的两段源码落到 /tmp/gsonprobe/ 后
GSON=~/.gradle/caches/modules-2/files-2.1/com.google.code.gson/gson/2.10.1/*/gson-2.10.1.jar
javac -cp $GSON Probe.java Probe2.java && java -cp ".:$GSON" Probe && java -cp ".:$GSON" Probe2

# 5. bit62 算术
python3 -c "f=1<<62; print(f, f|97773, (f|97773)&(f-1))"
```

---

## 附录 B：Gson 行为探针源码（本报告 §3 / §9 的「实测」即出自这两段）

> 探针**不在仓库内**（本文件是本次唯一的交付物），源码抄录如下，可原样落到任意目录重跑。
> 依赖：`com.google.code.gson:gson:2.10.1`（与 `app/build.gradle.kts:324` 同版本）。

**B.1 `Probe.java` —— 当前落盘 JSON 形状（null 字段是否落盘）**

```java
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.*;

public class Probe {
    // 与 SearchHistoryManager.HistoryItem 逐字段同构（字段声明顺序也一致）
    static class HistoryItem {
        long id; String title; String coverUrl; String subtitle;
        long timestamp = System.currentTimeMillis();
        HistoryItem(long id, String title, String coverUrl, String subtitle) {
            this.id = id; this.title = title; this.coverUrl = coverUrl; this.subtitle = subtitle;
        }
    }
    public static void main(String[] a) {
        Gson gson = new Gson();
        List<HistoryItem> items = new ArrayList<>();
        items.add(new HistoryItem(5257138L, "屋顶", null, null));                       // 无封面/无歌手
        items.add(new HistoryItem(4611686018427485677L, "晴天", "http://c/1.jpg", "周杰伦"));
        System.out.println(gson.toJson(items));
    }
}
```

**B.2 `Probe2.java` —— 老条目缺新字段时读出什么（Unsafe vs 无参构造器）**

```java
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.util.*;

public class Probe2 {
    // 与 Kotlin data class HistoryItem 同形：只有带参构造器 ⇒ Gson 走 UnsafeAllocator
    static class ItemNoNoArg {
        final long id; final String title; final String coverUrl; final String subtitle; final long timestamp;
        String source = "NEW_FIELD_INITIALIZER";
        ItemNoNoArg(long id, String title, String coverUrl, String subtitle, long timestamp) {
            this.id = id; this.title = title; this.coverUrl = coverUrl; this.subtitle = subtitle; this.timestamp = timestamp;
        }
    }
    // 对照：有无参构造器 ⇒ Gson 走构造函数、初始值生效
    static class ItemWithNoArg {
        long id = -1L; String title = "D"; String coverUrl; String subtitle; long timestamp = 42L;
        String source = "NEW_FIELD_INITIALIZER";
        ItemWithNoArg() {}
    }
    public static void main(String[] a) {
        Gson gson = new Gson();
        String legacy = "[{\"id\":5257138,\"title\":\"屋顶\",\"timestamp\":1700000000000}]";
        List<ItemNoNoArg> r1 = gson.fromJson(legacy, new TypeToken<List<ItemNoNoArg>>(){}.getType());
        System.out.println("A. Unsafe: source = " + r1.get(0).source);   // null
        List<ItemWithNoArg> r2 = gson.fromJson(legacy, new TypeToken<List<ItemWithNoArg>>(){}.getType());
        System.out.println("B. 无参构造器: source = " + r2.get(0).source); // NEW_FIELD_INITIALIZER
    }
}
```

实测输出（原样抄录）：

```
A. 无无参构造器（= 真实 HistoryItem 的形状，Gson 走 Unsafe）：
   id=5257138 title=屋顶 timestamp=1700000000000
   新字段 source = null   <-- 声明初始值 NEW_FIELD_INITIALIZER 没生效
B. 有隐式/显式无参构造器（对照）：
   id=5257138 title=屋顶 timestamp=1700000000000
   新字段 source = NEW_FIELD_INITIALIZER   <-- 初始值生效
# 附带 JDK 警告（正是 Unsafe 绕过构造器写 final 字段的证据）：
#   WARNING: Final field id in class Probe2$ItemNoNoArg has been mutated reflectively by
#   class com.google.gson.internal.bind.ReflectiveTypeAdapterFactory$1
```
