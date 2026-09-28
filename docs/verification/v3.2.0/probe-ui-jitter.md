# v3.2.0 · P0-B 探针：简洁档为什么还在抖 + 界面律动独立开关现状

> 结论先行（**两处代码，原文可核**）：
>
> 1. **`app/src/main/java/com/takahashirinta/ncrust/ui/player/PlayerCard.kt:1822-1825`** ——
>    封面的 `translationY` 每帧被 `MotionClock.pulse()`（**瞬态/鼓点脉冲**）乘上
>    `AppMotion.COVER_FLOAT_DP = 2dp` 抬起又落下；它的唯一门控是 `motion.coverElevation`，
>    而 `MotionEffects.of(...)` 在 **简洁档把 `coverElevation` 直接置为 `uiOn`（恒真）**
>    （`MotionEffects.kt:352`）。**这一层在简洁档下仍然随节拍上下位移** —— 这就是 P0-B 的抖动。
> 2. **`app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionBackdrop.kt:113-123`** ——
>    整块背景（模糊封面）的 `alpha`（±5%）与 `scaleX/scaleY`（±1%）每帧被
>    `MotionClock.level()`（**平滑响度包络**）调制；门控是 `motion.backgroundBreathing`
>    = `uiOn && switches.breathing`（`MotionEffects.kt:351`），**简洁档同样为真** ——
>    这是第二处「整屏随声音起伏」的抖动源。
>
> 其余节拍驱动动效（歌词律动 / 控制条脉冲 / 冲击波 / 光晕 / 粒子 / 3D）在简洁档**确实是关的**
> （它们都在 `refinedPlus` 之后），所以「简洁档还抖」**只可能来自上面两处**。
> 判断口径见 §5，逐条证据见 §3。

- 探针时间：v3.2.0 开发期，基线 `HEAD = 08e2641`（v3.1.0 已发布）
- 探针方法：**静态代码走查 + 挂载点 grep**（下列每一行都是 `sed -n` 读出来的原文，
  不是回忆）；真机取证口径见 §7
- 相关文档：`docs/verification/v3.0.0/probe/probe-motion-binding.md`（v3.0.0 的绑定层探针）、
  `AGENTS.md` 的 v2.9.0 / v3.0.0 两章

---

## 1. 三档能力位表（`MotionEffects.of(...)` 的逐格输出）

由 `MotionEffects.of(tier, uiMotionEnabled = true, waveformShowcaseEnabled = true,
switches = MotionSwitches.ALL_ON)` 纯函数映射（`ui/player/motion/MotionEffects.kt:312-368`）。
`refinedPlus = uiOn && t >= REFINED`（`:321`）、`showcase = refinedPlus && t >= SHOWCASE`（`:322`）。

| 能力位 | 简洁 0 | 精致 1 | 炫技 2 | 映射行 | 驱动量（谁让它变） |
|---|---|---|---|---|---|
| `backgroundBlur` 背景模糊 | ✅ | ✅ | ✅ | `:350`（`uiOn`） | 静态（每首歌算一次） |
| `backgroundBreathing` 背景呼吸 | ⚠️ **✅** | ✅ | ✅ | `:351`（`uiOn && switches.breathing`） | `MotionClock.level()` ← RMS 包络 |
| `coverElevation` 封面浮起 | ✅ | ✅ | ✅ | `:352`（`uiOn`） | **两种**：静态阴影 + `MotionClock.pulse()` 浮动 |
| `coverTransition` 切歌淡入 | ✅ | ✅ | ✅ | `:353`（`uiOn`） | 静态（切歌那一下，400ms tween） |
| `fullScreenWaveform` 背景级波形 | ❌ | ✅ | ✅ | `:355` | 波形环形缓冲（RMS） |
| `lyricPulse` 歌词律动 | ❌ | ✅ | ✅ | `:356` | `MotionClock.pulse()` ← 瞬态 |
| `beatPulse` 控制条脉冲 | ❌ | ✅ | ✅ | `:357` | `MotionClock.pulse()` ← 瞬态 |
| `parallax` 视差 | ❌ | ✅ | ✅ | `:358` | 卡片 `progress`（**手势**，不是音频） |
| `shockwave` 冲击波 | ❌ | ✅ | ✅ | `:360`（`refinedPlus && switches.shockwave`） | `MotionBackdropState` ← 瞬态 |
| `haloBloom` 光晕 | ❌ | ✅ | ✅ | `:361` | 同上（+ 强度） |
| `particles` 粒子 | ❌ | ✅ | ✅ | `:362` | 中高频能量 |
| `particleDensity` | LOW | LOW | HIGH | `:363` | — |
| `haloRings` | 1 | 1 | 2 | `:364` | — |
| `cover3d` 封面 3D | ❌ | ❌ | ✅ | `:366`（`showcase`） | `MotionClock.pulse()` ← 瞬态 |
| 波形那一半（`waveform.*`） | v2.8.0 简洁 | +flow/dots/breathe | +shockwave/particles/perspective | `:330-348` | 见 `VisualizerTier.kt:218-252` |

