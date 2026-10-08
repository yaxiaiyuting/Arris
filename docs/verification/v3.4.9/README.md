# v3.4.9 验证记录 —— qm 登录态：一周左右掉登录的根因与静默续期

> 本目录只放**这一次修复的可复现证据**。结论先行，每条都标出「这是实测还是推断」。

## 报障原话

> 「qq 音乐登陆身份在一周左右就会掉，重新登录很麻烦，尝试解决」

---

## 一、根因：**不是腾讯的票据不稳定，是本客户端从来没有续过期**

### 1.1 客户端把能换新票的字段全丢了（本机落盘样本，实测）

`.scratch/pcl-prefs/shared_prefs/ncrust_qq_prefs.xml`（PCL110 真机备份，2026-09-27）：

```
qq_cookie = uin=1152921504891728649;
            qqmusic_uin=1152921504891728649;
            qqmusic_key=W_X_63B0…（长票据）;
            qm_keyst=W_X_63B0…（同上）;
            psrf_musickey_createtime=1790277358
```

这份 cookie 由 `QqPhoneLogin.cookieFromCredential` 产出，它**只写 5 个字段**。
而 `Login` 的响应 `data` 里还有 `refreshKey` / `refreshToken` / `accessToken` /
`openid` / `unionid` / `loginType` / `keyExpiresIn` / `needRefreshKeyIn` ——
**一个字节都没有落盘**。

`psrf_musickey_createtime=1790277358` = 2026-09-24 12:35 (+08:00)，与用户描述的
「一周左右」同量级；而腾讯在同一个响应里下发的 `keyExpiresIn`（票据寿命）正是
判断它何时到期的**唯一权威依据**，也被丢掉了。

**结论**：票据一到期，客户端手里没有任何可用来换新票的东西 ⇒ 只能让用户重新登录。
这条链路上没有任何一步是概率的 —— 丢掉续期凭证等于把「可续期的会话」降级成
「一次性票据」。

### 1.2 不止是「没落盘」，也「没有续期请求」

全仓检索 `loginMode` / `refresh_token` 的**实现**（不是 URL 白名单）：

| 检索 | v3.4.8 前的命中 |
|---|---|
| `loginMode` 出现次数 | **2 次**，都是注释与 `phoneLogin(loginMode=1)` |
| `refresh_token` 出现次数 | **1 次**，在 `QqCookie.requestCookie` 的**字段白名单**里 |
| 任何调用 `Login` 且 `loginMode != 1` 的代码 | **0** |

`QqRequests.phoneLogin` 的注释里其实早就写着「`2` 是 refresh_token 续期」
（v2.1.1 留下的一条知识），但因为凭证没落盘，这条知识一直没能变成功能。

---

## 二、续期怎么做（协议依据，非推断）

### 2.1 同一个方法，只换一个开关

| | 登录 | 续期 |
|---|---|---|
| 模块 | `music.login.LoginServer` | **同左** |
| 方法 | `Login` | **同左** |
| `param.loginMode` | `1`（手机验证码） | **`2`（刷新）** |
| 其余参数 | `code` / `phoneNo` | 整份凭证（见 2.2） |

依据是成熟参考实现 `L-1124/QQMusicApi` 的 `modules/login.py::refresh_credential()`
（本轮直接读源码核对，未凭记忆）：它按 `login_type` 分三支，三支的参数集互有出入。

### 2.2 参数取「并集」，因为手机号登录落在参考实现的 `case _`

| `loginType` | 参考实现的参数集 |
|---|---|
| 1（微信） | `openid` `refresh_token` `str_musicid` `musickey` `unionid` `refresh_key` `loginMode=2` |
| 2（QQ） | `openid` `access_token` `refresh_token` `expired_in` `musicid` `musickey` `refresh_key` `loginMode=2` |
| **其它 / 未知** | **上面两套的并集** ← 本客户端走这一支 |

本客户端唯一的凭证来源是手机验证码登录，它发出的 `comm.tmeLoginType = 0`
（既不是微信 1 也不是 QQ 2）⇒ 落在 `case _`。所以 `QqRequests.refreshCredential`
按**并集**构造：多带的字段服务端会忽略，**少带一个却只会静默失败**。

