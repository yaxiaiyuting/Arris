# 探针 3.1 · 现有音频特征提取能力（v3.0.0）

> **性质**：本文件是**只读探针 + 已落地实现的数值核对**。所有结论都给了 `file:line`。
> **证据分级**：**【事实】**= 源码引用；**【实测】**= 真机/仪器测试跑出来的数字（附产出文件）；
> **【估算】**= 代码算式；**【未验证】**= 明确没确认。
> 实现状态：v3.0.0 的实现**已经写好**（`player/AudioFeatureExtractor.kt` 等），
> 本文回答「它到底算了什么、依据是什么、代价是多少、哪些还没验证」。

---

## 1. v2.9.0 的低频节拍通道：逐字回顾

| 项 | 值 | 证据 |
|---|---|---|
| 递推 | `state += a·(x − state)`（一阶 / 单极点） | `TransparentWaveformSink.kt:246-263`（v2.9.0 版）→ 现在在 `AudioFeatureExtractor.kt:313-341` |
| 截止频率 | **150 Hz** | `PcmRms.BASS_CUTOFF_HZ = 150.0`（`TransparentWaveformSink.kt:325`） |
| 系数 | `a = 1 − exp(−2π·fc/fs)`，clamp 到 `[0,1]` | `PcmRms.lowPassCoefficient`（`TransparentWaveformSink.kt:304-308`） |
| 打包 | `(bass, full)` 两个 Float 打进**一个 `Long`**，零分配 | `PcmRms.pack/bassOf/fullOf`（`TransparentWaveformSink.kt:311-316`） |
| 遍历次数 | 与全带 RMS **共用同一次**逐样本遍历 | 同上 `analyze` 的循环体 |
| 消费方 | `WaveformEffectsState.update`（涟漪/粒子）、`MotionEnvelope.update`（脉冲/光晕） | `AudioVisualizer.kt:297-320`（`pump`） |
| 交付时的诚实边界 | **只有一个**低频通道，底鼓与贝斯不可区分；慢歌触发变少是正确行为 | `WaveformEffectsState.kt:101-106` |

**v2.9.0 留下的一个真缺陷（v3.0.0 修正）**：滤波状态只有**一个标量**（`DoubleArray(1)`），
而循环按**交错样本**推进 ⇒ 相邻样本属于不同声道 ⇒ 每个声道的状态每帧被推进 `channelCount` 次
⇒ **有效截止频率被乘以声道数**：标称 150 Hz 在单声道是 150、立体声是 **300**、
6 声道（QQ 臻品档的实测布局）是 **900 Hz**。它不崩、不报错，只是「低频带」悄悄变成「中低频带」。
修法见 §4。

---

## 2. 现有 RMS 提取路径 / 采样率 / 缓冲大小

### 2.1 数据通路（**【事实】**）

```
ExoPlayer 解码输出
   └─ DefaultAudioSink.handleBuffer  →  audioProcessingPipeline.queueInput
        └─ TeeAudioProcessor.queueInput        ← 用户 processors 在链首
             ├─ AudioBufferSink.handleBuffer(readOnly 副本)   ← 我们在这里
             └─ replaceOutputBuffer(n).put(原始 buffer)       ← 音频原样继续
```

证据：`VisualizerRenderersFactory.kt:66-84`（构造 tee 并塞进 `DefaultAudioSink`）；
`TeeAudioProcessor.queueInput` 的字节码（javap，media3 1.5.0）逐字是
「`remaining()` → `handleBuffer(createReadOnlyByteBuffer(buf))` → `replaceOutputBuffer(n)` → `put`」，
**没有 try/catch、没有切块、没有缓冲**。

### 2.2 采样率与声道数（**【事实】**）

来自 media3 的 `AudioBufferSink.flush(sampleRateHz, channelCount, encoding)`
（`TransparentWaveformSink.kt:155-166` 保存它们）。**代码不假设任何固定值。**

### 2.3 缓冲大小（**【实测】**，见 [EVIDENCE.md](./EVIDENCE.md) 的 `PROBE-AUDIO-TAP` 段）

`TeeAudioProcessor` 原样透传上游的块 ⇒ 一次回调多少帧**是运行时行为**，代码里没有常量能回答它。
仪器探针 `app/src/androidTest/java/com/takahashirinta/ncrust/probe/AudioTapProbeTest.kt`
用自建 WAV + 真实 ExoPlayer 播放，在 sink 里用**预分配直方图**统计帧数（零分配、不加锁）：

| 设备 | 请求格式 | 实测采样率/声道/编码 | 回调率 | 平均每缓冲帧数 | 平均每缓冲时长 |
|---|---|---|---|---|---|
| （见 EVIDENCE.md，探针输出原样抄录） | | | | | |

