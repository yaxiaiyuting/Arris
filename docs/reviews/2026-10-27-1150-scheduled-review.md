# 11:50 定时续审：状态收尾与后续调用链

日期：2026-10-27，Asia/Shanghai。计划触发时间 11:50；运行证据按 XML/日志实际时间记录，不能视为准点执行。启动 HEAD `d755ad3`，业务基线 `15237f8c06ba160632c9096101386f821b85a723`，v3.7.8-gpl / versionCode 94。此后尚无业务修复提交，06:43 轮 H01–H24/F01–F05、前轮 R1–R4 不重复计数。

用户仅授权审查、隔离复现与留档。本轮不修改业务实现，不升级、打包、推送、发布，不测试设备、不打开可见浏览器或播放。所有探针 HTTP 都由本地应用拦截器返回夹具，不调用 proceed，不操作真实账号或云端。

**阶段一留档，审查继续。** 以下通过的探针断言错误确实存在，不表示问题修复。

阶段二已追加音频特征、身份回填、统计和搜索的六条缺陷用例，累计 **11 tests / 0 failures / 0 errors**。J07/J08 属于 v3.7.8 对 S7/S9 的修复残余，不能称为新引入的问题；S9 的来源计数重复部分已经修复。

阶段三累计 **15 tests / 0 failures / 0 errors**，追加 QQ 旧接口形状映射、失败提示占位符、两条手势方向组合用例。另补三项 UI 源码问题及实际依赖字节码证据；尚未执行设备视觉或触摸验证。

阶段四累计 **18 个 JVM 用例 / 0 failure / 0 error**，确认三泳道曲线点数截断、旧设置覆盖，并补上 J08 的 HTTP200/业务失败分支。另审独立 Go 服务的 5 个源文件，确认 3 项有隔离路由证据的问题，**当前 APK 不调用该服务，放在附录，不计入安卓问题数**。

## 阶段一确认

| 编号 | 优先级 | 问题 | 证据范围 |
|---|---|---|---|
| J01 | P2 | 统计清空与刷新没有统一写入顺序，旧任务可删新数据或用旧快照覆盖新值 | 两条真实持久化入口的隔离并发探针 |
| J02 | P2 | 仅 MUSIC_U 的扫码候选经服务端验证后，保存被拒绝，而 UI 仍按登录成功收尾 | 真实验证/保存入口；UI 收尾为源码确认；生产响应出现频率待核实 |
| J03 | P2 | 首次登录检查断网失败也消费 30 分钟节流窗口，恢复网络重进仍不检查 | 真实 SessionMonitor 入口，本地失败→成功对照 |
| J04 | P2 | 删除在播队列项后索引已指下一首，实际音频未切；后续导航再前进一步，跳过该首 | 真实队列纯逻辑组合 + 实际 UI/结束回调调用链 |
| J05 | P2 | 多声道瞬态包络时钟乘错声道数，双声道按四倍真实时间衰减和解冷却 | 两条真实 PCM 提取器探针 |
| J06 | P2 | 恢复队列时 QQ mid/mediaId 回填因 TrackKey 身份相等被直接跳过 | 真实 adoptTrackIdentity 方法的隔离实例探针 |
| J07 | P2 | 一次自动过渡固定 playingTrack 后，手动切歌不再更新，后续统计归到旧歌 | 真实统计采样器状态探针 + 过渡调用链，S7 残余 |
| J08 | P2 | 新分页结构仍把失败网易页确认并清掉待补标记，QQ 成功时继续跳页 | 真实 SearchViewModel + 本地 HTTP，S9 残余 |
| J09 | P2 | 清空搜索框未取消请求，晚到专辑响应重新填回空白页面 | 真实 clearQuery/searchByType + 本地 HTTP |
| J10 | P2 | QQ 旧字段 songmid/songid/songname 只兼容 mid，数字身份散列化且标题丢失；与新接口同曲重复收藏 | 真实旧响应解析和收藏合并；远端当前返回形状频率未测 |
| J11 | P3 | 换源失败提示在全部八种语言都暴露字面 `%s` | 真实提示函数，八语言对照 |
| J12 | P2 | 播放器与控制栏把进度速度当屏幕 Y 速度，快甩方向判反 | 两条真实吸附决策与入口公式组合，未注入触摸事件 |
| J13 | P2 | 三泳道实际 85 个点，PCHIP 只计算前 64 个，截断边界切线可使曲线越界 | 真实生产点数与曲线数学探针 |
| J14 | P2 | 已迁移的新版动效档位，在后续升级时被保留的旧 visualizer 档位覆盖 | 真实 prefs 迁移入口 |
| K01 | P1 | MainScreen 的异步私人 FM、立即播放和续播返回时缺少播放意图提交闸门，旧结果可夺回播放权 | 静态调用链；未执行 Compose/音频复现 |
| K02 | P2 | 启动时异步恢复旧队列，无条件覆盖等待期间用户新建的队列 | 静态调用链；尚无页面竞态复现 |
| K03 | P2 | 海报在最小字号仍放不下且当前行靠后时，不裁掉前文也不剪裁，歌词可越过页脚 | 静态渲染边界，未运行 Android Canvas |
| K04 | P2 | 播放器触摸协程捕获旧 geo，面板变化后命中边界不更新 | 静态代码 + 实际 Compose 1.7.6 字节码，未执行设备手势 |
| K05 | P3 | 库页或搜索页修改当前歌曲收藏后，播放器红心可保留旧值 | 静态状态依赖链，未运行 Compose |
| K06 | P2 | 关闭多频段或特征不可用时，单泳道路径未使用滚动相位，仍是逐格变形 | 静态绘制分支，未执行视觉复测 |
| K07 | P2 | 背景图受前台内存压力隐藏后，恢复监听也被移除；在途导入也缺少清除代次 | 静态生命周期/提交链，两条相关观察，无 Canvas/设备复现 |
| K08 | P3 | 从系统后台权限设置返回后，白名单状态可能保持跳转前值 | 静态 remember/生命周期链 |

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

