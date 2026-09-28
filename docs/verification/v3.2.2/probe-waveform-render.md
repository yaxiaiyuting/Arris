# 波形渲染探针：离散柱 → 连续曲线（v3.2.2 改造前调研）

> **只读调研。** 未修改任何源码 / 构建文件，未运行 Gradle、未运行测试。行号为本次逐行核对结果，路径相对仓库根 `Ncrust/`。
> **环境**：本机无设备、无编译产物 ⇒ 不含任何本轮实测数字；文中的实测值全部是源码注释里记录的**既有测量**，逐条标注出处。
> 无法从源码确认的一律写成「未确认（源码中无依据）」，并说明需要什么才能确认。

## 结论速览

| # | 问题 | 结论 | 关键证据 |
|---|---|---|---|
| 1 | 当前渲染方式 | 纯 Compose `Canvas` + `DrawScope` 自绘；**没有 Path**、没有 `drawPath` | `AudioVisualizer.kt:15`、`:568`、`:741-835`；`app/src` 内 `androidx.compose.ui.graphics.Path` grep 命中 0 |
| 2 | 柱数 / 采样率 / 数据源 | 28 柱；环形缓冲 256；**真实柱率 = `handleBuffer` 回调率，代码不保证任何固定值**；"30 柱/秒"已删除 | `AudioVisualizer.kt:118`、`:133`、`:123-131`、`:228-230`；`TransparentWaveformSink.kt:183-238` |
| 3 | 柱高 ↔ RMS | `h = max(1.dp, sqrt(clamp(bars[i],0,1)) × size.height)`，以中线居中；输入已是 22ms/130ms 时间常数平滑值 | `AudioVisualizer.kt:769-772`、`:759-760`、`:582`；`WaveformRing.kt:280-297`、`:399-405` |
| 4 | 颜色来源 | **单色**：`LocalMetroColors.current.primary` 一个色相，多色只是同一色相的明暗/alpha 分档；可视化层**不读任何 HCT 角色**，HCT 只在「主题色来源=跟随封面」时经主题间接进入 | `AudioVisualizer.kt:520`、`:530`、`:785-789`；`CoverPalette.kt:65-83`、`:120-124`；`ThemeManager.kt:107` |
| 5 | 能否改连续曲线 | 不能。无 Path、无 `cubicTo`；要改 5 个绘制助手 + 6 个调用点，并解决"跨帧复用路径对象"与档位/流动两处契约 | `AudioVisualizer.kt:597`、`:605`、`:589`、`:613`、`:618`、`:860-862`；`:463`、`:718` |
| 6 | 与 MotionClock 的关系 | `WaveformStore.pump` **只有一个调用者**（`MotionClock.kt:100`）；`MotionFrameClock` 只挂载一次；`AudioVisualizerBars` 是**纯读取方** | `MotionClock.kt:100`、`:203-235`；`PlayerCardBackdrop.kt:130-134`；`AudioVisualizer.kt:556-566`、`:570` |
| 7 | 异常隔离 | **只隔离音频线程那一段**；渲染/绘制这一段没有任何兜底，且是有意不包 | `TransparentWaveformSink.kt:189`、`:234-237`；`PlayerCard.kt:837-842`；`AudioVisualizer.kt` 全文 0 处 try/catch |

## 1. 当前波形渲染方式：Canvas / Compose Path / 自绘

**纯 Compose `Canvas` + `DrawScope` 自绘，没有任何 Path。**

- 入口 `androidx.compose.foundation.Canvas`（import `AudioVisualizer.kt:15`），组件 `@Composable fun AudioVisualizerBars(modifier, barCount)`（`:515-519`），Canvas 调用与 draw lambda 起点 `:568`。
- 一帧的全部绘制落在 5 个**顶层 private `DrawScope` 扩展函数**里（抽成顶层函数是为了避免局部函数/lambda 的捕获与分配不确定性，`:714-719`）：
  `drawWaveformBars`（定义 `:741-835`，调用 `:597`、`:605`）、`drawShockwaveRipples`（`:940-962`，调用 `:589`）、
  `drawParticles`（`:970-986`，调用 `:613`）、`drawBandLanes`（`:846-863`，调用 `:618`）、`drawBandLane`（`:866-888`，调用 `:860-862`）。
