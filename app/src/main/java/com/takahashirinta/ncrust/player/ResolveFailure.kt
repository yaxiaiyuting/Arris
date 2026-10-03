/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P0：**取链失败的分类与分流（纯逻辑，JVM 可单测）**。
 *
 * ## 这个文件为什么必须存在（v3.2.0 的两条新铁律）
 *
 * 铁律 20：**版权判定不得把「权限不足」误判为「无版权」。**
 * 铁律 21：**版权判定失败不得触发自动跳歌，必须走独立失败路径。**
 *
 * v3.1.0 及以前，取链失败的形状是**一个 null**：
 *
 * ```
 * QqApi.fetchPlayUrl(...)  -> null      // 不管是「要会员」「要登录」「网络挂了」还是「确实下架」
 * SourceRouter.resolveUrl  -> null
 * PlayerViewModel          -> 一律当作「这首放不了」-> 弹「此源无版权」-> 自动跳下一首
 * ```
 *
 * 于是「会员过期」「QQ 未登录」「一次网络抖动」在用户眼里全部变成
 * **「这首歌没有版权」+ 被跳过**。这不是文案问题，是判据问题：
 * 客户端**根本没有任何字段**支撑「无版权」这个结论（`QqCatalogApi` 的 KDoc
 * 早就写过「QQ 侧没有任何权威的版权字段……把『拿不到』说成『无版权』是撒谎」），
 * 而播放路径一直在撒这个谎。
 *
 * ## 本文件的职责边界
 *
 * 只做两件**纯函数**的事：
 * 1. [classifyResolveFailure] 把各音源交上来的原始判据（result 码 / 是否登录 /
 *    是否传输失败 / 该曲是否被服务端显式声明无版权）收敛成一个 [ResolveFailureKind]；
 * 2. [resolveFailureAction] 把「哪一种失败 × 哪一种开播来源」映射成**有界**的处置。
 *
 * 网络、SharedPreferences、Android 一律不碰 —— 所以这两件事都能被 JVM 单测逐条钉住，
 * 「哪些失败允许跳歌」这件事从此是一个**被测过的表**，而不是散在各处的 if。
 */

package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.source.MusicSource

/**
 * 取链失败的原因。**判据必须来自服务端或本地状态，不允许猜。**
 *
 * 每一档都写明「谁能产出它」—— 这条注释是防止下一个人顺手把某个失败塞进
 * [COPYRIGHT_GONE] 的唯一屏障（那正是铁律 20 要禁的动作）。
 */
enum class ResolveFailureKind {
    /**
     * **结构性取不到**：音源没注册、曲目缺 `sourceId`（QQ 缺 songmid）、
     * 音源被用户关掉。**这是唯一允许自动跳歌的一类** ——
     * 它与「版权 / 权限」无关，重试一万次也是同一个结果。
     */
    UNRESOLVABLE,

    /** 该音源**当前未登录**，而这首需要登录。产出者：QQ 的 `result=104003` + 本地无票据。 */
    NEED_LOGIN,

    /**
     * 已登录，但**权益不足**（会员专享 / 高音质档需要会员）。
     *
     * 产出者：QQ 的 `result=104003` 且本地**有**票据；ncm 的 `memberOnly == true`
     * 或 `fee == 1`（VIP 专享）。⚠️ 票据**已失效**时服务端同样回 104003 ——
     * 客户端分不开这两种，所以文案要同时给出「开通会员」与「重新登录」两条出路，
     * 绝不把它们说成「无版权」。
     */
    NEED_VIP,

    /** 数字专辑 / 单曲付费：需要单独购买。产出者：QQ 的 `pneedbuy==1 && isbuy==0`。 */
    NEED_PURCHASE,

    /** 地区限制。**目前没有任何实测样本**，保留判据位是因为它与「无版权」处置不同。 */
    REGION_LOCKED,

    /**
     * **版权方下架 / 无版权**——⚠️ 只有**音源自己显式声明**时才允许产出。
     *
     * 已实测的唯一合法产出者：ncm 的 `noCopyrightRcmd != null`
     * （v2.3.0 探针：零假阳性，带上它时该曲确实取不到链）。
     * QQ 侧**没有任何字段**能证明这件事，所以 **QQ 永远不产出这一档**
     * （见 `QqRejection.toResolveFailureKind`）。
     */
    COPYRIGHT_GONE,

    /** 凭证过期 / 被服务端拒绝（HTTP 401/403）。产出者：各音源的传输层状态码。 */
    AUTH_EXPIRED,

    /** 网络 / 超时 / 响应畸形。**可以重试**，但必须有界（铁律 5）。 */
    NETWORK,

    /** 读不懂的拒绝。**按「不跳歌」处置** —— 不知道原因时保守的一侧是不要动用户的队列。 */
    UNKNOWN,
}

