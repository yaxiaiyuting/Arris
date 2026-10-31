# 网易云取链失败 · 根因分析（2026-10-30）

## 现象（来自真机 logcat，PLC110 / API 37）

```
NcrustHttpTiming: path=/eapi/song/enhance/player/url/v1 ttfb=-1ms body=-1ms total=8001ms failed=InterruptedIOException
SongUrlFetcher:   java.io.InterruptedIOException: timeout
SongUrlFetcher:     Caused by: java.io.IOException: Canceled
```

以及成批出现的 HTTP/2 流重置：

```
stream was reset: REFUSED_STREAM
stream was reset: CANCEL
```

`ttfb=-1ms` 是关键：**请求头已发出，响应一个字节都没回来**。这不是"服务端算得慢"
（那会有 TTFB），而是连接层面的黑洞/拒流。两者叠在同一时间段，说明是**同一条 HTTP/2 连接被服务端/中间设备判定不可用**。

## 根因链

1. **单一共享连接 + 高并发**：`RetrofitClient` 里所有 eapi 读请求共用一个 OkHttp 客户端，
   `maxRequestsPerHost = 12`（见 `RetrofitClient.plainClient`）。取链、歌词、封面、详情会同时压在
   `interface3.music.163.com` 的**同一条 HTTP/2 连接**上（h2 多路复用只建一条连接）。
2. **服务端按流拒服务**：`volc-dcdn`（响应头里能看到）在负载/风控触发时对单条连接上的新增流回
   `REFUSED_STREAM` / `RST_STREAM(CANCEL)`。这是**流级**拒绝，连接本身还没被 GOAWAY。
3. **OkHttp 不会因为流级拒绝就换连接**：它对 `REFUSED_STREAM` 的处理是"这条流可以安全重试"，
   但**继续用同一条连接**重试。于是后续每个请求都撞在同一口坏井上，表现为连续失败 + 8 秒级超时。
4. **表现**：先是 "网络异常"，再是长时间缓冲；用户点下一首也放不出来 —— 因为取链全部卡在这条连接上。

## 已做的修复（commit `7b27786`）

`PlaybackReadTransport.awaitPlaybackRead`：只对**只读取链**这一条请求，在满足条件时**追加一次**
使用**独立连接池 + HTTP/1.1** 的请求：
- 触发条件只有两种：错误消息是 `stream was reset: REFUSED_STREAM` / `CANCEL`，或
  `InterruptedIOException("timeout")`（`ttfb=-1` 的表现）；
- 用户取消（协程已取消 / 错误是 `Canceled`）**立即结束**，不触发回退；
- 回退只有一次，仍受原有取链总时限与播放代次约束；写操作（收藏/歌单）不进入这条路径。

## 这套修复能解决什么、不能解决什么

**能解决**：一条已经"中毒"的 h2 连接不会再让取链持续失败 —— 回退走 HTTP/1.1 新连接，绕开坏井。

**不能证明**：服务端为什么开始拒流（限流？中间设备？连接复用触发？）。现有日志只能证明"发生在传输层"。

## 仍然缺的证据（需要真机 + 抓包才能补）

1. 相同 cookie、相同歌，**HTTP/1.1 首请求**是否稳定成功（用来区分"服务端限流"与"h2 连接问题"）。
2. 被拒流的时间分布是否与**并发数**相关（把 `maxRequestsPerHost` 调到 4 复测）。
3. 是否只在 `interface3` 出现（换 `interface` / `interface.music.163.com` 对照）。

## 进一步的方向（未实施）

- 给**取链**单独一个 `protocols = listOf(HTTP_1_1)` 的客户端（彻底不共用 h2 连接），代价是失去多路复用；
- 或对 `REFUSED_STREAM` 直接 `connectionPool.evictAll()` 后再重试（本轮用独立池达到了同样效果）；
- `pingInterval` 从 15s 调小到 5s，让死连接在空闲期就被判死（此前尝试过，本轮未纳入，避免一次改两处变量）。

## 结论一句话

**不是"网络异常"，是 HTTP/2 连接被服务端按流拒绝后仍被复用**；本轮把取链从坏连接上"救"出来，
服务端为何拒流仍未定论，需要用上面的三条对照实验才能定性。
