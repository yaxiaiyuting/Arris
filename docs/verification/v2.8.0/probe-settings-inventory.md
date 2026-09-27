# v2.8.0 探针：设置项穷尽清单（probe-settings-inventory）

- **仓库**：`/home/duanjb666/deepseek/Ncrust`
- **被测版本**：`app/build.gradle.kts:284-285` → `versionCode = 50` / `versionName = "2.6.2-gpl"`
- **探针性质**：**只读代码审计**，未修改任何源码（本文件是本次唯一新增/写入的文件）
- **方法**：对 `app/src/main/java` 全量 grep + 逐文件精读。命令见 §0
- **证据纪律**：本文件中出现的每一个 key、默认值、取值范围均给出 `file:line`。**未在代码中确认的一律写「未确认」，不猜。**

---

## 0. 方法：实际执行的取证命令

```bash
# 0.1 所有 SharedPreferences 文件（显式 getSharedPreferences 调用）
grep -rn "getSharedPreferences(" --include="*.kt" app/src/main/java

# 0.2 所有 prefs 文件名常量（PREFS / PREFS_NAME）
grep -rn "PREFS\s*=\|PREFS_NAME\s*=\|PREFS_KEY\s*=\|private const val PREFS" --include="*.kt" app/src/main/java

# 0.3 所有 key 常量
grep -rn 'private const val KEY\|const val KEY' --include="*.kt" app/src/main/java

# 0.4 所有字面量 key 的读写（get*/put*/contains）
grep -rn 'getBoolean("\|getInt("\|getString("\|getLong("\|getFloat("\|putBoolean("\|putInt("\|putString("\|putLong("\|putFloat("\|contains(' --include="*.kt" app/src/main/java

# 0.5 迁移逻辑
grep -rn "fun migrate\|migrat\|MIGRAT\|SCHEMA_VERSION\|schemaVersion" --include="*.kt" app/src/main/java

# 0.6 设置项读取者（谁在冷启动读盘、谁在 UI 里读）
grep -rn "getSavedThemeIndex\|getSavedThemeMode\|getSavedAccentSource\|getSavedLanguageCode\|PageTransitionSetting.readEnabled\|VisualizerSetting.read\|KeepScreenOnSetting.read\|RotationSetting.read" --include="*.kt" app/src/main/java

# 0.7 设置项写入者（UI → prefs 的落点）
grep -rn "saveThemeIndex\|saveThemeMode\|saveAccentSource\|saveLanguageCode\|setLyricsFontScale\|setQualityPreference\|setLyricsWordAnimation\|setLyricsSweepQuality\|setLyricsTranslation\|setLyricsInMediaSession\|setLyricsTtmlEnabled\|setLyricsTtmlFirst\|setLyricsRomanization\|setDynamicLyricFont\|refreshGaplessSetting\|onQualityPreferenceChanged" --include="*.kt" app/src/main/java
```

**结论先行**：
- 全仓库共 **16 个** SharedPreferences 文件（用户任务书列出的 7 个是其中的子集）。
- **静态可命名 prefs key 共 82 个**（70 个唯一 key 常量值 + 9 个无常量字面量 key + 3 个枚举 key，见 §7.1 的可复算推导），另有 **7 族动态 key**。
- `ncrust_settings` 一个文件里就有 **40 个 key**，其中 **27 个**有 UI 入口、**13 个**无 UI 入口（§2.1 / §2.2）。
- **「设置项」的三种口径**（§7.1 详述，本探针**推荐口径 C**）：
  - 口径 A（严格设置页语义）= **27 项**；口径 B（+ 隐藏/内部键）= **40 项**；口径 C（+ `user_cookie` + `qq_cookie`）= **42 项**。
- 发现 **2 处显式版本迁移逻辑**（`QualityLadder.migrate`：音质档位 7→8 档；`LyricsDisplayPrefs.readWordAnimation`：`lyrics_word_by_word` 布尔→`lyrics_word_animation` Int 的一次性消费）+ **1 处「脏值回落默认」防御**（`offline_cache_mb`，**不是**版本迁移，见 §5.3）。
- **无「无法归类」的设置项**；有 **13 个无 UI 入口的键**（§7.3 逐条给出处置建议）、**8 条「未确认」项**（§8），以及 **1 个天然为空的分组**（「关于」，§6.7 —— 这是正确结果而非遗漏）。

---

## 1. prefs 文件总表（16 个）

| # | prefs 文件 | 归属类/文件（file:line 定义） | 内容性质 | 是否用户可见设置 |
|---|---|---|---|---|
| 1 | `ncrust_settings` | 多处共用（见 §2） | 显示/播放/歌词/主题/缓存上限 | **是（主设置文件）** |
| 2 | `ncrust_prefs` | `auth/CookieManager.kt:7` | 网易云 cookie | 是（账号） |
| 3 | `ncrust_device` | `network/ClientIdentity.kt:23` | 客户端指纹 deviceId | 否（内部） |
| 4 | `ncrust_netease_vip` | `auth/NeteaseVipStore.kt:62` | 网易云红V缓存 | 否（派生缓存） |
| 5 | `ncrust_qq_prefs` | `qq/QqAuthStore.kt:62`、`qq/QqIdentity.kt:32` | QQ cookie / 资料 / 设备种子 | 是（账号）+ 内部 |
| 6 | `ncrust_qq_playlists` | `qq/QqPlaylistStore.kt:52` | QQ 歌单缓存 | 否（缓存） |
| 7 | `ncrust_qq_probe` | `qq/QqProbeStore.kt:40` | QQ 兜底统计 | 否（诊断） |
| 8 | `ncrust_library` | `library/LibraryManager.kt:75` | 收藏单曲/专辑/红心 id | 否（数据） |
| 9 | `search_history` | `library/SearchHistoryManager.kt:36` | 搜索历史 | 否（数据） |
| 10 | `ncrust_playback_state` | `player/PlaybackStateManager.kt:30` | 上次播放曲目/队列/模式/进度 | 否（状态） |
| 11 | `ncrust_lyrics_cache` | `lyric/LyricsCache.kt:63` | 歌词缓存（LRC/TTML） | 否（缓存） |
| 12 | `ncrust_offline` | `cache/OfflineUrlStore.kt:107`、`cache/OfflineLibrary.kt:173` | 离线 URL 清单 + 离线曲目索引 | 否（缓存） |
| 13 | `ncrust_local_playlists` | `local/LocalPlaylistStore.kt:56` | 本地歌单 | 否（数据） |
| 14 | `ncrust_home_cache` | `cache/HomeSnapshot.kt:39` | 首页磁盘快照 | 否（缓存） |
| 15 | `ncrust_match_cache` | `crosssource/MatchCacheStore.kt:45` | 跨源匹配缓存 | 否（缓存） |
| 16 | `ncrust_report_gate` | `player/ReportGateStore.kt:43` | 跨源上报闸门计数 | 否（诊断） |

**旁证（第三方独立清单，与上表交叉印证）**：`warmup/AppWarmup.kt:84-92` 的 `PREFS_FILES` 数组只列了 7 个文件（冷启动 IO 预热的白名单，**不是**全部文件）—— 该数组注释自称「全部 SharedPreferences 文件名」，**与本次 grep 结果不符**，实际漏了 9 个（`ncrust_device` / `ncrust_netease_vip` / `ncrust_qq_prefs` / `ncrust_qq_playlists` / `ncrust_qq_probe` / `ncrust_offline` / `ncrust_local_playlists` / `ncrust_match_cache` / `ncrust_report_gate`）。这是本次审计发现的**文档/注释陈旧点**，不影响设置项本身，但写进这里以免后续迁移时被误导。

---

## 2. 完整设置项清单表（核心 · 可直接当迁移清单）

> **列说明**：「是否高级/实验性」= 代码里有没有显式标注为高级/实验性/隐藏（注释依据）；
> 「UI 入口」写 `—` 表示**当前没有任何 UI 入口**（不是遗漏，是本次审计的结论）。

### 2.1 `ncrust_settings` —— 用户可配置项（有 UI 入口，27 项）

