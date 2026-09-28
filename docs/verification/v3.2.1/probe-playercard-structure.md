# v3.2.1 探针 1 · PlayerCard 结构探针

> 采集时间：2026-09-28 · 代码基线：`v3.2.0-gpl`（`versionCode 55`，HEAD `122ab69`）
> 产物基线：`dist/Ncrust-v3.2.0-gpl-release.apk`（md5 `e3fe3b17f97f1f565c7cf5067f447b09`，
> 与崩溃报告 §设备上的包逐字节一致）
> 工具：`tools/method-size/method_size.py`（本轮新增，见探针 4）+ `dexdump`（build-tools 36.0.0）

本探针**先落盘、后改代码**（铁律 2）。所有数字都是实测，不是估算。

---

## 0. 一句话结论

`PlayerCard` 是**一个** 9092 code unit / **191 个寄存器**的 Kotlin 函数，
它同时承担「状态机 + 手势 + 三个互斥布局分支 + 托盘 + 封面落点 + 背景动效」六件事；
真正的巨型来源不是「代码行数多」，而是**三个布局分支的树构建指令全部长在同一个方法体里**。
拆分维度必须是**布局分支 + 托盘**（结构维度），不是「按状态域」——
状态域拆分会把 `remember` 的生存期改掉，直接违反铁律 25。

---

## 1. 事实基线

| 项 | 实测值 | 来源 |
|---|---|---|
| 文件行数 | 2290 行 | `wc -l` |
| `PlayerCard` 函数行范围 | **104 – 1899**（含签名与 KDoc 从 103 起） | 源码 |
| 形参个数 | **25**（含 16 个 lambda、2 个 Boolean、1 个 `Animatable`） | 源码 + mapping 签名 |
| 编译产物（release，R8 后） | `R4.b1.c(...)` = **9092 code unit / 191 寄存器 / ins 52 / outs 40** | `tools/method-size/method_size.py` + `dexdump -d` |
| mapping 还原 | `com.takahashirinta.ncrust.ui.player.PlayerCardKt.PlayerCard` | `app/build/outputs/mapping/release/mapping.txt` |
| 全 app 排名 | 第 1（第 2 名 `MainActivityKt.MainScreen` = 7317 / 143） | 同工具 `--only com.takahashirinta.ncrust` |
| dex 255 上限余量 | 寄存器 **191/255**（余 64）；`ins+outs` = 92（invoke 的**参数寄存器**上限 255，余量充足但已进入「值得监控」区间） | `dexdump` |

> `191 个寄存器`这个数字要与 v2.0.0 · HF1 的教训一起读：HF1 是**方法参数寄存器**撞 255
> （往 `Strings` 主构造器加字段），本处是**方法体局部寄存器**。两者都不是「能不能编译过」
> 的问题，而是「真机启动才崩」的问题 —— 静态检查看不出来，只能靠 dex 指标 + 真机。

---

## 2. 职责块（按源码行范围）

`PlayerCard` 的方法体可以切成 12 块。下表「副作用」一列是拆分的判据：
**带副作用的块不能随便搬**（搬了会改变副作用发生的时机/次数）。

