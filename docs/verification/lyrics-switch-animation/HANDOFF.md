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

---

# 附：Codex 的解法（提交 `d4bf83e`，学习了它之后补记）

> 我（上一任接手人）三次尝试都停在"调参"层面；Codex 这次是**改结构**。差别不在参数，在于**谁来驱动、在什么坐标系里驱动**。

## 它做了四件事

### ① 一个 tween 同时推「列表位置」和「聚焦进度」

```kotlin
private val LyricHandoff = tween<Float>(360, easing = SokuouTweens.CoverFade.easing)

suspend fun handoff(goal: LyricFocusTarget, move: (Float) -> Unit = {}) {
    val from = visualFocus
    val source = from.weights()
    animate(0f, 1f, animationSpec = LyricHandoff) { phase, _ ->
        move(phase)                       // ← 同一帧里滚列表
        visualFocus = if (phase == 1f) LyricFocusFrame(goal.index.toFloat(), goal) else {
            LyricFocusFrame(lerp(from.center, goal.index.toFloat(), phase), goal, source, phase)
        }                                 // ← 同一帧里更新聚焦
    }
}
// 调用侧：scroll { handoff(goal) { phase -> scrollBy(travel * phase - applied); … } }
```

**我用两条时间线**（`smoothCurrentIndex: Animatable` 管缩放、`animateScrollToItem`/自绘 `scrollBy` 管滚动），
它们永远差半拍；它把两者塞进**同一个 `animate` 回调的同一帧**，物理上不可能错拍。

### ② 聚焦是 `source → target` 的交叉，而不是「从 A 跳到 B」

```kotlin
fun emphasis(row: Int) = lerp(source[row] ?: 0f, if (row == target.index) target.strength else 0f, phase)
fun weights() = buildMap { for (row in source.keys + target.index) { … } }   // 只列"画面上已有的行"
```

`source` 是**打断瞬间画面上的权重表**。因此：
- 密集短句被打断 → 新动画从"屏幕上现在的样子"接续，不跳
- 跨段 seek → 只点亮起点与终点，**被跳过的中间行不会被误点亮**

我原来的模型只有"当前 index"这一个整数 + 一个连续量，天然表达不了"两个行同时处于半亮"。

### ③ 每句保留自己的排版（这是硬切的另一半根因）

```kotlin
val fontScale = line.fontScale            // 固定，不随"是不是当前行"变
val textStyle = TextStyle(fontSize = fontSize * fontScale, …)
// 放大只发生在绘制层：
var scale = lerp(baseScale, 1f, emphasis()); scaleX = scale; scaleY = scale
```

我在竖屏踩的坑：切句时把上下文小字换成卡拉 OK 大字布局 → **行高当场变化** → 列表必须瞬移 → 硬切。
它反过来：**行高恒定**，只缩绘制。位置可预测，滚动距离能跟缩放同一个 phase 走。
逐字扫光、侧边光轨（`cueRail(emphasis=…)`）、三点入唱全部留在同一页。

### ④ 景深放到字形绘制，不用逐行 `RenderEffect`

```kotlin
private fun DrawScope.drawDefocusedText(layout: TextLayoutResult, color: Color, radius: Float) {
    if (radius < 0.4f) { drawText(layout, color = color); return }
    // 同一份 TextLayoutResult 按 8 个方向低透明度重画 + 主画降 alpha = 廉价散景
    drawText(layout, color = side,  topLeft = Offset(±radius, 0f)) … 8 次
    drawText(layout, color = color.copy(alpha = color.alpha * (1f - 0.64f * spread)))
}
```

半径变化**不重装滤镜、不把整行渲进离屏层**。这是它能做到"帧内连续变焦"、而我用
`RenderEffect.createBlurEffect` 每帧新建 effect 会抖/掉帧的原因（任务描述里的"约半秒停顿"也是同源）。

配套：
- `defocus = maxDefocusPx * depth * (1f - emphasis())`，竖屏下方陡（`/1.9`）、上方缓（`/6`），横屏 `/2.4`
- `userScrolling` 时 `defocus = 0`（拖动期间不做景深，省算力）

### ⑤ 另外两处我没做到的细节

- `snapshotFlow { LyricFollowRequest(target, viewport, userScrolling, rowMetrics) }` + `collectLatest`：
  `rowMetrics`（译文主行 / 字号）变了先等两帧再量高度重新居中 —— 我版本里"字号变化后偏移"就是这么来的
- 远距离定位藏在淡出里：`relocationAlpha` 1→0（`QuickSwitch`）→ `placeLine` → 0→1（`SheetAppear`）
- 移除 `LYRIC_SWITCH_MS` 常量，改 `LyricHandoff`；`focusedRow` 回调签名加了 `emphasis` / `defocus` 两个 lambda

## 结论：我的三次尝试错在哪

| 维度 | 我的 4.0.11 / 4.0.12 / 4.0.13 | Codex `d4bf83e` |
|---|---|---|
| 时间线 | 缩放与滚动两条，靠"时长/曲线调成一样"去凑 | 同一 `animate` 回调里 `move(phase)` |
| 中断 | 重启动画 ⇒ 从旧起点重来 | `source→target` 交叉，从当前画面接续 |
| 行高 | 竖屏切布局 ⇒ 行高变 ⇒ 位置必跳 | 布局恒定，只缩绘制 |
| 景深 | 逐行 `RenderEffect`（贵） | 字形 8 向重绘（便宜、无滤镜重装） |
| 远距离 | `animateScrollToItem` 直接跳 | 淡出 → 定位 → 淡入 |

**教训**：这类"两个视觉量必须同时到位"的过渡，调参救不回来 —— 必须让它们共享**同一个进度变量**，
并且保证被动画的对象的**布局尺寸在动画期间不变**。

## 交接状态更新（2026-11-05 晚）

- 主线 `feat/4.0.0`：`ba60cd3`（测试整理）→ `d4bf83e`（本次修复），工作树版本号 `4.0.13 / 213`
- JVM 单测 3,532 / 0 失败 / 4 跳过；Debug Lint 0 错误
- QA 产物：`/home/duanjb666/deepseek/arris/dist/lyrics-switch-qa/handoff-comparison.mp4`（新旧同场景慢放）、
  `handoff-frames.png`（逐帧对照）
- **LTS（`lts/3.7`，3.7.31 / `defaad0`）仍是旧实现**（`smoothCurrentIndex` + `animateScrollBy`），
  与主线已不同源；要同步请按本文件 §①–⑤ 重做
- 真机帧率仍待实测（Codex 的流畅度数据来自隔离模拟器）