| prefs 文件 | key | 类型 | 默认值 | 取值范围/枚举 | 读取处 (file:line) | 写入处 (file:line) | UI 入口 (file:line) | 是否高级/实验性 | 依赖项 |
|---|---|---|---|---|---|---|---|---|---|
| `ncrust_settings` | `wifi_quality` | Int | `3`（无损） | `0..7`，见 `QualityLadder.LEVELS`（`player/QualityLadder.kt:42-44`） | `ui/screen/UserScreen.kt:146`；`ui/viewmodel/PlayerViewModel.kt:917`、`1589`；`player/PlaybackService.kt:882` | `ui/screen/UserScreen.kt:441`；`ui/viewmodel/PlayerViewModel.kt:1067` | `ui/screen/UserScreen.kt:432-445`（设置页下拉）；另有播放器内联入口 `ui/player/PlayerCard.kt:804`、`1063` | 否 | 依赖 `quality_ladder_version` 的迁移（§5.1）；档位实际可用性依赖设备 FLAC 解码器（`SongUrlFetcher.deviceSupportsFlac`，`ui/screen/UserScreen.kt:431`） |
| `ncrust_settings` | `mobile_quality` | Int | `1`（较好） | `0..7`，同上 | `ui/screen/UserScreen.kt:147`；`ui/viewmodel/PlayerViewModel.kt:918`、`1591` | `ui/screen/UserScreen.kt:455`；`ui/viewmodel/PlayerViewModel.kt:1067` | `ui/screen/UserScreen.kt:446-458`；`ui/player/PlayerCard.kt:804`、`1063` | 否 | 同上 |
| `ncrust_settings` | `gapless_playback` | Boolean | `true` | `true` / `false` | `ui/screen/UserScreen.kt:148`；`ui/viewmodel/PlayerViewModel.kt:777`、`1577` | `ui/screen/UserScreen.kt:473` | `ui/screen/UserScreen.kt:467-478` | 否 | 无 |
| `ncrust_settings` | `keep_screen_on` | Boolean | `true`（`KeepScreenOnSetting.kt:36` `DEFAULT_ENABLED`） | `true` / `false` | `KeepScreenOnSetting.kt:46` | `KeepScreenOnSetting.kt:53` | `ui/screen/UserScreen.kt:481-489` | 否 | 只在 `isPlaying && 播放器界面` 生效（`KeepScreenOnSetting.kt:19-28`）；冷启动必须 `MainActivity.kt:216` 先 read，否则开关失效（HF2 实测 bug） |
| `ncrust_settings` | `auto_rotate` | Boolean | `true`（`RotationSetting.kt:42` `DEFAULT_ENABLED`） | `true` / `false` | `RotationSetting.kt:53`；`MainActivity.kt:552` | `RotationSetting.kt:61` | `ui/screen/UserScreen.kt:493-501` | 否 | 与系统 `ACCELEROMETER_ROTATION` **互相独立**（`RotationSetting.kt:28-31`）；仅影响 `requestedOrientation` |
| `ncrust_settings` | `audio_visualizer` | Boolean | `true`（`ui/player/AudioVisualizer.kt:41` `DEFAULT_ENABLED`） | `true` / `false` | `ui/player/AudioVisualizer.kt:49`；`MainActivity.kt:211`；`player/PlaybackService.kt:378` | `ui/player/AudioVisualizer.kt:58` | `ui/screen/UserScreen.kt:503-511` | 否 | 只在横屏大屏模式可见（`AudioVisualizer.kt:31-34`） |
| `ncrust_settings` | `artist_reco_enabled` | Boolean | `false`（`reco/ArtistReco.kt:72`） | `true` / `false` | `reco/ArtistReco.kt:72`（经 `isEnabled`），`ui/screen/UserScreen.kt:158` | `reco/ArtistReco.kt:75`（经 `setEnabled`） | `ui/screen/UserScreen.kt:514-521` | 否（但默认关 + 需手工配置才算数） | 依赖 `artist_reco_target_id` 非 0 **且** 锚点非空（`reco/ArtistReco.kt:97-99` `isConfigured`） |
| `ncrust_settings` | `page_transition_enabled` | Boolean | `true`（`ui/theme/PageTransitionSetting.kt:74` `DEFAULT_ENABLED`） | `true` / `false` | `ui/theme/PageTransitionSetting.kt:90-98`；`MainActivity.kt:901` | `ui/theme/PageTransitionSetting.kt:101-102`（经 `MainActivity.kt:901` 的状态提升） | `ui/screen/UserScreen.kt:526-531` | 否 | 无 |
| `ncrust_settings` | `lyrics_translation` | Boolean | `true` | `true` / `false` | `ui/screen/UserScreen.kt:156`；`ui/viewmodel/PlayerViewModel.kt:498` | `ui/screen/UserScreen.kt:538`；`ui/viewmodel/PlayerViewModel.kt:853` | `ui/screen/UserScreen.kt:533-541` | 否 | 无 |
| `ncrust_settings` | `lyrics_word_animation` | Int | `0`（渐变扫过） | `0`/`1`/`2` = `GRADIENT_SWEEP`/`HARD_CUT`/`OFF`（`lyric/LyricsDisplayPrefs.kt:29-31`） | `lyric/LyricsDisplayPrefs.kt:135`；`ui/screen/UserScreen.kt:161`；`ui/viewmodel/PlayerViewModel.kt:143` | `lyric/LyricsDisplayPrefs.kt:147`；`ui/viewmodel/PlayerViewModel.kt:788` | `ui/screen/UserScreen.kt:544-552` | 否 | 是 `lyrics_sweep_quality` 的**前置**（§4.1）；旧键 `lyrics_word_by_word` 的迁移目标（§5.2） |
| `ncrust_settings` | `lyrics_sweep_quality` | Int | `0`（自动） | `0`/`1`/`2` = `AUTO`/`SOFT`/`EDGE`（`lyric/LyricsDisplayPrefs.kt:46-52`） | `lyric/LyricsDisplayPrefs.kt:152`；`ui/screen/UserScreen.kt:169`；`ui/viewmodel/PlayerViewModel.kt:148` | `lyric/LyricsDisplayPrefs.kt:156`；`ui/viewmodel/PlayerViewModel.kt:803` | `ui/screen/UserScreen.kt:555-563` | 否 | **依赖 `lyrics_word_animation`**：`HARD_CUT`/`OFF` 下软边参数完全不生效（§4.1） |
| `ncrust_settings` | `lyrics_font_scale` | Float | `1.0f`（`lyric/LyricsDisplayPrefs.kt:118` `FONT_SCALE_DEFAULT`） | 夹取到 `0.7f..1.5f`（`:116-117`）；UI 档位表 `0.7/0.85/1.0/1.2/1.5`（`:121`） | `lyric/LyricsDisplayPrefs.kt:205`；`ui/screen/UserScreen.kt:167`；`ui/player/PlayerCard.kt:179` | `lyric/LyricsDisplayPrefs.kt:208`；`ui/viewmodel/PlayerViewModel.kt:832-837`（**3s 防抖落盘**） | `ui/screen/UserScreen.kt:565-574`；播放器内 A-/A+ `ui/player/PlayerCard.kt:870` | 否 | 无 |
| `ncrust_settings` | `lyrics_in_media_session` | Boolean | `false` | `true` / `false` | `ui/screen/UserScreen.kt:164`；`ui/viewmodel/PlayerViewModel.kt:512` | `ui/viewmodel/PlayerViewModel.kt:820` | `ui/screen/UserScreen.kt:577-585` | 否 | 与 `session_metadata_lyrics` 语义相邻但**不是同一个开关**（§4.5） |
| `ncrust_settings` | `lyrics_ttml_enabled` | Boolean | `true`（`lyric/LyricsDisplayPrefs.kt:166`） | `true` / `false` | `lyric/LyricsDisplayPrefs.kt:166`；`ui/screen/UserScreen.kt:172`；`ui/viewmodel/PlayerViewModel.kt:162` | `lyric/LyricsDisplayPrefs.kt:169`；`ui/viewmodel/PlayerViewModel.kt:889` | `ui/screen/UserScreen.kt:587-594` | 否 | 是 `lyrics_ttml_first` 的**前置**（§4.2） |
| `ncrust_settings` | `lyrics_ttml_first` | Boolean | `true`（`lyric/LyricsDisplayPrefs.kt:173`） | `true` / `false` | `lyric/LyricsDisplayPrefs.kt:173`；`ui/screen/UserScreen.kt:173`；`ui/viewmodel/PlayerViewModel.kt:168` | `lyric/LyricsDisplayPrefs.kt:176`；`ui/viewmodel/PlayerViewModel.kt:897` | `ui/screen/UserScreen.kt:598-607`（**`lyrics_ttml_enabled == false` 时整行不挂载**） | 否 | **硬依赖 `lyrics_ttml_enabled`**（§4.2） |
| `ncrust_settings` | `lyrics_romanization` | Boolean | `false`（`lyric/LyricsDisplayPrefs.kt:187`） | `true` / `false` | `lyric/LyricsDisplayPrefs.kt:187`；`ui/screen/UserScreen.kt:175`；`ui/viewmodel/PlayerViewModel.kt:521` | `lyric/LyricsDisplayPrefs.kt:190`；`ui/viewmodel/PlayerViewModel.kt:864` | `ui/screen/UserScreen.kt:610-618` | 否 | 无（只影响显示，不重取歌词 —— `LyricsDisplayPrefs.kt:86-90`） |
| `ncrust_settings` | `lyrics_dynamic_font` | Boolean | `false`（`lyric/LyricsDisplayPrefs.kt:195`） | `true` / `false` | `lyric/LyricsDisplayPrefs.kt:195`；`ui/screen/UserScreen.kt:177`；`ui/viewmodel/PlayerViewModel.kt:523` | `lyric/LyricsDisplayPrefs.kt:198`；`ui/viewmodel/PlayerViewModel.kt:876` | `ui/screen/UserScreen.kt:622-630` | **是 —— 代码显式标注「实验性」**（`LyricsDisplayPrefs.kt:93-98`；UI 文案 `ui/i18n/zh_CN.kt:44-45`） | 与 `lyrics_font_scale` 相乘（`ui/player/PlayerCard.kt:868-872` 同帧传入） |
| `ncrust_settings` | `theme_color_index` | Int | `0`（云杉） | `0..5`（`ui/theme/ThemeManager.kt:26-33` 6 个预设），越界兜底 0（`:77-79`） | `ui/theme/ThemeManager.kt:61`；`MainActivity.kt:270` | `ui/theme/ThemeManager.kt:70`；`MainActivity.kt:346` | `ui/screen/UserScreen.kt:648-655`（`ThemeColorSelector`） | 否 | 与 `accent_source` 有优先级合并（`MainActivity.kt:298` 起） |
| `ncrust_settings` | `theme_mode` | String（枚举名） | `"SYSTEM"`（`ui/theme/ThemeManager.kt:46`） | `SYSTEM`/`DARK`/`LIGHT`（`ThemeManager.kt:36`）；非法值回落 `SYSTEM` | `ui/theme/ThemeManager.kt:45-46`；`MainActivity.kt:273` | `ui/theme/ThemeManager.kt:52`；`MainActivity.kt:351` | `ui/screen/UserScreen.kt:637-645`（`ThemeModeSelector`） | 否 | 无 |
| `ncrust_settings` | `accent_source` | String（枚举名） | `"PRESET"`（`ui/theme/AccentSource.kt:36`） | `PRESET`/`COVER`/`SYSTEM`（`AccentSource.kt:24`）；非法值回落 `PRESET` | `ui/theme/AccentSource.kt:35-36`；`MainActivity.kt:288` | `ui/theme/AccentSource.kt:42`；`MainActivity.kt:356` | `ui/screen/UserScreen.kt:659-670`（`AccentSourceSelector`） | 否 | `SYSTEM` 值需要 API 31+（`AccentSource.kt:30-31`、`50-55`）；UI 里 `SYSTEM` 额外挂一个「手动重读」按钮（`UserScreen.kt:672-687`） |
| `ncrust_settings` | `language_code` | String | `"zh-CN"`（`ui/i18n/LanguageManager.kt:30`） | 8 个 locale code（`LanguageManager.kt:12-21`）；旧值 `"en-UK"` 映射到 `en`（`:39-40`） | `ui/i18n/LanguageManager.kt:30`；`MainActivity.kt:276`；`player/PlaybackService.kt:852`；`ui/viewmodel/PlayerViewModel.kt:333`、`1157` | `ui/i18n/LanguageManager.kt:34`；`MainActivity.kt:360` | `ui/screen/UserScreen.kt:690-702`（`MetroLanguageDropdown`） | 否 | 切换后 `MainActivity.kt:361` 重放 splash（重建 UI 字符串） |
| `ncrust_settings` | `custom_bg_enabled` | Boolean | `false`（`ui/theme/BackgroundImageManager.kt:57`） | `true` / `false` | `ui/theme/BackgroundImageManager.kt:57`（经 `isActive`） | `ui/theme/BackgroundImageManager.kt:62`（`setEnabled`，由 `importFromUri`/`clear` 调用） | `ui/screen/UserScreen.kt:743-788`（选图/更换 + 移除） | 否 | **依赖文件存在**：`isActive` = 开关 && `filesDir/background/bg.jpg` 是文件（`BackgroundImageManager.kt:56-58`） |
| `ncrust_settings` | `offline_cache_mb` | Int | `512`（`cache/OfflineAudioCache.kt:51` `DEFAULT_MAX_BYTES` = 512 MiB） | 合法 `64..8192`（`OfflineAudioCache.kt:54-55`）；非法值回落默认（`:72`） | `cache/OfflineAudioCache.kt:70`、`76` | `cache/OfflineAudioCache.kt:89`（`setMaxMb`） | `ui/screen/UserScreen.kt:820-839`（入口行）→ `ui/screen/OfflineCacheOverlay.kt:206-247`（选择器，档位表 `:474`：`64,128,256,384,512,768,1024,2048,4096,8192`） | 否 | **只写 prefs，下次启动生效**（淘汰器构造时固化，`OfflineCacheOverlay.kt:99-105`；UI 文案 `ui/i18n/zh_CN.kt:130`） |
| `ncrust_settings` | `library_playlist_layout` | Int | `0` = `CARD`（`ui/screen/PlaylistLayoutSetting.kt:63-64` `DEFAULT_INDEX`） | `0` = `CARD`，`1` = `LIST`（`:47-55`）；越界回落 `CARD` | `ui/screen/PlaylistLayoutSetting.kt:127`；`ui/screen/LibraryScreen.kt:131` | `ui/screen/PlaylistLayoutSetting.kt:114`；`ui/screen/LibraryScreen.kt:382` | `ui/screen/LibraryPlaylistsTab.kt:213` → `:507-545`（`PlaylistLayoutSwitch`） | 否 | 无（代码显式声明「没有旧形状 ⇒ 没有迁移」，`PlaylistLayoutSetting.kt:83-86`） |
| `ncrust_settings` | `library_section_collapsed_local` | Boolean | `false`（`ui/screen/LibrarySectionFoldSetting.kt:138`） | `true` / `false` | `ui/screen/LibrarySectionFoldSetting.kt:138`；`LibraryScreen.kt:132` | `ui/screen/LibrarySectionFoldSetting.kt:134`；`LibraryScreen.kt:390` | `ui/screen/LibraryPlaylistsTab.kt:222`（本地歌单区块标题的收展按钮） | 否 | 无（「绝不自动折叠」，`LibrarySectionFoldSetting.kt:52-58`） |
| `ncrust_settings` | `library_section_collapsed_netease` | Boolean | `false`（同上） | `true` / `false` | 同上 | 同上 | `ui/screen/LibraryPlaylistsTab.kt:265` | 否 | 无 |
| `ncrust_settings` | `library_section_collapsed_qq` | Boolean | `false`（同上） | `true` / `false` | 同上 | 同上 | `ui/screen/LibraryPlaylistsTab.kt:331` | 否 | 无 |

