# v3.2.1 探针 2 · 拆分方案

> 依据：[probe-playercard-structure.md](probe-playercard-structure.md)（结构探针）
> 目标：`PlayerCard` 不再存在 > 2000 code unit 的方法；行为**逐字节等价**（铁律 25）。

---

## 1. 拆什么：三个候选维度的取舍

| 候选维度 | 主张 | 判定 | 理由 |
|---|---|---|---|
| **按 UI 区块**（封面区/控制区/歌词区/托盘区） | 拆成 `Cover`/`Controls`/`Lyrics`/`Tray` | ⚠️ 部分采纳 | 托盘、控制条、面板确实是独立区块；但「封面区」在三个分支里落点不同，单独拆会把 `weight` 与 `onGloballyPositioned` 拆散 |
| **按状态域**（播放状态/队列状态/动效状态） | 按 state 归属拆 | ❌ **否决** | 状态是**跨区块共享**的（`wideSplit` 同时喂 `wideLeftFraction`、`panelBoundaryPx`、封面落点）；按状态域拆必然把 `remember` 搬进子组件 ⇒ 生存期跟着挂载条件走 ⇒ 行为不等价（铁律 25 直接违反） |
| **按屏幕形态**（竖屏/横屏/大屏） | 一个形态一个文件 | ✅ **采纳（主维度）** | 三个分支在源码里本来就是 `when` 的互斥分支、**运行期只有一棵被组合**；整块搬移 ⟹ 组合结构逐节点等价 |
| **按屏幕形态 + 叠加层**（形态 + 托盘 + 封面 + 收起键） | 上面再加三个「与分支正交」的层 | ✅ **采纳（次维度）** | 这三层在三个分支之外、且互相独立（托盘常挂载、封面跨态连续、收起键大屏不挂载） |

**最终维度 = 「布局分支（3）+ 叠加层（3）+ 两个槽位（2）」**。

---

## 2. 目标结构（新文件与函数清单）

| 新文件 | composable | 来源（源码行） | 预计 code unit | 作用域要求 |
|---|---|---|---|---|
| `PlayerCardControls.kt` | `PlayerCardControls(...)` | `playerControls` lambda 体 848–964 | ~900 | 无（内部自建 `Box`） |
| `PlayerCardPanels.kt` | `PlayerCardPanels(...)` | `playerPanels` lambda 体 967–1102 | ~1300 | 无（内部自建 `Box`/`Column`/`Row`） |
| `PlayerCardBigScreen.kt` | `PlayerCardBigScreenLayout(...)` | 1105–1244 | ~1200 | 内部自建 `Row` |
| 同上 | `BigScreenLeftColumn(...)` | 1130–1224 | ~700 | `ColumnScope`（由调用点提供） |
| 同上 | `BigScreenInfoRow(...)` | 1159–1214 | ~400 | `RowScope` |
| `PlayerCardWide.kt` | `PlayerCardWideLayout(...)` | 1245–1336 | ~800 | 内部自建 `Row` |
| `PlayerCardNarrow.kt` | `PlayerCardNarrowLayout(...)` | 1337–1527 | ~1500 | 内部自建 `Box`/`Column` |
| 同上 | `NarrowTopBar(...)` | 1339–1379 | ~450 | 内部自建 `Box` |
| 同上 | `NarrowPanelArea(...)` | 1380–1443 | ~600 | 内部自建 `Box` |
| 同上 | `NarrowCoverInfo(...)` | 1416–1443 | ~250 | — |
| 同上 | `NarrowControlsBar(...)` | 1445–1476 | ~250 | — |
| 同上 | `NarrowControlsHandle(...)` | 1477–1527 | ~450 | — |
| `PlayerCardTray.kt` | `PlayerCardTray(...)` | 1532–1784 | ~1400 | 内部自建 `Box`+`Row` |
| 同上 | `TrayInfoColumn(...)` | 1568–1710 | ~800 | `RowScope` |
| 同上 | `TrayControls(...)` | 1712–1753 | ~350 | `RowScope` |
| 同上 | `TrayNoSong(...)` | 1755–1784 | ~250 | `RowScope` |
| `PlayerCardCover.kt` | `PlayerCardCoverOverlay(...)` | 1786–1876 | ~800 | — |
| 同上 | `PlayerCardDismissButton(...)` | 1878–1898 | ~180 | — |
| 同上 | `StableCover(...)`（已在文件内，迁到此处） | 2005–2090 | ~900 | — |
| 同上 | `ArtistLineWithSource(...)` | 2120–2160 | ~250 | `RowScope` 内部自建 |
| `PlayerCard.kt`（保留） | `PlayerCard(...)` | 104–1899 减去搬走的部分 | **目标 < 1900** | — |

> 「预计 code unit」= 行数 × 8.7（本轮实测：`PlayerCard` 方法体 1041 行 → 9092 code unit）。
> 它只是**排产依据**，最终以 release dex 实测为准（见探针 4 的工具与 §7 的验收）。