/**
 * 一次取链失败的完整描述。
 *
 * @property kind 分类结论（本文件唯一的决策输入）。
 * @property source 出错的音源。用于文案（「qm」/「ncm」）与「可切另一源」的判据。
 * @property rawCode 服务端原始码（仅用于**日志**；`null` = 没有这个信息）。
 * @property rawMessage 服务端原始文案（仅用于**日志**）。
 *   ⚠️ **绝不直接回显给用户**：这是外部平台的自由文本，既不本地化也不可信。
 */
data class ResolveFailure(
    val kind: ResolveFailureKind,
    val source: MusicSource,
    val rawCode: Int? = null,
    val rawMessage: String? = null,
) {
    /** 诊断用的一行摘要（不含任何凭证）。 */
    fun diag(): String = "kind=$kind source=${source.key} code=${rawCode ?: "-"}"
}

/**
 * 一次取链的**结果或失败**。二者必居其一。
 *
 * 为什么不让 `resolveUrl` 返回 `null` 而再加一个「错误码出参」：出参是两个可以互相
 * 矛盾的真相源（返回了 URL 同时带着错误码），而本仓库已经因为「两份真相必然漂移」
 * 栽过跟头（v2.1.5 / v2.6.0）。密封类型让「成功」与「失败」在类型上互斥。
 */
data class ResolveOutcome(
    val result: SongUrlResult? = null,
    val failure: ResolveFailure? = null,
) {
    val isOk: Boolean get() = result != null

    companion object {
        fun ok(result: SongUrlResult): ResolveOutcome = ResolveOutcome(result = result)

        fun failed(
            kind: ResolveFailureKind,
            source: MusicSource,
            rawCode: Int? = null,
            rawMessage: String? = null,
        ): ResolveOutcome = ResolveOutcome(
            failure = ResolveFailure(kind, source, rawCode, rawMessage),
        )
    }
}

/**
 * 取链失败之后**该做什么**。
 *
 * 只有三态，且没有第四态 —— 「重试」与「跳歌」必须是互斥的两个决定，
 * 因为跳歌会**消耗用户的队列**（不可撤销），而重试只是多花一次往返。
 */
enum class ResolveFailureAction {
    /**
     * 允许跳到下一首。
     *
     * ⚠️ 只有 [ResolveFailureKind.UNRESOLVABLE] 能走到这里，**并且**调用方仍要过
     * `AutoSkipGuard` 的连续计数熔断。铁律 21 的可执行含义就是这张表。
     */
    SKIP_ALLOWED,

    /** 有界重试同一首歌（不跳歌）。次数由 [UrlRetryGate] 管，超了转 [STOP_AND_INFORM]。 */
    RETRY,

    /** 停下 + 给用户一条**说人话**的提示，等他手动操作。**绝不跳歌。** */
    STOP_AND_INFORM,
}

/**
 * 失败类型 → 处置。**纯函数，单测逐条覆盖。**
 *
 * | 失败 | 处置 | 依据（任务书 §3.1） |
 * |---|---|---|
 * | [ResolveFailureKind.UNRESOLVABLE] | [ResolveFailureAction.SKIP_ALLOWED] | 与版权无关，重试无意义 |
 * | [ResolveFailureKind.NETWORK] | [ResolveFailureAction.RETRY] | 「网络失败：重试（有界），不跳歌」 |
 * | [ResolveFailureKind.NEED_LOGIN] | [ResolveFailureAction.STOP_AND_INFORM] | 「认证失败：提示重新登录」 |
 * | [ResolveFailureKind.AUTH_EXPIRED] | [ResolveFailureAction.STOP_AND_INFORM] | 同上 |
 * | [ResolveFailureKind.NEED_VIP] | [ResolveFailureAction.STOP_AND_INFORM] | 「需要 VIP：提示用户，不跳歌」 |
 * | [ResolveFailureKind.NEED_PURCHASE] | [ResolveFailureAction.STOP_AND_INFORM] | 「需要单曲购买：提示用户，不跳歌」 |
 * | [ResolveFailureKind.COPYRIGHT_GONE] | [ResolveFailureAction.STOP_AND_INFORM] | 「无版权：提示用户，**尝试其他音源**（提示里给出口）」 |
 * | [ResolveFailureKind.REGION_LOCKED] | [ResolveFailureAction.STOP_AND_INFORM] | 同上 |
 * | [ResolveFailureKind.UNKNOWN] | [ResolveFailureAction.STOP_AND_INFORM] | 不知道原因时不动用户的队列 |
 *
 * ⚠️ **[ResolveFailureKind.COPYRIGHT_GONE] 也不跳歌**，这是本版与 v3.1.0 最大的行为差异，
 * 也是任务书 §3.1 的字面要求（「无版权：提示用户，尝试其他音源」——
 * 「尝试其他音源」是用户拿着提示去做的动作，不是客户端替他跳掉这一首）。
 */
