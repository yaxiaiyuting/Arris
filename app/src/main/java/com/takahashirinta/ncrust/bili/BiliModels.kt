/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · B：B 站音源的解析层。**纯逻辑（只依赖 org.json），JVM 可单测。**
 */

package com.takahashirinta.ncrust.bili

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.SourceIds
import org.json.JSONObject
import java.net.URLDecoder

/**
 * 一条 B 站音轨（v3.1.0 · B）。
 *
 * ## 为什么一个类型要同时装两种东西
 *
 * B 站有**两个**能出声的地方，而它们的发现方式与元数据形状完全不同：
 *
 * | | 音频区（`au`） | 视频音轨（`BV`） |
 * |---|---|---|
 * | 能否按关键词搜到 | **不能**（实测无搜索接口，见 [BiliApi] 的 KDoc） | 能（Wbi 签名搜索） |
 * | 元数据 | `song/info`：标题 / UP 主 / 封面 / **LRC 歌词** / 时长 | 搜索项：标题 / UP 主 / 封面 / 时长（`m:ss`） |
 * | 音频流 | `song/url`：192K `m4a` | `playurl` 的 DASH `audio[]` |
 * | 歌词 | **有**（`lyric` 字段） | 没有（音乐区视频基本不带字幕） |
 *
 * 所以「搜索 → 播放 → 歌词」这条链在 B 站上**不可能只走一条腿**：
 * 只有视频能被搜到，只有音频区有歌词。本类型把两者统一成一种「音轨」，
 * 由 [auid] 是否为 null 区分，取链与取词各自分派。
 *
 * @property auid 音频区曲目 id；视频音轨为 null。
 * @property bvid 视频号；音频区曲目为 null（音频区自己的 `bvid` 字段常为空串）。
 * @property aid 视频的 av 号（数字）。
 * @property cid 视频分 P id —— `playurl` 必需，搜索结果里**没有**，要单独问 `view`。
 * @property lyric ⚠️ **音频区 `song/info` 的原始 `lyric` 字段**：实测它通常是
 *   **一个 LRC 文件的 URL**（`http://i0.hdslb.com/bfs/music/149994607539.lrc`），
 *   而不是 LRC 正文，而且大部分曲目是空串。**取词请走 [BiliApi.audioLyric]**
 *   （`/audio/music-service-c/web/song/lyric`），那里的 `data` 才是正文。
 *   本字段保留原样是为了让「服务端到底给了什么」不被二次加工掉。
 *   视频音轨为 null（**不是空串**：「没有这个数据源」与「这首歌没有歌词」是两件事）。
 */
