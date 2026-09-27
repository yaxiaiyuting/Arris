# B站音频区（au）API 全集 —— Ncrust v3.1.0 接入调研

> **实测时间**：2026-09-28 02:2x–02:3x（+0800），epoch ≈ 1790533200–1790533800
> **出口**：**直连**（`env -u https_proxy -u http_proxy`，本机环境变量里默认有 `http_proxy=https_proxy=http://127.0.0.1:10808`，本文所有数据均为**直连**结果；仅 `community-implementations.md` 里的 GitHub API 调用走了代理）
> **身份**：**完全匿名**，不带任何 Cookie、不带 SESSDATA
> **可复现**：`bash collect_evidence.sh` → `./evidence/*.txt`（68 个原始响应文件）
> **UA**：Chrome 120 desktop（`Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/120.0.0.0 Safari/537.36`）
> **Referer**：`https://www.bilibili.com/audio/home`

---

## 0. 结论先行（TL;DR）

| 问题 | 实测结论 | 证据 |
|---|---|---|
| 音频区还能用吗？ | **能播，但"区"已经名存实亡** —— 播放/歌词/歌单元数据全在，**发现与搜索类接口大面积下线** | 第 6 节 |
| 有独立搜索接口吗？ | **没有**。`song/search`、`search/song`、`search`、`search/type`、`song/list`、`song/query`、`searchByKey` **全部 HTTP 404**；`menu/search` 是空壳（`code:0, data:null`） | §6.1 |
| 通用搜索里有音频分区吗？ | **没有**。`search/all/v2` 的 `pageinfo` key 与 `result_type` 枚举中**均无 audio/music**；`search_type=music` / `search_type=audio` 与 `search_type=foobar` 返回同一个错误 `-1200 被降级过滤的请求` ⇒ **取值非法** | §6.2 |
| 能匿名听吗？ | **能，而且不止 192K** —— web 端点只给 192K，但 **APP 端点 `/audio/music-service-c/url` 匿名直出 320K**（ffprobe 实测 321584 bps） | §3 |
| FLAC / 无损？ | **匿名拿不到**：`quality=3` 被静默降级成 320K（`type:2`），且 `qualities[]` 里根本没有 `type:3` 条目 | §3.4 |
| URL 有效期？ | 响应里 **`timeout: 10800`（3h，名义值）**，但 URL 查询串里的 **`deadline = now + 7200s`（恰好 2 小时，实测值）** —— **两者不一致，必须以 `deadline` 为准** | §4.2 |
| URL 能裸取吗？ | **不能**。CDN 强校验 Referer：带 `bilibili.com` Referer → `206`；不带 / 带外部 Referer → **`403`** | §4.4 |
| 视频 DASH 备选可行吗？ | **可行**，但必须走**旧的非 wbi 路径** `/x/player/playurl`（`fnval=4048`）—— `/x/player/wbi/playurl` 对本机出口**整体 412**，加签名也无效 | §7 |

---

## 1. 域名与路径总览

音频区 API 有两套**完全等价**的 host（实测响应逐字节一致，仅 CDN 出口 IP 不同）：

```
https://www.bilibili.com/audio/music-service-c/web/...   ← 文档与社区常用（web 端点）
https://api.bilibili.com/audio/music-service-c/web/...   ← 同一后端，可用于统一 baseUrl
```

此外还有一个**语义不同**的 APP 端点（路径里**没有 `/web`**）：

```
https://api.bilibili.com/audio/music-service-c/url       ← APP 端点，参数名是 songid，能出 320K
```

> ⚠️ **`/web/url` 与 `/url` 不是同一个接口**：参数名不同（`sid` vs `songid`）、返回的 `qualities[]` 不同（前者恒为 `null`，后者是完整音质表）、最高音质不同（192K vs 320K）。这是本次调研最重要的发现之一，见 §3。

---

## 2. 歌曲信息 `song/info`

```
GET https://www.bilibili.com/audio/music-service-c/web/song/info?sid=<auid>
```

**实测**（au39，`evidence/02-songinfo-au39.txt`）：

```json
{"code":0,"data":{
  "id":39,"uid":14452610,"uname":"Roro_Zhang","author":"Roro_Zhang",
  "title":"成都（Cover赵雷）",
  "cover":"http://i0.hdslb.com/bfs/music/a5a79465d73c53606bacca073c33c3bc2822b2ae.jpg",
  "intro":"粉丝群：568439813",
  "lyric":"http://i0.hdslb.com/bfs/music/149994607539.lrc",
  "crtype":1,"duration":258,"passtime":1502179114,"curtime":1790533743,
  "aid":7361800,"bvid":"BV18s411Y7Wu","cid":12035021,"msid":0,
  "attr":0,"limit":0,"activityId":0,"limitdesc":"","coin_num":7,
  "ctime":1499945799000,
  "statistic":{"sid":39,"play":2981,"collect":96,"comment":39,"share":0},
  "vipInfo":{"type":2,"status":1,"due_date":1835625600000,"vip_pay_type":1},
  "collectIds":[],"is_cooper":0},
 "message":"OK","msg":"success"}
```

### 2.1 字段语义（逐条实测确认）

