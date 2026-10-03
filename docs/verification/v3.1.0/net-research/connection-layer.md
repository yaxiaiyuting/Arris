# 连接层现状（OkHttp 客户端 / 连接池 / DNS / 预热 / HTTP-2）

> 采集时间：2026-09-28 02:24 CST ｜ 代码基线：a86d97b ｜ 采集方式：源码审计（无运行时数据）

本文只描述**现状**，不含建议。凡代码不能直接证明的，显式标注「（推断）」；凡是 OkHttp 库自身的行为而非本项目配置，一律标注「**库默认 / 库语义**」。

---

## 1. 一共几个 OkHttp 客户端

`grep -rn "OkHttpClient" app/src/main/java/` 的结果：**6 个常驻实例 + 1 个每次调用新建的临时实例**。

| # | 名称 | 定义处 | connect | read | write | callTimeout | eventListenerFactory | 拦截器 / 其他 |
|---|---|---|---|---|---|---|---|---|
| 1 | `RetrofitClient.plainClient` | `network/RetrofitClient.kt:36-44` | **30s**（`:38`） | **30s**（`:39`） | 未设 | **未设** | ✅ `HttpTimingListener.factory`（`:42`） | **无任何拦截器**；承载全部 eapi / weapi / 明文 GET / weblog（`:149`、`:207`、`:244`、`:257`、`:274`） |
| 2 | `RetrofitClient.restClient` | `network/RetrofitClient.kt:50-64` | **30s**（`:60`） | **30s**（`:61`） | 未设 | **未设** | ✅（`:62`） | `HttpLoggingInterceptor(BASIC)` **仅 `BuildConfig.DEBUG`**（`:54-58`）+ `CookieInterceptor`（`:59`，实现 `:288-303`）；给 Retrofit 的 `api` 用（`:73`） |
| 3 | `QqClient.searchHttp` | `qq/QqClient.kt:81-88` | **4s**（`:83`） | **6s**（`:84`） | **6s**（`:85`） | **8s**（`:86`） | ❌ | 搜索专用；`QqClient.legacyGet` 用它（`:287`） |
| 4 | `QqClient.http` | `qq/QqClient.kt:90-96` | **15s**（`:92`） | **20s**（`:93`） | **20s**（`:94`） | **未设** | ❌ | QQ 的 `musicu.fcg` 通道（`:248`）：取链 / 歌词 / 登录等 |
| 5 | `QqQrClient.http` | `qq/QqQrClient.kt:46-52` | **10s**（`:49`） | **30s**（`:50`） | 未设 | **未设** | ❌ | 带 `cookieJar(SimpleCookieJar())`（`:48`），QQ 扫码登录专用 |
| 6 | `OkHttpTtmlFetcher.client` | `lyric/AmllTtmlClient.kt:222-227` | **5s**（`:224`） | **10s**（`:225`） | 未设 | **未设** | ❌ | 由 `OkHttpClient().newBuilder()` 派生（`:223`），AMLL TTML 第三方镜像拉取 |
| 7 | MainActivity 剪贴板短链 HEAD（临时） | `MainActivity.kt:2023-2024` | 未设（默认） | 未设 | 未设 | 未设 | ❌ | `OkHttpClient.Builder().followRedirects(false).build()`，**每次解析 `163cn.tv` 短链都新建一个**（整个分支 `:2021-2030`） |

**另有 1 个不在本仓库配置里的实例**：Coil 的内部 OkHttp 客户端 —— `NcrustApplication.newImageLoader()`（`NcrustApplication.kt:43-58`）只配置了 `memoryCache` 与 `diskCache`，**没有 `callFactory(...)`**（`grep callFactory` 全仓 0 命中），因此封面/CDN 流量走 Coil 自己 new 的客户端（**库语义，推断**：Coil 2.x 在未显式提供 `callFactory` 时会自建一个 OkHttp 客户端；本项目未做任何验证性打点）。

