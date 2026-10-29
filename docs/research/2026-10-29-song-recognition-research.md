# 听歌识曲（Song Recognition）接入调研

- 日期：2026-10-29
- 调研对象：Arris（`/home/duanjb666/deepseek/Arris-playback-fixes`，`HEAD = 5462b53`，`versionName = 3.7.10-gpl` / `versionCode = 96`，`minSdk = 24` / `targetSdk = 36`）
- 调研方式：只读 Arris 源码 + clone 并逐行读 5 个开源实现 + 抄录 Android / Apple 官方文档原文
- 产物：本文件。**未改动任何 app 源码**。

---

## 结论先行

**四句话说完：**

1. **「内部音频识别」在 API 29+ 可以做，但不是零成本的**：`AudioPlaybackCapture` 需要 `RECORD_AUDIO` + 一次 `MediaProjection` 投屏授权（用户会看到一个"开始投屏"的系统弹窗），才能拿到**别的 app** 正在放的声音；API 24–28 **没有** `AudioPlaybackCapture`，但**有一条合法的降级路径**——`android.media.audiofx.Visualizer` 的全局会话 0（8 bit、约 20 ms 才刷新一帧、必须做重复帧对齐），它不是"没有路径"，是"降质路径"。**同目录的 `2026-10-29-minsdk-decision.md` 已拍板把 `minSdk` 提到 29，所以这条限制在主线版本上会消失**——但代价是放弃 Android 7.0–9.0 设备，且那份 ADR 与本文有两处口径需要对齐（见 §1.5）。
2. **但 Arris 自己正在播的那首歌，根本不需要上面两条**：`VisualizerRenderersFactory.buildAudioSink()` 已经在 AudioSink 里挂了 `TeeAudioProcessor`，把**解码后的原始 PCM**（未跳静音、未变速）旁路出来给波形用（`VisualizerRenderersFactory.kt:55-77`、`TransparentWaveformSink.kt:117`）。**零权限、全码率、带采样率与声道数**。这是 Arris 里唯一无条件可用的"内部音频"形态，也是"当前这首歌播的是不是我以为的那首"这类需求的正确入口。
3. **最小可用方案（第一版）**：手动点击 → 麦克风 10~15 s（直接复用 `BlowRecorder` 已验证的 `AudioRecord` 参数：16 kHz / MONO / PCM16 / `UNPROCESSED` 优先，`BlowRecorder.kt:29-36`）→ Shazam 式指纹直连（SongRec / shazamio 协议，**无凭证**）→ 取回 `title / artist / album / duration` → `SourceRouter.searchSongs()`（`SourceRouter.kt:135`）双源搜索 → 复用 `CrossSourceMatcher.gradeTrack()`（`CrossSourceMatcher.kt:249`）定级 + `TrackAvailability.of()`（`TrackAvailability.kt:151`）排序 → 跳播放。**不新增前台服务类型、不做后台常驻监听。**
4. **ShazamKit for Android 是真的，但第一版不碰它**：Apple 官方有 AAR（2.1.1，`minSdkVersion 21`，Kotlin），可它**不在 Maven Central / Google Maven**（本次实测两处均 0 命中），必须用 Apple 开发者账号登录取 AAR，并用 Media ID + 私钥签 `developerToken`（JWT）交给 `DeveloperTokenProvider`。**把签名私钥放进 GPLv3 的开源 APK = 把私钥公开**，这是产品级 blocker，不是许可问题。

**一句话选择**：第一版用**麦克风 + Shazam 式指纹（自实现，无凭证）**；`AudioPlaybackCapture` 排第二版；ShazamKit / AudD / ACRCloud 作为可插拔 Provider 保留接口位但不默认开启。

---

## 1. 内部音频识别可行性

### 1.1 `AudioPlaybackCapture` 能捕获哪些音源

官方原文（`developer.android.com/media/platform/av-capture`，2026-10-28 更新，本次实抓）：

> The `AudioPlaybackCapture` API was introduced in **Android 10**. This API gives apps the ability to copy the audio being played by **other apps**.
>
> To be able to capture audio, an app must meet these requirements:
> - The app must have the **`RECORD_AUDIO`** permission.
> - The app must bring up the prompt displayed by **`MediaProjectionManager.createScreenCaptureIntent()`**, and the user must approve it.
> - The capturing and playing apps must be in the **same user profile**.

**能捕获的三类 usage**（`addMatchingUsage`）：

| usage | 能否被捕获 |
|---|---|
| `USAGE_MEDIA` | ✅ |
| `USAGE_GAME` | ✅ |
| `USAGE_UNKNOWN` | ✅ |
| `USAGE_VOICE_COMMUNICATION` / 通话 / 提示音等 | ❌ 不在可捕获集合内 |

**被捕获方还必须没关掉自己**（"Constraining capture by other apps"）：

- `targetSdk ≤ 28` 的 app **默认不可捕获**，必须显式 `android:allowAudioPlaybackCapture="true"`；
- `targetSdk ≥ 29` 的 app **默认可捕获**，可用 `android:allowAudioPlaybackCapture="false"` 关掉；
- 生效策略取**最严格**的一侧（manifest + `AudioManager.setAllowedCapturePolicy` + `AudioAttributes.Builder.setAllowedCapturePolicy` 三者取 min）。

**落地含义**：国内主流音乐 app 的 `targetSdk` 早已 ≥ 29，所以默认是**可捕获**的；但**任何一家都可以随时**用一行 manifest 关掉，这是本方案的头号不可控风险（见 §8）。

**Arris 侧的实证参照物**：Audile 的 `DefaultSoundSource` 就是这段文档的完整实现——

```kotlin
// Audile/core/audio/.../soundsource/DefaultSoundSource.kt:60-74
if (mediaProjection == null) {
    audioRecordBuilder.setAudioSource(DEFAULT_AUDIO_SOURCE)      // 麦克风
} else {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        audioRecordBuilder.setAudioPlaybackCaptureConfig(captureConfig)
    } else {
        error("AudioPlaybackCapture API is available on Android 10+")
    }
}
```

注意它的 `else` 分支：**Audile 在 API < 29 上直接把这条路判死**（抛异常），因为确实没有。

**需要 `MediaProjection` 授权吗？** 需要。且授权粒度是**投屏**，不是"录音"——用户看到的是"Arris 将开始截取您的屏幕内容"。这是文案上必须提前处理的心理成本（见 §7 PR2 的对话框文案）。

**Android 14+ 的服务形态**：要长时间持有投影，必须起 `foregroundServiceType="mediaProjection"` 的前台服务，并在**起服务之前**先调 `createScreenCaptureIntent()` 拿授权（FGS 类型文档原文：*"Call the `createScreenCaptureIntent()` method before starting the foreground service… After you have created the foreground service, you can call `MediaProjectionManager.getMediaProjection()`"*）。另外 `targetSdk ≥ 35` 时**不允许**从 `BOOT_COMPLETED` 起 mediaProjection 类型的 FGS——对我们无影响（第一版不做常驻）。

### 1.2 API 24–28：明确回答「有」

**`AudioPlaybackCapture` 没有。** 它是 API 29 引入的类，24–28 上连符号都不存在。

**但另有一条合法路径：`android.media.audiofx.Visualizer` 的全局会话 0。**

证据链（这是本次调研最有价值的一条，因为它推翻了 Arris 仓库里一条已经过时的结论）：

1. Audile 在**没有** `MediaProjection` 令牌时，**自动退回 Visualizer**：

```kotlin
// Audile/core/audio/.../audiorecord/AudioRecordingControllerFactory.kt:168-183
private fun deviceDescriptor(appContext: Context, mediaProjection: MediaProjection?, mediaProjectionId: String?) =
    SourceDescriptor(
        key = SoundSourceKey.DeviceOutput(projectionTokenId = mediaProjectionId ?: "visualizer"),
        create = {
            if (mediaProjection != null) {
                DefaultSoundSource(appContext, mediaProjection)   // API 29+：AudioPlaybackCapture
            } else {
                VisualizerSoundSource(appContext)                 // 任意 API：Visualizer 全局会话
            }
        },
    )
```

2. `VisualizerSoundSource` 的做法是 `Visualizer(0)`（**session 0 = 全局输出混音**，不是某个播放器实例），抓 8 bit 波形再升到 16 bit：

```kotlin
// Audile/core/audio/.../soundsource/VisualizerSoundSource.kt:68-101
val visualizer = Visualizer(0).apply {
    captureSize = Visualizer.getCaptureSizeRange()[1]   // 典型 1024 samples
    scalingMode = Visualizer.SCALING_MODE_NORMALIZED
    measurementMode = Visualizer.MEASUREMENT_MODE_NONE
    enabled = true
}
...
val rawNewBytes = resolveNewWaveformBytes(previousWaveform, rawWaveform, expectedDuration, sampleRate)  // :136 去重复帧
val pcm16 = convert8BitUnsignedTo16BitSigned(rawNewBytes)                                               // :180
```

3. **Arris 仓库里那条"Visualizer 不能用"的结论，前提已经变了**：

```kotlin
// Arris/player/VisualizerRenderersFactory.kt:32-33
//    备选方案 `android.media.audiofx.Visualizer` 从 AOSP 7.1.1 起就要求
//    **RECORD_AUDIO**（不是"Android 10+ 才要"），与项目定位冲突，不采用。
```

这条写在 v1.8.0，当时的定位是**零录音权限**。但现在的 manifest 里 `RECORD_AUDIO` 已经因为"生日吹蜡烛"声明过了：

```xml
<!-- Arris/app/src/main/AndroidManifest.xml:12-14 -->
<!-- Optional, foreground-only birthday puff detection, requested only on user action. -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-feature android:name="android.hardware.microphone" android:required="false" />
```

并且已经有一套**运行时申请 + 录音**的完整先例（`CelebrationOverlay.kt:136-137`、`BirthdayWebOverlay.kt:129-130`）。**所以"与项目定位冲突"这个理由在 v2.x 之后已经不成立了** —— 这是一个需要在实现时明确记下的前提变更，否则会有人拿旧注释把方案否掉。

**Visualizer 路径的真实限制**（这些是它排在麦克风之后、AudioPlaybackCapture 之前的原因）：

| 限制 | 具体值 | 后果 |
|---|---|---|
| 位深 | 8 bit unsigned（`SCALING_MODE_NORMALIZED`） | 等效 SNR ≈ 48 dB，对 Shazam 式峰值指纹**够用**（它只取相对峰值），对 Chromaprint 的 chroma 量化**偏紧** |
| 帧长/刷新率 | `captureSize` 上限（典型 1024 samples，48 kHz 下 ≈ 21 ms），`getWaveForm()` 拉取式 | 会重复读到同一帧，必须像 Audile 那样做差分对齐（`resolveNewWaveformBytes`） |
| 采样率 | 跟随 `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`（典型 48 kHz），**不能指定** | 需要重采样到 16 kHz |
| 可靠性 | 部分 ROM（尤其 Android 12+ 某些厂商）`Visualizer(0)` 恒返回全 0 | **必须**在启动后 300 ms 内做一次"非静音"自检，失败就降级到麦克风 |
| 权限 | `RECORD_AUDIO`（+ 通常还要 `MODIFY_AUDIO_SETTINGS`，Arris 已有：`AndroidManifest.xml:10`） | 与麦克风同权限，用户心智一致 |

### 1.3 可用性矩阵

| API | `AudioPlaybackCapture`（他人音频） | `Visualizer(0)`（他人音频） | `AudioRecord` 麦克风 | Arris 自己的 PCM tee（**本 app 音频**） |
|---|---|---|---|---|
| **24–28** | ❌ 类不存在 | ⚠️ 可用，8 bit / 需差分对齐 / 部分 ROM 失效 | ✅ | ✅ 零权限 |
| **29–32** | ✅ `MediaProjection` + `RECORD_AUDIO` | ⚠️ 同上（与上一条二选一） | ✅ | ✅ 零权限 |
| **33+** | ✅ 同上；`targetSdk ≥ 34` 还需 FGS 类型 `mediaProjection` | ⚠️ 同上，且实测失效机型更多 | ✅ 但 Android 14 起**不能在后台**起麦克风 FGS | ✅ 零权限 |
| **FGS 类型要求** | API 29–33 只需 `mediaProjection`（且 34 起强制声明） | 无（前台 Activity 内即可） | API 34+ 需 `FOREGROUND_SERVICE_MICROPHONE` + 类型 `microphone` | 无（就在播放服务里） |