同一模式还见 `:2177–2194` 的空缓存全部收藏加载后播放、`:2884–2889` 的无播放推荐和 `:3072–3108` 搜索专辑/艺人批量立即播放。并入 K01，不拆成多项。已有缓存的 playAllLiked 在 `:2215` 有队列对象身份检查，不能把它说成完全无保护。

### K02：延迟旧队列恢复会覆盖用户编辑

`MainActivity.kt:1247–1275` 的 LaunchedEffect(Unit) 等待 PlaybackStateManager.getQueue，返回后直接赋 playbackQueue/currentQueueIndex，缺少“用户是否已改过队列”的修订检查。getQueue 的 JSON 解析可挂起到 Default，期间 UI 可以接收新的播放/队列动作。需要慢解析且用户动作抢先的时序；本轮仅确认调用链，没有运行页面竞态。

建议恢复只在初始化占位状态且队列修订未变时提交，与用户立即播放动作共享修订；不能无条件用旧磁盘恢复压过已建立的新队列。

## 阶段二确认

### J05：PCM 样本数到时长的换算相反

`player/AudioFeatureExtractor.kt:385/428` 的 subFrames 统计交错样本总数，`:473` 却用 `n * channelCount / sampleRate` 换算时间，正确量纲应为 `n / channelCount / sampleRate`。因此双声道是四倍，更多声道按声道数平方放大。`:508/517/531` 用此时间推进基线、峰值记忆和冷却。

真实提取器处理相同声音、相同 100ms，单声道/复制双声道 RMS 与公开 frameMs 相同，fullBaseline 却分别为 0.05875155 / 0.19673467，后者正好是 400ms 的衰减结果。第二条用例给出 10ms 脉冲、30ms 静音、10ms 脉冲：单声道未重复触发，双声道在两次起点相距 40ms 时已经重复触发，违背 90ms 冷却。

