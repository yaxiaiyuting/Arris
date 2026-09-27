# 探针 · 波形可视化的效果分级（A/B/C 档）：落点、每帧增量、风险

> **方法声明（先读）**：本文是**只读代码审计**。本环境**没有设备、没有 release 包**，
> 因此**全文没有任何本次实测的毫秒/帧率数字**。所有成本都是**代码推导 + 相对量级**，每条都标了依据；
> 标【估算】的是「由 API 语义推出、但无法在此环境量化」的部分。文中出现的真机数字（S6 / PCL110）
> 一律是**仓库既有记录**的转引并已标注出处，不当作本次结论。
> 本文件是本次任务唯一被写入的文件，未修改任何源码。

---

## 0. 现状基线（后面所有成本都以它为基准）

| 环节 | 落点 | 现状 |
|---|---|---|
| 音频旁路 | `VisualizerRenderersFactory.kt:70-71`（`TeeAudioProcessor` + `TransparentWaveformSink`），**不用** media3 的 `WaveformAudioBufferSink`（同文件 `60-69`，原因见 `TransparentWaveformSink.kt:8-43` 的 v2.2.1 P0） | 每个 `handleBuffer` 回调对**整块 PCM 逐样本**求一个 RMS 标量（`TransparentWaveformSink.kt:81-89`、`PcmRms.of` `112-139`） |
| 单写者环形缓冲 | `WaveformRing.kt:50`（`FloatArray(capacity)`）、`71-76`（push） | 音频线程：一次数组写 + 一次 volatile 自增；**零分配/零锁/零 IO**（`17-24`、`TransparentWaveformSink.kt:77`） |
| 平滑 | `WaveformRing.pump` `95-104` / `approach` `154-170` | 时间常数形式（起音 22ms / 回落 130ms，`189-192`），与刷新率无关；收敛即 `return false`（不再要求重绘） |
| 帧时钟 | `AudioVisualizer.kt:198-221` | `withFrameNanos` 限流到 `visualizerFrameIntervalMs`（`160-166`）；暂停/缓冲走 `delay`（`213-218`） |
| 绘制 | `AudioVisualizer.kt:223-251` | **28 次 `drawRect`**（`BAR_COUNT=28`，`89`）、sqrt 幅度（`238`）、按新旧 alpha 阶梯（`241`）、间距**已有**（`229-230`，`gap = width × 0.28 / n`） |
| 零分配证据 | `AudioVisualizer.kt:194`（`remember` 的 `FloatArray`）+ `226`（`snapshot` 原地拷贝）+ `WaveformRing.kt:173-176` | 绘制循环里没有堆分配（`Offset/Size/Color` 在 Compose 里是 value class）【估算，依据 Compose 1.7.x 类型语义】 |
| 现有 Brush 用法 | `NcrustLyricsPanel.kt:831-840`（`drawRect(brush = Brush.horizontalGradient(...))`，注释说明 `TileMode.Clamp` 的越界语义） | 全仓唯一的逐帧 Brush 构造点，且只对**当前行**构造 1 个/帧 —— 可容忍 1~2 个/帧的先例，不是「可容忍 28 个/帧」的先例 |
| 挂载判据 | `PlayerLayout.kt:79-87`（`visualizerSlot`），调用点 `PlayerCard.kt:257-263`、`1009-1016`、`1136-1142`；高度 `PlayerLayout.kt:190-192` | 关掉 = **整块不挂载**（`AudioVisualizer.kt:33`、`PlayerCard.kt:1980-1981`） |
| 设备档位 | `AudioVisualizer.kt:160-166` | `SDK_INT < 26` 或 `isLowRamDevice` → 33ms 上限 |

**三条写在代码里的硬契约**（新增效果必须逐条对照）：
① 音频线程只允许零分配/零锁/零异常（`WaveformRing.kt:17-24`、`TransparentWaveformSink.kt:77-79`）；
② 只在 **draw 阶段**读状态、只重绘不重组（`AudioVisualizer.kt:168-184`、`223-226`）；
③ 不空转：数据归零且收敛后必须停止失效（`AudioVisualizer.kt:176-177`、`WaveformRing.kt:95-104`）。

