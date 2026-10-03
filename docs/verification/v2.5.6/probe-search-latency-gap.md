# 探针：聚合搜索「TTFB → 结果上屏」的延迟缺口（v2.5.6）

- 设备：`PLC110`（`3B15CD00GB700000`，Android 16 / API 36，KernelSU root，**ncm + qm 双登录**）
  、`WGR-W09`（`WVQ6R22124000968`，Android 12，**未取到有效数据，见 §6**）
  、`SM-G9209`（`0715f763f54c023a`，Android 7.0，**本轮未使用**）
- 被测包：**`v2.5.5-gpl` / `versionCode = 46`**（`dumpsys package` 实测；三台设备装的都是它，
  **本版就是带 v2.5.5「两个 `async` 并发」修复的那一版**，因此测的就是「现状」）
- 读的源码：工作区 `HEAD = fef5e7b`（v2.5.5 已发布状态）。本文所有 `file:line` 都对应这一版。
- 探针原则：**不改 `app/` 源码、不注入、不 hook、不写 app 数据**。只用 adb 的三条既有通道
  （`logcat` / `screenrecord --bugreport` / `screencap`）+ 宿主机直连接口探测。
- 测量窗口：2026-09-26 19:29–19:45（宿主机）；19:35–19:41（真机 6 个样本）

---

## §0 结论先行

### 0.1 一句话

**「TTFB → 结果上屏」不是主成本，它只占 QQ 那条腿总耗时的 1.4%（P50，直接实测）。**
QQ 结果从请求发出到上屏 P50 = **2963ms**，其中：

| 段 | P50 | 占总量 | 来源 |
|---|---|---|---|
| 派发（两个请求同一瞬间起飞） | ~0ms | 0% | `SearchViewModel.kt:181,188,203` + 日志反推 `t_dispatch` |
| **服务端 TTFB（QQ `client_search_cp`）** | **2905ms** | **98%** | 宿主机同网段直连 n=30（设备侧无埋点，用同网段近似） |
| 响应体读完（55KB） | ~150ms | ~5% | 宿主机 `time_total − time_starttransfer` P50 |
| JSON 解析 + 模型映射 + 排序 | **未测得** | — | 见 §6；由 P50 对比可判定为小量 |
| **状态写入 → 首帧上屏** | **41.5ms**（36–50ms） | **1.4%** | **本轮直接实测**（帧内烧录时间戳 vs 日志时刻） |

⚠️ **这张表的读法（必须说明，否则数字会看起来自相矛盾）**：
「服务端 TTFB」与「响应体读完」两行是**宿主机同网段近似值**（设备侧无埋点，见 §6），
而「派发/上屏」是**设备实测值**。宿主机那两行加起来 3055ms **比设备端总量 2963.5ms 还大 3%** ——
这恰恰说明**设备端在整个「首字节之后」的开销小于跨主机噪声**，不是矛盾。
唯一能直接归给客户端的、有确定数字的量就是最后一行 41.5ms。

### 0.2 任务书前提的判定

| 任务书说法 | 判定 | 证据 |
|---|---|---|
| 「TTFB 到 UI 更新之间存在需要量化的缺口」 | ⚠️ **成立但极小**：缺口 = 响应体读取 + 解析/映射/排序 + 41.5ms 渲染，**≲10%**，实测到的部分只有 **1.4%** | §3 表 1、表 3 |
| 「这段缺口是最值得压缩的」 | ❌ **推翻**：98% 的服务端 TTFB 才是主成本；客户端再怎么优化，天花板也只有 ~2% | §3、§4 |
| 「最可能的真实缺口是搜索响应之后每首歌 N 次额外网络请求（跨源匹配 / simiSong / 详情补全）」 | ❌ **推翻**：搜索链路上**一次额外网络请求都没有**。`CatalogAggregator`/`CrossSourceMatcher` 只被 3 个详情页调用，**不在搜索链路** | §2.4、§4.1 |
| 「"0 首在屏幕" 花了 2.2~2.7s ⇒ 这就是 time-to-first-result」 | ❌ **读错了**：v2.5.5 探针原文里 **2.2–2.7s 是「假状态『qm 0 首』在屏幕上停留的时长」**，不是首条结果上屏耗时。首条结果上屏是 **tap 后 1.17–1.76s**（含 500ms 防抖） | `PROBE-SUMMARY.md:29,205` |

### 0.3 真正值得改的地方（按性价比，详见 §7）

1. **换主通道**：QQ 搜索现在**先用 legacy `client_search_cp`**（TTFB P50 **2905ms**），
   `musicu.fcg` 只做兜底（TTFB P50 **378ms**）⇒ **7.7 倍差距**，且响应体 55KB vs 2.2KB（25 倍）。
   这一条单独就能把 QQ 腿从 ~2.9s 压到 ~0.4s。**本轮实测再次确认（v2.5.5 是 4.6 倍）**。
2. **解掉「先上屏」对 ncm 腿的硬阻塞**：首帧上屏被 `neteaseDeferred.await()` 卡住
   （`SearchViewModel.kt:230`），**哪怕 QQ 已经先回来了也不发布**。真机实测到
   `elapsed=30006ms netease=0 qq=30`：QQ 数据在 ≤5s 内已经到手，界面却空了 30 秒 ⇒
   **≥4.5s 已被帧证据证实、完整浪费约 25s（= ncm 30s 超时 − QQ 5s 预算），全部是客户端的结构性浪费**（§3.4）。
