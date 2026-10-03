# 库页「歌单」tab 列表式 / 卡片式布局切换 —— 源码探针报告（v2.6.0 · P1）

> **仓库基线**：`ncrust-gpl/Ncrust` · 分支 `master` · HEAD = `7a4e33b`（`docs(v2.5.6): 回填设备验证结果与发布物信息（含未完成的平板复测）`）· `git status --short` **空**（工作区干净）
> **纪律**：本次探针**未修改任何源码**、未提交、未运行 gradle、未上机。全文只有静态阅读 + 对**仓库内既有构建产物**的 `javap`。
> **范围**：库页（`LibraryScreen`）「歌单」tab（`LibraryPlaylistsTab`）。不涉及详情页、不涉及播放器。
> **证据分级**：【读码】= 直接读到源码行；【命令】= 有命令输出 + 退出码；【推断】= 由前两者推出但**未上机验证**（见 §11）。

---

## 1. 结论先行

| # | 问题 | 结论一句话 | 关键证据 |
|---|---|---|---|
| Q1 | 当前布局结构 | **一个** `LazyVerticalGrid(columns = GridCells.Adaptive(minSize = 160.dp))`；区块标题 / QQ 行 / 空态 / 加载态用 `GridItemSpan(maxLineSpan)` 占满整行，**卡片格子**只有本地歌单、ncm 歌单、两个「＋」入口 | `LibraryPlaylistsTab.kt:148-155`；逐项对照见 §3 |
| Q2 | 切换按钮放哪 | **这一页没有工具栏、也没有页头**（全文 0 处 `TopAppBar`/`Bar`/`Toolbar`；第一个 item 就是「本地歌单」区块标题）。页头在 `LibraryScreen.kt:246-257`、分类 tab 在 `LibraryScreen.kt:259-263`。最佳落点是**在 `LibraryPlaylistsTab` 的 grid 顶部新加一个 `maxLineSpan` 的头部行**，形制直接抄**同文件** QQ 区块标题那一行（`LibraryPlaylistsTab.kt:235-258`） | §4 |
| Q2b | 既有「页头两态图标开关」 | **无**（`SegmentedButton` / `IconToggleButton` 均 0 命中，退出码 1）。最近的三类先例：① `RotationToggleButton`（同槽位换图标 + 换染色，**播放器控制栏**，不是页头）；② QQ 区块标题右侧刷新图标（**是页头行，但单态**）；③ `MetroTabRow` / `AccentSourceSelector` / `ThemeModeSelector`（分段选择器，**设置项**形态） | §4.3 |
| Q3 | 持久化 | 唯一落点 `ncrust_settings`；键名全小写 `snake_case`；「枚举索引存 Int + `normalize` 回落」已有 **3 个**先例；一次性迁移先例 = `QualityLadder.migrate`（版本键 + 幂等 + 在 `NcrustApplication.onCreate` 调用）。**推荐**：新建 `object PlaylistLayoutSetting`，`PREFS="ncrust_settings"` / `KEY="library_playlist_layout"`(Int) / 默认 `CARD`，配纯函数 `normalize` + `PlaylistLayoutSettingTest` | §5 |
| Q4 | 既有布局切换组件 | **无**。`GridCells` 全仓库仅 3 处、全部是**写死**的（无用户可选）；`GridCells.Fixed` 0 命中；`rememberSaveable` 0 命中；`key(layout…` 0 命中 | §4.4 |
| Q4b | `SongCard` 的两种形态 | **是**，这是本仓库最强的「同一实体、两套渲染」先例：`enum class SongCardStyle { LIST, COMPACT, GRID }`，`SongCard(style = …)`，`when (style)` 分派到「Row 行」与「Column 格子」两条完全不同的实现。**但它没有"用户可切换"这一半**：风格由**调用点**写死（22 处引用，0 处来自用户输入、0 处落盘） | `SongCard.kt:36-40 / 44-46 / 83-246`；§4.4 |
| Q5 | 列表式规格 | **封面 48dp、行高 64dp**（`48 + 8×2`）、行内 padding `horizontal 16dp / vertical 8dp`、封面↔文字 12dp、标题 `bodyLarge` 单行省略、副标题 `bodySmall` = `"N 首" (+ " · 我喜欢")`、圆角 `AppShapes.small`(8dp) + 1dp 描边。全部逐值来自**同文件的 QQ 行** | §6 |
| Q6 | 卡片式规格 | `GridCells.Adaptive(minSize = 160.dp)`、封面 `fillMaxWidth().aspectRatio(1f)`、圆角 `AppShapes.large`(16dp) + 1dp 描边、▶ 覆盖封面右下（直径 36dp，6dp 内边距）、标题 `bodyMedium` 单行省略、副标题 `bodySmall`、格子间距双向 2dp。窄屏内容宽 360dp ⇒ **2 列 × 179dp**；宽屏列数随宽度增长 | §7 |
| Q7 | 换容器会不会丢状态 | **会丢**。该 tab 没传 `state`，`LazyVerticalGrid` 的默认参数会现场 `rememberLazyGridState()`（已用字节码取证）；换成 `LazyColumn` ⇒ 该 composable 槽位被替换 ⇒ 滚动位置回到 0。**推荐不换容器**：容器保持 `LazyVerticalGrid`，`columns` 在 `Adaptive(160.dp)` ↔ `Fixed(1)` 之间切，item 内部再按模式二选一 —— 槽位不变 ⇒ `LazyGridState` 保留。仓库既有同类手段是「把 state 提到被替换子树之上」（`LibraryScreen.kt:186` 建 state → `:294` 传给 `LazyColumn`，而它外面的 `AnimatedContent` 会替换整棵子树） | §8 |
| Q8 | benchmark 覆盖 | `benchmark/` 共 **4 条**基准（Startup / HomeScroll / SettingsScroll / ExpandPlayer）+ 1 个基线 profile 采样器；**没有任何歌单页 / 库页 tab 的滚动基准**（在 `benchmark/src/` 下检索「歌单」→ 退出码 1） | §8.4 |
| Q9 | 动效 token | `ui/theme/AppMotion.kt` 是**唯一落点**；「内容替换」= `spatialDefault`(spring 0.8/380，注释「面板切换的默认值」) / `spatialSlow`(spring 0.8/200，注释「整页内容替换」)；**开关类短反馈已有专用 token** = Kanesumi `SokuouTweens.ToggleFlip`(220ms `MetroCubic`)；同页内容替换的既有先例就在本页外层：`tween(200, MetroDefault)` 位移 + `tween(160, MetroDefault)` 淡入 | §9 |
| Q10 | 文案预算 | 预算 **150**、预警 140；`Strings` 主构造器**当前精确值 = 135**（dex 槽 `1+135+5+1 = 142`，余量 113）。新文案**必须进具名组**：本次推荐落**已存在**的 `PlaylistsStrings`（现 17 参数，组上限 120）⇒ **不动主构造器、不新建组**，改 `Strings.kt` + **8 个语言文件** | `StringsConstructorBudgetTest.kt:73/81/84/87/182/185`；`javap` 实测 135；§10 |

**两处与任务前提不符的地方**（详见 §11.3）：

1. 任务说「卡片式/列表式应用到**两个源**」——实际上这一页有**三个**按源分区：`本地歌单` / `ncm` / `qm`（`LibraryPlaylistsTab.kt:66-70`、`:156-301`）。**本地歌单那一段也是网格格子**，只改两个源会留下一个「半切换」的页面。
2. 任务隐含「切换是纯增量」——但今天的默认形态**本身就是混合的**（ncm/本地是卡片、QQ 是整行），所以**不存在一个"两源都零行为变化"的默认值**，默认值必须由产品拍板（§5.5 给了两个选项与后果）。

---

## 2. 探针方法（命令 + 退出码）

### 2.1 基线与环境

| 命令 | 退出码 | 输出要点 |
|---|---|---|
| `git log --oneline -3` | 0 | `7a4e33b docs(v2.5.6): …` / `4120da2 …` / `fb2aef1 build: 升级至 v2.5.6-gpl（versionCode 47）` |
| `git status --short \| head -20` | 0 | （空 ⇒ 工作区干净） |
| `wc -l LibraryPlaylistsTab.kt LibraryScreen.kt ThemeManager.kt LanguageManager.kt` | 0 | 485 / 508 / 139 / 40 |
| `grep -n "compose-bom" app/build.gradle.kts` | 0 | `326: implementation(platform("androidx.compose:compose-bom:2024.12.01"))` |
| `javap` 所在 JDK | 0 | `openjdk 26.0.2`，`javap` = `/usr/bin/javap` |

Compose Foundation 的实际解析版本（来自 gradle 缓存，非仓库源码）：
`~/.gradle/caches/modules-2/files-2.1/androidx.compose.foundation/foundation-android/1.7.6/…/foundation-release.aar`。

### 2.2 正向检索（阳性对照）

| 命令 | 退出码 | 命中 |
|---|---|---|
| `grep -rn "GridCells" --include=*.kt app/src/main` | 0 | 3：`LibraryPlaylistsTab.kt:149`(Adaptive 160dp)、`HomeScreen.kt:330`(Adaptive 148dp)、`LibraryScreen.kt:375`(Adaptive 160dp) |
| `grep -rnE "GridCells\.Adaptive" --include=*.kt app/src/main` | 0 | 3（同上） |
| `grep -rn "LazyVerticalGrid" --include=*.kt app/src/main` | 0 | 9（含 import 与 KDoc） |
| `grep -rn "SongCardStyle" --include=*.kt app/src` | 0 | 22（枚举定义 + 21 处调用） |
| `grep -rn "RotationToggleButton" --include=*.kt app/src/main` | 0 | 3：`PlayerCard.kt:1053`、`FullPlayerControls.kt:550`、`FullPlayerControls.kt:575`（定义） |
| `grep -rn "MetroTabRow" --include=*.kt app/src/main` | 0 | 8（`LibraryScreen.kt:259`、`SearchScreen.kt:407`、`ArtistDetailScreen.kt:203/215` + import） |
| `grep -rn "rememberLazyGridState" --include=*.kt app/src/main` | 0 | 2：`HomeScreen.kt:17`(import)、`HomeScreen.kt:128` |
| `grep -rn "rememberLazyListState" --include=*.kt app/src/main` | 0 | 10（`LibraryScreen.kt:186` 等） |
| `grep -rn "ncrust_settings" --include=*.kt app/src/main app/src/test` | 0 | 34 行（见 §5.1） |
| `grep -rn "Icons.Default.Refresh" --include=*.kt app/src/main` | 0 | 5（含 `LibraryPlaylistsTab.kt:251`） |

### 2.3 负向检索（**每条都记了 stderr 与退出码**；`grep` 无命中 = 退出码 1、stderr 为空）

