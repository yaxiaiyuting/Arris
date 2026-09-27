# v2.8.0 探针阶段 · 决策级汇总（PROBE-SUMMARY）

> 汇总对象（只读探针；本文件不改它们、也不改任何源码）：[probe-waveform.md](./probe-waveform.md) · [probe-waveform-tier.md](./probe-waveform-tier.md) · [probe-settings-inventory.md](./probe-settings-inventory.md) · [probe-settings-structure.md](./probe-settings-structure.md)
>
> **证据分级**：**【事实】** = 有 `file:line` / 字节码依据的代码事实；**【估算】** = 由 API 语义或代码形状推导、本轮无法量化的量级；**【未验证】** = 探针明确声明未确认，必须真机 / release 包 / 跑测试才能定。
> **本轮没有任何实测帧时间**：写探针时沙箱内无设备（`/dev/bus/usb`、`/dev/kvm` 均不存在），四份探针只交付「静态成本代理 + 可执行测量方案」（`probe-waveform.md:131-132`、`probe-waveform-tier.md:3-6`）。

## 0. 本次环境事实（已核实；照抄，不改数字）

1. **工作区曾被完全清空**：仓库、`tools/`、`dist/`、`Kanesumi-sec-a` 同级检出、`keystore.properties` 全部丢失。
   本次从 GitHub 重新克隆仓库（HEAD `4b0a124` = v2.6.2-gpl）与 `Kanesumi-sec-a`，并把 Gradle 缓存复制进工作区后恢复构建能力。
2. **原 release 签名密钥永久丢失**（不在 git：`.gitignore:18-20` 排除 `*.jks` / `keystore.properties`；也不在磁盘与会话日志中）。
   用户已生成新密钥 `ncrust-release-v2.jks`（alias `ncrust`，RSA 4096，SHA256
   `E6:2E:CA:39:B7:E5:E3:6B:C9:A0:5F:83:68:8D:CF:EC:C1:1F:C5:58:9A:40:97:F7:DB:BA:AA:7D:DF:BB:EC:82`）。
   **后果：本版 APK 与历史版本签名不同，老用户无法覆盖安装，必须卸载重装（会清掉登录态与离线缓存）—— 这条必须同时写进本汇总与最终 release note。**
3. **versionCode 决策**：`tools/next-version.sh`（本轮按 AGENTS.md 规范重建）三源交叉校验：
   ① 最近 5 个 tag 的 `app/build.gradle.kts` 最大 **50**（v2.6.2-gpl）；② 已发布 APK `Ncrust-v2.6.2-gpl-release.apk`
   的 `aapt2 dump badging` 实测 **50**；③ 工作区 `app/build.gradle.kts` = **50** ⇒ max 50 ⇒ 本版 **51**，versionName **`2.8.0-gpl`**。
4. **探针阶段的可测性边界**：写探针时沙箱内无设备，因此**探针文档里没有任何本轮实测帧时间**，只有静态成本代理与测量方案；
   **真机帧时间与设置迁移实测在后续 verification 阶段用 release 包补做**，设备为 S6 `SM-G9209`（Android 7.0 / API 24 / 2.7GB RAM）
   与华为 `WGR-W09`（Android 12 / API 31 / 7.9GB）；历史文档里的 PCL110 **本轮未连接**。
5. **任务书点名的文件不存在**（`probe-settings-structure.md:7-11` 已纠正）：`ui/screen/PlayerLayoutSetting.kt`、`ui/navigation/NavRoutes.kt`、`ui/navigation/MainNavGraph.kt` 均不存在；实际对应 `ui/screen/PlaylistLayoutSetting.kt`（歌单**布局模式**）与 `ui/navigation/NavGraph.kt` 内的 `NavRoutes`（`:25`）/`MainNavGraph`（`:128`）—— **照任务书路径施工会改错文件。**

## 1. 波形各效果的实现成本与性能开销是多少？

### 1.1 现状基线（所有增量都以它为基准，均为【事实】）

- 渲染 = 纯 Compose `Canvas` 自绘：**每帧 28 次 `drawRect` + 28 次 `sqrt`**，无 Path / shader / 文字 / 图片；**颜色只有 `primary` 一个色槽**，只有「主题色来源 = 跟随封面」才是 HCT 派生色，默认 `PRESET` 不是（`probe-waveform.md:13`、`:138`、`:14`、`:45-63`）。
- 帧驱动 `withFrameNanos`（16/33ms 门），`generation` **只在 draw 阶段读 ⇒ 不触发重组**；每帧源码可见堆分配 **0**（`Color`/`Offset`/`Size`/`Dp` 皆 value class），挂起 lambda 是否每帧分配**【未验证】**（`probe-waveform.md:16`、`:80`、`:40`、`:142`）。
- **音频线程每 `handleBuffer` 2 次堆分配**（`duplicate()` + `asShortBuffer()`），与「零分配」KDoc 契约冲突；数据源是**整块 PCM 压成 1 个 RMS 标量**，`BARS_PER_SECOND = 30` 已是死常量，真柱率 = `handleBuffer` 回调率（`probe-waveform.md:19`、`:143`；`probe-waveform-tier.md:30-34`）。
- 三条硬契约：① 音频线程零分配/零锁/零异常；② 只在 draw 阶段读状态、只重绘不重组；③ 不空转（收敛即停止失效）（`probe-waveform-tier.md:25-28`）。