### 2.3 `expired_in` 是**绝对到期时刻**，不是「还有多久」

参考实现的 `Credential.expired_at` 注释写的是「到期时间」，`refresh_credential`
传的是它。首次登录时它等于 `musickeyCreateTime + psrf_access_token_expiresAt` ——
这两个值我们都有，所以 `QqRequests.refreshCredential` 按那个式子**还原**；
还原不出来传 `0`（参考实现在同样情况下传的也是 `0`）。
**传 `now` 是错的**：那等于告诉服务端「这个 access_token 刚刚过期」。

### 2.4 续期侧的码表**不是**登录侧那张

参考实现的 `_validate_result` 把 `1000` / `104400` / `104401` 一律抛成
`LoginAuthExpiredError`。登录时 `1000` 是「验证码错」，续期时同一个码只有一个含义：
**凭证不再被接受**（终态，重试没有意义）。`QqTokenRefresher.classifyRefresh`
按这张表实现，并有单测逐码钉住。

---

## 三、改了什么

| 落点 | 内容 |
|---|---|
| `QqRefreshCredential`（新） | 凭证的解析 / 序列化 / 到期与续期时机判定 / 字段级合并。纯逻辑，JVM 可单测 |
| `QqRefreshStore`（新） | 落盘到 `ncrust_qq_prefs` 的 `qq_refresh_credential`（与 cookie 同文件，登出一起清） |
| `QqRequests.refreshCredential`（新） | 续期请求体（并集参数 + `loginMode=2`） |
| `QqTokenRefresher`（新） | 单飞（`Mutex`）+ 失败冷却（60 s）+ 只在冷启动与「取链被拒」两个时刻触发 |
| `QqApi.requestVkeyBatch` | 拆出 `…Once`，外面套一层**一次性**续期重试 |
| `QqApi.loginWithPhoneCode` | 登录成功后把续期凭证一起落盘（**不再只留 cookie**） |
| `QqMusicSourceProvider.install` | 冷启动主动续期（票据寿命剩 < 12 h 才起协程） |
| `QqAuthStore.clear` / `QqLoginOverlay` | 登出与重新登录时**一起清**续期凭证，防跨账号串用 |

### 3.1 两条触发路径，各自补对方的洞

| 路径 | 触发点 | 覆盖的场景 |
|---|---|---|
| **主动** | 冷启动时剩余寿命 ≤ 12 h | 最常见的一种：「一周没开 App，再打开时票刚好到期」——用户**完全无感** |
| **被动** | 取链被服务端拒（`NEED_LOGIN` 且有票 / HTTP 401·403） | 寿命未知的老凭证、后台常驻跨过整个窗口、时钟不准 |

只有被动 ⇒ 每次到期都要先失败一次（用户看到一次「要会员/去登录」的错误）；
只有主动 ⇒ 上面那些情况全漏掉。

### 3.2 为什么**不**在 `NEED_VIP` 时刷新

`result=104003` 在有票时被分类成 `NEED_VIP`（票有效、权益不够），在无票时是
`NEED_LOGIN`。「服务端拒了我的票」这个事实的判据是
**`NEED_LOGIN` 且本地 `isLoggedIn()` 为真**，外加传输层 401/403。
拿 `NEED_VIP` 去触发续期，只会让「非会员点会员歌」每次都换一次票 ——
腾讯侧看到的是一个不停续期的账号，而用户的权益一点没变。

### 3.3 防「跨账号串用」

续期凭证属于**某一个**账号。用户登出 A、登录 B 之后若还留着 A 的凭证，
下一次续期会把 B 的登录态换成 A 的，而**全程不报任何错**
（用户看到「登录着 B、数据全是 A」）。三道防线：

1. `QqAuthStore.clear()` 里**同一个函数**清掉 cookie 与凭证（结构保证，不靠纪律）；
2. 所有登录入口在落 cookie **之前**先 `QqRefreshStore.clear()`；
3. `QqTokenRefresher.isUsableFor` 再比一次 `musicid` 与 cookie 的 `uin`，
   不一致就丢弃凭证并按「不能续期」处理。

---

## 四、验证

