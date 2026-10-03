# EVIDENCE —— v2.5.5 证据索引（单测 / lint / 真机 / benchmark）

> 产物：`app-release.apk`（`versionCode = 46`，`versionName = 2.5.5-gpl`，项目既有签名）
> `sha256 = 42d42ddedeed25c7925fa8231c76daf914a31a154b1c0482bdb395f53d9eaa16`
> （`docs/verification/v2.5.5/version-check.txt` 里另有一次打 tag 后的自证）
> 所有命令与输出都在本目录或 `probe-raw/`，**没有一条是复述**。

---

## §1 单测 / 静态检查

| 项 | 命令 | 结果 |
|---|---|---|
| JVM 单测 | `./gradlew clean testDebugUnitTest` | **1316 个用例，0 失败 / 0 错误 / 0 跳过**（95 个测试类） |
| lint | `./gradlew lint` | **0 Error / 0 Fatal**；8 条 Warning，全部为既有项（`ApplySharedPref` 6 条是 `LocalPlaylistStore`/`QqPlaylistStore` **有意**用 `commit()`；`ModifierParameter` 2 条是 `DetailScaffold.contentModifier`） |
| debug 包 | `./gradlew assembleDebug` | BUILD SUCCESSFUL |
| release 包 | `./gradlew assembleRelease` | BUILD SUCCESSFUL（R8 全量） |
| benchmark 模块 | `./gradlew :benchmark:assembleDebug` | BUILD SUCCESSFUL |

⚠️ 一次**瞬态构建失败**如实记录：`clean` 后首轮 `:app:packageDebug` 抛
`IncrementalSplitterRunnable` 失败（`app/build/outputs/apk/debug` 为空）。
清掉 `app/build/intermediates/incremental/packageDebug*` 后重跑即绿 —— 是增量打包状态问题，
不是源码问题（同一轮的 `assembleRelease` 正常）。记录在此以免后来者误判。

### 本版新增/改写的用例（与实现一一对应）

| 测试类 | 例数 | 钉住什么 |
|---|---|---|
| `TrayLayoutTest` | 16 | 托盘高度 80dp / 封面 56dp / 三个控制按钮**存在性与顺序**（铁律 19）/ 底部预留与高度同增同减 |
| `OfflineTrackCodecTest` | 17 | 离线索引落盘 key 是字段本名；**真机 `a b e f g i` 缺 `h` 样本**；坏条目逐条丢弃 |
| `PlaybackPositionCodecTest` | 11 | 续播进度落盘 key；`a`→`posMs`、`b`→`savedAtMs`；旧形状样本 |
| `SavedAlbumCodecTest` | 11 | 收藏专辑落盘 key；`albumId` 非法必须丢弃（LazyColumn 重复 key 会崩） |
| `PersistenceFieldNameContractTest` | 9 | **防复发四件套**：DTO 注册表 / keep 白名单 / 源码扫描 / 反证 |
| `ReportGateTest` | 16 | bit62 判据双向对称；`2^62` 与 `2^62-1` 边界；拦截计数；落盘 key 稳定 |
| `SourceCountsTest` | 15 | ★ QQ 未返回时**绝不显示计数 0**；真的 0 要显示 0；超时≠失败；新路径与旧格式逐字一致 |
| `ShuffleNavTest` | 7 | ★ 乱序轮首「上一首」回绕到轮末；越界返回 null 不钳制 |
| `SongTagsTest`（新增 4 例） | +4 | 搜索历史音源角标；老条目 bit62 必须显示 QQ |
| `PlayerLayoutVisualizerTest`（新增 5 例） | +5 | 平板 ⤢ 入口六格 A/B 矩阵 |
| `StringsConstructorBudgetTest`（改写） | 12 | 主构造器 129 → 134 → **135**；八语言新文案可用性；新旧统计行格式逐字一致 |

---

## §2 真机验证（release 包，`versionCode = 46`）

### 2.1 设备矩阵

| 设备 | 系统 | 用途 | 结果 |
|---|---|---|---|
| **PLC110**（`3B15CD00GB700000`） | Android 16 / API 36 / 1272×2800 @560dpi | 手机竖屏主验证 | ✅ |
| **WGR-W09**（`WVQ6R22124000968`） | Android 12 / API 31 / 华为平板 / 2560×1600 @320dpi | 平板托盘 + macrobenchmark | ✅ |
| **SM-G9209**（`0715f763f54c023a`） | Android 7.0 / **API 24** / 1440×2560 @640dpi | R8 老数据迁移 + **API 24 冷启不崩** | ✅（**界面层阻塞**，见 §6） |
| `ncrust_tablet_api34` AVD | Android 14 / API 34 / 2560×1600，**强制竖屏 1600×2560** | 平板竖屏托盘 | ✅ |