### 1.2 逐效果成本表（draw op 基线 = 28；「分配」= 每帧堆分配）

| 档 | 效果 | 实现落点 / 新数据 | 每帧增量 | 风险与边界 |
|---|---|---|---|---|
| A | 镜像对称 | 绘制循环 `AudioVisualizer.kt:233-250`。读法①「以中线上下对称」**现状已满足**（`:231`/`:244-249`）；读法② 上/下两笔不同强度 | **读法①：0 / 0 / 无**；读法② +28 draw op（28→56）、0 分配 | 低。左右镜像（`abs(i-center)`）是**数据语义变更**：28 个时间切片信息减半 |
| A | 圆角柱 + 间距 | 间距**已实现**（`:229-230`）；圆角 = `:242` `drawRect`→`drawRoundRect` | draw op 不变（28）；`CornerRadius` 是 value class ⇒ **0 新增分配**【估算】 | 真成本是治理：`drawRoundRect` + 硬编码 `CornerRadius` **不被** `AppShapesSingleSourceTest.kt:54-57` 拦住（`AppShapes` 唯一落点出现绕过口） |
| A | 峰值保持 | 需新增 `peak: FloatArray(n)`，放 `WaveformRing`（可 JVM 单测）或 draw 期 `remember` 数组 | +28 比较 / +28 写每帧；0 分配 | **中，本档最易踩**：必须并入 `pump` 收敛判据，否则 bars 收敛 ⇒ `pump` 返回 false ⇒ **峰值冻在画面永不落下**（v1.8.1 同形缺陷） |
| A | 缓动衰减 | **已实现**：起音 22ms / 回落 130ms 时间常数（`WaveformRing.kt:156-157`、`:192`） | **0 / 0 / 无** | 低。改成「可配置长尾」会动既有 10 个 `@Test` 与观感基线 |
| B | 渐变流动 | `:242` `drawRect(color=)`→`drawRect(brush=)`。**任务书点名的 `Brush.infiniteLinearGradient` 在 Compose 1.7.x 不存在** ⇒ 必须用等价物 | (a) 每帧新建 Brush：1 个 `LinearGradient` + colors List + FloatArray(stops) + 1 次 native SkShader ⇒ **违反每帧零分配**【估算】；(b)（**推荐**）缓存 brush + `TileMode.Repeated` + 每帧 `withTransform{translate}` ⇒ 0 新增对象/0 shader，只多一次 save/restore/translate，draw op 仍 28 | 尺寸变化（旋转/分栏/进出大屏）后必须按 `size` 重建 shader；仓库唯一逐帧 Brush 先例只容忍 **1~2 个/帧**（`NcrustLyricsPanel.kt:831-840`），**不可外推到 28 个/帧** |
| B | 柱顶光点 | `:233-250` 每柱追加一笔 `drawCircle` | draw op 28→56；`Offset` value class ⇒ 0 分配 | 低（纯色）。每个光点加径向光晕 = **28 个 radial Brush/帧**，与渐变路线 (a) 同级，不建议 |
| B | 多频段分色 | **当前数据无法近似频段**：音频线程只产出 1 个 RMS 标量、UI 侧只有 28 个 0..1 时间标量；全仓 **0 处 FFT / Goertzel** ⇒ **新增数据源**。(a) 音频线程对同一样本做 N 段累加；(b)（**推荐**）音频线程只做定长 memcpy 进预分配 PCM 环，UI 每帧对 512/1024 点做 FFT/Goertzel | (a) 现有逐样本循环成本 **×N**，唯一会碰音频线程的方案；(b) N=4 段 ×1024 点 ≈ **8k 乘加/帧**、1024 点 FFT ≈ **20k flops/帧**【估算】 | **最高**。(a) 与 v2.2.1 P0 同级（音频线程任何分配/异常/停顿 = 爆音）；两条路都要**重做数据通路**（`WaveformStore` 只有 `onBar(Double)`、`WaveformRing` 是单值环），不是「改绘制」 |
| B | 呼吸 | 绘制期读一个时间累加器（只改 alpha/scale）；0 新数据 | 只改 alpha：0 draw op / 0 分配；若也改 brush 参数 ⇒ 迫使 brush 每帧重建，退化成渐变路线 (a) | **中（与契约冲突）**：无限期动画 ⇒ 与「不空转」冲突，需限定 `active=true` 区间或显式停止条件 |
| C | 冲击波（节拍涟漪） | 检测放 UI 线程 `WaveformRing.pump`，**必须用未平滑的 `targets`**（用 `bars` 会把 onset 抹圆） | 每帧 O(K=8~16) 次比较；同时存活涟漪 ≤2~3 ⇒ **+3 draw op/帧** | 中。信号**只有 30Hz RMS 序列** ⇒ 时间分辨率 33ms（该速率现已无人保证）；RMS 是响度不是低频能量 ⇒ 底鼓/军鼓/人声**不可区分**，要分底鼓**依赖 B 档多频段**（依赖而非并列）；判定必须严格有界（涟漪数/寿命/冷却写死常量 + 单测） |
| C | 拖拽交互 | 需在可视化条上挂新 `pointerInput`；**现状明确不挂**任何 `pointerInput`/`clickable` | 0 GPU；需新增「手势归属」纯函数 + 单测（照 `PlayerDragSnap.kt` 范式） | **高（交互回归面）**。减缓事实：可视化只在大屏/平板横屏挂载，而这两种形态整卡拖拽**本就停用**；仍需防「命中面随播放状态出现/消失」。**建议单列开关，不进三档** |
| C | 粒子 | 必须**预分配定长池 + SoA `FloatArray`**（禁 `List<Particle>`/data class） | N=32：**+32 draw op/帧**、每粒子 ~6 次浮点更新、O(N) 生死循环【估算】；fill rate 非瓶颈（条带仅 32~56dp 高） | 中。与呼吸同一条帧时钟冲突（必须并入收敛判据）；低端机上 draw op 线性增长；视觉噪声与「信息优先」有张力 |
| C | 3D 透视 | 只能走 `Modifier.graphicsLayer{ rotationX/rotationY + cameraDistance }` —— `DrawScope` 只有 2D 变换 ⇒ **新增一个 render layer** | 每帧一次矩阵合成 + 该层 GPU 合成；`rotationX` 本身不强制离屏【估算】 | 低端机合成成本**可能最高的一项，本环境无法量化 ⇒ 必须真机 A/B**；与镜像对称语义冲突 |

