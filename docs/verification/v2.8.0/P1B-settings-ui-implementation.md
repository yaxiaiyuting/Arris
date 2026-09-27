# v2.8.0 · P1-B 设置界面「一级分组卡片 + 二级详情页」：交付说明（UI 集成侧）

> 本文是实现者交给上游的**事实清单**：每个提交改了什么、每一项设置去了哪、
> 「不丢项 / 不改语义」是怎么被机械证明的，以及**没有验证过的部分**。
> 所有结论都对应到具体提交与 `file:line`。
> 本环境**无设备、无模拟器**：没有装机截图、没有转场手感、没有滚动位置保留的实测，
> 与两份探针（`probe-settings-structure.md` / `probe-settings-inventory.md`）同一前提。

前置阅读：`probe-settings-structure.md`（主规格：分组、卡片落地、导航方案①、五阶段计划）、
`probe-settings-inventory.md`（42 项清单与依赖）、`P1A-waveform-tier-implementation.md`（波形侧 API）。

## 0. 提交清单（阶段 2–5；阶段 1 已由 v2.8.0 的前序提交完成）

| # | 提交 | 内容 | 可回滚性 |
|---|---|---|---|
| 1 | `refactor(settings): 抽出设置行组件到 ui/components/SettingsRows.kt` | `SectionTitle` / `SettingSwitchRow` / `MetroDropdownRow` / `CacheUsageLine` 从 `UserScreen.kt` 原样搬出（`private` → `internal`），新增可选 `enabled`（默认 `true`） | 删文件 + revert 一个提交；产物行为不变 |
| 2 | `feat(settings): 落设置二级页路由与骨架` | `ui/settings/SettingsGroupRoute.kt`、`ui/settings/SettingsRenderPlan.kt`、`ui/components/SettingsGroupCard.kt`、`ui/screen/SettingsGroupScreen.kt`（骨架）、`NavGraph` 的 `settings/{group}` + `MainNavGraph.settingsGroupContent`、3 个单测文件 | 一级页仍是旧平铺页 ⇒ 行为不变；新路由当时**不可达** |
| 3 | `feat(settings): 一级页改分组卡片、7 个分组搬进二级页` | `UserScreen.kt` → 卡片列表；`SettingsGroupScreen.kt` 填满 6 组；`SettingsAccountSection.kt`（账号组 + 三个覆盖层）；`MainActivity` 接线 | 一个提交同时上二级页 + 删旧块（无双轨期） |
| 4 | `docs(v2.8.0): 回填 P1-B 设置界面重组的交付说明` | 本文 | 纯文档 |

## 1. 落点：文件与函数

| 职责 | 落点 |
|---|---|
| 一级页（页头 + 7 张卡片） | `ui/screen/UserScreen.kt`（`UserScreen`，108 行；迁移前 1566 行） |
| 卡片组件（图标 + 标题 + 副标题 + 箭头） | `ui/components/SettingsGroupCard.kt`（`SettingsGroupCard` / `settingsGroupIcon` / `settingsGroupTitle` / `settingsGroupSubtitle` / `SettingsCardDivider`） |
| 二级页骨架 + 6 个分组的内容 | `ui/screen/SettingsGroupScreen.kt`（`SettingsGroupScreen` → `SettingsPreferenceGroupPage`） |
| 「账号与登录」二级页 + 其覆盖层 | `ui/screen/SettingsAccountSection.kt`（`SettingsAccountPage` / `ProfileBlock` / `QqAccountBlock` / `AccountDialog` / `DialogButton` / `FullWidthDialogButton`） |
| 行组件（开关 / 下拉 / 分区标题 / 分账行 / 跳转行） | `ui/components/SettingsRows.kt` |
| 路由模板与解析（纯逻辑） | `ui/settings/SettingsGroupRoute.kt` |
| 渲染计划：条目 → 控件形态（纯逻辑） | `ui/settings/SettingsRenderPlan.kt` |
| 导航接入 | `ui/navigation/NavGraph.kt`（`NavRoutes.SETTINGS_GROUP` / `settingsGroup(id)` / `settingsGroupContent`） |
| 状态接线 | `MainActivity.kt`（`MainScreen`：`when(selectedTab) → UserScreen(onOpenSettingsGroup)` + `MainNavGraph(settingsGroupContent = …)`） |

## 2. `UserScreen.kt` 删掉的旧 item 块（同一个提交里全部搬进二级页）

