/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v3.1.0 · B：B 站音源的**偏好与档位映射**。纯逻辑（映射部分无 Android），JVM 可单测。
 */

package com.takahashirinta.ncrust.bili

import android.content.Context
import android.content.SharedPreferences
import com.takahashirinta.ncrust.player.QualityLadder

/**
 * B 站音频区的 `qn`（音质档位）与码率。
 *
 * ## 这是**协议常量**，不是我们的档位表
 *
 * 本应用自己的档位名是 `standard` / `higher` / `exhigh` / `lossless` / `hires` / …（8 档，
 * 见 `QualityLadder.LEVELS`）。B 站音频区只认 0~3 这四个 `qn`，且**同一个 `qn`
 * 在登不登录时的实际码率不同**（匿名多半被服务端压到 192K）。
 * 所以 [mapFromLevel] 只回答「该请求哪个 qn」，**实际拿到什么**一律以响应里的
 * `quality` / 文件后缀为准（`SongUrlResult.levelFromFile` 那条既有纪律）。
 */
enum class BiliQn(val qn: Int, val label: String, val approxKbps: Int) {
    Q128(0, "128K", 128),
    Q192(1, "192K", 192),
    Q320(2, "320K", 320),

    /** FLAC（需要大会员；匿名请求会被服务端降级）。 */
    FLAC(3, "FLAC", 0),
    ;

    companion object {
        fun of(qn: Int): BiliQn? = values().firstOrNull { it.qn == qn }
    }
}

/**
 * 本应用档位 ↔ B 站 `qn` 的映射，以及**降级阶梯**（纯逻辑，JVM 可单测）。
 *
 * ## 映射规则
 *
 * | 本应用档位 | B 站 `qn` | 理由 |
 * |---|---|---|
 * | `standard`（压缩） | 0 | 128K |
 * | `higher`（较好） | 1 | 192K |
 * | `exhigh`（更好） | 2 | 320K |
 * | `lossless`（无损） | 3 | FLAC |
 * | `hires` / `jyeffect` / `jymaster` / `dolby` | 3 | **B 站没有比 FLAC 更高的档** —— 映射到它自己的最高档，而不是「不请求」 |
 * | 未知字符串 | 3 → 逐级下探 | 与 `SongUrlFetcher` 的 `else` 分支同一取舍：先试最高、再降 |
 *
 * ## 为什么最高档是 3 而不是「匿名只能 1」
 *
 * 「匿名只能拿 192K」是**服务端的结论**，不是客户端的常量：用户登录（未来的版本）
 * 或有大会员时同一个 `qn=3` 能拿到 FLAC。把它写死成 1 等于把将来的登录能力一起锁死。
 * 拿不到时由 [fallbackLadder] 逐级下探 —— 失败处理有界（铁律 5）。
 */
object BiliQuality {

    /** 本应用档位 → 首选 `qn`。 */
    fun mapFromLevel(level: String): BiliQn = when (level) {
        "standard" -> BiliQn.Q128
        "higher" -> BiliQn.Q192
        "exhigh" -> BiliQn.Q320
        "lossless", "hires", "jyeffect", "jymaster", "dolby" -> BiliQn.FLAC
        else -> BiliQn.FLAC
    }

    /**
     * 降级阶梯：从 [mapFromLevel] 的结果逐级降到 128K，**去重、有序**。
     *
     * 与 `SongUrlFetcher.fetch` 的 `fallbackLevels` 同一条纪律：循环有界、
     * 每一档只试一次、最低档兜底。
     */
    fun fallbackLadder(level: String): List<BiliQn> {
        val start = mapFromLevel(level).qn
        return (start downTo 0).mapNotNull { BiliQn.of(it) }
    }

