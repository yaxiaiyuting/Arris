# 06:43 定时续审：当前缺陷与证据

日期：2026-10-27（Asia/Shanghai）。启动 HEAD `b82545c`，应用基线 `15237f8`，v3.7.8-gpl / versionCode 94。用户只授权审查、隔离复现与留档，修复由用户统一安排。

**进度快照：本轮仍在继续。** 第一阶段已确认下列 13 类问题，共 16 个通过缺陷断言的探针；另有 3 项源码调用链确认的问题。部分是旧 S17/S18/S10/S5/S20 修复未覆盖的分支，不应把它们全部称为新引入的回归。前一轮 R1–R4 未重复计数。

## 有动态证据的问题

| 编号 | 优先级 | 问题 | 用例 |
|---|---|---|---|
| H01 | P1 | 专辑收藏/取消仍能反序提交 | albumUnlikeCanBeOvertakenByTheOlderSubscribe |
| H02 | P1 | 排队改名会改用新账号凭据 | queuedRenameUsesTheNewAccountsCredential |
| H03 | P2 | 延迟播放上报会改用新账号凭据 | delayedScrobbleUsesTheNextLoggedInAccount |
| H04 | P2 | 更新器不同实例仍共用 `.part-1`，取消可破坏新下载 | separateDownloaderInstancesStillSharePartOneAndLoseTheNewPackage |
| H05 | P1 | 专辑匹配把锚点歌手注入候选，错误确认不同歌手的同名专辑 | albumCandidateBorrowsTheAnchorArtistAndConfirmsADifferentArtist |
| H06 | P2 | 从 QQ 进入聚合专辑时，已匹配的网易曲目未加入输出 | qqAnchorFindsButOmitsItsNeteaseCounterpartTracks |
| H07 | P2 | 并发首次 QQ 请求生成不同设备身份 | concurrentFirstDeviceRequestsReturnDifferentPersistentIdentities |
| H08 | P2 | 路由参数二次解码使百分号名称抛异常、加号变空格 | 2 个编解码用例；导航依赖字节码证据 |
| H09 | P2 | 新建本地歌单写盘失败仍返回成功形状 | creatingALocalPlaylistStillReturnsSuccessAfterDiskCommitFails |
| H10 | P2 | 网易批量详情失败和截断头表丢失完整性，影响同步 TTL 与收藏翻页 | 3 个业务入口用例 |
| H11 | P2 | 车机载荷回查优先返回不可用的本地旧记录，遮蔽可用收藏 | incompleteLocalPayloadShadowsAResolvableSavedSongDuringCarRecovery |
| H12 | P2 | QQ 共享 HTTP 状态让并发请求污染取链失败分类 | unrelatedSuccessfulMusicuCallTurnsAVkey403IntoANetworkFailure |
| H13 | P2 | QQ 单曲详情路由丢掉 mid 和元数据，当前版本播放项不可解析 | qqSongDetailRouteBuildsAnUnplayableCurrentVersionFromAPlayableSong |

### H01：专辑写入仍缺少最新意图串行化

`library/LibraryManager.kt:318` 的 `pushSubAlbum` 仍为每次独立 launch，`subscribeAlbum/removeAlbum` 没有单曲收藏的串行门控。探针先挂起 sub，再让 unsub 提交为 false，最后放行旧 sub：本地已经移除，模拟服务端最终却为 true。**这是上轮静态残余观察的动态确认**，与 R2 的“旧读取覆盖本地新编辑”不同。

建议专辑也按会话、专辑与最新意图串行提交。回归覆盖 true→false、false→true、期间换号与失败后重试；不要只复制当前单曲实现，前一轮 R1 已指出其跨会话意图清理仍有缺陷。

### H02：歌单其它写操作没有钉住原会话

`network/PlaylistEditApi.kt:162–188` 的删除、批量删除、改名、描述、隐私和移除曲目没有 pinned 参数；`write(:228)` 只有传入 pinned 时才核对归属。已有修复只覆盖了显式传快照的创建与加曲路径。

探针占住真实 `PlaylistWriteGate`，在 A 会话排队改名，然后换 B 再释放门控：真实改名 API 带 B 凭据发出并接受模拟成功响应。未操作真实歌单，不能宣称真实服务端已经替用户改名。

建议在用户动作产生时捕获登录代次，所有写出口固定凭据并在每批发送前复核，不在等待结束后重新读取另一账号。多字段编辑也应共享一次操作快照。

### H03：播放上报线程重新读取账号

`player/PlayReporter.kt:136–145` 在调用线程只用 cookie 判断是否登录，真正发包的线程调用 `RetrofitClient.eapiPostOsx(:377)` 又读取当前凭据。探针在真实工作线程的计数时间源处暂停，A 创建报告后切到 B，再恢复：请求确实携带 B 的 MUSIC_U。所有凭据均为虚构夹具，所有请求均被本地拦截。

