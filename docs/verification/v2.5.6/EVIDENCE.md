# v2.5.6 证据（EVIDENCE）

> 全部数据来自本版**实际执行过**的命令与**实际读到的**文件。
> 未测得的项一律标「未测得」并给出原因（铁律 11 / 22）。

---

## 0. 环境与设备

| 设备 | 型号 | 系统 | API | 用途 | 改动留痕 |
|---|---|---|---|---|---|
| `0715f763f54c023a` | Samsung SM-G9209 | Android 7.0 | **24** | 冷启动 A/B（release） | 动画原值即为 `0/0/0`（未改）；末尾回读一致 |
| `WVQ6R22124000968` | Huawei WGR-W09 | **EMUI 14.2.0** / Android 12 | 31 | 平板 ⤢ 真机验证 | `user_rotation` 临时改过并**已还原为基线 1**（`raw/00`、`raw/80` 逐项回读） |
| `3B15CD00GB700000` | OnePlus PLC110 | Android 16 | 36 | 搜索延迟探针 / profile 采样尝试 / 离线索引收尾 | 无障碍服务临时关闭并**已还原**（见 §5）；重启过一次 |

---

## 1. baseline profile：现状探针（详见 `probe-baseline-profile.md`）

| 断言 | 命令 | 实测 |
|---|---|---|
| 依赖存在 | `git log --oneline -S profileinstaller -- app/build.gradle.kts` | `f9d45f4`（versionName 1.2.1） |
| 三个 tag 里都在 | `git show <tag>:app/build.gradle.kts \| grep -c profileinstaller:profileinstaller` | v2.5.3/2.5.4/2.5.5 → `1/1/1` |
| APK 里有 profile | `unzip -l dist/Ncrust-v2.5.5-gpl-release.apk \| grep dexopt` | `baseline.prof` 6470B + `baseline.profm` 838B |
| 清单挂上了 | `aapt2 dump xmltree --file AndroidManifest.xml <apk> \| grep -ci profile` | **7** |
| **API 24 真机装得上** | `am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE …` + `logcat` | `Broadcast completed: result=1`；`D ProfileInstaller: RESULT_INSTALL_SUCCESS` |
| profile 落盘 | `su -c ls -la /data/misc/profiles/cur/0/com.takahashirinta.ncrust/` | `primary.prof` 27018B |
| `DROP_SHADER_CACHE` 有人应答 | `am broadcast … BENCHMARK_OPERATION` | `result=14`（API 24 与 API 31 各一次，**都是 14**） |
| 安装时机（字节码） | `javap -c ProfileInstallerInitializer` | `SDK_INT < 24 → 空操作`；`≥ 28 → Handler28Impl.createAsync`；`postDelayed(5000 + rand)` |
| `.prof` 体积占比 | — | 7092B / 10 059 204B = **0.07%** |

**v2.5.5 那条错误结论的根因复现**：

```
$ aapt2 dump xmltree dist/Ncrust-v2.5.5-gpl-release.apk AndroidManifest.xml
missing required flag --file
dump xmltree [options] --file arg files...
→ grep -ci profile = 0        # 空 stdout 的计数被读成「没有」
```

---

## 2. baseline profile 效果：冷启动 A/B（release 包 / API 24 真机）

**口径**（脚本：`verification/coldstart-ab.sh`）：
同一台 S6（API 24）、同一份 release 源码、**只换 APK 里的
`assets/dexopt/baseline.prof`**；`cmd package compile -m speed-profile -f` 固定编译档；
`am start -W` 取 `TotalTime`；每组 10 次迭代、每次 `am force-stop` 保证冷启。

### 2.1 分组

| 组 | 源码 | profile 内容 | 原始数据 |
|---|---|---|---|
| **A** | v2.5.5（`fef5e7b`） | 手写 class-only（91 行） | `verification/coldstart-A-handwritten.txt` |
| **B0** | v2.5.6（本版，含搜索/平板改动） | 手写 class-only（同上） | `verification/coldstart-B0-handwritten.txt` |
| **B1** | v2.5.6 | **真机采样的方法级 profile** | 见 §2.4 |

