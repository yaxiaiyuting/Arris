# Arris 当前 Bug 汇总

整理日期：2026-10-27（Asia/Shanghai）。

## 范围与状态

本文件汇总截至本次整理已经发现、尚未修复的问题，不代表完整扫描了所有源码，也不把历史上已经修复的问题重新计为待修复。

- 审查基线：**v3.7.7-gpl / versionCode 93 / 应用源码 `4e9d763`**。
- **22 类问题已有本地业务入口、状态夹具、路由或协议层复现，共 26 个用例。**
- 另有 **3 项源码证据明确、尚未补动态复现的问题**，以及 **1 项需要评估的跨源匹配策略问题**，单独列出。
- “复现用例通过”表示断言确认了错误行为存在，**不表示修复完成**。
- 所有已记录复现均未做真实账号云端写入或真机测试；部分问题的 UI 后果由调用方源码推导，详情中已说明。
- 本次仅新增本汇总文档，不修复源码、不运行测试、不升级版本、不打包或发布。

P1：优先处理的数据丢失、会话归属、安全或核心音源路由问题。P2：随后处理的功能、显示、缓存与恢复问题；优先级不表示每个用户都会触发。

## 已复现问题速览

| 编号 | 优先级 | 问题 | 主要影响 |
|---|---|---|---|
| S1 | P2 | QQ 部分/降级歌单被当作完整同步成功 | 伪成功、10 分钟内不补取 |
| S2 | P1 | 在途本地同步覆盖删除或重建已删除歌单 | 删除意图丢失、内容复活；2 条复现 |
| S3 | P2 | QQ 按账号清缓存匹配错字段 | 漏清本账号、误清其他账号缓存；维护入口 |
| S4 | P1 | 清空统计后旧快照仍能反写 | 被清除的历史重启后重新出现 |
| S5 | P1 | 本地歌单写盘失败仍返回成功形状 | 实际未保存却提示已加入 |
| S6 | P1 | B 站旧登录收尾跨会话写入 | 清掉新账号凭据或覆盖其会员资料；2 条复现 |
| S7 | P2 | 待播身份被用于实际收听统计 | A 的时长和标题可能记到 B |
| S8 | P2 | 缓存目录读取失败被当作空目录 | 持久化离线管理索引被清空 |
| S9 | P2 | 聚合搜索共享游标与重复累计 | 失败来源跳页，来源计数偏大 |
| S10 | P2 | 已取消旧下载仍能改写同名新包 | 新包完成后可被截成 0 字节 |
| S11 | P2 | 下载响应 ETag 未保存 | 无资产 ID 的 URL 回退续传可拼出混合文件 |
| S12 | P2 | 清首页缓存未清预热时间 | 空缓存仍被视为新鲜，首页可能跳过加载 |
| S13 | P2 | 空列表快照不覆盖旧值 | 时间更新但旧推荐冷启动后再出现 |
| S14 | P2 | 首页缓存缺少账号归属 | 换账号后仍可能显示旧推荐、昵称和头像 |
| S15 | P1 | 队列读写把取消当异常并清队列 | 正常队列丢失，旧写入还能撤销新写入；2 条复现 |
| S16 | P2 | 退出播放器清空整份播放偏好 | 播放模式恢复默认，续播冷热状态不一致 |
| S17 | P1 | 收藏/取消并发反序提交 | 模拟云端以旧意图收尾，本地与云端不一致 |
| S18 | P1 | 异步云端写操作未绑定原会话 | 收藏回退或排队建歌单可改用新账号凭据；2 条复现 |
| S19 | P1 | 局域网扫码发现公开回传密钥材料 | 可伪造发现应答的接收方可能获得可解密凭据 |
| S20 | P2 | 车机浏览重建 QQ 曲目时丢失 songmid | 原本可解析的曲目变为无法取链 |
| S21 | P1 | 旧收藏的身份与取链载荷来源不一致 | 已判为 QQ 的条目仍发往网易 Provider |
| S22 | P2 | 网易歌单写入没有拦截跨源合成 ID | QQ/B 站 ID 原样发往网易写接口 |

## 已复现问题详情

## S1 · P2：不完整/降级的 QQ 歌单被当成本地同步成功

