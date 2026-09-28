# 探针 · P0：QQ 音乐 VIP 歌曲「误判无版权 + 自动切歌」

> 版本：v3.2.0 ｜ 采集时间：2026-09-28 18:20–18:40 CST
> 设备：SM-G9209（`0715f763f54c023a`，Android 7.0 / API 24，已 root，Magisk）
> 账号：设备上已登录的 QQ 音乐账号（`ncrust_qq_prefs.qq_vip_type = 1`，`qq_vip_expire_at = 1793548800` = 2026-11-01）
> 原始命令与输出：本文件内联（全部可复现）；cookie 与 purl/vkey **一律未落盘**

---

## 0. 一句话结论

**用户报的「误判无版权」不是一次判定错误，而是「客户端根本没有判定」。**
v3.1.0 的取链链路把「需要登录 / 需要会员 / 需要单曲购买 / 网络失败 / 该档位没有文件」
**五种完全不同的结果折叠成一个 `null`**，然后由 `PlayerViewModel.onUrlUnavailable`
无条件当成「这首歌放不了」→ 弹「此源无版权，可切另一源」→ 自动跳下一首。

因此：

- 根因**不是** cookie / guid / `media_mid` / 取链参数（这四项本轮逐一实测，**全部正确**）；
- 根因是**判定逻辑缺失 + 失败路径合流**（铁律 20 / 21 要禁的那两件事）；
- 复现「VIP 歌被跳过」需要**恰好**命中「五类失败之一」，所以它**不稳定** ——
  用有效 VIP 票据在本机重放 12 首歌，8/8 档全部拿到 purl，一次都没复现。
  但同一个 `104003` 在**匿名态**是 8/8 档稳定出现（见 §2），
  而客户端对匿名态与登录态的处理**是同一行代码**。

---

## 1. 任务书 10 问 → 逐条结论