3. **补 4 个埋点**（这是让本问题**可测**的前提）：今天从 socket 到状态写入之间**一个时间戳都没有**
   （§2.5）。最小改动 = 给 QQ 搜索的 OkHttp 挂一个 `EventListener`。

---

## §1 方法（可复现）

### 1.1 关键手法：帧内烧录时间戳，绕开「设备钟 vs 宿主钟」

`screenrecord` 在 Android 上支持 `--bugreport`，它会把**毫秒级设备墙钟**烧进每一帧左上角
（本轮实测确实有，样例读数 `19:35:41.720 f=51 (0)`）。于是：

- 帧的绝对时刻可以**直接读**，不需要 pts（**实测 pts 步长 ≠ 墙钟步长**，见 §1.4，pts 不可用）；
- `logcat -v epoch` 的时间戳与覆盖层是**同一个时钟**，两者可以**直接相减**。

这一条是本轮能测出 41.5ms 渲染段的唯一原因。

```bash
# 采集一轮：录屏 + logcat + 触发搜索
adb -s 3B15CD00GB700000 logcat -c
adb -s 3B15CD00GB700000 logcat -v epoch > OUT/logcat.txt &
adb -s 3B15CD00GB700000 shell "screenrecord --bugreport --bit-rate 16000000 --time-limit 13 /sdcard/probe.mp4" &
sleep 1.6
adb -s 3B15CD00GB700000 shell input tap 1078 273      # × 清空 ⇒ 确定的空态
sleep 1.6
adb -s 3B15CD00GB700000 shell input text q            # 只打一个 ASCII 字符
adb -s 3B15CD00GB700000 shell input keyevent 66       # ENTER：提交 IME 组字 ⇒ 触发 onQueryChanged
adb -s 3B15CD00GB700000 pull /sdcard/probe.mp4 OUT/rec.mp4
```

### 1.2 为什么必须用 ASCII 关键词 + ENTER

- `adb shell input text` **打不了中文**（v2.5.5 已记录），所以本轮一律用单字符 ASCII（`q` / `k` / `x`）。
- 本机 IME（搜狗拼音）会把 `input text` 的字符放进**组字区**而不提交，搜索框的 `query` 仍是空的
  ⇒ **不触发搜索**。必须先 `input keyevent 66` 提交。实测：
  - 只 `input text love` → 搜索框仍显示占位符「搜索歌曲、专辑、艺人」，**0 条** `SearchViewModel` 日志；
  - 补一次 `keyevent 66` → 立刻拿到
    `I SearchViewModel: aggregate query='lovelove' netease=30 qq=30 qqTimedOut=false qqAllowed=true vip(netease=true qq=true) elapsed=3589ms`。
- **不要点搜索历史条目**：完整条目（带 songmid）的 `onClick` 走的是**播歌**而不是重搜
  （`SearchScreen.kt:273-283` 的 `isIncomplete` 分支），本轮第一次尝试就因此误播了一首歌（见 §5）。

### 1.3 `t_dispatch`（请求派发时刻）的反推

日志自己给了 `elapsed = System.currentTimeMillis() − startedAt`（`SearchViewModel.kt:181,275`），
而 `logcat -v epoch` 给了这行日志的绝对时刻，所以：

```
t_dispatch(设备墙钟) = logcat_epoch(aggregate 行) − elapsed
```

6 个样本**逐条对得上**（§3 表 1 的 `elapsed` 列 = `状态写入 − t_dispatch`，误差 0ms）。
这同时证明：**两个 `async` 确实在同一瞬间起飞**（`startedAt` 在 `coroutineScope` 之前，
`neteaseDeferred`/`qqDeferred` 之间只有协程机制开销）。

### 1.4 两个必须记录的踩坑

1. **`screenrecord` 的 pts 不可用**：同一段录像里 pts 差 2.184s 而覆盖层时间差 **2.684s**。
   所以本文所有时刻都从**覆盖层直读**，不用 pts。
2. **`-fps_mode passthrough` 抽 PNG 与 `select=eq(n,K)` 抽帧的编号不一致**（实测同一 idx 的
   平均像素差 9.3–13.9）。原因是该 mp4 有重复/非单调 dts，两条 ffmpeg 管线处理不同。
   最终改为**同一句 `ffmpeg -f rawvideo` 解码既做差分又存帧**（单一真源），编号才自洽。
   ⚠️ 本报告在发现这一点**之前**读的一张对照图（`composite_28_29_30_49_50_51_2.png`）编号是错的，
   已用自洽编号重做并复核（§3 表 1 的数据全部来自自洽版本）。

### 1.5 宿主机接口探测（同网段近似设备侧网络）

```bash
cd docs/verification/v2.5.5
python3 probe-qq-search-latency.py --n 30 --keyword "love" --out /tmp/probe-256/raw/host-qq-search-2.5.6.json
python3 /tmp/probe-256/netease_probe.py     # POST api/cloudsearch/pc，n=20
```

`--noproxy '*'` 是必须的（宿主机有 `https_proxy`）。宿主机 `192.168.5.32`、PLC110 `192.168.5.70`，
同一 /24、同一出口。

---

## §2 聚合搜索全链路追踪（file:line）

### 2.1 UI 入口与触发

