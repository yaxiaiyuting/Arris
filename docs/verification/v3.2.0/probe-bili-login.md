# B 站扫码登录探针 —— Ncrust v3.2.0 · P1

> **实测时间**：2026-09-28 18:39–18:44（+0800）
> **出口**：**直连**（本机可直连 B 站，`env | grep -i proxy` 为空；`curl -o /dev/null -w %{http_code} https://api.bilibili.com/x/web-interface/nav` → **200**）
> **身份**：**完全匿名**（除明确标注为「伪造 Cookie」的对照项外，一个 B 站 Cookie 都不带）
> **⚠️ 本轮没有可用的 B 站账号**：`data.code = 0`（扫码成功）与 `86090`（已扫码未确认）需要**真人拿手机扫一次**，
> 开发环境无法产生。本文把「实测」与「社区文档/构造样本」**逐条分开标注**，没有任何一处把文档包装成实测。
> **可复现**：命令逐条附在 §1–§6；原始响应（脱敏后）原样贴在下面。

---

## 0. 结论先行

| 问题 | 结论 | 实测? |
|---|---|---|
| 扫码登录是几跳？ | **两跳**：`qrcode/generate` 拿 `url` + `qrcode_key` → `qrcode/poll` 轮询到 `data.code = 0` | ✅ 端点与形状 |
| 业务码在哪一层？ | **`data.code`**（顶层 `code` 恒 0，HTTP 恒 200）。`0`/`86038`/`86090`/`86101` | ✅ 86101/86038，📄 0/86090 |
| 二维码有效期？ | **180 秒**：同一个 key 在 **+178s 仍是 `86101`**、**+189s 已是 `86038`** | ✅ |
| 凭据在哪？ | **两处都有**：轮询响应的 `Set-Cookie`（`SESSDATA`/`bili_jct`/`DedeUserID`/`DedeUserID__ckMd5`/`sid`）**与** `data.url`（`passport.biligame.com/crossDomain?…&SESSDATA=…`）的 query | 📄 成功态；✅ 两个载体的解析器已被单测钉住 |
| 过期怎么判？ | `nav` 回 `HTTP 200 + code:-101 + data.isLogin:false` —— **与匿名、与伪造 cookie 完全同一个形状** | ✅（伪造 token） |
| 登录能换来 FLAC 吗？ | **未验证**（无账号）。能实测的是：匿名 `quality=3` 被静默降级成 `type:2`（320K），`qualities[]` 里**根本没有 `type:3`** | ✅ 匿名侧；❌ 登录侧 |
| Wbi 密钥随登录变化吗？ | 匿名与带（伪造）`SESSDATA` 两次 `nav` 的 `wbi_img` **逐字节相同** ⇒ 强证据「不随登录变化」；真·登录态**未实测** | ✅ 伪造对照 |
| 能力复用 QQ 那套吗？ | **状态机形状完全可复用**（枚举语义/有界轮询/取消语义/错误分类），**协议细节全部要换**（见 §2） | ✅ |

---

## 1. 探针问题 1：B 站扫码登录流程（真实响应形状）

### 1.1 申请二维码（实测）

```bash
curl -s -D - 'https://passport.bilibili.com/x/passport-login/web/qrcode/generate' \
  -H 'User-Agent: Mozilla/5.0 (Linux; Android 7.0; SM-G9209) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36' \
  -H 'Referer: https://www.bilibili.com/'
```

**响应头（逐字）**：

```http
HTTP/2 200
date: Mon, 28 Sep 2026 10:40:27 GMT
content-type: application/json; charset=utf-8
content-length: 245
bili-status-code: 0
bili-trace-id: 0e58e3c8c26aba44
cpu_usage: 90
x-bili-trace-id: 6ebcdc25145be9aa0e58e3c8c26aba44
expires: Mon, 28 Sep 2026 10:40:26 GMT
cache-control: no-cache
x-cache-webcdn: BYPASS from blzone01
```

**响应体（逐字，未脱敏 —— 它不含任何凭据）**：

```json
{"code":0,"message":"OK","ttl":1,"data":{"url":"https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1\u0026callback=close\u0026qrcode_key=4789f53cc92fcb2191a94ba57aed76c3\u0026from=","qrcode_key":"4789f53cc92fcb2191a94ba57aed76c3"}}
```

