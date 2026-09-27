# 波形 / 音频可视化现状探针（v2.8.0）

> **只读调研：本文没有修改任何源码 / 构建文件。** 行号在当前工作区 `HEAD = 4b0a124`
> （`versionName = 2.6.2-gpl` / `versionCode = 50`，`app/build.gradle.kts:284-285`）上核对过。
> **环境**：本机**无任何设备**（`/dev/bus/usb` 不存在、`/dev/kvm` 不存在）、`app/build` 无编译产物、
> 仓库根无 `dist/` ⇒ **本文不含任何本轮实测帧时间数字**；引用的历史数字均标注「非本轮测量」。
> 无法从代码确认的一律进 §7「未确认」。

## 0. 结论先行

| # | 结论 | 证据 |
|---|---|---|
| 1 | 渲染是**纯 Compose `Canvas` 自绘**：每帧 28 次 `drawRect` + 28 次 `sqrt`，无 Path / 无第三方库 / 无 shader / 无文字 | `AudioVisualizer.kt:223-251`、`:238`、`:242-249` |
| 2 | 颜色**只有 `primary` 一个槽**（`LocalMetroColors.current.primary`），当且仅当「主题色来源 = 跟随封面」时才是 HCT 派生色；默认（`PRESET`）是预设色 | `AudioVisualizer.kt:192`、`MainActivity.kt:302-318`、`ThemeManager.kt:107`、`CoverTheme.kt:125`、`NcrustColors.kt:119`、`AccentSource.kt:33-37` |
| 3 | 数据来自 **AudioSink tee**，**每根柱 = 一次 `handleBuffer` 回调**；`BARS_PER_SECOND = 30` 已无消费者，真柱率由解码器缓冲粒度决定 | `VisualizerRenderersFactory.kt:70-71`、`TransparentWaveformSink.kt:81-89`、`AudioVisualizer.kt:98`（全仓仅定义处） |
| 4 | 帧驱动 = `LaunchedEffect` + `withFrameNanos`（16/33ms 门）；**state 只在 draw 阶段读 ⇒ 不触发重组** | `AudioVisualizer.kt:198-221`、`:225` |
| 5 | **异常隔离只覆盖一半**：RMS 在 `runCatching` 内，`WaveformStore.onBar(rms)` **裸奔**；media3 的 `TeeAudioProcessor` 对 sink 回调**不做任何 catch** ⇒ 抛异常直达 `onPlayerError`（v2.2.1 级联形状） | `TransparentWaveformSink.kt:86-88`；`TransparentWaveformSinkTest.kt:53-66`；字节码核实见 §4 |
| 6 | **「关掉开关 = 零开销」不成立**：`enabled` 的门在 `WaveformStore.onBar`，而全缓冲 RMS 在它之前已算完 | `AudioVisualizer.kt:120` vs `TransparentWaveformSink.kt:86` |
| 7 | 音频线程**每缓冲 2 次堆分配**（`duplicate()` + `asShortBuffer()`），与 KDoc 的「零分配」契约不符 | `TransparentWaveformSink.kt:86`、`:120` vs `:77`、`AudioVisualizer.kt:118`、`WaveformRing.kt:21` |

## 1. 渲染方式与每帧绘制清单

**方式**：Jetpack Compose `Canvas`（`androidx.compose.foundation.Canvas`，`AudioVisualizer.kt:14`），
组件 `AudioVisualizerBars`（`AudioVisualizer.kt:185-252`）。不是原生 Canvas 子类、不是第三方库、
不是 `android.media.audiofx.Visualizer`（后者全仓零命中，且清单无 `RECORD_AUDIO`：
`AndroidManifest.xml:5-20`、`VisualizerRenderersFactory.kt:30-33`）。
**柱数** = `barCount` 形参，默认 `WaveformStore.BAR_COUNT`（`AudioVisualizer.kt:189`）= **28**（`:89`）。

