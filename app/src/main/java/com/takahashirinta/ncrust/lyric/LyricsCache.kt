/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 一条缓存的歌词原文、译文与逐字时间轴。
 *
 * [yrc] 是 v1.5.0 · B 新增的；老缓存里没有这个字段，Gson 走 Unsafe 反序列化不会填默认值，
 * 所以声明成可空并在读取处 `orEmpty()` —— 老缓存命中时退化成「没有逐字数据」，不会崩。
 *
 * [ttml] / [ttmlAt] 是 v1.9.0 新增的 AMLL TTML 原文与写入时刻，同样必须**可空 + 有默认值**：
 * 老缓存的 JSON 里没有这两个 key，Gson 同样走 Unsafe，缺字段时 [ttml] 是 null、[ttmlAt] 是 0，
 * 正好等于「没有 TTML」—— 既不崩，也不会被误判成新鲜数据。
 *
 * [romalrc] / [translationSource] / [romanSource] 是 v1.9.2 新增的，**同一条规矩**（可空 + 默认值）：
 * - [romalrc]：ncm 音译轨原文。它本来就在 `/api/song/lyric` 的响应里（实测 `rv=0` 与 `rv=-1`
 *   对同一首歌返回逐字节相同的 body），v1.9.2 之前只是没有解析；
 * - [translationSource] / [romanSource]：译文轨 / 音译轨**上一次实际展示用的源**
 *   （[LyricTrackSource] 的枚举名，见 [LyricTrackSource.cacheTag]）。
 *   **它只是观测字段**：显示哪一份永远由 `LyricTrackMerge` 拿当下的设置 + 当下的原文当场算，
 *   缓存里的标记不参与任何决策。记它的用处是「这首歌的译文到底来自谁」可归因，
 *   以及源真的换了的时候能看出来（值未变时 [putTrackSources] 不落盘）。
 */
data class CachedLyrics(
    val lrc: String,
    val tlyric: String,
    val timestamp: Long,
    val yrc: String? = null,
    val ttml: String? = null,
    val ttmlAt: Long = 0,
    val romalrc: String? = null,
    val translationSource: String? = null,
    val romanSource: String? = null
)

/**
 * 歌词本地缓存。
 *
 * 目的：进程被杀后重进时，无需再等网络即可立即恢复当前歌歌词，消除
 * 「冷启动歌词按钮灰掉 / 歌词消失、必须切歌才回来」的问题。
 *
 * 存储：SharedPreferences 内一个 JSON map（songId -> CachedLyrics）。
 * 内存镜像避免重复读盘；容量上限 [MAX_ENTRIES]，超出按 timestamp 淘汰最旧。
 * 只缓存服务端 code==200 的权威结果（含"确无歌词"的空串），失败不写，
 * 这样下次重试仍会打网络而不是命中一个假的空结果。
 *
 * v1.9.0 起同一张表还承载 AMLL TTML（见 [TTML_KEY_PREFIX] / [getTtml] / [putTtml]），
 * 与 LRC 共用容量上限与淘汰策略；TTML 另有 7 天 TTL（[TTML_TTL_MS]）。
 */
object LyricsCache {
    private const val PREFS = "ncrust_lyrics_cache"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 200

    /**
     * 「只有 TTML、还没有 LRC 条目」时的暂存 key 前缀（v1.9.0）。
     *
     * 为什么不把 TTML 直接写进歌曲自己的条目：条目里 `lrc == ""` 是**「确无歌词」的权威标记**
     * （[put] 会写、PlayerViewModel 命中它就把歌词按钮置灰且不再打网络），而预取 TTML 的下一首
     * 往往还没有 LRC 条目。为了存 TTML 就建一条 lrc="" 的正式条目，等于把那首歌判成「确无歌词」，
     * 一进播放歌词就没了 —— 预取反而把功能弄坏。所以没有正式条目时先落在 `ttml:<songId>` 下，
     * 等 [put] 写正式条目时再并进去（见 [put]），同一首歌最终只留一条。
     */
    private const val TTML_KEY_PREFIX = "ttml:"

    /** TTML 的本地 TTL：7 天。AMLL 投稿修订很慢，7 天足够省掉绝大多数重复拉取。 */
    const val TTML_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val gson = Gson()
    private val type = object : TypeToken<MutableMap<String, CachedLyrics>>() {}.type
    private val lock = Any()

    @Volatile
    private var memory: MutableMap<String, CachedLyrics>? = null

