# EVIDENCE · v2.5.3

> 本文件是 v2.5.3 全部证据的**索引**。每条都指向可复现的命令或落盘文件。
> **凡是没验证到的，统一收在 §6，不做美化。**
>
> | | |
> |---|---|
> | 目标 tag | `v2.5.3-gpl` |
> | versionCode | **44**（三源交叉见 `version-check.txt`） |
> | 本版性质 | **结构加固**（P0 上帝类拆分 + P1 队列身份收敛），**不修任何用户可见的 bug** |
> | 任务书范围 | §3 P0 `Strings` 拆分 · §4 P1 队列去重改用 `TrackKey` |
> | **本次是否实机验证** | **否**（任务书 §5.2 明确不要求）—— 缺口见 §6 |

---

## 0. 一句话

`Strings` 的主构造参数从 **245（= 255 个 dex 槽用满、余量 0）降到 128（余量 121）**，
三个功能分组承接 120 条文案，**36 个消费文件、~290 个调用点一行未改**；
队列去重从裸 `song.id` 改用 `TrackKey`，14 处内联判重收敛成 `QueueKeys` 的纯函数。

`clean testDebugUnitTest lint assembleDebug assembleRelease` **全绿**
（83 类 / **1134** 单测 0 失败；lint **0 Error / 0 Fatal**）。

**本版没有修任何用户可见的问题** —— 探针证明队列跨源 id 撞号的实际发生率是
**结构性 0**，`Strings` 拆分是参数预算问题而不是功能问题。详见 §1 与 §3。

---

## 1. 探针（先于代码改动落盘）

| 文件 | 内容 |
|---|---|
| `PROBE-SUMMARY.md` | 三个必答问题：拆分选型 / 冲突发生率 / 改造影响面 |
| `probe-strings.md` + `probe-strings.py` + `probe-strings.raw.txt` | `Strings` 现状与拆分选型；脚本可重跑 |
| `probe-queue-dedup.md` + `probe-queue-dedup.py` + `probe-queue-dedup.raw.txt` | 队列去重 key、id 空间隔离的算术证明、1000 首 A/B 抽样 |
| `split-strings.py` | P0 的机械变换脚本（对 v2.5.2 源码可重放出逐字节相同的结果） |
| `tools/StringsSnapshotDumpTest.kt` | 生成黄金快照的一次性工具（**已从 test 源集移出**，见 §5 说明） |

复现：

```bash
cd <repo>/Ncrust
python3 docs/verification/v2.5.3/probe-strings.py      | tee /tmp/a.txt
python3 docs/verification/v2.5.3/probe-queue-dedup.py  | tee /tmp/b.txt
```

### 1.1 探针的关键数字（可直接引用）

| 项 | 值 |
|---|---|
| `Strings` 主构造参数（改造前） | **245**（两种解析法互证） |
| dex 槽位（改造前） | `1 + 245 + 8 mask + 1 marker = **255**` / 上限 255 ⇒ **余量 0** |
| 既有嵌套组 | 7 个，合计 136 参数，最大 `SourceStrings`(57) |
| 类体既有转发属性 | 111 条（「搬家不改调用点」已被验证过四遍） |
| 消费 `Strings` 的文件 | 36 个，全在 `ui/` + `MainActivity` |
| `UserScreen.kt` 的 key 数 | **78**（第二名 29 的 2.7 倍） |
| 8 语言里取值逐字相同的 key | **1 条**（`uidLabel`）⇒ 按语言拆无效 |
| 队列去重落点（改造前） | **14 处**，全是 `MainActivity` 里的内联表达式 |
| 队列去重落点（**改造后，探针实测**） | **0 处**（探针排除注释行后重跑） |
| `SongItem.dedupeKey` 生产调用点 | **0 处**（定义在、守卫在、没接线）—— v2.5.3 把它的**定义**并到与生产路径同一个落点上，但它本身仍未被直接调用 |
| QQ id 的 bit62 标志位 | `1L shl 62 = 4611686018427387904` |
| ncm songId 实测量级 | `1e6 ~ 3e9`（< `2^40`） |
| **跨源裸 id 冲突（1000 首 A/B 抽样）** | **0**（结构性 0，见 §3） |
| 用户报障条数 | **0** |

---

## 2. P0：`Strings` 拆分（245 → 128）

### 2.1 改动落点

