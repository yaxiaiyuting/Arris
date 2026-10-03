# 代码摘录（PlayReporter 跨音源数据泄露探针 · v2.5.5）

仓库：`/home/duanjb666/deepseek/ncrust-gpl/Ncrust`  HEAD：`10e9df9` (`git describe: v2.5.4-gpl-3-g10e9df9`)
生成时间：2026-09-26T18:34:09+08:00
生成方式：`sed -n 'A,Bp' <file>`，行号为源文件真实行号，未经编辑。

> ⚠️ **时间戳说明**：本文件生成于 2026-09-26 18:34:09，捕获的是**提交态**（`HEAD = 10e9df9`，
> 即 v2.5.4）的源码。当日 18:37 起，工作区出现了**另一条并行工作流**的未提交改动
> （`player/ReportGate.kt`、`player/ReportGateStore.kt` 新增，`player/PlayReporter.kt` 被改），
> 因此**今天磁盘上的 `PlayReporter.kt` 已与本摘录不同**（多了跨音源闸门）。
> 本报告的所有行号与结论都以**本摘录 / HEAD** 为准；落地现状见
> `../../probe-playreporter.md` §3.6。


## 1. `app/src/main/java/com/takahashirinta/ncrust/player/PlayReporter.kt`（全文 84 行）

```kotlin
     1	//! 播放行为上报。
     2	//!
     3	//! 复刻官方 web 播放器的 webLog 上报:当一首歌被"听完"(自然结束或进度 ≥80%)时,
     4	//! 向ncm端上报一条 play 行为,使本地收听能够反馈给推荐/指数体系。
     5	//!
     6	//! 链路(从 music.163.com web 播放器 JS 静态还原):
     7	//!   POST https://clientlogusf.music.163.com/api/feedback/weblog?csrf_token=<csrf>
     8	//!   form: logs = JSON.stringify([{ action: "play", json: {...} }])
     9	//! 该链路不经过 eapi/weapi 加密,只做普通表单 POST。
    10	//!
    11	//! ⚠️ 风险提示: webLog 的精确定位(尤其是内部 useNewEncrypt 握手)无法仅凭静态 JS
    12	//! 百分之百复现,本实现尽力贴合真实请求形态;上报本身为"尽力而为",失败绝不影响播放。
    13	
    14	package com.takahashirinta.ncrust.player
    15	
    16	import android.util.Log
    17	import com.takahashirinta.ncrust.network.RetrofitClient
    18	import org.json.JSONArray
    19	import org.json.JSONObject
    20	import kotlin.concurrent.thread
    21	
    22	object PlayReporter {
    23	    private const val TAG = "PlayReporter"
    24	    private const val WEBLOG_PATH = "/api/feedback/weblog"
    25	    private const val WEBLOG_HOST = "https://clientlogusf.music.163.com"
    26	
    27	    /** 判定"听完"的进度阈值。 */
    28	    private const val COMPLETION_THRESHOLD = 0.8f
    29	
    30	    /**
    31	     * 上报一首歌的播放行为。
    32	     *
    33	     * @param songId 歌曲 ID
    34	     * @param strategy 推荐策略标识(alg),可为空
    35	     * @param playedMs 实际播放时长(毫秒)
    36	     * @param end 结束原因,对齐官方枚举: "playend" / "interrupt" / "ui" / "exception"
    37	     * @param isWifi 是否 Wi-Fi
    38	     */
    39	    fun reportPlay(
    40	        songId: Long,
    41	        playedMs: Long,
    42	        durationMs: Long,
    43	        end: String = "playend",
    44	        strategy: String? = null,
    45	        isWifi: Boolean = false,
    46	    ) {
    47	        val cookie = RetrofitClient.getCookie() ?: return
    48	        if (!cookie.contains("MUSIC_U") || songId <= 0) return
    49	
    50	        val csrf = RetrofitClient.getCsrfToken() ?: return
    51	        val url = "$WEBLOG_HOST$WEBLOG_PATH?csrf_token=$csrf"
    52	
    53	        val json = JSONObject()
    54	            .put("type", "song")
    55	            .put("wifi", if (isWifi) 0 else 1)
    56	            .put("download", 0)
    57	            .put("id", songId)
    58	            .put("time", playedMs)
    59	            .put("end", end)
    60	            .put("mainsite", "1")
    61	            .put("mainsiteWeb", "1")
    62	        if (!strategy.isNullOrEmpty()) json.put("alg", strategy)
    63	
    64	        val logs = JSONArray().put(
    65	            JSONObject().put("action", "play").put("json", json)
    66	        ).toString()
    67	
    68	        // 尽力而为,失败不影响播放。
    69	        thread(name = "ncrust-weblog") {
    70	            try {
    71	                val resp = RetrofitClient.postWeblog(url, logs)
    72	                Log.d(TAG, "weblog resp: ${resp.code} duration=${playedMs}/${durationMs}")
    73	                resp.close()
    74	            } catch (e: Exception) {
    75	                Log.w(TAG, "weblog report failed", e)
    76	            }
    77	        }
    78	    }
    79	
    80	    /** 是否已达到"听完"阈值。 */
    81	    fun reachedCompletion(positionMs: Long, durationMs: Long): Boolean {
    82	        return durationMs > 0 && positionMs.toFloat() / durationMs.toFloat() >= COMPLETION_THRESHOLD
    83	    }
    84	}```

