# 版本回溯 —— P0 级联故障的引入版本

- **方式**：提交级代码考古（`git log -S` / `--follow --diff-filter=A` / `-L`），HEAD = `5dfa064`（v2.2.0-gpl）
- **执行**：只读命令，未 checkout、未改动工作区
- **完整命令与决定性行**：见本文每节的「证据」小节

> ## 结论（先看这一段）
>
> | 问题 | 答案 |
> |---|---|
> | **触发条件首次可达的版本** | **v2.1.0-gpl（versionCode 30）** —— 这一版才有 QQ 音源与「臻品」档前缀（`Q001` = 6 声道 FLAC） |
> | **症状的放大器从哪来** | 自 **v1.0.4-gpl（versionCode 7，fork 首发）** 起就带着：无上限降档重试 + 无熔断跳歌（两个提交都早于 fork，属上游继承） |
> | **为什么 v1.8.0–v2.0.x 复现不了** | 那一版已经有 mono tee（环 1），但**服务端拿不到 6 声道文件**（没有 QQ 源，ncm 侧没有 6 声道臻品档） |
> | **为什么 v2.1.0–v2.1.4 没被报出来** | 环 1+2 已成立，理论上可复现；但那时**账号权益只到 HQ**（`music_lev_sq=0`，见 v2.1.4 报告），请求臻品档拿到的是空 purl，压根走不到 6 声道文件 |
> | **现在为什么必现** | fork 账号权益变化后，`AI00`/`Q000`/**`Q001`** 开始返回真实 purl ⇒ 环 1 被点着 |
> | **用户报障版本** | v2.2.0-gpl（versionCode **37**），本机实测复现 |

---

## 逐环引入点

| # | 缺陷环节 | 引入提交 | 提交说明 | 首个包含它的发布 tag | 该 tag 的 versionCode |
|---|---|---|---|---|---|
| 1 | 可视化 tee 强制单声道 `WaveformAudioBufferSink(…, 1, …)` | `96469e1` | feat(visualizer): 大屏模式音频可视化（T3） | `v1.8.0-gpl` | 21 |
| 2 | QQ 臻品档前缀 `AI00` / `Q000` / `Q001` | `51b4169` | feat(qqmusic): qm API 客户端（v2.1.0 · B） | `v2.1.0-gpl` | 30 |
| 3 | 无上限降档阶梯（按**实际**档位取下一档，无尝试计数） | `ed6b92c` | fix: 无损以上档位播放无声时自动降档重试 | `v1.0.4-gpl`（上游继承） | 7 |
| 4 | `onUnplayable` → 直接跳下一首，无计数器 | `8806bb6` | fix: 蓝牙断开不停播 / 无版权歌曲卡住 等三修 | `v1.0.4-gpl`（上游继承） | 7 |
| 5a | `PlaybackService` 五参调 `saveState` ⇒ `putString(key, null)` 抹掉音源身份 | `51b85da` | feat(player): 队列按音源路由 + 双账号 + 聚合搜索（v2.1.0 · C/E） | `v2.1.0-gpl` | 30 |
| 5b | 冷启动恢复用 `TrackKey.of(null, savedState.songId)`（无视落盘身份） | `63916fc` | fix(player): 跨源切歌不再问错音源，歌词跟着当前音频走 | `v2.1.5-gpl` | 35 |

**「整条链（含 5b）首次同时成立」= v2.1.5-gpl（35）**；`97916fc` 之外的 5 个提交都已是 v2.1.4 的祖先，
唯一不在 v2.1.4 里的是 `63916fc`（用 `git merge-base --is-ancestor` 逐条核过）。

---

## 各环证据

### 环 1 —— `96469e1`（v1.8.0-gpl / 21）

```
git log --follow --diff-filter=A -- app/src/main/java/com/takahashirinta/ncrust/player/VisualizerRenderersFactory.kt
  → 96469e1（唯一新增）
git log -S 'WaveformAudioBufferSink' --all
  → 96469e1（代码）+ bd758d0（v1.8.0 调研文档，非代码）
git log -L '/WaveformAudioBufferSink/,/WaveformAudioBufferSink/:…/VisualizerRenderersFactory.kt
  → 该行历史上只被 96469e1 触碰过
```

决定性行（`git show 96469e1:…/VisualizerRenderersFactory.kt`）：

```kotlin
val waveformSink = WaveformAudioBufferSink(
    WaveformStore.BARS_PER_SECOND,
    // 1 = 混成单声道：柱状图只画一条包络，省掉 UI 侧再合并一次。
    1,          // ← 这一行就是 6→1 ChannelMixingMatrix 的来源
)
```

### 环 2 —— `51b4169`（v2.1.0-gpl / 30）

```
git log --follow --diff-filter=A -- app/src/main/java/com/takahashirinta/ncrust/qq/QqQuality.kt
  → 51b4169（所有 ref 上的唯一新增，含 feature 分支）
```

决定性行：

```kotlin
AI00("AI00", "flac", "臻品母带", 4),
Q000("Q000", "flac", "臻品全景声", 5),
Q001("Q001", "flac", "臻品音质", 6),
"dolby" -> listOf(QqFileType.Q001, QqFileType.Q000, QqFileType.AI00)
QqFileType.Q001 -> "dolby"        // ← 6 声道文件落在统一档位表的「杜比」上
```

### 环 3 —— `ed6b92c`（上游 2026-09-08 → 首个 tag v1.0.4-gpl / 7）

```
git log -S 'qualityRetryLadder' --all      → 最早 = ed6b92c
git log -S 'handlePlaybackError' --all     → 最早 = ed6b92c
git log -S 'playback error at level' --all → 最早 = ed6b92c
```

决定性行：

```kotlin
private val qualityRetryLadder = listOf("dolby","jyeffect","hires","lossless","exhigh","higher","standard")
val level = lastPlayedLevel.ifEmpty { qualityApiLevels.getOrElse(currentQualityIndex.value) { "lossless" } }
idx in 0 until qualityRetryLadder.size - 1 -> qualityRetryLadder[idx + 1]
```

`lastPlayedLevel` 本身就是 `ed6b92c` 引入的（= `result.actualLevel`，**实际**档位而非请求档位）；
去重只有 `"${songId}@$level"` + 3 秒窗口，**没有任何 per-song 尝试计数或上限**。

### 环 4 —— `8806bb6`（上游 2026-08-20 → v1.0.4-gpl / 7）

```
git log -S 'setOnUnplayableCallback' --all → 唯一 = 8806bb6
```

决定性行（`git show 8806bb6:…/MainActivity.kt`）：

```kotlin
playerViewModel.setOnUnplayableCallback { playNext() }   // ← 无计数、无上限
```

### 环 5a —— `51b85da`（v2.1.0-gpl / 30）

```
git log -S 'KEY_SONG_SOURCE' --all / -S 'getSourceId' --all → 全部指向 51b85da
git show 51b85da -- …/PlaybackService.kt | grep saveState   → 没有任何改动
```

同一提交在 `PlayerViewModel` 里加了 4 处**带** sourceKey/sourceId/mediaId 的正确写入，
却**没改** `PlaybackService` 那个五参调用点。`SharedPreferences.putString(key, null)` 的语义是
**删除该键**，于是「每次开始播放」都把上一处写好的音源身份抹掉。

> 口径说明：`PlaybackService` 那行五参调用本身可追到 `2bc7a43`(Initial commit)，
> 但**当时无害**（`saveState` 还没有 source 参数）。所以缺陷引入点记 `51b85da`。

### 环 5b —— `63916fc`（v2.1.5-gpl / 35）

```
git log -S 'TrackKey.of(null' --all → 唯一 = 63916fc
```

决定性行：

```kotlin
currentTrack = TrackKey.of(null, savedState.songId)   // ← 落盘里的 songmid 从不读
```

其父提交的恢复代码只有 `currentSongId.value = savedState.songId`，**完全没有 source 概念** ——
即「引入了 source 模型，却把持久化的 source 字段丢掉」这一形态由 `63916fc` 引入。

**本机 A/B 复核（Variant A，2026-09-25）**：手工把 `song_source_id=003Rxsvb1Vwl5U` 写进
`ncrust_playback_state.xml` 再冷启动，日志**依旧** `missing songmid`
⇒ 环 5b 独立成立，与环 5a 无关。

---

## 未验证边界（如实标注）

1. **未做逐版本装机回溯**：需要登录态 QQ 账号 + 6 声道文件可得的曲目；
   设备（PCL110）当时被实时复现占用，平板（WGR-W09）没有登录 QQ。
   本报告的引入版本来自**提交级代码考古**（可复核、可重跑，命令都在上面），
   不是装机实测。**这一点在 release notes 的未验证清单里同样列出。**
2. 环 3 / 环 4 是**上游继承**提交，本仓库内没有上游 tag，所以「首个发布版本」只能给到
   fork 首发 `v1.0.4-gpl`（7）—— 这不代表它们在 fork 线是新引入的。
3. 「为什么 v2.1.0–v2.1.4 没人报」的解释（账号权益当时只到 HQ）来自 v2.1.4 报告里
   `vip_login_base` 的实测字段，属于**历史证据推断**，不是本轮实测。