    /**
     * 服务端返回的 `quality` 是否**真的是**我们请求的那一档。
     *
     * 用途只有一个：把「请求 FLAC、拿到 192K」这件事**如实**标出来
     * （`SongUrlResult.levelFromFile`），而不是按请求档位自欺。
     */
    fun actualLabel(returnedQn: Int, fallback: String): String =
        BiliQn.of(returnedQn)?.label ?: fallback

    // ---------------------------------------------------------------- 视频 DASH 选流（v3.4.8） ----

    /**
     * 视频 DASH 那一侧：从**全部**音频候选里挑一条。
     *
     * ## 这是 v3.4.8 修的缺陷的落点
     *
     * 修复前视频轨**完全不看请求档位**：`resolveUrl` 把 `dash.audio[]` 里带宽最高的
     * 那条直接交出去。而 B 站的大会员 Hi-Res 无损**不在 `dash.audio[]` 里**
     * （在 `dash.flac.audio`），所以那个写法在**任何**档位下都只能拿到 192K AAC ——
     * 「无论如何都播放的是普通版本」就是这一行造成的。
     *
     * ## 选择规则（按 [level] 分派，不是按码率大小分派）
     *
     * | 请求档位 | 首选 | 次选 | 再次 |
     * |---|---|---|---|
     * | `dolby` | 杜比 | FLAC | AAC（最高带宽） |
     * | `lossless` / `hires` / `jyeffect` / `jymaster` | **FLAC** | 杜比 | AAC（最高带宽） |
     * | `standard` / `higher` / `exhigh` / 未知 | AAC（最高带宽） | FLAC | 杜比 |
     *
     * 三条纪律：
     *
     * 1. **按 `kind` 分派，不按 `br` 比大小。** `br` 在这三支之间**不是同一把尺子**：
     *    `dash.audio[]` 内部的 64K/132K/192K 是一条真正的音质阶梯（所以**只有那一支内部**
     *    才按带宽降序），而 `flac` 与 `dolby` 是**格式不同**的另外两条轨 ——
     *    「谁的数字大」并不回答「用户要的是哪一个」。拿单一数字给三支排序，
     *    等于把「格式选择」当成「码率比较」处理。（实测锚点：flac = 2247494 bps、
     *    AAC 顶档 = 213610 bps；杜比的码率本次**没有实测样本**，见
     *    `BiliParse.parseDashAudios` ② 关于形状未验证的说明。）
     * 2. **用户只要有损档位时不去碰 FLAC。** 选 320K 的用户是在明确省流量；
     *    偷偷给他一条 2.2 Mbps 的流是**违背用户意图**，不是「升级」。
     * 3. **列表里有什么就用什么兜底。** 首选项不存在时按次选走，全都没有才返回 null
     *    —— 「请求无损、该视频只有 AAC」应当播 AAC 并如实标「已降级」，
     *    而不是判定这首歌放不出来。
     *
     * @param preferFlac 用户开关「优先 Hi-Res FLAC 而非 AAC」（v3.4.8 新增）。
     *   关掉时 FLAC 被排到 AAC **之后** —— 它只影响「两者都有时选哪个」，
     *   不影响「只有 FLAC 时能不能播」（那种情况仍然播 FLAC，否则等于无声抗议）。
     */
    fun selectStream(
        streams: List<BiliStream>,
        level: String,
        preferFlac: Boolean = true,
    ): BiliStream? {
        if (streams.isEmpty()) return null
        val wantsLossless = level in LOSSLESS_TIERS || level == "dolby"
        // 首选顺序：无损档位是 FLAC→杜比→AAC，有损档位是 AAC→FLAC→杜比。
        val order = if (wantsLossless) {
            if (level == "dolby") {
                listOf(BiliAudioKind.DOLBY, BiliAudioKind.FLAC, BiliAudioKind.AAC)
            } else if (preferFlac) {
                listOf(BiliAudioKind.FLAC, BiliAudioKind.DOLBY, BiliAudioKind.AAC)
            } else {
                listOf(BiliAudioKind.DOLBY, BiliAudioKind.AAC, BiliAudioKind.FLAC)
            }
        } else {
            listOf(BiliAudioKind.AAC, BiliAudioKind.FLAC, BiliAudioKind.DOLBY)
        }
        for (kind in order) {
            // `streams` 已由 `parseDashAudios` 保证「AAC 内部按带宽降序」，
            // 这里取**第一条**即该支里最好的那一条。
            streams.firstOrNull { it.kind == kind }?.let { return it }
        }
        return streams.firstOrNull()
    }