生产入口 `player/TransparentWaveformSink.kt:155–167/192–215` 将实际声道数传入并把瞬态发给动效。建议统一按帧数换算真实时间，补单/双/多声道相同信号的不变性和冷却边界回归。本轮没有视觉验收，不能把此项直接等同于用户见过的波形停顿。

### J06：逻辑身份相同不等于载荷已完整

`ui/viewmodel/PlayerViewModel.kt:953–967` 合并得到带新 mid 的 TrackKey 后，用 `merged == currentTrack` 判定无需回填；`source/TrackKey.kt:75–78` 刻意只比较 source/id，因此同身份的所有 mid/mediaId 补全都会被跳过。`MainActivity.kt:1269–1274` 确有恢复队列调用该入口。

`queueIdentityBackfillRejectsNewMidBecauseTrackEqualityIgnoresPayload` 给构造器未运行的隔离 VM 仅填该方法读取的两个字段，再调用真实 adoptTrackIdentity：旧 QQ TrackKey 的 mid/mediaId 仍为 null，队列给出的两个值都未采用。该探针未创建 Activity、服务或请求，不证明某台设备必然缺失旧载荷。

建议保留 TrackKey 的逻辑身份相等语义，回填比较各载荷字段并明确按同源合并；不要为了这一个入口直接改变全局 equals，避免破坏队列去重。缺 mid 的旧状态恢复后重试歌词/取流需要专门回归。此项区别于 H11 的多处持久记录查找优先级和 H13 的详情路由主动丢载荷。

### J07：实际身份只在 changed 分支更新

`PlayerViewModel.kt:1518–1520` 在手动取链前更新 currentTrack；`:823–837` 的媒体过渡回调仅在 nextTrack != currentTrack 时更新 playingTrack。手动切歌已经提前把 currentTrack 改为 B，实际 B 过渡时 changed=false，playingTrack 仍是上次自动过渡的 A。`stats/StatsRecorder.kt:203` 永久优先取这个非空旧值。

`staleConfirmedTrackReceivesTheNewManualSongsListeningTime` 建立上述状态（playingTrack=A、currentTrack/currentSongId/标题=B），调用真实 onSample：B 的 500ms 和标题写到 A 下，B 没有统计条目。状态来自源码可达的过渡序列，未实际运行媒体播放；构造器未运行的 VM 只提供采样器所需字段。

这是 S7 修复残余，当前新增 playingTrack 并未完成所有实际过渡的发布。建议每次真实媒体过渡都原子更新在播身份和元数据，与“是否需要重取歌词”分开判定；停止时也需清理，不能用非空旧身份无限遮蔽新项。

### J08：pendingPage 在失败确认中被删掉

`SearchViewModel.kt:496–501` 网易失败时登记 pendingPage，但 `:366–371` 的 accumulateAndPublish 随后无条件 put 网易页，即便输入为空且失败；put 又确认该页并 remove pending。`:339–340` 下次用最大已确认页+1，所以仍跳过失败页。

`failedNeteasePageIsStillConfirmedAndSkippedDespiteThePerSourceCursor` 经真实私有搜索入口、真实 Retrofit 和本地 HTTP500，QQ 使用本地 Provider 夹具。网易 offsets 仍为 0、30（失败）、60，未重试 30；结果缺第 2 页。对照同时确认网易唯一结果 60、来源数 60：旧的双计数确已修复，本轮只保留跳页残余。

建议发布以每个来源明确的 Pending/Success/Failure 为输入；只有已成功到达的页可确认和替换，失败和未到达不能占用页号或清除待补页。回归必须覆盖同一页的先到发布与最终发布，而不能只测独立分页游标类。

阶段四补充 `anHttpSuccessWithABusinessErrorBecomesASuccessfulEmptySearch`：真实 Retrofit 返回 HTTP200、JSON code500 且没有 result，QQ 本地替身合法空表。搜索返回 true、空结果、error=null、网易徽标 DONE、hasMore=false。`network/SearchResponse.kt:8–10` 丢弃业务 code，`:494/502` 将 result 缺失变成无错误空页。该分支甚至不会登记 pending，应连同“业务失败与真实空表”区别一起修；不另加一个问题编号。

