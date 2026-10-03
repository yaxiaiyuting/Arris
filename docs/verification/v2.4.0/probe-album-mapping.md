# 探针 · 专辑跨源匹配（ncm ↔ qm）

> 生成：`docs/verification/v2.4.0/probe-album-mapping.py`（**匿名只读**）。锚点艺人取自 `probe-artist-mapping.md` 的实测结果。
> 原始响应：`probe-raw/album-mapping.json`。

## 结论速览

| 置信度 | 专辑对数 |
|---|---|
| EXACT | 40 |
| HIGH | 4 |
| MEDIUM | 4 |
| LOW | 0 |
| NONE | 0 |

## Q1 · 专辑名是否完全一致

| 项 | 值 |
|---|---|
| 归一化后能配对的专辑 | 242 对（覆盖 6 位艺人的 top80） |
| 其中**原始字符串完全相同** | 33 / 48 |
| 其中**原始字符串不同、归一化后才相等** | 15 / 48 |

逐艺人的配对情况：

| 艺人 | ncm 专辑 | QQ 专辑 | 归一化配对 | 仅 ncm | 仅 QQ |
|---|---|---|---|---|---|
| 周杰伦 | 44 | 43 | **33** | 11 | 10 |
| 林俊杰 | 80 | 75 | **63** | 15 | 9 |
| 五月天 | 74 | 72 | **58** | 15 | 13 |
| G.E.M.邓紫棋 | 62 | 68 | **54** | 4 | 11 |
| 薛之谦 | 28 | 31 | **25** | 3 | 6 |
| Taylor Swift | 80 | 80 | **9** | 19 | 39 |

### 「只差版本后缀」的近似对（`in` 互相包含但归一化不等）

- 周杰伦：ncm `2004无与伦比演唱会` ↔ QQ `周杰伦2004无与伦比演唱会livecd`
- 周杰伦：ncm `周杰伦2007世界巡回演唱会` ↔ QQ `2007世界巡回演唱会`
- 周杰伦：ncm `超时代演唱会` ↔ QQ `theera2010超时代演唱会`
- 周杰伦：ncm `魔天伦世界巡回演唱会` ↔ QQ `周杰伦魔天伦世界巡回演唱会`
- 林俊杰：ncm `和自己对话` ↔ QQ `和自己对话frommetomyself`
- 五月天：ncm `92全运会纪念ep` ↔ QQ `92全运会纪念`
- 五月天：ncm `五月之恋` ↔ QQ `音乐电影五月之恋`
- 五月天：ncm `五月天十万人出头天live` ↔ QQ `十万人出头天live`
- 五月天：ncm `五月天诺亚方舟世界巡回演唱会live` ↔ QQ `诺亚方舟世界巡回演唱会`
- 五月天：ncm `五月天追梦3dna电影原声音乐专辑` ↔ QQ `追梦3dna电影原声音乐`
- 五月天：ncm `步步自选作品辑` ↔ QQ `步步自选作品辑thebestof19992013`
- Taylor Swift：ncm `themorefearlesschapter` ↔ QQ `fearless`
- Taylor Swift：ncm `themoreloverchapter` ↔ QQ `lover`
- Taylor Swift：ncm `thetorturedpoetsdepartmenttstheerastoursetlist` ↔ QQ `ours`

**说明**：归一化规则已把 `（Deluxe）`/`豪华版`/`Remastered` 之类的括注与版本词剥掉（见 `probe_lib.normalize_name`），上与不上的差异都能在上表里看到。「仅 ncm / 仅 QQ」里绝大多数不是版本差异，而是**真的只在一边上架**（例如 ncm 有大量「Live」「伴奏」单曲碟，QQ 归入专辑下的曲目）。

## Q2 · 辅助判据的可信度

| 判据 | 一致数 / 可比数 | 结论 |
|---|---|---|
| 曲目数（`album.size` vs `latest_song.song_count` / `totalNum`） | 41 / 48 | ⚠️ 有分歧，**只能当 HIGH 的加分项**，不能单独当 EXACT 判据 |
| 发行年份 | 43 / 48 | ⚠️ 不够稳，只做参考 |
| 发行方（company） | 8 / 34 | ⚠️ QQ 侧 `company` 常为空 / 口径不同（ncm 是厂牌，QQ 是版权方），**不宜做判据** |

