# 探针 · B 站音源接入（v3.1.0 · B）

> 采集时间：2026-09-28 02:51 CST ｜ 代码基线：0b4ed2c ｜ 方式：源码审计 + 实测（无臆测）

**判决先行**：B 站作为**第三条腿**接入的骨架成立 —— 独立音源枚举、独立开关（默认关）、
独立 OkHttp 客户端、合成 id 隔离值域、搜索追加在最后、统计行第三段在关闭时短路。
本探针在**对照 `bili-research` 原始证据**时提出过三条与实现冲突的事实，其中三条已在
`0b4ed2c`（commit：`fix(bili): 两处只有实测才暴露的缺陷 —— 歌词取正文、播放地址走旧路径`）
修掉并有守卫单测；**还剩一条未修且会直接阻断播放**：B 站 CDN 强校验 `Referer`，
而 ExoPlayer 的媒体数据源一个请求头都不注入（§10.4）。

> 基线说明：本探针的初版审计在 `59f3d6c` 上完成，三条发现被上游采纳并修在 `0b4ed2c`。
> 本文所有行号按 **`0b4ed2c`** 复核（`git status --porcelain` 里 `app/` 与 `app/src/test/` 无改动）。

---

## 1. `Source` 枚举新增 `BILIBILI`

| 项 | 落点 | 说明 |
|---|---|---|
| 枚举值 | `source/MusicSource.kt:46` | `BILIBILI("bilibili")`，key 显式写死（会进队列 JSON / 离线 key / mediaId） |
| 全部音源（UI 顺序） | `MusicSource.kt:63` | `selectable = listOf(NETEASE, QQMUSIC, BILIBILI)` |
| **可登录**音源 | `MusicSource.kt:76` | `loginSources = listOf(NETEASE, QQMUSIC)` —— B 站**不在其中** |
| 「换一个源试试」 | `MusicSource.kt:92-93` | 只遍历 `loginSources` ⇒ B 站不会出现在无版权提示里（默认关闭时那条提示是死路，:89-91） |
| `isLoggedIn` | `BiliSourceProvider.kt:59` | 恒 `false`（**诚实**：本轮没有账号体系接入，见 `bili-research/bili-auth.md`） |
| `requiresSourceId` | `MusicSource.kt:50` | 只对 QQ 成立；B 站靠 `sourceId` 载荷前缀分派，但不是「必需」语义 |

---

## 2. `TrackKey` 的 id：为什么必须用 `BILI_ID_FLAG`（位 61）

### 2.1 auid 与网易云 id **值域重叠**（这是前提，不是猜测）

- 实测样本：`auid = 22760301`（约 **2.3×10⁷**）—— 见 `net-research/EVIDENCE-S6.md:62`
  的 `bili.audio.info`（探针请求的 URL 就是 `song/info?sid=22760301`，`NetTimingProbeTest.kt:247`）。
- 采样分布（`bili-research/bili-audio-api.md` §2.2）：`1–200`、`1e4–3e4`、`1e5–3e5`、
  `1e6–3e6`、`4e6–5e6` 全部命中真实音频 ⇒ auid 与网易云 songId（百万~十亿）**同一量级**。
- 代码里也把这条写死了：`SourceIdDomain.kt:104-113`（B 站分支的 KDoc）明确说
  「它与网易云的值域重叠 …… 值域在这里**结构性地不可用**」。

⇒ 裸 `Long` id 无法区分 `TrackKey(BILIBILI, 22760301)` 与 `TrackKey(NETEASE, 22760301)`，
而本应用有 10+ 处以裸 `Long` 为键的跨版本持久化结构（队列、续播进度、离线缓存 key、歌词缓存 key…，
见 `MusicSource.kt:176-192` 的既有论证）。

### 2.2 做法与判序

```kotlin
// source/MusicSource.kt:221
const val BILI_ID_FLAG: Long = 1L shl 61        // 位 61 = B 站
const val QQ_ID_FLAG:   Long = 1L shl 62        // 位 62 = QQ（v2.1.0 起的既有约定，:199）

fun biliId(rawAuid: Long): Long {               // :236-239
    if (rawAuid <= 0L || rawAuid >= BILI_ID_FLAG) return 0L   // 造不出就返回 0，绝不造假 id
    return BILI_ID_FLAG or rawAuid
}
fun isBiliId(id: Long): Boolean = !isQqId(id) && (id and BILI_ID_FLAG) != 0L   // :227

fun sourceOfId(id: Long): MusicSource = when {  // :263-269
    isQqId(id)   -> MusicSource.QQMUSIC         // ★ 先判位 62
    isBiliId(id) -> MusicSource.BILIBILI        // ★ 再判位 61
    else         -> MusicSource.NETEASE
}
```

**判序为什么不能反**（`MusicSource.kt:265-267` + `:217-219`）：

- 两个标志位同时置位的 id **本应用不会造**（`qqId` / `biliId` 各自只置自己那一位），
  但**读外部/历史数据**时先判谁是有定义的：先判 QQ 与 v2.1.5 起的历史行为一致。
- 具体后果：`QQ_ID_FLAG - 1 = 2^62 - 1` 这个数**位 61 是 1**。若先判位 61，它会被解释成 B 站。
  顺序固定之后这个边界是**可断言**的：`TrackKeyTest.kt:186-187` 写明
  `sourceOfId(QQ_ID_FLAG - 1) == BILIBILI`、`sourceOfId(QQ_ID_FLAG) == QQMUSIC`，
  而网易云的真实 id（含 `9_999_999_999L` 与 `BILI_ID_FLAG - 1`）依旧全部判成 NETEASE（:170-188）。