**读法**：简洁档当前只有 4 个界面能力位为真，其中 **`backgroundBreathing` 与 `coverElevation`
是逐帧的**（`needsFrameClock`，`MotionEffects.kt:270-271`）—— 也就是说简洁档**不是静态档**，
它每一帧都在推 `MotionClock`。这是 P0-B 的结构性前提。

---

## 2. 简洁档：逐个节拍驱动动效的判定（任务书 §二的第 2 条）

判据：**该效果的启用条件写在哪一行**，以及它是否真的在简洁档被挂载。

| 动效 | 启用条件（file:line） | 简洁档 | 依据 |
|---|---|---|---|
| 背景呼吸 breathing | `MotionEffects.kt:351` `uiOn && switches.breathing`；渲染侧 `MotionBackdrop.kt:113` `if (motion.backgroundBreathing)` | ⚠️ **触发** | 与档位**无关**（A 档），只受独立开关约束 |
| 封面浮动 coverFloat | `PlayerCard.kt:1799-1804`（读 `pulse()` 的条件）+ `:1822-1825`（`floatPx = if (motion.coverElevation) beatPulse * coverFloatPx else 0f`） | ⚠️ **触发** | 门控是 `coverElevation`（`MotionEffects.kt:352` = `uiOn`，恒真） |
| 歌词律动 lyricPulse | `MotionEffects.kt:356`；渲染侧 `PlayerCard.kt:1003` → `NcrustLyricsPanel.kt:564-569` `if (lyricPulseEnabled && index == currentIndex)` | ✅ 关 | `refinedPlus` |
| 控制条脉冲 beatPulse | `MotionEffects.kt:357`；渲染侧 `PlayerCard.kt:839-846` `if (motion.beatPulse)` | ✅ 关 | `refinedPlus` |
| 视差 parallax | `MotionEffects.kt:358`；渲染侧 `MotionBackdrop.kt:128` `if (motion.parallax)` | ✅ 关 | `refinedPlus` |
| 冲击波 shockwave | `MotionEffects.kt:360`；渲染侧 `MotionBackdrop.kt:193` | ✅ 关 | `refinedPlus && switches.shockwave` |
| 光晕 haloBloom | `MotionEffects.kt:361`；渲染侧 `MotionBackdrop.kt:213` | ✅ 关 | 同上 |
| 粒子 particles | `MotionEffects.kt:362`；渲染侧 `MotionBackdrop.kt:233` | ✅ 关 | 同上 |
| 3D 透视（封面 3D） | `MotionEffects.kt:366`；渲染侧 `PlayerCard.kt:1829-1833` | ✅ 关 | `showcase` |
| 背景级波形 fullScreenWaveform | `MotionEffects.kt:355`；渲染侧 `PlayerCard.kt:790` | ✅ 关 | `refinedPlus` |

