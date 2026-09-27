# 探针阶段证据（v3.1.0 · probe）

> 采集时间：2026-09-28 02:40–02:52 CST ｜ 代码基线：0b4ed2c ｜ 方式：源码审计 + 实测（无臆测）

本文只收录**本次探针阶段真实执行过的命令、真实看到的输出**，以及**引用自既有证据文件**的原始片段。
每条证据都标注类别：**实测**（有原始输出/响应文件）/ **源码审计**（读代码得到）/
**推断**（由前两者推出，未经运行验证）。**没有任何数字是编的**；没测到的一律写「未实测」。

基线变化说明：本次探针从 `59f3d6c` 开始审计，三条 B 站缺陷被上游采纳并修在 `0b4ed2c`
（02:45:55）。因此本文给出**两次**单测执行的原始输出，正文文档的行号按 `0b4ed2c` 复核。

---

## 1. 本次实际执行的命令与原始输出

### 1.1 基线

```
$ cd /home/duanjb666/deepseek/Ncrust && pwd && git rev-parse --short HEAD && date '+%Y-%m-%d %H:%M:%S %Z'
/home/duanjb666/deepseek/Ncrust
59f3d6c
2026-09-28 02:40:11 CST

$ git status --porcelain | wc -l
0                      # 审计开始时工作区干净（app/ 与 app/src/test/ 无改动）
```

审计结束时（02:46）HEAD 已前进到修复提交：

```
$ git log --oneline -5
0b4ed2c fix(bili): 两处只有实测才暴露的缺陷 —— 歌词取正文、播放地址走旧路径
59f3d6c test(v3.1.0): 并行编排 / URL TTL / Wbi 签名 / 解析 / 三源聚合 + 同步被钉住的计数
3dc482b feat(v3.1.0): 并行预载 + B站音源接入 + 三源聚合搜索
27e064d docs(v3.1.0): 网络调研 + 分段探针（真机 S6 与 API 24 模拟器 A/B）
a86d97b docs(v3.0.0): README 更新到 v3.0.0（并修掉「自动降级」的过期描述）

$ git log -1 --format='%H %ad %s' --date=iso 0b4ed2c
0b4ed2c9ad16047d641bc09ae2a68abdff611e2a 2026-09-28 02:45:55 +0800 fix(bili): 两处只有实测才暴露的缺陷 —— 歌词取正文、播放地址走旧路径
```

`0b4ed2c` 改动范围（`git diff --stat 59f3d6c 0b4ed2c`）：

```
 app/src/main/java/.../bili/BiliApi.kt            | 87 ++++++++++++++++++----
 app/src/main/java/.../bili/BiliModels.kt         | 41 +++++++++-
 app/src/main/java/.../bili/BiliSourceProvider.kt | 16 +++-
 app/src/test/java/.../bili/BiliParseTest.kt      | 37 ++++++++-
 app/src/test/java/.../bili/BiliSourceProviderTest.kt | 23 +++++-
 5 files changed, 184 insertions(+), 20 deletions(-)
```

**工作区状态（02:52 观察；本文所有行号与「未修」判断都以 `0b4ed2c` 为准）**

```
$ git status --porcelain -- app/src
 M app/src/main/java/com/takahashirinta/ncrust/bili/BiliApi.kt
 M app/src/main/java/com/takahashirinta/ncrust/cache/OfflineAudioCache.kt
?? app/src/main/java/com/takahashirinta/ncrust/bili/BiliCdn.kt

$ git diff --stat -- app/src
 .../bili/BiliApi.kt                | 87 +++++++++++++++++++---
 .../cache/OfflineAudioCache.kt     | 36 ++++++++-
 2 files changed, 109 insertions(+), 14 deletions(-)
```

⇒ 有另一个写入者正在实现 §10.4 的 Referer 修复（`BiliCdn.kt` 是新增文件，100 行）。
**本探针没有审计这份草稿**（它未提交、随时可能变），`probe-bili-integration.md` §10.4 的
「未修」只针对 HEAD `0b4ed2c`。落地后需要重跑单测 + 真机播放验证。

