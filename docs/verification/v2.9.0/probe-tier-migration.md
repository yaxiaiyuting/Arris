# v2.9.0 探针 · 档位迁移（v2.8.0 的 8 个波形键 → v2.9.0 的统一「动效强度」）

> **性质**：只读探针（**v2.9.0 的实现已由另一个人写好**，本文只做侦察与引用，不发明任何设计）。
> 本轮**没有**修改任何源文件、**没有**编译、**没有**跑单测、**没有**接真机。
> 所有结论要么是 `file:line` 引用 + 逐字原文，要么是「代码算式 + 仓库既有探针的实测值」的换算，逐处标注来源。
> **证据分级**：**【事实】** = `file:line` / 原文引用；**【推导】** = 由代码形状或既有实测值推出；**【未验证】** = 本轮明确没确认。
> **配套**：`probe-ui-motion.md`（竖屏布局树 / 封面位图通路 / RMS 数据源）、`probe-landscape-motion.md`（横屏几何与插入点）、
> `probe-blur.md`（背景模糊）、`probe-perf-budget.md`（性能预算与降级阶梯）、`PROBE-SUMMARY.md`（一页纸）。
> **基线**：`docs/verification/v2.8.0/probe-waveform-tier.md`、`probe-waveform.md`、`PROBE-SUMMARY.md`。

---

## 0. 结论速览

| # | 问题 | 答案 | 落点 |
|---|---|---|---|
| 1 | v2.8.0 有几个落盘键？ | **8 个**（`VisualizerPrefs.kt:65-72`）；但 `SettingsRegistry` 里只有 **7 个**条目带 `isNewInV280` —— `visualizer_auto_downgraded` **从未进入 registry**，也不在 42 键清单里（§1.3） | `VisualizerPrefs.kt:65-72`；`SettingsRegistry.kt:435-529` |
| 2 | v2.8.0 的三档是什么？ | `SIMPLE=0` / `REFINED=1` / `SHOWCASE=2`；默认档**不是常量**，由设备静态判据解析（低内存 / 3.5 GiB 级 / API<26 → 简洁） | `VisualizerTier.kt:39/42/45/78-82` |
| 3 | 迁移搬的是盘上的值还是「有效档」？ | **有效档**。v2.8.0 的 C 档要求 `tier==2` **且** `showcase==true`，而 `showcase` 默认 `false` ⇒ `tier==2 && !showcase` 的老盘搬到 **1（精致）** | `VisualizerTier.kt:203`；`MotionPrefs.kt:169-178` |
| 4 | `visualizer_tier` 缺 key 怎么办？ | **不写新键**（返回 `null`）。保留「用户没选过」这一信息，让新版本继续用解析出的设备默认档 | `MotionPrefs.kt:170`、`:204` |
| 5 | `visualizer_auto_downgraded=true` 搬到第几级？ | **第 1 级**（砍 B 档界面动效），**不是第 3 级**。v2.8.0 的降级结果已经写在 `visualizer_tier` 里，再扣一次波形 = 重复处罚 | `MotionPrefs.kt:187-188` |
| 6 | 五个炫技细分开关的下场？ | `visualizer_showcase/_shockwave/_particles/_perspective/_drag` **不再参与渲染**；registry 里 `internal = true` + `legacyV290 = true`（无 UI 入口、不删除） | `SettingsRegistry.kt:450-514`；`MotionEffects.kt:45-50` |
| 7 | 迁移水位？ | 新键 `motion_version`：`VERSION_PRE_V290 = 0` / `CURRENT_VERSION = 1`；**缺 key = 「未迁移」而不是「搬到 0」** | `MotionPrefs.kt:82/85/114-115` |
| 8 | 机械防线？ | 三条：`newV290KeysAreExactlyTheMotionSet` / `v280KeysAreAllLegacyUnderV290` / `v290EntriesAreReachableAndHaveStrings` | `SettingsRegistryTest.kt:93/105/117` |
| 9 | 一处**缺口**（诚实记录） | 8 个旧键里只有 7 个被 registry 枚举 ⇒ `visualizer_auto_downgraded` 落在「不丢项」双向等值断言的**覆盖面之外**（迁移仍读它，靠 `MotionPrefsTest` 的键名断言兜） | §1.3、§4.4 |

---

## 1. v2.8.0 基线：三档定义、8 个 prefs 键、迁移历史

### 1.1 三档与档位→能力位的**唯一**映射

```kotlin
// VisualizerTier.kt:36-48（原文，节选）
object VisualizerTier {
    /** 简洁：现状的逐像素基线 + 圆角柱 + 峰值保持。低端机默认。 */
    const val SIMPLE = 0
    /** 精致：默认档。简洁 + 渐变流动 + 柱顶光点 + 呼吸。 */
    const val REFINED = 1
    /** 炫技：默认关闭，用户手动开。精致 + 冲击波 / 粒子 / 3D 透视 / 点按交互（各自还有细分开关）。 */
    const val SHOWCASE = 2
    /** 合法取值。越界值一律回落「解析出的默认档」，不写回盘（同 `offline_cache_mb` 的口径）。 */
    val RANGE: IntRange = SIMPLE..SHOWCASE
```

| 项 | 值 / 语义 | 落点 |
|---|---|---|
| 三档取值 | `SIMPLE=0` / `REFINED=1` / `SHOWCASE=2` | `VisualizerTier.kt:39/42/45` |
| 设备默认档判据 | `isLowRamDevice \|\| totalMem ∈ 1..3.5 GiB \|\| sdkInt ∈ 1..25` → 简洁，否则精致 | `VisualizerTier.kt:78-82` |
| `LOW_RAM_TOTAL_BYTES` | `3_500L * 1024 * 1024`（= 3.5 **GiB**，KDoc 明确说**不要照抄 `PlaybackService` 那个把 GiB 写成 TiB 的字面量**） | `VisualizerTier.kt:53-59` |
| `LOW_TIER_MAX_SDK_INT` | `25`（API < 26） | `VisualizerTier.kt:62` |
| 越界归一化 | `sanitize(raw, fallback)`：非法回落 `fallback`，`fallback` 自身非法才用精致档 | `VisualizerTier.kt:85-86` |
| 有界降级 | `downgradedTier(current, autoDowngraded)`：已降过 / 已在最低档 → `null`，否则**只降一级** | `VisualizerTier.kt:96-101` |
| 能力位表 | `VisualizerEffects`（A/B/C 分组写在 KDoc 里） | `VisualizerTier.kt:109-146` |

