/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.2.0 · P0：QQ 取链失败的**分类**（纯逻辑，无 Android、无 IO、JVM 可单测）。
 */

package com.takahashirinta.ncrust.qq

import com.takahashirinta.ncrust.player.ResolveFailureKind

/**
 * QQ `music.vkey.GetVkey` 一次取链被拒的原因。
 *
 * ## 这个枚举存在的唯一理由：**不许再把「权限不足」说成「无版权」**
 *
 * v3.1.0 及以前，`QqApi.fetchPlayUrl` 把下面**全部**情况折叠成一个 `null`：
 *
 * | 真实情况 | 服务端证据 | v3.1.0 的结论 | 用户看到的 |
 * |---|---|---|---|
 * | 未登录 | `result=104003`，且本地无票据 | `null` | 「此源无版权」+ 自动跳歌 |
 * | 已登录但无会员权益 / 票据失效 | `result=104003`，且本地有票据 | `null` | 同上 |
 * | 需要单曲购买 | `pneedbuy=1 && isbuy=0` | `null` | 同上 |
 * | 网络挂了 | 请求本身失败 | `null` | 同上 |
 * | 该档位确实没有文件 | `result=0 && purl==""` | `null` | 同上 |
 *
 * 五行里**没有一行**能证明「版权」这件事，而 UI 一直这么说（见 `PlaybackGuard` 的
 * `SourceFallbackHintGate`）。本枚举把每一行分开，并且**不提供**「无版权」这一档 ——
 * QQ 侧没有那个字段，客户端就不许下那个结论（铁律 20）。
 *
 * ## 哪些取值是实测的，哪些是防御性的（**不许含糊**）
 *
 * | 取值 | 证据等级 |
 * |---|---|
 * | [NEED_LOGIN] / [NEED_VIP]（`result=104003`） | **实测**：2026-09-28，同一首歌同一 `filename[]`，带 VIP 票据 8/8 档 `result=0` + purl；完全匿名 8/8 档 `result=104003` + 空 purl |
 * | [NO_FILE]（`result=0` 但 purl 空） | **实测到过**（匿名态部分档位），但**没有**在登录态复现过 |
 * | [NEED_PURCHASE]（`pneedbuy`/`isbuy`） | **字段实测存在**（`midurlinfo[]` 里 `pneedbuy` / `isbuy` / `pneed` / `isonly` / `onecan` 都在），但本轮 12 首样本里**全部为 0**，即「非零时服务端怎么答」**未验证** |
 * | [TRIAL_ONLY]（`type == -1`） | **未复现**（v3.1.0 也记过同一句） |
 * | [REGION_LOCKED] / [COPYRIGHT_GONE] | **没有任何实测样本**。保留判据位是为了「将来真拿到证据时不用重排分类」，**当前没有任何代码路径能产出它们** |
 * | [AUTH_EXPIRED]（HTTP 401/403） | **未实测**（QQ 的失败都是 HTTP 200 + 业务码；401/403 是防御分支） |
 */
enum class QqRejection {
    /** 没有被拒（成功）。 */
    NONE,

    /** 本地没有票据，服务端要求登录。 */
    NEED_LOGIN,

    /**
     * 本地**有**票据但服务端仍拒（`104003`）。
     *
     * ⚠️ 两件事在客户端**分不开**：会员权益不足，与「票据已失效但字段还在」。
     * 所以文案必须同时给出「开通会员」与「重新登录」两条出路 ——
     * 说死了任何一边都会让另一半用户白折腾。
     */
    NEED_VIP,

    /** 数字专辑 / 单曲付费。 */
    NEED_PURCHASE,

    /** 地区限制（**无实测样本**）。 */
    REGION_LOCKED,

    /** 版权方下架（**无实测样本**，QQ 当前不产出）。 */
    COPYRIGHT_GONE,

    /** 只拿到试听片段（**无实测样本**）。 */
    TRIAL_ONLY,

    /** 凭证过期 / 被服务端拒绝（HTTP 401/403）。 */
    AUTH_EXPIRED,

