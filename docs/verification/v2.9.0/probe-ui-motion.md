# v2.9.0 探针 · 全屏播放器 UI 动效（只读代码侦察）

> **性质**：纯只读探针。本轮**没有**修改任何源文件、**没有**编译、**没有**跑单测、**没有**真机/模拟器。
> 所有结论要么是源码行号引用，要么是「代码算式 + 仓库既有探针实测值」的换算，每一处都标了来源。
> **读法**：`未找到` = 全仓 grep 零命中，不是"我没看到"。
>
> **覆盖文件**：`PlayerLayout.kt` / `PlayerCard.kt` / `PlayerCardOverlay.kt` / `WaveformRing.kt` /
> `AudioVisualizer.kt` / `NcrustLyricsPanel.kt` / `FullPlayerControls.kt` / `SlimProgressBar.kt` /
> `TrayLayout.kt` / `TrayLyric.kt` / `ui/theme/AppShapes.kt` / `ui/theme/AppMotion.kt` /
> `ui/components/AppVisualModifiers.kt` / `ui/theme/color/*` / `ui/theme/CoverTheme.kt` /
> `ui/theme/AccentSource.kt` / `ui/player/waveform/*` / `AGENTS.md`。

> ### ⚠️ 探测时点与并发实现（**读本文前必看**）
>
> 本轮探测期间，**另一个 agent 正在同一个工作区写 v2.9.0 的实现**（不是本探针改的）。
> `git status` 实测（2026-09-27 21:43–21:46）：`M ui/player/AudioVisualizer.kt`（+25 / −79 行）、
> `M ui/theme/AppMotion.kt`（+76 行，新增 A/B/C 档**幅度** token）、新目录
> `?? ui/player/motion/`：`MotionClock.kt` / `MotionEnvelope.kt` / `MotionEffects.kt` /
> `MotionPrefs.kt` / `MotionBackdrop.kt` / `CoverBlur.kt`。
>
> 因此本文所有 **「未找到」都是对 v2.8.0 基线（HEAD）成立**；其中三条已被那份并发实现改动：
> ① **帧时钟**：`MotionClock.frame()` 已经只调一次 `WaveformStore.pump`，`AudioVisualizerBars`
> 退化成纯读取方 —— 与本文 §5.4 推出的约束一致；② **RMS 读口**：已新增 `WaveformStore.newestBar()`；
> ③ **模糊**：走 CPU 侧 `CoverBlur`（`CoverBlur.kt:27` 明确写了不用 `RenderEffect` 的理由 =
> API 31+ 且作用在全屏图层上），而不是 `Modifier.blur`。
> 本文的价值因此在于「**基线事实 + 几何数字 + §5 那三条结构约束的推导**」，
> 不应被读成"当前工作区还没有这些代码"的断言。

---

## 0. 结论速览（先看这 6 条）

| # | 事实 | 落点 |
|---|---|---|
| 1 | 全屏播放器的背景**只有一层纯色**（`LocalMetroColors.current.background`），**没有任何模糊**：全仓 `Modifier.blur` / `RenderEffect` **零命中** | `PlayerCard.kt:695-702`；§3.1 |
| 2 | **封面取色/调色板已经存在且已经在后台线程跑**（`androidx.palette` + vendored HCT），但只在用户把「主题色来源」设成 `COVER` 时才接进主题 | `PlaybackService.kt:1101-1165`、`CoverTheme.kt:87-135`、`MainActivity.kt:300-318`；§4.5 |
| 3 | **UI 侧今天没有任何"当前 RMS"的公开读口**：`WaveformRing` 实例是 `WaveformStore` 的 `private val`，`newestTarget()` 在外部不可达；唯一公开的 Compose 可观察量是 `WaveformStore.generation: Int`，且**只允许在 draw 阶段读** | `AudioVisualizer.kt:140/166-169/191-197`；§5 |
| 4 | 即使补一个 `latestRms()`，它的值也**只在 `WaveformStore.pump` 跑过之后才前进**，而 `pump` 的唯一调用点在 `AudioVisualizerBars` 的帧循环里 —— 波形条不挂载的形态（手机竖屏、手机横屏非大屏）里那个值**是冻的** | `AudioVisualizer.kt:181-188/374-404`；§5.4 |
| 5 | 节拍（onset）判据**已经写好且可复用**（基线 + 绝对/相对门槛 + 180ms 冷却），今天只被 C 档的涟漪/粒子消费，**播放键 / 进度条上没有任何脉冲** | `WaveformEffectsState.kt:88-136/283-296`；§3.4 |
| 6 | 呼吸相位**已经存在但和 RMS 无关**：`breatheScale()` 是纯相位余弦（3.2s / 深度 0.16），只乘在波形柱 alpha 上 | `WaveformRing.kt:280-287`、`AudioVisualizer.kt:416/557-559`；§3.3 |

---

## 1. 竖屏全屏播放器布局树（含实际 dp/px 数字）

### 1.1 外层：谁在画背景

`PlayerCardOverlay`（`PlayerCardOverlay.kt:13-81`）是**唯一**的定位壳，只有一个 `Box(fillMaxSize)` +
一层 `graphicsLayer`：

```kotlin
// PlayerCardOverlay.kt:46-52
Box(
    modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
            translationY = collapsedOffsetY + (0f - collapsedOffsetY) * progress.value
        }
) {
    PlayerCard( … )
```

`progress: Animatable<Float, AnimationVector1D>` 由 `MainScreen` 持有（`MainActivity.kt:969`
`val progress = remember { Animatable(0f) }`），调用点在 `MainActivity.kt:2262-2327`；
`collapsedOffsetY` 与 `totalDragDistancePx` 在 `MainActivity.kt:936-943`
（`totalDragDistancePx = contentHeightPx * 0.85f`）。

`PlayerCard` 的**根**是一个 `Box`（`PlayerCard.kt:561-689`），它自己挂了两个 `pointerInput`
（`580-592` 展开态吞事件 / `596-685` 整卡拖拽），以及折叠态的命中区让位
（`576` padding + `688` 等量 offset）。**背景层是这个根 Box 的前两个子节点**：

```kotlin
// PlayerCard.kt:695-713（原文）
Box(
    modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
            translationY = statusBarPx * (1f - progress.value)
        }
        .background(LocalMetroColors.current.background)
)
// 折叠态卡背：… 折叠时用 surface 盖住，与 miniBar 同色，避免底部黑块；展开时透明。
Box(
    modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
            alpha = (1f - progress.value * 5f).coerceIn(0f, 1f)
            translationY = statusBarPx * (1f - progress.value)
        }
        .background(LocalMetroColors.current.surface)
)
```

- **背景层 = `PlayerCard.kt:695-702`**，画的是 `LocalMetroColors.current.background`
  （深色 = OLED 纯黑 `#000000`，浅色 = 暖白 `#F6F2E9`；见 `AGENTS.md` 的 Theming 节）。
  注意它带 `translationY = statusBarPx * (1f - progress.value)`：折叠态整体下移一个状态栏高，
  这是"miniBar 上方不多一条 surface 色块"的修法。
- **折叠态卡背 = `PlayerCard.kt:705-713`**，`surface` 色 + `alpha = 1 - 5p` 阶梯淡出。
- 内容层是紧随其后的 `Column(fillMaxSize).systemBarsPadding()`（`715-719`）。

### 1.2 展开态子树的挂载门