### 2.2 实测结果（`TotalTime`，ms）

| 组 | 编译档 | n | min | **P50** | mean | max |
|---|---|---|---|---|---|---|
| A | `speed-profile`（按 profile 编译） | 10 | 553 | **598.5** | 597.2 | 619 |
| A | `speed`（全量 AOT，**不看 profile**） | 10 | 476 | **500.0** | 497.3 | 514 |
| B0 | `speed-profile` | 10 | 575 | **590.5** | 594.9 | 640 |
| B0 | `speed` | 10 | 473 | **486.5** | 488.6 | 504 |

### 2.3 从这张表里能读出什么（**只读得出这些**）

1. **协议确实区分编译档**：`speed-profile` P50 ≈ 590~599ms 而 `speed` ≈ 487~500ms，
   相差 **~95~100ms（约 17%）**。⇒「按 profile 编译」与「全量 AOT」是两个不同状态，
   这个协议不是「两次都等价于同一档」。
2. **v2.5.6 的代码改动没有让冷启动变慢**：B0 − A = **−8ms（−1.3%，`speed-profile`）** /
   **−13.5ms（−2.7%，`speed`）**，都在同一台设备同一时段的噪声带内（各组 max−min 约 66ms / 38ms）。
   ⇒ 本版新增的搜索埋点（`EventListener` + 打点）**没有可测量的启动代价**。
3. **手写 class-only profile 与「全量 AOT」之间还差 ~95ms** —— 这就是方法级 profile
   理论上能争回来的空间上界（下界是 0：若生成的 profile 覆盖不足，可能一点也争不回来）。

### 2.4 生成结果

**见 §6「阻塞清单」** —— 本版**未能**在三台可用设备上完成采样，
因此 **B1 未测得**，`app/src/main/baseline-prof.txt` **仍然是手写的 91 行清单**。
**本版不声称 baseline profile 带来了任何冷启动改善**（铁律 22）。

对照：A 的 `verify` 组无效 —— API 24 不接受 `verify`/`quicken` 这两个 filter
（`Error: "verify" is not a valid compilation filter.`），那一组的数字实际是 `speed` 状态的重复测量，
**已从结论中排除**。API 24 只接受 `speed` / `speed-profile` / `everything`。

---

## 3. 搜索延迟

### 3.1 探针阶段（改动前，PLC110 / 6 样本 / `screenrecord --bugreport` 帧内毫秒时间戳）

| 量 | P50 | min–max |
|---|---|---|
| QQ 腿（派发 → 状态写入） | 2923ms | 1619–3969 |
| 派发 → 首条结果上屏（ncm 先到） | 616ms | 519–718 |
| **状态写入 → QQ 计数上屏（渲染段）** | **41.5ms** | 36–50 |
| 渲染段占比 | **1.4%** | 0.9–2.5% |

宿主机同网段对照：QQ legacy `client_search_cp` TTFB P50 **2905ms** / total 3055ms / 响应体 55.5KB；
`musicu.fcg` TTFB P50 **378ms** / 2.2KB；ncm 搜索 total P50 743ms。
设备 QQ 腿只比宿主机 TTFB 高 **18ms**（宿主机光传响应体就要 150ms）。

**首帧被 ncm 硬阻塞的真机证据**：`elapsed=30006ms netease=0 qq=30 qqTimedOut=false`；
录像某一帧（19:37:02.333，派发 +9.5s）**搜索框在转圈、结果区全空**，而 QQ 数据 ≤5s 已到手。

### 3.2 埋点审计（改动前）

| 分段 | 改动前有埋点吗 |
|---|---|
| 请求派发 | ❌（只能由 `logcat -v epoch` 时刻减 `elapsed` **反推**） |
| TTFB | ❌（全仓 `EventListener` **0 命中**） |
| 响应体读完 | ❌ |
| 解析 / 映射 | ❌ |
| UI 更新（首帧上屏） | ❌ |

