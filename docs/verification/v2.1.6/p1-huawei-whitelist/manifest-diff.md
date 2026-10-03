# 静态清单对比 —— ncm / 华为音乐 / Ncrust（WGR-W09 实机 APK）

采集时间：2026-09-25 12:57（CST）
设备：`WVQ6R22124000968` · HUAWEI WGR-W09 · HarmonyOS 4.2 / EMUI 14.2.0 / Android 12 / API 31
工具：`/home/duanjb666/Android/sdk/build-tools/35.0.0/aapt2`（`Android Asset Packaging Tool (aapt) 2.19-11948202`）

> 本文件所有数字均来自本目录下已落盘的原始 dump，非推断。每个 APK 的来源与 sha256 见 §0。

---

## 0. 三个被比对对象的来源（可复现）

| 项 | ncm | 华为音乐 | Ncrust |
|---|---|---|---|
| package | `com.netease.cloudmusic` | **`com.huawei.music`** | `com.takahashirinta.ncrust` |
| versionName | 9.6.05 | 12.11.45.301 | 2.1.5-gpl |
| versionCode | 9006005 | 121145301 | 35 |
| `pm path`（实测原文） | `/data/app/~~c3sfiv7amKGFsYjb_5dDuw==/com.netease.cloudmusic-vMlQYcn4OFL-2RDsqtVQ3Q==/base.apk` | `/data/app/~~5K2_v2Xb0yxDD7VhQpTT5w==/com.huawei.music-IJq6io8LYrrH-ds8pIrIew==/base.apk`（另有 `split_card.apk`） | `/data/app/~~cbQPxuptwh3z4fa850bWgQ==/com.takahashirinta.ncrust-HdN1uVs_CpkJxcXHPg7EaQ==/base.apk` |
| APK sha256 | `6efecd9a422a0660504241026ef0e801bfa07c33b6ba1e5ed3ba0cf5b7853ba7` | `f3650cbac5f049dc1cdfe22f55b6518e902f75715e9963a79b8fd1c210127665` | `315f0d8a551b426a90bb364c0e3a7ffd7bf442de7d836f5bc9986de53dd1b451` |
| 清单 dump | `tmp/manifest/netease.manifest.xmltree.txt`（9876 行） | `tmp/manifest/huaweimusic.manifest.xmltree.txt`（5145 行） | `tmp/manifest/ncrust.manifest.xmltree.txt`（124 行） |
| badging | `tmp/manifest/netease.badging.txt` | `tmp/manifest/huaweimusic.badging.txt` | `tmp/manifest/ncrust.badging.txt` |

命令（原文，三包各一次）：

```bash
adb -s WVQ6R22124000968 shell pm path <pkg>
adb -s WVQ6R22124000968 pull <pm path 给出的 apk> tmp/apks/<tag>-base.apk
aapt2 dump xmltree --file AndroidManifest.xml tmp/apks/<tag>-base.apk
aapt2 dump badging tmp/apks/<tag>-base.apk
```

### ⚠️ 关于「华为音乐的真实包名」（本任务要求用实测确定）

| 候选 | 实测结果 |
|---|---|
| `com.android.mediacenter` | **未安装**（`pm path com.android.mediacenter` → 无输出，exit=1） |
| `com.huawei.music` | **已安装**，`pm path` 有输出；`dumpsys package` 里其组件仍叫 `com.android.mediacenter.*`（历史命名空间），媒体会话 id 也是 `com.android.mediacenter.mediasession`，但 **package= 字段是 `com.huawei.music`** |
| `com.huawei.music.local` | 已安装（`/system/priv-app/HwMusicLocal/HwMusicLocal.apk`），是「本地音乐播放器」另一个包 |

⇒ **本文件的「华为音乐」= `com.huawei.music`**。`com.android.mediacenter` 只作为 ROM 白名单里的历史条目出现（见 `whitelist-criterion.md`）。

原始证据：`02-badging-and-certs.txt`、`dumpsys-media-session-huawei.txt`（`com.android.mediacenter.mediasession com.huawei.music/com.android.mediacenter.mediasession (userId=0)` / `package=com.huawei.music`）。

---

## 1. sdk 与体积

| 项 | ncm | 华为音乐 | Ncrust |
|---|---|---|---|
| `minSdkVersion` | 23 | 24 | 24 |
| `targetSdkVersion` | **33** | **29** | **36** |
| `compileSdkVersion`（badging） | 34 | 34 | 36 |
| `<activity>`/`<activity-alias>` 总数 | 991 | 314 | **1** |
| `<receiver>` 总数 | 37 | 26 | **2** |
| `<service>` 总数 | 93 | 36 | **2** |
| `<uses-permission>` 总数 | 84 | 111 | **11** |

---

## 2. `<receiver>` 逐项差集（重点：MEDIA_BUTTON）

