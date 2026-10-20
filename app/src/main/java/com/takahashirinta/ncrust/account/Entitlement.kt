package com.takahashirinta.ncrust.account

import com.google.gson.annotations.SerializedName

/**
 * 单曲播放权益判定（纯函数）。
 *
 * 规格来源：上游 981e9b3 的 `spec/api/entitlement.md` 与
 * `spec/fixtures/entitlement/playback.json`（本 Fork 只随包引入夹具，不复制上游文档）。
 *
 * 背景：网易对未登录 / 非会员**不报错**，而是返回 200 + 45 秒试听 URL、把音质悄悄降到
 * `privilege.pl`、或 404 + `url: null`。客户端照单全收，用户看到的就是
 * 「放到一半断了 / 音质设置不生效 / 点了没反应」，自然归咎于 Ncrust 本身。
 *
 * ## 与 Arris 既有实现的边界（移植时最重要的一条）
 *
 * - 档位表**复用** `player.QualityLadder`（Arris 是 8 档，含 jymaster；上游是 7 档）。
 *   夹具只涉及两套表共同的前 7 档，因此同一份夹具在两边都成立。
 * - 歌曲 `privilege` **复用** `network.SongPrivilege`（Arris 自 v2.3.0 起就用它做版权判定，
 *   并随队列 / 收藏经 Gson 落盘）。这里用 typealias 保留上游 `account.SongPrivilege` 的
 *   书写方式，不新增第二份同形 DTO —— 两份 DTO 必然漂移，而且会让 `SongItem.privilege`
 *   与判定函数的入参类型对不上。
 * - 本文件不碰 Android / org.json：夹具用 Gson 解析后直接喂进来，与线上链路
 *   （同样是 Gson 解析响应体）共用同一套字段映射。
 */

/** 阶梯内档位的索引；阶梯外（服务端可能返回 `sky` 等）返回 -1，表示「不可比较」。 */
private fun qualityIndexOf(level: String?): Int =
    if (level == null) -1 else com.takahashirinta.ncrust.player.QualityLadder.LEVELS.indexOf(level)

/** 上游 API 的兼容别名：判定用到的 privilege 就是 Arris 既有的那个 DTO。 */
typealias SongPrivilege = com.takahashirinta.ncrust.network.SongPrivilege

/**
 * 判定结果的原因。
 *
 * trial / capped 只用 `VIP` `ALBUM` `LOGIN`，unavailable 用全集（含 `COPYRIGHT` `UNKNOWN`）——
 * 用一个枚举是为了让界面只写一处「原因 → 文案」映射，各分支的合法取值由 [classifyPlayback]
 * 保证。
 */
enum class EntitlementReason(val wire: String) {
    VIP("vip"),
    ALBUM("album"),
    LOGIN("login"),
    COPYRIGHT("copyright"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): EntitlementReason? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * 一次取链的判定结果。
 *
 * - [Full] 可完整播放；[Full.capped] 为真时实际拿到的是更低的档位（账号权限不足，
 *   不是设备原因——FLAC 门控造成的降级在调用前就已发生，不算 capped）。
 * - [Trial] 只能播 [Trial.seconds] 秒试听片段。
 * - [Unavailable] 所有档位都拿不到 URL，必须停下说明原因（Arris 的 ResolveFailure
 *   规则把「自动跳歌」限定在结构性缺失，所以这里不直接决定跳歌）。
 */
sealed interface PlaybackEntitlement {
    data class Full(
        val capped: Boolean,
        val cappedTo: String? = null,
        val cappedReason: EntitlementReason? = null
    ) : PlaybackEntitlement

    data class Trial(
        val seconds: Int,
        val reason: EntitlementReason
    ) : PlaybackEntitlement

