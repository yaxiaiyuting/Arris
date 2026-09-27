# v3.1.0 探针阶段总表（结论 → 决策 → 代码落点 → 守卫测试）

> 采集时间：2026-09-28 02:51 CST ｜ 代码基线：0b4ed2c ｜ 方式：源码审计 + 实测（无臆测）

四份探针：[parallel](probe-parallel.md) · [preload-queue](probe-preload-queue.md) ·
[preload-list](probe-preload-list.md) · [bili-integration](probe-bili-integration.md)。
原始证据与分类见 [EVIDENCE.md](EVIDENCE.md)。

---

## 1. 四条探针的最终判决（一句话版）

| 探针 | 最终判决 |
|---|---|
| **并行编排** | ✅ **成立**：URL / 歌词 / 封面三条腿真的并发（`startAuxiliaryLoad` 在取链之前发起），上限 4 是硬信号量、有依据；「详情」在点播路径上**不存在**，谈不上并行；取消**结构性不可吞**，且歌词腿与取链是兄弟不是父子。 |
| **队列预加载** | ✅ **成立**：预加载的仍是「下一首」，内容扩到 URL + LRC + TTML + 封面；URL 的两种 TTL 模型收在一个纯函数里；四样内容**都不进播放列表**，`PreloadSlot` 与 v3.0.0 **逐字节相同**（git 可证），待播槽位不变量有 19 条守卫单测。 |
| **列表预加载** | ✅ **成立**：只预取封面与元数据、**绝不预取 URL**；N = WiFi 5 / 移动 2 / 离线 0，判不出网络返回 0（宁可少取，不在计费网络上多花）；去重（列表身份 + 60s + 32 条）、并发 4、失败隔离齐全；四处调用点行号已核对。 |
| **B 站接入** | ⚠️ **骨架成立，但尚不能判「可播放」**：三条由探针发现并已在 `0b4ed2c` 修掉的缺陷（歌词取正文、播放地址走旧路径、`-352` 判据）都有守卫单测；**在 `0b4ed2c` 上还剩一条未修** —— ExoPlayer 取 B 站 CDN 流没有 `Referer`，实测（`evidence/96`）三条路都 403，这会单独让「B 站放不出声」。（02:52 起工作区有一份未提交的修复草稿，**本探针未审计**；见 §2.4 D7 与 §3.1 Q1。） |

---

## 2. 结论 → 决策 → 代码落点 → 守卫测试

### 2.1 并行编排（P0-A）

| # | 探针结论（证据） | 决策 | 代码落点 | 守卫测试 |
|---|---|---|---|---|
| A1 | 取链是阻塞的、歌词 launch 排在它之后 ⇒ 两段 RTT **相加**（`request-orchestration.md` §2.1；TTFB P50 80~250ms，`EVIDENCE-S6.md`） | 取链与歌词/封面**同时发起**，用带硬上限的原语收口 | `PlayerViewModel.startAuxiliaryLoad` :1566-1584（调用点 :1146，在 `playJob` :1238 之前） | 端到端未实测；单测覆盖原语本身 |
| A2 | OkHttp `maxRequestsPerHost=5` 且排队不是瓶颈（`EVIDENCE-S6.md` §2：`dispatcherMaxPerHost=5`、`maxWait=190`、`wall=559ms`） | 并发上限 = **4**（Semaphore 硬上限，不是建议） | `BoundedParallel.DEFAULT_MAX_CONCURRENCY` :86、`:100`、`:128` | `BoundedParallelTest.并发上限是硬的` :36（峰值 ≤ limit 且 ≥2）；`非法并发上限被夹到 1` :136 |
| A3 | 失败与「没有」是两种业务结论（无版权/无会员/下架 ≠ 出错）；`Result` 只有两态 | 统一的 `FetchOutcome` 三态（Ok / Failed / Missing） | `BoundedParallel.kt:27-35`、`runOne` :124-139 | `一个子任务抛异常不影响其它子任务` :64（含顺序断言） |
| A4 | v2.5.2 的教训：`runCatching` 捕 `Throwable` 会吞取消 | `catch (CancellationException) { throw e }` 写在 `catch (Exception)` **之前**；不用 `runCatching` | `BoundedParallel.kt:132-137`；同形状：`ConnectionWarmup.kt:116-119`、`:132-135` | `取消原样传播 不被当成子任务失败` :100 |
| A5 | `loadNeteaseLyrics` 的重试循环用 `catch (e: Exception)`（:2112），最后一线会 `return degraded()` | 歌词腿**不能**挂 `playJob`（它每次 `playSong` 都被 cancel :1237、预载接管还会 cancel :1733） | `auxiliaryJob` 与 `playJob` 并列：:1569 vs :1238；理由注释 :1141-1145 | 源码审计 + 取消传播单测 |
| A6 | 「先到先发布」与「收集型并行」是两件事 | 搜索用 `select`，播放侧用 `BoundedParallel`（收集型） | `SearchViewModel.kt:313-327`（首发 :343-368）vs `BoundedParallel.runAll` :95-111 | `SourceCountsTest` / `AggregateStringsTest` 覆盖发布文案；原语单测见上 |
| A7 | 冷连接一次性 ~365ms、取链落在**另一个 host** | 连接预热（2 host、5s 超时、失败静默、**不给 B 站预热**） | `ConnectionWarmup.kt:57-137`（host :71-74、`runAll` :99、边界注释 :49-55） | 无单测（IO 逻辑）；未实测收益 |