| 步骤 | 位置 | 说明 |
|---|---|---|
| 输入框 | `SearchScreen.kt:173-216` | `BasicTextField`，`onValueChange = { viewModel.onQueryChanged(it) }` |
| 加载圈 | `SearchScreen.kt:202-205` | **唯一的加载指示**，由 `isLoading` 驱动，画在搜索框右侧 |
| 防抖 | `SearchViewModel.kt:95-106` | `searchJob?.cancel()` → `delay(500)` → `searchByType`。**每个字符重置一次，500ms 是硬编码**（`:99`） |
| 结果列表 | `SearchScreen.kt:449-519` | `LazyColumn` + `itemsIndexed(songs, key = { _, item -> item.id })`（`:490`）——**有 key**，diff 正常 |
| 来源统计行 | `SearchScreen.kt:457-487` | 列表**第 0 项**，`item(key = "source-summary")` |

### 2.2 ViewModel：两个 `async` 的确切结构（v2.5.5 的改动就在这里）

```
SearchViewModel.searchByType(type = 1)                       // :149
  _isLoading = true                                          // :150
  keyword = _query.value                                     // :180
  startedAt = System.currentTimeMillis()                     // :181  ← 唯一的时间戳
  qqAllowed = QqClient.isLoggedIn() || allowAnonymousSearch   // :182  （默认 true ⇒ QQ 永远会发）
  coroutineScope {                                           // :184
    neteaseDeferred = async { RetrofitClient.api.search(...) }// :188  ← 立即开始（start=DEFAULT）
    qqDeferred      = async { withTimeoutOrNull(5000) {       // :203, :205
                                SourceRouter.searchSongs(QQMUSIC, keyword, 30) } }
    (netease, neteaseError) = neteaseDeferred.await()         // :230  ★ 首帧上屏的硬闸门
    publish(netease, emptyList())                             // :231  ← 第一次上屏
    _sourceCounts = SourceCounts(netease…DONE, qq…PENDING)    // :234-239  → 界面「qm 搜索中…」
    _isLoading = false                                        // :240  → 转圈到此结束
    qqOutcome = qqDeferred?.await()                           // :243  ← 第二次等待
    if (qq.isNotEmpty() && _query.value == keyword) publish(netease, qq)   // :248-250
    if (_query.value == keyword) _sourceCounts = …qq 计数…     // :251-263 → 界面「qm N 首」
    Log.i("SearchViewModel", "aggregate … elapsed=…ms")        // :270-276 ← 唯一的时间日志
  }
```

**逐条回答任务书的三个问法：**

| 问 | 答 | 证据 |
|---|---|---|
| UI 等两个源吗？ | **不等两个，但等 ncm**。`:230` 是发布前的唯一 `await`；QQ 只影响第二次 `publish`（`:248`） | `SearchViewModel.kt:230,243,248` |
| 有 `awaitAll` 吗？ | **没有**。只有两次顺序 `await`（`:230` ncm、`:243` QQ） | 同上 |
| QQ 结果一到就显示吗？ | **不是**。QQ 到手时若 ncm 还没回来，`publish` **不会**被调用 —— QQ 数据只能干等 `:230` 返回 | §3.4 的 30s 实测 |

### 2.3 两次结果如何合并

`publish`（`SearchViewModel.kt:125-147`）= `SearchRanking.order(...)`（`SearchRanking.kt:200-213`）
→ `.map { it.value }` → `.distinctBy { it.trackKey }`（`:146`）。
- `order` = `rank`（会员墙分组 + `interleave` 拉链交错，`SearchRanking.kt:125-160`）
  + `demoteNoCopyright`（无版权行沉底，`:187-191`）；
- 全部是**纯内存 List 操作**，无 IO、无挂起；
- `trackKey` 含音源（`SongSourceExt.kt:36` → `SourceIds.trackKey(source, id)`，字符串拼接），
  所以 `distinctBy` 不会把「同号不同源」合并掉。

### 2.4 响应之后有没有「每首歌 N 次额外网络请求」——**没有**

| 检查 | 结果 |
|---|---|
| `QqApi.searchSongs` | **单次请求**（`QqApi.kt:63-82`）：legacy GET 一次 → 只有返回 null/0 条时才**再发一次** `musicu.fcg`（`:91-95`）。**无分页**（`page` 恒为 1）、**无重试循环** |
| 映射 | `QqSongMapper.songsFromLegacySearch`（`QqSongMapper.kt:71-76`）→ `mapArray`（`:78-86`）：30 条纯 `JSONObject` 遍历，无 IO、无正则 |
| 搜索链路里的挂起调用 | 只有 `RetrofitClient.api.search`（`:190`）与 `SourceRouter.searchSongs`（`:206`）两处 |
| `publish` 里的循环 | 只有 `map`/`distinctBy`（`:130,137,146`）——**没有网络调用** |
| `CatalogAggregator` / `CrossSourceMatcher` | 调用点只有 `AlbumDetailScreen.kt:150` / `SongDetailScreen.kt:124,315` / `ArtistDetailScreen.kt:136`，**搜索链路一次都不经过** |
| 取链 / 音质探测 / 歌词预取 | 搜索链路**没有**。`QqMusicSourceProvider.songDetail`（`:89`）直接返回原对象，连一次往返都省掉 |

⇒ 任务书猜测的「最可能的真实缺口」在**代码层面不存在**。

### 2.5 现有埋点审计

**全仓 `EventListener` / TTFB / 首字节相关检索：0 条命中**。实际跑的是：

