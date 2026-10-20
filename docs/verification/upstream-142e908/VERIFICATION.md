# Arris 上游 142e908 移植集成验证（v3.4.12-gpl / versionCode 74）

本轮把上游 `e35ddf3..142e908` 的剩余改动按功能拆成 7 个提交整合进
`sync/ncrust-142e908`，并完成 QQ 续期修复的独立提交。**未改版本号、未
push/tag/release、未安装任何未知 APK、未使用真实账号做云写。**

- 集成基线：`fd94bd3`（`fix: 网易扫码登录切换 PC eapi 并校验新会话`）
- 最终源码 HEAD：`0c455ac`（证据文档提交不改变 app 源码）
- 设备：`emulator-5554`，API 33，`sdk_gphone64_x86_64`；**不是真机**
- 构建 JDK：`/usr/lib/jvm/java-21-openjdk`（不是系统默认 Java 27）

## 1. 提交历史（功能与适配说明的权威位置）

| 提交 | 内容 |
|---|---|
| `34215f6` | `fix(qq)`：续期响应 snake/camel 解析、loginType=0、取链前刷新、auth 锁/revision 提交闸门、迟到成功/失败/终态/冷却防污染、读路径不删新凭证、取消传播 |
| `28a31cd` | `feat(player)`：PlaybackQueueLogic（TrackKey 回查、乱序轮末不回绕、ENDED/IDLE 重启）与 AudioOffloadGate（PCM 需求 → offload 判定）纯逻辑 |
| `f76111a` | `feat(account)`：权益判定/会话监控/VIP 四态/试听徽标与 45 秒门控；301 上报；取链响应解析；账号页登录/退出接线 |
| `b1659e0` | `feat(update)`：yaxiaiyuting/Arris、`-gpl` tag、debug/release 资产、包名/versionCode/签名校验、断点续传、设置项与入口 |
| `e273f19` | `feat(library)`：收藏整列播放、clientlog eapi + osx 上报、矮屏高度档/封面几何/生命周期收集、gapless 默认 false |
| `93ba9d4` | `feat(player)`：把队列、offload 运行中 snapshotFlow、暂停 ticker、权益/上报门控、车机试听标记接到实际播放链路 |
| `bf06b02` | `feat(settings)`：AccountStrings 24 条 + Strings.update 28 条八语合并、删除 shim、设置键注册/计数/渲染同步 |
| `0c455ac` | `fix(player)`：AudioOffloadGate 对 media3 `UnstableApi` 显式 opt-in（未放宽 lint） |

## 2. 功能矩阵

| 功能 | 实现要点 | JVM 覆盖 | 设备 smoke |
|---|---|---|---|
| QQ 续期 | snake/camel、loginType=0、成功后 merge、显式 loginType=0、取链前 refreshIfNeeded、提交闸门/冷却 revision、过期/限流/传输失败分类、取消重抛、sendCookie=false；`QqApi` / `QqClient` / `QqMusicSourceProvider` 调用层已随全量 build 编译 | `QqRefreshCredentialTest` 32 + `QqRefreshCommitGuardTest` 7；隔离 harness 73 例、7 个竞态场景 | 未用真实账号；启动不崩 |
| 账号权益 | Full/Trial/Unavailable，8 档表复用，freeTrialInfo/privilege 解析，FLAC 门控 requestedLevel | `EntitlementFixtureTest` 3 + `PlaybackNoticeTrackerTest` 8 | 未登录状态 UI 已截图；真实会员/试听未测 |
| 会话/VIP | 启动/回前台≥30min/301 三路；失效清请求层 cookie、保留曾登录与本地收藏；账号页扫码/退出通知 SessionMonitor；VIP 四态 | 夹具 + 全量设置/快照测试 | 仅验证「未登录」态；Expired/Valid/VIP 未用真实账号 |
| 设置 | `vip_trial_playback`、`update_auto_check`、内部 `session_was_logged_in` 全部注册；内部 27 / 可见 47 / 库页 4 / 总 78 | `SettingsRegistryTest` / `SettingsRenderPlanTest` / `SettingsDefaultsTest` / `SettingsVisibilityTest` | 播放设置页可见试听策略、gapless「测试中」提示 |
| i18n | AccountStrings 24 + UpdateStrings 28，八语；主构造器 146→148；删除 `UpdateStrings.kt` shim、LocalUpdateStrings、白名单 | `StringsConstructorBudgetTest`（148）、`StringsMigrationTest`、标题路径真实解析 | 设置/关于/更新入口中文渲染正常 |
| 更新 | 固定 yaxiaiyuting/Arris、`vX-gpl`、debug/release 资产、包名→versionCode→签名、断点续传 marker、目标切换代次、Mutex、FileProvider 复用 | update 5 个测试类 47 例（ApkVerification 7 / AppVersion 8 / Downloader 12 / Fixture 2 / FlowRules 18） | 关于页「检查更新」入口可点；未下载真实 APK |
| 队列身份 | TrackKey 回查、删除重定位、SHUFFLE 轮末不回绕、AUTO/非 AUTO 过渡上报、ENDED 恢复 | `PlaybackQueueLogicTest` 14 | 匿名播放免费 ncm 曲目，展开/收起正常 |
| offload/动效 | snapshotFlow 监听 VisualizerSetting.state + MotionPrefs.effects.needsAudioFeatures；任一路开禁 offload、全关恢复 + gapless；onDestroy cancel | `AudioOffloadGateTest` 7 | 模拟器 `mp3 supported=false`，运行中切换路径无法在本设备观测 |
| 暂停 ticker | 仅 isPlaying 时 ticker；暂停补发位置；onPositionDiscontinuity 覆盖暂停 seek | 代码审查 + 播放 smoke | 暂停后位置停在 0:12，不空转 |
| 上报 | clientlog eapi + osx；startplay/80%/自然结束；试听三道 `!currentIsTrial`；车机 onTrialResolved；ReportGate 仍唯一跨源出口 | `PlayReporterTest`、`RetrofitClientOsxHeaderTest`、`ReportGateTest` | 未验证真实上报到达；匿名播放未做账号云写 |
| 收藏整列 | LikedPlayAll 顺序/tombstone/去重；全量分批；缓存先播后补；插播/追加 | `LikedPlayAllTest` 7 | 未用真实收藏数据 |
| 矮屏播放器 | HeightClass/CoverGeometry；Tiny 压缩控制栏；阈值隔离/生命周期收集 | `HeightClassTest` 7、`CoverGeometryTest` | 840×1400@420dpi（320dp×533dp）展开/收起/暂停截图通过 |
| 歌单写接口 | 与上游端点逐项核对，未替换 fork 既有实现 | 既有测试 | 未做真实写操作 |

