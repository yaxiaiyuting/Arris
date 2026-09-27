# 探针 · 并行请求编排（v3.1.0 · P0-A）

> 采集时间：2026-09-28 02:51 CST ｜ 代码基线：0b4ed2c ｜ 方式：源码审计 + 实测（无臆测）

**判决先行**：URL（取链）、歌词、封面三条腿在 `playSong` 里**真的并行**了；「详情」这条腿在**点播路径**上
**根本不存在**（`songDetail` 全仓唯一的生产调用点在列表预取路径上，见 §1.2），所以谈不上并行。
四件事里能并的只有三件。

本文所有行号来自本次实际读取的文件。审计开始时基线是 `59f3d6c`（工作区干净：
`git status --porcelain` 输出 0 行），随后上游提交了 `0b4ed2c` —— 该提交**只改了
`bili/` 下的三个源文件与两个测试文件**（`git diff --stat 59f3d6c 0b4ed2c`），
本文引用的 `BoundedParallel.kt` / `PlayerViewModel.kt` / `ConnectionWarmup.kt` /
`ListPrefetch.kt` / `SearchViewModel.kt` / `SourceCounts.kt` **一行未动**，
因此这些行号在 `0b4ed2c` 上同样成立。

---

## 1. 逐条判定：能不能并行

| # | 数据 | 能否与取链并行 | 为什么 | 代码落点 |
|---|---|---|---|---|
| 1 | **URL（取链）** | ✅ 与歌词/封面并行；❌ 与自己（多档位）并行 | 档位降级阶梯是**有界串行**：成功即 `return`，失败才下一档 | `PlayerViewModel.playSong` → `playJob = viewModelScope.launch(Dispatchers.IO)`（PlayerViewModel.kt:1237-1238）→ `fetchUrlOfflineFirst`（:1243） |
| 2 | **歌词** | ✅ 能 | 上一版是「取链返回后才 launch 取词」，两段 RTT 相加；现在与取链**同时**发起 | `startAuxiliaryLoad`（PlayerViewModel.kt:1146，**在 playJob 之前**）→ `BoundedParallel.runAll` 的 `"lyrics"` 腿（:1570-1575） |
| 3 | **封面** | ✅ 能（且**不新增**网络请求种类） | 用的是列表项**已有的** `artworkUrl`，只是把「播起来之后才解码」提前到「点下去就开始解码」 | 同上 `"cover"` 腿（PlayerViewModel.kt:1576-1579）→ `ListPrefetch.prefetchCover`（ListPrefetch.kt:159-167，Coil，320px） |
| 4 | **详情（`songDetail`）** | ❌ **点播路径上没有这条腿**（所以谈不上并行） | `MusicSourceProvider.songDetail` 全仓只有**一个**生产调用点，而且在列表预取路径上；`playSong` / `preloadNextSong` / `PlaybackService` 都不碰它。点播链路上唯一的追加请求 `fetchSongMaxLevel` 是取链的**子步骤**，必须串在取链之后 | 声明：`MusicSourceProvider.kt:73`；**全仓唯一调用点**：`ListPrefetch.kt:171`（`detailOf` :169-172，仅 `song.name.isBlank()` 时触发，:147）；v3.0.0 基线的「零调用点」审计见 `net-research/request-orchestration.md:103` |

### 1.1 「URL 与自己不能并行」为什么是硬约束

取链不是一次请求，而是**同一端点的逐档试探**（`SongUrlFetcher.fetch` 的 `fallbackLevels`，见
`net-research/request-orchestration.md` §4.1）。把它拆成并行意味着同时对同一首歌发 3~6 档请求：
服务端会为同一首歌做多次转码/鉴权，且**结果的优先级反而不可控**（可能先回来的低档覆盖后到的高档）。
所以这里保持串行，`probe-parallel` 不主张改它。

### 1.2 「详情」这条腿的准确说法

- `songDetail` 在**队列缺元数据**时才需要（持久化恢复、离线索引）。它出现在
  `ListPrefetch.prefetchBlocking`（ListPrefetch.kt:147-151）而不是 `playSong`。
