# v2.6.1 探针 P1：艺人跳转参数链路静态审计

> 方法：全树 `grep` + 逐文件精读，**只读**，不改任何源码。
> 目标版本：`6ae5de1`（v2.6.0-gpl，versionCode 48）。
> 所有路径相对仓库根目录。

## 0. 结论先行

任务书的初步判断是「匹配错误 / fallback / 缓存污染」。实测**三条都不是**：

| 任务书假设 | 实测 | 证据 |
|---|---|---|
| 走了 v2.4.0 跨源匹配，匹配置信度不足 | **没有走匹配**。跳转路径里一行 `CrossSourceMatcher` 都没有 | §2.1；`grep -rn "CrossSourceMatcher" MainActivity.kt` → 0 命中 |
| 存在 fallback 跳到默认艺人 | **不存在**。fallback 是「补一次 ncm song/detail」，QQ id 查不到 ⇒ 静默放弃 | §2.2 |
| 匹配缓存被污染 | **没被污染**。缓存里存的恰恰是**正确**的对应关系 | §2.3 |
| 只有 QQ / 只有二级菜单 / 只有周杰伦 | QQ 全中；二级菜单**与播放页托盘**都中；不止周杰伦 | §3 |

真实根因是**两个独立的身份缺陷叠在同一条路径上**，见 §4。

## 1. 路由：两套艺人路由，但只有一套在跑

`app/src/main/java/com/takahashirinta/ncrust/ui/navigation/NavGraph.kt`

```kotlin
28:    const val ARTIST = "artist/{artistId}"          // 老路由：NavType.LongType
75:    const val ARTIST_SRC = "artist/{source}/{artistId}"  // v2.4.0 新增：两段，都是 String
80:    fun artist(artistId: Long) = "artist/$artistId"
87:    fun artist(source: MusicSource, id: String) = "artist/${source.key}/$id"
```

老路由的目的地**把音源写死**：

```kotlin
196:        composable(
197:            route = NavRoutes.ARTIST,
198:            arguments = listOf(navArgument("artistId") { type = NavType.LongType })
199:        ) { backStackEntry ->
200:            val artistId = backStackEntry.arguments?.getLong("artistId") ?: return@composable
201:            ArtistDetailScreen(
202:                sourceKey = MusicSource.NETEASE.key,     // ← 音源在这里被"发明"出来
203:                artistId = artistId.toString(),
```

带音源那条路由的注释（`NavGraph.kt:60-74`）自己就写明了意图：

> 旧路由的 id 参数是 `NavType.LongType`，而 **qm 的身份是字符串**：
> 艺人是 `singerMID`、专辑是 albumMid、单曲是 songmid（形状 `0025NhlN2yWrP4`）。
> 用 Long 装它只有两条路，两条都是错的……

**问题不在于新路由写错了，而在于 5 个入口一个都没改。**

## 2. 全部艺人跳转入口（穷举）

`grep -rn "NavRoutes.artist" app/src/main/java` 的全部命中，加上间接调用点：