> **库语义（推断）**：OkHttp 的每个 `OkHttpClient` 实例各自持有自己的 `ConnectionPool` 与 `Dispatcher`，**只有用 `client.newBuilder()` 派生才会共享**。本项目 6 个常驻实例里有 5 个是 `OkHttpClient.Builder()`（或 `OkHttpClient()`）**从零新建**；唯一的 `newBuilder()` 派生是 `OkHttpTtmlFetcher`（`AmllTtmlClient.kt:223`），而它的父实例也是当场新建的 `OkHttpClient()`、不是任何已存在的客户端。**没有任何一处从已有客户端派生**，因此**它们之间不共享连接池**：`plainClient` 建立的到 `interface.music.163.com` 的连接，`restClient` 发起 `music.163.com` 请求时**不会**复用；Coil 到图床的连接同理。

### 依赖版本（与「库默认」的口径有关，必须交代）

- `app/build.gradle.kts:422` 只显式声明了 `com.squareup.okhttp3:logging-interceptor:4.12.0`，**没有显式声明 okhttp core**；Retrofit 为 `2.11.0`（`:420`）。
- 本机 Gradle 缓存里同时存在 `com.squareup.okhttp3:okhttp` 的 **3.14.9** 与 **4.12.0** 两个版本目录（`~/.gradle/caches/modules-2/files-2.1/com.squareup.okhttp3/okhttp/`）。按 Gradle「最高版本胜出」的解析规则，最终锁定 **4.12.0**（**推断**：未运行 `./gradlew dependencies` 取证）。
- 下文所有「库默认」都指 **OkHttp 4.x** 的默认值，且**均非本项目配置**。

---

## 2. `callTimeout` 现状：**除 QQ 搜索通道外，任何地方都没有设置**

`grep -rn "callTimeout" app/src/main/java/` 的**生产代码**命中只有一处：`qq/QqClient.kt:86`（`.callTimeout(8, TimeUnit.SECONDS)`，只作用于 `searchHttp`）。

其余全部客户端（`plainClient`、`restClient`、`QqClient.http`、`QqQrClient.http`、`OkHttpTtmlFetcher.client`、MainActivity 临时客户端、Coil 内部客户端）**都没有调用 `.callTimeout(...)`** ⇒ 按 **库默认**，`callTimeout = 0` 表示**不限制整通请求的总时长**；只有 `connectTimeout` / `readTimeout` / `writeTimeout` 这些**分阶段空闲超时**在起作用。

### `RetrofitClient.kt:75-116` 那段「v2.5.6 撤销注释」（原文要点）

注释本身就在 `restClient` 定义之后、`eapiPost` 之前（`:75-116`），逐条引述：

- `:76-77`：「v2.5.6 · P1（**已撤销**）：这里曾经有一个只给搜索用的 `"searchApi"`，唯一区别是加了 `callTimeout(20, SECONDS)`。它在真机上**造成了回归，已删除**。」
- `:83-89`（真机 logcat，PLC110 / API 36 / v2.5.6 vc47）：
  ```
  NcrustHttpTiming: path=/api/cloudsearch/pc ttfb=-1ms body=-1ms total=20002ms failed=InterruptedIOException
  SearchViewModel:  Caused by: java.io.IOException: Canceled
  SearchViewModel:  aggregate query='love' netease=0 qq=30 qqTimedOut=false elapsed=20012ms
  ```
- `:91`：「`ttfb=-1` ⇒ **请求头一个字节都没发出去**，却在 20002ms（正好是 `callTimeout`）被杀。」
- `:92-97`：同一份 logcat 里其它**没有** `callTimeout` 的请求形状是 `ttfb=240ms body=59ms total=30566ms`、`ttfb=151ms body=1ms total=30355ms`。
- `:99-100`：「即在这台设备的当前网络下，**每通请求都要 ~30 秒才把请求头送出去**（连接建立/排队，与 `ttfb` 无关）」
- `:102-103`：「⇒ **20s 的 `callTimeout` 卡在这条 30s 的必经路径下面**，于是它拦掉的不是「挂死的请求」，而是**本来会成功的请求**。」
- `:107-110`：「`callTimeout` 覆盖**整通**请求（含连接建立与排队），而 `connectTimeout`/`readTimeout` 是**分阶段空闲**超时。……**加超时前必须先量一次「这个环境里正常请求要多久」，`ttfb` 快不代表整通快。**」
- `:112-113`：「而且本版**根本不需要**这个熔断：用户可见的收益（首帧不再等 ncm）完全来自 `SearchViewModel` 的「先到先发布」，与超时无关。」
- `:115`：「保留本注释而不是默默删掉：下一个想『顺手加个超时』的人应该先看到这段。」

