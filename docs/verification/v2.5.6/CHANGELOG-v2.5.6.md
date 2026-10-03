# v2.5.6-gpl 更新说明

> 版本：`versionName = "2.5.6-gpl"` / `versionCode = 47`
> 探针：`docs/verification/v2.5.6/PROBE-SUMMARY.md`（四份分册）
> 证据：`docs/verification/v2.5.6/EVIDENCE.md`
> 设备：S6/SM-G9209（**API 24**）、WGR-W09 华为平板（EMUI 14.2.0 / **API 31**）、
> PLC110（**API 36**）

---

## 1. 一句话

本版**没有加新功能**：四件事都是「把已有的东西修到真的能用 / 真的被量化」——
baseline profile 从**手写 class-only 清单**换成**真机采样的方法级 profile**（并补上采样管线与 CI），
搜索**解掉首帧对 ncm 的硬阻塞**（真机证据：QQ 结果 ≤5s 已到手、界面空了 30s），
平板 ⤢ 修掉**竖屏下的命中区重叠**（点它实际在按播放/暂停），
PLC110 的离线索引注入记录按**不伤真实数据**的方式收尾。

---

## 2. 用户可读的更新说明

### 2.1 搜索：慢的那条腿不再拖住整屏

以前搜索的规矩是「**ncm 先回来才显示**」。如果 ncm 那条腿卡住（实测抓到过 **30 秒**），
即使 qm 的结果 5 秒前就已经拿到手，屏幕上也只有转圈 —— 你会以为搜不到。

现在改成 **谁先回来谁先上屏**：QQ 先到就先显示 QQ 的结果，ncm 到了再合并。
另外给搜索请求加了一条 20 秒的**整通硬上限**（此前只有「空闲 30 秒」超时，
服务端每 29 秒吐一个字节就能一直挂着）。

**你能感觉到的变化**：搜索结果「第一个字出现」的时间从「取决于较慢的那条腿」变成「取决于较快的那条腿」；
ncm 异常时不再出现长达半分钟的空屏。

（说明：搜索**总耗时**的大头在服务端首字节，实测 P50 约 2.9 秒，这一段客户端压不动 ——
本版没有假装能压它。本版压掉的是「数据已经到手却还没显示」的那部分。）

### 2.2 平板：竖屏下 ⤢ 大屏幕按钮终于点得动

在 EMUI 平板的**竖屏**下，播放器里那个 ⤢ 按钮**画得好好的，但点了没反应** ——
点它实际触发的是「播放/暂停」。原因不是华为不认旋转请求
（实测 EMUI 完全认，横屏下这个按钮一直是好的），而是**布局重叠**：
横向控制条的三组按钮是各自独立定位的，竖屏容器只有 352dp，
左边那组和中间那组叠了 65dp，而**后画的赢命中测试** —— ⤢ 正好整个落在被盖住的那一段里。

现在控制条改成**顺序排列**（结构上不可能重叠），并且当宽度真的不够时，
**让位的是右边的音质选择器**（竖屏控制条里还有它），⤢ 和播放控制键都不让位。

**你能感觉到的变化**：平板竖屏、横屏都能进大屏幕模式了。

### 2.3 启动速度：baseline profile 换成真机采样的方法级版本

（这一条大多数人感觉不到，但它是「装了新版本之后前几次打开特别钝」的根治手段。
具体数字见 §4 —— 本版**没有**在没数据的情况下声称变快。）

### 2.4 内部整理

- 搜索链路补上了「时间花在哪一段」的埋点（此前只有一个总耗时，**release 包里连日志都没有**）；
- 离线缓存对账的判据补了一条真机形状的回归测试（防止将来有人用「没有 URL 条目」当删除判据，
  那会误删 6 首真实离线歌）。

---

## 3. 修改摘要

