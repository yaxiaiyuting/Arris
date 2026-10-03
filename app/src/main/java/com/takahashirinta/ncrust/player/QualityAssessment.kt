/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * 修改说明：
 *   - v1.2.0 · A3：音质状态判定。旧逻辑拿「服务端返回的 level 字符串」比档位序号，
 *     会把两种正常情况误报成「已降级」：
 *       ① level 标签写 lossless、实际文件是 Hi-Res（实测 br=1,685,762，type=flac）；
 *       ② 请求杜比全景声时服务端给了 jyeffect —— 沉浸声是不同容器/格式，不是降级。
 *     现在改为按**实际文件参数**（br/type）推算真实档位，再结合该曲的档位上限
 *     （privilege.maxBrLevel）区分「无权限」与「该曲无此档位」。
 *   - v1.3.0 · B4：能力上限归一化。服务端存在不在档位表里的能力标签（sky／沉浸环绕声），
 *     旧逻辑拿它查 LEVELS 得到 -1，于是选 hires/无损播 sky 上限的曲子会落到兜底的
 *     「已降级」——而服务端给的本来就是该曲最高源（exhigh 320k mp3），属于误报。
 *     现在 sky 归一化成 exhigh 参与比较，并用 capFromAlias 标记"上限来自别名"，
 *     避免归一化后被误判成「无权限」。展示档位同时改为按**请求档位**封顶，
 *     未知标签不会再被推成杜比/母带。
 *
 * 判定依据（v1.3.0 · B4 实测确认，**改判定前必读，顺序反了就会误报**）：
 *   实测档位 measuredIdx 由 br/type 推算；capIdx 是该曲上限（privilege.maxBrLevel，
 *   别名先归一化）。当 measuredIdx < requestedIdx（实际文件低于请求档位）时，看上限：
 *     ① capIdx > measuredIdx → 「无权限」(NO_ENTITLEMENT)
 *        服务端明明给得出比现在更高的一档，却没给 —— 是账号/版权没放行。
 *        例：请求 lossless、只给 exhigh 320k mp3，而 cap=hires。
 *     ② capIdx == measuredIdx → 「该曲无此档位」(SONG_LACKS_TIER)
 *        实测这一档就是该曲的天花板，再往上没有文件可给。
 *        例：cap=sky 的曲子归一化后 = exhigh = 实测（sky 没有独立音频文件）。
 *   注意：**不是**"capIdx 与 requestedIdx 谁高"决定二者 —— cap 介于 measured 与
 *   requested 之间时（如请求 hires、实测 exhigh、cap=lossless），仍是①「无权限」。
 *   capFromAlias 是这条规则的例外保护：上限来自别名（sky）时不能按①断言"服务端没给"，
 *   因为该标记只代表"没有更高档位"，不代表账号拿到了 exhigh 以上的权限。
 *   回归用例见 app/src/test/.../QualityAssessmentTest.kt（cap=hires 对照 cap=sky）。
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
    ): QualityVerdict {
        val levels = QualityLadder.LEVELS
        val requestedIdx = levels.indexOf(requested).takeIf { it >= 0 } ?: 0
        val grantedIdx = levels.indexOf(granted)
        // 能力上限归一化：sky 这类别名按等价基础档位（exhigh）参与比较，
        // 否则 capIdx=-1 会落到兜底的「已降级」，把"该曲没有更高档位"误报成服务端降级。
        val capIdx = capabilityIndex(songMaxLevel)
        // 上限来自别名时，即使归一化后仍 >= 请求档位，也不能断言"服务端没给"：
        // sky 只是"没有更高档位"的标记，不代表账号拿到了 exhigh 以上的权限。
        val capFromAlias = songMaxLevel != null &&
            levels.indexOf(songMaxLevel) < 0 && capIdx != null
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

        val status = when {
            // 拿不到实际文件参数：不妄下结论，保持安静 —— **除非**档位本身就是从文件反推的。
            //
            // v2.2.1 · P0：QQ 的档位来自**真正取回的文件名前缀**（`RS01…flac` → hires），
            // 它自己就是证据，不是「服务端说它给了什么」。而 QQ 的 FLAC 档没有 br 字段
            // （编一个数字会污染 measuredLevel，见 QqQuality.knownBitrateOf），
            // 于是旧规则下「请求超清母带、实际 Hi-Res」落在这一支：既不是降级、也没有提示。
            // 实测用户报障原话就是「开了母带只能出极高，和免费用户没区别」—— 沉默同样是在撒谎。
            measuredIdx < 0 -> {
                if (levelFromFile && grantedIdx >= 0 && grantedIdx < requestedIdx) {
                    QualityStatus.DOWNGRADED
                } else {
                    QualityStatus.NORMAL
                }
            }
            // 实际文件不低于请求档位（含"标签写低了、文件其实是高解析"）
            measuredIdx >= requestedIdx -> QualityStatus.NORMAL
            // 沉浸声换格式：请求杜比/环绕，实际拿到 >= 无损的沉浸声文件，不算降级
            requested in IMMERSIVE_TIERS && measuredIdx >= levels.indexOf("lossless") -> QualityStatus.NORMAL
            // 该曲自己就没有这个档位（含 sky 上限归一化后仍低于请求档位的情况）
            capIdx != null && capIdx < requestedIdx -> QualityStatus.SONG_LACKS_TIER
            // 该曲有（且该上限不是别名），但服务端没给
            capIdx != null && capIdx >= requestedIdx && !capFromAlias -> QualityStatus.NO_ENTITLEMENT
            // 其余才是真降级（服务端临时策略、解码器受限等）
            else -> QualityStatus.DOWNGRADED
        }
        return QualityVerdict(displayIdx, status)
    }
}
