# 原始实测证据 —— B站音源调研（Ncrust v3.1.0）

> 本文是 `docs/verification/v3.1.0/bili-research/` 全部结论的**证据底座**。
> 结论篇：`bili-audio-api.md` / `wbi-signature.md` / `bili-auth.md` / `community-implementations.md` / `RECOMMENDATIONS.md`

---

## 0. 环境与复现方式

| 项 | 值 |
|---|---|
| 实测时间 | **2026-09-28 02:20 – 02:55（+0800）**，epoch ≈ 1790533200 – 1790535300 |
| 出口 | **直连**。本机环境变量默认含 `http_proxy=https_proxy=http://127.0.0.1:10808`，**所有 B站请求均显式 `env -u https_proxy -u http_proxy` 绕过代理** |
| 出口 IP（实测 `%{remote_ip}`） | `221.178.63.11/12`、`111.19.247.150`（IPv4）；`2409:8c60:2600:2::2xx`、`2409:8c62:e10:2::2x`（IPv6）—— 随 CDN 调度变化 |
| 身份 | **完全匿名**：无 `SESSDATA`、无任何 B站 Cookie（除显式标注的 `buvid3` 对照） |
| UA | `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36` |
| Referer | `https://www.bilibili.com/audio/home`（音频区） / `https://www.bilibili.com/`（通用） |
| GitHub 相关 | ⚠️ **走代理**（`https_proxy=http://127.0.0.1:10808`）—— 本机直连 `api.github.com` 超时（`curl (28)`） |

### 一键复现

```bash
cd docs/verification/v3.1.0/bili-research
bash collect_evidence.sh          # 跑全部 65 条 curl + 3 份脚本产物 → ./evidence/
bash collect_evidence.sh songinfo # 只跑名字含 songinfo 的
```

产物清单（**共 73 个文件**）：

```
evidence/
├── 01-nav.txt                              … 65-*.txt   ← collect_evidence.sh 的 curl 证据
├── 80-cdn-referer-and-ttl.txt              ← Referer 403 对照 + deadline 精确测量 + ffprobe 码率
├── 90-wbi-ab.txt                           ← 【硬性验收】wbi 签名 A/B
├── 91-wbi-golden-vector.txt                ← 【硬性验收】官方向量自检（离线）
├── 92-wts-tolerance.txt                    ← wts 容忍窗口实测
├── 93-wbi-key-global.txt                   ← wbi key 全站统一验证
├── 94-auth-boundary.txt                    ← 登录/Cookie 边界
├── 95-wbi-encoding-ab.txt                  ← 编码问题 + 风控升级实测
└── 96-cdn-referer-exoplayer.txt            ← 【关键】三种流的 Referer 依赖
```

配套脚本（**都是本仓库自有代码，无第三方依赖**）：

| 文件 | 作用 |
|---|---|
| `wbi_sign_reference.py` | Wbi 签名参考实现（仅标准库），被下面两个脚本复用 |
| `wbi_golden.py` | **离线**官方向量自检（不需要网络） |
| `wbi_ab.py` | 服务端 A/B：无签名 vs 有签名 vs 伪造签名 |
| `collect_evidence.sh` | 全量 curl 取证 |

---

## 1. 未登录也能拿到 wbi key（`01-nav.txt`）

```bash
env -u https_proxy -u http_proxy curl -sS --compressed \
  'https://api.bilibili.com/x/web-interface/nav' \
  -H 'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/120.0.0.0 Safari/537.36'
```

```json
{"code":-101,"message":"账号未登录","ttl":1,"data":{"isLogin":false,
 "wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
            "sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"},
 "ip_region":"CN"}}
```

- **`code:-101`（未登录）但 `data.wbi_img` 照常下发** ✅ 任务书要求验证的点成立
- 响应头里**没有 `Set-Cookie`**（实测只有一个 `HTTP/2 200` 行）⇒ "访问 nav 会自动种 `buvid3`"在本机**不成立**
- `img_key = 7cd084941338484aae1ad9425b84077c`、`sub_key = 4932caff0ff746eab6f01bf08b70ac45`
- `mixin_key = ea1db124af3c7062474693fa704f4ff8`

---

## 2. wbi key 与 UA / 登录态 / IP 无关（`93-wbi-key-global.txt`）

三种截然不同的 UA（含 **空 UA**）拿到**逐字节相同**的 key：

```
UA=Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chr {"code":-101,"wbi_img":{"img_url":"...7cd0849...","sub_url":"...4932caf..."}}
UA=Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like  {"code":-101,"wbi_img":{"img_url":"...7cd0849...","sub_url":"...4932caf..."}}
UA=curl/8.5.0                                    {"code":-101,"wbi_img":{"img_url":"...7cd0849...","sub_url":"...4932caf..."}}
UA=（空）                                         {"code":-101,"wbi_img":{"img_url":"...7cd0849...","sub_url":"...4932caf..."}}
```

⇒ **wbi key 是全站统一下发**，可以跨用户缓存；**与账号、IP、UA 均无关**。

---

## 3. ⭐【硬性验收 1】Wbi 官方向量自检 —— 4 项全部 MATCH（`91-wbi-golden-vector.txt`）

```bash
env -u https_proxy -u http_proxy python3 wbi_golden.py --live
```

