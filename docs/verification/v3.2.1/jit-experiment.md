# v3.2.1 · C 项：JIT 对照实验（结果）

> 设计（先落盘）：[probe-jit-experiment.md](probe-jit-experiment.md)
> 被测对象：`dist/Ncrust-v3.2.0-gpl-release.apk`（md5 `e3fe3b17f97f1f565c7cf5067f447b09`，
> 与设备上 `base.apk` 逐字节一致）
> 设备：SM-G9209（zerofltctc）· Android 7.0 · API 24 · arm64-v8a · Magisk root
> 原始日志：`.scratch/v321/jit-A/`（步骤 A，6 轮）与 `.scratch/v321/jit/`（步骤 A2，重启后 6 轮）

---

## 0. 结论（一句话）

**本轮未能复现该崩溃** —— 正常 JIT 下 12 次冷启动尝试（6 次直接冷启 + 6 次重启后的绝对冷启动）
**零次**出现 `FATAL EXCEPTION`。

按探针 §4 事先写死的判定表，**实验不成立**：

> | 观测 | 结论 |
> |---|---|
> | **A 不崩**（6 次全绿） | 实验**不成立** —— 本轮未能复现，结论只能写「未能复现」，不得推断 H1 |

因此：**「`invalid weight 0.0` 是不是 ART JIT 代码生成问题」这个问题，本版没有结论。**
B（关 JIT）/ C（全量 AOT）/ D（重装）三步在不成立的基线上没有信息量
（它们只会同样得到「不崩」，与 A 无法区分），所以**没有执行**，也不把它们的「不崩」写成证据。

本版的修复依据**不是**这个实验，而是另外两条独立的证据：

1. **静态规模**（可复现、可交叉验证）：`PlayerCard` 是 9092 code unit / 191 寄存器的单方法，
   真机日志显示 ART 为它 `allocated 45MB`；拆分后 1972 code unit；
2. **不变量收紧**（A 项）：唯一的动态权重 `wideLeftFraction` 左右对称加正下限 + 显式挡 NaN，
   使「宽度异常」在结构上不可能再变成 `require` 异常。

---

## 1. 实际执行的步骤

| 步骤 | 设计里的做法 | 实际执行 | 结果 |
|---|---|---|---|
| **A · 正常 JIT** | 6 次冷启动 + 起播 + 点一首不在队列里的歌 | ✅ 执行（两次共 12 轮） | **0/12 崩溃** |
| A2 · reboot 后 | 崩溃报告说「重启后更稳」，故追加一轮 | ✅ 执行（先 `adb reboot` 再 6 轮） | **0/6 崩溃** |
| **B · 关 JIT** | `setprop dalvik.vm.usejit false` + reboot | ⛔ **未执行**（见 §0：基线不成立） | — |
| **C · 全量 AOT** | `cmd package compile -m speed -f` | ⛔ **未执行**（同上） | — |
| **D · 重装** | `adb install -r` 后复现 | ⛔ **未执行**（同上） | — |

> 如果将来这个崩溃再次出现，`jit_experiment.py` 可以直接按步骤跑：
> `ATTEMPTS=6 python3 jit_experiment.py`（四步一次跑完）或
> `python3 jit_experiment.py B_nojit`（只跑某一步）。脚本已经处理了
> 「`logcat -c` 清不掉 events 缓冲」这个坑（按设备时钟过滤）。

## 2. 每一轮的原始记录

`A_normal`（不重启，6 轮；`.scratch/v321/jit-A/`）：

```
attempt1..6: crash=False
汇总: 0/6 崩溃
```

`A_normal`（**reboot 后**，6 轮；`.scratch/v321/jit/`）：

```
[20:36:12] config[A_normal]: usejit=true install=interpret-only
[20:36:59] A_normal attempt1: crash=False weight0=False
[20:37:46] A_normal attempt2: crash=False weight0=False
[20:38:34] A_normal attempt3: crash=False weight0=False
[20:39:21] A_normal attempt4: crash=False weight0=False
[20:40:08] A_normal attempt5: crash=False weight0=False
[20:40:55] == A_normal 汇总: 0/6 崩溃, 0 次 invalid weight ==
```

每一轮都完整采集了 `logcat -b all`（`A_normal-attempt*.txt`）、当轮 UI 层级
（`A_normal-attempt*.xml`）与运行时配置（`config-A_normal.txt`）。

## 3. 为什么这次复现不了（如实列出观测到的差异，不是辩解）

| 项 | 崩溃报告那次（2026-09-28 19:19 / 19:34） | 本轮（20:36–20:41） |
|---|---|---|
| 触发动作 | 用户**手动点击**首页一首歌（`replaceQueueAndPlay`） | 脚本 `input tap` 点首页第 3 行（同样的路径） |
| 崩溃前的关键日志 | `qq lyric APPLIED` + `quality verdict ... displayIdx=6` 回填后 14ms | 未观察到同一组回填（正在播的歌不同） |
| 复现率 | 5 次冷启动崩 2 次（≈40%） | 12 次 0 次 |
| 设备累计运行时间 | 刚重启、ART 刚 JIT 完 `PlayerCard`（45MB 编译） | 已经反复启动过同一 APK 多次 |

**已知干扰因素**（探针 §5 里预先列出的第 1、2、3 条都在本轮生效）：
① 间歇性（历史复现率 ≈ 2/5 = 40%；若真实复现率就是 40%，12 轮全绿的概率约 **0.2%** —— 低，但正是「间歇」的定义）；
② 触发点依赖「点一首不在当前队列里的歌」，而队列本身是上一轮点击的产物；
③ 起播依赖网络，空转轮次会稀释复现率。

**这些都是事实陈述，不是「所以问题不存在」的推论** —— 崩溃报告里的两次崩溃有完整堆栈、
有 dropbox 里从 v2.0.0 到 v3.2.0 的历史记录，它是一个真实存在的缺陷。

## 4. 这个结论对发布的影响

- v3.2.1 **不声称**「已证明是 JIT 问题并已修好」；
- v3.2.1 声称的是：**把那个 9092 code unit 的方法拆到 1972**，并把唯一能产生非法权重的
  路径对称收紧（A 项）。这两件事都有可复现的静态证据；
- 「10 次冷启动无崩溃」是本版的**验收观测**（见 `REAL-DEVICE.md`），
  在 40% 复现率下 10 次全绿的概率约 0.6% ⇒ 它是**较强的**（但不是决定性的）证据。