### 4.1 已做（可复现）

| 项 | 结果 |
|---|---|
| 全量 JVM 单测 | **2731 用例**（含本轮新增 18 条），`--rerun-tasks` 全绿 |
| 新增单测 | `QqRefreshCredentialTest`：18 条，覆盖解析 / 失败骨架 / 前缀推断 / 时机边界 / 字段级合并 / 请求体逐参数 / 码表 / 日志不泄露 |
| `PersistenceFieldNameContractTest` | 把 `QqRefreshCredential` 加进注册表 —— 13 个字段全部有 `@SerializedName` |
| release 构建（R8） | `assembleRelease` 成功 |
| **R8 后字段名实测** | `mapping.txt` 里类被混淆成 `J4.F0`、字段被改成 `a…m`（符合预期，因为走的是 `@SerializedName` 路线）；`classes.dex` 里 **13 个注解值全部存在**（`musicid` `musickey` `refreshKey` `refreshToken` `accessToken` `openid` `unionid` `strMusicId` `loginType` `keyCreatedAt` `keyExpiresIn` `needRefreshKeyIn` `encryptUin`）⇒ 落盘 key 不受混淆影响 |
| `lintDebug` | 零 error |

### 4.2 **没有**验证的部分（如实标注，不要当成已验）

1. **续期的成功路径没有跑过**。那需要一份真实账号的 **`refreshKey` / `refreshToken`**，
   而本仓库没有 qm 账号，也不能为了测试去消耗真实账号的刷新额度。
   所以下面两条是**推断**，不是实测：
   - 「手机号登录的响应 `data` 里确实带 `refreshKey`」—— 依据是参考实现的
     `Credential` 模型把 `refresh_key` 列为该端点的字段，以及 `login_with_phone_code`
     与 `refresh_credential` 用的是同一个 `Login` 方法。**没有本仓库的成功态样本。**
   - 「`refreshKey` 在多次刷新之间保持有效」—— 同样只有参考实现的字段契约。
2. **真机端到端**（装 APK → 等到票据进入 12 h 窗口 → 观察静默续期 → 再取链成功）
   没有做：本轮施工期间没有可用的真机/模拟器（`adb devices` 为空，无 AVD）。
   复现脚本见 §4.3，留给有账号的人跑一次。
3. **网页登录 / QQ 互联扫码那条路不可续期**，这是**能力边界**不是缺陷：
   它只能读到 cookie，而 cookie 里没有 `refreshKey`、也没有 `openid`。
   走那条路登录的用户仍然会在票据到期后需要重新登录一次。

### 4.3 给有 qm 账号的人：怎么验一次

```
# 1. 用手机验证码登录一次（设置 → 音源账号 → qm → 手机号登录）
# 2. 立刻看凭证有没有落盘（只关心字段名与长度，不要贴值）
adb shell run-as com.takahashirinta.ncrust cat shared_prefs/ncrust_qq_prefs.xml
#    期望：多出 qq_refresh_credential，且 refreshable=true
adb logcat -s QqApi | grep "已保存续期凭证"
#    期望：已保存续期凭证 canRefresh=true {… refreshable=true}

# 3. 手工把签发时间往前推 7 天（把票据"变旧"，不需要真的等一周）
#    改 qq_refresh_credential 里的 keyCreatedAt，或改 cookie 里的
#    psrf_musickey_createtime，然后冷启一次
adb shell am force-stop com.takahashirinta.ncrust
adb logcat -s QqTokenRefresher QqApi | grep -E "静默续期|refresh"
#    期望：QqTokenRefresher: qm 登录态已静默续期 newCredential={… refreshable=true}
#          且 ncrust_qq_prefs.xml 里的 qqmusic_key 换成了一张**新**票

# 4. 反证：把 qq_refresh_credential 删掉，重复第 3 步
#    期望：不出现任何 refresh 请求（走 NO_CREDENTIAL），行为与 v3.4.8 完全一致
```

---

## 五、升级后的行为（对老用户）

