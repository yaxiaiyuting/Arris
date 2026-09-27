# Ncrust v3.1.0 交付验证报告（并行预加载 + B站音源）

> 版本 v3.1.0-gpl ｜ versionCode 54 ｜ 单测 1945 全绿 ｜ 验证：API 24 模拟器 + API 33 模拟器 + 真机 S6 ｜ 采集时间 2026-09-28 03:31 CST

---

## 1. 一句话结论

**并行预加载与三源聚合搜索已落地并全部通过静态与单测验收（142 suite / 1945 用例、lint 0 error、debug 与 release 均构建成功、release 包在三台设备上冷启无崩溃）；B站音源的搜索与歌词已端到端实测通过，但「播放」这一项因出口 CDN 风控（403）本轮无法判定，如实记为未验证 —— 本版不声称「B站可以播放」。**

### 1.1 验收标准 → 结果 → 证据

| # | 验收标准 | 结果 | 证据路径 |
|---|---|---|---|
| 1 | 网络调研 RECOMMENDATIONS 完整回答根因分层 | ✅ | [net-research/RECOMMENDATIONS.md](../net-research/RECOMMENDATIONS.md)（L1 网络/服务端、L1b 连接、L2 编排、L3 缓存四层，含被证伪的三个假设）；实测输入 [net-research/net-latency-breakdown.md](../net-research/net-latency-breakdown.md)、[EVIDENCE-S6.md](../net-research/EVIDENCE-S6.md)、[EVIDENCE-EMULATOR.md](../net-research/EVIDENCE-EMULATOR.md) |
| 2 | B站调研 RECOMMENDATIONS 完整回答接入方案 / Wbi / 登录边界 | ✅ | [bili-research/RECOMMENDATIONS.md](../bili-research/RECOMMENDATIONS.md)（§1 两条腿接入模式、§2 Wbi 签名方案、§3 匿名可用边界与「本版不做登录」的 4 条理由、§5 未验证项、§6 风险提示）；原始响应 73 份在 [bili-research/evidence/](../bili-research/evidence/) |
| 3 | 并行预加载生效、遵守 PreloadSlot 不变量 | ✅ | 单测 `PreloadSlotTest`（19 条）、`BoundedParallelTest`（6 条）、`PreloadCachePolicyTest`（9 条）；`PreloadSlot.kt` 逐字节未改（见 §2.2 与 §5 复现命令 R3） |
| 4 | B站搜索 / 播放 / 歌词 | **搜索 ✅ / 歌词 ✅ / 播放 ⚠️ 未验证** | [EVIDENCE-bili-probe.md](EVIDENCE-bili-probe.md)（`PROBE-BILI-SEARCH n=10` + 10 条真实 bvid/title；`lyricLen=629 head=[00:33.26]让我掉下眼泪的`；`PROBE-BILI-PLAY SKIPPED(env)` 403） |
| 5 | B站音源独立开关生效 | ✅ | `PROBE-BILI-TOGGLE off=true n=0 elapsedMs=3.5` vs `off=false n=5 elapsedMs=476`（[EVIDENCE-bili-probe.md](EVIDENCE-bili-probe.md) 判决表；同一探针在 S6 上重跑为 `n=0 / 35.8ms` 与 `n=5 / 549ms`，结论一致、耗时随设备浮动）；单测 `BiliSourceProviderTest.关掉开关之后一个请求都不发 —— 搜索返回空` |
| 6 | 各音源互不影响 | ✅ | `PROBE-BILI-ROUTER loginSources=[netease, qqmusic]` / `selectable=[netease, qqmusic, bilibili]`；单测 `SourceCountsBiliTest.B 站关闭时统计行逐字不变`、`BiliSourceProviderTest.网易云与 QQ 的 host 一个都不能命中`、`BiliSourceProviderTest.来源缺少载荷时取链返回 null 而不是退回别的音源` |
| 7 | 虚拟机验证通过 | ✅ **（口径受限，见注）** | [release-smoke/api24-smoke.txt](release-smoke/api24-smoke.txt)、[release-smoke/api33-smoke.txt](release-smoke/api33-smoke.txt)、截图 [api24-launch.png](release-smoke/api24-launch.png)、[api33-launch.png](release-smoke/api33-launch.png)；三台设备均 `install: Success` + 进程存活 + `crashes / fatal` 为空 + 首页三请求正常 |
| 8 | 单测 / lint / 构建全绿 | ✅ | 单测：`142 suite / 1945 用例 / 0 失败 / 0 错误 / 0 跳过`（`app/build/test-results/testDebugUnitTest/*.xml` 汇总，2026-09-28 03:23）；lint：`0 error`（32 warning，`app/build/reports/lint-results-debug.xml`，03:24）；`assembleDebug` = `app-debug.apk` 31,090,089 B（03:25）、`assembleRelease` = `app-release.apk` 10,157,540 B（03:27） |
| 9 | tag / APK / gradle versionCode 一致 | 待发布后回填 | <!-- RELEASE-BACKFILL --> |

