# 其他 Android 音乐播放器的音频可视化取证（ref-research）

> **本文是参考资料（ref-research），不是 Ncrust 的实测报告。**
> 所有结论都来自**当轮抓取的仓库快照原文**（源码 / LICENSE / 文件清单），每条都带 URL。
> 抓不到或没读原文的，一律标「未核实」，**不当论据用**。
>
> **取证方式与时点（重要，影响可信度边界）**：
> GitHub REST API（`api.github.com/repos/...`）在本轮**全程被限流**
> （`API rate limit exceeded for 203.10.99.36`），因此**没有任何一条 `license.spdx_id` 来自 API** ——
> 全部改由仓库内 `LICENSE` 文件原文判定。
> 仓库内容改用 `https://codeload.github.com/<owner>/<repo>/tar.gz/refs/heads/<branch>` 解包后**全仓 grep**，
> 外加 `raw.githubusercontent.com` 取单个文件。各仓库实际取到的分支与文件数：

| 仓库 | 抓到的分支 | 文件数 | 全仓 grep 的仓库范围 |
|---|---|---|---|
| mostafaalagamy/Metrolist | `main` | 1059 | ✅ |
| z-huang/InnerTune | `master` | 506 | ✅ |
| vfsfitvnm/ViMusic | `master` | 352 | ✅ |
| OxygenCobalt/Auxio | `master` | 869 | ✅ |
| vanilla-music/vanilla | `master` | 597 | ✅ |
| maxrave-dev/SimpMusic | `main` | 900 | ✅ |
| FoedusProgramme/Gramophone | `master` | 896（仅文件清单，`--filter=blob:none`） | ⚠️ 只列了路径，未读全部内容 |
| Moriafly/SaltPlayerSource | `main` / `master` | 仅 README + LICENSE | ⚠️ 无应用源码 |

---

## 0. 结论摘要（TL;DR）