fun resolveFailureAction(kind: ResolveFailureKind): ResolveFailureAction = when (kind) {
    ResolveFailureKind.UNRESOLVABLE -> ResolveFailureAction.SKIP_ALLOWED
    ResolveFailureKind.NETWORK -> ResolveFailureAction.RETRY
    ResolveFailureKind.NEED_LOGIN,
    ResolveFailureKind.AUTH_EXPIRED,
    ResolveFailureKind.NEED_VIP,
    ResolveFailureKind.NEED_PURCHASE,
    ResolveFailureKind.COPYRIGHT_GONE,
    ResolveFailureKind.REGION_LOCKED,
    ResolveFailureKind.UNKNOWN,
    -> ResolveFailureAction.STOP_AND_INFORM
}

/**
 * 这一类失败**是否值得给用户一条「可以换另一个音源试试」的提示**。
 *
 * 只对「这个源拿不到，但另一源可能有」的三类放行：
 * 无版权（换源是常规解法）、地区限制、以及权益不足（用户在另一家买了会员）。
 * 「未登录 / 凭证过期」不给换源提示 —— 那是**在当前源登录一下**就能解决的事，
 * 引导他换源等于让他白跑一趟（而且换过去多半还要再登录一次）。
 */
fun suggestsOtherSource(kind: ResolveFailureKind): Boolean = when (kind) {
    ResolveFailureKind.COPYRIGHT_GONE,
    ResolveFailureKind.REGION_LOCKED,
    ResolveFailureKind.NEED_VIP,
    ResolveFailureKind.NEED_PURCHASE,
    -> true

    ResolveFailureKind.NEED_LOGIN,
    ResolveFailureKind.AUTH_EXPIRED,
    ResolveFailureKind.UNRESOLVABLE,
    ResolveFailureKind.NETWORK,
    ResolveFailureKind.UNKNOWN,
    -> false
}

/**
 * 取链网络失败的**重试闸**（铁律 5：失败处理必须有界）。
 *
 * 为什么需要它：`NETWORK` 是唯一允许重试的一类，而「重试」如果不受限，
 * 一次断网就会让播放链在「取链 → 失败 → 取链」之间空转 —— 那正是 v2.2.1 那次
 * P0 级联故障的形状（用户报的「不关应用就一直切」本质是同一个环）。
 *
 * 与 `QualityRetryGuard` 的分工：那一个管**降档**重试（档位单调下降），
 * 这一个管**同一档位的原样重试**（网络抖动）。两者都按「本首歌」计数，换歌清零。
 *
 * @param maxAttemptsPerSong 同一首歌最多原样重试几次。取 2：
 *   一次抖动重试一次足够覆盖「DNS 慢一拍 / 连接被重置」，再多只是让用户多等。
 * @param minIntervalMs 两次重试之间的最小间隔，避免瞬间打两次。
 */
class UrlRetryGate(
    private val maxAttemptsPerSong: Int = MAX_ATTEMPTS_PER_SONG,
    private val minIntervalMs: Long = MIN_INTERVAL_MS,
) {
    companion object {
        /** 同一首歌最多原样重试 2 次。 */
        const val MAX_ATTEMPTS_PER_SONG = 2

        /** 两次重试之间至少隔 3 秒。 */
        const val MIN_INTERVAL_MS = 3_000L
    }

    private var songKey: String = ""
    private var attempts: Int = 0
    private var lastAttemptAtMs: Long = 0L

    /** 换歌：计数清零。**换歌是唯一的清零条件之一**（另一个是用户手动操作）。 */
    fun onNewSong(key: String) {
        if (key == songKey) return
        songKey = key
        attempts = 0
    }

    /** 用户手动操作（点播放 / 切歌 / 手动改档位）→ 重新给满额度。 */
    fun onUserAction() {
        attempts = 0
    }

    /**
     * 请求一次重试。
     *
     * @return 允许重试时返回 true，并且内部已记账；返回 false 表示额度用尽或间隔不足，
     *   调用方必须转 [ResolveFailureAction.STOP_AND_INFORM]。
     */
    fun requestRetry(nowMs: Long): Boolean {
        if (attempts >= maxAttemptsPerSong) return false
        if (lastAttemptAtMs != 0L && nowMs - lastAttemptAtMs < minIntervalMs) return false
        attempts++
        lastAttemptAtMs = nowMs
        return true
    }

    /** 还剩几次（诊断 / 日志用）。 */
    fun remaining(): Int = (maxAttemptsPerSong - attempts).coerceAtLeast(0)
}
