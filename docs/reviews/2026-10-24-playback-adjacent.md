# 队列、离线缓存与试听状态审查

基线：v3.7.1-gpl（02c7df2，master 文档提交9d3e07c）。本轮重点沿应用队列→ExoPlayer待播槽、离线URL→音频span、缓存回放→试听权益/上报链路审阅。以下为源码可构造状态的发现，未声称已在真机或真实云端上报复现。

## P1：删除/移动待播歌曲没有同步撤销旧预载槽

位置：MainActivity.kt removeFromQueue（1353–1368）、moveInQueue（1372–1385），对照1642附近needsPreload的单次触发说明、PlaybackService.kt的preload_next分支（870–909）。

触发：开启无缝播放，队列[A,B,C]正在播A，B已经加入ExoPlayer待播槽（或取链仍在飞）；此时从应用队列删除B。removeFromQueue只修改UI队列和索引、保存状态，未取消在飞预载、移除服务里的B或预载C；needsPreload已保持true，本曲不会再次触发。A结束时ExoPlayer仍能自动播B。移动B或批量插播有同类接缝。

建议：队列编辑后按完整TrackKey立即使旧待播意图失效，清除服务待播项，并按最新队列重建下一首。仅等待新URL成功后替换旧槽不够，离线或请求失败时旧项仍会播放。需要覆盖已入槽、在飞、删除后无下一首以及跨源同号情形。

## P1：离线URL先命中的失效档位会挡住另一份可播放缓存

位置：OfflineUrlStore.kt OfflineUrlIndex.recall（71–76），PlayerViewModel.kt recallOfflineCache（1722–1743）。

触发：索引同时记录某曲lossless、standard。用户偏好lossless，但它的音频span已被LRU淘汰（或该key已进failedUrlKeys）；standard的起始span仍在。recall先返回lossless，调用方检查失败直接return null，不继续尝试standard。URL索引不随SimpleCache的自动淘汰同步移除，状态可达。

结果：离线界面可见其他档位缓存，但这首歌仍无法使用离线兜底。建议把可用性过滤纳入候选遍历，并覆盖首选档位无span、首选已失败、另一档位可从头播放的组合。

## P2：冷启动离线回放无法还原缓存文件的试听属性

位置：OfflineUrlStore.kt仅存key→URL；PlaybackService.kt rememberFromUrl（705、1212）；PlayerViewModel.kt recallOfflineCache构造SongUrlResult（1749–1757）、classifyEntitlement（1122–1123）、playSong初始化currentIsTrial=false（1421）及曲终上报门控（700）。

触发：先缓存一份网易试听片段，结束进程，离线启动再播放。持久化索引没有freeTrialInfo/内容属性，离线SongUrlResult的playbackItem为空；classifyEntitlement直接返回null，跳过试听的偏好与试听徽标均无判据。新的ViewModel默认currentIsTrial=false，上报门控也因此存在把片段视为普通歌曲的路径。未向真实账号发送上报验证。

建议：持久化与具体缓存文件绑定的Full/Trial/Unknown元数据并设计旧缓存迁移，不能把缺失字段直接当Full；让播放、跳过偏好、徽标、上报使用同一份内容属性。首次缓存试听、冷重建、旧条目缺失字段、联网后换成完整文件都要回归。

以上三项为审查发现，尚未修改业务实现。本次v3.7.2功能修改是庆祝结束按钮的布局；单测和发布验证另存verification目录。

## 修复状态（v3.7.3-gpl / versionCode 89）

- **旧预载插队**：`player/PreloadGeneration` 给“下一首计划”一个单调代次；MainActivity 的 12 处队列变更都在改队列前调用 `invalidatePreload()`，`PlayerViewModel` 取消在飞任务、清空槽位，`PlaybackService.discardPreload()` 清掉已入队的待播项与封面预取。Service 在处理 `preload_next` 时核对 Intent 携带的代次，过期直接丢弃。队列/播放模式变化后会按最新计划重新预载一次（`preloadNextSong` 自带去重与 gapless 开关门控）。
- **离线档位回退**：`OfflineUrlIndex.recallRecord` 按“请求档位优先 → 同曲其它档位”排序，并对每个候选做可用性判定（本会话失败过的不再喂、`coversStart` 覆盖 position 0），不再因首选档位失效就放弃整首歌。
- **冷启动试听属性**：`cache/OfflineContent` 把 full / trial（含起止秒）随文件记录持久化；`SongUrlResult.cachedContent` 一路带到预载缓存与播放判定，冷启动命中离线文件时能还原 `Trial`。
  - 兼容性取舍：**FULL 沿用历史 key** `song:<id>:<level>`，只有 trial 才加 `~trial_<start>_<end>` 后缀——否则升级后既有的全部下载都会 miss。旧清单（纯字符串）迁移为 `UNKNOWN`：仍可播放，但不会被当成“完整收听的证据”（`currentContentKnown = false`），避免把 30 秒片段计入播放上报。