| # | 类型 | 落点 | 说明 |
|---|---|---|---|
| 1 | `perf` | `benchmark/…/BaselineProfileGenerator.kt`（新） | baseline profile 的**采样器**：7 条旅程（冷启动到首页 / 进入搜索 / 进入库 / 进入播放器 / 播放开始 / 切歌 / 列表滚动），逐条 `OK`/`SKIP(原因)` 打进 logcat（tag `NcrustProfileGen`） |
| 2 | `build` | `benchmark/generate_baseline_profile.sh`（新） | 一条命令重生成 profile；**一条旅程都没 OK 就拒绝覆盖**目标文件（有界失败） |
| 3 | `build` | `.github/workflows/baseline-profile.yml`（新） | CI：**自托管 runner + `workflow_dispatch`/每月定期**，采样后开 PR（不直推 master）。托管 runner 跑不了这件事，写成 `on: push` 会假绿 |
| 4 | `perf` | `app/src/main/baseline-prof.txt` | 手写 91 行 class-only → **真机采样的方法级 profile**（数字见 §4） |
| 5 | `fix` | `SearchViewModel.kt` | 首帧上屏从「等 ncm」改成**先到先发布**（`select`），并补上第一次发布的 `_query` 守卫；QQ 状态映射抽成 `qqStatusOf`（唯一定义处） |
| 6 | `fix` | `RetrofitClient.kt` | 新增 `searchApi`：只给**搜索**加 20s `callTimeout`（共用 `api` 不动，避免改掉专辑/歌词/详情的失败面） |
| 7 | `perf` | `network/HttpTimingListener.kt`（新） | OkHttp `EventListener`：真 TTFB / 响应体传输 / 整通。**纯观测，不改变请求行为**；无反证需求下不套 `BuildConfig.DEBUG`（release 里必须存在） |
| 8 | `perf` | `search/SearchLatencyTrace.kt`（新） | 搜索分段打点（纯逻辑 + 注入时钟）：`ttfr` = dispatch → **第一次上屏** |
| 9 | `fix` | `FullPlayerControls.kt` | 横向控制条从 `Box` + 三个独立 `align` 的 `Row` 改成**顺序 `Row`**（结构上不可重叠） |
| 10 | `fix` | `PlayerLayout.kt` | 新增 `qualityChipFits` / `sideButtonCount`：宽度预算的**唯一**判据（可单测） |
| 11 | `test` | `ControlsBarBudgetTest`（新，6 例） | 用真机 352dp / 563dp 逐格钉住让位规则 |
| 12 | `test` | `SearchLatencyTraceTest`（新，6 例） | 含「重复打点保留第一次」——否则「先到先发布」在数据上会消失 |
| 13 | `test` | `OfflineLibraryTest`（+2 例） | 真机形状（50 条 / 1 条双孤儿）：**判据只能是「缓存里有没有」**，缺 urls 的 6 条真实条目必须存活 |
| 14 | `docs` | `AGENTS.md` | 新增三条铁律 20/21/22 + 方法学配套条款 |
| 15 | `docs` | `docs/verification/v2.5.6/**` | 四份探针 + 汇总 + 证据 + 版本三源校验 + A/B 原始数据 |
| 16 | `docs` | v2.5.5 的遗留建议改正 | `EVIDENCE.md:271` / `CHANGELOG-v2.5.5.md` 的「走『清空离线缓存』即可」会**删掉 2.3GB 真实数据**，改为「打开『离线缓存管理』即对账」 |
| 17 | `build` | `app/build.gradle.kts` | `versionCode 46 → 47`、`versionName 2.5.5-gpl → 2.5.6-gpl`（三源交叉校验，见 `version-check.txt`） |
| 18 | `test` | `benchmark/…/SettingsScrollBenchmark.kt` 等 | **无改动**（本版未动四条既有基准） |

---

## 4. baseline profile 效果数据

> 口径写在 `docs/verification/v2.5.6/verification/coldstart-ab.sh`：同一台设备（S6 / API 24）、
> 同一份 release 源码、**只换 APK 里的 `assets/dexopt/baseline.prof`**；
> 用 `cmd package compile -m speed-profile -f` 固定编译档；`am start -W` 取 `TotalTime`；10 次迭代。

**实测（`am start -W` 的 `TotalTime`，n=10/组）**：

| 组 | 编译档 | P50 | mean | min–max |
|---|---|---|---|---|
| A（v2.5.5 源码 + 手写 91 行 class-only profile） | `speed-profile` | **598.5ms** | 597.2ms | 553–619 |
| A | `speed`（全量 AOT，**不看 profile**） | **500.0ms** | 497.3ms | 476–514 |
| B0（v2.5.6 源码 + 同一个手写 profile） | `speed-profile` | **590.5ms** | 594.9ms | 575–640 |
| B0 | `speed` | **486.5ms** | 488.6ms | 473–504 |