```
[1] 乱序表
    MIXIN_KEY_ENC_TAB 长度 = 64 (期望 64)
    是否为 0..63 的一个排列 : True
[2] mixin_key 推导
    img_key  = 653657f524a547ac981ded72ea172057
    sub_key  = 6e4909c702f846728e64f6007736a338
    img+sub  = 653657f524a547ac981ded72ea1720576e4909c702f846728e64f6007736a338 (len=64)
    计算值   = 72136226c6a73669787ee4fd02a74c27
    期望值   = 72136226c6a73669787ee4fd02a74c27
    MATCH    = True
[3] 排序 + 过滤 + 编码（数字参数）
    计算 query = bar=514&foo=114&wts=1684746387&zab=1919810
    期望 query = bar=514&foo=114&wts=1684746387&zab=1919810
    MATCH      = True
[4] w_rid = md5(query + mixin_key)
    md5 输入 = bar=514&foo=114&wts=1684746387&zab=1919810 72136226c6a73669787ee4fd02a74c27
    计算 w_rid = 90efcab09403023875b8516f07e9f9de
    期望 w_rid = 90efcab09403023875b8516f07e9f9de
    MATCH      = True
[5] 中文 / 空格编码用例
    计算 query = bar=%E4%BA%94%E4%B8%80%E5%9B%9B&baz=1919810&foo=one%20one%20four
    期望 query = bar=%E4%BA%94%E4%B8%80%E5%9B%9B&baz=1919810&foo=one%20one%20four
    MATCH      = True
    对照：不同 URL 编码器的空格表现
      urllib.parse.urlencode 默认 (quote_plus) : a=one+two
      quote_via=quote, safe=''  （正确）        : a=one%20two
[6] 过滤 !'()* 的行为
    输入值     = "a!b'c(d)e*f"
    过滤后参与 = 'abcdef' (期望 'abcdef')
================================================================================
总判定 : 全部 PASS ✅ —— 实现与文档定义逐字节一致
================================================================================
[附] 用**实时** nav key 再跑一次（需要网络）
    nav.code = -101
    img_key  = 7cd084941338484aae1ad9425b84077c
    sub_key  = 4932caff0ff746eab6f01bf08b70ac45
    mixin_key= ea1db124af3c7062474693fa704f4ff8 (len=32)
    wts      = 1790533855
    query    = keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6&page=1&search_type=video&wts=1790533855
    md5 输入 = keyword=%E5%91%A8%E6%9D%B0%E4%BC%A6&page=1&search_type=video&wts=1790533855ea1db124af3c7062474693fa704f4ff8
    w_rid    = 1c2fd2596de0ef74273ea5f7bc801322
```

**这一步的意义**：把"实现是对的"从"服务端没报错"提升为**与文档定义逐字节等价**，排除"错误实现碰巧被接受"。

---

## 4. ⭐【硬性验收 2】Wbi 签名服务端 A/B（`90-wbi-ab.txt`）

**完整原始输出**（2026-09-28 02:29:37，直连、匿名）：

```
==============================================================================
Ncrust · B站 Wbi 签名 A/B 对照实验
时间: 2026-09-28 02:29:37 +0800  epoch = 1790533777
出口: 直连（http_proxy/https_proxy 已 unset），匿名，无 SESSDATA
==============================================================================

[0] nav → wbi key
    nav.code   = -101 (-101 = 未登录，但 wbi key 照常下发)
    img_key    = 7cd084941338484aae1ad9425b84077c
    sub_key    = 4932caff0ff746eab6f01bf08b70ac45
    mixin_key  = ea1db124af3c7062474693fa704f4ff8 (len=32)

------------------------------------------------------------------------------
[1] 主对照：/x/web-interface/wbi/view/detail  (bvid=BV1GJ411x7h7)
------------------------------------------------------------------------------
  A) 无 w_rid / 无 wts
     GET https://api.bilibili.com/x/web-interface/wbi/view/detail?bvid=BV1GJ411x7h7
     HTTP=200   0.26s  code=-352     message=风控校验失败  v_voucher=voucher_42e0516d-f6b8-4f0d-8958-9de3ea611277

  B) 有 w_rid（本仓库 wbi_sign_reference.py 现算）
     wts           = 1790533778
     signed_query  = bvid=BV1GJ411x7h7&wts=1790533778
     md5 输入       = bvid=BV1GJ411x7h7&wts=1790533778ea1db124af3c7062474693fa704f4ff8
     w_rid         = 8c2633241031d11b3e3107856aa1f4a8
     GET https://api.bilibili.com/x/web-interface/wbi/view/detail?bvid=BV1GJ411x7h7&wts=1790533778&w_rid=8c2633241031d11b3e3107856aa1f4a8
     HTTP=200   0.58s  code=0        message=OK  data.keys=['Card', 'Related', 'Reply', 'Spec', 'Tags', 'View']

  C) 有 wts 但 w_rid 伪造（全 0）—— 验证服务端真的在算 md5，而不是只看字段存在
     GET ...&wts=1790533778&w_rid=00000000000000000000000000000000
     HTTP=200   0.25s  code=-352     message=风控校验失败  v_voucher=voucher_0b7b0d89-54fb-49b4-859f-c4e1ea2e102b

  D) 有有效 w_rid + buvid3 Cookie（对照：Cookie 不改变结论）
     HTTP=200   0.61s  code=0        message=OK  data.keys=['Card', 'Related', 'Reply', 'Spec', 'Tags', 'View']

------------------------------------------------------------------------------
[2] 对照：/x/web-interface/wbi/search/type  —— 本机实测**不强制** w_rid
------------------------------------------------------------------------------
  A) 无 w_rid   : HTTP=200   0.58s  code=0        message=OK  data.keys=['egg_hit','exp_list',...]
  B) 有 w_rid   : HTTP=200   0.63s  code=0        message=OK  data.keys=['egg_hit','exp_list',...]
  C) 伪造 w_rid : HTTP=200   0.56s  code=0        message=OK  data.keys=['egg_hit','exp_list',...]
  ⇒ 三者结果一致 ⇒ 该端点在本机出口上不校验 w_rid

------------------------------------------------------------------------------
[3] 反例：签名**无法**挽救的两种情况（IP 级风控 / 路径级封禁）
------------------------------------------------------------------------------
  space/wbi/acc/info  无签名 : HTTP=200   0.23s  code=-352     message=风控校验失败
  space/wbi/acc/info  有签名 : HTTP=200   0.23s  code=-352     message=风控校验失败
  player/wbi/playurl  无签名 : HTTP=412   0.25s  NON-JSON body[0:80]='<!DOCTYPE html>\n<html lang="zh-cn">...'
  player/wbi/playurl  有签名 : HTTP=412   0.24s  NON-JSON body[0:80]='<!DOCTYPE html>\n<html lang="zh-cn">...'
  ⇒ 两者同为 HTTP 412 ⇒ 该路径对本机出口整体封禁，wbi 不是原因也不是解药

------------------------------------------------------------------------------
[4] 可用替代：/x/player/playurl （**非 wbi** 旧路径）→ 200 + DASH
------------------------------------------------------------------------------
   HTTP=200   0.35s  code=0        message=OK
    dash.audio 条数 = 3
      id=30216  bw=43962    codecs=mp4a.40.5    deadline=1790540982 (now+7200s)
      id=30232  bw=102931   codecs=mp4a.40.2    deadline=1790540982 (now+7200s)
      id=30280  bw=203786   codecs=mp4a.40.2    deadline=1790540982 (now+7200s)
```

