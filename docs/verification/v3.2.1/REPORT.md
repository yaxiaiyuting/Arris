# v3.2.1-gpl 交付报告

> 目标发布：**v3.2.1-gpl**（`versionCode 56`）· 基线 v3.2.0-gpl（HEAD `122ab69`）
> 交付日期：2026-09-28 · 设备：SM-G9209（Android 7.0 / API 24 / arm64-v8a / Magisk）
> 全部证据目录：[`docs/verification/v3.2.1/`](.)

---

## 1. 修改摘要

| 范围 | 做了什么 | 关键提交 |
|---|---|---|
| **P0 · PlayerCard 拆分** | 单方法 **9092 → 1972** code unit（寄存器 191 → 99）；拆成 15 个 composable，**家族内 >2000 的方法数 = 0** | `perf(player): PlayerCard 巨型 composable 拆分` |
| **P0-A · 对称保护** | 分栏比例左右两侧对称加正下限 + **显式挡 NaN**；新增对称出口 `wideRightFraction` | `fix(player): 分栏比例的对称正下限` |
| **P0 · B站扫码登录** | 「打开即失效」根因修复：**申请 → 渲染 → 再轮询**；二维码区文案分三档 | `fix(bili): 扫码登录「打开即失效」` |
| **C · JIT 对照实验** | 按预设判据执行 → **未能复现**，实验不成立（如实落盘） | `docs(v3.2.1): C 项 JIT 对照实验结果` |
| **方法大小监控** | 自研 DEX 解析脚本（阈值 2000 / 退出码报警 / mapping 反查 / `--baseline`） | `chore(tools): 新增单方法 code unit 监控脚本` |
| **AGENTS.md** | 新增铁律 24/25/26/27 + 方法体预算实测表 | `build: 升级至 v3.2.1-gpl …` |

改动文件：`ui/player/` 新增 7 个文件、`bili/BiliQrLogin.kt`、`ui/components/BiliQrLoginDialog.kt`、
`ui/components/QrLoginDialog.kt`（只补日志）、`tools/method-size/`、`docs/verification/v3.2.1/`、
`app/build.gradle.kts`、`AGENTS.md`。**华为/荣耀卡片零改动**（`git diff --stat` 可自证）。

## 2. 探针结论（摘要，细节见 [PROBE-SUMMARY.md](PROBE-SUMMARY.md)）

- **结构**：9092 code unit 里 12 个职责块；**副作用全部集中在 155–836 行** ⇒ 拆分在 838 之后切；
  9 处 `RowScope.weight` 中**只有 1251 是动态值且没有下限保护**（右栏 1326 有）—— 铁律 26 的由来。
- **方案**：按「状态域」拆**被否决**（会改 `remember` 生存期）；最终按「三分支 + 三叠加层 +
  状态/几何/副作用」拆，**状态一个都不搬**。
- **JIT**：判据先写死；实际 **0/12 复现** ⇒ 实验不成立，无结论。
- **方法大小**：自研 DEX 解析（与 `dexdump` 交叉验证同一个数），阈值 2000、预警 1200。
- **B站**：接口被调用且成功（在线抓取 `HTTP 200 + code:0`）、请求头完整 ⇒ **根因在 UI 的调用顺序**
  （位图生成被排在轮询终态之后）+ 文案把「没有位图」显示成「已失效」。

## 3. A / B / C 实现说明

### A（保险，先做）
`PlayerLayout.MIN_SPLIT_FRACTION = 0.0001f` 成为唯一下限；`wideLeftFraction` 出口夹一次并显式挡 NaN；
新增对称出口 `wideRightFraction`；调用点两处都走同一个常量。
单测覆盖 `0/0.5/1/负数/超1/±Inf/NaN`。**验收：任何输入下左右权重都 > 0 且非 NaN**（`PlayerLayoutTest` + `PlayerCardGeometryTest`）。

### B（拆分，核心）
见 [probe-split-plan.md](probe-split-plan.md) 与 §4 的对比表。行为等价三层证据见 [EQUIVALENCE.md](EQUIVALENCE.md)：
**真机 7/7 形态逐节点完全一致** + 2126 个 JVM 用例全绿。

### C（JIT 对照实验）
见 [jit-experiment.md](jit-experiment.md)。**结论：未能复现，实验不成立**；B/C/D 三步未执行，
理由写在文档里（基线不成立时它们无法与 A 区分，把"不崩"写成证据就是伪造结论）。

## 4. 拆分前后对比

