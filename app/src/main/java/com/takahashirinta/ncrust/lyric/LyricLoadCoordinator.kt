/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.1.5：把「歌词请求的世代 + 当前曲目身份 + UI 状态机」收进一个纯逻辑对象。
 */

package com.takahashirinta.ncrust.lyric

import com.takahashirinta.ncrust.source.MusicSource
import com.takahashirinta.ncrust.source.TrackKey

/**
 * 歌词加载协调器（v2.1.5）。**纯逻辑、无 Android、无 IO、无协程**，JVM 可单测。
 *
 * ## 它取代了什么，为什么原来的不够
 *
 * v1.9.0 引入了 [LyricRequestGate]（自增序号 + `isCurrent`），修的是一类具体缺陷：
 * 「A 歌的请求还在飞，用户切到 B 歌，A 的响应后到把 B 的歌词盖掉」。那个闸门本身是对的，
 * 但它只有**一个维度**：序号。调用方还要自己再补一个 `currentSongId.value == songId` 的判断，
 * 也就是说「这份歌词属于哪首歌」这件事在两个地方各判一次，且判的是**裸 songId**。
 *
 * v2.1.4 的用户报障暴露了单维度的不足：跨源自动接续时，旧响应并不是「过期的旧请求」——
 * 它是**当次请求的真实响应**，序号是新鲜的，`currentSongId` 也已经是新歌。
 * 唯一错的是它回答的是**另一个音源**的曲目。所以判据必须带上音源，也就是 [TrackKey]。
 *
 * 本对象把三件事合成一个状态机：
 *
 * | 关注点 | 字段 |
 * |---|---|
 * | 现在播的是哪一首 | [currentTrack] |
 * | 在途请求属于哪一代 | [Load.generation] 与私有 `generation` |
 * | UI 该显示什么 | [state] |
 *
 * ## 三条不变量（全部有单测）
 *
 * 1. **身份含音源**：判「还是不是当前请求」时比较的是 [TrackKey]，
 *    而 [TrackKey] 的相等性只看 `(source, id)`。于是 `netease:123` 的响应
 *    永远不可能被当成 `qqmusic:123` 的响应。
 * 2. **世代单调**：[onTrackChanged] 与 [begin] 都会让 `generation` 自增，
 *    所有更早的 [Load] 立即失效。切歌**一定**先经过 [onTrackChanged]，
 *    所以「切歌后旧响应还能落地」在结构上不可能。
 * 3. **切歌先清空**：[onTrackChanged] 把状态置成 [State.Loading]，
 *    旧歌词不会残留到新歌词就绪（这正是用户看到的「仍是 QQ 那首」）。
 *
 * ## 线程模型
 *
 * 所有方法都只在主线程调用（`PlayerViewModel` 的既有契约：状态写入前都回到 Main）。
 * 刻意**不加锁**：一个 `synchronized` 会让「谁在什么时候改状态」变得难以推理，
 * 而这里根本不需要 —— 挂起点之后复查 [isCurrent] 已经是完整的保护。
 */
class LyricLoadCoordinator {

    /**
     * 一次歌词请求的凭据。响应回来时凭它回答「我还是当前那次请求吗」。
     *
     * @property generation 领号时刻的世代。
     * @property track 这次请求是**为哪一首**发的。
     */
    data class Load(val generation: Long, val track: TrackKey)

    /**
     * UI 状态机的取值。渲染层只接受 [Loaded.track] == 播放器当前曲目的歌词。
     *
     * 抽成 sealed interface 而不是几个 `Boolean` 流，是因为「没有歌词」有两种完全不同的含义：
     * [Empty]（服务端权威地说这首歌没歌词 → 歌词按钮置灰）与 [Error]（这次没拿到 → 保持可重试）。
     * 用布尔量表示时这两者必然会被某一处写反，而它们的用户可见行为是相反的。
     */
    sealed interface State {
        /** 还没有任何曲目（冷启动、停止播放）。 */
        data object Idle : State

        /** 正在为 [track] 取词。**切歌瞬间就进入这个状态**，旧歌词不得残留。 */
        data class Loading(val track: TrackKey) : State

        /** [track] 的歌词就绪，共 [lineCount] 行。 */
        data class Loaded(val track: TrackKey, val lineCount: Int) : State

        /** 服务端权威答复：[track] 确实没有歌词（或只有空行）。 */
        data class Empty(val track: TrackKey) : State

        /** [track] 这次没拿到（瞬时失败）。可重试，**不能**当成「没有歌词」。 */
        data class Error(val track: TrackKey, val reason: String) : State
    }

    /** 播放器当前所在曲目；null = 还没有曲目。 */
    var currentTrack: TrackKey? = null
        private set

    /** UI 状态机当前取值。 */
    var state: State = State.Idle
        private set

    private var generation: Long = 0L

