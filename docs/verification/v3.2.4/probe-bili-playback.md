# 探针 · P0「B 站歌曲无法播放」根因（v3.2.4）

> 口径：**先探针、后改码**。本文件里所有 HTTP 结论都来自 2026-09-29 的真实请求
> （host 侧 `curl` 矩阵 + 真机 `HttpURLConnection`/media3 链路），原始输出逐字留档在
> [`evidence/`](evidence/)。凡是没有实测到的，本文**明确标注「未验证」**，不写成结论。

## 0. 一句话根因

**B 站媒体 CDN 的准入闸门是「`Referer` 是 B 站域」**且**「`User-Agent` 不在黑名单里」**——
v3.1.0 只补了前者。App 实际发出去的 UA 是 Android 平台的默认 UA
（`Dalvik/2.1.0 (Linux; U; Android …)`，因为它同时命中 CDN 黑名单里的 `dalvik` 与 `android` 两个字样），
于是**取链成功、日志干净、一去取字节就 403**，ExoPlayer 报 `ERROR_CODE_IO_BAD_HTTP_STATUS`，
用户看到的是「一直缓冲 / 无法播放」。

v3.1.0 之所以把这条记成「环境问题（出口风控）」而不是缺陷，是因为它的 A/B 对照里
**手工写死了 `User-Agent: ExoPlayerLib/1.5.0`**（见 §7），于是一个「UA 合格 / 不合格」的对照
被误读成了「Referer 有 / 无」的对照。

---

## 1. B 站音频流 URL 取链接口返回什么？

**端点**（生产代码 `BiliApi.kt:571`）：`GET https://api.bilibili.com/audio/music-service-c/url`

```
?songid=<auid>&quality=<qn>&privilege=2&mid=0&platform=pc
```

实测响应（`auid=39`，`quality=2`，匿名、无 Cookie）——原始件
[`evidence/audio-url-au39-qn2.json`](evidence/audio-url-au39-qn2.json)：

```json
{"code":0,"msg":"success","data":{
  "sid":39,"type":2,"info":"","timeout":10800,"size":10374528,
  "cdns":["https://upos-sz-mirrorhw.bilivideo.com/ugaxcode/ef0083e1cd13e73dcd20bfe3e672c21a-320k.m4a?e=…&deadline=1790630068&…&upsig=…&uparams=…&bvc=vod&nettype=0&orderid=0,1&logo=00000000"],
  "qualities":[{"type":2,"desc":"高品质","size":10374528,"bps":"320kbit/s","tag":"HQ","require":0},
               {"type":1,"desc":"标准","size":6244629,"bps":"192kbit/s","require":0},
               {"type":0,"desc":"流畅","size":4179753,"bps":"128kbit/s","require":0}],
  "title":"成都（Cover赵雷）","cover":"http://i0.hdslb.com/bfs/music/….jpg"}}
```

| 问题 | 实测答案 |
|---|---|
| **URL 格式** | `https://<upos 节点>/ugaxcode/<内容哈希>-<档位>k.m4a?e=…&deadline=…&upsig=…&uipk=…&os=…&platform=pc` —— **内容寻址**：路径里的哈希 + 档位后缀稳定，query 每次都变 |
| **是否有 `backup_url` 备用** | **音频区没有**。响应里只有 `cdns[]`（本次 1 条）。视频 DASH 那一路**有** `backupUrl[]`（本次实测每条音轨 2 条备份，且备份**换 host**：`upos-sz-mirrorhwb` / `upos-sz-mirroralib`），生产代码 `BiliModels.kt:368-369` 只在 `baseUrl` 缺失时才退到 `backupUrl[0]`，**不做多 CDN 轮询** |
| **`sid` 字段** | 响应体里的 `sid` 是**返回字段**；请求参数名是 **`songid`**（用 `sid=` 请求 → `code:72000000 param missing error`，见 §7 的参数矩阵） |

## 2. 请求该 URL 时的完整 HTTP 头是什么？

### 2.1 生产链路实际发出去的（真机俘获，修复前）