### 2.3 载荷形状（两条腿靠它分派，不靠 id）

`BiliTrack.sourceId`（`BiliModels.kt:82-87`）：`au:<auid>` / `bv:<bvid>:<cid>`。
解析：`BiliTrack.parseSourceId`（:112-129）→ `Payload`（:132-134）。
**id 分不出两条腿**（`numericId = biliId(auid ?: aid ?: 0)`，:73），所以
「音频区 2478206」与「视频 av2478206」会撞成同一个 id —— 这是**已知且可接受**的，
因为取链分派看 `sourceId`（`BiliModels.kt:65-72` 的注释把这条写明了）。

`SourceIdDomain.matches(BILIBILI, id)`（`SourceIdDomain.kt:114-115`）回答的是
「这个串在 B 站域内合不合法」（十进制正整数），**不是**「它是不是 B 站」——后者由标志位回答。

---

## 3. 歌词来源：音频区有、视频音轨没有 ⇒ 诚实降级

| 腿 | 数据源 | 代码 | 结果 |
|---|---|---|---|
| 音频区 `au:<id>` | **`/audio/music-service-c/web/song/lyric?sid=`** 的 `data`（LRC 正文） | `BiliApi.audioLyric`（`BiliApi.kt:249-253`）→ `BiliParse.parseAudioLyric`（`BiliModels.kt:423-428`）→ `BiliSourceProvider.fetchLyric`（:149-167） | 交给既有 `LrcParser`（`PlayerViewModel.loadBiliLyrics:1951`，**不新写解析器**） |
| 视频音轨 `bv:…` | **没有数据源** | `fetchLyric` 直接 `return null`（:152） | `markEmpty`（PlayerViewModel.kt:1945-1949），**不是 `fail`** |

- **两义性保住**：`parseAudioLyric`（`BiliModels.kt:423-428`）——`code != 0` 或没有 `data` 键 ⇒
  `null`（「没有这个数据源」）；`data` 是空串 ⇒ `""`（「这首歌确实没有歌词」）。
- **URL 形状的守卫**：个别曲目的 `data` 会是 `.lrc` 链接，`fetchLyric` 用
  `BiliParse.looksLikeUrl`（`BiliModels.kt:437-440`）**如实返回 null**，而不是把 URL 喂给 `LrcParser`
  （`BiliSourceProvider.kt:162-165`）。理由写在注释里：取词路径上再发一次网络会把一次播放变成两次往返。
- 「诚实降级」的含义（`PlayerViewModel.kt:1927-1930`）：重试一百次也不会有歌词，
  把它标成可重试的错误只会让界面白转圈。
- B 站曲目**不进 `LyricsCache`**（:1932-1935）：那张表存的是网易云的字段形状
  （lrc/tlyric/yrc/romalrc/ttml），塞进去要么加字段 + 迁移逻辑（v1.9.3 的教训），
  要么污染字段语义。代价是每次播放现取一次。
- ⚠️ `fetchLyric` 的 KDoc（`BiliSourceProvider.kt:139-147`）仍写着「音频区那条路的歌词是
  `song/info` 的 `lyric` 字段」「对音频区返回它的 `lyric` 字段」——**这是改代码时漏改的注释**，
  与 `:153-166` 的实现不一致（见 §10.5）。

---

## 4. 封面来源与 URL 归一化

| 来源 | 字段 | 代码 |
|---|---|---|
| 音频区 | `song/info` 的 `cover` | `BiliModels.kt:400` → `normalizeCover` |
| 搜索（视频） | 搜索项的 `pic` | `BiliModels.kt:329` → `normalizeCover` |

`BiliParse.normalizeCover`（`BiliModels.kt:216-224`）：

```
""                 -> ""                        (保持空串，调用方自己回落)
"//i2.hdslb.com/…" -> "https://i2.hdslb.com/…"  (协议相对 URL 补全 —— 搜索接口给的就是这个形状)
"http://i0.…"      -> "https://i0.…"            (http 抬成 https)
其他                -> 原样
```

为什么必须补全（:213-214）：搜索接口给的是协议相对 URL，Coil 能处理，
但**队列持久化之后再读出来就未必了**。为什么必须抬 https（`bili-audio-api.md` §2.1 对 `cover` 的备注）：
音频区给的是明文 `http://i0.hdslb.com/...`，Android 9+ 默认禁明文。
守卫：`BiliParseTest.kt:59` `封面补全协议头 并且 http 抬成 https`。

---

## 5. 搜索聚合：B 站**追加在最后**、不参与会员交错

| 项 | 落点 | 说明 |
|---|---|---|
| 三源合并 | `SearchViewModel.publish` :138-176 | 新参数 `biliList: List<SongItem> = emptyList()`（:142）⇒ 旧调用点与单测零改动 |
| 追加位置 | :147-175 | `SearchRanking.order(netease, qq, bili, …)`；注释 :147-148「B 站没有会员信号，不参与交错；`bili` 为空时与两源版本逐字相同」 |
| 中性取值 | :164-172 | `TrackAccess.UNKNOWN`（无会员依据）+ `TrackAvailability.of(it)`（B 站恒 UNKNOWN）⇒ 不参与重排 |
| 去重 | :175 | `.distinctBy { it.trackKey }` |
| 时间预算 | `BILI_SEARCH_BUDGET_MS = 4_000L`（:106） | 比 QQ 的 5s 短，理由 :99-105：B 站要先取 wbi 密钥再签名（冷启两通），风控命中时 4 通 |
| 并发结构 | `async`（:267-282）+ `select`（:313-327） | 与 QQ 完全同构；`CancellationException` 显式 rethrow（:274-275） |
| 状态映射 | `biliStatusOf` :502-508 | 与 `qqStatusOf`（:490-496）**逐条同构**：未启用→SKIPPED、还没回来→PENDING、超时→TIMEOUT、失败→ERROR、否则 DONE |
| 结果类型 | `BiliOutcome` :531-535 | 与 `QqOutcome` 同构：「超时」与「确实 0 条」在**类型上**必须分得开（v2.5.5 用一整版修出来的东西） |