data class BiliTrack(
    val auid: Long? = null,
    val bvid: String? = null,
    val aid: Long? = null,
    val cid: Long? = null,
    val title: String,
    val author: String,
    val coverUrl: String,
    val durationMs: Long,
    val lyric: String? = null,
) {

    /** 是不是音频区曲目。取链与取词的分派判据，只此一处。 */
    val isAudioZone: Boolean get() = auid != null

    /**
     * 本应用内部使用的数字 id。
     *
     * 两条腿共用一个标志位（[SourceIds.BILI_ID_FLAG]），所以「音频区 2478206」
     * 与「视频 av2478206」会撞成同一个 id —— 这**不会**造成串台，因为
     * 取链分派看的是 [sourceId] 而不是 id。但它意味着**不该**用 id 去区分这两条腿。
     * 返回 0 表示这个 id 造不出来（见 [SourceIds.biliId]），调用方应当整条丢掉。
     */
    val numericId: Long get() = SourceIds.biliId(auid ?: aid ?: 0L)

    /**
     * 取链用的载荷（进 [SongItem.sourceId]）。
     *
     * 形状是 `au:<auid>` / `bv:<bvid>:<cid>`：`MusicSourceProvider.isResolvable` 只要求它非空，
     * 而取链那头按前缀分派。用**带前缀的字符串**而不是裸数字，是为了让
     * 「这条腿是哪条」在队列持久化之后仍然可判（id 里分不出来，见 [numericId]）。
     */
    val sourceId: String?
        get() = when {
            auid != null -> "au:$auid"
            !bvid.isNullOrEmpty() -> "bv:$bvid:${cid ?: 0L}"
            else -> null
        }

    /**
     * 转成本应用的 [SongItem]。
     *
     * `id <= 0` 时返回 null（[SourceIds.biliId] 拒绝造一个假 id）——
     * 调用方据此把这条结果整个丢掉，而不是塞一个必然取不到链的条目进队列。
     */
    fun toSongItem(): SongItem? {
        val id = numericId
        if (id <= 0L) return null
        return SongItem(
            id = id,
            name = title,
            artists = listOf(ArtistItem(id = null, name = author.ifBlank { "Bilibili" })),
            album = AlbumItem(id = null, name = null, picUrl = coverUrl.ifBlank { null }),
            duration = durationMs.takeIf { it > 0L },
            source = MusicSource.BILIBILI.key,
            sourceId = sourceId,
            mediaId = null,
        )
    }

    companion object {
        /** 从 `au:<id>` / `bv:<bvid>:<cid>` 解析出载荷。解析不出返回 null（不猜）。 */
        fun parseSourceId(sourceId: String?): Payload? {
            val v = sourceId?.trim().orEmpty()
            if (v.isEmpty()) return null
            return when {
                v.startsWith("au:") -> v.removePrefix("au:").toLongOrNull()
                    ?.takeIf { it > 0L }?.let { Payload(auid = it) }

                v.startsWith("bv:") -> {
                    val parts = v.removePrefix("bv:").split(':')
                    val bvid = parts.getOrNull(0)?.takeIf { it.isNotBlank() }
                    val cid = parts.getOrNull(1)?.toLongOrNull() ?: 0L
                    bvid?.let { Payload(bvid = it, cid = cid.takeIf { c -> c > 0L }) }
                }

                else -> null
            }
        }
    }

    /** [parseSourceId] 的产物：两条腿各自的载荷。 */
    data class Payload(val auid: Long? = null, val bvid: String? = null, val cid: Long? = null) {
        val isAudioZone: Boolean get() = auid != null
    }
}

/**
 * 一条可播放的音频流（v3.1.0 · B）。
 *
 * @property url 直链。**带签名，会过期。**
 * @property expiresAtMs 绝对过期时刻（`System.currentTimeMillis()` 口径），
 *   由 URL 自带的 `deadline` 参数反推（见 [BiliParse.expiryFromUrl]）。
 *   铁律 26：B 站音频流有时效，必须处理 TTL 和过期刷新 —— 这个字段就是它的载体。
 * @property qualityLabel 实际拿到的档位文案（服务端说了算，不是我们请求的那一档）。
 * @property br 码率（服务端给多少算多少，拿不到填 0）。
 * @property container 容器后缀（`m4a` / `mp3` / `flac`…），只用于诊断与角标。
 */
data class BiliStream(
    val url: String,
    val expiresAtMs: Long,
    val qualityLabel: String,
    val br: Long,
    val container: String,
)

/**
 * B 站响应的解析（v3.1.0 · B）。**全部是纯函数**，入参是原始 JSON 字符串。
 *
 * 为什么单独成对象：这些解析规则是「协议知识」，而协议知识最容易在真机上
 * 因为一个字段缺失而静默降级。抽出来之后 `BiliParseTest` 可以用**真实响应样本**
 * （见 `docs/verification/v3.1.0/bili-research/EVIDENCE.md`）逐字段断言，
 * 不需要网络也不需要设备。
 */
object BiliParse {

    /**
     * 搜索项标题里的高亮标签（`<em class="keyword">…</em>`）。
     *
     * B 站的搜索接口**在标题里塞 HTML**，不清掉的话列表上会原样显示尖括号。
     * 实测样本：`mmd<em class="keyword">初音未来</em>VS德莱文和黑岩`。
     */
    private val EM_TAG = Regex("</?em[^>]*>", RegexOption.IGNORE_CASE)

    /** 其余 HTML 标签（`<br>` 之类），一并去掉。 */
    private val ANY_TAG = Regex("<[^>]+>")

    /** 标题清洗：去高亮标签 → 去其它标签 → 反转义基本实体 → trim。 */
    fun cleanTitle(raw: String?): String {
        val noEm = EM_TAG.replace(raw.orEmpty(), "")
        val noTag = ANY_TAG.replace(noEm, "")
        return noTag
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&#39;", "'")
            .trim()
    }