| 旧块（迁移前 `UserScreen.kt`） | 去处 |
|---|---|
| 账号块 ×2（`ProfileBlock` :872 / `QqAccountBlock` :1439） | 账号与登录页（`SettingsAccountSection.kt`） |
| 音质两行（:427-460） | 播放与音质页 |
| 播放块（:463-632，含混在里面的歌词 9 行） | 播放与音质页（无缝 / 禁止熄屏 / 可视化 / 波形 6 项）+ 歌词页（9 项） |
| 外观块（:635-704，一个 item 里 4 个 `SectionTitle`） | 外观与动效页（主题模式 / 主题色 / 主题色来源 + 重读系统色 / 页面切换动效 / 背景图）+ 通用页（语言） |
| 后台运行（:707-737） | 通用页 |
| 自定义背景图（:742-788） | 外观与动效页 |
| 存储与缓存（:791-841） | 存储与缓存页（+ 离线缓存上限行） |
| 关于（:844-865） | 关于页 |
| 随之删除的私有组件：`SectionTitle` / `SettingSwitchRow` / `MetroDropdownRow` / `CacheUsageLine`（阶段 2 已搬）、`ThemeModeSelector` / `MetroLanguageDropdown` / `ClearCacheConfirmDialog` / `AccountDialog` / `DialogButton` / `FullWidthDialogButton` / `ProfileBlock` / `QqAccountBlock` / `isFlacTierIndex` | 迁到 `ui/components/SettingsRows.kt` 与两个二级页文件 |

## 3. 「不丢项」怎么被证明（不是靠人眼比对）

`app/src/test/java/com/takahashirinta/ncrust/settings/` 下 3 个新测试类（共 24 个用例）：

| 测试 | 断言 |
|---|---|
| `SettingsRenderPlanTest.everyNonInternalEntryIsMappedExactlyOnce` | 非 internal 条目**全集** == 各组二级页渲染 ∪ 库页承载（4 项），且两边**不相交**、渲染列表内**无重复** —— 双向等值，丢项与重复同时被卡住 |
| `SettingsRenderPlanTest.renderedRowCountPerGroupIsPinnedAndNothingIsLost` | 逐组行数钉死（2/4/5/11/9/3/1 = **35**），并断言 `55 条 registry 条目 = 16 内部项 + 4 库页承载 + 35 二级页渲染` |
| `SettingsRenderPlanTest.everyPlannedRowStaysInItsOwnGroupAndInRegistryOrder` | 每组渲染列表 == registry 在该组的声明顺序过滤后；条目的 `group` 与所在页面一致 |
| `SettingsRenderPlanTest.levelOneCardListMatchesRegistryGroupsExactly` | 一级页卡片列表（`SettingsRenderPlan.cardGroups()`，`UserScreen` 直接迭代它）== `SettingsRegistry.groups()`，数量 7、顺序固定，且每组渲染列表非空 |
| `SettingsGroupRouteTest.allSevenGroupIdsResolveBackToTheirGroup` | `settings/{account…about}` 7 条路由都能解析回对应分组；`NavRoutes.SETTINGS_GROUP == SettingsGroupRoute.PATTERN`（模板只有一个字面量） |
| `SettingsGroupRouteTest.unknownGroupIdsFallBackWithoutThrowing` | 10 种非法参数（null / 空串 / 空白 / 未知 id / 大小写不符 / 带空格 / 多一段 / 前缀同词 / 模板本身）一律返回 `null` ⇒ NavGraph 安全回落一级页，**不抛异常** |
| `SettingsGroupRouteTest.secondaryPageCanListEveryEntryOfItsGroup` | 每个分组「registry 里的全部条目」与「实际挂载的行」都在，且默认取值下二者只差内部项与库页承载项 |
| `SettingsRenderPlanTest.gatedRowsAreVisibleByDefaultAndOnlyTtmlFirstCanDisappear` | 默认取值下「计划」=「实际挂载」；把 `lyrics_ttml_enabled` 关掉后歌词页**只**少 `lyrics_ttml_first` 一行（既有硬依赖语义原样保留） |
| `WaveformSettingsGatingTest.theSevenWaveformKeysLandOnThePlaybackPageWithTheExpectedControls` | 波形 7 键：6 条渲染在播放与音质页（档位下拉 + 5 个开关），`visualizer_tier_version` 是迁移水位（内部项，不渲染） |
| `WaveformSettingsGatingTest.lowEndDeviceEchoesSimpleTierWithoutWritingItBack` | 低端机（3 GiB / API 25 / `isLowRamDevice`）解析出的默认档 = **0（简洁）**；键缺失时回显 0 且 `prefs.getAll()` **逐键不变**（回显不落盘）、`hasExplicitTier` 仍为 false；用户显式选过则以用户值为准 |
| `WaveformSettingsGatingTest.showcase*`（3 条） | `tier != 2` ⇒ C 档 4 个细分项与总开关「可见但不可用」（`TIER_NOT_SHOWCASE_CAPABLE`）；`tier == 2 且 showcase=false` ⇒ 细分项「可见但不可用」（`SHOWCASE_DISABLED`）；只有 T2 + 总开关开才 `FREE` |