> **⚠️ 第 7 条的口径（必须与结论一起读）**：模拟器走的是**宿主机网络栈**，其网络数字与真机**不可换算**，只用于 A/B 对照（同一份探针在两种环境下形状是否一致）—— 原话见 [net-research/EVIDENCE-EMULATOR.md](../net-research/EVIDENCE-EMULATOR.md) 开头的告示，该文件还记录模拟器把宿主网络**报成蜂窝**（`network=wifi=false cell=true`）。**「模拟器上通过」不等于「真实移动网络下通过」。**
>
> **⚠️ 文件名提示**：`docs/verification/v3.1.0/verification/api33-smoke.txt` 是 **v3.0.0** 的基线冒烟（03:21，`versionName=3.0.0-gpl`），**不是** v3.1.0 的证据；v3.1.0 的三份冒烟全部在 `verification/release-smoke/` 下（03:27–03:28，`versionName=3.1.0-gpl`）。

### 1.2 release 冒烟逐设备明细

| 设备 | 型号 / 系统 | 安装 | 冷启 | 崩溃 | 首页三请求 TTFB |
|---|---|---|---|---|---|
| `emulator-5554` | Android SDK built for x86_64 / 7.0（API 24） | Success | 进程存活 pid 8339，`mResumedActivity=MainActivity` | 无 | 72 / 77 / 180 ms |
| `emulator-5556` | sdk_gphone64_x86_64 / 13（API 33） | Success | 进程存活 pid 8984 | 无 | 69 / 86 / 209 ms |
| `0715f763f54c023a`（真机） | SM-G9209 / 7.0（API 24） | Success | 进程存活 pid 25525，`mResumedActivity=MainActivity` | 无 | 107 / 129 / 258 ms |

三条请求为 `/eapi/v1/discovery/recommend/resource`、`/eapi/v2/discovery/recommend/songs`、`/api/v1/discovery/new/songs`；三台设备的 `dumpsys package version` 均为 `versionName=3.1.0-gpl`。

---

## 2. 改了什么 / 没改什么

### 2.1 改了什么（按模块，逐文件一句话）

#### A. B站音源（新增 `bili/` 六个文件 + 接线）

| 文件 | 一句话 |
|---|---|
| `bili/BiliApi.kt`（新增，+418） | B站网络层：**自带独立 `OkHttpClient`**（不共享网易云的 Referer/UA/Cookie 拦截器）、`x/web-interface/nav` 取 Wbi 密钥（内存缓存 6h）、视频搜索（签名）、取 cid、**旧路径** `x/player/playurl`（`fnval=4048` + `buvid3`）、音频区 `song/info` / `music-service-c/url` / `song/lyric` |
| `bili/BiliModels.kt`（新增，+497） | 纯逻辑解析层（只依赖 `org.json`，JVM 可单测）：搜索条目、音频详情、DASH 音轨选择、音频流与档位反推、**TTL 按 URL 的 `deadline` 反推**、歌词字段的两义性、`BiliTrack` 载荷（`au:<auid>` / `bv:<bvid>:<cid>`） |
| `bili/BiliWbi.kt`（新增，+196） | Wbi 签名纯逻辑：mixinKey 重排表、参数排序/过滤/URL 编码、MD5；**无 Android、无 IO** |
| `bili/BiliPrefs.kt`（新增，+170） | 独立开关（键 `bilibili_enabled`，默认 **false**，唯一读写入口 + 进程内镜像）、8 档 → `qn` 映射与**有界**降级阶梯 |
| `bili/BiliCdn.kt`（新增，+113） | CDN 取流约束：**Referer host 白名单**（`needsReferer`，BiliCdn.kt:82-86）+ **内容寻址缓存键**（`cacheKeyFor`，:107-112） |
| `bili/BiliSourceProvider.kt`（新增，+201） | Provider：「两条腿」——搜索走视频、取链/取词走音频区；**每条对外路径第一行判 `isEnabled`**（:72 / :91 / :126 / :152）；契约是「绝不抛」，失败返回 `null` / 空列表 |

#### B. 并行编排与预加载

