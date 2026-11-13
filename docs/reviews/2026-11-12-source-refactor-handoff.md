# Arris 主线源码查验与交接

## 11 月 13 日收藏、离线缓存、歌词与队列续查

从 `0512ef1` 接续，源码查验到 `8b9b5ae`。本轮 12 个本地提交，涉及 `app/src` 下 32 个文件，其中生产侧 21 个、JVM 测试 9 个、Android 测试 2 个。另同步更新预取入口的仓库说明。

| 提交 | 查验范围与结果 |
| --- | --- |
| `1cb2c57` | 单曲和专辑收藏在本地变更前锁定登录归属。同一条目的云端写入串行，尚未发出的操作合并，任务完成即释放槽位。批量去重后保留顺序。本地拦截生产入口复现旧版换号后发出一次错归属写入、同一曲目写两次。 |
| `6358fe1` | Media3 保留区间可能提前创建缓存键。离线列表保留写入中的曲目元数据，首个片段提交后才显示。隔离模拟器通过真实缓存入口复现并核对。 |
| `9660802` | AMLL 预取和当前曲加载共用同曲下载。重叠预取和起播从两次镜像请求减到一次，仍有等待者时继续，最后一个离开后取消。 |
| `8658d3c`、`8b9b5ae` | 歌词发布省去仅写旧诊断字段的全表编码，历史字段仍可读取。网易云、QQ 与 B 站按当前曲目音源分派。网易云网络请求被取消时立即退出，既有重试与旧缓存回退继续生效。 |
| `9b6f331` | 队列快照相同就只提交游标键。5,000 首队列移动游标三次，整列键写入从 4 次降到 1 次。SharedPreferences 仍负责将更新后的文件写盘。 |
| `a76ad53` | 整张歌单起播时直接使用原队列清除断点。空断点表由读取 5,000 个队列条目降到 0 个，夹具中最后一个断点在第三首时读取 3 个。其他歌曲的断点保留。 |
| `f3aa740` | 将队列、游标和随机轮次合到一个去抖提交，恢复时取同一份快照。已有键与 JSON 可继续读取。离线 JVM 复现旧版分成两次提交，覆盖换模式、坏轮次和过期写入。Android 14 核对随机轮末自动接续。 |
| `abc2356` | 单曲详情页成功路径由两条详情请求收敛到一条，歌词独立发起并随页面取消。删除旧详情 DTO、ViewModel、基线规则与未渲染的译文状态。请求数按生产调用链核对。 |
| `37cdb62`、`8f78d8b` | 清理队列身份中无生产调用的辅助函数、重复说明及预取的未消费结果。保留批次并发上限、失败隔离及父子取消传播，已有去重测试直接断言队列身份。 |
| `b7e9545` | 进入空搜索只读一轮历史，删除一条直接使用该次读取结果，清空只更新对应分区。JVM 验证只读变更分区，并保留跨源同号记录。隔离模拟器验证清空单曲后专辑记录仍显示。 |

离线全量 JVM 3,598 项通过、13 项跳过、零失败，覆盖 351 个测试类。Debug Lint 0 错误、90 警告。Debug 应用与测试 APK 构建成功，Release Kotlin 编译通过。隔离 Android 14 模拟器 14 项通过，涵盖后台队列、随机轮末、离线缓存提交、首页迟到快照和搜索历史。使用虚构账号、本地音频和回环连接。本轮源码只提交到本地分支，未生成新的签名包。

以重构启动前 `b042755` 为基线，在当前仍存在的 464 个生产 Kotlin／Java 文件中，172 个含累计 Git 差异，占 37.1%。新增文件计入，已删除文件和测试文件排除。这一比例衡量文件改动覆盖。

[全量构建](/home/duanjb666/.cache/arris-library-refactor.T2G5J9/gradle-final.log) · [模拟器回归](/home/duanjb666/.cache/arris-library-refactor.T2G5J9/instrumentation-final.log) · [收藏基线](/home/duanjb666/.cache/arris-library-refactor.T2G5J9/favorites-baseline.xml) · [AMLL 基线](/home/duanjb666/.cache/arris-library-refactor.T2G5J9/ttml-baseline.xml) · [队列提交基线](/home/duanjb666/.cache/arris-library-refactor.T2G5J9/shuffle-atomic-baseline.xml)

下轮从 `OfflineUrlStore.recallRecord` 及 `PlaybackService` 的后台起播日志接续。离线回退目前只传一个请求档位，先量同曲多档位、多版本的候选组装与缓存探测次数，保留档位优先、同档完整曲优先和坏文件排除。再核对预取与实际播放并发读网易云 LRC 的次数，保留旧条目音译补齐、失败重试及登录代次约束。真机弱网、切网和后台切歌仍由待测包或后续测试覆盖。本轮性能证据是请求数、队列读取和提交次数。