`PlayerCard.kt:721` `if (hasSong && expandedMounted)` —— `expandedMounted = progress.value > 0.01f`
（`317`），`progress <= 0.01f` 时**整棵展开态子树不挂载**（v1.3.0 · B-1，`AGENTS.md` 触摸陷阱 §4）。
再往下是三分支 `when { bigScreenActive -> … isWidePlayer -> … else -> … }`（`958 / 1103 / 1199`）。

### 1.3 竖屏（`else` 分支，`PlayerCard.kt:1199-1387`）自顶向下

| 顺序 | 节点 | 行号 | 实际数字 |
|---|---|---|---|
| 1 | 顶部标题栏 `Box` | `1201-1208` | `fillMaxWidth().height(56.dp).padding(start = 68.dp, end = 56.dp)`，alpha 阶梯 `((p-0.7)/0.3)` |
| 1a | └ 歌名 `MetroText` + `basicMarquee` | `1216-1229` | `initialDelayMillis = 2000`、`repeatDelayMillis = 2500`、`velocity = 48.dp` |
| 1b | └ `ArtistLineWithSource`（歌手 · 音源） | `1232-1237` | `bodyMedium` / 角标 `bodySmall` |
| 2 | 内容区 `Box(weight(1f))` | `1240-1245` | 占满剩余高度 |
| 2a | └ `playerPanels(Modifier.fillMaxSize())`（歌词 / 队列） | `1246` | 见 §1.4 |
| 2b | └ 收起态悬浮播放键 | `1250-1274` | `56.dp`，`padding(end=16.dp, bottom=16.dp)`，仅 `controlsCollapse > 0.5` 时挂载 |
| 2c | └ 大封面信息 overlay | `1276-1302` | `align(BottomStart)`，`padding(horizontal = 24.dp, vertical = 8.dp)` |
| 3 | `Spacer(16.dp)` | `1304` | — |
| 4 | 控制栏 `Box` | `1305-1326` | `fillMaxWidth()` + `collapsibleHeight(controlsCollapse)` + `translationY = collapse * size.height` |
| 5 | 把手拖拽带 | `1337-1386` | 外层全宽 × `24.dp`（+ 手势导航上抬 `32.dp`，`2020`）；内层点击盒 `48.dp × 24.dp`；视觉 `40.dp × 3.dp` |

控制栏之后（仍在根 Box 内）：

| 叠加层 | 行号 | 数字 |
|---|---|---|
| mini bar（托盘） | `1399-1423` | `fillMaxWidth()` + `statusBarsPadding()` + `height(TrayLayout.HEIGHT_DP.dp)` = **80dp**（`TrayLayout.kt:75`），`background(surface)` |
| 唯一封面 overlay | `1643-1683` | 见 §4 |
| 收起按钮 | `1693-1709` | 全宽 × `56.dp`，`padding(end = 8.dp)`，仅 `progress > 0.99f` 时挂载（`1703`） |

**托盘几何**（`TrayLayout.kt`，唯一落点）：`HEIGHT_DP = 80`（`:75`）、`COVER_SIZE_DP = 56`（`:91`）、
`LINE_GAP_DP = 2`（`:81`）、`coverCenterOffsetDp() = HEIGHT/2 = 40`（`:108`）、
`controlsWidthDp() = 3 × 48 = 144`（`:132-134`）、`bottomOverlayInsetDp(wide) = 88 / 168`（`:163-165`）。
落到 `PlayerCard`：`miniCoverHalfPx = 28.dp`（`288`）、
`miniCoverCenterY = statusBarPx + TrayLayout.coverCenterOffsetDp()`（`293`）。

### 1.4 歌词 / 队列面板（`playerPanels`，`PlayerCard.kt:829-956`）

```kotlin
// PlayerCard.kt:836-843（歌词面板外层）
Box(
    modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
            val q = queueSlideProgress.value
            alpha = lyricAnimProgress.value * if (q < 0.01f) 1f else 0f
            translationX = -q * screenWidthPx
        }
)
```

- 歌词 = `Crossfade(targetState = song?.id, SokuouTweens.CoverFade)` 包 `LyricsView(...)`（`847-879`），
  `centeredLayout = usesSideCover`（`877`）；
- 队列 = `Column(fillMaxSize)` + `graphicsLayer{alpha/translationX}`（`886-954`），标题行
  `padding(horizontal = 16.dp, vertical = 8.dp)`、标题 `fontSize = 20.sp`，`QueueView(...)`（`943-953`）；
- 两个面板的"显示/隐藏"**全部走 `graphicsLayer` 的 `alpha` + `translationX`**，不卸载（有意为之，见 `831-835` 注释）。

### 1.5 竖屏封面的尺寸与落点（实际数字）

```kotlin
// PlayerCard.kt:276-296（原文，节选）
val coverSizePx = if (usesSideCover) {
    if (wideCoverSizePx > 0f) wideCoverSizePx
    else PlayerLayout.coverFallbackSizePx(screenWidthPx, screenHeightPx)
} else screenWidthPx
…
val largeCoverCenterX = if (usesSideCover) wideCoverCenter.x else screenWidthPx / 2f
val largeCoverCenterY = if (usesSideCover) wideCoverCenter.y else screenHeightPx * 0.3f + dp24px
```

- 竖屏：封面边长 = **整屏宽**（`280`），绘制用 `Modifier.fillMaxWidth().aspectRatio(1f)`（`1652`），
  中心 = `(屏宽/2, 屏高 × 0.3 + 24dp)`（`294-295`）；
- 侧栏（宽屏 / 大屏）：边长 = **实测封面区的最小边**（`PlayerLayout.squareCoverSizePx`，`PlayerLayout.kt:241-242`），
  实测前用兜底 `min(screenWidthPx × 0.4, screenHeightPx × 0.5)`（`PlayerLayout.kt:252-253`，调用点 `279`）；
- 封面到 mini 态的缩放：`miniScale = miniCoverHalfPx * 2f / coverSizePx`（`289`）。

---

## 2. 横屏（horizontal）布局树：谁在判定、宽度怎么分

### 2.1 没有 `isLandscape` / `wideLayout`，只有三个谓词

`PlayerCard` 里**不存在**名为 `isLandscape` 或 `wideLayout` 的布尔量（全文件 grep 无命中）。
方向判定的**唯一原文**是 `LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE`，
出现在两处：`215-216`（喂给 `bigScreenActive`）与 `254-255`（喂给 `visualizerSlot`）。

```kotlin
// PlayerCard.kt:206-222（原文，删去注释）
val isWidePlayer = LocalConfiguration.current.screenWidthDp >= 600
val bigScreenActive = PlayerLayout.isBigScreenActive(
    requested = bigScreen,
    orientationLandscape = LocalConfiguration.current.orientation ==
        Configuration.ORIENTATION_LANDSCAPE,
)
val usesSideCover = isWidePlayer || bigScreenActive
val wideLeftFraction = PlayerLayout.wideLeftFraction(isWidePlayer, wideSplit)
```

判据全部收在 `PlayerLayout`（纯逻辑、可单测）：