| # | 问题 | 结论 | 证据 |
|---|---|---|---|
| 1 | QQ 取链返回的原始 `code` / `result` / `msg` / `purl` | 外层 `req.code=0`（成功）；每个档位一条 `midurlinfo`，含 `result` / `purl` / `tips` / `pneedbuy` / `isbuy` / `vkey` / `ekey` / `subcode`；**登录态 8/8 档 `result=0` + 非空 purl**，**匿名态 8/8 档 `result=104003` + 空 purl** | §2 |
| 2 | Ncrust 在哪一步判定「无版权」？判据是什么？ | **没有任何一步做版权判定。**判据根本不存在：`QqCatalogApi.kt:124` 与 `:185` 的 KDoc 早就写明「QQ 侧没有任何权威的版权字段……把『拿不到』说成『无版权』是撒谎」，而播放路径在 `PlayerViewModel.kt` 的 `onUrlUnavailable` → `showSourceFallbackHint` 里把**任何** `null` 一律说成「此源无版权」 | §5 |
| 3 | VIP cookie 是否正确携带？ | **正确。**`QqCookie.requestCookie` 只保留认识的身份字段并注入 `Cookie` 头；`comm` 里另注入 `uin` + `authst`（`QqCookie.applyIdentity`，v2.1.4 补的）。实测同一曲目带票据 8/8 档 `result=0`、去掉票据 8/8 档 `104003` ⇒ **票据确实被服务端认了** | §2 的 A/B |
| 4 | `guid` 生成是否符合规则？ | **符合。**`QqIdentity.guid` 从 16 位十六进制设备种子派生 10 位数字（`QqIdentity.kt:59`）；本地生成值 `5830514025` 形状正确且服务端接受（`req.code=0`）。规则本身是「10 位数字」，本实现的取值域与之一致 | §2 的请求体 |
| 5 | 取链参数：`filename` 格式、`songmid` / `media_mid` 是否混淆？ | **格式正确、没有混淆，但有一个已知的坏形状。**`filename = <前缀><media_mid>.<扩展名>`（`QqQuality.fileNameFor`），实测走通；`media_mid` 与 `songmid` 在实测样本里**确实不同**（《给我一首歌的时间》`mid=004BhQke4adHcf`、`media_mid=000uSJdv1nG6w2`）。⚠️ 用错 mid 时**服务端照样回 purl**（同一次实测的三个变体都拿到 purl），**但 CDN 会 404 `file not exist`** —— 换句话说这个错误在取链阶段是**静默**的，只在取字节时才现形 | §3 |
| 6 | QQ 歌曲的 `pay` 字段是否正确解析？ | **解析正确但只是布尔。**`QqSongMapper.kt:185` 读 `pay.pay_play == 1` → `SongItem.memberOnly`。实测 12 首样本里 `pay_play=1` 的 11 首、`=0` 的 1 首，与 `memberOnly` 一致。⚠️ **`pay` 只在搜索阶段可见**，取链响应里没有它，所以「这首歌要不要会员」在取链失败时**无法回读** —— 这正是必须解析 `result` 码的原因 | §2 / §4 |
| 7 | 错误码分类：哪些是「无版权」/「需要 VIP」/「需要单曲购买」/「版权方下架」？ | **实测到的只有一个码：`104003` = 需要登录 / 需要会员**（匿名态稳定复现）。**没有观测到任何「无版权」或「版权方下架」的码。**`pneedbuy` / `isbuy` 字段存在但本轮 12 首全为 0 ⇒「非零时服务端怎么答」**未验证**。`type=-1`（30 秒试听）**未复现** | §2 / §4 |
| 8 | 自动切歌的触发路径 | `QqApi.fetchPlayUrl` 返回 `null` → `SourceRouter.resolveUrl` 返回 `null` → `PlayerViewModel.fetchUrlOfflineFirst` 返回 `null` → `onUrlUnavailable` → `maySkipOnUrlFailure(USER) == true` → `showSourceFallbackHint` + `autoSkipGuard.requestAutoSkip()` → `onUnplayableCallback()` → 下一首。**五类失败在该路径上完全同形** | §5 |
| 9 | 同账号在官方 App 能否播放 | **未验证**（本轮没有安装官方 App，也不该为了取证安装）。间接证据：同一账号同一曲目在**本机**取链 8/8 档成功且 M800 直链 `HTTP 206 + 真实 ID3 字节`（§3）⇒ 账号权益本身是有效的 | §3 |
| 10 | 对比其他 VIP 歌曲（非周杰伦）是否同样误判 | **本轮 12 首（含周杰伦 5 首、林俊杰 3 首、陈奕迅 1 首、久石让 1 首、欧美 2 首）全部 8/8 档取链成功，无一复现。**唯一观察到的差异是《Hotel California》的 `AI00` 档回 `104003` 而 `Q000` 档回 0 —— **同一首歌不同档位的权限不同**，这说明「逐档位分类」是必需的，不能只看一条 | §2 |

---

## 2. 实测 A：同一首歌在三种身份下的逐档位结果

**方法**：用设备上真实的 VIP cookie，按 `QqRequests.vkey` 的**逐字形状**（同样的 `comm`、
同样的 `filename[]`、同样的 `guid`）向 `https://u.y.qq.com/cgi-bin/musicu.fcg` 发请求。

歌曲：周杰伦《给我一首歌的时间》（`songmid=004BhQke4adHcf`、`media_mid=000uSJdv1nG6w2`、
`pay={"pay_down":1,"pay_month":1,"pay_play":1,"pay_status":0,"price_album":0,"price_track":200,"time_free":0}`）。

