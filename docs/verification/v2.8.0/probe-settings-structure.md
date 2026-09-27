# v2.8.0 设置界面重构 · 结构探针（只读）

> 本文件是**只读代码**的产物：未运行 Gradle、未跑单测、未装机、**未看过任何截图**。
> 分组参考对象（「音理」）在本环境**没有截图**，本文只使用任务书给出的**文字分组名**。
> 所有「现状」均带 `file:line`；所有「怎么做」一律标 **建议**，不构成已实现事实。

## 0. 任务书与仓库实际的两处不一致（先纠正，避免照错文件施工）
| 任务书写法 | 仓库实际 |
|---|---|
| `ui/screen/PlayerLayoutSetting.kt` | **不存在**。实际是 [`ui/screen/PlaylistLayoutSetting.kt`](app/src/main/java/com/takahashirinta/ncrust/ui/screen/PlaylistLayoutSetting.kt)（歌单**布局模式**，非播放器） |
| `ui/navigation/NavRoutes.kt`、`MainNavGraph.kt` | **两个文件都不存在**。`NavRoutes` 与 `MainNavGraph` 同在 [`ui/navigation/NavGraph.kt`](app/src/main/java/com/takahashirinta/ncrust/ui/navigation/NavGraph.kt)（前者 :25，后者 :128） |

## 1. 现状：UserScreen 的分段与全部设置项
### 1.1 目前是「一个大 LazyColumn + 8 个 item 块」
| 段落 | item 起点 | 内容 |
|---|---|---|
| 页头 | `UserScreen.kt:318` | `MetroText(pageHeading)` |
| 账号 | `:335`（`ProfileBlock` :336）、`:355`（`QqAccountBlock` :356） | 网易云 / QQ 音乐 |
| 音质 | `:427` | 音质两行 |
| 播放 | `:463` | 播放 6 行 **+ 歌词 9 行混在同一块里**（:544–:630） |
| 外观 | `:635` | 主题模式 / 主题色 / 主题色来源 / 语言 |
| 后台运行 | `:707` | 跳系统「忽略电池优化」 |
| 背景图 | `:742` | 选图 / 移除 |
| 存储与缓存 | `:791` | 占用三项 + 清除 + 离线缓存入口 |
| 关于 | `:844` | 单行 |

可复用行组件全部是**私有**的：`SectionTitle` `:967`、`SettingSwitchRow` `:994`、`ThemeModeSelector` `:1034`、`CacheUsageLine` `:1087`、`MetroDropdownRow` `:1157`、`MetroLanguageDropdown` `:1369`（唯一非 private）、`ProfileBlock` `:872`、`QqAccountBlock` `:1439`。