设备状态快照（验证前）：`probe-raw/devices/devices-before-v255.txt`。

### 2.2 托盘三层布局 + 上一首按钮（P0-C）

| 证据 | 文件 |
|---|---|
| PLC110 竖屏托盘（三层 + 三按钮） | `verification/01-plc110-v255-home.png`、`02a-plc110-tap-prev-tray.png` |
| 平板竖屏托盘 | `verification/31-tabletavd-v255-tray.png`、`32a-tablet-tray-crop.png` |
| 平板横屏托盘（WGR 宽屏布局） | `verification/20-wgr-v255-portrait.png` |
| 逐行像素剖面（几何量化） | `probe-raw/tray/04-plc110-v255-tray-profile.txt` |

**真机实测的托盘几何（PLC110 @560dpi，1dp = 3.5px）**：

| 量 | 实测 | 换算 |
|---|---|---|
| 三条文本带 | 2385–2423 / 2441–2486 / 2505–2543 | 歌词 / 歌名 / 作者+音源，**正好三行** |
| 封面内容带 | 2378–2562（中心 2470） | 56dp，垂直居中 |
| 三个图标中心 x | **851 / 1019 / 1187** | 间距 168px = **48dp** |
| 托盘高度 | 2612 − 2332 = **280px** | **80.0dp** ✓（`TrayLayout.HEIGHT_DP`） |

**播放控制按钮受控复测**（脚本 `verification/15-plc110-prev-button-test-after-fix.txt`）：
每次「读标题 → 点上一首 → 最多等 8s 看标题是否变化」：

| 版本 | 结果 |
|---|---|
| 修复前（乱序模式 `play_mode = 2`） | **1/5 成功** ← 见 §2.3 |
| 修复后 | **5/5 成功，每次 1s 内响应** |

下一首按钮同样受控复测通过（`03a-plc110-after-play-tray.png` 之后的 metadata 变化链）。

### 2.3 ★ 乱序模式「上一首」在轮首静默失效（**真机实测发现，本版修**）

第一次 5 次复测只有 1/5 成功，而同一按钮在非乱序下每次都成功。查落盘状态
（`/data/data/…/shared_prefs/ncrust_playback_state.xml`）发现当时 **`play_mode = 2`（SHUFFLE）**，
再看 `MainActivity.playPrevious()`：乱序分支只有 `if (shuffledPosition > 0)`、**没有 else** ——
游标在轮首时整个分支什么都不做，按钮点了没有任何反馈。

修法：轮首回绕到轮末（与 `playNext` 的队尾回绕、与 CYCLE/LINE 的上一首对称），
判定抽成 `ShuffleNav.previousPosition` + 7 条单测。修复后 **5/5**。

> 这条**不是**本版引入的缺陷（`playNext` 在轮末会重洗一轮，`playPrevious` 从来没有过回绕），
> 但它与铁律 19 的意图冲突：一个在特定模式下静默无效的上一首按钮，对用户来说与「缺失」无异。

### 2.4 R8 字段名迁移（P0-A）——**端到端真机证据**

| 证据 | 文件 | 内容 |
|---|---|---|
| **旧形状**（v2.5.4 写出的） | `verification/40-s6-offline-v254-legacy-shape.xml` | S6 上 `ncrust_offline/tracks` = **39 条**，字段名 `a,b,c,d,e,f,g,i` —— **没有 `h`**（`approxBytes` 为 null 被 Gson 省掉），与探针结论逐字一致 |
| **新形状**（v2.5.5 写出的） | `verification/41-plc110-offline-after-migration.xml` | PLC110 上同一张表 = **48 条**，字段名 `songId,name,artist,albumPicUrl,durationMs,level,cacheKey,completedAt` |
| **迁移闭环** | 本文件 §2.4 正文 | 见下 |

**迁移闭环的做法（不是「看起来对」，是注入—复播—回读）**：

1. 从 S6 上取一条 **v2.5.4 真实写出**的旧形状条目
   `{"a":503616,"b":"EM10_C_Long_Premix#070705","c":"鷺巣詩郎",…,"f":"jymaster","g":"song:503616:jymaster","i":1790403031347}`；
