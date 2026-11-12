/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 */

package com.takahashirinta.ncrust.player

/** 音质标签后缀状态。NORMAL 时不加任何后缀。 */
enum class QualityStatus {
    /** 实际文件质量不低于请求档位（含"标签写低了、文件其实是高解析"）。 */
    NORMAL,

    /** 实际文件低于请求档位，但该曲本身有这个档位 → 账号/版权没给到。 */
    NO_ENTITLEMENT,

    /** 该曲本身就没有请求的档位（privilege.maxBrLevel 低于请求档位）。 */
    SONG_LACKS_TIER,

    /** 其余真实降级（服务端临时策略、解码器受限等）。 */
    DOWNGRADED,
}

/** Account-scoped evidence from the same provider request as the URL. */
data class QualityAccessEvidence(
    val accountRevision: Long,
    val playableLevel: String? = null,
    val observed: Map<String, QualityStatus> = emptyMap(),
)

data class QualityVerdict(val displayIndex: Int, val status: QualityStatus)

object QualityAssessment {
    /** 沉浸声档位：与立体声档位不是同一坐标系，不能按 br 直接比较。 */
    private val IMMERSIVE_TIERS = setOf("dolby", "jyeffect")

    /**
     * 档位**别名**：服务端会给出不在 [QualityLadder.LEVELS] 里的能力标签
     * （目前只有 sky／沉浸环绕声）。它们不是独立音质、也没有独立的音频文件，
     * 只是在**更高档位缺失时**等价于某个基础档位，所以比较前必须先归一化。
     *
     * v1.3.0 实测（登录态 + PC 身份）：账号态 36 首 maxBrLevel=sky 的曲目，
     * privilege 里 maxbr/pl 上限就是 320000、song.sq / song.hr 均为 null ——
     * 这些曲子的最高源就是 exhigh 320k mp3，level=sky 与 exhigh 取回同一个文件
     * （CDN 回源 6/6 一致）。详见 AGENTS.md「Playback」。
     */
    private val CAPABILITY_ALIASES = mapOf("sky" to "exhigh")

    /** 把能力标签归一化成基础档位；不在档位表里且无别名时返回 null。 */
    private fun capabilityIndex(level: String?): Int? {
        if (level.isNullOrEmpty()) return null
        val levels = QualityLadder.LEVELS
        val idx = levels.indexOf(level)
        if (idx >= 0) return idx
        val alias = CAPABILITY_ALIASES[level] ?: return null
        return levels.indexOf(alias).takeIf { it >= 0 }
    }

    /** Missing catalog tiers and account restrictions answer different questions. */
    fun choiceStatus(
        level: String,
        songMaxLevel: String?,
        access: QualityAccessEvidence?,
        verifiedLevel: String? = null,
    ): QualityStatus {
        if (level == verifiedLevel) return QualityStatus.NORMAL
        val requested = capabilityIndex(level) ?: return QualityStatus.NORMAL
        val catalog = capabilityIndex(songMaxLevel)
        if (catalog != null && catalog < requested) return QualityStatus.SONG_LACKS_TIER
        access?.observed?.get(level)?.let { return it }
        val playable = if (access?.playableLevel == "none") -1 else capabilityIndex(access?.playableLevel)
        if (catalog != null && playable != null && playable < requested) return QualityStatus.NO_ENTITLEMENT
        return QualityStatus.NORMAL
    }

    // 实测锚点（2026-09，登录态）：母带 4.7–5.8 Mbps、jyeffect 2.8–3.1 Mbps、
    // Hi-Res 1.69 Mbps、无损 0.87–0.92 Mbps，均为 flac；320k 为 mp3。
    private const val MASTER_BR = 4_000_000L
    private const val HIRES_BR = 1_400_000L
    private const val LOSSLESS_BR = 700_000L

    /**
     * 由实际文件参数推算真实档位；拿不到（br=0 / 未知容器）返回 null，调用方据此不下结论。
     */
    fun measuredLevel(br: Long, type: String): String? = when (type.lowercase()) {
        "mp4" -> "dolby"
        "flac" -> when {
            br >= MASTER_BR -> "jymaster"
            br >= HIRES_BR -> "hires"
            br >= LOSSLESS_BR -> "lossless"
            else -> null
        }
        "mp3" -> when {
            br >= 300_000 -> "exhigh"
            br >= 190_000 -> "higher"
            br > 0 -> "standard"
            else -> null
        }
        else -> null
    }

    /**
     * 实际码率（bit/s）→ 播放界面上的紧凑文案（`1411kbps`）；**拿不到时返回 null**。
     *
     * ## 为什么是 null 而不是 `0kbps`
     *
     * 那串字符会被当成一次测量结果读。而拿不到 br 的路径不少：离线缓存兜底（br 恒为 0，
     * 见 `PlayerViewModel.recallOfflineCache`）、B 站音频区（响应里只有文件字节数
     * `size`，没有码率字段）、取链失败、以及不带码率字段的老缓存条目。
     * 在这些路径上写「0kbps」比什么都不写更糟 —— 后者只是没信息，前者是**错误的信息**。
     * 小于 1 kbps 的畸形值（>0 但 <1000）同理按「拿不到」处理。
     *
     * ## 为什么向下取整（整除）而不是四舍五入
     *
     * 显示值必须与档位判据同向，否则同一行文字会自相矛盾：判据里
     * `br >= HIRES_BR(1,400,000)` 才算高解析，而 1,399,600 四舍五入会写成 `1400kbps` ——
     * 数字看起来已经够到高解析阈值，档位标签却写「无损」。向下取整得到 `1399kbps`，
     * 与判据一致。代价是每个值最多少写 999 bit/s（≈0.07%），用户读不出来。
     *
     * ## 为什么不进 i18n
     *
     * 这是「数字 + 单位」，不是句子，8 个语言下完全一样。而 `Strings` 主构造器已被
     * `StringsConstructorBudgetTest` 精确钉在 **141** 个参数（预算 150）——
     * 为一条不需要翻译的字符串去动它、再补齐 8 个语言文件，是纯粹的负担。
     */
    fun bitrateLabel(br: Long): String? {
        val kbps = br / 1000L
        return if (kbps > 0L) "${kbps}kbps" else null
    }