| 用户状态 | v3.4.9 之后的表现 |
|---|---|
| v3.4.8 之前登录的（没有续期凭证） | **与 v3.4.8 完全一致** —— 票据到期后仍需重新登录一次。这是本次迁移的验收口径：**不会更糟** |
| 重新登录一次之后 | 之后每次到期都会静默续期，**不再需要重新登录** |
| 走网页登录的 | 仍需重新登录（cookie 里没有续期所需的事实，见 §4.2.3） |
| 登出 / 换账号 | 续期凭证与 cookie 一起清、一起换，不存在跨账号串用 |

---

# 追加：扫码登录也能续期（同一天，同一份证据纪律）

> 用户追问：「有办法做扫码的续签吗，还是必须手机号」。
> 结论：**可以，但不是现在那条扫码路**。下面是实测过程与两处真机路径上踩到的坑。

## 六、三条登录路各自能不能拿到续期凭证

| 登录方式 | 最后一步拿到的东西 | 能续期吗 |
|---|---|---|
| 手机号验证码 | `Login` 的 `data`（含 `refreshKey` / `refreshToken`） | ✅ 已实现（§三） |
| **QQ 互联扫码**（原「自绘二维码」） | `y.qq.com` 域下的 **cookie** | ❌ 拿不到 `refreshKey`，也没有 `openid` |
| **QQ 音乐 App 扫码**（本版新增） | 服务端 MQTT 推送 `qqmusic_key` → 再调 `Login` → **完整凭证 JSON** | ✅ 复用同一条续期链路 |

第三条就是答案：**换一条扫码路**。它的最后一步不是交给 WebView，而是
`Login(param={musicid, qrCodeID, token}, comm={tmeLoginType:6})` ——
`tmeLoginType = 6` 就是「手机扫码」这一支，回的是与手机号登录**完全同形**的凭证 JSON。

## 七、协议链路（每一步都在本机实测过）

```
① CreateQRCode            POST musicu.fcg（music.login.LoginServer）
                          → code=0 + 91 字符 qrCodeID + data:image/png;base64,…
② wss://mu.y.qq.com/ws/handshake
   CONNECT  AUTH_METHOD="pass"
            user props: tmeAppID=qqmusic, business=management,
                        hashTag=<qrCodeID>, clientTag=management.user, userID=<qrCodeID>
   → CONNACK reasonCode=0x9D（Server moved）+ SERVER_REFERENCE
③ 重连 wss://mu.y.qq.com/ws/handshake/<SERVER_REFERENCE>，重发 CONNECT
   → CONNACK reasonCode=0
④ SUBSCRIBE management.qrcode_login/<qrCodeID>
            user props: authorization=tmelogin, pubsub=unicast
   → SUBACK 成功
⑤ 等服务端 PUBLISH（`type` 挂在 **user property** 上）：
     scanned / canceled / timeout / loginFailed / cookies
   没人扫码时**一条都不推**（实测 25 秒窗口内无推送）
⑥ cookies 推送 → Login(param={musicid, qrCodeID, token}, comm={tmeLoginType:6})
   → 完整凭证 JSON → cookie + 续期凭证落盘（复用 §三 那条链路，一行没改）
```

## 八、两处实测踩到的坑（都只在真机路径上出现）

### 8.1 `mu.y.qq.com` 只认 WebSocket，裸 MQTT over TCP 被 400 拒绝

```
[TCP+TLS 443] 发 MQTT CONNECT → HTTP/1.1 400 Bad Request (nginx)
```

所以 **Paho Android 那类纯 TCP 的 MQTT 库在这里连不上**。这条已经写成一条会红的
单测（`QqScanLoginProbeTest.裸 MQTT over TCP 被服务端拒绝为 400`），
防止后人「顺手换个库」。

### 8.2 第一条 CONNACK **一定**是重定向，而且 `SERVER_REFERENCE` 是**路径段**

抓到的完整报文（2026-10-08，本机）：

```
20 59 00 9d 56                        CONNACK, reasonCode=0x9D, props len=86
   26 0006 "server"    0013 "11.168.20.203_29001"
   26 0009 "channelID" 0013 "2108223752734814208"
   1c 0014 "11.154.131.152:29001"     ← SERVER_REFERENCE
```

