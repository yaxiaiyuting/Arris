# v3.0.0 调研结论与推荐技术路线

> **性质**：本文是 **v3.0.0 调研报告的收口件**，不重写任何一份报告，只做三件事：
> ① 把五份报告里**可直接落地**与**必须改造**的结论各列一张表；② 给出**不可用清单**与**已落地的技术路线**；
> ③ 直接回答任务书的两条硬规则，并把五份报告里的「未核实」集中登记。
>
> **输入（只读，不改写）**：
> [neriplayer.md](neriplayer.md)（下称 **R-A**）、
> [audio-feature-extraction.md](audio-feature-extraction.md)（下称 **R-B**）、
> [compose-particle-systems.md](compose-particle-systems.md)（下称 **R-C**）、
> [runtime-shader.md](runtime-shader.md)（下称 **R-D**）、
> [other-players.md](other-players.md)（下称 **R-E** —— 本文首轮撰写时它**尚未落盘**，现已存在，见 §7 第 1 条）。
>
> ✅ **R-E 的结论（均为「转述自 other-players.md」，本文未逐仓库复核）**：覆盖 **Metrolist / InnerTune / ViMusic /
> Auxio / Vanilla Music / SimpMusic / Gramophone** 七个仓库；对七个仓库做**全源码 grep**
> （`audiofx.Visualizer|getFft|getWaveForm|setDataCaptureListener`）**零命中** —— 该集合里**没有任何 Android 音乐播放器
> 使用 `Visualizer` API**；唯一找到的真 FFT 是 Metrolist 的 Shazam 式音频指纹（`ShazamSignatureGenerator.kt`，
> 2048 点 / 16 kHz / Hann），服务的是**识别**而非视觉；七个仓库的 LICENSE 均为 **GPL-3.0**。
> 这三条与 §4 第 2 条（`Visualizer` 不可用）方向一致，是本版「不用 `Visualizer`」的**旁证**，不是新证据来源。
>
> **实现侧只读**：
> [AudioFeatureExtractor.kt](app/src/main/java/com/takahashirinta/ncrust/player/AudioFeatureExtractor.kt)、
> [MotionBindings.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionBindings.kt)、
> [MotionEffects.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionEffects.kt)，
> 以及为核对落点而读取的 [MotionClock.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionClock.kt)、
> [MotionEnvelope.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionEnvelope.kt)、
> [MotionBackdrop.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionBackdrop.kt)、
> [AudioVisualizer.kt](app/src/main/java/com/takahashirinta/ncrust/ui/player/AudioVisualizer.kt)。
>
> **口径**：凡「已测量」只出现在源报告/探针明确写了测量的地方（本文唯一一处真机数字在 §5.1，逐字引自探针产出）；
> 凡没测的，一律标「未核实」。**行号以本文件定稿时的源文件为准**（`AudioFeatureExtractor.kt` 在本文两次撰写之间
> 由 660 行增至 767 行，全部引用已按新版本重核）。

---

## 1. 一句话结论

**维持「不做真 FFT」：调研证明了「数据源可靠」（Ncrust 已经在用自家 `TeeAudioProcessor` 解出的 PCM），真机实测也补上了原本最缺的那条 —— S6 上音频旁路的缓冲粒度是 **100 ms / 11 Hz**、现有特征链每缓冲 574.19 µs（**162×** 实时余量，见 §5.1）；但这些数字测的是**现有两低通链**，**不是 FFT** —— 分帧 / 窗 / PCM 环的开销仍未测量，「性能可控」对 FFT 而言依然不成立。**

**v3.0.0 落地的是一条不新增遍历、不新增缓冲、不新增分配的路：音频线程上**一次逐样本遍历**里跑**两个一阶低通（150 Hz / 2 kHz）+ 每声道独立状态**，产出 `rms/low/mid/high/质心/瞬态`；因为实测出 100 ms 的缓冲粒度，瞬态判据在**缓冲内部每 10 ms 结算一次**（`SUB_FRAME_MS`），产出瞬态**个数**而不是 bool；这些量经 **volatile 标量**发布给 UI，由 `MotionBindings` 翻译成视觉参数，再交给**唯一的 `MotionClock` 帧循环**推进**定长 SoA 池**。**

依据：[R-B §7 R0/R1](audio-feature-extraction.md)、[R-B §1.7](audio-feature-extraction.md)、[R-A §8.1](neriplayer.md)、[R-C §3.1–3.2](compose-particle-systems.md)、[R-D §6 R3](runtime-shader.md)。

---

## 2. 可直接借鉴的实现

> 判定标准：**外部依据成立 + 不触碰 Ncrust 的四条硬约束（minSdk 24 / S6 验收机 / GPU 零重组 / 音频线程零分配 / GPLv3）**。