```bash
$ grep -rn "EventListener\|time_starttransfer\|firstByte\|TTFB\|responseReceived\|callStart\|requestHeadersEnd" app/src/main/java/
./BigScreenOrientation.kt:29:    /** `OrientationEventListener.ORIENTATION_UNKNOWN`（读不到方向时回调 -1）。 */
./BigScreenOrientation.kt:130:     * `OrientationEventListener` 的语义：0° = 自然方向竖直（竖屏正持），
./MainActivity.kt:26:import android.view.OrientationEventListener
./MainActivity.kt:177:    private var bigScreenOrientationGate: OrientationEventListener? = null
./MainActivity.kt:629:        val listener = object : OrientationEventListener(this) {
```

—— 命中的 5 条全是 `OrientationEventListener`（屏幕方向），**与网络计时无关**。

搜索链路上**全部**时间戳只有两处（同一对）：

```
ui/viewmodel/SearchViewModel.kt:181:  val startedAt = System.currentTimeMillis()
ui/viewmodel/SearchViewModel.kt:275:  "elapsed=${System.currentTimeMillis() - startedAt}ms"
```

（`qq/QqApi.kt:109` 的 `System.currentTimeMillis()` 是 `newSearchId()` 的随机数种子，与计时无关。）

搜索链路上的日志语句（`Log.d` 全部被 `BuildConfig.DEBUG` 关掉 ⇒ **release 包里不存在**）：

```
ui/viewmodel/SearchViewModel.kt:194  Log.w "netease search failed"
ui/viewmodel/SearchViewModel.kt:216  Log.w "qq search failed"
ui/viewmodel/SearchViewModel.kt:270  Log.i "aggregate …"          ← release 里唯一存活的那行
qq/QqMusicSourceProvider.kt:52       Log.w "search failed"
qq/QqMusicSourceProvider.kt:67       Log.d …                       ← BuildConfig.DEBUG 门控
qq/QqApi.kt:74,80                    Log.d "search(legacy|musicu) …" ← BuildConfig.DEBUG 门控
qq/QqClient.kt:289                   Log.d "legacyGet http=… len=…"  ← BuildConfig.DEBUG 门控
```

实测自证（本轮 release 包的 logcat）：

```bash
$ grep -ac "search(legacy)\|search(musicu)" tl-t6/logcat.txt tl-t7/logcat.txt tl-t10/logcat.txt
tl-t6/logcat.txt:0
tl-t7/logcat.txt:0
tl-t10/logcat.txt:0
```

**结论：五个分段里有四个完全没有时间戳。**

| 分段 | 有没有时间戳 | 证据 |
|---|---|---|
| ① 请求派发 | ❌ 只有相对锚点 `startedAt`，没有绝对时刻（但可由 `log − elapsed` 反推） | `SearchViewModel.kt:181` |
| ② TTFB（首字节） | ❌ **完全没有**。`legacyGet` 里只有 `response.code` / `text.length`（debug 才有） | `QqClient.kt:287-291`；无 `EventListener` |
| ③ 响应体读完 | ❌ 没有（`response.body?.string()` 一行读完，无计时） | `QqClient.kt:288` |
| ④ JSON 解析 / 模型映射 | ❌ 没有（`JSONObject(text)` 在 `QqClient.kt:291`，映射在 `QqSongMapper`） | `QqSongMapper.kt:71-86` |
| ⑤ UI 状态写入 / 首帧 | ⚠️ 只有 `Log.i` 的**行时刻**可以当「状态已写入」的锚点，**没有**重组/首帧计时 | `SearchViewModel.kt:270-276` |

ncm 侧同理，且**更差**：`RetrofitClient` 的 ncm client 只有 debug 才挂 `HttpLoggingInterceptor`
（`RetrofitClient.kt:48-51`，`Level.BASIC` 也不含分相耗时），release 里连一条请求日志都没有。

---

## §3 分段量化

### 3.1 表 1：真机 6 个样本（PLC110 / v2.5.5 vc46 / 双登录 / WiFi 播放中）

时刻全部为**设备墙钟**（从帧内 `--bugreport` 覆盖层直读，或由日志反推）；单位 ms。

| # | 关键词 | `t_dispatch` | 首条结果上屏 | 主源段<br>(含渲染) | 「搜索中…」<br>窗口 | QQ 计数上屏 | 状态写入<br>(日志时刻) | app `elapsed` | **渲染段**<br>(状态写入→上屏) | 渲染占比 |
|---|---|---|---|---|---|---|---|---|---|---|
| t4 | `lovelovek` | 19:35:41.002 | 41.720 | 718 | 2497 | **44.217** | 44.179 | 3177 | **38** | 1.18% |
| t6 | `q` | 19:39:38.033 | 38.713 | 680 | 3339 | **42.052** | 42.002 | 3969 | **50** | 1.24% |
| t7 | `q` | 19:39:54.902 | 55.433 | 531 | 3453 | **58.886** | 58.850 | 3948 | **36** | 0.90% |
| t8 | `q` | 19:40:11.695 | 12.258 | 563 | 1097 | **13.355** | 13.314 | 1619 | **41** | 2.47% |
| t9 | `q` | 19:40:28.401 | 28.920 | 519 | 2193 | **31.113** | 31.070 | 2669 | **43** | 1.59% |
| t10 | `q` | 19:40:45.135 | 45.804 | 669 | 1205 | **47.009** | 46.967 | 1832 | **42** | 2.24% |