### 2.1 声明了 `android.intent.action.MEDIA_BUTTON` 的接收器（全部）

| | ncm | 华为音乐 | Ncrust |
|---|---|---|---|
| 组件名 | `com.netease.cloudmusic.receiver.MediaButtonEventReceiver`<br>**以及第二个** `com.netease.cloudmusic.module.webview.audio.WebMediaButtonReceiver` | `com.android.mediacenter.playback.systeminteract.MediaButtonIntentReceiver` | `androidx.media3.session.MediaButtonReceiver` |
| 个数 | **2** | 1 | 1 |
| `android:priority` | **2147483647（MAX）** ×2 | **999** | **未声明**（⇒ 默认 0） |
| `android:exported` | `true` | `true` | `true` |
| 证据（xmltree 行号） | 2609 / 7989 | 2571 | 144 |

结论：**三包都声明了 `MEDIA_BUTTON`**。priority 上 Ncrust 是唯一没写的（ncm MAX、华为音乐 999）——
这是一处**真实差集**，但**不足以解释准入失败**：华为音乐只用了 999 也照样上卡片。

### 2.2 其余与媒体沾边的 receiver

| 应用 | 其它相关 receiver |
|---|---|
| ncm | 大量 appwidget（`PlayerWidget` / `PlayerWidgetFourTwo` / `PlayerWidgetFourFour` / `Feature*AppWidgetProvider` …）、`SonyBluetoothA2DPReceiver` |
| 华为音乐 | `MediaAppWidgetProvider`、`launcherwidget.MediaAppWidgetStyle1/2/3Provider`、`WearTransNotificationRemoveReceiver`、`MiniNotificationRemoveReceiver`、`SleepModeReceiver`、`CleanNotificationReceiver`、`HiVoiceReceiver`、`ShortcutsReceiver` |
| Ncrust | 仅 `androidx.profileinstaller.ProfileInstallReceiver`（与媒体无关） |

ncm receiver 全名单见 `tmp/manifest-media-sections.txt`。

---

## 3. `<service>` 逐项差集

| | ncm | 华为音乐 | Ncrust |
|---|---|---|---|
| 声明 `android.media.browse.MediaBrowserService` 的 service | **✓ 1 个**：`com.netease.cloudmusic.module.ucar.UCarService`（xmltree 行 2444） | **✗ 一个都没有**（全文 grep 无命中） | **✓ 1 个**：`com.takahashirinta.ncrust.player.PlaybackService`（xmltree 行 77） |
| 声明 `androidx.media3.session.MediaSessionService` | ✗ | ✗ | **✓**（同一 service，xmltree 行 75） |
| `android:foregroundServiceType` | UCarService：未声明 | —— | `PlaybackService`：**`0x00000002`**（= `mediaPlayback`） |
| `android:exported` | `true` | —— | `true` |
| MediaSession 相关 service 个数 | 1（`UCarService`） | **0** | 1（`PlaybackService`） |

> **这是本节最重要的一条**：**华为音乐在自己的清单里根本没声明 `MediaBrowserService` / `MediaSessionService`，
> 它的控制中心卡片却是正常的**。⇒ 「必须声明 MediaBrowserService(Compat) / MediaSessionService 才能上卡片」
> 这个假设被同设备同卡片的 A/B **直接证伪**。

---

## 4. 权限声明逐项差集

| 权限 | ncm | 华为音乐 | Ncrust |
|---|---|---|---|
| `android.permission.FOREGROUND_SERVICE` | ✓ | ✓ | ✓ |
| `android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK` | ✗ | ✗ | **✓** |
| `android.permission.BLUETOOTH` | ✓ | ✓ | ✗ |
| `android.permission.BLUETOOTH_ADMIN` | ✓ | ✓ | ✗ |
| `android.permission.BLUETOOTH_CONNECT` | ✓ | ✓ | ✗ |
| `android.permission.BLUETOOTH_SCAN` | ✓ | ✓ | ✗ |
| `android.permission.WAKE_LOCK` | ✓ | ✓ | ✓ |
| `android.permission.POST_NOTIFICATIONS` | ✓ | ✗ | ✓ |
| `android.permission.MODIFY_AUDIO_SETTINGS` | ✓ | ✓ | ✓ |
| `android.permission.MEDIA_CONTENT_CONTROL` | ✗ | ✗ | ✗ |
| `android.permission.BIND_MEDIA_BROWSER_SERVICE` | ✗ | ✗ | ✗ |
| `android.permission.BIND_MEDIA_SESSION_SERVICE` | ✗ | ✗ | ✗ |
| `android.permission.RECEIVE_BOOT_COMPLETED` | ✗ | ✓ | ✗ |
| `android.permission.READ_MEDIA_AUDIO` | ✓ | ✗ | ✗ |
| `android.permission.READ_EXTERNAL_STORAGE` | ✓ | ✓ | ✗ |