| # | 入口（file:line） | 传的参数 | 参数域 | 带 source？ | QQ 判定 |
|---|---|---|---|---|---|
| A | `MainActivity.kt:2511-2513` 二级菜单「转到歌手」→ `resolveAndNavigate(song, true)` | `artists[0].id` | QQ 曲目 ⇒ **QQ 数字 `singerID`** | ❌ 走老路由 | **错**（P0 本体） |
| A′ | `MainActivity.kt:2514-2516` 二级菜单「转到专辑」→ `resolveAndNavigate(song, false)` | `album.id` | QQ 曲目 ⇒ QQ 数字 album id | ❌ | **同类错**（本版未修，见 §5） |
| A″ | `MainActivity.kt:2520-2523` 二级菜单「单曲信息」 | `song.musicSource` + `songIdForRoute(song)` | 带源 | ✅ | **正确**（本版照它改） |
| B | `ui/player/PlayerCard.kt:1518` 竖屏托盘作者名 → `PlayerCardOverlay.kt:34,70` → `MainActivity.kt:2169` | `artist.id: Long` | QQ 数字 `singerID` | ❌ | **错** |
| C | 全屏播放器点歌名（`PlayerCard.kt:1016,1149,1218,1282` → `MainActivity.kt:2162-2165`） | 打开菜单 | — | — | 继承 A ⇒ **错** |
| D1 | `SearchScreen.kt:602-608` 搜索「艺人」tab → `MainActivity.kt:2315` | `artist.id: Long` | ncm 十进制 | ❌（但按构造正确） | 正确（列表只由 ncm 填充） |
| D2 | `SearchScreen.kt:381-390` 搜索历史艺人 → 同上 | `HistoryItem.id` | ncm 十进制 | ❌ | 正确但**结构性脆弱** |
| D3 | `SearchScreen.kt:503-519` 搜索歌曲行 | 菜单 | 两源混合 | — | QQ 行 ⇒ **错** |
| E | `ui/screen/AlbumDetailScreen.kt:173-181,214` 专辑页副标题艺人 | `(MusicSource.NETEASE, id)` | ncm 十进制 | ✅ | **正确**（且**有意**不给 QQ 入口） |
| F | `ui/screen/ArtistDetailScreen.kt:258` 艺人页 → 专辑 | `(album.key.source, album.key.id)` | 带源 | ✅ | **正确** |
| G | `ui/screen/HomeScreen.kt:421-430` + `ui/components/ArtistRecoCard.kt:60` → `MainActivity.kt:2255` | `artistId: Long` | ncm 十进制 | ❌（按构造正确） | 正确 |
| H | `MainActivity.kt:1898-1912` 剪贴板 `music.163.com/artist?id=` | `Long`（正则解析） | ncm 十进制 | ❌（按构造正确） | 正确 |
| I1 | `ui/screen/QqPlaylistDetailScreen.kt:257` 长按曲目 | 菜单 | **100% QQ** | — | **错（全表）** |
| I2 | `ui/screen/LocalPlaylistDetailScreen.kt:297-313` | 菜单 | 混合 | — | QQ 行 ⇒ **错** |
| I3 | `ui/screen/LibraryScreen.kt:335-353` 收藏单曲 | 菜单 | 混合 | — | QQ 行 ⇒ **错** |
| I4 | `ui/screen/PlaylistDetailScreen.kt:269-296` | 菜单 | ncm | — | 正确 |
| J | `ui/screen/SongDetailScreen.kt` | — | — | — | **没有艺人入口**（`:213` 是纯文本） |

### 2.1 逐字回答任务书 §2.3 的八个问题

| # | 问题 | 答案 |
|---|---|---|
| 1 | 二级菜单「查看艺人」传了什么参数？ | `SongItem.artists[0].id`（裸 `Long`）。**既没有 source，也没有 TrackKey/ArtistKey**。菜单项的**实际文案是「转到歌手」**（`Strings.actionGoToArtist`，`ui/i18n/zh_CN.kt:239`），全仓库不存在「查看艺人」这四个字 |
| 2 | source 在哪一步丢失 / 被覆盖？ | 有两处，且**都在跳转这一侧**：① `resolveAndNavigate` 只取 `artists[0].id`，从未读过 `song.musicSource`；② 老路由 `ARTIST` 的 composable 把 `sourceKey` **写死**成 `MusicSource.NETEASE.key`。上游映射侧另有一处丢失，见 §4 |
| 3 | 是否走了 v2.4.0 跨源匹配？置信度？ | **没有**。`CrossSourceMatcher` / `MatchCacheStore` / `MatchConfidence` 在这条路径上一次都没被调用 |
| 4 | QQ 艺人 id 与 ncm 艺人 id 数值是否撞车？ | **会**，而且是"撞到一个有内容的真人"这种最坏的撞法：QQ 周杰伦 `4558` → ncm `4558` = **马洪波**（`albumSize=1`、`musicSize=32`）。见 `probe-artist-id-collision.md` |
| 5 | 路由是 `artistId + source` 还是只有 `artistId`？ | **只有 `artistId`**（老路由 `artist/{artistId}`）。带 source 的两段路由存在，但 5 个入口全都没用它 |
| 6 | 是否有 fallback？fallback 值是什么？ | 有，且它**不是**"跳到某个默认艺人"，而是"补一次 `song/detail`"。见 §2.2 |
| 7 | 是否与 v2.6.0 的 TrackKey 改造有关？ | **无关**。TrackKey 只管曲目身份（队列判重/取链/取词），艺人身份从来不在它的轴上。二级菜单的「单曲信息」用的正是 `TrackKey`-侧的正确写法（带 source），而「转到歌手」没有跟上 |
| 8 | 是否与 v2.4.0 的匹配缓存有关？缓存是否被污染？ | **无关且未污染**。见 §2.3 |

