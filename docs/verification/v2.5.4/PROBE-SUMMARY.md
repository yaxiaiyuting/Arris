# PROBE-SUMMARY.md · v2.5.4 探针收敛

> **探针 HEAD**：`da5c9f8`（v2.5.3-gpl，`versionCode = 44`，工作区干净）
> **真机**：S6 SM-G9209（Android 7.0 / API 24 / 已 root，日常机）、WGR-W09 平板
> （Android 12 / API 31 / 2560×1600 @320dpi / 未 root）、Cuttlefish（API 37 x86_64）
> **本文件只做收敛**：五份探针各自的长论证在对应文件里，这里回答任务书 §2.6 要求的
> 「五个探针问题」，并**显式记录探针推翻任务书前提的地方**（铁律 2：探针先行，
> 不直接采信 prompt 中的初步判断）。

---

## 0. 五问五答

| # | 任务书的初步判断 | 探针结论 | 是否被推翻 |
|---|---|---|---|
| A | 「231 条转发属性可能有运行期开销」 | 转发属性是**计算型 getter**，零分配，且在 release 产物里**已被 R8 全部内联**（120 条中 0 条留方法体、65 条留内联帧）。开销上界 = 每次访问 2 条 `iget`。**没有任何 release 性能基线**，v2.5.4 建立第一条 | 未推翻，但**「是否需要优化」的答案提前变成「不需要」**——量化仍需真机数据兜底 |
| B | 「搜索历史只存 id，导致 QQ 被认成 ncm」 | **半对**。写读两侧确实都丢音源，但「被当成 ncm」只在**字符串口径**成立；播放路径 `TrackKey.of(null,id)` 用 bit62 **认得出 QQ**，真正的用户可见故障是「认得出是 QQ、songmid 丢了 ⇒ 取不到链」。**并且探针漏掉了第三个事实：落盘字段名由 R8 决定**（真机 XML 是 `{"a":…,"e":…}`） | **是**（症状定性 + 发现第三个 bug） |
| C | 「QQ 无 songid 兜底**路径**」（读作取链时兜底） | **取链根本不读 songid**：`QqApi.kt:134` 是 `song.sourceId ?: return null` —— songmid 缺失是硬失败，无兜底无重试。真正的兜底在**解析期**（`SourceIds.qqId` 在 `rawSongId<=0` 时用 songmid 的 FNV-1a 散列造 id），只影响**身份**，有实现、有单测、**零计数器零日志** | **是** |
| D | 怀疑「sw600dp 未覆盖横屏平板」 | **反了**：`screenWidthDp >= 600` 恰恰**满足**（平板横竖都满足），正因为满足才走**宽屏两栏**分支，而可视化只挂在 `bigScreenActive`（用户点 ⤢）那一支里。更硬的一条：平板上**没有 ⤢ 入口**（它只在竖屏控制条变体里）⇒ 该功能在平板上**结构性不可达** | **是**（谓词满足才是根因） |
| E | 「托盘要改成 实时歌词 / 歌名·作者·音源」 | 托盘不是独立组件，是 `PlayerCard` 里的一段 56dp `Box`，**已经是两行**；改版是**内容替换而不是高度变化**（`collapsedOffsetY` / `BottomOverlayInsetDp` / `miniCoverHalfPx` 全部不用动）。行级歌词的正确数据源是 `lyrics + currentPosition` + **既有的** `currentLineIndex`，**不是**通知栏那条（被默认关闭的开关挡着，且不可观察） | 未推翻，但**高度假设与「复用通知栏歌词」被否掉** |

---

## 1. 探针 A：转发属性量化

**文件**：`probe-forwarding-attr.md`（667 行）