### 5.1 统计行第三段：关闭时**短路**

`ui/components/SourceCounts.kt`：

- 新字段 `biliCount`（:77）与 `biliStatus`（:78），默认值 `SKIPPED`（**不是** DONE+0）；
- `summary()` :108-114：

```kotlin
val two = strings.searchSourceSummaryWithStatus(neteaseText(strings), qqText(strings))
if (biliStatus == SourceSearchStatus.SKIPPED) return two      // ★ :112 短路
return strings.source.sourceSummaryBili(two, biliText(strings))
```

⇒ **关掉开关时统计行与 v3.0.0 逐字相同**（连一次文案拼接都不多做）：
`SourceCountsBiliTest.kt:34` `B 站关闭时统计行逐字不变`。
启用后才拼第三段（:46），PENDING 显示「搜索中…」而不是 0（:56），三态各自有独立文案（:66），
8 种语言都不为空（:85），英文文案不漏中文（:111）。

写作侧也是新增字段而不是改旧字段：`Strings.searchSourceSkipped`（`Strings.kt:376`）、
`Strings.sourceSummaryBili`（:974）、`Strings.bilibiliEnabledLabel`（:1353）；
`AggregateStringsTest` 的 dex 单方法预算用例（:75）与 `Strings` 主构造器预算用例（:89）
钉住「没有把主构造器撑爆」。

本地筛选（`ui/components/SourceFilter.kt:36`）是**纯本地**的：默认 `ALL` = 不过滤，
与 v3.0.0 行为相同；`SourceFilter.visible(biliEnabled)` 只在开关打开时露出 B 站档
（`SourceCountsBiliTest.kt:229`）；`ALL` 原样返回同一个列表实例（:206）。

---

## 6. 音质映射：本应用 8 档 → B 站 `qn` 0/1/2/3 + 有界降级阶梯

### 6.1 映射表（`bili/BiliPrefs.kt:64-70`）

| 本应用档位 | B 站 `qn` | 名义档位 |
|---|---|---|
| `standard`（压缩） | 0 | 128K |
| `higher`（较好） | 1 | 192K |
| `exhigh`（更好） | 2 | 320K |
| `lossless`（无损） | 3 | FLAC |
| `hires` / `jyeffect` / `jymaster` / `dolby` | 3 | B 站没有比 FLAC 更高的档 ⇒ 映射到它自己的最高档（**不是「不请求」**） |
| 未知字符串 | 3 → 逐级下探 | 与 `SongUrlFetcher` 的 `else` 分支同一取舍 |

`BiliQn` 枚举（:27-39）：`Q128(0)` / `Q192(1)` / `Q320(2)` / `FLAC(3)`。
最高档取 3 而不是「匿名只能 1」的理由（:55-59）：**「匿名只能拿 192K」是服务端的结论，
不是客户端的常量**；写死成 1 等于把将来的登录能力一起锁死。

### 6.2 降级阶梯（有界）

```kotlin
fun fallbackLadder(level: String): List<BiliQn> {      // :78-81
    val start = mapFromLevel(level).qn
    return (start downTo 0).mapNotNull { BiliQn.of(it) }   // 去重、有序、最低档收尾
}
```

调用点 `BiliSourceProvider.resolveUrl` :97-106：逐档 `runCatching { BiliApi.audioStream(auid, qn) }`，
**每一档只试一次**（铁律 5 的既有形状），第一档成功即返回。
`SongUrlResult.levelFromFile = true`（`BiliSourceProvider.kt:195`）：实际档位来自**文件名/编码**（`-192k.m4a` / DASH `bandwidth`），
不是我们请求的那个 `qn` —— 这样 `QualityAssessment` 才会如实显示「已降级」。

### 6.3 匿名实测：**四个 qn 都返回 192K**（这是 web 端点的行为）

原始证据（`bili-research/evidence/13..16-songurl-web-au39-qn0..3.txt`，逐档一行）：

```
13-songurl-web-au39-qn0 → "type":1,"timeout":10800,"size":6244629  文件名 …-192k.m4a
14-songurl-web-au39-qn1 → "type":1,"timeout":10800,"size":6244629  文件名 …-192k.m4a
15-songurl-web-au39-qn2 → "type":1,"timeout":10800,"size":6244629  文件名 …-192k.m4a
16-songurl-web-au39-qn3 → "type":1,"timeout":10800,"size":6244629  文件名 …-192k.m4a
```

⇒ 在 web 端点（`BiliApi.kt:95` `AUDIO_URL`）上 `quality` 参数**完全被忽略**，匿名恒 192K。
实现明确承认这一点（`BiliApi.kt:318-328`、`BiliSourceProvider.kt:86-88`），
并保留阶梯「为了将来接登录态时不需要改结构」。