### 2.2 fallback：不是"跳默认艺人"，是"静默放弃"

```kotlin
// MainActivity.kt:1838-1861（v2.6.0）
fun resolveAndNavigate(song: SongItem, toArtist: Boolean) {
    coroutineScope.launch(Dispatchers.IO) {
        var target = song
        val idMissing = if (toArtist) target.artists?.firstOrNull()?.id == null
                        else            target.album?.id == null
        if (idMissing) {
            target = runCatching { PlaylistApi.getSongsByIds(listOf(song.id)) }   // ← ncm接口
                .getOrDefault(emptyList()).firstOrNull() ?: song
        }
        val artistId = target.artists?.firstOrNull()?.id
        ...
        when {
            toArtist && artistId != null -> navController.navigate(NavRoutes.artist(artistId))
            !toArtist && albumId != null -> navController.navigate(NavRoutes.album(albumId))
            // ← 两个条件都不成立时：什么都不做，也不提示
        }
    }
}
```

- 补 id 用的是 `PlaylistApi.getSongsByIds` → `/eapi/v3/song/detail`，**ncm 的接口**；
- QQ 曲目的 `song.id` 是 `SourceIds.qqId` 合成的（bit62 恒置位，`source/MusicSource.kt:164`），
  拿它问 ncm 必然返回空 ⇒ `?: song` ⇒ `artistId` 仍是 `null`；
- `when` 两个分支都不匹配 ⇒ **一个字节的反馈都没有**。

这就是用户报告的第二种症状「点了没反应」的成因（真机复现见 `probe-artist-jump.md` §4）。

### 2.3 匹配缓存不但没被污染，它存的是**正确**答案

真机 `ncrust_match_cache.xml`（PCL110 / WGR-W09 两台一致）：

```json
"artist:netease:6452": {
  "confidence": "EXACT",
  "overlap": 33,
  "reason": "唯一同名候选 + 专辑重合 33 张（占较小侧 76%）",
  "aliasesJson": "[{\"id\":\"0025NhlN2yWrP4\",\"name\":\"周杰伦\",\"source\":\"qqmusic\"}]"
}
```

也就是说应用**早就知道**「ncm 6452 ↔ QQ `0025NhlN2yWrP4` 是同一个周杰伦，置信度 EXACT」。
它只是**从来没有在这条路径上问过这张表** —— 因为它的键是 `(netease, 6452)`，
而用户是从 QQ 侧进来的，手上只有 `4558`。

> 这一条直接决定了修复方向：**不需要新建任何跨源匹配**，
> 只需要把 QQ 侧本来就有的 `singerMID` 带出来。见 §4。

## 3. 影响范围

| 维度 | 结论 |
|---|---|
| 只有 QQ？ | **是**。ncm 曲目的 `artists[0].id` 就是 ncm 艺人 id，走老路由正确 |
| 只有二级菜单？ | **否**。二级菜单与**播放页竖屏托盘作者名**（入口 B）是两个独立入口、同一个错法；二级菜单本身还有 9 个宿主页面（首页/搜索/收藏/歌单/QQ 歌单/本地歌单/专辑/艺人/播放器） |
| 只有周杰伦？ | **否**，但只有他会"看起来像一个人"：<br>· 周杰伦 `4558` → 马洪波（有 1 张专辑 / 32 首，页面**正常渲染**）<br>· 林俊杰 `4286` → 刘子译（0 专辑 / 0 单曲，页面**空白**）<br>· 陈奕迅 `143` → 404（页面**报错**） |
| 有没有"看起来没坏"的？ | 有，且这是最危险的一档：QQ `singerID` 恰好落在某个**真实且有内容**的 ncm 艺人上时，页面完全正常 —— 用户会以为"应用的艺人数据就是错的"。这正是铁律 21 说的那种伤害 |

