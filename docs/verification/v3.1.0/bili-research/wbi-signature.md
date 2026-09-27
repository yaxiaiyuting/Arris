# Wbi 签名算法 —— Ncrust v3.1.0 调研（附可复现实测）

> **实测时间**：2026-09-28 02:29–02:32（+0800），epoch ≈ 1790533777–1790533970
> **出口**：**直连**（`env -u https_proxy -u http_proxy`），匿名，无 Cookie（除显式标注的 `buvid3` 对照）
> **可复现**：
> - `python3 wbi_golden.py --live` → `evidence/91-wbi-golden-vector.txt`（离线官方向量自检）
> - `python3 wbi_ab.py` → `evidence/90-wbi-ab.txt`（服务端 A/B）
> - `python3 wbi_sign_reference.py`（通用签名器，被上面两个脚本复用）
> - `evidence/92-wts-tolerance.txt`、`evidence/93-wbi-key-global.txt`

---

## 0. 结论先行

| 问题 | 实测结论 |
|---|---|
| 算法实现对不对？ | ✅ **与官方文档逐字节一致** —— `mixin_key` / 排序 query / `w_rid` / 中文空格编码 **4 项全部 MATCH**（§2） |
| 服务端**真的**验签吗？ | ✅ **真的**。`/x/web-interface/wbi/view/detail`：无签名 `-352 风控校验失败` → 有签名 `code:0 OK`（§3） |
| 所有 wbi 端点都验签吗？ | ❌ **不是**。`search/type` 在本机出口**完全不验**（无签名/伪造签名/正确签名三者结果一致）（§4.2） |
| 缺签名会返回 HTTP 412 吗？ | ❌ **不会**。缺签名是 **HTTP 200 + `code:-352`**。HTTP 412 是**另一回事**（路径级封禁，签名也救不了）（§4.3） |
| `wts` 的容忍窗口是多久？ | ⚠️ **没有窗口** —— 实测 `wts` 取 **±30 天 / ±1 年 / 甚至 `0`** 全部 `code:0`。**`wts` 根本不参与有效性判定**（§5） |
| wbi key 缓存多久？ | 社区共识**每日轮换**；本次实测确认 key **与 UA/Cookie/IP 无关、全站统一**（§6）。**"跨天失效"未能直接实测**（当天只观测到一个 key 值），只做保守建议 |
| Ncrust 该实现吗？ | ✅ **建议实现**，但只对**真正需要的端点**用，且必须能降级（§8） |

---

## 1. 算法（可直接照抄的 5 步）

来源：`SocialSisterYi/bilibili-API-collect` → `docs/misc/sign/wbi.md`
（本机通过镜像 `https://raw.githubusercontent.com/venhow/bilibili-API-collect/master/docs/misc/sign/wbi.md` 取得，19,290 bytes）

### 步骤 1 · 取实时口令

```
GET https://api.bilibili.com/x/web-interface/nav
```

**未登录也返回**（实测 `code:-101` 但 `data.wbi_img` 存在）：

```json
{"code":-101,"message":"账号未登录","ttl":1,"data":{
  "isLogin":false,
  "wbi_img":{
    "img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png",
    "sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png"},
  "ip_region":"CN"}}
```
（`evidence/01-nav.txt`）

- `img_key` = `img_url` 的**文件名去掉扩展名** = `7cd084941338484aae1ad9425b84077c`
- `sub_key` = `sub_url` 同理 = `4932caff0ff746eab6f01bf08b70ac45`
- ⚠️ **这两个 URL 是伪装成 png 的 token，不要去下载**

### 步骤 2 · 重排得 `mixin_key`

```kotlin
// 固定 64 位乱序表（mix-in key table）—— 逐字节照抄，不要"优化"
private val MIXIN_KEY_ENC_TAB = intArrayOf(
    46, 47, 18,  2, 53,  8, 23, 32, 15, 50, 10, 31, 58,  3, 45, 35, 27, 43,  5, 49,
    33,  9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48,  7, 16, 24, 55, 40,
    61, 26, 17,  0,  1, 60, 51, 30,  4, 22, 25, 54, 21, 56, 59,  6, 63, 57, 62, 11,
    36, 20, 34, 44, 52
)
```

```
orig      = img_key + sub_key            // 长度恒为 64（两个 32 位 hex）
mixin_key = orig[ TAB[0] ] + orig[ TAB[1] ] + ... + orig[ TAB[63] ]，然后取前 32 个字符
```