### 判读表

| 请求 | HTTP | `code` | 结论 |
|---|---|---|---|
| 无 `w_rid` | 200 | **-352** | 风控拒绝（带 `v_voucher`） |
| `w_rid` = 全 0（有 `wts`） | 200 | **-352** | **服务端确实在算 md5** |
| **正确 `w_rid`** | 200 | **0** | ✅ **通过** |
| 正确 `w_rid` + `buvid3` | 200 | **0** | Cookie 不改变结论 |

### ⚠️ 关于"412 对照"的诚实说明

任务书假设「无签名 → HTTP 412，有签名 → HTTP 200」。**实测这个假设不成立**：

- **缺 `w_rid` 的真实表现是 `HTTP 200 + code:-352`**，不是 412；
- **HTTP 412 是"路径级封禁"**：`/x/player/wbi/playurl` 对 4 种组合（无签名 / 有效签名 / 伪造签名 / 无 Cookie）**全部返 412**，且正文是 3286 字节 HTML 错误页（`<title>出错啦! - bilibili.com</title>`）。**给 412 补签名不会变成 200。**
- 因此本文给出**真实存在**的 A/B（`-352` → `0`），而不是伪造一个 412。

---

## 5. ⚠️ 风控会随时间升级：同一个 A/B 在 18 分钟后失效（`95-wbi-encoding-ab.txt` 附 3）

```
2026-09-28 02:29:37  view/detail 无签名 → -352；有签名 → code:0 (OK)      ← A/B 成立
2026-09-28 02:46:54  同一份代码、同一个出口：
    SIGNED   bvid=BV1GJ411x7h7&wts=1790534814&w_rid=<正确>  → {"code":-352,"message":"风控校验失败"}
    UNSIGNED bvid=BV1GJ411x7h7                              → {"code":-352,"message":"风控校验失败"}
```

**签名不再能救回来** —— 该端点对本机出口进入整体风控。

**同一时刻的对照**（三个端点同时打）：

```
search/type  无签名 : {"code":0,"message":"OK","ttl":1,...}                          ← 仍可用
song/info au39      : {"code":0,"data":{"id":39,...}}                                ← 完全不受影响
song/url  au39      : {"code":0,"data":{"sid":39,...,"timeout":10800,...}}           ← 完全不受影响
```

⇒ **音频区端点不参与 wbi 风控，是稳定的那条路。**
⇒ 复现者若发现 A/B 两边都是 `-352`，说明**自己的出口已被风控**，换网络再试；**算法正确性由 §3 的离线自检独立保证**。

---

## 6. ⚠️【关键】B站 CDN 对三种流都强校验 Referer（`80-...txt` + `96-...txt`）

```bash
# 取一条音频流 URL
URL=$(curl -sS --compressed -H 'Referer: https://www.bilibili.com/audio/home' \
  'https://www.bilibili.com/audio/music-service-c/web/url?sid=39&quality=0&privilege=2&mid=0&platform=web' \
  | jq -r '.data.cdns[0]')

curl -sS -r 0-1023 -o /dev/null -w 'HTTP=%{http_code}\n' -H 'Referer: https://www.bilibili.com/audio/home' "$URL"
curl -sS -r 0-1023 -o /dev/null -w 'HTTP=%{http_code}\n' "$URL"
```

```
--- A) 带 Referer: https://www.bilibili.com/audio/home   →  HTTP=206 bytes=1024
--- B) 完全不带头                                          →  HTTP=403 bytes=346
--- C) 带外部 Referer: https://example.com/               →  HTTP=403 bytes=347
--- D) 只带 UA（无 Referer）                              →  HTTP=403 bytes=347
--- E) Range 请求响应头（ExoPlayer 用）
HTTP/2 206
content-type: application/octet-stream
content-length: 1024
content-range: bytes 0-1023/6244629
access-control-expose-headers: Content-Length,Content-Range,x-service-module
```

**三种流一致**（`96-cdn-referer-exoplayer.txt`）：

| 流 | 带 B站 Referer | 只带 UA | 模拟 ExoPlayer |
|---|---|---|---|
| 音频区 web 192K | **206** | 403 | **403** |
| 音频区 APP 320K | **206** | — | **403** |
| 视频 DASH 音轨 | **206** | 403 | **403** |

（"模拟 ExoPlayer" = `UA: ExoPlayerLib/1.2.1` + `Accept-Encoding: identity`）

⇒ **Ncrust 的 ExoPlayer 取流必须带 `Referer: https://www.bilibili.com/`，否则一律 403。**
⇒ 而当前实现**没有地方能带**（`SongUrlResult` 无 `headers`；`DefaultDataSource.Factory(app)` 无 `defaultRequestProperties`）——详见 `RECOMMENDATIONS.md` §4.9。

---

## 7. 音频流 URL 的时效：`timeout`(10800) ≠ `deadline`(+7200)（`80-...txt` F 段）

```
--- F) deadline 与 timeout 的精确差值（连测 3 次）
  #1 now=1790533776 deadline=1790540976 timeout=10800 deadline-now=7200s
  #2 now=1790533776 deadline=1790540976 timeout=10800 deadline-now=7200s
  #3 now=1790533776 deadline=1790540977 timeout=10800 deadline-now=7201s
```

- 响应体 `timeout` = **10800**（名义 3 小时，**恒定常量**，不随请求变化）
- URL 查询串 `deadline` = **`now + 7200`**（真实 2 小时，3 次全为 7200/7201）
- **视频 DASH 的 3 条音轨 `deadline` 同为 `now+7200`** ⇒ B站全站统一的签名有效期

