# EVIDENCE · v2.5.0

> 本文件是 v2.5.0 全部证据的**索引**。每条都指向可复现的命令或落盘文件，
> 不含任何未跑过的结论。凡是**没验证到**的，统一收在最后一节。

## 0. 一句话

`clean testDebugUnitTest lint assembleDebug assembleRelease` **全绿**
（79 类 / **1068** 单测 0 失败、lint **0 Error / 0 Fatal**），
四台设备（S6 Android 7.0 / PLC110 Android 16 / WGR-W09 平板 Android 12 / 模拟器 Android 7.0）
冷启动 **0 崩溃**，并在 **PLC110 上真机验证了「添加到下一首播放」的五条边界**。

## 1. 构建与测试

| 项 | 值 | 落盘 |
|---|---|---|
| 构建 | `BUILD SUCCESSFUL in 3m 3s`（421 tasks） | [`verification/build-summary.txt`](verification/build-summary.txt) |
| 单测 | **79 类 / 1068 用例 / 0 失败 / 0 错误 / 0 跳过** | 同上 |
| lint | **0 Fatal / 0 Error** / 8 Warning（全部是既有的 `ApplySharedPref`）/ 2 Information | 同上 + `app/build/reports/lint-results-debug.{xml,txt,html}` |
| debug APK | 31,044,436 B，`versionCode=41 versionName=2.5.0-gpl` | `app/build/outputs/apk/debug/app-debug.apk` |
| release APK | 10,042,824 B，`versionCode=41 versionName=2.5.0-gpl`，已签名 | `app/build/outputs/apk/release/app-release.apk` |
| 签名者 | `CN=Ncrust GPL Fork, OU=Personal, O=yaxiaiyuting, C=CN` | 同上 |

**单测增量**：进场基线 68 类 / 937 用例 → 79 类 / **1068** 用例（+11 类 / +131）。
新增的 11 类里 **4 类是本版为「让约定变红」而专门写的守卫**（见 §4）。

## 2. 版本三源交叉校验（铁律 9 / 铁律 4）

命令：`bash tools/next-version.sh`（**带 `git fetch --tags --prune`**）。完整输出见
[`verification/version-check.txt`](verification/version-check.txt)。

| 源 | 值 |
|---|---|
| ① 最近 tag 指向的 `build.gradle.kts` | `v2.4.0-gpl` → **40** |
| ② `dist/*.apk` 的 `aapt2 dump badging`（唯一可信的「已发布出去」来源） | `Ncrust-v2.4.0-gpl-{debug,release}.apk` → **40** |
| ③ 仓库当前 `build.gradle.kts`（**定号前**） | **40** |
| **三源 max** | **40** ⇒ 本版 **41** |
| 本版两个产物的 badging | debug **41** / release **41** ✅ 一致 |

> 文件里那次脚本复跑是在**改完 build.gradle 之后**，所以它报 MAX=41 ——
> 该文件末尾已写明读法，避免把复跑结果误当成定号依据。

## 3. 真机 / 模拟器验证

完整输出：[`verification/device-smoke.txt`](verification/device-smoke.txt)

| 设备 | 型号 | 系统 | 装了哪个包 | 冷启动 | 应用进程崩溃 |
|---|---|---|---|---|---|
| S6 | SM-G9209 | Android 7.0 (API 24) | **debug** | 1223 ms | **0** |
| PLC110 | PLC110 | Android 16 (API 36) | **debug** | 1711 ms | **0** |
| WGR-W09 平板 | WGR-W09 | Android 12 (API 31) | **release**（R8） | 358 ms | **0** |
| 模拟器 | sdk_google_phone_x86_64 | Android 7.0 (API 24) | **debug** | 693 ms | **0** |

> ⚠️ 崩溃计数**按进程名过滤**（`FATAL EXCEPTION` 后 2 行内出现包名）。
> 未过滤时 PLC110 会多出 2 行 —— 那是 **`uiautomator` 工具自身**在 ColorOS 16 上
> `UiAutomationService already registered` 的崩溃，与应用无关。这一条在
> `device-smoke.txt` 里也写明了，避免下一个读的人误判。

### 3.1 「添加到下一首播放」真机端到端（**PLC110**，1068 首队列，`play_mode=2` 随机）

这是本版最硬的一条证据：**在随机模式下、真实 1068 首队列上，功能确实生效**。

