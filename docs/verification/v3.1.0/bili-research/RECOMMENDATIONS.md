# 接入方案推荐 —— Ncrust v3.1.0 · B站音源

> 本文是 `docs/verification/v3.1.0/bili-research/` 的结论篇。
> 所有"实测"字样均可在 `EVIDENCE.md` 与 `evidence/*.txt`（73 个原始响应文件）中逐条复现：
> ```bash
> bash docs/verification/v3.1.0/bili-research/collect_evidence.sh
> ```
> **未标注"实测"的结论一律是文档推断，已在第 5 节集中列出。**

---

## 0. 结论速览（一页）

| 决策点 | 推荐 | 一句话理由 |
|---|---|---|
| 接入模式 | **音频区 `au` 为播放主体 + 视频搜索为发现入口**（"两条腿"） | 音频区**匿名到 320K 且零风控**；但音频区**没有搜索接口**（穷举 10 个候选全 404/空壳），视频搜索是唯一能"按关键词找到 B站内容"的路 |
| Wbi | **必须实现**，但只对视频路径生效；音频区路径**完全不需要** | 服务端确实验签（`view/detail` A/B：`-352` → `0`），但音频区全部端点匿名直通 |
| 登录 | **本版不做** | 匿名已覆盖"搜索→播放→歌词"全链路；登录只换来收藏夹/投币/FLAC，却引入账号级风控与合规风险 |
| 开关默认值 | **默认关闭**（`BiliPrefs.DEFAULT_ENABLED = false`，现有实现已如此） | 见 §6 的法律风险；且 B站音源的风控状态**会随出口 IP 与时间变化**，不适合做默认开启的主音源 |
| URL TTL | **以 URL 里的 `deadline` 为准（`now+7200s`），再乘 0.9 安全系数**；`timeout`(10800) **不可信** | 实测 `timeout` 是名义常量、`deadline` 才是真实过期；两者差 1 小时 |
| 歌词 | **B站自己的 `song/lyric`（标准 LRC）**，不引第三方 | 实测是 `[MM:SS.mm]`，**可直接喂现有 `LrcParser`**；社区项目去接 LRCLIB/网易云是因为不知道这个端点 |
| 音质映射 | `standard→qn0(128K)` / `higher→qn1` / `exhigh→qn2(320K)`；`lossless+` 一律**不请求** | 匿名没有 FLAC（`qualities[]` 里根本没有 `type:3`），请求了也是静默降级 |

---

## 1. 推荐接入模式：音频区 `au` 为主体，视频为发现入口

### 1.1 两条路的实测对照

| 维度 | **音频区 `au`** | **视频 DASH** |
|---|---|---|
| 搜索 | ❌ **无接口**（穷举 `/song/search`、`/search`、`/search/song`、`/search/type`、`/song/list`、`/song/query`、`/song/searchByKey`、`/menu/search`、`app x/v2/search`、`api x/v2/search/type` 全部 404 或空壳/`-400`） | ✅ `search_type=video`（可加 `tids=3` 音乐区） |
| 统一搜索里有分区吗 | ❌ `search/all/v2` 的 `pageinfo` 与 `result_type` 枚举里**都没有 audio/music** | ✅ 有 `video` |
| 播放（匿名） | ✅ **128K/192K/320K**（APP 端点），**ffprobe 验证 321584 bps** | ✅ 3 条 DASH 音轨（64K/132K/192K，`fnval=4048` 时还有 FLAC/杜比） |
| 风控 | ✅ **零风控** —— 全程实测无一次失败 | ⚠️ `wbi/playurl` 对某些出口 **整体 412**；`view/detail` 会在密集请求后进入整体风控 |
| 歌词 | ✅ `song/lyric` 返回**标准 LRC** | ❌ 无数据源（要走视频字幕，另一套且不保证有） |
| 元数据 | ✅ `title/author/cover/duration/bvid/statistic/tag/member` | ✅ 视频标题/UP主/封面，但"作者"是 UP 主不是歌手 |
| 曲目粒度 | ✅ 就是"一首歌" | ⚠️ 一个视频可能是合集/电台/整张专辑（实测有 `duration:5963` 的一百分钟合集） |
| CDN 备份 | ⚠️ `cdns[]` **只有 1 条** | ✅ `baseUrl` + `backupUrl`（2 条） |
| `crtype=2 + msid!=0` | ❌ `code:0` 但 **`cdns:null`**（无流） | — |
| Referer | ⚠️ **必须带**，否则 403 | ⚠️ **必须带**，否则 403 |

### 1.2 为什么这个组合（而不是"只做一条腿"）

**不支持"只做音频区"**：没有搜索 = 用户没法找到歌，只能靠榜单/热门歌单（11 + 12 个固定歌单）+ 直链输入。这不是一个音乐 App 的搜索体验。

**不支持"只做视频 DASH"**（社区 4/5 个项目的做法）：会失去
① **零风控**的稳定播放路径（视频 `/x/player/wbi/playurl` 在本机出口直接 412）；
② **LRC 歌词**（视频没有对应数据源，`biu` 因此去接 LRCLIB + 网易云）；
③ 干净的"一首歌 = 一个条目"粒度。

**"两条腿"的代价（必须承认）**：

