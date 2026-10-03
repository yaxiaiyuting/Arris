# P2 探针：库页「歌单」tab 的区块手动折叠（v2.6.0）

| | |
|---|---|
| 探针对象 | 库页「歌单」tab（`LibraryPlaylistsTab`）的三个源区块：本地歌单 / ncm / qm |
| 目标特性 | 每个区块可**手动**展开/折叠；**绝不自动折叠**；折叠态**跨重启持久化**；折叠时显示「展开全部 N 个」；展开/折叠动画平滑 |
| 仓库 | `/home/duanjb666/deepseek/ncrust-gpl/Ncrust` |
| 分支 / HEAD | `master` / `7a4e33b`（`docs(v2.5.6): 回填设备验证结果与发布物信息（含未完成的平板复测）`） |
| 探针起点的树状态 | **`git status --porcelain` 为空（干净树）** —— 见 §0 的并发声明 |
| 结论日期 | 2026-09（本轮探针**只读**：本 agent 只创建了本文件，未修改任何源文件） |
| 取证纪律 | 每条结论都标 **（读码）/（命令）/（推断未验证）** 三类之一。所有「0 命中」结论均附 `EXIT` + stderr 字节数 + 同 scope 正向对照 |

---

## 0. 并发声明（读本文件前先看这一条）

本轮探针**开始**时工作区是干净的：

```console
$ git -C /home/duanjb666/deepseek/ncrust-gpl/Ncrust status --porcelain | head -20
（空）
```

探针进行中，工作区出现了**不属于本 agent** 的改动（同一工作区有并发的另一个 agent 在改「本地收藏单曲的持久化」）：

```console
$ git status --porcelain
 M app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt
 M app/src/test/java/com/takahashirinta/ncrust/contract/PersistenceFieldNameContractTest.kt
?? app/src/main/java/com/takahashirinta/ncrust/library/SavedSongCodec.kt
?? app/src/main/java/com/takahashirinta/ncrust/library/SavedSongModels.kt
?? app/src/test/java/com/takahashirinta/ncrust/library/LibraryImportPersistenceTest.kt
?? app/src/test/java/com/takahashirinta/ncrust/library/SavedSongCodecTest.kt
?? app/src/test/java/com/takahashirinta/ncrust/library/SavedSongSyncTest.kt
?? docs/verification/v2.6.0/          ← 本文件所在目录
```

**这些改动不是本轮探针产生的**（本 agent 只写了 `docs/verification/v2.6.0/probe-fold.md`）。
逐文件核对：本报告引用的**全部**证据文件都**未被**并发改动碰到（`git diff --quiet -- <file>` 逐个返回 CLEAN）：

```
app/src/main/java/com/takahashirinta/ncrust/ui/screen/LibraryPlaylistsTab.kt     CLEAN
app/src/main/java/com/takahashirinta/ncrust/ui/screen/LibraryScreen.kt           CLEAN
app/src/main/java/com/takahashirinta/ncrust/ui/theme/AppMotion.kt                CLEAN
app/src/main/java/com/takahashirinta/ncrust/ui/theme/PageTransitionSetting.kt    CLEAN
app/src/main/java/com/takahashirinta/ncrust/lyric/LyricsDisplayPrefs.kt          CLEAN
app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt                   CLEAN
app/src/main/java/com/takahashirinta/ncrust/ui/player/TrayLayout.kt              CLEAN
app/src/main/java/com/takahashirinta/ncrust/ui/BottomOverlayInset.kt             CLEAN
app/src/main/java/com/takahashirinta/ncrust/player/PlayerCard.kt                 CLEAN
```

**唯一受影响的引用**是 `LibraryManager.kt` 的行号（`306 → 434`、`358 → 483`，因为并发改动把它改长了）。
本报告的正文**没有**引用 `LibraryManager.kt`（`rg -n 'LibraryManager' 本文件` → `EXIT=1`），
所以**报告内所有 `file:line` 引用在探针时点与当前工作区都成立**。

> 落地实现前请先确认工作区已回到干净状态，否则 `LibraryPlaylistsTab.kt` 的行号可能与本报告不一致。

---

## 1. 结论先行

| # | 问题 | 结论 | 关键证据 |
|---|---|---|---|
| 1 | 仓库今天有没有任何「折叠/展开」概念？ | **确无「区块折叠」概念。** 全部命中都是**播放器卡片折叠**、**下拉菜单 `expanded`**、**折叠屏机型**三件事；`animateContentSize` / `expandVertically` / `shrinkVertically` 在 Kotlin 源码里 **0 命中**（`EXIT=1`，stderr 0 字节） | §3；`LibraryPlaylistsTab.kt` 全文无折叠状态 |
| 2 | 三个区块各含哪些 `item(key=…)`？ | 本地 4 类 key、ncm 6 类 key、QQ 6 类 key；**ncm 与 QQ 的开头各有一个 `when` 状态块**（同一时刻只渲染其中一个）。**QQ 的区块标题不是 `SectionHeader`，是一段内联 `Row`** | §4；`LibraryPlaylistsTab.kt:157/165/176`、`:189/194/201/212/217/223`、`:235/260/274/283/288/297` |
| 3 | 折叠单位该是「按源」还是「按歌单」？ | **按源（每区块一个布尔）**。文件的 KDoc 明确写「按音源分区，三块，各自独立、不合并」；且 QQ 条目是整行、本地/ncm 是栅格单元，按歌单折叠无法统一 | `LibraryPlaylistsTab.kt:62-70`、`:78-84`、`:297` |
| 4 | 持久化放哪？ | **`ncrust_settings`**，与全仓库所有显示偏好同文件；键命名 `snake_case`；**没有**任何生产代码写过 `Set<String>`，**有** CSV 先例（`artist_reco_anchor_ids`）；`ncrust_settings` **从未被 `edit().clear()`** | `ThemeManager.kt:38`、`LanguageManager.kt:25`、`ArtistReco.kt:44/84/87/99`、§5 |
| 5 | 折叠与 `LazyVerticalGrid` 的关系？ | `contentPadding` 与条目数**无关**（只由托盘高度派生），折叠**不影响**它；但 `animateItem` 只挂在**本地**条目的 2 处，ncm/QQ 条目**没有**，折叠时会出现「本地淡出、另两段硬跳」的不一致 | `LibraryPlaylistsTab.kt:153/167/179` vs `:223/:297`；`BottomOverlayInset.kt:27-30` |
| 6 | 动效该用什么？ | **`Modifier.animateItem(...)`，逐字复用本文件既有的 `150/220/120 + MetroDefault`**；`AnimatedVisibility` 在本仓库**只有 2 处且都是「只入场、从不切回」的级联**，**没有可重复 toggle 的先例** | `LibraryPlaylistsTab.kt:167-171`、`DetailScaffold.kt:147-154`、`AboutScreen.kt:163-171`；`UwpEasing.kt:115` |
| 7 | 折叠 QQ 区块要不要停掉它的网络加载？ | **不要。** 仓库的闸门是**「tab 级」而不是「区块级」**：只有 `selectedCategory == 1` 时才 `loadPlaylists()`，QQ 的加载在 tab 组合时才起 | `LibraryScreen.kt:155-161`、`:268`、`:350-366`；`LibraryPlaylistsTab.kt:115-139` |
| 8 | 折叠指示器怎么写、文案放哪？ | 复用 `SectionHint` 的**几何与字色**（`onSurfaceVariant` + `bodySmall`），但必须**新增**文案属性；新文案进 **`PlaylistsStrings`**（当前 **17** 个参数，预算 150/120）——**绝不能**加外层 `Strings`（钉死 **135**） | `LibraryPlaylistsTab.kt:314-318/322-326`；`StringsConstructorBudgetTest.kt:73/84/177-187`；`Strings.kt:863-898` |
| 9 | 播放器有可复用的「折叠」先例吗？ | **有形态、但明确不可复用**：`controlsCollapse` 是 `Animatable` + 手势 + 阈值判定，且注释写着「**收起状态不持久化**」——与 P2 的「持久化」要求**方向相反** | `PlayerCard.kt:323-340`（尤其 `:335`）、`PlayerDragSnap.kt:82-104` |
| 10 | 任务书前提有错吗？ | **有 4 处**，见 §11.3：`BottomOverlayInsetDp` 是 **168/88dp**（不是 144/64）、QQ 区块标题不是 `SectionHeader`、`AppMotion` 的「零重组」约束**不只**针对播放器卡片、「折叠/展开」的 150 条英文命中里 `foldstate`/`foldcrossfade` 是 `DetailScaffold` 的**假阳性** | `TrayLayout.kt:75/148/151/159-165`、`AGENTS.md:450` vs `BottomOverlayInset.kt:12-13` |

---

## 2. 探针方法

### 2.1 环境自证

```console
$ pwd
/home/duanjb666/deepseek
$ git -C /home/duanjb666/deepseek/ncrust-gpl/Ncrust rev-parse --short HEAD
7a4e33b
$ git -C /home/duanjb666/deepseek/ncrust-gpl/Ncrust status --porcelain | head -20
（空）
$ git -C /home/duanjb666/deepseek/ncrust-gpl/Ncrust log --oneline -3
7a4e33b docs(v2.5.6): 回填设备验证结果与发布物信息（含未完成的平板复测）
4120da2 docs(v2.5.6): 用户可读的 release 说明（含诚实声明与未验证缺口）
fb2aef1 build: 升级至 v2.5.6-gpl（versionCode 47）
```

### 2.2 检索脚本（**stdout / stderr / exit code 三者分开落盘**）