**探针发现（两处，评估必须先知道）**：
1. `BARS_PER_SECOND = 30`（`AudioVisualizer.kt:98`）**已经是死常量**：全仓 `grep` 只有这一行，
   而 v2.2.1 换掉的 `WaveformAudioBufferSink` 才是它的消费者。现在**推柱率 = `handleBuffer` 回调率**
   （`TransparentWaveformSink.kt:81`），不由代码保证。`CAPACITY=256` 的注释「最多积压 8.5 秒」
   （`AudioVisualizer.kt:100-101`）建立在 30 柱/秒这一**未再被强制**的假设上。
   ⇒ 所有「按柱/秒换算时间窗」的设计（节拍冷却、涟漪寿命）都要先钉住真实推柱率。
2. 全仓 `app/src/main` **没有任何 FFT / 频域 / Goertzel 代码**（`grep -rni "fft|goertzel|spectrum"` 0 命中）
   ⇒ B 档「多频段」不是「接一下现有数据」，是**新增数据源**。

---

## 1. 逐效果：落点 / 需要新增的数据 / 每帧增量 / 风险

图例：`draw op` = 每帧 Canvas 绘制调用笔数（现状 28）；「分配」= 每帧堆分配；【估算】= 无法在此环境量化的部分。

### A 档（简洁）

| 效果 | 落在哪 / 需要什么新数据 | 每帧增量（代码推导） | 风险 |
|---|---|---|---|
| **镜像对称** | 绘制循环 `AudioVisualizer.kt:233-250`。读法①「以中线上下对称」**现状已经满足**（柱以 `half` 为中点居中画，`231`/`244-249`）⇒ 增量 0。读法②「上/下两笔不同强度」= 每柱 2 笔：draw op 28→56 | 读法①0；读法② +28 draw op + 28 次小算术；0 新增分配、0 新增数据 | 低。但若做成「左右镜像（同一份数据画两遍，`abs(i-center)` 取索引）」要注意这是**数据语义变更**：`BAR_COUNT=28`（`89`）是 28 个时间切片，镜像后时间信息减半 ⇒ 更对称、信息更少 |
| **圆角柱 + 间距** | 间距**已实现**（`229-230`）。圆角 = `242` 的 `drawRect` → `drawRoundRect` | draw op 不变（28）；`CornerRadius` 是 value class ⇒ **0 新增分配**【估算】 | 性能可忽略；**真正的成本是治理**：`AppShapesSingleSourceTest.kt:54-57` 只禁 `RoundedCornerShape(`/`CircleShape`/`CutCornerShape(`，而 `drawRoundRect` + 硬编码 `CornerRadius(2.dp.toPx())` **不会被拦住** ⇒ 圆角 token（`ui/theme/AppShapes.kt:9`、`61-116`）出现一个绕过口。【建议】给 `AppShapes` 加 dp 半径 token，并把 `CornerRadius(` 加进同一条 forbidden 列表 |
| **峰值保持** | 需新增 `peak: FloatArray(n)` 状态。放 **`WaveformRing`**（可 JVM 单测，`15`；同 `targets`/`bars` 的形状 `65-68`）或放 draw 阶段的 `remember` 数组（同 `194`） | +28 次比较/+28 次写每帧，与 `approach`（`154-170`）同量级；0 分配 | **中，且是本档最容易踩的一条**：`pump` 的收敛判据只看 `bars/targets`（`95-104`）。若峰值只活在 draw 里，一旦 bars 收敛 ⇒ `pump` 返回 false ⇒ `generation` 不再自增（`AudioVisualizer.kt:127-129`）⇒ **峰值冻在画面上永不落下**。这正是 v1.8.1 被单测抓到过的同形状缺陷（`WaveformRing.kt:145-153`）。峰值状态必须并入收敛判据并加 `WaveformRingTest` 用例 |
| **缓动衰减** | **已实现**：回落时间常数 130ms（`WaveformRing.kt:156-157`、`192`） | 0 | 低。若要做成「可配置长尾」，`ATTACK/RELEASE_TAU_MS` 是 companion 常量（`189`/`192`），改成实例参数会动到既有 10 个 `@Test`（`WaveformRingTest.kt`）与仓库记录过的观感基线（`AGENTS.md:1159`） |