    private fun loadLocked(context: Context): MutableMap<String, CachedLyrics> {
        memory?.let { return it }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
        val map = if (raw.isNullOrEmpty()) {
            mutableMapOf()
        } else {
            runCatching { gson.fromJson<MutableMap<String, CachedLyrics>>(raw, type) }
                .getOrNull() ?: mutableMapOf()
        }
        memory = map
        return map
    }

    /** 超出上限时按 timestamp 淘汰最旧。LRC 条目与 TTML 暂存条目共用这一个名额池。 */
    private fun trimLocked(map: MutableMap<String, CachedLyrics>) {
        if (map.size > MAX_ENTRIES) {
            map.entries
                .sortedBy { it.value.timestamp }
                .take(map.size - MAX_ENTRIES)
                .forEach { map.remove(it.key) }
        }
    }

    private fun persistLocked(context: Context, map: Map<String, CachedLyrics>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ENTRIES, gson.toJson(map))
            .apply()
    }

    suspend fun get(context: Context, songId: Long): CachedLyrics? = withContext(Dispatchers.IO) {
        synchronized(lock) { loadLocked(context)[songId.toString()] }
    }

    /**
     * 写入 LRC / 译文 / 逐字 / 音译。
     *
     * [yrc] / [romalrc] 保持默认参数，v1.5.0 / v1.9.0 的调用点签名不变（v1.9.2 只是多接一个字段）。
     * 若这首歌之前只有 TTML 暂存条目，这里把它并进正式条目并删掉暂存，避免同一首歌留两条。
     *
     * 上一次记下的 [CachedLyrics.translationSource] / [CachedLyrics.romanSource] **原样保留**：
     * 它们描述的是「上一次实际展示了谁」，而这次只是刷新了原文；新的展示结果会由
     * [putTrackSources] 在落地那一步改写（值没变就不写盘）。
     */
    suspend fun put(
        context: Context,
        songId: Long,
        lrc: String,
        tlyric: String,
        yrc: String = "",
        romalrc: String = "",
    ) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val map = loadLocked(context)
                val key = songId.toString()
                val now = System.currentTimeMillis()
                val old = map[key]
                val staged = map.remove(ttmlKey(songId))
                // 正式条目已有 TTML 就用它（putTtml 会就地更新它），否则取暂存的那份。
                val ttmlSource = old?.takeIf { it.ttml != null } ?: staged
                map[key] = CachedLyrics(
                    lrc, tlyric, now, yrc,
                    ttmlSource?.ttml, ttmlSource?.ttmlAt ?: 0,
                    romalrc,
                    old?.translationSource, old?.romanSource
                )
                trimLocked(map)
                persistLocked(context, map)
            }
        }
    }

    /**
     * 记录译文轨 / 音译轨**这一次实际用的源**（v1.9.2 观测字段，见 [CachedLyrics.translationSource]）。
     *
     * 不新建条目：这首歌还没有正式条目时直接 no-op（绝不能为了记一个标记建出 `lrc=""` 的条目，
     * 那等于把它判成「确无歌词」）。值没变就不落盘 —— 缓存命中路径每次都要调它，
     * 而 [persistLocked] 会把整张表序列化一遍，不能为了记一个没变的标记反复写。
     */
    suspend fun putTrackSources(
        context: Context,
        songId: Long,
        translation: LyricTrackSource?,
        roman: LyricTrackSource?,
    ) {
        val translationTag = translation?.cacheTag
        val romanTag = roman?.cacheTag
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val map = loadLocked(context)
                val key = songId.toString()
                val updated = withTrackSources(map[key], translationTag, romanTag) ?: return@withContext
                map[key] = updated
                persistLocked(context, map)
            }
        }
    }

    /**
     * 纯函数：把源标记写进条目。**没有条目、或标记没变**时返回 null（调用方据此跳过落盘）。
     * 抽出来是为了能在 JVM 上直接测「值未变不写盘」这条语义，不必起 Android 环境。
     */
    internal fun withTrackSources(
        entry: CachedLyrics?,
        translationTag: String?,
        romanTag: String?,
    ): CachedLyrics? = when {
        entry == null -> null
        entry.translationSource == translationTag && entry.romanSource == romanTag -> null
        else -> entry.copy(translationSource = translationTag, romanSource = romanTag)
    }

    /**
     * 取 TTL 内的 TTML 原文（[isTtmlFresh]）；过期、没有、内容为空都返回 null，
     * 调用方据此决定要不要打网络。
     */
    suspend fun getTtml(context: Context, songId: Long): String? =
        readTtml(context, songId, requireFresh = true)

    /**
     * 忽略 TTL 取 TTML 原文，**给离线兜底用**：只在 [getTtml] 返回 null（过期或没拉过）
     * 且当前拿不到网络时，用这份旧数据先显示，别让用户看到空白。
     */
    suspend fun getTtmlStale(context: Context, songId: Long): String? =
        readTtml(context, songId, requireFresh = false)

    private suspend fun readTtml(context: Context, songId: Long, requireFresh: Boolean): String? =
        withContext(Dispatchers.IO) {
            val entry = synchronized(lock) {
                val map = loadLocked(context)
                map[songId.toString()] ?: map[ttmlKey(songId)]
            }
            ttmlOf(entry, System.currentTimeMillis(), requireFresh)
        }

    /**
     * 写入 TTML 原文并打上 [System.currentTimeMillis] 的时间戳。
     *
     * 空内容直接丢弃：宁可下次再拉一次，也不把「这次没拿到」缓存成「这首歌没有 TTML」——
     * 后者会让这首歌在 TTL 内都不再尝试。
     */
    suspend fun putTtml(context: Context, songId: Long, ttml: String) {
        if (ttml.isBlank()) return
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val map = loadLocked(context)
                val key = songId.toString()
                val now = System.currentTimeMillis()
                val existing = map[key]
                if (existing != null) {
                    map[key] = existing.copy(ttml = ttml, ttmlAt = now)
                } else {
                    // 还没有正式条目：暂存，绝不建 lrc="" 的正式条目（见 TTML_KEY_PREFIX）。
                    map[ttmlKey(songId)] = CachedLyrics("", "", now, null, ttml, now)
                }
                trimLocked(map)
                persistLocked(context, map)
            }
        }
    }

    private fun ttmlKey(songId: Long): String = TTML_KEY_PREFIX + songId

    /**
     * 纯函数：这份 TTML 是否还在 TTL 内。JVM 可单测（不碰 Android）。
     *
     * [ttmlAt] <= 0（老缓存没这个字段）一律算过期，所以不需要额外判断条目存在与否。
     * 边界：`now - ttmlAt == ttlMs` 算**过期**（TTL 是左闭右开区间），这样「正好第 7 天」
     * 会去重新拉一次，而不是把一个卡在边界上的值当成永远新鲜。
     */
    fun isTtmlFresh(ttmlAt: Long, now: Long, ttlMs: Long = TTML_TTL_MS): Boolean =
        ttmlAt > 0 && now - ttmlAt < ttlMs

    /**
     * 纯函数：这条缓存是不是**升级前写下的**（缺 v1.9.2 的 romalrc 字段）。
     *
     * 为什么需要它：LRC 条目**没有 TTL**（只有 200 条的 LRU 上限），所以 v1.9.1 及更早写下的条目
     * 会一直躺在盘上、`romalrc` 一直是 null。若照旧当命中用，音译回退对升级用户就**永远不生效** ——
     * 真机实测（PCL110，装着 v1.9.1 时期的缓存）：64 条里 63 条没有这个字段，1959528822 因此只拿到
     * TTML 的 16 行音译，而 ncm 那 41 行 romalrc 明明在服务端、却一直用不上。判据用「字段缺失」
     * 而不是「字段为空」：空串是「这首歌确实没有音译」的权威结论（v1.9.2 会写下去），缺失才是「没记过」。
     *
     * 条目为 null（本来就要打网络）返回 false。
     */
    internal fun needsRomalrcRefetch(entry: CachedLyrics?): Boolean = entry != null && entry.romalrc == null

    /**
     * 纯函数：从缓存条目里挑出 TTML 原文。
     *
     * [requireFresh] = true 走在线路径（[isTtmlFresh] 过滤）；false 走离线兜底（忽略 TTL）。
     * 空白内容一律不算命中 —— 正常写不进缓存，但半截写入/人工改坏的老数据不能当成歌词返回。
     */
    internal fun ttmlOf(entry: CachedLyrics?, now: Long, requireFresh: Boolean = true): String? {
        if (entry == null) return null
        val raw = entry.ttml ?: return null
        if (raw.isBlank()) return null
        if (requireFresh && !isTtmlFresh(entry.ttmlAt, now)) return null
        return raw
    }
}
