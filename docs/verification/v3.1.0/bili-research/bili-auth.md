# B站登录与 Cookie 边界 —— Ncrust v3.1.0 调研

> **实测时间**：2026-09-28 02:29–02:33（+0800）
> **出口**：**直连**（`env -u https_proxy -u http_proxy`）
> **⚠️ 本次调研没有可用的 B站账号**：所有"登录态"结论均**未实测**，本文严格区分「实测」与「文档推断」。
> **可复现**：`evidence/94-auth-boundary.txt`、`evidence/01-nav.txt`、`evidence/33..35-login-*.txt`

---

## 0. 结论先行

| 问题 | 结论 | 依据 |
|---|---|---|
| 匿名能用音频区吗？ | ✅ **能，而且是完整的播放链路** | §2 |
| 匿名音质上限？ | **320K**（走 APP 端点）—— 不需要登录 | §2.3 |
| 有"必须登录"的音频区功能吗？ | ✅ 有：**收藏夹 / 投币**（`4511003 用户未登录`） | §2.4 |
| 登录能换来什么？ | **文档推断**：FLAC 无损（大会员）、收藏夹同步、投币。**均未实测** | §4 |
| Ncrust 必须做 B站登录吗？ | ❌ **本版不必**。匿名已覆盖"搜索→播放→歌词"全链路 | §5 |
| 如果要做，流程难吗？ | 常规扫码：`qrcode/generate` → `qrcode/poll`，两跳，**已实测可达** | §3 |
| 可以复用 ncm/QQ 的登录吗？ | ❌ 完全独立的账号体系，Cookie 不通用 | §1 |
| Cookie 该怎么存？ | 沿用 `CookieManager` 的 `ncrust_prefs` 分桶，但**必须与 ncm/QQ 分开存** | §5.2 |

---

## 1. 账号体系：与现有两个音源**完全无关**

B站、ncm、qm 是**三套独立的账号体系**，Cookie 名字、格式、域名、认证后端全不相同：

| 音源 | 认证载体 | Cookie 关键字段 | 登录后端点前缀 |
|---|---|---|---|
| ncm（现有） | `MUSIC_U` | `MUSIC_U` | `music.163.com` |
| qm（现有） | `uin` / `qqmusic_key` | — | `u.y.qq.com` |
| **B站（新增）** | **`SESSDATA`** | `SESSDATA` / `bili_jct` / `DedeUserID` / `DedeUserID__ckMd5` / `sid` | `passport.bilibili.com`、`api.bilibili.com` |

⇒ **绝不能复用 `CookieManager` 里那份字符串**。必须新增一个独立槽位（例如 `ncrust_prefs` 里加 `bili_cookie`），否则切换音源时会互相覆盖。

> `CookieManager` 现有实现只有一个 `user_cookie` 键（`ncrust_prefs`），这是接入第三个音源时**必须动的一处架构**（见 `RECOMMENDATIONS.md` §4.8）。

---

## 2. 匿名可用边界（**逐条实测**）

### 2.1 完全匿名可用的（✅）

| 功能 | 端点 | 实测 |
|---|---|---|
| 曲目元数据 | `/web/song/info?sid=` | `code:0` |
| **音频流 192K** | `/web/url?sid=&quality=&privilege=2&mid=0&platform=web` | `code:0`, `type:1` |
| **音频流 320K** | `/audio/music-service-c/url?songid=&quality=2&privilege=2&mid=0&platform=pc` | `code:0`, `type:2`，ffprobe 实测 **321584 bps** |
| 歌词（LRC 正文） | `/web/song/lyric?sid=` | `code:0` |
| 榜单列表（12 个） | `/web/menu/rank` | `code:0` |
| 热门歌单（11 个） | `/web/menu/hit` | `code:0` |
| 歌单元数据 / 曲目列表 | `/web/menu/info`、`/web/song/of-menu` | `code:0` |
| 标签 / 创作成员 / 计数 | `/web/tag/song`、`/web/member/song`、`/web/stat/song` | `code:0` |
| 视频搜索 | `/x/web-interface/wbi/search/type?search_type=video` | `code:0` |
| 视频 DASH 取流 | `/x/player/playurl?...&fnval=4048`（需 `buvid3`） | `code:0`，3 条 audio |
| 视频元数据 | `/x/web-interface/view`、`/x/player/pagelist`、`/x/player/wbi/v2` | `code:0` |
| 新歌 / 音乐热榜 | `/x/centralization/interface/new/music`、`/x/copyright-music-publicity/toplist/*` | `code:0` |

