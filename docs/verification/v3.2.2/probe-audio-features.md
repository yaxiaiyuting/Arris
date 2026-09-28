# v3.2.2 探针：按「主导频带」给波形曲线着色（probe-audio-features）

> 只回答一件事：**现有音频特征链路里已经有什么、缺什么**，以及「按主导频带着色」能否落在不新增逐样本/逐帧计算的前提下。方法：只读源码 + 只读既有探针证据；所有事实性断言都带 `path:LINE`（相对仓库根 `Ncrust/`）；源码里查不到的一律写「未确认（源码中无依据）」并说明需要什么证据。
>
> 一处更正：任务给定的必读文件 `app/src/main/java/com/takahashirinta/ncrust/player/PcmRms.kt` **不存在**；`PcmRms` 是 `TransparentWaveformSink.kt` 内的 `internal object`（`app/src/main/java/com/takahashirinta/ncrust/player/TransparentWaveformSink.kt:270`）。

## 摘要（六问一表）

| # | 问题 | 结论 | 关键引用 |
|---|---|---|---|
| 1 | 输出格式 / 频率 / 量纲 | `rms/low/mid/high/spectralCentroid` 均为 0..1 的 Float（归一化幅度，非 dB 非能量）；两个一阶低通 150 Hz / 2 kHz，频带由「低通之差」得到；每缓冲一次，`frameMs` 由样本数反算 | `AudioFeatureExtractor.kt:100-113`、`572`、`583`、`368-374`、`440-448`、`452-453` |
| 2 | 是否接入 MotionBindings | 已接入，但**只在界面动效侧**；逐柱着色走 `mixRing/mixTargets` + 静态 `tintMix`，不经过 `MotionBindings` 实例 | `MotionClock.kt:102-111`、`AudioVisualizer.kt:573-577`、`778-790` |
| 3 | 平滑 / 时间窗口 | **只有柱高与峰值被平滑**（τ=22/130 ms）；`low/mid/high` 与驱动颜色的 `mixTargets` **完全不平滑**；可见窗口 28 根 ≈ 2.4~2.5 s | `WaveformRing.kt:280-297`、`399-402`、`245-248`、`117` |
| 4 | 缓冲率 vs 帧率 | 实测缓冲 **100.00 ms / 11.0~11.5 Hz**；帧间隔 16 或 33 ms ⇒ 一个缓冲跨约 3~6 帧，一帧平均 0.16~0.33 根柱 | `EVIDENCE.md:40-47`、`AudioVisualizer.kt:430-454`、`WaveformRing.kt:207-235` |
| 5 | 不新增计算能否复用 | 二向（低 vs 中高）判定**可以**（`mix` 已在窗口里）；三向只能用「最新一个缓冲」的三个标量（约 11 Hz），且必须先读 `available` | `WaveformRing.kt:117`、`380`、`AudioVisualizer.kt:194-198`、`287` |
| 6 | 降级路径 | `available=false` 时 `mid/high` 被传 0；现有逐柱着色**没有**读该标志 ⇒ 降级被画成「全是低频」，与真低频不可区分 | `TransparentWaveformSink.kt:227-232`、`AudioVisualizer.kt:247-253`、`784-790` |

---

## 1. AudioFeatureExtractor 的输出：格式、频率、量纲

### 1.1 每个输出字段的确切类型（`var ... private set`）

| 字段 | 行 | 类型 / 范围 | 语义 |
|---|---|---|---|
| `rms` | `AudioFeatureExtractor.kt:100-101` | `Float` 0..1 | 全带 RMS，与 v2.9.0 的 `PcmRms.fullOf` 逐值一致（`:99`） |
| `low` | `AudioFeatureExtractor.kt:104-105` | `Float` 0..1 | 低频带（< 150 Hz） |
| `mid` | `AudioFeatureExtractor.kt:108-109` | `Float` 0..1 | 中频带（150 Hz .. 2 kHz） |
| `high` | `AudioFeatureExtractor.kt:112-113` | `Float` 0..1 | 高频带（> 2 kHz） |
| `spectralCentroid` | `AudioFeatureExtractor.kt:126-127` | `Float` 0..1 | 三频带近似质心，0 = 全低、1 = 全高（`:116-125`） |
| `transientCount` | `AudioFeatureExtractor.kt:143-144` | `Int` 0..64 | 本次 `process` 判出的瞬态个数（上界 `:657`） |
| `transientStrength` | `AudioFeatureExtractor.kt:147-148` | `Float` 0..1 | 最强一次瞬态的强度，已 clamp |
| `frameMs` | `AudioFeatureExtractor.kt:161-162` | `Float` ≥0 | 本次缓冲覆盖的时长 |
| `available` | `AudioFeatureExtractor.kt:165-166` | `Boolean` | 采样率已知且编码受支持 |
| `transient` | `AudioFeatureExtractor.kt:151` | `Boolean` | `transientCount > 0` 的便捷读法 |