### 1.2 单测（`./gw.sh` 在仓库**外**：`/home/duanjb666/deepseek/gw.sh`）

**第一次（`59f3d6c`，02:41–02:42）**

```
$ cd /home/duanjb666/deepseek && ./gw.sh :app:testDebugUnitTest
…
> Task :app:compileDebugKotlin UP-TO-DATE
> Task :app:compileDebugUnitTestKotlin UP-TO-DATE
> Task :app:testDebugUnitTest
…
BUILD SUCCESSFUL in 1m 9s
85 actionable tasks: 6 executed, 79 up-to-date
```

**第二次（`0b4ed2c`，02:46–02:49）**

```
$ cd /home/duanjb666/deepseek && ./gw.sh :app:testDebugUnitTest
w: file:///home/duanjb666/deepseek/Ncrust/app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt:2313:9 Parameter 'load' is never used
…
> Task :app:testDebugUnitTest
w: Detected multiple Kotlin daemon sessions at kotlin/sessions
…
BUILD SUCCESSFUL in 2m 36s
85 actionable tasks: 7 executed, 78 up-to-date
```

两次都**没有** `FAILED` 任务行，退出码 0。

### 1.3 用例数：从 XML 结果统计（Gradle 控制台不打印总数）

```python
# 实际执行的脚本（对 app/build/test-results/testDebugUnitTest/*.xml 求和）
import glob, xml.etree.ElementTree as ET
tot=f=e=s=0
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
    r=ET.parse(p).getroot()
    tot+=int(r.get('tests',0)); f+=int(r.get('failures',0))
    e+=int(r.get('errors',0));  s+=int(r.get('skipped',0))
print(f"suites={len(files)} tests={tot} failures={f} errors={e} skipped={s}")
```

| 运行 | 基线 | 原始输出 |
|---|---|---|
| 第 1 次 | `59f3d6c` | `suites=141 tests=1936 failures=0 errors=0 skipped=0` |
| 第 2 次 | `0b4ed2c` | `suites=141 tests=1940 failures=0 errors=0 skipped=0` |

交叉校验（同一棵树上的静态计数）：

```
$ grep -rn '@Test' --include=*.kt app/src/test | wc -l
1940
$ grep -rn '^class .*Test' --include=*.kt app/src/test | wc -l
141
```

⇒ **最终基线 `0b4ed2c` 上：141 个 suite / 1940 个用例，0 失败 / 0 错误 / 0 跳过。**

> ⚠️ **与任务书数字的差异（不掩盖）**：任务书写「最终 1851 → **1952** 个用例全绿」。
> 我在 `0b4ed2c` 上重复执行并用两种方式（XML 汇总、`@Test` 静态计数）都得到 **1940**，
> **无法复现 1952**（差 12）。也不存在「按参数化展开」把它变多的空间：全仓只有 5 处
> `Parameterized/@RunWith`，且 XML 与注解数已经一致。以实跑为准：**1940**。

### 1.4 新增/相关 suite 的逐类计数（`0b4ed2c` 的 XML）

```
25  com.takahashirinta.ncrust.bili.BiliParseTest
 5  com.takahashirinta.ncrust.bili.BiliTrackMappingTest
11  com.takahashirinta.ncrust.bili.BiliSourceProviderTest
 3  com.takahashirinta.ncrust.bili.BiliSignatureRejectionTest
11  com.takahashirinta.ncrust.bili.BiliWbiTest
 3  com.takahashirinta.ncrust.bili.BiliQualityTest
 6  com.takahashirinta.ncrust.network.BoundedParallelTest
 9  com.takahashirinta.ncrust.player.PreloadCachePolicyTest
19  com.takahashirinta.ncrust.player.PreloadSlotTest
 7  com.takahashirinta.ncrust.ui.components.SourceCountsBiliTest
 4  com.takahashirinta.ncrust.ui.components.SearchRankingThreeSourceTest
 4  com.takahashirinta.ncrust.ui.components.SourceFilterTest
16  com.takahashirinta.ncrust.source.TrackKeyTest
10  com.takahashirinta.ncrust.source.SourceIdDomainTest
29  com.takahashirinta.ncrust.settings.SettingsRegistryTest
 5  com.takahashirinta.ncrust.crosssource.AggregateStringsTest
```

