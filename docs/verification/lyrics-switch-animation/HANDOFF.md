# 交接：歌词跨行过渡「硬切」问题

> 交接日期：2026-11-05 · 主线 4.0.12 已发布 / 4.0.13 WIP 未发布
> 相关提交：`cfc41da`（4.0.11 引入）→ `a0d1d20`（4.0.12 连续量）→ `beee58b`（4.0.13 WIP）
> 代码位置：`app/src/main/java/com/takahashirinta/ncrust/ui/player/NcrustLyricsPanel.kt`

## 1. 用户描述的原话

- 「两首歌的歌词的切换还是有点硬切的感觉，没有那么流畅的感觉」
- 「主要是需要一个渐变的从第一句歌词的最大到第二句歌词的最大的这样一个过程」
- 「我需要一个类似手机应用冷启动那样的应用遮罩那种感觉的流畅的动画」
- 「在 60 帧设备上，4.0.12 的模糊表现还可以？也有可能是直接把过渡帧卡掉了从而看起来还可以」← **这条猜测基本正确，见 §4 实测**

参考素材（用户提供，两段录屏）：一段是纯歌词页（当前句大字、上下句渐隐 + 模糊），
一段是带封面的播放页（切到新句时新句从模糊到清晰）。共同特征：**过渡是"滑过去"的，不是"啪一下换掉"。**

## 2. 现在到底是什么行为（别信注释，信代码）

| 常量 / 分支 | 实际值 | 位置 |
|---|---|---|
| `LYRIC_SWITCH_MS` | **320**（⚠️ 注释里写的是 420，两者不一致，见 §6） | `NcrustLyricsPanel.kt:163` |
| 索引动画曲线 | `CubicBezierEasing(0.22f, 0.61f, 0.24f, 1f)` | `:238` |
| 缩放 | `lerp(inactiveScale=0.82f, 1f, act)` | `:720` |
| 透明度 | `alpha *= lerp(0.55f, 1f, act)` | `:723` |
| 位移 | `±(1-act) * (switchTravelPx*0.4f)`，即 ±4dp | `:229, :728` |
| 模糊 | `act ∈ (0.02, 0.98)` 时挂 `RenderEffect`，半径 `(1-act)*3.2dp`，**仅 API 31+** | `:734` |
| 相邻行滚动 | `animateScrollBy`：按 `LYRIC_SWITCH_MS` 分帧，三次缓出 | `:403` |

`act = (1 - |index - smoothCurrentIndex|).coerceIn(0,1)` —— 缩放/透明度/位移/模糊全部由它派生，
单一连续量（`beee58b` 之前是"缩放一套 + 颜色瞬切一套"，那是 4.0.11 硬切的根因）。

## 3. 三个版本各修了什么

### 4.0.11（`cfc41da`）—— 失败，反而确认了"硬切"
- 加了 `lineSwitch: Animatable` 只管**当前行**，`smoothCurrentIndex` 只管缩放，**颜色仍按整数 `currentIndex` 瞬切**
- 三者不同步 ⇒ 用户仍说硬
- 教训：**多条动画链必须由同一个连续量驱动**，不能各自为政

### 4.0.12（`a0d1d20`）—— 合并为连续量，但仍硬
- `act` 单一连续量（保住了，是正确方向）
- 索引动画从 `SokuouTweens.QuickSwitch`（180ms）放慢
- **遗留 bug**：`animateToLine` 用 `listState.firstVisibleItemIndex` 判"是否相邻行"，而当前行在视口 36% 处、
  上面还压着 2~3 行 ⇒ 两者永远差 >1 ⇒ 判定恒假 ⇒ 回落到 `animateScrollToItem`（一行位移 150~200ms 滑完），
  与缩放错拍 ⇒ **位置先跳一格、大小后到**

### 4.0.13（`beee58b`，WIP，**未出包未发布未验证**）
- 相邻判定改为 `lastPlaced[0]`（`placeLine` / `animateToLine` 都会写这个值）
- 位移取相邻行的**实测高度**（`itemHeights`），不再用 `firstVisibleItemIndex`
- 新增 `animateScrollBy(total)`：`LYRIC_SWITCH_MS` 时长 + 三次缓出，逐帧 `scrollBy`，与 graphicsLayer 同曲线
- 版本号已改成 `213 / 4.0.13-gpl`（要接着发就发，要退回就改回 212）

## 4. 实测数据（这是接手时最该看的部分）

设备：`3B15CD00GB700000`（PLC110，1272×2800，Android 16，60Hz）
方法：