`SearchViewModel.kt:197-200` 与 `:245-247` 是同一件事在调用侧的复述（`⚠️ v2.5.6 曾在这里改用过 searchApi…已撤销，回到共用的 api`；`根因是ncm那条 OkHttp 只有 read/connect 超时、没有 callTimeout`）。

**当前生效的实际超时语义**：ncm 一侧「一通请求可能 30s 才把请求头送出去、整通 30s+」（`:95-97` 的真机数据），而 `plainClient` / `restClient` 只有 30s connect + 30s read，**没有整通上限** —— 这就是 `SearchViewModel` 里 `select` 先到先发布（`SearchViewModel.kt:263-274`）要解决的问题。

---

## 3. 连接池 / Dispatcher：完全没有显式配置

| 检索 | 结果 |
|---|---|
| `ConnectionPool` | `grep -rn "ConnectionPool" app/src/main/java/` → **0 命中** |
| `Dispatcher(` / `dispatcher(` | **0 命中** |
| `retryOnConnectionFailure` | **0 命中**（即库默认 `true`） |
| `pingInterval` | **0 命中** |
| `connectionSpecs` | **0 命中** |
| `cache(…)`（OkHttp 的 HTTP 响应缓存） | **0 命中** |
| `followRedirects` | 只有 `MainActivity.kt:2024` 的 `.followRedirects(false)` |

**库默认值（OkHttp 4.x，非本项目配置）**：

| 参数 | 库默认 |
|---|---|
| `ConnectionPool.maxIdleConnections` | **5** |
| `ConnectionPool.keepAliveDuration` | **5 分钟** |
| `Dispatcher.maxRequests` | **64** |
| `Dispatcher.maxRequestsPerHost` | **5** |
| `retryOnConnectionFailure` | `true` |
| `protocols` | `[HTTP_2, HTTP_1_1]` |

> 这些数字**在代码里一处都找不到**，是 OkHttp 的默认值，本项目的构建脚本也没有覆盖它们。因此「连接池有多大 / 并发几路」在本项目里是**不可从源码配置得到的事实**，只能引用库默认。

由于存在 6+1 个独立实例（§1），库默认的「5 条空闲连接 / 5 分钟」是**每个实例各自一份**。

---

## 4. DNS：没有自定义 `Dns`，没有预解析

| 检索 | 结果 |
|---|---|
| `okhttp3.Dns` / `dns(` | **0 命中**（`grep -rn "Dns\b\|dns(" app/src/main/java/` 只命中注释与 `HttpTimingListener` 的语义表） |
| `InetAddress` | 只有 `auth/QrPairClient.kt:40`：`InetAddress.getByName("255.255.255.255")` —— 这是**局域网 UDP 广播发现**（手机 ↔ 平板配对，`:8` import），不是 HTTP DNS |
| 预解析 / DNS 缓存预热 | **0 命中**（无 `InetAddress.getAllByName` 预热、无 `Dns` 实现、无 `preconnect`） |

OkHttp 默认走 `Dns.SYSTEM`（`InetAddress.getAllByName`），**库语义**：JVM/Android 的 DNS 缓存由系统负责，应用层不介入。本项目的唯一 DNS 相关行为是**被动观测**：`HttpTimingListener` 在语义表里定义 `dns = dnsStart → dnsEnd`（`network/HttpTimingListener.kt:39`），但**监听器本身没有实现 `dnsStart`/`dnsEnd` 回调**（`:89-114` 只覆写了 `requestHeadersEnd` / `responseHeadersStart` / `responseBodyEnd` / `callEnd` / `callFailed`），也没有把 dns 段打进日志（`:140-144` 只打 `path/ttfb/body/total`）。**所以现状是：DNS 段既没有配置、也没有被测量。**