判据命令模板（保证退出码取自 `grep` 本身，而不是管道末端）：

```bash
out=$(grep -rnE "$pat" --include=*.kt app/src/main 2>/tmp/e.txt); rc=$?
echo "exit=$rc stderr=[$(cat /tmp/e.txt)]"
```

| 模式 | 退出码 | 命中 | stderr |
|---|---|---|---|
| `SegmentedButton\|segmentedButton` | **1** | 0 | 空 |
| `IconToggleButton\|IconToggle` | **1** | 0 | 空 |
| `\b(LayoutMode\|layoutMode\|listMode\|gridMode\|viewMode\|displayMode\|listStyle\|cardStyle\|gridStyle\|LayoutStyle)\b` | **1** | 0 | 空 |
| `GridCells\.Fixed` | **1** | 0 | 空 |
| `isGrid\|isListMode\|listStyle\|cardStyle` | **1** | 0 | 空 |
| `rememberSaveable` | **1** | 0 | 空 |
| `key\(layout\|key\(style\|key\(mode` | **1** | 0 | 空 |
| `ncrust_settings.*layout\|layout.*prefs\|prefs.*layout` | **1** | 0 | 空 |
| `MetroAppBar` | **1** | 0 | 空 |
| `grep -rn "歌单" benchmark/src/main/java/…/benchmark/` | **1** | 0 | 空 |
| （对照）`Segmented` 的 2.2 节各条 | 0 | >0 | 空 |

**说明**：`viewMode` 单用会命中 `viewModel` / `loadWithOverviewMode`（第一次检索 `LayoutMode\|layoutMode\|listMode\|gridMode\|viewMode\|displayMode` 得到 57 条噪音，退出码 0）；上表用的是加词边界后的收紧版本 —— **收紧后 0 命中**，这是本节「无既有布局开关」结论的依据。

### 2.4 关键取证：`javap` 读**仓库内既有构建产物**

被测 class：`app/build/tmp/kotlin-classes/release/com/takahashirinta/ncrust/ui/i18n/Strings.class`（mtime `2026-09-26 18:58`）。

**产物与 HEAD 一致性的自证**（这决定下面数字能不能用）：

| 命令 | 输出 |
|---|---|
| `git log -1 --format='%h %ad %s' --date=iso -- app/src/main` | `f5ac514 2026-09-26 20:23:09 +0800 fix(player): 平板竖屏 ⤢ 点不到 …`（**晚于** class mtime） |
| `git show --stat f5ac514` | 只改 `FullPlayerControls.kt` / `PlayerLayout.kt` / `PlayerLayoutTest.kt` —— **不含 i18n** |
| `git log --since='2026-09-26 18:00' -- …/ui/i18n/` | 最后一条 = `52809de 2026-09-26 18:58:20 fix(search): …` ⇒ 18:58 之后 **i18n 目录零变更** |

⇒ `Strings.class` 的 i18n 内容与 HEAD 相同，数字可用。

```bash
# 主构造器参数个数（只数顶层逗号，跳过泛型里的逗号）
javap -p -cp app/build/tmp/kotlin-classes/release com.takahashirinta.ncrust.ui.i18n.Strings \
  | grep -E "public com.takahashirinta.ncrust.ui.i18n.Strings\(" \
  | python3 -c "…顶层逗号计数…"
# → primary ctor params = 135
# → dex slots = 1 + 135 + 5 + 1 = 142
```

```
# LazyVerticalGrid 的 state 默认值（foundation-android 1.7.6）
javap -c -p -cp . androidx.compose.foundation.lazy.grid.LazyGridDslKt | grep -n rememberLazyGridState
# → 568: invokestatic // LazyGridStateKt.rememberLazyGridState:(IILandroidx/compose/runtime/Composer;II)…
#   该调用点所属方法 = public static final void LazyVerticalGrid(...)
```

```
# GridCells.Fixed / GridCells.Adaptive 是带 equals/hashCode 的稳定值类型
javap -p -cp . "androidx.compose.foundation.lazy.grid.GridCells\$Fixed"
# → public final class …GridCells$Fixed  … public static final int $stable;
#   public boolean equals(java.lang.Object); public int hashCode();
javap -p -cp . "androidx.compose.foundation.lazy.grid.GridCells\$Adaptive"
# → 同样有 $stable / equals / hashCode
```

（提取步骤：`unzip -o -q foundation-release.aar classes.jar` 到 `/tmp/ncrust-probe/fa`，再 `javap -cp .`。**只读仓库外的临时目录，未改动仓库**。）

### 2.5 git 取证

| 命令 | 退出码 | 输出 |
|---|---|---|
| `git log --oneline -S "pageTransitionLabel" -- …/Strings.kt` | 0 | `516e005 feat(ui): 「页面切换动效」用户可配（默认启用）` |
| `git show 516e005 -- …/Strings.kt …/zh_CN.kt` | 0 | 见 §10.3 全文引用 |

---

## 3. 当前布局结构（`LibraryPlaylistsTab` 逐 item 对照表）

### 3.1 容器

```kotlin
// LibraryPlaylistsTab.kt:148-155
LazyVerticalGrid(
    columns = GridCells.Adaptive(minSize = 160.dp),
    modifier = Modifier.fillMaxSize(),
    horizontalArrangement = Arrangement.spacedBy(2.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
    contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
    flingBehavior = rememberMetroFlingBehavior(),
) { … }
```

| 项 | 值 | 出处 |
|---|---|---|
| 容器 | `LazyVerticalGrid`（**整页只有一个滚动容器**） | `LibraryPlaylistsTab.kt:148` |
| 列策略 | `GridCells.Adaptive(minSize = 160.dp)` | `LibraryPlaylistsTab.kt:149` |
| `state` | **未传** ⇒ 现场 `rememberLazyGridState()`（javap 证据见 §2.4） | `LibraryPlaylistsTab.kt:148-155` |
| 双向间距 | `2.dp` | `:151`、`:152` |
| `contentPadding` | `bottom = BottomOverlayInsetDp`（144dp 窄屏 / 64dp 宽屏） | `:153` |
| fling | `rememberMetroFlingBehavior()` | `:154` |
| KDoc 的设计理由 | 「`LazyColumn` 里嵌 `LazyVerticalGrid`」在 Compose 里非法（同向嵌套滚动 + 无限高度约束），所以三个区块共用一个网格，标题与 QQ 行用 `GridItemSpan(maxLineSpan)` 占满整行 | `:78-84` |

### 3.2 逐 item 对照表（按源码书写顺序）

| # | key | span | 渲染的 composable | 形态 | 行号 |
|---|---|---|---|---|---|
| 1 | `"hdr-local"` | `GridItemSpan(maxLineSpan)` | `SectionHeader(strings.localPlaylistSectionTitle)` | 整行标题 | `:157-159`（`SectionHeader` 定义 `:313-318`） |
| 2 | `"local-empty"`（条件：本地为空） | `maxLineSpan` | `SectionHint(strings.localPlaylistEmpty)` | 整行小字 | `:160-164`（`SectionHint` 定义 `:321-326`） |
| 3 | `"local-create"` | 默认（**1 格**） | `NewPlaylistGridItem(label = strings.localPlaylistNew)` | **网格格子** | `:165-175` |
| 4 | `"local-" + it.key.tag` | 默认（**1 格**） | `LocalPlaylistGridItem` | **网格格子** | `:176-186` |
| 5 | `"hdr-netease"` | `maxLineSpan` | `SectionHeader(strings.sourceNetease)` | 整行标题 | `:189-191` |
| 6 | `"ne-loading"`（条件） | `maxLineSpan` | `Box(72dp)` + `MetroProgressIndicator` | 整行加载态 | `:194-198` |
| 7 | `"ne-error"`（条件） | `maxLineSpan` | `Column` + `MetroText(error)` + 重试 `Box(clickable)` | 整行错误态 | `:201-209` |
| 8 | `"ne-empty"`（条件） | `maxLineSpan` | `SectionHint(strings.noPlaylists)` | 整行小字 | `:212-214` |
| 9 | `"ne-create"`（条件：非空） | 默认（**1 格**） | `NewPlaylistGridItem()`（label 缺省 = `strings.playlistNew`） | **网格格子** | `:217-222` |
| 10 | `"ne-" + it.id` | 默认（**1 格**） | `PlaylistGridItem(onPlayAll = …)` | **网格格子** | `:223-230` |
| 11 | `"hdr-qq"` | `maxLineSpan` | 内联 `Row`：`MetroText(sourceQqMusic)` + `Spacer(weight(1f))` + 刷新图标 `Box(clickable)` | **整行头（含右侧图标按钮）** | `:235-258` |
| 12 | `"qq-degraded"`（条件） | `maxLineSpan` | `SectionHint(OFFLINE / NEED_LOGIN / TRUNCATED)` | 整行小字 | `:260-270` |
| 13 | `"qq-loading"`（条件） | `maxLineSpan` | `Box(72dp)` + `MetroProgressIndicator` | 整行加载态 | `:274-278` |
| 14 | `"qq-need-login"`（条件） | `maxLineSpan` | `SectionHint(strings.playlistLoginRequired)` | 整行小字 | `:283-285` |
| 15 | `"qq-empty"`（条件） | `maxLineSpan` | `SectionHint(loadFailed / playlistEmpty)` | 整行小字 | `:288-294` |
| 16 | `"qq-" + it.key.tag` | **`GridItemSpan(maxLineSpan)`** | `QqPlaylistInlineRow` | **整行条目** | `:297-299` |

**关键结论**（三个"不同宽度共存于一个网格"的机制）：网格格子 = 默认 span（1 格），整行 = `GridItemSpan(maxLineSpan)`。**QQ 是唯一给 `items(...)` 传 `span` 的一段**（`:297`）—— 这就是「ncm 是卡片、QQ 是整行」的全部实现。

### 3.3 四个 item composable 的定义位置

| composable | 定义位置 | 现状形态 |
|---|---|---|
| `PlaylistGridItem` | **`LibraryScreen.kt:448-478`**（不在 `LibraryPlaylistsTab.kt`） | 卡片格子（`aspectRatio(1f)` 封面 + ▶ + 标题 + 曲目数） |
| `NewPlaylistGridItem` | `LibraryScreen.kt:409-446`（`public`，三处复用） | 卡片格子（占位色块 + 居中「＋」） |
| `LibraryAlbumGridItem` | `LibraryScreen.kt:480-509` | 卡片格子（**专辑 tab 用，与本功能无关**） |
| `LocalPlaylistGridItem` | `LibraryPlaylistsTab.kt:394-439`（`private`） | 卡片格子（占位色块 + 居中音符图标 + 标题 + 同步/本地徽标） |
| `QqPlaylistInlineRow` | `LibraryPlaylistsTab.kt:335-386`（`private`） | 整行（48dp 封面 + 标题 + 曲目数） |
| `SectionHeader` / `SectionHint` | `LibraryPlaylistsTab.kt:313-318` / `:321-326`（均 `private`） | 整行 |