**自检**：`TAB` 必须是 `0..63` 的一个排列（`sorted(TAB) == 0..63`）；`img_key`/`sub_key` 长度不足 32 时索引会越界，健壮实现应 `continue` 跳过（`bili-music` 的 Dart 实现就是这么写的）。

实测本节输入/输出（`evidence/91-wbi-golden-vector.txt` §[2]）：

```
img_key  = 653657f524a547ac981ded72ea172057
sub_key  = 6e4909c702f846728e64f6007736a338
img+sub  = 653657f524a547ac981ded72ea1720576e4909c702f846728e64f6007736a338 (len=64)
计算值   = 72136226c6a73669787ee4fd02a74c27
期望值   = 72136226c6a73669787ee4fd02a74c27
MATCH    = True
```

### 步骤 3 · 加 `wts`

`wts` = **秒级** Unix 时间戳（`System.currentTimeMillis() / 1000`，不是毫秒）。把它当成一个普通参数加入待签名集合。

### 步骤 4 · 排序 + 过滤 + 编码

对**全部**参数（含 `wts`）：

1. **值**先删除 `!'()*` 五个字符（是**删除**，不是转义）
2. 按 **key 字典序升序**排序
3. 做 URL query 编码，且：
   - **百分号后必须是**大写十六进制（`%E4%BA%94` 不是 `%e4%ba%94`）
   - **空格必须编码成 `%20`，不是 `+`**

实测（`evidence/91-...txt` §[5]）：

```
计算 query = bar=%E4%BA%94%E4%B8%80%E5%9B%9B&baz=1919810&foo=one%20one%20four
期望 query = bar=%E4%BA%94%E4%B8%80%E5%9B%9B&baz=1919810&foo=one%20one%20four
MATCH      = True
```

对照（同一个 `"one two"`）：

```
urllib.parse.urlencode 默认 (quote_plus) : a=one+two      ← ❌ 错
quote_via=quote, safe=''  （正确）        : a=one%20two    ← ✅ 对
```

### 步骤 5 · 算 `w_rid`

```
w_rid = md5_hex_lowercase( query + mixin_key )
```

实测（`evidence/91-...txt` §[4]）：

```
md5 输入  = bar=514&foo=114&wts=1684746387&zab=1919810 72136226c6a73669787ee4fd02a74c27
计算 w_rid = 90efcab09403023875b8516f07e9f9de
期望 w_rid = 90efcab09403023875b8516f07e9f9de
MATCH      = True
```

最后把 `w_rid` 作为**普通 query 参数追加**到原始请求上（`wts` 与 `w_rid` 都要带）。

---

## 2. 官方向量自检结果（`wbi_golden.py`）

```
[1] 乱序表
    MIXIN_KEY_ENC_TAB 长度 = 64 (期望 64)
    是否为 0..63 的一个排列 : True
[2] mixin_key 推导        MATCH = True
[3] 排序 + 过滤 + 编码     MATCH = True
[4] w_rid = md5(query+mixin_key)  MATCH = True
[5] 中文 / 空格编码用例     MATCH = True
[6] 过滤 !'()* 的行为       'a!b\'c(d)e*f' → 'abcdef'  ✅
================================================================
总判定 : 全部 PASS ✅ —— 实现与文档定义逐字节一致
================================================================
```

**这一步的意义**：它把"我的实现是对的"从"服务端没报错"升级成**逐字节等价于文档定义**，排除了"错误实现碰巧被接受"的可能。

---

## 3. 服务端 A/B：**证明服务端真的在算 md5** ⭐

这是本次调研的**硬性验收项**。

### 3.1 主对照端点

```
GET https://api.bilibili.com/x/web-interface/wbi/view/detail?bvid=<BV>
```

**为什么用它而不是 `search/type`**：`search/type` 在本机出口**根本不验签**（§4.2），拿它做 A/B 只会得出"签名没用"的错误结论。`view/detail` 是本次穷举 9 个 wbi 端点后**唯一**能稳定复现「无签名失败 → 有签名成功」的端点。

### 3.2 原始输出（`evidence/90-wbi-ab.txt` §[1]，逐字复制）