- 视频搜索出来的条目，**播放走视频、歌词为空**；音频区条目**有歌词但搜不到**。同一个 UI 列表里两种条目的能力不同 ⇒ **必须在曲目上标注来源形态**（见 §4.4）。
- 两套生命周期（`cid` 要额外请求、DASH 音轨要选、`fnval` 不同）⇒ Provider 内部复杂度上升。
- 用户搜"周杰伦"得到的是一堆**视频**，不是"音频区里的周杰伦" —— 这是 B站平台上客观事实，**不是实现缺陷**，UI 文案要说清楚。

### 1.3 音频区条目的入口：直链语义（唯一可行）

既然音频区搜不到，**能拿到 `auid` 的途径只有**：

| 途径 | 可行性（实测） |
|---|---|
| 用户粘贴 `au123456` / `bilibili.com/audio/au123456` 链接 | ✅ 推荐（现有实现已做 `parseAuidKeyword`） |
| **榜单**（`/web/menu/rank`，12 个）与**热门歌单**（`/web/menu/hit`，11 个） | ✅ **实测可用，匿名**，是"B站音频区歌单"功能的天然数据源 |
| 歌单内曲目（`/web/song/of-menu`） | ✅ 实测可用 |
| 站外链接 / 分享 | ✅ |

⇒ **建议补一个"B站音频区榜单/歌单"入口**（首页或 B站音源专属页），这是音频区**唯一**的浏览能力，成本很低（两个端点，都已实测 `code:0`，且**不需要签名、不需要登录**）。若不补，音频区条目实际上只能靠手输 auid 触达，"音频区"这条腿就白接了。

---

## 2. Wbi 签名实现方案

### 2.1 放在哪个类

**现有结构是对的，保持**：`bili/BiliWbi.kt`（纯逻辑、无 Android、无 IO、JVM 可单测）+ `bili/BiliApi.kt`（密钥缓存与 HTTP）。

理由（来自本项目既有教训）：本项目把 `EapiCrypto` / `WeapiCrypto` 也放在 `network/crypto/` 下做纯逻辑，`PlayerDragSnap` / `YrcParser` 也抽出纯函数配 JVM 单测 —— `BiliWbi` 完全符合这个模式。

### 2.2 怎么缓存 key

| 项 | 建议 | 依据 |
|---|---|---|
| TTL | **6–12 小时**（现有 `WBI_KEY_TTL_MS = 6h` ✅ 合理，可保留） | 上游文档称"每日变化"但**本次未实测轮换**（只观测到一个 key 值）；6h 保证一天至少刷一次且不会每请求都问 |
| 存储 | **仅内存**（`@Volatile` 字段），不落盘 | key 全站统一但每日轮换 ⇒ 落盘只会在跨天冷启动时拿到一个已失效的 key，白白多一次 412 |
| 作用域 | **全站统一**，与账号/IP/UA 无关（实测三种 UA + 空 UA 拿到逐字节相同的 key） | `evidence/93-wbi-key-global.txt` |
| 强制刷新触发 | `-403` / `-352`（**不含 `-1200`**，见 §2.3） | 见下 |

### 2.3 失败怎么降级（**这一节是对现有实现的修正**）

现有 `BiliApi.isSignatureRejected` 把 **`-1200`** 与 **非 JSON（412 HTML）** 都当签名失败。实测两者都不该这样处理：

| 现象 | 实测真因 | 正确处理 |
|---|---|---|
| `code:-1200 被降级过滤的请求` | **非法 `search_type`**（`music`/`audio`/`foobar` 三者返回完全相同）或**越界翻页**（`numResults` 上限 1000） | **不是签名问题** —— 刷新 key 无用。应当记日志并**放弃这次搜索** |
| **HTTP 412**（HTML 正文） | **路径级封禁**（`/x/player/wbi/playurl` 对本机出口整体 412，**加签名也无效**；4 种组合全 412） | **不是签名问题** —— 重签无用。应当**降级到别的路径**（旧 `/x/player/playurl`）或**降级到音频区** |
| `code:-403` | 缺 `w_rid`（经典风控） | ✅ 刷新 key + 重签 + **重试一次** |
| `code:-352` | 风控校验（**可能连有效签名也拒**，实测 18 分钟后升级为整体风控） | ✅ 刷新 key 重试一次；仍失败则**标记该端点"冷却"**，一段时间内不再尝试 |

**推荐的降级链（视频路径）**：

```
① /x/player/wbi/playurl  (带 w_rid)
      ↓ 412 / -352
② /x/player/playurl      (旧非 wbi 路径 + Cookie: buvid3=<finger/spi 的 data.b_3>)   ← 实测 code:0
      ↓ 仍失败
③ 该曲目标记为"不可播"，交给 Ncrust 既有的跳歌逻辑（绝不返回指向 HTML 的 URL）
```

### 2.4 URL TTL（**音频流有时效 —— 铁律 26**）

实测（`evidence/80-cdn-referer-and-ttl.txt` F 段，连测 3 次）：

```
timeout  = 10800        （响应字段，名义 3 小时，恒定不变）
deadline = now + 7200   （URL query 参数，真实 2 小时，3 次全为 7200/7201）
```

**两种流的 `deadline` 一致**（音频区 `song/url` 与视频 DASH `playurl` 都是 `now+7200`）⇒ 这是 B站全站统一的签名有效期。

**推荐 TTL**：