| 问题 | 结论 |
|---|---|
| 转发属性是什么形态 | `val X: T get() = group.X`（`Strings.kt:476/479/…`）。**计算型 getter**：无 backing field、不进构造函数、每次访问读一次组实例的字段 |
| 有没有分配 | **零分配**。字符串 / lambda / List 在 locale 顶层 `val zhCN = Strings(...)` 只创建一次 |
| release 里还在不在 | **被 R8 内联**。`mapping/release/mapping.txt`（v2.5.3 的构建产物）：`Strings -> O4.A`；231 条转发属性里 120 条新版属性 **0 条作为字段、0 条作为真方法**（`:0:0 ->`），65 条留内联帧（如 `Strings.getQualitySectionTitle():476 -> invoke` 紧跟 `SettingsStrings.getQualitySectionTitle():1034`） |
| 有没有基线 | **没有**。`benchmark/output/` 不存在，全盘 0 个 `*benchmarkData.json` / perfetto trace。仓库内唯一的 release 帧数据是 v2.5.1 的 S6 `gfxinfo`（滚动 p90 19ms、展开 p90 27ms，1ms 分辨率） |
| 瓶颈面在哪 | 设置页（`UserScreen`）**66 处扁平读点** vs 播放器 44、首页 0 |

**因此本版要做的事**：新增 `SettingsScrollBenchmark`（设置页不是路由，是底部导航第 4 项「用户」），
在 release 包上跑三个场景（设置页 / 首页列表 / 播放页展开），把结果落
`docs/verification/v2.5.4/verification/`。

**判据**（写死在探针 §5）：`frameDurationCpuMs` p90 的 Δ ≤ 1.0ms 且不超噪声带 2 倍、
`janky%` Δ ≤ 2pp。**因果归因需要「直读对照包」**（把 146 个调用点改成直读组、删掉 231 条属性）；
没有它就只是「v2.5.4 vs 基线」，不能声称因果 —— 本版如实按后者写结论。

---

## 2. 探针 B：搜索历史音源丢失

**文件**：`probe-search-history.md`（655 行）

| 问题 | 结论 |
|---|---|
| 存了哪些字段 | 五段：`id / title / coverUrl / subtitle / timestamp`。prefs 文件 `search_history`，三段 key `songs` / `albums` / `artists`，每段 ≤10 条，TTL 14 天 |
| 只存 id 的证据 | 写：`SearchHistoryManager.kt:27-35` 的 `addSong` **一次都没读** `song.source` / `song.sourceId` / `song.mediaId`；读：`SearchScreen.kt:749-755` 的 `toSongItem()` 只填 5 个字段，音源三件套落在声明默认值 `null` |
| 读取时怎么恢复 source | **今天根本不恢复**。`SongItem.musicSource` 读那个 `String?` 字段，`null` ⇒ ncm（`MusicSource.fromKey`） |
| 唯一可证明的推断 | **bit62**：`SourceIds.isQqId(id)` = `(id and (1L shl 62)) != 0`。「带标志位 ⇒ QQ」是**结构性结论**（全部 QQ id 都由 `qqId()` 产出、标志位恒置位；ncm id 是十进制的百万~十亿量级）。**id 区间启发式必须放弃** —— QQ 的裸 songid 同样是 9~10 位十进制，两个区间重叠 |
| 推不出什么 | **songmid / media_mid**。兜底 id 是 songmid 的 FNV-1a 散列，**不可逆** |
| 迁移代价 | 每段 ≤10 条、TTL 14 天 ⇒ 「读时推断、不刷盘、等自然过期」是一个**有界**策略，不需要破坏性迁移 |

### 2.1 探针漏掉的第三个 bug（本版真机取证补上）

真机 S6（v2.5.2 release）的 `search_history.xml` 实际内容是：

```json
[{"a":4611686018530183086,"b":"残酷な天使のテーゼ",
  "c":"https://y.qq.com/music/photo_new/T002R500x500M000000pmPam3gTtA5_1.jpg",
  "d":"高橋洋子","e":1790344822158}, {…net ease 657666…}]
```

**字段名是被 R8 混淆过的单字母。** 与 `mapping/release/mapping.txt` 逐字对得上：

```text
com.takahashirinta.ncrust.library.SearchHistoryManager$HistoryItem -> G4.k:
    long id -> a
    java.lang.String title -> b
    java.lang.String coverUrl -> c
    java.lang.String subtitle -> d
    long timestamp -> e
```