三个要点：

1. **`data.url` 是二维码内容**（一个 URL 字符串，不是 PNG）⇒ 客户端用 ZXing 把它编码成二维码就完事，
   **不需要** QQ 那条 `ptqrshow` 的图片通道，也不需要 PNG 头校验；
2. `data.qrcode_key` 恒为 **32 字符**（本轮 **4 次**生成，每次都逐条 grep 过响应头：长度一律 32、`Set-Cookie` 一律 0 条）；
3. ⚠️ **与 v3.1.0 `bili-auth.md` §3.1 的一处不符**：那里写「响应同时 `Set-Cookie: qrcode_key=<…>`」，
   **本轮 4/4 次生成都没有任何 `Set-Cookie`**（其中 4 次逐条 `grep -c '^set-cookie'` 都是 0；上面那份响应头是逐字的）。结论：**轮询只需要 query 参数，
   不存在必须回带的 cookie** —— 这一点决定了 B 站侧不需要 QQ 那样的内存 `CookieJar`。

每次 `generate` 都是**新 key**（第二次调用拿到 `eeb677cb2f00a9d9008296f1f72e3282`，与第一次不同），
所以「刷新二维码」= 重新调一次 `generate`。

### 1.2 轮询（实测：未扫码 / 已失效 / 缺参数）

```bash
curl -s -D - "https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=$KEY" \
  -H 'User-Agent: <同上>' -H 'Referer: https://www.bilibili.com/'
```

| 输入 | `data.code` | 响应体（逐字） |
|---|---|---|
| 刚生成的 key（第 1 次） | **`86101`** | `{"code":0,"message":"OK","ttl":1,"data":{"url":"","refresh_token":"","timestamp":0,"code":86101,"message":"未扫码"}}` |
| 伪造 key `0000…`（32 个 0） | **`86038`** | `{"code":0,"message":"OK","ttl":1,"data":{"url":"","refresh_token":"","timestamp":0,"code":86038,"message":"二维码已失效"}}` |
| **不带** `qrcode_key` 参数 | **`86038`** | 与上一行**逐字节相同** |
| `qrcode_key=`（空值） | **`86038`** | 与上一行**逐字节相同** |

**HTTP 层**：四种情况全部 `HTTP/2 200` + `bili-status-code: 0` + `content-type: application/json`，
**没有任何 `Set-Cookie`**。业务码只在 `data.code`。

⇒ 两个实现要点：
- **空 key 不是错误，会被服务端当成「已失效」** —— 客户端不该发这种请求（发了也只是白费一次往返，
  而且会把「本地状态错误」伪装成「二维码过期」）。`BiliAuthApi.qrPoll` 对空 key **直接返回失败、不发请求**；
- **不要让「顶层 `code == 0`」参与状态判断**：它恒为 0，写成顶层就会读到一个永远的成功。

### 1.3 二维码有效期（实测：180 秒）

同一个 `qrcode_key`（`6b291bdb61649dedfa307930ef310998`）每 10 秒轮询一次的时间序列：

```
t=+1s    {"code":0,"message":"OK","ttl":1,"data":{"url":"","refresh_token":"","timestamp":0,"code":86101,"message":"未扫码"}}
t=+11s   … 86101 …
t=+22s   … 86101 …
…（每 10 秒一次，全部 86101，中间省略）…
t=+168s  … 86101 …
t=+178s  … 86101 …
t=+189s  {"code":0,"message":"OK","ttl":1,"data":{"url":"","refresh_token":"","timestamp":0,"code":86038,"message":"二维码已失效"}}
STOP at +189s code=86038
```

⇒ **TTL ∈ (178s, 189s]，标称 180 秒**（社区文档 `bilibili-API-collect` 的 QR 页也写「密钥超时为 180 秒」，
两处一致）。客户端取 `QR_TTL_MS = 180_000`、`POLL_INTERVAL_MS = 2_000`、`MAX_POLLS = 90`，
三者**互相对齐**（有单测钉住这个关系：改一个忘了改另一个就变红）。

### 1.4 成功态（`data.code = 0`）—— **本轮未实测，形状来自社区文档**

**为什么没实测**：需要一部装了 B 站 App 的手机扫一个真账号，开发环境两个都没有（§7 未验证清单第 1/2 条）。

