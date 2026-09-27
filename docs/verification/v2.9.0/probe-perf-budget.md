# v2.9.0 探针 · 性能预算与自动降级（A/B/C 三档的每帧增量、三级阶梯、帧监控归属）

> **性质**：只读探针（**实现已由另一个人写好**，本文只做侦察与引用）。本轮**没有**修改任何源文件、
> **没有**编译、**没有**跑单测、**没有**驱动任何真机。
> **证据分级**：**【实测】** = 任务书给定的真机实测数字（原文照抄，见 §1，**本文不重新推导、不新增数字**）；
> **【事实】** = `file:line` / 原文引用；**【估算】** = 由代码算式或源码 KDoc 推出的量级（**本轮未量化**）；
> **【未验证】** = 明确没确认。
> **配套**：`probe-tier-migration.md`（档位与能力位）、`probe-blur.md`（模糊的像素代价）、
> `probe-ui-motion.md` §5（RMS 数据源与「帧时钟归谁」的三条结构约束）、`probe-landscape-motion.md` §3（背景级波形的两个选项）。

---

## 0. 结论速览

| # | 问题 | 答案 | 落点 |
|---|---|---|---|
| 1 | 任务书说的「基线 S6 P50 20 / P99 46」是什么？ | **不是基线**。那是 **v2.8.0 的 S6 T0（简洁档）结果**；真正的 v2.6.2 基线是 P50 15 / P99 31。任务书的措辞要纠正 | §1 |
| 2 | A 档每帧增量多少？ | **背景模糊不在帧路径上**（每首歌一次）；帧路径只有**背景呼吸**（一次图层属性更新 + 1 次 `generation` 状态读）与切歌后 400 ms 的封面淡入；封面阴影是**静态**修饰符 | §2 |
| 3 | A 档比波形贵吗？ | **显著便宜**。v2.8.0 的波形每帧 ≤28 `drawRoundRect` + ≤28 峰值 + ≤28 光点（**≤84 笔绘制 + 28 次 sqrt**）；A 档界面动效**一笔绘制都不新增** | §2.5 |
| 4 | B 档每帧增量？ | 背景级波形 = **多一次 `AudioVisualizerBars` 的 Canvas 绘制**（与左栏那条共用 `WaveformStore` 与**同一条**帧时钟）；歌词律动只让**当前行那一层**失效；节拍脉冲多一次缩放图层更新；视差多一次 `translationY` 更新 | §3 |
| 5 | C 档每帧增量？ | 粒子 **≤32 个 `drawCircle`** + 光晕 **≤2 个描边圆**（定长池、零分配）；封面 3D **多一层 render layer** | §4 |
| 6 | 降级阶梯？ | `MotionDegrade` 显式三级表：**1 砍 B 档界面动效 → 2 砍 A 档 → 3 波形档位降一级**，到顶即止；用户手改档位重置为 0 | §5 |
| 7 | 帧监控是复用还是新建？ | **复用 `VisualizerFrameMonitor`**（`FrameMetrics`，API 24+），但**注册点从波形组件搬到 `MotionFrameClock`**；`FrameBudgetPolicy` 的判据（60 帧 / 16.67 ms / 40%）**一字未改** | §6 |
| 8 | 一处**措辞校正**（诚实记录） | 「**每进程**最多推进一级」实际是「**每次注册**最多一级」——`FrameBudgetPolicy` 的「每实例一次」保证的是实例，不是进程 | §5.4 |
| 9 | 一处**未接线**（诚实记录） | `MotionClock.clear()`（换歌清空包络/特效池）**没有任何调用点**，它 KDoc 里写的 `WaveformStore.clear()` **也不存在** | §5.5 |

---

## 1. v2.8.0 基线（【实测】原文照抄）与任务书措辞的纠正

**测量方法（HARD FACT，可复现）**：

```bash
adb -s <serial> shell "dumpsys gfxinfo com.takahashirinta.ncrust reset"
sleep 30
adb -s <serial> shell "dumpsys gfxinfo com.takahashirinta.ncrust"
# grep: Total frames | Janky frames | 50th | 90th | 95th | 99th
```

| 测量 | 设备 | 配置 | Total frames / 30 s | Janky | P50 | P90 | P95 | P99 | 换算 fps |
|---|---|---|---|---|---|---|---|---|---|
| **v2.6.2 真基线** | S6 `0715f763f54c023a`（SM-G9209，API 24 / Android 7.0，1440×2560） | 旧波形 | 944 | 366（**38.77%**） | **15** | 25 | 27 | **31** | 31.5 |
| v2.8.0 | 同上 | **T0 简洁** | 963 | 884（**91.80%**） | **20** | 27 | 31 | **46** | 32.1 |
| v2.8.0 | PCL110 `3B15CD00GB700000`（OnePlus PLC110，API 36 / Android 16，1272×2800，**144 Hz**） | T1 精致 | 1821 | 5（**0.27%**） | **7** | 9 | 11 | **14** | 60.7 |

（fps 一列 = `Total / 30`，**【推导】**；其余全部是 HARD FACT 的原文数字。基线原始文件 = `docs/verification/v2.8.0/verification/frame-baseline-v262.txt`。）

### 1.1 对任务书前提的纠正（这一条必须写在最前面）

| 任务书的措辞 | 事实 |
|---|---|
| 「基线：S6 P50 20 / P99 46」 | **错**。20 / 46 是 **v2.8.0 在 S6 上 T0（简洁档）的结果**，不是基线。真正的 v2.6.2 基线是 **P50 15 / P99 31**。 |
| 「基线：PCL110 P50 7 / P99 14」 | **同样是 v2.8.0 的结果**（T1 精致档），不是基线。 |
| 「v2.8.0 与基线相比」 | 正确的读法：S6 上 v2.8.0 比真基线 **P50 差 5–8 ms、P99 差 11–22 ms**（范围覆盖三档；本节可逐格核对的那一格 T0 是 P50 **+5**（20 vs 15）、P90 **+2**（27 vs 25）、P95 **+4**（31 vs 27）、P99 **+15**（46 vs 31））。 |

**也就是说：任务书把「新版本的结果」当成了「旧版本的基线」，于是「本版要比它更快」这个目标从一开始就写错了对象。**
本版的性能目标因此只能是「**相对 v2.8.0 的 A/B/C 三档不显著更贵，且超预算时能自动降级**」，
而不是「回到 P50 15」（那需要回退波形实现本身，不在本版范围内）。