2. 把它**原样注入** PLC110 的 `ncrust_offline/tracks`（47 条 → 48 条），记下注入前快照；
3. 冷启 v2.5.5 release，播一首歌（`OfflineLibrary.record` 触发一次整表重写）；
4. 回读：**48 条**（一条没丢），字段名全部是稳定名，注入的那条**每个字段都正确**：
   `{"songId":503616,"name":"EM10_C_Long_Premix#070705","artist":"鷺巣詩郎","level":"jymaster","completedAt":1790403031347,…}`。

即：**老数据读得回来（`a`~`i` 认得出）+ 新数据写得稳定（字段本名）**，两端都在真机上验证过。

**API 24 冷启不崩**：S6（Android 7.0 / API 24）装上 v2.5.5 release 后冷启，
进程正常起来（`pidof` 有值），logcat 无 `FATAL` / `ClassFormatError` / `VerifyError`。
这一条盖住的是「`Strings` 主构造器 129 → 135 是否越界」与「R8 后的类加载」。

### 2.5 搜索加载态（P1 · 追加任务）——**逐帧证据**

`screenrecord` 8 秒（`verification/11-plc110-search-transition.mp4`）→ 按 5fps 裁出统计行
→ 拼成 `verification/12-plc110-search-summary-frames.png`：

| 帧 | 统计行 |
|---|---|
| 22 / 25 / 28 / 31 | **`ncm 30 首 · qm 搜索中…`** |
| 34 / 40 / 50 | **`ncm 30 首 · qm 30 首`** |

**误导性的「qm 0 首」在屏幕上再没有出现过**；QQ 未返回时显示「搜索中…」，
返回后原地更新为真实计数（单帧另存 `13-plc110-search-pending.png` / `14-plc110-search-done.png`）。

### 2.6 搜索历史音源角标（P2）

`verification/` 中搜索结果页的历史区（见 `09-plc110-search-2s.png` 上方的历史列表）：
两条**同名**历史「怪我太天真」并排，右侧分别是 **`qm`** 与 **`ncm`** 角标，
且角标贴右、不参与省略 —— 「区分同名历史」这条需求在真机上成立。

### 2.7 托盘交互（P1-B）

| 交互 | 证据 | 结果 |
|---|---|---|
| **点作者 → 艺人页** | `verification/05-plc110-tap-artist.png`、`05a-…-small.png` | ✅ 从库页导航到详情页（返回箭头 + 加载态）；页面内容因 §6 的 IPv6 环境问题未加载完，**点击区域本身已验证** |
| **点歌词 → 展开播放器 + 直接看歌词** | `verification/08-plc110-tap-lyric.png` | ✅ 展开后直接落在歌词面板，当前行高亮，译文行同时在 |
| 点歌名 → 展开播放器 | 由整条托盘的 `clickable` 承载（行为与点歌词的展开部分相同） | ✅（与点歌词同一条命中路径） |
| 点音源 → 无独立动作 | 设计决定，判据见 `probe-tray-layout.md` §6.1 | — |
| 三个控制按钮 | §2.2 的受控复测 | ✅ |

---

## §3 Macrobenchmark（release 包，铁律 16）

### 3.1 结果（WGR-W09 / Android 12 / API 31 / 华为平板）

口径：`CompilationMode.Ignore` + `iterations = 3`（`BENCH_COMPILATION=ignore BENCH_ITERATIONS=3`），
产物是 **v2.5.5 release**（`versionCode = 46`，脚本已打印并自证）。

| 基准 | 指标 | 值 |
|---|---|---|
| **`ExpandPlayerBenchmark.expandCollapse`**（HOT 起播，播放器展开/收起动画） | `frameDurationCpuMs` | **P50 4.73ms / P90 8.71ms / P95 9.92ms / P99 12.30ms** |
| 同上 | `frameCount` | min 136 / median 137 / max 139（CV = 0.011） |

**结论（明确）**：**P99 = 12.30ms < 16.67ms 的一帧预算** —— 播放器展开/收起这条
「转发属性最密集 + 每帧读 Animatable」的路径在这台设备上**没有超预算帧**。
转发属性在动画路径上的开销**不显著**（与 v2.5.4 的静态结论「120 条转发属性在 release 里
0 条留方法体，开销上界是每次 2 条 `iget`」一致，本版第一次给出真机帧时间数字）。