- 取链内部确实还有一次**条件性追加请求** `fetchSongMaxLevel`（`SongUrlFetcher.kt:204-228`），
  触发判据是 `QualityAssessment.needsSongCapability(level, br, type)`（request-orchestration.md §4.2）。
  它的结果决定「无权限」还是「该曲没这个档位」，**必须在拿到 URL 之后**才能问 ⇒ 结构上不可并行。

---

## 2. 并发上限设多少、依据是什么

**4。落点唯一**：`BoundedParallel.DEFAULT_MAX_CONCURRENCY = 4`（BoundedParallel.kt:86），
由 `Semaphore` 实现硬上限（:100、:128）——不是「大概这么多」。

四条依据（前两条是探针实测，后两条是源码审计）：

1. **恰好四件事**：取链 / 歌词 / 封面 / 详情（BoundedParallel.kt:78-85 的注释）。
   上限 4 = 「同时都发出去」，不需要更高。
2. **排队不是瓶颈**（实测，`net-research/EVIDENCE-S6.md` §2）：
   ```
   PROBE-NET-CONCURRENCY-BEGIN n=6 dispatcherMaxPerHost=5
   PROBE-NET-STAT label=netease.concurrent n=6 ok=6 reused=0 total p50=502 ... wait p50=168 p95=180
   PROBE-NET-CONCURRENCY wall=559ms n=6 maxWait=190
   ```
   OkHttp 自己的 `maxRequestsPerHost` 就是 **5**；6 通同主机并发时最大 `wait` 只有 190ms、墙钟 559ms
   ⇒ 再抬高我们的上限只是把请求堆进 Dispatcher 队列，**把延迟换个地方藏起来**（BoundedParallel.kt:60-61）。
3. **低端机成本**（源码审计）：每个请求背后一份 Gson 反序列化（搜索/歌词响应都在主响应体上），
   单线程 IO 池；并发过高会让「谁先回来」变得不可预测，而播放器的状态写入对顺序敏感
   （`currentTrack` / `lyricsSongId` 由世代闸门保护，但少一次乱序就少一次风险）。
4. **冷启动期还有别的并发**（源码审计）：`AppWarmup` 的首页三请求、`ConnectionWarmup`
   的两个 host（ConnectionWarmup.kt:71-74，走同一个原语 :99）、首页封面预取。上限 4 是留给
   「业务腿 + 启动腿」同存时的余量。

守卫单测（实测，本版全绿）：

| 断言 | 用例 | 文件:行 |
|---|---|---|
| 峰值 ≤ limit，且峰值 ≥ 2（证明不是串行实现） | `并发上限是硬的` | `network/BoundedParallelTest.kt:36-61` |
| `limit = 0` 退化为串行（峰值 1）而不是抛异常 | `非法并发上限被夹到 1 而不是抛异常` | 同上 :136-152 |
| 空任务列表返回空且不启动协程 | `空任务列表返回空 且不启动任何协程` | 同上 :128-133 |

> ⚠️ **诚实边界**：这个上限是**每次 `runAll` 调用内部**的硬上限，**不是进程级全局并发上限**
> （每次调用 `new Semaphore`，:100）。同时存在的 `runAll` 有：`startAuxiliaryLoad`（2 任务）、
> `preloadNextSong` 的 aux（≤3 任务）、`ListPrefetch.prefetchBlocking`（≤limit×2 任务）、
> `ConnectionWarmup`（2 任务）。真正的全局兜底仍是 OkHttp Dispatcher 的 `maxRequestsPerHost=5`。
> **未实测**：真机上「上限 4 vs 6」的端到端对比未采集（复现方式见 §6）。

---

## 3. 并行之后错误处理如何统一：`FetchOutcome` 三态

`FetchOutcome<T>`（BoundedParallel.kt:27-35）是**唯一**的结局类型：

| 结局 | 产生条件（runOne，:124-139） | 业务语义 | 调用方处置 |
|---|---|---|---|
| `Ok(value)` | `block()` 返回非 null | 拿到了 | 正常消费 |
| `Failed(key, error)` | `block()` 抛 `Exception`（取消已先行 rethrow） | **出错**：网络挂了 / 解析炸了 / 服务端异常 | 记日志（`error` 保留原始类型）、该腿降级，其它腿不受影响 |
| `Missing(key)` | `block()` 自己返回 `null` | **没有**：无版权 / 无会员 / 下架 / 该数据源不存在 | 与 `Failed` **区别对待**（例：B 站视频音轨取词返回 null ⇒ `markEmpty`，不是 `fail`） |

