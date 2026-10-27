# 11:50 定时续审：状态收尾与后续调用链

日期：2026-10-27，Asia/Shanghai。计划触发时间 11:50；运行证据按 XML/日志实际时间记录，不能视为准点执行。启动 HEAD `d755ad3`，业务基线 `15237f8c06ba160632c9096101386f821b85a723`，v3.7.8-gpl / versionCode 94。此后尚无业务修复提交，06:43 轮 H01–H24/F01–F05、前轮 R1–R4 不重复计数。

用户仅授权审查、隔离复现与留档。本轮不修改业务实现，不升级、打包、推送、发布，不测试设备、不打开可见浏览器或播放。所有探针 HTTP 都由本地应用拦截器返回夹具，不调用 proceed，不操作真实账号或云端。

**阶段一留档，审查继续。** 以下通过的探针断言错误确实存在，不表示问题修复。

## 阶段一确认

| 编号 | 优先级 | 问题 | 证据范围 |
|---|---|---|---|
| J01 | P2 | 统计清空与刷新没有统一写入顺序，旧任务可删新数据或用旧快照覆盖新值 | 两条真实持久化入口的隔离并发探针 |
| J02 | P2 | 仅 MUSIC_U 的扫码候选经服务端验证后，保存被拒绝，而 UI 仍按登录成功收尾 | 真实验证/保存入口；UI 收尾为源码确认；生产响应出现频率待核实 |
| J03 | P2 | 首次登录检查断网失败也消费 30 分钟节流窗口，恢复网络重进仍不检查 | 真实 SessionMonitor 入口，本地失败→成功对照 |
| J04 | P2 | 删除在播队列项后索引已指下一首，实际音频未切；后续导航再前进一步，跳过该首 | 真实队列纯逻辑组合 + 实际 UI/结束回调调用链 |
| K01 | P1 | MainScreen 的异步私人 FM、立即播放和续播返回时缺少播放意图提交闸门，旧结果可夺回播放权 | 静态调用链；未执行 Compose/音频复现 |
| K02 | P2 | 启动时异步恢复旧队列，无条件覆盖等待期间用户新建的队列 | 静态调用链；尚无页面竞态复现 |

### J01：同一 clear 代次内的持久化反序

`stats/StatsStore.kt:164–184` 在内存清空时推进 clearGeneration，但删除磁盘快照的协程异步执行。`:197–199` 的 flushSoon 可在该删除之前保存清空后的新数据；`:232–238` 又在提交锁之外序列化，仅检查 clearGeneration，没有每次写入的单调序号。

`delayedClearRemovesNewListeningDataThatWasAlreadyFlushed` 挂起真实 clear 的 IO 启动，新增并成功落盘 500ms 后放行删除：内存仍有 500ms，磁盘记录被删，冷读变为零。`olderSerializedSnapshotOverwritesANewerSuccessfulFlushWithoutAnyClear` 在真实 Gson 标准适配器委托序列化处暂停旧 100ms 快照，先成功保存新 500ms，再放行旧提交：内存 500ms，磁盘退回 100ms。后者全程没有 clear。

这是本轮新确认的写入顺序问题，区别于 H19 的无锁 Map 读取和旧 S4 的清空前快照恢复。建议清空、保存都进入同一持久化提交序列，状态锁下取不可变快照，以递增修订拒绝旧提交；清空后的新样本应保留。

### J02：验证成功与本地接受是两种结果

`auth/QrLoginProtocol.kt` 接受只有 MUSIC_U 的 Set-Cookie；`ui/components/QrLoginDialog.kt:120–137` 通过 validateSession 后交出候选。`auth/CookieManager.kt:101–108` 的 saveCookieMerged 对已存在完整 A 会话时的 B 增量拒绝替换；`MainActivity.kt:2554–2558` 忽略返回值，仍 onLoginSucceeded 并关闭对话框。

`aServerValidatedQrDeltaIsDiscardedWhileTheOldCompleteCookieSurvives` 经真实 QR cookie 组装、真实 validateSession（本地模拟 account/get 200）和真实保存，确认 B 验证通过、保存 false、A 凭据仍在。没有测试真实网易当前是否/多常返回这种增量；因此结论是该合法形状边界下的错误衔接，不能声称所有扫码登录都失败。

建议显式区分保存接受、同会话无变化、候选不完整或拒绝。只有确认接受且归属一致才收尾成功；候选缺字段时获取同一候选的完整凭证或保留重试，不应盲目拼入旧账号的 CSRF。

### J03：失败检查被当成成功缓存