这与 `proguard-rules.pro` 里 v2.3.0 记下的本地歌单事故是**同一类问题的第三个实例**
（前两个：`local.**` 的本地歌单 `ncrust_local_playlists.xml`、`crosssource.**` 的匹配缓存）。
同一 APK 内读写自洽所以**不崩**，但下一次混淆映射一变，用户的搜索历史就会**静默消失**。

同一次取证还发现**第四个实例**：`ncrust_offline` 的 `tracks` 也是单字母 key
（`{"a":557902,"b":"…","e":287533,"f":"jymaster","g":"song:557902:jymaster","i":…}` ——
`h` 是 `approxBytes`，值为 null 所以**整条 key 被省掉**；`mapping.txt` 里 `h` 其实是分配了的）。
**`cache.**` 至今没有 keep 规则** —— 本版**不动它**（见 §6 遗留风险）。

**本版的取舍**：不去加 `-keep class …library.**`。理由是那条规则会**改掉全局混淆映射**，
反而可能让 `cache.**` 里那套单字母 key 在升级时读不出来 —— 用一个静默数据丢失
去换另一个，不是修复。改为把契约**写进代码**：新增 `SearchHistoryCodec`，
写路径只经带 `@SerializedName("id"/"title"/…)` 的 DTO，
读路径**同时认三种形状**（稳定名字 / 已知的 `a`~`e` / 未知 key 时按声明顺序兜底）。

---

## 3. 探针 C：QQ 无 songid 兜底频率

**文件**：`probe-qq-fallback.md`（738 行）

| 问题 | 结论 |
|---|---|
| 兜底在哪 | **解析期**，唯一触发点 `QqSongMapper.kt:93` 的 `item.optLong("id", 0L)` —— 字段缺失与字段为 `0` 不可区分，两者都走 `SourceIds.qqId(rawId, mid)` 的散列分支（`MusicSource.kt:198-201`） |
| 触发条件 | `rawSongId <= 0` 或 `rawSongId >= 2^62` |
| 兜底做了什么 | `QQ_ID_FLAG or (FNV-1a64(songmid) and (2^62-1))`。**确定性**（队列持久化、离线缓存、续播进度都拿它当 key），但**不可逆** |
| 取链时有没有兜底 | **没有**。`QqApi.kt:134` 是 `song.sourceId ?: return null`；另有一条**取链侧**的次要兜底：`media_mid` 缺失时回落 songmid（`:136`），且刻意**不**反向重试（`:126-131`，用错 mid 会拿到一条到 CDN 才 404 的坏链） |
| 有界吗 | 音质阶梯 ≤6 种文件类型在**一次** HTTP 请求里问完（`QqQuality.attemptsFor` → `QqApi.kt:138-139`），`:143` 的循环是纯内存挑档 ⇒ **容量有界**，不是熔断器。上层另有 `QualityRetryGuard.MAX_ATTEMPTS_PER_SONG = 3` 与 `AutoSkipGuard.MAX_CONSECUTIVE_AUTO_SKIPS = 5` |
| 现在能测到什么 | **零**。QQ 取链/解析路径上没有任何计数器、没有能区分兜底的日志 |
| 会上报吗 | **没有 QQ 专用上报**，但探针发现一条**跨音源泄漏**：`PlayReporter`（ncm webLog）没有音源闸门，QQ 曲目的 2^62 合成 id 会被 POST 给 `clientlogusf.music.163.com`（`PlayerViewModel.kt:549-554`、`PlayReporter.kt:47-48`），与 `QqMusicSourceProvider.kt:40` 的 KDoc 承诺矛盾。**服务端效果未验证**；本版**不动它**（见 §6） |

**因此本版要做的事**：新增 `QqFallbackCounter`（纯 JVM，`AtomicLong`）+ `QqProbeCounters`
（唯一埋点入口）+ `QqProbeStore`（落 `ncrust_qq_probe`/`stats`，**不上报**），
在 4 个点位埋点（解析 / 取链成败 / 取链缺失 songmid / 播放确认与失败），
debug 包多一个读出入口（logcat `QqProbe` + 落盘）。