| 名字 | 值 / 语义 | 行号 |
|---|---|---|
| `WIDE_BREAKPOINT_DP` | `600` | `PlayerLayout.kt:23` |
| `LARGE_SCREEN_BREAKPOINT_DP` | `600`（比的是 `smallestScreenWidthDp`） | `:34` |
| `isBigScreenActive(requested, orientationLandscape)` | `requested && orientationLandscape` | `:209-210` |
| `WIDE_RIGHT_FRACTION` | `0.56f` | `:195` |
| `BIG_SCREEN_LEFT_FRACTION` | `1f - 0.56f = 0.44f` | `:198` |
| `wideLeftFraction(isWidePlayer, wideSplit)` | `if (isWidePlayer) 1f - 0.56f × wideSplit else 1f` | `:216-217` |
| `splitBoundaryPx(...)` / `bigScreenLeftBoundaryPx(...)` | 命中测试分界线 | `:227-228 / :231-232` |
| `visualizerSlot(enabled, bigScreenActive, isWidePlayer, isLargeScreen, orientationLandscape)` | 六格 A/B 矩阵写在 KDoc 里 | `:79-87` |
| `visualizerHeightDp(screenHeightDp)` | `(H × 0.11).coerceIn(32f, 56f)` | `:190-192`（常量 `:37-39`） |

`wideSplit` 不是常量，是 `animateFloatAsState(showLyrics || showQueue ? 1f : 0f, tween(280, CubicBezier(0.2,0,0,1)))`
（`PlayerCard.kt:196-200`）—— 也就是说**横屏左栏宽度是动画量**：`wideSplit = 0` 时左栏占 **100%**，
`wideSplit = 1` 时占 **44%**。

### 2.2 两条横屏分支

**(a) `bigScreenActive`（`PlayerCard.kt:958-1102`）**：`Row(fillMaxSize)` +
`windowInsetsPadding(WindowInsets.displayCutout)`（`968-982`）

| 栏 | 权重 | 内容 |
|---|---|---|
| 左栏 `Column`（`984-988`） | `weight(BIG_SCREEN_LEFT_FRACTION)` = **0.44** | ① 封面落点 `Box(fillMaxWidth().weight(1f))`（`992-1002`，里面**不画东西**，只上报中心/边长）② 波形条（`1012-1018`，条件挂载）③ `Spacer(10.dp)` ④ 信息行 `Row(padding(horizontal = 16.dp))`：歌名/歌手列（`weight(1f).clickable { onSongInfoClick() }`，`1026-1046`）+ `Spacer(10.dp)` + `PlayerQualityChip`（`1056-1066`）+ `RotationToggleButton(40.dp / 22.dp)`（`1071-1078`）⑤ `Spacer(8.dp)` |
| 右栏 `Column`（`1083-1087`） | `weight(1f - 0.44f)` = **0.56** | ① `Box(fillMaxWidth().weight(1f)){ playerPanels(...) }`（`1088-1098`）② `playerControls()`（`1099`） |

**(b) `isWidePlayer`（`PlayerCard.kt:1103-1197`）**：`Row(fillMaxSize)`（`1106`）

| 栏 | 权重 | 内容 |
|---|---|---|
| 左栏 `Column`（`1107-1111`） | `weight(wideLeftFraction)` = **100% → 44%** 随 `wideSplit` 动画 | ① 封面落点 `Box(fillMaxWidth().weight(1f))`（`1115-1125`）② 波形条（`1138-1144`，**仅平板横屏**挂载）③ 歌名/歌手 `Box(widthIn(max = 560.dp).padding(horizontal = 24.dp).clickable { onSongInfoClick() })`（`1146-1170`）④ `Spacer(12.dp)` ⑤ 控制条 `Box(widthIn(max = 560.dp))`（`1172-1181`）⑥ `Spacer(8.dp)` |
| 右栏 `Box`（`1186-1196`） | `weight((1f - wideLeftFraction).coerceAtLeast(0.0001f))` = **≈0 → 56%** | `playerPanels(fillMaxSize)`（`1191-1195`） |

**控制条在横屏是"扁平横向"变体**：`landscape = usesSideCover`、`compact = usesSideCover`
（`PlayerCard.kt:780-781`）→ `FullPlayerControls.kt:159-376`：
`lPad = 12.dp`（compact）/ `20.dp`（非 compact），`lTop = 2.dp / 4.dp`（`:162-163`）；
进度行 = `PositionText` + `Spacer(8.dp)` + `SlimProgressBar(weight(1f), horizontalPadding = 0.dp)` + `Spacer(8.dp)` + `DurationText`（`:169-185`）；
`Spacer(6.dp)`（`:186`）；操作行 = 左组三个 40dp 按钮（歌词/队列/收藏，`:225-273`；平板再加一个 ⤢，`:278-295`）
+ 中组 `weight(1f)` 内居中三键 **44dp / 54dp / 44dp**（`:305-352`）+ 右组音质片或 `trailing`（`:357-371`）。

### 2.3 实际数字（代码算式 + 仓库既有真机实测值）

设备基线（都取自仓库既有探针，不是本轮实测）：
PCL110 手机横屏 `800dp × 363dp`（`probe-waveform-tablet.md:125` 引 `PlayerLayoutTest.kt:19-22`）、
WGR-W09 平板横屏 `sw800dp w1280dp h768dp / 320dpi / mBounds 2560×1600`（同文件 `:123-124`）。

| 量 | 手机横屏（PCL110，大屏模式） | 平板横屏（WGR-W09，宽屏两栏） | 来源 |
|---|---|---|---|
| 全屏宽 | 800dp | 1280dp | 实测 |
| **左栏宽** | **352dp = 44%** | **563dp = 44%** | `BIG_SCREEN_LEFT_FRACTION` / 实测（`probe-waveform-tablet.md:603` 写「0.44 × 1280dp ≈ 563dp」） |
| 右栏宽 | 448dp = 56% | 717dp = 56% | `1 - 0.44` |
| 波形条高 | `363 × 0.11 ≈ 39.9dp` | `768 × 0.11 = 84.5 → clamp 56dp` | `PlayerLayout.kt:190-192`；`probe-waveform-tablet.md:138-140/628` |
| **波形条可见宽** | `352 - 2×16 = 320dp`（= **整屏的 40.0%**） | `563 - 32 = 531dp`（= 整屏的 **41.5%**） | `AudioVisualizerSlot` 的 `.fillMaxWidth().padding(horizontal = 16.dp)`（`PlayerCard.kt:2002-2005`） |
| 波形条柱数 | 28（`WaveformStore.BAR_COUNT`，`AudioVisualizer.kt:123`） | 28 | — |
| 封面边长 | `min(352, 封面区高)`；源码注释给的观感值是 **≈230dp** | `min(563, 封面区高)` ⇒ **563dp**（贴满左栏宽） | `PlayerCard.kt:967`（注释「封面能拿到 ~230dp」）、`PlayerLayout.kt:241-242` |

### 2.4 横屏现在**空着**的区域（逐块点名）

以**手机横屏 / 大屏模式**（800×363dp，左栏 352dp）为例，按代码算式自上而下：

1. **封面正方形之外的横向留白**：封面区 `Box` 是 `weight(1f)` 的矩形（352dp × ≈253dp），
   封面取 `min(w,h)` ⇒ 边长 ≈253dp，**左右各空 ≈49.5dp × 253dp**（`992-1002` + `PlayerLayout.kt:241-242`）。
   这块区域**不画任何东西**，是"封面居中留白"。
2. **波形条只占左栏减 32dp**：320dp / 800dp = 40% 宽（`PlayerCard.kt:2002-2005`），
   右栏（448dp）整条高度里**一个波形像素都没有**。