**社区文档（BAC / `bilibili-API-collect` QR 页）记录的形状**：

```json
// data.code = 0（扫码并确认成功）
{"code":0,"message":"0","ttl":1,"data":{
  "url":"https://passport.biligame.com/crossDomain?DedeUserID=***&DedeUserID__ckMd5=***&Expires=***&SESSDATA=***&bili_jct=***&gourl=https%3A%2F%2Fpassport.bilibili.com",
  "refresh_token":"***","timestamp":1662363009601,"code":0,"message":""}}

// 同一响应的响应头
set-cookie: SESSDATA=***; Path=/; Domain=bilibili.com; Expires=…; HttpOnly; Secure
set-cookie: bili_jct=***; Path=/; Domain=bilibili.com; Expires=…
set-cookie: DedeUserID=***; Path=/; Domain=bilibili.com; Expires=…
set-cookie: DedeUserID__ckMd5=***; Path=/; Domain=bilibili.com; Expires=…
set-cookie: sid=***; Path=/; Domain=bilibili.com; Expires=…

// data.code = 86090（已扫码未确认）
{"code":0,"message":"0","ttl":1,"data":{"url":"","refresh_token":"","timestamp":0,"code":86090,"message":"二维码已扫码未确认"}}
```

⇒ **凭据有两个载体，客户端必须都处理**：
1. `Set-Cookie`（浏览器语义的那一份）；
2. `data.url` 的 query（跨域登录用的那一份）。

`BiliAuthApi.parseQrPoll(body, setCookieHeaders)` 的实现方式：两边各自解析成 `BiliCredential`，
再**逐字段合并**（非空覆盖空）—— 只读一边的实现在真实成功态里有一半概率拿不到 `SESSDATA`。
解析**只认白名单字段**、**不做 URL 解码**（真实 `SESSDATA` 里的逗号是 `%2C` 的形式，
解码成 `,` 之后服务端比对的就是另一个字符串）。这两条都有单测钉住。

### 1.5 三个接口的最终形状（端点常量已落进代码）

| 步骤 | 方法与 URL | 业务码位置 |
|---|---|---|
| 申请二维码 | `GET https://passport.bilibili.com/x/passport-login/web/qrcode/generate` | 顶层 `code`（0 = 成功） |
| 轮询 | `GET https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=<32hex>` | **`data.code`** |
| 校验 | `GET https://api.bilibili.com/x/web-interface/nav` + `Cookie` | 顶层 `code`（0 = 成功，-101 = 未登录） |

---

## 2. 探针问题 2：复用现有扫码登录框架的可行性（逐条）

对照对象：QQ 侧 `qq/QqQrLogin.kt` + `qq/QqQrClient.kt` + `ui/components/QqQrLoginDialog.kt`（网易云侧 `auth/QrPair*` 是**手机扫平板**的局域网传票，与平台扫码登录不同源，只作旁证）。

### 2.1 **直接可用**（形状照搬，语义一一对应）