```bash
cd /home/duanjb666/deepseek/ncrust-gpl/Ncrust && out=/tmp/probe-out; rm -rf $out; mkdir -p $out
run() { name="$1"; shift; rg "$@" >"$out/$name.out" 2>"$out/$name.err"; echo "$?" >"$out/$name.code"; }
for p in expanded collapsed AnimatedVisibility animateContentSize expandVertically shrinkVertically; do
  run "kt_$p" -n -F "$p" app/src --glob '*.kt'
done
for p in 折叠 收起 展开; do run "kt_$p" -n -F "$p" app/src --glob '*.kt'; done
# 正向对照（同 scope 内必然命中）
run "ctl_animateItem"  -n -F "animateItem"  app/src --glob '*.kt'
run "ctl_MetroDefault" -n -F "MetroDefault" app/src --glob '*.kt'
# 第二条独立路径：英文标识符（大小写无关）
run "kt_i_foldcollaps" -ni 'fold|collaps' app/src --glob '*.kt'
# 第三条路径：文档层
run "docs_LazyVerticalGrid" -n -F "LazyVerticalGrid" docs TASK.md AGENTS.md
run "ctl_docs_expanded"     -n -F "expanded" docs TASK.md AGENTS.md
```

### 2.3 执行结果（真实输出，未经改写）

```console
=== ctl_animateItem          EXIT=0  stdout_lines=5    stderr_bytes=0
=== ctl_docs_expanded        EXIT=0  stdout_lines=41   stderr_bytes=0
=== ctl_MetroDefault         EXIT=0  stdout_lines=23   stderr_bytes=0
=== docs_LazyVerticalGrid    EXIT=1  stdout_lines=0    stderr_bytes=0
=== kt_收起                  EXIT=0  stdout_lines=108  stderr_bytes=0
=== kt_展开                  EXIT=0  stdout_lines=113  stderr_bytes=0
=== kt_折叠                  EXIT=0  stdout_lines=39   stderr_bytes=0
=== kt_animateContentSize    EXIT=1  stdout_lines=0    stderr_bytes=0
=== kt_AnimatedVisibility    EXIT=0  stdout_lines=5    stderr_bytes=0
=== kt_collapsed             EXIT=0  stdout_lines=33   stderr_bytes=0
=== kt_expanded              EXIT=0  stdout_lines=36   stderr_bytes=0
=== kt_expandVertically      EXIT=1  stdout_lines=0    stderr_bytes=0
=== kt_i_foldcollaps         EXIT=0  stdout_lines=150  stderr_bytes=0
=== kt_shrinkVertically      EXIT=1  stdout_lines=0    stderr_bytes=0
```

### 2.4 两条元规则（本轮踩到的坑，写给下一个探针）

1. **不要把 `rg` 接进 `| head` 之后再读 `$?`** —— `$?` 会变成 `head` 的退出码，永远 0。本轮第一次跑 `LazyVerticalGrid` 就是这样拿到假的 `EXIT=0`，重跑（不接管道）才是真值 `EXIT=1`。
2. **大小写无关的英文子串检索会产生假阳性**：`-i 'fold|collaps'` 在 150 行结果里，`foldstate`(7) 全部来自 `Detail**Scaffold**State`、`foldcrossfade`(1) 来自 `DetailScaffold**Cross**fade`。见 §3.3。

---

## 3. 既有折叠概念检索结果（逐条命中 / 确无 + 多路径交叉验证）

### 3.1 `expanded` —— 36 行命中，**全部与区块折叠无关**（读码 + 命令）

| 命中族 | 文件:行 | 它实际是什么 |
|---|---|---|
| 播放器展开态（跨屏回传） | `MainActivity.kt:729`、`:732`、`:735`、`:590` | `playerExpanded` —— 卡片是否全屏，用于「自动进大屏」判定 |
| 播放器展开态子树挂载 | `PlayerCard.kt:303`、`:707` | `expandedMounted = progress.value > 0.01f`（B-1 命中区契约） |
| 下拉菜单开合 | `SearchScreen.kt:822`、`UserScreen.kt:1164/1172/1200/1201/1375/1382/1406/1407/1412`、`FullPlayerControls.kt:629/646/658/659/668`、`AlbumSearchItem.kt:91`、`ArtistSearchItem.kt:105`、`EditPlaylistDialog.kt:109`、`CreatePlaylistDialog.kt:127`、`OfflineCacheOverlay.kt:231` | 全部是 Compose `DropdownMenu(expanded = …)` 的**菜单开合**参数，与「区块内容折叠」无关 |
| 播放器手势单测 | `PlayerDragSnapTest.kt:74/80/87/92/119/131` | 用例名里的 `expanded` = 卡片全屏态 |
| Shader 预热注释 | `MainActivity.kt:1496` | `expandedEnough` 阈值 |

**交叉过滤（证「没有一个属于区块/歌单折叠」）**：

```console
$ rg -n -F 'expanded' app/src --glob '*.kt' | rg -v 'PlayerDragSnapTest|MainActivity|UserScreen|FullPlayerControls|OfflineCacheOverlay|AlbumSearchItem|EditPlaylistDialog|ArtistSearchItem|PlayerCard|SearchScreen|CreatePlaylistDialog'
EXIT=1
（无输出）
```

### 3.2 `collapsed` —— 33 行命中，**全部是播放器折叠**（读码 + 命令）

| 命中族 | 文件:行 | 说明 |
|---|---|---|
| 卡片折叠落点 | `MainActivity.kt:890/891/902/2126`、`PlayerCardOverlay.kt:17/48`、`PlayerCard.kt:540` | `collapsedOffsetY` 公式与 `graphicsLayer { translationY }` |
| 折叠态命中区闸门 | `PlayerCard.kt:307/562/674` | `collapsedHitGate` / `hitGateInsetDp`（AGENTS.md 触摸陷阱 §5/§6） |
| 控制栏收起 | `PlayerCard.kt:339` | 卡片回到折叠态时 `controlsCollapse.snapTo(0f)` |
| 详情页死带警告 | `PlaylistDetailScreen.kt:11/12/243/244`、`DetailScaffold.kt:71` | 折叠态卡片的 `fillMaxSize` 死带 |
| 单测/文档 | `PlayerDragSnapTest.kt:30/36/42/48/54/65/141/147/153`、`TrayLayout.kt:23/25/97/140`、`AGENTS.md:180/253/256/473-476/493-499/507/515/517/934` | 全部是同一件事 |

**结论：0 条与「区块折叠」有关。**

### 3.3 中文词三条 + 英文标识符一条

| 检索 | 命中行数 | 命中族（逐条核对后） | 属于区块折叠？ |
|---|---|---|---|
| `折叠` | 39 | 折叠屏机型（`BigScreenOrientation.kt:59`、`ResponsiveContent.kt:15`、`PlayerLayout.kt:22`、`MainActivity.kt:202/428/537/540/873`）、播放器折叠态（`PlayerCard.kt` 十余处）、折叠态命中区（`AppMotion.kt:122`） | **0** |
| `收起` | 108 | 播放器控制栏收起（`PlayerCard.kt:323-403`）、卡片收起（`PlayerDragSnap.kt`）、面板抽屉收起（`AppMotion.kt:206-210`）、`AppSnackbar.kt:94/150`、抽屉可下滑收起（`SongMenuSheet.kt:45`、`AddToPlaylistSheet.kt:84`） | **0** |
| `展开` | 113 | 播放器展开、ComboBox 双向展开（`UserScreen.kt:1198`）、TTML 节点展开（`TtmlScanner.kt:236`）、CAM16 直接展开（`Cam16.kt:177`）、渐变带展开（`NcrustLyricsPanel.kt:642`） | **0** |

**交叉过滤（中文）**：

```console
$ rg -n -F '折叠' app/src --glob '*.kt' | rg '歌单|区块|section|Section'
EXIT=1
（无输出）
```

**第二条独立路径（英文标识符，大小写无关）** —— 词频分类：

```console
$ rg -oi 'fold\w*|collaps\w*' app/src --glob '*.kt' | sed 's/^[^:]*://' | tr 'A-Z' 'a-z' | sort | uniq -c | sort -rn
     27 collapse
     24 fold
     22 collapsedoffsety
     18 foldersize
     16 collapsed
     12 collapseplayer
      7 foldstate
      7 collapsecard
      6 collapses
      5 folderitem
      4 collapse_fraction
      4 collapsedrag
      4 collapsedhitgate
      3 collapsiblecontrols
      2 folder
      2 collapsibleheight
      2 collapsedforinput
      1 folder_mixed
      1 foldcrossfade
      1 collapsetoonechoice
```

逐族判定：

- `foldstate`(7) / `foldcrossfade`(1)：**假阳性** —— 它们是 `DetailScaffoldState`（`DetailScaffold.kt:101/102/103/112/117/140/208`）与 `DetailScaffoldCrossfade`（`:109`）里的子串。
- `foldersize`(18) / `folderitem`(5) / `folder`(2) / `folder_mixed`(1)：**「文件夹」不是「折叠」** —— 属于本地歌单的文件夹式条目模型。
- `collapse*` / `collapsed*` / `collapsible*`（27+16+6+22+12+7+4+4+4+3+2+2+1 = 110）：**全部是播放器**（`collapsedOffsetY` / `collapsedHitGate` / `ControlsDragSnap` / `collapsibleHeight` / `collapsePlayer`）。
- 唯一的 `fold`(24) 独立命中是 Kotlin 标准库 `fold`（`QueueKeysTest.kt:125` 注释「用 fold 而不是 runningFold」）。

### 3.4 动画原语：`AnimatedVisibility` 只有 2 处生产调用点，另三个 **0 命中**

```console
$ rg -n -F 'AnimatedVisibility' app/src --glob '*.kt'          # EXIT=0, 5 行
app/src/main/java/com/takahashirinta/ncrust/ui/components/DetailScaffold.kt:4:   import androidx.compose.animation.AnimatedVisibility
app/src/main/java/com/takahashirinta/ncrust/ui/components/DetailScaffold.kt:147:     AnimatedVisibility(
app/src/main/java/com/takahashirinta/ncrust/ui/screen/AboutScreen.kt:16:         import androidx.compose.animation.AnimatedVisibility
app/src/main/java/com/takahashirinta/ncrust/ui/screen/AboutScreen.kt:154:         // 关于页专用的级联入场包装：每块内容独立 MutableTransitionState + AnimatedVisibility，
app/src/main/java/com/takahashirinta/ncrust/ui/screen/AboutScreen.kt:163:         AnimatedVisibility(
```

