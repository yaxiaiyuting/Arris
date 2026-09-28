# 探针 · P1「新设备（高刷新率）音频条抖动」根因（v3.2.4）

> 口径：**先探针、后改码**。本文的代码结论全部带行号；帧率结论来自
> ①源码算术 ②对**逐字复刻**的闸门/插值逻辑做的确定性仿真
> （[`evidence/sim_final.py`](evidence/sim_final.py) → [`evidence/sim-framerate.txt`](evidence/sim-framerate.txt)）；
> 设备侧只用**实测**（`dumpsys display` / `dumpsys gfxinfo`）。
> **本环境没有 120Hz 真机**（见 §1），所以凡是需要高刷硬件才能定的结论，本文一律标注「未验证」。

## 0. 一句话根因

**帧闸门把「重绘预算」写死成 16ms（`VISUALIZER_FRAME_INTERVAL_FAST_MS`），从来没有读过设备刷新率。**
闸门的判据是 `now - 上次推进 >= 16ms`，而 `now` 只能取到**整数个 vsync**：

| 面板 | vsync | `ceil(16ms / vsync)` | 名义 dt | 距 16ms 的余量 | 结果 |
|---|---|---|---|---|---|
| 60Hz（S6） | 16.667ms | **1** | 16.667ms | +0.667ms | **每一帧都推进**，没有量化 ⇒ 平滑 |
| 90Hz | 11.111ms | 2 | 22.222ms | +6.222ms | 推进率只有面板的 **50%**（45fps） |
| 120Hz（新设备） | 8.333ms | 2 | 16.667ms | **+0.667ms** | 只要 frame pacing 抖一下，这一档就从「2 个 vsync」变成「3 个 vsync」（25ms）——**曲线左移量在 16.7ms 与 25ms 之间跳（+49%）** |
| 144Hz | 6.944ms | 3 | 20.833ms | +4.833ms | 推进率只有面板的 **33%**（48fps） |

也就是说：**同一份代码在 60Hz 上恰好退化成「每帧都推进」（无量化、无抖动），在 120Hz 上退化成
「每两帧推进一次、且这『两帧』随时会变成『三帧』」** —— 这正是用户报的「S6 好好的、新设备抖」。

仿真（`±0.6ms` frame-pacing 抖动、不掉帧，隔离闸门量化这一个变量）：

| 刷新率 | 实测推进率 | dt_p50 | dt_p99 | dt_max | Δφ_CV |
|---|---|---|---|---|---|
| 60Hz | 60.0 fps（100%） | 16.66 | 17.25 | 17.27 | **0.021** |
| 90Hz | 45.0 fps（50%） | 22.20 | 23.24 | 23.37 | 0.022 |
| **120Hz** | 57.4 fps（48%） | 16.76 | **24.63** | **24.82** | **0.125** |
| 144Hz | 48.0 fps（33%） | 20.82 | 22.11 | 22.33 | 0.028 |

`Δφ = dt / barIntervalMs` 就是**这一帧曲线左移了多少格**，它的离散度就是肉眼看到的抖动。
120Hz 那一行的 `Δφ_CV = 0.125`，是 60Hz（0.021）的 **6 倍**；`dt_p99 = 24.63ms`
说明**约 1% 的帧位移比中位数大 47%**，抖动频率约 0.6 次/秒量级；把 frame-pacing 抖动放大到
`±1.5ms`（真实设备上一帧画得慢一点就是这个量级），**30.3% 的推进都会多跨一个 vsync**。

## 1. 新设备的刷新率是多少？（60 / 90 / 120Hz）

| 设备 | 刷新率 | 来源 |
|---|---|---|
| 本环境可用设备：`emulator-5554` / `127.0.0.1:5555`（sdk_gphone64_x86_64 / API 33） | **60.000004 Hz**，且 `supportedModes` **只有这一个模式**（`alternativeRefreshRates=[]`） | `adb shell dumpsys display` 原始输出见 [`verification/EVIDENCE-waveform-v324.md`](verification/EVIDENCE-waveform-v324.md) |
| 用户报的「新设备」 | **未验证** —— 环境里没有高刷真机 | — |
| 对照机 S6 / SM-G9209 | 60Hz（历史结论，v3.2.2 / v3.2.3 的全部采样都在它上面做的） | `docs/verification/v3.2.2/probe-perf-tier.md` |

⚠️ **如实说明**：本版**无法**在高刷真机上复现「抖动」。能做到的是
①把根因用「与刷新率无关的判据」算出来（上面的表）；②用显式读刷新率的实现让 60/90/120/144Hz
四档走同一条判据；③在可用设备（60Hz）上做 **release 包不回归**验证。
`app/src/test` 里的 `DisplayRefreshTest` / `WaveformFrameRateTest` 用**注入的刷新率**把
60/90/120/144 四档钉死，这是本版能给出的最强证据（也正好是铁律 33 要求的形状：
**不得假设固定帧率**，而不是「在高刷机上试过了」）。