统计（n=6）：

| 量 | P50 | min | max |
|---|---|---|---|
| QQ 腿（派发 → 状态写入）= `elapsed` | **2923** | 1619 | 3969 |
| 派发 → QQ 计数上屏 | **2963.5** | 1660 | 4019 |
| 派发 → 首条结果上屏（ncm） | 616 | 519 | 718 |
| 「qm 搜索中…」窗口 | **2345** | 1097 | 3453 |
| **渲染段（状态写入 → 上屏）** | **41.5** | **36** | **50** |

6/6 样本 `netease=30 qq=30 qqTimedOut=false qqAllowed=true`；`qqTimedOut=false` 全部成立
⇒ 5s 预算在这 6 次里**一次都没截断**。

**每一行的 `elapsed` = 状态写入 − `t_dispatch`，逐个对得上（误差 0ms）**，例如
`19:39:42.002 − 19:39:38.033 = 3.969s = elapsed=3969ms`。这是 `t_dispatch` 反推正确的自证。

### 3.2 表 2：宿主机接口分相（2026-09-26 19:29–19:44，同 /24、同出口、`--noproxy '*'`）

| 通道 | n | total P50 | total P95 | TTFB P50 | TTFB P95 | TLS P50 | DNS P50 | 响应体 P50 |
|---|---|---|---|---|---|---|---|---|
| QQ legacy `client_search_cp`（新连接） | 30 | **3055ms** | 4008ms | **2905ms** | 3870ms | 215ms | 7ms | 55 517 B |
| QQ legacy（同连接 ×10） | 10 | 2680ms | 3337ms | 2542ms | 3197ms | 0ms | 0ms | — |
| QQ `musicu.fcg` POST（兜底通道） | 15 | **378ms** | 1689ms | **378ms** | 754ms | 251ms | 1ms | 2 219 B |
| ncm `api/cloudsearch/pc` | 20 | **743ms** | 784ms | **702ms** | 747ms | — | — | 46 353 B |

30/30、15/15、20/20 全部 HTTP 200 且返回非空（`empty_results=0`，QQ `items=30`）。

**与 v2.5.5 的对比（同脚本、同网段，关键词不同）**：
v2.5.5 实测 legacy TTFB P50 **1722ms**（关键词「晴天」），本轮 **2905ms**（关键词 `love`）
⇒ 接口本身**变慢了 1.7 倍**（服务端抖动 / 关键词相关），
但 `musicu` 基本没变（P50 376 → 378ms）⇒ **两条通道的差距从 4.6 倍拉大到 7.7 倍**。

### 3.3 表 3：交叉验证 —— 客户端到底花了多少

| 交叉验证 | 设备实测 | 宿主机同网段 | 差 |
|---|---|---|---|
| ncm 腿 + 渲染（派发→上屏） | P50 **616ms** | ncm total P50 **743ms** | 设备**比宿主机还快 127ms** ⇒ ncm 结果**一到就上屏**，客户端可忽略 |
| QQ 腿（派发→状态写入） | P50 **2923ms** | QQ legacy TTFB P50 **2905ms** | **+18ms（0.6%）** |
| QQ 响应体传输（宿主机自证） | — | total − TTFB = **150ms** | 设备若做同样的响应体读取 + 任何解析，`elapsed` 应比宿主机 TTFB 高出 **≥150ms**；实测只高 **18ms** |

⇒ 两个独立方向都指向同一结论：**响应首字节之后的客户端工作（读体 + 解析 + 映射 + 排序 + 状态写入）
相对跨主机噪声是小量**，而其中**唯一被直接测到的渲染段是 36–50ms**。

### 3.4 ★ 真机实测到的结构性浪费：首帧被 ncm 腿硬阻塞

本轮第一次采集（t5）撞上了 v2.5.5 §4.1 记录过的**同一个环境阻塞**（PLC110 该 WiFi 到
`*.music.163.com` 的 IPv6 被黑洞 ⇒ 连接停在 SYN_SENT，而 ncm client 是 30s connect / 30s read
且**没有 callTimeout**，见 `RetrofitClient.kt:53-54`）。这一次它变成了**本报告最有价值的一条证据**：

```
$ adb -s 3B15CD00GB700000 logcat -d | grep -a "SearchViewModel"
09-26 19:37:22.901 19668 19668 W SearchViewModel: netease search failed
09-26 19:37:22.901 19668 19668 W SearchViewModel: java.net.SocketTimeoutException: timeout
09-26 19:37:22.901 19668 19668 I SearchViewModel: aggregate query='m' netease=0 qq=30 qqTimedOut=false qqAllowed=true vip(netease=true qq=true) elapsed=30006ms
```

同一轮录像的某一帧（帧内时间戳 **19:37:02.333**）：

```
搜索框 = "m"，右侧「转圈」在转，结果区**完全空白**
```

把两条拼起来：

| 事实 | 数值 | 依据 |
|---|---|---|
| 派发时刻 | ≈ 19:36:52.8 | `type_ms = 19:36:52.278` + 500ms 防抖（`SearchViewModel.kt:99`） |
| **QQ 数据到手** | **≤ 19:36:57.8**（派发 + ≤5s） | `qqTimedOut=false` ⇒ `withTimeoutOrNull(5000)`（`:205`）没超时 |
| 界面仍然空白 | 19:37:02.333 | 帧内覆盖层直读 |
| 首帧真正上屏 | ≈ 19:37:22.9 | ncm 腿 30s 超时后才 `publish`（`:230-231`） |