| 原语 | `EXIT` | stdout 行数 | stderr 字节 | 判定 |
|---|---|---|---|---|
| `animateContentSize` | **1** | 0 | 0 | **确无** |
| `expandVertically` | **1** | 0 | 0 | **确无** |
| `shrinkVertically` | **1** | 0 | 0 | **确无** |
| `animateItem`（正向对照） | 0 | 5 | 0 | 有（见 §7） |
| `MetroDefault`（正向对照） | 0 | 23 | 0 | 有 |

### 3.5 文档层第三条路径

```console
$ rg -n -F 'LazyVerticalGrid' docs TASK.md AGENTS.md    # EXIT=1, stdout 0 行, stderr 0 字节
$ rg -n -F 'expanded' docs TASK.md AGENTS.md             # EXIT=0, 41 行（正向对照成立）
```

→ **连文档/任务书层面都没有讨论过这个网格的折叠**（41 行 `expanded` 对照证明检索本身没失效）。

### 3.6 结论

> **「区块折叠」在本仓库是 0 先例。** 三条独立路径（中文词分类 / 英文标识符分类 / 文档层）一致；
> 三条「0 命中」的原语检索均带 `EXIT=1` + stderr 0 字节 + 同 scope 正向对照。
> 与之最接近的既有物是**播放器的折叠态**（坐标系、命中区、手势），但它**明确不持久化**（§9）。

---

## 4. 三个区块的 item 清单（key → 归属区块 → 折叠时应隐藏与否）

`LibraryPlaylistsTab.kt` 里整页是**一个** `LazyVerticalGrid`（`:148-155`）：

```kotlin
148:     LazyVerticalGrid(
149:         columns = GridCells.Adaptive(minSize = 160.dp),
150:         modifier = Modifier.fillMaxSize(),
151:         horizontalArrangement = Arrangement.spacedBy(2.dp),
152:         verticalArrangement = Arrangement.spacedBy(2.dp),
153:         contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
154:         flingBehavior = rememberMetroFlingBehavior(),
155:     ) {
```

**注意：`LazyVerticalGrid` 没有传 `state =`** —— 网格状态没有上提（没有 `rememberLazyGridState()`），
所以仓库里**没有任何一处**能读/写这个网格的滚动位置。

### 4.1 区块 A：本地歌单（`localPlaylists: List<LocalPlaylist>`）

| # | `item(key = …)` | 行 | 出现条件 | span | 折叠时 |
|---|---|---|---|---|---|
| A1 | `"hdr-local"` | `:157` | 恒 | `GridItemSpan(maxLineSpan)` | **保留**（折叠开关的宿主） |
| A2 | `"local-empty"` | `:161` | `localPlaylists.isEmpty()` | `maxLineSpan` | **隐藏** |
| A3 | `"local-create"` | `:165` | 恒（「＋新建」格） | 默认（1 格） | **隐藏** |
| A4 | `"local-" + it.key.tag` | `:176` | 每个本地歌单 | 默认（1 格） | **隐藏** |

- `it.key.tag` 的形状是 `PlaylistKey.tag = source.key + ":" + ownerId + ":" + id`（`PlaylistModels.kt:44-60`），
  所以实际 key 形如 `local-qqmusic:12345:67890`、`local:netease:…`。
- **A2/A3/A4 是本区块的全部内容项**；A1 是标题。折叠实现 = `if (!collapsed) { A2; A3; A4… }`。
- 本地区块**永远至少有 1 项内容**（A3 无条件渲染），所以「折叠后区块空掉」这种情况不存在。

### 4.2 区块 B：ncm（`neteasePlaylists: List<PlaylistApi.PlaylistInfo>`）

| # | `item(key = …)` | 行 | 出现条件 | span | 折叠时 |
|---|---|---|---|---|---|
| B0 | `"hdr-netease"` | `:189` | 恒（`SectionHeader(strings.sourceNetease, …)` `:190`） | `maxLineSpan` | **保留** |
| B1 | `"ne-loading"` | `:194` | `isLoadingNetease && neteasePlaylists.isEmpty()` | `maxLineSpan` | **隐藏** |
| B2 | `"ne-error"` | `:201` | `neteaseError != null && neteasePlaylists.isEmpty()` | `maxLineSpan` | **隐藏** |
| B3 | `"ne-empty"` | `:212` | `neteasePlaylists.isEmpty()` | `maxLineSpan` | **隐藏** |
| B4 | `"ne-create"` | `:217` | `else`（列表非空） | 默认（1 格） | **隐藏** |
| B5 | `"ne-" + it.id` | `:223` | `else`，每个歌单 | 默认（1 格） | **隐藏** |

- `it.id` 是 `PlaylistInfo.id: Long`（`PlaylistApi.kt:280`）。
- **B1/B2/B3 是一个 `when` 的互斥分支，B4/B5 是同一个 `else` 分支**（`:192-232`）：
  同一时刻只会有 `hdr-netease` + 其中一个状态项 / 或 `ne-create` + N 个 `ne-<id>`。
  所以折叠的落点是**整个 `when` 块**，不是逐分支判断。

### 4.3 区块 C：qm（`qqOrdered`，`:141-145` 派生）

| # | `item(key = …)` | 行 | 出现条件 | span | 折叠时 |
|---|---|---|---|---|---|
| C0 | `"hdr-qq"` | `:235` | 恒 | `maxLineSpan` | **保留** |
| C1 | `"qq-degraded"` | `:260` | `qqDegradation != null` | `maxLineSpan` | **隐藏** |
| C2 | `"qq-loading"` | `:274` | `!qqHasLoadedOnce && qqLoading` | `maxLineSpan` | **隐藏** |
| C3 | `"qq-need-login"` | `:283` | `qqNeedLogin` | `maxLineSpan` | **隐藏** |
| C4 | `"qq-empty"` | `:288` | `qqOrdered.isEmpty()` | `maxLineSpan` | **隐藏** |
| C5 | `"qq-" + it.key.tag` | `:297` | `else`，每个歌单 | `maxLineSpan`（整行） | **隐藏** |

- C1 是**独立于 `when` 的**（`:259-271`，`qqDegradation?.let { … }`），折叠实现必须把它一起关掉 ——
  否则折叠后最上面仍挂一行「离线模式：下面显示的是本地缓存」，而下面什么都没有。
- C3 用 `strings.playlistLoginRequired`、C4 用 `playlistEmpty` 或 `playlistLoadFailed`（`:289-293`）。

### 4.4 ⚠️ 区块标题不是同一个 composable（任务书前提错误之一）

| 区块 | 标题实现 | 行 |
|---|---|---|
| 本地歌单 | `SectionHeader(strings.localPlaylistSectionTitle, …)` | `:158` |
| ncm | `SectionHeader(strings.sourceNetease, …)` | `:190` |
| **qm** | **内联 `Row`**（`MetroText(strings.sourceQqMusic, …)` + `Spacer(weight)` + 刷新 `Box`） | **`:235-258`** |

`SectionHeader` 只有 2 个调用点。**把折叠开关做进 `SectionHeader` 会漏掉 QQ 区块** ——
QQ 标题必须单独改（它已经有一个可点区域：刷新按钮 `:245-256`，折叠开关要避免与它抢命中区，
见 `AGENTS.md` 触摸陷阱 §7「命中区只增不减」）。

### 4.5 三个区块的私有 composable 画像（读码）

```kotlin
312: /** 区块标题。整行 span，左对齐，与库页的 Groove 风格一致。 */
313: @Composable
314: private fun SectionHeader(text: String, colors: MetroColors, typography: MetroTypography) {
315:     Box(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
316:         MetroText(text, color = colors.onSurfaceVariant, style = typography.caption)
317:     }
318: }
319:
320: /** 区块内的说明 / 空状态。**不是**居中大空态 —— 它只是这一块没有内容，别的块还有。 */
321: @Composable
322: private fun SectionHint(text: String, colors: MetroColors, typography: MetroTypography) {
323:     Box(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp)) {
324:         MetroText(text, color = colors.onSurfaceVariant, style = typography.bodySmall)
325:     }
326: }
```

| | `SectionHeader` | `SectionHint` |
|---|---|---|
| 字色 | `colors.onSurfaceVariant` | `colors.onSurfaceVariant`（同） |
| 字级 | `typography.caption` | `typography.bodySmall` |
| padding | `start=16, end=16, top=14, bottom=6` | `start=16, end=16, top=2, bottom=8` |
| 语义 | 区块标题（整行 span） | 「这一块没有内容」/降级提示 |
| 可点击 | 否 | 否 |

**两者都不可点击** —— 折叠指示器不能直接复用其中任何一个（需要 `clickable` + 语义）。

---

## 5. 折叠单位与持久化方案

### 5.1 折叠单位 = 按源（读码，仓库有明确理由）

`LibraryPlaylistsTab.kt:59-85` 的 KDoc：

```kotlin
62:  * ## 信息架构（任务书 3.1 / 3.2）
63:  *
64:  * **按音源分区，三块，各自独立、不合并**：
65:  *
66:  * | 区块 | 内容 | 来源 |
67:  * |---|---|---|
68:  * | 本地歌单 | 可编辑、可混装音源、只加不减 + tombstone | 本地 prefs |
69:  * | ncm | 新建入口 + 云端歌单网格 | `PlaylistApi.getUserPlaylists` |
70:  * | qm | **歌单列表直接平铺** | `QqPlaylistRepository.loadList` |
```

```kotlin
78:  * ## 为什么整页是**一个** `LazyVerticalGrid`
79:  *
80:  * 三个区块的高度都不知道（本地歌单数量本地可变、两个远程源各自可能为空/加载中/报错），
81:  * 而「`LazyColumn` 里嵌 `LazyVerticalGrid`」在 Compose 里是非法的（同向嵌套滚动 +
82:  * 无限高度约束）。所以用**一个**网格：区块标题与 QQ 歌单行都用 `GridItemSpan(maxLineSpan)`
83:  * 占满整行 —— 三种不同宽度的内容共存在同一个滚动容器里，滚动 / fling / `contentPadding`
84:  * 都只有一份。
```

