# CHANGELOG · v2.5.4-gpl

**发布类型**：五特性版（转发属性量化 + 搜索历史音源修复 + QQ 兜底统计 +
平板横屏波浪条修复 + 竖屏托盘改版）
**versionCode**：45
**兼容**：Android 7.0（API 24）起；覆盖安装**无破坏性迁移**（搜索历史的新旧两种落盘形状同时可读）
**许可证**：GPLv3（原代码 MIT）—— 无新增权限、无自建 API、无 Termux、**无任何新增上报**

---

## ⚠️ 先读这一节：探针推翻了任务书的两处前提

本版开工前跑了五份探针（`PROBE-SUMMARY.md`）。**两处初步判断与代码事实不符**，
本版按代码事实做，并把偏差写在这里：

| 任务书的说法 | 事实 | 本版怎么做 |
|---|---|---|
| 「QQ 无 songid 兜底**路径**」 | **取链根本不读 songid** —— `QqApi.kt` 是 `song.sourceId ?: return null`，songmid 缺失是硬失败、无兜底无重试。真正的兜底在**解析期**：服务端没给数字 songid 时，用 songmid 的 FNV-1a 散列造一个合成 id（只影响**身份**） | 统计按「解析期兜底」埋点，并把「兜底曲目的取链成功率 / 真的播出声率」一起记 |
| 「平板横屏波浪条不显示，怀疑 sw600dp 条件不满足」 | **反了**：`screenWidthDp >= 600` 在平板横竖都满足，正因为满足才走**宽屏两栏**分支 —— 而可视化只挂在「用户点了 ⤢ 大屏幕模式」那一支里 | 把挂载判据抽成 `PlayerLayout.visualizerSlot`，只在「平板 + 横屏」这一格由无变有 |

另外探针**漏掉、由真机取证补上**的一个 bug（见下节 B-3）。

---

## A · 转发属性量化（release 包基线）

**结论先行：转发属性不需要优化 —— 但本版第一次把 release 基线落到了盘上。**

- 转发属性是 `val X get() = group.X` 的**计算型 getter**：无 backing field、
  不进构造函数、**零分配**（字符串 / lambda / List 在 locale 顶层只创建一次）；
- release 产物里它们**已经被 R8 全部内联**：`mapping.txt` 显示 231 条里
  120 条新版转发属性 **0 条作为字段、0 条作为真方法**，65 条留内联帧
  （`Strings.getX():476 -> invoke` 紧跟 `SettingsStrings.getX():1034`）⇒
  每次访问只剩 2 条 `iget`；
- **本版之前没有任何 release 性能基线**（`benchmark/output/` 不存在，
  全盘 0 个 `benchmarkData.json`）。本版新增 `SettingsScrollBenchmark`
  （设置页是转发属性最密集的一面：66 处读点 vs 播放器 44、首页 0），
  连既有两条一起在 release 包上跑，原始数据落
  `docs/verification/v2.5.4/verification/`。

**如实说明归因边界**：本版拿到的是「v2.5.4 的绝对数字 + 与 v2.5.1 S6 gfxinfo 的粗对照」，
**不是**转发属性的因果开销 —— 因果归因需要一份「直读对照包」
（把调用点改成直读组、删掉 231 条属性）在同一台设备上跑。本版不做，理由写在
`probe-forwarding-attr.md` §5：产物级的 R8 内联证据（0 条残留方法体）已经足以
判定「没有可优化的运行期开销」，再花一个对照包去量化一个上界是 2 条 `iget` 的东西不划算。

---

## B · 搜索历史音源丢失修复

### B-1 症状的准确定性（与任务书措辞有偏差）

任务书说「把 QQ 认成 ncm」。探针实测的准确形态是**两件事**：

| 口径 | 修之前的行为 |
|---|---|
| **字符串口径**（`SongItem.musicSource`） | **确实认错**。它读 `source` 字符串，`null` ⇒ ncm。角标、单曲页路由、收藏 / 点赞都按 ncm 处理 |
| **播放口径**（`TrackKey.of(null, id)`） | **不会问错平台**。bit62 把 QQ 认了出来 |

所以用户真正看到的是：**「认得出是 QQ，但 songmid 丢了 ⇒ 取不到链」** ——
点一条历史，歌被跳过，还弹一条方向错误的「可切到 ncm」提示（它本来就是 QQ）。

### B-2 修了什么

1. `HistoryItem` 补 `source` / `sourceId` / `mediaId` 三个字段（可空 + 默认值）；
2. 写路径一律写**规范值** `song.musicSource.key`（ncm 也显式写 `"netease"`
   —— 不写的话 `null` 会被读成「老条目」）；
3. 读路径按 **bit62** 推断老条目的音源，规则收敛在纯函数
   `SearchHistoryMigration.effectiveSource`；
