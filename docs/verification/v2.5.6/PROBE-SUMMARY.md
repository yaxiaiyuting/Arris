# v2.5.6 探针汇总（PROBE-SUMMARY）

> 探针时间：2026-09-26 · 仓库 `HEAD` 起点 `fef5e7b`（v2.5.5）
> 真机：`0715f763f54c023a` Samsung SM-G9209 / **Android 7.0 / API 24**（root，Magisk）、
> `WVQ6R22124000968` Huawei WGR-W09 / **EMUI 14.2.0 / Android 12 / API 31**（平板）、
> `3B15CD00GB700000` OnePlus PLC110 / **Android 16 / API 36**
> 分册：`probe-baseline-profile.md` / `probe-search-latency-gap.md` /
> `probe-tablet-rotate.md` / `probe-offline-index-cleanup.md`

## 0. 结论先行：四条任务书前提里，**三条被推翻**

| # | 任务书前提 | 实测判定 | 一句话依据 |
|---|---|---|---|
| 1 | 「仓库当前无 `androidx.profileinstaller` 依赖」（§2.1） | ❌ **推翻** | 依赖自 v1.2.1（`f9d45f4`）就在；API 24 真机 `D ProfileInstaller: RESULT_INSTALL_SUCCESS` |
| 2 | 「搜索延迟 gap（TTFB → UI）最值得压缩」（§1/§4） | ❌ **推翻** | 客户端渲染段 P50 **41.5ms（占总量 1.4%）**；98% 是服务端 TTFB。真正的浪费在别处（见 §2） |
| 3 | 「EMUI 未采纳 wm 旋转 override，平板 ⤢ 不工作」（§2.3） | ❌ **推翻** | EMUI **采纳**（受控 A/B）；真因是竖屏下控制条三组**命中区重叠**，⤢ 被传输组盖住 |
| 4 | 「PLC110 离线索引注入记录需要清理」（§6） | ⚠️ **部分成立** | 注入记录**恰好 1 条**且无音频无 URL（功能上惰性）；既有对账路径已能精确清除 ⇒ 属**设备状态收尾**，不需要产品代码改动 |
| — | 「baseline profile 可选」（隐含） | ✅ 成立（按新铁律 20 升级为基础设施） | 但真正的缺口在**内容与管线**，不在依赖 |

**四条探针的共同形状**：任务书描述的是**症状**，而症状的根因都在别处。
本版因此按探针结论施工，并按新铁律 22「无数据不声称优化」逐条留数字。

---

## 1. 探针 A：baseline profile 现状（→ `probe-baseline-profile.md`）

**问题**：仓库有没有接 profileinstaller？APK 里有没有 profile？能不能生成？CI 能不能跑？
API 24~30 的机制是什么？体积影响多大？

| 问题 | 答案 | 证据 |
|---|---|---|
| 有依赖吗 | **有**，`androidx.profileinstaller:profileinstaller:1.4.0`，自 v1.2.1 | `git log -S`、`git show <tag>:app/build.gradle.kts \| grep -c` = 1（v2.5.3/2.5.4/2.5.5） |
| APK 里有 profile 吗 | **有**：`assets/dexopt/baseline.prof` 6470B + `baseline.profm` 838B，另有 `META-INF/androidx.profileinstaller_profileinstaller.version` | `unzip -l dist/Ncrust-v2.5.5-gpl-release.apk` |
| 清单里挂上了吗 | **挂了** 7 处：`ProfileInstallReceiver`（4 个 action）+ `ProfileInstallerInitializer` | `aapt2 dump xmltree --file AndroidManifest.xml`（⚠️ 必须带 `--file`，见 §5） |
| **API 24~30 真的会装吗** | **会**。S6（API 24）实测 `D ProfileInstaller: RESULT_INSTALL_SUCCESS`，profile 落到 `/data/misc/profiles/cur/0/<pkg>/primary.prof`（27018B） | 真机广播 + logcat + root 读目录 |
| API 31+ 呢 | 同一路径**不被跳过**（字节码无 31 分支）；31+ 平台侧另有 `ArtManagerLocal`，两者不冲突 | `javap -c ProfileInstallerInitializer` |
| API 分档 | `< 24` 空操作；`24~30` **首帧后 5~6s** 后台线程安装；`≥ 28` 用异步 Handler | 同上（`if_icmpge 24` / `if_icmplt 28` / `postDelayed(5000 + rand(1000))`） |
| 体积影响 | **0.07%**（7.1KB / 10 059 204B） | `unzip -l` |
| 能生成吗 | **探针前不能**：benchmark 模块 0 个 `BaselineProfileRule` | `benchmark/src/main/java/…` 只有 4 个 `MacrobenchmarkRule` 基准 |
| 需要新模块吗 | **不需要**：现有 `:benchmark` 已是 `com.android.test` + `targetProjectPath=":app"` + `self-instrumenting`，结构等价于官方 `:baselineprofile` 模板 | `benchmark/build.gradle.kts` |
| CI 能跑吗 | **托管 runner 不能**（无设备/无嵌套虚拟化）⇒ 必须是**自托管 + `workflow_dispatch`/定期** | 见 `.github/workflows/baseline-profile.yml` |

