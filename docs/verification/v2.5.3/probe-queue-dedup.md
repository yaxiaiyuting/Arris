# probe-queue-dedup.md · v2.5.3 · P1：队列去重的 key 现状与双源 id 冲突发生率

> **探针方法**：`docs/verification/v2.5.3/probe-queue-dedup.py`（可重跑）
> **原始输出**：`docs/verification/v2.5.3/probe-queue-dedup.raw.txt`
> **探针提交**：HEAD = `9e0f67e`（v2.5.2-gpl，`versionCode 43`）
> **铁律 2 / 铁律 6**：所有数字来自 HEAD 源码或可重放的算术，并做 A/B 对照。

```bash
python3 docs/verification/v2.5.3/probe-queue-dedup.py | tee docs/verification/v2.5.3/probe-queue-dedup.raw.txt
```

---

## 0. 结论先行（含对既有文档的**更正**）

| 问题 | 结论 |
|---|---|
| 当前队列去重用什么 key？ | **裸 `song.id`**，落点 **14 处**，全在 `MainActivity.kt` |
| 双源 `song.id` 冲突实际发生率 | **0**，而且是 **bit62 标志位带来的结构性 0**，不是抽样运气 |
| 同源内 `song.id` 是否唯一？ | 是（ncm = 平台 songId；QQ = `bit62 \| songid`） |
| 跨源冲突的实际影响 | **今天不存在**；今天存在的是「身份规则有两套」 |
| 跨源队列是否已有用户报障？ | **没有找到任何一条**（`docs/**`、`AGENTS.md`、`git log` 全文检索） |
| 改成 TrackKey 后顺序/槽位/随机是否受影响？ | 顺序与随机**不受影响**（它们按下标）；待播槽位**本来就按音源**，改造后与队列判重**收敛为同一套** |
| 是否有历史数据需要迁移？ | **没有**（队列快照落的是整个 `SongItem`，带 `source`/`mid`/`media_id`） |

> ### ⚠️ 对 v2.5.0/v2.5.1 遗留清单的更正
>
> v2.5.0 / v2.5.1 的未验证清单里写着：
> 「队列去重用**裸 `song.id`**，跨源**裸 id 撞号**（ncm 某首 vs QQ 某首）时会把其中一首当重复。」
>
> **这句话的前提在本版被证伪。** 它成立需要「ncm 的 songId 与 QQ 的 songId 落在同一个数值空间」，
> 而 v2.1.0 · A 起 QQ 的 id 一律由 `SourceIds.qqId()` 产出（见 §2），
> **bit62 恒置位**，与 ncm 1e6~3e9 的区间不相交 —— 撞号在 64 位整数上不可能发生。
>
> 按 `AGENTS.md` v2.5.1 规则 2（探针结论与既有说法冲突时以探针为准），
> 本版**更正**这条遗留项，并把真正的收益重述为「去掉双身份规则」（§5）。
> 这不改变本版要做 TrackKey 改造的结论，但改变了它的**定性**：
> 这是一次**结构加固**，不是 bug 修复；本版**不声称修好了任何用户可见的问题**。

---

## 1. 当前去重落点：14 处，全部内联、全部裸 `song.id`

```
MainActivity.kt
   1084  [队列内按 id 找歌]     val found = playbackQueue.firstOrNull { it.id == id }
   1190  [INFINITY 去重集]      val existingIds = playbackQueue.map { it.id }.toSet()
   1494  [同曲短路（取反）]      if (song.id != currentId) {
   1495  [队列去重（单曲）]      val filtered = playbackQueue.filter { it.id != song.id }.toMutableList()
   1506  [播放前定位下标]        val idx = playbackQueue.indexOfFirst { it.id == song.id }
   1637  [同曲短路]             if (song.id == currentId) return
   1638  [队列去重（单曲）]      val filtered = playbackQueue.filter { it.id != song.id }
   1641  [去重后重定位当前歌]    playbackQueue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
   1688  [待插入列表剔除当前歌]  val toInsert = if (currentId != null) songs.filter { it.id != currentId } else songs
   1691  [队列去重（批量）]      val filtered = playbackQueue.filter { it.id !in ids }.toMutableList()
   1709  [INFINITY 去重集]      val existingIds = playbackQueue.map { it.id }.toSet()
   1710  [批量追加去重]          val songs.filter { it.id !in existingIds }
```

**两个后果**：

1. **不可单测** —— 它们全是 `MainActivity`（Compose 编排层）里的内联表达式，
   JVM 单测够不着。唯一被抽出来的是 v2.5.0 · D 的 `QueueInsert.plan`（「添加到下一首播放」一条路径）。