- 图元只有：圆角柱 `drawRoundRect`（`:793`、`:801`）、直角柱 `drawRect`（`:810`、`:812`）、柱顶光点 `drawCircle`（`:815`）、峰值横条 `drawRect`（`:826`）、频带条 `drawRect`（`:880`）、涟漪/粒子 `drawCircle`（`:954`、`:979`）。
- 变换只有 2D：整体 `translate(left = -phasePx)`（`:596`）与逐柱 x 补偿（`:771`）；C 档 3D 透视走 `Modifier.visualizerPerspective` 的 `graphicsLayer`（定义 `:709-712`、挂载 `:537-539`，理由 `:700-708`：`DrawScope` 无 `cameraDistance` 语义）。
- **无 Path 的三重证据**：① import 列表 `:11-45` 无 `androidx.compose.ui.graphics.Path`；② `app/src` 内 grep `androidx.compose.ui.graphics.Path` 与 `drawPath` 均 0 命中；③ 全仓唯一的 `Path` 符号是 Retrofit 的 `@Path`（`NcmApi.kt:8`），两处 `clipPath` 只在注释里（`NcrustLyricsPanel.kt:669`、`LyricsDisplayPrefs.kt:21`）。
- C 档裁剪只在炫技档挂：`clipModifier`（`:542-544`，理由 `:540-541`）；点按着色 `detectTapGestures`（`:547-555`，默认关）。

## 2. 柱子数量、采样频率、数据源

- **柱数**：`WaveformStore.BAR_COUNT = 28`（`AudioVisualizer.kt:118`，理由 `:117`），形参默认值 `:518`，实际使用 `n = bars.size`（`:578`、`:758`）。
- **环形缓冲容量 256**：`CAPACITY = 256`（`:133`，语义注释 `:120-132`），实例化 `WaveformRing(capacity = CAPACITY, barCount = BAR_COUNT)`（`:135`）；内部三个逐槽对齐的平行环 `ring`/`bassRing`/`mixRing`（`WaveformRing.kt:68`、`:77`、`:87`）。
- **数据源**：ExoPlayer tee → `TransparentWaveformSink.handleBuffer`（`TransparentWaveformSink.kt:183-238`）→ `WaveformStore.onBar`（`:194-199` 正常路径、`:227` 降级路径）→ `ring.push`（`AudioVisualizer.kt:229`）→ `WaveformRing.push`（`WaveformRing.kt:138-146`）；注入方是 `VisualizerRenderersFactory` 的 tee（`AudioVisualizer.kt:105-107`，不需要 RECORD_AUDIO）。
- **真实柱率 = `handleBuffer` 回调率**：`AudioVisualizer.kt:123-124` 写明「每回调推一根柱」且「由解码器/渲染器送进 sink 的缓冲粒度决定，**代码不保证任何固定值**（探针 §7 #1 明确未确认）」；一次 `handleBuffer` 里 `onBar` 恰好一次（`TransparentWaveformSink.kt:194-199`、`:227`）。同一事实复述于 `AudioFeatureExtractor.kt:156-159`：缓冲粒度是运行时行为，「代码里没有常量能回答它多大」。故本探针不给出任何"柱/秒"数字。
- **已删除的伪常量**：`BARS_PER_SECOND = 30` 与由它推出的「最多积压 8.5 秒」已删除，考古见 `AudioVisualizer.kt:128-131`。
- **注释里记录的既有粒度测量（两条口径不同，如实转述）**：`AudioVisualizer.kt:182` 「实测缓冲粒度 ~10–50ms」（跨特征字段偏斜容差语境）；`AudioVisualizer.kt:262`、`TransparentWaveformSink.kt:210-211`、`AudioFeatureExtractor.kt:640`、`:649` 「真机缓冲粒度 100ms（S6 实测 4410 帧 / 11 Hz）」（瞬态分帧语境）。
- **UI 侧消费与丢帧**：`pump` 一次把"上次之后新到的柱"全部搬进滚动窗口（`WaveformRing.kt:207-215`），积压超容量时丢最旧（`:211-215`，绝不回压音频线程 `:24-25`）。
- **开关门**：柱只有 `enabled` 为真才入环（`AudioVisualizer.kt:228-230`）；音频线程侧两个开关都关时一次样本遍历都不跑（`TransparentWaveformSink.kt:186-188`）。
- **重绘节奏（不是采样率）**：静态档 16ms ≈ 60fps / 33ms ≈ 30fps（`AudioVisualizer.kt:430`、`:433`），判据 `visualizerFrameIntervalMs`（`:448-454`）。