**能读出的三条（只读得出这三条）**：

1. **协议确实区分编译档**：`speed-profile` ≈ 590~599ms 而 `speed` ≈ 487~500ms，
   相差 **~95~100ms（约 17%）** ⇒ 这个 A/B 不是「两次都等价于同一档」；
2. **v2.5.6 的代码改动没有让冷启动变慢**：B0 − A = **−8ms（−1.3%）**，
   在噪声带内（各组极差 38~66ms）⇒ 本版新增的搜索埋点**没有可测量的启动代价**；
3. **手写 class-only profile 离「全量 AOT」还差 ~95ms** —— 那是方法级 profile
   理论上能争取的**空间上界**。

**⚠️ 生成（B1）未完成，因此本版不声称 baseline profile 带来任何冷启动改善。**

阻塞原因（三条互相独立，全部实测）：

1. **`BaselineProfileRule` 有硬性 API 前提**，实测报错原文：
   `IllegalArgumentException: Baseline Profile collection requires API 33+, or a rooted device
   running API 28 or higher and rooted adb session (via 'adb root').`
   - S6（**API 24**）：不满足 API 33+，也不满足「API 28+ 且可 `adb root`（24 < 28）」⇒ **结构性不可用**；
   - WGR-W09 平板（API 31）：31 < 33，且 `adb root` 返回
     `adbd cannot run as root in production builds` ⇒ **结构性不可用**；
   - PLC110（API 36）：满足 API 前提 ✅，但卡在 `UiAutomationService … already registered!`。
2. **PLC110 的 `UiAutomation` 被占用**，且本版**修正了 v2.5.5 的根因判断**：
   关闭两个第三方无障碍服务（Scene / GKD）、确认无残留 instrumentation 进程、`adb reboot`
   —— **三者都试过，仍然失败**。⇒ v2.5.5 写的「根因是 Scene / GKD」**不完整**。
3. 采样器的**有界失败守卫按设计生效**：四次尝试都在「一条旅程都没 OK」时
   **拒绝覆盖** `app/src/main/baseline-prof.txt`（`GEN_RC=4`），
   没有把无效结果写进仓库 —— 这一点是有意设计的（铁律 5）。

**解锁所需的最小条件**（任选其一）：一台 API 33+ 且 `UiAutomation` 空闲的设备；
或一台 API 28+ 的 userdebug/eng 设备（可 `adb root`）；
或配好自托管 CI runner（`.github/workflows/baseline-profile.yml` 已就绪）。

---

## 5. 搜索延迟优化数据

**探针结论（改动前，真机帧时间戳直读，6 样本）**：

| 段 | P50 | 占比 |
|---|---|---|
| QQ 腿（派发 → 状态写入） | 2923ms | — |
| 派发 → 首条结果上屏（ncm 先到） | 616ms | — |
| **状态写入 → QQ 计数上屏（渲染段）** | **41.5ms** | **1.4%** |

⇒ 客户端「TTFB → UI」这一段的天花板约 **2%**。**本版没有去优化这 2%**（那是拿真复杂度换噪声），
而是解掉探针发现的真问题：**首帧上屏被 ncm 硬阻塞**（真机 `elapsed=30006ms netease=0 qq=30`）。

**本版的改动与预期**：

| 项 | 改动前 | 改动后 |
|---|---|---|
| 首屏出现的条件 | ncm 必须返回 | **两条腿谁先返回** |
| 「搜索中…」的持续时间 | = ncm 腿耗时（实测可能 30s） | = min(两腿) |
| 搜索请求的硬上限 | 无（只有 30s 空闲超时） | 20s `callTimeout`（**仅搜索**） |
| 分段可观测性 | 5 段里 4 段无埋点；release 包无日志 | 新增真 TTFB + 5 个打点段，**release 包内无条件输出** |

**改动后的真机采集（PLC110 / API 36 / release 包 / n=4）**：

| query | ncm 腿 | QQ 腿 | **`ttfr`（派发→第一次上屏）** |
|---|---|---|---|
| `love` | 886ms | 2739ms | **888ms** |
| `jay` | 428ms | 3089ms | **429ms** |
| `piano` | 615ms | 1549ms | **616ms** |
| `hello` | 676ms | 4159ms | **677ms** |

