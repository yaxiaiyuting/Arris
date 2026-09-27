# v3.0.0 探针证据索引（EVIDENCE）

> 本文件是 v3.0.0 探针阶段的**原始输出**索引：每条都是命令 + 原样抄录的输出，不做二次加工。
> 采集日期：2026-09-27。设备与构建：debug 变体（探针只测**数据通路与纯算术**，
> 不测帧时间 —— 帧时间一律用 release 包，见 `../verification/`）。

---

## 0. 设备与工具链

| 设备 | 型号 | Android | 用途 |
|---|---|---|---|
| `0715f763f54c023a` | SM-G9209（S6） | 7.0（API 24） | 低端基线、帧时间、降级（**本轮降级机制已删**） |
| `3B15CD00GB700000` | PLC110 | 16（API 36） | 高端基线、帧时间 |
| `WVQ6R22124000968` | WGR-W09（华为平板） | 12 | 横屏动效（本轮锁屏无 root，见 §3 的失败记录） |

```
$ /home/duanjb666/Android/sdk/platform-tools/adb devices -l
0715f763f54c023a       device usb:3-1 product:zerofltectc model:SM_G9209 device:zerofltectc transport_id:3
3B15CD00GB700000       device usb:6-1 product:PLC110 model:PLC110 device:OP60EDL1 transport_id:1
WVQ6R22124000968       device usb:4-2 product:WGR-W09 model:WGR_W09 device:HWWGR transport_id:2
```

---

## 1. `PROBE-AUDIO-TAP` —— 音频旁路的缓冲粒度（**本轮最重要的一条实测**）

**探针**：`app/src/androidTest/java/com/takahashirinta/ncrust/probe/AudioTapProbeTest.kt`
（自建 44.1k/48k 立体声 16bit WAV 写进 cache，用挂了同一个 `TeeAudioProcessor` 的
真实 `ExoPlayer` 播放；sink 里用**预分配 `IntArray` 直方图**统计帧数，零分配、不加锁；播完 dump 一次）。

**命令**：
```
$ ANDROID_SERIAL=<serial> ./gw.sh :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.takahashirinta.ncrust.probe.AudioTapProbeTest
```

**S6（SM-G9209 / Android 7.0）原样输出**：
```
09-27 23:24:02.943 I NcrustAudioTapProbe: PROBE-AUDIO-TAP label=44100-2ch requested=44100Hz/2ch actual=44100Hz/2ch/enc=2 callbacks=44 empty=0 avgFrames=4410.0 avgBufferMs=100.00 callbackRateHz=11.0 elapsedS=4.1 hist={[4352..4607]=44 }
09-27 23:24:07.266 I NcrustAudioTapProbe: PROBE-AUDIO-TAP label=48000-2ch requested=48000Hz/2ch actual=48000Hz/2ch/enc=2 callbacks=45 empty=0 avgFrames=4800.0 avgBufferMs=100.00 callbackRateHz=11.0 elapsedS=4.1 hist={[4608..4863]=45 }
```

**PCL110（Android 16）原样输出**：
```
09-27 23:28:46.247 I NcrustAudioTapProbe: PROBE-AUDIO-TAP label=44100-2ch requested=44100Hz/2ch actual=44100Hz/2ch/enc=2 callbacks=46 empty=0 avgFrames=4410.0 avgBufferMs=100.00 callbackRateHz=11.5 elapsedS=4.1 hist={[4352..4607]=46 }
09-27 23:28:50.438 I NcrustAudioTapProbe: PROBE-AUDIO-TAP label=48000-2ch requested=48000Hz/2ch actual=48000Hz/2ch/enc=2 callbacks=46 empty=0 avgFrames=4800.0 avgBufferMs=100.00 callbackRateHz=11.5 elapsedS=4.1 hist={[4608..4863]=46 }
```

**怎么读**：
- 两台设备、两种采样率下的**平均缓冲时长都是 100.00ms**（4410 / 4800 帧），**回调率 11~11.5 Hz**；
- 直方图只有一个桶 ⇒ 粒度**高度稳定**，不是抖动的；
- 也就是说 `TeeAudioProcessor` 确实原样透传解码器的输出块，而那个块**远粗于**
  v2.9.0 注释里假定的「几十 Hz」。