**格外的发现（必须写下来，否则下一个改这里的人会踩）**：
`MotionFrameClock` 的挂载判据是
`motion.needsFrameClock || waveform.tier > SIMPLE || waveform.anyShowcase`
（`ui/player/motion/MotionClock.kt:190-192`）—— **简洁档的波形柱推进，一直搭的是
「背景呼吸」这个能力位的便车**。v2.8.0 的帧循环长在 `AudioVisualizerBars` 内部（每一档都跑），
v2.9.0 搬进 `MotionFrameClock` 之后，横屏/平板在**简洁档**能继续动，唯一原因是
`backgroundBreathing` 在简洁档为真。⇒ **一旦把呼吸从简洁档拿掉，不同时修这条判据，
简洁档的波形会冻住**（`AudioVisualizerBars` 已经退化成纯读取方，自己不再推进：
`ui/player/AudioVisualizer.kt:555-566`）。P0-B 的修复必须连着改这一行。

---

## 3. 「冲击波关了但界面还抖」的来源（任务书 §二第 3 条）

用户关掉的是 `motion_shockwave`；它**只**影响 `MotionEffects.kt:360` 与
`MotionBackdrop.kt:193-212` 那一块 Canvas 绘制。它与下面两处的**能力位没有任何关系**：

| 抖动层 | file:line | 幅度 token（`ui/theme/AppMotion.kt`） | 随什么变 |
|---|---|---|---|
| **封面 translationY** | `PlayerCard.kt:1822-1825` | `COVER_FLOAT_DP = 2.dp`（`AppMotion.kt:318`） | `MotionClock.pulse()`：瞬态那一帧置 1.0，之后按 `PULSE_TAU_MS = 350ms` 指数衰减（`MotionEnvelope.kt:105-109`、`:148-153`） |
| **背景 alpha + scale** | `MotionBackdrop.kt:119-123` | `BREATH_ALPHA_AMPLITUDE = 0.05`（`:326`）、`BREATH_SCALE_AMPLITUDE = 0.01`（`:329`） | `MotionClock.level()`：全带 RMS 的起音快/回落慢包络（`MotionEnvelope.kt:123-141`，`ATTACK/RELEASE_TAU_MS`） |

两处都是**逐帧**读 `MotionClock.generation` 这个 Compose 状态（`PlayerCard.kt:1802`、
`MotionBackdrop.kt:114/183`），所以「画面在动」与用户关掉的那一项无关。
**结论：用户看到的是封面的上下浮动（±2dp）与整块背景的明暗/缩放（±5% / ±1%），
不是冲击波。** 这也是为什么症状描述是"抖动"而不是"有光圈"。

---

## 4. 独立开关现状（任务书 §二第 2 条的"现状"部分）

读写入口**只有** `MotionPrefs`（`ui/player/motion/MotionPrefs.kt`），
设置页经由 `SettingsGroupScreen.kt` 的 `switchValueOf` / `writeSwitch`（`:316-437`）。

| 键 | 默认 | 读入口 | 写入口 | 作用于哪一层 |
|---|---|---|---|---|
| `ui_motion_enabled` | `true` | `MotionPrefs.readUiMotionEnabled`（`:137`） | `MotionPrefs.setUiMotionEnabled`（`:318`） | **总闸**：A/B/C 三层全部不挂载（`MotionEffects.kt:320/350-353/355-358/360-366` 全走 `uiOn`） |
| `motion_shockwave` | `true`（缺 key = 开，`DEFAULT_SWITCH`） | `readSwitches`（`:155-161`） | `setSwitch`（`:322`） | `MotionEffects.kt:360` → `MotionBackdrop.kt:193` |
| `motion_halo` | `true` | 同上 | 同上 | `MotionEffects.kt:361` → `MotionBackdrop.kt:213` |
| `motion_particles` | `true` | 同上 | 同上 | `MotionEffects.kt:362` → `MotionBackdrop.kt:233` |
| `motion_wave_bands` | `true` | 同上 | 同上 | `MotionEffects.kt:339-347` → 波形逐柱着色 / 三频带能量条 |
| `motion_breathing` | `true` | 同上 | 同上 | `MotionEffects.kt:351` → `MotionBackdrop.kt:113-123`（**就是"背景呼吸"本身**） |