    /** 无损及以上的档位名（与 `SongUrlFetcher.FLAC_TIERS` 同源，但这里只用于**选流**）。 */
    private val LOSSLESS_TIERS = setOf("lossless", "hires", "jyeffect", "jymaster")

    /**
     * 一条流**实际**是什么档位 —— 由 [BiliStream.kind] 与码率反推。
     *
     * ## 为什么必须有它：`levelFromFile = true` 不许自相矛盾
     *
     * `SongUrlResult.levelFromFile = true` 的语义是「`actualLevel` 是**由实际拿到的东西
     * 推出来的**，不是服务端标签」。而 v3.4.7 及以前，视频轨这条路上写的是
     * `actualLevel = requested?.name ?: "bili-dash"` —— 既不是从文件推的（`requested`
     * 就是请求档位），`"bili-dash"` 更是 `QualityLadder` 里**不存在**的字符串
     * （`QualityAssessment` 拿它去 `levels.indexOf` 得到 -1，于是所有判定都退化成
     * 「安静」—— 请求无损、实际 132K AAC 时界面上不显示任何降级提示）。
     *
     * 修复后这里给出的是**真实档位**，三条判据与 `QualityAssessment.measuredLevel`
     * 的锚点一致（同一份实测数据，不是另立一套刻度）：
     *
     * | kind | 判据 | 结果 |
     * |---|---|---|
     * | [BiliAudioKind.FLAC] | `br >= 1_400_000`（实测 Hi-Res 样本 2.03 Mbps） | `hires` |
     * | [BiliAudioKind.FLAC] | 其余（44.1 kHz 无损约 0.9 Mbps） | `lossless` |
     * | [BiliAudioKind.DOLBY] | 恒为 | `dolby` |
     * | [BiliAudioKind.AAC] | `br >= 190_000`（实测 30280 = 203786） | `higher` |
     * | [BiliAudioKind.AAC] | 其余（30232 = 102931 / 30216 = 43962） | `standard` |
     *
     * ⚠️ **AAC 永远拿不到 `exhigh`**：B 站视频 DASH 的音轨上限就是 192K（30280），
     * 320K 只存在于音频区。所以「用户选 320K、视频轨给 192K AAC」是**降级**，
     * 由 `QualityAssessment` 如实标出 —— 这正是修复前缺的那条信息。
     */
    fun levelOf(stream: BiliStream): String = when (stream.kind) {
        BiliAudioKind.FLAC -> if (stream.br >= HIRES_BR) "hires" else "lossless"
        BiliAudioKind.DOLBY -> "dolby"
        BiliAudioKind.AAC -> if (stream.br >= HIGHER_BR) "higher" else "standard"
    }

    /** 实测锚点：Hi-Res 样本 2247494 / 3154514 bps（96 kHz FLAC）。与 `QualityAssessment` 同源。 */
    private const val HIRES_BR = 1_400_000L

    /** 实测锚点：30280 = 203786 bps（192K AAC-LC）。低于它的都是 132K / 64K 档。 */
    private const val HIGHER_BR = 190_000L

