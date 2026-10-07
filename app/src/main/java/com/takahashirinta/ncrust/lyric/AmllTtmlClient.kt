/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 一次 TTML 拉取的 HTTP 结果（v1.9.0）。
 *
 * [code] 是**跟随重定向之后**的最终状态码（OkHttp 默认跟 302），所以「文件不存在」看到的是 404
 * 而不是 302；[code] < 0（[AmllTtmlClient.NETWORK_ERROR]）表示网络异常，一个字节都没拿到。
 */
data class TtmlHttpResult(val code: Int, val body: String?)

/**
 * TTML 取数抽象（v1.9.0）。
 *
 * 生产实现是 [OkHttpTtmlFetcher]；单测注入假实现，**单测绝不发真实网络请求**。
 */
fun interface TtmlFetcher {
    suspend fun get(url: String): TtmlHttpResult
}

/**
 * 按 ncm songId 拉 AMLL TTML 的客户端（v1.9.0）。只打 AMLL TTML DB 的只读镜像，
 * **不发任何 ncm 请求**：不需要登录、不需要 cookie、不碰任何用户凭据。
 *
 * ## 为什么能按 songId 直接拉
 * TTML DB 的文件名就是 ncm 歌曲 ID（`ncm-lyrics/<id>.ttml`），不需要搜索接口。
 *
 * ## 镜像行为（2026-09 curl 实测，非文档推断）
 * - 主镜像**有**这首歌 → 直接 200，无重定向；
 * - 主镜像**没有** → 302 到 `/lyrics/ncm-lyrics/<id>`，跟随后 404，body 是中文「歌词不存在」
 *   ⇒ 「有没有」必须看**最终状态码 + body 是不是 TTML**，不能只看 3xx；
 * - 覆盖率实测只有约 5–10%（本机 98 首真实收藏）⇒ **404 是常态不是异常**：静默、不重试、
 *   404 走 Log.d，只有网络异常才 Log.w，避免刷屏。
 *
 * ## 回退策略（v1.9.1 修正：区分「权威镜像」与「非权威镜像」）
 *
 * 主镜像失败（网络异常 / 非 200）→ 依次试备用镜像。但**「404」的含义取决于是哪一面镜子**：
 *
 * - **权威镜像**（`amlldb` / `amlldb-alt` / `github-raw`，都是同一份官方仓库的直出）：
 *   404 就是「这首歌确实没有 TTML」⇒ 立刻收工，不再试后面的。
 * - **非权威镜像**（`jsdelivr`）：它有自己的**单包 50 MB 上限**，对**确实存在**的文件也会返回
 *   `403 Package size exceeded the configured limit of 50 MB`，也会偶发 404。
 *   ⇒ 在它上面 404/403 **不能**判「这首歌没有 TTML」，只能当「这面镜子服务不了这个文件」，
 *   **继续试下一面**。
 *
 * ⚠️ **这是 v1.9.0 的一个真实缺陷，由独立验证者发现并促成本次修正**（实测数据在
 * `tools/verify-mirror-equivalence.py`）：抽样 60 个**确认存在于 DB** 的 ID，
 * 有 4 个 ID 出现「其余三面均 200、只有 jsdelivr 拿不到」（3× 403 超限 + 1× 网络异常）。
 * v1.9.0 的旧逻辑对**任一**镜像的 404 都立即 `return null`，于是当 `amlldb` 与
 * `github-raw` 同时网络抖动、而 jsdelivr 又对存在文件报 403/404 时，**第 4 面
 * `amlldb-alt` 永远没机会被请求**，这首歌会静默丢掉 TTML。
 *
 * v1.9.0 原先写的「备用镜像与主镜像内容逐字节一致 ⇒ 继续试不可能变出歌词」只在**内容**维度
 * 成立（我自己抽的 10 首热歌确实四面全同），在**可用性**维度是错的 —— 抽样偏差让它看起来成立。
 *
 * 顺序上把 jsdelivr 排到最后：常见情形（有/没有 TTML）在第 1 面就终结，它是否抽风不影响主路径。
 */
object AmllTtmlClient {
    const val TAG = "AmllTtml"

    /** 网络异常的统一 code：没拿到任何 HTTP 响应。 */
    const val NETWORK_ERROR = -1