### 1.2 滤波器递推与两个截止频率

两个截止频率：低频 **150 Hz** = `LOW_CUTOFF_HZ`，直接引用 `PcmRms.BASS_CUTOFF_HZ` 而不是复制数值（`AudioFeatureExtractor.kt:572`、`TransparentWaveformSink.kt:420`）；中/高分界 **2000 Hz** = `MID_HIGH_CUTOFF_HZ`（`AudioFeatureExtractor.kt:583`，依据在 `:576-582`）。
系数 `a = 1 − exp(−2π·fc/fs)`，clamp 到 `[0,1]`（`AudioFeatureExtractor.kt:710-714`），`configure` 里算一次（`:274-275`）。逐样本递推（16bit 分支 `:368-371`，float 分支同构 `:412-415`）：`sLow += aLow*(v − sLow)`、`sMid += aMid*(v − sMid)`、`m = sMid − sLow`（中频带 = 两低通之差）、`h = v − sMid`（高频带）。
滤波状态**每声道一份**（`filterState[channel][0]=150 Hz`、`[1]=2 kHz`，`AudioFeatureExtractor.kt:179`、`:173`；声道上限 8 见 `:635`），修的是「交错 PCM 会把有效截止频率乘以声道数」（`:43-54`）。

### 1.3 每个频带值怎么算出来的

四个量都在**同一遍逐样本遍历**里累加平方和（`AudioFeatureExtractor.kt:367-374`）：`sumFull += v*v`、`sumLow += sLow*sLow`、`sumMid += m*m`、`sumHigh += h*h`；结尾 `sqrt(sum / n)`，`n = samples`（`:440-444`）。
⇒ 每个频带值 = **该频带滤波后信号的 RMS**；`samples` 是**全部声道的交错样本总数**（`:340`），所以多声道是一起求一个 RMS，不按声道平均。逐样本恒等式 `low + mid + high == x` 由既有探针复算（`docs/verification/v3.0.0/probe/probe-audio-features.md:92-95`）。

### 1.4 量纲、归一化与 `frameMs`

16bit 走 `v = raw.toShort() / 32768.0`（满幅归一化到 1.0 的**幅度**，`AudioFeatureExtractor.kt:363`）；float 编码 `v = Float.fromBits(bits).toDouble()`，**不做缩放也不 clamp**（`:407`）；输出统一过 `finite01`（非有限值归 0，否则 clamp 到 `[0,1]`，`:445-448`、`:764-765`）。因为取了 `sqrt`（`:441-444`），量纲是**幅度而不是能量**，也不是 dB。三频带之间另有一次归一化：`bandMix = (mid+high)/(low+mid+high)`（`:726-734`），分母 ≤ `CENTROID_EPS = 1e-9` 时返回 0 而不是 NaN（`:595`、`:731`）。
`frameMs`：`frames = samples / channelCount`，`frameMs = frames * 1000.0 / sampleRateHz`，`sampleRateHz <= 0` 时为 0（`AudioFeatureExtractor.kt:452-453`）。这是「每缓冲一次判定」能成立的**全部**依据：缓冲粒度是运行时行为，代码里没有常量能回答它多大（`:153-160`）；`TYPICAL_FRAME_MS = 21.0` **只是**给 `baselineK` 一个非零初值，注释明确写了它不能当作缓冲粒度依据（`:638-644`）。

### 1.5 真实更新率：唯一已知的实测

