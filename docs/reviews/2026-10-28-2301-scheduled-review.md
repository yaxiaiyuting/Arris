# Arris v3.7.10 定时续审（2026-10-28，实际 23:01 起）

触发标签为 `arris-11-50`，实际启动为北京时间 23:01，不声称准点执行。只审查、隔离复现与留档，修复交由用户安排。

开始 HEAD `e56adcc704ce88aea43721aa56343bf32c8a34e0`，业务基线 `e28a9583762075cbfbc9453679c83f89f110f053` / v3.7.10-gpl / versionCode 96。共享工作树存在 31 个他人已修改的跟踪文件，包含新版本及歌词分享等工作；本轮没有将其当作已交付修复，也没有覆盖、暂存或提交这些改动。全部业务阅读及探针运行基于 `git archive HEAD` 的隔离快照。原始状态、文件指纹、快照路径见 [initial-state.json](probes/2026-10-28-2301/initial-state.json)。

开始实测五小时剩余 96%、周 99%。配额随阶段记录，任一窗口接近 10% 则停止扩展；不消耗重置。

## 本轮有隔离执行证据的发现

### N01 / P2（控制器逻辑）：旧 B 站扫码响应将新一轮会话改成 CANCELLED

- **触发**：同一 `BiliQrLogin` 还有在途申请/轮询时，执行 `reset` 并开始新一轮；新一轮已进入 WAITING 或 CONFIRMED，旧请求随后返回。
- **源码**：`bili/BiliQrLogin.kt:169–171,260–262,297–299` 检出旧代次后仍调用 `emitTerminal(CANCELLED)`；`428–430` 会改写共享 `state` 并调用旧回调。generation 校验保护了新凭证不被旧凭证替换，但没有保护新会话的状态。
- **证据**：`NightBiliGenerationAuditProbe` 两项测试直接执行真实 `prepare/pollOnce/reset`。在途申请被新申请取代后，旧响应把新 WAITING 改成 CANCELLED；新轮询已 CONFIRMED 后，旧 Pending 响应仍把它改成 CANCELLED，新凭证在内存里未被换掉。接口全部为进程内 Deferred，未发 HTTP，没有 `finishLogin`、持久化或真实账号操作。
- **与既有 E08 的关系及限制**：v3.7.9 修复已挡住“取消后旧响应重新写入凭证”的原分支；本项是新增代次处理引入/暴露的“旧任务取消新会话”。实际 `BiliQrLoginDialog.kt:159–188` 还由 `LaunchedEffect(generation)` 撤销旧协程，可能另行阻断该时序。因此这里只确认控制器契约，没有把它说成真实界面必现登录失败。
- **建议**：区分“本轮被显式取消”和“已被另一轮替代”；失去发布权的旧请求直接退出，不发布任何状态或回调。当前会话的取消应由当前 owner 执行。

### N02 / P2（生产编解码 + 收藏逻辑）：B 站视频身份迁移产生同 key 重复记录，随后无法取消收藏

- **触发**：旧版保存的视频身份为 bit61 | aid；v3.7.9 将新视频身份改成 bit61 | bit60 | aid，但当时未迁旧收藏。因此用户在 v3.7.9 再收藏同视频，可持有两条不同 ID 的记录。v3.7.10 读取时补位，把它们归一成同一 TrackKey，却仍保留两条。
- **源码**：`library/SavedSongCodec.kt:155–160,260–267`；`source/MusicSource.kt:310–315`；`library/SavedSongModels.kt:261–268` 的 `remove` 只处理 `indexOfFirst`，第一条已 tombstoned 则直接返回。真实 `LibraryManager.kt:511–528` 使用同一删除函数。
- **证据**：`NightIdentityAuditProbe` 的两个 Bili 用例使用旧 ID、新分区 ID 和当时已有的 `sourceId=bv:…`，经真实 `encode/decode` 得到相同 key 的两条记录，再执行真实 `remove`。第一次删除仍留一条可见，继续删除无效；若旧删除标记排在前面，则第一次删除就是空操作。没有 Android UI、真实磁盘或网络。
- **历史依据**：`c0bcb97` 的 BiliModels 改了新生成 ID，但未改 SavedSongCodec；迁移在 `e28a958` 才加入。因此无需假设旧版本存过未来字段。共享工作树对 SavedSongModels 的未提交变更只刷新云端载荷，没有改变该删除分支。
- **影响/建议**：视频收藏可能重复显示且无法取消。迁移后按规范身份合并记录，明确手动收藏、删除标记、时间及载荷的冲突规则；删除逻辑不应被重复条目中的首个墓碑挡住，不能简单 distinctBy 丢掉删除意图。
- **QQ 用例降级**：本轮另有两个纯逻辑用例确认“旧散列 + qq_songid”同样会重复。但 qq_songid 与迁移在同版引入，尚未找到旧散列记录同时携带该新字段的真实生产者，因此 **QQ 历史可达性仅为待确认，不计作已确认 QQ 缺陷**。初期阶段记录的 QQ 推论以本条限制为准；原始探针保留供复核。