### 2.2 `buvid3` 是什么？—— 一个**匿名即可取**的匿名设备标识

```
GET https://api.bilibili.com/x/frontend/finger/spi
→ {"code":0,"data":{"b_3":"A0F86246-BEB0-3FFA-20CD-F4E6F1ABE34529812infoc","b_4":"6C7F6674-..."}}
```

- `b_3` → `buvid3` Cookie，`b_4` → `buvid4`
- **不需要登录**，随手可取
- **不是所有端点都需要**：实测音频区全部端点**不带 `buvid3` 也正常**（`evidence/94-auth-boundary.txt` §[6]）：
  ```
  匿名        : {"code":0,"type":1,"size":6244629}
  带 buvid3   : {"code":0,"type":1,"size":6244629}
  带 mid=12345: {"code":0,"type":1,"size":6244629}
  ```
- **但视频 DASH 的 `/x/player/playurl` 需要它**：不带 → 412；带上 → `code:0`（见 `bili-audio-api.md` §7.1）
- ⚠️ **反例**：`/x/web-interface/nav` 在纯匿名下**不下发任何 `Set-Cookie`**（`evidence/94-...txt` §[4]，响应头里只有 `HTTP/2 200`，无 `set-cookie`）—— 所以"访问首页就会自动种 `buvid3`"的说法在本机**不成立**，要 `buvid3` 就得显式打 `finger/spi`。

### 2.3 音质：**匿名就是 320K**（关键结论）

| 音质 | 匿名可得？ | 端点 | 实测 |
|---|---|---|---|
| 128K (`quality=0`) | ✅ | APP `/url` | `type:0`, 4179753 B |
| 192K (`quality=1`) | ✅ | web `/web/url` **或** APP `/url` | `type:1`, 6244629 B, ffprobe **193568 bps** |
| **320K (`quality=2`)** | ✅ | **APP `/url`** | `type:2`, 10374528 B, ffprobe **321584 bps** |
| FLAC (`quality=3`) | ❌ | APP `/url` | **静默降级为 `type:2`（320K）** |

`qualities[]` 表里三档的 `require` **全是 0**：

```json
[{"type":2,"desc":"高品质","bps":"320kbit/s","tag":"HQ","require":0,"requiredesc":""},
 {"type":1,"desc":"标准",  "bps":"192kbit/s","tag":"",  "require":0,"requiredesc":""},
 {"type":0,"desc":"流畅",  "bps":"128kbit/s","tag":"",  "require":0,"requiredesc":""}]
```

（**没有 `type:3` 条目** —— 这与 `quality=3` 被降级到 320K 互相印证：**匿名根本没有 FLAC 这个选项**。）

### 2.4 匿名**不可用**的（🔒 `code:4511003 用户未登录`）

| 端点 | 匿名返回 |
|---|---|
| `/web/collections/list?uid=` | `{"code":4511003,"data":null,"message":"用户未登录","msg":"用户未登录"}` |
| `/web/collections/info?sid=` | 同上 |
| `/web/collections/songs-coll?sid=` | 同上 |
| `/web/coin/audio?sid=`（投币状态） | 同上 |
| `/web/coin/add`（投币，POST） | 需登录 + CSRF |

**伪造 Cookie 不被接受**（`evidence/94-...txt` §[5]）：

```bash
curl '.../collections/list?uid=14452610&pn=1&ps=5' \
  -H 'Cookie: SESSDATA=fake123; bili_jct=fake; DedeUserID=1'
→ {"code":4511003,"data":null,"message":"用户未登录","msg":"用户未登录"}
```

⇒ 音频区**真的在校验 `SESSDATA` 的有效性**，不是"看到字段就放行"。所以 Ncrust 若要做登录，**必须走真实登录流程拿真 Cookie**，不能伪造。

### 2.5 边界小结（给 UI 用）

