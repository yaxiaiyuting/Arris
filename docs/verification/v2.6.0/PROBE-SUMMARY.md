# v2.6.0 探针汇总（PROBE-SUMMARY）

> 版本：`v2.6.0-gpl` · 基线 HEAD `7a4e33b`（v2.5.6）
> 四份探针：`probe-qq-import-loss.md` / `probe-artist-playall.md` /
> `probe-layout-switch.md` / `probe-fold.md`
> 设备：S6（SM-G9209 / Android 7.0 / 已 root）、PLC110（Android 16）、
> WGR-W09（华为平板 / EMUI 12）、Chromium 之外无其它依赖

本文件是四份探针的**收敛结论**。每条结论都注明证据级别：

- **【实测】** = 本机 / 真机上跑了命令或读了真实落盘数据，原始输出留档在 `EVIDENCE.md`；
- **【读码】** = 逐行读源码 + `file:line` 引用，未上机；
- **【推断】** = 由前两者推出，**未直接验证**（在最后一节单独列出）。

---

## 0. 探针推翻了任务书的哪些前提（**下一个读任务书的人先看这里**）

| # | 任务书原文 | 实测 | 处置 |
|---|---|---|---|
| 1 | §1「P0：QQ 歌曲长按入库后刷新消失」隐含「QQ 歌单是只读镜像，入库写到别处」 | **前提基本成立但需修正**：QQ 歌单**确实是只读镜像**（本地不存它的曲目），但「入库」写的是**ncm 红心歌单的镜像** `LibraryManager.saved_songs` —— 一个 QQ 曲目**永远进不去**的表。见 §1 | 按「同步口径错误」修，不是按「QQ 只读」修 |
| 2 | §0 铁律 23「跨源混播去重…（本版新增）」 | 仓库里**从来没有**编号 23 的铁律（`grep -rn "铁律 *2[3-9]"` → 0 命中），该规则的实质早已存在：AGENTS.md **v2.4.0 三条新规则第 1、2 条**（`MatchConfidence.mergeable` ≥ MEDIUM 是唯一阈值） | 本版把 22/23/24 三条**真正写进 AGENTS.md**（任务书 §9 要求），并把「编号漂移」这件事一并修掉 |
| 3 | §4.1「跨源混播去重…复用 v2.4.0 的匹配规则**或新建**」 | 主路径**已经做完了**：聚合器 `assembleSongs` 已把 `mergeable` 的对端行吸收掉（`CatalogAggregator.kt:372-374,413`）。真正的增量只有两件：① 给歌手页加 ▶ 入口；② 补 **`TrackKey` 轴**去重（`replaceQueueAndPlay` 一句判重都没有） | P1 按「入口 + 一条正交轴」做，**不改** `replaceQueueAndPlay`（14 处调用，含 FM 电台） |
| 4 | §5.1「两个源（ncm、QQ）统一使用同一布局」 | 这一页是**三个**按源分区：本地歌单 / ncm / qm，**本地那一段也是网格卡片** | 按**三段统一**做。只统一两段会留下一个「本地卡片 + 两源列表」的半切换页面 |
| 5 | §2.3 隐含「布局切换有一个零行为变化的默认值」 | **不存在**：改造前这一页本身就是混合形态（本地/ncm 卡片 + QQ 整行） | 默认值取 **CARD**（保留面积最大的两段现状），理由与代价写进 `PlaylistLayout` 的 KDoc 与 release notes |
| 6 | §1「P2：歌单手动折叠」隐含「已有折叠概念」 | 本页**确无**任何折叠概念（三条独立路径交叉证否，见 `probe-fold.md` §3） | 按**新增**做；折叠单位取「区块」而不是「单张歌单」 |
| 7 | §2.1 问「QQ 歌单的『我喜欢』等特殊 dirId 是否导致写入被忽略」 | **不是**。`dirId` 是**载荷不是身份**（v2.2.0 已判决），特殊歌单只影响 `isFavorite` 排序；写入被忽略的原因与 dirId 完全无关 | 探针第 5 问的答案是「否」，不进修复清单 |
| 8 | §2.1 问「是否与 v2.2.0 的只加不减 + tombstone 冲突」 | **不冲突、也没关系**：v2.2.0 的实现在 `local/LocalPlaylistSync`，它**只追加、从不删除、从不 tombstone**，方向正确（27 个既有 `@Test` + 本次转写实跑复核）。丢歌的是 `library/LibraryManager`，它**根本没有 origin/tombstone 概念** | 根因不是「tombstone 逻辑反向」，而是「这条纪律从未被应用到收藏库上」 |
| 9 | §2.4 隐含 `BottomOverlayInsetDp` = 144/64dp（AGENTS.md 也这么写） | **168dp（窄）/ 88dp（宽）**（`TrayLayout.kt:163-165`）；144/64 是 v2.5.4 **之前**的字面量 | 本版未改这个值（与本版范围无关），但 AGENTS.md 那条过期数字在 `EVIDENCE.md` 里点名 |

