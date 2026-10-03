# ncm 接口耗时分解（v3.1.0 · P0 网络调研）

> 采集时间：2026-09-28 02:22–02:24 ｜ 代码基线：`a86d97b`（v3.0.0）｜
> 采集方式：**真机 S6（SM-G9209 / Android 7.0 / API 24 / WiFi）+ 仪器化探针**
> 原始数据：[EVIDENCE-S6.md](EVIDENCE-S6.md)（逐通样本 + 统计行，未加工）

## 0. 方法与它的边界

| 项 | 值 |
|---|---|
| 探针 | `app/src/androidTest/java/com/takahashirinta/ncrust/probe/NetTimingProbeTest.kt` |
| 手段 | OkHttp `EventListener`（与生产代码 `network/HttpTimingListener.kt` 同一套事件语义，只把「请求头出去之前」再切开） |
| 时钟 | `SystemClock.elapsedRealtime`（单调，不受 NTP 校正影响） |
| 设备 | S6 / WiFi `downKbps=1048576` |
| 样本 | 每目标 6 通；**同一 client 连发** ⇒ 第 1 通冷连接、第 2~6 通共享同一条 h2 连接 |
| 客户端配置 | `connectTimeout(30s)` / `readTimeout(30s)` / **无 `callTimeout`** —— 与生产 `RetrofitClient.restClient` 一致 |

**边界（不许含糊）**：
- 探针请求带的是**匿名 / 无登录态**（没有 MUSIC_U）。带登录态的耗时可能不同（服务端要查账号态）。
- 探针打的是**固定 URL**，且第一通之后连接就复用了。生产里冷启动瞬间并发更高（见 §4）。
- 「宿主机 curl」与「设备」不是同一网络栈：本文所有数字**只来自设备**。

## 1. 结果总表（S6 · WiFi · P50 / P95 / P99，单位 ms）

| 目标 | total P50 | total P95 | TTFB P50 | TTFB P95 | DNS P50 | TCP P50 | TLS P50 | 复用 |
|---|---|---|---|---|---|---|---|---|
| `netease.search`（cloudsearch/pc） | **266** | 270 | **249** | 254 | 19 | 139 | 99 | 5/6 |
| `netease.songurl`（eapi 取链） | 96 | 165 | 81 | 145 | 33 | 220 | 143 | 5/6 |
| `netease.lyric`（api/song/lyric） | 105 | 117 | 82 | 101 | 10 | 139 | 101 | 5/6 |
| **`qq.search`**（musicu.fcg） | **137** | 187 | **121** | 169 | 19 | 234 | 151 | 5/6 |
| `bili.nav`（带 ncm Referer，见 §5） | 123 | 141 | 101 | 115 | 50 | 184 | 130 | 5/6 |
| `bili.audio.info`（同上） | 118 | 138 | 100 | 116 | 15 | 159 | 105 | 5/6 |

冷连接单通（每个目标的第 1 通）：

| 目标 | total | wait（callStart→connectionAcquired） | 其中 DNS | 其中 TCP | 其中 TLS | TTFB | write |
|---|---|---|---|---|---|---|---|
| `netease.search` | 509 | 162 | 19 | 139 | 99 | 334 | 6 |
| `netease.songurl` | 412 | 261 | 33 | 220 | 143 | 145 | 5 |
| `netease.lyric` | 281 | 158 | 10 | 139 | 101 | 110 | 3 |
| `qq.search` | 458 | 261 | 19 | 234 | 151 | 186 | 2 |

复用连接（第 2~6 通）的典型形状：`wait=2~9ms`、`write=2~9ms`、`ttfb≈80~250ms`。

## 2. ncm vs QQ：差异在哪一段

**结论：差异在 TTFB（服务端处理 + 回程），不在连接层。**

- 冷连接：ncm `connect 139ms / tls 99ms`，QQ `connect 234ms / tls 151ms` —— **QQ 的连接建立更慢**。
- 冷连接 total：ncm 509ms vs QQ 458ms —— **同一量级**。
- 复用连接 TTFB：ncm 搜索 **249ms** vs QQ 搜索 **121ms** —— **ncm 是 QQ 的 2.06 倍**。
- 同一台设备、同一条 h2 连接、同一个 `write`（5ms vs 2ms）⇒ 差异**不可能**来自客户端或连接层，
  只能来自服务端（ncm 的 `cloudsearch/pc` 比 QQ 的 `musicu.fcg` 慢）。

**换算成「用户感知」的那一格**：一次搜索的 ncm 那一段 = `dns+connect+tls+ttfb`（冷）≈ 139+99+334 = 572ms；
复用后 ≈ 249ms。这与 v2.5.6 在 PCL110 上观察到的「ncm 慢」是同一个方向，但**量级小一个数量级**
（那次是 30s，这次是 0.25s）—— 见 §3。

## 3. v2.5.6 的「请求头 30s 才出去」是否复现：**没有复现**

v2.5.6 的原始证据（`RetrofitClient.kt:84-100` 引用的真机 logcat）形状是：

```
path=/api/cloudsearch/pc ttfb=-1ms body=-1ms total=20002ms failed=InterruptedIOException
path=/eapi/v2/discovery/recommend/songs ttfb=240ms body=59ms total=30566ms
```

即：`ttfb` 只有 240ms，但 `total` 是 30s ⇒ 「请求头 30s 才出去」。本轮探针把它拆开后**没有复现**：