### 1.3 零成本项与最大成本项（决策要点）

- **零成本项（现状 T0 已包含，无需新代码）**：**镜像对称**（中线读法①）、**柱间距**（`:229-230`）、**缓动衰减**（起音 22ms / 回落 130ms）。T0 本身即「现状逐像素保留」（`probe-waveform-tier.md:106`）。
- **最大成本项 = 多频段分色**，三个理由：① 它是**唯一需要新增数据源**的效果（全仓 0 处 FFT/Goertzel，现有通路只传单标量 RMS）；② 两条路线都付**结构代价** —— (a) 把计算搬上音频线程（与 v2.2.1 P0 同级），(b) 重做数据通路 + 每帧 8k~20k flops【估算】；③ 它与**渐变流动在 API 层硬互斥**：同一笔 `drawRect` 要么 `color=` 要么 `brush=`，共用一条带渐变就无法逐段上色，每段一个 brush 就是 N 个 shader/帧 —— **这不是优先级问题，是二选一**。
- 次高：**3D 透视**（新增 render layer，无法在本环境量化）、**粒子 / 柱顶光晕**（draw op 线性增长）。
- **组合约束**（`probe-waveform-tier.md:78-92`）：渐变流动 × 多频段分色 = ❌ 硬互斥；峰值保持 × 冲击波 × 粒子 = ⚠️ **必须共用同一个收敛条件**（否则冻结陷阱 ×3）；呼吸/粒子/涟漪 ×「暂停即停帧」= ⚠️ 需显式总停止条件；镜像对称 × 3D = ⚠️ 设计冲突；圆角柱 ×{镜像、3D、渐变} = ✅；拖拽 × 任意效果 = ✅ 但**建议独立开关**。

## 2. 波形分级方案怎么定？

### 2.1 档位数与每档内容：**三档 + 内部能力位（不暴露给用户）**

理由（全部来自仓库既有纪律）：① 细粒度 N 个开关 = 2^N 个组合，每个都要 release + 真机 A/B 才能声称结论 ⇒ **组合爆炸无法取证**；② 仓库对「同一件事的多个落点」有收敛范式（单一落点 + 机器守卫），档位正是一个落点；③ 现有开关已是「布尔 + 单一读写入口」（key `audio_visualizer`，默认 `true`），扩成三档是最小改动；④ **内部能力位**（每效果一个 bit）保留，让「关掉某一项」有单点落点（`probe-waveform-tier.md:98-104`）。

