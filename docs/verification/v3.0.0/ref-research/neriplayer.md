# NeriPlayer（音理）音频反应式动效调研

> 调研对象：**NeriPlayer（音理）** — <https://github.com/cwuom/NeriPlayer>
> 调研时间：本轮会话；**证据基线 = 仓库默认分支 `master` 的一次完整浅克隆**
> 证据提交：`db85bf68a42ab8907650638990f8a4c92a7be830`（`2026-09-27 19:20:35 +0800`，`fix(ci): align widget tests and speed up bulk delete setup (#446)`）
>
> **取证方式（重要）**：GitHub REST API 在本机出口 IP 上返回 `API rate limit exceeded`，因此**没有**使用 API 列目录；
> 改为 `git clone --depth 1 https://github.com/cwuom/NeriPlayer.git`，克隆**成功**。本报告的全部文件路径、行号与代码片段
> 均来自该本地克隆，并用 `curl -sL https://raw.githubusercontent.com/cwuom/NeriPlayer/master/...` 对关键文件做了
> **逐字节 diff 复核**（`AudioReactive.kt` 远程与本地 `IDENTICAL`，HTTP 200）。
>
> **已知取证盲区**：`.gitmodules` 中声明的 4 个子模块（`np-submodule/miuix`、`np-submodule/accompanist-lyrics-ui`、
> `np-submodule/accompanist-lyrics-core`、`np-submodule/NeriPlayer-LTW`）在浅克隆中为**空目录**（各 0 个条目），
> 未做二次取证。涉及子模块的结论一律标注「未核实」。

---

## 0. 结论速览

