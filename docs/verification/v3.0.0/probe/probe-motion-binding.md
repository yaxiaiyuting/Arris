# 探针 3.3 · 动效绑定逻辑（v3.0.0）

> **性质**：只读探针 + 已落地实现的绑定说明。每条给 `file:line`。
> **证据分级**：**【事实】**= 源码；**【实测】**= JVM / 仪器探针；**【估算】**= 代码算式；**【未验证】**= 明确没确认。

---

## 1. 绑定表（唯一真相，逐行给落点）

| 动效 | 音频特征 | 触发 / 映射 | 落点 |
|---|---|---|---|
| **冲击波** | **瞬态**（音频线程判定） | `transientCount > 0` 触发一次；幅度 = `strengthScale(强度)` ∈ [0.55, 1] | 状态 `MotionEnvelope.kt` 的 `spawnShock`；渲染 `MotionBackdrop.kt` 的 `drawCircle(brush=…)` |
| **光晕** | **瞬态** | 同一个触发源；每次扩 `haloRings` 圈（精致 1 / 炫技 2），圈间用 `ringStartOffset` 错开起始进度 | `spawnHalos`；`MotionBindings.ringStartOffset` |
| **粒子** | **中频 + 高频能量** | 速率 = `particleRateHz(midHigh, 密度)`；低于门槛 **恒为 0**；生成速率与能量**线性正相关**，到上限封顶 | `MotionBindings.particleRateHz`；`MotionBackdropState` 的 `particleEmitAcc` |
| **波形频带响应** | 低 / 中 / 高频（**逐柱**占比） | 每根柱子按**它那一刻**的 `mix` 决定是实心低沉色还是流动明亮色；炫技档再加三条频带能量条 | `AudioFeatureExtractor.bandMix` → `WaveformRing.mixRing` → `AudioVisualizer.kt` 的 `drawWaveformBars` / `drawBandLanes` |
| **背景呼吸** | **全带 RMS** | 响度包络（τ 起音 90ms / 回落 260ms）→ 背景 `alpha` 与 `scale` | `MotionEnvelope.update` → `MotionClock.level()` → `MotionBackdrop.kt` 的 `graphicsLayer { }` |

**没有任何一条**用伪随机或固定周期伪装（铁律 25）：
随机数只用于**粒子的出生角度/速度**（形状上的抖动，种子固定可复现），
而粒子的**数量**完全由中高频能量决定。可执行判据见
`MotionBindingsTest.静音输入下所有随时间变化的量都收敛到零`。

---

## 2. 触发条件与冷却

| 问题 | 答案 | 证据 |
|---|---|---|
| 谁判定瞬态？ | **音频线程**（每缓冲一次 `process`，内部每 10ms 结算一次子帧） | `AudioFeatureExtractor.updateTransient` |
| 冷却多少？ | `ONSET_COOLDOWN_MS = 90`（音频线程侧）；UI 回落判据仍是 v2.9.0 的 180 | `AudioFeatureExtractor.kt:537` |
| 冷却可配吗？ | **不可配**（常量 + KDoc 说明依据）。可配的是**每个动效的开关**与**档位** | 见 §6 |
| 一次击打会触发几个特效？ | 冲击波 1 个 + 光晕 1~2 圈；**粒子不由瞬态触发**（由中高频能量持续生成） | `MotionBackdropState.update` |
| 一帧最多补几次？ | `MotionBindings.MAX_TRANSIENTS_PER_FRAME = 4` | `MotionBindings.kt` |
| 一个缓冲里多次击打？ | `transientCount` 累加（上限 `MAX_TRANSIENTS_PER_BUFFER = 64`） | `AudioFeatureExtractor.kt:143` |

---

## 3. 冲击波：形状、扩散、寿命、数量上限

- **形状**：从画面中心（纵向 `HALO_CENTER_Y_FRACTION = 0.42`）扩散的**实心径向渐晕**
  （`Brush.radialGradient`，色标 `1.0 → 0.55 → 0`）；光晕则是**描边环**（`Stroke(2dp)`）。
  两者刻意用不同的画法，否则同一时刻出现两层一样的圆。
- **扩散**：半径 = `min(宽,高) × 0.7 × 幅度倍率 × 进度`；透明度随进度线性归零。
- **寿命**：`SHOCK_LIFE_MS = 700`（比光晕的 900 短 —— 它是"一下"，不是"余韵"）。
- **数量上限**：`SHOCK_CAPACITY = 2`；池满**覆盖最旧**，绝不扩容。
- **颜色**：`LocalMetroColors.current.primary`（主题色 = HCT / 主题取色的产物），
  不是硬编码色值。**没有用封面取色**（封面取色是 `CoverBlur.vibrance` 那条路，
  只用于背景模糊；把它引到这里要每首歌重算一次 Brush，收益不抵成本）。

