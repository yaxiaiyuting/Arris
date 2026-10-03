# PROBE-SUMMARY —— v2.5.5 六项探针的收敛结论

> 设备：PLC110（Android 16 / 1272×2800 @560dpi）、SM-G9209（Android 7.0 / **PIN 锁屏**）、
> WGR-W09（Android 12 / 华为平板 / 1600×2560 竖屏 override @320dpi）、
> Cuttlefish x86_64（Android 17）、`ncrust_api24` AVD（Android 7.0）、`ncrust_tablet_api34` AVD
> 基准产物：v2.5.4-gpl release（`versionCode = 45`）—— 探针阶段测的都是**上一个已发布版本**，
> 本版的修复效果另在 `EVIDENCE.md` 里用 v2.5.5 的 release 包验证。
> 探针人：本 session（自动）。**没有一条结论来自推断；跑不到的条目一律写「未验证」。**

---

## §0 结论先行（含**推翻任务书三处前提**）

| # | 任务书的前提 | 探针实测 | 处置 |
|---|---|---|---|
| 1 | §2.3/§5.4「v2.5.4 把托盘的上一首按钮删了/挤没了」 | **不成立**。`git log -S "SkipPrevious" -- PlayerCard.kt` **零命中**；v2.5.3 的托盘也只有播放/暂停与下一首 | 改按「补一个从未实现的核心功能」做（铁律 19） |
| 2 | §5.2「三层垂直间距统一走 AppShapes / AppMotion 规范」 | **不成立**。那两个文件是**圆角** token 与**动效** token，都不含间距；全仓库没有 spacing token 文件 | 间距落进 `TrayLayout.LINE_GAP_DP`（与高度同处唯一落点），偏差写进报告 |
| 3 | §6.1 隐含「v2.5.4 的 macrobenchmark 没跑完 = 设备不满足」 | **不成立**。S6 的 perfetto 能力与 `am instrument` 路径都正常；主因是**时间预算 + 参数只能改源码 + 无进度输出**，且 `EVIDENCE.md` 把源码里的 `CompilationMode.DEFAULT` 记成了 `Full` | 修脚本、参数化（`BenchArgs`）、加 release 硬断言，改在 PCL110 上实跑 |

六项探针各自的一句话结论：

| 探针 | 一句话结论 |
|---|---|
| **R8 单字母 key 全仓扫描** | 除已知的 `cache.**`（`ncrust_offline/tracks`）外，**又找到两个此前所有文档都没提过的实例**（`PlaybackStateManager$PositionEntry`、`library.AlbumInfo`），共 3 处必修、7 个历史实例 |
| **PlayReporter 跨源泄露** | **真机复现成功**：QQ 合成 id（bit62 置位 ⇒ 正数）通过了 `songId > 0` 卫语句，被 POST 给 ncm webLog（`weblog resp: 200`） |
| **托盘上一首按钮回归** | **不存在回归** —— 那个按钮从来没有存在过 |
| **托盘三层布局** | 可做，但必须把托盘从 56dp 加到 80dp（三行排版盒实测 56dp，56dp 托盘上下各剩 0dp）；同时有**三个** 56dp 消费者必须一起改 |
| **macrobenchmark 换设备** | 推荐 PCL110 + `CompilationMode.Ignore` + 3 迭代；预估 5–8 分钟（测量阶段）；`run_benchmark.sh` 有一个**会永久关掉设备动画**的 bug |
| **QQ 搜索延迟** | 延迟来源 = **串行编排 + QQ legacy 接口本身慢**（TTFB P50 1722ms / P95 2989ms，n=30）；**不是** token 初始化、**不是**映射、**不是**重试；「qm 0 首」在屏幕上挂了 **2.2–2.7 秒** |

---

## §1 R8 单字母 key 全仓扫描（`probe-r8-keys.md`）

**方法**：扫遍 `app/src/main/java` 的全部持久化路径（Gson `@SerializedName` / 手写
SharedPreferences / `filesDir` 文件 / 反射读字段名），逐项对照
`app/build/outputs/mapping/release/mapping.txt`（55 万行）与 `dist/Ncrust-v2.5.4-gpl-release.apk`
的 dex 反汇编（`apkanalyzer`）。**每一条 R8 名字都能指到 mapping.txt 的行号。**

### 必须修（3 处）