`BiliV324ProbeTest.probeDefaultRequestHeaders` 用一个本地 `ServerSocket` 把
**生产的那条链**（`OfflineAudioCache.dataSourceFactory` → `CacheDataSource` →
`ResolvingDataSource` → `DefaultDataSource` → `DefaultHttpDataSource`）引过来，
逐字读它写的请求行与 headers。结果见
[`verification/EVIDENCE-bili-v324.md`](verification/EVIDENCE-bili-v324.md) 的
`PROBE-V324-HEADERS` 段。

| 头 | 现状 | B 站 CDN 是否接受 |
|---|---|---|
| `Referer` | **有**（`https://www.bilibili.com/`，v3.1.0 加的） | ✅ 必需 |
| `User-Agent` | **平台默认**（media3 没有设 `userAgent`；`DefaultDataSource.Factory(Context)` 走的是裸 `DefaultHttpDataSource.Factory()`，`userAgent == null` ⇒ 交给 `HttpURLConnection` 的默认值）⇒ `Dalvik/2.1.0 (Linux; U; Android …)` | ❌ **403** |
| `Cookie`（SESSDATA） | **不带** | 不需要（媒体 CDN 不认登录态；实测匿名 320K 可拿） |
| `Range` | `bytes=0-…`（media3 按需发） | ✅ |
| `Accept-Encoding` | `identity` | ✅ |

**这一条是本次 P0 的关键**：`DefaultHttpDataSource` 的 `userAgent` 字段是 `final`，
而且在 `makeConnection` 里**最后**才写（bytecode 偏移 166-182，晚于 `dataSpec.httpRequestHeaders`
的 69-139）——所以**光把 UA 塞进 `DataSpec` 的 request headers 是无效的，会被覆盖**。
要换 UA 只能 `DefaultHttpDataSource.Factory().setUserAgent(...)`。

### 2.2 UA 黑名单（host 侧 curl 矩阵，逐条可复现）

原始件 [`evidence/ua-android-hypothesis.txt`](evidence/ua-android-hypothesis.txt)、
[`evidence/ua-blocklist-scan.txt`](evidence/ua-blocklist-scan.txt)、
[`evidence/ua-boundary.txt`](evidence/ua-boundary.txt)、
[`evidence/ua-reproducibility.txt`](evidence/ua-reproducibility.txt)。

| `User-Agent` | + B 站 Referer |
|---|---|
| `ExoPlayerLib/1.5.0` | **206** |
| `ExoPlayerLib/1.5.0 (Linux;Android 13)` | **403** |
| `Android` / `myandroidapp/1.0` / `ANDROID` | **403** |
| `Dalvik/2.1.0` | **403** |
| `Dalvik/2.1.0 (Linux; U; Android 13; sdk_gphone64_x86_64 Build/…)` ← **App 现状** | **403** |
| `Mozilla/5.0 (Linux; Android 13; Pixel 7) … Mobile Safari/537.36` | **403** |
| `Mozilla/5.0 (Windows NT 10.0; Win64; x64) … Chrome/120.0.0.0 Safari/537.36` | **206** |
| `okhttp/4.12.0` | **206** |
| `curl/8.5.0` / `curlx/1.0` / `CURL/1.0` | **403** |
| `python-requests/2.31` / `python/3.11` | **403** |
| `VLC/3.0.20 LibVLC/3.0.20` | **403** |
| `Mozilla/5.0 BiliDroid/7.63.0 … os/android …` | **403** |
| `Mozilla/5.0 BiliDroid/7.63.0 … os/ios …`（**同一串，只换 os**） | **206** |
| `Mozilla/5.0 (iPhone; …) … Safari/604.1` / iPad 同款 | **206** |
| `wget/1.21` / `Java/17.0.1` / `Googlebot/2.1` / `spider/1.0` / `Dart/3.0` / `libmpv` | **206** |
| **完全不带 UA 头** | **403** |

⇒ 判据是**子串黑名单**（至少含 `android` / `dalvik` / `curl` / `python` / `vlc`，大小写不敏感）
**且 UA 必须存在**。同一条 UA 连测 5 次结果完全一致（
[`ua-reproducibility.txt`](evidence/ua-reproducibility.txt)），不是限流噪声。