    /**
     * `duration` 解析。**两种形状都要认**：
     * - 视频搜索给的是 `"4:03"` / `"1:03:22"`（冒号分隔的字符串）；
     * - 音频区给的是 `112`（秒，数字）。
     *
     * 认不出返回 0（调用方据此不显示时长，而不是显示 `0:00`）。
     */
    fun parseDuration(raw: Any?): Long {
        val text = raw?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return 0L
        if (text.contains(':')) {
            val parts = text.split(':')
            var acc = 0L
            for (p in parts) {
                val v = p.trim().toLongOrNull() ?: return 0L
                acc = acc * 60L + v
            }
            return acc * 1000L
        }
        return text.toLongOrNull()?.takeIf { it > 0L }?.let { it * 1000L } ?: 0L
    }

    /**
     * `//i2.hdslb.com/bfs/archive/xxx.jpg` → `https://i2.hdslb.com/...`。
     *
     * 搜索接口给的封面是**协议相对 URL**（以 `//` 开头）。Coil 能处理，
     * 但队列持久化之后再读出来就未必了 —— 补全成 `https:` 是零成本的确定性。
     */
    fun normalizeCover(raw: String?): String {
        val v = raw?.trim().orEmpty()
        return when {
            v.isEmpty() -> ""
            v.startsWith("//") -> "https:$v"
            v.startsWith("http://") -> "https://" + v.removePrefix("http://")
            else -> v
        }
    }

    /**
     * 从**带签名的 CDN URL** 里解析出绝对过期时刻。铁律 26 的落点。
     *
     * ## 为什么不能直接用响应里的 `timeout`
     *
     * 音频区实测（`docs/verification/v3.1.0/bili-research/EVIDENCE.md`）：
     *
     * ```
     * timeout = 10800        （响应字段，3 小时）
     * deadline = now + 7200  （URL 的 query 参数，2 小时）
     * ```
     *
     * **两者不一致，且 URL 自己那个更保守。** 按 `timeout` 缓存会让最后 1 小时的条目
     * 「看起来新鲜、实际已经 403」—— 用户听到的是「播到一半失败」。
     * 所以：**优先信 `deadline`，`timeout` 只作为它缺失时的兜底**，
     * 并且两边都再减去一个 [SAFETY_MARGIN_MS]（时钟偏差 + 请求在途时间）。
     *
     * @param nowMs 当前时刻；显式传入是为了单测能钉死一条固定向量。
     * @param timeoutSeconds 响应里的 `timeout`；null / <= 0 时用 [DEFAULT_TTL_MS]。
     */
    fun expiryFromUrl(
        url: String,
        timeoutSeconds: Long?,
        nowMs: Long = System.currentTimeMillis(),
    ): Long {
        val deadlineSec = deadlineOf(url)
        if (deadlineSec != null && deadlineSec > 0L) {
            val at = deadlineSec * 1000L - SAFETY_MARGIN_MS
            // deadline 已经过去（或离过去不到安全边距）⇒ 返回一个**已过期**的时刻，
            // 让上层的 TTL 判据把它当废条目丢掉。绝不在这里「向上取一个最小值」——
            // 那等于把一条已死的 URL 说成还能用 5 分钟。
            return at
        }
        val ttlMs = (timeoutSeconds?.takeIf { it > 0L }?.times(1000L)) ?: DEFAULT_TTL_MS
        return nowMs + ttlMs - SAFETY_MARGIN_MS
    }