唯一的时间戳是 `SearchViewModel` 的 `startedAt` 与结尾 `elapsed` 这一对；
且 `QqApi.kt:74,80` 与 `QqClient.kt:289` 的搜索日志被 `BuildConfig.DEBUG` 门控
⇒ **release 包里 0 命中**（与铁律 16 冲突）。

### 3.3 本版改动

| 文件 | 改动 |
|---|---|
| `SearchViewModel.kt` | 首帧上屏：`neteaseDeferred.await()` 硬闸门 → **`select` 先到先发布**；补第一次发布的 `_query` 守卫；QQ 状态映射抽成 `qqStatusOf` |
| `RetrofitClient.kt` | 新增 `searchApi`（只多 20s `callTimeout`；共用 `api` 不动） |
| `HttpTimingListener.kt`（新） | OkHttp `EventListener` → `path=… ttfb=…ms body=…ms total=…ms`（tag `NcrustHttpTiming`，**无条件打**） |
| `SearchLatencyTrace.kt`（新） | 6 个打点 → `query=… ttfr=…ms marks=… segments[…]`（tag `NcrustSearchLatency`，**无条件打**） |

### 3.4 改动后的真机采集（PLC110 / API 36 / v2.5.6 vc47 / release 包）

原始数据：`verification/search-latency-v256.txt`（含 `NcrustHttpTiming` 与 `NcrustSearchLatency` 两类行）。

| query | ncm 腿 | QQ 腿 | **`ttfr`（派发→第一次上屏）** | 首帧来源 |
|---|---|---|---|---|
| `love` | 886ms | 2739ms | **888ms** | ncm |
| `jay` | 428ms | 3089ms | **429ms** | ncm |
| `piano` | 615ms | 1549ms | **616ms** | ncm |
| `hello` | 676ms | 4159ms | **677ms** | ncm |

- `ttfr` **P50 ≈ 646ms**（n=4，429–888ms）—— 这是「点击搜索之后多久看到第一屏结果」的直读值，
  旧实现只能给「总耗时」，本版第一次把它拆出来；
- ncm 腿 **4/4 先到**，与探针的 6/6 一致 ⇒ 这条主路径的 `ttfr` 与 v2.5.5 **按构造相同**
  （`publish` 仍在 ncm 到手时发生）；
- `cloudsearch/pc` 的 `ttfb=661ms / body=9ms / total=877ms` ⇒ 健康态下首字节之后只有 ~216ms
  是传输+解析，与探针结论（客户端段占比个位数百分比）一致。

**诚实标注**：这 4 个样本里 ncm 都是先到的，所以**它们不能证明「先到先发布」带来了提速** ——
该优化的收益只出现在「ncm 慢」这条异常路径上。异常路径的证据是：
① 探针抓到的真机 `elapsed=30006ms netease=0 qq=30`（30s 空屏）；
② 本版在回归复现时抓到的界面状态 `ncm 搜索中… · qm 30 首` ——
   QQ 的 30 条已经在屏幕上，而 ncm 仍在途中（旧实现在这一刻是**一片空白**）。
③ 本版**主动记录一次自己造成的回归**（见 §3.5）—— 那次的真机数据恰好是
   「ncm 腿被打断时 QQ 结果照样在屏」的端到端演示。

### 3.5 ⚠️ 本版自己造成并已修复的一次回归（必须留档）

**症状（用户报告）**：v2.5.6 装机后「ncm 搜索搜不出歌」。

**真机定位**（PLC110，用的正是本版新加的埋点 —— 这是埋点第一次回本）：

```
NcrustHttpTiming: path=/api/cloudsearch/pc ttfb=-1ms body=-1ms total=20002ms failed=InterruptedIOException
SearchViewModel:  Caused by: java.io.IOException: Canceled
SearchViewModel:  aggregate query='love' netease=0 qq=30 qqTimedOut=false elapsed=20012ms
NcrustSearchLatency: dispatch->netease_done=20009ms dispatch->qq_done=3493ms
UI: ncm 0 首 · qm 30 首
```