```
[1] 主对照：/x/web-interface/wbi/view/detail  (bvid=BV1GJ411x7h7)
  A) 无 w_rid / 无 wts
     GET https://api.bilibili.com/x/web-interface/wbi/view/detail?bvid=BV1GJ411x7h7
     HTTP=200   0.26s  code=-352     message=风控校验失败  v_voucher=voucher_42e0516d-f6b8-4f0d-8958-9de3ea611277

  B) 有 w_rid（本仓库 wbi_sign_reference.py 现算）
     wts           = 1790533778
     signed_query  = bvid=BV1GJ411x7h7&wts=1790533778
     md5 输入       = bvid=BV1GJ411x7h7&wts=1790533778ea1db124af3c7062474693fa704f4ff8
     w_rid         = 8c2633241031d11b3e3107856aa1f4a8
     GET https://api.bilibili.com/x/web-interface/wbi/view/detail?bvid=BV1GJ411x7h7&wts=1790533778&w_rid=8c2633241031d11b3e3107856aa1f4a8
     HTTP=200   0.58s  code=0        message=OK  data.keys=['Card','Related','Reply','Spec','Tags','View']

  C) 有 wts 但 w_rid 伪造（全 0）—— 验证服务端真的在算 md5，而不是只看字段存在
     GET ...&wts=1790533778&w_rid=00000000000000000000000000000000
     HTTP=200   0.25s  code=-352     message=风控校验失败  v_voucher=voucher_0b7b0d89-54fb-49b4-859f-c4e1ea2e102b

  D) 有有效 w_rid + buvid3 Cookie（对照：Cookie 不改变结论）
     HTTP=200   0.61s  code=0        message=OK  data.keys=['Card','Related','Reply','Spec','Tags','View']
```

### 3.3 判读表

| 请求 | HTTP | `code` | 说明 |
|---|---|---|---|
| 无 `w_rid` | 200 | **-352** | 风控拒绝，带 `v_voucher` |
| `w_rid` = 全 0（有 `wts`） | 200 | **-352** | **证明服务端在算 md5** —— 光有字段不够 |
| **正确 `w_rid`** | 200 | **0** | ✅ 通过 |
| 正确 `w_rid` + `buvid3` | 200 | **0** | Cookie 不影响结论 |

**A/B 成立**：唯一的变量是 `w_rid` 的值（`wts` 在 A 组缺失、C 组存在但错），结果从 `-352` 变成 `0`。

> ⚠️ **注意这里的"412 对照"不是任务书预期的那个**：任务书假设「无签名 → HTTP 412，有签名 → HTTP 200」。**实测这个假设不成立** —— 见 §4.3。本文给出的是**真实存在**的 A/B（`-352` → `0`），而不是伪造一个 412。

---

## 4. 哪些端点验签、哪些不验、哪些签名没用（9 端点穷举）

### 4.1 总表

| 端点 | 无 `w_rid` | 有正确 `w_rid` | 判定 |
|---|---|---|---|
| **`/x/web-interface/wbi/view/detail`** | `-352` | **`0` OK** | ✅ **验签** |
| `/x/web-interface/wbi/search/type` | `0` OK | `0` OK | ⚪ **不验**（本机出口） |
| `/x/web-interface/wbi/search/all/v2` | `0` OK | `0` OK | ⚪ 不验 |
| `/x/web-interface/wbi/index/top/rcmd` | `0` OK | — | ⚪ 不验 |
| `/x/web-interface/wbi/view` | `0` OK | — | ⚪ 不验 |
| `/x/web-interface/wbi/search/default` | `0` OK | — | ⚪ 不验 |
| `/x/web-interface/wbi/search/square` | `0` OK | — | ⚪ 不验 |
| `/x/player/wbi/v2` | `0` OK | — | ⚪ 不验 |
| `/x/space/wbi/acc/info` | `-352` | **`-352`** | ❌ **IP 级风控，签名无效** |
| **`/x/player/wbi/playurl`** | **HTTP 412** | **HTTP 412** | ❌ **路径级封禁，签名无效** |

### 4.2 `search/type` 不验签的原始证据（`evidence/90-wbi-ab.txt` §[2]）

```
  A) 无 w_rid   : HTTP=200   0.58s  code=0        message=OK  data.keys=['egg_hit','exp_list',...]
  B) 有 w_rid   : HTTP=200   0.63s  code=0        message=OK  data.keys=['egg_hit','exp_list',...]
  C) 伪造 w_rid : HTTP=200   0.56s  code=0        message=OK  data.keys=['egg_hit','exp_list',...]
  ⇒ 三者结果一致 ⇒ 该端点在本机出口上不校验 w_rid
```