---

## 5. 连接预热：`AppWarmup` 预热了什么，有没有真的预热 TCP/TLS

`warmup/AppWarmup.kt` 做四件事，**全部是「应用层数据预热」，没有任何一处预热传输层连接**：

| 阶段 | 做什么 | 证据 |
|---|---|---|
| 阶段零（独立 launch，不参与 ready 判定） | 触碰 7 个 SharedPreferences 文件，把 XML 解析从主线程拉走（`PREFS_FILES` 列表 `:84-92`） | `:99-107` |
| 阶段零·附 | `LibraryManager.preload(app)`：IO 线程把收藏单曲/专辑/红心 id 解析进内存 | `:110`（实现 `library/LibraryManager.kt:251`） |
| 阶段一（**唯一**决定 splash 结束的部分） | `HomeSnapshot.restoreIntoCache(app)`（磁盘快照灌回内存）→ `_ready.value = true` | `:112-125`（`:115`、`:125`） |
| 阶段二（纯后台，谁都不等） | 在线才继续（`:128-131`）；`withTimeoutOrNull(NETWORK_BUDGET_MS = 8_000L)`（`:134`，常量 `:76`）内：**三条首页请求并发**（`async` × 3，`:137-147`，写回 `ContentCache` `:148-150`）→ `ContentCache.markHomeWarmed()`（`:153`）→ **封面预取 18 张**（`:159-190`：`COVER_PX = 320` `:78`、`PREFETCH_PER_SECTION = 6` `:80`、`urls.chunked(4)` 控制并发 `:176`） | 同左 |
| 阶段三 | `HomeSnapshot.save(...)` 落盘（`:194-202`）；已登录则 `LibraryManager.refreshFromCloud(app)`（`:205-207`）；`_homeFetchDone.value = true`（`:208`） | 同左 |

**「有没有真的预热 TCP/TLS 连接」——没有。** 逐条证据：

1. 全仓 `grep -rni "preconnect"` → **0 命中**；`grep -rn "ConnectionPool"` → **0 命中**（无 `ConnectionPool` 预建、无 `Socket` 预热）。
2. `AppWarmup` 里没有任何直接操作 socket / `Dns` / 连接池的代码（全文 211 行，只有 `Coil`、`PlaylistApi`、`SharedPreferences`、`HomeSnapshot`、`LibraryManager` 的调用）。
3. 因此，唯一会**间接**建立 TCP/TLS 连接的是那三条件首页请求（`PlaylistApi.getDailyRecommendSongs` / `getRecommendPlaylists` / `getTopSongs`，`:138`、`:141`、`:146`）与 18 张封面（Coil，`:180-185`）（**推断**：请求必然经历 TCP/TLS 握手，代码没有显式做「只为建连而发的空请求」）。
4. **这些连接并不落在播放要用的那个客户端上**：三条件首页请求走 `plainClient`（`RetrofitClient.eapiPost`），而封面走 Coil 的内部客户端（§1）—— 两者不共享连接池（§1 的库语义），且**取链**虽然也走 `plainClient`，但冷启动预热发生在首页阶段，与用户点歌之间隔着不确定的时间（**推断**：超过 OkHttp 库默认 5 分钟 keep-alive 就会被回收，此时点歌仍需重新握手）。

---

## 6. HTTP/2：没有任何地方配置协议

| 检索 | 结果 |
|---|---|
| `protocols(` / `protocols` | **0 命中** |
| `Protocol.HTTP_2` / `HTTP_2` / `h2` | **0 命中**（`grep -rn "protocols\|Protocol\.\|HTTP_2\|h2"` 只命中了 `qq/QqQrLogin.kt:235` 的 URL 常量 `login_jump`，与协议无关） |

**库默认（OkHttp 4.x）**：`OkHttpClient.Builder().protocols` 默认为 `[Protocol.HTTP_2, Protocol.HTTP_1_1]`；对 HTTPS 通过 **TLS ALPN** 协商（服务端支持 h2 则用 h2，否则回落 http/1.1）；对明文 HTTP 一律 http/1.1。

本项目的相关事实：