**基线里另一条必须带上的事实**：**v2.8.0 的自动降级在 S6 上几乎必然触发**
（`EVIDENCE.md:96` 的「自动降级 ✅ 真机触发（`visualizer_auto_downgraded=true`）」、
`:114` 的「自动降级在 S6 上几乎必然触发（P50 本就 ≈20 ms）」）——这正是 v2.9.0 要把它做成
**三级阶梯**而不是「一次降到底」的直接原因（§5）。

**S6 的读数解释（【推导】）**：S6 的帧产出 32.1 fps **与「33 ms 重绘上限」严格对齐**（`1/0.033 ≈ 30.3 fps`）。
这与 v2.8.0 的 `visualizerFrameIntervalMs` 低端档一致：`SDK_INT < 26 || isLowRamDevice` ⇒ **33 ms** 上限
（`AudioVisualizer.kt:229-232`、`:247-253`。S6 两条都命中）。**这不等于"卡死"**：
§7 的底线是「允许掉到 30 fps，但不能卡死」。

---

## 2. A 档新增的每帧预算（逐项）

### 2.1 总表

| 项 | 落点 | 在帧路径上？ | 每帧增量 | 依据 |
|---|---|---|---|---|
| 背景模糊（降采样 + 3 遍盒式 + 上采样） | `CoverBlurCache.blurred` → `MotionBackdrop` 的 `Image` | **不在**（**每首歌一次**） | 0（静态位图，每帧仅一次纹理采样） | `CoverBlur.kt:256-279`；`MotionBackdrop.kt:129-138` |
| 背景呼吸 | `MotionBackdrop.kt:108-119` 的 `graphicsLayer` | 在 | **1 次 `MotionClock.generation` 状态读 + 4 次浮点读写**（`level()` 读 + `alpha`/`scaleX`/`scaleY` 三次写）；**0 笔绘制增量、0 重组** | `MotionBackdrop.kt:108-119` |
| 封面浮起阴影 | `Modifier.shadow(...)`（`StableCover` 内） | **不在**（**静态**，elevation 不随时间变） | 0（一个 render layer + 一次阴影绘制，仅在挂载/形状变化时更新） | `PlayerCard.kt:1986-2000` |
| 封面切歌淡入 | `StableCover` 的 `crossfade: Animatable` | 只在**切歌后的 400 ms** 内 | 一次 `graphicsLayer.alpha` 写入 / 帧，只让上层 `Image` 那一层失效 | `PlayerCard.kt:1971-1978`、`:2029`；`AppMotion.coverFade = tween(400, …)`（`AppMotion.kt:213`） |
| 背景层挂载判据 | `if (motion.anyUiMotion)` | 组合期 | 关掉总开关 ⇒ **整层不挂载**（不是 `alpha=0`） | `PlayerCard.kt:747-763`；`MotionEffects.kt:192` |

### 2.2 背景模糊：**每首歌一次，不在帧路径上**

- 计算入口只有一个：`MotionBackdrop` 的 `LaunchedEffect(coverKey, coverBitmap)`（`MotionBackdrop.kt:88-96`），
  key 是封面 URL + 位图 ⇒ **同一首歌不会重算**（缓存命中时 `CoverBlurCache.peek` 同步返回，`:94`）。
- 单次代价（**【估算】**，量化过程见 `probe-blur.md` §1/§2）：
  - 源像素拷贝：`getPixels` 对 1000² 要拷 **4 MB**（源码自述「在 S6 上是**十几毫秒**量级」，`CoverBlur.kt:259-260`）；
  - 降采样：一次 O(源像素) 遍历（1024 个目标像素 × 各约 977 个源像素，`CoverBlur.kt:50-53`）；
  - 模糊：`3 遍 × 4 通道 × 2 方向 × 1024 ≈ 24.6 k` 滑动窗口步（源码 KDoc 记作「≈6 k 次加法」，
    那是**按单通道**算的；见 `probe-blur.md` §1 的算式修正），源码自述「一次约 **几十微秒**」（`CoverBlur.kt:38`、`:35`）；
  - 产出：一张 `32×32 ARGB_8888 = 4 KB` 的位图（`CoverBlur.kt:307-310`）。
- **结论**：这一项**不进每帧预算**。它进的是「切歌那一帧」的预算，而且已经挪到 `Dispatchers.Default`
  （`CoverBlur.kt:273`）——但**`IntArray(w*h)` 的 4 MB 分配仍在**，GC 压力是真实的
  （**【未验证】**：本版没有测过切歌时的长帧）。

### 2.3 背景呼吸：一次图层属性更新，零绘制增量、零重组

```kotlin
// MotionBackdrop.kt:105-127（原文，节选）
Box(
    modifier = Modifier
        .fillMaxSize()
        .graphicsLayer {
            // 呼吸：随 RMS 的明暗 + 缩放（幅度见 AppMotion）。
            // 静态时 level() = 0 ⇒ 倍率恰好是 1f，与「没有呼吸」逐像素一致。
            if (motion.backgroundBreathing) {
                MotionClock.generation
                val level = MotionClock.level()
                alpha = (1f - AppMotion.BREATH_ALPHA_AMPLITUDE +
                    AppMotion.BREATH_ALPHA_AMPLITUDE * 2f * level).coerceIn(0f, 1f)
                val breathScale = 1f + AppMotion.BREATH_SCALE_AMPLITUDE * level
                scaleX = breathScale
                scaleY = breathScale
            }
            // 视差：背景走前景 [AppMotion.PARALLAX_FACTOR] 的行程（竖屏为主，任务书 §5.4）。
            if (motion.parallax) {
                translationY = (1f - parallaxProvider().coerceIn(0f, 1f)) *
                    parallaxTravelPx * AppMotion.PARALLAX_FACTOR
            }
        }
)
```

逐项拆解（**这就是「每帧增量」的全部内容**）：