    /**
     * 从 URL 的 query 里取 `deadline`（unix 秒）。取不到返回 null。
     *
     * ⚠️ **先整体解码、再按 `&` 切**，不是反过来：B 站的 CDN 直链在 JSON 里是
     * `\u0026`、在别处可能被再转义成 `%26` —— 也就是说「分隔符本身」有可能是转义过的。
     * 先切后解码的话，`e=1%26deadline=123` 会被切成一整段（key 是 `e`），
     * deadline 就此丢掉 —— 而丢掉它的后果是**退回 3 小时的 timeout**，
     * 也就是拿到一条 1 小时后就会 403 的 URL（铁律 26 要防的正是这个）。
     */
    fun deadlineOf(url: String): Long? {
        val idx = url.indexOf('?')
        if (idx < 0 || idx == url.length - 1) return null
        val rawQuery = url.substring(idx + 1)
        val query = runCatching { URLDecoder.decode(rawQuery, "UTF-8") }.getOrDefault(rawQuery)
        for (pair in query.split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) != "deadline") continue
            return pair.substring(eq + 1).takeWhile { it in '0'..'9' }.toLongOrNull()
        }
        return null
    }

    /** 安全边距：URL 过期前 60 秒就不再使用它。 */
    const val SAFETY_MARGIN_MS: Long = 60_000L

    /** `timeout` 缺失时的兜底 TTL（30 分钟）。**故意比服务端的 3 小时保守得多。** */
    const val DEFAULT_TTL_MS: Long = 30 * 60 * 1000L

    /**
     * `nav` → `(img_url, sub_url)`。任何一个缺失返回 null。
     *
     * 注意 [BiliWbi.mixinKey] 还要再做一次长度校验 —— 这一层只管「字段在不在」。
     */
    fun parseNavWbiKeys(body: String?): Pair<String, String>? {
        val data = runCatching { JSONObject(body.orEmpty()).optJSONObject("data") }.getOrNull() ?: return null
        val wbi = data.optJSONObject("wbi_img") ?: return null
        val img = wbi.optString("img_url").takeIf { it.isNotBlank() } ?: return null
        val sub = wbi.optString("sub_url").takeIf { it.isNotBlank() } ?: return null
        return img to sub
    }

    /**
     * 搜索响应 → 音轨列表。
     *
     * 只认 `type == "video"` 的条目：实测同一个响应里还会混进 `ketang`（课堂）等类型，
     * 而它们既没有 `bvid` 也没有可提取的音轨。**不认识的类型直接丢**，
     * 不做「猜一个 id 出来试试」—— 那种做法在列表里表现为若干条点了没反应的死条目。
     */
    fun parseSearchTracks(body: String?, limit: Int): List<BiliTrack> {
        val root = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return emptyList()
        val result = root.optJSONObject("data")?.optJSONArray("result") ?: return emptyList()
        val out = ArrayList<BiliTrack>(minOf(limit, result.length()))
        for (i in 0 until result.length()) {
            if (out.size >= limit) break
            val item = result.optJSONObject(i) ?: continue
            if (item.optString("type") != "video") continue
            val bvid = item.optString("bvid").takeIf { it.isNotBlank() } ?: continue
            val aid = item.optLong("aid", 0L).takeIf { it > 0L } ?: continue
            out += BiliTrack(
                bvid = bvid,
                aid = aid,
                title = cleanTitle(item.optString("title")),
                author = cleanTitle(item.optString("author")),
                coverUrl = normalizeCover(item.optString("pic")),
                durationMs = parseDuration(item.opt("duration")),
            )
        }
        return out
    }

    /** `view` 响应 → `cid`（`playurl` 必需）。取不到返回 null。 */
    fun parseCid(body: String?): Long? {
        val data = runCatching { JSONObject(body.orEmpty()).optJSONObject("data") }.getOrNull() ?: return null
        val cid = data.optLong("cid", 0L)
        if (cid > 0L) return cid
        // 多 P 视频：`pages[0].cid`。搜索/播放只取第一 P（音乐区视频基本是单 P）。
        val pages = data.optJSONArray("pages") ?: return null
        val first = pages.optJSONObject(0) ?: return null
        return first.optLong("cid", 0L).takeIf { it > 0L }
    }

    /**
     * `playurl`（DASH）→ 带宽最高的那条**音频**流。
     *
     * 只取 `dash.audio[]`，**永远不碰 `dash.video[]`**：本应用没有视频渲染面，
     * 拿到视频流只会白费流量。`baseUrl` 缺失时退 `backupUrl[0]`。
     */
    fun parseDashAudio(body: String?, nowMs: Long = System.currentTimeMillis()): BiliStream? {
        val root = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return null
        val dash = root.optJSONObject("data")?.optJSONObject("dash") ?: return null
        val audios = dash.optJSONArray("audio") ?: return null
        var best: JSONObject? = null
        var bestBw = -1L
        for (i in 0 until audios.length()) {
            val a = audios.optJSONObject(i) ?: continue
            val bw = a.optLong("bandwidth", 0L)
            if (bw > bestBw) {
                bestBw = bw
                best = a
            }
        }
        val picked = best ?: return null
        val url = picked.optString("baseUrl").takeIf { it.isNotBlank() }
            ?: picked.optJSONArray("backupUrl")?.optString(0)?.takeIf { it.isNotBlank() }
            ?: return null
        val mime = picked.optString("mimeType") // audio/mp4
        val codecs = picked.optString("codecs")  // mp4a.40.2 / flac
        return BiliStream(
            url = url,
            expiresAtMs = expiryFromUrl(url, timeoutSeconds = null, nowMs = nowMs),
            qualityLabel = qualityLabelOf(codecs, bestBw),
            br = bestBw,
            container = when {
                mime.contains("flac", true) || codecs.contains("flac", true) -> "flac"
                mime.contains("mp4", true) -> "m4a"
                else -> "m4a"
            },
        )
    }

    /** `song/info` 响应 → 音轨（含 LRC 歌词）。`code != 0` 或没有 id 返回 null。 */
    fun parseAudioInfo(body: String?): BiliTrack? {
        val root = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return null
        if (root.optInt("code", -1) != 0) return null
        val data = root.optJSONObject("data") ?: return null
        val auid = data.optLong("id", 0L).takeIf { it > 0L } ?: return null
        return BiliTrack(
            auid = auid,
            title = cleanTitle(data.optString("title")),
            // 音频区给了两个名字：`author`（曲目作者串，可能多人）与 `uname`（UP 主）。
            // 优先 `author`（更接近「歌手」语义），空则退 `uname`。
            author = cleanTitle(
                data.optString("author").takeIf { it.isNotBlank() } ?: data.optString("uname")
            ),
            coverUrl = normalizeCover(data.optString("cover")),
            durationMs = parseDuration(data.opt("duration")),
            // 只有响应里**真的有** `lyric` 这个键时才给值（哪怕是空串）：空串的语义是
            // 「这首歌确实没有歌词」，而 null 的语义是「这个数据源没有歌词这一项」。
            // 两者在 UI 上都是「暂无歌词」，但在缓存与重试判据上不是一回事。
            lyric = if (data.has("lyric")) data.optString("lyric") else null,
        )
    }

    /**
     * `/audio/music-service-c/web/song/lyric` 响应 → **LRC 正文**。
     *
     * 实测响应：`{"code":0,"msg":"success","data":"[00:33.26]让我掉下眼泪的\n…"}`。
     *
     * 判据与 [BiliTrack.lyric] 的两义性保持一致：
     * - `code != 0` 或没有 `data` 键 ⇒ `null`（**没有这个数据源**）；
     * - `data` 是空串 ⇒ `""`（**这首歌确实没有歌词**）。
     *
     * ⚠️ `data` 也可能是一个 **URL**（个别曲目服务端直接给了 `.lrc` 链接）。
     * 那**不是**正文，本函数如实返回它 —— 由调用方决定要不要拉。
     * 之所以不在这里顺手拉一次：那会让「解析函数」变成「会发网络的函数」，
     * 而本对象的全部价值就是它能在 JVM 里被无网络地断言。
     */
    fun parseAudioLyric(body: String?): String? {
        val root = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return null
        if (root.optInt("code", -1) != 0) return null
        if (!root.has("data")) return null
        return root.optString("data")
    }

    /**
     * `player/wbi/v2` 响应 → **第一条可用字幕的 URL**（v3.3.0）。
     *
     * 实测响应形状（登录态，`BV1cN4y167BJ`，`aid=877869754 cid=1381738635`）：
     * ```json
     * { "code":0, "data": { "subtitle": { "subtitles": [
     *     { "id":1387771279457024256, "lan":"ai-zh", "lan_doc":"中文", "is_lock":false,
     *       "subtitle_url":"//aisubtitle.hdslb.com/bfs/ai_subtitle/prod/…?auth_key=…",
     *       "subtitle_url_v2":"//subtitle.bilibili.com/…" } ] } } }
     * ```
     *
     * 选哪一条：
     * - **优先中文**（`lan` 含 `zh`，实测值是 `ai-zh`）。选到英文字幕会拿到一份
     *   逐句英译而不是歌词正文；
     * - 没有中文就取**第一条有 URL 的**（有总比没有好，且 [BiliSubtitle] 的 `music`
     *   判据会独立判断它到底是不是歌词 —— 判定与选取是两件事）；
     * - **用 `subtitle_url`，不用 `subtitle_url_v2`**：后者实测是
     *   `subtitle.bilibili.com` 上一串百分号转义的非 JSON 载荷，形状未经证实。
     *   拿没验证过的字段去赌，就是把「未验证」写成「已实现」。
     *
     * 不按 `is_lock` 过滤：实测它为 `false`，而「锁定」与「能否下载」的关系
     * **没有实测依据**，按未证实字段过滤会静默丢字幕。
     *
     * @return 原始 URL（可能是 `//` 开头，协议补全由 `BiliApi.normalizeSubtitleUrl` 负责）；
     *   无字幕 / `code != 0` / 解析失败一律返回 null。
     */
    fun parseFirstSubtitleUrl(body: String?): String? {
        val root = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return null
        if (root.optInt("code", -1) != 0) return null
        val arr = root.optJSONObject("data")
            ?.optJSONObject("subtitle")
            ?.optJSONArray("subtitles")
            ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val url = o.optString("subtitle_url").takeIf { it.isNotBlank() } ?: continue
            if (o.optString("lan").lowercase().contains("zh")) return url
        }
        for (i in 0 until arr.length()) {
            val url = arr.optJSONObject(i)?.optString("subtitle_url")?.takeIf { it.isNotBlank() }
            if (url != null) return url
        }
        return null
    }

    /**
     * 这个「歌词值」其实是一个 **LRC 文件的 URL**（而不是 LRC 正文）。
     *
     * `song/info` 的 `lyric` 字段实测就是这种形状（见 [BiliApi.audioLyric] 的说明）。
     * 判据写成纯函数是为了让「拿 URL 当正文解析」这条错路有一个**可断言的守卫**：
     * `BiliParseTest` 用它证明实现不会把 URL 喂给 `LrcParser`。
     */
    fun looksLikeUrl(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        return v.startsWith("http://") || v.startsWith("https://") || v.startsWith("//")
    }

    /**
     * `song/url` 响应 → 音频流。
     *
     * `cdns[]` 是候选直链数组，取第一条（社区实现一致的做法；它们是同一份文件的镜像）。
     * `timeout` 只作为 `deadline` 缺失时的兜底 —— 见 [expiryFromUrl] 的实测说明。
     */
    fun parseAudioStream(body: String?, nowMs: Long = System.currentTimeMillis()): BiliStream? {
        val root = runCatching { JSONObject(body.orEmpty()) }.getOrNull() ?: return null
        if (root.optInt("code", -1) != 0) return null
        val data = root.optJSONObject("data") ?: return null
        val cdns = data.optJSONArray("cdns") ?: return null
        var url: String? = null
        for (i in 0 until cdns.length()) {
            val u = cdns.optString(i).takeIf { it.isNotBlank() }
            if (u != null) {
                url = u
                break
            }
        }
        val picked = url ?: return null
        val timeout = data.optLong("timeout", 0L).takeIf { it > 0L }
        return BiliStream(
            url = picked,
            expiresAtMs = expiryFromUrl(picked, timeout, nowMs),
            qualityLabel = qualityLabelOfFileName(picked),
            br = data.optLong("size", 0L),
            container = fileNameExtension(picked),
        )
    }

    /** 从文件名反推档位（`...-192k.m4a` → `192K`）。反推不出时给 `未知`。 */
    fun qualityLabelOfFileName(url: String): String {
        val name = url.substringBefore('?').substringAfterLast('/')
        val m = Regex("-(\\d+)k\\.", RegexOption.IGNORE_CASE).find(name)
        if (m != null) return "${m.groupValues[1]}K"
        val ext = fileNameExtension(url)
        return if (ext == "flac") "FLAC" else "未知"
    }

    /** 容器/扩展名（小写，无点）。取不到返回 `m4a`。 */
    fun fileNameExtension(url: String): String {
        val name = url.substringBefore('?').substringAfterLast('/')
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return "m4a"
        return name.substring(dot + 1).lowercase()
    }

    /** DASH 音频流的档位文案：按带宽与编码猜一个**保守**的说法。 */
    private fun qualityLabelOf(codecs: String, bandwidth: Long): String = when {
        codecs.contains("flac", true) -> "FLAC"
        bandwidth >= 256_000 -> "320K"
        bandwidth >= 160_000 -> "192K"
        bandwidth >= 96_000 -> "128K"
        else -> "未知"
    }
}