2. **同一个应用里有两套身份语义**：

   | 用途 | 身份 |
   |---|---|
   | 队列去重（上述 14 处） | 裸 `Long` |
   | 待播槽位 `PreloadSlot` | `(source, id)`（含在 mediaId 里） |
   | 歌词闸门 `LyricLoadCoordinator` | `TrackKey` |
   | 续播恢复 `PlaybackStateManager` | `TrackKey.of(...)` |

### 1.1 一个刺眼的既有事实

`SongSourceExt.kt` 里**已经**定义了 `SongItem.dedupeKey`，KDoc 写着
「**队列内判重用的身份串**……不能用 `SongItem.id` 单独判重」，
`SongSourceExtTest` 里**已经有两条守卫用例**（`同为ncm的新旧条目身份一致` /
`同 id 不同音源的判重键不同`）——

**而它在生产代码里的调用点是 0 处。**

```
★ 已存在但零调用点的身份属性：SongItem.dedupeKey
  `X.dedupeKey` 在生产代码里的使用: 0 处 ← 定义在、守卫在，但一次都没接线
```

也就是说：**阵地早就修好了，只是没接线**。

> ⚠️ **v2.5.3 之后的准确说法**：本版把 `dedupeKey` 的**定义**改成
> `TrackKey.ofSong(this).tag` —— 与生产路径（`trackKeyOf()`）**同一个落点**，
> 所以它不再可能漂移。但**直接读 `.dedupeKey` 的代码仍然是 0 处**，
> 生产路径走的是 `trackKeyOf()`。「不再会漂移」与「已被调用」是两件事，不混为一谈。

---

## 2. `SongItem.id` 的生产者与 id 形态

探针枚举了全部 `SongItem(` 构造点，逐个抽 `id = …` 与 `source = …`：

| 生产者 | id 形态 | 判定 |
|---|---|---|
| `qq/QqSongMapper.kt:115` | `syntheticId`（= `SourceIds.qqId(rawId, mid)`） | ✅ QQ 带 bit62 |
| `qq/QqCatalog.kt:351` | `SourceIds.qqId(...)` | ✅ QQ 带 bit62 |
| `ui/screen/SongDetailScreen.kt:256` | `SourceIds.qqId(idFromRoute, …)`（路由形态无标志位时补上） | ✅ QQ 带 bit62 |
| `network/PlaylistApi.kt` ×5 | `optLong("id")`（ncm 接口） | ✅ 裸 id（ncm） |
| `crosssource/CatalogAggregator.kt:520` | `s.id` + `source = NETEASE` | ✅ 裸 id（ncm） |
| `MainActivity.kt:1752` / `2262` | ncm 专辑详情 | ✅ 裸 id（ncm） |
| `playlist/PlaylistCacheCodec.kt:440` | 从 DTO 恢复，**要求 `source` 非空** | ✅ 形态随源 |
| `source/SongSourceExt.kt:65`（`songRefOf`） | 调用方给 | ✅ 调用方 4 处全部来自 `TrackKey`/合成 id |
| `MainActivity.kt:887`（续播恢复） | `PlaybackStateManager.getSourceKey/Id/MediaId` | ✅ 三件套齐全 |

**没有发现任何一条「QQ 曲目拿到未置位的裸 id」的路径。**
反过来，**有一条「QQ 曲目丢了 `source` 字符串但 id 仍带 bit62」的路径**：
`ui/screen/SearchScreen.kt:749` 的 `HistoryItem.toSongItem()` 只恢复 `id`
（`SearchHistoryManager.HistoryItem` 里没有 source 字段）。
这一条不影响 id 空间隔离（id 还是合成的），但**会影响按字符串判音源的代码** ——
见 §5.3，它是本版选择 `TrackKey.ofSong` 而不是 `TrackKey.fromSong` 的直接原因。

---

## 3. id 空间隔离的**算术证明**

```
QQ_ID_FLAG = 1L shl 62 = 4611686018427387904  (0x4000000000000000)
ncm songId 实测量级 : 1e6 ~ 3e9   （远小于 2^40 = 1099511627776）
QQ 合成 id 的最小值    : 4611686018427387905  (bit62 恒置位)
两个区间是否相交       : 否
```

⇒ 只要 QQ 曲目的 id 都经 `SourceIds.qqId()` 产出，**「ncm id == QQ id」在 64 位整数上
不可能成立** —— 与抽样无关，也与队列里放了多少首无关。

这与 `MusicSource.kt` 里 v2.1.0 · A 的设计文档一致，且该文档已经写明收益是
「撞号从『需要每个调用点都记得带音源』变成**结构上不可能**」。

---

## 4. 抽样 1000 首双源配对（A/B 对照，铁律 6）

取值形状取自仓库里的**真实观测**（`docs/verification/**`、`app/src/test/**`）：