全仓检索 `PROBE-AUDIO-TAP` 的命中只有 v3.0.0 的探针文档与其仪器探针（`docs/verification/v3.0.0/probe/EVIDENCE.md:26`、`docs/verification/v3.0.0/ref-research/RECOMMENDATIONS.md:125`、`docs/verification/v3.0.0/probe/probe-audio-features.md:53`、`app/src/androidTest/java/com/takahashirinta/ncrust/probe/AudioTapProbeTest.kt:216`）与五处代码/单测引用（`AudioFeatureExtractor.kt:135`、`:215`、`:640`、`AudioVisualizer.kt:262`、`app/src/test/java/com/takahashirinta/ncrust/player/AudioFeatureExtractorTest.kt:279`）——**v3.1.0 / v3.2.0 / v3.2.1 各目录下没有任何新测量**。**已实测**（原样抄录，帧数/缓冲与时长均为平均值）：S6 `SM-G9209`（Android 7.0 / API 24）在 44100 与 48000 下分别是 4410.0 / 4800.0 帧、**100.00 ms**、11.0 Hz（`docs/verification/v3.0.0/probe/EVIDENCE.md:40-41`）；PCL110（Android 16 / API 36）同样 4410.0 / 4800.0 帧、**100.00 ms**、11.5 Hz（`:46-47`）；设备表 `:13-15`，直方图只有一个桶 ⇒ 粒度高度稳定（`:50-54`）。**现代设备（Android 16）的粒度同样已实测**，不是未确认。
仍然**未确认（源码中无依据）**的部分与所需证据：(a) 在线曲目 / 6 声道 FLAC 端到端粒度 —— 探针用的是自建 WAV + 真实 ExoPlayer（`EVIDENCE.md:28-30`），6 声道端到端明确未做（`:92`），需在臻品档 6ch FLAC 上复跑探针；(b) 音画同步延迟真机值 —— 现有 100~133 ms 是结构性上界而非实测（`:93`）；(c) 真机欠载率 —— underrun 计数未采集（`:91`、`probe-audio-features.md:183-185`）。
⚠️ 源码里有两处注释**不要**作为依据：`AudioVisualizer.kt:182` 写「实测缓冲粒度 ~10–50ms」，与 `EVIDENCE.md:40-47` 的 100.00 ms 矛盾；`AudioVisualizer.kt:124` 把「代码不保证任何固定值」归因到「探针 §7 #1 明确未确认」，而 §7 第 1 条讲的是 underrun / 真实增量（`probe-audio-features.md:183-185`）。

---

## 2. 是否已接入 MotionBindings？现有档位下如何使用？

### 2.1 PCM → 像素的完整调用链

**A. 采样与发布（音频线程，每缓冲一次）**：`VisualizerRenderersFactory.buildAudioSink` 构造 `TransparentWaveformSink()` 并装进 `TeeAudioProcessor`（`player/VisualizerRenderersFactory.kt:70-73`）；tee 把解码输出的只读副本交给 `handleBuffer`（`TransparentWaveformSink.kt:183`，零分配/零异常契约 `:171-182`）；两个开关都关时连样本遍历都不跑（`:186-188`）；`extractor.process(buffer, encoding)` 一次遍历算全部特征（`:192`），失败即降级（`:216-233`）。
**B. 三组发布量**：`onBar(rms, low, bandMix(low,mid,high))`（`TransparentWaveformSink.kt:194-199`）、`onFeatures(rms, low, mid, high, centroid, true)`（`:202-209`）、`onTransient(count, strength)`（`:212-214`）；默认收端是 `WaveformStore::onBar / ::onFeatures / ::onTransient`（`:120-123`）。
**C. 波形侧（UI 线程，每帧一次）**：`MotionFrameClock` 的循环调 `MotionClock.frame(...)`（`motion/MotionClock.kt:206-234`、`:217`）⇒ `WaveformStore.pump`（`:100`）⇒ `ring.pump`（`AudioVisualizer.kt:317`）；随后 `featureBindings.update(...)` 逐个取 `WaveformStore.featureRms/Low/Mid/High/Centroid/featuresAvailable/transientCount/transientStrength`（`MotionClock.kt:102-111`）。
**D. 进环形缓冲**：`onBar` ⇒ `ring.push(rms, bass, mix)`（`AudioVisualizer.kt:228-230`）⇒ `WaveformRing.push` 写 `ring/bassRing/mixRing`（`WaveformRing.kt:138-145`）；`consumePending` 把新柱搬进 `targets`、把 `mix` 搬进 `mixTargets`（`:207-235`、`:231`、`:245-248`）；`pump` 返回真时 `generationState.intValue++`（`AudioVisualizer.kt:337`）。
**E. 到像素**：`AudioVisualizerBars` 的 `Canvas` 在 **draw 阶段**读 `generation`（`AudioVisualizer.kt:570`）并 `snapshot(bars, peaks, mixes)`（`:574`、`:344-345`）⇒ `drawWaveformBars` 逐柱 `MotionBindings.tintMix(mixes[i])`（`:784-786`、`MotionBindings.kt:252-256`）⇒ `lerp(barColor, brightColor, tint)`（`:787`）⇒ 一笔 `drawRoundRect` / `drawRect`（`:791-813`）。`brightColor = lerp(barColor, White, 0.45)`，`Color` 是 value class ⇒ 不分配（`:529-530`、`:643`）。
**F. 能量条是另一条链**：`effects.waveBandLanes` 为真时直接读 volatile 标量 `featureLow()/featureMid()/featureHigh()`（`AudioVisualizer.kt:617-627`，getter `:291-295`），长度 = `size.width * sqrt(v)`（`:878`），不读 `mixes`。

### 2.2 两个挂载点

