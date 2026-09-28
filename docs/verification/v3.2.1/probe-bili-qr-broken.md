# v3.2.1 追加探针 · B站扫码登录「打开即失效」

> 现象（用户实测，v3.2.0）：打开 B站登录 → 二维码区直接显示「二维码已失效，请刷新」，
> 没有二维码、没有轮询过程、无法登录。
> 采集时间：2026-09-28 · 基线：`v3.2.0-gpl`（HEAD `122ab69`）
> 手段：源码逐行审计 + **真实端点在线抓取**（本机 curl，带应用同一组请求头）+ 与 `QqQrLoginDialog` 逐条对照。

---

## 0. 结论先行

**根因是界面层把「申请二维码」与「轮询到终态」串成了一行阻塞调用**：

```kotlin
// app/src/main/java/com/takahashirinta/ncrust/ui/components/BiliQrLoginDialog.kt:96-102（v3.2.0）
LaunchedEffect(generation) {
    qrBitmap = null
    state = login.start { state = it }                       // ← 阻塞到终态（最长 180 秒）
    login.qrCode?.url?.let { qrBitmap = generateQrBitmap(it) } // ← 只有终态之后才执行
}
```

`BiliQrLogin.start()` 的定义是 **`prepare()` + 有界轮询循环**，它只在
`CONFIRMED / EXPIRED / FAILED / CANCELLED` 时返回。于是：

1. 二维码**位图在整轮登录结束之前永远不会被生成**（`qrBitmap` 恒为 null）；
2. 二维码区在"没有位图"时走 `else` 分支，而那个分支只区分 `LOADING` 与"其它"，
   其它一律显示 [`strings.biliLoginExpired`](../../../app/src/main/java/com/takahashirinta/ncrust/ui/i18n/zh_CN.kt)
   = **「二维码已失效，请刷新」**；
3. 首帧 `state` 还是 `IDLE`（也不是 `LOADING`）⇒ **打开的那一刻就显示"已失效"**；
4. 轮询其实**在跑**（状态机会走到 `WAITING`，下方文案是「请用哔哩哔哩客户端扫码」），
   但二维码区一直写着"已失效"，用户读成"没有轮询"。

**接口、请求头、解析、状态机本身都没有问题**（§2/§3 有在线抓取与单测证据）。
修复方向见 §7：**生成 → 渲染 → 再轮询**，并把"没有位图"的文案按状态分开
（加载中 / 已失效 / 明确失败）。

---

## 1. 二维码生成接口是否被调用？

| 项 | 结论 |
|---|---|
| 调用点 | `BiliQrLogin.prepare()`（`BiliQrLogin.kt:130-154`）→ `api.qrGenerate()` |
| 谁调 `prepare` | `BiliQrLogin.start()`（`:170-171`），UI 的唯一入口是 `BiliQrLoginDialog` 的 `LaunchedEffect(generation)`（`:96-102`） |
| 是否真的发请求 | **是**。`qrGenerate()`（`BiliAuthApi.kt:388-403`）在 `Dispatchers.IO` 上 `BiliApi.getRaw(QR_GENERATE_URL)` |
| URL | `GET https://passport.bilibili.com/x/passport-login/web/qrcode/generate` |
| method | `GET` |
| headers | `User-Agent`（PC Chrome 120，`BiliApi.kt:154-156`）、`Referer: https://www.bilibili.com/`（`:158`）、`Origin: https://www.bilibili.com`（`:337`）；**不带 Cookie**（匿名接口） |
| body | 无 |

**所以"生成接口有没有被调用"这一问的答案是：被调用了，而且成功。**
用户看不到二维码不是因为它没请求，而是**结果没有被渲染**（§5）。

---

## 2. 生成接口返回了什么？（在线抓取，2026-09-28 12:02 GMT）

本机用**应用同一组请求头**直接打真实端点：

```
$ curl -i 'https://passport.bilibili.com/x/passport-login/web/qrcode/generate' \
      -H 'User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) … Chrome/120.0.0.0 Safari/537.36' \
      -H 'Referer: https://www.bilibili.com/' -H 'Origin: https://www.bilibili.com'

HTTP/2 200
content-type: application/json; charset=utf-8
bili-status-code: 0
{"code":0,"message":"OK","ttl":1,"data":{
    "url":"https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1\u0026callback=close\u0026qrcode_key=d5eec71c7dad33d3f3e26d83dbe96710\u0026from=",
    "qrcode_key":"d5eec71c7dad33d3f3e26d83dbe96710"}}
```