| 序 | 每帧（一次 draw lambda）实际发生的事 | 次数 | 位置 |
|---|---|---|---|
| 1 | 读 `WaveformStore.generation`（draw 阶段 state 读） | 1 | `AudioVisualizer.kt:225` |
| 2 | `WaveformStore.snapshot(bars)` → `WaveformRing.copyInto`（Float 拷进复用数组） | 1（28 写） | `AudioVisualizer.kt:226`→`:132`→`WaveformRing.kt:173-176` |
| 3 | 提前返回 `n==0 \|\| width<=0 \|\| height<=0`；`gap` / `barWidth` / `half` / `minBar = 1.dp.toPx()` | 1 + 4 | `AudioVisualizer.kt:228`、`:229-232` |
| 5 | `coerceIn(0f,1f)` + `sqrt` + `coerceAtLeast(minBar)` + `alpha` | 28×4 | `AudioVisualizer.kt:238`、`:239`、`:241` |
| 6 | `barColor.copy(alpha=…)` / `Offset(...)` / `Size(...)` 构造 | 28×3 | `AudioVisualizer.kt:243-248` |
| 7 | **`drawRect`** | **28** | `AudioVisualizer.kt:242-249` |

**路径构造：无**（全仓波形代码没有 `Path`/`drawPath`/`drawIntoCanvas`/`Brush`/`Shader`）。
**每帧字符串 / 集合分配：无**（draw lambda 体内无任何 `String`/`List`/`Map`/`Pair` 构造）。
**每帧堆分配（源码可见）：0** —— `Color`/`Offset`/`Size`/`Dp` 均为 `@JvmInline value class`。
⚠️ 唯一待核的是 `withFrameNanos` 的挂起 lambda（`:204-212`）捕获 3 个变量 —— 见 §7 #3。

## 2. 颜色来源：HCT 链路与「是否复用播放器主题色」

**是，波形复用了播放器主题色，且只有一个色槽**：`val barColor = LocalMetroColors.current.primary`
（`AudioVisualizer.kt:192`，组合期读一次）。柱间层次靠 `copy(alpha = 0.30f + 0.70f*(i+1)/n)`
（`:241`、`:243`），**没有第二个颜色、没有 HCT 其它角色参与**。

| 步 | 封面位图 → 波形颜色 | 证据 |
|---|---|---|
| 1 | `applyCoverAccent(bitmap, url, gen)`；`Dispatchers.Default` 上缩到 112×112 → `getPixels` → `CoverThemeExtractor.extractBoth(px,w,h)` | `PlaybackService.kt:1105`、`:1113`、`:1118`、`:141`、`:1135`、`:1136`；定义 `CoverTheme.kt:87-100` |
| 2 | `downsample → CoverPaletteExtractor.extract → CoverPalette.fromSeed`（HCT + 量化 + 评分）；逐层 `runCatching`，失败即 `null` | `CoverTheme.kt:92-100`；`CoverPalette.kt:119-163`；`PlaybackService.kt:1117-1122`、`:1132-1141` |
| 3 | `onCoverTheme?.invoke(theme)` → `PlayerViewModel.coverTheme`；**仅当 `accentSource == COVER`** 才取调色板并交给主题 | `PlaybackService.kt:1149`、`:1164`、`:226`；`PlayerViewModel.kt:271`、`:598`；`MainActivity.kt:292`、`:318`、`:328-332` |
| 5 | `if (coverPalette != null) toNcrustColors(coverPalette, base) else base` | `ThemeManager.kt:97-108`（关键 `:107`） |
| 6 | `toNcrustColors` **覆盖 `primary`** = HCT `a1` tone 80（深）/ 40（浅） | `CoverTheme.kt:125`；`CoverPalette.kt:134`、`:150` |
| 7 | `NcrustColors.toMetroColors`：`primary = primary` → `MetroTheme` 注入 `LocalMetroColors` → 波形读取 | `NcrustColors.kt:115-119`；`MainActivity.kt:339`；`AudioVisualizer.kt:192` |