### 2.2 `ncrust_settings` —— 无 UI 入口的隐藏 / 内部键（13 项）

> 这一节是「设置项清单」的**另一半**：它们同样是持久化的用户级偏好，但**没有任何设置界面入口**。
> 迁移时必须逐条决策：保留为隐藏高级项 / 补 UI / 删除。

| prefs 文件 | key | 类型 | 默认值 | 取值范围/枚举 | 读取处 (file:line) | 写入处 (file:line) | UI 入口 (file:line) | 是否高级/实验性 | 依赖项 |
|---|---|---|---|---|---|---|---|---|---|
| `ncrust_settings` | `lyrics_word_by_word` | Boolean | `true`（`lyric/LyricsDisplayPrefs.kt:137` 读时默认） | `true` / `false` | `lyric/LyricsDisplayPrefs.kt:137` | **无写入点**（`:71` 注释：「仅用于一次性迁移，不再写入」） | — | 否（**遗留键**） | 被 `lyrics_word_animation` 的迁移消费（§5.2）；消费后**不删除**，留在盘上 |
| `ncrust_settings` | `lyrics_sweep_fade_em` | Float | `LyricsSweepConfig.DEFAULT.fadeEm`（`lyric/LyricsDisplayPrefs.kt:265`） | `> 0` 且有限（非法回落默认，`:266`） | `lyric/LyricsDisplayPrefs.kt:265` | **无写入点**（只能 adb / root 写） | — | **是 —— 代码显式标注「高级覆盖项」「普通用户永远不会碰到」**（`LyricsDisplayPrefs.kt:109-111`） | 同 `lyrics_sweep_quality`：只在 `GRADIENT_SWEEP` 下生效 |
| `ncrust_settings` | `lyrics_sweep_inactive_alpha` | Float | `LyricsSweepConfig.DEFAULT.inactiveAlpha`（同上） | 夹取 `0f..1f`（`:268`） | `lyric/LyricsDisplayPrefs.kt:267` | **无写入点** | — | **是（高级覆盖）** | 同上 |
| `ncrust_settings` | `lyrics_sweep_easing` | Int | `LyricsSweepConfig.DEFAULT.easing.ordinal`（`:269`） | `SweepEasing` 的 ordinal；越界回落 `LINEAR`（`:269-272`） | `lyric/LyricsDisplayPrefs.kt:269` | **无写入点** | — | **是（高级覆盖）** | 同上 |
| `ncrust_settings` | `session_metadata_lyrics` | Boolean | `true`（`player/PlaybackService.kt:1406`） | `true` / `false` | `player/PlaybackService.kt:1406`（`sessionMetadataFollowsLyrics`） | **无写入点**（`PlaybackService.kt:1395-1399` 注释明确给出 adb 写法） | — | **是 —— 注释称「缺失即默认 true，对既有用户零行为变化」**（`PlaybackService.kt:1401-1403`） | 与 `lyrics_in_media_session` 语义耦合（§4.5） |
| `ncrust_settings` | `live_update_enabled` | Boolean | `true`（`player/LiveUpdateNotifier.kt:63`） | `true` / `false` | `player/LiveUpdateNotifier.kt:63` | **无写入点** | — | **是 —— 注释写「用户可在 prefs 里关掉」但代码里没有 UI**（`LiveUpdateNotifier.kt:46`） | 需要 API 36 + 系统允许 promoted notification（`:61`） |
| `ncrust_settings` | `battery_prompt_done` | Boolean | `false`（`MainActivity.kt:390`） | `true` / `false` | `MainActivity.kt:390` | `MainActivity.kt:399`、`410` | 「首次启动电池白名单弹窗」自身（`MainActivity.kt:386-412`）—— **不是设置行**，是首启一次性标记 | 否（内部标记） | 无 |
| `ncrust_settings` | `artist_reco_target_id` | Long | `0L`（`reco/ArtistReco.kt:78`） | 任意网易云艺人 id；`0` = 未配置 | `reco/ArtistReco.kt:78` | `reco/ArtistReco.kt:81`（`setTarget`） | — （无 UI 调用方；`grep setTarget` 只有定义处） | **是（隐藏配置）** | 与 `artist_reco_enabled`、锚点共同决定卡片是否出现（`:97-99`） |
| `ncrust_settings` | `artist_reco_anchor_ids` | String（CSV） | `""`（`reco/ArtistReco.kt:84`） | 逗号/分号/空格分隔的 id 串（解析见 `:96`） | `reco/ArtistReco.kt:84` | `reco/ArtistReco.kt:87`（`setManualAnchors`） | —（无 UI 调用方） | **是（隐藏配置）** | 同上 |
| `ncrust_settings` | `artist_reco_auto_anchor_ids` | String（CSV） | `""`（`reco/ArtistReco.kt:91`） | 同上，另有 `AUTO_MAX_ANCHORS = 20` 上限（`:60`） | `reco/ArtistReco.kt:91` | `reco/ArtistReco.kt:163`（`refreshAutoAnchors` 自动推导） | —（全自动，无 UI） | 否（派生） | 依赖 `artist_reco_target_id` 非 0；7 天 TTL（`:48-49`、`:94`） |
| `ncrust_settings` | `artist_reco_auto_anchor_at` | Long | `0L`（`reco/ArtistReco.kt:94`） | 毫秒时间戳 | `reco/ArtistReco.kt:94` | `reco/ArtistReco.kt:164` | — | 否（派生） | TTL 判定用（`AUTO_TTL_MS`，`:48-49`） |
| `ncrust_settings` | `quality_ladder_version` | Int | `1`（`player/QualityLadder.kt:54` 读时默认） | 当前 `CURRENT_VERSION = 2`（`:33`） | `player/QualityLadder.kt:54` | `player/QualityLadder.kt:61` | — | 否（**迁移水位标记**） | 见 §5.1 |
| `ncrust_settings` | `live_update_probed` | Boolean | `false`（`player/LiveUpdateNotifier.kt:64`） | `true` / `false` | `player/LiveUpdateNotifier.kt:64` | `player/LiveUpdateNotifier.kt:65` | — | 否（内部：只用来「只打一次日志」） | 无 |

**两条「不存在的项」的澄清**（列出以免后续 grep 时误判）：

- **`quality_api_levels` 不落盘**：档位 → API level 的映射是内存常量 `PlayerViewModel.QUALITY_LEVELS`（`ui/viewmodel/PlayerViewModel.kt:917-918` 引用），**没有对应的 prefs key**。
- **「恢复默认设置」功能不存在**：`grep -rn 'getSharedPreferences("ncrust_settings"' --include="*.kt" app/src/main/java | grep clear` **零命中**（全仓库仅 4 处 `edit().clear()`，分别在 `auth/NeteaseVipStore.kt:113`、`crosssource/MatchCacheStore.kt:161`、`player/PlaybackStateManager.kt:109`、`qq/QqPlaylistStore.kt:164`，**都不是 `ncrust_settings`**）。

### 2.3 其他 prefs 文件的 key（非设置项，完整备查）

> 这些**不是设置项**（没有用户可配语义），列出以保证「prefs 全景」不丢项，并供迁移时判断「哪些 key 需要一起搬」。

