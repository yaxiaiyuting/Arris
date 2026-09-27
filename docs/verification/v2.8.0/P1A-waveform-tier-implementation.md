# v2.8.0 · P1-A 波形效果分级：交付说明（实现侧）

> 本文是实现者交给上游的**事实清单**：每个档位实际画了什么、哪些没做、为什么，
> 以及**没有验证过的部分**。所有结论都对应到具体的提交与 `file:line`，
> 没有本轮实测帧时间数字（本环境无设备、无 release 包，与两份探针同一前提）。

前置阅读：`probe-waveform.md`（现状与两个真问题）、`probe-waveform-tier.md`（分级方案与成本）。

## 0. 提交清单

| # | 提交 | 内容 |
|---|---|---|
| 1 | `fix(player): 波形 tee 把 onBar 一并收进隔离边界，并前置可视化开关` | 探针 §4/§5 的两个真问题 + 零分配 RMS + 删死常量 |
| 2 | `feat(waveform): 新增波形效果分级的三档纯逻辑与 ncrust_settings 读写/迁移` | `ui/player/waveform/{VisualizerTier,VisualizerPrefs,VisualizerStrings}.kt` |
| 3 | `feat(waveform): 落地简洁/精致两档渲染` | `WaveformRing` 峰值/相位 + `AudioVisualizer` 分级绘制 |
| 4 | `feat(waveform): 落地炫技档（冲击波 · 粒子 · 3D 透视）` | `WaveformEffectsState` + 拖拽→点按的降级 |
| 5 | `feat(waveform): 帧时间持续超标时自动降一级` | `FrameBudgetPolicy` + `VisualizerFrameMonitor` |
| 6 | `perf(waveform): 帧时钟回调提升到循环外` | 去掉每帧 1 个 lambda 分配 |

## 1. 探针查出的问题：修了什么（提交 1）

| 探针结论 | 修法 | 落点 |
|---|---|---|
| §4 `WaveformStore.onBar(rms)` 在 `runCatching` **之外**（唯一还能打挂播放的路径） | 「RMS + 回调」收进同一个 `try`，`catch (Throwable)` 后**丢弃这一根柱**（不补 0：补 0 会画出假的静音凹陷），失败计数落 `droppedBarCount`（volatile，不拼字符串） | `player/TransparentWaveformSink.kt:132-142` |
| §5 关掉可视化仍在音频线程做全样本 RMS | 开关判断前置到 RMS 之前，用既有的 `@Volatile` 进程内镜像（**音频线程不读盘**） | 同上 `:135` |
| §6.1 每 `handleBuffer` 2 次堆分配（`duplicate()` + `asShortBuffer()`） | 改成绝对下标读字节：零分配、零副作用（不动 position/limit/order）、按 nativeOrder 自己拼 16/32 位整数 | `PcmRms.of` `:181-230` |
| §0 发现 1 `BARS_PER_SECOND = 30` 已是死常量、`CAPACITY=256` 的「积压 8.5 秒」过期 | 删掉常量；注释改成**真实柱率 = `handleBuffer` 回调率**（代码不保证任何固定值），容量只承诺「最多积压 256 根柱，再多丢最旧的」 | `ui/player/AudioVisualizer.kt` `WaveformStore` |
| 顺带：`WaveformAudioBufferSink` 两处过期注释 | 改为 `TransparentWaveformSink` | 同上 |

`TransparentWaveformSink` 新增三个构造参数（`enabled` / `onBar` / `rootMeanSquare`）作**单测注入点**，
生产路径全用默认值（对象方法引用 = 单例，连构造都不分配）。不注入就测不了「关掉开关后 RMS 到底跑没跑」。

## 2. 三档实际实现了什么