| 变体 | 身份 | AI00 | Q000 | Q001 | RS01 | F000 | C400 | M800 | M500 |
|---|---|---|---|---|---|---|---|---|---|
| A | 登录态 + `comm.authst` + 正确 `media_mid` | `0` | `0` | `0` | `0` | `0` | `0` | `0` | `0` |
| B | **完全匿名**（无 Cookie、`uin=0`、无 `authst`） | `104003` | `104003` | `104003` | `104003` | `104003` | `104003` | `104003` | `104003` |
| C | 登录态但**把 `songmid` 当 `media_mid`** | `0` | `0` | `0` | `0` | `0` | `0` | `0` | `0` |

- A 与 C 的 `purl` **都非空**（长度 203–232）⇒ **服务端在取链阶段不校验 `media_mid` 的正确性**，
  这是 §3 那个「静默坏链」的成因。
- B 的 `purl` **全为空**，`result` 全为 `104003` ⇒ 这是**唯一在本次探针里稳定复现的失败码**，
  它的语义是「需要登录 / 需要会员」，**与版权无关**。

**免费歌匿名对照**（同一方法，《千与千寻》/《城南花已开》）：

```
results = 104003,104003,104003,104003,104003,0,104003,0
                （第 6 档 C400 与第 8 档 M500 拿到 purl）
```

⇒ 匿名态**并非一律取不到链**：免费曲目的低档位可以放。这也说明
「匿名 ⇒ 取不到 ⇒ 无版权」这条推理在**两个方向上**都是错的。

---

## 3. 实测 B：CDN 取字节（「purl 非空」不等于「能播」）

对同一首歌逐档位取到的 `purl` 做 `Range: bytes=0-2047` 实测：

| 档位 | purl | CDN 结果 |
|---|---|---|
| `M800`（320k mp3） | 有 | **HTTP 206**，2048 字节，`Content-Type: audio/mpeg`，魔数是真 mp3 |
| `F000`（无损 FLAC） | 有 | **HTTP 206**，`Content-Type: audio/x-ogg`（⚠️ 不是 `audio/flac`） |
| `RS01`（Hi-Res） | 有 | **HTTP 404** `{"errorcode":-46628,"errormsg":"file not exist, retcode:-46628"}` |
| `AI00`（臻品母带） | 有 | **HTTP 206**，`Content-Type: audio/x-ogg` |
| `M800`，**故意用 `songmid` 当 `media_mid`** | 有 | **HTTP 404** `file not exist` |

**两条独立结论：**

1. **坏链是静默产生的。**「服务端给了 purl」**不等于**「CDN 有这个文件」。
   本曲的 `RS01` 就是活证据；用错 mid 也是。而 `QqQuality.attemptsFor` 的挑选规则是
   「按优先级取第一个 purl 非空的」⇒ 客户端会把这条必然 404 的链交给 ExoPlayer。
   这与 `QqApi` 类文档里那条「**绝不返回坏链接**」的硬纪律直接冲突。
   *本版不引入「取链后再探一次字节」的额外往返*（那会给每次播放加一个 RTT），
   而是让失败分类把这条链的失败**如实**归到「音源暂时取不到文件」而不是「无版权」。
2. **`F000`/`AI00` 是 `audio/x-ogg` 而不是 FLAC。** 这条只是记录事实，
   本轮**没有**验证 ExoPlayer 能否解出声音（不属于本 P0 的判定面）。

---

## 4. 实测 C：多曲目扫描（判断「是否普遍」）

12 首、登录态、一次批量问 8 个档位：

