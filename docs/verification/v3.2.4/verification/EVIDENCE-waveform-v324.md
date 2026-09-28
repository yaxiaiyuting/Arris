# v3.2.4 · P1 设备证据（帧率适配 / 音频条抖动）

> **先说清楚能证什么、不能证什么**：本环境**没有高刷真机**，所以
> 「120Hz 上不抖了」这句话**在本轮无法直接验证**。本文给的是三样**能验证**的东西：
> ①设备的真实刷新率（实测）②release 包在这台 60Hz 设备上确实按帧出图（实测）
> ③60Hz 上新旧判据**逐帧等价**（确定性单测，环境无关）。
> 高刷那一侧的判据是 `WaveformFrameRateTest` 的四档仿真 —— 它是**算术**，不是观感。

## 1. 设备刷新率（实测）

```
$ adb -s emulator-5554 shell dumpsys display | grep -E "supportedModes|mSfDisplayModes|fps="
DisplayDeviceInfo{"Built-in Screen", 1080 x 2340, modeId 1, defaultModeId 1,
  supportedModes [{id=1, width=1080, height=2340, fps=60.000004, alternativeRefreshRates=[]}], …}
mDisplayModeSpecs={baseModeId=1 allowGroupSwitching=false primaryRefreshRateRange=[0 60]
  appRequestRefreshRateRange=[0 Infinity]}
mSfDisplayModes=
  DisplayMode{id=0, width=1080, height=2340, xDpi=440.0, yDpi=440.0, refreshRate=60.000004, …}
```

**只有 60.000004Hz 一个模式**（`alternativeRefreshRates=[]`）⇒ 本机无法进入 90/120/144Hz，
`DisplayRefresh.strideFor` 的高刷分支在本机**跑不到**。

第二台设备 `127.0.0.1:5555` 形状相同（同为 `sdk_gphone64_x86_64` / API 33 / 60Hz）。

## 2. P1 的判据（确定性单测，不依赖设备）

`app/src/test/java/com/takahashirinta/ncrust/ui/player/WaveformFrameRateTest.kt` +
`app/src/test/java/com/takahashirinta/ncrust/ui/player/motion/DisplayRefreshTest.kt`，
共 **16 条用例**，全部 JVM 直跑、无设备依赖：

| 用例 | 断言 |
|---|---|
| `四档刷新率的帧间隔就是 1000 除以刷新率` | 16.667 / 11.111 / 8.333 / 6.944 ms |
| `非低内存设备在四档刷新率上都是每帧推进` | `stride == 1`（⇒ 推进率 = 面板刷新率） |
| `低内存设备在四档刷新率上都被节流到约 30fps` | 2 / 3 / 4 / 5 与 27~34fps |
| `非法刷新率一律回落到 60Hz` | 0 / −1 / 10 / 1000 / NaN / ±Inf |
| `非低内存设备在四档刷新率上推进率都等于面板刷新率` | 仿真 20s，误差 <2% |
| `四档刷新率上都不存在超过名义帧间隔 1_2 倍的帧` | 120Hz 上 `max dt / nominal = 1.0`（旧判据是 1.5） |
| `滚动位移总量等于消费掉的柱数` | 四档上「位移格数 ≈ 推入柱数」⇒ 同一条运动曲线 |
| `旧的时间阈值闸门在 120Hz 上会产生量化跳变` | 特征化：旧判据 `max dt = 1.5 个 vsync` |
| **`60Hz 上新旧判据逐帧等价 —— S6 的非回归证明`** | 两趟同种子仿真，**推进时刻逐个相等（容差 1µs）**、次数相同（10s 内 ~600 次） |
| `低内存设备在四档上都被节流到同一档` | 60/90/120/144Hz 全部落在 27~34fps |
| `回绕后相位必须归零` | 20s 内 >100 次回绕，0 次「回跳」 |

**为什么「60Hz 逐帧等价」比「在 60Hz 设备上再测一次帧时间」更强**：旧判据在 60Hz 上
恒真（vsync 16.667ms > 预算 16ms）⇒ 与 `stride = 1` **语义等价**，不是「差不多」；
而帧时间测量会被热漂移污染（v3.2.3 的实测口径：同一配置相隔 10 分钟就能差 7ms）。