**结论：v3.0.0 已有的五个开关里，`motion_breathing` 就是背景呼吸**（不重复造键）。
**缺口恰好是三个**：封面浮动、歌词律动、控制条脉冲 —— 它们**至今一个开关都没有**；
另外「律动类」没有一个统一闸（关掉一项就得逐项关，而 `motion_shockwave` 之类的
非律动效果又会被误以为与它有关，见 §3）。

---

## 5. 「律动」归类判据与归类结果（供 P1 的总开关使用）

**判据（可执行，不是口号）：该视觉量的唯一驱动源是否来自 `MotionEnvelope` 的
节拍 / 强拍 / 响度** —— 即渲染路径里是否读了 `MotionClock.pulse()` 或 `MotionClock.level()`。

- 读了 ⇒ **律动类**（跟音乐一起"动"，与手势、静态、波形子系统无关）；
- 只读 `progress`（手势）、静态资源、或走**另一套**子系统（`WaveformStore` 的环形缓冲、
  `MotionBackdropState` 的瞬态特效池）⇒ **不是律动类**。

| 动效 | 读的量（file:line） | 归类 | 理由 |
|---|---|---|---|
| 背景呼吸 | `MotionClock.level()`（`MotionBackdrop.kt:115`） | **律动** | 响度包络 |
| 封面浮动 | `MotionClock.pulse()`（`PlayerCard.kt:1803`） | **律动** | 瞬态/鼓点 |
| 歌词律动 | `MotionClock.pulse()`（`NcrustLyricsPanel.kt:566`） | **律动** | 瞬态/鼓点 |
| 控制条脉冲 | `MotionClock.pulse()`（`PlayerCard.kt:842`） | **律动** | 瞬态/鼓点 |
| 封面 3D 旋转 | `MotionClock.pulse()`（`PlayerCard.kt:1830-1831`） | **律动** | 它**只有**这一个驱动源；律动闸关掉时 `rotationY` 恒为 0，位若仍为 true 就是"位在撒谎" |
| 冲击波 / 光晕 | `MotionBackdropState`（瞬态特效池，`MotionClock.kt:129-131`） | 不是律动 | 用户可感知为"一下特效"，与"界面在动"是两件事（任务书明确要求律动闸不影响它们） |
| 粒子 | 中高频能量（`MotionBackdropState.update`） | 不是律动 | 同上 |
| 视差 | 卡片 `progress`（`MotionBackdrop.kt:129`） | 不是律动 | 手势驱动 |
| 背景级波形 | `WaveformStore` 环形缓冲 | 不是律动 | 波形子系统自己的量 |
| 背景模糊 / 封面阴影 / 切歌淡入 | 静态 / 一次性 tween | 不是律动 | 没有逐帧量 |

---

## 6. P0-B 根因判定

### 6.1 `MotionEffects.of()` 有没有真的把 `tier` 传进去？——**有**

`MotionEffects.kt:318-322`：`t = sanitize(tier, REFINED)` → `refinedPlus` → `showcase`，
B/C 档的能力位逐条走这两个布尔（`:355-366`）。所以「简洁只是波形档位、界面动效仍按精致跑」
这个假设**不成立** —— B/C 档的界面动效在简洁档确实是关的（§2 已逐条核对）。

### 6.2 那简洁档到底哪一层还在抖？——**A 档的两个位**

`MotionEffects.kt:349-353` 这四行把 A 档四项写成**与档位无关**（`背景Blur = uiOn`、
`breathing = uiOn && switches.breathing`、`coverElevation = uiOn`、`coverTransition = uiOn`）。
其中两项是逐帧量：