---

## 1. QQ 入库丢失的根因（探针问题一）

### 一句话

「入库」写进的是**ncm 红心歌单的镜像**（`ncrust_library` / `saved_songs`），
而 `refreshFromCloud` 用**云端 `likedIds` 顺序整体重建**这张表 ——
不在云端红心歌单里的条目**连候选都不是**，被静默物理丢弃，紧接着 `scheduleFlush`
把丢掉的结果写回磁盘。QQ 曲目的 id 由 `SourceIds.qqId` 合成（`bit62` 恒置位，≥ 2⁶²），
而 ncm songId < 2⁴⁰，**两个值域不相交 ⇒ QQ 曲目 100% 命中这条路径**。

### 存储位置 / 持久化 / 同步覆盖（任务书 §2.5 要求明确回答的三问）

| 问题 | 答案 | 证据 |
|---|---|---|
| **存储位置** | `LibraryManager.saveSong` → 内存 `cachedSongs` →（去抖 300ms）→ `ncrust_library` / `saved_songs`（SharedPreferences，JSON） | 【读码】`LibraryManager.kt:135-167,195-206` |
| **是否立即持久化** | **不是**。`scheduleFlush` 有一次 `delay(300L)` 去抖；`apply()` 本身是异步的 ⇒ 用户加完歌立刻从最近任务划掉，这一条**不会落盘** | 【读码】`LibraryManager.kt:135-144` |
| **刷新触发什么** | 进库页（`LaunchedEffect(Unit)`）与登录后（`refreshTrigger`）各调一次 `LibraryManager.refreshFromCloud`；它用 `likedIds` **重建整张表** | 【读码】`LibraryScreen.kt:165-176`、`LibraryManager.kt:291-356` |
| **存活谓词** | `刷新前已缓存 X 存活 ⟺ X ∈ PlaylistApi.getLikedTrackIds(uid)`（除 refresh 在取 likedIds 前就 return/抛异常） | 【实测】探针把该重建块逐句转写成 Java 实跑（`JAVAC_EXIT=0 / JAVA_EXIT=0`），QQ 合成 id `survived=false`，谓词对全部样本成立 |

### 真机落盘证据（【实测】）

S6 上 `su -c cat /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_library.xml`：

```text
<string name="saved_songs">[{"al":{…},"ar":[…],"dt":201437,"id":1469825684,"name":"燕无歇"}, …]</string>
```

- **147 条**，每条的 key 恰好是 `al ar dt id name` 五个 ——
  **没有** `source`、**没有** `origin`、**没有** `tombstone`（Gson 把 null 字段整条省掉）；
- `ids >= 2^62` 的条目数 = **0** ⇒ 磁盘上**一条 QQ 曲目都没有**，与根因预测一致；
- 原始 XML 留档：`verification/ncrust_library-BEFORE-S6.xml`。

### ncm 是否同样受影响（探针问题六）

**是，条件性。** 存活谓词与 QQ 完全相同（**与音源无关**），差别只在可满足性：

| 情形 | 结果 |
|---|---|
| 加的是 QQ 曲目 | **必然丢**（合成 id 永远进不了 `likedIds`） |
| 加的是 ncm 曲目，且 `like` 真的生效 | 存活（它在 `likedIds` 里） |
| 加时未登录 | `pushLike` 被 `saveSong` 的卫语句跳过 ⇒ **下次刷新消失** |
| 加时离线 / like 被风控拦 | 写请求失败被 `runCatching` 吞掉 ⇒ **下次刷新消失** |