**真正的缺口**（这是 P0 的施工面）：`app/src/main/baseline-prof.txt` 是 **91 行手写的 class-only 清单**
（`Lcom/…;`，无一条方法级 `HSPL…`），且**没有采样管线**。⇒ P0 = 补采样器 + 用真机采样替换 + 量化。

**顺带推翻的一条 v2.5.5 遗留**：「三条 macrobenchmark 基准结构上跑不了（`DROP_SHADER_CACHE` 无人应答）」
—— 实测两台设备 `result=14`（= 期望值）。**接收器在，广播有人应答。**

---

## 2. 探针 B：搜索延迟 gap（→ `probe-search-latency-gap.md`）

**问题**：TTFB 1722ms、「0 首在屏幕」2.2~2.7s —— gap 花在哪？哪一段能压？

**方法**：PLC110 上 `screenrecord --bugreport`（帧内烧录毫秒级设备时间戳）直读时刻，
6 个真机样本 + 宿主机同网段对照。

| 量 | P50 | min–max |
|---|---|---|
| QQ 腿（派发 → 状态写入） | **2923ms** | 1619–3969 |
| 派发 → 首条结果上屏（ncm） | 616ms | 519–718 |
| **状态写入 → QQ 计数上屏（渲染段）** | **41.5ms** | 36–50 |
| 渲染段占比 | **1.4%** | 0.9–2.5% |

宿主机同网段：QQ legacy `client_search_cp` TTFB P50 **2905ms**（响应体 55.5KB），
`musicu.fcg` TTFB P50 **378ms**，ncm 搜索 total P50 743ms。
设备 QQ 腿只比宿主机 TTFB 高 **18ms**，而宿主机光传响应体就要 150ms
⇒ **首字节之后的客户端开销小于跨主机噪声。**

**三段推翻**：

1. ❌「gap 最值得压缩」—— 98% 是服务端 TTFB，客户端天花板约 2%；
2. ❌「最可能是响应后每首歌 N 次额外网络请求」—— **代码层面不存在**：QQ 搜索是单请求（无分页），
   `publish` 只有纯内存排序 + `distinctBy`；`CatalogAggregator`/`CrossSourceMatcher` 只被 3 个详情页调用；
3. ❌「2.2~2.7s = time-to-first-result」—— **读错了**：那是假状态「qm 0 首」的**停留时长**；
   首条结果上屏是 tap + 1.17~1.76s（含 500ms 防抖）。

**探针真正找到的两条（本版只做第一条，理由见 §6）**：