- ✅ **反应性是「真的」**，但不是靠 Android `Visualizer`，也不是靠麦克风：它用 **Media3 `TeeAudioProcessor` 从 ExoPlayer 自己的 PCM 管线里分流样本**，在**播放线程**上算 **RMS + 双 EMA 起音（onset）检测**。
  证据：[`ReactiveRenderersFactory.kt#L131`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/engine/ReactiveRenderersFactory.kt#L131)、[`AudioReactive.kt#L84-L99`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L84-L99)
- ✅ **全仓库没有任何 FFT / 频谱 / 频段分析代码**。`grep -rni "fft|spectrum|频谱|bands"` 在 `app/src/main` 下只命中等化器频段与「同步频率」等无关内容；
  `AudioReactive` 只输出两个标量：`level`（响度）与 `beat`（起音脉冲）。
- ✅ **Android 13+ `RuntimeShader` + AGSL 确实在用**，但**只有两处生产代码**：`BgEffectPainter.java`（音频反应背景）与 `AdvancedGlassRenderEffect.kt`（玻璃模糊遮罩，**与音频无关**）。
  AGSL 源码放在 `app/src/main/assets/shaders/` 里，**不是** Kotlin 内联字符串。
- ⚠️ **绑定方式是「真实音频包络 × 固定周期载波」，不是「鼓点触发冲击波」**。`beat` 只用来**缩放**正弦载波的幅度（`uv += beatEase * 0.011 * beatWave`），
  没有 onset 事件、没有一次性冲击波、没有生命周期管理。这一点对 Ncrust 的「禁止固定周期假反应」规则**必须逐个效果复核**。
  证据：[`hyper_background_effect.glsl#L56-L63`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/assets/shaders/hyper_background_effect.glsl#L56-L63)
- ✅ **静音时 shader 输出是静止的**：`uAnimTime` 只出现在被 `beatEase`/`motionEase` 乘过的项里，静音 ⇒ `motionEase=0` ⇒ 位移项全部为 0。这是「真反应」的强证据。
  （**例外**：Java 侧的 5 个色块点有一个默认 `uPointOffset = 0.1` 的常驻漂移，静音也在动——属于「氛围动效」而非「假反应」。）
- 🚨 **许可有一处红线级疑点**：根 `LICENSE` 是 **GPL-3.0**（无争议），但 `BgEffectPainter.java` 文件头明确写着
  `! Reference: https://github.com/ReChronoRain/HyperCeiler !`，而 **HyperCeiler 的 LICENSE 是 AGPL-3.0**。
  若该文件是 HyperCeiler 的衍生物，则 NeriPlayer 以 GPL-3.0 单独分发它本身就有问题，Ncrust 更**不应直接搬运该文件**。
  详见 §6.4（已标注为需法务核实的「未核实」项，本报告不指控任何一方侵权）。
- ⚠️ **性能上没有免费的午餐**：开启音频反应会**强制关闭 audio offload**（`AUDIO_OFFLOAD_MODE_DISABLED`），把整条链路拉回 PCM 软件管线——这是耗电代价，不是纯 GPU 代价。
  证据：[`PlaybackAudioOffloadPolicy.kt#L32`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/policy/offload/PlaybackAudioOffloadPolicy.kt#L32)
- ⚠️ **没有文档化的帧时间数字**：README / CONTRIBUTING 中 `grep -rni "帧率|frame time|fps|jank"` **零命中**。渲染性能只有 Android 仪器测试（像素级比对），**没有 benchmark**。
- 🎯 **对 Ncrust 的直接价值**：`TeeAudioProcessor` 这条通路**满足「数据源可靠」**（是你自己的解码后 PCM，不是估算），
  且 **RMS+onset 的计算量足以忽略**；真正需要权衡的是**强制关闭 offload 的功耗**，以及**是否值得为此引入 FFT**——NeriPlayer 的答案是「不值得，两个标量就够了」。

---

## 1. 数据通路

### 1.1 结论

**不使用 `android.media.audiofx.Visualizer`，不使用 `AudioRecord` / 麦克风，不自定义 `AudioSink` 做抓取，
而是把 Media3 官方的 `TeeAudioProcessor` 插进 `DefaultAudioSink` 的 AudioProcessor 链。**

### 1.2 证据

**（a）全仓库不存在 `Visualizer` API 调用。**
`grep -rn "Visualizer\|android.media.audiofx" app/src/main` 的唯一命中是音量/音效控制器，且只用到 `Equalizer` 与 `LoudnessEnhancer`：

- [`PlaybackEffectsController.kt#L3-L4`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/PlaybackEffectsController.kt#L3-L4)
  ```kotlin
  import android.media.audiofx.Equalizer
  import android.media.audiofx.LoudnessEnhancer
  ```

**（b）不存在录音权限，也没有 `AudioRecord`。**
`app/src/main/AndroidManifest.xml` 的 `uses-permission` 列表中没有 `RECORD_AUDIO`；
`grep -rn "AudioRecord\|RECORD_AUDIO\|MediaRecorder" app/src/main` **零命中**。
来源：[`AndroidManifest.xml`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/AndroidManifest.xml)

**（c）TeeAudioProcessor 的注入点。**
[`ReactiveRenderersFactory.kt#L123-L140`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/engine/ReactiveRenderersFactory.kt#L123-L140)：

```kotlin
override fun buildAudioSink(
    context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean
): AudioSink {
    forceFfmpegPcm16Output = !enableFloatOutput
    val volumeNormalization = VolumeNormalizationAudioProcessor()
    val balance = StereoBalanceAudioProcessor()
    val tee = TeeAudioProcessor(AudioReactive.teeSink)          // ← L131
    val fallbackSink = DefaultAudioSink.Builder(context)
        .setAudioProcessors(arrayOf<AudioProcessor>(volumeNormalization, balance, tee))  // ← L133
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioOutputPlaybackParameters(false)
        .build()
    return UsbExclusiveAudioSink(context.applicationContext, fallbackSink)              // ← L139
}
```

类注释同样明说（[`ReactiveRenderersFactory.kt#L49-L53`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/engine/ReactiveRenderersFactory.kt#L49-L53)）：

> `自定义 RenderersFactory: - 注入 TeeAudioProcessor 将 PCM 能量送入 AudioReactive, 供可视化/背景特效使用`

注意：`TeeAudioProcessor` 与 `DefaultRenderersFactory.buildAudioSink` 都是 Media3 的 `@UnstableApi`，
因此本文件与 `AudioReactive.kt` 都做了 `OptIn`（[`AudioReactive.kt#L1`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L1)：`@file:androidx.annotation.OptIn(markerClass = [UnstableApi::class])`）。
依赖版本为 **media3 1.10.1**（[`gradle/libs.versions.toml#L30`](https://github.com/cwuom/NeriPlayer/blob/master/gradle/libs.versions.toml#L30)：`media3Exoplayer = "1.10.1"`）。

**（d）Sink 的实现。**
[`AudioReactive.kt#L84-L99`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L84-L99)：

```kotlin
val teeSink = object : TeeAudioProcessor.AudioBufferSink {
    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        this@AudioReactive.sampleRate = sampleRateHz
        this@AudioReactive.channels   = max(1, channelCount)
        this@AudioReactive.encoding   = encoding
        emaFast = 0.0; emaSlow = 0.0; noiseEma = 0.0
        lastBeatNs = 0L; lastBeatUpdateNs = 0L
        _level.value = 0f; _beat.value = 0f
    }
    override fun handleBuffer(buffer: ByteBuffer) { handlePcmBuffer(buffer) }
}
```

**（e）USB 独占播放的第二条采样通路。**
当走 USB 独占（native UAC）输出时，`DefaultAudioSink` 被绕过，于是 `UsbExclusiveAudioSink` 自己在写入前手动喂样本：

- [`UsbExclusiveAudioSink.kt#L567`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/usb/sink/UsbExclusiveAudioSink.kt#L567)
  ```kotlin
  // usb native 在后级才乘系统音量，这里同步视觉采样增益
  AudioReactive.handlePcmBuffer(original, effectiveVolume = nativeVolume)
  ```
- [`UsbExclusiveAudioSink.kt#L898`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/usb/sink/UsbExclusiveAudioSink.kt#L898)
  ```kotlin
  AudioReactive.teeSink.flush(sampleRate, channelCount, pcmEncoding)
  ```

**（f）采样率 / 声道 / 编码 / 缓冲区大小。**

| 项 | 取值 | 证据 |
|---|---|---|
| 采样率 | **不硬编码**，从 `flush(sampleRateHz, ...)` 读真实值；字段默认值 `44100` 仅用于未 flush 前的占位 | [`AudioReactive.kt#L68-L70`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L68-L70) |
| 声道 | 同上，默认 `2`，`max(1, channelCount)` 兜底 | 同上 |
| 编码 | 支持 PCM 8/16/16BE/24/24BE/32/32BE/FLOAT，共 8 种分支 | [`AudioReactive.kt#L119-L129`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L119-L129) |
| 缓冲区大小 | **NeriPlayer 自己不定义任何 buffer size 常量**；块大小 = Media3 音频管线喂进来的解码输出缓冲 | 见下 |

缓冲区大小的上游依据（Media3 1.10.1 源码，非 NeriPlayer）：
[`TeeAudioProcessor.java#L80-L87`](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/TeeAudioProcessor.java#L80-L87) 的 `queueInput` 把**整个输入 ByteBuffer**原样转给 sink：

```java
@Override
public void queueInput(ByteBuffer inputBuffer) {
  int remaining = inputBuffer.remaining();
  if (remaining == 0) { return; }
  audioBufferSink.handleBuffer(Util.createReadOnlyByteBuffer(inputBuffer));
  replaceOutputBuffer(remaining).put(inputBuffer).flip();
}
```

而 `DefaultAudioSink` 是在 sink 写路径里调用 `audioProcessingPipeline.queueInput(inputBuffer)` 的
（[`DefaultAudioSink.java#L1159`](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L1159)），
`inputBuffer` 即 renderer 交给 sink 的那一块。**结论：采样窗口大小由 Media3/解码器决定，NeriPlayer 没有做窗口化（windowing），也没有固定 hop size。**

**（g）计算发生在哪个线程？—— ExoPlayer 的播放线程（playback thread），不是 UI 线程，也不是独立的音频输出线程。**

推理链（全部有源码支撑）：
1. `TeeAudioProcessor.queueInput()` 在第 85 行**同步调用** `audioBufferSink.handleBuffer(...)`（见上）。
2. `queueInput` 由 `DefaultAudioSink` 在 `handleBuffer()` 内部的缓冲区泵里同步调用（`DefaultAudioSink.java#L1159`）。
3. Media3 的 `DefaultAudioSink` 源码注释点明了回调线程（[`DefaultAudioSink.java#L1763-L1764`](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/DefaultAudioSink.java#L1763-L1764)）：
   > `// Must be lazily initialized to receive listener events on the current (playback) thread as`
   > `// the constructor is not called in the playback thread.`

因此 `rms16()` 之类的逐样本循环**跑在播放线程上**，会与解码/渲染共享同一个 Looper。
好消息是 `handlePcmBuffer` 第一行就是 `if (!enabled || !buffer.hasRemaining()) return`
（[`AudioReactive.kt#L117`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L117)），关闭时零开销。

> 补充说明：Media3 官方对 `TeeAudioProcessor` 的定位是 *"This is intended to be used for diagnostics and debugging."*
> （[`TeeAudioProcessor.java#L34-L43`](https://github.com/androidx/media/blob/1.10.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/audio/TeeAudioProcessor.java#L34-L43)）。
> NeriPlayer 把它用于生产特性，属于**超出上游设计意图的用法**——能用，但升级时需自行承担 API 变动风险。

**（h）启用条件（决定何时开始采样）。**
[`NeriApp.kt#L3785-L3791`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/NeriApp.kt#L3785-L3791)：

```kotlin
val effectiveDynamicBackgroundEnabled =
    nowPlayingDynamicBackgroundEnabled && !nowPlayingCoverBlurBackgroundEnabled
val effectiveAudioReactiveEnabled =
    nowPlayingAudioReactiveEnabled && effectiveDynamicBackgroundEnabled

DisposableEffect(showNowPlaying, effectiveAudioReactiveEnabled, lifecycleResumed) {
    AudioReactive.enabled = showNowPlaying && effectiveAudioReactiveEnabled && lifecycleResumed
    onDispose { AudioReactive.enabled = false }
}
```

即：**播放页可见 + 动态背景开 + 音律动开 + 前台 RESUMED** 才采样。

**（i）开启反应式的真实代价：强制关闭 audio offload。**
[`PlaybackAudioOffloadPolicy.kt#L8-L34`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/policy/offload/PlaybackAudioOffloadPolicy.kt#L8-L34)：

```kotlin
internal fun requiresPcmAudioProcessing(..., audioReactiveActive: Boolean, ...): Boolean {
    return audioSource == PlaybackAudioSource.NETEASE ||
        ...
        audioReactiveActive ||                       // ← L32
        ...
}
```

配合 [`PlayerManagerLifecycleExtensions.kt#L1594-L1620`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/lifecycle/PlayerManagerLifecycleExtensions.kt#L1594-L1620)：
`requiresPcmProcessing == true` ⇒ `AUDIO_OFFLOAD_MODE_DISABLED`。
另有 `AudioReactive.onEnabledChanged` 回调（[`PlayerManagerLifecycleExtensions.kt#L581-L596`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/lifecycle/PlayerManagerLifecycleExtensions.kt#L581-L596)）
在切换时重新下发 offload 偏好，并刻意避免在播放中途重建管线：

```kotlin
AudioReactive.onEnabledChanged = { enabled -> mainScope.launch {
    val playbackActive = isTransportActiveWithoutInitialization()
    if (shouldUpdateAudioOffloadForReactiveChange(enabled, playbackActive)) {
        updateAudioOffloadPreferences("audio_reactive_$enabled")
    } else { /* keep audio offload pipeline during active playback */ }
} }
```

（`shouldUpdateAudioOffloadForReactiveChange` = `audioReactiveEnabled || !playbackActive`，见同文件 `#L36-L41`。）
这是一条**很有价值的工程经验**：开关视觉反应会改变音频管线拓扑，必须防止在播放中途爆音。

---

## 2. 音频特征

### 2.1 结论

**只有两个标量特征：`level`（感知响度，0..1）和 `beat`（起音脉冲，0..1 带衰减）。**
**没有 FFT、没有频段能量、没有频谱质心、没有过零率、没有 chroma。**

### 2.2 `level`：RMS + 感知压缩

核心循环（[`AudioReactive.kt#L96-L154`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L96-L154)）：

```kotlin
val rawLevel = when (encoding) {
    C.ENCODING_PCM_8BIT  -> rms8(buffer)
    C.ENCODING_PCM_FLOAT -> rmsFloat(buffer)
    C.ENCODING_PCM_16BIT -> rms16(buffer, ByteOrder.LITTLE_ENDIAN)
    C.ENCODING_PCM_24BIT -> rms24(buffer, bigEndian = false)
    C.ENCODING_PCM_32BIT -> rms32(buffer, ByteOrder.LITTLE_ENDIAN)
    ...
}
val gain = effectiveVolume.coerceIn(0f, 1f).toDouble()
val lvl = (rawLevel * gain).coerceIn(0.0, 1.0)
```

各 `rms*` 都是标准做法：归一化到 ±1.0 → 平方累加 → `sqrt(sum/count)`。
例如 16 bit（[`#L196-L208`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L196-L208)）：

```kotlin
private fun rms16(buf: ByteBuffer, byteOrder: ByteOrder): Double {
    val dup = buf.duplicate().order(byteOrder)
    var sum = 0.0; var count = 0
    while (dup.remaining() >= 2) {
        val s = dup.short.toInt()
        val f = s / 32768.0
        sum += f * f; count++
    }
    if (count == 0) return 0.0
    return sqrt(sum / count)
}
```

最后做一次**感知压缩**（`#L152-L153`）：

```kotlin
val perceptual = sqrt(lvl).toFloat()
_level.value = if (newBeat) max(perceptual, min(1f, perceptual + 0.08f)) else perceptual
```

即 `level = sqrt(RMS × volume)`，并且**在起音帧上额外抬升 0.08**（有上限 1.0）——一个很轻的视觉「提亮」手法。

### 2.3 `beat`：双 EMA 差分 + 自适应噪声门 + 不应期

常量（[`AudioReactive.kt#L63-L66`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L63-L66)）：

```kotlin
private const val MIN_BEAT_GAP_NS = 120_000_000L          // 120 ms 不应期
private const val BEAT_DECAY_REFERENCE_NS = 16_666_667L   // 一帧 @60Hz
private const val BEAT_DECAY_PER_REFERENCE = 0.90         // 每帧 ×0.90
private const val EPS = 1e-9
```

检测逻辑（[`AudioReactive.kt#L133-L150`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L133-L150)）：

```kotlin
val aFast = 0.5   // 攻速
val aSlow = 0.05  // 释速
emaFast = aFast * lvl + (1 - aFast) * emaFast
emaSlow = aSlow * lvl + (1 - aSlow) * emaSlow

val delta = max(0.0, emaFast - emaSlow)
noiseEma = 0.02 * delta + 0.98 * noiseEma            // 自适应噪声地板
val threshold = 3.0 * (noiseEma + EPS)

var newBeat = false
if (delta > threshold && nowNs - lastBeatNs > MIN_BEAT_GAP_NS) {
    lastBeatNs = nowNs; lastBeatUpdateNs = nowNs
    _beat.value = 1f; newBeat = true
} else {
    decayBeat(nowNs)
}
```

衰减是**按真实经过时间**而不是按缓冲区个数（[`#L171-L181`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L171-L181)）：

```kotlin
val references = elapsedNs.toDouble() / BEAT_DECAY_REFERENCE_NS.toDouble()
_beat.value *= BEAT_DECAY_PER_REFERENCE.pow(references).toFloat()
```

这一点有单元测试保护（[`AudioReactiveTest.kt#L46-L54`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/test/java/moe/ouom/neriplayer/core/player/AudioReactiveTest.kt#L46-L54)）：

```kotlin
@Test fun `beat decay follows elapsed time instead of buffer count`() { ... }
```

**特征小结表**

| 特征 | 算法 | 输出范围 | 更新频率 | 线程 |
|---|---|---|---|---|
| `level` | 逐样本 RMS → `sqrt(RMS×vol)` | 0..1 | 每个 PCM 缓冲一次 | 播放线程 |
| `beat` | `max(0, EMA(0.5) − EMA(0.05))` > `3×自适应噪声地板`，120 ms 不应期，命中置 1，否则按 0.90/16.67 ms 指数衰减 | 0..1 | 同上 | 播放线程 |
| 频段/频谱 | **不存在** | — | — | — |

**对外暴露方式**：`MutableStateFlow<Float>`（[`AudioReactive.kt#L79-L82`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L79-L82)），
再经 `PlayerManager` 转发（[`PlayerManager.kt#L705-L706`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/PlayerManager.kt#L705-L706)）：

```kotlin
val audioLevelFlow get() = AudioReactive.level
val beatImpulseFlow get() = AudioReactive.beat
```

---

## 3. 动效绑定

### 3.1 唯一的消费者

`grep -rn "audioLevelFlow\|beatImpulseFlow"` 在 `app/src/main` 下**只有 3 处命中**：
`PlayerManager` 的定义（2 处）+ `HyperBackground.kt#L318,#L321` 的消费。
**音频特征目前只驱动「正在播放页的动态流体背景」这一个效果。**

### 3.2 第一层平滑（Compose 侧，逐帧）

[`HyperBackground.kt#L306-L370`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L306-L370)：

```kotlin
val targetLevel = currentLevel.coerceIn(0f, 1f)
val targetBeat = (maxOf(currentBeat, pendingBeatPeak) * 0.94f).coerceIn(0f, 1f)
pendingBeatPeak = 0f
val levelRate = if (targetLevel > smoothLevel) 0.12f else 0.045f   // 非对称：攻快释慢
val beatRate  = if (targetBeat  > smoothBeat)  0.46f else 0.12f
smoothLevel += (targetLevel - smoothLevel) * levelRate
smoothBeat  += (targetBeat  - smoothBeat)  * beatRate
painter.setReactive(smoothLevel, smoothBeat)
```

要点：
- **非对称一阶低通（one-pole，attack/release 不同系数）** —— 这是「包络跟随 / envelope follower」的标准形态，NeriPlayer 做得**规范**。
- `pendingBeatPeak` 是**峰值保持（peak hold）**：因为音频线程可能在两帧之间连发多个 beat，UI 每帧只取一次流值会漏掉峰值，所以用 `max()` 累积、每帧消费后清零（`#L310`、`#L324`、`#L349`）。这是一个**真实存在的工程细节**，不是装饰。
- `* 0.94f` 是给 beat 留 6% 顶部余量。

### 3.3 第二层映射（Java 侧，uniform 计算）

[`BgEffectPainter.java#L216-L261`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L216-L261)：

```java
private void updateReactiveUniforms() {
    uLevelEase = smoothStep(0.04f, 0.82f, uMusicLevel);
    uBeatEase  = smoothStep(0.03f, 0.62f, uBeat);
    uMotionEase = clamp01(0.42f * uLevelEase + 0.82f * uBeatEase);
    uZoom       = 1.0f + 0.024f * uLevelEase + 0.105f * uBeatEase;
    uColorPulse = clamp01(0.68f * uLevelEase + 0.32f * uBeatEase);
    ...
}

private void updateAnimatedPoints() {
    float pointOffset  = uPointOffset + 0.022f * uLevelEase + 0.108f * uBeatEase;
    float radiusMulti  = uPointRadiusMulti * (1.0f + 0.045f * uLevelEase + 0.220f * uBeatEase);
    ...
    float pushScale = pushLength > 0f ? uBeatEase * 0.118f / pushLength : 0f;   // 径向推开
    ...
}

private void updateGlobalMotionUniform() {
    uGlobalMotion[0] = uMotionEase * 0.0060f * (float) Math.sin(uAnimTime * 1.9f);
    uGlobalMotion[1] = uMotionEase * 0.0060f * (float) Math.cos(uAnimTime * 1.6f);
}
```

### 3.4 绑定关系总表（**这是本报告最关键的表**）

| 视觉量 | 绑定的音频特征 | 映射公式 | 是否有阈值/平滑 |
|---|---|---|---|
| `uZoom`（整体缩放） | `level` + `beat` | `1 + 0.024·L + 0.105·B` | 有：`smoothStep` 软化 + 两段 one-pole |
| `uColorPulse`（饱和度/明度脉冲） | `level`(0.68) + `beat`(0.32) | `clamp01(0.68L + 0.32B)` | 同上 |
| `uMotionEase`（运动总量） | `level`(0.42) + `beat`(0.82) | `clamp01(0.42L + 0.82B)` | 同上 |
| `uPoints[].z`（色块半径） | `level` + `beat` | `×(1 + 0.045L + 0.220B)` | 同上 |
| 色块位移幅度 | `level` + `beat` | `0.1 + 0.022L + 0.108B` | 同上 |
| 色块径向「推开」 | `beat` | `B · 0.118 / dist` | 同上 |
| UV 抖动（beatWave） | `beat` | `uv += B · 0.011 · vec2(w, −w)` | 同上 |
| UV 径向脉冲（radialPulse） | `beat` | `uv += B · 0.0085 · normalize(vUv−c) · r` | 同上 |
| UV 带状波（ribbonWave） | `motionEase` | `uv += M · 0.0062 · vec2(w, −0.75w)` | 同上 |
| 全局平移 `uGlobalMotion` | `motionEase` | `M · 0.006 · (sin(1.9t), cos(1.6t))` | 同上 |
| **全体 alpha** | **刻意不绑定** | `color.a *= uAlphaMulti`（常量 1.0） | — |

GLSL 侧对应代码（[`hyper_background_effect.glsl#L56-L63`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/assets/shaders/hyper_background_effect.glsl#L56-L63)）：

```glsl
float beatWave = sin((vUv.y + uAnimTime * 0.12) * 6.2832) *
    cos((vUv.x - uAnimTime * 0.10) * 6.2832);
float radialPulse = sin((distance(vUv, center) * 5.5 - uAnimTime * 0.18) * 6.2832);
float ribbonWave = sin(((vUv.x * 1.7 + vUv.y * 2.3) - uAnimTime * 0.12) * 6.2832);
uv += beatEase * 0.0110 * vec2(beatWave, -beatWave);
uv += beatEase * 0.0085 * normalize(vUv - center + vec2(1e-4)) * radialPulse;
uv += motionEase * 0.0062 * vec2(ribbonWave, -ribbonWave * 0.75);
uv += uGlobalMotion;
```

以及一行**明确的设计意图注释**（[`#L105-L107`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/assets/shaders/hyper_background_effect.glsl#L105-L107)）：

```glsl
// 透明度保持稳定，避免音频脉冲造成整屏闪烁
color.a = clamp(color.a, 0., 1.);
color.a *= uAlphaMulti;
```

### 3.5 **批判性判断：这算「真反应」吗？**

**是，但有严格边界。** 分三点说清：

1. **振幅 100% 来自真实 PCM。** `uLevelEase` / `uBeatEase` 的唯一输入是 `_level` / `_beat`，
   而它们只由 §2 的 RMS 与 onset 检测产生。**没有任何随机数、没有 `Random`、没有时间驱动的伪特征。**
   `grep -rni "random"` 在 `AudioReactive.kt` / `BgEffectPainter.java` / `hyper_background_effect.glsl` 中零命中。

2. **载波是固定周期的 —— 这是 Ncrust 规则需要警惕的地方。**
   `beatWave` / `radialPulse` / `ribbonWave` 都是 `sin/cos(uAnimTime · 常数)`，
   `uAnimTime` 由 `withFrameNanos` 自由推进（`HyperBackground.kt#L344-L345`），**与音频无关**。
   音频只控制**这些周期波的振幅**。所以它**不是**「鼓点触发一次冲击波」，而是
   「**连续周期波动 × 音频包络**」。按 Ncrust 的措辞「motion must be bound to real audio features, never
   pseudo-random or fixed-period fake reactivity」，NeriPlayer 属于**灰色地带**：
   周期是固定的，但**振幅是真音频**，且静音时振幅严格为 0。
   是否接受这种形态，需要 Ncrust 自己定义边界（建议：允许「音频门控的周期载波」，禁止「音频无关的固定周期动画」）。

3. **静音时确实是静止的（对 GLSL 部分成立）。**
   `uAnimTime` 在 shader 中**仅**出现在被 `beatEase`/`motionEase` 相乘的项里。
   静音 ⇒ `level=0, beat=0` ⇒ `uLevelEase=0, uBeatEase=0, uMotionEase=0` ⇒ 三个位移项全为 0、`uZoom=1`、`uColorPulse=0`。
   这是「真反应」的**有力证据**。
   **但有一个例外**：Java 侧 `updateAnimatedPoints()` 的 `pointOffset = uPointOffset + ...`，
   而 `uPointOffset` 默认 **0.1**（[`BgEffectPainter.java#L78`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L78)），
   所以 5 个色块点**静音时依然以 `sin(uAnimTime + y)*0.1` 缓慢漂移**。
   这是「氛围动效」，不是「假反应」——但它意味着**静音 ≠ 完全静止**。

**关于随机性**：shader 里唯一的「噪声」是确定性 hash 抖动（[`#L38-L41`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/assets/shaders/hyper_background_effect.glsl#L38-L41)、`#L112-L113`）：

```glsl
float gradientNoise(in vec2 uv) {
    return fract(52.9829189 * fract(dot(uv, vec2(0.06711056, 0.00583715))));
}
...
float dither = (gradientNoise(fragCoord.xy) - 0.5) * (5.0 / 255.0);
```

固定幅度 `5/255`、无状态、纯屏幕坐标函数 —— **是抗色带 dither，不是随机动效**。

---

## 4. RuntimeShader

### 4.1 使用点清点

| 文件 | 用途 | 是否与音频相关 | 加载的 shader 资源 |
|---|---|---|---|
| [`BgEffectPainter.java`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java) | 播放页流体背景 | ✅ **是（唯一一处）** | `assets/shaders/hyper_background_effect.glsl` |
| [`AdvancedGlassRenderEffect.kt`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/effect/glass/AdvancedGlassRenderEffect.kt) | 玻璃模糊区域遮罩 | ❌ 否 | `assets/shaders/advanced_glass_region_mask.agsl` |
| [`HyperBackgroundShaderView.kt`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackgroundShaderView.kt) | 把同一个 shader 当 `Paint` 的 shader 用来做渲染一致性测试 | ❌ 否（测试/校验路径） | 复用上面那个 |
| `app/src/androidTest/.../AdvancedGlassSurfaceRenderTest.kt` | 仪器测试中内联小 shader | ❌ 否 | 内联字符串 |

### 4.2 构造与绑定

[`BgEffectPainter.java#L90-L128`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L90-L128)：

```java
public BgEffectPainter(Context context) {
    mContext = context;
    @Language("AGSL") String loadShader = loadShader();
    mBgRuntimeShader = new RuntimeShader(loadShader);            // ← L93
    mBgRuntimeShader.setFloatUniform("uTranslateY", 0.0f);
    mBgRuntimeShader.setFloatUniform("uColors", uColors);
    ...
    updateReactiveUniforms();
    updateGlobalMotionUniform();
    updateAnimatedPoints();
}

public RenderEffect getRenderEffect() {
    return RenderEffect.createRuntimeShaderEffect(mBgRuntimeShader, "uTex");   // ← L127
}
```

资源加载（[`#L272-L284`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L272-L284)）：

```java
try (InputStream is = mContext.getAssets().open("shaders/hyper_background_effect.glsl");
     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) { ... }
```

> 注意命名陷阱：音频反应背景的 AGSL 源码**扩展名是 `.glsl`**（内容实为 AGSL：`vec4 main(vec2 fragCoord)`、`uniform shader uTex`），
> 而玻璃那套用的是 `.agsl`。**两者都是 AGSL**，只是历史命名不统一。

### 4.3 Shader 接口（uniforms）

`hyper_background_effect.glsl` 的完整 uniform 清单（[`#L1-L18`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/assets/shaders/hyper_background_effect.glsl#L1-L18)）：

```glsl
uniform vec2 uResolution;
uniform shader uTex;          // 输入：底层 UI 内容（RenderEffect 的 child）
uniform float uAnimTime;
uniform vec4 uBound;
uniform float uTranslateY;
uniform vec3 uPoints[5];
uniform vec4 uColors[5];
uniform float uAlphaMulti;
uniform float uSaturateOffset;
uniform float uLightOffset;
uniform float uLevelEase;     // ← 音频（level）
uniform float uBeatEase;      // ← 音频（beat）
uniform float uMotionEase;    // ← 音频（level+beat 混合）
uniform float uZoom;          // ← 音频
uniform float uColorPulse;    // ← 音频
uniform vec2 uGlobalMotion;
```

**⚠️ 一处 README 与代码的措辞差异（重要，容易误导）**：
README 说「接入 `uMusicLevel / uBeat` 做音频响应」（[`README.md#L126-L130`](https://github.com/cwuom/NeriPlayer/blob/master/README.md#L126-L130)），
但 `uMusicLevel` / `uBeat` **并不是 shader uniform**，而是 `BgEffectPainter.java` 的 **Java 字段**
（[`#L76-L77`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L76-L77)）。
真正下发给 GPU 的是 `uLevelEase / uBeatEase / uMotionEase / uZoom / uColorPulse`。
本报告以源码为准。

### 4.4 API < 33 的降级路径

**降级策略：完全不做动态背景（透明），UI 上把开关强制关掉。** 不是软件模拟、不是静态渐变。

1. `BgEffectPainter` 整体标注 `@RequiresApi(api = Build.VERSION_CODES.TIRAMISU)`
   （[`#L49`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L49)）。
2. `HyperBackground` 类注释与实现（[`#L133-L156`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L133-L156)）：
   ```kotlin
   /**
    * 渲染 Hyper 背景
    * - Android 13+ (API 33) 启用 RuntimeShader; 低版本自动降级为透明
    * - 通过 withFrameNanos 获取逐帧时间, 驱动 BgEffectPainter
    */
   ...
   val painter = remember(currentIsDark, applicationContext) {
       if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
           BgEffectPainter(applicationContext)
       } else null          // ← 低版本 painter == null
   }
   ```
   `painter == null` 会让后续所有 `LaunchedEffect` 直接 `return@LaunchedEffect`
   （`#L278`、`#L293`、`#L306`），**连音频特征的收集协程都不会启动**。
3. Compose 侧的另一处 `AndroidView` 宿主只是一个透明 `View`（`#L172-L176`：`setBackgroundColor(Color.TRANSPARENT)`），
   在低版本上保持透明即最终视觉。
4. 设置页在 API < 33 时**强制关闭并置灰**相关开关
   （[`SettingsMotionSection.kt#L147`、`#L157-L172`、`#L185-L193`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/screen/tab/settings/component/SettingsMotionSection.kt#L147-L193)）：
   ```kotlin
   val dynamicBackgroundApiAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
   ...
   if (!dynamicBackgroundApiAvailable) {
       if (nowPlayingDynamicBackgroundEnabled) { onNowPlayingDynamicBackgroundEnabledChange(false) }
       if (nowPlayingAudioReactiveEnabled)      { onNowPlayingAudioReactiveEnabledChange(false) }
   }
   ```
   不可用时的后缀文案是 `settings_android13_required`（Android 13 要求）。
5. 玻璃那套有独立的、**可注入的 SDK 判断 + 空实现 session**，降级更优雅
   （[`AdvancedGlassRenderEffect.kt#L12`、`#L23-L24`、`#L58-L75`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/effect/glass/AdvancedGlassRenderEffect.kt#L23-L75)）：
   ```kotlin
   internal const val ADVANCED_GLASS_BACKEND_MIN_SDK = Build.VERSION_CODES.TIRAMISU
   internal fun isAdvancedGlassBackendSupported(sdkInt: Int): Boolean = sdkInt >= ADVANCED_GLASS_BACKEND_MIN_SDK
   ...
   private object UnsupportedAdvancedGlassRenderEffectSession : AdvancedGlassRenderEffectSession {
       override fun update(radiusPx: Float, regions: List<AdvancedGlassRenderRegion>): RenderEffect? = null
   }
   ```
   它刻意把 `sdkInt` 作为**参数**传入（而不是直接读 `Build.VERSION.SDK_INT`），**便于单元测试**——这是个值得抄的设计。

6. 文档侧同样声明了降级契约：
   - [`README.md#L447-L448`](https://github.com/cwuom/NeriPlayer/blob/master/README.md#L447-L448)：`RuntimeShader 动态背景仅在 Android 13+ 启用；封面模糊需要 Android 12+，高级模糊需要 Android 13+，低版本会降级。`
   - [`CONTRIBUTING.md#L364-L365`](https://github.com/cwuom/NeriPlayer/blob/master/CONTRIBUTING.md#L364-L365)：`RuntimeShader 流体/音频响应背景只在 Android 13+ 启用；... 修改动效时必须保留低版本降级路径。`（**明确的 review 约束**）

**SDK 基线**（[`build-logic/convention/src/main/kotlin/Version.kt#L9-L11`](https://github.com/cwuom/NeriPlayer/blob/master/build-logic/convention/src/main/kotlin/Version.kt#L9-L11)）：
`compileSdkVersion = 37`、`minSdk = 28`、`targetSdk = 36`。
即 `minSdk 28` 起就要能跑，**API 28–32 这段占比不小的设备完全没有动态背景**。

### 4.5 未使用的 AGSL 资产（存疑项）

`app/src/main/assets/shaders/` 下有 4 个文件，但**只有 2 个被 Kotlin/Java 引用**：

| 资产 | 是否被引用 |
|---|---|
| `hyper_background_effect.glsl` | ✅ `BgEffectPainter.java#L274` |
| `advanced_glass_region_mask.agsl` | ✅ `AdvancedGlassShaderSource.kt#L8` |
| `advanced_glass_reduced_quality.agsl` | ❌ **全仓库零引用** |
| `advanced_glass_ultra_low_quality.agsl` | ❌ **全仓库零引用** |

（验证命令：`grep -rn "advanced_glass_reduced_quality\|advanced_glass_ultra_low_quality" app/` → 无输出。）
这两个是**上一版区域模糊实现的遗留**（内容是对 `child` 做 5 抽 / 3 抽的十字模糊）。
若 Ncrust 想参考「AGSL 降采样模糊」的写法，它们仍可读，但**上游已不再使用**，不代表当前架构。

---

## 5. 性能手段

### 5.1 逐帧节流（frame clock / cadence gating）

[`HyperBackground.kt#L67-L69`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L67-L69)：

```kotlin
private const val DynamicBackgroundSteadyFrameIntervalNs = 1_000_000_000L / 45L   // 稳态 45 fps
private const val DynamicBackgroundBoostFrameIntervalNs  = 1_000_000_000L / 60L   // 提速 60 fps
private const val DynamicBackgroundBoostDurationNs       = 900_000_000L           // 提速持续 900 ms
```

调度逻辑（[`#L328-L345`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L328-L345)）：

```kotlin
withFrameNanos { t ->
    val frameIntervalNs = if (t < latestBoostedAnimationUntilNs) DynamicBackgroundBoostFrameIntervalNs
                          else DynamicBackgroundSteadyFrameIntervalNs
    if (nextRenderNs != Long.MIN_VALUE && t < nextRenderNs) return@withFrameNanos
    nextRenderNs = nextDynamicBackgroundRenderNs(t, nextRenderNs, frameIntervalNs)
    ...
}
```

`nextDynamicBackgroundRenderNs`（[`#L453-L467`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L453-L467)）
用**累加而非重置**的方式对齐节拍，并在长时间卡顿后自我复位：

```kotlin
if (currentNextNs == Long.MIN_VALUE || frameNs - currentNextNs > intervalNs * 2L) {
    return frameNs + intervalNs          // 停顿后重新起拍，避免追赶式补帧
}
var nextNs = currentNextNs
while (nextNs <= frameNs) { nextNs += intervalNs }
return nextNs
```

有单元测试覆盖（[`HyperBackgroundTest.kt#L24-L51`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/test/java/moe/ouom/neriplayer/ui/view/HyperBackgroundTest.kt#L24-L51)：`render schedule keeps cadence and resets after a long stall`）。

**⚡ 提速（boost）是由「封面切换」触发的，不是由音频触发的**：
`boostedAnimationUntilNs = System.nanoTime() + DynamicBackgroundBoostDurationNs`
（[`#L226-L228`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L226-L228)）。**不要把它误解为「音乐响时提帧」。**

### 5.2 生命周期与可见性

- 整段逐帧循环包在 `repeatOnLifecycle(Lifecycle.State.RESUMED)` 里（[`#L307`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L307)）——**后台立即停止**。
- `AudioReactive.enabled` 同样受 `lifecycleResumed` 与 `showNowPlaying` 门控（§1.2(h)）。
- 采样侧 `if (!enabled || !buffer.hasRemaining()) return` 提前返回（`AudioReactive.kt#L117`）。

### 5.3 脏标记（dirty flag）避免冗余 uniform 上传

[`BgEffectPainter.java#L106-L124`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L106-L124)：

```java
public void setReactive(float level, float beat) {
    float boundedLevel = Math.max(0f, Math.min(1f, level));
    float boundedBeat  = Math.max(0f, Math.min(1f, beat));
    if (Float.compare(uMusicLevel, boundedLevel) == 0 && Float.compare(uBeat, boundedBeat) == 0) {
        return;                                    // 值未变 → 不置脏
    }
    uMusicLevel = boundedLevel; uBeat = boundedBeat;
    reactiveUniformsDirty = true;
}

public void updateMaterials() {
    mBgRuntimeShader.setFloatUniform("uAnimTime", uAnimTime);   // 每帧必写
    if (reactiveUniformsDirty) { updateReactiveUniforms(); }   // 仅变化时写 5 个
    updateGlobalMotionUniform();
    updateAnimatedPoints();
}
```

稳态下每帧的 `setFloatUniform` 次数约为 **3 次**（`uAnimTime`、`uPoints`(15 float)、`uGlobalMotion`），
反应式那 5 个仅在数值变化时写入。这是一个**成本很低但有效**的优化。

### 5.4 定长数组 / 复用缓冲

- `private static final int POINT_COUNT = 5; private static final int POINT_STRIDE = 3;`
  与 `uAnimatedPoints = new float[POINT_COUNT * POINT_STRIDE]` —— **一次分配、每帧原地覆写**
  （[`BgEffectPainter.java#L52-L53`、`#L86`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L52-L86)）。
- 调色板过渡用**预分配的 `FloatArray` + `into` 变体**避免每帧分配
  （[`HyperBackground.kt#L417-L418`、`#L669-L679`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L669-L679)：`lerpFloatArrayInto(start, stop, fraction, result)`）。
- 玻璃遮罩同理：`FloatArray(ADVANCED_GLASS_MAX_REGIONS * 4)`（32×4）在 region 数量上限内预分配
  （[`AdvancedGlassRenderEffect.kt#L122-L123`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/effect/glass/AdvancedGlassRenderEffect.kt#L122-L123)）。
- RMS 侧：`buf.duplicate()`（每次缓冲 1 个小 `ByteBuffer` 对象），**逐样本零分配、零临时数组**（`AudioReactive.kt#L183-L262`）。

### 5.5 缓存 / 降级路径

**音频反应特效本身几乎没有缓存**，因为它只有一个 shader + 少量 uniform。缓存主要出现在**玻璃模糊**这条独立链路（非音频相关，但同属「动效性能」范畴，可借鉴）：

- **Blur `RenderEffect` 按半径缓存**（[`AdvancedGlassRenderEffect.kt#L105-L106`、`#L146-L154`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/effect/glass/AdvancedGlassRenderEffect.kt#L146-L154)）：
  `cachedBlurEffect?.takeIf { cachedBlurRadiusPx == radiusPx } ?: createBlurEffect(...)`。
- **局部渲染 + 动态下采样**（[`AdvancedGlassRenderProfile.kt#L28-L55`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/effect/glass/AdvancedGlassRenderProfile.kt#L28-L55)）：
  半径 ≥18 px 用 2× 下采样，≥48 px 用 4× 下采样；`Low`/`UltraLow` 档走 `RegionLocal` 管线并限制合并输入面积比（1.08 / 1.20）。
- **局部模糊帧缓存 + RenderNode 记录**（[`AdvancedGlassLocalBlurRenderer.kt#L52`、`#L117-L136`、`#L153-L178`、`#L211-L260`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/effect/glass/AdvancedGlassLocalBlurRenderer.kt#L117-L178)）。
- **会话健康度降级**：`AdvancedGlassController.afterBackendFailure()` 会翻掉 `sessionHealthy`（[`AdvancedGlassController.kt#L19-L47`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/effect/glass/AdvancedGlassController.kt#L19-L47)）。
- **按设备降级**：README 称「天玑设备在未保存偏好时默认超低」（[`README.md#L435-L440`](https://github.com/cwuom/NeriPlayer/blob/master/README.md#L435-L440)）——**设备白/黑名单代码未逐行核实，标「未核实」**。

### 5.6 精度 / 长时运行保护

[`HyperBackground.kt#L343-L345`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L343-L345)：

```kotlin
if (startNs == 0L) startNs = t
val seconds = ((t - startNs) / 1_000_000_000.0).toFloat()
painter.setAnimTime(seconds % 62.831852f)
```

`62.831852 ≈ 20π`。把 `uAnimTime` **对 20π 取模**：一方面所有 `sin/cos(·)` 相位连续（周期是 2π 的整数倍，不会跳变），
另一方面避免长时间播放后 float 精度退化导致 shader 抖动。**这是个很值得抄的小技巧。**

### 5.7 分配与绘制循环的问题（批判）

**`getRenderEffect()` 每帧都会新建一个 `RenderEffect`**（[`BgEffectPainter.java#L126-L128`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L126-L128)），
而调用点在逐帧循环内（[`HyperBackground.kt#L368`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L368)：`v.setRenderEffect(painter.renderEffect)`）。
对比玻璃那条链路**专门做了 `RenderEffect` 缓存**（§5.5），可见这是流体背景侧**尚未优化的一处每帧分配**。
`RenderEffect` 是轻量 native 句柄包装，实测影响可能很小（**未核实，无 profiler 数据**），但严格来说违反「draw loop 零分配」。

### 5.8 文档化的帧时间数字

**没有。** 对 `README.md` / `README_EN.md` / `CONTRIBUTING.md` / `CONTRIBUTING_EN.md` 执行
`grep -rni "帧率|frame time|fps|掉帧|jank|耗时"` → **零命中**。
性能证据只有 Android 仪器测试（像素正确性，非性能）：

- [`BgEffectPainterRenderTest.kt`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/androidTest/java/moe/ouom/neriplayer/ui/view/BgEffectPainterRenderTest.kt)：
  用 `captureToImage()` + 逐像素比对，断言
  `"direct shader differs from RenderEffect"`、`"RuntimeShader uniforms did not update through Canvas drawing"`、
  `"HyperBackground rendered transparent or black"`（#L97-L152）。**这些是正确性回归测试，不是 benchmark。**
- [`AdvancedGlassSurfaceRenderTest.kt`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/androidTest/java/moe/ouom/neriplayer/ui/effect/glass/AdvancedGlassSurfaceRenderTest.kt)。

**结论：任何「NeriPlayer 动效性能如何」的量化说法都必须标「未核实」。**

---

## 6. 许可

### 6.1 根许可：GPL-3.0（已核实）

- `LICENSE` = **GNU GPL Version 3, 29 June 2007 完整原文**，35149 字节，674 行，
  `md5 = 1ebbd3e34237af26da5dc08a4e440464`。
  来源：<https://github.com/cwuom/NeriPlayer/blob/master/LICENSE>（本地克隆逐字节一致）
- README 明示（[`README.md#L995`](https://github.com/cwuom/NeriPlayer/blob/master/README.md#L995)）：
  > `NeriPlayer 使用 **GPL-3.0** 开源许可证发布。`
  英文版一致（[`README_EN.md#L1232`](https://github.com/cwuom/NeriPlayer/blob/master/README_EN.md#L1232)：`NeriPlayer is released under **GPL-3.0**.`）

### 6.2 源文件头：GPL-3.0-**or-later**（已核实，但覆盖面有限）

213 个 `app/src/main` 下的 `.kt`/`.java` 文件带标准 GPL 头（`app/src/main` 下 `.kt`+`.java` 总计 1038 个），
头部措辞是 **"either version 3 of the License, or (at your option) any later version"**，即 **GPL-3.0-or-later**。
样例：[`AudioReactive.kt#L5-L26`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L5-L26)。

> 「or later」对想并入 GPLv3 项目的 Ncrust **完全友好**：既可按 v3 使用，也可按 v3-or-later 使用。

### 6.3 可搬运性结论（GPLv3 ⇄ GPLv3）

| 判断 | 结论 |
|---|---|
| GPL-3.0 代码能否并入 GPLv3 项目？ | ✅ **可以**（同许可，且 `or-later` 更宽松） |
| 是否需要保留版权与许可声明？ | ✅ **必须**（GPLv3 §5） |
| 是否有网络分发（AGPL 式）附加义务？ | NeriPlayer 根许可是 GPL 而非 AGPL，**无**网络条款 |
| 是否有额外限制（§7 additional terms）？ | 见 §6.4 的 native 附加授权与 HyperCeiler 疑点 |

### 6.4 🚨 三个必须法务核实的点

**（1）`BgEffectPainter.java` 疑似源自 AGPL-3.0 项目 HyperCeiler —— 最高风险项**

已核实事实：
- 文件头写着（[`BgEffectPainter.java#L25`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L25)）：
  ```java
  * ! Reference: https://github.com/ReChronoRain/HyperCeiler !
  ```
- **HyperCeiler 的 LICENSE 是 AGPL-3.0**（不是 GPL-3.0）。我已直接抓取并确认文件头：
  `GNU AFFERO GENERAL PUBLIC LICENSE / Version 3, 19 November 2007`，34523 字节。
  来源：<https://github.com/ReChronoRain/HyperCeiler/blob/main/LICENSE>（raw: <https://raw.githubusercontent.com/ReChronoRain/HyperCeiler/main/LICENSE>）
- 全仓库只有这**一处** `Reference:` 第三方项目署名（`grep -rn "Reference:" app/src/main` 仅此 1 命中）。

**未核实 / 不能断言的部分**：
- 「`Reference:` 是**借鉴**还是**复制**」——本报告**无法判定**，也**不指控任何一方侵权**。
- 尝试在 HyperCeiler 仓库中检索同名 shader 资产（`app/src/main/assets/shaders/hyper_background_effect.glsl` 等 3 条路径）均返回 **HTTP 404**，
  即**未找到**直接对应的上游文件；这既不能证明有复制，也不能证明没有。

**对 Ncrust 的可执行建议**：
> ⛔ **不要直接复制 `BgEffectPainter.java`**（以及可能同源的 `hyper_background_effect.glsl`）。
> AGPL-3.0 的传染性远强于 GPL-3.0（含网络服务条款），一旦 Ncrust 是 GPLv3 项目而引入了 AGPL 代码，
> 就会产生不可调和的许可冲突。若确实需要，请**独立重写**（本报告的 §3 公式表已给出全部数学关系，足以支撑 clean-room 重写），
> 并做正式的法律审查。

**（2）Native 代码有「附加授权」的双授权安排（已核实）**

[`app/src/main/cpp/README.md`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/cpp/README.md) 声明：
对列出的 NeriPlayer 自有 native 路径（`crash/`、`usb/exclusive/`、`usb/feedback/`、`usb/iso/`、`usb/pcm/`、`usb/uac1/`、`usb/uac2/` 等），
除根 GPL-3.0 外**额外**授予一份 **"NeriPlayer Native Attribution License"**，允许闭源/商业使用。

- 该附加授权**只覆盖 native 目录**，明确排除 Kotlin / Java / Android 资源 / 脚本 / 构建产物：
  > `This alternative license does not automatically apply to Kotlin, Java, Android resources, scripts or tools outside this directory...`
- 第三方源码不受影响，例如 `libusb/` 继续遵循 **LGPL-2.1-or-later**（同文件声明）。
- **对 Ncrust 的影响**：这份附加授权是**额外的宽松选项**，**不会**削弱 GPL-3.0 分支；按 GPLv3 使用这些 native 代码依然成立。
  但搬运 `libusb/` 等第三方代码时必须保留原始许可声明（LGPL-2.1-or-later）。

**（3）子模块许可未核实（盲区）**

`.gitmodules` 声明的 4 个子模块指向**独立仓库**，各有自己的许可，需要单独核实：

| 子模块路径 | 仓库 |
|---|---|
| `np-submodule/NeriPlayer-LTW` | <https://github.com/TheSmallHanCat/NeriPlayer-LTW> |
| `np-submodule/accompanist-lyrics-ui` | <https://github.com/cwuom/accompanist-lyrics-ui> |
| `np-submodule/accompanist-lyrics-core` | <https://github.com/cwuom/accompanist-lyrics-core> |
| `np-submodule/miuix` | <https://github.com/cwuom/miuix> |

来源：[`.gitmodules`](https://github.com/cwuom/NeriPlayer/blob/master/.gitmodules)（本地克隆内容一致）。
本次浅克隆中它们**全为空目录**，**许可「未核实」**。
若 Ncrust 要用到歌词 UI / miuix 主题组件，**必须**单独打开这些仓库确认许可。

---

## 7. 未核实 / 存疑项

按「影响程度」排序。**下列每一条都不应被当作事实引用。**

| # | 事项 | 状态 | 说明 |
|---|---|---|---|
| 1 | `BgEffectPainter.java` 是否为 HyperCeiler 衍生物；能否合法搬运 | **未核实（高风险）** | 已核实「文件头署名 HyperCeiler」+「HyperCeiler 是 AGPL-3.0」；**未能**判定借鉴 vs 复制。未在 HyperCeiler 找到同名 shader（3 条路径均 404）。需法务判断 |
| 2 | 子模块（miuix / accompanist-lyrics-* / NeriPlayer-LTW）的许可 | **未核实** | 浅克隆中为空目录；未单独抓取 |
| 3 | `hyper_background_effect.glsl` 是否同样源自 HyperCeiler | **未核实** | 该文件**没有**任何第三方署名头；但也因此无法确认独立性 |
| 4 | 任何帧率 / 帧时间 / CPU 占用的量化数字 | **未核实（源码中不存在）** | 文档中 `fps|帧率|frame time|jank` 零命中；仓库无 benchmark |
| 5 | 实际听感上 `beat` 是否踩在真正的鼓点上 | **未核实** | 仅有启发式算法（双 EMA + 3× 噪声地板 + 120 ms 不应期），**没有**节拍跟踪（beat tracking）、没有 BPM 估计、没有相位对齐。对 4/4 电子鼓点大概率有效，对古典/人声/弱起音曲目**可能误触发或漏触发** |
| 6 | 音画同步延迟（视觉滞后多少毫秒） | **未核实** | 链路：PCM 分流 → 播放线程 → StateFlow → 主线程 collect → 下一帧 `withFrameNanos` 应用。**结构上至少有 1 帧（≈16.7–22 ms）延迟**，但未做实测 |
| 7 | 「32-bit 高解析输出会旁路音频可视化」在代码层面是否成立 | **部分核实** | README 明确这样说（[`README.md#L904`](https://github.com/cwuom/NeriPlayer/blob/master/README.md#L904)）；`requiresPcmAudioProcessing` 里确实有 `highResolutionOutputEnabled` 分支（[`PlaybackAudioOffloadPolicy.kt#L31`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/policy/offload/PlaybackAudioOffloadPolicy.kt#L31)），但**「是否真的跳过 tee」这条具体路径未逐行追完**。**注意矛盾**：该分支与 `audioReactiveActive` 同为「需要 PCM 处理」，逻辑上不必然绕过 tee —— **以代码为准的建议：Ncrust 别照抄这条结论** |
| 8 | 「天玑设备默认超低模糊」的设备判定代码 | **未核实** | 仅 README 声明（[`README.md#L435-L440`](https://github.com/cwuom/NeriPlayer/blob/master/README.md#L435-L440)），未定位到具体 SoC 判定实现 |
| 9 | README 中 `uMusicLevel / uBeat` 是 shader uniform 的说法 | **❌ 与源码不符（已纠正）** | 它们是 Java 字段，不是 uniform。真正下发的是 `uLevelEase/uBeatEase/uMotionEase/uZoom/uColorPulse`。见 §4.3 |
| 10 | `advanced_glass_reduced_quality.agsl` / `advanced_glass_ultra_low_quality.agsl` 的用途 | **已核实为「当前无引用」** | 但**为何保留**（是否有动态加载 / 反射 / 构建期选择）未核实；`grep` 在 `app/` 全域零引用 |
| 11 | 动态背景在低端机上的实际可用性 | **未核实** | 无性能数据；但有硬降级（API<33 直接没有）与 45 fps 节流作为间接证据 |
| 12 | 屏幕截图 / 视频里的视觉效果是否与 shader 一致 | **未核实** | 本报告**没有**查看任何截图或演示视频，全部结论来自源码 |

---

## 8. 对 Ncrust 的可借鉴点

> Ncrust 的两条硬规则：
> **(R1)** 「no real FFT unless research proves the data source is reliable and performance is controllable」
> **(R2)** 「motion must be bound to real audio features, never pseudo-random or fixed-period fake reactivity」

### 8.1 直接回答 R1：NeriPlayer 的证据支持「先不做 FFT」

| 问题 | NeriPlayer 给出的事实 | 对 Ncrust 的含义 |
|---|---|---|
| 数据源可靠吗？ | ✅ **可靠**。`TeeAudioProcessor` 拿到的是**你自己解码、经响度均衡/声道平衡处理后、送进 AudioTrack 之前**的 PCM（[`ReactiveRenderersFactory.kt#L131-L133`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/engine/ReactiveRenderersFactory.kt#L131-L133)） | 若 Ncrust 也走 Media3，「数据源可靠性」这一半 R1 条件**已满足**；比 `Visualizer`（需要录音权限、有系统级延迟、部分机型不可用）和麦克风回采（完全不可靠）都强 |
| 性能可控吗？ | ⚠️ **计算量可控（RMS 是 O(n) 逐样本，可忽略），但管线代价不可忽略**：开反应式 ⇒ `AUDIO_OFFLOAD_MODE_DISABLED` ⇒ **整机功耗上升**（[`PlaybackAudioOffloadPolicy.kt#L32`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/policy/offload/PlaybackAudioOffloadPolicy.kt#L32)） | **FFT 的增量成本主要不是 CPU，而是「你已经为了采样关掉了 offload」**。既然代价已经付了，在同一个 tap 上多算一个 1024 点 FFT 的边际成本其实不大 |
| 需要 FFT 吗？ | ❌ **NeriPlayer 明确不需要**：853 个源文件里**一行 FFT 都没有**，全靠 `level`+`beat` 两个标量做出完整视觉 | **最省事的路线：先用 RMS+onset 做出 v1，把 FFT 留到有明确「频段→视觉」需求时再引入。** 这与 R1 的保守立场一致 |

**建议的落点**：把 `TeeAudioProcessor` 视为**经过验证的、可靠的 PCM 分流点**（R1 的「数据源可靠」成立），
但把「是否 FFT」与「是否关闭 offload」解耦决策——**先量化 offload 关闭对续航的影响**，再决定值不值得在同一个 tap 上跑 FFT。

### 8.2 直接回答 R2：哪些能抄，哪些不能抄

**✅ 可以放心借鉴（真反应）**

1. **双 EMA + 自适应噪声地板 + 不应期** 的 onset 检测器（[`AudioReactive.kt#L133-L150`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/core/player/effects/AudioReactive.kt#L133-L150)）。
   代码短、无依赖、**有单元测试**、参数有物理含义（120 ms ≈ 500 BPM 上限；0.90/16.67 ms 是指数衰减时间常数 ≈ 158 ms）。
   **这是本仓库最值得原样重写（clean-room）移植的一段。**
2. **两层非对称 one-pole 平滑**：检测器内 `aFast=0.5 / aSlow=0.05`，UI 层 `attack 0.12 / release 0.045`（level）、`0.46 / 0.12`（beat）。
   真实项目里常见的 bug 是只有一层平滑导致「要么抖要么拖」，NeriPlayer 的两层结构值得照搬。
3. **`pendingBeatPeak` 峰值保持**：解决「音频线程高频产出 vs UI 帧低频消费」的采样丢失问题（[`HyperBackground.kt#L324`、`#L348-L349`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L324-L349)）。**这是真实工程细节，不是装饰。**
4. **`uAnimTime % 20π`** 的相位回绕（[`HyperBackground.kt#L345`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L345)）——长时播放精度保护，零成本。
5. **`withFrameNanos` + 间隔累加 + 长停顿复位** 的帧率节流器（[`HyperBackground.kt#L453-L467`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L453-L467)），45 fps 稳态 / 60 fps 事件提速，**有单测**。
6. **把 `sdkInt` 作为参数注入**而非直接读 `Build.VERSION.SDK_INT`，并用空对象（`UnsupportedAdvancedGlassRenderEffectSession`）做降级（[`AdvancedGlassRenderEffect.kt#L58-L75`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/effect/glass/AdvancedGlassRenderEffect.kt#L58-L75)）——**可测试的降级设计**。
7. **dirty flag 跳过未变化的 uniform**（[`BgEffectPainter.java#L106-L124`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L106-L124)）。
8. **不把 alpha 绑到音频**（[`hyper_background_effect.glsl#L105-L107`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/assets/shaders/hyper_background_effect.glsl#L105-L107)）——避免整屏闪烁，**这是经验性的可读性/可用性决策**。

**⚠️ 需要 Ncrust 自己划边界（灰色地带）**

9. **「音频包络 × 固定周期载波」是否算「真反应」**：NeriPlayer 的 `beatWave/radialPulse/ribbonWave` 是 `sin(uAnimTime·k)`。
   - 支持它的理由：**振幅 100% 来自 PCM**，静音时严格归零 ⇒ 不是"假反应"。
   - 反对它的理由：周期恒定 ⇒ 视觉节奏不会跟随音乐速度变化；一首 60 BPM 的慢歌和 160 BPM 的快歌，**波形的"形状"是一样的**，只是幅度不同。
   - **建议 Ncrust 明确写进规范**：允许「音频门控的周期载波」，**禁止**「振幅与音频无关的周期载波」。并为前者加一条**可测的验收判据**：*静音输入下，视觉输出必须收敛到静止（或明确的 idle 基线）*。NeriPlayer 的 GLSL 部分满足这条判据，Java 侧的色块漂移（`uPointOffset=0.1`）**不满足**。
10. **不要照抄「静音完全静止」的结论**：由于 `uPointOffset = 0.1` 是常量偏移，静音时 5 个色块**仍在缓慢漂移**（[`BgEffectPainter.java#L78`、`#L239-L261`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L239-L261)）。

**❌ 不要借鉴 / 要改进**

11. **`getRenderEffect()` 每帧新建 `RenderEffect`**（[`BgEffectPainter.java#L126-L128`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/BgEffectPainter.java#L126-L128) + [`HyperBackground.kt#L368`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L368)）。
    Ncrust 应**缓存 RenderEffect**，只在 uniform 变化时更新——同一个仓库的玻璃链路已经这么做了（[`AdvancedGlassRenderEffect.kt#L146-L154`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/effect/glass/AdvancedGlassRenderEffect.kt#L146-L154)），可作对照。
12. **`WaveformSlider` 是纯装饰，不要误当成反应式组件**：
    [`WaveformSlider.kt#L60-L62`、`#L113-L119`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/component/playback/WaveformSlider.kt#L113-L119) 里振幅是常量 `WAVE_AMPLITUDE = 6f`，仅由 `isPlaying` 开关，**与音频无关**：
    ```kotlin
    private const val WAVE_AMPLITUDE = 6f      // 波浪的振幅
    private const val WAVE_FREQUENCY = 0.08f   // 波浪的频率
    ...
    val animatedAmplitude by animateFloatAsState(
        targetValue = if (enabled && isPlaying && !isPlaybackWaiting && !isDragging) WAVE_AMPLITUDE else 0f,
        animationSpec = tween(durationMillis = 500, easing = LinearEasing), label = "amplitude_animation")
    ```
    这正是 R2 要禁止的「固定周期假反应」形态——**NeriPlayer 里也存在这种组件，说明不能靠"这个 App 有反应式效果"来推断每个动画都是真的**。Ncrust 若要做波形进度条，必须把它接到真实 `level`。
13. **API < 33 直接"什么都不显示"过于激进**。NeriPlayer 的选择是 `painter = null` ⇒ 背景透明（[`HyperBackground.kt#L152-L156`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/main/java/moe/ouom/neriplayer/ui/view/HyperBackground.kt#L152-L156)）。
    **Ncrust 的 minSdk 是 24**（见 `Ncrust/CLAUDE.md` 工具链表），比 NeriPlayer 的 28 还低，API 24–32 的覆盖面更大 ——
    可考虑**静态渐变兜底**（用同一份封面取色，只是不做流体），体验更连续、成本近零。
14. **没有 FFT ⇒ 没有「频段差异化的视觉」**。如果 Ncrust 的产品需求里明确有「低频驱动粒子/高频驱动亮度」这类差异化效果，**NeriPlayer 无法作为参考**，必须自己引入 FFT 或滤波器组。

### 8.3 落地清单（可直接转成任务）

- [ ] **复用 PCM tap 方案**：`TeeAudioProcessor(AudioBufferSink)` 插进 `DefaultAudioSink` 的 `setAudioProcessors`；`flush()` 里读真实格式；`handleBuffer()` 里早期 return 做零开销关闭。
- [ ] **clean-room 重写 onset 检测器**（双 EMA + 自适应噪声地板 + 不应期 + 时间基衰减），配套单测：`disabled 时无输出`、`衰减按时间而非缓冲计数`、`音量增益按比例缩放`（参考 [`AudioReactiveTest.kt`](https://github.com/cwuom/NeriPlayer/blob/master/app/src/test/java/moe/ouom/neriplayer/core/player/AudioReactiveTest.kt)）。
- [ ] **明确 offload 取舍**：把「开反应式 ⇒ 关闭 audio offload」写进设置页文案（NeriPlayer 只写在 policy 里，用户不可见；**这是可以做得更好的地方**）。
- [ ] **两层平滑 + 峰值保持**，参数从 `0.12/0.045`、`0.46/0.12` 起调。
- [ ] **RenderEffect 缓存**（不要每帧新建）。
- [ ] **帧率节流器 + 相位回绕**（45 fps / 60 fps、`% 20π`）。
- [ ] **把「静音 ⇒ 视觉静止」做成自动化测试**（喂 0 值 PCM，断言所有音频驱动量收敛到 0）。这是 R2 唯一可机器验证的判据。
- [ ] **许可排查**：凡是要从 NeriPlayer 搬的**.kt/.java 文件**，逐文件确认头部许可；**`BgEffectPainter.java` 与 `hyper_background_effect.glsl` 建议 clean-room 重写**（§6.4）。

---

*报告完。全部代码引用均指向 `cwuom/NeriPlayer@master`（提交 `db85bf6`）；Media3 引用指向 `androidx/media@1.10.1`。*