## 3. release 包帧时间（实测，60Hz）

口径与 v3.2.2 / v3.2.3 相同：`dumpsys gfxinfo <pkg> reset` → **30 秒窗口** → `framestats`，
4 次采样只比中位数。**包 = release（`versionCode 59` / `versionName 3.2.4-gpl`，实测 `dumpsys package`）**。

配置（与「手机竖屏」那格不同 —— 波形只在横屏大屏模式挂载）：

| 项 | 值 |
|---|---|
| 形态 | **大屏模式横屏**（点竖屏控制栏的 ⤢ 进入），波形槽挂载 |
| 波形档位 | 默认（三条频带泳道，`motion_wave_bands` 默认开） |
| 播放 | **B 站歌曲播放中**（`PlaybackState{state=3}`，position 在推进） |
| 屏幕 | 1080×2340 物理 / density 440 → 横屏 2340×1080 |
| GPU | **模拟器软件光栅化** |

| 采样 | 总帧（30s） | Janky | p50 | p90 | p95 | p99 |
|---|---|---|---|---|---|---|
| 1 | 1562 | 15.36% | 34ms | 48ms | 48ms | 48ms |
| 2 | 1575 | 14.60% | 34ms | 48ms | 48ms | 48ms |
| 3 | 1606 | 12.39% | 34ms | 48ms | 48ms | 48ms |
| 4 | 1525 | 18.10% | 34ms | 48ms | 48ms | 53ms |
| **中位** | **1568.5** | **14.98%** | **34ms** | **48ms** | **48ms** | **48ms** |

原始件：`gfx-v324-bigscreen-1..4.txt`、`measure-v324-bigscreen.txt`、
汇总 `frame-times-summary.txt`、解析脚本 `parse-gfx.py`。

⚠️ **这组数字不具代表性，不作为性能结论**：模拟器是纯软件光栅化，2340×1080 全屏播放器
+ 三条泳道曲线的绝对耗时远高于真机（S6 的实测 p50 是 19ms）。它的作用只有一个 ——
**证明 release 包在这台设备上确实在按帧出图**（30 秒 1568 帧 ≈ 52fps，且帧间隔中位数
对齐 60Hz 的 16.67ms 网格）。
**没有**因此声称「性能无回归」；回归判据见 §2 的等价性用例。

## 4. 复现方式

```bash
# 装 release 包（versionCode 59）
adb -s emulator-5554 install -r app/build/outputs/apk/release/app-release.apk
# 开 B 站音源（release 包不可 run-as，用 root 直接写 prefs）
adb -s emulator-5554 shell su 0 sh -c "sed -i 's|</map>|    <boolean name=\"bilibili_enabled\" value=\"true\" />\n</map>|' \
  /data/data/com.takahashirinta.ncrust/shared_prefs/ncrust_settings.xml"
# UI：搜索 au39 → 只看 B 站 → 点第一条 → 上滑展开 → 点「大屏幕模式」
adb -s emulator-5554 shell input tap 676 2231 && adb -s emulator-5554 shell input tap 300 235 \
  && adb -s emulator-5554 shell input text au39 && adb -s emulator-5554 shell input tap 603 512 \
  && adb -s emulator-5554 shell input tap 500 709 && adb -s emulator-5554 shell input swipe 400 1900 400 1100 250 \
  && adb -s emulator-5554 shell input tap 746 1955
# 测量
/home/duanjb666/deepseek/.scratch/v324/measure_frames.sh emulator-5554 v324-bigscreen 30 4
```

## 5. 未验证项（如实）

| # | 项 | 原因 |
|---|---|---|
| 1 | **120Hz / 90Hz 真机上的观感** | 环境无高刷设备（§1 实测只有 60Hz 一个显示模式） |
| 2 | **S6（SM-G9209 / Android 7.0）真机回归** | 环境无该设备（`adb devices` 只有两台 API 33 模拟器）；代偿是 §2 的逐帧等价用例 |
| 3 | **低内存设备分支（stride > 1）在真机上的行为** | 需要 `isLowRamDevice == true` 的设备；分支本身有 5 条单测 |
| 4 | **`DisplayManager.DisplayListener` 在真实面板切换时的回调** | 模拟器没有第二个显示模式，切不了 |