> ⚠️ 同一份调研同时实测到**另一条路**能匿名拿 320K：APP 端点
> `/audio/music-service-c/url?songid=&quality=2&platform=pc`（`bili-audio-api.md` §3.3-3.4，
> ffprobe 实测 `bit_rate=321584`）。**本实现用的是 web 端点**，所以匿名下只有 192K。
> 这是取舍不是缺陷，但「B 站能到 320K」不能写成已经拿到（见 §10.6）。

---

## 7. 「音频区没有搜索接口」的实测证伪（这是本版设计的根据）

### 7.1 404 端点清单（穷举实测）

`bili-research/bili-audio-api.md` §6.1 + `evidence/38..45-probe-*.txt`：

| 候选端点 | 实测 |
|---|---|
| `/web/song/search?keyword=…` | **HTTP 404**（`evidence/39-probe-song-search-keyword-x-pn-1-ps-5.txt`：`HTTP/2 404`、`HTTP=404`） |
| `/web/search/song?keyword=…` | **HTTP 404**（`evidence/44-probe-search-song-keyword-x.txt`：`HTTP/2 404`） |
| `/web/search?keyword=…` | **HTTP 404** |
| `/web/search/type?keyword=…` | **HTTP 404** |
| `/web/song/list` | **HTTP 404** |
| `/web/song/searchByKey` | **HTTP 404** |
| `/web/song/query` | **HTTP 404** |
| `/web/menu/search?keyword=…` | HTTP 200 但**空壳**：`{"code":0,"data":null,"message":"OK","msg":"success"}`（`evidence/45-…txt`） |
| `app.bilibili.com/x/v2/search/type?search_type=audio` | `{"code":-400}` |
| `api.bilibili.com/x/v2/search/type?search_type=audio` | `{"code":-400}` |

同一结论也写在实现里（`BiliApi.kt:46-58` 的表格，逐条与上面一致）。

### 7.2 通用搜索里也没有音频分区

- `search/all/v2` 的 `pageinfo` key 与 `result_type` 枚举**都没有** audio/music
  （`bili-audio-api.md` §6.2，`evidence/57-search-all-v2.txt`）。
- `search_type` 取值矩阵（`evidence/46..55-searchtype-*.txt`）：
  `video / media_bangumi / bili_user / live_room / article / topic / photo` ⇒ `code:0`；
  **`music` / `audio` 与故意乱填的对照 `foobar` 三者返回完全相同的
  `{"code":-1200, "message":"被降级过滤的请求"}`**（`evidence/47/48/49-*.txt` 原样）
  ⇒ 判据是「取值非法」，不是「被风控拦截」（`-1200` 也会由越界翻页触发）。
- ⚠️ **诚实修正**：任务书说 `search_type=audio/music/au/song` 四个取值全回 `-1200`。
  证据目录里**只有 `music` / `audio` / `foobar`** 三个（探针脚本 `collect_evidence.sh:105`
  的循环就是 `video music audio foobar media_bangumi bili_user live_room article topic photo`），
  **没有 `au` / `song` 的原始文件**。本探针不替它补结论：`au` / `song` 记为**未实测**，
  复现方式：把 `au` `song` 加进那个 `for st in …` 循环重跑 `bash collect_evidence.sh`。

### 7.3 因此的两条腿设计

| 动作 | 音频区曲目 `au:<auid>` | 视频音轨 `bv:<bvid>:<cid>` |
|---|---|---|
| 搜索 | 只有当**关键词本身是 auid / B 站音频链接**时才可达（直链语义）：`parseAuidKeyword`（`BiliSourceProvider.kt:177-183`）认 `au2478206` 与 `bilibili.com/audio/au…`，**纯数字故意不认**（:175） | 正常关键词搜索（`search_type=video`，`BiliApi.kt:263-281`） |
| 取链 | `song/url`（web 端点，192K） | `playurl` 的 DASH `audio[]`（`BiliModels.parseDashAudio` :353-384，**永不取 `dash.video[]`** :349-350） |
| 歌词 | `/song/lyric` 的 `data`（§3） | **没有** ⇒ `markEmpty` |
| 元数据 | `song/info`（标题/UP 主/封面/时长） | 搜索项自带 |

⇒ 「搜索 → 播放 → 歌词」在 B 站上**不可能只走一条腿**（`BiliTrack` KDoc :35-37）：
能搜到的只有视频，有歌词的只有音频区。两条腿都是平台上真实存在的能力，**没有编造**。

---

## 8. 独立开关（铁律 24）

### 8.1 默认**关闭**，理由与 v3.0.0 动效开关**相反**

`bili/BiliPrefs.kt:96-102`：

- 动效开关默认全开：它们只影响**本机渲染**，「默认关」会让用户以为升级没变化；
- B 站音源默认关闭：它是一个**外部平台依赖**（要发 Wbi 签名的请求、有自己的风控与 URL 时效）。
  默认打开等于「用户什么都没做，App 就开始给 B 站发请求」。

这条与 `net-research/RECOMMENDATIONS.md` §4.4 的接入约束一致（那是探针给的，不是文档推断）。

### 8.2 读写唯一入口