| 动作 | 次数/帧 | 说明 |
|---|---|---|
| 读 `MotionClock.generation` | 1 | Compose 状态读；**只在 `graphicsLayer` 块里 ⇒ 只让这一层失效，不触发重组**（`MotionClock.kt:66` 的契约原文：「只应在 draw / `graphicsLayer` 块里读（在组合阶段读会变成每帧重组）」） |
| 读 `MotionClock.level()` | 1 | 普通字段读（`MotionClock.kt:73`） |
| 写 `alpha` / `scaleX` / `scaleY` | 3 | 图层属性，**不产生新的绘制调用** |
| 绘制增量 | **0** | 呼吸不改内容，只改这一层的合成参数；模糊位图本身是静态的（`MotionBackdrop.kt:60-64` 原文：「背景位图本身是静态的：每帧只做一次纹理采样（`drawImage` 一个四边形）」） |
| **合计** | **1 次状态读 + 4 次浮点读写** | 与任务书的「4 次浮点读写 + 一次 `generation` 状态读」同一件事 |

- 幅度 token：`BREATH_ALPHA_AMPLITUDE = 0.05f`（±5%）、`BREATH_SCALE_AMPLITUDE = 0.01f`（±1%）——
  与任务书 §4.2 的「亮度 ±5% / 缩放 ±1%」逐字一致（`AppMotion.kt:312-329`）。
- **静态时恰好是「没有效果」**：`level() == 0` ⇒ `alpha = 1 - 0.05 + 0 = 0.95`？
  **注意**：源码注释写「静态时 `level()` = 0 ⇒ 倍率恰好是 1f」（`MotionBackdrop.kt:110`），
  但按算式 `alpha = 1 - A + A·2·0 = 1 - 0.05 = 0.95`、`scale = 1 + 0.01·0 = 1f`。
  也就是说**缩放**是恒等的，**alpha 不是 1 而是 0.95**（呼吸的「谷底」）——
  与注释「逐像素一致」的说法有出入。**【事实】一处注释与算式不符**（行为上无害：0.95 的底色 alpha
  叠在不透明底色上，观感是背景略暗 5%），但读注释的人会被误导。

### 2.4 封面浮起阴影：静态，不进帧路径

```kotlin
// PlayerCard.kt:1986-2000（原文，节选）
            // v2.9.0 · A 档：浮起阴影。**在 clip 之前**（= 更外层），这样阴影画在裁切层之外、
            // 跟随同一个 `graphicsLayer` 变换一起移动/缩放；顺序反过来阴影会被自己裁掉。
            // `clip = false` 是有意的：裁切交给下面的 `clip(shape)`，两处都裁会多一层离屏缓冲。
            .then(
                if (shadowElevation != null) {
                    Modifier.shadow(
                        elevation = shadowElevation, shape = shape, clip = false,
                        ambientColor = shadowColor, spotColor = shadowColor,
                    )
                } else { Modifier }
            )
```

- 由 `motion.coverElevation` 驱动（`PlayerCard.kt:1746`），elevation = `AppMotion.COVER_SHADOW_ELEVATION_DP = 8.dp`（`AppMotion.kt:310`）；
- **阴影参数是常量**（`elevation` / `shape` / 颜色都不随帧变化）⇒ render layer 在**挂载 / 形状变化**时更新一次，
  帧路径上是 0。关掉时 `shadowElevation = null` ⇒ 与 v2.8.0 逐像素一致（`PlayerCard.kt:1744-1746` 的注释原文）；
- **【事实】一处「能力位声明」与「实际驱动」的错位**：`MotionEffects` 的 KDoc 把 `coverElevation` 描述成
  「封面浮起阴影 **+ 随节拍微浮动（幅度 ±2dp）**」（`MotionEffects.kt:153`），
  但那个浮动读的是 `MotionClock.pulse()`，而读它的代码条件是 `if (motion.beatPulse || motion.cover3d)`
  （`PlayerCard.kt:1768-1771`）—— **简洁档（只开 A）两者都是 false ⇒ `beatPulse` 恒为 0 ⇒ 浮动恒为 0**。
  也就是说：**「A 档的封面浮动」只在精致档及以上才真的会动**，而它被记在了 A 档的账上。
  对性能预算的影响是**偏保守的**（A 档实际更便宜），但读 KDoc 会高估 A 档的帧成本。

### 2.5 封面切歌淡入：只在切歌后的 400 ms 内跑

```kotlin
// PlayerCard.kt:1971-1978（原文）
    LaunchedEffect(model, crossfadeEnabled) {
        if (!crossfadeEnabled) {
            crossfade.snapTo(1f)
            return@LaunchedEffect
        }
        crossfade.snapTo(0f)
        crossfade.animateTo(1f, AppMotion.coverFade)
    }
```

- 驱动对象是**单层 `Image` 的 `graphicsLayer { alpha = crossfade.value }`**（`PlayerCard.kt:2016-2021`），
  在 `graphicsLayer` 块里读 `Animatable` ⇒ 只让这一层失效；
- 时长 = `AppMotion.coverFade = tween(400, CubicBezierEasing(0.2f, 0f, 0f, 1f))`（`AppMotion.kt:213`）；
- **不卸载任何子树**（注释原文：「v2.6.0 的教训是 `AnimatedContent` 会在切换期把旧子树卸载掉（状态丢失 + 命中区抖动），
  这里刻意用一个 `Animatable` + `graphicsLayer` 实现同一观感，子树始终挂载」，`PlayerCard.kt:1955-1958`）；
- 关掉时 `snapTo(1f)` ⇒ **0 帧动画**，与 v2.8.0 的硬切逐帧一致。

### 2.6 与 v2.8.0 波形每帧增量的对比（A 档每帧增量 ≪ 波形）

v2.8.0 的波形在**每一帧**都要重画（`AudioVisualizer.kt` 的 `drawWaveformBars`）：

| 绘制调用 | 每帧笔数 | 条件 | 依据 |
|---|---|---|---|
| `drawRoundRect`（圆角柱） | **28**（`BAR_COUNT = 28`） | 恒（`of()` 里 `rounded = true`） | `VisualizerTier.kt:209`；`AudioVisualizer.kt:509`、`:517` |
| `drawCircle`（柱顶光点） | **≤28** | 精致档起（`dots = refined`）+ 柱高 > `minBar × 3` | `VisualizerTier.kt:213`；`AudioVisualizer.kt:531` |
| `drawRect`（峰值横条） | **≤28** | 恒（`peaks = true`）+ 峰值显著高于柱 | `VisualizerTier.kt:210`；`AudioVisualizer.kt:540-548` |
| `sqrt` 幅度映射 | 28（柱）+ ≤28（峰值） | 恒 | `AudioVisualizer.kt:496`、`:540` |
| （C 档另计）涟漪 + 粒子 | ≤3 描边圆 + ≤16 圆 | 只在 C 档 | `WaveformEffectsState.kt:262`、`:272`；`AudioVisualizer.kt:617`、`:642` |