**C 档的门是复合的**（这是本次迁移最关键的一行）：

```kotlin
// VisualizerTier.kt:201-219（原文，节选）
val t = VisualizerTier.sanitize(tier, VisualizerTier.REFINED)
val refined = t >= VisualizerTier.REFINED
val showcaseOn = t >= VisualizerTier.SHOWCASE && showcase      // ★ 两个条件缺一不可
val flow = refined
return VisualizerEffects(
    tier = t, autoDowngraded = autoDowngraded,
    mirror = true, rounded = true, peaks = true, timeOrderedTint = !flow,
    flow = flow, dots = refined, breathe = refined,
    shockwave = showcaseOn && shockwave,
    particles = showcaseOn && particles,
    perspective = showcaseOn && perspective,
    tapInteraction = showcaseOn && tapInteraction,
)
```

### 1.2 8 个落盘键（键名是持久化契约）

```kotlin
// VisualizerPrefs.kt:64-72（原文）
// ---- 落盘键（唯一字面量落点）----
const val KEY_TIER = "visualizer_tier"
const val KEY_SHOWCASE = "visualizer_showcase"
const val KEY_SHOCKWAVE = "visualizer_shockwave"
const val KEY_PARTICLES = "visualizer_particles"
const val KEY_PERSPECTIVE = "visualizer_perspective"
const val KEY_DRAG = "visualizer_drag"
const val KEY_AUTO_DOWNGRADED = "visualizer_auto_downgraded"
const val KEY_TIER_VERSION = "visualizer_tier_version"
```

| # | key | 类型 | 默认 | v2.8.0 的语义 | 谁写 | 谁读 |
|---|---|---|---|---|---|---|
| 1 | `visualizer_tier` | Int 0..2 | **不写盘**，按设备判据解析 | 波形效果档位 | 设置页（`setTier`）、自动降级（写回降级后档位） | `readTier(prefs, deviceDefault)` `:99-103` |
| 2 | `visualizer_showcase` | Bool | `false`（`DEFAULT_SHOWCASE`，`:75`） | C 档**总闸**：只有它为 true 且 `tier==2` 才逐项看细分开关 | 设置页 | `readShowcase` `:105` |
| 3 | `visualizer_shockwave` | Bool | `false` | C 档冲击波涟漪 | 设置页 | `readShockwave` `:107` |
| 4 | `visualizer_particles` | Bool | `false` | C 档粒子（定长池 SoA） | 设置页 | `readParticles` `:109` |
| 5 | `visualizer_perspective` | Bool | `false` | C 档 3D 透视（新增 render layer） | 设置页 | `readPerspective` `:111` |
| 6 | `visualizer_drag` | Bool | `false` | C 档「点按切换着色」（v2.8.0 已把「拖拽」有界降级成点按，见 `AudioVisualizer.kt:296-311`） | 设置页 | `readTapInteraction` `:113-114` |
| 7 | `visualizer_auto_downgraded` | Bool | `false`（`:76`） | **诊断 + 防振荡**标记：自动降级只发生一次 | `applyAutoDowngrade` `:190-201` | `readAutoDowngraded` `:116-117` |
| 8 | `visualizer_tier_version` | Int | `1`（`:79`） | 迁移水位 | `migrate` `:176-180` | `readTierVersion` `:119-120` |

**「缺 key ≠ 选了默认档」是 v2.8.0 就定下的口径**，v2.9.0 逐字继承：

```kotlin
// VisualizerPrefs.kt:88-103（原文，节选）
 * 用户是否**显式选择过**档位（键存在且类型正确）。这是「没选过」与「选了 1」的唯一判据。
 * 探针 §3.3 明确要求：缺 key **不得**被解读成「用户选了最低档」。
fun hasExplicitTier(prefs: SharedPreferences): Boolean = intOrNull(prefs, KEY_TIER) != null

 * 实际生效的档位：显式选择优先，否则用调用方解析出来的设备默认档。
 * **越界值同样回落该默认档**（不是回落常量 1 —— 低端机上常量 1 会把用户顶到精致档）。
fun readTier(prefs: SharedPreferences, deviceDefault: Int): Int {
    val fallback = VisualizerTier.sanitize(deviceDefault, VisualizerTier.REFINED)
    val raw = intOrNull(prefs, KEY_TIER) ?: return fallback
    return VisualizerTier.sanitize(raw, fallback)
}
```

对应到 v2.9.0 是 `MotionPrefs.hasExplicitTier`（`:95`）与 `MotionPrefs.readTier`（`:101-105`）——**同一套写法、同一套默认值语义**，只是键名换成了 `motion_tier`。

### 1.3 迁移历史（两个版本的水位）

| 版本 | 水位键 | 首次引入时取值 | 那一版实际搬了什么 |
|---|---|---|---|
| v2.8.0 | `visualizer_tier_version` | `DEFAULT_TIER_VERSION = 1`、`CURRENT_TIER_VERSION = 1`（`VisualizerPrefs.kt:79/82`） | **什么都没搬**（波形分级是首次引入）。KDoc 原文：「缺 key 时本函数是**严格 no-op**（不写盘、不新增键）」——`VisualizerPrefs.kt:165-168` |
| v2.9.0 | `motion_version` | `VERSION_PRE_V290 = 0`、`CURRENT_VERSION = 1`（`MotionPrefs.kt:82/85`） | 第一次**真的有东西要搬**：`visualizer_tier`（有效档）→ `motion_tier`、`visualizer_auto_downgraded` → `motion_degrade_level`，并写水位 |

```kotlin
// MotionPrefs.kt:84-85（原文）
/** 当前水位。加语义 ⇒ +1 并在 [migrate] 里补一段搬运逻辑。 */
const val CURRENT_VERSION = 1
```