### B 档（精致）

| 效果 | 落在哪 / 需要什么新数据 | 每帧增量（代码推导） | 风险 |
|---|---|---|---|
| **渐变流动** | `242` 的 `drawRect(color=)` → `drawRect(brush=)`。**任务书点名的 `Brush.infiniteLinearGradient` 在 Compose 里不存在**（Compose UI 1.7.x 的 `Brush` 工厂只有 linear/vertical/horizontal/radial/sweep + `SolidColor`）⇒ 必须用等价物 | 路线(a) **每帧新建 Brush**：每帧 1 个 `LinearGradient` + colors List + FloatArray(stops) + 1 次 native SkShader 构造 ⇒ 直接违反「每帧零分配」（`AudioVisualizer.kt:174-176`）【估算】。路线(b) **缓存 brush + `TileMode.Repeated` + 每帧 `withTransform { translate(phase) }`**：0 新增对象、0 新增 shader，仅多一次 canvas save/restore/translate，draw op 仍 28 ⇒ **推荐 (b)** | 尺寸变化（旋转/分栏/进出大屏）后必须重建 shader，否则渐变跨度停在旧尺寸；缓存要按 `size` 做 key。仓库里唯一的逐帧 Brush 先例是 `NcrustLyricsPanel.kt:831-840`（只对当前行构造 1 个/帧，且用 `TileMode.Clamp` 处理越界）——可容忍 1~2 个/帧，**不可**外推到 28 个/帧 |
| **柱顶光点** | `233-250` 循环内每柱追加一笔 `drawCircle` | draw op 28→56；`Offset` value class ⇒ 0 分配 | 低（纯色）。**若给每个光点加径向渐变光晕**，naive 实现 = 每帧 28 个 radial Brush ⇒ 28 个 shader/帧，与「渐变流动」同一条高成本路线，**不建议** |
| **多频段分色** | **当前数据无法近似频段**：音频线程只把整块 PCM 压成**一个** RMS 标量（`TransparentWaveformSink.kt:112-139`），UI 侧历史窗口只有 28 个 0..1 标量（`AudioVisualizer.kt:132`）。「按位置分色」表达的是**时间新旧**（现有 alpha 阶梯 `241` 就是它），不含频率语义 ⇒ 画成低/中/高频会被误读为真频谱 | 真频段两条路：(a) 音频线程对同一份样本做 N 段累加 ⇒ 现有逐样本循环（`120-134`）成本 ×N，**唯一会碰音频线程的方案**；(b) 音频线程只做一次**定长 memcpy** 进预分配 PCM 环，UI 线程每帧对 512/1024 点做 FFT/Goertzel ⇒ N=4 段 ×1024 点 ≈ 8k 乘加/帧、1024 点 FFT ≈ 20k flops/帧【估算，O(N log N)】。**推荐 (b)** | **高（数据层）**：`WaveformStore` 只有 `onBar(Double)`（`119-121`）、`WaveformRing` 是单值 `FloatArray` 环（`50`、`71-76`）⇒ 要重做一版数据通路，不是「改绘制」。(a) 的风险与 v2.2.1 P0 同级（音频线程上任何分配/异常/停顿 = 爆音，`WaveformRing.kt:17-24`） |
| **呼吸效果** | 绘制期读一个时间累加器（只改 alpha/scale）即可，**0 数据、0 分配** | 若呼吸只改 alpha：0 draw op、0 分配；若也改 brush 参数 ⇒ 迫使 brush 每帧重建，退化成上表路线(a) | **中（与契约冲突）**：呼吸是无限期动画 ⇒ 即使暂停/静音也要一直排帧，与「不空转」（`AudioVisualizer.kt:176-177`）以及仓库既有的「不做跑马灯（持续排帧与铁律 17 冲突）」判断（`AGENTS.md:3518`、`3633`）同形状。要么限定在 `active=true`（`AudioVisualizer.kt:203`）区间，要么显式给停止条件 |

