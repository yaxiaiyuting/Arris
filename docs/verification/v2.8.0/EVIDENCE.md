# v2.8.0-gpl 证据索引（EVIDENCE）

> 本文件是 v2.8.0 的**证据目录**：每条结论指向可复现的命令或仓库内的原始文件。
> 与本轮环境有关的异常事实（工作区被清空、签名密钥丢失）在 §0/§1 显式记录，不隐藏。

## 0. 本轮环境事实（与代码无关，但影响可复现性）

| 事实 | 证据 |
|---|---|
| 接手时**工作区被完全清空**：仓库、`tools/`、`dist/`、同级 `Kanesumi-sec-a`、`keystore.properties` 全部不存在 | `ls -la /home/duanjb666/deepseek` = 空目录；全盘 `find` 无 `TransparentWaveformSink*` / `next-version.sh` |
| 仓库从 GitHub 重新克隆（公开可读） | `git clone https://github.com/yaxiaiyuting/Ncrust` → HEAD `4b0a124`（v2.6.2-gpl），`git rev-parse HEAD origin/master` 一致 |
| 组合构建依赖 `Kanesumi-sec-a` 必须与仓库同级 | `settings.gradle.kts` 的 `includeBuild("../Kanesumi-sec-a")`；已克隆到 `/home/duanjb666/deepseek/Kanesumi-sec-a` |
| 沙箱写权限只在工作区内，且 Java 的 `user.home` 忽略 `$HOME` | 构建统一走 `/home/duanjb666/deepseek/gw.sh`（设 `GRADLE_USER_HOME`、`-Duser.home=`、SDK 路径） |
| `tools/next-version.sh` 丢失 | 按 AGENTS.md 规范**重建**为 `/home/duanjb666/deepseek/tools/next-version.sh`（三源交叉校验 + 红线 19：只信 APK badging） |

## 1. ⚠️ 签名密钥更换（本版最重要的用户可见后果）

| 项 | 值 |
|---|---|
| 旧密钥 | `ncrust-release.jks` —— **永久丢失**（从不入库：`.gitignore:18-20` 排除 `*.jks`/`keystore.properties`；全盘与全部 git 历史均无） |
| 新密钥 | `ncrust-release-v2.jks`（用户本轮生成），alias `ncrust`，RSA 4096，有效期 10000 天，`CN=Ncrust, OU=Dev, O=Ncrust, L=changji, ST=xinjiang, C=CN` |
| 指纹 | SHA-256 `E6:2E:CA:39:B7:E5:E3:6B:C9:A0:5F:83:68:8D:CF:EC:C1:1F:C5:58:9A:40:97:F7:DB:BA:AA:7D:DF:BB:EC:82`；SHA-1 `7C:BD:4B:29:C9:FB:83:9C:C7:44:8E:06:60:53:D5:29:A3:05:BA:A0` |
| 产物实测 | `apksigner verify --print-certs` 输出与上表一致（`docs/verification/v2.8.0/verification/` 会话记录） |
| **后果** | 本版 APK 与所有历史版本**签名不同** ⇒ **老用户无法覆盖安装**，必须先完全卸载（会清掉登录态与离线缓存）。已写入 release note 与 `app/build.gradle.kts` 的版本注释 |

## 2. 版本决策（红线 8 / 9 / 19）

`tools/next-version.sh`（重建版，**先 fetch**）三源交叉校验 → `docs/verification/v2.8.0/verification/next-version.txt`：

| 源 | 值 |
|---|---|
| ① 最近 5 个 tag 的 `app/build.gradle.kts` | 最大 **50**（v2.6.2-gpl） |
| ② **已发布 APK** `aapt2 dump badging`（重新下载 `Ncrust-v2.6.2-gpl-release.apk`） | **50** |
| ③ 工作区 | **50** |
| ⇒ | max 50 ⇒ 本版 **versionCode 51 / versionName `2.8.0-gpl`** |

v2.7.x 从未存在（tag 与 release 列表均无）。