**URL 每次都不同**（同一 sid 连取 3 次，`deadline` 同、`trid`/`upsig` 不同）：

```
  #1 now=1790533440 deadline=1790540640 timeout=10800 url_md5=b8ab609f88b5
  #2 now=1790533440 deadline=1790540640 timeout=10800 url_md5=8c49bef70e6a
  #3 now=1790533440 deadline=1790540640 timeout=10800 url_md5=0086ecd5b33e
```

### 320K 落盘 + ffprobe 实测（`80-...txt` G 段）

```
--- G) 320K 文件落盘 + ffprobe 实测码率
HTTP=200 bytes=10374528
/tmp/.ev320.m4a: ISO Media, Apple iTunes ALAC/AAC-LC (.M4A) Audio
duration=258.085000
bit_rate=321584          ← 321.6 kbps，确认是 320K 档
```

对照 192K：`duration=258.085000 / bit_rate=193568`。

---

## 8. 音频流：web 端点 vs APP 端点（`13..20-songurl-*.txt`）

### 8.1 web 端点 —— `quality` 参数**完全无效**，恒 192K

```bash
for qn in 0 1 2 3; do
  curl -sS --compressed -H 'Referer: https://www.bilibili.com/audio/home' \
    "https://www.bilibili.com/audio/music-service-c/web/url?sid=39&quality=$qn&privilege=2&mid=0&platform=web"
done
```

| qn | `type` | `size` | 文件名 |
|---|---|---|---|
| 0 | 1 | 6244629 | `ef0083e1cd13e73dcd20bfe3e672c21a-192k.m4a` |
| 1 | 1 | 6244629 | 同上 |
| 2 | 1 | 6244629 | 同上 |
| 3 | 1 | 6244629 | 同上 |

`qualities` 字段：**恒为 `null`**。`title`/`cover`：**恒为空串**。

### 8.2 APP 端点 —— `quality` 生效，**匿名到 320K**

```bash
curl -sS --compressed \
  'https://api.bilibili.com/audio/music-service-c/url?songid=39&quality=2&privilege=2&mid=0&platform=pc'
```

```json
{"code":0,"msg":"success","data":{
  "sid":39,"type":2,"info":"","timeout":10800,"size":10374528,
  "cdns":["https://upos-sz-mirrorhw.bilivideo.com/ugaxcode/ef0083e1cd13e73dcd20bfe3e672c21a-320k.m4a?e=...&deadline=1790540893&...&upsig=88b59c54f8f1caa547d8531d619644f7&..."],
  "qualities":[
    {"type":2,"desc":"高品质","size":10374528,"bps":"320kbit/s","tag":"HQ","require":0,"requiredesc":""},
    {"type":1,"desc":"标准",  "size":6244629, "bps":"192kbit/s","tag":"",  "require":0,"requiredesc":""},
    {"type":0,"desc":"流畅",  "size":4179753, "bps":"128kbit/s","tag":"",  "require":0,"requiredesc":""}
  ]}}
```

| `quality` | 返回 `type` | `size` | 文件名 |
|---|---|---|---|
| 0 | 0 | 4179753 | `-128k.m4a` |
| 1 | 1 | 6244629 | `-192k.m4a` |
| 2 | **2** | 10374528 | `-320k.m4a` |
| 3 (FLAC) | **2（静默降级）** | 10374528 | `-320k.m4a` |

**`qualities[]` 表里根本没有 `type:3` 条目** ⇒ 匿名确实没有 FLAC 选项。

参数名验证：`/audio/music-service-c/url?sid=...` → `{"code":72000000,"msg":"param missing error: songid"}` ⇒ **主键参数确实是 `songid`**。

---

## 9. `song/info` 实测（`02..12-songinfo-*.txt`）

```bash
curl -sS --compressed -H 'Referer: https://www.bilibili.com/audio/home' \
  'https://www.bilibili.com/audio/music-service-c/web/song/info?sid=39'
```

```json
{"code":0,"data":{"id":39,"uid":14452610,"uname":"Roro_Zhang","author":"Roro_Zhang",
 "title":"成都（Cover赵雷）",
 "cover":"http://i0.hdslb.com/bfs/music/a5a79465d73c53606bacca073c33c3bc2822b2ae.jpg",
 "intro":"粉丝群：568439813",
 "lyric":"http://i0.hdslb.com/bfs/music/149994607539.lrc",
 "crtype":1,"duration":258,"passtime":1502179114,"curtime":1790533743,
 "aid":7361800,"bvid":"BV18s411Y7Wu","cid":12035021,"msid":0,
 "attr":0,"limit":0,"activityId":0,"limitdesc":"","coin_num":7,"ctime":1499945799000,
 "statistic":{"sid":39,"play":2981,"collect":96,"comment":39,"share":0},
 "vipInfo":{"type":2,"status":1,"due_date":1835625600000,"vip_pay_type":1},
 "collectIds":[],"is_cooper":0},"message":"OK","msg":"success"}
```

不存在的 sid（**HTTP 200**，不是 404）：

```json
{"code":4511001,"data":null,"message":"音频未找到或已下架","msg":"音频未找到或已下架"}
```

| 字段 | 实测结论 |
|---|---|
| `duration` | **秒**（258 ↔ ffprobe `duration=258.085`）✅ |
| `cover` / `lyric` | **`http://`**（非 https），`lyric` 可以是空串 |
| `ctime` | **毫秒**（`1499945799000`），与 `passtime`/`curtime`（秒）单位不同 |
| `statistic` | `{sid, play, collect, comment, share}` |
| `vipInfo` | 可为 `null`（au127015、au4059094） |
| `crtype` | 1=自制 2=转载 |

### `crtype=2` 的可播性缺陷

| auid | crtype | msid | `song/url` 结果 |
|---|---|---|---|
| au127015 | 2 | 332082 | `code:0` 但 **`cdns:null`** ⇒ 无流 |
| au130878 | 2 | 268963 | `code:0` 但 **`cdns:null`** ⇒ 无流 |
| au1124992 | 2 | 0 | ✅ `cdns[]` |
| au1780974 | 2 | 0 | ✅ |
| au2339898 | 2 | 0 | ✅ |
| au2493404 | 2 | 0 | ✅ |

