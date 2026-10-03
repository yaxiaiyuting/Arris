# v2.1.6 回归报告 —— 媒体承重结构改造（会话合并）

- 版本：`v2.1.6-gpl` / `versionCode 36`（`tools/next-version.sh` 三源交叉验证 MAX=35 ⇒ 36）
- 改动：把并行的**两条 MediaSession 合并成一条**（`PlaybackService`）
- 采集脚本：本目录 `regression/capture.sh`（基线/改动后**同一份脚本、同一批场景**）
  与 `regression/card-ab.sh`（华为卡片定点探针）
- 采集日期：2026-09-25

---

## 0. 一句话结论

| 问题 | 结果 |
|---|---|
| Ncrust 暴露几条 MediaSession | **2 → 1** ✅（验收标准达成） |
| 通知 / 锁屏 / 媒体键 / 息屏控制 / 蓝牙按键路径 | **无回归**，全部通过 ✅ |
| **华为控制中心媒体卡** | **仍然是「未在播放」** ❌ —— 但**这一版本来就不该修好它**，原因见 §4 |
| 附带修掉的一个既有 bug | 暂停时 `speed` 不再谎报 `1.0` ✅ |

**华为卡片不是本版的修复目标，也没有被修好。** 根因是 ROM 的包名白名单，与 session 条数无关 ——
完整证据见 [`p1-huawei-whitelist/whitelist-criterion.md`](p1-huawei-whitelist/whitelist-criterion.md)。
本版的措辞在任何地方都不得写成「修复了控制中心卡片」。

---

## 1. 改了什么

`PlaybackService` 原先同时持有：

| 会话 | 谁建的 | 用途 |
|---|---|---|
| `NcrustSession` | 应用自己 `MediaSessionCompat` | 通知栏、锁屏（metadata 用应用字段，所以有歌词） |
| `androidx.media3.session.id.` | media3 `MediaLibrarySession` | 车机浏览树（metadata 用 MediaItem） |

两条都 `active=true`，于是 ROM 的 media button session 在两者之间来回跳（v2.1.5 实测日志）：

```
I MediaSessionService: Media button session is changed to …/androidx.media3.session.id.
I MediaSessionService: Media button session is changed to …/NcrustSession
```

对照：官方 ncm `com.netease.cloudmusic/MediaSession` 只有一条；华为音乐
`com.android.mediacenter.mediasession` 也只有一条。

**改法**（三处承重点，缺一不可）：

1. 删掉 legacy `MediaSessionCompat`，只留 media3 那一条；
2. 通知的 `MediaStyle.setMediaSession(...)` 改指向 `mediaSession.sessionCompatToken`
   （media3 官方给「应用自己发通知」留的口子）；
3. **上一首/下一首改由应用回答**：media3 默认会把 `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` 当成
   `player.seekToNextMediaItem()`，而 ExoPlayer 的播放列表**不是**用户的队列（按 v1.5.2 的待播槽位
   不变量，里面只有「当前项 + 至多一首预载项」）⇒ 会退化成「跳到那首预载的歌」并串台。
   现在在 `onPlayerCommandRequest` 里接回 `onPlaybackEnded` / `onPlaybackPrevious`。

会话 metadata 的写入口也换了：media3 没有「直接 setMetadata」的公开 API，metadata 就是当前
`MediaItem` 的 metadata，所以改用 `player.replaceMediaItem(当前项, 只换 metadata)`。
**「替换当前项只换 metadata、不会重新缓冲」是 media3 的行为假设，本报告 §3 用真机数据验证了它成立。**

---

## 2. 回归表（基线 vs 改动后）

设备：**WVQ6R22124000968**（WGR-W09 / HarmonyOS 4.2 / API 31）、
**0715f763f54c023a**（SM-G9209 / Android 7.0 / API 24，非华为）。