**【事实】一处口径缺口（本轮侦察发现，如实记录）**：v2.8.0 **写了 8 个键**，但 `SettingsRegistry` 里带
`isNewInV280 = true` 的条目只有 **7 个**（`visualizer_tier` `:435-449`、`visualizer_showcase` `:450-462`、
`visualizer_shockwave` `:463-475`、`visualizer_particles` `:476-488`、`visualizer_perspective` `:489-501`、
`visualizer_drag` `:502-514`、`visualizer_tier_version` `:515-529`）——
`visualizer_auto_downgraded` **既不在 registry**（全文件零命中），**也不在 `legacyPrefKeysArePreservedExactly`
的 42 键清单里**（那是 v2.8.0 之前的「口径 C」清单，`SettingsRegistryTest.kt:58`）。后果：

- 「不丢项」的双向等值断言**盖不到这个键**；它是否被保留，只由迁移代码自己决定（结论：**保留了**，`MotionPrefs.kt:359` 把键名再声明一次）；
- 三条机械防线里的 `v280KeysAreAllLegacyUnderV290` 断言的是「v2.8.0 的 **7 个**键全部 `legacyV290`」（`SettingsRegistryTest.kt:105-114`）——**数字 7 与「8 个落盘键」是两个不同的口径**，读文档时别混。
  **【建议】**（非本轮结论）：要么把 `visualizer_auto_downgraded` 补成 registry 条目（一处即可让三条断言都覆盖它），要么把「键数 = 7」的口径写进 registry 的 KDoc；否则下一个人看到「v2.8.0 有 8 个键」会以为丢了一项。

---

## 2. 现有设置项在 `SettingsRegistry` 里的位置

### 2.1 数据模型里的四个版本标志

```kotlin
// SettingsRegistry.kt:164-188（原文，节选）
/** v2.8.0「波形可视化分级」新增项（迁移前不存在这个键）。 */
val isNewInV280: Boolean = false,
/**
 * v2.9.0「统一动效强度」新增项（v2.8.0 的盘上不存在这些键）。
 * 与 [isNewInV280] 并列而不是复用它：那两个断言各自钉住**一个版本**新增了什么，
 * 合并成一个"新键"标志会让「v2.9.0 顺手夹带了无关功能项」不再被机械挡住。
 */
val isNewInV290: Boolean = false,
/** v2.9.0 起**不再参与渲染**、只作为迁移源保留的 v2.8.0 键。 */
val legacyV290: Boolean = false,
/** 内部项：**UI 必须跳过**（无 UI 入口的隐藏键 / 遗留迁移源 / 派生数据 / 账号凭证）。 */
val isInternal: Boolean = false,
```

### 2.2 v2.8.0 的 7 个条目（现在的状态：全部 `internal` + `legacyV290`）

| key | type | `defaultValue` | `newInV280` | `internal` | `legacyV290` | 行号 |
|---|---|---|---|---|---|---|
| `visualizer_tier` | CHOICE（`choices = listOf(0,1,2)`） | `1` | ✅ | ✅ | ✅ | `:435-449` |
| `visualizer_showcase` | SWITCH | `false` | ✅ | ✅ | ✅ | `:450-462` |
| `visualizer_shockwave` | SWITCH | `false` | ✅ | ✅ | ✅ | `:463-475` |
| `visualizer_particles` | SWITCH | `false` | ✅ | ✅ | ✅ | `:476-488` |
| `visualizer_perspective` | SWITCH | `false` | ✅ | ✅ | ✅ | `:489-501` |
| `visualizer_drag` | SWITCH | `false` | ✅ | ✅ | ✅ | `:502-514` |
| `visualizer_tier_version` | INFO | `1` | ✅ | ✅ | ✅ | `:515-529` |

`visualizer_tier` 条目上的原文注释把「为什么降级为迁移源」写死了：

> // v2.9.0：**降级为迁移源**。统一「动效强度」接管渲染之后，这个键只在
> // `MotionPrefs.migrate` 里被读一次（搬进 `motion_tier`）。留着不删是纪律
> // （回滚安装不丢用户数据），但不能有 UI 入口 —— 两个键都能改档位就是双轨。
> —— `SettingsRegistry.kt:443-445`

### 2.3 v2.9.0 的 4 个新条目

| key | type | `defaultValue` | 可见性 | titleKey | 行号 |
|---|---|---|---|---|---|
| `motion_tier` | CHOICE（0/1/2） | `MotionIntensity.REFINED`（= 1） | **有 UI** | `waveform.motionIntensityLabel` / `…Description` | `:534-551` |
| `ui_motion_enabled` | SWITCH | `MotionPrefs.DEFAULT_UI_MOTION`（= `true`） | **有 UI** | `waveform.uiMotionLabel` / `…Description` | `:552-562` |
| `motion_degrade_level` | INFO | `MotionDegrade.NONE`（= 0） | `internal = true`（派生状态） | — | `:563-573` |
| `motion_version` | INFO | `MotionPrefs.VERSION_PRE_V290`（= 0） | `internal = true` | — | `:574-586` |

`motion_version` 的默认值注释是本版「两义性」纪律的落点：

> // 缺 key 的读数是 `VERSION_PRE_V290`（= 0 = 「还没搬过」），
> // 不是「搬到 0」—— 这个两义性与 v1.9.3 的「缺失 vs 空」同源，必须写清。
> —— `SettingsRegistry.kt:578-579`

文案落在 `WaveformStrings`（`zh_CN.kt:117-120`）：`motionIntensityLabel = "动效强度"`、`uiMotionLabel = "界面动效"`，
副标题原文明确写了「一个档位同时决定波形与界面动效」与「关闭后背景回纯色、不再有任何逐帧动效，性能最优；
帧时间持续超标时系统会先自动削减界面动效，再降低波形档位」——**这是 KDoc 之外，用户能读到的降级契约**。

### 2.4 `SettingsVisibility` 的门控（含**三条** C 档门控）