| # | `QqQrLogin` / `QqQrLoginDialog` 的设计 | B 站侧的落点 | 证据 |
|---|---|---|---|
| 1 | 状态枚举语义：`WAITING`/`SCANNED`/`CONFIRMED`/`EXPIRED`/`FAILED`，**过期是独立状态**（UI 要能点「刷新二维码」） | `BiliQrLogin.State` 前五个逐字同名同义（另加 `IDLE`/`LOADING`/`CANCELLED` 三个入口/终态） | 码表 1:1（下表） |
| 2 | 码表显式且**注释里钉死**「65/67 曾写反 ⇒ 用户一扫就显示已过期」 | `BiliAuthApi.POLL_CODE_*` 四个常量 + `when` 的 `else → FAILED`（不落到「继续轮询」） | 86101/86090/86038/0 全部有单测样本 |
| 3 | 「服务端明确回绝」与「网络抖动」**分开**（`PollResult.Unavailable` vs `NetworkError`） | `BiliQrPoll.Failed.Reason` 三档：`MALFORMED`/`SERVER`（重试无意义）vs `NETWORK`（抖动） | 连续上限 3 vs 5，另有「抖动 2 次后恢复 ⇒ 全程不闪 FAILED」的用例 |
| 4 | **有界轮询**：`deadline = now + TTL` 的 `while` + 固定 `delay(2s)`，绝不无限重试 | 总时长 180s + 间隔 2s + 次数 90，**三个上限任一先到就停** | 单测断言 `pollCount == MAX_POLLS == api.pollCalls`，且假时钟恰好走到 180_000ms |
| 5 | **「取消不是失败」**：每个 `catch` 原样抛出 `CancellationException`；结果类型里没有「取消」这一档 | `State.CANCELLED` 与 `FAILED` 分开；`sleep`/网络两侧的取消都穿透 | 两条用例：cancel → CANCELLED 且只发 1 次请求；`CancellationException` 原样穿透 |
| 6 | 常量显式（`QR_TTL_SECONDS`/`QR_SIZE_PARAM`），不散落字面量 | `POLL_INTERVAL_MS`/`QR_TTL_SECONDS`/`QR_TTL_MS`/`MAX_POLLS`/两个连续失败上限 | 单测钉住常量之间的关系 |
| 7 | 解析失败 ≠ 登录失败（`parsePtuiCb` 返回 null，由调用方决定继续还是降级） | `BiliQrPoll.Failed(detail)` + `lastFailure` 对 UI 可见（界面能说清「为什么在重试」） | 源码/行为用例 |
| 8 | UI 外壳：ZXing 渲染二维码 + 弹窗 + `LaunchedEffect` 驱动 | **完全可复用**（二维码内容从「PNG 字节」换成「URL 字符串」，ZXing 的入口不同而已） | 由编排者接线（本 P1 不写 UI） |

**码表对照**（这是「状态机同构」的可执行形式）：

| 语义 | QQ（`ptuiCB` 第一参数） | B 站（`data.code`） |
|---|---|---|
| 等待扫码 | `66` | `86101` |
| 已扫未确认 | `67` | `86090` |
| 成功 | `0` | `0` |
| 已过期 | `65` | `86038` |
| 其它 | `68`/`22005`/… → `FAILED` | 未知码 → `FAILED` |

### 2.2 **必须换掉**（一点都不能复用）

| 维度 | QQ 互联 | B 站 |
|---|---|---|
| 二维码载体 | `ptqrshow` 返回 **PNG 二进制**（要校验 `0x89 P N G`） | `data.url` 是**字符串**（客户端自己编码成二维码） |
| 轮询参数 | `ptqrtoken = hash33(qrsig)`（算法细节是坑：初值 0 不是 5381） | 直接 `qrcode_key`，**没有任何签名/哈希** |
| 会话 cookie | `qrsig` 在 `Set-Cookie` 里，**必须回带** ⇒ 需要内存 `CookieJar` | **实测三态都没有 `Set-Cookie`** ⇒ 不需要 jar（`BiliApi.getRaw` 是无状态 GET） |
| 成功那一跳 | `ptuiCB('0',…,url)` → **WebView 换票**（HTTP 侧拿不到登录态） | `Set-Cookie` + `data.url` **直接给凭据**，不需要 WebView |
| 状态码 | 顶层字符串 `'66'` | **`data.code` 里的整数**（顶层 `code` 恒 0 —— 最容易写错的一处） |
| 凭据 | `uin`/`qm_keyst`/`qqmusic_key`（要 `QqCookie.merge`） | `SESSDATA`/`bili_jct`/`DedeUserID`/`DedeUserID__ckMd5`/`sid` |
| CSRF | 无 | `bili_jct`（社区文档：写操作必带；本版**不做任何写操作**，只存不用） |
| 过期判据 | `65` | `86038`（服务端）**或**本地 180s 上限先到 |

网易云侧（`QrPair*`）与本任务无关：它解决的是「手机扫平板、把手机 cookie 经局域网递给平板」，
是**同账号跨设备传票**，不是平台侧扫码登录；唯一可借鉴的是「有界轮询 + 状态三态」的写法，
而那部分 QQ 侧已有更贴近的形状（且 QQ 侧自己踩过 403 与码表写反两个坑）。

**结论：状态机整体复用 QQ 的形状，协议层 100% 重写。** 这正是本 P1 交付的
`BiliQrLogin`（状态机）+ `BiliAuthApi`（协议/解析）两层结构。

---

## 3. 探针问题 3：登录后的收益（能实测的与不能实测的）

