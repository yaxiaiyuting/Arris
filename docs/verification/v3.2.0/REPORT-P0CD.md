# v3.2.0 · P0-C（B 站搜不到歌）/ P0-D（切「只搜 B 站」卡死）交付报告

- 分支 / 基线：`master` / `08e2641`（工作区**未 commit**，按编排者要求由编排者统一提交）
- 探针文档（**先于代码落盘**）：[`probe-bili-search.md`](probe-bili-search.md)（8 + 9 问逐条、带行号证据、实测输出、未验证清单）
- 本报告只覆盖 P0-C / P0-D，不含本轮的其它 P0

## 1. 根因（一句话版）

| P0 | 根因 | 判决 |
|---|---|---|
| **C** | `BiliApi` 的**同步阻塞**网络方法（`get()` = `OkHttp.execute()`）被 `SearchViewModel` 的 `Main.immediate` 作用域内联调用；Android 对 `targetSdk >= 11` 在主线程上启用 `StrictMode.enableDeathOnNetwork()` ⇒ **毫秒级**抛 `NetworkOnMainThreadException`，而 `BiliApi.wbiKeys` 的 `runCatching` 把它**静默吞成 null** ⇒ `signedGet` 返回空串 ⇒ 解析 0 条 ⇒ 统计行显示「B站 0 首」，**全程没有任何错误**。请求**从未发出**（不是签名错、不是接口错、不是解析错、不是映射错、不是跨源匹配误过滤） | 探针 §1 / §2（C1–C8） |
| **D** | 筛选档（SourceFilter chips）与统计行都画在结果 `LazyColumn` 的 item 里，而整条列表被 `if (visibleSongs.isEmpty() && !isLoading)` 挡着 ⇒ 用户点「只看 B 站」后若该轮没有 B 站行，**筛选档自己消失、没有任何路径切回「双源」** = 用户说的「卡死 / 无法退出」。次要同向缺陷：B 站 TIMEOUT/ERROR 没有重试入口、结果区没有加载态、搜索页没有下拉刷新、空态文案不分「无结果 / 筛选后为空」 | 探针 §1 / §3（D1–D9） |

**实测判据（API 33 模拟器，`emulator-5554`，见探针 §5.2）**：

```
PROBE-BILI320-SOCKET  on=main connected=false elapsedMs=0 error=android.os.NetworkOnMainThreadException
PROBE-BILI320-RAWSYNC on=main bodyLen=-1  elapsedMs=8    error=android.os.NetworkOnMainThreadException   ← v3.1.0 BiliApi.get() 的调用形状
PROBE-BILI320-RAWSYNC on=io   bodyLen=249 elapsedMs=1527 error=null
PROBE-BILI320-SEARCH  on=main n=5 elapsedMs=396 error=null        ← 修复后：主线程发起也能拿到条数
PROBE-BILI320-SEARCH  on=io   n=5 elapsedMs=414 error=null
PROBE-BILI320-KEYS    n=20 idDistinct=20/20 trackKeyDistinct=20/20
PROBE-BILI320-TOGGLE  off=true n=0 elapsedMs=1 / off=false n=5 elapsedMs=1506
```

## 2. 改了哪些文件（逐文件一句话）

### 2.1 本 P0 的生产代码

