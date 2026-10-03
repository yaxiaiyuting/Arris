# probe-tray-regression —— 竖屏播放托盘「上一首按钮」回归探针

> 设备：PLC110（Android 16 / 1272×2800 px @560dpi = 363×800 dp）、SM-G9209（Android 7.0，**PIN 锁屏，仅可 root 读数据**）
> 被测产物：v2.5.4-gpl release，`versionCode = 45`（两台真机均已安装）
> 探针人：本 session（自动）。**本文件所有结论都有命令或 git 对象支撑，没有推断。**

---

## §0 结论先行

**任务书的前提不成立：「上一首按钮」不是 v2.5.4 删掉或挤压掉的 —— 它从来没有在托盘里存在过。**

三条独立证据：

| # | 证据 | 命令 | 结果 |
|---|---|---|---|
| 1 | `PlayerCard.kt` 全历史里从未出现过 `SkipPrevious` | `git log --oneline -S "SkipPrevious" -- app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt` | **零个提交**（空输出） |
| 2 | v2.5.3 的托盘源码只有两个按钮 | `git show v2.5.3-gpl:…/PlayerCard.kt \| sed -n '/迷你播放栏叠加层/,/唯一封面叠加层/p'` | 只有 `onPlayPause()` 与 `onPlayNext()` |
| 3 | v2.5.3 → v2.5.4 的托盘 diff **没有删任何按钮** | `git diff v2.5.3-gpl..v2.5.4-gpl -- …/PlayerCard.kt` | 新增 `TrayLyricLine` + 歌名/作者/音源 Row；`if (miniBarEnabled) { … }` 两个 `MetroIconButton` **逐字未动** |

`SkipPrevious` 在全仓库只出现在 `FullPlayerControls.kt:246` 与 `:361`（全屏播放器控制条），
托盘的 `Row` 里一次都没有。

**因此这不是「回归修复」，而是「补一个从未实现的核心功能」。** 定性差别很大：
- 「回归修复」的验收是「恢复原状」；
- 「补功能」的验收是「三个控制按钮都在、都可用、点击区 ≥48dp、有无障碍标签」。

本次按后者做，并在 `TrayLayoutTest` 里把它钉成**存在性断言**（铁律 19：缺失属 P0）。

### 次要结论（任务书问的第二问）

「是删了还是布局挤压后不可见？」——**两者都不是**。v2.5.4 的改动方向相反：
它给托盘**加了一行**（实时歌词），第二行从「歌名 + 作者 + 音源」变成「歌名 · 作者 · 音源」，
高度仍是 56dp。实测（PLC110，见 §2）两行文本的中心块占 126px / 196px，
上下各余 35px（10dp）——**没有溢出，也没有被裁**，只是余量从 v2.5.3 的充裕变成 10dp。

三行时（本版目标布局）同样算式得 182px / 196px = 上下各 7px（2dp）——
**这才是真正放不下的地方**，也是本版必须加高高度的量化理由（§3）。

---

## §1 方法（可复现）

```bash
cd /home/duanjb666/deepseek/ncrust-gpl/Ncrust

# 1) 按钮存在性的历史检索（-S 是「字符串出现次数变化的提交」，比 grep 历史可靠）
git log --oneline -S "SkipPrevious"      -- app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt
git log --oneline -S "Icons.Default.SkipPrevious" -- app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt

# 2) 当前全仓库 SkipPrevious 落点
grep -rn "SkipPrevious" --include=*.kt app/src/main/java

# 3) v2.5.3 / v2.5.4 的托盘源码对照
git show v2.5.3-gpl:app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt \
  | sed -n '/迷你播放栏叠加层/,/唯一封面叠加层/p'
git diff v2.5.3-gpl..v2.5.4-gpl -- app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt

# 4) 真机截图 + 逐行像素剖面（uiautomator dump 在 PLC110 上被系统 Killed，改用像素测量）
adb -s 3B15CD00GB700000 shell am start -n com.takahashirinta.ncrust/.MainActivity
adb -s 3B15CD00GB700000 exec-out screencap -p > tray.png
python3 - <<'PY'   # 见 probe-raw/tray/measure.py
from PIL import Image; import numpy as np
a = np.array(Image.open('tray.png').convert('RGB'))
for y in range(2380, 2620):
    r = a[y, 210:1000]          # 文本列（避开封面 0..196px 与按钮区）
    print(y, round(r.mean(),1), round(r.std(),1), int(r.max()))
PY
```

### 为什么不用 `uiautomator dump`