由此得到三条**从代码得出的**理由，支持「按源折叠」而不是「按歌单折叠」：

1. **分区是既定的信息架构**（`:64` 黑体「按音源分区，三块，各自独立、不合并」）——
   折叠是分区的自然粒度。
2. **粒度不匹配**：本地/ncm 条目是**栅格单元**（默认 span=1），QQ 条目是**整行**（`span = maxLineSpan`，`:297`）。
   「按歌单折叠」在栅格区会一次只藏 1 格，视觉上等于没有折叠。
3. **状态量爆炸**：按歌单折叠需要为每个歌单持久化一个 key（本地 key 含 `ownerId`，`:176` + `PlaylistModels.kt:44-60`），
   而仓库没有任何「按条目 id 存显示偏好」的先例（§5.3）。

### 5.2 三个数据源与量级

| 区块 | 数据源 | 行 | 上界（证据） | 可能为 0 |
|---|---|---|---|---|
| 本地 | `localPlaylists: List<LocalPlaylist>`（参数，由 `LibraryScreen` 从 `LocalPlaylistStore.readPlaylists` 提供） | 参数 `:88`；`LibraryScreen.kt:122` | **≤ 100**：`LocalPlaylistSync.MAX_PLAYLISTS = 100`（`LocalPlaylistModels.kt:319`，超出时按 `updatedAt` 降序截断，`LocalPlaylistStore.kt:76-87`） | 是（但「＋新建」格恒在） |
| ncm | `neteasePlaylists: List<PlaylistApi.PlaylistInfo>`（参数） | 参数 `:89` | **≤ 100**：`getUserPlaylists(uid, limit = 100, offset = 0)`（`PlaylistApi.kt:80`）；调用点只传 uid（`LibraryScreen.kt:135`），**不翻页**；且 `specialType != 0` 被过滤掉（`:98`） | 是 |
| QQ | **页内自己加载**：`qqPlaylists` + `qqOrdered` | `:107`、`:115-139`、`:141-145` | 自建 + 收藏合并（`QqPlaylistRepository.kt:111-118`）；收藏单页 `size = 50`（`QqPlaylistApi.kt:96-107`）；**无硬上限** | 是 |

`qqOrdered` 是**排序后的派生列表**（`:141-145`）：

```kotlin
141:     val qqOrdered = remember(qqPlaylists) {
142:         qqPlaylists.filter { it.isFavorite } +
143:             qqPlaylists.filter { it.isOwned && !it.isFavorite } +
144:             qqPlaylists.filter { !it.isOwned }
145:     }
```

→ **折叠后要显示的 N 必须取自「当前实际渲染的那个列表」**：本地 = `localPlaylists.size`、
ncm = `neteasePlaylists.size`、QQ = `qqOrdered.size`（**不是** `qqPlaylists.size`）。

### 5.3 持久化：`ncrust_settings` 的既有形状

| 项 | 事实 | 证据 |
|---|---|---|
| prefs 文件名 | `"ncrust_settings"`（主题、语言、播放、歌词显示、页面转场、音乐人推荐、离线上限全都在这一个文件） | `ThemeManager.kt:38`、`LanguageManager.kt:25`、`LyricsDisplayPrefs.kt:65`、`PageTransitionSetting.kt:65`、`ArtistReco.kt:40` |
| 键命名约定 | 全小写 `snake_case`，带功能前缀 | `theme_color_index` / `theme_mode`（`ThemeManager.kt:39-40`）、`language_code`（`LanguageManager.kt:26`）、`lyrics_ttml_enabled`（`LyricsDisplayPrefs.kt:80`）、`page_transition_enabled`（`PageTransitionSetting.kt:71`）、`artist_reco_anchor_ids`（`ArtistReco.kt:44`） |
| 默认值处理 | 三档：**键不存在 → 默认**；显式值 → 原样；**脏键（类型不符）→ 回落默认且不抛** | `PageTransitionSetting.readStored`（`PageTransitionSetting.kt:90-92`）用 `runCatching{…}.getOrNull()` 把「缺失」与「脏键」都折成 `null`；`resolveEnabled(stored) = stored ?: DEFAULT_ENABLED`（`:82`）。歌词侧同源做法：`readBooleanSafely`（`LyricsDisplayPrefs.kt:201-202`） |
| Int 非法值处理 | 越界一律 `normalize()` 回落默认 | `LyricsWordAnimationMode.normalize`（`LyricsDisplayPrefs.kt:34`）、`LyricsSweepQuality.normalize`（`:55`） |
| **Set/CSV-of-strings 先例** | **生产代码里 `putStringSet`/`getStringSet` 0 命中**（只在测试的 `FakePrefs` 里实现过）；**CSV 先例存在**：`artist_reco_anchor_ids`（CSV，`ArtistReco.kt:44/84/87/99`：`split(',', ';', ' ')` + `joinToString(",")`） | `rg -n 'putStringSet\|getStringSet' app/src --glob '*.kt'` → 仅 `PageTransitionSettingTest.kt:165/206`、`LyricsSourcePrefsTest.kt:97/138`（都是测试替身） |
| **boolean-per-section 先例** | **存在**：同族的四个显示布尔各自一个键、各自独立读写 —— `lyrics_ttml_enabled` / `lyrics_ttml_first` / `lyrics_romanization` / `lyrics_dynamic_font` | `LyricsDisplayPrefs.kt:80-83/91/98`，读写在 `:166-199` |
| **「若干相关标志一起存」的先例** | **存在，但不是原子的**：`LyricsDisplayPrefs` 是一个 object 管 9 个键（`:68-114`）；`ArtistReco` 一个 object 管 5 个键（`:42-46`）。**唯一**的「一次 `edit()` 写多键」是会员状态（`NeteaseVipStore.kt:102-106`，3 个 `put*` 串在一个编辑事务里） | — |
| `ncrust_settings` 会被整体清掉吗？ | **不会。** 生产代码里 `edit().clear()` 只有 4 处，全部针对**别的**文件：`ncrust_match_cache`、`ncrust_qq_playlists`、`ncrust_playback_state`、`ncrust_netease_vip` | `MatchCacheStore.kt:45/161`、`QqPlaylistStore.kt:52/164`、`PlaybackStateManager.kt:30/109`、`NeteaseVipStore.kt:62/113` |
| 多进程覆写风险 | **无**：`AndroidManifest.xml` 里没有 `android:process`（`rg -n 'android:process' app/src/main/AndroidManifest.xml` → `EXIT=1`），全应用单进程 | — |
| 设置页「清除缓存」会连带清设置吗？ | **不会**：它清 `ContentCache` / Coil 内存与磁盘缓存 / `cacheDir` 子项 / 离线音频，**不碰 SharedPreferences** | `UserScreen.kt:281-300` |

### 5.4 落地方案（推荐）

**文件**：`ncrust_settings`（既有文件，不新建）。

**键（推荐 3 个独立布尔，逐条对齐 `LyricsDisplayPrefs` 的四布尔先例）**：

| 键 | 类型 | 默认 | 语义 |
|---|---|---|---|
| `library_section_collapsed_local` | Boolean | `false` | 本地歌单区块是否折叠 |
| `library_section_collapsed_netease` | Boolean | `false` | ncm 区块是否折叠 |
| `library_section_collapsed_qq` | Boolean | `false` | qm 区块是否折叠 |

默认 `false` = **展开**，这同时满足「绝不自动折叠」与「键不存在 = 老用户升级后行为零变化」。

**为什么不用 CSV / `Set<String>`**：
- `Set<String>` 在生产代码里**零先例**（§5.3），引入它等于新造一种存法；
- CSV 先例只有 `artist_reco_anchor_ids` 一处，且它是「数量可变、会增删」的集合；本特性键集固定为 3 个，
  用 CSV 反而要自己写解析与非法值处理（多一处可测逻辑、多一处可能出错的地方）。

**代码形状**：照抄 `PageTransitionSetting`（`PageTransitionSetting.kt:63-125`）——
一个 `object`，内含 `PREFS` / `KEY_*` 具名常量 / `DEFAULT_*` / **纯函数** `resolve*` / `readStored`（缺失与脏键都返回 `null`）/ `read*` / `write*` / `Context` 便捷重载。
`PageTransitionSetting.kt:29-38` 明确解释了**为什么要抽 object 而不是在设置页里 `prefs.edit()…`**：
「键名与默认值是具名常量，可以被单测断言」「『键不存在』这一支有独立的纯函数可测」「脏键回落路径也能测」。

建议新增文件：`app/src/main/java/com/takahashirinta/ncrust/ui/screen/LibrarySectionFoldSetting.kt`
（放 `ui/screen/` 的理由：它只服务这个屏；同目录已有 `CacheUsage.kt` + 同目录测试 `CacheUsageTest.kt` 的成对先例）。

**单测落点**：`app/src/test/java/com/takahashirinta/ncrust/ui/screen/LibrarySectionFoldSettingTest.kt`
（目录已存在，内含 `CacheUsageTest.kt`；`FakePrefs` 直接借 `PageTransitionSettingTest.kt:150-250` 的实现思路）。
必须覆盖的用例（对照 `PageTransitionSettingTest` 的 10 条）：

| 用例 | 断言 |
|---|---|
| 键名是持久化契约 | 三个键名字面量逐个断言（改名 = 静默重置所有用户选择，`PageTransitionSetting.kt:68-71`） |
| 默认是展开 | 空 prefs ⇒ 三个都 `false`（**「绝不自动折叠」的可执行证明**） |
| 写盘后能读回 | `write(true)` → `read == true`；`write(false)` → `read == false` |
| 迁移只补默认、不覆盖用户选择 | 已显式 `false` 的键在读取时**不得**被改写成别的值 |
| 脏键回落 | 键里存 `String` ⇒ `read == false` 且不抛（真机上 `getBoolean` 会抛 `ClassCastException`，`PageTransitionSetting.kt:36-38`） |
| 脏键自愈 | 脏键之后写入合法值即可恢复，不需要清数据（`PageTransitionSettingTest.kt:109` 同款） |

