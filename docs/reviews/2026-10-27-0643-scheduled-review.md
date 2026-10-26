# 06:43 定时续审：当前缺陷与证据

日期：2026-10-27（Asia/Shanghai）。启动 HEAD `b82545c`，应用基线 `15237f8`，v3.7.8-gpl / versionCode 94。用户只授权审查、隔离复现与留档，修复由用户统一安排。

**本轮审查已完成。** 累计确认下列 24 类问题，共 30 个通过缺陷断言的探针；另有 5 项源码调用链确认的问题。部分是旧 S17/S18/S10/S5/S20/S14/S19/S1 修复未覆盖的分支，不应把它们全部称为新引入的回归。前一轮 R1–R4 未重复计数。

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
| H14 | P1 | B站音频区与视频区同号时身份冲突，选曲与收藏丢掉第二项 | audioZoneAndVideoWithTheSameNumericIdCollapseInQueueAndCollection |
| H15 | P2 | 首页旧快照排队写入时重新取账号，A 数据被标成 B 并恢复 | queuedHomeSnapshotRetagsThePreviousAccountsDataAsTheNewOwner |
| H16 | P2 | 回退至前三秒后保存，旧续播点没有清除 | seekingBackBeforeTheResumeThresholdLeavesTheOldResumePoint |
| H17 | P2 | B站字幕 CDN 请求附带完整登录 Cookie | subtitleCdnRequestReceivesTheFullLoginCookie |
| H18 | P2 | B站字幕生成的 100 分钟以上时间戳被歌词解析器丢弃 | subtitlesAfterOneHundredMinutesAreDroppedByTheLrcParser |
| H19 | P2 | 统计页无锁复制正在写入的 Map，可抛 ConcurrentModificationException | readingStatsDuringAListeningTickCanThrowConcurrentModification |
| H20 | P2 | QQ 扫码旧失败污染新会话、关闭帧未收尾、早到凭据被丢弃 | 3 个连接生命周期用例 |
| H21 | P2 | QQ 歌单按解析后条数翻页、缺数组响应被缓存成完整空表 | 2 个真实取数/仓库入口用例 |
| H22 | P2 | 单个逐词段折成三行时，扫光跳过中间行 | oneLyricWordWrappingAcrossThreeVisualLinesSkipsTheMiddleLine |
| H23 | P2 | 局域网回传缺少密文完整性校验，改 IV 后仍接受凭据形状 | changingAnIvByteChangesTheCookieWithoutAnyIntegrityRejection |
| H24 | P1 | 普通歌单名称含“喜欢的音乐”时可抢在真正红心歌单前命中 | ordinaryPlaylistNameContainingLikedMusicShadowsTheRealHeartPlaylist |

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

### H14：B站 AU 与 AV 的数字身份没有分区

`bili/BiliModels.kt:74` 将 `auid ?: aid` 都交给同一个 `SourceIds.biliId`。虽然 sourceId 分别保留 `au:101` 和 `bv:…:202`，`TrackKey.equals` 只比较 source/id，队列、收藏与缓存键仍会冲突。`:67–72` 的“不会串台”注释只考虑了取流分支，没有覆盖身份消费者。

探针经真实 BiliTrack→SongItem→TrackKey、QueueKeys.planPlayItem 和 SavedSongSync：AU101 已在播时选择不同的 AV101，计划不改队列，播放项仍为 AU；先收藏 AU 再收藏 AV，只保留 AU。两个 OfflineKeys 也相同，但没有运行真实音频缓存，不能宣称实际缓存已经串流。

建议给 AU/AV 建立互不相交的稳定身份域，或让所有身份消费者纳入媒体种类；同步迁移旧收藏、歌单、队列、歌词和音频索引。只修 provider 的 sourceId 分支不足以解决。

### H15：首页旧快照可以被重新标成新账号

`cache/HomeSnapshot.kt:77–103` 捕获待保存列表后异步执行，却在序列化末尾重新读当前 accountOwner。`CookieManager` 换号清理只能清已经存在的值，不能阻挡这个尚未提交的旧任务。`warmup/AppWarmup.kt:162–167` 和 `HomeScreen.kt:174–198` 的旧响应写内存也没有会话提交闸门，后两条只作静态观察，未单独运行完整启动器。