- **首帧上屏被 ncm 腿硬阻塞** —— `SearchViewModel` 的 `neteaseDeferred.await()` 是发布前唯一闸门，
  QQ 先回来也不发布。真机抓到 `elapsed=30006ms netease=0 qq=30 qqTimedOut=false`，
  录像某一帧（派发 +9.5s）**搜索框在转圈、结果区全空**，而 QQ 数据 ≤5s 已到手
  ⇒ **≥4.5s 帧证据、完整约 25s 的纯客户端浪费**。根因：ncm 那条 OkHttp 只有 30s connect/read、
  **无 `callTimeout`**（`RetrofitClient.kt:53-54`）。
- **主通道选错了** —— `QqApi.kt:63-82` 先 legacy（TTFB 2905ms）后 musicu（378ms）= **7.7 倍**
  （v2.5.5 是 4.6 倍）。换主通道需要先取证（代码注释称 musicu 曾限流 code 2001）。

**埋点审计**：全仓 `EventListener`/TTFB **0 命中**；搜索链路上唯一时间戳是
`SearchViewModel` 的 `startedAt` 与结尾 `elapsed` 那一对 —— **五个分段里四个没有时间戳**。
且 `QqApi.kt:74,80` 与 `QqClient.kt:289` 的搜索日志被 `BuildConfig.DEBUG` 门控
⇒ **release 包里一条都不存在**（与铁律 16 直接冲突）。

**未测得**：设备侧 TTFB / 响应体读完 / 解析映射耗时（无埋点，只有宿主机近似）；
健康态下「QQ 先于 ncm 返回」未抓到（6/6 ncm 先到）；平板无有效数据
（该设备**没有 `screenrecord`**，screencap 仅 1.71–1.80fps，不足以测 41ms）。

---

## 3. 探针 C：平板 ⤢ / EMUI（→ `probe-tablet-rotate.md`）

**问题**：⤢ 点了调用什么？EMUI 上具体表现？有没有别的路径？为什么手机行平板不行？

**⤢ 今天做什么**：`FullPlayerControls.kt:246-263` → `onToggleBigScreen()` →
`MainActivity.kt:361-362` → `enterBigScreenMode()`(:581-604) 置 `bigScreenMode=true`
后走 `applyOrientationPolicy()`(:546-565) 写 `requestedOrientation = SCREEN_ORIENTATION_SENSOR_LANDSCAPE`。
布局切换另有硬门槛 `PlayerLayout.kt:150-151 isBigScreenActive = requested && orientationLandscape`。
**全仓没有用 WindowManager rotation override**（`WindowManager` 只出现 `FLAG_KEEP_SCREEN_ON`）。

**真机实际表现（WGR-W09 / EMUI 14.2.0 / API 31）**：

| 情形 | 结果 |
|---|---|
| 平板**横屏**点 ⤢ | ✅ **成功**：`big screen: manual enter, holding landscape`、`mOrientation=SENSOR_LANDSCAPE`、`currentAppOrientation=6`、大屏布局 + 沉浸式生效 |
| 平板**竖屏**点 ⤢ | ❌ **无反应**：a11y 树里**没有** `大屏幕模式` 节点；但 ×5 放大截图证明图标**画在 x≈304px**；点该处实际触发**播放/暂停**（304/330 → 暂停↔播放） |
| 受控 A/B（应用持 `SENSOR_LANDSCAPE` 时强制 `user_rotation=0`） | 显示**保持横屏 ≥10s**；撤掉请求后同一 `user_rotation=0` 下**立刻回竖屏** |
| `wm get-ignore-orientation-request` | **false** |

⇒ **EMUI 完全采纳旋转请求。任务书前提被推翻。**

**真因（本版新发现）**：`FullPlayerControls.kt:188-263` 的横向控制条是 `Box` + 三个各自
`align(CenterStart/Center/End)` 的 `Row` —— 三组**互相独立定位**，窄容器下必然重叠，
而 Compose 里**后声明的赢命中测试**。

| 容器 | 宽度 | 左组 | 传输组 | 重叠 |
|---|---|---|---|---|
| 平板**竖屏**（左栏 = 0.44×800dp） | **352dp** | 8–168dp | 103–247dp | **65dp**，⤢ 槽位 128–168dp **100% 被盖** |
| 平板横屏 | 563dp | — | — | 无（门槛 W ≥ 478dp） |

