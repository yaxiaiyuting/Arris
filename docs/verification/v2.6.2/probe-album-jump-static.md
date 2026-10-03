# v2.6.2 探针 · 静态全树审计（QQ 曲目「转到专辑」）

> 目标版本：`v2.6.1-gpl`（versionCode 49）。审计对象是**该 tag 的源码树**，不是记忆。
> 方法：`git show v2.6.1-gpl:<file>` 与工作区逐行对照 + `grep` 全树穷举调用点。
> 结论全部标了**文件:行号**，可逐条复核。

---

## 1. 一句话根因

**与 v2.6.1 的艺人跳转**同构**：QQ `album.mid`（albumMID）在映射层被丢掉，跳转层把 QQ 的数字
`album.id` 交给**写死 ncm**的老路由 —— 而两个源的专辑编号空间互不相通。**

v2.6.1 已经修好了艺人那一半，并且在 `MainActivity.resolveAndNavigate` 的 KDoc 里
**明确写下**了这一半的处置（「本版不修」）—— 本探针的第一件事就是确认那句话仍然成立。

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt:1917-1923（v2.6.1-gpl，原文）
// ## 专辑分支为什么还留着ncm回落
//
// 与 artist 分支同形的那处 bug（QQ 的 `album.id` 被当ncm album id）**本版不修**：
// 它需要 QQ 侧的 `albumMID`，而 `AlbumItem` 目前不带（本次只给 `ArtistItem` 加了字段）。
```

---

## 2. 逐条回答任务书 §2.2

### 2.1 QQ `album.mid`（albumMID）在映射层是否被丢弃？位置？

**是，被丢弃。而且丢弃的方式比"漏读"更隐蔽：它被读出来了，但只喂给了封面 URL。**

`app/src/main/java/com/takahashirinta/ncrust/qq/QqSongMapper.kt:124-133`（v2.6.1-gpl）：

```kotlin
val albumObj = item.optJSONObject("album")
val albumMid = albumObj?.optString("pmid")?.takeIf { it.isNotEmpty() }      // ← 局部 val
    ?: albumObj?.optString("mid")?.takeIf { it.isNotEmpty() }
val album = albumObj?.let {
    AlbumItem(
        id = it.optLong("id", 0L).takeIf { v -> v > 0L },                    // ← 只带数字 id
        name = it.optString("name").takeIf { v -> v.isNotEmpty() },
        picUrl = albumMid?.let { m -> COVER_TEMPLATE.format(m) },            // ← mid 只活在这一行
    )
}
```

`AlbumItem`（`network/model/SongDetail.kt:50-54`）**根本没有能装它的字段**：

```kotlin
data class AlbumItem(
    @SerializedName("id") val id: Long? = null,      // QQ 域数字 id
    @SerializedName("name") val name: String?,
    @SerializedName("picUrl") val picUrl: String?
)
```

第二条映射链路 `qq/QqCatalog.kt:343-352`（`QqCatalogMapper.albumItemOf`）**同形**，
而且连那个局部 val 都没有 —— `mid` 只在拼封面时被用过就丢：

```kotlin
fun albumItemOf(item: JSONObject): AlbumItem? {
    val album = item.optJSONObject("album") ?: return null
    val mid = album.optString("pmid").takeIf { it.isNotEmpty() }
        ?: album.optString("mid").takeIf { it.isNotEmpty() }
    return AlbumItem(
        id = album.optLong("id", 0L).takeIf { it > 0L },
        name = album.optString("name").takeIf { it.isNotEmpty() },
        picUrl = mid?.let { "https://y.qq.com/music/photo_new/T002R500x500M000$it.jpg" },
    )
}
```

**实测确认 `album.mid` 一直在**（`probe-raw/probe-album-id-collision.out.txt`，2026-09 匿名可复现）：

```
QQ 曲目 晴天  songmid=0039MnYb0qxYhV songid=97773
      album: id=8220 mid=000MkMni19ClKG pmid=000MkMni19ClKG_5 name=叶惠美
      album 对象的字段集: id, mid, name, pmid, subtitle, time_public, title, title_highlight
