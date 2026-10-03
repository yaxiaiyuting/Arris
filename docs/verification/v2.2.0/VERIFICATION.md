# v2.2.0 真机验证记录

- 日期：2026-09-25
- 产物：`app-release.apk` / `app-debug.apk`，`versionCode = 37`，`versionName = 2.2.0-gpl`
- 证据目录：`docs/verification/v2.2.0/device/`（截图 + logcat）
- 方法：`adb install -r` → `am start` → 截图逐步核对；关键状态用「故障注入 + 恢复」验证

---

## 1. 设备矩阵（A/B 对照）

| 序列号 | 型号 | Android | SDK | ABI | root | 装的是 | 结果 |
|---|---|---|---|---|---|---|---|
| `3B15CD00GB700000` | PLC110 (OPPO) | **16** | 36 | arm64-v8a | KernelSU | **release** | ✅ 全流程通过 |
| `WVQ6R22124000968` | WGR-W09 (Huawei 平板) | **12** | 31 | arm64-v8a | 无 | **release** | ✅ 列表/详情通过（宽屏 3 列） |
| `0715f763f54c023a` | SM-G9209 (Samsung S6) | **7.0** | 24 | arm64-v8a | Magisk | debug | ⚠️ 见 §6（设备掉线） |

**为什么两台主设备都装 release**：本次唯一发现的严重缺陷（§4.1 的 R8 崩溃）**只在 release 上出现**，
debug 完全正常 —— 只测 debug 会把它漏掉。所以 A/B 对照的主轴是 **release APK × Android 16 / Android 12**。

---

## 2. PCL110（Android 16）· release APK · 逐项证据

### 2.1 入口按源隔离（`07-playlist-tab.png`）

库 → 歌单 tab：`qm歌单 / 来自 qm账号` 独占一整行，**在 ncm 歌单网格之外**，
下方才是 ncm 的 `新建歌单 / 1(16首) / 南斯拉夫内战金曲 / internationale`。
两者分区展示，**没有任何合并**。

### 2.2 歌单列表（`10-qq-list-release-fixed.png`）

```
qm歌单
  我喜欢      ← 分组「我喜欢」
    我喜欢   1 首 · 我喜欢        ← dirId=201，isFavorite 标记生效
  自建歌单    ← 分组「自建歌单」
    新建歌单1  22 首              ← 与探针实测完全一致（dirId=1 / tid=8591804616 / songNum=22）
```

「我喜欢」被单独分组并打标，证明 `dirId == 201 ⇒ isFavorite` 这条探针结论落地正确。

### 2.3 歌单详情 + 分页（`13-qq-detail-titled.png`）

```
新建歌单1        ← 标题取自接口返回的 dirinfo.title（不是路由参数）
qm
22 首
[22 首歌曲，每行带「qm」音源角标、封面、歌手、时长]
```

- 曲目行复用既有 `SongCard`，**没有另写列表行**；
- 音源角标说明这些曲目属于 qm（`SourceIds` 的 bit62 隔离在 UI 上可见）；
- 标题用**接口返回的真名**而非路由参数 —— 路由参数要过 URL 编解码，中文可能失真。

### 2.4 离线可看（`14-offline-list.png`）

操作：飞行模式 + 关 WiFi/数据 → 点右上角刷新（强制联网）→ 失败。

```
qm歌单
离线模式：下面显示的是本地缓存     ← 降级横幅
  我喜欢  1 首 · 我喜欢            ← 缓存数据照常渲染
  新建歌单1  22 首
```

**关键点**：降级是**横幅**而不是错误页 —— 错误页会把已经能看的数据藏起来，「离线可看」就不成立。

### 2.5 登录态过期降级（`21-login-expired-verified.png`）

故障注入（**已完整恢复，见 §5**）：把 cookie 里的 `qqmusic_key` 与 `qm_keyst` 改成 `INVALIDTICKET`
（保留 `uin` 与字段存在，所以 `QqCookie.isLoggedIn` 仍为 true，App 会真的去问服务端）。