> 关于"自家音频能不能用 `AudioPlaybackCapture` 抓"：官方措辞是 "audio being played by **other apps**"，而 Arris 已经有质量更好的 tee（全码率、带 `sampleRate`/`channelCount`/`encoding`，见 `VisualizerRenderersFactory.kt:70-77`）。**这个问题不需要回答** —— 自家音频走 tee，不依赖任何 capture API。

### 1.4 `minSdk = 24` 意味着功能怎么降级

`minSdk = 24`（`app/build.gradle.kts:46`）⇒ **约 1/5 的存量设备拿不到 AudioPlaybackCapture**。三条硬约束：

1. **入口必须按能力分层，而不是按版本号硬编码**。建议做成一个纯函数（可单测）：

```kotlin
// 建议落点：recognition/ClipSourceAvailability.kt（纯逻辑，无 Android import）
enum class ClipSource { MIC, PLAYBACK_CAPTURE, VISUALIZER, OWN_TEE }

object ClipSourceAvailability {
    /**
     * @param sdkInt Build.VERSION.SDK_INT
     * @param visualizerProbeOk 启动自检结果（Visualizer(0) 是否真的吐非 0 数据）
     */
    fun available(sdkInt: Int, visualizerProbeOk: Boolean): List<ClipSource> = buildList {
        if (sdkInt >= 29) add(ClipSource.PLAYBACK_CAPTURE)
        else if (visualizerProbeOk) add(ClipSource.VISUALIZER)
        add(ClipSource.MIC)          // 任何 API 都有，作为兜底永远是最后一项
        add(ClipSource.OWN_TEE)      // 仅当"识别当前播放"时才在 UI 上暴露
    }
}
```

2. **麦克风永远是兜底**，不需要任何版本判断 —— 这是唯一一条 24–36 全绿的路径。
3. **不隐藏入口，只隐藏"内录"这一项**。理由与仓库既有纪律一致：`ui/settings/SettingsVisibility.kt` 的 `GatingReason` 区分"整行不挂载"与"挂载但置灰"，而 `SettingsRenderPlan.SettingsRowKind` 的注释明确要求"未知 id 回落 INTERNAL 而不是抛异常"（`SettingsRenderPlan.kt:20-118`）。对一个**用户明确想要**的功能，直接藏掉入口会让用户以为没做；正确做法是**入口常驻**、点进去后按 `ClipSourceAvailability.available()` 渲染可选音源列表，API < 29 时列表里就只有"麦克风"。

### 1.5 与同目录 `2026-10-29-minsdk-decision.md`（minSdk 24 → 29 ADR）的关系

同批次还有一份 ADR（`docs/research/2026-10-29-minsdk-decision.md`）已经记下"**minSdk 24 → 29 已由需求方拍板**"。两份文档必须对齐，否则实现时会撞车。本节给出**唯一的对齐口径**：

| 问题 | 若 minSdk 停在 24 | 若按 ADR 提到 29（**当前已拍板**） |
|---|---|---|
| `AudioPlaybackCapture` | 仅在 29+ 设备可用，24–28 退 Visualizer / 麦克风 | **全设备可用** |
| Visualizer 路径（§1.2） | 是 24–28 的唯一内录路径，**必须实现** | **只作为"双 flavor 的 legacy 分支"存在**；主线不做，省掉 §8.5 第 2、3 条风险 |
| `ClipSourceAvailability` | 必需 | **仍然必需**，但退化成 2 分支（`PLAYBACK_CAPTURE` / `MIC`）；保留它是为了让 ADR 的"双 flavor"选项随时可用 |
| 认领 §2.3 的前台服务 | v1 不建 FGS | **v1 仍然不建**（见 §2.3 末）；只有做内录才建 `mediaProjection` 类型 |
| 验收设备 | 必须有一台 API 24 | API 29 / 31 / 36 三台（与 ADR §5 一致） |

两点必须显式澄清，因为 ADR 的措辞与本文的结论看起来冲突、实际不冲突：

1. ADR §4 预清单写"**识曲前台服务**需要 `foregroundServiceType`（`microphone` / `mediaProjection`）"。本文的结论是：**麦克风路径不需要前台服务**（v1 在前台 Activity/Dialog 内录音即可，见 §2.3 的官方 while-in-use 约束）。需要 FGS 的只有"回后台继续录"和"内录"两种形态，而 **v1 明确不做后台常驻监听**。⇒ 落地时以本文 §2.3 为准，ADR 那一行按"若做内录才需要"理解。
2. ADR §1 把"内部音频识别"与"`minSdk 29`"绑成因果关系。技术上正确，但**前提是"内部音频"指他人的输出**。Arris 对**自己**正在播的音频有零权限的 tee（见"结论先行"第 2 条）—— 这条能力与 minSdk 无关，**在 24 上就成立**。所以"提 minSdk"这笔买卖买到的是"识别别人在放什么"，不是"识别内部音频"这个笼统概念。

> **实现建议**：即使 minSdk 提到 29，`ClipSourceAvailability` 也要保留 `VISUALIZER` 分支的**代码路径**（可以不渲染入口），因为 `Visualizer` 是无 `MediaProjection` 时唯一的"零系统弹窗"内录手段——它不需要投屏授权，只需 `RECORD_AUDIO`。对一个"不想被录屏弹窗吓到"的用户，它可能是更受欢迎的那个选项。

---

## 2. 麦克风路径

### 2.1 采样参数：不要重新发明，抄 `BlowRecorder`

Arris 已经有一份**在真机上跑过**的录音实现，参数就是行业标准：

```kotlin
// Arris/celebration/BlowRecorder.kt:29-42
val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
if (minimum <= 0) return@withContext BlowResult.UNAVAILABLE
...
val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
val source = if (manager.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true")
    MediaRecorder.AudioSource.UNPROCESSED
else MediaRecorder.AudioSource.VOICE_RECOGNITION
record = AudioRecord(source, 16000, AudioFormat.CHANNEL_IN_MONO,
    AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum, 4096))
if (record.state != AudioRecord.STATE_INITIALIZED) return@withContext BlowResult.UNAVAILABLE
manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { record.setPreferredDevice(it) }
```

| 参数 | 取值 | 依据 |
|---|---|---|
| 采样率 | **16 000 Hz** | Shazam 式指纹的输入域就是 16 kHz（`SongRec/src/core/fingerprinting/algorithm.rs:141-143`，`sample_rate_hz: 16000`；Audile 也把 `REQUIRED_SAMPLE_RATE = 16_000` 写死在 `SongRecSignature.kt:9`）。取更高再降采样是纯浪费 |
| 位深 | **PCM 16 bit** | ShazamKit 官方 Android 示例只支持 `ENCODING_PCM_16BIT`（`ShazamKit for Android` 文档 "Record from Microphone" 一节：`toByteAllocation()` 对非 16 bit 直接抛异常）；Audile 的 `SoundSourceConfigProvider.kt:9-14` 也只列 `ENCODING_PCM_16BIT`，并注释「Most AAC encoders seem to support only 16-bit integer PCM input」 |
| 声道 | **MONO** | 同上，指纹算法吃单声道；`ShazamKit` 要求 `CHANNEL_IN_MONO` |
| AudioSource | `UNPROCESSED` → `VOICE_RECOGNITION` 兜底 | `UNPROCESSED` 关掉 AGC/降噪/回声消除，指纹最怕 AGC 把动态压平。`PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED` 探测法在 `BlowRecorder.kt:34` 已经验证过。**ShazamKit 官方示例也用 `UNPROCESSED`** |
| 首选设备 | `TYPE_BUILTIN_MIC` | 避免蓝牙 SCO 把采样率拉到 8 kHz（`BlowRecorder.kt:38`） |
| 缓冲 | `maxOf(minBufferSize, 4096)` | `BlowRecorder.kt:36` |

### 2.2 10~15 秒片段的实现要点

**片段长度不是越长越好。** Audile 三种 Provider 的 `RecordingScheme` 是硬证据——它用**递增重试**而不是一次录满：

```kotlin
// Audile/.../shazam/ShazamRecognitionService.kt:41-48
override val recordingScheme = RecordingScheme(
    steps = listOf(
        RecordingScheme.Step(recordings = listOf(3.seconds, 6.seconds)),
        RecordingScheme.Step(recordings = listOf(3.seconds, 6.seconds, 9.seconds)),
    ),
    fallback = 10.seconds,
    encodeSteps = false,     // Shazam 走指纹，不需要编码
)
// 上限常量：SAMPLE_DURATION_LIMIT = 12.seconds     // :127
```

```kotlin
// Audile/.../audd/AuddRecognitionService.kt:57-64 与 acrcloud/AcrCloudRecognitionService.kt:47-54
steps = listOf(Step(4.seconds, 8.seconds), Step(7.seconds)), fallback = 10.seconds, encodeSteps = true
```

**读出来的设计规则**（直接照抄）：

1. **先短后长**：3 s → 6 s 失败再加到 9 s，最后 10 s 兜底。10 s 内能出结果就不要拖到 15 s。
2. **Shazam 上限 12 s**（Audile 的 `SAMPLE_DURATION_LIMIT`，超了只 warning 不截断）；**ACRCloud 上限更硬**，Audile 注释写明「samples are truncated from the **beginning**, not the end」——所以**宁可短，不要超**。
3. **`encodeSteps` 是语义开关**：Shazam 送**指纹**（不需要容器），AudD/ACRCloud 送**文件**（需要 AAC/WAV 容器）。这直接决定了 §3 的 `RecogInput` 要分两种。
4. **`RecordingScheme` 的构造器自带不变式校验**（`RecordingScheme.kt:25-38`：同一 step 内时长必须严格递增、必须正且有限）——这是一个**值得照搬**的纯逻辑类，JVM 可单测。

**Arris 版建议参数**（比 Audile 保守，因为不做后台、用户就在屏幕前）：

| 项 | 值 |
|---|---|
| 单次录音窗口 | 首轮 6 s；无匹配再 10 s |
| 最长 | 12 s（硬上限，与 Shazam 一致） |
| 读取方式 | `AudioRecord.read(ShortArray, 0, n, READ_BLOCKING)`（**不要** `READ_NON_BLOCKING`；`BlowRecorder` 用 NON_BLOCKING 是因为它要 5 ms 轮询做爆破检测，识别要的是**不丢帧**） |
| 线程 | 录音线程 `Process.setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)`（ShazamKit 官方示例明确要求） |
| 静音判定 | 录完先算 RMS，< -50 dBFS 直接返回 `Failure(NO_SOUND)`，**不发网络**（省额度；Audile 对应 `RemoteRecognitionResult.NoSoundDetected`，`BaseRemoteRecognitionService.kt:41-43`） |
| 落盘 | **默认不落盘**。仅在 `BuildConfig.DEBUG` 或用户显式"保留样本用于反馈"时写 `cacheDir`，并在识别结束立即删除（见 §8 隐私） |

### 2.3 前台服务与 `RECORD_AUDIO`

**第一版：不建前台服务。**

理由（全部有官方原文支撑）：

- `RECORD_AUDIO` 是 **while-in-use** 权限。FGS 类型文档原文：*"The `RECORD_AUDIO` runtime permission is subject to **while-in-use restrictions**. For this reason, **you cannot create a microphone foreground service while your app is in the background** and you cannot launch a microphone foreground service from a `BOOT_COMPLETED` receiver, with a few exceptions."*
  → 也就是说麦克风 FGS **本来就必须从前台起**，它相对"前台 Activity 内录音"唯一多出来的能力是"**回后台后继续录**"。而第一版**明确不做**后台常驻监听，所以这个能力是纯负担。
- 一旦声明 `foregroundServiceType="microphone"`，就必须同时声明 `FOREGROUND_SERVICE_MICROPHONE`（API 34+）。Arris 现在**只有** `mediaPlayback`：

