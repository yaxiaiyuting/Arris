# 原始证据 · v3.1.0 网络分段探针（API 24 模拟器，A/B 对照用）

设备：`Android SDK built for x86_64` / Android 7.0 / API 24 / **cell=true（模拟器把宿主网络报成蜂窝）**
探针与段定义同 [EVIDENCE-S6.md](EVIDENCE-S6.md)。

> ⚠️ 模拟器走的是**宿主机网络栈**，数字与真机**不可换算**，只用于 A/B 对照
> （同一份探针在两种环境下形状是否一致）。

## 统计行（每目标 6 通）

```
PROBE-NET-STAT label=netease.concurrent n=6 ok=6 reused=0 total p50=682 p95=735 p99=735 ttfb p50=584 p95=629 p99=629 connect p50=96 tls p50=50 wait p50=97 p95=105 dns p50=0 write p50=0 acquireToReq p50=0
PROBE-NET-CONCURRENCY wall=772ms n=6 maxWait=107
PROBE-NET-ENV sdk=24 model=Android SDK built for x86_64 fingerprint=Android/sdk_google_phone_x86_64/generic_x86_64:7.0/NYC/6696031:userdebug/dev-keys
PROBE-NET-ENV network=wifi=false cell=true downKbps=102400 upKbps=51200
PROBE-NET-STAT label=netease.search n=6 ok=6 reused=5 total p50=237 p95=256 p99=256 ttfb p50=236 p95=254 p99=254 connect p50=84 tls p50=44 wait p50=0 p95=0 dns p50=14 write p50=0 acquireToReq p50=0
PROBE-NET-STAT label=netease.songurl n=6 ok=6 reused=5 total p50=65 p95=67 p99=67 ttfb p50=64 p95=65 p99=65 connect p50=135 tls p50=67 wait p50=0 p95=1 dns p50=14 write p50=0 acquireToReq p50=0
PROBE-NET-STAT label=netease.lyric n=6 ok=6 reused=5 total p50=86 p95=89 p99=89 ttfb p50=84 p95=87 p99=87 connect p50=79 tls p50=43 wait p50=0 p95=0 dns p50=0 write p50=0 acquireToReq p50=0
PROBE-NET-STAT label=qq.search n=6 ok=6 reused=5 total p50=116 p95=125 p99=125 ttfb p50=115 p95=119 p99=119 connect p50=191 tls p50=87 wait p50=0 p95=1 dns p50=11 write p50=0 acquireToReq p50=0
PROBE-NET-STAT label=bili.nav n=6 ok=6 reused=5 total p50=98 p95=98 p99=98 ttfb p50=97 p95=98 p99=98 connect p50=124 tls p50=63 wait p50=0 p95=0 dns p50=0 write p50=0 acquireToReq p50=0
PROBE-NET-STAT label=bili.audio.info n=6 ok=6 reused=5 total p50=94 p95=97 p99=97 ttfb p50=93 p95=95 p99=95 connect p50=105 tls p50=54 wait p50=0 p95=0 dns p50=13 write p50=0 acquireToReq p50=0
PROBE-NET-STAT label=reuse n=3 ok=3 reused=2 total p50=290 p95=290 p99=290 ttfb p50=275 p95=275 p99=275 connect p50=103 tls p50=53 wait p50=1 p95=1 dns p50=29 write p50=0 acquireToReq p50=0
```

## B 站请求头 A/B 对照

```
PROBE-BILI-HEADER-BEGIN
PROBE-BILI-HEADER variant=A_netease_referer referer=https://music.163.com/ origin=false http=403 total=224 ttfb=96 body=<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
PROBE-BILI-HEADER variant=B_bili_referer referer=https://www.bilibili.com/ origin=true http=200 total=234 ttfb=101 body={"code":-101,"message":"账号未登录","ttl":1,"data":{"isLogin":false,"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url
PROBE-BILI-HEADER variant=C_no_referer referer= origin=false http=200 total=221 ttfb=100 body={"code":-101,"message":"账号未登录","ttl":1,"data":{"isLogin":false,"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url
PROBE-BILI-HEADER-END
```