## 2. `source/MusicSource.kt` · SourceIds（L136-209：QQ_ID_FLAG / isQqId / qqId / qqRawId / sourceOfId）

```kotlin
136	/**
137	* qm 曲目的**数字 id**（v2.1.0 · A）。
138	*
139	* ## 为什么必须把它和 ncm 的 id 隔离开
140	*
141	* 本应用有 10+ 处**以裸 `Long` 歌曲 id 作唯一键**的结构，且它们全都跨版本持久化：
142	* 队列去重与持久化（`ncrust_playback_state`）、续播进度表、离线曲目索引、
143	* **离线音频缓存 key**（`song:<id>:<level>`）、歌词缓存 key、收藏 id 列表、ExoPlayer 的 mediaId。
144	* ncm 的 songId 与 qm 的 songid 各自独立编号，撞号是迟早的事 ——
145	* 一旦撞上，后果按严重度排：**播出另一首歌的音频字节** > 串歌词 > 串续播进度 > 收藏错乱。
146	*
147	* ## 做法：把 QQ 的 id 抬到一个 ncm 永远到不了的正数区间
148	*
149	* `qqId = (1L shl 62) or rawId`。ncm 的 id 是十进制百万~十亿量级（远小于 2^40），
150	* 永远不可能触到 2^62。于是：
151	*
152	* - **所有既有结构一个字节都不用改**，也不需要给它们做数据迁移
153	*   （对照方案是在 OfflineKeys / LyricsCache / PlaybackStateManager / OfflineLibrary /
154	*   LibraryManager 五个文件里各做一次「key 带音源 + 老 key 兼容读」，迁移面大得多，
155	*   而本仓库 v1.9.3 的教训正是「加字段 = 加迁移逻辑 = 加单测」）；
156	* - 撞号从「需要每个调用点都记得带音源」变成**结构上不可能**；
157	* - id 仍是 `Long`，不引入新的类型与装箱。
158	*
159	* ## 反解必须无损
160	*
161	* [qqRawId] 用掩码取回真实 songid。真实 songid 是 9~10 位十进制数，
162	* 不可能占到位 62（真占了就退回散列兜底，见下），所以掩码是无损的。
163	*/
164	const val QQ_ID_FLAG: Long = 1L shl 62
165	    30	
166	/** 是否为 [qqId] 造出来的 qm id。 */
167	fun isQqId(id: Long): Boolean = (id and QQ_ID_FLAG) != 0L
168	    33	
169	/**
170	* 从**裸 id** 反推音源（v2.1.5）。纯逻辑，JVM 可单测。
171	*
172	* 只有一个判据能用：[QQ_ID_FLAG]。ncm 的 songId 是十进制百万~十亿量级
173	* （远小于 `2^40`），**永远不可能**触到位 62 —— 所以「带标志位 ⇒ qm」
174	* 是一个结构性的、不会误判的结论，而不是启发式。
175	*
176	* ## 为什么需要它
177	*
178	* 有些持久化路径只存得下裸 id（`PlaybackStateManager` 的 `song_id` 就是），
179	* 那条路恢复出来的曲目**没有音源字符串**。若按「null ⇒ ncm」处理，
180	* 一首 QQ 曲目会被拿去问 ncm 的歌词接口（id 是 `2^62` 量级，必然查不到），
181	* 表现就是「冷启动恢复 QQ 歌曲时永远没有歌词」。
182	* 有标志位在，这个二义性本来就不存在，不该丢掉这条信息。
183	*
184	* **注意它推不出 songmid**：QQ 取链与取词都需要 songmid，而那只能来自队列里的
185	* [com.takahashirinta.ncrust.network.SongItem.sourceId]。所以本函数只负责
186	* 「别问错平台」，不负责「能不能取到」。
187	*/
188	fun sourceOfId(id: Long): MusicSource =
189	if (isQqId(id)) MusicSource.QQMUSIC else MusicSource.NETEASE
190	    55	
191	/**
192	* 造一个 qm 的数字 id。
193	*
194	* @param rawSongId 服务端给的 songid。**<= 0 或已经占到标志位时**改用 [sourceId] 的散列兜底
195	*   （某些接口只给 songmid 不给 songid；兜底必须是确定性的，因为队列持久化、
196	*   离线缓存、续播进度都拿它当 key）。
197	*/
198	fun qqId(rawSongId: Long, sourceId: String): Long {
199	val raw = if (rawSongId > 0L && rawSongId < QQ_ID_FLAG) rawSongId else hashSourceId(sourceId)
200	return QQ_ID_FLAG or raw
201	}
202	    67	
203	/**
204	* 反解 [qqId]；传入的不是 qm id 时返回 null。
205	*
206	* 返回 null 而不是「原样返回」是**有意的**：调用方拿到 null 说明「这不是一个 qm 的 id」，
207	* 此时把 id 当 QQ 的 songid 用一定是个 bug，静默通过只会让它跑到取链那一步才炸。
208	*/
209	fun qqRawId(id: Long): Long? = if (isQqId(id)) id and (QQ_ID_FLAG - 1L) else null
```