`ttfb=-1` ⇒ **请求头一个字节都没发出去**，却在 **20002ms**（正好是新加的 `callTimeout`）被杀。

**根因**：本版给搜索单独加了 `callTimeout(20s)`。而该设备/该网络下**每通请求要 ~30 秒
才把请求头送出去**（同一份 logcat 里 `recommend/songs` `ttfb=240ms body=59ms total=30566ms`
—— `ttfb` 只有 240ms，说明慢的不是服务端，而是连接建立阶段），
⇒ **20s 的 `callTimeout` 卡在这条 30s 的必经路径下面，拦掉的不是「挂死的请求」，
而是本来会成功的请求。**

**修法**：删掉 `searchApi` / `callTimeout`，回到共用的 `api`（`RetrofitClient` 里留有
一段「撤销注释」，写明为什么不要顺手加超时）。用户可见的收益（首帧不等 ncm）
完全来自「先到先发布」，与超时无关 —— 那个改动保留。

**修复后复测**（同一台设备、同一会话、v2.5.5 与 v2.5.6 背靠背）：

| 版本 | 界面 | `cloudsearch/pc` |
|---|---|---|
| v2.5.5（对照） | `ncm 30 首 · qm 30 首` | （无埋点） |
| **v2.5.6（修复后）** | `ncm 30 首 · qm 30 首` ✅ | `ttfb=661ms body=9ms total=877ms` |

**教训（已写进 `AGENTS.md` 铁律 22 的配套条款）**：
`callTimeout` 覆盖**整通**请求（含连接建立与排队），而 `connectTimeout`/`readTimeout`
是**分阶段空闲**超时。**「ttfb 快」不代表「整通快」** ——
加超时之前必须先量一次「这个环境里一通正常请求要多久」。
`ttfb=-1` 这个读数（本版新加的）正是把这次回归定位到分钟级的关键。

---

## 4. 平板 ⤢（EMUI 真机，详见 `probe-tablet-rotate.md`）

| 项 | 命令 / 证据 | 实测 |
|---|---|---|
| EMUI 是否采纳旋转请求 | `adb shell wm get-ignore-orientation-request` | **false** |
| 受控 A/B（持 `SENSOR_LANDSCAPE` + 强制 `user_rotation=0`） | `dumpsys window displays` 连续观察 | **保持横屏 ≥10s**；撤请求后立刻回竖屏 |
| 平板横屏点 ⤢ | `logcat` + 截图 `verification/02-after-bigscreen-tap.png` | ✅ `big screen: manual enter, holding landscape` / `mOrientation=SENSOR_LANDSCAPE` / `currentAppOrientation=6` |
| 平板竖屏点 ⤢（修复前） | a11y dump + ×5 放大截图 `verification/10-row-icons-x5.png` | ❌ a11y 树里**没有** `大屏幕模式` 节点；图标画在 x≈304px；点 304/330 → **暂停↔播放** |
| ⤢ 是否走 WindowManager override | 全仓 grep `WindowManager` | 仅 `FLAG_KEEP_SCREEN_ON` —— **没有任何 rotation override** |
| 布局硬门槛 | `PlayerLayout.kt:150-151` | `isBigScreenActive = requested && orientationLandscape` |

**修复前重叠几何**（竖屏容器 = 0.44 × 800dp = 352dp）：

| 组 | 占位 | 内容 |
|---|---|---|
| 左组（歌词/队列/收藏/⤢） | 8–168dp | 4 × 40dp |
| 传输组（上一首/播放/下一首） | 103–247dp | 44+54+44dp，`align(Center)` |
| **重叠** | **103–168dp（65dp）** | ⤢ 槽位 128–168dp **100% 落在重叠区** |

横屏容器 563dp：左组 8–168、传输组 210–352 ⇒ **无重叠**（门槛 W ≥ 478dp）。
**这就是「为什么手机/平板横屏行、平板竖屏不行」的答案。**