```kotlin
// 现有实现已是正确形状（BiliParse.expiryFromUrl）：
ttl = (deadlineSec * 1000 - SAFETY_MARGIN_MS) - now
      ↓ deadline 缺失时兜底
ttl = timeoutSec * 1000 - SAFETY_MARGIN_MS      // 走这条说明响应异常
      ↓ 两者都缺
ttl = 30 分钟（DEFAULT_TTL_MS，故意远保守于服务端）
```

⚠️ **一个必须强调的细节**：`deadline` 是 **URL 层面的**，而**同一首歌的 URL 每次都不同**（实测连取 3 次 `trid`/`upsig` 全变）。所以：

- **缓存 `URL → 过期时刻`** 才有意义，**不要**缓存 `auid → URL` 而不记过期；
- **冷启动后不要复用上次的 URL**（可能已过 2 小时）；
- Ncrust 既有 `OfflineUrlStore`（key→最后成功播放的 URL，≤300 LRU，**无 TTL 字段**）—— **B站条目必须带上过期时刻**，否则会拿一个死 URL 去播放并表现为"缓冲不动"。这是接入时必须处理的一处**既有结构改动**。

**安全系数建议**：`expiryFromUrl` 已有的 `SAFETY_MARGIN_MS` 应 ≥ 60s（网络往返 + ExoPlayer 起播 + seek 的时间）。建议 **90–120 秒**。

### 2.5 速率限制与负缓存（**新增建议，来自风控升级的实测**）

实测发现风控**会随时间/请求量升级**：同一个 A/B 在 02:29:37 成立（有签名 → `code:0`），到 02:46:54 时**连有效签名也被拒**（`-352`）。而同一时刻音频区三个端点**全部正常**。

⇒ **对 wbi / 视频端点必须做**：

1. **全局串行 + 最小间隔**（建议 ≥ 300ms），避免聚合搜索时三个音源并发把 B站打出风控；
2. **失败负缓存**：某端点连续失败 N 次后进入**冷却期**（建议 5 分钟），期间直接走降级路径，不再打它；
3. **不做自动重试风暴**：签名重试**上限 1 次**（现有实现已是"只重试一次" ✅）；
4. 开发/调试期尤其注意 —— **反复手测 wbi 端点会把开发机出口打进黑名单**（本次就是这样，文档已如实记录）。

---

## 3. 是否需要登录 / 匿名可用边界

### 3.1 结论：**本版不做登录**

### 3.2 匿名可用功能（**逐条实测**）

**✅ 匿名完全可用**

| 功能 | 端点 | 实测 |
|---|---|---|
| 视频搜索（含 `tids=3` 音乐区） | `/x/web-interface/wbi/search/type?search_type=video` | `code:0` |
| 曲目元数据 | `/web/song/info?sid=` | `code:0` |
| 音频流 **128K** | `/audio/music-service-c/url?songid=&quality=0` | `type:0`, 4179753 B |
| 音频流 **192K** | 同上 `quality=1`（或 web 端点的任意 qn） | `type:1`, 6244629 B, **193568 bps** |
| 音频流 **320K** | 同上 `quality=2` | `type:2`, 10374528 B, **321584 bps** |
| 音质能力表 | 同上返回的 `qualities[]` | 3 档，`require` 全 0 |
| **LRC 歌词** | `/web/song/lyric?sid=` | `code:0`，标准 LRC |
| 标签 | `/web/tag/song?sid=` | `code:0` |
| 创作成员 | `/web/member/song?sid=` | `code:0` |
| 播放/收藏计数 | `/web/stat/song?sid=` | `code:0` |
| **榜单列表（12 个）** | `/web/menu/rank?pn=&ps=` | `code:0` |
| **热门歌单（11 个）** | `/web/menu/hit?pn=&ps=` | `code:0` |
| 歌单元数据 | `/web/menu/info?sid=<amid>` | `code:0` |
| **歌单曲目列表** | `/web/song/of-menu?sid=<amid>&pn=&ps=` | `code:0` |
| 视频分 P / cid | `/x/player/pagelist?bvid=` | `code:0` |
| 视频详情 | `/x/web-interface/view?bvid=`、`/x/player/wbi/v2` | `code:0` |
| **视频 DASH 取流** | `/x/player/playurl?...&fnval=4048` + `buvid3` | `code:0`，3 条 audio |
| 新歌 / 音乐热榜 | `/x/centralization/interface/new/music`、`/x/copyright-music-publicity/toplist/*` | `code:0` |
| `buvid3` 获取 | `/x/frontend/finger/spi` | `code:0` |

**🔒 匿名不可用（`code:4511003 用户未登录`）**

| 功能 | 端点 |
|---|---|
| 我的收藏夹（列表 / 详情 / 曲目） | `/web/collections/{list,info,songs-coll}` |
| 投币状态 / 投币 | `/web/coin/audio`、`/web/coin/add` |

**❌ 匿名拿不到**

| 项 | 实测 |
|---|---|
| FLAC 无损（`quality=3`） | **静默降级为 320K**（`type:2`）；`qualities[]` 表里**根本没有 `type:3` 条目** |
| 更高码率 | 无 |

**⚠️ 匿名**不可用**但"看起来可用"的坑**