### 2.3 `Referer` 也仍然是必需的（不是「只要 UA 对就行」）

| 组合（UA=`okhttp/4.12.0`） | HTTP |
|---|---|
| `Referer: https://www.bilibili.com/` | **206** |
| `Referer: https://www.bilibili.com`（无尾斜杠） | 206 |
| `Referer: https://www.bilibili.com/video/BV…` | 206 |
| `Referer: https://music.163.com/` | **403** |
| 无 `Referer`，只有 `Origin: https://www.bilibili.com` | **403** |
| 无 `Referer` | **403** |

⇒ `Referer` 必须**是 B 站域**，且 `Origin` **不能**替代它（v3.1.0 的结论这一半是对的）。

## 3. 服务器响应码与响应体

| 场景 | HTTP | 响应体 |
|---|---|---|
| UA 合格 + Referer 合格 | **206**（`Range: bytes=0-1023`） | `application/octet-stream`，1024 字节 |
| 任一不合格 | **403** | `text/html` 321~322 字节，openresty 的默认错误页：`<html><head><title>403 Forbidden</title>…<hr><center>openresty</center><p>Date: …</p><p>Node_info: 3677-CACHE31</p><p>Hit-status: MISS</p>`（原始件 [`evidence/cdn-403-body.html`](evidence/cdn-403-body.html)） |
| 篡改签名（改 `deadline` 或 `upsig`） | **403** | 同上（**签名校验与 header 闸门是两道独立的闸**，两者都过才给字节） |
| 302 | **未观察到**：B 站媒体 CDN 直接回 206/403，不重定向 | — |

## 4. curl 手动请求：单变量矩阵

原始件 [`evidence/cdn-header-matrix.txt`](evidence/cdn-header-matrix.txt)（音频区直链）与
[`evidence/dash-header-matrix.txt`](evidence/dash-header-matrix.txt)（视频 DASH 音轨）。
两条路径**结论一致**。

| # | 请求头 | 音频直链 | DASH 音轨 |
|---|---|---|---|
| ① | 不带 header（curl 默认 UA） | 403 | 403 |
| ② | 只带 `Referer` | 403 | — |
| ③ | 只带浏览器 UA | — | 403 |
| ④ | 浏览器 UA + `Referer` | **206** | **206** |
| ⑤ | ExoPlayer UA（`ExoPlayerLib/1.2.1 (Linux;Android 13)`） | 403 | — |
| ⑥ | ExoPlayer UA + `Referer` | **403** | **403** |
| ⑦ | BiliDroid APP UA + `Referer` | 403 | — |
| ⑧ | 浏览器 UA + `Referer` 无尾斜杠 | 206 | — |
| ⑨ | 浏览器 UA + 视频页 `Referer` | 206 | — |
| ⑩ | 浏览器 UA + `Referer` + `Origin` | 206 | — |

**注意 ⑥**：这正是 App 修复前的形状（有 Referer、UA 是 Android 系的），
也是 v3.1.0 探针里`with_referer` 那一臂**唯一**成功的原因 —— 那一臂手写的是 `ExoPlayerLib/1.5.0`（无 `Android` 字样）。

## 5. URL 是否过期 / TTL 处理

实测（[`evidence/`](evidence/) 的 `deadline` 反解脚本输出）：

| 量 | 值 |
|---|---|
| 取链时刻 | `1790622902`（2026-09-29 03:15:02） |
| URL 的 `deadline` | `1790630068`（2026-09-29 05:14:28） |
| ⇒ URL 自身 TTL | **7166 s ≈ 1.99 h** |
| 响应体 `timeout` 字段 | **10800 s = 3.0 h** |

两者**不一致**，URL 自带那个更保守。生产代码的处理是**对的**：
`BiliParse.expiryFromUrl`（`BiliModels.kt:247-262`）**优先信 `deadline`**、再减
`SAFETY_MARGIN_MS = 60_000`，`timeout` 只在 `deadline` 缺失时兜底
（`DEFAULT_TTL_MS = 30min`）；`deadlineOf` 还先整体 URL-decode 再按 `&` 切，
以免 `%26` 把分隔符吃掉。TTL 的消费者是预载缓存
（`PreloadCachePolicy`，`now < expiresAtMs` 才算新鲜）。