| 检查项 | 实测 |
|---|---|
| HTTP 状态码 | **200** |
| `code` | **0** |
| `data.qrcode_key` | 有（32 位十六进制） |
| `data.url` | 有（`account.bilibili.com/h5/account-h5/auth/scan-web?...`） |
| 是否被风控拦截 | **否**（无 412、无 `-352`、无验证码挑战） |

**轮询端点**（用同一批 key 抓两次，证明码表语义）：

```
# 刚申请的 key
{"code":0,"data":{"code":86101,"message":"未扫码"}}      → parseQrPoll → Pending  → State.WAITING
# 伪造的 key
{"code":0,"data":{"code":86038,"message":"二维码已失效"}}  → parseQrPoll → Expired  → State.EXPIRED
```

两层 `code` 都解析正确：外层 `code=0` 是 HTTP 语义，内层 `data.code` 是业务码
（`parseQrPoll` 的实现与之一致，`BiliAuthApi.kt:250-...`）。

---

## 3. 请求头是否完整？

| 头 | 是否发送 | 证据 |
|---|---|---|
| `User-Agent` | ✅ | `BiliApi.kt:335` `.header("User-Agent", UA)` |
| `Referer: https://www.bilibili.com/` | ✅ | `:336`；`REFERER` 常量在 `:158`，注释明确写了「**绝不能是网易云那个**」 |
| `Origin: https://www.bilibili.com` | ✅ | `:337` |
| `Cookie` | ❌（设计如此） | `getRaw` 只在 `cookieHeader` 非空时加；generate 不传 —— 与 v3.2.0 探针「generate 无 Set-Cookie、不需要 CookieJar」一致，**且在线抓取证明匿名可通** |

**结论：请求头完整，不是"缺 header 被拒"。** 这一条是任务书提醒里最可疑的方向，
但实测排除（§2 的 curl 用的就是应用同一组头，200 + `code:0`）。

---

## 4. 状态机初始状态是什么？

| 阶段 | 状态 | 触发点 |
|---|---|---|
| 打开浮层 | `IDLE` | `remember { mutableStateOf(BiliQrLogin.State.IDLE) }`（Dialog `:88`） |
| 进入 `LaunchedEffect` | `LOADING` | `prepare()` 的第一句 `emit(State.LOADING, onState)`（`BiliQrLogin.kt:137`） |
| `generate` 成功 | `WAITING` | `:152` |
| `generate` 失败 | `FAILED` | `:148`（**不会**进 `EXPIRED`） |

**状态机本身没有"请求发起前就 EXPIRED"的问题** —— `EXPIRED` 只有两条来路：
服务端 `86038`（`BiliQrLogin.kt:304`）与本地两条上限（`:181-183`）。

⚠️ 但**界面**上出现了 `EXPIRED` 的**文案**：因为二维码区的 `else` 分支把
"不是 LOADING 且没有位图"一律画成 `biliLoginExpired`（`BiliQrLoginDialog.kt:148-158`）。
**这是"文案层的误判"，不是状态机的误判** —— 这个区分很重要：修状态机是修不好它的。

状态流转日志（源码推演，`generation = 0` 的那一轮）：

```
t=0.00s  IDLE      二维码区：无位图 + 非 LOADING → 「二维码已失效，请刷新」   ← 用户看到的第一帧
t=0.01s  LOADING   二维码区：「搜索中…」  (strings.searchSourcePending)
t=0.3xs  WAITING   二维码区：无位图 + 非 LOADING → 「二维码已失效，请刷新」   ← 又回到"已失效"
        …  下方状态行是「请用哔哩哔哩客户端扫码」，二维码区却写着"已失效"
t=180s   EXPIRED   start() 返回；**此刻才** generateQrBitmap(url)
        但 usable = (bmp != null && state != EXPIRED) = false → 仍然不画
```

---

## 5. 二维码生成成功但渲染失败？

**两者都不是"失败"，而是"根本没执行到"**：