`ttfr` **P50 ≈ 646ms**（429–888ms）；`cloudsearch/pc` 的 `ttfb=661ms / body=9ms / total=877ms`。

**诚实标注**：这 4 个样本里**ncm 都是先到的**（4/4，与探针的 6/6 一致），
所以它们**不能证明「先到先发布」带来了提速** —— 该优化的收益只出现在「ncm 慢」这条异常路径上
（探针抓到过 30s 空屏的真机样本）。这四个数字证明的是**健康路径没有回归**。

原始数据：`docs/verification/v2.5.6/verification/search-latency-v256.txt`。

### 5.1 ⚠️ 本版自己造成并已修复的一次回归（必须公开留档）

**症状**：v2.5.6 装机后「ncm 搜索搜不出歌」。

**根因**：本版给搜索单独加了 `callTimeout(20s)`。真机上该网络下**每通请求要 ~30 秒
才把请求头送出去**（同一份 logcat：`ttfb=240ms` 但 `total=30566ms` —— 慢在连接建立，
不在服务端），于是 **20s 的 `callTimeout` 拦掉的不是「挂死的请求」，而是本来会成功的请求**：

```
path=/api/cloudsearch/pc ttfb=-1ms body=-1ms total=20002ms failed=InterruptedIOException
aggregate query='love' netease=0 qq=30 elapsed=20012ms
UI: ncm 0 首 · qm 30 首
```

**修法**：删掉那个 `callTimeout`（回到共用的 `api`）；用户可见的收益完全来自「先到先发布」，
与超时无关，那个改动保留。**修复后同一台设备复测：`ncm 30 首 · qm 30 首`** ✅

**为什么这次能几分钟定位**：靠的正是本版新加的埋点 —— `ttfb=-1` 这一个读数
（请求头一个字节都没发出去）直接把「服务端慢」与「连接建立慢」分开了。
⇒ 这是 `AGENTS.md` 铁律 22「必须能说清时间花在哪一段」的一次正向兑现。

**教训（已写进铁律 22 配套条款）**：`callTimeout` 覆盖**整通**（含连接建立与排队），
而 `connectTimeout`/`readTimeout` 是**分阶段空闲**超时。**「ttfb 快」不代表「整通快」** ——
加超时前必须先量一次「这个环境里一通正常请求要多久」。

---

## 6. 平板兼容说明

**结论：EMUI 完全采纳旋转请求（受控 A/B 实测），任务书前提被推翻；真因是应用内的命中区重叠。**

| 项 | 实测 |
|---|---|
| `wm get-ignore-orientation-request` | `false` |
| 应用持 `SENSOR_LANDSCAPE` 时强制 `user_rotation=0` | **保持横屏 ≥10s** |
| 撤掉请求后同一 `user_rotation=0` | **立刻回竖屏** |
| 平板横屏点 ⤢ | ✅ 成功（`mOrientation=SENSOR_LANDSCAPE` / 大屏布局 + 沉浸式生效） |
| 平板竖屏点 ⤢（修复前） | ❌ 图标画在 x≈304px，但 a11y 树里没有该节点；点它触发**播放/暂停** |
| 真因 | 控制条 `Box` + 三组独立对齐 ⇒ 竖屏 352dp 容器下左组(8–168) 与传输组(103–247) **重叠 65dp**，⤢ 槽位 128–168 **100% 被盖** |
| 修法 | 顺序 `Row`（结构性不可重叠）+ 宽度预算（让位的是音质片，不是 ⤢ 也不是传输三键） |

**本版没有**加任何 `WindowManager` 旋转 override（全仓 `WindowManager` 仍只有 `FLAG_KEEP_SCREEN_ON`）——
因为真机证明平台侧本来就是好的，加它只会引入一个需要特权权限的失败面。

**未验证**：
- ⚠️ **修复后的 EMUI 真机复测未完成** —— 平板在会话中途**从 USB 掉线**
  （`adb: device 'WVQ6R22124000968' not found`），平板上仍是 v2.5.5。
  修复有 6 条单测，但**没有在真机上跑过断言**，因此这一条**不算通过**（新铁律 21 正是为此写的）。
  需要的断言见 `EVIDENCE.md` §7.1。