| 收益 | 本轮结论 | 依据 |
|---|---|---|
| **FLAC 无损（`quality=3`）** | **未验证（登录侧）**。能实测的是**匿名侧**：`quality=3` 被**静默降级**成 `type:2`（320K），且 `qualities[]` 里**根本没有 `type:3` 条目** | §3.1 原始响应 |
| **更高码率** | 匿名已实测到 320K（APP 端点 `quality=2` → `type:2`，`size=10374528`）；**没有比它更高的档位可观测** | §3.1 |
| **收藏夹同步**（`collections/*`） | **未验证**。匿名返回 `4511003 用户未登录`；**伪造 `SESSDATA` 返回完全一样** ⇒ 服务端确实在校验凭据，必须真实登录才能观测 | §3.2 |
| 投币 / 收藏 / 点赞 | **本版不做**（合规结论，见 §5 第 4 条） | — |
| 付费音频完整版 | **未验证**（与 `access_key` 有关，本版不做 APP token 路线） | — |

### 3.1 音频区取流：匿名 `quality=3` 的真实响应（实测）

```bash
curl -s 'https://api.bilibili.com/audio/music-service-c/url?songid=39&quality=3&privilege=2&mid=0&platform=pc' \
  -H 'User-Agent: <桌面 Chrome UA>' -H 'Referer: https://www.bilibili.com/'
```

```json
{"code":0,"msg":"success","data":{"type":2,"size":10374528,
 "qualities":[{"type":2,"desc":"高品质","size":10374528,"bps":"320kbit/s","tag":"HQ","require":0,"requiredesc":""},
              {"type":1,"desc":"标准","size":6244629,"bps":"192kbit/s","tag":"","require":0,"requiredesc":""},
              {"type":0,"desc":"流畅","size":4179753,"bps":"128kbit/s","tag":"","require":0,"requiredesc":""}]}}
```

- 请求 `quality=3`（FLAC）⇒ **回 `type:2`**：静默降级；
- `qualities[]` **只有 2/1/0 三档，没有 `type:3`**；
- 带上伪造 `SESSDATA`（`SESSDATA=fake123; bili_jct=fakejct; DedeUserID=12345`）再请求一次：
  **`type` 仍是 2、`qualities[]` 仍没有 `type:3`、`size` 一模一样**。

⇒ 这两条一起说明：**凭据无效时请求与匿名完全等价**。所以「登录后能否出现 `type:3`」
**本轮无法回答** —— 需要一份**真实（最好是大会员）的 `SESSDATA`**。
代码侧对此的处置是：不把「匿名只能 320K」写死（`BiliQuality` 的最高档仍是 `qn=3`），
拿不到就逐级下探，并由 `SongUrlResult.levelFromFile` **如实标出降级**。

### 3.2 收藏夹端点：伪造 cookie 不被接受（实测）

```bash
curl -s 'https://api.bilibili.com/audio/music-service-c/web/collections/list?uid=14452610&pn=1&ps=5' -H '<普通头>'
→ {"code":4511003,"data":null,"message":"用户未登录","msg":"用户未登录"}
curl -s '<同一 URL>' -H '<普通头>' -H 'Cookie: SESSDATA=fake123; bili_jct=fakejct; DedeUserID=12345'
→ {"code":4511003,"data":null,"message":"用户未登录","msg":"用户未登录"}   ← 逐字节相同
```

⇒ **伪造/失效凭据不能冒充登录**（与 v3.1.0 §2.4 的结论一致，本轮复现）。

---

## 4. 探针问题 4：`SESSDATA` 的存储与过期处理

### 4.1 过期表现（实测：无效凭据）

```bash
curl -s 'https://api.bilibili.com/x/web-interface/nav' -H '<桌面 UA>' -H 'Referer: https://www.bilibili.com/'
→ {"code": -101, "message": "账号未登录", "ttl": 1, "data": {"isLogin": false,
     "wbi_img": {...}, "ip_region": "CN"}}     HTTP 200，无 Set-Cookie
```

带伪造 `SESSDATA` 的**同一条请求**：响应体**逐字节相同**（`code:-101`、`isLogin:false`）。

⇒ 客户端判据（写进 `BiliAuthApi.parseNav`）：