⇒ **v2.8.0 的波形每帧 ≤84 笔绘制 + ≤56 次 sqrt**；而 **A 档的界面动效每帧 0 笔绘制**
（只有一次图层属性更新 + 一次纹理采样）。**这就是「A 档每帧增量显著小于波形」的完整依据。**

**同一档位下的总账**（以简洁档为例）：波形仍是 ≤28 `drawRoundRect` + ≤28 峰值；
界面动效新增的是「背景呼吸的一次图层属性更新」+「背景层的一次静态纹理采样」。
两者不在同一个量级上 —— 背景层的合成成本是否真的可忽略，**【未验证】**（见 §8）。

---

## 3. B 档：每帧增量逐项

### 3.1 背景级波形 = 多一次 `AudioVisualizerBars` 的 Canvas 绘制

```kotlin
// PlayerCard.kt:774-799（原文，节选）
        // ── v2.9.0 · B 档：**背景级波形**（横屏铺满底部横跨全屏 / 竖屏在歌词后面流动）──
        //
        // 它解决的问题是任务书 §7.2 的原话：「波形从左侧扩展到全屏底部横跨」。
        // 实现上**不动任何既有布局**（§7.3）：左栏那条波形原样留在原地，
        // 这一条是**额外**的一层背景 —— 所以"不改变现有横屏布局结构"这条要求由结构保证，
        // 而不是靠"改得小心"。
        if (motion.fullScreenWaveform) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(waveBackdropHeightDp)
                    .graphicsLayer {
                        alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) * WAVE_BACKDROP_ALPHA
                    },
            ) {
                AudioVisualizerBars(modifier = Modifier.fillMaxSize())
            }
        }
```

| 量 | 值 | 依据 |
|---|---|---|
| 每帧增量 | **多一次 Canvas 绘制**：≤28 `drawRoundRect` + ≤28 峰值 + ≤28 光点（与左栏那条**同一套画法**） | `PlayerCard.kt:781-784` 的注释原文：「复用 `AudioVisualizerBars` 而不是再写一份画法：A/B/C 三档的渲染差异…都已经在那里，复制一份必然分叉」 |
| 挂载高度 | `min(140.dp, screenHeightPx × 0.24f)`（源码算式） | `PlayerCard.kt:274-279` |
| 不透明度 | `WAVE_BACKDROP_ALPHA = 0.32f`（× 展开进度阶梯 `(p-0.7)/0.3`） | `PlayerCard.kt:1859`、`:794` |
| 数据源 | **同一个 `WaveformStore`**（读 `generation` + `snapshot(bars, peaks)`，`AudioVisualizer.kt:365-368`） | — |
| 帧时钟 | **同一条 `MotionFrameClock` 循环**（`MotionClock.frame` 里只调一次 `WaveformStore.pump`） | `MotionClock.kt:86-87` |
| 是否第二个 pump | **不是**。见下 | — |

**为什么不能有第二个 pump（这是 v2.9.0 最硬的一条结构约束）**：

> 如果两处各起一条循环，就会出现两个问题：
>  1. **同一个 `WaveformStore.pump` 被调用两次**：`consumePending` 是幂等的（第二次没有新柱），
>     但**动画相位**（渐变流动 / 呼吸）会每帧前进两次 —— 流动速度直接翻倍，
>     而且这个 bug 只在「波形与界面动效同时开」的设备上出现（横屏/平板），竖屏测不出来；
>  2. 两条循环各自持有 `LongArray` 帧时钟，暂停/恢复时的 `dt` 与节流各自为政，包络与柱高会**错帧**。
> —— `MotionClock.kt:38-45`（原文）

配套的代码事实：`AudioVisualizerBars` 的帧循环与帧监控**已经被删掉**，它的契约变成「**只读**」：

> 本组件的契约因此变成：**只读**（draw 阶段读 `WaveformStore.generation` 与快照），
> 推进由 `MotionFrameClock`（在 `PlayerCard` 里挂载恰好一次）负责。
> v2.8.0 的「不空转 / 零分配 / 零重组」三条契约一字未改，只是执行者换了地方。
> —— `AudioVisualizer.kt:361-363`（原文）

### 3.2 歌词律动：只让**当前行那一层**失效

```kotlin
// NcrustLyricsPanel.kt:557-567（原文）
                                // v2.9.0 · B 档：只有**当前行**随节拍脉冲。
                                //
                                // `MotionClock.generation` 刻意写在 `if (index == currentIndex)`
                                // **里面**：Compose 的快照观察是动态的，只有当前行这一层会订阅
                                // 帧时钟 —— 若写在外面，28 行歌词每帧各失效一次，那是纯浪费。
                                // 跨行时 `smoothCurrentIndex` 变化会让所有行重新执行本块，
                                // 依赖因此自然迁移到新的当前行，不会留下悬空的订阅。
                                if (lyricPulseEnabled && index == currentIndex) {
                                    MotionClock.generation
                                    scale *= 1f + MotionClock.pulse() * AppMotion.LYRIC_PULSE_SCALE
                                }
```

- 每帧增量：**一行**的 `graphicsLayer` 块里 1 次状态读 + 2 次 `scaleX/scaleY` 写；
- 幅度 `LYRIC_PULSE_SCALE = 0.03f`（+3%，`AppMotion.kt:360`）；
- 快照观察的依据是**动态订阅**：`generation` 的读发生在 `if` 里面 ⇒ 只有当前行订阅帧时钟；
  跨行时 `smoothCurrentIndex` 变化会让所有行重跑这个块，订阅自然迁移（注释原文，`:560-563`）；
- **【事实】这个「动态订阅」是【推导】级结论，不是实测**：源码注释给了机制解释，但本轮没有验证
  「只有一行在订阅」（需要 Compose 的 snapshot 观察统计或 instrumented 测试）。**【未验证】**。