```json
{"code":0,"data":{"sid":127015,"title":"","cover":"","type":1,"info":"",
 "timeout":10800,"size":1378320,"cdns":null,"qualities":null},"message":"OK","msg":"success"}
```

### ⚠️ `size` 字段在新上传音频上不可信

| auid | `size` 字段 | HTTP 实体实际字节数 |
|---|---|---|
| au39（2017） | 6244629 | 6244629 ✅ |
| au4059094（2023） | **146** | **5093773** ❌ |
| au4055594（2023） | **150** | **2628821** ❌ |
| au4056879（2023） | **146** | **144160817** ❌ |

---

## 10. 歌词实测（`21..24-lyric-*.txt`）

```bash
curl -sS --compressed -H 'Referer: https://www.bilibili.com/audio/home' \
  'https://www.bilibili.com/audio/music-service-c/web/song/lyric?sid=39'
```

```json
{"code":0,"msg":"success","data":"[00:33.26]让我掉下眼泪的\n[00:36.93]不止昨夜的酒\n[00:40.71]让我依依不舍的\n[00:44.47]不止你的温柔\n[00:48.82]余路还要走多久\n[00:52.88]你攥着我的手\n[00:56.63]让我感到为难的\n[01:00.43]是挣扎的自由\n[01:06.45][02:44.32]分别总是在九月\n[01:10.33][02:48.29]回忆是思念的愁\n[01:14.27][02:52.13]深秋嫩绿的垂柳\n[01:17.97][02:55.92]亲吻着我额头\n..."}
```

- **`data` 是字符串，不是对象** ⚠️
- **标准 LRC，`[MM:SS.mm]`（两位百分秒）** ⇒ 与 Ncrust 现有 `LrcParser` 兼容
- 一行可带**多个时间戳**（`[01:06.45][02:44.32]`）
- 无歌词时：`{"code":0,"msg":"success","data":null}`（au13）—— **`code` 仍为 0**，靠 `data == null` 判空
- `song/info` 的 `lyric` URL 指向**同一份内容**（实测逐行相同）
- **只有行级 LRC，无逐字/翻译/TTML**

---

## 11. 浏览类端点实测

### 榜单（`26-menu-rank.txt`）—— 12 个，匿名

```json
{"code":0,"data":{"curPage":1,"pageCount":1,"totalSize":12,"pageSize":12,"data":[
 {"menuId":10624,"title":"新曲推荐","uname":"大家的音乐机","type":2,...},
 {"menuId":10627,"title":"热歌榜（每日11:00更新）","type":2,...},
 {"menuId":10628,"title":"原创榜（每日11:00更新）","type":2,...},
 {"menuId":10629,"title":"中文人声三日榜","type":2,...},
 {"menuId":10634,"title":"纯音乐/演奏三日榜","type":2,...}]}}
```

### 热门歌单（`25-menu-hit.txt`）—— 11 个，匿名

```json
{"code":0,"data":{"curPage":1,"pageCount":1,"totalSize":11,"pageSize":11,"data":[
 {"menuId":10624,"uid":32708543,"uname":"大家的音乐机","title":"新曲推荐",
  "cover":"http://i0.hdslb.com/bfs/music/a32c1ed4f6ec3f74f8240f4486a750dda3a509e5.jpg",
  "intro":"每天11:00更新，为你推送最新音乐","type":2,"ctime":1501209433,
  "curtime":1790533754,"statistic":{"sid":10624,"play":2378424,...}},...]}}
```

### 歌单曲目（`28-song-of-menu-10624.txt`）

```json
{"code":0,"data":{"curPage":1,"pageCount":4,"totalSize":16,"pageSize":5,"data":[
 {"id":2478206,"uid":5669526,"uname":"MitchieM","author":"初音未来, MEIKO · Mitchie M",
  "title":"【Mitchie M】Nechusho No!No! (feat. 初音未来 & MEIKO)",
  "cover":"http://i0.hdslb.com/bfs/music/dee637baa58ec632ebf928312dfd8986611a465b.jpg",
  "crtype":1,"duration":112,...}]}}
```

### 标签 / 创作成员（`30,31-*.txt`）

```json
{"code":0,"data":[{"type":"song","subtype":0,"key":1,"info":"音乐"},
 {"type":"song","subtype":1,"key":3,"info":"人声"},
 {"type":"song","subtype":2,"key":2,"info":"翻唱"},
 {"type":"song","subtype":3,"key":32,"info":"汉语"},
 {"type":"song","subtype":5,"key":41,"info":"网络歌曲"},
 {"type":"song","subtype":4,"key":10,"info":"民谣"}],"message":"OK","msg":"success"}
```
```json
{"code":0,"data":[{"type":1,"list":[{"mid":0,"member_id":37,"name":"Roro_Zhang"}]},
 {"type":2,"list":[{"mid":0,"member_id":38,"name":"赵雷"}]},
 {"type":3,"list":[{"mid":0,"member_id":38,"name":"赵雷"}]},...]}
```

### 匿名不可用（`33..35-login-*.txt`）

```json
{"code":4511003,"data":null,"message":"用户未登录","msg":"用户未登录"}
```
（`collections/list`、`collections/info`、`coin/audio` 三者相同）

伪造 Cookie 同样被拒（`94-auth-boundary.txt` §[5]）：

```bash
curl '.../collections/list?uid=14452610&pn=1&ps=5' \
  -H 'Cookie: SESSDATA=fake123; bili_jct=fake; DedeUserID=1'
→ {"code":4511003,"data":null,"message":"用户未登录","msg":"用户未登录"}
```

---

## 12. ❌ 音频区**没有搜索接口**（`36..45-probe-*.txt`、`search2.out`）