并且**连打 100 次无签名请求，全部 `code=0`**（文档称"数次请求后返回 -403"，本次**未复现**）：

```
=== 无签名连续请求 100 次，观察是否触发风控 ===
req#1  code=0 http=200 msg=OK
req#2  code=0 http=200 msg=OK
req#3  code=0 http=200 msg=OK
req#20 code=0 http=200 msg=OK
req#40 code=0 http=200 msg=OK
req#60 code=0 http=200 msg=OK
req#80 code=0 http=200 msg=OK
（100 次全部 code=0）
```

> **实践结论**：wbi 是**风控手段**，其强制程度**取决于服务端对该 IP 的风险评级**，不是"协议要求"。同一份代码在不同出口 IP（家宽 / 机房 / 海外）上表现可能完全不同。
> **⇒ Ncrust 必须实现 wbi 并默认开启**（因为它在某些 IP 上确实是硬门槛），但**必须能在签名失败时降级重试**，而不是直接报错。

### 4.3 ⚠️ 澄清：HTTP 412 与 wbi **无关**

任务书假设"无签名 → 412"。**实测 412 是另一回事**：

```
  player/wbi/playurl  无签名 : HTTP=412  NON-JSON body: '<!DOCTYPE html>...'
  player/wbi/playurl  有签名 : HTTP=412  NON-JSON body: '<!DOCTYPE html>...'
```

- 4 种组合（无签名/有效签名/伪造签名/无 Cookie）**全部 HTTP 412**，返回 **3286 字节 HTML 错误页**（`<title>出错啦! - bilibili.com</title>`）
- 同一账号态下，**旧的非 wbi 路径 `/x/player/playurl` 却返回 `code:0` + 完整 DASH**（`evidence/90-wbi-ab.txt` §[4]）

⇒ **412 是"这个路径 + 这个出口 IP"的封禁**，不是签名失败的表现。给 412 补签名**不会**变成 200。

**真正的"缺签名"表现是 `HTTP 200 + code:-352`**（或某些端点干脆不校验）。这一点必须写进实现者的预期里，否则会照着"412 = 签名问题"去 debug，方向完全错。

---

## 5. ⚠️ `wts` 的"容忍窗口"：**不存在**（实测推翻常见假设）

社区普遍假设 `wts` 有几分钟的容忍窗口（防止重放）。**实测完全不成立**。

`evidence/92-wts-tolerance.txt`（端点 `view/detail`，**只改 `wts`，签名始终正确**）：

| `wts` 相对当前 | 结果 |
|---|---|
| now+0 / -30s / -60s / -300s / -600s | `code:0 OK` |
| -1800s（30 分）/ -3600s（1 h）/ -7200s（2 h） | `code:0 OK` |
| -10800s（3 h）/ -21600s（6 h）/ -43200s（12 h）/ -86400s（24 h） | `code:0 OK` |
| -604800s（7 天） | `code:0 OK` |
| -2592000s（30 天）/ -31536000s（1 年） | `code:0 OK` |
| +60s / +300s / +3600s / +31536000s（1 年后） | `code:0 OK` |
| **`wts = 0`（1970-01-01）** | **`code:0 OK`** |

**⇒ `wts` 不参与任何有效性判定。** 服务端只把它当作被签名字符串的一部分：
只要 `md5(query + mixin_key)` 对得上，`wts` 写多少都行。

**推论（重要）**：**Wbi 签名的真实寿命 = `mixin_key` 的寿命，不是 `wts` 的寿命。**
所以"缓存一个签名几分钟"这种优化毫无意义 —— 该缓存的是 **`img_key`/`sub_key`**。

> 这也解释了为什么"`wts` 容忍窗口"在社区里说法不一：**因为它压根不存在**，谁测都是"都能过"，于是各自猜了一个数字。

---

## 6. wbi key 的缓存时长

### 6.1 已实测确认的

`evidence/93-wbi-key-global.txt`：

```
UA=Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chr {"code":-101,"wbi_img":{...7cd084941338484aae1ad9425b84077c...4932caff0ff746eab6f01bf08b70ac45...}}
UA=Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like  ... 同上（逐字节相同）
UA=curl/8.5.0                                    ... 同上
UA=（空）                                         ... 同上
```