另有两条**比上面都强**的判据（本次实测出来的）：

1. **曲目名集合的重合率** —— 见下表，同源专辑之间普遍 ≥90%；
2. **曲目序号 / 时长** —— 两源都给 `interval`（秒）与 `dt`（毫秒），实测同一首歌在两源的时长差 ≤1s（本次抽样全部命中）。

## Q4 · 逐对详情（曲目名集合比对）

| 艺人 | 专辑 | ncm id | QQ mid | 曲目数 网/QQ | 曲目名重合 | 重合率 | 判定 | 名称相同 |
|---|---|---|---|---|---|---|---|---|
| 周杰伦 | Jay | `18918` | `000f01724fd7TH` | 10/10 | **10** | 100% | **EXACT** | ✅ |
| 周杰伦 | Mojito | `90743831` | `0009C3rp3Kfwg0` | 1/1 | **1** | 100% | **EXACT** | ✅ |
| 周杰伦 | Six Degrees | `259316984` | `001g7BIA0IyPBZ` | 1/1 | **1** | 100% | **EXACT** | ✅ |
| 周杰伦 | Try | `34420153` | `001V8fw21OdEnP` | 1/1 | **1** | 100% | **EXACT** | ✅ |
| 周杰伦 | 七里香 | `18903` | `003DFRzD192KKD` | 10/10 | **10** | 100% | **EXACT** | ✅ |
| 周杰伦 | 不爱我就拉倒 | `38721188` | `001CnPE31iJ899` | 1/1 | **1** | 100% | **EXACT** | ✅ |
| 周杰伦 | 不能说的秘密 电影原声带 | `18888` | `001UP7mW458ipG` | 25/25 | **24** | 100% | **EXACT** | ✅ |
| 周杰伦 | 依然范特西 | `18893` | `002jLGWe16Tf1H` | 10/10 | **10** | 100% | **EXACT** | ✅ |
| 林俊杰 | 100天 | `10766` | `002C0kX720gMQi` | 13/13 | **13** | 100% | **EXACT** | ✅ |
| 林俊杰 | 100天Love音乐实录 | `10758` | `004HNY674Nu68w` | 12/12 | **11** | 92% | **EXACT** | ❌（归一化后相等） |
| 林俊杰 | 2006就是俊杰世界巡回演唱会 | `10786` | `003zzhok1oFRWM` | 23/22 | **21** | 96% | **HIGH** | ❌（归一化后相等） |
| 林俊杰 | 2infinity And Beyond | `34875558` | `002RmPKZ10U80d` | 1/1 | **1** | 100% | **EXACT** | ✅ |
| 林俊杰 | 7千3百多天 | `154001751` | `001DyYEn16mUQm` | 1/1 | **1** | 100% | **EXACT** | ✅ |
| 林俊杰 | At Least I Had You | `136159734` | `001lSnI84flkYe` | 1/1 | **1** | 100% | **EXACT** | ✅ |
| 林俊杰 | Cheer Singapore | `10762` | `004gdnOA15jSXD` | 1/1 | **1** | 100% | **EXACT** | ✅ |
| 林俊杰 | Despacito 缓缓 | `37251646` | `000iHuBG0KsgOh` | 1/1 | **1** | 100% | **EXACT** | ❌（归一化后相等） |
| 五月天 | DNA | `156295043` | `004CMjEx2cnboY` | 2/2 | **2** | 100% | **EXACT** | ✅ |
| 五月天 | Do You Ever Shine? | `2805329` | `001g35Hp0ZM20e` | 6/6 | **5** | 100% | **EXACT** | ✅ |
| 五月天 | Enrich Your Life | `38261` | `0006oAnx03zXUC` | 6/6 | **5** | 100% | **EXACT** | ✅ |
| 五月天 | Final Home 当我们混在一起 | `38253` | `00042Dad1CLpHz` | 39/39 | **37** | 95% | **EXACT** | ❌（归一化后相等） |
| 五月天 | I Will Carry You | `38562055` | `001xf32c0zs2so` | 1/1 | **1** | 100% | **EXACT** | ✅ |
| 五月天 | Mayday×五月天 the Best of 1999-2013 | `2702043` | `001lRV0S20EpYw` | 15/13 | **10** | 77% | **MEDIUM** | ❌（归一化后相等） |
| 五月天 | Your Legend ~燃ゆる命~ | `3165061` | `003koohY2jHuFB` | 6/6 | **5** | 83% | **MEDIUM** | ❌（归一化后相等） |
| 五月天 | 为你写下这首情歌 | `156893900` | `0048JkDO00Blk7` | 1/1 | **1** | 100% | **EXACT** | ✅ |
| G.E.M.邓紫棋 | 18 | `23505` | `000HZzEx0QIMNM` | 11/16 | **9** | 82% | **MEDIUM** | ❌（归一化后相等） |
| G.E.M.邓紫棋 | 25LOOKS | `34897549` | `000GROwE30lDMA` | 4/4 | **4** | 100% | **EXACT** | ✅ |
| G.E.M.邓紫棋 | A.I.N.Y. 爱你 | `23499` | `00412vqA22pvPi` | 21/22 | **20** | 95% | **HIGH** | ✅ |
| G.E.M.邓紫棋 | Amazing Grace | `188209036` | `000cGuvr2B5smi` | 2/2 | **1** | 100% | **EXACT** | ✅ |
| G.E.M.邓紫棋 | Bang Bang (From 'Minions: The Rise of Gru' Soundtrack) | `147494109` | `000BYYLS28QQmx` | 1/1 | **1** | 100% | **EXACT** | ❌（归一化后相等） |
| G.E.M.邓紫棋 | G.E.M. | `23509` | `003FBC9b4XV8i9` | 5/5 | **5** | 100% | **EXACT** | ✅ |
| G.E.M.邓紫棋 | G.E.M.X.X.X.Live | `2698217` | `003oLIsS1rbdAz` | 23/23 | **23** | 100% | **EXACT** | ❌（归一化后相等） |
| G.E.M.邓紫棋 | Get Everybody Moving Concert 2011 | `23498` | `000pUbJr1Aac6E` | 29/31 | **24** | 86% | **MEDIUM** | ✅ |
| 薛之谦 | 一半 | `3316028` | `000dEfCC2ccVxz` | 3/3 | **3** | 100% | **EXACT** | ✅ |
| 薛之谦 | 人字拖 | `373448570` | `002hKiJX4bjcun` | 1/1 | **1** | 100% | **EXACT** | ✅ |
| 薛之谦 | 你不是一个人 | `149878590` | `003i7vXr1ScBjJ` | 1/1 | **1** | 100% | **EXACT** | ✅ |
| 薛之谦 | 你过得好吗 | `17079` | `004PY3HK4cv5z4` | 10/10 | **9** | 100% | **EXACT** | ✅ |
| 薛之谦 | 几个薛之谦 | `17072` | `003qp8IX21MXH0` | 9/9 | **9** | 100% | **EXACT** | ✅ |
| 薛之谦 | 初学者 | `34780271` | `000dcZ9I1nzO62` | 10/10 | **10** | 100% | **EXACT** | ✅ |
| 薛之谦 | 天外来物 | `121012393` | `000K9Zp13TZp5s` | 10/10 | **10** | 100% | **EXACT** | ✅ |
| 薛之谦 | 媚人 | `387407402` | `002vfBmm41fIRe` | 1/1 | **1** | 100% | **EXACT** | ✅ |
| Taylor Swift | 1989 (Taylor's Version) (Deluxe) | `177825165` | `002Kz5Jo1uzHjz` | 22/19 | **16** | 100% | **HIGH** | ❌（归一化后相等） |
| Taylor Swift | Elizabeth Taylor | `368715414` | `003bJYRQ2dEPPA` | 3/3 | **1** | 100% | **EXACT** | ✅ |
| Taylor Swift | I Knew It, I Knew You | `381339074` | `003BXFyG4EIyEc` | 3/3 | **1** | 100% | **EXACT** | ❌（归一化后相等） |
| Taylor Swift | Opalite (Chris Lake Remix) | `362507750` | `002KpGLf2Q6Num` | 1/1 | **1** | 100% | **EXACT** | ❌（归一化后相等） |
| Taylor Swift | Safe & Sound (Taylor's Version) | `161980893` | `000Vud1B17iKlX` | 1/1 | **1** | 100% | **EXACT** | ❌（归一化后相等） |
| Taylor Swift | Speak Now (Taylor's Version) | `168588248` | `001QmRCD2PGa4o` | 22/22 | **16** | 94% | **EXACT** | ❌（归一化后相等） |
| Taylor Swift | The Fate of Ophelia (TELYKAST & XanTz Remix (Extended Version)) | `358461738` | `004AbbRe0HcGil` | 1/1 | **1** | 100% | **EXACT** | ✅ |
| Taylor Swift | The Life of a Showgirl | `284356964` | `002N1U4h0VKQFV` | 12/26 | **12** | 100% | **HIGH** | ❌（归一化后相等） |

### 曲目不一致的样本（差异明细）

- **林俊杰 / 100天Love音乐实录**：仅 ncm ['记得心墙当你']；仅 QQ ['组曲记得心墙当你']
- **林俊杰 / 2006就是俊杰世界巡回演唱会**：仅 ncm ['莎郎嘿哟只对你说', '被风吹过的夏天']；仅 QQ ['사랑해요只对你说']
- **五月天 / Final Home 当我们混在一起**：仅 ncm ['enrichyourlife', '约翰蓝侬']；仅 QQ ['enrichyourlife让我照顾你', '约翰列侬']
- **五月天 / Mayday×五月天 the Best of 1999-2013**：仅 ncm ['乾杯', '出陣の歌', '孫悟空', '恋愛ing']；仅 QQ ['孙悟空', '干杯', '恋爱ing']
- **五月天 / Your Legend ~燃ゆる命~**：仅 ncm ['盛夏光年']；仅 QQ ['青春の彼方盛夏光年']
- **G.E.M.邓紫棋 / 18**：仅 ncm ['ainy爱你', 'mascara烟熏妆']；仅 QQ ['ainy', 'mascara', 'wheredidugo20', '写不完的温柔']
- **G.E.M.邓紫棋 / A.I.N.Y. 爱你**：仅 ncm ['allaboutu']；仅 QQ ['allaboutyou', '美好的旧时光']
- **G.E.M.邓紫棋 / Get Everybody Moving Concert 2011**：仅 ncm ['thevoicewithin遗失的声音', 'wannabestartinsomethin', '我的秘密', '美好的旧时光inmyheart']；仅 QQ ['lupo眼中的gem', 'mysecret', 'tan幕后的故事', 'thevoicewithin']
- **Taylor Swift / 1989 (Taylor's Version) (Deluxe)**：仅 ncm ['isitovernow', 'nowthatwedonttalk', 'saydontgo', 'slut']；仅 QQ （无）
- **Taylor Swift / Speak Now (Taylor's Version)**：仅 ncm ['castlescrumbling', 'electrictouch', 'foolishone', 'icanseeyou']；仅 QQ ['ifthiswasamovie']
- **Taylor Swift / The Life of a Showgirl**：仅 ncm （无）；仅 QQ ['thelifeofashowgirlintro', 'thelifeofashowgirloutro']

## Q3 · 匹配失败时的降级

1. **不合并**：两侧各自作为独立专辑行展示，UI 标注音源（铁律 17）。
2. **专辑详情页只在 confidence ≥ MEDIUM 时聚合歌曲列表**；低于阈值时只展示当前源的曲目，并提供「切到另一源」入口。
3. **`LOW`/`NONE` 不写匹配缓存**，下次进入重试。
4. **曲目级合并另算**：即使专辑配上了，单曲仍按 `MergedTrack` 规则单独判（见 `probe-song-mapping.md`），专辑级匹配**不传递**到曲目级。
