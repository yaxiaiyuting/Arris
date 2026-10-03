# 华为控制中心媒体卡的准入判据 —— `this app is not in media white list` 溯源

设备：`WVQ6R22124000968` · HUAWEI WGR-W09 · HarmonyOS 4.2 / EMUI 14.2.0 / Android 12 / API 31
ROM：`WGR-W09 4.2.0.213(C00E100R3P9)`，内核 `5.10.43`，`ro.build.type=user`，**无 root**（见 §0）
采集时间：2026-09-25 12:56–13:06（CST）

> **结论摘要（一句话）**：准入判据是 **「包的 `packageName` 字符串是否出现在 ROM 预置白名单 XML 里，
> 且版本号通过 `checkVersion`」** —— 不是签名、不是权限、不是清单声明、不是会话数量、不是元数据。
> 那份 XML 在只读的 `/system` 上，普通应用无法写入，也没有面向开发者的自助入口。

---

## 0. 前提：WGR-W09 的 root 判定（先证伪，再谈方法）

| 命令（原文） | 输出 | 退出码 |
|---|---|---|
| `adb -s WVQ6R22124000968 shell su -c id` | `/system/bin/sh: su: inaccessible or not found` | 127 |
| `adb -s WVQ6R22124000968 root` | `adbd cannot run as root in production builds` | 0 |
| `adb -s WVQ6R22124000968 shell id` | `uid=2000(shell) … context=u:r:shell:s0` | 0 |
| `getprop ro.debuggable` / `ro.secure` / `ro.build.type` | `0` / `1` / `user` | —— |

⇒ **WGR-W09 无 root、无法 `adb root`**。原任务书 C 方案的降级条件成立。

**但 C 方案仍然做成了**：本机 `/system` 下的 jar 是 `root:root 0644`、`/system` 只读挂载，
而 **shell 域可以读它们**（v2.1.5 的 `t3` 脚本就曾用 `adb shell unzip -p /system/priv-app/SystemUI/SystemUI.apk`）。
因此「pull system jar → 本地 `dexdump -d` 反汇编」这条路**不需要 root**，也不需要改设备。
证据：`01-root-probe.txt`、`10-static-locate-string.txt`。

使用的反汇编器：`/home/duanjb666/Android/sdk/build-tools/35.0.0/dexdump`（`dexdump -d`）。
设备/主机上**没有** jadx / baksmali / apktool（`which` 实测），所以本文件全部是 **smali 级反汇编**，
不是 Java 反编译；下面每一处结论都贴了原始指令行。

---

## 1. 【已证】两个打印点：谁在打这句话

在 `/system/framework/*.jar` 上直接 `grep -l "not in media white list"`：

```
/system/framework/hwEmui.jar
/system/framework/hwServices.jar
```

各自 `dexdump -d` 后定位到**恰好两个**引用该字符串常量的方法：

| 打印者（logcat tag） | 类 | 方法 | 来源 |
|---|---|---|---|
| `HwMediaSessionServiceInner` | `com.android.server.media.HwMediaSessionServiceInner` | `checkSessionRecord(MediaSessionRecord)` | `hwServices.jar!classes.dex` @ `312768` |
| `MediaControlUtils` | `com.huawei.android.media.session.MediaControlUtils` | `checkController(MediaController, Context, int)` | `hwEmui.jar!classes.dex` @ `105dd2` |

反汇编原文：`21-decompile-HwMediaSessionServiceInner.txt`、`20-decompile-MediaControlUtils.txt`。

两者在拒绝时的形态完全一致（原文，`HwMediaSessionServiceInner.checkSessionRecord`）：

```
312752: invoke-static {v2, v3, v4, v5}, Lcom/huawei/android/media/session/MediaControlUtils;.isInMediaSessionOrStyleList:(Ljava/lang/String;Landroid/content/Context;II)Z
31275a: if-nez v2, 0035                 # 结果 == false ⇒ 进入下面的打印分支
312768: const-string/jumbo v3, "this app is not in media white list, pkgName: "
31277c: append(pkgName) ... Log.v(TAG, ...)
312790: return v0                       # v0 = 0 = false
```

`MediaControlUtils.checkController` 同构（`105dbc` 调同一个函数，`105dd2` 同一句日志，`105df8 return 0`）。

⇒ **两个日志点共用同一个判据函数** `isInMediaSessionOrStyleList(pkgName, context, userId, 2)`，
第四个参数 `2` = `IS_IN_MEDIA_WHITE_LIST_SERVICE_EX`（静态字段初值实测为 `2`）。

---

## 2. 【已证】判据函数链：从包名到「在不在名单里」

### 2.1 `isInMediaSessionOrStyleList`（`20-…txt`）