```kotlin
// SettingsVisibility.kt:100-118（原文，节选）
/** 需要「T2 炫技档」才可用的档位值（probe-waveform-tier.md §3.1：T2 炫技）。 */
const val TIER_SHOWCASE: Int = 2
/** `visualizer_tier` 的默认档（probe-waveform-tier.md §3.3：默认 T1 精致）。 */
const val TIER_DEFAULT: Int = 1
val TIER_RANGE: IntRange = 0..TIER_SHOWCASE
/** v2.8.0 新增的 **C 档细分开关**（4 项）。它们的门控是复合的： */
val SHOWCASE_DETAIL_IDS: Set<String> = linkedSetOf(
    "visualizer_shockwave", "visualizer_particles", "visualizer_perspective", "visualizer_drag",
)
```

`availabilityOf(entry, read)`（`:141-183`）里的三条判定逐条是：

| 门控对象 | 判据 | 不满足时 | 行号 |
|---|---|---|---|
| `visualizer_showcase` 本身 | `visualizerTier(read) == TIER_SHOWCASE` | `disabled(TIER_NOT_SHOWCASE_CAPABLE)`（可见但置灰） | `:163-169` |
| `in SHOWCASE_DETAIL_IDS`（4 项） | ① 档位 == 2 ② `visualizer_showcase == true` | ① → `TIER_NOT_SHOWCASE_CAPABLE`；② → `SHOWCASE_DISABLED` | `:171-181` |
| 非上述 id | — | `FREE`（**未知 id 也返回 FREE，不抛异常**） | `:182-183` |

`GatingReason` 的三个取值（`:19-50`）全是为这套 C 档门控服务的：`TTML_SOURCE_DISABLED`（其它组的硬依赖）、
`SWEEP_ANIMATION_INACTIVE`（其它组的软依赖）、`SHOWCASE_DISABLED`、`TIER_NOT_SHOWCASE_CAPABLE`。

**v2.9.0 的后果（【推导】）**：这 5 个 id 现在**全部 `isInternal = true`**，而 `SettingsRenderPlan.rowKindOf`
的第一行就是 `if (entry.isInternal) return SettingsRowKind.INTERNAL`（`SettingsRenderPlan.kt:143`），
所以这套 C 档门控在 v2.9.0 **已经没有任何 UI 效果**——它保留下来只是「不丢项」与「将来回滚/复用」的活化石。
`SettingsVisibilityTest` 仍在断言它，因此**不能顺手删**（删了会让既有测试变红、也会让回滚版本的门控语义丢失）；
但下一个读到它的人必须知道：**它不再决定用户看到什么**。这一点在 AGENTS.md 的 v2.9.0 节里没有写，本文补记。

---

## 3. 迁移方案（旧 key → 新档位）：逐条规则与依据

### 3.1 `visualizer_tier` → `motion_tier`；**缺 key 不写新键**

```kotlin
// MotionPrefs.kt:169-178（原文）
fun migratedTier(legacyTier: Int?, legacyShowcase: Boolean, deviceDefault: Int): Int? {
    if (legacyTier == null) return null
    val effective = MotionIntensity.sanitize(legacyTier, deviceDefault)
    // 炫技档但「炫技效果」关着 ⇒ v2.8.0 实际渲染的就是精致档（见对象 KDoc）。
    return if (effective == MotionIntensity.SHOWCASE && !legacyShowcase) {
        MotionIntensity.REFINED
    } else {
        effective
    }
}
```

- 返回 `null` 的**唯一**含义是「**不要写 `motion_tier`**」（`:167` 的 `@return` 原文）；
  `migrate` 里对应 `if (tier != null) it.putInt(KEY_TIER, tier)`（`:204`）——**null 就不写**。
- 依据：`MotionPrefs.kt:55-56` 原文「**没有选过档位的盘不写 `motion_tier`**：v2.8.0 的「缺 key ≠ 选了默认档」
  这条纪律（`VisualizerTier.defaultTier` 的解析默认值 + 换机后不该锁死在老默认上）原样保留」。
- 越界值**回落设备默认档**而不是常量 1：`MotionIntensity.sanitize(legacyTier, deviceDefault)`（`:171`），
  与 `VisualizerTier.sanitize` 同源（`MotionEffects.kt:70` 直接转发 `VisualizerTier.sanitize`）。
  低端机上回落常量 1 会把用户顶到精致档 —— 这是 v2.8.0 就写明的坑（`VisualizerPrefs.kt:96-98`）。

### 3.2 **有效档**规则：`legacyTier == 2 && !legacyShowcase` ⇒ 搬到 1（精致）

依据链（每一环都有原文）：

1. **v2.8.0 的 C 档是复合门**：`val showcaseOn = t >= VisualizerTier.SHOWCASE && showcase`（`VisualizerTier.kt:203`）——
   `tier==2` 只满足一半。
2. **`showcase` 默认 `false`**：`const val DEFAULT_SHOWCASE = false`（`VisualizerPrefs.kt:75`），
   读路径 `readShowcase` 的缺省就是它（`:105`）。
3. **所以「选了炫技但没开炫技效果」的老盘，在 v2.8.0 下渲染出来就是精致档。**
4. **用户可见文案自己承认了这件事**：

   > `const val SHOWCASE_DESCRIPTION = "仅「炫技」档生效；关闭后炫技档与精致档外观一致。"`
   > —— `VisualizerStrings.kt:98`（同一句也进了 i18n：`zh_CN.kt:106` 的 `visualizerShowcaseDescription`）

5. **照抄 2 的后果是语义变化**：v2.9.0 的 `motion_tier == 2` 意味着「全开」（细分开关合并进档位，
   `MotionEffects.kt:45-50` 的 KDoc 原文：「炫技档就是「全开」，细分交给档位本身」），
   于是这些用户会**突然多出**冲击波 / 粒子 / 3D —— 那不是「保留用户选择」，是给用户加了他从未见过的效果。
   `MotionPrefsTest` 里对应的用例注释把这条写死了：「照抄 2 会让他们突然多出冲击波与粒子 —— 那是语义变化，
   不是"保留用户选择"」（`MotionPrefsTest.kt:160-162`）。

### 3.3 `visualizer_auto_downgraded` → `motion_degrade_level = 1`（**不是** 3）

```kotlin
// MotionPrefs.kt:187-188（原文）
fun migratedDegradeLevel(legacyAutoDowngraded: Boolean): Int =
    if (legacyAutoDowngraded) MotionDegrade.UI_ADVANCED_OFF else MotionDegrade.NONE
```