`PlaylistApi` 自己的 KDoc 就记着 like 写受风控（`PlaylistApi.kt:686-688`），
且 v2.5.6 的账号上四种协议变体**全部被 `-460` 拦**。

### 本版顺带发现的**第二条**数据丢失路径（【读码】+【实测不可达性未验证】）

`flushToDisk` 旧写法是 `cachedSongs?.toList() ?: emptyList()` ——
把「内存里还没加载」与「内存里确实是空的」压成同一个取值。于是
**任何一次在没有加载过收藏单曲时触发的 flush 都会把 `saved_songs` 写成 `[]`**。
这条路可达：`subscribeAlbum`（收藏一张专辑）直接 `scheduleFlush`，
既不读也不写 `cachedSongs` ⇒ 冷启后第一件事去专辑页点收藏，300ms 后整张表被清空。
本版已修（三个键各自独立判断，`null` ⇒ 不 put）。

### 队列 / 待播槽位是否受影响（探针问题八）

**不受影响，但有两个次级缺口**（都在本版一并处理或如实记录）：

- 队列身份走 `TrackKey`（v2.5.3 起），与 `saved_songs` 无耦合 ⇒ **主链路安全**；
- 但 `pushLike` 把 QQ 合成 id 原样 POST 给 ncm `/api/radio/like`（**无任何闸门**），
  本版在 like 的唯一出口加闸门（判据 `SourceIds.isQqId`，与 v2.5.5 的 `ReportGate` 同源）；
- 8 个 `saveSong` 调用点**全部无条件弹「已加入库」**（因为 `saveSong` 返回 `Unit`）——
  用户看到的成败与真实成败完全脱钩。本版改成按返回值提示。

---

## 2. 跨源混播去重方案（探针问题二）

### 结论

**主路径已经做完了**（聚合器按 `mergeable` 合成一行、低置信度保留两行），
本版只需补**两条正交的轴**并加一个入口：

| 轴 | 判据 | 落点 |
|---|---|---|
| A · 跨源同曲 | `CrossSourceMatcher.gradeTrack` 的结论 + `MatchConfidence.mergeable`（**唯一阈值**） | `PlayAllDedup.plan` 复用 `pairTracks`，**不新建判据** |
| B · 同源同号 | `TrackKey` 相等（统一用 `TrackKey.ofSong`，带 bit62 回落） | 同上 |

### 为什么**不**另写「歌名 + 歌手字符串相等」的判据

铁律 23 的字面要求是「按歌曲名+歌手匹配」，但**判据本身必须是 v2.4.0 那一套**：

- 那套判据是 230 首真实样本标定过的（时长容差 2s、版本标记、艺人重叠）；
- 「唯一阈值」这条纪律的意义就是**不允许第二个判据** —— 两处一旦分叉，
  页面上「合并成一行」与「只播一遍」就不是同一件事（用户看到一行却听到两遍，或反过来）；
- 字符串相等的写法会把两类**必须区别对待**的情况一视同仁：
  `晴天` vs `晴天 (Remix)`（时长相同 ⇒ HIGH ⇒ 合并是对的）与
  `晴天` vs `晴天 (Live)`（时长差 40s ⇒ LOW ⇒ **必须保留两份**）。

### 置信度不足时保留两份（铁律 23 的实测支撑）

`mergeable = false`（`LOW`/`NONE`）时**一条都不合并**。`LOW` 的三种来源：

| `LOW` 的来源 | 实测含义 |
|---|---|
| 仅曲名一致 | 同名不同曲（翻唱、合唱） |
| 仅曲名 + 时长一致（艺人不同） | 同上 |
| 艺人一致但时长差 > 2s | **实测 > 30s 的 15 条全部是同名不同版本**（伴奏 / Live / 加长版） |

合并第三类 = **把用户想听的那一首删掉**。本版的单测里「不许合并」的用例数
**多于**「必须合并」的用例数（这是有意的配比）。

### tie-break（合并后播哪一份）

三级、全确定性：① 更能播的那一份（`availabilityOrder` 小者胜）→
② 页面默认音源 `ArtistPage.preferredSource` → ③ 列表先出现的那一份。

---

## 3. 布局切换持久化方案（探针问题三）