### J09：清空输入未撤销结果提交权

`SearchViewModel.kt:924–926` clearQuery 只清输入和列表，不 cancel searchJob、不推进请求修订。`:836–847` 的专辑分支返回后直接发布；艺人分支 `:873–883` 与歌词分支 `:788–809` 也缺少关键字/类型/修订提交检查（后两条仅静态观察）。

`clearingTheQueryDoesNotStopALateAlbumResultFromRepopulatingThePage` 挂起本地专辑 HTTP，将实际在途 Job 放入 searchJob，调用真实 clearQuery，确认空输入/空列表；放行响应后，输入仍空而列表出现迟到专辑。使用真实私有 suspend 搜索入口构造确定时序，未执行 Compose。

建议清空、换查询、换类型统一取消并推进代次，所有结果、错误、loading 和 hasMore 都按代次提交。不要只给歌曲聚合分支加字符串相等检查；同关键字重查也可能属于不同请求。

## 阶段三确认与边界

### J10：旧 QQ 字段兼容不对称

`qq/QqSongMapper.kt:132–136` 接受旧 songmid，但 rawId 只读 id；`:193–195` 只读 name/title，不读 songname。`:93–97` 的公开旧搜索解析和 `QqApi.kt:118/160` 的正常/歌词搜索回退确实使用该映射。

`legacySongidAndSongnameAreIgnoredAndTheSameMidBecomesTwoSavedSongs` 输入带 songid=97773/songmid/songname 的旧形状，解析器将 ID 变成 mid 散列且标题为空；同一 mid 的新形状 id=97773 得到另一 TrackKey。经真实 SavedSongSync.addManualAll 后成为两项收藏。全部为本地 JSON/纯逻辑，没有声称当下服务端每次都回旧形状。

建议旧字段成组归一化（songid/songmid/songname 和对应专辑信息），避免“支持 mid 却丢掉同一形状其它字段”。已有散列身份需要可控迁移或别名合并，覆盖收藏、队列、缓存，不应只给之后的新搜索换 ID。

### J11：提示后缀的源名称未格式化

`player/ResolveFailureText.kt:63` 直接拼接 switchSource(otherLabel)；八语言的 `ui/i18n/*:647–650` 都在 lambda 中保留字面 `%s`。`Strings.kt:1482` 的 lambda 只接受另一源名称，没有当前源参数。真实 resolveFailureText 的八语言用例全部输出 `%s`。

建议后缀写成不含当前源占位的通用文案，或把当前/目标源都作为明确参数；回归检查最终完整提示，不只检查词条存在。本项为低优先级展示错误，不影响取链。

### J12：进度方向和屏幕 Y 方向相反

`ui/player/PlayerCard.kt:458/484` 向上拖动令 progress 增加，但 `:492–495` 直接将这个差值乘距离/时间作为 velocityY。`:981/992` 控制栏也相同。`PlayerDragSnap.kt:72/75/109–110` 却约定向上 velocityY 为负。

两条纯组合探针使用源码入口公式调用真实决策：主卡上甩 40px/10ms（4% 距离）传入 +4000，回到收起；正确屏幕 Y 符号会展开。控制栏上拖 120px/50ms（30% 距离）传入 +2400，速度分支甚至覆盖已越过的 12% 距离阈值，把它还原展开；正确符号会收起。没有向 Compose 注入触摸事件。

同一控制栏 `:970–983` 的 startMs/lastMs 在 onDragStart 未重置，后续手势耗时还会带上上次与间隔；此为静态伴随观察，未加算一项/一次动态复现。建议用屏幕坐标的 VelocityTracker，逐手势重置时间与速度，保留进度距离判定自身的符号。

### K03：海报缩到最小仍超高时没有完整收尾