| 关键词 | 命中曲名 | `pay_play` | `price_track` | 8 档 `result` | 首个有 purl 的档 |
|---|---|---|---|---|---|
| 给我一首歌的时间 周杰伦 | 给我一首歌的时间 | 1 | 200 | `0,0,0,0,0,0,0,0` | AI00 |
| 最伟大的作品 周杰伦 | 最伟大的作品 | 1 | 400 | `0,0,0,0,0,0,0,0` | AI00 |
| 说好不哭 周杰伦 | 说好不哭 (with 五月天阿信) | 1 | 400 | `0,0,0,0,0,0,0,0` | AI00 |
| 稻香 周杰伦 | 稻香 | 1 | 200 | `0,0,0,0,0,0,0,0` | AI00 |
| 那些你很冒险的梦 林俊杰 | 那些你很冒险的梦 | 1 | 200 | `0,0,0,0,0,0,0,0` | AI00 |
| 江南 林俊杰 | 江南 | 1 | 200 | `0,0,0,0,0,0,0,0` | AI00 |
| 晴天 周杰伦 | 晴天 | 1 | 200 | `0,0,0,0,0,0,0,0` | AI00 |
| 孤勇者 陈奕迅 | 孤勇者 | 1 | 200 | `0,0,0,0,0,0,0,0` | AI00 |
| Hotel California Eagles | Hotel California | 1 | 200 | **`104003`,0,0,0,0,0,0,0** | Q000 |
| Shape of You Ed Sheeran | Shape of You | 1 | 200 | `0,0,0,0,0,0,0,0` | AI00 |
| 千与千寻 久石让 | いつも何度でも | 0 | 200 | `0,0,0,0,0,0,0,0` | AI00 |
| 城南花已开 | 城南花已开 | 0 | 200 | `0,0,0,0,0,0,0,0` | AI00 |

- **11/12 首 VIP 曲目（`pay_play=1`、`price_track=200/400`）在有效票据下都能取到链**
  ⇒ 用户报的「VIP 歌被误判」**不是**一个普遍的服务端行为，而是**客户端在若干条失败支路上的行为**。
- 《Hotel California》那一行是**关键样本**：**同一首歌的不同档位权限不同**。
  客户端如果只取「第一条被拒的 result」做结论，就会得到错误的分类 —— 所以分类必须**逐档位收集再收敛**。

---

## 5. 静态链路审计：自动跳歌是怎么被触发的

```
QqApi.fetchPlayUrl(song, level)                      // app/src/main/java/.../qq/QqApi.kt
  ├─ songMid == null                    -> null      // 结构性
  ├─ requestVkeyBatch(...) == null      -> null      // 网络 / HTTP / 响应畸形
  └─ 8 档全部无 purl                     -> null      // 需要登录 / 需要会员 / 没有文件 / ……
                                    ↓
QqMusicSourceProvider.resolveUrl  -> null
                                    ↓
SourceRouter.resolveUrl           -> null
                                    ↓
PlayerViewModel.fetchUrlOfflineFirst
  └─ recallOfflineCache 未命中        -> null
                                    ↓
PlayerViewModel.onUrlUnavailable(songId, requested, origin)
  ├─ maySkipOnUrlFailure(origin) == false（音质切换/降档重试）-> 回退档位 / 停下
  └─ maySkipOnUrlFailure(origin) == true （USER / AUTO_NEXT / PRELOAD）
        ├─ showSourceFallbackHint(songId)      // ★「此源无版权，可切另一源：网易云」
        └─ autoSkipGuard.requestAutoSkip()     // ★连续 5 次熔断
              └─ onUnplayableCallback()        // ★跳到下一首
```

**四个缺陷（都在上面这几行里）：**

| # | 缺陷 | 位置 | 后果 |
|---|---|---|---|
| D1 | 五类失败折叠成一个 `null`，**没有分类** | `QqApi.fetchPlayUrl` 的四个 `return null` | 播放链无法区分「要会员」与「没有版权」 |
| D2 | `lastUrlFailure` 有 `resultCode` / `tips`，但**全仓库零个读取方** | `QqApi.kt`（唯一出现处是本文件的赋值） | 服务端说的原因从未到达任何决策点 |
| D3 | 提示文案**断言了客户端没有证据的结论** | `PlayerViewModel.showSourceFallbackHint`：`strings.tagSwitchSourceHint + "：" + label` | 用户被告知「此源无版权」——一句客户端无法验证的话（违反铁律 20） |
| D4 | 版权判定失败与播放失败**共用同一条路径**，且该路径会跳歌 | `onUrlUnavailable` 的 `else` 分支 | 权限不足被当成「这首放不了」并被跳过（违反铁律 21） |