位置：`local/LocalPlaylistRepository.kt:80` 的 `PlaylistResult.Data` 分支、`:121` 的同步提交；`local/LocalPlaylistModels.kt:322` 的 10 分钟 TTL。

`loadRemoteSongs` 把任何 Data 都解包成 songs，丢掉 `fresh/fromCache/degradation`。已知 TRUNCATED 的 1/100 条缓存、离线回退甚至 NEED_LOGIN 回退都能走这条路径。`sync` 因而返回 Success，并把 lastSyncedAt 改为现在；后续按需同步在 10 分钟内不再重试。

探针预存 complete=false、total=100、仅 1 首的 QQ 详情，先确认仓库返回 TRUNCATED，再调用真实本地同步：结果为 Success，lastSyncedAt 更新，1 秒后的 shouldAutoSync 为 false。并未丢弃本地旧条目，但界面把部分数据说成同步成功，拖延了补齐/续期入口。

建议：远程读取返回完整性与失败原因；可按只加不减合并部分数据，但不能更新“完整成功”的时间戳或显示完整成功。明确区分离线、需要登录、截断和成功空表。

## S2 · P1：已经删除的本地歌单会被在途同步重新创建

位置：`local/LocalPlaylistRepository.kt:120`、`:125`；`local/LocalPlaylistStore.kt:144` 附近的 `idx < 0 -> list + updated`。

同步发请求前持有 LocalPlaylist 对象，回来后没有复核该歌单是否仍存在、删除代次是否变化。写入函数既负责更新也允许新建，因此删除歌单后，旧同步仍可把旧元数据和远程曲目重新插入列表。

探针在真实网易只读请求入口暂停响应，删除本地歌单并确认列表为空，再放行响应：同步返回 Success，原 key 又出现在本地列表。HTTP 全部被应用拦截器截获。正常界面删除后会返回上页，页面作用域取消可能避开部分时序；数据层本身没有删除闸门，不能把 UI 及时销毁当作保证。

补充复现：`LocalSyncRevisionAuditProbe` 让同步先读取旧曲目快照，随后用户移除曲目并确认 tombstone 已提交，再恢复同步；旧同步覆盖了新的删除标记，曲目重新可见。因此修复不仅要阻止歌单重建，也必须保护同步期间发生的增删曲目修订。

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

补充复现：`PlaylistMutationAuditProbe.aQueuedPlaylistCreateIsSentUnderTheNextAccount` 在共享写入门控被占用时，以 A 会话调用创建歌单，再切到 B 后释放门控。真实写接口携带 B 凭据发送请求并接受模拟成功响应。故该问题也覆盖歌单创建等排队写操作，不只收藏回退。

建议动作创建时捕获登录代次与凭据，全通道固定归属，切账号后停止未发出的副作用，禁止拿新账号兜底。PlayReporter 在新线程重新取 Cookie 属于同类静态观察，尚未单独做线程复现。

## S19 · P1：局域网扫码广播公开了回传会话的全部密钥材料

QrPair.requestPayload 明文广播 unikey，key() 只由此派生 AES；QrPairClient.discover 接受第一个同 unikey 响应，向其来源地址和端口发送凭据。

纯协议探针从合法发现报文解析 unikey，构造可通过客户端 token/port 判断的应答，并用该公开材料解密虚构 Cookie。未发 UDP 广播、未建立攻击连接、未读取真实会话。结合客户端接收条件，在可伪造局域网发现响应的环境中，错误接收方可抢先应答并解密回传；不需要破解 AES。这不是已发现用户凭据实际泄露的证据。

建议接收设备以二维码绑定的公钥/秘密认证，发现报文不得包含全部秘密，回传使用完整性校验加密。只改成 GCM、仍用公开广播派生密钥不能解决冒充。修复保留官方扫码兼容性，旧的无认证回传不能静默回退。

## S20 · P2：车机浏览重建 QQ 曲目时丢失取链载荷

位置：`player/PlaybackService.kt` 的 `songItem`、`resolveMediaItem`；`source/SongSourceExt.kt` 的 `songRefOf`；`source/SourceRouter.kt`。

车机浏览项仅携带音源与数字 ID。解析回 `SongItem` 时未补回 QQ 必需的 `sourceId/songmid` 与 `mediaId/media_mid`，于是原本完整可解析的 QQ 曲目变成 `isResolvable=false`，被 Router 拒绝。