**结论：TTL 不是本次 P0 的根因**，且现有处理与实测口径一致，本版**不改**。
（如实说明未验证项：**正在播放**中的那条 URL 跨过 `deadline` 之后的行为没有实测到——
需要一首 >2h 不换链的连续播放，本环境做不到；见文末。）

**取链时间与播放时间的差**：App 是「点播放 → 取链 → 立刻交给 ExoPlayer」，
预载路径另有 5 分钟 TTL 缓存 + `expiresAtMs` 双重判据，实测取链耗时 <1s，不存在
「取完放到过期」的窗口。

## 6. 音频流格式 / ExoPlayer 配置

| 问题 | 实测 |
|---|---|
| 编码 | 音频区：**AAC in MP4**（`-320k.m4a`，`type:2`）；视频音轨：`mp4a.40.2`（AAC），DASH `id` 30216/30232/30280 对应 67k/133k/319k |
| 容器 | 音频区 `.m4a`（渐进式 MP4）；视频那一路是 **DASH 分离流**（`.m4s`，fMP4 分片，`baseUrl` 是整条音轨，不是分片列表） |
| 是否 DASH 分离流 | 音频区**不是**；视频区**是**（`fnval=4048` 只取 `dash.audio[]`，丢掉视频轨） |
| ExoPlayer 配置是否匹配 | **匹配**。`DefaultMediaSourceFactory(…)` 对 `.m4a` / `.m4s`（query 里的路径后缀）都推断成 `ProgressiveMediaSource`，`DefaultExtractorsFactory` 含 `Mp4Extractor`（fMP4 可读）。**没有观察到格式层面的失败**——失败一律发生在拿到字节之前（403） |

⇒ **格式不是根因**（这也是「探针先行」的价值：省掉一次无用的转码/换容器改造）。

## 7. 取链参数（`qn` / `sid` / `platform`）

原始矩阵 [`evidence/audio-url-param-matrix.txt`](evidence/audio-url-param-matrix.txt)：

| 请求 | 结果 |
|---|---|
| `songid=39&quality=2&privilege=2&mid=0&platform=pc` | `code:0`，`type:2`（320K），host `upos-sz-mirrorhw.bilivideo.com` |
| `sid=39&quality=2&platform=pc` | **`code:72000000 param missing error: mid`** ⇒ 主键名是 **`songid`**，`sid` 无效 |
| `songid=39&quality=3&…`（请求 FLAC） | `code:0` 但 `type:2`、文件仍是 `-320k.m4a` ⇒ **匿名拿不到 FLAC，服务端静默降级成 320K** |
| `songid=39&quality=0&…` | `type:0`，文件 `-128k.m4a` ⇒ `quality` 真的生效 |
| `songid=39&quality=2&privilege=2&mid=0`（**缺 `platform`**） | **`code:72000000`** ⇒ `platform` 是必需参数 |
| `songid=39&quality=2&privilege=2&mid=0&platform=android` | `code:0`，与 `pc` 同一条 320K 直链 |

⇒ **现有取链参数与实测口径逐字一致**（`songid` + `quality` + `privilege=2` + `mid=0` + `platform=pc`），
`qn` 就是 `quality`（0/1/2/3 → 128K/192K/320K/（FLAC，匿名被降级））。
**参数不是根因，本版不改。**

`BiliQuality.fallbackLadder` 的阶梯（≤4 档、每档只试一次）与「匿名只会拿到 320K」这个实测事实
相容：第一档失败就下探，不会打风暴（铁律 5）。

## 8. 登录态依赖

| 场景 | 实测 |
|---|---|
| 匿名取链 | ✅ `code:0`，直接给 320K |
| 匿名取字节 | ✅ 206（UA/Referer 都合格时） |
| 登录后取链 | **未验证**（本环境没有 B 站账号 Cookie；`BiliSourceProvider.isLoggedIn` 恒为 `false`，v3.1.0 起 B 站就不在登录音源列表里） |
| SESSDATA 是否随媒体请求发送 | **不发送**（`BiliCdn.requestHeaders()` 只有 `Referer`，本版也只加 UA —— 媒体 CDN 不认登录态，多带一次凭据反而是隐私面的净损失） |
| 需要登录的曲目 | 取链会 `code != 0`（如 `-404` 稿件不可见 / `-403` 权限不足），Provider 逐档下探后返回 null，**不自动跳歌**（铁律 21，`ResolveFailure` 只对结构性缺失放行） |