```
10646e: if-eqz v5, 0042        # pkgName == null → "pkgName or context is null"
106472: if-nez v6, 0008        # context != null 才继续
106478: hasMediaPermission(context, userId)
106486:   └─ 为真 ⇒ Log.i("isInMediaSessionOrStyleList has plrd") ; return true     ← 旁路 A
106492: SystemProperties.get("ro.board.platform","").equals("hmos_emulator")
1064b2:   └─ 为真 ⇒ return true                                                    ← 旁路 B（模拟器）
1064b4: if-nez v8, 002d        # queryType == 0  → isInMediaWhiteListInner(...)
1064c2: if-ne v8, 1, 0034      # queryType == 1  → isInMediaBlackListInner(...)
1064d0: if-ne v8, 2, 003c      # queryType == 2  → isInMediaWhiteListInner(pkg, ctx, uid, 2)
1064e0: 其它值 → Log.e("invalid queryType input in isInMediaWhiteList") ; return false
```

WGR-W09 实测 `ro.board.platform = kirin9000E`（≠ `hmos_emulator`）；
`hwouc.media_controller_enable` 实测为空串（≠ `enable`）；
`pm path com.huawei.mediacontroller.plrdtest` 无输出（exit=1，该测试包不存在）。
⇒ 对本例，旁路 A、B **均不成立**。原始输出：`16-bypass-probe.txt`。

### 2.2 `isInMediaWhiteListInner`（`20-…txt`）

```
1064f8  getVersionName(pkgName, context, userId)         # 取被查询应用的 versionName
106522  if (versionName != null) {
10652e      versionName.replaceAll("[^(0-9.)]", "").split("\\.")
106542  }
106546: monitor-enter LOCK
106548: sget-object v3, sMediaControllerWhiteList          ← 唯一的名单来源
106554: for (Map map : sMediaControllerWhiteList) {
10656c      checkMediaWhiteInfo(map, pkgName, versionName, versions, queryType)
106578        为真 ⇒ return true
106580: monitor-exit ; return false
```

⇒ **判定完全依赖静态字段 `sMediaControllerWhiteList`**（`Ljava/util/List;`，元素是 `Map`）。
它不看签名、不看权限、不看 session 数量、不看 metadata。

### 2.3 `checkMediaWhiteInfo`（逐条匹配规则，`20-…txt`）

```
105f80: "package".equals(key)
105f92:     pkgName.equals(value.trim())        # 精确字符串相等（trim 后）
105f9e:     versionName == null ⇒ Log.i("versionName is null, but in media white list") ; return true
105fa6:     "".equals(versionName) ⇒ 计数 +1，继续看 version
105fca: "version".equals(key)
105fee:     checkVersion(xmlVersion, appVersionArray)
105fde:     "0".equals(xmlVersion)             # version="0" 同时被当作通过
106010: "options".equals(key) 且非空 ⇒ checkOption(options)
106036: return (计数 == 2) ? true : false
```

### 2.4 `checkVersion`：**安装版本必须 ≥ 名单里的版本**（`20-…txt`）

```
1060ca: 名单版本 replaceAll("[^(0-9.)]","").split("\\.")
1060ea: min = Math.min(listVer.length, appVer.length)
106112: 逐段比较；首个不同段 v8 <= v7 ⇒ return false
106138: 全部相同 ⇒ listVer 更长则 false，否则 true
106128: NumberFormatException ⇒ Log.w("parseInt failed.") ; return false
```

⇒ XML 里的 `version="7.3.28"` 是**最低版本**语义，`version="0"` 是**通配**。
本机实测：ncm 装的 9.6.05 ≥ 名单 7.3.28 ✔；华为音乐装的 12.11.45.301 ≥ 名单 12.11.10.366 ✔。

### 2.5 `hasMediaPermission`（旁路 A，`20-…txt`）—— 存在但普通应用不可用

```
106160: SystemProperties.get("hwouc.media_controller_enable","").equals("enable")   # 不满足 ⇒ false
1061a2: PackageManager.getApplicationInfo("com.huawei.mediacontroller.plrdtest", 0)
1061b6: isSystemApp(context, info.uid, userId)
1061c0: Log.i(" hasMediaPerssion: isSystemApp: " + …)
```

⇒ 只有当 `hwouc.media_controller_enable=enable` **且** 设备上存在一个系统级的
`com.huawei.mediacontroller.plrdtest` 时，才无条件放行。这是**华为内部自检通道**，
Ncrust 既不能伪造系统应用身份，也不应冒用（任务铁律 3）。记为「已证的旁路，不可用」。

**本机实测（`16-bypass-probe.txt`）**：`hwouc.media_controller_enable` = 空串；
`pm path com.huawei.mediacontroller.plrdtest` 无输出（exit=1）；
`pm path com.huawei.mediacontroller` → `package:/system/priv-app/MediaPlaybackController/MediaPlaybackController.apk`
（媒体控制器本体存在，但测试包不在）⇒ **该旁路在本机未激活**。

---

## 3. 【已证】名单从哪来：ROM 里的那份 XML

### 3.1 加载链（`20-…txt` + `23-decompile-HwCfgFilePolicy-cota.txt`）