1. **`PlayerCard.kt:1822-1825`（封面的 `translationY`）** —— **本 P0 的答案**。
   `coverFloatPx`（`PlayerCard.kt:323`）= `AppMotion.COVER_FLOAT_DP` = **2dp**，
   `beatPulse` 来自 `MotionClock.pulse()`，**每来一次瞬态就抬 2dp、350ms 内指数落回**。
   `motion.coverElevation` 在简洁档为 `true` ⇒ 简洁档封面跟着鼓点上下浮动。
   **一个能力位同时表示"静态浮起阴影"与"随节拍浮动"两件事**，是这次 bug 的结构性根因。
2. **`MotionBackdrop.kt:119-123`（背景的 `alpha` / `scaleX` / `scaleY`）** —— 缩放 ±1%、
   亮度 ±5%，随响度包络起伏（`MotionEnvelope.kt:123-141`）。
   注意它被 v2.9.0/v3.0.0 的档位表**有意**写在简洁档（`MotionEffects.kt:193` 的表格行
   「简洁 | 波形基础 + 背景模糊 + **背景呼吸**」）—— 也就是说这不是"漏了门控"，
   而是**当年把"会动"当成了简洁档的组成部分**。v3.2.0 的取舍是：
   **简洁档 = 静态档**（背景模糊 + 封面阴影 + 切歌淡入，零逐帧量），
   因为用户报的就是「我选了简洁，它还在抖」。

### 6.3 与 v3.0.0「取消自动降级」的关系——**有，但不是回退**

- v3.0.0 删掉了整条降级链路（`AGENTS.md`「⚠️ 本版把『自动降级』整个删除了」），
  两条反射断言守着：`MotionEffectsTest.能力位对象上不存在降级水位字段`、
  `MotionPrefsTest.不存在任何推进降级水位的公开入口`。
- 已核对**没有任何残留的降级水位字段影响渲染**：
  `motion_degrade_level` 的全部读点只有 `MotionPrefs.readDegradeLevel`（`:141`）
  与迁移（`:249/:269`），**渲染侧零引用**（`grep -rn "readDegradeLevel" app/src/main` 只命中
  `MotionPrefs.kt` 自身）。所以「简洁档还抖」**不是**降级残留造成的。
- 真正的关系是**语义上的**：v2.9.0 那套阶梯的第 1 级（`UI_ADVANCED_OFF` = 砍 B 档界面动效）
  是当时「让画面安静下来」的手段；它被删掉之后，**"安静"这件事只能由档位表自己负责**。
  而档位表把两处逐帧量留在了简洁档 ⇒ 用户选简洁之后没有任何办法让画面真的静止
  （只能去关 `motion_breathing`，而封面浮动**根本没有开关**）。这正是 P1 要补的缺口。

### 6.4 判定汇总

| 问题 | 结论 |
|---|---|
| 简洁档当前包含哪些动效 | 背景模糊 + **背景呼吸** + **封面浮起阴影** + **封面随节拍浮动** + 切歌淡入（§1 表） |
| 哪些节拍驱动动效在简洁档仍被触发 | **背景呼吸**（`MotionBackdrop.kt:113-123`）与**封面浮动**（`PlayerCard.kt:1822-1825`），其余全部已关 |
| 是否与取消自动降级有关 | 机制上无关（水位字段零渲染引用）；语义上有关（阶梯删掉后档位表成了唯一"安静"手段） |
| 是否"简洁"只作用于波形 | **不是** —— `tier` 确实传进了 `MotionEffects.of`（`:318-322`）并按档位收窄了 B/C；漏的是 A 档两项 |
| **简洁档仍在抖的那一层** | **`PlayerCard.kt:1822-1825`**（封面 `translationY`，±2dp 随 `pulse()`）与 **`MotionBackdrop.kt:119-123`**（背景 `alpha/scale`，±5%/±1% 随 `level()`） |
| 连带缺陷（修复必带） | `MotionClock.kt:190-192` 的 `clockNeeded` 让简洁档的**波形**推进搭了背景呼吸的便车；只砍呼吸会把简洁档波形冻住 |