| 文件 | 改动 |
|---|---|
| `app/src/main/java/.../ui/i18n/Strings.kt` | 主构造器 `245 → 128`；新增类 KDoc（参数预算沿革）；新增 3 个组参数；新增 120 条转发属性；追加 3 个 `data class` |
| `app/src/main/java/.../ui/i18n/{zh_CN,zh_TW,en,jp_JP,jp_MY,ko_NK,de_DE,ru_RU}.kt` | 各搬 120 条实参进三个新组（**原样透传，含多行 lambda**） |

**没有改任何消费文件** —— 这是本版「API 兼容」的硬证据：
`git diff --stat` 里 `ui/screen/**`、`ui/player/**`、`ui/components/**`、`MainActivity.kt` **零改动**。

### 2.2 新增守卫

| 测试 | 例数 | 覆盖 |
|---|---|---|
| `StringsMigrationTest` | **8** | 旧 key 路径逐值不变 / 新旧路径一一对应 / 三组被真正填充 / 默认值回落 / 8 语言路径集合一致 / 跨语言该不同的确实不同 / 跨语言全同的集合只增不减且新增可解释 / 默认值声明搬进分组 |
| `StringsConstructorBudgetTest` | **9**（原 6） | 家族逐类可加载（`ClassFormatError` 防线）/ 主构造器 ≤ **150**（原 245）/ 精确值 = 128 / **每个嵌套组各自受监控**（≤120 + 预警线 80）/ 三新组规模钉住 / 转发属性可达性 / 文案非空与不撞词 |

阈值从 245 降到 150 的理由：245 是**天花板**不是配额，
「刚好不溢出」在实践中等于「下一个加文案的人必然踩坑」——
`240 → 261(崩) → 244 → 245 → 244 → 245` 这条轨迹就是证据。

### 2.3 「搬家 ≠ 改文案」的机器证明

黄金快照：`app/src/test/resources/i18n/strings-snapshot-v2.5.2.json`
（**397 KB**，8 语言 × 494 条路径 = **3952 个取值**，含 47 条 lambda 的**实际调用结果**）。

`StringsMigrationTest` 的第一条用例逐值比对 3952 个路径；
比对数量断言 `> 3000`，防止「快照没载入却假装通过」。

新旧路径映射表：`app/src/test/resources/i18n/strings-migration-map-v2.5.3.tsv`（120 条），
由 `split-strings.py` 的分组表生成 —— 分组表是**唯一真源**，映射表不是手写的。

### 2.4 验收对照

| 验收项（任务书 §3.3） | 结果 |
|---|---|
| 主构造器参数 < 150 | ✅ **128** |
| 现有调用点不破，或改动机械可批量 | ✅ **零改动**（转发属性） |
| 迁移单测覆盖旧 key → 新路径 | ✅ `StringsMigrationTest` |
| 参数数量监控落地 | ✅ 阈值 150 + 逐组监控 + 精确值断言 |
| 单测全绿 / lint 通过 / 构建成功 | ✅ 见 §5 |

---

## 3. P1：队列去重改用 `TrackKey`

### 3.1 冲突发生率的**算术证明**（主要证据）

```
QQ_ID_FLAG = 1L shl 62 = 4611686018427387904
ncm songId 实测量级   : 1e6 ~ 3e9（远小于 2^40 = 1099511627776）
QQ 合成 id 的最小值      : 4611686018427387905（bit62 恒置位）
两个区间是否相交         : 否
```

⇒ 只要 QQ 曲目的 id 都经 `SourceIds.qqId()` 产出，**「ncm id == QQ id」
在 64 位整数上不可能成立**，与队列长度、抽样规模都无关。

### 3.2 A/B 抽样（铁律 6，辅助证据）

`probe-queue-dedup.py` §4：1000 首双源配对，取值形状取自仓库里的**真实观测**
（ncm `5257138`/`287035`/`1959528822`/`1295411603`/`102792543`；
QQ songmid `0039MnYb0qxYhV` 等）。

| | 裸 `song.id` | `TrackKey` |
|---|---|---|
| 跨源数值冲突 | **0** | **0** |
| 同源重复 | 0 | 0 |

同源唯一性 + 兜底散列：抽样 20000 个 songmid → **0** 碰撞
（理论概率 `n²/2^63 ≈ 4.3e-11`）。

> **证据强度的自我限定**：抽样用的是「真实形状 + 合成样本」，**不是线上真实曲库**
> （那需要登录态与联网）。结论的强度来自 §3.1 的算术，抽样只是它的一个示例 ——
> 本版**不把抽样当成主要证据**。

### 3.3 改动落点