### 2.2 队列预加载（P0-B）

| # | 探针结论 | 决策 | 代码落点 | 守卫测试 |
|---|---|---|---|---|
| B1 | 只预取 URL + TTML 不够：切歌瞬间才开始取 LRC 与封面 | 预取**下一首**的 **URL + LRC + TTML + 封面**，三条辅助腿与取链同跑 | `PlayerViewModel.preloadNextSong` :1632-1810（aux :1680-1698、URL :1704-1712） | `PreloadSlotTest`（19 条）保证槽位不变量；预取内容本身无单测（需网络） |
| B2 | URL 的过期判据在三处各写一遍、且只支持一种 TTL 模型 | 收成纯函数 `isFresh`，两种模型：显式 `expiresAtMs`（B 站 `deadline`）优先，否则固定 5 分钟 | `PreloadCachePolicy.kt:62-76`、`DEFAULT_TTL_MS` :50；唯一两处调用 `PlayerViewModel.kt:1195`、`:1705` | `PreloadCachePolicyTest`（9 条，含边界取过期、档位判据在前） |
| B3 | URL 有时效，列表级预取等于制造必然过期的条目 | **URL 只在「下一首」这一个位置预取** | `ListPrefetch.kt:33-41` KDoc；`preloadNextSong` :1709-1712 | —— |
| B4 | 预加载的 URL/LRC/封面都可能被误当成「播放列表的项」 | 明确：**三样都不进 ExoPlayer 播放列表**；`PreloadSlot.decide/transitionMatches` **一行未改** | git 证据：`git diff --stat v3.0.0-gpl -- player/PreloadSlot.kt` 输出为空；最后改动 `63916fc`（2026-09-25，早于本版） | `PreloadSlotTest.kt` 19 条（清单见 `probe-preload-queue.md` §4.3） |
| B5 | 手动切歌必须让旧预载失效 | 版本号（`songPlayVersion`）+ 取消（`preloadJob?.cancel()`）+ 清镜像（`clearPreloadedState`）+ 三道判重 | `:1107`、`:1663-1664`、`:1726-1780`、`:1198/:1261/:1737/:2441`、判重 `:1652/:1655/:1661` | `PreloadSlotTest` 的 `double_preload_does_not_grow_playlist` :101、`transition_rejected_when_item_is_the_duplicate_previous_song` :140 |

### 2.3 列表预加载（P0-C）