三种来源下波形的实际颜色（`accent_source` 默认 `PRESET`，`AccentSource.kt:33-37`）：
`PRESET` → `themeColorForIndex(themeIndex)`，如 `#1DB954`，**非 HCT**（`MainActivity.kt:308`、`NcrustColors.kt:59`）；
`COVER` → HCT `a1` tone 80/40，**覆盖** `primaryColor`，**是 HCT**（`CoverPalette.kt:134`、`:150`）；
`SYSTEM` → `processAccentColor(systemAccent)`（HSV 压饱和度，非 HCT）（`MainActivity.kt:306`、`AccentSource.kt:132-141`）。
⚠️ `COVER` 档另有一条**并行的非 HCT** 通路（androidx `Palette` → `processAccentColor`，`MainActivity.kt:302`），
其产物会被第 6 步覆盖 —— 即 `COVER` 档的波形色来自 HCT，`Palette` 那条只影响通知栏着色（`PlaybackService.kt:1150-1164`）。

## 3. 每帧更新机制

| 项 | 事实 | 证据 |
|---|---|---|
| 数据来源 | **AudioSink tee**：`setAudioProcessors(arrayOf(TeeAudioProcessor(TransparentWaveformSink())))` | `VisualizerRenderersFactory.kt:70-76`；装配 `PlaybackService.kt:378-380`、`:394` |
| 不是 `onAudioSamples` / 不是 `audiofx.Visualizer` | 前者在 media3 里无 `AudioSamples`/`AudioListener` 类；后者需 `RECORD_AUDIO`，清单无此权限 | `VisualizerRenderersFactory.kt:24-27`、`:30-33`、`AndroidManifest.xml:5-20` |
| 不是伪随机动画 | `WaveformStore.onBar` 全仓唯一调用点是 tee | `TransparentWaveformSink.kt:88` |
| 写线程 | ExoPlayer playback 线程（`ExoPlayer:Playback`，THREAD_PRIORITY_AUDIO） | `VisualizerRenderersFactory.kt:41-43`、`WaveformRing.kt:19-24` |
| 缓冲区 | 环形 `FloatArray(256)`，`@Volatile writeIndex` 单写者、无锁 | `AudioVisualizer.kt:101`、`WaveformRing.kt:50`、`:58-59` |
| 采样点数 | **每回调遍历整个 PCM 缓冲的全部交错样本**，所有声道合并成一个 RMS | `TransparentWaveformSink.kt:112-139`（16bit `:119-126`、float `:127-134`） |
| 柱率 | **= `handleBuffer` 回调率**（每回调一根柱）。`BARS_PER_SECOND = 30` 全仓仅定义处、**无消费者**，「积压 8.5 秒」按 30/s 推算 ⇒ 已失效 | `TransparentWaveformSink.kt:88`；`AudioVisualizer.kt:98`、`:100-101` |
| 帧驱动 | `LaunchedEffect` + **`withFrameNanos`**（不是 `Animatable`，也不是手动 `invalidate`） | `AudioVisualizer.kt:198-212` |
| 帧率上限 | API ≥ 26 且非低内存 → 16ms ≈ 60fps；API < 26 或 `isLowRamDevice` → 33ms ≈ 30fps；门 `now - lastFrameNs >= budgetNs` | `AudioVisualizer.kt:142`、`:145`、`:160-165`、`:205` |
| `dtMs` | `((now-lastPumpNs)/1e6).coerceIn(1f, 100f)` | `AudioVisualizer.kt:206-207` |
| 平滑 | 时间常数 `1-exp(-dt/tau)`：起音 22ms / 回落 130ms / 收敛截断 0.004 | `WaveformRing.kt:154-170`、`:189-195` |
| **是否重组** | **否** —— `generation` 只在 `Canvas` 的 draw lambda 里读，只失效这块画布的重绘；会重组的读只有 `VisualizerSetting.state.value`（开关切换时） | `AudioVisualizer.kt:224-226`；`PlayerCard.kt:248` |
| 静止 / 暂停时 | `pump` 返回 false ⇒ 不再重绘；但 `while(true)` **仍每 16/33ms 唤醒协程** ⇒ KDoc「不空转」只对「重绘」成立 | `WaveformRing.kt:103`；`AudioVisualizer.kt:202-220`、`:176-177` |
| 挂载范围 | 仅在展开态子树内（`progress > 0.01f`）⇒ 折叠态整块不组合、帧循环停止 | `PlayerCard.kt:721`、`:317` |