```
MediaControlUtils.initHwMediaControllerWhiteList():
  107474: getCotaXmlFile()
  107484: 为空 ⇒ Log.e("initHwMediaControllerWhiteList get file path error!") ; return
  107498: initHwMediaControllerWhiteListFromCota(path)
  1074a4: if (sMediaControllerWhiteList.size() <= 0 && !path.equals("/system/emui/base/thirdappfilter/third_app_filter.xml"))
  1074d6:     initHwMediaControllerWhiteListFromCota("/system/emui/base/thirdappfilter/third_app_filter.xml")   ← 本地回落
  107524: initHwMediaControllerWhiteListFromCota(path):
        FileInputStream(path) + XmlPullParser → handleMediaPlayBackXml(parser, eventType)
  1070a0: handleMediaPlayBackXml:
        sMediaControllerWhiteList.clear(); sMediaStyleBlackList.clear(); sCallAppWhiteList.clear()
  1070d4: 只对 <feature name="mediaplaybackcontroller"> 命中（否则整段跳过）
  1070e6:   → handleMediaSessionXml  → 按 <function name> = mediasession / mediastyle / callapp 分派
  107216:     → handleMediaSessionWhiteAppXml 收集每个 <enable name= package= version= options=/>
  107234:       → handleMediaSessionWhiteApp(name, package, version, options)
                    ↳ 组一个 HashMap{"name","package","version","options"} 放进 sMediaControllerWhiteList

MediaControlUtils.getCotaXmlFile():
  106aae: "thirdappfilter" + File.separator + "third_app_filter.xml"
  106ad4: huawei.cust.HwCfgFilePolicy.getDownloadCfgFile("thirdappfilter", "third_app_filter.xml")
  106b28: 返回数组第 0 个元素（数组元素是目录路径）

HwCfgFilePolicy.getDownloadCfgFile(verDir, filePath):
  562158: getFileInfo("/data/cota/para/", verDir, filePath)         ← COTA 覆盖优先
  562166: for (dir : getCfgPolicyDir(0 /*GLOBAL*/)) getFileInfo(dir, …)  ← 预置目录
  562182: isPresetNewerVersionInfo(preset, cota) ⇒ 取更新的那份
  5621a6: 返回 new String[2]，调用方只取 [0]
  静态常量 COTAINFO_DIR = "/data/cota/para/"
```

⇒ **生效名单的来源是 `<dir>/thirdappfilter/third_app_filter.xml` 里
`<feature name="mediaplaybackcontroller">` 段**，其中 `<dir>` 优先 `/data/cota/para/`（COTA 下发），
回落到华为 cust 预置目录（本机实测即 `/system/emui/base/thirdappfilter/`）。

### 3.2 判据是「包名字符串表」，不是签名（已证）

`third_app_filter.xml` 的该段（`<feature name="mediaplaybackcontroller">`）三个 function 合计
**55 个 unique package**（逐条实测计数：`mediasession` 51 条 / `mediastyle` 8 条 / `callapp` 4 条），同时包含：

- 华为自家签名：`com.huawei.music`、`com.huawei.music.local`、`com.huawei.browser`、`com.huawei.health` …
- **第三方签名**：`com.netease.cloudmusic`（ncm）、`com.kugou.android`（酷狗）、`cn.kuwo.player`（酷我）、
  `com.tencent.qqmusic`（qm）、`com.ximalaya.ting.android`、`fm.qingting.qtradio`、`com.yibasan.lizhifm`、
  `com.tencent.radio`、`com.tencent.weread`、`com.luojilab.player`、`cmccwm.mobilemusic`、
  `com.luna.music`（汽水音乐）、`cn.wenyu.bodian`、`cn.missevan`、`com.apple.android.music`（Apple Music）、
  `com.spotify.music`、`deezer.android.app`、`com.aspiro.tidal`、`com.anghami`、`ru.yandex.music` …
- 三家签名指纹两两不同（见 `manifest-diff.md` §5）

⇒ 名单里既有华为签名也有几十个第三方签名 ⇒ **判据不可能是「华为签名白名单」**。
（此外 `checkSessionRecord` / `checkMediaWhiteInfo` 的指令里也**没有任何** `PackageManager.getPackageInfo(…, GET_SIGNATURES)`
或 `SigningInfo` 调用 —— 只有 `getVersionName` 走了一次 `getPackageInfo` 取版本号。）

### 3.3 本机实际内容（原文已落盘）

| 文件 | md5 | 结构 | 是否含 Ncrust |
|---|---|---|---|
| `/system/emui/base/thirdappfilter/third_app_filter.xml`（276312 B） | `9264d05edad364d3de6c4b75ababaf5d` | `<thirdparty-app-filters><feature name="mediaplaybackcontroller" version="11.1.0.139">` + `mediasession`(55 条) / `mediastyle`(8 条) / `callapp`(4 条) | **否** |
| `/system/emui/base/xml/hw_mediaplaybackcontroller_app_config.xml`（2587 B） | `7a213fd27ad90fb16db61480ac9e908c` | `<resources><whiteapp name= package= version=/>` ×32 | **否** |

原始文件：`13-hw_mediaplaybackcontroller_app_config.xml`、`14-third_app_filter.xml`
（后者 276 KB 全文，`mediaplaybackcontroller` 段单独摘出为 `tmp/mediaplaybackcontroller-feature.xml`）。

`grep -n "netease\|ncrust\|takahashirinta"` 的结果（原文）：