| 文件 | 一句话 |
|---|---|
| `bili/BiliApi.kt` | 7 个对外网络方法（`searchVideos` / `videoCid` / `videoAudioStream` / `audioInfo` / `audioStream` / `audioLyric` / `probeReachable`）改 `suspend` + 内部 `withContext(Dispatchers.IO)`；阻塞传输层（`get`/`post`/`signedGet`/`wbiKeys`/`buvid3`）保持 `private` 并标注「只能在 IO 块里调」；`wbiKeys` 的 nav 失败改为显式 `Log.w`；每个 catch 放行 `CancellationException`；新增 `clientForTest`（只给单测注入假传输层）。⚠️ 该文件里另有并行任务（P1 B 站登录）加入的 `BiliAuthStore` Cookie 合并与 `getRaw`，**不是本 P0 的改动** |
| `bili/BiliSourceProvider.kt` | `runCatching` 换成两个 suspend-safe helper（`biliOrNull` / `biliOrEmpty`）：取消原样抛出、其余记日志回 null/空表；契约（绝不抛）与有界性（无重试）不变；每条路径第一行仍判 `isEnabled`（有源码扫描守卫） |
| `source/SourceRouter.kt` | `searchSongs` 的 `runCatching` 改成 `try/catch`：**取消原样抛出**（加了真挂起点之后它才会显形），其余记日志回空表。⚠️ 该文件里另有并行任务（P0-B）的 `resolveUrlOutcome`，**不是本 P0 的改动** |
| `ui/components/SourceFilter.kt` | 新增三个**纯函数**：`shouldShowFilterRow(totalSongs, biliEnabled)`（唯一判据是「有没有可筛的东西」，**绝不看筛选后的结果**）、`emptyKind(total, visible)`、`emptyText(strings)`（筛选为空的占位文案）+ `enum SearchEmptyKind` |
| `ui/components/SourceCounts.kt` | 新增 `biliUnavailable` 与 `anyUnavailable`（`qqUnavailable \|\| biliUnavailable`），修掉「B 站超时/失败没有重试入口」 |
| `ui/screen/SearchScreen.kt` | 筛选档与逐源统计行**移出 `LazyColumn`**（常驻）；统计行的重试判据扩到 `anyUnavailable`；结果区新增加载态（复用 `MetroProgressIndicator`）；空态按 `emptyKind` 分流；接入既有下拉刷新（`Modifier.pullToRefresh` + `PullToRefreshIndicator`，`onRefresh` = 用当前关键词重查）；抽出 `SearchSourceFilterRow` / `SearchSourceSummaryRow` / `SearchResultsEmptyState` 三个私有 composable |
| `androidTest/.../probe/BiliV310ProbeTest.kt` | **仅机械适配**：8 处 `BiliApi.x()` 包一层 `runBlocking { }`（签名改 suspend 的编译要求）。打印内容、判定口径、用例名一个字未动（文件头有说明） |

### 2.2 新增文件

| 文件 | 内容 |
|---|---|
| `androidTest/.../probe/BiliV320ProbeTest.kt` | 6 个探针用例：旧代码调用形状的对照（RAWSYNC）、平台级裸 socket、主线程 vs IO 的搜索/音频区 A/B、真实响应的 key 唯一性、开关短路 |
| `test/.../bili/BiliApiDispatchTest.kt` | 7 个 JVM 单测（行为判据，完全离线：注入假 OkHttp client） |
| `test/.../bili/BiliApiIoContractTest.kt` | 4 个源码扫描守卫（结构判据） |
| `docs/verification/v3.2.0/probe-bili-search.md` | 探针文档（8 + 9 问 + 矩阵 + 实测 + 未验证 + 事故记录） |

### 2.3 修改的测试

| 文件 | 内容 |
|---|---|
| `test/.../ui/components/SourceCountsBiliTest.kt` | 在既有 `SourceFilterTest` 里新增 6 条 P0-D 回归用例（筛选后为空仍挂载、无结果不画、空态分流、文案指名档位、8 语言不漏中文…） |
| `test/.../ui/components/SourceCountsTest.kt` | 新增 2 条：`biliUnavailable` 五种状态映射、`anyUnavailable` 对 `qqUnavailable` 的向后兼容（逐状态断言） |

## 3. 单测清单与实测数字