## 3. `ui/viewmodel/PlayerViewModel.kt` L538-591（唯一的两个 reportPlay 调用点 + 进度 ticker 回调 + 播放结束回调）

```kotlin
538	PlaybackService.onProgressUpdate = { pos, dur ->
539	currentPosition.value = pos
540	duration.value = dur
541	progress.value = if (dur > 0) pos.toFloat() / dur.toFloat() else 0f
542	     5	
543	// v2.2.1 · P0：「确实播出声了」是自动跳歌熔断的**唯一**清零条件。
544	// 不能拿「起播成功」当清零条件 —— 起播即失败的那些歌会把计数冲掉，
545	// 5 次上限就永远数不到（实测那轮级联就是这么绕过一切防线的）。
546	if (currentSongId.value != null && pos >= AutoSkipGuard.PROGRESS_CONFIRM_MS) {
547	autoSkipGuard.onProgressConfirmed()
548	// v2.5.4 · C：QQ 兜底统计的「真的播出声了」判据。
549	//
550	// 为什么挂在这里而不是「起播成功」：与上面那条熔断清零条件同源 ——
551	// 「起播成功」不等于「出声了」（v2.2.1 的级联故障就是起播即失败）。
552	//
553	// 开销：本分支**已经在每 tick 执行**，新增的只是一次 Long 比较与一次
554	// @Volatile 比较；`onPlaybackTick` 内部先用「本曲已确认」短路，
555	// 所以实际每曲只自增一次。不新增集合查找、不新增分配、不新增调度、
556	// 不碰 IO、不碰网络 —— 统计是旁路，不是功能（铁律 4）。
557	QqProbeCounters.onPlaybackTick(currentSongId.value ?: -1L, currentTrack?.sourceId)
558	}
559	    22	
560	// 播放行为上报: 进度达 80% 视为"听完",每首歌只上报一次。
561	val sid = currentSongId.value ?: -1L
562	if (sid > 0 && sid != lastReportedSongId && PlayReporter.reachedCompletion(pos, dur)) {
563	lastReportedSongId = sid
564	PlayReporter.reportPlay(sid, pos, dur, end = "playend", isWifi = isOnWifi())
565	}
566	    29	
567	// B4：每 5s 落盘一次本歌进度，供断点续播；播完会由 onPlaybackEnded 清除。
568	if (sid > 0 && kotlin.math.abs(pos - lastSavedPositionMs) >= POSITION_SAVE_INTERVAL_MS) {
569	lastSavedPositionMs = pos
570	PlaybackStateManager.saveSongPosition(getApplication(), sid, pos)
571	}
572	    35	
573	// Signal the preload window once per song (guarded by !needsPreload.value).
574	if (gaplessEnabled && dur > 0 && pos > 1_000L && !needsPreload.value) {
575	val remaining = dur - pos
576	if (remaining in 1L..PRELOAD_THRESHOLD_MS) {
577	needsPreload.value = true
578	}
579	}
580	}
581	PlaybackService.onPlaybackEnded = {
582	// 自然播放结束时,若尚未上报则补一条 playend。
583	val sid = currentSongId.value ?: -1L
584	// B4：正常播完 = 这首歌已「听完」，清除进度记录，重播时从 0 分 0 秒对齐。
585	if (sid > 0) PlaybackStateManager.clearSongPosition(getApplication(), sid)
586	if (sid > 0 && sid != lastReportedSongId) {
587	lastReportedSongId = sid
588	PlayReporter.reportPlay(sid, duration.value, duration.value, end = "playend", isWifi = isOnWifi())
589	}
590	onSongEndedCallback?.invoke()
591	}
```