| 来源 | 借鉴的是什么 | 落到了 Ncrust 的哪个文件 | 为什么可行 |
|---|---|---|---|
| R-A §1.1 / §8.1 —— <https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/engine/ReactiveRenderersFactory.kt#L131> | 用 Media3 **`TeeAudioProcessor` 从自家 ExoPlayer 的 PCM 管线分流**，而不是麦克风 / `Visualizer` | `app/src/main/java/com/takahashirinta/ncrust/player/TransparentWaveformSink.kt`（v2.2.1 起既有，v3.0.0 未改这条通路） | 数据源是**自家解码后、送进 AudioTrack 之前**的 PCM，不是估算；media3 官方契约见 <https://developer.android.com/reference/androidx/media3/exoplayer/audio/TeeAudioProcessor.AudioBufferSink> |
| R-A §8.2(1) —— <https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L133-L150> | **自适应噪声地板 + 不应期**的 onset 检测器结构（clean-room 重写，不搬代码） | `AudioFeatureExtractor.kt:504-557`（`updateTransient`） | 全是标量算术，每个 10 ms 子帧一次、常数时间（见 §5.1(b)）；报告称其「参数有物理含义、有单元测试」，并明确建议**原样重写而非复制**（R-A §8.2 第 1 条） |
| R-A §8.2(3) —— <https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L324-L349> | **峰值保持**解决「音频线程高频产出 vs UI 帧低频消费」的采样丢失 | `AudioVisualizer.kt:261-268`（`onTransient` 单调计数）+ `MotionBindings.kt:109-144`（按差值取本帧次数） | 用「单调计数 + 差值」替代队列 ⇒ 零分配、有界（`MAX_TRANSIENTS_PER_FRAME`），且不会像 bool 那样丢掉同一帧里的多次击打（`MotionBindings.kt:107-143`） |
| R-A §8.2(2) —— `docs/verification/v3.0.0/ref-research/neriplayer.md`（§2.2 / §3.2–3.3） | **两层非对称 one-pole 平滑**（起音快 / 回落慢） | `MotionEnvelope.kt:202-210`（`ATTACK_TAU_MS = 90` / `RELEASE_TAU_MS = 260`） | 报告点名的常见 bug 是「只有一层平滑 ⇒ 要么抖要么拖」；两层结构的时间常数可单测钉住 |
| R-A §8.2(7) —— <https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L106-L124> | **dirty flag**：没有变化就不上传 / 不失效 | `MotionClock.kt:132`（`if (changed) generationState.intValue++`） | 与本 fork 的 GPU 零重组一致：帧循环只在真有变化时递增 `generation`，draw 层读它失效，**不写任何组合阶段被读的 state** |
| R-A §8.2(8)(9) —— <https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/assets/shaders/hyper_background_effect.glsl#L105-L107> | ① **不把 alpha 绑到音频**（避免整屏闪烁）；② 允许「音频门控的周期载波」、禁止「振幅与音频无关的周期载波」 | `MotionBackdrop.kt:321-324`（`PARTICLE_MAX_ALPHA = 0.5f` 为常量）+ `MotionBindings.kt:196-214`（粒子速率与中高频能量**正相关**、低于门槛恒为 0） | 报告为这条给了可机器验证的验收判据：「静音输入下视觉输出必须收敛到静止」（R-A §8.2 第 9 条），本仓库已在 §6(b) 落地 |
| R-B §1.2 / R6 —— <https://www.earlevel.com/main/2012/12/15/a-one-pole-filter/>、<https://dsp.stackexchange.com/questions/54086> | 一阶低通系数 **`a = 1 − exp(−2π·fc/fs)`**（不采用 `dt/(RC+dt)` 形式） | `AudioFeatureExtractor.kt:710`（`lowPassCoefficient`） | 引文级公式；结果 clamp 到 `[0,1]`，`fs<=0` 返回 1.0 ⇒ 不产生非法系数 |
| R-B §1.3 —— <https://dsp.stackexchange.com/questions/21903>、<https://dsp.stackexchange.com/questions/3182> | **带通 = 两个低通之差；高通 = `x − 低通`** | `AudioFeatureExtractor.kt:370-372`（`m = sMid − sLow`、`h = v − sMid`） | 不需要额外滤波器实例，每样本仍是常数次乘加；代价（一阶只有 −6 dB/oct、泄漏大）已由 R-B §1.6 的数值表公开 |
| R-B §7 R3 —— <https://dafx.de/paper-archive/2006/papers/p_133.pdf>（Dixon DAFx-06） | **自适应阈值 = 帧能量的一阶低通**（Dixon 的 `gα`），配合绝对门槛与相对门槛 | `AudioFeatureExtractor.kt:419-469`（`baselineState` + `ONSET_ABS_MIN` / `ONSET_REL_FACTOR`） | 报告的「先算 jump 再更新基线」顺序是契约：否则一次强瞬态会立刻抬高基线、把自己判掉（`AudioFeatureExtractor.kt:523-525` 的注释） |
| R-B §3.5 / §7 R1「必改项 3」 —— <https://librosa.org/doc/latest/api/generated/librosa.util.peak_pick.html> | **基线系数按本次缓冲的真实 `dt` 现算**：`k = 1 − exp(−dt/τ)`，而不是固定步长 | `AudioFeatureExtractor.kt:667`（`baselineCoefficient`）+ `:508` 每个子帧现算 | 缓冲粒度是**运行时行为**（代码里没有常量能回答它多大），固定步长会让时间常数随缓冲时长漂移；一次 `exp`／缓冲的代价可忽略 |
| R-B §1.7 / §7 R1「必改项 1」 —— `audio-feature-extraction.md`（§1.7 数值表） | **每声道一份滤波状态**（交错 PCM 的相邻样本属于不同声道） | `AudioFeatureExtractor.kt:179`（`filterState = Array(MAX_CHANNELS) { DoubleArray(2) }`）+ `:347-377` 的声道取模 | 共用一个状态会让**有效截止频率乘以声道数**（立体声 300 Hz / 6 声道 901 Hz，R-B §1.7 表）；修法**不增加每样本成本**；上限 8 把状态内存钉死在 128 B |
| R-C §1.2 / §1.3 —— `compose-particle-systems.md` | **SoA（并行 `FloatArray`）+ 定长容量 + 有界生成**，不用 `List<Particle>` 数据类 | `MotionEnvelope.kt:262-284`（`MotionBackdropState`：`particleX/Y/Vx/Vy/Life` 五条并行数组） | 容量在构造时定死（`PARTICLE_CAPACITY = 40`），关掉开关时 `fill` 清空并归零累加器（`MotionEnvelope.kt:304-317`） |
| R-C §2.1 —— `compose-particle-systems.md` | 单粒子用 **`DrawScope.drawCircle`**（形参经 value class 脱糖后全是原始类型） | `MotionBackdrop.kt:202`、`:219`、`:232` | 报告称 `Color`/`Offset` 是 value class、脱糖成 `long` ⇒ **零装箱零分配**（R-C §2.1 实测） |
| R-C §2.2 —— `compose-particle-systems.md` | **径向渐变 `Brush` 必须缓存**，绝不放进每帧 draw lambda | `MotionBackdrop.kt:263-286`（`RadialGradientCache`，`remember` 在 `:180`），调用点 `:177` 的注释 | 报告指出 `Brush.radialGradient(...)` 每次调用会新建 `Brush` + `List<Color>` + native `SkShader`；本实现按半径缓存、靠**缩放画布**画任意半径 |
| R-C §3.1–3.2 —— `compose-particle-systems.md` | **`withFrameNanos` 只用来推进相位**，它的 delta 不能当帧时长用；真实帧时长用 `FrameMetrics`（API 24，正好等于 minSdk） | `MotionClock.kt:195-218`（帧循环 `withFrameNanos` + `coerceIn(1f, 100f)`）；既有 `VisualizerFrameMonitor` | 与 minSdk 24 相容；报告明确「`withFrameNanos` 的 delta 可能是目标帧时间而非现在」（R-C §3.2） |
| R-C §6.1 / §6.2 —— <https://github.com/DanielMartinus/Konfetti>、<https://github.com/CuriousNikhil/compose-particle-system> | 只作**结构参考**（配置模型 + `dt` 驱动契约），**不引入依赖** | `app/build.gradle.kts`（未新增任何动画/粒子依赖；全文件仅 `material-icons-extended` 一处 material 制品） | 报告明确「Konfetti / Quarks 都只是结构参考，不加入 `app/build.gradle.kts`」（R-C §R6）⇒ 无 GPLv3 兼容性争议 |
| R-D §5 —— `runtime-shader.md` | API 24 上**真正可用**的替代：预模糊位图（先降采样再模糊）、`Brush.linearGradient/radialGradient`、`BitmapShader` | `ui/player/motion/CoverBlur.kt` + `CoverBlurCache`（每首歌只算一次）；渐变见 `MotionBackdrop.kt` | R-D 实测 `api-versions.xml`：`LinearGradient`/`RadialGradient`/`BitmapShader` **自 API 1**；报告称预模糊位图是「没有 RenderEffect 也能有模糊」的正确答案（R-D §5） |

---

## 3. 需要适配 Ncrust 架构的

> 每条都写清**改了什么**与**为什么非改不可**。四类约束：minSdk 24 / Galaxy S6 验收机 / GPU 零重组 / 音频线程零分配 / GPLv3。