| # | 路径 | 改动前（基线） | 改动后 | 判定 | 证据 |
|---|---|---|---|---|---|
| 1 | **本包 MediaSession 条数** | **2** | **1** | ✅ | `*/WVQ6*/S1-session-inventory.txt`、`*/0715*/S1-session-inventory.txt` |
| 2 | 会话名 | `NcrustSession` + `androidx.media3.session.id.` | 仅 `androidx.media3.session.id.` | ✅ | 同上 |
| 3 | media button session | `…/NcrustSession`（且与 media3 互跳） | `…/androidx.media3.session.id.`（唯一） | ✅ | `*/WVQ6*/S1-*.txt`、`CARD-AB.txt` §2 |
| 4 | 通知随歌词刷新 | 歌词行在变（`哈德斯别尔汗`→`Neighborhoods and kiks…`） | 歌词行在变（`你的心有一道墙 但我发现一扇窗`） | ✅ 无回归 | `*/WVQ6*/S2-notification-lyric-refresh.txt` |
| 5 | 通知条数 | 1 条（id=1，频道 `ncrust_playback`） | 1 条（同上） | ✅ 无回归 | `*/WVQ6*/S2-notification-full.txt` |
| 6 | 蓝牙 AVRCP / 耳机按键：播放暂停 | 127→2、126→3、85↔ 正常 | 同样正常 | ✅ 无回归 | `*/WVQ6*/S3-media-keys.txt`、`*/0715*/S3-media-keys.txt` |
| 7 | 蓝牙 AVRCP / 耳机按键：**下一首** | 晴天 → 屋顶（队列前进） | 屋顶 → 晴天（队列前进） | ✅ 无回归 | `*/0715*/S3-media-keys.txt` NEXT(87) 前后 `titles` |
| 8 | 上一首 | 屋顶 → 晴天 | 正常 | ✅ 无回归 | 同上 PREVIOUS(88) |
| 9 | HEADSETHOOK(79) | 播放↔暂停 | 播放↔暂停 | ✅ 无回归 | 两机 `S3` |
| 10 | 播放/暂停立即刷新 | 暂停后 `state=2` | 暂停后 `state=2` | ✅ 无回归 | `*/WVQ6*/S4-immediate-refresh.txt` |
| 11 | **暂停时 speed 是否谎报** | `state=2, speed=1.0`（legacy 会话把 speed 硬写成 1f） | `state=2, **speed=0.0**` | ✅ **修好一个既有 bug** | 同上 |
| 12 | 息屏控制 | 熄屏下 暂停→2、播放→3 | 熄屏下 暂停→2、播放→3 | ✅ 无回归 | `*/WVQ6*/S5-screen-off.txt`、`*/0715*/S5-screen-off.txt` |
| 13 | 锁屏 | 已采（截图 + uiautomator） | 已采 | ⚠️ 见 §5 未验证项 | `*/S8-lockscreen.{txt,xml,png}` |
| 14 | 非华为媒体面板（AOSP 链） | 会话 metadata 完整 | 会话 metadata 完整 | ✅ 无回归 | `*/S7-aosp-media-panel.txt` |
| 15 | 会话 metadata 是否打断播放 | — | 位置单调推进 221609→227707，`state=3`，无 BUFFERING | ✅ 假设成立 | 见 §3 |
| 16 | **华为控制中心媒体卡** | **未在播放** ❌ | **未在播放** ❌ | ❌ **未修好**（且非本版目标） | `*/WVQ6*/CARD-AB.txt` |
| 17 | 华为对 Ncrust 的白名单判定 | `not in media white list` ×2 | 仍然被拒 ×1 | — 预期内 | `*/WVQ6*/CARD-AB.txt` §5 |

「改动前 / 改动后」两列的每一格都能在对应文件里找到原文行；`*` = `baseline` 或 `after`。

### 2.1 第 1 行（会话条数）的原文对照

