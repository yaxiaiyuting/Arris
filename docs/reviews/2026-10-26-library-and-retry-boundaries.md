# 收藏库、部分同步失败与延迟重试审查

基线：`763987a90d8e80617bc84ff491a8fc91b30af2bb`，v3.7.6-gpl / versionCode 92。承接 10-24 的播放邻接链路与 10-25 的会话续期审查，检查最新取链失败处理，以及收藏库和 QQ 歌单仓库的提交边界。

本轮只审查和保存证据，未修改应用实现、版本号或发布产物。没有连接真实账号，没有安装或操作真机。共新增 5 类发现：R2–R5 用真实业务入口加内存 SharedPreferences、拦截式 HTTP 夹具复现，共 5 个用例；R1 为源码时序确认，未做运行时复现。探针通过表示当前缺陷被复现，不能解读为已经修复。

## R1 · P1：切歌后旧的延迟重试仍能抢回播放权，并混用新歌载荷

位置：`ui/viewmodel/PlayerViewModel.kt:1887`（网络重试）、`:1978`（播放错误降档重试）；对照 `:1430` 的 `playGate.next()` 与 `:1431` 的 `playJob?.cancel()`。

网络失败分支另起 `viewModelScope.launch`，等待 3 秒后调用 `playSong(songId, ...)`。这项任务没有保存为可取消的 Job，也没有捕获/复核原 TrackKey 或播放代次。切歌只取消 `playJob`，不会取消这个兄弟任务。播放错误处理也会在 `decision.delayMs` 后直接重试，缺少同样的复核。

可构造时序：

1. A 取链失败，安排延迟重试。
2. 等待期间用户点播 B，B 获得新的播放代次并正常开始解析或播放。
3. A 的重试醒来，以捕获的 A `songId` 调用 `playSong`；标题、封面、进度、`currentSongSourceKey/sourceId/mediaId` 却在醒来时读取，已经属于 B。
4. `playSong` 为这个旧意图领取更新的代次并取消 B 的 `playJob`，因此 IO 提交处已有的代次闸门也不能拦住它。

同源时存在切回 A 的路径；跨源时还会组合 A 的 id 与 B 的 QQ songmid/media_mid 等载荷。这是此前跨源问题之外的另一处入口，不能据此断言它就是用户每次反馈的根因。

建议：两条重试路径共用带 owner 的任务；调度时捕获完整不可变请求与播放代次，醒来后先复核再领取新代次。用户点播、切档、暂停/停止等动作应按明确策略撤销旧重试。补 A 失败→延迟期间切 B→A 不再提交、同歌重新点播的代次变化、跨源切换与连续错误去重测试，不能只比较裸 songId。

验证等级：源码时序确认；未构造真实 ViewModel/MediaService 运行时复现。

## R2 · P1：收藏写入口把未加载当空表，可覆盖旧本地收藏

位置：`library/LibraryManager.kt:319`、`:338`、`:372`；初始化见 `warmup/AppWarmup.kt:111`，直接调用入口例见 `ui/screen/HomeScreen.kt:309`。

`saveSong` / `saveSongs` / `removeSong` 在写入前没有调用 `ensureSongsLoaded`，而是直接使用 `cachedEntries ?: mutableListOf()`。预热是独立 IO 协程，没有等待完成的约束。若写入先于读盘，就会把磁盘原有列表当成空库；随后 `flushNow` 保存新列表，下一次预热看到非 null 缓存也不会再恢复旧记录。

探针通过真实 `LibraryManager.saveSong` 复现：磁盘已有本地收藏 101，内存尚未初始化，添加 202 后等待实际 flush；磁盘由 `[101]` 变为 `[202]`。旧本地条目已经被覆盖，不能指望云端同步恢复 QQ/B 站等本地收藏。删除入口同样存在把已有歌曲判断成未收藏的问题，未逐个动态测试。

同一初始化区域还有一个 P2 缺陷：`ensureSongsLoaded` / `ensureAlbumsLoaded` 首次加载时分别创建了两份 `toMutableList()`，返回值和缓存真源不是同一实例（`:146–147`、`:161–162`）。探针中首次直接调用 `subscribeAlbum(202)`，修改了返回副本，缓存及落盘仍只有 101。当前专辑详情页会先调用 `getSavedAlbums` 判断按钮状态，通常能避开这个首次写入条件；因此不把它描述成每次点收藏专辑都会失败。

建议：所有写入口必须在同一锁/事务内完成首次加载后再变更；首次加载只产生并返回一份真源实例。不能依赖 UI 恰好先读或预热恰好先完成。新增集成回归要调用 Manager 与真实 Codec，而不只是对已加载列表测试 `SavedSongSync`。

证据：`coldSongWriteReplacesUnreadDiskCollection`、`firstAlbumMutationChangesDetachedCopy`。

## R3 · P1：取链第二次 IOException 逃出分类，丢失网络重试与冷却

位置：`player/SongUrlFetcher.kt:294–307`，对照 `source/SourceRouter.kt:108–110`、`ui/viewmodel/PlayerViewModel.kt:1879–1913`。

第一次 IOException 在 catch 中发起原档位重试，但这次 `attempt(tryLevel)` 没有自己的捕获。若再次抛 IOException，不会进入同级 catch，也不会执行后面的 `transportFailures.add` 和 `startCooldown`，而是直接抛给 SourceRouter，被兜底成 `UNKNOWN`。