⇒ **wbi key 与 UA、Cookie/登录态、本机出口 IP 全部无关，是全站下发的同一份。**
所以 **key 可以跨用户、跨设备共享地缓存**；不需要按账号分桶。

### 6.2 未能直接实测的（诚实标注）

| 说法 | 来源 | 本次验证状态 |
|---|---|---|
| "这两个 Key 的值无关登录 Session 与 IP，属于全站统一使用的，但**每日都会变化**，使用时应做好**缓存和刷新**处理" | `bilibili-API-collect` `docs/misc/sign/wbi.md`（原文） | ⚠️ **"全站统一"已实测确认**；**"每日变化"未实测** —— 单次会话内只观测到一个 key 值（`7cd084941338484aae1ad9425b84077c`），跨天轮换需要隔天复测 |
| 轮换的确切时刻 | 社区口口相传"每天凌晨" | ⚠️ **未知**，未见权威来源 |
| Wayback Machine 归档佐证 | 尝试 `web.archive.org/cdx/search/cdx?url=api.bilibili.com/x/web-interface/nav*` | ❌ **本机到 archive.org 连接超时（40s）**，未取得 |

### 6.3 保守工程建议

既然"每日轮换"是社区共识且**轮换失败会导致签名全部失效**，而刷新 key 的成本只是**一次 nav 请求**，建议：

- **key 缓存 TTL 取 12 小时**（比"每日"更保守，一天最多多刷 1 次）
- **不做跨进程/跨设备持久化**：app 冷启动时若缓存已过期就重新拉 nav，未过期直接用
- **`-352` 时强制刷新 key 并重签重试一次**（这是一条零成本的兜底：即使轮换时刻比预期早，也能自愈）
- 绝不在 key 缺失时"跳过签名硬发"—— 那正是 `wbi_ab.py` C 组证明会被拒的形态

---

## 7. Kotlin 实现要点

### 7.1 乱序表常量

见 §1 步骤 2。**逐字节照抄**。写成 `private val ... = intArrayOf(...)` 即可（64 个 `Int`，约 256 字节，不值得做任何"压缩"）。

### 7.2 MD5 十六进制（**必须小写、必须补前导零**）

```kotlin
import java.security.MessageDigest

/** md5 的小写十六进制；w_rid 要求 32 位定长，前导零必须补。 */
private fun md5Hex(input: String): String {
    val d = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    val sb = StringBuilder(32)
    for (b in d) {
        val v = b.toInt() and 0xFF          // ← 关键：Kotlin Byte 是有符号的，
        sb.append(HEX[v ushr 4])            //    b.toInt() 直接算会得到负数，
        sb.append(HEX[v and 0x0F])          //    "0f".toInt() 之类会炸。
    }
    return sb.toString()                    // 32 位，小写
}
private val HEX = "0123456789abcdef".toCharArray()
```

> ⚠️ 两个最常见的手写 bug：
> ① `b.toInt().toString(16)` —— Byte 有符号，负数会输出 8 位（如 `ffffffab`）；
> ② 忘了补前导零 —— `Integer.toHexString` 会把 `0x0a` 输出成 `"a"`，导致 `w_rid` 只有 31 位。

### 7.3 URL 编码：`Uri.encode` vs `URLEncoder` ⚠️

**这是整条链路上最容易踩、且最难 debug 的一步。**

| 方案 | 空格 | 百分号大小写 | 是否可用 |
|---|---|---|---|
| `java.net.URLEncoder.encode(s, "UTF-8")` | **`+`** ❌ | 大写 ✅ | ❌ **错**（表单语义，不是 URI query） |
| `android.net.Uri.encode(s)` | **`%20`** ✅ | 大写 ✅ | ✅ **可用** |
| `okhttp3.HttpUrl.Builder` | `%20` ✅ | 大写 ✅ | ⚠️ 它自己会重排/编码，**不要用它算签名串** |
| `java.net.URI` 多参构造 | `%20` ✅ | 大写 ✅ | ✅ 可用但 API 笨重 |

**推荐**：

```kotlin
// okhttp3 的 HttpUrl 对 path segment 的编码规则与 wbi 一致（%20、大写百分号）
private fun wbiEncode(s: String): String =
    Uri.encode(s, /* allow = */ null)   // allow=null ⇒ 除 unreserved 外全部百分号编码
```

