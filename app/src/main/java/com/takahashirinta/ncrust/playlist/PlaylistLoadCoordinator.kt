/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.2.0：账号切换 / 歌单切换的竞态防护。**纯逻辑，无 Android 依赖、无协程、无 IO，JVM 可单测。**
 */

package com.takahashirinta.ncrust.playlist

import com.takahashirinta.ncrust.source.PlaylistKey

/**
 * 歌单请求的世代 + 主体判定（v2.2.0）。
 *
 * ## 为什么必须有它
 *
 * v2.1.5 的跨源歌词串台（`LyricLoadCoordinator` 的由来）在歌单上会以**更严重**的形式重演：
 * 歌词串台最多是「显示错一行字」，歌单串台是**把 A 账号的歌单当成 B 账号的显示出来**。
 * 触发路径很普通：
 *
 * 1. 用户点开 QQ 账号 A 的歌单「我喜欢的音乐」，请求在飞；
 * 2. 用户切到账号 B（或点开另一个歌单）；
 * 3. A 的响应回来了。
 *
 * 若无世代判定，第 3 步会**覆盖** B 的界面。所以这里把「这次请求还算不算数」收敛成
 * 一个**纯函数** [isCurrent]，判据三条同时成立：
 *
 * - `load.generation == generation` —— 期间没有发生任何作废事件；
 * - `load.key == currentKey` —— 请求的就是当前正在看的那个歌单（[PlaylistKey.equals] 含 ownerId，
 *   所以「同一个歌单 id、不同账号」天然判不等）；
 * - `load.ownerId == currentOwnerId` —— 期间没有换过账号。
 *
 * 第三条看似与第二条重复（key 里就有 ownerId），但**不重复**：
 * 列表页的请求主体是**账号**而不是某个歌单（`currentKey == null`），
 * 而 `beginList()` 领号时 `currentKey` 是 null —— 若只比 key，「A 的列表」与「B 的列表」
 * 的 key 都是 null，会互相覆盖。ownerId 那一条正是为列表页准备的。
 *
 * ## 线程模型（与 `LyricLoadCoordinator` 一致）
 *
 * 只在主线程使用：全部状态是普通 `var`，没有锁，也没有 `@Volatile`。
 * 这让它可以在 Compose 的 `remember` 里安全持有，也让单测是确定性的（不需要并发）。
 * 若将来要在后台线程读写，**必须**先把它改成单线程调度或加锁 —— 不要靠「大概不会并发」。
 */
class PlaylistLoadCoordinator {

    /**
     * 一次已被领号的请求。
     *
     * @property generation 领号时的世代。
     * @property key 请求的歌单；列表请求为 null。
     * @property ownerId 领号时的账号。**必填**（未登录用 [PlaylistKey.OWNER_ANONYMOUS]），
     *   这样「未登录」也是一个明确的身份，而不是「没有身份」。
     */
    data class Load(
        val generation: Long,
        val key: PlaylistKey?,
        val ownerId: String,
    )

    /**
     * 加载失败的原因。**分类型而不是一个字符串**，因为 UI 对它们的处置完全不同：
     * [NeedLogin] 要给「去登录」入口，[Network] 要给「重试」，[NotFound] 只能回退。
     */
    enum class Reason {
        /** 登录态已失效（qm 实测：`GetLoginUserInfo` 返回 `code=1000`）。 */
        NEED_LOGIN,

        /** 网络失败 / 服务端返回了非 0 的业务码。可重试。 */
        NETWORK,

        /** 歌单不存在或不属于当前账号（qm 实测：`code=10004`）。**重试没有意义**。 */
        NOT_FOUND,

        /** 登录态有效，但这个歌单确实一首歌都没有。**不是错误**，但详情页要能区分它与「还没加载」。 */
        EMPTY,
    }

    /** UI 状态机。与 `LyricLoadCoordinator.State` 同形，便于两处的心智模型一致。 */
    sealed interface State {
        /** 还没有确定主体（未登录、或还没进页面）。 */
        data object Idle : State

        /** 正在拉取。**切换歌单/账号的瞬间就进入这个状态**，旧数据不得残留。 */
        data class Loading(val key: PlaylistKey?) : State

        /** 就绪。[trackCount] 是本次拿到的曲目数。 */
        data class Loaded(val key: PlaylistKey?, val trackCount: Int) : State