| 环节 | 落点 |
|---|---|
| prefs 键（显式声明） | `BiliPrefs.KEY_ENABLED = "bilibili_enabled"`（:121），默认 `DEFAULT_ENABLED = false`（:124） |
| 进程内镜像 | `@Volatile var enabled`（:126-127）；读内存 `isEnabled()`（:135）可以安全地在组合期调用（:104-110） |
| 冷启动 | `MainActivity.kt:224` `BiliPrefs.init(this)`（与 `RetrofitClient.init` 同处） |
| 写 | `BiliPrefs.setEnabled(context, value)`（:143-146）—— **先落盘、再改内存**（顺序理由 :137-142） |
| 从盘读（初始化/单测） | `read(context)`（:155） |
| 设置页接线 | `SettingsRegistry.kt:354-365`（`key = "bilibili_enabled"`，`default = false`，`newInV310 = true`，放在「通用」组）；读写 `SettingsGroupScreen.kt:389-391`（`biliEnabled = value` + `BiliPrefs.setEnabled`），显示 `:307` / `:328`，文案 `:822` / `:876` |
| 单测镜像 | `setEnabledForTest`（:164-166，**不落盘**；存在的唯一理由是让「默认值」也被钉住） |
| Provider 闸门 | `BiliSourceProvider.isEnabled`（`BiliSourceProvider.kt:62`）—— 每条对外路径第一行都判它：`searchSongs` :72、`resolveUrl` :90-91、`songDetail` :126、`fetchLyric` :150 |

### 8.3 「关闭时一个请求都不发」的守卫测试

| 用例 | 文件:行 | 断言 |
|---|---|---|
| `默认关闭（升级后行为不变的默认体验）` | `bili/BiliSourceProviderTest.kt:41-46` | `DEFAULT_ENABLED == false` 且未初始化时 `isEnabled == false` |
| `关掉开关之后一个请求都不发 —— 搜索返回空` | :49-57 | 四种关键词（含合法 auid 形状 `au2478206`）全返回空列表 |
| `关掉开关之后取链返回 null 且不解析载荷` | :60-70 | `resolveUrl` / `songDetail` / `fetchLyric` 全 null |
| `Provider 已在 SourceRouter 注册（否则聚合搜索永远搜不到它）` | :107-110 | 注册与开关解耦（注册无条件，判据在 Provider 里） |
| `开关的 prefs 键与设置注册表逐字一致（铁律 17 显式声明）` | :118-126 | 键名 / 默认值 / prefs 文件 / 可见性 / `newInV310` |
| `B 站不是可登录音源 —— 不出现在换源提示里` | :86-97 | `otherThan(NETEASE) == QQMUSIC` 语义不变 |

> ⚠️ **诚实说明**：`BiliSourceProvider.kt:43` 的 KDoc 写「用一个**会计数的假传输层**钉住」，
> 但实际用例没有注入任何传输层 —— `BiliApi.client` 是 private lazy（`BiliApi.kt:116-121`），
> 测试只断言**返回值**（空列表 / null）。所以这条守卫是**行为性**的（「不发请求」是从
> 「四条入口第一行就 return」推出来的），**不是字节级**的「HTTP 调用次数 == 0」。
> 真要字节级证明需要给 `BiliApi` 开注入口，那是实现改动，不在本探针范围。

---

## 9. 异常隔离与 Wbi 签名

四层隔离，都是结构性事实：

1. **Provider 契约**（`source/MusicSourceProvider.kt:33-41`）：实现方**绝不抛异常给调用方**，
   失败一律 null / 空列表。`BiliSourceProvider` 每个入口都包 `runCatching`
   （:74、:77、:101、:109、:114、:130、:160），KDoc :35 明写「契约 1」。
2. **路由器兜底**：`SourceRouter.searchSongs`（`SourceRouter.kt:82-87`）无论 Provider 怎么炸，
   `runCatching { … }.getOrDefault(emptyList())` ⇒ 一个平台挂掉不会吞掉另一个平台的结果。
   搜索侧再包一层硬预算（`withTimeoutOrNull(BILI_SEARCH_BUDGET_MS)`，`SearchViewModel.kt:269`）
   与 `catch (e: Exception)`（:276-279），失败只记 `BiliOutcome(failed = true)`。
3. **取链绝不回落**：`SourceRouter.resolveUrl`（:65-76）在「没注册 / 不可解析」时返回 null，
   **绝不退回网易云**（:61-62 注释；`BiliSourceProviderTest.kt:73-84` 钉住）。
4. **独立 OkHttp 客户端，不共享任何请求头**（`BiliApi.kt:116-121`）：
   没有 `CookieInterceptor`（网易云的 UA/Referer/Cookie 一个都不带）。这是实测逼出来的 ——
   `net-research/EVIDENCE-S6.md:104-106` 的 A/B 对照：

   ```
   variant=A_netease_referer  referer=https://music.163.com/      http=403  body=<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.01 Transitional//EN">
   variant=B_bili_referer     referer=https://www.bilibili.com/   http=200  body={"code":-101,"message":"账号未登录",...}
   variant=C_no_referer       （不带 Referer）                     http=200  body={"code":-101,...}
   ```

   顺带买到两件事（`BiliApi.kt:38-42`）：B 站收不到网易云的 Cookie（隐私），
   B 站的 412 / -352 风控不会影响网易云那条链路（铁律 27）。

Wbi 签名侧（`bili/BiliWbi.kt`）是纯逻辑、可单测：乱序表 :59-64、`mixinKey` :91-99、
`signedQuery` :111-132（敏感字符先剔再编码 :120-125）、`wts` 是**秒**（:44-45、:118）、
空格编成 `+`（:135-143）。密钥缓存 6 小时 + **签名被拒时强制刷新一次**
（`BiliApi.kt:106` `WBI_KEY_TTL_MS`、:191-203 `signedGet`）。
「被拒」的判据现在把 **`-352` 也算进去**（:220-226：`code == -352 || -403 || -1200`，或正文非 JSON）。

---

