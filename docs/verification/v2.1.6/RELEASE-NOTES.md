# Ncrust v2.1.6-gpl 发布说明

> **版本**：`2.1.6-gpl` / **versionCode 36** / patch（媒体承重结构改造：会话合并）
> **基线**：v2.1.5-gpl（versionCode 35）
> **定号依据**：`tools/next-version.sh --no-fetch` 三源交叉验证最大值为 35
> （最近 5 个 tag、`dist/` 里 60 个 APK 的 `aapt2 dump badging`、仓库当前 `build.gradle.kts`）
> ⇒ 本版取 **36**。
> **发布纪律**：已发布过的 tag **不移动**；本版是新 tag。

---

## 0. 一句话

Ncrust 同时向系统暴露了**两条** MediaSession（`NcrustSession` 与
`androidx.media3.session.id.`），两条都是 active，于是 ROM 的「当前播放器」在两者之间反复横跳：

```
I MediaSessionService: Media button session is changed to …/androidx.media3.session.id.
I MediaSessionService: Media button session is changed to …/NcrustSession
```

对照：官方 ncm**只有一条** `com.netease.cloudmusic/MediaSession`；华为音乐**也只有一条**。
本版把两条合成一条 —— 系统从此只看到一个「当前播放器」。

**同时必须说清楚：这一版没有修好华为控制中心媒体卡，而且它本来就不该由这一版修好。**
根因已查明是 ROM 侧的一份**包名白名单**，与 session 条数无关。详见 §5。

---

## 1. 根因

### 1.1 两条会话、两个真相源

`PlaybackService` 原先同时持有：

| 会话 | 谁建的 | metadata 来源 | 谁在用 |
|---|---|---|---|
| `NcrustSession` | 应用自己 `MediaSessionCompat` | 应用字段 [mediaTitle]/[mediaArtist]（所以**有歌词**） | 通知栏、锁屏 |
| `androidx.media3.session.id.` | media3 `MediaLibrarySession` | 当前 `MediaItem` 的 metadata | 车机、部分 ROM 的控制中心 |

两条都 `active=true`、都注册了 media button receiver。系统的
`MediaSessionService` 只能有一个「media button session」，于是它在两者之间跳。

这不只是日志难看：**「谁是当前播放器」正是控制中心媒体卡与蓝牙 AVRCP 要回答的第一个问题**，
也是「别的应用起播时该暂停谁」的判据。

### 1.2 metadata 也讲两套话

同一时刻两条会话对系统说的是不同的事（v2.1.5 真机 dumpsys 原文）：

```
NcrustSession                metadata: size=5,  description=别讲想念我 我会受不了这样, 修炼爱情 · 林俊杰
androidx.media3.session.id.  metadata: size=10, description=修炼爱情, 林俊杰
```

v2.1.5 已经把「当前播放项补全 metadata」修掉了（media3 那条从 `size=3, null` 变成 `size=10`），
但两条会话的存在本身没动。

---

## 2. 改法（三处承重点，缺一不可）

### 2.1 删掉 legacy 会话

`MediaSessionCompat` 及其 `setMetadata` / `setPlaybackState` / `isActive` / `release` 全部移除，
只留 media3 那一条。

### 2.2 通知的 MediaStyle 改指向合并后的会话

```kotlin
androidx.media.app.NotificationCompat.MediaStyle()
    .setMediaSession(mediaSession?.sessionCompatToken)   // 以前是 mediaSessionCompat?.sessionToken
```

`getSessionCompatToken()` 是 media3 官方给「应用自己发 MediaStyle 通知」留的口子，
返回的就是这条 media3 会话的 `MediaSessionCompat.Token`。通知栏、锁屏、车机、控制中心
从此引用**同一条**会话。

### 2.3 上一首/下一首必须由应用回答（最容易改错的一处）

合并前，legacy 会话的 `Callback.onSkipToNext` 接的是 `onPlaybackEnded`（最终走 `MainScreen` 的**队列**操作）。
删掉它之后如果不补，media3 的默认实现会把 `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` 当成
`player.seekToNextMediaItem()` —— 而 **ExoPlayer 的播放列表不是用户的播放队列**：
按 v1.5.2 的待播槽位不变量，它里面只有「当前项 + 至多一首预载项」。