## 2. 帧时间数据（P50 / P90 / P99）

| 来源 | 数据 |
|---|---|
| S6 / v3.2.1 简洁档（基线） | p50 19ms / p90 28ms / p95 30ms / p99 36ms（`probe-perf-tier.md`） |
| S6 / v3.2.2 三条泳道 | p50 19ms / p90 26ms / p95 29ms / p99 38ms |
| 本环境 60Hz 模拟器 / **release 包** | 见 [`verification/EVIDENCE-waveform-v324.md`](verification/EVIDENCE-waveform-v324.md)（`dumpsys gfxinfo … framestats`，30s 窗口，多次采样取中位数） |

注意口径：S6 的 p50 = 19ms **比 16.67ms 还大**，说明那台机器本来就不是每帧都能出图
（流水线式掉帧，v1.6.0 已经记录过）。**「60Hz 上平滑」不是因为帧时间短，而是因为闸门在 60Hz 上
不产生量化**（§0 的余量列）—— 这一点是本次根因定位的关键，也是「为什么 30fps→16ms 的 v3.2.3
改动没有引出问题、而新设备引出问题」的答案。

## 3. 亚格插值实现逻辑（v3.2.3 的 `scrollPhase01`）

| 问题 | 答案（带行号） |
|---|---|
| 插值基于什么时间基准？ | **距上一根柱的毫秒数** `sinceBarMs`（`WaveformRing.kt:114`），在 `pump` 里按**帧间隔**累加（`:206 sinceBarMs += dtForPhase`），分母是**实测柱间隔的滑动平均** `barIntervalMs`（`:117`，EMA α=0.15，`:213`） |
| 是否假设了固定帧率？ | **插值本身没有**：`dt` 是真实帧间隔，`k = 1 - exp(-dt/tau)`、`sinceBarMs += dt` 都是时间常数/时间累加形式，30fps 与 120fps 画的是同一条运动曲线（`WaveformRing.kt:325-355` 的 KDoc 与实现）。**假设固定帧率的是它的上游**——`MotionFrameClock` 的 16ms 闸门（§5） |
| 高刷新率下插值是否仍平滑？ | **插值够用，闸门不够用**。渲染侧 `phase = clamp(sinceBarMs / barIntervalMs, 0, 1)`（`:441-444`），取相邻两格线性插值（`AudioVisualizer.kt:696-708`），相位分辨率只受 `dt` 影响：60Hz 下 0.167/帧、120Hz 下（每帧都推进时）0.083/帧 ⇒ **高刷只会更细**。真正把平滑度吃掉的是闸门把 `dt` 量化成 16.7/25ms 两档 |
| 插值会不会「插出假数据」？ | 不会：插的是**相邻两格之间**的位置（`a + (b-a)·phase`，`a`、`b` 都是已经到达的真实值），不外推未来；最后一格没有「下一格」时保持原值（`AudioVisualizer.kt:706-708`） |

## 4. 音频缓冲更新频率 vs UI 帧率

| 量 | 实测值 | 来源 |
|---|---|---|
| 音频缓冲粒度 | **100.00 ms（11.0~11.5 Hz）**，S6（API 24）与 PCL110（API 36）**都是**，直方图只有一个桶 | `docs/verification/v3.0.0/probe/EVIDENCE.md:40-47`，v3.2.2 探针复核 |
| UI 帧率（现状闸门） | 60Hz 面板 60fps / 90Hz 面板 45fps / 120Hz 面板 ~57fps / 144Hz 面板 48fps | §0 仿真 |
| 比值 | 60fps 时 **1 根柱 / 6.25 帧**；120Hz 面板若每帧推进则是 1 / 12.5 帧 | 算术 |

**缓冲粒度不是根因**：插值层就是为 11Hz 的数据补出逐帧连续滚动而存在的（v3.2.3 的设计），
它在 120Hz 上要补的格数更多、但每一格的位移更小，观感只会更好。
**没有**「缓冲粒度太粗所以要改音频链路」这回事 —— 那会动到铁律 29（不得新增每帧计算）。

## 5. 帧时钟 MotionClock：采样率与设备刷新率的关系

```kotlin
// MotionClock.kt:201
val frameIntervalMs = remember(context) { visualizerFrameIntervalMs(context) }
// MotionClock.kt:208
val budgetNs = frameIntervalMs * 1_000_000L
// MotionClock.kt:211-219
val onFrame: (Long) -> Unit = { now ->
    if (clock[0] == 0L || now - clock[0] >= budgetNs) {   // ★ 闸门
        val dtMs = if (clock[1] == 0L) frameIntervalMs.toFloat()
        else ((now - clock[1]) / 1_000_000f).coerceIn(1f, 100f)
        clock[0] = now; clock[1] = now
        MotionClock.frame(active = true, dtMs = dtMs, waveform = waveform, motion = motion)
    }
}
```