## 10. 探针发现清单：已修 / 未修 / 未闭环

> 分类口径：**实测** = 有原始响应文件；**源码审计** = 本次读代码得到；**推断** = 由前两者推出的后果。

### 10.1 ✅ 已修（`0b4ed2c`）：`song/info` 的 `lyric` 是 **URL**，曾被当成 LRC 正文

| | |
|---|---|
| **实测** | `evidence/02-songinfo-au39.txt` → `"lyric":"http://i0.hdslb.com/bfs/music/149994607539.lrc"`；`05-songinfo-au11624.txt`、`06-songinfo-au17315.txt` 同为 `http://…lrc`；`03/04/07-11` 为空串。真正是 LRC 正文的是另一个端点：`evidence/21-lyric-au39.txt` → `{"code":0,"msg":"success","data":"[00:33.26]让我掉下眼泪的\n…"}` |
| **旧实现（已不存在）** | `BiliParse.parseAudioInfo` 把 `lyric` 当正文取出、`fetchLyric` 直接返回它 ⇒ URL 串里没有 `[mm:ss]` ⇒ `LrcParser` 0 行 ⇒ `markEmpty` ⇒ 有歌词也永远「暂无歌词」，且不报错 |
| **现实现** | 改走 `/song/lyric`：`BiliApi.audioLyric`（:249-253）→ `BiliParse.parseAudioLyric`（`BiliModels.kt:423-428`）→ `fetchLyric`（:153-166）；`BiliTrack.lyric` 的 KDoc 改成「⚠️ 服务端原始字段，通常是 URL，取词请走 `audioLyric`」（`BiliModels.kt:43-48`） |
| **守卫测试** | `BiliParseTest.kt:199` `歌词正文走 song_lyric 而不是 song_info 的 lyric 字段`、:207 `song_lyric 的两义性：没有 data 是 null 空串是空串`、:216 `looksLikeUrl 认出三种 URL 形状`、:232 `lyric 字段缺失是 null 空串是空串（两义性必须保住）` |
| **复现** | `curl -s 'https://www.bilibili.com/audio/music-service-c/web/song/info?sid=39' -H 'Referer: https://www.bilibili.com/audio/home' \| grep -o '"lyric":"[^"]*"'` |

### 10.2 ✅ 已修（`0b4ed2c`）：视频 DASH 曾走**被封禁的** `/x/player/wbi/playurl`

| | |
|---|---|
| **实测** | `wbi-signature.md:218`、§4.3 :246-260：`/x/player/wbi/playurl` 四种组合（无签名/正确签名/伪造签名/无 Cookie）**全部 HTTP 412**、3286 字节 HTML（`evidence/65-playurl-wbi-nosign.txt`、`90-wbi-ab.txt` 行 48-50）；而**旧路径** `/x/player/playurl?...&fnval=4048` 返回 `code:0` + 3 条 DASH audio（43962/102931/203786，`evidence/64-playurl-legacy-dash.txt`、`90-wbi-ab.txt` §[4] 行 53-58） |
| **现实现** | `PLAYURL_URL = "https://api.bilibili.com/x/player/playurl"`（`BiliApi.kt:86`，KDoc :74-85 记录四种组合的实测）、`PLAYURL_FNVAL = "4048"`（:93）、`videoAudioStream` 用**普通 GET、不签名**（:296-306），URL 形状抽成 `playUrlFor`（:341-342） |
| **守卫测试** | `BiliSourceProviderTest.kt:136` `播放地址走旧路径而不是 wbi 路径`：断言 `startsWith("https://api.bilibili.com/x/player/playurl?")`、不含 `/wbi/playurl`、含 `fnval=4048`、不含 `w_rid` |
| **未闭环** | 412 是「路径 + 出口 IP」的封禁，实测在**探针机出口**上得到；手机出口是否也封 **未实测**（但现在走的是旧路径，与成功样本一致）。另：`videoAudioStream` 的 KDoc 第 :294 行仍写「`fnval=16` = 请求 DASH」（**过期注释**，实际由 `playUrlFor` 给 4048，见 §10.5） |
| **复现** | `python3 wbi_ab.py`（产出 `evidence/90-wbi-ab.txt`） |

### 10.3 ✅ 已修（`0b4ed2c`）：`isSignatureRejected` 曾不认 `-352`

| | |
|---|---|
| **实测** | `wbi-signature.md:20`「缺签名是 **HTTP 200 + `code:-352`**」；§3.3 判读表：无 `w_rid` → `-352`、`w_rid` 全 0 → `-352`、正确 → `0` |
| **旧实现的后果（推断）** | 服务端每日轮换 wbi key、密钥 TTL 6 小时；轮换后若以 `-352` 拒签，客户端既不刷新也不报错 ⇒ `parseSearchTracks` 找不到 `data.result` ⇒ 「B 站 0 首」最长 6 小时 |
| **现实现** | `isSignatureRejected`（`BiliApi.kt:220-226`）返回 `code == -352 \|\| -403 \|\| -1200`，KDoc :213-218 把「漏了它的后果是静默的」写下来了 |
| **守卫测试** | `BiliSourceProviderTest.kt:182` `结构化响应里的 -352 与 -403 与 -1200 都算被拒`；:175 `HTTP 412 的 HTML 正文算被拒`；:191 `正常响应与其它错误码不算被拒` |
| **残留** | `signedGet` 的 KDoc（:190）与 `wbiKeys` 的 `@param force`（:142）仍写「412 / -403」；`BiliWbi.kt:21-23` 的 KDoc 也仍写「没有 `w_rid` 时返回 HTTP 412」——都是**过期注释**（§10.5） |

