/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.recognize

/** 识曲输入形态：麦克风给 PCM，文件/调试给编码字节。 */
sealed interface RecogInput {
    data class Pcm(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int = 1,
    ) : RecogInput

    data class Encoded(
        val bytes: ByteArray,
        val mime: String,
        /** 音频时长（毫秒）。网易云识曲端点必须显式上报 `duration`。 */
        val durationMs: Long = 0L,
    ) : RecogInput
}

/** 识别失败分类。**必须细分**：`NO_MATCH` 与 `PROTOCOL_CHANGED` 的处理完全不同。 */
enum class RecogFailure {
    /** 没录到声音（全静音）。 */
    SILENT_INPUT,

    /** 权限没给。 */
    PERMISSION_DENIED,

    /** 采集设备被占用 / 初始化失败。 */
    RECORDER_UNAVAILABLE,

    NO_NETWORK,
    TIMEOUT,

    /** 服务端 5xx / 明确的可重试错误。 */
    SERVER_ERROR,

    /** 额度用尽：**绝不重试**。 */
    QUOTA_EXCEEDED,

    /** 指纹算法或接口协议变了（逆向协议的专属失败态，不能和 NO_MATCH 混）。 */
    PROTOCOL_CHANGED,

    /** 服务端明确说这不是已知曲目。 */
    NO_MATCH,
}

/** 识别结果：只有元数据，**不含**任何播放地址或凭证。 */
data class RecogMatch(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    /** 提供方给的置信度（0..1）；Shazam 系协议给的是匹配分数，归一到这个区间。 */
    val confidence: Float = 0f,
    /** 提供方名称，仅用于诊断与文案。 */
    val provider: String = "",
    /** 提供方返回的曲目 id（仅诊断用，**不**当作跨源身份）。 */
    val providerTrackId: String = "",
)

sealed interface RecogOutcome {
    data class Matched(val match: RecogMatch) : RecogOutcome
    data class Failed(val failure: RecogFailure, val detail: String = "") : RecogOutcome
}

/** 可插拔的识别提供方。第一版只有指纹直连；AudD / ACRCloud / ShazamKit 后续按同一接口接入。 */
interface RecognitionProvider {
    val name: String

    /** 是否具备运行条件（凭证、网络等）。UI 据此决定是否显示该入口，而不是点了才报错。 */
    fun isAvailable(): Boolean

    suspend fun recognize(input: RecogInput): RecogOutcome
}

/** 跨源候选（由聚合搜索产出，交给 [CandidateRanker] 排序）。 */
data class RecogCandidate(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0L,
    /** `NameNormalizer.editionKinds` 的结果（原唱为空集）。 */
    val editionKinds: Set<String> = emptySet(),
    /** 服务端声明的原唱/翻唱（`TrackVersionTag`，QQ/B站通常为 UNKNOWN）。 */
    val versionTag: String = "UNKNOWN",
    /** 该源能不能播（PLAYABLE / MEMBER_ONLY / NO_COPYRIGHT / UNKNOWN）。 */
    val availability: String = "UNKNOWN",
    /** 来源音源 key（netease / qqmusic / bilibili），仅用于分组展示。 */
    val source: String = "",
    val sourceId: String = "",
)

/** 候选等级。LOW 以下不展示（宁可"没找到"也不给错的）。 */
enum class MatchConfidence { HIGH, MEDIUM, LOW, REJECT }

data class RankedCandidate(
    val candidate: RecogCandidate,
    val confidence: MatchConfidence,
    val score: Int,
)