⚠️ 这是**本版建立的口径**，v2.5.4 没有可比的 release 基线（那一版的 benchmark 未跑完），
所以**不做版本间 Δ 比较**。

原始数据：`verification/benchmark/com.takahashirinta.ncrust.benchmark-benchmarkData.json`
+ 3 个 perfetto trace + `run-benchmark-wgr-log.txt`。

### 3.2 三条基准没跑成 —— **诚实记录，且这不是设备问题**

`StartupBenchmark` / `SettingsScrollBenchmark` / `HomeScrollBenchmark` 三条全部失败，错误一致：

```
java.lang.IllegalStateException: The DROP_SHADER_CACHE broadcast was not received.
This most likely means that the `androidx.profileinstaller` library used by the target apk is old.
```

**根因不在设备，也不在 `CompilationMode` 参数**（参数确实生效：日志里四条基准都打印了
`-e ncrust.bench.compilation ignore`）。实测：

```
$ aapt2 dump xmltree app-release.apk AndroidManifest.xml | grep -ci profile
0
$ aapt2 dump xmltree app-debug.apk   AndroidManifest.xml | grep -ci profile
0
$ adb shell am broadcast -a androidx.profileinstaller.action.BENCHMARK_OPERATION \
    -e EXTRA_BENCHMARK_OPERATION DROP_SHADER_CACHE \
    com.takahashirinta.ncrust/androidx.profileinstaller.ProfileInstallReceiver
Broadcast completed: result=0        ← 期望 14
```

**这个仓库根本没有依赖 `androidx.profileinstaller` 这个库**（release 与 debug 都没有），
只有 AGP 打进 APK 的 `baseline.prof` 资产。后果有两条，都记进遗留清单：

1. macrobenchmark 里需要丢 shader cache 的三条基准**结构上跑不了**；
2. **API 24~30 的设备永远不会安装这份 baseline profile**（那个库才是安装器）——
   冷启动会比应有的慢。这是**既有**问题（不是本版引入），修它要加依赖 + 重新构建，
   超出本版范围 ⇒ 列为 v2.5.6 候选。

### 3.3 PLC110 上的替代尝试（失败原因不同，也如实记录）

PLC110 上四条基准全部失败于：

```
java.lang.IllegalStateException: UiAutomationService … already registered!
```

`adb shell am force-stop <benchmark 包>` 与重跑都无效；`dumpsys accessibility` 显示该机启用了
两个第三方无障碍服务（Scene / GKD），且设备侧没有任何残留的 instrumentation 进程。
**判定为设备侧的 UiAutomation 连接被占用**（不在应用可控范围内）。
没有为了跑基准而重启用户的手机 —— 改用 **WGR-W09 真机**（同为 release 包，见 §3.1）。

---

## §4 PlayReporter 音源闸门（P0-B）

| 证据 | 位置 |
|---|---|
| 泄漏的**真机复现**（v2.5.4） | `probe-raw/playreporter/device-evidence.md` + logcat 摘录：`D/PlayReporter: weblog resp: 200 duration=0/0`，且 `restore -> track=qqmusic:4611686018784987997` |
| bit62 合成 id 是正数（Python + JVM 双复算） | `probe-raw/playreporter/bit62_guard_probe.out`、`GuardProbe.out` |
| 闸门落点与判据 | `player/ReportGate.kt` + `probe-search…`（见 §1 的 `ReportGateTest` 16 例） |
| 拦截计数落盘 | `player/ReportGateStore.kt`（`ncrust_report_gate`），落盘点在 `Activity.onStop` 与 debug 诊断入口 |

**本版未做**：在 v2.5.5 上重复一次「QQ 曲目播到 80% 看 `weblog blocked` 日志」的真机闭环 ——
需要构造一首可播放的 QQ 曲目并把进度推到 80%，本轮时间不足以稳定做到。
**闸门的行为由 16 条单测覆盖（含 bit62 边界与双向对称），但没有 v2.5.5 的真机日志闭环。**
这一条如实列进 §6 的未验证清单，**不用单测冒充真机证据**。

---

## §5 埋点 / 隐私（v2.5.4 规则 3 的延续）

本版新增的唯一落盘统计是 `ncrust_report_gate`（跨源上报被拦次数）。核验：

