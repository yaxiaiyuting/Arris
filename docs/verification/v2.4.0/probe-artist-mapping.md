# 探针 · 艺人跨源匹配（ncm ↔ qm）

> 生成：`docs/verification/v2.4.0/probe-artist-mapping.py`（**匿名只读**）。
> 无登录态可用：真机 WGR-W09 未 root（`su` 不存在）、release 包不可 `run-as`、`adb root` 被拒（production build）。匿名态对**艺人/专辑/单曲的 id、名称、曲目列表**没有任何影响（这些是公开目录数据），但会影响 `purl`（播放地址）——那部分单独在 `probe-copyright-field.md` 里做 A/B。
> 原始响应：`probe-raw/artist-mapping.json`。

## 结论速览

| 置信度 | 校验算法（本版要实现的） | 朴素同名算法（反面教材） |
|---|---|---|
| EXACT | 8 | 2 |
| HIGH | 3 | 8 |
| MEDIUM | 0 | 0 |
| LOW | 0 | 0 |
| NONE | 0 | 1 |

**可合并率（confidence ≥ MEDIUM）= 11/11 = 100%**；其中 ≥HIGH = 11/11 = 100%。

## 逐艺人实测

| 艺人 | 标注 | ncm 锚点 | QQ 锚点 | ncm 专辑 | QQ 专辑 | 重合 | 校验判定 | 朴素判定 |
|---|---|---|---|---|---|---|---|---|
| 周杰伦 | 华语·ncm 无版权；ncm 3 个同名字号 | `6452` 周杰伦 | `0025NhlN2yWrP4` 周杰伦 | 44 | 43 | **33** | **EXACT** | HIGH |
| 林俊杰 | 华语·两源都有版权；两源各有 2 个同名字号 | `3684` 林俊杰 | `001BLpXF2DyJe2` 林俊杰 | 78 | 72 | **63** | **EXACT** | HIGH |
| 五月天 | 华语乐队 | `13193` 五月天 | `000Sp0Bz4JXH0o` 五月天 | 73 | 71 | **58** | **EXACT** | EXACT |
| 陈奕迅 | 华语·同名少 | `2116` 陈奕迅 | `003Nz2So3XXYek` 陈奕迅 | 74 | 78 | **31** | **HIGH** | HIGH |
| 邓紫棋 | 华语·**艺名与真名不同**（真身 G.E.M.邓紫棋） | `7763` G.E.M.邓紫棋 | `001fNHEf1SFEFN` G.E.M.邓紫棋 | 58 | 65 | **54** | **EXACT** | NONE |
| 李健 | 华语·同名歧义严重（QQ 同名字号 9 个） | `3695` 李健 | `001oEyQf4Ub6s7` 李健 | 50 | 44 | **35** | **EXACT** | HIGH |
| 周深 | 华语·新生代 | `1030001` 周深 | `003fA5G40k6hKc` 周深 | 76 | 78 | **41** | **EXACT** | EXACT |
| 薛之谦 | 华语 | `5781` 薛之谦 | `002J4UUk29y8BY` 薛之谦 | 28 | 31 | **25** | **EXACT** | HIGH |
| Taylor Swift | 欧美·英文名 | `44266` Taylor Swift | `000qrPik2w6lDr` Taylor Swift | 28 | 48 | **9** | **HIGH** | HIGH |
| Adele | 欧美·单名 | `46487` Adele | `003CoxJh1zFPpx` Adele | 18 | 20 | **18** | **EXACT** | HIGH |
| 张学友 | 华语·老牌 | `6460` 张学友 | `004Be55m1SJaLk` 张学友 | 75 | 80 | **25** | **HIGH** | HIGH |

### 判定依据（逐条，可追溯）

- **周杰伦** → `EXACT`：唯一同名候选 + 专辑重合 33 张（占较小侧 77%）
  - 候选 `周杰伦` (0025NhlN2yWrP4, albumNum=43)：拉回 43 张专辑，与 ncm 重合 **33**