| # | 行范围 | 职责 | 输入 | 输出 | 副作用 | 纯 UI |
|---|---|---|---|---|---|---|
| 1 | 155–213 | 基础状态与配置读取 | 形参、`LocalConfiguration/LocalDensity/LocalContext` | `hasSong` / `showLyrics` / `showQueue` / `density` / `context` / `coroutineScope` / `gestureNavLiftDp` / `strings` / `playerViewModel` / 14 个歌词相关 `collectAsState` / `libraryTick` / `isSongSaved` / `wideSplit` / `screenWidth*` | `remember`、`viewModel()`、`collectAsState` | 否 |
| 2 | 214–280 | 布局谓词与派生几何 | 上述配置 | `isWidePlayer` / `bigScreenActive` / `usesSideCover` / `wideLeftFraction` / `panelBoundaryPx` / `qualityPickerMaxHeightDp` / `visualizerSlot` / `visualizerHeightDp` / `waveBackdropHeightDp` | 无（纯计算，但读 `LocalConfiguration`） | 否（读 Local） |
| 3 | 282–336 | 触觉 + 动效能力位 + 封面位图持有 + 托盘几何 | `MotionPrefs.effects` / `WindowInsets.statusBars` | `haptic` / `motion` / `coverArtKey` / `coverArtBitmap` / `cardRootOrigin` / `wideCoverCenter` / `wideCoverSizePx` / `coverSizePx` / `miniScale` / `miniCoverCenterX/Y` / `topBarCoverCenterY` / `largeCoverCenterX/Y` / `boundsCenter` / `coverFloatPx` | `LaunchedEffect(song?.id){ MotionClock.clear() }`（**清动效包络，副作用**） | 否 |
| 4 | 338–394 | 阈值门（离散化）与动画轴 | `progress` | `miniBarEnabled` / `dismissEnabled` / `expandedMounted` / `collapsedHitGate` / `lyricAnimProgress` / `queueSlideProgress` / `lyricsEnabled` / `cardExpandedForInput` / `controlsCollapse` / `controlsHeightPx` / `controlsTopInCardPx` / `controlsCollapsedForInput` | `LaunchedEffect` 里 `snapshotFlow{progress}` 复位控制栏（**写 Animatable**） | 否 |
| 5 | 396–457 | 控制栏收起手势 | `controlsCollapse` / `controlsHeightPx` / `density` | `Modifier` 工厂 `controlsCollapseDrag()`、`toggleControlsCollapse()` | `pointerInput`（**手势副作用**）、`snapTo` / `animateTo` | 否 |
| 6 | 459–503 | 歌词可达性驱动的视图自动切换 | `lyricsReady` / `lyricsLoading` / `showQueue` / `bigScreenActive` | 写 `showLyrics` | 两个 `LaunchedEffect` | 否 |
| 7 | 505–550 | 面板切换动画与歌词定位触发 | `showLyrics` / `showQueue` / `progress` | 写 `lyricAnimProgress` / `queueSlideProgress` / `lyricLocateTrigger` | `LaunchedEffect` + `Animatable.animateTo` | 否 |
| 8 | 552–599 | 三个**语义互不混用**的命中测试谓词 | `progress` / `cardRootOrigin` / `panelBoundaryPx` / `controlsTopInCardPx` | `isPanelInteractive` / `isOverPanel` / `isOverCardVisibleArea` / `isOverCollapsibleControls` | 无 | 否（读 state） |
| 9 | 601–729 | 卡片根 `Box`：命中区让位 + 两个 `pointerInput`（展开态吞事件 / 整卡拖拽） | `collapsedHitGate` / `hitGateInsetDp` / `progress` / `totalDragDistancePx` | Modifier 链 + 两个手势 | **手势副作用**（吞事件、`snapTo`、`animateTo`） | 否 |
| 10 | 730–836 | 背景层：纯黑底 / 折叠卡背 / `MotionBackdrop` / `MotionFrameClock` / 背景级波形 | `motion` / `progress` / `coverArt*` / `isPlaying` | 三层 `Box` + 帧时钟 | `MotionFrameClock`（**每帧副作用**，必须恰好挂载一次） | 否 |
| 11 | 838–1530 | 主 `Column`：`playerControls` lambda + `playerPanels` lambda + 三个互斥布局分支 | 上面全部 | 展开态整棵子树 | 子组件自己的副作用 | 部分是 |
| 12 | 1532–1899 | mini 托盘 + 唯一封面 overlay + 收起按钮 overlay | 托盘几何 / `coverSizeDp` / `motion` | 三个叠加层 | `clickable` / `haptic` | 部分是 |

### 2.1 三个布局分支是主体（也是 9092 的来源）

| 分支 | 行范围 | 构成 | 该分支内的 `weight` |
|---|---|---|---|
| `bigScreenActive ->` | 1105–1244 | `Row` → 左 `Column`(封面区+可视化+信息行+音质 chip+旋转键) / 右 `Column`(面板+控制条) | 1132(Row) / 1170(Row) / 1141,1233(Column) |
| `isWidePlayer ->` | 1245–1336 | `Row` → 左 `Column`(封面区+可视化+歌名+控制) / 右 `Box`(面板) | 1251(Row) / 1326(Row) / 1260(Column) |
| `else ->`（窄屏） | 1337–1527 | 顶栏 `Box` + 面板 `Box`(含收起态悬浮播放键 + 大封面信息) + 控制栏 `Box` + 把手 `Box` | 1382(Column) |
| `when` 整体 | 1104–1528 | — | — |

**三个分支互斥**（`when` 的第一个命中即返回），所以在同一个 `when` 里的树构建指令
在**运行期**只会走一条，但在**编译期**全都被写进同一个方法体 —— 这正是巨人症的成因。
拆分它们**不改变任何运行期语义**（同一时刻仍然只有一棵子树被组合）。

---

## 3. 各块的输入/输出明细（拆分时的参数清单来源）

### 3.1 状态（`remember` / `Animatable`，**必须留在 PlayerCard**）