这是上轮第二处静态残余观察的动态确认。建议上报保存产生时的登录快照，换账号后丢弃旧报告；不要把 A 的收听记录归给 B。未核对真实网易“最近播放”入账。

### H04：更新文件隔离只在同一个 Downloader 对象内成立

`update/UpdateDownloader.kt:127–154` 的 attemptSeq/latestAttempt 都是实例字段；`UpdateService.kt:280` 每次启动创建新实例，所以两次都叫 `.part-1`，两个实例也无法把对方识别为 superseded。

探针模拟生产入口的两个实例：旧任务写完首块后取消，新任务写完同名 part 后暂停，放行旧任务收尾。旧实例把新实例的 part 移到目标文件；新实例完成时先删除目标，再因 part 已被移走而重命名失败，最后 APK 不存在。不是签名绕过，也没有安装或启动 APK。

建议临时文件身份必须跨实例唯一，并让取消与收尾只处理本任务文件；续传存档和正式目标提交按目标串行。回归必须使用生产方式的两个独立实例，不能只测复用一个 Downloader。

### H05：候选专辑被人为赋予锚点歌手

`crosssource/CatalogAggregator.kt:676` 构造候选时把 `anchorArtist` 加进 `artistNames`，因此 `gradeAlbum` 的 artistStrong 必然包含锚点。`:692–706` 的冲突保护检查 `AlbumLookup.score()==null`，但同名不同歌手会得到非 null 的零分 Scored，无法挡下。

探针通过真实 `loadAlbum`、REST 与 QQ 映射链，给 Alpha 专辑匹配 Beta 的同名专辑，曲目名也相同：结果为 HIGH，Beta 专辑作为 alias 缓存，输出含 Beta 曲目。建议候选证据只来自候选，明确区分“艺人冲突”“资料缺失”“同名”；增加同名专辑/通用曲名的反例并使错误匹配缓存失效。没有访问实际音乐目录。

### H06：QQ 主源的聚合结果漏了网易侧

`CatalogAggregator.loadAlbum(:190–194)` 只在锚点为网易时填 neSongs；锚点为 QQ 时即使找到网易 counterpart，仍给 neSongs 空列表。`loadArtist(:140–152)` 也有相同分支形状，艺人页本轮只作静态确认。

探针确认 QQ 专辑已经得到 source=NETEASE/id=20 的 alias，但最终输出全部为 QQ，网易计数为 0。**没有把网易 id 误发给 QQ**：最初探针的这一猜测被实际结果否定，最终用例改为确认“对端曲目缺失”。建议根据 counterpart.source 分别取其专辑/曲目，覆盖两种主源的对称回归。

### H07：SharedPreferences.commit 不是首次设备标识的 CAS

`qq/QqIdentity.kt:44–54` 把 commit 成功理解成“没有别的写入者抢先”，但两个并发首次读都可得到 null、各自 commit 返回 true。探针经真实 deviceId 入口确认两个调用返回不同 seed，只有其中一个成为后续持久化身份。未证明真实服务器因此拒绝凭据；确认的是同机同时发出不同身份的本地前提。

建议生成、判空、提交和返回置于同一把锁；guid/QIMEI 由同一个稳定 seed 派生。增加并发首次初始化用例。

### H08：导航已解码后又用表单解码器

`ui/navigation/NavGraph.kt:310/340/344/374/400–408` 又调用 URLDecoder。实际依赖 navigation-common 2.8.5 的 NavDeepLink 路径参数匹配在放入参数前已执行 `android.net.Uri.decode`，本轮从本机 AAR 的 classes.jar 反汇编确认。

两条纯编解码探针使用真实 NavRoutes：`100% Love` 在第一次路径解码后变为 `100%+Love`，第二次 URLDecoder 抛 IllegalArgumentException；`A+B` 则被改成 `A B`。没有在模拟器或 Compose 页面上运行，导航首次解码依据为实际依赖字节码，纯探针的首次百分号解码用 java.net.URI 表达。

建议路径统一用 URI 编解码，页面消费导航已解码的参数。覆盖百分号、加号、中文和封面 URL 的转义字符。旧 ArtistRouteContractTest 直接 split 原始 route 后只解码一次，没有包含导航本身的一次解码。

### H09：新建入口没有接上写盘失败

`LocalPlaylistStore.writePlaylists(:81–96)` 丢弃 commit 结果；`createPlaylist(:246)` 与 `LocalPlaylistRepository.createLocalOnly(:244)` 仍返回创建对象。`LibraryScreen.kt:293–296` 无条件关框并弹新建成功。

使用符合“commit 失败仍改内存”语义的双层 prefs 夹具：真实新建返回对象，当前进程能看到歌单，模拟冷启动读取的持久层为空。建议新建/收养也使用可失败的原子提交结果，失败保留重试入口并恢复内存；不要只修 add/remove/clear 的曲目编辑。这是旧 S5 未覆盖的新建分支。