> ⚠️ 这条对施工很关键：`PlaylistGridItem` / `NewPlaylistGridItem` 在 **`LibraryScreen.kt`**，不在 tab 文件里。列表式要同时改这两个文件（或在 tab 侧新增行式 composable，把 `PlaylistGridItem` 留给卡片模式）。

---

## 4. 切换按钮落点候选

### 4.1 「这一页有没有工具栏」的确切答案

**没有。** 证据：

| 检索 | 退出码 | 结果 |
|---|---|---|
| `grep -n "Bar\|Toolbar" LibraryPlaylistsTab.kt` | **1** | 0 命中 |
| `grep -rn "MetroAppBar" --include=*.kt app/src/main` | **1** | 0 命中（全仓库未使用） |
| `grep -rn "TopAppBar\|Scaffold\(" --include=*.kt app/src/main` | 0 | 只有注释提到「没有 M3 TopAppBar 的实体 Surface」（`DetailScaffold.kt:50`、`AboutScreen.kt:60`、`SongDetailScreen.kt:137`）与 `DetailScaffold(` 调用点 —— **主 tab 屏（首页/库/搜索/用户）没有任何 AppBar** |

`LibraryPlaylistsTab` 的**最顶部**就是第 1 个 item：`"hdr-local"` → `SectionHeader("本地歌单")`（`:157-159`），其 padding 为 `start=16, end=16, top=14, bottom=6`（`:315`）。

页头与分类 tab 在**上一层**：

```kotlin
// LibraryScreen.kt:244-263
Column(modifier = Modifier.fillMaxSize()) {
    // Groove 风大字页头。
    Column(
        modifier = Modifier.fillMaxWidth().statusBarsPadding()
            .padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
    ) {
        MetroText(strings.tabLibrary, …, style = …pageHeading)      // :252-256
    }
    Spacer(Modifier.height(4.dp))                                   // :258
    MetroTabRow(selectedTabIndex = selectedCategory, …)             // :259-263
    AnimatedContent(targetState = selectedCategory, …) { … }        // :268-397
}
```

### 4.2 落点候选

| 候选 | 位置 | 优点 | 代价 / 风险 |
|---|---|---|---|
| **C1（推荐）** | `LibraryPlaylistsTab` 的 grid **最顶部**新增一个 `item(key="hdr-layout", span={GridItemSpan(maxLineSpan)})`，内容 = 一行 `Row`：左侧可选一句说明，右侧一个两态图标按钮 | 开关离被控内容最近；随内容滚动（与首页页头同构：首页页头就是 grid 的第一个 item，`HomeScreen.kt:339-353`）；不碰 `LibraryScreen` 的分层 | 多一个约 34–40dp 高的行；滚动到底部时按钮不可见（可接受：它不是高频操作） |
| **C2（次选）** | 与 C1 同，但把这一行**并入现有的 QQ 区块标题那一行的形状**（`Row(fillMaxWidth, padding(start=16,end=16,top=14,bottom=6), verticalAlignment=CenterVertically)` + `Spacer(weight(1f))` + `Box(clickable).padding(6.dp)` + `MetroIcon(sizeDp=18.dp)`） | 形制与**同文件既有代码逐值一致**，无需新造视觉 | 若放在最顶部，会与「本地歌单」标题形成两个视觉权重相同的行，需靠图标靠右来区分 |
| C3 | `LibraryScreen.kt:259-263` 的 `MetroTabRow` 右侧（需要把 tab 行包进一个 `Row`） | 位置最显眼、不随内容滚走 | 该行的语义是「切分类」，塞一个布局开关进去会让这一行承担两件事；且它作用于整页（单曲/专辑两个 tab 没有这个开关）⇒ 作用域不匹配 |
| C4 | 设置页（`UserScreen`）新增开关 | 与 `PageTransitionSetting` 完全同构 | 违反「**立即生效、不刷新数据**」的体感要求（用户要跳出去再回来）；且必须像 v2.5.1 那样把状态提升到 `MainScreen`（`516e005` 提交信息：「状态提升到 `MainScreen`，避免两处各存一份导致『设置页关了、转场还在』」） |

**推荐 C1**：它是唯一「不新增页面级 chrome、作用域恰好等于被控内容、且与仓库既有『页头 = 列表第一项』约定一致」的位置（Kanesumi `AGENTS.md`：「**没有 topBar 槽**：Kanesumi Design 约定『顶栏 = 滚动列表第一项』」）。

### 4.3 既有「小尺寸两态图标开关」先例（诚实回答：**页头里的没有**）

| 先例 | 位置 | 是否两态 | 是否在页头 | 形态 |
|---|---|---|---|---|
| `RotationToggleButton` | `FullPlayerControls.kt:575-596` | ✅ 是 | ❌ 播放器控制栏 | 同槽位换图标（`if (autoRotate) ScreenRotation else ScreenLockPortrait`）+ 换染色（`if (…) primary else onBackground`）+ 外层 `Box.size(size).clickable{}.semantics{contentDescription}`，内层图标 `contentDescription = null`（避免 TalkBack 读两遍） |
| QQ 区块标题的刷新图标 | `LibraryPlaylistsTab.kt:245-256` | ❌ 单态（点击 = `qqReloadTick++`） | ✅ **是**（本页唯一的页头行） | `Box(clickable(enabled=!qqLoading).padding(6.dp))` + `MetroIcon(sizeDp = 18.dp, tint = colors.primary)`；`contentDescription = strings.playlistRefresh` |
| `DetailScaffold` 右上角动作 | `DetailScaffold.kt:76-77 / 175-182`；实现在 `TopScrimIconButton` `:194+` | ❌ 单态 | ✅（顶部 scrim） | `icon` + `contentDescription` 参数化；48dp 触控区（委托 Kanesumi `MetroTopScrim`） |
| `MetroTabRow` | Kanesumi `kanesumi-controls/…/MetroTabRow.kt`；调用点 `LibraryScreen.kt:259`、`SearchScreen.kt:407`、`ArtistDetailScreen.kt:203/215` | ✅ 多态（分段） | 部分是 | 整行等分 tab；2 个 item 即是「两段式开关」，但视觉重量是「切分类」级 |
| `AccentSourceSelector` | `ui/theme/AccentSourceSelector.kt:22-82` | ✅ 三态 | ❌ 设置项 | `Row(spacedBy(8.dp))` + 每项 `.weight(1f).border(1.dp, …).background(primary@14%)` + 选中态主色字（`:39-58`） |
| `ThemeModeSelector` | `UserScreen.kt:1034`（`private`），调用点 `:637` | ✅ 三态 | ❌ 设置项 | 与 `AccentSourceSelector` 同形（后者 KDoc 自述「形态对齐同页的 ThemeModeSelector」，`AccentSourceSelector.kt:18`） |

**结论**：仓库里「两态图标开关」的**行为**先例是 `RotationToggleButton`，「页头右侧小图标」的**布局**先例是 QQ 区块标题那一行 —— 二者**没有合体过**。所以本功能需要**组合**这两个既有形状，而不是复用某个现成组件（这也意味着需要新增一个 `private` composable，但**不需要**新增 i18n 组）。

### 4.4 「既有布局切换组件」的搜索结果

| 检索 | 退出码 | 结论 |
|---|---|---|
| `GridCells` | 0（3 处） | 三处全是**写死**的常量（`160.dp` / `148.dp` / `160.dp`），没有一处来自用户输入 |
| `GridCells.Fixed` | 1（0 处） | 仓库从未用过 `Fixed` 列策略 ⇒ 「列表式 = 单列」需要新写 |
| `LazyVerticalGrid` | 0（9 处，含 import/KDoc） | 实际调用点 3 个：`HomeScreen.kt:328`、`LibraryScreen.kt:374`（专辑 tab）、`LibraryPlaylistsTab.kt:148` |
| `LazyColumn`（`ui/screen/`） | 0 | 实际调用点：`LibraryScreen.kt:292`（单曲 tab）、`SearchScreen.kt:238/449/536/593/654`（各 tab 的结果列表）、`UserScreen.kt:312`、`OfflineCacheOverlay.kt:266` |
| `rememberSaveable` | **1**（0 处） | 仓库**没有**任何 `rememberSaveable` ⇒ 「跨配置变更/进程重建保留」这条能力在本仓库**从未被使用过**（新功能若需要，是本仓库第一次） |
| `key(...)`（Compose 组合键） | 1（`ui/` 下 0 处） | 仓库**没有**用 `key(...)` 包容器的先例；唯一的 `key(` 命中是 `OfflineKeys.key(songId, level)`（`PlayerViewModel.kt:1428`，与 Compose 无关） |

**⇒ 没有任何"用户可选的布局"存在**；本功能是第一次引入「用户可选布局」这一概念。

**`SongCardStyle`（最强先例，引用原文）** —— 同一实体两套渲染：

```kotlin
// SongCard.kt:36-46
enum class SongCardStyle {
    LIST,
    COMPACT,
    GRID
}

@Composable
fun SongCard(
    song: SongItem,
    style: SongCardStyle = SongCardStyle.LIST,
    …
)
```

```kotlin
// SongCard.kt:83-88
    when (style) {
        SongCardStyle.LIST, SongCardStyle.COMPACT -> {
            val actualCoverSize = when {
                coverSize != Dp.Unspecified -> coverSize
                else -> 72.dp
            }
            // …（Row：封面 72dp + 标题/副标题，见 :92-180）
        }

        SongCardStyle.GRID -> {
            // …（Column：fillMaxWidth().aspectRatio(1f) 封面 + 标题/副标题，见 :190-244）
        }
    }
```

它缺的两半：**① 用户可切换**（`style` 全部来自调用点写死，22 处引用里 0 处来自用户输入）；**② 可持久化**（无任何 prefs 键）。所以本功能 = 「`SongCardStyle` 的形态」+「`PageTransitionSetting` 的持久化」+「一个新的切换按钮」。

---

## 5. 持久化方案

### 5.1 `ncrust_settings` 的既有写入者（规范来源）

`grep -rn "ncrust_settings" --include=*.kt app/src/main app/src/test` → 退出码 **0**、34 行，摘录：