| 字段 | 类型 | 实测结论 | 备注 |
|---|---|---|---|
| `id` | num | = 请求的 `sid`（auid 的**裸数字**，不带 `au` 前缀） | 对应 `TrackKey.id` |
| `uid` / `uname` | num/str | UP 主 mid / 昵称 | `uname` 是账号名 |
| `author` | str | **展示用作者名**，可能含多人（`"洛天依 · 言和 · JUSF周存"`）或与 `uname` 不同（au4059094 的 `author` 是 `ClessS`、`uname` 是 `辣椒少吃伤胃`） | 建议取 `author`，空则回落 `uname` |
| `title` | str | 标题 | |
| `cover` | str | **http（非 https）** `i0.hdslb.com/bfs/music/*.jpg` | ⚠️ 明文 http，Android 9+ 默认禁明文，需走 `https:` 改写或 `CoverUrls` 同款处理 |
| `intro` | str | 简介，含 `\n` | 可取作专辑名回退 |
| `lyric` | str | **LRC 文件的 URL**（不是歌词正文）；**可能为空串 `""`** | 与 `song/lyric` 端点二选一，见 §5 |
| `crtype` | num | **1 = 自制/原创，2 = 转载**。**这是最关键的分支字段** | 见 §2.3 |
| `duration` | num | **秒**（au39 = 258 → 4:18；au13 = 169；au127015 = 34） | 与 ffprobe 实测 `duration=258.085` 吻合 |
| `aid` / `bvid` / `cid` | num/str/num | 关联的**视频** id。`crtype=1` 通常三者齐全；`crtype=2` 常见 `aid=0, bvid="", cid=0` | 可用于"看视频"入口 / 视频 DASH 回退 |
| `msid` | num | 音乐素材 id。`crtype=2` 常见非 0（au127015 = 332082） | **与可播性相关**，见 §2.3 |
| `statistic` | obj | `{sid, play, collect, comment, share}` 四个计数 | 与 `stat/song` 端点返回**逐字节相同** |
| `vipInfo` | obj\|null | `{type, status, due_date, vip_pay_type}`；**可能为 `null`**（au127015、au4059094） | 语义未确证，见 §3.5 |
| `attr` / `limit` / `limitdesc` / `activityId` | num/str | 匿名实测恒为 `0/0/""/0` | 付费/限免标记位，未能触发非 0 样本 |
| `coin_num` | num | 投币数 | |
| `ctime` | num | **毫秒**（`1499945799000`）；对照 `passtime=149994607539` 附近但不相等 | ⚠️ 与 `curtime`/`passtime`（秒）**单位不同** |
| `collectIds` | arr | 匿名恒为 `[]` | 登录后是「我创建的包含此曲的歌单 id」 |
| `is_cooper` | num | 合作投稿标记 | |

**不存在**该端点：不存在的 sid 返回 **HTTP 200 + `code:4511001`**（"音频未找到或已下架"），**不是 HTTP 404**：

```json
{"code":4511001,"data":null,"message":"音频未找到或已下架","msg":"音频未找到或已下架"}
```
（`evidence/12-songinfo-au999999999.txt`；`sid=1` 同样 4511001，说明 **auid 从 13 起才有数据**，编号 1–12 空缺）

### 2.2 auid 的取值分布（本次实测样本）

通过对数区间随机抽样确认音频 id 空间真实存在（不是文档里的示例号）：

| 区间 | 抽样命中 | 举例 |
|---|---|---|
| 1–200 | ✅ | au13 / au15 / au39 / au94 / au102 / au138（2017 年上传） |
| 1e4–3e4 | ✅ | au11526 / au11624 / au12027 / au14056 / au17244 / au17315 |
| 1e5–3e5 | ✅ | au127015 / au130878 / au137815 |
| 1e6–3e6 | ✅ | au1124992 / au1131678 / au1393994 / au1780974 / au2298157 / au2339898 / au2493404 |
| 4e6–5e6 | ✅ | au4059094 等（2023 年上传，文件名形如 `n230903a...`） |
| ≥ 8e6 | ❌ 抽样 12 个全 miss | 本次样本未命中，**不能据此断定上界** |

> **实践含义**：auid 是稀疏的稀疏大整数空间，**不存在"从 1 遍历"的可行性**，必须有 id 来源（歌单 / 榜单 / 用户输入 / 站外链接解析）。

### 2.3 ⚠️ `crtype=2`（转载）音频的可播性缺陷

实测 6 个 `crtype=2` 音频，**2 个拿不到流地址**：

| auid | crtype | msid | `song/url` 结果 |
|---|---|---|---|
| au127015 | 2 | 332082 | **`code:0` 但 `cdns:null`** ⇒ 无流可播 |
| au130878 | 2 | 268963 | **`code:0` 但 `cdns:null`** ⇒ 无流可播 |
| au1124992 | 2 | 0 | ✅ 正常 `cdns[]` |
| au1780974 | 2 | 0 | ✅ 正常 `cdns[]` |
| au2339898 | 2 | 0 | ✅ 正常 `cdns[]` |
| au2493404 | 2 | 0 | ✅ 正常 `cdns[]` |