### 3.1 为什么不用 `Result<T>`

代码里已经把理由写死（BoundedParallel.kt:20-26）：

- `Result` 的失败侧是 `Throwable`，而本应用里「取链失败」是一个**业务结论**（无版权 / 无会员 / 下架），
  不是异常。把业务结论塞进异常里，下一个读代码的人就会开始 `catch` 它 ——
  v2.5.2 的 `runCatching` 吞取消就是这么来的。
- `Result` **只有两态**，无法表达「没有」。本仓库在搜索侧已经为同一件事付过一次学费：
  `Pair<Int,Int>` 用 `0` 代表「还没回来」，界面上就是「QQ 音乐 0 首」这句假话
  （`SourceCounts.kt:19-32`），后来才补上 `SourceSearchStatus.PENDING/TIMEOUT/ERROR/SKIPPED`
  （`SourceCounts.kt:34-56`）。`FetchOutcome` 的 `Missing` 是同一个教训的第二次应用。

### 3.2 失败隔离

`catch (e: Exception)` 只把**自己**变成 `Failed`（BoundedParallel.kt:135-137），兄弟任务照跑。
行为性单测：`一个子任务抛异常不影响其它子任务`（BoundedParallelTest.kt:64-91）断言
`boom` 变 `Failed`、`null` 变 `Missing`、两侧 `ok` 都拿到值，且**返回顺序与入参一致**（:81-90）
——「顺序」是调用方按下标取结果的前提。

便利重载 `valuesOrNull`（:119-122）**故意**丢掉三态区别，注释明确限定用途（:113-118）；
本次审计确认它的生产调用点只有「不关心区别」的批量封面场景。

---

## 4. 不吞 `CancellationException`：结构性做法与那条不能挂错的腿

### 4.1 结构上怎么做到的

```kotlin
// BoundedParallel.kt:124-139
): FetchOutcome<T> = semaphore.withPermit {
    try {
        val value = block()
        if (value == null) FetchOutcome.Missing(key) else FetchOutcome.Ok(value)
    } catch (e: CancellationException) {
        throw e                       // ★ :132-134 —— 在下面那个 catch 之前
    } catch (e: Exception) {
        FetchOutcome.Failed(key, e)
    }
}
```

Kotlin 的 `catch` 是**顺序匹配**：`CancellationException` 是 `Exception` 的子类，写在前面就必然先命中
⇒ 取消永远不会落进「子任务失败」那一支。这不是靠自觉，是靠**分支顺序**。

同一形状在本版另一处被显式写下：`ConnectionWarmup.warmOne`（ConnectionWarmup.kt:111-137），
DNS 与 HTTP 两段各自 `catch (CancellationException) { throw e }` 在前（:116-119、:132-135），
注释直接点名「刻意不用 `runCatching`：它捕 `Throwable`」（:104-110）。

行为性单测：`取消原样传播 不被当成子任务失败`（BoundedParallelTest.kt:100-125）——
8 个任务、limit 2，`job.cancel()` 后断言 `finishedTasks < 8` **且** `job.isCancelled`
（父 Job 处于取消态，说明取消没被吞成「正常完成」）。

### 4.2 为什么歌词腿**不能**挂在 `playJob` 下

这是本版最容易踩错的一处，代码注释也点名了（PlayerViewModel.kt:1141-1145）：

1. **`playJob` 会被取消两次以上**：每次 `playSong` 都 `playJob?.cancel()`（:1237）；
   预载接管那条路也会 `playJob?.cancel()`（:1733）。
2. **歌词那条路的重试循环会吞取消**：`loadNeteaseLyrics` 用 `repeat(4)` + `catch (e: Exception)`
   （PlayerViewModel.kt:2074-2119）。取消发生在 `getLyric` 挂起点时会被 :2112 的 catch 接住：
   - `attempt == 3` 那一支直接 `return degraded()`（:2114-2117）⇒ 取消被**转成一次正常返回**；
   - 其余三支靠随后的 `delay`（:2118）再抛出取消，但此时已经多绕一圈，并且
     `Log.e("fetchLyrics failed")`（:2115）会把一次取消记成故障。
   也就是说：这条路的**取消语义本来就不干净**，把它接到一个「每次切歌都会被 cancel」的父 Job 上，
   用户快速连点两首歌时第一首的取词会变成一次静默的半途而废（或一次假故障日志）。