```
匿名可用：搜索（视频） / 播放（128K·192K·320K） / 歌词 / 榜单 / 热门歌单 / 歌单浏览 / 元数据
匿名不可用：我的收藏夹 / 投币 / 收藏 / FLAC 无损
```

---

## 3. 扫码登录流程（**端点可达性已实测，完整流程未实测**）

### 3.1 两步

**第 1 步 · 生成二维码**（实测 `code:0`，`evidence/94-...txt` §[1]）

```
GET https://passport.bilibili.com/x/passport-login/web/qrcode/generate
```
```json
{"code":0,"message":"OK","ttl":1,"data":{
  "url":"https://account.bilibili.com/h5/account-h5/auth/scan-web?navhide=1&callback=close&qrcode_key=721a7829c91a1d65dce08a36f0fc4975&from=",
  "qrcode_key":"721a7829c91a1d65dce08a36f0fc4975"}}
```

- 把 `data.url` 渲染成二维码（Ncrust 已有 ZXing 依赖，`QrLoginDialog` 可直接复用）
- 响应同时 `Set-Cookie: qrcode_key=<...>`，但**轮询只需 query 参数**，Cookie 非必需

**第 2 步 · 轮询**（实测两种状态）

```
GET https://passport.bilibili.com/x/passport-login/web/qrcode/poll?qrcode_key=<key>
```

| `data.code` | `data.message` | 含义 | 实测 |
|---|---|---|---|
| `86101` | 未扫码 | 继续轮询 | ✅ 实测到 |
| `86038` | 二维码已失效 | 重新生成 | ✅ 实测到（用伪造 key） |
| `86090` | 已扫码未确认 | 继续轮询 | ⚠️ 文档推断，未实测 |
| `0` | 成功 | 从 `Set-Cookie` 取凭证 | ⚠️ 文档推断，未实测 |

⚠️ **注意双层 `code`**：HTTP 层永远是 200，业务码在 **`data.code`**（不是顶层 `code`，顶层恒为 0）。这是与音频区 API（业务码在顶层 `code`）**不一致**的地方，实现时容易搞错。

### 3.2 成功后的 Cookie（**文档推断，未实测**）

预期 `Set-Cookie` 会包含：

| Cookie | 用途 |
|---|---|
| `SESSDATA` | **核心凭证**，音频区 `collections/*`、`coin/*` 靠它 |
| `bili_jct` | **CSRF token**，所有写操作（投币/收藏）必须带 |
| `DedeUserID` | 用户 mid |
| `DedeUserID__ckMd5` | mid 校验 |
| `sid` | 会话 id |

⚠️ 这五个字段名来自社区文档与常识，**本次未观测到真实的成功响应**，接入前**必须实测一次**。

### 3.3 与 Ncrust 现有登录架构的差异

| 维度 | ncm（现有） | **B站（新增）** |
|---|---|---|
| 二维码生成 | weapi `/api/login/qrcode/unikey`（需加密） | **明文 GET**，无加密 |
| 轮询 | `/api/login/qrcode/client/login`，业务码 800/802/803 | `qrcode/poll`，业务码 86101/86090/86038/0 |
| 凭证字段 | `MUSIC_U` | `SESSDATA` + `bili_jct` + … |
| WebView 登录 | `music.163.com/#/login` 抓 `MUSIC_U=` | `passport.bilibili.com/login`（**未实测**） |
| CSRF | `csrf_token`（值不校验） | `bili_jct`（**必须与 Cookie 内一致**，未实测） |

⇒ **不能复用 `QrLoginDialog` 的逻辑，只能复用它的 UI 外壳**。建议把"二维码登录"抽成接口，B站实现一个独立的 `BiliQrLogin`。

---

## 4. 登录能换来什么（**全部为文档推断，未实测**）

| 收益 | 置信度 | 说明 |
|---|---|---|
| FLAC 无损（`quality=3`） | 中 | 文档说"无损 FLAC（大会员）"；本次实测匿名降级到 320K。**登录且是大会员**时才可能真出 FLAC |
| 我的收藏夹（`collections/*`） | **高** | 端点确实返回 `4511003 用户未登录`，登录后极可能可用 |
| 收藏 / 投币 | **高** | `coin/add` 存在且需登录 |
| 更高码率的付费音频 | 中 | 文档称 web 端付费曲只给 30s 试听；APP 端点用 `access_key`（APP 登录 token）可拿完整付费曲（`biu` 的注释如此声称）。**本次未能触发任何付费曲样本** |
| 收藏夹作为"我的歌单" | 中 | `collections/list?uid=` 需要登录；`collections/songs-coll?sid=` 也需登录 |