```xml
<!-- Arris/app/src/main/AndroidManifest.xml:7-8 -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```

  并**不能**复用 `PlaybackService`（`AndroidManifest.xml` 里它是 `foregroundServiceType="mediaPlayback"`，加 `microphone` 会在 API 34+ 触发"类型与权限不匹配"的 `SecurityException`）。

**第二版（若做 `AudioPlaybackCapture`）才需要 FGS**，届时新增：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION" />
<service
    android:name=".recognition.CaptureService"
    android:exported="false"
    android:foregroundServiceType="mediaProjection" />
```

**Android 14（API 34）的前台服务类型限制汇总**（本次实抓 `fgs-types-required` 文档）：

| 场景 | 需要 | 额外运行时约束 |
|---|---|---|
| 麦克风录音（前台 Activity 内） | 只要 `RECORD_AUDIO` 运行时授权 | 无 FGS 要求 |
| 麦克风录音（回后台继续） | `FOREGROUND_SERVICE_MICROPHONE` + 类型 `microphone` + `RECORD_AUDIO` | **不能从后台起服务**；不能从 `BOOT_COMPLETED` 起 |
| 内录（`AudioPlaybackCapture`） | `FOREGROUND_SERVICE_MEDIA_PROJECTION` + 类型 `mediaProjection` + `RECORD_AUDIO` | 必须先 `createScreenCaptureIntent()` 拿授权，**再**起服务；`targetSdk ≥ 35` 不能从 `BOOT_COMPLETED` 起 |
| 短任务（< 3 min，无类型权限） | `shortService` | 不能是 sticky、不能起别的 FGS、超时回调 `onTimeout()` |

> **推荐的 v1 形态**：识别在**全屏 Dialog / 底部 Sheet 的 `LaunchedEffect` + `Dispatchers.IO` 协程**里跑，录音随 Dialog 生命周期取消。这套模式 Arris 已有先例（`BirthdayWebOverlay` 的 `BlowRecorder.listen()` 就是这么跑的，`BirthdayWebOverlay.kt:129-134`）。屏幕常亮用现成的 `KeepScreenOnSetting`（`KeepScreenOnSetting.kt:17` 提到它和 `VisualizerSetting` 同一套写法）。
>
> **与 minSdk ADR 的口径差**：`2026-10-29-minsdk-decision.md` §4 预清单把"识曲前台服务"列成了必做项。**v1 不是必做**——麦克风路径的 `RECORD_AUDIO` 受 while-in-use 约束，本来就必须从前台发起，"前台 Activity 内录音"与"麦克风 FGS"的能力差只有"回后台继续录"这一条，而 v1 明确不做。⇒ 只有 v2 做内录（`mediaProjection` 类型）或决定支持"最小化后继续录"（`microphone` 类型）时才加服务。详见 §1.5 的对齐表。

---

## 3. 识别 Provider 抽象

### 3.1 设计目标

从四类 Provider 的真实差异反推接口（差异全部来自源码，不是拍的）：

| Provider | 输入 | 凭证 | 请求形态 | 返回的关键字段 |
|---|---|---|---|---|
| Shazam（SongRec 协议） | **指纹**（二进制 sig） | 无 | `POST amp.shazam.com/discovery/v5/{lang}/{country}/android/-/tag/{uuid}/{uuid}`，body 是 JSON，`signature.uri` = base64 sig | title / subtitle(artist) / images / `sections[].metadata`（album、release date）/ `matches[].offset` |
| ShazamKit（AAR） | **PCM16 mono @48/44.1/32/16 kHz** | Apple `developerToken`（JWT） | 进程内 SDK 调用 | SDK 自定义（`MatchResult`） |
| AudD | **编码文件**（multipart） | `api_token` | `POST https://api.audd.io/` form-data | artist / title / album / release_date / timecode / song_link |
| ACRCloud | **编码文件**（multipart + HMAC-SHA1 签名） | `host` / `access_key` / `access_secret` | `POST https://{host}/v1/identify`，`signature = HMAC-SHA1(timestamp + "\n" + POST + "\n" + path + "\n" + access_key + "\n" + data_type, access_secret)` | `metadata.music[]`（title/artists/album/duration_ms/external_ids.isrc） |
| Chromaprint + AcoustID | **原始 PCM**（`chromaprint_feed`） | AcoustID `client` key | `GET api.acoustid.org/v2/lookup?client=…&fingerprint=…&duration=…` | recording id / title / artists[] |

凭证字段可逐字对照 Audile 的 DTO：

```kotlin
// Audile/core/domain/.../preferences/RecognitionServiceConfig.kt:5-15
data class AuddConfig(val apiToken: String) : RecognitionServiceConfig()
data class AcrCloudConfig(val host: String, val accessKey: String, val accessSecret: String) : RecognitionServiceConfig()
data object ShazamConfig : RecognitionServiceConfig()   // 无凭证
```

ACRCloud 的签名实现在 `AcrCloudRecognitionService.kt:123-135`（`encryptByHMACSHA1`），AudD 的 multipart 上传在 `AuddRecognitionService.kt:67-92`，Shazam 的 URL 拼装在 `ShazamRecognitionService.kt:78-100`。

### 3.2 建议落点与代码

**包**：`app/src/main/java/com/takahashirinta/ncrust/recognition/`（新建，与 `crosssource/`、`search/` 平级；**纯接口 + 纯逻辑**，实现类各自分文件）。

**文件 1：`recognition/RecognitionTypes.kt`**（纯数据，无 Android import，JVM 可单测）

```kotlin
package com.takahashirinta.ncrust.recognition

/** Provider 身份。落盘字符串，**不可改名**（进识别历史 JSON 与诊断日志）。 */
enum class ProviderId(val key: String) {
    SHAZAM_SONGREC("shazam_songrec"),   // 自实现指纹 + amp.shazam.com，无凭证
    SHAZAM_KIT("shazam_kit"),           // Apple AAR，需 developerToken
    AUDD("audd"),                       // 需 apiToken
    ACRCLOUD("acrcloud"),               // 需 host/accessKey/accessSecret
    CHROMAPRINT("chromaprint"),         // 需 AcoustID client key
}

/**
 * Provider 的输入。**两种形态不可互换**：
 *  - [Pcm]：给"要算指纹"的 Provider（Shazam/SongRec、Chromaprint、ShazamKit）
 *  - [Encoded]：给"要上传音频文件"的 Provider（AudD、ACRCloud）
 */
sealed interface RecogInput {
    /** 16 kHz / mono / PCM16。约定：**必须是这两个参数**，Provider 不做重采样。 */
    data class Pcm(
        val samples16kMono: ShortArray,
        val capturedAtMs: Long,
    ) : RecogInput {
        val durationMs: Long get() = samples16kMono.size * 1000L / 16_000L
    }

    /** 自描述容器（WAV / M4A）。[durationMs] 是**声明时长**，与文件实际长度必须一致。 */
    data class Encoded(
        val bytes: ByteArray,
        val mimeType: String,
        val durationMs: Long,
        val capturedAtMs: Long,
    ) : RecogInput
}

/**
 * 一次识别的产出。**字段全部可空**：没有一家 Provider 保证给全。
 * `durationMs` 尤其关键 —— 它是 §5 跨源匹配的第一判据。
 */
data class RecognizedTrack(
    val provider: ProviderId,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    /** 单位为毫秒。Provider 给秒时在 mapper 里换算，**不要在调用方换算**。 */
    val durationMs: Long? = null,
    val isrc: String? = null,
    val releaseYear: Int? = null,
    /** 0..1，只有部分 Provider 给。null = 未知（**不可当成 0**）。 */
    val confidence: Float? = null,
    val artworkUrl: String? = null,
)

/** 失败分类。**这是本设计的核心**：它决定了 UI 说哪句话、要不要重试。 */
enum class FailureKind {
    /** DNS 失败 / `IOException` / 无网。**可重试**。 */
    NO_NETWORK,
    /** 连接超时或读超时。**可重试**。 */
    TIMEOUT,
    /** 401 / 403 / 签名错 / token 过期。**不可重试**，要用户去改配置。 */
    AUTH,
    /** 429 或响应体显式额度用尽。**不可重试**（重试只会更糟）。 */
    QUOTA,
    /** 5xx。**可重试**（有限次）。 */
    SERVER,
    /**
     * 200 但响应形状不认识 —— **协议变更**。
     * 这是 Shazam 式"逆向协议"方案的**专属失败态**，必须与"无匹配"分开：
     * 两者在 UI 上都是"没认出来"，但对开发者的意义完全相反。
     */
    PROTOCOL_CHANGED,
    /** 片段是静音 / 只有噪声。**不发网络**，让用户重录。 */
    NO_SOUND,
    /** 采样率或编码不被该 Provider 接受（配置错误，不可重试）。 */
    BAD_AUDIO,
    UNKNOWN,
}

sealed interface RecognitionOutcome {
    data class Match(val track: RecognizedTrack) : RecognitionOutcome
    /** 服务端明确回答"库里没有"。**不是错误**。 */
    data object NoMatch : RecognitionOutcome
    data class Failure(
        val kind: FailureKind,
        val detail: String = "",
        val httpCode: Int? = null,
        val cause: Throwable? = null,
    ) : RecognitionOutcome
}

/** 该 Provider 的输入偏好。引擎据此决定"要不要编码"。 */
enum class InputKind { PCM, ENCODED }
```

**文件 2：`recognition/RecognitionProvider.kt`**（接口）

```kotlin
package com.takahashirinta.ncrust.recognition

/**
 * 一个可插拔的识别后端。
 *
 * ## 实现约定（四条，全部来自 Audile 的实证）
 *
 * 1. **不抛异常**：所有异常都要被翻译成 [RecognitionOutcome.Failure]。
 *    唯一例外是 `CancellationException` —— 必须原样抛出（Audile 的
 *    `BaseRemoteRecognitionService.recognizeUntilFirstMatch` 用协程 select 收尾，
 *    吞掉取消会让"用户松手"变成"再等 10 秒"）。
 * 2. **不做重采样**：输入已经是 16 kHz mono PCM16（或已编码容器）。重采样只有一处，
 *    在录音层（对应 Audile 的 `AudioResampler`）。
 * 3. **不碰 UI / 不持有 Context**：需要 Context 的东西（缓存目录、AssetManager）走构造参数。
 * 4. **凭证由调用方注入**，Provider 不读 SharedPreferences —— 否则单测要先造 prefs。
 */
interface RecognitionProvider {

    val id: ProviderId

    /** 这个 Provider 吃什么。引擎据此决定走 [RecogInput.Pcm] 还是 [RecogInput.Encoded]。 */
    val acceptedInput: Set<InputKind>

    /** 是否已具备可用凭证。**不发网络**（Audile 的 `ShazamConfig` 直接 `return Success`，见 `ConfigValidatorImpl.kt:33`）。 */
    fun credentialProblem(config: ProviderConfig): CredentialProblem?

    suspend fun recognize(input: RecogInput, config: ProviderConfig): RecognitionOutcome
}

/** 凭证问题。null = 没问题。 */
sealed interface CredentialProblem {
    data object Missing : CredentialProblem
    data class Invalid(val hint: String) : CredentialProblem
    /** 该 Provider 在本构建里被编译掉了（例如 GPL 构建不含 ShazamKit AAR）。 */
    data object NotInThisBuild : CredentialProblem
}

/**
 * 凭证载体。**全部是可空 String** —— 空串与 null 都要当成"没配"，
 * 不要写 `if (token.isNotEmpty())` 这种二元判断散落在各处。
 */
data class ProviderConfig(
    val provider: ProviderId,
    val acoustIdClientKey: String? = null,
    val auddApiToken: String? = null,
    val acrCloudHost: String? = null,
    val acrCloudAccessKey: String? = null,
    val acrCloudAccessSecret: String? = null,
    /** ShazamKit 的 JWT。**不进 APK**，见 §6。 */
    val shazamKitDeveloperToken: String? = null,
)
```

**文件 3：`recognition/RecognitionEngine.kt`**（编排：重试 + 降级 + 静音短路）