| 文件:行 | 用途 |
|---|---|
| `ui/theme/ThemeManager.kt:38` | `PREFS_NAME = "ncrust_settings"`（主题色索引 / 主题模式） |
| `ui/i18n/LanguageManager.kt:25` | `PREFS_NAME = "ncrust_settings"`（语言 code） |
| `ui/theme/PageTransitionSetting.kt:65` | `PREFS = "ncrust_settings"`（页面转场开关） |
| `ui/theme/AccentSource.kt:26` | `PREFS_NAME = "ncrust_settings"`（主题色来源三选一） |
| `ui/theme/BackgroundImageManager.kt:38` | `PREFS` |
| `player/QualityLadder.kt:26` | `PREFS = "ncrust_settings"`（音质档位 + 迁移版本号） |
| `lyric/LyricsDisplayPrefs.kt:65` | `PREFS_NAME = "ncrust_settings"`（歌词显示 10+ 个键） |
| `cache/OfflineAudioCache.kt:59` | `PREFS = "ncrust_settings"`（`offline_cache_mb`） |
| `RotationSetting.kt:38` / `KeepScreenOnSetting.kt:32` | 根包下的两个开关 |
| `player/LiveUpdateNotifier.kt:54`、`reco/ArtistReco.kt:40`、`ui/player/AudioVisualizer.kt:37` | 其它 |
| `warmup/AppWarmup.kt:86` | 冷启动预热清单里显式列出该 prefs 文件 |
| `app/src/test/…/ui/theme/PageTransitionSettingTest.kt:50` | 单测里**逐字断言** `"ncrust_settings"` |

**⇒ 规范落点 = `ncrust_settings`，无第二个候选。**

### 5.2 键名约定与既有键清单

`snake_case`，按功能面加前缀（功能名在前、语义在后）：

| 键 | 类型 | 默认 | 出处 |
|---|---|---|---|
| `theme_color_index` | Int（**枚举索引**） | 0 | `ThemeManager.kt:39 / 59-62` |
| `theme_mode` | String（枚举名） | `"SYSTEM"` | `ThemeManager.kt:40 / 43-47` |
| `language_code` | String | `"zh-CN"` | `LanguageManager.kt:26 / 28-30` |
| `accent_source` | String（枚举名） | `"PRESET"` | `AccentSource.kt:27 / 33-37` |
| `page_transition_enabled` | Boolean | `true` | `PageTransitionSetting.kt:71 / 74` |
| `offline_cache_mb` | Int（**范围** 64..8192） | 512 | `OfflineAudioCache.kt:54-55 / 60 / 69-72` |
| `quality_ladder_version` + `wifi_quality` / `mobile_quality` | Int | v=1→2；wifi=3 / mobile=1 | `QualityLadder.kt:27-36` |
| `lyrics_word_animation` | Int（**枚举索引**） | `GRADIENT_SWEEP` | `LyricsDisplayPrefs.kt:68 / 133-136` |
| `lyrics_sweep_quality` | Int（枚举索引） | `AUTO=0` | `LyricsDisplayPrefs.kt:55 / 150-153` |
| `lyrics_romanization` / `lyrics_ttml_enabled` / `lyrics_ttml_first` / `lyrics_dynamic_font` | Boolean | false / true / true / false | `LyricsDisplayPrefs.kt:87 / 79 / 82 / 96` |
| `auto_rotate` | Boolean | `true` | `RotationSetting.kt:39 / 42` |

### 5.3 「枚举索引存成 Int」的先例（**有，3 个**）

| 先例 | 写法 | 非法值处理 |
|---|---|---|
| `theme_color_index` | `getInt(KEY, 0)`，再用 `themeColorPresets.getOrElse(index) { themeColorPresets[0] }` 取色 | **索引越界回落第 0 项**（`ThemeManager.kt:77-79`） |
| `lyrics_word_animation` | `getInt(KEY, GRADIENT_SWEEP)`，再过 `LyricsWordAnimationMode.normalize(raw)` | `fun normalize(raw: Int): Int = if (raw in GRADIENT_SWEEP..OFF) raw else GRADIENT_SWEEP`（`LyricsDisplayPrefs.kt:34`） |
| `lyrics_sweep_quality` | `getInt(KEY, AUTO)`，再过 `LyricsSweepQuality.normalize(raw)` | `if (raw in AUTO..EDGE) raw else AUTO`，「越界值一律回落 AUTO，绝不因为 prefs 被写坏而崩或不显示」（`LyricsDisplayPrefs.kt:54-55`） |

**枚举名存 String 的先例**（另一条可选路线）：`theme_mode` / `accent_source`：

```kotlin
// ThemeManager.kt:43-47
fun getSavedThemeMode(context: Context): ThemeMode {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_THEME_MODE, null)
    return runCatching { ThemeMode.valueOf(raw ?: "") }.getOrDefault(ThemeMode.SYSTEM)
}
```

```kotlin
// AccentSource.kt:33-37
fun getSavedAccentSource(context: Context): AccentSource {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_ACCENT_SOURCE, null)
    return runCatching { AccentSource.valueOf(raw ?: "") }.getOrDefault(AccentSource.PRESET)
}
```

**两种路线都合法**；`page_transition_enabled`（Boolean）与 `lyrics_sweep_quality`（Int + `normalize`）的**布尔脏键**处理另有一份显式实现：`LyricsDisplayPrefs.readBooleanSafely`（`LyricsDisplayPrefs.kt:201`，KDoc 在 `:158-165` 说明「SharedPreferences 对类型不符的键会抛 ClassCastException，脏键不该让播放器崩」）。

### 5.4 一次性迁移（one-shot migration）先例 —— **有**

**`QualityLadder.migrate`（`player/QualityLadder.kt:53-63`）**：

```kotlin
/** 当前档位表版本。1 = 旧 7 档（dolby 在索引 6），2 = 8 档（新增 jymaster）。 */
private const val CURRENT_VERSION = 2                                  // :33
/** 迁移前 dolby 所在的索引。 */
private const val OLD_INDEX_FIRST_SHIFTED = 6                          // :36

fun migrate(prefs: SharedPreferences) {                                // :53
    if (prefs.getInt(KEY_VERSION, 1) >= CURRENT_VERSION) return        // :54 ← 幂等早退
    val edit = prefs.edit()
    for (key in listOf(KEY_WIFI, KEY_MOBILE)) {                        // :56
        if (!prefs.contains(key)) continue
        val old = prefs.getInt(key, 0)
        if (old >= OLD_INDEX_FIRST_SHIFTED) edit.putInt(key, old + 1)   // :59
    }
    edit.putInt(KEY_VERSION, CURRENT_VERSION).apply()                  // :61
    Log.i(TAG, "quality ladder migrated to v" + …)
}
```

调用点：`NcrustApplication.onCreate` → `QualityLadder.migrate(getSharedPreferences(QualityLadder.PREFS, Context.MODE_PRIVATE))`（`NcrustApplication.kt:40`）。

**第二个先例（"消费老键一次"）**：`LyricsDisplayPrefs.readWordAnimation`（`lyrics/LyricsDisplayPrefs.kt:133-143`）：

```kotlin
fun readWordAnimation(prefs: SharedPreferences): Int {
    if (prefs.contains(KEY_WORD_ANIMATION)) {                          // :134 新键在 ⇒ 直接返回
        return LyricsWordAnimationMode.normalize(prefs.getInt(KEY_WORD_ANIMATION, …))
    }
    val migrated = if (prefs.getBoolean(KEY_WORD_BY_WORD_LEGACY, true)) { … }   // :137 老布尔键
    prefs.edit().putInt(KEY_WORD_ANIMATION, migrated).apply()          // :142 迁移结果立刻写回
    return migrated
}
```

「加字段 = 加迁移逻辑 = 加单测」是本仓库的硬约束（`AGENTS.md` 的 v1.9.3 歌词缓存迁移纪律；`PageTransitionSetting.kt:31-33` 明确引用）。

### 5.5 推荐落点（**可直接施工的方案**）

**推荐**：新建 `app/src/main/java/com/takahashirinta/ncrust/ui/screen/PlaylistLayoutSetting.kt`（与唯一消费者 `LibraryPlaylistsTab.kt` 同包同目录；结构照抄 `ui/theme/PageTransitionSetting.kt`，因为它是本仓库最新、最完整、且**带单测**的"UI 显示开关"先例；键与 prefs 名照抄 `QualityLadder` / `LyricsDisplayPrefs`）。

```kotlin
object PlaylistLayoutSetting {
    const val PREFS = "ncrust_settings"                 // §5.1 唯一落点
    const val KEY = "library_playlist_layout"           // snake_case，功能前缀 library_
    const val CARD = 0                                  // 卡片式（默认）
    const val LIST = 1                                  // 列表式
    const val DEFAULT = CARD

    /** 纯函数，可单测：越界 / 脏值一律回落默认（同 LyricsSweepQuality.normalize）。 */
    fun normalize(raw: Int): Int = if (raw == CARD || raw == LIST) raw else DEFAULT

    /** 读原始值：键不存在 / 类型不符 → null（同 PageTransitionSetting.readStored）。 */
    fun readStored(prefs: SharedPreferences): Int? = runCatching {
        if (prefs.contains(KEY)) prefs.getInt(KEY, DEFAULT) else null
    }.getOrNull()

    fun read(prefs: SharedPreferences): Int = normalize(readStored(prefs) ?: DEFAULT)
    fun read(context: Context): Int = read(prefs(context))
    fun write(context: Context, mode: Int) { … putInt(KEY, normalize(mode)).apply() }
}
```

**默认值必须由产品拍板 —— 这里没有"零行为变化"的选项**（任务前提的一个空洞）：

| 默认 | 谁零变化 | 谁变了 | 代价 |
|---|---|---|---|
| **`CARD = 0`（本报告推荐）** | 本地歌单段、ncm 段（面积最大、卡片数最多的一段） | **QQ 段由整行变卡片** | v2.3.0 为 QQ 行写过一段设计理由（`LibraryPlaylistsTab.kt:328-334`：「用整行而不是格子…让『这是 QQ 那一段』与上下两段在视觉上一眼可分」）—— 默认改卡片会**推翻这条既有设计决策**，必须显式记账 |
| `LIST = 1` | QQ 段 | 本地段 + ncm 段（含「＋」入口）全部从卡片变行 | 本页视觉变化面积最大；且 `NewPlaylistGridItem` 的三处复用点里有两处在本页 |

**单测落点**：`app/src/test/java/com/takahashirinta/ncrust/ui/screen/PlaylistLayoutSettingTest.kt`（该目录已存在，见 `CacheUsageTest.kt`）。用例照 `ui/theme/PageTransitionSettingTest.kt:48-138` 的六条：① 键名/默认值是持久化契约（`assertEquals("ncrust_settings", PREFS)` / `assertEquals("library_playlist_layout", KEY)`）；② 键不存在 ⇒ 默认；③ 写读往返；④ 越界值 `normalize`；⑤ **脏键（同键塞 String）回落默认、不抛**（照抄 `FakePrefs.putRaw`，`PageTransitionSettingTest.kt:99-115 / 150-191`）；⑥ 读函数无副作用（重复读 N 次不覆盖用户选择）。

