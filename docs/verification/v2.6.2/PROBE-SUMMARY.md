# v2.6.2 探针汇总（PROBE-SUMMARY）

> 目标：QQ 曲目二级菜单「转到专辑」跳到**错误的专辑**（或被静默吞掉）。
> 目标版本 `v2.6.1-gpl`（versionCode 49），修复版本 `v2.6.2-gpl`。
> 方法：静态全树审计 + 公开接口取证 + **两台真机**受控复现 + 同机交叉 A/B。
> **任务书 §2 的初步判断被本探针修正了一条**，见 §2。

---

## 1. 复现路径与稳定性

### 1.1 最短复现路径（用户报告的路径）

> 搜索 `Eason Chan` → 结果里同时出现 ncm 与 QQ 两条同名曲目 → **长按 QQ 那一行** →
> 二级菜单 → 点「转到专辑」→ 落地页是**另一张真专辑**

| 设备 | 版本 | 曲目（QQ） | QQ `album.id` | 落地页 | 稳定性 |
|---|---|---|---|---|---|
| PCL110（OPPO / Android 16） | `v2.6.1-gpl` (49) | 葡萄成熟时 / 陈奕迅《U 87》 | `7879` | **《爱的供养》/ 邓杰** | **2/2** |
| S6（三星 G9209 / Android 7.0） | `v2.6.0-gpl` (48) | 富士山下 / 陈奕迅《What's Going On...?》 | `22276` | **《百万金曲 陈小云2 苦恋梦 免失志》/ 陈小云** | **2/2** |

**两台设备落到两张不同的错专辑** ⇒ 排除"某个写死的 fallback 值"，
指向"QQ 的数字 id 恰好命中了 ncm 的哪张专辑"。

原始证据：
`screenshots/before-pcl-01-qq-song-menu.png` → `before-pcl-02-goto-album-result.png`；
`screenshots/before-s6-01-qq-song-menu.png` → `before-s6-02-goto-album-result.png`；
语义树 `probe-raw/s6-before-menu-tree.txt`、`probe-raw/s6-before-album-tree.txt`；
`logcat-before-pcl-album-jump.txt`（`/api/v1/album/7879` —— QQ 的 album id 打在 ncm 的接口上）。

### 1.2 第二种症状：**点了没反应**

S6 真机上**真实存在**的队列条目（`probe-raw/s6-persisted-qq-queue-entry.json`）：

```json
{"al": {"picUrl": "https://y.qq.com/music/photo_new/T002R500x500M000002Neh8l0uciQZ_3.jpg"},
 "ar": [{"name": "周杰伦"}], "id": 4611686018427837109,
 "name": "稻香", "source": "qqmusic", "mid": "003aAYrm3GE0Ac"}
```

`al` 的 key 集合是 **`{picUrl}`** —— 没有 `id`、没有 `name`。把它放回
`ncrust_playback_state` 冷启动，「转到专辑」**页面完全不变、无提示、logcat 一条不留**
（因为专辑分支在 `albumId == null` 时连网络请求都不发）。

### 1.3 同机交叉 A/B：症状随**数据来源**切换，不随机型切换

同一台 S6、同一个搜索页、同一个长按手势、同一个菜单项：

| 点的是哪一行 | 菜单里的曲目头 | 落地页 | 结果 |
|---|---|---|---|
| qm《富士山下》 | 陈奕迅 / `What's Going On...?` | 《百万金曲 陈小云2 苦恋梦 免失志》/ 陈小云 | ✗ **错专辑** |
| ncm《富士山下》 | 陈奕迅 / `What's Going On…?` | 《What's Going On…?》/ 陈奕迅（17 首） | ✓ **正确** |

---

## 2. 任务书判断 vs 实测（一条被修正）

| 任务书 §2 的假设 | 实测 | 证据 |
|---|---|---|
| QQ `album.mid`（albumMID）在映射层被丢弃 | **成立**。更隐蔽：它**被读出来了**，但只喂给封面 URL，`AlbumItem` 没有字段装它 | `probe-album-jump-static.md` §2.1 |
| 专辑跳转路由不携带 source，只传数值 id | **成立**。`NavRoutes.album(albumId: Long)` 的 composable 把 `sourceKey` 写死成 `NETEASE` | §2.2 |
| 老缓存 DTO 缺 albumMID | **成立**，三层都缺（`AlbumItem` / `SavedAlbumCodec.AlbumDto` / `AlbumInfo`） | §2.4 |
| 「有回落逻辑，回落打的是某个源的接口」 | **不成立（本探针修正）**。专辑分支**没有任何回落** —— `albumId == null` 时直接静默 no-op，**连一次失败的网络请求都没有**。这比艺人那处更难查：logcat 里什么都不留 | §2.3 |
| 「与 v2.6.1 的 ArtistNavigator 同构」 | **成立**，四个环节一一对应；唯一差异是入口数（艺人 2 个、专辑 1 个） | §2.5 / §2.6 |