- 依据（`MotionPrefs.kt:180-186` 的 KDoc 原文）：「只映射到 `UI_ADVANCED_OFF`：v2.8.0 的那次降级**已经体现在档位里**，
  这里再扣一次波形就是重复处罚；但完全不理会又等于把一台实测吃力的设备当新机。砍掉 B 档界面动效是代价最小、
  又确实回吐预算的那一格。」
- 「已经体现在档位里」的代码事实：v2.8.0 的自动降级**两个键一起写** ——
  `it.putInt(KEY_TIER, next)` + `it.putBoolean(KEY_AUTO_DOWNGRADED, true)`（`VisualizerPrefs.kt:196-199`），
  而且 `applyAutoDowngrade` 把降级后的档位**写回 `visualizer_tier`** 是**有意**的：
  「设置页显示的必须是「实际在渲染的那一档」，否则用户看到的是「炫技」、画面上是「精致」——那是撒谎」（`VisualizerPrefs.kt:54-55`）。
- 实测支撑（**HARD FACT / 既有记录**）：v2.8.0 的自动降级在 S6 上**几乎必然触发**
  （`docs/verification/v2.8.0/EVIDENCE.md:96` 的「自动降级 ✅ 真机触发（`visualizer_auto_downgraded=true`）」；
  `:114` 的「自动降级在 S6 上几乎必然触发（P50 本就 ≈20 ms）」）。所以这条规则影响的是**真实存在的盘**，不是理论分支。
- 三级阶梯的另一半依据是「不重复处罚」：`motion_degrade_level = 3` 会**同时**触发
  `MotionDegrade.cutsWaveformTier(3) == true`（`MotionEffects.kt:138`）并把档位再降一级
  （`MotionPrefs.applyAutoDowngrade` 的 `currentTier - 1`，`:228-231`）——那才是重复处罚。

### 3.4 五个「炫技细分开关」的处理：保留键、退出渲染、标 `internal + legacyV290`

三个动作的落点：`SettingsRegistry.kt:450-514`（`internal = true` + `legacyV290 = true`，逐条注释
「v2.9.0：细分开关合并进档位 ⇒ 只作为**迁移源**保留，不再有 UI 入口」）；
`MotionEffects.kt:45-50`（KDoc 说明为什么合并）；`MotionEffects.kt:244-252`（`VisualizerEffects.of(...)` 的调用点
把 5 个能力位**全部绑到 `waveformShowcaseEnabled` 一个参数**上）。

| 旧键 | 它在 v2.8.0 控制什么（原文/落点） | v2.9.0 由什么取代 |
|---|---|---|
| `visualizer_showcase` | C 档**总闸**：「`tier == SHOWCASE` **且** `showcase == true` 才逐项看 C 档细分开关」（`VisualizerTier.kt:189`） | **档位本身**：`motion_tier == 2` 即全开（`MotionEffects.kt:235`：`val showcase = advanced && t >= MotionIntensity.SHOWCASE`） |
| `visualizer_shockwave` | 冲击波涟漪（`VisualizerEffects.shockwave`，`VisualizerTier.kt:129`） | 同上（`motion_tier == 2`） |
| `visualizer_particles` | 波形条内的粒子（定长池 + SoA，`VisualizerTier.kt:131`） | 同上；**另有**背景层粒子（`motion.particles`，`MotionEffects.kt:261`） |
| `visualizer_perspective` | 波形条 3D 透视（新增 render layer，`VisualizerTier.kt:133`） | 同上；**另有**封面 3D 旋转（`motion.cover3d`，`MotionEffects.kt:263`） |
| `visualizer_drag` | 「点按切换着色」（v2.8.0 把拖拽有界降级成点按，`VisualizerPrefs.kt:113-114`） | 同上（`tapInteraction` 绑 `waveformShowcaseEnabled`，`MotionEffects.kt:250`） |

**为什么必须保留键而不是删掉**（三条，都写在源码里）：

1. **回滚不丢数据**：`MotionPrefs.kt:61` 原文「迁移**不删除、不修改任何既有键**（`visualizer_*` 一律原样留着，
   回滚安装不会丢用户数据）」；
2. **不能有第二个可写入口**：`SettingsRegistry.kt:445` 原文「两个键都能改档位就是双轨」；
   配套地，`VisualizerPrefs` 的 5 个 setter（`:250-258`）现在只**写盘 + 刷新镜像**，
   而 `MotionEffects` 的渲染路径**一个都不读**。
   **【事实】本轮 grep 的机械证据**：`VisualizerPrefs.readShowcase / readShockwave / readParticles /
   readPerspective / readTapInteraction / readEffects` 在 `app/src/main` 里的调用点**为零**
   （唯一命中是 `VisualizerPrefs.kt` 自己的定义与 KDoc）；它们现在**只被 `VisualizerPrefsTest` 使用**
   （`VisualizerPrefsTest.kt:65-140`、`:274`）。生产读路径是
   `derivedStateOf { MotionPrefs.effects.value.waveform }`（`VisualizerPrefs.kt:218`）。
   也就是说：**这 5 个键的读通路在运行时已经没有任何消费者** —— 这是「保留键但不参与渲染」最硬的证据；
3. **单测要把「键名」当契约钉住**：`MotionPrefsTest.键名字面量被钉住`（`:45-51`）、
   `迁移读的旧键名与 VisualizerPrefs 逐字一致`（`:54-63`）、`新键与 v2_8_0 的旧键不重名`（`:66-84`）。

> **【事实】一个容易误读的点**：`MotionEffects.of(...)` 仍然把 `waveformShowcaseEnabled` 作为参数
> （`MotionEffects.kt:217`，默认 `true`），并且把它同时喂给 5 个 C 档能力位（`:246-250`）。
> KDoc 明确写了它**只留给单测做 A/B**：「`waveformShowcaseEnabled` 只留给单测做 A/B（证明渲染结果确实由档位驱动，
> 而不是由遗留键驱动）」（`MotionEffects.kt:242-243`）。生产路径 `MotionPrefs.readEffects`（`:118-127`）
> **不传这个参数**（用默认 `true`），所以遗留键永远不会把 C 档关掉 —— 这正是「细分开关退出渲染」的机械保证。