| 候选端点 | 结果 |
|---|---|
| `/web/song/search?keyword=…` | **HTTP 404** |
| `/web/search/song?keyword=…` | **HTTP 404** |
| `/web/search?keyword=…` | **HTTP 404** |
| `/web/search/type?keyword=…` | **HTTP 404** |
| `/web/song/list` | **HTTP 404** |
| `/web/song/searchByKey` | **HTTP 404** |
| `/web/song/query` | **HTTP 404** |
| `/web/menu/search?keyword=…` | **HTTP 200 但空壳**：`{"code":0,"data":null,"message":"OK","msg":"success"}` |
| `app.bilibili.com/x/v2/search/type?search_type=audio` | `{"code":-400,"message":"-400","ttl":1}` |
| `api.bilibili.com/x/v2/search/type?search_type=audio` | `{"code":-400,"message":"-400","ttl":1}` |

**通用搜索里也没有音频分区**（`57-search-all-v2.txt`）：

```
pageinfo keys : activity, article, bangumi, bili_user, clustering_card, live, live_all,
                live_master, live_room, live_user, media_bangumi, media_ft, movie,
                operation_card, pgc, related_search, special, topic, tv, upuser, user, video
result_type   : tips, brand_ad, esports, activity, web_game, card, media_bangumi,
                media_ft, bili_user, user, star, video
```

**两者都没有 audio / music。**

### `search_type` 合法取值矩阵（`46..55-searchtype-*.txt`）

| `search_type` | 结果 |
|---|---|
| `video` / `media_bangumi` / `bili_user` / `live_room` / `article` / `topic` / `photo` | `code:0` + 结果 ✅ |
| **`music`** | `{"code":-1200, "message":"被降级过滤的请求"}` ❌ |
| **`audio`** | `{"code":-1200, "message":"被降级过滤的请求"}` ❌ |
| **`foobar`**（故意乱填的对照） | `{"code":-1200, "message":"被降级过滤的请求"}` ❌ |

**判据**：`music`/`audio` 与**故意乱填的 `foobar`** 返回**完全相同**的错误 ⇒ 它们是**非法取值**，**不是"被风控拦截"**。

---

## 13. wbi 端点验签矩阵（`wbiprobe.out` + `90-wbi-ab.txt`）

| 端点 | 无 `w_rid` | 有正确 `w_rid` | 判定 |
|---|---|---|---|
| **`/x/web-interface/wbi/view/detail`** | `-352` | **`0` OK** | ✅ **验签** |
| `/x/web-interface/wbi/search/type` | `0` OK | `0` OK | ⚪ 不验（本机出口） |
| `/x/web-interface/wbi/search/all/v2` | `0` OK | — | ⚪ 不验 |
| `/x/web-interface/wbi/index/top/rcmd` | `0` OK | — | ⚪ 不验 |
| `/x/web-interface/wbi/view` | `0` OK | — | ⚪ 不验 |
| `/x/web-interface/wbi/search/default` | `0` OK | — | ⚪ 不验 |
| `/x/web-interface/wbi/search/square` | `0` OK | — | ⚪ 不验 |
| `/x/player/wbi/v2` | `0` OK | — | ⚪ 不验 |
| `/x/space/wbi/acc/info` | `-352` | **`-352`** | ❌ IP 级风控，签名无效 |
| **`/x/player/wbi/playurl`** | **HTTP 412** | **HTTP 412** | ❌ 路径级封禁，签名无效 |

**连打 100 次无签名 `search/type`，全部 `code=0`**（未复现上游文档"数次请求后返回 -403"的说法）：

```
req#1  code=0 http=200 msg=OK      req#20 code=0 http=200 msg=OK
req#2  code=0 http=200 msg=OK      req#40 code=0 http=200 msg=OK
req#3  code=0 http=200 msg=OK      req#60 code=0 http=200 msg=OK
                                   req#80 code=0 http=200 msg=OK
```

---

## 14. `wts` 容忍窗口实测 —— **窗口不存在**（`92-wts-tolerance.txt`）

端点 `view/detail`，**只改 `wts`，签名始终正确**：

| `wts` 相对当前 | 结果 |
|---|---|
| now+0 / −30s / −60s / −300s / −600s | `code:0 OK` |
| −1800s(30min) / −3600s(1h) / −7200s(2h) | `code:0 OK` |
| −10800s(3h) / −21600s(6h) / −43200s(12h) / −86400s(24h) | `code:0 OK` |
| −604800s(7d) / −2592000s(30d) / −31536000s(1y) | `code:0 OK` |
| +60s / +300s / +3600s / +31536000s(1y) | `code:0 OK` |
| **`wts = 0`（1970-01-01）** | **`code:0 OK`** |

⇒ **`wts` 不参与任何有效性判定**，服务端只把它当作被签名字符串的一部分。
⇒ **签名的真实寿命 = `mixin_key` 的寿命（每日轮换），不是 `wts`。**

---

## 15. 视频 DASH 备选路径（`62..65-*.txt`）

```bash
curl -sS --compressed \
  'https://api.bilibili.com/x/player/playurl?bvid=BV1GJ411x7h7&cid=137649199&fnval=4048&fnver=0&fourk=1' \
  -H 'Cookie: buvid3=A0F86246-BEB0-3FFA-20CD-F4E6F1ABE34529812infoc' \
  -H 'Referer: https://www.bilibili.com/video/BV1GJ411x7h7'
```

`code:0`，`data.dash.audio` **3 条**：

| `id` | `bandwidth` | `codecs` | `mimeType` | 含义 |
|---|---|---|---|---|
| `30216` | 43962 | `mp4a.40.5` | `audio/mp4` | ~64K HE-AAC |
| `30232` | 102931 | `mp4a.40.2` | `audio/mp4` | ~132K AAC-LC |
| `30280` | 203786 | `mp4a.40.2` | `audio/mp4` | ~192K AAC-LC |

- 每条含 `baseUrl` **与 `backupUrl`（2 条）**
- `baseUrl` 形如 `https://<pcdn-host>:4483/upgcxcode/99/91/137649199/137649199_da2-1-30280.m4s?...`
- query 里 `deadline` 同样是 **`now+7200`**
- **`buvid3` 必需**：不带 → 412；带上 → 200

`buvid3` 来源（匿名）：

```bash
curl -sS 'https://api.bilibili.com/x/frontend/finger/spi'
→ {"code":0,"data":{"b_3":"A0F86246-BEB0-3FFA-20CD-F4E6F1ABE34529812infoc","b_4":"6C7F6674-..."}}
```

---