| 文件 | 一句话 |
|---|---|
| `network/BoundedParallel.kt`（新增，+140） | 全仓库**唯一**的并行原语：`Semaphore` 硬上限 4、`FetchOutcome` 三态（Ok / Failed / Missing）、`CancellationException` 原样 rethrow、子任务失败互不影响 |
| `player/PreloadCachePolicy.kt`（新增，+105） | URL 缓存新鲜度的**纯函数**：显式 `expiresAtMs` 优先，否则固定 5 分钟；**档位判据在最前面**（降档重试不得复用上一档 URL） |
| `warmup/ListPrefetch.kt`（新增，+183） | 列表预取：只预取**封面 + 缺元数据时的详情**、**绝不预取 URL**；N = WiFi **5** / 移动 **2** / 判不出 **0**；去重 60s + 32 条；并发 4；单任务失败隔离 |
| `network/ConnectionWarmup.kt`（新增，+138） | 连接预热：对取链专用 host 做 DNS 预解析 + 无副作用轻量 GET，5s 超时、失败**完全静默**；**明确不给 B站预热**（未启用的音源不该产生流量） |
| `ui/viewmodel/PlayerViewModel.kt`（+193/−25） | `startAuxiliaryLoad`：取链**之前**并发发起歌词与封面（三条腿真并发）；`preloadNextSong` 内容从「URL + TTML」扩到「**URL + LRC + TTML + 封面**」；新增 `loadBiliLyrics`；URL 新鲜度判定收口到 `PreloadCachePolicy` |
| `player/SongUrlFetcher.kt`（+14） | `SongUrlResult` 新增 `expiresAtMs: Long? = null`（**默认 null ⇒ 网易云/QQ 缓存行为逐字不变**） |
| `warmup/AppWarmup.kt`（+9） | 网络阶段接入 `ConnectionWarmup`，与首页三请求**并发**，不改既有顺序 |
| `search/SearchLatencyTrace.kt`（+13） | 新增 `MARK_BILI_DONE` 分段 —— B站是三个源里唯一要**两跳**（nav → 签名搜索）才能出结果的 |

#### C. 三源聚合搜索

| 文件 | 一句话 |
|---|---|
| `ui/viewmodel/SearchViewModel.kt`（+96/−7） | B站第三条腿（受开关与硬预算约束）、三源发布路径、`biliStatusOf` 三态、日志补 `bili=… biliAllowed=…` |
| `search/SearchRanking.kt`（+31） | 三源 `order` 重载：**B站结果追加在最后**、不参与「按会员交错」；`bili` 为空时直接返回两源结果 |
| `ui/components/SourceCounts.kt`（+24/−3） | `biliCount` / `biliStatus`（默认 `SKIPPED`，不是 `DONE+0`）；`summary()` 在 `SKIPPED` 时**短路** ⇒ 关掉开关统计行与 v3.0.0 逐字相同；`isPending` / `isDone` 覆盖三源 |
| `ui/components/SourceFilter.kt`（新增，+81） | 「只看某个音源」的**纯本地**筛选档（不重新发请求）；B站档只在音源启用时出现 |
| `ui/screen/SearchScreen.kt`（+45/−2） | 筛选行（只在**搜到过东西**时出现）+ 搜索结果列表预取 `LaunchedEffect` |
| `ui/components/SongTags.kt`（+3）、`ui/player/PlayerCard.kt`（+1） | B站行上的音源名（「这一行来自哪里」必须一眼可见） |
| `ui/i18n/Strings.kt`（+45）与 8 个语言文件（各 +6） | 新增 `sourceBilibili` / `sourceSummaryBili` / `aggFilterBili` / `bilibiliEnabledLabel` / `bilibiliEnabledDescription`；**进分组类（`SettingsStrings` / `SourceStrings`）不占主构造器预算**，两条走转发属性 |

#### D. 音源框架与既有结构

