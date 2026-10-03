# v2.1.5 验证证据索引

> 目标版本 `2.1.5-gpl` / versionCode 35。所有证据都来自**真机**或**可复现命令**，
> 没有任何一条是推断出来的结论。凡未验证的，在各文件里显式标注。

---

## 1. 设备

| 序列号 | 型号 | 系统 | 用途 |
|---|---|---|---|
| `0715f763f54c023a` | SM-G9209（Galaxy S6） | Android 7.0 / API 24 | **P0 主验证机**：ncm 与 qm 都已登录、已 root、可 `run-as` |
| `3B15CD00GB700000` | PLC110（一加 / ColorOS 系） | Android 16 / API 36 | P1 能力矩阵 |
| `WVQ6R22124000968` | WGR-W09（华为平板） | Android 12 / API 31（HarmonyOS 4.2 / EMUI 14.2.0） | P1 能力矩阵（用户报障机型） |

`0715f763f54c023a` 是唯一同时满足「两个音源都已登录」+「可写 SharedPreferences（root）」
+「API 24（本仓库要兼容的下限）」的设备，所以 P0 的 A/B 在它上面做。

---

## 2. P0：跨源切歌歌词串台

### 2.1 场景

队列 `[qqmusic 晴天(id=4611686018427485677, mid=0039MnYb0qxYhV, media=003Qui1q2u1Zho),
netease 屋顶(5257138)]`，从队列起播 QQ 那首，`seek` 到 266s（该曲 269s）
让它**自然播完**，由 ExoPlayer 自动接续到预载好的 ncm 那首。

队列用 `tools/seed-cross-source.py` 生成后经 root 写入
`ncrust_playback_state.xml`（**必须在 `am force-stop` 之后写**，否则内存镜像会覆盖回来）。

### 2.2 A/B 结果

| | `p0-before-fix/`（HEAD `d6b0fce` = v2.1.4，**未修复**） | `p0-after-fix/`（v2.1.5，**已修复**） |
|---|---|---|
| 接续 | `gapless transition -> songId=5257138 title=屋顶` | `gapless transition -> songId=5257138 source=netease title=屋顶` |
| 取词路径 | **`qq lyric songId=5257138 lines=63`** | `歌词源 songId=5257138 phase=1 picked=YRC lines=55` |
| 媒体面板 title | `录音助理：刘勇志` | `编曲 : 屠颖` |
| 媒体面板 artist | `屋顶 · 周杰伦` | `屋顶 · 周杰伦` |
| 结论 | 显示的是**《晴天》**的词（63 行 = QQ QRC 行数） | 显示的是**《屋顶》**自己的词（55 行） |

### 2.3 判据为什么是硬的

- 《屋顶》的 ncm lrc 共 **55 行**，前三行是 `作曲 : 周杰伦` / `作词 : 周杰伦` / `编曲 : 屠颖`
  —— 直接读设备上的 `ncrust_lyrics_cache.xml` 得到，命令写在 `p0-after-fix/TRACE.txt` 的注释里；
- `录音助理：刘勇志` 与 `制作人` 在《屋顶》的 lrc 里**一次都不出现**（逐字核对，`False`）；
- 63 ≠ 55，而 63 恰好等于 QQ 那首（《晴天》）的 QRC 行数 —— 与"请求用的是晴天的 songmid"吻合；
- 未修复版的 `qq lyric songId=5257138` 这行的存在本身就说明：**为 ncm 的歌发了 QQ 的取词请求**。

### 2.4 文件

| 文件 | 内容 |
|---|---|
| `p0-before-fix/TRACE.txt` | 未修复版日志切片 + 判据注释 |
| `p0-before-fix/logcat-full.txt` | 未修复版完整 logcat（`logcat -d -v time`） |
| `p0-before-fix/media_session.txt` | 未修复版 `dumpsys media_session` |
| `p0-before-fix/notification.txt` | 未修复版 `dumpsys notification --noredact` |
| `p0-after-fix/TRACE.txt` | 已修复版同场景日志切片（含期望值注释） |
| `p0-after-fix/logcat-full.txt` | 已修复版完整 logcat |
| `p0-after-fix/media_session.txt` | 已修复版 `dumpsys media_session` |
| `p0-after-fix/notification.txt` | 已修复版 `dumpsys notification --noredact` |

复现：`bash tools/verify-cross-source-lyrics.sh <serial>`（脚本会重跑整个场景并落证据）。

---