另外两条既有防线继续有效（未改动）：
`SettingsRegistryTest.legacyPrefKeysArePreservedExactly`（42 个既有键双向等值 = 防丢项 + 防夹带音理功能项）
与 `everyTitleKeyResolvesToARealStringsAccessorPath`（文案路径在 `Strings` 上真实可达）。

**16 条内部项（`isInternal`）不渲染**：它们是 probe-settings-inventory §2.2 的 13 个隐藏键 + 2 条账号凭证 +
`visualizer_tier_version` 迁移水位。registry 里逐条在册（防丢项的那一半由 42 键等值断言保证），
但它们**迁移前也没有任何 UI 入口**，本版不新造界面（铁律：只重组现有项）。
**4 条库页显示偏好**（`library_playlist_layout` / `library_section_collapsed_*`）由库页承载：
结构探针 §1.2 明确建议不迁移 —— 搬进设置页会让同一个状态出现两个可写入口（真双轨）。

## 4. 「不改语义」的核对表（读写入口逐条对照）

| 设置项 | 读 | 写 | 与迁移前 |
|---|---|---|---|
| `wifi_quality` / `mobile_quality` | 裸 `prefs.getInt(...)`（默认 3 / 1） | 裸 `prefs.edit().putInt(...)` **+** `PlayerViewModel.onQualityPreferenceChanged()` | **一致**（未补包装、未改默认值） |
| `gapless_playback` | 裸 `prefs.getBoolean(...)`（默认 true） | 裸 `prefs.edit().putBoolean(...)` **+** `PlayerViewModel.refreshGaplessSetting()` | **一致** |
| `keep_screen_on` | `KeepScreenOnSetting.read(context)` | `KeepScreenOnSetting.write(context, v)` | **一致** |
| `auto_rotate` | `RotationSetting.read(context)` | `RotationSetting.write(context, v)` | **一致** |
| `audio_visualizer` | `VisualizerSetting.read(context)` | `VisualizerSetting.write(context, v)` | **一致** |
| `artist_reco_enabled` | `ArtistReco.isEnabled(context)` | `ArtistReco.setEnabled(context, v)` | **一致** |
| `page_transition_enabled` | `pageTransitionEnabled` 入参（状态提升在 `MainScreen`） | `onPageTransitionChange` → `MainScreen` 里 `PageTransitionSetting.writeEnabled` | **一致**（受控组件语义没变） |
| `lyrics_translation` | 裸 `prefs.getBoolean(...)`（默认 true） | 裸 `prefs.edit().putBoolean(...)` **+** `PlayerViewModel.setLyricsTranslation()` | **一致** |
| `lyrics_word_animation` | `LyricsDisplayPrefs.readWordAnimation(prefs)`（**读路径带一次性迁移**） | `PlayerViewModel.setLyricsWordAnimation()` | **一致**（**没有**用 registry 默认值回显） |
| `lyrics_sweep_quality` | `LyricsDisplayPrefs.readSweepQuality(prefs)` | `PlayerViewModel.setLyricsSweepQuality()` | **一致** |
| `lyrics_font_scale` | `LyricsDisplayPrefs.readFontScale(prefs)` / `fontScaleStepIndex` | `PlayerViewModel.setLyricsFontScale(FONT_SCALE_STEPS[i])` | **一致** |
| `lyrics_in_media_session` | 裸 `prefs.getBoolean(...)`（默认 false） | **只**调 `PlayerViewModel.setLyricsInMediaSession()` | **一致**（写路径没有多写一次裸 prefs） |
| `lyrics_ttml_enabled` / `_first` / `lyrics_romanization` / `lyrics_dynamic_font` | `LyricsDisplayPrefs.read*` | `PlayerViewModel.setLyricsTtmlEnabled/setLyricsTtmlFirst/setLyricsRomanization/setDynamicLyricFont` | **一致** |
| `theme_mode` / `theme_color_index` / `accent_source` | `themeMode` / `themeIndex` / `accentSource` 入参（Activity 持有） | `onThemeModeChange` / `onThemeChange` / `onAccentSourceChange`（Activity 里 save*） | **一致** |
| `language_code` | `getSavedLanguageCode(context)` | `onLanguageChange` → Activity 的 `saveLanguageCode` + 重放 splash | **一致** |
| `custom_bg_enabled` | `BackgroundImageManager.isActive(context)`（开关 **且** 文件在） | `BackgroundImageManager.importFromUri` / `clear` | **一致**（仍然不是开关控件） |
| `offline_cache_mb` | `OfflineAudioCache.maxMb(context)` | `OfflineAudioCache.setMaxMb(context, mb)` | **一致**；新增一行下拉（旧入口在离线缓存管理弹窗里，两者都走同一个读写 API；弹窗关闭后本页重读一次，避免同一 key 两个入口显示不一致） |
| 波形 `visualizer_tier` | `VisualizerPrefs.readTier(prefs, deviceDefaultTier(context))` + `hasExplicitTier` | `VisualizerPrefs.setTier(context, v)`（用户显式选择才写） | 新增项，语义按 P1-A |
| 波形 5 个开关 | `VisualizerPrefs.readShowcase/readShockwave/readParticles/readPerspective/readTapInteraction` | `VisualizerPrefs.setShowcase/setShockwave/setParticles/setPerspective/setTapInteraction` | 新增项 |
| 跳转/行为行（后台运行、清缓存、离线管理、关于、账号×2） | — | 与迁移前同一段代码（`BackgroundActivity.requestIntent` + `appDetailsIntent` 兜底 / `ContentCache.clearAll` + Coil API + `OfflineAudioCache.clear` / `OfflineCacheManagerDialog` / `onOpenAbout` / 登录登出流程） | **一致** |