## 4. 异常隔离现状（对照 AGENTS.md 的 v2.2.1 · P0）

v2.2.1 根因链（`AGENTS.md:2821-2860`）：tee 请求 6→1 混音 → `ChannelMixingMatrix` 抛
`UnsupportedOperationException` → AudioSink 不可恢复 → 之后每档都失败 → 无上限降档 + 跳歌。
当时的修复契约在 `TransparentWaveformSink.kt:34-42`，落地在 `VisualizerRenderersFactory.kt:60-71`。

| # | 调用点 | 是否受保护 | 依据 |
|---|---|---|---|
| 1 | `PcmRms.of` 全缓冲 RMS（音频线程） | ✅ `runCatching{…}.getOrDefault(0.0)` | `TransparentWaveformSink.kt:86-87` |
| 2 | `WaveformStore.onBar(rms)` → `WaveformRing.push` | ❌ **裸奔**（在 `runCatching` **之外**） | `TransparentWaveformSink.kt:88`；`AudioVisualizer.kt:119-121`；`WaveformRing.kt:71-76` |
| 3 | `flush` | ❌ 裸奔，但体内只有 `PcmRms.bytesPerSample` 的 `when`（无异常面）—— 这正是 v2.2.1 的修复点 | `TransparentWaveformSink.kt:70-74`、`:106-110` |
| 4 | 未知编码 / 声道数 0 | ✅ 提前 return，宁可无可视化 | `TransparentWaveformSink.kt:82-83`、`:106-110`、`:135` |
| 5 | media3 的 `TeeAudioProcessor` 对 sink 回调；上游 `DefaultAudioSink.handleBuffer` | ❌ 都不兜：`queueInput` 中 `AudioBufferSink.handleBuffer` 调用点**无任何异常表**（`flushSinkIfActive → flush(III)` 同理）；`DefaultAudioSink` 的异常表**只**捕获 `AudioSink$InitializationException`，范围不含 `processBuffers` | 字节码核实（**非仓库文件**）：`~/.gradle/caches/…/media3-exoplayer-1.5.0-runtime.jar` |
| 6 | 异常最终落点 | `PlaybackService.onPlayerError`（`:441`）→ ViewModel 降档重试；AudioSink 侧另有 `onAudioSinkError`（`:565-577`，含 `player.stop()` + `clearMediaItems()`） | `PlaybackService.kt:441`、`:563-577` |
| 7 | `AudioVisualizerBars` 帧循环 / draw | ❌ 无 try/catch（**有意**：`@Composable` 里 try/catch 破坏重组语义）⇒ UI 线程异常，不污染播放链路 | `PlayerCard.kt:1985-1990`；`AudioVisualizer.kt:198-251` |
| 8 | `visualizerFrameIntervalMs` | `as? ActivityManager` 安全转换，但跑在组合期（`remember`）⇒ 异常面是 UI 崩溃 | `AudioVisualizer.kt:160-166`、`:196` |
| 9 | Service 启动路径装配 | ❌ 裸奔：`VisualizerSetting.read` / `VisualizerRenderersFactory(this)` / `ExoPlayer.Builder(...).build()` 均不在 try 内 | `PlaybackService.kt:378`、`:379`、`:394`；`VisualizerRenderersFactory.kt:70-76` |
| 10 | v2.2.1 回归单测 | ✅ 钉住「旧写法 6→1 必然抛 / 新写法不抛」 | `TransparentWaveformSinkTest.kt:53-60`、`:62-66` |