**未验证**：大屏多窗口 / 华为 PC 模式下的方向采纳；应用内 `auto_rotate=on`（该机为 off，日志逐字可证，未去改）。

**本版修复的真机验证**：见 §7（本版改动后的复测）。

---

## 5. 设备改动留痕（逐条，含还原）

| 设备 | 改动 | 还原 | 证据 |
|---|---|---|---|
| S6 | 无（动画原值即 `0/0/0`，本版未写 settings） | — | `coldstart-*.txt` 头部逐项打印 + 末尾回读 |
| WGR-W09 | `user_rotation` 临时改过（探针 C 的受控 A/B） | ✅ 已还原为基线 **1** | `probe-tablet-rotate.md` 的 `raw/00`、`raw/80` |
| PLC110 | `wlan0/disable_ipv6` 尝试写 1，回读为 0（未生效） | 终态 0（与改前一致） | 探针 B §5 |
| PLC110 | `all/disable_ipv6` 改为 1 后立即改回 0 | ⚠️ **写前未记录原值**（探针 B 自陈的失误） | 探针 B §5 |
| PLC110 | 生成 profile 期间临时关闭无障碍服务两次 | ✅ 两次都已还原为原值 | `verification/plc110-accessibility-restore.txt` |
| PLC110 | `adb reboot` 一次（尝试清除 UiAutomation 陈旧注册） | 设备已重启完成，数据无损失 | `verification/generate-profile-plc110.txt` |

**全程未读取、未改动任何华为控制中心媒体卡片相关内容**（任务明令不碰）。

---

## 6. 阻塞清单（本版未能完成的事，逐条给根因）

### 6.1 🚫 baseline profile 采样无法在任何可用设备上完成 → **B1 未测得**

`BaselineProfileRule.collect` 有**硬性 API 前提**，实测报错原文：

```
java.lang.IllegalArgumentException: Baseline Profile collection requires API 33+,
or a rooted device running API 28 or higher and rooted adb session (via `adb root`).
    at androidx.benchmark.macro.BaselineProfilesKt.buildMacrobenchmarkScope(BaselineProfiles.kt:160)
```

逐台核对：

| 设备 | API | 走哪条路 | 结果 |
|---|---|---|---|
| S6 / SM-G9209 | **24** | 需要 API 33+ ❌；或 API 28+ 且 `adb root` ❌（24 < 28） | 🚫 **结构性不可用** |
| WGR-W09 / EMUI 平板 | **31** | 需要 API 33+ ❌（31 < 33）；`adb root` → `adbd cannot run as root in production builds` | 🚫 **结构性不可用** |
| PLC110 | **36** | 满足 API 33+ ✅ | ⚠️ 但见下 |

PLC110 满足 API 前提，却卡在另一处：

```
java.lang.IllegalStateException: UiAutomationService android.accessibilityservice.
IAccessibilityServiceClient$Stub$Proxy@… already registered!
    at android.app.UiAutomation.connectWithTimeout(UiAutomation.java:386)
    at androidx.test.uiautomator.UiDevice.getUiAutomation(UiDevice.java:1463)
    at androidx.benchmark.macro.MacrobenchmarkScope.<init>(MacrobenchmarkScope.kt:165)
```

这与 v2.5.5 §3.3 是**同一个失败**，并且本版**独立复现**了它，同时**修正了 v2.5.5 的根因判断**：

| 尝试 | 结果 |
|---|---|
| 关闭两个第三方无障碍服务（`enabled_accessibility_services=""` + `accessibility_enabled=0`） | ❌ **仍然失败** |
| 确认无残留 instrumentation / uiautomator 进程（`ps -A \| grep -iE "instrument\|uiautomator"` → 空） | ❌ 仍然失败 |
| `adb reboot` 清除可能的陈旧注册 | ❌ 仍然失败 |