```
854:  <enable name="ncm" package="com.netease.cloudmusic" version="7.3.28" options=""/>
907:  <enable name="ncm" package="com.netease.cloudmusic" version="8.9.61" options=""/>   ← mediastyle 段
<ncrust / takahashirinta: NO MATCH>
```

### 3.4 本机装了名单里的哪几个？（实测交集）

`adb shell pm list packages` ∩ 上面两个文件：

- 与 `hw_mediaplaybackcontroller_app_config.xml` 的交集 = **`com.huawei.music`、`com.netease.cloudmusic`**（恰好 2 个）
- 与 `<feature name="mediaplaybackcontroller">` 整段（55 个 unique package）的交集 = `com.huawei.music`、
  `com.netease.cloudmusic`、`com.huawei.music.local`、`com.ss.android.ugc.aweme`（抖音）、`com.tencent.mm`、
  `com.huawei.hwread.dz`、`com.huawei.vassistant`、`com.huawei.browser`、`com.huawei.health`、
  `com.huawei.meetime`、`com.eg.android.AlipayGphone`、`com.android.server.telecom`
  （计算方式：`comm -12 tmp/installed.txt tmp/wl_mpc.txt`，两个中间文件均已落盘）

**而「卡片正常」的两个应用恰好就是这份名单里装的音乐类应用**（ncm、华为音乐）；
名单外的 `com.takahashirinta.ncrust` 被拒。这个交集是本节最强的相关性证据。

### 3.5 COTA 覆盖通道：本机没用上，且普通应用写不进去（已证）

```
$ adb -s … shell 'ls -lR /data/cota'
/data/cota:
drwxrwxr-x 5 system system 3440 2026-02-13 15:46 .
drwxr-xr-x 3 root   root   3440 2025-03-15 12:10 para
/data/cota/para:
drwxr-xr-x 4 root   root   3440 2026-02-13 15:46 iaware
/data/cota/para/iaware/{ThirdPartyAppProperty,iGraphicsPara}     ← 只有这两项

$ adb -s … shell 'ls -l /data/cota/para/thirdappfilter'
ls: /data/cota/para/thirdappfilter: No such file or directory   (exit=1)

$ adb -s … shell 'ls -ld /system/emui/base/thirdappfilter /system/emui/base/xml'
drwxr-xr-x 2 root root  124 2018-08-08 00:01 /system/emui/base/thirdappfilter
drwxr-xr-x 2 root root 1906 2018-08-08 00:01 /system/emui/base/xml
$ adb -s … shell 'mount | grep " /data "'
/dev/block/sdd79 on /data type f2fs (rw,…)
```

⇒ ① COTA 覆盖目录**在本机不存在**，实际生效的就是只读 `/system` 上的预置副本；
② `/data/cota/para` 是 `root:root 0755`、`/system` 只读、目标文件 `0644 root:root`
⇒ **一个普通第三方应用没有任何写入途径**；COTA 通道是 OEM/OTA 侧能力（`/data/cota` 本身还是 `system` 组）。

原始证据：`15-cota-and-config-dir-probe.txt`、`23-decompile-HwCfgFilePolicy-cota.txt`。

### 3.6 名单还被谁消费（已证，说明影响面）

| 调用点 | 来源 | 行为 |
|---|---|---|
| `HwMediaSessionServiceInner.checkSessionRecord` ← 唯一调用者 `pauseOtherMedia` | hwServices | 名单外 ⇒ 返回 false（**不参与**「别的应用起播时暂停它」这条逻辑） |
| `MediaControlUtils.checkController` ← `HwMediaSessionServiceEx.getPlayerInfoList` / `startConfigHistoryMediaPlayer` | hwEmui / hwServices | 名单外 ⇒ 不进入华为媒体控制器（HiCar / 播控） |
| `HwMediaSessionServiceEx.isInMediaWhiteList` ← `filterMediaInfos` | hwServices | 名单外 ⇒ 不进 `MediaPlayerInfoEx` 列表（`Log.i("this app is not support pkgName = ")`） |

反汇编原文：`22-decompile-HwMediaSessionServiceEx-filter.txt`。

---

## 4. 【已证】动态 A/B：同设备、同卡片、同判据

| 应用 | `dumpsys media_session` 的 state | `HwMediaSessionServiceInner` / `MediaControlUtils` 的 white list 判定 | 控制中心媒体卡 `content-desc` |
|---|---|---|---|
| **ncm**（第三方） | `state=3`（正在播放） | **`I MediaControlUtils: checkController is true` ×4，无 not-in-white-list** | **`The Final Countdown Europe`** ✅ |
| 华为音乐（系统应用） | `state=2`（**未在播放，本轮证据无效**，见 §6） | 本轮未采到 | 沿用 v2.1.5：`喜欢你 BEYOND` ✅ |
| **Ncrust** | `state=2`（本轮未重跑，见 §6 与协调说明） | **`V HwMediaSessionServiceInner: this app is not in media white list, pkgName: com.takahashirinta.ncrust` ×2**（12:59:10 抓到） | v2.1.5：`未在播放` ❌ |