### 3.5 迁移水位：新键 `motion_version`

```kotlin
// MotionPrefs.kt:114-115（原文）
fun readVersion(prefs: SharedPreferences): Int =
    intOrNull(prefs, KEY_VERSION) ?: VERSION_PRE_V290
```

- `VERSION_PRE_V290 = 0`（`:82`）的语义是**未迁移**，不是「已迁移到 0」；`CURRENT_VERSION = 1`（`:85`）。
- `migrate` 的幂等判据就一行：`if (readVersion(prefs) >= CURRENT_VERSION) return false`（`:196`）。
- **旧水位 `visualizer_tier_version` 不参与这次迁移**（它只被 registry 标成 `legacyV290` 保留，`:517-529`）——
  两个水位各管各的版本段：`visualizer_tier_version` 管 v2.8.0 内部，`motion_version` 管 v2.8.0 → v2.9.0 这一段。

### 3.6 规则总表

| # | 旧键（v2.8.0） | 新键（v2.9.0） | 规则 | 依据 |
|---|---|---|---|---|
| 1 | `visualizer_tier` 存在 | `motion_tier` | 搬**有效档**：`==2 && !showcase` ⇒ `1`；越界回落设备默认档 | `MotionPrefs.kt:169-178`；`VisualizerTier.kt:203`；`VisualizerStrings.kt:98` |
| 2 | `visualizer_tier` 缺失 | 不写 | 返回 `null` ⇒ 不落 `motion_tier`，保留「没选过」 | `MotionPrefs.kt:170/204`、`:55-56` |
| 3 | `visualizer_auto_downgraded == true` | `motion_degrade_level` | 写 **1**（`UI_ADVANCED_OFF`），不是 3 | `MotionPrefs.kt:187-188`、`:180-186` |
| 4 | `visualizer_showcase/_shockwave/_particles/_perspective/_drag` | —（无） | 不再参与渲染；registry 里 `internal + legacyV290`；键原样留着 | `SettingsRegistry.kt:450-514`；`MotionEffects.kt:45-50` |
| 5 | `visualizer_tier_version` | —（无） | 不参与；单独保留为 legacy | `SettingsRegistry.kt:515-529` |
| 6 | （新） | `motion_version` | 每次迁移末尾写 `CURRENT_VERSION`（=1）；缺 key = 未迁移 | `MotionPrefs.kt:82/85/196-207` |
| 7 | 任何键 | — | 迁移**不删、不改**任何既有键 | `MotionPrefs.kt:195-207` 的实现 + `:61` 的承诺 |
| 8 | 用户改档位 | `motion_degrade_level` | 写回 `NONE`（用户显式意图优先于自动降级） | `MotionPrefs.kt:137-142` |

---

## 4. 迁移单测覆盖

### 4.1 `MotionPrefsTest`（`app/src/test/java/.../ui/player/motion/MotionPrefsTest.kt`，344 行 / **24** 个 `@Test`）

| 组 | 用例名（逐字） | 行号 | 它挡什么 |
|---|---|---|---|
| 键名契约 | `键名字面量被钉住` | `:45` | 4 个新键的**字面量**（`motion_tier` / `ui_motion_enabled` / `motion_degrade_level` / `motion_version`）+ `PREFS = "ncrust_settings"` 改名即红 |
| 键名契约 | `迁移读的旧键名与 VisualizerPrefs 逐字一致` | `:54` | `MotionPrefs.LegacyKeys` 与 `VisualizerPrefs.KEY_*` 三个历史键不会分叉（`:57-62`） |
| 键名契约 | `新键与 v2_8_0 的旧键不重名` | `:66` | 4 新键 ∩ 8 旧键 = ∅（`:83`） |
| 纯读 | `缺 key 时用解析出来的设备默认档` | `:89` | 缺 key ⇒ 低端机 `SIMPLE` / 高端机 `REFINED`；`hasExplicitTier == false`；总开关默认 `true`；水位 = `VERSION_PRE_V290` |
| 纯读 | `缺 key 绝不写回盘` | `:102` | 读路径零写盘（`prefs.snapshot().isEmpty()`，`:106`） |
| 纯读 | `显式选择优先于设备默认档` | `:110` | 低端机上用户显式选的档**必须被尊重**（`:114-118`） |
| 纯读 | `越界档位回落调用方给的默认档` | `:122` | `-1 / 3 / 99` 三种坏值，低端与高端两个默认档各断言一次（`:126-127`） |
| 纯读 | `类型不符的脏数据不抛异常` | `:132` | `motion_tier = "2"` / `ui_motion_enabled = 1` / 水位 `"v1"` ⇒ 全部回落默认（`MemoryPrefs.getInt` 是**硬转**，与 Android 原实现一致，`MemoryPrefs.kt:48-49`） |
| 迁移 | `有效的炫技档原样搬过来` | `:147` | `tier=2 && showcase=true` ⇒ `2` |
| 迁移 | **`炫技档但炫技效果关着时搬到精致档`** | `:159` | 本版迁移的**关键决定**：`tier=2 && showcase=false` ⇒ `1`（`:163-170`） |
| 迁移 | `简洁与精致档不受炫技开关影响` | `:174` | `SIMPLE/REFINED` × `showcase ∈ {true,false}` 四格全等（`:175-183`）——**四种输入组合**里的另一半 |
| 迁移 | `没选过档位的老盘不写 motion_tier` | `:187` | `legacyTier = null` ⇒ 返回 `null`（`:188-191`） |
| 迁移 | `自动降级过的老盘从第一级界面动效降级起跑` | `:195` | `auto_downgraded=true` ⇒ `UI_ADVANCED_OFF`；`false` ⇒ `NONE`（`:196-200`） |
| 迁移 | `迁移把老盘搬成新键并留下水位` | `:204` | 搬迁 + 水位；并**逐键断言旧键不变**（`:214-215`） |
| 迁移 | `迁移是幂等的且第二次不写盘` | `:219` | 第一次 `true`、第二次 `false`，且 `afterFirst == prefs.snapshot()`（**盘逐键不变**，`:227`） |
| 迁移 | `迁移不碰任何无关键` | `:231` | `audio_visualizer` / `wifi_quality` / `lyrics_ttml_enabled` / `theme_mode` / `visualizer_tier_version` 五个键原样（`:233-242`） |
| 迁移 | `全新安装不写任何键` | `:246` | 断言 `prefs.snapshot().keys == setOf(KEY_VERSION)` —— 名称说「不写任何键」，**实际断言是「只写水位一个键」**（`:248-251`）；名字与断言略有不一致，但断言本身正是任务书要求的语义 |
| 迁移 | `低端机的老盘按设备默认档兜底越界值` | `:255` | 老盘 `visualizer_tier = 7` + 低端默认档 ⇒ 写 `SIMPLE`（不是精致） |
| 写/降级 | `用户改档位会重置降级水位` | `:265` | `writeTier` 把水位写回 `NONE`（`:269-273`） |
| 写/降级 | `界面动效总开关不重置降级水位` | `:278` | 总开关是稳定偏好、水位是设备结论，两者生命周期不同（`:282-286`） |
| 写/降级 | `自动降级逐级推进并且写回盘` | `:290` | 三级依次 `1 → 2 → 3`；**前两级波形档位原封不动**（`:299-303`）；第 3 级才把 `motion_tier` 减一（`:305-310`）；到顶 `null`（`:312`） |
| 写/降级 | `已经在简洁档时第三级不再越界` | `:316` | `SIMPLE` 档上第 3 级不产生 `-1`（`:320-321`） |
| 写/降级 | `降级之后读出来的能力位立刻反映新水位` | `:325` | 第 1 级之后 `anyAdvanced == false && anyShowcase == false`，但 `anyBasic == true`（`:333-334`） |
| 常量 | `当前水位常量与阶梯上界一致` | `:339` | `MotionDegrade.MAX == WAVEFORM_DOWN`、`CURRENT_VERSION == 1`、`MotionIntensity.DEFAULT == VisualizerTier.REFINED`（`:340-342`） |