    /**
     * 一个镜像。[template] 里的 `%d` 是 ncm songId。
     *
     * [authoritative] = 这一面给出的 404 能不能**判定「这首歌没有 TTML」**。
     * 只有直出官方仓库的三面为 true；jsdelivr 带 50 MB 单包上限，对存在文件也会 403/404，故为 false。
     */
    data class Mirror(val name: String, val template: String, val authoritative: Boolean) {
        // 用 Locale.ROOT：本应用有 8 种语言，某些 locale 下 String.format 的 %d 会输出
        // 本地化数字（如阿拉伯-印度数字），拼进 URL 就是一个必然 404 的地址。
        fun url(songId: Long): String = String.format(Locale.ROOT, template, songId)
    }

    /**
     * 镜像表，顺序 = 回退顺序。
     *
     * 前 3 面直出同一份官方仓库（内容实测逐字节一致），**它们的 404 可信**；
     * 第 4 面 jsdelivr 只作为兜底放在最后 —— 它有 50 MB 单包上限，对**存在**的文件也会
     * `403 Package size exceeded`（实测抽样 60 个 DB 内 ID 有 4 个如此），
     * 所以既不能让它靠前抢答，也不能信它的 404。
     */
    val mirrors: List<Mirror> = listOf(
        Mirror("amlldb", "https://amlldb.bikonoo.com/ncm-lyrics/%d.ttml", authoritative = true),
        Mirror("github-raw", "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/ncm-lyrics/%d.ttml", authoritative = true),
        Mirror("amlldb-alt", "https://amlldb.bikonoo.com/lyrics/ncm-lyrics/%d.ttml", authoritative = true),
        // 非权威：仅兜底。它的 403/404 一律当「这面镜子服务不了」处理，继续往后（后面已没有，
        // 但语义必须写对，否则以后在它后面再加镜像会重新踩坑）。
        Mirror("jsdelivr", "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/ncm-lyrics/%d.ttml", authoritative = false),
    )

    /** 该曲在四面镜像上的 URL（顺序同 [mirrors]）。公开给调用方与排查用。 */
    fun urlsFor(songId: Long): List<String> = mirrors.map { it.url(songId) }

    /**
     * TTML 的根元素恒为 `<tt`；去掉前导空白后拿它当「这份 body 到底是不是 TTML」的判据。
     *
     * 为什么需要它：状态码不足以证明拿到了歌词 —— CDN/代理层完全可能用 200 返回一个 HTML
     * 错误页。脏数据不放行，解析器和缓存就不会被污染。
     */
    private fun looksLikeTtml(body: String?): Boolean =
        body != null && body.trimStart().startsWith("<tt")

    /**
     * 拉一份 TTML 原文；「这首歌没有」与「全部镜像不可用」都返回 null（调用方只需回退，
     * 不必区分 —— 对播放路径来说两者等价）。
     *
     * 只有网络异常/非 200 才换镜像；某面镜子一旦给出确定性答复（404，或 200 但不是 TTML），
     * 立即收工。
     */
    suspend fun fetch(songId: Long, fetcher: TtmlFetcher = OkHttpTtmlFetcher): String? {
        var lastCode = NETWORK_ERROR
        for (mirror in mirrors) {
            val result = try {
                fetcher.get(mirror.url(songId))
            } catch (ce: CancellationException) {
                // 协程取消必须原样抛出：吞掉它会让「用户已经切歌」的那次预取把剩下三面镜子跑完。
                throw ce
            } catch (t: Throwable) {
                // 取数实现抛出的任何异常都等价于「这面镜子网络不可用」，换下一面。
                TtmlHttpResult(NETWORK_ERROR, null)
            }
            lastCode = result.code
            val body = result.body
            when {
                result.code == 200 && body != null && looksLikeTtml(body) -> {
                    Log.i(TAG, "命中 songId=$songId mirror=${mirror.name} bytes=${body.length}")
                    return body
                }
                // 「这首歌没有 TTML」是一个**只能由权威镜像下**的结论：非权威镜像（jsdelivr）
                // 对确实存在的文件也会 404/403，信它就会让后面的镜像失去机会（v1.9.0 的真实缺陷）。
                (result.code == 404 || result.code in 200..299) && mirror.authoritative -> {
                    Log.d(
                        TAG,
                        "无此歌词 songId=$songId mirror=${mirror.name} code=${result.code}" +
                            if (result.code in 200..299) " 非TTML" else ""
                    )
                    return null
                }
                // 非权威镜像的 404 / 200-非TTML：只当「这面服务不了这个文件」，换下一面。
                result.code == 404 || result.code in 200..299 ->
                    Log.d(TAG, "镜像不可用 songId=$songId mirror=${mirror.name} code=${result.code} 非权威，继续")
                else -> Log.d(TAG, "镜像失败 songId=$songId mirror=${mirror.name} code=${result.code}")
            }
        }
        // 每次拉取最多一行告警：整首收藏里 90%+ 都是 404（上面已提前返回），
        // 剩下的才可能是网络问题 —— 逐镜像打警告会在弱网下刷屏。
        if (lastCode < 0) {
            Log.w(TAG, "全部镜像网络异常 songId=$songId lastCode=$lastCode")
        } else {
            Log.d(TAG, "全部镜像未命中 songId=$songId lastCode=$lastCode")
        }
        return null
    }

