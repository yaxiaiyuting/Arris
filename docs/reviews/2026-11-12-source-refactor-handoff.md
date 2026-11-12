# Arris 主线源码查验与交接

## 当前交付与构建

| 项目 | 版本或提交 | 查验结果 |
| --- | --- | --- |
| 本轮起点 | `068d042`，`refactor/arris-source` | 已有歌词行起点复用 |
| 首轮签名测试包 | 4.1.1-gpl，versionCode 226，`2b47a1f` | JVM 3,595 通过、13 跳过。Lint 0 错误、90 警告 |
| 最新签名测试包 | 4.1.2-gpl，versionCode 227，`e837ea8` | JVM 3,600 通过、13 跳过。Lint 0 错误、90 警告 |
| 签名包之后的源码 | `0221a0e` | JVM 3,602 通过、13 跳过。Lint 0 错误、90 警告。Release Kotlin 已编译 |

主线最低 Android 10（API 29），targetSdk 36。两个 APK 的包名都是 `com.takahashirinta.ncrust`，签名证书与本地 4.1.0 正式包一致。4.1.2 的 APK SHA-256 为 `4fee33d82fd50daa0c17f2dc25096c0bd5f081b0bc54da836a45868749497f15`。

- [4.1.2 签名 APK](/home/duanjb666/deepseek/arris/dist/v4.1.2-gpl-test/Arris-v4.1.2-gpl-release.apk)
- [校验文件](/home/duanjb666/deepseek/arris/dist/v4.1.2-gpl-test/SHA256SUMS) · [R8 mapping](/home/duanjb666/deepseek/arris/dist/v4.1.2-gpl-test/mapping.txt) · [构建记录](/home/duanjb666/deepseek/arris/dist/v4.1.2-gpl-test/VERIFICATION.md)
- [4.1.1 首轮测试包](/home/duanjb666/deepseek/arris/dist/v4.1.1-gpl-test/Arris-v4.1.1-gpl-release.apk)

## 完成范围

原始三项均已进入 4.1.1 和 4.1.2。实机反馈追加的两项，即下一首音频预热和后台封面加载，已进入 4.1.2。源码查验从 `068d042` 起共有 16 个实现、测试及构建提交，涉及 65 个文件。统计以本轮实际提交为准。

| 范围 | 完成内容 | 进入签名包 |
| --- | --- | --- |
| 均衡器 | 系统文件选择器导入、导出 Arris JSON。校验格式版本、十段频率和增益范围，同名不同曲线保留两份。横屏、竖屏和跟唱的按钮共用可滚动预设菜单，选曲线即应用并启用，详细调节入口保留 | 4.1.1 起 |
| 音质 | 将曲库缺档、权限受限和未知分开。网易读取曲库上限及当前登录的可播档位，QQ 使用同一批文件的结果。未知档位显示原名称，实际码率继续取文件参数 | 4.1.1 起 |
| 后台播放 | 前后台交接时核对媒体前台服务。播放或主动缓冲期间移除任务仍保留媒体项，唤醒沿用 Media3。位置及缓冲停止推进时按原曲断点尝试恢复，用户暂停和音频焦点抑制期间不触发自动恢复 | 4.1.1 起 |
| 下一首 | 提前取链，保留一个真正的 Media3 待播项。无缝开启时主动预缓冲开头 12 秒，音频走现有缓存，当前曲加载优先。临近切换时复核链接寿命，旧队列及旧账号预取结果不接管 | 4.1.2 |
| 封面 | 下一首封面与音频取链并行。自然切歌采用同一个已完成或在途的封面任务。位图下采样和通知栏 JPEG 编码放到 IO，切换后只发布当前曲的图 | 4.1.2 |
| 无缝接续重试 | 真实换曲后按曲目重置重试额度，采用该待播项的请求档位和权益；音质上限记录以音源及曲目身份为键 | 4.1.2 后源码 |
| 后台断点 | 由实际播放服务保存当前曲目和位置，正常进度按 5 秒更新，显式暂停、拖动记录最终位置，曲终清除已播完曲目的断点 | 4.1.2 后源码 |