## 3. 探针阶段（红线 2：先探针后改代码）

| 文件 | 一句话结论 |
|---|---|
| `docs/verification/v2.8.0/probe-waveform.md` | 现状是纯 Compose Canvas 自绘（每帧 28×`drawRect`、0 分配、0 重组）；**异常隔离只做了一半**（`onBar` 在 `runCatching` 之外）；「关掉开关 = 零开销」不成立；`BARS_PER_SECOND=30` 是死常量；颜色只有 `accent_source=COVER` 时才是 HCT 派生 |
| `probe-waveform-tier.md` | A/B/C 共 13 个效果的落点与每帧增量（代码推导，标注【估算】）；`Brush.infiniteLinearGradient` **在 Compose 中不存在**；全仓 0 处 FFT ⇒ 多频段分色是「新增数据源」；渐变 × 多频段**硬互斥** |
| `probe-settings-inventory.md` | 16 个 prefs 文件 / 82 个静态键 / 42 项设置口径；2 处真版本迁移 + 1 处脏值防御；10 条依赖；7 分组映射加总校验 |
| `probe-settings-structure.md` | 7 分组建议；卡片落地所需现成件与新增件；导航方案①接入既有 NavGraph；registry 数据模型与五阶段计划 |
| `PROBE-SUMMARY.md` | 逐条回答任务书四个必答问题，并记入本轮环境事实 |

> 探针阶段**没有任何帧时间实测**（当时沙箱内 `/dev/bus/usb`、`/dev/kvm` 均不存在）——
> 探针文档如实写了这一点，只给静态成本代理与测量方案；真机数据在本文件 §6。

## 4. P1-A 波形效果分级：实现与自证

| 内容 | 位置 |
|---|---|
| 实现说明（交付侧） | `docs/verification/v2.8.0/P1A-waveform-tier-implementation.md`（commit `d9b1c27`） |
| 异常隔离修复 | `player/TransparentWaveformSink.kt:132`（RMS + `onBar` 同一 try；失败丢一根柱，不补 0；`droppedBarCount` 诊断） |
| 开关前置（音频线程不读盘） | 同上 `:135`；`enabled` 是 `@Volatile` 进程内镜像 |
| 三档纯逻辑 | `ui/player/waveform/VisualizerTier.kt`（`defaultTier` :78、`VisualizerEffects.of` :192） |
| prefs + 幂等迁移 | `ui/player/waveform/VisualizerPrefs.kt`（8 个键 :59 起、`migrate` :175、纯函数 `applyAutoDowngrade` :189） |
| C 档状态机（节拍/涟漪/粒子定长池） | `ui/player/waveform/WaveformEffectsState.kt` |
| 帧时间有界降级 | `ui/player/waveform/FrameBudgetPolicy.kt`（60 帧滑窗、≥24 帧超 16.67 ms 触发）+ `VisualizerFrameMonitor.kt`（注册/回调/注销三处隔离） |
| 未实现（明确决策） | 真 FFT/多频段分色（不画任何会被读成频谱的东西，`spectrumColoring` 恒 false 有单测守）；拖拽手势降级为「点按切换着色」（默认关 ⇒ 零新增命中面） |

新键（字段名显式声明，红线 17）：`visualizer_tier`(Int 0/1/2，缺键时按设备解析默认)、`visualizer_showcase`、`visualizer_shockwave`、`visualizer_particles`、`visualizer_perspective`、`visualizer_drag`（均 Bool，默认 false）、`visualizer_auto_downgraded`(Bool，诊断/有界标记)、`visualizer_tier_version`(Int，迁移水位)。

## 5. P1-B 设置界面二级菜单：实现与自证