## 3. 每根柱子的高度如何映射 RMS

```
h_i   = max(minBar, sqrt(clamp(bars[i], 0, 1)) × H)      // AudioVisualizer.kt:769-770
minBar = 1.dp.toPx()                                      // AudioVisualizer.kt:582
top_i  = H/2 - h_i/2 ，center = H/2                        // AudioVisualizer.kt:759, :772
```

- `sqrt` 在 `AudioVisualizer.kt:769`（`val amplitude = sqrt(bars[i].coerceIn(0f, 1f))`），乘高与下限在 `:770`；依据注释 `:766-768`（流行乐 RMS 常落 0.05–0.3，线性映射只有 5%–30% 带宽；sqrt 把 0.09→0.3、0.25→0.5）。
- **镜像居中是几何构造，不是分支**：`half = size.height / 2f`（`:759`）配 `top = half - barHeight / 2f`（`:772`），对应 `VisualizerEffects.mirror` 恒为 `true`（`VisualizerTier.kt:107`、`:237`）。
- **`bars[i]` 已是平滑值**：`pump → approach`（`WaveformRing.kt:280-297`）用时间常数形式 `k = 1 - exp(-dt/tau)`，起音 `ATTACK_TAU_MS = 22f`（`:399`）、回落 `RELEASE_TAU_MS = 130f`（`:402`）、收敛截断 `SETTLE_EPSILON = 0.004f`（`:405`）；原始值入环即 clamp 0..1（`:142`）。
- 完整链：`RMS(buffer)` → clamp（`WaveformRing.kt:142`）→ 指数平滑（`:287-291`）→ `snapshot` 拷入复用数组（`AudioVisualizer.kt:341`、`:344-345`，调用 `:574`、`:576`）→ `sqrt` → `× H` → `coerceAtLeast(1.dp)`。
- **同一 sqrt 映射被刻意复用于另外两处**：峰值 `sqrt(peaks[i]) * heightPx`（`:824`，理由 `:823`），且只在高于柱顶 `peakCapPx` 时画（`:825`，`peakCapPx` 定义 `:534`、常量 `:689`）；频带条宽 `size.width * sqrt(v)`（`:878`，理由 `:877`）。
- 另两个逐柱 transform（不改高度、只改观感）：按时序 alpha `timeAlpha = (0.30 + 0.70 × (i+1)/n) × breath`（`:775-776`，常量 `:632-633`）；多频段 `tint` 与 alpha（`:784-790`，常量 `:643`、`:652`、`:655`）。呼吸倍率 `breath`（`:584`）来自 `WaveformStore.breatheScale()`（`:376`）→ `WaveformRing.breatheScale()`（`WaveformRing.kt:358-364`），只乘 alpha。

## 4. 颜色来源：HCT 取色哪个角色？单色还是多色？

**单色**：可视化只有一个色相来源，且不直接读任何 HCT 角色。