`share/LyricPosterRenderer.kt:247–265` 到最小字号仍超 available 时，只能从尾部移除“当前行后”的行；若当前行靠近末尾，end-1>keepCurrent 很快为 false，即便 total 仍超高也结束。`:273–308` 绘制时没有 lyrics 区域 clip、也不裁前文；页脚随后仍按固定位置绘制。`LyricShareSelection.selectionForPoster` 支持最多 20 行，整首选段且当前句靠后时可走到这个边界。

结论限定为最小字号测量后仍超高的输入（长原文/翻译/音译组合），本轮没有执行 Android 字体测量或导出实际图片，不能给出具体机型的溢出像素。建议优先保留当前行，按实际高度同时裁前后上下文，仍超高则对歌词区域剪裁/省略，不能继续画进页脚。

### K04：长寿命触摸协程保留旧几何

`PlayerCard.kt:239–252` 每次重组计算新的 geo；局部 isOverPanel/isOverCardVisibleArea 在 `:349–381` 读取该值，`:403` 的 pointerInput(Unit) 和 `:419` 的 pointerInput(hasSong,bigScreenActive) 捕获它，但 key 不含 panelBoundary/屏幕尺寸，未用 rememberUpdatedState。宽屏打开歌词时 wideSplit 从 0→1，`PlayerLayout.kt:248–251/276–277` 的边界由全宽变成约 44%，旧闭包仍可能按全宽拦截面板手势。

本机实际依赖解析为 Compose UI **1.7.6**；其 SuspendingPointerInputModifierNodeImpl.update 字节码仅 key 变化才 reset 运行协程，换 handler 引用只更新字段。实际编译 PlayerCard$3/$4 协程各捕获 final $geo。证据归档于阶段三的 compose-version/pointer-node-update/player-pointer-captures 文件及依赖 AAR SHA-256。没有运行设备触摸，不能把它直接称为之前某次错位的唯一原因。

建议稳定触摸协程通过最新状态读取命中几何；避免把每帧动画几何都作为 key 造成拖动被不断取消。静态复核还应覆盖总拖动距离、密度和回调所需其它变化值。

### K05：播放器红心依赖本组件局部 tick

`PlayerCard.kt:212–214` 只 remember(song.id,ui.libraryTick) 读取是否收藏；`PlayerCardExpanded.kt:189–204` 只在播放器内收藏按钮动作后增加 tick。`LibraryScreen.kt:452–455` 和 `SearchScreen.kt:434–442` 可修改同一歌曲收藏，但不通知该 PlayerCardUiState。播放器缩为小条时仍挂载，所以在库页删除当前歌后，再展开可继续显示旧红心。

源码状态链确认，未执行 Compose 画面。建议收藏仓库提供按身份的可观察修订/状态，播放器订阅它；不要依赖页面私有 tick 或靠切歌触发刷新。本项为展示状态问题，不能称为收藏数据已经丢失。

## 阶段四确认

### J13：生产曲线点数已经超过旧上限

`ui/player/AudioVisualizer.kt:690/706/829/899–905` 按 28 格×3 泳道加 1 个延长点建立并绘制 85 点；`ui/player/waveform/WaveformCurve.kt:60/71` 仍把切线数截成 MAX_POINTS=64。之后绘图 `AudioVisualizer.kt:1192–1204` 却使用完整点数，不仅最后 21 个斜率未计算，第 63 个还被错误视作整条曲线终点，缺少相对于第 64 点的单调保护。

`productionThreeLanePointCountExceedsTheTangentLimitAndOvershootsTheSeam` 使用真实生产常量和真实计算器，给第 61–64 点合法归一化高度 0.1/0.4/1.0/0.1，边界切线为正而下一段下降；采样得到峰值 **1.0243**，真实 staysWithinEndpoints 返回 false。没有执行 Canvas，不能据此声称已复现用户的闪屏或停顿。

建议以实际绘制点数计算、初始化全部切线，或统一容量上限至少覆盖 85；回归包含第 63/64 点转折及最后一泳道，而非只测 28 点样本。

### J14：再次迁移时把旧选择搬回来