| 响应 | 判定 | 动作 |
|---|---|---|
| `code:0` + `data.isLogin:true` | `LoggedIn` | 显示昵称/会员，继续用 |
| `code:-101`（或 `code:0` + `isLogin:false`） | `NotLoggedIn` | **需要重新扫码** |
| `code:-352` / `-403` / 其它 | `Failed(code,message)` | **不是过期** —— 把它们当成「重新登录」会让用户反复扫码却永远登不进去 |
| 非 JSON / 网络异常 | `Unknown` | 提示网络问题，**不清凭据** |

⚠️ **真·「用久了过期」的 `SESSDATA` 未实测**（要等一个真 token 自然过期，或有一个真账号）。
能确证的是：**匿名 / 伪造 / （极可能）过期 走的是同一条判定路径**，服务端回答的形状一致。
社区文档抓包显示 `Set-Cookie` 的 `Expires` 是 6 个月后（2022-09-05 → 2023-03-04），
但**本轮没有真实抓包，不作为结论**。

### 4.2 存储方案（已落进代码，逐条给理由）

| 项 | 决定 | 理由 |
|---|---|---|
| prefs 文件 | **`ncrust_bili_prefs`**（独立） | 与 `ncrust_prefs`（网易云）/`ncrust_qq_prefs`（QQ）/`ncrust_settings`（开关）分开：登出一家不能清掉另一家；「哪一家过期了」必须能单独判定；B 站音源默认关闭时它的登录态仍要能独立存在 |
| 字段名 | **显式常量**（铁律 17）：`SESSDATA`/`bili_jct`/`DedeUserID`/`DedeUserID__ckMd5`/`sid`/`uname`/`mid`/`vip_type`/`vip_status`/`profile_at` | 有源码扫描守卫逐字比对；`clear` 清掉的键集合必须**等于**写入集合（漏清一个的症状是「换账号后还显示上一个账号的昵称」） |
| 进程内镜像 | `@Volatile`；`init(context)` 载入、`save`/`clear` 同步 | 网络层（`BiliApi` 是 `object`）没有 `Context`；镜像为空 = 匿名 |
| 注入请求头 | 业务请求（搜索/取流/歌词/详情）在 `BiliApi.get` 里合并 `SESSDATA=…; bili_jct=…; DedeUserID=…`；**扫码流程自己不发** | 「登录之后请求带上身份」是登录唯一有意义的落点；未登录/未 init 时**一个 Cookie 都不带**（有行为用例：匿名时 `Cookie` 头为 null） |
| 日志 | **只打字段名与长度** | `BiliCredential.toString()` 被改写成 `SESSDATA len=…`（结构性：任何字符串插值、崩溃上报都带不出凭据）+ 源码扫描守卫（任何 `Log.` 行都不许直接插值凭据变量） |
| 上传 | **不上传任何服务器** | 与网易云/QQ 同样的约定；`SESSDATA` 只在本机 |
| 资料刷新 | TTL **10 分钟**（`BiliAuthStore.PROFILE_TTL_MS`），未登录恒 `false` | 与 `QqAuthStore` 同口径；未登录时不该发一个注定 `-101` 的请求 |

`bili_jct` **一起存**（社区文档明确它与 `SESSDATA` 同时下发、写操作缺它必失败），
但**本版没有任何一处用它发请求** —— 存下来是为了将来做写操作时不至于要用户重新登录一次。

---

## 5. 探针问题 5：Wbi 签名密钥是否随登录变化（实测比对）

两次 `nav`，唯一变量是**有没有带（伪造的）`SESSDATA`**：

```bash
# A：匿名
curl -s 'https://api.bilibili.com/x/web-interface/nav' -H '<桌面 UA>' -H 'Referer: https://www.bilibili.com/'
# B：带伪造 SESSDATA
curl -s 'https://api.bilibili.com/x/web-interface/nav' -H '<桌面 UA>' -H 'Referer: https://www.bilibili.com/' \
  -H 'Cookie: SESSDATA=fake123%2Cfake; bili_jct=fakejct; DedeUserID=12345'
```

| 观测项 | A（匿名） | B（带伪造 SESSDATA） | 是否相同 |
|---|---|---|---|
| 顶层 `code` | `-101` | `-101` | ✅ |
| `data.isLogin` | `false` | `false` | ✅ |
| `wbi_img.img_url` | `https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png` | **同一个** | ✅ 逐字节 |
| `wbi_img.sub_url` | `https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png` | **同一个** | ✅ 逐字节 |
| `data.ip_region` | `CN` | `CN` | ✅ |