### 2.1 为什么窄屏分支与托盘还要再切一刀

`NarrowLayout ≈ 1500` 与 `Tray ≈ 1400` 都**贴阈值**，而铁律 24 的阈值是 2000。
「贴阈值」在下一个版本就会变成「超阈值」——v3.2.0 的 `MainActivityKt.MainScreen`
（7317）就是这么长出来的。所以本版按「**单块 ≤ 1500**」排产，代价只是多几个文件。

---

## 3. 状态提升方案

| 状态 | 现状 | 拆分后 | 理由 |
|---|---|---|---|
| `showLyrics` / `showQueue` | `PlayerCard` 无条件 `remember` | **保持** | 被 3 个分支 + 2 个 lambda 同时读写 |
| `lyricAnimProgress` / `queueSlideProgress` / `controlsCollapse` | 同上 | **保持** | 跨分支连续的动画轴（封面要跨托盘↔全屏连续动） |
| `wideSplit` | `animateFloatAsState` | **保持** | 喂 `wideLeftFraction` / `panelBoundaryPx` |
| `coverArtKey` / `coverArtBitmap` / `cardRootOrigin` / `wideCoverCenter` / `wideCoverSizePx` | 同上 | **保持** | 封面 overlay 的唯一数据源；`StableCover` 上抛 |
| 全部 `derivedStateOf` 门 | 同上 | **保持** | 阈值离散化的唯一落点 |
| `libraryTick` / `isSongSaved` | 同上 | **保持** | 收藏按钮状态 |
| `queueHeaderHeightPx` | **在 `playerPanels` lambda 内** `remember` | 随代码搬进 `PlayerCardPanels` | 生存期不变（都在 `expandedMounted` 为真时才组合） |
| 各 `MutableInteractionSource` | 在 `clickable(...)` 调用点内联 `remember` | 随代码搬 | 同上 |
| `progress` / `playerViewModel` / `strings` / `haptic` / `motion` | 组合期读取 | **传入子组件**（值或引用） | `progress` 必须传 **`Animatable` 引用**（帧路径读）；`playerViewModel` 只在需要订阅的地方用它的 `StateFlow` 引用 |
| `StateFlow` 订阅位置 | `TrayLyricLine` / `FullPlayerControls` 叶子内部 | **保持叶子订阅** | 在 `PlayerCard` 或分支 composable 里 `collectAsState` 会让整棵子树随 2Hz 位置重组（v1.8.0 起的硬约束） |

**不做任何「向下提升」**：没有新增 `remember`、没有新增 `LaunchedEffect`、
没有把任何状态从 `PlayerCard` 挪到子组件（唯一的搬移是 `queueHeaderHeightPx`，
它的生存期在搬移前后完全相同）。

---

## 4. Modifier 链的传递方式

| 链 | 传递方式 |
|---|---|
| 根 `Box` 的三段 Modifier（hitGate `padding` / 两个 `pointerInput` / `offset`） | **留在 `PlayerCard`**，一行不动（它属于卡片本体，不属于任何分支） |
| 面板槽位的 `Modifier`（`playerPanels(Modifier)`） | 保持「**调用点构造、被调用点消费**」的现有形状：`PlayerCardPanels(panelModifier, ...)`，`panelModifier` 由分支构造（带各自的 `graphicsLayer` alpha 表达式） |
| 控制条槽位 | `playerControls` 无 Modifier 参数（现状如此）；窄屏那层 `collapsibleHeight + graphicsLayer + pointerInput` 属于**窄屏分支**，搬进 `NarrowControlsBar` |
| 封面 overlay 的 `graphicsLayer` | 整块搬进 `PlayerCardCoverOverlay`，**内部顺序不变**（变换在外、`clip`/`border` 在内） |
| 手势 Modifier 工厂 | `controlsCollapseDrag()` 从 `PlayerCard` 的**局部函数**提升为**同文件顶层 internal 函数**：`controlsCollapseDragModifier(hasSong, isWidePlayer, controlsCollapse, controlsHeightPx::get, density, coroutineScope)`。仍然「每次调用新建实例」（`SuspendPointerInputElement` 的可变状态不能共享） |

---

## 5. 重组隔离方案

1. **子组件不接收「每帧变化的值」**：`progress` 传 `Animatable` 引用，帧路径读留在
   `graphicsLayer { }` 内；`MotionClock.generation/pulse()` 的读也必须在子组件的
   `graphicsLayer` 块里，**不允许**在子组件组合期求值后传下去。
2. **子组件不写父组件的状态**（唯一例外是既有的两处 `onGloballyPositioned`：
   `wideCoverCenter`/`wideCoverSizePx` 与 `controlsHeightPx`/`controlsTopInCardPx`，
   它们今天就这么写，保持原样；由调用点构造 setter lambda 传下去）。
3. **`derivedStateOf` 的门留在父组件**：子组件内新增的任何 `if` 都必须是**参数判定**
   （`hasSong`、`miniBarEnabled` 等），不得新建派生状态。