**规律**：失败的 2 个都是 `crtype=2 且 msid != 0`（即"从视频转载且已关联音乐素材库"）。**但样本只有 2 个，不足以定论** —— 归入"未验证项"，接入时必须**把 `code:0 + cdns:null/[]` 当作"不可播"，而不是当作成功**。

---

## 3. 音频流 URL —— **两个端点，能力不同**（本节最重要）

### 3.1 端点对照

| | web 端点 | APP 端点 |
|---|---|---|
| URL | `https://www.bilibili.com/audio/music-service-c/web/url` | `https://api.bilibili.com/audio/music-service-c/url` |
| 主键参数 | **`sid`** | **`songid`** |
| 其它参数 | `quality`, `privilege=2`, `mid=0`, `platform=web` | `quality`, `privilege=2`, `mid=0`, `platform=pc` |
| 匿名最高音质 | **192K**（`type:1`） | **320K**（`type:2`） |
| `quality` 参数是否生效 | ❌ **完全被忽略**（qn 0/1/2/3/4 返回同一个 `-192k.m4a`，`size` 全部相同） | ✅ **生效**（qn 0→128k，1→192k，2/3→320k） |
| `qualities[]` | **恒为 `null`** | **完整音质表**（含 `desc/bps/tag/require`） |
| 缺参报错 | — | `{"code":72000000,"msg":"param missing error: songid"}` ← 说明参数名确实是 `songid` |

### 3.2 web 端点实测（`evidence/13..16-songurl-web-au39-qn*.txt`）

| qn | `type` | `size` | 实际文件名 |
|---|---|---|---|
| 0 | 1 | 6244629 | `ef0083e1...-192k.m4a` |
| 1 | 1 | 6244629 | 同上 |
| 2 | 1 | 6244629 | 同上 |
| 3 | 1 | 6244629 | 同上 |

⇒ **`quality` 参数在 web 端点上没有任何作用**，匿名恒为 192K。

### 3.3 APP 端点实测（`evidence/17..20-songurl-app-au39-qn*.txt`）

```jsonc
// GET /audio/music-service-c/url?songid=39&quality=2&privilege=2&mid=0&platform=pc
{"code":0,"msg":"success","data":{
  "sid":39,"type":2,"info":"","timeout":10800,"size":10374528,
  "cdns":["https://upos-sz-mirrorhw.bilivideo.com/ugaxcode/ef0083e1cd13e73dcd20bfe3e672c21a-320k.m4a?e=...&deadline=1790540893&...&mid=0&platform=pc&...&upsig=88b59c54f8f1caa547d8531d619644f7&..."],
  "qualities":[
    {"type":2,"desc":"高品质","size":10374528,"bps":"320kbit/s","tag":"HQ","require":0,"requiredesc":""},
    {"type":1,"desc":"标准",  "size":6244629, "bps":"192kbit/s","tag":"",  "require":0,"requiredesc":""},
    {"type":0,"desc":"流畅",  "size":4179753, "bps":"128kbit/s","tag":"",  "require":0,"requiredesc":""}
  ]}}
```

**qn 全扫（匿名）**：

| `quality` | 返回 `type` | `size` | 文件名后缀 |
|---|---|---|---|
| 0 | 0 | 4179753 | `-128k.m4a` |
| 1 | 1 | 6244629 | `-192k.m4a` |
| 2 | **2** | 10374528 | `-320k.m4a` |
| 3 (FLAC) | **2（降级！）** | 10374528 | `-320k.m4a` |

### 3.4 音质映射与匿名边界（**实测，非文档推断**）

| qn | 文档说法 | **匿名实测（APP 端点）** | `require` 字段 | 结论 |
|---|---|---|---|---|
| 0 | 流畅 128K | ✅ 128K 真实到手 | 0 | **匿名可用** |
| 1 | 标准 192K | ✅ 192K 真实到手 | 0 | **匿名可用** |
| 2 | 高品质 320K | ✅ **320K 真实到手**（未登录！） | 0 | **匿名可用** |
| 3 | 无损 FLAC（大会员） | ❌ **静默降级为 320K** | 表中**无 type:3 条目** | **匿名不可用** |

**320K 是"真的" 320K —— 用 ffprobe 解码验证过**（`evidence/80-cdn-referer-and-ttl.txt` G 段）：

```
HTTP=200 bytes=10374528
/tmp/.ev320.m4a: ISO Media, Apple iTunes ALAC/AAC-LC (.M4A) Audio
duration=258.085000
bit_rate=321584          ← 321.6 kbps，确实是 320K 档
```

对照 192K：`duration=258.085000 / bit_rate=193568`。

> **这是本次调研对音质方案影响最大的一条**：社区普遍认为"B站音频匿名只能 192K"（`bilibili-API-collect` 的 `musicstream_url.md` 明确写"本接口仅能获取192K音质的音频"）—— 那句话**只对 web 端点成立**。换成 APP 端点，**匿名就是 320K**。
>
> ⚠️ **未验证**：`require` 全 0 只说明**服务端认为这三档不需要会员**，不代表 320K 授权永久稳定。接入时应**每首歌都读 `qualities[]` 而不是硬编码最高档**，并在 `type < 期望` 时按降级处理（见 RECOMMENDATIONS）。

