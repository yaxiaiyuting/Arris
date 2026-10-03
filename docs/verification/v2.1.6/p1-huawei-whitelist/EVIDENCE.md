# EVIDENCE —— 华为控制中心媒体卡准入判据（v2.1.6 · P1）

设备（本任务主战场）：`WVQ6R22124000968` · HUAWEI WGR-W09 · HarmonyOS 4.2 / EMUI 14.2.0 / Android 12 / API 31
ROM：`WGR-W09 4.2.0.213(C00E100R3P9)` · 内核 `5.10.43` · `ro.board.platform=kirin9000E` · `ro.build.type=user`
**root：无**（`su` 不存在 exit=127；`adb root` → `adbd cannot run as root in production builds`）
采集窗口：2026-09-25 12:56:25 – 13:08（CST）
输出根目录：`docs/verification/v2.1.6/p1-huawei-whitelist/`

> 本文件把每一份证据的 **采集命令原文 / 采集时间 / 设备序列号 / 原始输出路径** 对齐；
> 每条结论标注 **【实测】**（有落盘原文）或 **【推断】**（推断链在 `whitelist-criterion.md` §8.2）。
> 拿不到的一律写「拿不到 + 原因」，见 §D。

---

## A. 静态清单对比（→ `manifest-diff.md`）

| 证据 | 采集命令（原文） | 时间 | 设备 | 输出路径 |
|---|---|---|---|---|
| 三个 APK 的路径 | `adb -s WVQ6R22124000968 shell pm path <pkg>` | 12:57 | WVQ6R22124000968 | `manifest-diff.md` §0 |
| 三个 APK 本体 | `adb -s WVQ6R22124000968 pull <pm path> tmp/apks/<tag>-base.apk` | 12:57 | 同上 | 已删除，sha256 见 `03-pulled-binaries-sha256.txt` |
| 清单（AXML 反解） | `/home/duanjb666/Android/sdk/build-tools/35.0.0/aapt2 dump xmltree --file AndroidManifest.xml <apk>` | 12:57 | 主机侧 | `tmp/manifest/{netease,huaweimusic,ncrust}.manifest.xmltree.txt` |
| badging | `aapt2 dump badging <apk>` | 12:57 | 主机侧 | `tmp/manifest/*.badging.txt` |
| sdk/权限/指纹汇总 | `aapt2 dump badging \| grep -E '^(package\|sdkVersion\|targetSdkVersion\|…)'` + `apksigner verify --print-certs <apk>` | 12:57 | 主机侧 | `02-badging-and-certs.txt` |
| 结构化抽取 | `python3 tmp/parse2.py`（脚本已落盘） | 13:00 | 主机侧 | `tmp/manifest-summary.json`、`tmp/manifest-media-sections.txt` |

【实测】要点：三包**都**声明 `MEDIA_BUTTON`；华为音乐**没有**声明 `MediaBrowserService`/`MediaSessionService`；
Ncrust 声明最全（MediaSessionService + MediaBrowserService + FGS mediaPlayback）却被拒。
三包签名 SHA-256 两两不同。

---

## B. 动态观测（WGR-W09）

统一流程由脚本 `tmp/capture_app.sh` 执行（脚本原文已落盘），每应用的完整过程日志在 `tmp/capture-<tag>.log`。
统一前置：`am force-stop` 四个候选包 → `logcat -c` → `am start` → 轮询到 `state=3` → 采证 → `force-stop`。

### B-1 ncm `com.netease.cloudmusic` —— 【实测】有效