---

## 3. 根因

**与 v2.6.1 完全同构的两个身份缺陷，叠在同一条路径上。**

### 根因 A（跳错专辑）：QQ 的 `albumMID` 在映射层被丢掉，跳转层又把源写死

1. `qq/QqSongMapper.kt:124-133` 把 `album.pmid ?: album.mid` 读进一个**局部 val**，
   只用于拼封面 URL；`AlbumItem`（`network/model/SongDetail.kt:50-54`）只有
   `id`(QQ 域数字) / `name` / `picUrl` 三个字段，**没有地方装 albumMID**。
   `qq/QqCatalog.kt:343-352` 是第二条同形的映射链路。
2. `MainActivity.resolveAndNavigate` 的专辑分支只读 `song.album?.id`，**从不读 `song.musicSource`**。
3. 老路由 `album/{albumId}`（`NavType.LongType`）的 composable 把 `sourceKey` **写死**成
   `MusicSource.NETEASE` —— QQ 的数字 album id 于是被拿去查 ncm。

> **两个编号空间互不相通**：周杰伦《叶惠美》QQ `8220` vs ncm `18905`；
> 陈奕迅《What's Going On...?》QQ `22276` vs ncm `6451`。
> 拿一个去查另一个不是"查不到"，而是**查到另一张真专辑**：
> `22276` → 《百万金曲 陈小云2 苦恋梦 免失志》/ 陈小云（页面看起来完全正常）。

> ⚠️ **附带纠正一条会写错的字段选择**：搜索响应里 `album` 对象同时给了
> `mid`（albumMID，`000MkMni19ClKG`）与 `pmid`（封面照片 id，`000MkMni19ClKG_5`）。
> 现有代码**优先取 `pmid`**（对封面是对的），照抄它当路由参数就是错的形状。
> 独立探针实测 QQ 服务端**碰巧能容忍** `pmid`（内部会剥掉 `_N`），但那是服务端的宽容、
> 不是契约 —— 本版把身份显式钉在 `mid` 上，并让值域闸门（base62，`_` 不合法）把 `pmid` 挡下。

### 根因 B（没反应）：专辑分支没有任何回落，身份缺失时静默 no-op

```kotlin
// MainActivity.kt:1929-1937（v2.6.1-gpl）
coroutineScope.launch(Dispatchers.IO) {
    val albumId = song.album?.id
    if (albumId != null && albumId > 0L) { … navigate(NavRoutes.album(albumId)) }
    // ← else：什么都不做，不提示，不记日志
}
```

四类真实存在的"没有 id"的 QQ 曲目都会走到 else 分支：

| 来源 | 代码位置 | `album` 的形状 |
|---|---|---|
| 老队列（≤ v2.6.1 落盘的） | `ncrust_playback_state` / `queue` | `{picUrl}` |
| ViewModel 状态恢复 | `MainActivity.kt:944-958` | `AlbumItem(id=null, name="", picUrl=artwork)` |
| 搜索历史重开 | `SearchHistoryMigration.kt:103` | `AlbumItem(id=null, name=null, picUrl=coverUrl)` |
| 聚合器合成候选 | `CatalogAggregator.kt:703` | `AlbumItem(id=null, name=albumName, picUrl=null)` |

### 明确排除的三项

| 排除项 | 判据 |
|---|---|
| 跨源匹配错误 | 这条路径上零次 `CrossSourceMatcher` / `MatchCacheStore` / `MatchConfidence` 调用（`probe-album-jump-static.md` §2.3） |
| 匹配缓存污染 | 它根本没读匹配缓存；且 v2.6.1 已实测缓存内容正确 |
| fallback 跳默认专辑 | **不存在这种 fallback**；存在的是"什么都不做" |

---

## 4. 影响范围（逐条回答任务书 §2.1）