| 文件 | 一句话 |
|---|---|
| `source/MusicSource.kt`（+88/−8） | 新增 `BILIBILI("bilibili")`；`selectable` = 三源，**新增 `loginSources`** = 网易云 + QQ，`otherThan()` 改走 `loginSources`（B站不该出现在「换个源登录」的提示里） |
| `source/MusicSource.kt`（`SourceIds`） | `BILI_ID_FLAG = 1L shl 61`、`isBiliId` / `biliId` / `biliRawId`、`sourceOfId` **先判位 62 再判位 61**（判序与历史行为一致） |
| `source/SourceRouter.kt`（+5） | **无条件**注册 `BiliSourceProvider` —— 开关判定放在 Provider 里，因为开关用户随时可改、而注册表只在类加载时跑一次 |
| `source/PlaylistModels.kt`（+9/−2） | `groupPlaylistsBySource` 的默认源 `selectable` → `loginSources` ⇒ 收藏页分组形态与 v3.0.0 逐字相同（不凭空多一个永远空的「B站」分组） |
| `crosssource/CatalogAggregator.kt`（+13） | 5 处**显式** `MusicSource.BILIBILI -> emptyList()` 分支（跨源匹配对 B站无意义）；写成显式分支而非 `else`，将来加音源时编译器会再指出来 |
| `local/LocalPlaylistRepository.kt`（+5） | B站歌单**不做**远程同步 ⇒ 返回 `null`（语义是「这个 key 没有远程来源」，调用方保留本地内容） |
| `source/AlbumNavigator.kt`（+3）/ `source/ArtistNavigator.kt`（+4） | B站没有专辑页；「作者」是 UP 主而接口只给名字不给 mid ⇒ 一律 `null`，走既有「不可跳转 / 跳搜索」降级 |
| `search/TrackAvailability.kt`（+7） | B站的版权可用性与版本标注恒为 `UNKNOWN`（**不知道 ≠ 可播**；猜成 FREE 会让放不出来的曲子排到前面） |
| `source/SourceIdDomain.kt`（+13） | 记录「auid 与网易云**值域重叠**」这一事实 ⇒ 字符串身份不可用于判源，隔离只能靠位 61 |
| `ui/settings/SettingsRegistry.kt`（+29） | 新增 `bilibili_enabled`（`SettingsGroup.GENERAL`、默认 `false`、标 `newInV310`），键名与 `BiliPrefs.KEY_ENABLED` 逐字一致 |
| `ui/screen/SettingsGroupScreen.kt`（+15） | 开关的读写接线（读进程内镜像、写唯一入口 `BiliPrefs.setEnabled`） |
| `MainActivity.kt`（+4） | `BiliPrefs.init(this)` —— 与 `RetrofitClient.init` 同处（两者都是「进程级一次性配置」，分开写会让下一个人只找到一个） |
| `cache/OfflineAudioCache.kt`（+44/−3） | 上游挂一层 `ResolvingDataSource`：**只对 B站 CDN 的 host** 补 `Referer`；缓存键走 `BiliCdn.cacheKeyFor`（内容寻址）。**非 B站 host 的键与请求头逐字不变** |
| `ui/screen/AlbumDetailScreen.kt`（+7）/ `PlaylistDetailScreen.kt`（+6）/ `ArtistDetailScreen.kt`（+6） | 三处 `LaunchedEffect` 接 `ListPrefetch.prefetchList`（与搜索页共四处调用点） |

#### E. 测试

| 文件 | 一句话 |
|---|---|
| `test/.../bili/BiliParseTest.kt`（新增，30 用例） | 解析层：搜索只认 video、封面 http→https、DASH 取最高带宽、**TTL 按 deadline 而不是 timeout**、歌词两义性、id 不撞号 |
| `test/.../bili/BiliSourceProviderTest.kt`（新增，19 用例） | 开关行为、`-352` 判据、**`BiliCdnTest` 的 11 个「必须不命中」host**、设置键一致性、旧路径取流 |
| `test/.../bili/BiliWbiTest.kt`（新增，14 用例） | 四条固定签名向量（A/B/C/D）+ `BiliQualityTest`（档位映射、阶梯有序去重） |
| `test/.../ui/components/SourceCountsBiliTest.kt`（新增，15 用例） | 统计行三源文案 + `SearchRankingThreeSourceTest`（B站追加在最后、不参与会员交错） |
| `test/.../network/BoundedParallelTest.kt`（新增，6 用例） | 并发上限是硬的、失败隔离、取消原样传播 |
| `test/.../player/PreloadCachePolicyTest.kt`（新增，9 用例） | 两种 TTL 模型与边界、档位判据在最前 |
| `androidTest/.../probe/BiliV310ProbeTest.kt`（新增，+330）、`probe/NetTimingProbeTest.kt`（新增，+373） | 仪器化探针（真实网络 + 真实生产代码）；**不参与 `testDebugUnitTest` 的 1945 用例** |

单测总量：**142 suite / 1945 用例**（v3.0.0 基线 141 / 1940）。

### 2.2 没改什么（逐条可验证）

