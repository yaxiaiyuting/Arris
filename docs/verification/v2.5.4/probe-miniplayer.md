# 竖屏播放托盘（mini bar）改造探针 —— v2.5.4

| | |
|---|---|
| 探针对象 | 折叠态播放器卡片底部的迷你播放栏（代码名 **mini bar**），目标形态 = 两行：`实时歌词` / `歌名 作者 音源` |
| 仓库 / 版本 | `ncrust-gpl/Ncrust`，`app/build.gradle.kts` → `versionCode = 44` / `versionName = "2.5.3-gpl"`（本探针为 **v2.5.4** 施工依据） |
| 性质 | **只读调查**，未修改任何源码；全部结论附 `file:line` 与真实代码引用 |
| 取证方式 | 只读工具（`read` / `grep` / `glob`）逐行核对；行号均为当前工作区实测值 |
| 姊妹仓库 | Kanesumi 走组合构建（`ncrust-gpl/Kanesumi-sec-a`），涉及 `MetroText` / `MetroIconButton` / `MetroTypography` 时一并给出其行号 |

---

## 0. 结论先行

1. **今天的托盘是什么**：它不是独立 composable，而是 `PlayerCard` 根 `Box` 里的一段内联 `Box`
   （[PlayerCard.kt:1321-1422](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)），
   `fillMaxWidth().statusBarsPadding().height(56.dp)`，背景 `MetroColors.surface`，**永远挂载**，
   可见性只由 `graphicsLayer { alpha = (1f - progress*5f) }` 在绘制阶段控制。
   它渲染的是**两行文字**：第 1 行 = 歌名（`bodyMedium`，`maxLines=1` + `Ellipsis`），
   第 2 行 = `ArtistLineWithSource`（歌手 + 音源角标，`bodySmall`），右侧两个 48dp 图标按钮
   （播放/暂停、下一首）。**没有音质角标、没有进度条、没有歌名点击**。
   → **结论：本次改造是「换内容」而不是「加行」** —— 现托盘本来就是两行，56dp 可以保持不变
   （详见 §7.3），这是本探针最有施工价值的一条。

2. **第 1 行的真值来源（推荐）**：**不新建歌词引擎，也不依赖通知那条链路**，而是直接使用
   `PlayerViewModel.lyrics: StateFlow<List<LrcLine>>` + `currentPosition: StateFlow<Long>`（2Hz），
   在一个**叶子 composable** 内用 `derivedStateOf` 走**已有的纯逻辑**
   `currentLineIndex(positionMillis, timestamps)`
   （[NcrustLyricsPanel.kt:931-942](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/NcrustLyricsPanel.kt)）。
   理由：① 这是歌词面板**正在用的同一份二分查找**（`LyricsView` 的逐字窗口也复用它，
   见 [LyricsView.kt:531](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/LyricsView.kt)），
   两侧绝不会错开一行；② `derivedStateOf` 天然把 2Hz 采样收敛成**跨行才变字符串** ⇒
   **行级重组、零新节流器**；③ 它不依赖 `lyrics_in_media_session` 开关（默认 **关**），
   也不把 UI 绑到「发通知」这个副作用上。
   **通知那条链路（`PlaybackService.mediaLyricLine`）不建议复用**：它是
   `@Volatile var`（非 Compose 可观察）、**受默认关闭的设置项闸门控制**、且 setter 会触发重 post 通知
   （详见 §5.6）。

3. **是否需要新增 i18n 文案：不需要（且应当避免）**。降级态第 1 行直接回落到**已有的歌名**
   （`SongItem.name`），第 2 行复用 `ArtistLineWithSource` 用的
   `strings.sourceNetease` / `strings.sourceQqMusic`（`SourceStrings` 组内，早已存在）。
   `Strings` 主构造器当前被测试**精确钉死在 128**（预算 150），且 `PlayerUiStrings` 被**精确钉死在 31** ——
   新增一条播放器文案要同时改 `Strings.kt`、8 个语言文件与两条钉死断言（详见 §9）。

4. **降级策略（推荐）**：无歌词 / 歌词未就绪 / 还没到第一行 ⇒ 第 1 行显示歌名（当前第 1 行的内容），
   第 2 行照旧显示 `ArtistLineWithSource`。**不显示「暂无歌词」占据主行**，也不新增任何占位文案。

5. **长行策略（推荐）**：第 1 行 `maxLines = 1` + `TextOverflow.Ellipsis`（与今天歌名一致），
   **不引入跑马灯**。全仓库唯一的跑马灯在窄屏顶栏歌名上（`basicMarquee`，
   [PlayerCard.kt:1151-1157](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)），
   而托盘是常驻 60fps 场景，跑马灯 = 常驻帧调度 + 常驻 draw，与「GPU 零重组 / 静态零帧调度」的既有纪律冲突。

6. **横屏口径（代码核实）**：托盘**没有被任何 `isWidePlayer` / 方向谓词排除**，它只在
   `progress.value < 0.01f` 时才可交互、在 `progress > 0.2f` 时 alpha 归零。手机横屏只有两条路：
   ① 进「大屏模式」（`bigScreenActive = 意图 && 横屏`）—— 此时卡片必然是展开态（`progress = 1`，
   从全屏播放器里按 ⤢ 进去），托盘不可见且**整卡拖拽也被停用**
   （[PlayerCard.kt:548-553](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)）；
   ② 平板（`smallestScreenWidthDp >= 600`，方向未锁）真的横过来且用户把卡片收起 —— 托盘的
   **两行布局会照样显示**（今天的单行内容已经会显示；不加守卫时新两行版式同样会出现）。
   故「横屏不显示托盘」**当前不是由代码保证的**：
   建议用 `!usesSideCover`（= 窗口宽 < 600dp 且非大屏）作为新两行布局的开关，
   两选一与理由详见 §7.2。

---

## 1. 现状：竖屏 mini bar 的完整描述

### 1.1 它不是独立 composable

`PlayerCard.kt` 全文的函数清单只有 4 个顶层声明（`grep "^@Composable|^private fun|^fun "` 实测）：

| 行 | 声明 |
|---|---|
| 94 | `fun PlayerCard(...)` |
| 1503 | `private fun Modifier.collapsibleHeight(...)` |
| 1548 | `private fun StableCover(...)` |
| 1637 | `private fun ArtistLineWithSource(...)` |
| 1697 | `private fun isGestureNavigation(...)` |

**没有 `MiniBar` / `CollapsedBar` composable**。托盘是 `PlayerCard` 根 `Box`（起点
[PlayerCard.kt:513](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)）的**第三个子节点**，
位于「全屏黑底 `Box`（647-654）→ 折叠态卡背 `Box`（657-665）→ `Column{...}`（667-1319）」之后、
「唯一封面 `StableCover` 叠加层（1424-1466）」之前。

**关键不变量（改造时必须保住）**：
- 托盘是折叠态**唯一保留挂载**的子树（`expandedMounted` 只控制展开态子树，
  [PlayerCard.kt:255-269](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)）；
- 它**永远可命中**（`alpha=0` 不退出命中测试，AGENTS「Compose 触摸陷阱」第 1 条），
  所以它必须永远可见可用，**不能当"隐藏层"用**；
- 折叠态根 `Box` 会被加 `padding(top = statusBar)` 收窄两个 `pointerInput` 的命中区，
  再用等量 `offset` 把子节点放回原位（[PlayerCard.kt:517-528](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)、
  [PlayerCard.kt:638-640](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)）——
  托盘自身的布局位置**不受影响**，它的内容被 `statusBarsPadding()` 下推 statusBar 高。

### 1.2 行号引用：托盘全部代码（PlayerCard.kt:1321-1422）

```kotlin
1321:        // 迷你播放栏叠加层：始终在 Composition 中，透明度仅在绘制阶段控制，避免动画期间触发重组
1322:        Box(
1323:            modifier = Modifier
1324:                .fillMaxWidth()
1325:                .statusBarsPadding()
1326:                .height(56.dp)
1327:                .then(
1328:                    // 暂无播放（hasSong=false）时不可点击展开，避免空白播放器被拉起。
1329:                    if (miniBarEnabled && hasSong) Modifier.clickable(
1330:                        interactionSource = miniBarInteractionSource,
1331:                        indication = null,
1332:                        onClick = {
1333:                            coroutineScope.launch {
1334:                                progress.animateTo(
1335:                                    1f,
1336:                                    tween(durationMillis = 400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
1337:                                )
1338:                            }
1339:                        }
1340:                    ) else Modifier
1341:                )
1342:                .graphicsLayer {
1343:                    alpha = (1f - progress.value * 5f).coerceIn(0f, 1f)
1344:                }
1345:                .background(LocalMetroColors.current.surface)
1346:        ) {
1347:            Row(
1348:                modifier = Modifier.fillMaxSize(),
1349:                verticalAlignment = Alignment.CenterVertically
1350:            ) {
1351:                if (hasSong) {
1352:                    val s = song!!
1353:                    // 唯一封面 overlay 会落到这个方形占位处（窄屏与宽屏一致）。
1354:                    Spacer(modifier = Modifier.fillMaxHeight().aspectRatio(1f))
1355:                    Column(
1356:                        modifier = Modifier
1357:                            .weight(1f)
1358:                            .padding(horizontal = 12.dp)
1359:                    ) {
1360:                        MetroText(
1361:                            s.name,
1362:                            color = LocalMetroColors.current.onBackground,
1363:                            style = LocalMetroTypography.current.bodyMedium,
1364:                            maxLines = 1,
1365:                            overflow = TextOverflow.Ellipsis
1366:                        )
1367:                        // v2.1.0 · F：音源角标（折叠态 mini bar，两个音源都标）。同字号
1368:                        // （bodySmall）+ 次要色，与列表行逐像素同款；角标定宽、歌手让位省略。
1369:                        ArtistLineWithSource(
1370:                            song = s,
1371:                            color = LocalMetroColors.current.onSurfaceVariant,
1372:                            style = LocalMetroTypography.current.bodySmall,
1373:                            badgeStyle = LocalMetroTypography.current.bodySmall
1374:                        )
1375:                    }
1376:                    if (miniBarEnabled) {
1377:                        MetroIconButton(onClick = {
1378:                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
1379:                            onPlayPause()
1380:                        }) {
1381:                            MetroIcon(
1382:                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
1383:                                contentDescription = null,
1384:                                tint = LocalMetroColors.current.onBackground
1385:                            )
1386:                        }
1387:                        MetroIconButton(onClick = {
1388:                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
1389:                            onPlayNext()
1390:                        }) {
1391:                            MetroIcon(Icons.Default.SkipNext, null, tint = LocalMetroColors.current.onBackground)
1392:                        }
1393:                    } else {
1394:                        Spacer(modifier = Modifier.width(96.dp))
1395:                    }
1396:                } else {
1397:                    // 暂无播放: 卡片仍不可拉起(保持既有约束), 但给一个播放键
1398:                    // 直接开始 Infinity——取每日推荐开播, 无需先有队列
1399:                    MetroText(
1400:                        strings.noSongPlaying,
1401:                        color = LocalMetroColors.current.onSurfaceVariant,
1402:                        style = LocalMetroTypography.current.bodyMedium,
1403:                        modifier = Modifier
1404:                            .weight(1f)
1405:                            .padding(horizontal = 12.dp)
1406:                    )
1407:                    if (miniBarEnabled) {
1408:                        MetroIconButton(onClick = {
1409:                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
1410:                            onPlayNothing()
1411:                        }) {
1412:                            MetroIcon(
1413:                                imageVector = Icons.Default.PlayArrow,
1414:                                contentDescription = LocalStrings.current.playButton,
1415:                                tint = LocalMetroColors.current.onBackground
1416:                            )
1417:                        }
1418:                        Spacer(modifier = Modifier.width(12.dp))
1419:                    }
1420:                }
1421:            }
1422:        }
```

