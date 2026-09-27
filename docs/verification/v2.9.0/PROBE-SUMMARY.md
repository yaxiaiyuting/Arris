# v2.9.0 探针阶段 · 决策级汇总（PROBE-SUMMARY）

> 汇总对象（只读探针；本文件不改它们、也不改任何源码）：
> [probe-ui-motion.md](./probe-ui-motion.md)（竖屏布局树 / 封面位图通路 / RMS 数据源）·
> [probe-landscape-motion.md](./probe-landscape-motion.md)（横屏几何 / 背景层插入点 / 背景级波形）·
> [probe-tier-migration.md](./probe-tier-migration.md)（档位迁移）·
> [probe-blur.md](./probe-blur.md)（背景模糊）·
> [probe-perf-budget.md](./probe-perf-budget.md)（性能预算与降级）。
>
> **性质**：本轮**没有**改源码、**没有**编译、**没有**跑单测、**没有**驱动真机。所有结论 = `file:line` 引用 + 原文。
> **证据分级**：**【实测】** = 任务书给定的真机数字（只照抄，不新增）；**【事实】** = 源码引用；**【估算】** = 代码算式或 KDoc 自述量级；**【未验证】** = 明确没确认。
> **实现状态**：v2.9.0 的实现**已经写好**（新目录 `ui/player/motion/`，6 个文件），本文档回答的是「这套实现做了什么、依据是什么、哪些还没验证」。

---

## 1. 档位迁移方案（旧 key → 新档位）

**一句话**：v2.8.0 的 8 个波形键全部保留、全部退出渲染；只把其中 2 个**搬成有效值**，另 5 个标成迁移源，水位独立。

| 旧键（v2.8.0） | 新键（v2.9.0） | 规则 |
|---|---|---|
| `visualizer_tier` | `motion_tier` | 搬**有效档**；**缺 key ⇒ 不写新键** |
| `visualizer_auto_downgraded` | `motion_degrade_level` | 映射到 **1**（砍 B 档界面动效），**不是 3** |
| `visualizer_showcase` / `_shockwave` / `_particles` / `_perspective` / `_drag` | —（合并进档位） | 5 个键**不再参与渲染**；registry 标 `internal = true` + `legacyV290 = true` |
| `visualizer_tier_version` | `motion_version` | 水位分开；`VERSION_PRE_V290 = 0` 表示「未迁移」 |

- **关键规则一（有效档）**：v2.8.0 的 C 档要求 `tier==2` **且** `showcase==true`（`VisualizerTier.kt:203`），
  而 `showcase` 默认 `false`（`VisualizerPrefs.kt:75`）⇒「选了炫技但没开炫技效果」的老盘，在 v2.8.0 下**渲染出来就是精致档**。
  用户文案自己承认过这件事：「仅「炫技」档生效；**关闭后炫技档与精致档外观一致**」（`VisualizerStrings.kt:98`）。
  所以 `tier==2 && !showcase` ⇒ 搬到 **1**；照抄 2 会让这些用户**突然多出**冲击波/粒子/3D（语义变化）。
- **关键规则二（降级映射）**：v2.8.0 的降级结果**已经写在 `visualizer_tier` 里**（`VisualizerPrefs.kt:196-199` 两个键一起写），
  再扣一次波形就是重复处罚；但完全不理会又等于把一台实测吃力的设备当新机。所以只预置**第 1 级**（`MotionPrefs.kt:187-188`）。
- **缺 key 不写盘**：`migratedTier` 返回 `null` 的唯一含义是「不要写 `motion_tier`」（`MotionPrefs.kt:167/170/204`），
  保住「用户没选过」——设备判据仍可解析默认档（S6/API 24【事实】⇒ 简洁；PCL110/API 36【推导】⇒ 精致）。
- **机械防线**：`newV290KeysAreExactlyTheMotionSet`（只许新增 4 键）、`v280KeysAreAllLegacyUnderV290`（7 个旧键必须 `internal + legacyV290`）、
  `v290EntriesAreReachableAndHaveStrings`（可见新项必须被渲染计划覆盖且文案齐全）（`SettingsRegistryTest.kt:93/105/117`）；
  迁移单测 24 个在 `MotionPrefsTest.kt`。**【未验证】**：没有任何真机升级实测（S6/PCL110 已 root 可做，WGR-W09 锁屏无 root 做不了）。