## 11 月 13 日启动、缓存、网络和统计续查

从 `bbc21de` 接续，源码验收到 `885e22a`。本轮 11 个本地源码提交，涉及 `app/src` 下 40 个文件（生产源码 30 个，测试 10 个）。范围按实际修改和验证记录。

| 提交 | 查验范围与变化 |
| --- | --- |
| `2811757` | `AppWarmup`、`HomeFeed`、`HomeScreen` 和 `SearchScreen` 共用栏目请求。隔离测试让日推等待者在 750 毫秒超时后重进，日推实际发出一次请求，新歌独立就绪。换号后旧结果被拒，失败可以重新请求。 |
| `5120f62`、`ea53bd5` | 首页、搜索和三个详情页的封面按规范化 URL 去重。所有列表共用四个并发名额。前页取消时，仍可见页面能接手同一 URL，回归验证重叠的两个等待者只发出一次接手请求。 |
| `2a0d227` | `OfflineLibrary` 用索引修订号校验 `OfflineAudioCache` 的键快照。回归覆盖在扫描后新增曲目，旧扫描不能删掉它。 |
| `e22c2de`、`3f6cdcd` | 启动页本地恢复等待上限为 3 秒。首页 700 毫秒等待结束后可以先显示空态，离线快照恢复后自动填充。Android 14 上旧接线等不到新歌，修后在同一页面显示。真实 `MainActivity` 冷启动进入库页，切回首页后也读到了预置的虚构磁盘快照。 |
| `4061afe` | `ConnectionWarmup` 从实际在播的网易云媒体地址确定预热目标，复用 `MediaHttp` 的池和 TLS 配置。一次回环连接服务了预热和音频请求，短响应只排空，不解码正文。整次预热限时 5 秒并可取消。 |
| `a9f7c6e` | `HomeSnapshot` 更新栏目时只读归属键，避免每次复制整份 prefs 映射。回归验证保存阶段整表读取为零，损坏的归属字段不会变成游客快照。保留已有键。 |
| `5725e8e` | `StatsStore` 与 `StatsCommitGate` 让统计采样、取快照与清空内存无需等待前一次磁盘提交。阻塞写入的 JVM 夹具在旧代码上领号超时，改后可取得顺序并正确写入最新数据。 |
| `c36e569` | 删除无业务入口的旧歌词请求序号类和只写不读的协调器状态。网易云、QQ 和 B 站仍用曲目身份与请求代次校验，界面沿用歌词就绪与确无内容的状态。 |
| `885e22a` | 离线起播检查在同一缓存状态下用最多两次 Media3 区间查询，省去复制片段树。Android 14 真缓存覆盖中段、空洞、补齐空洞与同曲多档位。 |

离线全量 JVM 3,593 项通过、13 项跳过，零失败。测试总数减少主要来自移除已无调用方的歌词状态及旧缓存片段扫描用例，保留对应请求归属和缓存起播的回归。Debug Lint 0 错误、90 警告，Release Kotlin 编译通过。隔离 Android 14 模拟器 12 项通过。回环连接验证为 HTTP/1.1，真实 CDN、真机切网、启动耗时变化和帧率未实测。4.1.2 正式包仍对应 `50460a5`。

[完整构建日志](/home/duanjb666/.cache/arris-startup-refactor.sY0CZq/gradle-checkpoint.log) · [模拟器回归](/home/duanjb666/.cache/arris-startup-refactor.sY0CZq/instrumentation-checkpoint.log) · [离线首页基线](/home/duanjb666/.cache/arris-startup-refactor.sY0CZq/home-late-baseline.log) · [冷启动烟测](/home/duanjb666/.cache/arris-startup-refactor.sY0CZq/cold-start.log) · [统计阻塞基线](/home/duanjb666/.cache/arris-startup-refactor.sY0CZq/stats-gate-baseline.xml) · [音频回环验证](/home/duanjb666/.cache/arris-startup-refactor.sY0CZq/connection-warmup.log)

下轮从 `LibraryManager` 的单曲、专辑收藏写入接续。初查两套按 ID 串行逻辑存在进程内长期留存的 Mutex 表。先用拦截请求核对同曲连点、换号及批量收藏，确认安全回收或合并的边界。随后检查播放索引早于首个缓存片段提交的时序，以及 `LyricsCache` 同曲主轨、TTML 和来源标记分别写入时的序列化成本。本轮没有对这些待查路径下结论。

