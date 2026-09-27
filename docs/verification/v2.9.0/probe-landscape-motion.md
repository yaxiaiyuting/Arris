# v2.9.0 探针 · 横屏播放器动效（背景模糊 / 全宽波形 / 视差）

> **性质**：纯只读探针。本轮**没有**修改任何源文件、**没有**编译、**没有**跑单测、**没有**真机/模拟器。
> 数字分两类，逐处标注：**[码]** = 由源码算式推出；**[测]** = 仓库既有探针的真机实测值（给出处）。
> 配套文档：`probe-ui-motion.md`（竖屏布局树、封面组件、RMS 数据源、AppShapes/AppMotion 全量 token）。

> ### ⚠️ 探测时点与并发实现（**读本文前必看**）
>
> 本轮探测期间，**另一个 agent 正在同一个工作区写 v2.9.0 的实现**（不是本探针改的）：
> `M ui/player/AudioVisualizer.kt`（+25 / −79）、`M ui/theme/AppMotion.kt`（+76，新增幅度 token）、
> 新目录 `?? ui/player/motion/`（`MotionClock.kt` / `MotionEnvelope.kt` / `MotionEffects.kt` /
> `MotionPrefs.kt` / `MotionBackdrop.kt` / `CoverBlur.kt`），实测时间 2026-09-27 21:43–21:46。
> 本文的 **「未找到」= 对 v2.8.0 基线成立**；其中「帧时钟只能有一条」「模糊在 minSdk 24 上要有降级」
> 这两条已被那份实现按同方向处理（`MotionClock` 共享单条循环；`CoverBlur` 走 CPU 侧而非 `RenderEffect`）。
> 本文的几何数字、插入点与碰撞分析与这些改动**互不影响**。

---

## 0. 结论速览

| # | 问题 | 答案 |
|---|---|---|
| 1 | 横屏左栏多宽？ | **[码]** `44%`（大屏模式）或 `1 - 0.56 × wideSplit`（宽屏两栏）；手机横屏 800dp ⇒ **352dp**，平板横屏 1280dp ⇒ **563dp** |
| 2 | 波形条今天占多大？ | **[码]** 左栏宽减 `2 × 16dp`，手机横屏 **320dp = 整屏 40.0%**、平板横屏 **531dp = 41.5%**；高 **[测]** 39.9dp / 56dp |
| 3 | 背景层插哪？ | **`PlayerCard.kt:702` 与 `:705` 之间**（根 Box 内、纯色背景之后、折叠态卡背之前）。现有唯一背景 = `PlayerCard.kt:695-702` 的 `LocalMetroColors.current.background` |
| 4 | 全宽波形怎么落？ | 两条路：**(a)** 把 `AudioVisualizerSlot` 的单实例挪成"底部整宽"宿主（**不能挂两个实例** —— 会双份 `WaveformStore.pump`）；**(b)** 放进 §3 的背景层（触摸零新增，代价是全屏失效重绘） |
| 5 | 视差能读什么？ | **零新增手势**可读：`progress`（`MainActivity.kt:969`）、`lyricAnimProgress`、`queueSlideProgress`、`controlsCollapse`、`wideSplit`；全仓**没有** `draggable`/`parallax`/`detectDragGestures`（0 命中） |
| 6 | v2.5.4 做过什么？ | 只做了"**平板 + 横屏**这一格补上挂载"：把可视化同时挂到宽屏两栏左栏（`PlayerCard.kt:1138-1144`），判据收进 `PlayerLayout.visualizerSlot`。**没有**做全宽、没有做模糊 |

---

## 1. 当前横屏几何（精确数字）

### 1.1 你会落在哪条分支：由两个谓词决定

```kotlin
// PlayerCard.kt:206-222（原文，节选）
val isWidePlayer = LocalConfiguration.current.screenWidthDp >= 600
val bigScreenActive = PlayerLayout.isBigScreenActive(
    requested = bigScreen,
    orientationLandscape = LocalConfiguration.current.orientation ==
        Configuration.ORIENTATION_LANDSCAPE,
)
val usesSideCover = isWidePlayer || bigScreenActive
```

