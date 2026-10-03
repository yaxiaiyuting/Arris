# 无 FFT 的音频特征提取（一阶滤波器组 / 质心近似 / 瞬态）—— 参考资料与选型

> **本文是参考资料（ref-research），不是实测报告。**
> 除仓库内证据外，所有外部结论都在 2026-09-27 当场抓取过原文，引用块里是**原文逐字**（英文原文 + 中文转述）。
> 抓不到的一律进 §6「未核实」集中列表，**不当论据用**。
>
> 本文服务的实现（工作区当前状态）：
> [`AudioFeatureExtractor.kt`](app/src/main/java/com/takahashirinta/ncrust/player/AudioFeatureExtractor.kt)（**未提交**，483 行）、
> [`TransparentWaveformSink.kt`](app/src/main/java/com/takahashirinta/ncrust/player/TransparentWaveformSink.kt)（工作区已改）、
> [`AudioVisualizer.kt`](app/src/main/java/com/takahashirinta/ncrust/ui/player/AudioVisualizer.kt)、
> [`WaveformRing.kt`](app/src/main/java/com/takahashirinta/ncrust/ui/player/WaveformRing.kt)、
> [`WaveformEffectsState.kt`](app/src/main/java/com/takahashirinta/ncrust/ui/player/waveform/WaveformEffectsState.kt)、
> 探针 [`AudioTapProbeTest.kt`](app/src/androidTest/java/com/takahashirinta/ncrust/probe/AudioTapProbeTest.kt)（**尚未在真机跑过** —— `docs/verification/v3.0.0/probe/` 不存在）。

---

## 0. 结论摘要（TL;DR）