## 11 月 13 日播放边界与渲染热路径续查

从 `d5585f7` 接续，验证到 `75deeed`。本轮在 `refactor/arris-source` 本地完成九组源码提交，涉及 16 个源码和测试文件。

| 提交 | 查验范围与变化 |
| --- | --- |
| `509f460` | 顺序播放自然结束或手动跳到队尾时停止，应用、通知和持久化状态一起选回第一首。清除 Media3 的尾曲后，下一次按播放才从第一首起播。隔离模拟器复现了旧版队列与在播身份不一致。 |
| `535bc14` | 码率采样复用选中音轨及已知文件长度。未知长度继续查询，曲目切换后重新读取。500 毫秒采样的 JVM 探针在 120 次取值中只读一次缓存文件长度。 |
| `a8d5932` | 待播按音源登录代次和请求档位校验，账号变化撤销旧待播项。生效音质随默认路由切换时重排，恢复网络时重试此前缺缓存的待播项。隔离模拟器用本地虚构登录态复现并验证网易云登出路径。 |
| `6723ca8` | 一起听成员的下一首随房间及播放界面归属，实际起播后才并入本地队列。隔离模拟器连接回环 LAN 房间，覆盖移除界面时停止自动接本地队列、房主断开时撤销房间下一首并恢复本地待播。 |
| `1bdd6ad` | 单曲无预载重播后重新累计本轮收听时长。进度心跳和曲终共用上报条件，保留试听和累计时长门槛。去掉未读取的辅助任务引用。 |
| `40c61a8` | 缓存管理按 key 读取已缓存字节，避免复制每个档位的片段树。模拟器真实缓存测试覆盖有洞片段和同歌多档位。旧缓存键及索引格式沿用原实现。 |
| `c333918` | 跟唱的行状态与倒计时提示在时间轴构造时准备，帧循环复用对象。JVM 验证 120 帧内复用同一提示，并覆盖回拖与切句时刻。 |
| `17ffd87` | 波形窗口在写入时更新有信号列数，重绘判定从最多读四组各 28 列缩减为最多读四个计数。覆盖暂停、缺样、倍速保留和清空。 |
| `75deeed` | 播放器在同进程直接把播放代次、房间、起播状态交给前台服务，去掉无发送方的等待 URL action 及 Intent 分配。 |

离线全量 JVM 3,607 项通过、13 项跳过。Debug Lint 0 错误、90 警告，Release Kotlin 编译通过。隔离 Android 14 模拟器使用本地音频、虚构账号和回环房间，11 项 `BackgroundQueueTest` 通过。账号验证覆盖网易云登出，QQ 登录代次和 Wi‑Fi／蜂窝的切换走源码及 JVM 核查，真实网络切换和房间快照的端到端传输未复测。波形和跟唱优化验证到绘制前的状态和分配，未做设备帧率测量。4.1.2 正式版依旧对应发布提交 `50460a5`，本轮产出的是后续本地源码。

[离线构建](/home/duanjb666/.cache/arris-queue-edge.SILqz1/gradle-selection.log) · [模拟器验证](/home/duanjb666/.cache/arris-queue-edge.SILqz1/instrumentation-selection.log) · [媒体服务日志](/home/duanjb666/.cache/arris-queue-edge.SILqz1/playback-selection.log)

下轮从 `AppWarmup`、`HomeScreen` 和 `ListPrefetch` 接续。先对照首页首屏等待、登录代次与请求去重，再检查列表封面预取的调度和失败后的重进。随后查 `OfflineLibrary.reconcileAndList` 的缓存键快照与同时发生的播放写入或删除。网络与房间的模拟器边界优先补可复用的本地测试。

## 11 月 13 日后台队列接续

本轮从 `934e3a8` 开始。预载、队列生命周期及媒体服务交接分别提交到本地 `refactor/arris-source`。

| 提交 | 已查验的范围与改动 |
| --- | --- |
| `e942f1e` | 统一 `MainActivity.kt` 的预载参数，保留单曲自预载、乱序轮次和队尾续播的原入口 |
| `8ec5d07` | `NcrustApplication.kt` 持有进程内的播放器。队列、媒体按键、随机轮次和预载由 `PlaybackQueueController.kt` 管理，页面重建后沿用当前队列。取链、重试、试听判定和歌词读取继续用 `PlayerViewModel.kt` 的既有入口。检查了播放器卡片、歌词分享、统计和一起听的接线 |
| `19177fc` | `PlaybackService.kt` 在移除最近任务时保留仍属当前代次的取链与已排队的曲终处理，过期操作仍按播放代次、媒体项和房间归属过滤 |
| `a92df6d` | `PlaybackService.kt` 的码率、断点、媒体面板、桌面组件和卡播观察共用 500 毫秒进度采样。每轮少读两次位置和三次时长，事件触发的刷新仍单独采样 |