`showLyrics`、`showQueue`、`libraryTick`、`isSongSaved`、`wideSplit`、
`coverArtKey`、`coverArtBitmap`、`cardRootOrigin`、`wideCoverCenter`、`wideCoverSizePx`、
`lyricAnimProgress`、`queueSlideProgress`、`controlsCollapse`、`controlsHeightPx`、
`controlsTopInCardPx`、`lyricLocateTrigger`、`miniBarInteractionSource`、
`autoSwitchedForSong`。

这些状态的**生存期与 `PlayerCard` 相同**（无条件 remember）。把它们搬进子 composable
= 生存期绑到子 composable 的挂载条件上 —— 例如 `showLyrics` 若搬进「窄屏分支」，
宽屏下再回来就会被重置。**一律不搬。**

### 3.2 派生量（纯计算，可以随使用点搬）

`isWidePlayer` / `bigScreenActive` / `usesSideCover` / `wideLeftFraction` /
`panelBoundaryPx` / `visualizerSlot` / `visualizerHeightDp` / `waveBackdropHeightDp` /
各 `*Px` 几何量。它们**同时被多个块消费**（封面 overlay 用 `miniCoverCenterX`，
托盘用 `TrayLayout.COVER_START_DP`），所以留在 `PlayerCard` 里算一次再传下去更便宜，
也避免「两处各算一遍、其中一处忘了跟 `TrayLayout` 走」。

### 3.3 回调（全部是「谁构造、谁调用」）

`PlayerCard` 的 16 个 lambda 形参是**外部注入**的，直接透传即可。
真正需要在拆分时小心的是**内部构造的 lambda**：

| 内部 lambda | 位置 | 行为 | 拆分处置 |
|---|---|---|---|
| `playerControls: @Composable () -> Unit` | 848–964 | 节拍脉冲 `Box` + `FullPlayerControls`(40 个实参) + `trailing` 退出大屏键 | 保留为**槽位**，内容搬进顶层 composable |
| `playerPanels: @Composable (Modifier) -> Unit` | 967–1102 | 歌词面板（`Crossfade` + `LyricsView`）+ 队列面板（标题行 + `QueueView`） | 同上 |
| `onToggleLyrics` / `onToggleQueue` / `onAddToLibrary` / `onSeek` / `onQualitySelect` / `preferredQualityIndexProvider` | 876–941 | 写 `showLyrics/showQueue`、Toast、`libraryTick++`、`seekTo` | 原样搬进 `PlayerCardControls`（调用时机不变） |
| `trailing`（大屏退出键） | 942–961 | `clickable { onToggleBigScreen() }` | 随 `playerControls` 一起搬 |

> **回调顺序是行为等价的一部分**：`onToggleLyrics` 里 `showLyrics = !showLyrics` →
> `showQueue = false` → 条件 `retryLyrics()` 的顺序、以及 `onAddToLibrary` 里
> 「先查 `isSongSaved` → 再 `removeSong`/`saveSong` → 再 `libraryTick++`」的顺序
> 必须逐行保持。这些 lambda 整体搬移，不重排。

### 3.4 Modifier 链（三处必须逐字保持）

| 链 | 位置 | 契约 |
|---|---|---|
| 根 `Box` 的 `padding(top=hitGateInsetDp)` + 两个 `pointerInput` + `offset(y=-hitGateInsetDp)` | 601–729 | 修饰符**顺序即语义**：outer modifier（吞事件）必须在 inner（拖拽）之后执行；`offset` 必须最后。任何重排都会让「死带」或「拖不动」回归（AGENTS.md 触摸陷阱 §2/§5/§6） |
| 封面 overlay 的 `graphicsLayer` | 1810–1860 | 变换在**外**、`clip`/`border` 在**内**（`StableCover` 的 KDoc 已固化）。拆分只能整块搬，不能拆成两层 Modifier |
| 窄屏控制栏的 `collapsibleHeight` + `graphicsLayer` | 1445–1476 | `layout` 阶段读 `Animatable`（零重组），顺序不能动 |

---

## 4. 纯 UI 块 vs 带副作用块

| 类别 | 块 |
|---|---|
| **纯 UI**（无状态、无副作用，只读参数） | 三个布局分支里的树构建本身、托盘的文本列、`StableCover` 的绘制层、把手、收起按钮 |
| **带副作用** | 块 3（`MotionClock.clear`）、块 4（`snapshotFlow` 复位）、块 5（手势）、块 6/7（`LaunchedEffect` + `Animatable`）、块 9（手势 + 吞事件）、块 10（`MotionFrameClock` 每帧循环） |

