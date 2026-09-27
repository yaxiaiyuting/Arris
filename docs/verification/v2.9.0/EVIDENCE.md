# v2.9.0 证据索引（Ncrust GPL fork）

> 本文件记录 **v2.9.0-gpl** 的探针产出、实现落点、真机证据与未验证项。
> 所有数字都来自本轮实测；没有实测的地方一律写「未验证」，不估算、不代替。

## 0. 环境

| 项 | 值 |
|---|---|
| 仓库 | `/home/duanjb666/deepseek/Ncrust`，分支 `master` |
| 版本 | `versionName = "2.9.0-gpl"` / `versionCode = 52`（三源交叉校验，见 §5） |
| 构建 | `./gradlew clean testDebugUnitTest lint assembleDebug assembleRelease` = **BUILD SUCCESSFUL**（3m20s，1778 个单测全绿，lint 0 error） |
| 设备 | S6 `0715f763f54c023a`（SM-G9209 / **API 24 / Android 7.0**，1440×2560）· PCL110 `3B15CD00GB700000`（OnePlus PLC110 / **API 36 / Android 16**，1272×2800，144Hz）· WGR-W09 `WVQ6R22124000968`（华为平板 / API 31，**锁屏休眠且无 root，本轮未能驱动**） |
| 测量方法 | release 包 + `dumpsys gfxinfo`，30 秒稳态（与 v2.8.0 同方法同口径） |
| prefs 注入 | `/home/duanjb666/deepseek/set-prefs.py`（root 读写 `ncrust_settings.xml`；S6 = Magisk、PCL110 = KernelSU） |
| 测量脚本 | `/home/duanjb666/deepseek/tools/measure-v290.sh`（四条自检：方向 / 锁屏 / 进程活着 / 播放推进，任一不满足即判本次无效） |

## 1. 探针产出（`docs/verification/v2.9.0/`）

| 文件 | 行数 | 回答什么 |
|---|---|---|
| `probe-tier-migration.md` | 479 | 档位迁移：旧 key → 新档位、有效档规则、细分开关的处置、迁移单测覆盖 |
| `probe-ui-motion.md` | 746 | 播放器竖屏/横屏布局现状、封面/背景/歌词/控件位置、RMS 数据源、几何数字 |
| `probe-landscape-motion.md` | 349 | 横屏全屏动效：背景层插入点、全宽波形两个方案、视差可读的状态 |
| `probe-blur.md` | 468 | 背景模糊：降采样 + 盒式模糊 + 缓存 + 异常隔离 + 为什么不用 RenderEffect |
| `probe-perf-budget.md` | 593 | 性能预算与降级：A/B/C 每帧增量、三级阶梯、帧监控复用与注册点搬迁 |
| `PROBE-SUMMARY.md` | 130 | 四个问题的结论 + 与任务书前提的 4 条偏差 |

**与任务书前提的偏差（如实记录）**：① 任务书把「S6 P50 20ms / P99 46ms」「PCL110 P50 7ms / P99 14ms」
当作**基线**，实测它们是 **v2.8.0 的结果**（S6 那条还是 T0 简洁档），同方法同设备的真基线是
S6 P50 15 / P99 31；② 任务书要求「低版本（API 24~30）降级方案」，实测本实现**全 API 同一条路径**
（不依赖 `RenderEffect` / `Modifier.blur` / RenderScript），不需要降级；③ 任务书 §7.2 说「波形从左侧
扩展到全屏底部横跨」，真去改宿主会动到既有分栏结构与控制条位置、违反同一条任务书的 §7.3，本版
落地成**额外一层背景带**；④ §6.4 的真 FFT 未做，本案未翻案（低频通道见 §3.5，它不是 FFT）。

## 2. 实现落点