---

## 6. 折叠时显示什么（文案与样式，含既有 `SectionHint` 对照）

### 6.1 新增一个私有 composable（不复用 `SectionHint`，因为要可点击）

建议形状（几何/字色**逐值沿用** `SectionHint`，`LibraryPlaylistsTab.kt:322-326`）：

```kotlin
/**
 * 区块折叠后的指示行。可点击展开。
 *
 * 几何与字色**逐值沿用 [SectionHint]**（同为「这一块现在没有铺开的内容」的语义），
 * 只多一个 clickable —— 所以它与上面的空态/降级提示在视觉上是同一族，
 * 用户不会把它读成「另一种东西」。
 */
@Composable
private fun SectionFoldIndicator(
    text: String,
    colors: MetroColors,
    typography: MetroTypography,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
    ) {
        MetroText(text, color = colors.onSurfaceVariant, style = typography.bodySmall)
    }
}
```

- padding 与 `SectionHint` **完全相同**（`start=16, end=16, top=2, bottom=8`），所以折叠前后那一行的
  垂直节奏不变，展开/折叠不会让下面的区块「跳一下」。
- 可点击区域 = 整行（≥ 16dp 高的文字行 + 上下 10dp），满足 `AGENTS.md` 触摸陷阱 §7「命中区只增不减」。
- 字号用 `bodySmall` 而不是 `caption`：`caption` 是**标题**的字级（`SectionHeader:316`），
  指示器是**内容占位**，与 `SectionHint` 同级。

### 6.2 折叠行的文案

| 区块 | 折叠行文案（zh-CN） | N 的取值 |
|---|---|---|
| 本地歌单 | 「展开全部 12 个」 | `localPlaylists.size` |
| ncm | 「展开全部 37 个」 | `neteasePlaylists.size` |
| qm | 「展开全部 8 个」 | `qqOrdered.size`（`:141-145`） |

**边界处理（建议写进实现注释）**：

- 区块内容项**为 0 时也要能展开**（ncm/QQ 的空态、加载态、错误态都是「内容项」）。
  此时折叠行显示「展开全部 0 个」并不友好 —— 建议：**只有该区块当前有成规模的内容（N ≥ 1）时才提供折叠开关**，
  否则标题右侧不显示折叠入口。这样「展开全部 0 个」永远不会出现，而空态/错误态提示仍由
  展开后的 `SectionHint` 承担。
- 本地区块恒有 A3（「＋新建」格），所以 N 恒 ≥ 1。

### 6.3 与既有 `SectionHint` 的对照结论

| 维度 | 既有 `SectionHint` | 建议的折叠指示器 |
|---|---|---|
| 字色 | `colors.onSurfaceVariant` | **同** |
| 字级 | `typography.bodySmall` | **同** |
| padding | `16 / 2 / 16 / 8` | **同** |
| span | 由调用点给 `maxLineSpan` | 同（`:161/212/288` 的既有做法） |
| 可点击 | ❌ | ✅（唯一差别） |
| 语义 | 「这一块没有内容」 | 「这一块被用户收起了，点这里摊开」 |

---

## 7. 动效方案（与仓库既有 `AnimatedVisibility` 用法逐字对齐）

### 7.1 仓库既有的 `AnimatedVisibility` 全部用法（2 处生产调用点，逐字引用）

**（1）`DetailScaffold.kt:140-165`** —— 内容分支的入场级联：

```kotlin
140:                 DetailScaffoldState.Content -> {
141:                     // 入场级联：内容分支从下方 12dp 微滑上 + 淡入。方向与 NavGraph 横向推入正交。
142:                     val density = LocalDensity.current
143:                     val slideOffsetPx = with(density) { 12.dp.roundToPx() }
144:                     val cascadeState = remember {
145:                         MutableTransitionState(false).apply { targetState = true }
146:                     }
147:                     AnimatedVisibility(
148:                         visibleState = cascadeState,
149:                         enter = fadeIn(animationSpec = tween(220, easing = MetroDefault)) +
150:                             slideInVertically(
151:                                 animationSpec = tween(220, easing = MetroDefault),
152:                                 initialOffsetY = { slideOffsetPx }
153:                             )
154:                     ) {
```

规格：`enter = fadeIn(tween(220, MetroDefault)) + slideInVertically(tween(220, MetroDefault), initialOffsetY = 12dp)`；
**没有写 `exit`**（走框架默认）。

**（2）`AboutScreen.kt:154-172`** —— 关于页的级联入场包装（被 5 次调用：`:79/98/111/124/134`）：

```kotlin
154: // 关于页专用的级联入场包装：每块内容独立 MutableTransitionState + AnimatedVisibility，
155: // 通过 delayMillis 拉开时序。首块传 0 即立即出现，符合"首元素 0ms"约束。
156: @Composable
157: private fun CascadeBlock(delayMillis: Int, content: @Composable () -> Unit) {
158:     val density = LocalDensity.current
159:     val slideOffsetPx = with(density) { 8.dp.roundToPx() }
160:     val visibleState = remember {
161:         MutableTransitionState(false).apply { targetState = true }
162:     }
163:     AnimatedVisibility(
164:         visibleState = visibleState,
165:         enter = fadeIn(
166:             animationSpec = tween(200, delayMillis = delayMillis, easing = MetroDefault)
167:         ) + slideInVertically(
168:             animationSpec = tween(200, delayMillis = delayMillis, easing = MetroDefault),
169:             initialOffsetY = { slideOffsetPx }
170:         )
171:     ) { content() }
172: }
```

规格：`enter = fadeIn(tween(200, MetroDefault, delay)) + slideInVertically(tween(200, MetroDefault, delay), initialOffsetY = 8dp)`；
**同样没有写 `exit`**。

**⚠️ 两处的共同点很关键**：都用 `MutableTransitionState(false).apply { targetState = true }` 做
**一次性入场**，`visibleState` **从不切回 `false`**。也就是说：

> **仓库里没有「可重复 toggle 的 `AnimatedVisibility`」先例。**
> `visibleState` 一旦为 `true` 就再没被改过；`exit` 分支从未被执行，两处都靠框架默认。
> 谁把这两处当成「折叠动画模板」照抄，会连 `exit = fadeOut + shrinkOutVertically`（框架默认）
> 一起继承下来 —— 而那正是 `expandVertically`/`shrinkVertically` 在 Kotlin 源码里 0 命中的原因：
> 它们只以**框架默认值**的形式存在，没有被仓库显式写过。

### 7.2 `expandVertically` / `shrinkVertically` / `animateContentSize`：**0 使用**

见 §3.4。因此**不要**为折叠引入 `AnimatedVisibility(enter = expandVertically())`——
那会是仓库里的**新原语**，且与本文件既有的列表动效语言（`animateItem`）不一致。

### 7.3 推荐方案：`Modifier.animateItem(...)`，逐字复用本文件既有取值

折叠的物理行为是**「从列表里移除 / 加回一批带 key 的条目」**，而不是「改变一个 composable 的高度」。
仓库里对「条目出现/消失/位移」的既有手段就是 `animateItem`，**本文件已经在用**：

```kotlin
165:         item(key = "local-create") {
166:             NewPlaylistGridItem(
167:                 modifier = Modifier.fillMaxWidth().animateItem(
168:                     fadeInSpec = tween(150, easing = MetroDefault),
169:                     placementSpec = tween(220, easing = MetroDefault),
170:                     fadeOutSpec = tween(120, easing = MetroDefault),
171:                 ),
```

同款再出现在本地歌单条目上（`:179-183`），以及库页专辑栅格（`LibraryScreen.kt:385`）。
另有一处**故意不同**的用法在队列拖拽里（`QueueView.kt:402-408`，`placementSpec = tween(0)`，
注释解释「若落定帧切回 spring，`animateItem` 会把整轮重排再播一遍」）——说明这个 API 在本仓库
是**被理解过、并按场景调过参**的。

**推荐规格（逐字对齐 `LibraryPlaylistsTab.kt:167-171`）**：

```kotlin
// 折叠 / 展开：条目从列表里成批移除或加回。
// 逐值沿用本文件「新建」格与本地歌单格已有的 animateItem 取值 —— 不新造参数。
private fun Modifier.sectionFoldItemAnimation(): Modifier = animateItem(
    fadeInSpec  = tween(150, easing = MetroDefault),   // 展开：条目淡入
    placementSpec = tween(220, easing = MetroDefault), // 余下条目滑到新位置
    fadeOutSpec = tween(120, easing = MetroDefault),   // 折叠：条目淡出（比入场更短促）
)
```

`MetroDefault` 的确切语义（Kanesumi，`Kanesumi-sec-a/kanesumi-anim/.../UwpEasing.kt:115`）：

```kotlin
115: val MetroDefault: Easing = uwpEasing(UwpEasing.Quadratic, EasingMode.EaseOut)
```

即 **easeOutQuad**：`1 - (1-t)²`（`UwpEasing.kt:37` + `:97-101`）。

**必须补的两处覆盖（否则折叠时三段不同步）**：

| 项 | 现状 | 需要做 |
|---|---|---|
| `"local-create"` / `local-<tag>` | **有** `animateItem`（`:167`、`:179`） | 不动 |
| `ne-create` / `ne-<id>` | **无** `animateItem`（`:217-222`、`:223-230`） | 补同一个 modifier |
| `qq-<tag>` | **无** `animateItem`（`:297-299`） | 补同一个 modifier |
| 状态项（`ne-loading`/`ne-empty`/`qq-empty`…） | 无 | 建议也补，否则折叠瞬间它们硬消失 |