`AudioVisualizerBars` 有两个挂载点，改造必须同时覆盖：大屏左栏经 `AudioVisualizerSlot`（`ui/player/PlayerCard.kt:845-855`，判据 `PlayerCardLayouts.kt:155-156`、`:309-310`）；背景级全屏波形经 `if (motion.fullScreenWaveform)`（`PlayerCardBackdrop.kt:147-163`），两处复用同一实现的理由见 `:143-146`。`MotionFrameClock` 只在背景层挂**恰好一次**（`:130-134`），`waveformMounted = visualizerSlot || motion.fullScreenWaveform`（`:133`）。

### 2.3 档位闸门（现有档位下如何使用）

- `VisualizerEffects.waveBandMode`：`0` 关 / `1` 逐柱着色 / `2` 逐柱着色 + 能量条（`waveform/VisualizerTier.kt:179-186`、`:151`），派生位 `waveBandOn`（`:169`）、`waveBandLanes`（`:172`）。
- 档位映射的**唯一落点**是 `MotionEffects.of`：`refinedPlus && switches.waveBands` 才开，炫技档给 `LANES`、精致档给 `TINT`（`motion/MotionEffects.kt:407-415`）；其中 `refinedPlus = uiMotionEnabled && tier >= REFINED`（`:387`）⇒ **「界面动效」总开关关掉时逐柱着色一起关**（波形开关仍开着也没用）。
- 再下一层：`VisualizerEffects.of` 在 `tier < REFINED` 时强制 `MODE_WAVE_BAND_OFF`（`VisualizerTier.kt:231`、`:250`）⇒ **简洁档永远没有多频段调制**。
- 用户逐项开关是 `motion_wave_bands`（`motion/MotionPrefs.kt:101`、`:194`），缺 key 默认开（`DEFAULT_SWITCH = true`，`:124`）；组合期只读一次 `val effects = VisualizerPrefs.effects.value`（`AudioVisualizer.kt:527`），该状态是 `derivedStateOf { MotionPrefs.effects.value.waveform }`（`VisualizerPrefs.kt:208`）。
- 数据是否被生产取决于 `MotionEffects.needsAudioFeatures`，它**已包含** `waveform.waveBandOn`（`MotionEffects.kt:343-345`）⇒ `syncAudioFeatureDemand()` 置真 `WaveformStore.motionFeaturesEnabled`（`MotionPrefs.kt:452-457`），音频线程据此跑提取器（`TransparentWaveformSink.kt:119`、`:187`）。

### 2.4 MotionBindings 现状（关键区分）

已接入：每帧调一次 `update`（`MotionClock.kt:102-111`），字段见 `MotionBindings.kt:62-99`（`rms/low/mid/high/centroid/midHigh/brightness/transients/strength/available`），导出入口 `MotionClock.bindings()`（`MotionClock.kt:84`）。
但本文检索 `app/src/main` 全量后，`bindings()` 的**生产调用点为 0**（唯一定义 `MotionClock.kt:84`）；实例字段的实际消费者只有两处：`MotionEnvelope`（响度来源 / 瞬态 / 强度 / available，`MotionClock.kt:114-127`）与 `MotionBackdropState.update`（瞬态、强度、`midHigh`，`MotionClock.kt:129`、`motion/MotionEnvelope.kt:345-365`）。⇒ **逐柱着色不经过 `MotionBindings` 实例**，它经过 `mixRing/mixTargets`（`WaveformRing.kt:87`、`:117`）与 `tintMix` 这个静态纯函数（`MotionBindings.kt:252`）。

---

## 3. 是否有平滑处理？时间窗口？

### 3.1 被平滑的量（时间常数形式 `k = 1 − exp(−dt/τ)`）

| 量 | 时间常数 | 位置 |
|---|---|---|
| 柱高 `bars[i]` ← `targets[i]` | 起音 **22 ms** / 回落 **130 ms** | `WaveformRing.kt:280-293`、`:399-402` |
| 峰值 `peaks[i]` | 保持 420 ms，之后 0.9/s 下落 | `WaveformRing.kt:305-323`、`:412`、`:418` |
| 渐变流动 / 呼吸相位 | 周期 2400 ms / 3200 ms | `WaveformRing.kt:193-202`、`:421`、`:424` |
| 界面响度包络 `level` | 起音 **90 ms** / 回落 **260 ms** | `motion/MotionEnvelope.kt:127-141`、`:204-207` |
| 重拍脉冲 `pulse` | **350 ms** | `MotionEnvelope.kt:105-109`、`:210` |

### 3.2 **没有**被平滑的量（改造必须知道）