`RoutingAuditProbe.carMediaIdRoundTripDiscardsQqPayloadRequiredForResolution` 已按真实构造链和真实 Router 复现。未启动实际车机或完整 PlaybackService，验证范围是路由与载荷丢失。建议从受信任的本地曲目记录恢复完整身份，或在浏览载荷中可靠传递并验证这些字段；不要回退到网易源。

## S21 · P1：旧收藏迁移只修复身份，没有修复歌曲载荷的取链来源

位置：`library/SavedSongCodec.kt` 的旧条目解码、`library/LibraryManager.kt:getSavedSongs`、`source/SongSourceExt.kt:musicSource`、`source/SourceRouter.kt`。

兼容的旧收藏条目中，QQ 合成 ID 可以使 `entry.trackKey.source` 正确恢复为 QQ，但 `entry.song.source` 仍缺失。库页直接取 song，Router 又按 song.musicSource 路由，后者将缺失来源解释成网易，于是身份用于判重时是 QQ，实际取链却走网易。

`RoutingAuditProbe.legacyQqCollectionIdentityAndResolvedProviderDisagree` 已通过真实 Codec 与 Router 确认：QQ 合成 ID 到达了注入的网易 Provider。没有访问真实网易服务，未证明实际播出了另一首歌曲。建议迁移时让完整载荷与权威身份一致，并统一判定来源，保留 QQ 所需 mid，拒绝互相矛盾的身份。

## S22 · P2：网易歌单写接口接受 QQ/B 站合成 ID

位置：`MainActivity.kt` 的全局“加入歌单”菜单与 `pendingAddSongs`；`network/PlaylistEditApi.kt:addSongs/removeSongs`。

全局菜单对 QQ/B 站歌曲也能打开网易云歌单选择，最后只传裸数字 ID；写接口未用 SourceIds 拦截其他音源。`PlaylistMutationAuditProbe.ncmPlaylistMutationAcceptsQqAndBiliSyntheticIds` 解析真实 EAPI 请求，确认两类合成 ID 原样进入网易歌单写接口。模拟响应拒绝了请求，未修改真实云端，也未证明服务端会加入错误歌曲。

建议 UI 明确可用目标音源，数据层在唯一写出口再次拒绝跨源/非法 ID。本地歌单仍可保留多音源；不要自动拿同名曲替换用户选择。

## 源码已定位、尚未补动态复现的 3 项问题

### F1 · P2：车机路径的试听时长单位错误

`PlaybackService.resolveMediaItem` 对 `freeTrialInfo.end-start` 又除以 1000；同一响应模型在 `account/Entitlement.kt:classifyPlayback` 中按秒直接使用。典型 start=0、end=45 在车机路径得到 0 秒，并交给 `onTrialResolved` 和提示跟踪器。

源码已能确认两条路径单位不一致；未运行完整车机回调验证。建议统一模型单位与转换入口，避免将显示时长写成 0。现有 `currentIsTrial=true` 的设置仍会执行，不能把本条描述成已证明试听被当作完整歌曲上报。

### F2 · P2：车机路径忽略移动数据音质偏好

`PlaybackService.currentQualityLevel` 固定读取 `wifi_quality`；手机主路径按当前网络在 `wifi_quality` 与 `mobile_quality` 中选择。车机经移动网络点播时，可能使用比用户移动数据配置更高的音质，增加流量。

这是源码调用链确认，未做移动网络/车机实测。建议复用同一网络与音质偏好决策，避免两套实现漂移。

### F3 · P2：云端加入歌单失败仍弹成功提示

`MainActivity.kt:addSongsToPlaylist` 已根据返回 count 计算 SUCCESS/FAILED，却无条件调用传入的成功 Toast 文案；选择歌单的调用点传入 `addToPlaylistSuccess`。

所以请求失败时，上层返回 FAILED、选择框可能继续保留，但用户仍看到成功 Toast。源码确认，未执行 Compose 界面测试。建议只在成功时使用成功文案，失败给出对应结果；批量部分成功也应单独表达。该问题不同于 S5 的本地写盘失败。

## 待确认的策略问题

### C1：跨源匹配可能合并原唱与伴奏、Live 与 Remix

位置：`crosssource/NameNormalizer.kt`、`CrossSourceMatcher.kt:gradeTrack`、`CatalogAggregator.kt:assembleSongsWithProbe`、`PlayAllDedup.kt`。