| # | 结论 | 依据 |
|---|---|---|
| 1 | **这 7 个播放器里没有一个是「音频可视化」播放器。** 对 7 个仓库全量源码 grep `audiofx.Visualizer` / `new Visualizer` / `Visualizer(` / `getFft` / `getWaveForm` / `setDataCaptureListener` —— **命中 0 条**。不是「实现得不好」，是**根本没接这条 API**。 | §附·检索式；各仓库源码快照 |
| 2 | **唯一出现真 FFT 的地方是 Metrolist 的听歌识曲**（Shazam 式指纹），不是可视化：`ShazamSignatureGenerator.kt`，`FFT_SIZE = 2048`、`SAMPLE_RATE = 16_000`、Hann 窗、迭代式实数 FFT。它跑在 `AudioRecord` 采到的 12 s 单声道 44.1 kHz 录音上，**跟播放画面无关**。 | [ShazamSignatureGenerator.kt](https://github.com/mostafaalagamy/Metrolist/blob/main/app/src/main/kotlin/com/metrolist/music/recognition/ShazamSignatureGenerator.kt)、[MusicRecognitionService.kt](https://github.com/mostafaalagamy/Metrolist/blob/main/app/src/main/kotlin/com/metrolist/music/recognition/MusicRecognitionService.kt) |
| 3 | **三个项目（Metrolist / Auxio / Gramophone）已经用 media3 `BaseAudioProcessor` 子类拿到了自己的 PCM**，用途分别是静音检测、ReplayGain、音量归一化、参数均衡 —— 说明「在播放链路里读 PCM」在这批项目里是**被走通了的常规做法**，不需要任何权限。 | §1 §4 §7 |
| 4 | **InnerTune / ViMusic 用的是 media3 官方 `SilenceSkippingAudioProcessor(2_000_000, 20_000, 256)`**，即「2 s / 20 ms / 幅度阈值 256」这一组常量在多个独立项目里重复出现，是媒体播放器的通用默认值，可作为 Ncrust 阈值选取的横向参照。 | [InnerTune MusicService.kt:626](https://github.com/z-huang/InnerTune/blob/master/app/src/main/java/com/zionhuang/music/playback/MusicService.kt#L626)、[ViMusic PlayerService.kt:852](https://github.com/vfsfitvnm/ViMusic/blob/master/app/src/main/kotlin/it/vfsfitvnm/vimusic/service/PlayerService.kt#L852) |
| 5 | **这批项目里唯一与「BPM」有关的常量是 SimpMusic 的混响延迟默认值**：`DEFAULT_DELAY_TIME_MS = 400`，注释写着「Quarter-note-ish spacing at 150 bpm」—— 它是**人工设定的固定周期**，不来自音频分析。这正好是「固定周期装饰 vs 真正音频驱动」的分界样例。 | [SettingsViewModel.kt:2138](https://github.com/maxrave-dev/SimpMusic/blob/main/composeApp/src/commonMain/kotlin/com/maxrave/simpmusic/viewModel/SettingsViewModel.kt#L2138) |
| 6 | **7 个项目的许可证全部是 GPL-3.0**（LICENSE 原文 `GNU GENERAL PUBLIC LICENSE Version 3, 29 June 2007`）。唯一的例外 Salt Player 的公开仓库是 MIT，但**那里面没有应用源码**。 | §1–§8 |

---

## 1. Metrolist（`mostafaalagamy/Metrolist`）

**取证 URL**：<https://github.com/mostafaalagamy/Metrolist>（分支 `main`，1059 个文件）

1. **许可证**：**GPL-3.0**。`LICENSE` 首行原文 `GNU GENERAL PUBLIC LICENSE / Version 3, 29 June 2007`
   （<https://github.com/mostafaalagamy/Metrolist/blob/main/LICENSE>）。
   源码文件头也自称 GPL-3.0，例如 `SilenceDetectorAudioProcessor.kt` 的头注释
   `Metrolist Project (C) 2026 / Licensed under GPL-3.0 | See git history for contributors`。
2. **是否做音频可视化**：**否**。全仓 `Visualizer` 只命中「标题清洗正则」和「歌词清洗正则」里的
   *visualizer* 这个**英文单词**（把 `(Official Visualizer)` 从歌名里去掉），与音频可视化无关：
   `app/src/main/kotlin/com/metrolist/paxsenix/Paxsenix.kt:90`、`.../lrclib/LrcLib.kt:41`。
   `audioSessionId` 的用法只有一处用途：把会话号塞进系统均衡器面板的 Intent
   （`PlayerMenu.kt:706-709`，`AudioEffect.EXTRA_AUDIO_SESSION`）。
3. **数据通路（PCM，供 Ncrust 参考）**：三个 **media3 `BaseAudioProcessor` 子类**，
   在 `MusicService.createExoPlayer()` 里创建并注入渲染器工厂：

   | 文件 | 作用 | 关键字面证据 |
   |---|---|---|
   | [`playback/audio/SilenceDetectorAudioProcessor.kt`](https://github.com/mostafaalagamy/Metrolist/blob/main/app/src/main/kotlin/com/metrolist/music/playback/audio/SilenceDetectorAudioProcessor.kt) | 长静音检测 | `:23 minSilenceDurationUs = 2_000_000L`、`:24 silenceThreshold = 256`、`:42` 只接受 `C.ENCODING_PCM_16BIT`、`:59 replaceOutputBuffer(...)`、`:66 inputBuffer.order(ByteOrder.LITTLE_ENDIAN)`、`:80 consecutiveSilentFrames * 1_000_000L / sampleRate >= minSilenceDurationUs` |
   | [`playback/audio/VolumeNormalizationAudioProcessor.kt`](https://github.com/mostafaalagamy/Metrolist/blob/main/app/src/main/kotlin/com/metrolist/music/playback/audio/VolumeNormalizationAudioProcessor.kt) | 音量归一化 | `bytesPerSample` 按编码映射（16bit→2、24bit→3、32bit/FLOAT→4）；增益 `10.0.pow(gainMb / 2000.0)` |
   | [`eq/audio/CustomEqualizerAudioProcessor.kt`](https://github.com/mostafaalagamy/Metrolist/blob/main/app/src/main/kotlin/com/metrolist/music/eq/audio/CustomEqualizerAudioProcessor.kt) | AutoEQ 参数均衡 | biquad 级联；`bands.filter { it.enabled && it.frequency < sampleRate / 2.0 }`（Nyquist 保护）；preamp `10^(preamp/20)` |

   注入点：`MusicService.kt:1286-1289`（三个 processor 实例化）、`:3966-3968`（`createRenderersFactory(...)` 形参）、
   `:419 / :425 / :426`（按 Player 存的三个 HashMap）。
4. **特征提取**：
   - **可视化方向：无。**没有 waveform / FFT / 频带 / RMS / onset 的任何可视化消费者。
   - **静音检测方向（可复用）**：逐帧取**跨声道峰值** `framePeak = maxOf(framePeak, abs(getShort(index).toInt()))`，
     与常量阈值 `256` 比较，用「连续静音帧数 × 1e6 / sampleRate ≥ 2_000_000 µs」判长静音。
     用 `getShort(绝对下标)` 读，**不改动 buffer 的 position**，随后 `replaceOutputBuffer(...).apply { put(inputBuffer); flip() }` 原样透传。
   - **听歌识曲方向（非可视化）**：`MusicRecognitionService.kt` 用 `AudioRecord`
     （`:42 RECORDING_SAMPLE_RATE = 44100`、`:43 CHANNEL_IN_MONO`、`:44 ENCODING_PCM_16BIT`、`:47 RECORDING_DURATION_MS = 12000L`），
     重采样到 `VibraSignature.REQUIRED_SAMPLE_RATE` 后送 `ShazamSignatureGenerator`：
     `:25 SAMPLE_RATE = 16_000`、`:26 FFT_SIZE = 2048`、`:27 FFT_OUTPUT_SIZE = 1025`、`:28 MAX_PEAKS = 255`、
     `:29 MAX_TIME_SECONDS = 12.0`、`:32 RING_BUF_SIZE = 256`、`:35-38` 四个频带
     （`BAND_250_520 / BAND_520_1450 / BAND_1450_3500 / BAND_3500_5500`）、`:45 HANNING` 窗、`:317` 注释指明「size 2048 的实数 FFT，迭代实现」。
5. **可视化是否真正音频驱动**：**该项目没有可视化**。其 UI 侧动画是**固定周期装饰**，可作反例引用：
   `ui/screens/recognition/RecognitionScreen.kt:430` 附近 `rememberInfiniteTransition(label = "rotate")`，
   `:350` 附近 `rememberInfiniteTransition(label = "pulse")` + `tween(1000, easing = LinearEasing)` + `RepeatMode.Reverse` ——
   即使是**这个唯一跑真 FFT 的界面**，它的脉冲/旋转也是**固定 1 s 周期**，与音频内容无关。
   `ui/screens/wrapped/components/AnimatedBackground.kt:56`、`AnimatedDecorativeElement.kt:33` 同理。
6. **便宜且聪明、值得抄的**：
   - 静音检测用**整数峰值 + 整数阈值（256）**，全程无浮点、无分配，还能当「跳过前奏/间奏」的廉价音频驱动信号；
   - 透传写法 `replaceOutputBuffer(remaining).apply { put(inputBuffer); flip() }` —— 处理器只读不消费输入；
   - 用 `ByteOrder.LITTLE_ENDIAN` + `getShort(绝对下标)` 读样本，**不动 position**，所以后续透传零风险；
   - 只声明 `C.ENCODING_PCM_16BIT`，其它编码直接 `throw AudioProcessor.UnhandledAudioFormatException`
     ——**拒收比降级安全**，避免在音频线程上做半成品的位深转换。

---

## 2. InnerTune（`z-huang/InnerTune`）

**取证 URL**：<https://github.com/z-huang/InnerTune>（分支 `master`，506 个文件）

1. **许可证**：**GPL-3.0**，`LICENSE` 首行 `GNU GENERAL PUBLIC LICENSE / Version 3, 29 June 2007`
   （<https://github.com/z-huang/InnerTune/blob/master/LICENSE>）。
2. **是否做音频可视化**：**否**。全仓 `Visualizer` / `getFft` / `getWaveForm` **0 命中**。
   `audioSessionId` 只有两处，都是给系统均衡器面板传会话号：
   `playback/MusicService.kt:438`、`:448`，`ui/menu/PlayerMenu.kt:268`。
3. **数据通路**：**只用了 media3 官方默认链，没有自定义 `AudioProcessor`**。
   `playback/MusicService.kt:623-627`：
   `DefaultAudioSink.DefaultAudioProcessorChain(emptyArray(), SilenceSkippingAudioProcessor(2_000_000, 20_000, 256), SonicAudioProcessor())`。
   注意第一个参数 `emptyArray()` —— **它明确留了一个「可插自定义 processor」的空位**。
4. **特征提取**：**无**。连 RMS/峰值都没有（静音跳过完全交给 media3 的 `SilenceSkippingAudioProcessor`）。
   对 Ncrust 的意义：这是「**没做**」的干净样本 —— 一个 500 文件级别的成熟播放器，可以完全不碰 PCM 特征。
5. **可视化是否真正音频驱动**：**没有可视化**（无任何可视化代码可判）。
6. **便宜且聪明、值得抄的**：常量组 `(2_000_000, 20_000, 256)` 与 Metrolist 的自研静音检测在
   「2 秒 / 幅度 256」上**完全一致**——说明 Ncrust 若要做静音或能量门限，这三个数字是社区默认值，
   选它们不需要额外论证。

---

## 3. ViMusic（`vfsfitvnm/ViMusic`）

**取证 URL**：<https://github.com/vfsfitvnm/ViMusic>（分支 `master`，352 个文件）

1. **许可证**：**GPL-3.0**，`LICENSE` 原文同上
   （<https://github.com/vfsfitvnm/ViMusic/blob/master/LICENSE>）。
2. **是否做音频可视化**：**否**。全仓 `Visualizer` / `getFft` / `getWaveForm` **0 命中**。
   `app/src/main/res/drawable/equalizer.xml` 只是设置项图标。
3. **数据通路**：同样是 media3 默认链，无自定义 processor：
   `app/src/main/kotlin/it/vfsfitvnm/vimusic/service/PlayerService.kt:849-853`，
   `DefaultAudioProcessorChain(emptyArray(), SilenceSkippingAudioProcessor(2_000_000, 20_000, 256), SonicAudioProcessor())`。
   另有一处**基于会话的音效**：`:473 loudnessEnhancer = LoudnessEnhancer(player.audioSessionId)`
   —— 走系统 `AudioEffect`，同样不读 PCM。均衡器入口在 `ui/screens/player/Player.kt:401`、`ui/screens/settings/PlayerSettings.kt:115`。
4. **特征提取**：**无**。
5. **可视化是否真正音频驱动**：**没有可视化**。
6. **便宜且聪明、值得抄的**：**没有**。ViMusic 在这一点上（相对于 InnerTune）没有新增可抄的东西 ——
   它的价值是「GPL-3.0 的又一个大体量播放器同样选择不做可视化」这一**反面证据的样本量**。

---

## 4. Auxio（`OxygenCobalt/Auxio`）

**取证 URL**：<https://github.com/OxygenCobalt/Auxio>（分支 `master`，869 个文件）

1. **许可证**：**GPL-3.0**，`LICENSE` 原文同上
   （<https://github.com/OxygenCobalt/Auxio/blob/master/LICENSE>）。
2. **是否做音频可视化**：**否**。全仓 `Visualizer` / `getFft` / `getWaveForm` **0 命中**。
   文件清单里唯一形似的 `app/src/main/java/org/oxycblt/auxio/home/ThemedSpeedDialView.kt` 是首页快捷拨号视图，与音频无关。
3. **数据通路**：**自定义 media3 `AudioProcessor`**：
   [`playback/replaygain/ReplayGainAudioProcessor.kt`](https://github.com/OxygenCobalt/Auxio/blob/master/app/src/main/java/org/oxycblt/auxio/playback/replaygain/ReplayGainAudioProcessor.kt)
   （`class ReplayGainAudioProcessor @Inject constructor(...) : BaseAudioProcessor(), PlaybackStateManager.Listener, PlaybackSettings.Listener`），
   挂载点 `playback/service/ExoPlaybackStateHolder.kt:669` → `.setAudioProcessors(arrayOf(replayGainProcessor))`。
4. **特征提取**：**没有做运行时分析** —— 增益值来自标签里的 ReplayGain 数据（预估增益），不是对 PCM 求 RMS/峰值。
   该类 KDoc 原文（逐字）：
   > An `AudioProcessor` that handles ReplayGain values and their amplification of the audio stream.
   > Instead of leveraging the volume attribute like other implementations, this system manipulates
   > the bitstream itself to modify the volume, which allows the use of positive ReplayGain values.
   即：**只做乘增益，不做测量**。属性 `volume` 的 setter 里调 `flush()`（改增益即冲刷流）。
5. **可视化是否真正音频驱动**：**没有可视化**。
6. **便宜且聪明、值得抄的**：
   - **「改比特流而不是改音量属性」**：为了支持**正的**增益（音量属性只能衰减），它选择在 PCM 上乘系数 ——
     Ncrust 若要做「归一化音量」而不想动 `player.volume`，这是现成理由；
   - `playback/replaygain/` 这个**按用途分包**的目录组织，比把 processor 全塞进 `playback/` 更好找；
   - processor 同时实现 `PlaybackStateManager.Listener` / `PlaybackSettings.Listener`，
     **配置变更走监听器而不是每帧读 SharedPreferences** —— 音频线程上零 I/O。

---

## 5. Vanilla Music（`vanilla-music/vanilla`）

**取证 URL**：<https://github.com/vanilla-music/vanilla>（分支 `master`，597 个文件）

1. **许可证**：**GPL-3.0**，`LICENSE` 原文同上
   （<https://github.com/vanilla-music/vanilla/blob/master/LICENSE>）。
2. **是否做音频可视化**：**否**。全仓 grep `Visualizer|AudioProcessor|AudioRecord|audioSessionId|Equalizer`
   只有**一条**命中：`app/src/main/java/ch/blinkenlights/android/vanilla/PreferencesActivity.java:147`
   `public static class EqualizerFragment extends PreferenceFragment` —— 是**设置页里的系统均衡器入口**，
   不是可视化，也不是 PCM 处理。
3. **数据通路**：**无自定义 PCM 通路**。它用自己的 `VanillaMediaPlayer.java` / `PlaybackService.java`
   （路径见仓库），**未核实**它是否使用 media3（对 `app/build.gradle` 检索 `media3|exoplayer` 无命中，
   但未核实是否存在其它构建文件）。
4. **特征提取**：**无**。
5. **可视化是否真正音频驱动**：**没有可视化**。
6. **便宜且聪明、值得抄的**：**没有**。价值同上：它是「**不用 media3 的老牌播放器也不做可视化**」这一样本。

---

## 6. SimpMusic（`maxrave-dev/SimpMusic`，InnerTune 生态的 fork）

**取证 URL**：<https://github.com/maxrave-dev/SimpMusic>（分支 `main`，900 个文件）

1. **许可证**：**GPL-3.0**，`LICENSE` 原文同上
   （<https://github.com/maxrave-dev/SimpMusic/blob/main/LICENSE>）。
2. **是否做音频可视化**：**否**。全仓 `Visualizer` / `getFft` / `getWaveForm` **0 命中**。
   唯一含「spectrum」字样的地方是**注释**，且说的是绘制静态 EQ 曲线：
   `ui/screen/home/EqualizerSection.kt:362` —— `// Filled underneath, the way a spectrum reads — the area says "this much of this range",`
   它画的是**用户设定的增益响应**，与正在播放的音频无关。
3. **数据通路**：**没有任何自定义 `AudioProcessor`**（全仓 `AudioProcessor` 0 命中）。
   音效只有一条路：**拉起系统音效面板**，`expect/ui/OpenEq.android.kt:18` 起的
   `Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)` +
   `:25 putExtra(AudioEffect.EXTRA_AUDIO_SESSION, audioSessionId)`；
   会话号来自 `:272 fun getAudioSessionId() = mediaPlayerHandler.player.audioSessionId`（`viewModel/SettingsViewModel.kt`）。
4. **特征提取**：**无**。均衡器频点表是**纯显示常量**，注释原文：
   > `/** Band centre labels, for display only — the backend owns the actual frequencies. */`
   > `val EQUALIZER_BAND_LABELS = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")`
   （`viewModel/SettingsViewModel.kt:2131-2132`）——**「后台拥有真实频点、前端只画标签」**这个分工值得抄。
5. **可视化是否真正音频驱动**：**没有可视化**。但有两个**「看起来像音频驱动、实际不是」**的现成反例，值得 Ncrust 引以为戒：
   - `ui/component/HeartBurstEffect.kt` —— 文件头 KDoc 逐字自述：
     > `Owns the live bursts and fires new ones — from the LIKE TAP, never from state.`
     即：**由用户点赞手势触发，与音频无关**；
   - `viewModel/SettingsViewModel.kt:2138` ——
     > `/** Quarter-note-ish spacing at 150 bpm; see `DataStoreManagerImpl.delayTimeMs`. */`
     > `const val DEFAULT_DELAY_TIME_MS = 400`
     **把 150 BPM 的「四分音符感」硬编码成 400 ms 常量**：这是「用固定的音乐学数字装成有节奏感」的典型。
6. **便宜且聪明、值得抄的**：上面的「显示标签 vs 真实频点分离」；以及它把 `expect/actual`
   的均衡器入口做成 `OpenEqLauncher`（跨 JVM/Android）——如果 Ncrust 未来要跨端，这个抽象形状可参考。

---

## 7. Gramophone（`FoedusProgramme/Gramophone`）

> ⚠️ **任务书给的 `phansier/Gramophone` 取不到**：对 `raw.githubusercontent.com/phansier/Gramophone/{master,main}/LICENSE`
> 与 `README.md` 全部返回 `404: Not Found`。改用 GitHub 搜索定位到同名的 `FoedusProgramme/Gramophone`
> （搜索结果描述与 `owns` 关系均指向该项目：`A sane music player built with media3 and material design library
> that is following android's standard strictly.`，`license: GPL-3.0`）。
> **「phansier/Gramophone 是否就是 FoedusProgramme/Gramophone 的前身（改名/转移）」这一点未核实。**

**取证 URL**：<https://github.com/FoedusProgramme/Gramophone>（分支 `master`，`git ls-tree` 896 个 blob）

1. **许可证**：**GPL-3.0**，`LICENSE` 首行 `GNU GENERAL PUBLIC LICENSE / Version 3, 29 June 2007`
   （<https://github.com/FoedusProgramme/Gramophone/blob/master/LICENSE>）。
2. **是否做音频可视化**：**未发现**（依据：文件清单里**没有** visual / fft / spectrum / waveform 相关模块）。
   ⚠️ 本次只拉了 `--filter=blob:none` 的**文件清单**，**没有逐文件读内容**，所以「没有可视化」这一条
   比 §1–§6 弱一档，标为**部分核实**。
3. **数据通路**：文件清单显示存在自定义 `AudioProcessor`：
   `app/src/main/java/org/akanework/gramophone/logic/utils/ReplayGainAudioProcessor.kt`；
   另有 `logic/utils/exoplayer/ExtendedAudioOutput.kt`、`NativeTrackAudioOutput.java`、
   `hificore/src/main/cpp/audio-legacy.h` 等自定义音频输出层（**内容均未读，行为未核实**）。
4. **特征提取**：**未核实**（同上，未读内容）。仅凭文件名不足以判定是否有 RMS/FFT。
5. **可视化是否真正音频驱动**：**未核实**。
6. **便宜且聪明、值得抄的**：**本次未取证，不写**（避免从文件名编造结论）。
   唯一可记的是**结构信号**：它把自定义音频链路集中在 `logic/utils/exoplayer/` 下，
   并有独立的 `hificore/` 模块（含 C++ 头文件）——**说明「音频输出这一层值得单独成模块」**。

---

## 8. Salt Player / 椒盐音乐（`Moriafly/SaltPlayerSource`）

**取证 URL**：<https://github.com/Moriafly/SaltPlayerSource>

1. **许可证**：**MIT**。`LICENSE` 首行原文 `MIT License` / `Copyright (c) 2021-2026 Moriafly`
   （master 与 main 两个分支上内容一致：<https://raw.githubusercontent.com/Moriafly/SaltPlayerSource/main/LICENSE>）。
   注意：**这个 MIT 覆盖的是该仓库里的文件**，不是「Salt Player 应用本体开源」。
2. **是否做音频可视化**：**未核实**。README 原文（逐字）说明了仓库性质：
   > `Salt Player is a local music playback app. This repository is used for releasing new versions, collecting feedback, and posting announcements.`
   即**仓库仅用于发版、收集反馈、发布公告**，**不含播放器源码**。因此
   「它有没有可视化」在公开渠道**无法取证**，不写成「有」也不写成「没有」。
3. **数据通路 / 4. 特征提取 / 5. 是否音频驱动**：**均未核实**（无源码）。
6. **便宜且聪明、值得抄的**：**无可取证内容**。它的价值在于**许可证事实**：
   「MIT 的公开仓库 ≠ MIT 的应用」——引用别人的「开源播放器」时，**先确认仓库里有没有实现代码**，
   否则会把「发版仓库的 MIT」误当成「实现可抄」，这是许可证合规上真实存在的陷阱。

---

## 横向对比表

| 项目 | 许可证 | 数据通路 | 特征 | 可视化 | 备注 |
|---|---|---|---|---|---|
| **Metrolist**<br>`mostafaalagamy/Metrolist` | GPL-3.0 | media3 `BaseAudioProcessor` ×3（静音检测 / 音量归一化 / 参数均衡）；`AudioRecord` 仅用于识曲 | 静音检测：逐帧跨声道**峰值** + 阈值 `256` + 2 s 时长；识曲：`FFT_SIZE=2048`/16 kHz/Hann/4 频带/`MAX_PEAKS=255` | **无**（`Visualizer` 命中仅为歌名清洗正则里的英文单词） | 唯一有真 FFT 的项目，**但 FFT 服务于识曲不是可视化**；UI 脉冲是固定 1 s 周期 |
| **InnerTune**<br>`z-huang/InnerTune` | GPL-3.0 | media3 默认链 `DefaultAudioProcessorChain(emptyArray(), SilenceSkippingAudioProcessor(2_000_000, 20_000, 256), SonicAudioProcessor())` | **无** | **无** | `emptyArray()` 明确留了自定义 processor 空位；`audioSessionId` 仅用于系统 EQ |
| **ViMusic**<br>`vfsfitvnm/ViMusic` | GPL-3.0 | 同 InnerTune 默认链；`LoudnessEnhancer(player.audioSessionId)` | **无** | **无** | 大体量 GPLv3 播放器同样不做可视化（反面证据样本） |
| **Auxio**<br>`OxygenCobalt/Auxio` | GPL-3.0 | media3 `ReplayGainAudioProcessor`（`setAudioProcessors(arrayOf(...))`） | **无运行时分析**（增益来自标签） | **无** | 「改比特流而非音量属性」以支持正增益；配置走 Listener，音频线程零 I/O |
| **Vanilla Music**<br>`vanilla-music/vanilla` | GPL-3.0 | **无自定义 PCM 通路**（仅设置页 `EqualizerFragment` 系统 EQ 入口） | **无** | **无** | 是否用 media3 **未核实**；老牌播放器也不做可视化 |
| **SimpMusic**<br>`maxrave-dev/SimpMusic` | GPL-3.0 | **无自定义 `AudioProcessor`**；仅 `ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL` + `EXTRA_AUDIO_SESSION` | **无**；EQ 频点表自述「display only」 | **无** | 「像音频驱动但其实不是」两个反例：`HeartBurstEffect`（点赞触发）、`DEFAULT_DELAY_TIME_MS = 400`（注释称 150 BPM 四分音符） |
| **Gramophone**<br>`FoedusProgramme/Gramophone` | GPL-3.0 | 清单见 `logic/utils/ReplayGainAudioProcessor.kt`、`.../exoplayer/ExtendedAudioOutput.kt`、`hificore/`（**内容未读**） | **未核实** | **未发现**（仅凭文件清单，**部分核实**） | `phansier/Gramophone` 返回 404，改用搜索到的同名仓库；两者关系**未核实** |
| **Salt Player / 椒盐音乐**<br>`Moriafly/SaltPlayerSource` | **MIT**（仅仓库文件） | 无源码 | 无源码 | **未核实** | 仓库自述「仅用于发版/反馈/公告」，**不含应用源码**；MIT ≠ 应用开源 |

---

## 对 Ncrust 的结论

> 前置事实（仓库内，已核）：Ncrust `minSdk = 24`（`app/build.gradle.kts:33`），
> 主许可 GPLv3（`LICENSE` 首行 `GNU GENERAL PUBLIC LICENSE / Version 3, 29 June 2007`），
> 且**已经**存在 `player/VisualizerRenderersFactory.kt` + `player/TransparentWaveformSink.kt` +
> `ui/player/AudioVisualizer.kt` 这条「TeeAudioProcessor 读自己的 PCM、**不需要任何权限**」的路径
> （`ui/player/AudioVisualizer.kt:104-106` 的 KDoc 明写「不需要任何权限，也绕开了
> `android.media.audiofx.Visualizer` 那条要 RECORD_AUDIO 的路」）。
> 也就是说：**下面 (a)(b)(d) 是在「PCM 已经在手上」的前提下回答的。**

### (a) Android `Visualizer` API 在这里有吸引力吗？——**没有，明确不采用**

四条独立理由，任一条单独就足以否决：

1. **要 `RECORD_AUDIO` 权限**，与项目定位冲突。Ncrust 自己的代码注释已断言
   `player/VisualizerRenderersFactory.kt:32-33`：
   > 备选方案 `android.media.audiofx.Visualizer` 从 AOSP 7.1.1 起就要求 **RECORD_AUDIO**（不是"Android 10+ 才要"），与项目定位冲突，不采用。
   官方文档同样把它列为需要 `RECORD_AUDIO` 的 API（[Visualizer | Android Developers](https://developer.android.com/reference/android/media/audiofx/Visualizer)）。
   ⚠️「从 **AOSP 7.1.1** 起」这一具体版本断言，本轮**未追溯到 AOSP 提交**，属**未核实**（作为仓库内既有结论引用，未当独立事实）。
2. **它抓的是输出混音（或指定 audio session），而 Ncrust 已经能拿到「自己这一路」的 PCM。**
   抓别的应用还要 `MediaProjection` 用户授权 + 同 user profile（[播放捕获指南](https://developer.android.com/guide/topics/media/playback-capture)）——
   Ncrust 没有这个需求。
3. **数据质量更低**：`getFft()` 给的是 **8-bit 幅度** FFT，capture size 必须是 **2 的幂**。
   8 bit ≈ 48 dB 的可用动态范围，对「安静段落」的分辨力远不如 Ncrust 手上的 16-bit PCM。
4. **横向证据**：本次取证的 **7 个播放器（含 3 个已经用上自定义 `AudioProcessor` 的项目）0 个使用它**（§0 结论 1）。
   一个「所有人都能用」的 API 在成熟项目里 0 采用，本身就是很强的信号。

**结论**：保持现状 —— 走 `TeeAudioProcessor` / `BaseAudioProcessor` 路径，
**不引入 `RECORD_AUDIO` 权限**，也不要为它写第二套数据源。

### (b) GPLv3 应用 + Galaxy S6（API 24）验收机，真 FFT 值得吗？——**为「可视化」不值得；为「识别」才值得**

- **横向证据**：这批项目里唯一的真 FFT 是 Metrolist 的 **Shazam 式听歌识曲**（2048 点、16 kHz、12 s、Hann 窗、`MAX_PEAKS=255`），
  **没有一个项目为了画面去做 FFT**（§0 结论 1/2）。做的人都是为「识别」这个**必须有频谱**的目标。
- **收益侧**：Ncrust 的画面是 **28 根柱**（`ui/player/AudioVisualizer.kt:117 const val BAR_COUNT = 28`）。
  1024/2048 点 FFT 得到 512/1025 个 bin，最终仍要归并到 28 根柱 ——
  而**现有的一阶滤波器组已经能给出单调的频带能量代理**，把 FFT 的额外信息量压到 28 根柱上，
  视觉上的增量极小（真正质变的是「频谱质心」这类指标，而现有文档已论证时间域只能给单调代理，
  见 [`audio-feature-extraction.md`](audio-feature-extraction.md) §0 结论 6）。
- **成本侧**：按同仓库文档的算术，现行无 FFT 实现约 **17 flop/样本 ≈ 1.50 Mflop/s（44.1 kHz 立体声）**，
  并已明确「**真正的风险是音频线程上的分配/阻塞，不是吞吐**」（同文档 §0 结论 13）。
  FFT 引入的**新风险恰好落在这一项上**：窗函数表、复数缓冲、位反转表、每帧的幅度开方 ——
  都是「要么预分配并复用、要么在音频线程上分配」的取舍。S6（API 24，2015 年机器）上是**风险净增**。
- **结论**：
  1. **可视化不要上 FFT**。用一阶滤波器组 + 峰值/RMS + 瞬态检测（现有路线）即可；
  2. **若将来做听歌识曲/指纹**，再引入 FFT —— 那时它是**功能必需**，且有 Metrolist 的
     `FFT_SIZE=2048 / SAMPLE_RATE=16_000 / Hann / 4 频带` 这一组**可直接照抄的参数起点**（§1·4）。
     注意它的 16 kHz 是**为指纹算法降采样后的**值，不是播放采样率；
  3. 上 FFT 之前必须先解决「**每缓冲一次回调、回调粒度未知**」这件未决事
     （同文档 §0 结论 14：4096 帧 ≈93 ms 与 1024 帧 ≈23 ms 都在可能范围内，
     对瞬态检测是「可用与不可用」的区别）——**粒度没实测之前，FFT 的收益/成本都无法定论**。

### (c) 许可证兼容性：哪些能直接抄进 GPLv3 的 Ncrust？

| 项目 | 许可证 | 与 GPLv3 的兼容性 | 能否把代码抄进 Ncrust |
|---|---|---|---|
| Metrolist | GPL-3.0 | 同许可 | ✅ 可以（保留版权与许可声明；GPLv3 之间可直接合并） |
| InnerTune | GPL-3.0 | 同许可 | ✅ 可以 |
| ViMusic | GPL-3.0 | 同许可 | ✅ 可以 |
| Auxio | GPL-3.0 | 同许可 | ✅ 可以 |
| Vanilla Music | GPL-3.0 | 同许可 | ✅ 可以 |
| SimpMusic | GPL-3.0 | 同许可 | ✅ 可以 |
| Gramophone | GPL-3.0 | 同许可 | ✅ 可以（本次只核了 LICENSE，未读实现） |
| Salt Player（公开仓库） | MIT（仅仓库文件） | MIT 与 GPLv3 兼容（单向） | ⚠️ **无实现可抄**；仅能抄其 README/LICENSE 形态，与音频可视化无关 |

- **注意 1**：本次所有许可证结论都来自**仓库内 `LICENSE` 原文**，**没有**一条来自
  GitHub API 的 `license.spdx_id`（API 全程限流，见文首）。若要把某段代码真的并入 Ncrust，
  应再核一次**该文件头部的版权声明**（例如 Metrolist 的文件头写
  `Metrolist Project (C) 2026 / Licensed under GPL-3.0 | See git history for contributors`，
  这是**署名要求**，抄的时候必须保留）。
- **注意 2**：Ncrust 自身是 **GPLv3 + 原始部分 MIT** 的双许可结构（`LICENSE` / `LICENSE-MIT`，见
  `ui/player/AudioVisualizer.kt:1-7` 的文件头）。**并入 GPLv3 代码不影响 MIT 部分**，
  但并入后的那个文件整体受 GPLv3 约束 —— 文件头声明要跟着改，别留在 MIT 头下。

### (d) 没有 FFT 时，最便宜的「真正音频驱动」特征集

按「代价从低到高」排列，全部来自本次取证的**实际代码**（不是设想）：

| 优先级 | 特征 | 代价 | 现成出处（可直接参考的常量/写法） |
|---|---|---|---|
| 1 | **逐帧跨声道峰值** | 每样本 1 次 `abs` + 1 次 `max`，全整数、零分配 | Metrolist `SilenceDetectorAudioProcessor.kt`：`framePeak = maxOf(framePeak, abs(getShort(index).toInt()))`；阈值 `256`；`getShort(绝对下标)` **不动 position** |
| 2 | **二值静音/有声状态（含最短时长）** | 一次整数除法比较 | 同上 `:80`：`consecutiveSilentFrames * 1_000_000L / sampleRate >= minSilenceDurationUs`（`2_000_000L` = 2 s）。**同一组常量在 InnerTune / ViMusic 的 `SilenceSkippingAudioProcessor(2_000_000, 20_000, 256)` 里独立重现** —— 选它不需要额外论证 |
| 3 | **RMS / 短时能量** | 每样本 1 乘 1 加（可整数），一次开方/缓冲 | 你已有的 `PcmRms.analyze`（[`player/TransparentWaveformSink.kt`](app/src/main/java/com/takahashirinta/ncrust/player/TransparentWaveformSink.kt)）；本条为仓库内既有能力，非外部取证 |
| 4 | **一阶滤波器组的三带能量** | 每样本约 17 flop（现有实测口径） | 你已有的 [`AudioFeatureExtractor.kt`](app/src/main/java/com/takahashirinta/ncrust/player/AudioFeatureExtractor.kt) + [`audio-feature-extraction.md`](audio-feature-extraction.md) §1 |
| 5 | **瞬态/onset（自适应阈值 + 冷却）** | 检测函数 + 滑动基线 + 最小间隔 | 同文档 §3（Dixon 自适应阈值 / `peak_pick`）；横向项目**无人实现**，属 Ncrust 的差异化能力 |
| 6 | **编码白名单（拒收而非降级）** | 0（一次判断） | Metrolist `:42 if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(...)` —— **只处理你确定的格式**，避免在音频线程上写半成品位深转换 |
| 7 | **透传写法（处理器只读不消费）** | 0 | 同上 `:59 replaceOutputBuffer(inputBuffer.remaining()).apply { put(inputBuffer); flip() }` + `inputBuffer.order(ByteOrder.LITTLE_ENDIAN)` |

**一句话**：最便宜且**真正由音频驱动**的一组是
「**峰值 → 静音/有声 → RMS → 三带能量**」这四级，
它们全部是**逐样本 O(1)**、可在音频线程上零分配完成、且**不需要任何权限**；
**FFT 是第五级才需要的东西，而第五级（瞬态）用自适应阈值也已经能做得很好**（同文档 §3）。

---

## 未能取证的项

| 项 | 状态 | 原因 / 影响 |
|---|---|---|
| 全部项目的 `license.spdx_id`（GitHub API 字段） | **未核实** | `api.github.com` 全程 `API rate limit exceeded for 203.10.99.36`；许可证结论一律改由 `LICENSE` 原文判定（结论本身仍可靠，只是**不来自** API 字段） |
| `phansier/Gramophone` | **取不到** | `raw.githubusercontent.com` 的 `master`/`main` 上 `LICENSE`、`README.md` 全 404；改用搜索到的 `FoedusProgramme/Gramophone`。两者**是否为同一项目的前后身，未核实** |
| Gramophone 的实现细节（是否有可视化 / 是否有 RMS / processor 行为） | **未核实** | 只拉了 `--filter=blob:none` 的**文件清单**，未读文件内容。表中的「可视化：未发现」严格来说只是「文件清单里没有对应模块」 |
| Salt Player 的可视化实现 | **未核实（且公开渠道不可得）** | 公开仓库自述仅用于发版/反馈/公告，**不含应用源码** |
| 各项目**运行期**真实行为（是否真的没有可视化 UI、画面是否另有装饰） | **未核实** | 本次是**静态源码/清单检索**，未安装 APK、未抓帧、未读反编译产物 |
| Metrolist「`Visualizer` 从 **AOSP 7.1.1** 起要求 RECORD_AUDIO」这一版本断言 | **未核实** | 该断言来自 Ncrust 自身代码注释（`player/VisualizerRenderersFactory.kt:32-33`），本轮未追溯到 AOSP 提交；官方文档只说明该 API 需要 `RECORD_AUDIO` |
| 各项目是否有**闭源/未合并**的可视化分支或插件 | **未核实** | 只检索了默认分支的快照 |
| Vanilla Music 是否使用 media3 | **未核实** | 在 `app/build.gradle` 上检索 `media3\|exoplayer` 无命中，但未核实是否存在其它构建文件 |

---

## 附：检索式（可复现）

对每个仓库解包后的全量源码（`.kt` / `.java` / `.xml`）执行：

```bash
# 1) Android Visualizer API 的使用（7 个仓库合计命中 0 条）
grep -rIn --include=*.kt --include=*.java --include=*.xml \
  -E 'audiofx\.Visualizer|new Visualizer|Visualizer\(|getFft|getWaveForm|setDataCaptureListener' .

# 2) 音频通路（区分「有自定义 PCM 处理」与「只用 media3 默认链」）
grep -rIn --include=*.kt --include=*.java \
  -E 'android\.media\.audiofx\.Visualizer|Visualizer\(|audioSessionId|getWaveForm|getFft|TeeAudioProcessor|AudioProcessor\b|AudioRecord' .

# 3) 其它特征词（确认没有 waveform / spectrum / beat / tempo / onset / bpm 的实现）
grep -rIn --include=*.kt --include=*.java -iE 'waveform|spectrum|spectrogram|\bfft\b|beatdetect|tempo|onset|bpm' .
```

解包与文件清单：

```bash
curl -sL --max-time 60 -o repo.tgz \
  "https://codeload.github.com/<owner>/<repo>/tar.gz/refs/heads/<branch>"
tar xzf repo.tgz -C target --strip-components=1

# Gramophone 只列了文件清单（未读内容）
git clone --depth 1 --filter=blob:none --no-checkout \
  https://github.com/FoedusProgramme/Gramophone.git && git ls-tree -r --name-only HEAD
```

---

## 相关文档

- [`audio-feature-extraction.md`](audio-feature-extraction.md) —— 无 FFT 的音频特征提取（一阶滤波器组 / 质心近似 / 瞬态），
  本文的 (b)(d) 两问与它的 §0 结论 12/13/14 直接衔接。
- [`compose-particle-systems.md`](compose-particle-systems.md)、[`runtime-shader.md`](runtime-shader.md) —— 视觉呈现侧参考资料。
- [`neriplayer.md`](neriplayer.md) —— 同类「其他播放器」取证的另一份。