        /** 这次没拿到。[reason] 决定 UI 给什么出口。 */
        data class Error(val key: PlaylistKey?, val reason: Reason) : State
    }

    /** 单调递增的世代。只用 `++`，永不回绕赋值。 */
    var generation: Long = 0L
        private set

    /** 当前主体歌单；列表页为 null。 */
    var currentKey: PlaylistKey? = null
        private set

    /** 当前账号。null 表示「还没确定」（与 [PlaylistKey.OWNER_ANONYMOUS] 不同）。 */
    var currentOwnerId: String? = null
        private set

    var state: State = State.Idle
        private set

    /**
     * 账号发生变化（登录 / 登出 / 换号）。**作废一切在途请求**，并清空当前歌单。
     *
     * 幂等：同一个 ownerId 再调一次也会 `generation++` —— 这是**有意的**，
     * 因为「重新登录了同一个账号」也必须让旧票据时代发出的请求作废
     * （旧请求可能是用已经失效的 cookie 发的，它带回来的 `NEED_LOGIN` 会覆盖新登录的成功结果）。
     */
    fun onOwnerChanged(ownerId: String?) {
        currentOwnerId = ownerId
        currentKey = null
        generation++
        state = State.Idle
    }

    /**
     * 为「列表页」领一次请求号。主体是**账号**。
     *
     * @param ownerId 必须是调用方此刻认定的账号。传 null 视为未登录（[PlaylistKey.OWNER_ANONYMOUS]），
     *   而不是「不校验账号」—— 后者会让未登录的响应落进已登录的界面。
     */
    fun beginList(ownerId: String?): Load {
        val owner = ownerId ?: PlaylistKey.OWNER_ANONYMOUS
        currentOwnerId = owner
        currentKey = null
        generation++
        val load = Load(generation, null, owner)
        state = State.Loading(null)
        return load
    }

    /**
     * 为「某个歌单的详情」领一次请求号。主体是**歌单**（其 key 里已经带了 ownerId）。
     *
     * 会同时把 [currentOwnerId] 对齐到 `key.ownerId`：调用方拿到的 key 就是权威身份，
     * 不必再单独传一次账号（传两次必然有一天会不一致）。
     */
    fun beginDetail(key: PlaylistKey): Load {
        currentOwnerId = key.ownerId
        currentKey = key
        generation++
        val load = Load(generation, key, key.ownerId)
        state = State.Loading(key)
        return load
    }

    /**
     * 这次请求**是否仍然有效**。
     *
     * 注意 `currentOwnerId == null` 时恒为 false：主体都还没确定，任何响应都不该落进 UI。
     */
    fun isCurrent(load: Load): Boolean =
        load.generation == generation &&
            load.key == currentKey &&
            currentOwnerId != null &&
            load.ownerId == currentOwnerId

    /** [State.Loading] → [State.Loaded]。不是当前请求则**整包丢弃**并返回 false。 */
    fun accept(load: Load, trackCount: Int): Boolean {
        if (!isCurrent(load)) return false
        state = State.Loaded(load.key, trackCount)
        return true
    }

    /**
     * [State.Loading] → [State.Error]。同样要判世代 —— 否则「切歌单时上一个歌单的超时」
     * 会把新歌单打成错误态（用户看到的是「刚点进去就报错」）。
     */
    fun fail(load: Load, reason: Reason): Boolean {
        if (!isCurrent(load)) return false
        state = State.Error(load.key, reason)
        return true
    }

    /**
     * 渲染闸门：**只有「当前主体的、已就绪的」数据才允许画出来**。
     *
     * 与 [isCurrent] 的区别：[isCurrent] 判的是「这个请求还算不算数」（请求侧），
     * 这里判的是「这份已经拿到的数据还能不能画」（渲染侧）。渲染侧多一个 `Loaded` 条件 ——
     * 一个已经被 `fail` 掉的 key，即便它 == `currentKey`，也不该被当成有数据。
     */
    fun shouldRender(loaded: PlaylistKey?): Boolean =
        state is State.Loaded && loaded == currentKey

    /** 退出页面 / 登出：作废一切。 */
    fun invalidate() {
        generation++
        currentKey = null
        currentOwnerId = null
        state = State.Idle
    }
}