```
# baseline（v2.1.5）  ===== 本包会话条数 = 2
    NcrustSession                 com.takahashirinta.ncrust/NcrustSession (userId=0)
    androidx.media3.session.id.   com.takahashirinta.ncrust/androidx.media3.session.id. (userId=0)

# after（v2.1.6）     ===== 本包会话条数 = 1
    androidx.media3.session.id.   com.takahashirinta.ncrust/androidx.media3.session.id. (userId=0)
```

---

## 3. 承重假设的真机验证：`replaceMediaItem` 不会打断播放

会话合并后，歌词行每推进一次就要重发一次会话 metadata，实现上是
`player.replaceMediaItem(当前项, 只换 metadata)`。**这是整个改动里唯一一个「错了会很难看」的假设** ——
如果 ExoPlayer 因此重建 MediaSource，就会每隔几秒重新缓冲一次。

真机实测（WGR-W09，v2.1.6，连续播放跨多个歌词行）：

```
state=PlaybackState {state=3, position=221609, ...}
state=PlaybackState {state=3, position=221609, ...}
state=PlaybackState {state=3, position=227707, ...}
state=PlaybackState {state=3, position=227707, ...}
```

- position **单调推进**，没有回退；
- 全程 `state=3`，**没有出现 BUFFERING(6)**；
- 同期 `S2-notification-lyric-refresh.txt` 记录到歌词行在变（即 metadata 确实在重发）。

⇒ 结论：localConfiguration 未变时 media3 复用原 MediaSource，只换 mediaItem 引用。假设成立。

### 3.1 回退开关

`ncrust_settings` 的 `session_metadata_lyrics`（缺 key 即默认 `true`）可以切到「会话只讲歌名/艺人」。
纯逻辑抽在 [`MediaSessionMerge.kt`](../../../app/src/main/java/com/takahashirinta/ncrust/player/MediaSessionMerge.kt)，
9 条 JVM 单测覆盖（含「开关关闭时无歌词的歌与打开完全等价」与「通知那一份不受开关影响」）。
**这是为真机 A/B 留的开关，默认值 = 与 v2.1.5 逐字节一致的行为。**

---

## 4. 华为控制中心媒体卡：为什么没修好，以及为什么不意外

**完整取证见 [`p1-huawei-whitelist/whitelist-criterion.md`](p1-huawei-whitelist/whitelist-criterion.md)。** 摘要：

- 判据是 **ROM 只读分区里的一份包名白名单**：
  `/system/emui/base/thirdappfilter/third_app_filter.xml`（md5 `9264d05edad364d3de6c4b75ababaf5d`，
  `root:root 0644`，2018 年出厂镜像）→ `<feature name="mediaplaybackcontroller">` →
  `<function name="mediasession">` 共 **51** 条 `<enable>`。
- 名单里**有** `com.netease.cloudmusic`、`com.huawei.music`、`com.ss.android.ugc.aweme`；
  **没有**任何 ncrust / takahashirinta。
- 「唯一准入判据」已由实验证死：名单内、**用户自己安装的第三方**抖音在同一台设备上
  `media white list` 拒绝日志 = **0 次**；Ncrust 反复被拒。
  （`p1-huawei-whitelist/H1-falsification-douyin.txt`）
- 判据**不是**签名（名单里华为签与几十个第三方签并存）、**不是**清单声明
  （华为音乐连 `MediaBrowserService` 都没声明也上卡，Ncrust 反而是三家里声明最全的）、
  **不是** session 条数 / metadata（判据函数只取 `getPackageName()` 做字符串比较）。

### 4.1 同脚本、同场景的 A/B（这是本报告最该看的两行）

| | 基线（v2.1.5） | 改动后（v2.1.6） |
|---|---|---|
| 被测应用是否在播 | `state=3 3`，MBS=`…/NcrustSession` | `state=3`，MBS=`…/androidx.media3.session.id.` |
| 采集期间歌词是否在推进 | `其实你不是不爱了吧` → `就骗骗我也可以` | `好时光都该被宝贝 因为有限` → `Wooh 第一次遇见阴天遮住你侧脸` |
| **控制中心卡片** | `music_item content-desc="未在播放"` ❌ | `music_item content-desc="未在播放"` ❌ |
| ROM 白名单判定 | `not in media white list` ×2 | `not in media white list` ×1 |