    /** 该档位服务端没有文件（`result=0` 且 purl 为空）。 */
    NO_FILE,

    /** 网络 / 超时 / 响应畸形。 */
    NETWORK,

    /** 其它非零 `result`：读不懂。 */
    UNKNOWN,
}

/**
 * 分类的输入。**只收原始判据**，不做任何二次解释 —— 这样单测能逐字段钉死。
 *
 * @property hasPurl 这一档**服务端给了非空 purl**。这是「能播」的**唯一**判据 ——
 *   比 `result` 码更权威：实测《Hotel California》的 `AI00` 回 `104003` 而 `Q000` 回 `0` + purl，
 *   只看 `result` 会把「其实能播」误判成被拒。默认 `false` 是因为**绝大多数的调用点
 *   都只在「没有 purl」时才会去分类**（`fetchPlayUrl` 拿到 purl 就返回了）。
 * @property resultCode `midurlinfo[i].result`。`null` = 根本没有这一项（响应缺字段）。
 * @property tips `midurlinfo[i].tips`。**只进日志**，绝不回显（外部自由文本）。
 * @property pneedbuy `midurlinfo[i].pneedbuy`。
 * @property isbuy `midurlinfo[i].isbuy`。
 * @property trialType `midurlinfo[i].type`；实测已知 `-1` 表示 30 秒试听（本版未复现）。
 * @property transportFailed 请求本身失败（连不上 / 非 2xx / 响应不是 JSON）。
 * @property httpStatus 传输层的 HTTP 状态码；`null` = 没拿到响应。
 * @property loggedIn 发起这次请求时**本地是否持有完整票据**（`uin` + `musicKey` 都有）。
 *   这是「104003 该读成『去登录』还是『权益不足』」的**唯一**判据。
 */
data class QqRejectionInput(
    val hasPurl: Boolean = false,
    val resultCode: Int? = null,
    val tips: String? = null,
    val pneedbuy: Int? = null,
    val isbuy: Int? = null,
    val trialType: Int? = null,
    val transportFailed: Boolean = false,
    val httpStatus: Int? = null,
    val loggedIn: Boolean = false,
)

/** 实测的「需要登录 / 需要会员」业务码。**唯一在登录态与匿名态双向验证过的码。** */
const val QQ_RESULT_NEED_LOGIN_OR_VIP = 104003

/**
 * 单条 `midurlinfo` 条目的分类。
 *
 * 判据顺序是**有意的**（先传输层、再购买、再试听、最后业务码）：
 * 传输层失败时 `resultCode` 一定是 null，把它放最前面可以让「网络问题」
 * 永远不会被误读成任何一种业务拒绝。
 */
fun classifyQqRejection(input: QqRejectionInput): QqRejection {
    // ⓪ 拿到了非空 purl ⇒ 能播。这一条必须排在最前面：服务端对**不同档位**的权限不同
    //    （实测同一首歌 AI00 被拒而 Q000 给链），所以「有一档成功」优先于任何拒绝码。
    if (input.hasPurl) return QqRejection.NONE
    // ① 传输层：请求根本没成功。401/403 归「凭证」而不是「网络」—— 那需要用户重新登录。
    if (input.transportFailed) {
        return when (input.httpStatus) {
            401, 403 -> QqRejection.AUTH_EXPIRED
            else -> QqRejection.NETWORK
        }
    }
    // ② 单曲购买：服务端把「要不要买」写成了两个独立字段，两者同时成立才是「没买」。
    if (input.pneedbuy == 1 && input.isbuy == 0) return QqRejection.NEED_PURCHASE
    // ③ 试听片段：拿到了 purl 但它只有 30 秒。判据来自服务端字段，不来自文件名/时长猜测。
    if (input.trialType == -1) return QqRejection.TRIAL_ONLY
    // ④ 业务码。104003 是**唯一**实测过的拒绝码。
    return when (val code = input.resultCode) {
        null -> QqRejection.UNKNOWN
        0 -> QqRejection.NO_FILE          // result=0 却没有 purl ⇒ 这一档服务端没有文件
        QQ_RESULT_NEED_LOGIN_OR_VIP ->
            if (input.loggedIn) QqRejection.NEED_VIP else QqRejection.NEED_LOGIN

        else -> QqRejection.UNKNOWN       // 包括 code < 0 的各种风控码 —— 一律不猜
    }
}

