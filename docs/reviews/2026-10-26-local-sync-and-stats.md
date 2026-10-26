# 歌单同步、账号会话、统计、离线索引与搜索分页审查

基线：v3.7.7-gpl / versionCode 93，应用源码 `4e9d763`。本报告承接已修复的五类问题，继续检查 local、QQ 缓存维护、stats、B 站登录收尾、更新下载和首页缓存。以下十九类通过真实业务入口/状态快照和本地夹具复现，共 21 个用例；不使用真实账号，不做真机测试。它们是新发现，尚未修复，不能把 v3.7.7 描述成已解决这些问题。

## S1 · P2：不完整/降级的 QQ 歌单被当成本地同步成功

位置：`local/LocalPlaylistRepository.kt:80` 的 `PlaylistResult.Data` 分支、`:121` 的同步提交；`local/LocalPlaylistModels.kt:322` 的 10 分钟 TTL。

`loadRemoteSongs` 把任何 Data 都解包成 songs，丢掉 `fresh/fromCache/degradation`。已知 TRUNCATED 的 1/100 条缓存、离线回退甚至 NEED_LOGIN 回退都能走这条路径。`sync` 因而返回 Success，并把 lastSyncedAt 改为现在；后续按需同步在 10 分钟内不再重试。

探针预存 complete=false、total=100、仅 1 首的 QQ 详情，先确认仓库返回 TRUNCATED，再调用真实本地同步：结果为 Success，lastSyncedAt 更新，1 秒后的 shouldAutoSync 为 false。并未丢弃本地旧条目，但界面把部分数据说成同步成功，拖延了补齐/续期入口。

建议：远程读取返回完整性与失败原因；可按只加不减合并部分数据，但不能更新“完整成功”的时间戳或显示完整成功。明确区分离线、需要登录、截断和成功空表。

## S2 · P1：已经删除的本地歌单会被在途同步重新创建

位置：`local/LocalPlaylistRepository.kt:120`、`:125`；`local/LocalPlaylistStore.kt:144` 附近的 `idx < 0 -> list + updated`。

同步发请求前持有 LocalPlaylist 对象，回来后没有复核该歌单是否仍存在、删除代次是否变化。写入函数既负责更新也允许新建，因此删除歌单后，旧同步仍可把旧元数据和远程曲目重新插入列表。

探针在真实网易只读请求入口暂停响应，删除本地歌单并确认列表为空，再放行响应：同步返回 Success，原 key 又出现在本地列表。HTTP 全部被应用拦截器截获。正常界面删除后会返回上页，页面作用域取消可能避开部分时序；数据层本身没有删除闸门，不能把 UI 及时销毁当作保证。

建议：对已存在歌单的同步使用带 key 和 revision 的更新事务，与 create/adopt 分离；删除或重新创建同一 key 都推进代次。提交前在 Store 锁内复核，不允许 sync 借更新接口隐式重建。并覆盖清空、增删曲目与两个在途同步的时序。

## S3 · P2：QQ 按账号清缓存使用了错误的后缀匹配

位置：`qq/QqPlaylistStore.kt:173` 的 `clearOwner`。

列表 key 是 `list:qqmusic:<owner>`，但详情 key 是 `detail:qqmusic:<owner>:<playlistId>`。对这两者都用 `endsWith(":" + owner)`，只会正确匹配列表；详情的最后一段是歌单 id。

探针存入账号 10001 的歌单 111，以及账号 20002 的歌单 10001，再 clearOwner(10001)：前者详情仍在，后者详情却被删除。故障是缓存维护不完整和误删其它账号缓存；读取侧还有 owner 校验，本轮没有证明发生跨账号内容展示。

当前主源码仅在 Repository.clearCurrentOwner 中封装此维护 API，未找到界面调用，因此这是维护入口的确定缺陷，不能描述成现有登出流程每次都会误删。

建议：通过明确的前缀/键解析按 owner 段匹配，并对 list/list_at/detail/detail_at 四种形状分别验证。另审查调用方是在清凭证前捕获 owner，避免拿 anonymous 执行清理。