| 问题 | 答案 |
|---|---|
| 只有 QQ？ | **是。** ncm 曲目的 `album.id` 就是 ncm 专辑 id，老路由写死的源恰好正确（真机 A/B 已回归，无回归） |
| 有几个入口？ | **动作 1 个、宿主页面 9 个、出口 1 个**（首页 / 库页 / 搜索 / 歌单 / QQ 歌单 / 本地歌单 / 专辑 / 艺人 / 播放器卡）。全树只有 `MainActivity.kt:2613` 一处构造这个动作（§2.6） |
| 只有二级菜单？ | **是**（与艺人不同：艺人另有"播放页托盘作者名"这个第二出口，专辑没有） |
| 只有陈奕迅？ | **不是。** 撞号与否取决于 QQ 的数字 id 落在 ncm 哪张专辑上。四个样本里 **2 首撞到真人**（陈奕迅两首）、**3 张 404**（周杰伦/林俊杰/邓紫棋各一） |
| 哪些数据形态会中招 | ① 新鲜加载的 QQ 曲目 ⇒ **跳错专辑**；② 老队列/老缓存/冷启动恢复/搜索历史重开 ⇒ **静默无反应** |
| 严重度排序 | **跳错专辑最严重**：页面正常渲染（封面/发行日期/厂牌/曲目数）⇒ 用户会以为"这个应用的专辑数据整体是错的"（铁律 15：跳转错误比找不到更严重） |

---

## 5. 修复方向（详见 `CHANGELOG-v2.6.2.md`）

1. **把 albumMID 带出来**：`AlbumItem` 新增 `mid: String?`（可空 + 默认值，Gson 安全），
   在 `QqSongMapper` 与 `QqCatalogMapper` 两条链路上一起填，加对称单测钉住逐值一致。
   **身份取 `album.mid`、封面继续取 `pmid ?: mid`** —— 两者职责分离，不互相顶替。
2. **身份判定收成一个纯函数**：新增 `source/AlbumNavigator.kt`，产出
   `Direct(source, id)` / `Search(keyword, reason)` / `Unavailable` 三态；
   **类型上就不存在「跳到另一个源」**。
3. **值域闸门抽成唯一落点** `source/SourceIdDomain.kt`（v2.6.1 的
   `ArtistNavigator.idDomainMatches` 改为委托它，**行为零变化**、原单测原样通过）：
   ncm 吃十进制、QQ 吃 base62 mid。
4. **置信度闸门**：`crossSourceJump(confidence, targetSource, targetId)` 要求
   `MatchConfidence.mergeable`（全应用唯一阈值）**且**目标值域合法；任一不满足 ⇒ 跳搜索。
5. **兜底跳搜索**：身份不可信时切到搜索 tab 并预填关键词，**并给一句提示**
   —— 旧行为是静默失败，那正是它拖到用户报告才被发现的原因。
   关键词优先用专辑名；专辑名也缺失（老缓存连名字都没有）时回落到「曲名 + 艺人名」。
6. **观测性**：这条路径第一次有了 `TAG_ALBUM_NAV` 日志（修复前 release 包零日志，
   见 `probe-logcat.md`）。
7. **收藏专辑表补 `albumMid`**（`SavedAlbumCodec.AlbumDto` / `AlbumInfo`，显式
   `@SerializedName`）：老形状三读法原样兼容，无 `albumMid` ⇒ 标记「身份不可信」。

---

## 6. 本探针的产出清单

| 文件 | 内容 |
|---|---|
| `probe-album-jump-static.md` | 静态审计：**逐条回答 §2.2 六问**、9 个入口穷举、值域撞号矩阵 |
| `probe-album-jump.md` | 真机复现步骤、同机 A/B、老缓存溯源、**WGR-W09 未取到干净复现的诚实记录** |
| `probe-logcat.md` | 「修复前这条路径零日志」的取证与可重跑命令；修复后应出现的日志 |
| `probe-raw/probe-album-id-collision.*` | 接口取证：QQ `album` 对象的字段集 + 三个身份候选 |
| `probe-raw/probe-album-cross-domain.out.txt` | 接口取证：QQ 数字 id → ncm 专辑的撞号矩阵（含两例"跳到另一张真专辑"） |
| `probe-raw/probe-album-detail-identity.out.txt` | 接口取证：QQ 专辑接口按 `mid` 命中、按数字 id 报 `104400` |
| `probe-raw/s6-persisted-qq-queue-entry.json` | 真机上"老缓存"QQ 队列条目的原文（`al` 只有 `picUrl`） |
| `screenshots/` | 修复前截图（两台设备 × 菜单/落地页） |
| `EVIDENCE.md` | 证据索引（哪条结论对应哪个文件） |

可重跑脚本（在**仓库外**的 `ncrust-gpl/tools/`，按本仓库惯例不入 git）：
`probe-album-id-collision.py` / `probe-album-cross-domain.py` / `probe-album-detail-identity.py`，
三个都**不依赖账号与 cookie**，匿名可复现。