| 步骤 | 观测 | 结论 |
|---|---|---|
| 初始 | `queue_index=138`（Beethoven Virus），`queue[139]`=Sweet Dreams，`play_mode=2` | 随机模式 |
| 长按「最后一页」→ 点「添加到下一首播放」 | Snackbar = **「已移到下一首」** | `MOVED_TO_NEXT` 分支正确 |
| 读回落盘队列 | `queue_index 138→137`；`queue[137]`=Beethoven Virus（**当前歌没变**）；`queue[138]`=**最后一页**；`queue[139]`=Sweet Dreams | ① 当前歌不变 ② 插入项落在 `currentIndex+1` ③ 原下一首被顶后 ④ 无重复 ⑤ 长度不变（搬移非新增） |
| **`queue_index` 从 138 → 137** | 被搬的歌原本在下标 2（在 138 **之前**），删掉后当前歌前移一位 | **这就是「去重后必须重新定位下标」那条不变量** —— 朴素的 `.filter` + 沿用旧下标会让 `queue_index=138` 指向「最后一页」，即在播歌指针直接指错 |
| 点应用内「下一首」 | `song_name` → **最后一页**（id 247936），`queue_index` → 138 | **随机模式下真的下一首播它**（只改线性队列不改编排序列时，这里会播原来那首随机歌） |

**待播槽位重同步（本版修的第三个缺陷）** —— 这是最隐蔽、也是 v1.5.2「串台」的同一形状：

```
20:07:36.878 D/PlaybackService: onStartCommand action=preload_next
20:07:36.879 D/PlaybackService: preload_next replaces stale slot songId=1483497966 -> 1965392316
20:07:36.886 D/PlaybackService: Queued next: Doja … ncrustkey=song:1965392316:jymaster count=2
```

加入 Doja 之前，槽位里预载的是**旧的**下一首（EVA-02, 1483497966）。
**没有这次重同步，ExoPlayer 播完当前曲会直接播 EVA-02，而队列面板显示下一首是 Doja** ——
即用户报告的「显示加了 A、耳朵里是 B」。日志证明槽位被 **REPLACE** 成 Doja，
且 `count=2` 说明「当前项之后至多一首预载项」的不变量仍然成立。

### 3.2 S6（Android 7.0 / API 24，**目标下限设备**）上抓到并修掉的真实回归

**这是本版唯一一个只有真机能暴露的问题**，记在这里因为它最容易被漏掉：

- **现象**：歌曲菜单加到第 **10** 条时，在 S6 竖屏（1440×2560 = 411×731dp）下
  弹层总高 ≈752dp > 731dp，最后一条「单曲信息」被挤出屏幕；
  而 `MetroBottomSheet` 的内容是**不滚动**的 Column ——
  实测在其上做上滑手势，所有条目坐标**完全不变**，也就是那一条**点不到**。
- **归因**：本版新增「添加到下一首播放」把它从 9 条推到 10 条。9 条时刚好放得下
  （`转到专辑` 结束于 y=2394 < 2560），10 条时最后一条落到 y=2543–2618。
- **修法**（app 侧，不动 Kanesumi）：`ui/components/SongMenuSheet.kt` 给操作列表加
  `heightIn(max = 屏幕高 − 176dp)` + `verticalScroll`。
  用 `heightIn(max=)` 而不是固定高度 —— 内容本来就放得下时（平板/横屏/条目少）
  **行为与改动前逐字节一致**。
- **验证**：修后竖屏下弹层改为底部对齐，从 `加入库[224,843]` 滚到
  `单曲信息[224,2411][468,2486]`，**10 条全部可达**。
  截图：[`screenshots/v250-s6-menu-before-scrollfix.png`](screenshots/v250-s6-menu-before-scrollfix.png)
  （修前，只有 4 条可见）vs [`screenshots/v250-s6-menu-scrolled.png`](screenshots/v250-s6-menu-scrolled.png)（修后滚动到位）。

### 3.3 视觉证据（截图）