```
qm歌单
qm登录已过期          ← 明确的过期状态
重新登录                   ← 可操作的出口
  我喜欢  1 首 · 我喜欢     ← 缓存数据仍然可看
  新建歌单1  22 首
```

### 2.6 隐私：缓存落在应用私有目录

```
$ adb shell su -c 'ls -la /data/data/com.takahashirinta.ncrust/shared_prefs/ | grep qq'
-rw-rw---- u0_a334 u0_a334 14891 2026-09-25 13:58 ncrust_qq_playlists.xml   ← 本次新增的歌单缓存
-rw-rw---- u0_a334 u0_a334   803 2026-09-25 03:15 ncrust_qq_prefs.xml       ← 既有登录态
```

- 目录是 `MODE_PRIVATE` 的应用私有目录；其它应用读不到；
- 全程 logcat 里**没有**歌单名 / 歌名 / 昵称 / uin 的明文（只打 id、数量、错误码）；
- **不上报**：本版没有新增任何对外上报路径。

---

## 3. Huawei WGR-W09（Android 12）· release APK · A/B 对照

| 截图 | 内容 |
|---|---|
| `24-huawei-awake.png` | 应用启动正常，**宽屏侧边栏布局**（首页/库/搜索/用户），无崩溃 |
| `25-huawei-playlist-tab.png` | 歌单 tab：QQ 入口行在顶部，ncm 歌单为**自适应 3 列**网格；绿色主题 |
| `26-huawei-qq-list.png` | QQ 歌单页：`我喜欢 1 首 · 我喜欢` + `新建歌单1 22 首`，与 Android 16 上**完全一致** |

**A/B 结论**：同一份 release APK，在 Android 16（窄屏 + 侧边栏）与 Android 12（宽屏 + 3 列自适应栅格）
两种布局形态下，数据一致、无崩溃。曲目数与内容与探针实测一致 ⇒ 平台假设不是只在单一设备上成立。

---

## 4. 真机暴露、且**只有真机能暴露**的三个问题（都已修）

这一节是本次「真机验证」的主要产出：三个问题在 debug 构建、单测、lint 里**全都看不出来**。

### 4.1 release-only R8 崩溃：Gson 泛型签名被裁掉（严重）

**症状**（`logcat-01-release-crash.txt`，release APK，点进 QQ 歌单页必崩）：

```
java.lang.ClassCastException: a4.n cannot be cast to I4.j
	at J4.C.h(SourceFile:293)
```

**定位**（`retrace` + `mapping.txt`）：

```
ClassCastException: a4.n cannot be cast to I4.j
  at PlaylistCacheCodec.decodeList(PlaylistCacheCodec.kt:463)
  at QqPlaylistStore.readList(QqPlaylistStore.kt:84)
  at QqPlaylistRepository.loadList(...)
  at QqPlaylistScreenKt$QqPlaylistScreen$load$1.invokeSuspend(QqPlaylistScreen.kt:102)

com.google.gson.internal.LinkedTreeMap     -> a4.n
...playlist.PlaylistCacheCodec$PlaylistDto -> I4.j
```

**根因**：`ListEnvelope.playlists` 声明成 `List<PlaylistDto>?`，元素类型只能靠**字段的泛型签名 attribute** 得知。
`playlist.**` 没有 proguard keep 规则（既有的 `lyric.**` 有），R8 把这个 attribute 丢掉 ⇒
Gson 看到裸 `List` ⇒ 元素按 `Object` 反序列化成 `LinkedTreeMap` ⇒ `as PlaylistDto` 崩溃。
**debug 不混淆，所以完全正常** —— 这正是「只测 debug 会漏」的实例。

**修法（结构性，不靠 keep 规则）**：内层数组**存成字符串**，解析时用**编译期捕获**的
`TypeToken<List<PlaylistDto>>`（项目里 `HomeSnapshot` / `LyricsCache` 用的就是这套），
**不依赖任何字段泛型签名**。另外在 `proguard-rules.pro` 补 `-keep class ...playlist.** { *; }` 作第二道防线。