4. **`queueHeaderHeightPx`** 搬进 `PlayerCardPanels` 后，其写入只让「队列面板」子树失效
   （今天是让整个 `playerPanels` lambda 作用域失效）—— 这是拆分带来的**唯一**重组粒度变化，
   方向是「更小」，不改变任何可观测输出。

---

## 6. 与 A 项（对称保护）的关系

`wideLeftFraction` 的两处对称消费点在**宽屏分支**里：

```kotlin
// 左栏（1251）：无保护 —— 本轮补 .coerceAtLeast(0.0001f)
.weight(wideLeftFraction)
// 右栏（1326）：已有保护
.weight((1f - wideLeftFraction).coerceAtLeast(0.0001f))
```

拆分时这两行会一起搬进 `PlayerCardWideLayout`。**在同一个提交里补齐左栏保护**
（铁律 26），并且保护加在**两处**：
- `PlayerLayout.wideLeftFraction(...)` 的返回值加 `coerceAtLeast(0.0001f)`（单一事实来源）；
- 调用点 `.weight(wideLeftFraction.coerceAtLeast(0.0001f))`（防将来新增调用点绕过）。

---

## 7. 行为等价的验证方案（三层）

| 层 | 手段 | 覆盖 |
|---|---|---|
| **L1 纯逻辑单测**（JVM） | `PlayerLayoutTest` 扩展：`wideLeftFraction` 的边界值（`wideSplit` = 0/0.5/1/负数/超 1/NaN）；`splitBoundaryPx`；`TrayLayoutTest` 的分支几何 | 参数域全覆盖 |
| **L2 结构回归测试**（androidTest） | 新增 `PlayerCardSplitRegressionTest`：在设备上组合 `PlayerCard`，断言 (a) 组合不抛异常（**直接覆盖 `invalid weight 0.0` 这一类**）、(b) 托盘的三个核心按钮存在且顺序为「上一首/播放暂停/下一首」、(c) 展开后歌词/队列/加入库/大屏四个入口存在、(d) 关闭歌词后底部控制栏仍可命中（AGENTS.md 触摸陷阱 §3 的回归面） | 组合期 + 语义节点 |
| **L3 真机 A/B 快照**（S6） | 6 个形态（折叠/歌词/队列/大屏/宽屏两栏/宽屏单栏）各采 `uiautomator` 层级 + 截图，**拆分前后逐节点比对**（`bounds` + `content-desc` + `clickable`） | 三个布局分支的真实几何 |

L3 的采集脚本与产物：`.scratch/v321/capture_states.py`（`before`/`after` 两轮）+
`compare_states.py`；结论落 `docs/verification/v3.2.1/EQUIVALENCE.md`。

> 「逐字节等价」在本项目里的可执行定义：**同一设备、同一数据、同一形态下，
> 卡片相关语义节点的集合与每个节点的 `bounds` 完全相同**（文本内容中随时间变化的部分
> —— 歌词行、播放位置 —— 单独剔除，见 `compare_states.py` 的过滤规则）。

---

## 8. 风险与对策

| 风险 | 对策 |
|---|---|
| **R8 把新子 composable 内联回 `PlayerCard`**，9092 只是换个名字 | 每次构建后用 `tools/method-size/method_size.py` 实测 release dex；若出现内联，用 `-neverinline` 规则钉住（`app/proguard-rules.pro`） |
| 搬移过程中改变 `RowScope`/`ColumnScope` 归属（`weight` 编译不过或语义变化） | 分支**整块搬**；子切分只切在「内部自建 `Row`/`Column`」的位置，`weight` 永远留在它自己的 `Row`/`Column` 内 |
| `remember` 生存期被改 | 见 §3：只搬一个 `queueHeaderHeightPx`，且挂载条件相同 |
| `MotionFrameClock` 被搬进可能不挂载的分支 | 它留在 `PlayerCard` 块 10（`if (hasSong && expandedMounted)` 之外），唯一挂载点；拆分后加结构断言（L2 里断言节拍脉冲在展开态仍推进 —— 至少断言组合不抛异常） |
| 首帧展开变慢（多一层 composable 调用） | 拆分**不增加** composable 深度：搬移后调用点深度不变（原来这些代码就在同一个 `Column` 里，现在只是多一层函数调用，Compose 的 group 数不变） |
| `stable`/`immutable` 参数导致不必要的重组 | 子组件参数一律用既有类型（`SongItem?`、`Boolean`、`Float`、lambda）；lambda 由调用点构造，Compose 自动记忆化 |

---

## 9. 验收（本方案的完成定义）

1. `PlayerCard` 方法 ≤ 2000 code unit（release dex 实测），所有新子 composable 同样 ≤ 2000。
2. L1/L2/L3 三层证据齐全且全部通过。
3. 三个布局分支 + 托盘 + 封面 overlay 的**语义节点与 bounds**在拆分前后完全一致。
4. 真机 10 次冷启动无 `invalid weight` 崩溃。
5. `./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease` 全绿。