3. **右栏在 `wideSplit = 0` 时几乎为 0 宽**：`weight((1f - wideLeftFraction).coerceAtLeast(0.0001f))`
   （`1186-1188`）—— 这不是"空白"，是"被压扁"，但横屏**大屏模式**不会有这个状态
   （`LaunchedEffect(bigScreenActive)` 会强制 `showLyrics = true`，`461-463`）。
4. **平板横屏（1280×768）**：封面 563dp 正方形，封面区高 ≈642dp ⇒ **上下空 ≈79dp**；
   波形条同理只占 41.5% 宽。
5. **两条分支的"整屏背景"**：全屏唯一的底色就是 §1.1 那层纯色 —— **没有任何模糊/封面底图**。

---

## 3. 现有动效盘点（逐项给证据或明确"未找到"）

### 3.1 背景模糊 / 封面模糊：**未找到**

```
$ grep -rn "Modifier.blur\|\.blur(\|RenderEffect" app/src/main/java/com/takahashirinta/ncrust --include=*.kt
（零命中）
$ grep -rn "blur" … --include=*.kt        # 零命中
$ grep -rn "RenderEffect" … --include=*.kt # 零命中
```

全仓 `graphicsLayer` 共 **75 处**，没有一处带 `renderEffect` / `blur`。
**结论：这个仓库从来没有过模糊**（既没有 `Modifier.blur`，也没有 `RenderEffect.createBlurEffect`）。

### 3.2 "从封面生成背景"：**存在，但是"换算成主题色"而不是"模糊封面"**

- `CustomBackgroundLayer`（`ui/CustomBackgroundLayer.kt:46-95`）是**用户手动导入的静态背景图**
  （`BackgroundImageManager` + Coil `AsyncImage` + 一层黑/白 35%~45% 遮罩），不是从当前封面来的；
- 唯一"封面 → 背景色"的通路是 v2.5.0 · A 的 HCT 调色板：
  `CoverThemeExtractor.toNcrustColors`（`CoverTheme.kt:124-135`）把封面色板的中性色覆写到
  `background` / `surface` / `surfaceVariant`，**并且只在 `accentSource == AccentSource.COVER` 时生效**
  （`MainActivity.kt:318`；`accentSource` 默认 `PRESET`，`AccentSource.kt:24/36`）。
  也就是说：**"背景跟着封面"今天是用户可开的主题行为，而不是播放器自己的一个图层**，
  且它是**纯色**（无模糊、无渐变、无呼吸）。
- 播放页本身只画 `LocalMetroColors.current.background`（`PlayerCard.kt:701`）。

### 3.3 音频驱动的"背景呼吸"：**未找到**

存在的只有波形条自己的呼吸：

```kotlin
// WaveformRing.kt:280-287（原文）
fun breatheScale(): Float {
    if (!breathePhase.isFinite()) return 1f
    // 用 cos 而不是 sin：相位 0（起播那一刻）就是**满亮度** —— 呼吸的第一步不该先暗一下。
    // 0.5 + 0.5·cos(2π·phase) ∈ [0,1]，再映射到 [1-DEPTH, 1]。
    val wave = 0.5f + 0.5f * kotlin.math.cos(TWO_PI * breathePhase)
    return 1f - BREATHE_DEPTH + BREATHE_DEPTH * wave
}
```

- 它是**纯相位余弦**（`BREATHE_PERIOD_MS = 3200f` / `BREATHE_DEPTH = 0.16f`，`WaveformRing.kt:337-341`），
  **与 RMS 无关**；
- 消费点只有一处：波形柱的 alpha（`AudioVisualizer.kt:416` + `557-559` + `584-600`）；
- 而且只在 B 档（`effects.breathe = refined`，`VisualizerTier.kt:214`）才推进（`WaveformRing.kt:156-159`）。
- 「背景随 RMS 呼吸」= **不存在**。

### 3.4 节拍脉冲：判据**存在**，但只驱动 C 档涟漪/粒子；播放键/进度条上**未找到**

```kotlin
// WaveformEffectsState.kt:120-135（原文，节选）
val onset = value >= ONSET_MIN_RMS &&
    jump >= ONSET_ABS_MIN &&
    jump >= baseline * ONSET_REL_FACTOR &&
    cooldownMs <= 0f
if (!onset) return changed
cooldownMs = ONSET_COOLDOWN_MS
if (effects.shockwave) { spawnRipple(); changed = true }
if (effects.particles) { spawnParticles(); changed = true }
```

常量：`BASELINE_TAU_MS = 800f`、`ONSET_COOLDOWN_MS = 180f`、`ONSET_MIN_RMS = 0.12f`、
`ONSET_ABS_MIN = 0.05f`、`ONSET_REL_FACTOR = 0.35f`（`WaveformEffectsState.kt:283-296`）。
输入是**未平滑**的最新柱（`WaveformRing.newestTarget()`，`WaveformRing.kt:297`；理由见 `293-296`）。
消费点：`AudioVisualizer.kt:184-186`（`if (effects.shockwave || effects.particles)`）+ `420-445`。

- 播放键：`FullPlayerControls.kt:321-336`（横屏）/ `442-456`（竖屏）—— 只有 `clickable` + `MetroIcon`，
  **零动画**；
- 进度条：`SlimProgressBar.kt` 只有缓冲期的 `rememberInfiniteTransition` 脉冲（`219-240`，`tween(1400)` / `tween(700)`），
  与音频**无关**（它在 `isBuffering` 时跑）。
- 「播放按钮/进度条随节拍脉冲」= **不存在**。

### 3.5 歌词脉冲：**未找到**；现有的只有"跨行缩放"

```kotlin
// NcrustLyricsPanel.kt:545-553（原文）
.graphicsLayer {
    // 连续距离驱动缩放:无翻转瞬间,行间渐变交接。
    // 缩放放在整行(原句+翻译)外层,双语同时放大/缩小。
    val dist = abs(index - smoothCurrentIndex.value)
    val scale = lerp(1f, inactiveScale, (dist / 1.8f).coerceIn(0f, 1f))
    scaleX = scale
    scaleY = scale
    transformOrigin = TransformOrigin(0f, 0.5f)
},
```

`smoothCurrentIndex` 是 `Animatable`，只在**跨行**时 `animateTo`（`184-190`，`SokuouTweens.QuickSwitch`），
`inactiveScale = 0.82f`（`163`）。整面板另有 `alpha = fadeIn`（`467`）。
**没有任何按 RMS/节拍逐帧变化的量**。

### 3.6 视差（parallax）：**未找到**

```
$ grep -rn "parallax\|draggable(\|detectDragGestures\|detectHorizontalDrag" app/src/main --include=*.kt
（零命中）
```

存在的位移全部是"手势直接驱动的单轴进度"，见 §7.4（landscape 文档里细讲）。

### 3.7 封面交叉淡入 / 浮起阴影 / 3D 旋转