⇒ v2.5.5 写的「判定为设备侧的 UiAutomation 连接被占用（Scene / GKD 两个无障碍服务）」
**不是完整根因** —— 关掉它们并不能恢复。真实约束更像「该 ROM 上 `UiAutomation` 已被某个
系统级持有者占用，或 Android 16 收紧了单客户端策略」。

**因此本版如实交付**：
- ✅ 采样器（`BaselineProfileGenerator`，7 条旅程 + 逐条 OK/SKIP 日志）**已实现并编译通过**；
- ✅ 一条命令的重生成脚本 + CI workflow **已实现**；
- ✅ 脚本的**有界失败守卫真的生效了**：三次尝试都在「一条旅程都没 OK」时
  **拒绝覆盖** `app/src/main/baseline-prof.txt`（`GEN_RC=4`），没有把无效结果写进仓库；
- ❌ **B1（生成后的冷启动）未测得**；`baseline-prof.txt` 仍是手写 91 行；
- ❌ **不声称 baseline profile 带来了冷启动改善**（铁律 22）。
- ✅ 已量化的是**上界**：`speed` 比 `speed-profile` 快 ~95~100ms（约 17%），
  那是方法级 profile 能争取的空间上限（见 §2.3）。

**解锁所需的最小条件**（任选其一）：
1. 一台 **API 33+ 且 `UiAutomation` 空闲**的设备（无第三方无障碍服务、无厂商管控）——
   本仓库三台里只有 PLC110 满足 API，但它被 `UiAutomation` 占用；
2. 或一台 **API 28+ 的 userdebug/eng 版本**设备（可用 `adb root`），走
   `BaselineProfileRule` 的 rooted 分支；
3. 或配好**自托管 CI runner**（`.github/workflows/baseline-profile.yml` 已就绪）。

### 6.2 ⚠️ 搜索延迟「改动后」的真机采集未完成

埋点已加进 release 并断言「release 包里必须存在」，但**没有完成改动后的真机采集会话**
（PLC110 在 §6.1 的尝试期间被反复占用与重启）。
⇒ §3.4 如实写「未测得」，不声称已量化。

### 6.3 ⚠️ macrobenchmark 在本仓库仍无一次成功运行

本版实测 `StartupBenchmark`（`MacrobenchmarkRule` + `CompilationMode.Partial`）在 API 24 上**挂死**：
`AndroidJUnitRunner` 打完 `Output Directory: …` 之后 **24 分钟零输出**，
进程 `S (sleeping)` 在 `SyS_epoll_wait`，`mResumedActivity` 是桌面而不是被测应用。

⇒ 冷启动 A/B 改用平台自带的两条命令（`cmd package compile` + `am start -W`），口径写进
`verification/coldstart-ab.sh`。这是**方法替换**，已在 CHANGELOG §8 记为缺口 6。

### 6.4 ⚠️ 平板修复后的真机复测

见 §7 —— 若未完成，如实标注。

---

## 7. 本版改动的真机验证

**见 `verification/` 下的原始数据；本节按实际完成情况填写。**

| 项 | 状态 | 证据 |
|---|---|---|
| 搜索：改动后真机分段数据 | 未测得（§6.2） | — |
| 平板 ⤢：改动后双方向命中区断言 | 见 §7.1 | — |
| 离线索引：注入记录清除 before/after | 见 §7.2 | — |
| release 包装机验证 | 见 §7.3 | — |

### 7.1 平板 ⤢ 复测 —— ❌ **未完成（设备掉线）**

修复已实现（顺序 `Row` + 宽度预算）并有 6 条单测，但**修复后的 EMUI 真机复测没做成**：

```
$ adb devices -l
List of devices attached
0715f763f54c023a  device  ... model:SM_G9209      # S6 在
3B15CD00GB700000  device  ... model:PLC110       # PLC110 在
                                                 # ← WGR-W09 不在列表里
$ adb -s WVQ6R22124000968 install -r app-release.apk
adb: device 'WVQ6R22124000968' not found
```

