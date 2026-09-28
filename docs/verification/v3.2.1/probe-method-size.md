# v3.2.1 探针 4 · 方法大小监控机制

> 结论先行：**自研 DEX 解析脚本**（`tools/method-size/method_size.py`），阈值 **2000 code unit**，
> 报警 = 退出码 1 + Top-N 报告，集成方式 = 本地检查（release 构建后一条命令）+ 本文档规定的发布门禁。
> 不引入编译期 Gradle 插件（理由见 §2.3）。

---

## 1. 为什么必须监控「单方法 code unit」

| 事故 | 症状 | 静态源码能不能提前发现 |
|---|---|---|
| v2.0.0 · HF1 | 往 `Strings` 主构造器加字段 → 某方法参数寄存器撞 dex 255 → **真机启动才崩** | ❌ 编译器不报错，单测全绿 |
| v3.2.0 | `PlayerCard` 单方法 9092 code unit / 191 寄存器 → ART 为它分配 **45MB** 编译、JIT 后 8.2s 出现 `invalid weight 0.0` | ❌ 源码 2290 行「看起来只是个长文件」 |

两起事故的共同点：**规模问题只在编译产物里可见**。所以监控必须落在 **dex** 上。

---

## 2. 工具选型

### 2.1 候选对比（实测，非纸面评估）

| 方案 | 能否给出单方法 code unit | 实测结论 |
|---|---|---|
| **R8 `mapping.txt`** | ❌ **不能** | `mapping.txt` 只有「原始名 → 混淆名 + 行号区间」，**没有指令数/大小**。它能做的是**反查**（把 `R4.b1.c` 还原成 `PlayerCardKt.PlayerCard`），所以它是本工具的**辅助输入**，不是数据源 |
| **`dexdump -d`** | ✅ 能（`insns size : 9092 16-bit code units`） | 可用，但：release dex 全量反汇编 **333 万行**（v3.2.0 实测），文本解析慢且脆；不同 build-tools 版本输出格式有差异。**作为交叉验证手段保留** |
| **`dexdump`（不带 `-d`）** | ✅ 能 | 输出小（只打头部），但把「类 → 方法」的归属关系丢了，且仍然要文本解析 |
| **编译期 Gradle 插件**（ASM / R8 API） | ✅ 能（在 class 层） | ❌ 不采纳：class 层的字节码大小 ≠ dex code unit（`const` 合并、`invoke` 重编码、寄存器分配都会改变大小）；而且给构建加一个自定义插件 = 给每次构建加一个失败面 |
| **自研 DEX 解析**（本轮采纳） | ✅ 能（读 `code_item.insns_size`） | ✅ 采纳：DEX 的 `code_item` 是**定长头部 + `insns_size`**，直接读偏移最稳；纯标准库 ~250 行；同时能在同一进程里做 `mapping.txt` 反查 |

### 2.2 交叉验证（两种独立手段给出同一个数）

```
$ dexdump -d classes.dex | grep -A6 "name.*'c'" | grep "insns size"
    insns size    : 9092 16-bit code units

$ python3 tools/method-size/method_size.py dist/Ncrust-v3.2.0-gpl-release.apk --only ncrust --top 1
      9092    191  com.takahashirinta.ncrust.ui.player.PlayerCardKt.PlayerCard
```

两者一致 → 解析器可信。**这一步是必需的**：自己写的解析器必须有独立实现对照，
否则「监控工具本身错了」会静默变成「一切正常」。

### 2.3 为什么不做成 Gradle 插件

1. 构建期失败面：release 构建已经承载签名/混淆/baseline profile 三件事，再挂一个
   自定义分析任务，一旦它误报就会阻塞发布；
2. 自研解析器要在**发布门禁**上跑，而不是在每次增量构建上跑 —— 它读的是**已签名的产物**，
   这正是「HEAD == 产物源码」要校验的那个文件；
3. 真要接 CI，接在**构建之后**（一条 `python3 …` 命令 + 退出码）比接进 Gradle 更简单，
   也不需要 runner 装 Android SDK。

---

## 3. 阈值