**残余风险（最需要传下去的一条）**：第 2 条是**唯一还能把播放打挂的路径**。`WaveformStore.onBar`
不在任何 `runCatching` 内，其下游当前不抛，但**契约上是裸的** —— 将来在 `push`/`onBar` 里加一行
可能抛的代码（加锁、拼日志、采样率换算），就会原样恢复 v2.2.1 的级联形状：tee 异常 →
`queueInput` → `DefaultAudioSink.handleBuffer` → `onPlayerError` → 降档循环。修法只在
`TransparentWaveformSink.kt:86-88` 一处：把 `onBar` 也收进同一个 `runCatching`。

## 5. 挂载点清单与设置项「音频可视化」

| 界面 / 组件 | 挂载落点 / 判据 | 证据 |
|---|---|---|
| 横屏**大屏模式**左栏（封面下、歌名上） | `if (visualizerSlot)` | `PlayerCard.kt:1012-1018` |
| **平板横屏**宽屏两栏左栏 | 同上 | `PlayerCard.kt:1138-1144` |
| 唯一 slot composable / 绘制组件 | `AudioVisualizerSlot` / `AudioVisualizerBars` | `PlayerCard.kt:1992-2008`；`AudioVisualizer.kt:185-252` |
| 判据函数（唯一落点） | `enabled && (bigScreenActive \|\| (isWidePlayer && isLargeScreen && landscape))` | `PlayerLayout.kt:79-87`；消费点 `PlayerCard.kt:257-263` |
| 高度 | 窗口高 × 0.11，夹 32~56dp | `PlayerLayout.kt:190-192`；`PlayerCard.kt:264-266` |
| 六格 A/B | 手机竖屏❌ / 手机横屏·大屏✅ / 手机横屏·非大屏❌ / 平板竖屏❌ / **平板横屏✅** / 平板横屏·大屏✅ | `PlayerLayout.kt:68-77`；单测 `PlayerLayoutVisualizerTest.kt:48-78` |
| 折叠态 | 整块不挂载（`progress <= 0.01f`） | `PlayerCard.kt:721`、`:317` |

| 设置项 | 值 | 证据 |
|---|---|---|
| prefs 文件 / key / 默认值 | `ncrust_settings` / `audio_visualizer` / `true`（开） | `AudioVisualizer.kt:37`、`:38`、`:40`、`:111` |
| 读 / 写入口（唯一） | `VisualizerSetting.read` / `.write` | `AudioVisualizer.kt:47-55`、`:57-65`；调用点 `MainActivity.kt:211`、`PlaybackService.kt:378`、`UserScreen.kt:153`、`:509` |
| UI | `SettingSwitchRow` + 8 语言文案 | `UserScreen.kt:503-511`；`Strings.kt:551-552`、`:1170-1172` |
| 关闭时 ① / ② | 波形**整块不挂载**（`enabled` 进 `visualizerSlot`，两处挂载点都是 `if (visualizerSlot)`）⇒ 连帧时钟都不跑；**不重建 ExoPlayer**，音频线程侧只多一次 volatile 读 | `PlayerLayout.kt:80`、`:85-87`；`PlayerCard.kt:1012`、`:1138`；`AudioVisualizer.kt:61-64`、`:106-111` |
| ⚠️ 关闭时**仍付出** | **全缓冲 RMS 照跑**：门在 `WaveformStore.onBar`（`:120`），RMS 在它之前已算完（`TransparentWaveformSink.kt:86`）⇒「关掉 = 零开销」不成立；「聚合由 media3 的 `WaveformAudioBufferSink` 完成」（`AudioVisualizer.kt:178-179`）是**过期注释**（该类已被 `VisualizerRenderersFactory.kt:60-71` 替换） | — |

