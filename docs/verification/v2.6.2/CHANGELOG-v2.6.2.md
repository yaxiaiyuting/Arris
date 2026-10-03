# v2.6.2 · CHANGELOG（修复说明）

> 修复版本 `v2.6.2-gpl`（versionCode **50**），一行代码之外的改动都不在本版范围内。
> 目标只有一个 P0：**QQ 曲目「转到专辑」跳到错误的专辑 / 静默无反应**。
> 根因与 v2.6.1 的「转到歌手」P0 **同构**，探针见 `PROBE-SUMMARY.md`。

---

## 1. 用户看得见的变化

| 场景 | 修复前（`v2.6.1-gpl`） | 修复后（`v2.6.2-gpl`） |
|---|---|---|
| QQ 曲目 →「转到专辑」 | 跳到**另一张真实存在的专辑**（陈奕迅《What's Going On...?》→ 陈小云《百万金曲…》；陈奕迅《U 87》→ 邓杰《爱的供养》），页面渲染完全正常 | 进 **QQ 的《What's Going On...?》/《U 87》** 专辑页 |
| QQ 曲目（老队列 / 冷启动恢复）→「转到专辑」 | **毫无反应**：页面不变、无提示、logcat 一条不留 | 切到搜索 tab 并预填关键词，**给一句提示**，并记 `AlbumNav` 日志 |
| ncm 曲目 →「转到专辑」 | 正确 | 正确（**零回归**，真机 A/B 已验） |
| 收藏专辑表 / 队列 / 收藏 / 歌单 里的老数据 | 身份缺失被当成"数字 id 可用" | 读时兼容老形状；**缺身份一律标记「身份不可信」**，绝不猜 |

---

## 2. 改了什么（按层）

### 2.1 映射层：把 QQ 的 `albumMID` 带出来

`qq/QqSongMapper.kt` —— 旧代码把 `album.pmid ?: album.mid` 读进一个**局部 val**
只用来拼封面，`AlbumItem` 没有字段装身份。现在**拆成两个职责**：

```kotlin
val albumPhotoId = albumObj?.optString("pmid")…  ?: albumObj?.optString("mid")…   // 封面
val albumMid     = albumObj?.optString("mid")…                                   // 身份
```

`qq/QqCatalog.kt` 的 `QqCatalogMapper.albumItemOf` 同形修改，两条链路**逐值一致**（有单测）。

> ⚠️ **刻意不取 `pmid` 当身份**：`pmid` 是**封面照片 id**（`004Z85XP1c25b7_5`，尾部 `_N` 是封面序号）。
> 独立探针实测 QQ 服务端**碰巧能容忍**把它当 `albumMid` 传（内部会剥掉 `_N`），
> 但那是服务端的宽容、不是契约。把身份建立在巧合上迟早出错，所以身份只认 `mid`，
> 并由值域闸门（base62，`_` 不合法）把 `pmid` **挡在门外** —— 这一条有专门的单测。

### 2.2 模型层：`AlbumItem` 新增 `mid`

`network/model/SongDetail.kt`：`@SerializedName("mid") val mid: String? = null`。
**可空 + 默认值**是硬要求（Gson 走 Unsafe、不调用构造函数），
`null` 的语义是「**这条数据没有字符串身份**」（老缓存 / 服务端没给），**不是**「不需要身份」。

它同时进了 `PersistenceFieldNameContractTest.PERSISTED_DTOS`：它嵌在 `SongItem.album`
里落盘到 5 张表，字段名被 R8 改掉的表现是「转到专辑静默退化成跳搜索」，页面上看不出是字段丢了。

### 2.3 判定层：新增 `source/AlbumNavigator.kt`

`ArtistNavigator`（v2.6.1）的孪生兄弟，**形状故意做成一样**：

```kotlin
sealed interface AlbumNav {
    data class Direct(val source: MusicSource, val id: String) : AlbumNav
    data class Search(val keyword: String, val reason: AlbumNavReason) : AlbumNav
    data object Unavailable : AlbumNav
}
```

**类型上不存在「跳到另一个源」**——这比"在实现里小心不提"可靠。

三条不变量：

1. **绝不产出与歌曲音源不同的 `Direct`**（`Direct` 的两条出口都写死 `song.musicSource`）；
2. **身份必须过值域闸门**：ncm 十进制 / QQ base62 mid。QQ 的数字 `albumID`、`pmid`
   都在闸门上失败；
3. **置信度不足一律跳搜索**：`crossSourceJump` 要求 `MatchConfidence.mergeable`
   **且**目标值域合法，任一不满足 ⇒ `Search`。