**可选**：折叠指示器那一行自身的出现/消失用 `Crossfade`（仓库对「加载↔内容」切换的既有手段，
`SokuouTweens.CoverFade = tween(400, CubicBezierEasing(0.2f, 0f, 0f, 1f))`，
`Kanesumi-sec-a/.../Sokuou.kt:108`，仓库侧同值副本在 `AppMotion.kt:213`），
或直接跟着上面的 `fadeIn/fadeOut` 走（更省、更一致）。

### 7.4 新增动效参数必须落在 `AppMotion`

`AppMotion.kt:9` 的文件头写着：

```kotlin
9:  *   - v2.5.0 · A：全局动效规范（本仓库**唯一**允许新增动画参数的地方）。
```

`AppMotion.kt:57` 也写明「本版只保证「**新增**动效不散落」」。
所以**如果**折叠要引入一个具名新规格（例如 `listItemExit`），它必须加在 `AppMotion.kt` 里；
但如果直接复用 §7.3 的 `150/220/120 + MetroDefault`，则**不需要**新参数（这三个取值已经在本文件里）。

### 7.5 现有可用 token 速查（供选择时对齐）

| token | 取值 | 出处 |
|---|---|---|
| `MetroDefault` | easeOutQuad | `UwpEasing.kt:115` |
| `AppMotion.sheetAppear` | `tween(300, CubicBezier(0.2,0,0,1))` | `AppMotion.kt:203` |
| `AppMotion.sheetDismiss` | `tween(260, FastOutSlowInEasing)` | `AppMotion.kt:210` |
| `AppMotion.coverFade` | `tween(400, CubicBezier(0.2,0,0,1))` | `AppMotion.kt:213` |
| `AppMotion.listItemEnter` | `tween(220, FastOutSlowInEasing)` | `AppMotion.kt:222` |
| `AppMotion.LIST_ITEM_RISE_DP` | `8.dp` | `AppMotion.kt:225` |
| `AppMotion.effects` | `tween(200, FastOutSlowInEasing)` | `AppMotion.kt:184` |
| 本文件既有的 `animateItem` | `150 / 220 / 120`，全部 `MetroDefault` | `LibraryPlaylistsTab.kt:167-171`、`:179-183` |
| `SokuouTweens.CoverFade` | `tween(400, CubicBezier(0.2,0,0,1))` | `Kanesumi-sec-a/.../Sokuou.kt:108` |

⚠️ 注意一处**仓库内部不一致**：`AppMotion.listItemEnter` 用 `FastOutSlowInEasing`（220ms），
而 `DetailScaffold.kt:149` 的入场用 `MetroDefault`（220ms）。折叠条目建议跟随**本文件**（`MetroDefault`），
因为同一个列表里两种曲线并存会让「本地淡出」与「ncm 淡出」手感不同。

---

## 8. 不自动折叠的保证方式（哪些代码路径会写这个状态 —— 必须只有用户点击）

### 8.1 唯一允许的写入路径

```
用户点击 ──► LibrarySectionFoldSetting.writeCollapsed(prefs, Section.Local, !current)
                                （唯一写入口，形状同 PageTransitionSetting.writeEnabled, PageTransitionSetting.kt:101-103）
```

**必须只有这一条。** 任何 `LaunchedEffect` / `snapshotFlow` / `derivedStateOf` 回调写入这个状态，
都等于「自动折叠」的变体。

### 8.2 必须排除的自动写路径（逐条列出，写进实现注释）

| 候选路径 | 为什么容易误写 | 处置 |
|---|---|---|
| 列表变为空 / 加载失败 / 未登录 | 「反正没内容，折叠起来更清爽」是**最诱人的自动折叠** | **禁止**：`qqNeedLogin`（`:146`）、`qqOrdered.isEmpty()`（`:287`）、`neteaseError != null`（`:200`）一律**不得**写入折叠状态 |
| 列表变得很长 | 「超过 N 条自动折叠」是常见的省空间策略 | **禁止**（任务书 Hard requirement 明写 manual only） |
| 切 tab 离开/回来 | 见 §8.3，`remember` 状态会随 tab 卸载而丢失，看起来像「刷新后自动展开了」 | 状态必须**从 prefs 读**（`readCollapsed`），不得只放 `remember` |
| 冷启动 / `AppWarmup` | 预热时顺手重置 | **禁止** |
| 登录/登出 | QQ 区块内容整包换人 | **禁止**：折叠是**显示偏好**，与账号无关；换账号后保持用户的折叠选择 |
| 设置页「清除缓存」 | 它清了很多东西 | **不受影响**（§5.3：不碰 SharedPreferences） |
| 进程被杀 / 重启 | 要求是**持久化** | prefs 保证；恢复后**保持**折叠态（这正是需求） |

### 8.3 一个必须注意的实现陷阱：不要把状态只放 `remember`

`LibraryScreen.kt:268-285` 用 `AnimatedContent(targetState = selectedCategory)` 承载四个 tab，
`LibraryPlaylistsTab` 只在 `1 ->` 分支里（`:350-366`）。切走再切回时，
该分支会离开 composition（框架语义，见 §11 未验证项），因此：

- **`remember { mutableStateOf(false) }` 装的折叠态会在切 tab 后丢失** ⇒ 表现为「我明明折叠了，切回来又展开了」——
  这正是「自动展开」，与需求的「manual only + 持久化」都冲突。
- 正确做法：折叠态**每次组合都从 `ncrust_settings` 读**（`readCollapsed(context, section)`），
  写入时同步落盘。这与 `ThemeManager` / `LanguageManager` / `LyricsDisplayPrefs` 的既有做法一致
  （`AGENTS.md` 状态管理节：「No DI framework … all persistence is SharedPreferences + Gson」）。

### 8.4 读路径也必须是纯读（不要在这里做迁移写回）

`PageTransitionSetting.readEnabled` 是**纯读、无副作用**（`PageTransitionSetting.kt:94-98`），
而 `LyricsDisplayPrefs.readWordAnimation` 会在读的时候**写回**一次（`LyricsDisplayPrefs.kt:142`，一次性迁移）。
本特性的键是**全新的**、没有可消费的老键，所以应当照 `PageTransitionSetting`：**读不写盘**。
好处是可以在单测里反复调用而不污染 `FakePrefs`（`PageTransitionSetting.kt:53-56` 明确写了这一点）。

---

## 9. 与 `LazyVerticalGrid` / `BottomOverlayInsetDp` / 滚动位置的相互影响

### 9.1 `contentPadding` 与折叠**无关**（读码）

```kotlin
153:         contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
```

`BottomOverlayInsetDp` 的定义（`BottomOverlayInset.kt:27-30`）：

```kotlin
27: val BottomOverlayInsetDp: Dp
28:     @Composable get() = TrayLayout
29:         .bottomOverlayInsetDp(LocalConfiguration.current.screenWidthDp >= 600)
30:         .dp
```

派生算式（`TrayLayout.kt:163-165`）：

```kotlin
163: fun bottomOverlayInsetDp(isWideLayout: Boolean): Int =
164:     if (isWideLayout) HEIGHT_DP + BOTTOM_INSET_BUFFER_DP
165:     else BOTTOM_NAV_HEIGHT_DP + HEIGHT_DP + BOTTOM_INSET_BUFFER_DP
```

代入常量（`TrayLayout.kt:75` `HEIGHT_DP = 80`、`:148` `BOTTOM_NAV_HEIGHT_DP = 80`、`:151` `BOTTOM_INSET_BUFFER_DP = 8`）：

| 布局 | 算式 | 值 |
|---|---|---|
| 窄屏（<600dp） | 80 + 80 + 8 | **168dp** |
| 宽屏（≥600dp） | 80 + 8 | **88dp** |

→ **折叠不改变 `contentPadding`**（它只依赖托盘高度与屏宽），所以折叠**不会**造成「最后一项被托盘盖住」。
反过来也**不要**为了折叠去改这个常量。

> ⚠️ 任务书前提错误：任务书写 `BottomOverlayInsetDp` = 144dp/64dp。那是 **v2.5.4 之前的字面量**，
> `TrayLayout.kt:159-161` 明确记录了这个搬家：「v2.5.4 之前这里是另一个 56dp 字面量（`144.dp / 64.dp`）
> —— 那正是「托盘加高之后列表最后一项被盖住」的成因。现在它由 `HEIGHT_DP` 派生」。
> `AGENTS.md:450` 仍写着 144/64（**过期文档**，与本仓库自己的「源码优先」纪律冲突）。

### 9.2 `animateItem` 的覆盖不对称（折叠时会露出来）

| 条目 | `animateItem` | 行 |
|---|---|---|
| `"local-create"` | ✅ `150/220/120 + MetroDefault` | `:167-171` |
| `local-<tag>` | ✅ 同上 | `:179-183` |
| `ne-create` | ❌ | `:217-222` |
| `ne-<id>` | ❌ | `:223-230` |
| `qq-<tag>` | ❌ | `:297-299` |
| 库页专辑栅格（旁证） | ✅ | `LibraryScreen.kt:385` |

→ **折叠三个区块时，只有本地那一段会淡出/滑动，ncm 与 QQ 会硬跳。**
这是本次实现**必须**一并补齐的（§7.3），否则「动画平滑」这条硬要求只满足了 1/3。

### 9.3 key 稳定性

