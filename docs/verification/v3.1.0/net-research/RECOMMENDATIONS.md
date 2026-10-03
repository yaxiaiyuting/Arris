# RECOMMENDATIONS · v3.1.0 网络层（根因分层 + 可优化边界）

> 输入：[net-latency-breakdown.md](net-latency-breakdown.md)（真机 S6 + 模拟器实测）、
> [request-orchestration.md](request-orchestration.md)、[preload-slot-status.md](preload-slot-status.md)、
> [cache-status.md](cache-status.md)、[connection-layer.md](connection-layer.md)（源码审计）
> 采集时间：2026-09-28 ｜ 代码基线：`a86d97b`

## 1. 结论先行：ncm「慢」可以分成三层，其中**只有一层是网络环境**

| 层 | 现象 | 本轮证据 | 可优化？ |
|---|---|---|---|
| **L1 网络/服务端** | ncm 搜索 TTFB P50 **249ms**，QQ 只有 **121ms**（同设备、同 h2 连接、同 `write`） | 探针 §1/§2 | ❌ **改不动**。这是 ncm `cloudsearch/pc` 自身的处理时间 |
| **L1b 网络/连接** | 冷连接一次性成本 ~365ms（dns 19 + tcp 139 + tls 99），且 ncm 的搜索/取链/歌词分布在 **2 个 host** ⇒ 冷启动要付 2 次 | 探针 §4 | ✅ **可优化**：连接预热（收益 ≈ 365ms × 命中率，成本 ≈ 1 个 404 请求） |
| **L2 编排** | 点歌之后：**取链（网络，阻塞）→ 才 `launch { fetchLyrics }`** ⇒ 歌词的 RTT 完全串在取链后面；封面不预取；下一首只预取 URL + TTML | `request-orchestration.md`、`preload-slot-status.md` | ✅ **可优化，且这是本轮最大的收益点** |
| **L3 缓存** | 歌词缓存命中**不直接落地**（要等 `applyBestLyricSource` 的源选择）；URL 缓存 TTL 是**隐式的 5 分钟常量**、没有显式过期时间戳；进入列表不预取封面 | `cache-status.md` | ✅ **可优化**（也正是 B 站 TTL 必须显式化的原因） |

**被证伪的假设（不许再当理由）**：
- ❌「请求头 30s 才出去」是本轮**没有复现**的（`wait` 2~9ms、`acquireToReq` 0ms、`write` 2~5ms，
  并发 6 通的墙钟只有 573ms）。它的形状与 `connectTimeout(30s)` 精确相等 ⇒ 更像是
  **连接一直没建成功、由 connectTimeout 收尾**的设备/网络状态，而不是客户端编排问题。
- ❌「OkHttp Dispatcher 排队」：6 通同主机并发（上限 5）最大 `wait` 只有 **157ms**，墙钟 573ms。
- ❌「加 `callTimeout` 能救」：v2.5.6 已用真机证伪（20s `callTimeout` 把本来会成功的搜索切成 0 首）。
  **本轮不重新引入任何 `callTimeout`。**

## 2. 该做什么（按收益/风险排序）

### P0-A 并行请求编排（L2）
**现状**：`PlayerViewModel.playSong` 里 `fetchUrlOfflineFirst(...)` 是 `withContext(IO)` 的阻塞调用，
`viewModelScope.launch { fetchLyrics(track) }` 排在它**之后**（缓存命中路径也是）。
⇒ 歌词要等取链完全结束才开始，两段 RTT 相加。

**做法**：把「取链」「歌词」改成**同时发起**的两个子任务，用一个有并发上限的编排原语收口；
最短可用者先落地。并发上限取 **4**（不是 2）：这四件事是 URL / 歌词 / 封面 / 详情，
同 host 上还有首页刷新与预热在跑，4 既能重叠又不至于把 OkHttp 队列堆满。
**必须**：
- `CancellationException` **原样抛出**（v2.5.2 / v2.5.5 的既有纪律）；
- 每个子任务失败**只影响自己**（取链失败 ≠ 歌词失败）；
- 上限是**硬上限**（信号量），不是「建议」。

### P0-B 队列预加载扩展（L2 + L3）
**现状**：`preloadNextSong` 预取 URL + TTML（`AmllTtmlClient.prefetch`），**不预取封面**，
**不预取 LRC/yrc**。URL 的过期判据是 `System.currentTimeMillis() - timestamp <= 5min`。