```

> ⚠️ **`pmid` 不是专辑身份，是封面照片 id**：`pmid = "<albumMid>_<n>"`，尾部那个 `_5` / `_3`
> 是封面序号。现有代码**优先取 `pmid`**（对封面是对的），若照抄它当路由参数就是错的形状。
> 独立探针 `probe-album-detail-identity.py` 实测：QQ 服务端**能**容忍 `pmid`（内部会剥掉 `_N`），
> 但这属于服务端的宽容，不是契约 —— 本版把身份显式钉在 `mid` 上，并用值域闸门
> （base62，`_` 不合法）把 `pmid` 挡在门外。**「服务端碰巧能跑」不是身份判据。**

### 2.2 专辑跳转路由是否携带 source？还是只传数值 id？

**只传数值 id，source 在 composable 里被写死成 ncm。**

`app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt:1929-1937`（v2.6.1-gpl）：

```kotlin
coroutineScope.launch(Dispatchers.IO) {
    val albumId = song.album?.id                    // ← 不问 source
    if (albumId != null && albumId > 0L) {
        withContext(Dispatchers.Main) {
            if (progress.value > 0.01f) collapseCard()
            navController.navigate(NavRoutes.album(albumId))   // ← 老路由（Long）
        }
    }
}
```

`ui/navigation/NavGraph.kt:79` 的老路由 + `:170-176` 的 composable：

```kotlin
fun album(albumId: Long) = "album/$albumId"
...
composable(route = NavRoutes.ALBUM, arguments = listOf(navArgument("albumId") { type = NavType.LongType })) {
    val albumId = backStackEntry.arguments?.getLong("albumId") ?: return@composable
    AlbumDetailScreen(
        sourceKey = MusicSource.NETEASE.key,        // ← 写死
        albumId = albumId.toString(),
        ...
```

**带音源的两段路由 v2.4.0 就有了，只是这条路没用它**（`NavGraph.kt:76` / `:112`）：

```kotlin
const val ALBUM_SRC = "album/{source}/{albumId}"
fun album(source: MusicSource, id: String) = "album/${source.key}/$id"
```

→ 这是本版**零新增路由**即可修复的结构性前提。

### 2.3 是否有回落逻辑？回落打的是哪个源的接口？

**专辑分支没有任何"补 id 的回落"——比艺人分支更彻底：它在 `albumId == null` 时直接什么都不做，
连一次网络请求都不发。**

对照艺人分支（v2.6.0 的形状，v2.6.1 已删）：`PlaylistApi.getSongsByIds(listOf(song.id))`
打 `/eapi/v3/song/detail`（**ncm**），QQ 的 bit62 合成 id 在那里必然查空 ⇒ 静默失败。
专辑分支连这一步都没有：

| 数据形态 | `song.album` | 专辑分支的行为 |
|---|---|---|
| 搜索/歌单/专辑页新鲜加载的 QQ 曲目 | `{id: 22276, name: "What's Going On...?"}` | 用 `22276` 走**ncm**路由 ⇒ **跳到另一张真专辑** |
| 老队列/老缓存里的 QQ 曲目 | `{picUrl: "…T002R500x500M000002Neh8l0uciQZ_3.jpg"}` | `albumId == null` ⇒ **一个分支都不匹配，静默无反应** |
| ViewModel 冷启动恢复（`MainActivity.kt:944-958`） | `AlbumItem(id = null, name = "", picUrl = artwork)` | 同上 —— 且**连名字都是空串** |
| 搜索历史重开（`library/SearchHistoryMigration.kt:103`） | `AlbumItem(id = null, name = null, picUrl = item.coverUrl)` | 同上 |

第 2、4 行是**真机取证的**，不是构造的（见 `probe-album-jump.md` §3 与
`probe-raw/s6-persisted-qq-queue-entry.json`）。

### 2.4 老缓存 DTO 是否缺 albumMID？

**缺，而且是三层都缺。**

| 层 | 结构 | 落盘位置 | 有 albumMID 吗 |
|---|---|---|---|
| 网络/队列模型 | `network.model.AlbumItem` | `ncrust_playback_state`（queue）、`ncrust_library`（saved_songs）、`ncrust_home_cache`、`ncrust_local_playlists`、`ncrust_qq_playlists` | **无字段** |
| 收藏专辑表 | `library.SavedAlbumCodec.AlbumDto` | `ncrust_library` / `saved_albums` | **无字段** |
| UI 模型 | `library.AlbumInfo`（`LibraryManager.kt:611-617`） | 同上（经 codec） | **无字段** |

`library/SavedAlbumCodec.kt:83-89`：

```kotlin
internal data class AlbumDto(
    @SerializedName("albumId") val albumId: Long? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("picUrl") val picUrl: String? = null,
    @SerializedName("artist") val artist: String? = null,
    @SerializedName("songCount") val songCount: Int? = null,
)
```

真机证据（S6 / `ncrust_playback_state` / 队列第 847 项，原文见
`probe-raw/s6-persisted-qq-queue-entry.json`）：

```json
{"al": {"picUrl": "https://y.qq.com/music/photo_new/T002R500x500M000002Neh8l0uciQZ_3.jpg"},
 "ar": [{"name": "周杰伦"}],
 "id": 4611686018427837109, "media_id": "0020wJDo3cx0j3",
 "name": "稻香", "source": "qqmusic", "mid": "003aAYrm3GE0Ac"}
```

`al` 的 key 集合是 **`{picUrl}`** —— 既没有 `id`，也没有 `name`。
（对照 v2.6.1 给 `ar` 补 `mid` 之前的历史形状，`ar` 里同样只有 `name`。）

### 2.5 是否与 v2.6.1 的 `ArtistNavigator` 同构？

**同构，四个环节一一对应。**

| 环节 | 艺人（v2.6.1 已修） | 专辑（本次） |
|---|---|---|
| ① 映射层丢掉源内字符串身份 | `singer[].mid` 被丢（`QqSongMapper.kt:110`） | `album.mid` 只用于拼封面（`QqSongMapper.kt:124-133`） |
| ② 跳转层不看 `song.musicSource` | 只读 `artists[0].id` | 只读 `album.id` |
| ③ 老路由 source 写死 | `artist/{artistId}` → `NETEASE` | `album/{albumId}` → `NETEASE` |
| ④ 身份不可信时静默失败 | 补 id 回落打 ncm ⇒ 查空 | 连回落都没有 ⇒ 直接 no-op |

**一处不同（本版据此收窄了修复面）**：艺人有**两个出口**（长按菜单 + 播放页托盘作者名），
专辑只有**一个**（见 §3）。所以本版不需要像 v2.6.1 那样同时改托盘。

### 2.6 「转到专辑」的入口有几个？是否都走同一出口？

**动作只有 1 个构造点，宿主页面 9 个，出口唯一。**

全树穷举（`grep -rn "actionGoToAlbum\|resolveAndNavigate\|onShowSongMenu(" app/src/main`）结论：

| # | 宿主 | 触发方式 | 代码位置 |
|---|---|---|---|
| 1 | 首页 | 长按歌曲行 / 卡片菜单 | `ui/screen/HomeScreen.kt:411,484,492` |
| 2 | 库页（收藏单曲） | 长按歌曲行 | `ui/screen/LibraryScreen.kt:335` |
| 3 | 搜索页（歌曲结果） | 长按结果行 | `ui/screen/SearchScreen.kt:527` |
| 4 | 歌单详情 | 长按曲目行 | `ui/screen/PlaylistDetailScreen.kt:269` |
| 5 | QQ 歌单详情 | 长按曲目行 | `ui/screen/QqPlaylistDetailScreen.kt:257` |
| 6 | 本地歌单详情 | 长按曲目行 | `ui/screen/LocalPlaylistDetailScreen.kt:297` |
| 7 | 专辑详情 | 长按曲目行 | `ui/screen/AlbumDetailScreen.kt:326` |
| 8 | 艺人详情 | 长按曲目行 | `ui/screen/ArtistDetailScreen.kt:344` |
| 9 | 播放器卡（点歌名） | `onSongInfoClick` → 同一个 `menuSong` / `SongMenuSheet` | `MainActivity.kt:2241-2242` |

这 9 个宿主全部经 `MainActivity.showSongMenu`（`MainActivity.kt:1018`）汇到**同一张**
`SongMenuSheet`，而「转到专辑」这一项在其中只被构造一次：

```kotlin
// MainActivity.kt:2613-2615（v2.6.1-gpl）
SongMenuAction(Icons.Default.LibraryMusic, LocalStrings.current.actionGoToAlbum) {
    resolveAndNavigate(song, toArtist = false)
},
```

**结论：入口 9 个、出口 1 个。** 因此本版的验收方式与 v2.6.1 不同：
不需要逐个入口改代码，只需要保证「这 1 个出口正确」，再用**宿主对照**
（每个宿主的 `song` 对象形状一致：都来自同一个 `SongItem`）来覆盖。

单曲信息页（`SongDetailScreen`）**没有**「转到专辑」入口 —— `NavGraph.kt:369,386` 没有给它传
`onShowSongMenu`，所以它既没有长按菜单也没有这一项。这是**有意的**（它自己就是"看别的版本"的页面）。
本版不动它。

---

## 3. 值域与撞号（为什么"查一下"不可行）

两个源的专辑编号空间互不相通，且**不是"查不到"而是"查到另一张真专辑"**：

| QQ 曲目 | QQ `album.id` | QQ `album.mid` | 同专辑的 ncm id | 拿 QQ 数字 id 查 ncm 的结果 |
|---|---|---|---|---|
| 晴天 / 周杰伦《叶惠美》 | `8220` | `000MkMni19ClKG` | `18905` | `code 404`（静默失败） |
| 江南 / 林俊杰《第二天堂》 | `8036` | `000y5gq7449K9I` | `10804` | `code 404` |
| **富士山下 / 陈奕迅《What's Going On...?》** | **`22276`** | `004Z85XP1c25b7` | `6451` | **`code 200` →《百万金曲 陈小云2 苦恋梦 免失志》/ 陈小云（12 首）** |
| 光年之外 / 邓紫棋 | `1796874` | `001mTkmb4GJlh4` | `74826488` | `code 404` |
| 葡萄成熟时 / 陈奕迅《U 87》 | `7879` | `003J6fvc0bVJon` | （搜索未命中同名专辑，不影响结论） | **`code 200` →《爱的供养》/ 邓杰（12 首）** —— 与 PCL110 真机 logcat 的 `/api/v1/album/7879` 逐字对上 |

原始响应：`probe-raw/probe-album-id-collision.out.txt`、`probe-raw/probe-album-cross-domain.out.txt`。

**最坏的一档和 v2.6.1 完全一样**：v2.6.1 是「周杰伦 `4558` → 马洪波（页面看起来完全正常）」，
本版是「陈奕迅 `22276` → 陈小云（封面/发行日期/厂牌/13 首歌曲，页面看起来完全正常）」。

---

## 4. 影响范围

| 问题 | 答案 |
|---|---|
| 只有 QQ？ | **是。** ncm 曲目的 `album.id` 就是 ncm 专辑 id，老路由写死的源恰好正确（真机 A/B 已回归，见 `probe-album-jump.md` §4） |
| 只有二级菜单？ | **是**（与艺人不同）。全树只有 1 个动作构造点（§2.6） |
| 只有某几个艺人？ | **不是。** 撞号与否取决于 QQ 数字 id 落在 ncm 哪张专辑上：陈奕迅/周杰伦/林俊杰/邓紫棋 四个样本里 **2 个撞到真人**（陈奕迅两首）、**3 个 404** |
| 哪些数据形态会中招 | ① 新鲜加载的 QQ 曲目（`album.id` 在）⇒ **跳错专辑**；② 老队列/老缓存/冷启动恢复/搜索历史重开的 QQ 曲目（`album.id` 不在）⇒ **静默无反应** |
| 严重度 | **跳错专辑 > 找不到**（AGENTS.md 铁律 15）。页面正常渲染 ⇒ 用户会以为"这个应用的专辑数据整体是错的" |

---

## 5. 三条被本探针**确认**的任务书判断，与一条被**推翻**的

| 任务书 §2 的初步判断 | 实测 |
|---|---|
| 「QQ `album.mid` 在映射层被丢弃」 | **成立**，位置 `QqSongMapper.kt:124-133` + `QqCatalog.kt:343-352` |
| 「路由不携带 source，只传数值 id」 | **成立**，`MainActivity.kt:1934` + `NavGraph.kt:79/170-176` |
| 「老缓存 DTO 缺 albumMID」 | **成立**，三层都缺（§2.4） |
| 「有回落逻辑，回落打的是某个源的接口」 | **不成立**。专辑分支**没有任何回落**，`albumId == null` 时静默 no-op。这是它比艺人那处更隐蔽的原因：连一次失败的网络请求都没有，logcat 里什么都不留 |

---

## 6. 修复方向（详见 `CHANGELOG-v2.6.2.md`）

1. **把 albumMID 带出来**：`AlbumItem` 新增 `mid: String?`（可空 + 默认值，Gson 安全），
   在 `QqSongMapper` 与 `QqCatalogMapper` 两条链路上一起填，并加对称单测钉住逐值一致。
   **身份取 `album.mid`，封面继续取 `pmid ?: mid`** —— 两者职责分离，不互相顶替。
2. **身份判定收成一个纯函数**：新增 `source/AlbumNavigator.kt`，产出
   `Direct(source, id)` / `Search(keyword, reason)` / `Unavailable` 三态；
   **类型上就不存在「跳到另一个源」**。
3. **值域闸门**抽成**唯一落点** `source/SourceIdDomain.kt`（v2.6.1 的
   `ArtistNavigator.idDomainMatches` 改为委托它，**行为零变化**，v2.6.1 单测原样通过）：
   ncm 吃十进制、QQ 吃 base62 mid。`pmid` 的 `_` 在这里被挡下。
4. **置信度闸门**：`crossSourceJump(confidence, targetSource, targetId)` 要求
   `MatchConfidence.mergeable`（全应用唯一阈值）**且**目标值域合法；任一不满足 ⇒ 跳搜索。
5. **兜底跳搜索**：身份不可信时切到搜索 tab 并预填关键词，**并给一句提示**；
   关键词优先用专辑名，专辑名缺失（老缓存连名字都没有）时回落到「曲名 + 艺人名」。
6. **观测性**：这条路径第一次有了 `TAG_ALBUM_NAV` 日志（修复前 release 包零日志，
   见 `probe-logcat.md`）。
7. **收藏专辑表补 `albumMid`**（`SavedAlbumCodec.AlbumDto` / `AlbumInfo`，显式
   `@SerializedName`），老形状三读法原样兼容，无 `albumMid` ⇒ 标记「身份不可信」。