- **林俊杰** → `EXACT`：唯一同名候选 + 专辑重合 63 张（占较小侧 88%）
  - 候选 `林俊杰` (001BLpXF2DyJe2, albumNum=75)：拉回 72 张专辑，与 ncm 重合 **63**
- **五月天** → `EXACT`：唯一同名候选 + 专辑重合 58 张（占较小侧 82%）
  - 候选 `五月天` (000Sp0Bz4JXH0o, albumNum=72)：拉回 71 张专辑，与 ncm 重合 **58**
- **陈奕迅** → `HIGH`：同名 + 专辑重合 31 张
  - 候选 `陈奕迅` (003Nz2So3XXYek, albumNum=141)：拉回 78 张专辑，与 ncm 重合 **31**
- **邓紫棋** → `EXACT`：唯一同名候选 + 专辑重合 54 张（占较小侧 93%）
  - 候选 `G.E.M.邓紫棋` (001fNHEf1SFEFN, albumNum=68)：拉回 65 张专辑，与 ncm 重合 **54**
- **李健** → `EXACT`：唯一同名候选 + 专辑重合 35 张（占较小侧 80%）
  - 候选 `李健` (001oEyQf4Ub6s7, albumNum=46)：拉回 44 张专辑，与 ncm 重合 **35**
- **周深** → `EXACT`：唯一同名候选 + 专辑重合 41 张（占较小侧 54%）
  - 候选 `周深` (003fA5G40k6hKc, albumNum=312)：拉回 78 张专辑，与 ncm 重合 **41**
- **薛之谦** → `EXACT`：唯一同名候选 + 专辑重合 25 张（占较小侧 89%）
  - 候选 `薛之谦` (002J4UUk29y8BY, albumNum=31)：拉回 31 张专辑，与 ncm 重合 **25**
- **Taylor Swift** → `HIGH`：同名 + 专辑重合 9 张
  - 候选 `Taylor Swift` (000qrPik2w6lDr, albumNum=197)：拉回 48 张专辑，与 ncm 重合 **9**
- **Adele** → `EXACT`：唯一同名候选 + 专辑重合 18 张（占较小侧 100%）
  - 候选 `Adele` (003CoxJh1zFPpx, albumNum=26)：拉回 20 张专辑，与 ncm 重合 **18**
- **张学友** → `HIGH`：同名 + 专辑重合 25 张
  - 候选 `张学友` (004Be55m1SJaLk, albumNum=136)：拉回 80 张专辑，与 ncm 重合 **25**

## P6 · 两种算法的差异（**这一段是本探针最重要的产出**）

| 艺人 | 朴素算法 | 校验算法 | 为什么不同 |
|---|---|---|---|
| 周杰伦 | **HIGH** | **EXACT** | 朴素同名锚到「周杰伦」；包含召回 + 专辑重合锚到「周杰伦」(0025NhlN2yWrP4) |
| 林俊杰 | **HIGH** | **EXACT** | 朴素同名锚到「林俊杰」；包含召回 + 专辑重合锚到「林俊杰」(001BLpXF2DyJe2) |
| 邓紫棋 | **NONE** | **EXACT** | 朴素同名锚到「邓紫棋」；包含召回 + 专辑重合锚到「G.E.M.邓紫棋」(001fNHEf1SFEFN) |
| 李健 | **HIGH** | **EXACT** | 朴素同名锚到「李健」；包含召回 + 专辑重合锚到「李健」(001oEyQf4Ub6s7) |
| 薛之谦 | **HIGH** | **EXACT** | 朴素同名锚到「薛之谦」；包含召回 + 专辑重合锚到「薛之谦」(002J4UUk29y8BY) |
| Adele | **HIGH** | **EXACT** | 朴素同名锚到「Adele」；包含召回 + 专辑重合锚到「Adele」(003CoxJh1zFPpx) |