`PlayerCard.kt:766-769` 的注释当年就写过「第 4 个在横向三段式布局里会顶到居中的传输组
（实测与『上一首』重叠）」—— v2.5.5 正是把它加回了左组。

**未验证**：大屏多窗口 / 华为 PC 模式下的方向采纳；「先竖屏、后发请求」的时序
（竖屏下唯一入口恰好不可点，无法用应用自身发起）；应用内 `auto_rotate=on`（本机为 off）。

---

## 4. 探针 D：PLC110 离线索引注入记录（→ `probe-offline-index-cleanup.md`）

**注入记录：恰好 1 条**

| 字段 | 值 |
|---|---|
| songId | `503616` |
| name | `EM10_C_Long_Premix#070705` |
| artist | 鷺巣詩郎 |
| level / cacheKey | `jymaster` / `song:503616:jymaster` |
| completedAt | `1790403031347`（2026-09-26 14:10:31 +08） |
| durationMs | 137160 |
| PLC110 上的 urls 条目 | **无** |
| PLC110 上的音频片段 | **无**（`exoplayer_internal.db` 索引表 57 条 key 里没有它） |

**三重判别证据（互相独立）**：
① `docs/verification/v2.5.5/EVIDENCE.md:109`「原样注入 PLC110 的 tracks（47→48 条）」+ `:271`「⚠️ 未还原」；
② 与 S6 旧形状快照 `40-s6-offline-v254-legacy-shape.xml` 的 `{"a":503616,…,"i":1790403031347}`
九字段逐一相同（S6 的 urls 有它、PLC110 没有）⇒ 外来记录；
③ 拉取 `databases/exoplayer_internal.db`：它是 50 条 tracks 里**唯一**「无缓存 + 无 URL」双孤儿。

**条数**：快照 `43-plc110-offline-AFTER-replay.xml`（与 v2.5.5 归档 `41-…after-migration.xml` md5 逐字节相同）
= tracks **48** / urls **145** / 注入 **1**。19:29 回读已漂移：tracks **50**（新增 `561105553`、`1854421610`）/
urls 仍 145 / `503616` 仍在。音频 529 个 `.v3.exo`、2.3GB、**零孤儿文件**；`offline_cache_mb=4096`。

**要不要改代码：不要 —— 属设备状态收尾**

| 机制 | 有效 | 伤真实数据 | 判定 |
|---|---|---|---|
| A. 设置页「清除缓存」（`OfflineAudioCache.clear`） | ✅ | ❌ **会清掉 2.3GB / 50 首真实离线数据** | **禁用** |
| B. 打开「离线缓存管理」即对账（`OfflineCacheOverlay.kt:128` → `reconcileLibrary` → `OfflineLibrary.retain`） | ✅ **精确只删这 1 条**（`urls` 一字不动） | ✅ 不伤 | ✅ **推荐** |
| C. 下次启动自动清理 | ❌ 不存在（`reconcileLibrary` 唯一调用点是管理页） | — | 不加（收益≈0、风险>0） |
| D. `offline_index_version` + 迁移 | ❌ 不存在 | 需设计「只删哪条」否则必然误伤 | 不加（与 `OfflineTrackCodec.kt:89-91`「版本号不落盘」契约冲突） |
| E. 等 LRU 自然淘汰 | ❌ **实际不会**：上限 300、它在插入序第 46/50 位靠队尾，LRU 从队头淘汰 ⇒ 需约 250+ 次新 upsert | — | 不可依赖 |

**它为什么是惰性的**：离线兜底 `recallOfflineCache` 需要 `OfflineUrlStore.recall`
**且** `OfflineAudioCache.contains` 同时成立（`PlayerViewModel.kt:1287-1289`）—— 两条都不满足
⇒ 既不能让用户看到假的离线歌，也不可能被拿去起播。