- 基色 `val barColor = LocalMetroColors.current.primary`（`AudioVisualizer.kt:520`）——全文唯一颜色取值点。
- "多色"全部是同一色相的明暗/alpha 分档：明亮端 `brightColor = lerp(barColor, Color.White, BAND_TINT_WHITE_MIX)`（`:530`，`0.45f` 与理由 `:637-643`，亮端是**硬编码白色**、不来自调色板）；逐柱实心色 `lerp(barColor, brightColor, tint)`（`:787`），`tint` 来自 `MotionBindings.tintMix(mixes[i])`（`:785`，定义 `MotionBindings.kt:252-256`，常量 `:259`、`:262`），切 Brush 阈值 `BAND_TINT_BRUSH_THRESHOLD = 0.5f`（`:652`、判据 `:786`），低沉端 alpha `BAND_TINT_ALPHA_MIN = 0.8f`（`:655`、用 `:789`）；流动色带 `FlowBrushCache` 的三个 stop 是**同一个 `color`** 的三个 alpha（`:905-915`，常量 `:670-671`），`TileMode.Repeated`（`:904`、`:914`），只在尺寸变化时重建（`:900-903`，理由 `:891-894`、`:483-485`）；alpha 阶梯 `:775-779`；涟漪/粒子/频带条同样只用 `barColor`（`:589`、`:613`、`:622`）。
- **仓库里的 HCT 角色**（`CoverPalette.kt`）：字段集合即角色集合 `primary / onPrimary / primaryContainer / secondary / tertiary / neutral / neutralVariant / onSurface / onSurfaceVariant / outline / outlineVariant`（`:65-83`）；角色→tone 对照表 `:43-55`（深色 outline 是 tone 60、浅色 neutral 是 tone 99 两个易抄错点，`:57-58`）；取色 `Hct.fromInt(seedArgb)`（`:120`）、`hue`/`chroma`（`:121-122`）、五条色板（`:124-128`，a1 = `(hue, max(48, chroma))`）；深色 `primary = a1.tone(80)`（`:134`）、浅色 `a1.tone(40)`（`:150`）；工厂 `light/dark/forMode`（`:166-172`）。
- **可视化层不用它们**：`AudioVisualizer.kt:11-45` 无任何 `ui.theme.color` 引用，全文无 `CoverPalette`/`Hct` 命中。HCT 只能经主题间接到达 `:520`：`AccentSource.COVER` → `coverPalette`（`MainActivity.kt:328`）→ `NcrustTheme(coverPalette = …)`（`:338-341`）→ `toNcrustColors`（`ThemeManager.kt:107`）→ `NcrustColors.primary = Color(palette.primary)`（`CoverTheme.kt:124-125`）→ `toMetroColors` 直通（`NcrustColors.kt:115-119`）→ `MetroTheme`（`MainActivity.kt:348-349`）→ `LocalMetroColors.current.primary`（`AudioVisualizer.kt:520`）。
- 另两条来源**不是 HCT**：预设档 `themeColorForIndex`（`MainActivity.kt:318`，返回点 `ThemeManager.kt:78`）；系统档 `processAccentColor`（`MainActivity.kt:316`）是 HSV 压缩（饱和度 ≤0.6 + 亮度锚定，`AccentSource.kt:132-140`，注释 `:128-131`）；默认预设档（`AccentSource.kt:33-36`）。
- 覆盖顺序易错点：`NcrustTheme` 先 `base.copy(primary = primaryColor, …)`（`ThemeManager.kt:97-103`），`coverPalette != null` 时整组覆盖（`:107` → `CoverTheme.kt:125`）⇒ 跟随封面时 `MainActivity.kt:312` 传入的 accentColor 会被 `palette.primary` 覆盖。
- 结论：曲线化不引入新颜色维度；颜色仍是"一个 `primary` + 白色亮端 + alpha/渐变分档"。

## 5. 是否支持改绘连续曲线（Path.cubicTo 等）

**不支持，且改动面大于"换一个绘制 API"。**

- 无 Path 先例（见 §1）；`cubicTo` / `quadraticBezierTo` / `Path()` / `drawPath` 在 `app/src/main` 内 grep 命中 0。
- **助手与调用点清单**（曲线化要逐个决定去留）：

  | 助手 | 定义 | 调用点 | 必须决定的事 |
  |---|---|---|---|
  | `drawWaveformBars` | `AudioVisualizer.kt:741-835` | `:597`（流动）、`:605`（不流动） | 主体几何从"逐柱矩形"改为折线/贝塞尔 |
  | `drawShockwaveRipples` | `:940-962` | `:589` | 画在曲线之下（层次 `:587-590`） |
  | `drawParticles` | `:970-986` | `:613` | 画在曲线之上（层次 `:612-614`） |
  | `drawBandLanes` | `:846-863` | `:618` | 频带条仍在曲线之上；刻意不用数组（`:857-858`） |
  | `drawBandLane` | `:866-888` | `:860-862` | 三条等长横条，与柱高无关，可不动 |