## 4. `ui/viewmodel/PlayerViewModel.kt` L219 / L560-565 / L581-591（lastReportedSongId 去重状态）

```kotlin
215	private var playJob: Job? = null
216	private var preloadJob: Job? = null
217	     3	
218	// 防止同一首歌重复上报播放行为。
219	private var lastReportedSongId = -1L
220	     6	
221	/** B4：本歌最近一次已落盘的进度，避免每 500ms 的采样都写一次 SharedPreferences。 */
222	private var lastSavedPositionMs = -1L
```

## 5. `network/RetrofitClient.kt` L200-231（postWeblog / getCsrfToken / getCookie 的形态）

```kotlin
200	.header("Cookie", currentCookie ?: "")
201	.build()
202	     3	
203	return plainClient.newCall(request).execute().body?.string() ?: throw Exception("empty response")
204	}
205	     6	
206	/**
207	* 播放行为上报(webLog)。与官方 web 播放器一致,POST 到 clientlogusf 日志域,
208	* body 为表单 `logs=<JSON 数组>`,并携带 session Cookie。
209	*
210	* 这条链路不走 eapi/weapi 加密,只做普通表单 POST + csrf_token。
211	*/
212	fun postWeblog(weblogUrl: String, logsJson: String): okhttp3.Response {
213	val request = Request.Builder()
214	.url(weblogUrl)
215	.post(FormBody.Builder().add("logs", logsJson).build())
216	.header("User-Agent", UA)
217	.header("Referer", "https://music.163.com/")
218	.header("Cookie", currentCookie ?: "")
219	.build()
220	return plainClient.newCall(request).execute()
221	}
222	    23	
223	/** 从当前 Cookie 串中提取 __csrf token,用于 weblog 上报。 */
224	fun getCsrfToken(): String? {
225	val cookie = currentCookie ?: return null
226	return cookie.split(';')
227	.asSequence()
228	.map { it.trim() }
229	.filter { it.startsWith("__csrf=") }
230	.map { it.removePrefix("__csrf=") }
231	.firstOrNull()
```

## 6. `player/PlaybackService.kt` L1497-1511（2Hz 进度 ticker：onProgressUpdate 的唯一发射点）

```kotlin
1497	private fun startProgressUpdates() {
1498	progressJob?.cancel()
1499	progressJob = scope.launch {
1500	while (isActive) {
1501	if (player.isPlaying) {
1502	onProgressUpdate?.invoke(player.currentPosition, player.duration)
1503	updatePlaybackState()
1504	}
1505	// 500 ms tick：歌词滚动/进度条精度感知不到差异，但把 UI 层 4Hz
1506	// 广播降到 2Hz，PlayerViewModel 的三个 StateFlow / SlimProgressBar
1507	// 每秒重绘次数直接减半，低端机主线程 snapshot 广播压力显著下降
1508	delay(500)
1509	}
1510	}
1511	}
```