## S4 · P1：统计清空的代次检查与写盘之间仍有窗口

位置：`stats/StatsStore.kt:clear` 与 `:flushNow` 最后 IO 块。

旧 flush 在锁内判断 generation 相同，然后释放锁，再调用 SharedPreferences.edit/putString/apply。若 clear 落在检查与写入之间，旧快照仍能在清空之后写回来。clear 自己的异步 remove 也没有提交代次检查，存在与新一轮统计落盘竞争的路径。

探针让真实 flush 通过代次检查，在下一次 putString 前执行 clear，并等待它的 remove/commit 完成，再恢复旧写：内存 totalPlays 为 0，落盘 totalPlays 却重新变为 1。重启恢复会读到被用户清除的历史。已动态确认的是旧 flush 反写路径，clear 的延迟 remove 覆盖新数据为额外源码观察。

建议：快照可以锁外序列化，但“代次校验 + 实际提交”和 clear 的实际删除必须进入同一串行提交器/锁，并给异步 clear 自身携带代次；不能只在写入前单独读一次 generation。

## S5 · P1：本地歌单编辑丢掉持久化失败结果，仍返回成功形状

位置：`local/LocalPlaylistRepository.kt:263`、`MainActivity.kt:3379` 和 `:3386`。

Store 的 writePlaylistWithTracks 已会在 commit 失败时返回 null 并还原内存快照；Repository.persist 却忽略这个返回值，addTrack/removeTrack/clear 仍返回希望写入的列表。界面的加入本地歌单路径无条件关闭选择框并提示“已加入”。

探针给现有本地歌单的写盘注入 commit=false：Store 回滚后实际曲目仍为 0，addTrack 返回的列表却有 1 首。这证明底层已有的失败判据没有抵达调用方，不是在推测磁盘满时的系统行为。批量调用和元数据创建的失败也应跟着核对，不能仅把 Store 的单测通过描述成端到端成功反馈已正确。

建议：persist 返回结构化结果，用户编辑入口只有在持久化提交成功后才返回新列表；调用方按结果提示并保留可重试入口。让失败回滚与 UI 提示的集成回归覆盖同一条真实路径。

## S6 · P1：B 站登录收尾的迟到 nav 能清掉新登录，或覆盖新账号会员资料

位置：`bili/BiliQrLogin.kt:299–306`，`bili/BiliAuthStore.kt:saveProfile/clear`。

finishLogin 保存 A 的凭据后等待 nav，再无条件保存 A 的资料或在 NotLoggedIn 时清凭据。它没有像网易/QQ 那样绑定登录代次。若期间退出 A、登录 B，A 的旧响应可以操作 B 当前的存储。

两个探针使用真实 BiliQrLogin 状态机和可注入的只读 API：先完成虚构 A 的扫码，挂起 finishLogin 的 nav，切到虚构 B 后再放行。旧 NotLoggedIn 清掉了 B 的凭据；旧 LoggedIn 则保留 B 的凭据，但把资料 mid 和会员状态改成 A。没有访问 B 站服务，没有使用真实 Cookie。

设置页 BiliAccountBlock 的常规资料刷新已有 cookie 相等检查，本条特指扫码登录收尾；UI 作用域取消能避免部分实际时序，尚未做真实界面并发复现。数据层仍须独立保证迟到响应只影响原会话，尤其是 clear 这种副作用。

建议：BiliAuthStore 引入会话 revision/login generation 与原子条件提交；finishLogin 在 clear/profile 写入前复核请求快照。新凭据覆盖旧凭据时清理不属于它的资料缓存，不能依赖模态框恰好阻止所有账号变化。

另在 `probes/2026-10-26-accounting/` 保存 2 条附加复现，以下 S7/S8 也尚未修复。

## S7 · P2：正在取链的曲目身份被用于实际收听统计

`PlayerViewModel.playSong` 在 URL 返回前就更新 currentTrack；这时旧 MediaItem 可以仍在播放、currentSongId 和标题也仍是旧歌。`StatsRecorder.onSample` 却使用 currentTrackKey 作为统计键。夹具构造了真实入口会产生的中间状态（请求 B，实际 id/标题仍为 A），调用真实采样器：A 的后续 500ms 被计入 B，并带上 A 的标题。