| 来源 | 借鉴的是什么 | 落到了哪个文件 | 必须改什么、为什么 |
|---|---|---|---|
| R-A §4.1–4.4、§8.2(13) —— <https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L152-L156>（API < 33 时 `painter = null`） | `RuntimeShader` / AGSL 音频反应背景；API < 33 时 `painter = null`（**什么都不显示**） | `MotionBackdrop.kt`（Canvas 渐变 + 预模糊位图 + `drawCircle`）；档位表在 `MotionEffects.kt:229-368` | **改**：不做 AGSL，也不做「什么都不显示」。**为什么**：Ncrust minSdk 24，覆盖面比 NeriPlayer 的 28 更大（R-A §8.2 第 13 条），且 `RuntimeShader` **类自 API 33 起才存在、无任何回落**（R-D §1/§6 R3）⇒ API 24–32 必须有画面 |
| R-A §2.2–2.3 —— `neriplayer.md` | `level` / `beat` 通过 **`StateFlow` → 主线程 collect** 交付 | `AudioVisualizer.kt:261-281`（`@Volatile` 标量 + 取值函数）、发布侧 `TransparentWaveformSink.kt:150` | **改**：改成**预分配标量 + volatile 写**，瞬态用「先写强度、**最后写**计数」的顺序（`AudioVisualizer.kt:261-267`）。**为什么**：音频线程零分配是铁律（R-B §3.7）；`StateFlow` 的写入路径与本约束冲突，而 volatile 标量不分配 |
| R-A §2.2、§8.1、§8.2(14) —— `neriplayer.md` | 只有 `level`（RMS）一个标量，**明确没有频段**；报告自己说「若需要频段差异化视觉，NeriPlayer 无法作为参考」 | `AudioFeatureExtractor.kt:100-166`（`rms/low/mid/high/spectralCentroid/transientCount`） | **改**：在同一遍遍历里多算一个低通 + 三条平方累加。**为什么**：v3.0.0 的产品需求就是「低频/中频/高频差异化」；R-A 已证明它给不了这个，只能自己扩，且必须**不新增遍历**（R-B §7 R0） |
| R-A §6.4(1) —— <https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L25> | `BgEffectPainter.java`（及其可能同源的 `hyper_background_effect.glsl`） | **不搬运**；AGSL 相关一律不进入本仓库 | **改**：不做任何形式的移植。**为什么**：该文件头署名 `https://github.com/ReChronoRain/HyperCeiler`，而 HyperCeiler 的 LICENSE 是 **AGPL-3.0**（R-A §6.4 已抓取核实）；AGPL 与 GPLv3 的项目合并会产生许可冲突，报告的可执行建议是**独立重写 + 正式法律审查**（R-A §6.4） |
| R-A §0、§8.1 —— <https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/policy/offload/PlaybackAudioOffloadPolicy.kt#L32> | 「开启音频反应 ⇒ 强制 `AUDIO_OFFLOAD_MODE_DISABLED`」，并建议把它写进设置页文案 | `PlaybackService.kt:294-303`（`logAudioOffloadCapability`，**刻意不启用 offload**） | **改**：**不照抄这条代价，也不把它写成 Ncrust 的取舍**。**为什么**：本仓库的既有结论是 offload 会绕过应用侧音频处理链、与无缝预载策略冲突，且无损 FLAC 走 FFmpeg 软解、本来就不经过 offload（`PlaybackService.kt:300-303`）⇒ 该代价在本仓库不存在 |
| R-C §R1 —— `compose-particle-systems.md` | 新建 `ui/player/motion/ParticlePool.kt`，用 **swap-remove** 删除，内联 **xorshift32**（`rngState: Int`） | 实际落在 `MotionEnvelope.kt:262-284` 的 `MotionBackdropState`（池满时**游标覆盖最旧**，`spawnParticle` 见 `:460-472`；随机数用 `kotlin.random.Random(PARTICLE_RANDOM_SEED)`，`MotionEnvelope.kt:263`） | **改**：① 不新建文件——光晕/冲击波/粒子是**同一层背景状态**，拆开会让 `MotionClock` 多一个消费方；② 删除用覆盖最旧而不是 swap-remove，因为在定长硬上界下它同样 O(1) 且无搬移；③ **PRNG 未按 R1 用 xorshift32**（见 §7 未核实项）。**为什么**：有界 + 零分配是硬约束，报告本身也给了「该选哪种」的取舍空间（R-C §1.4） |
| R-C §R3、§R4 —— `compose-particle-systems.md` | 把粒子档位接进**既有的 `MotionDegrade` 自动降级阶梯**，并参与降级判据 | `MotionEffects.kt:113-142`（`MotionDegrade` 只剩历史常量）；v3.0.0 **整个删除了自动降级**（`MotionEffects.kt:32-55` 的类 KDoc） | **改**：**不接降级阶梯**。**为什么**：v3.0.0 的取舍是「渲染结果必须可推导」——S6 上实测「完全关掉波形」的对照轮同样 95% 超标，说明降级判据不知道是谁把帧顶起来的（`MotionEffects.kt:36-43`）；性能兜底改为**静态初始档位 + 逐项开关 + 每个动效自身的硬上界**。⚠️ 这是**报告与实现的一处真实分歧**，以实现的理由为准，但应在发行说明里写明 |
| R-C §2.3 —— `compose-particle-systems.md` | 想用 `drawPoints` + `PointMode.Points` 画大量粒子 | `MotionBackdrop.kt:202/219/232`，**用 `drawCircle`** | **改**：不使用。**为什么**：形参是 `List<Offset>`（value class 进 List 必然装箱），Android 实现是**逐点** `nativeCanvas.drawPoint()`、没有批处理（R-C §2.3 实测 + 源码） |
| R-D §5 —— `runtime-shader.md` | 想要模糊 → 用 `Modifier.blur` / `BlurMaskFilter` | `CoverBlur.kt` 的预模糊位图路线 | **改**：`Modifier.blur` 在 API < 31 **被静默忽略（no-op）**；`Paint.setMaskFilter()` 在硬件加速画布上官方支持表标 **✗**（R-D §5 与不可用清单 #4/#6）⇒ 只能在**软件位图**上预先把模糊算好 |
| R-C 头注、R-C §R1 —— `compose-particle-systems.md` | 目标环境「**无 `androidx.compose.material3`**」 | 粒子/光晕取色用 `LocalMetroColors.current.onBackground`（`MotionBackdrop.kt:175`）；`app/build.gradle.kts` 未新增 material3 | **改**：不用 material3 的任何组件/色板。**为什么**：本 fork 没有该依赖（R-C 头注把它列为前提条件），Kanesumi 组件才是本仓库的 UI 词表 |
| R-B §4.3 / §7 R0 —— <https://developer.android.com/reference/android/media/audiofx/Visualizer> | 用 `android.media.audiofx.Visualizer` 拿频谱 | 不引入；改用自家 PCM tap（`TransparentWaveformSink.kt`） | **改**：不用。**为什么**：见 §4 不可用清单第 2 条（权限 + 8-bit FFT + 2 的幂捕获 + 与自家 PCM 不是同一份数据） |

---

## 4. 不可用清单（附理由）