**关键设计**：「这首歌的 id 是不是兜底造出来的」判据是**纯函数**
`SourceIds.isSynthesizedQqId(id, songmid)`（把 `qqId` 的定义重算一遍再比），
不存旁路标记 —— 旁路是 last-write-wins，跨源切歌必错（v2.1.5 的教训）。

---

## 4. 探针 D：平板横屏波浪条

**文件**：`probe-waveform-tablet.md`（783 行）

| 问题 | 结论 |
|---|---|
| 可视化挂载条件 | **只有一个挂载点**：`PlayerCard.kt` 的 `bigScreenActive ->` 分支里。`bigScreenActive = PlayerLayout.isBigScreenActive(requested = bigScreen, orientationLandscape)`，而 `bigScreen` 是**用户意图**（点 ⤢），默认 `false` |
| 平板横屏走哪条布局 | `isWidePlayer = screenWidthDp >= 600` 在**平板横竖都成立**（WGR-W09 实测竖屏 800dp / 横屏 1280dp）⇒ 走 **`isWidePlayer ->` 宽屏两栏**分支，**那一条里没有可视化** |
| 判断条件是不是「没满足」 | **恰恰相反**：`sw600dp` **满足了**，正因为满足才被路由到缺可视化的那条分支。失败的谓词是 `isBigScreenActive(requested = false, …)` |
| 还有更硬的一层 | **平板上没有 ⤢ 入口**。它只存在于竖屏控制条变体（`FullPlayerControls` 的 `if (landscape)` 分支里没有它），而 `landscape = usesSideCover = isWidePlayer \|\| bigScreenActive` 在平板上恒为真。唯一进大屏的路是 `AutoRotateWatcher` 的自动进入（要 `auto_rotate` + 播放器展开 + 窗口横屏），关掉自动旋转或手动退出一次之后**再也回不去** |
| 高度是不是问题 | **不是**。WGR-W09 横屏 768dp × 0.11 = 84.5 ⇒ 夹到 56dp 上界 |
| 数据链有没有问题 | 没有。`TransparentWaveformSink` 吞掉 RMS 异常，`generation` 只在 Canvas 的 draw 里读，可视化不挂任何 `pointerInput` |

### 4.1 真机 A/B（WGR-W09，本版实测，非推断）

| 场景 | 证据 | 结果 |
|---|---|---|
| 平板横屏 + 播放器展开 | `verification/wgr-landscape-player-BEFORE-v2.5.2.png` | 宽屏两栏布局（左封面/歌名/控件 + 右歌词），**无波浪条** |
| 同上，UI 树 | `uiautomator dump` | 控制条只有 歌词 / 队列 / 加入库 / 上一首 / 播放 / 下一首 / 音质偏好 —— **没有 ⤢、没有旋转按钮** |
| 平板竖屏 + 播放器展开 | `verification/wgr-portrait-player-BEFORE-v2.5.2.png` | 宽屏两栏，**无波浪条**（v1.8.0 起就如此，不回归） |

**因此本版要做的事**：把挂载判据抽成 `PlayerLayout.visualizerSlot(enabled, bigScreenActive,
isWidePlayer, isLargeScreen, orientationLandscape)`，只在**「平板 + 横屏」**这一格由无变有
（`isLargeScreen = smallestScreenWidthDp >= 600`，与方向无关，是仓库里区分「平板」与
「横过来的手机」的唯一谓词）。**⤢ 入口缺失记为遗留风险**，不在本版改（它属于
「大屏幕模式的入口可达性」，与「波浪条不显示」是两件事，混在一起改会让两者的回归面互相污染）。

---

## 5. 探针 E：竖屏播放托盘

**文件**：`probe-miniplayer.md`（1109 行）