- **draw lambda 是逐帧的**：全仓唯一读 `WaveformStore.generation` 的地方是 `AudioVisualizer.kt:570`（grep 只此一处；`:461`、`:564` 是注释）。失效语义 `:461-463`：只在 draw 阶段读 ⇒ 只重绘这块画布、**不重组**；组合期只读一次 `val effects = VisualizerPrefs.effects.value`（`:527`，注释 `:525-526`）。
- 曲线需要的几何量现在全在 draw 内现算且依赖"逐柱"假设：`n = bars.size`（`:578`）、`gap = size.width * 0.28f / n`（`:580`）、`barWidth = ((size.width - gap*(n-1)) / n).coerceAtLeast(1f)`（`:581`）、`minBar = 1.dp.toPx()`（`:582`）、`half`/`heightPx`（`:759-760`）、`left = i*(barWidth+gap) + xShift`（`:771`）。
- 平移把 x 与相位绑定：画布整体 `translate(-phasePx)`、每柱 `+phasePx` 抵消（`:594-603` + `:771`），相位来自 `flowPhase01()`（`:594`）、tile 比例 `FLOW_TILE_FRACTION = 0.55f`（`:667`）。曲线若用绝对坐标采样 `Brush` 必须复刻。
- 逐柱附加绘制依赖"每柱一个矩形"：光点（`:814-821`，阈值 `dotThreshold = minBar * DOT_MIN_BAR_MULTIPLE` `:762`、常量 `:692`）、峰值横条（`:822-833`）；`effects.rounded` 二分支（`:791-813`）与 `color`/`brush` 二选一（`:736-737`、`VisualizerTier.kt:154-163`）也是逐柱语义。
- 形参与空输入路径要重定义：`barCount` 是公开形参（`:516-519`）；`n == 0 || size.width <= 0f || size.height <= 0f` 时提前返回（`:579`）。
- **零分配直接决定曲线怎么写**：`:463`、`:718` 声明帧路径零分配（柱高数组是 `remember` 的 `FloatArray`、逐帧原地更新 `:521-524`），`WaveformRing.kt:22`、`:333` 同。⇒ **每帧 `Path()` 或每帧构造点列表会违反契约**，正确形状是跨帧复用一个对象/数组并原地重写。可复用的具体 Path API（重置/重写方法名与语义）**未确认（源码中无依据）**：本仓库无任何 Path 使用先例，需按所用 compose-ui 版本查 `androidx.compose.ui.graphics.Path` 成员，或先在单测/样例中验证。
- 着色分支在曲线上仍需成立：`mixes != null` 走逐柱 tint（`:592`、`:784-790`），`null` 走 v2.8.0 单一着色路径；"逐柱"变"逐段/逐采样点"的语义要显式定义。

## 6. 渲染层与 MotionClock / MotionBindings 的关系