**教训（写进 AGENTS.md 的铁律 17 依据）**：「名字对了」不等于「是同一个人」。邓紫棋那一行里，朴素算法把 ncm 的**仿冒号** `邓紫棋`(62017015, 1 张专辑) 当成锚点，于是两边都配不上；而真身 `G.E.M.邓紫棋`(7763, 58 张) 在两边都在。所以线上必须是「**召回 → 校验 → 分级**」三步：召回用包含关系、判定用专辑列表重合 —— 名字只用来召回，绝不用来判定。

## P1 · 两源 ID 体系是否互通

**不互通，且没有任何共同键。** 实测证据：

| 事实 | 实测值 |
|---|---|
| ncm 周杰伦 | `id=6452`（十进制） |
| QQ 周杰伦 | `singerID=4558` / `singerMID=0025NhlN2yWrP4`（base62 字符串） |
| 两源响应里有没有对方的 id | **没有** —— `/api/artist/albums/6452` 与 `fcg_v8_singer_album.fcg?singermid=0025NhlN2yWrP4` 的字段集合完全不相交 |
| 有没有 ISRC / 指纹 / 共享艺人码 | **没有**：两源的艺人接口都不返回 ISRC |
| QQ 的 `singerID` 能不能直接当 ncm id | **不能**：实测同一艺人 `6452 ≠ 4558`，两个编号空间各自独立 |

⇒ 跨源艺人身份**只能靠可观测属性**匹配（名称 + 专辑列表重合度）。它是启发式而不是权威事实 —— 这正是必须做置信度分级、且低于阈值不合并的原因。

## P2 · 可用于匹配的属性

| 属性 | ncm | qm | 可用于匹配？ |
|---|---|---|---|
| 艺名 | `name` | `singerName` | ✅ **召回**用 |
| 别名/英文名 | **`alias[]`**（周杰伦 = `['Jay Chou','周董']`） | 艺人搜索**不返回别名**（`singerTransName` 只在专辑搜索里） | ⚠️ 单边属性，**不能**做对称判据。反例：ncm `Jay`(122200643) 的 `alias` 同时含周杰伦/林俊杰/权志龙/章若楠 —— 拿别名判定会一次错配四个人 |
| 繁体/异体名 | 归一化后仍不同（周杰伦 ≠ 周杰倫） | QQ 同时有 `周杰伦`(4558, 43 张) 与 `周杰倫`(23063564, 1 张，疑似仿冒) | ⚠️ 需要简繁归一化，**本版不做**（见 P4） |
| 专辑数 | `albumSize` | `albumNum` | ⚠️ 口径不同（周杰伦：41 vs 43），只用于**候选排序**，不用于判定 |
| 歌曲数 | `musicSize` | `songNum` | ⚠️ 口径差异更大（568 vs 1012，QQ 含伴奏/多版本） |
| 粉丝数 | 艺人搜索**不返回** | 艺人搜索**不返回** | ❌ |

## P3 · 同名艺人如何区分

实测的同名分布（匿名搜索 top20 内，归一化名称相等）：

| 艺人 | ncm 同名字号 | QQ 同名字号 | 区分手段 |
|---|---|---|---|
| 周杰伦 | 3 | 1 | 专辑数排序 + 专辑列表重合校验 |
| 林俊杰 | 2 | 2 | 专辑数排序 + 专辑列表重合校验 |
| 五月天 | 1 | 1 | 专辑数排序 + 专辑列表重合校验 |
| 陈奕迅 | 1 | 1 | 专辑数排序 + 专辑列表重合校验 |
| 邓紫棋 | 12 | 0 | 专辑数排序 + 专辑列表重合校验 |
| 李健 | 5 | 13 | 专辑数排序 + 专辑列表重合校验 |
| 周深 | 1 | 1 | 专辑数排序 + 专辑列表重合校验 |
| 薛之谦 | 3 | 1 | 专辑数排序 + 专辑列表重合校验 |
| Taylor Swift | 3 | 1 | 专辑数排序 + 专辑列表重合校验 |
| Adele | 3 | 7 | 专辑数排序 + 专辑列表重合校验 |
| 张学友 | 1 | 1 | 专辑数排序 + 专辑列表重合校验 |

