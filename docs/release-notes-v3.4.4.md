# Ncrust v3.4.4-gpl

**versionCode 66** ｜ 全量单测 **2604 用例 / 0 失败 / 198 个测试类** ｜ `lintDebug` 零 error

按你定的两处观感改的。

---

## 一、小球：从「匀速下落 + 落地黏住」改成**牛顿落体 + 弹性碰撞**

你的原话：「**小球上下运动的函数要不就用牛顿运动学公式，和弹性碰撞的公式来做吧**」。

### 改前为什么弹不起来

```kotlin
PEAK_FALL_PER_SECOND = 0.9f              // 匀速下落 —— 恒定速度，不是重力
var fallen = previous - PEAK_FALL_PER_SECOND * dt / 1000f
if (fallen < bar) fallen = bar           // 落到柱高就停 —— 永不反弹
```

### 现在

```
v ← v − g·dt       重力，g = 32 /s²（由「满幅下落 250ms」反解：2×1.0 / 0.25² = 32）
v ← −e·v           柱顶作为地面的弹性碰撞，e = 0.45
```

`e = 0.45` 的读法：每次碰撞保留 45% 的速度 ⇒ **顶点高度剩 e² = 20%**。
第一次反弹是落差的 20%、第二次 4%、第三次 0.8% —— 三次内收敛，既看得出「弹」，
又不会在柱顶上永远振铃（`e → 1` 就是你说的「抖动」）。

| 量 | 改前 | 改后 |
|---|---|---|
| 落体加速度中位数 | **+0.00 /s²** | **−32.00 /s²** |
| 自由落体帧占比 | 0% | **100%** |
| 贴地反弹次数 | **0** | **85** |
| 一次滞空 | 126 帧（0.86 秒，慢飘） | 27 帧（0.18 秒，短促起落） |

### 顶点 = 0.2（按你指定的值）

实测 **0.19999999**。这里我**算错过一次**，如实记下来：

我一度按「参数 0.12 → 实测 0.135」乘 1.125 去反解 0.178 —— 但规范地跑 `BallStep`
得到的是 0.178 **与参数严格相等**，那个 0.135 是别处的观测量、不是这条判据的值。
现在直接用 0.2，并加了看门狗测试 `顶起目标的实测顶点接近用户要求的零点二`，
它断言的是**跑出来的顶点**（±0.02），不是常量 —— 这样以后谁改常量、观感变了，测试会立刻红。

---

## 二、短横与圆点**错开**（你选的方案）

你的原话：「**短横感觉错开比较合理**」。

### 改前为什么被遮住

v3.4.5 把圆点改成跟着小球走后，两者**共用同一个 `ballHalf`**：

| 元素 | 纵向位置 |
|---|---|
| 圆点圆心 | `centerY − ballHalf − r` |
| 短横矩形 | `[centerY − peakHalf − cap, centerY − peakHalf]`，而 `peakHalf = ballHalf` |

短横的矩形正好落在圆点的半径范围内 ⇒ **被完全遮住**，于是 `peaks` + `dots` 都开的档位上，
那条粉色虚线变成「一串小球」。

### 推导与修法

```
圆点圆心 cy = centerY − ballHalf − r
圆点上沿    = cy − r = centerY − ballHalf − 2r
短横下沿    = centerY − peakHalf
不重叠 ⇔ 短横下沿 ≤ 圆点上沿 ⇔ peakHalf ≥ ballHalf + 2r
```

⇒ 抬升量 = **`2r + cap`**。再 `+cap` 是让两者之间留出**自身厚度**那么大的空隙，
而不是「刚好贴着」—— 贴着在低分辨率屏上仍会读成一坨。

⚠️ **我第一版写成 `r + cap`（少算一个半径），测试当场抓住**（半高 0 处空隙 = −1.8，即重叠）。
这条几何很容易差一个半径，已在代码注释和测试里都留了记录。

**错开量与柱高无关**（有不变式测试断言）—— 若随柱高缩放，高柱上又会重叠，等于没修。
`separate = false` 时逐值退化成旧行为，供 A/B 与「为什么不能改回去」的对照测试。

---

## 验证

- 全量单测 **2604 用例 / 0 失败 / 198 个测试类**；`lintDebug` BUILD SUCCESSFUL；
- 新增 `BandBallisticsTest`（物理）、`BandMarkerGeometryTest`（错开几何）、`WaveformBallisticsAbTest`（改前/改后同口径对照）；
- 物理判据可独立复算：自由落体 `t=0.1s` 仿真 0.8384 vs `½gt² = 0.84` ✓；
  反弹 `−2.0 → +0.9144`（期望 `0.9`）✓；4000 帧随机地面**最大穿透 0.0** ✓。

## ⚠️ 未验证（请重点看这一节）

1. **没有视觉验证。** 我无法在本环境渲染这个 Composable（波形只在横屏大屏 / 平板宽屏挂载，
   模拟器对手机尺寸锁竖屏）⇒「**弹起感是否自然**」「**错开之后好不好看**」
   **只有数字、没有眼睛**，必须由你真机确认。
2. 上一版留下的两条**未解决项**原样保留：
   - 到达帧的周期间位移不匀；
   - `frameClockMs += dtForPhase.toLong()` 的取整疑点 —— 有人改成四舍五入后项目自己的
     两条判据立刻变红（最大步 5.88× 名义、相位停顶棚 478/1080 帧）⇒
     **向下取整正在替某处未定位的系统偏差打掩护**，已在原位留注释警告。
3. 小球在**单条曲线降级路径**下的行为未量（本次只覆盖三泳道 = 生产路径）。

---

## English summary

**v3.4.4** (`versionCode 66`) makes the waveform bead obey real kinematics.

**Bead:** replaced constant-velocity fall + "clamp to bar height and stick" with
**free fall + elastic collision**: `v ← v − g·dt` with `g = 32/s²` (solved from
"full-scale fall in 250 ms") and `v ← −e·v` at the bar top with restitution `e = 0.45`
(apex retains `e² = 20%`; converges within three bounces — `e → 1` would ring forever,
which is the "jitter" being complained about). Measured: fall acceleration median
**+0.00 → −32.00 /s²**, free-fall frames **0% → 100%**, ground bounces **0 → 85**,
airtime per hop **126 → 27 frames**. Apex is **0.2** as requested (measured 0.19999999),
guarded by a test that asserts the *measured* apex rather than the constant.

**Peak dash vs bead:** both used to share one `ballHalf`, so the dash's rectangle sat
inside the bead's radius and was completely hidden. Correct algebra gives a lift of
**`2r + cap`** (my first attempt used `r + cap` — one radius short — and the test caught it:
gap = −1.8 at zero height). The lift is deliberately **independent of bar height**, with an
invariant test, since scaling it would re-overlap on tall bars.

**Verified:** 2604 unit tests, 0 failures, 198 classes; lint clean.
**Not verified:** no visual confirmation is possible in this environment — whether the bounce
*feels* right and whether the separation *looks* right must be confirmed on a real device.