## 已查验的源码路径

- **播放和后台** · 检查 `MainActivity` 的队列交接、`PlayerViewModel` 的播放代次和重试、`PlaybackService` 的前台服务、缓冲、曲终和媒体通知。重点覆盖退后台仍在播的同曲、旧任务取消、预载已完成和仍在途、停止及暂停状态。后台停掉不可见的波形与律动分析，实际音频链继续运行。
- **预取和缓存** · 检查 `PreloadCachePolicy` 的有效期和同档复用、服务端单待播槽位、`OfflineAudioCache` 与 `OfflineLibrary` 的列表对账。离线列表一次加载只复制一次缓存键集合，缓存读取失败保留索引。
- **网络切换和取链** · 检查 `AppForegroundTracker`、`DefaultNetworkObserver`、`RetrofitClient`、`MediaHttp` 和两个音源的取链失败出口。返回前台按实际离开时长处理，IO 清理共享池、网易 HTTP/2 与 HTTP/1.1 池及媒体池，待处理恢复以当时的播放代次为准。QQ 和网易的业务分类随各自请求返回。
- **均衡器和音质 UI** · 检查 `EqualizerScreen`、预设编解码及偏好、播放器入口、`PlayerQualityChip` 和两个音源的证据路径。覆盖文件往返、非法频段、同名保留、权限不足与无文件、确证不足时的展示。

以上是本轮逐调用链查验范围。覆盖数字来自本轮 Git 差异和验证套件，未据此估算整个项目的覆盖率。

## 真机复测与下一轮断点

1. 在 4.1.2 开启无缝播放，放入至少三首未缓存的歌，切到其他应用或锁屏。分别观察曲终自动接续和通知栏手动下一首的声音、标题、封面。用同一队列再测一次缓存命中，记录切换时间和是否出现缓冲。
2. 比较稳定 Wi-Fi、Wi-Fi 与蜂窝切换以及两首不同编码或码率的衔接。手动暂停、其他应用取得音频焦点、移除最近任务分别验证，避免把几种系统状态混在一轮结果里。
3. 跟唱、竖屏和横屏各打开一次均衡器菜单。导出一条自定义曲线再导回，核对频段、同名保存与切换。用同一首明确具有较高档位的歌对照有权限和无权限的账号，再测曲库确实缺档的歌。
4. 最新源码与 APK 的断点在 `e837ea8`。接续先查 `PlaybackService.onTaskRemoved` 与 `PlayerViewModel.onCleared` 之后的连续队列归属。目前移除任务时服务可保留实际在播项和已入队的下一项，后续选曲仍依赖应用队列的回调。另一处是 `PlaybackStateManager.getSongPosition` 在时长不匹配时撤销旧位置的读写窗口。

本轮真机由用户复测。自动验证使用离线 JVM、Lint、签名构建和 APK 元数据检查。Media3 的预缓冲由当前播放的加载状态调度，12 秒是目标值。 [Android Media3 队列预加载](https://developer.android.com/blog/posts/elevating-media-playback-introducing-preloading-with-media3-part-1)

## 接续构建

工作树为 `/home/duanjb666/deepseek/arris/repos/Arris-refactor`，分支为 `refactor/arris-source`。本地 JDK 21 与邻接的 Kanesumi 组合构建已经可用。签名配置从工作树的忽略文件加载，交接产物中没有签名配置内容。

```bash
cd /home/duanjb666/deepseek/arris/repos/Arris-refactor
git status --short --branch
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --offline :app:testDebugUnitTest :app:lintDebug :app:compileReleaseKotlin
```

新包定号先运行 `/home/duanjb666/deepseek/tools/next-version.sh`。确定新版本后执行 `:app:assembleRelease`，用 Android SDK 的 `apksigner verify` 和 `aapt2 dump badging` 核对签名、包名、版本与 API 下限，另存产物和校验值。

[最新源码查验日志](/home/duanjb666/.cache/arris-handoff-source.qkz1po/gradle.log)