**结论**：靠「名称」永远分不开（周杰伦在 ncm 有 3 个同名字号、`李健` 在 QQ 有 9 个）；靠「粉丝数」不行（接口不返回）。唯一在实测中稳定的是**专辑列表重合度**：真身之间重合 9~63 张，仿冒号之间重合 0 张。

## P4 · 匹配失败时的降级

1. **不合并**（铁律 17）：两侧各自成组，UI 上标注音源，用户自己选。
2. **不做「猜 ncm」**：合并失败时的默认音源由**可播放性**决定，不由 id 空间决定。
3. **不缓存失败**：`NONE` / `LOW` 不写匹配缓存，下次进页面重试。
4. **保留人工入口**：艺人页可切「只看 ncm / 只看 QQ / 双源」。
5. **繁简/别名不做隐式归一**：`周杰伦` 与 `周杰倫` 在本版**视为两个艺人**（宁可不合并，也不冒错配风险）。

## P5 · 分级分布

见「结论速览」。样本 11 位艺人，覆盖华语/欧美、有版权/无版权、同名歧义/无歧义、艺名≠真名四类。

## 明细样本（归一化后的专辑名，前 6 条）

### 周杰伦 — EXACT

- ncm 锚点：`6452` 周杰伦（44 张）
- QQ 锚点：`0025NhlN2yWrP4` 周杰伦（43 张）
- 两侧都有（归一化）：11月的萧邦, 2004无与伦比演唱会, fantasyshow香港演唱会, initialj, jay, mojito
- 仅 ncm：11月的萧邦, 2004无与伦比演唱会, fantasyshow香港演唱会, initialj, partners拍档, theone周杰伦演唱会
- 仅 QQ：2007世界巡回演唱会, jiiimp3player, theera2010超时代演唱会, theone演唱会, 十一月的萧邦, 周杰伦2004无与伦比演唱会livecd

### 林俊杰 — EXACT

- ncm 锚点：`3684` 林俊杰（78 张）
- QQ 锚点：`001BLpXF2DyJe2` 林俊杰（72 张）
- 两侧都有（归一化）：100天, 100天love音乐实录, 2006就是俊杰世界巡回演唱会, 2infinityandbeyond, 7千3百多天, asibelieve
- 仅 ncm：asibelieve, checkmate, gentlebones, hold住爱影视原声, iprayforyou, oursingapore
- 仅 QQ：itunessession, 乐行者, 俯冲的灵魂, 和自己对话frommetomyself, 幸存者drifter, 我为你祈祷

### 五月天 — EXACT

- ncm 锚点：`13193` 五月天（73 张）
- QQ 锚点：`000Sp0Bz4JXH0o` 五月天（71 张）
- 两侧都有（归一化）：92全运会纪念ep, belief春を待つ君へ, dna, doyouevershine, enrichyourlife, finalhome当我们混在一起
- 仅 ncm：92全运会纪念ep, belief春を待つ君へ, 五月之恋, 五月天创造小巨蛋dnalive创记录音, 五月天十万人出头天live, 五月天诺亚方舟世界巡回演唱会live
- 仅 QQ：92全运会纪念, callmeno1, dnalive五月天创造小巨蛋演唱会创纪录音, firstday, 你要去哪里台湾巡回演唱会live全纪录, 候鸟五月天电影音乐作品

### 陈奕迅 — HIGH

- ncm 锚点：`2116` 陈奕迅（74 张）
- QQ 锚点：`003Nz2So3XXYek` 陈奕迅（78 张）
- 两侧都有（归一化）：2013陈奕迅musiclife精选, 3mm, 3mmremixlp, 903idclub拉阔音乐会, chinup, cmonin
- 仅 ncm：3mmremixlp, 903idclub拉阔音乐会, chinup, cmonin, duo陈奕迅2010演唱会, easonair
- 仅 QQ：6829, 903idclub拉阔音乐会陈奕迅x杨千嬅x梁汉文, eason18首选, eason4achangehits, easonchanduoconcert2010, easonfriends903idclub拉阔音乐会

### 邓紫棋 — EXACT