### 1.2 落盘清单（`ncrust_settings`，除注明外）
| # | key | 类型 | 默认值 | 单一真相（读/写入口） | 设置页读 | 设置页行 |
|---|---|---|---|---|---|---|
| 1 | `wifi_quality` | Int | `3` | 裸 prefs（`UserScreen.kt:146` 读 / `:441` 写） | `:146` | `:432` |
| 2 | `mobile_quality` | Int | `1` | 裸 prefs（`:147` / `:455`） | `:147` | `:446` |
| 3 | `gapless_playback` | Bool | `true` | 裸 prefs（`:148` / `:473`）+ `PlayerViewModel.refreshGaplessSetting()` `:476` | `:148` | `:467` |
| 4 | `keep_screen_on` | Bool | `true` | `KeepScreenOnSetting`（KEY :33，DEFAULT :36） | `:155` | `:481` |
| 5 | `auto_rotate` | Bool | `true` | `RotationSetting`（KEY :39，DEFAULT :42） | `:151` | `:493` |
| 6 | `audio_visualizer` | Bool | `true` | `VisualizerSetting`（`AudioVisualizer.kt:38/40`） | `:153` | `:503` |
| 7 | `artist_reco_enabled` | Bool | `false` | `ArtistReco`（KEY :42，`isEnabled` 默认 false :72） | `:158` | `:514` |
| 8 | `page_transition_enabled` | Bool | `true` | `PageTransitionSetting`（KEY :71，DEFAULT :74）；**状态提升在 `MainActivity.kt:900`** | `:116` 入参 | `:526` |
| 9 | `lyrics_translation` | Bool | `true` | 裸 prefs（`:156` / `:538`）+ `setLyricsTranslation` `:539` | `:156` | `:533` |
| 10 | `lyrics_word_animation` | Int | `0`（渐变扫过） | `LyricsDisplayPrefs`（KEY :68；**读路径含一次性迁移** :133–:145） | `:161` | `:544` |
| 11 | `lyrics_sweep_quality` | Int | `0`（自动） | `LyricsDisplayPrefs`（KEY :77，默认 :152） | `:169` | `:555` |
| 12 | `lyrics_font_scale` | Float | `1.0f` | `LyricsDisplayPrefs`（KEY :74，`FONT_SCALE_DEFAULT` :118） | `:167` | `:565` |
| 13 | `lyrics_in_media_session` | Bool | `false` | 裸 prefs（`:164` / `:583`） | `:164` | `:577` |
| 14 | `lyrics_ttml_enabled` | Bool | `true` | `LyricsDisplayPrefs`（KEY :80，默认 :166） | `:172` | `:587` |
| 15 | `lyrics_ttml_first` | Bool | `true` | `LyricsDisplayPrefs`（KEY :83，默认 :173） | `:173` | `:599`（**条件挂载**：`if (lyricsTtmlEnabled)` :598） |
| 16 | `lyrics_romanization` | Bool | `false` | `LyricsDisplayPrefs`（KEY :91，默认 :187） | `:175` | `:610` |
| 17 | `lyrics_dynamic_font` | Bool | `false` | `LyricsDisplayPrefs`（KEY :98，默认 :195） | `:177` | `:622` |
| 18 | `theme_mode` | String | `SYSTEM` | `ThemeManager`（KEY :40） | `MainActivity` 持有 | `:637` |
| 19 | `theme_color_index` | Int | `0` | `ThemeManager`（KEY :39） | `MainActivity` 持有 | `:648` |
| 20 | `accent_source` | String | `PRESET` | `AccentSource.kt`（KEY :27，默认 :36） | `MainActivity` 持有 | `:659` |
| 21 | `language_code` | String | `"zh-CN"` | `LanguageManager`（KEY :26，默认 :30） | `:179` | `:690` |
| 22 | `custom_bg_enabled` | Bool | `false` | `BackgroundImageManager`（:39） | `:745` 经 `isActive` | `:742` |
| 23 | `offline_cache_mb` | Int | `512`（合法 `64..8192`） | `OfflineAudioCache`（:54/:55/:70/:86） | — | 入口 `:820`，选择器 `OfflineCacheOverlay.kt:203` |

**非 prefs 行（无 key，必须靠稳定 id 防丢项）**：账号块（`:336`、`:356`）、系统后台活动跳转（`:707`，`BackgroundActivity.requestIntent` :713）、清除缓存（`:796`→确认弹窗 `:1109`）、离线缓存管理（`:823`）、关于（`:848`→`MainActivity.kt:2076` `showAbout`）。

**存在但不属于设置页的两项**（在库页，**建议不迁移**）：`library_playlist_layout`（`PlaylistLayoutSetting.kt:90`，UI `LibraryScreen.kt:382`）、`library_section_collapsed_local/_netease/_qq`（`LibrarySectionFoldSetting.kt:47/50/53`，UI `LibraryScreen.kt:390`）——它们是**库页上下文**操作，移到设置页会让「切布局」多两次跳转。

## 2. 问题 1：分组建议（**建议**，7 个一级分组）
| # | 一级分组卡片 | 副标题（建议） | 二级页内容 | 音理对应 |
|---|---|---|---|---|
| 1 | 账号与登录 | 网易云 / QQ 音乐 | 账号块 1、2 + 调试诊断行（debug-only，`UserScreen.kt:371/396/556`） | 第三方平台登录（部分） |
| 2 | 通用 | 语言、后台运行、旋转 | 语言 `#21`、后台运行（`:707`）、自动旋转 `#5`、音乐人推荐 `#7` | 通用 |
| 3 | 外观与动效 | 主题、主题色、背景、转场 | 主题模式 `#18`、主题色 `#19`、主题色来源 `#20` + 系统强调色重读（`:672`）、页面切换动效 `#8`、背景图 `#22` | 主题设置·个性化·动效设置 |
| 4 | 播放与音质 | 音质档位、播放行为 | Wi-Fi `#1`、移动网络 `#2`、无缝播放 `#3`、禁止熄屏 `#4`、音频可视化 `#6` | 播放设置·音质设置 |
| 5 | 歌词 | 逐字、翻译、字号、TTML | `#9`–`#17`（9 项，含 `#15` 的条件挂载语义） | 歌词设置 |
| 6 | 存储与缓存 | 占用与清理 | 占用三项 + 清除缓存 + 离线缓存管理（`#23`） | 存储与缓存 |
| 7 | 关于 | 版本与许可 | 直达 `AboutScreen`（`MainActivity.kt:2078`） | 关于 |

