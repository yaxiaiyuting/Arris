# P0 级联故障探针报告 —— QQ 音源循环切音质 / 自动切歌 / 音频焦点抢占

- **版本**：v2.2.0-gpl（versionCode 37）复现；修复进 v2.2.1-gpl
- **日期**：2026-09-25
- **设备**：PCL110（OnePlus / Android 16 / API 36 / **已 root（KernelSU）**，可读应用私有目录与注入故障）
  对照设备：WGR-W09（华为平板 / Android 12 / API 31，未 root）
- **账号**：设备上已登录 qm（`ncrust_qq_prefs.xml` 有 `qq_cookie` / `qm_keyst`，`qq_vip_type=1`）
- **证据目录**：本目录（`logcat/`、`screenshots/`、`EVIDENCE.md`）

> **一句话结论**：这不是「降级逻辑写错」这么轻。根因是**可视化用的音频 tee 把输出声明成单声道**，
> 而 QQ「臻品音质」档回的是**6 声道 FLAC**；media3 没有 6→1 的混音系数，直接抛
> `UnsupportedOperationException`，并**把 AudioSink 打进不可恢复状态** —— 同一个 player 实例
> 之后**任何档位**都播不出来。于是「降档重试」必然全败（把音频焦点抢 8 次），最后跳歌；
> 下一首同样失败 ⇒ 无限跳歌。用户报的三件事（循环切音质 / 自动切歌 / 抢焦点）是同一条链。

---

## 0. 复现条件（用户提供 + 本机复核）

| 项 | 结论 | 证据 |
|---|---|---|
| 音源 | **只在 QQ 音源**（ncm 侧没有 6 声道臻品档） | `logcat/04-qq-vkey-tiers.txt` |
| 触发操作 | QQ 曲目播放中，音质从 **Hi-Res（或无损）切到「杜比全景声」** | 用户 2026-09-25 复现 + 本报告 §3 |
| 是否需 SVIP | **不需要**。决定因素是「服务端给不给臻品档的 purl」，与账号等级只是相关 | `probe-qq-vkey-auth.py` 矩阵 |
| 特定歌曲 | 任意一首服务端**给得出** 6 声道臻品档（Q001）的曲目；实测《稻香》《晴天》《One Last Kiss》均可 | `probe-channels.py`（见 §2.3） |
| 网络 | 无关（本地解码/输出链故障，不是取流故障） | `ERROR_CODE_UNSPECIFIED` + cause |
| 复现率 | **必现**（用户原话「稳定触发」；本机同一路径两次复现一致） | `logcat/01-cascade-loop-v2.2.0.txt` |

---

## 1. 版本回溯

**结论：触发条件（6 声道文件）随 v2.1.0「QQ 音源接入」进入；放大器（无上限降档 + 无熔断跳歌）
在更早版本就有，但只有 QQ 臻品档能稳定把它点着。**

详细逐提交证据见 [`version-bisect.md`](version-bisect.md)。要点：

| 环节 | 引入 | 说明 |
|---|---|---|
| 可视化 tee 混成单声道（`WaveformAudioBufferSink(…, 1, …)`） | v1.8.0 · T3 | 平台假设未做 A/B：注释写「media3 内部走默认的 ChannelMixingMatrix」，实际 6→1 没有系数 |
| QQ 臻品档前缀（`Q001`/`Q000`/`AI00`） | v2.1.0 · B | 6 声道文件的唯一来源 |
| 无上限降档重试 | v1.2.0（自动降档引入） | 每错一次降一档，没有次数上限 |
| 无熔断的 `onUnplayable → playNext` | v1.3.0 | 整条队列一起失败 = 无限跳歌 |
| 冷启动丢 songmid | v2.1.5（`TrackKey.of(null, id)`） | 让「取不到链 → 吃离线缓存」成为常态 |

**在 v2.1.0 之前的版本无法复现**（没有 QQ 音源，也就没有 6 声道臻品档）。
本机**未做逐版本装机回溯**：设备被用户占用做实时复现，且回溯需要登录态 QQ 账号
（Huawei 平板上没有登录）。**这一条如实记为未验证缺口**，代之以提交级代码考古（可复核、可重跑）。

---

## 2. 链路探针

### 2.1 冷启动丢失跨源身份（songmid）

```
09-25 14:41:37.345 I NcrustTrack: lyric request begin: track=qqmusic:4611686018727083242 gen=1
09-25 14:41:37.345 W NcrustTrack: qq lyric skipped: track=qqmusic:4611686018727083242 missing songmid
```

冷启动后**每次**都是这一对行（`logcat/03-coldstart-identity-loss.txt`，6 次冷启 6 次命中）。
缺失的 `songmid` / `media_mid` 是 QQ 取链的**必需参数**，于是：

```
14:37:06.760 W SourceRouter: unresolvable song source=qqmusic id=4611686018727083242 (missing sourceId)
14:37:06.760 I PlayerViewModel: offline cache hit songId=4611686018727083242 key=song:…:lossless
14:37:06.787 I PlayerViewModel: quality verdict requested=jymaster granted=lossless br=0 type= songMax=null -> displayIdx=3 status=NORMAL
```