- `isWidePlayer`：横屏时窗口宽必然 ≥ 600dp（[测] PCL110 横屏 800dp、WGR-W09 横屏 1280dp，
  `docs/verification/v2.8.0/probe-waveform.md:138-140`）⇒ **横屏永远为 true**；
- `bigScreenActive = requested && orientationLandscape`（`PlayerLayout.kt:209-210`）；
- `requested`（= `bigScreenMode`，`MainActivity.kt:176`）在手机上**会被自动置位**：
  `AutoRotateWatcher`（调用点 `MainActivity.kt:238-241`，实现 `:748-776`）在
  `BigScreenOrientation.shouldAutoEnterBigScreen(autoRotate, playerExpanded, windowLandscape, bigScreen)
  = autoRotate && playerExpanded && windowLandscape && !bigScreen`（`BigScreenOrientation.kt:120-125`）
  成立后**延迟 `AUTO_ENTER_SETTLE_MS = 250L`**（`:41`）进入。而 `auto_rotate` **默认开**
  （`RotationSetting.kt:41-42`）⇒ **手机横屏展开播放器的默认落点是"大屏模式"那一支**，
  与既有探针的表格一致（`probe-waveform-tablet.md:138`「requested(bigScreen) true（自动进大屏）」）。
  用户手动关掉自动旋转后才停在"宽屏两栏"那一支。

### 1.2 两条横屏分支的栏宽与内容

**(a) `bigScreenActive` — `PlayerCard.kt:958-1102`**

```
Row(fillMaxSize) + windowInsetsPadding(displayCutout)          :968-982
├─ Column  weight(BIG_SCREEN_LEFT_FRACTION = 0.44f)            :984-988
│   ├─ Box(fillMaxWidth).weight(1f)  ← 封面落点，自己不画东西   :992-1002
│   ├─ if (visualizerSlot) AudioVisualizerSlot(...)            :1012-1018
│   ├─ Spacer(10.dp)                                           :1019
│   ├─ Row(padding(horizontal = 16.dp))                        :1020-1079
│   │   ├─ Column(weight(1f)).clickable { onSongInfoClick() }   :1026-1046
│   │   ├─ Spacer(10.dp) + PlayerQualityChip                    :1047-1066
│   │   └─ RotationToggleButton(size = 40.dp, icon = 22.dp)     :1071-1078
│   └─ Spacer(8.dp)                                            :1080
└─ Column  weight(1f - 0.44f = 0.56f)                          :1083-1087
    ├─ Box(fillMaxWidth).weight(1f) { playerPanels(...) }       :1088-1098
    └─ playerControls()   ← 横屏扁平控制条在**右栏底部**         :1099
```

**(b) `isWidePlayer` — `PlayerCard.kt:1103-1197`**

```
Row(fillMaxSize)                                              :1106
├─ Column weight(wideLeftFraction)   // 1 - 0.56×wideSplit      :1107-1111
│   ├─ Box(fillMaxWidth).weight(1f)  ← 封面落点                 :1115-1125
│   ├─ if (visualizerSlot) AudioVisualizerSlot(...)   // 仅平板横屏 :1138-1144
│   ├─ Box(widthIn(max = 560.dp).padding(horizontal = 24.dp))
│   │     .clickable { onSongInfoClick() }   ← 歌名/歌手          :1146-1170
│   ├─ Spacer(12.dp)                                           :1171
│   ├─ Box(widthIn(max = 560.dp)) { playerControls() }  ← 控制条在**左栏**  :1172-1181
│   └─ Spacer(8.dp)                                            :1182
└─ Box weight((1f - wideLeftFraction).coerceAtLeast(0.0001f))   :1186-1196
      { playerPanels(...) }                                    :1191-1195
```

两条分支的**关键差别**（也是"背景层该放在哪"的判据）：
控制条在 (a) 里属于**右栏**、在 (b) 里属于**左栏**；右栏宽度 (a) 恒 56%、(b) 随 `wideSplit` 动画到 56%