真实 HomeSnapshot.save 探针在 A 列表序列化时暂停，清 A、登录 B，再放行：磁盘 owner 为 B，daily_songs 却含 A；清内存后在 B 会话调用真实 restoreIntoCache，也恢复了 A。此为旧 S14 的异步残余，不是此前已修的直接切号缓存未清。

建议在请求/快照创建时捕获归属和代次，内存发布与磁盘提交都核对同一个快照；显式清理也推进写入代次。不要在工作线程末尾给旧数据贴上“当前账号”标签。

### H16：回退至不可续播区间没有撤销旧位置

`player/PlaybackStateManager.kt:216–217` 对小于 MIN_RESUMABLE_MS=3000 的位置直接返回。`PlayerViewModel.kt:726–728` 会在进度大幅变化时调用该入口；`:3072–3084` 的 seek 没有另外清记录。因此保存过 60 秒、用户拖回 1 秒并离开后，旧 60 秒仍然是下次的续播点。

探针直接经真实 saveSongPosition/getSongPosition 确认 60000→1000 后读回 60000；这是持久化入口逻辑复现，没有模拟整段播放。建议有效位置落回起点区间时删除该歌旧记录，而非忽略本次更新，并覆盖暂停拖动和拖回起点后立即切歌。

### H17：通用 B站 GET 把凭据发给字幕 CDN

`bili/BiliApi.kt:283–301` 不看目标 host 就拼入完整 BiliAuthStore Cookie；`subtitleBody(:539–544)` 也复用它。`BiliSubtitle.normalizeSubtitleUrl(:331–340)` 接受 hdslb CDN 或其它 http(s) 地址，未限定鉴权域。

探针给虚构登录镜像后，经真实 subtitleBody 请求 `aisubtitle.hdslb.com`，本地拦截器捕获到 SESSDATA 与 bili_jct。没有 DNS/socket，也没有把真实凭据传出去。证明的是登录凭据超出 bilibili.com 账号 API 域的发送范围；**未证明 CDN 泄露、账号被接管或重定向攻击**。

建议按目标域和请求用途带凭据：账号 API 才携带会话，字幕/媒体 CDN 使用无账号 Cookie 的客户端；对服务端给出的 URL 与跨域重定向建立明确边界。不要只检查日志脱敏。

### H18：100 分钟后的字幕时间戳与解析器不兼容

`bili/BiliSubtitle.kt:532–539` 的 `%02d` 对分钟只规定最小宽度，100 分钟生成 `[100:00.00]`；`lyric/LrcParser.kt:48/61–62` 却只接受恰好两位分钟。真实链路 `BiliSourceProvider.fetchLyric` 生成 LRC，`PlayerViewModel.loadBiliLyrics(:2602)` 再用该解析器。

纯转换链探针输入 99:59、100:00、100:02 三条有效音乐字幕：音乐筛选保留全部三条，LRC 解析只剩第一条。影响长视频/长音频在该边界之后的歌词；未验证某个线上视频。建议内部直接传时间化行表，或统一允许多位分钟，补边界与长时长回归。

### H19：统计页快照没有持有写入使用的锁

`stats/StatsStore.kt:128` 直接调用 aggregate.snapshot，而 addListen/addPlay/prune 使用 Store.lock；`StatsModel.kt:201–208` 的 snapshot 逐个复制普通 LinkedHashMap。`StatsScreen.kt:124–126` 在 IO 读取，采样器可在 Main 同时增加曲目、来源或跨日桶。

真实 StatsStore.snapshot 与 addListen 探针在复制 byDay 时暂停普通迭代器，然后新增一个日期桶：读取抛 ConcurrentModificationException。夹具只给标准 LinkedHashMap 的迭代器加时序闸门，没有改变其 fail-fast 行为。统计页 LaunchedEffect 未捕获此异常，存在页面崩溃路径；没有运行 Compose/真机。

建议所有快照都在同一个 aggregate 锁内取不可变副本，序列化继续放锁外；补读取与新增曲目/跨日/裁剪同时发生的用例。此问题不同于旧 S4 的清空后旧磁盘快照反写。