那样「下一首」会退化成「跳到那首预载的歌」，而 `currentQueueIndex` / `PlaybackStateManager` /
歌词全停在上一首 —— 正是用户报过的串台。

所以新增 `onPlayerCommandRequest` 拦截：

| 命令 | 去向 |
|---|---|
| `COMMAND_SEEK_TO_NEXT_MEDIA_ITEM` / `COMMAND_SEEK_TO_NEXT` | `onPlaybackEnded`（应用队列） |
| `COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM` / `COMMAND_SEEK_TO_PREVIOUS` | `onPlaybackPrevious`（应用队列） |
| 其余（播放/暂停/seek/stop/…） | `super` —— 保持 media3 自己的行为 |

这张表抽成纯逻辑 `MediaSessionMerge.route()`，9 条 JVM 单测钉住（含「少接一条命令」与
「上一下一写反」两种静默错误）。

### 2.4 会话 metadata 改从当前 MediaItem 写

media3 没有「直接给会话 setMetadata」的公开 API —— 会话的 metadata 就是
`Player.getMediaMetadata()`，等于当前 `MediaItem.mediaMetadata`。所以歌词行改由
`player.replaceMediaItem(当前项, 只换 metadata)` 写入。

**「替换当前项只换 metadata、不会重新缓冲」是 media3 的行为假设**，本版用真机数据验证了它（§3.2）。

### 2.5 保留回退开关

`ncrust_settings` 的 `session_metadata_lyrics`（缺 key 即默认 `true`，与 v2.1.5 逐字节一致）
可切到「会话只讲歌名/艺人」。纯逻辑抽在 `MediaSessionMerge.sessionLines()` 并有单测覆盖。
**这不是缓存字段，缺 key 就是默认值，因此没有数据迁移。**

---

## 3. 验证

