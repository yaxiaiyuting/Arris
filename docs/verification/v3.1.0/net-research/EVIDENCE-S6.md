# 原始证据 · v3.1.0 网络分段探针（真机 S6）

采集时间：2026-09-28 02:22–02:24（设备本地时钟）
设备：SM-G9209 / Android 7.0 / API 24 / WiFi（downKbps=1048576）
探针：`app/src/androidTest/java/com/takahashirinta/ncrust/probe/NetTimingProbeTest.kt`
运行方式：`ANDROID_SERIAL=0715f763f54c023a ./gw.sh :app:connectedDebugAndroidTest -P...class=...NetTimingProbeTest`

段定义（全部单调时钟 `SystemClock.elapsedRealtime`，取自 OkHttp `EventListener`）：

| 段 | 事件对 | 含义 |
|---|---|---|
| `dns` | dnsStart→dnsEnd | 域名解析 |
| `connect` | connectStart→connectEnd | TCP 连接（不含 TLS） |
| `tls` | secureConnectStart→secureConnectEnd | TLS 握手 |
| `wait` | callStart→connectionAcquired | 排队 + DNS + TCP + TLS 的合计 |
| `acquireToReq` | connectionAcquired→requestHeadersStart | 拿到连接后到开始写请求头 |
| `write` | requestHeadersStart→requestHeadersEnd | 把请求头写出去 |
| `ttfb` | requestHeadersEnd→responseHeadersStart | 首字节（服务端 + 网络那一段） |
| `body` | responseHeadersStart→responseBodyEnd | 响应体传输 |
| `total` | callStart→callEnd | 整通 |
## 1. 逐通原始样本（每目标 6 通，同一 client ⇒ 第 1 通冷连接、其余复用）