### 10.4 ❌ **未修（指 HEAD `0b4ed2c`）**：ExoPlayer 取 B 站 CDN 流**没有 Referer**（会 403）

> ⚠️ **并发写入声明（02:52 观察）**：写作时工作区里已出现一份**未提交、未经本探针审计**的修复草稿 ——
> 新增 `app/src/main/java/com/takahashirinta/ncrust/bili/BiliCdn.kt`（100 行，host 白名单 + 兜底判据），
> 并修改 `bili/BiliApi.kt` 与 `cache/OfflineAudioCache.kt`
> （`git diff --stat -- app/src` = `2 files changed, 109 insertions(+), 14 deletions(-)`）。
> **本节的结论只对 HEAD `0b4ed2c` 成立**；草稿的 host 判据是否正确、对网易云/QQ 有没有副作用，
> 本探针**没有审计**（它还在飞行中、随时可能变）。落地后必须重跑单测**并补真机播放验证** ——
> 「单测全绿」不能证明 CDN 不再 403（这正是 §10.4 这条缺陷最初能溜过 1940 个用例的原因）。

| | |
|---|---|
| **实测（旧）** | `evidence/80-cdn-referer-and-ttl.txt` A–E：带 `Referer: https://www.bilibili.com/audio/home` → `HTTP 206`；**完全不带头 → 403**；外部 Referer → 403；只带 UA → 403 |
| **实测（新，并发写入）** | `evidence/96-cdn-referer-exoplayer.txt`（2026-09-28 02:47）把三条路都测了：**音频区 192K / 音频区 320K / 视频 DASH 音轨**，每条都是「带 B 站 Referer → 206；只带 UA → 403；**模拟 ExoPlayer（UA=ExoPlayerLib/1.2.1, Accept-Encoding: identity）→ 403**」⇒ B 站 CDN（openresty）统一防盗链，与音源方式无关 |
| **源码审计** | ExoPlayer 的数据源是 `OfflineAudioCache.dataSourceFactory`（`cache/OfflineAudioCache.kt:111-122`）→ `DefaultDataSource.Factory(app)`，**没有任何 `defaultRequestProperties`**；装配点 `PlaybackService.kt:392`。全仓 `Referer` 只出现在 4 个文件：`network/RetrofitClient.kt`、`qq/QqClient.kt`、`qq/QqQrClient.kt`、`bili/BiliApi.kt` —— **媒体播放路径一个都没有**。另外 `SongUrlResult`（`SongUrlFetcher.kt:34-75`）**没有 headers 字段** ⇒ 即便 Provider 知道要带 Referer，取链结果也无处携带 |
| **推断（后果）** | 即使 §10.1/§10.2 都修好、URL 也取到了，ExoPlayer 拉流仍会 403 ⇒ `onPlayerError` ⇒ 降档重试 ⇒ 最终跳歌。**这是当前唯一能单独让「B 站放不出声」的原因** |
| **未闭环** | 没有在真机上端到端播放过 B 站曲目；CDN 行为是否对所有 B 站 CDN 域名一致（`*.bilivideo.com` / `*.hdslb.com` / `*.mountaintoys.cn`）**未穷举**；`CacheDataSource` 是否会把 403 响应体写进 `SimpleCache`（`FLAG_IGNORE_CACHE_ON_ERROR` 已设）**未实测** |
| **修法（来自 `bili-research/RECOMMENDATIONS.md`，该文件与本探针并发产出）** | A. 给 `SongUrlResult` 加 `headers`，由 `BiliSourceProvider` 填 `Referer: https://www.bilibili.com/`，PlaybackService 建 `MediaItem` 时落到一个**按 host 判定**的 `DataSource.Factory`；B. 在 `OfflineAudioCache.dataSourceFactory` 上挂 `setDefaultRequestProperties`，但**必须按 host 限定**（只对 B 站 CDN 加），否则会把 B 站 Referer 带到网易云/QQ 请求上（本项目已实测「网易云 Referer 请求 B 站 → 403」，反向同理有风险）；C. 让 Provider 把 Referer 需求编码进结果、由 PlaybackService 落实。**三选一都必须做 host 限定** |
| **复现** | `curl -sI -r 0-1023 '<CDN 直链>'`（带 / 不带 Referer 各一次）；或直接跑 `evidence/96` 的采集脚本 |

### 10.5 过期注释清单（不影响行为，但会误导下一个接手的人）

| 位置 | 现在写的 | 实际 |
|---|---|---|
| `BiliSourceProvider.kt:139-147` | 「音频区那条路的歌词是 `song/info` 的 `lyric` 字段」「对音频区返回它的 `lyric` 字段」 | 已改走 `/song/lyric`（:153-166） |
| `BiliApi.kt:294` | 「`fnval=16` = 请求 DASH」 | `playUrlFor` 给的是 `4048`（:341-342、:93） |
| `BiliApi.kt:310` | 「音频区曲目详情（含 **LRC 歌词**）」 | `audioInfo` 里的 `lyric` 是 URL，正文走 `audioLyric` |
| `BiliApi.kt:190`、:142 | 「412 / -403 时强制刷新」 | 判据还含 `-352`（:225） |
| `BiliWbi.kt:21-23` | 「没有 `w_rid` + `wts` 时返回 HTTP 412」 | 实测缺签名是 `200 + code:-352`；412 是路径级封禁 |
| `BiliSourceProvider.kt:43` | 「用一个会计数的假传输层钉住」 | 没有这样的测试基建（§8.3） |