| 量 | 取值 |
|---|---|
| ncm songId | 7~10 位十进制，`1e6 ~ 3e9`（实测例：`5257138` / `287035` / `1959528822` / `1295411603` / `102792543`） |
| QQ 原始 songid | 9~10 位十进制，`1e9 ~ 3.6e9` |
| QQ songmid | 14 位、`00` 起头的 base62（实测例：`0039MnYb0qxYhV` 等） |

```
样本数                          : 1000（ncm × QQ 各 1 首配对）
裸 song.id 上的跨源数值冲突     : 0 次
裸 song.id 上的同源重复         : 0
TrackKey 上的冲突               : 0 次
```

**这个 0 不是抽样运气**，是 §3 的算术在样本上的体现 —— 样本里 QQ 的 id 由
探针**逐位复刻**的 `qqId()`（FNV-1a 64 位、取低 62 位）产出，bit62 恒置位。

### 4.1 同源唯一性 + 兜底散列

| 情形 | 结论 |
|---|---|
| ncm 同源 | id 就是平台 songId，平台内唯一 ⇒ 无重复可能 |
| QQ 同源（有 songid） | `bit62 \| songid`，songid 平台内唯一 ⇒ 唯一 |
| QQ 同源（**没有** songid，FNV-1a 散列 songmid 兜底） | 抽样 **20000** 个 songmid → **0** 个散列碰撞；理论概率 ≈ `n²/2^63 = 4.3e-11` |

---

## 5. 跨源冲突：真实影响是什么

### 5.1 今天的影响面 = 0（用户可见层面）

因为 §3 的算术，**没有任何一条真实队列会因为「跨源同号」而被误去重**。
检索 `docs/**`、`AGENTS.md`、`git log` 也**没有找到任何一条用户报障**提到这件事。

### 5.2 那为什么还要改

因为今天那个 0 依赖一条**纪律**（「每个 id 生产者都记得走 `qqId`」），
而 `MusicSource.kt` 自己就记录了这条纪律**破过一次**：v2.1.0 之前
所有结构都是裸 `Long` 作唯一键，10+ 处，正是 `qqId` 的设计动机。

改成 `TrackKey` 之后：

1. **判据由类型承载**：`QueueInsert.plan(queue.map { it.id }, …)` **编译不过**；
2. **只剩一套身份规则**：队列判重、待播槽位、歌词闸门、续播恢复用同一个 `TrackKey`；
3. **判重逻辑第一次可单测**：从 `MainActivity` 的 14 处内联表达式收敛成
   `player/QueueKeys.kt` 的纯函数；
4. **`SongItem.dedupeKey` 这条早就写好、带守卫、却零调用的防线，不会再与队列判重漂移** ——
   它的定义并到了与生产路径同一个落点上（注意：**不是**「它被调用了」）。

### 5.3 一个**新发现的相邻风险**（本版顺带修掉，并如实记录）

`SearchHistoryManager.HistoryItem` 只存 `id`，没有 `source`。
`SearchScreen.kt:749` 的 `HistoryItem.toSongItem()` 因此恢复出一首
`source = null` 的曲目 —— 如果它原本是 QQ 曲目，`SongItem.musicSource`
（`MusicSource.fromKey(null)`）会把它认成**ncm**。

- **这个 bug 不是本版引入的**，本版也没有把「搜索结果写进历史记录」这条路上的音源补全
  （那要改 `SearchHistoryManager` 的表结构与迁移，超出本次范围）。
- 但它**直接决定了 P1 用哪个构造函数**：`TrackKey.fromSong(song)` 沿用字符串口径，
  会把这个错继承进队列身份；`TrackKey.ofSong(song)` 走 `TrackKey.of`，
  在 `source` 为空时按 id 的 bit62 标志位推断 —— **认得出这是 QQ**。
- 所以本版选了 `ofSong`，并把它定为「`SongItem` → 身份的唯一落点」。
  这个选择有独立单测（`QueueKeysTest.身份落点是 TrackKey_ofSong …`）。

---

## 6. 队列顺序 / 待播槽位 / 随机模式是否受影响

| 机制 | 身份类型 | 本版改动 | 行为是否变 |
|---|---|---|---|
| 队列**顺序** | 无（就是 `List<SongItem>` 的顺序） | 无 | **不变** |
| 队列**去重** | 裸 `Long` → `TrackKey` | 14 处收敛进 `QueueKeys` | 正常路径逐值不变；只有 §5.3 那种「source 丢了」的形态被修正 |
| **待播槽位** | `(source, id)`（编在 mediaId 里） | **没碰** | **不变**（并新增「与队列身份一致」的断言） |
| **随机模式** | **下标**（`shuffledIndices: List<Int>`） | 只换入参类型，算法一行未动 | **不变**（并新增跨源队列下的排列合法性用例） |
| 续播进度表 | 裸 `Long`（QQ 是合成 id） | 无 | **不变** |
| 混乱度游标 `shuffledPosition` | 下标 | 无 | **不变** |