- `wideSplit = animateFloatAsState(if (showLyrics || showQueue) 1f else 0f, tween(280, CubicBezier(0.2f,0f,0f,1f)))`
  （`PlayerCard.kt:196-200`）；大屏模式下有 `LaunchedEffect(bigScreenActive)` 强制打开歌词面板
  （`461-463`），所以 (a) 里右栏不会停在 0 宽。

### 1.3 逐项数字表

| 量 | 手机横屏（PCL110） | 平板横屏（WGR-W09） | 来源 |
|---|---|---|---|
| 窗口 | `800 × 363 dp` | `1280 × 768 dp`（sw800dp / 320dpi / 2560×1600px） | [测] `probe-waveform-tablet.md:123-125` |
| 左栏宽 | **352dp = 44%** | **563dp = 44%** | [码] `BIG_SCREEN_LEFT_FRACTION`；[测] `probe-waveform-tablet.md:603`「0.44 × 1280dp ≈ 563dp」 |
| 右栏宽 | **448dp = 56%** | **717dp = 56%** | [码] `1 - 0.44` |
| 左栏内波形条 | 宽 `352 - 32 = 320dp`（**40.0% 屏宽**），高 `363×0.11 ≈ 39.9dp` | 宽 `563 - 32 = 531dp`（**41.5%**），高 `768×0.11 = 84.5 → 56dp` | [码] `PlayerCard.kt:2002-2005` + `PlayerLayout.kt:190-192`；[测] 高度值见 `probe-waveform-tablet.md:138-140` |
| 封面边长 | `min(352, 封面区高)`；源码注释给的观感值 **≈230dp** | `min(563, 封面区高)` ⇒ **563dp**（贴满左栏宽） | [码] `PlayerLayout.kt:241-242`；[测/注] `PlayerCard.kt:967` |
| 托盘落差（非活动层） | 折叠态 miniBar 高 80dp（`TrayLayout.kt:75`） | 同 | [码] |

### 1.4 横屏现在空着/没被利用的区域（逐块点名）

用 [码] 算式在手机横屏（800×363dp，大屏模式）上展开：

1. **封面两侧的横向留白** —— 封面区 `Box` 是 352dp 宽 × 约 253dp 高的矩形（`992-1002`），
   封面取 `min(w,h)` ⇒ 边长 ≈253dp，**左右各 ≈49.5dp × 253dp 完全不画东西**。
2. **波形条只覆盖左栏的一部分** —— 320dp / 800dp = **40%**；右栏（448dp × 全高）
   在整条高度上**一个波形像素都没有**（`probe-waveform-tablet.md:300` 对 v2.5.4 前的描述同形：
   「宽屏两栏分支…**没有任何可视化挂载点**」，该缺口已由 v2.5.4 在左栏补齐，但**宽度没变**）。
3. **整屏背景** —— 唯一底色是 `PlayerCard.kt:695-702` 的一层纯色；
   没有模糊、没有封面底图、没有渐变（`grep -rn "Modifier.blur\|RenderEffect"` **0 命中**）。
4. **平板横屏**：封面 563dp 正方形、封面区高 ≈642dp ⇒ **上下各空 ≈39.5dp**（合计 79dp）；
   波形条同理只占整屏 41.5%。
5. **宽屏两栏且 `wideSplit = 0`** 时右栏 `weight` 被压到 `0.0001f`（`1186-1188`）——
   这不是留白而是"被压扁"；横屏大屏模式不会停在这个状态（`461-463` 强制开歌词）。

---

## 2. 全屏背景层插在哪（**不改任何现有布局结构**）

### 2.1 现在谁在画背景