| 档 | 名称 | 包含什么 | 依据 |
|---|---|---|---|
| **T0** | 简洁 | **现状逐像素保留**：28 笔 `drawRect` + sqrt + alpha 阶梯 + 时间常数平滑 | `probe-waveform-tier.md:106` |
| **T1** | 精致 | T0 + 圆角柱 + 峰值保持 + 柱顶光点（纯色） | 同上 |
| **T2** | 炫技（C 档） | T1 + 渐变流动（缓存 brush + translate）+ 冲击波 +（可选）镜像对称 | 同上 |

**3D 透视、拖拽交互、粒子不进档位**，各自独立开关（前者无法在无设备环境评估，后两者风险最高）。

### 2.2 默认值与低端机判据

- 默认值：`visualizer_tier` **默认 1**，自动判低端机 → **0**；`audio_visualizer` 默认 `true` **不变**（语义仍是「关掉 = 整块不挂载」）。
- 低端机判据（**静态判据，不是实测帧时间**）：`SDK_INT < 26 || isLowRamDevice || totalMem ≤ 3GB` → 起始档 **T0**，否则 **T1**。
- 取仓库**现成**判据的并（`AudioVisualizer.kt:160-166` 的帧率档与 `isLowRamDevice`、`PlaybackService.kt:345-347` 的 `totalMem` 3.5GB 分档、`NcrustApplication.kt:61-67` 的 3GB/6GB 图片缓存分档），抽成**单一落点**（如 `visualizerAutoTier(context)`），**不引入新判据**（避免第三个口径）。
- 必须写明它不依据实测帧时间 —— 依据是 `AudioVisualizer.kt:147-158` 记录的 S6 流水线式掉帧教训（帧间隔量不出真实压力，v1.6.0 已踩过并回退）。
- 【推导，非探针结论】按该判据，后续 verification 用的 **S6（2.7GB / API 24）会落入 T0 起始档**，与 WGR-W09（7.9GB / API 31）形成天然的低端/高端对照。

### 2.3 C 档（T2）开关设计

- **T2 不是独立开关，而是档位选择器的一个值**（`visualizer_tier = 2`，设置页三选一）；「关掉炫技」= 降档，不需要第二个布尔。**内部能力位**（每效果一个 bit）保留但不暴露成 UI 开关 —— 它是「某效果在某机型上永久关闭」的单点落点。
- **不进档位的三个高风险效果各自独立开关**（建议默认关）：3D 透视、拖拽交互、粒子；其中**拖拽必须单列**（它新增命中面，与任何档位组合都正交）。
- **自动降级只降一级、一个会话最多降一次、降级后本会话不再尝试恢复**；结果写 `visualizer_auto_downgraded` 供诊断；静态判据只决定**起始档**，实测降级只做「再降一级」，两者**不得互相覆盖**（否则同一台机器每次冷启动观感不同）。

### 2.4 prefs 键与迁移策略（全部落既有 `ncrust_settings`，**不新增 prefs 文件**）

| key | 类型 | 默认 | 说明 |
|---|---|---|---|
| `audio_visualizer` | Boolean | `true`（不变） | **总开关**，语义不变：关掉 = 整块不挂载 |
| `visualizer_tier` | Int | `1`；自动判低端机 → `0` | 合法 0/1/2；**非法值回落默认**（同 `offline_cache_mb` 的既有口径） |
| `visualizer_auto_downgraded` | Boolean | `false` | 运行期自动降级标记（诊断 + 防振荡） |
| `visualizer_tier_version` | Int | `1` | 迁移版本号；范式 = `QualityLadder.migrate`（读版本号 → 幂等 → 启动时调用） |

迁移四条：① 老用户只有 `audio_visualizer` 时**不得**被解读成「用户选了最低档」——**缺 key 一律取默认档**（纪律来源：v1.9.3「缺失 vs 空」两义性）；② 迁移判定抽**纯函数 + 单测**（同 `QualityLadder` / `LyricsCacheModelTest`）；③ 落盘 key 名加断言；④ 不新增 prefs 文件（`probe-waveform-tier.md:118-127`）。

### 2.5 异常隔离与自动降级的设计要点

| 层 | 失败后果 | 现有隔离 | 新增效果必须遵守 |
|---|---|---|---|
| 音频线程（tee + RMS） | **等于播放失败**（v2.2.1 P0 形状） | `runCatching` 兜底 + 未知编码返回 0 | 只做**定长 memcpy**；任何新 DSP 不得分配/加锁/抛异常 |
| 数据层 `WaveformRing` | 画面异常 | 纯 Kotlin、可 JVM 单测、NaN/Inf 防御 + 夹紧 | 峰值 / 涟漪 / 节拍检测状态**都放这层**，不放 draw 私有状态 |
| 帧循环 + 绘制 | 画面停更 / 掉帧 | 只做「读时间 + pump」；0 分配、draw 阶段读状态 | 帧循环内不得做检测 / 分配；绘制内禁止分配、I/O、写 Compose 状态 |
| 手势（新增）+ 组件边界 | 交互回归 | 现状**零命中面**；**不做 `@Composable` 内 try/catch**（有意） | 判定纯函数化 + 单测（`PlayerDragSnap.kt` 范式）；用**逐效果前置条件检查**把失败变成「这一帧不画该效果」，而不是抛异常 |