| 文件 | 改动 |
|---|---|
| `player/QueueKeys.kt` | **新增**：队列身份运算的唯一落点（判重 / 重定位 / 批量剔除 / 重建 / 重复检测 / 诊断） |
| `source/TrackKey.kt` | **新增** `TrackKey.ofSong`：`SongItem` → 身份的唯一入口（`source` 为空时按 bit62 推断） |
| `source/SongSourceExt.kt` | `SongItem.dedupeKey` 从「另算一份」改成「就是 `TrackKey` 的那一份」；新增 `SongItem.trackKeyOf()` |
| `player/QueueInsert.kt` | `plan` / `shuffleAfterInsert` 入参 `List<Long>` → `List<TrackKey>`；算法一行未动 |
| `MainActivity.kt` | 5 处队列写入 + 1 处当前歌同步改走 `QueueKeys`；14 处内联裸 id 判重全部消失 |

### 3.4 新增/改写守卫

| 测试 | 例数 | 覆盖 |
|---|---|---|
| `QueueKeysTest` | **24** | 同源去重 / 幂等 / **跨源同 id 绝不当成同一首**（核心验收项）/ 重定位不沿用旧下标 / 批量剔除 / 重建失败返回 null / 重复检测 / **历史数据 4 例**（老 JSON、新 JSON、混合、新旧判重键一致）/ **待播槽位行为不变** 2 例 / **随机模式行为不变** 2 例 / **反射断言签名不许退回裸 `Long`** |
| `QueueInsertTest` | 23（语义未变） | 入参类型换成 `TrackKey`，用例断言逐条保留 |
| `TrackKeyTest` | 16（+2） | `ofSong` 的 bit62 回落语义 |
| `SongSourceExtTest` | 8（既有） | 跨源判重键不同 / 新旧条目身份一致 |

### 3.5 验收对照

| 验收项（任务书 §4.3） | 结果 |
|---|---|
| 队列去重改用 `TrackKey` | ✅ 唯一入口 `QueueKeys.keyOf`；14 处内联裸 id 判重全部消失 |
| 历史数据迁移有单测 | ✅ **结论是「无需迁移」**，并用 4 条用例**证明**（不是断言） |
| 待播槽位行为不变 | ✅ `PreloadSlot` 一行未改 + 2 条一致性断言 |
| 随机模式行为不变 | ✅ 编排是下标、算法未动 + 2 条跨源排列合法性用例 |
| 单测全绿 | ✅ 见 §5 |

---

## 4. 对既有文档的**更正**与「探针 vs 任务书」的偏差

### 4.1 更正 v2.5.0 / v2.5.1 遗留清单

那两版写着：「队列去重用裸 `song.id`，跨源**裸 id 撞号**（ncm 某首 vs QQ 某首）
时会把其中一首当重复。」

**这句话的前提被本版证伪** —— 它需要两个平台的 id 落在同一数值空间，
而 QQ 的 id 一律带 bit62 标志位（§3.1）。
按 `AGENTS.md` v2.5.1 规则 2（探针结论与既有说法冲突时以探针为准），本版更正它，
并把 TrackKey 改造**重新定性为结构加固，而不是 bug 修复**。

### 4.2 三条偏差（逐条记录，不默默照抄）

| # | 任务书的说法 | 探针实测 | 处置 |
|---|---|---|---|
| 1 | §2.2「双音源下 song.id 冲突**实际发生率**」（措辞暗示可能存在） | **结构性 0**，无用户报障 | 照做改造，但重新定性；**不声称修了 bug** |
| 2 | §4.2「队列快照、待播槽位**如有持久化**，加迁移逻辑」 | 持久化存在，但**没有旧 key 形状**（落的是全量 `SongItem`） | **不写迁移逻辑**，改为写「无需迁移」的证明性单测 |
| 3 | §3.1 建议方向含「方案 B：按语言包拆」 | 8 语言只有 **1 条**文案逐字相同 ⇒ 方案 B **算术上无效** | 选方案 A，并在 `probe-strings.md` §4 记录否决理由 |

---

## 5. 测试 / 静态 / 构建证据

### 5.1 命令与结果

```bash
cd <repo>/Ncrust
export ANDROID_HOME=/home/duanjb666/Android/sdk
./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease
# BUILD SUCCESSFUL in 3m 10s
# 421 actionable tasks: 133 executed, 288 up-to-date
```