## 5. 门控（`SettingsVisibility` 的 UI 落地）

| 项 | 门控 | UI 行为 |
|---|---|---|
| `lyrics_ttml_first` | 硬依赖 `lyrics_ttml_enabled` | **整行不挂载**（行列表就把它过滤掉了，不是 `alpha=0`；AGENTS.md「Compose 触摸陷阱」#1） |
| `lyrics_sweep_quality` | 软依赖 `lyrics_word_animation == GRADIENT_SWEEP` | 可见但置灰（`enabled=false`），行下用既有词条拼出前置条件（本任务禁止改 `ui/i18n`，不能新增「需要渐变扫过」词条） |
| `visualizer_showcase` 与 4 个 C 档细分项 | `tier == 2`（+ 总开关） | 可见但置灰；原因写在各自的 `visualizer*Description`（i18n 已逐条写明生效条件） |

## 6. 未验证 / 存疑（如实声明）

1. **没有装机**：卡片触控高度（实测值只有源码里的 `vertical = 14.dp` + 两行文字）、
   二级页转场手感、返回后一级页滚动位置是否保留（探针 §4.3-5 的预期**未经真机确认**）、
   播放器「触摸死带」下二级页底部行的可点性 —— 全部需要真机/模拟器复核。
2. **一级页不再显示账号资料块**：账号信息（头像/昵称/UID）现在只在「账号与登录」二级页里。
   这是本次重组的必然结果（卡片只承载图标+标题+副标题+箭头），但它确实改变了首屏信息量。
3. **「关于」页返回后回到一级页而不是关于二级页**：`MainScreen` 里 `if (showAbout) { AboutScreen(); return }`
   会整棵替换主界面（**迁移前就是这个形状**），`rememberNavController` 随之被丢弃 ⇒ 关闭关于后
   回到 tab 屏（一级页），而不是「关于」二级页。要修得让 About 也走导航库，本轮未做（不属于本任务边界）。
4. **`rowTitle` / `rowSubtitle` 是 registry `titleKey` 的第二份显式映射**：Compose 侧必须在编译期拿到真实字符串，
   不能按路径反射取（R8 会重命名/裁剪）。两份映射的「路径真实存在」由
   `SettingsRegistryTest.everyTitleKeyResolvesToARealStringsAccessorPath` 保证，但**两份之间的数值一致性
   没有机械断言**（只有 6 个波形项例外：文案住在 `Strings.waveform`，由 `VisualizerStrings.Property.ALL` 逐条钉住）。
5. **`SettingsRenderPlan.rowsOf` 的 `when (entry.id)` 分支**：控件形态（枚举）由测试穷尽覆盖，
   但「某个 id 具体读哪个词条」这一层没有机械断言（同上一条）。
6. **`offline_cache_mb` 现在有两个入口**（存储页下拉 + 离线缓存管理弹窗）：两者读写都走
   `OfflineAudioCache.maxMb/setMaxMb`，且弹窗关闭后本页会重读；但「上限改小后本进程内仍按旧上限淘汰」
   这条既有语义没有变（提示文案 `offlineCacheLimitHint` 已说明下次启动生效）。
7. `SettingsRenderPlan` 里 `SettingsRowKind` 的枚举分支在 Compose 侧是穷尽的（少一个分支 = 编译错误），
   但**「HOSTED_ELSEWHERE 的内容是否真的还在库页」没有断言** —— 那 4 项的 UI 在
   `LibraryPlaylistsTab.kt`，不在本任务范围内，本轮只保证它们没有被搬走、也没有被删。