**不需要**一次性迁移：这是**全新键**，没有"老键"要消费（与 `PageTransitionSetting` 的情形相同 —— 它的 KDoc `:53-56` 明确区分了「有老键要消费」与「没有老键」两种情形）。

---

## 6. 列表式规格（封面 dp / 行高 / 副标题字段，含出处）

### 6.1 主先例：`QqPlaylistInlineRow`（同文件、同实体类型，**必须**保持一致）

```kotlin
// LibraryPlaylistsTab.kt:336-386（节选）
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 8.dp),      // :344
    verticalAlignment = Alignment.CenterVertically,
) {
    Box(
        modifier = Modifier
            .size(48.dp)                                     // :349  ← 封面 48dp
            .clip(AppShapes.small)                            // :351  ← 8dp 圆角
            .background(colors.surfaceVariant),               // :352  ← 占位底板
    ) {
        … AsyncImage(
            modifier = Modifier.fillMaxSize().appCoverFrame(shape = AppShapes.small),  // :361-363
            contentScale = ContentScale.Crop)
    }
    Spacer(Modifier.width(12.dp))                             // :367  ← 封面↔文字 12dp
    Column(modifier = Modifier.weight(1f)) {
        MetroText(playlist.name, … style = typography.bodyLarge, maxLines = 1,
                  overflow = TextOverflow.Ellipsis)           // :369-375  ← 标题
        val subtitle = buildString {
            append(strings.playlistTrackCount(playlist.trackCount))   // :377
            if (playlist.isFavorite) { append(" · "); append(strings.playlistFavorite) }  // :378-381
        }
        MetroText(subtitle, color = colors.onSurfaceVariant, style = typography.bodySmall)  // :383
    }
}
```

| 规格项 | 值 | 出处 | 推导 |
|---|---|---|---|
| 封面边长 | **48dp** | `LibraryPlaylistsTab.kt:349` | 直接读 |
| 封面圆角 | `AppShapes.small` = `RoundedCornerShape(8.dp)` | 调用 `:351`、`:363`；定义 `AppShapes.kt:75` | 规则「≥160dp → large，<160dp → small」（`AppShapes.kt:69-74`、`AppVisualModifiers.kt:58`），48 < 160 |
| 封面描边 | 1dp `outlineVariant` | `AppVisualModifiers.kt:62-70`（`border(width = 1.dp, color = outlineVariant, shape = shape)`） | 直接读 |
| 无封面占位 | `clip(AppShapes.small)` + `background(surfaceVariant)` | `:351-352` | 本地歌单无封面字段（`LocalPlaylistModels.kt:49-62` 无 cover），占位底板必须先裁圆（注释 `:350`） |
| 行内 padding | `horizontal 16dp` / `vertical 8dp` | `:344` | 直接读 |
| **行高** | **64dp** = 48 + 8×2 | 推导自 `:344` + `:349` | 行高由封面决定（文字两行 ≈ 20+16=36dp < 48dp） |
| 封面↔文字间距 | 12dp | `:367` | 直接读 |
| 标题排版 | `typography.bodyLarge`，`onSurface`，`maxLines = 1`，`Ellipsis` | `:369-375` | 直接读 |
| 副标题排版 | `typography.bodySmall`，`onSurfaceVariant` | `:383` | 直接读 |
| 副标题内容 | `playlistTrackCount(trackCount)` + （收藏时）`" · " + playlistFavorite` | `:376-382` | 直接读 |
| 整行 span | `GridItemSpan(maxLineSpan)` | `:297` | 直接读 |
| 点击 | 挂在 `Row` 上的 `clickable`（**没有**按压回弹） | `:343` | 与卡片格子不一致的一点（见 §6.3） |

### 6.2 次先例：`SongCard` 的 LIST 形态（实体不同：歌曲）

| 规格项 | 值 | 出处 |
|---|---|---|
| 封面 | **72dp**（`coverSize` 未传时的默认）；搜索页显式传 `72.dp`，另一处 `56.dp` | `SongCard.kt:85-88`；`SearchScreen.kt:494 / 850` |
| 行 padding | `vertical 6.dp` ⇒ **行高 84dp** = 72 + 6×2 | `SongCard.kt:103` |
| 封面↔文字 | 14dp | `SongCard.kt:120` |
| 封面圆角 | `AppShapes.small`（72 < 160） | `SongCard.kt:117` + 注释 `:107-109` |
| 标题 / 副标题 | `bodyLarge` / `bodySmall`（副标题 = `艺人 · 专辑  时长  · 音源`） | `SongCard.kt:137 / 149-160` |
| 右侧 actions | 可选 `RowScope` 槽（`actions: (@Composable RowScope.() -> Unit)?`），行尾 16dp | `SongCard.kt:51 / 174-179` |

### 6.3 统一两个源时必须处理的三处差异（**这些是任务描述里没提的**）

| # | 差异 | 证据 | 影响 |
|---|---|---|---|
| 1 | **ncm/本地格子有「播放全部」▶，QQ 行没有** | `LibraryScreen.kt:468-471`（`PlayAllButton` 覆盖封面右下，`padding(6.dp)`，直径 36dp 默认 —— `SongCard.kt:291`）；`QqPlaylistInlineRow` 全文无 ▶（`:336-386`） | 列表式下若保留 ▶，需要一个右侧 actions 槽（宽 36dp + 间距）；若不保留，ncm/本地就**丢了一个既有入口**（功能回退，铁律 19 类问题）。**必须产品决策** |
| 2 | **本地歌单没有封面、也没有曲目数** | `LocalPlaylist` 字段：`key/name/createdAt/updatedAt/lastSyncedAt/dirId` + `hasRemoteSource`（`LocalPlaylistModels.kt:49-65`）—— 无 `cover`、无 `trackCount`；KDoc 明确「曲目数**不在这里读 prefs** —— 列表渲染时对每个格子做一次 SharedPreferences 读会让滚动带上 IO」（`LibraryPlaylistsTab.kt:389-392`） | 列表式下本地行必须沿用 **48dp 占位色块 + 居中图标**（`Icons.Default.PlaylistPlay`，`sizeDp = 32.dp`，`tint = primary@45%`，`LibraryPlaylistsTab.kt:404-420`），副标题只能是徽标 `localPlaylistSync / localPlaylistLocalBadge`（`:430-436`） |
| 3 | **两个源的曲目数文案是两条不同的 i18n 条目，中文下差一个空格** | ncm：`strings.trackCount` → zh `"${it}首"`（`zh_CN.kt:227`）；QQ：`strings.playlistTrackCount` = `playlists.trackCount` → zh `"$n 首"`（`zh_CN.kt:375`） | 统一列表式后两源并排显示会出现「12首」与「12 首」。**必须二选一**（推荐统一用 `playlists.trackCount`，因为它已是 QQ 行的既有取值，且带空格更符合中文排版） |

---

## 7. 卡片式规格（列数 / 封面比例 / 标题 / 副标题，含出处）

### 7.1 `PlaylistGridItem` 定义（**在 `LibraryScreen.kt`，不在 tab 文件里**）

```kotlin
// LibraryScreen.kt:448-478
@Composable
fun PlaylistGridItem(
    playlist: PlaylistApi.PlaylistInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlayAll: () -> Unit
) {
    val strings = LocalStrings.current
    Column(modifier = modifier.appPressScale().clickable { onClick() }) {          // :456
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {                   // :457  ← 1:1 封面
            AsyncImage(
                model = CoverUrls.small(playlist.coverImgUrl),
                contentDescription = strings.playlistCoverDesc,
                modifier = Modifier.fillMaxSize()
                    .appCoverFrame(shape = AppShapes.large),                        // :465  ← 16dp 圆角 + 1dp 描边
                contentScale = ContentScale.Crop
            )
            PlayAllButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),        // :469  ← ▶ 右下
                onClick = onPlayAll
            )
        }
        Spacer(Modifier.height(6.dp))                                                // :473
        MetroText(playlist.name, …, style = …bodyMedium, maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.padding(horizontal = 6.dp))                    // :474  ← 标题
        MetroText(strings.trackCount(playlist.trackCount), …,
                  style = …bodySmall, modifier = Modifier.padding(horizontal = 6.dp))// :475  ← 副标题
        Spacer(Modifier.height(6.dp))                                                // :476
    }
}
```

### 7.2 规格表

| 规格项 | 值 | 出处 |
|---|---|---|
| 列策略 | `GridCells.Adaptive(minSize = 160.dp)` | `LibraryPlaylistsTab.kt:149` |
| 实际列数 / 格宽（窄屏） | 内容宽 **360dp**（`ResponsiveContent` 在 `<600dp` 时 `widthIn(max = 360.dp)`，`ResponsiveContent.kt:52-64`）− 2dp 间距 ⇒ **2 列 × 179dp** | 推导；且源码注释逐字给出同一结论：「窄屏 360dp 内容宽下是 2 列 179dp」（`LibraryScreen.kt:462`） |
| 列数（宽屏 ≥600dp） | `ResponsiveContent` 铺满内容栏 + 左右各 24dp（`ResponsiveContent.kt:43-51`）⇒ 列数随宽度增长（自适应） | 读码 |
| 封面比例 | `Modifier.fillMaxWidth().aspectRatio(1f)` | `LibraryScreen.kt:457`；本地格子 `LibraryPlaylistsTab.kt:407`；「＋」格子 `LibraryScreen.kt:422` |
| 封面圆角 | `AppShapes.large` = `RoundedCornerShape(16.dp)`；规则「≥160dp → large」（单元格 ≥160dp） | 调用 `LibraryScreen.kt:465`；定义 `AppShapes.kt:90`；规则注释 `LibraryScreen.kt:461-462`、`AppShapes.kt:84-89` |
| 封面描边 | 1dp `outlineVariant` | `AppVisualModifiers.kt:62-70` |
| ▶ 播放全部 | `PlayAllButton`，直径默认 **36dp**，对齐 `BottomEnd` + `padding(6.dp)` | 调用 `LibraryScreen.kt:468-471`；定义 `SongCard.kt:288-317` |
| 标题 | `bodyMedium`、`onBackground`、`maxLines = 1`、`Ellipsis`、左右 6dp、上间距 6dp | `LibraryScreen.kt:473-474` |
| 副标题 | `bodySmall`、`onSurfaceVariant`、`strings.trackCount(trackCount)`、左右 6dp | `LibraryScreen.kt:475` |
| 格间距 | `horizontalArrangement = spacedBy(2.dp)`、`verticalArrangement = spacedBy(2.dp)` | `LibraryPlaylistsTab.kt:151-152` |
| 按压反馈 | `Modifier.appPressScale()`（1.05×，`AppMotion.pressScale` 弹簧 0.45/900） | `LibraryScreen.kt:456`；`AppVisualModifiers.kt:100-103`；`AppMotion.kt:148-151` |
| 入场/移动动画 | `animateItem(fadeIn = tween(150, MetroDefault), placement = tween(220, MetroDefault), fadeOut = tween(120, MetroDefault))`（本地歌单与「＋」有；ncm `PlaylistGridItem` **没有**） | `LibraryPlaylistsTab.kt:167-171 / 179-183`；ncm 侧 `:217-230` 无 `animateItem` |
| 「＋」格子 | 同形：`aspectRatio(1f)` + `AppShapes.large` + `surfaceVariant` 底 + 居中 `Icons.Default.Add`(`sizeDp = 40.dp`, `tint = primary`) + 标题、**无副标题** | `LibraryScreen.kt:409-446` |
| 本地歌单格子 | 无封面 ⇒ `aspectRatio(1f)` + `AppShapes.large` + `surfaceVariant` + 居中 `Icons.Default.PlaylistPlay`(`sizeDp = 32.dp`, `tint = primary@45%`)；标题 `bodyMedium`；副标题 = `localPlaylistSync` / `localPlaylistLocalBadge`（`bodySmall`） | `LibraryPlaylistsTab.kt:394-439` |
| 标题/副标题字色 | 卡片标题 `onBackground`（行式标题是 `onSurface`） | `LibraryScreen.kt:474` vs `LibraryPlaylistsTab.kt:371` |