    /**
     * **音频区**文件名反推出的档位（`-320k.m4a` → `exhigh`）。
     *
     * ## 为什么音频区也需要它
     *
     * 修复前音频区这条路的 `actualLevel` 写的是**请求的 qn 名字**，
     * 而服务端会**静默降级**：请求 `qn=3`（FLAC）在拿不到无损时返回
     * `type:2` + `-320k.m4a`。于是界面按请求档位显示「无损」、耳朵听到 320K ——
     * 与 v2.1.4 修掉的「标签写高」是**同一个缺陷形状**，只是发生在 B 站这一侧。
     *
     * 音频区的真实档位判据是**文件名**（`BiliParse.qualityLabelOfFileName`），
     * 那是服务端自己写进去的，比任何请求参数都可信。这里把它翻回本应用的档位名。
     *
     * 返回 null（`未知` 标签）时调用方**退回请求档位** —— 与 `QualityAssessment`
     * 「拿不到证据时保持安静」的纪律一致，不猜。
     */
    fun levelOfAudioLabel(label: String): String? = when (label) {
        "FLAC" -> "lossless"
        "320K" -> "exhigh"
        "192K" -> "higher"
        "128K" -> "standard"
        else -> null
    }

    /**
     * 「从哪一档降下来的」：实际档位**严格低于**请求档位时返回请求档位，否则返回 null。
     *
     * 判据用 `QualityLadder.LEVELS` 的序号，不比较字符串 ——
     * 音频区那条路曾经拿 `"lossless" != "FLAC"` 当降级判据，
     * 于是**每一次成功拿到 FLAC 都被记成一次降级**（两套词表不可比）。
     * 这条函数是那条教训的落点：只有同一套刻度比出来的「更低」才算降级。
     */
    fun fallbackFrom(requested: String, actual: String): String? {
        val levels = QualityLadder.LEVELS
        val r = levels.indexOf(requested)
        val a = levels.indexOf(actual)
        if (r < 0 || a < 0) return requested.takeIf { it != actual }
        return requested.takeIf { a < r }
    }
}

/**
 * B 站音质的**用户上限**（v3.4.8，问题 3「开放参数供用户自行调整」）。
 *
 * ## 为什么需要它，而不是只跟随播放音质档位
 *
 * 应用已经有一个 8 档的全局音质设置（Wi-Fi / 移动网络各一份）。但 B 站这条链路上
 * 用户会遇到两个**全局档位表达不了**的诉求：
 *
 * - **「我有大会员，永远要 Hi-Res」** —— 全局档位在移动网络下默认是「较好 192K」，
 *   于是一个有大会员的用户在外面听歌时永远拿不到无损，而他并没有想省这点流量；
 * - **「别给我 FLAC，我就要 320K」** —— FLAC 码率实测可达 **2~3 Mbps**
 *   （`BV1BZbSzZEGT` 的 30251 是 **3154514 bps**），弱网下起播明显变慢。
 *   全局档位要改就得连 ncm / qm 一起改，而用户往往只想限制 B 站。
 *
 * ## 四个取值的语义边界（互不重叠，都可判定）
 *
 * | 取值 | 上限 | 典型用法 |
 * |---|---|---|
 * | [AUTO] | = 当前播放音质档位 | 默认。全局选无损才要无损 |
 * | [HIRES] | 放开到最高（无损 / Hi-Res / 杜比） | 大会员，永远要最好的 |
 * | [EXHIGH] | 320K | 有损封顶，永不请求 FLAC |
 * | [HIGHER] | 192K | 省流 |
 *
 * ⚠️ [AUTO] 与 [HIRES] 的区别**只在「全局档位低于无损」时才显现** ——
 * 这不是冗余：全局档位是「按网络类型」的通用偏好，而 [HIRES] 是
 * 「B 站这一个源」的覆盖。两者共存是本项目既有纪律（每个源可以有独立开关）。
 */
enum class BiliQualityCap(val key: String) {
    AUTO("auto"),
    HIRES("hires"),
    EXHIGH("exhigh"),
    HIGHER("higher"),
    ;

    companion object {
        val DEFAULT: BiliQualityCap = AUTO

        fun of(key: String?): BiliQualityCap? =
            values().firstOrNull { it.key.equals(key?.trim(), ignoreCase = true) }
    }
}