### 3.3 节拍脉冲：控制条多一次缩放图层更新

```kotlin
// PlayerCard.kt:812-826（原文，节选）
                    Box(
                        modifier = Modifier.graphicsLayer {
                            if (motion.beatPulse) {
                                MotionClock.generation
                                val pulseScale = 1f + MotionClock.pulse() * AppMotion.BEAT_PULSE_SCALE
                                scaleX = pulseScale
                                scaleY = pulseScale
                                transformOrigin = TransformOrigin(0.5f, 0.5f)
                            }
                        }
                    ) { FullPlayerControls(… ) }
```

- 每帧增量：**一次图层缩放更新**（1 次状态读 + 2 次写），控制条子树**不重组、不重绘**；
- 幅度 `BEAT_PULSE_SCALE = 0.02f`（+2%），注释写明上限的理由是「按钮是点击目标」（`AppMotion.kt:363`）；
- 同一份 `beatPulse` 还被封面浮动消费（`PlayerCard.kt:1768-1792`）—— 但**只在精致档及以上**（§2.4）。

### 3.4 视差：背景层多一次 `translationY` 更新

- 落点与呼吸**在同一个 `graphicsLayer` 块里**（`MotionBackdrop.kt:120-126`），所以**不额外增加图层数**；
- 每帧增量：1 次 `parallaxProvider()` 调用（读 `progress.value`）+ 1 次 `translationY` 写；
  **注意它是「按需」的**：`progress` 静止时位移恒定 ⇒ 不产生新的重绘（源码注释原文：
  「静止时位移是恒定的，不产生额外重绘」，`MotionBackdrop.kt:121-122`）；
- 行程由调用方算好：`parallaxTravelPx = screenHeightPx * AppMotion.PARALLAX_TRAVEL_FRACTION`（`PlayerCard.kt:757`），
  fraction = 0.06（屏幕高的 6%，`AppMotion.kt:351`），系数 `PARALLAX_FACTOR = 0.35f`（`:338`）；
- **横屏的实际效果**：源码自己承认「横屏时卡片位移被分栏布局吸收，视差几乎不可见」（`AppMotion.kt:336`）。

### 3.5 B 档总增量

| 项 | 每帧增量 | 是否新增图层 |
|---|---|---|
| 背景级波形 | 1 个 `Box` + 1 个 Canvas（≤84 笔） | 是（一个合成层，`alpha=0.32`） |
| 歌词律动 | 当前行 1 次 `graphicsLayer` 更新 | 否（复用既有行图层） |
| 节拍脉冲 | 控制条 1 次 `graphicsLayer` 更新 | 是（控制条外多包了一个 `Box`） |
| 视差 | 背景层 `translationY` 1 次写（与呼吸共用图层） | 否 |

---

## 4. C 档：每帧增量逐项

```kotlin
// MotionBackdrop.kt:154-191（原文，节选）
            Canvas(Modifier.fillMaxSize()) {
                // 在 draw 阶段读：只让这块画布失效重绘，不触发重组。
                MotionClock.generation
                val backdrop = MotionClock.backstage
                if (size.width <= 0f || size.height <= 0f) return@Canvas
                val center = Offset(size.width / 2f, size.height * HALO_CENTER_Y_FRACTION)
                val maxRadius = size.minDimension * HALO_MAX_RADIUS_FRACTION
                if (motion.haloBloom) {
                    for (i in 0 until backdrop.haloCapacity) {          // HALO_CAPACITY = 2
                        val progress = backdrop.haloProgressAt(i)
                        if (progress < 0f) continue
                        drawCircle(color = haloColor.copy(alpha = alpha), radius = radius,
                                   center = center, style = Stroke(width = strokeWidth))
                    }
                }
                if (motion.particles) {
                    for (i in 0 until backdrop.particleCapacity) {      // PARTICLE_CAPACITY = 32
                        val life = backdrop.particleLifeAt(i)
                        if (life <= 0f) continue
                        drawCircle(color = particleColor.copy(alpha = life * PARTICLE_MAX_ALPHA), …)
                    }
                }
            }
```

| 项 | 上限 | 每帧增量 | 依据 |
|---|---|---|---|
| 光晕 | **≤2**（`HALO_CAPACITY = 2`） | ≤2 笔**描边圆**（`Stroke`）+ 每次强拍 1 次槽位写入 | `MotionEnvelope.kt:315`；`MotionBackdrop.kt:161-175` |
| 粒子 | **≤32**（`PARTICLE_CAPACITY = 32`） | ≤32 笔 `drawCircle` + 活跃粒子的 ~6 次浮点更新 | `MotionEnvelope.kt:316`；`MotionEnvelope.kt:253-272` |
| 每次强拍粒子 | 6（`PARTICLES_PER_BEAT = 6`） | 6 次写入（无分配） | `MotionEnvelope.kt:317`、`:284-300` |
| 粒子/光晕状态 | 定长 SoA `FloatArray`（2 + 32×5 个槽位） | **零分配**（构造时分配一次，之后原地更新） | `MotionEnvelope.kt:188-196` |
| 循环次数 | 每帧固定 2 + 32 = **34 次槽位检查**（空槽 `continue`） | 与活跃数无关的定长开销 | `MotionBackdrop.kt:162`、`:178` |
| 封面 3D | 一个 render layer | 每帧 1 次矩阵合成 + 该层 GPU 合成（`rotationY`/`rotationX`/`cameraDistance` 三次写） | `PlayerCard.kt:1796-1800`；`AppMotion.kt:371/378` |
| 3D 幅度 | `COVER_3D_DEGREES = 6f`、`COVER_3D_CAMERA_DISTANCE_FACTOR = 24f` | — | `AppMotion.kt:371`、`:378` |

- 池满策略是**覆盖最旧的**（游标轮转），「绝不扩容、绝不等待」（`MotionEnvelope.kt:183`）；
- 光晕/粒子画在**遮罩之上**（`MotionBackdrop.kt:149` 注释原文：「画在遮罩之上，才看得见」），
  但它们仍在 **`MotionBackdrop` 这一层内部**，因此 z 序低于内容 `Column`（`PlayerCard.kt:801`）——
  **不抢前景**；