### 4.2 `SettingsRegistryTest` 的三条机械防线（各自挡什么）

| 用例 | 行号 | 挡什么 | 断言形状 |
|---|---|---|---|
| `newV290KeysAreExactlyTheMotionSet` | `:93-102` | **「v2.9.0 顺手夹带无关功能项」**（一起听 / 下载管理 / 流量管理 / 备份恢复…）；也挡「新键少了一个」 | `filter { isNewInV290 }.mapNotNull { key }.toSet() == NEW_V290_KEYS`（`:426-431` 硬编码 4 个键）且 `size == 4` |
| `v280KeysAreAllLegacyUnderV290` | `:105-114` | **「旧键还留在 UI 上（双轨）」**与**「旧键被悄悄删掉（回滚丢数据）」**：逐个断言 `legacyV290` ⇒ `isInternal == true`（`:111`）且 `isNewInV280 == true`（`:112`），集合等值于 `NEW_V280_KEYS`（7 个） | 双向等值 + 逐项蕴含 |
| `v290EntriesAreReachableAndHaveStrings` | `:117-134` | **「新增了可见项却没人渲染 / 没文案」**：非 internal 的新项必须**恰好**是 `{motion_tier, ui_motion_enabled}`，且每个都过 `SettingsRenderPlan.isRenderedOnGroupPage(entry)`、有 `titleKey`、且 `titleKey` 以 `"waveform."` 开头（新文案按纪律放 `WaveformStrings`） | 集合等值 + 三个逐项断言 |

配套（不在任务书点名的三条里，但同属这套防线，读的时候不要漏）：

- `legacyPrefKeysArePreservedExactly`（`:44-59`）：**42 键双向等值**，判据里显式排除了 `isNewInV280 || isNewInV290`
  （`:49`，注释原文：「否则这条断言会因为**新增**而变红，那正好把"机械防线"变成"每次加功能都要改测试"的噪声源」）；
- `newAndLegacyKeySetsAreDisjointAndAllKeysAreUnique`（`:76-90`）：三组（legacy / v280 / v290）两两不重名；
- `registryExposesNoWriteLikeApi`（`:381`）：registry 不给写盘能力。

### 4.3 兄弟测试（同一批新增，一并列出便于核对覆盖面）

| 文件 | 用例数 | 覆盖 |
|---|---|---|
| `MotionEffectsTest.kt` | 15 | 三档能力位逐档断言、`motion_tier==2` 与遗留键解耦（`波形那一半仍然按 v2_8_0 的档位语义走`）、总开关关掉时三层全关且波形不受影响、三级降级与 A/B/C 的单调对应、`需要帧时钟的效果集合被如实声明`、`背景层在纯色回退时仍然挂载` |
| `MotionEnvelopeTest.kt` | 16 | 响度包络夹取/回落、暂停清基线避免起播误判 onset、突变触发脉冲、安静段不被底噪触发、冷却、强拍门槛高于普通门槛、脉冲收敛到精确零、`dt` 脏值不产生 NaN、两个池定长有上限、关掉能力位立刻清空残留 |
| `CoverBlurTest.kt` | 13 | 见 `probe-blur.md` §8 |

### 4.4 **未覆盖 / 缺口**（如实）

1. **`MotionPrefs` 的 Context 路径没有 JVM 单测**：`ensureLoaded`（`:264-273`）、`setTier`（`:276`）、
   `applyAutoDowngrade(context)`（`:302-306`）、`deviceDefaultTier`（`:283-295`）都依赖 `Context` + `ActivityManager`；
   单测只覆盖纯函数与 `SharedPreferences` 版本。**【未验证】**：本轮的「读盘 → 迁移 → 解析默认档」整链
   在真机上的行为（S6 是否真的只写 `motion_version` 一个键）**本轮没有实测**。
2. **`MotionSettingsReachabilityTest` 不存在**：`MemoryPrefs` 的 KDoc 写「v2.9.0 起提到公共位置，供
   `MotionPrefsTest` / `MotionSettingsReachabilityTest` 复用」（`MemoryPrefs.kt:24-25`），
   但全仓 `app/src/test` **没有这个文件**（`find` 零命中）。**KDoc 引用了一个不存在的测试类**，属实文陈旧点。