| 可能承载那 30s 的段 | 本轮实测（S6 · WiFi） |
|---|---|
| `wait`（排队 + DNS + TCP + TLS） | 冷 133~261ms，复用 **2~9ms** |
| `acquireToReq`（拿到连接→开始写头） | **P50 = 0ms**（全部 6 通都是 0~2ms） |
| `write`（把请求头写出去） | **P50 = 2~5ms** |
| 并发排队（6 通同主机同时发，Dispatcher 上限 5） | 墙钟 **573ms**，最大 `wait` **157ms** |

**判决：那 30s 不是客户端编排、不是 Dispatcher 排队、也不是写请求头的开销。**
本轮数据能排除的三条假设与不能排除的两条，见 §6。

## 4. 连接层：HTTP/2 复用是**真的在用**

`probeProtocolAndReuse`（同一 client 连发 3 通 `music.163.com`）：

```
sample=reuse proto=h2 reused=false dns=31 connect=201 tls=133 wait=237 ttfb=500 total=759
sample=reuse proto=h2 reused=true  dns=-1 connect=-1  tls=-1  wait=2   ttfb=369 total=380
sample=reuse proto=h2 reused=true  dns=-1 connect=-1  tls=-1  wait=3   ttfb=378 total=391
```

- 第 1 通建连（dns+tcp+tls = 365ms），第 2/3 通 `dns/connect/tls` 全部为 `-1`（未发生）⇒ **同一连接复用**。
- 协议恒为 **h2**（ALPN 协商成功）⇒ HTTP/2 多路复用可用。
- 三条目标是**不同 host**（`music.163.com` / `interface3.music.163.com` / `u.y.qq.com` /
  `api.bilibili.com` / `www.bilibili.com`），每个 host 各自建连 —— 这是 h2 的正常形状
  （连接按 host 复用，不跨 host）。

**对「冷启动第一通贵」的直接推论**：ncm 的搜索、取链、歌词分别落在
`music.163.com`、`interface3.music.163.com` 两个 host 上 ⇒ **至少要建两条连接**
（≈ 2 × 365ms ≈ 730ms 的一次性成本）。这是可优化项，见 RECOMMENDATIONS §3。

## 5. 一条顺带发现（直接影响 B 站接入）：**跨源 Referer 会 403**

第一轮探针给**所有**请求都带了生产代码那套 `Referer: https://music.163.com/`
（`RetrofitClient.kt:145` / `:234` / `:253` / `:293`）。B 站三条目标**全部 HTTP 403**：

```
variant=A_netease_referer referer=https://music.163.com/ origin=false  http=403 body=<!DOCTYPE HTML PUBLIC ...
variant=B_bili_referer    referer=https://www.bilibili.com/ origin=true http=200 body={"code":-101,"message":"账号未登录",...,"wbi_img":{...}}
variant=C_no_referer      referer=                        origin=false http=200 body={"code":-101,...}
```

**判决（证据充分，不是推断）**：B 站接入**不能复用**ncm 那个无条件注入 Referer/UA/Cookie 的
`CookieInterceptor` 与 `plainClient`；必须有自己的 OkHttp 客户端（或无 Referer）。
带正确的 B 站 Referer 后，匿名 `nav` 返回 **200 + `code:-101` + `wbi_img`** ——
这正是 Wbi 签名要的 key，且**不需要登录**。

## 6. 判决表：哪些是网络环境限制，哪些是可优化的

| 现象 | 本轮证据 | 归类 | 能不能优化 |
|---|---|---|---|
| ncm 搜索 TTFB 249ms vs QQ 121ms | 同设备同连接，差在 TTFB | **服务端**（ncm 接口本身慢） | ❌ 客户端改不动；只能**别再串行等它** |
| 冷连接 ~365~600ms | `dns+connect+tls` | 网络环境 + 每 host 一次 | ✅ 可**预热连接**（启动时提前建连） |
| ncm 取链/歌词在**两个不同 host** | `music.163.com` vs `interface3.music.163.com` | 客户端配置 | ✅ 可预热两个 host |
| 复用连接后 `wait=2~9ms`、`write=2~5ms` | 5/6 复用 | —— | 已是最优 |
| 「请求头 30s 才出去」 | **未复现**（wait/acquireToReq/write 全部 < 10ms） | **环境相关的偶发**（见下） | ⚠️ 只能加可观测性，不能再加短 `callTimeout`（v2.5.6 已证伪） |

**未能排除的两条假设**（诚实标注）：
1. **设备网络栈在特定状态下会长时间阻塞**（例如 WiFi 省电态 / DNS 服务器无响应时的重试）。
   本轮的 6 通都发生在设备刚被唤醒、网络活跃的窗口里，抓不到那个状态。
   v2.5.6 的 30s 与 `connectTimeout(30s)` **精确相等** —— 这更像是「连接建立一直没成功、
   由 connectTimeout 收尾」，而不是「请求头写不出去」。
2. **服务端在登录态 / 特定 deviceId 下的排队**（探针是匿名请求）。

两条都指向同一个结论：**不要用 `callTimeout` 去拦它**（那会把本来会成功的请求杀掉，
v2.5.6 已经踩过），要做的是**把等待从关键路径上挪开**（并行 + 预加载），这正是 v3.1.0 的实现方向。

## 7. 未验证项

- 登录态下的分段耗时**未采集**（探针匿名）。带 MUSIC_U 时服务端要多查账号态，TTFB 可能更高。
- 移动数据（蜂窝）**未采集** —— 本轮只有 WiFi。
- 弱网 / 高丢包 / 跨运营商**未采集**：`netProbeIterations` 与目标列表都在探针里，
  复现只需在目标网络下重跑一次用例。
- **模拟器**上的同一份探针数据见 [EVIDENCE-EMULATOR.md](EVIDENCE-EMULATOR.md)（与真机不可换算，
  模拟器走的是宿主机网络栈）。