| # | 技术 | 为什么不可用 | 依据 |
|---|---|---|---|
| 1 | **`RuntimeShader` / AGSL**（含 `RenderEffect.createRuntimeShaderEffect`） | **API 等级**：类自 **API 33** 起才存在；minSdk 24 上**没有任何回落方案**；本 fork 验收机 S6 = API 24 **跑不到** ⇒ 任何「没掉帧」的结论都无法在验收机上取证 | R-D §1 + §6 R3；不可用清单 #1/#2；实测 `api-versions.xml since="33"` |
| 2 | **`android.media.audiofx.Visualizer`** | **权限 + 精度 + 契约三重不可用**：① 官方要求 **`RECORD_AUDIO`**（本应用清单没有该权限）；② 只给 **8-bit 幅度 FFT**；③ 捕获大小**必须是 2 的幂**（`getCaptureSizeRange()`）；④ 它抓的是 audioflinger 的输出混音/会话，**与自家 tee 的 PCM 不是同一份数据** ⇒ 会让「可视化与音频严格对应」的既有契约失效 | R-B §4.3（官方原文四段，<https://developer.android.com/reference/android/media/audiofx/Visualizer>、<https://developer.android.com/guide/topics/media/playback-capture>）；**旁证**：R-E（转述自 other-players.md）在七个 GPL-3.0 播放器仓库里 grep `audiofx.Visualizer\|getFft\|getWaveForm\|setDataCaptureListener` **零命中** |
| 3 | **本版做真 FFT / STFT / DCT / 滤波器组频谱** | **FFT 自身的性能证据缺失**：R-B 的 R0 把「任何 FFT/STFT/DCT/滤波器组频谱库」列为**不该做**；理由是它要引入分帧/窗/PCM 环/倍率计算，**新增一条必须真机取证的路径**。⚠️ §5.1 的 `PROBE-FEATURE-COST`（**已有**）测的是**现有两低通链**（4096 帧 574.19 µs），**不能**外推成 FFT 的成本 ⇒ 「性能可控」对 FFT 而言仍未被证明 | R-B §7 R0、§6 第 12 条；对照本文 §5.1 |
| 4 | **`drawPoints` + `List<Offset>`** | **每帧分配**：`points` 形参是 `List<Offset>`，`Offset` 是 value class、进 List **必然装箱** ⇒ 每帧 N 次装箱；且 Android 实现是**逐点** `nativeCanvas.drawPoint()`，没有批处理 | R-C §2.3（TL;DR 表 + 源码引用） |
| 5 | **每帧 `Brush.radialGradient` 分配**（写在 `Canvas {}` / `drawBehind` 的 draw lambda 里） | **每帧分配**：每次调用新建 `Brush` + `List<Color>` + 绘制时的 native `SkShader` ⇒ 直接违反稳态 0 B/帧的验收口径 | R-C §2.2、§R4；本仓库的反例修正见 `MotionBackdrop.kt:249-286` |
| 6 | **`Modifier.blur`** | **API 等级**：需要 **API 31**；官方 KDoc 原文写明「on older Android versions **will be ignored**」⇒ 在 API 24–30 上是**静默 no-op**（不报错、只是没效果） | R-D §5 + 不可用清单 #4（<https://raw.githubusercontent.com/androidx/androidx/androidx-main/compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/draw/Blur.kt>） |
| 7 | **`BlurMaskFilter`（`Paint.setMaskFilter`）用在硬件加速画布上** | **静默无效**：类的存在（API 1）与「能不能用」是两件事 —— 官方硬件加速支持表把 `setMaskFilter()` 标为 **✗（不支持）**；Compose 默认硬件加速 ⇒ 不会报错，只会没效果 | R-D §5 + 不可用清单 #6（<https://developer.android.com/guide/topics/graphics/hardware-accel>） |
| 8 | **任何源自 NeriPlayer `BgEffectPainter.java` 的 AGPL-3.0 衍生代码**（含可能同源的 `hyper_background_effect.glsl`） | **许可**：该文件头署名 HyperCeiler，而 HyperCeiler 的 LICENSE 是 **AGPL-3.0**（已抓取核实，34523 字节）。Ncrust 整体以 **GPLv3** 分发，引入 AGPL 代码会产生不可调和的许可冲突 ⇒ 报告的可执行建议是 **clean-room 重写 + 法律审查** | R-A §6.4(1)（<https://github.com/ReChronoRain/HyperCeiler/blob/main/LICENSE>）；本仓库 `AudioFeatureExtractor.kt:1-7` 的 GPLv3 文件头 |
| 9 | **`RenderEffect` / `graphicsLayer { renderEffect = BlurEffect(...) }`** | **API 等级**：实测 `api-versions.xml` **since="31"**；与第 6 条同因 | R-D 不可用清单 #3/#5 |
| 10 | **`drawVertices`（想在 API 24 上自己批处理粒子）** | **API 等级**：硬件加速下 `drawVertices()` 首次支持 **API 29** ⇒ minSdk 24 上行为不可依赖 | R-D 不可用清单 #9（硬件加速支持表） |
| 11 | **在组合阶段写 uniform / 音频特征值**（`mutableStateOf` + composable 里读） | **与现有架构冲突**：会变成每帧重组整棵子树，直接违反本 fork 的「GPU 零重组」硬约束 | R-D 不可用清单 #8（<https://developer.android.com/develop/ui/compose/performance/bestpractices>） |
| 12 | **每帧新建 `RenderEffect`**（NeriPlayer 的做法） | **每帧分配 / 冗余 native 对象**：R-A 明确「Ncrust 应缓存 RenderEffect」，并给出同仓库玻璃链路的正确对照 | R-A §8.2(11) |
| 13 | **用 `RuntimeShader` 做「每粒子效果」** | **语义不符**：`createRuntimeShaderEffect` 是把**一个 RenderNode 的内容**喂给 shader —— 图层级，不是逐粒子级 | R-D 不可用清单 #7 |
| 14 | **`List<Particle>` 数据类（AoS）** | **每帧分配 / 缓存不友好**：报告的结论是 SoA 的 `FloatArray` 胜过 `List<Particle>` 数据类 | R-C TL;DR 表 + §1.2 |
| 15 | **绝对阈值做 onset 判据** | **跨曲目不可用**：绝对门槛在母带压缩强的曲目上永远不达标、在安静曲目上永远达标 | R-B §3.3、§7 R0 |
| 16 | **用「缓冲计数」做冷却或基线** | **与运行时行为耦合**：缓冲粒度是运行时的，**不是常量** —— §5.1 的探针证实 `TeeAudioProcessor` 原样透传解码器的块（S6 上恰好是 100 ms，但代码里没有常量能保证它），用缓冲计数写冷却会让同一份代码在不同设备上有不同的节拍分辨率 | R-B §7 R0/R1；本文 §5.1(a) |

---

## 5. 推荐技术路线（已落地）

### 5.1 真机实测：音频旁路的缓冲粒度是 100ms（这条改变了设计）

> **来源**：`docs/verification/v3.0.0/probe/EVIDENCE.md` —— **S6 / SM-G9209 / Android 7.0** 上
> `connectedDebugAndroidTest` 跑 `AudioTapProbeTest` 探针的产出。以下三行**逐字转录**，未做任何换算或补测。
> ⚠️ **本文定稿时 `EVIDENCE.md` 尚未落盘**（该目录当时只有 `probe-audio-features.md` / `probe-transient.md` /
> `probe-motion-binding.md`）—— 三行数字来自**上游转述**，本文**未能独立复核该文件本身**；落盘后应回填核对。

```
PROBE-AUDIO-TAP label=44100-2ch actual=44100Hz/2ch/enc=2 callbacks=44 avgFrames=4410.0 avgBufferMs=100.00 callbackRateHz=11.0 hist={[4352..4607]=44}
PROBE-AUDIO-TAP label=48000-2ch actual=48000Hz/2ch/enc=2 callbacks=45 avgFrames=4800.0 avgBufferMs=100.00 callbackRateHz=11.0 hist={[4608..4863]=45}
PROBE-FEATURE-COST frames=4096 ch=2 perBufferUs=574.19 audioMsPerBuffer=92.88 realtimeFactor=162x iterations=4000
```

