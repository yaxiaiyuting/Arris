/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.4.0 · D：ncm 的**批量版权可用性探测**。
 */

package com.takahashirinta.ncrust.network

import android.util.Log
import com.takahashirinta.ncrust.search.TrackAvailability
import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey
import com.takahashirinta.ncrust.source.musicSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * ncm 的批量可播放性探测（v2.4.0 · D）。
 *
 * ## 为什么必须有这个类（探针直接逼出来的）
 *
 * v2.3.0 的版权判据（`TrackAvailability.ofNetease`）读的是 `SongItem.privilege`，
 * 而**只有搜索接口会带这个字段**。专辑页的 `GET /api/v1/album/{id}` 与
 * 艺人页的 `GET /api/artist/albums/{id}` **都不返回 `privilege`**：
 *
 * - 专辑页的 `songs[]` 里只有 `st` / `fee`，而实测 `st` 在专辑页**恒为 `-1`** ——
 *   `-1` 在 v2.3.0 的判据里是「不可用」（15/30 可播），所以它**一个字节的信息都没有**；
 * - 于是「周杰伦的专辑点进去，11 首全部看起来一模一样」，用户只能点进去才发现播不了。
 *
 * 修法就是这里：**另发一次批量详情**，把 `privileges[]` 拿回来。
 * 一次请求覆盖 100 首，实测 ~0.3–0.6s（`probe-copyright-field.md` C2），
 * 与页面主请求并行发出的成本可以接受。
 *
 * ## 契约
 *
 * 1. **绝不抛异常**：失败返回空 Map，调用方把整批留在 `UNKNOWN`（什么都不显示）；
 * 2. **只读、不改 `SongItem`**：返回 `TrackKey → TrackAvailability`，
 *    可用性是「与账号和时刻绑定的临时事实」，**不能写回落盘结构**（见 `AggregatedSong` 的 KDoc）；
 * 3. **判据复用 `TrackAvailability.ofNetease`**：本类不自己判定，
 *    只负责把字段取回来 —— 判定逻辑在 v2.3.0 已经定死并有 20 条单测。
 */
object NeteaseAvailabilityApi {

    private const val TAG = "NeteaseAvail"

    /** 单次请求的曲目上限。实测 100 有效（探针按 100 分批，未遇到失败）。 */
    internal const val BATCH = 100

    /** 一次探测的总上限。一屏 3 批 = 300 首，超过的部分不再探（留在 UNKNOWN）。 */
    internal const val MAX_TOTAL = 300

    /**
     * 批量探测。
     *
     * @return `TrackKey → TrackAvailability`。**缺席 ≠ 不可播**：
     *   没探到的曲目不会出现在 Map 里，调用方用 `UNKNOWN` 兜底。
     */
    suspend fun probeAvailability(songs: List<SongItem>): Map<TrackKey, TrackAvailability> =
        withContext(Dispatchers.IO) {
            val targets = songs.asSequence()
                .filter { it.musicSource == MusicSource.NETEASE }
                .filter { it.id > 0L }
                .distinctBy { TrackKey.fromSong(it) }
                .take(MAX_TOTAL)
                .toList()
            if (targets.isEmpty()) return@withContext emptyMap()

            val out = HashMap<TrackKey, TrackAvailability>(targets.size)
            for (batch in targets.chunked(BATCH)) {
                val rows = runCatching { fetchPrivileges(batch.map { it.id }) }
                    .onFailure { Log.w(TAG, "probeAvailability batch failed", it) }
                    .getOrDefault(emptyMap())
                for (song in batch) {
                    val row = rows[song.id] ?: continue
                    out[TrackKey.fromSong(song)] = TrackAvailability.ofNetease(
                        privilegeSt = row.st,
                        playableBr = row.pl,
                        noCopyright = row.noCopyright,
                        // ★ 用**批量详情**里的 `fee`，不是列表里那个。
                        //   探针实测两者会不一致：Taylor Swift 那批专辑页 `fee=1`、
                        //   详情页 `fee=1` 但服务端只给 30 秒试听 —— 而这个差别正是
                        //   「需会员」与「可播放」的分界。列表里的 `fee` 是「播放不需要付费」
                        //   的宽泛声明，详情里的才是逐曲事实。
                        fee = row.fee,
                    )
                }
            }
            out
        }

    /** 一次批量详情，返回 `songId → 版权行`。 */
    private suspend fun fetchPrivileges(ids: List<Long>): Map<Long, PrivilegeRow> {
        val cArray = JSONArray()
        ids.forEach { id -> cArray.put(JSONObject().put("id", id)) }
        val response = RetrofitClient.eapiPost("/eapi/v3/song/detail", mapOf("c" to cArray.toString()))
        val body = response.body?.string() ?: return emptyMap()
        val json = JSONObject(body)
        if (json.optInt("code", 0) != 200) return emptyMap()

        // `privileges[]` 是权威；`songs[].noCopyrightRcmd` 是补充的显式声明。
        val noCopyrightIds = HashSet<Long>()
        json.optJSONArray("songs")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONObject(i) ?: continue
                if (s.optJSONObject("noCopyrightRcmd") != null) noCopyrightIds.add(s.optLong("id"))
            }
        }

        val out = HashMap<Long, PrivilegeRow>()
        json.optJSONArray("privileges")?.let { arr ->
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val id = p.optLong("id", 0L)
                if (id <= 0L) continue
                out[id] = PrivilegeRow(
                    st = p.optIntOrNull("st"),
                    pl = p.optIntOrNull("pl"),
                    fee = p.optIntOrNull("fee"),
                    noCopyright = if (noCopyrightIds.contains(id)) true else null,
                )
            }
        }
        return out
    }

    /**
     * `optInt` 在字段缺失时返回默认值，而这里的「字段缺失」与「显式 0」
     * **语义完全不同**（缺失 ⇒ `UNKNOWN`，0 ⇒ 参与判定），所以必须显式区分。
     */
    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (has(key) && !isNull(key)) optInt(key) else null

    /** 一行版权数据。字段全可空 —— null 表示「服务端没给」，不是 0。 */
    internal data class PrivilegeRow(
        val st: Int?,
        val pl: Int?,
        val fee: Int?,
        val noCopyright: Boolean?,
    )
}
