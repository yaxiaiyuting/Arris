# v3.2.1 探针 3 · JIT 对照实验设计（C 项）

> 目的：把「`invalid weight 0.0` 到底是不是 **ART JIT 代码生成**问题」变成一个**可判定**的问题，
> 而不是一句推测。本文件先落盘设计（铁律 2），执行结果落 [jit-experiment.md](jit-experiment.md)。

---

## 0. 待判定的假设

崩溃报告 §4/§5 给出的矛盾点：

> 设备上那个 APK 的字节码里，`PlayerCard` 的 9 处 `weight()` 实参**没有任何一处可能为 0.0**
> （唯一能算出 0.0 的 `1f - wideLeftFraction` 已有 `coerceAtLeast(0.0001f)`），
> 而运行时抛出的正是 `invalid weight 0.0`。

候选解释（本实验要区分的就是它们）：

| # | 假设 | 若成立的预期观测 |
|---|---|---|
| H1 | **ART JIT 代码生成缺陷**（巨型方法 + 高寄存器压力下编译出错） | 关闭 JIT（解释执行）后**不再崩**；全量 AOT 后仍崩或缓解 |
| H2 | **AOT/dex2oat 产物问题**（安装时 `interpret-only`，某些路径下编译错误） | 重装 / `cmd package compile -m speed -f` 后行为改变 |
| H3 | **R8 产物或源码逻辑问题**（确定性错误） | 任何运行态下都**稳定复现**，与 JIT/AOT 无关 |
| H4 | 环境/时序问题（与代码生成无关，例如某种状态竞态被误读成 weight 0） | 四个步骤的复现率没有显著差异 |

---

## 1. 实验前置条件（必须逐条确认，否则结论不成立）

| 项 | 值 | 确认方式 |
|---|---|---|
| 设备 | SM-G9209（zerofltctc）· Android 7.0 · API 24 · arm64-v8a · Magisk root | `adb shell getprop ro.product.model` / `su -c id` |
| 被测 APK | `dist/Ncrust-v3.2.0-gpl-release.apk`（md5 `e3fe3b17f97f1f565c7cf5067f447b09`） | `md5sum` |
| 安装态与被测 APK 一致 | 设备上 `base.apk` 与该 APK 逐字节一致 | `adb shell su -c md5sum /data/app/.../base.apk` |
| `mapping.txt` 留档（**铁律 27**） | `app/build/outputs/mapping/release/mapping.txt` → 归档到 `docs/verification/v3.2.1/mapping-v3.2.0.txt` | 文件存在 + 行数 |
| 复现动作 | 冷启动 → 起播 → 点首页列表里**不在当前队列**的一首歌（触发 `replaceQueueAndPlay`） | 脚本 `jit_experiment.py` |
| 屏幕 | 不锁屏（重启后能直接 `am start`） | 重启一轮后能拿到 UI dump |

---

## 2. 四个步骤（每一步 N=6 次冷启动尝试）

### 步骤 A · 正常 JIT（对照基线）

```bash
# 不改任何运行时属性
for i in $(seq 6); do
  adb logcat -c && adb logcat -b all -v threadtime > A-$i.txt &
  adb shell am force-stop com.takahashirinta.ncrust
  adb shell am start -n com.takahashirinta.ncrust/.MainActivity
  sleep 7
  adb shell input keyevent 126          # 起播
  sleep 4
  adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml
  # 点第 3 个可点歌曲行（第 1 行往往就是当前播放的那首，不会走 replaceQueueAndPlay）
  adb shell input tap <x> <y>
  # 等崩溃或超时 26s（JIT 编译发生在起播后 ~8s）
done
```

**判定输入**：`A_normal-attempt*.txt` 里是否出现 `FATAL EXCEPTION`。

### 步骤 B · 关闭 JIT（解释执行）

```bash
adb shell su -c 'setprop dalvik.vm.usejit false'
adb shell su -c 'setprop dalvik.vm.usejitprofiles false'
adb reboot          # ← 必须重启：dalvik.vm.* 由 zygote 在启动时读取，
                    #   setprop 之后不重启，新进程仍然是 JIT 的
# 等 sys.boot_completed=1，解锁，然后重复步骤 A 的循环
```

**注意**：`dalvik.vm.usejit=false` 只停用 JIT；已经生成的 `.odex/.vdex` 仍然生效
（本机安装态是 `interpret-only`，所以这一步等价于「完全解释执行」）。

### 步骤 C · 恢复 JIT + 全量 AOT 编译

```bash
adb shell su -c 'setprop dalvik.vm.usejit true'
adb shell su -c 'setprop dalvik.vm.usejitprofiles true'
adb reboot
adb shell su -c 'cmd package compile -m speed -f com.takahashirinta.ncrust'
adb shell su -c "cmd package dump com.takahashirinta.ncrust | grep -i status"   # 自证 filter=speed
```