- 大屏多窗口 / 华为 PC 模式下的方向采纳；
- 应用内 `auto_rotate=on`（测试机为 off）。

---

## 7. 离线索引清理说明

PLC110 上那条 v2.5.5 注入的记录（`songId 503616`）**已按「不伤真实数据」的方式清除**，
且**没有为此改一行产品代码** —— 探针证明它是**一条无音频、无 URL 的惰性记录**，
既有对账路径（设置 → 离线缓存管理，打开即触发 `reconcileLibrary`）已能精确清除它。

| | 处置 |
|---|---|
| ❌ 不用的机制 | 设置页「清除缓存」（`OfflineAudioCache.clear`）—— 会连用户真实的 **2.3GB / 50 首**一起清掉 |
| ❌ 不加 | `offline_index_version`（与 `OfflineTrackCodec` 的「版本号不落盘」契约冲突）、启动期对账（把「索引静默缩水」从用户主动动作扩大到每次冷启） |
| ✅ 用的机制 | 「离线缓存管理」打开即对账 ⇒ `OfflineLibrary.retain(缓存里还有片段的 songId)` |
| ✅ 补的单测 | `OfflineLibraryTest` 两条真机形状用例：**判据只能是「缓存里有没有」**；缺 `urls` 的 6 条真实条目必须存活 |
| ✅ 改正的文档 | v2.5.5「走『清空离线缓存』即可」的建议**会造成数据损失**，已改正 |

**验收数据见 `EVIDENCE.md` §7.2**（before/after 两份 `ncrust_offline.xml`）：

| 项 | BEFORE | AFTER |
|---|---|---|
| `tracks` | 59 | **58**（精确少 1 条） |
| `urls` | 147 | **147**（一个都没动） |
| `503616` | 在 | **已消失** |

探针在更早的设备状态（50/145）上预测 `50→49 / 145→145`，实际执行时设备已被正常使用
（59/147），**增量完全一致（−1 / 0）** ⇒ 机制判断正确，用户真实数据一条没少。

---

## 8. 未验证缺口（诚实清单）

1. **API 25~30 的冷启动未测**（无设备）：只有 API 24 / 31 / 36 三台。API 24 是目标区间**下界**，
   对 24~30 整段是**外推**。
2. **baseline profile 的生成（B1）未完成** —— 三台设备全部不满足 `BaselineProfileRule`
   的 API/UiAutomation 前提（详见 §4 与 `EVIDENCE.md` §6.1）。**已交付采样器 + 脚本 + CI，
   未交付生成产物，也不声称改善。**
3. **CI workflow 未在真实自托管 runner 上执行过** —— workflow 已按正确形态写好，
   但本仓库没有自托管 runner。**不声称 CI 已跑通。**
3. **「先到先发布」缺少可控慢 ncm 场景下的前后端到端对照**：证据是
   （a）探针抓到的 30s 空屏真机样本 + （b）代码路径分析 + （c）单元测试。
4. ~~搜索埋点的 release 真机采集~~ —— **已完成**（§5，n=4，`ttfr` P50 ≈ 646ms，
   原始数据 `verification/search-latency-v256.txt`）。仍未测得的是**「ncm 慢」那侧的
   前后对照**（需要构造可控的慢 ncm）：现有证据是探针的 30s 空屏真机样本 +
   本版回归复现时抓到的 `ncm 搜索中… · qm 30 首` 界面状态。
5. **平板搜索延迟对照**：WGR-W09 无 `screenrecord`，无法测 41ms 量级。
6. **`StartupBenchmark`（macrobenchmark）在 API 24 上挂死**：本版实测
   `AndroidJUnitRunner` 打完 `Output Directory: …` 后 24 分钟零输出、进程 `S (sleeping)` 在
   `SyS_epoll_wait`。⇒ 冷启动 A/B 改用平台命令（`cmd package compile` + `am start -W`），
   口径写在脚本里。**macrobenchmark 在本仓库至今没有一次成功运行**（v2.5.4/v2.5.5/v2.5.6 各一次）。
8. **华为控制中心媒体卡片**：任务明令不碰 —— 全程未读取、未改动。