**判断说明（建议）**：`#7 artist_reco_enabled` 现状在「播放」块（`:514`），但它不改变播放行为，只决定首页是否出现推荐卡；**建议**放「通用」。若要求「本次只重组、不做任何归属再判断」，则留在「播放与音质」——两种都可接受，但必须**二选一并写进提交说明**。**分组数 = 7**（含「关于」；若「关于」按现状继续做一级页底部的单行入口，则一级卡片为 6 张）。

### 2.1 音理分组中**不适用**的，及原因（均为「Ncrust 没有该功能」）
| 音理分组 | 结论 | 证据 |
|---|---|---|
| 网络设置 | **不适用** | 无任何网络配置项。`proxy` 的 3 处命中全是 CameraX `ImageProxy`（`QrScannerScreen.kt:10/156/268`）；`网络设置`/`metered`/`wifi_only` 命中 **0**；`RetrofitClient` 的三个 host 是编译期常量（AGENTS.md 网络层） |
| 下载管理 | **不适用** | 无下载功能。AGENTS.md v1.5.0 调研节：`(?i)download` 仅 1 处命中且是 webLog 的 `download:0` 字段。现有的是**自动离线缓存**（`OfflineAudioCache`，:42 注释「上限默认 512 MiB」），语义是「播过的自动留在盘上」，与「用户主动下载 + 任务队列 + 已下载列表管理」不是同一件事；把它命名成「下载管理」会让用户以为能离线下载新歌 |
| 流量管理 | **不适用** | `流量管理` 命中 **0**；无流量统计、无「仅 Wi-Fi 下载」类开关。唯一网络相关偏好是移动网络音质 `#2`，属音质组 |
| 备份与恢复 | **不适用** | `backup` 命中 **0**；无导入/导出/WebDAV。全部落盘是 SharedPreferences + Gson（AGENTS.md 状态管理），没有备份入口 |
| 一起听 | **不适用** | `一起听`/`listenTogether` 命中 **0**。⚠️ 唯一沾边的是手机扫平板二维码传 cookie（`QrAuthorizeScreen`，`auth/QrPair*`），那是**登录授权**（`UserScreen.kt:248/1268`），**不要**归到「一起听」 |
| 播放源 | **不适用（本版）** | 双音源（`MusicSource.kt:31` NETEASE/QQMUSIC）是**每首歌的身份**，不是全局偏好；`preferred_source`/`default_source`/`playback_source` 命中 **0**。若将来做「默认/优先音源」才是播放源设置，本版不做 |
| B 站登录 | **不适用** | `bilibili`/`B站` 命中 **0**。账号只有两个：网易云 + QQ 音乐（`UserScreen.kt:336/356`） |