## 7. `player/PlaybackService.kt` L425-430 + L765-784（STATE_ENDED → onPlaybackEnded；媒体键 NEXT → onPlaybackEnded）

```kotlin
425	player.addListener(object : Player.Listener {
426	override fun onPlaybackStateChanged(state: Int) {
427	if (state == Player.STATE_ENDED) {
428	onPlaybackEnded?.invoke()
429	}
430	onBufferingChanged?.invoke(state == Player.STATE_BUFFERING)
  ...
765	override fun onPlayerCommandRequest(
766	session: M3MediaSession,
767	controller: M3MediaSession.ControllerInfo,
768	playerCommand: Int
769	): Int = when (MediaSessionMerge.route(playerCommand)) {
770	MediaSessionMerge.Route.APP_NEXT -> {
771	Log.i(TAG_MEDIA_PANEL, "media button NEXT -> app queue (onPlaybackEnded)")
772	onPlaybackEnded?.invoke()
773	SessionResult.RESULT_SUCCESS
774	}
775	    11	
776	MediaSessionMerge.Route.APP_PREVIOUS -> {
777	Log.i(TAG_MEDIA_PANEL, "media button PREVIOUS -> app queue (onPlaybackPrevious)")
778	onPlaybackPrevious?.invoke()
779	SessionResult.RESULT_SUCCESS
780	}
781	    17	
782	MediaSessionMerge.Route.MEDIA3_DEFAULT ->
783	super.onPlayerCommandRequest(session, controller, playerCommand)
784	}
```

## 8. `qq/QqSongMapper.kt` L94（QQ 合成 id 的唯一产地）

```kotlin
88	fun fromSongObject(item: JSONObject?): SongItem? {
89	if (item == null) return null
90	val mid = item.optString("mid").takeIf { it.isNotEmpty() }
91	?: item.optString("songmid").takeIf { it.isNotEmpty() }
92	?: return null
93	val rawId = item.optLong("id", 0L)
94	val syntheticId = SourceIds.qqId(rawId, mid)
95	// v2.5.4 · C：解析期埋点（唯一落点）。
96	// 「服务端没给 songid」在这里是**唯一可判**的地方：`optLong("id", 0L)` 把
97	// 「字段缺失」与「字段是 0」压成了同一个值，两者都走散列兜底。
98	// 一次 AtomicLong 自增 + 一次毫秒时间戳，无分配、无 IO、无网络。
99	QqProbeCounters.onParsed(
100	hadSongId = rawId > 0L && rawId < SourceIds.QQ_ID_FLAG,
101	// media_mid 缺失时 QqApi 会回落 songmid 取链；这里只记事实，不改行为。
102	hadMediaMid = item.optJSONObject("file")
103	?.optString("media_mid")?.isNotEmpty() == true,
104	)
```

## 9. `qq/QqMusicSourceProvider.kt` L24-45（QQ 侧「不做播放上报」的既有声明）

```kotlin
24	*
25	* 与 [com.takahashirinta.ncrust.source.NeteaseSourceProvider] 的差别不只是换个 API：
26	* QQ 这一侧**没有云歌单/收藏/播放上报**的对应物（本版也不做，见下），
27	* 它提供的是「能搜、能放、能出歌词」这条最小可用链。
28	*
29	* ## 为什么 [isLoggedIn] 为 false 时**不**把自己从路由表里摘掉
30	*
31	* 摘掉的话，未登录用户搜索 qm 只能得到空结果，而**原因是「未登录」还是「搜不到」
32	* 在 UI 上完全无法区分**。保留注册、让请求照发：
33	* 实测匿名态搜索与歌词都能拿到数据（只有取链会被拒，`result=104003`），
34	* 所以未登录用户至少能搜到歌、看到歌词，点播放时才提示需要登录 —— 这是更好的降级。
35	*
36	* ## 本版**不做**的事（避免误以为已支持）
37	*
38	* - 不把 QQ 歌曲加进 ncm 歌单/收藏（那需要「本地歌单」这个尚不存在的概念，
39	*   而且 ncm 的歌单写接口会拒绝外部曲目）；
40	* - 不把 QQ 的播放行为上报给任何一方（QQ 侧没有对应的 webLog 机制，也不该伪造）。
41	*/
42	object QqMusicSourceProvider : MusicSourceProvider {
43	    20	
44	private const val TAG = "QqMusicSource"
45	    22	
```

