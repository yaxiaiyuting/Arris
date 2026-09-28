# v3.2.0 · P0-B 真机取证：简洁档下封面确实在抖（**修前**，v3.1.0 release）

> **一句话结论**：在真机 S6 上，**出货版 v3.1.0**（`versionCode=54`）在**简洁档**渲染时，
> 左上角封面在连续 10 帧里相对屏幕发生 **±1…5px 的竖直位移**；同一批帧里
> **对照组（底部播放键图标）逐帧位移恒为 0** ⇒ 位移是真实的、只发生在封面这一层，
> 不是测量噪声。这与静态走查的根因判定一致：
> `PlayerCard.kt:1822-1825` 的 `translationY` 被 `MotionClock.pulse()` 乘 `2dp` 抬起/落下，
> 而它的门控 `motion.coverElevation` 在简洁档恒真（`MotionEffects.kt:352`）。

- 取证时间：v3.2.0 开发期（设备与被测包均为**修复前**状态）
- 取证性质：**只读**（没有安装任何新包、没有修改任何 prefs）——该设备是共享资源，
  同时间可能有别的任务在用，因此本轮**不做安装与 prefs 注入**
- 配套图片：[`s6-simple-tier-cover-frames.png`](s6-simple-tier-cover-frames.png)（10 帧封面并排 + 红色基准线）

## 1. 设备与构建（实测，不是回忆）

| 项 | 值 | 取法 |
|---|---|---|
| 序列号 | `0715f763f54c023a` | `adb devices` |
| 机型 / 系统 | SM-G9209 / **API 24**（Android 7.0） | `getprop ro.build.version.sdk` |
| 屏幕 | 1440 × 2560，`Physical density: 640` ⇒ **density = 4.0** | `wm density` |
| 已装包 | `versionName=3.1.0-gpl`，`versionCode=54` | `dumpsys package com.takahashirinta.ncrust` |
| 渲染档位 | **简洁档**（`ncrust_settings.xml` 里**没有** `motion_tier`；API 24 命中 `MotionIntensity.defaultFor` 的 `sdkInt < 26` 判据 ⇒ 解析为 `SIMPLE`） | `su -c cat …/ncrust_settings.xml` |
| 开关状态 | 盘上**没有** `ui_motion_enabled` / 任何 `motion_*` 开关 ⇒ 全部走默认（**全开**） | 同上 |
| 播放状态 | 正在播放《紫荆花盛开》（李荣浩/梁咏琪），`PlaybackState.state=3`（PLAYING） | `dumpsys media_session` |

**这正是用户报告的那个组合**：低端设备（API 24）默认就落在简洁档，而简洁档当时仍然带两个逐帧量
（背景呼吸 + 封面浮动）⇒「我选了简洁，界面还在抖」。

## 2. 方法（可复现，命令逐条给出）

```bash
S=0715f763f54c023a
adb -s $S shell input keyevent KEYCODE_WAKEUP
for i in $(seq -w 1 10); do adb -s $S exec-out screencap -p > f$i.png; sleep 0.25; done
# 每帧间隔 ≈ 1.0 s（screencap 在 S6 上约 0.7 s，加上 0.25 s 等待）
```

分析（`numpy`，相位相关法；脚本见本节末）：

1. **封面区域**：帧 1 的饱和度 > 40 的像素给出外接框 `x=0..223, y=144..366`，
   裁 `x∈[0,240), y∈[90,400)`（含上方留白，便于看基准线）；
2. **位移**：相邻帧裁剪块做 `ifft2(fft2(a)·conj(fft2(b))/|·|)` 取峰值 ⇒ 整数像素 `(dy, dx)`；
3. **对照组**：底部播放键图标 `x∈[600,840), y∈[1740,1930)`（静态 UI），同一算法必须给 0；
4. **背景呼吸**：两块纯背景区域（`x∈[1100,1400), y∈[1180,1320)` 与 `x∈[60,300)` 同 y）的
   平均灰度逐帧变化。

## 3. 结果

### 3.1 封面竖直位移（相对上一帧）