## 3. 问题 2：一级卡片「图标 + 标题 + 副标题 + 箭头」的落地（**建议**）
### 3.1 仓库现有的可复用件（全部已存在，无需改）
| 用途 | 现成 API | 位置 |
|---|---|---|
| 卡片底板 + 点击 | `MetroSurface(color, onClick) { }`（**无 shape 参数**，仅 `background + clickable`） | `Kanesumi-sec-a/kanesumi-controls/.../MetroSurface.kt:20-29` |
| 图标 | `MetroIcon(image, contentDescription, tint, sizeDp)` | 用法见 `UserScreen.kt:1191`、`:833`、`:858` |
| 文本 | `MetroText(text, color, style)` + `LocalMetroTypography`（`bodyLarge`/`bodyMedium`/`bodySmall`/`caption`） | `UserScreen.kt:1011/1014`、`:1476/1488` |
| 箭头 | `MetroIcon(Icons.Default.ChevronRight, null, tint = onSurfaceVariant, sizeDp = 20.dp)` | `UserScreen.kt:858-863`、`:833-838`（**已有一模一样的写法**） |
| 行的间距与触控 | `Row.fillMaxWidth().clickable{}.padding(horizontal = 16.dp, vertical = 14.dp)`（≈48dp 高） | `UserScreen.kt:845-849`、`:820-825` |
| 按压回弹（零重组） | `Modifier.appPressScale(enabled)`（`PointerEventPass.Initial`，**不消费**事件） | `ui/components/AppVisualModifiers.kt:100-135` |
| 分隔线 | `MetroDivider()` | `ui/components/AddToPlaylistSheet.kt:103` |
| 形状（圆角） | **建议保持直角**。正典是「直角、无圆角」（`AppShapes.kt:23-24`）；`AppShapes` 是 `RoundedCornerShape` 的**唯一落点**（`:30-33` 由 `AppShapesSingleSourceTest` 强制）。Kanesumi 库内部画直角，圆角只能在调用点 `Modifier.clip(...)` 施加（`:52-59`） | `ui/theme/AppShapes.kt:61-115` |
| 二级页骨架（返回箭头 + LazyColumn + 底部 inset） | `DetailScaffold(title, onBack, header, content)`，返回箭头 = `TopScrimIconButton` → `MetroTopScrim` | `ui/components/DetailScaffold.kt:57-206`、`:194-206` |
| 二级页备选标题栏 | `MetroAppBar(title, navigationIcon, actions)`（直角、随主题色） | `kanesumi-structure/.../MetroAppBar.kt:34-44` |
| 转场时长/曲线 | `AppMotion.pageTransitionSpec()`（260ms + `FastOutSlowInEasing`） | `ui/theme/AppMotion.kt:267-289` |

### 3.2 必须**新增**的三项（**建议**）
1. **`SettingsGroupCard`**：`MetroSurface(onClick)` + `Row`（左 `MetroIcon` 24dp / 中 `Column(weight(1f))` 两行 `MetroText` / 右 `ChevronRight` 20dp）+ `Modifier.appPressScale()`。
   为什么必须新写：`ProfileBlock`（`UserScreen.kt:872`）与 `QqAccountBlock`（`:1439`）形态最接近，但**都是 `private fun`**，且首页是 96dp 头像而非图标。**建议**落点 `ui/components/SettingsGroupCard.kt`。
2. **把行组件从 `UserScreen.kt` 提出来**：`SettingSwitchRow`(`:994`)、`MetroDropdownRow`(`:1157`)、`CacheUsageLine`(`:1087`)、`SectionTitle`(`:967`) 改成 `internal` 并移到 `ui/components/SettingsRows.kt` —— 参数、默认值、`toggleable(Role.Switch)` 语义（`:1003-1007`）、`MetroSelectorFlyout` 锚点写法（`:1199`）**逐字不动**。
3. **分组标题文案**：7 条新词条须进 `SettingsStrings`（`ui/i18n/Strings.kt:1109`）× **8 个 locale 文件**，并受 `StringsConstructorBudgetTest` 的参数上限监控（阈值 150；当前各组参数数**本探针未运行测试，不假装知道**）。

## 4. 问题 3：二级菜单导航方式选型（**推荐方案①**）
### 4.1 仓库现有导航设施（现状）
- 唯一 `NavHost` 在 `MainNavGraph`（`NavGraph.kt:128`），路由常量在 `NavRoutes`（`:25`）；`HOME` 是**空 composable**（`:165-167`），内容由 `MainScreen` 的 Scaffold 填充，`NavGraph` 叠在 tab 屏**之上**（`MainActivity.kt:2552-2556` 注释）。
- 四个方向的转场由 `PageTransitionSetting` 统一控制（`NavGraph.kt:160-163`，实现 `:415-456`），**关闭时挂 `EnterTransition.None`**。
- **全屏 overlay 先例**：`About`（`MainActivity.kt:2076-2080`：`if (showAbout) { AboutScreen(onBack); return }`）、WebView 登录（`:2179-2225`，同样提前 `return`，整棵主界面被替换）、QQ 登录浮层（`:2161`）。
- **Dialog 形态先例**：「离线缓存管理」是全屏 `Dialog`（`UserScreen.kt:268-274`；文件头解释了为什么不是导航页）。
- **BackHandler 先例**：`AboutScreen.kt:52` `BackHandler { onBack() }`；`MainActivity.kt:2792` 的播放器展开拦截（`:2790` 注释：`OnBackPressedDispatcher` **按注册逆序**回调）。

