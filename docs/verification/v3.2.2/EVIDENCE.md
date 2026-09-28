# v3.2.2 证据清单（EVIDENCE）

本文件列出本版**全部可复核的测量与产物**：怎么产生、落在哪、以及口径限制。
凡是没有证据支撑的结论，在 [PROBE-SUMMARY.md](PROBE-SUMMARY.md) 的「遗留风险与未验证项」里逐条列出。

## 1. 探针实测（代码未改之前）

| 证据 | 产生方式 | 文件 |
|---|---|---|
| 主导频段四方案 × 5 首歌的切换频率 | `./gradlew :app:testDebugUnitTest --tests '*V322DominantBandProbe*'`；语料 = 5 首 320kbps 完整歌曲（1219 秒）；生产类 `AudioFeatureExtractor` 逐缓冲（4410 帧 = 100ms）跑 PCM | `probe/dominant-band-measurements.md`、`probe/dominant-band-timeseries.csv`（12202 个缓冲的逐条 `t,rms,low,mid,high`） |
| 曲线四种插值的过冲与负高度 | `--tests '*V322CurveProbe*'`；输入 = 生产 `WaveformRing` 在同样 5 首歌上产出的 19055 帧柱高，每段 16 采样点（8 250 815 点） | `probe/curve-smoothing-measurements.md` |
| HCT 三角色可区分性 | `--tests '*V322RolesProbe*'`；36 个真实种子（6 预设 + 8 封面色经 `processAccentColor`）× 明暗两模式；16 个色相/彩度变体 | `probe/color-roles-measurements.md`、`probe/color-roles-swatches.png` |
| 颜色切换方式扫描 | `--tests '*V322ColorTransitionProbe*'`；**生产 `BandDominance`**、60fps 判定 / 11Hz 输入、过渡 0/40/80/100/120/400ms | `probe/color-transition-measurements.md`、`probe/color-transition-trajectories.csv`（逐帧权重与混合色）、`probe/color-transition-compare.png` |
| 图表渲染器（只画像素、不重算逻辑） | `python3 tools/probe-v322/render_charts.py` | `tools/probe-v322/render_charts.py` |

**语料可复现性**：语料不入库（约 200MB）。查找顺序 = 系统属性 `ncrust.probe.audio` →
环境变量 `NCRUST_PROBE_AUDIO` → `../../.scratch/v322/audio`（相对模块目录）。
**找不到语料时探针测试 skip**，绝不用合成信号出数。

## 2. 单元测试

| 项 | 值 |
|---|---|
| 全量单测 | `./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease` → **BUILD SUCCESSFUL** |
| 用例总数 | **2182**（v3.2.1 是 2129；本版 +53 = 52 条新逻辑用例 + 1 条探针用例） |
| 失败数 | **0** |
| 本版新增用例文件 | `BandDominanceTest`（13）、`WaveformCurveTest`（12）、`BandColorRolesTest`（9）、`BandColoringGatingTest`（8）、`WaveformIsolationTest`（7） |
| 探针用例（有语料才跑） | `V322DominantBandProbe`、`V322CurveProbe`、`V322RolesProbe`、`V322ColorTransitionProbe` |
| lint | 无新增告警（`lint` 任务在 clean 构建里通过） |

## 3. 真机（S6 / SM-G9209 / Android 7.0 / 1440×2560）

口径：**release 包**（铁律 16）、横屏大屏模式、播放中、波形挂载；每次 `dumpsys gfxinfo reset`
→ 30 秒窗口 → dump；**每配置 3 次**，只比中位数（单次采样的「总帧数 / 卡顿率」噪声带 ±15%，
实测 p50 在 19~28ms 之间随采样漂移，因此不拿单次结论下判断）。

完整表与对照见 [probe-perf-tier.md](probe-perf-tier.md)；原始 dump 在 `verification/frame-*.txt`。

## 4. 截图（真机）

| 文件 | 内容 |
|---|---|
| `verification/frame-v322-simple.png` | v3.2.2 简洁档（曲线 + 单一主题色） |
| `verification/frame-v322-refined-{1,2,3}.png` | v3.2.2 精致档（频带着色 + 渐变流动 + 峰值 + 光点） |
| `verification/frame-*.txt` | 每次采样的 `dumpsys gfxinfo` 原文 |

## 5. 构建与产物

| 项 | 值 |
|---|---|
| release APK | `app/build/outputs/apk/release/app-release.apk`，`aapt2 dump badging` 实测 `versionCode=57 versionName=3.2.2-gpl` |
| versionCode 三源校验 | `docs/verification/v3.2.2/next-version.txt`（tag v3.2.1=56 / dist APK 实测 56 / 工作区 56 ⇒ 57） |
| R8 mapping 归档（铁律 27） | `mapping-v3.2.2.txt.gz`（5 327 002 字节）+ `.sha256`；未压缩原文在仓库外 `dist/mapping/mapping-v3.2.2.txt` |
| tag | `v3.2.2-gpl`（打 tag 前用 `git show v3.2.2-gpl:app/build.gradle.kts \| grep version` 自证） |

## 6. 明确**没有**证据的结论（不许当成已验证）

- **PCL110（Android 16）**：本轮设备不在线，无该机型的帧时间与观感数据。
- **三档观感的用户 A/B**：只有截图与帧时间，没有用户调研。
- **主题色对比度**：探针量到 5/36 个组合对比度 < 3.0，其中素白浅色是既有问题；本版**没有**修，
  也**没有**验证修法。
- **长时间运行（>30 分钟）的稳定性**：没有做长跑。
