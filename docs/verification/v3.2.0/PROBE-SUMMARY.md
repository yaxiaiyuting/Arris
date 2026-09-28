# v3.2.0 探针总裁决（PROBE-SUMMARY）

> 版本：v3.2.0-gpl ｜ 采集窗口：2026-09-28 18:20–19:30 CST
> 设备：SM-G9209（`0715f763f54c023a`，Android 7.0 / API 24 / 1440×2560 / density 4.0，已 root，
> 已安装 v3.1.0-gpl release 且**已登录 QQ 音乐会员账号**）
> 本文件是**索引与判决**；每条结论的原始证据在对应的分册里，逐条可复现。

---

## 0. 三句话

1. **三个用户可见的 P0 全部定位到「判据/调度/结构」而不是「接口不通」**：
   QQ VIP 歌没有误判服务端数据，是**客户端没有判定**；B站搜不到不是签名问题，是**主线程做 socket IO**；
   简洁档还抖不是降级失效，是**两个逐帧动效被留在了简洁档**。
2. **两个「看起来像平台问题」的假设被实测证伪**：`media_mid` 混淆（取链阶段服务端根本不校验，静默坏链只在 CDN 才现形）、
   Wbi 签名错误（host 侧用同一套算法 20/20 条成功）。
3. **三处修复都落在纯函数/单一几何源上**，因此都有 JVM 单测；真机未取证的部分逐条如实列在 §4。

---

## 1. 分册索引

| 分册 | 回答什么 | 关键判决 |
|---|---|---|
| [`probe-qq-copyright.md`](probe-qq-copyright.md) | QQ VIP 歌「误判无版权 + 自动跳歌」 | 根因是**判定逻辑缺失**（D1–D4），不是 cookie/guid/参数 |
| [`probe-bili-search.md`](probe-bili-search.md) | P0-C 搜不到 / P0-D 切筛选卡死 | C = **`NetworkOnMainThreadException` 被静默吞掉**；D = **筛选档画在会被自己过滤掉的列表里** |
| [`probe-ui-jitter.md`](probe-ui-jitter.md) | P0-B 简洁档还抖 / P1 律动开关 | 根因是 `MotionEffects.kt:352`（`coverElevation` 恒真）与 `:351`（呼吸在简洁档为真） |
| [`probe-mini-cover.md`](probe-mini-cover.md) | P0-A 歌词全屏左上角封面错位 | 封面按 **80dp 托盘**中心摆，而全屏顶栏只有 **56dp** ⇒ 低 12dp；真机实测差 **45px ≈ 11.6dp** |
| [`probe-bili-login.md`](probe-bili-login.md) | P1 B站扫码登录 | 三跳协议实测；**复用 `QqQrLogin` 的状态机**，协议层重写 |

---

## 2. QQ 版权（P0）· 判决

**问题**：QQ 音乐 VIP 歌曲被说成「此源无版权」并被自动跳过。

| 候选根因 | 判决 | 证据 |
|---|---|---|
| cookie 没带对 | ❌ 证伪 | 同一曲目：带票据 8/8 档 `result=0` + purl；完全匿名 8/8 档 `result=104003` + 空 purl |
| `guid` 生成不对 | ❌ 证伪 | 10 位数字、服务端 `req.code=0` |
| `filename`/`songmid`/`media_mid` 混淆 | ❌ 取链阶段证伪；⚠️ 但「用错 mid 时 purl 照样非空、**CDN 才 404**」是真实存在的静默坏链 | 三个变体都拿到 purl（长度 203–232）；M800 正确 mid → `206`；M800 错误 mid → `404 file not exist` |
| 错误码读错 | ✅ 部分成立 | `lastUrlFailure` 里存着 `resultCode`，但**全仓库零个读取方** |
| 判定逻辑 | ✅ **根因** | 没有任何版权判定；任何 `null` 一律被说成「无版权」并跳歌 |

**修复**：`QqRejection`（逐档位分类 + 批量收敛）→ `ResolveFailureKind`（跨音源）→
`resolveFailureAction`（三态：只有 `UNRESOLVABLE` 允许跳歌、`NETWORK` 有界重试、其余停下+说明）。
**QQ 永远不产出 `COPYRIGHT_GONE`**（15,120 组输入的穷举单测守着）。