- **`WaveformStore.pump` 的唯一调用者是 `MotionClock.frame`**（`MotionClock.kt:100`；全仓 `WaveformStore.pump` 调用点仅此一处，其余是定义 `AudioVisualizer.kt:316` 与注释）。`pump` 推进环形窗口/平滑/峰值/C 档状态机（`:316-338`），有变化时 `generationState.intValue++`（`:337`）。
- **`MotionFrameClock` 唯一挂载点 `PlayerCardBackdrop.kt:130-134`**：`activeProvider = { isPlaying && !isBufferingFlow.value }`（`:131`）、`enabled = hasSong && expandedMounted`（`:132`）、`waveformMounted = visualizerSlot || motion.fullScreenWaveform`（`:133`）。`PlayerCardBackdrop` 由 `PlayerCard.kt:482` 调用一次、位于展开态子树（`PlayerCard.kt:503`；`expandedMounted` 定义 `PlayerCardState.kt:114`）。
- **`AudioVisualizerBars` 两个挂载点**：① 左栏/宽屏条 —— `AudioVisualizerSlot`（`PlayerCard.kt:845-856`）→ `AudioVisualizerBars`（`PlayerCard.kt:849`），由 `PlayerCardLayouts.kt:156`、`:310` 在 `visualizerSlot` 为真时挂（谓词 `PlayerLayout.kt:79-87`）；② 背景级波形 —— `PlayerCardBackdrop.kt:156-163`，门控 `if (motion.fullScreenWaveform)`（`:147`），复用同一组件（理由 `:143-146`），压暗 `WAVE_BACKDROP_ALPHA = 0.26f`（`:160-161`、`:186`）。
- **帧循环只有一条**（`MotionClock.kt:206`），门 `clockNeeded = enabled && (motion.needsFrameClock || waveformMounted)`（`:203`）；播放中 `withFrameNanos(onFrame)`（`:222`，回调与节流 `:211-219`），暂停/缓冲 `delay(frameIntervalMs)` 并显式推进一次 `active=false`（`:224-232`）。
- **"reader only" 契约**（原文 `AudioVisualizer.kt:556-566`）：帧循环自 v2.9.0 起搬到 `MotionClock.kt`，因为竖屏手机上波形不挂载而动效要跑；两处各起循环会让 `pump` 每帧被调两次，`consumePending` 幂等但**动画相位**（流动/呼吸）前进两次、流速翻倍，且只在横屏/平板出现（`:560-562`）。故本组件契约是"只读：draw 阶段读 `generation` 与快照，推进由 `MotionFrameClock` 负责"（`:564-566`）；同一契约另见 `MotionClock.kt:41-42`、`PlayerCard.kt:846-848`。
- **两份 generation 互不复用**：波形 `AudioVisualizer.kt:215-218`（写 `:337`、手动 `:387`）；动效层 `MotionClock.kt:66-69`（写 `:131`、`:146`）。
- **与 `MotionBindings` 只有一处借用**：`MotionBindings` 由帧循环更新（`MotionClock.kt:102-111`；类 `MotionBindings.kt:55`、`update` `:109`），但可视化**不读 `MotionClock.bindings()`**，只调用纯函数 `tintMix`（`AudioVisualizer.kt:785`）。逐柱 `mix` 来自环形缓冲快照（`:574`、`:592` → `WaveformRing.kt:351-355`）；频带条读 `WaveformStore.featureLow/Mid/High`（`:619-621`，定义 `:291-297`，音频线程发布 `:239-254`）。`AudioVisualizer.kt` 全文**不引用 `MotionClock`**（grep：`:39` 只 import 了 `MotionBindings`）。

## 7. 异常隔离现状：渲染失败时如何处理？

**隔离边界只在音频线程那一段；渲染/绘制这一段完全没有兜底，且是有意为之。**