自动降级四点：① 候选指标诚实排序 —— 自家 draw 块 CPU 时长（测不到 GPU 光栅）→ `withFrameNanos` 间隔（现成但**不得单独用作判据**）→ `Window.addOnFrameMetricsAvailableListener`（API 24+，全仓 0 命中，只用于诊断 + 降级，不参与 UI 逻辑）；② **判定点在帧循环内，每帧累加、只在 N 帧窗口结算一次**（窗口与阈值都是常量 + 单测）；③ **有界失败**（铁律 1）：只降一级、一会话最多一次、不恢复；④ 静态判据与实测降级**互不覆盖**（`probe-waveform-tier.md:133-155`）。

**必须同时带进施工的既有风险【事实】**：`WaveformStore.onBar(rms)` **不在任何 `runCatching` 内**（RMS 在它之前已算完），是**唯一还能把播放打挂的路径**；其下游当前不抛，但契约上是裸的 —— 将来在 `push`/`onBar` 里加一行可能抛的代码（加锁、拼日志、采样率换算）就会原样恢复 v2.2.1 级联形状。**修法只在 `TransparentWaveformSink.kt:86-88` 一处：把 `onBar` 也收进同一个 `runCatching`**（`probe-waveform.md:103-107`）。

## 3. 设置项完整清单与分组映射是什么？

### 3.1 口径说明（三个数字必须并列声明，否则无法对账）

- **口径 A（严格设置页语义）** = `ncrust_settings` 中有 UI 入口的项 = **27**；**口径 B（+ 隐藏 / 内部键）** = `ncrust_settings` 全域（27 + 13 个无 UI 键）= **40**；**口径 C（+ 账号凭证）** = 口径 B + `user_cookie` + `qq_cookie` = **42**（探针**推荐**作为迁移清单）。
- 配套全景：全仓 **16 个** SharedPreferences 文件；**82 个**静态可命名 key（70 个唯一常量值 + 9 个无常量字面量 + 3 个枚举构造 key）；另有 **7 族动态 key**（无法静态计数）。`AppWarmup.PREFS_FILES` 只列 7 个文件，其注释自称「全部」**与 grep 结果不符**（漏 9 个）—— 注释陈旧点，迁移时别被误导（`probe-settings-inventory.md:37-41`、`:49-68`、`:514-532`）。

### 3.2 分组 → 条目数映射（两份探针口径不同，**必须二选一并写进提交说明**）

**口径 1（清单探针 §6，按 key 单归组计数）**

| 分组 | 条目数 | 主要成员 |
|---|---|---|
| 账号 | **2** | `user_cookie`、`qq_cookie`（另**附带** 8 个账号派生缓存 key：需随登录/登出一起清，但不出现在 UI） |
| 通用 | **8** | `theme_color_index`、`theme_mode`、`accent_source`、`language_code`、`custom_bg_enabled`、`page_transition_enabled`、`auto_rotate`、`battery_prompt_done`（末项不出现在 UI） |
| 播放 | **10** | `gapless_playback`、`keep_screen_on`、`audio_visualizer`、`artist_reco_enabled` + 4 个 `artist_reco_*` 隐藏键、`session_metadata_lyrics`、`live_update_enabled` |
| 音质 | **3** | `wifi_quality`、`mobile_quality`、`quality_ladder_version`（内部水位，**必须跟音质两项同批迁移**） |
| 歌词 | **13** | `lyrics_translation`、`lyrics_word_animation`、`lyrics_word_by_word`（遗留）、`lyrics_sweep_quality` + 3 个 sweep 隐藏覆盖键、`lyrics_font_scale`、`lyrics_in_media_session`（**双归属**）、`lyrics_ttml_enabled`、`lyrics_ttml_first`、`lyrics_romanization`、`lyrics_dynamic_font` |
| 存储与缓存 | **5** | `offline_cache_mb` + 4 个 `library_*` 键（**弱归属 / 语义错配**，见 §3.3） |
| 关于 / 不属于任何分组 | **0 + 1** | 「关于」天然为空：只读入口、无可配置项（**正确结果，不是遗漏**）；`live_update_probed` 是纯内部「只打一次日志」标记 |
| **合计** | **42 ✔** | 与口径 C 完全一致 |

另 3 项需显式说明：`play_mode`（在 `ncrust_playback_state`，语义是播放偏好且跨启动记忆 ⇒ 归播放组，**但入口不在设置页**）；`client_device_id`、`qq_device_seed`（**不属于任何用户分组**，它们标识设备不标识账号）。