| prefs 文件 | key | 类型 | 默认值 | 读取处 (file:line) | 写入处 (file:line) | 性质 |
|---|---|---|---|---|---|---|
| `ncrust_prefs` | `user_cookie` | String | `null`（`auth/CookieManager.kt:17`） | `auth/CookieManager.kt:17` | `auth/CookieManager.kt:13`；清除 `:26` | 账号凭证 |
| `ncrust_device` | `client_device_id` | String | `null` → 生成 32 位 hex（`network/ClientIdentity.kt:49-54`） | `network/ClientIdentity.kt:48` | `network/ClientIdentity.kt:52` | 客户端指纹 |
| `ncrust_netease_vip` | `is_vip` | Int | `0`（`auth/NeteaseVipStore.kt:88`） | `auth/NeteaseVipStore.kt:88` | `auth/NeteaseVipStore.kt:110` | 派生缓存（TTL 30min，`:74`） |
| `ncrust_netease_vip` | `checked_at` | Long | `0L`（`auth/NeteaseVipStore.kt:94`） | `auth/NeteaseVipStore.kt:94` | `auth/NeteaseVipStore.kt:112` | 派生缓存时间戳 |
| `ncrust_netease_vip` | `red_vip_level` | Int | 无默认（只写不读，`NeteaseVipStore.kt:71` 注释「只为诊断留痕」） | — | `auth/NeteaseVipStore.kt:111` | 诊断留痕 |
| `ncrust_qq_prefs` | `qq_cookie` | String | `null`（`qq/QqAuthStore.kt:88`） | `qq/QqAuthStore.kt:88` | `qq/QqAuthStore.kt:75`、`:81`；清除 `:100` | 账号凭证 |
| `ncrust_qq_prefs` | `qq_nick` / `qq_uid` / `qq_vip_type` / `qq_vip_expire_at` / `qq_profile_at` | 混合 | `null` / `0`（见 `QqAuthStore.kt:64-69`） | `qq/QqAuthStore.kt` 内多处 | `qq/QqAuthStore.kt` 内多处；清除 `:101-105` | QQ 资料缓存（TTL 10min，`:71`） |
| `ncrust_qq_prefs` | `qq_device_seed` | String | `null` → 16 位 hex（`qq/QqIdentity.kt:50-55`） | `qq/QqIdentity.kt:50` | `qq/QqIdentity.kt:53`（`commit()` 并发安全） | 设备种子 |
| `ncrust_library` | `saved_songs` | String(JSON) | `null` | `library/LibraryManager.kt:145` | `library/LibraryManager.kt:242` | 收藏数据（`SavedSongCodec SCHEMA_VERSION=2`，`library/SavedSongCodec.kt:61`） |
| `ncrust_library` | `saved_albums` | String(JSON) | `null` | `library/LibraryManager.kt:160` | `library/LibraryManager.kt:244` | 收藏数据（`SavedAlbumCodec SCHEMA_VERSION=3`，`library/SavedAlbumCodec.kt:70`） |
| `ncrust_library` | `liked_ids` | String(JSON) | `null` | `library/LibraryManager.kt:173` | `library/LibraryManager.kt:245` | 红心歌单 id 表 |
| `search_history` | `songs` | String(JSON) | `null`（`library/SearchHistoryManager.kt:147-149`） | `library/SearchHistoryManager.kt:148` | `library/SearchHistoryManager.kt:154` | 搜索历史（≤10/类，14 天 TTL，`:37-38`；`SearchHistoryCodec SCHEMA_VERSION=2`） |
| `search_history` | `albums` | String(JSON) | `null` | 同上（`keyFor`，`:141-143`） | 同上 | 同上 |
| `search_history` | `artists` | String(JSON) | `null` | 同上 | 同上 | 同上 |
| `ncrust_playback_state` | `song_id` | Long | `0`（`player/PlaybackStateManager.kt:128`） | `:128` | `:88` | 播放状态 |
| `ncrust_playback_state` | `song_name` | String | `""`（`:129`） | `:129` | `:89` | 播放状态 |
| `ncrust_playback_state` | `song_artist` | String | `""`（`:130`） | `:130` | `:90` | 播放状态 |
| `ncrust_playback_state` | `song_artwork` | String | `""`（`:131`） | `:131` | `:91` | 播放状态 |
| `ncrust_playback_state` | `song_source` | String? | `null`（`:100`） | `:100` | `:93` | 音源身份（v2.1.0） |
| `ncrust_playback_state` | `song_source_id` | String? | `null`（`:101`） | `:101` | `:94` | 音源身份 |
| `ncrust_playback_state` | `song_media_id` | String? | `null`（`:102`） | `:102` | `:95` | 音源身份 |
| `ncrust_playback_state` | `is_playing` | Boolean | `false`（`:132`） | `:132` | `:92`、`:105` | 播放状态 |
| `ncrust_playback_state` | `has_state` | Boolean | `false`（`:113`） | `:113`、`:126` | `:87` | 有无状态的哨兵位 |
| `ncrust_playback_state` | `queue` | String(JSON) | `null`（`:288`） | `:288` | `:277`（200ms 防抖） | 队列 |
| `ncrust_playback_state` | `queue_index` | Int | `0`（`:295`） | `:295` | `:278` | 队列游标 |
| `ncrust_playback_state` | `play_mode` | Int | `0` = CYCLE（`:313`） | `0..4` = `QueueModes`；越界回落 0（`:313`） | `:317` | **播放模式**（跨启动记忆；UI 入口在播放器，不在设置页） |
| `ncrust_playback_state` | `song_positions` | String(JSON) | `null`（`:174`） | `:174` | `:237`（≤300 条 LRU，`:53`） | 每曲续播位置（`PlaybackPositionCodec SCHEMA_VERSION=2`） |
| `ncrust_lyrics_cache` | `entries` | String(JSON) | `null`（`lyric/LyricsCache.kt:91`） | `lyric/LyricsCache.kt:91`（`loadLocked`） | `lyric/LyricsCache.kt:114`（`persistLocked`） | 歌词缓存（≤200 条，`:66`；TTML TTL 7 天，`:78`） |
| `ncrust_lyrics_cache` | `ttml:<songId>`（动态族） | String(JSON) | — | `lyric/LyricsCache.kt:75` 定义前缀 | `lyric/LyricsCache.kt` TTML 暂存路径 | 只有 TTML 时的暂存条目 |
| `ncrust_offline` | `urls` | String(JSON) | `null`（`cache/OfflineUrlStore.kt:119`） | `cache/OfflineUrlStore.kt:117-119` | `cache/OfflineUrlStore.kt:126-129` | 离线 URL 清单（≤300 LRU） |
| `ncrust_offline` | `tracks` | String(JSON) | `null`（`cache/OfflineLibrary.kt:185`） | `cache/OfflineLibrary.kt:183-185` | `cache/OfflineLibrary.kt:192-194` | 离线曲目索引（≤300 LRU；`OfflineTrackCodec SCHEMA_VERSION=2`） |
| `ncrust_local_playlists` | `playlists` | String(JSON) | `null`（`local/LocalPlaylistStore.kt:73`） | `local/LocalPlaylistStore.kt:73` | `local/LocalPlaylistStore.kt:89`（`commit()`，`:91`） | 本地歌单列表（`LocalPlaylistCodec SCHEMA_VERSION=1`） |
| `ncrust_local_playlists` | `tracks:<source>:<ownerId>:<playlistId>`（动态族） | String(JSON) | — | `local/LocalPlaylistStore.kt:97` | `local/LocalPlaylistStore.kt:107`；删除 `:113` | 本地歌单曲目 |
| `ncrust_home_cache` | `daily_songs` | String(JSON) | `null` | `cache/HomeSnapshot.kt:102` | `cache/HomeSnapshot.kt:79` | 首页快照（≤60 条/块，`:45`） |
| `ncrust_home_cache` | `recommend_playlists` | String(JSON) | `null` | `cache/HomeSnapshot.kt:105` | `cache/HomeSnapshot.kt:81` | 首页快照 |
| `ncrust_home_cache` | `new_songs` | String(JSON) | `null` | `cache/HomeSnapshot.kt:108` | `cache/HomeSnapshot.kt:83` | 首页快照 |
| `ncrust_home_cache` | `toplists` | String(JSON) | `null` | `cache/HomeSnapshot.kt:111` | `cache/HomeSnapshot.kt:85` | 首页快照 |
| `ncrust_home_cache` | `saved_at` | Long | `0L`（`cache/HomeSnapshot.kt:60`） | `cache/HomeSnapshot.kt:60` | `cache/HomeSnapshot.kt:86` | 快照时间戳 |
| `ncrust_match_cache` | `schema_version` | Int | 无默认（只写） | — | `crosssource/MatchCacheStore.kt:191` | 水位标记（`MatchCacheCodec SCHEMA_VERSION=1`） |
| `ncrust_match_cache` | 动态族（艺人/歌曲 key） | String(JSON) | `null` | `crosssource/MatchCacheStore.kt:174`（`raw`） | `crosssource/MatchCacheStore.kt:190`（`mutate` 内） | 跨源匹配缓存（≤400 条，`:50`） |
| `ncrust_qq_playlists` | `schema_version` | Int | 无默认（只写） | — | `qq/QqPlaylistStore.kt:187` 定义；`:96`、`:130` 写 | 水位标记（`PlaylistCacheCodec SCHEMA_VERSION=2`） |
| `ncrust_qq_playlists` | `list:qqmusic:<ownerId>` | String(JSON) | `null` | `qq/QqPlaylistStore.kt:70-72` | `qq/QqPlaylistStore.kt:94` | QQ 歌单列表缓存 |
| `ncrust_qq_playlists` | `list_at:qqmusic:<ownerId>` | Long | `0L`（`:100`） | `qq/QqPlaylistStore.kt:99-100` | `qq/QqPlaylistStore.kt:95` | 列表时间戳 |
| `ncrust_qq_playlists` | `detail:qqmusic:<ownerId>:<playlistId>` | String(JSON) | `null` | `qq/QqPlaylistStore.kt:110-112` | `qq/QqPlaylistStore.kt:124-128` | 详情缓存（≤30 条 LRU，`:82`） |
| `ncrust_qq_playlists` | `detail_at:qqmusic:<ownerId>:<playlistId>`（动态族，**第 7 族**） | Long | `0L`（`qq/QqPlaylistStore.kt:151`） | `qq/QqPlaylistStore.kt:151`（prune 遍历） | `qq/QqPlaylistStore.kt:129` | 详情时间戳 |
| `ncrust_qq_probe` | `stats` | String(JSON) | `null`（`qq/QqProbeStore.kt:67`） | `qq/QqProbeStore.kt:67` | `qq/QqProbeStore.kt:81` | debug 诊断统计 |
| `ncrust_report_gate` | `stats` | String(JSON) | `null`（`player/ReportGateStore.kt:70`） | `player/ReportGateStore.kt:70` | `player/ReportGateStore.kt:83` | debug 诊断统计 |

---

## 3. 设置界面当前布局结构（平铺列表 · 区块顺序）

**宿主**：`ui/screen/UserScreen.kt`（1566 行），单棵 `LazyColumn`（`:312-866`），宽屏限宽 720dp（`:313`），
底部 padding = `BottomOverlayInsetDp`（`:314`）。

**没有任何折叠/分组/二级页**：所有项都是同一层 `item { }`，靠 `SectionTitle`（`:966-974`）做视觉分节。
**唯一的二级界面是两个 Dialog**（不进导航图）：`ClearCacheConfirmDialog`（`:276-308`）、
`OfflineCacheManagerDialog`（`:268-274`，定义在 `ui/screen/OfflineCacheOverlay.kt:107`）。

| 顺序 | 区块（`item` 行号） | 标题来源 | 包含项（逐项 file:line） |
|---|---|---|---|
| 1 | 页头 `:318-332` | `strings.tabUser` | 「用户/我的」大字标题 |
| 2 | **账号块 · 网易云** `:335-352` | 无 `SectionTitle`（`ProfileBlock`） | 头像/昵称/UID；整块点击 → 已登录进 `AccountDialog`（`:227-244`）／未登录进扫码（宽屏）或 WebView 登录（窄屏）`ui/screen/UserScreen.kt:342-349` |
| 3 | **账号块 · QQ 音乐** `:354-424` | `strings.sourceQqAccount`（`QqAccountBlock` 内 `:1467`） | 登录态/VIP 角标 + 登录/登出 `:1468-1517`；手机号登录入口 `:1527-1534`；扫码可用性说明 `:1535-1540`；**debug-only** 取链诊断 `:1542-1551`、兜底统计 `:1556-1565` |
| 4 | **音质** `:426-460` | `strings.qualitySectionTitle` `:428` | ① Wi-Fi 音质 `:432-445` ② 移动网络音质 `:446-458`（附 FLAC 不支持提示 `:431`、`:436-438`） |
| 5 | **播放** `:462-632` | `strings.playbackSectionTitle` `:464` | ① 无缝播放 `:467-478` ② 播放时禁止熄屏 `:481-489` ③ 自动旋转 `:493-501` ④ 音频可视化 `:503-511` ⑤ 音乐人推荐 `:514-521` ⑥ 页面切换动效 `:526-531` ⑦ **歌词翻译** `:533-541` ⑧ **逐字动画模式** `:544-552` ⑨ **渐变质量** `:555-563` ⑩ **歌词字号** `:565-574` ⑪ **媒体面板歌词** `:577-585` ⑫ **AMLL 逐字歌词(TTML)** `:587-594` ⑬ **TTML 优先**（条件挂载）`:598-607` ⑭ **音译显示** `:610-618` ⑮ **动态字号（实验性）** `:622-630` |
| 6 | **外观** `:634-704`（一个 item 里塞了 4 个 `SectionTitle`） | 依次：`themeModeSectionTitle` `:636` / `themeSectionTitle` `:648` / `accentSourceSectionTitle` `:659` / `languageSectionTitle` `:690` | ① 主题模式三选一 `:637-645` ② 主题色六选一 `:649-655` ③ 主题色来源三选一 `:660-670` + 条件「重读系统色」按钮 `:672-687` ④ 语言下拉 `:691-702` |
| 7 | **后台运行** `:706-737` | 无 `SectionTitle`（裸 `Row`） | 「允许后台活动」跳系统设置 `:708-735`（`strings.batteryTitle`） |
| 8 | **自定义背景图** `:739-788` | `strings.bgSectionTitle` `:743` | 选图/更换 `:757-770`；条件「移除背景」`:771-786` |
| 9 | **存储与缓存** `:790-841` | `strings.storageSectionTitle` `:792` | ① 缓存占用 + 「清除缓存」` :793-811` ② 三项分账（音频/图片/其他）`:815-817` ③ **离线缓存管理**入口 `:820-839` |
| 10 | **关于** `:843-865` | 无 `SectionTitle`（裸 `Row`，`strings.aboutButton`） | 打开 About `:845-864` |