| 落点 | DTO | R8 实际映射 | 后果 |
|---|---|---|---|
| `ncrust_offline` / `tracks` | `cache.OfflineTrack` | `songId→a` … `completedAt→i` | 离线曲目清单静默消失（缓存字节还在，列表空了） |
| `ncrust_playback_state` / `song_positions` | `PlaybackStateManager$PositionEntry` | `posMs→a, savedAtMs→b` | 「位置↔时间戳对调」或**续播静默失效**（**本版新发现**） |
| `ncrust_library` / `saved_albums` | `library.AlbumInfo` | `albumId→a` … `songCount→e` | `albumId` 读成 0 ⇒ `LibraryScreen` 的 `key={it.albumId}` 重复 key 抛异常（**本版新发现**） |

**`AlbumInfo` 最容易被误判成安全**：同一个文件里的 `SongItem` 在 `network.**` 下被 `-keep` 保护，
而它是 `library/` 的顶层 data class，不在任何 keep 覆盖的包里。上一版把 `library.**` 整体记为
「已由 SearchHistoryCodec 修好」，于是它漏了过去。

### 已安全（有证据）

`ncrust_home_cache/*`、`ncrust_playback_state/queue`（`SongItem`，keep `network.**`）、
`ncrust_lyrics_cache/entries`（keep `lyric.**`）、`ncrust_qq_playlists/*`、`ncrust_match_cache/*`、
`ncrust_local_playlists/*`（keep 各自包）、`search_history/*` 与 `ncrust_qq_probe/stats`
（`@SerializedName`，dex 里字段名是单字母但**每个都带稳定名注解**）。

### 假阳性

`ncrust_offline/urls`（序列化的是 `LinkedHashMap<String,String>`，key 是数据串而非字段名）、
`theme_mode` / `accent_source`（写枚举 `.name`，dex 里 `Enum.<init>` 的字符串常量未变）、
`SavedState`（只拼 prefs 标量）、`ContentCache`（纯内存）、网络 payload（字面 key）、
Coil / media3 / PNG（二进制）。

### 处置（本版落地）

三条读法（稳定名 → 已知旧单字母 → 声明顺序）+ 逐条容错；
**没有**加 `-keep class …cache.**`（加 keep 只影响将来写出的形状，对已落盘的 `a`~`i` 无用，
反而会改掉全局混淆映射）；防复发是 `PersistenceFieldNameContractTest` 的四件套
（DTO 注册表 / keep 白名单 / 源码扫描 / 反证）。

---

## §2 PlayReporter 跨源泄露（`probe-playreporter.md`）

**上报路径**：`PlayReporter.reportPlay()` 在 ncm 登录态下
`POST https://clientlogusf.music.163.com/api/feedback/weblog?csrf_token=<__csrf>`，
表单 `logs=[{action:"play",json:{type,wifi(反),download,id,time,end,mainsite,mainsiteWeb}}]`；
主线程过卫语句 → 新建 `ncrust-weblog` 线程 fire-and-forget。

**现有卫语句拦不住 QQ id**：三条（`getCookie()!=null` / `contains("MUSIC_U") && songId>0` /
`getCsrfToken()!=null`）逐条 PASS。bit62 合成 id 实测十进制 **4611686018784987997**，
符号为正 ⇒ `songId <= 0` **在数学上不可能**拦住它。

**真机复现（成功）**：
```
18:35:12.270 NcrustTrack: restore -> track=qqmusic:4611686018784987997 …
18:35:20.097 PlaybackService: onStartCommand action=next（= onPlaybackEnded）
18:35:20.390 D/PlayReporter: weblog resp: 200 duration=0/0   ★上报真的发出且 HTTP 200
```
`duration=0/0` 是 `onPlaybackEnded` 分支的指纹（80% 路径因 `reachedCompletion` 要求 `dur>0`
不可能打 0/0），且 `sid` 在队列推进前读取 ⇒ 报的就是 QQ 合成 id。

**反方向不成立**：QQ 侧没有任何上报实现（`grep -rn "weblog\|postWeblog\|reportPlay"` 只命中 ncm 链路）。
仍然把它写进 `ReportGate.Target.QQ` 并加双向对称单测 —— 一条只写在文档里的规则拦不住
下一个加 QQ 上报的人。

