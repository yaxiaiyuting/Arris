/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 *
 * v2.4.0 · E：跨源身份匹配的**名称归一化**。**纯逻辑，无 Android 依赖，JVM 可单测。**
 */

package com.takahashirinta.ncrust.crosssource

/**
 * 专辑名 / 曲名 / 艺人名的归一化（v2.4.0 · E）。
 *
 * ## 它是「召回」的一部分，**不是判据**
 *
 * 归一化的唯一职责是把「看起来是同一个名字」的东西收进同一个桶里，
 * 好让下一步的**结构判据**（专辑列表重合、曲目名集合重合、时长差）去做判定。
 *
 * 这条分工是探针逼出来的，不是设计偏好：`probe-artist-mapping.md` 的 P6 里，
 * ncm 的仿冒号 `邓紫棋`(62017015, 1 张专辑) 与真身 `G.E.M.邓紫棋`(7763, 58 张)
 * 在**名字层面无法区分**（一个是另一个的子串），而专辑列表重合度一眼分开（0 张 vs 54 张）。
 * 所以本文件里所有函数都只做「归一」，**没有任何一个函数返回「它们是不是同一个」**。
 *
 * ## 与探针脚本必须逐字对应
 *
 * `docs/verification/v2.4.0/probe_lib.py` 里的 `normalize_name` / `normalize_loose` /
 * `has_edition_marker` 是这三个函数的 Python 版本。**改一边必须改另一边并重跑探针** ——
 * 探针算出来的准确率只有在两侧归一化一致时才代表线上行为。
 * `NameNormalizerTest` 用同一批真实字符串钉住这条对应关系。
 */
object NameNormalizer {

    /**
     * 括注：`（）`、`()`、`【】`、`[]`、`〈〉`、`<>` 以及其中的内容。
     *
     * 去掉它是因为「叶惠美（Deluxe）」与「叶惠美」在实测里就是同一张专辑
     * （`probe-album-mapping.md`：48 对里有 15 对靠归一化才配上）。
     */
    private val BRACKET = Regex("[（(\\[【〈<][^）)\\]】〉>]*[）)\\]】〉>]")

    /**
     * 版本词。**归一化时一律剥掉**，但 [hasEditionMarker] 会把「剥掉过」这个事实
     * 单独报出来 —— 因为「同一张专辑的豪华版」可以合并，
     * 而「同一首歌的 Live 版」在时长不同时**不能**合并（见 [CrossSourceMatcher] 的单曲规则）。
     */
    private val VERSION_WORDS = listOf(
        "deluxe edition", "deluxe version", "deluxe", "remastered", "remaster",
        "special edition", "limited edition", "collector's edition", "anniversary edition",
        "豪华版", "豪華版", "典藏版", "纪念版", "紀念版", "特别版", "特別版", "限量版",
        "珍藏版", "加强版", "数字版", "數位版", "完整版", "精装版", "精裝版",
    )

    /**
     * 版本 / 介质标记。与 [VERSION_WORDS] 的区别：
     * [VERSION_WORDS] 只影响**名称等价**，这里还要额外告诉调用方
     * 「这个名字里有版本信息」—— 单曲匹配的 `HIGH` / `LOW` 分叉就靠它。
     */
    private val EDITION_MARKERS = Regex(
        "(?i)\\b(deluxe|remaster(ed)?|special|limited|collector'?s?|anniversary|version|ver|" +
            "live|acoustic|instrumental|remix|demo|mono|stereo|ep|single|bonus|" +
            "off\\s?vocal|karaoke)\\b" +
            "|豪华版|豪華版|典藏版|纪念版|紀念版|特别版|特別版|限量版|珍藏版|加强版|" +
            "现场|現場|演唱会|演唱會|翻唱|伴奏|纯音乐|純音樂|重制|重置|" +
            "慢板|快板|加长|加長|片段|试听|試聽|副歌|清唱|和声|和聲|混音|重混|" +
            "器乐|器樂|演奏版|钢琴版|鋼琴版|吉他版|女声版|女聲版|男声版|男聲版|深情版",
    )

    /** 标点与空白。ASCII 标点用区间写，中文标点逐个列。 */
    private val PUNCT = Regex(
        "[\\s\u3000!-/:-@\\[-`{-~·・…—–\\-_,.;:!?'\"“”‘’（）()【】\\[\\]《》〈〉、。，！？]+",
    )