- ncm 锚点：`7763` G.E.M.邓紫棋（58 张）
- QQ 锚点：`001fNHEf1SFEFN` G.E.M.邓紫棋（65 张）
- 两侧都有（归一化）：18, 18plus, 25looks, ainy爱你, amazinggrace, bangbang
- 仅 ncm：18plus, goodtobebad, 后会无期, 女也herstorywithmayday
- 仅 QQ：justthewayyouare, sacrifice争, somedayillfly, 你把我灌醉, 多远都要在一起, 小黄人大眼萌神偷奶爸前传电影原声专辑

### 李健 — EXACT

- ncm 锚点：`3695` 李健（50 张）
- QQ 锚点：`001oEyQf4Ub6s7` 李健（44 张）
- 两侧都有（归一化）：feelinggood, marine玛琳娜, 一念一生, 一路花香一路唱, 为你而来, 人群中的人
- 仅 ncm：marine玛琳娜, 人群中的人, 你从天而降, 如果可以, 宋词辑壹, 完美坚持
- 仅 QQ：1981, 君子行, 庆余年第二季影视原声带, 无乐不谈, 李健, 李健自选辑1

### 周深 — EXACT

- ncm 锚点：`1030001` 周深（76 张）
- QQ 锚点：`003fA5G40k6hKc` 周深（78 张）
- 两侧都有（归一化）：七夜雪电视剧影视原声带, 万里晴空, 三餐四季, 云边的风筝, 今天的我们, 仙台有树电视剧原声音乐专辑
- 仅 ncm：七夜雪电视剧影视原声带, 三餐四季, 云边的风筝, 仙台有树电视剧原声音乐专辑, 借过一下, 再一次见面
- 仅 QQ：letitgo, unravel, 一期一会未闻花名, 不再流浪, 不说话, 东游

### 薛之谦 — EXACT

- ncm 锚点：`5781` 薛之谦（28 张）
- QQ 锚点：`002J4UUk29y8BY` 薛之谦（31 张）
- 两侧都有（归一化）：一半, 一首歌一个故事, 为了遇见你, 人字拖, 你不是一个人, 你过得好吗
- 仅 ncm：一首歌一个故事, 为了遇见你, 渡
- 仅 QQ：传说, 别, 最好, 渡thecrossing, 电视剧你微笑时很美影视原声带, 那是你离开了北京的生活

### Taylor Swift — HIGH

- ncm 锚点：`44266` Taylor Swift（28 张）
- QQ 锚点：`000qrPik2w6lDr` Taylor Swift（48 张）
- 两侧都有（归一化）：1989, allofthegirlsyoulovedbefore, antihero, cruelsummer, elizabethtaylor, eyesopen
- 仅 ncm：allofthegirlsyoulovedbefore, antihero, cruelsummer, eyesopen, fortnight, icandoitwithabrokenheart
- 仅 QQ：22, badblood, beautifuleyes, christmastreefarm, cmtcrossroads, crazier

### Adele — EXACT

- ncm 锚点：`46487` Adele（18 张）
- QQ 锚点：`003CoxJh1zFPpx` Adele（20 张）
- 两侧都有（归一化）：19, 21, 25, 30, chasingpavements, coldshoulder
- 仅 ncm：（无）
- 仅 QQ：ituneslivefromsoho, youllneverseemeagainnevergonnaleaveyou

### 张学友 — HIGH

- ncm 锚点：`6460` 张学友（75 张）
- QQ 锚点：`004Be55m1SJaLk` 张学友（80 张）
- 两侧都有（归一化）：903拉阔演唱会, blackwhite, btb3ep张学友黄凯芹, byyourside, greatesthits新曲精选, jackycheung15
- 仅 ncm：903拉阔演唱会, btb3ep张学友黄凯芹, byyourside, greatesthits新曲精选, lustcaution, multiverseofpolygram55thanniversary张学友
- 仅 QQ：1999友个人演唱会, 25周年smile, 93学与友演唱会, dsd视听之王张学友爱你多一些精选, greatesthits新歌精选, jacky