ncm 那次采集的原始文件：`dumpsys-media-session-netease.txt`、`logcat-hwmediasession-netease.txt`、
`uiautomator-netease.xml`、`screenshots/netease-controlcenter.png`、`tmp/capture-netease.log`。
其中 logcat 原文（节选）：

```
09-25 13:02:44.874  1639  3987 I MediaControlUtils: checkController is true
09-25 13:02:44.882  1639  3987 I MediaControlUtils: checkController is true
09-25 13:02:44.904  1639  3987 I MediaControlUtils: checkController is true
09-25 13:02:45.106  1639  3987 I MediaControlUtils: checkController is true
```

同一时刻 ncm 的 `getPlayerInfoList` 侧也有 `V HwMediaSessionServiceEx: getPlayerInfo, playerId: 202558260,pkgname: com.netease.cloudmusic`
与 `I HwMediaSessionServiceEx: player infoList size: 1`（见 `logcat-hwmediasession-douyin.txt`，那次跑到的其实是 ncm）。

**这就是铁律 2 要求的「同设备另一个应用正常工作」的反例搜索记录**，
并且给出了机制解释：**ncm 之所以能上，不是因为它「是音乐软件」或「声明得更全」，
而是因为它的包名 `com.netease.cloudmusic` 就写在 §3.3 那份名单里**（`version="7.3.28"`，装有 9.6.05 ≥ 7.3.28 ✔）。

---

## 5. 为什么 ncm 能过而 Ncrust 不能 —— 逐项对比到具体差异

| 对比项 | ncm | 华为音乐 | Ncrust | 是判据吗 |
|---|---|---|---|---|
| 包名在 `third_app_filter.xml` → `mediaplaybackcontroller/mediasession` | **在**（`version="7.3.28"`） | **在**（`version="12.11.10.366"`） | **不在** | ✅ **是（已证）** |
| 安装版本 ≥ 名单版本 | 9.6.05 ≥ 7.3.28 ✔ | 12.11.45.301 ≥ 12.11.10.366 ✔ | 无条目可比 | ✅ 是（已证，但本例未走到这一步） |
| 签名 | ncm 自签 | Huawei 平台签 | Ncrust GPL Fork 自签 | ❌ 否（名单里华为签与几十个第三方签并存） |
| 清单声明 `MEDIA_BUTTON` receiver | ✓ ×2（priority **MAX**） | ✓ ×1（priority **999**） | ✓ ×1（**未声明 priority**） | ❌ 否（华为音乐无 MediaBrowserService 也照样上卡） |
| 声明 `MediaBrowserService` | ✓（`UCarService`） | **✗ 一个都没有** | ✓（`PlaybackService`） | ❌ 否（被华为音乐证伪） |
| 声明 `MediaSessionService` | ✗ | ✗ | ✓ | ❌ 否（同上） |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | ✗ | ✗ | ✓ | ❌ 否（ncm 没有也上卡） |
| `MEDIA_CONTENT_CONTROL` 等媒体权限 | ✗ | ✗ | ✗ | ❌ 否（三家都没有） |
| MediaSession 条数 | 1 | 1 | **2**（`NcrustSession` + `androidx.media3.session.id.*`） | ❌ 否（判据只看 `getPackageName()`，与条数无关 —— 已证） |
| Session metadata 是否完整 | 完整 | 完整 | v2.1.5 起已完整 | ❌ 否（同上，判据函数不读 metadata —— 已证） |
| targetSdk | 33 | 29 | 36 | ❌ 否（三家跨越 29–36，无相关性） |

**结论**：唯一与「过/不过」完全对齐的差异就是**第一行：包名在不在那份 ROM 名单里**。

> ⚠️ **对本仓库既有计划的纠偏（重要）**：`../../v2.1.5/P1-MEDIA-PANEL-DECISION.md` §4 把
> 「把两条 MediaSession 合成一条」列为最可能的真因。**本轮证据表明那条路解决不了白名单问题**：
> `checkSessionRecord` / `checkController` 只取 `session.getPackageName()` / `controller.getPackageName()`
> 去做字符串比较（已证，见 §1、§2.3），**与 session 条数、metadata、通知样式都无关**。
> 合并会话对「锁屏/蓝牙/车机只有一个当前播放器」可能仍有价值，但**不能**让卡片恢复。

---

## 6. 【已证】判据不在应用清单里（清单层面 Ncrust 反而更完整）

见 `manifest-diff.md` §2–§4 的逐项表。一句话：