- `low/mid/high` 本身**没有任何平滑**：`process` 直接 `sqrt(sum/n)` 再 clamp（`AudioFeatureExtractor.kt:440-448`），发布是纯 volatile 赋值（`AudioVisualizer.kt:247-251`）。唯一带时间常数的是**瞬态判据的慢速基线**（`BASELINE_TAU_MS = 800 ms`，`AudioFeatureExtractor.kt:660`、`:508`），它是判据内部状态，不是发布值。
- 驱动颜色的 `mixTargets` **不平滑**：`shiftMixIn` 逐值原样搬运（`WaveformRing.kt:245-248`），`mixTarget = mixValue` 取原始值（`:230`、`:380`）；`approach` 只碰 `bars[i]` 与峰值，**从不修改 `mixTargets`/`mixTarget`**（`:284-295`）。这是刻意的：颜色变化不进重绘判据，理由在 `:238-244`。
- 能量条读的 `featureLow/Mid/High` 是最新一个缓冲的原始值（`AudioVisualizer.kt:291-295`、`:618-621`）；`MotionBindings.low/mid/high` 每帧原样收下（`MotionBindings.kt:121-124`），`brightness = high/(low+mid+high)` 是瞬时比值（`:126-127`），同样不平滑。

### 3.3 时间窗口

可见窗口 = `barCount = 28` 根（`AudioVisualizer.kt:118`、`WaveformRing.kt:61`），按实测柱率 11.0~11.5 Hz 折算约 **2.4~2.5 s**（本文算术：28 ÷ 11.5、28 ÷ 11.0；柱率来源 `EVIDENCE.md:40-47`）。积压窗口 = `CAPACITY = 256` 根（`AudioVisualizer.kt:133`），同一柱率下约 **22~23 s**（本文算术），溢出丢最旧的、绝不回压音频线程（`WaveformRing.kt:211-215`），`pendingCount` 可读（`:395`）。
注意：`bars` 与 `mixTargets` 同维度（都 28）但**只有前者被 22/130 ms 平滑** ⇒ 改造后「柱高的运动曲线」与「颜色的切换时刻」不是同一条曲线。

---

## 4. 数据更新频率与 UI 帧率的关系

缓冲率（实测）**100.00 ms / 11.0~11.5 Hz**（`EVIDENCE.md:40-47`）；帧间隔 `visualizerFrameIntervalMs` = **16 ms**（API ≥ 26 且非低内存）或 **33 ms**（API < 26 或 `isLowRamDevice`）（`AudioVisualizer.kt:448-454`，常量 `:430`、`:433`）。由这两组数字作算术：**一个缓冲跨约 6.25 帧（16 ms）或 3.03 帧（33 ms）**，即**一帧平均只到达 0.16 根（60 fps）或 0.33 根（30 fps）柱**。
`WaveformStore.pump` 遇到多个待消费柱时，`consumePending` 用 `repeat(pending)` **一次全部消费**（`WaveformRing.kt:207-235`、`:217`）：每根都 `shiftIn`（进 `targets`）并 `shiftMixIn`（进 `mixTargets`）；`bassTarget`/`mixTarget`/`newestTarget()` 只保留**最后一根**（`:225`、`:230`、`:374`、`:377`、`:380`）。所以积压 k 根时，一帧里可见窗口**一次前移 k 格**，而柱高只朝最终 `targets` 走**一步**（`approach` 每帧一次，`:280-297`）。
瞬态不受影响：音频线程发布的是**单调累计计数**（`AudioVisualizer.kt:207-209`、`:283`），UI 侧比较差值，一帧补发上限 `MAX_TRANSIENTS_PER_FRAME = 4`（`MotionBindings.kt:176`、`WaveformEffectsState.kt:159`）。消费只在 `active` 时发生（`WaveformRing.kt:177-182`）；帧循环本身只在 `clockNeeded = enabled && (motion.needsFrameClock || waveformMounted)` 时存在（`MotionClock.kt:203-207`）⇒ 暂停期间待消费柱攒着（上限 256），恢复后一次消费。

---

## 5. 能否在不新增计算的前提下复用这些数据？

### 5.1 UI 线程上已算好、已可读的量

- **volatile 标量**（音频线程写 / UI 线程读，`AudioVisualizer.kt:176-213`）：`featureRms()`、`featureLow()`、`featureMid()`、`featureHigh()`、`featureCentroid()`（`:289-297`）、`featuresAvailable()`（`:287`）、`transientCount()`、`transientStrength()`（`:299-303`）。每个都是最新**一个缓冲**的值，**无历史**。
- **滚动窗口**：`snapshot(bars, peaks, mixes)`（`:344-345`）、`newestBar/newestBass/newestMix`（`:356-373`）；`mixes` 是逐柱的 `bandMix`（`WaveformRing.kt:117`、`:354`）。
- **每帧特征快照**：`MotionClock.bindings()`（`MotionClock.kt:84`、`:102-111`），字段含 `low/mid/high/midHigh/brightness/available`（`MotionBindings.kt:62-99`）；当前无生产调用点（见 §2.4），读取是普通字段读、不触发重组、不分配。
- **已算好的派生量**：`bandMix`（`AudioFeatureExtractor.kt:726-734`，每缓冲在 `TransparentWaveformSink.kt:197` 调用一次）、`tintMix` 对比度拉伸（`MotionBindings.kt:252-256`）。