| 项 | 状态 | 证据 |
|---|---|---|
| 封面**交叉淡入** | **部分存在但不是 alpha crossfade**：`StableCover` 在切歌时把**旧位图**垫在底层，新图 loading 期间露旧图，成功即被上层 `Image` 覆盖（瞬切） | `PlayerCard.kt:1811-1830`；`COVER_HOLD_MS = 400L`（`1730`）；`lastBitmap` 只在 `State.Success` 时写入（`1787-1793`） |
| 切歌**淡入** | 只有歌词面板：`Crossfade(targetState = song?.id, animationSpec = SokuouTweens.CoverFade)` | `PlayerCard.kt:847-851` |
| 封面**阴影 / 浮起** | **未找到**：全仓 `shadow(` / `elevation` 零命中（唯一命中是 `NcrustColors.kt:19` 的一句注释「不产生任何阴影 —— 无 elevation 的视觉识别不变」）。封面只有 `clip(shape)` + `border(1.dp, divider, shape)` | `PlayerCard.kt:1800-1809`；`AppVisualModifiers.kt:62-70`（`appCoverFrame`） |
| 封面 **3D 旋转** | **未找到**。唯一的 3D 是波形条的**恒定** 9° `rotationX`（`PERSPECTIVE_DEGREES = 9f`，注释明确"恒定值 ⇒ 不产生逐帧 layer 失效"） | `AudioVisualizer.kt:481-499` |
| 可复用的"描边包裹" | `Modifier.appCoverFrame(shape = AppShapes.large, width = 1.dp)` = `clip` + `border`（顺序不能反） | `AppVisualModifiers.kt:62-70` |

---

## 4. 封面组件：实现、加载、位图可达性、取色路径

### 4.1 组件与调用点

- 组件：`private fun StableCover(...)`，`PlayerCard.kt:1764-1832`；
- 调用点：`PlayerCard.kt:1643-1683`（`if (hasSong)` 内，根 Box 的**倒数第二层**，z 序高于内容 Column、
  低于收起按钮）；
- model：`CoverUrls.large(s.album?.picUrl)`（`1646`）→ 会追加 `?param=1080y1080`
  （`network/CoverUrls.kt:19-29`）。

### 4.2 尺寸与形状

```kotlin
// PlayerCard.kt:1649-1653（原文，节选）
modifier = Modifier
    .then(
        if (usesSideCover) Modifier.size(coverSizeDp)
        else Modifier.fillMaxWidth().aspectRatio(1f)
    )
```

- `shape: Shape = AppShapes.large`（`1772`）⇒ **16dp 圆角**（`AppShapes.kt:90`）；
- 描边：`frameColor = LocalMetroColors.current.divider`（`1681`）→ `Modifier.border(1.dp, frameColor, shape)`（`1808`）；
- 位移/缩放**全部**在 `graphicsLayer` 里算（`1654-1674`），`transformOrigin = TransformOrigin(0.5f, 0.5f)`（`1673`）；
- **Kanesumi 约束与现实的落差**：设计正典是「直角、无圆角」（`AppShapes.kt:24` 原文：
  「本仓库在 v2.5.0 之前**一处 `RoundedCornerShape` 都没有** —— 因为设计正典是「直角、无圆角」」），
  但 v2.5.0 · A 已经**主动引入**了六档圆角 token 并把它用在封面上（16dp）。
  新动效代码必须走 `AppShapes.*`，`AppShapesSingleSourceTest` 会扫源码树，
  `AppShapes.kt` 之外出现 `RoundedCornerShape(` / `CircleShape` 即测试变红（`AppShapes.kt:30-33`）。

### 4.3 位图加载与"位图能不能复用"

```kotlin
// PlayerCard.kt:1777-1793（原文，节选）
val painter = rememberAsyncImagePainter(
    model = model,
    imageLoader = Coil.imageLoader(context),
)
val state = painter.state
// 最近一次成功加载的封面位图（跨切歌保留）。
var lastBitmap by remember { mutableStateOf<Bitmap?>(null) }
…
LaunchedEffect(state) {
    val s = state
    if (s is AsyncImagePainter.State.Success) {
        (s.result.drawable as? BitmapDrawable)?.bitmap?.let { lastBitmap = it }
        timedOut = false
    }
}
```

**结论**：`Bitmap` 在组合内**已经可达** —— `lastBitmap` 就是 1080×1080 的整张位图，
且**跨切歌保留**（这就是 v2.5.0 之前"切歌不闪"的实现）。它今天只被用来 `BitmapPainter(bg.asImageBitmap())`
（`1815`）垫底。

- Coil 内存缓存：`NcrustApplication : Application(), ImageLoaderFactory`（`NcrustApplication.kt:32-58`），
  `MemoryCache.maxSizeBytes` 按总内存分档 **16/24/32 MB**（`:60-70`），磁盘缓存 100MB（`:51-57`）。
  设置页有手动清缓存入口（`SettingsGroupScreen.kt:700-703`）。
- **服务侧还有一份现成的降采样封面**：`PlaybackService` 用 Coil 预载/加载封面后
  `downscaleArtwork()`（`PlaybackService.kt:1061-1069`）把封面缩到 `ARTWORK_MAX_PX`，
  再 `Bitmap.createScaledBitmap(bitmap, 112, 112, true)`（`PALETTE_SAMPLE_PX = 112`，`1101-1121`）
  —— **这张 112×112 的采样图没有被保留**（是 `applyCoverAccent` 的局部量），
  但"服务侧已经有一张封面位图 + 一个降采样函数"是事实。
- 若新动效要一张"小图做背景模糊源"，**最省的路径是给 `StableCover` 现有的 `lastBitmap` 加一个回调**
  （它已经是 1080px 的 `Bitmap`，在组合内、无需任何新解码），
  或者复用 `PlaybackService` 已经算好的 `currentArtworkBitmap`（需要新增一条静态回调，范式照
  `onCoverAccent` / `onCoverTheme`，`PlaybackService.kt:211 / 226`）。

### 4.4 "主色/调色板提取"现有路径（完整链路）

| 环节 | 落点 |
|---|---|
| 触发 | `PlaybackService.applyCoverAccent(bitmap, url, gen)`，`PlaybackService.kt:1101-1165`（封面加载成功后调用） |
| 线程 | `scope.launch(Dispatchers.Default)`（`:1111`）—— 铁律 3「非核心计算必须在后台线程」 |
| 采样 | `Bitmap.createScaledBitmap(bitmap, 112, 112, true)`（`:1118`），`PALETTE_SAMPLE_PX = 112`（`:141`） |
| 通路 1（通知栏着色） | `androidx.palette.graphics.Palette.from(sampled).generate()`（`:1123-1124`，import 在 `:68`）；`palette.getDominantColor(...)`（`:1152`）；accent 优先 `vibrantSwatch → dominantSwatch → mutedSwatch`（`:1156-1158`） |
| 通路 2（主题调色板） | `bmp.getPixels(...)` → `CoverThemeExtractor.extractBoth(px, w, h)`（`:1128-1135`） |
| 量化/评分 | `CoverPaletteExtractor.extract`（`CoverPaletteExtractor.kt:69-99`）+ `downsample`（`:112-160`，box average，默认 `SAMPLE_MAX_PX = 50`，`CoverTheme.kt:76`）+ vendored `QuantizerCelebi` / `Score` / `Hct` |
| 出品 | `CoverPalette`（12 个角色 ARGB，`CoverPalette.kt:65-116`）、`CoverThemeColors(dark, light)`（`CoverTheme.kt:40-48`） |
| 缓存 | **有，但是"当前歌"级别的单值缓存**：`paletteUrl` / `paletteTheme` / `paletteRgb`（`PlaybackService.kt:143-148`），URL 未变直接复用（`:1106-1110`，注释「HCT 量化比 Palette 贵，这条早退是它唯一的省法」）—— **不是 LRU，不是多首缓存** |
| 世代防串色 | `if (gen != artworkGeneration) return@launch`（`:1140`） |
| 推送 UI | 静态回调 `onCoverAccent` / `onCoverTheme`（`:211 / :226`）→ `PlayerViewModel.coverAccentRgb` / `coverTheme`（`PlayerViewModel.kt:260 / 271`，赋值在 `:596-598`） |
| 消费 | `MainActivity.kt:290-318`：`accentColor`（COVER 档走 `processAccentColor`）+ `coverPalette`（只有 `AccentSource.COVER` 才非 null） |
| 降级 | 任何一步失败返回 `null` / 静默回落预设主题（`CoverTheme.kt:57-63`、`CoverPaletteExtractor.kt:69-70`） |