**回归防护**：新增单测 `信封里的数组必须以字符串字段承载——R8 泛型签名回归`
（断言编码结果含 `playlistsJson` / `songsJson`，且不再出现 `"playlists":[`）——
谁改回泛型 List，**单测立刻红**，而不是等到 release 装机才崩。

### 4.2 头部与顶部 scrim 重叠（`04-qq-list.png` → `10-qq-list-release-fixed.png`）

第一版把页面标题交给 `DetailScaffold(title = ...)`，实测「qm 歌单」被返回箭头压住
（截图里只剩「音乐」两字可见）。查 `DetailScaffold` 的 KDoc 才知道 **`title` 参数已弃用**
（「页面标题由 header 本身承担」），既有详情页都是 `DetailHeader` 自己渲染标题。

修法：`title = ""`，标题由本页 header 渲染，并按 `DetailHeader` 的约定用 `top = 56.dp` 让开 scrim。

### 4.3 QQ 入口被 ncm 加载状态绑架（`17a-before-tap.png`）

QQ 入口原本是 ncm 歌单网格的第一格 ⇒ ncm 在转圈 / 报错 / 空列表时，**用户根本点不到 QQ 歌单**。
实测 ncm 歌单卡在加载态 10s+，QQ 入口整个不可见。

修法：把入口**提到 `when` 之外**（`Column { QqPlaylistEntryRow(); Box(weight) { when { ... } } }`）。
qm 是独立音源，它的入口不该被另一个音源的加载状态决定。
验证：`18-entry-visible-while-loading.png` —— ncm 仍在转圈，QQ 入口已经可见可点。

---

## 5. 故障注入的**恢复**（设备已还原）

登录态过期测试改动了 `ncrust_qq_prefs.xml`。按本仓库既有约定（先 `su -c cp` 备份、
`am force-stop` 之后再写、测完还原）：

```bash
# 备份
adb shell su -c 'cp /data/data/.../shared_prefs/ncrust_qq_prefs.xml /data/local/tmp/qq_prefs.bak'
# 注入（只改票据，保留 uin 与字段存在）
adb shell su -c 'sed -i "s/qqmusic_key=[^;]*/qqmusic_key=INVALIDTICKET/g; \
                          s/qm_keyst=[^;]*/qm_keyst=INVALIDTICKET/g" .../ncrust_qq_prefs.xml'
# 还原
adb shell su -c 'cp /data/local/tmp/qq_prefs.bak .../ncrust_qq_prefs.xml && chown u0_a334:u0_a334 ...'
adb shell su -c 'rm -f /data/local/tmp/qq_prefs.bak /data/local/tmp/qq_pl.bak'
```

还原后复核（脱敏，不打印值）：

```
残留 INVALIDTICKET: 0
qq_cookie len=444 fields=['uin','qqmusic_uin','qqmusic_key','qm_keyst','psrf_musickey_createtime']
```

与注入前的初始读数**逐字段一致**（长度 444、字段集合相同）⇒ 登录态已完整还原，临时文件已删。
歌单缓存 `ncrust_qq_playlists.xml` 未受影响（本就是本版新增的数据，属正常产物）。

---

## 6. 未能验证 / 明确缺口（不掩盖）