```kotlin
package com.takahashirinta.ncrust.recognition

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.random.Random

/**
 * 单次识别的编排。**纯编排，不含任何网络与音频代码** —— 所以它能在 JVM 上被完整单测
 * （注入 fake provider + 假的 delay）。
 *
 * ## 重试策略：只重试"网络性失败"
 *
 * 逐字对照 Audile 的 `BaseRemoteRecognitionService`（`BaseRemoteRecognitionService.kt:78-88`）：
 *
 *     private fun RemoteRecognitionResult.isRetryRequired() = when (this) {
 *         RemoteRecognitionResult.Error.BadConnection -> true
 *         is RemoteRecognitionResult.Error.HttpError -> code in 500..599
 *         else -> false
 *     }
 *
 * 也就是说 **AUTH / QUOTA / NO_MATCH / PROTOCOL_CHANGED 一律不重试**。
 * 指数退避 + 抖动（`delayForRetry`，`BaseRemoteRecognitionService.kt:71-76`）：
 * `min(2^n * 1000ms, 5000ms) + random(0, 1000ms)`，最多 2 次。
 */
class RecognitionEngine(
    private val providers: List<RecognitionProvider>,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: Random = Random.Default,
) {

    /** 按注入顺序执行，第一个 [RecognitionOutcome.Match] 直接返回。 */
    suspend fun recognize(
        input: RecogInput,
        configs: Map<ProviderId, ProviderConfig>,
        order: List<ProviderId> = providers.map { it.id },
    ): RecognitionOutcome {
        var lastFailure: RecognitionOutcome.Failure? =
            RecognitionOutcome.Failure(FailureKind.UNKNOWN, "no provider configured")

        for (id in order) {
            val provider = providers.firstOrNull { it.id == id } ?: continue
            val config = configs[id] ?: ProviderConfig(id)
            when (provider.credentialProblem(config)) {
                null -> Unit
                CredentialProblem.NotInThisBuild -> continue
                else -> {
                    lastFailure = RecognitionOutcome.Failure(FailureKind.AUTH, "credential missing for $id")
                    continue
                }
            }
            if (inputKindOf(input) !in provider.acceptedInput) continue

            var attempt = 0
            while (true) {
                val outcome = try {
                    provider.recognize(input, config)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (t: Throwable) {
                    RecognitionOutcome.Failure(FailureKind.UNKNOWN, t.message.orEmpty(), cause = t)
                }
                if (outcome is RecognitionOutcome.Match) return outcome
                if (outcome is RecognitionOutcome.NoMatch) return outcome
                val failure = outcome as RecognitionOutcome.Failure
                lastFailure = failure
                if (!failure.kind.isRetryable() || attempt >= RETRY_LIMIT) break
                sleep(backoffMillis(attempt, random))
                attempt++
            }
        }
        return lastFailure ?: RecognitionOutcome.Failure(FailureKind.UNKNOWN)
    }

    private fun inputKindOf(input: RecogInput): InputKind = when (input) {
        is RecogInput.Pcm -> InputKind.PCM
        is RecogInput.Encoded -> InputKind.ENCODED
    }

    companion object {
        const val RETRY_LIMIT = 2
        private const val BASE_DELAY_MS = 1000L
        private const val MAX_DELAY_MS = 5000L
        private const val JITTER_MS = 1000L

        fun FailureKind.isRetryable(): Boolean = when (this) {
            FailureKind.NO_NETWORK, FailureKind.TIMEOUT, FailureKind.SERVER -> true
            FailureKind.AUTH, FailureKind.QUOTA, FailureKind.PROTOCOL_CHANGED,
            FailureKind.NO_SOUND, FailureKind.BAD_AUDIO, FailureKind.UNKNOWN -> false
        }

        fun backoffMillis(attempt: Int, random: Random): Long {
            val exp = minOf((1L shl attempt) * BASE_DELAY_MS, MAX_DELAY_MS)
            return exp + random.nextLong(0, JITTER_MS)
        }
    }
}
```

**文件 4：`recognition/AcoustIdProvider.kt`**（自建 Chromaprint 路线的最小骨架，见 §4 为什么它只做"兜底/校验"）

```kotlin
package com.takahashirinta.ncrust.recognition

/**
 * 自建路线：本地算 Chromaprint 指纹 → AcoustID 查询。
 *
 * ⚠️ **它不是"听歌识曲"的主力**，理由见 §4：Chromaprint 需要 ~10 s 以上才能稳定，
 * 而 Shazam 式对 3 s 就能出结果。它的价值是：
 *  ① 无第三方额度限制（AcoustID 免费，只要求署名）；
 *  ② 可作为"识别结果二次校验"——拿候选曲的 fingerprint 反查，确认不是同名不同曲。
 */
class AcoustIdProvider(
    private val http: RecognitionHttp,
    private val fingerprinter: ChromaprintAdapter,
) : RecognitionProvider {

    override val id = ProviderId.CHROMAPRINT
    override val acceptedInput = setOf(InputKind.PCM)

    override fun credentialProblem(config: ProviderConfig): CredentialProblem? =
        if (config.acoustIdClientKey.isNullOrBlank()) CredentialProblem.Missing else null

    override suspend fun recognize(input: RecogInput, config: ProviderConfig): RecognitionOutcome {
        val pcm = input as? RecogInput.Pcm
            ?: return RecognitionOutcome.Failure(FailureKind.BAD_AUDIO, "AcoustID needs PCM")
        if (pcm.durationMs < MIN_DURATION_MS) {
            return RecognitionOutcome.Failure(
                FailureKind.BAD_AUDIO,
                "chromaprint needs >= ${MIN_DURATION_MS}ms, got ${pcm.durationMs}ms",
            )
        }
        val (fingerprint, durationSec) = fingerprinter.fingerprint(pcm.samples16kMono, 16_000)
        val body = http.get(
            "https://api.acoustid.org/v2/lookup" +
                "?client=${config.acoustIdClientKey}" +
                "&meta=recordings+releasegroups+compress" +
                "&duration=$durationSec" +
                "&fingerprint=$fingerprint",
        ) ?: return RecognitionOutcome.Failure(FailureKind.NO_NETWORK)
        return AcoustIdMapper.map(body)
    }

    companion object {
        /** Chromaprint 的经典下限。低于这个长度，chroma 量化噪声会压过真实音高。 */
        const val MIN_DURATION_MS = 10_000L
    }
}
```

**文件 5：`recognition/RecognitionHttp.kt`**（极薄封装，便于单测注入）

```kotlin
package com.takahashirinta.ncrust.recognition

/**
 * 只有两个方法。**故意不暴露 OkHttp 类型** —— 这样 fake 实现是一行 lambdas，
 * 不需要 mock 整个 `OkHttpClient`。
 *
 * 实现侧必须把 `IOException` 转成 null，把 4xx/5xx 转成 [HttpFailure] 并带上 code，
 * 否则 §3.1 的失败分类无从谈起（这正是 Audile 里 `catch (_: IOException)
 * return@withContext Error.BadConnection` 的位置，`ShazamRecognitionService.kt:101-103`）。
 */
interface RecognitionHttp {
    suspend fun postJson(url: String, headers: Map<String, String>, body: String): String?
    suspend fun get(url: String): String?
}

class HttpFailure(val code: Int, val message: String) : Exception("HTTP $code: $message")
```

### 3.3 凭证放置

| Provider | 凭证 | 放哪里 | 谁签发 | 能否随 GPLv3 APK 分发 |
|---|---|---|---|---|
| Shazam(SongRec) | 无 | — | — | ✅（代码 GPLv3 兼容，协议本身无授权书） |
| ShazamKit | `developerToken`（JWT, ES256） | **不能进 APK**。应由用户自建中转，或用户自己粘贴 | Apple Developer Program + Media ID 私钥 | ❌ 私钥公开 = 任何人可冒用你的配额 |
| AudD | `api_token` | 用户自填（`ncrust_settings`） | audd.io 免费注册 | ⚠️ 团队 token 进 APK = 公开；只允许"用户自带" |
| ACRCloud | `host`/`access_key`/`access_secret` | 用户自填 | ACRCloud 控制台 | ⚠️ 同上 |
| Chromaprint+AcoustID | `client` key（非机密） | 可硬编码一个默认值 | acoustid.org 注册 | ✅（AcoustID 的 client key 不是密钥，`acoustid.py:244-253` 里就是明文 query 参数） |

**落盘约定**（与仓库既有纪律一致）：

- 新增 prefs 键一律进 `ncrust_settings`（`ui/settings/SettingsRegistry.kt:27` `PREFS_SETTINGS`），**不要新建 prefs 文件**；
- 每个键都要在 `SettingsRegistry` 里登记一条 `SettingsEntry`（`SettingsRegistry.kt:147-173`），`type = STRING`、`defaultValue = ""`；
- 凭证类条目 `isInternal = true`（`SettingsRenderPlan.SettingsRowKind.INTERNAL` 的注释：账号凭证属于"从来没有过 UI 入口"的那一类），或者给出专门的"识别服务设置"二级页；
- **加键 = 加默认值 + 加迁移判据**（`AGENTS.md` 的"歌词缓存字段迁移策略"第 1、2 条：新字段一律可空 + 默认值，判"老条目"只看字段缺失）。

### 3.4 失败分类 → UI 文案映射（必须一一对应）

| `FailureKind` | 重试 | UI 文案键（新增到 `SettingsStrings`/新 `RecognitionStrings`） | 备注 |
|---|---|---|---|
| `NO_NETWORK` | ✅ 2 次 | `recognitionErrorNetwork` | 与现有 `loadFailed` 分开：那个是"加载失败"，这里是"识别失败" |
| `TIMEOUT` | ✅ 2 次 | `recognitionErrorTimeout` | |
| `AUTH` | ❌ | `recognitionErrorAuth` | 引导去设置页填凭证 |
| `QUOTA` | ❌ | `recognitionErrorQuota` | **必须与 AUTH 分开**：用户改凭证没用，只能等或换 Provider |
| `SERVER` | ✅ 2 次 | `recognitionErrorServer` | |
| `PROTOCOL_CHANGED` | ❌ | `recognitionErrorUnsupported` | 文案要能对开发者说人话（"该识别服务已变更，请更新版本"） |
| `NO_SOUND` | ❌ | `recognitionNoSound` | Audile 对应文案 `result_message_no_sound_detected_microphone`（`core/strings/.../values/strings.xml`） |
| `BAD_AUDIO` | ❌ | `recognitionErrorAudio` | 配置/设备问题 |
| `NoMatch` | ❌ | `recognitionNoMatch` | **不是错误**，不给"重试"按钮，给"再听一次" |
| `Match` | — | （进 §5） | |

---

## 4. 指纹方案对比

### 4.1 Shazam 式短片段指纹（SongRec / Audile 实证）

**算法步骤**（逐行读 `SongRec/src/core/fingerprinting/algorithm.rs`）：

| 步骤 | 代码位置 | 参数 |
|---|---|---|
| ① 输入规范化 | `algorithm.rs:119-121` | `f32` 单声道 **16 kHz**；非 16 kHz 由调用方先重采样（Audile：`AudioResampler().resample(decoded, 16_000)`，`ShazamSignatureGeneratorSongRec.kt:31`） |
| ② 环形缓冲 | `algorithm.rs:33-40` | `[i16; 2048]` 环形，**每 128 个样本推进一次**（`fft_outputs: [[f32; 1025]; 256]`） |
| ③ 加窗 + FFT | `algorithm.rs:162-204` | 2048 点 Hanning 窗（`hanning.rs` 里 2048 个预先算好的乘数），`realfft` → 1025 个实频 bin；功率谱除以 `1<<17` 并 clamp 到 `1e-10` |
| ④ 频域扩散 | `algorithm.rs:206-239` | 每个 bin 取 `max(self, +1, +2)`（**3 bin 滑窗**） |
| ⑤ 时域扩散 | `algorithm.rs:222-237` | 向前看 **1 / 3 / 6 帧**，逐 bin 取 max（这是"峰值不会被邻帧淹没"的关键） |
| ⑥ 峰值识别 | `algorithm.rs:241-330` | 比较 `fft[i-46]` 与 `spread[i-49]`；阈值 `1/64`；邻域偏移 `[-10,-7,-4,-3,1,2,5,8]`；时域邻域 `[-53,-45,165,172,...,249]`（模 256） |
| ⑦ 频率带 | `signature_format.rs:17-22` | 4 个带：`250-520` / `520-1450` / `1450-3500` / `3500-5500` Hz |
| ⑧ 序列化 | `signature_format.rs:24-34` | 48 字节头（magic `0xcafe2580`、CRC32、sample_rate_id 左移 27、`number_samples + sample_rate*0.24`）+ 每峰 8 字节 |