**口径 2（结构探针 §2，按一级卡片计数，只覆盖 23 个 prefs key）**：通用 **3** / 外观与动效 **5** / 播放与音质 **5** / 歌词 **9** / 存储与缓存 **1** = **23**；账号与登录、关于两张卡片**没有 prefs key**（账号是 2 个账号块 + debug 诊断行，关于直达 `AboutScreen`）。**交叉印证：27（口径 A 清单）− 23（结构探针清单）= 恰好 4 个库页键**，两份探针互证没有漏项（`probe-settings-structure.md:60-71`、`:29-56`；`probe-settings-inventory.md:375-394`）。

### 3.3 无法归类或双归属的特殊项（逐条决策）

| 类型 | 项 | 处置要点 |
|---|---|---|
| **唯一双归属** | `lyrics_in_media_session` | 单归属计数算「歌词」，同时出现在「播放」组并注明跨界；它与隐藏键 `session_metadata_lyrics` **隐式耦合**，必须一起决定归属，否则出现「设置页关了歌词、媒体卡片仍跟随」 |
| **不属于任何分组（显式列出）** | `live_update_probed`、`client_device_id`、`qq_device_seed`、各缓存/数据/运行状态/诊断 key、「关于」空组 | 单列「内部 / 设备身份」「缓存数据」「用户数据」「运行状态」「诊断」；**不要为凑齐分组硬塞项** |
| **语义错配（未决）** | `library_playlist_layout` + 3 个 `library_section_collapsed_*` | 清单探针说塞进「存储与缓存」是错配、建议新增「库页 / 列表显示」；结构探针建议**不迁移**到设置页（库页上下文操作，移过去会多两次跳转）。**必须二选一** |
| **有 UI 但可能完全无效 / 硬依赖已门控** | `lyrics_sweep_quality`（软依赖 `lyrics_word_animation`）；`lyrics_ttml_first` → `lyrics_ttml_enabled` | 前者在 `HARD_CUT`/`OFF` 下改了这一行**看不到任何变化**，迁移时须门控或加提示（门控用**条件挂载**，不用 alpha）；后者是全仓唯一「UI 已条件挂载 + 逻辑已门控」的一对，`ttmlEnabled=false` 时 TTML 根本不进回退链 |
| **必须同批迁移的隐藏键** | `quality_ladder_version`、`lyrics_word_by_word` | 前者漏搬会让音质迁移**重跑一次**（用户选的杜比再 +1 → 越界）；后者是迁移源（零写入点、读后不删除），漏搬会丢掉老用户「关掉过逐字」的意图 |
| **两个「不存在的项」** | `quality_api_levels`、「恢复默认设置」功能 | 前者是内存常量、**不落盘**；后者在全仓**不存在**（`ncrust_settings` 从未被 `clear()` 过） |

### 3.4 「不丢项」的判定办法

1. **清单侧（结构）**：`§2.1 + §2.2` 每一项都必须落在某个分组；覆盖校验加总 **2+8+10+3+13+5+0+1 = 42 ✔**（与口径 C 一致）。
2. **结构侧（可执行、机器守卫）**：`legacyPrefKeysArePreservedExactly`（registry key 集合 **==** 测试里硬编码的迁移前清单，**双向等值** —— 既防丢项，也防「顺手加一个音理的功能项」）；`actionRowIdsAreStable`（非 prefs 行清单：账号×2、后台运行、清除缓存、离线缓存管理、关于）；`everyGroupNonEmptyAndEveryRowInExactlyOneGroup`（无空组、`sum == rows.size`、组内 `order` 唯一）。
3. **对账前提**：先声明口径（A/B/C）；「关于」空组是有意为之，不计为丢项。

## 4. 设置界面重构的迁移方案是什么？

### 4.1 registry 数据模型（纯数据、无 Compose/Context 依赖、JVM 可测）

落点 `app/src/main/java/com/takahashirinta/ncrust/ui/settings/SettingsRegistry.kt`（新包 `ui/settings`），单测 `app/src/test/.../ui/settings/SettingsRegistryTest.kt`（手写 `FakePrefs`，不引 mock 框架）：

```kotlin
enum class SettingType { BOOL, INT, FLOAT, STRING }
enum class SettingsGroup(val key: String) {   // key 用于路由参数，与 i18n 文案解耦
    ACCOUNT("account"), GENERAL("general"), APPEARANCE("appearance"),
    PLAYBACK("playback"), LYRICS("lyrics"), STORAGE("storage"), ABOUT("about"),
}
enum class SettingControl { SWITCH, DROPDOWN, ACTION, INFO, COLOR, CUSTOM }
data class PrefSetting(
    val key: String, val type: SettingType,          // ← 落盘 key，必须与迁移清单逐字一致
    val defaultValue: Any,                           // 只用于回显/断言，**绝不写盘**
    val prefsName: String = "ncrust_settings", val control: SettingControl,
    val titleStringKey: String,                      // 指向 SettingsStrings 属性名（不存中文）
    val subtitleStringKey: String? = null,
)
data class ActionSetting(val id: String, val control: SettingControl, val titleStringKey: String)
sealed interface SettingRow { val group: SettingsGroup; val order: Int }
object SettingsRegistry {            // 唯一注册表；UI 由它驱动，**类型上不给写盘能力**
    val rows: List<SettingRow>; val groups: List<SettingsGroup>
    fun rowsOf(g: SettingsGroup): List<SettingRow>; fun pref(key: String): PrefSetting?
}
```