- `MEDIA_BUTTON` receiver：三包**都有**；
- `MediaBrowserService`：ncm 有、**华为音乐没有**、Ncrust 有；
- `MediaSessionService`：只有 Ncrust 有；
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`：只有 Ncrust 有；
- 媒体权限：三家都没有 `MEDIA_CONTENT_CONTROL`。

⇒ **Ncrust 在「媒体应用声明」这个维度上比卡片正常的华为音乐更齐全，却仍被拒。**
这直接排除了「Ncrust 少声明了什么」这一类解释。

---

## 7. Ncrust 侧可行动的差距（按可行性排序）

### 7.1 能靠正规声明/实现补上的（**但都补不上白名单**）

| # | 动作 | 对本问题的效果 | 依据 |
|---|---|---|---|
| 1 | 给 `androidx.media3.session.MediaButtonReceiver` 的 `MEDIA_BUTTON` intent-filter 补 `android:priority="2147483647"` | **不会**让它过白名单；只在「多个播放器抢媒体键」时有意义 | 华为音乐 priority=999 也过（已证，`manifest-diff.md` §2.1） |
| 2 | 合并两条 MediaSession（v2.1.5 §4 计划） | **不会**过白名单；解决的是锁屏/蓝牙只有一个「当前播放器」 | 判据只看 `getPackageName()`（已证，§1/§2.3） |
| 3 | 复核/补齐 MediaStyle 通知 | **不会**过白名单；可能改善「通知栏/锁屏」这条**不依赖华为白名单**的路径 | SystemUI 侧 `MediaDataManager` 在 v2.1.5 有 `active=true` 记录，但**未做端到端确认 → 未定位** |
| 4 | 把 targetSdk 降到 ≤33 | **不会**过白名单；且会牺牲 Android 14+ 的 FGS 合规 | 三家 targetSdk 29/33/36 与结果无相关性（§5） |

**这四条都不是解法 —— 写在这里是为了防止后续把它当成解法去试。**

### 7.2 补不上的（真边界）

| # | 缺口 | 为什么补不上 |
|---|---|---|
| A | 包名不在 `/system/emui/base/thirdappfilter/third_app_filter.xml` 的 `mediaplaybackcontroller/mediasession` 名单里 | 该文件在**只读** `/system`（`0644 root:root`，`/system` 只读挂载）；COTA 覆盖目录 `/data/cota/para/thirdappfilter` 本机不存在且 `root:root 0755`。第三方应用**没有任何写入途径**（已证，§3.5） |
| B | 走 `hasMediaPermission` 旁路 | 需要 `hwouc.media_controller_enable=enable` + 系统级 `com.huawei.mediacontroller.plrdtest`；普通应用无法满足，且冒用身份违反铁律 3（已证存在、不可用，§2.5） |
| C | 靠 `versionName == null` 短路 | 该分支在 `package` **已命中之后**才评估（`checkMediaWhiteInfo` 先比 package），且 Ncrust 的 versionName 非空。**不可用**（已证，§2.3） |

### 7.3 唯一「正规」的解决方向

让 `com.takahashirinta.ncrust` 被写进那份 ROM 配置 —— 即**由 OEM（华为）在 ROM/COTA 配置里加入该包名**。
这不是开发者可自助的入口；本仓库既不能改包名冒充（铁律 3），也不能改系统文件（无 root、只读分区）。

### 7.4 因此，关于「平台不支持/能力边界」怎么说才合规

铁律 2 要求的反例搜索**已经做了并且找到了正例**（ncm，同一台设备、同一张卡、同一个判据函数）。
所以结论**不能**写成「华为不支持第三方应用」，正确的表述是：

> **控制中心媒体卡对第三方应用是开放的（ncm 就是第三方，工作正常），
> 但它采用的是「ROM 预置包名白名单」而不是「应用自我声明」；
> 名单之外的包名一律被 `isInMediaSessionOrStyleList(...,2)` 拒绝。
> `com.takahashirinta.ncrust` 不在名单内，且名单文件位于只读系统分区、无面向开发者的自助入口 ⇒
> 就「出现在华为控制中心媒体卡」这一具体能力而言，对 Ncrust 构成**硬边界**；
> 这是**预置名单型**的边界，不是「拒绝第三方」型的边界。**

---

## 8. 分档结论（严格三档）

### 8.1 已证（有原文/命令/输出支撑，可复现）

1. 打印 `this app is not in media white list, pkgName: …` 的**恰好两处**：
   `HwMediaSessionServiceInner.checkSessionRecord`（hwServices.jar）与
   `MediaControlUtils.checkController`（hwEmui.jar）；二者都调
   `MediaControlUtils.isInMediaSessionOrStyleList(pkgName, context, userId, 2)`，返回 false 时打印并拒绝。
   → `21-…txt`、`20-…txt`
2. **判据 = 包名字符串命中白名单 + 版本号通过 `checkVersion`**；
   匹配规则见 `checkMediaWhiteInfo`（`package` 精确相等、`version` 最低版本语义 / `"0"` 通配、`options` 非空才判）。
   → `20-…txt`
3. **白名单的加载路径**：`initHwMediaControllerWhiteList` → `getCotaXmlFile`
   → `HwCfgFilePolicy.getDownloadCfgFile("thirdappfilter","third_app_filter.xml")`（首查 `/data/cota/para/`）
   → 回落 `/system/emui/base/thirdappfilter/third_app_filter.xml`；
   解析器只认 `<feature name="mediaplaybackcontroller">` → `mediasession/mediastyle/callapp` → 收集 `<enable …/>`
   进 `sMediaControllerWhiteList`。→ `20-…txt`、`23-…txt`
4. 本机那份 XML 的**原文内容**：`third_app_filter.xml` 该段 55 个 package，
   **含 `com.netease.cloudmusic`、`com.huawei.music`，不含任何 ncrust/takahashirinta**。
   → `14-third_app_filter.xml`、`tmp/mediaplaybackcontroller-feature.xml`
5. 本机**没有** COTA 覆盖（`/data/cota/para/thirdappfilter` 不存在），
   生效副本在只读 `/system`，`0644 root:root`，普通应用不可写。→ `15-cota-and-config-dir-probe.txt`
6. 判据**不是签名**：名单同时含华为签名与几十个第三方签名；判定指令里没有任何签名 API 调用。→ §3.2
7. 判据**不是清单声明**：华为音乐无 `MediaBrowserService`/`MediaSessionService` 也上卡；
   Ncrust 声明更全却被拒。→ `manifest-diff.md`
8. 动态：ncm → `checkController is true`、无 not-in-white-list、卡片显示歌名/艺人；
   Ncrust → `not in media white list`（12:59:10 原文）。→ `logcat-hwmediasession-netease.txt`、§4
9. `hasMediaPermission` 旁路存在但需系统级 `com.huawei.mediacontroller.plrdtest`。→ `20-…txt`

### 8.2 高置信推断（说明推断链、支持证据、以及能证伪它的实验）

| # | 推断 | 推断链与支持证据 | 能证伪它的实验 |
|---|---|---|---|
| H1 | 名单里的第三方包（ncm 等）**之所以**能上卡，就是因为在名单里；名单是**唯一**准入判据 | 判据函数已证是纯包名/版本比较（8.1-2）；本机名单内装的音乐类应用恰好就是卡片正常的两个（§3.4）；名单外的 Ncrust 被同一函数拒绝（§4）。缺的一步：没有穷尽证明「不存在其它旁路」 | ① 让名单内另一个**第三方**（如抖音 `com.ss.android.ugc.aweme`，在名单中，装机 v40.6.0 > 名单 25.3.0）真正播放，观察是否出现 not-in-white-list；② 找一个包名不在名单、但**清单声明与 ncm 完全同类**的第三方音乐应用做 A/B（本轮未做） |
| H2 | 控制中心那张卡的播放器列表就是 `HwMediaSessionServiceEx.filterMediaInfos` 产出的 `MediaPlayerInfoEx` 列表 | `filterMediaInfos` 逐条 `checkPkgName` → `isInMediaWhiteList` → 命中才 `new MediaPlayerInfoEx(...)` 并 `List.add`（已证）；`getPlayerInfoList` 是 `com.huawei.mediacontroller` 的取数接口；logcat 有 `player infoList size: 1` 与 `onActiveSessionsChanged controller size = 1 topPkg:com.netease.cloudmusic` 时间对齐 | 关掉/卸载 ncm 后打开控制中心，对比 `player infoList size` 与卡片是否同时变空；或在名单内应用播放时观察 `filterMediaInfos` 的 `Log.i("this app is not support pkgName = ")` 是否只对名单外出现 |
| H3 | `checkSessionRecord` 的拒绝意味着「别的应用起播时不会自动暂停 Ncrust」 | 唯一调用者 `pauseOtherMedia`，返回 false 时直接 `return-void`，不走 `pauseLocalMedia` / `pauseMigrateInMedia`（已证指令流）；`mContext`/`mUserId` 传参一致 | 让 Ncrust 播放，再让名单内应用起播，观察 Ncrust 是否被自动暂停（本轮未做） |

### 8.3 未定位（直说没查到）

| # | 未定位项 | 缺哪一步 |
|---|---|---|
| U1 | `hw_mediaplaybackcontroller_app_config.xml`（32 条 `<whiteapp>`）**在运行时是否真的被读** | 该文件名常量 `CONFIG_FILE_WHITE_BLACK_APP` 由 `getHwCfgFileList` 使用，而 `getHwCfgFileList` 的唯一调用者是 `MediaControlUtils.getPath`；**`getPath` 的调用者未展开**。它不在这条 `initHwMediaControllerWhiteList` 链上，但也不能断言它永不生效 |
| U2 | `getCfgPolicyDir(0)` 具体展开成哪些目录/优先级 | 未反汇编该函数；只知道 `getDownloadCfgFile` 会用它的返回值逐个 `getFileInfo` |
| U3 | COTA 更新的**触发方式与签名校验**（谁下发、怎么签名、是否只能 OTA） | `/data/cota/para` 无写入权限已证，但「华为服务卡/OTA 包如何投递该文件」未查 |
| U4 | 「抖音能过」这条 A/B **未采到有效证据** | 本轮抖音采集时它的 session 停在 `state=1`（未播放），随后 ROM 自行把历史媒体应用 ncm 拉起播放，导致 dump 到的是 ncm 的卡。两份 uiautomator 文件 md5 相同（`41c322c4…`）⇒ **该条 A/B 判为无效，不作为证据**（详见 `EVIDENCE.md` §B-5） |
| U5 | 华为音乐为何被 `checkSessionRecord` **主动排除**却仍能上卡 | `checkSessionRecord` 对 `com.huawei.music` / `com.android.mediacenter` / `com.huawei.music.local` 显式返回 false（`Log.v("this session is not support pkgName: …")`，已证），说明它走的是**另一条原生路径**；那条路径是什么，未定位 |
| U6 | Ncrust 本轮**未重跑**播放态采集 | 与主会话在同一台 WGR-W09 上并发采集会互相污染（见 `EVIDENCE.md` §B-6），按协调要求让出设备；Ncrust 侧的动态证据沿用 12:59:10 抓到的 logcat 原文与 v2.1.5 的 A/B |
| U7 | 华为音乐本轮动态采集停在 `state=2`（未在播放） | 按任务规则记为「未在播放状态，证据无效」；为避免抢占媒体焦点干扰主会话回归，**未重跑**。其卡片结论沿用 v2.1.5 |

---

## 9. 复现清单（全部命令原文见 `EVIDENCE.md`）

```bash
S=WVQ6R22124000968
# 1) 定位字符串所在 jar（无需 root）
adb -s $S shell 'grep -l "not in media white list" /system/framework/*.jar'
# 2) 取回并反汇编
adb -s $S pull /system/framework/hwEmui.jar     tmp/rom/
adb -s $S pull /system/framework/hwServices.jar tmp/rom/
adb -s $S pull /system/framework/framework.jar  tmp/rom/
(cd tmp/rom && for j in hwEmui hwServices framework; do mkdir -p x/$j && (cd x/$j && unzip -o -q ../../../$j.jar 'classes*.dex'); done)
/home/duanjb666/Android/sdk/build-tools/35.0.0/dexdump -d tmp/rom/x/hwEmui/classes.dex     > tmp/rom/x/hwEmui.dis.txt
/home/duanjb666/Android/sdk/build-tools/35.0.0/dexdump -d tmp/rom/x/hwServices/classes.dex > tmp/rom/x/hwServices.dis.txt
# 3) 白名单原文
adb -s $S shell 'cat /system/emui/base/thirdappfilter/third_app_filter.xml'
adb -s $S shell 'cat /system/emui/base/xml/hw_mediaplaybackcontroller_app_config.xml'
# 4) 是否被 COTA 覆盖
adb -s $S shell 'ls -lR /data/cota'
```

> 附：`/system/emui/base/thirdappfilter/version.txt` 原文
> `version=1.10.22.228` / `type=THIRDAPPFILTER` / `subtype=generic` / `compatibleVersion=2` / `classify=1` /
> `displayVersion=TF.GENC.1.10.22.228`（同一 COTA 机制的版本描述文件，见 `15-cota-and-config-dir-probe.txt`）。

---

## 10. 补充：`Settings.Secure.media_button_receiver`（任务书里指定查 global，实测键在 secure）

任务书要求 `settings list global | grep -i media_button_receiver`。**实测：`global` 里没有这个键**
（无输出，exit=1，三次读取均如此）。真正的键在 **`Settings.Secure`**，原文如下
（`settings-global-media_button_receiver-ALL.txt`）：

```
$ adb -s WVQ6R22124000968 shell settings list secure | grep -i media_button_receiver
media_button_receiver=com.netease.cloudmusic/com.netease.cloudmusic.receiver.MediaButtonEventReceiver,0,1-\
com.netease.cloudmusic/com.netease.cloudmusic.receiver.MediaButtonEventReceiver,0,1-\
com.huawei.music/com.android.mediacenter.playback.systeminteract.MediaButtonIntentReceiver,0,1-\
com.netease.cloudmusic/com.netease.cloudmusic.module.webview.audio.WebMediaButtonReceiver,0,1
```

【实测】该列表里有 ncm（3 条，含重复）与华为音乐（1 条），**没有 Ncrust**——
尽管 Ncrust 的清单里确实声明了 `androidx.media3.session.MediaButtonReceiver`（`manifest-diff.md` §2.1）。

【高置信推断】这个键**不是**华为白名单，而是 **AOSP 的 `MediaSessionService.Settings`
（`KEY_MEDIA_BUTTON_RECEIVER`，「记住上一个媒体按键接收者」）**。推断链：

1. 它在 `secure` 命名空间，值格式 `pkg/component,userId,type[-…]` 与 AOSP 的实现一致；
2. 同一毫秒的 logcat 有 AOSP 侧的方法名：
   `09-25 13:02:03.828 I MediaSessionService: rememberMediaButtonReceiverLocked com.netease.cloudmusic/MediaSession (userId=0)`
   —— 该行出现的时刻正好是 ncm 成为 media button session 的时刻；
3. 因此「Ncrust 不在这张表里」**很可能是结果而不是原因**（它只被 `remember` 于会话成为 media button session
   且该 session 带有非 null 的 `mediaButtonReceiver` 时），**不能**把它当作准入判据。

能证伪它的实验（未做，见 `EVIDENCE.md` §D-5）：让 Ncrust 起播并成为 media button session，
观察 ① `settings list secure` 是否出现 Ncrust 的条目；② `dumpsys media_session` 里 Ncrust 会话的
`mediaButtonReceiver=` 字段是否非 null。若 ① 仍不出现而 ② 为非 null，则本推断被证伪。