### 1.5 「`PreloadSlot` 一行未改」的 git 证据（**实测**）

```
$ git diff --stat v3.0.0-gpl -- app/src/main/java/com/takahashirinta/ncrust/player/PreloadSlot.kt
（输出为空）

$ git log -1 --format='%h %ad %s' --date=short -- app/src/main/java/com/takahashirinta/ncrust/player/PreloadSlot.kt
63916fc 2026-09-25 fix(player): 跨源切歌不再问错音源，歌词跟着当前音频走
```

⇒ 该文件与 `v3.0.0-gpl` tag **逐字节相同**，最后一次改动早于本版所有提交。

### 1.6 「媒体播放路径没有任何 Referer 注入」的 grep（**源码审计**）

```
$ grep -rln "Referer" --include=*.kt app/src/main/java/
app/src/main/java/com/takahashirinta/ncrust/network/RetrofitClient.kt
app/src/main/java/com/takahashirinta/ncrust/qq/QqClient.kt
app/src/main/java/com/takahashirinta/ncrust/qq/QqQrClient.kt
app/src/main/java/com/takahashirinta/ncrust/bili/BiliApi.kt
$ grep -rn "Referer" --include=*.kt app/src/main/java/ | wc -l
16
```

ExoPlayer 的数据源装配点（`PlaybackService.kt:392`）用的是
`OfflineAudioCache.dataSourceFactory(this)` → `cache/OfflineAudioCache.kt:111-122` 的
`CacheDataSource.Factory().setUpstreamDataSourceFactory(DefaultDataSource.Factory(app))`
—— **没有 `setDefaultRequestProperties`**。

### 1.7 字段实锤：`song/info` 的 `lyric` 是 URL（**引用的既有实测**）

本次没有重跑 curl，读的是既有原始响应文件（`bili-research/evidence/`）：

```
$ for f in 0*-songinfo-*.txt 1[0-2]-songinfo-*.txt; do grep -o '"lyric":"[^"]*"' "$f"; done
02-songinfo-au39.txt      "lyric":"http://i0.hdslb.com/bfs/music/149994607539.lrc"
03-songinfo-au13.txt      "lyric":""
04-songinfo-au15.txt      "lyric":""
05-songinfo-au11624.txt   "lyric":"http://i0.hdslb.com/bfs/music/150813556111624.lrc"
06-songinfo-au17315.txt   "lyric":"http://i0.hdslb.com/bfs/music/151625816017315.lrc"
07..11-songinfo-*.txt     "lyric":""

$ tail -c 120 evidence/21-lyric-au39.txt
{"code":0,"msg":"success","data":"[00:33.26]让我掉下眼泪的\n[00:36.93]不止昨夜的酒\n…"}

$ tail -c 120 evidence/24-lyric-au13.txt
{"code":0,"msg":"success","data":null}
```

---

## 2. 引用自既有证据文件的原始片段（未加工）

### 2.1 并发排队与请求头 A/B（真机 S6；`net-research/EVIDENCE-S6.md`）

`EVIDENCE-S6.md:72-87`（同主机 6 通并发，Dispatcher `maxRequestsPerHost=5`）：

```
PROBE-NET-CONCURRENCY-BEGIN n=6 dispatcherMaxPerHost=5
PROBE-NET-STAT label=netease.concurrent n=6 ok=6 reused=0 total p50=502 p95=526 p99=526 ttfb p50=337 p95=342 p99=342 connect p50=158 tls p50=108 wait p50=168 p95=180 dns p50=1 write p50=3 acquireToReq p50=0
PROBE-NET-CONCURRENCY wall=559ms n=6 maxWait=190
```

