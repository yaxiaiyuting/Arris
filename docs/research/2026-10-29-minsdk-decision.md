# ADR：minSdk 24 → 29（Android 7.0 → Android 10）

- 日期：2026-10-29
- 状态：**已落地**。`minSdk = 29`、`versionName = 3.8.0-gpl`、`versionCode = 120`（2026-10-29 实测 APK：`minSdkVersion:'29'` / `versionCode='120'`）
- 版本线分配：**3.7.x LTS = 97–119**（长期支持线，只收缺陷修复）、**3.8.x = 120–199**（功能线），3.9 之后另起一段
- 影响面：`app/build.gradle.kts` 的 `minSdk`、清单权限与前台服务类型、两处新功能（一起听 / 听歌识曲）的可行性边界

## 1. 决策

把 `minSdk` 从 **24** 提到 **29**。

理由不是“想用新 API”，而是**唯一能解锁「识别他人播放的音频」**的档位。

### 先拆词：「内部音频识别」是两件事

调研报告（`2026-10-29-song-recognition-research.md` §1.3）纠正了一个会误导决策的笼统说法：

| 说法 | 实际手段 | 需要的版本 |
|---|---|---|
| 识别**别的应用**正在放什么 | `MediaProjection` + `AudioPlaybackCapture` | **29+**（本次提 minSdk 买的就是这一条） |
| 识别**自己**正在放什么 | 现有 `TeeAudioProcessor` 已把解码后 PCM 旁路（`VisualizerRenderersFactory.kt:55-77`） | **24 就成立，零权限** |

所以提 minSdk 29 换到的能力边界必须写清：**不是“内部音频”这个笼统概念，而是“捕获他人输出”**。

| 档位 | 麦克风识曲 | 内部音频识别 | 说明 |
|---|---|---|---|
| 24（现状） | 可 | **不可** | 无 `AudioPlaybackCapture` |
| 28（Android 9） | 可 | **不可** | 仍无该 API，只是前台服务/通知行为更顺 |
| **29（Android 10）** | 可 | **可**（需 `MediaProjection` 授权） | 本次选定档位 |

补充事实（写死在报告里，避免以后重复争论）：

- `CAPTURE_AUDIO_OUTPUT` 是**系统签名权限**，普通应用申请不到；第三方唯一路径是
  `MediaProjection` + `AudioPlaybackCaptureConfiguration`（API 29+）。
- 能否捕获**别人**的输出，还取决于对方 `AudioAttributes.setAllowedCapturePolicy` /
  清单 `android:allowAudioPlaybackCapture`。网易云 / QQ 音乐 / B站 是否放开，需要真机实测，
  不作为本 ADR 的前提。
- Arris 自己是否允许被别的应用捕获，是可以主动选的（默认建议 `ALLOW_CAPTURE_BY_SYSTEM`，
  保持与系统录屏/投屏一致）。

## 2. 代价

- 放弃 Android 7.0–9.0 设备。具体比例以调研报告引用的版本分布数据为准（要求给出处与日期），
  本 ADR 不重复引用二手数字。
- 现有 `versionCode` 线不需要重置：`minSdk` 提高不改变包名与签名，已发布的 3.7.x 用户
  在**新版本 APK** 上会因 `minSdk` 不满足而无法覆盖安装 —— 这是预期行为，不是 bug。

## 3. 兼容与灰度

三选一，最终由报告结论 + 需求方确认：

1. **同一 APK 内按 `SDK_INT` 降级**：内部音频入口在 <29 上隐藏，只留麦克风识曲。
   （提了 minSdk 后这条基本没用，因为根本装不上，但**仍要保留**——它是双 flavor 方案的公共底层。）
2. **双 flavor**：`modern`（minSdk 29，带内部音频识别）与 `legacy`（minSdk 24，仅麦克风）。
   代价是两条构建线、两套测试矩阵。
3. **只提主线**：直接 minSdk 29，老设备停留在 3.7.x。

建议先做 **3**，若老设备占比确实显著再补 **2**。

## 4. 清单与代码影响（预清单，落地时逐条核对）

- `app/src/main/AndroidManifest.xml`
  - `RECORD_AUDIO`：**已有**（生日吹蜡烛录音），麦克风识曲不需要新增声明
  - `FOREGROUND_SERVICE_MEDIA_PROJECTION`（API 34+ 必需）**只在做内录时**才需要 ——
    v1 是「前台 Activity 内录音 + 单次识别」，`RECORD_AUDIO` 是 while-in-use 权限，
    **不需要任何前台服务**（报告 §2.3）。只有「最小化后继续录」才需要 `microphone` 类型的 FGS。
    ⇒ 本行从「必做」改为**条件式**。
- 低版本分支可以就地简化，但**不是必须**（33 处 `SDK_INT` 判断里只有 3 处与 29 相关）：
  - `player/PlaybackService.kt:345`（`< Q` 的通知构造分支）
  - `share/LyricShareActions.kt:180` / `:291`（MediaStore 相册写入与 `canSaveToGallery`）
  - 这几处保留即可，删除属于后续清理，不和功能 PR 混在一起。

## 5. 验收

1. `assembleRelease` 产物的 `aapt2 dump badging` 里 `sdkVersion:'29'`。
2. 在 API 29 / 31 / 36 三台设备（或模拟器）上装得上、能启动、能播放。
3. 内部音频识别在 <29 的模拟器上：应用装不上（预期），在 29+ 上能进入授权流程。

## 5.1 保留 Visualizer 路径（即使已提到 29）

报告 §1.2 给出一条推翻了仓库旧注释的事实：Audile 在拿不到 `MediaProjection` 时会退回
`android.media.audiofx.Visualizer(0)` 全局会话（`VisualizerSoundSource.kt:68-101`），
它是**唯一不需要投屏弹窗**的内录手段。旧注释说「Visualizer 要 RECORD_AUDIO，与本项目定位冲突」——
该前提已失效：manifest 早已为生日录音声明了 `RECORD_AUDIO`。

结论：即使 minSdk 提到 29，**也保留 VISUALIZER 代码路径**（可以不挂 UI 入口），
它的价值是「不想被录屏提示吓到的用户」这一条真实诉求。

## 6. 未验证

- 网易云 / QQ 音乐 / B站 的实际 `allowedCapturePolicy`（需真机 + 各自最新版实测）。
- 提到 29 后的真实设备覆盖率数字（等调研报告给出带出处的数据）。