/**
 * 音质上限的**作用点**：把「全局档位」按用户设置换算成本音源的**有效档位**。
 *
 * ## 为什么它不是一个纯粹的「取小」函数
 *
 * 四个取值里有三个是夹取、一个是**抬升**，而且这不是设计失误 ——
 * 用户的原话诉求就是「自动 / 允许无损·Hi-Res / 仅 320K / 仅 192K 省流」：
 *
 * | 取值 | 换算式 | 为什么 |
 * |---|---|---|
 * | [BiliQualityCap.AUTO] | `level`（恒等） | 默认。全局档位说了算，新开关**不改变现状** |
 * | [BiliQualityCap.HIRES] | `max(level, "hires")` | 「我有大会员，永远要最好的」—— 全局在移动网络下默认是 192K，不抬升就永远拿不到无损 |
 * | [BiliQualityCap.EXHIGH] | `min(level, "exhigh")` | 封顶 320K，永不请求 FLAC（省流量 / 弱网起播） |
 * | [BiliQualityCap.HIGHER] | `min(level, "higher")` | 封顶 192K 省流 |
 *
 * 纯函数，JVM 可单测。三条性质都被单测钉住：
 *
 * 1. **幂等**：`apply(x) == apply(apply(x))` —— 取链失败重试与预载会重入；
 * 2. **[AUTO] 是恒等**：默认值对既有行为**零影响**（「新开关默认不改变现状」的机械保证）；
 * 3. **单调**：抬升档只在 [BiliQualityCap.HIRES] 上发生，且它**永不降低**任何档位；
 *    其余三档**永不升高**任何档位。两条方向互斥，不会出现「想省流却被升级」。
 *
 * 未知档位字符串（将来加了新档）**按用户选择直接给出目标档位**：
 * 例如 `apply("sky", EXHIGH) == "exhigh"`。拿不准时**服从用户的显式选择**，
 * 而不是退回「什么都不做」—— 后者会让一个新档位静默绕过用户的省流上限。
 */
object BiliQualityCapRules {

    fun applyCap(level: String, cap: BiliQualityCap): String {
        if (cap == BiliQualityCap.AUTO) return level
        val levels = QualityLadder.LEVELS
        val target = when (cap) {
            BiliQualityCap.AUTO -> level
            BiliQualityCap.HIRES -> "hires"
            BiliQualityCap.EXHIGH -> "exhigh"
            BiliQualityCap.HIGHER -> "higher"
        }
        val idx = levels.indexOf(level)
        val targetIdx = levels.indexOf(target)
        // 未知档位：直接给出用户选择的目标档位（见 KDoc 最后一段）。
        if (idx < 0) return target
        return when (cap) {
            BiliQualityCap.HIRES -> if (idx >= targetIdx) level else target
            else -> if (idx <= targetIdx) level else target
        }
    }
}

/**
 * B 站音源的**用户开关**（v3.1.0 · 铁律 24：必须支持独立开关，用户可选择是否启用）。
 *
 * ## 默认**关闭**（与 v3.0.0 五个动效开关「默认全开」相反）
 *
 * 两者的理由正好相反：
 * - 动效开关默认全开，是因为它们只影响**本机渲染**，「默认关」会让用户以为升级没变化；
 * - B 站音源默认关闭，是因为它是一个**外部平台依赖**：要发 Wbi 签名的请求、
 *   有自己的风控与 URL 时效。默认打开等于「用户什么都没做，App 就开始给 B 站发请求」。
 *   `RECOMMENDATIONS.md`（net-research）§4 把这条定成了接入约束。
 *
 * ## 为什么是 `@Volatile` + `init`，而不是每个调用点读一次 SharedPreferences
 *
 * 与 `RetrofitClient.currentCookie` 同一套服务定位器模式：
 * - 读取发生在**组合期**（音源角标、设置页开关状态）与**每通请求之前**，
 *   每次 `getSharedPreferences` + `getBoolean` 虽然便宜但会进组合期；
 * - 写只有两个入口（`MainActivity` 冷启动、设置页开关），
 *   两边都走 [setEnabled]，盘上与内存不会漂移。
 */