探针连续两次抛 IOException，确认共调用 2 次，最终 `failure.kind == UNKNOWN`、`lastAttemptFailedOnTransport == false`、全局冷却值仍为 0。上层会走停下提示未知错误的路径，不会按 NETWORK 执行约定的有界重试；接下来的请求也缺少本应启用的冷却。这与 v3.7.6 的长连接修复是两个不同层面的缺口。

建议：把有限次数的传输重试封装成单一循环/结果，最后一次失败也必须完成 NETWORK 分类和冷却；CancellationException 原样抛出。重试返回业务拒绝时也须保留业务判据，不能仅因第一次发生过 IO 错误就统称网络故障。

证据：`secondTransportFailureEscapesNetworkClassification`。

## R4 · P1：网易云收藏专辑业务失败被当作空列表并落盘

位置：`network/PlaylistApi.kt:992–996`、`library/LibraryManager.kt:518–537`。

`getSubscribedAlbums` 报告业务码后没有校验成功，也没有区分缺失数据与服务端确认空表。`{"code":500}` 等没有 data 的失败响应被返回为 `emptyList()`；LibraryManager 随即整体替换 `cachedAlbums`、设置 `anySuccess = true` 并保存。`refreshFromCloud` 尾部又无条件返回 true。

探针中 account/get 成功，自建/红心歌单读取失败，album/sublist 返回 code 500。原有专辑 101 被清成 `[]` 并实际写回 SharedPreferences，刷新返回 true。这里只确认本地缓存被清，没有发送取消订阅，也没有删除云端收藏。

此外，当前同步只调用默认 `limit=100, offset=0` 的专辑读取，然后整体替换；超过一页的收藏没有完整性保证。这条分页边界为源码观察，未单独做探针，修复时应一并覆盖完整/部分结果的提交语义。

建议：像此前 `LikedIdsResult` 一样明确区分成功空表、读取失败与不完整列表。失败保留旧缓存；分页完整后才能整体替换，部分结果要合并并标记降级。返回实际刷新结果，不能用进入过函数作为成功条件。

证据：`albumBusinessFailureClearsCacheAndReportsSuccess`。

## R5 · P2：QQ 收藏歌单附加读取失败会覆盖完整缓存，并被标为新鲜

位置：`qq/QqPlaylistRepository.kt:110–122`、`:167–190`；TTL 见 `playlist/PlaylistCacheCodec.kt:65`。

自建歌单成功后，`fetchFavoritesSafely` 在“我喜欢”详情或收藏歌单接口失败时只返回空列表。调用方随后将 `owned + emptyList()` 写成整份账号列表，返回 `fresh=true, degradation=null`。原缓存中的他人收藏歌单因此从本地列表消失，后续普通进入又会在默认 10 分钟 TTL 内直接使用这份缓存。

探针预存 `[自建111, 收藏222]`，登录校验和自建列表读取成功，在获取 encrypt_uin 的详情步骤抛 IOException。真实 `loadList(forceRefresh=true)` 将磁盘覆盖为 `[111]`，结果仍为新鲜且没有降级提示。

建议：附加读取返回带状态的结果。附加项失败时合并保留同 owner 的旧收藏，只更新自建部分，明确标记部分失败；不能把失败写成完整空结果并刷新整表 TTL。收藏接口的默认 50 条上限也应随完整性策略检查，避免超过上限的项目静默丢失。

证据：`qqFavoriteFailureOverwritesCompleteCacheAsFreshSuccess`。

## 复现材料与范围

材料位于 `probes/2026-10-26/`：`SourceReviewBoundaryProbe.kt`、`probe-results.xml`、`probe-summary.json`、`probe-build.txt`。不包含用户数据，Cookie 与账号均为虚构夹具，所有请求均被 OkHttp 应用拦截器截获，未建立外网连接。

探针运行：将该 Kotlin 文件临时复制到 `app/src/test/java/com/takahashirinta/ncrust/library/`，运行 `JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:testDebugUnitTest --tests '*SourceReviewBoundaryProbe' --offline --console=plain --max-workers=4`，结束后移除临时副本。探针通过后已从自动测试目录移走，仅保留在审查材料中，因为它们断言的是旧的错误结果；正式修复时应改为正确期望并纳入常规测试。

本轮只执行了这 5 个定向探针（0 失败、0 错误），没有重跑完整 3074 项测试、构建 APK、发布 Release 或真机测试。下一轮优先修 R1/R3 的重试归属与错误分类、R2 的收藏初始化，随后收敛 R4/R5 的完整/部分读取与持久化策略。后续审查可转入 `local/LocalPlaylistStore`、`LocalPlaylistSync`、播放统计和更新器，不重复把本报告当作整库审查已完成。

## 修复状态（v3.7.7-gpl / versionCode 93）

R1–R5 已在构建源码 `4e9d763` 修复。新增 26 项常规回归（PlaybackRetrySchedulerTest 8、SongUrlTransportRetryTest 4、LibraryBoundaryRegressionTest 14），包括把原先 5 条探针的错误结果改为正确期望。全量 3100 项 JVM 测试通过，Lint 0 错误，独立无窗口模拟器 8 项播放检查通过；未做真机或真实账号联网测试。具体产物、方法预算与剩余边界见 `../verification/v3.7.7/RELEASE_VERIFICATION.md`。原探针继续作为历史缺陷证据，不进入常规测试。