| 文件 | 证明了什么 |
|---|---|
| [`screenshots/v250-pcl110-player-bigcover.png`](screenshots/v250-pcl110-player-bigcover.png) | 播放页大封面 **16dp 圆角 + 1dp 描边**，且**没有被裁在屏幕左上角**（探针警告过的那条实现约束） |
| [`screenshots/v250-pcl110-library-coverframe.png`](screenshots/v250-pcl110-library-coverframe.png) | 列表小封面 **8dp 圆角 + 描边**；mini bar 封面同样有圆角 |
| [`screenshots/v250-pcl110-songmenu-addnext.png`](screenshots/v250-pcl110-songmenu-addnext.png) | 歌曲菜单里的**「添加到下一首播放」**（图标 `QueuePlayNext`，位置在「移除收藏」与「转到歌手」之间）；菜单头封面圆角+描边 |
| [`screenshots/v250-pcl110-snackbar-moved.png`](screenshots/v250-pcl110-snackbar-moved.png) | 应用级 **Snackbar** 药丸形提示「已移到下一首」 |
| [`screenshots/v250-pcl110-cover-theme-player.png`](screenshots/v250-pcl110-cover-theme-player.png) | **播放页背景与强调色跟随封面**（背景 `#201A18`、进度条/控件 `#FFB5A0`，来自该封面的暖色调色板） |
| [`screenshots/v250-pcl110-preset-accent.png`](screenshots/v250-pcl110-preset-accent.png) | 同一台设备切回 `PRESET` 时：背景 `#000000`、强调色 = 琥珀预设 `#F59E0B` —— **A/B 对照**，证明确实是取色在起作用而不是巧合 |
| [`screenshots/v250-s6-api24-wide-sidebar.png`](screenshots/v250-s6-api24-wide-sidebar.png) | **Android 7.0 上圆角/描边/圆形按钮全部正常渲染**（API 24 的 Compose 兼容性） |
| [`screenshots/v250-tablet-release.png`](screenshots/v250-tablet-release.png) | **release（R8 混淆）包**在平板上正常：宽屏侧栏、全部封面圆角+描边 |

> §3.3 的背景/强调色是**像素采样**得出的，不是肉眼判断：
> `background (20,600)` 在 PRESET 下 `#000000`、在 COVER 下 `#201A18`；
> `playAllBtn` 在 PRESET 下是琥珀 `#F59E0B`、在 COVER 下是封面色 `#FFB5A0`。

## 4. 把「约定」变成「会变红的测试」（本版的四条守卫）

约定写在文档里会被绕过 —— 本仓库的 41 处手写 `tween` 就是证据。所以本版新增的四条守卫：

| 测试 | 钉住的事实 | 用例数 |
|---|---|---|
| `ui/theme/AppShapesSingleSourceTest` | 圆角构造器**只能**出现在 `AppShapes.kt`；且**自检「确实扫到了文件」**，防止工作目录变化导致空扫描恒绿 | 3 |
| `ui/theme/AppMotionSpecTest` | ① 官方 M3 Expressive 六个弹簧逐值；② **播放器转场不得过冲**（`dampingRatio >= 1.0`）；③ `spring(` 只能来自 `AppMotion` | 11 |
| `ui/components/ListItemAppearTest` | 入场动效**必须有界**（阈值在 1..64，边界是半开区间） | 5 |
| `player/QueueInsertTest` | 铁律 16 的边界 + **乱序排列合法性（穷举 4! 全部排列）** | 23 |

## 5. 与任务书三处前提的偏差（**必读**）

铁律 2 要求「探针先行，不直接采信任务书中的初步判断」。实测有三处前提不成立：

| # | 任务书原文 | 实测 | 落盘 |
|---|---|---|---|
| 1 | 「参考 **SPlayer-Next**」的取色与圆角规范 | `SPlayer-Dev/SPlayer-Next` 是 **Electron + Vue 3 + Rust 桌面端**；浅克隆后 `find` 查 `*.gradle*`/`AndroidManifest.xml`/`*.kt`/`pubspec.yaml` **全空**。「50×50 采样」「`Score` 评分」只存在于其 `color.ts` 的一句 **JSDoc 注释**（真实常量 `COVER_SAMPLE_SIZE = 64`，且该文件**从未 import `Score`**） | [`probe-splayer-ref.md`](probe-splayer-ref.md) §1/§2 |
| 2 | 「M3 Expressive 弹簧动效**四档**」 | 官方是「**三种速度** × **两种类型** = **六个弹簧**」；且**没有任何官方 spring→tween 对应值** | `probe-splayer-ref.md` §5、[`probe-motion.md`](probe-motion.md) P3 |
| 3 | 用 `player.addMediaItem(index, mediaItem)` 实现「添加到下一首」 | 本工程队列在**应用层**，ExoPlayer 列表**不是队列**；照做会往列表塞第三项，**直接破坏 v1.5.2 的待播槽位不变量** | [`probe-queue.md`](probe-queue.md) P2 |

**另有一处数值错误被独立复核证伪**：任务书隐含的 `Hct.fromInt(0xFF4285F4)` 校验值
（hue 220 / chroma 68–72 / tone 53）**是错的**；实测 **265.98 / 62.27 / 56.55**
（220 是它的 HSV 色相 217.5，两个色彩空间的色相本来就不相等）。
单测断言**实测值**，不迁就错误 ballpark。三方复核（官方 Kotlin 移植逐位相同 /
官方 TS `hct_test.ts` 锚点全中 / PyPI 包一致到 1e-5）见 [`probe-theme.md`](probe-theme.md) §2.5。