**关键数字**：

- **2048 点窗 / 128 点跳** ⇒ 窗长 **128 ms**、帧率 **125 帧/秒**（16 kHz ÷ 128）；
- **时间分辨率 8 ms**，频率分辨率 **7.8 Hz**（16 kHz / 2048）；
- 峰值识别有 **46 帧（≈368 ms）的固有延迟** —— 也就是"至少要 0.37 s 音频才能吐第一个峰"；
- 因此 **3 秒片段 ≈ 375 帧 ≈ 数百个峰**，对匹配已经足够（Audile 的第一档就是 3 s，`ShazamRecognitionService.kt:43`）。

**为什么它适合"几秒的环境音"**：

1. **只看相对峰**：扩散 + 邻域比较全是**相对**关系，对 AGC、音量、8 bit 量化都不敏感（这是 Visualizer 那条 8 bit 路径还能用的原因）；
2. **不需要绝对音高**：4 个频带只是"分桶"，不是"识别音名"，所以环境噪声只要不淹没峰值就不影响；
3. **有 368 ms 的启动延迟但只需数百帧**：短到 3 s 就有统计意义。

### 4.2 Chromaprint（为什么不适合几秒环境音）

**算法与参数**（读 `chromaprint/src/`）：

| 项 | 值 | 出处 |
|---|---|---|
| 目标采样率 | **11 025 Hz**（`DEFAULT_SAMPLE_RATE`） | `src/fingerprinter_configuration.h:13, 114-116` |
| 帧长/重叠 | `m_frame_size` / `m_frame_overlap`，`item_duration = frame_size - frame_overlap` | `fingerprinter_configuration.h:112-125` |
| 算法本质 | 12 维 **chroma**（音级能量）→ 量化 → 32 bit 子指纹 | `src/chroma.h:17`、`src/classifier.h` |
| 输入 | 原始 PCM，`chromaprint_start(ctx, sample_rate, num_channels)`，示例用 **44100 Hz 立体声** | `src/chromaprint.h:28-48, 218-223` |

**它为什么不适合 3~5 秒环境音**（三条，都是结构性的，不是调参能救的）：

1. **chroma 是"音级"表示，不是"峰值"表示**。它把频谱折叠到 12 个半音类，**丢掉了绝对八度**——这对"识别整首歌"是优点（变调/翻录不影响），但对"3 秒里只有一段鼓 + 人声"是灾难：12 维向量在 3 秒里几乎不变，量化后得到的子指纹序列极短且自相似。
2. **对音质/噪声远比 Shazam 敏感**。chroma 依赖**能量分布**，而 Shazam 依赖**局部峰的存在**。8 bit 量化（SNR ≈ 48 dB）下 chroma 的量化误差会直接改变子指纹位；而 Audile 的 Visualizer 路径恰恰只有 8 bit。**这是"API 24–28 走 Visualizer + Chromaprint"这个组合不可行的直接原因。**
3. **需要更长时间才能有区分度**。Chromaprint 的设计输入是整首歌（`fpcalc` 默认按整文件算），AcoustID 的查询也按 `duration`（整曲秒数）匹配——`pyacoustid/acoustid.py:244-253` 的 `lookup(apikey, fingerprint, duration, meta)` 里 `duration` 是**整曲时长**。给 4 s 的 `duration` 去查，服务端侧的候选集与打分都不成立。

**结论**：

| | Shazam 式 | Chromaprint |
|---|---|---|
| 最小可用片段 | **3 s**（`426` 帧启动延迟） | ~10 s 起才有意义，20 s+ 才稳 |
| 对 8 bit / AGC | 不敏感（相对峰） | 敏感（能量分布） |
| 变调/翻录鲁棒性 | 中（频带会移） | 高（chroma 免疫） |
| 能否本地算 | 可以（`algorithm.rs` 386 行纯 Rust，无外部依赖） | 可以（但 = 一个 LGPL C++ 库 + JNI） |
| 是否要服务端 | 要（Shazam） | 要（AcoustID） |
| **本项目的定位** | **主力** | **不做主力**：仅用作 §5 的"二次校验"或离线兜底 |

### 4.3 Arris 里已有的音频特征代码能复用什么

**能复用的**：`AudioFeatureExtractor`（`player/AudioFeatureExtractor.kt:93`）**不能**用于指纹——它的 KDoc 明确写着：

> 无 FFT 的音频特征提取器（**单线程使用**）

```kotlin
// Arris/player/TransparentWaveformSink.kt:67
 * （决定性依据见 [AudioFeatureExtractor] 的档头：没有分帧、没有窗函数、没有频域变换、
```

⇒ 它是**时域** RMS/低频/瞬态统计，**没有 FFT**。指纹必须有 FFT。所以 §4.1 的 ②③④⑤⑥ 要**新写**（约 300 行 Kotlin，纯算术、零分配、可 JVM 单测）。

**能复用的是它的工程纪律**，不是算法：零分配（`AudioFeatureExtractor.kt:57-67` 的 "零分配 / 零 GC" 一节）、异常隔离边界（`TransparentWaveformSink.handleBuffer`）、以及 `configure(sampleRate, channels, encoding)` 的 `flush` 契约。新写的 `ShazamFingerprinter` 应该照同一形状写：

```kotlin
// 建议落点：recognition/fingerprint/ShazamFingerprinter.kt（纯 Kotlin，无 Android import）
class ShazamFingerprinter {
    /** 一次性分配全部状态。之后 [feed] 与 [finish] 零分配。 */
    fun reset()
    /** @return 本次消耗的样本数；内部按 128 样本对齐，余数留到下次 */
    fun feed(samples: ShortArray, offset: Int, count: Int): Int
    /** @return base64 的 signature uri（与 SongRec 的 `DecodedSignature::encode` 等价） */
    fun finish(): String
}
```

`finish()` 的编码必须逐字节对齐 `signature_format.rs`（48 字节头 + 8 字节/峰 + CRC32 + base64）—— 否则 `amp.shazam.com` 会返回 200 + 空 `matches`，而且**不会告诉你为什么**。这一点在 §7 的 PR1 里要有专门的对照测试（生成 sig → 用 `shazamio` 的 Python 实现解码 → 逐字段比对）。

### 4.4 ShazamKit 的输入约束（若第二版启用）

Apple 官方 Android 文档原文（本次实抓 `developer.apple.com/shazamkit/android/`）：

> Developers can provide the SDK with audio obtained from any source as long as the audio format is **PCM 16bit MONO** in one of the following sample rates: **48000Hz, 44100Hz, 32000Hz, 16000Hz**.

⇒ 与 §2.1 的 16 kHz / MONO / PCM16 **完全兼容**，不需要为它单独设计录音参数。这是它相对 SongRec 路线的唯一优势（不用自己实现指纹）；劣势见 §6。

---

## 5. 识别结果 → 跨源匹配

### 5.1 输入与输出

**输入**：`RecognizedTrack(title, artists[], album?, durationMs?, isrc?)`
**输出**：`List<Candidate>`，每项 = `(SongItem, MusicSource, TrackAvailability, MatchVerdict)`，按"能播 + 置信度高"排序，第一项直接可播。

### 5.2 管线（5 步，每一步都有现成落点）

```
RecognizedTrack
   │
   ├─(1) 构造查询串 + 双源搜索
   │      SourceRouter.searchSongs(NETEASE, kw, 30)   ← SourceRouter.kt:135
   │      SourceRouter.searchSongs(QQMUSIC, kw, 30)
   │
   ├─(2) SongItem → CrossSourceMatcher.TrackCandidate（映射，纯逻辑）
   │      key = TrackKey(source, id, sourceId=mid, mediaId=media_id)   ← TrackKey.kt:59
   │      name / artists / durationMs=dt / albumName
   │
   ├─(3) gradeTrack(anchor, candidate) 定级                ← CrossSourceMatcher.kt:249
   │      规则：曲名归一化相等 → 艺人交集 → 版本种类集合相等 → 时长差 ≤ 2s
   │
   ├─(4) TrackAvailability.of(song) 标注可播性             ← TrackAvailability.kt:151
   │      ncm: privilege.st/pl + noCopyrightRcmd + fee
   │      qm : pay.pay_play + 账号是否会员
   │
   └─(5) 过滤 + 排序 + 取首项
          rankByAvailability(...)                          ← CrossSourceMatcher.kt:355
          pickPlayable(...)                                ← CrossSourceMatcher.kt:384
```

### 5.3 查询串怎么拼

**只用 title + 第一艺人**，不要拼 album、不要拼"歌词"：

```kotlin
// 建议落点：recognition/RecognitionQuery.kt（纯逻辑）
object RecognitionQuery {
    /**
     * 用**主艺人的第一个**，不用全部艺人：
     * 识别服务给的 artists 往往是 "A, B, C"，全拼进查询串会把结果集缩到 0。
     * 也不带 album —— ncm/QQ 的搜索对 album 字段的加权很低，带上只会引入噪声。
     */
    fun build(track: RecognizedTrack): String {
        val artist = track.artists.firstOrNull()?.trim().orEmpty()
        return listOf(track.title.trim(), artist).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
```

依据：`CrossSourceMatcher` 的类文档第 2 条——**"名字只用来召回，绝不用来判定"**（`CrossSourceMatcher.kt:23`）。搜索串的职责只是"别漏"，所以越宽越好。

### 5.4 评分与阈值策略

**不要新造评分函数**。`gradeTrack` 已经是仓库里被探针校准过的唯一落点（`CrossSourceMatcher.kt:249-296`），它的规则与本次需求的交集是 100%：

| `gradeTrack` 分支 | 条件 | 置信度 | 识别场景下的处置 |
|---|---|---|---|
| 曲名不等 | `!NameNormalizer.sameName` | `NONE` | **丢弃** |
| 艺人不同 + 时长一致 | `durationOk` | `LOW` | **丢弃**（翻唱/同名不同曲） |
| 艺人不同 + 时长不一致 | — | `LOW` | 丢弃 |
| 艺人一致 + 时长 ≤ 2 s + 版本种类一致 | — | **`EXACT`** | ✅ **直接播** |
| 艺人一致 + 时长一致但版本不同 | 伴奏/Live/Remix… | `LOW` | 丢弃（见 §5.5） |
| 艺人一致 + 一侧无时长 | `delta == null` | `MEDIUM` | ⚠️ **只有这种情况需要额外判据**（下面补） |
| 艺人一致 + 时长差 > 2 s | — | `LOW` | 丢弃（">30s 的 15 条全是同名不同版本"，`CrossSourceMatcher.kt:290-292`） |

**阈值策略**：

- **`EXACT` → 自动选第一条可播的**（`pickPlayable`）。
- **`MEDIUM` → 要求用户确认**。因为 `MEDIUM` 的语义就是"缺时长、校验不足"，直接播等于赌。
- **`LOW` / `NONE` → 不展示为"识别结果"**，但**可以**折叠进"其他候选"区域（用户点开才看）。理由与仓库既有铁律一致："给错单曲比不合并严重得多"（`CrossSourceMatcher.kt:266-270`）。

**`MEDIUM` 时的补充判据**（按可靠性降序，实现时按顺序短路）：

1. **ISRC 直比**：`RecognizedTrack.isrc` 若存在，直接与 `SongItem` 里的 isrc 比（ACRCloud / AudD 都给 isrc）。**这是唯一的强判据**，命中直接升到 `EXACT`。
2. **专辑名归一化相等**：`NameNormalizer.sameName(track.album, song.album?.name)`。
3. **时长差 ≤ 5 s 的兜底**：`delta <= 5_000` 才算"大概同一版"，仍只给 `MEDIUM`。

**为什么不用"标题相似度 < 1 也算"**：`NameNormalizer.normalizeName` 已经把括注、版本词、标点全部剥掉（`NameNormalizer.kt:96-108`），`sameName` 是**严格相等**。引入编辑距离会让"《晴天》vs《晴天 (钢琴版)》"这类在归一化后已经分开的东西重新混起来 —— 而仓库的 `editionKinds` 就是为了防这个（`NameNormalizer.kt:124-147`）。