| 项 | 结论 | 验证方式 |
|---|---|---|
| `player/PreloadSlot.kt` | **逐字节未改** —— 待播槽位不变量仍是 v3.0.0 那份实现 | `git diff --stat v3.0.0-gpl HEAD -- app/src/main/java/com/takahashirinta/ncrust/player/PreloadSlot.kt` **输出为空**；该文件最后一次改动是 `63916fc`（2026-09-25），早于本版 |
| `network/RetrofitClient.kt`（含**超时**） | **整个文件未改** ⇒ 「不引入 `callTimeout`」等纪律原样保留 | `git diff --stat v3.0.0-gpl HEAD -- app/src/main/java/com/takahashirinta/ncrust/network/RetrofitClient.kt` **输出为空** |
| 华为 / 荣耀媒体卡片（v2.0.2 的「双通知栏」修复） | 未改 —— 那段逻辑在 `player/PlaybackService.kt`，该文件**不在本版改动清单里** | `git diff --name-only v3.0.0-gpl HEAD -- app/src/main/java/com/takahashirinta/ncrust/player/PlaybackService.kt` **输出为空** |
| 网易云 / QQ 的**协议与端点行为** | 取链端点与参数、质量阶梯、歌词端点、搜索端点、webLog 上报、`otherThan` 语义（`otherThan(NETEASE) == QQMUSIC`）**一行未改**；`SongUrlResult` 只**新增**一个默认 `null` 的字段 | 见 §2.1 各文件改动；`PlaybackGuardTest` / `PlaylistModelsTest` 的既有断言未改 |
| **⚠️ 诚实边界** | 「网易云 / QQ 的**任何行为**都没变」这句话**不成立**，不要照抄：本版**有意**改变了它们的**编排时序**（取链与歌词/封面并发）与**预载内容**（多了 LRC 与封面）—— 这正是「切歌更快」的来源。准确说法是「**协议与端点未变，时序被有意改为并行**」 |

---

## 3. 诚实清单（不许含糊）

