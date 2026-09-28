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
import com.takahashirinta.ncrust.player.ResolveFailureKind
import com.takahashirinta.ncrust.player.ResolveOutcome
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
     * v3.2.0 · P0：按音源取链，**并带上失败分类**。播放链走这一条。
     *
     * 三条前置判断与 [resolveUrl] 完全一致（音源未注册 / 缺 sourceId / Provider 说什么就是什么），
     * 唯一的差别是失败时不再丢弃原因 —— 那正是铁律 20/21 要求的东西：
     * 「权限不足」与「无版权」必须能被分开，而分开的唯一办法是**别把原因扔掉**。
     *
     * ⚠️ 这两条路径的实现必须**逐分支对齐**：漏掉一条分支的表现是
     * 「同一首歌从离线兜底走能播、走在线路径说没版权」。有单测钉住两者的一致性。
     */
    suspend fun resolveUrlOutcome(song: SongItem, level: String): ResolveOutcome {
        if (!song.isResolvable) {
            Log.w(TAG, "unresolvable song source=${song.musicSource.key} id=${song.id} (missing sourceId)")
            // 结构性失败：与版权/权限无关，是唯一允许跳歌的一类。
            return ResolveOutcome.failed(ResolveFailureKind.UNRESOLVABLE, song.musicSource)
        }
        val provider = providers[song.musicSource]
        if (provider == null) {
            Log.w(TAG, "no provider registered for source=${song.musicSource.key}")
            return ResolveOutcome.failed(ResolveFailureKind.UNRESOLVABLE, song.musicSource)
        }
        // Provider 的契约是「绝不抛」，但这里再兜一层：取链在播放的关键路径上，
        // 一个未捕获的异常会把「取链失败」升级成「播放崩溃」（铁律 4）。
        return runCatching { provider.resolveUrlOutcome(song, level) }
            .onFailure { Log.w(TAG, "resolveUrlOutcome failed on ${song.musicSource.key}", it) }
            .getOrElse {
                ResolveOutcome.failed(ResolveFailureKind.UNKNOWN, song.musicSource)
            }
    }

    /**
     * 按音源搜索。失败 / 未注册一律返回空列表 —— 聚合搜索时一个平台挂掉
     * 不应该把另一个平台的结果也吞掉，这个契约由调用方按返回值判断。
     *
     * ## v3.2.0 · P0-C：**取消必须原样抛出**（这一条是本版新加的）
     *
     * v3.1.0 时这段用的是 `runCatching { … }.getOrDefault(emptyList())`，而那时
     * B 站那条腿是**纯同步阻塞**的（`BiliApi` 全是 `execute()`），整条路径上没有挂起点，
     * 取消根本不会在这里出现，所以吞掉它没有可观测后果。
     *
     * 本版把 B 站调用挪进了 `withContext(Dispatchers.IO)`（P0-C 的修复）⇒ 取消从此会在
     * 这里出现，而「取消是控制流不是错误」是本仓库 v2.5.5 就写下的纪律
     * （`SearchViewModel` 的注释原话：「取消必须原样抛出，不能落进 `catch (e: Exception)`」）。
     * 吞掉它有两个具体后果：① 用户每敲一个字（500ms debounce 之后的 `searchJob.cancel()`）
     * 都会打一条 "search failed on …" 的**假日志**；② 嵌套超时抛出的
     * `TimeoutCancellationException`（job 本身没被取消的那种）会被改写成「真的 0 条」——
     * 那正是 v2.5.5 用一整版修出来的「PENDING/TIMEOUT 不许显示成 DONE+0」。
     *
     * 契约的其余部分一个字没变：**绝不把异常抛给调用方**（取消除外），
     * 也**不引入任何重试**（失败处理有界）。
     */
    suspend fun searchSongs(source: MusicSource, keyword: String, limit: Int): List<SongItem> {
        val provider = providers[source] ?: return emptyList()
        return try {
            provider.searchSongs(keyword, limit)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "search failed on ${source.key}", e)
            emptyList()
        }
    }

    private const val TAG = "SourceRouter"
}