    /**
     * 全角 → 半角，并把带圈数字、罗马数字等做兼容分解（NFKC）。
     *
     * 用 `java.text.Normalizer` 而不是手写映射表：手写表一定会漏
     * （`①` / `Ⅳ` / `㍿` 这些在曲名里真的出现过）。
     */
    fun toHalfWidth(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)

    /** 去掉所有括注（含内容）。 */
    fun stripBrackets(text: String): String = BRACKET.replace(text, "")

    /**
     * **强归一化** —— 用于「名称完全一致」这类判据。
     *
     * 顺序是有意的：先去括注 → 再去版本词 → 再去标点空白 → 最后小写。
     *
     * 反过来（先去标点）会让 `(Deluxe Edition)` 变成一个词、与不带括号的
     * `Deluxe Edition` 收敛到不同结果 —— 两条路必须收敛到同一个字符串，
     * 否则「同一张专辑的两种写法」会被判成两张专辑。
     */
    fun normalizeName(text: String): String {
        var s = toHalfWidth(text)
        s = stripBrackets(s).lowercase()
        for (word in VERSION_WORDS) s = s.replace(word, " ")
        return PUNCT.replace(s, "")
    }

    /**
     * **宽松归一化** —— 保留括注内容，只做全角/大小写/标点。
     *
     * 用途是**召回候选**：`晴天 (Live)` 的强归一化结果是 `晴天`，
     * 与录音室版撞在同一个桶里；宽松归一化能把它区分出来，让调用方
     * 在看到「桶里有多个候选」时知道要**逐个用结构判据校验**，而不是随便挑一个。
     */
    fun normalizeLoose(text: String): String =
        PUNCT.replace(toHalfWidth(text).lowercase(), "")

    /** 名字里是否带版本 / 介质标记（`Live`、`伴奏`、`Remix`、`豪华版`…）。 */
    fun hasEditionMarker(text: String): Boolean = EDITION_MARKERS.containsMatchIn(toHalfWidth(text))

    /**
     * 两个名字是否**同一归一化结果**。空串一律不相等 ——
     * 空名字能互相匹配是匹配算法里最容易造成错配的一条捷径。
     */
    fun sameName(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        val na = normalizeName(a)
        val nb = normalizeName(b)
        return na.isNotEmpty() && na == nb
    }

    /**
     * 名称**包含**关系（召回用）。
     *
     * 两条规则，各有实测依据：
     *
     * 1. **相等永远成立**，不看长度 —— 「李健」「周深」「王菲」这些双字名是华语乐坛的主力，
     *    长度门槛会把它们全部拒掉（本实现的第一版就是这么错的：`李健` 的召回结果是 0 条，
     *    而探针里 `李健` 明明是 `EXACT`）。
     * 2. **真包含要求较短一侧 ≥ [minLength]**，默认 2 —— 单字符包含（`A` ⊂ `ABBA`）是纯噪声，
     *    而 2 字符包含在华语里是真实存在的（`周深` ⊂ `周深工作室`），
     *    且它只影响**召回**：判定永远交给专辑重合 / 曲目重合 / 时长。
     */
    fun containsName(anchor: String?, candidate: String?, minLength: Int = 2): Boolean {
        if (anchor.isNullOrBlank() || candidate.isNullOrBlank()) return false
        val a = normalizeName(anchor)
        val b = normalizeName(candidate)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.length < minLength || b.length < minLength) return false
        return a.contains(b) || b.contains(a)
    }

    /**
     * 艺人名集合是否有交集（歌手可能有多个，任一对上即可）。
     *
     * 归一化用 [normalizeName]，所以 `Jay Chou` 与 `jaychou` 算同一个 ——
     * 但**不做繁简转换**（铁律 17 的降级原则：`周杰伦` 与 `周杰倫` 在本版视为两个艺人）。
     */
    fun artistsOverlap(a: List<String>?, b: List<String>?): Boolean {
        val na = a.orEmpty().mapNotNull { it.takeIf { s -> s.isNotBlank() }?.let(::normalizeName) }
            .filter { it.isNotEmpty() }.toSet()
        val nb = b.orEmpty().mapNotNull { it.takeIf { s -> s.isNotBlank() }?.let(::normalizeName) }
            .filter { it.isNotEmpty() }.toSet()
        return na.isNotEmpty() && nb.isNotEmpty() && na.any { it in nb }
    }
}