### 5.5 「翻唱 / Live / 伴奏」排除规则

三层，全部是**现成函数**：

**第 1 层：版本种类集合必须相等**（`CrossSourceMatcher.kt:257-261`）

```kotlin
val anchorEdition = NameNormalizer.editionKinds(anchor.name)
val candidateEdition = NameNormalizer.editionKinds(candidate.name)
val sameEdition = anchorEdition == candidateEdition
```

`editionKinds` 把标记归到 11 个种类（`NameNormalizer.kt:150-165`）：`instrumental` / `live` / `remix` / `acoustic` / `cover` / `demo` / `vocal` / `edit` / `deluxe` / `remaster`。空集合 = 原版。

⇒ **识别服务给的是"晴天"，候选是"晴天 (Live)"：`{} != {live}` ⇒ 不得合并**（`gradeTrack` 会给 `LOW`）。

**第 2 层：艺人不一致直接拒**（`CrossSourceMatcher.kt:263-271`）

翻唱最典型的形态就是"同名 + 不同艺人"。`gradeTrack` 在这种情况下**最高只给 `LOW`**，注释写得很直接：

> 因为「给错单曲」比「不合并」严重得多（铁律 17）

**第 3 层：`TrackVersionTag`（原唱 / 翻唱，只对 ncm 侧生效）**

仓库里已经有专门的枚举，**不要**直接去读裸字段：

```kotlin
// Arris/search/TrackAvailability.kt:209-218
enum class TrackVersionTag {
    /** 服务端声明这条就是原曲本身（`originCoverType == 1`）。 */
    ORIGINAL,
    /** 服务端声明这条是翻唱（`originCoverType == 2`）。 */
    COVER,
    /** 服务端没给判据、或判据语义未确证（`0` / `3` / 字段缺失）。UI **不显示任何标签**。 */
    UNKNOWN,
    ;
```

按音源分派的唯一入口在 `TrackAvailability.kt:231-238`：

```kotlin
fun of(song: SongItem): TrackVersionTag = when (song.musicSource) {
    MusicSource.QQMUSIC -> UNKNOWN                            // QQ 没有这个字段
    MusicSource.NETEASE -> ofOriginCoverType(song.originCoverType)
    MusicSource.BILIBILI -> UNKNOWN                            // 翻唱信息写在标题里，不是结构化标注
}
```

底层字段是 `SongItem.originCoverType`（`SearchResponse.kt:133`，`1` = 原唱 / `2` = 翻唱 / `0`、`3` = 不知道），
原曲引用是 `SongItem.originSong`（`SearchResponse.kt:140`，类型 `OriginSongRef` 在 `SearchResponse.kt:190`）。
**这是接口字段，不是启发式**（自洽性 58/60 = 96.7%，见 `TrackAvailability.kt:187-207` 的注释块）。用途：

- 若候选里**同时**有一条 `COVER` 和一条 `ORIGINAL` → **优先 `ORIGINAL`**；
- 若候选**只有** `COVER`（即只有翻唱可播）→ **允许播放，但 UI 必须标注"这是翻唱"**，副标题用 `originSong.artists` + `originSong.name`；
- `UNKNOWN` → **什么都不显示**（`hasBadge == false`，`TrackAvailability.kt:220`），不许猜。

**这一条是本方案对"排除规则"的最强补充**：它把"翻唱"从**字符串猜测**变成了**服务端声明**。而 QQ 侧没有等价字段（`of()` 恒返回 `UNKNOWN`），所以只能靠第 1、2 层。

### 5.6 可单测的纯逻辑落点

| 落点（新建） | 职责 | 测试要点 |
|---|---|---|
| `recognition/RecognitionQuery.kt` | `build(track): String` | 空 artist / 多 artist / 全空白 |
| `recognition/RecognizedTrackMapper.kt` | 三家 Provider 的 JSON → `RecognizedTrack` | **一家一个真响应样本**（fixture），断言 durationMs 单位是毫秒（AudD 给 `timecode`、ACRCloud 给 `duration_ms`、Shazam 给 `samplems`） |
| `recognition/fingerprint/ShazamFingerprinter.kt` | PCM → base64 sig | ① 全零输入不崩；② 与 SongRec 的参考 sig 逐字节相等（黄金文件）；③ `feed` 分片边界（120/128/129 样本） |
| `recognition/RecognitionEngine.kt` | 重试/降级编排 | ① `QUOTA` 不重试（调用计数 = 1）；② `SERVER` 重试 2 次；③ `CancellationException` 透传；④ 无凭证的 provider 被跳过 |
| `recognition/CandidateRanker.kt` | 候选过滤 + 排序（把 §5.4/5.5 收成纯函数） | ① Live/伴奏被排除；② `EXACT` 优先于 `MEDIUM`；③ `PLAYABLE` 优先于 `MEMBER_ONLY`；④ 空候选返回空而不是首项 |

`CandidateRanker` 的形状建议（**唯一的"新算法"，必须单测**）：

```kotlin
// recognition/CandidateRanker.kt —— 纯逻辑，无 Android import
object CandidateRanker {

    data class Candidate(
        val song: SongItem,
        val verdict: MatchVerdict,
        val availability: TrackAvailability,
    )

    /** 低于此档不进"识别结果"，只进"其他候选"。 */
    private val ACTIONABLE = setOf(MatchConfidence.EXACT, MatchConfidence.MEDIUM)

    fun rank(candidates: List<Candidate>): List<Candidate> =
        candidates
            .filter { it.verdict.confidence in ACTIONABLE }
            .sortedWith(
                compareBy(
                    { confidenceRank(it.verdict.confidence) },              // 先按置信度
                    { CrossSourceMatcher.availabilityOrder(it.availability) }, // 再按能不能播
                ),
            )

    /** 是否可以直接起播（无需二次确认）。 */
    fun autoPlay(candidates: List<Candidate>): Candidate? =
        rank(candidates).firstOrNull {
            it.verdict.confidence == MatchConfidence.EXACT &&
                it.availability == TrackAvailability.PLAYABLE
        }

    private fun confidenceRank(c: MatchConfidence): Int = when (c) {
        MatchConfidence.EXACT -> 0
        MatchConfidence.HIGH -> 1
        MatchConfidence.MEDIUM -> 2
        MatchConfidence.LOW -> 3
        MatchConfidence.NONE -> 4
    }
}
```

**注意排序的稳定性**：用 `sortedWith(compareBy(...))`（稳定），不用 `sortedByDescending` —— 理由与 `ArtistReco.rankAnchors` / `rankByAvailability` 完全一致：**同一份服务端数据每次必须给出同一个顺序**（`CrossSourceMatcher.kt:353-362`）。

### 5.7 从"选中的候选"到"真的能播"：别忘了 B 站

`TrackAvailability.of()` 已经覆盖三源（`TrackAvailability.kt:151-167`），但**注意 B 站恒为 `UNKNOWN`**（注释：B 站音频区响应里没有任何版权字段）。所以：

- 如果 `bilibili_enabled` 开着（`Grep` 到该 prefs 键存在，见 `SettingsStrings` 的 `bilibiliEnabledLabel`），识别结果**也会**搜到 B 站条目；
- 但 `UNKNOWN` 在 `availabilityOrder` 里排在 `PLAYABLE` 之后（`CrossSourceMatcher.kt:342-348`），所以 B 站条目天然不会抢默认位；
- **最终是否可播仍要过一次 `SourceRouter.resolveUrlOutcome()`**（`SourceRouter.kt:90`），因为"标注"与"取链"是两件事 —— 仓库已经为此写过纪律：`isConfirmedPlayable` 的注释里记着"回显虚高"的真机事故（`CrossSourceMatcher.kt:373-382`）。

---

## 6. 许可与合规

### 6.1 逐个列许可证

| 项目 | 许可证（实测原文） | 出处 | 能否搬进 GPLv3 的 Arris |
|---|---|---|---|
| **Arris 本体** | GPLv3（fork 部分）+ MIT（上游原始代码） | 每个文件头 `LICENSE` / `LICENSE-MIT`；`README` | — |
| **Audile** | **GPLv3**（`LICENSE.md` 第 1 行 "GNU GENERAL PUBLIC LICENSE Version 3"） | `repos/Audile/LICENSE.md:1` | ✅ 可以搬代码（同许可），但**必须**在文件头保留原作者版权 + 注明来源。它的架构（`RecordingScheme` / `RemoteRecognitionResult` / `BaseRemoteRecognitionService`）**非常值得搬** |
| **SongRec** | **GPL-3.0+**（`Cargo.toml:2` `license = "GPL-3.0+"`） | `repos/SongRec/Cargo.toml:2` | ✅ 可以搬（`algorithm.rs` 386 行是干净的自包含实现）。搬了要在 `THIRD-PARTY-LICENSES.md` 里加一节 |
| **shazamio** | **MIT**（`LICENSE.txt:1`） | `repos/shazamio/LICENSE.txt` | ✅ 可搬 |
| **shazamio-core** | **MIT**（`Cargo.toml` `license = "MIT"`） | `repos/shazamio-core/Cargo.toml:19` | ✅ 可搬 |
| **Chromaprint** | 整体 **LGPL-2.1**：自有代码 MIT，内嵌 FFmpeg 部分 LGPL-2.1 | `repos/chromaprint/LICENSE.md:1-9`（原文："As a whole, Chromaprint should be therefore considered to be licensed under the LGPL 2.1 license."） | ⚠️ **LGPL-2.1 → GPLv3 是单向兼容的**（LGPL-2.1 §3 允许升到 GPLv3），可以链接。但**动态链接更省事**（JNI `.so` 单独分发），且必须提供"替换该库"的能力。**第一版不引入** |
| **pyacoustid** | **MIT**（`LICENSE` 第 1 行） | `repos/pyacoustid/LICENSE` | ✅ 只作参考（Python，不进 APK） |
| **ShazamKit for Android（AAR）** | **Apple 自有许可**，非 OSI | Apple 文档站 + AAR 内嵌 LICENSE；**不在 Maven** | ❓ **见 §6.2** |
| AcoustID 服务 | 数据 CC0 / 服务条款要求署名 | `acoustid.org` | ✅（要求署名，加进"关于"页） |
| AudD / ACRCloud 服务 | 商业 ToS | 各自官网 | ⚠️ 与代码许可无关，但与"分发含团队 token 的 APK"有关 |

### 6.2 ShazamKit SDK 能否随 GPLv3 APK 分发

**本次实测到的硬事实（全部可复现）：**

1. **SDK 真实存在**：`https://developer.apple.com/shazamkit/android/` 是 Dokka 生成的官方文档站，版本 **2.1.1**，平台标记 `androidJvm`，包名 `com.shazam.shazamkit`。Apple 主站原文：*"A ShazamKit SDK is also available for Android."*
2. **形态是 AAR**：文档原文 *"ShazamKit comes in the form of an **Android Archive (AAR) file**. Once downloaded, place the file in the `libs` directory…"*，并在 `dependencies` 里显式要 `implementation(name: "shazamkit-android-release", ext: "aar")`。
3. **`minSdkVersion 21`**，Kotlin 编写（Changelog："2.0.2 Lowers the minSdkVersion to 21"、"2.1.0 Adds support for 16 KB page sizes"）。
4. **必须有 Apple 开发者凭证**：*"in order to use the ShazamCatalog you need to have an **Apple Developer token**, which you need to provide using your own `DeveloperTokenProvider`."*；主站补充：*"To communicate with the Shazam service, visit Certificates, Identifiers & Profiles to create a **media identifier and private key to sign your developer tokens**."*
5. **不在任何公共仓库**：`search.maven.org` 查 `shazamkit` → `numFound: 0`；`dl.google.com/dl/android/maven2/master-index.xml` grep `shazam` → 0 行；下载页 `developer.apple.com/download/all/?q=Android+ShazamKit` 重定向到 **"Sign In - Apple"**。
6. 传递依赖明确：`kotlinx-coroutines-core/android 1.8.0`、`okhttp 4.12.0`、`retrofit 2.11.0`、`converter-gson 2.11.0` —— 与 Arris 现有依赖（`app/build.gradle.kts:631-634` 的 retrofit 2.11.0 / gson 2.10.1）基本兼容。