| 项 | 取值 | 依据 |
|---|---|---|
| prefs 文件 | `ncrust_settings` | 全仓库用户显示偏好的**唯一**落点（34 处读写都在此）【读码】 |
| key | `library_playlist_layout`（`Int`） | 与 `theme_color_index` / `lyrics_word_animation` / `lyrics_sweep_quality` 三个既有先例同形 |
| 取值 | `0 = CARD`（默认）/ `1 = LIST` | 越界/类型错配一律回落 `CARD`，**不抛异常** |
| 迁移 | **不需要** | 全新键，v2.6.0 之前不存在任何布局偏好（`grep` 已证）⇒ 没有旧形状可认 |
| 落点 | `ui/screen/PlaylistLayoutSetting.kt` | 与唯一消费者同包（`PageTransitionSetting` 的既有形状） |

### 「切换后立即生效」怎么保证（这一条比持久化更容易做错）

**容器一个都不换，只换 `columns`**：`GridCells.Adaptive(160.dp)` ↔ `GridCells.Fixed(1)`。

为什么不能写成 `if (list) LazyColumn else LazyVerticalGrid`：

1. 本页**没有**向滚容器传 `state`，两个容器各自 `rememberLazyGridState` ⇒ **丢滚动位置**；
2. 更糟的是把它包在调用点上，会连 `remember { PlaylistLoadCoordinator() }` 与
   `qqHasLoadedOnce` 一起重置 ⇒ **真的会重发一次 QQ 歌单请求** ——
   那正好违反任务书 5.1 的「切换后数据不刷新」。

### 两套规格（探针逐值从既有代码量出，没有新造尺寸）

| | 列表式 | 卡片式 |
|---|---|---|
| 列 | `GridCells.Fixed(1)` | `GridCells.Adaptive(160.dp)`（窄屏 2 列 × 179dp） |
| 封面 | 48dp | `fillMaxWidth().aspectRatio(1f)` |
| 圆角 | `AppShapes.small`（8dp）+ 1dp 描边 | `AppShapes.large`（16dp）+ 1dp 描边 |
| 行高 / 间距 | 64dp（48 + 8×2）、封面↔文字 12dp | 格间距 2dp、▶ 36dp 右下 `padding(6.dp)` |
| 标题 / 副标题 | `bodyLarge` 单行省略 / `bodySmall` | `bodyMedium` 单行 / `bodySmall` |

---

## 4. 折叠交互与持久化方案（探针问题四）

| 项 | 取值 | 依据 |
|---|---|---|
| **折叠单位** | **按源分区的三个区块**（本地 / ncm / QQ） | 「我只想看 QQ 那一段」是这一页最常见的诉求；按单张歌单折叠等于让用户收起 99 张 |
| prefs 文件 | `ncrust_settings` | 同上 |
| key | `library_section_collapsed_local` / `_netease` / `_qq`（三个独立 `Boolean`） | 生产代码 `putStringSet` **0 命中**（只在测试 FakePrefs 里）⇒ 不引入 `Set<String>`；键集编译期固定 3 个，用集合只换来两个额外失败面 |
| 默认 | 全 `false`（展开） | 老用户升级零行为变化；同时满足「绝不自动折叠」 |
| 状态归属 | **prefs**，不是 `remember` | 本页被 `AnimatedContent(targetState = selectedCategory)` 包裹，**切走再切回会卸载整棵子树** —— 只放 `remember` 会静默展开（用户看到「我收起来的又自己打开了」） |
| 折叠时显示 | 标题右侧 `展开全部 N 个`（收起态）/ `收起`（展开态） | 显示**数量**是因为用户要判断「值不值得展开」；不带数字的「已收起」回答不了 |
| 展开/收起动画 | `animateItem(fadeIn 150ms / placement 220ms / fadeOut 120ms, MetroDefault)` | 这**正是本文件里已有的取值**（本地歌单段 v2.5.0 就在用）。`expandVertically`/`shrinkVertically`/`animateContentSize` 全仓 **0 命中** ⇒ 不引入新机制 |
| 覆盖补齐 | ncm 段与 QQ 段**目前没有 `animateItem`**，本版一并补上 | 不补就是「本地淡出、另两段硬跳」，平滑只满足 1/3 |
| 折叠按钮位置 | **区块标题整行可点** | QQ 段的标题不是 `SectionHeader` 而是带刷新按钮的内联 `Row` —— 做进 `SectionHeader` 会**漏掉 QQ** |
| 折叠 QQ 时是否停掉网络加载 | **不停** | 本仓库的闸门是 **tab 级**的；展开后要等一次网络往返会违反「先渲染、绝不空白 + 加载」的既有模式 |