---

## 7. 真机取证与未验证项

### 7.1 已取证（只读，v3.1.0 出货版 = **修前**）

真机 S6（`0715f763f54c023a` / API 24 / density 4.0）上装的正是 **v3.1.0-gpl（versionCode 54）**，
盘上**没有** `motion_tier` ⇒ API 24 命中「< 26」判据、解析成**简洁档**，且五个开关全默认开
—— 与用户报告的组合完全一致。在该设备上连拍 10 帧（≈1s 间隔）做相位相关：

- **封面竖直位移 ±1…5 px / 帧**（9 对帧绝对值和 17px，`dx` 恒 0），包络上限
  `2dp × density 4.0 = 8px` 与 `AppMotion.COVER_FLOAT_DP` 吻合；
- **对照组（底部播放键图标）逐帧位移恒为 0** ⇒ 算法不会凭空造出位移；
- 两块纯背景区域的整屏明暗极差 **0.93 / 0.90（灰度 0…255）**，与「±5% 呼吸 × 0.72–0.86 遮罩」
  的量级一致。

完整数据、复现命令与诚实边界：**`docs/verification/v3.2.0/evidence-s6-simple-tier-jitter.md`**
（配图 `s6-simple-tier-cover-frames.png`）。本轮取证**没有安装新包、没有改 prefs**（共享设备）。

### 7.2 未验证项（如实）

- **修后未在真机取证**：证明「简洁档真的不动了」需要在设备上装一个含本版修复的新包，
  与共享设备上的其它任务冲突 ⇒ **本轮不做**。修后的判据目前只有单测
  （`MotionEffectsTest.简洁档是静态档：律动类能力位逐个为假` +
  `MotionClock.kt` 的 `clockNeeded` 结构保证）。
- **真机帧时间未采集**：本轮没有 `dumpsys gfxinfo framestats`。P0-B 的判据是
  「简洁档有没有逐帧量」（纯逻辑、可单测），不是「掉了多少帧」。
- **抖动幅度未做跨曲风 A/B**：只有一首歌（《紫荆花盛开》）的样本；
  `PULSE_TAU_MS = 350` / `COVER_FLOAT_DP = 2dp` / `BREATH_*` 三个常数沿用 v2.9.0 的取值。
- **`motion_breathing=false` 是否已能让 v3.1.0 用户满意**：没有复现过「关掉呼吸仍然抖」的
  用户报告，本轮只是从代码上判定封面浮动当时没有开关。
- **浅色主题 / 低端机上的主观幅度**（±2dp、±1%）未做 A/B。
- WGR-W09（华为平板）仍未能驱动 ⇒ 平板横屏形态下的简洁档未取证（沿用 v3.0.0 的未验证项）。
- **`motion_rhythm_enabled` 与 `ui_motion_enabled` 的 UI 表现未在真机复核**（本轮改设置页但不装包）。

---

## 8. 修复方案（本版落地）

### P0-B：简洁档 = 静态档

| 改动 | 落点 |
|---|---|
| 新增能力位 `coverFloat`（封面随节拍浮动），与 `coverElevation`（静态浮起阴影）**拆开** | `MotionEffects.kt`（字段 + `of(...)`：`coverFloat = refinedPlus && switches.rhythm && switches.coverFloat`） |
| `backgroundBreathing` 从 A 档收窄到 `refinedPlus`（简洁档不再呼吸） | `MotionEffects.kt:351` 那一行 |
| 封面浮动改用 `coverFloat`；`pulse()` 的读取条件同步改 | `PlayerCard.kt:1799-1804`、`:1822-1825` |
| **波形帧推进与界面动效解耦**（否则简洁档波形冻住） | `MotionClock.kt`：`MotionFrameClock` 新增 `waveformMounted` 参数，`clockNeeded = enabled && (motion.needsFrameClock || waveformMounted)`；`PlayerCard` 传入 `visualizerSlot || motion.fullScreenWaveform` |