/**
 * 把**一次批量取链的全部档位**的分类收敛成一条。
 *
 * ## 为什么不能取「最后一条」或「第一条」
 *
 * 实测（2026-09-28，登录态）：《Hotel California》的 `AI00` 返回 `104003` 而
 * `Q000` 返回 `0` + purl —— 也就是说**同一首歌的不同档位各自的权限不同**。
 * 取错一条就会让「其实能播」的歌被报成「要会员」（或者反过来，把真的要会员的歌
 * 说成「没文件」，用户重试到天荒地老）。
 *
 * ## 收敛规则（按优先级，第一条命中即返回）
 *
 * 1. 有任何一档 [QqRejection.NONE] ⇒ [QqRejection.NONE]（**能播就是能播**）；
 * 2. 否则取「最有行动价值」的那一条：凭证问题 > 购买 > 试听 > 地区/下架 > 无文件 > 未知。
 *    「有行动价值」的定义是**用户能对提示做出动作**：重新登录 / 开通会员 / 购买。
 *    网络失败排在最前（它连「这首歌能不能播」都还没回答）。
 */
fun classifyQqBatch(inputs: List<QqRejectionInput>): QqRejection {
    if (inputs.isEmpty()) return QqRejection.UNKNOWN
    if (inputs.any { classifyQqRejection(it) == QqRejection.NONE }) return QqRejection.NONE
    val ranked = inputs.map { classifyQqRejection(it) }
    return RANKING.firstOrNull { it in ranked } ?: QqRejection.UNKNOWN
}

/** 收敛优先级（自左向右）。**显式的表**，不是散落的 `if`（同 `MotionDegrade` 的教训）。 */
private val RANKING = listOf(
    QqRejection.NETWORK,
    QqRejection.AUTH_EXPIRED,
    QqRejection.NEED_LOGIN,
    QqRejection.NEED_VIP,
    QqRejection.NEED_PURCHASE,
    QqRejection.TRIAL_ONLY,
    QqRejection.REGION_LOCKED,
    QqRejection.COPYRIGHT_GONE,
    QqRejection.NO_FILE,
    QqRejection.UNKNOWN,
)

/**
 * QQ 的分类 → 跨音源的分类（铁律 21 的决策输入）。
 *
 * ⚠️ 这张映射表里**没有一行产出 `COPYRIGHT_GONE`**，这是刻意的：
 * QQ 侧没有任何字段能证明「版权方下架」，所以 QQ 的失败**永远不许**被说成无版权。
 * 想加这一行的人必须先拿出「服务端哪个字段说了这句话」的实测证据。
 */
fun QqRejection.toResolveFailureKind(): ResolveFailureKind = when (this) {
    QqRejection.NONE -> ResolveFailureKind.UNKNOWN          // 不该被调用（成功不分类）
    QqRejection.NEED_LOGIN -> ResolveFailureKind.NEED_LOGIN
    QqRejection.NEED_VIP -> ResolveFailureKind.NEED_VIP
    QqRejection.NEED_PURCHASE -> ResolveFailureKind.NEED_PURCHASE
    QqRejection.REGION_LOCKED -> ResolveFailureKind.REGION_LOCKED
    QqRejection.COPYRIGHT_GONE -> ResolveFailureKind.COPYRIGHT_GONE
    QqRejection.TRIAL_ONLY -> ResolveFailureKind.NEED_VIP   // 试听片段 = 权益不足，处置同「要会员」
    QqRejection.AUTH_EXPIRED -> ResolveFailureKind.AUTH_EXPIRED
    QqRejection.NO_FILE -> ResolveFailureKind.UNKNOWN
    QqRejection.NETWORK -> ResolveFailureKind.NETWORK
    QqRejection.UNKNOWN -> ResolveFailureKind.UNKNOWN
}