```
PROBE-NET-ENV sdk=24 model=SM-G9209 fingerprint=samsung/zerofltectc/zerofltectc:7.0/NRD90M/G9209KEU2ERI2:user/release-keys
PROBE-NET-ENV network=wifi=true cell=false downKbps=1048576 upKbps=1048576
PROBE-NET-BEGIN
PROBE-NET sample=netease.search proto=h2 code=200 reused=false dns=6 connect=147 tls=100 wait=157 acquireToReq=1 write=6 ttfb=370 body=6 total=540
PROBE-NET sample=netease.search proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=3 acquireToReq=0 write=5 ttfb=247 body=5 total=260
PROBE-NET sample=netease.search proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=3 acquireToReq=0 write=4 ttfb=262 body=8 total=277
PROBE-NET sample=netease.search proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=3 acquireToReq=0 write=5 ttfb=260 body=9 total=277
PROBE-NET sample=netease.search proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=3 acquireToReq=0 write=4 ttfb=328 body=4 total=339
PROBE-NET sample=netease.search proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=2 acquireToReq=0 write=6 ttfb=279 body=10 total=297
PROBE-NET sample=netease.songurl proto=h2 code=200 reused=false dns=32 connect=200 tls=132 wait=237 acquireToReq=0 write=3 ttfb=126 body=1 total=367
PROBE-NET sample=netease.songurl proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=4 acquireToReq=1 write=5 ttfb=139 body=2 total=151
PROBE-NET sample=netease.songurl proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=7 acquireToReq=1 write=8 ttfb=76 body=1 total=93
PROBE-NET sample=netease.songurl proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=6 acquireToReq=1 write=7 ttfb=74 body=2 total=90
PROBE-NET sample=netease.songurl proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=7 acquireToReq=0 write=6 ttfb=73 body=1 total=87
PROBE-NET sample=netease.songurl proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=7 acquireToReq=1 write=5 ttfb=72 body=1 total=86
PROBE-NET sample=netease.lyric proto=h2 code=200 reused=false dns=9 connect=402 tls=356 wait=418 acquireToReq=1 write=2 ttfb=107 body=7 total=535
PROBE-NET sample=netease.lyric proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=7 acquireToReq=1 write=3 ttfb=611 body=8 total=630
PROBE-NET sample=netease.lyric proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=5 acquireToReq=0 write=5 ttfb=82 body=10 total=102
PROBE-NET sample=netease.lyric proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=5 acquireToReq=1 write=8 ttfb=82 body=9 total=105
PROBE-NET sample=netease.lyric proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=2 acquireToReq=0 write=3 ttfb=82 body=12 total=99
PROBE-NET sample=netease.lyric proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=5 acquireToReq=0 write=3 ttfb=89 body=10 total=107
PROBE-NET sample=qq.search proto=h2 code=200 reused=false dns=11 connect=259 tls=166 wait=274 acquireToReq=0 write=2 ttfb=206 body=8 total=490
PROBE-NET sample=qq.search proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=7 acquireToReq=0 write=5 ttfb=181 body=7 total=200
PROBE-NET sample=qq.search proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=6 acquireToReq=1 write=4 ttfb=124 body=8 total=143
PROBE-NET sample=qq.search proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=3 acquireToReq=1 write=2 ttfb=126 body=8 total=140
PROBE-NET sample=qq.search proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=7 acquireToReq=0 write=3 ttfb=127 body=8 total=145
PROBE-NET sample=qq.search proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=7 acquireToReq=0 write=4 ttfb=132 body=8 total=151
PROBE-NET sample=bili.nav proto=h2 code=403 reused=false dns=7 connect=202 tls=136 wait=214 acquireToReq=1 write=2 ttfb=163 body=9 total=389
PROBE-NET sample=bili.nav proto=h2 code=403 reused=true dns=-1 connect=-1 tls=-1 wait=6 acquireToReq=0 write=6 ttfb=141 body=10 total=163
PROBE-NET sample=bili.nav proto=h2 code=403 reused=true dns=-1 connect=-1 tls=-1 wait=3 acquireToReq=0 write=3 ttfb=106 body=9 total=121
PROBE-NET sample=bili.nav proto=h2 code=403 reused=true dns=-1 connect=-1 tls=-1 wait=6 acquireToReq=1 write=3 ttfb=109 body=10 total=129
PROBE-NET sample=bili.nav proto=h2 code=403 reused=true dns=-1 connect=-1 tls=-1 wait=7 acquireToReq=0 write=6 ttfb=105 body=10 total=128
PROBE-NET sample=bili.nav proto=h2 code=403 reused=true dns=-1 connect=-1 tls=-1 wait=6 acquireToReq=0 write=3 ttfb=105 body=8 total=122
PROBE-NET sample=bili.audio.info proto=h2 code=403 reused=false dns=20 connect=169 tls=112 wait=198 acquireToReq=0 write=2 ttfb=144 body=8 total=352
PROBE-NET sample=bili.audio.info proto=h2 code=403 reused=true dns=-1 connect=-1 tls=-1 wait=5 acquireToReq=1 write=5 ttfb=120 body=9 total=140
PROBE-NET sample=bili.audio.info proto=h2 code=403 reused=true dns=-1 connect=-1 tls=-1 wait=7 acquireToReq=0 write=3 ttfb=99 body=6 total=115
PROBE-NET sample=bili.audio.info proto=h2 code=403 reused=true dns=-1 connect=-1 tls=-1 wait=4 acquireToReq=0 write=5 ttfb=119 body=8 total=136
PROBE-NET sample=bili.audio.info proto=h2 code=403 reused=true dns=-1 connect=-1 tls=-1 wait=6 acquireToReq=0 write=6 ttfb=94 body=10 total=116
PROBE-NET sample=bili.audio.info proto=h2 code=403 reused=true dns=-1 connect=-1 tls=-1 wait=5 acquireToReq=0 write=2 ttfb=92 body=3 total=102
PROBE-NET-STAT label=netease.search n=6 ok=6 reused=5 total p50=277 p95=339 p99=339 ttfb p50=262 p95=328 p99=328 connect p50=147 tls p50=100 wait p50=3 p95=3 dns p50=6 write p50=5 acquireToReq p50=0
PROBE-NET-STAT label=netease.songurl n=6 ok=6 reused=5 total p50=90 p95=151 p99=151 ttfb p50=74 p95=126 p99=126 connect p50=200 tls p50=132 wait p50=7 p95=7 dns p50=32 write p50=5 acquireToReq p50=1
PROBE-NET-STAT label=netease.lyric n=6 ok=6 reused=5 total p50=105 p95=535 p99=535 ttfb p50=82 p95=107 p99=107 connect p50=402 tls p50=356 wait p50=5 p95=7 dns p50=9 write p50=3 acquireToReq p50=0
PROBE-NET-STAT label=qq.search n=6 ok=6 reused=5 total p50=145 p95=200 p99=200 ttfb p50=127 p95=181 p99=181 connect p50=259 tls p50=166 wait p50=7 p95=7 dns p50=11 write p50=3 acquireToReq p50=0
PROBE-NET-STAT label=bili.nav n=6 ok=6 reused=5 total p50=128 p95=163 p99=163 ttfb p50=106 p95=141 p99=141 connect p50=202 tls p50=136 wait p50=6 p95=7 dns p50=7 write p50=3 acquireToReq p50=0
PROBE-NET-STAT label=bili.audio.info n=6 ok=6 reused=5 total p50=116 p95=140 p99=140 ttfb p50=99 p95=120 p99=120 connect p50=169 tls p50=112 wait p50=5 p95=7 dns p50=20 write p50=3 acquireToReq p50=0
PROBE-NET-END
```