| 内容 | 位置 |
|---|---|
| 交付说明 | `docs/verification/v2.8.0/P1B-settings-ui-implementation.md`（commit `b3942db`） |
| 注册表（纯数据，无写盘能力） | `ui/settings/SettingsRegistry.kt`（7 组 / 55 条 = 49 键 + 6 行为行） |
| 依赖门控（纯函数） | `ui/settings/SettingsVisibility.kt`（TTML 硬依赖、渐变扫过软依赖、C 档四项） |
| 渲染计划（页面与单测同一函数） | `ui/settings/SettingsRenderPlan.kt`（`cardGroups` / `rowsOf` / `rowKindOf`） |
| 路由 | `ui/navigation/NavGraph.kt`（`settings/{group}`；未知 group 安全回落，不抛异常） |
| 一级页（1566 → 108 行） | `ui/screen/UserScreen.kt`（7 张分组卡片） |
| 二级页 | `ui/screen/SettingsGroupScreen.kt`、账号组 `ui/screen/SettingsAccountSection.kt` |
| 行组件 | `ui/components/SettingsRows.kt`（原 `UserScreen` 私有组件原样搬出） |
| 卡片 | `ui/components/SettingsGroupCard.kt`（图标 + 标题 + 副标题 + 箭头；直角；主题色取 `LocalMetroColors`） |

「不丢项 / 不改语义 / 不改默认值 / 不引入无关功能」由单测机械强制（`app/src/test/.../settings/`）：
`SettingsRegistryTest`（22）、`SettingsDefaultsTest`（10）、`SettingsVisibilityTest`（17）、`SettingsRenderPlanTest`（10）、`SettingsGroupRouteTest`（5）、`WaveformSettingsGatingTest`（9）。
其中 `legacyPrefKeysArePreservedExactly` 是**双向等值**（既挡丢项，也挡夹带音理功能项）。

## 6. 真机验证矩阵

| 项 | S6（Android 7.0 / API 24 / 2.7 GB） | PCL110（Android 16 / API 36 / 15.7 GB） | 平板 WGR-W09（Android 12 / API 31 / 7.9 GB） |
|---|---|---|---|
| 新设置界面一级页（7 卡片） | ✅ `s6-01-settings-level1.png` | ✅ `pcl-02-settings-level1.png` | ✅ 文本 dump 命中 7 组 |
| 二级页（播放与音质） | ✅ 含波形档位 + C 档门控 | ✅ `pcl-03-settings-playback.png` | ✅ 文本 dump |
| 波形档位默认值（平台 A/B） | **简洁**（低端判据：SDK<26）｜`s6-02-waveform-tier-default.png` + UI dump `enabled=false` | **精致** | — |
| 设置项迁移（老 prefs 原地升级） | ✅ 14 键**键集与取值全部逐字不变**（`pre-upgrade-*` vs `post-upgrade-*`） | ✅ 17 个 prefs 文件、22 键恢复 | ✅ 旧签名需重装（无 root，数据按约定放弃） |
| 默认档**不落盘** | ✅ root 读回：`ncrust_settings.xml` 无 `visualizer_tier` 键 | — | — |
| 波形各档效果 | ✅ 三档截图 + 帧时间（§7） | ✅ 精致档波形可见（圆角柱 + 封面派生色 + 柱顶光点） | ❌ 未测 |
| 帧时间 | ✅ §7 | ✅ §7 | ❌ 未测 |
| 自动降级 | ✅ 真机触发（`visualizer_auto_downgraded=true`） | — | — |

迁移实验的构造方式（如实说明）：S6 有 root，先把旧版 App 全部 16 个 prefs 文件 tar 备份到**仓库外**
（`/home/duanjb666/deepseek/.scratch/device-backup/`，含登录 cookie，**不入库**），
`pm uninstall`（换签名必须）+ 安装新构建后，用 root 把 `shared_prefs` 写回并 `chown`/`restorecon`，
再冷启动 ⇒ 得到「老用户升级」的等价状态。PCL110 同样处理（KernelSU）。

## 7. 帧时间（release 包，详见 `verification/frame-time-verification.md`）