> `Uri.encode(s)` 的**单参**重载会保留 `!#$&'()*+,/:;=?@[]` 等字符，**不能用于 wbi**
> —— 必须传 `allow = null`（或用 `Uri.encode(s, "")`，两者对 `!'()*` 的差别在 wbi 里已被
> 步骤 4 的"先删除"规则覆盖，但 `&` `=` `+` 仍会被单参版放过，导致签名串与请求串不一致）。
>
> **更稳的做法**：不要依赖任何"通用编码器"，而是**自己按 UTF-8 字节手写百分号编码**，
> 只放过 `A-Za-z0-9-._~`。这样与 `quote(safe="")` 逐字节等价，且不受 Android 版本差异影响。
> 这段逻辑值得抽成**纯函数 + JVM 单测**（用 §2 的三组向量做断言）。

### 7.4 签名流程（伪代码）

```kotlin
object WbiSigner {
    private var cachedKey: Pair<String, String>? = null   // img_key to sub_key
    private var cachedAtMs: Long = 0L

    /** 12 小时 TTL；失败返回 null，由调用方决定降级策略。 */
    private suspend fun keys(forceRefresh: Boolean): Pair<String, String>? { ... }

    /**
     * 对 params 签名。
     * @return 追加了 wts / w_rid 的新 Map；key 不可用时返回 null（**不要**抛异常给 UI）
     */
    suspend fun sign(params: Map<String, String>): Map<String, String>? {
        val (img, sub) = keys(forceRefresh = false) ?: return null
        val mixin = mixinKey(img, sub)
        val wts = System.currentTimeMillis() / 1000
        val all = params + ("wts" to wts.toString())
        val query = all.entries
            .map { (k, v) -> k to v.filterNot { it in "!'()*" } }   // ① 先删除
            .sortedBy { it.first }                                   // ② key 升序
            .joinToString("&") { (k, v) -> "${wbiEncode(k)}=${wbiEncode(v)}" }  // ③ %20/大写
        val wRid = md5Hex(query + mixin)                             // ④ md5 小写
        return params + mapOf("wts" to wts.toString(), "w_rid" to wRid)
    }
}
```

### 7.5 JVM 单测断言（用官方向量，零网络）

这些断言**不需要网络**，应该进 `app/src/test/`：

```kotlin
@Test fun `mixinKey matches official vector`() {
    assertEquals(
        "72136226c6a73669787ee4fd02a74c27",
        mixinKey("653657f524a547ac981ded72ea172057", "6e4909c702f846728e64f6007736a338")
    )
}

@Test fun `query uses %20 not +`() {
    assertEquals("a=one%20two", wbiEncode("one two").let { "a=$it" })
}

@Test fun `w_rid matches official vector`() {
    val query = "bar=514&foo=114&wts=1684746387&zab=1919810"
    assertEquals("90efcab09403023875b8516f07e9f9de", md5Hex(query + "72136226c6a73669787ee4fd02a74c27"))
}

@Test fun `md5Hex pads leading zeros and stays 32 chars`() {
    assertEquals(32, md5Hex("").length)
    assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(""))
}
```

（空串的 MD5 常量是行业常识值，可用作"补零 + 小写"的定型断言。）

---

## 8. 对 Ncrust 的落地建议（摘要，详见 `RECOMMENDATIONS.md`）

1. **必须实现**，但**只在需要的端点上用**。本次调研中，**Ncrust 接入音频区所需的端点（`song/info` / `song/url` / `song/lyric` / `menu/*` / `song/of-menu`）一个都不需要 wbi**（全部匿名直通）。
2. 需要 wbi 的是：**若采用视频 DASH 路线** → `search/type`（本机不验，但换个 IP 可能验）+ `wbi/view/detail`（**必验**）。
3. **默认开，失败可降级**：签名失败（`-352`）→ 刷新 key 重试 **1** 次 → 仍失败则**不带签名**再试 1 次 → 仍失败才向上报错。这条链路的依据是 §4.2（同一份代码在不同 IP 上强制程度不同）。
4. **不要**把 HTTP 412 当成签名问题去重试（§4.3）—— 412 应触发"换路径/换音源"的降级，而不是重签。
5. **`wts` 用当前秒级时间戳即可，不需要校准、不需要担心时钟偏差**（§5：服务端不校验它）。
6. key 缓存 **12 小时** + `-352` 强制刷新（§6.3）。
