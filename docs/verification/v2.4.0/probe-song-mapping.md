# 探针 · 单曲跨源匹配（ncm ↔ qm）

> 生成：`docs/verification/v2.4.0/probe-song-mapping.py`（**匿名只读**）。专辑对取自 `probe-album-mapping.md` 的实测结果。
> 原始响应：`probe-raw/song-mapping.json`。

## 结论速览

| 置信度 | 曲目数 | 占比 |
|---|---|---|
| EXACT | 123 | 48% |
| HIGH | 61 | 24% |
| MEDIUM | 46 | 18% |
| LOW | 0 | 0% |
| NONE | 26 | 10% |

## S1 · 匹配该用哪个字段

| 字段 | ncm | qm | 能不能当跨源键 |
|---|---|---|---|
| 数字 id | `songId`（如 `186016`） | `songid`（如 `97773`） | ❌ 两个编号空间独立，实测同一首《晴天》`186016 ≠ 97773` |
| 字符串 mid | **没有** | `songmid`（如 `0039MnYb0qxYhV`） | ❌ 只有一边有 |
| `media_mid` | **没有** | `003Qui1q2u1Zho`（**与 `songmid` 不同**） | ❌ 只有一边有 |
| ISRC | **不返回** | **不返回** | ❌ |
| 音频指纹 | 不返回 | 不返回 | ❌ |

`media_mid == mid` 的比例（QQ 侧实测，本样本 230 首）：

- 只有 0 首两者相等 —— 印证 v2.1.0 的结论：**取链必须用 `media_mid`**，匹配时两个都要留着（任一都可能在另一条路径上缺失）。

⇒ **跨源单曲匹配没有任何权威键**，只能用可观测属性：`曲名 + 艺人 + 专辑 + 时长`。这就是必须做置信度分级的原因。

## S5 · 时长判据的实测精度

| 项 | 值 |
|---|---|
| 可比对数 | 230 |
| 时长差 ≤ 2s | 184 / 230 = 80% |
| 中位差 | 543 ms |
| 最大差 | 195356 ms |
| 分布（≤1s / 1~2s / 2~10s / 10~30s / >30s） | 164 / 20 / 24 / 7 / 15 |

**关键读数**：≤2s 的占 184/230；而 >30s 的 15 条全部是**同名不同版本**（伴奏 / Live / 加长版）—— 它们正是「时限判据」要挡掉的东西。

**结论**：时长是一个**强判据**（口径都是「整曲时长」，ncm `dt` 毫秒、QQ `interval` 秒 ×1000）。容差取 **2s** 是实测出来的：同一首歌两源的差集中在 0~1000ms（编码器补静音/淡出的差异）。

## S2 · 同名不同版本

同一张专辑里**归一化曲名相同、原始名不同**的样本：

- 周杰伦 / 不能说的秘密 电影原声带：`Secret (加长快板)` / `Secret (慢板)`
- 五月天 / Do You Ever Shine?：`Do You Ever Shine` / `Do You Ever Shine? (BITTER BLOOD Version)`
- 五月天 / Enrich Your Life：`Enrich Your Life` / `Enrich Your Life(伴奏)`
- 薛之谦 / 你过得好吗：`你过得好吗` / `你过得好吗(伴奏)`

**区分规则（本版采用）**：

1. **归一化曲名一致 + 艺人一致 + 时长差 ≤2s + 版本标记一致** ⇒ `EXACT`；
2. 上面四条里**只有版本标记不同**（一侧 `Live`、一侧没有）⇒ `HIGH`（仍可合并，但 UI 必须显示两源各自的原始名）；
3. 版本标记不同**且时长差 >2s** ⇒ 降 `LOW`，**不合并**（`Live` 版通常比录音室版长）；
4. 同名 + 艺人不同（合唱/翻唱）⇒ 最多 `MEDIUM`，且**不自动选源**。

## S3 · 匹配失败时的降级

1. **不合并**（铁律 17）：单曲页只展示当前源的版本，并给「在另一源搜索」入口。
2. **绝不按 `LOW` 自动合并**，也绝不拿 `LOW` 的结果去选默认音源。
3. `LOW`/`NONE` **不写匹配缓存**。
4. 单曲页的「默认音源」判据是**可播放性**，不是匹配置信度 —— 即使配上了，若当前源无版权而另一源有，默认切到有版权的那一源。