| 设备 | 配置 | P50 | P90 | P99 | 帧数/30s |
|---|---|---|---|---|---|
| S6 | 基线 v2.6.2（旧波形，同法实测） | 15 ms | 25 ms | 31 ms | 944 |
| S6 | v2.8.0 简洁（低端机默认档） | 20 ms | 27 ms | 46 ms | 963–970 |
| S6 | v2.8.0 精致 | 22–23 ms | 29–31 ms | 46–53 ms | 933–962 |
| S6 | v2.8.0 炫技（C 档全开） | 21 ms | 29 ms | 42 ms | 945 |
| PCL110 | v2.8.0 精致（默认档） | **7 ms** | **9 ms** | **14 ms** | **1821（60 fps）** |

**结论（不美化）**：在 S6 上本版比 v2.6.2 的旧波形**更贵**（P50 +5…8 ms、P90 +2…6 ms、P99 +11…22 ms），
且自动降级在 S6 上几乎必然触发（P50 本就 ≈20 ms）⇒ 用户选的精致/炫技会被降回简洁，T1/T2 的实测值应读作上界。
在 PCL110 上三档都远在预算内（P90 9 ms、janky 0.27%、满帧）。**没有出现 30 fps 锁死、ANR 或掉帧级联**。

## 8. 构建与测试

见 `verification/build-and-test.txt`（`clean testDebugUnitTest lint assembleDebug assembleRelease` 的原始输出）。
测试基线数（含本轮新增）：

| 项 | 值 |
|---|---|
| 单测 | 122 个测试类 / 1687 用例 / 0 失败（本轮新增约 158 条：波形 85 + 设置 49 + i18n 24 等） |
| lint | 走仓库既有基线机制（`app/lint-baseline.xml`）；**新增文件不在基线内**，其新问题会导致失败 |
| debug / release APK | 均 `BUILD SUCCESSFUL`；release 走 R8 + 新密钥签名 |

## 9. 未验证 / 遗留风险（全部如实列出）

1. **S6 上更贵**（§7）：低端机默认档 + 自动降级是缓解措施，但「精致/炫技在 S6 上基本无法保持」未经产品决策确认；
   自动降级阈值（60 帧内 ≥24 帧超预算）是否过严，需要真机长时间使用才能判断。
2. **平板 WGR-W09 未做波形验证**（无 root，装新签名包必须卸载重装；本轮只验证了设置界面与迁移）；
   PCL110 只测了默认档（精致）的帧时间，T0/T2 未测。
3. **音频线程侧开销未测**（`handleBuffer` 真实回调率、RMS 逐样本成本）——三段探针都标为未确认。
4. **未做 bench harness**：`benchmark/` 的 macrobenchmark 不覆盖可视化稳态；低噪声档位差值需要新增 `WaveformBenchmark`（API 29+）。
5. **触控/动效手感未验**：卡片高度、二级页转场、返回后一级页滚动位置、播放器死带下二级页底部行可点性（PCL110 上目视正常，未做触摸矩阵）。
6. **`offline_cache_mb` 现在有两个入口**（存储页下拉 + 离线缓存弹窗内原选择器）：读写都走 `OfflineAudioCache.maxMb/setMaxMb`，
   弹窗关闭后本页重读一次；这是为满足「每个非 internal 条目被渲染恰好一次」的唯一 UI 新增。
7. **`rowTitle/rowSubtitle` 是 registry `titleKey` 的第二份显式映射**（Compose 侧不能反射取、R8 会改名），
   两份额的数值一致性目前靠人工对齐，没有机械断言。
8. **圆角半径未收敛进 `AppShapes`**（`drawRoundRect` + 硬编码 `CornerRadius`，不被 `AppShapesSingleSourceTest` 拦截）；
   `PlaybackService.LOW_RAM_TOTAL_BYTES` 存在量级 bug（`3_500L*1024³` = 3.5 TiB，本版未修，波形侧取的是它想表达的 3.5 GiB）。
9. **i18n 组预算**：`SettingsStrings` 已 78/80（预警线），下一批设置文案必须先拆组。