---

## 2. 背景模糊：技术方案与「降级」

**方案四步**：`getPixels` → **32×32 盒式平均**降采样 → **三遍可分离盒式模糊**（滑动窗口、Clamp 边界、纯整数）→ 当静态位图 `ContentScale.Crop` 铺满全屏（`FilterQuality.High`）。

| 环节 | 做法与依据 |
|---|---|
| 为什么不对原图直接模糊 | 1000² = **4 MB** 缓冲、3 遍可分离 = **6 次全缓冲遍历**（源码自述在 S6 上「几百毫秒量级」）；`RenderEffect` 走 GPU 却**每帧**作用在**全屏图层**上，而背景**每首歌只变一次**（`CoverBlur.kt:21-40`） |
| 降采样 | `DOWNSAMPLE_PX = 32`；用**盒式平均**而非 `createScaledBitmap` 双线性 —— 1000→32 是 31 倍，双线性只采到 `32×32 = 1024` 个源像素（**0.1%**），背景色会随抽样点跳变（`CoverBlur.kt:48-53`、`:96-132`） |
| 模糊 | 三遍可分离盒式，**单遍 O(n) 与半径无关**；边界 **Clamp**（补零会在四边压出暗边，上采样后正好落在屏幕边缘）；半径 `12`、遍数 `3`、半径≥边长被夹取（`CoverBlur.kt:134-208`） |
| 缓存 | 键 = **封面 URL**（不是 bitmap 身份哈希）；`LruCache` **4 条** × `32×32×4 = 4 KB` = **16 KB**；命中**同步零成本**，未命中在 `Dispatchers.Default`（`getPixels` 拷 4 MB，源码自述「十几毫秒量级」）；`hits`/`misses` 可断言（`CoverBlur.kt:239-289`） |
| 失败回退 | `blurred()` 全程 `runCatching`、**永不抛**，任何失败返回 `null` ⇒ `MotionBackdrop` 里先铺主题背景色 ⇒ **纯色背景**（与 v2.8.0 观感一致）（`CoverBlur.kt:233-238`；`MotionBackdrop.kt:98-104`） |
| 可读性 | 模糊图之上压主题背景色，`BACKDROP_SCRIM_ALPHA = 0.62`（任务书 §7.3「歌词可读性优先」；不压会让浅色封面把歌词冲得读不出来）（`MotionBackdrop.kt:140-146`、`:203`） |

**「降级」这件事不存在**：实现不依赖 `RenderEffect` / `RenderScript` / `Modifier.blur`（全仓 grep：这三个名字**只出现在 `CoverBlur.kt` 的 KDoc 注释里**，零调用），
纯 Kotlin 整数运算 ⇒ **API 24 与 API 36 跑的是同一份代码、同一条路径**。
所以对任务书 §2.3「低版本（API 24~30）降级方案」的答复是**加强**而不是满足：**不是降级，而是全版本同一实现**（`CoverBlur.kt:39-40`）。
**【未验证】**：没有真机耗时、没有截图、`CoverBlurCache` 的 LRU/命中计数无单测（13 个 `CoverBlurTest` 只覆盖纯函数）。

---

## 3. 性能预算与降级策略

**先纠正基线**：任务书说的「S6 P50 20 / P99 46」与「PCL110 P50 7 / P99 14」都是 **v2.8.0 的结果**（前者还是 T0 简洁档），
**不是基线**；真正的 v2.6.2 基线是 S6 **P50 15 / P99 31**（`frame-baseline-v262.txt`）。所以本版的目标只能是「不比 v2.8.0 显著更贵 + 超预算能降级」。