> **对 v2.9.0 的直接含义**：A 档要的"封面主色"**不需要新写提取器**，
> 需要的是①让播放器能拿到一个**颜色**（今天拿到它要经过 `MainActivity` 的 accentSource 分支，
> 播放器自己读不到）②决定要不要为"背景层"单独算一个更暗/更低饱和的变体。

---

## 5. 【关键】RMS 数据源：UI 侧到底能读到什么、每帧怎么读

### 5.1 音频线程侧（写）

```
TransparentWaveformSink.handleBuffer(buffer, encoding)
   → onBar(rootMeanSquare(buffer, encoding))        // player/TransparentWaveformSink.kt:96-97 / :137
   → WaveformStore.onBar(rootMeanSquare: Double)     // AudioVisualizer.kt:171-174
   → ring.push(value.toFloat())                      // WaveformRing.kt:101-106
```

```kotlin
// AudioVisualizer.kt:171-174（原文）
/** **音频线程**调用：零分配、零锁。 */
fun onBar(rootMeanSquare: Double) {
    if (enabled) ring.push(rootMeanSquare.toFloat())
}
```

契约（写在这里是为了防止被"顺手优化"）：单写者 / `writeIndex` 是唯一的 `@Volatile`（`WaveformRing.kt:76-80`）、
零分配零锁零 IO、溢出丢最旧的。开关判断前置到 RMS 之前
（`TransparentWaveformSink.kt:54-57` / `126-127`）。
**柱率不是常数**：`WaveformStore.CAPACITY` 的 KDoc 明确写「真实柱率 = `handleBuffer` 回调率，
代码不保证任何固定值」，并已删掉过期的 `BARS_PER_SECOND = 30`（`AudioVisualizer.kt:126-137`）。

### 5.2 UI 侧（读）：只有三个公开读口

| 成员 | 类型 / 语义 | 行号 | 是否 Compose 可观察 |
|---|---|---|---|
| `WaveformStore.generation` | `Int`（`mutableIntStateOf`），`pump` 判定"需要重绘"时 `+1` | `AudioVisualizer.kt:166-169 / 187` | **是**（唯一的一个），注释要求「只应在 draw 阶段读（在组合阶段读会变成每帧重组）」 |
| `WaveformStore.snapshot(bars, peaks)` | 把 28 根柱的**画面值 + 峰值**拷进调用方复用的数组 | `:191` | 否（普通字段读） |
| `WaveformStore.breatheScale()` / `flowPhase01()` / `showcase` / `isColoringInverted()` | 相位 / C 档状态 | `:194 / :197 / :200 / :214` | 否 |

### 5.3 **不存在**"当前 RMS"的公开读口

```kotlin
// AudioVisualizer.kt:140（原文）
private val ring = WaveformRing(capacity = CAPACITY, barCount = BAR_COUNT)
```

- `ring` 是 `private`；`WaveformRing` 上的 `newestTarget()`（`WaveformRing.kt:297`，最新**未平滑** RMS）、
  `barAt()`（`:300`）、`targetAt()`（`:303`）、`peakAt()`（`:306`）虽然都是 `public`，
  **但外部拿不到那个实例** ⇒ 对非波形组件而言**不可达**；
- `snapshot(bars, peaks)` 是唯一能间接读到 RMS 的公开 API（读 `bars[barCount-1]`），
  但它要求调用方自备数组、且必须有人先 `pump`。
- **所以：今天不存在任何"非波形组件可以便宜地每帧读到的当前 RMS"。** 需要新增一个访问器
  （例如 `fun latestRms(): Float = ring.newestTarget()`）+ 一个"谁负责 `pump`"的决策。

### 5.4 帧时钟只有一个，而且它住在波形组件里（**这是最关键的约束**）

```kotlin
// AudioVisualizer.kt:374-404（原文，节选）
LaunchedEffect(barCount, frameIntervalMs, effects) {
    val budgetNs = frameIntervalMs * 1_000_000L
    …
    val onFrame: (Long) -> Unit = { now ->
        if (clock[0] == 0L || now - clock[0] >= budgetNs) {
            val dtMs = …
            clock[0] = now; clock[1] = now
            WaveformStore.pump(active = true, dtMs = dtMs, effects = effects)
        }
    }
    while (true) {
        if (currentActive.value()) { withFrameNanos(onFrame) }
        else { delay(frameIntervalMs); … ; WaveformStore.pump(active = false, …) }
    }
}
```

`WaveformStore.pump`（`AudioVisualizer.kt:181-188`）→ `ring.pump(active, dtMs, effects)`：
它既**消费环形缓冲**（`consumePending`，`WaveformRing.kt:165-182`）又**推进画面值/峰值/相位**
（`approach` / `advancePhase`，`:214-265`），返回值与 `generation` 一起构成"要不要重绘"的判据。

推论（写新动效之前必须先决定这一条）：

1. **波形条不挂载的形态里 `pump` 从不执行** —— 判据是 `PlayerLayout.visualizerSlot`
   （`PlayerCard.kt:257-263`）。按它的 A/B 矩阵，**手机竖屏 / 手机横屏非大屏**这两格
   `visualizerSlot == false`（`PlayerLayout.kt:66-77`）⇒ 波形组件的 `LaunchedEffect` 根本不存在
   ⇒ `targets` / `bars` 永远停在 0 附近，**即使补一个 `latestRms()` 也读不到活数据**。
2. **不能起第二个 `pump` 调用者**：`pump` 不是幂等的（平滑是"每帧走一步"，同一帧走两步等于把
   时间常数减半；`advancePeak` 的保持计时也会被扣两次）。**必须共享同一个帧时钟**，
   而不是各起一个 `withFrameNanos`。
3. 三种可行形状（供 v2.9.0 选型，本轮不实现）：
   - **提升帧时钟**：把 `LaunchedEffect` 里的帧循环提到 `PlayerCard`（或一个新的 `PlayerMotionClock`），
     波形 Canvas 只读 `generation`；代价是要动 `AudioVisualizerBars` 的"不空转"契约（`:260-262`）；
   - **新增"最省"读口 + 由动效宿主 pump**：`latestRms()` + 让动效宿主也调 `pump`，但要保证
     **全局每帧只有一次**（例如用一个 `lastPumpNanos` 去重，`FrameBudgetPolicy` 那类有界判据的思路）；
   - **完全绕开环形缓冲**：给 `WaveformStore` 加一个 `@Volatile private var lastRms` +
     `fun latestRms()`，音频线程在 `onBar` 里顺手写（零额外开销），
     动效宿主自己按帧读 + 自己算包络/节拍。**这一条对现有波形路径零侵入**，
     代价是"再算一份包络"（而不是复用 `WaveformRing` 那份）。

### 5.5 节拍检测可以直接复用（不必新写）