---

## 8. 「切换后不刷新数据」的可行性

### 8.1 数据在谁手里（这是"不刷新"的前提）

| 数据 | 持有者 | 初始化 | 刷新触发键 |
|---|---|---|---|
| ncm 歌单 `playlists` | `LibraryScreen` | `remember { mutableStateOf(emptyList()) }`（`:117`） | `LaunchedEffect(selectedCategory)`（`:155-161`）内 `loadPlaylists()`；`LaunchedEffect(refreshTrigger)`（`:171-176`） |
| 本地歌单 `localPlaylists` | `LibraryScreen` | `LocalPlaylistStore.readPlaylists(context)`（`:122`） | `LaunchedEffect(selectedCategory)`（`:155`）/ `LaunchedEffect(localPlaylistsTick)`（`:181-183`） |
| QQ 歌单 `qqPlaylists` + 加载闸门 | **`LibraryPlaylistsTab` 自己** | `remember { mutableStateOf(emptyList()) }`（`:107`） | `LaunchedEffect(ownerId, qqReloadTick)`（`:115-139`），网络调用 `QqPlaylistRepository.loadList(forceRefresh = qqReloadTick > 0)`（`:120`） |

**结论**：只要布局开关**不进上面这 5 个 `LaunchedEffect` 的 key**、也不改任何 state 的 identity，切换就**只影响渲染**，一个网络请求都不会发。`LaunchedEffect(Unit)`（`:165`）与 `LaunchedEffect(refreshTrigger)`（`:171`）与布局无关。

### 8.2 但"换容器"会丢状态（这是最容易踩的坑）

**已取证**：`LibraryPlaylistsTab.kt:148-155` **不传 `state`** ⇒ `LazyVerticalGrid` 内部走默认参数：

```
javap -c -p … LazyGridDslKt | grep rememberLazyGridState
→ 568: invokestatic // LazyGridStateKt.rememberLazyGridState:(II…)LazyGridState;
   所属方法 = public static final void LazyVerticalGrid(…)
```

⇒ 默认 `state` 是**现场 `remember` 出来的**，绑在 `LazyVerticalGrid` 这个 composable 的槽位上。

**因此**：

| 实现方式 | 滚动位置 | 是否触发数据刷新 | 判定 |
|---|---|---|---|
| `if (listMode) LazyColumn{…} else LazyVerticalGrid{…}` | ❌ 丢掉（两个不同 composable 占同一槽位，`remember` 的键不同 ⇒ 新状态从 index 0 开始） | 否（state 与 effect 都在更上层） | 不推荐 |
| `key(layoutMode) { LazyVerticalGrid{…} }` | ❌ 同上，且**更明确地强制重建** | 否（`key` 只作用于其子树） | 不推荐 |
| 把 `key(layoutMode)` / `if` 包到**整个 `LibraryPlaylistsTab` 的调用点**（`LibraryScreen.kt:353-365`） | ❌ 丢 | ✅ **会**：`remember { PlaylistLoadCoordinator() }`（`LibraryPlaylistsTab.kt:106`）与 `qqHasLoadedOnce`（`:111`）一起重置 ⇒ `LaunchedEffect(ownerId, qqReloadTick)` 重跑 ⇒ 一次真实 QQ 网络刷新 | **明确违反需求** |
| **容器不变，只切 `columns` + item 内部形态**（推荐） | ✅ 保留（`LazyGridState` 由同一 composable 的同一槽位持有） | 否 | 推荐 |

**为什么"只切 `columns`"是安全的**：`GridCells.Fixed` 与 `GridCells.Adaptive` 都是带 `equals`/`hashCode`/`$stable` 的**稳定值类型**（`javap` 输出见 §2.4）⇒ `columns` 变化只是让网格**重新测量**，不改变 composable 的 identity，也不重建 `LazyGridState`。

推荐写法：

```kotlin
val mode = PlaylistLayoutSetting.read(context)          // 或由上层 hoist 进来
LazyVerticalGrid(
    columns = if (mode == PlaylistLayoutSetting.LIST)
        GridCells.Fixed(1)                              // 列表式：单列 ⇒ 天然整行
    else
        GridCells.Adaptive(minSize = 160.dp),           // 卡片式：现状
    … 其余参数一字不动 …
) {
    … item(key = "hdr-layout", span = { GridItemSpan(maxLineSpan) }) { LayoutSwitchRow(…) }
    … 各 item 内部按 mode 二选一（卡片 = 现状；列表 = 新的 64dp 行）…
    // QQ 段在列表式下保持 span = maxLineSpan（本来就是整行）；卡片式下改成默认 span
}
```

### 8.3 仓库现有的「同类数据两种容器」模式 —— **没有**

| 检索 | 结果 |
|---|---|
| `key(layout…` / `key(style…` / `key(mode…` | 0 命中（退出码 1） |
| Compose `key(...)` 包容器 | 0 命中（`ui/` 下唯一 `key(` 是 `OfflineKeys.key(...)`，`PlayerViewModel.kt:1428`） |
| 最接近的既有手段 | **把 `state` 提到会被替换的子树之上**：`LibraryScreen.kt:186` `val songListState = rememberLazyListState()`（在 `AnimatedContent` 之外）→ `:294` `LazyColumn(state = songListState, …)`；而 `AnimatedContent`（`:268`）在切 tab 时会把整棵子树换掉 |

> ⚠️ 顺带发现（**推断，未上机**）：因为 `LibraryPlaylistsTab` 整个挂在 `AnimatedContent` 的内容 lambda 里（`LibraryScreen.kt:285 / 350-366`），**今天**从「歌单」tab 切走再切回来，这个 tab 的网格滚动位置与 QQ 首载状态（`qqHasLoadedOnce` 等，`:107-113`）都会重置 ⇒ 会再拉一次 QQ 列表。这是**既有行为**，与本次改动无关，但它说明「本页对『重新挂载』的代价很敏感」—— 不要在布局切换里引入任何形式的重新挂载。

### 8.4 benchmark 模块覆盖（release 包验证口径）

| 基准类 | 覆盖 | 迭代（源码逐值） | 出处 |
|---|---|---|---|
| `StartupBenchmark` | 冷启动 | `iterations = BenchArgs.iterations(default = 8)`；`StartupMode.COLD` | `StartupBenchmark.kt:33-34`；分派 `run_benchmark.sh:191/197` |
| `HomeScrollBenchmark` | **首页**纵向主列表滚动帧时 | `default = 6`；`StartupMode.COLD` | `HomeScrollBenchmark.kt:37-38`；分派 `run_benchmark.sh:192/199` |
| `SettingsScrollBenchmark` | **设置（用户）页**滚动帧时 | `default = 6`；`StartupMode.COLD` | `SettingsScrollBenchmark.kt:63 / 67`；分派 `run_benchmark.sh:194/200`；KDoc `:20-51` |
| `ExpandPlayerBenchmark` | 播放器展开/收起 | `default = 6`；`StartupMode.HOT` | `ExpandPlayerBenchmark.kt:34 / 38`；分派 `run_benchmark.sh:195/201` |
| `BaselineProfileGenerator` | 7 条旅程（含 `journeyLibrary` = **库页「单曲」tab** 滚两屏） | `DEFAULT_ITERATIONS = 15` | `BaselineProfileGenerator.kt:397 / 132 / 42-52 / 199-203` |

| 检索 | 退出码 | 结论 |
|---|---|---|
| `grep -rn "歌单" benchmark/src/main/java/…/benchmark/` | **1** | 基准模块里**没有任何歌单相关**代码 |
| `grep -rn "playlist\|library" benchmark/src/…` | 0 | 只有 `journeyLibrary`（库页**单曲** tab，`BaselineProfileGenerator.kt:200-203`「进『库』页（默认『单曲』tab）」） |

**⇒ 没有任何基准覆盖「歌单 tab 的滚动」**。若本次要出性能数据，需要**新增一条基准**（照 `SettingsScrollBenchmark` 的形状：`By.text("库")` 切页 → 点「歌单」tab → `By.scrollable(true)` 取最大可见高度的节点 → swipe 循环），并且**必须跑在 release 包上**：

- `benchmark/run_benchmark.sh` 里有一段**硬断言**：被测应用若是 debuggable 直接 `exit 3`（`run_benchmark.sh:117-135`，注释「铁律 16『性能验证必须用 release 包』在 v2.5.4 之前只是一句警告」）；要强行用 debug 包必须 `ALLOW_DEBUG_BUILD=1`，且「数字**不得**写进发布说明当基线」。
- 口径写死在 `AppMotion.kt:257-262`：「必须…用 `adb shell dumpsys gfxinfo <pkg> framestats` 记录**开启时**的 90 分位帧时间，并与关闭时对照。**模拟器结论不构成证据，debug 包数据也不构成证据**」。
- 判据样例（可照抄）：`frameDurationCpuMs` p90 的 Δ ≤ 1.0ms 且不超过噪声带 2 倍、`janky%` Δ ≤ 2pp（`SettingsScrollBenchmark.kt:35-36`）。

---

## 9. 性能与动效（既有 token）