全部证据在 `docs/verification/v2.1.6/`，总报告
[`REGRESSION.md`](https://github.com/yaxiaiyuting/Ncrust/blob/v2.1.6-gpl/docs/verification/v2.1.6/REGRESSION.md)。
基线/改动后**共用同一份脚本、同一批场景**。

### 3.1 回归表（摘要）

设备：WGR-W09（HarmonyOS 4.2 / API 31）、SM-G9209（Android 7.0 / API 24，非华为）。

| 路径 | 改动前 | 改动后 | 判定 |
|---|---|---|---|
| 本包 MediaSession 条数 | **2** | **1** | ✅ 验收标准达成 |
| 通知随歌词刷新 | 歌词行在变 | 歌词行在变 | ✅ 无回归 |
| 蓝牙/耳机按键：播放暂停 | 正常 | 正常 | ✅ 无回归 |
| 蓝牙/耳机按键：**下一首** | 晴天 → 屋顶 | 屋顶 → 晴天 | ✅ 队列正确前进 |
| 上一首 | 正常 | 正常 | ✅ 无回归 |
| 播放/暂停立即刷新 | 暂停后 `state=2` | 暂停后 `state=2` | ✅ 无回归 |
| **暂停时 speed** | `state=2, speed=1.0`（谎报） | `state=2, **speed=0.0**` | ✅ **顺手修掉一个既有 bug** |
| 息屏控制 | 熄屏下可控 | 熄屏下可控 | ✅ 无回归 |
| 非华为媒体面板链 | 正常 | 正常 | ✅ 无回归 |

### 3.2 承重假设的真机验证：歌词刷新不打断播放

歌词行每推进一次就重发一次会话 metadata（即一次 `replaceMediaItem`）。
实测连续播放跨多个歌词行：position 单调推进 `221609 → 227707`，全程 `state=3`，
**没有一次 BUFFERING** ⇒ media3 在 localConfiguration 未变时复用原 MediaSource。假设成立。

### 3.3 构建与测试

- `./gradlew testDebugUnitTest`：**596 通过 / 0 失败**（含新增 `MediaSessionMergeTest` 9 条）
- `./gradlew lint`：**0 error**
- `assembleDebug` / `assembleRelease`：成功
- 三台真机覆盖安装（`install -r`，签名与旧版相同）**数据与登录态未丢**

---

## 4. 未验证 / 已知问题（如实标注）

1. **车机（Android Auto / Automotive）未真机验证。** 本版**动了**这条路径所在的 Callback 对象
   （新增 `onPlayerCommandRequest`）。只做了代码级审查 + 单测，**没有车机可测**。
2. **小米 / vivo 控制中心、Pixel / AOSP 媒体面板未验证**（无设备）。非华为侧只用
   PCL110 的 `MediaDataManager` 链与三星 API 24 作代理观测。
3. **蓝牙 AVRCP 是代理观测，不是真蓝牙链路。** 没有蓝牙音频外设，用
   `input keyevent`（InputManager → MediaSessionService → media button session）注入，
   与 AVRCP 在平台侧走同一条路径，但不等价于真链路。
4. **耳机按键只验证了单击**（HEADSETHOOK 79）。双击/长按由耳机固件与 ROM 识别，应用只收 keycode。
5. **锁屏歌词未做肉眼端到端确认**（已采截图与 uiautomator，但本机锁屏有密码）。
6. **OPPO PLC110（ColorOS / API 36）未取到有效回归整表。** 该机 ROM 同样会把上一个媒体应用
   （qm / 哔哩哔哩）拉起来抢焦点，脚本的两道前提闸如实中止并留下
   `PRECONDITION-FAILED.txt`。**该机只验证了「安装与启动正常」，不当作通过。**
7. **设备侧白名单覆盖（root / Magisk）未验证**，且已决定**暂停这条路线**（华为机型 root 成本极高）。
   仓库里保留的模块生成器与模块本体**全部标注「未在真机验证」**，不随本次发布出货。
8. 华为 `MediaControlUtils.checkVersion` 的版本比较规则未逐条逆完
   （`version="0"` 为通配已由抖音侧证：40.6.0 vs 名单 25.3.0 仍然通过）。

---

## 5. 华为控制中心媒体卡：根因已查明，本版**没有**修好它

**完整取证**：
[`p1-huawei-whitelist/whitelist-criterion.md`](https://github.com/yaxiaiyuting/Ncrust/blob/v2.1.6-gpl/docs/verification/v2.1.6/p1-huawei-whitelist/whitelist-criterion.md)。

### 5.1 判据是 ROM 里的一份包名白名单

生效文件 `/system/emui/base/thirdappfilter/third_app_filter.xml`
（md5 `9264d05edad364d3de6c4b75ababaf5d`，`root:root 0644`，2018 年出厂镜像）
→ `<feature name="mediaplaybackcontroller">` → `<function name="mediasession">`，共 **51** 条 `<enable>`。

- **名单里**：`com.netease.cloudmusic`、`com.huawei.music`、`com.ss.android.ugc.aweme` …
- **名单里没有**：任何 ncrust / takahashirinta

打印 `this app is not in media white list` 的正是 `MediaControlUtils`，而承载这个文件名的静态常量
`CONFIG_FILE_WHITE_BLACK_APP` 就在**同一个类**上（`hwEmui.jar` dexdump 原文）—— 调用链同一实体，
不是相关性。判定函数 `isInMediaSessionOrStyleList(pkg, ctx, uid, 2)` **只取 `getPackageName()`
做字符串比较**。

### 5.2 「唯一判据就是包名」已被实验证死

用名单里、**用户自己安装的第三方**抖音（`com.ss.android.ugc.aweme`，装机 40.6.0 vs 名单 25.3.0）
在同一台设备上做 A/B：

| 应用 | 在名单里 | `media white list` 拒绝日志 |
|---|---|---|
| 抖音（用户自装第三方，非华为签名） | ✅ | **0 次** |
| Ncrust | ❌ | **反复被拒**（两个打印点都打） |

这一步同时排掉了「只认华为预置应用」与「只认华为签名」两种解释。
证据：`p1-huawei-whitelist/H1-falsification-douyin.txt`。

同时排除的还有：**签名**（名单里华为签与几十个第三方签并存）、**清单声明**
（华为音乐连 `MediaBrowserService` 都没声明也上卡，Ncrust 反而是三家里声明最全的）、
**session 条数 / metadata**（判据函数不读这两样）。

### 5.3 同脚本 A/B：合并会话**没有**改变卡片

| | 基线（v2.1.5） | 改动后（v2.1.6） |
|---|---|---|
| 被测应用在播 | `state=3 3`，MBS=`…/NcrustSession` | `state=3`，MBS=`…/androidx.media3.session.id.` |
| 采集期间歌词在推进 | ✅ | ✅ |
| **控制中心卡片** | `music_item content-desc="未在播放"` ❌ | `music_item content-desc="未在播放"` ❌ |

两边的**前置与后置校验都通过**（起播前 / 采完后被测应用都在播），所以这不是采到了别人的假阴性。
uiautomator 原文与截图在 `baseline|after/WVQ6R22124000968/CARD-AB.{txt,xml,png}`。

### 5.4 有没有别的路

| 方向 | 可行性 |
|---|---|
| 应用侧自我声明（权限 / 清单 / MediaStyle / priority） | ❌ 判据只比包名 |
| 合并会话（本版做的） | ❌ 对卡片无效，但对锁屏/蓝牙/车机的「唯一当前播放器」有价值 |
| `hasMediaPermission` 旁路 | ❌ 需系统属性 `hwouc.media_controller_enable=enable` **且**系统级测试包 `com.huawei.mediacontroller.plrdtest`，本机两项都不成立 |
| 改包名冒充名单内应用 | ❌ **不做**。会顶掉真正的 ncm（Android 不允许同包名共存）⇒ 先毁掉唯一的 A/B 反例；且违反本项目铁律 |
| hook 成「ncm 的调用」 | ❌ 语义上不成立。MediaSession 的包身份来自**拥有它的进程 UID**，不是可填的字符串 |
| 设备侧覆盖白名单（root / Magisk） | ⚠️ 唯一不冒用身份的路，但**未验证**，且已决定暂停 |

### 5.5 结论的合规写法

> 控制中心媒体卡对第三方应用是开放的（官方 ncm、用户自装的抖音都正常），
> 但它采用「ROM 预置包名白名单」而不是「应用自我声明」；名单之外的包名一律被
> `isInMediaSessionOrStyleList(...,2)` 拒绝。`com.takahashirinta.ncrust` 不在名单内，
> 且名单位于只读系统分区、无面向开发者的自助入口 ⇒ 就「出现在华为控制中心媒体卡」
> 这一具体能力而言，对 Ncrust 构成**硬边界**。
> 这是**预置名单型**的边界，不是「拒绝第三方」型的边界。

---

## 6. 装机验证（release 包，真机）

| 设备 | 系统 | 结果 |
|---|---|---|
| WGR-W09（华为平板） | Android 12 / API 31 | 35 → 36 **覆盖安装成功**，数据与登录态保留 |
| PLC110 | Android 16 / API 36 | 35 → 36 **覆盖安装成功** |
| SM-G9209 | Android 7.0 / API 24 | 装的是 **debug** 包（该机原本就是 debug 签名），34 → 36 覆盖成功 |

签名证书 SHA-256 与 v2.1.5 相同（`e75af3ff…5511`），老用户可直接覆盖安装。

---

## 7. 回归面（明确未触碰）

- **不改包名、不冒用任何应用的身份、不蹭别家会话标识**；
- 通知条数仍是 1 条（id=1 / 频道 `ncrust_playback`），v2.0.2 修好的「不让 media3 自己再发一条」
  （`onUpdateNotification` 覆写）**一个字节未改**；
- 歌词缓存的字段与磁盘 key 形状未动 ⇒ **没有任何数据迁移**；
- `applicationId`、签名配置、权限清单未动；
- `PreloadSlot` 的待播槽位不变量（v1.5.2 串台修复）未动；
- 新增的 `session_metadata_lyrics` 是**偏好项**不是缓存字段，缺 key 即默认值。