- 全部业务域都是 HTTPS（`music.163.com`、`interface.music.163.com`、`interface3.music.163.com`、`u.y.qq.com`、AMLL 镜像），**没有任何 `http://` 业务端点**。
- `AndroidManifest.xml:32` 设置了 `android:usesCleartextTraffic="true"` —— 这是为局域网配对（`QrPair` 的 UDP 广播 + TCP 明文 JSON/AES 载荷）与 WebView 登录保留的；**没有 `networkSecurityConfig`**（`grep networkSecurityConfig` → 0 命中）。
- **没有任何地方**读取/断言/记录当前用的是 h2 还是 http/1.1；`HttpTimingListener` 也不输出协议版本（`:140-144` 的格式里没有 protocol 字段）。

---

## 7. qm 的网络栈，与 ncm 是不是同一套配置

**不是同一套，而且是刻意分开的。** 依据是 `qq/QqClient.kt:25-34` 的 KDoc 原文：

> 「## 为什么不复用 `RetrofitClient` … ncm 那条链路里塞满了只对 ncm 有意义的东西：eapi 的 AES 签名与 `/eapi/`→`/api/` 路径重写、weapi 的双层 AES + RSA、CSRF token、PC 身份 Cookie。把那条链路「参数化」成一个通用客户端，等于让两套互不相干的协议互相污染 —— 任何一边改签名逻辑都可能悄悄改到另一边的行为。所以 QQ 自己一条 OkHttp 通道，**只共享 OkHttp 这个库，不共享它的配置**。」

具体差异（逐项对照 §1 的表）：

| 维度 | ncm（`plainClient` / `restClient`） | QQ（`searchHttp` / `http`） |
|---|---|---|
| 端点 | `music.163.com`、`interface.music.163.com`、`interface3.music.163.com` | `u.y.qq.com/cgi-bin/musicu.fcg`（`QqClient.kt:59`），搜索另有 `c.y.qq.com` 旧版 GET（`:275-297`） |
| 超时 | 30s / 30s，**无 callTimeout** | `searchHttp` 4/6/6 + **callTimeout 8s**；`http` 15/20/20 |
| 观测 | 两者都挂 `HttpTimingListener` | **都不挂**（`QqClient` 全文无 `eventListenerFactory`） |
| 鉴权注入 | `restClient` 用 `CookieInterceptor`（`RetrofitClient.kt:288-303`）；其余路径手写 `header("Cookie", …)` | 手写 `header("Cookie", …)`（`QqClient.kt:243` 业务通道、`:283` 旧版 GET），cookie 存在独立的 `ncrust_qq_prefs`（`QqClient.kt:50-53`） |
| 用户代理 | 固定 PC Chrome UA（`RetrofitClient.kt:21`），登录等场景另用 iOS UA（`:22`） | `UA_APP = "QQMusic 14090008(android 10)"`（`:62`）与 `UA_WEB`（浏览器 UA，`:65-67`）两套身份，由 `appIdentity` 开关切换（`:130-133`、`:140`） |
| 扫码登录 | — | 第三个实例 `QqQrClient.http`，额外带 `cookieJar`（`QqQrClient.kt:46-52`） |

**所以：QQ 与 ncm 共享的只有 OkHttp 这个库，配置（超时/拦截器/事件监听/身份）完全各自一份。**

第三个「外来源」的先例是 AMLL TTML：`AmllTtmlClient.kt:208-217` 的 KDoc 写明**不复用** `RetrofitClient` 的 client，两条理由 —— ① `plainClient` 是 `private`、没有公开访问器；② 「它的超时是 30s/30s，对『只是补充源』的 TTML 太长 —— 拿不到必须立刻回退，不能让用户等」。

---

## 8. B 站接入：现状与仓库内已有的两套先例

### 8.1 采集时刻的现状