三条硬约束（写成 KDoc + 测试）：① **key 只复制、不重命名**，UI 不再手写字符串；② **读写仍走既有入口**（`KeepScreenOnSetting.write` / `RotationSetting.write` / `VisualizerSetting.write` / `PageTransitionSetting.writeEnabled` / `ArtistReco.setEnabled` / `LyricsDisplayPrefs.writeXxx` / `PlayerViewModel.setXxx`；`wifi_quality`/`mobile_quality`/`lyrics_translation`/`lyrics_in_media_session` **继续用原来的裸 `prefs.edit()`** —— 不要顺手补一层包装，否则等于新增第三套语义）；③ **默认值不落盘**，回显一律调既有 `read*`（`lyrics_word_animation` 的读路径带一次性迁移并写回，用 registry 默认值回显会让老用户的三选一显示错误）。

### 4.2 导航方案选型：**推荐方案① 接入既有 NavGraph**（不推荐设置页内部状态 + BackHandler）

- 理由：二级设置在语义上就是「从设置 tab 推入的详情页」，与 album/artist 详情同构；返回栈正确性**由导航库保证**（系统返回、手势返回、进程重建后恢复都对）；转场**复用** `pageTransitionEnabled`（现成唯一真相，`NavGraph.kt:160-163`），而方案②会打破 `AppMotion.kt:240-241` 明确的「唯一调用点是 `NavGraph.kt`」约定；`DetailScaffold`（返回箭头 / opaque 背景 / `BottomOverlayInsetDp`）直接可用。
- 实现要点：① 路由带参数 `SETTINGS_GROUP = "settings/{group}"` + `fun settingsGroup(key)` + `navArgument("group") { type = NavType.StringType }`；② 进入用 `navigate(...)`，**不要**加 `launchSingleTop`（连续点两个分组会复用栈顶、参数不更新）；③ 二级页返回**只**用 `popBackStack()`，**不要** `navigate(HOME)`（会把栈压平、丢 tab 状态）；④ **未知 key 兜底**：解析失败回落一级页 + `popBackStack()`，**不抛异常**（同 `AccentSource` 的 `getOrDefault(PRESET)` 纪律）；⑤ 与播放器 `BackHandler` 共存：Navigation 自己的 BackHandler 参与即可，**不能再加一层 `enabled=true` 的全局拦截**；⑥ 二级页必须 opaque 背景（否则 popExit 期间透视到 tab 屏）；⑦ 一级页滚动位置保留是**预期**（`NavHost` 叠在 tab 屏之上、tab 屏不卸载），但**【未验证】必须真机确认**。

### 4.3 目录 / 文件级实施步骤（五阶段，每阶段可独立 revert）

| 阶段 | 动作 | 文件 | 风险 / 回滚 |
|---|---|---|---|
| 1 | 纯数据 + 单测，**不接线** | 新增 `ui/settings/SettingsRegistry.kt` + `app/src/test/.../SettingsRegistryTest.kt`（5 条用例） | 极低（产物行为不变）；删两个文件即回滚 |
| 2 | 抽行组件，**零视觉变化** | 新增 `ui/components/SettingsRows.kt`；`SettingSwitchRow`/`MetroDropdownRow`/`CacheUsageLine`/`SectionTitle` 从 `UserScreen.kt` 移入（`private`→`internal`，参数与视觉逐字不动） | `SectionTitle` 等可能重名（**须编译验证**【未验证】）；revert 单 commit |
| 3 | 路由与骨架先上，**旧页不动** | `ui/navigation/NavGraph.kt` 加 `SETTINGS_GROUP` + composable（按 `group` 分发）+ `MainNavGraph` 透传回调；新增 `ui/screen/SettingsGroupScreen.kt`（`DetailScaffold` 骨架） | 一级页仍是旧 `UserScreen` ⇒ 行为不变 |
| 4 | **一个分组一个 commit**，顺序：账号 → 通用 → 外观与动效 → 播放与音质 → 歌词 → 存储与缓存 → 关于 | 同步新增 `ui/components/SettingsGroupCard.kt`（`MetroSurface(onClick)` + 图标 + 两行文本 + ChevronRight + `appPressScale`）与一级页卡片列表 | **最大风险 = 双轨期同一 key 挂两处 ⇒ 状态分裂**：禁止「先加二级页、下个 commit 再删旧块」，**必须同一 commit** |
| 5 | 收尾：删空壳、清 import | — | **不新增任何 key、不改任何默认值、不改任何文案语义**；i18n 只允许新增 7 条分组标题（× 8 locale，进 `SettingsStrings`，受 `StringsConstructorBudgetTest` 阈值 150 监控） |

