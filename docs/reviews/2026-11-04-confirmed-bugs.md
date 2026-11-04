# Arris 已确认问题清单 · 2026-11-04

源码上界 `8a4f435`，基线为已交付的 `f05a88a`。本清单合并[第一轮报告](2026-11-04-incremental-review.md)和本轮审查，保留编号。P1 是应优先处理的问题；P2 影响特定条件下的行为。**隔离复现**表示运行了生产函数/入口，但依赖被替换；**源码确认**表示触发与结果可由调用链确定。没有使用真实账号、线上房间、麦克风或 Android 设备。

## 建议先修

| 编号 | 问题、触发和影响 | 源码与证据 | 修复方向 |
|---|---|---|---|
| I01 · P1 | 私人 FM 首批加载成功后退出无限模式，播到队尾不再按 FM 补歌。首次加载为空时，反而可能提前改变当前普通队列的播放模式。 | 源码确认。[MainActivity 2494–2545](../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt#L2494) | FM 与普通换队列使用不同意图。先确认加载结果非空且仍属于当前请求，再提交模式、来源与队列。 |
| I03 · P1 | 取消大包下载后，旧分段线程继续写文件；立即重试同目标时，两代任务共用同一 `.seg`。 | 隔离复现，推进代次后旧任务仍写完 4 MiB。[UpdateService](../../app/src/main/java/com/takahashirinta/ncrust/update/UpdateService.kt#L327) · [日志](probes/2026-11-04-incremental/segmented-download.log) | 捕获启动代次并主动取消所有分段 Call。每代使用独立临时文件；取消后禁止旧任务回落、删文件或改名。 |
| I11 · P1 | 公网房间允许普通成员转发播放快照。其他成员会把收到的 Snapshot 当作房主状态处理。连接的 `role=host` 也只由请求参数决定，没有唯一房主或房主凭据校验。 | 隔离复现中继把普通成员的有效播放快照转发给其他 socket；播放器跟随后果由源码确认。[Worker 房间](../../relay/src/index.mjs#L456) · [转发](../../relay/src/index.mjs#L520) · [成员消费](../../app/src/main/java/com/takahashirinta/ncrust/listen/TogetherSession.kt#L199) · [日志](probes/2026-11-04-incremental/room-relay.log) | 建房时分发独立的房主凭据，绑定唯一房主连接；中继按真实 socket 角色放行消息类型，客户端也核对房间和快照序号。 |
| I14 · P1 | P2P 成员在握手前就收 DATA；未核对报文来源，也未校验 DATA 的令牌。房主还会在校验前把首包来源登记成对端。 | 用两个本地 UDP 端口复现，生产接收器和 RoomWire 解码接受了未握手的另一端口报文。[P2pLink](../../app/src/main/java/com/takahashirinta/ncrust/listen/P2pLink.kt#L87) · [日志](probes/2026-11-04-incremental/update-recognition.log) | 验证握手后再绑定地址和端口，握手前丢弃 DATA/ACK；会话数据加认证，ACK 仅允许匹配已发送分片。 |
| I15 · P1 | 识曲进入上传/等待响应后，按停止、退出页面或切到后台会取消协程和界面状态，但已经开始的 HTTP Call 仍会继续。 | 用生产识曲 Provider 和拦截器复现，取消时 Call 仍未取消。[RecognizeScreen](../../app/src/main/java/com/takahashirinta/ncrust/ui/screen/RecognizeScreen.kt#L60) · [Provider](../../app/src/main/java/com/takahashirinta/ncrust/recognize/NeteaseRecognizeProvider.kt#L110) · [日志](probes/2026-11-04-incremental/update-recognition.log) | 使用可取消的 Call 桥，取消时调用 `Call.cancel()`，覆盖响应体读取，保留协程取消语义。录音释放路径和网络取消分别验证。 |

## 其余已确认项

| 编号 | 问题、触发和影响 | 源码与证据 | 修复方向 |
|---|---|---|---|
| I02 · P2 | 播放全部批量删除续播点时，先前的延迟快照仍可覆盖较新的记录；批量序列化还会锁外遍历可变 map。 | 源码确认。[PlaybackStateManager](../../app/src/main/java/com/takahashirinta/ncrust/player/PlaybackStateManager.kt#L248) | 统一持久化通道，使用不可变快照和提交代次。 |
| I04 · P2 | 分段下载只核对 206 和字节数，接受不同 ETag、错误范围或错误总长的等长正文。分段阶段判成功，失去单流回退机会。 | 隔离复现。[SegmentedDownload](../../app/src/main/java/com/takahashirinta/ncrust/update/SegmentedDownload.kt#L147) · [日志](probes/2026-11-04-incremental/segmented-download.log) | 先核对每段完整范围、总长和实体身份，再写入；失败立即取消同批次。 |
| I05 · P2 | 官网试听及 APK 的旧 `If-Range` 仍获 206；试听有 Range 时还忽略 `If-None-Match`；APK 回退把 `bytes=-3` 切成前四字节，并给滚动别名片段加一年 immutable。 | Worker 入口隔离复现。[APK handler](../../relay/src/index.mjs#L63) · [试听 handler](../../relay/src/studio-audio.mjs#L5) · [日志](probes/2026-11-04-incremental/studio-range.log) | 先处理条件请求再切片，正确换算后缀，给可变别名单独设置缓存策略。 |
| I06 · P2 · 历史工作树 | 第一轮未提交的熔岩背景用全不透明灰度纹理做 `SrcIn`，灰度细节消失；一层向下平铺时顶部露出缝。**该实验已随 `6ee90ed` 撤掉，当前分支无须按此项修复。** | 第一轮源码快照及指纹。[详情](2026-11-04-incremental-review.md#i06--p2--未提交的熔岩背景把噪声细节完全上成纯色并在部分相位漏出顶边) | 若重新引入，使用灰度 alpha 遮罩，修正向负坐标补块和周期首尾。 |
| I07 · P2 | 切网清理只驱逐旧业务池，新 AppHttp 公共池未接入。QQ、更新和房间目录仍可能使用上一路由的空闲连接。 | 源码确认。实际复用与延迟取决于 OkHttp 检查。[AppHttp](../../app/src/main/java/com/takahashirinta/ncrust/network/AppHttp.kt#L43) · [切网入口](../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt#L739) | 集中登记公共池和专用池，切网统一驱逐；活动 WebSocket 另行处理。 |
| I08 · P2 | 网易云音质拒绝记忆只按档位键控。连续点同一首也能累计两次；换账号后新账号仍会继承旧账号的拒绝记忆，可能在 10 分钟内跳过可用高音质。 | 源码确认。[SongUrlFetcher 拒绝表](../../app/src/main/java/com/takahashirinta/ncrust/player/SongUrlFetcher.kt#L180) · [应用到档位阶梯](../../app/src/main/java/com/takahashirinta/ncrust/player/SongUrlFetcher.kt#L330) | 按会话代次隔离，只计不同曲目与明确权益拒绝，使用单调时间并在换账号时清除。 |
| I09 · P2 | 更新源只要返回非空页列表就停止回退，之后才验证载荷。镜像返回 HTTP 200 但给出损坏内容时，后续健康源不会被尝试；数组 `[1]` 还会被判为已最新，从而清除已发现更新。另有总预算失效的源码路径：阻塞 `execute()` 不会随 22 秒协程预算自动取消，在多个慢源或多页时可超过预算。 | 非法数组误判已隔离复现；源循环及预算为源码确认。[UpdateService](../../app/src/main/java/com/takahashirinta/ncrust/update/UpdateService.kt#L537) · [ReleaseFeed](../../app/src/main/java/com/takahashirinta/ncrust/update/ReleaseFeed.kt#L46) · [日志](probes/2026-11-04-incremental/update-recognition.log) | 单源校验成功后再结束回退；HTTP Call 绑定检查取消和总截止时间，每页发起前检查剩余预算。 |
| I10 · P2 | 同一 Release 同时有主线和 LTS APK 时，先选数字最大的资产，再过滤通道。LTS 客户端丢掉主线后也看不到同批合规 LTS 包。 | 生产筛选函数隔离复现。把同一 LTS 资产单独放进 LTS 条目可找到。[UpdateChecker](../../app/src/main/java/com/takahashirinta/ncrust/update/UpdateChecker.kt#L108) · [日志](probes/2026-11-04-incremental/update-recognition.log) | 对每个资产先过滤通道与变体，再取最高版本。 |
| I12 · P2 | 公网房主离开只发送结束通知，成员 socket 仍开。旧成员再发消息会刷新房间目录，已关闭房间可重新被扫描到。 | Worker handler 隔离复现，房主 close 后成员仍开，随后成员消息触发目录 beat。[Worker leave](../../relay/src/index.mjs#L555) · [日志](probes/2026-11-04-incremental/room-relay.log) | 房主退出时封存该房间代次并关闭成员连接；无房主时拒绝加入与目录刷新。 |
| I13 · P2 | 一起听成员退出或换房时，旧快照已启动的曲目解析、起播和延迟 seek 没有绑定房间。旧请求可在退房后返回，改变当前播放器。 | 源码确认，未用真机触发。[TogetherSession.leave](../../app/src/main/java/com/takahashirinta/ncrust/listen/TogetherSession.kt#L329) · [播放器跟随](../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt#L1184) | 按房间代次取消旧工作，在排队回调、解析返回、起播、seek、追加队列前核对房间和歌曲身份。 |
| I16 · P2 | 识曲对空正文先返回协议错误，再检查 HTTP 状态。空 503 会被误判为协议变化，空 401/403 同样绕过现有的凭证分流。 | 空 503 已用生产 Provider 隔离复现；401/403 为同一分支的源码结果。[Provider](../../app/src/main/java/com/takahashirinta/ncrust/recognize/NeteaseRecognizeProvider.kt#L110) · [日志](probes/2026-11-04-incremental/update-recognition.log) | 先按非 2xx HTTP 状态分流，再处理成功响应的正文和解密。 |
| I17 · P2 | 一起听的同曲桥接只 seek，收到房主确认暂停后仍不会改变成员的播放状态；暂停快照还会被无条件加上双方墙钟差，放大错误 seek。 | 源码确认。[TogetherSession](../../app/src/main/java/com/takahashirinta/ncrust/listen/TogetherSession.kt#L440) · [MainActivity](../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt#L1281) | 走有媒体项守卫的播放/暂停入口；暂停时不外推，用校准过的时钟与有界外推计算播放位置。 |
| I18 · P2 | 房主会把下一首放进快照，成员也显示它；成员构造播放器快照时只传当前曲，播放器桥依赖的 `nextSongId` 等字段一直缺失，因此不会提前把下一首加进队列。 | 源码确认。[成员快照转换](../../app/src/main/java/com/takahashirinta/ncrust/listen/TogetherSession.kt#L431) · [追加入口](../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt#L1217) | 把下一首完整音源与曲目身份传入桥接，在房间代次校验后按曲目身份去重预载。 |

## 本轮审查边界

- **播放网络：** 检查了 `PlaybackReadTransport`、`BufferedHttpCall`、`DefaultNetworkObserver`、`PlaybackRecoveryGate`、`OfflineFirstResolution` 的执行逻辑；对照 `RetrofitClient.eapiPostCancellable`、`SongUrlFetcher` 的档位阶梯/传输回退、`PlayerViewModel` 的切网与缓存兜底、`PlaybackService` 的播放代次入口、`OfflineAudioCache` 的媒体源接线。没有重新做真实 HTTP/2 或切网实测。
- **更新：** 核对了 `UpdateService` 的源选择、分页、超时、归一化、分段/单流切换和安装前交付，`ReleaseFeed`、`UpdateChecker`、`ForkRelease` 的版本选择，`UpdateDownloader` 的续传与取消，以及 `ApkArchive`/`ApkVerification` 的身份校验入口。未安装 APK。
- **一起听：** 核对了 `TogetherScreen` 的连接方式入口、`TogetherSession` 生命周期与跟随、`MainActivity` 桥接、WSS/LAN/P2P 的连接和数据入口、`RoomWire`、`UdpReliable`、`StunClient`、Worker 房间和目录。时钟与纯状态机只核对生产接线，实验室演算没有作为正式链路行为。
- **识曲：** 核对了 `RecognizeScreen` 权限与生命周期、`AacRecorder` 编码和清理、`NeteaseRecognizeProvider` 构造/取消/解密与结果分流；`MicCapture`、`ShazamFingerprint`、`ShazamSignature`、`CandidateRanker`、`WavEncoder` 只核对调用范围，其中实验室诊断和未接入代码不算正式识曲覆盖。

续审完成边界按本次指定的四条主链路收口。均衡器、生日、视觉和其他官网页面未在本轮重新展开。复现通过表示对应问题仍存在。

审查任务 `arris` 已停用，已复核配置为 `PAUSED`。本轮收尾时五小时和周额度分别剩 16% 和 87%。