---

## 4. 粒子：定长池与零分配

| 项 | 值 | 依据 |
|---|---|---|
| 池容量 | `PARTICLE_CAPACITY = 40` | 高密度档 26 个/秒 × 0.9s 寿命 ≈ 24 稳态；40 是硬上界且让"池满覆盖最旧"路径可达 |
| 存储 | **SoA**：`particleX/Y/Vx/Vy/Life` 五个 `FloatArray` | 构造时分配一次，帧路径零分配（禁止 `List<Particle>` / data class） |
| 每帧上限 | `MAX_PARTICLES_PER_FRAME = 4` | 长卡顿后的有界补发 |
| 累加器 | `particleEmitAcc` 夹在 `[0, 1]` | 速率异常时不会无限增长 |
| 随机 | `Random(PARTICLE_RANDOM_SEED)`（固定种子） | 同一段音频得到同一套分布，便于排查"是不是随机看着乱" |
| 绘制 | `drawCircle(color, radius, center, alpha)` | **不用 `drawPoints`**（形参是 `List<Offset>`，`Offset` 是 value class、进 List 必装箱；且 `AndroidCanvas` 的 `PointMode.Points` 是逐点 `nativeCanvas.drawPoint()`，没有批处理 —— 见 `ref-research/compose-particle-systems.md` §1） |

---

## 5. 渐变 Brush 的缓存（零分配的关键一环）

`Brush.radialGradient(...)` 会分配一个 `Brush` + 一个 `ColorStop` 数组 + 一个 native `SkShader`。
写在 `Canvas {}` 的 draw lambda 里就是**每帧每个槽位一次**。
本实现按「颜色 + 半径」缓存（`RadialGradientCache`，`MotionBackdrop.kt`），
只在主题色或画布尺寸变化时重建；绘制时用进度**缩放半径参数**（同一个 Brush 画出任意半径的圆）。

> **【未验证】**：`Ref` 里提到的 `Modifier.drawWithCache` 是另一种（更"官方"的）缓存点。
> 本实现用 `remember(color)` + 私有缓存类达到同样目的；两者的实际差别没有在真机上 A/B 过。

---

## 6. 与「动效强度」档位的映射（任务书 §4.4）

| 档 | 冲击波 | 光晕 | 粒子 | 波形频带响应 | 背景呼吸 | 背景模糊 | B 档 | C 档 |
|---|---|---|---|---|---|---|---|---|
| **简洁** | ✗ | ✗ | ✗ | ✗ | ✅ | ✅ | ✗ | ✗ |
| **精致**（默认） | ✅ | ✅（1 圈） | ✅（低密度 10/s） | ✅（逐柱着色） | ✅ | ✅ | ✅ | ✗ |
| **炫技** | ✅ | ✅（**2 圈**） | ✅（**高密度 26/s**） | ✅（+ 三条频带能量条） | ✅ | ✅ | ✅ | ✅（封面 3D） |

- 映射的**唯一落点**是 `MotionEffects.of`（`MotionEffects.kt`），纯函数、单测逐格断言
  （`MotionEffectsTest`）。四层判据顺序不能反：**总开关 → 用户选的档位 → 逐项开关**。
- ⚠️ **与任务书表格的一处刻意偏差**：任务书的「简洁」一栏只写了「波形基础 + 背景呼吸」，
  本实现**保留背景模糊**。理由是真机事实（S6 之类低端设备的静态判据就是简洁档，
  而模糊背景是那台设备上唯一看得见的美化）。**不保留**的话，低端设备播放页会退化成纯色。

### 6.1 独立开关（铁律 26）

五个新动效各有 `motion_*` 开关，**默认全开**（缺 key = 开）：

| 开关 | 键 | 关掉之后的画面 |
|---|---|---|
| 冲击波 | `motion_shockwave` | 不再有径向渐晕 |
| 光晕 | `motion_halo` | 不再有描边光环（冲击波不受影响） |
| 粒子 | `motion_particles` | 不再生成粒子；已有残留**立刻清空**（改设置马上跟上） |
| 波形频带响应 | `motion_wave_bands` | 柱子回到单一主题色（v2.8.0 的着色路径），能量条消失 |
| 背景呼吸 | `motion_breathing` | 背景亮度/缩放恒定 |

**默认全开的理由**：开关是为了让用户**能关掉**某一样，而不是让他去发现某一样。
默认关会让「升级之后动效没变化」成为默认体验，而档位表里明明写着精致档包含它们 —— 那是撒谎。
（`motion_tier` 缺 key 的解析结果与这五个开关**正交**：档位决定"这一档有没有这类动效"。）