| # | 探针结论 | 决策 | 代码落点 | 守卫测试 |
|---|---|---|---|---|
| C1 | 进入列表时首屏封面未预取，滚动才现拉 | 预取前 N 首的**封面 +（缺元数据时的）详情**，**绝不预取 URL** | `ListPrefetch.prefetchBlocking` :126-156（封面 :131-144、详情 :147-151） | 无（需网络/Coil）；诊断入口 `prefetchBlockingForProbe` :180-182 |
| C2 | 移动数据与 WiFi 的成本不同；判不出网络时不该赌 | N = WiFi/Ethernet **5** / 蜂窝 **2** / 其它与判不出 **0** | `WIFI_LIMIT` :59、`MOBILE_LIMIT` :62、`limitFor` :84-95（`:87/:88/:93` 三条返回 0） | 无纯函数单测（依赖 `ConnectivityManager`）；未实测 |
| C3 | 同一列表来回切 tab 不该反复打网络 | 按列表身份去重：60s 窗口 + 32 条上限（超限整体清空） | `DEDUPE_WINDOW_MS` :68、`DEDUPE_MAX` :71、判定 :110-115、`clearDedupeForTest` :175 | —— |
| C4 | 一个封面失败不该影响别人，更不该影响页面 | 并发上限 4 + 单任务 `runCatching` + 整体 `runCatching`，入口立刻返回 | `:154-155`、`:134-141`、`:149`、`:108` | `BoundedParallelTest` 的失败隔离用例 :64 |
| C5 | 四个列表页要一致地接上 | 四处 `LaunchedEffect`（搜索结果 / 专辑 / 歌单 / 艺人） | `SearchScreen.kt:132-134`、`AlbumDetailScreen.kt:207-210`、`PlaylistDetailScreen.kt:127-130`、`ArtistDetailScreen.kt:195-198` | —— |

### 2.4 B 站接入（B）

| # | 探针结论（证据） | 决策 | 代码落点 | 守卫测试 |
|---|---|---|---|---|
| D1 | auid 与网易云 id **值域重叠**（实测 auid 2.3×10⁷，`EVIDENCE-S6.md:62`） | 合成 id：`BILI_ID_FLAG(1L shl 61) or auid`；`sourceOfId` **先位 62 再位 61** | `MusicSource.kt:221`、`biliId` :236-239、`isBiliId` :227、`sourceOfId` :263-269 | `TrackKeyTest.kt:170-188`（含 `QQ_ID_FLAG - 1` 的边界断言） |
| D2 | 音频区**没有搜索接口**（7 个端点 404、`menu/search` 空壳、`search_type=audio/music` 与乱填的 `foobar` 同回 `-1200`） | 两条腿：搜索走视频、取词走音频区；关键词若是 `au…`/链接则按 auid 直取 | `BiliApi.kt:46-58`；`BiliSourceProvider.searchSongs` :71-81、`parseAuidKeyword` :177-183 | `BiliSourceProviderTest.auid 关键词的识别形状` :147；`BiliParseTest.搜索只认 video 条目` :49 |
| D3 | 视频音轨**没有歌词数据源** | 诚实降级：`markEmpty`，**不是 `fail`** | `BiliSourceProvider.fetchLyric` :149-167；`PlayerViewModel.loadBiliLyrics` :1945-1949 | `BiliParseTest` 的两义性用例 :207/:232 |
| D4 | `song/info` 的 `lyric` 是 **URL**（`evidence/02`），正文在 `/song/lyric`（`evidence/21`） | **已修（`0b4ed2c`）**：改走 `/song/lyric`，URL 形状一律返回 null | `BiliApi.audioLyric` :249-253、`BiliParse.parseAudioLyric` :423-428、`looksLikeUrl` :437-440、provider :153-166 | `BiliParseTest.kt:199/:207/:216/:232` |
| D5 | `/x/player/wbi/playurl` 是**路径级封禁**（四种组合全 412，`evidence/65`/`90`）；旧路径 + `fnval=4048` 可用（`evidence/64`） | **已修（`0b4ed2c`）**：改走旧路径、不签名 | `BiliApi.PLAYURL_URL` :86、`PLAYURL_FNVAL` :93、`videoAudioStream` :296-306、`playUrlFor` :341-342 | `BiliSourceProviderTest.播放地址走旧路径而不是 wbi 路径` :136 |
| D6 | 缺签名/错签名是 `HTTP 200 + code:-352`，不是 412（`wbi-signature.md:20`） | **已修（`0b4ed2c`）**：`-352` 也算被拒 ⇒ 触发强制刷新密钥一次 | `BiliApi.isSignatureRejected` :220-226 | `BiliSignatureRejectionTest.结构化响应里的 -352 与 -403 与 -1200 都算被拒` :182 |
| D7 | B 站 CDN 对**三种流**都强校验 Referer；模拟 ExoPlayer 一律 403（`evidence/80`、`evidence/96`） | **未修（指 HEAD `0b4ed2c`；本版最高优先级遗留）**：媒体数据源没有任何请求头注入，`SongUrlResult` 也没有 headers 字段。⚠️ 02:52 观察到工作区有一份**未提交、未审计**的草稿（新增 `bili/BiliCdn.kt`，改 `BiliApi.kt`/`OfflineAudioCache.kt`） | 缺口在 `cache/OfflineAudioCache.kt:111-122`（`DefaultDataSource.Factory` 无 `defaultRequestProperties`）与 `player/SongUrlFetcher.kt:34-75` | **无**（这正是它危险的地方：单测全绿也发现不了） |
| D8 | 独立开关必须默认关（外部平台依赖） | 默认 `false`；读内存镜像、写唯一入口；关闭时四条入口第一行 return | `BiliPrefs.kt:121/:124/:135/:143-146/:155`；`SettingsRegistry.kt:354-365`；`MainActivity.kt:224`；`BiliSourceProvider.isEnabled` :62 | `BiliSourceProviderTest.kt:41/:49/:60`（行为性；**不是**字节级零请求） |
| D9 | 网易云 Referer 会让 B 站 403（`EVIDENCE-S6.md:104-106`） | B 站**独立 OkHttpClient**，不共享 CookieInterceptor/Referer/连接池 | `BiliApi.kt:116-121`、类文档 :23-42 | `BiliSourceProviderTest.Provider 已在 SourceRouter 注册` :107 |
| D10 | B 站失败不得影响其他音源 | Provider 契约「绝不抛」+ 路由器 `runCatching` + 搜索硬预算 + 取链不回落 | `MusicSourceProvider.kt:33-41`、`SourceRouter.kt:82-87`、`SearchViewModel.kt:269-279`、`SourceRouter.kt:65-76` | `BiliSourceProviderTest.来源缺少载荷时取链返回 null 而不是退回别的音源` :73 |
| D11 | 匿名 web 端点四个 qn 都返回 192K（`evidence/13..16`） | 映射表 8 档 → qn 0/1/2/3；阶梯有界；`levelFromFile = true` 如实显示降级 | `BiliPrefs.kt:64-70`、`:78-81`；`BiliSourceProvider.kt:97-106`、`:195` | `BiliQualityTest.kt:192/:207/:220` |
| D12 | B 站没有会员信号 | 搜索结果**追加在最后**、不参与交错；统计行第三段在 `SKIPPED` 时**短路** | `SearchViewModel.kt:147-175`、`:351-364`；`SourceCounts.kt:108-114`（短路 :112） | `SourceCountsBiliTest.kt:34`（关闭时逐字不变）、`SearchRankingThreeSourceTest.kt:154/:166` |