| 效果 | 档位 | 实现 | 每帧增量 |
|---|---|---|---|
| 镜像对称（上下对称） | A | **现状本来就满足**（柱以中线为中心画），只把它写进能力位与文档，未改绘制语义 | 0 |
| 圆角柱 + 间距 | A | `drawRoundRect`（`CornerRadius` 是 value class）；间距是现状已有的 `gap = width × 0.28 / n` | 0（同类笔数） |
| 峰值保持 | A | `WaveformRing.peaks` + `peakHoldMs` 定长数组；420ms 保持后按 0.9/s 下落且不低于柱高 | +≤28 笔（仅当峰值高出柱顶 1.5dp） |
| 缓动衰减 | A | 已实现（起音 τ=22ms / 回落 τ=130ms），**确认并复用**，未改 | 0 |
| 按时序着色（**非频谱**） | A | 沿用现状的「越旧越淡」alpha 阶梯，命名与文案如实标注 | 0 |
| 渐变流动 | B | `FlowBrushCache`：缓存 1 个 Brush + `TileMode.Repeated` + 每帧只改 `translate` 相位；画布整体左移、每根柱右移抵消 ⇒ 柱子不动、色带在流 | 0（多一次 canvas save/restore） |
| 柱顶光点 | B | 纯色 `drawCircle`（不加径向光晕：28 个 shader/帧是探针明确不建议的路线） | +≤28 笔 |
| 呼吸 | B | 只乘 alpha（改 Brush 参数会迫使 shader 每帧重建）；相位用 cos ⇒ 起播是满亮度 | 0 |
| 冲击波 | C | RMS 相对基线的突变 + 180ms 冷却触发；涟漪池定长 3，寿命 700ms，描边圆扩散淡出 | +≤3 笔 |
| 粒子 | C | SoA 定长池（x/y/vx/vy/life 五个 `FloatArray`），归一化坐标，寿命 650ms | +≤16 笔 |
| 3D 透视 | C | 父层 `graphicsLayer { rotationX = 9°; cameraDistance }`，**恒定倾角** ⇒ 不产生逐帧 layer 失效 | 1 层合成 |
| 多频段分色 | — | **不做**（见 §3） | — |

**默认档**：`VisualizerTier.defaultTier(isLowRamDevice, totalMemBytes, sdkInt)` =
低端机（`isLowRamDevice` **或** `totalMem ≤ 3.5 GiB` **或** `SDK_INT < 26`）→ 简洁，其余 → 精致。
这是那三条既有判据在波形档位上的**单一落点**（原先分散在 `AudioVisualizer` / `LyricsDisplayPrefs` /
`PlaybackService` / `NcrustApplication` 四处，口径互不相同）。

## 3. 「多频段分色」为什么不做（明确选择）

探针结论是「当前没有频域数据源」：真频段要么在音频线程倍增计算（与 v2.2.1 P0 同级风险），
要么新增 PCM 环 + UI 侧 FFT（重做数据层）。本版两条都不做，并且**不提供任何"看起来像频谱"的视觉**：

- 现有 alpha 阶梯被**如实命名**为「按时序着色」（亮度 = 时间新旧），代码注释与设置项文案都写明
  「不是频谱：本版没有频域数据，不区分低/中/高频」（`VisualizerStrings.Zh.NOT_SPECTRUM_HINT`）；
- `VisualizerEffects.spectrumColoring` 是一个**恒为 false 的能力位**，留作将来真做频域数据时的单点落点，
  并有单测钉住「它永远不与渐变流动同时为真」。

理由：画一个会被用户读成频谱的假东西，比不画更糟（信息优先正典）。

## 4. 「拖拽交互」降级为「点按切换着色」（明确选择）

**本版不挂拖拽手势**，`visualizer_drag` 的语义是**点按**：在「渐变流动 ↔ 按时序着色」之间切换。
理由三条：

1. **挂载点不在本任务允许改的文件内**：可视化条由 `PlayerCard.kt` 的 `AudioVisualizerSlot` 挂载，
   手势归属判定按仓库范式（`ui/player/PlayerDragSnap.kt`）应与它同层；