| 项 | 事实 | 证据 |
|---|---|---|
| 所有 key 都显式给出 | `"hdr-local"` / `"local-empty"` / `"local-create"` / `"local-"+tag` / `"hdr-netease"` / `"ne-loading"` / `"ne-error"` / `"ne-empty"` / `"ne-create"` / `"ne-"+id` / `"hdr-qq"` / `"qq-degraded"` / `"qq-loading"` / `"qq-need-login"` / `"qq-empty"` / `"qq-"+tag` | §4 全表 |
| 前缀互不冲突 | `local-` / `ne-` / `qq-` 各自带前缀；`ne-<Long>` 与 `qq-<source:owner:id>`、`local-<source:owner:id>` 不会撞 | `PlaylistModels.kt:44-60`、`PlaylistApi.kt:280` |
| 状态项 key 是**字面量**、位置在 `when` 里 | 所以「加载中 → 有内容」时是 `ne-loading` **消失** + `ne-create`/`ne-<id>` **出现**（两个不同 key），`LazyGrid` 会走 diff 而不是复用 | `:192-232` |
| 折叠 = 成批移除**连续区间**的 keyed 条目 | 不产生重编号（key 是内容身份，不是下标） | — |
| 新增折叠行的 key | 建议 `"local-folded"` / `"ne-folded"` / `"qq-folded"`，`span = maxLineSpan`，与 `hdr-*` 同族命名 | — |

### 9.4 滚动位置：仓库**没有**针对本网格的书面风险记录

```console
$ rg -n -F 'LazyVerticalGrid' docs TASK.md AGENTS.md    # EXIT=1, stdout 0 行, stderr 0 字节
$ rg -n -F 'expanded'        docs TASK.md AGENTS.md    # EXIT=0, 41 行（正向对照）
```

**唯一**有书面记录的滚动位置风险在**歌词面板**，不是这个网格：

- `AGENTS.md:1936`：「而它做的是 `listState.scrollToItem(0)`（回顶）。」
- `AGENTS.md:1937`：「**「回顶」在大屏下 == 「第一句被推出可视区」**。顶部留白是**固定 200dp**…」
- `AGENTS.md:1967`：「于是"`lines` 重置回顶"与"`isVisible` 定位"之间存在**竞态**：谁最后跑决定结果」
- `TASK.md:1022`：`I/LyricsScroll: ev=lines-reset OUT first=0 off=0      ← 回顶，此后没有任何路径纠正`

这条教训的**可迁移部分**是：**「列表内容重建」与「滚动位置」之间在本仓库真的出过事，
所以任何改变条目集合的操作都必须在真机上量一次滚动行为，而不是假设它没事。**

具体到本次折叠，有两个**只能靠真机验证**的场景（列入 §11）：

1. **用户在底部时折叠 QQ 区块** ⇒ 总内容高度骤减 ⇒ `LazyGrid` 会把滚动偏移钳回合法范围 ⇒
   可见内容会跳。仓库里**没有**任何「折叠后保持视口」的先例可抄。
2. **折叠的区块在视口上方** ⇒ 上面整段消失，用户当前看的内容会整体上移。这是**必然**的，不算 bug，
   但要确认它不会让用户「找不到刚点的那个按钮」——建议折叠行的 `padding` 与 `SectionHint` 一致（§6.1），
   保证折叠后那一行**仍在原处附近**。

**不要**为折叠引入 `scrollToItem` / `animateScrollToItem` 之类的补偿：仓库里这类调用只出现在
歌词面板（`NcrustLyricsPanel.kt:270/295/304/335`）与队列（`QueueView.kt:160/163/170/172/175`），
且都带各自的定位不变量；本网格**没有上提 `state`**（§4 开头），加补偿需要先上提状态，属于额外改动面。

### 9.5 网格列数

`columns = GridCells.Adaptive(minSize = 160.dp)`（`:149`）。折叠会让「本地 + ncm」的格子总数变化，
但由于折叠是**整段移除**，剩余格子的**列数不变、每格宽度不变**（自适应按容器宽度算，不按条目数），
所以不会出现「折叠后封面变大/变小」。这一点是**读码可证**的（`GridCells.Adaptive` 只依赖容器宽度）。

---

## 10. 文案落点（`Strings` 预算与分组）

### 10.1 预算规则（可执行的那一份）

`app/src/test/java/com/takahashirinta/ncrust/ui/i18n/StringsConstructorBudgetTest.kt`：

| 常量 | 值 | 行 |
|---|---|---|
| `maxPrimaryParams`（外层硬预算） | **150** | `:73` |
| `warnPrimaryParams`（外层预警） | 140 | `:81` |
| `maxGroupParams`（单个嵌套组硬上限） | **120** | `:84` |
| `warnGroupParams`（组预警） | 80 | `:87` |
| **`Strings` 主构造器当前精确值（钉死）** | **135** | `:177-187` |
| `dexSlots(135, true)` | 142（`1 + 135 + 5 mask + 1 marker`） | `:184-186` |
| 距 255 的余量 | ≥ 100（实测 113） | `:186` |

**规则（逐字引自测试与 AGENTS.md）**：

- `:151-155`：「请把新文案放进语义相符的嵌套组，并在类体里补一条转发属性保住调用点。」
- `:180-182`：「若是有意加文案，请把新文案放进嵌套组（外层一个都不要加），然后同步改这条断言并在提交信息里说明。」
- `:89`（`family` 列表上方）：「`Strings` 家族的全部数据类（外层 + 嵌套组）。**加组时**必须**同步这里**。」
- `AGENTS.md:3331`：「本仓库对「一个 data class 装全部静态文案」这类上帝类给出的预算是 **150**，且**每个嵌套组也各自受监控**」
- `AGENTS.md:3338`：「预测要**钉精确值**而不是只给范围」

### 10.2 各组当前参数个数（本轮实际解析 `Strings.kt` 得到）

解析方式：对每行匹配 `^    val \w+`（`data class Xxx(` 到收尾 `)` 之间）。

| 组 | 参数个数 | 源位置 | 距 warn(80) 余量 |
|---|---|---|---|
| `Strings`（外层，钉死 135） | — | `Strings.kt:53` | — |
| `OfflineStrings` | 21 | `:664-699` | 59 |
| `SourceStrings` | **57** | `:708-852` | 23 |
| **`PlaylistsStrings`（推荐落点）** | **17** | **`:863-898`** | **63** |
| `TagsStrings` | 7 | `:912-927` | 73 |
| `LocalPlaylistStrings` | 26 | `:939-992` | 54 |
| `QueueStrings` | 8 | `:1019-1036` | 72 |
| `MotionStrings` | 2 | `:1050-1055` | 78 |
| `SettingsStrings` | 64 | `:1073-1176` | 16 |
| `AboutStrings` | 25 | `:1195-1223` | 55 |
| `PlayerUiStrings` | 31 | `:1242-1295` | 49 |

（`v2_5_3 三个新组的规模被钉住` 那条用例也钉了 `SettingsStrings=64 / AboutStrings=25 / PlayerUiStrings=31`，
与本轮实测一致 —— `StringsConstructorBudgetTest.kt:254-266`。）

### 10.3 建议的新文案属性

**组：`PlaylistsStrings`**（`Strings.kt:863-898`，当前 17 个参数）。

为什么是它，而不是 `LocalPlaylistStrings` 或 `SourceStrings`：

- 折叠控件服务**三个源**，不是本地歌单专属 ⇒ 排除 `LocalPlaylistStrings`；
- `SourceStrings` 已经 57 个参数（且它装的是音源品牌/QQ 登录/聚合搜索），语义不符；
- `PlaylistsStrings` 就是「歌单」语义组（它已经装了 `sectionOwned`「自建歌单」/`sectionFav`「收藏歌单」
  这两个**区块级**文案，`:867-869`），折叠指示器与它们同级。

**建议新增 2 个属性**（保持最小面，`sectionCollapse` 只服务无障碍/长按提示）：

```kotlin
data class PlaylistsStrings(
    …
    /**
     * 区块折叠后那一行的文案：(该区块当前渲染的条目数) -> 文案。
     *
     * 参数是**已渲染列表**的长度，不是服务端 total：
     * 本地取 `localPlaylists.size`、ncm取 `neteasePlaylists.size`、
     * QQ 取 `qqOrdered.size`（不是 `qqPlaylists.size` —— 排序后才是真正渲染的那份）。
     */
    val sectionExpandAll: (Int) -> String,

    /**
     * 展开态下折叠控件的无障碍描述（TalkBack）与长按提示。
     *
     * ⚠️ **不复用** `collapsePlayer`（zh_CN.kt:104「收起」）——
     * AGENTS.md 规则 10「跨功能的文案不要复用」（v2.1.3 用户当场报出来的）：
     * 同一个词在不同功能里必须有各自的字符串，否则将来改一处会连带改错另一处。
     */
    val sectionCollapse: String,
)
```

**8 种语言的 zh-CN 取值（`zh_CN.kt:358-376` 那个 `playlists = PlaylistsStrings(...)` 块里补两行）**：

```kotlin
    playlists = PlaylistsStrings(
        …
        trackCount = { n -> "$n 首" },
        sectionExpandAll = { n -> "展开全部 $n 个" },   // 任务书原话
        sectionCollapse = "收起",
    ),
```

其余 7 个语言文件（`zh_TW.kt` / `en.kt` / `jp_JP.kt` / `jp_MY.kt` / `ko_NK.kt` / `de_DE.kt` / `ru_RU.kt`）
的同一块**必须同步补齐**：`PlaylistsStrings` 的参数**没有默认值**，
而各语言文件用的是**具名参数**构造 ⇒ 漏填一个语言会**编译错**（这正是仓库用编译期强制 8 语言齐全的机制）。

### 10.4 两个接线细节

1. **转发属性**：`Strings` 类体里有大量转发属性（例如 `:452-455` 的
   `val playlistFavorite get() = playlists.favorite`、`:479` 的
   `val localPlaylistSectionTitle get() = localPlaylist.sectionTitle`）。
   若要让调用点写 `strings.sectionExpandAll` 而不是 `strings.playlists.sectionExpandAll`，
   需要照 `:452` 的形状补一条转发属性；若直接写 `strings.playlists.sectionExpandAll`，则不用补。
   **两种都合法**，仓库两种都有先例（`LibraryPlaylistsTab.kt:213` 用的是转发属性 `strings.noPlaylists`）。
2. **不要碰外层**：本轮**不需要**改 `StringsConstructorBudgetTest` 里的任何断言 ——
   只要新文案进组，`Strings` 主构造器仍然是 135，测试保持全绿。这也是判断「落点选对了没有」的
   最简信号：**如果为了加这两条文案不得不改 `:177-187` 的 135，说明落点错了。**