### 「不自动折叠」的保证方式（**结构性**，不是注释）

- `LibrarySectionFold` 的唯一状态迁移 API 是 `toggled(section)`，**没有**任何
  「按数量 / 按加载结果设置状态」的入口；
- 一条反射单测把那个事实钉成断言：白名单外的公开方法出现即失败 ——
  将来若有人加一个 `collapseIfLarge(n)`，它会红，而 code review 很可能会放过它；
- 调用点（`LibraryScreen` 的 `onToggleSection`）是**唯一**写这个状态的地方。

---

## 5. 未验证项（**未测得 ≠ 没做，但没测得就不许写成测得**）

| # | 项 | 为什么没测 | 谁会受影响 |
|---|---|---|---|
| 1 | 「QQ 歌单详情长按入库」这一条**确切点击路径** | HEAD 的 `QqPlaylistDetailScreen.kt:257` 传的是 `emptyList()`，那张菜单里**没有**「加入库」（`grep -c LibraryAdd` = 0；阳性对照 `PlaylistDetailScreen` = 2）。QQ 曲目可达的「加入库」是**播放器卡片按钮**与**搜索结果/历史菜单** | 需要一张用户菜单截图 + versionName 才能定案；本版修的是**共享的存储与同步层**，所以只要走的是 `LibraryManager`，路径差异不影响修复有效性 |
| 2 | `subscribeAlbum` 触发 `flushToDisk` 清空收藏单曲的**时序可达性** | 【读码】已证明代码路径存在，**未在真机上复现**（需要精确的操作时序） | 本版已把它修掉（三个键独立判断），所以即使可达也已关闭 |
| 3 | 布局切换的**帧时间**（release 包） | 本仓库**没有任何歌单 tab 的滚动基准**（`grep -rn 歌单 benchmark/src` → exit 1）。新增一条基准需要设备 + 时间预算，且必须 release 包 | 结论：本版**不声称**「性能无显著下降」（铁律 22）。切换只改 `columns` 与条目的 `if`，不新增逐帧动画 |
| 4 | QQ 主源歌手页 | `NavRoutes.artist(source,id)` 的唯一调用点 `AlbumDetailScreen.onArtistClick` 在 `source != NETEASE` 时恒不触发 ⇒ **QQ 主源歌手页今天不可达**，其歌单是单源的 | 「全部播放」的跨源去重在 QQ 主源页面上走不到；ncm 主源页面（可达的那条）**已实测** |
| 5 | 「全部播放」在**真实登录态**下的端到端播放 | 需要真机 + 登录 + 点击 | 已列为真机验证项，见 `EVIDENCE.md` |
| 6 | `MatchCacheStore.track()/putTrack()` 是死代码 | 【实测】`grep` exit=1（正对照 `putArtist/putAlbum` 各命中） | 不影响本版（本版不需要它）；如实记录，不顺手删 |
| 7 | 结论 1 的转写验证是**逻辑等价**而非同一份字节码 | 探针把重建块逐句转写成 Java 实跑，证明的是**判据**而不是那个 class 文件 | 判据已由 4 份新单测在 Kotlin 侧再钉一遍 |

---

## 6. 四份探针各自的一号结论（便于交叉索引）

| 探针 | 一号结论 |
|---|---|
| `probe-qq-import-loss.md` | 根因 = `refreshFromCloud` 用云端 `likedIds` **重建整张表**，本地兜底只在 `id ∈ likedIds` 时才被查到 ⇒ 「不在云端红心歌单里的条目」连候选都不是 |
| `probe-artist-playall.md` | 跨源去重的主路径**已经做完**；真实增量是「入口 + `TrackKey` 轴」，且**不要**改 `replaceQueueAndPlay`（14 处调用） |
| `probe-layout-switch.md` | 「两个源」实际是**三个**；**不存在**零行为变化的默认值；容器**不许换**（会丢滚动位置并重发 QQ 请求） |
| `probe-fold.md` | 本页**确无**折叠概念；`BottomOverlayInsetDp` 是 168/88 而不是文档里的 144/64；QQ 的标题不是 `SectionHeader` |