**未验证**：报文里 `id` 的字节级内容（TLS，无 MITM）；ncm 服务端如何处理该 id；
80% 路径 + QQ id 的真机证据。**不提供任何频率数字。**

---

## §3 托盘上一首按钮回归（`probe-tray-regression.md`）

**结论：不是回归，是「从来没有过」。** 三条独立证据：

1. `git log --oneline -S "SkipPrevious" -- …/PlayerCard.kt` → **零个提交**；
2. `git show v2.5.3-gpl:…/PlayerCard.kt` 的托盘只有 `onPlayPause()` 与 `onPlayNext()`；
3. `git diff v2.5.3-gpl..v2.5.4-gpl -- …/PlayerCard.kt` 里那两个 `MetroIconButton` **逐字未动**。

`SkipPrevious` 全仓库只出现在 `FullPlayerControls.kt:246` 与 `:361`。

**真机几何（PLC110 @560dpi，逐行像素剖面）**：托盘 2408–2604 = 196px = **56.0dp**；
第 1 行字形 2458–2495，第 2 行字形 2508–2551，上下留白 50/53px。
排版盒合计 126px = 36dp，居中于 196px ⇒ 每侧 **10dp**（不是「挤压到看不见」）。

**三个（+1 派生）消费者必须一起改**：`PlayerCard` 的 `.height(...)`、
`MainActivity` 的 `miniBarHeightPx`（进 `collapsedOffsetY`）、`BottomOverlayInsetDp`；
派生量是 `miniCoverHalfPx`（封面落点中心）。

**未验证**：S6（Android 7.0）的**界面**层验证 —— 设备是 **PIN 锁屏**，
`locksettings` 二进制在 Android 7 上不存在、`settings put secure lockscreen.disabled` 无效、
`wm dismiss-keyguard` 无效；**不会**用 root 删除 gatekeeper 文件（那会破坏用户设备的锁屏口令）。
替代方案是 API 24 AVD（同一 API 级别），但它**不能**替代真机触摸。

---

## §4 托盘三层布局（`probe-tray-layout.md`）

**目标布局**：实时歌词 / 歌名（独占） / 作者（左）… 音源（右）。

**当前 Box 结构**：`Box(fillMaxWidth → statusBarsPadding → height(56dp) → clickable(展开) →
graphicsLayer{alpha} → background(surface))` 内套 `Row(fillMaxSize, CenterVertically)`；
无 `heightIn`、无滚动、无 `maxLines` 总量控制。

**高度预算（真机数字）**：`bodySmall(16sp) + bodyMedium(20sp) + bodySmall(16sp) = 52sp = 182px`；
56dp 的托盘只有 196px ⇒ 剩 14px（每侧 2dp，贴死）。**取 80dp**（每侧 12dp）；
72dp 在行间距之后只剩 8dp，仍然发紧。

**溢出处理**：歌词与歌名都是 `maxLines=1 + Ellipsis`；**不做跑马灯**
（托盘在播放全程常驻，跑马灯会持续排帧，与铁律 17 直接冲突）；
音源角标**永不省略**（定宽、贴右）。

**点击区域**：点歌词 = 展开 + 进歌词（v2.5.4 已有）；点作者 = 艺人页（v2.5.4 已有）；
点歌名 = 落到整条托盘的展开点击（**不加独立 clickable** —— 行为相同，多一个命中层只会
制造 AGENTS.md 触摸陷阱 §5/§6 那类问题）；**点音源不做独立动作**（三个候选动作逐个否决，
判据见该文档 §6.1）。

**三种形态**：托盘是同一份代码，`isWidePlayer` / `bigScreenActive` 都不参与它的布局。
窄屏文本列 **136dp**（360 − 56 封面 − 144 三按钮 − 24 padding）—— 这个数决定了「不加第四个按钮」。

**任务书前提修正**：`AppShapes`（圆角）与 `AppMotion`（动效）都**不装间距**，
全仓库没有 spacing token 文件 ⇒ 不为单一消费者新建全局 spacing 体系。

---

## §5 macrobenchmark 换设备（`probe-benchmark.md`）

**推荐**：PCL110（`3B15CD00GB700000`，API 36 / arm64 / root / 未锁屏 / 有 perfetto / 插电）
+ `CompilationMode.Ignore`（字节码实测 `shouldReset=false`，**完全不动** app 的编译态与登录态）
+ `iterations = 3` + `-e androidx.benchmark.suppressErrors=UNLOCKED`。
4 条基准 × 3 迭代 ≈ **5–8 分钟**（测量阶段），一轮完整测量 ≈ 15 分钟。

