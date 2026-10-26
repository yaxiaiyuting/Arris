# 歌单同步、账号会话、统计、离线索引与搜索分页审查

基线：v3.7.7-gpl / versionCode 93，应用源码 `4e9d763`。本报告承接已修复的五类问题，继续检查 local、QQ 缓存维护、stats 和 B 站登录收尾。以下九类通过真实业务入口/状态快照和本地夹具复现，共 10 个用例；不使用真实账号，不做真机测试。它们是新发现，尚未修复，不能把 v3.7.7 描述成已解决这些问题。

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

## 证据与断点

`probes/2026-10-26-followup/` 保存 `FollowupBoundaryProbe.kt`、XML、构建日志和摘要。7 个探针全部复现；通过表示已确认缺陷，尚不表示修复。临时测试源已移出常规测试目录，未进入 v3.7.7 的 APK，正式版本仍对应已验收的 3100 项测试。

`probes/2026-10-26-accounting/` 的 2 个用例和 `probes/2026-10-26-search/` 的 1 个用例也全部通过缺陷复现断言。搜索探针需要归档的 MainDispatcherFactory service，并设置 JAVA_TOOL_OPTIONS=-Dkotlinx.coroutines.fast.service.loader=false；首次未关闭 Android fast loader 的运行仅为探针环境初始化失败，修正环境后的成功 XML 与日志保存在 search 目录。全部 10 个用例断言的是已知错误行为，不应计为修复回归通过。

本轮在五小时额度剩余约 11% 时收尾；后续断点为更新下载/安装链路、页面生命周期和异步读取的完整性，以及以上九类问题的修复。正式应用源码、v3.7.7 tag 和 APK 不因本轮审查发生变化。源码 review 不执行真实账号写操作。
