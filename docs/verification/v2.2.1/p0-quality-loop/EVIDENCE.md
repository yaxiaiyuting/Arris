# 证据清单 —— P0 级联故障（v2.2.1）

- **设备**：PCL110（OnePlus / Android 16 / API 36 / **已 root**，序列号 `3B15CD00GB700000`）
- **对照设备**：WGR-W09（华为平板 / Android 12 / API 31 / 未 root，序列号 `WVQ6R22124000968`）
- **时间**：2026-09-25 14:35–15:10（UTC+8）
- **应用**：修复前 `2.2.0-gpl`（versionCode 37）；修复后 `2.2.1-gpl`（versionCode 38）

> ⚠️ **截图含真实界面文本**（歌名/歌单名/账号相关界面）。已删除含账号与充值页面的两张
> （`07-after-back*.png` / `08-closed-webview*.png`）。其余如需公开，请先复核再发布。

## 1. 复现与链路日志

| 文件 | 内容 | 怎么来的 |
|---|---|---|
| [`logcat/01-cascade-loop-v2.2.0.txt`](logcat/01-cascade-loop-v2.2.0.txt) | v2.2.0 级联全貌：`playSong` 88 次、`playback error at level` 48 次、`lowest tier also failed`（跳歌）12 次 | `adb logcat -v threadtime`，grep 应用侧 tag |
| [`logcat/03-coldstart-identity-loss.txt`](logcat/03-coldstart-identity-loss.txt) | 6 次冷启动 6 次 `missing songmid`；对照行是**从队列起播**时带 `mid=` | 同上 |
| [`logcat/04-qq-vkey-tiers.txt`](logcat/04-qq-vkey-tiers.txt) | QQ 取链逐档位结果：`requested=jymaster actual=hires prefix=RS01` 与 `actual=jymaster prefix=AI00` 两种 | 同上 |
| [`logcat/A-before-v2.2.0-cascade.txt`](logcat/A-before-v2.2.0-cascade.txt) | **修复前 A 组**，35 秒窗口：`Q001…flac` → `6->1` 异常 → `Disable failed`/`Reset failed` → 16 次降档 → 1 次跳歌；6→1 异常 72 次 | 见 §3 步骤 |
| `logcat/B-after-v2.2.1-*.txt` | **修复后 B 组**（同步骤同曲目） | 见 §3 步骤 |

复现原始 dump 的取法（本轮未入库，60MB+/份）：

```bash
adb -s 3B15CD00GB700000 logcat -c
adb -s 3B15CD00GB700000 logcat -v threadtime > raw.txt &
adb -s 3B15CD00GB700000 shell am start -n com.takahashirinta.ncrust/.MainActivity
# 步骤见 §3；随后
grep -aE "playSong|quality verdict|Playback error for songId|playback error at level|lowest tier|6->1|QqApi   :" raw.txt
```

## 2. 服务端契约探针（脚本在仓库外 `tools/`，按本仓库惯例不入 git）

| 命令 | 目的 | 关键输出 |
|---|---|---|
| `python3 tools/probe-qq-vkey-auth.py 3B15CD00GB700000` | 登录态逐档位 `result`/`purl` | `AI00=0(url) RS01=104003 F000=104003 M800=0(url) M500=0(url)` |
| `python3 /tmp/probe-purl-shape.py` | 只打 purl 的**文件名形态**（不含 vkey） | `AI00<mid>.flac` / `Q001<mid>.flac` … |
| `python3 /tmp/probe-bytes.py` | 取每档前 64 字节判容器 | 全部 `HTTP 206` + `fLaC` / `ID3` |
| `python3 /tmp/probe-channels.py` | 解析 FLAC STREAMINFO 的**声道数** | **`Q001` = 44100Hz 6ch 16bit**；`AI00` = 192000Hz 2ch 24bit；`Q000` = 44100Hz 2ch 16bit |

> 安全：三个脚本都**不打印 cookie / vkey / 完整 purl**，只打 `result` 码、purl 有无、
> 文件名形态与 STREAMINFO 四个数值。

**这四条命令的结论就是根因的最后一块拼图**：QQ「臻品音质」档（统一档位表的 `dolby`）
回的确实是 **6 声道**文件，而 media3 没有 6→1 的混音系数。

## 3. 真机 A/B 步骤（同一台设备、同一账号、同一曲目）

固定步骤（A/B 完全一致，只有 APK 不同）：

1. 安装目标 APK（保留数据，签名相同）；
2. `adb shell am force-stop com.takahashirinta.ncrust`；`adb logcat -c`；开始抓日志；
3. 启动 App → 打开播放器卡片 → 打开「播放列表」；
4. **从队列里点那首 QQ 曲目**（必须从队列起播 —— 只有这条路径才带着 songmid）；
5. 点音质角标 → 选「超清母带」→ 等 8s；
6. 再点音质角标 → 选「杜比全景声」→ 观察 30s。

| 组 | APK | 结果 |
|---|---|---|
| A | `Ncrust-v2.2.0-gpl-release.apk`（37） | `Q001…flac` → `UnsupportedOperationException: … 6->1 …` → `Disable failed`/`Reset failed` → 之后**每一档**都失败（连立体声 Hi-Res 与 128k mp3）→ 降档走完 → 跳歌 |
| B | `Ncrust-v2.2.1-gpl-release.apk`（38） | 见 `logcat/B-after-v2.2.1-*.txt` |