**诚实边界**：用有效 VIP 票据 12 首 × 8 档 = 96 次取链，**零次**复现「无 purl」；
缺陷由源码链路的四条事实证明，**触发条件是概率的**（票据失效一次就会命中）。

---

## 3. B站搜索（P0-C/D）· 判决

**P0-C 根因**：`SearchViewModel` 的 `async` 继承 `Dispatchers.Main.immediate`（内联启动）
→ `BiliApi.searchVideos` → `client.newCall(...).execute()` **在主线程做 socket IO**
→ 平台**毫秒级**抛 `NetworkOnMainThreadException` → 被 `wbiKeys` 的 `runCatching` 静默吞成 `null`
→ `parseSearchTracks("")` → **恒 0 条，且零错误提示**。

```
PROBE-BILI320-RAWSYNC on=main bodyLen=-1 elapsedMs=8 error=NetworkOnMainThreadException
PROBE-BILI320-RAWSYNC on=io   bodyLen=249 elapsedMs=1527 error=null
PROBE-BILI320-SEARCH  on=main n=5 / on=io n=5     ← 修复后主线程发起也能拿到条数
```

（**任务书与我的初始假设都被修正**：不是「主线程被冻 30 秒」，而是**立即失败**。）

**P0-D 根因**：筛选档与逐源统计行画在结果 `LazyColumn` 的 item 里，
而整条列表被 `if (visibleSongs.isEmpty() && !isLoading)` 挡着 ⇒ 点「只看 B 站」后
若这一轮没有 B 站行，**筛选档自己消失、没有任何路径切回「双源」** ⇒ 用户描述为「卡死 / 无法退出」。

**修复**：`BiliApi` 7 个对外方法改 `suspend` + `withContext(Dispatchers.IO)`；
筛选档/统计行移出列表常驻；判据抽成纯函数 `shouldShowFilterRow` / `emptyKind` / `emptyText`；
加载态与下拉刷新**复用既有组件**（`PullToRefreshBox` 确实存在，此前只用在歌单详情页）。

---

## 4. 界面律动（P0-B/P1）· 判决

| 层 | 落点（修前） | 为什么简洁档还在动 |
|---|---|---|
| 封面浮动 | `PlayerCard.kt:1822-1825` | 门控是 `motion.coverElevation`，而它在 `MotionEffects.kt:352` 是 `uiOn`（简洁档恒真）—— 一个能力位同时表示「静态阴影」与「逐帧浮动」 |
| 背景呼吸 | `MotionBackdrop.kt:113-123` | `backgroundBreathing = uiOn && switches.breathing`（`:351`）在简洁档同样为真 |

歌词律动 / 控制条脉冲 / 视差 / 冲击波 / 光晕 / 粒子 / 3D 在简洁档**确实都已关** ⇒
「简洁只是波形档位」**被证伪**（`tier` 确实进了 `MotionEffects.of`）；
`motion_degrade_level` 在渲染侧**零引用**（与 v3.0.0 取消降级机制上无关）。

**连带缺陷**：`MotionClock.kt:190-192` 让简洁档的**波形推进搭了「背景呼吸」的便车** ——
只砍呼吸会把简洁档波形冻住，本版一并解耦（新增 `waveformMounted` 参数）。

**P1**：复用 `motion_breathing`（就是背景呼吸），新增 `motion_rhythm_enabled` /
`motion_cover_float` / `motion_lyric_pulse` / `motion_bar_pulse`（默认全开），水位 4 → 5 带迁移。

**真机取证（只读，未装包）**：S6 连拍 10 帧相位相关 —— **封面逐帧竖直位移 ±1…5px**，
对照组（播放键图标）逐帧 `dy ≡ 0`；纯背景区整屏明暗极差 0.93/0.90。
见 [`evidence-s6-simple-tier-jitter.md`](evidence-s6-simple-tier-jitter.md)。

---

## 5. 迷你封面（P0-A）· 判决

真机实测（PNG 像素 + `uiautomator dump` 双向取证）：

