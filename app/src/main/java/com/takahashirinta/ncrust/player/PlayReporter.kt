//! 播放行为上报。
//!
//! 复刻官方 web 播放器的 webLog 上报:当一首歌被"听完"(自然结束或进度 ≥80%)时,
//! 向 ncm 端上报一条 play 行为,使本地收听能够反馈给推荐/指数体系。
//!
//! 链路(从 music.163.com web 播放器 JS 静态还原):
//!   POST https://clientlogusf.music.163.com/api/feedback/weblog?csrf_token=<csrf>
//!   form: logs = JSON.stringify([{ action: "play", json: {...} }])
//! 该链路不经过 eapi/weapi 加密,只做普通表单 POST。
//!
//! ⚠️ 风险提示: webLog 的精确定位(尤其是内部 useNewEncrypt 握手)无法仅凭静态 JS
//! 百分之百复现,本实现尽力贴合真实请求形态;上报本身为"尽力而为",失败绝不影响播放。

package com.takahashirinta.ncrust.player

import android.util.Log
import com.takahashirinta.ncrust.network.RetrofitClient
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

object PlayReporter {
    private const val TAG = "PlayReporter"
    private const val WEBLOG_PATH = "/api/feedback/weblog"
    private const val WEBLOG_HOST = "https://clientlogusf.music.163.com"

    /** 判定"听完"的进度阈值。 */
    private const val COMPLETION_THRESHOLD = 0.8f

    /**
     * 上报一首歌的播放行为。
     *
     * @param songId 歌曲 ID
     * @param strategy 推荐策略标识(alg),可为空
     * @param playedMs 实际播放时长(毫秒)
     * @param end 结束原因,对齐官方枚举: "playend" / "interrupt" / "ui" / "exception"
     * @param isWifi 是否 Wi-Fi
     */
    fun reportPlay(
        songId: Long,
        playedMs: Long,
        durationMs: Long,
        end: String = "playend",
        strategy: String? = null,
        isWifi: Boolean = false,
    ) {
        // ===== v2.5.5 · B：跨音源闸门（**这里是唯一的上报出口**）=====
        //
        // 闸门放在最外层，而不是在 PlayerViewModel 的两个调用点各判一次：
        //  · 调用点会变多（自然播完 / 80% 阈值 / 将来可能的 seek 上报），
        //    而「出口」只有一个 —— 判据必须挂在**不会漏**的那一层；
        //  · 判据本身是纯函数（ReportGate.mayReport），可单测；
        //    调用点重复写一遍就等于多一份会漂移的口径。
        //
        // 被拦下的次数只做一次内存自增（不碰 IO、不碰网络），
        // 落盘在 MainActivity.onStop 与诊断入口（铁律 4：非核心组件不得破坏核心播放链路）。
        val gateReason = ReportGate.blockReason(ReportGate.Target.NETEASE_WEBLOG, songId)
        if (gateReason != null) {
            ReportGateStats.counter.onBlocked(ReportGate.Target.NETEASE_WEBLOG, songId)
            Log.i(TAG, "weblog blocked: songId=$songId reason=$gateReason")
            return
        }

        val cookie = RetrofitClient.getCookie() ?: return
        if (!cookie.contains("MUSIC_U") || songId <= 0) return

        val csrf = RetrofitClient.getCsrfToken() ?: return
        val url = "$WEBLOG_HOST$WEBLOG_PATH?csrf_token=$csrf"

        val json = JSONObject()
            .put("type", "song")
            .put("wifi", if (isWifi) 0 else 1)
            .put("download", 0)
            .put("id", songId)
            .put("time", playedMs)
            .put("end", end)
            .put("mainsite", "1")
            .put("mainsiteWeb", "1")
        if (!strategy.isNullOrEmpty()) json.put("alg", strategy)

        val logs = JSONArray().put(
            JSONObject().put("action", "play").put("json", json)
        ).toString()

        // 尽力而为,失败不影响播放。
        thread(name = "ncrust-weblog") {
            try {
                ReportGateStats.counter.onReported()
                val resp = RetrofitClient.postWeblog(url, logs)
                Log.d(TAG, "weblog resp: ${resp.code} duration=${playedMs}/${durationMs}")
                resp.close()
            } catch (e: Exception) {
                Log.w(TAG, "weblog report failed", e)
            }
        }
    }

    /** 是否已达到"听完"阈值。 */
    fun reachedCompletion(positionMs: Long, durationMs: Long): Boolean {
        return durationMs > 0 && positionMs.toFloat() / durationMs.toFloat() >= COMPLETION_THRESHOLD
    }
}