⇒ **≥4.5s 已被实测证实、最多 ~25s 的纯客户端阻塞**：
QQ 的 30 条结果早就躺在 `qqDeferred` 里，但 `:230` 的 `await` 不返回，
`:231` 的 `publish` 就永远不执行。**这与服务端 TTFB 一点关系都没有。**

（本轮**没有**复现 v2.5.5 §4.1 的处置动作：我先按同样办法写过
`wlan0/disable_ipv6=1`，回读时它已经是 `0`；`all/disable_ipv6` 我改成 1 后**立即改回默认 0**。
后续 11 次搜索全部正常完成，阻塞未再出现。详见 §5。）

---

## §4 各段优化空间评估

### 4.1 网络传输段（这是全部成本所在）

**是的，QQ 搜索是单请求、无分页、无逐条补充请求**（§2.4）。所以「N 次额外请求」的天花板是 0。

真正的两个杠杆：

| 杠杆 | 现状 | 收益 | 依据 |
|---|---|---|---|
| **换主通道 legacy → musicu** | `QqApi.kt:63-82` 先 legacy、`musicu` 只兜底 | TTFB **2905 → 378ms**（7.7×）、响应体 **55.5KB → 2.2KB**（25×）、解析量同比例下降 | 表 2 |
| **连接复用** | `searchHttp` 是 OkHttp 默认池，理论已复用 | 同连接 TTFB 2542 vs 新连接 2905 ⇒ 只省 **~12%**。**不是主要杠杆** | 表 2 B 组 |
| **超时对齐** | `QQ_SEARCH_BUDGET_MS = 5000`（`SearchViewModel.kt:93`）< `callTimeout = 8s`（`QqClient.kt:86`） | 预算丢弃结果后 socket 还要烧 3s；不影响上屏时间，只影响资源 | 表 2：本轮 legacy **n=30 里 0 条**超过 5000ms（max 4191ms），故本轮 6/6 `qqTimedOut=false`；预算没被触发 |
| **结果缓存** | 无 | 只对「重复搜同一个词」有效（v2.5.5 §3.1 D） | — |

### 4.2 映射段（可并行/下沉，但收益有上限）

**发现一条真实但小的问题**：映射**跑在主线程上**。

- `QqClient.legacyGet` 里 `JSONObject(text)` 在 `withContext(Dispatchers.IO)` 内（`QqClient.kt:275,291`）⇒ **解析在 IO 线程**；
- 但 `QqApi.searchSongs`（`QqApi.kt:72`）→ `QqSongMapper.songsFromLegacySearch` 已经把控制权交回
  `async` 所在的调度器 = `viewModelScope` = `Dispatchers.Main.immediate` ⇒
  **30~60 条的模型映射在主线程**；随后的 `publish()`（`SearchViewModel.kt:231,249`）
  = 排序 + `distinctBy` 也在主线程。

优化空间：把 `QqSongMapper.*` 与 `publish` 的排序都放进 `withContext(Dispatchers.Default)`，
或改成流式（`Sequence`）逐条喂给 UI。**但收益受 §3.3 约束**：整段 ②③④ 加起来
在 P50 上还抵不过跨主机噪声，乐观估计**几十毫秒量级**，远小于 2905ms 的 TTFB。
⇒ **列为「顺手做」，不作为本问题的解法。**

### 4.3 渲染段（已经很好，只剩「观感」问题）

| 检查 | 结果 | 证据 |
|---|---|---|
| 列表 `key` | ✅ 有，`itemsIndexed(songs, key = { _, item -> item.id })` ⇒ diff 正常 | `SearchScreen.kt:490` |
| 有没有骨架屏 / 占位 | ❌ **没有**。`if (songs.isEmpty() && !isLoading) 显示「没有找到歌曲」 else LazyColumn`；加载中且无结果时 `LazyColumn` 里**一项都没有** ⇒ 结果区一片空白 | `SearchScreen.kt:437-448` |
| 转圈 | 有，但 `_isLoading=false` 在**ncm 到手那刻**就执行（`SearchViewModel.kt:240`）⇒ QQ 还在路上时**没有任何加载信号**（v2.5.5 已改成文字「搜索中…」，`:234-239` + `SourceCounts.kt:111`） |
| 入场动效 | 前 12 项播 `tween(220ms)` 淡入+上滑（`ListItemAppear.kt:49,86-102`、`AppMotion.kt:222,225`）。**首帧可见即 alpha 从 0 开始**，完全显现要 +220ms | 同上 |
| 二次上屏的抖动 | QQ 到手会**整表重排**（`:248-250` → `SearchRanking.order`）。按 key diff ⇒ 已有行**移动**、新 QQ 行**首次组合**并重新播 220ms 入场动效 | 实测：**首行本身没有变**（QQ 计数帧的 row 带 diff = 0.0，因为首行两次都是 netease 的会员墙组第 0 项）；但**首行以下仍在持续变化** —— 各轮「QQ 计数帧」之后还各有 **53–101 帧**画面变化（t4=53 / t6=78 / t7=69 / t8=100 / t9=87 / t10=101），与「新插入的 QQ 行首次组合 + 封面异步加载 + 前 12 行 220ms 动效」一致。**这不是「首条结果延迟」，只是观感抖动** |

