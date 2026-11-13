/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明（B4 进度↔歌词对齐）：
 *   - 新增「每首歌曲的播放进度记忆」：中途退出可从断点续播；
 *     正常播完则清除记录，使重播从 0 分 0 秒开始，避免进度条与歌词错位。
 */

package com.takahashirinta.ncrust.player

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.cache.trimOldest
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.source.SourceIds
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PlaybackStateManager {
    private const val PREFS_NAME = "ncrust_playback_state"
    private const val KEY_SONG_ID = "song_id"
    private const val KEY_SONG_NAME = "song_name"
    private const val KEY_SONG_ARTIST = "song_artist"
    private const val KEY_SONG_ARTWORK = "song_artwork"
    // v2.1.0 · C：音源身份要一起落盘，否则冷启动恢复出来的 QQ 曲目会被当成 ncm 同号歌曲。
    // 可空 + 默认 null：v2.1.0 之前写入的状态里没有这几个 key，读到就是 null ⇒ ncm。
    private const val KEY_SONG_SOURCE = "song_source"
    private const val KEY_SONG_SOURCE_ID = "song_source_id"
    private const val KEY_SONG_MEDIA_ID = "song_media_id"
    private const val KEY_IS_PLAYING = "is_playing"
    private const val KEY_HAS_STATE = "has_state"

    // 队列持久化 key
    private const val KEY_QUEUE = "queue"
    private const val KEY_QUEUE_INDEX = "queue_index"

    // 播放模式 key（v2.0.0 · T1-C）。合法区间 0..4 = QueueModes.CYCLE..INFINITY，
    // 非法值一律回落 CYCLE —— prefs 是跨版本存活的，别让它变成一个能越界的脏值。
    private const val KEY_PLAY_MODE = "play_mode"

    private const val KEY_SHUFFLE = "shuffle_round_v1"

    // 每首歌曲进度记忆 key（B4）
    private const val KEY_SONG_POSITIONS = "song_positions"
    // 上限：超出后按保存时间淘汰最旧的，避免 SharedPreferences 无限膨胀。
    private const val MAX_SONG_POSITIONS = 300

    // 复用一个 Gson 实例：new Gson() 会构建反射映射表，几百首歌频繁调用时反射初始化非常热。
    // Gson 本身线程安全。
    private val gson = Gson()
    private val songListType = object : TypeToken<List<SongItem>>() {}.type

    // 队列写盘 debounce：连续 addToQueue / insertNext / removeFromQueue 会累计触发。
    // 200 ms 合并一次能把连续 20 首歌的加入压成 1 次 IO，避免主线程 Gson.toJson 抖动。
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val flushLock = Any()
    private var queueFlushJob: Job? = null
    private var queueGeneration = 0L
    private var savedQueue: SavedQueue? = null
    private var playModeGeneration = 0L

    private class SavedQueue(
        val context: WeakReference<Context>,
        val songs: WeakReference<List<SongItem>>,
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ---------- 单曲状态 ----------
    fun saveState(
        context: Context,
        songId: Long,
        title: String,
        artist: String,
        artwork: String,
        isPlaying: Boolean,
        sourceKey: String? = null,
        sourceId: String? = null,
        mediaId: String? = null,
    ) {
        getPrefs(context).edit()
            .putBoolean(KEY_HAS_STATE, true)
            .putLong(KEY_SONG_ID, songId)
            .putString(KEY_SONG_NAME, title)
            .putString(KEY_SONG_ARTIST, artist)
            .putString(KEY_SONG_ARTWORK, artwork)
            .putBoolean(KEY_IS_PLAYING, isPlaying)
            .putString(KEY_SONG_SOURCE, sourceKey)
            .putString(KEY_SONG_SOURCE_ID, sourceId)
            .putString(KEY_SONG_MEDIA_ID, mediaId)
            .apply()
    }

    /** v2.1.0 · C：上次播放曲目的音源身份（无记录时为 null ⇒ ncm）。 */
    fun getSourceKey(context: Context): String? = getPrefs(context).getString(KEY_SONG_SOURCE, null)
    fun getSourceId(context: Context): String? = getPrefs(context).getString(KEY_SONG_SOURCE_ID, null)
    fun getMediaId(context: Context): String? = getPrefs(context).getString(KEY_SONG_MEDIA_ID, null)

    fun updatePlayingState(context: Context, isPlaying: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_PLAYING, isPlaying).apply()
    }

    /**
     * 只清「当前播放状态」（上次播到哪首、是否在播），**不清**用户的播放模式、队列与续播记录。
     *
     * 旧实现是 `edit().clear()`：`PlayerViewModel.stopService` 调用它之后，用户存的随机/单曲
     * 循环模式被一起抹掉，下次启动读回 CYCLE（`getPlayMode` 的默认值）。队列与续播表也被清空，
     * 但内存里的 positionCache 没清 —— 冷热两套口径。
     */
    fun clearState(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_SONG_ID)
            .remove(KEY_SONG_NAME)
            .remove(KEY_SONG_ARTIST)
            .remove(KEY_SONG_ARTWORK)
            .remove(KEY_SONG_SOURCE)
            .remove(KEY_SONG_SOURCE_ID)
            .remove(KEY_SONG_MEDIA_ID)
            .remove(KEY_IS_PLAYING)
            .remove(KEY_HAS_STATE)
            .apply()
    }

    fun hasState(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAS_STATE, false)
    }

    data class SavedState(
        val songId: Long,
        val songName: String,
        val songArtist: String,
        val songArtwork: String,
        val isPlaying: Boolean
    )

    fun getState(context: Context): SavedState? {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_HAS_STATE, false)) return null
        return SavedState(
            // H14 残余：旧版本落盘的 B 站视频 id 没有位 60，与 queue 走**同一条**纯函数
            // 补位。少这一处的话，「续播的那一首」与「迁移后的队列条目」会算成两首歌
            // （表现：冷启动后当前项高亮丢失、队列里那一行点不动）。
            // 只改返回值、不回写：判据幂等，重复读得到同一个结果，没有必要在 getter 里写盘。
            songId = run {
                val raw = prefs.getLong(KEY_SONG_ID, 0)
                val payload = prefs.getString(KEY_SONG_SOURCE_ID, null)
                // J10：单曲状态里没有 qqSongId 落盘（历史形状），但队列那条路径会带上；
                // 这里能判出的只有 B 站补位，QQ 散列 id 在队列侧迁移后再回来。
                SourceIds.migrateBiliAvPartition(raw, payload)
            },
            songName = prefs.getString(KEY_SONG_NAME, "") ?: "",
            songArtist = prefs.getString(KEY_SONG_ARTIST, "") ?: "",
            songArtwork = prefs.getString(KEY_SONG_ARTWORK, "") ?: "",
            isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false)
        )
    }

    // ---------- 每首歌曲的播放进度记忆（B4） ----------
    //
    // 语义（对应「每首歌自动从 0 分 0 秒播放对齐，有播放记录的就从退出点续播」）：
    //   - 中途切歌 / 暂停 / 进程被杀 → 记下当前进度，下次再播这首歌从断点续播；
    //   - 正常播完 → **清除**记录，重播必定从 0:00 开始。
    // 清除这一步很关键：若播完仍留着旧进度，重播时播放器从 0 开始而歌词面板/
    // 进度条可能停在旧位置，就会出现「歌词对不上歌」。
    //
    // ⚠️ H14 残余：**这张表故意不参与 B 站分区位迁移**。它的 key 是裸 `Long` 且**没有**
    // `sourceId` 可查，因此 `BILI_ID_FLAG|101` 到底是 `au101` 还是「没补位的 av101」
    // **无法判定**。补错方向就是把两条不同曲目的续播点合成一个（比「少一条续播」严重得多）；
    // 代价只是老视频条目的续播点读不出来（从 0:00 开始播，一次性的）。
    // 队列与单曲状态那边有载荷可判，所以在那里迁移（见 [migrateQueueSongs] / [getState]）。
    /**
     * 某首歌的续播记录。
     *
     * v2.5.5 · A：**字段名必须显式声明**（AGENTS.md v2.5.4 规则 1 / 本版铁律 18）。
     * R8 曾把这两个字段混淆成 `a` / `b`（`mapping.txt` 取证见
     * `docs/verification/v2.5.5/probe-r8-keys.md` §2 的 F2）—— 同 APK 内读写自洽所以不崩，
     * 但字母表一变就会「位置↔时间戳对调」或「续播静默失效」。
     *
     * `proguard-rules.pro` 已有的全局规则
     * `-keepclassmembers,allowobfuscation class * { @SerializedName <fields>; }`
     * 保证注解里的字符串常量进 dex，落盘 key 从此与字段名无关。
     *
     * 读路径见 [PlaybackPositionCodec]（认稳定名 / 旧单字母 / 声明顺序三种形状）。
     */
    data class PositionEntry(
        @SerializedName("posMs") val posMs: Long,
        @SerializedName("savedAtMs") val savedAtMs: Long,
    )

    private val positionLock = Any()
    private var positionGeneration = 0L
    @Volatile private var positionCache: MutableMap<Long, PositionEntry>? = null
    private var posFlushJob: Job? = null

    private fun ensurePositionsLoaded(context: Context): MutableMap<Long, PositionEntry> {
        positionCache?.let { return it }
        return synchronized(positionLock) {
            positionCache ?: run {
                // v2.5.5 · A：读路径走 PlaybackPositionCodec（认稳定名 / 旧单字母 / 声明顺序），
                // 且**逐条**容错 —— 旧写法 `runCatching { gson.fromJson(...) }` 是
                // 「一个字节坏了，用户全部歌曲的续播记录一起消失」。
                val parsed = PlaybackPositionCodec
                    .decode(getPrefs(context).getString(KEY_SONG_POSITIONS, null))
                parsed.also { positionCache = it }
            }
        }
    }

    /**
     * 读取某首歌的续播位置（毫秒）。
     *
     * 返回 0 表示「没有可用记录」——包括从未播过、已播完被清除、
     * 以及位置小于 [MIN_RESUMABLE_MS] 或已达到当前曲目的已知时长。
     *
     * @param durationMs 本曲**当前解析出来的真实时长**；0 / 负数 = 未知。
     *   已知时长时必须核对：记录可能来自「上一次解析出的更长的源」（完整曲 vs 试听片段，
     *   v3.7.9 · H16）。3 分钟的记录配 20 秒的曲目，续播点已经越界，撤销这条记录并返回 0，
     *   而不是拿它去 seek 到曲尾。
     */
    fun getSongPosition(context: Context, songId: Long, durationMs: Long = 0L): Long {
        if (songId <= 0) return 0
        val map = ensurePositionsLoaded(context)
        return synchronized(positionLock) {
            val entry = map[songId] ?: return 0L
            if (durationMs > 0L && entry.posMs >= durationMs) {
                // 校验和撤销在同一把锁内，避免删掉并发保存的新进度。
                map.remove(songId)
                schedulePositionFlushLocked(context)
                return 0L
            }
            if (entry.posMs < MIN_RESUMABLE_MS) 0L else entry.posMs
        }
    }

    /** 播放整张歌单时撤销这些曲目的断点，一次提交所有删除。 */
    internal fun clearSongPositions(context: Context, queue: List<SongItem>) {
        if (queue.isEmpty()) return
        val map = ensurePositionsLoaded(context)
        synchronized(positionLock) {
            val oldSize = map.size
            if (oldSize == 0) return
            for (song in queue) {
                map.remove(song.id)
                if (map.isEmpty()) break
            }
            if (map.size != oldSize) schedulePositionFlushLocked(context, immediate = true)
        }
    }

    /**
     * 记录某首歌的播放位置（1s debounce 写盘）。
     *
     * 两条**撤销**路径（v3.7.9 · H16）：位置落回起点区间（用户把进度拖回开头），
     * 或超出本曲真实时长（旧记录来自更长的源）。旧实现只是 `return`，于是「上次记的 60 秒」
     * 一直留着，下次又从 60 秒续播 —— 听感上就是「这歌一播就跳到中段/直接结束」。
     *
     * @param durationMs 本曲真实时长；0 / 负数 = 未知，此时不做时长校验。
     */
    fun saveSongPosition(
        context: Context,
        songId: Long,
        positionMs: Long,
        durationMs: Long = 0L,
    ) {
        if (songId <= 0) return
        if (positionMs < MIN_RESUMABLE_MS || (durationMs > 0L && positionMs >= durationMs)) {
            // 显式回到不可续播区间 = 旧记录已经不代表「上次听到哪」⇒ 撤销，而不是忽略本次更新。
            clearSongPosition(context, songId)
            return
        }
        val map = ensurePositionsLoaded(context)
        synchronized(positionLock) {
            map[songId] = PositionEntry(positionMs, System.currentTimeMillis())
            map.trimOldest(MAX_SONG_POSITIONS) { it.savedAtMs }
            schedulePositionFlushLocked(context)
        }
    }

    /** 歌曲正常播完时清除记录，使下次重播从 0:00 开始。 */
    fun clearSongPosition(context: Context, songId: Long) {
        if (songId <= 0) return
        val map = ensurePositionsLoaded(context)
        synchronized(positionLock) {
            if (map.remove(songId) != null) schedulePositionFlushLocked(context)
        }
    }

    /** 持有 positionLock 调用，使写盘任务按内存变更的顺序排队。 */
    private fun schedulePositionFlushLocked(context: Context, immediate: Boolean = false) {
        val appContext = context.applicationContext
        positionGeneration++
        posFlushJob?.cancel()
        posFlushJob = ioScope.launch {
            if (!immediate) delay(1_000L)
            flushPositions(appContext)
        }
    }

    private suspend fun flushPositions(context: Context) {
        val map = positionCache ?: return
        val (generation, snapshot) = synchronized(positionLock) { positionGeneration to map.toMap() }
        try {
            // 与队列一样：序列化放 Default，IO 只做写盘。
            // v2.5.5 · A：序列化改走 codec（显式字段名），不再依赖 R8 后的类结构。
            val json = withContext(Dispatchers.Default) { PlaybackPositionCodec.encode(snapshot) }
            withContext(Dispatchers.IO) {
                synchronized(positionLock) {
                    if (positionGeneration == generation) {
                        getPrefs(context).edit().putString(KEY_SONG_POSITIONS, json).apply()
                    }
                }
            }
        } catch (_: Exception) {
            // 写失败不影响播放，下次变更再试。
        }
    }

    /**
     * 小于此值的位置不值得续播（直接从头播），也避免把开头几秒当成有效记录。
     *
     * v3.7.9 · H16：`internal` 而非 private —— `PlayerViewModel.seekTo` 要用同一个阈值
     * 判断「这次 seek 是不是落回了起点区间」，避免把数字各写一份。
     */
    internal const val MIN_RESUMABLE_MS = 3_000L

    // ---------- 队列持久化 ----------
    internal fun saveQueue(
        context: Context,
        queue: List<SongItem>,
        currentIndex: Int,
        shuffle: (() -> ShuffleSnapshot)? = null,
    ) = scheduleQueueWrite(context, queue, currentIndex, shuffle, cursorOnly = false)

    /** 已保存的队列只更新游标和随机轮次。首次保存或换队列时会完整编码。 */
    internal fun saveQueueCursor(
        context: Context,
        queue: List<SongItem>,
        currentIndex: Int,
        shuffle: (() -> ShuffleSnapshot)? = null,
    ) = scheduleQueueWrite(context, queue, currentIndex, shuffle, cursorOnly = true)

    private fun scheduleQueueWrite(
        context: Context,
        queue: List<SongItem>,
        index: Int,
        shuffle: (() -> ShuffleSnapshot)?,
        cursorOnly: Boolean,
    ) {
        val app = context.applicationContext
        synchronized(flushLock) {
            if (!cursorOnly) savedQueue = null
            val generation = ++queueGeneration
            val modeGeneration = playModeGeneration
            queueFlushJob?.cancel()
            queueFlushJob = ioScope.launch {
                delay(200L)
                flushQueue(app, queue, index, shuffle, generation, modeGeneration)
            }
        }
    }

    private suspend fun flushQueue(
        context: Context,
        queue: List<SongItem>,
        index: Int,
        shuffle: (() -> ShuffleSnapshot)?,
        generation: Long,
        modeGeneration: Long,
    ) {
        val bodySaved = synchronized(flushLock) {
            if (queueGeneration != generation) return
            savedQueue?.let { it.context.get() === context && it.songs.get() === queue } == true
        }
        try {
            val (body, round) = if (bodySaved && shuffle == null) null to null else {
                withContext(Dispatchers.Default) {
                    val queueJson = if (bodySaved) null else gson.toJson(queue)
                    val snapshot = shuffle?.invoke()
                    val keys = snapshot?.queueKeys
                    val shuffleJson = if (keys != null && snapshot.matches(keys, index)) gson.toJson(snapshot)
                        else null
                    queueJson to shuffleJson
                }
            }
            val prefs = getPrefs(context)
            synchronized(flushLock) {
                if (queueGeneration != generation) return
                val editor = prefs.edit()
                if (body != null) editor.putString(KEY_QUEUE, body)
                editor.putInt(KEY_QUEUE_INDEX, index)
                // 改模式会清除旧轮次。此时旧任务仍可保存队列与游标。
                if (modeGeneration == playModeGeneration) {
                    if (round == null) editor.remove(KEY_SHUFFLE) else editor.putString(KEY_SHUFFLE, round)
                }
                editor.apply()
                if (queueGeneration == generation && body != null) {
                    savedQueue = SavedQueue(WeakReference(context), WeakReference(queue))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 序列化/写盘失败：保留已落盘的旧队列，不清空。等下一次变更再写。
        }
    }

    internal data class RestoredQueue(val songs: List<SongItem>, val index: Int, val shuffle: ShuffleSnapshot?)

    internal suspend fun getQueue(context: Context, readShuffle: Boolean = false): RestoredQueue? {
        return try {
            val (json, index, roundJson) = withContext(Dispatchers.IO) {
                val prefs = getPrefs(context)
                synchronized(flushLock) {
                    val json = prefs.getString(KEY_QUEUE, null)
                    if (json.isNullOrEmpty() || json == "[]") return@synchronized null
                    val round = if (readShuffle) runCatching { prefs.getString(KEY_SHUFFLE, null) }.getOrNull()
                        else null
                    Triple(json, prefs.getInt(KEY_QUEUE_INDEX, 0), round)
                }
            } ?: return null
            // Gson 反射反序列化是 CPU 密集，切 Default 避免主线程卡顿（队列几百首时 50ms+）。
            withContext(Dispatchers.Default) {
                val restored: List<SongItem> = gson.fromJson(json, songListType)
                // H14 残余：读盘后过一次「旧 B 站视频腿补分区位」（纯函数 + 幂等）。
                val queue = migrateQueueSongs(restored)
                if (queue.isEmpty()) return@withContext null
                val cursor = index.coerceIn(queue.indices)
                val round = roundJson?.let {
                    runCatching {
                        gson.fromJson(it, ShuffleSnapshot::class.java)
                            ?.takeIf { snapshot -> snapshot.matches(queue.map { song -> QueueKeys.keyOf(song).tag }, cursor) }
                    }.getOrNull()
                }
                RestoredQueue(queue, cursor, round)
            }
        } catch (e: CancellationException) {
            // 取消不是「队列损坏」：原样抛出，绝不顺手删除已落盘的队列。
            throw e
        } catch (_: Exception) {
            // 解析失败只影响本次读取：保留磁盘数据（可能是新版写的、旧版读不懂），
            // 交回 null 让上层走空队列，而不是把用户队列删掉。
            null
        }
    }

    /**
     * **H14 残余**：队列条目的读时身份迁移（**纯函数**，不碰 Context，JVM 可单测）。
     *
     * ## 为什么必须在这里做
     *
     * 队列 JSON 里只有 `List<SongItem>`，**没有**独立的 trackKey —— 判重、待播槽位、
     * 切歌定位全都由 `TrackKey.ofSong(song)` 现算，也就是说「身份」就是 `song.id` 本身。
     * 旧版本写下的 B 站**视频腿** id 没有位 60（分区位是 v3.7.9 才加的），
     * 不在这里补，它就会与「现在搜出来的同一首视频曲目」算成两首歌：
     * 队列判重拦不住、点它也不会复用正在播的那一项。
     *
     * ## 只动 `id`
     *
     * `sourceId`（`au:<auid>` / `bv:<bvid>:<cid>`）是**取链载荷**，一个字节都不改 ——
     * 改了旧条目会变成「不可解析」，那才是真正的播放回归。判据与幂等性全在
     * [SourceIds.migrateBiliAvPartition]：`au:` 不动、已经补过的再读一遍也不动。
     *
     * 没有任何条目需要迁移时**返回同一个实例**（`===`），调用方据此可以零成本判断
     * 「这次读盘有没有搬家」。
     */
    internal fun migrateQueueSongs(songs: List<SongItem>): List<SongItem> {
        var updated: MutableList<SongItem>? = null
        songs.forEachIndexed { index, song ->
            val migrated = migrateQueueSong(song)
            if (migrated !== song) {
                val copy = updated ?: songs.toMutableList().also { updated = it }
                copy[index] = migrated
            }
        }
        return updated ?: songs
    }

    /** 单条队列条目的迁移；不需要迁移时**返回同一个实例**。 */
    internal fun migrateQueueSong(song: SongItem): SongItem {
        // J10：旧散列合成 id → 规范 id（幂等；没有 qqSongId 的原样保留）。
        val qqMigrated = SourceIds.migrateSynthesizedQqId(song.id, song.sourceId, song.qqSongId)
        val migrated = SourceIds.migrateBiliAvPartition(qqMigrated, song.sourceId)
        return if (migrated == song.id) song else song.copy(id = migrated)
    }

    /**
     * v2.0.0 · T1-C：读取上次使用的播放模式（0 = CYCLE，见 `QueueModes`）。
     *
     * 为什么必须落盘：`playMode` 原先只存在 MainScreen 的 `remember` 里，任何 Activity 重建
     * （低内存回收、"不保留活动"、主题/语言切换）都会把它静默重置成"顺序循环"，
     * 队列于是按**原始顺序**播 —— 网易歌单/每日推荐的原始顺序本来就常按语种/地区/专辑成块，
     * 用户看到的就是"随机播放却十首日语连播"。这是播放模式，不是队列内容，
     * 所以 `clearQueue` **不**清它（换队列不该改用户的模式选择）。
     */
    fun getPlayMode(context: Context): Int =
        getPrefs(context).getInt(KEY_PLAY_MODE, 0).let { if (it in 0..4) it else 0 }

    fun savePlayMode(context: Context, mode: Int) {
        if (mode !in 0..4) return
        val prefs = getPrefs(context)
        synchronized(flushLock) {
            if (runCatching { prefs.getInt(KEY_PLAY_MODE, -1) }.getOrDefault(-1) == mode) return
            playModeGeneration++
            prefs.edit().putInt(KEY_PLAY_MODE, mode).remove(KEY_SHUFFLE).apply()
        }
    }

    fun clearQueue(context: Context) {
        val prefs = getPrefs(context)
        synchronized(flushLock) {
            queueFlushJob?.cancel()
            queueFlushJob = null
            queueGeneration++
            savedQueue = null
            prefs.edit().remove(KEY_QUEUE).remove(KEY_QUEUE_INDEX).remove(KEY_SHUFFLE).apply()
        }
    }
}