| 主题 | 文件 |
|---|---|
| 统一档位 / 能力位矩阵 / 三级降级阶梯（纯逻辑） | `ui/player/motion/MotionEffects.kt` |
| 新 prefs 键 + v2.8.0 迁移 + 有界降级 + 降级日志 | `ui/player/motion/MotionPrefs.kt` |
| 32px 盒式平均降采样 + 三遍可分离盒式模糊 + 观感修正 + URL 键 LRU | `ui/player/motion/CoverBlur.kt` |
| 响度包络 / 节拍与强拍 / C 档特效池（纯逻辑） | `ui/player/motion/MotionEnvelope.kt` |
| **唯一的帧时钟** + 帧时间监控挂载 | `ui/player/motion/MotionClock.kt` |
| 全屏动效背景层（模糊 / 呼吸 / 视差 / 粒子 / 光晕） | `ui/player/motion/MotionBackdrop.kt` |
| 幅度 token（±5% / ±1% / ±2dp / 视差 6% / 歌词 3% / 播放条 2% / 3D 6°） | `ui/theme/AppMotion.kt` |
| 背景层与背景级波形的挂载点（根 Box 内、内容 Column 之前） | `ui/player/PlayerCard.kt` |
| 歌词律动 + **换歌作废外推锚点**（`trackKey`） | `ui/player/NcrustLyricsPanel.kt`、`LyricsView.kt` |
| 低频（鼓/贝斯）通道：一阶低通，与全带 RMS 同一次遍历 | `player/TransparentWaveformSink.kt`（`PcmRms.analyze`） |
| 设置项 / 渲染计划 | `ui/settings/Settings{Registry,RenderPlan}.kt`、`ui/screen/SettingsGroupScreen.kt` |
| 文案（4 条 × 8 语言，进 `WaveformStrings` 而不是已 78 条的 `SettingsStrings`） | `ui/i18n/*.kt`、`ui/player/waveform/VisualizerStrings.kt` |

## 3. 真机验证（release 包 / `versionCode 52`）

### 3.1 帧时间（`dumpsys gfxinfo`，30 秒稳态）

| 轮次 | 设备 / 形态 / 档位 / 界面动效 | Total | Janky | P50 | P90 | P95 | P99 |
|---|---|---|---|---|---|---|---|
| `s6-portrait-t1-ui` | S6 竖屏展开 · 精致 · 开 | 1129 | 860 (76.17%) | 21ms | 30ms | 34ms | 150ms |

原始文件：`verification/frame-s6-portrait-t1-ui.txt`（完整 `dumpsys gfxinfo`）+ `frame-s6-portrait-t1-ui-state.png`（状态自检截图）。

对照基线（**同设备同方法**）：v2.6.2 基线 `944 / 366 (38.77%) / 15 / 25 / 27 / 31`；
v2.8.0 T0 `963 / 884 (91.80%) / 20 / 27 / 31 / 46`。⇒ v2.9.0 竖屏（含背景模糊 + 呼吸 + 全屏背景层）
的 P50 = **21ms**，与 v2.8.0 T0 的 20ms 同档、低于 v2.8.0 该轮 P99（46 → 150 的差异见 §4 的诚实说明：
S6 是流水线式掉帧，单轮 P99 抖动极大，v2.8.0 自己的对照轮就出现过 150ms）。

### 3.2 自动降级（三处真机缺陷的现场与修复）

| 现象 | 根因 | 证据 |
|---|---|---|
| 「竖屏动效切一次横屏之后全没了，回退成老 UI」（用户报告） | 阶梯走到第 2 级砍掉 A 档，且**永不恢复**；`FrameBudgetPolicy` 只保证「每实例一次」，`DisposableEffect` 重注册导致同一进程连跳 | `motion_degrade_log` 实测 `<ts> level=2 tier=2 ui=true why=over-budget 24/60`（PCL110）与 S6 的 `level=2` |
| 「PCL110 上根本没有竖屏美化」（用户报告） | Coil 在 API 26+ 默认给**硬件位图**，`Bitmap.getPixels` 对 `Config.HARDWARE` 抛 `IllegalStateException`，而「取不到就回退纯色」把它完全吞掉 | 修复前后截图对比（§3.4）；修复后 `logcat -s CoverBlur` 为空 |
| 「开一次横屏，炫技特效直接全没了，横屏也一样」（用户报告） | 第 3 级把 `motion_tier` 从 2 写成 1（"写回档位"是 v2.8.0 的语义） | 修复后实测：进横屏大屏一轮后 `motion_tier` **仍为 2**、`motion_degrade_level = 1` |

三处修复：① 进程级「已判定」标记 + 空操作级别跳过；② **用户显式选过档位 ⇒ 阶梯止步于第 1 级，
绝不动 `motion_tier`**；③ `copy(ARGB_8888, false)` 转软件位图 + `Log.w` 留痕；
④ `motion_version` 1→2→3 两条**纠正迁移**把已经落盘的过量降级夹回来。

### 3.3 播放链路未受影响（音频线程改动的验收）

低频通道在音频线程上每样本多一次乘加。验收方式：release 包播放中