### H20：QQ 扫码连接没有隔离旧连接回调

`qq/MqttWebSocketTransport.kt:163–168` reset 取消旧 socket 并清队列，但 `Connection.onMessage/onClosed/onFailure(:197–224)` 没检查自己是否仍是 active，全部写同一个 incoming。`QqQrMqttSession.open(:164–172)` 的正常重定向会经过 reset；只有 awaitConnAckFrame 忽略旧断连哨兵，`:293–295/336–338` 的订阅及事件阶段仍直接失败。

状态夹具建立旧、新 Connection，调用真实 reset 和旧 onFailure，再给新连接发送正常 scanned MQTT 帧：真实 awaitEvent 首先返回 Failed，下一次才读到正常 Scanned，新连接的 send 仍成功。没有建立真实 WebSocket，证明的是回调/队列的会话归属。

另一用例经真实当前 Connection.onClosing 确认：没有调用 socket.close/cancel，也没有终止事件，awaitEvent 只超时；`:209–211` 只有日志。扫码 UI 将 null 当作继续等待，可能一直到二维码超时。建议给连接加代次，所有回调先核对 active；当前连接收到关闭帧应确认关闭并且只投递一次终止事件，旧连接不能终止新会话。

第三个状态机探针给合法的早到 Scanned、Credentials，随后给 SUBACK：真实 awaitSubAck(:288–291) 只保留第一个事件，凭据事件被永久丢掉，后续只得到 Scanned 和超时。没有声称 QQ 生产服务已经观察到此包序；已确认的是应用承诺处理的“PUBLISH 早于 SUBACK”分支不能保存多条状态。建议按序暂存早到事件，至少不得丢失 Credentials/终态，并覆盖同一网络帧含多条 MQTT 报文。

### H21：QQ 歌单丢掉了原始分页消费量与响应完整性

`QqPlaylistParser.kt:253–261` 跳过无法映射的原始条目；`QqPlaylistApi.kt:183` 却按 page.tracks.size 推进服务端 song_begin。真实 fetchAllPages 探针给第一页两个原始条目，其中一个缺 mid：下一请求 offset=1 而非 2，最后输出 A、A、B，重复了一首。建议页结果同时保存原始消费数/下一偏移，映射过滤不改变传输游标，并检测无进展分页。

`:249/264–272` 对缺失 songlist、缺失 hasmore 都给默认空/false；仓库 `QqPlaylistRepository.kt:241–250` 据此标 complete=true。第二个真实 loadDetail 探针给 code=0、声明 total=100、但没有 songlist 的响应：返回零曲目、无降级，并缓存为 fresh=true/complete=true/total=100。建议缺数组判结构错误，显式空数组且总数一致才是权威空表；部分结果不能覆盖完整缓存或推进完整 TTL。这是旧 S1 修复的上游证据缺失分支：本地同步虽然开始看 degradation，仓库仍有把不完整结果标成完整的路径。

### H22：跨三行的计时段只建立首尾两行轨道

`lyric/SweepTrack.kt:307–312` 在 l0 != l1 时只按中点建立 l0 与 l1，没有中间视觉行。`ui/player/NcrustLyricsPanel.kt:725` 使用真实排版几何，`:817–824` 会把光标所在行之前的内容一次全点亮。

纯几何探针构造 12 个字符、每行 4 个字符的合法计时段：0–1200ms 所有采样只出现视觉行号 {0,2}，从未出现 1。影响窄栏/大字/长计时短语折成三行以上的扫光；不是全部逐字歌词都失败，也没有做像素渲染验证。建议按每个实际折行边界拆段，按字符或几何长度分配时长，覆盖三行、四行与 RTL。

### H23：配对发现已认证，但回传内容尚未认证

旧 S19 已把秘密移出 UDP、为发现响应补 HMAC；本轮没有重复报告“秘密仍在广播”。残余是 `auth/QrPair.kt:110–128` 仍用 AES-CBC，IV 与密文没有 MAC/AEAD；decrypt 只做 UTF-8 与 MUSIC_U 形状检查。`QrPairServer.kt:82–85` 据此交给登录回调。