| 现象 | 实测 |
|---|---|
| `code:0` 但 `cdns:null` | `crtype=2 且 msid!=0` 的转载音频（au127015 / au130878）⇒ **必须把 `cdns:null/[]` 判为不可播** |
| `code:0` 但 `size` 是 146/150 | 2023 年后上传的音频，`size` 字段语义已变/损坏（HTTP 实体是 5MB/2.6MB/144MB）⇒ **绝不用于判大小** |
| `code:0` 但 `title`/`cover` 是空串 | `song/url` 的这两个字段**恒为空**，不要指望它，回 `song/info` |

### 3.3 为什么不做登录（4 条理由）

1. **匿名已覆盖完整播放链路**（上表），登录只换来 3 项非必要能力；
2. **登录把风险从 IP 升级到账号**：实测已经看到"出口 IP 被打进风控"的现象；带 `SESSDATA` 后，**用户自己的 B站账号**会暴露在同样的风控/封禁风险下。第三方客户端让用户拿主账号冒这个险，性价比极低；
3. **登录会牵动三处既有结构**：`CookieManager`（现只有一个 `user_cookie` 键）、`UserScreen`（登录入口）、`MusicSource.loginSources`（B站不在其中 —— **现有实现已经有意识地把 B站排除在"可登录音源"之外**，`otherThan()` 不再遍历 `selectable`，这个设计是对的，应保留）；
4. **写操作（投币/收藏）的失败形态是 HTTP 200 + 业务码**，必须正确处理 `bili_jct`，且这些写操作是**最容易被法务/风控盯上**的行为（见 §6）。

**UI 上要诚实**：B站音源显示"**无需登录**"，并在音质选择处说明"最高 320K（匿名）"；不要显示"登录以获得无损"——那是在承诺一个未验证的能力（实测匿名环境**无法确认**登录后能否拿到 FLAC）。

---

## 4. 与 Ncrust 现有 Provider / Source 架构的集成

> **本节对照的是仓库里**当前正在写的**实现**（`app/src/main/java/com/takahashirinta/ncrust/bili/` 五个文件，2026-09-28 02:27–02:31 写入）。
> 结构判断：**整体方向正确**，下面逐条给出"已对/需改"。

### 4.1 `Source.BILIBILI`

```kotlin
enum class MusicSource(val key: String) {
    NETEASE("netease"), QQMUSIC("qqmusic"), BILIBILI("bilibili"),
    // selectable   = [NETEASE, QQMUSIC, BILIBILI]   ← UI 顺序
    // loginSources = [NETEASE, QQMUSIC]             ← 可登录音源，B站不在其中
}
```

✅ **现有实现正确**，三个要点都做对了：

1. `key = "bilibili"` 是**稳定字符串**（不是 ordinal）——符合本项目"以后插音源不能让历史数据指错源"的既有纪律；
2. **`selectable` 与 `loginSources` 分开** —— 关键设计。`otherThan()` 的语义是"提示用户换个源登录"，B站不可登录，放进 `selectable` 会让提示变成死路。现有 KDoc 已写明这一点；
3. `MusicSource.DEFAULT` 仍是 `NETEASE`，未知 key 一律回落 —— 老数据兼容不变。

### 4.2 `TrackKey(source = BILIBILI, id = <合成数字 id>, sourceId = "au:<auid>" / "bv:<bvid>:<cid>")`

⚠️ **任务书写的是 `TrackKey(source=BILIBILI, id="au<auid>", mediaId=null)` —— 但 `TrackKey.id` 是 `Long`，字符串 `"au123"` 放不进去。** 现有实现的做法（合成数字 id + `sourceId` 承载载荷）才是对的，理由：

1. `TrackKey.id` 的类型是 `Long`（见 `TrackKey.kt`），**结构性约束**；
2. 本项目已有成熟的"数字 id 需要隔离"的方案：`SourceIds.QQ_ID_FLAG = 1L shl 62` —— 把 QQ 的 id 抬到网易云到不了的区间。B站应**沿用同一模式**（现有实现已有 `isBiliId(id)` 与 `sourceOfId` 分支）；
3. `sourceId` 的既有语义就是"取链/取词必须带、但不参与身份相等"的载荷 —— B站的 `auid`/`bvid+cid` 正是这个语义。

**必须满足的三条不变量**（来自 `TrackKey` 的既有 KDoc）：

| 不变量 | B站侧的含义 |
|---|---|
| `equals` 只比 `(source, id)` | 同一个 auid 在冷启动恢复时缺 `sourceId` 也必须判为同一首 —— 现有实现用**合成 id 自带可逆载荷**（`parseSourceId`）满足 |
| `id` 必须**自解释**音源 | `sourceOfId(id)` 要能从 id 反推 `BILIBILI`（防止持久化丢 `source` 字符串后串台） |
| **撞号在结构上不可能** | B站的 `auid` 是 6–7 位十进制（实测 au39 ~ au4059094），与网易云 id 空间**重叠** ⇒ **必须加标志位/偏移**，不能直接透传 |

✅ 现有实现已按此设计（`isBiliId` + `SourceIds` 分支）。**建议补一条 JVM 单测**：`TrackKey.of(null, biliSyntheticId)` 必须反解成 `BILIBILI`（照抄 QQ 那条测试的形状）。

### 4.3 音质 `qn` 映射

**用户档位 → B站 `qn`**：

