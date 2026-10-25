# Arris v3.7.5-gpl 验收记录（播放取链不可用）

- 版本：`3.7.5-gpl` / `versionCode 91`（上一版 90）
- 现象（用户真机 PLC110 / Android 16）：点「下一首」卡 30 s 以上；收藏列表点开 ncm 歌要等 ~5 s；
  加载中竖屏播放页按钮点了没反应；下一首只有歌词变了、播放界面与音源不动；偶发「已降级」提示。
- 结论：**不是网络问题**，是到 eapi 主机的 HTTP/2 请求被服务端/CDN 持续拒流。

## 1. 现场证据（真机 logcat，修复前）

```
12:08:38.121  playFromQueue index=573 key=netease:531786301 mode=2
12:08:38.735  /eapi/song/enhance/player/url/v1  ttfb=-1ms  body=-1ms  total=605ms  failed=D
              g6.D: stream was reset: REFUSED_STREAM
```

- 53 秒内 `fetch failed for level=` 出现 **2064 次**，覆盖 **6 个档位 × 344 首歌**；`ttfb` 全为 `-1`
  （响应头一个字节都没回来），最终 `no playable url ... at any level`。
- 同期其它接口（`account/get`、`playlist/detail`）能通，但出现 `ttfb=96ms / total=30162ms`
  的反常组合 —— 时间全花在同一主机的排队上。
- 设备侧 `ping interface3.music.163.com` 69 ms、`curl`(HTTP/1.1) POST 同一路径 **200 / 0.40 s**。

## 2. 根因

`/eapi/song/enhance/player/url/v1` 走 `interface3.music.163.com`，OkHttp 默认用 HTTP/2。
该链路对 h2 的 POST 返回 `REFUSED_STREAM`（流在服务端被拒绝，不处理、不回响应头），
客户端只能等到超时。对照实验：**保留 h2、每次请求前 `evictAll()` 强制全新连接**，
仍然 600 ms 内被 `StreamResetException` 拒 —— 排除「连接复用/连接池脏」这一假设，
是服务端/CDN 对这条链路的 h2 流直接拒收。

补充证据：**qm（QQ 音乐）的 h2 取链在同一台设备上是正常的**。qm 使用 `QqClient` 自建的
`OkHttpClient`，不继承 `RetrofitClient.baseClient`；因此把 h1 限定在 ncm 客户端即可，
qm 继续走 h2。这条观察同时排除了「设备或运营商整体禁用 h2」的可能，
把问题范围收敛到 ncm 取链主机这条链路。

## 3. 修复

| # | 改动 | 作用 |
|---|---|---|
| 1 | `baseClient` 强制 `Protocol.HTTP_1_1`（只影响 ncm 客户端；qm 用自己的 client，仍走 h2） | 绕开被拒收的 h2 链路；h1 与 h2 同层 TLS，不降低安全等级 |
| 2 | `plainClient` 每主机并发 5 → 12 | 5 条槽位被慢请求占满后，同主机其它请求排队到 30 s（实测） |
| 3 | 取链调用 `callTimeout` 8 s | 一次失败不再耗完 30 s 读超时 |
| 4 | `eapiPostCancellable`（`Call.cancel()`） | 切歌时真正取消在飞请求，连点不再堆积 |
| 5 | 阶梯 `catch` 分离 `CancellationException` / `IOException` | 取消原样抛出；连接层失败不再逐档重试（1 次故障不再放大成 6 次） |
| 6 | 连接层失败 → 弃连接 + 原档重试一次 → 放弃 | 有界重试，且不再把失败当「档位不可用」 |
| 7 | 传输失败归类 `NETWORK` + 3 s 全局冷却 | 走「有界重试」而不是「结构性不可播 ⇒ 跳歌」；实测同类误判曾导致 1 分钟自动跳 240 首 |

## 4. 修复后实测（同一台真机）

```
12:17:50.424  playFromQueue index=47 key=netease:419485656
12:17:50.455  /eapi/song/enhance/player/url/v1  ttfb=115ms  total=118ms
12:17:50.456  requested=jymaster granted=jymaster br=4239511 type=flac accepted=true
```

点击到拿到 URL **130 ms**，首档命中母带 FLAC，无降级提示。用户确认「现在正常了」。

## 5. 自动检查

- JVM 单测：见同目录 `artifacts.json`（全量通过，0 失败）。
- Lint：0 error（既有 warning 计数不变）。
- 版本 `3.7.5-gpl / 91`；签名证书与 3.7.2–3.7.4 相同，可覆盖安装。

## 6. 边界

- 只在真机 PLC110（Android 16，移动网络）上验证；未覆盖其它运营商/地区/CDN 节点。
- **没有**对 h2 做协议层抓包（设备 curl 无 h2 支持、也没有可用抓包工具），
  「服务端拒收 h2」的结论由「保留 h2 + 强制新连接仍被拒」这一对照实验得出，
  属于行为级证据，不是报文级证据。
- 若将来该链路的 h2 恢复，把 `.protocols(...)` 去掉即可回到 h2；没有其它代码依赖 h1。