统计探针只填入采样器读取的 ViewModel 字段，没有创建 Android 生命周期或播放服务。它证明了该合法中间状态下的错记，未宣称做过慢网真机复现。后续应让统计读取由实际 MediaItem 过渡确认的原子播放快照，并区分请求身份与在播身份。

## S8 · P2：音频缓存枚举异常会清掉持久化离线元数据

`OfflineAudioCache.keys` 将异常降为 emptySet；`reconcileLibrary` 又把它当作已确认的空缓存，并调用 OfflineLibrary.retain。夹具令音频目录读取失败，预存离线条目由 1 变为 0，重建内存索引后仍为 0。音频字节与 URL 表没有被本探针删除，确认丢失的是管理页的持久化元数据。

对账入口应区分枚举成功为空与枚举失败；只有取得缓存真相后才允许裁剪索引。错误降级为空列表可用于显示，不能直接成为持久化删除依据。

## S9 · P2：聚合搜索共享翻页游标会跳过失败来源，分批发布重复累计来源数

`SearchViewModel.searchByType` 只要有来源成功，就会确认共享页号。网易第 2 页失败而 QQ 成功时，下次加载直接请求各来源第 3 页，网易第 2 页没有补取。`accumulateAndPublish` 对同一页先到来源和最终合并结果都做 append，最终 UI 虽按曲目去重，但来源计数仍可能重复。

真实 SearchViewModel 探针用只读 HTTP 拦截器令网易 offset=30 返回 500，QQ 各页成功；连续加载三页后，网易请求 offset 为 0、30、60，缺失第 2 页，未重试 30。网易实际仅 60 首去重结果，来源计数却为 90。两个表现由同一条异步分页测试确认，没有访问远程服务。

建议：各来源独立维护已确认游标与待重试页，合并结果发布采用该页替换或按 TrackKey 幂等提交；来源数由已合并的唯一结果计算，不能以多次发布的长度累加。

## S10 · P2：取消后的旧下载仍能截断已完成的新包

位置：`update/UpdateDownloader.kt:209` 的初始进度回调与 `:211` 的 FileOutputStream；`UpdateService.cancelDownload` 只 cancel 不等待旧阻塞下载退出，文件名仍按资产名共用。

探针在 A 下载初始进度回调暂停（真实回调边界的线程调度夹具），取消 A，再让同名新资产 B 完整下载；B 内容 1000 字节且比对一致。恢复 A 后，旧任务先打开覆盖输出流，随后才在循环里检查 cancelled；B 文件变成 0 字节。状态代次保护挡住旧进度，但没有保护文件副作用。

没有启动安装器，也没有证明系统会接受坏包；影响是已校验/显示就绪的文件可能被旧任务破坏，导致安装失败或重复下载。建议按目标身份隔离 part 文件并串行化同目标写入和最终交付；取消后的终止必须得到确认。仅在 FileOutputStream 前多读一次取消标记仍存在检查与打开之间的竞争。

## S11 · P2：响应 ETag 从未写入续传标记，URL 回退身份可拼接换包内容

位置：`update/UpdateDownloader.kt:160` 只写传入/已有 ETag，响应分支从不保存 response ETag；`:245` 附近 If-Range 只使用已有值。

本地 HTTP 先返回 ETag=v1 并中断一半传输；标记里的 etag 仍为 null。相同 URL、相同长度、没有资产 id 的回退身份换成 v2 后，下次请求只带 Range，未带 If-Range，下载器把 v1 前 500 字节和 v2 后 500 字节拼在一起并返回成功。

正常 GitHub 资产存在稳定 asset id，换资产 id 会触发重下；本条动态确认的是代码明示支持的无 id / URL 身份回退。APK 后续校验可能拒绝坏包，不能描述成绕过系统签名。建议收到响应头后、写入任何字节前原子记录实体校验信息；回退身份缺少可靠验证器时放弃拼接，安全整包下载。

## S12 · P2：首页清缓存后仍被标成刚预热，可能跳过初始加载