**用户选「超清母带」，实际播的是离线缓存里那条「无损」的旧 URL，而且角标 `status=NORMAL`（一个字都不提示）。**
这就是「SVIP 开不了母带、只能用 hires/无损」的直读来源。

落盘一侧的机械原因（代码级，`git blame` 可查）：

1. `PlaybackService` 起播时调 `PlaybackStateManager.saveState(this, songId, title, artist, artwork, true)`
   —— **没带** `sourceKey/sourceId/mediaId`；而 `saveState` 内部是
   `putString(KEY_SONG_SOURCE_ID, null)`，SharedPreferences 的语义是**删除该键**。
   于是每次起播都会把 ViewModel 刚写好的 QQ songmid 抹掉。
2. `PlayerViewModel` 恢复时写死 `currentTrack = TrackKey.of(null, savedState.songId)`
   —— 即使落盘里有 songmid 也**从不读**。
   A/B 实测（`Variant A`）：手工把 `song_source_id=003Rxsvb1Vwl5U` 写进 prefs 再冷启，
   日志**依旧** `missing songmid` ⇒ 这一半与第 1 条无关，是独立的第二个洞。

### 2.2 取链正常时，服务端实际给什么

```
14:38:17.216 I QqApi: vkey ok: requested=jymaster actual=hires prefix=RS01 mid=000zwUDf0KftQv
14:40:33.825 I QqApi: vkey ok: requested=jymaster actual=jymaster prefix=AI00 mid=003u4H921sRR6B
```

- 有的曲子请求母带确实拿到 `AI00`（臻品母带）；
- 有的只给 `RS01`（Hi-Res）。
  ⇒ **「拿不到母带」是服务端按曲按权益下发的结果，不是客户端映射错。**

### 2.3 服务端逐档位矩阵（`tools/probe-qq-vkey-auth.py` + 自写 `probe-channels.py`）

登录态、逐档位请求，读 `result` 与 `purl`（**不打印任何 vkey / cookie**）：

| 档位（前缀） | 《稻香》result | purl | **文件实际格式**（解析 FLAC STREAMINFO） |
|---|---|---|---|
| `AI00` 臻品母带 | 0 | 有 | FLAC 192000Hz **2ch** 24bit |
| `Q000` 臻品全景声 | 0 | 有 | FLAC 44100Hz **2ch** 16bit |
| `Q001` 臻品音质 | 0 | 有 | FLAC 44100Hz **6ch** 16bit ← **致命** |
| `RS01` Hi-Res | 104003 | 空 | — |
| `F000` 无损 | 104003 | 空 | — |
| `M800` 320k | 0 | 有 | mp3（ID3） |
| `M500` 128k | 0 | 有 | mp3（ID3） |

**最刺眼的一行是 `Q001` = 6 声道。** 它在 Ncrust 的统一档位表里映射成 `dolby`（杜比全景声），
而 `qualityRetryLadder` 里 `dolby` **紧跟**在 `jymaster` 之后 —— 母带一失败就轮到它。

### 2.4 致命错误：6→1 声道矩阵

```
E ExoPlayerImplInternal:   java.lang.UnsupportedOperationException:
      Default channel mixing coefficients for 6->1 are not yet implemented.
E ExoPlayerImplInternal: Disable failed.   (同一异常)
E ExoPlayerImplInternal: Reset failed.     (同一异常)
E PlaybackService: Playback error for songId=… code=ERROR_CODE_UNSPECIFIED: Unexpected runtime error
```

`errorCode` 是**最没用**的 `ERROR_CODE_UNSPECIFIED`，原因只出现在 cause 里
（这也是修复里必须带上 cause 做分类的原因，见 `PlaybackGuard.classifyFailure`）。

media3 1.5.0 字节码核实（`javap -c ChannelMixingMatrix.createMixingCoefficients`）——
**只实现三种矩阵**：

| 输入 → 输出 | 结果 |
|---|---|
| N → N | 单位矩阵 |
| 1 → 2 | `{1, 1}` |
| 2 → 1 | `{0.5, 0.5}` |
| **6 → 1** | **抛 `UnsupportedOperationException`** |

而 `VisualizerRenderersFactory` 把可视化 tee 建成
`WaveformAudioBufferSink(BARS_PER_SECOND, 1, …)` —— 第二个参数 `1` 就是「混成单声道」。

### 2.5 级联实测（v2.2.0，`logcat/01-cascade-loop-v2.2.0.txt`）

同一份 20 分钟日志里：`playSong` **88** 次、`playback error at level=` **48** 次、
`lowest tier also failed`（= 跳歌）**12** 次。片段：