| 问题 | 结论 |
|---|---|
| 托盘是什么 | **不是 composable**，是 `PlayerCard.kt` 里一段内联 `Box`：`fillMaxWidth().statusBarsPadding().height(56.dp)`，surface 底色，**常驻组合**，透明度只在 `graphicsLayer` 里按 `1 - progress*5` 控制 |
| 现在显示什么 | **已经是两行**：歌名（`bodyMedium`，1 行省略）+ `ArtistLineWithSource`（`bodySmall`，艺人 + 音源角标），右侧两个 48dp 图标按钮（播放/暂停、下一首） |
| 改版是高度变化吗 | **不是**。56dp 装得下两行 ⇒ `collapsedOffsetY`、`BottomOverlayInsetDp`（144/64dp）、`miniCoverHalfPx = 28dp` **全部不用动** |
| 实时歌词从哪来 | **推荐**：`PlayerViewModel.lyrics` + `currentPosition`（2Hz）+ **既有的纯函数** `currentLineIndex`（`NcrustLyricsPanel.kt:931-942`，面板与逐字窗口共用的那一份）。收敛在一个**叶子** composable 里 |
| 通知栏那条能不能复用 | **不能**。`PlaybackService.mediaLyricLine` 被 `lyrics_in_media_session`（默认**关**）挡着；它是个不可观察的 `@Volatile var`，写它还会顺手重发一次通知；经过 `LyricNotifyGate` 最多延后 250ms |
| 歌词更新频率 | **行级**。上游 `combine(歌词, 位置)` 每 500ms 产出一个值，`distinctUntilChanged()` 把它压到「文本真的变了才向下游发」⇒ 一行持续期间组件重组 **0** 次 |
| 歌词太长怎么办 | **`maxLines = 1 + Ellipsis`，不做跑马灯**。全仓库唯一的 `basicMarquee` 是窄屏顶栏的歌名，它会持续排帧；托盘是常驻组件，挂上去等于常态耗电（铁律 17） |
| 无歌词怎么降级 | 第一行画**空串**（Compose 的空文本仍占一行高）⇒ 托盘高度不跳；第二行的 歌名·作者·音源 仍在 |
| 音源显示什么 | **复用 `ArtistLineWithSource` 的角标那一段**（`showArtist = false`），文案来自既有的 `strings.sourceNetease` / `sourceQqMusic`，**零新增文案** |
| 点击区域 | 点歌词 → 展开播放器**并直接看歌词**（`showLyrics = true`）；点歌名 → 整条托盘的既有行为（展开）；点作者 → 进艺人页（新增 `onArtistClick(Long)` 透传，`MainActivity` 接 `NavRoutes.artist`）；点角标 → 展开 |
| 横屏怎么办 | 托盘不受方向谓词控制。手机上横屏只在**大屏模式**里发生（那时 `progress = 1`、托盘不可见），平板横屏收起态**会**显示托盘 —— 新布局在两种方向下都成立（两行 56dp），因此**不加方向闸门** |

---

## 6. 探针共同确认的「不做」清单

| 不做 | 理由 |
|---|---|
| 不移动任何已发布的 tag | 铁律 7 |
| 不碰华为控制中心卡片 | 任务书 §1 不在范围 |
| 不加 `-keep class …library.**` | 会改全局混淆映射，可能把 `cache.**` 的静默丢失换到另一个类上；改用 `@SerializedName` 固定契约 + 三形状读取 |
| 不修 `PlayReporter` 的跨音源泄漏 | 它是探针**发现**的既有缺陷，本版范围是「统计兜底频率」；改上报行为需要单独的证据与回归面。列入遗留风险 |
| 不给平板补 ⤢ 入口 | 与「波浪条不显示」是两件事；混改会让两者的回归面互相污染。列入遗留风险 |
| 不用标题/艺人模糊匹配补 songmid | `QqApi.kt:126-131` 对同类兜底已有先例判决：猜测性兜底会把一次干净的失败换成一次诡异的播放错误 |
| 不做逐字托盘歌词 | 铁律 17 + 任务书明确「行级即可」 |
| 不上报任何统计 | 铁律 15 / 任务书 §5.2 |