| Ncrust 档位（`QualityLadder.LEVELS`） | B站 `qn` | 实际得到 | 说明 |
|---|---|---|---|
| `standard` | 0 | 128K | |
| `higher` | 1 | 192K | |
| `exhigh` | 2 | **320K** | 匿名上限 |
| `lossless` / `hires` / `jyeffect` / `jymaster` / `dolby` | **不请求**（或请求 3 后按 320K 处理） | 320K | **匿名没有 FLAC**；请求 `quality=3` 会被静默降级 |

**两条建议**：

1. **`lossless` 及以上不要发 `quality=3`** —— 实测它稳定返回 `type:2`（320K），发出去只是多一次无意义的往返 + 多一次风控暴露。更好的做法：**读 `qualities[]` 判断真实上限**（这是 APP 端点独有的能力，web 端点 `qualities` 恒为 `null`），把 B站的"最高可得档位"如实告诉 `QualityAssessment`，让 UI 显示"该源最高 320K"而不是"已降级"。
2. **降级阶梯按"实际返回的 `type`"判定，不要按请求的 qn 判定** —— 因为服务端会静默降级。`type` 与 qn 的对应是 `-1`试听 / `0`128K / `1`192K / `2`320K。

⚠️ **`type = -1`（30 秒试听）本次未能复现**（23 首样本全是完整曲），但**必须防御**：文档称此时 `size = 0` 且只给片段。接入后遇到 `type == -1` 应作为"不可完整播放"处理。

**视频 DASH 侧的档位映射**（若采用）：

| `dash.audio[].id` | 码率 | codec | 建议映射 |
|---|---|---|---|
| `30216` | ~44 kbps | `mp4a.40.5`（HE-AAC） | `standard` |
| `30232` | ~103 kbps | `mp4a.40.2` | `higher` |
| `30280` | ~204 kbps | `mp4a.40.2` | `exhigh` |
| `30250` / `30251` | FLAC | — | `lossless`+（**需 `fnval=4048` 且多数视频没有**） |

（映射表参考 `AprDeci/bili-music` 的 `_buildQualityLabel`，**仅借鉴事实性常量，未复制代码**。）

### 4.4 歌词来源

✅ **用 B站自己的 `song/lyric`**（`data` 是**字符串**，不是对象）：

```json
{"code":0,"msg":"success","data":"[00:33.26]让我掉下眼泪的\n[00:36.93]不止昨夜的酒\n..."}
```

- 格式 `[MM:SS.mm]`（**两位**百分秒）—— **与 Ncrust 现有 `LrcParser` 支持的形状一致**，可直接接入，无需新解析器；
- **无歌词时 `code` 仍是 0，靠 `data == null` 判空**（不要用 `code != 0` 判）；
- 一行可带多个时间戳（`[01:06.45][02:44.32]` 重复段落），`LrcParser` 需确认支持；
- **不需要登录**；
- `song/info` 的 `lyric` 字段是同一份 LRC 的**文件 URL**（http，可能为空串），可作兜底；
- **只有行级 LRC，没有逐字/翻译/TTML** ⇒ B站音源上 Ncrust 的逐字歌词与 TTML 能力**不可用**，应走既有的"诚实降级"路径而不是报错。

⚠️ **视频条目没有歌词数据源**。`BiliSourceProvider` 现有 KDoc 已明确"空（诚实降级）"，这是对的。**不要**像 `biu` 那样去接 LRCLIB/网易云 —— 那会引入新的第三方依赖、新的许可证问题，以及**把 B站曲目名发给第三方**的隐私问题。

### 4.5 封面来源

- `song/info` 的 `cover` 是 **`http://`**（非 https）：`http://i0.hdslb.com/bfs/music/*.jpg`
  ⇒ **必须改写为 `https://`**（Android 9+ 默认禁明文；且 Ncrust 只在 QR 配对时开了 `cleartextTraffic`）。i0.hdslb.com 支持 https，实测音频流 CDN 也是 https。
- `song/url` 的 `cover` **恒为空串** —— 回 `song/info` 取。
- 视频条目的封面用 `search` 结果的 `pic`（也是 `http://`，同样要改写）。
- 建议走 Ncrust 已有的 `CoverUrls` 统一处理，不要在每个 Provider 里各改一次。

### 4.6 搜索聚合、排序与计数文案

现有 `SourceCounts` 已经有 `biliCount` / `biliStatus` / `sourceSummaryBili` / `aggFilterBili`，结构已就位。三点建议：

1. **`SourceSearchStatus.SKIPPED` 语义**：B站**默认关闭**（`DEFAULT_ENABLED = false`）⇒ 关掉时必须是 `SKIPPED`（不计入"三源"文案），而不是 `DONE`(0 条)。现有 `SourceCounts.kt:112` 的 `if (biliStatus == SKIPPED) return two` 正是这个语义 ✅。**这条很重要** —— 否则默认关闭的用户会看到"网易云 20 · QQ 15 · B站 0"，以为 B站搜不到东西。
2. **排序**：B站视频条目的元数据质量低于网易云/QQ（作者是 UP 主、时长可能是合集长度），**不建议给 B站结果加权**。建议沿用既有的 `SearchRanking`，让 B站条目按自身匹配度自然排序。
3. **计数文案**：B站是"**部分能力源**"（能搜到视频、能播，但视频条目无歌词），建议在聚合文案里不要把它与另两个源完全并列；至少在新的 `aggAvailabilityNote` 里说明。

