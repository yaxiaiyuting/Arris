# 2026-11-04 Arris 增量源码审查

状态：**已完成用户后续指定的四条主链路**。续审结果和可交付清单见 [已确认问题清单](2026-11-04-confirmed-bugs.md)。已停用定时任务 `arris`，配置状态复核为 `PAUSED`。以下保留首轮的范围、证据和当时断点。

上次核心审查报告中的问题在正式 3.7.11 阶段已对照修复。本轮从其修复源码 `f05a88a317168204f0af4013824c4832c331fb5a` 开始，以主线工作树 `feat/4.0.0` 的已提交源码 `8a4f435` 为上界。前者是后者的祖先。完整树差异为 310 个文件，包含历史报告、测试和媒体产物；其中 `app/src/main`、`app/build.gradle.kts`、`relay` 有 143 个已提交变更路径，`app/src/main/java` 占 108 个。另观察了工作树内 6 个已有未提交文件，其中 `MotionBackdrop.kt` 在第一轮阅读期间继续变化，报告按收尾时重读的快照记录。固定范围清单和工作树指纹见 [scope.tsv](probes/2026-11-04-incremental/scope.tsv) 与 [working-tree-sha256.txt](probes/2026-11-04-incremental/working-tree-sha256.txt)。续审已提交文件以固定 SHA 为准，未提交文件用指纹核对，文件已变化时报告区分版本。测试仅作为证据和相邻边界使用，未把整仓重读列为任务。

## 第一轮问题

### I01 · P1 · 私人 FM 刚开播就退出无限模式