**渲染段实测 36–50ms（P50 41.5ms）已经是「写完状态后下一帧就画出来」的水平，没有可压缩的延迟。**
可改的只有观感：加骨架屏、或在 QQ 行插入时关掉入场动效（避免首行以下「整表闪一下」）。

---

## §5 对测试设备做的改动（逐条留痕）

| 项 | 内容 | 还原状态 |
|---|---|---|
| PLC110 `wlan0/disable_ipv6` | 按 v2.5.5 §4.1 的办法尝试写 `1`；随后回读为 **`0`**（未生效/被系统重置） | ✅ 终态 `wlan0=0`（= 我进来时读到的原值） |
| PLC110 `all/disable_ipv6` | ⚠️ **我先写成 1、但没有先记录原值**（这是我的操作失误）。发现后**立即写回 Android 默认 `0`** | ✅ 终态 `all=0` |
| PLC110 的 IPv6 阻塞 | **没有靠我的改动解决** —— 后续 11 次搜索全部正常完成，阻塞自行消失 | — |
| PLC110 播放曲目 | ⚠️ 第一次尝试时**误点了一条完整搜索历史**（`怪我太天真`），`onClick` 走的是播歌（`SearchScreen.kt:281-283`）⇒ 播放器切到了那首 QQ 曲目；此后随队列自然演进（现为 `Alive, Faded · Alan Walker`） | ❌ 未能还原到进入前的 `M C 8 1 0 · もののけ獣` |
| PLC110 `search_history.xml` | **未被改动**（回读 mtime = `2026-09-26 17:57`，早于本轮 19:30） | ✅ 原样 |
| PLC110 搜索框文字 | 结束时框内留着测试关键词 `q`（纯内存态） | 无害，未清 |
| 平板 WGR-W09 | ⚠️ 我的两次坐标试探**误触了侧边栏与列表**，App 从「搜索」跳到「首页」，播放曲目从 `Open Your Eyes / Yann Fontaine` 变成 `燕无歇 / 蒋雪儿Snow.J`；此后**我主动停止**了平板测量 | ❌ 未还原（避免更多误触） |
| 华为控制中心媒体卡片 | **全程未打开、未触碰控制中心、未改任何媒体卡片相关设置**（只做了 App 内的点击与 `screenrecord`/`screencap`） | — |
| S6 `0715f763f54c023a` | **本轮完全未使用**（进入时是 Awake + 桌面；v2.5.5 已记录它带 PIN 锁屏无法解锁） | ✅ 未触碰 |

---

## §6 未测得项与原因

| 未测得的东西 | 为什么 | 影响 |
|---|---|---|
| **设备侧 TTFB（首字节）** | App 里**没有任何** TTFB 埋点（§2.5），OkHttp 也没挂 `EventListener`；root 也没装 `tcpdump`。只能拿同网段宿主机的 TTFB 近似 | 表 3 的「2905ms」是**近似值**，不是设备侧实测 |
| **响应体读完时刻** | 同上（`QqClient.kt:288` 一行读完，无计时） | 用宿主机 `total − TTFB = 150ms` 近似 |
| **JSON 解析 / 模型映射耗时** | 无埋点，**没有独立基准**（v2.5.5 §4.2 也是同一个缺口） | 只能由 §3.3 的 P50 对比判定为「小量」，给不出数字 |
| **首次重组耗时** | 无 `Modifier.layout`/`onGloballyPositioned` 埋点。只能测「状态写入 → 上屏」的**外包络** = 36–50ms | 渲染段有了上界，但没有拆成「重组/布局/绘制」 |
| **健康态下 QQ 先于 ncm 返回** | 6 个样本里 ncm 腿（519–718ms）**总是**先完成，没抓到反序样本 | 反序的影响只能由 §3.4 的阻塞样本（QQ 先好、界面空等 30s）体现 |
| **平板 WGR-W09（Android 12）** | ① **该设备没有 `screenrecord`**（`/system/bin/sh: screenrecord: inaccessible or not found`，`rc=127`）；② `screencap` 轮询只有 **1.71–1.80 fps**（≈550ms/帧），**分辨率不足以测 41ms 的渲染段**；③ 我的坐标试探误触了界面，遂主动停止 | 平板**无有效量化数据**；跨设备结论只能由 PLC110 单机给出 |
| **S6（Android 7.0）** | 本轮未使用（v2.5.5 已记录它是带 PIN 的锁屏，`input swipe` 无法解锁）；没有 Android 7 的数据 | 低端机/旧 API 的结论**缺失** |
| **移动数据 / 弱网** | 全部数据在 WiFi `CMCC-YKPt-5G`（PLC110 `192.168.5.70`）下取得 | 弱网下服务端 TTFB 占比只会更高，不会改变结论方向 |
| **超时截断路径** | 6/6 样本 `qqTimedOut=false`，**没触发** 5s 预算截断 | 只记录 P95 total 4008ms 已贴近 5000ms 预算 |

---

## §7 对下一步实现的建议

按「收益 ÷ 回归面」排序。**前两条才是把 2.9s 打下来的东西；补埋点是让以后不用再猜的前提。**

### 7.1 P0 · 补 4 个埋点（让本问题可测）

不改行为，只在 QQ 搜索这条链路上加时间戳，全部走已有的 `Log.i`（release 可见）或
`QqProbeCounters` 那套 `AtomicLong`（无 IO）：