| 档位 | 值 | 含义 |
|---|---|---|
| **硬阈值（报警）** | **2000 code unit** | 铁律 24。超过 = 必须拆分 |
| 预警 | 1200 code unit | 报告里单列「接近阈值」，提醒下一版加字段/加分支前先拆 |
| 参考上限 | dex `insns_size` 是 `u4`（理论 4G）；ART 的 `compiler` 对巨型方法的内存分配与编译时间随规模非线性增长（v3.2.0：9092 → 45MB） | — |

**2000 的依据**：v3.2.0 拆分前的 `PlayerCard` = 9092（**4.5 倍阈值**）；
拆分后本轮所有 PlayerCard 子 composable 实测值见 [method-size-report.md](method-size-report.md)。
2000 相当于「一个 200~300 行的树构建函数」，是「人还能读懂 + R8 不会内联 + ART 编译廉价」的交集。

---

## 4. 报警方式

```bash
$ python3 tools/method-size/method_size.py app/build/outputs/apk/release/app-release.apk \
      --mapping app/build/outputs/mapping/release/mapping.txt \
      --only com.takahashirinta.ncrust --top 20 --json /tmp/method-size.json
❌ 3 个方法超过 2000 code unit（铁律 24：巨型 composable 必须拆分）
$ echo $?
1
```

- **退出码 0/1/2**：0 = 全部达标；1 = 有方法超阈值（CI 直接用）；2 = 输入/解析错误。
- `--only <子串>`：只看本应用代码（库里的 `PDF417Common.<clinit>` 11178、`MatroskaExtractor.read`
  5276 这些**我们改不了**，混在报告里会让报警失去意义）。默认不带 `--only`，全量列 Top-N。
- `--baseline <json>`：与上一版报告逐方法对比，打印 `±code unit`（用于确认「拆分后确实降下来了」
  而不是「换个名字又长回去了」）。
- `--mapping <txt>`：把混淆名还原成源码名。⚠️ R8 会把**内联链**上的行号全写进同一行
  （`PlayerCard` 的 mapping 行有 808 条候选），所以工具做了两步收敛：属主匹配 + 库/合成名降级 +
  保持 mapping 原始顺序；**类名是权威的，方法名是最佳近似**（这一条写在工具的 docstring 里）。

---

## 5. 集成方式

| 场景 | 做法 |
|---|---|
| **发布前（硬门禁）** | `assembleRelease` 之后跑一次，把 `--json` 报告归档到 `docs/verification/<版本>/method-size-report.json` |
| **本地日常** | 同上，一条命令 |
| **CI** | 未接（本仓库的 workflow 只有 baseline profile；且托管 runner 没有签名密钥）。接的话就是「构建之后加一步脚本」，退出码即结论 |
| **回归对比** | 用上一版的 JSON 作 `--baseline` |

**明确不做**：不把阈值检查挂进 `check`/`assemble` 任务图（一次误报会挡住整条发布链路）；
不监控库代码（改不了的数字进报告只会稀释信号）。

---

## 6. 本轮基线与结论

拆分前的全量实测（release dex，32080 个带代码的方法）：

| 排名 | code unit | 寄存器 | 方法 |
|---|---|---|---|
| 1 | 11178 | 2 | `com.google.zxing.pdf417.PDF417Common.<clinit>`（库） |
| 2 | **9092** | **191** | **`com.takahashirinta.ncrust.ui.player.PlayerCardKt.PlayerCard`** |
| 3 | 8584 | 9 | `androidx.media3…DefaultBandwidthMeter`（库） |
| 4 | **7317** | **143** | `com.takahashirinta.ncrust.MainActivityKt.MainScreen` |
| 8 | 3362 | 68 | `PlayerCardKt$PlayerCard$…$invoke`（PlayerCard 内的 lambda） |
| 9 | 3026 | 359 | `…ui.settings.SettingsRegistry.<init>` |
| 13 | 2795 | 125 | `…ui.player.FullPlayerControlsKt.FullPlayerControls` |
| 14 | 2744 | 89 | `…ui.player.LyricsViewKt.LyricsView` |

`--only com.takahashirinta.ncrust` ⇒ **6335 个方法、8 个超阈值、28 个预警**。

> 本轮 P0 只修 `PlayerCard`（任务书范围）。`MainActivityKt.MainScreen`(7317)、
> `SettingsRegistry.<init>`(3026)、`FullPlayerControls`(2795)、`LyricsView`(2744)
> **仍然超阈值**，已记入遗留风险清单 —— 它们不在本版范围内，但监控机制从此会一直报出来。