**读法**：两种采样率下每回调都是 **4410 / 4800 帧 = 100.00 ms**（直方图只有一个桶 ⇒ 粒度高度稳定），
回调率 **11.0 Hz**；特征提取每 4096 帧缓冲耗时 **574.19 µs**，而这段音频本身长 92.88 ms ⇒
**实时余量 162×**（`iterations=4000`）。

#### 三个后果（这才是这条测量真正改变的东西）

**(a) 缓冲粒度是运行时属性，不是常量 —— 所以时间基准必须从样本数反算。**
`TeeAudioProcessor` **确实原样透传解码器的块**，代码里没有任何常量能回答「一次回调多少帧」。
⇒ v3.0.0 的每一个时间量都由 `frames ÷ channels ÷ sampleRate` 现算，而**不是**假设一个缓冲大小：
`frameMs`（`AudioFeatureExtractor.kt:451-453`）、基线系数 `k = 1 − exp(−dt/τ)`（`:667`）、
峰值记忆衰减 `e^(−dt/τ)`、以及冷却按毫秒递减 —— 全部使用真实 `dt`。

**(b) 「每缓冲一次判定」会把 10 ms 的击打稀释约 10× —— 所以判据在缓冲内部每 10 ms 结算一次。**
一个 10 ms 的瞬态落进 100 ms 的窗口，能量被摊薄约 **10 倍**（RMS 约 √10 ≈ **3.2 倍**），
安静的击打会被直接摊到门槛之下而**永远不被判定**。⇒ 判据的结算周期从「一个缓冲」细化为
`AudioFeatureExtractor.SUB_FRAME_MS = 10.0`（`:654`）：逐样本累加**不变**（仍然只有一次遍历、零分配），
只是每 10 ms 结算一次瞬态判据（`finalizeSubFrame()`，`:468-480`；调用点 `:386`、`:429`），
缓冲末尾那个**不完整**的子帧也会结算（`:455-459`，否则最后 10 ms 恰好是击打落点时会漏）。
时间分辨率因此从 **11 Hz 提到约 100 Hz**；代价是**每样本多两次加法**。
推论：一次缓冲里可能有**多次**击打 ⇒ 输出是 `transientCount: Int`（上限 `MAX_TRANSIENTS_PER_BUFFER = 64`，`:657`）
而不是一个 bool，视觉幅度取**最强的那一次**（`:548-557`）。

**(c) 诚实的剩余限制：特征仍然是「每缓冲发布一次」。**
细化的是**判据**，不是**发布**：`rms/low/mid/high/质心` 以及瞬态计数依然是每个缓冲结束时写一次
volatile 标量。所以在最坏情况下，一次击打的**视觉反应可以比声音晚最多约 100 ms，再加上一帧 UI**
（约 16–33 ms）。这是**数据通路的属性，不是检测器的属性** —— 检测器已经在缓冲内部看见了那一击，
但消费方要等到这个缓冲结束才能读到。

### 5.2 整条链路（每一跳都可追到代码）

```
PCM（自家 TeeAudioProcessor 分流）
   │  TransparentWaveformSink.handleBuffer
   ▼
AudioFeatureExtractor.process(view, encoding)        ← 音频线程，**一次**逐样本遍历，零分配
   │   · sumFull += v·v
   │   · sLow[c] += aLow·(v − sLow[c])               aLow  = 1 − exp(−2π·150/fs)
   │   · sMid[c] += aMid·(v − sMid[c])               aMid  = 1 − exp(−2π·2000/fs)
   │   · sumLow += sLow²  sumMid += (sMid−sLow)²  sumHigh += (v−sMid)²
   │   ┆ 每 SUB_FRAME_MS = 10ms 结算一次瞬态判据（缓冲内，逐样本累加不变）
   │   ⇒ rms / low / mid / high / centroid / transientCount / transientStrength / frameMs
   ▼
WaveformStore 的 @Volatile 标量（预分配，无装箱、无 StateFlow；**每缓冲发布一次**）
   ▼
MotionBindings.update(...)                            ← 每帧一次，把特征翻成视觉参数
   │   midHigh / brightness / transients（按计数差值）/ strength
   ▼
MotionClock.frame(active, dtMs, waveform, motion)     ← **唯一的帧循环**
   ├─▶ MotionEnvelope        ：响度包络 + 节拍脉冲
   └─▶ MotionBackdropState   ：冲击波 / 光晕 / 粒子（**定长 SoA 池**）
   ▼
generationState++ （仅在 changed 时） → draw 层失效 → 画
```

代码依据：[`AudioFeatureExtractor.kt:330-461`](app/src/main/java/com/takahashirinta/ncrust/player/AudioFeatureExtractor.kt)（`process`）、
[`AudioFeatureExtractor.kt:468-480`](app/src/main/java/com/takahashirinta/ncrust/player/AudioFeatureExtractor.kt)（`finalizeSubFrame`）、
[`AudioVisualizer.kt:261-281`](app/src/main/java/com/takahashirinta/ncrust/ui/player/AudioVisualizer.kt)、
[`MotionBindings.kt:109-144`](app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionBindings.kt)、
[`MotionClock.kt:95-135`](app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionClock.kt)、
[`MotionEnvelope.kt:262-284`](app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionEnvelope.kt)。

### 5.3 逐条对应任务书要求

| 要求 | 落地形态 | 证据 |
|---|---|---|
| **两个一阶低通（150 Hz / 2 kHz）** | `LOW_CUTOFF_HZ = PcmRms.BASS_CUTOFF_HZ`（**引用而非复制**）、`MID_HIGH_CUTOFF_HZ = 2000.0` | `AudioFeatureExtractor.kt:572`、`:583` |
| **只有一次逐样本遍历** | 全带 RMS、两个低通、三条平方累加在**同一个 `while` 循环体**里完成；没有第二次扫描、没有分帧、没有窗函数、没有 PCM 环形缓冲 | `AudioFeatureExtractor.kt:355-436`（文件头 KDoc `:16-27` 明确「两条都不越」） |
| **每声道状态** | `filterState = Array(MAX_CHANNELS) { DoubleArray(2) }`，声道索引按交错样本自增取模；上限 8 把内存钉在 128 B | `AudioFeatureExtractor.kt:179`、`:347-377`、`:635` |
| **音频线程零分配** | 全部状态构造时分配一次；`process` 里没有装箱/lambda/字符串/集合/IO/锁；输出是**对象自身字段** | `AudioFeatureExtractor.kt:56-62`、`:168-226` |
| **volatile 标量发布** | 音频线程写、UI 线程读的都是 `@Volatile` 标量；瞬态**先写强度、最后写计数** | `AudioVisualizer.kt:261-267`、`TransparentWaveformSink.kt:150` |
| **`MotionBindings` 映射特征 → 视觉参数** | 绑定表在类 KDoc：冲击波/光晕 ← 瞬态；粒子 ← 中频+高频；波形调制 ← 三频带；背景呼吸 ← 全带 RMS | `MotionBindings.kt:30-38`、`:196-214`、`:224-241` |
| **定长 SoA 池 + 单一 `MotionClock` 帧循环** | `MotionBackdropState` 的五条并行 `FloatArray`；推进**只在** `MotionClock.frame` 里，**不放进 draw lambda** | `MotionEnvelope.kt:276-281`、`MotionClock.kt:95-135`；R-C §R3 的硬约束 |
| **关掉开关 = 零开销 / 有界** | 能力位关掉时立刻清池并归零累加器；每帧新粒子 ≤ 4、瞬态 ≤ 4 次 | `MotionEnvelope.kt:304-317`、`MotionBindings.kt:169-176` |