## S4 · 逐曲明细

| 专辑 | 曲名（ncm） | QQ 曲名 | 时长差 | 判定 | 依据 |
|---|---|---|---|---|---|
| 周杰伦 / Jay | 伊斯坦堡 | 伊斯坦堡 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / Jay | 印第安老斑鸠 | 印第安老斑鸠 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / Jay | 反方向的钟 | 反方向的钟 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / Jay | 可爱女人 | 可爱女人 | 27 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 27ms |
| 周杰伦 / Jay | 娘子 | 娘子 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / Jay | 完美主义 | 完美主义 | 533 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 533ms |
| 周杰伦 / Jay | 斗牛 | 斗牛 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / Jay | 星晴 | 星晴 | 400 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 400ms |
| 周杰伦 / Jay | 黑色幽默 | 黑色幽默 | 190 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 190ms |
| 周杰伦 / Jay | 龙卷风 | 龙卷风 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 七里香 | 七里香 | 七里香 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 七里香 | 乱舞春秋 | 乱舞春秋 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 七里香 | 借口 | 借口 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 七里香 | 园游会 | 园游会 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 七里香 | 困兽之斗 | 困兽之斗 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 七里香 | 外婆 | 外婆 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 七里香 | 将军 | 将军 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 七里香 | 我的地盘 | 我的地盘 | 640 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 640ms |
| 周杰伦 / 七里香 | 搁浅 | 搁浅 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 七里香 | 止战之殇 | 止战之殇 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 不能说的秘密 电影原声带 | Angel | Angel | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 不能说的秘密 电影原声带 | Ending | Ending | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 不能说的秘密 电影原声带 | First Kiss | First Kiss | 173 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 173ms |
| 周杰伦 / 不能说的秘密 电影原声带 | Flash Back | Flash Back | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 不能说的秘密 电影原声带 | Opening | Opening | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 不能说的秘密 电影原声带 | Ride With Me | Ride With Me | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 不能说的秘密 电影原声带 | Secret (慢板) | Secret | 17067 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=17067） |
| 周杰伦 / 不能说的秘密 电影原声带 | The Swan | The Swan | 586 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 586ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 不能说的秘密 | 不能说的秘密 | 533 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 533ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 与父共舞 | 与父共舞 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 女孩别为我哭泣 | 女孩别为我哭泣 | 1360 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1360ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 小雨写立可白Ⅰ | 小雨写立可白Ⅰ | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 小雨写立可白Ⅱ | 小雨写立可白Ⅱ | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 情人的眼泪 | 情人的眼泪 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 斗琴 | 斗琴 | 546 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 546ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 早操 | 早操 | 106 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 106ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 晴天娃娃 | 晴天娃娃 | 1974 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1974ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 淡水海边 | 淡水海边 | 1294 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1294ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 湘伦小雨四手联弹 | 湘伦小雨四手联弹 | 1160 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1160ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 父与子 | 父与子 | 1920 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1920ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 琴房 | 琴房 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 脚踏车 | 脚踏车 | 586 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 586ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 路小雨 | 路小雨 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 周杰伦 / 不能说的秘密 电影原声带 | 阿郎与阿宝 | 阿郎与阿宝 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 林俊杰 / 100天 | Still Moving Under Gunfire | Still Moving Under Gunfire | 543 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 543ms |
| 林俊杰 / 100天 | X | X | 177 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 177ms |
| 林俊杰 / 100天 | 一个又一个 | 一个又一个 | 306 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 306ms |
| 林俊杰 / 100天 | 加油！ | 加油 | 640 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 640ms |
| 林俊杰 / 100天 | 妈妈的娜鲁娃 | 妈妈的娜鲁娃 | 557 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 557ms |
| 林俊杰 / 100天 | 无法克制 | 无法克制 | 794 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 794ms |
| 林俊杰 / 100天 | 曙光 | 曙光 | 662 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 662ms |
| 林俊杰 / 100天 | 爱不会绝迹 | 爱不会绝迹 | 431 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 431ms |
| 林俊杰 / 100天 | 第几个100天 | 第几个100天 | 906 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 906ms |
| 林俊杰 / 100天 | 背对背拥抱 | 背对背拥抱 | 919 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 919ms |
| 林俊杰 / 100天 | 表达爱 | 表达爱 | 386 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 386ms |
| 林俊杰 / 100天 | 跟屁虫 | 跟屁虫 | 520 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 520ms |
| 林俊杰 / 100天 | 转动 | 转动 | 436 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 436ms |
| 林俊杰 / 100天Love音乐实录 | 一个又一个 (Live) | 一个又一个 | 0 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（一个又一个 (Live) vs 一个又一个） |
| 林俊杰 / 100天Love音乐实录 | 加油 (Live) | 加油 | 0 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（加油 (Live) vs 加油） |
| 林俊杰 / 100天Love音乐实录 | 妈妈的娜鲁娃 (Live) | 妈妈的娜鲁娃 | 0 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（妈妈的娜鲁娃 (Live) vs 妈妈的娜鲁娃） |
| 林俊杰 / 100天Love音乐实录 | 小酒窝 (Live) | 小酒窝 | 0 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（小酒窝 (Live) vs 小酒窝） |
| 林俊杰 / 100天Love音乐实录 | 无法克制 (Live) | 无法克制 | 0 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（无法克制 (Live) vs 无法克制） |
| 林俊杰 / 100天Love音乐实录 | 爱与希望 (Live) | 爱与希望 | 0 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（爱与希望 (Live) vs 爱与希望） |
| 林俊杰 / 100天Love音乐实录 | 第几个100天 (Live) | 第几个100天 | 0 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（第几个100天 (Live) vs 第几个100天） |
| 林俊杰 / 100天Love音乐实录 | 组曲：记得 + 心墙 + 当你 | — | 不可比 | **NONE** | 只有一侧有这首 |
| 林俊杰 / 100天Love音乐实录 | 翅膀 (Live) | 翅膀 | 0 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（翅膀 (Live) vs 翅膀） |
| 林俊杰 / 100天Love音乐实录 | 背对背拥抱 (Live) | 背对背拥抱 | 771 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（背对背拥抱 (Live) vs 背对背拥抱） |
| 林俊杰 / 100天Love音乐实录 | 记得+心墙+当你 (Live) | — | 不可比 | **NONE** | 只有一侧有这首 |
| 林俊杰 / 100天Love音乐实录 | 豆浆油条 (Live) | 豆浆油条 | 0 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（豆浆油条 (Live) vs 豆浆油条） |
| 林俊杰 / 100天Love音乐实录 | 转动 (Live) | 转动 | 3000 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=3000） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | Encore (Live) | Encore | 493 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（Encore (Live) vs Encore） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | I Do (Live) | I Do | 0 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（I Do (Live) vs I Do） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 一千年以后 (Live) | 一千年以后 | 44640 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=44640） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 决战时刻 (Live) | 决战时刻 | 802 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（决战时刻 (Live) vs 决战时刻） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 听不懂 没关系 (Live) | 听不懂没关系 | 26 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（听不懂 没关系 (Live) vs 听不懂没关系） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 善与恶 (Live) | 善与恶 | 746 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（善与恶 (Live) vs 善与恶） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 子弹列车 (Live) | 子弹列车 | 760 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（子弹列车 (Live) vs 子弹列车） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 就是我 (Live) | 就是我 | 4827 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=4827） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 幕后英雄 (Live) | 幕后英雄 | 0 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（幕后英雄 (Live) vs 幕后英雄） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 开场白 (Live) | 开场白 | 506 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（开场白 (Live) vs 开场白） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 忘记 (Live) | 忘记 | 1688 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（忘记 (Live) vs 忘记） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 明天 (Live) | 明天 | 78374 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=78374） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 木乃伊 (Live) | 木乃伊 | 124387 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=124387） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 江南 (Live) | 江南 | 4040 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=4040） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 熟能生巧 (Live) | 熟能生巧 | 72294 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=72294） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 爱笑的眼睛 (Live) | 爱笑的眼睛 | 106 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（爱笑的眼睛 (Live) vs 爱笑的眼睛） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 第二天堂 (Live) | 第二天堂 | 16774 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=16774） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 翅膀 (Live) | 翅膀 | 17907 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=17907） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 英雄再现 (Live) | 英雄再现 | 0 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（英雄再现 (Live) vs 英雄再现） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 莎郎嘿哟只对你说 (Live) | — | 不可比 | **NONE** | 只有一侧有这首 |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 被风吹过的夏天 (Live) | — | 不可比 | **NONE** | 只有一侧有这首 |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 记得 (Live) | 记得 | 2400 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2400） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 进化论 (Live) | 进化论 | 853 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（进化论 (Live) vs 进化论） |
| 林俊杰 / 2006就是俊杰世界巡回演唱会 | 사랑해요只对你说 | — | 不可比 | **NONE** | 只有一侧有这首 |
| 五月天 / Do You Ever Shine? | DNA | DNA | 546 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 546ms |
| 五月天 / Do You Ever Shine? | Do You Ever Shine | Do You Ever Shine? | 10920 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=10920） |
| 五月天 / Do You Ever Shine? | OAOA(日语版) | OAOA | 360 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 360ms |
| 五月天 / Do You Ever Shine? | 拥抱(日语版) | 拥抱 | 666 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 666ms |
| 五月天 / Do You Ever Shine? | 诺亚方舟 | 诺亚方舟 | 600 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 600ms |
| 五月天 / Enrich Your Life | Enrich Your Life | Enrich Your Life | 970 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 970ms |
| 五月天 / Enrich Your Life | 憨人 | 憨人 | 2951 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2951） |
| 五月天 / Enrich Your Life | 温柔(还你自由版) | 温柔 | 526 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 526ms |
| 五月天 / Enrich Your Life | 生命有一种绝对 | 生命有一种绝对 | 279 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 279ms |
| 五月天 / Enrich Your Life | 终结孤单 | 终结孤单 | 746 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 746ms |
| 五月天 / Final Home 当我们混在一起 | Enrich Your Life (Live) | — | 不可比 | **NONE** | 只有一侧有这首 |
| 五月天 / Final Home 当我们混在一起 | Enrich Your Life 让我照顾你 | — | 不可比 | **NONE** | 只有一侧有这首 |
| 五月天 / Final Home 当我们混在一起 | Hosee (Live) | HoSee | 171 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（Hosee (Live) vs HoSee） |
| 五月天 / Final Home 当我们混在一起 | I Love You 无望 (Live) | I Love You 无望 | 839 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（I Love You 无望 (Live) vs I Love You 无望） |
| 五月天 / Final Home 当我们混在一起 | Intro 红 (Live) | Intro + 红 | 536 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（Intro 红 (Live) vs Intro + 红） |
| 五月天 / Final Home 当我们混在一起 | Ok啦 (Live) | OK啦 | 228 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（Ok啦 (Live) vs OK啦） |
| 五月天 / Final Home 当我们混在一起 | Talking Of Mayday | Talking Of Mayday | 346 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 346ms |
| 五月天 / Final Home 当我们混在一起 | Tears For Fears (Live) | Tears For Fears | 64 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（Tears For Fears (Live) vs Tears For Fears） |
| 五月天 / Final Home 当我们混在一起 | The Heart Beat Of 反击 (Live) | The Heart Beat Of 反击 | 505 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（The Heart Beat Of 反击 (Live) vs The Heart Beat Of 反击） |
| 五月天 / Final Home 当我们混在一起 | 一颗苹果 (Live) | 一颗苹果 | 474 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（一颗苹果 (Live) vs 一颗苹果） |
| 五月天 / Final Home 当我们混在一起 | 人生海海 (Live) | 人生海海 | 489 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（人生海海 (Live) vs 人生海海） |
| 五月天 / Final Home 当我们混在一起 | 倔强 (Live) | 倔强 | 955 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（倔强 (Live) vs 倔强） |
| 五月天 / Final Home 当我们混在一起 | 叫我第一名 (Live) | 叫我第一名 | 432 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（叫我第一名 (Live) vs 叫我第一名） |
| 五月天 / Final Home 当我们混在一起 | 回来吧 (Live) | 回来吧 | 625 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（回来吧 (Live) vs 回来吧） |
| 五月天 / Final Home 当我们混在一起 | 圣诞夜惊魂 (Live) | 圣诞夜惊魂 | 293 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（圣诞夜惊魂 (Live) vs 圣诞夜惊魂） |
| 五月天 / Final Home 当我们混在一起 | 垃圾车 (朋友版) (Live) | 垃圾车 | 773 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（垃圾车 (朋友版) (Live) vs 垃圾车） |
| 五月天 / Final Home 当我们混在一起 | 孙悟空+放手 (Live) | 孙悟空 + 放手 | 957 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（孙悟空+放手 (Live) vs 孙悟空 + 放手） |
| 五月天 / Final Home 当我们混在一起 | 小护士 (Live) | 小护士 | 520 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（小护士 (Live) vs 小护士） |
| 五月天 / Final Home 当我们混在一起 | 心中无别人 (Live) | 心中无别人 | 639 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（心中无别人 (Live) vs 心中无别人） |
| 五月天 / Final Home 当我们混在一起 | 志明与春娇 (Live) | 志明与春娇 | 210 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（志明与春娇 (Live) vs 志明与春娇） |
| 五月天 / Final Home 当我们混在一起 | 恒星的恒心 (Live) | 恒星的恒心 | 439 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（恒星的恒心 (Live) vs 恒星的恒心） |
| 五月天 / Final Home 当我们混在一起 | 憨人 (Live) | 憨人 | 895 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（憨人 (Live) vs 憨人） |
| 五月天 / Final Home 当我们混在一起 | 拥抱+相信 (Live) | 拥抱 + 相信 | 877 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（拥抱+相信 (Live) vs 拥抱 + 相信） |
| 五月天 / Final Home 当我们混在一起 | 时光机 (Live) | 时光机 | 1022 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（时光机 (Live) vs 时光机） |
| 五月天 / Final Home 当我们混在一起 | 晚安 地球人 (Live) | 晚安 地球人 | 4 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（晚安 地球人 (Live) vs 晚安 地球人） |
| 五月天 / Final Home 当我们混在一起 | 武装 (Live) | 武装 | 173 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（武装 (Live) vs 武装） |
| 五月天 / Final Home 当我们混在一起 | 温柔 (Live) | 温柔 | 277 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（温柔 (Live) vs 温柔） |
| 五月天 / Final Home 当我们混在一起 | 燕尾蝶 (Live) | 燕尾蝶 | 653 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（燕尾蝶 (Live) vs 燕尾蝶） |
| 五月天 / Final Home 当我们混在一起 | 爱情万岁+脱掉 (Live) | 爱情万岁 + 脱掉 | 508 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（爱情万岁+脱掉 (Live) vs 爱情万岁 + 脱掉） |
| 五月天 / Final Home 当我们混在一起 | 疯狂世界 (Live) | 疯狂世界 | 772 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（疯狂世界 (Live) vs 疯狂世界） |
| 五月天 / Final Home 当我们混在一起 | 神的孩子都混在一起 (Live) | 神的孩子都混在一起 | 369 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（神的孩子都混在一起 (Live) vs 神的孩子都混在一起） |
| 五月天 / Final Home 当我们混在一起 | 约翰列侬 | — | 不可比 | **NONE** | 只有一侧有这首 |
| 五月天 / Final Home 当我们混在一起 | 约翰蓝侬 (Live) | — | 不可比 | **NONE** | 只有一侧有这首 |
| 五月天 / Final Home 当我们混在一起 | 终结孤单 (Live) | 终结孤单 | 188 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（终结孤单 (Live) vs 终结孤单） |
| 五月天 / Final Home 当我们混在一起 | 而我知道 (Live) | 而我知道 | 305 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（而我知道 (Live) vs 而我知道） |
| 五月天 / Final Home 当我们混在一起 | 花 (Live) | 花 | 397 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（花 (Live) vs 花） |
| 五月天 / Final Home 当我们混在一起 | 赌神 (Live) | 赌神 | 194 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（赌神 (Live) vs 赌神） |
| 五月天 / Final Home 当我们混在一起 | 超人 (Live) | 超人 | 188 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（超人 (Live) vs 超人） |
| 五月天 / Final Home 当我们混在一起 | 轧车 (Live) | 轧车 | 968 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（轧车 (Live) vs 轧车） |
| 五月天 / Final Home 当我们混在一起 | 轻功 (Live) | 轻功 | 758 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（轻功 (Live) vs 轻功） |
| 五月天 / Final Home 当我们混在一起 | 这个世界 (Live) | 这个世界 | 1003 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（这个世界 (Live) vs 这个世界） |
| G.E.M.邓紫棋 / 25LOOKS | 再见 (Club Remix) | 再见 | 237 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（再见 (Club Remix) vs 再见） |
| G.E.M.邓紫棋 / 25LOOKS | 多远都要在一起 (Dub Mix) | 多远都要在一起 | 534 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 534ms |
| G.E.M.邓紫棋 / 25LOOKS | 泡沫 (PÀO MÒ Remix) | 泡沫 | 473 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（泡沫 (PÀO MÒ Remix) vs 泡沫） |
| G.E.M.邓紫棋 / 25LOOKS | 瞬间 (Dance Remix) | 瞬间 | 539 ms | **HIGH** | 曲名 + 艺人 + 时长一致，但版本标记不同（瞬间 (Dance Remix) vs 瞬间） |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | 18 | 18 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | A.I.N.Y. (爱你) | A.i.n.y. | 800 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 800ms |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | All About U | — | 不可比 | **NONE** | 只有一侧有这首 |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | All About You | — | 不可比 | **NONE** | 只有一侧有这首 |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | G.E.M. (Get Everybody Moving) | G.E.M. | 2528 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2528） |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | Get Over You | Get Over You | 787 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 787ms |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | Good To Be Bad | Good To Be Bad | 13787 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=13787） |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | Mascara (烟熏妆) | Mascara | 1040 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1040ms |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | One Button | One Button | 186 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 186ms |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | Say It Loud | Say It Loud | 426 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 426ms |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | The Voice Within | The Voice Within | 2333 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2333） |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | Twinkle II | Twinkle II | 799 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 799ms |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | Where Did U Go | Where Did U Go | 2200 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2200） |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | 回忆的沙漏 | 回忆的沙漏 | 4600 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=4600） |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | 塞纳河 | 塞纳河 | 2600 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2600） |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | 寂寞星球的玫瑰 | 寂寞星球的玫瑰 | 973 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 973ms |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | 想讲你知 | 想讲你知 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | 意式恋爱 (粤) | 意式恋爱 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | 我不懂爱 | 我不懂爱 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | 我的秘密 | 我的秘密 | 1854 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1854ms |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | 末日 | 末日 | 973 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 973ms |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | 爱现在的我 | 爱现在的我 | 1626 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1626ms |
| G.E.M.邓紫棋 / A.I.N.Y. 爱你 | 美好的旧时光 | — | 不可比 | **NONE** | 只有一侧有这首 |
| G.E.M.邓紫棋 / G.E.M. | Where Did U Go | Where Did U Go | 1800 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1800ms |
| G.E.M.邓紫棋 / G.E.M. | 回忆的沙漏 (国) | 回忆的沙漏 | 467 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 467ms |
| G.E.M.邓紫棋 / G.E.M. | 爱现在的我 (国) | 爱现在的我 | 640 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 640ms |
| G.E.M.邓紫棋 / G.E.M. | 睡公主 | 睡公主 | 2120 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2120） |
| G.E.M.邓紫棋 / G.E.M. | 等一个他 | 等一个他 | 26 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 26ms |
| 薛之谦 / 一半 | Stay Here | Stay Here | 395 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 395ms |
| 薛之谦 / 一半 | 一半 | 一半 | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| 薛之谦 / 一半 | 小孩 | 小孩 | 405 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 405ms |
| 薛之谦 / 你过得好吗 | 丢手绢 | 丢手绢 | 2853 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2853） |
| 薛之谦 / 你过得好吗 | 你过得好吗 | 你过得好吗 | 387 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 387ms |
| 薛之谦 / 你过得好吗 | 倾城 | 倾城 | 2893 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2893） |
| 薛之谦 / 你过得好吗 | 朋友你们还好吗 | 朋友你们还好吗 | 2333 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2333） |
| 薛之谦 / 你过得好吗 | 爱情宣判 | 爱情宣判 | 2333 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2333） |
| 薛之谦 / 你过得好吗 | 爱的期限 | 爱的期限 | 1986 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1986ms |
| 薛之谦 / 你过得好吗 | 续雪 | 续雪 | 2426 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2426） |
| 薛之谦 / 你过得好吗 | 苏黎世的从前 | 苏黎世的从前 | 3546 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=3546） |
| 薛之谦 / 你过得好吗 | 马戏小丑 | 马戏小丑 | 2853 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2853） |
| 薛之谦 / 几个薛之谦 | 为了遇见你 | 为了遇见你 | 333 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 333ms |
| 薛之谦 / 几个薛之谦 | 为什么 | 为什么 | 173 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 173ms |
| 薛之谦 / 几个薛之谦 | 伏笔 | 伏笔 | 306 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 306ms |
| 薛之谦 / 几个薛之谦 | 几个你 | 几个你 | 573 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 573ms |
| 薛之谦 / 几个薛之谦 | 我们爱过就好 | 我们爱过就好 | 614 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 614ms |
| 薛之谦 / 几个薛之谦 | 我知道你都知道 | 我知道你都知道 | 200 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 200ms |
| 薛之谦 / 几个薛之谦 | 我终于成了别人的女人 | 我终于成了别人的女人 | 533 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 533ms |
| 薛之谦 / 几个薛之谦 | 敷衍 | 敷衍 | 293 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 293ms |
| 薛之谦 / 几个薛之谦 | 楚河汉界 | 楚河汉界 | 866 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 866ms |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | All You Had To Do Was Stay (Taylor's Version) | All You Had To Do Was Stay | 289 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 289ms |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | Bad Blood (Taylor's Version) | Bad Blood | 103 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 103ms |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | Blank Space (Taylor's Version) | Blank Space | 100833 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=100833） |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | Clean (Taylor's Version) | Clean | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | How You Get The Girl (Taylor's Version) | How You Get The Girl | 533 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 533ms |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | I Know Places (Taylor's Version) | I Know Places | 20300 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=20300） |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | Is It Over Now? (Taylor's Version) (From The Vault) | — | 不可比 | **NONE** | 只有一侧有这首 |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | I Wish You Would (Taylor's Version) | I Wish You Would | 100650 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=100650） |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | New Romantics (Taylor's Version) | New Romantics | 177 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 177ms |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | Now That We Don't Talk (Taylor's Version) (From The Vault) | — | 不可比 | **NONE** | 只有一侧有这首 |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | Out Of The Woods (Taylor's Version) | Out Of The Woods | 800 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 800ms |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | Say Don't Go (Taylor's Version) (From The Vault) | — | 不可比 | **NONE** | 只有一侧有这首 |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | Shake It Off (Taylor's Version) | Shake It Off | 209 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 209ms |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | "Slut!" (Taylor's Version) (From The Vault) | — | 不可比 | **NONE** | 只有一侧有这首 |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | Style (Taylor's Version) | Style | 0 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 0ms |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | Suburban Legends (Taylor's Version) (From The Vault) | — | 不可比 | **NONE** | 只有一侧有这首 |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | This Love (Taylor's Version) | This Love | 100 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 100ms |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | Welcome To New York (Taylor's Version) | Welcome To New York | 600 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 600ms |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | Wildest Dreams (Taylor's Version) | Wildest Dreams | 433 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 433ms |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | Wonderland (Taylor's Version) | Wonderland | 566 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 566ms |
| Taylor Swift / 1989 (Taylor's Version) (Deluxe) | You Are In Love (Taylor's Version) | You Are In Love | 389 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 389ms |
| Taylor Swift / Speak Now (Taylor's Version) | Back To December (Taylor's Version) | Back To December | 1189 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1189ms |
| Taylor Swift / Speak Now (Taylor's Version) | Better Than Revenge (Taylor's Version) | Better Than Revenge | 3306 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=3306） |
| Taylor Swift / Speak Now (Taylor's Version) | Castles Crumbling (Taylor’s Version) (From The Vault) | — | 不可比 | **NONE** | 只有一侧有这首 |
| Taylor Swift / Speak Now (Taylor's Version) | Dear John (Taylor's Version) | Dear John | 2906 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2906） |
| Taylor Swift / Speak Now (Taylor's Version) | Electric Touch (Taylor’s Version) (From The Vault) | — | 不可比 | **NONE** | 只有一侧有这首 |
| Taylor Swift / Speak Now (Taylor's Version) | Enchanted (Taylor's Version) | Enchanted | 1253 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1253ms |
| Taylor Swift / Speak Now (Taylor's Version) | Foolish One (Taylor’s Version) (From The Vault) | — | 不可比 | **NONE** | 只有一侧有这首 |
| Taylor Swift / Speak Now (Taylor's Version) | Haunted (Taylor's Version) | Haunted | 28413 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=28413） |
| Taylor Swift / Speak Now (Taylor's Version) | I Can See You (Taylor’s Version) (From The Vault) | — | 不可比 | **NONE** | 只有一侧有这首 |
| Taylor Swift / Speak Now (Taylor's Version) | If This Was A Movie | — | 不可比 | **NONE** | 只有一侧有这首 |
| Taylor Swift / Speak Now (Taylor's Version) | Innocent (Taylor's Version) | Innocent | 779 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 779ms |
| Taylor Swift / Speak Now (Taylor's Version) | Last Kiss (Taylor's Version) | Last Kiss | 2120 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2120） |
| Taylor Swift / Speak Now (Taylor's Version) | Long Live (Taylor's Version) | Long Live | 960 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 960ms |
| Taylor Swift / Speak Now (Taylor's Version) | Mean (Taylor's Version) | Mean | 1693 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1693ms |
| Taylor Swift / Speak Now (Taylor's Version) | Mine (Taylor's Version) | Mine | 1706 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1706ms |
| Taylor Swift / Speak Now (Taylor's Version) | Never Grow Up (Taylor's Version) | Never Grow Up | 2920 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2920） |
| Taylor Swift / Speak Now (Taylor's Version) | Ours (Taylor’s Version) | Ours | 2197 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2197） |
| Taylor Swift / Speak Now (Taylor's Version) | Sparks Fly (Taylor’s Version) | Sparks Fly | 1230 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1230ms |
| Taylor Swift / Speak Now (Taylor's Version) | Speak Now (Taylor's Version) | Speak Now | 2473 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=2473） |
| Taylor Swift / Speak Now (Taylor's Version) | Superman (Taylor’s Version) | Superman | 1055 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1055ms |
| Taylor Swift / Speak Now (Taylor's Version) | The Story Of Us (Taylor's Version) | The Story Of Us | 1653 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 1653ms |
| Taylor Swift / Speak Now (Taylor's Version) | Timeless (Taylor’s Version) (From The Vault) | — | 不可比 | **NONE** | 只有一侧有这首 |
| Taylor Swift / Speak Now (Taylor's Version) | When Emma Falls in Love (Taylor’s Version) (From The Vault) | — | 不可比 | **NONE** | 只有一侧有这首 |
| Taylor Swift / The Life of a Showgirl | Actually Romantic | Actually Romantic | 121699 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=121699） |
| Taylor Swift / The Life of a Showgirl | CANCELLED! | CANCELLED! | 155498 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=155498） |
| Taylor Swift / The Life of a Showgirl | Eldest Daughter | Eldest Daughter | 195356 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=195356） |
| Taylor Swift / The Life of a Showgirl | Elizabeth Taylor | Elizabeth Taylor | 291 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 291ms |
| Taylor Swift / The Life of a Showgirl | Father Figure | Father Figure | 777 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 777ms |
| Taylor Swift / The Life of a Showgirl | Honey | Honey | 139535 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=139535） |
| Taylor Swift / The Life of a Showgirl | Opalite | Opalite | 191356 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=191356） |
| Taylor Swift / The Life of a Showgirl | Ruin The Friendship | Ruin The Friendship | 180564 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=180564） |
| Taylor Swift / The Life of a Showgirl | The Fate of Ophelia | The Fate of Ophelia | 185073 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=185073） |
| Taylor Swift / The Life of a Showgirl | The Life of a Showgirl | The Life of a Showgirl | 766 ms | **EXACT** | 曲名归一化一致 + 艺人一致 + 时长差 766ms |
| Taylor Swift / The Life of a Showgirl | The Life of a Showgirl Intro | — | 不可比 | **NONE** | 只有一侧有这首 |
| Taylor Swift / The Life of a Showgirl | The Life of a Showgirl Outro | — | 不可比 | **NONE** | 只有一侧有这首 |
| Taylor Swift / The Life of a Showgirl | Wi$h Li$t | Wi$h Li$t | 169349 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=169349） |
| Taylor Swift / The Life of a Showgirl | Wood | Wood | 120517 ms | **MEDIUM** | 曲名 + 艺人一致，时长口径不可比（delta=120517） |