`EVIDENCE-S6.md:100-107`（B 站请求头 A/B，403 根因）：

```
PROBE-BILI-HEADER variant=A_netease_referer referer=https://music.163.com/      http=403 total=414 ttfb=163 body=<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
PROBE-BILI-HEADER variant=B_bili_referer    referer=https://www.bilibili.com/   http=200 total=390 ttfb=170 body={"code":-101,"message":"账号未登录",…}
PROBE-BILI-HEADER variant=C_no_referer      （不带 Referer）                     http=200 total=372 ttfb=165 body={"code":-101,…}
```

`EVIDENCE-S6.md:63-68`（P50 汇总；取链/歌词/搜索各自量级）：

```
PROBE-NET-STAT label=netease.search  n=6 ok=6 reused=5 total p50=277 ttfb p50=262 …
PROBE-NET-STAT label=netease.songurl n=6 ok=6 reused=5 total p50=90  ttfb p50=74  …
PROBE-NET-STAT label=netease.lyric   n=6 ok=6 reused=5 total p50=105 ttfb p50=82  …
PROBE-NET-STAT label=bili.audio.info n=6 ok=6 reused=5 total p50=116 ttfb p50=99  …
```

（`bili.audio.info` 的探针 URL 里就是 `sid=22760301`，即 §2.4 D1 的 auid 值域样本。）

### 2.2 音频区没有搜索接口（`bili-research/evidence/`）

```
$ head -14 39-probe-song-search-keyword-x-pn-1-ps-5.txt | tail -3
HTTP/2 404
HTTP=404 time=0.217738s ip=… bytes=1923

$ head -14 44-probe-search-song-keyword-x.txt | tail -3
HTTP/2 404
HTTP=404 time=0.231746s ip=… bytes=1923

$ tail -c 80 45-probe-menu-search-keyword-x.txt
HTTP=200 time=0.209707s ip=… bytes=50
{"code":0,"data":null,"message":"OK","msg":"success"}

$ tail -c 90 47-searchtype-music.txt ; tail -c 90 48-searchtype-audio.txt ; tail -c 90 49-searchtype-foobar.txt
{"code":-1200, "message":"被降级过滤的请求"}
{"code":-1200, "message":"被降级过滤的请求"}
{"code":-1200, "message":"被降级过滤的请求"}
```

⇒ `music` / `audio` 与**故意乱填的 `foobar`** 三者逐字相同 ⇒ 判据是「取值非法」。
（探针脚本 `collect_evidence.sh:105` 的循环里**没有** `au` / `song` —— 见 `probe-bili-integration.md` §7.2。）

### 2.3 音频区四个 qn 全 192K（`evidence/13..16`）

```
$ for f in 13-songurl-web-au39-qn0.txt 14-songurl-web-au39-qn1.txt 15-songurl-web-au39-qn2.txt 16-songurl-web-au39-qn3.txt; do
    grep -o '"type":[0-9-]*,"info":"[^"]*","timeout":[0-9]*,"size":[0-9]*' "$f"; grep -o '\-[0-9]*k\.m4a' "$f" | head -1; done
"type":1,"info":"","timeout":10800,"size":6244629   -192k.m4a
"type":1,"info":"","timeout":10800,"size":6244629   -192k.m4a
"type":1,"info":"","timeout":10800,"size":6244629   -192k.m4a
"type":1,"info":"","timeout":10800,"size":6244629   -192k.m4a
```

### 2.4 CDN Referer（`evidence/80` 与并发写入的 `evidence/96`）

`evidence/80-cdn-referer-and-ttl.txt`（音频区 192K 直链）：

```
--- A) 带 Referer: https://www.bilibili.com/audio/home   HTTP=206 bytes=1024
--- B) 完全不带头                                          HTTP=403 bytes=346
--- C) 带外部 Referer: https://example.com/               HTTP=403 bytes=347
--- D) 只带 UA（无 Referer）                              HTTP=403 bytes=347
--- F) #1 now=1790533776 deadline=1790540976 timeout=10800 deadline-now=7200s
```