### N03 / P2（生产请求 + 真实依赖重定向构造）：B 站账号域重定向仍可保留 Cookie 到域外

- **触发**：一个携带账号 Cookie 的 `*.bilibili.com` GET 返回跨域重定向。这里没有断言真实 B 站当前一定返回该重定向，也没有证明第三方可以控制其 Location。
- **源码**：`bili/BiliApi.kt:219–223,308–345` 只在最初构造 Request 时检查账号域，客户端仍使用默认自动重定向，未安装逐跳凭据过滤。相同 OkHttp 4.12 重定向构造会保留显式 Cookie/Referer；初始白名单不会再执行。
- **证据**：`NightRedirectAuditProbe` 先执行真实私有 `BiliApi.get`，在应用拦截器本地返回固定正文并捕获真实 Request，再把带测试 Location 的 Response 交给当前依赖的真实 `RetryAndFollowUpInterceptor.followUpRequest`。新请求目标为 `fixture.invalid`，Cookie 仍含虚构 SESSDATA，Referer 也保留。**新请求只构造、绝未发送**；没有外部 DNS、真实账号、真实响应或泄露事件。
- **与 H17 的关系/建议**：H17 原先“直接取第三方字幕时带 Cookie”的分支已修；本项补充自动重定向的绕行路径，不把原分支重报。应在每一跳发送前校验实际目标并移除域外凭据，或关闭自动重定向、按明确策略重新构造请求；同源正常重定向需保留。

### N04 / P1（实际编译手势协程）：禁用队列交互时空转，不等待下一次指针事件

- **源码**：`ui/player/QueueView.kt:244–250` 把 `if (!interactive) return@awaitEachGesture` 放在 `awaitFirstDown` 之前。真实 Compose `awaitEachGesture` 在 block 返回后等待所有指针抬起；当前本来没有按下指针时，这一步会立即完成，于是下一轮仍立即返回。没有任何挂起点。
- **证据**：`NightQueueSpinAuditProbe` 反射实例化并执行实际编译的 `QueueViewKt$QueueView$dragGesturesModifier$1`，不是重写它的循环。仅提供空指针事件的接口夹具与 Compose State；观察到 64 次 currentEvent 读取、0 次 awaitPointerEvent，在第 64 次抛出有界停止信号，真实 finally 完成清理。没有启动 Compose UI、设备或无限忙等进程。
- **真实接线及限制**：`PlayerCardState.kt:127,138` 的展开子树挂载阈值为 progress > 0.01、输入阈值为 > 0.9；`PlayerCardExpanded.kt:483–488` 传入这个输入条件。过渡中存在“子树已挂载、输入禁用”的区间。若该指针处理器在 false 状态收到/恢复执行，并到达无按下指针的状态，就会在其线程上忙等；Android UI 中可能表现为动画和点击停滞。没有运行真实布局命中或复现 ANR，不把所有展开/收起都描述为必然卡死。
- **与 E10 的关系/建议**：新版本已补 finally 和身份提交；空转由新增禁用门控的位置造成。应在整个 pointerInput 块外退出，或让每个 awaitEachGesture 分支先有可靠的事件等待，再决定是否处理；禁止无挂起地重复返回手势块。补覆盖 idle + disabled、按下期间禁用、再次启用的协程行为。
- **夹具调试记录**：初版探针把普通 Continuation 传入 restricted suspension，夹具在进入目标分支前报上下文错误；已仅修正夹具的 EmptyCoroutineContext 转接并重跑。原失败日志另存 fixture-debug，不能记成业务失败或本缺陷证据。