3. **`visualizer_auto_downgraded` 不在任何集合断言里**（§1.3）：它的保留完全靠迁移代码自觉。
4. **`SettingsVisibility` 的 C 档门控已成活化石**（§2.4）：没有测试断言「它不再影响渲染」，
   所以将来若有人把它接回渲染，测试也不会红。

---

## 5. 旧盘状态 → 新盘状态 对照表

约定：`D` = 设备解析默认档（判据 `VisualizerTier.kt:78-82`）。**S6 ⇒ 简洁 `0` 是【事实】**（API 24 ≤ `LOW_TIER_MAX_SDK_INT = 25`，直接命中低端判据，`VisualizerTier.kt:62/78-82`）；**PCL110 ⇒ 精致 `1` 是【推导】**（API 36 > 25 不命中该条，且它不是低内存设备；`deviceDefaultTier` 还看 `totalMem`，那个值不在本轮的硬事实清单里）。
「新盘」列只列**迁移这一动作**写下的键；其余键（含全部 `visualizer_*`）逐键不变（`MotionPrefs.kt:195-207`）。

| # | 旧盘（v2.8.0 的 `ncrust_settings`） | 新盘新增/改动的键 | 用户在新版看到的 |
|---|---|---|---|
| 1 | **全新安装**（无任何动效键） | `motion_version = 1` | 档位 = `D`；界面动效全开（总开关默认 true）；背景模糊+呼吸可用 |
| 2 | **v2.8.0 但没选过档位**（只有 `audio_visualizer` 等，无 `visualizer_tier`） | `motion_version = 1`（**不写 `motion_tier`**） | 档位 = `D`（S6 ⇒ 简洁【事实】/ PCL110 ⇒ 精致【推导】）；「没选过」的信息保留 |
| 3 | `visualizer_tier = 0`（简洁） | `motion_tier = 0`、`motion_version = 1` | 简洁档：波形圆角柱+峰值；界面动效只留 A 档 |
| 4 | `visualizer_tier = 1`（精致） | `motion_tier = 1`、`motion_version = 1` | 精致档（默认观感） |
| 5 | `visualizer_tier = 2` + `visualizer_showcase = false`（或缺） | `motion_tier = 1`、`motion_version = 1` | **精致档**（= 他在 v2.8.0 下实际看到的样子，§3.2） |
| 6 | `visualizer_tier = 2` + `visualizer_showcase = true`（细分开关任意） | `motion_tier = 2`、`motion_version = 1` | 炫技档全开；5 个细分键保留但**不再被读** |
| 7 | `visualizer_auto_downgraded = true` + `visualizer_tier = 1`（v2.8.0 降级后写回的值） | `motion_tier = 1`、`motion_degrade_level = 1`、`motion_version = 1` | 档位不变（不重复处罚）+ 直接砍掉 B 档界面动效（全屏波形/歌词律动/节拍脉冲/视差） |
| 8 | `visualizer_tier = 2` + `showcase = true` + `auto_downgraded = true` | `motion_tier = 2`、`motion_degrade_level = 1`、`motion_version = 1` | 两条规则同时命中：档位照搬 2，但降级水位 = 1 |
| 9 | **坏数据**：`visualizer_tier = 7`（或 `-1`/`99`） | `motion_tier = D`、`motion_version = 1` | 回落设备默认档（**不是**常量 1；单测 `低端机的老盘按设备默认档兜底越界值`，`MotionPrefsTest.kt:255-260`） |
| 10 | **脏类型**：`visualizer_tier = "2"`（字符串） | 只写 `motion_version = 1`（`intOrNull` 抛异常被吞 ⇒ 等价于「没选过」） | 档位 = `D`（与 #2 同结果；单测 `类型不符的脏数据不抛异常`，`:132-142`） |
| 11 | **回滚**：装回 v2.8.0 | —（v2.9.0 不删不改任何旧键） | v2.8.0 读到的 `visualizer_*` 与升级前**逐字节相同** |

**【事实】关于 #7/#8 的一个副作用**：v2.9.0 的 `motion_degrade_level = 1` 与用户之后手改档位会互相作用 ——
用户只要在设置页动一次档位，`writeTier` 就把水位写回 `NONE`（`MotionPrefs.kt:137-142`）。
所以「从第 1 级起跑」是**初始水位**，不是永久惩罚。

---

## 6. 未验证清单（本轮明确没做的事）

1. **没有任何真机迁移实测**：谁都没有带着 v2.8.0 的 `ncrust_settings.xml` 升级过 v2.9.0。
   S6 与 PCL110 都**已 root**（Magisk / KernelSU），可以用 `/home/duanjb666/deepseek/set-prefs.py <serial> key=value`
   改写 prefs、并在升级后 `cat` 回 `ncrust_settings.xml` 做逐键对账 —— **这是留给 EVIDENCE.md 的动作**。
   WGR-W09 **当前锁屏、休眠且无 root**（HARD FACT），无法参与这一项。
2. **未跑 `./gradlew test`**：§4 的用例名与断言逐条来自源码阅读，**没有执行过**；
   `MotionPrefsTest` 是否真的有 24 个用例、有没有编译期/运行期失败，本轮未确认。
3. **`visualizer_auto_downgraded` 在真机上的实际取值分布未统计**（只有 v2.8.0 的「S6 上几乎必然触发」这一条记录，
   `EVIDENCE.md:114`）。
4. **`SettingsVisibility` 的 C 档门控「已无 UI 效果」是【推导】**（依据 `SettingsRenderPlan.kt:143` 的
   `isInternal → INTERNAL` 早退 + 7 个键全 `internal`），没有真机截图核对；
   结构上不可能有 UI 入口，但「设置页是否还残留某条隐藏入口」未逐屏核对。
5. **迁移失败路径未测**：`MotionPrefs.ensureLoaded` 用 `runCatching { migrate(...) }` 吞异常（`:269`），
   「迁移抛异常之后是否仍能读到正确档位」只有代码推导，没有构造过失败盘。