    /**
     * 播放路径取 TTML：TTL 内命中缓存就直接返回（预取过的歌零网络），否则拉网络并落缓存。
     *
     * **无网/全部镜像不可用时返回 null**。需要「用旧数据兜底」的调用方请再调
     * [LyricsCache.getTtmlStale] —— 这里刻意不自动兜底：要不要用过期数据是歌词源链的策略，
     * 藏在取数客户端里会让上层无法表达「离线优先」与「只在网络失败时兜底」的区别。
     */
    suspend fun load(context: Context, songId: Long, fetcher: TtmlFetcher = OkHttpTtmlFetcher): String? {
        LyricsCache.getTtml(context, songId)?.let { return it }
        val body = fetch(songId, fetcher) ?: return null
        LyricsCache.putTtml(context, songId, body)
        return body
    }

    /**
     * 预取一首歌的 TTML（T4 在「当前歌播放时预取下一首」调用）：同样是「取数 + 落缓存」，
     * 但**静默** —— 失败不抛、不返回结果，对播放路径零影响。
     *
     * 已经新鲜就直接返回、不打网络：同一首歌在一轮播放里会被预取多次（切歌瞬间 + 播放到一半），
     * 这里必须幂等，否则每切一次歌就多打一轮镜像。
     */
    suspend fun prefetch(context: Context, songId: Long, fetcher: TtmlFetcher = OkHttpTtmlFetcher) {
        try {
            if (LyricsCache.getTtml(context, songId) != null) return
            val body = fetch(songId, fetcher) ?: return
            LyricsCache.putTtml(context, songId, body)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.d(TAG, "预取失败 songId=$songId ${t.javaClass.simpleName}")
        }
    }
}

/**
 * 生产取数实现（v1.9.0）：OkHttp，**短超时**。
 *
 * 为什么不复用 [com.takahashirinta.ncrust.network.RetrofitClient] 的 client：它的 `plainClient`
 * 是 private，没有公开访问器（改它不在本次改动范围内）；而且它的超时是 30s/30s，对「只是补充源」
 * 的 TTML 太长 —— 拿不到必须立刻回退，不能让用户等。
 *
 * 这里从 OkHttp 默认实例 `newBuilder()` 派生一次并常驻（[client] 是进程内单例），
 * 连接池/线程池随之复用；每次拉取不再新建 client，也**不改动任何共享实例**。
 */
object OkHttpTtmlFetcher : TtmlFetcher {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val client: OkHttpClient by lazy {
        OkHttpClient().newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun get(url: String): TtmlHttpResult = withContext(Dispatchers.IO) {
        try {
            client.newCall(
                Request.Builder().url(url).header("User-Agent", UA).build()
            ).execute().use { resp ->
                TtmlHttpResult(resp.code, resp.body?.string())
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // 网络异常统一映射成负 code：调用方据此换镜像，不需要区分异常类型。
            TtmlHttpResult(AmllTtmlClient.NETWORK_ERROR, null)
        }
    }
}