**结论（分三层，不要混着说）：**

| 问题 | 答案 |
|---|---|
| 技术可行性 | ✅ 可行。AAR 直接丢 `libs/`，minSdk 21 < Arris 的 24，16 kHz PCM16 mono 与 §2 参数一致 |
| **能否随 GPLv3 APK 分发** | ❓ **需律师确认**。理由不是"Apple 许可禁止 GPL"，而是：**GPLv3 要求"完整对应源码"（§6）与"可以修改并再分发"（§2）**，而 ShazamKit 是**闭源二进制 + 禁止逆向/再分发条款**的 Apple SDK。GPLv3 §6 对"系统库"有例外，但 Apple SDK 是否构成 GPLv3 定义的 "System Library"（要求"随操作系统一起分发"或"与编译器一起"）**高度可争**。这个判断不能由工程侧下 |
| **实际 blocker（比许可更早发生）** | ❌ **`developerToken` 是 ES256 签名的 JWT，必须由 Media ID 的私钥签发**。放进开源 APK = 私钥/令牌公开 = 任何人可消耗你的 Shazam 配额，且 Apple 的 ToS 视角下这是**凭证泄露**。唯一干净的做法是**自建一个只做 token 签发的极小中转**，但那违背 Arris "客户端直连"的既有架构（`AGENTS.md`：app 直接打 `music.163.com`，`ncrust-api` 是"orphaned, not wired into the app"）。 |

**替代方案（按推荐度）**：

1. **自实现 Shazam 指纹 + 直连 `amp.shazam.com`**（SongRec 路线）。许可证上完全干净：SongRec 是 **GPL-3.0+**，与 Arris 同许可，搬代码只需保留版权声明；**没有凭证**（请求体里只有 sig + 随机 geolocation，`ShazamRecognitionService.kt:60-72`），所以**没有密钥泄露问题**。风险只在"协议随时可能变"（§8）。
2. **AudD / ACRCloud，用户自带 token**。凭证在用户手里，APK 里只有一个空字段。代价：用户要自己去注册，转化率低。
3. **Chromaprint + AcoustID**。许可最干净（AcoustID 免费、只需署名），但 §4.2 已证明它不适合几秒片段。
4. **ShazamKit**：**第一版不启用**。接口留位（`ProviderId.SHAZAM_KIT` + `CredentialProblem.NotInThisBuild`），实现类可以**写但不注册**，由 build flag 控制。

### 6.3 SongRec / shazamio 的协议逆向代码能不能搬

**能搬，但要分清"代码"与"协议"两件事：**

| 对象 | 版权 | 结论 |
|---|---|---|
| `algorithm.rs` 的指纹算法 | SongRec 的**源代码**，GPL-3.0+ | ✅ 可搬。这是**算法实现**，不是协议 |
| `hanning.rs` 的窗表 | 同上 | ✅ 可搬（纯常量） |
| `signature_format.rs` 的 48 字节头布局 | 同上 | ✅ 可搬（这是**事实性结构**，且已公开十余年） |
| `amp.shazam.com/discovery/v5/...` 端点与参数 | **不是版权对象**（URL 与参数名不受版权保护） | ✅ 可用。但见下 |
| shazamio 的 `misc.py` 端点常量 | MIT | ✅ 可搬 |
| Audile 的 `ShazamRecognitionService.kt` | GPLv3 | ✅ 可搬（同许可） |

**需要说清楚的边界**：

- **版权层面**：以上全部干净。SongRec 与 Audile 都是 GPLv3，Arris 也是 GPLv3；shazamio 是 MIT（MIT → GPLv3 兼容）。
- **服务条款层面（不是版权，但真实存在）**：直连 `amp.shazam.com` 是**未公开 API**。Apple/Shazam 的 ToS 大概率不允许第三方客户端调用（本次**未逐条核实**其 ToS 原文，列入"未验证事项"）。这一条不影响"能不能把代码放进 GPLv3 仓库"，但影响"能不能上架 Google Play"（§8）。
- **工程层面**：`amp.shazam.com` 会校验 `User-Agent` 与 `Content-Language`，Audile 为此内置了 10 条 Dalvik UA 轮换（`ShazamRecognitionService.kt:135-146`）。这不是"绕过安全机制"，是**服务端按 UA 分流**的常规现实；但它是**易碎点**：UA 一旦全被判掉，识别就整体失效，且失败态是 `PROTOCOL_CHANGED` 而不是 `AUTH`。

---

## 7. 落地计划

**第一版范围（三句话）**：手动点击 → 麦克风 → 单次识别（Shazam 式指纹，无凭证）→ 结果卡片 → 点击跳播放。**不做**后台常驻监听、不做内录、不做识别历史持久化（只留内存最近一条）、不引入 ShazamKit / AudD / ACRCloud / Chromaprint。

### PR 1：纯逻辑 + Provider 骨架（**零 UI、零权限、零网络**）

| 文件 | 动作 | 说明 |
|---|---|---|
| `recognition/RecognitionTypes.kt` | 新增 | §3.2 的类型（`ProviderId` / `RecogInput` / `RecognizedTrack` / `FailureKind` / `RecognitionOutcome` / `InputKind`） |
| `recognition/RecognitionProvider.kt` | 新增 | 接口 + `ProviderConfig` + `CredentialProblem` |
| `recognition/RecognitionEngine.kt` | 新增 | §3.2 的编排（重试表 + 降级） |
| `recognition/RecognitionQuery.kt` | 新增 | §5.3 |
| `recognition/CandidateRanker.kt` | 新增 | §5.6 |
| `recognition/fingerprint/ShazamFingerprinter.kt` | 新增 | §4.1 的 ②~⑧，纯 Kotlin、零分配 |
| `recognition/fingerprint/ShazamSignatureFormat.kt` | 新增 | 48 字节头 + 峰编码 + CRC32 + base64（逐字节对齐 `signature_format.rs`） |
| `app/src/test/.../recognition/*Test.kt` | 新增 | 见下表 |

**可单测部分（本 PR 的全部价值都在这里）**：

| 测试 | 断言 |
|---|---|
| `RecognitionEngineTest.quotaIsNotRetried` | `QUOTA` 时 provider 调用次数 == 1 |
| `RecognitionEngineTest.serverErrorRetriedTwice` | `SERVER` 时调用 3 次（1 + 2 重试） |
| `RecognitionEngineTest.cancellationPropagates` | 抛 `CancellationException` → 原样抛出，不被翻译成 `Failure` |
| `RecognitionEngineTest.skipsProviderWithoutCredential` | `AUTH` 且不是最后一个 provider → 换下一个 |
| `RecognitionEngineTest.backoffIsBounded` | `backoffMillis(10, seed)` ≤ 5000 + 1000 |
| `CandidateRankerTest.liveVersionExcluded` | 锚点"晴天" + 候选"晴天 (Live)" → 输出空 |
| `CandidateRankerTest.exactBeatsMedium` | 混合输入 → `EXACT` 在前 |
| `CandidateRankerTest.unplayableNotAutoPlayed` | `EXACT` + `MEMBER_ONLY` → `autoPlay() == null` |
| `ShazamFingerprinterTest.matchesSongRecGolden` | **黄金文件**：一段 6 s 定长 PCM → sig base64 与 SongRec 输出逐字节相等 |
| `ShazamFingerprinterTest.chunkBoundaryAgnostic` | 同一段 PCM 按 1/128/129/4096 分片送入 → 结果相同 |
| `ShazamFingerprinterTest.silenceYieldsNoPeaks` | 全零 → 峰数 0，不抛异常 |

**验收**：`./gradlew test --tests '*recognition*'` 全绿；`git diff --stat` 只含 `recognition/` 与 `src/test/`；**`app/src/main/AndroidManifest.xml` 一个字节未改**。

### PR 2：麦克风 + 识别 + 结果卡片（**第一个可见功能**）

| 文件 | 动作 | 说明 |
|---|---|---|
| `recognition/audio/MicClipRecorder.kt` | 新增 | `AudioRecord` 封装，参数抄 `BlowRecorder.kt:29-42`；输出 `ShortArray`；支持 `cancel()`；静音检测 |
| `recognition/ShazamSongRecProvider.kt` | 新增 | 指纹 → `POST amp.shazam.com`；错误映射见 §3.4 |
| `recognition/RecognitionHttpImpl.kt` | 新增 | 复用 `RetrofitClient` 的 OkHttp 实例（**不要新建 client**；`RetrofitClient` 已有 cookie 拦截器，识别请求要**不带 cookie**，所以要用独立实例，见下） |
| `recognition/RecognitionSettings.kt` | 新增 | prefs 读写（`ncrust_settings`），键：`recognition_provider`（默认 `shazam_songrec`）、`recognition_history_json`（可选，≤20 条） |
| `ui/recognition/RecognitionSheet.kt` | 新增 | 全屏 Dialog：状态机 `Idle → Requesting → Recording(progress) → Matching → Result | Error`；`LaunchedEffect` 里跑协程 |
| `ui/recognition/RecognitionResultCard.kt` | 新增 | 候选列表（主候选 + 折叠"其他候选"），复用 `SongCard` |
| `ui/i18n/RecognitionStrings.kt` + 8 个 locale 文件 | 新增 | 见 §3.4 的文案键；`Strings.kt` 主构造器加 `val recognition: RecognitionStrings` |
| `MainActivity.kt` / `UserScreen.kt` | 改（各 1 处入口） | 入口按钮；权限申请走 `rememberLauncherForActivityResult(RequestPermission())`（照 `BirthdayWebOverlay.kt:129-130`） |

**关键实现细节（都是踩过坑的点）**：

1. **识别请求必须用独立的 OkHttp 实例**。`RetrofitClient` 的 cookie 拦截器会给每个请求塞 ncm 的 `Cookie`/`User-Agent`/`Referer`（见 `AGENTS.md` 的 Network Layer 一节）。把 ncm 的 `MUSIC_U` 发给 `amp.shazam.com` 是**凭证外泄**。实现里要显式 `OkHttpClient.Builder().build()`，不带任何拦截器。
2. **`amp.shazam.com` 走 https + 固定 UA 轮换**（抄 Audile 的 10 条 Dalvik UA，`ShazamRecognitionService.kt:135-146`），并且**不要**复用 `usesCleartextTraffic="true"`（`AndroidManifest.xml:39`）——它只影响 http，不影响 https，但会让人误以为可以放松。
3. **超时要显式设**：`connectTimeout = 8s` / `readTimeout = 12s`。默认 10 s/10 s 在弱网下会把"无匹配"变成"超时"，用户看到的是错误的失败原因。
4. **不做识别历史落盘也能满足验收**。若一定要留，用 `ncrust_settings` 里一个 JSON 数组（≤20 条），**照抄 `MatchCacheCodec` 的"可空 + 默认值 + 缺字段即 miss"三原则**（`crosssource/MatchCacheCodec.kt`）。
5. **UI 文案必须覆盖 8 个 locale**（`zh_CN`/`zh_TW`/`en`/`jp_JP`/`jp_MY`/`ko_NK`/`de_DE`/`ru_RU`，见 `ui/i18n/` 目录）。漏一个 locale = 编译不过（`Strings` 是 `data class`，缺参会报错）——这是仓库机制给的免费保障。

**验收**：

- 真机（建议 API 31 与 API 24 各一台）：点入口 → 授权 → 播放外部音源 → 6 s 内出结果 → 点结果能起播；
- 断网：显示"网络错误"且**只**显示一次（不重试到 3 次才报错）；
- 静音环境：**不发网络请求**（logcat 里没有 `POST amp.shazam.com`），直接提示"没听到声音"；
- `adb shell dumpsys package com.takahashirinta.ncrust | grep RECORD_AUDIO` 确认没新增权限；
- `git diff` 里 `AndroidManifest.xml` **没有新增 `<service>`**。

### PR 3：内录（`AudioPlaybackCapture`）+ 能力分层