### H10：网易曲目读取的失败与不完整被折成列表

`PlaylistApi.getPlaylistDetail(:254–298)` 不核对 trackCount 与 trackIds 完整性，`fetchSongDetails(:341–365)` 反复失败后返回 emptyList；`LocalPlaylistRepository.loadRemoteOutcome` 对网易又固定 complete=true。

三条探针分别确认：

1. 声明 2 首，首屏含 1 首，缺失详情请求返回 code500，仍 Success(total=1) 并推进 lastSyncedAt。
2. 声明 2 首，头表只有 1 个 id，直接接受、不补齐，仍推进完整同步 TTL。
3. 收藏分页用失败的批量详情得到空表，`LibraryManager.loadMoreLikedSongs(:581–590)` 的游标已经到末尾；网络恢复后再翻页没有请求，失败批次被跳过。

建议批量读保留成功/部分/失败语义，网易歌单像 QQ 一样保留完整性与降级信息；只有完整同步更新 TTL。收藏分页区分个别歌曲确实不可取得与整批接口失败，后者不得消费游标。当前同步只加不减，未把它描述成已删除原有本地曲目。

### H11：载荷回查未跳过不可解析记录

`source/SongSourceExt.kt:118–130` 找到第一张本地歌单的同身份记录就返回，未判断其 songmid 是否可用，也未继续找收藏中的完整记录。

探针建立一个无 mid 的旧本地 QQ 条目和同曲目完整收藏：真实回查返回旧条目，Router 返回不可解析，完整收藏被遮蔽。建议回查按身份一致且可解析筛选，允许继续找后续记录；数据迁移后也应纠正旧载荷。未连接车机，证明范围是回查与路由。

### H12：QQ 失败分类读取其它请求的状态

`qq/QqClient.kt:369/375/395` 把传输结果写进全局 lastHttpStatus/lastTransportError，`QqApi.kt:457–468` 在挂起调用返回后再从全局字段构造本请求的失败。

对照探针：同一个 vkey HTTP403 单独执行是 AUTH_EXPIRED；在其响应收尾处暂停，让另一条资料形态的 musicu HTTP200 完成，再放行原请求，结果变成 NETWORK。请求全部本地拦截。此时可能错过鉴权续期而进入网络重试；反向污染同样需防护，但本轮未专门复现反向用例。

建议 musicu 返回与请求绑定的传输结果，业务控制流不读取诊断用全局 last 字段。QQ 的 lastRejection/lastSip 也有共享形状，本轮未证明其并发后果，不额外计数。

### H13：QQ 单曲详情的当前版本本身无法播放

`MainActivity.kt:777/3323` 导航只传裸数字 id；`SongDetailScreen.kt:255` 的 routeAnchorSong 丢掉 mid、media_mid、曲名与歌手，`:116` 只为网易补详情。QQ 原曲于是成为空标题、无 mid 的“当前版本”，但行内仍提供播放按钮。

探针调用真实私有 routeAnchorSong，再走真实 CatalogAggregator.loadTrack 与 Router：原曲可解析，同身份的详情行不可解析，且没有曲名可召回另一源。这里未要求新增 QQ 歌词能力；问题是已有播放按钮交出不可播放条目。建议保留选中曲目的完整载荷或按身份回查，未补齐时禁用播放与插播。

## 源码确认，未运行 Compose 的 3 项

- **F01 / P2：单曲详情“添加到下一首”回调未接线。** `NavGraph.kt:433/450` 两个 SongDetailScreen 调用都省略 onAddToNext，页面默认 `{}`，行内按钮点到空实现。主层已有 onSongInsertNext 可接。
- **F02 / P2：QQ 歌单“重新登录”回调未接线。** `NavGraph.kt:378` 省略 onRelogin，`QqPlaylistDetailScreen.kt:231` 的重登按钮触发默认空实现。需要把 QQ 登录入口送到该路由。
- **F03 / P2：网易歌单列表只展示首 100 项。** `LibraryScreen.kt:191–193` 仅一次调用默认 limit=100/offset=0 的 getUserPlaylists，忽略 result.more；列表没有后续页入口。已检查的是这一 UI 调用链，未用真实 100+ 歌单账号验证。

## 验证与边界

第一阶段证据见 [probes/2026-10-27-0643/phase-1/](probes/2026-10-27-0643/phase-1/)。16 个探针通过，0 failure / 0 error；**通过表示错误存在，未修复**。未运行全量 3109 项测试，未改业务实现、版本、APK 或 Release，未做真实网络和设备操作。

探针开发中修正过：QQ 主源的错误推测（实际是漏掉对端，没有误发该 id）、一次 shouldAutoSync 参数类型错误。最终归档的是修正后的成功运行；这些探针问题不是应用构建故障。

本轮后续还会继续审阅未覆盖区域，收尾时补最终额度、覆盖范围与下一次断点。修复不在本任务内。