## 16. 扫码登录端点可达性（`94-auth-boundary.txt`）

```bash
curl -sS 'https://passport.bilibili.com/x/passport-login/web/qrcode/generate'
→ {"code":0,"message":"OK","ttl":1,"data":{
     "url":"https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1&callback=close&qrcode_key=721a7829c91a1d65dce08a36f0fc4975&from=",
     "qrcode_key":"721a7829c91a1d65dce08a36f0fc4975"}}

curl -sS '.../qrcode/poll?qrcode_key=721a7829c91a1d65dce08a36f0fc4975'
→ {"code":0,"message":"OK","ttl":1,"data":{"url":"","refresh_token":"","timestamp":0,
     "code":86101,"message":"未扫码"}}

curl -sS '.../qrcode/poll?qrcode_key=deadbeef'
→ {"code":0,"message":"OK","ttl":1,"data":{"url":"","refresh_token":"","timestamp":0,
     "code":86038,"message":"二维码已失效"}}
```

⚠️ **注意双层 `code`**：HTTP 恒 200，业务码在 **`data.code`**（顶层 `code` 恒 0）—— 与音频区 API（业务码在顶层 `code`）**不一致**。

**`buvid3` 对音频区端点无影响**（`94-...txt` §[6]）：

```
匿名         : {"code":0,"type":1,"size":6244629}
带 buvid3    : {"code":0,"type":1,"size":6244629}
带 mid=12345 : {"code":0,"type":1,"size":6244629}
```

---

## 17. 新发现的端点（任务书未提及，本次实测确认匿名可用）

### `x/centralization/interface/new/music`（`59-*.txt`）

```json
{"code":0,"message":"0","ttl":1,"data":{"list":[
 {"id":30855,"music_id":"MA637817956440498033","music_title":"new trick",
  "publish_time":"9-17","wish_count":878,"author":"ROSÉ","album":"new trick",
  "aid":"117290355984421","cid":"0","bvid":"BV1ghe26METF","total_vv":1251103,
  "cover":"https://i0.hdslb.com/bfs/station_src/music_metadata/320d0b666d82f295199e401dd06cc983.jpg",
  "rank":999,...}]}}
```

### `x/copyright-music-publicity/toplist/*`（`60,61-*.txt`）

```json
{"code":0,"message":"0","ttl":1,"data":{"list":[
 {"music_id":"MA409252256362326366","music_title":"極楽浄土","singer":"GARNiDELiA",
  "album":"約束 -Promise code-","mv_aid":28361833,"mv_bvid":"BV1us41137Fd",
  "heat":5328979,"rank":1,"can_listen":true,
  "creation_bvid":"BV1Mi4y1L7CV","creation_duration":228,"creation_play":8566857}]}}
```

⚠️ 这套是**正版曲库推广位**，主键是 `music_id`（`MA…`）与 `bvid`，**不是 auid**，播放只能走视频 DASH。本次仅记录可行性，**未深入研究**。

---

## 18. 社区实现的许可证取证实录

### 18.1 `bilibili-API-collect` 关停声明（上游 README 原文）

```markdown
# Deprecated
本仓库停止维护并永久关停。
This Repo will cease maintenance and be permanently closed.
## 原因
2026年1月28日，本仓库维护者收到B站委托的律师事务所发律师函警告邮件，指控本仓库中的项目存在
"通过技术手段对哔哩哔哩平台非公开的API接口及其调用逻辑、参数结构、访问控制及安全认证机制
进行系统性收集、整理，并以技术文档、代码示例等形式向不特定公众传播"的侵权行为。
即日起停止维护并删除相关文档及源代码。
```

**上游内容已删除实测**：

| 路径（上游 master） | 实测 |
|---|---|
| `LICENSE` | **HTTP 404** |
| `docs/audio/info.md` | **HTTP 404** |
| `docs/misc/sign/wbi.md` | **HTTP 404** |

本次使用的文档副本来自**贡献者存档 fork**（`pskdje/bilibili-API-collect`、`venhow/bilibili-API-collect`、`Geekertao-bot/bilibili-API-collect`；三者 `docs/misc/sign/wbi.md` 与 `LICENSE` 均实测 HTTP 200）。

### 18.2 许可证（存档 fork 的 `LICENSE` 首行）

```
Creative Commons Attribution-NonCommercial 4.0 International
```

上游 README badge：`License-CC%20BY--NC%204.0`；声明段第 1 条：「本项目遵守 CC-BY-NC 4.0 协议，**禁止一切商业使用**」。

⇒ **CC BY-NC 4.0 与 GPLv3 不兼容**，其文档文本**不得复制进本仓库**。

### 18.3 各项目 LICENSE 实测

| 项目 | 实测来源 | License |
|---|---|---|
| `AprDeci/bili-music` | `LICENSE` 首行 | **MIT**（`Copyright (c) 2026 AprDec`） |
| `despcy/Bilibili-MusicPlayer` | `LICENSE` 首行 | **MIT**（`Copyright (c) 2019 Chenxi Yang`） |
| `wood3n/biu` | `LICENSE` 首行 | **PolyForm Noncommercial License 1.0.0**（`Required Notice: Copyright (c) 2022–2025 wood3n`） |
| `bigbirdone/BiliBili_Music` | main/master × 5 个候选文件名 | ❌ **未找到** |
| `bb-music/flutter-app` | 同上 | ❌ **未找到** |
| `zsakvo/BiliMusic` | 同上 | ❌ **未找到** |

### 18.4 关键源码取证（`AprDeci/bili-music`）

`lib/core/net/net_config.dart`：

```dart
class NetConfig {
  static const String baseUrl = 'https://api.bilibili.com';
  static const String bmBaseUrl = 'https://bm.126386.xyz';   // ← 第三方后端
  static const Map<String, dynamic> defaultHeaders = {
    'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/131.0.0.0 Safari/537.36',
    'Referer': 'https://www.bilibili.com/',
    'Origin': 'https://www.bilibili.com',
  };
}
```

`lib/core/bili/sign/bili_wbi_signer.dart`：`_mixinKeyEncTab` 枚举值与本文 §3 的表**逐值相同**；使用 `Uri.encodeQueryComponent`（Dart，空格→`+`）。