- 位图生成语句 `login.qrCode?.url?.let { qrBitmap = generateQrBitmap(it) }`
  排在 `login.start(...)` **之后**（Dialog `:100-101`），而 `start` 是长跑协程；
- 渲染判据 `usable = bmp != null && state != EXPIRED && state != FAILED`（`:139-141`）
  本身没错，但它依赖的 `bmp` 在 WAITING 期间恒为 null。

顺带发现的**第二个缺陷**（本次一并修）：`generateQrBitmap` 是

```kotlin
// QrLoginDialog.kt:236
internal fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? = runCatching { … }.getOrNull()
```

`runCatching{}.getOrNull()` **把异常与失败原因全部吞掉、且没有任何日志** ——
真正的渲染失败（ZXing 编码失败 / OOM）在日志里查不到任何痕迹。
（本次不引入行为变化，只补日志 + 让调用方能区分"没生成"与"生成失败"。）

---

## 6. 轮询逻辑是否过早启动？

**不是过早，而是"结果不可见"**：

- 轮询**在二维码渲染之前**就开始了（都在 `start()` 里面），但起点是 `prepare()` 成功之后，
  服务端语义上没问题；
- 轮询返回的 `code` **没有被误判**：`86101 → Pending → WAITING`、`86038 → Expired → EXPIRED`
  （`BiliAuthApi.parseQrPoll` + `BiliQrLogin.apply`），在线抓取（§2）与既有单测都钉住了这两条；
- 间隔 2s / 次数 90 / 总时长 180s 三条上限齐备（`MAX_POLLS * POLL_INTERVAL_MS == QR_TTL_MS`，
  由 `BiliAuthQrLoginTest` 的 `三个上限之间的关系` 用例断言）。

**真正的问题是顺序**：正确顺序是「生成 → **渲染** → 轮询」（QQ 就是这么做的，见 §9）。
现在的顺序是「生成 → 轮询到终态 → 渲染」，中间的 180 秒里用户什么都看不到。

---

## 7. 异常是否被静默吞掉？

| 位置 | 行为 | 判定 |
|---|---|---|
| `BiliAuthApi.qrGenerate` 的 `catch (e: Exception)` | `Log.w(TAG, "generate 请求失败", e)` + 返回 null | ✅ 有日志（但**UI 侧看不出区别**：null 一律走 `FAILED`） |
| `BiliAuthApi.qrGenerate` 解析失败分支 | `Log.w(..., "generate 解析失败 http=… 正文长度=…")` | ✅ 有日志 |
| `generateQrBitmap` | `runCatching{}.getOrNull()` | ❌ **静默**（无日志、调用方无法分辨原因） |
| `BiliQrLoginDialog` 的位图赋值 | 无日志 | ❌ 无法从日志判断"到底生成没生成" |

---

## 8. 是否与 v3.2.0 的改动有关？

**是 v3.2.0 新增代码自身的缺陷，与三跳协议无关。** 逐条核对任务书的提醒：

| 提醒 | 核对结果 |
|---|---|
| 「generate 无 Set-Cookie，不需要 CookieJar」 | ✅ 成立，且**在线抓取复现**（匿名 200 + `code:0`）。三跳协议的 generate 阶段确实不需要 CookieJar |
| 「generate 接口本身可能需要其他 header」 | ❌ 排除。UA / Referer / Origin 三个头都在发（`BiliApi.kt:335-337`），curl 用同一组头 200 |
| 与 v3.1.0 文档的差异 | v3.1.0 没做登录（只做了只读搜索），所以"差异"就是**新增的这条 UI 路径**；三跳协议的实现（`prepare`/`poll`/`finishLogin`）与探针文档一致 |

即：**协议层照做了，界面层把两步串成了一步。**

---

## 9. 与 `QqQrLogin` 的 7 条设计逐条对照（为什么 QQ 正常）