纯协议探针先正常加密虚构 cookie，再只翻转线上 IV 的一位：无需在修改步骤使用秘密，就把 token 首字符 f 改为 g；真实 decrypt 仍返回非空，NeteaseCookie.isUsable 仍为 true。**没有证明伪造了有效云端票据、攻破账号或完成真实局域网中间人攻击。** 已确认的是接收端无法识别传输篡改，可能接纳坏凭据并提前结束等待。

建议回传也用 AEAD 或 encrypt-then-MAC，把协议版本、unikey、挑战与密文一起绑定并防重放；认证失败必须不触发 onCookie。不能把“发现响应的 HMAC”当成“回传密文也已经完整性校验”。

### H24：歌单名包含关键词不是红心身份

`network/LikedPlaylistPick.kt:50–54` 在 specialType 判定前就按名称子串返回第一个命中。固定的官方红心名称不可改，并不意味着普通歌单不能叫“以前喜欢的音乐精选”。`PlaylistApi.kt:710–721/770` 将此结果直接用于收藏底表读取。

真实 getLikedPlaylistId/getLikedTrackIds 入口探针给同一 owner 的两项列表：普通 id111、specialType=0、名称“以前喜欢的音乐精选”在前，真实红心 id999、specialType=5 在后。实际选中 111 并以其详情返回收藏 ID。全部接口本地拦截；未声称线上该账号当前就是这个排序。触发前提是类似名称的普通歌单位于真红心之前，一旦发生会污染 liked_ids/总数/后续导入，而 like 写入仍落在真正红心歌单。

建议先按 owner 与明确专用标记建立唯一身份；名称降级只接受经过约束的精确候选，遇到多义返回 Failure、保留旧底表。停止“任意特殊歌单”兜底污染数据。回归要加入普通同名/含关键词歌单，以及语言差异和特殊类型缺失的反例；不能只测两个 specialType=5 的名称优先级。

## 源码确认，未运行 Compose/设备的 5 项

- **F01 / P2：单曲详情“添加到下一首”回调未接线。** `NavGraph.kt:433/450` 两个 SongDetailScreen 调用都省略 onAddToNext，页面默认 `{}`，行内按钮点到空实现。主层已有 onSongInsertNext 可接。
- **F02 / P2：QQ 歌单“重新登录”回调未接线。** `NavGraph.kt:378` 省略 onRelogin，`QqPlaylistDetailScreen.kt:231` 的重登按钮触发默认空实现。需要把 QQ 登录入口送到该路由。
- **F03 / P2：网易歌单列表只展示首 100 项。** `LibraryScreen.kt:191–193` 仅一次调用默认 limit=100/offset=0 的 getUserPlaylists，忽略 result.more；列表没有后续页入口。建议保留分页游标与 more，支持重试和去重。已检查的是这一 UI 调用链，未用真实 100+ 歌单账号验证。
- **F04 / P2：服务退出后的桌面卡片播放没有恢复入口。** `NcrustWidgetProvider.kt:236–249` 从持久化状态画有内容、暂停态的卡片；`WidgetRenderer.kt:151–153` 的播放按钮只发 resume。冷服务 onCreate 创建空播放器，`PlaybackService.kt:923/1833–1842` 的 resumePlayback 只 seek/prepare/play，不读取已存队列、载荷或取链。因而保留旧卡片而服务已不存在时，按钮没有可播放 MediaItem。建议经完整身份恢复或先引导打开应用。此项为调用链静态确认，未执行 Android 生命周期；没有把“划掉任务仍继续播放”算作问题，当前 onTaskRemoved 明确 stopSelf。
- **F05 / P2：导出媒体服务也暴露了内部 start Intent 命令。** `AndroidManifest.xml:66–69` 的 PlaybackService exported=true、无调用权限；`PlaybackService.kt:873–945` 直接处理 pause/stop/seek，`:949` 只在 URL Intent **自带** playRevision 时检查代次，不带就绕过。第三方前台应用显式启动此导出组件时可进入内部命令/URL路径，绕过 UI 路由和播放意图门控。证据为清单和服务静态分支，未执行外部应用/ADB 命令或播放任意 URL，也未声称云端写入或凭据泄露。建议区分公开 MediaLibrarySession 与仅本应用可用的命令通道，通过私有组件或受控能力调用；不要为修此问题直接关闭车机所需的服务导出。