（以上为**逐行原样**引用；1367-1368、1397-1398 是代码里的注释行。）

### 1.3 字段 / 尺寸 / 排版 / 交互一览

| 项 | 值 | 证据 |
|---|---|---|
| 高度 | `56.dp`（**外加** `statusBarsPadding()`，故实测总高 = 56dp + statusBar） | PlayerCard.kt:1325-1326 |
| 宽度 | `fillMaxWidth()`（窄屏、宽屏、大屏三态**同一条代码**） | PlayerCard.kt:1324 |
| 背景 | `LocalMetroColors.current.surface` | PlayerCard.kt:1345 |
| 内边距 | 文字列 `padding(horizontal = 12.dp)`；左侧封面占位 `fillMaxHeight().aspectRatio(1f)`（= 56×56dp 方形） | PlayerCard.kt:1354-1358 |
| 第 1 行 | `SongItem.name`，`MetroTypography.bodyMedium`（**14sp / lineHeight 20sp**），`maxLines=1`，`TextOverflow.Ellipsis`，色 `onBackground` | PlayerCard.kt:1360-1366；[MetroTypography.kt:34](../../../../Kanesumi-sec-a/kanesumi-core/src/main/java/io/github/takahashirinta/kanesumi/core/theme/MetroTypography.kt) |
| 第 2 行 | `ArtistLineWithSource`：歌手 + `· 音源`角标，`bodySmall`（**12sp / lineHeight 16sp**），色 `onSurfaceVariant` | PlayerCard.kt:1369-1374；MetroTypography.kt:35 |
| 右侧按钮 | `MetroIconButton` ×2（播放/暂停 + 下一首），每个 **48dp 触控区** | PlayerCard.kt:1377-1392；[MetroIconButton.kt:25](../../../../Kanesumi-sec-a/kanesumi-controls/src/main/java/io/github/takahashirinta/kanesumi/controls/MetroIconButton.kt) |
| 按钮占位 | 折叠动画期（`!miniBarEnabled`）保留 `Spacer(width = 96.dp)` = 2×48dp，防文字列宽度跳变 | PlayerCard.kt:1393-1395 |
| **没有**的东西 | 音质角标（只在 `FullPlayerControls` / 大屏左栏 `PlayerQualityChip`）、进度条、歌名点击、收藏键、播放模式键 | 全段代码 + `grep onSongInfoClick` 只命中 978 / 1082 / 1143 / 1215（都在展开态） |
| 播放/暂停图标 | `if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow`，`contentDescription = null`（无 a11y 描述，既有状态） | PlayerCard.kt:1381-1385 |
| 触觉 | 两个按钮都是 `haptic.performHapticFeedback(HapticFeedbackType.LongPress)` | PlayerCard.kt:1378 / 1388 |
| 激活条件 | `miniBarEnabled = derivedStateOf { progress.value < 0.01f }`（阈值穿越才重组一次） | PlayerCard.kt:251-252 |
| 不可见时的行为 | `alpha = (1f - progress*5f).coerceIn(0,1)` ⇒ `progress ≥ 0.2` 全透明，**但仍参与命中测试**（子节点 clickable 被 `miniBarEnabled` 摘掉，根 Box 仍占位） | PlayerCard.kt:1342-1344 |
| 空态分支 | `hasSong == false` 时显示 `strings.noSongPlaying` + 一个「一键开播」按钮（`onPlayNothing`）。**实际是死代码**：`MainScreen` 只在 `currentSong != null` 时才挂载整层 overlay | PlayerCard.kt:1396-1419；[MainActivity.kt:2080](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt) |

### 1.4 与托盘几何耦合的「唯一封面」体系（改高度必看）

托盘左侧没有自己的封面组件：全屏大封面与托盘封面是**同一个节点**，靠 `graphicsLayer` 缩放位移：

```kotlin
240:    val miniCoverHalfPx = with(density) { 28.dp.toPx() }        // = 56dp / 2
241:    val miniScale = miniCoverHalfPx * 2f / coverSizePx
244:    val miniCoverCenterX = miniCoverHalfPx
245:    val miniCoverCenterY = statusBarPx + miniCoverHalfPx
```
（PlayerCard.kt:240-245；折叠落点 = 托盘内左侧 56×56 方形的中心。）

⇒ **若两行改造需要改高（例如 64dp），必须同步改**：`PlayerCard.kt:1326` 的 `.height(56.dp)`、
`PlayerCard.kt:240` 的 `28.dp`、`PlayerCard.kt:245` 的 `miniCoverCenterY`、
[MainActivity.kt:865](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt) 的 `miniBarHeightPx`、
[BottomOverlayInset.kt](../../../app/src/main/java/com/takahashirinta/ncrust/ui/BottomOverlayInset.kt) 的 144/64 常量与其 KDoc。
**§0 已经论证本改造不必改高**（两行 → 两行），这是首选路径。

---

## 2. `ArtistLineWithSource`（第 2 行的首选复用件）

**位置**：`PlayerCard.kt:1636-1676`，`private`，同文件 5 个调用点：
`988`（大屏左栏）、`1092`（宽屏左栏，行 1092 处）、`1161`（窄屏顶栏）、`1225`（窄屏大封面 overlay）、
`1369`（**折叠态 mini bar**）。

```kotlin
1636:@Composable
1637:private fun ArtistLineWithSource(
1638:    song: SongItem,
1639:    color: Color,
1640:    style: TextStyle,
1641:    badgeStyle: TextStyle,
1642:    modifier: Modifier = Modifier,
1643:) {
1644:    val strings = LocalStrings.current
1645:    // 走 song.musicSource（枚举）而不是原始 source 字符串：null / 未知 key 的旧数据在
1646:    // 这里也落到「ncm」，与列表行同一条判定（SongSourceExt.musicSource）。
1647:    val sourceLabel = when (song.musicSource) {
1648:        MusicSource.NETEASE -> strings.sourceNetease
1649:        MusicSource.QQMUSIC -> strings.sourceQqMusic
1650:    }
1651:    val artistStr = song.artists?.joinToString("/") { it.name }.orEmpty()
1652:    Row(modifier = modifier) {
1653:        if (artistStr.isNotEmpty()) {
1654:            MetroText(
1655:                artistStr,
1656:                color = color,
1657:                style = style,
1658:                maxLines = 1,
1659:                overflow = TextOverflow.Ellipsis,
1660:                modifier = Modifier
1661:                    .weight(1f, fill = false)
1662:                    .alignByBaseline()
1663:            )
1664:            Spacer(Modifier.width(6.dp))
1665:        }
1666:        MetroText(
1667:            if (artistStr.isNotEmpty()) "· $sourceLabel" else sourceLabel,
1668:            color = LocalMetroColors.current.onSurfaceVariant,
1669:            style = badgeStyle,
1670:            maxLines = 1,
1671:            softWrap = false,
1672:            overflow = TextOverflow.Ellipsis,
1673:            modifier = Modifier.alignByBaseline()
1674:        )
1675:    }
1676:}
```

**行为契约（KDoc，PlayerCard.kt:1617-1635 摘录）**：
- **两个音源都标**（与列表行 `SongCard.sourceBadge` 只标非 ncm 不同）——「播放页是用户唯一能确认
  现在放的是哪一家的地方」；
- 视觉：`bodySmall`、次要色、`·` 分隔、**无边框无底色**；
- `Modifier.weight(1f, fill = false)` **只给歌手**：歌手过长由它自己省略，**角标永远完整可见**；
- 基线对齐（`alignByBaseline`）而不是垂直居中（两段字号不同）；
- 角标与歌手**同一行**而不是新起一行：窄屏顶栏是固定 56dp 的 Box，多起一行会被裁。

**用到的字符串资源**（都在 `SourceStrings` 组，早已存在，无需新增）：

| 属性 | 定义 | 转发属性 |
|---|---|---|
| `sourceQqMusic` | [Strings.kt:668](../../../app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt) | [Strings.kt:362](../../../app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt) |
| `sourceNetease` | [Strings.kt:712](../../../app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt)（注释：「只用在播放页的音源角标上」） | [Strings.kt:379](../../../app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt) |

**改造建议（对第 2 行）**：不要改 `ArtistLineWithSource` 的签名去塞「歌名」（会牵动 5 个调用点与
各自的排版契约）。两种干净做法：
- **(A)（推荐）** 在托盘里包一层：`Row { MetroText(歌名, weight(1f), Ellipsis); Spacer(6.dp); ArtistLineWithSource(...) }`
  —— 复用零风险，代价是歌名与歌手之间没有 `·`。
- **(B)** 新增一个同文件私有 composable `TitleArtistLineWithSource(song, ...)`，内部**复制**
  `sourceLabel` 的 `when(song.musicSource)` 判定，把 `Modifier.weight` 给歌名、歌手 `fill = false`。
  若要避免第二处真相，可把判定抽成 `internal fun sourceBadgeLabel(song: SongItem, netease: String, qq: String): String`
  （纯函数、JVM 可测；`MusicSource` 枚举见 `source/MusicSource.kt`）。

---

## 3. 歌词系统

### 3.1 `SweepTrack.kt` 公共 API（全文 339 行，逐项实测）