### 4.7 独立开关：**默认关闭**

✅ **现有实现 `BiliPrefs.DEFAULT_ENABLED = false` 正确**，理由：

1. **合规**（§6）：默认开启意味着"产品主动提供 B站音源"；默认关闭意味着"用户显式选择启用"，风险形态不同；
2. **风控不确定性**：B站端点的可用性**随出口 IP 与时间变化**（本次实测到 18 分钟内从可用变为整体风控）。默认开启会让所有用户都撞上这种波动；
3. **能力不完整**（视频条目无歌词、无 FLAC），不宜作为默认体验；
4. **既有纪律**：本项目所有"实验性/条件性"功能（`artist_reco_*`、`lyrics_ttml_enabled`）都是默认关闭的。

**并且**：现有实现在**每条对外路径第一行都判 `isEnabled`**（"关掉之后一个请求都不发"），这个设计是对的，必须保留 —— 它同时满足合规（不主动触达）与性能（零开销）两条。

### 4.8 异常隔离与降级

**必须遵守的既有契约**（`MusicSourceProvider` KDoc）：

1. **绝不抛异常给调用方** —— 网络失败一律返回 `null` / 空列表。B站挂掉不该让聚合搜索或播放失败。✅ 现有实现全部包在 `runCatching` 里；
2. **绝不返回指向 HTML 错误页的 URL** —— 这是本项目**踩过的坑**（网易云 `song/media/outer/url` 会 302→404 返回 HTML，导致"缓冲不动"）。B站的 412 / 错误页必须在这里拦掉，✅ 现有 `isSignatureRejected` + 非 JSON 判定已拦；
3. **独立的 `OkHttpClient`** ✅ **现有实现做对了，且理由充分**：`RetrofitClient` 的拦截器**无条件注入网易云的 Referer/UA/Cookie**，而复用它会让 B站永远 403（实测"用网易云 Referer 请求 B站 → 403"），同时会把网易云 Cookie 泄露给 B站。**这个隔离必须保留**。

**需要新增的隔离**（来自本次实测）：

| 项 | 建议 |
|---|---|
| **Referer（CDN 取流）** | ⚠️ **最高优先级** —— 见 §4.9 |
| **B站请求的速率限制** | 全局串行 + 最小间隔（§2.5），且**不要与网易云/QQ 的并发共用一个信号量**（避免 B站的慢请求拖累另两个源） |
| **端点冷却** | 某个 B站端点连续失败后进入冷却期，期间直接走降级（§2.5） |
| **离线缓存隔离** | B站的 URL 会过期（2h），`OfflineUrlStore` 是**无 TTL 的 LRU** ⇒ 必须带过期时刻，或让 B站条目**不进** `OfflineUrlStore`（离线播放对 B站意义有限：匿名 + 2 小时过期 + 需要 Referer，三个约束叠加后"离线可播"的承诺很难兑现）。**建议本版让 B站不参与离线下载**，并在 UI 上说清。 |

### 4.9 ⚠️ **最高优先级：CDN 取流必须带 Referer**（当前实现会 403）

**实测（三种流全部一致）**：

| 请求头 | 音频区 192K | 音频区 320K | 视频 DASH |
|---|---|---|---|
| `Referer: https://www.bilibili.com/...` | **206** ✅ | **206** ✅ | **206** ✅ |
| 只带 UA（无 Referer） | **403** ❌ | — | **403** ❌ |
| 模拟 ExoPlayer（`UA=ExoPlayerLib/1.2.1` + `Accept-Encoding: identity`） | **403** ❌ | **403** ❌ | **403** ❌ |

**而当前链路上没有地方能带上它**：

```kotlin
// cache/OfflineAudioCache.kt:111-122
fun dataSourceFactory(context: Context): DataSource.Factory =
    CacheDataSource.Factory()
        .setCache(c)
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app))  // ← 无 defaultRequestProperties
        ...
// player/SongUrlFetcher.kt:34
data class SongUrlResult(val url: String, val actualLevel: String, ...)  // ← 没有 headers 字段
```

⇒ **这是接入 B站后"能搜到、能取到 URL、一播就 403"的根因**，必须在实现阶段解决。三个方案：

| 方案 | 做法 | 评价 |
|---|---|---|
| **A（推荐）** | 给 `SongUrlResult` 加 `headers: Map<String,String>`，`BiliSourceProvider` 填 `{"Referer": "https://www.bilibili.com/"}`；`PlaybackService` 建 `MediaItem` 时把 headers 传进一个自定义 `DataSource.Factory` | 最干净：**"这个 URL 需要什么头"由产出 URL 的那一层决定**，不污染其他音源 |
| B | 给 `OfflineAudioCache.dataSourceFactory` 的 `DefaultDataSource.Factory` 挂 `setDefaultRequestProperties(...)` | ⚠️ **必须按 host 白名单限定**（只对 B站 CDN）。否则会给网易云/QQ 的请求也带上 B站 Referer —— 而本项目**已实测**"拿网易云 Referer 请求 B站 → 403"，反向同理，有把现有音源打出 403 的风险 |
| C | 在 Provider 内先做一次 HEAD 校验（带 Referer），通过后才返回 URL | 多一次往返，且 HEAD 可能不被 CDN 支持；**不推荐** |