| 档 | 每帧增量（**【估算】**，全部来自代码算式；本版无任何帧时间实测） |
|---|---|
| **A** | 背景模糊**不在帧路径**（每首歌一次）；帧路径只有**背景呼吸**（1 次 `generation` 状态读 + `alpha`/`scaleX`/`scaleY` 三次写，**0 笔绘制增量、0 重组**）+ 切歌后 400 ms 的封面淡入（1 次图层 alpha 写）；封面阴影是**静态** `Modifier.shadow` ⇒ A 档每帧 **0 笔绘制** |
| **B** | 背景级波形 = **多一次 `AudioVisualizerBars` 的 Canvas**（≤28 柱 + ≤28 峰值 + ≤28 光点），与左栏那条**共用同一个 `WaveformStore` 与同一条帧时钟**；歌词律动只让**当前行那一层**失效；节拍脉冲 1 次控制条缩放图层更新；视差 1 次 `translationY` 写 |
| **C** | 粒子 **≤32 个 `drawCircle`** + 光晕 **≤2 个描边圆**（定长池、零分配、每帧固定 34 次槽位检查）；封面 3D **多一层 render layer** |

**对比依据**：v2.8.0 的波形每帧 **≤28 `drawRoundRect` + ≤28 峰值 + ≤28 光点 = ≤84 笔绘制 + ≤56 次 sqrt**（`AudioVisualizer.kt:504-563`），
而 **A 档界面动效一笔绘制都不新增** ⇒ 「A 档每帧增量显著小于波形」成立。

**三级降级阶梯**（`MotionDegrade`，**显式表**而不是散落的 `if`）：`0 不做 → 1 砍 B 档界面动效 → 2 再砍 A 档（背景回纯色）→ 3 波形档位降一级`，**到顶即止**；
**用户手动改档位把水位重置为 0**（`MotionPrefs.kt:137-142`），总开关**不**重置（生命周期不同）。
误判的代价被限制在「**少看一层特效**」（判据不做归因 —— 归因要 Perfetto）。

**帧监控：复用，不新建**。继续用 `VisualizerFrameMonitor`（`Window.addOnFrameMetricsAvailableListener`，API 24+），
`FrameBudgetPolicy` 的判据（**60 帧窗口 / 16.67 ms 预算 / 40% 超标**）**一字未改**；
但**注册点从波形组件搬到了 `MotionFrameClock`** —— 因为竖屏手机上波形**根本不挂载**（`PlayerLayout.visualizerSlot` 六格表里「手机竖屏」恒 false），
监控只挂在波形里的话「优先砍界面动效」永远不会触发。
**【事实】两处措辞校正**：① 「每**进程**最多推进一级」实际是「每**次注册**最多一级」（收起再展开就会重新注册）；
② `MotionClock.clear()`（换歌清空包络/特效池）**没有任何调用点**，它引用的 `WaveformStore.clear()` **也不存在**。

---

## 4. 横屏全屏动效布局方案

**① 背景模糊铺满全屏**：插入点在 `PlayerCard` 根 Box 内的**两张纯色底板之上、内容 `Column` 之下**（`PlayerCard.kt:741-763`，
位于 `:721-728` 的 `background` 底板与 `:731-739` 的折叠态卡背之后、`:801` 的 `Column` 之前）。
这个位置同时满足四条：在根 Box 内（跟着卡片一起平移）、在内容之前（z 序更低）、在纯色层之后（不会被盖住）、
并且抄了 `translationY = statusBarPx * (1f - progress.value)`（否则折叠态会在 miniBar 上方多出一条背景）。
整层只挂 `graphicsLayer`/绘制，**不挂任何 `pointerInput`** ⇒ **触摸零新增命中面**（AGENTS.md 触摸陷阱第 1 条）。

**② 背景级波形**：**额外**一层 `AudioVisualizerBars` 挂在卡片**底部整宽**（`Modifier.align(BottomCenter).fillMaxWidth()`），
高度 `min(140.dp, 屏幕高 × 0.24)`，`alpha = WAVE_BACKDROP_ALPHA = 0.32`（`PlayerCard.kt:774-799`、`:1859`）。
**既有的左栏波形与所有分栏结构一行未动** —— 这正是不去改宿主挂载点的原因（任务书 §7.3「不改变现有横屏布局结构」由结构保证）。
它复用同一套画法（圆角柱/峰值/渐变/光点/涟漪/粒子都已在那里），读**同一个 `WaveformStore`**、由**同一条帧时钟**推进：
「两处各起一条循环会让动画相位每帧前进两次、流动速度翻倍，而且只在横竖屏都开波形时出现」是 `MotionClock` KDoc 里写死的禁令。