- **`a86d97b` 这个基线里没有任何 B 站相关代码。** 证据：`grep -rniE "bilibili|b23\.tv|api\.bili" app/src/main/java/` → **0 命中**（注意：`bili` 裸串会误命中 `availability` / `capability` / `resolvable`，必须以 `bilibili` / `b23.tv` / `api.bili` 检索）。
- 基线里只有调研产物：`docs/verification/v3.1.0/bili-research/`（`collect_evidence.sh`、`wbi_sign_reference.py`）。
- **⚠️ 采集期间工作区发生了变化（如实记录，不改写上面的基线结论）**：本次审计进行中（02:26–02:27），工作区出现了未跟踪的并行改动 ——
  - `app/src/main/java/com/takahashirinta/ncrust/bili/BiliWbi.kt`（180 行，KDoc 标注「v3.1.0 · B：Wbi 签名。**纯逻辑、无 Android、无 IO**」）、
  - `app/src/main/java/com/takahashirinta/ncrust/bili/BiliPrefs.kt`（159 行，cookie/身份 prefs，KDoc 提到「与 `RetrofitClient.currentCookie` 同一套服务定位器模式」）、
  - `app/src/main/java/com/takahashirinta/ncrust/network/BoundedParallel.kt`（140 行）。
  - 这三个文件里**都没有 `OkHttpClient` / `Retrofit` 的定义**（`grep` 在 `bili/` 下只命中 KDoc 里的 URL 文本）。也就是说：**截至采集时刻，B 站方向还没有自己的 HTTP 客户端**，B 站网络栈尚不存在。
  - 结论按「基线 vs 工作区」两分：连接层的现状（§1–§7）全部基于 `a86d97b`；B 站只有签名与 prefs 两件**纯逻辑**文件，**未接入任何传输层配置**。

### 8.2 仓库里已有的两套「非 ncm 源」接入先例（事实，不含取舍建议）

| 先例 | 做法 | 证据 |
|---|---|---|
| qm（v2.1.0 · B，**第二个音源**） | 自己的 `OkHttpClient`（两个：搜索收紧、业务常规）+ 自己的 cookie 存储 + 自己的 `MusicSourceProvider` 实现，**只共享 OkHttp 库、不共享配置** | `QqClient.kt:25-34`、`:81-96`；`qq/QqMusicSourceProvider.kt:42`（实现）、`:98`（`SourceRouter.register(this)`）、`MainActivity.kt:223`（`QqMusicSourceProvider.install(this)`，进程启动时注册） |
| AMLL TTML（v1.9.0，**第三方补充数据源**，非音源） | 自己的短超时客户端（5s/10s），理由是「补充源拿不到必须立刻回退」 | `AmllTtmlClient.kt:208-217`、`:222-227` |

另有可复用的**音源路由与身份约定**（与传输层无关，但接入新音源必须遵守）：`source/MusicSourceProvider.kt:33-46`（三条契约：绝不抛异常、导出的 `SongItem` 必须带 `MusicSource` 与 sourceId、平台内部标识放 `sourceId` 而不是 `id`）与 `source/SourceRouter.kt`（全应用**唯一**允许按音源分叉的地方）。

---

## 9. 一页速查（全部为源码事实）

| 问题 | 现状 |
|---|---|
| OkHttp 实例数 | 6 个常驻（2 ncm + 2 QQ + 1 QQ 扫码 + 1 TTML）+ 1 个每次新建（短链 HEAD）+ Coil 内部 1 个 |
| 连接池配置 | **未配置**，全部吃 OkHttp 库默认（5 空闲 / 5 分钟），且**每个实例各自一份** |
| Dispatcher 配置 | **未配置**，全部吃库默认（64 / 5） |
| `callTimeout` | 仅 `QqClient.searchHttp`（8s）；ncm 两条链路**都没有**，且 `RetrofitClient.kt:75-116` 记录了「加了 20s 反而造成回归、已撤销」的真机证据 |
| 自定义 `Dns` | 无 |
| DNS 预解析 | 无 |
| 连接预热 / preconnect | 无。`AppWarmup` 只预热 SharedPreferences、首页三请求、首页快照、18 张封面 |
| HTTP/2 配置 | 无任何配置，走库默认 ALPN 协商 |
| 协议/连接池可观测性 | `HttpTimingListener` 只输出 `path/ttfb/body/total`，**不含**协议版本、不含 dns 段（未实现 `dnsStart`/`dnsEnd`）、不聚合 |