| 项 | 值 |
|---|---|
| 启动命令 | `adb -s WVQ6R22124000968 shell am start -n com.netease.cloudmusic/.activity.IconChangeDefaultAlias` |
| 播放触发 | `adb -s … shell input keyevent 126`（KEYCODE_MEDIA_PLAY） |
| 轮询结果 | `t=3s state=2` → `t=6s state=6` → **`t=9s state=3`**（缓冲后进入播放；state=3 = 正在播放） |
| 采集时间 | 13:02:30 – 13:02:54 |
| 会话原文 | `Media button session is com.netease.cloudmusic/MediaSession (userId=0)`；`state=PlaybackState {state=3, …}`；`metadata: size=11, description=Scarborough Fair / Canticle (斯卡布罗集市), Simon & Garfunkel, null` |
| **白名单判定原文** | `I MediaControlUtils: checkController is true` ×4，**无** `not in media white list` |
| 卡片原文 | `content-desc="The Final Countdown Europe"` |
| 文件 | `dumpsys-media-session-netease.txt`、`dumpsys-notification-netease.txt`、`logcat-hwmediasession-netease.txt`、`uiautomator-netease.xml`、`screenshots/netease-controlcenter.png`（sha256 `afe87d6f…`，人工看图复核：控制中心媒体卡显示 `The Final Countdown / Europe` + 封面 + 三个传输键）、`settings-global-media_button_receiver-netease.txt`、`tmp/capture-netease.log` |

【实测】`settings list global | grep -i media_button_receiver` → **无输出，exit=1**（该键不在 global）。

### B-2 华为音乐 `com.huawei.music` —— 【实测】⚠️ 证据无效（未在播放）

| 项 | 值 |
|---|---|
| 启动命令 | `adb -s … shell am start -n com.huawei.music/com.android.mediacenter.PageActivity` |
| 播放触发 | `input keyevent 126` ×2 |
| 轮询结果 | **60 秒全程 `state=2`（暂停）**，未取得 `state=3` |
| 采集时间 | 13:02:59 – 13:04:19 |
| 会话原文 | `com.android.mediacenter.mediasession com.huawei.music/com.android.mediacenter.mediasession (userId=0)` / `package=com.huawei.music` / `state=PlaybackState {state=2, …}` / `metadata: size=20, description=喜欢你, BEYOND, 金碟至尊精选` |
| 白名单判定原文 | **未采到**（`logcat-hwmediasession-huawei.txt` 仅 7 行，无 white-list 相关行） |
| 控制中心 | **未打开成功**——`uiautomator-huawei.xml` dump 到的是华为音乐自己的播放页（含 `打开歌词`/`已收藏` 等节点），不是控制中心 |
| 文件 | `dumpsys-media-session-huawei.txt`、`dumpsys-notification-huawei.txt`、`logcat-hwmediasession-huawei.txt`、`uiautomator-huawei.xml`、`screenshots/huawei-controlcenter-INVALID-black-frame.png`（❌ 纯黑帧，无效）、`settings-global-media_button_receiver-huawei.txt`、`tmp/capture-huawei.log` |

**判定：按任务规则「`state=3` 才是在播，否则记为未在播放状态、证据无效」→ 本条无效，不作为证据。**
未重跑原因：主会话同期在同一台设备跑 Ncrust 回归，重跑需要真正起播（会抢媒体焦点），
按协调要求让出设备。华为音乐卡片结论**沿用 v2.1.5**（`../../v2.1.5/p1-huawei-controlcenter/ui-dump-huawei-music-card.xml`）。

**附带【实测】**：华为音乐的会话 **id** 是 `com.android.mediacenter.mediasession`，而 **package 是 `com.huawei.music`**
—— 这两个不是一回事，`manifest-diff.md` §0 有专门说明。

### B-3 Ncrust `com.takahashirinta.ncrust` —— 【实测】部分（logcat 原文 + 既有 A/B）

| 项 | 值 |
|---|---|
| 播放态 A/B | **本轮未重跑**（与主会话 Ncrust 回归冲突，按协调让出设备）→ 沿用 v2.1.5 的三份 uiautomator + 截图 |
| 白名单拒绝原文 | `09-25 12:59:10.329 … V HwMediaSessionServiceInner: this app is not in media white list, pkgName: com.takahashirinta.ncrust`（同秒内两条） |
| 该次采集命令 | `adb -s WVQ6R22124000968 logcat -d \| grep -iE "HwMediaSession\|MediaControlUtils\|media white list\|Media button session\|MediaSessionService"` |
| 采集时间 | 12:59:10（**早于**主会话的协调要求） |
| 同时段会话原文 | `Media button session is com.takahashirinta.ncrust/NcrustSession (userId=0)`；`package=com.takahashirinta.ncrust` ×2；两条会话均 `state=PlaybackState {state=2, …}` |
| 文件 | `logcat-hwmediasession-ncrust.txt`、`settings-global-media_button_receiver-ncrust.txt` |

