# v3.2.1 · 探针摘要（PROBE-SUMMARY）

> 四个探针 + 一个追加探针，全部**先落盘再改代码**（铁律 2）。
> 本文件回答任务书 §10 的第一条验收：「探针 PROBE-SUMMARY.md 完整回答四个探针问题」。

| # | 探针 | 文件 | 回答的问题 |
|---|---|---|---|
| 1 | PlayerCard 结构 | [probe-playercard-structure.md](probe-playercard-structure.md) | 9092 code unit 里有哪些职责块？谁是纯 UI、谁带副作用？9 处 `weight` 在哪？与 TrayLayout / Motion 怎么耦合？ |
| 2 | 拆分方案 | [probe-split-plan.md](probe-split-plan.md) | 拆成几个、按什么维度？状态怎么提升？Modifier 链怎么传？怎么证明等价？ |
| 3 | JIT 对照实验 | [probe-jit-experiment.md](probe-jit-experiment.md) → 结果 [jit-experiment.md](jit-experiment.md) | 怎么把「是不是 JIT」变成可判定的问题？判据是什么？ |
| 4 | 方法大小监控 | [probe-method-size.md](probe-method-size.md) → 报告 [method-size-report.md](method-size-report.md) | 怎么监控单方法 code unit？阈值多少？怎么报警？ |
| 5 | 追加 · B站扫码登录 | [probe-bili-qr-broken.md](probe-bili-qr-broken.md) | 「打开即失效」是接口、请求头、状态机、渲染、轮询还是异常吞掉？ |

---

## 1. PlayerCard 结构（探针 1 的四条硬结论）

**Q：9092 code unit 里有哪些职责块？**
A：12 块（签名 / 状态 / 几何谓词 / 动效与封面持有 / 阈值门 / 手势工厂 / 歌词自动切换 /
面板动画 / 命中谓词 / 根 Box 手势 / 背景层 / 三个布局分支 + 三个叠加层）。

**Q：哪些纯 UI、哪些带副作用？**
A：**副作用全部集中在 155–836 行**（`MotionClock.clear` / `snapshotFlow` 复位 / 两个手势 /
4 个 `LaunchedEffect` / 唯一的帧时钟）；838 行之后基本是「读状态、画树」。
⇒ 拆分的安全切法就是「**在 838 之后切**」，副作用块一行不动。

**Q：9 处 `weight` 在哪？**
A：源码 1132 / 1170 / 1227 / **1251** / 1326 / 1583 / 1667 / 1685 / 1764（与崩溃报告的 dex
地址表逐行核对一致）。**只有 1251 是动态值**（`wideLeftFraction`），而它**没有**下限保护 ——
右栏 1326 有。这正是铁律 26 的由来。
另外 4 处 `ColumnScope.weight`（1141/1233/1260/1382）与 2 处落在 lambda 里的
`RowScope.weight`（1054/2148）不在 `PlayerCard` 方法体内。

**Q：与 TrayLayout / Motion 的耦合？**
A：TrayLayout 7 处（高度 / 封面起始位 / 三行间距 / 顶栏高 / 文本起始位 / 控制区占位宽 /
三个落点中心），**全部只能通过 TrayLayout 通信**（新文件里不许出现字面量 16/56/80/2）；
Motion 有 8 个交互点，其中三条是硬约束：`MotionFrameClock` **恰好挂载一次**、
帧路径的量必须在 `graphicsLayer` 块内读、三个挂载判据不得退化成 `alpha = 0`。

## 2. 拆分方案（探针 2 的选项裁决）

| 候选维度 | 裁决 |
|---|---|
| 按 UI 区块 | ⚠️ 部分采纳（托盘 / 控制 / 面板确实是独立区块，但「封面区」在三分支里落点不同） |
| **按状态域** | ❌ **否决**：状态跨区块共享，按状态域拆必然把 `remember` 搬进子组件 ⇒ 生存期跟着挂载条件走 ⇒ 行为不等价（直接违反铁律 25） |
| **按屏幕形态（三个分支）** | ✅ 主维度：三分支运行期互斥、源码本来就是 `when` 的分支，整块搬移 = 组合结构逐节点等价 |
| **叠加层（托盘 / 封面 / 收起键 / 背景）** | ✅ 次维度：与分支正交、互相独立 |