**真机复现记录（诚实版）：**

- 用有效 VIP 账号在本机取链 12 首 × 8 档 = 96 次，**零次**出现「无 purl」；
- 因此**没有**在真机上拍到「VIP 歌被跳过」的那一刻。用户报告的这一现象
  **本轮未能稳定复现**（与任务书「可能不能稳定复现」一致）；
- 但 **D1–D4 是源码级事实**，且匿名态的 `104003` 是稳定可复现的入口 ——
  只要票据失效/被服务端拒绝一次，用户就会看到「无版权 + 跳歌」。
  也就是说：**缺陷是确定的，触发条件是概率的。**

---

## 6. 根因（明确回答任务书要求的那两件事）

### 根因：**不是** cookie / guid / 错误码 / 参数，而是**判定逻辑缺失**

| 任务书列的候选根因 | 本轮实测判决 |
|---|---|
| cookie 没带对 | ❌ 证伪（§2 变体 A vs B 是干净 A/B） |
| `guid` 生成不对 | ❌ 证伪（10 位数字、服务端接受、`req.code=0`） |
| 错误码读错 | ⚠️ **部分成立**：客户端**根本没有读** `result` 码（D1/D2） |
| 参数（`filename` / `songmid` / `media_mid`）混淆 | ❌ 取链阶段证伪；⚠️ 但「用错 mid 时 purl 照样非空、CDN 才 404」是**真实的静默坏链**（§3） |
| 判定逻辑 | ✅ **根因**：不存在版权判定，且失败路径合流（D3/D4） |

### 自动切歌的触发路径

`QqApi.fetchPlayUrl → null` ⇒ `onUrlUnavailable` ⇒ `maySkipOnUrlFailure(USER)=true`
⇒ `showSourceFallbackHint()` + `autoSkipGuard.requestAutoSkip()` ⇒ `onUnplayableCallback()`。
**五类失败全部走这一条**，所以「需要会员」也被跳过了。

---

## 7. 修复方向（本版落地）

| 缺陷 | 修复 | 落点 |
|---|---|---|
| D1 | 新增 `QqRejection` 分类（纯逻辑 + 单测），`fetchPlayUrl` 的每一处失败都写分类 | `qq/QqRejection.kt`、`qq/QqApi.kt` |
| D2 | `lastUrlFailure.rejection` + `lastRejection` 成为**唯一**分流判据；传输层也记账（HTTP 401/403 = 凭证过期） | `qq/QqApi.kt`、`qq/QqClient.kt` |
| D3 | 文案按分类产出；**只有服务端显式声明无版权时才出现「暂无版权」**（网易云的 `noCopyrightRcmd`）；QQ 侧永远不产出那一档（有穷举单测守） | `player/ResolveFailureText.kt`、`ui/i18n/*` |
| D4 | 新增 `resolveFailureAction(kind)` 三态表：只有 `UNRESOLVABLE` 允许跳歌；`NETWORK` 有界重试；其余**停下 + 说明原因** | `player/ResolveFailure.kt`、`ui/viewmodel/PlayerViewModel.kt` |

**未做（如实记录）：**

- **不引入「取链后探一次字节」的二次校验**：那会给每次播放加一个 RTT，
  而 §3 的坏链只是**偶发**（12 首里 1 首的 1 个高档位）。改用「失败分类 + 有界降档重试」
  把它的代价限制在「多试一档」，而不是把所有播放都变慢。
- **`pneedbuy` / `isbuy` 非零时服务端的确切回答未验证** —— 分类里有防御分支，
  但**没有实测证据**，探针文档与本文件均如实标注。
- **`F000` / `AI00` 的 `audio/x-ogg` 能否被 ExoPlayer 解码未验证**（不属于本 P0 判定面）。