- **触发与影响：** 首页点「我的电台」，首次返回非空曲目。首批可正常播放，随后走原非无限模式的队尾分支，不会按私人 FM 持续补曲。若原模式为循环或随机，用户可能一直在初始批次打转。
- **证据（静态实际调用链）：** [MainActivity.kt:2530](../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt#L2530) 先设置 `INFINITY`；非空回调调用 [replaceQueueAndPlay:2494](../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt#L2494)，后者恢复 `lastNonInfinityMode`；回调只把 `fmMode` 置回 true。队尾 [MainActivity.kt:2029](../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt#L2029) 以 `playMode` 分流。首批为空时，提前写入的模式同样没有随请求失败回滚，是同一处事务边界。
- **建议：** 普通换队列与进入 FM 使用明确不同的提交意图；只有非空 FM 列表仍属于当前请求时，一次提交队列、播放模式和来源。覆盖进 FM、空返回、被新选择取消、退出到普通歌单四条路径。

### I02 · P2 · 批量删除续播点可能被旧的持久化快照覆盖

- **触发与影响：** 歌曲位置的延迟写已拿到快照，此时用户点歌单「播放全部」。新批量删除直接写较新 JSON；旧任务之后仍能写下旧快照，重启后已清除的进度可能回来。反向交错时，批量写在锁外遍历活动 map，也可能因并发保存而失败，且异常被吞。
- **证据（静态线程交错，未做 Android 执行）：** [PlaybackStateManager.kt:248](../../app/src/main/java/com/takahashirinta/ncrust/player/PlaybackStateManager.kt#L248) 在锁内删记录、锁外直接传活动 map；[PlaybackPositionCodec.kt:89](../../app/src/main/java/com/takahashirinta/ncrust/player/PlaybackPositionCodec.kt#L89) 遍历该 map。[PlaybackStateManager.kt:316](../../app/src/main/java/com/takahashirinta/ncrust/player/PlaybackStateManager.kt#L316) 的旧快照跨 Default/IO 写盘，无提交代次校验。本项针对新增批量入口与位置记录交错，历史 O06 是队列本身的持久化，未重复计数。
- **建议：** 所有续播记录变更共用单一快照/提交路径；序列化不可变快照，落盘前在同一提交门核对代次。单纯给批量删除补一次加锁无法防止旧快照覆盖。

### I03 · P1 · 取消分段下载仍写文件，重新下载同目标还会共用临时文件

- **触发与影响：** 大包进入四段下载后取消或改选。状态会回到 Idle，旧线程仍继续下载、写入和执行收尾；若马上重试同目标，两代任务可对同一 `.seg` 文件执行删除、写入和改名。失败时还可能删除另一代任务的临时文件。并发同名写的最终产物未在设备验证。
- **证据（生产分段函数 + 隔离 Kotlin 执行）：** [UpdateService.kt:327](../../app/src/main/java/com/takahashirinta/ncrust/update/UpdateService.kt#L327) 的取消条件把当前全局代次与另一次读取同一全局值比较，[352 行](../../app/src/main/java/com/takahashirinta/ncrust/update/UpdateService.kt#L352) 并没有捕获创建该任务的代次；[398 行](../../app/src/main/java/com/takahashirinta/ncrust/update/UpdateService.kt#L398) 取消的是单流 downloader 和外层 Job，分段线程使用自己的同步请求并在 [SegmentedDownload.kt:114](../../app/src/main/java/com/takahashirinta/ncrust/update/SegmentedDownload.kt#L114) 启动后由阻塞 `join` 等待。探针把生产取消表达式和分段函数组合起来，代次推进后仍成功写完 4,194,304 字节。[探针](probes/2026-11-04-incremental/segmented-download.sh) · [输出](probes/2026-11-04-incremental/segmented-download.log)
- **建议：** 捕获每次启动的代次，保存并主动取消分段请求；为每代分配独立临时文件，取消或失去所有权不得进入回落、改名和删除其他任务文件。

### I04 · P2 · 分段下载接受错误范围和不同实体

- **触发与影响：** CDN 在探测后换包，或对请求的区段给出同长度、错误起止位置的 206。程序可将错块写入 APK 并宣称分段成功；真正的单流回落不会触发，错误留待后续 APK 可读性、校验或安装阶段处理。本轮未验证系统安装器。
- **证据（生产分段函数 + 隔离 Kotlin 执行）：** [SegmentedDownload.kt:147](../../app/src/main/java/com/takahashirinta/ncrust/update/SegmentedDownload.kt#L147) 只用 `If-Range` 发送 ETag，收到响应只判断 206、正文和字节数。长度检查在 [132 行](../../app/src/main/java/com/takahashirinta/ncrust/update/SegmentedDownload.kt#L132) 对预先设长的文件执行。探针返回与探测不同的 ETag、错误的区段和总长、正确的正文长度，生产函数仍返回成功。[输出](probes/2026-11-04-incremental/segmented-download.log)
- **建议：** 每段写入前验证完整 `Content-Range` 以及强实体标识，写入时限制字节上界；身份不可确认或范围有偏差时丢弃该代分段并回落。验证与 I03 独立。

### I05 · P2 · 官网 APK 和试听的断点响应破坏条件请求；APK 后缀范围取错方向

- **触发与影响：** 更新或替换资源后，携带旧 `If-Range` 的客户端收到新实体的片段而非整包，可能造成断点失败或拼接不一致。APK 切片回退把 `bytes=-3` 当作 0–3，并把会更换版本的下载别名片段标为一年不可变。试听区段另会丢掉 `If-None-Match`。
- **证据（真实 Worker 分派入口 + 本地绑定）：** [APK 路由:63](../../relay/src/index.mjs#L63) 只转发 Range，[76–106 行](../../relay/src/index.mjs#L76) 的回退错误解释空起点并强制设置缓存策略；[试听入口:5](../../relay/src/studio-audio.mjs#L5) 在有 Range 时清空请求头。通过本地 `ASSETS` 模拟稳定的新实体，证实旧实体条件返回 206、试听已匹配 ETag 的条件请求仍返回 206，以及 APK 回退的前缀取错和缓存头。[探针](probes/2026-11-04-incremental/studio-range.mjs) · [输出](probes/2026-11-04-incremental/studio-range.log) · [RFC 9110 §13.1.5](https://www.rfc-editor.org/rfc/rfc9110.html#section-13.1.5)
- **建议：** 先判断一般条件与 `If-Range`，仅在实体匹配时切片；从总长换算后缀区间，区分版本文件和可变下载别名的缓存策略。保留简单有效 Range 的现有行为。

### I06 · P2 · 未提交的熔岩背景把噪声细节完全上成纯色，并在部分相位漏出顶边

- **触发与影响：** 打开玩家界面动效。噪声纹理只改变位图 RGB、透明度全满；`SrcIn` 用该位图的 alpha 给主题色做遮罩，噪声的明暗不会保留。向下滚动的层同时只从正向 tile 下标开始，画布顶边会留下一条随相位扩大的未覆盖区域；相位归零时该区域突然消失。源码足以确认机制，未量化实际可见度。
- **证据（静态合成/几何）：** [MotionBackdrop.kt:228](../../app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionBackdrop.kt#L228) 的第二层 `dyFactor=-0.30`，所以 phase=0.5 时第一块从 `0.15 × tileHeight` 才开始；在 0..2 取 tile 不会补前一块。[MotionBackdrop.kt:236](../../app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionBackdrop.kt#L236) 的 tint 与 [497 行](../../app/src/main/java/com/takahashirinta/ncrust/ui/player/motion/MotionBackdrop.kt#L497) 的固定 `0xFF` alpha 组合丢掉噪声变化。[Android BlendMode.SRC_IN](https://developer.android.com/reference/android/graphics/BlendMode#SRC_IN)
- **建议：** 灰度进入 alpha 遮罩或改用保留亮度的上色方式；对每轴用向负坐标铺开的周期起点，并使用能完整绕回的轨迹，复核 0/半程/周期末的覆盖和跳变。另在设备上量完整画布 56dp 模糊的帧开销，当前没有性能定论。

### I07 · P2 · 切网清理没有覆盖新 AppHttp 公共连接池

- **触发与影响：** QQ、更新、房间目录等先建立公共池中的空闲连接，随后发生 Wi-Fi/蜂窝或代理路径切换。现有清理代码会逐个清旧业务池，公共池未收到驱逐请求。新请求是否复用旧连接取决于地址匹配与 OkHttp 健康检查，实际等待时长未验证。
- **证据（静态连线）：** [AppHttp.kt:43](../../app/src/main/java/com/takahashirinta/ncrust/network/AppHttp.kt#L43) 定义并使用独立公共池，生产代码没有调用它的 `evictAll()`。[PlayerViewModel.kt:739](../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt#L739) 清 Retrofit、媒体、B站和 DNS；[RetrofitClient.kt:144](../../app/src/main/java/com/takahashirinta/ncrust/network/RetrofitClient.kt#L144) 与 [MediaHttp.kt:77](../../app/src/main/java/com/takahashirinta/ncrust/player/MediaHttp.kt#L77) 都在 builder 上覆盖公共池，所以驱逐任一个都不触及公共池。QQ、更新和房间目录使用公共池。
- **建议：** 将公共池加入网络路由变化的清理，集中登记独立池；用本地连接复用探针验证路由代次切换后不再命中旧空闲连接。活动 WebSocket 重连另由房间状态机处理。

## 本轮覆盖与续审断点

- **播放/队列（局部）：** `MainActivity.kt` 1498、1569–1586、1909–1960、2029–2034、2488–2546 和各换队列调用点；`PlaybackStateManager.kt` 168–329；`PlaybackPositionCodec.kt` 86–97；`PlayerViewModel.kt` 续播位置存取调用点。尚未扫完新播放/缓存和网络切换实现。
- **网络工厂（局部）：** 通读 `AppHttp.kt` 和 `MediaHttp.kt`，核对 `RetrofitClient.kt` 73–90、144–163、`PlayerViewModel.kt` 299–313、739–753、`AppForegroundTracker.kt` 52–64 和公共池调用方。尚未通读网络传输或媒体链。
- **更新下载（局部 + 完整分段函数）：** `SegmentedDownload.kt` 1–173 执行代码，`UpdateService.kt` 220–225、297–464、468–494，`UpdateDownloader.kt` 20、71–89、分段边界，`UpdateScreen.kt` 167–202 的取消、重新下载入口。检查源归一化、分页、通道选择、旧单流和校验仍待继续。
- **官网（局部 + 完整试听 handler）：** `relay/src/studio-audio.mjs` 1–44、`relay/src/index.mjs` 27–119、当前播放器脚本的帧调度与控制入口定位。官网其余中继与资源路由仍待续审。
- **Android 视觉（未提交快照，局部）：** `MotionBackdrop.kt` 194–260、420–506；`NcrustLyricsPanel.kt` 585–628、693；`PlayerCardExpanded.kt` 361–390；通看 `LyricsView.kt`、`AboutScreen.kt` 和生日 `motion.js` 的未提交 diff，未完整验收运行行为。歌词模糊仍在组合阶段读逐帧值，只作待量化观察。历史同类问题只在变更路径有新证据时再列。

下次按 [scope.tsv](probes/2026-11-04-incremental/scope.tsv) 继续，先查 **AppHttp/取链与音频流、更新检查/单流/校验**，接着查 **一起听公网/P2P（包括 Worker 房间）和识曲录音/协议**，最后查 **均衡器/构建、歌词/波形/生日、剩余 UI 与官网资源**。逐文件记录已读函数或行段，低风险媒体只核对接线和引用；清单无剩余再停任务。禁止把本轮首查当作全量覆盖。

本轮只有本地隔离探针和静态阅读。探针成功代表复现缺陷，未改业务代码、未使用设备、未进行真实账户或生产写入。

额度实测（五小时 / 周）：开始 38% / 91%，阶段 33% / 90%，收尾 31% / 89%。本轮结束在已复现并留档的模块断点，5 小时任务继续有效。
