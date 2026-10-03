# 探针 · 列表预加载（v3.1.0 · P0-C）

> 采集时间：2026-09-28 02:51 CST ｜ 代码基线：0b4ed2c ｜ 方式：源码审计 + 实测（无臆测）

**判决先行**：进入列表时预取的是**前 N 首的封面**（外加「连名字都没有」的条目的元数据），
**绝不预取 URL**；N 按网络类型分档 **WiFi 5 / 移动 2 / 离线 0**；同一张列表 60 秒内只预取一次；
判不出网络时**返回 0**（一个请求都不发）。

> 行号口径：本文引用的 `warmup/ListPrefetch.kt` 与四个 Screen 在 `59f3d6c → 0b4ed2c` 之间
> **一行未动**（`0b4ed2c` 只改 `bili/` 下的源文件与测试），行号在两个基线上都成立。

---

## 1. 预取什么、不预取什么

| 内容 | 预取？ | 落点 |
|---|---|---|
| **封面**（`CoverUrls.small`，320px） | ✅ | `ListPrefetch.prefetchBlocking` :131-144 → Coil `loader.execute` :135-141 |
| **元数据**（只补「名字为空」的条目） | ✅ | :147-151（判据 `song.name.isBlank() && song.isResolvable`）→ `detailOf` :169-172 → `SourceRouter.provider(...).songDetail(...)` |
| **URL** | ❌ **绝不** | 见 `ListPrefetch.kt:33-41` 的 KDoc；URL 只在「下一首」这一个确定位置预取（`PlayerViewModel.preloadNextSong`） |
| 歌词（LRC/TTML） | ❌ | 列表页不预取歌词 |
| 列表本身 | — | 本来就在列表响应里，不需要额外请求（RECOMMENDATIONS §P0-C） |

**为什么不预取 URL**（代码里写死了理由，ListPrefetch.kt:35-41）：URL 有时效 ——
ncm 实测会轮换，B 站直链自带 `deadline`（实测 `now + 7200s`，见
`bili-research/evidence/80-cdn-referer-and-ttl.txt` F 段）。用户进入歌单后可能几分钟才点第一首、
也可能直接划走 ⇒ 预取一批 URL 等于制造一批**必然过期**的条目：要么被 TTL 判据丢掉（白花流量），
要么在临界点被用掉（播到一半 403）。

---

## 2. N 为什么是 5 / 2 / 0

常量（ListPrefetch.kt）：

```kotlin
const val WIFI_LIMIT = 5      // :59  —— 一张列表首屏大约可见 4~5 行
const val MOBILE_LIMIT = 2    // :62  —— 「第一屏不空」与「别花流量」的折中
private const val COVER_PX = 320  // :65 —— 与 AppWarmup.COVER_PX 同口径
```

分档理由（KDoc :43-47）：

- **WiFi 5**：一张列表首屏大约可见 4~5 行，取 5 刚好覆盖首屏（多取的部分是滚动缓冲）。
- **移动数据 2**：用户点进列表往往只是看一眼；2 首 = 「既让第一屏不空，又不为一次滚动花掉几百 KB」。
- **离线 0**：**一个请求都不发**。

### 2.1 移动数据下限制的判据在哪

唯一落点：`ListPrefetch.limitFor(context)`（ListPrefetch.kt:84-95）：

```kotlin
val cm = context.applicationContext
    .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    ?: return 0                                              // :85-87
val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return 0   // :88
return when {
    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> WIFI_LIMIT        // :90
    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> WIFI_LIMIT    // :91
    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> MOBILE_LIMIT  // :92
    else -> 0                                                                  // :93
}
```

- 判据与 `PlayerViewModel.isOnWifi()`（PlayerViewModel.kt:1082-1086）**同源**（都读 `NetworkCapabilities`），
  但后者只回答「是不是 WiFi」（用于选档位），前者要回答「该预取几首」。
- **判不出网络时返回 0**（:87、:88、:93 三条路径）：拿不到 `ConnectivityManager`、
  拿不到 `activeNetwork` 的 capabilities、或 transport 不认识 —— 一律 **0**。
  理由（KDoc :81-83）：**宁可什么都不预取，也不要在计费网络上按 WiFi 的量去拉**。
  这是一个有意的**保守偏向**：错误地少预取只损失一点首屏体验，错误地多预取花的是用户的钱。
- Ethernet 归到 WiFi 档（:91）：电视盒子 / 有线场景没有流量顾虑。

---

## 3. 去重、并发上限与失败隔离

### 3.1 去重：列表身份 + 60s 窗口 + 32 条上限

| 参数 | 值 | 落点 |
|---|---|---|
| 窗口 | `DEDUPE_WINDOW_MS = 60_000L` | ListPrefetch.kt:68 |
| 表上限 | `DEDUPE_MAX = 32` | :71（超过就整体 `clear()`，:113 —— 它只是「最近预取过谁」的备忘，不需要 LRU 精度） |
| 表本体 | `recent: LinkedHashMap<String, Long>`（listKey → 上次预取时刻） | :76 |
| 判定 | `if (last != null && now - last < DEDUPE_WINDOW_MS) return@launch` | :110-115（`synchronized(recent)` 内） |