### 3.1 不在 UserScreen 里的设置入口（迁移时不能漏）

| 入口 | 位置 (file:line) | 影响的 key |
|---|---|---|
| 播放器内联音质选择（大屏 & 竖屏） | `ui/player/PlayerCard.kt:802-804`、`1058-1064` | `wifi_quality` / `mobile_quality`（经 `PlayerViewModel.setQualityPreference`） |
| 播放器内歌词字号 A- / A+ | `ui/player/PlayerCard.kt:870` | `lyrics_font_scale` |
| 库页「歌单」tab 布局切换 | `ui/screen/LibraryPlaylistsTab.kt:213`、`:507-545` | `library_playlist_layout` |
| 库页三个区块折叠 | `ui/screen/LibraryPlaylistsTab.kt:222`、`265`、`331` | `library_section_collapsed_*` |
| 离线缓存上限选择器 | `ui/screen/OfflineCacheOverlay.kt:206-247` | `offline_cache_mb` |
| 首启电池白名单弹窗 | `MainActivity.kt:386-412` | `battery_prompt_done` |
| 播放模式（循环/单曲/随机/顺序/无限） | `MainActivity` 的 `MainScreen`（不落盘于设置页，落 `ncrust_playback_state.play_mode`） | `play_mode` |
| 扫码/WebView 登录、登出 | `ui/screen/UserScreen.kt:253-266`、`MainActivity` WebView 路径 | `user_cookie` |
| QQ 登录（扫码 / 手机号） | `ui/screen/UserScreen.kt:1464-1516` | `qq_cookie` + QQ 资料字段 |

---

## 4. 依赖关系（逐条给代码依据）

### 4.1 「渐变质量」(`lyrics_sweep_quality`) 依赖「逐字动画模式」(`lyrics_word_animation`)

- **UI 侧无门控**：`ui/screen/UserScreen.kt:555-563` 的 `MetroDropdownRow` 无条件挂载 —— 模式选「关闭逐字」时这一行**照样可点**。
- **生效侧有门控**（代码依据）：
  - `ui/player/NcrustLyricsPanel.kt:677`：`mode == LyricsWordAnimationMode.OFF` → 直接走整行 `MetroText`，**提前 return**，后面所有扫过逻辑都不执行。
  - `ui/player/NcrustLyricsPanel.kt:704`：`mode == HARD_CUT` → `track = null`（不建扫过轨道）→ 绘制走 `:738-740` 的 `drawHardCut`。
  - `ui/player/NcrustLyricsPanel.kt:750`：**唯一**使用 `sweepConfig.softEdge` 的调用点，只在「非 HARD_CUT、非 OFF」分支里。
  - `ui/player/LyricsView.kt:497-499`：`buildSweepWindows` 在 `!wordByWordEnabled || mode == OFF` 时返回全空窗口表。
- **依赖强度**：**软依赖（有 UI 但可能完全无效）**。`AUTO/SOFT/EDGE` 在 `GRADIENT_SWEEP` 下才改变渲染；在 `HARD_CUT` / `OFF` 下用户改了设置但看不到任何变化。
- **迁移建议**：v2.8.0 分组时，把「渐变质量」标为「逐字动画模式 = 渐变扫过时可用」，或在 UI 上门控（条件挂载，不用 `alpha`，见 `UserScreen.kt:595-597` 的既有先例）。

### 4.2 「TTML 优先」(`lyrics_ttml_first`) 硬依赖「AMLL 逐字歌词」(`lyrics_ttml_enabled`)

- **UI 侧门控（唯一一处条件挂载设置行）**：`ui/screen/UserScreen.kt:598` `if (lyricsTtmlEnabled) { … }`，注释 `:595-597` 明确写「整行不挂载而不是 alpha 隐藏」，并引用 AGENTS.md 触摸陷阱第 1 条。
- **语义侧门控**：`lyric/LyricSourceChain.kt:69-70`
  ```kotlin
  !prefs.ttmlEnabled -> listOf(LyricSourceKind.YRC, LyricSourceKind.LRC)
  prefs.ttmlFirst   -> listOf(TTML, YRC, LRC)
  else              -> listOf(YRC, TTML, LRC)
  ```
  → `ttmlEnabled == false` 时 TTML **根本不进回退链**，`ttmlFirst` 的值不参与任何判定。
- **`LyricSourcePrefs` 的字段默认值**（`:30-33`）：`ttmlEnabled = true`、`ttmlFirst = true`，与 `LyricsDisplayPrefs` 的读默认一致。
- **依赖强度**：**硬依赖**（UI 已门控 + 逻辑已门控），是本次审计中唯一「UI 已正确门控」的依赖对。

### 4.3 「音质档位」(`wifi_quality` / `mobile_quality`) 依赖设备 FLAC 解码能力

- `ui/screen/UserScreen.kt:431`：`val flacUnsupported = !SongUrlFetcher.deviceSupportsFlac`
- `:436-438`、`:450-452`：命中 `lossless`/`hires`/`jyeffect` 档位时显示 `strings.qualityFlacUnsupportedHint`
- `:1220-1224`：`isFlacTierIndex()` 把档位索引映射回 level 字符串再问 `SongUrlFetcher.isFlacTier`
- **依赖强度**：**提示级依赖**（设置本身可写，但实际取链会降级）。

### 4.4 「音质档位」依赖迁移水位(`quality_ladder_version`)

见 §5.1。读盘只发生在 `NcrustApplication.kt:40`（进程启动、UI 之前）。

### 4.5 `lyrics_in_media_session` 与 `session_metadata_lyrics` 的**非**等价关系

- `lyrics_in_media_session`：**用户可见开关**（`UserScreen.kt:577-585`，默认 `false`），控制「当前歌词行要不要推给 PlaybackService」（`PlayerViewModel.kt:512`、`:820`、`lastMediaLyricLine`）。
- `session_metadata_lyrics`：**隐藏回退开关**（`PlaybackService.kt:1406`，默认 `true`），控制「会话 metadata 的副标题是否跟随歌词」。
- 代码依据：`player/MediaSessionMerge.kt:60` 注释明确把它称为「`PlaybackService` 里 `session_metadata_lyrics` 回退开关的那一侧」。
- **依赖强度**：**隐式耦合**（前者是产品开关、后者是回退阀）；迁移时必须一起决定归属，否则会出现「设置页关了歌词、媒体卡片仍然跟随」的困惑。

### 4.6 「离线缓存上限」(`offline_cache_mb`) 依赖进程重启

- `cache/OfflineAudioCache.kt:81-83` 注释：`SimpleCache` 的淘汰器在构造时固化，改上限只能重建实例，而重建会打断播放 → **只写 prefs**。
- `ui/screen/OfflineCacheOverlay.kt:99-105`（类 KDoc）与 `ui/i18n/zh_CN.kt:130` 的提示文案「改动在下次启动后生效」同源。
- **依赖强度**：**生效时机依赖**（值可写、范围受夹取，但本进程内不生效）。

### 4.7 「自定义背景图」(`custom_bg_enabled`) 依赖文件存在

- `ui/theme/BackgroundImageManager.kt:56-58`：`isActive` = `getBoolean(KEY_ENABLED, false) && backgroundFile(context).isFile`
- `ui/screen/UserScreen.kt:745`：UI 用 `BackgroundImageManager.isActive(context)` 决定显示「更换/移除」还是「选择图片」
- **依赖强度**：**硬依赖文件**。开关为 `true` 但文件被删 → 界面回到「选择图片」。

### 4.8 「主题色来源 = 系统」依赖 API 31+

- `ui/theme/AccentSource.kt:30-31`（`systemAccentSupported`）、`:50-55`（低版本返回 `null`）
- `ui/screen/UserScreen.kt:661`（把 `systemEnabled` 传给选择器）、`:672`（`SYSTEM` 且受支持才显示「重读系统色」）
- **依赖强度**：**平台能力依赖**。跨设备恢复备份时 `accent_source = "SYSTEM"` 落到 API < 31 会被 `systemAccentColor` 挡住回落预设色（`AccentSource.kt:47-49` 注释明确写了这个场景）。

### 4.9 「动态字号」与「歌词字号倍率」同帧相乘

- `ui/player/PlayerCard.kt:868-872`：`fontScale = lyricsFontScale` 与 `dynamicFontEnabled = showDynamicLyricFont` 同时传给歌词面板。
- **依赖强度**：**组合运算**（不是门控），两者都开时最终字号 = 倍率 × 动态倍率；无上限夹取（`DynamicLyricFont` 的倍率范围见 `lyric/DynamicLyricFont.kt:40` 起，**未在本次探针中逐行确认其上下界 → 标为「未确认」**，见 §8）。

### 4.10 冷启动读盘顺序（隐性依赖）

- `MainActivity.kt:211`（`VisualizerSetting.read`）、`:216`（`KeepScreenOnSetting.read`）、`:552`（`RotationSetting.read`）—— 三个进程内镜像必须在任何 UI 读之前就位。
- 代码依据：`MainActivity.kt:213-215` 的 HF2 注释（「`KeepScreenOnSetting.state` 是进程内镜像，只在设置页里读过盘 … 真机（S6）实测确认过这个 bug：prefs 里 `keep_screen_on=false`，但播放时 flag 照样被挂上」）。
- `RotationSetting` / `KeepScreenOnSetting` / `VisualizerSetting` 三者都是「读一次、之后只走内存」的单例（`loadedFromDisk` 标志，`RotationSetting.kt:45`、`KeepScreenOnSetting.kt:39`、`AudioVisualizer.kt:43`）。
- **迁移风险**：把这三个项迁到新框架时，**必须保留「onCreate 先 read」这一步**，否则老 bug 回归。

---

## 5. 迁移历史（迁移函数 file:line + 触发时机 + 幂等性）

### 5.1 `QualityLadder.migrate` —— 音质档位 7 档 → 8 档（新增 `jymaster`）

| 项 | 内容 |
|---|---|
| **函数** | `player/QualityLadder.kt:53-63` |
| **触发时机** | **进程启动**，`NcrustApplication.kt:40`（`onCreate`，在任何 UI/播放之前）；`ClientIdentity.init` 之后 |
| **迁移内容** | `KEY_VERSION`（`quality_ladder_version`）默认读到 `1`，`CURRENT_VERSION = 2`（`:33`）。对 `wifi_quality` / `mobile_quality` 两个 key：**仅当 key 存在**（`prefs.contains(key)`，`:57`）且 `old >= OLD_INDEX_FIRST_SHIFTED = 6`（`:36`、`:59`）时 `+1` |
| **幂等性** | **幂等**。判据 `prefs.getInt(KEY_VERSION, 1) >= CURRENT_VERSION` 直接 `return`（`:54`）；迁移完成后立刻写 `KEY_VERSION = 2`（`:61`）。第二次启动直接短路 |
| **不迁移的情形** | key 不存在 → `continue`（`:57`），不改写；索引 `< 6` → 位置未变，不改（`:59`） |
| **副作用** | 迁移成功时 `Log.i(TAG, "quality ladder migrated to v2 (8 levels)")`（`:62`） |
| **注释依据** | `QualityLadder.kt:9-13`（v1.2.0 · A2 修改说明）、`:50-52`（幂等性声明） |