**注意**：`-f`（force）会丢弃既有的 profile 并**全量编译**；这一步把「JIT 现场编译」
换成「安装期 AOT」。若 H1 成立（JIT 编译器出错），这一步应当**不再崩**；
若在这一步也崩，则问题在 R8 产物 / 源码 / AOT 编译器，而不是 JIT 的现场编译。

### 步骤 D · 重装 APK（重新生成 odex/vdex）

```bash
adb install -r -d dist/Ncrust-v3.2.0-gpl-release.apk
# 重复步骤 A 的循环
```

重装会把编译过滤器退回 `pm.dexopt.install`（本机 = `interpret-only`），
即「回到出厂安装态」。它同时排除「上一次安装留下的脏 odex」这一类解释。

---

## 3. 采集方式（三层，互为交叉验证）

| 层 | 命令 | 用途 |
|---|---|---|
| **logcat** | `adb logcat -b all -v threadtime` 全程落盘 | 主证据：`FATAL EXCEPTION` + 反混淆前的栈 + `art: JIT allocated …` 行 |
| **events buffer** | 同上（`-b all` 含 events） | `am_crash` / `am_proc_died` 交叉验证。⚠️ 实测 `logcat -c` **不会清空 events**，陈旧 `am_crash` 会污染判定 —— 必须按设备时间戳过滤（脚本已实现） |
| **dropbox** | `adb shell su -c 'ls -l /data/system/dropbox'` + 需要时 `su -c cat` | 系统级崩溃留档（历史版本 v2.0.0/v2.3.0/v2.5.2/v2.6.2/v3.0.0/v3.1.0/v3.2.0 都有记录） |
| **tombstone** | `adb shell su -c 'ls -l /data/tombstones'` | 本异常是 **Java 异常**，不产生 tombstone；留档以证明「没有 native 崩溃」这一点 |
| **运行时状态** | `getprop dalvik.vm.usejit` / `cmd package dump … \| grep status` | 自证每一步的运行时配置真的生效（否则实验无效） |

---

## 4. 判定标准（先写死，避免事后解释）

| 观测 | 结论 |
|---|---|
| A 崩、**B 不崩**、C 不崩、D 崩 | **H1 成立**：ART JIT 现场编译问题；结构性拆分（B 项）是正解 |
| A 崩、B 崩、C 崩、D 崩 | 与 JIT 无关（H3/H4）：需要按 A 项收紧不变量 + 继续抓 tombstone/simpleperf |
| A 崩、B 崩、C 不崩、D 崩 | H2：编译态相关（AOT 产物 vs 解释执行），拆分仍是正解，但要在 release notes 说明 |
| **A 不崩**（6 次全绿） | 实验**不成立** —— 本轮未能复现，结论只能写「未能复现」，不得推断 H1 |
| 复现率显著低于历史（历史 2/5） | 记录实际复现率，样本不足时**明确标注**，不得把「没崩」当成「修好了」 |

---

## 5. 已知干扰因素（如实列出）

1. **间歇性**：崩溃报告实测 5 次冷启动崩 2 次（≈40%）。6 次尝试在 40% 复现率下
   至少命中一次的概率约 95%，但「一次都没崩」仍有约 5% 概率 —— 所以 N=6 是**下限**，
   若 A 步全绿，应追加轮次而不是直接下结论。
2. **歌曲依赖**：触发点依赖「点一首不在当前队列里的歌」，而首页列表内容与当前队列
   都是设备状态的一部分。脚本在每轮之间不修改队列（除点击本身），但**上一轮的点击会改变下一轮的队列** ——
   因此脚本每次尝试都重新冷启动、并轮换点击行（第 1/2/3 行）以覆盖不同「是否在队列里」的组合。
3. **网络**：起播需要 `SongUrlFetcher` 拿到真实 URL。网络失败的那一轮不会走
   「换队列→歌词回填→重组」那条路径，等于空转。脚本用 `STATE_PLAYING` 行数无法可靠区分，
   故判据只看 `FATAL EXCEPTION`；**空转轮次会稀释复现率**，结论里必须一并说明。
4. **锁屏**：重启后若设备锁屏，`am start` 仍可拉起 Activity，但 `input tap` 会打在锁屏上。
   脚本在 `wait_device()` 里发 `keyevent 82`（menu）解锁；若设备设了 PIN，本实验**无法继续**，
   按「阻塞清单」上报，不伪造结果。

---

## 6. 执行记录

- 脚本：`.scratch/v321/jit_experiment.py`（含设备时钟过滤、每轮落盘 `summary.json`）
- 原始日志：`.scratch/v321/jit/<step>-attempt<i>.txt`（每轮一份完整 logcat）+ `config-<step>.txt`
- 汇总：`.scratch/v321/jit/summary.json`
- 结论：见 [jit-experiment.md](jit-experiment.md)