平板在会话中途**从 USB 掉线**（`adb reconnect offline` 无效，不是 adb 侧问题）。
⇒ 平板上装的仍是 **v2.5.5（vc46）**，v2.5.6 的修复**没有在真机上验证过**。

**这条缺口不能算通过**（新铁律 21 正是为此写的：「平台特定行为必须在目标平台真机验证」）。
复测脚本已经具备（探针 C 的 uiautomator 断言路径），需要的断言是：

1. 平板**竖屏**展开播放器 → a11y 树里 `大屏幕模式` 节点**存在**，
   且其 `bounds` 与 `上一首`/`播放`/`下一首` 的 `bounds` **不相交**（旧实现在此必然相交 65dp）；
2. 点它 → `mOrientation=SENSOR_LANDSCAPE` + 大屏布局生效（截图）；
3. 平板**横屏**回归一次（旧实现本来就通过，用同一套断言防回归）。

### 7.2 离线索引收尾 —— ✅ **完成**（PLC110 / v2.5.6 vc47）

**操作**：设置 → 用户 → 存储与缓存 → **「离线缓存管理」**（只打开，未点任何删除）。

**回读对账**（`verification/offline-BEFORE.xml` / `offline-AFTER.xml`）：

| 项 | BEFORE | AFTER | 判定 |
|---|---|---|---|
| `tracks` 条数 | 59 | **58** | 精确少 1 条 |
| `urls` 条数 | 147 | **147** | **一个都没动** |
| `503616` 在 `tracks` 里 | ✅ 在 | ❌ **已消失** | 注入记录已清除 |
| 管理页显示 | — | 「已缓存曲目（**58**）」 | 与 prefs 自洽 |

⚠️ 与探针的**确定性预测**对比：探针在更早的设备状态（tracks 50 / urls 145）上预测
`50→49 / 145→145`；实际执行时设备已被正常使用（tracks 涨到 59、urls 涨到 147），
**增量完全一致（−1 / 0）** ⇒ 机制判断正确，且**用户真实数据一条没少**。
音频缓存未被触碰（`retain` 只丢索引条目）。

### 7.3 release 包 —— ✅

| 项 | 值 |
|---|---|
| tag | `v2.5.6-gpl`（附注 tag 对象 `d2654ef2` → 提交 `fb2aef1`） |
| draft release | `https://github.com/yaxiaiyuting/Ncrust/releases/tag/untagged-c5b7134f914b447f13a7`（draft 未发布，URL 为 `untagged-…` 属正常） |
| 资产 | `Ncrust-v2.5.6-gpl-release.apk` 10 059 208 B / `-debug.apk` 30 811 502 B / `SHA256SUMS-v2.5.6-gpl.txt` |
| release sha256 | `d56606248d0b16503ed250640558365909c8f27ecc8e9705d83b69667fa6e97b`（与 GitHub 侧 digest **一致**） |
| debug sha256 | `bf3a410b61150ce3aa53d8bb7970fcf6505b95d5f5224563fb440af20e3b9a06`（一致） |
| badging | `versionCode='47' versionName='2.5.6-gpl'` |
| 签名 | `CN=Ncrust GPL Fork, OU=Personal, O=yaxiaiyuting` / SHA-256 `e75af3ff…5511`（项目既有 key） |
| profile 资产 | `assets/dexopt/baseline.prof` 6464 B + `baseline.profm` 841 B（**仍是手写清单**，见 §6.1） |
| versionCode 三源交叉 | tag `47` / badging `47` / build.gradle `47` ⇒ **一致** |
| 产物源码 == HEAD 源码 | `git diff v2.5.6-gpl^{} HEAD -- app/ benchmark/ build.gradle.kts settings.gradle.kts .github/` **为空**（tag 之后只有 docs 提交） |
| 已发布 tag 是否移动 | **否** —— v2.5.0~v2.5.5 的 peeled commit 与打 tag 前逐条一致（推送时用 `refs/tags/v2.5.6-gpl`，未用 `--force`） |