### C 档（炫技）

| 效果 | 落在哪 / 需要什么新数据 | 每帧增量（代码推导） | 风险 |
|---|---|---|---|
| **冲击波（节拍涟漪）** | 检测放在 UI 线程 `WaveformRing.pump`（`95-104`），**必须用未平滑的 `targets`**（`65`、`127-141`）——用 `bars` 会把 onset 抹圆（起音 τ=22ms，`189`）。判定信号**只有 30Hz 的 RMS 序列** | 检测：每帧 O(K=8~16) 次比较（滑动均值 + 距上次触发时长）；同时存活涟漪 ≤2~3 ⇒ +3 draw op/帧 | **中**。诚实边界：30 柱/秒 ⇒ 时间分辨率 33ms（`AudioVisualizer.kt:98`，且该速率目前已无人保证，见 §0 发现 1）；RMS 是响度不是低频能量 ⇒ **底鼓/军鼓/人声重音不可区分**；母带压缩乐动态小 ⇒ 阈值法误触发率高（`AudioVisualizer.kt:234-237` 注释已记录「音乐 RMS 常落在 0.05~0.3」这一现象）。要分底鼓**依赖 B 档多频段**，两者是依赖而非并列。判定必须**严格有界**（铁律 1，`AGENTS.md:2828`）：涟漪数上限、寿命上限、冷却上限全部写死常量 + 单测 |
| **拖拽交互** | 需在可视化条上挂新的 `pointerInput`。**现状明确不挂**：`PlayerCard.kt:1982-1983`「本组件不挂任何 `pointerInput`/`clickable`，不新增命中面」 | 0 GPU；但需要一个新的「手势归属」纯函数 + 单测（照 `PlayerDragSnap.kt` 范式） | **高（交互回归面），但有两条可降低风险的事实**：① 可视化**只**在大屏/平板横屏挂载（`PlayerLayout.kt:79-87`），而这两种形态下**整卡拖拽本来就被显式停用**（`PlayerCard.kt:596-601`）⇒ 与竖屏整卡上拉/下滑的直接冲突面比想象小；② 仍需防「命中面随播放状态出现/消失」（暂停不挂载 ⇒ 手势入口时有时无），这正是 `AGENTS.md:452-465` 触摸陷阱合集的形状。**建议单列开关，不进三档** |
| **粒子** | 必须**预分配定长池 + SoA `FloatArray`**（禁止 `List<Particle>`/data class，否则每帧分配，`AudioVisualizer.kt:174-176`） | N=32 时 +32 draw op/帧、每粒子 ~6 次浮点更新、生死循环 O(N)【估算】；fill rate 不是瓶颈（条带只有 32~56dp 高，`PlayerLayout.kt:190-192`），瓶颈在 draw op 数与每笔 paint 配置 | 中。① 与呼吸同一条帧时钟冲突（粒子活着就必须重绘，必须并入收敛判据）；② 视觉噪声 vs「信息优先」；③ 低端机上 draw op 线性增长 |
| **3D 透视** | 只能走 `Modifier.graphicsLayer { rotationX/rotationY + cameraDistance }` —— DrawScope 只有 2D 变换（`withTransform/rotate/scale`），**没有 cameraDistance 语义** ⇒ 这是**新增一个 render layer** | 每帧一次矩阵合成 + 该层的 GPU 合成；`rotationX` 本身不强制离屏（`alpha<1`/clip 才强制）【估算，依据 Compose graphicsLayer 语义】 | 低端机合成成本可能最高的一项，**本环境无法量化 ⇒ 必须真机 A/B**。**与圆角柱不冲突**（圆角在 local 空间画好再被父矩阵变换）；**与镜像对称语义冲突**（透视本身已表达一个空间轴）；与「越靠左越淡」的 alpha 阶梯（`241`）叠加会削弱纵深。另：3D 会让「柱高 = 能量」失真，与信息优先正典有张力 |

---