⚠️ **诚实性标注**：13:05 再执行同一条命令时，设备 logcat 环形缓冲（main **256 KiB**）已滚过，输出 **0 行**
（原文见 `tmp/logcat-rerun-check.txt`）。因此 `logcat-hwmediasession-ncrust.txt` 是**当时真实输出的落盘**，
不是事后重新生成的；文件头部已写明这一点。同样两行在 v2.1.5 的独立记录中也出现过
（`../../v2.1.5/p1-huawei-controlcenter/EVIDENCE.md:20-21`）。

### B-4 抖音 `com.ss.android.ugc.aweme` —— 【实测】❌ 采集失败/无效（原打算作为「另一个第三方」正例）

| 项 | 值 |
|---|---|
| 启动命令 | `adb -s … shell am start -n com.ss.android.ugc.aweme/.splash.SplashActivity` |
| 轮询结果 | 全程只在 `state=1`（停止）与空之间摆动，**从未 `state=3`** |
| 采集时间 | 13:00:56 – 13:02:13 |
| 失败原因（实测 logcat） | 抖音会话转入 idle 后，**ROM 自行把「历史媒体应用」ncm 拉起并开始播放**：`13:02:03.828 MediaSessionService: Audio playback is changed … u/pid:10235/24523 state:started` → `Media button session is changed to com.netease.cloudmusic/MediaSession`。于是采到的是 ncm 的会话与卡片 |
| logcat 里是否出现 aweme | **否**（`grep aweme logcat-hwmediasession-douyin.txt` 无命中）⇒ 抖音**没有**拿到任何白名单判定 |
| 两份 uiautomator 的关系 | `uiautomator-douyin.xml` 与 `uiautomator-netease.xml` **md5 完全相同**：`41c322c4337bd9be024b0c3fe53536a6`（76097 B；mtime 13:02:13 vs 13:02:53）。两者内容都是**ncm 的卡**（`content-desc="The Final Countdown Europe"`） |
| 判定 | **该条 A/B 无效，不作为证据**（它证明的是 ncm，不是抖音） |
| 文件 | `dumpsys-media-session-douyin.txt`、`dumpsys-notification-douyin.txt`、`logcat-hwmediasession-douyin.txt`、`uiautomator-douyin.xml`、`screenshots/douyin-controlcenter-WRONG-app-is-netease.png`（❌ 内容是 ncm 的卡）、`tmp/capture-douyin.log` |

> 附带【实测】的 ROM 行为：**当一个媒体会话转入 idle，EMUI 会把「历史媒体应用」拉起来播放。**
> 这条行为本身与白名单无关，但它会污染「逐个应用隔离采集」，后续做 A/B 时必须注意。

### B-5 `settings` 三次读取（本项要求的关键补充）

任务书要求 `settings list global | grep -i media_button_receiver`。**实测：global 里没有这个键**（exit=1）。
真正的键在 **`Settings.Secure`**：

```
$ adb -s WVQ6R22124000968 shell settings list secure | grep -i media_button_receiver
media_button_receiver=com.netease.cloudmusic/com.netease.cloudmusic.receiver.MediaButtonEventReceiver,0,1-\
com.netease.cloudmusic/com.netease.cloudmusic.receiver.MediaButtonEventReceiver,0,1-\
com.huawei.music/com.android.mediacenter.playback.systeminteract.MediaButtonIntentReceiver,0,1-\
com.netease.cloudmusic/com.netease.cloudmusic.module.webview.audio.WebMediaButtonReceiver,0,1
```

【实测】该列表里有 ncm（×3 条）与华为音乐（×1 条），**没有 Ncrust**——
尽管 Ncrust 在清单里声明了 `androidx.media3.session.MediaButtonReceiver`。

