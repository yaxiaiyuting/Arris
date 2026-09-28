# v3.2.2 探针总结（PROBE-SUMMARY）

本版目标：**波形条 → 连续曲线 + 主导频段着色**。探针在**任何生产渲染改动之前**落盘，
六份分项文档在 [§1 索引](#1-探针索引)，原始数据在 `probe/`（CSV / PNG / 逐次测量表）。

> 口径声明：本页所有数字都来自**实测**（真实音乐 / 真实设备 / 生产类），不是估算。
> 推断性的部分逐条标「推断」，未做的部分逐条标「未验证」。

## 0. 六问速答（任务书要求必须回答的六项）

| # | 问题 | 结论 | 依据 |
|---|---|---|---|
| 1 | **主导频段定义方案（含滞回阈值）** | **平滑 300ms（EMA）→ 取最大 → 滞回 20% → 静音闸门 0.06**；另加 100ms 颜色过渡。实测切换频率 **0.103 次/秒**（5 首 1219 秒合计）、最差曲目 0.169 次/秒、零闪断、最短驻留 300ms | [probe-dominant-band.md](probe-dominant-band.md)、[probe-color-transition.md](probe-color-transition.md) |
| 2 | **颜色切换方案** | **分段 + 短过渡 100ms**（不是硬切、也不是长线性渐变）。每帧最大色差 **7.13 ΔE**（硬切 17.58、400ms 渐变 3.11 但混合态占 4.1%） | [probe-color-transition.md](probe-color-transition.md)、[probe/color-transition-compare.png](probe/color-transition-compare.png) |
| 3 | **曲线平滑算法** | **单调三次 PCHIP**（保号切线 + 圆条件）。19055 帧 / 8.25M 采样点实测：过冲 **0**、负高度 **0**；Catmull-Rom 在同一份数据上负高度 4066 点、朴素单调三次过冲 1.3% | [probe-curve-smoothing.md](probe-curve-smoothing.md) |
| 4 | **档位适配** | **三档都画曲线**（曲线是基础渲染，不是效果）；**频段着色只在精致档及以上**（复用既有 `waveBandMode` 门槛），简洁档回落单一主题色。**界面动效总闸关掉 ⇒ 曲线仍画、颜色回落单色**；界面律动闸（`motion_rhythm_enabled`）**不影响**它（它不读 `pulse()`/`level()`） | [probe-perf-tier.md](probe-perf-tier.md)、`BandColoringGatingTest` |
| 5 | **性能预估** | 结构上：**1 笔填充 + ≤28 峰值条 + ≤28 光点**（27 段 `cubicTo`）对比 v3.2.1 的 28 笔圆角矩形 + 峰值 + 光点；控制点计算实测 **904ns / 27 段**（JVM）。真机 release 帧时间见 §4 | [probe-curve-smoothing.md](probe-curve-smoothing.md) §3、[probe-perf-tier.md](probe-perf-tier.md) |
| 6 | **异常隔离** | 音频线程既有隔离**未动**；**组合期**的 HCT 三角色计算自带 try/catch（三色合一退路）；**draw 期**一帧绘制由 `isolateFrame` 包住 ⇒ 丢这一帧、不向上抛、不重试。频段数据不可用时权重不可信 ⇒ **退回单一 RMS 色** | [probe-waveform-render.md](probe-waveform-render.md) §7、[probe-audio-features.md](probe-audio-features.md) §6 |

## 1. 探针索引

| 分项 | 文档 | 核心产出 |
|---|---|---|
| §2.1 波形渲染层现状 | [probe-waveform-render.md](probe-waveform-render.md) | 纯 Canvas 无 Path；28 柱 / 256 环形缓冲；柱高 `max(1dp, sqrt(柱)×H)`；**单色**（不读 HCT 角色）；**渲染侧零隔离**（这是本版要补的洞） |
| §2.2 音频特征数据源 | [probe-audio-features.md](probe-audio-features.md) | `low/mid/high` 为 0..1 归一化幅度、一阶低通 150Hz/2kHz、**每缓冲一次**（实测 100ms / 11.0~11.5 Hz）；频带值**完全不平滑**；降级路径没读 `available`（既有缺口） |
| §2.3 主导频段定义 | [probe-dominant-band.md](probe-dominant-band.md) + `probe/dominant-band-*.{md,csv}` | 4 方案 × 5 首歌实测；**a 与 b 数学恒等**；裸 argmax 1.258 次/秒（23ms 缓冲 4.260）；选型 d |
| §2.4 颜色切换方式 | [probe-color-transition.md](probe-color-transition.md) + `probe/color-transition-*` | 0/40/80/100/120/400ms 扫描；smoothstep **被实测否决**；过渡被打断会 pop（已修）；HCT 三角色 ΔE 实测 |
| §2.5 曲线平滑算法 | [probe-curve-smoothing.md](probe-curve-smoothing.md) + `probe/curve-smoothing-measurements.md` | 4 算法在 19055 帧真实波形上的过冲/负高度；选 PCHIP |
| §2.6 性能与档位适配 | [probe-perf-tier.md](probe-perf-tier.md) | 结构成本对照 + 真机 release 帧时间（v3.2.1 vs v3.2.2） |

## 2. 探针直接改变的三处实现（先说结论，细节在分项文档）

1. **裸 argmax 上色被否决**（§2.3）：切换频率 1.258 次/秒、闪断 5 次 —— 必须先平滑再判定。
   现代设备缓冲更细时是 **4.260 次/秒**，只按 S6 的粒度选型会在新机上闪。
2. **smoothstep 缓动被实测否决**（§2.4）：直觉是"起步太快"，实测 80ms 档每帧最大色差
   **8.13 → 9.33 更差**（缓动把斜率峰值搬到了感知变化最快的中段）。改用**加长过渡**这条真手段。
3. **过渡期间允许再切换会制造 pop**（§2.4）：实测那一次跳变 ΔE 8.13。
   加「过渡期间不接受新切换」之后，打断次数 128 → **0**，代价为零（最短驻留 300ms ≫ 100ms）。

## 3. 本版**没有**新增设置项（为什么）

主导频段着色挂的是**既有**能力位 `waveBandMode`（由 `motion_wave_bands` 开关 + 档位 ≥ 精致决定），
所以：**零新键、零新迁移、零新文案**。依据：

- 它本身就是 v3.0.0「多频段波形调制」的新表达（逐柱 tint → 整条曲线一个色），再加开关会变成
  「两个开关管同一件事」；
- 仓库纪律「能不加字段就不加」（v1.9.3 固化：显示偏好不进缓存表）；且没有新键 ⇒ 没有 2^N 组合要上真机取证
  （v2.8.0 的教训：细粒度开关组合爆炸无法取证）；
- 这条决定是**可执行的**：`BandColoringGatingTest` 逐格断言谁能开谁不能开，
  并有一条「键集合与迁移水位都不许变」的守卫测试。

## 4. 性能（release 包，S6 / Android 7.0 / 1440×2560 横屏大屏 + 播放中 + 波形挂载）

见 [probe-perf-tier.md](probe-perf-tier.md) 的完整表与采样口径（每配置 3 次 30 秒窗口，
`dumpsys gfxinfo`；单次采样噪声带 ±15%，所以只比中位数）。

**结构对照（推断，非实测）**：v3.2.1 每帧 28 笔 `drawRoundRect` + ≤28 峰值条 + ≤28 光点；
v3.2.2 每帧 **1 笔 `drawPath` 填充**（27 段 `cubicTo`）+ ≤28 峰值条 + ≤28 光点。
曲线路径是**跨帧复用**的同一个 `Path` 对象（`reset()` 后重写），控制点写进 `remember` 的数组
⇒ 帧路径零分配。

## 5. 遗留风险与未验证项（如实列出）

| 项 | 状态 |
|---|---|
| **PCL110（Android 16）真机** | **未验证** —— 本轮该设备不在线（`adb devices` 只有 S6 与 API 33 模拟器）。过渡/滞回对更细缓冲的鲁棒性有 23ms 的对照数据（§2.3）支撑，但没有该机型的观感与帧时间 |
| **主题色对比度不足的 5 个样本** | 探针实测：素白主题 + 浅色底对比度 **1.12**（今天单色波形同样看不见）、云杉浅 2.31、琥珀浅 1.92 等。**这是主题色本身的性质，不是本版引入的**；本版不做静默修正（会超出任务范围），如实留档 |
| **曲线在三档下的观感** | 真机截图见 `verification/`；"简洁档只画单色曲线"是设计决定，没有 A/B 用户数据 |
| **`effects.rounded` 能力位** | 曲线上**没有角可圆**，该位不再参与曲线渲染（保留以稳定 API 与档位表）。三档下它本来就是常量 `true` |
| **长按/点按交互（C 档）** | 未改：仍是 `detectTapGestures` 切换着色模式；曲线化后它的语义（渐变流动 ↔ 实时序淡出）保持不变 |