`WaveformEffectsState.update(newestBar, dtMs, active, effects)`（`WaveformEffectsState.kt:88-136`）
已经是一个**有界**的 onset 检测器（基线时间常数 / 绝对门槛 / 相对门槛 / 冷却 / `active=false` 时基线归零），
而且它的输入**明确要求未平滑值**（`:81-82`、`WaveformRing.kt:293-296`）。
今天它被 `if (effects.shockwave || effects.particles)` 挡在 C 档之外（`AudioVisualizer.kt:184-186`）。
**把它的"节拍事件"暴露给 A/B 档（播放键脉冲、进度条脉冲、背景呼吸）是现成的**，
需要新增的只是"一次 onset ⇒ 一个 0..1 的脉冲包络"这层（今天直接 `spawnRipple()`，没有标量脉冲）。

### 5.6 与现有档位体系的关系（**别新造一套 A/B/C 命名**）

`ui/player/waveform/VisualizerTier.kt` 已经有一套**面向用户的三档**
（`SIMPLE = 0` / `REFINED = 1` / `SHOWCASE = 2`，`:39-45`）+ 内部**能力位**
（`VisualizerEffects`，`:109-146`），并且**能力位在 KDoc 里就已经按 A/B/C 分组**：

```
// VisualizerTier.kt:114-135（原文，节选）
/** A：镜像对称 … */   val mirror: Boolean,
/** A：圆角柱 … */     val rounded: Boolean,
/** A：峰值保持 … */   val peaks: Boolean,
/** A：按时序着色 … */ val timeOrderedTint: Boolean,
/** B：渐变流动 … */   val flow: Boolean,
/** B：柱顶光点 … */   val dots: Boolean,
/** B：呼吸 … */       val breathe: Boolean,
/** C：冲击波 … */     val shockwave: Boolean,
/** C：粒子 … */       val particles: Boolean,
/** C：3D 透视 … */    val perspective: Boolean,
/** C：点按交互 … */   val tapInteraction: Boolean,
```

映射的唯一落点是 `VisualizerEffects.of(...)`（`:192-220`），落盘键的唯一落点是 `VisualizerPrefs`
（8 个 `KEY_*`，`VisualizerPrefs.kt:64-71`，并有"键名是持久化契约"的单测）。
**v2.9.0 的"UI 动效档位"如果要与它统一，最干净的做法是扩展这一张能力位表 + `of()` 的同一个映射，
而不是在播放器里再写一套 `when(tier)`。**

---

## 6. AppShapes / AppMotion 全量 token + 仓库纪律（原文引用）

### 6.1 `AppShapes`（`ui/theme/AppShapes.kt`，全 6 个 token）

| token | 值 | KDoc 指定的用途 | 行号 |
|---|---|---|---|
| `extraSmall` | `RoundedCornerShape(4.dp)` | 角标、药丸内层、极小控件 | `:67` |
| `small` | `RoundedCornerShape(8.dp)` | **列表项** | `:75` |
| `medium` | `RoundedCornerShape(12.dp)` | **卡片** | `:82` |
| `large` | `RoundedCornerShape(16.dp)` | **封面**与歌词面板 | `:90` |
| `extraLarge` | `RoundedCornerShape(28.dp)` | **弹窗** | `:99` |
| `full` | `RoundedCornerShape(percent = 50)` | 按钮 / 搜索框 / 音源标签（药丸） | `:115` |

守卫：`AppShapesSingleSourceTest` 扫源码树，`AppShapes.kt` 之外出现 `RoundedCornerShape(` / `CircleShape` 即变红（`:30-33`）。

### 6.2 `AppMotion`（`ui/theme/AppMotion.kt`，全量）

| token | 值 | 行号 |
|---|---|---|
| `spatialFast` | `spring(dampingRatio = 0.6f, stiffness = 800f)` | `:90` |
| `spatialDefault` | `spring(0.8f, 380f)` | `:93` |
| `spatialSlow` | `spring(0.8f, 200f)` | `:96` |
| `spatialFastDp` | `SpringSpec<Dp>` `spring(0.6f, 800f)` | `:99` |
| `spatialDefaultDp` | `SpringSpec<Dp>` `spring(0.8f, 380f)` | `:102` |
| `spatialNoOvershoot` | `spring(1.0f, 800f)` —— **唯一允许驱动带阈值 `progress` 的空间弹簧** | `:112` |
| `playerExpand` | `spring(1.0f, 260f)`（临界阻尼，禁止改过冲） | `:133` |
| `playerCollapse` | `spring(1.0f, 420f)` | `:141` |
| `pressScale` | `spring(0.45f, 900f)`（**只用于缩放**，允许过冲） | `:148` |
| `PRESS_SCALE` | `1.05f` | `:151` |
| `effectsSpringFast` | `spring(1.0f, 3800f)` | `:166` |
| `effectsSpringDefault` | `spring(1.0f, 1600f)` | `:169` |
| `effectsSpringSlow` | `spring(1.0f, 800f)` | `:172` |
| `effects` | `tween(200, FastOutSlowInEasing)` | `:184` |
| `effectsFast` | `tween(100, LinearEasing)` | `:187` |
| `sheetAppear` | `tween(300, CubicBezierEasing(0.2f, 0f, 0f, 1f))` | `:203` |
| `sheetDismiss` | `tween(260, FastOutSlowInEasing)` | `:210` |
| `coverFade` | `tween(400, CubicBezierEasing(0.2f, 0f, 0f, 1f))` | `:213` |
| `listItemEnter` | `tween(220, FastOutSlowInEasing)` | `:222` |
| `LIST_ITEM_RISE_DP` | `8.dp` | `:225` |
| `colorTransition` | `tween(300, FastOutSlowInEasing)` | `:233` |
| `PAGE_TRANSITION_MS` | `260` | `:267` |
| `pageTransitionEasing` | `FastOutSlowInEasing` | `:278` |
| `pageTransitionSpec<T>()` | `tween(PAGE_TRANSITION_MS, pageTransitionEasing)` | `:288-289` |

守卫：`AppMotionSpecTest` —— 逐值 + 「播放器转场不得过冲」+ **`spring(` 只能来自 `AppMotion`**（源码树扫描）。

### 6.3 AGENTS.md 的硬规则（原文引用）

**① GPU 零重组（总纲）** — `AGENTS.md:112`：

> 2. **GPU zero-recomposition** — animations driven by a single `progress: Float` through `graphicsLayer`, not state-driven recomposition.

**② 播放器卡片的具体写法** — `AGENTS.md:191`：

> The player card uses a single `progress: Float` driven by `Animatable`. All visual properties (card size, position, opacity) are computed inside `graphicsLayer { }` — **never `animateFloatAsState`**. This is the core GPU zero-recomposition pattern. Easing is `tween + CubicBezierEasing` (controlled deceleration, no spring/bounce). Cover art always fills the full screen width with no clipping; transitions use centre-based `TransformOrigin(0.5f, 0.5f)` with computed translations.

**③ 高频 StateFlow 只在叶子 / draw 订阅** — `AGENTS.md:930`：

> - **GPU zero-recomposition**: read animation values only inside `graphicsLayer`; never `animateFloatAsState` for the player card. High-frequency StateFlows are subscribed in leaf composables / `draw` scope, not in the parent.

**④ v2.5.0 铁律 1（新增动效一律零重组）** — `AGENTS.md:3074-3078`：