Android 14 隔离模拟器断网运行三项 `BackgroundQueueTest`。实际移除 Activity 任务后，有无缝预载与无预载两条路径都从第一首自动播到第三首，重开页面时标题对应真实在播项。第三项在真实服务里模拟取链结果尚未返回，再送入本地缓存地址，验证移除任务期间前台服务能接受当前代次的结果。曲终排队与任务移除恰好同帧的路径做了源码校验。 [模拟器验证](/home/duanjb666/.cache/arris-queue-emulator.phDW5T/instrumentation-final.log) · [媒体服务日志](/home/duanjb666/.cache/arris-queue-emulator.phDW5T/playback-final.log)

离线 JVM 3,603 项通过、13 项跳过。Debug Lint 0 错误、90 警告，Release Kotlin 编译通过。后台验证使用 Debug 测试包，4.1.2 正式包保持原发布产物。 [构建日志](/home/duanjb666/.cache/arris-progress-snapshot.Fjlzpv/gradle.log)

下轮优先覆盖随机轮末、顺序队尾、单曲循环和一起听断开时的后台行为，并验证当前待播链接在账号或网络切换后的归属。随后检查无码率格式的缓存元数据读取。

## 11 月 12 日 20:10 UTC 续查

本轮从 `a4aadf1` 接续，源码验收到 `b540200`。四组改动已分别提交到本地 `refactor/arris-source`。

| 提交 | 已查验的范围与改动 |
| --- | --- |
| `559d54f` | `PlaybackStateManager.kt` 的续播位置读、撤销、写入、批量清理及写盘调度。越界撤销与读取共用位置锁，避免旧读删掉并发保存的新断点。写盘调度与修改按同一顺序执行 |
| `ee140b5` | `MainActivity.kt` 的异步队列恢复与 QQ 身份补全、`PlaybackQueueLogic.kt` 的曲目回查。读盘后按当前播放身份对齐保存游标和随机轮次。`PlaybackQueueLogicTest.kt` 覆盖保留同一曲目的重复项位置 |
| `1706c09` | `MainActivity.kt` 的曲目变化与 Media3 过渡回调共用随机轮次更新，重新洗牌后直接使用生成函数固定在首位的当前项 |
| `b540200` | `PlaybackService.kt` 的过渡回调、码率采样和当前曲目读取复用实际媒体项身份。按 500 ms 心跳计算，连续播放同一项时每分钟减少约 120 次重复身份解析，另省去断点采样中的重复解析 |

离线全量 JVM 3,603 通过、13 跳过。Debug Lint 0 错误、90 警告，与前次相同。Release Kotlin 编译通过。本轮对 Activity 重建和后台队列的验证来自源码调用链及 JVM 索引逻辑。 [离线构建日志](/home/duanjb666/.cache/arris-media-identity.VwWHI8/gradle.log)

下轮从 `PlaybackService.onTaskRemoved`、`PlayerViewModel.onCleared` 和 `MainActivity` 的预载入口接续。移除最近任务后，服务可续播已加入 Media3 的下一项。ViewModel 清理回调后，待播项播完仍缺少继续选曲的入口。先核对任务移除前后预载槽位与队列所有权，再归并队列变更、主动选曲、曲终和进度窗口重复的预载参数传递。保留账号、预载代次、播放模式与当前曲目的校验。

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

## 发布状态

- 2026-11-12 已把 4.1.2 转正：tag `v4.1.2-gpl` 打在 `50460a5`，该提交的 `app/` 与 `e837ea8` 逐字节一致，只多 CHANGELOG、README、官网与校验归档。GitHub 与 Gitee 的资产同名 `Arris-v4.1.2-gpl.apk`，10,704,508 字节，SHA-256 `4fee33d82fd50daa0c17f2dc25096c0bd5f081b0bc54da836a45868749497f15`，与测试包一致。
- 官网主推已切到 4.1.2，`latest.apk` 指向它；国内 `/get/*` 302 到 Gitee 的同一资产。
- 为了让 tag 指向「源码 = APK」的提交，`57a9479`、`0221a0e`、`ebbc717` 已变基到发布提交之后，新哈希为 `cc4a647`、`5f3c7e8`、`65a94cb`。这两处播放修复未进本包，等真机复测后随下一版发布。

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