### 9.1 动效 token 文件：**有**，`ui/theme/AppMotion.kt`（290 行，自述"唯一落点"）

```kotlin
// AppMotion.kt:26
 * v2.5.0 · A：全局动效规格（**唯一落点**）。
```

| 用途 | token | 值 | 出处 |
|---|---|---|---|
| **内容替换 / 面板切换**（"content change"） | `spatialDefault` | `spring(dampingRatio = 0.8f, stiffness = 380f)`；注释逐字：「卡片入场、**面板切换的默认值**」 | `AppMotion.kt:92-93` |
| **整页内容替换** | `spatialSlow` | `spring(0.8f, 200f)`；注释逐字：「大面板入场（宽屏侧栏、**整页内容替换**）」 | `AppMotion.kt:95-96` |
| 非物理属性（透明度/颜色类）通用过渡 | `effects` | `tween(200, easing = FastOutSlowInEasing)` | `AppMotion.kt:183-184` |
| 极短反馈 | `effectsFast` | `tween(100, easing = LinearEasing)` | `AppMotion.kt:186-187` |
| 列表 item 入场 | `listItemEnter` | `tween(220, FastOutSlowInEasing)` + `LIST_ITEM_RISE_DP = 8.dp` | `AppMotion.kt:222 / 225` |
| 按压回弹 | `pressScale` + `PRESS_SCALE` | `spring(0.45f, 900f)` + `1.05f`；经 `Modifier.appPressScale()` 消费（`graphicsLayer` 读值，零重组） | `AppMotion.kt:148-151`；`AppVisualModifiers.kt:100-129` |

**「开关类」专用 token（Kanesumi 侧）** —— 与本功能语义最贴的一条：

```kotlin
// Kanesumi-sec-a/kanesumi-anim/…/sokuou/Sokuou.kt:90-94（注释）与 :109
    // 开关滑块 / 短反馈（对齐 UWP toggle 的 220ms）。
    val ToggleFlip: AnimationSpec<Float> = tween(
        durationMillis = 220,
        easing = MetroCubic
    )
…
object SokuouTweens {
    val ToggleFlip: TweenSpec<Float> = tween(220, easing = MetroCubic)   // :109
}
```

### 9.2 同一页已有的"内容替换"动效先例（本页外层）

```kotlin
// LibraryScreen.kt:265-284
// Tab 切换用 AnimatedContent 做方向感知的横向滑入。
// 距离 1/16 屏宽（比 NavGraph 的 1/8 更轻——同页内切 tab，不应有"翻页"的重量感）；
// 200ms MetroDefault 曲线。
AnimatedContent(
    targetState = selectedCategory,
    transitionSpec = {
        …
        (slideInHorizontally(animationSpec = tween(200, easing = MetroDefault), …) +
         fadeIn(animationSpec = tween(160, easing = MetroDefault))) togetherWith
        (slideOutHorizontally(animationSpec = tween(200, easing = MetroDefault), …) +
         fadeOut(animationSpec = tween(160, easing = MetroDefault)))
    }, …)
```

**建议**（本报告立场）：

1. 布局切换**不要**做容器级动画（不要slide/fade 整个网格）—— 它与 `animateItem` 的 placement 动画会叠加，且滚动位置会视觉跳。
2. item 级联用仓库已有的 `animateItem`（`LibraryPlaylistsTab.kt:167-171`：`fadeIn 150 / placement 220 / fadeOut 120`，曲线 `MetroDefault`）。
3. 按钮自身的按压反馈用 `Modifier.appPressScale()`（`AppMotion.pressScale`），**不要**新写 `animateFloatAsState`（违反「GPU 零重组」，`AppMotion.kt:69-73`）。
4. 若产品坚持要一个"切换"过渡：用 `AppMotion.spatialDefault`（注释即"面板切换的默认值"）或 `effects`（200ms），**不要**自己拍 `tween`（`AGENTS.md`/Kanesumi 铁律：「用 `:kanesumi-anim` 的 Sokuou 预设，不散写 `tween(300, CubicBezierEasing(...))`」；`AppMotion.kt:48-57` 说明本版**有意不**批量改存量 41 处 `tween`，但**新增**动效不许散落）。

### 9.3 release 包验证口径（本功能若出数据）

| 要求 | 出处 |
|---|---|
| 必须在 release 包上测；debug 数据不得当基线 | `run_benchmark.sh:117-135`（硬断言 `exit 3`）；`AppMotion.kt:260-262` |
| 模拟器结论不构成证据 | `AppMotion.kt:261` |
| 帧时间口径 | `adb shell dumpsys gfxinfo <pkg> framestats` 的 **p90**；判据 Δ≤1.0ms、janky% Δ≤2pp | `AppMotion.kt:259-262`；`SettingsScrollBenchmark.kt:34-36` |
| 本页**没有**现成基准 | §8.4（`grep 歌单 benchmark/src` 退出码 1） |

---

## 10. 文案落点（`Strings` 预算与分组，含确切数字）

### 10.1 预算与当前值（确切数字）

| 项 | 值 | 出处 |
|---|---|---|
| `Strings` 主构造器**预算（硬上限）** | **150** | `StringsConstructorBudgetTest.kt:73`（`private val maxPrimaryParams = 150`） |
| 预警线（不失败，只打印 WARN） | 140 | `:81` |
| 单个嵌套组硬上限 | 120 | `:84` |
| 组预警线 | 80 | `:87` |
| **`Strings` 主构造器当前精确期望值** | **135** | `:176-187` 用例 `v2_5_5 之后 Strings 主构造器稳定在 135`，`:182` `assertEquals(135, primaryParams(clazz))` |
| 当前 dex 槽位 | **142** = `1 + 135 + 5 + 1` | `:185` `assertEquals(142, dexSlots(135, true))` |
| 余量 | **113** 槽 | `:186` `assertTrue("余量不足 100 个槽位", 255 - dexSlots(135, true) >= 100)` |
| dex 公式 | `slots = 1 + N + ceil(N/32) + 1 ≤ 255` | `:104-106`；`Strings.kt:13` 同一算式 |

**独立复核（命令取证，非引用测试）**：对仓库内已编译的 release class 数出 `primary ctor params = 135`、`dex slots = 142`（命令与一致性自证见 §2.4）。

### 10.2 规则：新文案进具名组，不进外层构造器

| 出处 | 原文要点 |
|---|---|
| `Strings.kt:47-51` | 「长期防线：`StringsConstructorBudgetTest` 会加载本类并断言主构造器参数 **< 150**，同时对**每一个**嵌套组做同样的监控。**加文案请走「往组里加字段」**，需要新组就在类体里补转发属性。**不要**直接往主构造器加参数。」 |
| `Strings.kt:36-45` | 搬走的每条都在**类体**里留一条同名转发属性（`val qualitySectionTitle: String get() = settings.qualitySectionTitle`）。转发属性不进构造函数，**不占 dex 槽**。**新代码可以直接写 `strings.settings.qualitySectionTitle`** |
| `StringsConstructorBudgetTest.kt:150-155` | 断言失败信息：「请把新文案放进语义相符的嵌套组，并在类体里补一条转发属性保住调用点」 |
| `StringsConstructorBudgetTest.kt:89-102` | **`family` 名单**：新增**组**时必须同步这个列表（否则新组不被监控）。现有 11 项：`Strings`、`OfflineStrings`、`SourceStrings`、`PlaylistsStrings`、`TagsStrings`、`LocalPlaylistStrings`、`QueueStrings`、`MotionStrings`、`SettingsStrings`、`AboutStrings`、`PlayerUiStrings` |
| `StringsConstructorBudgetTest.kt:170-175` | 「单钉一个精确值而不是『< 150』是有意的：这条会在有人**顺手**往主构造器里加参数时立刻变红」 |

### 10.3 近期同类先例（**引用真实 diff**）

`git log --oneline -S "pageTransitionLabel" -- …/Strings.kt` → `516e005`（退出码 0）。该提交往**新组**里加了**两条**文案 —— 正是本次「两条新标签」的形状：

```diff
diff --git a/app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt b/…
@@ -196,6 +196,21 @@ data class Strings(
      val queue: QueueStrings,
 
+    /**
+     * v2.5.1 · F：**页面转场（动效）文案组**（[MotionStrings]）。
+     * …
+     */
+    val motion: MotionStrings,
+
@@ -963,3 +978,22 @@ data class QueueStrings(
+/**
+ * v2.5.1 · F：**页面转场文案组**。
+ * …
+ */
+data class MotionStrings(
+    /** 设置页开关标题：「页面切换动效」。 */
+    val pageTransitionLabel: String,
+    /** 设置页开关说明：「关闭可提升低端机流畅度」。 */
+    val pageTransitionDescription: String,
+)

diff --git a/app/src/main/java/com/takahashirinta/ncrust/ui/i18n/zh_CN.kt b/…
@@ -415,4 +415,8 @@ val zhCN = Strings(
             queueAddToNextStarted = "队列为空，已直接播放",
         ),
+        motion = MotionStrings(
+            pageTransitionLabel = "页面切换动效",
+            pageTransitionDescription = "关闭可提升低端机流畅度",
+        ),
 )
```

调用点（**直接读组，没有走转发属性**）：

```kotlin
// UserScreen.kt:526-531
SettingSwitchRow(
    title = strings.motion.pageTransitionLabel,
    description = strings.motion.pageTransitionDescription,
    checked = pageTransitionEnabled,
    onCheckedChange = onPageTransitionChange
)
```

> 该提交信息还记了一笔重要的账：「文案进新的嵌套组 `MotionStrings`。`Strings` 主构造器在 v2.5.0 已降到 244（腾 2 花 1），本组用掉**最后一个空位** ⇒ 回到 245 = 天花板，余量现在是 **0**。」—— 这正是 v2.5.3 拆分 120 条文案（245 → 128）的直接动因。**今天（135）不需要再腾位置**。

### 10.4 本次推荐落点

**推荐：落进已存在的 `PlaylistsStrings`（`Strings.kt:863-898`），不新建组、不动主构造器。**