- 光晕中心 `HALO_CENTER_Y_FRACTION = 0.42f`、最大半径 `0.7 × minDimension`、最大 alpha `0.35f`
  （`MotionBackdrop.kt:206-212`）；
- **封面 3D 的合成成本**：v2.8.0 的探针已把「3D 透视新增 render layer」列为「低端机上**可能最高**的一项，
  本环境无法量化 ⇒ 必须真机 A/B」（`docs/verification/v2.8.0/probe-waveform-tier.md:70`）。
  本版把同一条结论搬到**封面**这一层：**【未验证】**，见 §8。

---

## 5. 降级策略（铁律 23：优先砍界面动效，再砍波形档位）

### 5.1 阶梯表（**显式的表**，不是散落的 `if`）

```kotlin
// MotionEffects.kt:80-88（原文的 KDoc 表）
 * 任务书铁律 23：**优先砍界面动效，再砍波形档位**。这条纪律在这里被写成一张
 * 显式的、可单测的阶梯表，而不是散落在三处的 `if`：
 *
 * | 级别 | 名字 | 动作 | 用户感知 |
 * |---|---|---|---|
 * | 0 | [NONE] | 什么都不做 | — |
 * | 1 | [UI_ADVANCED_OFF] | 关掉 **B 档**界面动效（全屏波形 / 歌词律动 / 节拍脉冲 / 视差） | 背景模糊与呼吸还在，画面安静一点 |
 * | 2 | [UI_ALL_OFF] | 再关掉 **A 档**界面动效（背景回纯色） | 播放页与 v2.8.0 完全一致 |
 * | 3 | [WAVEFORM_DOWN] | 波形档位**降一级**（写回 `motion_tier`） | 波形少一层特效 |
```

代码落点：常量 `NONE=0 / UI_ADVANCED_OFF=1 / UI_ALL_OFF=2 / WAVEFORM_DOWN=3`、`MAX = WAVEFORM_DOWN`
（`MotionEffects.kt:107-113`）；推进 `next(current)`：到顶返回 `null`，否则 `+1`（`:126-129`）；
三个判据函数 `allowsAdvancedUi` / `allowsBasicUi` / `cutsWaveformTier`（`:132-138`）。

**用户可见的承诺**（i18n 原文，`zh_CN.kt:120`）：
「关闭后背景回纯色、不再有任何逐帧动效，性能最优；**帧时间持续超标时系统会先自动削减界面动效，再降低波形档位**」
—— 阶梯表与文案是同一件事的两种表达。

### 5.2 三条边界（铁律 4）

| 边界 | 实现 | 行号 |
|---|---|---|
| **总共最多三级** | `MAX = WAVEFORM_DOWN`，`next()` 到顶恒 `null` —— 「永不自动恢复、永不无限降级」 | `MotionEffects.kt:113`、`:126-129` |
| **只推进一级** | `applyAutoDowngrade` 每次只写 `next = current + 1`；第 3 级才 `motion_tier - 1`（且 `coerceAtLeast(SIMPLE)`） | `MotionPrefs.kt:225-234`、`:228` |
| **用户手动改档位 → 重置为 0** | `writeTier` 同时写 `KEY_TIER` 与 `KEY_DEGRADE_LEVEL = NONE` | `MotionPrefs.kt:137-142` |
| （反例，**有意**）总开关**不**重置水位 | `writeUiMotionEnabled` 只写一个键 | `MotionPrefs.kt:144-150`（注释原文：「总开关是用户的稳定偏好，降级水位是设备实测的结论，两者的生命周期不同」） |

**为什么「优先砍界面动效」写在阶梯里而不是靠顺序调用**：KDoc 原文说
「写成一串散落的 `if` 会让「先砍谁」变成不可断言的口头约定」（`AGENTS.md:4238-4239` 的同一句话）；
单测侧由 `MotionEffectsTest` 的 `第一级降级只砍 B 档界面动效` / `第二级降级把 A 档也砍掉` /
`第三级降级才动波形档位且只降一级` / `降级级别与界面动效的对应关系是单调的` 四个用例钉住。

### 5.3 误判的代价被限制在「少看一层特效」

> 与 v2.8.0 的 `VisualizerTier.downgradedTier` 同源：降级是**一次性单向阀**，
> 判据（60 帧窗口内 40% 超标）本身不知道是谁把帧顶起来的（归因要 Perfetto）。
> 误判的代价必须小 —— 「用户少看一层特效」而不是「用户被一次扒光所有动效」。
> 三级阶梯让「判定错了」这件事最多只损失一档，且下一级只在**再次**触发时才发生。
> —— `MotionEffects.kt:98-103`（原文）

这四句是**三件事**的组合：① 判据不做归因（诚实边界）；② 单向阀（不振荡）；
③ 分三级（损失有界）。三者缺一，误判的代价就会从「少看一层」变成「观感被扒光」。

### 5.4 **【事实】措辞校正**：「每进程最多推进一级」实际是「每次注册最多一级」

- `FrameBudgetPolicy` 保证的是**每实例只判定一次**：`private var decided = false`，
  `if (decided) return false`（`FrameBudgetPolicy.kt:65-66`、`:77`、`:92-93`）；
  KDoc 原文：「每个 `FrameBudgetPolicy` 实例**最多返回一次 true**」（`:30`）。
- `VisualizerFrameMonitor` 在判定成立后**立刻注销自己**（`VisualizerFrameMonitor.kt:62-64`），
  所以那一个实例不会再触发第二次。
- **但注册是按 `DisposableEffect(hostActivity, monitorEnabled)` 的 key 走的**
  （`MotionClock.kt:164-175`）：`monitorEnabled = clockNeeded && motion.degradeLevel < MAX && (…)`（`:161-163`）。
  这个 `DisposableEffect` 只在两个 key **发生变化**时重跑，于是有两条能拿到「新实例」的路径：
  1. **`monitorEnabled` 由 true→false→true**：最典型的是**播放器卡片收起再展开** ——
     `enabled = hasSong && expandedMounted`（`PlayerCard.kt:769-772`），
     `expandedMounted = progress.value > 0.01f`（`PlayerCard.kt:343`，v1.3.0 · B-1 的「折叠态不挂载展开态子树」）。
     收起时 `clockNeeded`/`monitorEnabled` 变 false ⇒ 旧实例被 `onDispose { monitor?.stop() }` 注销（`:173`）；
     再展开 ⇒ key 变回 true ⇒ **注册一个新实例**，它可以再判定一次。
  2. **宿主 Activity 变化**（`hostActivity` 是 key 之一）。