| 文件 | 内容 |
|---|---|
| `settings-global-media_button_receiver-netease.txt` / `-huawei.txt` / `-ncrust.txt` | 三个状态各一次的原文（global 无该键） |
| `settings-global-media_button_receiver-ALL.txt` | 汇总 + **secure** 命名空间的原文 + `settings list system/secure` 的 `music` 相关项 |
| `tmp/logcat-rerun-check.txt` | 13:05 重跑 logcat 过滤得到 0 行的原文 |

【推断】（不是已证，推断链见 `whitelist-criterion.md` §10）：这个 `secure` 键更像是 **AOSP 的
`MediaSessionService.Settings`（`KEY_MEDIA_BUTTON_RECEIVER`）** 机制（logcat 里同一毫秒出现
`rememberMediaButtonReceiverLocked`），即「记住上一个媒体按键接收者」，而不是华为白名单本身。

### B-6 截图有效性（人工看图复核，非推断）

| 文件 | 判定 | 依据 |
|---|---|---|
| `screenshots/netease-controlcenter.png`（625623 B，sha256 `afe87d6f…`） | ✅ 有效 | 看图可见「控制中心」标题、媒体卡 `The Final Countdown / Europe` + 专辑封面 + 上一首/播放/下一首 |
| `screenshots/huawei-controlcenter-INVALID-black-frame.png`（19838 B，sha256 `f269ebe8…`） | ❌ **无效** | 整幅纯黑，`screencap` 抓到空白帧；同一时刻控制中心也没打开成功（§B-2） |
| `screenshots/douyin-controlcenter-WRONG-app-is-netease.png`（437243 B，sha256 `0d3cbd54…`） | ❌ **无效** | 画面里是**ncm**的卡，不是抖音（§B-4 的污染） |

校验值文件：`05-screenshots-sha256.txt`。
**未做任何美化/裁剪**：无效的截图保留原文件、仅在文件名上标注无效，避免被误引。

---

## C. 逆向 system_server 侧（→ `whitelist-criterion.md`）

**前提【实测】**：WGR-W09 无 root（`01-root-probe.txt`），但 `/system/framework/*.jar` 是 `0644 root:root`
且 shell 域可读 → **pull + 本地 dexdump 不需要 root**。反汇编器：
`/home/duanjb666/Android/sdk/build-tools/35.0.0/dexdump`；设备与主机上**没有** jadx/baksmali/apktool（`which` 实测）。

| 步骤 | 命令（原文） | 时间 | 输出路径 |
|---|---|---|---|
| 定位字符串所在 jar | `adb -s … shell 'grep -l "not in media white list" /system/framework/*.jar'` | 12:56:46 | `10-static-locate-string.txt` |
| 白名单包名探针 | `adb -s … shell 'grep -l "<pkg>" /system/framework/*.jar'`（netease/qqmusic/kugou/…） | 12:57:03 | `11-static-whitelist-pkgname-probe.txt` |
| 定位白名单 XML | `adb -s … shell 'find / -name "hw_mediaplaybackcontroller_app_config.xml" 2>/dev/null'` 等 | 12:58:20 | `12-whitelist-xml-locate.txt` |
| XML 原文 | `adb -s … shell 'cat /system/emui/base/xml/hw_mediaplaybackcontroller_app_config.xml'`（32 条 `<whiteapp>`） | 12:58 | `13-hw_mediaplaybackcontroller_app_config.xml` |
| XML 原文 | `adb -s … shell 'cat /system/emui/base/thirdappfilter/third_app_filter.xml'`（276 KB 全文） | 12:58 | `14-third_app_filter.xml`（该段摘出：`tmp/mediaplaybackcontroller-feature.xml`） |
| COTA / 目录权限 | `ls -lR /data/cota`、`ls -ld /system/emui/base/*`、`mount \| grep ' /data '`、`md5sum <两个 XML>` | 13:05 | `15-cota-and-config-dir-probe.txt` |
| 旁路条件 | `getprop ro.board.platform`（=`kirin9000E`）、`getprop hwouc.media_controller_enable`（空）、`pm path com.huawei.mediacontroller.plrdtest`（不存在） | 13:07 | `16-bypass-probe.txt` |
| pull jar | `adb -s … pull /system/framework/{hwEmui,hwServices,framework,services,zframework.z}.jar tmp/rom/` | 12:57 | 已删除；sha256 + 设备侧 md5 见 `03-pulled-binaries-sha256.txt` |
| 解 dex | `(cd tmp/rom/x/<j> && unzip -o -q ../../../<j>.jar 'classes*.dex')` | 12:57 | 同上 |
| **反汇编** | `dexdump -d tmp/rom/x/hwEmui/classes.dex`、`…/hwServices/classes.dex`、`…/framework/classes4.dex` | 12:57–13:06 | 见下表 |
| 方法级抽取 | `awk -v M=<方法名> -f tmp/extract_all.awk <class>.dis.txt`（脚本已落盘） | 13:06 | 见下表 |