`evidence/96-cdn-referer-exoplayer.txt`（2026-09-28 02:47 写入，本探针读取时存在）：

```
# 1) 音频区 CDN（web 端点 192K）      带 B站 Referer : HTTP=206 / 只带 UA/无Ref : 403 / 模拟 ExoPlayer : 403
# 2) 音频区 CDN（APP 端点 320K）      带 B站 Referer : HTTP=206 / 模拟 ExoPlayer : 403
# 3) 视频 DASH 音轨 CDN（legacy playurl + buvid3）  带 B站 Referer : 206 / 只带 UA/无Ref : 403 / 模拟 ExoPlayer : 403
⇒ 不管走哪条路，ExoPlayer 取流都必须带 Referer: https://www.bilibili.com/，否则 403。
```

### 2.5 wbi playurl 路径级封禁与旧路径可用（`evidence/65`、`evidence/90`、`evidence/64`）

```
$ grep -n "player/wbi/playurl" evidence/90-wbi-ab.txt | head -2
  player/wbi/playurl  无签名 : HTTP=412   0.25s  NON-JSON body[0:80]='<!DOCTYPE html>…'
  player/wbi/playurl  有签名 : HTTP=412   0.24s  NON-JSON body[0:80]='<!DOCTYPE html>…'
  ⇒ 两者同为 HTTP 412 ⇒ 该路径对本机出口整体封禁，wbi 不是原因也不是解药

$ sed -n '53,59p' evidence/90-wbi-ab.txt
[4] 可用替代：/x/player/playurl （**非 wbi** 旧路径）→ 200 + DASH
   HTTP=200   0.35s  code=0        message=OK  …
    dash.audio 条数 = 3
      id=30216  bw=43962    codecs=mp4a.40.5    deadline=1790540982 (now+7200s)
      id=30232  bw=102931   codecs=mp4a.40.2    deadline=1790540982 (now+7200s)
      id=30280  bw=203786   codecs=mp4a.40.2    deadline=1790540982 (now+7200s)

$ head -12 evidence/64-playurl-legacy-dash.txt | tail -2
--- url
https://api.bilibili.com/x/player/playurl?bvid=BV1GJ411x7h7&cid=137649199&fnval=4048&fnver=0&fourk=1
# 该次抓取的 cmd 行只有 -H 'User-Agent' 与 -H 'Referer'，没有 Cookie
```

### 2.6 缺签名 = `200 + code:-352`，不是 412（`bili-research/wbi-signature.md`）

`wbi-signature.md:20` / `:190-195` / `:246-260`：

```
| 缺签名会返回 HTTP 412 吗？ | ❌ **不会**。缺签名是 **HTTP 200 + `code:-352`**。HTTP 412 是**另一回事**（路径级封禁，签名也救不了）（§4.3） |
| 无 `w_rid` | 200 | **-352** | 风控拒绝，带 `v_voucher` |
| `w_rid` = 全 0（有 `wts`） | 200 | **-352** | **证明服务端在算 md5** |
| **正确 `w_rid`** | 200 | **0** | ✅ 通过 |
```

---

## 3. 三类证据的边界（明确区分）