### 5.2 `LyricsDisplayPrefs.readWordAnimation` —— 布尔键 → Int 三模式（一次性消费）

| 项 | 内容 |
|---|---|
| **函数** | `lyric/LyricsDisplayPrefs.kt:133-144` |
| **触发时机** | **读时惰性迁移**（不是启动时）。首次读取点是 `ui/viewmodel/PlayerViewModel.kt:492-495`（ViewModel `init`）与 `ui/screen/UserScreen.kt:161` |
| **迁移内容** | 新键 `lyrics_word_animation` 不存在（`:134` `!prefs.contains(...)`）时，读旧键 `lyrics_word_by_word`（默认 `true`，`:137`）：`true` → `GRADIENT_SWEEP(0)`；`false` → `OFF(2)`；随后**立刻写回新键**（`:142`） |
| **幂等性** | **幂等**。第一次运行后新键存在，后续读取走 `:135` 的正常分支（含 `normalize` 越界回落） |
| **旧键处理** | **不删除**。`KEY_WORD_BY_WORD_LEGACY` 的注释（`:70-71`）写「仅用于一次性迁移，不再写入」；`grep` 确认全仓库**只有这一处读**、**零处写**。因此旧键会永久留在 `ncrust_settings.xml` 里 |
| **副作用** | 写盘一次（`:142` 的 `apply()`）；这是**唯一**一处「读函数有写副作用」的设置项 |
| **相关 UI 文案** | `ui/i18n/Strings.kt:1134` 注释：「（`lyricsWordAnimationLabel`），这一项只为迁移路径保留，已无 UI 入口」—— 指旧的 `lyricsWordByWordLabel` |
| **注释依据** | `LyricsDisplayPrefs.kt:126-132`、`TASK.md:34`（D2：「设置页把原来的『逐字歌词』开关换成三选一下拉，而不是并列两个控件」） |

### 5.3 `offline_cache_mb` 的「非法值回落默认」——**不是版本迁移**

| 项 | 内容 |
|---|---|
| **函数** | `cache/OfflineAudioCache.kt:66-73`（`maxBytes`） |
| **触发时机** | 每次构造 `SimpleCache` 时（`get()`，`:94-107`），以及设置页回显（`maxMb`，`:76`） |
| **行为** | `getInt(KEY_MAX_MB, 512)` 包在 `runCatching` 里（`:67-71`），异常回落 512；随后 `if (mb in 64..8192) mb*MB else DEFAULT_MAX_BYTES`（`:72`） |
| **幂等性** | **纯读、无写**，因此天然幂等；**不修正盘上的脏值**（脏值会一直留在 prefs 里，每次都回落） |
| **性质** | 与 §5.1 / §5.2 不同，**这不是版本迁移**，是「脏键防御」。列在此处是因为任务书把它当作迁移项提出 —— 结论：**代码里没有对应的历史迁移逻辑**，只有回落 |

### 5.4 已确认**不存在**迁移的设置项（避免重复劳动）

| 设置项 | 代码依据 |
|---|---|
| `library_playlist_layout` | `ui/screen/PlaylistLayoutSetting.kt:83-86`：「这是一个**全新键**：v2.6.0 之前不存在任何布局偏好（探针 §5.4 的 `grep` 已证），所以没有老形状可认，也就没有 `migrate()`」 |
| `library_section_collapsed_*` | `ui/screen/LibrarySectionFoldSetting.kt:105-107`：「**不需要迁移逻辑**：三个 key 都是 v2.6.0 新增，没有旧形状（探针 §5 已证）」 |
| `page_transition_enabled` | `ui/theme/PageTransitionSetting.kt:43-58`：有「v1（无键）→ v2（显式布尔）」的**语义迁移决策**（老用户默认拿到「开」），但**纯读、无副作用、不写回**（`readEnabled` 是 `resolveEnabled(readStored(prefs))`，`:95`） |
| `session_metadata_lyrics` | `player/PlaybackService.kt:1401-1403`：「**缺失即默认 true** … 也没有『加字段 = 加迁移逻辑』的问题（这不是跨版本存活的缓存表，缺 key 就是默认值）」 |
| `lyrics_romanization` / `lyrics_dynamic_font` | `lyric/LyricsDisplayPrefs.kt:88-91`（「与歌词源、缓存、请求全都无关」）、`:93-98`；默认 `false` 是刻意的「老用户不该凭空多出一行小字」策略（`:181-184`） |

### 5.5 与设置项相邻、但属于**数据/缓存**的迁移（备查，不属设置页迁移范围）

| 载体 | 迁移/版本逻辑 | file:line | 幂等性 |
|---|---|---|---|
| `ncrust_library.saved_songs` | 信封版本 `SCHEMA_VERSION = 2`，认 v1 裸数组 | `library/SavedSongCodec.kt:61`；读路径 `library/LibraryManager.kt:158` | 读时判定，反序列化失败逐条容错（`LibraryManager.kt:150-152` 注释） |
| `ncrust_library.saved_albums` | `SCHEMA_VERSION = 3` | `library/SavedAlbumCodec.kt:70` | 同上 |
| `search_history.*` | `SCHEMA_VERSION = 2`；`source == null` 语义 = 「v2.5.4 之前的老条目」 | `library/SearchHistoryCodec.kt:104`；语义 `library/SearchHistoryManager.kt:47-50` | 读时判定 |
| `ncrust_playback_state.song_positions` | `SCHEMA_VERSION = 2` | `player/PlaybackPositionCodec.kt:62`；读写 `PlaybackStateManager.kt:174`、`237` | 读时判定 |
| `ncrust_qq_playlists.*` | `SCHEMA_VERSION = 2`；`version < 2` ⇒ `LegacyNoOwner`（**拒绝解释**而不是猜） | `playlist/PlaylistCacheCodec.kt:62`、`:373-378`；写 `qq/QqPlaylistStore.kt:96`、`130` | 写时刷新水位 |
| `ncrust_local_playlists.*` | `SCHEMA_VERSION = 1`；`version > SCHEMA_VERSION` ⇒ 返回空列表（拒绝解释） | `local/LocalPlaylistCodec.kt:66`、`:166-167` | 读时判定 |
| `ncrust_match_cache.*` | `SCHEMA_VERSION = 1`；`Legacy` / `Future` 判定 | `crosssource/MatchCacheCodec.kt:51`、`:128-129`、`:184`；写 `crosssource/MatchCacheStore.kt:191` | 读时判定 |
| `ncrust_offline.tracks` | `SCHEMA_VERSION = 2` | `cache/OfflineTrackCodec.kt:93` | 读时判定 |
| `ncrust_qq_probe.stats` | `SCHEMA_VERSION = 1`；缺字段 → `null` → 0 | `qq/QqFallbackStats.kt:142`、`:104` | 读时判定 |
| `ncrust_report_gate.stats` | `SCHEMA_VERSION = 1`；`canonical(null)` 归零 | `player/ReportGate.kt:159`、`:127`；`player/ReportGateStore.kt:39-42` | 读时判定 |
| `ncrust_lyrics_cache.entries` | **字段级迁移**（不是版本号）：`ttml`/`romalrc` 缺失按 miss 重取一次 | `lyric/LyricsCache.kt` 的 `needsRomalrcRefetch`；策略见 `AGENTS.md`「歌词缓存字段迁移策略」 | 自愈、一次性 |

---

## 6. 分组映射表（账号 / 通用 / 播放 / 音质 / 歌词 / 存储与缓存 / 关于）

> **规则**：§2.1 + §2.2 的**每一项**都必须落在某个分组里，不丢项。
> `→` 后面的数字是该组内的顺序建议（大致沿用当前平铺顺序）。
>
> **覆盖校验（口径 C 的 42 个 key，逐个落组，无遗漏 · 单归属计数）**
>
> | 分组 | 口径 C 项数 | 具体 key |
> |---|---|---|
> | 账号 | **2** | `user_cookie`、`qq_cookie`（另**附带** 8 个账号派生缓存 key，见 §2.3 —— 不算设置项，但登录/登出必须一起清） |
> | 通用 | **8** | `theme_color_index`、`theme_mode`、`accent_source`、`language_code`、`custom_bg_enabled`、`page_transition_enabled`、`auto_rotate`、`battery_prompt_done` |
> | 播放 | **10** | `gapless_playback`、`keep_screen_on`、`audio_visualizer`、`artist_reco_enabled`、`artist_reco_target_id`、`artist_reco_anchor_ids`、`artist_reco_auto_anchor_ids`、`artist_reco_auto_anchor_at`、`session_metadata_lyrics`、`live_update_enabled` |
> | 音质 | **3** | `wifi_quality`、`mobile_quality`、`quality_ladder_version` |
> | 歌词 | **13** | `lyrics_translation`、`lyrics_word_animation`、`lyrics_word_by_word`、`lyrics_sweep_quality`、`lyrics_sweep_fade_em`、`lyrics_sweep_inactive_alpha`、`lyrics_sweep_easing`、`lyrics_font_scale`、`lyrics_in_media_session`（**多归属**：§6.3 也列它）、`lyrics_ttml_enabled`、`lyrics_ttml_first`、`lyrics_romanization`、`lyrics_dynamic_font` |
> | 存储与缓存 | **5** | `offline_cache_mb`、`library_playlist_layout`、`library_section_collapsed_local`、`library_section_collapsed_netease`、`library_section_collapsed_qq` |
> | 关于 | **0** | §6.7（天然为空，正确结果） |
> | **不属于任何分组（§6.8 显式列出）** | **1** | `live_update_probed`（纯内部「只打一次日志」标记） |
>
> **加总：2 + 8 + 10 + 3 + 13 + 5 + 0 + 1 = 42 ✔**（与 §7.1 的口径 C 完全一致）
>
> **另有 3 个跨文件 / 非口径 C 的项需要显式说明**：
> - `play_mode`（`ncrust_playback_state.play_mode`，§2.3）**不是**口径 C 成员（不在 `ncrust_settings`），但语义是播放偏好且跨启动记忆 → §6.3 收它，并标注「入口不在设置页」。
> - `client_device_id`（`ncrust_device`）与 `qq_device_seed`（`ncrust_qq_prefs`）**不是**口径 C 成员，且**不属于任何用户分组**（§6.8 逐条给出理由：它们标识设备，不标识账号）。
>
> **唯一的双归属项**：`lyrics_in_media_session` —— 上表把它计在「歌词」（口径 C 单归属计数），§6.3 同时列出它并注明跨界。

### 6.1 账号（4 行 / 10 个 key）

| key | 归属理由（代码依据） |
|---|---|
| `user_cookie` | `auth/CookieManager.kt:8`，唯一读写点 `:13`/`:17`/`:26`；UI `ui/screen/UserScreen.kt:335-352` |
| `qq_cookie` | `qq/QqAuthStore.kt:64`；UI `ui/screen/UserScreen.kt:354-424` |
| QQ 资料缓存（`qq_nick`/`qq_uid`/`qq_vip_type`/`qq_vip_expire_at`/`qq_profile_at`） | 与 `qq_cookie` 同文件、同登录态、同清空路径（`qq/QqAuthStore.kt:100-105`）→ **跟随账号分组，但不应出现在 UI**（派生数据） |
| 网易云会员缓存（`is_vip`/`checked_at`/`red_vip_level`） | `auth/NeteaseVipStore.kt:65-71`；登录/登出必须一起清（`ui/screen/UserScreen.kt:197`、`239` 注释明确「留着会让下一个登录的账号按**上一个账号**的会员状态排序」）→ **跟随账号分组，不出现在 UI** |