- **这条数字直接改变了设计**：瞬态判据不能只按"一个缓冲一次"结算（10ms 的击打会被
  100ms 的整缓冲 RMS 稀释 10 倍能量、√10 ≈ 3.2 倍 RMS ⇒ 弱击打直接跌到门槛之下），
  于是有了 `AudioFeatureExtractor.SUB_FRAME_MS = 10.0` 的**子帧结算**。

---

## 2. `PROBE-FEATURE-COST` —— 特征提取的纯算术开销

**同一条探针的第二个用例**：4096 帧立体声缓冲跑 4000 次 `process`，量墙钟时间。
判据是**实时倍率**（一次 process 覆盖的音频时长 ÷ 它花掉的 CPU 时间）。

**S6（Android 7.0）**：
```
09-27 23:23:58.667 I NcrustAudioTapProbe: PROBE-FEATURE-COST frames=4096 ch=2 perBufferUs=574.19 audioMsPerBuffer=92.88 realtimeFactor=162x iterations=4000
```

**PCL110（Android 16）**：
```
09-27 23:28:41.941 I NcrustAudioTapProbe: PROBE-FEATURE-COST frames=4096 ch=2 perBufferUs=414.90 audioMsPerBuffer=92.88 realtimeFactor=224x iterations=4000
```

**怎么读**：
- 最慢的验收机（S6）上，**162 倍实时**余量；4096 帧立体声只要 574 µs。
- 这条数字**只覆盖当前的「两个一阶低通」链路**（每样本约 6 乘 10 加减）。
  **它不能用来给真 FFT 背书** —— FFT 还要分帧、窗函数、以及一条 PCM 环（重做数据层），
  那部分的代价**本轮没有测**。铁律 28 的判决不因此改变（见 `../ref-research/RECOMMENDATIONS.md` §6）。
- **【未验证】**：真机播放下的欠载率（`dumpsys media.audio_flinger` 的 underrun 计数）未采集；
  上面量的是纯算术，不含调度抖动。

---

## 3. 未取证的项（**不许含糊**）

| 项 | 状态 | 原因 |
|---|---|---|
| WGR-W09（华为平板）横屏动效与帧时间 | ❌ **未做** | 设备**锁屏且无 root**（`which su` 返回空），既无法驱动 UI 也无法注入 prefs。与 v2.9.0 的结论一致。 |
| 真机播放下的音频线程欠载率 | ❌ 未采集 | 需要 `dumpsys media.audio_flinger` 的 underrun 计数在长播放下取样；本轮探针只量纯算术。 |
| 6 声道 FLAC（QQ 臻品档）端到端 | ❌ 未做 | 需要真实账号 + 臻品档曲目；纯数学有单测覆盖（`PcmBassAnalysisTest.六声道的低频读数与单声道一致`、`AudioFeatureExtractorTest.六声道不会把截止频率乘六`）。 |
| 音画同步延迟（ms 级） | ❌ 未量化 | §1 给出的是**结构性上界**（发布仍是每缓冲一次 ⇒ 最多滞后一个缓冲 + 一帧 ≈ 100~133ms），不是实测的对齐误差。 |
| `ONSET_PEAK_RATIO` / `ONSET_COOLDOWN_MS` 的曲风矩阵 | ❌ 未做 | 合成轨道 + 文献量级是全部依据。 |

---

## 4. 复现方式（三步）

```bash
# 1) 设备（S6 或 PCL110；两者都有 root）
ADB=/home/duanjb666/Android/sdk/platform-tools/adb
$ADB devices -l

# 2) 跑探针（debug 变体；会安装 debug 包，与已装的 release 包签名冲突时先备份 prefs 再卸载）
cd /home/duanjb666/deepseek
ANDROID_SERIAL=<serial> ./gw.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.takahashirinta.ncrust.probe.AudioTapProbeTest

# 3) 读原始输出（每个用例一份 logcat）
ls "app/build/outputs/androidTest-results/connected/debug/<device>/" | grep logcat
grep "PROBE-" "app/build/outputs/androidTest-results/connected/debug/<device>"/logcat-*probe*.txt | sort -u
```

**PCL110 的 prefs 备份**（跑探针前必须先卸 release 包）：
```
$ adb -s 3B15CD00GB700000 shell "su -c 'tar czf /sdcard/ncrust-prefs.tgz -C /data/data/com.takahashirinta.ncrust shared_prefs'"
$ adb -s 3B15CD00GB700000 pull /sdcard/ncrust-prefs.tgz /home/duanjb666/deepseek/.scratch/pcl-backup/
```