| # | 项 | 现状 | 证据 / 说明 |
|---|---|---|---|
| 1 | **模拟器网络不代表真机（虚拟机验证的口径）** | 模拟器走**宿主机网络栈**，其网络数字与真机**不可换算**，只用于 A/B 对照；且模拟器把宿主网络**报成蜂窝**（`cell=true`）⇒ 本报告的 API 24 / API 33 两份冒烟只证明「装得上、起得来、首页请求通」，**不等于真实移动网络下可用** | [net-research/EVIDENCE-EMULATOR.md](../net-research/EVIDENCE-EMULATOR.md) 开头告示与 `PROBE-NET-ENV network=wifi=false cell=true`；§1.1 表格第 7 行的注 |
| 2 | **ExoPlayer 取 B站 CDN 流：未验证** | 同一条直链、同一台设备、同一个进程内：第一次裸 `HttpURLConnection`（UA=ExoPlayerLib + Range + Referer）→ **206 / 1001 字节**；去掉 Referer → **403**；随后同一设备上**全部**请求一律 403（4 种 media3 数据源 × 7 种头组合），两台设备形状完全一致 ⇒ 出口级风控/限流 | [EVIDENCE-bili-probe.md](EVIDENCE-bili-probe.md) 的 `PROBE-BILI-PLAY SKIPPED(env)`、`PROBE-BILI-MATRIX case=1..7 -> 403`、`PROBE-BILI-REFERER variant=A..D -> 403` 与文末「为什么记未验证而不是失败」 |
| 3 | **音频区没有搜索接口（任务书前提被证伪）** | 穷举 **10 个**候选端点：8 个 404、1 个空壳（`/web/menu/search` 返 `data:null`）、2 个 `code:-400`；通用搜索 `search/all/v2` 的 `pageinfo` 与 `result_type` 枚举里**也没有** audio/music ⇒ 只能「视频搜索 + 音频区播放」两条腿 | [bili-research/EVIDENCE.md](../bili-research/EVIDENCE.md) §12；原始响应 [evidence/36-45](../bili-research/evidence/) |
| 4 | **`search_type=music/audio/au/song` 非法** | `music` / `audio` 与**故意乱填的 `foobar`** 返回**完全相同**的 `{"code":-1200,"message":"被降级过滤的请求"}`（HTTP 200）⇒ 是**非法取值**，不是风控；因此 `-1200` **不算**签名被拒、刷新密钥无用 | [evidence/47](../bili-research/evidence/47-searchtype-music.txt)、[48](../bili-research/evidence/48-searchtype-audio.txt)、[49](../bili-research/evidence/49-searchtype-foobar.txt)；单测 `BiliSourceProviderTest.只有 -352 与 -403 算签名被拒；-1200 不算`。⚠️ `au` / `song` 两个取值**没有采到原始证据**，不声称 |
| 5 | **B站歌词每次现取，不进 `LyricsCache`（断网无词）** | `LyricsCache` 存的是网易云的字段形状（lrc/tlyric/yrc/romalrc/ttml），B站只有行级 LRC ⇒ **不走那张表**，每次播放现取 | `PlayerViewModel.kt:1932` 的 KDoc「缓存：**不走 [LyricsCache]**」；`loadBiliLyrics` :1937 |
| 6 | **B站媒体流不参与「离线下载」承诺** | B站直链约 2 小时轮换且**同一首歌每次 URL 都不同**（`trid`/`upsig` 全变），而 `OfflineUrlStore` **没有 TTL 字段** ⇒ 缓存 URL 清单对它没有意义；播放期的 `SimpleCache` 改用**内容寻址键** `bili:<hash>-<档位>.m4a` 命中（而不是按完整 URL）。⚠️ **未实测**：`OfflineLibrary.record` 记的 key 由 `OfflineKeys.keyOf(uri)` 算出，与 `SimpleCache` 给 B站用的 `BiliCdn.cacheKeyFor` **不是同一个函数**，两者对账后 B站曲目能否留在离线曲目清单里**本轮没有验证** | [bili-research/RECOMMENDATIONS.md](../bili-research/RECOMMENDATIONS.md) §2.4、§4.8；`BiliCdn.kt:88-112`；`OfflineAudioCache.kt:154-161`；`PlaybackService.kt:1311` |
| 7 | **匿名无 FLAC（`qn=3` 静默降级为 320K）** | APP 端点 `quality=3` 返回 `type:2` + 同一个 320K 文件（10374528 B，ffprobe 实测 321584 bps）；`qualities[]` 表里**根本没有** `type:3` 条目 | [bili-research/EVIDENCE.md](../bili-research/EVIDENCE.md) §8.2；[evidence/20](../bili-research/evidence/20-songurl-app-au39-qn3.txt)；探针 `PROBE-BILI-STREAM qn=3 label=320K` |
| 8 | **腾讯 / 网易以外没有真机播放验证** | 真机 S6 上只跑了 **release 包的冷启冒烟**（安装 → 冷启 → 无崩溃 → 首页三请求 → 截图）；B站曲目的**真机播放**没有验证过（被第 1 条阻断）。三台设备的验证都只覆盖「冷启动到首页」 | [release-smoke/s6-smoke.txt](release-smoke/s6-smoke.txt) 与 [s6-launch.png](release-smoke/s6-launch.png) |
| 9 | **UI 自动化端到端尝试失败，如实保留** | 计划中的 `uiautomator` + `input tap` 驱动 release 包做搜索/播放**没有成功**（节点树里定位不到底部导航「搜索」入口，脚本点在空坐标上）；`run.log` 里那几条 `aggregate` 来自冷启动时**上一次输入残留**触发的搜索，不是脚本真的操作了搜索页。**没有把它包装成「通过」** | [ui-automation-attempt/README.md](ui-automation-attempt/README.md)、[run.log](ui-automation-attempt/run.log)、[summary.txt](ui-automation-attempt/summary.txt) |
| 10 | **并行之后「点按 → 出声」快了多少毫秒：未采集** | 本轮只有 JVM 单测与仪器化探针，**没有端到端延迟对照**；并发上限 4 是否最优（vs 6）、列表预取的流量与解码影响、移动数据 5→2 省下的字节数均**未采集** | [probe/PROBE-SUMMARY.md](../probe/PROBE-SUMMARY.md) §3.2（Q5–Q8） |
| 11 | **Wbi 密钥跨天轮换未实测** | 本次会话只观测到一个 key 值；「6h TTL + `-352` 强制刷新一次」是否够用，需要跨天长跑 | [bili-research/RECOMMENDATIONS.md](../bili-research/RECOMMENDATIONS.md) §5.2 |
| 12 | **老版本 App 读到 `"bilibili"` key 的真实表现未实测** | 源码审计：`MusicSource.fromKey` 对未知 key 回落 `NETEASE` ⇒ 老版本可能把一首 B站曲目当网易云同号歌取链。**未在设备上复现** | [probe/PROBE-SUMMARY.md](../probe/PROBE-SUMMARY.md) §3.1（Q2） |
| 13 | **`/x/player/wbi/playurl` 的 412 是出口相关的** | 本机出口对四种签名/Cookie 组合**全部 412**；这不代表所有出口都 412 —— 换出口需重测 | [bili-research/RECOMMENDATIONS.md](../bili-research/RECOMMENDATIONS.md) §5.3；[evidence/65](../bili-research/evidence/65-playurl-wbi-nosign.txt) |
| 14 | **B站音频区 LRC 的实际覆盖率未知** | 调研抽样 23 首「约一半」有词，**样本小**；视频条目**没有任何歌词数据源**（诚实降级为空，不是报错） | [bili-research/RECOMMENDATIONS.md](../bili-research/RECOMMENDATIONS.md) §4.4；单测 `BiliParseTest.song_lyric 的两义性` |
| 15 | **`search_type=au` / `song`、`type=-1`（30 秒试听）、`%20` vs `+` 的空格编码** | 均为**未验证**（前两者无原始证据；第三条上游参考自相矛盾，本实现选 `%20` 并有单测钉住，但不是实测结论） | [bili-research/RECOMMENDATIONS.md](../bili-research/RECOMMENDATIONS.md) §5.1、§5.2 |