结论：**副作用全部集中在 155–836 这一段**，838 以后基本是「读状态、画树」。
所以拆分的安全切法就是「**在 838 之后切**」，副作用块一行不动。

---

## 5. 可独立重组的块（现状盘点）

`PlayerCard` 里已经做了的重组隔离（这些**不能动**）：

| 手段 | 落点 | 作用 |
|---|---|---|
| `derivedStateOf` 阈值离散化 | `miniBarEnabled` / `dismissEnabled` / `expandedMounted` / `collapsedHitGate` / `lyricsEnabled` / `cardExpandedForInput` / `controlsCollapsedForInput` / `isPanelInteractive` | 只在跨阈值那一帧重组，动画帧零成本 |
| `graphicsLayer { progress.value }` | 卡片平移、面板横滑、控制栏收起、封面变换、封面浮动 | 动画帧零重组（读 Animatable 只失效绘制层） |
| 叶子订阅 `StateFlow` | `TrayLyricLine`（歌词+位置）、`FullPlayerControls` 的 position/duration/quality 叶子、`AudioVisualizerSlot` | 2Hz 位置更新不冒泡到 `PlayerCard` |
| `Crossfade(targetState = song?.id)` | 歌词面板 | 换歌只换内容 |

**还没做到、拆分后自然获得的隔离**：
`queueHeaderHeightPx`（队列标题行的实测高度）目前写在 `playerPanels` lambda 里，
它的写入会让该 lambda 的作用域失效；拆成独立 composable 后，
失效范围收窄到「队列面板」这一棵子树。

---

## 6. 9 处 `RowScope.weight()`（+4 处 `ColumnScope.weight()`）

崩溃报告 §4 给的 dex 地址表与本轮源码逐行核对**完全一致**。`RowScopeInstance.weight`
是 `IllegalArgumentException: invalid weight 0.0` 的抛出者，所以这 9 处是**唯一**能抛该异常的点：

| # | 源码行 | 写在哪个作用域 | 源码实参 | 编译后实参 | 语义 |
|---|---|---|---|---|---|
| 1 | 1132 | 大屏 `Row` | `PlayerLayout.BIG_SCREEN_LEFT_FRACTION` | `const 0.44f` | 大屏左栏占 44% |
| 2 | 1170 | 大屏左栏信息行 `Row` | `1f` | `const 1.0f` | 歌名/作者吃掉信息行剩余宽 |
| 3 | 1227 | 大屏 `Row` | `1f - BIG_SCREEN_LEFT_FRACTION` | `const 0.56f` | 大屏右栏占 56% |
| 4 | **1251** | 宽屏 `Row` | **`wideLeftFraction`（变量）** | 寄存器 `v116` | 宽屏左栏随 `wideSplit` 动画 100%→44% |
| 5 | **1326** | 宽屏 `Row` | `(1f - wideLeftFraction).coerceAtLeast(0.0001f)` | `max(1.0f - v116, 0.0001f)` | 宽屏右栏（**已有下限保护**） |
| 6 | **1583** | 托盘 `Row` | `1f` | `const 1.0f` | **堆栈指向的这一行**（托盘文本列） |
| 7 | 1667 | 托盘作者行 `Row` | `1f` | `const 1.0f` | 作者名吃掉剩余宽（音源角标贴右） |
| 8 | 1685 | 托盘作者行 `Row` | `Spacer(Modifier.weight(1f))` | `const 1.0f` | 无艺人信息时占位，保持右对齐契约 |
| 9 | 1764 | 托盘 `Row` | `1f` | `const 1.0f` | 无歌时「暂无播放」占位 |

**只有 #4 是动态值**，而它的取值范围按字节码是 `[0.44, 1]`（`screenWidthDp >= 600` 时），
`screenWidthDp < 600` 时恒为 `1f` —— 也就是**本机（360dp）走窄屏，5 处 weight 全是字面量 1f**。
这正是崩溃报告 §4 的「矛盾点」：**按字节码不可能得到 0.0**。

> **对拆分的约束（铁律 26 的落点）**：#4 与 #5 是一对**对称位置** —— 右栏有
> `coerceAtLeast`、左栏没有。拆分时若把宽屏分支整块搬走，这一对不对称会被搬进新文件；
> 本版在拆分的**同一次提交**里补齐（见 `probe-split-plan.md` §6 与 A 项实现）。

另外 4 处 `ColumnScope.weight()`（1141 / 1233 / 1260 / 1382）与 2 处落在 lambda 内的
`RowScope.weight()`（1054 队列标题、2148 `ArtistLineWithSource` 的歌手名）**不在**
`PlayerCard` 方法体内，不参与本次异常，但拆分时同样要保证「作用域跟着代码走」。