| # | 设计点 | `QqQrLoginDialog`（正常） | `BiliQrLoginDialog`（异常） |
|---|---|---|---|
| 1 | **申请与轮询是否分离** | ✅ **两个 `LaunchedEffect`**：`(generation)` 只负责 `qr = code`；`(qr, generation)` 才是轮询循环 —— 轮询**以 `qr` 为 key**，天然在拿到码之后才启动 | ❌ **一个 `LaunchedEffect`**：`state = login.start{}` 把两步串成一次阻塞调用 |
| 2 | **位图生成时机** | ✅ 拿到 `code` 立刻画（`:109 qr = code` → 渲染分支 `:191 if (bmp != null && status != EXPIRED)`） | ❌ 排在 `start()` 返回之后 |
| 3 | **"没有码"时的文案** | ✅ 三档分开：`status == EXPIRED → sourceQrExpired`、`FAILED → sourceQrFailed`、其它 → 加载态 | ❌ 只分 `LOADING` / "其它 → 已失效" |
| 4 | **TTL 从哪算** | ✅ `deadline = now + QR_TTL_SECONDS*1000` 在**拿到码之后**算（`:117`） | ⚠️ 在 `start()` 内部、`prepare()` 之后算（语义正确，但用户看不到码） |
| 5 | **失败/降级是否可见** | ✅ `degraded` 状态 + 提示（`:157-159`） | ⚠️ 只有 `lastFailure`，UI 没消费 |
| 6 | **有界轮询** | ✅ `while (now < deadline)` + 连续失败上限 | ✅ 三条上限齐备（状态机里） |
| 7 | **取消不是失败** | ✅ 协程取消即退出 | ✅ `cancel()` → `CANCELLED`，且 `CancellationException` 原样抛 |

**一句话**：QQ 的"申请"与"轮询"是两个独立 effect，B站的被合成了一次阻塞调用；
再加上二维码区的降级文案把"还没画出来"说成了"已失效"。
第 1 条是**根因**，第 3 条是**放大器**（没有第 3 条，用户至少会看到"搜索中…"）。

---

## 10. 根因与修复方向

### 根因（一句话）

> **位图生成被排在"轮询到终态"之后，而二维码区把"还没有位图"显示成「已失效」。**

三个可独立验证的缺陷：

| # | 缺陷 | 证据 |
|---|---|---|
| R1 | 申请/渲染/轮询被串成一次阻塞调用，位图 180 秒内不可能出现 | Dialog `:96-102` vs `BiliQrLogin.start` `:170-207` |
| R2 | 二维码区文案只分两档，"非 LOADING 且无位图" 一律说「已失效」 | Dialog `:148-158` |
| R3 | 位图生成失败被 `runCatching` 静默吞掉，且与"网络失败"共用同一条 UI 文案 | `QrLoginDialog.kt:236` + Dialog `:139-141` |

### 修复方向（与任务书建议的 6 条对应）

1. **顺序修正（R1）**：状态机新增 `poll(onState)`（只跑有界轮询，不重复申请），
   UI 改成 `prepare()` → 拿到 `url` → **生成并渲染位图** → 再 `poll()`；
   `start()` 保留为 `prepare() + poll()`（既有调用方与单测不变）。
2. **状态机语义（R2）**：`IDLE` 也算"加载中"；只有 `EXPIRED` 才显示「已失效」。
3. **失败文案（R3）**：`prepare()` 返回 `FAILED` 时显示明确错误（`biliLoginFailed`），
   不显示「已失效」；位图生成失败同样落"明确错误 + 可刷新"，不再伪装成过期。
4. **渲染兜底**：位图在 `Dispatchers.Default` 上生成（512×512 逐像素 `setPixel` 不该压主线程），
   失败时给出明确文案与刷新入口。
5. **日志（可观测性）**：`generate` 开始/成功/失败、位图生成成功/失败、轮询启动
   各打一条 `Log.i/w`，下次线上问题不必再靠推演。
6. **不动的部分**：三跳协议、请求头、`parseQrPoll` 的码表、有界轮询的三条上限 —— 全部保留，
   本轮只改"顺序 + 文案 + 日志"。

### 验收（与任务书一致）

- 打开登录浮层 → 二维码**在 1 秒内**出现；
- 扫码 → 轮询 → 登录成功（真机验证）；
- 过期时显示「已失效，请刷新」且刷新可用；
- **生成失败显示明确错误**（网络异常），不显示「已失效」；
- 单测覆盖：`poll` 不重复申请、`prepare` 失败不轮询、状态序列、文案分档（纯函数抽取后单测）。