### 3.5 付费/大会员音频 —— **未验证**

文档称"web 端无法播放完整付费歌曲，付费歌曲为 30s 试听片段"（`type:-1`）。本次抽样 **23 首音频全部 `type:1/2`（完整曲），从未观察到 `type:-1`**。

抽样里确实包含 `vipInfo.status=1` 的样本（au4055594 `type:2,status:1`、au39 `vip_pay_type:1`），**均返回完整文件**（au4055594 实际下载 2628821 字节 / 108s ≈ 195kbps，完整曲长）。

⇒ **"30 秒试听"这一行为本次未能复现**。`vipInfo` 更像是"上传者是否为大会员"而非"此曲是否收费"。归入**未验证项**：接入后遇到 `type:-1` 必须按"试听片段"降级处理（此时 `size=0`，见文档）。

### 3.6 ⚠️ `size` 字段在新上传的音频上**不可信**

| auid | 上传年份（文件名特征） | `size` 字段 | **HTTP 实体实际字节数** |
|---|---|---|---|
| au39 | 2017（`ef0083e1…` 纯 hash） | 6244629 | 6244629 ✅ 一致 |
| au4059094 | 2023（`n230903a22zx…`） | **146** | **5093773** ❌ |
| au4055594 | 2023 | **150** | **2628821** ❌ |
| au4056879 | 2023 | **146** | **144160817** ❌ |

`qualities[].size` 同样是 146/150（三个音质档全是同一个数）。**该字段在 2023 年后的投稿上语义已变（或损坏）。**

⇒ **Ncrust 绝不能用 `size` 做「文件大小 / 缓冲进度 / 是否完整」的判断**，需要长度就用 HTTP `Content-Length`（CDN 正常返回，见 §4.4）。

---

## 4. 音频流 URL 的行为细节

### 4.1 返回结构

| 字段 | 类型 | 实测 |
|---|---|---|
| `sid` | num | auid |
| `title` / `cover` | str | **实测恒为空串 `""`**（两版文档说 `null`）—— 不要指望从这里拿元数据，回 `song/info` |
| `type` | num | 音质标识。`-1` 试听 / `0` 128K / `1` 192K / `2` 320K（**本次实测未见 `-1` 与 `3`**） |
| `info` | str | 恒 `""`（文档："作用尚不明确"） |
| `timeout` | num | **10800**（秒）= 3 小时，**每次请求都是这个常数** |
| `size` | num | 见 §3.6，**不可信** |
| `cdns` | array | 流 URL 列表。**实测长度恒为 1**（文档说 0=主 1=备用，本次 23 首全部只有 1 条；`crtype=2+msid!=0` 时为 **`null`**） |
| `qualities` | array\|null | web 端点恒 `null`；APP 端点完整 |

### 4.2 ⚠️ `timeout`(10800) 与 URL `deadline`(+7200) **不一致** —— 必须以 `deadline` 为准

实测（`evidence/80-...txt` F 段，连测 3 次）：

```
  #1 now=1790533776 deadline=1790540976 timeout=10800 deadline-now=7200s
  #2 now=1790533776 deadline=1790540976 timeout=10800 deadline-now=7200s
  #3 now=1790533776 deadline=1790540977 timeout=10800 deadline-now=7201s
```

- 响应体 `timeout` = **10800 s（3 h）** —— 这是个**名义常量**，不随请求变化，也不是 URL 的真实寿命
- URL 查询串 `deadline` = **`now + 7200 s`（恰好 2 h）** —— 实测三次全部 7200（±1 秒舍入）

**同一现象在视频 DASH 上也成立**：`/x/player/playurl` 返回的 3 条 audio 流的 `deadline` 同样是 `now+7200s`（`evidence/90-wbi-ab.txt` §[4]）。**说明 `deadline` 是 B站全站的统一签名有效期：2 小时。**

> 「社区共识是 3h」的来源：`bilibili-API-collect` 的 `musicstream_url.md` 写「`timeout` 有效时长，单位为秒，**一般为 3h**」。这个描述**读的是 `timeout` 字段**，而 `timeout` 恰好不是真实过期时间。本次实测把这个流传已久的说法纠正为：**名义 3h / 实际 2h**。

### 4.3 URL 每次都不同（不可跨会话复用）

连测 3 次同一 sid，`deadline` 相同但 URL 不同：

```
url_md5=b8ab609f88b5 / 8c49bef70e6a / 0086ecd5b33e    ← 三次全不同
```

差异来自 `trid`（每次随机）与 `upsig`（对 query 的签名）。**⇒ 缓存 URL 只在剩余 TTL 内有效，重启后必须重取。**

### 4.4 CDN 强校验 Referer（**ExoPlayer 必须带**）

`evidence/80-...txt` A–E 段：

| 请求头 | 结果 |
|---|---|
| `Referer: https://www.bilibili.com/audio/home` | **`HTTP/2 206`**，`content-length: 1024` ✅ |
| 完全不带头 | **`HTTP 403`**（347 字节 HTML）❌ |
| `Referer: https://example.com/` | **`HTTP 403`** ❌ |
| 只带 UA、无 Referer | **`HTTP 403`** ❌ |

Range 请求响应头（ExoPlayer 需要）：