---

## 3. 探针**没能回答**的问题（诚实清单）

按「影响」排序。每条都给复现方式或说明为什么现在答不了。

### 3.1 会阻断功能（必须在合并前解决或明确接受）

| # | 问题 | 现状 | 怎么答 |
|---|---|---|---|
| Q1 | **ExoPlayer 能否播 B 站流**（§2.4 D7） | 实测三条 CDN 路径模拟 ExoPlayer 都是 403；代码里没有任何 Referer 注入 ⇒ 推断为「不能」。⚠️ 02:52 起工作区有一份未提交的 Referer 修复草稿（`bili/BiliCdn.kt`），**本探针未审计**；结论只对 HEAD `0b4ed2c` 成立 | 真机启用 B 站开关，播一个 `au:<id>` 曲目，看 `onPlayerError`；或在挂上 host 限定的 Referer 后重测（注意先清 `filesDir/offline/audio`，403 可能已被写进缓存） |
| Q2 | 老版本 App 读到 `"bilibili"` 音源 key 的真实表现 | 源码审计：`MusicSource.fromKey` 回落 NETEASE ⇒ 可能把 B 站曲目当网易云同号歌**取链**；但标志位（位 61）不在 `sourceOfId` 的老实现里，老版本会判成网易云 | 装一个 v3.0.0 包 + 写入一条 `source=bilibili` 的队列 JSON，观察是否跳到一首网易云的同号歌 |
| Q3 | 视频音轨的 `cid` 获取链在真实网络下的成功率（`view` → `playurl` 两跳） | 只有 curl 证据（`evidence/64`），没有客户端实测 | 真机启用开关、搜关键词、点一条视频条目，看 `BiliApi` 日志 |
| Q4 | `audioStream` 的 web 端点是否会在某些曲目/账号态下返回 `type:-1`（30s 试听） | 调研 23 首样本**从未复现**；实现拿到 `-1` 只会显示成「未知」档 | 更大样本抽样（`bili-audio-api.md` §9） |