| 反汇编证据文件 | 内容（关键结论所在） |
|---|---|
| `20-decompile-MediaControlUtils.txt` | `com.huawei.android.media.session.MediaControlUtils` 的静态字段初值 + 21 个方法的完整 smali，含 `checkController`（打印点）、`isInMediaSessionOrStyleList`（判据入口）、`isInMediaWhiteListInner`、`checkMediaWhiteInfo`、`checkVersion`、`getCotaXmlFile`、`initHwMediaControllerWhiteList`、`initHwMediaControllerWhiteListFromCota`、`handleMediaPlayBackXml`、`handleMediaSessionXml`、`handleMediaSessionWhiteAppXml`、`hasMediaPermission`、`checkOption` |
| `21-decompile-HwMediaSessionServiceInner.txt` | `checkSessionRecord`（另一个打印点）全文 + 唯一调用者 `pauseOtherMedia` + 该类方法清单 |
| `22-decompile-HwMediaSessionServiceEx-filter.txt` | `isInMediaWhiteList`（转调 `isInMediaSessionOrStyleList(...,2)`）、`filterMediaInfos`（构建 `MediaPlayerInfoEx` 列表）+ 两处调用点的 grep 原文 |
| `23-decompile-HwCfgFilePolicy-cota.txt` | `huawei.cust.HwCfgFilePolicy.getDownloadCfgFile` 全文 + `COTAINFO_DIR="/data/cota/para/"` 等常量 |

**另有一处未保留的环境事实**（避免误引）：`dexdump` 的默认（非 `-d`）class/method 清单 dump 曾用于
定位类名（`tmp/rom/x/HwMediaSessionServiceEx.dexdump.txt` 保留；`hwServices.dexdump.txt` 49 MB 已删除）。

---

## D. 拿不到 / 未采到的（明确列出，无替代推断）

| # | 拿不到的 | 原因 |
|---|---|---|
| D1 | WGR-W09 的 root | 生产版 ROM：`su` 不存在（exit=127），`adb root` 被拒（`01-root-probe.txt`）。**未伪造**，改走「pull system jar + 本地反汇编」，实测可行 |
| D2 | 华为音乐播放态（`state=3`）证据 | `keyevent 126` ×2 未能起播（60 s 全为 `state=2`）；重跑需真正起播、会抢媒体焦点，按主会话协调要求让出设备（§B-2） |
| D3 | Ncrust 本轮的播放态 A/B | 同上（§B-3）；沿用 v2.1.5 的三份 uiautomator + 截图 |
| D4 | 抖音的 white-list 判定 | 抖音会话始终 `state=1`，随后 ROM 拉起 ncm → 采到的是 ncm（§B-4）。该条 A/B **判为无效**，两份 uiautomator md5 相同 |
| D5 | Ncrust 会话的 `mediaButtonReceiver=` 字段 | 需 Ncrust 进程存活时有会话；采集窗口结束时它已被 force-stop，而当前（13:07）设备上无 Ncrust 会话（`tmp/dumpsys-media-session-NOW.txt`）。按协调要求未再启动它 |
| D6 | `hw_mediaplaybackcontroller_app_config.xml` 的运行时读取点 | 该文件名常量由 `getHwCfgFileList` 使用，而 `getHwCfgFileList` 的唯一调用者是 `getPath`；**`getPath` 的调用者未展开** → 记为未定位（`whitelist-criterion.md` §8.3-U1） |
| D7 | `getCfgPolicyDir(0)` 展开出的目录清单 | 未反汇编该函数（§8.3-U2） |
| D8 | COTA 下发渠道的触发方式与签名校验 | 仅证明「本机没有 COTA 覆盖、目录不可写」（§3.5） |
| D9 | `com.huawei.music` 的 `split_card.apk` 清单 | 只 pull 了 base.apk 并对比其清单；split 的清单未展开（`manifest-diff.md` §7） |