- `dumpsys` / logcat：`STATE_PLAYING`，位置从 `0:00` 推进到 `4:30`；
- `logcat` 全量过滤 `ncrust|AudioSink|ExoPlaybackException`：**无**降档、无 `onPlayerError`、
  无 `CoverBlur` 警告（与 v2.2.1 P0 的现场形状逐条对照）。

### 3.4 观感证据（截图）

| 文件 | 说明 |
|---|---|
| `verification/ui-s6-portrait-before.png` | 修复前：背景是**没有色度**的灰（S6 实测逐段平均 `[43,43,46] / [38,38,40] / [45,45,48]`） |
| `verification/ui-s6-portrait-after.png` | 修复后：深海军蓝（vibrance + 纵向渐变遮罩） |
| `verification/ui-s6-landscape-after.png` | 横屏：背景铺满全屏（右栏歌词背后也有），B 档背景级波形在底部整宽 |
| `verification/ui-pcl110-portrait-after.png` | PCL110（API 36）：硬件位图修复后终于有背景；底部可见背景级波形 |

### 3.5 低频（鼓 / 贝斯）通道

- 实现：`PcmRms.analyze`（一阶低通，截止 `BASS_CUTOFF_HZ = 150`），与全带 RMS **同一次**逐样本遍历，
  结果打包成一个 `Long`（零分配）。
- 消费方：`WaveformEffectsState.update`（涟漪/粒子）与 `MotionEnvelope.update`（脉冲/光晕）。
- 单测：`PcmBassAnalysisTest` 8 例（60Hz 读数 > 6kHz 的 5 倍、全带读数与 v2.8.0 参考实现
  `PcmRms.of` 一致、低频 ≤ 全带、采样率未知时退化成低频=全带、系数公式与量级、打包可逆、
  状态跨缓冲连续、空缓冲/未知编码不抛异常）。
- **未验证**：真机上「鼓点是否真的更准」需要人耳判断（自动化只能证明低频读数确实只在低频段响应）。
  已知边界：只有一个低频通道，底鼓与贝斯仍不可区分；无鼓曲目触发变少是**正确行为**。

## 4. 未验证项（如实，不含推测）

1. **WGR-W09（华为平板 / API 31）**：验证时设备**锁屏休眠且无 root**，既不能驱动 UI 也不能注入 prefs
   ⇒ 该设备上的界面动效、背景级波形与帧时间**未验证**。
2. **PCL110 的帧时间未重测**：本轮 PCL110 只做了观感与降级行为验证（截图 + prefs 回读），
   `dumpsys gfxinfo` 的 30 秒稳态**没有在最终产物上重跑**。
3. **S6 的 P99 抖动**：单轮 150ms 与 v2.8.0 自己的对照轮（`frame-s6-visualizer-off` 的 150ms）同量级，
   但**没有**做多轮取中位数 ⇒ 该数字只作为「不比 v2.8.0 更差」的方向性证据，不作为精确结论。
4. **Perfetto 归因未做**：降级判据仍是保守单向阀（v2.8.0 的同一结论）。
5. **音频线程增量未单独测**：只有"播放未受影响"的定性证据，没有 `atrace`/Perfetto 的定量数据。
6. **`CoverBlurCache` 的 LRU 行为无单测**：13 个模糊单测覆盖的是纯函数层；
   缓存的命中/淘汰只在真机上以「同一首歌不重复计算」的日志间接观察。
7. **低频节拍的主观准确度未验证**（见 §3.5）。

## 5. 版本决策（三源交叉校验）

`bash tools/next-version.sh`（fetch 后执行）：

| 来源 | 值 |
|---|---|
| ① 最近 5 个 tag 指向的 `app/build.gradle.kts` | v2.8.0-gpl=51 / v2.6.2-gpl=50 / v2.6.1-gpl=49 / v2.6.0-gpl=48 / v2.5.6-gpl=47 |
| ② 已发布 APK 的 `aapt2 dump badging` | `Ncrust-v2.6.2-gpl-release.apk` = 50（v2.8.0 的 release 仍是 draft，其 APK 在仓库外 `dist/`，脚本第二源看不到它） |
| ③ 工作区 | 51 / `2.8.0-gpl` |

三源最大值 = 51 ⇒ **versionCode = 52**。产物 `aapt2 dump badging` 实测 `versionCode='52' versionName='2.9.0-gpl'`。