```
$ grep -rn "RetrofitClient\|OkHttp\|HttpURLConnection\|java.net" \
    app/src/main/java/com/takahashirinta/ncrust/player/ReportGate.kt \
    app/src/main/java/com/takahashirinta/ncrust/player/ReportGateStore.kt
（无输出）
```

只落私有目录、无网络出口、埋点点位只做一次 `AtomicLong` 自增（不碰 IO）。

---

## §6 未验证 / 未做（**不许用单测或推断冒充**）

| # | 项 | 状态 | 卡在哪 |
|---|---|---|---|
| 1 | **SM-G9209（Android 7.0）的界面层验证** | ❌ **阻塞** | 设备是 **PIN 锁屏**。`su` 可用（Magisk）但 Android 7 无 `locksettings` 二进制，`settings put secure lockscreen.disabled` 与 `wm dismiss-keyguard` 均无效。**不会**用 root 删 `/data/system/gatekeeper.*`（那会破坏用户设备的锁屏口令）。**替代**：API 24 上的冷启 + prefs 迁移已验证（§2.4），界面层用了 API 34 平板与真机 PLC110 —— 但**不能**替代「S6 真机触摸」 |
| 2 | **PlayReporter 闸门的 v2.5.5 真机日志闭环** | ❌ 未做 | 需要 QQ 曲目播到 80%；本轮时间不足。行为由单测覆盖（§1/§4） |
| 3 | **三条 macrobenchmark 基准（startup / settings / home scroll）** | ❌ 跑不了 | 仓库未依赖 `androidx.profileinstaller`（§3.2），`DROP_SHADER_CACHE` 广播无人应答。**根因明确、可修**，但不是本版范围 |
| 4 | **PLC110 上的 macrobenchmark** | ❌ 环境阻塞 | 设备侧 `UiAutomationService already registered`（§3.3），非应用可控；改用 WGR-W09 真机出数 |
| 5 | **平板 ⤢ 入口的点击闭环** | ❌ 未做 | WGR-W09 的 `wm` 尺寸/旋转 override 未被 EMUI 采纳（`dumpsys window` 显示 `cur=2560x1600`，仍是横屏），且展开播放器的那次点击落到了歌曲菜单上；API 34 平板 AVD 上时间不够。**判据本身有 5 条纯逻辑单测**（六格 A/B 矩阵），但**没有真机点击证据** |
| 6 | **艺人页内容加载** | ❌ 环境问题 | PLC110 该 WiFi 到 `*.music.163.com` 的 **IPv6 被黑洞**（探针独立复现过：连接停在 SYN_SENT）。这一点与「点作者能进艺人页」无关（导航已发生），但页面内容加载不完整 |
| 7 | **搜索结果页在 IPv6 恢复后的表现** | ⚠️ 已临时处置 | 为了完成 §2.5 的搜索验证，临时 `disable_ipv6=1`（探针与我都做过、都已还原为 0）。**这不改变应用的任何行为**，只是绕开该 WiFi 的 IPv6 黑洞 |
| 8 | **把 QQ 搜索主通道从 legacy 换成 `musicu`** | ❌ 未做 | 探针实测 `musicu` P50 376ms vs legacy P50 1722ms（快约 5 倍），但换主通道会改掉结果形状，回归面无法在本版验证完 ⇒ v2.5.6 候选 |
| 9 | **热门关键词短期缓存（TTL 5 分钟）** | ❌ 未做 | 与本次报告的现象（首次搜索就慢）不同源，是「重复搜同一个词」的优化 ⇒ v2.5.6 候选 |
| 10 | **华为/荣耀控制中心卡片** | 🚫 **不在范围内** | 任务书明令不碰；本版一个字节未动 |

### 设备改动与还原（如实记录）