### 4.2 两方案对比
| | ① 接入既有 NavGraph | ② 设置页内部状态 + BackHandler |
|---|---|---|
| 返回栈正确性 | **由导航库保证**：系统返回、手势返回、进程重建后恢复都对 | 自己维护；进程被杀后 `openGroup` 丢失（除非 `rememberSaveable`），且不参与系统导航恢复 |
| 转场 | 复用 `pageTransitionEnabled`（`NavGraph.kt:160-163`），**零新增动画调用点** | 需新写 `AnimatedContent` + `AppMotion.pageTransitionSpec()`；但 `AppMotion.kt:240-241` 明确写「**唯一调用点是 `NavGraph.kt`**」——再开一处就破坏该唯一性 |
| 骨架复用 | `DetailScaffold`（返回箭头/opaque 背景/`BottomOverlayInsetDp`）现成 | 需自绘页头，或同样用 `DetailScaffold` 但不是导航页 |
| 改动面 | 新增 1 条路由 + `MainNavGraph` 透传 1 个回调到 `UserScreen` | `UserScreen` 内部新增状态 + BackHandler（改动更局部） |
| 风险 | 与 `MainActivity.kt:2792` 的播放器 BackHandler 共存（见下） | 注册顺序问题**同样存在**，且多一条「忘记处理返回」的路径 |

**推荐：方案①。** 理由：设置二级页在语义上就是「从设置 tab 推入的详情页」，与 album/artist 详情同构；返回栈正确性交给库、转场开关复用现成一处真相、`DetailScaffold` 直接可用；方案②会把 `AppMotion` 的「唯一调用点」约定打破，并把返回栈正确性变成手工契约。

### 4.3 返回栈正确性的实现要点（**建议**）
1. 路由带参数：`NavRoutes.SETTINGS_GROUP = "settings/{group}"` + `fun settingsGroup(key: String) = "settings/$key"`，`navArgument("group") { type = NavType.StringType }`。
2. 进入用 `navController.navigate(NavRoutes.settingsGroup(key))`；**不要**加 `launchSingleTop = true` —— 连续点两个不同分组时会复用栈顶条目、参数不更新。
3. 二级页返回**只**用 `navController.popBackStack()`（同 `NavGraph.kt:180` 的调用方式）；**不要**在二级页里 `navigate(HOME)`（会把栈压平，丢掉 tab 状态）。
4. **未知 key 兜底**：进程重建后 `group` 可能对不上（版本升级删了分组）。解析失败必须回落到一级页并 `popBackStack()`，**不抛异常**（与 `AccentSource.kt:36` 的 `getOrDefault(PRESET)` 同一条纪律）。
5. 一级页滚动位置：`NavHost` 叠在 tab 屏之上、tab 屏不卸载（`MainActivity.kt:2552-2556`），因此**预期**返回后位置保留；这条**必须真机/模拟器确认**，本探针不声称已验证。
6. 与播放器 BackHandler 的关系：`UserScreen` 在 `when(selectedTab)`（`MainActivity.kt:2524`）里组合，早于 `:2792` 的播放器 BackHandler；逆序回调 ⇒ 播放器展开时返回键先被播放器消费（`enabled = progress>0.01f || bigScreen`）。这个优先级是**期望行为**（先收播放器），但新增的路由必须让 Navigation 自己的 BackHandler 参与，不能再加一层 `enabled=true` 的全局拦截。
7. 二级页必须 **opaque 背景**：`DetailScaffold` 已用 `background(background)`（`:96-98`），沿用即可，否则 popExit 期间会透视到 tab 屏。

## 5. 问题 4：注册表（registry）数据模型 + 单测设计（**建议**）
**落点**：`app/src/main/java/com/takahashirinta/ncrust/ui/settings/SettingsRegistry.kt`（新包 `ui/settings`，**纯数据、无 Compose/Context 依赖**，JVM 可测）；单测放 `app/src/test/.../ui/settings/SettingsRegistryTest.kt`（与 `PlaylistLayoutSettingTest.kt` 同风格：手写 `FakePrefs`，不引 mock 框架）。