位置：`cache/ContentCache.kt:77` 的 clearAll 未清 homeWarmedUpAt；`ui/screen/HomeScreen.kt` 初始 LaunchedEffect 首行以 isHomeFresh 直接 return。

真实缓存入口探针先 markHomeWarmed，再 clearAll：三个首页字段均为 null，isHomeFresh 仍为 true。首页新实例在 15 秒窗口内进入时，会保留初始 isLoading=true 并跳过取数。这一 UI 后果来自调用方源码，未做 Compose/真机页面复现。清理入口既有设置页，也有 Application.onTrimMemory/onLowMemory。

建议清内容与失效新鲜度原子配套，页面只有具备可用内容才跳过加载；测试要同时覆盖清理后的 freshness 与首页初始加载决策。

## S13 · P2：首页空列表快照写入没有替换旧值

位置：`cache/HomeSnapshot.kt:78` 至 `:85` 对全部非 null 列表再过滤 isNotEmpty，`:86` 却仍推进 saved_at。

探针预存 daily=[101]，调用 save(daily=emptyList()) 并等真实异步 apply 完成；保存时间已更新，重建内存再 restore 后仍读到 101。确认的是空参数保存语义，未把服务端异常缺数组冒称为权威空表。

建议先由 API 区分有效空表与读取失败；持久化层明确 null=未提供、空列表=成功清空，失败不提交。与账号归属隔离一起处理，避免旧推荐在冷启动时再次出现。

## S14 · P2：首页个性化缓存没有账号归属，换账号后可读到旧资料

位置：`cache/ContentCache.kt` 的 homeDailySongs/userProfile；`cache/HomeSnapshot.kt` 的无 owner 全局键；CookieManager 登录/清凭据和 SettingsAccountSection 登出没有使这些缓存失效。

探针经真实 CookieManager 写入虚构 A，填入 A 的首页与头像资料，再 clearCookie 并登录虚构 B：B 凭据已经生效，内存资料仍为 A。清内存模拟重新加载，HomeSnapshot 又能不检查 owner 地恢复 A 的推荐。HomeScreen 首帧直接消费这些缓存，私人 FM 资料有缓存时还会跳过新取资料。

没有证明跨账号凭据混用或云端数据写错账号；影响是首页推荐/昵称/头像的展示归属。建议对个性化块绑定账号 owner 与登录代次，切换时清理内存并过滤磁盘恢复；公共榜单与新歌可以独立复用。异步旧响应与快照写盘也必须携带原会话，避免刚清掉又被旧请求写回。

## S15 · P1：队列读写把协程取消当故障，删除正常队列并撤销新写入

位置：PlaybackStateManager.flushPendingQueue/getQueue 的 catch(Exception) 均调用 clearQueue，包含 CancellationException。

两条真实入口复现：正常 JSON 读出后取消调用方，解析挂起点抛取消，正常落盘队列被删除；A 队列正在 Default 序列化时保存 B，A 被取消后调用 clearQueue，连 B 的 pendingQueue 和新 flushJob 也撤销。后一条用 AbstractList 在序列化读取处控制线程，不改业务实现。

建议取消原样传播；读失败不做破坏性清理；写盘快照按代次串行提交，旧任务失败也无权清新任务。显式清队列进入同一提交序列。

## S16 · P2：退出播放器清掉用户保存的播放模式

PlaybackStateManager.clearState 清整个 ncrust_playback_state，PlayerViewModel.stopService 调用它。探针保存 mode=3 和曲目后清状态，模式读出变为默认 0。MainScreen 重建会读此值，与 clearQueue 注释“不改变模式”冲突。整文件清空也触及续播表，内存 positionCache 却没清，冷热口径不同（附加静态观察）。建议拆开当前状态、队列、偏好与续播记录的删除边界。

## S17 · P1：收藏/取消独立异步写入，旧意图可能最后提交

LibraryManager.pushLike 每次独立 launch。探针经真实 saveSong -> removeSong，解析真实 EAPI 加密载荷，在模拟服务端先提交后发的 false，再提交迟到的 true：本地 tombstone 有效，模拟云端却变回 true。没有真实网络或云端写入，证明的是本应用允许反序提交，未宣称验证网易生产服务内部排序。