---

## 4. 未做（有意，不是遗漏）

| 项 | 为什么不做 |
|---|---|
| **B站登录 / 大会员** | 匿名已覆盖「搜索 → 播放 → 歌词」全链路；登录把风险从出口 IP 升级到**用户自己的账号**，只换来收藏夹/投币/FLAC。UI 上如实写「无需登录」「最高 320K（匿名）」，**不承诺**「登录可得无损」 |
| **B站收藏夹同步** | 匿名不可用（`code:4511003 用户未登录`）；且它与「本地歌单 / 云歌单」两源模型不同构 ⇒ `loadRemoteSongs` 对 B站返回 `null`，收藏页分组仍只有网易云 + QQ 两组 |
| **视频画面模式** | 本版 B站只做**音频**：取 DASH 音轨，不取视频轨、不做画面渲染 |
| **歌单同步（B站音频区 amid）** | 端点（`menu/rank` / `menu/hit` / `song/of-menu`）**实测匿名可用**，但没有「我的歌单」语义可依附；本版不做入口 |
| **华为 / 荣耀卡片** | 本版不动 `PlaybackService.kt`（v2.0.2 的双通知栏修复原样保留）；没有新的通知栏需求 |
| **真·FFT** | v3.0.0 的判决未被推翻：数据源可靠但性能未被证明（162 倍实时只覆盖两个一阶低通）。本版不翻案 |
| **B站视频搜索翻页** | 只取第一页；翻页需要额外的页参数与负缓存策略，本版不做 |
| **AMLL TTML / 逐字歌词用于 B站** | B站音频区只给行级 LRC，没有逐字/翻译/TTML 数据源 ⇒ 走既有的诚实降级路径 |
| **跨源匹配含 B站** | `CatalogAggregator` 的 5 处 B站分支一律返回空：B站标题常年带【】与翻唱标注，跨源匹配的假阳性会直接表现为「单曲页推荐了另一首歌」 |

---

## 5. 复现方式（每条关键结论一条可复制命令）

在仓库根目录 `/home/duanjb666/deepseek/Ncrust` 执行。