### N05 / P1（静态，K01 的另一时序）：两次联网开播请求仍由先返回者抢先落定

- **源码**：`MainActivity.kt:2149–2180,2236–2247,2259–2274` 的 FM/歌单/专辑入口均读取 `playRequestGeneration.current`，没有在发起新的联网用户意图时推进；`pendingPlayJob.getAndSet(launch(...))` 的旧 Job 返回值被丢弃，未取消。队列替换后的 `playFromQueue` 才产生新的播放代次。
- **触发/影响**：冷缓存点击歌单 A，随后点击歌单 B；两次都捕获 G。A 先返回时仍判当前、开播并推进代次；用户后点的 B 随后返回反而被丢弃。快速连续选择时可能播放 A，而不是最后选择的 B。FM 还在 IO 上判代次后再切 Main，提交前缺少第二次核对。
- **证据范围**：真实入口、代次读取/领取与替换调用链静态核对，没有运行 Compose 或真实播放；没有用复制条件的镜像测试冒充业务入口复现。
- **建议**：用户动作产生时领取独立的开播意图代次，撤销被替代的在途 Job；IO 完成、切回 Main 后、队列替换前复核同一 owner。不要等真正开播才决定哪次用户意图更新。

### N06 / P2（生产重排函数）：拖动“下一首区”的重复曲目，会移动当前在播的那一份

- **源码**：`ui/player/QueueView.kt:393–406` 按 TrackKey 的 indexOfFirst 重定位；`MainActivity.kt:785–828` 的 `reorderByIdentity` 再按同样方式选首个匹配。`QueueView.kt:662–678` 已明确通过出现序号允许同 key 的多个队列行，但提交与悬浮仍没有条目级身份。
- **证据**：`NightQueueIdentityAuditProbe` 执行真实 `reorderByIdentity`，输入 `[当前 A, 将播 A, B]`，拖将播 A 到 B 后，结果把当前 A 移到末尾，currentIndex 从 0 变成 2，将播 A 留在首位。两份 A 图文可区分，确认移动的是错误的实例，而非仅列表数值巧合。
- **可达性/限制**：重复行是新行模型显式支持的情况；`PlaybackStateManager.kt:411–440` 的逐条身份迁移也可把此前两个不同 ID 的同一视频合为同 key，而不删除队列项。没有执行真实拖拽或音频，界面与后续 Next 的影响属于静态接线结论。
- **建议**：整个拖拽事务携带稳定队列条目 ID；TrackKey 只表示曲目，不足以表示同一曲目在队列中的哪一次出现。若选择去重策略，也必须先明确用户队列语义，不能只在提交时默默选第一条。

## 既有问题的补证（不重复计入新问题数）

- **J08 / S9 搜索失败页确认仍存在**：本版 SearchViewModel 相较上轮仅给 clearQuery 加取消；页累积仍无条件确认网易页。`NightSearchResidualAuditProbe` 在当前快照复跑 2 条原缺陷断言：HTTP500 的第 2 页后请求第 3 页、跳过失败段；HTTP200/code500 仍成为 DONE、空结果、hasMore=false。全部 HTTP 本地拦截，未发真实搜索。原问题继续待处理。
- **H09 / R3 存储失败事务的删除分支补证**：`LocalPlaylistStore.kt:238–243` 删除元数据失败后，commitValues 已回滚元数据，但仍调用 removeTracks。`NightLocalStoreResidualAuditProbe.failedMetadataDeletionStillDeletesItsTrackTable` 确认返回 false、歌单仍存在、曲目表却空了。仅 MemoryPrefs 注入单次失败，不是实际磁盘试验。
- **H09 新建之外的转存分支补证**：`LocalPlaylistRepository.kt:376–395` 的 adoptRemote 继续忽略 writePlaylists 的 false，返回一个并未入库的歌单。相同探针的另一用例确认。真实 QQ 详情页仍可能据此显示转存成功；后者仅静态证据。
- **E01 / E06 / E07**：缓存删除失败保索引的底层分支已修，界面仍无条件成功 Toast；预载封面交接和波形旧说明保持原有待处理状态。没有把这些旧分支重复列成新编号。