两边的**前置与后置校验都通过**（起播前 / 采完后被测应用都在播），所以这不是「采到了别人」的假阴性。
证据：`baseline/WVQ6R22124000968/CARD-AB.txt`、`after/WVQ6R22124000968/CARD-AB.txt`（含 uiautomator 原文与截图）。

> 采集过程本身踩到两个真机坑，已写进脚本注释，避免下次重复：
> ① WGR-W09 的 ROM 有 `startHistoryMediaApp` 行为 —— 一旦没有白名单内的应用在播，它会把 ncm
> 拉起来播，于是卡片显示的是**ncm**（连续两次采到 `Dangerous Michael Jackson`）；
> ② `input tap/swipe` 用的是**当前朝向**的帧缓冲坐标，而帧缓冲会在 1600x2560 / 2560x1600 之间翻转。
> 两条都靠「采完复核被测应用是否仍在播」+ 按朝向现算坐标解决。

### 4.2 有没有别的路（已调研，结论：没有应用侧的路）

| 方向 | 可行性 | 依据 |
|---|---|---|
| 应用侧自我声明（权限/清单/MediaStyle/priority） | ❌ | 华为音乐声明比 Ncrust 少也上卡；判据只比包名 |
| 合并会话（本版做的） | ❌ 对卡片无效 | 判据不读 session |
| `hasMediaPermission` 旁路 | ❌ | 需系统属性 `hwouc.media_controller_enable=enable` **且**系统级测试包 `com.huawei.mediacontroller.plrdtest`，本机两项都不成立 |
| 改包名冒充名单内应用 | ❌ **不做** | 会顶掉真正的 ncm（Android 不允许同包名共存）⇒ 先毁掉唯一的 A/B 反例；且违反任务书铁律「不改包名、不冒用身份、不蹭别家会话标识」 |
| hook 成「ncm 的调用」 | ❌ 语义上不成立 | MediaSession 的包身份来自**拥有它的进程 UID**，不是可填的字符串；只能注入 ncm 进程（恶意软件行为，且对方一升级即失效） |
| 设备侧覆盖白名单（root / Magisk） | ⚠️ 唯一诚实的路，**未验证** | COTA 覆盖目录 `/data/cota/para/thirdappfilter` 本机不存在且 `root:root 0755`；`/system` 只读。改由用户在自己设备上做，不改包名不冒用。**本版未采用、未验证** |

结论的合规写法（满足「写能力边界必须先给反例解释」这条铁律）：

> 控制中心媒体卡对第三方应用是开放的（官方 ncm、用户自装的抖音都正常），
> 但它采用「ROM 预置包名白名单」而不是「应用自我声明」；名单之外的包名一律被
> `isInMediaSessionOrStyleList(...,2)` 拒绝。`com.takahashirinta.ncrust` 不在名单内，
> 且名单位于只读系统分区、无面向开发者的自助入口 ⇒ 就「出现在华为控制中心媒体卡」这一具体
> 能力而言，对 Ncrust 构成**硬边界**。这是**预置名单型**的边界，不是「拒绝第三方」型的边界。

---

## 5. 未验证 / 未取到（如实列出，不当结论用）