## 2. 可组合性矩阵

✅ 可同时开（成本线性叠加）／⚠️ 需约束或降级／❌ 硬互斥。

| 组合 | 结论 | 依据 / 叠加成本 |
|---|---|---|
| 圆角柱 × 镜像对称 | ✅ | 同一循环内的绘制形状变化，draw op 仍按笔数线性 |
| 圆角柱 × 3D 透视 | ✅ | 圆角是 local 空间几何，父层矩阵只做变换 |
| 圆角柱 × 渐变流动 | ✅ | `drawRoundRect(brush=)` 存在，路径与笔数不变 |
| 镜像对称 × 3D 透视 | ⚠️ 设计冲突 | 透视已暗示空间轴，镜像再叠一层纵深语义打架 |
| **渐变流动 × 多频段分色** | ❌ **硬互斥** | 同一笔绘制 `drawRect` 要么 `color=` 要么 `brush=`。共用一条带渐变 ⇒ 无法逐段上色；每段一个 brush ⇒ N 个 shader/帧（高成本）。**这不是优先级问题，是 API 层的二选一** |
| 渐变流动 × 柱顶光点 | ✅ | 光点用纯色即可 |
| 渐变流动 × 呼吸 | ⚠️ | 呼吸若也动 brush 参数 ⇒ 每帧重建 brush，退化成 §1 路线(a) 的高成本；只动 alpha 则 ✅ |
| 峰值保持 × 缓动衰减 | ✅ 互补 | 同一数组：先取 max 再按 τ 衰减 |
| 峰值保持 × 冲击波 × 粒子 | ⚠️ | 三者都持有「还没演完」的状态，**必须共用同一个收敛条件**（否则 §1 冻结陷阱 ×3） |
| 粒子 × 冲击波 | ✅ 语义同源 | 粒子可由涟漪生成，但两者都要有界 |
| 呼吸 / 粒子 / 涟漪 ×「暂停即停帧」契约 | ⚠️ | 三者都破坏 `AudioVisualizer.kt:176-177`，需显式给总停止条件（例如静音或暂停 N 秒后停动画） |
| 拖拽 × 任意其他效果 | ✅ 技术可行 | 但新增命中面，建议独立开关，不进档位组合 |
| 3D 透视 × 粒子 | ✅ | 同一 layer 内，成本叠加 |

---

## 3. 分级方案建议

### 3.1 三档 vs 细粒度开关 —— **推荐三档（+ 内部能力位，不暴露给用户）**

理由全部来自本仓库既有纪律，不是偏好：
① 细粒度 N 个开关 = 2^N 个组合，而每个组合都要 release + 真机的 A/B 才能声称结论（铁律 16/22，`AGENTS.md:3195`、`3931`）——组合爆炸**无法取证**；
② 本仓库对「同一件事的多个落点」有明确的收敛范式（`AGENTS.md:3117-3118`：单一落点 + 机器守卫），档位正是一个落点；
③ 现有开关已经是「布尔 + 单一读写入口」（`AudioVisualizer.kt:35-74`，key `audio_visualizer` `38`，默认 `true` `40`），扩成三档是最小改动；
④ 内部能力位（每个效果一个 bit）仍然保留 —— 它让「关掉某一项」有单点落点，只是不暴露成 UI 开关。

**档位定义（建议）**：`T0 简洁` = 现状逐像素保留（28 笔 `drawRect` + sqrt + alpha 阶梯 + 时间常数平滑）；`T1 精致` = T0 + 圆角柱 + 峰值保持 + 柱顶光点（纯色）；`T2 炫技` = T1 + 渐变流动（缓存 brush + translate）+ 冲击波 +（可选）镜像对称。**3D 透视与拖拽、粒子不进档位**，各自独立开关（理由见 §1：前者无法在无设备环境评估、后两者风险最高）。

### 3.2 低端机自动降级判据 —— 仓库**已有**现成判据（但分散三处，口径不同）