```
HTTP/2 206
content-type: application/octet-stream
content-length: 1024
content-range: bytes 0-1023/6244629
access-control-expose-headers: Content-Length,Content-Range,x-service-module
server: openresty
```

⇒ **`content-range` 里的总长度才是可信的文件大小**（6244629，与 192K 的 `size` 一致、与 320K 的新歌 `size` 不一致）。
⇒ `content-type` 是 `application/octet-stream`（不是 `audio/mp4`），ExoPlayer 的 `DefaultExtractorsFactory` 靠**嗅探**识别 M4A，实测文件魔数 `ftypM4A` ✅ 可正常识别。

---

## 5. 歌词

**两条路，内容一致**（实测同一首歌两条路返回同一份 LRC）：

### 5.1 `song/lyric` 端点（推荐）

```
GET https://www.bilibili.com/audio/music-service-c/web/song/lyric?sid=<auid>
```

**实测 au39**（`evidence/21-lyric-au39.txt`）—— `data` 是**字符串**（不是对象！）：

```json
{"code":0,"msg":"success","data":"[00:33.26]让我掉下眼泪的\n[00:36.93]不止昨夜的酒\n[00:40.71]让我依依不舍的\n[00:44.47]不止你的温柔\n[00:48.82]余路还要走多久\n[00:52.88]你攥着我的手\n[00:56.63]让我感到为难的\n[01:00.43]是挣扎的自由\n[01:06.45][02:44.32]分别总是在九月\n..."}
```

- **格式**：标准 LRC，`[MM:SS.mm]`（**两位**百分秒，不是三位毫秒），一行可带**多个时间戳**（`[01:06.45][02:44.32]` 重复段落）
- **可直接喂给 Ncrust 现有的 `LrcParser`**（`[MM:SS.mm]` 正是它支持的形状）
- 无歌词时：`{"code":0,"msg":"success","data":null}`（au13 实测）—— **`code` 仍是 0**，靠 `data == null` 判空
- **不需要登录**

### 5.2 `song/info` 的 `lyric` 字段（URL）

`song/info` 里 `lyric` 是 `.lrc` 文件的 URL，实测内容与 `song/lyric` **逐行相同**：

```
http://i0.hdslb.com/bfs/music/149994607539.lrc
→ [00:33.26]让我掉下眼泪的
  [00:36.93]不止昨夜的酒
  ...
```

- **注意也是 http（非 https）**
- **可能为空串**（au13、au4059094 等为 `""`，此时 `song/lyric` 也返回 `null`）
- 覆盖情况：本次抽样 23 首里约一半有歌词

⇒ **推荐用 `song/lyric`**（少一次请求、无 http 混合内容问题），`info.lyric` 仅作兜底/交叉校验。

### 5.3 只有 LRC，**没有逐字/翻译**

音频区的歌词是**纯行级 LRC**，实测响应里**没有** `yrc` / `tlyric` / `romalrc` / TTML 之类字段。Ncrust 的逐字歌词与 TTML 能力在 B站音源上**不可用**（B站视频字幕是另一套 `x/player/wbi/v2` 的 `subtitle`，且音频区歌曲不保证有关联视频）。

---

## 6. 浏览、发现与搜索 —— **音频区的最大短板**

### 6.1 ❌ 没有独立搜索接口（**穷举实测**）

`evidence/38..45-probe-*.txt`：

| 候选端点 | 结果 |
|---|---|
| `/web/song/search?keyword=…` | **HTTP 404** |
| `/web/search/song?keyword=…` | **HTTP 404** |
| `/web/search?keyword=…` | **HTTP 404** |
| `/web/search/type?keyword=…` | **HTTP 404** |
| `/web/song/list` | **HTTP 404** |
| `/web/song/searchByKey` | **HTTP 404** |
| `/web/song/query` | **HTTP 404** |
| `/web/menu/search?keyword=…` | **HTTP 200 但是空壳**：`{"code":0,"data":null,"message":"OK"}` |
| `app.bilibili.com/x/v2/search/type?search_type=audio` | `{"code":-400}` |
| `api.bilibili.com/x/v2/search/type?search_type=audio` | `{"code":-400}` |

⇒ **音频区没有任何可用的搜索接口。** `bilibili-API-collect` 的 `docs/audio/` 里也**从未记录过**搜索端点 —— 文档与实测一致。

### 6.2 ❌ 通用搜索里也没有音频分区

`search/all/v2` 的 `pageinfo` key（`evidence/57-search-all-v2.txt`）：

```
activity, article, bangumi, bili_user, clustering_card, live, live_all, live_master,
live_room, live_user, media_bangumi, media_ft, movie, operation_card, pgc,
related_search, special, topic, tv, upuser, user, video
```

实际返回的 `result_type` 取值：

```
tips, brand_ad, esports, activity, web_game, card, media_bangumi,
media_ft, bili_user, user, star, video
```

**两者都没有 audio / music。**

### 6.3 ⚠️ `search_type` 合法取值（澄清任务书里的疑问）

`evidence/46..55-searchtype-*.txt`，`/x/web-interface/wbi/search/type?search_type=<X>&keyword=test&page=1`：