---

## 7. 与 `TrayLayout` 三个常量的关系

`TrayLayout` 是托盘几何的**唯一落点**（v2.5.5 · C）。`PlayerCard` 对它的依赖共 7 处：

| `TrayLayout` 成员 | `PlayerCard` 用法 | 行 |
|---|---|---|
| `HEIGHT_DP` = 80 | 托盘 `Box.height(...)` | 1542 |
| `COVER_START_DP` = 16 | 托盘封面占位 `Spacer(width)` | 1579 |
| `COVER_SIZE_DP` = 56 | 托盘封面占位 `Spacer(size)` | 1580 |
| `LINE_GAP_DP` = 2 | 三层文本行间距 ×2 | 1646 / 1663 |
| `TOP_BAR_HEIGHT_DP` = 56 | 窄屏顶栏高度、收起键叠加层高度 | 1356 / 1884 |
| `topBarTextStartDp()` = 16+56+12 = 84 | 窄屏顶栏 `padding(start)` | 1358 |
| `controlsPlaceholderWidthDp()` = 144 | 展开态控制区占位宽 | 1743 |
| `coverCenterXDp()` / `coverCenterOffsetDp()` / `topBarCoverCenterOffsetDp()` | 封面 overlay 的三个落点中心 | 318 / 324 / 326 |

**拆分的硬约束**：这三个派生量与「唯一封面 overlay」必须**读同一份数据**。
托盘本体搬进 `PlayerCardTray.kt` 后，overlay 仍在 `PlayerCard`（因为它要跨托盘/全屏连续动画），
两侧都只能通过 `TrayLayout` 通信 —— 不允许在新文件里出现任何字面量 16/56/80/2。
（`TrayLayoutTest` 已断言这些关系；本轮新增的分支几何断言见探针 2 §7。）

---

## 8. 与 Motion 系统的交互点

| 交互点 | 位置 | 读/写 | 说明 |
|---|---|---|---|
| `MotionPrefs.effects.value` | 288 | 读 | **组合期读一次**，帧路径只读捕获值 |
| `MotionClock.clear()` | 336 | 写 | 换歌时清包络/特效池（`LaunchedEffect(song?.id)`） |
| `MotionBackdrop(...)` | 763–777 | 挂载 | `if (motion.anyUiMotion)`；关掉 = **整层不挂载** |
| `MotionFrameClock(...)` | 787–791 | 挂载 | **恰好一次**（竖屏波形不挂载时也必须有它） |
| `motion.fullScreenWaveform` | 804 | 挂载 | 背景级波形层 |
| `motion.beatPulse` + `MotionClock.pulse()` | 854–860 | 帧路径读 | 控制条脉冲（在 `playerControls` lambda 内） |
| `motion.lyricPulse` | 1017 | 传参 | 交给 `LyricsView` |
| `motion.coverElevation/coverTransition/coverFloat/cover3d` + `MotionClock.generation/pulse()` | 1794–1857 | 帧路径读 | 封面 overlay 的四个能力位 |

**拆分约束**：
1. `MotionFrameClock` 在 `PlayerCard` 里**恰好挂载一次**（块 10），拆分后必须仍然只在
   `PlayerCard` 里出现一次 —— 搬到任何一个「可能不挂载」的子 composable 都会让帧时钟停摆
   （v3.2.0 的 `waveformMounted` 就是这么加上的）。
2. 帧路径的读（`MotionClock.generation`）必须留在**同一个 `graphicsLayer` 块内**，
   跨 composable 传递 `pulse()` 的**值**会把每帧状态读提前到组合期 —— 直接违反
   「GPU 零重组」。
3. 三个 `if (motion.xxx)` 的挂载判据（不是 `alpha=0`）保持不变。

---

## 9. 探针结论（拆分维度）

1. **按布局分支 + 托盘 + 叠加层拆**（结构维度），不按状态域、不按屏幕形态另起一套。
2. **副作用块（155–836）整块留在 `PlayerCard`**，只搬 838 之后的「读状态画树」。
3. `RowScope`/`ColumnScope` 的 `weight` 必须留在**它原本所在的那个 `Row`/`Column` 内部** ——
   也就是分支整块搬，不能把 `weight` 提到调用点。
4. `MotionFrameClock` 唯一挂载点、`graphicsLayer` 帧路径读、三个挂载判据：原样保留。
5. 允许新增的只有「传参」和「顶层 composable 定义」，不允许新增 `remember`/`LaunchedEffect`
   到子组件里（`queueHeaderHeightPx` 是唯一例外 —— 它本来就长在 lambda 里，随代码搬走即可）。