| 声明 | 行 | 说明 |
|---|---|---|
| `interface SweepGeometry` | 45-64 | `rtl: Boolean`；`lineForChar(charIndex): Int`；`lineStart/lineEnd(lineIndex): Float`；`charStart/charEnd(charIndex): Float` —— 全部是**文本块局部像素坐标**，与书写方向解耦 |
| `enum class SweepEasing { LINEAR, SMOOTH, EASE_OUT }` + `apply(u: Float): Float` | 74-92 | 默认 `LINEAR`（词内匀速，与 yrc 逐点吻合） |
| `data class LyricsSweepConfig(fadeEm=0.65f, inactiveAlpha=0.4f, easing=LINEAR, softEdge=true)` | 100-133 | companion：`DEFAULT_FADE_DP = 20f`、`DEFAULT`、`LOW_END` |
| `class SweepSample(val lineIndex: Int, val x: Float, val rtl: Boolean)` + `band(fadePx): SweepBand` | 140-153 | 渐变带以光标为中心（光标 == 50% 透明度处） |
| `class SweepBand(fadeStart, fadeEnd, litOnLeft)` + `width` | 156-158 | 视觉坐标 |
| `class SweepTrack private constructor(times, xs, lines, holds, rtl, endMs, easing)` | 166-179 | `val nodeCount: Int` |
| `fun sample(positionMillis: Long): SweepSample?` | 189-214 | **早于第一个词 → `null`**；晚于最后节点 → 停在末位置 |
| `companion { const val TERMINAL_MAX_MS = 700L }` | 219 | 行尾收束上限 |
| `fun build(words: List<LrcWord>, textLength: Int, endMs: Long?, geo: SweepGeometry, fadePx: Float, easing: SweepEasing = LINEAR): SweepTrack?` | 231-337 | 无有效词 → `null`（调用方退回整行渲染） |

**关键结论**：`SweepTrack` 是**行内**「时间 → 光标位置」的折线，**不含**「时间 T 落在第几行」的判定
（那是 `currentLineIndex` 的职责）。**托盘第 1 行与 `SweepTrack` 无关**，不要为了托盘去碰它。
使用点：`NcrustLyricsPanel.kt:707`（`SweepTrack.build`）、`LyricsView.kt:516`（`TERMINAL_MAX_MS`）；
JVM 单测 [SweepTrackTest.kt](../../../app/src/test/java/com/takahashirinta/ncrust/lyric/SweepTrackTest.kt)（含 RTL 用例）。

### 3.2 「当前行」的三条现成通路

#### (a) 纯逻辑二分查找 —— **唯一的行判定真相**

```kotlin
 928:// 播放位置(ms) -> 当前行索引。-1 表示还没到第一行(全部未来行)。
 929:// v1.5.2：提升为 internal —— LyricsView 的逐帧扫过窗口也要用它判「当前行」，
 930:// 两处必须是同一份二分查找，否则「面板高亮的行」与「正在推进的窗口」会错开一行。
 931:internal fun currentLineIndex(positionMillis: Long, timestamps: LongArray): Int {
 932:    if (timestamps.isEmpty()) return -1
 933:    if (positionMillis < timestamps[0]) return -1
 934:    var lo = 0
 935:    var hi = timestamps.size - 1
 936:    if (positionMillis >= timestamps[hi]) return hi
 937:    while (lo < hi) {
 938:        val mid = (lo + hi + 1) ushr 1
 939:        if (timestamps[mid] <= positionMillis) lo = mid else hi = mid - 1
 940:    }
 941:    return lo
 942:}
```
（[NcrustLyricsPanel.kt:928-942](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/NcrustLyricsPanel.kt)；`internal` 顶层函数 = **JVM 可单测**，同包先例见 `PlayerCardDragSnap`（[PlayerDragSnap.kt:37](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerDragSnap.kt)）+ [PlayerDragSnapTest.kt](../../../app/src/test/java/com/takahashirinta/ncrust/ui/player/PlayerDragSnapTest.kt)。）

**目前没有针对 `currentLineIndex` 的单测**（`grep currentLineIndex app/src/test` 只命中
[LyricsPanelScrollTest.kt:113](../../../app/src/test/java/com/takahashirinta/ncrust/ui/player/LyricsPanelScrollTest.kt) 的一条注释）。

#### (b) 面板内部：离散索引（跨行才重组）

```kotlin
178:    val currentPosition by rememberUpdatedState(currentPositionMillis)
179:    val timestamps = remember(lines) { LongArray(lines.size) { lines[it].timestampMillis } }
180:    // 离散当前行:二分定位,只在跨行时变 -> 面板只在跨行时重组。
181:    val currentIndex by remember(lines) {
182:        derivedStateOf { currentLineIndex(currentPosition(), timestamps) }
183:    }
```
（NcrustLyricsPanel.kt:178-183 —— **这就是托盘要照抄的写法**。）

#### (c) 面板 a11y：`position → index → distinctUntilChanged`（现成节流先例）

```kotlin
201:    // a11y:liveRegion 播报当前行文本,map + distinctUntilChanged 压掉帧级位置流。
202:    val a11yText = remember { mutableStateOf("") }
203:    LaunchedEffect(lines) {
204:        snapshotFlow { currentPosition() }
205:            .map { currentLineIndex(it, timestamps) }
206:            .distinctUntilChanged()
207:            .collect { idx -> ... }
208:    }
```
（NcrustLyricsPanel.kt:201-216 —— 仓内已有「把位置流压成行级事件」的既有范式。）

#### (d) `LyricsView` 的「按需唤醒」循环（更新率）

```kotlin
262:    LaunchedEffect(isPlaying, isVisible, enabled, boundaries, sweepWindows) {
263:        if (!isPlaying || !isVisible || !enabled) { displayPosition.longValue = positionState.value; return@LaunchedEffect }
267:        while (true) {
268:            val nowMs = anchor.anchorPosMs + (System.nanoTime() - anchor.anchorNanos) / 1_000_000L
272:            if (displayPosition.longValue > nowMs + POSITION_SNAP_BACK_MS) displayPosition.longValue = nowMs
276:            if (inSweepWindow(nowMs, sweepWindows, timestamps)) { withFrameNanos { }; ...; continue }
283:            val next = nextLineBoundaryAfter(boundaries, nowMs)
284:            if (next == null) { delay(500); continue }
289:            val waitMs = next - nowMs
291:            if (waitMs > 0) delay(waitMs.coerceAtMost(1_000L))
295:            if (extrapolated >= next && extrapolated > displayPosition.longValue) displayPosition.longValue = extrapolated
```
（[LyricsView.kt:252-299](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/LyricsView.kt)。）

- 唤醒表 = **行时间戳 ∪ 词起始时刻**（`TreeSet` 去重升序，`LyricsView.kt:192-199`）；
- 窗口外**只在越过边界时**写一次状态（`295`）—— 静态期零状态写入、零帧调度；
- 越过最后一行后 `delay(500)` 低频醒来（`286`）；等待上限 `1_000L`（`291`）；
- 2Hz 采样到达时重置外推锚点，并对 >1.5s 漂移立刻对齐（`LyricsView.kt:241-250`，`POSITION_SNAP_BACK_MS = 1_000L` 在 `477`）。

**⇒ 面板的歌词位置更新率 = 2Hz（真实采样）+ 行/词边界上的精确定位 + 行内逐帧（仅逐字窗口内）。
托盘的「行级」需求只需要其中最低的那一档：2Hz 采样 + `derivedStateOf` 收敛。**

### 3.3 位置流本身的更新率（源头）

```kotlin
1497:    private fun startProgressUpdates() {
1499:        progressJob = scope.launch {
1500:            while (isActive) {
1501:                if (player.isPlaying) {
1502:                    onProgressUpdate?.invoke(player.currentPosition, player.duration)
1508:                delay(500)
```
（[PlaybackService.kt:1497-1511](../../../app/src/main/java/com/takahashirinta/ncrust/player/PlaybackService.kt)：**只在 `player.isPlaying` 时广播**，2Hz。暂停态 seek 由 `publishProgressNow()` 补一次，`1492-1495`。）

`PlayerViewModel.kt:537-540`：`onProgressUpdate` 里同时写 `currentPosition` / `duration` / `progress` 三个 `StateFlow`。

---

## 4. 双语（tlyric）与音译（romalrc / TTML x-roman）的处理

### 4.1 数据层：两条副文本轨各存各的，**渲染层按 `timeMs` 精确配对**

- 状态：`PlayerViewModel.translatedLyrics: MutableStateFlow<List<LrcLine>>`（[PlayerViewModel.kt:97](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)）、
  `romanizedLyrics`（[PlayerViewModel.kt:107](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)）。
- ncm 源落地：`applyNeteaseLyrics(...)`（[PlayerViewModel.kt:2092-2117](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)）

  > 「ncm 源的两条副文本轨**按时间戳与原行配对**是既有语义（tlyric/romalrc 与 lrc 是同一份资产、
  > 时间戳同刻），v1.5.0 起就是这么显示的，本版一行不改」（2090 行注释）

- TTML 胜出时走**分轨合并** `LyricTrackMerge`：`applyTtmlLyrics(...)`（[PlayerViewModel.kt:2130-2147](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)），
  合并产出的每一行时间戳都取自 `TtmlDoc.lines`（2127-2128 注释）。

### 4.2 渲染层：合并/取舍在这里（**这是托盘该照抄的那一段**）

```kotlin
151:    val panelLines = remember(
152:        lyrics, translatedLyrics, showTranslation, romanizedLyrics, showRomanization, lineScales,
153:    ) {
154:        val tMap = if (showTranslation) translatedLyrics.associateBy { it.timeMs } else emptyMap()
155:        val rMap = if (showRomanization) romanizedLyrics.associateBy { it.timeMs } else emptyMap()
156:        lyrics.mapIndexed { index, line ->
157:            NcrustLyricLine(
158:                timestampMillis = line.timeMs,
159:                text = line.text,
160:                translation = tMap[line.timeMs]?.text ?: "",
161:                romanization = LyricSubtitleText.visibleRomanization(
162:                    main = line.text,
163:                    romanization = rMap[line.timeMs]?.text ?: "",
164:                    show = showRomanization,
165:                ),
166:                words = line.words,
167:                endMs = line.endMs,
168:                fontScale = lineScales.getOrElse(index) { 1f },
169:            )
170:        }
171:    }
```
（[LyricsView.kt:151-171](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/LyricsView.kt)。）

面板里的三层顺序：**原文 → 译文 → 音译**（[NcrustLyricsPanel.kt:574-589](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/NcrustLyricsPanel.kt) 译文槽、
[599-613](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/NcrustLyricsPanel.kt) 音译槽），
两者都 `softWrap = true`、**没有 `maxLines`**（会整段折行）。

音译的「该不该显示」规则抽在纯函数里（**JVM 已单测**）：