`ui/player/motion/MotionPrefs.kt:283–299/326–334` 在任何旧水位（包括 4）升级到 5 时，优先读取仍保留的 visualizer_tier，再写入已经存在的 motion_tier。旧键按设计不会删除，因此在 v2.9/v3.0 迁移后又手动改过新档位的用户可被覆盖。

`upgradingAnAlreadyMigratedPreferenceReappliesAnObsoleteShowcaseChoice` 给出合法 version4 盘：旧 visualizer=SHOWCASE，新 motion=SIMPLE（后来显式选择）；真实 migrate 后新档位变回 SHOWCASE，水位推进到 5。纯 prefs 入口，无设备动效或播放。

建议已存在且有效的 motion_tier 优先；只在确需从旧命名空间首次迁移且新键缺失时搬运。补“旧版设置→首次迁移→用户改新键→后续水位升级”的连续路径，不只测缺新键或缺旧键的独立盘。这是历史升级缺陷，不声称由 v3.7.8 新引入。

### K06：单泳道没有接入连续滚动

`AudioVisualizer.kt:788` 只有 waveBandOn && featuresAvailable && paletteReady 才进三泳道；这一支 `:827/851` 使用 scrollPhase01/BandScroll。回退单泳道 `:943–990` 只复制各格显示高度，xShift 固定为 0（flow 分支的平移又被内层相反抵消，只移动刷色）；没有使用滚动相位。`WaveformRing.kt:914–937` 也只平移 aggregate targets，没有将 aggregate bars/速度/峰值随历史平移，形成逐格形状逼近。

`MotionEffects.kt:424–431` 的用户多频段开关可明确走此分支；特征暂不可用也会降级进入。结论是源码分支仍缺少所要求的均匀横向滚动，未运行视觉复测，不把它认作某段用户录屏的已确认唯一原因。建议单/三泳道共享时间轴、历史平移与几何插值，不以颜色流动替代波形移动。

### K07：恢复监听被自己的降级分支移除

`ui/CustomBackgroundLayer.kt:49–55` 在 suppressed=true 时先 return；恢复监听却在 `:63–69` 才注册。`ui/theme/BackgroundImageManager.kt:142–148` 的内存压力只把 suppressed 设 true，唯一生产 onForeground 调用就在该监听。若内存压力发生于前台并触发重组，监听随 return 被移除，下次回前台无入口解除 suppressed，背景图可一直隐藏到清除/进程重建。`NcrustApplication.kt:75–80/88–92` 确有内存回调入口。后台期间是否先重组受生命周期调度影响，所以没有声称每次切后台都必现。

同一管理器 `:72–128` 的异步导入和 `:132–138` clear 没有共享修订/取消闸门。若用户清除发生在旧导入创建目录/提交之前，旧导入仍可晚到写文件并 setEnabled(true)，恢复刚清除的旧图；此为第二条静态提交观察，不额外加一个编号。

建议前台恢复监听放在可视资源条件之外或 Application 生命周期中；在途导入捕获修订，清除/新导入推进修订，只有最新任务可提交。未执行 Android 图片导入或 Compose 生命周期复现。

### K08：后台权限结果没有恢复时刷新

`SettingsGroupScreen.kt:309/869–901` 的 allowed 只 remember(backgroundBatteryRefresh)；计数在启动系统页面后立即增加，通常仍读取跳转前的权限值。该页面没有 ON_RESUME 观察者/结果回调，用户在系统设置改好权限再返回，同一页面可继续显示旧“未允许”，直到销毁重进或再次点行。

建议在恢复前台或设置结果返回时重新读取系统状态，必要时刷新跳转目标。仅源码确认；不测试系统权限或设备界面。

## 独立 Go 服务附录（不属于当前 APK 请求链）

`RetrofitClient.kt:25/205` 当前直连 music.163.com；历史设计也明确不使用自建 API。因此下面 3 项仅针对单独部署 ncrust-api 的情形，不计作安卓运行中的账号泄露或播放故障。5 个 Go 文件均已通读，覆盖单独记在 non_kotlin_boundaries。