| 判据 | 现成落点 | 覆盖范围 |
|---|---|---|
| `ActivityManager.isLowRamDevice` | `AudioVisualizer.kt:161-164`（可视化自己的帧率档）、`LyricsDisplayPrefs.kt:250-256`（歌词软边降级） | **只覆盖 ≤1GB 机型**（`PlaybackService.kt:341-343` 明确记录了这个缺口） |
| `MemoryInfo.totalMem` 分档 | `PlaybackService.kt:345-347`（阈值 `LOW_RAM_TOTAL_BYTES = 3.5GB`，`291`）、`NcrustApplication.kt:61-67`（图片缓存 3GB/6GB 分档） | 覆盖 3GB 级老机（S6 一类） |
| `Build.VERSION.SDK_INT < O`（API 26） | `AudioVisualizer.kt:163` | 覆盖 API 24/25 |

【建议】抽一个**单一落点**（例如 `visualizerAutoTier(context)`）取三条的并：`SDK_INT < 26 || isLowRamDevice || totalMem ≤ 3GB` → 起始档 `T0`，否则 `T1`。**不引入新判据**（避免第三个口径）。必须写明它是**静态判据、不是实测帧时间**，依据是 `AudioVisualizer.kt:147-158` 记录的 S6 流水线式掉帧教训（帧间隔量不出真实压力，v1.6.0 已踩过并回退）与 `AGENTS.md:1159`。

### 3.3 prefs 键设计（建议；全部落在既有 `ncrust_settings`，`AudioVisualizer.kt:37`）

| key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `audio_visualizer` | Boolean | `true`（不变，`AudioVisualizer.kt:38/40`） | **总开关**。语义保持不变：关掉 = 整块不挂载（`PlayerCard.kt:1980-1981`） |
| `visualizer_tier` | Int | `1`；自动判低端机 → `0` | 合法 0/1/2；**非法值回落默认**（同 `offline_cache_mb` 的既有口径，`AGENTS.md:393`、`994`） |
| `visualizer_auto_downgraded` | Boolean | `false` | 运行期自动降级标记（诊断 + 防止反复振荡），见 §4 |
| `visualizer_tier_version` | Int | `1` | 迁移版本号；范式 = `QualityLadder.migrate`（`QualityLadder.kt:53-63`：读版本号 → 幂等 → 启动时调用） |

**迁移策略（建议）**：① 老用户只有 `audio_visualizer` 时**不得**被解读成「用户选了最低档」——缺 key 一律取默认档（纪律来源：v1.9.3「缺失 vs 空」两义性，`AGENTS.md:414`、`1545`）；② 迁移判定抽**纯函数 + 单测**（同 `QualityLadder` / `LyricsCacheModelTest` 的做法）；③ 落盘 key 名加断言（v2.3.0 的「落盘 key 名断言」做法，`AGENTS.md:393` 一带）；④ **不新增 prefs 文件**。

---

## 4. 异常隔离与自动降级（建议）

### 4.1 分层边界：每一层允许做什么

| 层 | 失败后果 | 现有隔离（file:line） | 新增效果必须遵守 |
|---|---|---|---|
| 音频线程（tee + RMS） | **等于播放失败** —— v2.2.1 P0 的形状（`AGENTS.md:2821-2855`、`TransparentWaveformSink.kt:8-43`） | `runCatching` 兜底 + 未知编码返回 0（`TransparentWaveformSink.kt:86-87`、`78-79`、`106-110`） | 只做**定长 memcpy**；任何新 DSP 都不得在音频线程分配/加锁/抛异常（`WaveformRing.kt:17-24`） |
| 数据层（`WaveformRing`） | 画面异常 | 纯 Kotlin、可 JVM 单测（`WaveformRing.kt:15`）、NaN/Inf 防御 + 夹紧（`71-76`、`135`） | 峰值 / 涟漪 / 节拍检测状态**都放这一层**（可测），不放 draw 私有状态 |
| 帧循环（`AudioVisualizer.kt:198-221`） | 画面停更 | 只做「读时间 + pump」（`202-212`） | 不得在这里做检测/分配 |
| 绘制（`223-251`） | 掉帧 | 0 分配、draw 阶段读状态（`174-176`） | 禁止分配、禁止 I/O、禁止写 Compose 状态 |
| 手势（新增） | 交互回归 | 现状**零命中面**（`PlayerCard.kt:1982-1983`） | 判定纯函数化 + 单测（`PlayerDragSnap.kt` 范式） |
| 组件边界 | — | **不做 `@Composable` 内 try/catch**（理由见 `PlayerCard.kt:1985-1990`） | 用**逐效果前置条件检查**（数组长度/有限性/时间）把失败变成「这一帧不画该效果」，而不是抛异常 |