2. **附近已有命中面**：大屏左栏可视化条正下方就是 `clickable { onSongInfoClick() }` 的歌名/歌手区，
   卡片根部还有两个 `pointerInput`（整卡拖拽 / 展开态吞事件）。整卡拖拽在 `bigScreenActive` 时
   被显式停用（`if (bigScreenActive) return@pointerInput`），但「某一形态下刚好没冲突」不等于
   **手势分解（slop / 方向认领）**是对的；
3. **无设备 ⇒ 无法取证**：AGENTS.md 的触摸陷阱合集与 v1.7.0 · P0 都要求这类判定必须真机 A/B。

**冲突规避方案（本版实际做法）**：开关**默认关 ⇒ 默认零新增命中面**（关掉时连 `pointerInput`
都不挂）；打开后只挂 `detectTapGestures`（不消费 MOVE、不与拖拽争方向），与整卡拖拽/点按展开
不存在同一手势上的竞争。文案也如实写「点按切换着色」而不是「拖拽」。
真要做拖拽：先补一份「手势归属」纯函数 + 单测（照 `PlayerDragSnap.kt`），再上真机 A/B。

## 5. 帧时间自动降级（提交 5）

- 信号：`Window.addOnFrameMetricsAvailableListener`（API 24+），取 `FrameMetrics.TOTAL_DURATION`。
  **不用 `withFrameNanos` 间隔当判据**（S6 流水线式掉帧，v1.6.0 已踩过并回退）。
- 判据（`FrameBudgetPolicy`，纯逻辑）：60 帧滑窗内超标帧数 ≥ 24（40%）才算「持续」；
  预算 16.67ms = 一个 vsync；窗口未满不结算；非正时长（读不到）忽略。
- 有界：每实例最多判定一次 → 判定成立**立刻注销监听** → 只降**一级** → 落 `visualizer_auto_downgraded=true`
  → **永不自动恢复**；`tier=0` 与「已降过」都不注册监控。
- 隔离：注册 / 回调 / 注销三处都在边界里（回调在主线程，抛出去就是崩溃）；
  Activity 找不到（预览/测试宿主）就静默不监控。
- 诚实边界：本判据**不知道**是谁把帧顶起来的（归因要 Perfetto）。误判代价是「少看一层特效」，
  不是「被反复降档」。

## 6. 设置项与迁移

`ncrust_settings` 新增 8 个键（键名字面量集中在 `VisualizerPrefs`，有单测逐字钉住）：

| 键 | 类型 | 默认 |
|---|---|---|
| `visualizer_tier` | Int | **解析出来的**：低端机 0，其余 1（缺 key 不写回盘，用「键是否存在」区分「没选过」与「选了 1」） |
| `visualizer_showcase` | Bool | false |
| `visualizer_shockwave` / `_particles` / `_perspective` / `_drag` | Bool | false |
| `visualizer_auto_downgraded` | Bool | false |
| `visualizer_tier_version` | Int | 1（迁移水位，幂等；v1 无键可搬 ⇒ 缺 key 时严格 no-op） |

- 非法值（越界 Int）回落**解析默认档**（不是常量 1）；
- 类型脏数据不抛异常，回落默认；
- 迁移不删不改任何既有键（`audio_visualizer` / `wifi_quality` / `theme_index` 逐值断言）。

## 7. 未验证项与风险（**不要当成已验证**）

1. **帧时间全部是静态推导**：本环境无设备、无 release 包。三档在 S6 / PCL110 上的实际
   P50/P90/P99 与 janky% **一个数字都没有**。验收标准与测量命令见 `probe-waveform.md` §6.2。
2. **渐变流动的 shader 采样开销**、**3D 透视新增 render layer 的合成成本**只能在真机上看；
   后者是 C 档里最贵的一项（所以默认关）。