---

## E. 与大体积二进制有关的落盘说明（诚实性）

`tmp/apks/*.apk`（约 389 MB）、`tmp/rom/*.jar`（约 98 MB）、解出的 `classes*.dex` 与
49 MB 的 `hwServices.dexdump.txt` 在 **sha256/md5 记录完成之后**已删除，以免把约 500 MB 二进制
塞进 git 工作区（本目录位于 `docs/`）。它们的 sha256 与设备侧 md5 交叉校验值在
`03-pulled-binaries-sha256.txt`，并且每条 `adb pull` 命令都在 §A/§C 有原文，可原样重新取回。
**所有文本级证据均保留、未删改**（清理后目录 6.1 MB）。

---

## F. 结论回溯表（结论 → 证据 → 命令）

| 结论 | 档位 | 证据路径 | 命令出处 |
|---|---|---|---|
| 两个打印点 = `HwMediaSessionServiceInner.checkSessionRecord` / `MediaControlUtils.checkController` | 已证 | `20-…txt`、`21-…txt` | §C 反汇编 |
| 判据 = 包名命中白名单 + `checkVersion` | 已证 | `20-…txt`（`isInMediaWhiteListInner`/`checkMediaWhiteInfo`/`checkVersion`） | §C |
| 白名单加载链 = `getCotaXmlFile` → `HwCfgFilePolicy.getDownloadCfgFile` → `third_app_filter.xml` 的 `<feature name="mediaplaybackcontroller">` | 已证 | `20-…txt`、`23-…txt` | §C |
| 本机名单含 ncm/华为音乐、不含 Ncrust | 已证 | `14-third_app_filter.xml`、`13-…xml`、`tmp/mediaplaybackcontroller-feature.xml` | §C |
| 本机无 COTA 覆盖、名单文件不可写 | 已证 | `15-cota-and-config-dir-probe.txt` | §C |
| 判据不是签名 / 不是权限 / 不是清单声明 | 已证 | `02-badging-and-certs.txt`、`manifest-diff.md` §2–§5 | §A |
| ncm 通过（`checkController is true` ×4，卡片正常） | 已证 | `logcat-hwmediasession-netease.txt`、`uiautomator-netease.xml`、`screenshots/netease-controlcenter.png`（sha256 `afe87d6f…`，人工看图复核：控制中心媒体卡显示 `The Final Countdown / Europe` + 封面 + 三个传输键） | §B-1 |
| Ncrust 被拒（logcat 原文 ×2） | 已证（时刻早于协调要求，见 §B-3 标注） | `logcat-hwmediasession-ncrust.txt` | §B-3 |
| 「名单外一律被拒」是唯一判据（无其它旁路） | 高置信推断 | `whitelist-criterion.md` §8.2-H1 | —— |
| 卡片列表 = `filterMediaInfos` 产出的 `MediaPlayerInfoEx` 列表 | 高置信推断 | `22-…txt` + `logcat-hwmediasession-douyin.txt` 的 `player infoList size: 1` | §C |
| `checkSessionRecord` 被拒 ⇒ 不会被 `pauseOtherMedia` 自动暂停 | 高置信推断 | `21-…txt` | §C |
| `secure.media_button_receiver` 是 AOSP 记住机制而非白名单 | 高置信推断 | `settings-global-media_button_receiver-ALL.txt` + `logcat-hwmediasession-douyin.txt` 的 `rememberMediaButtonReceiverLocked` | §B-5 |
| `hw_mediaplaybackcontroller_app_config.xml` 的实际读取点 | 未定位 | —— | §D-6 |
| 抖音能否通过白名单 | 未采到（A/B 无效） | `tmp/capture-douyin.log` | §B-4 |
| 华为音乐本轮卡片证据 | 未采到（state=2） | —— | §B-2 |