    /**
     * 实际文件是否**可能**低于请求档位 —— 只有这种情况才值得再花一次请求去问该曲的
     * 档位上限（取链是开播关键路径，平时不要多加往返）。
     */
    fun needsSongCapability(requested: String, br: Long, type: String): Boolean {
        val levels = QualityLadder.LEVELS
        val measuredIdx = measuredLevel(br, type)?.let { levels.indexOf(it) } ?: return false
        val requestedIdx = levels.indexOf(requested).takeIf { it >= 0 } ?: return false
        if (measuredIdx >= requestedIdx) return false
        // 沉浸声换格式本来就不算降级（见 assess 的同款豁免），这里一并跳过，
        // 省掉一次纯属浪费的往返 —— 取链在开播关键路径上。
        if (requested in IMMERSIVE_TIERS && measuredIdx >= levels.indexOf("lossless")) return false
        return true
    }

    /**
     * @param requested 本次实际请求的 level（降档重试时是重试档位，不是用户偏好）
     * @param granted 服务端返回的 level 字符串（只是标签，可能与实际文件不符）
     * @param br / [type] 实际文件参数
     * @param songMaxLevel 该曲 privilege.maxBrLevel；拿不到传 null
     * @param levelFromFile v2.2.1：`granted` 是不是**由实际文件反推**出来的（QQ 前缀 / 离线 key），
     *   而不是服务端标签。只有它是 true 时，才允许在拿不到 br/type 的情况下断言「降级了」。
     */
    fun assess(
        requested: String,
        granted: String,
        br: Long,
        type: String,
        songMaxLevel: String?,
        levelFromFile: Boolean = false,
        access: QualityAccessEvidence? = null,
    ): QualityVerdict {
        val levels = QualityLadder.LEVELS
        val requestedIdx = levels.indexOf(requested).takeIf { it >= 0 } ?: 0
        val grantedIdx = levels.indexOf(granted)
        val measuredIdx = measuredLevel(br, type)?.let { levels.indexOf(it) } ?: -1

        // 展示档位：**实测到的实际文件说了算**（v2.1.4）。
        //
        // 历史（v1.2.0–v2.1.3）取的是 `maxOf(标签, 实测)`，理由是「标签写低了不该跟着写低」。
        // 那条理由本身没错，但只覆盖了一半：**标签写高**时它也照样信，于是会出现
        // 「文件是 320k mp3，界面写着超清母带」。
        //
        // 这不是理论风险，是 2026-09 真机实测到的用户报障形态：账号权益只到 HQ
        // （服务端 `music_lev_sq=0`，见 vip_login_base），选了「超清母带」被如实降级到 320k，
        // 而界面一直挂着「超清母带」—— 用户看到的名字与实际听到的东西不符，
        // 只能得出「开了母带却和免费用户没区别」这个结论，无从判断是没权限还是坏了。
        //
        // 规则改成两条，两个方向都诚实（v2.1.4）：
        //   ① **拿得到实测参数就以实测为准**：标签写低（label=lossless / 文件 Hi-Res）与
        //      标签写高（label=jymaster / 文件 320k）都按文件显示，且**不受请求档位封顶** ——
        //      「请求母带、实际 320k」的唯一诚实写法是极高，不是母带；
        //   ② 拿不到实测参数（br=0 且容器未知）时才退回标签，并用请求档位封顶 ——
        //      那时没有任何文件证据可以反驳标签，只能信它。
        //
        // 为什么不再用 v1.2.0–v2.1.3 的 `maxOf(标签, 实测)`：那条规则对「标签写低」是对的，
        // 对「标签写高」是错的，而**错的这一半恰好就是用户能看见的那一半** ——
        // 界面把母带挂在脸上、耳朵听到 320k，用户只能得出「开了母带和免费用户没区别」。
        val floorIdx = if (grantedIdx >= 0) grantedIdx else requestedIdx
        val displayIdx = if (measuredIdx >= 0) {
            measuredIdx.coerceIn(0, levels.lastIndex)
        } else {
            floorIdx.coerceIn(0, maxOf(requestedIdx, 0))
        }

        val knownDowngrade = if (measuredIdx >= 0) {
            measuredIdx < requestedIdx &&
                !(requested in IMMERSIVE_TIERS && measuredIdx >= levels.indexOf("lossless"))
        } else {
            levelFromFile && grantedIdx in 0 until requestedIdx
        }
        val status = if (knownDowngrade) {
            choiceStatus(requested, songMaxLevel, access).takeUnless { it == QualityStatus.NORMAL }
                ?: QualityStatus.DOWNGRADED
        } else QualityStatus.NORMAL
        return QualityVerdict(displayIdx, status)
    }
}