括注被归一化去除，版本标记只比较“有没有”，未比较标记种类；时长相近且艺人相同时，原曲/伴奏会被评为 HIGH，而 Live/Remix 都含标记，可能被评为 EXACT。合并后，可播放的另一源版本能替换原行，或在“播放全部”中吸收掉一份。

**状态：源码观察，专门探针尚未编译通过，未计入 26 个成功复现用例。** `EditionMatchAuditProbe.kt` 当前因 ArtistItem 导入错误停在编译阶段。本次仅整理文档，没有修正或重跑它。

现有部分测试明确锁定了“同等时长的原曲/伴奏、原曲/Remix 可以合并”的当前策略，因此不能把旧测试通过当作版本语义已经正确，也不能在没有评估的情况下宣称这项已动态证实。后续需区分录音版本差异与专辑包装版本词；若调整匹配规则，应复核既有样本，并推进 MatchCacheCodec 的 ALGORITHM_VERSION，使旧匹配结论失效。

## 证据索引与当前验证边界

前 21 个成功缺陷复现用例已归档在以下目录：

| 证据目录（相对本文件） | 用例数 | 内容 |
|---|---:|---|
| `probes/2026-10-26-followup/` | 7 | S1–S6；B 站旧响应包含两种结果 |
| `probes/2026-10-26-accounting/` | 2 | S7、S8 |
| `probes/2026-10-26-search/` | 1 | S9 两种表现 |
| `probes/2026-10-26-update-home/` | 5 | S10–S14 |
| `probes/2026-10-26-playback-cloud-pair/` | 6 | S15–S19；队列包含读写两种取消 |

追加 5 个成功用例的原测试源仍在 `app/src/test/java/com/takahashirinta/ncrust/`：

- `source/RoutingAuditProbe.kt`：2 个，对应 S20/S21。
- `network/PlaylistMutationAuditProbe.kt`：2 个，对应 S18 排队写入和 S22。
- `local/LocalSyncRevisionAuditProbe.kt`：1 个，对应 S2 删除标记被覆盖。

这 5 个用例的成功日志在仓库外：`/home/duanjb666/deepseek/dist/routing-playlist-revision-audit-20261026-build.txt`。XML 位于 `app/build/test-results/testDebugUnitTest/`，属于可能被后续测试替换的构建产物，尚未完成归档。

另有 `crosssource/EditionMatchAuditProbe.kt` 未编译完成，不能把当前工作区描述成“全套测试已通过”。它是审查用临时探针问题，不是已交付 v3.7.7 APK 的构建失败。修复/回归验证应以实际完成后的新结果为准，不能沿用本文件中的错误行为断言作为验收。

## 建议修复顺序

1. S19：局域网配对会话回传的接收方认证与密钥暴露。
2. S2、S4、S5、S15：先保护用户删除意图、持久化数据与播放队列。
3. S6、S17、S18：统一异步副作用的会话归属与最新意图顺序。
4. S21、S20、S22：修正旧数据和车机路径的音源载荷，封住跨源写接口。
5. 其余缓存、统计、分页、更新与提示问题；C1 先验证策略再修改。

## 已在 v3.7.7 修复的历史问题（不计入待修复清单）

| 历史问题 | 已交付修复 |
|---|---|
| 旧网络/降档重试抢回播放权 | 重试绑定曲目与播放代次；新播放、暂停、停止撤销旧任务 |
| 收藏库冷加载时修改覆盖未加载数据 | 修改前在锁内加载，真实缓存实例读改写，快照落盘串行 |
| 第二次取链网络异常归类遗漏 | 末次 IOException 也归为 NETWORK，并使用单调时钟冷却 |
| 网易收藏专辑只取部分、异常当空表 | 严格响应校验、完整翻页，失败保留旧库 |
| QQ 附加收藏失败丢掉旧条目 | 完整分页；降级时保留旧项与已取得新项，并允许重试 |

v3.7.7 的交付验收为 3100 项 JVM 测试零失败、Lint 零 Error/Fatal，debug/release 构建完成；这属于既有交付记录，不能推导以上新问题已经修好。详见 [v3.7.7 验收记录](../verification/v3.7.7/RELEASE_VERIFICATION.md)。