## 10. `qq/QqProbeStore.kt`（落盘做法的参照物，全文 89 行）

```kotlin
     1	/*
     2	 * Ncrust —— ncm 第三方客户端
     3	 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
     4	 *
     5	 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
     6	 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
     7	 *
     8	 * v2.5.4 · C：QQ 兜底统计的**落盘**（本地私有目录，绝不上报）。
     9	 */
    10	
    11	package com.takahashirinta.ncrust.qq
    12	
    13	import android.content.Context
    14	import android.util.Log
    15	import com.google.gson.Gson
    16	
    17	/**
    18	 * `QqFallbackCounters` 的持久化。**本地私有目录，没有网络出口。**
    19	 *
    20	 * ## 为什么单开一个 prefs 文件，而不是塞进 `ncrust_offline`
    21	 *
    22	 * `ncrust_offline` 与「清空离线缓存」是配对的不变量
    23	 * （`UserScreen` 的清缓存入口 → `OfflineAudioCache.clear`）。诊断计数一旦住在那里，
    24	 * 用户顺手清一次缓存就把样本抹掉了 —— 而本版要的正是「跑一两天」的累积样本。
    25	 *
    26	 * ## 写盘时机（绝不在埋点点位落盘）
    27	 *
    28	 * 只有两处：① debug 诊断入口被点开时；② `MainActivity.onStop`（进程可能被杀）。
    29	 * 两处都不在播放关键路径上，而且都用 `apply()`（异步落盘、不阻塞调用线程）。
    30	 *
    31	 * ## 加字段 = 加迁移逻辑 = 加单测
    32	 *
    33	 * 读出来的 JSON 先过 [QqFallbackCounters.canonical]（`null` 归零）再喂给计数器。
    34	 * 老 JSON 缺字段 ⇒ `null` ⇒ 0 ⇒ 语义正确（「没记到」）。
    35	 * 这条与 `OfflineLibrary` / `OfflineUrlStore` 的三段式（纯逻辑 / 落盘 / 快照）一致。
    36	 */
    37	object QqProbeStore {
    38	
    39	    private const val TAG = "QqProbeStore"
    40	    private const val PREFS = "ncrust_qq_probe"
    41	    private const val KEY = "stats"
    42	
    43	    private val gson = Gson()
    44	
    45	    @Volatile
    46	    private var seeded = false
    47	
    48	    /** JSON → 快照。坏 JSON 返回 `null`（调用方按「还没有样本」处理，**不抛**）。 */
    49	    fun decode(json: String?): QqFallbackCounters? {
    50	        if (json.isNullOrEmpty()) return null
    51	        return try {
    52	            gson.fromJson(json, QqFallbackCounters::class.java)
    53	        } catch (_: Exception) {
    54	            null
    55	        }
    56	    }
    57	
    58	    fun encode(counters: QqFallbackCounters): String = gson.toJson(counters)
    59	
    60	    private fun prefs(context: Context) =
    61	        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    62	
    63	    /** 进程启动后**第一次**触达时把落盘样本读回计数器（幂等）。 */
    64	    fun ensureSeeded(context: Context) {
    65	        if (seeded) return
    66	        seeded = true
    67	        val json = prefs(context).getString(KEY, null)
    68	        val restored = decode(json)
    69	        if (restored != null) QqProbeCounters.seed(restored)
    70	    }
    71	
    72	    /**
    73	     * 把当前快照写回磁盘，并返回它。
    74	     *
    75	     * 返回快照而不是 Unit：唯一的调用点是 debug 诊断入口，它要同时
    76	     * 「读出来给人看」和「落盘」—— 分两次调用就会出现两份不同的数字。
    77	     */
    78	    fun snapshotAndFlush(context: Context): QqFallbackCounters {
    79	        val counters = QqProbeCounters.snapshot()
    80	        return try {
    81	            prefs(context).edit().putString(KEY, encode(counters)).apply()
    82	            counters
    83	        } catch (e: Exception) {
    84	            // 落盘失败不该影响任何东西 —— 统计是旁路，不是功能。
    85	            Log.w(TAG, "qq probe flush failed", e)
    86	            counters
    87	        }
    88	    }
    89	}
```