### 3.2 会让结论不完整（影响体验，不阻断）

| # | 问题 | 现状 |
|---|---|---|
| Q5 | 并行之后「点按 → 出声」到底快了多少毫秒 | **完全未采集**：本探针只跑 JVM 单测，没有设备在环 |
| Q6 | 并发上限 4 在真机/低端机上是否最优（vs 6） | 只有 OkHttp 排队数据（`maxWait 190ms`），没有端到端对照 |
| Q7 | 列表预取（封面 320px × 5）对首屏解码与流量的真实影响 | 未采集；需要 macrobenchmark 或抓包 |
| Q8 | 移动数据下 5→2 省下的字节数、以及 `limitFor` 在 VPN/USB 共享下的分支 | 未采集；VPN 会走 `else -> 0`（推断） |
| Q9 | 预取 LRC「切歌瞬间已在盘上」的命中率 | 未采集；可在真机看 `fetchLyrics cache hit` 日志出现时刻 |
| Q10 | B 站 LRC 的实际覆盖率（多少音频区曲目真的有歌词） | 调研抽样 23 首「约一半」，样本小 |
| Q11 | Wbi 密钥跨天轮换的真实表现（`-352` 修复是否真的够） | 只观测到当天一个 key 值；需要跨天长跑 |
| Q12 | 旧路径 `/x/player/playurl` 是否需要 `Cookie: buvid3` | **证据自相矛盾**（文档说必须，成功样本没带）；未实测 |
| Q13 | `CacheDataSource` 会不会把 403 响应体写进 `SimpleCache` | 未实测（`FLAG_IGNORE_CACHE_ON_ERROR` 已设，但语义未验证） |
| Q14 | B 站曲目的续播进度 / 离线 URL 清单 / 收藏 是否与标志位 id 正确协同 | 只有 `sourceOfId` 的纯逻辑单测，没有跨模块的端到端用例 |
| Q15 | `search_type=au` / `song` 是否也返回 `-1200` | 证据目录里没有这两个取值的原始文件（只有 `music`/`audio`/`foobar`） |
| Q16 | 匿名 `qn=3` 的 FLAC 请求在真实播放链路的降级表现 | 单测只钉住映射与阶梯形状 |
| Q17 | `crtype=2 且 msid != 0` ⇒ `cdns:null` 是否**必然** | 2 个失败样本，疑似规律 |
| Q18 | APP 端点（匿名 320K）是否该采用 | 未决策：它是**实测到的能力**，换端点要改参数名与测 320K 的稳定性 |

### 3.3 明确不在本轮范围

| 项 | 说明 |
|---|---|
| B 站登录 / 大会员 / FLAC | `isLoggedIn` 恒 false（`bili-auth.md` 已调研，未接入） |
| 音频区歌单 / 榜单作为发现源 | 调研确认那些端点**匿名可用**（`menu/rank`、`song/of-menu`），但本版不做入口 |
| B 站视频搜索翻页 | 只取第一页（`BiliApi.searchVideos` :263-281 的 KDoc） |
| AMLL TTML / 逐字歌词用于 B 站 | B 站音频区只给行级 LRC（`bili-audio-api.md` §5.3） |

---

## 4. 本版单测证据（一句话）

`0b4ed2c` 上执行 `./gw.sh :app:testDebugUnitTest`：**BUILD SUCCESSFUL**，
XML 汇总 **141 个 suite / 1940 个用例，0 失败 / 0 错误 / 0 跳过**
（`59f3d6c` 的同一条命令是 1936 —— 差值是 `0b4ed2c` 新增的 4 条回归用例）。
命令、原始输出与统计方法见 [EVIDENCE.md](EVIDENCE.md)。