## 验证与边界

第一阶段证据见 [probes/2026-10-27-0643/phase-1/](probes/2026-10-27-0643/phase-1/)。16 个探针通过，0 failure / 0 error；**通过表示错误存在，未修复**。未运行全量 3109 项测试，未改业务实现、版本、APK 或 Release，未做真实网络和设备操作。

第二阶段累计证据见 [probes/2026-10-27-0643/phase-2/](probes/2026-10-27-0643/phase-2/)。22 个探针通过，0 failure / 0 error，包含第一阶段 16 个再运行及新增 6 个；不是 16+22 个独立用例。

第三阶段累计证据归档至 `phase-3/`，最终选定运行共 28 个缺陷断言，0 failure / 0 error；其中连接生命周期与几何、协议用例均为本地状态/纯逻辑复现，未启动浏览器、相机、网络配对或播放服务。前两阶段证据保持不变。

最终累计证据归档至 `phase-4-final/`，共 **30 tests / 0 failure / 0 error / 0 skipped**。新增的两项是 H20 早到凭据及 H24 红心身份；四个目录是阶段快照，独立用例总数仍为 30。

探针开发中修正过：QQ 主源的错误推测（实际是漏掉对端，没有误发该 id）、一次 shouldAutoSync 参数类型错误、一次 SongItem 夹具漏填构造参数、QQ 分页夹具漏导入 playlist 包。最终归档的是修正后的成功运行；这些探针问题不是应用构建故障。

## 覆盖与下次断点

本轮记录 203 个 Kotlin 文件：145 个执行代码通读、58 个局部调用链检查；另有 9 个非 Kotlin 边界文件。逐路径、行段与源码 SHA-256 见 [coverage.json](probes/2026-10-27-0643/coverage.json)。这不是整个项目“已审百分比”，也不是动态测试覆盖率；大段历史注释/import/空行没有计作逐字审阅。

下次先核对更新后的 git HEAD 与其它代理修复，再续审：统计清空后的新采样/异步删除提交顺序；网易扫码增量保存与上层成功状态；widget 冷恢复/内部 Intent 边界；最后补巨型 Activity/ViewModel、波形和剩余未记录模块。下一轮具体范围与未记录文件见 [REVIEW_COVERAGE.md](REVIEW_COVERAGE.md)。

待确认但**未计入缺陷数量**：统计清空的新提交顺序尚未补探针；扫码仅 MUSIC_U 的服务端实际响应形状未核对；清单启用 allowBackup 且未引用现有备份 XML 是配置事实，但凭据/本地歌单备份边界需要产品策略确认，不直接宣称发生了真实云备份泄露。

本轮还排除了几项初步疑点：生日 WebView 的 motion=1 之前有原生系统动效开关门控，不能据 URL 就断言忽略减少动效；playlist/local DTO 有整包 R8 keep，不能只因缺 SerializedName 就报告混淆丢字段；当前 onTaskRemoved 会明确停止播放服务，不能把“划掉任务后仍在播放”当作既成场景。

全部临时探针已从常规测试源移走；阶段快照保留不改。应用源文件、版本、APK、tag 和 Release 均未变，未推送。修复交由用户统一安排。

## 额度与收尾

开始读取五小时剩余 100%、周剩余 69%；2026-10-27 07:46:14（Asia/Shanghai）结论读取为五小时剩余 **12%**、周剩余 **55%**。已接近约 10% 的停止阈值，不再扩展审查；保留额度给留档及用户后续沟通，没有使用重置。

四批源文件/XML/日志摘要的 SHA-256 已核对，coverage 中的当前源码 SHA-256、行段边界与去重数量已核对；常规测试目录没有遗留本轮 probe，`git diff 15237f8 -- app` 为空。06:43 单次任务完成后清理该任务，11:50 与 17:00 两个单次任务保留。