- **已隔离**：`handleBuffer` 把"特征计算 + 三个回调"整个包进同一个 `try`（`TransparentWaveformSink.kt:189`），`catch (t: Throwable)` 吞掉并只自增 `droppedBarCount`（`:234-237`；计数声明 `:150-152`）。契约在 `:170-182`（四步顺序即契约）与类头 `:34-42`（"不抛异常：绝不把音频线程上的异常抛给播放器"）；失败语义是**丢这一根柱、不补 0**（`:181`，理由：补 0 会画出假的静音凹陷）。
- 该边界被单测钉住：`TransparentWaveformSinkTest.kt:212`（抛异常的柱消费方不得冒出 `handleBuffer`）、`:231`（抛异常的特征消费方同理）、`:249`（抛异常的 RMS 被隔离且丢柱）。`PcmRms` 自身按"绝不抛"设计：状态数组为空、未知编码、未知采样率都退化为 0（`TransparentWaveformSink.kt:313-318`、`:376`、`:424`、`:463`）。
- **未隔离（渲染侧）**：`AudioVisualizer.kt` 全文 986 行**无一处 `try`/`catch`/`runCatching`**（grep 命中的三行是 `geometry` 包名误匹配 `:24-26`）；5 个绘制助手（`:741-835`、`:846-888`、`:940-962`、`:970-986`）无兜底；`MotionClock.frame`（`MotionClock.kt:94-133`）也无。
- **这是明确取舍**：`PlayerCard.kt:837-842` 写着「这里不再包一层 `runCatching`（`@Composable` 里的 try/catch 会破坏 Compose 的重组语义，反而制造新的失败面）」。
- **异常抛在 `Canvas` draw lambda 里会发生什么：未确认（源码中无依据）。** 要确定它需要：在 draw lambda 首行注入 `throw`，在真机/模拟器上观察是进程崩溃、只丢这一帧还是被绘制层吞掉，并确认 logcat 落在哪个线程；或核对本项目所用 compose-ui 版本绘制层的异常处理实现。本仓库既无这类实验记录，也无覆盖它的单测（`app/src/test/.../ui/player/` 下只有几何/布局/环形缓冲/滚动等纯逻辑测试）。
- 数据侧非法值兜底是"归零"而非抛异常：`finite01`（`AudioVisualizer.kt:415-417`，注释说明 NaN/Inf 一律归零、NaN 进 `Color` 不抛异常只会让画面静默消失）、入环 clamp（`WaveformRing.kt:142-144`）、移位 clamp（`:259`）。曲线继承这条要求；Path 坐标含 NaN 时的平台行为**未确认（源码中无依据）**。
- 结论：只有「音频线程 → UI」有边界（丢一根柱，播放不受影响）；「UI 渲染 → 进程」没有边界。v3.2.2 若引入更复杂绘制（Path/曲线/更多图元），新增风险点全落在没有边界的一侧，且没有自动降级退路（见下节第 13 条）。

## 对 v3.2.2 改造的约束