### 10.6 其他诚实清单（未实测 / 未闭环）

| 项 | 状态 |
|---|---|
| `audioStream` 用 web 端点 ⇒ 匿名只有 192K（APP 端点实测能到 320K，**未采用**） | 取舍；若要 320K 需换端点 + 改参数名（`songid`） |
| 旧路径是否需要 `Cookie: buvid3` | **证据自相矛盾**：`bili-audio-api.md` §7.1 正文说「必须带 `buvid3`」，但那一次成功的抓取（`evidence/64-playurl-legacy-dash.txt` 的 `cmd` 行）只带了 UA 与 Referer、**没有 Cookie** ⇒ 本实现不带 Cookie 与成功样本一致；「是否必须」记为未实测 |
| `crtype=2 且 msid != 0` 的音频 `cdns:null` ⇒ 「不可播」 | 实现天然安全（`BiliModels.parseAudioStream:452` 起 `cdns` 为 null 即返回 null），但调研只有 **2 个失败样本**，规律未定论 |
| `size` 字段在新上传音频上不可信（146/150 vs 真实字节） | 本实现只用它填 `br`（`BiliModels.kt:467`），**不用于缓冲判断** ⇒ 影响面是角标可能失真 |
| 音频区搜索的 `search_type=au/song` 是否也 `-1200` | **未实测**（证据只有 music/audio/foobar），见 §7.2 |
| 登录态下能否拿 FLAC / `type:-1` 试听片段 | 均未验证（`bili-audio-api.md` §9） |
| 匿名 `qn=3` 的降级在真实播放链路上的表现 | 未实测（JVM 单测只钉住映射与阶梯形状：`BiliQualityTest.kt:192/:207/:220`） |
| Wbi 签名的**跨天**失效 | 未直接观测（当天只有一个 key 值，`wbi-signature.md` §6） |
| B 站曲目的队列持久化 / 跨版本兼容 | 未实测（`sourceOfId` 与标志位有 JVM 单测，但没有「老版本 App 读到 `bilibili`」的真机实验） |

---

## 11. 守卫测试索引（B 站相关，全部 JVM 单测；数字取自 `0b4ed2c` 的实测结果）

| 文件 | 用例数 | 覆盖 |
|---|---|---|
| `bili/BiliWbiTest.kt` | 11（+`BiliQualityTest` 3） | `fileStem` / `mixinKey`（与实测值一致 :54）/ 4 条签名向量（含中文空格 :70、取播放地址 :88、空格→`+` :113、敏感字符剔除 :133）/ `wts` 是秒 :151 / 空 key 返回未签名 query :163 / md5 向量 :172 / 乱序表 :178；档位映射逐条 :192、阶梯有序去重 :207、qn 取值 :220 |
| `bili/BiliParseTest.kt` | 25（+`BiliTrackMappingTest` 5） | 搜索只认 `video` 且去 `<em>` :49、封面补全/抬 https :59、duration 两形状 :66、limit 截断 :79、坏响应不抛 :85、title 清洗 :94、cid 单P/多P :104、DASH 只取 audio 且最高带宽 :117、baseUrl 缺失退 backup :137、无 audio 返回 null :149、音频区逐字段 :167、下架 :185、**歌词正文走 song/lyric** :199、**song/lyric 两义性** :207、**looksLikeUrl 三种形状** :216、author 优先 uname :226、**`song/info` 的 lyric 两义性** :232、音频流解析 :254、**TTL 按 deadline 而非 timeout** :263、deadline 缺失退 timeout :277、deadline 已过返回已过期时刻 :285、转义 query :293、空 cdns :302、nav 解析 :311/:323；id 带标志位且不撞网易云 :336、载荷形状 :349、往返 :358、造不出 id 就丢 :375、SongItem 带音源与时长 :382 |
| `bili/BiliSourceProviderTest.kt` | 11（+`BiliSignatureRejectionTest` 3） | 默认关闭 :41、关闭时零请求 :49/:60、缺载荷不回落 :73、非可登录音源 :86、key 稳定 :99、注册 :107、`requiresSourceId` :112、prefs 键一致 :118、**播放地址走旧路径** :136、auid 关键词形状 :147；412 HTML 算被拒 :175、**`-352`/`-403`/`-1200` 算被拒** :182、正常响应不算 :191 |
| `ui/components/SourceCountsBiliTest.kt` | 7（+`SearchRankingThreeSourceTest` 4 + `SourceFilterTest` 4） | 关闭时统计行逐字不变 :34、启用后第三段 :46、PENDING 不显示 0 :56、三态文案 :66、8 语言 :85、isDone 三源 :99、英文不漏中文 :111；追加最后 :154、不参与交错 :166、内部顺序稳定 :179；ALL/各取一源 :192、过滤保序与同一实例 :206、B 站档只在启用时出现 :229、四档文案 :241 |
| `source/TrackKeyTest.kt` | 16 | 含 §2 的边界断言 :170-188 |
| `source/SourceIdDomainTest.kt` | 10 | 值域闸门（含 QQ 拒绝网易云值域纯数字 :79、拒绝 pmid :89） |
| `crosssource/AggregateStringsTest.kt` | 5 | 8 语言聚合文案非空 :35、带参数文案 :56、原样透传 :66、dex 单方法预算 :75、主构造器预算 :89 |

单测执行证据见 [EVIDENCE.md](EVIDENCE.md)（`0b4ed2c` 实测：141 suite / 1940 用例全绿）。