| 量 | 实测（px） | 换算 |
|---|---|---|
| 全屏顶栏 | 96..320 | 高 **56dp**（中心 208） |
| 封面实际渲染 | 142..365 | 中心 **253** |
| **差** | **45px** | **≈ 11.6dp** |

根因：封面 overlay 按**收起态托盘**（80dp）的中心摆，而全屏顶栏只有 **56dp**；
且左边缘压在屏幕 x=0（`miniCoverCenterX = coverHalfDp()` = 28dp），而全应用内容边距是 16dp。
修复：几何收敛到 `TrayLayout`（新增 3 个常量 + 3 个派生函数），
`topBarBottomPx` / 顶栏高度 / 收起键高度三处字面量统一到 `TOP_BAR_HEIGHT_DP`。

---

## 6. B站登录（P1）· 判决

协议实测：`generate` → `poll`（业务码在 **`data.code`**：`86101` 未扫 / `86090` 已扫 / `86038` 失效 / `0` 成功）
→ `nav` 校验。TTL 实测 **180 秒**（+178s 仍 `86101`、+189s 已 `86038`）。
**纠正 v3.1.0 文档一处**：`generate` 4/4 次**没有任何 `Set-Cookie`**（不需要 CookieJar）。

复用 `QqQrLogin` 的 7 条设计（状态语义 / 码表显式 / 服务端回绝与网络抖动分离 / 有界轮询 /
取消不是失败 / 常量显式 / 解析失败≠登录失败），**协议层 100% 重写**。

**未验证（不许含糊）**：`data.code=0` 的成功态与 `86090`（需真人扫真账号）；
**登录后是否出现 FLAC（`type:3`）**；登录后收藏夹；真·自然过期表现。

---

## 7. 真机取证口径（与结论一起读）

| 项 | 口径 |
|---|---|
| QQ 取链协议探针 | **host 侧**用设备上真实 cookie、按生产代码的逐字请求形状发出 —— 是真实服务端响应，但不是 App 进程内发出的 |
| B站搜索调度 | **设备侧仪器化探针**（API 33 模拟器，真实网络 + 真实生产代码）；真机 S6 的 logcat 被 ROM 过滤，**未取到** |
| 界面律动 | **真机 S6 只读多帧取证**（未装包、未改 prefs）；**修复后**未取证 |
| 迷你封面 | **真机 S6 截图 + 节点 bounds**；**修复后**未取证（设备上是 v3.1.0 release，debug 包签名不同、强装会清掉登录态，代价不对等） |
| B站登录 | 全部**离线**（host 侧 curl + JVM 单测），**未占用真机** |

---

## 8. 未验证 / 未做（完整清单，逐条如实）

- **P0-A / P0-B 修复后的真机可视化验证**：需要装新包，而设备上已登录的 QQ 会员账号会因签名不同被清掉
  ⇒ 本版只声称「偏差已实测 + 目标值在算术上逐像素一致」，**不声称已用真机截图验证修复后位置**。
- **`pneedbuy` / `isbuy` 非零时服务端的确切回答**未验证（分类里有防御分支，探针文档标注为未验证）。
- **`type=-1`（30 秒试听）未复现**。
- **QQ 的「无版权 / 版权方下架」错误码完全没有观测到** —— 这正是客户端不许下这个结论的原因。
- **B站 CDN 取流（ExoPlayer 播字节）仍未验证**（v3.1.0 的 403 结论未被推翻，本版没碰那条链路）。
- **`F000`/`AI00` 直链是 `audio/x-ogg` 而不是 `audio/flac`**（本轮只记录事实，未验证解码）。
- **登录后能否拿到 B站无损** —— 无大会员账号，未验证；UI 文案如实写「尚未验证」，**不作承诺**。
- **界面律动关闭后的帧时间 / 功耗**未采集（无 release 包真机对照）。
- **下拉刷新与筛选档的实际渲染**无自动化证据（仓库无 Robolectric），也未做手点复核。
- **B站登录的 UI 未在真机上跑过**（浮层、设置页入口、`BiliAuthStore.init` 的播种都只有编译与单测保证）。