**这条数字决定了瞬态检测能不能用「每缓冲一次判定」**，所以 v3.0.0 的判据**不依赖它**：
时间基准一律由「样本数 ÷ 声道数 ÷ 采样率」现算（`AudioFeatureExtractor.frameMs`，
`AudioFeatureExtractor.kt:355-357`），基线系数按 `dt` 现算
（`baselineCoefficient`，`AudioFeatureExtractor.kt:560-564`），冷却按**毫秒**而不是「几个缓冲」。

---

## 3. 无 FFT 前提下能不能做多频段能量？——**能，但必须如实说明它不是频谱**

### 3.1 拓扑（**【事实】**）

```
           x ──┬────────────────────────────────────────────▶ full（全带 RMS）
               │
               ├─ LP(150) ──┬──────────────────────────────▶ low  （低频带）
               │            │
               ├─ LP(2k) ───┼── (− LP(150)) ───────────────▶ mid  （中频带 = 带通 150..2k）
               │            │
               └────────────┴── (x − LP(2k)) ──────────────▶ high （高频带 = 高通 2k）
```

- **带通 = 两个低通之差**、**高通 = x − LP**：两条都是标准构造（全极点一阶滤波器组）。
- 只有**两个**低通状态；`low` 用的就是 v2.9.0 那条 150 Hz 通道的**同一条递推**
  （`LOW_CUTOFF_HZ` 直接引用 `PcmRms.BASS_CUTOFF_HZ`，不是复制数值：
  `AudioFeatureExtractor.kt:484`）⇒ 「柱状图的低频」与「节拍判据的低频」不可能分叉。
- **单次遍历**：`process` 一个循环里算完全部六个量（`AudioFeatureExtractor.kt:294-410`）。

### 3.2 逐样本恒等式（**【事实】**，单测覆盖）

`low + mid + high == x` **逐样本精确成立**（代数恒等式：`sLow + (sMid−sLow) + (x−sMid) = x`）。
这不是「能量守恒」——能量**不**守恒。它证明的是「没有分帧、没有窗函数」：
任何分帧的频谱方案都不满足这条逐样本恒等式。
单测：`AudioFeatureExtractorTest.三频带逐样本精确重构输入`。

### 3.3 泄漏有多大（**【实测】**，JVM 数值复算）

同一个振幅 0.8 的正弦，三个频带的 RMS 读数：

| 输入 | full | low | mid | high |
|---|---|---|---|---|
| 60 Hz | 0.566 | **0.525** | 0.194 | 0.015 |
| 150 Hz（低频带边界） | 0.566 | 0.400 | 0.369 | 0.037 |
| 1 kHz | 0.566 | 0.084 | **0.463** | 0.219 |
| 8 kHz | 0.566 | 0.011 | 0.134 | **0.474** |

**怎么读这张表**：
- 每个频带在**自己的中心频率上占主导**（60Hz 时 low 是 mid 的 2.7 倍、8kHz 时 high 是 mid 的 3.5 倍）；
- 但泄漏**很大**：60Hz 的信号在中频带上还剩 **37%**（0.194/0.525）。
  根因是一阶滤波器只有 −6 dB/oct，而「两个近似同幅、相位差 21.8° 的向量相减」得到的差
  不会很小（复数域相减，不是幅度相减）。
- **所以它不是频谱**：不能报「这个频率上有多少能量」，不能拿三带做归一化基准。
  `VisualizerEffects.spectrumColoring` 因此**仍然恒为 false**（铁律 28 未翻案），
  画面上的柱子仍然是**时间轴**。

### 3.4 频谱质心的近似（**【事实】**）

真频谱质心 = 幅度加权的平均频率，需要频谱。v3.0.0 的做法是
**三个频带的代表频率做幅度加权平均，再按 `ln` 映射到 `[50 Hz, 16 kHz]` → `[0,1]`**
（`centroidOf`，`AudioFeatureExtractor.kt:643-657`；代表频率
`75 / 1000 / 6000 Hz`，`AudioFeatureExtractor.kt:498-500`）。

- 单调性由单测钉住（`AudioFeatureExtractorTest.质心随高频占比单调上升`）；
- **不得报 Hz**：三个代表频率是**约定值**，不是测量值。KDoc 里写死了这条边界；
- 全零 / NaN / 负数一律返回 0（不是 NaN —— NaN 进了 `Color` 会让整条波形静默消失，
  而且**不会抛异常**）。

---

## 4. 音频线程约束（**【事实】**）