### 5.2 今天**没有**发布的量

- **逐缓冲的 `mid`/`high` 历史**：进环形缓冲的只有 `rms`、`bass`、`mix` 三个槽（`WaveformRing.kt:68`、`:77`、`:87`、`:141-145`）；`mid`/`high` 只有「最新值」一个标量（`AudioVisualizer.kt:194-198`）。
- **「哪一带占主导」这个判定本身**：现有频带归并只有 `bandMix`（输出 1 个标量，`AudioFeatureExtractor.kt:726-734`）与 `centroidOf`（输出 1 个标量，`:750-762`）。本文检索 `app/src/main` 里 `featureLow|featureMid|featureHigh` 的命中只有 `AudioVisualizer.kt:291-295`（getter）、`:297`、`:619-621`（能量条）与 `MotionClock.kt:104-106`（绑定层）—— **没有任何取最大值/排序的代码**。
- **`frameMs` 没有发布到 UI**：只在本类内赋值/清零（`AudioFeatureExtractor.kt:161`、`:262`、`:313`、`:453`），消费者只有单测（`app/src/test/java/com/takahashirinta/ncrust/player/AudioFeatureExtractorTest.kt:388-392`）⇒ UI 侧**拿不到每个缓冲的真实时长**，任何「按毫秒」的窗口换算只能假设标称缓冲时长。
- **`available` 的逐值来源**：只有一个布尔（`AudioVisualizer.kt:205`），没有「mid/high 是缺失还是真为 0」的第二信号；发布顺序保证「读到 true ⇒ 本轮分量齐全」（`:184-186`、`:252-253`）。

### 5.3 纯用已发布值能不能做「主导频带」判定

- **二向（低 vs 中高）**：**能，且零新增音频侧计算**。`mixes[i]` 就是 `(mid+high)/(low+mid+high)`（`AudioFeatureExtractor.kt:732`），已在窗口里（`WaveformRing.kt:117`），逐柱阈值比较即可（现有代码已在 `AudioVisualizer.kt:786` 用 `>= 0.5` 比较）。
- **三向（低 / 中 / 高）**：**只能对「最新一个缓冲」做**，输入是 `featureLow/Mid/High`（`AudioVisualizer.kt:291-295`）或 `MotionClock.bindings().low/mid/high`（`MotionBindings.kt:66-79`）。**逐柱三向判定做不到** —— 窗口里只有二向聚合值。argmax 本身是 UI 侧 O(1) 比较，不新增每样本计算，但更新率被钉在缓冲率（约 11 Hz），与 16/33 ms 的帧率不同步。
- **诚实性前提**：一阶滤波器的频带泄漏很大，150 Hz 附近 `low/mid` 几乎相等（实测 `low=0.400 / mid=0.369`，`probe-audio-features.md:104`），1 kHz 时 `mid=0.463` 才明显领先（`:105`）⇒ argmax 在交叉点附近**会抖动**，直接用它选颜色会闪。「这个抖动在实际曲目上是否可见」**未确认（源码中无依据）**，需要真机采样三种曲风的 `low/mid/high` 时间序列才能定迟滞阈值。
- **降级时不能做三向判定**：`available=false` 时 `mid=high=0`（`TransparentWaveformSink.kt:231`），argmax 必然选 `low`，那是假结论；必须先读 `featuresAvailable()`（`AudioVisualizer.kt:287`）。

### 5.4 成本模型

**每缓冲发布是零分配（源码契约）**：`onBar/onFeatures/onTransient` 只做 volatile 原语写 + `isFinite/coerceIn`（`AudioVisualizer.kt:228-284`），KDoc 明写零分配（`:233`）；失败计数刻意不写日志、不拼字符串（`TransparentWaveformSink.kt:147-148`、`:61-62`）。音频线程每回调的成本是**一遍逐样本算术**（16bit 分支 `:355-393`），实测 4096 帧立体声 **574.19 µs / 162× 实时**（S6，`EVIDENCE.md:68`）与 **414.90 µs / 224× 实时**（PCL110，`:73`）；每样本约 6 乘 10 加减（`AudioFeatureExtractor.kt:37`）。注意实测的是 4096 帧，而真实缓冲是 4410/4800 帧（`EVIDENCE.md:40-41`）。
**UI 每帧成本**：`consumePending` 是 O(待消费柱数 × 28)（`WaveformRing.kt:217-233`），典型 0~1 根；`approach` 是 O(28) 且每帧两次 `exp`（`:281-282`）；`snapshot` 是三趟 O(28) 拷贝（`:351-355`）；绘制是 28 笔柱 + 可选峰值/光点（`AudioVisualizer.kt:764-834`），每笔一次 `sqrt`（`:769`）、一次 `tintMix`（`:785`）、一次 `lerp`（`:787`）。三个数组都是 `remember` 的 `FloatArray`，逐帧原地更新（`:463-464`、`:521-524`）；`FlowBrushCache` 只在宽度变化时重建 Brush（`:896-920`）。
⇒ 一个「读已发布标量 + 比较 + 选色」的主导频带选择器**可以做到零新增分配、零新增音频线程计算**，代价只是把已有的 `lerp`（`:787`）从二向插值换成三向查表，外加 O(1) 迟滞状态；若需要第三种颜色，则多一个 `remember` 的 `Color`（一次性，参考 `:530`）。