关键词回落链：**专辑名 → 「曲名 + 艺人名」**。第二条不是锦上添花，是实测必需 ——
真机老队列条目的 `al` 只有 `{picUrl}`（连 `name` 都没有），没有这条回落就会退化成
`Unavailable`，也就是本 P0 的第二种症状「点了没反应」。

### 2.4 值域闸门抽成唯一落点：新增 `source/SourceIdDomain.kt`

「ncm 吃十进制、QQ 吃 base62 mid」是**音源的性质**，不是艺人的性质。
v2.6.1 把它写在 `ArtistNavigator` 里；本版修专辑时最自然的动作是**再抄一份**，
而抄一份的代价是**两处会漂移的规则** —— 症状恰好就是「艺人跳得对、专辑跳错」。

所以把规则搬到 `SourceIdDomain`，两个 Navigator 都委托它：

```kotlin
// ArtistNavigator —— v2.6.1 的公开入口与语义**一字未改**
fun idDomainMatches(source: MusicSource, id: String?): Boolean = SourceIdDomain.matches(source, id)
const val NETEASE_ID_MAX: Long = SourceIdDomain.NETEASE_ID_MAX
```

**行为逐字不变**：v2.6.1 的 `ArtistNavigatorTest`（17 个用例）原样通过，
是这次搬迁的回归网；`SourceIdDomainTest` 另外穷举断言两个 Navigator 与
`SourceIdDomain` 逐值一致（分叉即红）。

### 2.5 出口层：`MainActivity.navigateToAlbum`

```kotlin
fun navigateToAlbum(song: SongItem) {
    when (val nav = AlbumNavigator.resolve(song)) {
        is AlbumNav.Direct -> { collapseCard(); Log.i(TAG_ALBUM_NAV, …); navigate(NavRoutes.album(nav.source, nav.id)) }
        is AlbumNav.Search -> { selectedTab = SEARCH_TAB_INDEX; pendingSearchQuery = nav.keyword
                                snackbar.show(albumNavSearchFallback); Log.i(TAG_ALBUM_NAV, …) }
        AlbumNav.Unavailable -> Log.w(TAG_ALBUM_NAV, …)
    }
}
```

与旧实现相比**删掉了两件事**：

- **删掉 `Dispatchers.IO` 协程**：身份就在 `song` 里，判定是纯函数，不需要线程切换；
- **删掉那处"没有回落"**：旧实现 `albumId == null` 时直接静默 no-op（连网络请求都不发），
  现在走 `Search`，**有提示、有日志**。

**零新增路由**：`NavRoutes.album(source, id)` 那条带音源的两段路由 v2.4.0 就有了，
只是这条路一直没用它。

### 2.6 缓存层：`SavedAlbumCodec` / `AlbumInfo` 新增 `albumMid`

- `SavedAlbumCodec.AlbumDto` 新增 `@SerializedName("albumMid")`，`SCHEMA_VERSION` 2 → 3；
- `AlbumInfo` 新增 `mid: String? = null` 与 `identityTrusted`（判据只有一处定义，
  与 `MatchConfidence.mergeable` 同一个形状）；
- **三种读法照旧**（稳定名 / v1 单字母 / 声明顺序），`albumMid` 这一维：

| 落盘形状 | 读成 | 语义 |
|---|---|---|
| 稳定名且有 `albumMid` | 该值 | 身份可信 |
| 稳定名但没有这个 key（v2.6.2 之前） | `null` | **身份不可信** |
| v1 单字母 `a`~`e` | `null` | **身份不可信** |
| 未知 key 集合、6 个非空值 | 第 6 个 | 按声明顺序（`albumMid` 是最后一个声明的） |
| 未知 key 集合、5 个非空值 | `null` | **身份不可信** |

> **刻意不往 `LEGACY_KEYS` 里补第六个字母 `f`**：v1 的结构**只写过五个字段**，
> 补一个 `f` 等于**发明**一条没有取证支撑的映射 —— 那比"读不出来"更糟
> （它会安静地把某个别的字段读成专辑身份）。这条由单测钉住。
>
> **写时**：有身份才写这个 key（Gson 默认跳过 null）。这样下次读到的人能分清
> 「字段缺失（老数据）」与「字段是 null」，符合 AGENTS.md v1.9.3 规则 2。

### 2.7 i18n

新增 `albumNavSearchFallback`（8 个语言文件全补齐）。文案只说「已为你搜索」，
**不说"跳错了"** —— 这条提示只在"我们没有可靠身份"时出现，正确结果是用户自己搜到那张专辑。

---

## 3. 一个**有意保留的不对称**

艺人出口（v2.6.1）只在**降级**时留痕；专辑出口**成功路径也记一行**
（`I AlbumNav: 转到专辑 source=qqmusic id=003J6fvc0bVJon song=…`）。