| 指标 | v3.2.0 | v3.2.1 | 变化 |
|---|---|---|---|
| `PlayerCard` code unit | 9092 | **1972** | **−78.3%** |
| `PlayerCard` 寄存器 | 191 | 99 | −92 |
| PlayerCard 家族 > 2000 的方法 | 1 | **0** | — |
| 家族最大方法 | 9092（PlayerCard） | 1735（NarrowLayout） | — |
| 全 app 超阈值方法（含库） | 45 | 44 | −1 |
| 播放器卡片文件数 | 1（2290 行） | 8（拆分后） | — |
| 真机 `invalid weight` 崩溃 | 5 次冷启动崩 2 次 | **10/10 无崩溃** | — |

## 5. JIT 实验结论

**「`invalid weight 0.0` 是 ART JIT 代码生成问题」——本版没有结论**（12 次尝试 0 复现）。
本版不把「没能复现」包装成「已修好」；修复依据是两条可复现的静态证据
（9092 code unit / 45MB 编译 → 1972；唯一动态权重的对称保护）。

## 6. 方法大小报告

见 [method-size-report.md](method-size-report.md)（含拆分后家族逐个实测 + 仍超阈值的 7 个方法清单）。
原始 JSON：[method-size-report.v3.2.1.json](method-size-report.v3.2.1.json)。

## 7. 关键文件

| 主题 | 文件 |
|---|---|
| 播放器卡片（拆分后 8 个文件） | `ui/player/PlayerCard.kt`（入口 1972）· `PlayerCardExpanded.kt` · `PlayerCardLayouts.kt` · `PlayerCardTray.kt` · `PlayerCardOverlays.kt` · `PlayerCardBackdrop.kt` · `PlayerCardEffects.kt` · `PlayerCardState.kt` |
| 对称下限 | `ui/player/PlayerLayout.kt`（`MIN_SPLIT_FRACTION` / `wideLeftFraction` / `wideRightFraction`） |
| 几何纯函数 | `ui/player/PlayerCardState.kt`（`playerCardGeometry` / `PlayerCardGeometry`） |
| B站登录 | `bili/BiliQrLogin.kt`（`poll`）· `ui/components/BiliQrLoginDialog.kt`（`biliQrPanel`） |
| 监控工具 | `tools/method-size/method_size.py` |
| mapping 留档（铁律 27） | `docs/verification/v3.2.1/mapping-v3.2.1.txt.gz` + `.sha256`（原始件在仓库外 `dist/mapping/`） |

## 8. 测试证据路径

| 证据 | 路径 |
|---|---|
| JVM 单测（2126 用例） | `app/build/reports/tests/testDebugUnitTest/`（本轮日志 `.scratch/v321/final-test-lint.log`） |
| lint | `app/build/reports/lint-results-debug.html` |
| 真机 10 次冷启动 | `.scratch/v321/coldstart/summary.txt` + `run1..10.txt` |
| B站二维码真机截图/日志 | `.scratch/v321/bili-qr-after.png` + [REAL-DEVICE.md](REAL-DEVICE.md) §1 |
| A/B 快照（7 形态） | `.scratch/v321/ui-{before,after}/` + `shots-{before,after}/` |
| 拆分前后 dex 实测 | [method-size-report.md](method-size-report.md) |
| JIT 实验原始日志 | `.scratch/v321/jit/`、`jit-A/` |

## 9. 发布

见 [RELEASE-STATUS.md](RELEASE-STATUS.md)（tag / APK digest / release 链接或阻塞清单）。

## 10. 遗留风险与未验证项

1. **JIT 归因未证实**（§5）—— 崩溃"不再出现"的证据强度是「10 次冷启动全绿」，不是数学证明。
2. **B站「扫码 → 登录成功」未做真机验证**（需要真实账号扫码）；逻辑层由 33 个 JVM 用例覆盖。
   真机另外实测到：该设备上 `generate` 首次请求耗时约 **60 秒**（网络原因），
   新版本期间显示「加载中…」而不是「已失效」。
3. **`MainActivityKt.MainScreen`（7447）、`SettingsRegistry.<init>`（3026）、
   `FullPlayerControls`（2795）、`LyricsView`（2744）仍超 2000**（本版范围外，报告每次都会列出）。
4. **A/B 快照只覆盖稳定态**，不含首帧与动画中间帧；未做像素级 diff（两次采集的歌不同）。
5. **`MotionFrameClock` 唯一挂载点**没有被自动化断言（见 EQUIVALENCE.md §3）。
6. **`autoSwitchedForSong` 的语义**（换歌重置）由代码结构与单测间接保证，没有专门的 UI 用例。

## 11. 用户可读的更新说明

见 [CHANGELOG-v3.2.1.md](CHANGELOG-v3.2.1.md)。
