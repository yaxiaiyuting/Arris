# 探针 · P0-A：歌词全屏模式左上角迷你封面位置错误

> 版本：v3.2.0 ｜ 采集时间：2026-09-28 18:30–18:40 CST
> 设备：SM-G9209（`0715f763f54c023a`，Android 7.0 / API 24 / 1440×2560 / density **4.0**）
> 取证方式：`adb exec-out screencap -p`（PNG 原图）+ `adb shell uiautomator dump`（节点精确 bounds）
> 原始文件：[`lyrics_now.png`](lyrics_now.png)（整屏）、[`crop_topleft.png`](crop_topleft.png)（左上角 900×500 裁切）

---

## 0. 一句话结论

**不是「位置略有偏差」，而是封面被放进了错误的容器中心。**

歌词全屏模式下，左上角那个迷你封面属于**窄屏顶栏**（高 **56dp**），
但唯一封面 overlay 的落点中心是按**收起态托盘**（高 **80dp**）算的 ——
于是封面比它所在的顶栏**低 12dp**：

- 上沿在状态栏下方 12dp（66dp 的封面有 12dp 悬空在顶栏上沿之下）；
- 下沿**溢出顶栏 12dp**，在标题/作者文字块的左下方孤零零地吊出来。

同时封面的**左边缘压在屏幕 x=0**（`miniCoverCenterX = coverHalfDp()` = 28dp），
而全应用的内容边距是 16dp —— 所以它看上去是「贴在状态栏左下的一个孤块」。

**真机实测差：45px ≈ 11.6dp**（见 §2），与 12dp 的理论差一致。

---

## 1. 任务书 4 问 → 逐条结论

| # | 问题 | 结论 | 证据 |
|---|---|---|---|
| 1 | 当前迷你封面的布局参数（padding、alignment、尺寸） | **尺寸 56dp**（`TrayLayout.COVER_SIZE_DP`）；**水平位置无任何 padding**（`miniCoverCenterX = coverHalfDp()` = 28dp ⇒ 左边缘 x=0）；**垂直中心 = 状态栏下沿 + 40dp**（`TrayLayout.coverCenterOffsetDp() = HEIGHT_DP/2`） | `PlayerCard.kt:312-317`、`TrayLayout.kt` 的 `COVER_SIZE_DP` / `coverHalfDp()` / `coverCenterOffsetDp()` |
| 2 | 与设计规范（AppShapes / 间距 token）的差异 | **两处**：① 全应用内容边距是 **16dp**（`ResponsiveContent`、搜索框 `padding(horizontal = 16.dp)`、设置行），而封面是 **0dp**；② 展开态顶栏高 **56dp**，封面却按 **80dp** 容器的中心摆 | 搜索页 uiautomator 实测：Tab 行左边界 `x=64px = 16dp`；`PlayerCard.kt:1333` 顶栏 `.height(56.dp)`；`PlayerCard.kt:546` `topBarBottomPx = statusBarPx + 56.dp` |
| 3 | 是否所有屏幕尺寸都错，还是特定尺寸 | **只有窄屏（竖屏 / 手机）错**。宽屏走 `usesSideCover` 分支，封面落点由左栏封面区**实测**得到（`wideCoverCenter`），不经过 `miniCoverCenterY`。本设备是窄屏 | `PlayerCard.kt` 的 `if (usesSideCover) … else …` 分支 |
| 4 | 是否与状态栏 inset 有关 | **间接有关**，但不是根因。两侧都以状态栏下沿为原点（托盘用 `statusBarsPadding()`、overlay 用 `WindowInsets.statusBars`），**原点一致**；差的是「加多少」。真机实测顶栏与封面的实际像素差正好等于 12dp，说明原点没有偏移、偏移量就是容器高度差的一半 | §2 |

---

## 2. 真机实测（原图测量，不是推断）

`uiautomator dump`（精确 bounds，density 4.0，1dp = 4px）：

| 节点 | bounds（px） | 换算 |
|---|---|---|
| 歌名「爱笑的眼睛」 | `[272,138][577,213]` | 左边界 272px = **68dp** |
| 作者「林俊杰」 | `[272,213][431,279]` | 同上 |
| 音源「· ncm」 | `[455,220][618,277]` | — |
| 歌词面板首行容器 | `[80,320][1360,1136]` | 面板上沿 **320px = 80dp** ⇒ 顶栏是 **96..320px**（56dp） |
| 缩小歌词字号按钮 | `[1080,336][1256,448]` | — |

PNG 像素测量（`crop_topleft.png`，取「与背景色差 > 60」的连通区域）：

| 量 | 实测（px） | 换算 |
|---|---|---|
| 顶栏区间 | 96 .. 320 | 高 224px = **56dp** |
| 顶栏中心 | **208** | statusBar(96) + 28dp |
| 封面区间 | 142 .. 365 | 高 223px = **56dp** ✓ |
| 封面中心 | **253** | statusBar(96) + **39.3dp** |
| **差** | **45px** | **≈ 11.6dp** |

⇒ 封面中心 ≈ `statusBar + 40dp`（= `TrayLayout.HEIGHT_DP/2`），
而它所在的顶栏中心是 `statusBar + 28dp`（= `56dp/2`）。**理论差 12dp，实测 11.6dp。**

**水平方向**：封面左边缘 = **x=0**（PNG 上封面像素从第 0 列开始），而文字块从 68dp 开始
（= 封面 56dp + 间隙 12dp，那 12dp 是**文字列自己的** `padding(horizontal = 12.dp)`）。

---

## 3. 根因（指名到行）