```kotlin
// AudioVisualizer.kt:515-531
fun visualizerFrameIntervalMs(context: Context): Long {
    val lowTier = activityManager?.isLowRamDevice == true
    return if (lowTier) VISUALIZER_FRAME_INTERVAL_SLOW_MS else VISUALIZER_FRAME_INTERVAL_FAST_MS
}
const val VISUALIZER_FRAME_INTERVAL_FAST_MS = 16L   // AudioVisualizer.kt:497
const val VISUALIZER_FRAME_INTERVAL_SLOW_MS = 33L   // AudioVisualizer.kt:500
```

| 问题 | 答案 |
|---|---|
| 采样率与设备刷新率的关系？ | **没有关系**。函数签名只吃 `Context`，判据只有 `isLowRamDevice`；16/33 两个常量都是「按设备档位拍的」，与面板刷新率无关 |
| 是否随刷新率自适应？ | **不**。既没有读 `Display.getRefreshRate()`，也没有注册 `DisplayManager.DisplayListener` ⇒ 切到 120Hz 模式（或 LTPO 动态变频）时代码路径**一个字节都不变** |
| 后果 | ①推进率不是面板刷新率（90Hz→45fps、144Hz→48fps）；②在 120Hz 上产生 §0 的**量化抖动** |

## 6. 与 S6 的对比：为什么 60Hz 平滑、120Hz 抖

| | S6（60Hz） | 新设备（120Hz） |
|---|---|---|
| vsync | 16.667ms | 8.333ms |
| 闸门预算 | 16ms | 16ms |
| 每几次 vsync 推进一次 | **1** | **2** |
| 闸门余量 | 0.667ms | 0.667ms |
| 抖动一次要多大扰动 | 让两帧间隔 <16ms（≈ −0.67ms） | 让两帧间隔 <16ms（≈ −0.67ms）**或**让「第 2 帧」晚到（+8.33ms 的整档跳变） |
| 量化误差 | **不存在**（正好 1 个 vsync） | **8.333ms**（预算的一半） |
| 仿真 Δφ_CV（±0.6ms） | 0.021 | **0.125** |

⇒ **「60Hz 平滑、120Hz 抖」不是设备问题，是闸门在这两个刷新率上的行为不同**：
60Hz 恰好落在「每帧都推进」的退化解上（因为没有比 1 个 vsync 更小的档），
120Hz 落在「两个 vsync 一推进」的解上，而 16ms 预算与 16.667ms 的间距只差 0.667ms。

## 7. 抖动来源定位（四选一）

| 候选 | 判定 | 依据 |
|---|---|---|
| 插值不够？ | **否** | 插值按真实 `dt` 推进（§3），相位分辨率随帧率**变细**；`WaveformRingTest`/`WaveformRingTierTest` 已钉住「30fps 与 60fps 同一条曲线」 |
| **帧时钟不同步？** | **是（根因）** | §5：预算与刷新率无关 + §0 的量化表 |
| 音频缓冲粒度太粗？ | **否** | §4：实测 100ms，且插值层的存在就是为了补它；改它要动音频链路（铁律 29 禁区） |
| 渲染层重复重组？ | **否** | 波形是 `Canvas` 的 draw 阶段读 `WaveformStore.generation`（`AudioVisualizer.kt:662-665`），不触发重组；全仓只有**一条** `withFrameNanos` 帧循环（`MotionClock.kt:222`，`grep -rn withFrameNanos app/src/main` 的其余命中都在歌词/队列的**按需**循环里） |

**次级风险（同一根因的另一面，本版一并处理）**：`visualizerFrameIntervalMs` 的 16ms
在**低于 60Hz** 或掉帧场景下也会变成「每 2 个 vsync 推进一次」（例如 60Hz 上真掉一帧 ⇒ dt=33ms），
仿真里 `±1.5ms` 抖动下 60Hz 的「多跨一个 vsync」占比 27.9%。这与 120Hz 的量化是同一个机制。

## 8. release 包验证方案

1. **单测（本版新增，JVM，确定性）**：
   - `DisplayRefreshTest`：60/90/120/144Hz 与非法值（0 / NaN / 1000）→ 帧预算；低内存设备仍取 33ms；
     预算**必须**是「一个刷新周期」⇒ 判据与刷新率同源。
   - `WaveformFrameRateTest`：用真实 `WaveformRing` + 逐字复刻的闸门，在 60/90/120/144Hz 四档
     各推 30 秒，断言 ①推进率 = 面板刷新率（±2%）②`Δφ` 的 CV 上限 ③**四档之间 Δφ_p50 的比值
     等于柱间隔之比**（即「同一条运动曲线」这条不变量在四档上都成立）。
2. **真机（本环境只有 60Hz）**：release 包 + `dumpsys gfxinfo <pkg> framestats`，30s 窗口 ×4 次采样
   取中位数，与 v3.2.3 的基线比（不回归）。原始件进
   [`verification/`](verification/)。
3. **高刷真机**：**未验证**（环境无设备）。代偿证据是第 1 条的四档单测 + 第 2 条的 release 不回归。