| 层 | 落点 | 内容 |
|---|---|---|
| 全屏底色 | **`PlayerCard.kt:695-702`** | `Box(fillMaxSize).graphicsLayer{translationY = statusBarPx * (1f - progress.value)}.background(LocalMetroColors.current.background)` |
| 折叠态卡背 | **`PlayerCard.kt:705-713`** | `Box(fillMaxSize).graphicsLayer{alpha = (1f - progress.value*5f)…}.background(LocalMetroColors.current.surface)` |
| 内容层 | `PlayerCard.kt:715-719` | `Column(fillMaxSize).systemBarsPadding()`（三个 `when` 分支都在它里面） |
| 页面根（播放器之外） | `ui/CustomBackgroundLayer.kt:46-95` | 用户**手动导入**的静态背景图 + 遮罩；**不是**当前封面 |

### 2.2 插入点（**唯一、共用、横竖屏同一条路**）

```kotlin
// PlayerCard.kt:695-719（原文骨架，★ 就是新层唯一该放的位置）
Box( … .background(LocalMetroColors.current.background) )   // :695-702  现有纯色背景
/* ★ 新背景层插在这里：Box(Modifier.fillMaxSize().graphicsLayer { … }) */
Box( … .background(LocalMetroColors.current.surface) )       // :705-713  折叠态卡背
Column( Modifier.fillMaxSize().systemBarsPadding() ) { … }   // :715-719  内容层
```

为什么是这里（四条，都要满足）：

1. **必须在根 `Box`（`:561-689`）之内** —— 根 Box 是 `PlayerCardOverlay.kt:49-51`
   那层 `graphicsLayer { translationY = collapsedOffsetY + (0-collapsedOffsetY)*progress.value }`
   的受动者；插在里面 ⇒ 背景层随卡片一起平移，**收起时自动让位给 mini bar / 下层页面**，
   不需要任何额外条件。
2. **必须在 `:715` 的 `Column` 之前** —— 之后声明的兄弟节点 z 序更高；
   背景层要在内容（封面、歌词、控件）之下。
3. **必须在 `:695-702` 的纯色层之后** —— 否则纯色 `background` 会把它整块盖住
   （这正是 `CustomBackgroundLayer` 的 KDoc 警告过的形状：`CustomBackgroundLayer.kt:36-40`）。
4. **`translationY` 要抄 `:699` 那一句**（`statusBarPx * (1f - progress.value)`）——
   不抄的话折叠态会在 miniBar 上方多出一条背景（`691-694` 的注释就是这条坑）。

**触摸影响：零。** 新层只挂 `fillMaxSize` + `graphicsLayer` +（模糊/绘制用）`drawWithContent`，
**不挂 `pointerInput` / `clickable`** —— Compose 的命中测试只登记带指针修饰符的节点，
纯绘制子节点不进命中表；卡片的两处手势仍然挂在根 Box 自己身上
（`580-592` 展开态吞事件 / `596-685` 整卡拖拽，后者在 `bigScreenActive` 时**直接 return**，`:601`）。

### 2.3 需要一并决定的三个实现细节（本轮只记录，不给结论）

1. **模糊的 API 底线**：`Modifier.blur` 在 API < 31 是 no-op、`RenderEffect` 需要 API 31+，
   而 `minSdk = 24`（`AGENTS.md` 工具链表）。必须准备**降级形态**（例如封面主色径向渐变）。
2. **封面从哪来**：`StableCover` 组合内已经有 `lastBitmap: Bitmap?`（`PlayerCard.kt:1783-1793`，
   1080px、跨切歌保留）；`PlaybackService` 另有一份 `currentArtworkBitmap` + `downscaleArtwork`
   （`PlaybackService.kt:1061-1069`）。两条都能用，见 `probe-ui-motion.md` §4.3。
   主色不必新写提取器（`CoverThemeExtractor` / `CoverPaletteExtractor` / `androidx.palette` 已存在），
   缺的只是"播放器读得到"这条通路（今天只有 `MainActivity.kt:300-318` 读）。
3. **不要让它变成第二个"整屏吞事件"的层**：背景层一旦挂 `pointerInput`，
   就会与 `AGENTS.md` 触摸陷阱 §1/§4/§5 的三条（`alpha=0` 不退出命中、`fillMaxSize` 死带、
   隐藏要条件挂载）正面冲突。

---