| `search_type` | 实测结果 | 合法？ |
|---|---|---|
| `video` | `code:0` + 完整结果 | ✅ |
| `media_bangumi` | `code:0` + 结果 | ✅ |
| `bili_user` | `code:0` + 结果 | ✅ |
| `live_room` | `code:0` + 结果 | ✅ |
| `article` | `code:0` + 结果 | ✅ |
| `topic` | `code:0` + 结果 | ✅ |
| `photo` | `code:0` + 结果 | ✅ |
| **`music`** | **`{"code":-1200, "message":"被降级过滤的请求"}`** | ❌ **不存在** |
| **`audio`** | **`{"code":-1200, "message":"被降级过滤的请求"}`** | ❌ **不存在** |
| **`foobar`**（故意乱填的对照） | **`{"code":-1200, "message":"被降级过滤的请求"}`** | ❌ |

**判据**：`music` / `audio` 与**故意乱填的 `foobar`** 返回**完全相同**的错误码与文案 ⇒ 它们都是"非法 `search_type`"，**不是"被风控拦截"**。
（`-1200` 这个码容易误读成"请求被降级"，其实是本站对**不认识的 search_type** 的统一回复。）

> ⚠️ **注意**：`-1200` 也会由**越界翻页**触发（`page=50&page_size=50` 超过 `numResults=1000` 的上限时同样返回 `-1200`）。所以「拿到 -1200」= 请求参数非法，**不等于** wbi 签名有问题。

### 6.4 ✅ `video` + `tids=3`（音乐区）是可行的替代搜索

```
GET /x/web-interface/wbi/search/type?search_type=video&keyword=周杰伦&page=1&tids=3
```
实测 `code:0`，返回音乐区（`typename:"音乐综合"` 等）视频，字段含 `bvid / aid / title / author / mid / typeid / typename / arcurl / description / duration / pic`（`evidence/56-search-video-tids3.txt`）。

**但这是视频，不是音频区 au** —— 拿到的 id 是 `bvid`，播放要走视频 DASH（§7），与 au 是**两条不同的管线**。

### 6.5 ✅ 仍然存活的"发现"接口（音频区内）

| 端点 | 实测 | 用途 |
|---|---|---|
| `/web/menu/rank?pn=1&ps=20` | **`code:0`，12 个榜单** | **最有价值的发现源**：热歌榜(10627)、原创榜(10628)、中文/日文/VOCALOID/古风/鬼畜/纯音乐/有声/ACG/英文 三日榜 |
| `/web/menu/hit?pn=1&ps=20` | **`code:0`，11 个热门歌单** | 人工精选歌单 |
| `/web/menu/info?sid=<amid>` | **`code:0`**，含 `menuId/title/uname/intro/snum/type/statistic` | 歌单元数据 |
| `/web/song/of-menu?sid=<amid>&pn=&ps=` | **`code:0`**，`{curPage,pageCount,totalSize,pageSize,data[]}` | **歌单内歌曲列表** —— auid 的主要来源 |
| `/web/tag/song?sid=<auid>` | **`code:0`**，返回 `[{type,subtype,key,info}]` 标签（音乐/人声/翻唱/汉语/网络歌曲/民谣） | 分类标签 |
| `/web/member/song?sid=<auid>` | **`code:0`**，`[{type,list:[{mid,member_id,name}]}]` 创作成员（词/曲/编曲/混音…） | 元数据增强 |
| `/web/stat/song?sid=<auid>` | **`code:0`**，与 `info.statistic` 相同 | 播放/收藏数 |

**实测榜单原样**（`evidence/26-menu-rank.txt`，截取）：

```json
{"code":0,"data":{"curPage":1,"pageCount":2,"totalSize":12,"pageSize":20,"data":[
 {"menuId":10624,"title":"新曲推荐","uname":"大家的音乐机","type":2,"statistic":{"sid":10624,"play":0,...}},
 {"menuId":10627,"title":"热歌榜（每日11:00更新）","type":2,...},
 {"menuId":10628,"title":"原创榜（每日11:00更新）","type":2,...},
 {"menuId":10629,"title":"中文人声三日榜","type":2,...},
 {"menuId":10634,"title":"纯音乐/演奏三日榜","type":2,...},
 ...]}}
```

**实测歌单曲目**（`evidence/28-song-of-menu-10624.txt`）—— 每首含 `id/title/author/duration/lyric/bvid/aid/cid/msid/crtype`，**可直接构造 Ncrust 的曲目对象**：

```json
{"id":2445151,"title":"【洛天依原创】双星伴生","author":"洛天依 · 言和 · JUSF周存",
 "duration":197,"lyric":"","bvid":"BV1bU4y1n7Nx","aid":674294080,"cid":0,"msid":0,"crtype":1}
```

### 6.6 ❌ 已下线的音频区接口（HTTP 404）

`tag/list`、`tag/type`、`song/new`、`song/recommend`、`song/hot`、`rank/list`、`home`、`song/search`、`collections/list`（此项是 404 或需登录，见 §6.7）、`up/list`、`song/music/list`。

### 6.7 🔒 需要登录的音频区接口（`code:4511003 用户未登录`）