| 判据 | 用例名（中文） | 文件 |
|---|---|---|
| 调度（主犯） | `搜索的阻塞传输不在调用线程上跑 —— P0-C 的回归判据` | BiliApiDispatchTest |
| 调度（音频区腿） | `音频区详情的阻塞传输不在调用线程上跑` | 同上 |
| 调度（播放取词腿） | `歌词的阻塞传输不在调用线程上跑 —— 播放路径是同一个 P0` | 同上 |
| 铁律 24 | `开关关闭时零请求 —— 调度修复不得动摇铁律 24` | 同上 |
| 契约 | `传输异常被隔离成空列表 —— 换了线程之后契约依然是绝不抛` | 同上 |
| 取消语义 | `取消是控制流不是失败 —— CancellationException 必须原样抛出` | 同上 |
| TIMEOUT≠DONE+0 | `预算用完时返回 null 而不是空列表 —— TIMEOUT 不许被写成 DONE 加 0` | 同上 |
| 形状守卫 | `BiliApi 的每一个对外网络方法都是 suspend 且内部换到 Dispatchers_IO` | BiliApiIoContractTest |
| 形状守卫 | `BiliApi 的对外函数只有两类 —— 纯内存读取 与 suspend 网络方法` | 同上 |
| 形状守卫 | `阻塞的传输层必须是 private` | 同上 |
| 铁律 24 顺序 | `BiliSourceProvider 的每一条对外路径都先判开关再发请求` | 同上 |
| P0-D 回归 | `筛选后为空时筛选档仍然挂载 —— P0-D 的回归判据` | SourceFilterTest |
| P0-D | `一条结果都没有时不画筛选档 —— 点下去只会得到另一个空列表` | 同上 |
| P0-D | `可用档位多于一个才画筛选档` | 同上 |
| P0-D | `空态分流 —— 本次搜索无结果 vs 当前筛选下无结果` | 同上 |
| P0-D | `筛选为空的文案必须指名当前档位 且与无结果文案不同` | 同上 |
| P0-D | `筛选为空的文案在 8 种语言下都不漏中文` | 同上 |
| P0-D | `B 站超时或失败时给重试入口` | SourceCountsTest |
| P0-D | `anyUnavailable 覆盖 QQ 且新增 B 站` | 同上 |

**实测数字**（`:app:testDebugUnitTest` 的 XML 逐类统计）：

| 范围 | 结果 |
|---|---|
| 定向（共享工作区，`--tests '*Bili*' --tests '*SourceFilter*' --tests '*SourceCounts*'`） | **18 类 / 175 用例 / 0 失败** |
| **全量（共享工作区）** | **151 类 / 2099 用例 / 0 失败 / 0 错误** |
| 仪器化探针（API 33 模拟器） | `Starting 6 tests` / `Finished 6 tests` / `BUILD SUCCESSFUL`，输出见探针 §5.2 |

## 4. 自检命令（真实输出摘要）

```
$ ./gw.sh :app:testDebugUnitTest --tests '*Bili*' --tests '*SourceFilter*' --tests '*SourceCounts*'
BUILD SUCCESSFUL in 11s

$ ./gw.sh :app:testDebugUnitTest
BUILD SUCCESSFUL in 44s           # 全量 2099 用例，0 失败

$ ./gw.sh :app:lintDebug
BUILD SUCCESSFUL in 1m 5s         # lint 报告：35 issues = 32 Warning + 3 Information，**0 error**

$ ANDROID_SERIAL=emulator-5554 ./gw.sh :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=…BiliV320ProbeTest
Starting 6 tests on emulator-5554 - 13
Finished 6 tests on emulator-5554 - 13
BUILD SUCCESSFUL
```

真机 S6（`0715f763f54c023a`）上的 **logcat 取证未取到**：ROM 过滤三方 App 日志，
按 pid 过滤 `logcat -d` 为 0 行；`run-as` 也因 release 包不可调试而失败。
**没有安装任何包到真机**，登录态未受影响。详见探针 §5.1。

## 5. 未做 / 未验证（摘要，完整清单见探针 §7）

- **「修复前的 v3.1.0 构建」没有在设备上直接跑过**（构建在共享工作区事故中被覆盖）；
  「修复前会怎样」由平台事实 + 同形状 OkHttp 调用 + 源码链路三条共同判定。
- 真机（S6）logcat 未取到；探针跑在模拟器上（平台政策与机型无关，但口径如实标注）。
- B 站 CDN 取流（ExoPlayer 播字节）仍未验证 —— 不在本 P0 面内，v3.1.0 的结论未推翻。
- 超时预算仍是「到点丢弃结果」而非「掐断请求」（取消是协作式的）。
- 筛选档 / 空态 / 下拉刷新的**实际渲染**没有自动化证据（无 Robolectric），也没有手点复核
  （v3.1.0 已证实 uiautomator 定位不到底部导航「搜索」入口）。
- 三源并发的弱网耗时分布未采集。

## 6. 需要编排者补的 i18n key