## 4. 关键源码位置（修复前 → 修复后）

| 环节 | 修复前 | 修复后 |
|---|---|---|
| 可视化 tee | `VisualizerRenderersFactory`：`WaveformAudioBufferSink(BARS_PER_SECOND, 1, …)` | `TransparentWaveformSink`（不混音、不抛异常） |
| 失败分类 | 只看 `errorCodeName`（当时是 `ERROR_CODE_UNSPECIFIED`） | `classifyFailure` 走到 cause 链 |
| 降档重试 | `qualityRetryLadder[idx + 1]`，`idx` 取自**实际**档位，无上限 | `QualityRetryGuard`：`min(请求, 实际)` 严格降一档、去重、≤3 次、≥10s 节流 |
| 取不到链 | 无条件 `onUnplayableCallback()`（跳歌） | `maySkipOnUrlFailure(origin)` 分流：音质来源**不跳歌** |
| 自动跳歌 | 无计数 | `AutoSkipGuard`：连续 >5 次熔断 → 暂停 + 错误态 |
| 失败后 | 什么都不做（sink 坏着、焦点没放） | `player.stop()` + `clearMediaItems()`（reset sink + abandonAudioFocus） |
| 跨源身份 | `saveState` 少传三个参数（抹键）+ 恢复时 `TrackKey.of(null, id)` | 两处都修 + `MainScreen` 用队列补齐（`adoptTrackIdentity`） |
| 降级显示 | 拿不到 br 就一律沉默 | `levelFromFile` 为真时如实标「已降级」 |
| 降级持久化 | 无 | `QualityCeilingMemory`（24h TTL，只约束自动路径，手动切档即清除） |

## 5. 单测与构建

```bash
cd /home/duanjb666/deepseek/ncrust-gpl/Ncrust
./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease
```

- 结果：**BUILD SUCCESSFUL**（EXIT=0），lint 无 Error/Fatal；
- 新增用例：`PlaybackGuardTest`（分类/单调/上限/重置/拉黑/节流/熔断/清零判据/来源分流/上限记忆）
  与 `TransparentWaveformSinkTest`（6→1 必然抛的回归钉、6 声道不抛、RMS 数值）、
  `QualityAssessmentTest` 新增 4 例（可信档位降级、不可信标签沉默、不低于请求仍 NORMAL、实测优先）；
- 结果文件：`app/build/reports/tests/testDebugUnitTest/index.html`。

## 5.5 三设备安装与冷启动冒烟（2026-09-25 15:2x）

三台设备全部升到 `2.2.1-gpl`（versionCode **38**），安装后逐台冷启一次并检查崩溃日志：

| 设备 | 型号 / Android | 安装包 | 签名 | 结果 | 截图 |
|---|---|---|---|---|---|
| `3B15CD00GB700000` | PLC110 / 16 (API 36) | `Ncrust-v2.2.1-gpl-release.apk` | release（`e75af3ff…`） | 冷启正常；**完整 QQ A/B 在本机完成** | `screenshots/B4-playing-6ch.png` |
| `0715f763f54c023a` | SM-G9209 / 7.0 (**API 24**) | `app-debug.apk`（见下） | **Android Debug**（`e10c8b4d…`） | 冷启正常，库页与队列都在 | `screenshots/C-s6-api24-launch.png` |
| `WVQ6R22124000968` | WGR-W09（华为）/ 12 (API 31) | `Ncrust-v2.2.1-gpl-release.apk` | release（`e75af3ff…`） | 冷启正常，播放器/歌词正常 | `screenshots/C-wgr-w09-launch.png` |

崩溃检查命令（三台一致）：

```bash
adb -s <serial> logcat -d | grep -aE "FATAL|AndroidRuntime.*ncrust|beginning of crash|UnsatisfiedLinkError|NoClassDefFoundError|ClassNotFoundException"
# 三台均为空
```

**S6 为什么装的是 debug 包**：它上面原有的 2.2.0 是 **Android Debug 签名**
（`apksigner verify --print-certs` 实测），而 `dist/` 里是 release 签名，直接覆盖会
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。AGENTS.md 已记过「S6 卸载会清登录态」，
所以改装**同一份源码构建的 debug 包**（同样是 38），既保住登录态又完成升级。

## 6. 修复后仍未验证的项（如实列出）

1. **未做逐版本装机回溯**（见 `version-bisect.md` 的口径说明）——引入版本来自提交级考古；
2. **华为 / 非华为 A/B 只完成一半**：非华为（PCL110）做完了完整 A/B；
   华为（WGR-W09）只做了**安装 + 冷启动冒烟**（见 §5.5），**没有跑 QQ 取链 A/B**
   —— 因此「华为 ROM 上 6 声道文件能否正常出声」**本轮未验证**；
3. **API 24（S6）只做了冷启动冒烟**：没有在上面跑 QQ 取链 A/B，
   「Android 7.0 上 6 声道 FLAC 经 FFmpeg 软解能否正常出声」**本轮未验证**；
4. **「ncm 侧是否有 6 声道文件」未逐档位核实**：本轮只核实了 QQ 侧（确定性结论），
   ncm 侧按现有档位表（jymaster/hires/lossless 均为立体声）推断无此形态，属推断而非实测；
5. **修复后的音频听感未做主观评价**（只看「能正常出声、进度前进、无异常日志」）；
6. **多声道文件的声道是否被正确保留**未用外部工具核验（app 侧只保证不再因矩阵缺失而崩）。