## 6. 帧时间

> **本环境无设备（`/dev/bus/usb`、`/dev/kvm` 均不存在）、无 release 包（无 `dist/`、`app/build` 无产物）
> ⇒ 本轮拿不到任何帧时间。** 本节只给 (a) 静态成本代理 与 (b) 可执行的测量方案。

### 6.1 静态成本代理（全部可从源码判定）

| 指标 | 值 | 证据 |
|---|---|---|
| 每帧 `drawRect` / `sqrt` / Path / shader / 文字 / 图片 | **28** / 28 / 0 / 0 / 0 / 0 | `AudioVisualizer.kt:242-249`、`:238`、`:223-251` |
| 每帧 draw 阶段读 state 数 | **1**（`WaveformStore.generation`） | `AudioVisualizer.kt:225` |
| 每帧读 `Animatable` 数 / 组合阶段读 state 数 | **0** / 0（仅开关切换时 1 次重组） | `AudioVisualizer.kt:223-251`；`PlayerCard.kt:248` |
| 每帧字符串 / 集合 / 堆分配（源码可见） | 0 / 0 / 0（`Color`/`Offset`/`Size`/`Dp` 皆 value class） | `AudioVisualizer.kt:223-251`、`:243-248` |
| 每帧堆分配（挂起 lambda） | 待核，见 §7 #3 | `AudioVisualizer.kt:204-212` |
| 每 `handleBuffer` 堆分配（**音频线程**） | **2**：`buffer.duplicate()` + `asShortBuffer()` | `TransparentWaveformSink.kt:86`、`:120`；与「零分配」契约（`:77`、`AudioVisualizer.kt:118`）冲突 |
| 每 `handleBuffer` 浮点运算 / volatile 写 | `remaining()/bps` 次乘加 + 1 次 `sqrt` / 1 次写 | `TransparentWaveformSink.kt:118-138`；`WaveformRing.kt:75` |
| 静止收敛后每帧成本 | 无重绘；协程仍每 16/33ms 唤醒，做 1 次 volatile 读 + 28 元素 `any{}` 扫描 | `WaveformRing.kt:95-104`；`AudioVisualizer.kt:213-219` |

### 6.2 release 包帧时间测量方案（可执行）

**A/B 设计**：同一设备、同一 release 包，只切设置页开关，两轮各 `dumpsys gfxinfo … reset` 后跑
**相同时长（建议 25s）**、同曲目、同形态。前置：登录态 + 队列非空；手机需先点 ⤢ 进大屏模式，平板直接横屏。

```bash
# ① 装 release（脚本硬断言被测包不可 debuggable，见 benchmark/run_benchmark.sh:139-160）
benchmark/run_benchmark.sh install_release
# ② 帧 CPU 时间（首选，µs 级）
BENCH_ITERATIONS=3 BENCH_COMPILATION=ignore benchmark/run_benchmark.sh expand
# ③ 现场帧直方图（1ms 分辨率）
adb shell dumpsys gfxinfo com.takahashirinta.ncrust reset   # → 播放 25s →
adb shell dumpsys gfxinfo com.takahashirinta.ncrust
adb shell dumpsys gfxinfo com.takahashirinta.ncrust framestats   # 逐帧分段，抓偶发长帧
# ④ 归因（是可视化还是别的）
adb shell perfetto -o /data/misc/perfetto-traces/wave.perfetto-trace -t 15s \
  -a com.takahashirinta.ncrust sched freq idle am wm gfx view binder_driver hal dalvik camera input res memory
adb pull /data/misc/perfetto-traces/wave.perfetto-trace
```

