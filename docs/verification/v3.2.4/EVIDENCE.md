# v3.2.4 证据索引（EVIDENCE）

> **先探针、后改码**。本目录下的每一份原始件都对应正文里的一句结论；
> 没有实测到的写「未验证」，绝不补一个看起来合理的数字。

## 目录

```
docs/verification/v3.2.4/
├── probe-bili-playback.md          P0 探针（9 个必答问题的逐条回答）
├── probe-waveform-jitter-hfr.md    P1 探针（8 个必答问题的逐条回答）
├── PROBE-SUMMARY.md                两个探针的一页结论 + 阻塞清单
├── EVIDENCE.md                     本文件
├── CHANGELOG.md                    用户可读的更新说明
├── RELEASE-STATUS.md               发布状态（tag / APK / release / 阻塞）
├── evidence/                       host 侧原始件（curl 矩阵、JSON、仿真脚本与输出）
└── verification/                   设备侧原始件（logcat、截图、gfxinfo、安装校验）
```

## P0 · B 站歌曲无法播放

| 结论 | 原始件 |
|---|---|
| 取链响应体 / URL 格式 / `qualities[]` / `timeout` | [`evidence/audio-url-au39-qn2.json`](evidence/audio-url-au39-qn2.json) |
| 取链参数矩阵（`songid` vs `sid`、`quality` 0/1/2/3、`platform` 必需） | [`evidence/audio-url-param-matrix.txt`](evidence/audio-url-param-matrix.txt) |
| 音频直链的 header A/B（10 组） | [`evidence/cdn-header-matrix.txt`](evidence/cdn-header-matrix.txt) |
| DASH 音轨的 header A/B（5 组） | [`evidence/dash-header-matrix.txt`](evidence/dash-header-matrix.txt) |
| DASH `backupUrl` 的 host 清单 | [`evidence/playurl-raw.json`](evidence/playurl-raw.json) + 探针 §1 |
| **UA 黑名单假设检验**（`android` / `dalvik` 等字样） | [`evidence/ua-android-hypothesis.txt`](evidence/ua-android-hypothesis.txt) |
| UA 黑名单扫描（`curl` / `python` / `vlc` / `wget` / `Java` …） | [`evidence/ua-blocklist-scan.txt`](evidence/ua-blocklist-scan.txt) |
| 边界收敛（UA 必须存在 / Referer 必须存在 / `Origin` 不能替代） | [`evidence/ua-boundary.txt`](evidence/ua-boundary.txt) |
| 复现性（同一条 UA 连测 5 次结果一致，排除限流噪声） | [`evidence/ua-reproducibility.txt`](evidence/ua-reproducibility.txt) |
| 403 响应体（openresty 默认错误页） | [`evidence/cdn-403-body.html`](evidence/cdn-403-body.html) |
| **设备侧**：生产数据源链打真实直链（修复前 403 / 修复后 206） | [`verification/EVIDENCE-bili-v324.md`](verification/EVIDENCE-bili-v324.md) + `verification/raw-*` / `postfix-*` |
| **release 包实测**：B 站歌曲真的在播（PlaybackState=3 + 元数据 + 日志里的 DASH 直链） | [`verification/release-bili-playback-state.txt`](verification/release-bili-playback-state.txt)、[`verification/release-bili-playback-logcat.txt`](verification/release-bili-playback-logcat.txt)、[`verification/release-bili-playing.png`](verification/release-bili-playing.png) |
| release 包实测截图（大屏模式 + 三条泳道波形同时可见） | [`verification/release-bigscreen-waveform.png`](verification/release-bigscreen-waveform.png) |

## P1 · 新设备（高刷新率）音频条抖动

| 结论 | 原始件 |
|---|---|
| 帧闸门 / 插值 / 缓冲三条链路的源码行号 | `probe-waveform-jitter-hfr.md` §3–§7 |
| 仿真脚本（逐字复刻 v3.2.3 的闸门） | [`evidence/sim_final.py`](evidence/sim_final.py) |
| 仿真输出（60/90/120/144Hz × ±0.6ms / ±1.5ms 抖动 × 有无掉帧） | [`evidence/sim-framerate.txt`](evidence/sim-framerate.txt) |
| 设备刷新率（模拟器只有一个 60.000004Hz 模式） | [`verification/EVIDENCE-waveform-v324.md`](verification/EVIDENCE-waveform-v324.md) §1 |
| 音频缓冲粒度（100.00ms / 11.0~11.5Hz，S6 与 PCL110 双机实测） | `docs/verification/v3.0.0/probe/EVIDENCE.md:40-47`（v3.2.2 探针复核，本版未重测） |
| release 包帧时间（`dumpsys gfxinfo framestats`，30s 窗口 ×4） | [`verification/EVIDENCE-waveform-v324.md`](verification/EVIDENCE-waveform-v324.md) §3 + `verification/gfx-*.txt` |
| 四档刷新率的确定性单测 | `app/src/test/java/.../ui/player/WaveformFrameRateTest.kt`、`.../motion/DisplayRefreshTest.kt` |

## 单测 / 静态检查

| 项 | 命令 | 结果 |
|---|---|---|
| JVM 单测 | `./gw.sh :app:testDebugUnitTest` | **2229 个用例 / 0 失败 / 0 跳过**（XML 计数，见 `verification/EVIDENCE-build.md`） |
| lint | `./gw.sh :app:lintDebug` | 0 error（只有 Warning/Information；baseline 160 条不变） |
| release 构建 | `./gw.sh :app:assembleRelease` | 成功，`versionCode=59` / `versionName=3.2.4-gpl` |
| 真机探针（androidTest） | `:app:connectedDebugAndroidTest -P…class=BiliV324ProbeTest` | 4/4 通过 |

## 与任务书的三处偏离（如实）

1. **「真机验证（新设备 + S6）」无法执行** —— 本环境只有两台 API 33 的 x86_64 模拟器
   （`adb devices` 实测），没有 120Hz 设备、没有 S6。P1 的高刷验收改用
   「四档刷新率的确定性单测 + 60Hz release 帧时间不回归」代偿，并在
   `PROBE-SUMMARY.md` 的阻塞清单里列明。
2. **「B 站登录后可播」无法验证** —— 没有 B 站账号；B 站也不在登录音源列表里
   （`BiliSourceProvider.isLoggedIn` 恒为 false，v3.1.0 起如此）。
3. **`./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease` 里的 `clean`**
   在本轮**没有**与 `testDebugUnitTest` 串在一条命令里跑：`clean` 之后 Gradle 需要重跑
   全部 359 个任务（含 R8），而本轮为了保留 release APK 与 mapping 的对应关系，
   改成「先 `clean assembleRelease`，再单独跑测试与 lint」。两者覆盖的任务集合相同，
   但**不声称**「一条命令跑通」。