- **结论**：真正的边界是「**每次注册**最多一级」+「水位到顶（`< MAX`）即不再注册」⇒
  同一进程内**最多推进 3 级**，与「总共最多三级」自洽；但「**每进程**最多推进一级」这个说法
  **比代码强**（同一进程里收起/展开一次就能再推进一级，最多到 `MAX` 为止）。
  **【建议】**（非本轮结论）：把 KDoc / `AGENTS.md` 的措辞改成「每**次注册**最多推进一级，
  水位到顶即不再注册」，或在 `VisualizerFrameMonitor` 之外加一个进程级 `AtomicBoolean`
  把语义真正做成「每进程一级」（前者更省事，后者才是文档现在承诺的东西）。

### 5.5 **【事实】换歌清空**未接线**（`MotionClock.clear()` 无调用点）

```kotlin
// MotionClock.kt:102-107（原文）
    /** 换歌 / 停止：清空包络与特效池（与 `WaveformStore.clear()` 一起调）。 */
    fun clear() {
        envelope.clear()
        backdrop.clear()
        generationState.intValue++
    }
```

实测（本轮 grep）：

- `MotionClock.clear()` 的调用点 **0 个**（全仓 `app/src/main` 只有定义处 `MotionClock.kt:103`）；
- 它引用的 `WaveformStore.clear()` **不存在**：`WaveformStore` 只有 `internal fun resetForTest()`
  （`AudioVisualizer.kt:219-224`），那是单测入口（`internal`，只在测试源集可见）。

后果（**【推导】**，不改变有界性）：切歌（尤其无缝切歌，`active` 不经过 false）时，
`MotionEnvelope.baseline` / `pulse` 与 C 档的 `haloProgress` / 粒子池会**跨歌保留**：

| 状态 | 上界 | 影响 |
|---|---|---|
| `baseline` | 单标量，落在 0..1 | 新歌第一根柱的 onset 判据用的还是上一首的基线 ⇒ 可能**漏一次**或**多一次**重拍（一次性） |
| `pulse` | 0..1，`PULSE_TAU_MS = 350ms` 内衰减到 0 | 最多 0.35 s 的残留脉冲 |
| 光晕 / 粒子 | 2 + 32 个定长槽位，寿命 ≤ `900ms` | 上一首的粒子最多再飘 0.9 s |

**没有无限增长、没有内存风险**（全部定长 + 有时间常数），但「换歌即清空」这条**写在 KDoc 里的契约没有落地**。
**【建议】**：在 `MotionBackdrop` 或 `PlayerCard` 的换歌副作用里调一次 `MotionClock.clear()`，
或者把 KDoc 改成「不做换歌清空，靠时间常数自愈」。**这条要进 EVIDENCE.md 的未验证/遗留清单。**

---

## 6. 帧监控：**复用** `VisualizerFrameMonitor`，但注册点搬到 `MotionFrameClock`

### 6.1 结论与理由

| 问题 | 答案 | 依据 |
|---|---|---|
| 复用还是新建？ | **复用** `VisualizerFrameMonitor`（`Window.addOnFrameMetricsAvailableListener`，**API 24+**，正好等于 minSdk） | `VisualizerFrameMonitor.kt:18`、`:76-84` |
| 注册点在哪？ | **`MotionFrameClock`**（`ui/player/motion/MotionClock.kt`），不再是波形组件 | `MotionClock.kt:159-175` |
| 判据改了吗？ | **一字未改**：60 帧窗口 / `16_666_667ns` 预算 / 24 帧（40%）门槛 | `FrameBudgetPolicy.kt:102-111` |

**为什么必须搬（四条，前两条是结构性的）**：

1. **竖屏手机上波形组件根本不挂载**：`PlayerLayout.visualizerSlot` 的六格 A/B 表里，
   「手机竖屏」与「手机横屏·非大屏模式」两格恒为 `false`（`PlayerLayout.kt:60-88`，表在 `:66-77`）。
   监控只挂在波形里 ⇒ 这两格的用户永远不会被判定超标 ⇒ 「优先砍界面动效」**永远不会触发**
   （`MotionClock.kt:126-131` 的 KDoc 原文：「竖屏用户开着背景模糊与呼吸时，波形组件不存在，
   监控就永远不会注册，「自动降级优先砍界面动效」也就永远不会触发」）。
2. **降级阶梯要覆盖界面动效**：v2.9.0 的 `MotionDegrade` 第 1/2 级砍的全是**界面动效**
   （§5.1），而界面动效在竖屏也有（背景模糊 + 呼吸）—— 判据必须挂在「有界面动效就有帧循环」的那一层。
3. **判据本身要求「确实有东西在动」**：注册条件四条（`MotionClock.kt:133-137`）：
   ① 本组件挂载；② 宿主 Activity 找得到（找不到**静默不监控**，`findHostActivity` 返回 `null`，`:217-224`）；
   ③ 波形档位 > 简洁 **或** 界面动效开着；④ 阶梯还没到顶。
4. **关掉 = 零开销由结构保证**：`LaunchedEffect` 的 key 里带 `clockNeeded`，为 false 时**直接 return**
   （`MotionClock.kt:178-179`）；`DisposableEffect` 在 `!monitorEnabled` 时只 `onDispose { }`（`:164-168`）。

### 6.2 一次降级的完整链路（端到端）

```
Window.FrameMetrics 回调（主线程）
  → VisualizerFrameMonitor.Listener.onFrameMetricsAvailable   // 整体包在 runCatching 里，绝不崩主线程
      → FrameBudgetPolicy.onFrame(TOTAL_DURATION)             // 60 帧滑窗 / ≥24 帧超 16.67ms / 每实例一次
          → 先 removeOnFrameMetricsAvailableListener(this)     // 判定成立立刻注销
          → onSustainedOverBudget()
              → runCatching { MotionPrefs.applyAutoDowngrade(activity) }   // MotionClock.kt:169-172
                  → MotionDegrade.next(level)                  // 到顶 → null（不动作）
                  → 写 motion_degrade_level（第 3 级另写 motion_tier - 1）
                  → refresh(context) ⇒ effectsStateHolder.value 变化
                      → MotionPrefs.effects（State）→ 组合期各消费点重组一次 ⇒ 整层能力位更新
```