---

## 11. 未验证项

> 下面每一条都是**本轮没有拿到证据**的；不要把它们当成结论使用。

### 11.1 推断但未验证（(c) 类）

| # | 项 | 为什么没验证 | 怎么验 |
|---|---|---|---|
| 1 | **key 锚定能保住滚动位置**（折叠移除条目后用户视口不跳） | 这是 Compose LazyLayout 的框架语义，仓库里既没有测试也没有 probe 记录（§9.4 的 0 命中）；本轮只读代码、未在设备上跑 | 真机：滚到 QQ 段中部 → 折叠 ncm 段 → 记录折叠前后首个可见条目的 key（可用 `LayoutInspector` 或加临时日志） |
| 2 | **折叠底部区块时滚动偏移被钳回、可见内容跳一下** | 同上，纯框架行为 | 真机：滚到列表最底 → 折叠 QQ → 观察是否跳；这是本轮**最可能出问题**的场景 |
| 3 | `AnimatedContent` 在切 tab 后**确实会 dispose** `LibraryPlaylistsTab` | 需读 Compose 框架语义；仓库没有注释或测试说明这一点 | 真机：把折叠态**故意**只放 `remember` 跑一次，切 tab 回来观察是否复位；或读 Compose `AnimatedContent` 的 `SaveableStateProvider` 行为 |
| 4 | `animateItem` 的 `fadeOutSpec` 在「一批条目同时被移除」时确实逐个播放 | 未在设备上测；`QueueView.kt:403-406` 的注释只讨论了 `placementSpec` | 真机观察；必要时降到只保留 `placementSpec` |
| 5 | 折叠动画的**帧时间**是否达标 | 仓库对新动效有**硬取证要求**（`AppMotion.kt:257-262`：「只要这个值 > 0，就必须在低端真机（Android 7.0 / 3GB 这一档）上用 `adb shell dumpsys gfxinfo <pkg> framestats` 记录**开启时**的 90 分位帧时间，并与关闭时对照。**模拟器结论不构成证据，debug 包数据也不构成证据**」） | 按该要求出 `docs/verification/v2.6.0/verification/framestats-*.txt` |
| 6 | 折叠控件与 QQ 刷新按钮的命中区不冲突 | 需要真机触摸验证（`AGENTS.md` 触摸陷阱 §7：命中区只增不减；§8：阈值要按可拖动行程核对） | 真机在 QQ 标题行左右两侧各点若干次 |
| 7 | 「非法值回落」在真机 prefs 上的表现 | 单测用 `FakePrefs` 可覆盖 `ClassCastException` 回落，但本轮**没有运行任何测试** | `./gradlew test --tests '*LibrarySectionFoldSettingTest'` |

### 11.2 本轮**没有执行**的验证（重要）

- **没有编译、没有跑单测**：本轮是纯只读探针，未执行 `./gradlew`。所以「新文案进组后仍然编译通过」
  「`animateItem(fadeInSpec=…, placementSpec=…, fadeOutSpec=…)` 签名在当前 Compose 版本可用」这两条，
  属于**读码 + 既有已发布代码**的推断（该三参签名已在 `LibraryPlaylistsTab.kt:167-171` 里、
  且随 v2.5.6 发布），**不是**本轮跑出来的。
- **没有真机 / 模拟器**：所有动效、滚动、命中区结论都是纸面推理。
- **工作区在探针期间被并发改动**（详见 §0）：本报告引用的证据文件全部 CLEAN，但落地前请重新确认工作区状态。
- **Kanesumi 侧未钉版本**：`MetroDefault` / `SokuouTweens` 的取值引自兄弟仓库
  `/home/duanjb666/deepseek/ncrust-gpl/Kanesumi-sec-a`（**不在 Ncrust 的 git 版本控制内**，
  通过 `settings.gradle.kts` 的 `includeBuild` 组合构建）。本轮没有记录该 checkout 的 commit，
  所以 `UwpEasing.kt:115`、`Sokuou.kt:108` 的行号**只对当前这份工作区副本有效**。

### 11.3 任务书前提中**确证有误**的地方（4 处）

| # | 任务书原话 | 实际情况 | 证据 |
|---|---|---|---|
| 1 | `contentPadding`（`BottomOverlayInsetDp`）按 **144dp/64dp** 讨论 | 实际是 **168dp（窄）/ 88dp（宽）** | `TrayLayout.kt:163-165` + `:75/148/151`；`BottomOverlayInset.kt:12-13`。`TrayLayout.kt:159-161` 明确说 144/64 是 **v2.5.4 之前**的字面量。`AGENTS.md:450` 仍在写 144/64 —— **过期文档**（与仓库自己的「源码优先」纪律冲突） |
| 2 | 「`SectionHeader`」被当作三个区块的区块标题 | **只有本地与 ncm 用 `SectionHeader`**；**QQ 的标题是一段内联 `Row`**（带刷新按钮） | `LibraryPlaylistsTab.kt:158`、`:190` vs `:235-258`。把开关做进 `SectionHeader` 会漏掉 QQ |
| 3 | 「仓库禁止播放器卡片用状态驱动重组，但**这是列表、不是播放器卡片**」（暗示列表可以放宽） | `AppMotion` 的 KDoc 把这条约束**写给了所有消费者**，不只是播放器：「这些 spec **只描述时间**，不规定怎么消费。消费侧仍必须遵守「GPU 零重组」…**不得**为了用这些 spec 而新引入逐帧重组的 `animateFloatAsState`」 | `AppMotion.kt:71-73`。本轮推荐的 `animateItem` 恰好符合（它不动画 state、不需要逐帧重组），但**不能**因此认为列表可以随便用 `animateFloatAsState` |
| 4 | 检索「`expanded`/`collapsed`」并期望得到干净的命中清单 | 大小写无关的英文子串检索会命中 `Detail**Scaffold**State`（→ `foldstate`）与 `DetailScaffold**Cross**fade`（→ `foldcrossfade`） | §3.3 词频表；`DetailScaffold.kt:101/102/103/112/117/140/208`、`:109` |

### 11.4 顺带发现的一处**仓库内部矛盾**（与 P2 无关，但读到了就记下来）

`StringsConstructorBudgetTest.kt:204-206` 的注释写：

```
* 为什么这次可以加外层而不是拆组：`SourceStrings` 当时是 57 个参数、
* 上限 60（只剩 3 个槽位），而主构造器只到 129、预算 150。
```

- 「`SourceStrings` 是 57 个参数」**与本轮实测一致**（§10.2）。
- 但「**上限 60**」与同一文件 `:84` 的 `maxGroupParams = 120` **直接矛盾**。
  仓库可执行的规则是 `:84` 的 **120**；注释里的 60 来历不明。
  → 建议下一个动 `Strings` 的人**以 `:84` 为准**，并顺手修掉这条注释。

---

## 附录 A：本轮全部只读命令（可重跑）

```bash
cd /home/duanjb666/deepseek/ncrust-gpl/Ncrust
git rev-parse --short HEAD && git status --porcelain && git log --oneline -3

# 1) 折叠概念（Kotlin 源码）
rg -n -F 'expanded'          app/src --glob '*.kt'
rg -n -F 'collapsed'         app/src --glob '*.kt'
rg -n -F '折叠'              app/src --glob '*.kt'
rg -n -F '收起'              app/src --glob '*.kt'
rg -n -F '展开'              app/src --glob '*.kt'
rg -ni 'fold|collaps'        app/src --glob '*.kt'

# 2) 动画原语
rg -n -F 'AnimatedVisibility' app/src --glob '*.kt'
rg -n -F 'animateContentSize' app/src --glob '*.kt'   # EXIT=1
rg -n -F 'expandVertically'   app/src --glob '*.kt'   # EXIT=1
rg -n -F 'shrinkVertically'   app/src --glob '*.kt'   # EXIT=1
rg -n -F 'animateItem'        app/src --glob '*.kt'   # 正向对照

# 3) 文档层
rg -n -F 'LazyVerticalGrid' docs TASK.md AGENTS.md   # EXIT=1
rg -n -F 'expanded'         docs TASK.md AGENTS.md   # 正向对照

# 4) prefs
rg -ln 'ncrust_settings' app/src --glob '*.kt'
rg -n -B2 'edit\(\)\.clear\(\)' app/src/main/java --glob '*.kt'
rg -n 'putStringSet|getStringSet' app/src --glob '*.kt'
rg -n 'android:process' app/src/main/AndroidManifest.xml   # EXIT=1

# 5) 文案预算
rg -n '^(data )?class \w*Strings' app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt
# 参数个数：解析 `data class Xxx(` 与收尾 `)` 之间匹配 `^    val \w+` 的行
```

## 附录 B：一句话交接

> **本仓库今天没有任何「区块折叠」先例（三条检索路径交叉证否，`expandVertically`/`shrinkVertically`/`animateContentSize` 均 0 命中）。**
> 折叠单位定为**按源**（3 个布尔，存 `ncrust_settings`，键名建议 `library_section_collapsed_{local,netease,qq}`，默认 `false`，脏键回落 `false`）。
> 动效**逐字复用本文件已有的** `Modifier.animateItem(fadeIn = tween(150, MetroDefault), placement = tween(220, MetroDefault), fadeOut = tween(120, MetroDefault))`，
> 并**必须**把 `animateItem` 补到目前缺失的 ncm 条目（`LibraryPlaylistsTab.kt:217-230`）与 QQ 条目（`:297-299`）。
> 文案进 `PlaylistsStrings`（17→19 参数），**不要**碰外层 `Strings`（钉死 135）。
> 实现时最容易踩的两个坑：① QQ 区块标题**不是** `SectionHeader`（是 `:235-258` 的内联 `Row`）；
> ② 折叠态**不能只放 `remember`**，否则切 tab 回来会「自动展开」（`LibraryScreen.kt:268` 的 `AnimatedContent`）。