| # | 项 | 状态 |
|---|---|---|
| U1 | **车机（Android Auto / Automotive）** | 无设备。改动**动了**这条路径的相关代码（`onGetLibraryRoot` 所在的同一个 Callback 对象新增了 `onPlayerCommandRequest`），只做了代码级审查 + 单测，**未真机验证** |
| U2 | **小米 / vivo 控制中心** | 无设备，未验证 |
| U3 | **Pixel / AOSP 媒体面板** | 无设备；用 `S7-aosp-media-panel.txt`（PCL110 的 `MediaDataManager` 链）作代理观测，**不等于** Pixel 真机 |
| U4 | **蓝牙 AVRCP 真链路** | 无蓝牙音频外设。用 `input keyevent`（InputManager → MediaSessionService → media button session）作**代理观测**；这是同一条平台路径，但不是真蓝牙链路 |
| U5 | **耳机按键双击/长按** | 双击/长按由耳机固件与 ROM 识别，应用只收 keycode。只验证了单击（HEADSETHOID 79） |
| U6 | 锁屏歌词的**肉眼**确认 | 已采截图与 uiautomator，但本机锁屏有密码，未做端到端肉眼确认 |
| U7 | OPPO PLC110（ColorOS / API 36）整表 | 基线与改动后都**没有取到有效整表**：该机的 ROM 同样会把上一个媒体应用（qm/哔哩哔哩）拉起来抢焦点，脚本的前置闸如实中止（`*/3B15CD00GB700000/PRECONDITION-FAILED.txt`）。**未验证，不当作通过。** 该机只验证了「安装与启动正常」 |
| U8 | 设备侧白名单覆盖（root / Magisk） | 用户已决定暂停此路线。仓库里保留了模块生成器与模块本体，**全部标注「未在真机验证」**，不随本次 release 出货 |
| U9 | 华为 Rom 的 `MediaControlUtils.checkVersion` 边界 | 名单里 `version="0"` 为通配已由抖音（40.6.0 vs 名单 25.3.0）侧证；但版本比较的完整规则未逐条逆完 |

---

## 6. 复现方式

```bash
# 回归表（基线 / 改动后共用同一份脚本）
cd docs/verification/v2.1.6/regression
./capture.sh <serial> baseline      # 装旧版时跑
./capture.sh <serial> after         # 装新版时跑

# 华为卡片定点探针（含隔离、前置校验、采完复核、白名单判定日志）
./card-ab.sh <serial> baseline
./card-ab.sh <serial> after
```

两道**前提闸**（脚本内置，防止把别的应用的证据当成 Ncrust 的）：

1. 被测应用必须有自己的会话且 `state=3`（按「会话头 = 一行 `名字 包名/类名`」切块，
   不在块之间串 state）；
2. 该机若打印 `Media button session is …`（API 24 不打印、已如实记录跳过），
   它必须是被测应用。

任一不成立就写 `PRECONDITION-FAILED.txt` 并以 exit 3 中止 —— **宁可没有数据，不要错的数据。**
这条闸在本次采集里真的拦下过两次污染（ncm 抢焦点、S6 降档瞬态 ERROR），不是摆设。

---

## 7. 构建与测试

| 项 | 命令 | 结果 |
|---|---|---|
| JVM 单测 | `./gradlew testDebugUnitTest` | **596 通过 / 0 失败**（含新增 `MediaSessionMergeTest` 9 条） |
| Lint | `./gradlew lint` | **0 error**（6 warning / 2 info） |
| Debug 包 | `./gradlew assembleDebug` | 成功，`versionCode=36 / versionName=2.1.6-gpl` |
| Release 包 | `./gradlew assembleRelease` | 成功，`aapt2 dump badging` 见上 |

真机装机（三台，均为 `install -r` 覆盖安装，**数据与登录态未丢**）：

| 设备 | 装的是 | 说明 |
|---|---|---|
| WVQ6R22124000968（WGR-W09） | release | 与已装 v2.1.5 同为项目签名，直接覆盖 |
| 0715f763f54c023a（SM-G9209） | **debug** | 该机原本装的就是 debug 签名 |
| 3B15CD00GB700000（PLC110） | release | 同为项目签名 |

> 版本号来源：`bash tools/next-version.sh --no-fetch` ⇒ 三源最大值 35
> （`dist/Ncrust-v2.1.5-gpl-*.apk` 的 `aapt2 dump badging`）⇒ 本版 **36**。