**另外两个必须注意的点**：

1. **`Content-Type` 是 `application/octet-stream`**（不是 `audio/mp4`）—— ExoPlayer 靠**嗅探**识别。实测文件魔数是 `ftypM4A`（`ISO Media, Apple iTunes ALAC/AAC-LC (.M4A) Audio`），`DefaultExtractorsFactory` 能正常识别 ✅。但**若将来给 `MediaItem` 显式设 `mimeType`，必须是 `audio/mp4`**（不是 `application/octet-stream`），否则 ExoPlayer 会选错提取器。
2. **Range 请求正常**（`206` + `content-range: bytes 0-1023/6244629`）✅ —— seek 与 `CacheDataSource` 的 `CacheDataSink` 都能工作。**但 `content-range` 里的总长度才是可信的文件大小**（`size` 字段对新上传的音频不可信，§3.2）。
3. ⚠️ **历史 403 可能污染 `SimpleCache`** —— 在修好 Referer 之后，建议**清一次 `filesDir/offline/audio`** 再验证，否则会出现"修了还是没声"。（当前已设 `FLAG_IGNORE_CACHE_ON_ERROR`，但是否足够**未实测**。）

---

## 5. 未验证项（诚实清单）

### 5.1 完全未验证（无账号 / 无对应样本）

| 项 | 说明 |
|---|---|
| 登录后能否拿到 **FLAC**（`quality=3`） | 无可用 B站账号，未做登录态实测 |
| 大会员 vs 普通登录的音质差异 | 同上 |
| 登录成功后的**真实 Cookie 字段集合** | 扫码流程的 `generate` / `poll` 端点可达性已实测，但 `data.code == 0` 成功态**未观测** |
| `data.code = 86090`（已扫码未确认） | 未观测 |
| `bili_jct` 的 CSRF 校验形态 | 未验证 |
| 登录后 `collections/*` 的真实返回结构 | 未验证 |
| APP `access_key` 换取付费音频完整版 | 未验证（`biu` 的源码注释如此声称，**未独立验证**） |
| `SESSDATA` 有效期 / 刷新机制 | 未验证（`poll` 返回了空的 `refresh_token` 字段，暗示有刷新机制） |
| **`type = -1`（30 秒试听）的真实触发条件** | 23 首样本全是完整曲，**未复现** |
| `attr` / `limit` / `limitdesc` 非 0 的样本 | 未采到（匿名实测恒为 0） |
| `vipInfo` 的确切语义 | 未确证（含 `status=1` 的样本仍返回完整文件，且完整曲长） |

### 5.2 部分验证（有证据但不足以定论）

| 项 | 现状 |
|---|---|
| **`%20` vs `+` 的空格编码** | **无法实测区分**（详见 `wbi-signature.md` §7.6.1）。参考文档 5 份 Demo 自相矛盾；唯一会用到含空格关键词的 `search/type` 在本机出口**不校验 w_rid**；在会验签的 `view/detail` 上追加未知参数会被直接拒。**建议选 `%20`**（= 逆向出的前端 `encodeURIComponent`），并配 JVM 单测 |
| **wbi key 的轮换周期** | 上游文档称"每日变化"，但本次**单次会话只观测到一个 key 值**（`7cd0849…`），**跨天轮换未实测**。Wayback Machine 佐证也失败（本机到 archive.org 超时 40s） |
| `crtype=2 + msid!=0` ⇒ 必然 `cdns:null` | **样本仅 2 个**（au127015 / au130878 命中，au1124992 等 4 个未命中），疑似规律但不足以定论 |
| auid 的数值上界 | ≥ 8e6 的 12 个随机抽样全 miss，但**不足以断定上界** |
| URL 超过 `deadline` 后的真实响应 | 需要等 2 小时，未做 |
| 音频 CDN 是否需要 Cookie | 只在匿名下测过；登录态是否更宽松未知 |
| 历史 403 是否污染 `SimpleCache` | 未实测 |
| `s.search.bilibili.com/cate/search` 老分区搜索接口 | 未实测（`despcy/Bilibili-MusicPlayer` 在用，但项目是 2019 年的） |

### 5.3 环境相关（**结论随出口 IP 变化**）

| 项 | 现状 |
|---|---|
| `search/type` 是否强制 wbi | **本机出口不强制**（无签名/伪造签名/正确签名结果一致；连打 100 次全部 `code:0`）。**换个 IP 可能强制** —— 上游文档称"数次请求后返回 -403" |
| `/x/player/wbi/playurl` | **本机出口整体 412**（4 种签名/Cookie 组合全 412）。**这不代表所有出口都 412** |
| `/x/space/wbi/*` | 本机出口恒 `-352 风控校验失败`，与签名无关 |
| `/x/web-interface/wbi/view/detail` | 02:29:37 时 A/B 成立（无签名 `-352` → 有签名 `0`）；**02:46:54 起连有效签名也 `-352`**（整体风控）。**签名正确性由离线官方向量自检独立保证** |

> ⚠️ **复现者注意**：如果你跑 `wbi_ab.py` 时两组都返回 `-352`，说明**你的出口已被风控**，换个网络（手机热点）再试，而不是认为算法错了。算法正确性由 `wbi_golden.py` 的离线向量自检保证（不依赖网络）。