## 4. 根因（两个独立缺陷）

### 缺陷 1：QQ 的 `singerMID` 在映射层被丢弃

`qq/QqSongMapper.kt:110`（v2.6.0）：

```kotlin
ArtistItem(id = a.optLong("id", 0L).takeIf { it > 0L }, name = name)
```

同一个 `a`（`singer[]` 的一个条目）上**一直有 `mid`**。实测字段集（2026-09，匿名可复现）：

```
['id', 'mid', 'name', 'pmid', 'title', 'title_highlight', 'type', 'uin']
```

`ArtistItem`（`network/model/SongDetail.kt`）当时只有 `id: Long?` 与 `name: String`，
**没有任何字段装得下它**。于是 QQ 曲目走到跳转那一刻，手上只剩一个 QQ 域的数字 id。

> 而 `ui/screen/AlbumDetailScreen.kt:170-172` 的注释写着
> 「QQ 一侧拿不到 `singerMID`」—— 这句话对**专辑接口**成立，
> 对**搜索接口**不成立。整个 P0 就建在这句以偏概全的注释上。

### 缺陷 2：跳转层不知道 id 属于哪个源，也不问

- `resolveAndNavigate` 读 `artists[0].id` 就跳，从不读 `song.musicSource`；
- 老路由把 `sourceKey` 写死成 ncm；
- 补 id 的回落是 ncm 接口，对 QQ 的合成 id 必然失败 ⇒ 冷启动恢复的曲目静默无反应。

### 两个缺陷的组合矩阵（真机实测，见 `probe-artist-jump.md`）

| 当前歌曲的数据来源 | `artists[0]` 形状 | 结果 |
|---|---|---|
| 搜索结果里**新鲜**加载的 QQ 曲目 | `id=4558`、无 mid | 跳到 **马洪波** |
| **冷启动恢复**的 QQ 曲目 | `id=null`、只有名字（`MainActivity.kt:920`） | **毫无反应** |

**这两行是同一台手机上先后出现的**（PCL110），所以它不是平台差异 —— 见 `probe-artist-jump.md` §5 的交叉 A/B。

## 5. 本版**不修**的同类问题（如实记录）

| 问题 | 为什么不在本版修 |
|---|---|
| 二级菜单「转到专辑」对 QQ 曲目同样错（`album.id` 是 QQ 数字 id） | 需要 QQ 侧的 `albumMID`，而 `AlbumItem` 不带；为它再加一次跨表迁移 + 5 张持久化结构的回归，会把这次 hotfix 的面扩大到半个数据层 |
| `SearchScreen` 的「艺人」tab 只有 ncm 结果 | 这是**缺失的功能**，不是错误跳转。QQ 艺人搜索端点（`QqCatalogMapper.artistsFromSearch`）已存在但只被跨源匹配使用 |
| `SearchHistoryManager.addArtist` 不落 `source` | 当前唯一写入方是 ncm，行为正确；但只要将来写入一个 QQ 艺人就会变成错误跳转。本版在 `ArtistNavigator` 的值域闸门上兜住（QQ mid 不会被当成 ncm id） |
| `QqCatalogMapper.artistsOf` 零调用方 | 死代码，但与 `QqSongMapper` 是同一件事的两条链路；本版让两条链路**逐值一致**并加对称单测，不删（删它会把回归面扩大到歌单解析） |
| `NavRoutes.song(songId: Long)` 零调用方 | 同上，属"新路由建好、老入口没改"的同类遗留 |

## 6. 本节的产出

- `probe-artist-id-collision.sh` / `.out.txt` —— 接口取证（原始响应已存档）
- `probe-artist-jump.md` —— 真机复现与交叉 A/B
- `PROBE-SUMMARY.md` —— 汇总
- `EVIDENCE.md` —— 证据索引