> 1. **UI 动效不得影响播放性能和核心功能。**
>    判据不是"看起来流畅"，而是三件可测的事：
>    ① 动效**必须有界**（列表入场只对下标 < `ListItemAppear.MAX_ANIMATED_INDEX` 播放，
>    纯函数判定 + `ListItemAppearTest` 钉住阈值在 1..64）；
>    ② 动效**不得驱动带阈值判定的 `progress`**，除非弹簧不过冲 ——
>    播放器卡片的 `progress` 同时是命中测试开关（`<0.01f` 卸载展开态子树、`>0.99f` 吞事件），
>    会过冲的弹簧会让它反复跨越阈值 ⇒ 子树挂载抖动 + 命中区闪烁。
>    `AppMotionSpecTest` 断言 `playerExpand` / `playerCollapse` 的 `dampingRatio >= 1.0`；
>    ③ **新增**动效一律零重组（`Animatable` + 在 `graphicsLayer { }` / draw 阶段读取），
>    `spring(` 只能来自 `AppMotion`（`AppMotionSpecTest` 扫源码树）。

**⑤ v2.5.1 铁律 3（影响体验的动效默认开 + 必须给开关）** — `AGENTS.md:3221-3232`（原文摘录）：

> 3. **影响体验的动效默认启用，但必须提供开关，由用户选择。**
>    … v2.5.1 起改为「默认开 + 用户可关」。落地要求：
>    - 新增**影响体验**的动效（页面转场、列表入场、面板动效…）默认**开**，
>      且在设置页有一个**可持久化**的开关；「关掉」必须逐字节回到「没有这个动效」的行为；
>    - 开关的**默认值必须有迁移规则**：键不存在（老用户）按默认值处理，并在单测里钉住（铁律 5）。
>      **不得**把「上一版没有这个功能」解读成「用户选了关」；
>    - 开关必须**立即生效**，不得要求重启 —— 状态提升到设置页与消费点的共同祖先；
>    - 开关**不豁免**规则 1：仍然要用 **release 包**给出量化数据。

**⑥ v2.6.0 的 `AnimatedContent` 教训（子树会被卸载，`remember` 会丢）** —
`AGENTS.md:3896-3900` 与 `docs/verification/v2.6.0/probe-fold.md:724-734`：

> - **折叠状态必须是同一类纪律**（`LibrarySectionFoldSetting`）：状态归 prefs，
>   **不许只放 `remember`** —— 本页被 `AnimatedContent(targetState = selectedCategory)`
>   包裹，切走再切回会**卸载整棵子树**，只放 `remember` 会静默展开
>   （用户看到「我收起来的又自己打开了」）；

> `LibraryScreen.kt:268-285` 用 `AnimatedContent(targetState = selectedCategory)` 承载四个 tab，
> `LibraryPlaylistsTab` 只在 `1 ->` 分支里（`:350-366`）。切走再切回时，
> 该分支会离开 composition（框架语义，见 §11 未验证项），因此：
> - **`remember { mutableStateOf(false) }` 装的折叠态会在切 tab 后丢失** ⇒ 表现为「我明明折叠了，切回来又展开了」…
> - 正确做法：折叠态**每次组合都从 `ncrust_settings` 读**…写入时同步落盘。

**对本版的直接含义**：任何"动效档位 / 开关"的状态**不许只放 `remember`**；
播放器卡片子树本身也在 `progress <= 0.01f` 时不挂载（`PlayerCard.kt:317/721`），
同一条纪律在这里同样成立（现存范式：`VisualizerPrefs.effects` 是进程内 `MutableState` + 落盘键）。

**⑦ Kanesumi Design 约束** — `AGENTS.md:111`：

> 1. **Kanesumi Design** — right-angle cuts, no curves, no rounded corners, information-first.

配合 `AppShapes.kt:24-33` 的原文（「直角、无圆角」+ 唯一落点 + 扫源码树的守卫），
新动效若需要圆角/描边，**只能**走 `AppShapes` 与 `appCoverFrame`（`AppVisualModifiers.kt:62-70`）。
按压回弹可走 `Modifier.appPressScale`（`:100-129`）：它用 `Animatable` + `graphicsLayer` 读值、
在 `PointerEventPass.Initial` **不消费**事件（`:85-95` 原文：「绝不消费事件」）。

---

## 7. 对 v2.9.0 实现的直接结论

1. **背景层有且只有一个插入点**：`PlayerCard.kt` 根 Box 内、`695-702` 那层纯色背景**之后**、
   `705-713` 折叠态卡背**之前**（详见 `probe-landscape-motion.md` §2）。插入点是**共用**的，
   横竖屏同一条路径，不需要在三个 `when` 分支里各加一次。
2. **模糊是新东西**：仓库零 `Modifier.blur` / 零 `RenderEffect`。`Modifier.blur` 在 API 31- 上是
   无操作（minSdk 24），`RenderEffect` 需要 API 31+ —— **必须给出降级形态**
   （例如"低版本用封面主色径向渐变"），并写进未验证清单；这条不能靠"看起来也能跑"糊过去。
3. **封面主色不必新写提取器**：`CoverThemeExtractor` / `CoverPaletteExtractor` / `androidx.palette` 三条
   都已经在 `Dispatchers.Default` 上跑并带 URL 级缓存。要补的是**播放器侧的读取通路**
   （今天 `coverTheme` 只在 `MainActivity` 被 `accentSource == COVER` 消费，`MainActivity.kt:318`）。
4. **RMS 是这一版最大的结构性缺口**（§5）：今天**没有**任何非波形组件能便宜地每帧读到"当前 RMS"，
   而且**波形条不挂载时那个数据是死的**（手机竖屏 / 手机横屏非大屏两格）。
   必须先决定"帧时钟归谁"（提升 / 共享 pump / 新增 `@Volatile lastRms`），再谈 A/B/C 三档；
   **绝不允许起第二个 `pump` 调用者**（会改掉平滑时间常数与峰值保持语义）。
5. **节拍判据别重写**：`WaveformEffectsState.update` 的 onset 检测（基线 800ms / 冷却 180ms /
   `ONSET_MIN_RMS = 0.12`）已经过单测与探针收敛，B 档的"播放键脉冲 / 进度条脉冲"应当复用它，
   新增的只是"onset ⇒ 一个 0..1 的脉冲标量"这层（今天直接 `spawnRipple()`）。
6. **档位命名要与既有体系统一**：`VisualizerTier`（SIMPLE/REFINED/SHOWCASE）+ `VisualizerEffects` 的能力位
   **已经**按 A/B/C 分组、已经有 `of()` 唯一映射与 8 个落盘键 + 键名契约单测。
   新 UI 动效应当**扩展这张表**，不要新造 `when(tier)`；档位默认值必须走
   `VisualizerTier.defaultTier`（低端机简洁）与 `VisualizerPrefs.hasExplicitTier` 的"缺 key ≠ 选了默认"口径。
7. **所有新动效必须是"零重组 + 有界 + 可关"三件套**：值只在 `graphicsLayer { }` / draw 阶段读；
   动画量必须有上界与显式停止条件（照 `WaveformRing.pump` 的"收敛即返回 false"契约，
   `WaveformRing.kt:130-131`）；开关默认开、落盘、立即生效、关掉逐字节回到没有它。
8. **封面交叉淡入今天只以"旧位图垫底"存在**（`StableCover` 的 `lastBitmap`，`PlayerCard.kt:1783-1830`）：
   要做真正的 alpha crossfade，最省的做法是在这个组件里加一层 `Animatable` alpha，
   **不要**在调用点再叠一个 `Image`（会多一次解码 + 多一个命中层）。