| 项 | 结果 | 证据文件 |
|---|---|---|
| JVM 单测 | **83 类 / 1134 例 / 0 失败 / 0 错误** | `build-summary.txt`；原始 XML 在 `app/build/test-results/testDebugUnitTest/` |
| 单测增量 | v2.5.2 = 81 类 / 1100 例 ⇒ **+2 类 / +34 例** | 同上 |
| lint | **0 Error / 0 Fatal**（10 条 Warning/Information，均既有） | `app/build/reports/lint-results-debug.{txt,html,xml}` |
| `assembleDebug` | ✅ `app-debug.apk`（30,762,350 B） | `build-summary.txt` |
| `assembleRelease` | ✅ `app-release.apk`（10,042,824 B，R8 全量优化 + 签名） | 同上 |
| versionCode 三源交叉 | 三源一致 = 43 ⇒ 本版 **44** | `version-check.txt` + `next-version.txt` |

### 5.2 关键测试类的例数

```
StringsMigrationTest             tests=   8 failures=0
StringsConstructorBudgetTest     tests=   9 failures=0
QueueKeysTest                    tests=  24 failures=0
QueueInsertTest                  tests=  23 failures=0
TrackKeyTest                     tests=  16 failures=0
SongSourceExtTest                tests=   8 failures=0
PreloadSlotTest                  tests=  19 failures=0   ← 未改动，证明槽位行为未回归
ShuffleRoundTest                 tests=   9 failures=0   ← 未改动，证明随机模式未回归
```

### 5.3 发布产物的可复现性（**实测到的意外，如实记录**）

同一个提交跑两次 `assembleRelease`，APK 的 **sha256 不同**。
逐 zip 条目比对后定位：**470 / 471 个条目的 CRC 完全相同**，
唯一差异是 `META-INF/version-control-info.textproto` —— AGP 写进 APK 的
**构建时 VCS 状态**（提交号）。两次构建之间 HEAD 变了一次（一个只改 `docs/` 的提交）。

⇒ **不要拿 sha256 当「产物 == 源码」的判据**。本版用两条独立证据满足该纪律：
① `git diff --stat <build-commit> HEAD -- app/` 为空；
② 两次 APK 的内容条目 CRC 全部相同。
细节与复现脚本：`apk-reproducibility.md`。

本版**没有**为了「让 sha256 稳定」而关掉 VCS 戳 ——
「这个 APK 是哪个提交构建的」这条溯源信息在排查线上问题时比一个稳定哈希更值钱。

### 5.4 一条工具纪律（本版踩到并记录）

`tools/StringsSnapshotDumpTest.kt` 生成黄金快照后**必须从 test 源集移出** ——
留着它，任何一次 `./gradlew test` 都会**覆盖黄金快照**，
于是 `StringsMigrationTest` 会拿「刚生成的快照」跟自己比，永远绿。
这正是「守卫测试的输入不许由被测对象自己产生」的实例。移出后归档在 `docs/verification/v2.5.3/tools/`。

---

## 6. 未验证缺口清单（**如实列出，不做美化**）

> **本次任务书 §5.2 明确不要求实机验证。** 因此 §5 的全部证据都是
> **单测 / 静态 / 构建**级别。**不允许用单测冒充真机证据** ——
> 下列每一项都是单测**原理上无法证明**的东西。

### 6.1 哪些是**单测覆盖**的（可以放心）

| 结论 | 覆盖方式 |
|---|---|
| `Strings` 拆分后 8 语言 3952 个取值逐值不变 | `StringsMigrationTest`（反射 + 黄金快照，含 lambda 实际调用） |
| 旧 key 路径 `strings.xxx` 仍可达 | 同上（快照里的扁平路径逐条解析） |
| 新 key 路径 `strings.settings.xxx` 取值与扁平一致 | 同上（120 条映射表逐条双向比对） |
| 默认值回落（`aboutDesignSystemLabel`） | 同上（5 个语言回落到构造器默认值） |
| 8 语言路径集合一致、该不同的确实不同 | 同上 |
| 主构造器参数 128 / 每组规模 / 类可加载 | `StringsConstructorBudgetTest`（显式 `Class.forName`） |
| 同源去重、跨源不去重、幂等 | `QueueKeysTest` |
| 去重后重定位当前歌不沿用旧下标 | `QueueKeysTest` |
| 历史队列 JSON（老/新/混合）身份正确 | `QueueKeysTest`（Gson 真反序列化） |
| 待播槽位身份与队列身份一致 | `QueueKeysTest` + 未改动的 `PreloadSlotTest`(19) |
| 随机模式是下标编排、跨源插入后排列合法 | `QueueKeysTest` + 未改动的 `ShuffleRoundTest`(9) |
| `QueueInsert.plan` 签名不许退回裸 `Long` | `QueueKeysTest` 的反射断言 |