## 3. "背景级波形"的两个选项（挂载点 / 碰撞 / 代价）

先记住三条**硬约束**（它们决定了选项的形态）：

- **只能有一个 `AudioVisualizerBars` 实例**：它的 `LaunchedEffect` 是唯一的帧时钟，
  每帧调用 `WaveformStore.pump(...)`（`AudioVisualizer.kt:374-404` → `:181-188`）。
  挂第二个实例 = 同一帧 `pump` 两次 ⇒ 平滑时间常数被减半、峰值保持计时被扣两次
  （`WaveformRing.kt:214-231`）—— **这是静默的观感回归，不是性能问题**。
  所以"全宽波形"与"左栏波形"只能是**同一个实例换宿主**，不能并存。
- **`AudioVisualizerBars` 今天不挂任何命中面**（`PlayerCard.kt:1982-1983` 原文：
  「本组件不挂任何 `pointerInput` / `clickable`，不新增命中面」）；
  C 档的 `tapInteraction` 是**默认关**的可选件（`AudioVisualizer.kt:344-352`；
  默认值 `VisualizerPrefs.kt:74/113`）。
- **它今天所处的区域是手势密集区**（v2.8.0 原文，`AudioVisualizer.kt:296-309`）：

> **附近已有命中面**：大屏左栏里，可视化条正下方就是 `clickable { onSongInfoClick() }`
> 的歌名/歌手区，卡片根部还有两个 `pointerInput`（整卡拖拽 / 展开态吞事件）。
> 整卡拖拽在 `bigScreenActive` 时被显式停用（`PlayerCard.kt` 的 `if (bigScreenActive) return@pointerInput`），
> 但"某一形态下刚好没冲突"不等于**手势分解（slop / 方向认领）**是对的。

### 选项 (a)：把现有条**换成底部整宽**（同一个实例，改宿主）

- **挂载点**：`AudioVisualizerSlot`（`PlayerCard.kt:1992-2008`）是唯一落点，
  今天被两处调用（`1012-1018` 大屏左栏 / `1138-1144` 宽屏两栏左栏）。
  改成"整宽"只需要**把这两处调用删掉、换成一处**：
  在根 Box 里（§2.2 的插入点附近，例如 `:713` 之后、`:715` 之前）挂
  `Modifier.fillMaxWidth().align(Alignment.BottomCenter)` + `navigationBarsPadding()` 的同一个 Slot。
- **碰撞**：
  - 触摸：只要不加 `pointerInput` 就**零碰撞**（同上，纯绘制子节点不进命中表）。
    一旦要开 C 档 `tapInteraction`，它会落在根 Box 两个 `pointerInput` 的命中区里
    （`580-592` 的吞事件在 `isOverCardVisibleArea` 为 false 时生效 ——
    而 `isWidePlayer && !bigScreenActive` 时**左栏被显式排除在卡片可见区之外**，`:553`），
    也就是"大屏模式下有豁免、宽屏两栏下没有"这种非对称，必须真机 A/B 才能声称结论。
  - 视觉：底部整宽会与**控制条**重叠 —— (a) 大屏模式控制条在**右栏底部**（`:1099`）、
    (b) 宽屏两栏控制条在**左栏**（`:1172-1181`）。因为背景层 z 序更低，
    表现是"波形在控制条后面透出来"，不是遮住控件；要避免就把它压在控制条上方（例如 `bottom = 96.dp`）。
  - 布局：`bigScreenActive` 左栏的封面区是 `weight(1f)`，把波形搬走后封面会**变大** ⇒
    **这不是"零布局变化"**（与 §2 的背景层不同），要有意接受或另加占位。
- **代价**：画布面积从 320×40dp ⇒ 800×96dp 量级（约 6 倍像素），
  每帧一次全宽重绘；仍在"只在有信号时排帧"的契约内（`WaveformRing.kt:130-131/143-161`），
  但低端机上按 `FrameBudgetPolicy` 的判据（60 帧窗口里 24 帧超标 = 40% 即一次性降档，
  `FrameBudgetPolicy.kt:24-26` / `:104-107`）有可能被自动降档。