### 5.4 诚实边界（一条都不缩小）

1. **频带之间泄漏很大** —— 一阶只有 **−6 dB/oct**。R-B §1.6 的数值表：1 kHz 的 mid = −1.74 dB 而 high = −8.26 dB（**中/高之间只有 6.5 dB 对比**）；60 Hz 的 mid = −9.29 dB（**约 34% 幅度的低频能量渗进中带**）。所以两个边界是「**重心**」不是「分界」（`AudioFeatureExtractor.kt:575-583`）。
2. **它给的不是频谱，而是三个带宽很宽的包络** —— 报告的原话是「不是滤波器组频谱」；**不得**把三带能量当频谱展示、**不得**把 `centroid01` 当 Hz 报出（R-B §7 R0；`AudioFeatureExtractor.kt:22-26`、`:122-125`）。
3. **底鼓与贝斯的分离是粗的** —— 「底鼓与贝斯仍然不可区分」这条 v2.9.0 的边界**一条都没变**（`AudioFeatureExtractor.kt:22-23`）。v3.0.0 只修掉了「有效截止频率被声道数放大」这个**真缺陷**（立体声下 150 Hz 曾是 300 Hz，R-B §1.7 表），并没有让低频带变得能分辨乐器。
4. **三带能量和 ≠ 全带能量** —— 60 Hz–6 kHz 之间差 0.1–1.2 dB 且偏差随频率变化 ⇒ **不能拿三带做归一化基准**，要用全带时就用 `sumFull`（R-B §1.6）。
5. **瞬态判据的常数是「推荐默认值」，不是文献值** —— `ABS_MIN`/`REL_FACTOR`/`FLOOR_*`/`STRENGTH_FULL` 在 R-B §6 第 7 条被明确登记为「**未在任何一手文献里核到**」；`COOLDOWN_MS = 90` 是「先试」的建议值，需按真机观感在 60–180 间定（R-B §7 R3、`AudioFeatureExtractor.kt:625`）。
6. **性能数字：只有 §5.1 那三条是真机测量，其余仍是估算** —— 「+9 flop/样本」「44.1kHz 立体声 ≈ 1.5 Mflop/s」「6 声道 ≈ 4.5 Mflop/s」出自 R-B §5 的**算式**（`AudioFeatureExtractor.kt:38-39`），R-B §6 第 12 条当时也明确「每缓冲一次 `exp` 的实测开销：**未测量**」；§5.1 的 `PROBE-FEATURE-COST` 补上了「每缓冲耗时」这一项，但 **R-B §7 R5 要求的 perfetto「特征开/关」两轮对照（帧 P90 Δ ≤ 1.0 ms、janky% Δ ≤ 2pp）仍未做**。
7. **§5.1(c) 的发布延迟（最多约 100 ms + 一帧）未做端到端实测** —— 它是从「100 ms 缓冲粒度 + 每缓冲发布一次」推出来的**结构性上限**，不是测出来的音画同步延迟；R-A §7 第 6 条对 NeriPlayer 的同项也仍是「未核实」。

---

## 6. 对两条硬规则的直接回答

### (a)「不做真 FFT 的翻案，除非调研证明数据源可靠且性能可控」

**问题拆成两半，两半的答案不一样：**

| 条件 | 调研是否证明 | 依据 |
|---|---|---|
| **数据源可靠** | ✅ **已证明**（Ncrust 侧本就成立） | R-A §8.1：`TeeAudioProcessor` 拿到的是**自家解码、经处理后、送进 AudioTrack 之前**的 PCM，比 `Visualizer`（需录音权限、有系统级延迟、部分机型不可用）与麦克风回采都强。Ncrust 自 v2.2.1 起就走这条路（`TransparentWaveformSink.kt`） |
| **性能可控** | ❌ **仍未证明**（但缺口缩小了一条） | ① R-B §7 R0 把「任何 FFT/STFT/DCT/滤波器组频谱库」列为**不该做**；② R-B §6 第 1、2 条当时说本工作区**没有任何真机实测数据**、**缓冲粒度未测量** —— 这一条**已被 §5.1 的 `PROBE-AUDIO-TAP` 补上**（100 ms / 11 Hz）；③ R-B §6 第 12 条「每缓冲一次 `exp` 的开销未测」也**已被 §5.1 的 `PROBE-FEATURE-COST` 覆盖**（4096 帧 574.19 µs，162× 实时余量）。**但这些数字测的是「现有两低通特征链」，不是 FFT**：分帧 / 窗 / PCM 环 / 倍率计算的开销，以及 FFT 与「段内每 10 ms 结算」如何共存，**仍未测量** |
| 旁证①：业界是否有人用 `Visualizer` | ✅ **没有** | R-E（**转述自 other-players.md**）：对 Metrolist / InnerTune / ViMusic / Auxio / Vanilla Music / SimpMusic / Gramophone 七个 GPL-3.0 仓库做全源码 grep（`audiofx.Visualizer|getFft|getWaveForm|setDataCaptureListener`）**零命中**；唯一真 FFT 是 Metrolist 的 Shazam 式指纹（2048 点 / 16 kHz / Hann），服务**识别**而非视觉。与 §4 第 2 条同向 |
| 旁证②：不做 FFT 能不能撑起视觉 | ✅ **能，但有上限** | R-A §8.1：NeriPlayer 853 个源文件里**一行 FFT 都没有**，靠 `level` + `beat` 两个标量做出完整视觉。但 R-A §8.2(14) 同时说明：**没有 FFT 就没有「频段差异化的视觉」**，若产品需要「低频驱动粒子/高频驱动亮度」，NeriPlayer 不能作为参考 |

**判决：仍不翻案。** v3.0.0 **不引入真 FFT**，也不引入任何 STFT/DCT/滤波器组频谱库。
替代路线是 R-B §7 R1 的**两个一阶低通 + 「低通之差」构造三带**（§5 已落地），它在**同一次遍历**内完成、不新增缓冲、不新增分配；
并且因为 §5.1 实测出 100 ms 的缓冲粒度，判据进一步细化到**缓冲内每 10 ms 结算一次**（`SUB_FRAME_MS`）。
**翻案的前置条件（写死在这里，防止下一版凭感觉翻）**：
① ~~跑掉 `AudioTapProbeTest` 拿到真机缓冲粒度~~ —— **已完成**（§5.1，R-B §7 R5 第 1 条的要求已满足）；
② 用 perfetto 跑「特征开/关」两轮，证明播放线程无分配性 GC、单缓冲耗时增量在噪声带内（仓库既有口径：帧 P90 Δ ≤ 1.0 ms、janky% Δ ≤ 2pp，见 R-B §7 R5 第 2 条）—— **仍未做**；
③ 明确一个**必须靠频段才能做**的产品需求 —— 否则 R-B §7 R0 成立；
④ **新增**：给出 FFT 版本自己的 `PROBE-FEATURE-COST` 同类测量（含分帧与 PCM 环），且证明它与每 10 ms 的子帧结算能共存 —— 否则「性能可控」依然只是推断。
⚠️ 注意 R-A §8.1 的另一面：在 NeriPlayer 上，开反应式的真实代价是**强制关闭 audio offload**；而 Ncrust **本就刻意不启用 offload**（`PlaybackService.kt:294-303`），所以这条「代价已经付了、FFT 边际成本不大」的论证**不能直接搬到 Ncrust**。