### 5.4 代码层面（对照在写的实现）

| 项 | 现状 |
|---|---|
| `AUDIO_URL` 用 web 端点 ⇒ 只有 192K | **已确认应改为 APP 端点**（`/audio/music-service-c/url?songid=`）以拿到 320K，见 §2 与 `bili-audio-api.md` §3 |
| `PLAYURL_URL` 用 `/x/player/wbi/playurl` | 本机出口 412；建议补 `/x/player/playurl` + `buvid3` 的降级路径 |
| `fnval=16` | 建议 `fnval=4048`（可多拿 `dash.flac.audio` 与 30251/30250 档） |
| `isSignatureRejected` 把 `-1200` / 非 JSON 当签名失败 | 建议区分（`-1200` = 参数非法；非 JSON = 路径封禁），见 §2.3 |
| `BiliWbi` KDoc 写"MIT/CC 授权" | 实际是 **CC BY-NC 4.0**，且上游已关停 —— 见 §6 |
| `size` 字段 | 若任何代码用它做"文件大小"，必须去掉（新上传音频该字段不可信） |

---

## 6. ⚠️ 风险提示（超出技术范围，但必须让项目所有者知情）

**本节不构成法律意见。**

调研中发现（`community-implementations.md` §0）：

- 本任务指定的参考项目 `SocialSisterYi/bilibili-API-collect`（GitHub ~20k star）**已于 2026-01-28 永久关停**，`docs/` 与 `LICENSE` 全部删除（实测 HTTP 404）。其 README 原文说明：维护者收到**B站委托律师事务所的律师函**，指控其"对哔哩哔哩平台**非公开的 API 接口**及其调用逻辑、参数结构、访问控制及**安全认证机制**进行系统性收集、整理，并……向不特定公众传播"。
- 多家媒体报道了此事（[凤凰科技](https://tech.ifeng.com/c/8qJ6eBQuxXV)、[IT之家](https://www.ithome.com/0/917/481.htm)、[新浪财经](https://finance.sina.com.cn/tech/digi/2026-01-29/doc-inhixyxy4258772.shtml)）。
- 该项目的许可是 **CC BY-NC 4.0**（非商业），**与 GPLv3 不兼容**，其文档内容也不能被复制进本仓库。

**对 Ncrust 的含义**：接入 B站音源在行为形态上与该被指控项目**有重叠面**（使用非公开接口 + 复现 Wbi 这一"安全认证机制"）。技术侧能做的风险缓释：

| # | 措施 | 现状 |
|---|---|---|
| 1 | **默认关闭**，由用户显式启用 | ✅ `BiliPrefs.DEFAULT_ENABLED = false` |
| 2 | **不实现任何写操作**（投币/收藏/点赞） | 建议明确不做 |
| 3 | **不内置账号凭证、不代登录** | ✅ `isLoggedIn` 恒 `false` |
| 4 | **保守速率限制**，不做批量抓取 | ⚠️ 待补（§2.5） |
| 5 | 关于页/README 明确"仅用于个人学习，与哔哩哔哩无关联" | 待补 |
| 6 | **clean-room 实现**，不复制 CC BY-NC 文档文本、不复制无许可项目的代码 | ✅ 本次调研全部结论建立在自测之上 |

---

## 7. 落地清单（按优先级）

| P | 项 | 依据 |
|---|---|---|
| **P0** | 给取流链路补 `Referer: https://www.bilibili.com/`（`SongUrlResult.headers` 或按 host 限定的 `DataSource.Factory`） | §4.9 —— **不修就是"一播就 403"** |
| **P0** | 音质换到 APP 端点 `/audio/music-service-c/url?songid=` 拿 320K；`lossless+` 不再发 `quality=3` | §4.3、`bili-audio-api.md` §3 |
| **P1** | 视频取流补 `/x/player/playurl` + `buvid3` 降级路径；`fnval=16` → `4048` | §2.3、`bili-audio-api.md` §7 |
| **P1** | `isSignatureRejected` 区分「签名失败」/「参数非法(-1200)」/「路径封禁(非 JSON/412)」 | §2.3 |
| **P1** | 给 wbi / 视频端点加**速率限制 + 失败冷却** | §2.5（实测风控 18 分钟内升级） |
| **P1** | 音频区 URL 的 TTL 落到 `OfflineUrlStore`（或让 B站不参与离线） | §2.4 |
| **P2** | 补"B站音频区榜单/歌单"入口（`menu/rank` + `menu/hit` + `song/of-menu`） | §1.3 —— 否则音频区这条腿只能手输 auid |
| **P2** | `cover` 的 `http://` → `https://` 统一改写 | §4.5 |
| **P2** | 修正 `BiliWbi` 的许可证措辞（CC BY-NC 4.0 / 已关停 / 协议常量） | §6 |
| **P2** | 补 JVM 单测：官方 wbi 向量、含空格关键词签名、`TrackKey.of(null, biliId)` 反解 | `wbi-signature.md` §7.5 |
| **P3** | `type == -1`（试听片段）的防御分支；`cdns == null` 的"无流"提示文案 | `bili-audio-api.md` §2.3、§3.5 |
| **P3** | README / 关于页的免责声明 | §6 |