**1 个（可选）**：`searchFilterEmpty: (String) -> String`（「当前筛选（X）下没有结果」）。
当前用 `SourceFilter.emptyText` 以两个**既有**已本地化字符串拼成 `只看 B 站 · 0 首` 占位
（无裸中文字面量，8 语言都不漏中文，有单测）。完整建议文案与落位约束（必须放 `SourceStrings`，
主构造器槽已满）见探针 §7.3。**不补也不会造成错误行为。**

## 7. git 状态

`git status --short`（本 P0 触碰的路径加粗；其余为并行任务/编排者的在途改动）：

```
 M AGENTS.md
 M app/src/androidTest/.../probe/BiliV310ProbeTest.kt          ← 本 P0（机械适配）
 M app/src/main/java/.../bili/BiliApi.kt                       ← 本 P0（+ P1 登录的 Cookie 合并）
 M app/src/main/java/.../bili/BiliSourceProvider.kt            ← 本 P0
 M app/src/main/java/.../qq/QqApi.kt                           （并行任务）
 M app/src/main/java/.../qq/QqClient.kt                        （并行任务）
 M app/src/main/java/.../qq/QqMusicSourceProvider.kt           （并行任务）
 M app/src/main/java/.../source/MusicSourceProvider.kt         （并行任务）
 M app/src/main/java/.../source/NeteaseSourceProvider.kt       （并行任务）
 M app/src/main/java/.../source/SourceRouter.kt                ← 本 P0（+ P0-B 的 resolveUrlOutcome）
 M app/src/main/java/.../ui/components/SourceCounts.kt         ← 本 P0
 M app/src/main/java/.../ui/components/SourceFilter.kt         ← 本 P0
 M app/src/main/java/.../ui/i18n/*.kt (9 个)                   （编排者）
 M app/src/main/java/.../ui/player/{PlayerCard,TrayLayout}.kt  （并行任务）
 M app/src/main/java/.../ui/player/motion/*.kt (4 个)          （并行任务）
 M app/src/main/java/.../ui/screen/SearchScreen.kt             ← 本 P0
 M app/src/main/java/.../ui/screen/SettingsGroupScreen.kt      （并行任务）
 M app/src/main/java/.../ui/settings/*.kt (2 个)               （并行任务）
 M app/src/main/java/.../ui/viewmodel/PlayerViewModel.kt       （并行任务）
 M app/src/test/java/.../settings/*.kt (3 个)                  （并行任务）
 M app/src/test/java/.../ui/components/SourceCountsBiliTest.kt ← 本 P0
 M app/src/test/java/.../ui/components/SourceCountsTest.kt     ← 本 P0
 M app/src/test/java/.../ui/i18n/*.kt (2 个)                   （编排者）
 M app/src/test/java/.../ui/player/**  (3 个)                  （并行任务）
?? app/src/androidTest/.../probe/BiliV320ProbeTest.kt          ← 本 P0
?? app/src/main/java/.../bili/BiliAuthApi.kt                   （并行任务 P1）
?? app/src/main/java/.../bili/BiliAuthStore.kt                 （并行任务 P1）
?? app/src/main/java/.../bili/BiliQrLogin.kt                   （并行任务 P1）
?? app/src/main/java/.../player/ResolveFailure*.kt (2 个)      （并行任务 P0-B）
?? app/src/main/java/.../qq/QqRejection.kt                     （并行任务）
?? app/src/test/java/.../bili/BiliApiDispatchTest.kt           ← 本 P0
?? app/src/test/java/.../bili/BiliApiIoContractTest.kt         ← 本 P0
?? app/src/test/java/.../bili/BiliAuth*.kt (4 个)              （并行任务 P1）
?? app/src/test/java/.../player/ResolveFailureTest.kt          （并行任务）
?? app/src/test/java/.../qq/QqRejectionTest.kt                 （并行任务）
?? app/src/test/java/.../settings/MotionRhythmGatingTest.kt    （并行任务）
?? docs/verification/v3.2.0/                                   ← 本 P0 的 probe-bili-search.md + 其它任务的文档
```

`git diff --stat`（本 P0 的 9 个已跟踪文件）：`9 files changed, 840 insertions(+), 163 deletions(-)`
（其中 `BiliApi.kt` / `SourceRouter.kt` 的数字含并行任务在同一文件里的改动）。

**未 commit**（按编排者要求）。