（落点：`VisualizerFrameMonitor.kt:56-66`、`FrameBudgetPolicy.kt:76-94`、`MotionClock.kt:169-172`、
`MotionPrefs.kt:225-234`、`:302-306`、`:323-328`。）

**降级动作本身也在隔离边界里**：`runCatching { MotionPrefs.applyAutoDowngrade(activity) }`
（`MotionClock.kt:170-172` 的注释原文：「降级动作本身也在隔离边界里（写 prefs + 刷新状态都可能失败，
失败就当没降）」）。**「失败就当没降」是有意的**：宁可这台机器多掉几帧，也不要因为一次写盘失败
让整个动效链路进入不确定状态。

---

## 7. 性能底线与验收口径

| 设备 | 底线 | 依据 / 状态 |
|---|---|---|
| **S6**（API 24 / Android 7.0 / 1440×2560） | **允许掉到 30 fps，但不能卡死** | HARD FACT 的 S6 读数（v2.8.0 T0 = 32.1 fps 换算）已经在这个区间；`visualizerFrameIntervalMs` 在 `SDK_INT < 26` 时**主动**把重绘上限降到 **33 ms**（`AudioVisualizer.kt:229-232`、`:247-253`）——所以「30 fps」在这台机器上是**设计目标**而不是退化 |
| **PCL110**（API 36 / Android 16 / 144 Hz） | **目标 60 fps** | HARD FACT 实测 60.7 fps（1821 帧 / 30 s）、janky 0.27% —— 已经达标 |
| 超出预算 | **自动降级**（§5 的三级阶梯） | `MotionDegrade` + `FrameBudgetPolicy` |

### 7.1 「S6 上更贵」是可能成立的结论

> 低端机上「新档位比旧实现更贵」是**可能成立的结论**：v2.8.0 在 S6 上 P50 +5…8 ms、
> P90 +2…6 ms。所以本条的验收口径是「有数字、方向明确、缓解措施落地」，不是「必须与旧版打平」。
> —— `AGENTS.md:4171-4173`（v2.8.0 写下的同一句话，v2.9.0 沿用）

**v2.9.0 的验收口径因此是**：

1. **有数字**：三档在 S6 与 PCL110 上的 release 包 30 s `dumpsys gfxinfo`（方法同 §1）；
2. **方向明确**：相对 v2.8.0 同档位的增/减方向与量级要说清，**不要求打平**；
3. **缓解措施落地**：三级降级阶梯 + 「关掉总开关 = 零开销」+ 低端机默认简洁档。

**【事实】本版连「有数字」这一步都还没做**（§8）。所以本文的性能结论**全部是预算估算，不是测量**。

---

## 8. 本版**没有**测到的（如实）

1. **没有任何 v2.9.0 的帧时间**：§2-§4 的每帧增量全部是**代码推导 + 源码 KDoc 自述**，
   没有一条是实测。三档（A/B/C）× 两台设备（S6 / PCL110）= 至少 6 组 `dumpsys gfxinfo` 还没跑。
2. **WGR-W09 无法驱动**：该设备（Huawei 平板，API 31 / Android 12，1600×2560 override）
   **当前锁屏、休眠且无 root**（HARD FACT）⇒ 既不能驱动 UI，也不能用
   `set-prefs.py` 注入 prefs（脚本需要 root 改写 `ncrust_settings.xml`）。
   这台设备上的**界面动效与帧时间全部未验证**，API 31 这一档（`RenderEffect` 可用的分界线）因此也没有对照。
3. **Perfetto 归因没做**：「到底是谁把帧顶起来的」这件事本版**没有做**，
   所以降级判据仍然是**保守单向阀**（`FrameBudgetPolicy.kt:35-39` 的 KDoc 原文自己也这么说：
   「这个判据**不知道**是谁把帧顶起来的。归因需要 Perfetto」）。
   后果：任何**非动效**的长帧（封面解码、列表滚动、GC、系统调度）都可能被算进这个窗口并触发降级 ——
   这正是「误判代价必须有界」的来由（§5.3）。
4. **音频线程增量没测**：`TransparentWaveformSink.handleBuffer` 的逐样本 RMS 成本、真实回调率、
   每缓冲的堆分配（`duplicate()` + `asShortBuffer()`，v2.8.0 探针记为 2 次/缓冲）
   —— **本版一条都没测**，也没有被本版改动（`MotionClock.frame` 里读的是
   `WaveformStore.newestBar()`，那只是**一次环形缓冲尾元素读**，`AudioVisualizer.kt:194`）。
   **【未验证】**：界面动效引入的包络计算是否对音频线程有**间接**影响（例如 GC 压力）——
   本轮没有 `atrace` 证据。这些要留到 `EVIDENCE.md` 的未验证清单。
5. **「只有一行歌词在订阅帧时钟」未验证**（§3.2，【推导】级）。
6. **A/B/C 三档的合成成本未验证**：新增的图层/合成项（背景级波形一层、控制条外层一层、
   封面 3D 变换一层、封面阴影一层）在低端机 GPU 上的代价**无法在无设备的环境里量化**，
   v2.8.0 的探针已经把同类项标成「本环境无法量化 ⇒ 必须真机 A/B」（`probe-waveform-tier.md:70`）；
   而且 Compose 是否真的为每一个 `graphicsLayer` 分配离屏缓冲，取决于 `alpha`/`clip` 等具体参数，
   本轮**没有逐个核对**。
7. **降级阶梯在真机上的推进速度**：S6 上 v2.8.0 的自动降级「几乎必然触发」（`EVIDENCE.md:114`），
   但 v2.9.0 的三级阶梯**在 S6 上实际推进到第几级、用多久**，本轮**没有实测样本**
   （`AGENTS.md:4310` 的「只有一次实测样本」说的也是这件事）。
8. **切歌长帧未测**：`CoverBlurCache` 的 4 MB `getPixels` + 400 ms 封面淡入是否在 S6 上叠加出可见长帧，
   未测（`probe-blur.md` §9 同一条）。
9. **未跑 `./gradlew test`**：本文引用的所有单测用例名与断言都是源码阅读，**没有执行过**。