object BiliPrefs {

    const val PREFS_FILE = "ncrust_settings"

    /**
     * 开关的 prefs 键。**显式声明**（铁律 17：持久化结构字段名必须显式声明），
     * 且与 `SettingsRegistry` 里的 `key` 逐字相同 —— 由
     * `BiliPrefsTest.开关的键与设置注册表逐字一致` 钉住。
     */
    const val KEY_ENABLED = "bilibili_enabled"

    /** 默认关闭。 */
    const val DEFAULT_ENABLED = false

    /**
     * v3.4.8（问题 3）：B 站音质上限。
     *
     * 它与 [KEY_ENABLED] 一样是**用户可见的独立开关**，所以同样遵循
     * 「显式声明键名 + 与 `SettingsRegistry` 逐字相同」的纪律。
     */
    const val KEY_QUALITY_CAP = "bilibili_quality_cap"

    /** v3.4.8（问题 3）：无损/Hi-Res 存在时是否优先 FLAC 而不是 AAC。 */
    const val KEY_PREFER_FLAC = "bilibili_prefer_flac"

    /** v3.4.8（问题 2）：字幕语言偏好（[BiliSubtitleLang] 的 `key`）。 */
    const val KEY_SUBTITLE_LANG = "bilibili_subtitle_lang"

    /** 音质上限的默认值：跟随全局音质档位（新开关对既有行为零影响）。 */
    val DEFAULT_QUALITY_CAP: BiliQualityCap = BiliQualityCap.DEFAULT

    /**
     * 「优先 FLAC」的默认值：**开**。
     *
     * 默认开而不是关，理由是这个开关存在的语境：用户报障的原始描述是
     * 「B 站有大会员支持的 Hi-Res 音源，但我们无论如何都播放 mp3 普通版本」。
     * 一个默认关的「要不要 Hi-Res」开关会让这次修复在默认配置下**零可见变化**。
     * 反过来（默认开）的代价是弱网下多花几 MB —— 而用户随时可以在
     * 同一页把它关掉，且**全局档位仍是一道独立的闸**（选 192K 时这条开关根本不生效）。
     */
    const val DEFAULT_PREFER_FLAC = true

    /** 字幕语言默认值：跟随应用语言，其次中文（= 修复前的固定行为）。 */
    val DEFAULT_SUBTITLE_LANG: BiliSubtitleLang = BiliSubtitleLang.DEFAULT

    @Volatile
    private var enabled: Boolean = DEFAULT_ENABLED

    @Volatile
    private var qualityCap: BiliQualityCap = DEFAULT_QUALITY_CAP

    @Volatile
    private var preferFlac: Boolean = DEFAULT_PREFER_FLAC

    @Volatile
    private var subtitleLang: BiliSubtitleLang = DEFAULT_SUBTITLE_LANG

    /** 冷启动时调用一次（`MainActivity` 与 `RetrofitClient.init` 同处）。 */
    fun init(context: Context) {
        enabled = read(context)
        // v3.4.8：三个新偏好在**同一个入口**读盘 —— 分多处 init 会让
        // 「某个入口忘了初始化」变成一次静默的默认值回落（B 站行为与设置页显示不一致）。
        qualityCap = readQualityCap(context)
        preferFlac = readPreferFlac(context)
        subtitleLang = readSubtitleLang(context)
    }

    /** 当前是否启用。**读内存**，可以安全地在组合期调用。 */
    fun isEnabled(): Boolean = enabled

    /** 当前音质上限（内存镜像）。 */
    fun qualityCap(): BiliQualityCap = qualityCap

    /** 当前是否优先 FLAC（内存镜像）。 */
    fun preferFlac(): Boolean = preferFlac