---

## 6. 数据获取失败时的降级路径（`available = false` 端到端）

1. **提取器侧**：`configure` 遇到采样率 ≤ 0 或声道数 ≤ 0 ⇒ 先清零输出再 `available = false` 并返回（`AudioFeatureExtractor.kt:270-273`，清零在 `:262-269`）；成功路径才 `available = true`（`:289`，此处曾漏写，见 `:285-288`）。`process` 首行 `if (!available) return false`（`:336`）；编码不支持或缓冲为空同样返回 false（`:337-341`）。
2. **sink 侧**：失败 ⇒ 回落 `PcmRms.analyze`（RMS-only）并 `onBar(full, bass, 0.0)`（`TransparentWaveformSink.kt:216-227`）与 `onFeatures(full, bass, 0.0, 0.0, 0.0, false)`（`:228-232`）；异常走同一隔离边界，只丢这一根柱（`:234-237`、`:181`）。
3. **发布侧**：`onFeatures` 先把 `mid/high/centroid` 写成 0，**最后**写 `featAvailable`（`AudioVisualizer.kt:247-253`）；读取用 `featuresAvailable()`（`:287`）。
4. **环形缓冲侧**：`mix = 0.0` 进 `mixRing`（`WaveformRing.kt:144`）⇒ `mixTarget = 0`（`:230`）⇒ `mixTargets` 全 0（`:247`）⇒ 逐柱 `tintMix(0) = 0`（`MotionBindings.kt:252-256`）⇒ `lerp(barColor, brightColor, 0) = barColor`、`useBrush = false`、alpha × `BAND_TINT_ALPHA_MIN = 0.8`（`AudioVisualizer.kt:784-790`、`:655`）。**⇒ 降级时全部柱子被画成「低沉色」，与「这一段真的全是低频」在画面上完全不可区分**（现存诚实性缺口）。能量条同理：`mid/high = 0 ⇒ sqrt(0) = 0 ⇒ width = 0 ⇒ 不画`（`:876-886`）。
5. **瞬态侧**：`pump` 在 `!featAvailable` 时传哨兵 `-1L`（`AudioVisualizer.kt:324`）⇒ `WaveformEffectsState` 走内置判据（`WaveformEffectsState.kt:111-116`、`:152-176`、`:175-202`），常量与 v2.9.0 同值（`AudioFeatureExtractor.kt:597-609`）；两条路径互斥，不会同一次击打判两次（`WaveformEffectsState.kt:113-114`）。
6. **绑定侧**：`MotionBindings.update(available=false)` 仍收下 `rms/low`（`MotionBindings.kt:119-124`），但 `midHigh = (0+0)*0.5 = 0`（`:125`）、`brightness = 0`（`:127`）、`transients = 0` 且 `lastTransientCount = NO_COUNT` 后返回（`:130-134`，哨兵 `:167`）。
7. **动效侧**：响度来源回落 `WaveformStore.newestBar()`（`MotionClock.kt:112-118`）；`MotionEnvelope` 走内置判据（`MotionEnvelope.kt:163-187`，门槛常量 `:173-176`）；`midHigh=0 ⇒ particleRateHz(0)=0` 不生成粒子（`MotionEnvelope.kt:360-380`、`MotionBindings.kt:203-211`），`transients=0` ⇒ 无光晕/冲击波（`MotionEnvelope.kt:345-358`）。

**新消费者（主导频带选色器）要诚实，必须做到**：(1) 先读 `WaveformStore.featuresAvailable()`（`AudioVisualizer.kt:287`）再使用 `low/mid/high`，为 false 时回落到现有语义明确的着色路径、不要做 argmax（发布顺序保证读到 true 时本轮分量齐全：`:184-186`、`:252-253`）；(2) 不许把 `mid == 0f` 解释成「没有中频」（依据 `TransparentWaveformSink.kt:231`）；(3) 不许复用 `mixes[]` 表达新语义 —— 那是 `bandMix`，降级时被写成 `0.0`（`TransparentWaveformSink.kt:227`），复用它等于继承上面第 4 条的缺口；(4) 回退实现只能有一处（两个挂载点都调 `AudioVisualizerBars`：`PlayerCard.kt:849`、`PlayerCardBackdrop.kt:156`）；(5) `needsAudioFeatures` 必须同步登记，否则数据根本不发布（`MotionEffects.kt:343-345`、`MotionPrefs.kt:452-457`、`TransparentWaveformSink.kt:187`）；(6) 对外口径是「退回 v2.9.0 的行为」而不是「功能消失」（`TransparentWaveformSink.kt:78-81`、`docs/verification/v3.0.0/probe/probe-audio-features.md:150-165`）。