```kotlin
package com.takahashirinta.ncrust.ui.settings

enum class SettingType { BOOL, INT, FLOAT, STRING }

/** 一级分组。key 用于路由参数 —— 与 i18n 文案解耦，改文案不动路由。 */
enum class SettingsGroup(val key: String) {
    ACCOUNT("account"), GENERAL("general"), APPEARANCE("appearance"),
    PLAYBACK("playback"), LYRICS("lyrics"), STORAGE("storage"), ABOUT("about"),
}

/** 控件形态：二级页据此选渲染分支，UI 不再自己判断"这一项是什么"。 */
enum class SettingControl { SWITCH, DROPDOWN, ACTION, INFO, COLOR, CUSTOM }

data class PrefSetting(
    val key: String,            // ← 落盘 key，必须与第 1.2 节逐字一致
    val type: SettingType,
    val defaultValue: Any,      // 与 type 匹配；**只用于回显/断言，绝不写盘**
    val prefsName: String = "ncrust_settings",
    val control: SettingControl,
    /** 指向 SettingsStrings 的属性名（i18n 单一真相，不在 registry 里存中文）。 */
    val titleStringKey: String,
    val subtitleStringKey: String? = null,
)

/** 无 prefs key 的行也有稳定 id：账号块 / 清除缓存 / 后台运行 / 关于。 */
data class ActionSetting(val id: String, val control: SettingControl, val titleStringKey: String)

sealed interface SettingRow { val group: SettingsGroup; val order: Int }

/** 唯一的注册表。UI 由它驱动；它**没有**任何写盘能力（类型上就不给）。 */
object SettingsRegistry {
    val rows: List<SettingRow>            // 全量，order 即组内顺序
    val groups: List<SettingsGroup>       // 一级卡片顺序
    fun rowsOf(g: SettingsGroup): List<SettingRow>
    fun pref(key: String): PrefSetting?   // 供单测与迁移期对账
}
```

**它为什么不破坏现有读写语义**（三条硬约束，建议写成 KDoc + 测试）：

1. **key 只复制、不重命名**：registry 的 `key` 逐字抄自第 1.2 节（如 `keep_screen_on`、`lyrics_word_animation`），UI 不再手写字符串。
2. **读写仍走既有入口**：`onCheckedChange` 里照旧调 `KeepScreenOnSetting.write` / `RotationSetting.write` / `VisualizerSetting.write` / `PageTransitionSetting.writeEnabled` / `ArtistReco.setEnabled` / `LyricsDisplayPrefs.writeXxx` / `PlayerViewModel.setXxx`；`wifi_quality`/`mobile_quality`/`lyrics_translation`/`lyrics_in_media_session` **继续用原来的裸 `prefs.edit()`**——迁移时**不要**顺手给它们补一层包装，否则等于新增第三套语义。
3. **默认值不落盘**：registry 只声明期望默认值，**没有任何 API 会把它写进 prefs**。回显一律调既有 `read*`；`lyrics_word_animation` 尤其重要——它的读路径带一次性迁移并写回（`LyricsDisplayPrefs.kt:133-145`），直接用 registry 默认值回显会让老用户的三选一显示错误。

**单测（建议 5 条，全部是 JVM 纯逻辑）**：

| 用例 | 断言 |
|---|---|
| `legacyPrefKeysArePreservedExactly` | registry 的 key 集合 **==** 测试里**硬编码**的迁移前清单（第 1.2 节 23 个）。**双向等值** ⇒ 既防丢项，也防「顺手加一个音理的功能项」 |
| `defaultsMatchCanonicalConstants` | 逐项对齐既有单一真相：`KeepScreenOnSetting.DEFAULT_ENABLED`(:36)、`RotationSetting.DEFAULT_ENABLED`(:42)、`VisualizerSetting.DEFAULT_ENABLED`、`PageTransitionSetting.DEFAULT_ENABLED`(:74)、`LyricsDisplayPrefs` 常量(:118/:152/:166/:173/:187/:195)、`ArtistReco.isEnabled` 默认 false、`OfflineAudioCache` 512/64/8192、`AccentSource` PRESET、`LanguageManager` `"zh-CN"` |
| `everyGroupNonEmptyAndEveryRowInExactlyOneGroup` | `rows.groupingBy{it.group}.eachCount()`：无空组、`sum == rows.size`、组内 `order` 唯一 |
| `actionRowIdsAreStable` | `ActionSetting.id` 集合 == 迁移前非 prefs 行清单（账号×2、后台运行、清除缓存、离线缓存管理、关于），防「整块搬走但忘了搬」 |
| `registryHasNoWriteCapability` | 反射断言 `PrefSetting` 字段类型白名单（无 `Context`、无函数类型）——把「registry 不管读写」变成可测事实 |