4. **去重 / 删除 / LazyColumn key** 三处统一改用 `SearchHistoryMigration.dedupeKey`
   （音源 + id），不再用裸 id；
5. **老 QQ 条目（缺 songmid）**：`isIncomplete` 判定为真 ⇒ 点击**不再静默入队**，
   而是弹一条提示并把标题填回搜索框，让用户重新点一次带 songmid 的结果；
   菜单里的「添加到下一首」「加入库」对这类条目**整项不挂载**
   （它们会把一首取不到链、音源标识不全的歌写进队列 / 收藏库）。
   **绝不猜 songmid** —— 散列不可逆，模糊匹配是猜测性写操作。

### B-3 顺带发现的第三个 bug：落盘字段名由 R8 决定

真机取证（S6，v2.5.2 release）：`search_history.xml` 里存的是
`{"a":4611686018530183086,"b":"残酷な天使のテーゼ","c":"https://y.qq.com/…","d":"高橋洋子","e":1790344822158}`。
`a b c d e` 就是 `id title coverUrl subtitle timestamp` 被混淆后的名字，
与 `mapping/release/mapping.txt` 逐字对得上。

这是 `proguard-rules.pro` 里 v2.3.0 记下的**同一类问题的第三个实例**：
同一 APK 内读写自洽所以**不崩**，但下一次混淆映射一变，用户的搜索历史就会**静默消失**。

本版**不加 `-keep`**（那会改掉全局混淆映射，可能把同一问题换到 `cache.**` 上），
而是把契约写进代码：新增 `SearchHistoryCodec`，
写路径只经带 `@SerializedName` 的 DTO，读路径**同时认三种形状**
（稳定名字 / 已知的 `a`~`e` / 未知 key 时按声明顺序兜底）。

**不丢历史**：新旧两种形状 + 第三种兜底形状都有单测；
坏条目**逐条**丢弃而不是整段丢光（旧实现是 `catch { mutableListOf() }`）。

---

## C · QQ 无 songid 兜底频率统计

- 新增 `QqFallbackCounter`（纯 JVM，`AtomicLong`）、`QqProbeCounters`（唯一埋点入口）、
  `QqProbeStore`（落 `ncrust_qq_probe` / `stats`）；
- 记 10 个量：解析总数 / 无 songid 数 / 无 media_mid 数 / 取链次数 / 取链缺 songmid 数 /
  media_mid 回落数 / 取链成功 / 取链失败 / **兜底曲目播出声数** / **兜底曲目失败数**；
- **不上报**：这份代码里不出现任何网络类型（可用一条 grep 证明）；
- debug 包多一个读出入口（logcat `QqProbe` + 落盘），release 包里那一行整项不挂载；
- 关键设计：「这首歌的 id 是不是兜底造出来的」判据是**纯函数**
  `SourceIds.isSynthesizedQqId(id, songmid)`，不存旁路标记 ——
  旁路是 last-write-wins，跨源切歌必错。

**如实说明**：任务书要求「真机跑 1~2 天」。本版交付的是**短窗口真机样本 +
可导出的统计文件**；1~2 天的长期窗口见 `EVIDENCE.md` §6 的未验证清单。

---

## D · 平板横屏波浪条修复

**根因**：可视化只有**一个**挂载点，在 `bigScreenActive`（用户点 ⤢ 之后的横屏桌面布局）
那一条分支里。平板横屏走的是**宽屏两栏**（`isWidePlayer`），那一条里没有挂载。
**并且平板上根本没有 ⤢ 入口**（它只在竖屏控制条变体里），
所以这个功能在平板上是**结构性不可达**的，不是「某个条件没满足」。

**修法**：挂载判据抽成 `PlayerLayout.visualizerSlot(enabled, bigScreenActive,
isWidePlayer, isLargeScreen, orientationLandscape)`，用
`smallestScreenWidthDp >= 600` 区分「平板」与「横过来的手机」（与方向无关），
只在**「平板 + 横屏」**这一格由无变有：

| 形态 | 修前 | 修后 |
|---|---|---|
| 手机竖屏 | 无 | 无（不回归） |
| 手机横屏 · 非大屏模式 | 无 | 无（不回归） |
| 手机横屏 · 大屏模式 | 有 | 有（不回归） |
| 平板竖屏 | 无 | 无（不回归） |
| **平板横屏** | **无** | **有** |
| 平板横屏 · 大屏模式 | 有 | 有 |

六格矩阵由 `PlayerLayoutVisualizerTest` 逐个钉住；高度算式同源抽成
`PlayerLayout.visualizerHeightDp`（平板横屏 768dp × 11% ⇒ 夹到 56dp）。
挂载点收敛成**一个** `AudioVisualizerSlot` composable，两处布局共用。