### 选项 (b)：做进 §2 的**背景层**（贴在歌词后面）

- **挂载点**：§2.2 那个新 Box 内部再画一层 Canvas（`drawWithContent` / `Canvas`），
  读 `WaveformStore.generation` + `WaveformStore.snapshot(bars, peaks)`（`AudioVisualizer.kt:166-169/191`）。
- **碰撞**：**触摸零碰撞**（同 §2.2，无指针修饰符）。
  视觉上它会被三样东西挡住一部分：大封面 overlay（`1643-1683`，z 序更高）、
  右栏歌词/队列面板的文字（面板本身**没有不透明底**，所以能透出来）、
  底部控制条与托盘（折叠态 miniBar 自带 `surface` 底色，`:1422`）。
- **代价**：这是**全屏失效重绘**（800×363dp），比选项 (a) 还大一档；
  而且 28 根柱铺满 800dp ⇒ 单根 ≈28.6dp，观感会很"粗"。
  更省的做法是**只画底部一条带**（例如高度 = `visualizerHeightDp`，`align(BottomCenter)`），
  这样点亮的区域小、又与"背景级"的视觉意图一致。
- **一个必须回答的问题**：背景波形也必须**有人 `pump`**。§3 开头的约束意味着
  选项 (b) 需要把帧时钟从 `AudioVisualizerBars` 里**提出来**（或让它与背景层共用同一个实例），
  详见 `probe-ui-motion.md` §5.4。

---

## 4. 视差：今天有哪些"可读、不新增手势"的状态

### 4.1 全仓现状：**没有任何 pager / draggable / parallax**

```
$ grep -rn "parallax\|draggable(\|detectDragGestures\|detectHorizontalDrag" app/src/main --include=*.kt
（零命中）
```

（唯一形态相近的是 `QueueView.kt` 的拖拽重排，它用 `pointerInput` + `Animatable(startTop)`
自己实现：`QueueView.kt:192 / 285 / 314` —— 那是**队列内部**的纵轴拖拽，与播放器外部动效无关。）

### 4.2 可被视差层读取的现成状态（**全部零新增手势**）

| 状态变量 | 类型 | 拥有者 / 行号 | 语义 | 视差可用性 |
|---|---|---|---|---|
| `progress` | `Animatable<Float, AnimationVector1D>` | 创建 `MainActivity.kt:969`；传参 `PlayerCardOverlay.kt:16` → `PlayerCard.kt:101`；读取 `PlayerCardOverlay.kt:50`、`PlayerCard.kt:1655-1673` 等 12 处 | 0 = 收起成 mini bar，1 = 全屏 | **首选**：整卡级视差（背景反向位移/缩放）。已有"在 `graphicsLayer` 里读"的范式 |
| `lyricAnimProgress` | `Animatable<Float>` | `PlayerCard.kt:327` | 0 = 大封面，1 = 小封面/内容视图 | 封面 ↔ 内容层次的视差 |
| `queueSlideProgress` | `Animatable<Float>` | `PlayerCard.kt:328` | 0 = 歌词位，1 = 队列位 | 面板横向视差 |
| `controlsCollapse` | `Animatable<Float>` | `PlayerCard.kt:343` | 0 = 控制栏展开，1 = 收起 | 控制栏与背景的差速位移 |
| `wideSplit` | `Float`（`animateFloatAsState`） | `PlayerCard.kt:196-200` | 0 = 单栏，1 = 两栏（左 44% / 右 56%） | **横屏专用**：左右栏进出的视差 |
| `cardRootOrigin` / `wideCoverCenter` / `wideCoverSizePx` | `Offset` / `Float` | `PlayerCard.kt:273-275`（写：`564`、`998-1000`、`1122-1123`） | 卡片原点 / 封面区实测中心与边长 | 只做几何换算，**不要**用来算动画（动画期滞后，见 `AGENTS.md` 触摸陷阱 §3） |
| `smoothCurrentIndex` | `Animatable<Float>` | `NcrustLyricsPanel.kt:185`（读：`:548`） | 连续当前歌词行 | 歌词区内部视差（行距/景深） |
| 歌词/队列滚动位置 | `LazyListState` | `NcrustLyricsPanel.kt:218`、`QueueView.kt:86` | 列表滚动 | 需要 `snapshotFlow`/`derivedStateOf`，**会引入重组**，慎用 |