## 6. 问题 5：分阶段实施计划（文件级，**建议**）
**阶段 1（纯数据 + 单测，不接线）** 新增 `ui/settings/SettingsRegistry.kt` + `app/src/test/.../ui/settings/SettingsRegistryTest.kt`（上述 5 条）。风险：极低（不接线，产物行为不变）；回滚：删两个文件。
**阶段 2（抽行组件，零视觉变化）** 新增 `ui/components/SettingsRows.kt`，把 `SettingSwitchRow`/`MetroDropdownRow`/`CacheUsageLine`/`SectionTitle` 从 `UserScreen.kt` 移入（`private`→`internal`，参数与视觉逐字不动），`UserScreen.kt` 改为引用。风险：`SectionTitle` 等名字与别处重名（**须编译验证**）；回滚：revert 单个 commit。
**阶段 3（路由与骨架先上，旧页不动）** `NavGraph.kt` 加 `SETTINGS_GROUP` + composable（内部按 `group` 分发）+ `MainNavGraph` 新增 `onOpenSettingsGroup` 透传参数；新增 `ui/screen/SettingsGroupScreen.kt`（`DetailScaffold` 骨架）。**此阶段一级页仍是旧 `UserScreen`** ⇒ 行为不变、可随时 revert。
**阶段 4（按分组迁移，一个分组一个 commit）** 顺序建议：账号 → 通用 → 外观与动效 → 播放与音质 → 歌词 → 存储与缓存 → 关于。**每组必须是「二级页上线 + 旧 item 块同时删除」的同一个 commit**（见风险 1）。同步新增 `SettingsGroupCard` 与一级页卡片列表；`UserScreen` 的私有行组件随最后一个使用点消失而删除。
**阶段 5（收尾）** 删空壳、清 import；**不新增任何 key、不改任何默认值、不改任何文案语义**（i18n 只允许新增分组标题）。

**风险点与回滚**：

1. **最大风险 = 双轨期同一 key 挂在两处** ⇒ 状态分裂（改一处、另一处不刷新）。处置：禁止「先加二级页、下个 commit 再删旧块」；两件事必须同一 commit。这是本项目已踩过的同类坑（`UserScreen.kt:110-118` 的 KDoc 记录了「页面切换动效在本地再 `remember` 一份就出现设置页显示关、转场还在」）。
2. **默认值/迁移语义风险**：`lyrics_word_animation` 的读路径含一次性迁移（`LyricsDisplayPrefs.kt:133-145`）；`lyrics_ttml_first` 是**条件挂载**（`UserScreen.kt:598`，AGENTS.md 触摸陷阱 #1：`alpha=0` 仍吃事件，必须不挂载）。二级页必须原样保留这两条。
3. **状态提升不可回退**：`page_transition_enabled` 的真源在 `MainScreen`（`MainActivity.kt:900-901`），`UserScreen` 只是受控组件（`:110-118`）。迁移时参数必须继续从 `MainActivity.kt:2541-2545` 传入。
4. **`Strings` 预算**：7 条分组标题 × 8 locale，进 `SettingsStrings`（`Strings.kt:1109`），受 `StringsConstructorBudgetTest`（阈值 150）监控。
5. **触摸死带**：设置页是 tab 屏、在 `PlayerCardOverlay` 之下（`MainActivity.kt:2248` 注释 + AGENTS.md「Compose 触摸陷阱」#2），一级页/二级页的 `LazyColumn` 必须继续用 `BottomOverlayInsetDp`（现见 `UserScreen.kt:314`、`DetailScaffold.kt:158`），否则底部卡片在有歌播放时点不动。
6. **铁律**：本版**只重组现有设置项**；registry 的「双向 key 等值」测试就是「不引入音理功能项（一起听/下载管理/流量管理/网络设置/备份与恢复/B 站登录）」的机械防线。

## 7. 未验证 / 边界（如实声明）
- **没有**音理截图，本文的分组对照仅依据任务书文字；「音理有 8 组、Ncrust 建议 7 组」是**建议**，不是对齐结论。
- **未运行** `./gradlew test`，因此 `StringsConstructorBudgetTest` 的当前参数数、阶段 2 的重名冲突均未实测。
- **未装机**：一级页滚动位置保留（§4.3-5）、卡片触控高度、二级页转场手感均需真机/模拟器验证。
- 本文只写入本文件一个路径；仓库源码**未被修改**。