## 基线与修复复核

v3.7.9 / v3.7.10 的 release notes 与修改源码已开始核对。已知问题不因改了版本就自动关闭，也不重复计入本轮新问题。E06 预载封面交接的相关实现本轮版本没有改变，保持原报告待处理状态；旧探针的成功结果不沿用为新版本验证。

## 用户停止时的证据与覆盖结论

北京时间 23:29 左右，用户明确要求“审查源码到这里先停止”，并转向双语双层歌词功能讨论。本轮立即停止扩展源码审查，仅整理已经完成的阅读、探针和结论；未继续补读剩余模块。现存定时任务 `arris-11-50`、`arris-17-00` 已通过应用工具暂停并回读 PAUSED；未发现 06:43 对应的本地任务配置，不补建或重启任务。

本轮正式记录 **6 类新发现/修复边界**：N01、N02、N03、N04、N06 有隔离生产逻辑或真实依赖逻辑证据，共 **7 条缺陷断言**；N05 为静态调用链结论。它们不是 6 个都在本版新引入的回归，报告已说明各自与 E08/H14/H17/E10/K01 的关系及复现限制。

最终 **13 tests / 0 failure / 0 error / 0 skipped，7 类**：上述 7 条正式问题断言，另 2 条 QQ 条件逻辑断言尚缺历史可达性、4 条为 J08/H09 既有问题及事务分支补证。阶段一、二是最终集合的子集，不叠加计数。见 [phase-final](probes/2026-10-28-2301/phase-final/)。夹具初次上下文错误单独保留在 fixture-debug，不记为业务失败。通过断言表示错误存在，不代表已经修复。全部 HTTP 入口本地拦截，重定向请求仅构造而未发送；无真实账号、真实云端写入、设备、可见浏览器或自动播放。探针已移出隔离快照的常规测试目录。

本轮 [coverage.json](probes/2026-10-28-2301/coverage.json) 记录 **85 个 Kotlin 文件：14 个执行代码通读、71 个局部/变更行检查**。部分 diff 输出被截断，未声称全部变更行均已复核；NavGraph 的相关整段行覆盖声明已移除，仅保留确实看到的函数/调用片段。没有把缺少后续补读的文件算为完成。

新基线共有 **386 个 Kotlin 文件**。与上轮相比，301 个文件 SHA 未变，81 个被修改，4 个新增；仅 SHA 相同的旧记录得以继承。合并后 **378 个有当前基线记录，311 个执行代码通读、67 个局部检查、8 个缺当前基线阅读记录**。详见 [cumulative-coverage.json](probes/2026-10-28-2301/cumulative-coverage.json) 和 [without-current-baseline-record.json](probes/2026-10-28-2301/without-current-baseline-record.json)。通读跳过注释/import/空行，不能等同于动态验收；更换基线后不能沿用先前 375 个通读的数字。

停止后必要收尾时实测五小时剩余 **67%**、周剩余 **95%**（23:32:11），详见 [quota.json](probes/2026-10-28-2301/quota.json)。这次按用户指令提前结束，没有等到 10%，没有使用额度重置。本轮只本地提交审查资料，原工作树中的他人业务改动保留。

## 留待用户决定是否恢复的断点

1. 先核对共享工作树正在进行的下一版交付，再对 N01–N06 和旧问题核对修复，避免重复报告。
2. 优先处理建议为 N04 禁用手势空转、N05 异步开播意图先后、N02 迁移后收藏删除、N06 重复队列项归属；本任务未修业务代码。
3. J08 搜索失败页与业务码失败仍有当前版本探针；H09 转存/删除失败分支需要完整事务，不能只回传 Boolean。E01 界面反馈、E06 封面代次、E07 旧说明保持原报告状态。
4. 剩余 8 个变更文件、其它局部文件、7 种语言全文及 Kotlin 外边界未完成。SessionMonitor 的短开机时间退避、跨请求状态收尾仍只是后续检查方向，未增加正式缺陷数。
5. QQ 迁移重复仅有条件逻辑证据，缺少真实历史生产者；N01 缺 UI 生命周期时序证据，N03 没有实际服务端重定向事实，N04 没有设备 ANR 复现，N06 没有真实拖拽验收。恢复审查前保持这些限制。