⇒ **判「Wbi 密钥不随登录状态变化」有强证据**：
① 未登录（`-101`，即鉴权未通过）时服务端**照样下发**完整的 `wbi_img`；
② 换一份 Cookie 之后这两个 key 一个字节都没变。

⚠️ **诚实边界**：B 组用的 `SESSDATA` 是伪造的（服务端判 `-101`），所以严格说这是
「**鉴权失败路径下** wbi_img 不变」的实测，**不是**「真·登录态下 wbi_img 不变」的实测 ——
后者需要真账号（§7 未验证清单第 6 条）。考虑到 `wbi_img` 在鉴权前就已下发、
且它对所有匿名用户都是同一份（全站统一），实现上按「**不需要因登录而刷新密钥**」处理：
`BiliApi` 的密钥缓存（内存 + 6h TTL + `-352` 强制刷新一次）**本版一行未改**。

---

## 6. 探针问题 6：匿名 vs 登录的 API 差异（实测响应差异）

| 端点 | 匿名 | 带伪造 `SESSDATA` | 差异 |
|---|---|---|---|
| `api.bilibili.com/x/web-interface/nav` | `{"code":-101,"message":"账号未登录","data":{"isLogin":false,"wbi_img":{…},"ip_region":"CN"}}` | **逐字节相同** | 无 |
| `api.bilibili.com/audio/music-service-c/url?songid=39&quality=3` | `code:0`、`type:2`、`qualities[]` 三档（无 `type:3`） | **`type:2`、`size` 完全相同** | 无 |
| `…/web/collections/list?uid=…` | `{"code":4511003,…"用户未登录"}` | **逐字节相同** | 无 |

**匿名 `nav` 的 `data` 里到底有什么**（这决定了「未登录时不许假设字段存在」的实现纪律）：

```json
{"isLogin": false,
 "wbi_img": {"img_url": "https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
             "sub_url": "https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"},
 "ip_region": "CN"}
```

—— **没有** `uname`、**没有** `mid`、**没有** `vipStatus`/`vipType`、**没有** `money`。
所以 B 站侧的资料解析一律给默认值（`null` / `0`），**绝不假设字段存在**
（`BiliAuthApi.parseNav` 有「匿名样本 ⇒ NotLoggedIn，一个字段都不读崩」的用例）。

⇒ **结论（本轮的诚实版本）**：本轮能实测的差异只有「服务端认不认这份凭据」，
而且**伪造的凭据一律不被接受**。真正的「匿名 vs 登录」差异（FLAC、收藏夹、会员标识）
**必须用真实账号才能观测**，本轮没有。

---

## 7. 对 v3.1.0「本版不做登录」四条理由的逐条回应

v3.1.0 的四条理由原文在 `docs/verification/v3.1.0/bili-research/RECOMMENDATIONS.md` §3.3
（`bili-auth.md` §5.1 是同一组的另一个版本）。本版（v3.2.0 · P1）**推翻的是「不做」这个决定，
不是推翻这四条理由本身** —— 四条里有三条今天依然成立，所以本版的实现方式是被它们约束出来的。