## 3. 构建与静态检查

统一命令（Java 21）：

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk \
  ./gradlew :app:testDebugUnitTest lintDebug assembleDebug assembleRelease --console=plain
```

结果：**BUILD SUCCESSFUL**。完整日志 `/tmp/arris-validation/full-2.log`。

- 单元测试：**2934 tests，0 failures，0 errors，0 skipped**（`app/build/test-results/testDebugUnitTest/*.xml` 汇总）。
- lint：`lintDebug` 成功；首轮唯一的 `UnsafeOptInUsageError` 已用
  `androidx.annotation.OptIn(UnstableApi::class)` 修正，**没有**把检查加入
  baseline、没有放宽阈值。
- debug/release Kotlin 编译均通过；release R8 完成。

### 方法体预算（tools/method-size/method_size.py）

工具真实接口：`input` 为 APK/dex/目录，另有 `--mapping --top --limit --json
--baseline --only --quiet`（默认阈值 2000）。

对 release APK + 本次 mapping 运行：

- 全 APK：33958 个含代码方法，51 个 >2000、91 个 >1200；超阈值项均为
  R8/Compose/库的混淆方法（如 `v4.a.<clinit>`、`X4.g.<clinit>`），不是本轮新增业务代码。
- `--only com.takahashirinta.ncrust`：3414 个方法，**0 个 >2000**，仅 2 个 >1200：
  `PlaybackService.onStartCommand` 1558、通知构建 `PlaybackService.i()` 1264。
- `--only com.takahashirinta.ncrust.ui.screen|ui.player|update`：全部 ≤2000。
- **结论：本轮没有新增超过 2000 code unit 的 composable，因此没有为过测而拆分或放宽阈值。**
  报告 JSON：`/tmp/arris-validation/method-size-app.json`。

## 4. 产物与 mapping

| 产物 | 路径 | SHA-256 |
|---|---|---|
| debug APK | `app/build/outputs/apk/debug/app-debug.apk` | `dec82c0c513d7d52ed7ce4eebefe59a00c0a28087ebdf08cc4238752e47e0842` |
| release APK | `app/build/outputs/apk/release/app-release.apk` | `18829ac226ff350b521d5cddf5135ca531f12649f7be2eb46b228488246eac85` |
| release mapping（gzip） | `docs/verification/upstream-142e908/mapping-upstream-142e908.txt.gz` | `258731fa8c84fb3df5ce3ff11ad7063f15e95d209b597e38595698f358e8442a` |
| release mapping（原始） | `/tmp/arris-validation/mapping-upstream-142e908.txt` | `4016d0de81d4774bad405a107bc8255b63af9e769828466e4414557ed2f2fd06` |

- 发布前旧 mapping 备份：`/tmp/arris-validation/preexisting-mapping.txt`
  （75118937 B，任务开始时已存在）。
- 本次 release 后立即另存 gzip + sha256，并把原始 mapping 另留
  `/tmp/arris-validation/`；仓库内不再放 77 MB 原始文件。
- APK 包名/版本（`aapt2 dump badging`）：`com.takahashirinta.ncrust`，
  versionCode **74**，versionName **3.4.12-gpl**。
- 签名（`apksigner --print-certs`）：release 与设备已安装包同为
  `CN=Ncrust`，证书 SHA-256
  `e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82`；
  debug 为 Android Debug 证书（不同）。

## 5. 模拟器 smoke（API 33，非真机）

### 5.1 设备基线

开始时：

```
Physical size: 320x640
Override size: 1080x1920
Physical density: 160
Override density: 420
```

smoke 期间设为 `840x1400` + `420`（= 320dp 宽 × 533dp 高，覆盖矮屏档）。
结束后已恢复 **Override size 1080x1920 / Override density 420**，与开始时
逐值一致；未卸载、未清数据。

### 5.2 安装与启动

- 已安装旧包：versionCode 72 / 3.4.10-gpl，签名与 release 相同。
- `adb install -r app-release.apk` 成功，数据保留；安装后 versionCode 74。
- 冷启动到库页，`mCurrentFocus=.../MainActivity`；全流程 logcat 无
  `FATAL EXCEPTION`、无 `ANR in com.takahashirinta.ncrust`。
- 未登录（账号页显示「未登录」），因此全程没有真实账号云写。

### 5.3 实际操作

1. 用户页 → 账号与登录：确认「未登录」会话态；二维码/手机号入口存在。
2. 用户页 → 关于：可见「自动检查更新」「关于 Arris」。
3. 关于 Arris → 项目信息：「检查更新」入口可点；点击后显示「正在检查…」，
   约 50 秒后状态收尾。未出现更新横幅，也未进入更新详情页；GitHub 检查
   结果本身未在 UI 显示“已是最新/失败”，因此不能据此断言线上最新版本号。
4. 搜索 `Canon` → 只看 ncm → 播放免费曲目 `Canon / Various Artists`：
   展开播放器、暂停、收起 mini bar 均正常；播放位置 0:11→0:12。
5. 播放设置：可见「无缝播放 + 测试中，可能不稳定」「非会员遇到 VIP 歌曲 =
   播放试听片段」。
6. 过程中音频 offload 日志为
   `AudioOffload: mp3 supported=false (decision: gated by PCM feature demand)`：
   该模拟器不支持 offload，运行中门控在此设备上不可观测（JVM 覆盖判定）。

### 5.4 截图（仓库内）

| 文件 | 内容 |
|---|---|
| `screenshots/01-account-session-320dp.png` | 账号页会话状态「未登录」 |
| `screenshots/03-update-entry-320dp.png` | 关于页「检查更新」入口 |
| `screenshots/03b-update-checking-320dp.png` | 点击后「正在检查…」 |
| `screenshots/04-player-320dp-short.png` | 320dp×533dp 展开播放器（Canon，0:11） |
| `screenshots/06-settings-top-320dp.png` | 320dp 设置一级页 + mini bar |
| `screenshots/07-playback-settings-320dp.png` | 播放设置与 gapless beta note |
| `screenshots/09-playback-settings-trial-320dp.png` | 试听策略行（播放试听片段） |

## 6. 用户文档保护

`docs/verification/v3.2.2/probe/curve-smoothing-measurements.md` 在本轮开始
时记录并备份：

- 原始 SHA-256：`5683659e6620297ed0260af600d3b520055eadd359166c35e06d34577f2b4152`
- 原始 diff SHA-256：`3faef9ac2db9481bb794b67a5e86b7e1e99311d3c6977c83231eb868f1f02380`
- 备份：`/tmp/arris-validation/user-doc-original.md` / `.diff`

该文件始终**未暂存、未提交**。任务过程中检测到它被外部进程改写过一次
（1259/1253/1367/1122 → 836/939/941/893），已按备份恢复原字节；最终状态
仍为上述 hash 的 ` M`（unstaged）。

## 7. 已知验证边界（不夸大）

1. **没有真机**：所有设备结论来自 API 33 模拟器；音频 offload、真实
   AudioSink、车机/Android Auto 均未在真机验证。
2. **没有真实账号**：未测试一周/隔夜续期、真实 301、VIP 四态、试听 45 秒、
   真实云写、真实 QQ/B 站播放。账号页只验证了「未登录」。
3. **没有真实更新下载/安装**：GitHub 发布页端到端未跑；未下载或安装任何
   真实 APK；更新详情页因当前无更高版本未进入。
4. **上报未验达**：`PlayReporter` 的字段/顺序由 JVM 测试锁定，没有真实
   clientlog 回执；匿名播放未做账号云写。
5. **offload 运行中切换**：判定与监听有 JVM/代码证据，模拟器不支持 offload，
   未观测到实际音频路径切换；个别 ROM 切换可能有极短断点。
6. **Tiny 取舍**：<560dp 高时「大屏模式 / 自动旋转」入口不挂载，与上游
   取舍一致，属于已知功能缺口而非回归。
7. **QQ**：真实服务端是否显式下发 loginType=0、是否轮换 snake_case
   refresh_token、expired_at 是否非 0，仍需真机日志确认；`needRefreshKeyIn`
   未参与主动调度。
8. **设置审计**：本分支没有名为 `ReadAuditScope` 的符号；以
   `SettingsRegistryTest`（所有键注册、内部项清单、标题路径真实反射）作为
   等价严格检查，新增键无白名单。