`lib/feature/player/data/bili_player_repository.dart`：

```dart
.getJson('/x/player/wbi/playurl',
  queryParameters: {'bvid':..., 'cid':..., 'fnval': 4048, 'fnver': 0, 'qn': 80, 'fourk': 1},
  requiresWbi: true, ...)
```
音质 id 映射：`30251`→hires、`30280`→192K、`30232`→132K、`30216`→64K、`30250`→(杜比)。**未找到任何 URL TTL / `deadline` 处理。**

### 18.5 `despcy/Bilibili-MusicPlayer` 关键源码

```java
@GET("x/player/playurl?fnval=16&otype=json")
Call<DownloadInfoResponse> getDownloadInfo(@Query("avid") Integer avId, @Query("cid") Integer cid);

@GET("https://s.search.bilibili.com/cate/search?main_ver=v3&search_type=video&view_type=hot_rank&order=click&jsonp=jsonp")
Call<DynamicResponse> getTrendingPlaylist(@Query("cate_id") Integer cateId, ...);
```
```java
public static final Integer AUDIO_QUALITY_HIGH=30280;
public static final Integer AUDIO_QUALITY_LOW=30216;
public static final Integer ORIG=28, COVER=31, VOCAL=30, ELEC=194, PLAY=59, MV=193, LIVE=29, MISC=130;
```
**无 Wbi**（2019 年项目）；曲库基于**收藏夹**（`x/space/fav/nav` + `x/space/fav/arc`），**强依赖登录**。

### 18.6 `wood3n/biu` 关键源码

`src/service/audio-web-url.ts`（**音频区 APP 端点**）：

```ts
/**
 * 获取音频流URL（可获取付费音频）
 * GET https://api.bilibili.com/audio/music-service-c/url
 * 认证：APP（access_key）或 Cookie（SESSDATA）
 */
export interface AudioStreamUrlRequestParams {
  access_key?: string;   // APP 登录 Token
  songid: number | string;
  quality: number;       // 0=128K，1=192K，2=320K，3=FLAC
  privilege?: number;    // 必须为 2
  mid: number;
  platform: string;
}
```

⇒ 与本次实测**完全一致**（`songid` 参数名、`privilege=2`、音质代码语义）。

`src/service/request/wbi-sign.ts`：`mixinKeyEncTab` 与本文 §3 相同，用 `spark-md5` + `encodeURIComponent`（空格→`%20`）；调用处 `player-playurl.ts` → `/x/player/wbi/playurl` 带 `useWbi: true`。

歌词：`electron/ipc/api/lrclib-lyric.ts`（LRCLIB）+ `netease-lyric.ts`（ncm）—— **不用 B站自己的歌词**。

---

## 19. 环境的两个坑（复现者必读）

### 19.1 必须绕过本机代理

```bash
env -u https_proxy -u http_proxy curl ... https://api.bilibili.com/...
```

本机环境变量默认含 `http_proxy=https_proxy=http://127.0.0.1:10808`；**不绕过会让结论不可信**（走的是代理出口而非本机出口）。

### 19.2 GitHub 反而**需要**代理

本机直连 `api.github.com` 超时（`curl: (28) Connection timed out`），`data.jsdelivr.com` 与 `raw.githubusercontent.com` 时通时断。**社区实现调研部分全部走代理**，已在 `community-implementations.md` 开头注明。

---

## 20. 证据文件索引 ↔ 文档章节对照

| 证据文件 | 被引用于 |
|---|---|
| `01-nav.txt`、`93-wbi-key-global.txt` | `wbi-signature.md` §1、§6；`bili-audio-api.md` §0 |
| `02..12-songinfo-*.txt` | `bili-audio-api.md` §2 |
| `13..16-songurl-web-*.txt` | `bili-audio-api.md` §3.1–3.2 |
| `17..20-songurl-app-*.txt` | `bili-audio-api.md` §3.3–3.5；`RECOMMENDATIONS.md` §4.3 |
| `21..24-lyric-*.txt` | `bili-audio-api.md` §5；`RECOMMENDATIONS.md` §4.4 |
| `25..29-menu-*.txt` | `bili-audio-api.md` §6.5；`RECOMMENDATIONS.md` §1.3 |
| `30..32-tag/member/stat*.txt` | `bili-audio-api.md` §6.5 |
| `33..35-login-*.txt` | `bili-auth.md` §2.4 |
| `36..45-probe-*.txt` | `bili-audio-api.md` §6.1、§6.6 |
| `46..55-searchtype-*.txt` | `bili-audio-api.md` §6.3 |
| `56-search-video-tids3.txt` | `bili-audio-api.md` §6.4 |
| `57-search-all-v2.txt` | `bili-audio-api.md` §6.2 |
| `58-search-nonwbi-path.txt` | `wbi-signature.md` §4.1 |
| `59..61-newmusic/toplist*.txt` | `bili-audio-api.md` §6.8 |
| `62..63-pagelist/finger-spi.txt` | `bili-audio-api.md` §7.3；`bili-auth.md` §2.2 |
| `64-playurl-legacy-dash.txt` | `bili-audio-api.md` §7.1；`RECOMMENDATIONS.md` §2.3 |
| `65-playurl-wbi-nosign.txt` | `bili-audio-api.md` §7.2；`wbi-signature.md` §4.3 |
| `80-cdn-referer-and-ttl.txt` | `bili-audio-api.md` §4；`RECOMMENDATIONS.md` §2.4 |
| `90-wbi-ab.txt` | `wbi-signature.md` §3（**硬性验收**） |
| `91-wbi-golden-vector.txt` | `wbi-signature.md` §2（**硬性验收**） |
| `92-wts-tolerance.txt` | `wbi-signature.md` §5 |
| `93-wbi-key-global.txt` | `wbi-signature.md` §6.1 |
| `94-auth-boundary.txt` | `bili-auth.md` §2、§3 |
| `95-wbi-encoding-ab.txt` | `wbi-signature.md` §7.6 |
| `96-cdn-referer-exoplayer.txt` | `RECOMMENDATIONS.md` §4.9（**P0**） |