**v2.5.4 的真实卡点（三条，都不是「设备不满足」）**：
1. `EVIDENCE.md` 把源码里的 `CompilationMode.DEFAULT` 记成了 `Full`（字节码实测纠正：
   `DEFAULT` 在 API≥24 = `Partial(UseIfAvailable, 0)`）⇒ 原先的降级方案选错了杠杆；
2. **时间预算 + 无进度输出**：`am instrument -w` 只在唯一那个 `@Test` 跑完才吐结果，
   「20 分钟无输出」是设计行为，不是死锁；单迭代 ≥20s；
3. S6 本身极差（jank 66.88%、MemFree 84MB、load 4.17），且**现在仍锁屏**。

**顺带找到的两个结构性问题（本版都修了）**：
- `run_benchmark.sh` 的 `restore_animations` 在「原值 null」时**短路失效** ⇒
  PCL110 / Cuttlefish（原值就是 `null`）跑一次就被**永久关掉动画**，而脚本下一次跑
  `scroll / expand` 时面对的就是那台设备（对 ExpandPlayer 是致命污染）；
- 「必须用 release 包」原先只是**一句警告** —— 而 benchmark 模块**没有**任何结构性手段
  保证它（AGP 8.5.1 的 `com.android.test` DSL 只有 `targetProjectPath`）。

**设备矩阵一句话**：PCL110 ✅ 能用（推荐）；Cuttlefish ⚠️ 技术上能跑但不作基线
（必命中 `EMULATOR_`）；S6 ❌ 本轮不要用（锁屏阻断 + 硬件太差）。

**设备改动**：三台动画比例改了并**已逐台复查还原**；PCL110 与 Cuttlefish 各跑过一次 `adb unroot`
并已 `adb root` 恢复。未装/卸载任何 APK、未改权限、未动 dexopt/省电策略。

---

## §6 QQ 搜索延迟（`probe-search-qq-latency.md`）

### 五个必答问题

| 问题 | 答案 | 证据 |
|---|---|---|
| **5s 延迟的来源** | ①**串行编排**（结构性）：旧码 `await ncm → 发布 → 才发 QQ`，整体耗时是 **sum 而非 max**；②**QQ legacy 接口本身慢**：`client_search_cp` TTFB **P50 1722ms / P95 2989ms**（n=30 同网段直连，DNS 6ms / TLS 213ms，其余全在服务端）；③**不是** token 初始化（搜索路径**零** token/签名，`ptqrtoken` 只属扫码登录）；④**不是**映射（纯 JSON 遍历 30 条，上界 ≤0.3s）；⑤**无循环重试**，只有一条**一次性** legacy→musicu 兜底（本次 45 次全未触发） | 直连接口探测脚本 + app 自身 `aggregate` 日志 15 次 |
| **加载态现状** | **QQ leg 完全没有加载态**。`_isLoading = false` 在 ncm 到手那刻就执行，唯一的转圈是搜索框指示器；`songs.isEmpty() && !isLoading` 还会直接显示「没有找到歌曲」；QQ 单独失败/超时**静默** | 代码行号 + 逐帧截图 |
| **结果合并方式** | **替换 + 整表重排**（`SearchRanking.order(...).distinctBy{trackKey}`），不是追加 | `SearchViewModel.publish` |
| **是否被去重丢弃** | **不会**。`CatalogAggregator` 只在专辑/艺人/单曲详情 3 个 Screen 被调用，**不在搜索链路**；搜索里唯一的去重是 `distinctBy{trackKey}`，而 `trackKey` **含音源** ⇒ 同号不同源不合并 | grep + `SongSourceExt.kt` |
| **超时阈值与表现** | 预算 `QQ_SEARCH_BUDGET_MS = 5000`；**顶到预算时结果被整包丢弃、UI 永久停在「0 首」** —— 这正是用户「以为没有」的机制 | 代码 + 一次 max 4363ms 的实测 |

### 关键实测数字（PLC110 / v2.5.4 vc45 / 双登录）