1. **零分配/帧是硬契约**：帧路径不得新建对象（`AudioVisualizer.kt:463`、`:718`；`WaveformRing.kt:22`、`:333`；`:857-858` 为守此纪律放弃 `floatArrayOf`；`:483-485` 记录了"每帧 new LinearGradient = 1 对象 + 1 colors List + 1 native SkShader"被否决）⇒ 曲线必须跨帧复用 Path/点缓冲。
2. **零重组**：`generation` 等状态只在 draw 阶段读（`AudioVisualizer.kt:461-463`、`:526-527`、`:569-570`；`MotionClock.kt:198-199` 组合期只读一次档位）；`PlayerCard.kt:828-831` 要求传 `StateFlow` 引用而非值。⇒ 曲线新参数若在组合期读 `WaveformStore` 的帧变化状态，就把重绘变成重组。
3. **失效机制单向**：`generationState`（`AudioVisualizer.kt:215-218`）只由 `pump` 返回值（`:316-338`、自增 `:337`）与手动交互 `toggleColoringMode`（`:385-388`，读侧 `:390-396`）推进，而 `pump` 的唯一调用者是 `MotionClock.kt:100`。⇒ 曲线若引入**新的逐帧动画量**，必须并入 `pump` 的返回判据或复用既有相位（`WaveformRing.kt:185-203`），否则会冻住——先例：`MotionClock.kt:173-181` 记录 v3.2.0 把呼吸收窄到精致档后，简洁档波形因"搭便车"消失而冻住，靠显式传 `waveformMounted` 才修好。
4. **不空转**：`pump` 在"没有新数据、已收敛、峰值已落、相位已停"时必须返回 false（`AudioVisualizer.kt:464-465`；`WaveformRing.kt:172-173`、`:185-203`、空转门槛 `ANIMATION_MIN_SIGNAL = 0.02f` `:429-434`）⇒ 曲线不能常驻重绘。
5. **档位/能力位是唯一开关面**：三档 `VisualizerTier.kt:36-49`；档位→能力位唯一映射 `VisualizerEffects.of`（`:213-252`）；位字段 `:101-152`（`rounded` `:109`、`peaks` `:111`、`flow` `:115`、`dots` `:117`、`breathe` `:119`、`shockwave` `:121`、`particles` `:123`、`perspective` `:125`、`tapInteraction` `:127`），派生 `anyShowcase` `:166`、`colorChannelMode` `:154-163`、`waveBandOn`/`waveBandLanes` `:169`、`:172`；读侧只有 `VisualizerPrefs.effects`（`derivedStateOf`、只读 `State`，`VisualizerPrefs.kt:208-213`）⇒ 曲线要么落进现有位，要么新增位并只在 `of()` 里映射一次。
6. **`color`/`brush` 二选一是 API 层规则**（`VisualizerTier.kt:154-163`；实现 `AudioVisualizer.kt:591`、`:736-737`、`:775-779`）⇒ "一条描边画完整段"与"每柱一笔、逐柱换色"是两种成本与着色模型，必须在档位层面显式选择，不能并存。
7. **几何契约要一并平移**：`gap`/`barWidth`（`AudioVisualizer.kt:580-581`）、镜像居中（`:759`、`:772`，位恒真 `VisualizerTier.kt:107`、`:237`）、`minBar = 1.dp`（`:582`、`:770`）、峰值与柱高必须同一 sqrt 映射（`:823-825`）、光点阈值（`:762`、`:692`）、圆角半径 `BAR_CORNER_RADIUS_DP = 2f`（`:683`、`:761`；"半径暂不收敛进 `AppShapes`"的遗留说明 `:673-682`）。
8. **两个挂载点必须同时成立**：前景条（`PlayerCard.kt:849`，高度来自 `PlayerCardLayouts.kt:156`、`:310` 的 `visualizerHeightDp`）与背景级波形（`PlayerCardBackdrop.kt:147-163`，额外乘 `0.26f` alpha `:160-161`、`:186`）⇒ "振幅 → 像素高"在两种高度下都要读得出来。
9. **C 档层次与裁剪**：涟漪在下、粒子在上、频带条最上（`AudioVisualizer.kt:587-590`、`:612-614`、`:617-627`）；`clipToBounds` 只在 `effects.anyShowcase` 时挂（`:540-544`）⇒ 曲线越出条带要么沿用该裁剪、要么重新论证（会动到 A/B 档"视觉零变化"的前提 `:541`）。
10. **流动模式下的 x 补偿**：整体 `translate` + 逐柱 `+phasePx`（`:594-603`、`:771`），相位 `flowPhase01()`（`:594`）、tile 比例 `:667`，Brush 只在尺寸变化时重建（`FlowBrushCache` `:896-920`）⇒ 曲线采样该 Brush 时必须复刻。
11. **非法值防御必须保留**：`finite01`（`:415-417`）、入环/移位 clamp（`WaveformRing.kt:142-144`、`:259`）。
12. **设计语言需显式裁定**：仓库顶层文档把 Kanesumi Design 定义为 "right-angle cuts, no curves, no rounded corners"（`AGENTS.md:111`），另见 "no rounded corners in the player"（`AGENTS.md:929`）；而现状已有 2dp 圆角柱（`AudioVisualizer.kt:683`）与圆弧类 C 档特效（`:940-986`）⇒ 曲线化应作为一次显式决定记录，而不是顺手改。
13. **没有自动降级退路**：`VisualizerFrameMonitor` / `FrameBudgetPolicy` 已无任何生产调用点（`MotionClock.kt:169-171`），帧间隔只有静态设备档（`AudioVisualizer.kt:448-454`）⇒ 曲线若更贵，出问题时没有"自动退回柱状"的机制可依赖。
14. **测试面**：重绘契约由环形缓冲单测钉住（`WaveformRing.kt:48-49` 点名 `WaveformRingTierTest`；`:272-274` 记录"用位移阈值判重绘会把柱子冻在非零值上"这一被单测抓到的缺陷）；异常隔离由 `TransparentWaveformSinkTest.kt:212`、`:231`、`:249` 钉住 ⇒ 改 `pump` 返回值或绘制判据都要同步这些测试（本探针按要求未运行测试）。