（完整 84 / 111 / 11 条见 `tmp/manifest/**.badging.txt` 的 `uses-permission:` 行。）

**没有任何一个应用声明 `MEDIA_CONTENT_CONTROL` / `BIND_MEDIA_BROWSER_SERVICE` / `BIND_MEDIA_SESSION_SERVICE`**
——包括卡片正常的 ncm 和华为音乐。⇒ 这类「媒体权限」不是准入条件。

---

## 5. 签名证书指纹（只看指纹；不反编译他人代码、不冒用身份）

来源文件：`02-badging-and-certs.txt`（命令 `apksigner verify --print-certs <apk>`，本目录内已落盘原文）

| 应用 | 证书 DN | SHA-256 | SHA-1 |
|---|---|---|---|
| ncm | `CN=LiangJian, OU=Corp.Netease, O=CloudMusic, L=HangZhou, ST=ZheJiang, C=310000` | `54254d2be09daef48dedc2b4a4f497d153e14ed9d70814fc9c360ee9240827f7` | `d35cb4a496f96a15afeb44a4c9694fe7435ea579` |
| 华为音乐 | `CN=China, OU=Huawei, O=Huawei, L=Shenzhen, ST=Shenzhen, C=CN` | `3e13f630c77618de3a580dbbaffe0ac04a16444633cc0253afb088d3e3ab6efe` | `71fe20abbd81b466fa7f9364fdaa9e205807a42f` |
| Ncrust | `CN=Ncrust GPL Fork, OU=Personal, O=yaxiaiyuting, L=Unknown, ST=Unknown, C=CN` | `e75af3ffbcf76a36a567188d88d132adf3c7484c53c20a3a083cb1d222025511` | `12f56c4b8bcdb72590f198e247210842e54d9b38` |

三个指纹两两不同。**Ncrust 的证书与 ncm、华为音乐都无关**——
本文件只做「指纹是否相同」的事实陈述，不提供、也不建议任何形式的签名冒用。

> 签名不是准入判据的直接反证在 `whitelist-criterion.md` §3.2：ROM 白名单里同时列着
> 华为自家签名（`com.huawei.music`）与第三方签名（ncm、酷狗、Spotify…），
> 且**同一家厂商的另一个包**（`com.netease.g67.huawei` 等）并未因此自动进入媒体白名单。

---

## 6. Ncrust 独有 / 缺失（相对另两家）

**Ncrust 有而另两家没有的**：
1. `androidx.media3.session.MediaSessionService` action（media3 专属）；
2. `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限（Android 14+ 要求，另两家 targetSdk 低所以不需要）；
3. `<application android:appComponentFactory="androidx.core.app.CoreComponentFactory">`、
   `androidx.startup.InitializationProvider` 等 AndroidX 样板。

**另两家有而 Ncrust 没有的**：
1. `MEDIA_BUTTON` receiver 的 `android:priority`（ncm MAX / 华为音乐 999 / Ncrust 缺省 0）；
2. 蓝牙相关权限（`BLUETOOTH*`）；
3. 桌面小部件（appwidget）provider —— Ncrust 清单里 `<receiver>` 只有 2 个，其中一个还是 profileinstaller；
4. ncm 额外声明了 `MediaBrowserService`（`UCarService`，车机用）。

**在「媒体会话/媒体按键」这个维度上，Ncrust 的清单声明比华为音乐更完整**
（有 MediaBrowserService + MediaSessionService + MEDIA_BUTTON receiver + FGS mediaPlayback），
但它是唯一被 ROM 打 `not in media white list` 的。⇒ **准入判据不在清单里**，见 `whitelist-criterion.md`。

---

## 7. 方法学说明与局限

- 清单来源全部是 **aapt2 dump**（二进制 AXML 反解），不是 `dumpsys package`。
  `dumpsys package` 的 registered receivers 段只反映**运行时已注册**的接收器，会漏掉静态声明，
  因此本文件不采用它作为 receiver 清单来源。
- `android:priority` 只在 `<intent-filter>` 上合法；xmltree 里 Ncrust 的 MEDIA_BUTTON
  intent-filter **没有该属性**（`tmp/manifest/ncrust.manifest.xmltree.txt` 第 146–148 行，无 `priority` 行），
  这与 `dumpsys package` 的 `mPriority=0` 语义一致，但本文件只陈述「未声明」。
- 三个包的 `<provider>` / `<activity>` 未做逐项对比（与准入判据无关，且 ncm 991 个 activity 无对比价值）；
  只在 §1 给出总数。如需全量，直接读 `tmp/manifest/*.manifest.xmltree.txt`。
- `com.huawei.music` 还带一个 `split_card.apk`（62 KB 配置 split），本文件只比对了 `base.apk` 的清单；
  `split_card.apk` 的清单未展开，标注为**未定位**。