| 帧对 | f1→f2 | f2→f3 | f3→f4 | f4→f5 | f5→f6 | f6→f7 | f7→f8 | f8→f9 | f9→f10 |
|---|---|---|---|---|---|---|---|---|---|
| `dy` (px) | **−2** | **+1** | **−3** | **−1** | **−2** | **+5** | **+1** | **+2** | 0 |
| `dx` (px) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

- 竖直位移绝对值和 = **17 px / 9 对帧**；水平位移**恒为 0**。
- 幅度上限可以算出来：`AppMotion.COVER_FLOAT_DP = 2dp` × density **4.0** = **8 px**
  ⇒ 实测 ±1…5 px 落在这个包络内（每帧采样到的是 350ms 指数衰减曲线上的随机相位）。

### 3.2 对照组（同一批帧、同一算法、静态 UI）

| 帧对 | f1→f2 | f2→f3 | f3→f4 | f4→f5 | f5→f6 | f6→f7 | f7→f8 | f8→f9 | f9→f10 |
|---|---|---|---|---|---|---|---|---|---|
| `dy` (px) | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |

⇒ **算法不会凭空造出位移**；封面那 17px 是画面里真实存在的运动。

### 3.3 背景呼吸（第二处逐帧量）

| 区域 | 逐帧平均灰度极差 |
|---|---|
| `x∈[1100,1400), y∈[1180,1320)` | **0.933 / 255**（78.018 → 78.349 → 77.416，先升后降） |
| `x∈[60,300), y∈[1180,1320)` | **0.896 / 255**（77.253 → 77.897 → 77.001） |

背景层上压着 0.72–0.86 的可读性遮罩，所以「背景自身的 ±5% 亮度呼吸」在成品画面上只剩
**≈0.4% 的整屏明暗起伏** —— 量级与实测一致。它比封面位移更难被肉眼指名，但确实是第二处
随响度起伏的逐帧量（修前在简洁档同样开着）。

### 3.4 图片

`s6-simple-tier-cover-frames.png`：10 帧的封面裁剪并排，红线 = 第 1 帧的封面顶边。
可以肉眼看出第 2 / 4 / 5 / 6 帧整体偏上、第 3 / 7 / 8 帧偏下。

## 4. 口径与诚实边界（不许含糊）

1. **本证据证明的是「修前出货版确有抖动」，不证明「修复后不抖」**。
   后者要在设备上装一个含本版修复的新包 ⇒ 与共享设备的其它任务冲突，
   本轮**没有做**（未验证项，见下）。
2. **采样率不是 60 fps**：帧间隔 ≈1.0 s，而抖动周期是「一次瞬态 + 350 ms 衰减」，
   所以上表**不是**帧时间/幅度分布，只是「画面确实在动 + 幅度落在 8px 包络内」的证据。
3. **帧时间（`dumpsys gfxinfo framestats`）本轮未采集** —— 本 P0 的判据是
   「简洁档有没有逐帧量」（纯逻辑、可单测），不是「掉了多少帧」。
4. 截图只能证明**某一帧的布局**；本轮的结论靠的是**多帧位移**（含静态对照组），
   单张截图不构成任何结论。
5. 抖动幅度与**曲目**有关：本轮只有一首（《紫荆花盛开》）的样本；
   `PULSE_TAU_MS = 350`、`COVER_FLOAT_DP = 2dp`、`BREATH_*` 三个常数未做跨曲风 A/B。

## 5. 复现脚本（分析部分）

```python
import numpy as np, glob
from PIL import Image
files = sorted(glob.glob('f*.png'))
imgs = [np.asarray(Image.open(f).convert('L'), dtype=np.float32) for f in files]

def shift(a, b):
    R = np.fft.fft2(a) * np.conj(np.fft.fft2(b)); R /= np.abs(R) + 1e-9
    r = np.fft.ifft2(R).real
    dy, dx = np.unravel_index(np.argmax(r), r.shape)
    if dy > a.shape[0] // 2: dy -= a.shape[0]
    if dx > a.shape[1] // 2: dx -= a.shape[1]
    return int(dy), int(dx)

cover = [im[90:400, 0:240] for im in imgs]      # 封面
ctrl  = [im[1740:1930, 600:840] for im in imgs] # 对照组：播放键图标
for i in range(1, len(imgs)):
    print(i, shift(cover[i], cover[i-1]), shift(ctrl[i], ctrl[i-1]))
```