建议按账号和曲目串行写入，保存最新期望方向；前次完成后按最新意图补一次，不盲目重放最初动作。专辑 sub/unsub 具有相同发射形状，应一并检查。

## S18 · P1：收藏回退重新取当前凭据，A 的操作可发给 B

PlaylistApi.likeSong 的 xeapi/weapi/eapi 回退没有统一登录快照，各 RetrofitClient 入口重新读 CookieManager；LibraryManager 排任务前也未绑定归属。

探针在 A 会话的 XEAPI 前置 token 请求挂起，登出 A、登录 B，再放行且令公钥不可用；后续 EAPI 请求携带 B 的凭据发出原操作，API 返回成功。HTTP 全部拦截。JVM Android Base64 是空桩，不能执行正常 WEAPI 加密；最初等待 WEAPI 的探针初始化方案因此失败，修正后从真实 XEAPI preflight 控制时序，不把 WEAPI 当动态覆盖。

建议动作创建时捕获登录代次与凭据，全通道固定归属，切账号后停止未发出的副作用，禁止拿新账号兜底。PlayReporter 在新线程重新取 Cookie 属于同类静态观察，尚未单独做线程复现。

## S19 · P1：局域网扫码广播公开了回传会话的全部密钥材料

QrPair.requestPayload 明文广播 unikey，key() 只由此派生 AES；QrPairClient.discover 接受第一个同 unikey 响应，向其来源地址和端口发送凭据。

纯协议探针从合法发现报文解析 unikey，构造可通过客户端 token/port 判断的应答，并用该公开材料解密虚构 Cookie。未发 UDP 广播、未建立攻击连接、未读取真实会话。结合客户端接收条件，在可伪造局域网发现响应的环境中，错误接收方可抢先应答并解密回传；不需要破解 AES。这不是已发现用户凭据实际泄露的证据。

建议接收设备以二维码绑定的公钥/秘密认证，发现报文不得包含全部秘密，回传使用完整性校验加密。只改成 GCM、仍用公开广播派生密钥不能解决冒充。修复保留官方扫码兼容性，旧的无认证回传不能静默回退。

## 证据与断点

`probes/2026-10-26-followup/` 保存 `FollowupBoundaryProbe.kt`、XML、构建日志和摘要。7 个探针全部复现；通过表示已确认缺陷，尚不表示修复。临时测试源已移出常规测试目录，未进入 v3.7.7 的 APK，正式版本仍对应已验收的 3100 项测试。

`probes/2026-10-26-accounting/` 的 2 个用例和 `probes/2026-10-26-search/` 的 1 个用例也全部通过缺陷复现断言。搜索探针需要归档的 MainDispatcherFactory service，并设置 JAVA_TOOL_OPTIONS=-Dkotlinx.coroutines.fast.service.loader=false；首次未关闭 Android fast loader 的运行仅为探针环境初始化失败，修正环境后的成功 XML 与日志保存在 search 目录。全部 10 个用例断言的是已知错误行为，不应计为修复回归通过。

本轮在五小时额度剩余约 11% 时收尾；后续断点为更新下载/安装链路、页面生命周期和异步读取的完整性，以及以上九类问题的修复。正式应用源码、v3.7.7 tag 和 APK 不因本轮审查发生变化。源码 review 不执行真实账号写操作。

2026-10-26 晚续审：`probes/2026-10-26-update-home/` 另存 5 条缺陷复现、两份 XML、摘要与成功构建日志。用户明确要求继续审查、重置前不修复。已建立本聊天单次自动续跑：2026-10-27 01:43（Asia/Shanghai），先核对 01:41:34 的五小时窗口已重置，再统一修复所有未解决条目；不做真机测试，发布仍只创建草稿。

继续审查补充：`probes/2026-10-26-playback-cloud-pair/` 归档 6 条复现（队列/模式 3、云端写入 2、配对协议 1）。均通过已知缺陷断言；不代表问题已修复。