    /** 当前字幕语言偏好（内存镜像）。 */
    fun subtitleLang(): BiliSubtitleLang = subtitleLang

    /**
     * 设置页的唯一写入入口：**先落盘、再改内存**。
     *
     * 顺序不能反：反过来的话写盘失败（极罕见但存在）会让内存说「开着」、
     * 下次冷启动又说「关着」—— 用户看到的是「开关自己弹回去了」。
     */
    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
        enabled = value
    }

    /** v3.4.8：音质上限的唯一写入口（先落盘、再改内存，同 [setEnabled]）。 */
    fun setQualityCap(context: Context, value: BiliQualityCap) {
        prefs(context).edit().putString(KEY_QUALITY_CAP, value.key).apply()
        qualityCap = value
    }

    /** v3.4.8：「优先 FLAC」的唯一写入口。 */
    fun setPreferFlac(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFER_FLAC, value).apply()
        preferFlac = value
    }

    /** v3.4.8：字幕语言的唯一写入口。 */
    fun setSubtitleLang(context: Context, value: BiliSubtitleLang) {
        prefs(context).edit().putString(KEY_SUBTITLE_LANG, value.key).apply()
        subtitleLang = value
    }

    /**
     * 直接从盘上读（**只给初始化和单测用**）。
     *
     * 缺键 = 默认关闭。注意判据是 `getBoolean(KEY, DEFAULT_ENABLED)` ——
     * 与「键存在但为 false」在语义上等价（都是关），这是有意的：
     * 参考实现里没有「从未设置过」这个第三态。
     */
    fun read(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    /**
     * v3.4.8：音质上限读盘。
     *
     * **非法取值一律回落默认**（与 `offline_cache_mb` 的既有纪律一致）：
     * 手改 prefs / 降级安装 / 将来删掉某一档，都不该让 B 站音源取链时
     * 拿着一个 `null` 上限往下走。回落是**静默**的 —— 但它回落到 [BiliQualityCap.AUTO]，
     * 也就是「与没有这个开关时完全一样」，不会改变任何既有行为。
     */
    fun readQualityCap(context: Context): BiliQualityCap =
        BiliQualityCap.of(prefs(context).getString(KEY_QUALITY_CAP, null)) ?: DEFAULT_QUALITY_CAP

    /** v3.4.8：「优先 FLAC」读盘。缺键 = 默认开。 */
    fun readPreferFlac(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PREFER_FLAC, DEFAULT_PREFER_FLAC)

    /** v3.4.8：字幕语言读盘。非法取值回落 [BiliSubtitleLang.AUTO]。 */
    fun readSubtitleLang(context: Context): BiliSubtitleLang =
        BiliSubtitleLang.of(prefs(context).getString(KEY_SUBTITLE_LANG, null)) ?: DEFAULT_SUBTITLE_LANG

    /**
     * 只给单测用：直接改进程内镜像（**不落盘**）。
     *
     * 存在的理由是「开关的默认值也要被单测钉住」：JVM 单测没有 `Context`，
     * 而 `BiliSourceProviderTest` 必须能证明「关着的时候一个请求都不发」。
     * 它不是生产入口 —— 唯一的生产写入入口是 [setEnabled]。
     */
    internal fun setEnabledForTest(value: Boolean) {
        enabled = value
    }

    /** 只给单测用：改进程内镜像的音质上限（不落盘）。 */
    internal fun setQualityCapForTest(value: BiliQualityCap) {
        qualityCap = value
    }

    /** 只给单测用：改进程内镜像的「优先 FLAC」（不落盘）。 */
    internal fun setPreferFlacForTest(value: Boolean) {
        preferFlac = value
    }

    /** 只给单测用：改进程内镜像的字幕语言（不落盘）。 */
    internal fun setSubtitleLangForTest(value: BiliSubtitleLang) {
        subtitleLang = value
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
}