> **注**：`ncrust_device.client_device_id`（`network/ClientIdentity.kt:24`）与 `ncrust_qq_prefs.qq_device_seed`（`qq/QqIdentity.kt:33`）**不归账号**：前者是设备指纹（`QqIdentity.kt:28-29` 明确「不含任何账号信息，清空 QQ 账号也不会清掉它（它标识的是设备，不是账号）」），归 **通用 / 内部**。

### 6.2 通用（8 项）

| key | 归属理由 |
|---|---|
| `theme_color_index` → 1 | `ui/screen/UserScreen.kt:648-655`（外观区块） |
| `theme_mode` → 2 | `:637-645` |
| `accent_source` → 3 | `:659-670` |
| `language_code` → 4 | `:690-702` |
| `custom_bg_enabled` → 5 | `:743-788` |
| `page_transition_enabled` → 6 | `:526-531`（当前在「播放」区块，但语义是界面动效 → **建议迁到通用**，见 §6.5） |
| `auto_rotate` → 7 | `:493-501`（当前在「播放」区块，但语义是窗口方向策略、`MainActivity.applyOrientationPolicy` 消费 → **建议迁到通用**） |
| `battery_prompt_done` → 8（**不出现在 UI**） | `MainActivity.kt:390` 首启一次性标记；语义是「已问过」，不是用户选项。**保留为内部标记，只在通用组的「后台运行」入口里体现为跳转按钮**（`:706-737`） |

### 6.3 播放（9 行 / 12 个 key）

| key | 归属理由 |
|---|---|
| `gapless_playback` → 1 | `ui/screen/UserScreen.kt:467-478` |
| `keep_screen_on` → 2 | `:481-489` |
| `audio_visualizer` → 3 | `:503-511` |
| `artist_reco_enabled` → 4 | `:514-521`（首页内容推荐；当前就在播放区块） |
| `lyrics_in_media_session` → 5 | `:577-585`（**语义争议项**：它是歌词显示项还是播放项？当前在播放区块；播放器/通知栏消费 → 见 §6.5） |
| `session_metadata_lyrics` → 6（**当前无 UI，建议保留为隐藏高级项或补 UI**） | `player/PlaybackService.kt:1406`；与上一项同消费方 |
| `live_update_enabled` → 7（**当前无 UI**） | `player/LiveUpdateNotifier.kt:63`；注释 `:46` 说「用户可在 prefs 里关掉」，但**没有设置行** → 补 UI 的最强候选 |
| `play_mode` → 8（**不在设置页**） | `player/PlaybackStateManager.kt:49`、`:313`、`:317`；UI 入口在播放器/`MainScreen`，跨启动记忆。**列为播放组成员但标注「入口不在设置页」** |
| `artist_reco_*`（`target_id` / `anchor_ids` / `auto_anchor_ids` / `auto_anchor_at`） → 9（**全部无 UI**） | `reco/ArtistReco.kt:78-94`；`grep setTarget`/`setManualAnchors` **零调用方** → 当前只能改 prefs 文件。列为播放组的隐藏子项 |

### 6.4 音质（3 项）

| key | 归属理由 |
|---|---|
| `wifi_quality` → 1 | `ui/screen/UserScreen.kt:432-445` |
| `mobile_quality` → 2 | `:446-458` |
| `quality_ladder_version` → 3（**内部水位，不出现在 UI**） | `player/QualityLadder.kt:29`、`:54`、`:61`。**必须跟随音质组一起迁移**，否则 §5.1 的迁移会重跑一次（后果：用户选的杜比会再 +1 变成越界） |

### 6.5 歌词（11 行 / 13 个 key，其中 `lyrics_in_media_session` 与 §6.3 双归属）

| key | 归属理由 |
|---|---|
| `lyrics_translation` → 1 | `ui/screen/UserScreen.kt:533-541` |
| `lyrics_word_animation` → 2 | `:544-552` |
| `lyrics_word_by_word` → 3（**遗留键，无 UI**） | `lyric/LyricsDisplayPrefs.kt:71`、`:137`。**迁移时必须一起搬**，否则老用户的「关掉过逐字」意图会丢（会退回默认「渐变扫过」） |
| `lyrics_sweep_quality` → 4 | `:555-563`；依赖 §4.1 |
| `lyrics_font_scale` → 5 | `:565-574` |
| `lyrics_in_media_session` → 6 | `:577-585`（**注**：若按 §6.5 的「播放」归属，则此处只留 5 项 —— 两组都说得通，见 §6.6 的显式标注） |
| `lyrics_ttml_enabled` → 7 | `:587-594` |
| `lyrics_ttml_first` → 8 | `:598-607`；硬依赖 §4.2 |
| `lyrics_romanization` → 9 | `:610-618` |
| `lyrics_dynamic_font` → 10 | `:622-630`；**实验性** |
| `lyrics_sweep_fade_em` / `lyrics_sweep_inactive_alpha` / `lyrics_sweep_easing` → 11（**三个隐藏高级覆盖键**） | `lyric/LyricsDisplayPrefs.kt:112-114`、`:265-272`。**建议：不补 UI，保留为「隐藏高级项」**（注释 `:109-111` 明确「普通用户永远不会碰到它们」） |

> 上表实际列出 **13 个 key**（10 个有 UI + `lyrics_word_by_word` + 3 个 sweep 覆盖键）。为满足「每一项都落组」，此处按 key 计数。

### 6.6 存储与缓存（5 项）

| key | 归属理由 |
|---|---|
| `offline_cache_mb` → 1 | `ui/screen/UserScreen.kt:820-839` + `ui/screen/OfflineCacheOverlay.kt:206-247`。（「清除缓存」那一行的行为与它无关，`UserScreen.kt:818-819` 注释明确「这一行只是入口，『清除缓存』那一行的行为一个字都没改」） |
| `library_playlist_layout` → 2（**建议新增分组**：库页 / 列表显示） | `ui/screen/LibraryPlaylistsTab.kt:213`、`:507-545`。与「存储」无关，与「通用外观」语义更近 —— 见 §6.7 |
| `library_section_collapsed_local` → 3（同上） | `ui/screen/LibraryPlaylistsTab.kt:222` |
| `library_section_collapsed_netease` → 4（同上） | `ui/screen/LibraryPlaylistsTab.kt:265` |
| `library_section_collapsed_qq` → 5（同上） | `ui/screen/LibraryPlaylistsTab.kt:331` |

> **注意**：这 5 项是「无 UI 入口 / 入口不在设置页」之外的**弱归属**。`offline_cache_mb` 是唯一真正属于「存储与缓存」的设置项；另外 4 项是库页显示偏好，塞进「存储与缓存」是**语义错配**。

### 6.7 关于（0 项）

| 项 | 说明 |
|---|---|
| **无任何持久化设置项** | 「关于」是一个只读入口：`ui/screen/UserScreen.kt:843-865` 的 `Row` → `onOpenAbout`；`AboutScreen` 读 `BuildConfig.VERSION_NAME`（`AGENTS.md`：「never hardcode a version constant」） |
| 结论 | **「关于」分组在本次清单中为空**。这是**正确结果，不是遗漏** —— 关于页没有任何可配置项，因此不需要迁移清单条目 |

### 6.8 显式列出：**不属于任何分组**的项

> 任务书要求「若某项不属于任何分组要显式列出并说明」。以下逐条给出。

| 项 | 为什么不属于 7 个分组 | 处置建议 |
|---|---|---|
| `ncrust_device.client_device_id` | **内部指纹**，无用户语义（`network/ClientIdentity.kt:23-24`、`:49-54`）。用户既看不到也不该改。归「通用」会把设备标识混进用户可见设置 | **单列「内部 / 设备身份」**，或明确标注为「不属于任何用户分组」 |
| `ncrust_qq_prefs.qq_device_seed` | 同上（`qq/QqIdentity.kt:32-33`、`:28-29` 注释：「它标识的是设备，不是账号」） | 同上 |
| `ncrust_lyrics_cache.entries` / `ttml:<songId>` | **缓存数据**，不是设置。归「存储与缓存」会让该分组混入「可被 LRU 淘汰的数据」（`lyric/LyricsCache.kt:64`、`:78`） | 单列「缓存数据」，或在「存储与缓存」里只体现为「清除」按钮的作用域 |
| `ncrust_offline.urls` / `tracks` | 同上（`cache/OfflineUrlStore.kt:108`、`cache/OfflineLibrary.kt:174`） | 同上；但**离线缓存的容量上限是设置项**（`offline_cache_mb`），二者必须一起迁移 |
| `ncrust_library.saved_songs` / `saved_albums` / `liked_ids` | **用户数据**（`library/LibraryManager.kt:76-78`），不是设置 | 单列「用户数据」 |
| `search_history.songs` / `albums` / `artists` | 同上（`library/SearchHistoryManager.kt:36`） | 同上 |
| `ncrust_playback_state.*`（13 个 key，除 `play_mode`） | **运行状态**（`player/PlaybackStateManager.kt:31-52`），不是用户偏好 | `play_mode` 建议归「播放」（§6.3）；其余 12 个单列「运行状态」 |
| `ncrust_home_cache.*`（5 个 key） | 磁盘快照缓存（`cache/HomeSnapshot.kt:40-44`） | 单列「缓存数据」 |
| `ncrust_match_cache.*` / `ncrust_qq_playlists.*` / `ncrust_local_playlists.*` | 缓存 + 用户数据（`crosssource/MatchCacheStore.kt:45`、`qq/QqPlaylistStore.kt:52`、`local/LocalPlaylistStore.kt:56`） | 分别归「缓存数据」/「用户数据」 |
| `ncrust_qq_probe.stats` / `ncrust_report_gate.stats` | **debug 诊断统计**，且「本地统计不上报」（`ui/screen/UserScreen.kt:370`、`:382-383`）。不属于任何用户分组 | 单列「诊断」 |
| `live_update_probed` | 纯内部「只打一次日志」标记（`player/LiveUpdateNotifier.kt:64-65`） | 单列「内部」 |
| 完整的「关于」组 | **空组**，见 §6.7 | 保留空组，标注原因 |

### 6.9 新增分组建议

| 建议分组 | 收纳的项 | 理由（代码依据） |
|---|---|---|
| **库页 / 列表显示** | `library_playlist_layout`、`library_section_collapsed_local`、`library_section_collapsed_netease`、`library_section_collapsed_qq` | 四项全部由 `ui/screen/LibraryPlaylistsTab.kt` 读写（`:213`、`:222`、`:265`、`:331`），**只在库页生效**；塞进「通用」会让通用组膨胀，塞进「存储与缓存」是语义错配 |
| **歌词（高级 / 实验性）** | `lyrics_sweep_fade_em`、`lyrics_sweep_inactive_alpha`、`lyrics_sweep_easing`、`lyrics_dynamic_font` | 前三项：`lyric/LyricsDisplayPrefs.kt:109-111` 明确「留这几个键是为了**不重新构建**就能在真机上扫参数 … 普通用户永远不会碰到它们」；第四项：`:93-98` 与 UI 文案 `ui/i18n/zh_CN.kt:44` 都写「实验性」 |
| **内部 / 设备身份** | `client_device_id`、`qq_device_seed`、`live_update_probed`、`battery_prompt_done` | 四项都没有用户语义（§6.8）；单列可避免污染用户可见分组 |
| **诊断（debug-only）** | `ncrust_qq_probe.stats`、`ncrust_report_gate.stats` | 只在 `BuildConfig.DEBUG` 下由 `ui/screen/UserScreen.kt:371-394` 的入口触达 |
| **缓存数据 / 用户数据** | 见 §6.8 | 与「设置」严格分开，避免「清缓存」误伤用户数据（`local/LocalPlaylistStore.kt:44-49` 已就「删歌单又回来」踩过坑） |