| 约束 | 实现 | 证据 |
|---|---|---|
| 不得在音频线程读盘 | 开关量是进程内 `@Volatile` 镜像（`WaveformStore.enabled` / `motionFeaturesEnabled`） | `AudioVisualizer.kt:160-172` |
| 不得分配 | 状态全部在 `configure` 时分配（`Array(8) { DoubleArray(2) }` = 128 B）；`process` 里零分配（无装箱 / 无 lambda / 无集合 / 无字符串） | `AudioFeatureExtractor.kt:194-206` |
| 不得重计算 | 一次遍历算完六个量；**没有第二遍扫描、没有分帧、没有 FFT** | `AudioFeatureExtractor.kt:294-410` |
| 异常必须隔离 | 「提取 + 三个回调」在**同一个** try 里；失败只丢这一根柱 | `TransparentWaveformSink.kt:183-233` |
| 关掉开关 = 零开销 | **两个**开关都关时，回调只剩两次 volatile 读 | `TransparentWaveformSink.kt:186-188` |
| 每声道状态（v3.0.0 修正） | `filterState` 的第二维 = 声道（上限 8） | `AudioFeatureExtractor.kt:194-206`、`MAX_CHANNELS`（`:547`） |

### 4.1 「两个开关」为什么必须分开（**【事实】**）

v2.9.0 只有「音频可视化」一个开关，而界面动效的节拍数据与波形**共用**它 ⇒
**关掉音频可视化会把背景呼吸、节拍脉冲、粒子一起静默掐掉**，设置页里那个开关一个字都没提。
v3.0.0 拆成两条：`WaveformStore.enabled`（画不画波形）与
`WaveformStore.motionFeaturesEnabled`（界面动效要不要特征），
后者由 `MotionPrefs.refresh()` 按 `MotionEffects.needsAudioFeatures` 写入（**唯一写点**）。
单测：`TransparentWaveformSinkTest.motion features keep running when only the visualizer is off`。

---

## 5. 失败即降级（**【事实】**）

`AudioFeatureExtractor.process` 返回 `false` 的三种情形：
① 采样率未知（`configure(0, …)`）；② 编码不支持（24bit 整数等）；③ 缓冲不足一个样本。

此时 `TransparentWaveformSink` 回落到 `PcmRms.analyze`（**RMS-only，v2.9.0 的行为**），
并且**明确告诉消费方 `available = false`**（`TransparentWaveformSink.kt:223-228`）——
而不是把「测不到中高频」伪装成「中高频能量为零」。

- 波形那一侧：仍然出一根柱（只有 RMS + 低频）；
- 界面动效那一侧：`MotionEnvelope` / `MotionBackdropState` 回落到**内置判据**
  （低频通道的突变 + 冷却），用户看到的是「动效退回 v2.9.0」，不是「动效没了」。
- 单测：`TransparentWaveformSinkTest.unknown sample rate falls back to rms only`。

---

## 6. 明确不做什么（避免下一个人重复调研）

| 不做 | 理由 |
|---|---|
| **真 FFT** | 铁律 28 未翻案。三频带包络已经能支撑任务书 §4.2 的全部绑定；FFT 要么把逐样本/分帧计算搬上音频线程，要么新增 PCM 环（重做数据层）。见 `ref-research/audio-feature-extraction.md` 的裁决。 |
| **`android.media.audiofx.Visualizer`** | 需要 `RECORD_AUDIO` 运行时权限；`session 0` 抓的是**输出混音**（不是本应用自己的流）；`getFft` 是 8-bit 幅度、capture size 必须是 2 的幂。与项目定位（零权限、旁路自己的解码输出）冲突。 |
| **按频段分色画一排柱子** | 见 §3.3 的泄漏表：那会被读成频谱，而它不是。 |
| **固定周期的"假反应"** | 铁律 25。所有随时间变化的量都必须能追到某个音频特征（`MotionBindingsTest` 的
`静音输入下所有随时间变化的量都收敛到零` 是这条的可执行判据）。 |

---

## 7. 未验证项（**不许含糊**）

1. **音频线程的真实增量**：仪器探针量的是 `process` 的**纯算术**墙钟时间（见 EVIDENCE.md 的
   `PROBE-FEATURE-COST`），**不是**在真实播放链路里的额外延迟/欠载率。真机播放下的
   underrun 计数（`dumpsys media.audio_flinger`）本轮**未采集**。
2. **6 声道曲目的端到端验证**：单测覆盖了「6 声道不放大截止频率」的**纯数学**，
   但没有拿一首真实的 6 声道 FLAC（QQ 臻品档）走完整链路。
3. **质心的听感校准**：0..1 的映射是约定值，没有做过「用户觉得这个亮度对不对」的 A/B。
4. **`ONSET_PEAK_RATIO = 1.6` / `ONSET_COOLDOWN_MS = 90` 的真机 A/B**：
   数值来自合成轨道 + 文献量级，**没有**在多种真实曲风（古典 / 电子 / 说唱）上做过矩阵实测。