列表身份 `listKey` 由调用方给出（:100 注释），实际取值见 §4：`album:<id>` / `artist:<id>` /
`playlist:<id>` / `search:<关键词>`。**参数本身也参与去重键的语义**：搜索页换关键词 ⇒ key 变 ⇒
允许再次预取；而「同一张列表滚动」不会重复预取。

空/空白 `listKey` 与空列表直接返回（:104）；`limitFor` 为 0 直接返回（:106-107）——
注意这两条在 `scope.launch` **之前**，所以离线时连一个协程都不会起。

### 3.2 并发上限

- 走 `BoundedParallel.DEFAULT_MAX_CONCURRENCY`（= 4，BoundedParallel.kt:86）：
  `runCatching { BoundedParallel.runAll(tasks) }`（ListPrefetch.kt:154）。
- 每条歌最多贡献 2 个任务（封面 + 元数据补齐），WiFi 下最多 `5 × 2 = 10` 个任务，
  信号量保证同时最多 4 个在跑（:100、:128 的实现见 `probe-parallel.md` §2）。
- **不做**「一次预取一个列表」的串行：多张列表短时间先后进入时，各自的 `runAll` 独立限流
  （与 `probe-parallel.md` §2 的诚实边界同一条：上限是每次调用内的）。

### 3.3 失败隔离

- 单个封面失败：任务体内 `runCatching { loader.execute(...) }`（:134-141）吞掉并返回 true
  ⇒ 不影响同批其它封面。
- 单个详情失败：`runCatching { detailOf(song) }.getOrNull()?.let { true }`（:149）
  ⇒ 拿不到就是 `Missing`，不影响其它任务。
- 整体失败：`runCatching { BoundedParallel.runAll(tasks) }`（:154）
  `.onFailure { Log.w(TAG, "prefetch failed for ${tasks.size} tasks", it) }`（:155）
  ⇒ **整体也绝不抛**（KDoc :124）。
- Coil 不可用（`Coil.imageLoader` 抛）⇒ `runCatching{...}.getOrNull() ?: return`（:128）静默退出。
- 调用入口 `prefetchList` **立刻返回**（预取在 `scope`（SupervisorJob + IO，:73）里跑，:108）。

---

## 4. 调用点清单（四处 `LaunchedEffect`，行号已核对）

| # | 页面 | listKey | 触发键 | 行号 |
|---|---|---|---|---|
| 1 | 搜索结果页 `SearchScreen` | `search:<当前关键词>` | `songs`（**不过滤前的原始列表**） | `SearchScreen.kt:132-134`（`prefetchList(context, …)` :133） |
| 2 | 专辑详情 `AlbumDetailScreen` | `album:<albumId>` | `songItems`（**口径过滤后**） | `AlbumDetailScreen.kt:207-210`（:207 `prefetchContext`，:208 `LaunchedEffect(songItems)`，:209 调用） |
| 3 | 歌单详情 `PlaylistDetailScreen` | `playlist:<playlistId>` | `songs` | `PlaylistDetailScreen.kt:127-130`（:127 context，:128 LaunchedEffect，:129 调用） |
| 4 | 艺人详情 `ArtistDetailScreen` | `artist:<artistId>` | `songs`（映射 `.map { it.song }`） | `ArtistDetailScreen.kt:195-198`（:195 context，:196 LaunchedEffect，:197 调用） |

两处细节（都是源码直读的结论）：

- **专辑页与艺人页传的是「口径过滤后」的列表**：`AlbumDetailScreen.kt:201-203` 先按用户选择的
  音源口径过滤，`:204` 再 `map { it.song }`；艺人页同理（`ArtistDetailScreen.kt:197` 用过滤后的 `songs`）。
  ⇒ 用户切到「只看 QQ」时，预取的也是即将显示的那一批，不会偷偷为另一源花流量。
- **搜索页传的是过滤前的 `songs`**（`SearchScreen.kt:133`），音源筛选（`sourceFilter`，:126-131）
  只影响渲染（`visibleSongs`）。这是有意的：搜索结果里 B 站/QQ 的封面同样值得预取。

---

## 5. 未实测项与复现方式

| 未实测项 | 为什么没测 | 怎么复现 |
|---|---|---|
| 首屏封面命中率提升 | 本阶段无设备在环 | 真机进歌单页后看 Coil 日志 / 断网重进看是否有缓存；或对比预取开关前后的首帧解码日志 |
| 移动数据下「5 → 2」省下的字节数 | 无蜂窝环境采集 | 同一列表分别在 WiFi 与蜂窝下进页，用 `NetworkStatsManager` 或抓包对比请求数 |
| `limitFor` 在 VPN / 蓝牙共享网络下的分支 | 无该网络形态 | 连接 VPN（transport 可能是 VPN，`else -> 0`）与 USB 网络共享（ETHERNET ⇒ 5）各走一次，读 `limitFor` 的返回值 |
| 去重表 32 条上限的真实溢出频率 | 需要长会话插桩 | 插桩 `recent.size` 打点（现成的诊断入口：`clearDedupeForTest` ListPrefetch.kt:175-177） |
| 诊断入口本身 | —— | `prefetchBlockingForProbe`（:180-182）是给探针用的阻塞入口，**生产代码不要走它**（注释 :179） |

单测执行证据见 [EVIDENCE.md](EVIDENCE.md)。