## 9. 与 v3.1.0 的 B 站实现对比：到底漏了什么

| 项 | v3.1.0 | v3.2.4（本版） |
|---|---|---|
| 取链 API 的 UA | ✅ 桌面 UA（`BiliApi.UA`） | 不变 |
| 取链 API 的 Referer | ✅ B 站域 | 不变 |
| 取链参数 | ✅ `songid`/`quality`/`privilege`/`mid`/`platform` | 不变（实测复核过，见 §7） |
| **媒体流的 `Referer`** | ✅ v3.1.0 补上（`BiliCdn` + `ResolvingDataSource`） | 不变 |
| **媒体流的 `User-Agent`** | ❌ **完全没管** —— 交给 media3/`HttpURLConnection` 的默认值 | **本版修**：显式 `setUserAgent` |
| 媒体流 header 的生效范围 | host 白名单（只认 `bilivideo.com` 等） | 白名单 **∪ 取链时见过的 host**（PCDN 第三方域名也能覆盖，见下） |
| URL TTL | ✅ `deadline` 优先 | 不变 |

### 9.1 为什么白名单本身也是一个缺口（本版一并收口）

`BiliCdn.needsReferer` 的 KDoc 自己写着：PCDN 边缘节点用的是**与 B 站无关的第三方域名**
（v3.1.0 实测抓到 `b-…edge.mountaintoys.cn`），而判据是**保守的 host 后缀白名单**，
第三方域名**故意不命中**。于是那些歌连 `Referer` 都拿不到，更不用说 UA。
本版把判据扩成**「白名单 ∪ 本进程从 B 站取链响应里见过的 host」**（有界 LRU），
既覆盖 PCDN，又保证**只对 B 站发给我们的 URL 生效**（网易云/QQ 一个字节都不动）。

## 10. 根因结论与修复方向

| 结论 | 判据 |
|---|---|
| **根因 = 媒体流请求缺少合格 `User-Agent`** | 同一条直链、同一个 Referer：`ExoPlayerLib/1.5.0` → 206；`Dalvik/…Android…`（App 实际发的）→ 403（§2.2） |
| 次要缺口 = `Referer` 注入按 host 白名单，PCDN 第三方域名漏加 | `BiliCdn.kt:82-86` 的判据 + v3.1.0 自己抓到的 PCDN host |
| **不是**根因的（已实测排除） | TTL（§5）、取链参数（§7）、音频/容器格式（§6）、Referer 缺失（v3.1.0 已修）、登录态（§8） |
| 修复方向 | ① `BiliCdn` 增加**合格 UA 常量**并纳入 `requestHeaders()`；② `OfflineAudioCache.dataSourceFactory` 按 URI 选一个 **设置了该 UA 的 `DefaultHttpDataSource.Factory`**（因为 media3 会用 `userAgent` 字段覆盖 `DataSpec` 里的 UA）；③ 判据从「host 白名单」扩成「白名单 ∪ 取链见过的 host」，其余音源**逐字节不变** |

## 11. 未验证项（如实）

1. **登录态下**的 B 站播放未验证（环境无 B 站账号；B 站也不在登录音源列表里）。
2. **PCDN 第三方域名**这一次没有抓到（本次取到的都是 `*.bilivideo.com`）；
   「取链见过的 host」这条兜底是按 v3.1.0 的实测记录写的，本版用单测而不是真机流量钉住它。
3. **>2h 连续播放跨过 `deadline`** 的行为未实测（需要一首 2 小时不断链的播放）。
4. B 站 CDN 的黑名单显然还会变（`curl`/`python` 这些字样今天是封的）；本版的判据是
   「用一个**实测过 206** 的桌面 UA」，而不是「猜一个不会被封的 UA」。