PLC110 上 `uiautomator dump` 一律返回 `Killed`（无文件产出），
`exec-out uiautomator dump /dev/tty` 同样被 Killed。改用**逐行像素剖面**：
托盘背景是纯色 `surface = #1A1A1A (26,26,26)`（实测 `std = 0.0`），
文本行的字形像素会把它抬起来，因此「均值/标准差开始偏离 26/0」的行就是文本块的上下沿。
分辨率换算：PLC110 = 560dpi ⇒ **1dp = 3.5px**。

---

## §2 实测数据（PLC110 / v2.5.4 / 竖屏）

原始截图：`probe-raw/tray/03-plc110-v254-current.png`；剖面输出：`probe-raw/tray/03-plc110-tray-profile.txt`

| 量 | 行号（px） | 换算 | 说明 |
|---|---|---|---|
| 托盘上沿 | 2408 | — | 背景从列表内容切到纯 `(26,26,26)` |
| 托盘下沿 | 2604 | — | 之后是底部导航（也是 26,26,26，但 2612 起） |
| **托盘高度** | 2408–2604 = **196px** | **56.0dp** | `.height(56.dp)` × 3.5 |
| 第 1 行（歌词）字形 | 2458–2495 | 10.6dp | `bodySmall`，实测文本「以为你就是这世上最爱我的人」 |
| 第 2 行（歌名·作者·音源）字形 | 2508–2551 | 12.3dp | `bodyMedium` + `bodySmall` |
| 上留白 | 2458−2408 = 50px | 14.3dp | |
| 下留白 | 2604−2551 = 53px | 15.1dp | |

**排版盒高度**（不是字形高度）：`bodySmall` 行高 16sp = 56px，`bodyMedium` 行高 20sp = 70px，
合计 **126px = 36dp**，居中于 196px ⇒ 上下各 35px = **10dp**。

### 横向占用（同屏实测）

| 元素 | 宽度 | 来源 |
|---|---|---|
| 封面占位 | 196px = 56dp | `Spacer(fillMaxHeight().aspectRatio(1f))`，与托盘等高 |
| 文本列水平 padding | 2 × 12dp = 84px | `Column(padding(horizontal = 12.dp))` |
| 播放/暂停按钮 | 48dp = 168px | `MetroIconButton(touchTargetDp = 48.dp)`（Kanesumi） |
| 下一首按钮 | 48dp = 168px | 同上 |
| **文本列可用宽** | 1272 − 196 − 84 − 336 = **656px = 187dp** | |

实测该屏文本行占满 187dp 且**未触省略号**（「怪我太天真」+「苏谭谭」+「qm」）。
若按钮变三个（+168px），文本列降到 **139dp** —— 这一条是 §3 的一个真实取舍，不是估算。

---

## §3 三个按钮要多少宽度 / 高度

### 宽度

48dp × 3 = **144dp**。在 360dp 宽的窄屏上，三个按钮 + 56dp 封面 + 24dp padding
= 224dp，文本列只剩 **136dp**。

**判定：可以接受，但必须让三个文本行都能省略（Ellipsis）而不是被裁。**
任务书 §5.3 问「是否需要更多按钮（如播放模式、添加到下一首）」——
**不做**，量化理由就是上面那 136dp：再加一个按钮文本列只剩 88dp，
「歌名 + 作者 + 音源」在这一宽度下必然两行以上，与「三层布局」直接冲突。
播放模式与「添加到下一首」在全屏播放器里已有入口（`FullPlayerControls` / 歌曲菜单），
托盘的职责是「最小可用的播放控制」，不是控制面板。

### 高度

三行排版盒合计：

| 行 | 样式 | 行高 | px（560dpi） |
|---|---|---|---|
| 1 歌词 | `bodySmall` 12sp | 16sp | 56 |
| 2 歌名 | `bodyMedium` 14sp | 20sp | 70 |
| 3 作者 + 音源 | `bodySmall` 12sp | 16sp | 56 |
| **合计** | | **52sp** | **182** |

| 托盘高 | px | 剩余留白 | 每侧 |
|---|---|---|---|
| 56dp（现状） | 196 | 14px | **7px = 2dp** ← 贴死，视觉上是「挤在一起」 |
| 72dp（任务书下界） | 252 | 70px | 10dp |
| **80dp（本版选定）** | 280 | 98px | **14dp** |