「**波形失败绝不影响播放**」的三层结论：① 结构上，tee 是**旁路消费者**，不向下游返回数据、不参与声道矩阵（`TransparentWaveformSink.kt:55-61`），这是 v2.2.1 用一次 P0 换来的结构性保证，**新增效果不得改变它**（尤其 B 档 (a) 路线会重新在音频线程加计算量）；② 异常上，音频线程用 `runCatching` + 未知编码静默返回 0；③ 渲染上，可视化任何异常只会让这块 Canvas 不出内容，不冒泡到播放链路（`PlayerCard.kt:1985-1990`）。

### 4.2 帧时间超标 → 自动关闭炫技：测什么、在哪测、怎么算有界

**候选指标（诚实排序）**：
1. **自家 draw 块的 CPU 时长**（`System.nanoTime()` 包住绘制内容）——只测录制/CPU 时间，**测不到 GPU 光栅**；
2. `withFrameNanos` 相邻回调间隔 —— 现成（`AudioVisualizer.kt:204-207` 已有 `now` 与 `dtMs`），但**不得单独用它做判据**（S6 流水线式掉帧，`AudioVisualizer.kt:147-158`、`AGENTS.md:1159`）；
3. `Window.addOnFrameMetricsAvailableListener`（**API 24+，正好等于本 app 的 minSdk**）拿 `totalDuration` —— 全仓 `app/src/main` 中 `FrameMetrics`/`JankStats`/`Choreographer` **0 命中**，这是唯一能拿到真帧时间的现成平台 API，但要在 Activity 侧新增观测面（【建议】若采纳，只用于诊断 + 降级，不参与任何 UI 逻辑）。

**判定点**：帧循环内（`AudioVisualizer.kt:202-212`），每帧累加，**只在 N 帧窗口结算一次**（例如最近 30 帧中超阈值帧数 ≥ K），窗口与阈值都是常量并加单测（照 `ListItemAppear.MAX_ANIMATED_INDEX` + `AppMotionSpecTest` 的范式，`AGENTS.md:3069-3079`）。

**有界失败（铁律 1，`AGENTS.md:2828`）**：只降**一级**（T2→T1→T0）；一个会话内最多降一次；降级后**本会话不再尝试恢复**（禁止「升回去再降」的振荡路径）；结果写 `visualizer_auto_downgraded` 供诊断。静态判据（§3.2）只决定**起始档**，实测降级只做「再降一级」，两者不得互相覆盖（否则同一台机器每次冷启动观感不同）。

---

## 5. 本环境无法回答、必须真机 + release 才能定的问题

1. 三个档位各自在 S6（API 24 / 3GB / 低端基线）与 PCL110 上的单帧成本与 jank 率；仓库既有 S6 A/B 记录（`AGENTS.md:1121`、`1170-1171`）只覆盖**现状 T0**。
2. 渐变流动的 (b) 路线（缓存 brush + translate）在真实 GPU 上的 shader 采样开销。
3. 3D 透视新增 render layer 的合成成本（本环境无设备，无法量化）。
4. `handleBuffer` 的真实回调率与块大小（决定 §0 发现 1 的「每柱代表多少毫秒」、节拍分辨率与涟漪寿命）。
5. 30Hz RMS 上的节拍检测误触发率（需要真实曲目集合做标注）。
6. 新增 `pointerInput` 后与大屏/平板横屏手势的真实分解行为（slop、方向认领）。
7. 自动降级的阈值 K/N 取值（无实测帧时间分布就无法定阈值；**先立判定点、后定阈值**）。