| # | v3.1.0 的理由 | v3.2.0 的回应（逐条） |
|---|---|---|
| 1 | **匿名已覆盖完整播放链路**（搜索→播放→歌词），登录只换来 3 项非必要能力 | **仍然成立，而且本版没有削弱它**：B 站音源依旧默认关闭（`BiliPrefs.DEFAULT_ENABLED=false`），登录是**完全可选**的独立入口；未登录时请求与 v3.1.0 **逐字一致**（有行为用例断言匿名时 `Cookie` 头恒为 `null`）。本版做登录的理由不是「匿名不够用」，而是「**用户自己的账号能换到的东西（收藏夹 / 大会员档位）现在有一条可选的路**」，并且这条路的成本被压到「三个只读接口 + 一份独立存储」 |
| 2 | **登录把风险从出口 IP 升级到用户账号** | **完全成立，本版不辩解，只收敛**：① 只做**只读**接口，不投币/不收藏/不点赞/不评论；② 凭据只存本机、`toString` 结构性脱敏、日志只打长度（源码扫描守卫）；③ 登录入口必须由用户主动触发，且默认没有任何登录态。**账号级风控风险没有消除**，只能如实告知（UI 文案建议见交付报告 §6） |
| 3 | **登录会牵动三处既有结构**：`CookieManager`、`UserScreen`、`MusicSource.loginSources` | 本版把牵动面压到**两处**，且都不在本 P1 里：① `CookieManager` **完全不动** —— B 站用独立的 `ncrust_bili_prefs`（`QqAuthStore` 已经证明这条路可行）；② `MusicSource.loginSources` **保持 B 站不在其中** —— 这是 v3.1.0 有意的设计（`otherThan()` 的语义是「提示换个源登录」），本版**不改**它，B 站登录是它自己的入口；③ 只剩 `UserScreen`/设置页的接线，那由编排者负责（本 P1 交付的是认证核心，不含 UI） |
| 4 | **写操作的失败形态是 HTTP 200 + 业务码**，必须正确处理 `bili_jct`，且最容易被法务/风控盯上 | **本版不做任何写操作**。`bili_jct` 只**存不用**（社区文档明确它与 `SESSDATA` 同时下发，写操作缺它必失败）。将来若要写，判据已经记在这里：成败**只看 `body.code`**（HTTP 恒 200），`bili_jct` 必须与 Cookie 内的值一致 |

**另外两处 v3.1.0 文档需要纠正的地方**（本轮的实测反例）：

1. `bili-auth.md` §3.1 写「`generate` 响应同时 `Set-Cookie: qrcode_key=<…>`」——**本轮 3/3 次都没有任何 `Set-Cookie`**（§1.1 的响应头逐字）。后果：B 站侧**不需要**内存 `CookieJar`（QQ 侧需要，因为 `qrsig` 必须回带）；
2. `bili-auth.md` §3.2 把成功态的 `Set-Cookie` 字段列为「文档推断」—— 本轮仍是推断（无账号），但**代码已经按「两个载体都读」实现**，并有单测样本把两个载体分别钉住。

---

## 8. 未验证项清单（诚实清单）

| # | 未验证项 | 为什么没验证 |
|---|---|---|
| 1 | `data.code = 0` 成功态的**真实响应体与 `Set-Cookie` 字段集合** | 需要真人拿手机扫一个真账号；形状来自 BAC 社区文档（§1.4，已标注） |
| 2 | `data.code = 86090`（已扫码未确认） | 同上（扫了但不确认，需要人工操作） |
| 3 | 登录 / 大会员后 `qualities[]` 是否出现 `type:3`（FLAC） | **本轮没有可用的 B 站大会员账号**。能实测的只有匿名侧：`quality=3` 被降级为 `type:2`、`qualities[]` 无 `type:3` |
| 4 | 登录后 `collections/*` 的真实返回结构 | 同上（伪造 cookie 与匿名都回 `4511003`） |
| 5 | **真实 `SESSDATA` 的过期表现与有效期** | 需要一个真 token 自然过期；实测到的是「匿名 / 伪造」两种无效凭据的形状（与预期一致：`code:-101`） |
| 6 | **真·登录态下 `wbi_img` 是否与匿名一致** | 实测到的是「鉴权失败路径下不变」（§5）；真登录态需真账号 |
| 7 | 登录态下音频 CDN 是否放宽 `Referer` 校验 | v3.1.0 已知**匿名**必须带 B 站 Referer（否则 403）；登录态未测（本版不改 CDN 侧一行代码） |
| 8 | 真机端到端扫码（手机扫屏幕 → 落盘 → 取流） | 需要真人 + 真账号；本 P1 的全部单测都是**离线**的（假传输层 + 假时钟） |
| 9 | `refresh_token` 的刷新机制 | 响应里有这个字段（社区文档），但**本轮没有成功态样本**，且本版**不实现刷新**（也不把它带进内存/磁盘：少一个可泄露的凭据） |
| 10 | 登录态下搜索/取流的风控表现（`-352`/`412` 是否变化） | 需要真账号；本版按「不因登录而刷新 wbi 密钥」处理（§5） |
| 11 | 登录态下 App 端真实运行表现（无崩溃、无 StrictMode 违规） | 本 P1 未占用真机（S6 是共享资源）；认证层的线程形状由源码扫描 + 假传输层行为测守住（`withContext(Dispatchers.IO)`） |