状态提升的结论是「**什么都不提升**」：全部 `remember`/`Animatable`/`derivedStateOf` 留在
原处（最后收进 `PlayerCardUiState`，生存期与读取位置不变），唯一搬走的是
`queueHeaderHeightPx`（它本来就长在 lambda 里）与 `autoSwitchedForSong`（仍是 `remember(songId)`）。

## 3. JIT 对照实验（探针 3 的判据 + 实际结果）

设计里先写死了判定表（避免事后解释），实际执行后落在这一行：

> **A 不崩（6 次全绿）⇒ 实验不成立 —— 本轮未能复现，结论只能写「未能复现」，不得推断 H1。**

真实执行：**12 次冷启动尝试（含重启后的绝对冷启动）0 次崩溃** ⇒ **未能复现**，
「是不是 JIT 代码生成问题」**本版没有结论**。B/C/D 三步没有执行
（基线不成立时它们只能得到同样的「不崩」，无法与 A 区分）。
详见 [jit-experiment.md](jit-experiment.md)。

## 4. 方法大小监控（探针 4 的选型与落地）

| 方案 | 裁决 |
|---|---|
| R8 `mapping.txt` | ❌ 单独用不行（**没有指令数**）；但它能做**反查**，所以作为辅助输入保留 |
| `dexdump -d` | ✅ 能给出 `insns size`（9092 与自研解析一致），但 v3.2.0 的 release dex 全量反汇编 **333 万行**，文本解析慢且脆 ⇒ 只当**交叉验证**手段 |
| 编译期 Gradle 插件 | ❌ 不采纳：class 层字节码 ≠ dex code unit；且给每次构建加一个失败面 |
| **自研 DEX 解析** | ✅ 采纳：读 `code_item.insns_size`，纯标准库约 250 行，同一进程里做 mapping 反查 |

阈值 **2000**（预警 1200）；报警 = 退出码 1 + Top-N 报告；集成 = 本地脚本（发布门禁）+
`--json` 归档 + `--baseline` 逐方法对比。落地后立刻抓到：`PlayerCard` 9092（拆分前）→ 1972（拆分后），
以及**仍然超阈值的 7 个方法**（本版范围外，如实列出）。

## 5. 追加探针 · B站扫码登录「打开即失效」

**Q：接口调了吗？返回了什么？请求头全吗？**
A：**调了、成功了、头是全的** —— 在线抓取 `generate` 得到 `HTTP 200 + code:0 + data.qrcode_key/url`；
UA / Referer / Origin 三个头都在发（`BiliApi.kt:335-337`）。**排除了「缺 header 被拒」这个方向**。

**Q：状态机初始态？渲染失败？轮询过早？异常吞掉？**
A：状态机初始 `IDLE`，**没有**"请求前就 EXPIRED"；轮询码表正确（`86101`/`86038` 实测抓取）；
**真正的问题是顺序**：界面把「申请」「轮询到终态」串成一次阻塞调用，
位图被排在 `start()` **返回之后** ⇒ 180 秒内不可能出现；
而二维码区把「非 LOADING 且无位图」一律显示成「已失效」（首帧 `IDLE` 就命中）。
位图生成失败被 `runCatching{}.getOrNull()` **静默吞掉**。

**Q：为什么 QQ 正常？**
A：QQ 用**两个** `LaunchedEffect`（申请 / 轮询分离，轮询以 `qr` 为 key）；
B站 合成了一次调用 —— 这是「为什么 QQ 正常而 B站异常」的答案（探针 §9 的 7 条逐条对照）。

**根因一句话**：**位图生成被排在"轮询到终态"之后，而二维码区把"还没有位图"显示成「已失效」。**

**修法与真机验证**：`prepare → 渲染 → poll`（状态机新增 `poll(onState)`）+ 三档文案分离
（加载中 / 已失效 / 明确失败）。真机（S6）已见二维码 + 日志顺序
`二维码已渲染` → `开始有界轮询`，见 [REAL-DEVICE.md](REAL-DEVICE.md) §1。