```bash
# R1 · 单测全绿（142 suite / 1945 用例 / 0 失败）
./gradlew clean testDebugUnitTest
#   → 汇总口径（本报告用的就是这个）：
python3 - <<'EOF'
import glob,re
files=glob.glob('app/build/test-results/testDebugUnitTest/*.xml'); tot=fail=err=skip=0
for f in files:
    h=re.search(r'<testsuite[^>]*',open(f,encoding='utf-8',errors='replace').read(4000)).group(0)
    g=lambda k:(int(re.search(k+r'="(\d+)"',h).group(1)) if re.search(k+r'="(\d+)"',h) else 0)
    tot+=g('tests'); fail+=g('failures'); err+=g('errors'); skip+=g('skipped')
print(f"suites={len(files)} tests={tot} failures={fail} errors={err} skipped={skip}")
EOF

# R2 · lint 无 error + 两个构建
./gradlew lint && grep -c 'severity="Error"' app/build/reports/lint-results-debug.xml   # → 0
./gradlew assembleDebug assembleRelease && ls -l app/build/outputs/apk/{debug,release}/*.apk

# R3 · PreloadSlot.kt 逐字节未改（并行预加载没有破坏待播槽位不变量）
git diff --stat v3.0.0-gpl HEAD -- app/src/main/java/com/takahashirinta/ncrust/player/PreloadSlot.kt   # → 空输出
git log -1 --format='%h %ad %s' --date=short -- app/src/main/java/com/takahashirinta/ncrust/player/PreloadSlot.kt
#   → 63916fc 2026-09-25 …（早于本版）

# R4 · RetrofitClient 的超时未改 / PlaybackService（华为卡片）未改
git diff --stat v3.0.0-gpl HEAD -- app/src/main/java/com/takahashirinta/ncrust/network/RetrofitClient.kt      # → 空输出
git diff --name-only v3.0.0-gpl HEAD -- app/src/main/java/com/takahashirinta/ncrust/player/PlaybackService.kt # → 空输出

# R5 · 开关键名与设置注册表逐字一致、默认关闭
grep -rn 'bilibili_enabled' app/src/main/java/com/takahashirinta/ncrust/bili/BiliPrefs.kt \
                              app/src/main/java/com/takahashirinta/ncrust/ui/settings/SettingsRegistry.kt
grep -n 'DEFAULT_ENABLED' app/src/main/java/com/takahashirinta/ncrust/bili/BiliPrefs.kt   # → false
#   行为判据（关掉时一个请求都不发）：
./gradlew testDebugUnitTest --tests '*BiliSourceProviderTest*'

# R6 · B站调研的 73 份原始响应一键复现（直连，脚本会 unset 代理）
bash docs/verification/v3.1.0/bili-research/collect_evidence.sh          # 全跑
bash docs/verification/v3.1.0/bili-research/collect_evidence.sh songinfo # 只跑名字含 songinfo 的
python3 docs/verification/v3.1.0/bili-research/wbi_golden.py             # Wbi 官方向量离线自检（不依赖网络）

# R7 · CDN 强校验 Referer（带 206 / 不带 403）与 deadline≠timeout
URL=$(curl -sS --compressed -H 'Referer: https://www.bilibili.com/audio/home' \
  'https://www.bilibili.com/audio/music-service-c/web/url?sid=39&quality=0&privilege=2&mid=0&platform=web' \
  | python3 -c 'import sys,json;print(json.load(sys.stdin)["data"]["cdns"][0])')
curl -sS -r 0-1023 -o /dev/null -w 'HTTP=%{http_code}\n' -H 'Referer: https://www.bilibili.com/audio/home' "$URL"  # → 206
curl -sS -r 0-1023 -o /dev/null -w 'HTTP=%{http_code}\n' "$URL"                                                      # → 403
echo "$URL" | grep -o 'deadline=[0-9]*'   # deadline ≈ now+7200；响应体里的 timeout 是 10800

# R8 · B站端到端仪器化探针（真实网络 + 真实生产代码；需要设备/模拟器）
ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.takahashirinta.ncrust.probe.BiliV310ProbeTest
#   403 时该用例打印 SKIP、其它错误才变红（判定口径见探针源码）

# R9 · release 冒烟（安装 → 冷启 → 无崩溃 → 首页三请求 → 截图）
adb -s <serial> install -r app/build/outputs/apk/release/app-release.apk
adb -s <serial> shell am start -n com.takahashirinta.ncrust/.MainActivity && sleep 25
adb -s <serial> shell dumpsys package com.takahashirinta.ncrust | grep versionName   # → 3.1.0-gpl
adb -s <serial> logcat -d | grep -E 'FATAL|AndroidRuntime|NcrustHttpTiming'

# R10 · versionCode 三源交叉验证（本版 = 54 ⇒ 下一个可用 55）
bash tools/next-version.sh    # 注意：脚本在仓库外 <repo>-gpl/tools/，按本仓库惯例不入 git
```

---

## 附录：证据文件总目录

| 目录 / 文件 | 内容 |
|---|---|
| [net-research/](../net-research/)（6 份 + 2 份 EVIDENCE） | 根因分层调研：`net-latency-breakdown.md`（实测分段）、`request-orchestration.md`、`preload-slot-status.md`、`cache-status.md`、`connection-layer.md`、`RECOMMENDATIONS.md` + `EVIDENCE-S6.md`、`EVIDENCE-EMULATOR.md` |
| [bili-research/](../bili-research/)（7 份 + `evidence/` 73 份 + 4 脚本） | `bili-audio-api.md`、`bili-auth.md`、`wbi-signature.md`、`community-implementations.md`、`EVIDENCE.md`、`README.md`、`RECOMMENDATIONS.md`；`evidence/01-96`；`collect_evidence.sh`、`wbi_golden.py`、`wbi_ab.py`、`wbi_sign_reference.py` |
| [probe/](../probe/)（6 份，基线 `0b4ed2c`） | `PROBE-SUMMARY.md`（结论 → 决策 → 代码落点 → 守卫测试）、`EVIDENCE.md`、`probe-parallel.md`、`probe-preload-queue.md`、`probe-preload-list.md`、`probe-bili-integration.md` |
| [EVIDENCE-bili-probe.md](EVIDENCE-bili-probe.md) | B站端到端探针原始输出 + 判决表（含「为什么记未验证而不是失败」） |
| [release-smoke/](release-smoke/) | `api24-smoke.txt`、`api33-smoke.txt`、`s6-smoke.txt` + `api24-launch.png`、`api33-launch.png`、`s6-launch.png` |
| [ui-automation-attempt/](ui-automation-attempt/) | 失败的 UI 自动化记录（如实保留，未被包装成「通过」） |
| [next-version.txt](next-version.txt) | 三源交叉验证：工作区 `versionCode=54` ⇒ 下一个可用 **55** |