---

## 7. 统计

### 7.1 总量

| 指标 | 数值 | 依据（可复算） |
|---|---|---|
| **SharedPreferences 文件数** | **16** | §1 |
| **`const val KEY*` 声明数** | **76 处** | `grep -rh 'const val KEY' --include="*.kt" app/src/main/java \| wc -l` → 76。其中 **4 处**是 `qq/QqCookie.kt:29-35` 的 cookie 字段名（`qqmusic_key`/`qm_keyst`/`uin`/`wxuin`），**不是 prefs key**；另有 **2 组值重名**（`stats` 出现 2 次：`ReportGateStore.kt:44` + `QqProbeStore.kt:41`；`schema_version` 出现 2 次：`MatchCacheStore.kt:48` + `QqPlaylistStore.kt:187`） |
| **唯一 prefs key 常量值** | **70 个** | 76 − 4（cookie 字段名）= 72；再 − 2（两组重名合并）= **70** |
| **无常量的字面量 key** | **9 个** | `battery_prompt_done`（`MainActivity.kt:390`）、`gapless_playback`（`ui/screen/UserScreen.kt:148`）、`live_update_probed`（`player/LiveUpdateNotifier.kt:64`）、`lyrics_in_media_session`（`ui/screen/UserScreen.kt:164`）、`lyrics_translation`（`ui/screen/UserScreen.kt:156`）、`session_metadata_lyrics`（`player/PlaybackService.kt:1406`）、`songs`/`albums`/`artists`（`library/SearchHistoryManager.kt:139-143` 的 `keyFor`） |
| **枚举构造参数形式的 key** | **3 个** | `library_section_collapsed_local` / `_netease` / `_qq`（`ui/screen/LibrarySectionFoldSetting.kt:36-45`） |
| **全仓库静态可命名 key 总数** | **82 个** | 70 + 9 + 3 = 82（`wifi_quality` / `mobile_quality` 既有常量又有字面量用法，已去重；故 82 < 70+9+3 的直觉值） |
| **`ncrust_settings` 的 key 总数** | **40** | 27 个有 UI（§2.1）+ 13 个无 UI（§2.2） |
| **用户可配置设置项（有 UI 入口）** | **27** | §2.1 全部 27 行 |
| **用户可配置设置项（含无 UI 的隐藏项）** | **40**（`ncrust_settings` 全域） | §2.1 + §2.2 |
| **动态 key 族（无法静态计数）** | **7 族** | `ttml:<songId>`（`lyric/LyricsCache.kt:75`）、`tracks:<source>:<ownerId>:<playlistId>`（`local/LocalPlaylistStore.kt:63-66`）、`ncrust_match_cache` 条目键（`crosssource/MatchCacheStore.kt:174`）、`list:qqmusic:<ownerId>`（`qq/QqPlaylistStore.kt:64-65`）、`list_at:qqmusic:<ownerId>`（`:68-69`）、`detail:qqmusic:<ownerId>:<playlistId>`（`:72-73`）、`detail_at:qqmusic:<ownerId>:<playlistId>`（`:135-136`） |

> **口径说明（重要，避免误用）**：上表「全仓库设置项总数」有歧义，本次采用**三口径并列**，不做单一数字：
> - **口径 A（严格设置页语义）**：`ncrust_settings` 中有 UI 入口的 **27 项**。
> - **口径 B（+ 隐藏/内部键）**：`ncrust_settings` 全部 **40 项**。
> - **口径 C（+ 账号凭证）**：口径 B **+ `user_cookie` + `qq_cookie` = 42 项**。
>
> **本探针推荐迁移清单以口径 C（42 项）为准**，因为「账号」是任务书七大分组之一，账号凭证必须进清单；其余 prefs 文件的 key 是数据/缓存（§2.3），单独维护。

### 7.2 迁移逻辑统计

| 指标 | 数值 | 明细 |
|---|---|---|
| **有版本迁移逻辑的设置项** | **3 项**（涉及 **4 个 key**） | `wifi_quality` / `mobile_quality`（§5.1）、`lyrics_word_animation` + `lyrics_word_by_word`（§5.2） |
| **有「脏值回落默认」防御的设置项** | **≥ 18 项** | `offline_cache_mb`（§5.3）、`lyrics_*` 全部（`LyricsDisplayPrefs.kt:201-202` 的 `readBooleanSafely`、`:34`/`:55` 的 `normalize`、`:205` 的 `coerceIn`）、`theme_mode`（`ThemeManager.kt:46`）、`accent_source`（`AccentSource.kt:36`）、`play_mode`（`PlaybackStateManager.kt:313`）、`library_playlist_layout`（`PlaylistLayoutSetting.kt:127`）、`library_section_collapsed_*`（`LibrarySectionFoldSetting.kt:138`）、`page_transition_enabled`（`PageTransitionSetting.kt:90-92`） |
| **有迁移水位标记 key** | **1 项** | `quality_ladder_version` |
| **数据/缓存侧的 SCHEMA 版本迁移** | **10 处** | §5.5 |

### 7.3 无 UI 入口的项（迁移时必须逐条决策）

| # | key | 建议 |
|---|---|---|
| 1 | `lyrics_word_by_word` | **必须搬**（迁移源），搬完可保留或清理 |
| 2 | `lyrics_sweep_fade_em` | 保留为隐藏高级项（§6.9） |
| 3 | `lyrics_sweep_inactive_alpha` | 同上 |
| 4 | `lyrics_sweep_easing` | 同上 |
| 5 | `session_metadata_lyrics` | 补 UI（与 `lyrics_in_media_session` 并列）或保留隐藏 |
| 6 | `live_update_enabled` | **补 UI 的最强候选**（注释已声称「用户可在 prefs 里关掉」） |
| 7 | `artist_reco_target_id` | 补 UI 或明确标注「仅手工配置」 |
| 8 | `artist_reco_anchor_ids` | 同上 |
| 9 | `battery_prompt_done` | 保留内部标记 |
| 10 | `quality_ladder_version` | **必须搬**（迁移水位） |
| 11 | `artist_reco_auto_anchor_ids` | 派生数据，**不迁移**（7 天 TTL 自动重建） |
| 12 | `artist_reco_auto_anchor_at` | 派生数据，**不迁移** |
| 13 | `live_update_probed` | 内部标记，可丢 |

---

## 8. 无法确认 / 需要真机验证的项

| # | 项 | 状态 | 说明 |
|---|---|---|---|
| 1 | `DynamicLyricFont` 的倍率上下界 | **未确认** | 本次探针只确认了 `ui/player/PlayerCard.kt:868-872` 把 `fontScale` 与 `dynamicFontEnabled` 同帧传入，**未逐行读完** `lyric/DynamicLyricFont.kt` 的倍率表与是否与 `lyrics_font_scale` 做夹取。若要做「组合后的字号上限」验收，需要补一次该文件的精读 |
| 2 | `strings.qualityOptions` 的实际文案与顺序 | **未确认（本次未展开 i18n 文件）** | 已确认 `QualityLadder.LEVELS`（`player/QualityLadder.kt:42-44`）是权威 8 档顺序，且 comment `:39-40` 声明「索引与 i18n qualityOptions 顺序、以及 eapi level 取值一一对应」。**8 个 locale 文件的实际 listOf 内容未逐条核对**（`ui/i18n/Strings.kt:1115` 声明类型） |
| 3 | `SweepEasing` 的枚举成员与 ordinal 顺序 | **未确认** | `lyric/LyricsDisplayPrefs.kt:269-272` 引用 `SweepEasing.SMOOTH` / `.EASE_OUT` / `.LINEAR`，但本次未打开 `SweepEasing` 的定义文件确认 ordinal 值。若要把 `lyrics_sweep_easing` 暴露成 UI，必须先确认 |
| 4 | `lyrics_sweep_*` 三键是否曾被任何历史版本写入过 | **未确认** | `grep` 确认当前**零写入点**；是否曾有写入点已被删除，需要 `git log -S` 反查（本次未做） |
| 5 | `live_update_enabled` 是否有过 UI 入口 | **未确认** | 同上，需要 `git log -S` 反查 |
| 6 | `ncrust_settings` 是否曾被 `clear()` 过 | **已确认「当前没有」** | `grep -rn "ncrust_settings" \| grep clear` 无命中；但历史版本是否有过，未反查 |
| 7 | `AppWarmup.PREFS_FILES` 只列 7 个文件是否有意为之 | **部分确认** | 代码注释（`warmup/AppWarmup.kt:83-84`）自称「全部 SharedPreferences 文件名」，但实际漏 9 个。是「有意只预热热路径」还是「注释陈旧」—— **注释措辞支持后者**，但未与作者确认 |
| 8 | 设置页各行的**渲染后**触控高度 | **未验证（静态审计无法确认）** | 只读代码可确认 `SettingSwitchRow` 整行 `toggleable`（`ui/screen/UserScreen.kt:1003-1007`）+ `padding(vertical = 12.dp)`（`:1008`），实际高度依赖 `bodyMedium` 行高。若要验收 §3 的布局改造，需要真机测量 |

---

## 9. 迁移施工检查清单（从本文档直接派生）

- [ ] `quality_ladder_version` 与音质两项**必须同批迁移**，否则 §5.1 的迁移会重跑（用户档位再 +1 → 越界 → `getOrElse` 兜底成别的档位）。
- [ ] `lyrics_word_by_word` **必须一起搬**，且搬完后**保留读一次**的迁移语义（或一次性在迁移脚本里换算成 `lyrics_word_animation`）。
- [ ] `MainActivity.onCreate` 里的三次 `read()`（`VisualizerSetting` / `KeepScreenOnSetting` / `RotationSetting`，`:211`/`:216`/`:552`）在新框架里要有等价物，否则 §4.10 的 HF2 bug 回归。
- [ ] `lyrics_ttml_first` 的**条件挂载**语义要保留（`ui/screen/UserScreen.kt:598` 的 `if`，不是 `alpha`）。
- [ ] `lyrics_sweep_quality` 的**失效场景**要有 UI 提示或门控（§4.1）。
- [ ] `offline_cache_mb` 的「下次启动生效」提示文案（`ui/i18n/zh_CN.kt:130`）必须随行迁移，否则是功能谎报。
- [ ] 账号分组里的**派生缓存**（`ncrust_netease_vip.*`、QQ 资料字段）要与登录/登出一起清（`ui/screen/UserScreen.kt:197`、`:239`），迁移后不能只搬 cookie。
- [ ] 「关于」分组为空是有意为之（§6.7），不要为了凑齐分组而硬塞项。

---

**文档结束** · 本次审计未修改仓库中任何源码文件（唯一的写入是本文件）。