| 项 | 值 | 出处 / 说明 |
|---|---|---|
| 组类 | `com.takahashirinta.ncrust.ui.i18n.PlaylistsStrings` | `Strings.kt:863`；已在 `family` 名单（`:94`） |
| 当前参数数 | **17**（`qqPlaylistsTitle` … `trackCount`） | 源码 `:864-897` 逐行计数 = 17；`javap` 对编译产物复核 = **17** |
| 组上限 / 预警 | 120 / 80 | `StringsConstructorBudgetTest.kt:84 / 87` ⇒ 加 2 条后 19，**远低于预警线**，无需拆层 |
| 为什么是这个组 | 该组的语义就是「歌单相关文案」，且本页现在读的 `playlistRefresh` / `playlistEmpty` / `playlistTrackCount` 全在里面（转发属性 `Strings.kt:449-465`） | 读码 |
| 主构造器影响 | **0**（组已存在于构造参数里，`Strings.kt:121`） | ⇒ **不需要**改 `StringsConstructorBudgetTest` 的 135 断言 |
| 需要改的文件 | `Strings.kt`（加 2 个 `val`）+ **8 个语言文件**（各加 2 行实参）：`zh_CN.kt:358-376` / `zh_TW.kt` / `en.kt:374` / `jp_JP.kt` / `jp_MY.kt` / `ko_NK.kt` / `de_DE.kt` / `ru_RU.kt` | 8 个 locale 文件各约 435-437 行（`wc -l`） |
| 推荐属性名与 zh 值 | `layoutCard = "卡片式"` / `layoutList = "列表式"`（两态分段/两个图标按钮的 contentDescription 都可用同一对） | 名词短语，与 `MotionStrings` KDoc 的文案契约同构（`pageTransitionLabel` 是名词短语） |
| 访问写法 | 直接 `strings.playlists.layoutCard`（**不必**加转发属性；`Strings.kt:43` 明确新代码可以直接读组） | 若为了与既有调用点风格一致也可加两条 `val playlistLayoutCard: String get() = playlists.layoutCard`（类体，**不占 dex 槽**） |
| 是否新建组 | **不需要**（新建组还要改 `family` 名单，多一处易漏点） | `StringsConstructorBudgetTest.kt:89-102` |

**注意**：若最终做成「一个图标按钮、两态」而不是「两个分段标签」，仍需要**两条** contentDescription（`strings.playlists.layoutSwitchToCard` / `…ToList`）—— 因为 `RotationToggleButton` 的先例就是按当前状态给不同 `contentDescription`（`FullPlayerControls.kt:558`、`:586`）。**无论哪种形态都是 2 条文案。**

---

## 11. 未验证项

### 11.1 未执行的动作（**明确声明**）

| 未做 | 原因 / 影响 |
|---|---|
| **未运行 gradle**（`./gradlew test` / `assembleDebug`） | 本探针只做静态阅读 + 对**既有产物**的 `javap`。因此 §10.1 的「135」有两条独立证据（测试源码断言 + `javap` 实测），但**没有**跑过 `StringsConstructorBudgetTest` 本体 |
| **未编译、未上机、未截图** | 所有 dp / 列数结论来自源码与推导，**没有真机像素复核** |
| **未做帧时间测量** | §9.3 只给口径，无数据 |
| **未验证"切 `columns` 后滚动位置在视觉上合理"** | 见 §11.2 第 1 条 |
| 未改动任何源码、未 commit | 符合任务纪律（`git status --short` 为空） |

### 11.2 属于【推断】而非【已验证】的结论

| # | 推断 | 置信度与依据 | 怎么验证 |
|---|---|---|---|
| 1 | 切 `columns`（`Adaptive(160.dp)` ↔ `Fixed(1)`）后 `LazyGridState` 保留、不跳回顶部 | 依据：同一 composable 槽位 + `GridCells.*` 是稳定值类型（javap 有 `equals`/`hashCode`/`$stable`）。**但**：行高从 179dp 变成 64dp，`firstVisibleItemScrollOffset` 的像素含义随之改变 ⇒ 「index 保留、但停在 item 的哪一段」未验证 | 真机：滚到第 20 项 → 切换 → 读 `LazyGridState.firstVisibleItemIndex`（可临时打日志）或直接目视 |
| 2 | 「切成 `LazyColumn` 会丢滚动位置」 | 依据：默认 `state` 是现场 `remember`（javap 证据）+ Compose 位置记忆按 composable 分组。**未**在本仓库实测 | 同上，但换容器实现做 A/B |
| 3 | 「切 tab 走再回来会重拉 QQ 列表」 | 依据：`AnimatedContent` 会 dispose 旧内容（`LibraryScreen.kt:268`）+ `LaunchedEffect(ownerId, qqReloadTick)`（`LibraryPlaylistsTab.kt:115`）+ `remember` 状态在同一 composable 内。**未**上机抓包/看日志 | 真机 logcat 过滤 `QqPlaylistRepository` / `PlaylistLoadCoordinator`，切 tab 后看是否再次发起 |
| 4 | 列表式下若保留 ▶，「右侧 actions 槽」的具体宽度 | 依据：`PlayAllButton` 默认 36dp + 6dp padding（`SongCard.kt:291`、`LibraryScreen.kt:469`）。未做整行宽度预算 | 真机极长歌单名下看省略是否可接受 |
| 5 | 卡片式在宽屏的实际列数 | 依据：`ResponsiveContent.kt:43-51` 铺满 + 24dp padding；`GridCells.Adaptive` 自算 | 平板真机/模拟器测（本仓库纪律：模拟器不能替代真机结论） |

### 11.3 任务前提中**不准确**的两处（需要上游确认）

| # | 任务原文 | 实际情况 | 影响 |
|---|---|---|---|
| 1 | 「the choice applies to **both sources** uniformly」/「qm 与 ncm」 | 这一页是**三个**按源分区：`本地歌单`（`:157-186`）、`ncm`（`:189-232`）、`qm`（`:235-301`）；KDoc 三行表也写明三块（`:64-70`）。**本地歌单段同样是网格格子**（`LocalPlaylistGridItem`） | 「两源统一」若照字面实现，会出现「本地卡片 + 两源列表」的**半切换**页面。建议改成**三源统一**（切换对象 = 本页所有条目） |
| 2 | 隐含「切换是纯增量、默认可以零行为变化」 | 今天的形态**本身就是混合的**（卡片 + QQ 整行同时存在）⇒ **不存在**"两源都零行为变化"的默认值。默认 `CARD` 会改 QQ（推翻 `:328-334` 为 QQ 行写下的设计理由）；默认 `LIST` 会改本地 + ncm（面积最大） | 默认值必须由产品拍板，并记账（见 §5.5 表）。**不能**用「与今天一致」当理由绕过 |
| （附） | 「Today the tab is a single `LazyVerticalGrid` where the ncm playlist section renders cards and the QQ playlist section renders full-width rows」 | ✅ **完全准确** | 无需更正 |
| （附） | 「the page header lives in `LibraryScreen.kt`」 | ✅ 准确；且这一页**连页头都没有**（`:157` 就是第一个 item） | 无需更正，但据此才能定位切换按钮落点 |
| （附） | 「`PlaylistGridItem` … it may be in `LibraryScreen.kt` or another file」 | ✅ 在 **`LibraryScreen.kt:448-478`**（`NewPlaylistGridItem` 也在同一文件 `:409-446`） | 施工需同时改两个文件 |

---

## 附：本次探针用到的全部关键 `file:line` 索引

| 主题 | 位置 |
|---|---|
| 歌单 tab 容器 | `app/src/main/java/com/takahashirinta/ncrust/ui/screen/LibraryPlaylistsTab.kt:148-155` |
| 三源分区 KDoc | `LibraryPlaylistsTab.kt:59-85` |
| 逐 item | `LibraryPlaylistsTab.kt:157-301` |
| QQ 整行 | `LibraryPlaylistsTab.kt:335-386` |
| 本地卡片 | `LibraryPlaylistsTab.kt:394-439` |
| `PlaylistGridItem` / `NewPlaylistGridItem` / `LibraryAlbumGridItem` | `app/src/main/java/com/takahashirinta/ncrust/ui/screen/LibraryScreen.kt:448-478 / 409-446 / 480-509` |
| 页头 + 分类 tab + AnimatedContent | `LibraryScreen.kt:244-263 / 268-284 / 285-397` |
| 单曲 tab 的 state 提升先例 | `LibraryScreen.kt:186` + `:292-296` |
| 响应式内容宽 | `app/src/main/java/com/takahashirinta/ncrust/ui/ResponsiveContent.kt:28-65` |
| `SongCardStyle` 两形态 | `app/src/main/java/com/takahashirinta/ncrust/ui/components/SongCard.kt:36-40 / 44-46 / 83-246` |
| `PlayAllButton` | `SongCard.kt:288-317` |
| `appCoverFrame` / `appPressScale` | `app/src/main/java/com/takahashirinta/ncrust/ui/components/AppVisualModifiers.kt:62-70 / 100-129` |
| 圆角 token | `app/src/main/java/com/takahashirinta/ncrust/ui/theme/AppShapes.kt:61-115` |
| 动效 token | `app/src/main/java/com/takahashirinta/ncrust/ui/theme/AppMotion.kt:75-289`（`spatialDefault` `:93`、`spatialSlow` `:96`、`effects` `:184`、`pressScale` `:148`、`PAGE_TRANSITION_MS` `:267`） |
| 显示开关最佳先例 | `app/src/main/java/com/takahashirinta/ncrust/ui/theme/PageTransitionSetting.kt:63-125` + 单测 `app/src/test/java/com/takahashirinta/ncrust/ui/theme/PageTransitionSettingTest.kt:42-138`（含 `FakePrefs` `:150-254`） |
| 枚举索引存 Int | `ThemeManager.kt:38-40 / 59-79`；`LyricsDisplayPrefs.kt:34 / 55 / 68 / 133-153 / 201` |
| 枚举名存 String | `ThemeManager.kt:43-54`；`AccentSource.kt:26-44` |
| 一次性迁移 | `player/QualityLadder.kt:29-63`；调用点 `NcrustApplication.kt:40` |
| `Strings` 预算 | `app/src/test/java/com/takahashirinta/ncrust/ui/i18n/StringsConstructorBudgetTest.kt:73 / 81 / 84 / 87 / 89-102 / 104-106 / 176-187` |
| `Strings` 规则 | `app/src/main/java/com/takahashirinta/ncrust/ui/i18n/Strings.kt:3-52`（算式 `:13`、新代码读组 `:43-45`、长期防线 `:47-51`） |
| `PlaylistsStrings` | `Strings.kt:863-898`；实参 `zh_CN.kt:358-376` |
| `MotionStrings`（两条文案的完整先例） | `Strings.kt:1050-1055`；构造参数 `Strings.kt:165`；实参 `zh_CN.kt:424-427`；调用 `UserScreen.kt:526-531`；diff = `516e005` |
| benchmark 模块 | `benchmark/run_benchmark.sh:190-201`（分派）、`:117-135`（非 debuggable 硬断言）；`SettingsScrollBenchmark.kt:20-51`（判据与形状）；`BaselineProfileGenerator.kt:42-52 / 199-203` |
| 页头两态图标先例 | `ui/player/FullPlayerControls.kt:564-596`（`RotationToggleButton`）；页头右侧图标 `LibraryPlaylistsTab.kt:235-258` |