- ② 读 `benchmarkData.json` 的 `metrics.frameDurationCpuMs.{P50,P90,P95,P99}` + janky%。**局限**：该基准只在
  竖屏滑动展开/收起（`ExpandPlayerBenchmark.kt:38-57`），**不覆盖可视化稳态**。
- ③ 读 `Janky frames` 与 `50th/90th/95th/99th percentile`；1ms 直方图，测不出 1ms 级差异。
- ④ 看 `Choreographer#doFrame` / `DrawFrame` 时长是否只在 ON 时变长；release 经 R8，需
  `app/build/outputs/mapping/release/mapping.txt` 反混淆（波形符号是否保留**未确认**）。
- **可视化专属基准尚无**：需在 `benchmark/src/main/java/…/benchmark/` 新增 `WaveformBenchmark`
  （`FrameTimingMetric()` + `HOT` + `swipe` 进大屏 + 稳态 `sleep`，照 `ExpandPlayerBenchmark.kt:22-58`），本轮不实现。

**判读标准（沿用仓库既有口径，`docs/verification/v2.5.4/probe-forwarding-attr.md:534`）**：
① `frameDurationCpuMs` **P90 Δ ≤ 1.0ms**；② Δ 不超过同配置重复跑的**噪声带 2 倍**；③ **janky% Δ ≤ 2pp**；
④ 冷启动 `timeToInitialDisplayMs` Δ ≤ 5ms（护栏）。可视化是**持续**每帧负载（不是一次性动画）
⇒ 必须同时看 **P99 与 janky%**，只看 P50 会漏长帧。**音频线程侧必须单独测**（帧指标完全看不见）：
§6.1 的每缓冲 2 次堆分配需 perfetto 的 `dalvik`/`heap` 类别观察 playback 线程 GC；
`AudioTrack` 欠载计数的**具体字段名未确认**，不要猜。

**仓库既有参考数字（历史记录，非本轮测量）**：`TASK.md:754-772` 记录 PCL110 大屏 v1.8.0 `~20fps`
→ v1.8.1 `61fps / Janky 0.00%`；S6 大屏 `20fps / 44.12% / 50th 15ms` → `29.2fps / 20.99% / 50th 13ms`。
v2.5.5 展开/收起帧 CPU 为 P50 4.73ms / P90 8.71ms（`docs/verification/v2.5.5/EVIDENCE.md:160`）。

## 7. 未确认 / 不确定（不要当结论用）

| # | 项 | 为什么未确认 |
|---|---|---|
| 1 | 实际柱率（`handleBuffer` 每秒回调次数） | 取决于解码器/渲染器送入 sink 的缓冲粒度，代码未固定，且本环境无设备 |
| 2 | `CAPACITY = 256` 对应的实际积压秒数 | 由 #1 决定；`AudioVisualizer.kt:100-101` 的「8.5 秒」按已失效的 30 柱/秒推算 |
| 3 | `withFrameNanos` 挂起 lambda 是否每帧一次堆分配 | 无 `app/build` 产物，无法 javap 核实；只能按 Kotlin 挂起 lambda 语义静态推断 |
| 4 | `WaveformRing.push` 的 `writeIndex` Int 溢出是否可达 | 理论越界路径（`ring[负数]`）；按 30 柱/秒需连续播放约 2.26 年，未做长时压测 |
| 5 | `PcmRms.of` 逐样本开销在 6 声道高码率下的实际占比 | 需设备 perfetto |
| 6 | release R8 后波形相关符号是否保留 | 无 `mapping.txt` 产物 |
| 7 | 波形真机帧时间 | 无设备、无 release 包 |
| 8 | `dumpsys media.audio_flinger` 中 underrun 的字段名 | 代码与仓库文档中均无可引用定义 |

**交叉参考**：`docs/verification/v2.5.4/probe-waveform-tablet.md`（平板横屏不显示的根因与修法）、
`docs/verification/v2.2.1/p0-quality-loop/PROBE.md`（tee 打挂 AudioSink 的根因链）。