## 6. 未验证缺口清单（**如实列出，不做美化**）

### 6.1 有意不做（不是遗漏，理由在探针里）

| 项 | 理由 |
|---|---|
| **页面切换转场** | `NavGraph.kt` 四个转场被**显式**关掉，注释写明是**用户决策**、理由是低端机掉帧。任务书未给新证据。铁律 15 + 目标下限 Android 7.0/3GB 支持现状 → 交回产品决策，代码留了 `AppMotion.PAGE_TRANSITION_MS` 与取证要求 |
| 把 **41 处存量手写 `tween`** 批量改走 `AppMotion` | 逐处判断「空间类/效果类」要读 41 处上下文，改错会让手感变化且**无法归因**；其中 `PlayerCard.kt`/`NcrustLyricsPanel.kt` 被 `AGENTS.md` 标注 load-bearing、禁止批量重构 |
| 5 处既有 `animateFloatAsState` | 同上；集中在承重文件里 |
| 改 Kanesumi 库（**用户裁定**） | 会让发布产物依赖另一个仓库的未发布提交，破坏「`git clone` 即可复现」。代价：库内部的弹窗/按钮背板**仍是直角** |

### 6.2 未验证到的（需要更多证据才能声称）

1. **灰度封面降级只有单测，没有真机 A/B**。`CoverPaletteExtractorTest` 覆盖了
   纯灰度→null、空输入→null、确定性、单像素不抛异常；但**没有在真机上找一首灰色封面的歌**走一遍。
   因此「灰度封面回退到默认主题」这条验收项的证据强度是**单测级**，不是真机级。
2. **mini bar 封面的圆角/描边是等比缩小的**（16dp→约 2.5dp、1dp→约 0.16dp），
   在 56dp 缩略图上**基本看不见描边**。这是有意接受的（见 `PlayerCard.StableCover` 的 KDoc），
   但**没有做「不缩放」的对比实现来量化差异**。
3. **1dp 描边的实际对比度未逐背景比对**。`outlineVariant` 深色 `#2A2A2A` / 浅色 `#E2DACB`，
   与封面图像本身的对比取决于封面，本轮只做了目视确认。
4. **通知栏封面未做任何改动**（规则要求不动），但**SystemUI 是否自行加圆角未确认** ——
   需要系统 UI 的截图比对，本轮没做。
5. **`jp_MY` / `ko_NK` 的 6 条新文案没有母语校对**，只按各自既有风格翻译。
6. **队列去重仍用裸 `song.id`**。跨源裸 id 撞号（ncm 某首 vs QQ 某首）时会把其中一首当重复。
   这是**既有**风险、本版未引入也未加剧（全部既有队列写入都是这把尺子），
   但本版也**没有**修它 —— `PlayerViewModel` 另有更严格的 `TrackKey`，队列去重从未用过。
7. **`QueueModes.SINGLE` 下「添加到下一首播放」的用户预期未做真人验证**。
   本版决定「只插队列、不动槽位」（否则等于静默把单曲循环改成顺序播放），
   并在文档里写明；但「用户点这个按钮时到底想看到什么」是产品问题。
8. **单曲循环 / 顺序线性（LINE）/ 无限（INFINITY）三种模式的真机验证没做** ——
    PLC110 上那一轮是 `play_mode=2`（随机）。这三种模式的边界只有**单测**证据
   （`QueueInsertTest` 覆盖了 `nextIndexAfterInsert` 与 `shouldPreloadAfterInsert` 的各模式分支）。
9. **动效流畅度没有量化**。任务书 §7.2 要求「动效流畅度验证」，
   本版做的是目视确认 + 「有界性」的单元级保证（只对前 12 项播放、播放器转场不过冲），
   **没有跑 `dumpsys gfxinfo` / macrobenchmark 做掉帧对照**。
   `benchmark/` 模块存在，但本轮没有跑。这是本节最实质的一个缺口。
10. **`Strings` 主构造器余量只剩 1 个槽位**（244/245）。
    下一个要加文案的人必须先腾位置 —— 这一条已写进 `AGENTS.md` 与代码 KDoc，
    但**没有新增一条会变红的测试**来强制它（既有的 `StringsConstructorBudgetTest` 只断言 `<= 245`）。
11. `ui/theme/CoverTheme.kt` 的 `toNcrustColors` **有意不覆盖**
    `surfaceContainerHigh` / `surfaceContainerHighest`（菜单与输入框底色，可读性影响面大）。
    这个取舍**没有做「覆盖后会怎样」的对照实验**。