```
PlayerCard.kt:312  val miniCoverHalfPx   = TrayLayout.coverHalfDp()            // 28dp
PlayerCard.kt:316  val miniCoverCenterX  = miniCoverHalfPx                     // ★ 左边缘 = x0
PlayerCard.kt:317  val miniCoverCenterY  = statusBarPx + TrayLayout.coverCenterOffsetDp()  // ★ 40dp（托盘中心）

PlayerCard.kt:1820 val currentCenterX = miniCoverCenterX + normalizedP * (targetCenterX - miniCoverCenterX)
PlayerCard.kt:1821 val currentCenterY = miniCoverCenterY + normalizedP * (targetCenterY - miniCoverCenterY)
PlayerCard.kt:1816 val targetCenterX  = largeCoverCenterX + lyricAnimValue * (miniCoverCenterX - largeCoverCenterX)
PlayerCard.kt:1817 val targetCenterY  = largeCoverCenterY + lyricAnimValue * (miniCoverCenterY - largeCoverCenterY)
                   // ★ 歌词全屏（lyricAnimValue→1）时缩回的就是这个「托盘中心」，
                   //   而全屏态的顶栏只有 56dp —— 容器不同，中心却用了同一个。

PlayerCard.kt:1330  窄屏展开态顶栏： fillMaxWidth().height(56.dp)
PlayerCard.kt:1335               .padding(start = 68.dp, end = 56.dp)   // 68 = 56 封面 + 12 间隙
PlayerCard.kt:546   topBarBottomPx = statusBarPx + 56.dp
PlayerCard.kt:1865  收起按钮叠加层：.height(56.dp)                      // 第三个 56dp 字面量
```

**三个 `56.dp` 是同一件事的三份字面量**（顶栏 / 手势判据 / 收起键），
而封面 overlay 用的是**另一件事**（80dp 托盘）的中心 —— 这就是缺口。

---

## 4. 修复（v3.2.0 落地）

全部几何收敛到 `TrayLayout`（该组件既有的「唯一几何落点」，v2.5.5 引入）：

| 新常量 / 函数 | 值 | 作用 |
|---|---|---|
| `TrayLayout.COVER_START_DP` | **16** | 封面左边距 = 应用级内容边距（不是组件内部的 12dp 间隙） |
| `TrayLayout.COVER_TEXT_GAP_DP` | **12** | 封面 → 文字的水平间隙（与文字列既有 padding 同值，但语义独立） |
| `TrayLayout.TOP_BAR_HEIGHT_DP` | **56** | 全屏顶栏高度（取代三处字面量） |
| `TrayLayout.coverCenterXDp()` | 16 + 28 = **44** | overlay 的唯一 X 入口 |
| `TrayLayout.topBarCoverCenterOffsetDp()` | 56/2 = **28** | 全屏态封面中心（相对状态栏下沿） |
| `TrayLayout.topBarTextStartDp()` | 16+56+12 = **84** | 托盘占位与顶栏 `padding(start=)` 的唯一来源 |

`PlayerCard` 的改动：

1. `miniCoverCenterX` → `TrayLayout.coverCenterXDp()`；
2. 新增 `topBarCoverCenterY`，并让 `targetCenterY` 用它（收起态仍用 `miniCoverCenterY`，
   因为收起态的目标容器确实是 80dp 托盘）；
3. 托盘的封面占位前加 `Spacer(width = TrayLayout.COVER_START_DP.dp)`；
4. 顶栏 `.height(56.dp)` → `TOP_BAR_HEIGHT_DP`、`.padding(start = 68.dp, …)` → `topBarTextStartDp()`；
5. `topBarBottomPx` 与收起按钮叠加层的高度改用 `TOP_BAR_HEIGHT_DP`。

**修复后的预期几何**（可在下一轮真机上复测）：

| 量 | 修复前（实测） | 修复后（算得） |
|---|---|---|
| 封面左边缘 | 0px | `16dp` = **64px** |
| 封面区间 X | 0..224px | 64..288px |
| 文字左边界 | 272px | `84dp` = **336px** |
| 全屏态封面中心 Y | 253px（差 45px） | `statusBar + 28dp` = **208px** = 顶栏中心 |
| 收起态封面中心 Y | `statusBar + 40dp` | **不变**（仍是托盘中心） |

---

## 5. 验收口径与未验证项

**已做：**

- 纯逻辑单测（`TrayLayoutTest`，**4 条新用例**）：两个中心相差半个高度差、左边距非 0、
  文字起始位与封面几何自洽、**源码扫描守卫**（`PlayerCard` 不许再出现 `padding(start = 68.dp`
  与裸的顶栏高度字面量）。
- 预期几何的算术推导（上表）。平台无关，与设备无关。

**未做 / 未验证（如实）：**

- **修复后的真机截图未采集** —— 设备上装的是 v3.1.0 release 包，
  而本轮所有改动要到 release 构建后才有一个可覆盖安装的签名（debug 包签名不同，
  `adb install -r` 会失败，强装会清掉设备上已登录的 QQ 会员账号，代价不对等）。
  **本版不声称「已用真机截图验证修复后位置」**，只声称「修复前的偏差已实测（45px）
  且修复后的目标值与顶栏中心逐像素一致（算术层面）」。
- **横屏未验证**：宽屏走 `usesSideCover` 分支，本次**没有改动**那条路径（它用实测的
  `wideCoverCenter`），但也没有在本轮重新取证。
- **其它 density 未验证**：本次只有一个 density（4.0）。修复全部走 dp token，理论上与
  density 无关，但未做多设备 A/B。