| 项 | 状态 | 原因 |
|---|---|---|
| **S6（Android 7.0 / API 24）界面渲染** | ⚠️ **未确认** | debug APK 装上了（`dumpsys` 报 `versionName=2.2.0-gpl`）、`am start` 返回 `Starting: Intent{...}`、logcat 无 `FATAL`/`VerifyError`/`LinearAlloc`/`MethodTooLarge`；但**截图前设备从 USB 掉线**（本次会话中该机掉线两次），无法确认界面真的画出来 |
| **release APK 在 API 24 上安装** | ⚠️ 未做 | S6 上原装的是 **debug 签名**（`CN=Android Debug`，摘要 `e10c8b4d…`），与本版 release 签名（`CN=Ncrust GPL Fork`）不符，`INSTALL_FAILED_UPDATE_INCOMPATIBLE`。要装 release 必须先卸载，而卸载会**销毁该机的登录态**（我无法重建）⇒ 判断为不该做的破坏性操作，改用 debug 验证 |
| 500+ 首歌单端到端分页 | ⚠️ 未验证 | 该账号最大自建歌单 22 首；公开榜单最大 300 首；造 500+ 歌单属**写操作**（本版禁止）。替代证据：大 `song_num`(500/1000/2000) 被接受、逐页游标不重叠、越界优雅降级（探针 §3.3），以及单测覆盖分页边界 |
| 两个**真实** QQ 账号互切 | ⚠️ 未验证 | 两台已 root 设备的 `uin` 哈希一致（同一个账号）。已用「切到失效态」覆盖降级路径；账号隔离由 `PlaylistKey` 三元组 + 缓存读侧 `OWNER_MISMATCH` 闸门 + 单测覆盖 |
| 收藏（他人）歌单的**非空**列表 | ⚠️ 未验证 | 该账号 `v_list` 为空（`total=0`）。接口本身已验证 `code=0`，且必须传 `encrypt_uin`（传裸 uin 得 `80050`） |
| 华为控制中心卡片 | 不涉及 | 任务书第 14 条：已判定为 ROM 白名单，本次不碰、不扩散 |

---

## 7. 复现脚本

```bash
# 1) 构建
cd Ncrust-v220
./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease --offline

# 2) 装机（PLC110：release）
adb -s 3B15CD00GB700000 install -r app/build/outputs/apk/release/app-release.apk
adb -s 3B15CD00GB700000 shell am start -n com.takahashirinta.ncrust/.MainActivity
# 库 → 歌单 tab → 「qm歌单」
#   注意：本 ROM 上 uiautomator dump 会被 SIGKILL，只能用「截图 + 坐标点击」驱动

# 3) 离线
adb -s <serial> shell "settings put global airplane_mode_on 1; \
  am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true"
adb -s <serial> shell svc wifi disable; adb -s <serial> shell svc data disable
# 进入 QQ 歌单页 → 点右上角刷新 → 应出现「离线模式」横幅且列表仍有数据

# 4) 登录态过期（务必先备份、测完还原，见 §5）
# 5) 还原后复核残留 INVALIDTICKET == 0

# 6) 探针复跑（只读，148 次请求，写接口零调用）
cd ../  &&  python3 tools/probe-qq-playlist.py --serial 3B15CD00GB700000
```

---

## 8. 关于截图里的内容（如实说明）

`device/` 下的截图是**真机屏幕原图**，因此包含测试账号**真实可见的界面文本**：
歌单名（「我喜欢」「新建歌单1」）、曲目名与歌手名、以及曲目数。

- 这些是**设备屏幕的忠实记录**，也是 UI 验证不可替代的证据；本仓库既有验证目录
  （`docs/verification/v2.1.5/`、`v2.1.6/`）同样提交界面截图；
- **没有**任何账号凭证进入截图：uin / 昵称 / 头像在 QQ 歌单页**本来就不显示**，
  截图里也不存在 12 位以上的数字串（已用脚本逐个 PNG 复核，
  正则命中项全部是 PNG 压缩流的字节噪声，不是可见文本）；
- 探针落盘的 JSON **全部脱敏**（`<redacted:N>` 225 处、`<text:N>` 2738 处），
  只有公开目录 id（songid / songmid / media_id / dirId / tid）按结论需要保留。

> 若维护者认为「歌单名 / 歌名」也不宜入库，删掉 `device/*.png` 即可 ——
> 结论与命令都在本文与 `EVIDENCE.md` 里，截图只是佐证。