### 4.3 唯一的两个"手势源"（如果将来真要做手势视差）

| 手势 | 落点 | 备注 |
|---|---|---|
| 整卡纵拖 | `PlayerCard.kt:596-685`（`pointerInput(hasSong, bigScreenActive)`） | **大屏模式下整段 `return@pointerInput`**（`:601`）⇒ 横屏大屏里**没有**这个手势 |
| 控制栏收起/恢复纵拖 | `PlayerCard.kt:361-409`（`pointerInput(hasSong, isWidePlayer)`） | **宽屏/横屏恒不启用**（`:362` `if (!hasSong || isWidePlayer) return@pointerInput`；注释 `:342`「只在窄屏生效」） |
| 进度条横拖 seek | `SlimProgressBar.kt:126-146` | 右栏/(b) 左栏的控制条内部，与视差无关 |

**结论**：横屏下**两个纵拖手势都被显式停用**，所以横屏视差**只能**由
`progress` / `wideSplit` / `lyricAnimProgress` 这些"已有动画量"驱动（这也正是最省、
最符合 `AGENTS.md:191`「All visual properties … computed inside `graphicsLayer { }`」的做法）。
若要做"手指跟随的视差"，等于在**手势最干净的形态**（横屏）里新开一个检测器，
但按 v1.7.0 · P0 与 `PlayerDragSnap.kt` 的纪律（`PlayerDragSnap.kt:30-36`），
必须先有"手势归属的纯函数 + 单测 + 真机 A/B"，否则不许上。

---

## 5. v2.5.4 的"平板横屏波浪条"到底做了什么（引用既有记录）

### 5.1 事实（源码）

- 判据收敛成纯函数 `PlayerLayout.visualizerSlot(enabled, bigScreenActive, isWidePlayer, isLargeScreen, orientationLandscape)`
  （`PlayerLayout.kt:79-87`），KDoc 里带**六格 A/B 表**（`:66-77`）：
  只有「**平板 + 横屏**」这一格由"无"变"有"，其余五格与 v1.8.0 逐格相同。
- 关键区分：「平板」只能看 `smallestScreenWidthDp >= 600`，**不能**用 `screenWidthDp >= 600`
  （手机横屏也成立）—— 原文写在 `PlayerLayout.kt:25-34` 与 `:61-64`。
- 挂载点收敛成一个 composable `AudioVisualizerSlot`（`PlayerCard.kt:1992-2008`），
  在**两处**共用：大屏左栏（`:1012-1018`，v1.8.0 起）+ **宽屏两栏左栏**（`:1138-1144`，v2.5.4 新增）。
  新增那处的原文注释（`:1126-1137`）明确写了"位置与横屏大屏左栏逐像素同款"。
- 高度算式搬进 `PlayerLayout.visualizerHeightDp`（`:190-192`），可单测；
  平板横屏 `768 × 0.11 = 84.5 → clamp 56dp`（`:186-188` 的 KDoc 就举了这两个设备的例子）。

### 5.2 明确的边界（**没有**做的事）

| 没做 | 证据 |
|---|---|
| **没有**把波形做成全宽 | 挂载点仍在左栏 `Column` 内，宽度 = 左栏宽 − `2 × 16dp`（`PlayerCard.kt:2003-2004`） |
| **没有**背景模糊 / 封面底图 | 全仓 `blur` / `RenderEffect` 零命中 |
| **没有**给平板补 ⤢ 入口 | `AGENTS.md:3517`「**不给平板补 ⤢ 入口** — 与「波浪条不显示」不同源；混改会让两者的回归面互相污染」（v2.5.5 · E 才单独做） |
| **没有**在手机横屏非大屏那一格显示 | `PlayerLayout.kt:72`（那一格 `❌`，与 v1.8.0 一致） |
| **没有**动拖拽交互 | C 档 `tapInteraction` 默认关，且文案如实写成「点按切换着色」（`VisualizerStrings.kt` 的 KDoc 第 1 条硬要求；`AudioVisualizer.kt:293-309` 的降级理由） |