```kotlin
43:    fun visibleRomanization(main: String, romanization: String, show: Boolean): String {
44:        if (!show) return ""
45:        val trimmed = romanization.trim()
46:        if (trimmed.isEmpty()) return ""
47:        if (trimmed == main.trim()) return ""
48:        return romanization
49:    }
```
（[LyricSubtitleText.kt:43-49](../../../app/src/main/java/com/takahashirinta/ncrust/lyric/LyricSubtitleText.kt)；
配套 `subtitleLines(translation, romanization)` 57-62、`a11yText(main, translation, romanization)` 70-79，单测
[LyricSubtitleTextTest.kt](../../../app/src/test/java/com/takahashirinta/ncrust/lyric/LyricSubtitleTextTest.kt)。）

### 4.3 托盘该显示什么（建议）

- **第 1 行只显示 `line.text`（原文）**：托盘是 1 行、56dp、常驻可点区域，塞第二行会破坏两行版式；
- 若产品坚持要带译文，**只在原文行后追加一个次要色的短译文**是可行的（`Row { 原文; Spacer; 译文(weight 1f, Ellipsis) }`），
  但会与第 2 行的三字段抢宽度；**默认不开**；
- 音译**不进托盘**（默认关、且它更像"辅助阅读"功能，属于展开态）；
- a11y：若将来要给托盘补 `contentDescription`，**已有现成的零新增文案方案** ——
  `LyricSubtitleText.a11yText(main, translation, romanization)`。但注意：**不要**给第 1 行加
  `liveRegion`，否则折叠态下每一句歌词都会被 TalkBack 播报（展开态面板已经播报一次，会重复）。

---

## 5. 通知歌词链路：机制、节流语义、以及「托盘能不能复用」

### 5.1 写入点：`PlayerViewModel` 的 2Hz pump（**目前唯一的内容生产者**）

```kotlin
522:        // v1.5.1 · D：把「当前行」推给 PlaybackService —— 只在**跨行**时写一次
523:        // （currentPosition 是 2Hz 采样，这里每次采样只做一次 O(行数) 的二分/线性比较，
524:        // 值没变就不写），通知栏因此不会逐帧重绘。关掉开关或无歌词时推 null。
525:        viewModelScope.launch {
526:            combine(lyrics, currentPosition, lyricsInMediaSession) { lines, pos, on ->
527:                Triple(lines, pos, on)
528:            }.collect { (lines, pos, on) ->
529:                val line = if (!on || lines.isEmpty()) null else lines.lastOrNull { it.timeMs <= pos }?.text
530:                if (line != lastMediaLyricLine) {
531:                    lastMediaLyricLine = line
532:                    PlaybackService.mediaLyricLine = line
533:                }
534:            }
535:        }
```
（[PlayerViewModel.kt:522-535](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)。注意 `529` 用的是
**线性 `lastOrNull` 扫描**，语义与 `currentLineIndex` 等价（都是「最后一个 `timeMs <= pos` 的行」），
差别只在「第一行之前」：`lastOrNull` → `null`，`currentLineIndex` → `-1`。）

重置点：关掉开关时立刻清空（[PlayerViewModel.kt:803-812](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)）。

### 5.2 传输层：`PlaybackService.mediaLyricLine`（companion 里的 `@Volatile var`）

```kotlin
169:        @Volatile var mediaLyricLine: String? = null
170:            set(value) {
171:                if (field == value) return
172:                field = value
173:                // v2.0.2：跨行时重 post 通知，**所有 API 版本都发**（v1.8.0 的 `SDK_INT < P`
174:                // 闸门已被真机推翻，见 LyricNotifyGate 的完整论证）。
178:                instance?.onMediaLyricLineChanged(value)
179:            }
```
（[PlaybackService.kt:169-179](../../../app/src/main/java/com/takahashirinta/ncrust/player/PlaybackService.kt)。）

**它是纯 Android 侧可变字段：没有 `StateFlow`、没有 `snapshot state`、没有监听器注册点。**

### 5.3 节流闸门：`LyricNotifyGate`（全文语义）

```kotlin
 42:internal object LyricNotifyGate {
 51:    const val MIN_INTERVAL_MS = 250L
 59:    const val NEVER_POSTED = "\u0000NEVER_POSTED\u0000"
 62:    sealed interface Decision {
 64:        data object Post : Decision
 67:        data object SkipNotStarted : Decision
 70:        data object SkipSameLine : Decision
 73:        data class Defer(val retryInMs: Long) : Decision
 74:    }
 83:    fun decide(
 84:        line: String?,
 85:        lastPostedLine: String?,
 86:        nowMs: Long,
 87:        lastPostAtMs: Long,
 88:        serviceStarted: Boolean,
 89:    ): Decision {
 90:        if (!serviceStarted) return Decision.SkipNotStarted
 91:        if (line == lastPostedLine) return Decision.SkipSameLine
 92:        val elapsed = nowMs - lastPostAtMs
 93:        if (elapsed < MIN_INTERVAL_MS) {
 96:            return Decision.Defer((MIN_INTERVAL_MS - elapsed).coerceAtMost(MIN_INTERVAL_MS))
 97:        }
 98:        return Decision.Post
 99:    }
100:}
```
（[LyricNotifyGate.kt:42-100](../../../app/src/main/java/com/takahashirinta/ncrust/player/LyricNotifyGate.kt)，单测
[LyricNotifyGateTest.kt](../../../app/src/test/java/com/takahashirinta/ncrust/player/LyricNotifyGateTest.kt)。）

**节流语义三条（KDoc 30-40 行）**：
1. 服务没进前台 → **不发**；
2. 要显示的行与通知里当前那一行**相同** → 不重发（这条同时挡掉「同一行被反复推」）；
3. 距上次 post 不足 `MIN_INTERVAL_MS = 250ms` → **延后重试而不是丢弃**（说唱段落行变化可能快到 200ms 一次，
   调用方按 `Defer.retryInMs` 挂一次延迟重试，保证「最后一行一定会到达」）。

> ⚠️ 这条闸门的**语义是「通知保持最新」而不是「UI 与音频精确对齐」**：`Defer` 意味着面板上的歌词行
> 最多会比音频**晚 250ms 一档**。托盘若复用它，就会继承这个延迟（对托盘或许可接受，
> 但它不是为 UI 设计的）。

### 5.4 两行文案规则：`MediaDisplayLines`（全 51 行）

```kotlin
 39:    data class Lines(val title: String, val subtitle: String)
 41:    fun of(songTitle: String, songArtist: String, lyricLine: String?): Lines {
 42:        val lyric = lyricLine?.takeIf { it.isNotBlank() }
 43:            ?: return Lines(title = songTitle, subtitle = songArtist)
 44:        val subtitle = when {
 45:            songArtist.isBlank() -> songTitle
 46:            songTitle.isBlank() -> songArtist
 47:            else -> songTitle + " · " + songArtist
 48:        }
 49:        return Lines(title = lyric, subtitle = subtitle)
 50:    }
```
（[MediaDisplayLines.kt:39-50](../../../app/src/main/java/com/takahashirinta/ncrust/player/MediaDisplayLines.kt)，单测
[MediaDisplayLinesTest.kt](../../../app/src/test/java/com/takahashirinta/ncrust/player/MediaDisplayLinesTest.kt)。
KDoc 的规则表：有歌词行 → 第一行 = 当前歌词行 / 第二行 = `歌名 · 艺人`；没歌词行 → `歌名` / `艺人`。
边界：空白歌词行当作没有歌词；艺人缺失不留 `"歌名 · "` 尾巴；两者都缺 → 第二行空串。）

> **注意：这正是本次托盘的目标两行版式（歌词在上、`歌名 · 艺人` 在下）—— 但托盘的
> 第二行还要求带音源角标，而 `MediaDisplayLines` 只处理纯文本、且它返回的是「通知的两行」
> 而不是「托盘的字段」。可以借鉴它的边界规则（空白歌词行当没有），但不要直接拿它当托盘数据源。**

### 5.5 消费端：通知、锁屏、Live Update

| 通道 | 代码 | 用的两行 |
|---|---|---|
| 媒体通知正文 | `buildNotification()`（[PlaybackService.kt:1656-1660](../../../app/src/main/java/com/takahashirinta/ncrust/player/PlaybackService.kt)） | `MediaDisplayLines.of(...)` |
| 会话 metadata（锁屏 / 系统媒体面板） | [PlaybackService.kt:1346-1378](../../../app/src/main/java/com/takahashirinta/ncrust/player/PlaybackService.kt)，另有回退开关 `session_metadata_lyrics`（默认 true，1396-1406），分支逻辑 `MediaSessionMerge.sessionLines`（1356-1361） | 同上 / 只写歌名艺人 |
| Android 16 Live Updates（API 36） | [LiveUpdateNotifier.kt:79-119](../../../app/src/main/java/com/takahashirinta/ncrust/player/LiveUpdateNotifier.kt)，`update(context, title=display.title, artist=display.subtitle, positionMs, durationMs, isPlaying)` | `MediaDisplayLines.of(...)`（[PlaybackService.kt:1626-1634](../../../app/src/main/java/com/takahashirinta/ncrust/player/PlaybackService.kt)） |
| 重 post 触发 | `onMediaLyricLineChanged(line)`（[PlaybackService.kt:1569-1596](../../../app/src/main/java/com/takahashirinta/ncrust/player/PlaybackService.kt)）→ `LyricNotifyGate.decide` → `updateNotify()`（1620-1654，记账 `lastPostedLyricLine` / `lastLyricNotifyAt`） | — |

### 5.6 **托盘能不能复用通知那条源？—— 不能，且不应**

| 判据 | 实测证据 | 后果 |
|---|---|---|
| **受默认关闭的设置项闸门** | `lyricsInMediaSession` 默认 **false**（[PlayerViewModel.kt:149-155](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)、init 读 `lyrics_in_media_session` 默认 false 于 [508-511](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)）；`529` 行 `if (!on ...) null` | 托盘会**跟着一个"媒体面板显示歌词"的开关一起失效**，语义完全不相干 |
| **不可观察** | `mediaLyricLine` 是 `@Volatile var`（169），无 StateFlow / 无 snapshot state / 无注册回调 | 托盘必须新增监听机制（第二套状态通道） |
| **写它 = 发通知** | setter 直接 `instance?.onMediaLyricLineChanged(value)`（178）→ 可能 `updateNotify()`（1581） | 托盘把 UI 绑到「重 post 通知」这个副作用上；只要以后有人改通知策略，托盘跟着变 |
| **节流语义不是 UI 语义** | `Defer` 最多 250ms 延迟（[LyricNotifyGate.kt:51](../../../app/src/main/java/com/takahashirinta/ncrust/player/LyricNotifyGate.kt)） | 托盘会继承一档最多 250ms 的滞后 |