| # | 结论 | 依据 |
|---|---|---|
| 1 | **一阶（单极点）递推 `y += a·(x−y)` 就是** `y[n] = a·x[n] + (1−a)·y[n−1]`，`H(z) = a / (1 − (1−a)z⁻¹)`，极点 `z = 1−a`，`0 < a ≤ 1` 时稳定，DC 增益恰为 1。 | §1.1；[EarLevel](https://www.earlevel.com/main/2012/12/15/a-one-pole-filter/)、[JOS](https://ccrma.stanford.edu/~jos/fp/One_Pole.html) |
| 2 | `a = 1 − exp(−2π·fc/fs)` 是**冲激不变近似**；`a = dt/(RC+dt)` 是**后向差分近似**；两者都近似，精确的 −3 dB 解是 `a = −y+√(y²+2y), y = 1−cos(ωc)`。**在本项目用的 150 Hz / 2 kHz 上，`exp` 形式的 −3 dB 点误差 ≤ 0.7%，`RC` 形式在 2 kHz 上偏低 11%（−3 dB 点 1769 Hz）** —— 现行代码用 `exp` 形式是对的。 | §1.2；[DSP SE 54086 采纳答案](https://dsp.stackexchange.com/questions/54086/single-pole-iir-low-pass-filter-which-is-the-correct-formula-for-the-decay-coe) |
| 3 | **带通 = 两个低通之差**、**高通 = `x − 低通`** 都成立，但都是**复数域相减**：只有线性相位（FIR）滤波器相减时幅度谱才等于幅度谱之差；一阶 IIR 相减得到的是"形状像带通"的滤波器，**不是**干净的频带划分。一阶只有 **−6 dB/oct**，频带泄漏极大。 | §1.3；[DSP SE 89388](https://dsp.stackexchange.com/questions/89388/given-two-low-pass-digital-iir-filters-find-bandpass-coefficients)、[DSP SE 21903](https://dsp.stackexchange.com/questions/21903/is-a-high-passed-signal-the-same-as-a-signal-minus-a-low-passed-signal)、[DSP SE 3182](https://dsp.stackexchange.com/questions/3182/is-it-correct-to-subtract-a-low-pass-filtered-signal-from-the-original-signal-an) |
| 4 | 三个频带信号**逐样本精确重构输入**（`low + mid + high = x`，代数恒等式），但**能量不守恒**（交叉项）——所以「三带能量条」不能相加得到全带能量，也不能叫频谱。数值见 §1.6 泄漏表。 | §1.3/§1.6；[Parseval 定理](https://en.wikipedia.org/wiki/Parseval%27s_theorem)（频域才守恒） |
| 5 | **⚠️ 本次新发现（数值仿真）：现行实现的低通状态是「跨声道共享的一个标量」，在交错 PCM 上使有效截止频率 × 声道数。** 标称 150 Hz 在单声道是 150 Hz、立体声（左右相关）是 **300 Hz**、6 声道是 **901 Hz**；标称 2 kHz 在 6 声道上变成 **18.2 kHz**（"高频带"几乎为空）。这是 v2.9.0 就存在的行为（不是 v3.0.0 引入），并且直接影响「低频只抓鼓/贝斯、不抓人声」这一论据。修法与单测见 §1.7 / §7 R1。 | 本报告数值仿真（脚本可复现，见 §1.7）；仓库 6 声道 FLAC 证据见 [`TransparentWaveformSink.kt`](app/src/main/java/com/takahashirinta/ncrust/player/TransparentWaveformSink.kt) 档头 |
| 6 | **真·频谱质心需要频谱**（`Σ fₖ·S[k,t] / Σ S[j,t]`）。**时间域只有代理量**：高/低能量比、ZCR、三带代表频率加权。ZCR 与"亮度/打击性"的相关性有文献支持（MPEG-7 那一条**未核实**），"ZCR ∝ 主导频率"对**单正弦**是恒等式 `ZCR = 2f₀/fs`（自证），但**不是**质心的通用替代。 | §2；[librosa.spectral_centroid](https://librosa.org/doc/latest/api/generated/librosa.feature.spectral_centroid.html)、[librosa.zero_crossing_rate](https://librosa.org/doc/latest/api/generated/librosa.feature.zero_crossing_rate.html)、[Gouyon DAFx-00](https://www.francoispachet.fr/wp-content/uploads/2021/01/gouyon-00-dafx00.pdf) |
| 7 | 现行 `centroidOf()`（三带幅度加权代表频率 75/1000/6000 Hz，再 ln 映射到 50–16000 Hz → 0..1）**不是**质心测量值，只是**单调亮度代理**；这一点代码 KDoc 已经写明，本文补充：代表频率是约定值、三带幅度本身来自 −6 dB/oct 滤波器（泄漏使"三带幅度"本身不等于三个真实频带的能量）。**必须靠单测钉住单调性与零输入保护**。 | §2.4/§2.5 |
| 8 | 瞬态检测的经典配方 = **检测函数 → 自适应阈值（对检测函数的滑动均值/中值）→ 峰值挑选 → 最小间隔（冷却）**。Dixon 的自适应阈值本身就是**对检测函数的一阶低通**：`gα(n) = max(f(n), α·gα(n−1) + (1−α)·f(n))`。绝对阈值跨曲目不可用；在线（不允许未来信息）时不能用全长归一化。 | §3.1/§3.3；[Dixon DAFx-06](https://dafx.de/paper-archive/2006/papers/p_133.pdf)、[Böck ISMIR 2012](http://ismir2012.ismir.net/event/papers/049_ISMIR_2012.pdf)、[librosa.util.peak_pick](https://librosa.org/doc/latest/api/generated/librosa.util.peak_pick.html) |
| 9 | 冷却窗口的**下界**是听感分辨极限（有文献取 **30 ms**）；冷却是**时间**不是缓冲计数（缓冲粒度是运行时行为）。**现行 `ONSET_COOLDOWN_MS = 180 ms` ⇒ 最多 5.56 次/秒**，而 120 BPM 的八分音符是 8 次/秒、180 BPM 的四分音符是 12 次/秒 ⇒ **快节奏会合并触发**。是否可接受取决于视觉（涟漪）预算，**必须实测后定**。 | §3.6；[Turchet DAFx-18](https://dafx.de/paper-archive/2018/papers/DAFx2018_paper_51.pdf) |
| 10 | **⚠️ 本次新发现（算术）：基线用固定每缓冲步长 `BASELINE_STEP = 0.026` ⇒ 时间常数随缓冲时长线性漂移。** `dt=21 ms → τ≈0.80 s`（与注释一致），但 `dt=46.4 ms → 1.76 s`、`dt=92.9 ms → 3.53 s`（4096 帧 @44.1 kHz 的情形）。代码注释写的"0.6~1.6 倍"与实际（0.47~2.37 倍）不符。修法：`k = 1 − exp(−dt/τ)`（每缓冲一次 `exp`）。 | §3.5；本报告算术（`τ = −dt/ln(1−k)`） |
| 11 | 音频线程的契约（media3 源码级）：`TeeAudioProcessor.queueInput` **同步**调用 `sink.handleBuffer(...)`，**没有 try/catch** ⇒ sink 抛出的异常会顺着 `queueInput` 走回播放链路（v2.2.1 的级联形状）。`AudioProcessor.queueInput` 的输入被文档保证为 **native byte order + 只读**。 | §4.1/§4.2；[TeeAudioProcessor.java](https://raw.githubusercontent.com/androidx/media/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/TeeAudioProcessor.java)、[AudioProcessor 参考](https://developer.android.com/reference/androidx/media3/common/audio/AudioProcessor)、[ExoPlayer 线程模型](https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer) |
| 12 | `android.media.audiofx.Visualizer` **要 `RECORD_AUDIO`**（官方原文如此），`getFft()` 给的是 **8-bit 幅度 FFT**，capture size 必须是 2 的幂；跨应用抓音还要 `MediaProjection` 用户授权 + 同 user profile。**在"自己的 PCM 已经在手上"的前提下，它只有坏处没有好处。** | §4.3；[Visualizer](https://developer.android.com/reference/android/media/audiofx/Visualizer)、[播放捕获指南](https://developer.android.com/guide/topics/media/playback-capture) |
| 13 | 现行实现的每样本代价 ≈ **6 乘 + 10 加减 + 1 除 ≈ 17 flop/样本**（v2.9.0 的 ≈8 flop/样本）。44.1 kHz 立体声 ≈ **1.50 Mflop/s**，6 声道 ≈ **4.50 Mflop/s** —— 量级上无关紧要，**真正的风险是音频线程上的分配/阻塞，不是吞吐**；但这些数字**必须由真机实测确认**。 | §5 |
| 14 | 缓冲粒度（每回调多少帧、每秒几次）**必须实测**：`TeeAudioProcessor` 原样透传上游块，代码里没有常量能回答。工作区已有探针（`AudioTapProbeTest`），**但还没有跑过**。4096 帧（≈93 ms）与 1024 帧（≈23 ms）都在真实可能范围内，两者对瞬态检测是**可用与不可用**的区别。 | §3.4；[TeeAudioProcessor.java](https://raw.githubusercontent.com/androidx/media/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/TeeAudioProcessor.java) + 工作区探针 |

---

## 1. 一阶 IIR 滤波器组（无 FFT 的多频段能量）

### 1.1 递推式、传递函数、极点、稳定性、DC 增益

一阶（单极点）低通的**直接 I 型**信号流图只有一个反馈支路。EarLevel 的实现是（原文）：

> Specifically, _b1_ = _-e_<sup>-2πFc</sup> and _a0_ = 1 – |_b1_|. (In the implementation, we'll roll the minus sign in the summation into _b1_ and make it positive, for convenience.)
> —— [A one-pole filter · EarLevel Engineering](https://www.earlevel.com/main/2012/12/15/a-one-pole-filter/)

```c
inline float OnePole::process(float in) { return z1 = in * a0 + z1 * b1; }
```

也就是本仓库写的那一行（[`PcmRms.analyze`](app/src/main/java/com/takahashirinta/ncrust/player/TransparentWaveformSink.kt)、[`AudioFeatureExtractor.process`](app/src/main/java/com/takahashirinta/ncrust/player/AudioFeatureExtractor.kt)）：

```
y += a * (x - y)        ≡        y[n] = a·x[n] + (1−a)·y[n−1]
```

JOS 给出一般式、传递函数与极点位置：

> Difference equation: `y(n) = b0 x(n) - a1 y(n-1)`；Transfer function: `H(z) = b0 / (1 + a1 z⁻¹)`；
> The filter has a pole at `z = -a1` … The lowpass character occurs when the pole is near the point `z = 1` (dc), which happens when `a1` approaches `-1`.
> —— [One-Pole · JOS (CCRMA)](https://ccrma.stanford.edu/~jos/fp/One_Pole.html)

对照本仓库的参数：`b0 = a`、`a1 = −(1−a)` ⇒ **极点 `z = 1 − a`**。因此

- `0 < a < 1` ⇒ 极点在单位圆内 ⇒ **稳定**（`a = 1` 时极点在原点，退化成 `y = x`，即"不滤波"—— 这正是本仓库采样率未知时的退化值，见下）；
- `a < 0` 或 `a > 1` 都不会被产生：现行 `lowPassCoefficient()` 把结果 `coerceIn(0.0, 1.0)`；且 `sampleRateHz <= 0` 时直接返回 1.0。

DC 增益为 1 是这套系数写法的**定义性质**。EarLevel 作者在评论里给了推导：

> To adjust for a gain of 1 at DC (0 Hz), we set the input multiplier to 1 – that value.
> （前文：`E = E0·exp(-T/RC)`，`Fc = …`，`E = E0·exp(-2πFc/Fs)`）
> —— [同页评论区（作者回复 Dario Sanfilippo）](https://www.earlevel.com/main/2012/12/15/a-one-pole-filter/#comment-53294)

代数验证：`H(1) = a / (1 − (1−a)) = a / a = 1`。**这一条很重要**：它意味着低频带的"幅度"不会被滤波器人为放大或缩小，`low`/`mid`/`high` 三个标量之间才具备可比性（都用同一个输入尺度）。

> ⚠️ 上面评论里的 `Fc = 0.5πRC` 是博客里的笔误（应为 `1/(2πRC)`）。正确关系用维基的原文：
> "…where `ω0 = 1/RC` is the cutoff frequency of the filter." —— [Low-pass filter · Wikipedia](https://en.wikipedia.org/wiki/Low-pass_filter)。
> 本文所有 `τ = 1/(2π·fc)` 的计算都用这一条。

### 1.2 系数 `a` 从截止频率与采样率推导（三种写法，含误差）

DSP StackExchange 上被采纳的答案把这件事讲得最全（原文公式，变量 `ωc` 已按采样率归一化）：

> `y[n] = α x[n] + (1−α) y[n−1]`；`H(z) = α / (1 − (1−α) z⁻¹)`
> The exact formula for the required value of α that results in a desired 3 dB cut-off frequency ωc was derived in this answer: `α = −y + √(y² + 2y), y = 1 − cos(ωc)`
> One of them is `α ≈ 1 − e^{−ωc}` … it is shown that (4) is only useful for relatively small cut-off frequencies (of course, small compared to the sampling frequency).
> … yet another approximative formula: `α ≈ ωc / (1 + ωc)` … derived … by replacing the derivative by a backward difference … Comparing (8) to (2) we see that `α = 1/(1 + τ/T)`. Since the (continuous-time) 3 dB cut-off frequency is `Ωc = 1/τ` …
> —— [Single-pole IIR low-pass filter — which is the correct formula for the decay coefficient? · DSP SE（采纳答案，Matt L.）](https://dsp.stackexchange.com/questions/54086/single-pole-iir-low-pass-filter-which-is-the-correct-formula-for-the-decay-coe)

三种写法与本项目的对应关系（`ωc = 2π·fc/fs`，`dt = 1/fs`，`τ = RC = 1/(2π·fc)`）：

| 写法 | 公式 | 来源 | 性质 |
|---|---|---|---|
| 指数 / 冲激不变 | `a = 1 − exp(−2π·fc/fs)` | 上述 (4)；EarLevel 代码 | 现行实现采用；`a` 恒在 (0,1) |
| RC / 后向差分 | `a = dt/(RC+dt) = ωc/(1+ωc)` | 上述 (5)(6)(9) | 等价于把 `s` 换成 `(1−z⁻¹)/T`；**−3 dB 点偏低** |
| 精确 −3 dB | `a = −y+√(y²+2y), y = 1−cos(ωc)` | 上述 (3) | "精确"指的是 −3 dB 点精确，代价是 `cos/sqrt` |

**本报告算出的数值（可直接写成 JVM 单测复核；脚本见 §7 R4 的用例 1）：**

| fs | fc | `a_exp` | 精确 `a`（−3 dB） | 相对误差 | `a_rc` | `a_exp` 的实际 −3 dB 点 | `a_rc` 的实际 −3 dB 点 |
|---|---|---|---|---|---|---|---|
| 44100 | 150 Hz | 0.021145 | 0.021144 | **+0.00 %** | 0.020924 | 150.0 Hz | 148.4 Hz |
| 44100 | 2000 Hz | 0.247949 | 0.246513 | **+0.58 %** | 0.221761 | 2013.7 Hz | **1769.0 Hz（−11.5 %）** |
| 48000 | 150 Hz | 0.019443 | 0.019443 | +0.00 % | 0.019257 | 150.0 Hz | 148.6 Hz |
| 48000 | 2000 Hz | 0.230335 | 0.229193 | +0.50 % | 0.207481 | 2011.5 Hz | 1784.5 Hz |

结论：**现行代码用 `exp` 形式是正确选择**（DSP SE 说它"只在小截止频率下有用"，本项目 2 kHz/44.1 kHz = 0.045·fs 仍属"小"）；如果换成网上常见的 `dt/(RC+dt)`，2 kHz 这条边界会静默下移到 ≈1.77 kHz，而画面上看不出来。

**稳定性/退化约定（现行代码，需保留）：**

- `sampleRateHz <= 0 || cutoffHz <= 0` ⇒ 返回 `1.0` ⇒ `y = x` ⇒ "不滤波"。调用方（`AudioFeatureExtractor.configure`）据此把 `available` 置 false 并降级到 RMS-only —— 这是"宁可退化，也不要拿猜出来的采样率滤波"的正确姿势。

### 1.3 带通 = 两个低通之差；高通 = `x − 低通`

两条构造法都有可引用的出处：

**高通 = 输入 − 低通输出**（一阶 DC 阻断器的标准做法）：

> However, we can still make a highpass filter suitable for a DC blocker by subtracting the output of a one-pole lowpass filter, set to a low frequency, from the direct signal.
> —— [EarLevel](https://www.earlevel.com/main/2012/12/15/a-one-pole-filter/)

**带通 = 两个低通之差**（FIR 情形下众所周知；IIR 情形有明确的相位前提）：

> In the case of FIR filters it is easy to get a band-pass filter by subtracting the coefficients of two low-pass filter filters …
> It's true that only impulse responses of linear phase (FIR) filters can be added or subtracted (if the delays are aligned) such that the resulting filter's magnitude response equals the sum or difference of the individual magnitude responses. **This generally doesn't work for non-linear phase filters.**
> —— [Given two low-pass digital IIR filters, find bandpass coefficients · DSP SE](https://dsp.stackexchange.com/questions/89388/given-two-low-pass-digital-iir-filters-find-bandpass-coefficients)

**必须写进文档的诚实边界（三条，都有出处）：**

1. **相减是复数域相减，不是幅度相减。** `H(ω) = 1 − H_LP(ω)` 与人们想要的 `|H| = |1 − |H_LP||` 不是一回事；只有线性相位时两者一致。
   > In general you can't simply subtract a low-pass filtered version of a signal from the original one to obtain a high-pass filtered signal. … What you're actually doing is implement a system with frequency response `H(ω) = 1 − H_LP(ω)`. Note that `H_LP(ω)` is a complex function. What you probably want is `|H(ω)| = |1 − |H_LP(ω)||` but this is generally not the case …
   > —— [Is a high-passed signal the same as a signal minus a low-passed signal? · DSP SE（采纳答案，Matt L.）](https://dsp.stackexchange.com/questions/21903/is-a-high-passed-signal-the-same-as-a-signal-minus-a-low-passed-signal)
2. **相位没对齐就会有构造性/破坏性干涉同时出现**，但在"只滤掉很低的频率"时影响可接受。
   > In theory you can do this, but in practice it is difficult to do because the time and phase alignment must be pretty good for it to work. … It can work, though, if you are only filtering out fairly low frequencies, since their timing requirements are the loosest because they change so slowly.
   > —— [DSP SE 3182](https://dsp.stackexchange.com/questions/3182/is-it-correct-to-subtract-a-low-pass-filtered-signal-from-the-original-signal-an)
   EarLevel 作者对一阶情形的判断同样是"相位不理想但无所谓"：
   > But your sense that we don't want to add and subtract filter outputs without regard to phase, normally, is correct (particularly with second order and up). But in this case, there is no perceptible penalty.
   > —— [EarLevel 评论区](https://www.earlevel.com/main/2012/12/15/a-one-pole-filter/#comment-59176)
3. **单个一阶节本身做不出带通**（这点常被误读，必须说清：带通来自**两个一阶低通相减**，即 2 极点 2 零点的组合，不是"一个一阶节"）：
   > with a single pole, you are not going to get complex response curves such as bandpass, peak, and shelving filters that you can get with the two poles and zeros of a biquad.
   > 以及作者回答 "How to make one pole bandpass?" —— "Good question. **You can't.**"
   > —— [EarLevel 正文与评论区](https://www.earlevel.com/main/2012/12/15/a-one-pole-filter/)

**一个只有代数才能给出的补充（本文推导，非引文）：** 现行实现的三带是

```
low  = sLow                    （LP150 的输出）
mid  = sMid − sLow             （LP2000 − LP150）
high = x − sMid                （输入 − LP2000）
```

三者**逐样本精确相加等于输入**：`low + mid + high = sLow + (sMid − sLow) + (x − sMid) = x`。这有两个直接后果：

- **好处**：三带幅度不会同时失真（重构恒等式成立），`mix = (mid+high)/(low+mid+high)` 这类比值在数值上是稳的；
- **陷阱**：**能量不守恒**。`Σx² ≠ Σlow² + Σmid² + Σhigh²`（交叉项 `2·low·mid` 等被丢掉）。频域里能量才按频带可加：
  > The interpretation of this form of the theorem is that the total energy of a signal can be calculated by summing power-per-sample across time or spectral power across frequency.
  > —— [Parseval's theorem · Wikipedia](https://en.wikipedia.org/wiki/Parseval%27s_theorem)
  实测（数值仿真，见 §1.6）：60 Hz 正弦的三带 RMS 平方和开方 = 0.7001，而输入 RMS = 0.7071；6 kHz 正弦是 0.6179 vs 0.7071。**偏差不是常数**，所以"三带能量归一化后当频谱显示"是错的。

### 1.4 累加什么：均方 → 开方（RMS）

RMS 的定义就是"均方根"：

> the root mean square (abbrev. RMS) of a set of values is the square root of the set's mean square.
> —— [Root mean square · Wikipedia](https://en.wikipedia.org/wiki/Root_mean_square)

对逐样本流，最省的累加器是"平方的累加"（每样本 1 乘 1 加），最后**每缓冲只开一次平方根**（现行实现对 4 个量各开一次 `sqrt`，即每缓冲 4 次）。librosa 的文档也把"从样本直接算 RMS"标为更省的那条路：

> Computing the RMS value from audio samples is faster as it doesn't require a STFT calculation.
> —— [librosa.feature.rms](https://librosa.org/doc/latest/api/generated/librosa.feature.rms.html)

**为什么不能"先开方再平均"**：`mean(|x|)` 不是 RMS；对带通输出取绝对值均值会随波形（正弦/脉冲）改变含义，而 RMS 对定标是线性的、对同频不同波形稳定。这是定义问题，不是性能问题。

**多声道聚合语义（现行实现，需明确写进文档）**：`sumFull/sumLow/…` 对**交错流里所有样本**求和，再除以样本总数 ⇒ 得到的是"所有声道合并后的一个 RMS"（与 v2.2.1/v2.9.0 的语义一致），不是"每声道 RMS 的平均"。两者在左右电平不同的曲目上结果不同（前者是能量平均，后者是幅度平均）。**这是有意的**（可视化只需要一个数值），但不要在文档里说成"每声道 RMS"。

### 1.5 群延迟与相位：一阶滤波器的"频带"到底意味着什么

群延迟的定义与"非线性相位会改变各分量相对相位"的后果：

> A more commonly encountered representation of filter phase response is called the group delay, defined by `D(ω) = −dΘ(ω)/dω`. … If the phase response is nonlinear, then the relative phases of the sinusoidal signal components are generally altered by the filter.
> —— [Group Delay · JOS (CCRMA)](https://ccrma.stanford.edu/~jos/fp/Group_Delay.html)

一阶低通在 DC 附近的群延迟就是时间常数（本文推导 + 上表数值）：`D(0) = (1−a)/a` 样本 ⇒

| 截止 | `D(0)`（44.1 kHz） | `τ = 1/(2π·fc)` |
|---|---|---|
| 150 Hz | 46.29 样本 = **1.050 ms** | 1.061 ms |
| 2000 Hz | 3.03 样本 = **0.069 ms** | 0.080 ms |

**这意味着什么（对特征提取的三条实际后果）：**

1. 低频带的"能量峰值"比真实事件**晚约 1 ms**（150 Hz 一阶），高频带几乎不晚（0.07 ms）。**同一击打在三带上的到达时间不同** ⇒ 用三带做"谁先到"的因果判断是无意义的；用低频带做瞬态触发，会固定晚 ~1 ms（相对缓冲粒度 20–90 ms 可忽略，但要知道它的存在）。
2. 一阶是 **−6 dB/oct**，没有平坦通带：
   > The 6 dB per octave slope of the one-pole filter—a halving of amplitude for every doubling of frequency—is gentle and natural.
   > —— [EarLevel](https://www.earlevel.com/main/2012/12/15/a-one-pole-filter/)
   ⇒ **"分频点"实际是"重心"，不是"边界"**：150 Hz 处的低带只衰减 3 dB，2 kHz 处的高带只衰减 4.3 dB（见 §1.6 表）。
3. **一个极点区分不了底鼓与贝斯**：两者的基频区间重叠（底鼓基频与贝斯 40–200 Hz 在 −6 dB/oct 的裙边下互相渗透），文献里区分这两类打击音用的是 **ZCR + 谱特征 + 分类器**，不是单个能量比：
   > the classification of percussive sounds … two dimensions which, taken alone, allows to differentiate the sounds: ZCR_Decay (zero-crossing rate computed over the decay region) and the StrongestPartialFFT_Decay.
   > —— [Gouyon, Pachet, Delerue, DAFx-00](https://www.francoispachet.fr/wp-content/uploads/2021/01/gouyon-00-dafx00.pdf)
   **所以：本层可以支持"低沉/明亮"的视觉调制与"有击打"的判定，不可以支持"这是底鼓还是贝斯"。** 现行代码档头写的这条边界是对的，本文给出上面这条**外部依据**。

### 1.6 推荐的三频段划分（150 Hz / 2000 Hz）与泄漏实测

**划分建议（= 现行实现，本文给出依据与代价）：**

| 边界 | 依据 | 代价 |
|---|---|---|
| **150 Hz** | 底鼓基频与贝斯低把位落在这一带之下（贝斯 4 弦最低 E1 ≈ **41.20344 Hz**，[Scientific pitch notation · Wikipedia](https://en.wikipedia.org/wiki/Scientific_pitch_notation)）；成年人声基频男 **90–155 Hz**、女 **165–255 Hz**（[Voice frequency · Wikipedia](https://en.wikipedia.org/wiki/Voice_frequency)）—— 150 Hz 尽量把男声基频排除在外 | 男声基频下沿（90–150 Hz）**仍在低带**；且 −6 dB/oct 使 300 Hz 只衰减 7 dB（见下表）⇒ 男声二次谐波会渗进"低频" |
| **2000 Hz** | 人声/吉他基频（≈82–1100 Hz）在其下，弦乐泛音/镲片/齿音在其上；这一"低/中/高"三分也是音频工程与 MIR 教程里最常见的粗分法（本报告不作"某标准规定"的断言） | 2000 Hz 之上到 Nyquist 全部算"高"；**中带在 2 kHz 处只比高带高约 0.6 dB**（−3.69 vs −4.28，见下表）⇒ 在那一段中/高几乎不可分 |

**一带相对幅度表（本报告数值计算；fs = 44100，`a` 用 exp 形式，单声道；单测可直接复核）：**

| 频率 | low（LP150） | mid（LP2000−LP150） | high（x−LP2000） |
|---|---|---|---|
| 40 Hz | −0.30 dB | −12.46 dB | −35.25 dB |
| 60 Hz | −0.64 dB | −9.29 dB | −31.73 dB |
| 100 Hz | −1.60 dB | −5.81 dB | −27.30 dB |
| 150 Hz | −3.01 dB | −3.72 dB | −23.79 dB |
| 300 Hz | −6.99 dB | −1.75 dB | −17.84 dB |
| 1 kHz | −16.57 dB | −1.74 dB | −8.26 dB |
| 2 kHz | −22.49 dB | −3.69 dB | −4.28 dB |
| 4 kHz | −28.41 dB | −7.56 dB | −2.24 dB |
| 14 kHz | −37.91 dB | −16.19 dB | −1.36 dB |

**读法（三条结论）**：

1. 150 Hz 处 low 与 mid 都是 ≈−3 dB —— 这正是"分频点在重心上"的含义；
2. 1 kHz 的 mid = −1.74 dB 而 high = −8.26 dB ⇒ **中/高之间只有 6.5 dB 的对比**，"高频带"不是"2 kHz 以上"的干净通道；
3. 60 Hz 的 mid = −9.29 dB ⇒ **低音鼓/贝斯的能量有一小部分（约 34% 幅度）出现在中带**，`mix` 会被低频乐器轻微抬高。

**单正弦的三带 RMS（幅度 1 的正弦，输入 RMS = 0.7071；本文数值仿真）：**

| 频率 | low | mid | high | √(low²+mid²+high²) |
|---|---|---|---|---|
| 60 Hz | 0.6564 | 0.2427 | 0.0183 | 0.7001（−0.09 dB） |
| 150 Hz | 0.4999 | 0.4610 | 0.0457 | 0.6816（−0.32 dB） |
| 1 kHz | 0.1050 | 0.5787 | 0.2733 | 0.6486（−0.75 dB） |
| 6 kHz | 0.0182 | 0.2131 | 0.5797 | 0.6179（−1.17 dB） |

⇒ **"三带能量和 ≠ 全带能量"在 60 Hz–6 kHz 之间差了 0.1–1.2 dB，且偏差随频率变化**。这既证明了"能量不守恒"，也说明**不能拿三带做归一化基准**（要用全带 RMS 时就用 `sumFull`）。

### 1.7 ⚠️ 新发现：交错 PCM + 单一滤波状态 ⇒ 有效截止频率 × 声道数

**问题**：现行 [`AudioFeatureExtractor.process`](app/src/main/java/com/takahashirinta/ncrust/player/AudioFeatureExtractor.kt)（以及 v2.9.0 的 [`PcmRms.analyze`](app/src/main/java/com/takahashirinta/ncrust/player/TransparentWaveformSink.kt)）只有**一个** `sLow`/`sMid` 标量状态，而循环是**按交错样本**推进的：立体声时序列是 `L,R,L,R,…`，于是每个声道的样本看到的状态是**上一个声道**更新过的。

**后果（本文数值仿真，脚本在 §7 R4 复现；判决量 = 该带输出/输入幅度 = 1/√2 的频率）：**

| 标称截止 | 单声道 | 立体声（L=R） | 3 声道 | 6 声道 |
|---|---|---|---|---|
| 150 Hz | 150.0 Hz（1.00×） | **300.0 Hz（2.00×）** | 450.2 Hz（3.00×） | **901.2 Hz（6.01×）** |
| 2000 Hz | 2014 Hz（1.01×） | **4113 Hz（2.06×）** | — | **18211 Hz（9.11×）** |

同一信号出现在 n 个声道时，每帧递推 n 次 ⇒ 等效系数 `1−(1−a)ⁿ ≈ n·a` ⇒ 有效截止 ≈ n×标称（小 `a` 情形；`a` 大时偏离，所以 6 声道 2 kHz 是 9.11× 而不是 6×）。

**为什么这件事在本仓库特别要紧：**

1. **主流内容就是立体声**（ncm/QQ 的常规档位）：标称 150 Hz 的"低频带"实际 ≈300 Hz —— 而成年人声基频 90–155 Hz 的**二次谐波 180–310 Hz 正好落在里面**。v2.9.0 文档里"人声与旋律重音不在这一带"的说法，在**立体声下不成立**；
2. **本仓库确实会收到 6 声道 FLAC**：QQ「臻品音质/臻品全景声」档实测回 6 声道 FLAC（见 [`TransparentWaveformSink.kt`](app/src/main/java/com/takahashirinta/ncrust/player/TransparentWaveformSink.kt) 档头与 `docs/verification/v2.2.1/p0-quality-loop/PROBE.md`）。6 声道下 2 kHz 的等效截止跑到 18.2 kHz ⇒**"高频带"几乎为空、"中频带"吞掉一切**，三带特征在那类曲目上会整体失真（不崩、不报错、画面上看不出来）；
3. 这是**数值语义**问题，不是性能问题：修法不增加每样本成本（见 §7 R1）。

**修法（两条，任选，推荐第 1 条）：**

1. **每声道一个状态**：`filterState = DoubleArray(channelCount * 2)`（`channelCount` 在 `configure` 时已知，上限取 8，超出则只分析前 8 个声道或退化为第 1 个声道），循环里维护 `ch` 索引（`ch++; if (ch == channelCount) ch = 0`），能量累加仍对全部样本做。**每样本成本不变**（一次乘加），只是把 `filterState[0]` 换成 `filterState[ch]`。
2. **只分析第 1 个声道**（或每 `channelCount` 个样本取一个）：成本降到 1/n，语义变成"以第 1 声道为代表的带能量"。简单、确定，但会漏掉只出现在其他声道的内容（5.1 的环绕声道）。

**回归单测（必须加）**：给一个 150 Hz 正弦的**立体声（L=R）** `FloatArray`，断言修复前有效 −3 dB 落在 300 Hz 附近、修复后回到 150 Hz 附近（容差 ±10%）。这条测试同时把"为什么必须有每声道状态"固定成证据，而不是一句注释。

---

## 2. 频谱质心（spectral centroid）近似

### 2.1 真定义，以及为什么它需要频谱

librosa 的文档给了定义式与出处：

> Each frame of a magnitude spectrogram is normalized and treated as a distribution over frequency bins, from which the mean (centroid) is extracted per frame.
> More precisely, the centroid at frame `t` is defined as: `centroid[t] = sum_k S[k, t] * freq[k] / (sum_j S[j, t])` where `S` is a magnitude spectrogram, and `freq` is the array of frequencies … of the rows of `S`.
> [1] Klapuri, A., & Davy, M. (Eds.). (2007). Signal processing methods for music transcription, chapter 5.
> —— [librosa.feature.spectral_centroid](https://librosa.org/doc/latest/api/generated/librosa.feature.spectral_centroid.html)

**结论**：质心的定义里**显式含 `S[k,t]`（幅度谱）**。要"报出以 Hz 为单位的质心"，就必须有频谱。本任务是"无 FFT"，所以能做的只有**单调代理量（monotone proxy）**——它可以在 [0,1] 上表示"更亮/更暗"，**不能**换算成 Hz。

### 2.2 时间域代理之一：高/低能量比（现行实现走的路）

`ratio = high / low`（或 `mix = (mid+high)/(low+mid+high)`）在**固定"亮"的物理量**上是单调的：高频成分占比上升 ⇒ `mix` 上升、`centroid01` 上升。它的优点是零成本（三带能量已经在手上），缺点是：

- 依赖分带滤波器的形状（§1.6 的泄漏直接进入比值）；
- 对"同一能量的宽窄带噪声"与"纯音"给出不同的值（因为没有频率分辨率）；
- 对总电平敏感（所以必须先归一化，见 §3.3 的 AGC 讨论）。

### 2.3 时间域代理之二：ZCR，以及它与质心的关系边界

**定义（官方文档）**：

> Compute the zero-crossing rate of an audio time series. … `zcr[..., 0, i]` is the fraction of zero crossings in frame `i`.
> —— [librosa.feature.zero_crossing_rate](https://librosa.org/doc/latest/api/generated/librosa.feature.zero_crossing_rate.html)

**"帧内过零数 ÷ 帧长"这一定义在文献里是一致的**：

> It is defined as the number of time-domain zero-crossings within a defined region of signal, divided by the number of samples of that region.
> —— [Gouyon, Pachet, Delerue, DAFx-00](https://www.francoispachet.fr/wp-content/uploads/2021/01/gouyon-00-dafx00.pdf)

**ZCR 是标准的"廉价亮度/打击性"描述量（可引用）**：

> The zero-crossing rate (ZCR) is the rate at which a signal changes from positive to zero to negative or from negative to zero to positive. Its value has been widely used in both speech recognition and music information retrieval, being a key feature to classify percussive sounds.
> —— [Zero-crossing rate · Wikipedia](https://en.wikipedia.org/wiki/Zero-crossing_rate)（该条目的引用正是 Gouyon DAFx-00）
>
> （Gouyon 原文的实验结论：ZCR_Decay 是区分"军鼓类 / 底鼓类"最具判别力的两个参数之一；见 §1.3 引用块。）

**"ZCR ∝ 主导频率"在什么前提下成立（本文推导，非引文）**：对频率 `f₀`、采样率 `fs` 的**单正弦**，一个周期内符号变化 2 次 ⇒ 每秒过零 `2f₀` 次 ⇒

```
ZCR（过零数/样本数） = 2·f₀ / fs        ⇒        f₀ = ZCR · fs / 2
```

这条恒等式只对**窄带/单音**成立。文献侧只支持到这个强度（"primitive pitch detection"）：

> For monophonic tonal signals, the zero-crossing rate can be used as a primitive pitch detection algorithm.
> —— [Zero-crossing rate · Wikipedia](https://en.wikipedia.org/wiki/Zero-crossing_rate)

**关于"ZCR 与谱质心相关"这一说法，本文只给到这个强度**：

- **可引用的部分**：ZCR 与质心都是"亮度/高频占比"的描述量，前者在时域、后者在频域；两者的标准定义见上；
- **未能核实的部分**：**没有**抓到任何给出"ZCR–质心相关系数"的一手文献（见 §6）。因此本报告**不**写"ZCR 可以替代质心"，只写"ZCR 是同一个物理直觉（信号变化快慢）的另一个廉价代理"。

**如果要在 Ncrust 里用 ZCR，正确的用法**（本文建议）：把它当作**独立的第三个亮度代理**，只用于与"三带比"交叉验证/降级，不要与 `centroid01` 混用为同一语义。ZCR 的零成本实现见 §7 R2。

**MPEG-7**：MPEG-7 音频部分（ISO/IEC 15938-4）确实定义了时域低层描述子，零交叉率通常被列为其中之一，但**本次未能抓到可引用的公开页面**（ISO 目录页返回 403），故本文**不把它当论据**，只登记在 §6。

### 2.4 现行实现的质心近似（逐行核对）

[`AudioFeatureExtractor.centroidOf`](app/src/main/java/com/takahashirinta/ncrust/player/AudioFeatureExtractor.kt)：

```kotlin
val weighted = (l*CENTROID_F_LOW_HZ + m*CENTROID_F_MID_HZ + h*CENTROID_F_HIGH_HZ) / denom   // 75 / 1000 / 6000 Hz
val hz = weighted.coerceIn(CENTROID_MIN_HZ, CENTROID_MAX_HZ)                                 // [50, 16000]
val normalized = ln(hz / CENTROID_MIN_HZ) / ln(CENTROID_MAX_HZ / CENTROID_MIN_HZ)            // → 0..1
```

**它是什么**：三个频带幅度对**约定代表频率**的加权平均，再按对数映射到 [0,1]。形式上与真定义同构（"加权平均频率"），但：

| 与真定义的差异 | 后果 |
|---|---|
| 权重只有 3 个（不是每频点） | 分辨率 3 档；三带比不变时质心不变（真质心会变） |
| 代表频率 75/1000/6000 Hz 是**约定值** | 只保证单调，不保证量值；**不能报 Hz** |
| 权重是**带内总幅度**，不是带内频点幅度 | 受 §1.6 泄漏影响（例如 60 Hz 有 34% 幅度进 mid，会把质心抬高） |
| 没有"带内频率分布"的信息 | 一个 1.5 kHz 的纯音与一段 1.5–2 kHz 的噪声给同一个质心 |

**必须保留的两条数值保护（现行代码已有，值得固化成单测）：**

```kotlin
val denom = l + m + h
if (denom <= CENTROID_EPS) return 0f      // 1e-9：静音/全零 ⇒ 0，不是 NaN
if (normalized.isFinite()) ... else 0f    // NaN/Inf ⇒ 0
```

为什么要这么写（本文的实际理由，代码 KDoc 也写了）：**NaN 进入 `Color`/缩放因子不会抛异常，只会让画面静默变透明/全黑**——比崩溃更难查。所有输出都过一遍 `finite01()`（clamp 到 [0,1] + 非有限值归 0）是这套代码里最值得保留的习惯。

### 2.5 从高/低能量比到 `brightness ∈ [0,1]` 的具体单调映射（给实现的两个候选）

设 `low, mid, high ≥ 0` 为三带 RMS（已 clamp/去 NaN），`E = low+mid+high`：

| 方案 | 公式 | 单调性 | 数值保护 | 评价 |
|---|---|---|---|---|
| A（现行 `bandMix`） | `mix = (mid+high)/E` | 对 `high` 严格递增（固定 low/mid），对 `low` 递减 | `E ≤ 1e-9 ⇒ 0` | 无对数、无除零；**推荐作为唯一"明亮度"量** |
| B（对数比，未采用） | `mix = 1 − low/max(E,ε)`，或 `0.5 + atan(ln((mid+high+ε)/(low+ε)))/(2·atan(1)·k)` | 同上 | 需要 `ε` 防 `ln 0` | 动态范围更好，但多一次 `ln`/`atan`，收益不明显 |

**建议**：**保留 A 作为唯一的"明亮度占比"**（`bandMix`），把 `centroidOf` 降级为"仅用于背景缩放/视觉映射"的第二个量，并在文档里明确"两个量都是代理，不得互相换算"。理由：两个语义相近但公式不同的量同时进入画面，会让"亮"的观感难以归因（这是可维护性问题，不是数学问题）。

---

## 3. 瞬态 / onset 检测（无 FFT）

### 3.1 标准配方（可引用的四条）

1. **检测函数（ODF）**：能量的正向一阶差分、HFC（高频内容）、谱通量等。
   > Traditional methods such as high frequency detection rely on the assumption that all note onsets contain high frequency energy … This improves results; however using the same energy content algorithm across all frequency bands is not necessarily the best method. … we propose using a hybrid scheme of transient energy detection in the high frequency subbands …
   > —— [Duxbury, Sandler, Davies, "A Hybrid Approach to Musical Note Onset Detection", DAFx-02](https://dafx.de/paper-archive/2002/DAFX02_Duxbury_Sandler_Davis_note_onset_detection.pdf)
2. **自适应阈值 + 峰值挑选 + 冷却**（三步走的规范形式，librosa 把它写成了三个不等式）：
   > A sample `n` is selected as an peak if the corresponding `x[n]` fulfills the following three conditions: `x[n] == max(x[n-pre_max : n+post_max])`；`x[n] >= mean(x[n-pre_avg : n+post_avg]) + delta`；`n - previous_n > wait`。
   > `delta` … threshold offset for mean；`wait` … number of samples to wait after picking a peak
   > 示例参数：`pre_max=3, post_max=3, pre_avg=3, post_avg=5, delta=0.5, wait=10`
   > This implementation is based on [Boeck, Krebs, Schedl, ISMIR 2012] and [CPJKU/onset_detection].
   > —— [librosa.util.peak_pick](https://librosa.org/doc/latest/api/generated/librosa.util.peak_pick.html)
   ⇒ **注意**：`delta = 0.5` 只有在输入被归一化之后才有意义（该示例的输入是归一化过的 onset envelope）。**不要照抄 0.5 用在未归一化的 RMS 上。**
3. **自适应阈值可以就是一阶低通**（Dixon 的 `gα`，本文认为这是与 Ncrust 现行实现最贴近的文献形式）：
   > Peak picking is performed as follows: each onset detection function `f(n)` is normalised to have a mean of 0 and standard deviation of 1 … `f(n) ≥ (mean over m·w+w+1) + δ`，`f(n) ≥ gα(n−1)`，where `w = 3` is the size of the window used to find a local maximum, `m = 3` is a multiplier … `δ` is the threshold above the local mean which an onset must reach, and `gα(n)` is a threshold function with parameter `α` given by: `gα(n) = max(f(n), α·gα(n−1) + (1−α)·f(n))`
   > 帧参数：N = 2048（44.1 kHz 下 46 ms），hop = 441（10 ms，78.5% 重叠）
   > —— [Dixon, "Onset Detection Revisited", DAFx-06](https://dafx.de/paper-archive/2006/papers/p_133.pdf)
   ⇒ `α·gα(n−1) + (1−α)·f(n)` **就是帧能量上的一阶低通**。Ncrust 的 `baselineState` 是同一件事，只是用固定系数而不是按 `dt` 算 —— 见 §3.5 的发现。
4. **在线（实时）约束：不能用未来信息、不能做全长归一化**：
   > Most methods use some kind of normalization over time, which renders them unusable for online tasks.
   > Since detecting a local maximum requires both past and future information, this method is only applicable to offline processing.
   > … the methods were evaluated under online conditions: **no future information was used to decide whether there is an onset at the current time point.**
   > Most methods use dynamic thresholding to take into account the loudness variations of a music piece. Mean, median or combinations are commonly used to filter the ODF.
   > —— [Böck, Krebs, Schedl, "Evaluating the Online Capabilities of Onset Detection Methods", ISMIR 2012](http://ismir2012.ismir.net/event/papers/049_ISMIR_2012.pdf)

**领域综述（本任务的"标准文献"入口）**：Bello, Daudet, Abdallah, Duxbury, Davies, Sandler,
*A tutorial on onset detection in music signals*, IEEE Trans. Speech and Audio Processing 13(5), 2005。

- 书目信息**已核实**（OpenAlex 记录：标题 / 年份 2005 / 期刊 IEEE Transactions on Speech and Audio Processing / DOI `10.1109/TSA.2005.851998`）：
  <https://api.openalex.org/works/doi:10.1109/tsa.2005.851998>
- **全文未抓到**（closed access：IEEE 落地页 <https://doi.org/10.1109/tsa.2005.851998>；OpenAlex/Unpaywall 均报无 OA 副本，CiteSeerX/UCL 镜像只给元数据）。
  ⇒ 本文**不引用该文的具体数字或原文**，只把它作为"方法综述的权威出处"登记；本文关于自适应阈值/峰值挑选/评测口径的具体引文一律落在可抓取的 Dixon DAFx-06 与 Böck ISMIR 2012 上（Dixon 明确写"we follow Bello et al. [2] in reporting results for optimal parameter settings"，即两者是同一套配方）。

**作为对照（本任务明确不做的 FFT/ML 路线）**：谱通量需要 STFT。

> Compute a spectral flux onset strength envelope. Onset strength at time `t` is determined by: `mean_f max(0, S[f,t] - ref[f,t-lag])` … By default, if a time series `y` is provided, `S` will be the log-power Mel spectrogram.
> —— [librosa.onset.onset_strength](https://librosa.org/doc/latest/api/generated/librosa.onset.onset_strength.html)

> The audio signal is transformed to the frequency domain with the Short Time Fourier Transform (STFT). Three parallel [STFTs] … The sizes used are 512, 1024, and 2048 samples, which corresponds to periods of 11.61, 23.22, and 46.44 ms, respectively, at a sample rate of 44,100 Hz.
> —— [Böck, Arzt, Krebs, Schedl, "Online Real-time Onset Detection with Recurrent Neural Networks", **DAFx-12 (2012), York**](https://dafx.de/paper-archive/2012/papers/dafx12_submission_4.pdf)
>
> ⚠️ 任务书把这篇写成 "2015"；**实际是 DAFx-12 / 2012 年 / York**（抓取原文页眉确认）。引用时请用 2012。

### 3.2 典型常数（只写文献里真实出现的数字）

| 常数 | 文献里的值 | 出处 |
|---|---|---|
| ODF 帧长 / hop | N = 2048（46 ms @44.1 kHz），hop = 441（10 ms，78.5% 重叠） | [Dixon DAFx-06](https://dafx.de/paper-archive/2006/papers/p_133.pdf) |
| 局部极大窗口 / 均值倍数 | `w = 3` 样本、`m = 3` | 同上 |
| 阈值 `δ`、基线系数 `α` | **论文按数据集用 ground truth 优化**（"experiments were performed with various values of the two parameters δ and α"），**没有给固定值** | 同上 |
| 峰值挑选示例参数 | `pre_max=3, post_max=3, pre_avg=3, post_avg=5, delta=0.5, wait=10`（输入为归一化包络） | [librosa.util.peak_pick](https://librosa.org/doc/latest/api/generated/librosa.util.peak_pick.html) |
| 匹配容差（评测口径，不是阈值） | 检测落在真值 ±50 ms 内算命中 | [Dixon DAFx-06](https://dafx.de/paper-archive/2006/papers/p_133.pdf) |
| 两次击打的时间分辨极限 | **30 ms**："we set such resolution to 30 ms since this is approximatively the temporal resolution of the human hearing system to distinguish two sequential sound events" | [Turchet DAFx-18](https://dafx.de/paper-archive/2018/papers/DAFx2018_paper_51.pdf) |
| 时间域支路的包络低通 | 高通 4000–7500 Hz + 两级低通 **25 Hz / 62 Hz**（Table 1，逐乐器不同） | 同上 |
| 动态阈值 | "a threshold consisting of the weighted median and mean of a section of the signal centered around the current sample"，权重 `α/β` 可配 | 同上 |
| 任务书提到的 "moving-average 0.5–2 s / 阈值倍数 1.3–2.0 / 最小间隔 60–120 ms" | **本次没有在任何一手文献里核到这一组具体数字**（Dixon 优化 δ/α；librosa 的 delta/wait 依赖归一化与帧率；Turchet 用 30 ms） ⇒ 这些数只能作为**推荐默认值**使用，且必须标注 | §6 |

**结论**：文献给的是**方法与结构**（自适应阈值 + 峰值 + 冷却），**不给可直接照抄的常数**。任何常数都必须按本项目的采样率/缓冲粒度/视觉预算标定 —— 这正是 §7 R6 的常量台账。

### 3.3 RMS AGC / 归一化：绝对阈值为什么必然失败

- **跨曲目失败**：母带压缩强的曲目 RMS 长期贴近 0.9，安静段落贴在 0.05；固定阈值 `full ≥ 0.12` 在前者几乎总为真、在后者几乎总为假。
- **文献侧的两条依据**：Böck 说"多数方法要做随时间/全长的归一化，那是在线不可用的"（§3.1 第 4 条）；Dixon 把它写成"检测函数先归一化到均值 0、标准差 1，再做峰值挑选"（§3.1 第 3 条）。**两者都指向同一件事：阈值必须相对于"最近一段时间的统计量"，而不是绝对响度。**
- **正确的做法（本任务）**：把慢速基线做成**帧能量上的一阶低通**（不是逐样本滤波！），判据用"相对基线的增量"：
  `onset ⟺ level ≥ floor ∧ Δ ≥ absMin ∧ Δ ≥ base·relFactor`
  其中 `Δ = level − base`，`base` 是慢速基线。这与 Dixon 的 `gα` + `δ` 同构：`base` 扮演滑动均值，`absMin`/`relFactor` 扮演 `δ`，冷却扮演 `wait`。
- **"AGC" 一词的边界**：不要真的去乘一个自动增益（那会改变 `low/mid/high` 的相对关系与 `mix` 的语义），只让**阈值**相对化。现行代码就是这么做的（`updateTransient`），这是对的。

### 3.4 帧/缓冲 vs 逐样本：缓冲粒度必须实测

**代码层的确定事实**（`TeeAudioProcessor` 源码逐字）：

```java
public void queueInput(ByteBuffer inputBuffer) {
  int remaining = inputBuffer.remaining();
  if (remaining == 0) { return; }
  audioBufferSink.handleBuffer(Util.createReadOnlyByteBuffer(inputBuffer));
  replaceOutputBuffer(remaining).put(inputBuffer).flip();
}
```

> —— [TeeAudioProcessor.java · androidx/media（release 分支）](https://raw.githubusercontent.com/androidx/media/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/TeeAudioProcessor.java)

它**不切块、不缓冲**：sink 拿到的就是上游（解码器 → `DefaultAudioSink`）交过来的那一块。所以"一次回调多少帧、每秒回调几次"是**运行时行为**，代码里没有常量能回答。**任务书里写的"typically 4096 frames or so"只能当假设，不能当依据。**

**工作区已有的测量工具**（[`AudioTapProbeTest.kt`](app/src/androidTest/java/com/takahashirinta/ncrust/probe/AudioTapProbeTest.kt)，`androidTest`，需要真机）：

- 自建 44.1 kHz 立体声 16-bit WAV（正弦 + 周期性低频脉冲串）→ 用挂了同一个 `TeeAudioProcessor` 的 ExoPlayer 播放；
- 在 sink 里用**预分配 `IntArray` 直方图**统计帧数（零分配、不加锁），播完一次性 dump；
- 探针档头自己写了两种真实可能的后果：

>  - 若一次回调 4096 帧（44.1kHz 约 93ms）⇒ 每秒只有 ~11 次判定，快鼓点必然漏；
>  - 若一次回调 1024 帧（约 23ms）⇒ 每秒 ~43 次，够用。
> 两者都是真实可能，**不许猜**。

**时间基准的映射（现行实现已经做对）**：

```kotlin
val frames = samples / channelCount                 // samples = view.remaining() / bytesPerSample（全声道交错样本数）
frameMs = (frames * 1000.0 / sampleRateHz).toFloat()
```

⇒ **不要**用"每缓冲固定 X ms"的假设；`frameMs` 必须由样本数反算（这也是 `updateTransient` 用毫秒而不是"缓冲计数"做冷却的原因，代码注释已经写明）。

**决策表（跑完探针后照此判读）：**

| 实测帧数 @44.1 kHz | 单次回调时长 | 每秒判定次数 | "每缓冲一次判定"是否够用 |
|---|---|---|---|
| 256 | 5.8 ms | ~172 | 够（过密，冷却会吃掉大部分） |
| 1024 | 23 ms | ~43 | **够**（推荐区间） |
| 2048 | 46 ms | ~21 | 边缘：8 分音符（16 次/秒）开始漏 |
| 4096 | 93 ms | ~11 | **不够**：需要在音频线程内**按帧累加**（把缓冲切成 ~512 帧的小判定窗）或接受漏检 |

**若实测落在 4096 一侧**，正确修法不是"加个 FFT"，而是：在**已有的那一次逐样本遍历**里按固定的样本数（例如每 `fs/100` 个样本 = 10 ms）结算一次帧能量，再对这些帧做 §3.1 的判据。这仍然是零分配、单遍扫描，只是把"每缓冲一次"细化成"每 10 ms 一次"。

### 3.5 ⚠️ 新发现：固定基线步长 ⇒ 时间常数随缓冲粒度漂移

现行代码（[`AudioFeatureExtractor`](app/src/main/java/com/takahashirinta/ncrust/player/AudioFeatureExtractor.kt)）：

```kotlin
baselineK = BASELINE_STEP          // 0.026，固定值
baselineState[i] += (level - baselineState[i]) * baselineK
// 注释：「按典型缓冲 ~21ms 折算成一次指数更新」「缓冲粒度实测落在 10~50ms ⇒ 0.6~1.6 倍时间常数」
```

一个一阶低通的每步系数 `k` 与时间常数 `τ` 的关系是 `k = 1 − exp(−dt/τ)`，反解 `τ = −dt / ln(1−k)`。取 `k = 0.026`：

| 每缓冲时长 `dt` | 实际 `τ` | 相对 0.8 s |
|---|---|---|
| 10 ms | 380 ms | 0.47× |
| 21 ms | **797 ms** | 1.00×（注释里的目标） |
| 23.2 ms | 881 ms | 1.10× |
| 46.4 ms | 1761 ms | 2.20× |
| 50 ms | 1898 ms | 2.37× |
| **92.9 ms（4096 帧 @44.1 kHz）** | **3526 ms** | **4.41×** |

⇒ 代码注释写的「0.6~1.6 倍」**与算术不符**（真实范围 0.47~2.37×，若缓冲是 4096 帧则是 4.4×）。后果不是崩溃，而是**判据行为随设备/曲目漂移**：基线越慢，"正常"的音量渐变（渐强、副歌进入）越容易整段被判成瞬态；同时强瞬态之后基线恢复得越慢，连续击打的第二、三下越容易被相对门槛吃掉。

**修法（推荐）**：把 `baselineK` 从"固定步长"改成"按本次 `dt` 计算"：

```
k = 1 − exp(−frameMs / BASELINE_TAU_MS)        // 每缓冲一次 exp；τ 固定，判据跨设备一致
```

代价是**每缓冲一次 `exp`**（不是每样本）。代码注释里"省下来的是可测的"是一个**未验证的性能主张**：一次 `exp` 在 ~20–90 ms 的预算里完全可忽略（§5 的估算：单缓冲总量级在 10⁴–10⁵ flop）。**建议改成按 `dt` 计算，并把"每缓冲一次 exp 的开销"列入实测项**（若实测显示它在播放线程上可测，再退回固定步长，但那时必须把"τ 随缓冲漂移"写进文档）。

**顺带修正**：`frameMs` 依赖 `channelCount`，因此 `channelCount` 未知或为 0 时 `updateTransient` 直接返回（现行代码在 `configure` 里已把这种情况置 `available=false`，不会走到这里）。

### 3.6 冷却窗口：180 ms 意味着什么

| 冷却 | 最大触发率 | 对照（音符率，四分/八分音符） |
|---|---|---|
| 30 ms | 33.3 次/秒 | 30 ms 是听感分辨极限（Turchet 引用的人类分辨极限） |
| 60 ms | 16.7 次/秒 | 120 BPM 八分音符 = 16 次/秒（刚好卡住） |
| 90 ms | 11.1 次/秒 | 180 BPM 四分音符 = 12 次/秒（略不足） |
| 120 ms | 8.3 次/秒 | 120 BPM 八分音符 = 16 次/秒（会合并一半） |
| **180 ms（现行值）** | **5.6 次/秒** | 120 BPM 四分音符 = 8 次/秒 ⇒ **会合并**；160 BPM 以上四分音符更明显 |

**判决**：`180 ms` 是"视觉预算"驱动的选择（一次击打只画一个涟漪），不是感知/音乐学驱动的选择。**它必须标注为「推荐默认值，需实测校准」**，并在真机上用两类曲目对照：

- **快节奏**（Drum & Bass / 快速流行，八分音符 ≥ 8 次/秒）：若用户可感知"鼓点漏触发"，降到 90 ms；
- **慢速 + 强混响**：若出现"一次击打两个涟漪"，维持 120–180 ms。

**注意**：冷却与基线是两个独立旋钮，不要用调冷却去掩盖基线的漂移（§3.5）—— 基线错会让"漏"和"多"同时出现。

### 3.7 零分配的做法（把"铁律"落到可检查的形式）

| 禁止 | 为什么 | 替代 |
|---|---|---|
| `List`/`ArrayList`/`ArrayDeque`/`HashMap` | 每次 `add` 可能扩容/装箱（`Integer`/`Float` 装箱 ⇒ 堆分配） | 预分配 `FloatArray`/`IntArray`/`DoubleArray` + 手写环形索引 |
| `Pair`/`Triple`/`data class` 返回值 | 每缓冲一个对象 ⇒ GC 压力 | 写到**调用方持有的**数组/对象字段（现行 `AudioFeatureExtractor` 的做法） |
| 字符串拼接、`Log.*`、`String.format` | 每次分配；且日志本身有锁 | `@Volatile Long` 计数器（现行 `droppedBarCount` 的做法），由 UI/单测读 |
| 磁盘 / `SharedPreferences` | 是 IO，可能阻塞（§4.1） | 进程内 `@Volatile` 镜像；开关变化由 UI 线程写入 |
| 加锁 / `synchronized` | 优先级反转 ⇒ 卡顿/爆音（§4.1） | 单写者 + `@Volatile` 发布；或单读者单写者定长环形缓冲 |
| 每样本一次函数调用（非内联） | 调用开销 + 可能阻止 JIT 优化 | 单循环（现行实现：`while` + `when(encoding)` 外提） |
| 每缓冲重新分配状态 | 与 GC 直接冲突 | 状态在 `configure`/构造时分配一次 |
| 异常向上抛 | media3 不兜（§4.1） ⇒ 播放级联失败 | 调用侧 `try/catch(Throwable)` + 降级（现行 `TransparentWaveformSink.handleBuffer`） |

**"零分配"的检查方式**（不能只靠注释）：JVM 单测**证伪异常隔离**（注入会抛的提取器，断言 sink 不抛）；真机上用 `perfetto` 的 `dalvik`/`heap` 类别看 playback 线程的 GC（v2.8.0 探针 §6.2 已给出这条口径；`AudioTrack` 欠载计数的**字段名未核实**，别猜）。

---

## 4. Android 平台注意事项

### 4.1 线程模型：谁调用 `handleBuffer`，为什么不能阻塞

**ExoPlayer 的线程模型（官方 javadoc 原文）：**

> ExoPlayer instances must be accessed from a single application thread unless indicated otherwise. …
> **An internal playback thread is responsible for playback. Injected player components such as Renderers, MediaSources, TrackSelectors and LoadControls are called by the player on this thread.**
> —— [ExoPlayer · Android Developers](https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer)

`TeeAudioProcessor` 是注入到音频渲染链上的 `AudioProcessor`，它的 `queueInput` 就发生在这条**内部播放线程**上；而 `queueInput` **同步**调用 `sink.handleBuffer(...)`（源码见 §3.4 引用块）。⇒ **`handleBuffer` 跑在播放线程上，与音频渲染共命运。**

> （仓库侧旁证：v2.8.0 探针把该线程记为 `ExoPlayer:Playback`、`THREAD_PRIORITY_AUDIO`，见 [`probe-waveform.md`](docs/verification/v2.8.0/probe-waveform.md) §3。**该优先级断言本文没有在 media3 源码里复核**，登记在 §6，结论不依赖它。）

**阻塞/加锁的后果（AOSP 官方原文）：**

> Priority inversion is a classic failure mode of real-time systems, where a higher-priority task is blocked for an unbounded time waiting for a lower-priority task to release a resource such as (shared state protected by) a mutex. **In an audio system, priority inversion typically manifests as a glitch (click, pop, dropout)**, repeated audio when circular buffers are used, or delay in responding to a command.
> —— [Avoid priority inversion · source.android.com](https://source.android.com/docs/core/audio/avoiding_pi)

**异常不隔离的后果（源码级事实）**：`TeeAudioProcessor.queueInput` 对 `sink.handleBuffer` **没有 try/catch**（上面的源码块里 `handleBuffer` 与 `replaceOutputBuffer` 之间没有异常表）；`TeeAudioProcessor.WavFileAudioBufferSink` 自己吞 `IOException` 恰恰说明**隔离责任在 sink 一侧**。这解释了为什么本仓库把"RMS + 全部回调"收进同一个 `try`（v2.8.0 的 P1-A）—— 抛出去的异常会顺着 `queueInput` 回到 `DefaultAudioSink`/播放器，形成 v2.2.1 的降档—跳歌级联。

**实践清单（本项目已有，保留）**：不阻塞、不加锁、不 IO、不分配、不抛；开关判断前置（`@Volatile` 镜像）；失败**降级**而不是"补 0"（补 0 会在画面上画出一个假静音）。

### 4.2 `AudioProcessor` 的缓冲契约（为什么可以直接按 native order 解释 PCM）

> `queueInput`: Queues audio data between the position and limit of the inputBuffer for processing. … **It must be a direct byte buffer with native byte order. Its contents are treated as read-only.** Its position will be advanced by the number of bytes consumed (which may be zero). The caller retains ownership of the provided buffer.
> `getOutput`: … The buffer will always be a direct byte buffer with native byte order.
> —— [AudioProcessor · Android Developers](https://developer.android.com/reference/androidx/media3/common/audio/AudioProcessor)

**三条可直接使用的结论**：

1. **native byte order 是文档保证的** ⇒ 现行代码用 `ByteOrder.nativeOrder() == LITTLE_ENDIAN` 自己拼 16/32 位整数是**合法**的（比依赖 `buffer.order()` 更稳）；不必担心"大端数据 + 小端声明"的组合出现在这条路径上（但保留这段字节序判断**零成本**，不建议删）。
2. **输入只读** ⇒ 绝对不能写回 `buffer`（会污染音频输出）。现行实现只做绝对下标 `get(index)`，不改 `position/limit/mark/order` —— 这一点同时满足"只读"和"零副作用"。
3. **`AudioBufferSink` 的两个方法签名（官方文档逐字核对过）**：

```java
void flush(int sampleRateHz, int channelCount, @C.PcmEncoding int encoding);  // "Called when the audio processor is flushed with a format of subsequent input."
void handleBuffer(ByteBuffer buffer);                                          // "Called when data is written to the audio processor."
// @param buffer "A read-only buffer containing input which the audio processor will handle."
```

> —— [TeeAudioProcessor.AudioBufferSink · Android Developers](https://developer.android.com/reference/androidx/media3/exoplayer/audio/TeeAudioProcessor.AudioBufferSink)

⇒ `flush()` 是**唯一**获得采样率/声道数/编码的时机（本仓库把它当作 `configure` 用），**必须在 `flush` 里重算滤波系数并清状态**（换歌/换设备时采样率会变）。`TeeAudioProcessor` 在 `onFlush`/`onQueueEndOfStream` 里都会调 `flushSinkIfActive()`（源码已验证）。

### 4.3 `android.media.audiofx.Visualizer`：要求与为什么不用它

**官方原文（三条）**：

> It is not an audio recording interface and only returns partial and low quality audio content. However, to protect privacy of certain audio data (e.g voice mail) **the use of the visualizer requires the permission android.permission.RECORD_AUDIO.**
> The audio session ID passed to the constructor indicates which audio content should be visualized: **If the session is 0, the audio output mix is visualized**；If the session is not 0, the audio from a particular MediaPlayer or AudioTrack using this audio session is visualized.
> **Frequency data: 8-bit magnitude FFT** by using the `getFft(byte[])` method … The capture size must be a power of 2 in the range returned by `getCaptureSizeRange()`.
> —— [Visualizer · Android Developers](https://developer.android.com/reference/android/media/audiofx/Visualizer)

**本次抓取该页面未见 deprecated 标记**（截至 2026-09-27，页面未出现 "deprecated" 字样）；但**基础类 `AudioEffect` 明确写了 session 0 的插入式效果已废弃**：

> NOTE: attaching insert effects (equalizer, bass boost, virtualizer) to the global audio output mix by use of session 0 is deprecated.
> —— [AudioEffect · Android Developers](https://developer.android.com/reference/android/media/audiofx/AudioEffect)

**跨应用抓音的门槛（官方原文）**：

> To be able to capture audio, an app must meet these requirements: The app must have the **RECORD_AUDIO** permission. The app must bring up the prompt displayed by **MediaProjectionManager.createScreenCaptureIntent()**, and the user must approve it. **The capturing and playing apps must be in the same user profile.**
> —— [Capture video and audio playback · Android Developers](https://developer.android.com/guide/topics/media/playback-capture)

**为什么在 Ncrust 里它不可取（四条，全部由上面原文推出）**：

1. **多要一个危险权限**（`RECORD_AUDIO`）+ 启动时一次系统弹窗（`MediaProjection`），而本应用当前清单**没有**这个权限（v2.8.0 探针 §1 已核对）；
2. **抓的是"另一条通路"的数据**：`Visualizer` 从 audioflinger 抓输出混音/会话，与我们从 tee 拿到的 PCM **不是同一份**（可能已被音量/音效/混音处理），会让"可视化与音频严格对应"的既有契约失效；
3. **它给的是 8-bit 幅度 FFT**（精度低、格式受限），而本任务要的恰恰是"不用 FFT"；
4. **失败模式不可控**：会话 0/无效会话返回 `ERROR_BAD_VALUE`（`-4`）等错误码，且 `getCaptureSizeRange()`/`getMaxCaptureRate()` 逐设备不同 —— 又是一条必须在最差设备上取证的路径，而收益为零（PCM 已经在手上）。

⇒ **结论：不引入 `Visualizer`。** 它只适合"应用完全拿不到 PCM"的场景。

### 4.4 Float vs Short PCM：编码常量与归一化

**media3 的编码常量（官方文档逐字核对）：**

| 常量 | 值 | 文档摘要 |
|---|---|---|
| `C.ENCODING_INVALID` | **0** | — |
| `C.ENCODING_PCM_16BIT` | **2** | "See ENCODING_PCM_16BIT"（详见 `AudioFormat`，见下） |
| `C.ENCODING_PCM_8BIT` | **3** | — |
| `C.ENCODING_PCM_FLOAT` | **4** | "See ENCODING_PCM_FLOAT" |
| `C.ENCODING_PCM_24BIT` | **21** | "PCM encoding with 24 bits per sample." |
| `C.ENCODING_PCM_32BIT` | **22** | "PCM encoding with 32 bits per sample." |

> —— [C · Android Developers](https://developer.android.com/reference/androidx/media3/common/C)

**归一化的权威依据（Android `AudioFormat` 原文）：**

> `ENCODING_PCM_16BIT`: The audio sample is a **16 bit signed integer** typically stored as a Java short … but when the short is stored in a ByteBuffer, **it is native endian** (as compared to the default Java big endian). The short has **full range from [-32768, 32767]**, and is sometimes interpreted as fixed point Q.15 data.
> `ENCODING_PCM_FLOAT`: … the audio sample is a **32 bit IEEE single precision float** … within a ByteBuffer it is stored in **native endian byte order**. **The nominal range of ENCODING_PCM_FLOAT audio data is [-1.0, 1.0].**
> —— [AudioFormat · Android Developers](https://developer.android.com/reference/android/media/AudioFormat)

⇒ 现行代码的两条归一化都是对的：

- 16-bit：`raw.toShort() / 32768.0` ⇒ 范围 `[-1.0, 0.99997]`（`−32768/32768 = −1.0`，`32767/32768 = 0.99997`）。**注意不是对称的 [−1,1]**：正满量程差 1 个 LSB（约 −0.0003 dB），对特征无影响，但不要写"精确 [−1,1]"；
- float：直接取 `Float.fromBits(...).toDouble()` ⇒ 已经是 [−1,1]（文档用词是 "nominal"，且"positive maximum of 1.0 是否包含在区间内由实现决定"）。

**鲁棒性建议（现行 `bytesPerSample` 已经这么写）**：只支持 `16BIT` 与 `FLOAT`，其余（`24BIT`/`32BIT`/`8BIT`/`INVALID`）返回 0 ⇒ 调用方降级到 RMS-only。理由：24-bit 在 ByteBuffer 里是 3 字节打包（`AudioFormat` 文档另述），解析成本与出错面都不值得；**宁可没有特征，也不能算错**。

---

## 5. 数值与开销估算（**必须由真机实测确认**）

### 5.1 每样本运算（现行实现，16-bit 路径逐行计数）

| 项 | 每样本操作 |
|---|---|
| 归一化 | 1 次整数转换 + **1 次除法**（`/ 32768.0`） |
| 全带 | `v*v`（1 乘）+ 累加（1 加） |
| 低频 `sLow += kl*(v−sLow)` | 1 减 + 1 乘 + 1 加 |
| 中频 `sMid += km*(v−sMid)` | 1 减 + 1 乘 + 1 加 |
| `m = sMid − sLow` | 1 减 |
| `h = v − sMid` | 1 减 |
| 三个平方累加 | 3 乘 + 3 加 |
| **合计** | **6 乘 + 10 加减 + 1 除 ≈ 17 flop/样本**（不含取字节/索引/类型转换） |

对照 v2.9.0 的 `PcmRms.analyze`：`v*v` + 1 递归 + `lp*lp` = **3 乘 + 1 减 + 3 加 + 1 除 ≈ 8 flop/样本**。

**可选的第三个代理（ZCR）的每样本代价**：`if ((v >= 0) != (prev >= 0)) crossings++` 再加一次 `prev = v` ⇒ **2 次比较 + 1 次加 + 1 次赋值 ≈ 3 op/样本**（不涉及浮点乘除）。44.1 kHz 立体声 ≈ **0.26 Mop/s**，相对 17 flop/样本的 1.50 Mflop/s 是 ~18% 的增量。即：**ZCR 便宜到可以顺手算，但它带来的是第三个语义相近的亮度代理**，是否值得（§2.5 的"单一明亮度口径"原则）比它的开销更值得权衡 —— 若不需要交叉验证，就不加。

> 注意：ZCR 对**直流偏置**极敏感（一个 1e-4 的 DC 偏移会让纯音也产生大量过零）。若要启用，必须先减掉慢速均值（又是一条一阶低通），或只对**已高通/去均值**的信号计数 —— 这是"便宜"背后的隐藏成本。

⇒ **v3.0.0 的增量是 +9 flop/样本（约 2.1×），而不是代码注释里写的"+3 次乘加"**（[`AudioFeatureExtractor`](app/src/main/java/com/takahashirinta/ncrust/player/AudioFeatureExtractor.kt) 档头表格写的"6 乘 6 加减 vs 3 乘 2 加减"偏低）。**这是文档勘误，不是性能问题** —— 量级见下。

**每秒运算量（纯算术，不含访存/分支）：**

| 采样率 | 声道 | 样本/秒 | flop/s（17 flop/样本） |
|---|---|---|---|
| 44.1 kHz | 2 | 88 200 | **≈ 1.50 Mflop/s** |
| 44.1 kHz | 6 | 264 600 | ≈ 4.50 Mflop/s |
| 48 kHz | 2 | 96 000 | ≈ 1.63 Mflop/s |
| 48 kHz | 6 | 288 000 | ≈ 4.90 Mflop/s |

### 5.2 每缓冲的**最坏情况**时间（数量级估算，非测量）

`flops = frames × channels × 17`；下表给三种假想的标量吞吐（低端 ARM 上 JIT 后的**保守**区间）：

| 缓冲 | 声道 | 样本 | flop | @100 Mflop/s | @500 Mflop/s | @2 Gflop/s |
|---|---|---|---|---|---|---|
| 1024 帧 | 2 | 2 048 | 34.8 k | 0.35 ms | 0.07 ms | 0.017 ms |
| 2048 帧 | 2 | 4 096 | 69.6 k | 0.70 ms | 0.14 ms | 0.035 ms |
| 4096 帧 | 2 | 8 192 | 139 k | 1.39 ms | 0.28 ms | 0.070 ms |
| 2048 帧 | 6 | 12 288 | 209 k | 2.09 ms | 0.42 ms | 0.104 ms |
| 4096 帧 | 6 | 24 576 | 418 k | 4.18 ms | 0.84 ms | 0.209 ms |

对照预算：4096 帧 @44.1 kHz 的**音频时长是 92.9 ms**。即使在最悲观的 100 Mflop/s 假设下，纯算术也只用掉 ~4.5% 的预算。⇒ **吞吐不是风险；风险是（a）音频线程上的分配/GC、（b）锁/IO 阻塞、（c）异常未被隔离。** 这三条正是 §3.7/§4.1 的清单。

**必须实测的量（口径已由仓库既有纪律给出）**：

1. **缓冲粒度直方图**（探针已在工作区，未跑）—— 决定 §3.4 的判据粒度、§3.5 的 `τ` 漂移、§3.6 的冷却下限；
2. **播放线程的单缓冲耗时分布**（perfetto `sched` + atrace 的 `AudioTrack`/`DefaultAudioSink` 片段；`AudioTrack` 欠载字段名**未核实**，见 §6）；
3. **GC 次数**（perfetto `dalvik`/`heap`，看 playback 线程是否有 Young GC）—— 这是"零分配"契约的唯一真机证据；
4. **每缓冲一次 `exp` 的开销**（§3.5 的修法是否可接受）；
5. **`/32768.0` 除法 vs `*(1.0/32768.0)` 的差异**（可忽略的可能性很大，但它是零风险的改动，测了再改）；
6. **功耗/发热**（持续每样本多 3 次乘加在 6 声道高码率下的边际影响）—— 帧指标看不见这一项。

### 5.3 与"关掉开关 = 零开销"的关系

现行 `handleBuffer`（工作区版本）先读**两个** `@Volatile` 开关（可视化 / 界面动效需要特征），**两个都为 false 时直接 return**（连一次样本遍历都不跑）。这是 v2.8.0 纪律的正确延伸（v2.8.0 的缺陷是"只有一个开关，关掉可视化把节拍数据一起掐掉"）。⇒ **"零开销"的判据是"两次 volatile 读"**，这一点应在真机上用 perfetto 复核（关掉时 playback 线程不应出现本类的栈帧）。

---

## 6. 未核实 / 不可依赖（集中列表）

| # | 条目 | 状态与影响 |
|---|---|---|
| 1 | **本机（工作区）没有任何真机实测数据**：`docs/verification/v3.0.0/probe/` 不存在；`AudioTapProbeTest` 未跑 | **未测量**。§3.4/§3.5/§3.6 的所有判决都依赖它 |
| 2 | 缓冲粒度（每回调帧数 / 每秒回调次数） | **未测量**（探针已就绪）；不得按 "4096 帧" 假设 |
| 3 | 播放线程的 `THREAD_PRIORITY_AUDIO` | **未核实**（本文未在 media3 源码里定位到设置优先级的代码；仓库 v2.8.0 探针有此断言）。结论不依赖它 |
| 4 | `AudioTrack` 欠载（underrun）在 `dumpsys media.audio_flinger` 里的字段名 | **未核实**（不要猜字段名；用 perfetto 的 atrace 片段代替） |
| 5 | MPEG-7（ISO/IEC 15938-4）是否把零交叉率列为低层描述子、其确切名称 | **未核实**（ISO 目录页 403；未找到可引用的公开页面）⇒ 本文不引用 |
| 6 | "ZCR 与谱质心存在定量相关（相关系数）"的一手文献 | **未核实** ⇒ 本文只把 ZCR 当**独立代理**，不写"可替代质心" |
| 7 | 任务书给出的常数组"moving-average 0.5–2 s / 阈值倍数 1.3–2.0 / 最小间隔 60–120 ms" | **未在任何一手文献里核到**（Dixon 优化 δ/α 不给值；librosa 的 `delta`/`wait` 依赖归一化与帧率；Turchet 用 30 ms）⇒ 只能作**推荐默认值** |
| 8 | 底鼓/贝斯基频的"50–100 Hz / 40–200 Hz"具体区间（仓库 KDoc 里的说法） | **未核实**（本次未找到一手测量文献）；可核实的是 **贝斯 4 弦最低 E1 ≈ 41.20344 Hz** 与人声基频区间 |
| 9 | 6 声道 FLAC 的实际声道相关性（是否左右/前后高度相关） | **未测量**。§1.7 的 ×6 是"同相信号"下的**上界**；真实内容介于 ×1 与 ×6 之间，但**只要不是单声道，有效截止就一定偏高** |
| 10 | 现行 `centroidOf` / `bandMix` 在真实曲目上的分布（是否集中在 0.3–0.7 之类） | **未测量**（需要真机 dump 直方图，音频线程上不能打日志，用预分配直方图） |
| 11 | `Visualizer` 是否在更新的 API 上被标记 deprecated | 本次抓取（2026-09-27）**页面未出现** deprecated 字样；不作为"可长期依赖"的依据 |
| 12 | 每缓冲一次 `exp` 在播放线程上的实测开销 | **未测量** |
| 13 | Bello et al. 2005 综述的**全文内容**（DOI 10.1109/TSA.2005.851998） | **未抓到**（closed access，无 OA 副本）⇒ 只登记书目、不引用其数字；具体引文改用 Dixon DAFx-06 与 Böck ISMIR 2012 |
| 14 | 现行 `AudioFeatureExtractor` 的 KDoc 里"每样本多三次乘加"的表述 | **与实际不符**（本文逐行计数为 +9 flop/样本）⇒ 已作为文档勘误写进 §5.1 |

---

## 7. 推荐方案（给 Ncrust）

> 目标形态：**在已有的一次逐样本遍历里，多算两个一阶低通的递归、三个平方累加、一条帧级基线与一个冷却计时器**；不新增遍历、不新增缓冲、不新增分配、不新增 IO、不新增锁、不新增异常面。
> 与工作区现行实现一致的地方直接沿用（并给出外部依据）；本文新发现的两处（§1.7 声道状态、§3.5 基线 `dt`）作为**必改项**列出。

### R0 · 不该做的（写进文档，防回归）

- ❌ **任何 FFT/STFT/DCT/滤波器组频谱库**（含"现成的 FFT 库"）。理由不是"FFT 不好"，而是：本层只需要"三个宽带包络 + 一个击打判定"，FFT 会引入分帧/窗/PCM 环/倍率计算，且**新增一条必须真机取证的路径**。
- ❌ **每加一个特征多扫一遍样本**（必须是同一次遍历）。
- ❌ **音频线程上的任何分配/IO/锁/日志/字符串**（§3.7 清单）。
- ❌ **绝对阈值**（§3.3）。
- ❌ **用缓冲计数做冷却或基线**（缓冲粒度是运行时行为；必须用毫秒/样本数）。
- ❌ **把三带能量当频谱展示或把 `centroid01` 当 Hz 报出**（§1.3/§2.4）。
- ❌ **在 `handleBuffer` 外裸调回调**（异常隔离边界必须包含全部分析 + 全部回调，v2.8.0 的 P1-A 契约）。

### R1 · 滤波器拓扑（精确到可以照抄）

```
configure(sampleRateHz, channelCount):
    if sampleRateHz <= 0 || channelCount <= 0:  available = false; return   // 降级
    ch = min(channelCount, 8)                                              // 状态上限
    aLow = 1 - exp(-2π·150 / fs)      // 低频边界：150 Hz（沿用 v2.9.0 的 BASS_CUTOFF_HZ）
    aMid = 1 - exp(-2π·2000 / fs)     // 中/高边界：2000 Hz
    sLow[ch], sMid[ch] = 0             // 每声道一个状态（DoubleArray，构造时分配一次）
    bLow = bFull = 0                   // 帧级慢速基线
    cooldownMs = 0

process(view, encoding):               // 音频线程，零分配，单遍
    for each sample v at frame f, channel c:
        sumFull += v*v
        sLow[c] += aLow*(v - sLow[c])
        sMid[c] += aMid*(v - sMid[c])
        m = sMid[c] - sLow[c]
        h = v - sMid[c]
        sumLow += sLow[c]*sLow[c]; sumMid += m*m; sumHigh += h*h
    rms/low/mid/high = sqrt(sumX / 样本总数)      // 每缓冲 4 次 sqrt
    mix      = (mid + high) / (low + mid + high)        // 分母 ≤ 1e-9 ⇒ 0
    centroid01 = ln(clamp(w, 50, 16000)/50) / ln(16000/50),  w = (75·low + 1000·mid + 6000·high)/(low+mid+high)
    frameMs  = (样本总数 / channelCount) · 1000 / fs
    updateOnset(low, rms, frameMs)                      // §R3
```

**必改项 1（§1.7）**：`sLow`/`sMid` **按声道分开**（`DoubleArray(ch)` + 自增取模的 `c` 索引），否则有效截止频率 = 标称 × 声道数（立体声 300 Hz / 6 声道 901 Hz）。

**必改项 2（可选，看实测）**：若实测缓冲 ≥ 4096 帧（≥93 ms），把"每缓冲一次判定"细化成"音频线程内每 10 ms 结算一帧"（在同一个循环里累加，不新增遍历）。

**保留项**：`available=false` 时**整个特征路径不跑**，调用方回落 `PcmRms.analyze`（RMS-only = v2.9.0 行为）；两个开关都为 false 时 `handleBuffer` 只做两次 volatile 读。

### R2 · 每缓冲派生的特征（这就是"数据通路"的完整语义）

| 量 | 定义 | 用途 | 边界 |
|---|---|---|---|
| `rms` | 全带 RMS（全部声道合并） | 波形柱高（**语义与 v2.9.0 逐值一致**） | clamp [0,1]，非有限 ⇒ 0 |
| `low` | LP(150) 的 RMS | 低频通道 / 节拍判据（**与 v2.9.0 同一条递推**） | 同上 |
| `mid` | LP(2000)−LP(150) 的 RMS | 中频带能量条 | 同上 |
| `high` | `x`−LP(2000) 的 RMS | 高频带能量条 | 同上 |
| `mix` | `(mid+high)/(low+mid+high)` | 逐柱着色的"明亮度占比" | 分母 ≤1e-9 ⇒ 0；**唯一的明亮度口径** |
| `centroid01` | §2.4 的三带加权 + ln 映射 | 背景/缩放的视觉映射 | 仅单调代理，**不得报 Hz** |
| `transient` / `transientStrength` | §R3 | 冲击波/粒子（**计数单调递增**，UI 比较差值） | 强度 clamp [0,1] |
| `frameMs` | `(samples/channels)/fs·1000` | 时间基准（冷却、基线） | ≤0 时不做瞬态判定 |

（可选第三个亮度代理：**ZCR**。每样本一次符号比较 + 一次计数即可，与现有遍历共用；`zcr = 过零数 / 样本数`，对单正弦 `= 2f₀/fs`。**只作交叉验证/降级用**，不与 `centroid01` 混用为同一语义 —— §2.3。）

### R3 · 自适应阈值与冷却（把文献映射到常数）

```
updateOnset(low, full, dtMs):                    // 每缓冲一次；dtMs <= 0 ⇒ return
    jumpLow  = low  - baseLow                    // 先算增量，再更新基线（顺序是契约）
    jumpFull = full - baseFull
    k = 1 - exp(-dtMs / TAU_MS)                  // ★ 必改项 3（§3.5）：固定步长会让 τ 随缓冲漂移
    baseLow  += jumpLow  * k
    baseFull += jumpFull * k
    if cooldownMs > 0: cooldownMs -= dtMs; return          // 冷却中
    onsetLow  = low  >= FLOOR_LOW  && jumpLow  >= ABS_MIN && jumpLow  >= baseLow  * REL_FACTOR
    onsetFull = full >= FLOOR_FULL && jumpFull >= ABS_MIN && jumpFull >= baseFull * REL_FACTOR
    if (!onsetLow && !onsetFull) return
    cooldownMs = COOLDOWN_MS
    strength   = max(jumpLow, jumpFull) / STRENGTH_FULL    // clamp [0,1]
```

**与文献的对应关系（写进注释，方便下一个人对照）**：`baseLow/baseFull` = Dixon 的 `gα`（对帧能量的一阶低通）；`ABS_MIN`/`REL_FACTOR`/`FLOOR_*` = Dixon 的 `δ`（相对滑动均值）；`COOLDOWN_MS` = librosa `peak_pick` 的 `wait` / 文献里的 refractory period；"先算 jump 再更新基线" = 避免强瞬态把自己判掉（Dixon 用 `max(f(n), …)` 达到同一目的）。

**推荐默认值（全部标注「推荐默认值，需实测校准」）**：

| 常数 | 现行值 | 本文建议 | 理由 |
|---|---|---|---|
| `LOW_CUTOFF_HZ` | 150 | **150**（保留） | §1.6；与 v2.9.0 的低频通道同一条递推（否则"柱状图的低频"与"判据的低频"分叉） |
| `MID_HIGH_CUTOFF_HZ` | 2000 | **2000**（保留） | §1.6 |
| `TAU_MS`（基线） | 隐式 0.8 s（固定步长） | **800 ms**，改为 `k = 1−exp(−dt/τ)` | §3.5 |
| `COOLDOWN_MS` | 180 | **90**（先试），按真机观感在 **60–180** 间定 | §3.6：180 ms 上限 5.6 次/秒；30 ms 是听感下界 |
| `ABS_MIN` | 0.05 | 保留，实测后调 | 相对基线的绝对增量门槛 |
| `REL_FACTOR` | 0.35 | 保留，实测后调 | "涨幅超过基线 35%" |
| `FLOOR_LOW` / `FLOOR_FULL` | 0.10 / 0.12 | 保留，实测后调 | 安静段防底噪触发 |
| `STRENGTH_FULL` | 0.20 | 保留 | 强度归一化（视觉用） |
| 质心代表频率 | 75/1000/6000 | 保留 | 约定值（§2.4） |
| 质心映射区间 | 50–16000 | 保留 | ln 映射区间 |

**判据准入条件（测试用例形式）**：合成一段"低频脉冲串 + 稳定底噪"，脉冲率分别取 **2 / 5 / 8 次/秒**，断言：2 与 5 次/秒全部触发（在冷却允许范围内）、8 次/秒在 `COOLDOWN_MS=180` 下**注定漏**（用这条测试把"180 ms 的代价"钉住），在 90 ms 下全部触发。

### R4 · 可单测的纯函数（`FloatArray` 进、`FloatArray`/标量出，不碰 Android）

**必须抽成纯函数（现行代码已有 3 个，建议补 2 个）：**

| 函数 | 签名（建议） | 断言什么 |
|---|---|---|
| `lowPassCoefficient` | `(cutoffHz: Double, sampleRateHz: Int): Double` | 表 §1.2 的 4 组值；`fs<=0 ⇒ 1.0`；结果恒在 [0,1] |
| `centroidOf` | `(low: Double, mid: Double, high: Double): Float` | 单调性（固定 mid，high↑ ⇒ 输出↑）；全零 ⇒ 0；NaN/Inf ⇒ 0；输出 ∈ [0,1] |
| `bandMix` | `(low: Float, mid: Float, high: Float): Float` | 同上；分母为 0 ⇒ 0 |
| **新增** `processFloatArray` | `(x: FloatArray, channelCount: Int, fs: Int, state: FeatureState)` | 用**单声道**与**立体声 L=R** 两种输入跑 §1.7 的判定（修复前后有效 −3 dB = 150 Hz vs 300 Hz） |
| **新增** `updateOnset` | `(low: Float, full: Float, dtMs: Float, state: OnsetState): Float`（返回强度，0=无） | 脉冲串用例（§R3）；`dtMs<=0` 不变；基线不因单次瞬态被"自己抬掉" |

**测试向量（可直接照抄，来自本报告的计算，全部可复现）：**

1. 系数表：`(150, 44100) → 0.021145`、`(2000, 44100) → 0.247949`、`(150, 48000) → 0.019443`、`(2000, 48000) → 0.230335`（±1e-6）；
2. **有效截止频率**：150 Hz 正弦、`FloatArray` 单声道 ⇒ `low` 带输出/输入 ≈ 0.707（−3 dB）；**立体声 L=R ⇒ 修复前 ≈0.707 出现在 300 Hz，修复后回到 150 Hz**（容差 ±10%）；
3. 泄漏表：§1.6 的 10 行（±0.3 dB 容差）；
4. 三带重构恒等式：逐样本 `low + mid + high == v`（浮点容差 1e-12）；
5. 零输入/极端输入：全零 ⇒ 全部输出 0 且无 NaN；`Float.NaN`/`Infinity` 输入 ⇒ 输出仍是有限值（这是防止"静默变透明"的关键回归）；
6. 冷却：合成 **2 秒**、**8 次/秒**（共 16 次）的低频脉冲串，逐缓冲喂入。断言：
   - `COOLDOWN_MS = 180` ⇒ 触发次数 ≤ `floor(2000/180) = 11`（**必然漏 ≥5 次**，把"180 ms 的代价"钉成证据）；
   - `COOLDOWN_MS = 90` ⇒ 触发次数 = 16（`floor(2000/90) = 22 ≥ 16`，全部触发）；
   - 同一条用例顺带断言"两次触发之间的间隔 ≥ 冷却时长"，防止将来有人把冷却改成缓冲计数。

### R5 · 必须实测（验收清单，按优先级）

1. **跑 `AudioTapProbeTest`**（真机 = S6 优先）⇒ 得到帧数直方图 ⇒ 决定 §3.4 的判据粒度、§3.5 的 `τ`、§3.6 的冷却下限；
2. **perfetto**（`sched` + `dalvik`/`heap` + atrace）跑"特征开 / 关"两轮：playback 线程不得有分配导致的 GC，单缓冲耗时增量必须在噪声带内（仓库既有口径：帧 P90 Δ ≤ 1.0 ms、janky% Δ ≤ 2pp，见 [`probe-waveform.md`](docs/verification/v2.8.0/probe-waveform.md) §6.2）；
3. **8 次/秒脉冲串 + 真实曲目**（快鼓点 + 慢歌各一）A/B `COOLDOWN_MS ∈ {60, 90, 180}`，按"视觉是否有击打感/是否重复"定值；
4. **6 声道曲目**（QQ 臻品档）跑一遍：修复前后 `high` 是否还是 0、`centroid01` 是否随内容变化（§1.7 的真机确认）；
5. **每缓冲一次 `exp`** 的实测（§3.5）；
6. **`/32768.0` → `*(1.0/32768.0)`** 的差异（无害优化，测了再改）。

### R6 · 常量台账（谁引的、谁推荐的、谁要校准）

| 常量 | 值 | 来源 |
|---|---|---|
| `a = 1 − exp(−2π·fc/fs)` | 公式 | **引文**：[DSP SE 54086](https://dsp.stackexchange.com/questions/54086/single-pole-iir-low-pass-filter-which-is-the-correct-formula-for-the-decay-coe)、[EarLevel](https://www.earlevel.com/main/2012/12/15/a-one-pole-filter/) |
| `a = dt/(RC+dt)` | 公式（**不采用**） | **引文**：同页（后向差分） |
| `H(z)`、极点、DC 增益 = 1 | 公式 | **引文**：[JOS](https://ccrma.stanford.edu/~jos/fp/One_Pole.html)、[EarLevel 评论区](https://www.earlevel.com/main/2012/12/15/a-one-pole-filter/#comment-53294) |
| −6 dB/oct、一阶做不出带通 | 性质 | **引文**：[EarLevel](https://www.earlevel.com/main/2012/12/15/a-one-pole-filter/) |
| 质心定义式 | 公式 | **引文**：[librosa](https://librosa.org/doc/latest/api/generated/librosa.feature.spectral_centroid.html)（转引 Klapuri & Davy 2007, ch.5） |
| ZCR 定义 / 打击性描述量 | 定义 | **引文**：[librosa](https://librosa.org/doc/latest/api/generated/librosa.feature.zero_crossing_rate.html)、[Gouyon DAFx-00](https://www.francoispachet.fr/wp-content/uploads/2021/01/gouyon-00-dafx00.pdf)、[Wikipedia ZCR](https://en.wikipedia.org/wiki/Zero-crossing_rate) |
| `ZCR = 2f₀/fs`（单正弦） | 恒等式 | **本文推导**（非引文） |
| 自适应阈值 = 帧能量的一阶低通 | 结构 | **引文**：[Dixon DAFx-06](https://dafx.de/paper-archive/2006/papers/p_133.pdf)、[librosa.peak_pick](https://librosa.org/doc/latest/api/generated/librosa.util.peak_pick.html) |
| "在线不能用未来信息/全长归一化" | 约束 | **引文**：[Böck ISMIR 2012](http://ismir2012.ismir.net/event/papers/049_ISMIR_2012.pdf) |
| 30 ms 听感分辨极限 | 常数 | **引文**：[Turchet DAFx-18](https://dafx.de/paper-archive/2018/papers/DAFx2018_paper_51.pdf) |
| `LOW_CUTOFF_HZ = 150` | 常数 | 沿用 v2.9.0；依据 §1.6（人声基频区间 **引文**：[Voice frequency](https://en.wikipedia.org/wiki/Voice_frequency)；贝斯 E1=41.2 Hz **引文**：[Scientific pitch notation](https://en.wikipedia.org/wiki/Scientific_pitch_notation)） |
| `MID_HIGH_CUTOFF_HZ = 2000` | 常数 | 同上（**推荐默认值，需实测校准**） |
| `TAU_MS = 800`（基线） | 常数 | **推荐默认值，需实测校准**（现行注释的 0.8 s 目标；算术见 §3.5） |
| `COOLDOWN_MS = 90`（建议；现行 180） | 常数 | **推荐默认值，需实测校准**（文献只给下界 30 ms） |
| `ABS_MIN = 0.05`、`REL_FACTOR = 0.35`、`FLOOR_LOW = 0.10`、`FLOOR_FULL = 0.12`、`STRENGTH_FULL = 0.20` | 常数 | **推荐默认值，需实测校准**（沿用现行值，文献无对应数值） |
| `CENTROID_F_* = 75/1000/6000`、`[50, 16000]`、`CENTROID_EPS = 1e-9` | 常数 | **推荐默认值，需实测校准**（约定值，非测量值） |
| `ENCODING_PCM_16BIT = 2`、`ENCODING_PCM_FLOAT = 4`、`ENCODING_INVALID = 0` | 常量值 | **引文**：[C](https://developer.android.com/reference/androidx/media3/common/C) |
| 16-bit 有符号 / native endian / `[-32768,32767]`；float `[-1,1]` | 归一化依据 | **引文**：[AudioFormat](https://developer.android.com/reference/android/media/AudioFormat) |

---

## 8. 参考来源（全部于 2026-09-27 抓取）

**DSP / 滤波器**

1. EarLevel Engineering, *A one-pole filter*（2012-12-15，含作者评论回复）—— <https://www.earlevel.com/main/2012/12/15/a-one-pole-filter/>
2. Julius O. Smith, *One-Pole*（CCRMA 在线书）—— <https://ccrma.stanford.edu/~jos/fp/One_Pole.html>
3. Julius O. Smith, *Group Delay* —— <https://ccrma.stanford.edu/~jos/fp/Group_Delay.html>
4. DSP StackExchange 54086（采纳答案）*Single-pole IIR low-pass filter — which is the correct formula for the decay coefficient?* —— <https://dsp.stackexchange.com/questions/54086/single-pole-iir-low-pass-filter-which-is-the-correct-formula-for-the-decay-coe>
5. DSP StackExchange 89388 *Given two low-pass digital IIR filters, find bandpass coefficients* —— <https://dsp.stackexchange.com/questions/89388/given-two-low-pass-digital-iir-filters-find-bandpass-coefficients>
6. DSP StackExchange 21903（采纳答案）*Is a high-passed signal the same as a signal minus a low-passed signal?* —— <https://dsp.stackexchange.com/questions/21903/is-a-high-passed-signal-the-same-as-a-signal-minus-a-low-passed-signal>
7. DSP StackExchange 3182 *Is it correct to subtract a low-pass filtered signal from the original signal and use the result as a "high-pass"?* —— <https://dsp.stackexchange.com/questions/3182/is-it-correct-to-subtract-a-low-pass-filtered-signal-from-the-original-signal-an>
8. Wikipedia, *Low-pass filter* —— <https://en.wikipedia.org/wiki/Low-pass_filter>
9. Wikipedia, *Root mean square* —— <https://en.wikipedia.org/wiki/Root_mean_square>
10. Wikipedia, *Parseval's theorem* —— <https://en.wikipedia.org/wiki/Parseval%27s_theorem>

**特征 / 瞬态检测**

11. librosa, `feature.spectral_centroid` —— <https://librosa.org/doc/latest/api/generated/librosa.feature.spectral_centroid.html>
12. librosa, `feature.zero_crossing_rate` —— <https://librosa.org/doc/latest/api/generated/librosa.feature.zero_crossing_rate.html>
13. librosa, `feature.rms` —— <https://librosa.org/doc/latest/api/generated/librosa.feature.rms.html>
14. librosa, `util.peak_pick` —— <https://librosa.org/doc/latest/api/generated/librosa.util.peak_pick.html>
15. librosa, `onset.onset_strength` —— <https://librosa.org/doc/latest/api/generated/librosa.onset.onset_strength.html>
16. librosa, `onset.onset_detect` —— <https://librosa.org/doc/latest/api/generated/librosa.onset.onset_detect.html>
17. F. Gouyon, F. Pachet, O. Delerue, *On the use of zero-crossing rate for an application of classification of percussive sounds*, DAFx-00 —— <https://www.francoispachet.fr/wp-content/uploads/2021/01/gouyon-00-dafx00.pdf>
18. S. Dixon, *Onset Detection Revisited*, DAFx-06 —— <https://dafx.de/paper-archive/2006/papers/p_133.pdf>
19. S. Böck, F. Krebs, M. Schedl, *Evaluating the Online Capabilities of Onset Detection Methods*, ISMIR 2012 —— <http://ismir2012.ismir.net/event/papers/049_ISMIR_2012.pdf>
20. S. Böck, A. Arzt, F. Krebs, M. Schedl, *Online Real-time Onset Detection with Recurrent Neural Networks*, **DAFx-12（2012）** —— <https://dafx.de/paper-archive/2012/papers/dafx12_submission_4.pdf>
21. C. Duxbury, M. Sandler, M. Davies, *A Hybrid Approach to Musical Note Onset Detection*, DAFx-02 —— <https://dafx.de/paper-archive/2002/DAFX02_Duxbury_Sandler_Davis_note_onset_detection.pdf>
22. L. Turchet, *Hard Real-time Onset Detection of Percussive Sounds*, DAFx-18 —— <https://dafx.de/paper-archive/2018/papers/DAFx2018_paper_51.pdf>
22b. J. P. Bello, L. Daudet, S. Abdallah, C. Duxbury, M. Davies, M. Sandler, *A tutorial on onset detection in music signals*, IEEE TSAP 13(5), 2005 —— DOI <https://doi.org/10.1109/TSA.2005.851998>（**书目信息经 OpenAlex 核实：<https://api.openalex.org/works/doi:10.1109/tsa.2005.851998>；全文 closed access，未抓到，故本文不引用其具体数字**）
23. Wikipedia, *Zero-crossing rate* —— <https://en.wikipedia.org/wiki/Zero-crossing_rate>
24. Wikipedia, *Voice frequency*（人声基频区间）—— <https://en.wikipedia.org/wiki/Voice_frequency>
25. Wikipedia, *Scientific pitch notation*（E1 = 41.20344 Hz）—— <https://en.wikipedia.org/wiki/Scientific_pitch_notation>

**Android / media3**

26. `androidx.media3.exoplayer.ExoPlayer`（线程模型）—— <https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer>
27. `androidx.media3.common.audio.AudioProcessor`（`queueInput` 契约）—— <https://developer.android.com/reference/androidx/media3/common/audio/AudioProcessor>
28. `androidx.media3.exoplayer.audio.TeeAudioProcessor` —— <https://developer.android.com/reference/androidx/media3/exoplayer/audio/TeeAudioProcessor>
29. `TeeAudioProcessor.AudioBufferSink`（`flush`/`handleBuffer` 签名）—— <https://developer.android.com/reference/androidx/media3/exoplayer/audio/TeeAudioProcessor.AudioBufferSink>
30. `TeeAudioProcessor.java` 源码（release 分支）—— <https://raw.githubusercontent.com/androidx/media/release/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/TeeAudioProcessor.java>
31. `androidx.media3.common.C`（PCM 编码常量）—— <https://developer.android.com/reference/androidx/media3/common/C>
32. `android.media.AudioFormat`（16-bit/float 的表示与范围）—— <https://developer.android.com/reference/android/media/AudioFormat>
33. `android.media.audiofx.Visualizer` —— <https://developer.android.com/reference/android/media/audiofx/Visualizer>
34. `android.media.audiofx.AudioEffect`（session 0 插入式效果已废弃）—— <https://developer.android.com/reference/android/media/audiofx/AudioEffect>
35. *Capture video and audio playback*（`AudioPlaybackCapture` 的三条前置条件）—— <https://developer.android.com/guide/topics/media/playback-capture>
36. AOSP, *Avoid priority inversion*（阻塞 ⇒ glitch）—— <https://source.android.com/docs/core/audio/avoiding_pi>

**仓库内证据（非外部引用）**

- [`TransparentWaveformSink.kt`](app/src/main/java/com/takahashirinta/ncrust/player/TransparentWaveformSink.kt)：v2.2.1/v2.8.0/v2.9.0 的契约、`PcmRms`（单一 150 Hz 一阶低通、packed `Long`、绝对下标读取、零分配）
- [`AudioFeatureExtractor.kt`](app/src/main/java/com/takahashirinta/ncrust/player/AudioFeatureExtractor.kt)（**工作区未提交**）：两低通 + 三带 + 质心近似 + 瞬态（本文 §1.7/§3.5 的两处新发现都在这里）
- [`AudioVisualizer.kt`](app/src/main/java/com/takahashirinta/ncrust/ui/player/AudioVisualizer.kt)：`onBar(rms, bass, mix)`、`onFeatures(...)`、`onTransient(...)`、`featuresAvailable()`
- [`WaveformRing.kt`](app/src/main/java/com/takahashirinta/ncrust/ui/player/WaveformRing.kt)：`push(value, bass, mix)`、`ATTACK_TAU_MS = 22f`、`RELEASE_TAU_MS = 130f`
- [`WaveformEffectsState.kt`](app/src/main/java/com/takahashirinta/ncrust/ui/player/waveform/WaveformEffectsState.kt)：外部瞬态计数（单调递增、按差值补触发、上限 `MAX_TRANSIENTS_PER_FRAME`）
- [`AudioTapProbeTest.kt`](app/src/androidTest/java/com/takahashirinta/ncrust/probe/AudioTapProbeTest.kt)：缓冲粒度探针（**未运行**）
- [`probe-waveform.md`](docs/verification/v2.8.0/probe-waveform.md)：v2.8.0 的静态成本代理、异常隔离现状、测量口径（P90 Δ ≤ 1.0 ms / janky% Δ ≤ 2pp）
- `docs/verification/v2.2.1/p0-quality-loop/PROBE.md`：QQ 臻品档返回 6 声道 FLAC 的根因链