```
14:44:50.789 Playing: …/Q001…flac … ncrustkey=song:…:dolby       ← 6 声道文件
14:44:51.117 E PlaybackService: Playback error … ERROR_CODE_UNSPECIFIED
14:44:51.117 W PlayerViewModel: playback error at level=dolby …, retrying at jyeffect
14:44:51.366 E PlaybackService: Playback error …        ← 同一实例，已坏，之后每一档都失败
14:44:51.580 W PlayerViewModel: playback error at level=hires …, retrying at lossless
14:44:52.949 W PlayerViewModel: playback error at level=exhigh …, retrying at higher
14:44:53.333 W PlayerViewModel: playback error at level=higher …, retrying at standard
14:44:54.038 W PlayerViewModel: lowest tier also failed …, skipping      ← 跳歌
14:44:54.039 I NcrustTrack: playSong -> currentTrack=qqmusic:4611686018427589359   ← 下一首
14:44:54.372 W PlayerViewModel: playback error at level=jyeffect …, retrying at hires
…（下一首再走一遍 8 档）
14:45:00.183 W PlayerViewModel: lowest tier also failed …, skipping      ← 又跳
```

**每次 `playSong` 成功路径都会 `startForegroundService` → `player.playWhenReady = true`，
也就是一次新的 `requestAudioFocus`** —— 8 档 × 每首 = 用户看到的
「循环切音质 + 视频被反复打断 + 音频焦点被反复抢占」；`lowest tier … skipping` 就是自动切歌。

另有离线兜底造成的**固定点**（同文件，14:44:51）：

```
14:44:51.791 offline cache hit … key=song:…:dolby     ← 请求 lower，兜底却给了 …
14:44:51.950 playback error at level=jyeffect …, retrying at hires
…                                    （actual 始终等于缓存档位 ⇒ next 反复不变）
```

---

## 3. 假设验证（逐条）

| 假设 | 结论 | 依据 |
|---|---|---|
| 超级 SVIP 请求母带，服务端实际返回 hires？ | **部分成立**：按曲不同 —— 有的给 `AI00`（真母带），有的只给 `RS01` | §2.2/§2.3 |
| 降级后 `QualityAssessment` 用 `maxOf` 回显虚高？ | **不成立（v2.1.4 已修）**。实测角标显示的是**实际档位**（hires/无损），不是请求档位 | §2.1 的 `displayIdx=3` |
| 虚高标签让后续逻辑再请求母带，形成循环？ | **不成立**。循环的真因是 §2.4 的 AUDIO SINK 致命错误 + 无上限降档 | §2.5 |
| `onPlayerError` 把音质切换失败误判成播放失败、触发跳歌？ | **成立**（这是放大器之一）：`lowest tier also failed → skipping` | §2.5 |
| 跳歌后新歌重复上述循环，导致无限切歌？ | **成立**：同一 player 实例已坏，下一首**每一档**都失败 | §2.5 |
| 每次试播都 requestAudioFocus，视频被反复打断？ | **成立**：每次重试都 `playWhenReady=true` | §2.5 + `dumpsys audio` |
| 用户新假设：「请求没有母带的歌 + 播母带」会诱发？ | **方向正确，但触发点是「杜比」而非「母带」**：母带失败后阶梯下一档就是 `dolby` → QQ 的 `Q001` = 6 声道 | §2.3/§2.4 用户补充复现步骤 |

> **用户 2026-09-25 补充的复现步骤被本报告完全采纳**：「QQ 音源播放这首 → 切超清母带 →
> 显示 hires 时再切杜比 → 稳定触发」。这条比原始报障更精确，直接指向 `Q001`。

---

## 4. 音频焦点

- 焦点由 media3 在 `playWhenReady = true` 时申请（`setAudioAttributes(…, true)`），应用本身
  **没有**第二次 `requestAudioFocus`（这是对的，不能加第二个申请者）。
- 问题在**重试次数**：一次故障 → 8 次 `play()` → 8 次申请焦点，且旧实现**从不主动释放**
  （失败后既不 `stop()` 也不 `pause()`，播放器停在 IDLE 但焦点语义取决于 media3 的实现细节）。
- 修复后：失败即 `player.stop()`（media3 会 reset AudioSink **并 abandonAudioFocus**），
  且两次自动重取之间至少 10 s，单曲最多 3 次。

---

## 5. 修复后必须成立的性质（回归判据）

1. 切到「杜比」不再产生 `UnsupportedOperationException`，6 声道 FLAC 能正常出声；
2. 单一曲目自动重取 ≤ 3 次，且档位**严格单调下降**、不重复；
3. 因音质问题失败**不跳歌**；熔断后进「暂停 + 错误态」等用户操作；
4. 连续自动跳歌 ≤ 5 次即熔断；
5. 两次自动重取间隔 ≥ 10 s（音频焦点节流）；
6. 冷启动后 QQ 曲目仍有 songmid（不再退化成「随便哪个档位的离线 URL」）；
7. 降级后自动路径不再自己升回原档位（24h TTL，用户手动切换即解除）。

以上 7 条对应 `app/src/test/java/.../player/PlaybackGuardTest.kt`、
`TransparentWaveformSinkTest.kt`、`QualityAssessmentTest.kt` 里的用例，并各有一条真机 A/B。