| 端点 | 匿名返回 |
|---|---|
| `/web/collections/list?uid=` | `{"code":4511003,"data":null,"message":"用户未登录"}` |
| `/web/collections/info?sid=` | 同上 |
| `/web/collections/songs-coll?sid=` | 同上 |
| `/web/coin/audio?sid=` | 同上 |
| `/web/coin/add`（投币，POST） | 需登录 + CSRF |

⇒ **"我的收藏夹/默认歌单"整条线在匿名下不可用**。

### 6.8 🆕 站内"音乐推广/新歌"体系（**不属于音频区**，但可作发现源）

任务书未提及、本次实测新发现的**匿名可用**端点：

```
GET https://api.bilibili.com/x/centralization/interface/new/music?plat=2&web_location=333.1351
```

实测 `code:0`（`evidence/59-newmusic-centralization.txt`）：

```json
{"code":0,"message":"0","ttl":1,"data":{"list":[
 {"id":30855,"music_id":"MA637817956440498033","music_title":"new trick",
  "publish_time":"9-17","wish_count":878,"author":"ROSÉ","album":"new trick",
  "aid":"117290355984421","cid":"0","bvid":"BV1ghe26METF","total_vv":1251103,
  "cover":"https://i0.hdslb.com/bfs/station_src/music_metadata/320d0b666d82f295199e401dd06cc983.jpg",
  "rank":999,...}]}}
```

配套还有（`evidence/60,61`，均匿名可用）：

| 端点 | 实测 |
|---|---|
| `/x/copyright-music-publicity/toplist/detail?list_id=76` | `code:0`，榜单元信息（`listen_fid`/`all_fid`/`cover_url`/`is_subscribe`…） |
| `/x/copyright-music-publicity/toplist/music_list?list_id=76&pn=1&ps=3` | `code:0`，`music_id`(MA…)/`music_title`/`singer`/`album`/`mv_bvid`/`creation_bvid`/`heat`/`rank`/`can_listen` |

> ⚠️ 这套是**正版曲库推广位**，主键是 `music_id`（`MA…`）与 `bvid`，**不是 auid**。播放只能走视频 DASH。本次仅记录可行性，**未深入研究**。

---

## 7. 视频 DASH 音频提取（备选方案，本版不实现）

### 7.1 ✅ 可行路径：**旧的非 wbi 端点**

```
GET https://api.bilibili.com/x/player/playurl?bvid=<BV>&cid=<cid>&fnval=4048&fnver=0&fourk=1
```

- **必须带 `Cookie: buvid3=<...>`**（`buvid3` 从 `GET /x/frontend/finger/spi` 随手可取，匿名即可）
- 实测 `code:0`（`evidence/64-playurl-legacy-dash.txt`），`data.dash.audio` 有 **3 条**：

（`evidence/90-wbi-ab.txt` §[4]）

| `id` | `bandwidth` | `codecs` | `mimeType` | 含义 |
|---|---|---|---|---|
| `30216` | 43962 | `mp4a.40.5` | `audio/mp4` | 64K HE-AAC |
| `30232` | 102931 | `mp4a.40.2` | `audio/mp4` | 132K AAC-LC |
| `30280` | 203786 | `mp4a.40.2` | `audio/mp4` | 192K AAC-LC |

- 每条含 `baseUrl` **与 `backupUrl`（2 条）** —— 比音频区的「只有 1 条 cdn」更健壮
- `baseUrl` 形如 `https://<pcdn-host>:4483/upgcxcode/99/91/137649199/137649199_da2-1-30280.m4s?...`
- query 里含 **`deadline`，同样是 `now + 7200 s`**（实测三次全为 7200）⇒ **与音频区共用同一个 2 小时签名模型**
- 视频（DASH）**不需要 Referer** 就能取流吗？—— 本次**未单独验证**，但 B站 CDN 普遍同款策略，接入时应统一带 `Referer`

### 7.2 ❌ 不可行路径：`/x/player/wbi/playurl`

实测 **HTTP 412**（不是 JSON，是 3286 字节的 HTML 错误页）：

| 请求 | 结果 |
|---|---|
| 无签名 + buvid3 | **HTTP 412** |
| **有有效 w_rid** + buvid3 | **HTTP 412** |
| 伪造 w_rid + buvid3 | **HTTP 412** |
| 无签名、无 Cookie | **HTTP 412** |

⇒ 该路径对**本机出口**整体封禁，**与 wbi 签名无关**（详见 `wbi-signature.md` §4）。

### 7.3 元数据获取

| 用途 | 端点 | 匿名 |
|---|---|---|
| bvid → 分 P / cid | `GET /x/player/pagelist?bvid=<BV>` | ✅ |
| 视频详情 | `GET /x/web-interface/view?bvid=<BV>` | ✅ |
| 视频详情+（**验签**） | `GET /x/web-interface/wbi/view/detail?bvid=<BV>` | ⚠️ **需要 w_rid** |
| 播放器元信息 | `GET /x/player/wbi/v2?bvid=<BV>&cid=<cid>` | ✅（实测不需要签名） |
| cid 探测 | `GET /x/player/pagelist`（见上） | ✅ |

### 7.4 视频搜索（**唯一可用的"按关键词找歌"路径**）