**③ 与歌词/控制区的 z 序与「不抢主视觉」**：背景层与背景级波形都在**内容 `Column` 之前**声明 ⇒ 封面、歌词、控制条、左栏波形**全部浮在它们之上**；
背景级波形再压到 32% 不透明度、模糊图之上盖 62% 主题色遮罩（§2），C 档粒子/光晕也画在 `MotionBackdrop` **这一层内部**（z 序仍低于内容）。
依据是任务书 §7.3「**歌词可读性优先**」与 Kanesumi「信息优先」正典：背景层的职责是「有东西在流动」，不是抢前景。

**【未验证】**：遮挡关系与观感**没有截图证据**（本轮无设备驱动；S6/PCL110 连着但探针阶段不驱动它们，WGR-W09 锁屏且无 root）。

---

## 5. 与任务书前提的偏差（4 条）

1. **「基线 S6 P50 20 / P99 46、PCL110 P50 7 / P99 14」写错了对象** —— 这四个数字都是 **v2.8.0 的结果**（S6 那个还是 T0 简洁档），
   不是基线。真正的 v2.6.2 基线是 S6 **P50 15 / P90 25 / P95 27 / P99 31**（944 帧 / janky 38.77%）；
   v2.8.0 T0 是 963 / 20 / 27 / 31 / 46（janky 91.80%）。同样，PCL110 的 7 / 9 / 11 / 14（1821 帧 / janky 0.27% / 60.7 fps）是 v2.8.0 T1。
   ⇒ 目标要改成「相对 v2.8.0 不显著更贵 + 能自动降级」，而不是「回到 P50 15」（那要回退波形实现，不在本版范围）。
2. **任务书 §2.3 说的「低版本（API 24~30）降级方案」不需要做**：实测实现是**纯 Kotlin 整数运算**，
   不依赖 `RenderEffect` / `RenderScript` / `Modifier.blur`（全仓零调用，只在 KDoc 里被解释为什么不用的），
   所以 **API 24 与 API 36 是同一条路径、同一份观感**。这是对任务书的**加强**，不是降级；
   release note 里要写成「全版本同一实现」，否则会被读成「低版本少了模糊」。
3. **任务书 §7.2「波形从左侧扩展到全屏底部横跨」没有按字面做**：真去改宿主（把左栏那条挪到根 Box 底部整宽）
   会动到既有分栏结构、封面尺寸（`weight(1f)` 会长高）与控制条位置，违反同一条任务书的 §7.3。
   实际选了「**额外一层背景带**」（卡片底部整宽、`alpha=0.32`、画在所有前景之下）：左栏那条与全部布局一行未动，
   「不改变现有横屏布局结构」由**结构**保证而不是靠小心。代价是每帧多一次全宽 Canvas（B 档，低端机默认简洁档 ⇒ 不挂载）。
4. **任务书 §6.4 的「真 FFT / 多频段」没有做，本案未翻案**：v2.8.0 的结论（数据链路只有「整块 PCM → 单标量 RMS」）
   在 v2.9.0 **未被推翻**，`VisualizerEffects.spectrumColoring` 仍是恒 false 的能力位；
   界面动效侧同样只消费 RMS 包络与 onset，没有频域数据。任务书 §6.4 自己也写了「除非探针确认数据源」。

（另附两处**实现侧**的诚实记录，放在对应探针里：`MotionClock.clear()` 未接线、「每进程最多一级」的措辞比代码强，
见 `probe-perf-budget.md` §5.4/§5.5；`BACKDROP_SCRIM_ALPHA` 上方的 KDoc 是复制粘贴残留（写着「0.22/背景级波形」而常量是 0.62），
见 `probe-blur.md` §7。）