**⤢ 入口缺失（平板上进不了大屏幕模式）如实列为遗留风险**，不在本版改。

---

## E · 竖屏播放托盘改版

```
实时歌词            ← 第一行，跟随播放按【行】变化
歌名  作者  音源     ← 第二行
```

- **不是高度变化**：托盘原本就是 56dp 两行（歌名 + 艺人·音源），
  所以 `collapsedOffsetY` / `BottomOverlayInsetDp` / mini 封面尺寸**一个都没动**；
- **行级节流是可证明的**：上游 `combine(歌词, 位置)` 每 500ms 产出一个值，
  `distinctUntilChanged()` 压到「文本真的变了才向下游发」——
  一行持续期间组件重组 **0** 次（单测：51 个 2Hz 采样点只产出 5 次新值）；
- **数据源**：`lyrics` + `currentPosition` + **既有的** `currentLineIndex`
  （面板与逐字窗口共用的那一份二分查找，托盘不另写一份）；
  **不复用**通知栏那条路（它被默认关闭的开关挡着、不可观察、还会重发通知）；
- **降解**：无歌词 / 还没到第一行 / 当前行是空白 ⇒ 第一行画空串
  （Compose 的空文本仍占一行高，托盘高度不跳）；
- **长歌词**：`maxLines = 1 + Ellipsis`，**不做跑马灯**（全仓库唯一的
  `basicMarquee` 是窄屏顶栏歌名，它会持续排帧；托盘是常驻组件，铁律 17）；
- **点击**：歌词 → 展开播放器**并直接看歌词**；歌名 → 整条托盘的既有展开行为；
  **作者 → 进艺人页**（新增 `onArtistClick` 透传）；音源角标 → 展开；
- **零新增文案**：音源角标复用既有的 `ArtistLineWithSource`（`showArtist = false`）。

---

## 测试与验证

| 项 | 结果 |
|---|---|
| 单元测试 | `./gradlew :app:testDebugUnitTest` —— **全绿**（1209+ 例，本版新增 5 个测试类 ~70 例） |
| lint | `:app:lintDebug` —— 无 Error / Fatal |
| 构建 | `:app:assembleDebug` / `:app:assembleRelease` 全绿 |
| release 基线 | `SettingsScrollBenchmark`（新）+ `HomeScrollBenchmark` + `ExpandPlayerBenchmark` + `StartupBenchmark` |
| 真机 | S6（Android 7.0）、WGR-W09 平板、Cuttlefish（API 37）—— 明细见 `EVIDENCE.md` |

新增测试类：

| 文件 | 覆盖 |
|---|---|
| `library/SearchHistoryCodecTest` | 三种落盘形状、真机 v1 样本、坏条目逐条丢弃、key 名单自证 |
| `library/SearchHistoryMigrationTest` | bit62 推断、显式 source 优先、去重键、`isIncomplete` 与 `isResolvable` 同源 |
| `qq/QqFallbackStatsTest` | 计数口径、比率分母、落盘迁移、8 线程并发、兜底判据、单例去重 |
| `ui/player/PlayerLayoutVisualizerTest` | 六格 A/B 矩阵、开关、高度夹取 |
| `ui/player/TrayLyricTest` | 取行边界、**行级节流**、与面板同源 |

---

## 破坏性变更 / 迁移

| 项 | 结论 |
|---|---|
| 搜索历史落盘形状 | **新旧兼容**。新写的是稳定字段名；旧的单字母 key 照读；未知 key 按声明顺序兜底。**不丢历史** |
| 其他持久化结构 | **一个字节都没改**（队列、离线索引、歌词缓存、本地歌单均未触碰） |
| 数据库 / 权限 / API | 无 |
| 新增上报 | **无**（本版新增的统计全部落本地私有目录） |

---

## 已知问题 / 遗留风险

1. **平板上进不了「大屏幕模式」**（⤢ 入口只存在于竖屏控制条变体）——
   本版修好了波浪条，但大屏模式本身在平板上仍不可达；
2. **`cache.**`（`ncrust_offline` 的 `tracks`）同样是 R8 混淆的单字母 key**，
   本版**未修**（修它要动全局混淆映射，风险面比收益大）；
3. **`PlayReporter` 没有音源闸门** —— QQ 曲目的合成 id 会被 POST 给 ncm 的 webLog，
   与 `QqMusicSourceProvider` 的 KDoc 承诺矛盾。服务端效果未验证，本版未改；
4. **QQ 兜底统计的长窗口样本**（1~2 天）未跑满；
5. **「兜底率是否恒为 0」未验证** —— 需要服务端在某个入口真的不下发 `id`；
   探针在真实搜索响应上没观察到这种响应，所以分子可能天然是 0。