    /**
     * 播放器切到了另一首（或第一次确定曲目）。**作废所有在途请求**并置 [State.Loading]。
     *
     * 这是本版修复的接缝：`PlayerViewModel.onSongTransitioned` 必须在发起新取词**之前**
     * 调用它，否则 `currentTrack` 还是上一首的音源，新取词会走错平台的接口。
     *
     * 同一首歌重复调用是幂等的（世代前进无害，状态仍是该曲的 [State.Loading]）——
     * 不在这里做「同曲就跳过」，因为显式重播同一首歌时也必须丢掉在途的旧响应。
     */
    fun onTrackChanged(track: TrackKey) {
        currentTrack = track
        generation++
        state = State.Loading(track)
    }

    /**
     * 为 [track] 领一次请求号。同时把 [currentTrack] 对齐到 [track]
     * （重试路径不会先走 [onTrackChanged]）。
     *
     * 与 [LyricRequestGate.begin] 的取舍相同：领号本身就让更早的号失效，
     * 不需要额外的「作废」标志位，也不存在「作废后闸门卡死」的状态。
     */
    fun begin(track: TrackKey): Load {
        currentTrack = track
        generation++
        val load = Load(generation, track)
        state = State.Loading(track)
        return load
    }

    /**
     * 这次请求**是否仍然有效**：世代没被超越，且请求的曲目就是当前曲目。
     *
     * 两个条件都要，缺一不可 ——
     * 只看世代会漏掉「同一世代里曲目被换掉」（[onTrackChanged] 会自动让世代前进，
     * 但重试路径 [begin] 会为同一曲目发新号）；
     * 只看曲目会漏掉「同一首歌发了两次请求，先发的后到」。
     */
    fun isCurrent(load: Load): Boolean =
        load.generation == generation && load.track == currentTrack

    /** [State.Loading] → [State.Loaded]。不是当前请求则**整包丢弃**并返回 false。 */
    fun accept(load: Load, lineCount: Int): Boolean {
        if (!isCurrent(load)) return false
        state = State.Loaded(load.track, lineCount)
        return true
    }

    /** [State.Loading] → [State.Empty]（服务端权威地答复「这首歌没有歌词」）。 */
    fun markEmpty(load: Load): Boolean {
        if (!isCurrent(load)) return false
        state = State.Empty(load.track)
        return true
    }

    /** [State.Loading] → [State.Error]。同样拒绝过期响应。 */
    fun fail(load: Load, reason: String): Boolean {
        if (!isCurrent(load)) return false
        state = State.Error(load.track, reason)
        return true
    }

    /**
     * 作废所有在途请求并回到 [State.Idle]（退出播放器 / 清空当前歌曲）。
     *
     * 与 [LyricRequestGate.invalidate] 一样共用同一条自增：老号因不等于新号而天然失效，
     * 之后 [begin] 仍能拿到有效号。
     */
    fun invalidate() {
        generation++
        currentTrack = null
        state = State.Idle
    }

    /**
     * 渲染层判据：**只接受属于当前曲目、且已经就绪**的歌词。
     *
     * `Loaded.track == player.currentTrackKey` 是本版对「歌词必须跟着当前音频」这条
     * 验收标准的**结构性**保证 —— 它不依赖任何调用顺序，只依赖相等性。
     */
    fun shouldRender(loaded: TrackKey?): Boolean =
        loaded != null && currentTrack != null && loaded == currentTrack && state is State.Loaded

    /**
     * 这首歌的歌词要不要落 [LyricsCache]；返回它的缓存 key，不可缓存返回 null。
     *
     * ## 为什么 key 里没有音源段（以及对「按 source 隔离」的交代）
     *
     * v2.1.0 起 qm 的数字 id 被 [com.takahashirinta.ncrust.source.SourceIds.qqId]
     * 抬到 `1L shl 62` 以上，而 ncm 的 id 是百万~十亿量级（远小于 `2^40`）。
     * 两个 id 空间**结构性不相交**，所以 `id.toString()` 这个既有形状本身就已经按音源隔离了
     * —— 这不是推断，[LyricLoadCoordinatorTest] 用边界值把它钉死了。
     *
     * 既然如此就**不给这张表加音源段**：`ncrust_lyrics_cache` 是跨版本存活的表
     * （LRC 条目没有 TTL，只有 200 条 LRU），改 key 形状等于让所有存量缓存失效，
     * 而按本仓库纪律「加字段 = 加迁移逻辑 = 加单测」，为一个可以证明不需要的隔离付迁移成本
     * 是纯粹的负收益。
     *
     * 真正需要的是**堵住漏写**：QQ 曲目的歌词本来就不进这张表（见
     * `PlayerViewModel.loadQqLyrics` 的说明），这里返回 null 把这条约定变成可执行的判据，
     * 而不是散在调用方的注释里。
     */
    fun cacheKeyOf(track: TrackKey): String? =
        if (track.source == MusicSource.NETEASE && track.id > 0L) track.id.toString() else null
}