理由：本版的验收要求 logcat 里能读到**路由参数**，而修复前这条路径在 release 包里
**一条日志都没有**（`probe-logcat.md`）—— 用户报"跳到一张莫名其妙的专辑"时，
唯一能把"点了哪首歌"和"打开了哪张专辑"对上的就是这一行。
**不为对称去动 v2.6.1 的艺人出口**：那一版的验收已经关闭，动它属于扩大本版的面。

---

## 4. 明确的**不做**（本版边界）

| 不做的事 | 理由 |
|---|---|
| 不改艺人跳转的任何行为 | v2.6.1 已验收；本版只做了**行为等价的**闸门搬迁 |
| 不修「折叠态播放器卡的命中带吞掉菜单最后几行」 | 既有缺陷（`AGENTS.md` 触摸陷阱第 2/5/6 条），与本 P0 无关，见 `probe-album-jump.md` §5 |
| 不给搜索历史加专辑身份字段 | `SearchHistoryManager.HistoryItem` 里**根本没有专辑字段**（只有 `coverUrl`），为它新造一个字段属于另一个改动；这些曲目走「跳搜索」是正确处置 |
| 不支持 QQ 专辑收藏 | `subscribeAlbum` 写的是 ncm 订阅；`AlbumDetailScreen` 对 QQ 一侧**不挂**收藏按钮（v2.4.0 既有决定）。本版给 `AlbumInfo` 加 `mid` 只是把身份补上，没有打开这个能力 |
| 不碰华为控制中心卡片 | 任务书禁止项 |
| 不做跨源专辑匹配跳转 | `crossSourceJump` 闸门保留但当前**恒返回 null**（这条路上没有任何可用的匹配结论）。要做跨源跳转必须先有 `MatchConfidence.mergeable` 的结论，那是另一个版本的事 |

---

## 5. 测试

| 项 | 结果 |
|---|---|
| `./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease` | **BUILD SUCCESSFUL**（5 分 16 秒，在**最终 HEAD** 上重跑，日志 `verification/gradle-clean-fullbuild-final.log`） |
| 单测 | **111 个suite / 1525 个用例 / 0 失败 / 0 错误 / 0 跳过** |
| 本版新增用例 | `AlbumNavigatorTest` 24、`SavedAlbumMidMigrationTest` 14、`QqAlbumMidMappingTest` 11、`SourceIdDomainTest` 10 = **59** |
| 受影响的既有用例 | `SavedAlbumCodecTest` 11（形状断言按新字段更新 + 新增两条「可空字段不许写出 key」）、`PersistenceFieldNameContractTest` 10（注册表 +`AlbumItem`）、`ArtistNavigatorTest` 17（**未改一行**，作为搬迁的回归网） |
| lint | **0 errors / 0 Fatal** / 9 warnings（全部是既有基线之外的风格提示），`verification/lint-results-debug.txt` |
| 产物 | `dist/Ncrust-v2.6.2-gpl-release.apk`（`aapt2 dump badging` = `versionCode='50' versionName='2.6.2-gpl'`）、`dist/Ncrust-v2.6.2-gpl-debug.apk` |

单测覆盖的六条（对应任务书 §4.1）：

1. **专辑跳转必须携带 source** —— `跳转路由必须把 source 编进路径`、
   `任何情况下 Direct 的 source 都等于歌曲自己的音源`、
   `所有宿主入口的曲目形状都产出带 source 的参数`；
2. **source 与 albumId 值域匹配** —— `ncm值域只吃十进制…`、
   `QQ 值域只吃 base62 且拒绝ncm值域内的纯数字`、`QQ 的 pmid 不是身份`、
   `SourceIdDomainTest` 全套；
3. **匹配置信度不足时跳搜索** —— `跨源跳转只有 mergeable 才放行`、
   `跨源跳闸门两道都要过…`、`当前这条路径上没有可用的跨源结论`；
4. **各入口跳转参数正确** —— `所有宿主入口的曲目形状都产出带 source 的参数`
   （9 个宿主交出的是同一个 `SongItem`，所以判据是"同一首歌无论从哪条路来，
   `(source, id)` 一致"）；
5. **老缓存无 albumMID 时跳搜索** —— `真机老队列形状的条目解出来没有任何可信身份`
   （夹具是 S6 真机落盘 JSON 的**原文**）；
6. **缓存迁移** —— `SavedAlbumMidMigrationTest` 全部 14 条。

---

## 6. 未修 / 未验证（如实记录）

见 release notes 与 `verification/DEVICE-VERIFICATION.md` 的「未验证缺口」一节。