它的用法**不是换一台主机**，而是拼成**路径段**
（参考实现 `_build_redirect_path` 的逻辑）：重连到
`wss://mu.y.qq.com/ws/handshake/11.154.131.152:29001`，由服务端按这段路由。
不跟随重定向的表现是「CONNACK 被拒」—— 而它其实是一次正常的指路。

### 8.3 拆旧连接时投进来的「断连哨兵」会污染下一次握手（最难查的一个）

重定向要先拆旧连接，而 OkHttp 的 `cancel()` 会让旧连接的回调**异步**再投一条
DISCONNECT 进来 —— 它可能落在清队列**之后**，于是新连接上等 CONNACK 的第一次
`receive()` 拿到的是那条陈旧哨兵，第一版据此判「CONNACK 被拒」。

症状是**每当服务端要求重定向就必然失败**，而日志里只有一句
`期望 CONNACK，收到 type=14`（`14` 是 DISCONNECT）—— 不了解这个细节的人会去查网络。

修法：`awaitConnAckFrame()` 循环**跳过陈旧的断连哨兵**继续等，
真正的断开会让它等到超时（那时返回 null、按失败处理），
所以不会把「连接死了」误判成「连上了」。

### 8.4 附带修掉的一个：SUBACK 之前可能先来一条推送

`awaitSubAck()` 原本「取一条，不是 SUBACK 就失败」。而服务端的推送与确认走**同一条通道** ——
第一次扫码可能因此失败、重试就好（那种最招人烦的形状）。现在它循环等 SUBACK，
并把先到的推送**留住**（`pendingEarlyEvent`），交给 `awaitEvent` 消费。

## 九、这一版扫码路的验证

| 项 | 结果 |
|---|---|
| `CreateQRCode` 真实响应 | ✅ 实测 `code=0` + 91 字符 ID + PNG（探针用例钉住） |
| 裸 MQTT over TCP | ✅ 实测被拒（探针用例钉住「为什么不用现成库」） |
| **WSS 握手 + 重定向跟随 + 订阅** | ✅ **实测 `open() == true`**，连跑 3 次全绿 |
| 报文编解码 | ✅ 27 条单测，**黄金字节**（不是自洽往返）：CONNECT 178 字节逐字节对上参考实现、四条变长整数分界值、属性表逐项 |
| 全量单测 / release(R8) / lint | 见 §四.1 的同一份口径 |

### 仍然**没有**验证的部分（如实标注）

1. **拿真手机扫码那三步没有跑过**：`cookies` 推送 → 换凭证 → 续期凭证落盘。
   自动化不可能扫屏幕上的码。探针用例因此**不断言「一定收到推送」**
   （实测没人扫码时服务端一条都不推）。复现步骤见 §十。
2. `cookies` 推送的 **payload 形状**（`{"cookies":{"qqmusic_uin":{"value":…},…}}`）
   来自参考实现的字段契约，本仓库**没有真实样本**。所以
   `QqQrMqttSession.eventOf` 在缺字段时**明确失败**而不是拿半个凭据去换票。
3. `tmeLoginType=6` 这个值同样来自参考实现，没有本仓库的成功态样本验证。

## 十、给有 qm 账号的人：怎么验扫码那条路

```
# 1. 设置 → 音源账号 → qm → 「用 QQ 音乐 App 扫码」
# 2. 用手机上的 QQ 音乐 App 扫屏幕上的码并确认
adb logcat -s QqQrMqtt QqApi QqTokenRefresher | grep -E "PUBLISH|Login\(qr-scan\)|静默续期"
#    期望依次看到：
#      QqQrMqtt: PUBLISH type=scanned payloadLen=…
#      QqApi:    Login(qr-scan) -> req.code=0 outcome=OK
#      QqApi:    Login(qr-scan) 已保存续期凭证 canRefresh=true {… refreshable=true}
# 3. 读回落盘（只看字段名与长度，不要贴值）
adb shell run-as com.takahashirinta.ncrust cat shared_prefs/ncrust_qq_prefs.xml
#    期望：qq_cookie 里有 uin/qqmusic_key/qm_keyst/psrf_musickey_createtime，
#          并且多出 qq_refresh_credential（refreshable=true）
# 4. 之后按 §4.3 的第 3 步验证续期本身
```
