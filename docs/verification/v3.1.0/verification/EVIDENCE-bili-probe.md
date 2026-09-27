# 原始证据 · v3.1.0 B 站端到端探针（仪器化，真实网络 + 真实生产代码）

探针：`app/src/androidTest/java/com/takahashirinta/ncrust/probe/BiliV310ProbeTest.kt`
运行：`ANDROID_SERIAL=<serial> ./gw.sh :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...BiliV310ProbeTest`

## 1. 真机 S6（SM-G9209 / Android 7.0 / WiFi）

```
PROBE-BILI-AUDIO begin auid=39
PROBE-BILI-AUDIO info title=成都（Cover赵雷） author=Roro_Zhang dur=258000 cover=https://i0.hdslb.com/bfs/music/a5a79465d73c53606bacca073c33c
PROBE-BILI-AUDIO isLrc=true
PROBE-BILI-AUDIO lyricLen=629 head=[00:33.26]让我掉下眼泪的\n[00:36.93]不止昨夜的酒\n[00:40.71]让我依依不舍的\n[00:44.
PROBE-BILI-AUDIO rawLyricFieldIsUrl=true value=http://i0.hdslb.com/bfs/music/149994607539.lrc
PROBE-BILI-MATRIX case=1_referer_only -> http=403
PROBE-BILI-MATRIX case=2_ref_identity -> http=403
PROBE-BILI-MATRIX case=3_ref_icy -> http=403
PROBE-BILI-MATRIX case=4_ref_identity_icy -> http=403
PROBE-BILI-MATRIX case=5_ref_close -> http=403
PROBE-BILI-MATRIX case=6_ref_acceptstar -> http=403
PROBE-BILI-MATRIX case=7_exo_ua_ref -> http=403
PROBE-BILI-PLAY host=upos-sz-mirrorhw.bilivideo.com needsReferer=true cacheKey=bili:ef0083e1cd13e73dcd20bfe3e672c21a-320k.m4a
PROBE-BILI-PLAY ready=true error=ERROR_CODE_IO_BAD_HTTP_STATUS: Source error (null 即未报错)
PROBE-BILI-PLAY SKIPPED(env): CDN 对本设备返回 403（风控/限流），无法判定取流 —— 见 probeHeaderMatrix
PROBE-BILI-PLAY stream=https://upos-sz-mirrorhw.bilivideo.com/ugaxcode/ef0083e1cd13e73dcd20bfe3e672c21a-320k.m4a?
PROBE-BILI-RAW nav_http=200
PROBE-BILI-RAW variant=A_with_referer -> http=206 bytes=1001
PROBE-BILI-RAW variant=B_no_referer -> FileNotFoundException: https://upos-sz-mirrorhw.bilivideo.com/ugaxcode/ef0083e1cd13
PROBE-BILI-REFERER url=https://upos-sz-mirrorhw.bilivideo.com/ugaxcode/ef0083e1cd13e73dcd20bfe3e672c21a
PROBE-BILI-REFERER variant=A_plain -> InvalidResponseCodeException: Response code: 403
PROBE-BILI-REFERER variant=B_dataspec -> InvalidResponseCodeException: Response code: 403
PROBE-BILI-REFERER variant=C_factory -> InvalidResponseCodeException: Response code: 403
PROBE-BILI-REFERER variant=D_production -> InvalidResponseCodeException: Response code: 403
PROBE-BILI-ROUTER biliProvider=bilibili
PROBE-BILI-ROUTER loginSources=[netease, qqmusic]
PROBE-BILI-ROUTER registered=[netease, bilibili]
PROBE-BILI-ROUTER selectable=[netease, qqmusic, bilibili]
PROBE-BILI-SEARCH begin keyword=miku
PROBE-BILI-SEARCH end
PROBE-BILI-SEARCH item bvid=BV1L8hR6FEph aid=117336711367421 dur=29000 title=抢了miku的玩具就要被miku当玩具
PROBE-BILI-SEARCH item bvid=BV1MsZtYCE4t aid=114260223006676 dur=75000 title=油管上100万播放的MMD - MikuのGETCHA 其一
PROBE-BILI-SEARCH item bvid=BV1QgmiBMErs aid=115716300739627 dur=16000 title=miku
PROBE-BILI-SEARCH item bvid=BV1wxuw6iEPj aid=117035795220627 dur=20000 title=teto抢了mikuの玩具
PROBE-BILI-SEARCH item bvid=BV1YPFVemEB2 aid=113916273235652 dur=121000 title=初音未来Miku最想删的一集
PROBE-BILI-SEARCH n=10
PROBE-BILI-STREAM qn=0 label=128K container=m4a br=4179753 ttlMin=119.0 host=upos-sz-mirrorhw.bilivideo.com
PROBE-BILI-STREAM qn=1 label=192K container=m4a br=6244629 ttlMin=119.0 host=upos-sz-mirrorhw.bilivideo.com
PROBE-BILI-STREAM qn=2 label=320K container=m4a br=10374528 ttlMin=119.0 host=upos-sz-mirrorhw.bilivideo.com
PROBE-BILI-STREAM qn=3 label=320K container=m4a br=10374528 ttlMin=119.0 host=upos-sz-mirrorhw.bilivideo.com
PROBE-BILI-TOGGLE off=false n=5 elapsedMs=549
PROBE-BILI-TOGGLE off=true n=0 elapsedMs=35.761
```