**判据（单测可断言）**：`MotionEffects.of(0, switches = ALL_ON)` 的**全部律动类能力位为 false**，
且 `needsFrameClock == false`、`needsAudioFeatures == false` ⇒ 简洁档**没有任何逐帧界面动效**。

### P1：界面律动独立开关

- 复用 `motion_breathing`（背景呼吸，已有）；**新增 4 个键**：
  `motion_rhythm_enabled`（界面律动总开关）、`motion_cover_float`、`motion_lyric_pulse`、
  `motion_bar_pulse`；默认**全开**（缺 key = 与档位表一致，升级后不会少画东西）。
- 语义（写进 `MotionSwitches` / `MotionPrefs` 的 KDoc）：
  **有效 = 档位允许 AND `ui_motion_enabled`（总闸）AND `motion_rhythm_enabled`（律动闸，仅律动类）
  AND 逐项开关**。总闸关 ⇒ A/B/C 三层一个都不挂载（背景回纯色、零帧时钟）；
  律动闸关 ⇒ 只掐 §5 表里那五项，冲击波 / 光晕 / 粒子 / 视差 / 背景级波形 / 背景模糊一概不受影响。
- 归属分组：`SettingsGroup.APPEARANCE`，且**声明位置必须跟着 `group` 走**
  （`SettingsRegistryTest.renderOrderListLosesNothing`：按分组顺序拼接 == 声明顺序）。
- 依赖门控走 `SettingsVisibility` 的「可见 / 置灰 + 原因」，**绝不靠 alpha**
  （AGENTS.md 触摸陷阱第 1 条）。
- prefs 水位 `motion_version` **4 → 5**：迁移不搬运任何键值，只在「这张盘升级后实际渲染在
  简洁档」时补一条可读说明（简洁档收窄了：呼吸与封面浮动不再在这一档渲染）——
  与 v3.0.0「被降级过的盘才补说明」同一手法，避免给所有人塞噪音。

### i18n（`ui/i18n/` 由编排者独占 —— 本版**没有**改任何 i18n 文件）

四个新开关的文案已由编排者在 `Strings.waveform` 里补齐（每个键 8 语言齐全），
registry 的 `titleKey` / `subtitleKey` 与设置页的 `rowTitle` / `rowSubtitle`
**直接引用这些真实键**（没有占位复用、没有裸中文）：

| 新键（`WaveformStrings`） | 用在哪 |
|---|---|
| `motionRhythmLabel` / `motionRhythmDescription` | `motion_rhythm_enabled`（界面律动总闸） |
| `motionCoverFloatLabel` / `motionCoverFloatDescription` | `motion_cover_float`（封面浮动） |
| `motionLyricPulseLabel` / `motionLyricPulseDescription` | `motion_lyric_pulse`（歌词律动） |
| `motionBarPulseLabel` / `motionBarPulseDescription` | `motion_bar_pulse`（控制条脉冲） |

同时被 P0-B 逼着改掉的两条既有文案（编排者已改）：

| 键 | 为什么必须改 |
|---|---|
| `waveform.motionIntensityDescription` | 原文写「简洁：……界面动效只保留基础项（封面背景模糊、**背景呼吸**、**封面浮起**与切歌淡入）」——简洁档已收窄为**静态档**，那句话现在是假的 |
| `waveform.motionBreathingDescription` | 原文没写生效档位；现在必须点明「只在精致档及以上生效；简洁档是静态档」（否则用户会在简洁档找不到它） |

> 这两条（以及新增的 8 条）会让 `ui/i18n` 自己的**计数类断言**失效，属于编排者的收尾项：
> `StringsConstructorBudgetTest` 的外层 `Strings` 参数数 **136 → 137**（新增的是
> `PlaybackFailureStrings` 组参数，与本题无关）、`WaveformStrings` 参数数 **30 → 38**；
> `StringsMigrationTest` 的「波形组字段数」**30 → 38**。逐条数字见交付报告。