```
GET /x/web-interface/wbi/search/type?search_type=video&keyword=<kw>&page=1[&tids=3]
```

`tids=3` = **音乐区**（实测返回 `typeid:"130" typename:"音乐综合"` 等）。
另有 `s.search.bilibili.com/cate/search?main_ver=v3&search_type=video&view_type=hot_rank&order=click&cate_id=<分区>&page=&pagesize=` 这条老接口被 `despcy/Bilibili-MusicPlayer` 使用（本次**未实测**）。

---

## 8. 端点总表（一页速查）

| # | 端点 | 方法 | 匿名 | 实测 code | 用途 |
|---|---|---|---|---|---|
| 1 | `/audio/music-service-c/web/song/info?sid=` | GET | ✅ | `0` / `4511001` | 曲目元数据 |
| 2 | `/audio/music-service-c/web/url?sid=&quality=&privilege=2&mid=0&platform=web` | GET | ✅ | `0` | 流 URL（**仅 192K**） |
| 3 | **`/audio/music-service-c/url?songid=&quality=&privilege=2&mid=0&platform=pc`** | GET | ✅ | `0` | **流 URL（到 320K）** |
| 4 | `/audio/music-service-c/web/song/lyric?sid=` | GET | ✅ | `0` / `data:null` | LRC 歌词正文 |
| 5 | `/audio/music-service-c/web/tag/song?sid=` | GET | ✅ | `0` | 曲目标签 |
| 6 | `/audio/music-service-c/web/member/song?sid=` | GET | ✅ | `0` | 创作成员 |
| 7 | `/audio/music-service-c/web/stat/song?sid=` | GET | ✅ | `0` | 播放/收藏计数 |
| 8 | `/audio/music-service-c/web/menu/rank?pn=&ps=` | GET | ✅ | `0` | **榜单列表（12 个）** |
| 9 | `/audio/music-service-c/web/menu/hit?pn=&ps=` | GET | ✅ | `0` | **热门歌单（11 个）** |
| 10 | `/audio/music-service-c/web/menu/info?sid=` | GET | ✅ | `0` | 歌单元数据 |
| 11 | `/audio/music-service-c/web/song/of-menu?sid=&pn=&ps=` | GET | ✅ | `0` | **歌单曲目列表** |
| 12 | `/audio/music-service-c/web/collections/{list,info,songs-coll}` | GET | 🔒 | `4511003` | 我的收藏夹（需登录） |
| 13 | `/audio/music-service-c/web/coin/audio?sid=` | GET | 🔒 | `4511003` | 投币状态 |
| 14 | `/audio/music-service-c/web/song/search` 等 7 个 | GET | — | **404** | **不存在** |
| 15 | `/audio/music-service-c/web/menu/search` | GET | ✅ | `0`+`data:null` | 空壳 |
| 16 | `/x/web-interface/wbi/search/type?search_type=video&tids=3` | GET | ✅* | `0` | 视频搜索（*本机不强制签名） |
| 17 | `/x/player/playurl?...&fnval=4048` | GET | ✅（需 buvid3） | `0` | **视频 DASH（3 条 audio）** |
| 18 | `/x/player/wbi/playurl?...` | GET | ❌ | **HTTP 412** | 本机出口被封 |
| 19 | `/x/web-interface/wbi/view/detail?bvid=` | GET | ⚠️ | `-352`→`0` | **验签**（A/B 用） |
| 20 | `/x/centralization/interface/new/music?plat=2` | GET | ✅ | `0` | 新歌（非 au） |
| 21 | `/x/copyright-music-publicity/toplist/{detail,music_list}` | GET | ✅ | `0` | 音乐热榜（非 au） |

---

## 9. 未验证项（诚实清单）

| 项 | 状态 |
|---|---|
| 登录后能否拿到 FLAC（`quality=3`） | **未验证** —— 本机无可用 SESSDATA，未做登录态实测 |
| 大会员能否拿到 FLAC / 更高码率 | **未验证** |
| `type:-1`（30s 试听）的真实触发条件 | **未复现** —— 23 首样本全是完整曲 |
| `attr` / `limit` / `limitdesc` 非 0 的样本 | **未采到** |
| `vipInfo` 的确切语义 | **未确证** —— 含 `status=1` 的样本仍返回完整文件 |
| auid 的数值上界 | **未确定** —— ≥8e6 的 12 个抽样全 miss，但不足以定论 |
| `crtype=2 + msid!=0` 是否**必然**无流 | **样本仅 2 个**，疑似规律，需更大样本 |
| 音频 CDN 是否需要 `Cookie` | **未验证** —— 只在匿名下测过 Referer；登录态是否更宽松未知 |
| URL 超过 `deadline` 后的真实响应 | **未验证** —— 需要等 2 小时，本次未做 |
| `www.bilibili.com` 与 `api.bilibili.com` 两个 host 的长期一致性 | 本次 21 个端点**逐一对照，响应完全一致**；长期一致性未验证 |
| `s.search.bilibili.com/cate/search` 老分区搜索接口 | **未实测** |
| 音频区在 App 端（`platform=android` + access_key）是否有更高音质 | **未验证**（`biu` 的注释提到 APP 认证可获取付费音频，但那需要 `access_key`） |