---

## 7. 自动降级：**v3.0.0 已整个删除**（用户明确要求）

现场与工程判断一致：那套机制制造的混乱多于它省下的帧。

| 问题 | v2.9.0 | v3.0.0 |
|---|---|---|
| 帧时间超标时 | 四级阶梯（砍 B → 砍 A → 波形降档），并**改写 `motion_tier`** | **什么都不做** |
| `MotionEffects` 上的水位字段 | `degradeLevel` | **不存在**（反射断言，`MotionEffectsTest.能力位对象上不存在降级水位字段`） |
| `MotionPrefs.applyAutoDowngrade` | 有 | **不存在**（反射断言） |
| `VisualizerTier.downgradedTier` | 有（v2.8.0 遗留） | **不存在**（反射断言） |
| `VisualizerFrameMonitor` 注册点 | `MotionClock` 的 `DisposableEffect` | **已删除**（类保留作诊断工具，**无生产调用点**） |
| `motion_degrade_level` / `motion_degrade_log` | 驱动渲染 | **历史值**（保留不删，`legacyV300`；迁移会补一条说明） |

**渲染的输入只剩三样**：用户选的档位、界面动效总开关、五个独立开关。
「同一份配置必然画出同一幅画面」由 `MotionEffectsTest.同档同开关的组合必然给出同一份能力位（渲染可推导）`
保证（反复构造 200 次逐字段比对）。

**性能兜底改由用户可见的手段承担**：
① 低端设备的**初始档位**由静态判据解析（只在用户没选过时生效，永不覆盖用户选择）；
② 每个动效都有自己的开关；
③ 每个动效**自身**都有硬上界（池容量 / 寿命 / 冷却 / 每帧上限），不会因为机器慢就失控。

---

## 8. 异常隔离（铁律 4）

「每个动效独立 try-catch」在实现上落成**两道**边界，比逐效果 try 更强：

1. **音频线程边界**：`TransparentWaveformSink.handleBuffer` 把「特征提取 + 三个回调」
   收在**同一个** try 里（`TransparentWaveformSink.kt:183-233`）。任何 Throwable 都在这里终结
   —— 抛出去就是 v2.2.1 的级联形状（tee → queueInput → AudioSink → onPlayerError → 降档循环）。
   单测：`a throwing rms is isolated and drops the bar` / `a throwing feature consumer never propagates out of handleBuffer`。
2. **渲染边界**：所有 draw 侧计算都是**纯函数 + 数组下标读**，且数值一律 `coerceIn` + `isFinite`。
   视觉层没有会抛的调用（没有 IO、没有解析、没有集合操作）。
   数值防御的单测：`MotionBindingsTest.脏值一律被夹回 0 到 1` /
   `AudioFeatureExtractorTest.数值一律有限且落在 0 到 1`。

**降级（不是失效）**：特征链路不可用 ⇒ 动效回落到 v2.9.0 的内置判据；
特征可用但用户关掉某项 ⇒ 那一项立刻清空并停止生成。播放链路**任何情况下不受影响**。

---

## 9. 未验证项

1. **观感的真机 A/B 没做**：光晕的 0.35 alpha、冲击波的 0.28 alpha、粒子 2dp 半径、
   `TINT_GAIN = 1.8` 这几个数字全部是**设计取值**，本轮只复核了「参数生效、方向正确」，
   没有做过多组参数的对照截图。
2. **`MotionBindings.tintMix` 的拉伸中心/增益**没有在浅色主题下复核
   （浅色主题下 `lerp(primary, White, 0.45)` 的对比度会明显更弱）。
3. **音画同步延迟**：真机实测缓冲粒度 **100ms**（见 `probe-audio-features.md` §2.3），
   特征的**发布**仍是每缓冲一次 ⇒ 视觉对一次击打的反应最多可能滞后
   「一个缓冲 + 一帧」≈ 100~133ms。这是数据通路的性质，**不是判据的问题**，
   本轮**没有**用高速摄影/音频对齐工具量化它。
4. **HCT 取色**：本轮动效颜色用的是 `LocalMetroColors.current.primary`（主题色）。
   任务书 §4.3 提到「颜色来自 HCT 取色」——项目里没有独立的 HCT 取色模块，
   唯一从封面取色的是 `CoverBlur.vibrance()`（背景模糊用）。**没有**把它接到光晕上，
   理由见 §3（每首歌重算 Brush 的收益不抵成本）。这一条是**有意的范围裁剪**，不是遗漏。