| 埋点 | 落点 | 建议形式 |
|---|---|---|
| 派发（绝对时刻） | `SearchViewModel.kt:181` 附近 | 把 `startedAt` 一并打进 `aggregate` 行（`t0=…`），省掉反推 |
| **TTFB** | `QqClient.legacyGet`（`QqClient.kt:287`） | 给 `searchHttp` 挂一个 `okhttp3.EventListener`（`requestHeadersEnd` / `responseHeadersStart` / `responseBodyEnd`）—— **这是唯一能拿到真 TTFB 的办法** |
| 响应体读完 | 同上 `responseBodyEnd` | 同上 |
| 映射完成 | `QqApi.kt:72` 之后 | `Log.i("QqSearch", "map=${…}ms n=${songs.size}")`，同时**把映射移出主线程**（§4.2） |

### 7.2 P0 · 解掉首帧对 ncm 的硬阻塞（§3.4，实测 ≥4.5s / 最多 25s 的纯浪费）

三个候选，建议 ①+② 一起：

1. **谁先回来谁先上屏**：把 `:230` 的 `await` 换成「先到先发布」——
   例如给 ncm 也加一个小预算（如 2s）或让两侧都往同一个 `Channel` 投递，
   收到第一份就 `publish`。**注意保住 v2.1.0 hotfix 3 的契约**：主源仍要优先、
   绝不能回到「两个都等」。
2. **给 ncm client 补 `callTimeout`**（`RetrofitClient.kt:53-54` 现在只有 30s connect/read、
   **无 callTimeout`**）⇒ 把最坏阻塞从 30s 压到可配置的秒级。
3. 顺带修 v2.5.5 §3.2 遗留的 `_sourceCounts` 陈旧写回守卫（现在是有了，`:251`）与
   `CancellationException` 语义（`:294-302` 已修）——这两条 v2.5.5 已处理，不用重做。

### 7.3 P1 · 换 QQ 搜索主通道（最大单项收益，但需先取证）

把 `QqApi.searchSongs`（`QqApi.kt:63-82`）的两条通道**调换优先级**（`musicu` 先、legacy 兜底）。
- 收益：TTFB **2905 → 378ms**、响应体 **55.5KB → 2.2KB**（表 2）。
- **代价与风险（必须先验证）**：`QqApi.kt:55-62` 的注释说 `musicu` 曾「连续 6 次只成功 1 次
  （`code:2001`，疑似按出口 IP 限流）」，而 v2.5.5（15/15）与本轮（15/15）都 100% 成功 ——
  服务端行为可能已变。**建议先跑一轮 `n ≥ 30` 的 `musicu` 稳定性样本 + 逐字段比对两条通道
  的返回形状**（字段名在 `new_json=1` 下一致，见 `QqSongMapper.kt:63-70`），再动优先级。
- 回归面：结果**顺序**可能变（`musicu` 是另一套服务端排序）⇒ 需要比对 top-10 命中。

### 7.4 P2 · 观感（不省时间，但省困惑）

- 结果区加**骨架屏**（`SearchScreen.kt:437-448` 现在加载中是纯空白）；
- QQ 行**首次插入时跳过 `listItemAppear`**（`SearchScreen.kt:495`），
  避免「QQ 到手 → 整表重排 → 前 12 行又淡入一次」的二次闪烁；
- 保留 `SourceSearchStatus`（`SourceCounts.kt:34-56`）的三态语义 —— 它已经把
  「还没回来 / 超时 / 真 0 条 / 未登录」分开了，这是 v2.5.5 的正确修复，不要回退。

### 7.5 不要做的事

- **不要为了「压缩 TTFB→UI 缺口」去优化映射/排序/渲染**：实测总占比 1.4%（P50），
  天花板 ~2%，而服务端 TTFB 占 98%。
- **不要引入跨源匹配到搜索链路**：`CatalogAggregator`/`CrossSourceMatcher` 现在不在搜索链路上，
  一旦引入，**每首歌 N 次额外请求**这个任务书担心的东西才会真的出现（§2.4）。

---

## §8 原始证据索引（本轮临时目录，未入 git）

| 路径 | 内容 |
|---|---|
| `/tmp/probe-256/raw/tl-t4` … `tl-t10` | 6 个真机样本：`rec.mp4`（帧内烧录时间戳）、`logcat.txt`（`-v epoch`）、`scan.json`、`key/`（关键帧 PNG） |
| `/tmp/probe-256/raw/tl-t5` | IPv6 阻塞样本（`elapsed=30006ms netease=0 qq=30`）+ 空白界面帧 |
| `/tmp/probe-256/raw/all-runs.png` | 25 张「覆盖层时间戳 + 来源统计行」对照图（表 1 的原始读数） |
| `/tmp/probe-256/raw/host-qq-search-2.5.6.json` | 宿主机 QQ 两条通道 n=30/10/15 全样本（表 2） |
| `/tmp/probe-256/raw/host-netease-search-2.5.6.json` | 宿主机 ncm 搜索 n=20 全样本 |
| `/tmp/probe-256/{run_probe2.sh,scan.py,scan_generic.py,keyframe.py,thin.py,series.py,netease_probe.py}` | 本轮全部探针脚本（可复现） |
| `docs/verification/v2.5.5/probe-search-qq-latency.md` · `PROBE-SUMMARY.md` §6 | v2.5.5 的同类探针（TTFB P50 1722 / P95 2989；串行 sum；无加载态；musicu 快 5 倍） |