**关键设计决定**：随机编排是**按下标**的（`ShuffleRound` / `shuffleAfterInsert`
的全部输入输出都是 `List<Int>`），所以「身份换类型」对它天然是零影响。
这正是探针要在动手前确认的事 —— 如果编排是按身份存的，改造面会完全不同。

---

## 7. 历史数据：**不需要迁移**（这一点必须证明，不能靠断言）

`ncrust_playback_state` 的 `queue` + `queue_index`：

```
落盘类型: List<SongItem>（Gson 全量对象，不是 id 列表）
SongItem 的持久化字段:
    id       -> id          ← 身份用
    source   -> source      ← 身份用
    mid      -> sourceId
    media_id -> mediaId
    （另有 name/ar/al/dt/fee/member_only/privilege/... 等元数据）
```

**v2.1.0 · A 起写入的每一条队列条目本来就带 `source` / `mid` / `media_id`** ——
`TrackKey` 可以直接从既有字段算出来，**没有任何「旧 key 形状」需要翻译**。

而 v2.1.0 **之前**的条目没有 `source` 字段：Gson 走 Unsafe 反序列化、不调用构造函数
⇒ 读到 `null` ⇒ `MusicSource.fromKey(null)` 回落 ncm。那正是老数据的正确解释
（那时候只有 ncm），本版**保持**这个语义。

`QueueKeysTest` 用 4 条用例把这件事钉住（`持久化队列的形状 - 老 JSON 直接可用 无需迁移`
等）：直接给 Gson 喂 v2.1.0 之前的 JSON、v2.1.0 之后的 JSON、以及混合队列，
断言算出来的身份各自正确且互不干扰。

> **不写迁移逻辑**是这里的正确决定，不是省事：
> v1.9.3 固化的规则是「加字段 = 加迁移逻辑 = 加单测」，而**本版没有加任何字段** ——
> 队列快照的形状一个字节都没动。为不存在的旧形状写迁移，只会引入一份新的、
> 需要长期维护与测试的死代码。

---

## 7.1 改造后复测（同一支探针，在 v2.5.3 的 HEAD 上重跑）

```
==============================================================================
1) 队列去重的落点（HEAD，全部按裸 `song.id`）
==============================================================================
  合计落点: 0 处，分布在 0 个文件（注释行已排除 —— 改造后旧写法只应出现在 KDoc 的反面教材里）
```

**从 14 处降到 0 处**，且这是**探针实测**而不是声称。

> 一个探针自身的坑（已修，记在这里）：改造后旧的裸 id 写法会以**反面教材**的形式
> 留在 `QueueKeys.kt` 的 KDoc 里。探针第一版只按 `//` 切行，于是把那些
> ` * val filtered = …` 当成真实落点，报出「还剩 3 处没改」的**假结论**。
> 修法是加一条 `is_comment_line()`（识别 `//` / `*` / `/*` 开头）。
> 教训：**探针的正则要跟着代码形态一起演进**，否则它会从「证据」变成「噪音」。

---

## 8. 未确认项（如实）

1. **1000 首抽样用的是「真实形状 + 合成样本」**，不是线上真实曲库的 1000 首
   （那需要登录态与网络，且本次不要求实机/联网验证）。结论的强度来自 §3 的算术证明，
   抽样只是它的一个示例 —— **不把抽样当成主要证据**。
2. **QQ 兜底散列（无 songid 路径）在线上是否真的会被走到**未确认：
   探针只证明了它的碰撞概率极低，没有统计它在真实 QQ 响应里的出现频率。
3. **`SearchScreen` 的搜索历史音源丢失（§5.3）没有修**，只改了 P1 的身份选取以避开它。
   完整修法要改 `SearchHistoryManager` 的持久化结构 + 迁移 + 单测，列入下一版候选。
4. **14 处落点是否全部覆盖**依据的是探针的正则清单
   （`filter { it.id != … }` / `map { it.id }` / `indexOfFirst { it.id == … }` 等）。
   可能有形态不同但语义相同的写法没被匹配到 —— 缓解手段是
   `QueueInsert.plan` 的签名已强类型化（编译期挡住一条主要路径），
   以及 `QueueKeysTest` 里那条反射断言。
5. **队列顺序在跨源插入后的实际观感**未真机验证（本次不要求实机验证）。
6. **性能**：`QueueKeys.keysOf(queue)` 每次写操作会多一次 O(n) 的 map（n = 队列长度）。
   队列上限未见约束，1000 首时是一次 1000 元素的 map —— 量级上远小于同一次操作里
   已有的 `saveQueue` Gson 序列化。但**没有做 release 包的帧时间 A/B**，列入缺口清单。