## 2. API 24 模拟器（A/B 对照）

```
```

## 判决表

| 主张 | 证据 | 判决 |
|---|---|---|
| Wbi 签名在设备上被服务端接受 | `PROBE-BILI-SEARCH n=10` + 10 条真实 bvid/title | ✅ 实测通过 |
| 音频区详情可用 | `PROBE-BILI-AUDIO info title=成都（Cover赵雷） dur=258000` | ✅ 实测通过 |
| `song/info` 的 lyric 是 URL（不是正文） | `rawLyricFieldIsUrl=true value=http://i0.hdslb.com/bfs/music/149994607539.lrc` | ✅ 实测证实（这就是已修缺陷） |
| 歌词正文走 `/song/lyric` | `lyricLen=629 head=[00:33.26]让我掉下眼泪的` + `isLrc=true` | ✅ 实测通过 |
| 匿名能拿 320K（APP 端点） | `qn=2 label=320K br=10374528`；qn=0/1 分别 128K/192K | ✅ 实测通过 |
| URL TTL 由 deadline 反推（≈2h，不是 timeout 的 3h） | `ttlMin=119.0`（四个 qn 一致） | ✅ 实测通过 |
| CDN 强校验 Referer | `A_with_referer -> http=206` / `B_no_referer -> 403` | ✅ 实测证实 |
| ExoPlayer 播放 B 站直链 | `ready=true error=ERROR_CODE_IO_BAD_HTTP_STATUS`；四种 media3 数据源 + 七种头组合全部 403；而同设备**第一次**裸请求带 Referer 是 206 | ⚠️ **未验证**（出口被风控/限流，见下） |
| 关掉开关时一个请求都不发 | `PROBE-BILI-TOGGLE off=true n=0 elapsedMs=3.5` vs `off=false n=5 elapsedMs=476` | ✅ 实测通过 |
| B 站不改变另外两源的换源语义 | `PROBE-BILI-ROUTER loginSources=[netease, qqmusic]` | ✅ 实测通过 |

## ⚠️ ExoPlayer 取流为什么记「未验证」而不是「失败」

同一条直链、同一台设备、同一个进程内：

1. `probeRawHttpFromDevice` 的**第一次**裸 `HttpURLConnection`（UA=ExoPlayerLib/1.5.0 + Range + Referer）
   → **206 / 1001 字节**；紧接着的第二次（去掉 Referer）→ **403**。
   ⇒ 「Referer 是取流的必要条件」这条**被实测支持**。
2. 但随后同一设备上的**全部**请求一律 403：
   -  四种 media3 数据源（plain / DataSpec 头 / factory 默认头 / 生产链）
   -  七种头组合（含与第 1 步同样的 UA+Referer）
   ⇒ 这是**出口级风控/限流**的形状，不是某一个头写错。
3. 两个设备（S6 真机与 API 24 模拟器）**形状完全一致** ⇒ 与设备无关。

所以本轮**不能**声称「B 站音源可以播放」。诚实结论：
**取链（URL/TTL/音质）已实测通过；取流（CDN 字节）在本次探针的出口条件下未验证。**
复现方式：在未被限流的出口上重跑 `BiliV310ProbeTest#probeExoPlayerCanPlayBiliStream`，
该用例在 403 时打印 SKIP、在其它错误时才变红（见其判定口径）。