    data class Unavailable(
        val reason: EntitlementReason
    ) : PlaybackEntitlement
}

/** `song/enhance/player/url/v1` 的 `data[i]`，只保留判定用得上的字段。 */
data class PlaybackItem(
    @SerializedName("url") val url: String? = null,
    @SerializedName("code") val code: Int? = null,
    @SerializedName("level") val level: String? = null,
    @SerializedName("fee") val fee: Int? = null,
    @SerializedName("freeTrialInfo") val freeTrialInfo: FreeTrialInfo? = null,
    @SerializedName("freeTrialPrivilege") val freeTrialPrivilege: FreeTrialPrivilege? = null
)

data class FreeTrialInfo(
    @SerializedName("start") val start: Int? = null,
    @SerializedName("end") val end: Int? = null
)

/**
 * 试听限制的细节。判定规则本身不用它（规则只看 `url` / `level` / `fee` / `freeTrialInfo`
 * 与歌曲详情的 `privilege.st`），带出来是为了日志与将来细文案（如 `cannotListenReason`）。
 */
data class FreeTrialPrivilege(
    @SerializedName("resConsumable") val resConsumable: Boolean? = null,
    @SerializedName("userConsumable") val userConsumable: Boolean? = null,
    @SerializedName("cannotListenReason") val cannotListenReason: Int? = null,
    @SerializedName("freeLimitTagType") val freeLimitTagType: String? = null
)

/**
 * 判定单曲播放结果（上游 981e9b3 的规则，三端一致）。
 *
 * @param requestedLevel 客户端**实际尝试的第一档**。调用方必须传 FLAC 门控之后的第一档，
 *   否则设备解不了 FLAC 造成的降级会被误判成「账号权限不足」。
 * @param sessionValid 会话是否 `Valid`（`Unknown` 不算 Valid）。它只影响原因文案，不影响能否播。
 * @param item 最后一次取链响应项；网络全失败时为 null，只能给 `unknown`。
 * @param privilege 歌曲详情里的 `privilege`，没有就传 null（规则已考虑）。
 */
fun classifyPlayback(
    requestedLevel: String,
    sessionValid: Boolean,
    item: PlaybackItem?,
    privilege: SongPrivilege?
): PlaybackEntitlement {
    // 一次响应都没有（全档位网络失败）：没有任何依据区分版权/会员，不编原因。
    if (item == null) return PlaybackEntitlement.Unavailable(EntitlementReason.UNKNOWN)

    val url = item.url
    // fee 优先取歌曲详情的（更权威），没有详情时退回取链响应项里的。
    val fee = privilege?.fee ?: item.fee

    if (!url.isNullOrEmpty()) {
        val trial = item.freeTrialInfo
        if (trial != null) {
            // 有 url 但带 freeTrialInfo = 官方给的试听片段（实测 VIP 歌对非会员返回 45 秒）。
            val seconds = ((trial.end ?: 0) - (trial.start ?: 0)).coerceAtLeast(0)
            val reason = when {
                fee == 4 -> EntitlementReason.ALBUM
                !sessionValid -> EntitlementReason.LOGIN
                else -> EntitlementReason.VIP
            }
            return PlaybackEntitlement.Trial(seconds, reason)
        }
        val requestIdx = qualityIndexOf(requestedLevel)
        val actualIdx = qualityIndexOf(item.level)
        // 只有两端都在档位表内才可比；阶梯外档位（如 sky）不参与比较、不算 capped。
        val capped = requestIdx >= 0 && actualIdx >= 0 && actualIdx < requestIdx
        return PlaybackEntitlement.Full(
            capped = capped,
            cappedTo = if (capped) item.level else null,
            cappedReason = if (capped) {
                if (sessionValid) EntitlementReason.VIP else EntitlementReason.LOGIN
            } else null
        )
    }

    val reason = when {
        (privilege?.st ?: 0) < 0 -> EntitlementReason.COPYRIGHT
        fee == 4 -> EntitlementReason.ALBUM
        fee == 1 -> if (sessionValid) EntitlementReason.VIP else EntitlementReason.LOGIN
        else -> EntitlementReason.UNKNOWN
    }
    return PlaybackEntitlement.Unavailable(reason)
}
