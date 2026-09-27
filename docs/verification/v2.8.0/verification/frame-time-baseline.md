# v2.8.0 波形帧时间基线（release 包 · 真机）

> 本文件只记录**实测**数据与可复现命令。所有数字来自本轮真机命令输出，未经任何加工；
> 原始 `dumpsys gfxinfo` 全文见同目录 `baseline-gfxinfo-visualizer-on*.txt`。

## 1. 测量装置

| 项 | 值 |
|---|---|
| 设备 | S6 `SM-G9209`（`0715f763f54c023a`），Android 7.0 / API 24 / MemTotal 2741028 kB |
| 被测包 | **release**（R8 minified，`assembleRelease`），`versionCode=50` / `versionName=2.6.2-gpl` |
| 签名 | 新密钥 `ncrust-release-v2.jks`（cert SHA-256 `e62eca39…bbec82`），与历史发布签名**不同**（见 EVIDENCE.md） |
| APK sha256 | `308abee59484a4db9fe2b1bbdfeb2f1e081a7326f2224192b4f4729f104956b1` |
| 界面状态 | 播放中（网易云《燕无歇》）+ 播放器卡片展开 + **大屏幕模式（⤢）** —— 这是 S6 上唯一会挂载可视化的布局（`PlayerLayout.visualizerSlot`：`bigScreenActive \|\| (isWidePlayer && isLargeScreen && orientationLandscape)`） |
| 可视化 | 开（`ncrust_settings/audio_visualizer` 未显式设置 ⇒ 默认 `true`） |
| 分辨率 / 方向 | 2560×1440（横屏，大屏幕模式） |

**为什么必须进大屏幕模式**：手机竖屏下 `visualizerSlot = false`，波形根本不挂载（AGENTS.md 的 A/B 矩阵
「手机竖屏 ❌ 自 v1.8.0 起就没有，不回归」）。这是本轮真机实测确认的行为，不是推断。

## 2. 测量方法（可复现）

```bash
export ADB=/home/duanjb666/Android/sdk/platform-tools/adb
P=com.takahashirinta.ncrust; D=0715f763f54c023a
# 1) 让 App 处于「播放中 + 播放器展开 + 大屏幕模式」
$ADB -s $D shell "dumpsys gfxinfo $P reset"    # 2) 清零
sleep 30                                        # 3) 稳态 30s（屏幕保持常亮）
$ADB -s $D shell "dumpsys gfxinfo $P"           # 4) 取 Total/Janky/percentile
```

**口径（沿用仓库既有约定，见 `docs/verification/*/probe-forwarding-attr.md:534`）**：
判读看 P50/P90/P99 与 janky%，A/B 差异以 **P90 Δ ≤ 1.0 ms** 为「不明显掉帧」，
并要求差异大于噪声带（同一配置重复测量的极差）。

## 3. 基线结果（v2.6.2 · 波形现状 28×drawRect）

| 轮次 | Total frames | Janky frames | P50 | P90 | P95 | P99 | 原始文件 |
|---|---|---|---|---|---|---|---|
| run1 | 1764 | 1713 (97.11%) | 19 ms | 28 ms | 30 ms | 36 ms | `baseline-gfxinfo-visualizer-on.txt` |
| run2 | 1274 | 1173 (92.07%) | 19 ms | 28 ms | — | 34 ms | `baseline-gfxinfo-visualizer-on-run2.txt` |
| run3 | 1762 | 1696 (96.25%) | 20 ms | 29 ms | — | 40 ms | `baseline-gfxinfo-visualizer-on-run3.txt` |

**噪声带（同配置三轮极差）**：P50 1 ms、P90 1 ms、P99 6 ms、帧数 490 帧（run2 明显偏低 ——
该轮可能有部分时间不在前台/未持续动画，因此**帧数不作为判据**，只作参考）。

**基线结论**：S6 上「播放中 + 播放器展开 + 大屏幕模式 + 波形运行」的稳态 P50 19–20 ms、
P90 28–29 ms、P99 34–40 ms，约 42–59 fps 帧产出。这与 AGENTS.md 记录的「S6 流水线式掉帧」
一致：**基线本身就不是 60 fps 满帧**，因此 v2.8.0 的验收标准是「不显著劣于该基线」，
而不是「达到 60 fps」。

## 4. 屏幕截图

| 文件 | 内容 |
|---|---|
| `baseline-01-home.png` | 恢复数据后启动，库页（1063 首）+ mini bar |
| `baseline-02-player-expanded.png` | 播放器展开（竖屏，歌词视图）—— 此布局**不挂载**波形 |
| `baseline-04-bigscreen.png` | **大屏幕模式**：左栏封面下方即现状波形（28 根等宽直角绿柱，无镜像/无圆角/无渐变） |

## 5. 未测项（如实记录）

- **中端设备**：任务书要求的 PCL110（`3B15CD00GB700000`）本轮**未连接**；当前可用的第二台设备是
  华为 `WGR-W09`（Android 12 / API 31 / 7.9GB）。其 App 为旧签名 v2.6.2，安装新签名包必须卸载重装
  （无 root，无法备份数据）⇒ 该机验证需用户确认，见 EVIDENCE.md「遗留风险」。
- **波形关（`audio_visualizer=false`）的对照轮次本轮未做**：优先级让给「档位 A/B」。
- **音频线程侧单独测量**（探针 §6 提到的 handleBuffer 回调率/音频线程开销）本轮未做。
