/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.0 · A：按音源路由到对应 Provider。
 */

package com.takahashirinta.ncrust.source

import android.util.Log
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.player.SongUrlResult

/**
 * 音源路由（v2.1.0 · A）。
 *
 * 全应用**唯一**允许按音源分叉的地方。调用方（PlayerViewModel / PlaybackService /
 * 搜索页）只需要把 [SongItem] 递进来，由这里决定找哪个 [MusicSourceProvider]。
 *
 * 这样做的收益不是「少写 if」，而是**把串台的可能性收敛到一处**：
 * 网易云的 songId 与 QQ 音乐的 songid 各自独立编号，撞号是迟早的事；
 * 只要有一次取链忘了带音源，用户听到的就是另一首歌。让所有取链都经过这里，
 * 「忘了带音源」就变成一个可以在这个文件里一眼看完的问题。
 */
object SourceRouter {

    private val providers = LinkedHashMap<MusicSource, MusicSourceProvider>()

    init {
        // 网易云恒可用；QQ 音乐在 B 阶段注册（它的客户端需要先 init 拿到 cookie）。
        register(NeteaseSourceProvider)
        // v3.1.0 · B：B 站。**注册是无条件的**，但 Provider 的每条路径第一行都判
        // `BiliPrefs.isEnabled()` —— 关闭时它返回空列表 / null，一个请求都不发。
        // 为什么不在这里按开关条件注册：注册表是 `object` 的 init，只在类加载时跑一次，
        // 而开关是**用户随时可改**的（改完不该重启 App）。判据放在 Provider 里才是对的。
        register(com.takahashirinta.ncrust.bili.BiliSourceProvider)
    }

    /**
     * 注册/替换一个音源的实现。**同名音源后注册的覆盖先注册的** ——
     * 单测据此注入假实现，不需要真网络。
     */
    fun register(provider: MusicSourceProvider) {
        providers[provider.source] = provider
    }

    /** 取某个音源的实现；没注册返回 null（调用方应视作「该音源不可用」）。 */
    fun provider(source: MusicSource): MusicSourceProvider? = providers[source]

    /** 当前已注册的音源，按注册顺序。UI 用它决定「哪些音源可以出现」。 */
    fun registeredSources(): List<MusicSource> = providers.keys.toList()

    /**
     * 按歌曲所属音源取播放 URL。
     *
     * 三条前置判断都在这里做掉，调用方不必各自重复：
     * 1. 音源没注册（QQ 音乐在未登录 / 未接入时不会注册）⇒ null；
     * 2. [SongItem.isResolvable] == false（QQ 音乐缺 songmid）⇒ null。
     *    **绝不退回网易云取链** —— id 相同不代表是同一首歌；
     * 3. Provider 返回什么就是什么（失败即 null，由调用方决定跳歌还是提示）。
     */
    suspend fun resolveUrl(song: SongItem, level: String): SongUrlResult? {
        if (!song.isResolvable) {
            Log.w(TAG, "unresolvable song source=${song.musicSource.key} id=${song.id} (missing sourceId)")
            return null
        }
        val provider = providers[song.musicSource]
        if (provider == null) {
            Log.w(TAG, "no provider registered for source=${song.musicSource.key}")
            return null
        }
        return provider.resolveUrl(song, level)
    }

    /**
     * 按音源搜索。失败 / 未注册一律返回空列表 —— 聚合搜索时一个平台挂掉
     * 不应该把另一个平台的结果也吞掉，这个契约由调用方按返回值判断。
     */
    suspend fun searchSongs(source: MusicSource, keyword: String, limit: Int): List<SongItem> {
        val provider = providers[source] ?: return emptyList()
        return runCatching { provider.searchSongs(keyword, limit) }
            .onFailure { Log.w(TAG, "search failed on ${source.key}", it) }
            .getOrDefault(emptyList())
    }

    private const val TAG = "SourceRouter"
}