### (b)「动效绑定必须基于真实音频特征」

**验收判据（唯一可机器验证的那条）：静音输入下，所有随时间变化的量必须收敛到零，且收敛后不得再排帧。**

| 项 | 内容 | 位置 |
|---|---|---|
| 判据来源 | R-A §8.2 第 9 条：为「音频门控的周期载波」加一条**可测的验收判据** —— *静音输入下，视觉输出必须收敛到静止（或明确的 idle 基线）*；R-A §8.3 把它列为落地清单项「把『静音 ⇒ 视觉静止』做成自动化测试」 | `docs/verification/v3.0.0/ref-research/neriplayer.md` §8.2 / §8.3 |
| **落点（主判据）** | `MotionBindingsTest.静音输入下所有随时间变化的量都收敛到零` —— 喂 0 值特征，断言在有限帧内全部收敛，且**收敛后一帧都不该再要求重绘** | `app/src/test/java/com/takahashirinta/ncrust/ui/player/motion/MotionBindingsTest.kt:240-272` |
| 落点（特征侧） | `AudioFeatureExtractorTest.静音时质心与各频段都是零`；`AudioFeatureExtractorTest.安静段落不产生瞬态` | `app/src/test/java/com/takahashirinta/ncrust/player/AudioFeatureExtractorTest.kt:170`、`:348` |
| 落点（生成速率） | `MotionBindingsTest.粒子速率与中高频能量正相关且低于门槛恒为零` —— 门槛下**恰好为 0**，不是「很小的速率」 | `.../MotionBindingsTest.kt:128`；实现 `MotionBindings.kt:203-211` |
| 落点（有界性） | `静音/长卡顿不炸量`：一帧补触发瞬态 ≤ 4、每帧新粒子 ≤ 4 | `MotionBindings.kt:169-176`、`MotionEnvelope.kt:524` |
| 追溯链（可审计） | `PCM → AudioFeatureExtractor → WaveformStore(volatile) → MotionBindings → MotionEnvelope / MotionBackdropState`，文件头 KDoc 把这条链写成了图 | `MotionBindings.kt:20-28` |
| 降级时的诚实表达 | 特征链路不可用时：瞬态回落到 v2.9.0 内置判据（**动效退回 v2.9.0，不是消失**）；中高频与质心**一律视为 0 ⇒ 粒子不生成**（如实表达「测不到」，而不是拿假数字驱动） | `MotionBindings.kt:47-54`、`:130-134` |

**⚠️ 未被这条判据覆盖的部分（如实登记）**：R-A §8.2 第 10 条指出，NeriPlayer 的 GLSL 满足该判据，但 Java 侧 5 个色块的 `uPointOffset = 0.1` 常驻漂移**不满足**。
本仓库没有移植那一层，因此**没有同类豁免**；但「背景呼吸」这类动效的 idle 基线是否也该有非零静止值，本版**未做真机观感核验** —— 见 §7。

---

## 7. 未核实 / 存疑项

> 五份报告共登记 43+ 条未核实项（R-E 的条目未逐条转录），下表**去重合并后**保留影响决策的部分。**每条都不应被当作事实引用。**