3. **`handleBuffer` 的真实回调率**仍未知（探针 §7 #1）：节拍检测的时间分辨率、涟漪节奏都受它影响。
4. **30Hz 量级 RMS 上的节拍误触发率**没有标注数据集，只有代码级门槛与冷却。
5. **点按交互与大屏/平板手势的真实分解行为**未在真机验证（默认关，风险面=打开该开关的用户）。
6. **峰值/涟漪/粒子在低端机上的实际观感**（420ms 保持、0.9/s 下落、16 粒子池）是设计取值，
   没有真机调参记录。
7. **R8 之后波形符号是否保留**未确认（无 release 产物 / `mapping.txt`）。
8. 圆角半径 token 未收敛进 `ui/theme/AppShapes.kt`（本任务不改该文件）：
   建议后续把 dp 半径 token 加进 `AppShapes`，并把 `CornerRadius(` 加进
   `AppShapesSingleSourceTest` 的 forbidden 列表（探针 §1 的建议）。
9. `PlaybackService.LOW_RAM_TOTAL_BYTES` 的量级 bug（`3_500L * 1024³` = 3.5 TiB ≈ 恒真）
   **本任务未修**（不属于本次改动范围）：波形档位取的是它想表达的值（3.5 GiB）。
   若将来把两处收敛成一个常量，必须连着修那边的量级，否则波形档位会退化成「永远简洁档」。

## 8. i18n 清单（16 条，已给中文基准 + 英文参考）

常量落点：`ui/player/waveform/VisualizerStrings.kt`（`Property` / `Zh` / `En`）。
属性名与文案见该文件；要点：

- `visualizerDragLabel` / `visualizerDragDescription` 必须写「点按」不能写「拖拽」；
- `visualizerNotSpectrumHint` 必须写明**不是频谱**；
- `visualizerTierDescription` 必须写明「低端设备默认简洁档」；
- `visualizer_auto_downgraded` **不需要用户可见文案**（只用于诊断与有界性判定）。

## 9. 测试与构建（本环境实际跑过的命令）

```bash
bash /home/duanjb666/deepseek/gw.sh :app:testDebugUnitTest   # BUILD SUCCESSFUL
bash /home/duanjb666/deepseek/gw.sh :app:assembleDebug       # BUILD SUCCESSFUL
```

本版新增单测 4 个类：

| 测试类 | 覆盖 |
|---|---|
| `player/TransparentWaveformSinkTest`（12 条，其中 6 条为本版新增） | 消费端抛异常不传播、RMS 抛异常被隔离、关掉开关后 RMS 一次都不跑、未知编码连开关都不读、零分配版与旧实现逐样本数值相等、极值符号对齐 |
| `ui/player/waveform/VisualizerTierTest`（21 条） | 档位解析（三条判据 + 边界值 + 内存未知）、越界回落解析默认、效果矩阵（含 C 档门控与硬互斥）、降级有界性 |
| `ui/player/waveform/VisualizerPrefsTest`（17 条） | 键名契约、缺 key/显式值/越界/脏类型、readEffects、迁移幂等与不碰既有键、自动降级只降一级 |
| `ui/player/waveform/WaveformEffectsStateTest`（18 条） | 节拍触发/不触发/冷却、上界、池满覆盖最旧、演完即停、暂停不新增、基线重置两种边界、清空、非法输入、可复现 |
| `ui/player/waveform/FrameBudgetPolicyTest`（11 条） | 单帧不降、窗口未满不结算、只判定一次、门槛边界、滑窗重评估、恰好等于预算、非正时长忽略、自定义参数、非法参数 |
| `ui/player/WaveformRingTierTest`（14 条） | 峰值跟随/保持/下落不穿柱/归零后才停/暂停有界、能力位关掉即恒零、相位前进与循环、静音不排帧、呼吸区间、老重载等价 |

既有 `WaveformRingTest`（10 条）**一行未改**、全部照旧通过：`pump(active, dtMs)` 重载
等价于预分配的 `VisualizerEffects.BASELINE` 单例（不是默认参数——默认参数会每帧构造对象）。