| 文件 | 动作 |
|---|---|
| `recognition/audio/CaptureClipRecorder.kt` | 新增：`AudioPlaybackCaptureConfiguration` + `MediaProjection`，抄 `DefaultSoundSource.kt:60-74` 的 usage 组合 |
| `recognition/ClipSourceAvailability.kt` | 新增：§1.4 的纯函数 |
| `recognition/CaptureService.kt` + `AndroidManifest.xml` | 新增 FGS（`mediaProjection` 类型 + `FOREGROUND_SERVICE_MEDIA_PROJECTION`）**仅当**这一版决定支持"离开界面继续录"；否则**不要**加 |
| `ui/recognition/ClipSourcePicker.kt` | 新增：按 `ClipSourceAvailability.available()` 渲染 |
| `ui/i18n/*` | 改：加"内录"相关文案 + **投屏授权的解释文案** |

**验收**：API 29+ 真机能识别别的 app 播放的音频；API 24–28 真机上"内录"选项**不出现**且不崩溃；对**关掉了 `allowAudioPlaybackCapture`** 的 app（可用一个自建 demo app 模拟，`targetSdk=29` + `allowAudioPlaybackCapture="false"`）识别返回 `NO_SOUND` 而不是 `UNKNOWN`。

**三个 PR 的共同纪律**（写进每个 PR 的描述）：

- `versionCode` 必须由 `tools/next-version.sh` 决定（`AGENTS.md` 的"动版本号之前必须先跑"）；
- 一个逻辑单元一个 commit，`feat:` / `refactor:` 前缀 + 中文 subject；
- 新文件头两份版权声明（MIT 上游 + GPLv3 fork），照抄任意现有文件的 6 行头即可；
- 搬来的 GPLv3 代码（SongRec / Audile）在文件头写明来源与许可证，并更新 `THIRD-PARTY-LICENSES.md`。

---

## 8. 风险清单

### 8.1 第三方接口稳定性

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| `amp.shazam.com` 改协议/加签 | **高**（历史上有过 v4→v5） | 识别整体失效 | ① `PROTOCOL_CHANGED` 独立失败态（`200` + 无 `matches` + schema 不符）；② Provider 可替换（§3 的接口就是为这个设计的）；③ 关键：**把响应 schema 的"必填字段"缩到最小**（只有 `track.title` + `track.subtitle`），少解析就少碎 |
| Shazam 按 UA 封禁 | 中 | 同上 | 不做激进轮换；失败时**不要**无限重试（可能触发风控） |
| `amp.shazam.com` 需要 `Content-Language` 等头 | 已知 | 400/空 | 抄 Audile 的固定头（`Content-Language: en_US`） |
| 被识别方关掉 `allowAudioPlaybackCapture` | 中（PR 3 起） | 内录对该 app 失效 | UI 上明确"某些应用不允许被录制"；降级到麦克风 |

### 8.2 额度

| Provider | 免费额度（**未逐条核实，见未验证事项**） | 对 Arris 的影响 |
|---|---|---|
| Shazam（自实现协议） | 无官方额度，**无凭证** | 风险不是钱，是"被风控" |
| AudD | 有免费额度上限 | 用户自带 token 时与我们无关 |
| ACRCloud | 有免费额度上限 | 同上 |
| AcoustID | 免费，要求署名 | 无额度问题，但要给署名 |
| ShazamKit | 计入 Apple 开发者账号 | 不启用 |

**设计约束**：**任何 Provider 的 `QUOTA` 都不得触发重试**（`RecognitionEngine` 已强制）。这是唯一一条"额度保护"的硬编码，必须单测钉住。

### 8.3 隐私（录音是否落盘）

| 项 | 决定 | 依据 |
|---|---|---|
| 默认 | **不落盘**。PCM 只在内存里，识别结束（无论成败）立即 `Arrays.fill(samples, 0)` | 与 `BlowRecorder` 的纪律一致：`/** Foreground UI owns cancellation. No file, upload, audio-focus request, SCO or output-route change. */`（`BlowRecorder.kt:24`） |
| Debug | 可选落 `cacheDir/recognition/`，文件名带时间戳，**`BuildConfig.DEBUG` 门控** | 便于复现指纹 bug |
| 用户显式"反馈样本" | 可选，但**必须**弹二次确认 + 说明"会上传给 X 服务" | 合规底线 |
| 内录（PR 3） | 投屏授权文案必须说清"Arris 会读取正在播放的声音用于识别，不录制屏幕、不上传画面" | 用户看到的是"截取屏幕"，不解释必然被当成间谍软件 |

**另外两条容易漏的**：

- **`RecognizedTrack` 与候选列表不要写进日志**（`Log.d` 带歌名 = 泄露用户在听什么）。仓库里 `Log.w(TAG, "search failed on …")` 这种只带 source key 的写法是正确示范。
- **`recognition_history_json` 若落盘**，它是"用户听过什么"的日志 —— 必须进"清除缓存"入口的清理范围（`SettingsRowKind.CLEAR_CACHE`）。

### 8.4 Google Play 政策（如适用）

Arris 是 GPLv3 的第三方客户端，**本身就不在 Play 上架**（`AGENTS.md` 提到"合规定位"、`docs/` 里全是 release notes + 自建分发）。所以这一节的实际适用性取决于是否要上架：

| 若上架，会撞上的条款 | 具体 |
|---|---|
| **`RECORD_AUDIO` 的"突出披露"** | Play 要求在使用麦克风前有 in-app 披露（不能只靠系统弹窗）。识别功能必须有一句"点击开始后 Arris 会录制约 6 秒的声音用于识别歌曲" |
| **`QUERY_ALL_PACKAGES` / 敏感权限** | 不涉及 |
| **"欺骗性行为"** | 内录时用 `MediaProjection` 但实际只取音频，**必须在披露里说清**，否则可能被判"以投屏为名获取屏幕内容" |
| **数据安全表单** | 若录音上传给第三方（AudD/ACRCloud），必须申报"音频数据共享给第三方" |
| **`allowAudioPlaybackCapture` 被识别方拒绝** | 不是政策问题，是技术限制 |

### 8.5 其他工程风险（按"会咬人"排序）

| # | 风险 | 触发条件 | 缓解 |
|---|---|---|---|
| 1 | **指纹实现与 SongRec 差一个字节 → 静默 0 匹配** | 首次实现 | 黄金文件测试（§7 PR1）；且**第一件事**是用 `shazamio` 的 Python 实现跑同一段 PCM 对拍 |
| 2 | **`Visualizer(0)` 在部分 ROM 恒返回 0** | PR 3 / API 24–28 | 启动 300 ms 自检，失败即从 `available()` 里剔除 |
| 3 | **8 bit 量化下指纹仍能工作**（这是 Visualizer 路径成立的前提） | **未实测** | 列入未验证事项；若实测不行，API 24–28 就只有麦克风 |
| 4 | **识别到的歌在两源都不可播** | 常见（冷门曲/地区限制） | UI 必须能显示"找到了但都不可播"，而不是空白。用 `TrackAvailability` 的四档 + `rankByAvailability` |
| 5 | **同名翻唱被当成原曲自动播放** | ncm 有 `originCoverType` 可以挡；QQ 侧只能靠 `editionKinds` + 艺人不一致 | `autoPlay()` 只在 `EXACT` 且 `PLAYABLE` 时触发（§5.6）；`MEDIUM` 一律要用户点 |
| 6 | **识别请求带上了 ncm cookie** | 复用 `RetrofitClient` 就会发生 | PR 2 用独立 OkHttp 实例（§7） |
| 7 | **`RECORD_AUDIO` 与生日功能抢麦克风** | 两个功能同时开 | 录音前检查 `AudioRecord.state`，失败给 `BAD_AUDIO`；`BlowRecorder` 的错误处理就是这个形状（`BlowRecorder.kt:37,42`） |
| 8 | **proguard/R8 把新包裁掉** | `isMinifyEnabled = true`（`app/build.gradle.kts:556`） | 新代码只要有反射/Gson 反序列化的 DTO，就要加 `@Keep` 或 keep 规则；识别 DTO **建议不用 Gson**（用现成的 `JSONObject` 手解析，和 `SongUrlFetcher` 一样，`SongUrlFetcher.kt:353-388`），彻底绕开 R8 风险 |
| 9 | **`targetSdk = 36` 的未来变化** | 每年 | 内录依赖的 `mediaProjection` 类型规则在 API 34/35 各收紧过一次（§2.3），要留一次"提前一版适配"的预算 |

---

## 未验证事项

以下每一条都是**本次没有做实测**的，实现前必须先验证；按"不验证就会做错"的严重度排序。

1. **8 bit（Visualizer）量化下 Shazam 式指纹的匹配率**。§1.2 论证了"理论上够用"（相对峰不受绝对幅度影响），但**没有实测**。验证方法：取 `amp.shazam.com` 能识别的 20 段 6 s 样本，分别用 16 bit 与 8 bit 量化后生成 sig，比较 `matches` 命中率。**若命中率 < 50%，API 24–28 的内录路径应当放弃，只留麦克风。**

2. **`amp.shazam.com/discovery/v5/.../tag/{uuid}/{uuid}` 在 2026-10 是否仍然可用、返回 schema 是否与 SongRec 0.8.0 的解析一致**。本次只读了源码，**没有发真实请求**（工作区内无凭证、也不宜在调研阶段消耗对方风控额度）。验证方法：`python3 -c` 用 `shazamio` 对一个已知样本跑一次，比对 `matches[].track.title`。

3. **Shazam / Apple 的服务条款是否允许第三方客户端直连未公开端点**。本次**未逐条核实** ToS 原文。它不影响代码的 GPLv3 合规性（§6.3），但影响可分发性与是否存在法律风险。**需要产品或法务确认。**

4. **ShazamKit AAR 内的实际许可条款文本**。AAR 需要 Apple 开发者账号登录才能下载（`developer.apple.com/download/all/?q=Android+ShazamKit` → "Sign In - Apple"），本次**拿不到 AAR 与其内嵌 LICENSE**。§6.2 的"需律师确认"结论基于"闭源二进制 + GPLv3 §6 完整对应源码"的结构性冲突，**不是**基于读过的条款原文。

5. **`MediaProjection` 在 Android 14+ 的"一次性令牌 / 必须注册 Callback"具体约束**。本次抓 `behavior-changes-all` 页面时**没有定位到对应小节**（页面结构变了）。已知的定性结论（令牌一次性、需 `registerCallback`）**未取得官方原文**，列此存疑。验证方法：API 34 模拟器上连续两次 `getMediaProjection()`，看第二次是否抛 `SecurityException`。

6. **`Visualizer(0)` 在目标机型（作者常用机：SM-G9209 / API 24、WGR-W09 / API 31、AGT-AN00 / API 35）上的实际可用性**。Audile 的代码说明它"能用"，但 Audile 的用户群与国内 ROM 不重合。验证方法：三台机器各跑一次 5 s 抓取，检查返回字节是否非全 0。

7. **AcoustID `lookup` 对 10~15 s 片段的行为**。§4.2 的结论（"不适合短片段"）来自**算法结构**（chroma + 整曲 duration 语义），不是实测。若第二版要考虑 Chromaprint 兜底，需要用真实短样本跑一次 `api.acoustid.org/v2/lookup`。

8. **AudD / ACRCloud 的当前免费额度与价格**。本次**没有访问两家定价页**（§8.2 的表述刻意留了"未逐条核实"）。这直接影响"是否值得做用户自带 token 的 UI"。

9. **`SourceRouter.searchSongs` 在"歌名 + 艺人"拼接查询下的召回率**。§5.3 的查询串策略没有实测。验证方法：用 30 首已知曲目构造"假识别结果"，看 `EXACT` 命中率（预期 > 90%，但 `NameNormalizer` 不做繁简转换 —— `周杰伦` vs `周杰倫` 会判成两个艺人，见 `NameNormalizer.kt:184-190` 的注释，这是**已知的**召回损失）。

10. **`ncrust_settings` 里新增键对 `SettingsRegistryTest` 的影响**。仓库有"42 键双向等值"的机械断言（`SettingsRenderPlan.kt` 注释提到 `legacyPrefKeysArePreservedExactly`）。新增识别相关键时**必须**同步更新该测试的期望清单，否则 CI 会红 —— 这一条本次只读到注释，**没有实际跑 `./gradlew test`**（调研阶段不构建）。