| 编号 | 优先级（若部署） | 问题与证据 | 建议 |
|---|---|---|---|
| W01 | P1 | `ncrust-api/handlers.go:280–296/418–423` 只截 Cookie 前 40 字节分账号缓存。真实 routes 先 A 后 B，两份 Cookie 的公共设备字段前缀相同、MUSIC_U 后缀不同，B 读到 A 的 account JSON，上游仅调用一次 | 账号资料不共享缓存，或按完整认证快照不可逆指纹隔离；不可用公共 Cookie 前缀当账号身份 |
| W02 | P2 | `handlers.go:76–84` 除特判 301 外不转发上游状态；本地 RoundTripper 的 HTTP503 经真实 search 路由变成 HTTP200 | 显式区分 HTTP 状态与 JSON 业务 code，保留失败状态/安全响应形状 |
| W03 | P2 | `handlers.go:176–177` 只看 HTTP200 就缓存歌词 24h；本地 HTTP200/code500 先到后，健康夹具再不被请求，失败体一直作为缓存命中 | 只缓存结构与业务码均成功的内容；失败不占完整内容 TTL；其它同形缓存出口一起核对 |

证据为 phase-4/noon_audit_test.go 和 optional-backend-go-test.jsonl：3 tests pass，断言错误存在。`httptest.NewRecorder` 直接调用 routes，所有外发由内存 RoundTripper 返回，**没有启动 TCP 服务、没有真实网络或账号、没有执行 fmTrash 等云端写入**。运行使用 GOTOOLCHAIN=local/GOPROXY=off，无依赖下载。

## 证据与执行说明

- 阶段一源码探针、XML 和 Gradle 日志：[phase-1/](probes/2026-10-27-1150/phase-1/)。5 tests / 0 failures / 0 errors，均断言错误存在。
- 阶段二完整累计证据：[phase-2/](probes/2026-10-27-1150/phase-2/)。11 tests / 0 failures / 0 errors。搜索夹具的网易 HTTP 由应用拦截器直接返回，QQ/B站 Provider 均被本地替身覆盖并恢复；未产生真实账号/云端请求。
- 阶段三完整累计证据：[phase-3/](probes/2026-10-27-1150/phase-3/)。15 tests / 0 failures / 0 errors；含真实依赖版本和触摸节点字节码，属于静态证据而非额外设备复现。
- 阶段四累计证据：[phase-4/](probes/2026-10-27-1150/phase-4/)。18 JVM tests pass，另有独立未接入 Go 服务的 3 条缺陷用例，不混算安卓用例数。
- 统计探针通过修改测试中对象实例的 CoroutineContext/Gson factories 控制时序，恢复全部原值，不改磁盘业务源码。初次尝试静态 final 字段赋值被 JDK21 拒绝、字段名猜测失败，均属探针环境错误，修正后才采纳上述证据，不算应用问题。
- 本轮逐文件/行段与源码 SHA-256：[coverage.json](probes/2026-10-27-1150/coverage.json)。阶段未结束，不提供全项目完成比例。
- 开始实测额度：五小时剩余 99%、周剩余 55%；阶段一读取：88% / 53%。未使用重置。
- 阶段二读取额度：78% / 52%。探针编译时直接引用 sun.misc.Unsafe 不在 Android 编译类路径，改用 JDK 反射后通过；属于夹具环境修正，不计应用缺陷。
- 后续阶段读取额度：57% / 48% → 46% / 47% → **35% / 45%**，均为五小时/周窗口实际剩余比例，继续审查，未消耗重置。

## 当前断点

继续 MainActivity 剩余生命周期和队列入口，再补 PlayerViewModel、音频特征提取、歌词 UI 与前轮未记录范围。页面协程取消本身不能直接算 bug：若 loading 状态随 Composable 一起销毁，需证明仍有被保留状态或共享写入受影响才报告。已有 H01/H02/H03 的专辑、歌单、上报归属缺口本轮核对但不重复计数。