## 2. 并发排队探针（同主机 6 通同时发出，OkHttp Dispatcher maxRequestsPerHost=5）

```
PROBE-NET-CONCURRENCY-BEGIN n=6 dispatcherMaxPerHost=5
PROBE-NET-BEGIN
PROBE-NET sample=netease.concurrent proto=h2 code=200 reused=false dns=0 connect=150 tls=98 wait=157 acquireToReq=1 write=4 ttfb=378 body=9 total=549
PROBE-NET sample=netease.concurrent proto=h2 code=200 reused=false dns=1 connect=174 tls=127 wait=190 acquireToReq=0 write=2 ttfb=299 body=3 total=494
PROBE-NET sample=netease.concurrent proto=h2 code=200 reused=false dns=2 connect=146 tls=94 wait=157 acquireToReq=1 write=3 ttfb=337 body=4 total=502
PROBE-NET sample=netease.concurrent proto=h2 code=200 reused=false dns=5 connect=171 tls=124 wait=180 acquireToReq=0 write=3 ttfb=268 body=3 total=454
PROBE-NET sample=netease.concurrent proto=h2 code=200 reused=false dns=0 connect=158 tls=108 wait=168 acquireToReq=0 write=4 ttfb=342 body=5 total=519
PROBE-NET sample=netease.concurrent proto=h2 code=200 reused=false dns=2 connect=168 tls=114 wait=177 acquireToReq=0 write=2 ttfb=342 body=5 total=526
PROBE-NET-STAT label=netease.concurrent n=6 ok=6 reused=0 total p50=502 p95=526 p99=526 ttfb p50=337 p95=342 p99=342 connect p50=158 tls p50=108 wait p50=168 p95=180 dns p50=1 write p50=3 acquireToReq p50=0
PROBE-NET-END
PROBE-NET-CONCURRENCY wall=559ms n=6 maxWait=190
PROBE-NET-CONCURRENCY-END
```

## 3. 连接复用 / HTTP/2 探针（同一 client 连发 3 通）

```
PROBE-NET-BEGIN
PROBE-NET sample=reuse proto=h2 code=200 reused=false dns=28 connect=199 tls=126 wait=234 acquireToReq=1 write=15 ttfb=314 body=4 total=568
PROBE-NET sample=reuse proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=2 acquireToReq=0 write=4 ttfb=396 body=4 total=406
PROBE-NET sample=reuse proto=h2 code=200 reused=true dns=-1 connect=-1 tls=-1 wait=2 acquireToReq=0 write=4 ttfb=365 body=4 total=375
PROBE-NET-STAT label=reuse n=3 ok=3 reused=2 total p50=406 p95=406 p99=406 ttfb p50=365 p95=365 p99=365 connect p50=199 tls p50=126 wait p50=2 p95=2 dns p50=28 write p50=4 acquireToReq p50=0
PROBE-NET-END
```

## 4. B 站请求头 A/B 对照（403 的根因）

```
PROBE-BILI-HEADER-BEGIN
PROBE-BILI-HEADER variant=A_netease_referer referer=https://music.163.com/ origin=false http=403 total=414 ttfb=163 body=<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
PROBE-BILI-HEADER variant=B_bili_referer referer=https://www.bilibili.com/ origin=true http=200 total=390 ttfb=170 body={"code":-101,"message":"账号未登录","ttl":1,"data":{"isLogin":false,"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url
PROBE-BILI-HEADER variant=C_no_referer referer= origin=false http=200 total=372 ttfb=165 body={"code":-101,"message":"账号未登录","ttl":1,"data":{"isLogin":false,"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url
PROBE-BILI-HEADER-END
```