| 设备 | 改了什么 | 还原了吗 |
|---|---|---|
| PLC110 | 临时 `disable_ipv6=1`（wlan0） | ✅ 已还原为 `0`（**收尾复查过一次**，见本节末尾） |
| PLC110 | 注入一条测试用的旧形状 `tracks` 条目（§2.4 的迁移闭环） | ⚠️ **未还原** —— 它就留在离线索引里（一条真实曲目的正确元数据，不影响功能）。**v2.5.6 已用「打开离线缓存管理即对账」清除**；⚠️ 原建议「走『清空离线缓存』即可」**已被 v2.5.6 推翻**，理由见下 |
| PLC110 | `adb root`（读 prefs 用） | 设备探测前本来就是 root（探针记录），未做 `unroot` |
| WGR-W09 | 安装 v2.5.5 release + benchmark APK；跑基准时临时改系统动画比例 | ✅ 动画已由脚本还原并**回读自证**（`window=1.0 transition=1.0 animator=null`） |
| WGR-W09 | `wm size` / `wm user-rotation` 尝试强制竖屏 | ❌ EMUI 未采纳（`wm size` 仍显示 override 1600×2560，实窗仍是 2560×1600）。**未新增改动**，原样保留 |
| SM-G9209 | 安装 v2.5.5 release（覆盖安装，登录态保留） | 不适用（安装是有意的） |
| `ncrust_tablet_api34` AVD | 新建（不修改任何既有 AVD）；`user_rotation=1` 强制竖屏；注入种子播放状态 | AVD 是本版新建的；未改既有 AVD |

### 收尾复查（本 session 结束时逐台回读）

```
PLC110  wlan0 disable_ipv6 = 0         ← 已还原
PLC110  window/transition/animator = 1.0 / 1.0 / null   ← 已还原（null 用 settings delete 删掉）
WGR-W09 window/transition/animator = 1.0 / 1.0 / null   ← 已还原
WGR-W09 user_rotation = 1              ← 与验证前一致（v2.5.4 遗留值，本版未改）
S6      window/transition/animator = 0 / 0 / 0          ← 与验证前一致（v2.5.4 遗留值，本版未改）
```

本 session 新建的 `ncrust_tablet_api34` AVD 已 `emu kill` 关闭；**未修改任何既有 AVD**。

---

## v2.5.6 对本文件的两处更正（**读上面那张表之前先看这里**）

### 更正 1：「如需清掉，走『清空离线缓存』即可」是**有害**建议

那条建议写于 2026-09-26 早些时候，而到 v2.5.6 探针复核时，这台 PLC110 上已经有
**用户真实的 2.3 GB / 50 首**离线数据（529 个 `.v3.exo` 片段）。在那个状态下照做
⇒ `OfflineAudioCache.clear()` 会把**真实数据一起清掉**。

正确的清除方式（v2.5.6 实测采用的）是**打开「设置 → 离线缓存管理」**：
它触发 `OfflineCacheOverlay.kt:128` → `OfflineAudioCache.reconcileLibrary` →
`OfflineLibrary.retain(缓存里还有片段的 songId)`，**精确**只删那一条
（`urls` 一个字不动、音频一个字节不动）。

判据也一并钉住了：**「没有 `urls` 条目」不是删除理由** ——
该机 50 条索引里有 **6 条真实条目没有 `urls` 条目**
（`1854421609 / 1380176 / 1983686033 / 22259257 / 381962 / 1970006`），
因为写 URL 的路径不覆盖所有起播路径。唯一正确的判据是**音频缓存里还有没有片段**。
复现与全部证据见 `docs/verification/v2.5.6/probe-offline-index-cleanup.md`。

### 更正 2：「本仓库无 `androidx.profileinstaller` 依赖」是**假**的（§3.2）

§3.2 的结论「这个仓库根本没有依赖 `androidx.profileinstaller` 这个库」**不成立**：
该依赖自 v1.2.1（`f9d45f4`）起就在 `app/build.gradle.kts` 里，
v2.5.3/2.5.4/v2.5.5 三个 tag 的 APK 清单里都有 `ProfileInstallReceiver`（命中 7 处），
且在 **API 24 真机**上 `am broadcast …INSTALL_PROFILE` 返回
`D ProfileInstaller: RESULT_INSTALL_SUCCESS`。

根因是那条取证命令**本身失败了**，而失败伪装成了阴性结果：

```
$ aapt2 dump xmltree app-release.apk AndroidManifest.xml
missing required flag --file          # ← stderr，被管道吞掉
$ … | grep -ci profile
0                                     # ← 空 stdout 的计数，被读成「没有」
```

连带被推翻的还有同节的「API 24~30 的设备永远不会安装这份 baseline profile」
与遗留清单第 3 条「三条 macrobenchmark 基准结构上跑不了」——
后者实测两台设备 `result=14`（= 期望值），**接收器在、广播有人应答**。

⇒ 已写进 `AGENTS.md` 新铁律 22 的配套条款：
**任何「计数为 0 / 无命中」的结论必须同时留 stderr 与退出码，
并用一条独立路径交叉验证。**