---

## 对 v3.2.2 改造的约束

1. **音频线程零新增逐样本计算**：现行契约是「一次遍历算全部」（`TransparentWaveformSink.kt:190-191`、`AudioFeatureExtractor.kt:18-26`、`:30-37`）。主导频带判定只能在 UI 侧对已发布的三个标量做比较；任何新的逐样本量必须并入同一次遍历，不得新增第二遍扫描（`TransparentWaveformSink.kt:72-73`）。
2. **音频线程零分配、零日志**（`AudioFeatureExtractor.kt:56-61`、`TransparentWaveformSink.kt:56-59`、`:250-267`、`AudioVisualizer.kt:233`）；失败只允许自增一个 volatile 计数，不拼字符串（`TransparentWaveformSink.kt:147-148`）。
3. **UI 线程零每帧分配**：逐帧数组必须 `remember` 成 `FloatArray`（`AudioVisualizer.kt:463-464`、`:521-524`），颜色必须是 value class 或 `remember` 常量（`:529-530`、`:643`），Brush 只在尺寸变化时重建（`:896-920`），不得在 draw 里 `floatArrayOf(...)`（`:857-858` 明确禁止）。
4. **必须带 `available` 判据并如实降级**（见 §6 六条）；缺了它，降级会被画成「全是低频」（`TransparentWaveformSink.kt:231` + `AudioVisualizer.kt:784-790`），即把「测不到」画成「测到了 0」。
5. **三向判定必须带迟滞/死区**，依据是频带泄漏实测（`probe-audio-features.md:103-106`）与 `bandMix` 自身的零分母防御口径（`AudioFeatureExtractor.kt:716-724`）；阈值本身需要新证据（真机采样 `low/mid/high` 时间序列），在此之前**未确认（源码中无依据）**。
6. **闸门层级不得新增第五层**：现有顺序是「总开关 → 用户档位 →（律动闸）→ 逐项开关」（`MotionEffects.kt:366-373`、`:386-415`），且 `MODE_WAVE_BAND_*` 只有三个取值（`VisualizerTier.kt:179-186`）。新语义要么落进这三态，要么新增一个显式开关键（唯一字面量落点 `MotionPrefs.kt:98-105`）并同步 `needsAudioFeatures`（`MotionEffects.kt:343-345`）。
7. **重绘判据要显式处理**：颜色不进 `pump` 返回值（`WaveformRing.kt:238-244`、`:250-265`）⇒ 若要求「主导频带一变就换色」，必须自己让 `generation` 失效（写法参考 `AudioVisualizer.kt:337`），否则颜色会停在旧值上直到柱子动。
8. **两个挂载点共用一份实现**（`PlayerCard.kt:849`、`PlayerCardBackdrop.kt:156`、`:143-146`）。
9. **UI 层不得引用 `AudioFeatureExtractor`**：它带 media3 的 `@UnstableApi`（`AudioFeatureExtractor.kt:92`），UI 层为避开 opt-in 传染已刻意重复声明常量（`AudioVisualizer.kt:276-280`、`:427`）⇒ 判定纯函数应放在**无 media3 依赖**的纯逻辑层（范式：`MotionBindings.kt:1-9` 零 import、`WaveformRing.kt:16`、`VisualizerTier.kt:12`）。
10. **必须 JVM 可测**：纯函数 + 单测是既有纪律（`VisualizerTier.kt:12`、`MotionBindings.kt:12`、`WaveformRing.kt:16`、`AudioFeatureExtractor.kt:597`）；判定 / 迟滞 / 降级回退都要能在 `app/src/test/java/com/takahashirinta/ncrust/ui/player/motion/MotionBindingsTest.kt`、`.../ui/player/waveform/VisualizerTierTest.kt`、`.../ui/player/WaveformRingTest.kt`、`.../player/TransparentWaveformSinkTest.kt` 这一层直接断言，不需要设备。
11. **不许声称为频谱**：横轴仍是时间（`AudioVisualizer.kt:730`、`VisualizerTier.kt:141-149`），`spectrumColoring` 保持恒 false（`VisualizerTier.kt:137`），文案不得写成「频谱分色」。