⚠️ **一个重要的反向风险**：登录后携带 `SESSDATA` 请求，**所有请求都会与真实账号绑定**。这意味着
① 风控/封号风险从"出口 IP"升级到"用户账号"；
② 一旦实现"收藏/投币"等写操作，就必须正确处理 `bili_jct`，否则会静默失败。

---

## 5. 对 Ncrust 的建议

### 5.1 本版（v3.1.0）**不做 B站登录**

理由：

1. **匿名已覆盖完整播放链路**：搜索（视频）+ 播放（到 320K）+ 歌词 + 榜单 + 歌单浏览，**全部匿名可用**；
2. 唯一被卡住的是"我的收藏夹 / 投币 / FLAC"——这三项都不是"能听歌"的必要条件；
3. 登录会引入**账号级风控风险**（§4 反向风险），对第三方客户端是实质性负担；
4. Ncrust 的 `Source` 抽象里**没有**"某音源可只读"的概念，加登录会牵动 `CookieManager`、`UserScreen`、`LibraryManager` 三处。

**UI 上诚实标注**：B站音源显示"无需登录 · 最高 320K"，把"我的收藏夹"入口隐藏而不是显示成报错。

### 5.2 若将来要做，Cookie 存储的硬要求

```
ncrust_prefs
├── user_cookie        ← ncm（现有，不动）
├── qq_cookie          ← qm（现有，不动）
└── bili_cookie        ← 【新增】B站，独立键
```

**三条硬约束**：

1. **独立键，绝不复用 `user_cookie`** —— 切音源时不能互相覆盖；
2. **`SESSDATA` 是敏感凭证**：不得进日志、不得进崩溃上报、不得进 `OfflineUrlStore`（那里的 URL 会带 `mid=`，但不带 Cookie）；
3. **`bili_jct` 与 `SESSDATA` 必须一起存** —— 少了 `bili_jct` 所有写操作都会失败，且**失败形态是 HTTP 200 + 业务错误码**（B站写操作从不返回 4xx/5xx），只看 HTTP 状态码会误判为成功。

### 5.3 `mid` 参数的语义（实测澄清）

音频区请求带 `mid=0` 且匿名可用；改成 `mid=12345` 结果**完全一样**（`evidence/94-...txt` §[6]）：

```
匿名        : {"code":0,"type":1,"size":6244629}
带 mid=12345: {"code":0,"type":1,"size":6244629}
```

⇒ **`mid` 不影响匿名取流结果**。但它会影响**登录态**的返回（判断"我有没有收藏/投币"）。建议：**匿名时固定传 `mid=0`**；将来若登录，传真实 `DedeUserID`。

---

## 6. 未验证项（诚实清单）

| 项 | 状态 |
|---|---|
| 扫码成功后 `Set-Cookie` 的**真实字段集合** | ❌ **未实测**（无账号） |
| `data.code = 0` 成功态的**完整响应体** | ❌ 未实测 |
| `86090 已扫码未确认` | ❌ 未实测（文档推断） |
| 登录后能否真拿到 **FLAC** | ❌ 未实测 |
| 大会员 vs 普通登录的音质差异 | ❌ 未实测 |
| `bili_jct` 的 CSRF 校验形态（header? form field?） | ❌ 未实测 |
| 登录后音频区 `collections/*` 的真实返回 | ❌ 未实测 |
| `access_key`（APP 登录 token）换取付费音频完整版 | ❌ 未实测（`biu` 源码注释如此声称） |
| `SESSDATA` 的有效期 / 刷新机制 | ❌ 未实测（`poll` 返回了 `refresh_token` 字段，暗示有刷新机制，但本次为空串） |
| WebView 登录 `passport.bilibili.com/login` 抓 Cookie 的可行性 | ❌ 未实测 |
| 登录态下音频 CDN 是否放宽 Referer 校验 | ❌ 未实测 |
