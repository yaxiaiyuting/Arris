package com.takahashirinta.ncrust.player

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioManager
import android.media.AudioTrack
import java.lang.ref.WeakReference
import kotlin.math.pow
import kotlin.math.sqrt

/** Reads the media stream on the routed track. Sampling happens off the audio and UI threads. */
internal class PlaybackVisualVolume(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)

    @Volatile
    private var track: WeakReference<AudioTrack>? = null

    fun observeTrack(audioTrack: AudioTrack) {
        track = WeakReference(audioTrack)
    }

    // AudioTrack routes to outputs. Some OEMs report an unsupported type, handled by the fallback.
    @SuppressLint("WrongConstant")
    fun sample(playerGain: Float): Float {
        val manager = audioManager ?: return visualGain(playerGain, 1, 1, null)
        val stream = AudioManager.STREAM_MUSIC
        return runCatching {
            val index = manager.getStreamVolume(stream)
            if (index == 0 || manager.isStreamMute(stream)) return 0f
            val max = manager.getStreamMaxVolume(stream)
            val routeDb = runCatching route@{
                val device = track?.get()?.routedDevice ?: return@route null
                if (!device.isSink) return@route null
                manager.getStreamVolumeDb(stream, index, device.type) -
                    manager.getStreamVolumeDb(stream, max, device.type)
            }.getOrNull()
            visualGain(playerGain, index, max, routeDb)
        }.getOrElse { visualGain(playerGain, 1, 1, null) }
    }

    companion object {
        /**
         * 把系统音量换算成波形的显示增益。
         *
         * **不再用线性振幅**（4.1.0 首次发布的写法是 `sqrt(playerGain × streamGain)`）：
         * 手机音量步进是**按 dB 均匀**的，50% 音量大约只有 −15 dB ≈ 0.18 的线性振幅，
         * 开方后只剩 0.42 —— 用户看到的就是"音量 50、波形高度也才 50%"，偏矮。
         *
         * 现在按**感知响度**折算：以 −[VISUAL_DB_FLOOR] dB 为归零点线性映射，
         * 于是 50% 音量 ≈ 0.75、30% ≈ 0.62、10% ≈ 0.35，静音仍是 0。
         * 拿不到路由 dB（少数 OEM）时退回 `(index/max)^0.35` —— 同样是"中间偏上"的形状。
         */
        internal fun visualGain(playerGain: Float, index: Int, max: Int, routeDb: Float?): Float {
            if (index <= 0 || !playerGain.isFinite()) return 0f
            val streamGain = when {
                routeDb == Float.NEGATIVE_INFINITY -> 0.0
                routeDb != null && routeDb.isFinite() ->
                    // 1 + db/60：0 dB → 1.00、−15 dB（约 50% 音量）→ 0.75、−30 dB → 0.50、−60 dB → 0。
                    (1.0 + routeDb.coerceAtMost(0f) / -VISUAL_DB_FLOOR).toDouble()
                else -> (index.toDouble() / max.coerceAtLeast(1)).pow(0.35)
            }.coerceIn(0.0, 1.0)
            // 播放增益仍开方压缩：安静段落也要有可见细节，但它不该再把中高音量压低。
            return (sqrt(playerGain.coerceIn(0f, 1f)) * streamGain).toFloat().coerceIn(0f, 1f)
        }

        /** 显示增益的 dB 量程：音量衰减到 −60 dB 时波形归零。 */
        private const val VISUAL_DB_FLOOR = -60f
    }
}