**做法**：
1. URL 缓存条目加**显式** `expiresAtMs`（不再只靠一个常量 + 时间戳），并把 TTL 变成
   **按音源可配**——B 站必须用它（服务端给 `timeout`）；
2. 预取下一首的**封面**（Coil，与列表预取同一个原语）；
3. 预取下一首的**行级歌词**（LRC），不再只有 TTML —— 切歌瞬间 LRC 已经在盘上；
4. **不破坏待播槽位不变量**：预加载的这三样都**不进 ExoPlayer 播放列表**，
   `PreloadSlot.decide/transitionMatches` 一行不改。

### P0-C 列表预加载（L3）
进入搜索结果 / 专辑 / 歌单 / 艺人页时，预取前 N 首的**封面**（元数据本来就在列表响应里，
不需要额外请求）。**N 按网络类型分档**：WiFi **5**、移动数据 **2**、离线 **0**。
并发上限 **4**，按（列表身份 + 歌曲集合）去重，同一列表滚动不重复预取。
**绝不预取 URL**（有时效，见 P0-B）。

### P1-A 缓存优先（L3）
- URL：`expiresAtMs` 未过期 ⇒ 直接用，**一个字节都不发**（既有行为，本轮把它显式化 + 加单测）；
- 歌词：缓存命中仍是「先给内容、再决定要不要补 TTML」（既有行为，本轮加判据单测防回归）；
- 封面：Coil 命中 ⇒ 不重新请求（既有行为）。

### P1-B 连接预热（L1b，探针后定 → **定为一档小改动**）
探针给的收益上界是 **≈365ms / host / 冷启动首次**。做法收敛在 `warmup/AppWarmup` 的网络阶段里，
**与首页三请求并发**、不改任何既有顺序：
- 对 `interface3.music.163.com`（取链专用 host，冷启动时**没有**任何请求会碰它）发一个
  **无副作用的轻量 GET**，让 TCP+TLS 建好、连接进 OkHttp 池；
- 顺带 `InetAddress.getAllByName` 预解析域名（DNS 那 19~50ms）；
- 全程 `runCatchingCancellable` + 短预算，失败**完全静默**（预热失败不许影响启动）。

**明确不做**：给 B 站也预热（B 站音源默认状态由探针决定，见 §4；未启用的音源不该产生任何流量）。

## 3. 明确不做（以及为什么）

| 不做 | 理由 |
|---|---|
| 给任何请求加 `callTimeout` | v2.5.6 真机证伪（见 `RetrofitClient.kt:75-116`）；本轮探针再次证明「慢」不在客户端可控段 |
| 调大 `maxRequestsPerHost` / 换 `Dispatcher` | 探针证明排队不是瓶颈（maxWait 157ms） |
| 自建代理 / 中转（`ncrust-api` 那条路） | 铁律 1：无自建 API |
| 把 ncm 的 host 合并成一个 | 服务端行为，客户端改不了；预热两个 host 更便宜 |
| 把首页三请求改成串行「省流量」 | 与 L2 的方向相反 |
| 预取 URL 到列表 | URL 有时效（B 站 3h / ncm 实测会轮换），预取一批等于制造一批必然过期的条目 |

## 4. 这一层给 B 站接入的直接约束（从探针来的，不是文档推断）

1. **B 站必须用自己的 HTTP 客户端**：探针 A/B 对照显示，带 ncm 的
   `Referer: https://music.163.com/` 会让 B 站返回 **HTTP 403**（`api.bilibili.com` 与
   `www.bilibili.com` 都中招），换成 B 站自己的 Referer 或**不带** Referer 都是
   `200 + code:-101`。⇒ **绝不能复用生产里那个无条件注入 ncm Referer/UA/Cookie 的
   `CookieInterceptor` 与 `plainClient`**（铁律 27 的具体落点）。
2. **B 站的 URL TTL 必须显式化**：P0-B 的 `expiresAtMs` 就是为它准备的。
3. **并发的同一套上限**：B 站的搜索/取链也走 P0-A 的编排原语，不额外开线程池。
4. **默认关闭**：B 站是一个需要 Wbi 签名、有自己风控的新依赖。默认关闭 ⇒
   「升级后行为不变」是默认体验，用户主动打开才产生流量（与 v3.0.0 五个动效开关
   「默认全开」的取舍相反，理由也相反：那五个是**本机**渲染，这个是**外部平台**流量）。
