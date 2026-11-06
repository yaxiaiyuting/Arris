# Arris LTS 源码审阅台账

## 2026-11-06 首轮

以 `0ba4602bd347ad8eeeb7c786d00dcd781625ffb7` 为应用源码基线，与 `v3.7.33-gpl` 和本地 `origin/lts/3.7` 指向一致。官网更新输入取正式发布归档 `d211a7a0f9ee5a031f6725542a6e2f5edae8c4bb`。开始工作树干净，本轮通过独立快照检查。[问题与证据](2026-11-06-3.7.33-review.md)

| 范围 | 通读或局部检查 | 本轮完成点 |
|---|---|---|
| Android 7.0/7.1 兼容 | 通读 `network/crypto/EapiCrypto.kt`、`update/ApkArchive.kt`、`update/ApkVerification.kt`。局部读 `network/PlaylistApi.kt:950–1144`、`library/LibraryManager.kt:296–340`、`app/build.gradle.kts:30–36,533–552,574–577`。筛查 Lint baseline 的 NewApi 条目和相关调用。 | 确认 API 24/25 的跟唱动效分支和安装身份分支，复核正式 APK 中的 Base64 指令。其余低 API 入口待补。 |
| 歌词时钟与跟唱 | 通读 `lyric/LyricClock.kt`、`ui/player/KaraokeTimeline.kt`。局部读 `lyric/LrcParser.kt:1–60,132–136`、`ui/player/LyricsView.kt:110–188,203–770`、`ui/player/KaraokeScreen.kt:80–106,340–489` 和 3.7.33 差异、`ui/player/NcrustLyricsPanel.kt:112–148,809–838`、`ui/player/PlayerCardExpanded.kt:198–264,329–353,414–460,507–536`。 | 核对首字前移、共享歌词时钟和两条 seek 入口。歌词绘制与媒体会话节奏未全读。 |
| 更新选择与下载接线 | 通读 `update/AppVersion.kt`、`update/ForkRelease.kt`、`update/ReleaseFeed.kt`、`update/UpdateChecker.kt`、`update/UpdateRecovery.kt`。局部读 `update/UpdateService.kt:101–117,120–165,229–270,274–610`、官网 `relay/src/index.mjs:373–483`、`relay/tools/snapshot-releases.mjs:1–62`。 | 正式发布列表重放、旧更新恢复、安装入口及分段取消接线。下载器内部和断点校验待复核。 |
| 播放网络与离线缓存 | 通读 `network/BufferedHttpCall.kt`、`player/PlaybackRecoveryGate.kt`、`player/OfflineFirstResolution.kt`、`cache/OfflineKeys.kt`。局部读 `player/SongUrlFetcher.kt:80–444`、`ui/viewmodel/PlayerViewModel.kt:276–336,704–755,787–836,1954–2057,3280–3311`、`player/PlaybackService.kt:630–675,2050–2125`、`cache/OfflineAudioCache.kt:174–241`。 | 定位切网恢复、取链档位、响应取消、缓存回落及采样来源。尚未验证跨音源和缓存完整性。 |

本轮执行代码通读 14 个 Kotlin 文件，其余为相关调用段。旧核心审阅的文件数量沿用其各自基线，本台账不合并为当前 LTS 的完成百分比。五小时／周额度开始剩 98%／25%，阶段检查剩 96%／25% 和 90%／24%，留档前剩 88%／24%。

## 下一轮断点

1. 核对 `lts/3.7` 最新提交，确认 LTS01 的发布文件名和 LTS02 的 API 兼容是否已有新修复。对照新 SHA，跳过已解决的分支。
2. 续读 `PlayerViewModel` 的 `playSong`、失败重试与播放意图交接，配合 `DefaultNetworkObserver`、`AppHttp` 和 `MediaHttp`。核实前台恢复时所有音源调用网易取链是否会污染网易传输冷却，以及当前网络回调的并发收尾。
3. 续读 `OfflineUrlStore`、`OfflineAudioCache` 和 `OfflineLibrary`，核实断点在缓存孔洞内、首选档位半缓存与其它档位全缓存、试听段和完整音轨，以及删缓存时的索引保留。
4. 复核 `SegmentedDownload`、`UpdateDownloader` 的取消、续传实体绑定和本地校验。用本地拦截验证 I03/I04 的 LTS 回移，区分 I09 既有缺陷和新残留。补核旧 API 的安装交接及播放器高风险系统调用，再判断是否完成约定范围。

## 2026-11-06 19:56 续审

应用基线更新为 `9a997b4d0c12f5b9dade621c6ff4a2fac9888c34`，与正式标签 `v3.7.34-gpl` 一致。工作树起始干净，源码核对直接读取 Git 提交对象，未运行设备或发起网络请求。额度开始和阶段检查均为五小时 11%、周 12%，留档时为 9%、11%，本轮仅核对上一轮问题的修复交付。

| 条目 | 修复提交与当前核对范围 | 已有发布证据 |
|---|---|---|
| LTS01 | `5dd46b1` 的 `update/ForkRelease.kt:63` 接受历史文件名，官网已提交下载清单包含旧客户端能识别的 `Arris-v3.7.34-gpl.apk` | 公开版 3.7.32 通过应用内更新覆盖安装，已安装文件哈希与正式包一致 |
| LTS02 | `5dd46b1` 的 `network/PlaylistApi.kt:1130` 把响应字节直接交给解密函数 | 发布验收用 JVM 拦截收藏回退，用 API 24 执行生产解密类和合成响应 |
| LTS03 | `5dd46b1` 的 `update/UpdateRecovery.kt:30` 复用通道筛选，`update/UpdateService.kt:246` 清理失效检查记录 | API 24 冷启动清除预置的主线候选和检查时间 |
| LTS04 | `c533cb0` 的 `ui/player/LyricsView.kt:406` 接收显式跳转事件 | 离线播放跨句后回拖，歌词回到上一句 |
| LTS05 | `c533cb0` 的 `ui/player/PlayerCardExpanded.kt:254` 接通切换，`9a997b4` 的 `ui/player/PlayerCardOverlays.kt:335` 防止收起按钮挡住切换 | 原文与译文双向切换、无译文回落原文 |

上述五项按正式版验收标记为已修。动态证据来自已有[发布验收](../../../../../dist/v3.7.34-lts-release/RELEASE_VERIFICATION.md)，本轮新增通读、问题和复现均为零。下一轮从前述断点第 2 项继续，先看播放意图与切网恢复，再查离线缓存及分段续传。