**顺带改正一条有害建议**：v2.5.5 `EVIDENCE.md:271` / `CHANGELOG-v2.5.5.md` 写「如需清掉，
走『清空离线缓存』即可」—— 那条写于本设备已有 2.3GB 真实离线数据的**现在**，
照做会造成用户数据损失。已在本版改正为机制 B。

---

## 5. 方法学教训（本版一号教训，已写进 AGENTS.md 新铁律 22 的配套条款）

v2.5.5 遗留清单里那条「本仓库根本没有依赖 `androidx.profileinstaller`」的取证命令**本身失败了**，
而失败伪装成了阴性结果：

```bash
$ aapt2 dump xmltree app-release.apk AndroidManifest.xml
missing required flag --file                  # ← stderr，被管道吞掉
dump xmltree [options] --file arg files...
$ … | grep -ci profile
0                                             # ← 空 stdout 的计数，被读成「没有」
```

新版 `aapt2` 要求显式 `--file`；写错时它**不产出任何 XML**，只在 stderr 打一行用法。
于是「命令没跑起来」与「清单里确实没有」在证据上**完全同形**。

⇒ 新规则：**任何「计数为 0 / 无命中」的结论必须同时留 stderr 与退出码，
并用一条独立路径交叉验证。** 本例的第二条路径是设备上的广播返回值 `result=14`（与 `aapt2` 完全无关）。

---

## 6. 本版按探针结论做的取舍

| 探针建议 | 本版处置 | 理由 |
|---|---|---|
| P0 解掉首帧对 ncm 的阻塞（先到先发布） | ✅ **做了** | 探针给出 30s 空屏的真机帧证据；改动只影响「ncm 慢」这条异常路径 |
| P0 给 ncm 补 `callTimeout` | ✅ **做了，但只给 `searchApi`** | 给共用 `api` 加会改掉专辑详情/歌词/歌曲详情的失败面 = 「修搜索」变成「改半个网络层」 |
| P0 补 4 个缺失埋点（OkHttp `EventListener` 拿真 TTFB） | ✅ **做了** | `HttpTimingListener` + `SearchLatencyTrace`，且**无条件打**（release 里必须存在） |
| P1 换 QQ 搜索主通道（musicu，快 7.7 倍） | ❌ **不做，只在报告留数据** | 回归面是**结果顺序**；代码注释记载 musicu 曾被限流（code 2001）。换它需要先拿 n≥30 稳定性 + 逐字段比对 —— 铁律 22 不允许在无数据时声称优化 |
| P2 骨架屏 + QQ 行跳过入场动效 | ❌ **不做** | 渲染段 P50 只有 41.5ms（1.4%）—— 在这个量级上做占位屏是拿真复杂度换噪声 |
| 「0 首」展示时长显著缩短 | ✅ 由「先到先发布」直接达成 | 旧实现下「0 首」的停留时长 = ncm 腿耗时；现在 = min(两腿) |

## 7. 全局未测得项（诚实清单）

1. **API 25~30 设备上的冷启动**：手上只有 API 24 / 31 / 36 三台。API 24 是目标区间**下界**，
   对 24~30 整段是**外推**。
2. **CI workflow 的真实执行**：本仓库没有自托管 runner。workflow 已按「自托管 + 手动/定期」写好，
   但**未在真实 runner 上跑过** —— 不声称 CI 已跑通。
3. **搜索链路的设备侧 TTFB / 解析 / 映射**：埋点已加，但本轮未做「加埋点后的真机采集」
   （探针阶段是**无埋点**状态下用录像帧时间戳反推的）。
4. **平板 / 手机的搜索延迟对照**：WGR-W09 没有 `screenrecord`，无法测 41ms 量级。
5. **「先到先发布」在真实慢 ncm 下的端到端复现**：探针抓到过 30s 空屏，
   但本版未构造可控的慢 ncm 场景做前后对照（只有单元测试 + 代码路径分析）。
6. **华为控制中心媒体卡片**：任务明令不碰 —— 全程未读取、未改动。