必须原样保留的三条既有语义：`lyrics_ttml_first` 的**条件挂载**（不是 alpha）；`lyrics_word_animation` 的**读时一次性迁移**；`page_transition_enabled` 的**状态提升**（真源在 `MainScreen`，`UserScreen` 只是受控组件，参数继续从 `MainActivity` 传入）。另：一级页/二级页的 `LazyColumn` 必须继续用 `BottomOverlayInsetDp`，否则有歌播放时底部卡片点不动（触摸死带）。

### 4.4 四条纪律如何被单测强制

| 纪律 | 强制手段 |
|---|---|
| **不丢项** | `legacyPrefKeysArePreservedExactly`（registry key 集合 == 硬编码迁移前清单，**双向等值**）+ `actionRowIdsAreStable` + `everyGroupNonEmptyAndEveryRowInExactlyOneGroup` |
| **不改语义** | `registryHasNoWriteCapability`（反射断言 `PrefSetting` 字段类型白名单：无 `Context`、无函数类型）+「读写仍走既有入口」的 KDoc + 双轨期「同 commit」禁令（评审 + 单测覆盖单归属） |
| **不改默认值** | `defaultsMatchCanonicalConstants`：逐项对齐既有单一真相常量（`KeepScreenOnSetting.DEFAULT_ENABLED`、`RotationSetting.DEFAULT_ENABLED`、`VisualizerSetting.DEFAULT_ENABLED`、`PageTransitionSetting.DEFAULT_ENABLED`、`LyricsDisplayPrefs` 各默认、`ArtistReco` 默认 false、`OfflineAudioCache` 512/64/8192、`AccentSource` PRESET、`LanguageManager` `"zh-CN"`） |
| **不引入无关功能项** | 同一条**双向等值**测试的反向断言：registry 多一个 key 就红 —— 这是对「一起听 / 下载管理 / 流量管理 / 网络设置 / 备份与恢复 / B 站登录」这些音理有、Ncrust 没有的功能项的机械防线 |

**【未验证】** 本轮**未运行** `./gradlew test`：`StringsConstructorBudgetTest` 的当前参数数、阶段 2 的重名冲突均未实测；渲染后触控高度、二级页转场手感、一级页滚动位置保留都需真机/模拟器验收。

## 5. 未验证清单与后续阶段必须补做的两件事

**探针自述的未确认项（不得当结论用）**：实际柱率与 `CAPACITY=256` 对应的真实积压秒数；`withFrameNanos` 挂起 lambda 是否每帧分配；`writeIndex` 溢出是否可达；6 声道高码率下逐样本开销占比；release R8 后波形符号是否保留；`dumpsys media.audio_flinger` 的 underrun 字段名；**三档各自在 S6 与 WGR-W09 上的单帧成本与 jank 率**；渐变路线 (b) 的真实 shader 采样开销；3D render layer 的合成成本；`handleBuffer` 真实回调率与块大小（决定节拍分辨率与涟漪寿命）；30Hz RMS 节拍检测误触发率；新增 `pointerInput` 后的手势分解行为；自动降级阈值 K/N（**先立判定点、后定阈值**）；`DynamicLyricFont` 倍率上下界；8 locale `qualityOptions` 文案；`SweepEasing` 的 ordinal；音理截图缺失。

**必须优先补做的两件事**（我的建议，非探针结论）：
1. **波形三档真机帧时间 A/B**（release 包、S6 + WGR-W09）：只切设置页开关，`dumpsys gfxinfo` + `perfetto`，按既有判读口径（P90 Δ ≤ 1.0ms / janky% Δ ≤ 2pp，同时看 P99）；**音频线程侧必须单独测** —— 帧指标完全看不见每缓冲 2 次堆分配与 AudioTrack 欠载。
2. **设置迁移的老用户实测**：带旧 `ncrust_settings.xml` 升级，逐项比对 3 个迁移/回落点（`quality_ladder_version`、`lyrics_word_by_word`→`lyrics_word_animation`、`offline_cache_mb` 脏值）与 42 项 key 的落盘一致性，并真机确认一级页滚动位置、二级页返回栈、未知 key 兜底。

**最终 release note 必写的一条**：本版 APK **签名与历史版本不同**（原 release 密钥永久丢失，已换新密钥 `ncrust-release-v2.jks`），**老用户无法覆盖安装，必须卸载重装** —— 卸载会清掉登录态与离线缓存。