### 6.2 **未**真机验证的（本轮明确未做）

| # | 缺口 | 为什么单测证明不了 | 建议怎么补 |
|---|---|---|---|
| 1 | **`Strings` 128 个参数在真机上不触发 `ClassFormatError`** | 单测只能证明 **JVM** 能加载；上限是 **dex/ART** 层面的。本版是**从 255/255 槽降到 134/255**，方向上是远离上限，但「没溢出」的最终判据仍是真机冷启动一次 | 装 release 包冷启一次，看有无 `VerifyError` / `ClassFormatError` |
| 2 | 转发属性的**运行期开销** | 111 + 120 = 231 条 getter 的理论开销是零（会被内联），但**没有做 release 包帧时间 A/B** | macrobenchmark `ExpandPlayerBenchmark` / `HomeScrollBenchmark`（**必须 release 包**，v2.5.1 规则 1） |
| 3 | `QueueKeys.keysOf(queue)` 的 **O(n) 开销** | 队列上限未见约束；1000 首时每次写操作多一次 1000 元素 map。量级上远小于同一次操作里已有的 `saveQueue` Gson 序列化，但**未测量** | 造 1000 首队列，测写操作耗时；或直接看帧时间 |
| 4 | 跨源队列的**实际观感** | 单测只证明集合运算对；「用户把 QQ 的和 ncm 的同一首歌都加进队列，队列面板显示对不对」只有真机能看 | 真机双源登录，构造 [ncm A, QQ A'] 队列，检查面板/待播/切歌 |
| 5 | **升级不丢队列**（老数据恢复路径） | 单测证明了 Gson 反序列化形状对；但 `PlaybackStateManager` 走 SharedPreferences，**真实老包的 `queue` JSON 没有实测读回** | 装 v2.5.2 播一首、留队列 → 覆盖安装 v2.5.3 → 冷启看队列是否原样 |
| 6 | 拆分后的**文案渲染** | 逐值比对只证明「字符串没变」，不证明「它在 UI 上出现的位置对」 | 8 语言各切一遍，抽查设置页 / 关于页 / 播放器 |
| 7 | **lint 基线未覆盖的新问题** | lint 是静态检查，`abortOnError = true` 已生效，但它不覆盖运行期 | —（无需补，列出以示边界） |

### 6.3 需要**后续真机补验**但本版已知有风险的具体项

| # | 项 | 说明 |
|---|---|---|
| 1 | **搜索历史的音源丢失** | 从搜索历史点播 QQ 曲目时它被当成 ncm（`HistoryItem` 只存 id）。**本版未修**，但通过 `TrackKey.ofSong` 避开了它在队列身份上的后果。要真机确认「点搜索历史里的 QQ 歌 → 取链会不会 404 跳歌」 |
| 2 | QQ 无 songid 的兜底散列路径 | 探针只证明了碰撞概率极低，**没有统计它在真实 QQ 响应里出现的频率** |
| 3 | 14 处去重落点的枚举完整性 | 探针用正则清单匹配，形态不同的写法可能漏网。类型系统已挡住主要路径，但真机跑一遍「加歌 / 批量加 / INFINITY 续播」更稳 |

---

## 7. 复现清单（按顺序）

```bash
cd <repo>/Ncrust
git checkout v2.5.3-gpl

# 1. 探针（改代码之前就该跑）
python3 docs/verification/v2.5.3/probe-strings.py
python3 docs/verification/v2.5.3/probe-queue-dedup.py

# 2. 版本决策（必须带 fetch）
bash ../tools/next-version.sh

# 3. 全量验证
export ANDROID_HOME=/home/duanjb666/Android/sdk
./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease

# 4. 拆分可重放（会改写工作区，仅在干净副本上跑）
#    python3 docs/verification/v2.5.3/split-strings.py --apply

# 5. tag 自证
git show v2.5.3-gpl:app/build.gradle.kts | grep -E 'version(Code|Name)'
```

---

## 8. 本版**没有**碰的东西（对照任务书「不在范围」）

- 华为控制中心卡片问题（**未碰**，一行都没改）
- 页面转场 / 动效调整（**未碰**）
- 灰度降级 / 三模式 / 第二台设备帧数据（**未做**）
- Kanesumi fork（**未碰**）
- 远程歌单写操作（**未碰**）
- 已发布的 tag：**一个都没动**（v2.5.0-gpl / v2.5.1-gpl / v2.5.2-gpl 保持原位）