### 5.3 与 v2.9.0 的关系

v2.5.4 只解决了"**挂不挂**"，**没有**解决"**多大/多宽/有没有背景**"。
本次任务书说的"横屏波形被挤在左侧一条窄带"，在 v2.5.4 之后**依然成立**（宽度一点没变），
只是平板横屏从"完全没有"变成"有 41.5% 宽的一条"。**所以 v2.9.0 要在宽度上做文章，
必须改挂载点（§3），而这会碰到三条既有约束：单一实例、单一帧时钟、手势密集区。**

---

## 6. 对 v2.9.0 实现的直接结论

1. **背景层的插入点唯一且共用**：`PlayerCard.kt:702` 与 `:705` 之间（根 Box 内、纯色背景之后、
   折叠态卡背之前），**横竖屏同一条路径**，不需要在 `when` 的三个分支里各加一遍；
   必须带上 `translationY = statusBarPx * (1f - progress.value)` 这一句（抄 `:699`）。
2. **触摸零新增是正确的默认**：背景层只做绘制、不挂 `pointerInput`/`clickable`；
   一旦要交互，就等于在一张已经有"整卡拖拽 + 展开态吞事件 + `clickable{onSongInfoClick}`"
   三个命中面的屏幕上再加第四个，按 `AGENTS.md` 触摸陷阱与 v2.8.0 的降级先例，
   必须走"纯函数判据 + 单测 + 真机 A/B"。
3. **全宽波形只能"换宿主"，不能"加倍"**：`AudioVisualizerBars` 的帧循环是唯一的
   `WaveformStore.pump` 调用者（`AudioVisualizer.kt:374-404`），挂两个实例会把平滑时间常数与
   峰值保持语义改掉；选项 (a) 换挂载点、选项 (b) 做进背景层，二者都需要先把帧时钟的所有权定下来。
4. **选项 (a) 不是零布局变化**：把波形从 `bigScreenActive` 左栏搬走后，
   该栏的封面区是 `weight(1f)`（`:995`）会自动长高 ⇒ 封面变大；要有意接受或补占位。
   选项 (b) 是零布局变化，代价是全屏失效重绘（建议只画底部一条带）。
5. **视差不需要新手势**：横屏下两个纵拖检测器一个被 `bigScreenActive` 关掉（`:601`）、
   一个被 `isWidePlayer` 关掉（`:362`），所以横屏视差应完全由
   `progress` / `wideSplit` / `lyricAnimProgress` 驱动，全部在 `graphicsLayer { }` 里读值。
6. **`wideSplit` 是横屏专用的"免费"视差轴**：左栏 100% ↔ 44%、右栏 0 ↔ 56%，
   幅度巨大且已经在跑 `tween(280, CubicBezier(0.2,0,0,1))`（`PlayerCard.kt:196-200`），
   背景/封面可以按它做反向位移而**零新增动画**。
7. **数字预算（横屏改造的验收口径）**：手机横屏波形条今天 =
   **320dp × 39.9dp = 整屏 40.0% 宽**；平板横屏 = **531dp × 56dp = 41.5% 宽**。
   任何"全宽"方案应当给出新的这两个数字，并说明封面尺寸/控制条位置是否变化
   （[码] 封面 = `min(左栏宽, 封面区高)`，见 `PlayerLayout.kt:241-242`）。
8. **未找到的东西要如实写进未验证清单**：`Modifier.blur` / `RenderEffect` 全仓零使用 ⇒
   本仓库**没有**任何"模糊在 minSdk 24 上怎么降级"的先例可抄；
   这一条必须真机（含 API 24/25 设备）取证，不能只在 API 31+ 上验证一次。