```bash
adb -s 3B15CD00GB700000 shell screenrecord --time-limit 14 --bit-rate 12000000 /sdcard/r.mp4
adb -s 3B15CD00GB700000 pull /sdcard/r.mp4 /tmp/r.mp4
ffmpeg -v error -i /tmp/r.mp4 -vf fps=60 /tmp/fr/%04d.png
# 然后逐帧算歌词区（y 600~1700）灰度差分，>0.02 视为"有变化"
```

**4.0.12 的结果**（`/tmp/rec.mp4`，8.4~9.6s 窗口）：

```
第 29 帧  Δ=0.381   ← 整帧突变：整个歌词区瞬间上移一行
第 30~40 帧 Δ=0.07~0.20 递减  ← 约 11 帧（≈180ms）收干净
之后 Δ≈0.001（静止）
```

两个结论：
1. **存在单帧"跳一格"**：Δ=0.381 是整段录屏里最大的单帧变化，对应位置瞬移 —— 这就是"硬"的主要来源
2. **有效过渡只有 ~180ms**，而缩放的 tween 是 320ms ⇒ 两者错拍；
   用户"可能是过渡帧被卡掉了"的猜测**方向对**：视觉上能看到的连续帧比设计值短

> ⚠️ 4.0.13 的对比录屏**没录到**（会话被中断）。所以"4.0.13 修好了跳一格"目前**只是代码推断，无实测证据**。

## 5. 下一步（建议按顺序）

1. **先定时长**：`LYRIC_SWITCH_MS` 到底 320 还是 420？代码是 320、注释写 420。
   建议 **360**：参考动效在 320–480ms，360 兼顾"跟得上连拍"和"看得见过渡"。
2. **录 4.0.13 的对比帧**，用 §4 同一套命令。判据：
   - 最大单帧 Δ **< 0.15**（不再有"跳一格"）
   - 活跃窗口 **≥ 18 帧（≥300ms）**
   - 窗口内 Δ 曲线单调递减、无二次尖峰
3. **若仍跳**：确认 `lastPlaced[0]` 在 `placeLine` 与 `animateToLine` 两条路径都被写；
   以及 `itemHeights[prev]` 是否已知（未知时 `h>0 && prevH>0` 会直接跳过平滑分支）。
4. **若变卡**（60fps 掉帧）：按代价从低到高降级
   - 模糊只挂在 **act ∈ (0.3, 0.9)** 的行（省掉两端）
   - 半径 3.2dp → **2.4dp**
   - 只给**进入行**挂模糊，离开行不挂
   - 最狠：彻底去掉 `RenderEffect`，只留 缩放+透明度+位移
5. **验收**：连拍段（一句 1.5s 以内的歌词）连续切 5 次不出现"跳格 + 后补大"的错拍感。

## 6. 已知坑（别再踩）

- **注释与实现不一致**：`:163` 常量 320，注释写"放慢到 420ms"。改动时先对齐，否则下一个人会被注释骗。
- **`firstVisibleItemIndex` 不能用来判"相邻行"**：当前行在视口 36% 处，视口首行是它上面 2~3 行。
  这是 4.0.12 硬切的直接根因，`beee58b` 已改，但**别在新代码里再用它**。
- **别再加第二条独立动画链**：4.0.11 的 `lineSwitch` 就是这么失败的。所有过渡量都从 `act` 派生。
- **LTS（`lts/3.7`，3.7.31 / `defaad0`）只有 4.0.12 那一版**：`act` 连续量 + 分帧滚动已同步，
  但 `animateScrollBy` 的相邻判定**仍是旧写法**（用 `firstVisibleItemIndex`）—— 要修请两边一起改。
- **LTS minSdk 24**：`RenderEffect` 只在 API 31+ 存在，24–30 上是"缩放+透明度+位移"，无模糊。
  别为了"手感一致"去引入 RenderScript 之类的老 API。

## 7. 环境

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk
# 可靠出包（离线 / 单 worker / in-process 编译）
./gradlew --offline --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process \
  '-Dorg.gradle.jvmargs=-Xmx5g -XX:ActiveProcessorCount=6 -Dfile.encoding=UTF-8' :app:assembleRelease
# 装到真机（签名与线上一致，可覆盖安装、保留登录态）
adb -s 3B15CD00GB700000 install -r app/build/outputs/apk/release/app-release.apk
```

真机上要放一首**换行密集**的歌才看得出差别（会话里用的是《心墙》林俊杰、《ありがとう》大橋卓弥）。
模拟器没有登录态和曲库，验不了这条 —— 别在模拟器上浪费时间。