| 类别 | 定义 | 本次探针里的例子 |
|---|---|---|
| **实测** | 有原始输出/响应文件，可复现 | 单测执行（§1.2/§1.3，1940 用例）；`git diff` 证明 `PreloadSlot` 与 v3.0.0 相同（§1.5）；`grep` 证明媒体路径无 Referer（§1.6）；引用自 `EVIDENCE-S6.md` 的并发/请求头/P50（§2.1）；`bili-research/evidence/` 的 404 清单、四个 qn、CDN Referer、wbi 412、`-352`（§2.2–§2.6） |
| **源码审计** | 读代码得到，可复核行号 | 四条腿的并发结构（`probe-parallel.md` §1）；并发上限 4 的四处调用点（同文 §5）；预加载的四样内容与落点（`probe-preload-queue.md` §2）；`limitFor` 的三条返回 0（`probe-preload-list.md` §2.1）；`sourceOfId` 判序、`isSignatureRejected` 判据、`SourceCounts.summary` 短路（`probe-bili-integration.md` §2/§9/§5.1） |
| **推断** | 由实测 + 审计推出，**未经运行验证** | 「ExoPlayer 播 B 站会 403」——依据是 `evidence/96` 的模拟 ExoPlayer 403 + §1.6 的无注入事实；「URL 形状的 lyric 会让歌词永远为空」——依据是 `evidence/02` 的 URL 形状 + `LrcParser` 只认 `[mm:ss]` + 旧代码链路；「`-352` 漏判会让搜索空 6 小时」——依据是 `wbi-signature.md` 的 `-352` + `WBI_KEY_TTL_MS = 6h` |

**运行环境边界**（照抄 `net-research/net-latency-breakdown.md` §0 的诚实边界）：

- S6 探针请求是**匿名**（无 MUSIC_U）；带登录态的耗时可能不同。
- 移动数据 / 弱网 / 跨运营商**未采集**（S6 是 WiFi）。
- `bili-research` 的抓取是**直连**（`env -u https_proxy -u http_proxy`）、匿名、Chrome120 UA；
  代理环境与登录态下的 B 站行为**可能不同**（`wbi-signature.md` §4.2 明确说 wbi 的强制程度取决于 IP 风险评级）。
- 本探针阶段的单测是 **JVM 单测**，不含仪器化测试、不含真机播放。

---

## 4. 复现清单（一条命令对应一个结论）

| 结论 | 复现命令 |
|---|---|
| 单测全绿 / 用例数 | `cd /home/duanjb666/deepseek && ./gw.sh :app:testDebugUnitTest`，再按 §1.3 的脚本汇总 XML |
| `PreloadSlot` 一行未改 | `git diff --stat v3.0.0-gpl -- app/src/main/java/com/takahashirinta/ncrust/player/PreloadSlot.kt` |
| 媒体路径无 Referer | `grep -rln "Referer" --include=*.kt app/src/main/java/`（应只出 4 个 HTTP 客户端文件） |
| `song/info.lyric` 是 URL | `curl -s 'https://www.bilibili.com/audio/music-service-c/web/song/info?sid=39' -H 'Referer: https://www.bilibili.com/audio/home' \| grep -o '"lyric":"[^"]*"'` |
| `/song/lyric` 给正文 | `curl -s 'https://www.bilibili.com/audio/music-service-c/web/song/lyric?sid=39' -H 'Referer: https://www.bilibili.com/audio/home' \| head -c 200` |
| 音频区无搜索接口（404 清单） | `bash docs/verification/v3.1.0/bili-research/collect_evidence.sh`（产出 `evidence/*.txt`，含 38–45） |
| `search_type` 非法取值 | 同上（46–55）；缺 `au`/`song` 时把两个值加进脚本 :105 的循环重跑 |
| 四个 qn 全 192K | 同上（13–16） |
| CDN Referer 403 | `curl -sI -r 0-1023 '<CDN 直链>'`（带/不带 `Referer` 各一次）；或读 `evidence/80`、`evidence/96` |
| wbi 412 / 旧路径可用 | `python3 docs/verification/v3.1.0/bili-research/wbi_ab.py`（产出 `evidence/90-wbi-ab.txt`） |
| 缺签名 = `-352` | `curl -s 'https://api.bilibili.com/x/web-interface/wbi/search/type?search_type=video&keyword=test&page=1'` |
| Wbi 签名向量 | `python3 docs/verification/v3.1.0/bili-research/wbi_golden.py --live`（产出 `evidence/91`） |
| 并行/预加载的端到端耗时 | **未实测**：需要真机 + 仪器化探针（`app/src/androidTest/.../probe/NetTimingProbeTest.kt`），本阶段没有设备在环 |
