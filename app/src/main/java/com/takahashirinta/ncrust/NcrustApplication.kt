package com.takahashirinta.ncrust

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import coil.Coil
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.network.ClientIdentity
import com.takahashirinta.ncrust.player.QualityLadder
import com.takahashirinta.ncrust.ui.theme.BackgroundImageManager

/**
 * 全局 Application，提供 Coil ImageLoader 的分级缓存配置 + 内存压力响应。
 *
 * 分级依据设备总内存：
 * - ≤ 3GB：16MB 内存缓存（低端机，避免挤压音频缓冲）
 * - ≤ 6GB：24MB
 * - > 6GB：32MB
 *
 * 磁盘缓存统一 100MB，足够覆盖首页 + 几个详情页的封面。
 *
 * crossfade 默认关闭--Metro 不需要淡入，直接显示更利落。
 *
 * onTrimMemory 分级响应：内存紧张时先清 ContentCache，再清 Coil 内存缓存，
 * 避免低端机后台被杀。
 */
class NcrustApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // v1.2.0 · A1：取链请求需要稳定的客户端身份（deviceId 持久化在独立的 ncrust_device）。
        // 放在 Application 而不是 MainActivity —— 车机 / 媒体键冷启动可能不经过 Activity。
        ClientIdentity.init(this)
        // v1.2.0 · A2：档位表 7 → 8 档（新增 jymaster），把旧的档位索引一次性迁移过来。
        QualityLadder.migrate(getSharedPreferences(QualityLadder.PREFS, Context.MODE_PRIVATE))
    }

    override fun newImageLoader(): ImageLoader {
        val memoryBytes = memoryCacheSizeBytes(this)
        // 封面 / 艺人头像 / 专辑图全部走 Coil：它默认自建 OkHttpClient（没有我们的 DNS 策略），
        // 本机 IPv6 不通时图片会一直等到超时 —— 表现为「图一直灰着、列表滚动卡」。
        val imageClient = com.takahashirinta.ncrust.network.AppHttp.builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(imageClient)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(memoryBytes)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .maxSizeBytes(100L * 1024 * 1024)
                    .directory(cacheDir.resolve("image_cache"))
                    .build()
            }
            .build()
    }

    private fun memoryCacheSizeBytes(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024 * 1024)
        return when {
            totalMb <= 3072 -> 16 * 1024 * 1024
            totalMb <= 6144 -> 24 * 1024 * 1024
            else -> 32 * 1024 * 1024
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                ContentCache.clearAll()
                Coil.imageLoader(this).memoryCache?.clear()
                // v1.2.0 · B3：Coil 缓存清掉后，界面上那张背景图仍被 painter 持有；
                // 显式隐藏它才能真正释放（回前台自动恢复）。
                BackgroundImageManager.onMemoryPressure()
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                ContentCache.clearAll()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ContentCache.clearAll()
        Coil.imageLoader(this).memoryCache?.clear()
        BackgroundImageManager.onMemoryPressure()
    }
}