## 3. P1：控制中心媒体面板

见 `P1-MEDIA-PANEL-DECISION.md`（决策记录）。原始证据在 `p1-baseline/`：

| 文件 | 内容 |
|---|---|
| `p1-baseline/FINDINGS.md` | 三设备能力矩阵 + 「可 adb 验证 / 不可 adb 验证」分栏 |
| `p1-baseline/README.md` | 每个证据文件对应的**确切命令** |
| `p1-baseline/SOURCE_PROVENANCE.txt` | 证据采集时工作区是脏的，所有源码结论已按 `git show HEAD:` 复核 |
| `p1-baseline/LOCAL_LIBRARY_lyrics_key_evidence.txt` | 平台 / `MediaMetadataCompat` / media3 三方键集扫描（含被纠正的第一次正向对照） |
| `p1-baseline/t3_huawei_controlcenter.sh` | 华为 SystemUI `MediaDataManager` 的取证脚本 |
| `p1-baseline/<serial>/` | 每台设备的原始 `media_session.txt` / `notification.txt` / `audio.txt` / `settings_*.txt` |

**一句话结论**：应用把当前歌词行发布到了 MediaSession 的 `TITLE`，华为 SystemUI 的
`MediaDataManager` 确实消费了它；平台上**不存在**专门的歌词 metadata key
（三处键集扫描均为 0 命中），media3 1.5.0 也没有 `setLyrics()`。
控制中心卡片**肉眼**是否显示、是否实时，adb 无法判定 —— 本版不做声称。

---

## 4. 产物与装机

| 文件 | 内容 |
|---|---|
| `dex-check.txt` | 两个产物的 dex 静态扫描：**release 单 dex、29,369 个方法、max registers_size=248、0 个超 255**；debug 24 dex、11 个方法超 255（集中在 i18n `Strings` 与 `PlayerCard`，R8 在 release 里把它们拆开了）。含 DEX version 037 = API 24 格式的说明，以及**一次被实测纠正的错误推断**（我一开始以为 registers_size>255 会导致 ART 拒绝加载，真机反驳了它） |
| `release-install/media_session-<serial>.txt` | release 包在 WGR-W09（API 31）与 PLC110（API 36）上 **34 → 35 覆盖安装成功**后的 `dumpsys media_session` |
| `..`（APK 本体） | `dist/Ncrust-v2.1.5-gpl-release.apk` / `-debug.apk`；`apksigner` 证书 SHA-256 与 v2.1.4 **逐字节相同** |

## 5. 静态门禁

| 文件 | 内容 |
|---|---|
| `lint-report-HEAD-unmodified.txt` | **未改动的 HEAD 上 `lintDebug` 本来就是红的**（57 error，全是依赖版本/opt-in 类既有问题）。留档是为了说明本版为什么引入 `lint-baseline.xml`，以及基线里没有本次新增的文件 |
| `p0-unit-tests.txt` | `testDebugUnitTest` 结果：587 用例 / 0 失败（含本版新增 48 例） |

---

## 5. 未验证清单（不得当作已验证）

1. **P1 的屏幕侧结论**：华为控制中心肉眼是否显示歌词、频率、整行还是逐字、锁屏是否显示 ——
   adb 无法判定。
2. **真机跨源连切 20 次**：单测有 20 轮确定性覆盖；真机完成的是**一次**完整的
   QQ → ncm 自然完播接续（A/B 各一次）。原因：构造跨源队列需要真实 QQ songmid，
   界面不显示，本次靠新加的探针手工取到一组，尚未做成全自动连切脚本。
3. **反向（ncm → QQ）真机 A/B**：同一接缝，只有单测覆盖。
4. **API 24 上的媒体会话元数据**：该机 `dumpsys` 里没有 Ncrust 会话，属测量缺口。
5. **华为私有歌词协议**（`LyricUtil` / `updateMediaCommand LYRIC_STATE`）对第三方是否可达。
6. **release 包没有在 Android 7.0 / API 24 上装过**：该机上装的是 debug 构建，两者签名不同，
   换装必须先卸载、会清掉用户登录态与 1066 首队列。dex 格式与寄存器指标已静态覆盖
   （且 release 比 debug 更干净），但「release 在 API 24 冷启」仍是缺口。
7. **降级重试路径**：A/B 中 QQ 曲目在 `hires` 档遇到 404 后降到 `lossless`
   （日志可见），该行为与 v2.1.4 一致、本版未改，但没有专门为它做回归。