**⇒ 正确做法：复用「同一份计算」，不要复用「同一个变量」。** 即：`lyrics` + `currentPosition` +
`currentLineIndex`（=`PlayerViewModel` 529 行正在算的同一件事），在 UI 层重新算一次 ——
零新增歌词引擎、零新增状态通道、与面板/通知的行判定天然一致（`null` vs `-1` 的边界按 §3.2 的
`currentLineIndex` 语义处理）。

---

## 6. `PlayerViewModel` 与托盘相关的 StateFlow 清单

| Flow | 类型 | 初值 | 更新时机 / 频率 | 行 |
|---|---|---|---|---|
| `isPlaying` | `MutableStateFlow<Boolean>` | `false` | `PlaybackService.onIsPlayingChanged` 回调 | [87](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `currentPosition` | `MutableStateFlow<Long>` | `0L` | **2Hz**（仅播放中）+ seek 后立即一次 | [88](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)（写入点 537-540） |
| `duration` | `MutableStateFlow<Long>` | `0L` | 同上 | [89](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `progress` | `MutableStateFlow<Float>` | `0f` | 同上 | [90](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `lyrics` | `MutableStateFlow<List<LrcLine>>` | `emptyList()` | 取词落地（切歌清空 → 加载完成写入） | [91](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `lyricsSongId` | `MutableStateFlow<Long>` | `-1L` | 与 `lyrics` 同步（判「歌词属于当前歌」） | [95](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `translatedLyrics` | `MutableStateFlow<List<LrcLine>>` | `emptyList()` | 取词落地 | [97](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `romanizedLyrics` | `MutableStateFlow<List<LrcLine>>` | `emptyList()` | 取词落地 | [107](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `lyricsLoading` | `MutableStateFlow<Boolean>` | `false` | 请求开始/结束 | [110](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `lyricsNoContentSongId` | `MutableStateFlow<Long>` | `-1L` | 服务端明确「无歌词」时（如 [1782](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)） | [114](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `showLyricsTranslation` | `MutableStateFlow<Boolean>` | `true` | 设置页开关 | [116](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `showLyricsRomanization` | `MutableStateFlow<Boolean>` | **`false`** | 设置页开关 | [127](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `lyricsInMediaSession` | `MutableStateFlow<Boolean>` | **`false`** | 设置页开关（`lyrics_in_media_session`） | [155](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `isBuffering` | `MutableStateFlow<Boolean>` | `false` | 缓冲回调 | [176](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `needsPreload` | `MutableStateFlow<Boolean>` | `false` | **每首歌一次**（剩 ≤60s 且 `!needsPreload` 时置 true，[563-568](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)） | [178](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `currentSongId` / `currentSongName` / `currentSongArtist` / `currentSongArtwork` | `MutableStateFlow<Long?>` / `<String?>` ×3 | `null` | 起播/切歌时写（冷启动恢复用，[MainActivity.kt:883-901](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt)） | [180-183](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `currentSource` | `MutableStateFlow<MusicSource>` | `NETEASE` | 切源/切歌 | [208](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |
| `currentQualityIndex` / `qualityStatus` | `MutableStateFlow<Int>` / `<QualityStatus>` | `3` / `NORMAL` | 取链/降级 | [242](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) / [252](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) |

> **托盘的 `song` 不来自这些 Flow**：`PlayerCard(song: SongItem?)` 由 `MainScreen` 的
> `currentSong` 直接传入（[MainActivity.kt:2082-2083](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt)），
> 所以第 2 行的歌名/歌手/音源**零新增状态**。
> **重组风险提示（既有纪律）**：`PlayerCard` 顶层刻意**不**订阅 `currentPosition` / `duration` /
> `isBuffering`（[PlayerCard.kt:162-167](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt) 注释：
> 「避免 PlayerCard 本身随位置更新 4Hz 重组」）。托盘第 1 行**必须**遵守同一条：
> 只能在托盘自己的叶子 composable 内订阅位置流。

---

## 7. 布局 / 自适应

### 7.1 `BottomOverlayInsetDp`

```kotlin
11: * - 窄屏（<600dp）：底部导航 80dp + miniBar 56dp + 视觉缓冲 8dp = 144dp。
12: * - 宽屏（>=600dp）：无底部导航（改用左侧 sidebar），只剩 miniBar 56dp + 缓冲 8dp = 64dp。
21: val BottomOverlayInsetDp: Dp
22:     @Composable get() = if (LocalConfiguration.current.screenWidthDp >= 600) 64.dp else 144.dp
```
（[BottomOverlayInset.kt:11-22](../../../app/src/main/java/com/takahashirinta/ncrust/ui/BottomOverlayInset.kt)。）

**它与托盘高度的耦合是"文档级"的，不是计算级**：`56` 被硬编码在注释与算式里，**没有任何代码
从托盘实测高度反推这个常量**。全仓库 `56.dp` 的落点（`grep 56\.dp` 实测，剔除无关项后）：

| 文件:行 | 用途 |
|---|---|
| [PlayerCard.kt:1326](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt) | **托盘高度** |
| [PlayerCard.kt:240](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt) | `miniCoverHalfPx = 28.dp`（= 托盘高 / 2） |
| [MainActivity.kt:865](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt) | `miniBarHeightPx`（进 `collapsedOffsetY` 公式） |
| [MainActivity.kt:864](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt) | `navBarHeightPx`（窄屏 56dp；底部导航 Box 也是 56dp，见 2383） |
| [PlayerCard.kt:1133](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt) | 窄屏展开态**顶栏**高度（与托盘等高，保证折叠动画封面落点无缝） |
| [BottomOverlayInset.kt:11-12](../../../app/src/main/java/com/takahashirinta/ncrust/ui/BottomOverlayInset.kt) | KDoc 算式 |

**`collapsedOffsetY` 的完整算式**（[MainActivity.kt:863-877](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt)）：

```kotlin
864:    val navBarHeightPx = if (isWideLayout) 0f else with(density) { 56.dp.toPx() }
865:    val miniBarHeightPx = with(density) { 56.dp.toPx() }
874:    val collapsedOffsetY =
875:        contentHeightPx - sysNavPx - navBarHeightPx - miniBarHeightPx - sysStatusPx
```
（`sysStatusPx` / `sysNavPx` 见 846-847；`isWideLayout = windowWidthDp >= 600` 见 851-852；
`contentHeightPx` 车机走实测、其余走 `screenHeightDp`，见 866-870。）

### 7.2 宽屏（`>= 600dp`）与横屏行为

| 场景 | 代码事实 | 托盘行为 |
|---|---|---|
| 宽屏竖持（平板） | `isWidePlayer = screenWidthDp >= 600`（[PlayerCard.kt:181](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)）；`isWideLayout`（[MainActivity.kt:852](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt)）把底部导航换成 200dp sidebar | 托盘**不变**：同一条 `fillMaxWidth().height(56.dp)`，横跨整宽并盖住 sidebar 空余底部（[MainActivity.kt:2141-2143](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt) 注释：「底部 miniBar 仍整宽叠加，自然盖住侧栏空余的底部」） |
| 宽屏 + 收起 | `usesSideCover = isWidePlayer \|\| bigScreenActive`（[195](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)） | 唯一差别：封面折叠落点在屏左上角（`miniCoverCenterX/Y`，无宽屏分支） |
| 手机横屏（唯一路径 = 大屏模式） | `isBigScreenActive(requested, orientationLandscape) = requested && landscape`（[PlayerLayout.kt:40-41](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerLayout.kt)）；`bigScreenActive` 在 [PlayerCard.kt:186-192](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt) | 卡片必为展开态（从全屏播放器按 ⤢ 进入），托盘 `alpha = 0` 但**仍挂载**；整卡拖拽被停用（[548-553](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)），收起按钮整层不挂载（[1476](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)）⇒ 不可能出现「横屏 + 收起态托盘」 |
| 平板横屏 + 收起 | 平板 `smallestScreenWidthDp >= 600` ⇒ 不锁方向（[MainActivity.kt:200-201](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt) 注释 + `applyOrientationPolicy`）；用户未开大屏时可收起卡片 | **托盘会显示**（两行布局会跟着显示）—— 「横屏不显示托盘」**不是代码保证**，只是手机上的间接结果 |
| 控制栏收起手势 | `controlsCollapseDrag` 在宽屏直接 `return@pointerInput`（[PlayerCard.kt:313-314](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)） | 与托盘无关，但说明「宽屏不启用」的既有写法就是 `isWidePlayer` 守卫 |

**建议（两选一，必须显式决定）**：
- **(A)（推荐，最小回归面）** 用 `val portraitTray = !usesSideCover`（等价于
  `screenWidthDp < 600 && !bigScreenActive`）作为新两行布局的开关 ⇒ 只有**窄屏手机**拿到新版式；
  平板（含竖持，`screenWidthDp >= 600`）与大屏保持今天的单行内容。
- **(B)** 若产品要求「平板竖持也要歌词托盘」，改用
  `LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT`（与 `bigScreenActive`
  互斥）—— 但这样平板竖屏会走一条**未经验证**的版式分支（封面落点在屏左上角、文字列宽得多），
  需要额外的真机核对。

无论选哪个，宽屏/大屏路径都**保持今天的单行内容**，不要给平板横屏引入未验证的新版式。

### 7.3 高度：**建议保持不变（56dp）**

现托盘已是两行（`bodyMedium` 20sp 行高 + `bodySmall` 16sp 行高 = 36dp 文本，居中于 56dp）。
目标版式同样是两行（歌词 / `歌名 作者 音源`），**内容行数不变**：

- 保持 56dp ⇒ `collapsedOffsetY`、`BottomOverlayInsetDp`、`miniCoverHalfPx`、
  窄屏顶栏 56dp、封面折叠落点**全部不用动**，回归面积最小；
- 若非要加高（例如 64dp），必须一次性改 §7.1 表里的**全部 5 处 + 1 处 KDoc**，
  并且要重测「折叠态死带」（AGENTS「Compose 触摸陷阱」第 2/5/6 条：`collapsedOffsetY` 一变，
  详情页底部交互元素是否落进死带就要重新核对）。

---

## 8. 点击目标与导航回调

### 8.1 托盘现有的点击目标（真实代码）

| 区域 | 手势 | 回调 | 去向 |
|---|---|---|---|
| 整条（文字列 + 空白，除按钮外） | `Modifier.clickable`（无 indication） | 无命名回调：**内联** `progress.animateTo(1f, tween(400, CubicBezierEasing(0.2f,0f,0f,1f)))` | [PlayerCard.kt:1329-1340](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)（与 `expandCard()` 同一条动画参数，[MainActivity.kt:1476-1480](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt)） |
| 播放/暂停按钮（48dp） | `MetroIconButton.onClick` | `haptic + onPlayPause()` | [MainActivity.kt:2092](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt) → `playerViewModel.togglePlayPause()` |
| 下一首按钮（48dp） | `MetroIconButton.onClick` | `haptic + onPlayNext()` | [MainActivity.kt:2095](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt) → `playNext()` |
| （空态分支）播放键 | `MetroIconButton.onClick` | `haptic + onPlayNothing()` | [MainActivity.kt:2100-2109](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt) → 每日推荐开播 |
| 整卡上/下拖 | 根 `Box.pointerInput` | 内联吸附 | [PlayerCard.kt:548-637](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)（**托盘的 clickable 会消费 down**，所以拖拽用了「忽略消费标志」的自定义 slop 累积：565-566、596 注释） |

**❌ 纠正任务书里的一个假设**：托盘**没有** `onExpand` / `onSongInfoClick` 回调参数 ——
- `onExpand` 不存在，展开是内联动画；
- `onSongInfoClick` **不在托盘上**：它只出现在展开态的 4 个位置
  （大屏左栏 [978](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)、宽屏左栏 1082、窄屏大封面 overlay 1215，以及展开态顶栏 1143），
  去向是 [MainActivity.kt:2122-2125](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt)：
  `currentSong?.let { menuSong = it; menuSongActions = emptyList() }` → 打开全局 `SongMenuSheet`。

### 8.2 「歌手页」导航的现成入口

```kotlin
2467:                        SongMenuAction(Icons.Default.Person, LocalStrings.current.actionGoToArtist) {
2468:                            resolveAndNavigate(song, toArtist = true)
2469:                        },
```
（[MainActivity.kt:2467-2469](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt)；`resolveAndNavigate` 定义在
[MainActivity.kt:1793-1824](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt)：

```kotlin
1798:    fun resolveAndNavigate(song: SongItem, toArtist: Boolean) {
1801:            val idMissing = if (toArtist) target.artists?.firstOrNull()?.id == null else target.album?.id == null
1805:            if (idMissing) {
1806:                target = runCatching { PlaylistApi.getSongsByIds(listOf(song.id)) }.getOrDefault(emptyList()).firstOrNull() ?: song
1809:            val artistId = target.artists?.firstOrNull()?.id
1813:                    toArtist && artistId != null -> {
1814:                        if (progress.value > 0.01f) collapseCard()
1815:                        navController.navigate(NavRoutes.artist(artistId))
```

路由定义（[NavGraph.kt:25-102](../../../app/src/main/java/com/takahashirinta/ncrust/ui/navigation/NavGraph.kt)）：

| 常量 / 函数 | 行 | 值 |
|---|---|---|
| `ARTIST` | 28 | `"artist/{artistId}"`（`LongType`，ncm） |
| `ARTIST_SRC` | 75 | `"artist/{source}/{artistId}"`（v2.4.0 · E，**StringType**，QQ 的 `singerMID`） |
| `fun artist(artistId: Long)` | 80 | `"artist/$artistId"` |
| `fun artist(source: MusicSource, id: String)` | 86 | `"artist/${source.key}/$id"` |
| `ArtistDetailScreen` 注册 | 200 / 221 | 两条路由各一个入口 |

**改造建议（第 2 行歌手可点）**：`resolveAndNavigate` 是 `MainScreen` 内的局部函数，无法从
`PlayerCard` 直接调用 ⇒ 需要新增一个纯透传回调（如 `onArtistClick: () -> Unit = {}`，
`PlayerCardOverlay` 与 `PlayerCard` 各加一个默认参数，零破坏），在 `MainScreen` 侧接
`{ currentSong?.let { resolveAndNavigate(it, toArtist = true) } }`。
**这个改动不需要任何新文案**（没有新 UI 文字，只是让已有区域可点）；
注意复用 `resolveAndNavigate` 的「id 缺失先拉 song/detail」兜底（1801-1808），不要自己再写一套。

---

## 9. i18n 约束（`Strings` 机制与预算）

### 9.1 机制

- 唯一容器是 `data class Strings`（[Strings.kt:53](../../../app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt)，1253 行），
  文案按功能面放进嵌套组；老调用点靠**类体里的转发属性**保住（`val sourceNetease: String get() = source.sourceNetease`）。
- **8 个语言文件**（`ls app/src/main/java/com/takahashirinta/ncrust/ui/i18n/` 实测）：
  `zh_CN.kt` / `zh_TW.kt` / `en.kt` / `jp_JP.kt` / `jp_MY.kt` / `ko_NK.kt` / `de_DE.kt` / `ru_RU.kt`
  （测试里逐个列出的清单见 [StringsConstructorBudgetTest.kt:243](../../../app/src/test/java/com/takahashirinta/ncrust/ui/i18n/StringsConstructorBudgetTest.kt)）。
- 加一条文案 = 属性进组 + **8 个语言文件全部补实参**（漏一个就编译错）+ 转发属性（若要保持 `strings.xxx` 写法）。

### 9.2 预算（`StringsConstructorBudgetTest` 实测值）

| 项 | 值 | 行 |
|---|---|---|
| `Strings` 主构造器预算（硬上限） | **150** | [73](../../../app/src/test/java/com/takahashirinta/ncrust/ui/i18n/StringsConstructorBudgetTest.kt) |
| 预警线 | 140 | [81](../../../app/src/test/java/com/takahashirinta/ncrust/ui/i18n/StringsConstructorBudgetTest.kt) |
| 单个嵌套组硬上限 / 预警 | 120 / 80 | [84](../../../app/src/test/java/com/takahashirinta/ncrust/ui/i18n/StringsConstructorBudgetTest.kt) / [87](../../../app/src/test/java/com/takahashirinta/ncrust/ui/i18n/StringsConstructorBudgetTest.kt) |
| **`Strings` 主构造器被精确钉死** | **128**（另断言 dex 槽 = 134、余量 ≥ 100） | [176-187](../../../app/src/test/java/com/takahashirinta/ncrust/ui/i18n/StringsConstructorBudgetTest.kt) |
| **三个新组规模被精确钉死** | `SettingsStrings=64`、`AboutStrings=25`、**`PlayerUiStrings=31`** | [226-237](../../../app/src/test/java/com/takahashirinta/ncrust/ui/i18n/StringsConstructorBudgetTest.kt) |
| dex 槽算式 | `this(1) + N + ceil(N/32) + DefaultConstructorMarker(1) ≤ 255`；`N=245` 时正好 255（v2.3.0 真机启动即崩的根因） | [104-106](../../../app/src/test/java/com/takahashirinta/ncrust/ui/i18n/StringsConstructorBudgetTest.kt)、[Strings.kt:12-20](../../../app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt) |

**⇒ 加一条播放器文案的完整代价**：改 `PlayerUiStrings`（[Strings.kt:1200-1253](../../../app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt)）
+ 改 8 个语言文件 + **改钉死断言 31**（那条测试的注释明确说：「若是有意加文案……然后同步改这条断言
并在提交信息里说明」）+ 版本可追溯性成本。

### 9.3 结论：**本次改造可以做到零新增文案**

| 需求 | 现成资源 |
|---|---|
| 第 2 行音源角标 | `strings.sourceNetease` / `strings.sourceQqMusic`（[Strings.kt:712](../../../app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt) / [668](../../../app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt)） |
| 第 1 行降级文案 | **不加文案**：直接用 `SongItem.name`（并在 `name` 为空时用 `ArtistLineWithSource` 的歌手，或留空） |
| 无歌词提示 | **不显示**（展开态面板已有 `strings.noLyrics`，托盘不需要重复） |
| 点击语义 | 不加 `contentDescription`（见 §4.3 的理由） |

---

## 10. 行长与长行处理

### 10.1 一行歌词可以有多长

- **解析层不做任何长度限制**：`LrcParser.parse` 只按正则取时间戳与文本、`trim()` 后原样入库
  （[LrcParser.kt:45-64](../../../app/src/main/java/com/takahashirinta/ncrust/lyric/LrcParser.kt)）——
  ⇒ 托盘第 1 行可能是一整句长句（中文 20–40 字、英文更长），**也可能是一条元信息行**：
  ncm 的 LRC 前几行常是 `作词 : xxx` / `作曲 : xxx`，解析器**不过滤**它们
  （`grep 作词` 只命中 [YrcParser.kt:25/222-223](../../../app/src/main/java/com/takahashirinta/ncrust/lyric/YrcParser.kt) 的注释与
  [YrcAligner.kt:26](../../../app/src/main/java/com/takahashirinta/ncrust/lyric/YrcAligner.kt) 的说明）。
  面板今天就会把这些行显示出来 ⇒ **托盘跟着显示是"与面板一致"的正确行为**，不要额外过滤（否则
  托盘与面板的当前行会不一致）。
- 「确无歌词」是两条路径：`lyricsNoContentSongId`（服务端明确无词，
  [PlayerViewModel.kt:111-114](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)、[1779-1783](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)）
  或 `lrc == "[00:00.00]暂无歌词"`（此时它是一条真实行，面板会显示这句）⇒ 托盘第 1 行会显示
  「暂无歌词」四个字。**这是可接受的**（与面板一致，且不需要新文案）；若要更干净，
  应在 `PlayerViewModel` 过滤而不是在托盘过滤（但那会改面板行为，超出本任务范围）。

### 10.2 面板对长行的处理：**软换行，不截断**

- 主行：`softWrap = true`、**无 `maxLines`**（[NcrustLyricsPanel.kt:677-685](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/NcrustLyricsPanel.kt)）；
- 译文槽：`softWrap = true`、无 `maxLines`（[574-589](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/NcrustLyricsPanel.kt)）；
- 音译槽：同（[599-613](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/NcrustLyricsPanel.kt)）；
- 面板还用 `LyricsPanelScroll.blockOffsetPx` 按「整块实测高度」做居中/上沿定位
  （[NcrustLyricsPanel.kt:223-289](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/NcrustLyricsPanel.kt)）——
  即**长行（多视觉行）是被显式支持的**，不是异常。
- **全仓库没有任何一处对歌词文本做省略/跑马灯。**

### 10.3 播放器里的省略/跑马灯现状（托盘策略的参照）

| 位置 | 处理 | 行 |
|---|---|---|
| 托盘歌名（今天） | `maxLines=1` + `TextOverflow.Ellipsis` | [PlayerCard.kt:1364-1365](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt) |
| 托盘歌手 | `maxLines=1` + `Ellipsis` + `weight(1f, fill=false)`；角标 `softWrap=false` 永不被挤 | [1654-1673](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt) |
| 窄屏展开态顶栏歌名 | `maxLines=1` + `TextOverflow.Clip` + **`basicMarquee`**（`iterations=Int.MAX_VALUE`、`animationMode=Immediately`、`initialDelayMillis=2000`、`repeatDelayMillis=2500`、`velocity=48.dp`） | [1145-1158](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt) |
| 窄屏大封面 overlay 歌名 | `maxLines=1` + `Ellipsis` | [1217-1223](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt) |
| 宽屏/大屏左栏歌名 | `maxLines=1` + `Ellipsis` | [980-986](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt) |
| `FullPlayerControls` 两处文本 | `maxLines=1` + `softWrap=false` + `Ellipsis` | [662-675](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/FullPlayerControls.kt) |

**策略对比**：

| 方案 | 优点 | 代价 | 建议 |
|---|---|---|---|
| **A. `maxLines=1` + `Ellipsis`** | 零帧调度、与今天托盘歌名逐像素同款、实现最简单、与 Kanesumi（无回弹/克制）一致 | 极长行看不到后半句 | ✅ **推荐**（托盘是常驻 60fps 区域，且用户点一下就能进全屏看整句） |
| B. `maxLines=1` + `Clip` + `basicMarquee` | 能看完整行 | 常驻帧调度 + 常驻 draw（跑马灯是无限循环动画），与「静态零帧调度/GPU 零重组」纪律冲突；在托盘这种「一直在屏幕最下方」的位置会持续吸引注意力 | ❌ 不推荐（跑马灯只应留给用户主动盯着的顶栏歌名） |
| C. `maxLines=2`（允许折行） | 不丢内容 | 托盘高度要 56 → 70+dp，牵动 §7.1 的 5 处硬编码 + 死带复测；且会挤压右侧 96dp 按钮区 | ❌ 不建议（成本/收益不划算） |
| D. 预截断（`take(n) + "…"`） | 省一点排版 | 引入「多少个字」这个拍脑袋阈值；CJK/拉丁宽度不同，`…` 还要占位 | ❌ 不推荐（Compose 的 `Ellipsis` 已经按真实排版宽度决定） |

---

## 11. 推荐方案（可施工）

### 11.1 第 1 行数据源：候选排序

| 排名 | 候选 | 评价 |
|---|---|---|
| **1（推荐）** | `PlayerViewModel.lyrics` + `currentPosition` + 既有纯逻辑 `currentLineIndex`，在**叶子 composable** 内用 `derivedStateOf` 收敛 | ① 与面板同一份行判定（同一函数，不会错开一行）；② 不依赖任何设置项（`lyrics_in_media_session` 默认关）；③ 不产生副作用（不发通知）；④ 无新增状态通道、无新增节流器；⑤ 2Hz 采样 → 字符串只在跨行时变 ⇒ **天然行级重组** |
| 2 | `LyricsView` 的按需唤醒循环思路（`boundaries` + `delay`） | 能给出**精确到边界毫秒**的行切换，但需要**新建一个 coroutine 循环 + 一个新的 `displayPosition` 状态**，等于把 `LyricsView` 里的引擎复制一份到托盘；而托盘只需要「行级」精度，2Hz 采样已经足够（最坏误差 = 一个 tick）。**仅当实测发现托盘比面板晚一行时才考虑**（那时更好的做法是把 `LyricsView` 的循环上提共享，而不是复制） |
| 3 | `PlaybackService.mediaLyricLine`（通知那条源） | ❌ 受默认关闭的 `lyrics_in_media_session` 闸门（[PlayerViewModel.kt:155](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt) / [529](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)）、不可观察（`@Volatile var`）、写它 = 重 post 通知（[PlaybackService.kt:169-179](../../../app/src/main/java/com/takahashirinta/ncrust/player/PlaybackService.kt)）、且带 250ms 的 `LyricNotifyGate` 延后（[LyricNotifyGate.kt:51](../../../app/src/main/java/com/takahashirinta/ncrust/player/LyricNotifyGate.kt)）。**如果产品坚持"与通知必须一字不差"，正确做法是：把 `PlayerViewModel` 529 行的计算抽成一个 `StateFlow<String?>`（例如 `currentLyricLine`）供**托盘与通知**共用，而不是让托盘去读那个 `var`** |
| 4 | 新建独立的歌词引擎（解析/计时） | ❌ 明确不做（会造出第二处真相） |

### 11.2 节流策略（明确写成契约）

1. **只按「行」更新**，不按词、不按帧；
2. 数据源 = 2Hz 的 `currentPosition`；**更新发生在「字符串内容变化」时**（`derivedStateOf` 的值比较），
   而不是「位置变化」时 ⇒ 同一行内的 2Hz 采样**不产生任何重组**；
3. **不在托盘里跑 `withFrameNanos`、不建 coroutine 循环、不做外推**（外推是逐字渲染的需求，
   托盘没有逐字需求）；
4. 切歌瞬间：`resetLyricsForNewSong()` 会同时清空 `lyrics` / `translatedLyrics` / `romanizedLyrics`
   并把 `currentPosition` 归零（[PlayerViewModel.kt:741-757](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)，
   注释 736-739 明确「切歌后不再保留旧歌词，避免残留上一首」）⇒ 托盘第 1 行**立刻回落到歌名**，
   不会残留上一首的歌词（与 `lyricsReady` 的既有语义一致：
   [PlayerCard.kt:376-382](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)）；
5. 暂停态：`currentPosition` 不再推进 ⇒ 第 1 行自然冻结在暂停那一刻的行（正确语义）；
   seek 后 `publishProgressNow()` 会立刻补一次采样 ⇒ 第 1 行立即跟上。

### 11.3 降级策略（无歌词 / 未就绪 / 未到第一行）

| 情况 | 判据（代码） | 第 1 行 | 第 2 行 |
|---|---|---|---|
| 无歌（`hasSong=false`） | `song == null` | 保持现状 `strings.noSongPlaying`（死代码，但别删） | — |
| 切歌瞬间 / 加载中 | `lyrics.isEmpty()` 或 `lyricsSongId != song.id` | **歌名** | `ArtistLineWithSource` |
| 服务端明确无词 | `lyricsNoContentSongId == song.id` | **歌名** | 同上 |
| 有歌词但还没到第一行 | `currentLineIndex(...) == -1` | **歌名** | 同上 |
| 当前行文本为空白 | `line.text.isBlank()` | **歌名**（避免托盘出现一条空行） | 同上 |
| 有当前行 | `index >= 0` | **当前行原文** | **歌名 + 歌手 + 音源角标** |

> 推荐实现上把「有歌词但没到第一行」与「无歌词」统一成同一个回落：**第 1 行 = 当前行 ?: 歌名**。
> 这样写只有一个分支、零新增文案，且用户永远看到有意义的信息。

### 11.4 第 2 行结构（三字段的宽度分配）

```
[ 56×56 封面占位 ] [ 第1行：歌词（或歌名回落）  weight(1f), Ellipsis ] [ ▶ 48dp ] [ ⏭ 48dp ]
                    [ 第2行：歌名 · 歌手 · 音源 ]
```
- 56dp 托盘内水平可用宽 ≈ `屏宽 − 56(封面) − 24(左右 12dp padding) − 96(两按钮)`（窄屏 360dp 时 ≈ 184dp）
  ⇒ **第 2 行必须做严格让位**：歌名 `weight(1f, fill=false) + Ellipsis`、歌手 `weight(1f, fill=false) + Ellipsis`、
  **音源角标 `softWrap=false` 且不参与 weight**（沿用 `ArtistLineWithSource` 的既有纪律：
  「角标永远完整可见 —— 反过来就正好丢掉了这个角标存在的意义」，[PlayerCard.kt:1632-1633](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)）；
- 三字段顺序建议 `歌名 · 歌手 · 音源`（与 `MediaDisplayLines` 的 `歌名 · 艺人` 顺序一致）；分隔符用现成的
  字面量 `"· "`（`ArtistLineWithSource` 已经是这么做的，**不是字符串资源**，无需新增文案）。

### 11.5 点击目标映射（复用现有回调）

| 区域 | 点击行为 | 怎么接 |
|---|---|---|
| 第 1 行歌词（整行文字） | **展开播放器**（与今天整条点击一致） | 什么都不用做：它落在托盘根 `Box` 的 `clickable` 内（[PlayerCard.kt:1329-1340](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)）。⚠️ 不要给第 1 行单独加 clickable/seek —— 会抢掉「点哪里都能展开」的既有手感 |
| 第 2 行歌名 | 展开播放器（同上，不单独接） | 同上（保持"点一下就能看到全部信息"） |
| 第 2 行歌手 | **进歌手页** | 新增透传回调 `onArtistClick: () -> Unit = {}` → `MainScreen` 侧 `currentSong?.let { resolveAndNavigate(it, toArtist = true) }`（[MainActivity.kt:1798-1824](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt)）。子节点 `clickable` 会先消费事件，因此不会误触发展开 |
| 播放/暂停按钮 | 不变 | `onPlayPause`（现成） |
| 下一首按钮 | 不变 | `onPlayNext`（现成） |
| 音源角标 | **不可点**（纯信息） | 无需回调 |
| 上/下滑 | 不变 | 根 `pointerInput`（现成，托盘 clickable 已被 565-566/596 的注释显式处理） |

> 若产品要求「点第 2 行歌名 → 打开歌曲菜单（转到歌手/专辑）」，也可以直接复用
> `onSongInfoClick`（展开态已在用、`MainScreen` 已接好）；但那样托盘内会出现两个语义相近的点击
> （歌名开菜单 / 歌手进歌手页）。**建议二者择一**，优先"歌手可点"。

### 11.6 实现草图

**(1) 抽一个纯逻辑 helper（JVM 可测，放 `ui/player/` 与 `PlayerDragSnap` / `LyricsPanelScroll` 同级）**

新文件 `app/src/main/java/com/takahashirinta/ncrust/ui/player/MiniBarLine.kt`：

```kotlin
internal object MiniBarLine {
    /** 托盘第 1 行要显示的主文本；null = 调用方改用回落（歌名）。 */
    fun lyricOrNull(lines: List<LrcLine>, positionMs: Long): String? {
        if (lines.isEmpty()) return null
        val ts = LongArray(lines.size) { lines[it].timeMs }
        val i = currentLineIndex(positionMs, ts)     // ← 直接复用面板的同一份二分查找
        if (i < 0) return null
        return lines[i].text.takeIf { it.isNotBlank() }
    }

    /** 第 1 行的最终文本：当前行 ?: 回落（避免顶层 ?? 分支散落在 composable 里）。 */
    fun primaryText(lines: List<LrcLine>, positionMs: Long, fallback: String): String =
        lyricOrNull(lines, positionMs) ?: fallback
}
```
> 逐帧调用成本：`LongArray(lines.size)` 的构造要放在 `remember(lines)` 里（见 (2)），
> 函数只做一次二分（O(log n)）。为了让 helper 自洽也可以把
> `fun primaryText(lines, timestamps, positionMs, fallback)` 收时间戳数组 —— 推荐后者，
> 避免每次调用重建数组。

**(2) 托盘第 1 行做成独立叶子 composable（状态读数收在叶子内，整卡零重组）**

```kotlin
@Composable
private fun MiniBarPrimaryLine(
    lines: List<LrcLine>,
    positionFlow: StateFlow<Long>,
    fallback: String,
    color: Color,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    // ★ 取 State 而不是 by：顶层组合作用域**不读** .value ⇒ PlayerCard 不订阅位置流
    val positionState = positionFlow.collectAsState()
    val timestamps = remember(lines) { LongArray(lines.size) { lines[it].timeMs } }
    // ★ 2Hz 采样只让这个 derivedState 失效；它算出的字符串不变 ⇒ 读它的本叶子不重组
    val text by remember(lines, fallback, timestamps) {
        derivedStateOf {
            MiniBarLine.primaryText(lines, timestamps, positionState.value, fallback)
        }
    }
    MetroText(text, color = color, style = style, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = modifier)
}
```

**(3) 托盘内的接线（替换 PlayerCard.kt:1360-1366 的 `MetroText(s.name, ...)`）**

```kotlin
Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
    if (!usesSideCover) {                       // ← 竖屏（窄屏）才上两行新版式，见 §7.2 的 (A)/(B)
        MiniBarPrimaryLine(
            lines = lyrics,                     // 已经在 PlayerCard 顶部 collect 过（PlayerCard.kt:139）
            positionFlow = playerViewModel.currentPosition,
            fallback = s.name,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.bodyMedium,
        )
    } else {
        MetroText(s.name, ...)                  // 宽屏/大屏保持今天的行为
    }
    // 第 2 行：歌名 + 歌手 + 音源（三字段一行，角标定宽不让位）
    Row(Modifier.fillMaxWidth()) {
        MetroText(s.name, style = bodySmall, color = onSurfaceVariant,
            maxLines = 1, overflow = Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        ArtistLineWithSource(song = s, color = onSurfaceVariant,
            style = bodySmall, badgeStyle = bodySmall)
    }
}
```
> `lyrics` 已在 `PlayerCard` 顶层 `collectAsState()`（[PlayerCard.kt:139](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)），
> 直接向下传引用即可 —— **不要**在托盘里再 `collectAsState()` 一次（那会多一个订阅者，
> 且 `lyrics` 的引用变化本来就应该重组整卡，因为它意味着换歌/取词完成）。
> 位置流相反：**只有叶子订阅**（见 (2) 的 `★`）。

**(4) 可选：把「当前歌词行」升级为 `PlayerViewModel` 的共享 StateFlow（若产品要求与通知一字不差）**

在 `PlayerViewModel` 里把 525-535 的 pump 改成先写一个
`val currentLyricLine = MutableStateFlow<String?>(null)`，通知侧（`PlaybackService.mediaLyricLine =`）
与托盘都读它。**代价**：多一个 StateFlow、`lyricsInMediaSession` 的闸门不能再直接复用
（托盘要求"总是有"，通知要求"开关关就没有"），需要把「计算」与「开关」拆开 —— 属于
**可选的第二步**，不要在第一步里做。

### 11.7 要写的单测清单（全部 JVM，无 Compose/无真机）

新建 `app/src/test/java/com/takahashirinta/ncrust/ui/player/MiniBarLineTest.kt`：

| # | 用例 | 断言 |
|---|---|---|
| 1 | 空歌词表 | `primaryText(emptyList(), ts, 5000, "歌名") == "歌名"` |
| 2 | 位置在第一行之前（`positionMs < lines[0].timeMs`） | 回落歌名（`currentLineIndex == -1` 语义） |
| 3 | 位置**恰好等于**某行时间戳 | 取该行（边界闭合：`<=`） |
| 4 | 位置落在两行之间 | 取前一行（与面板 `currentLineIndex` 逐值一致） |
| 5 | 位置超过最后一行 | 取最后一行 |
| 6 | 重复时间戳 | 取后者（二分返回最大下标，与面板一致） |
| 7 | 当前行文本纯空白 / 空串 | 回落歌名（避免托盘出现空行） |
| 8 | 行内 2Hz 连续采样（同一行内 10 个位置） | 返回的字符串**逐次相等**（`assertEquals`）—— 这是「行级节流」的可测代理 |
| 9 | 跨行扫描（从 0 扫到末行） | 字符串**恰好变化 `lines.size` 次**（用计数循环断言） |
| 10 | 与 `currentLineIndex` 的一致性 | 对同一批位置，`primaryText` 选中的下标 == `currentLineIndex` 的返回值（防第二处真相） |
| 11 | 长行 | 原样返回（**不截断** —— 截断交给 Compose `Ellipsis`；如果将来引入预截断，这条改成断言截断规则） |
| 12 | 无歌词时的降级不依赖任何设置 | `primaryText` 签名里不含 `showTranslation` / `lyricsInMediaSession` 之类的开关（用编译期强约束 + 一条「翻译关/开结果相同」的用例） |

补充（既有测试的护栏，不需要新写但要保持绿）：
- `StringsConstructorBudgetTest`（钉死 128 / 31）—— 零新增文案 ⇒ **必须不动**；
- `LyricsPanelScrollTest` / `LyricSubtitleTextTest` —— 不涉及；
- 若要顺手补 `currentLineIndex` 的直接单测（目前**没有**），可与 #3/#4/#6 合并。

### 11.8 回归风险清单（施工时逐条核对）

| 风险 | 核对点 |
|---|---|
| 命中测试 | 托盘永远挂载且永远可命中（AGENTS 陷阱 1）；托盘内新增的歌手 `clickable` **必须**是子节点（能消费 down），不能改成透明覆盖层 |
| 拖拽 | 根 `Box` 的整卡拖拽用「忽略消费标志」的 slop 累积（[PlayerCard.kt:563-592](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)）—— 新增子 clickable 不会破坏它，但**必须实测**「托盘上滑展开」仍然有效（v1.7.0 · P0 的回归点） |
| 死带 | 托盘高度**不变** ⇒ 死带不变；若改了高度，必须重跑 AGENTS 陷阱 2/5/6 的核对 |
| 重组 | 位置流**只在叶子订阅**；`PlayerCard` 顶层不得读 `currentPosition`（[PlayerCard.kt:162-167](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt) 的既有纪律） |
| 折叠动画 | 窄屏顶栏（[1133](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)）与托盘都是 56dp，封面折叠落点无缝；两行新版式只改**内容**不改高度 ⇒ 动画不受影响 |
| 宽屏/大屏 | 用 `!usesSideCover` 守卫，宽屏与大屏**行为零变化** |
| 切歌残留 | 切歌时 `lyrics` 被清空 ⇒ 第 1 行必须立刻回落到歌名（不能残留上一首）；`lyricsSongId` 是既有判据 |

---

## 12. 未确认 / 不确定

1. **真机观感未验证**（本探针只读代码，未构建、未上机）：两行都是 `bodySmall` 时第 2 行在 360dp 屏上的
   实际可读宽度（估算 ≈184dp）是否够放下「歌名 + 歌手 + 音源角标」；`MetroText` 的 `weight(1f, fill=false)`
   组合下歌名与歌手的让位顺序需要真机/预览确认。
2. **`derivedStateOf` 的重组边界只在纸面上成立**：`collectAsState()` 返回的 `State` 若不 `.value` 读，
   组合作用域不订阅 —— 这是 Compose 快照语义的标准结论，但本项目**没有既有代码用这个写法**
   （面板用的是 `rememberUpdatedState(lambda)` + `derivedStateOf`，见 NcrustLyricsPanel.kt:178-183）。
   落地时应用 `LiveLiterals`/Layout Inspector 或 `Modifier.drawWithContent` 计数实测「整卡不随 2Hz 重组」。
3. **`BottomOverlayInsetDp` 的 KDoc 与实现有一处历史不一致**：注释写「底部导航 80dp + miniBar 56dp + 8dp = 144」，
   而 `MainActivity` 里底部导航容器是 `.height(56.dp).navigationBarsPadding()`（[MainActivity.kt:2372-2383](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt)）、
   `navBarHeightPx` 也是 56dp（[864](../../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt)）。
   80dp 的来源未在本次调查中定位（可能含 `MetroBottomNav` 内部高度或历史值）。
   **与托盘改造无关**（托盘高度不变），但若有人要动 144 这个常量，必须先查清这 24dp 的差额。
4. **`lyrics_in_media_session` 的默认值**已核实为 **false**（[PlayerViewModel.kt:509-511](../../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt)）；
   但「用户实际开启比例」未知 —— 这只影响「复用通知源」这个已被否决的方案的收益评估。
5. **`currentLineIndex` 没有直接单测**（只有 `LyricsPanelScrollTest` 的一条注释提到它）——
   本次建议的 #3/#4/#6 用例等于给它补上；如果维护者认为它属于面板内部实现，
   应把「行判定」再抽一层（`LyricLineIndex.kt`）而不是让托盘直接依赖面板文件。
6. **平板横屏 + 收起态的托盘**：代码上会显示两行新版式（若守卫用 `!usesSideCover`，平板横屏
   `screenWidthDp >= 600` ⇒ `usesSideCover == true` ⇒ 会退回单行，即**不会**显示两行）。
   这一条依赖 `usesSideCover` 的定义（[PlayerCard.kt:195](../../../app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt)），
   若将来有人改这个谓词，托盘的布局分支会跟着变 —— 建议在代码注释里写死这条依赖。
7. **元信息行（`作词 : xxx`）会出现在托盘第 1 行**：这是与面板一致的**预期行为**，
   但产品是否接受未确认；若要过滤，应在 `PlayerViewModel` 落地歌词时过滤（会影响面板），
   不应只过滤托盘（会造成两处不一致）。
8. **`SweepTrack` 与托盘的边界**：本次结论是「托盘完全不需要 `SweepTrack`」（它只解决行内光标位置）。
   如果将来托盘要做「逐字进度条/卡拉OK 高亮」，才需要重新评估它 —— 但那与本次两行版式无关。