3. **正确形状是两个兄弟，不是父子**：`playJob`（取链，可被下一次点播取消）与
   `auxiliaryJob`（歌词 + 封面，`startAuxiliaryLoad` :1569）各自独立。取消只该作用于
   「这次请求的结果还要不要」，而不该作用于「这次请求能不能跑完」。

> ⚠️ **诚实边界**：`auxiliaryJob` 在 `playSong` 里**没有被显式 cancel**（全仓只有 :431 声明与
> :1569 赋值两处）。连续快速切歌会留下多个在途辅助腿，它们的结果由 `LyricLoadCoordinator`
> 的世代闸门（`fetchLyrics` :1986/:1990、每处挂起点后的 `isCurrent`）丢弃。
> 这是**有意**的（取词不该被取消），代价是短时在途请求数可能超过单次 `runAll` 的上限。

---

## 5. 「先到先发布」与「收集型并行」的分工

两者解决的是不同问题，**不能互相替代**（BoundedParallel.kt:69-74 的 KDoc 把这条写死了）：

| | 先到先发布 | 收集型并行 |
|---|---|---|
| 语义 | 谁先回来谁先上屏，其余后到再合并 | 等齐了按入参顺序返回结局 |
| 实现 | `kotlinx.coroutines.selects.select { … onAwait { … } }` | `Semaphore` + `async` + `awaitAll` |
| 本版落点 | `SearchViewModel.searchByType`（SearchViewModel.kt:313-327，第一次发布 :343-368，`_isLoading=false` :366） | `BoundedParallel.runAll`（BoundedParallel.kt:95-111） |
| 为什么不能用对方 | 搜索结果写的是**同一个列表**，需要「先上屏」这一个可见事件；三条腿若等齐再发，慢源会拖住快源（v2.5.6 的真机证据：网易云 30s 时 QQ 的 30 条在 ≤5s 已到手，界面却空了 30s，见 :286-295 注释） | 播放侧的腿写的是**同一个对象的不同字段**（`lyrics` / `currentSongArtwork`），没有「发布」这个动作，需要的是失败隔离 + 顺序确定 |

调用点清单（本次审计，`grep BoundedParallel` 全量）：

| 调用点 | 落点 | 腿数 |
|---|---|---|
| 开播辅助腿 | PlayerViewModel.kt:1570（lyrics + cover） | 2 |
| 下一首预取辅助腿 | PlayerViewModel.kt:1696（ttml? + lyric-lrc + cover） | 2~3 |
| 列表预取 | ListPrefetch.kt:154（封面 + 缺元数据的详情） | ≤ limit |
| 连接预热 | ConnectionWarmup.kt:99（两个 host） | 2 |

---

## 6. 未实测项与复现方式

| 未实测项 | 为什么没测 | 怎么复现 |
|---|---|---|
| 「并行后点按到出声」的端到端 TTFB 差值 | 本探针阶段只跑了 JVM 单测，**没有设备在环** | 用 `NetTimingProbeTest` 同款做法在 `playSong` 前后打点；或对同一首歌分别关/开 `startAuxiliaryLoad` 采集 `first audio frame` 时间戳（需要真机 + 登录态） |
| 上限 4 vs 6 的真机对比 | 同上 | 临时把 `DEFAULT_MAX_CONCURRENCY` 改成 6，重复 `probeConcurrencyQueueing`（NetTimingProbeTest.kt:271）观察 `wait` 段与墙钟 |
| 低端机（3GB / API 24）上的反序列化抢线程 | 无该档位设备 | 在 API 24 模拟器 + 真机各跑一次 `:app:connectedDebugAndroidTest` 的网络探针（模拟器数据不可与真机换算） |
| 连续快速切歌时在途辅助腿的真实条数 | 需要运行时插桩 | 在 `startAuxiliaryLoad` 里对 `auxiliaryJob` 计数并打点 |

单测执行证据见 [EVIDENCE.md](EVIDENCE.md)（`0b4ed2c` 实测 141 个 suite / 1940 个用例全绿）。