`account/SessionMonitor.kt:80–84` 在请求前就写 lastCheckAt/lastRevision；`:115` 网络失败仅保留状态，不退回节流标记。`aFailedInitialSessionCheckPreventsAHealthyResumeRetry` 让初始 account 请求本地 IOException，状态 Unknown；随后网络夹具恢复，真实 onResume 不发任何请求，仍 Unknown；强制 refresh 对照立即变为 Valid。该用例是同一节流窗口内连续恢复，不含真实设备计时。

`ui/screen/HomeScreen.kt:103–104/421` 的私人 FM 入口要求 Valid，因此冷启断网后的入口可持续被错误拦住。建议成功验证才推进长 TTL，失败使用短退避并在恢复网络/重进时重试，保留已知状态的同时不要缓存失败为成功检查。

### J04：删除当前项后的游标与音频脱节

`ui/player/QueueView.kt:447–452` 允许关闭当前行；`MainActivity.kt:1357–1370` 只改队列与索引，没有切换/停止当前音频。`player/PlaybackQueueLogic.kt:149–165` 删除 A 后把索引移到 B。随后 `MainActivity.kt:1575–1599` 和自然结束回调 `:1829–1840` 再向前一个位置。

`deletingThePlayingSongPointsAtItsSuccessorButNextSelectionSkipsThatSuccessor` 调用真实 removeAt/resolveCurrentIndex/nextIndex，输入 [A 在播,B,C] 删除 A 后，下一选择为 C。没有运行 Compose 或播放器；音频继续 A 的部分来自删除入口未发出音频操作的调用链。建议定义删除在播项语义并同步音频，或单独维护在播身份与待播游标，保证 B 不被跳过。单曲循环/最后一项也需要覆盖。

### K01：上层旧请求可以重新变成“最新播放”

`MainActivity.kt:2052–2064` startFm 启动独立协程；`:2118–2128` playPlaylistNow 和 `:2135–2152` playAlbumNow 也在取数后直接 replaceQueueAndPlay。用户先点慢请求 A，再播放 B，A 返回时会调用新的 playSong，绕开 ViewModel 内部“旧请求不得覆盖新播放”的代次保护。`:1472–1544` launchInfinity 同样捕获旧 seed/fromFm/existingKeys，返回时直接追加当前队列；startPlaying=true 时还能起播旧 continuation，未检查模式/种子/队列/操作代次。更换队列和模式的入口没有取消这个 job。

这是源代码可确认的缺少提交检查，未执行页面竞态，不声称已听到音频回跳。建议用户所有明确播放动作、模式/队列替换都推进同一个播放意图代次；启动请求捕获代次、归属和种子，返回提交前核对，取消旧任务并对不合作的网络返回仍复核。自动续播也须与用户意图协调，不能只在 ViewModel 发包后才建立代次。

### K02：延迟旧队列恢复会覆盖用户编辑

`MainActivity.kt:1247–1275` 的 LaunchedEffect(Unit) 等待 PlaybackStateManager.getQueue，返回后直接赋 playbackQueue/currentQueueIndex，缺少“用户是否已改过队列”的修订检查。getQueue 的 JSON 解析可挂起到 Default，期间 UI 可以接收新的播放/队列动作。需要慢解析且用户动作抢先的时序；本轮仅确认调用链，没有运行页面竞态。

建议恢复只在初始化占位状态且队列修订未变时提交，与用户立即播放动作共享修订；不能无条件用旧磁盘恢复压过已建立的新队列。

## 证据与执行说明

- 阶段一源码探针、XML 和 Gradle 日志：[phase-1/](probes/2026-10-27-1150/phase-1/)。5 tests / 0 failures / 0 errors，均断言错误存在。
- 统计探针通过修改测试中对象实例的 CoroutineContext/Gson factories 控制时序，恢复全部原值，不改磁盘业务源码。初次尝试静态 final 字段赋值被 JDK21 拒绝、字段名猜测失败，均属探针环境错误，修正后才采纳上述证据，不算应用问题。
- 本轮逐文件/行段与源码 SHA-256：[coverage.json](probes/2026-10-27-1150/coverage.json)。阶段未结束，不提供全项目完成比例。
- 开始实测额度：五小时剩余 99%、周剩余 55%；阶段一读取：88% / 53%。未使用重置。

## 当前断点

继续 MainActivity 剩余生命周期和队列入口，再补 PlayerViewModel、音频特征提取、歌词 UI 与前轮未记录范围。页面协程取消本身不能直接算 bug：若 loading 状态随 Composable 一起销毁，需证明仍有被保留状态或共享写入受影响才报告。已有 H01/H02/H03 的专辑、歌单、上报归属缺口本轮核对但不重复计数。