| # | 条目 | 状态 | 来自 |
|---|---|---|---|
| 1 | **第五份报告 `other-players.md` 现已存在**（`docs/verification/v3.0.0/ref-research/other-players.md`，本文首轮撰写后才落盘） | **已补齐**（首轮登记的「缺口」已关闭）。⚠️ 本文对它的全部引用（§4 第 2 条旁证、§6(a) 旁证①、本节第 29 条）均为**转述自 other-players.md** —— 七个仓库的 grep 与 Metrolist 指纹细节**本文未逐仓库复核** | 本次核对（`ls` 该目录）+ R-E |
| 2 | **`AudioTapProbeTest` 的缓冲粒度** | ✅ **已测量**（不再是缺口）：S6 / Android 7.0 上 44100 Hz 与 48000 Hz 均为 **100.00 ms / 11.0 Hz**；`PROBE-FEATURE-COST` 4096 帧 **574.19 µs**、**162×** 实时余量。见 §5.1。⚠️ 数字转录自 `probe/EVIDENCE.md`，而**该文件本文定稿时尚未落盘** ⇒ 复核状态见本节第 3 条 | R-B §6 第 1、2 条 → **已被本轮探针关闭**（数字逐字转录自 `docs/verification/v3.0.0/probe/EVIDENCE.md`） |
| 3 | ⚠️ **探针目录的复核残留**：R-B 撰写时说 `docs/verification/v3.0.0/probe/` 不存在；该目录**现已存在** `probe-audio-features.md`（190 行）、`probe-transient.md`（144 行）、`probe-motion-binding.md`。本文**未逐条核读**这三份探针的「【实测】」标注，也**未核读** §5.1 所引 `EVIDENCE.md`（撰写时未落盘） | **部分核实** | R-B §6 第 1 条 vs 工作区实际文件 |
| 4 | `MotionEnvelope.kt` 引用的 `docs/verification/v3.0.0/probe/probe-motion-binding.md` | ✅ **引用已闭合**（该探针文件现已落盘）。但 `MotionEnvelope.STRONG_BEAT_STRENGTH = 0.35` 的取值仍需真机观感复核，见本节第 27 条 | `MotionEnvelope.kt:220-226`（**行号未变**）+ 工作区实际文件 |
| 29 | **R-E 内部可能存在本文未核实的细节**：七仓库许可均为 GPL-3.0 的判定、`ShazamSignatureGenerator.kt` 的 2048 点 / 16 kHz / Hann 参数、grep 的完整命令与命中范围 | **转述自 other-players.md**（本文未独立验证；不与 GPLv3 的兼容性结论绑定） | R-E（转述） |
| 5 | **任何帧率 / 帧时间 / CPU 占用的量化数字**（NeriPlayer 仓库无 benchmark；全屏 AGSL 在 S6 级设备上**无公开数据**；「圆用纹理遮罩」在 Mali-T760 上是否真成瓶颈、每帧像素预算） | **未核实 / 需实测** | R-A §7 第 4 条；R-D 附「未核实」表末条；R-C §7 第 6 条 |
| 6 | **`BgEffectPainter.java` 是否为 HyperCeiler 衍生物**（「借鉴」还是「复制」无法判定；HyperCeiler 中未找到同名 shader，3 条路径均 404）；`hyper_background_effect.glsl` 是否同源（该文件**无**第三方署名头） | **未核实（高风险）** —— 需法务判断 | R-A §7 第 1、3 条 |
| 7 | **子模块许可未核实**（`miuix` / `accompanist-lyrics-*` / `NeriPlayer-LTW` 在浅克隆中为空目录）；FSF 许可清单对 ISC/Apache-2.0 与 GPLv3 兼容性的**具体措辞** | **未核实** | R-A §7 第 2 条；R-C §7 末条 |
| 8 | NeriPlayer 的 **native 附加授权**（"NeriPlayer Native Attribution License"）覆盖面与 Ncrust 的关系 | 报告判定为「**额外的宽松选项、不削弱 GPL-3.0 分支**」，但**未做法务核验** | R-A §6.4(2) |
| 9 | 实际听感上 NeriPlayer 的 `beat` 是否踩在真正的鼓点上（**没有节拍跟踪 / BPM 估计 / 相位对齐**；对古典、人声、弱起音曲目可能误触发或漏触发） | **未核实** | R-A §7 第 5 条 |
| 10 | 音画同步延迟（结构上至少有 1 帧 ≈ 16.7–22 ms，但**未实测**） | **未核实** | R-A §7 第 6 条 |
| 11 | 「32-bit 高解析输出会旁路音频可视化」在代码层面是否成立 —— R-A 明确「**Ncrust 别照抄这条结论**」 | **部分核实 / 存疑** | R-A §7 第 7 条 |
| 12 | AGSL 的语言限制：uniform 数量上限、源码字符串长度上限、`#include`/`#pragma` 支持、精度限定符全集、动态边界 `break` | **未核实**（官方文档均未涉及 ⇒ 不可依赖） | R-D 附「未核实」表；R-D 不可用清单 #10/#11 |
| 13 | `RuntimeShader` 构造编译失败的**具体异常类型**与首次编译耗时；API < 33 上执行它抛出的具体异常类型 | **未核实** | R-D 附「未核实」表 |
| 14 | Skia 是否按源码字符串缓存已编译的 `SkRuntimeEffect`（关系到「每帧改 uniform 会不会重建 native 对象」）；每帧离屏 pass 的**确切**数量 | **未核实**（「至少一次」是确定的量级） | R-D 附「未核实」表 |
| 15 | androidx 是否存在 `RuntimeShader` 兼容层；`asComposeRenderEffect` 的长期 API 稳定性；`setInputColorFilter`/`setInputXfermode` 的公开等级 | **未核实**（「没有回落」这一结论由 R-D §1.4 独立支撑） | R-D 附「未核实」表 |
| 16 | Compose 侧 API 稳定性：`Canvas.drawRawPoints` 是否属内部 API；`ShaderBrush` 内部 native `Shader` 的复用/失效细节；`withInfiniteAnimationFrameMillis` 的语义与稳定性（Konfetti 在用） | **未核实** | R-C §7 第 3、4、5 条 |
| 17 | **PRNG 确定性**：xorshift32 的移位三元组 (13,17,5) 与 Marsaglia 2003 原文是否逐字一致；`Random.Default` 在 Android 上具体走哪条实现 | **未核实**。⚠️ 另注：本仓库 `MotionBackdropState` **未采用** R-C §R1 的内联 xorshift32，而用 `kotlin.random.Random(PARTICLE_RANDOM_SEED)`（`MotionEnvelope.kt:263`）⇒ 报告 R-C §R5 第 1 条那条「逐位相同」的断言目前**没有对应的实现** | R-C §7 第 1、2 条；`MotionEnvelope.kt:263` |
| 18 | 「SoA vs AoS 的缓存优势」的权威出处 | **未核实**（R-C 自注：结论不依赖它） | R-C §7 第 7 条 |
| 19 | MPEG-7（ISO/IEC 15938-4）是否把零交叉率列为低层描述子；「ZCR 与谱质心存在定量相关（相关系数）」的一手文献 | **未核实** ⇒ R-B 只把 ZCR 当**独立代理**，不写「可替代质心」 | R-B §6 第 5、6 条 |
| 20 | 底鼓/贝斯基频的「50–100 Hz / 40–200 Hz」具体区间（仓库 KDoc 里的说法） | **未核实** | R-B §6 第 8 条 |
| 21 | 6 声道 FLAC 的**实际声道相关性**（R-B §1.7 的 ×6 是「同相信号」下的**上界**；真实内容介于 ×1 与 ×6 之间） | **未测量**（但「只要不是单声道，有效截止就一定偏高」成立） | R-B §6 第 9 条 |
| 22 | `centroidOf` / `bandMix` 在**真实曲目上的分布**（是否集中在 0.3–0.7 之类） | **未测量**（需真机 dump 直方图） | R-B §6 第 10 条 |
| 23 | 播放线程的 `THREAD_PRIORITY_AUDIO`；`AudioTrack` 欠载在 `dumpsys media.audio_flinger` 里的字段名 | **未核实**（不要猜字段名） | R-B §6 第 3、4 条 |
| 24 | `android.media.audiofx.Visualizer` 是否在更新的 API 上被标记 deprecated | **未核实**（2026-09-27 抓取页面未出现 deprecated 字样；不作为「可长期依赖」的依据） | R-B §6 第 11 条 |
| 25 | Bello et al. 2005 综述的**全文内容**（DOI 10.1109/TSA.2005.851998，closed access） | **未抓到** ⇒ 只登记书目、不引用其数字 | R-B §6 第 13 条 |
| 26 | 现行 `AudioFeatureExtractor` KDoc 里「每样本多三次乘加」的表述 | **与实际不符**（R-B 逐行计数为 **+9 flop/样本**）；`AudioFeatureExtractor.kt:38-39` 已按勘误改写 | R-B §6 第 14 条 |
| 27 | `MotionEnvelope.STRONG_BEAT_STRENGTH = 0.35` 的取值 | **需真机复核**（实现自己的注释写明「数值本身仍需真机复核」，且指向一份不存在的探针文件，见第 4 条） | `MotionEnvelope.kt:215-226` |
| 28 | 「背景呼吸」等动效在**静音时的 idle 基线**是否应为非零（§6(b) 的判据只覆盖「收敛到零」这一侧） | **未做真机观感核验** | 本次核对（`MotionBindingsTest.kt:240-272` 断言的是收敛到零） |
| 30 | **§5.1(c) 的「最多约 100 ms + 一帧」发布延迟** | **未做端到端实测**：它是「100 ms 缓冲粒度 + 每缓冲发布一次」推出的**结构性上限**，不是测出来的音画同步延迟 | 本文 §5.1(c) 推导；旁证 R-A §7 第 6 条（NeriPlayer 同项亦「未核实」） |
| 31 | **FFT 版本自身没有对应的性能测量**：分帧 / 窗 / PCM 环 / 倍率计算的开销，以及它与「缓冲内每 10 ms 结算」如何共存 | **未测量** ⇒ 这是 §6(a) 前置条件 ④ | 本文 §6(a)；R-B §7 R0 |

---

## 附：一句话总结

**这一版把「调研」变成了「一条可审计的数据通路」**：数据源是自家 PCM（可靠，R-A §8.1），
算法是两个一阶低通 + 自适应阈值瞬态（文献可引，R-B §7 R1/R3），
判据粒度由**真机实测**决定 —— 缓冲是 100 ms / 11 Hz，所以判据在缓冲内每 10 ms 结算一次（§5.1），
发布是 volatile 标量（零分配，但**每缓冲一次** ⇒ 最多约 100 ms + 一帧的反应延迟，如实写在 §5.4），
消费是唯一帧循环上的定长 SoA 池（零重组），
而被调研证明**不可用**的东西（AGSL / Visualizer / 真 FFT / `drawPoints` / 每帧 `Brush` / `Modifier.blur` / `BlurMaskFilter` / AGPL 衍生代码）**一条都没进来** ——
剩下的不确定，全部登记在 §7，**没有一条被写成了结论**。
