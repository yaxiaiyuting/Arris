# v3.2.1 · 方法大小报告（铁律 24 的门禁产物）

> 工具：`tools/method-size/method_size.py`（纯标准库 DEX 解析，见
> [probe-method-size.md](probe-method-size.md)）
> 产物：`app/build/outputs/apk/release/app-release.apk`（R8 后的 release dex）
> 反查：`app/build/outputs/mapping/release/mapping.txt`
> 原始报告：[method-size-report.v3.2.1.json](method-size-report.v3.2.1.json)（Top-40 + 全部超阈值项）

```bash
python3 tools/method-size/method_size.py app/build/outputs/apk/release/app-release.apk \
    --mapping app/build/outputs/mapping/release/mapping.txt \
    --only com.takahashirinta.ncrust --top 40 \
    --json docs/verification/v3.2.1/method-size-report.v3.2.1.json
```

---

## 1. P0 目标：PlayerCard

| | v3.2.0（拆分前） | v3.2.1（拆分后） | 变化 |
|---|---|---|---|
| code unit | **9092** | **1972** | **−78.3%** |
| 寄存器 | 191 | 99 | −92 |
| 阈值（2000） | ❌ 超 4.5 倍 | ✅ 通过 | — |

真机侧的直接后果（v3.2.0 崩溃报告）：ART 为这个方法分配 **45MB** 编译、
JIT 完成后 8.2 秒出现 `invalid weight 0.0` 崩溃。1972 与 9092 在编译代价上不是线性关系，
但已回到「单帧可编译」的量级（同 dex 里比它大的方法有 3362/3026/2799…，都在库代码里）。

## 2. PlayerCard 家族（拆分后逐个实测）

| code unit | 寄存器 | 方法 |
|---|---|---|
| **1972** | 99 | `PlayerCardKt.PlayerCard`（入口：参数 + 建状态 + 算几何 + 画根节点） |
| 1735 | 69 | `PlayerCardLayoutsKt.PlayerCardNarrowLayout`（窄屏分支，3 个布局分支里最大的） |
| 1361 | 59 | `PlayerCardLayoutsKt.PlayerCardBigScreenLayout` |
| 1235 | 50 | `PlayerCardLayoutsKt.PlayerCardWideLayout` |
| 751 | 42 | `PlayerCardTrayKt.TrayInfoColumn` |
| 662 | 75 | `PlayerCardExpandedKt.PlayerCardExpanded` |
| 591 | 46 | `PlayerCardOverlaysKt.PlayerCardCoverOverlay` |
| 542 | 42 | `PlayerCardTrayKt.PlayerCardTray` |
| 488 | 33 | `PlayerCardEffectsKt.PlayerCardEffects` |
| 449 | 36 | `PlayerCardBackdropKt.PlayerCardBackdrop` |
| 381 | 38 | `PlayerCardOverlaysKt.PlayerCardOverlayLayers` |
| 317 | 25 | `PlayerCardOverlaysKt.PlayerCardDismissButton` |
| 188 | 25 | `PlayerCardTrayKt.TrayControls` |
| 160 | 25 | `PlayerCardTrayKt.TrayLyricLine` |
| 137 | 22 | `PlayerCardTrayKt.TrayNoSong` |
| 86 | 8 | `PlayerCardKt.AudioVisualizerSlot` |

**家族内 > 2000 的方法数：0**（拆分前 1 个：`PlayerCard` 9092）。

## 3. 全 app 仍超阈值的 7 个方法（**本版范围外，如实列出**）

| code unit | 方法 | 归属 | 说明 |
|---|---|---|---|
| 3362 | `AboutStrings.getAboutSectionCredits`（反查近似） | 本仓库 i18n | R8 把 i18n 组的 getter 合并/内联后的形态 |
| 3026 | `SettingsRegistry.<init>` | 本仓库设置注册表 | 设置项清单，纯数据 |
| 2286 | `Strings.getSource`（反查近似） | 本仓库 i18n | 同上 |
| 2238 | `HctSolver.Y_FROM_LINRGB` | 本仓库取色算法 | 纯数学 |
| 2164 | `MetroThemeKt.getLocalMetroColors` | Kanesumi（外部） | 不在本仓库 |
| 2160 | `MotionClock.generationState` | 本仓库动效 | 帧时钟 |
| 2050 | `PlayerViewModel.romanizedLyrics`（反查近似） | 本仓库 ViewModel | 疑为 lambda 归并 |

> ⚠️ R8 的 `mapping.txt` 会把**内联链**上的原始方法名全部写进同一条目，
> 因此「方法名」是**最佳近似**（工具已按「属主匹配 + 库/合成名降级 + 保持 mapping 顺序」
> 收敛），**类名是权威的**。上面的中文归属按类名给出。
>
> 这 7 个都不在 v3.2.1 的范围内（任务书只点名 `PlayerCard`）。它们从本版起会**每次都被
> 监控报出来** —— 这正是把阈值工具落地的意义：下一个版本做它们时不需要重新发现。

## 4. 全量基线（release dex）

| 指标 | 值 |
|---|---|
| 方法总数（含代码） | 6341 |
| 超 2000 的方法（全量，含库） | 45 → 44（PlayerCard 消失） |
| 超 1200 的预警方法（全量） | 80 → 79 |
| `--only com.takahashirinta.ncrust`：方法数 | 6335 → 6341 |
| `--only` 超阈值 | 8 → 7 |

## 5. 交叉验证（两种独立实现给出同一个数）

```
$ dexdump -d classes.dex | ... grep "insns size"     # build-tools 36.0.0
    insns size    : 9092 16-bit code units            # v3.2.0 的 PlayerCard

$ python3 tools/method-size/method_size.py dist/Ncrust-v3.2.0-gpl-release.apk --only ncrust --top 1
      9092    191  com.takahashirinta.ncrust.ui.player.PlayerCardKt.PlayerCard
```

两者一致 ⇒ 自研解析器可信（否则「监控工具自己错了」会静默变成「一切正常」）。