- app 自身 `aggregate` 行 15 次：总耗时 **P50 2545ms / P95 4147ms / max 4363ms**，每次 netease=30 qq=30；
- 冷启首搜 2117 / **4363** / 2295ms；同进程热态 5 次 2034…2864ms ⇒ **无冷启/首次初始化效应**，
  变量是服务端抖动（接口 P95/P50 ≈ 1.7）；
- **逐帧 UI 证据**：「ncm 30 首 · **qm 0 首**」在 tap 后 **1.17–1.76s** 出现，
  直到 **3.98–4.50s** 才翻成「30 首」⇒ 假状态在屏幕上挂了 **约 2.2–2.7 秒**；
- QQ 兜底通道 `musicu` **P50 376ms**，比 legacy 快约 **5 倍**。

### 修复方案（按根因选，A 必做）

| 选项 | 做不做 | 依据 |
|---|---|---|
| **A 加载态** | ✅ **做** | 「0 首」是类型问题（`Pair<Int,Int>` 无法表达「未知」）⇒ 新增 `SourceCounts` + `SourceSearchStatus` |
| **B 并发** | ✅ **做** | 探针确认串行 ⇒ 两个 `async` 同时起飞，省掉 ncm 那一段的串行等待 |
| **C 预热** | ❌ 不做 | 探针证明**没有**首次/token 初始化效应（冷热差落在服务端抖动范围内），预热无收益 |
| **D 缓存** | ❌ 本版不做 | 是「重复搜同一个词」的优化，与本次报告的现象（首次搜索就慢）不同源；列入 v2.5.6 候选 |
| **E 降级** | ✅ 部分做 | 保留 5000ms 预算（P95 已接近 3s，砍到 3s 会丢掉约 5% 的结果，而**用户已经不再等待**：ncm 结果与「搜索中…」都已上屏）；超时/失败各给一句诚实话 + **可点的手动重试**（不做自动重试 —— 那会在用户已经往下翻时突然往列表里插结果） |

**本版不改、但如实记录**：把主通道从 legacy 换成 `musicu`（快约 5 倍）会改掉结果形状，
回归面无法在本版验证完 ⇒ 列为 **v2.5.6 候选**，并附上这次的实测数字。

### 未验证

S6（Android 7.0）**完全没参与 UI 实测**（PIN 锁屏）；映射层没有独立基准（只有上界）；
legacy→musicu 兜底未被真实触发；未做「主页路径 vs 搜索路径」逐字段比对；未测移动数据/弱网。
另如实记录一条**环境阻塞**：PLC110 该 WiFi 到 `*.music.163.com` 的 **IPv6 被黑洞**
（连接停在 SYN_SENT），前 4 次搜索因此卡死 10–22s；临时关闭该网卡 IPv6 后恢复，
**收尾已还原为 0**。所有真机数字都在该处置之后取得。

---

## §7 五项验收问题的收敛回答

1. **R8 全仓扫描完成了吗？** 完成。7 个历史实例、3 处必修（含 2 个本版新发现），
   全部有 mapping.txt + dex 双重证据；三处都落地了「显式 `@SerializedName` + 三形状读 + 逐条容错」，
   防复发机制是四条互补的断言（DTO 注册表 / keep 白名单 / 源码扫描 / 反证）。
2. **PlayReporter 有音源闸门吗？** 本版之前没有；**真机复现了泄漏**（HTTP 200）。
   本版把闸门挂在唯一出口、判据用 bit62、双向定义、拦截计数落 `ncrust_report_gate`。
3. **托盘上一首按钮是什么原因消失的？** 没有消失过 —— 它从来没有存在过。
   本版按「补核心功能」做，并加了存在性断言。
4. **托盘三层布局可行吗？** 可行，但必须加高到 80dp，且高度这一个常量有**三个消费者**必须一起改
   （`PlayerCard` / `MainActivity` / `BottomOverlayInsetDp`），本版把它抽成 `TrayLayout` 唯一落点。
5. **macrobenchmark 能换设备跑完吗？** 能。PCL110 + `CompilationMode.Ignore` + 3 迭代，
   预估 5–8 分钟；`run_benchmark.sh` 的两个阻碍（动画还原短路、release 无硬断言）已修。

**第六项（追加任务）**：QQ 搜索的 5s 来自「串行 + 接口本身慢」，不是 token 初始化也不是映射；
「0 首」在屏幕上挂了 2.2–2.7 秒；结果合并是整表重排、不会被去重丢弃。