**取 80dp**：72dp 的 10dp 每侧在行间再加 2dp 间距后只剩 8dp，与第 1 行的
`bodySmall` 视觉重心（字形只占行高的 ~60%）叠加后仍然发紧；80dp 与 v2.5.0 起的
「封面圆角/描边按尺寸分档」是同一类判断 —— 留白要按**行高**而不是按字形算。
80dp 同时是任务书给的上界，不越界。

### 连带的三处消费者（**这是本版真正的回归面**）

`56.dp` 这个字面量在本仓库有**三个**消费者，改一处不动其余两处 = 托盘与卡片错位：

| # | 位置 | 用途 | 不改的后果 |
|---|---|---|---|
| 1 | `PlayerCard.kt:1366` `.height(56.dp)` | 托盘本体高度 | — |
| 2 | `MainActivity.kt:883` `miniBarHeightPx` | 算 `collapsedOffsetY = contentHeight − sysNav − navBar − miniBar − sysStatus` | 卡片收起时整体下移 24dp 少 ⇒ **托盘上沿与卡片底沿差 24dp**，露出 24dp 的空白带；死带与命中区判定（`PlayerCard` 的 `collapsedHitGate` / `isOverCardVisibleArea`）同时错位 |
| 3 | `BottomOverlayInset.kt:22` `144.dp / 64.dp` | 所有 `LazyColumn` 的 `contentPadding(bottom)` | **列表最后一项被托盘盖住 24dp**（窄屏 144→168，宽屏 64→88） |

第 4 处是**派生量**，不是独立消费者，但同样要跟着改：

| 4 | `PlayerCard.kt:268` `miniCoverHalfPx = 28.dp` | 唯一封面 overlay 缩放/位移的落点中心 | 封面落点比托盘中心高 12dp ⇒ 收起态封面明显偏上 |

> 这正是 v2.5.2 规则 2 的形状：**同一个渲染常量出现第二个消费者时就必须抽成唯一落点**。
> 本版把它抽成 `ui/player/TrayLayout.kt`（纯逻辑、JVM 可单测），三处消费者全部改为读它。

---

## §4 与任务书前提的偏差（逐条）

| 任务书原文 | 实测 | 处置 |
|---|---|---|
| §2.3「上一首按钮是删了，还是布局挤压后不可见？」 | **都不是**：`SkipPrevious` 在 `PlayerCard.kt` 的全历史里零命中，v2.5.3 的托盘也只有两个按钮 | 改按「补功能」做，并在 release notes 里写明这是**补**不是**修**；`TrayLayoutTest` 用存在性断言钉住 |
| §5.4「明确 v2.5.4 上一首按钮消失的原因」 | 同上，不存在「消失」 | 如实记录，不编造根因 |
| §5.3「确认是否需要更多按钮」 | 不需要：三个按钮已经吃掉 144dp，文本列剩 136dp | 记录量化理由；播放模式/添加到下一首留在全屏播放器 |
| §5.2「三层垂直间距统一走 AppShapes / AppMotion 规范」 | `AppShapes` 是**圆角** token、`AppMotion` 是**动效** token，**两者都不含间距**；本仓库没有 spacing token 文件 | 见 `probe-tray-layout.md` §5：不为单一消费者新建全局 spacing 体系（v2.5.2 规则 2 的反向用法），行间距留在 `TrayLayout` 里并逐值标注 |

---

## §5 未验证 / 阻塞

| 项 | 状态 | 原因 |
|---|---|---|
| SM-G9209（Android 7.0，API 24）**界面**层托盘验证 | **阻塞** | 设备处于 **PIN 锁屏**。`su` 可用（Magisk，`uid=0`），但 `locksettings` 二进制在 Android 7 上不存在，`settings put secure lockscreen.disabled 1` 无效，`wm dismiss-keyguard` 无效。**不会**用 root 删除 `/data/system/gatekeeper.*`（那会破坏用户设备的锁屏口令） |
| 同上，**数据**层验证 | 可做（用 root 读 prefs + logcat） | 见 `EVIDENCE.md` |
| 平板竖屏托盘 | 见 `EVIDENCE.md`（平板 AVD） | WGR-W09 未接入本机（`adb connect WVQ6R22124000968` → `failed to resolve host`） |

**替代方案**：API 24 的界面层验证改用本机既有 AVD `ncrust_api24`
（Nexus 5 / 1080×1920 @480dpi / Android 7.0 google_apis x86_64）——
它是**同一 API 级别**的模拟器，能覆盖「API 24 上 `Strings` 类加载 + Compose 渲染 + 命中测试」，
但**不能**替代「S6 真机触摸」这一条。该差距如实写进 `EVIDENCE.md` 与 release notes。
