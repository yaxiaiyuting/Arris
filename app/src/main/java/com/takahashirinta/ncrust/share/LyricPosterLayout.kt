/*
 * Ncrust —— 网易云音乐第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（https://github.com/yaxiaiyuting/Ncrust）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.share

/**
 * 歌词海报的**版式几何**（纯逻辑，无 Android 依赖）。
 *
 * ## 尺寸选型：1080 × 1920（竖版 9:16）
 *
 * | 候选 | 为什么不是它 |
 * |---|---|
 * | 720×1280 | 微信/微博二次压缩后正文字号只剩 ~20px，糊 |
 * | 1080×1920 | **选它**。与主流手机屏幕同宽 ⇒ 分享出去不缩放；ARGB_8888 占 8.3 MB，`minSdk 24` 的机器也扛得住（失败还有 [FALLBACK_SCALE] 一档） |
 * | 1440×2560 | 单张 14.7 MB，低端机上出图必 OOM，收益（放大看才看得出）与代价不成比例 |
 *
 * ## Kanesumi 风格（直角 / 无圆角 / 信息优先 / 主色可用作底）
 *
 * - 画面**一个圆角都没有**：封面是直角矩形 + 1px 描边，色条是直角矩形；
 * - 上下各一条 [ACCENT_BAR_PX] 高的主色条 —— 这是全图唯一的大面积主色，
 *   既当「品牌色」又天然把内容框住（不用圆角也不用阴影）；
 * - 信息优先：封面在左上（360px，不是铺满全宽的 1080px），右边立刻是歌名/歌手，
 *   紧接着就是歌词正文 —— 分享图的重点是**歌词**，封面只是出处；
 * - 「当前唱的那一句」用主色，其余行用前景色/次要色 —— 与播放器里
 *   `NcrustLyricsPanel` 的取色口径一致（当前行 primary、过去行弱化、未来行更弱）。
 *
 * ## 纯逻辑的边界在哪
 *
 * 这里只算**矩形与档位**：谁在哪儿、字号从哪一档选、一行最多几个字。
 * 真正需要 `Paint` 的换行与测高在 [LyricPosterRenderer] 里，
 * 通过 [chooseTextSize] 的 `measure` 回调注入 —— 于是「字号自适应」这条判定
 * 在 JVM 上可以用一个假 measure 完整测出来。
 */
object LyricPosterLayout {

    /** 画布宽（px）。 */
    const val WIDTH_PX = 1080

    /** 画布高（px）。 */
    const val HEIGHT_PX = 1920

    /**
     * OOM 降级档的缩放比（720×1280 = 2/3）。见 [LyricPosterRenderer] 的两级降级。
     */
    const val FALLBACK_SCALE = 2f / 3f

    /** 左右安全边距。80px 在 1080 宽上约等于手机上的 16dp，信息密度与留白都合适。 */
    const val MARGIN_PX = 80

    /** 上下的主色条高度（直角矩形，不透明）。 */
    const val ACCENT_BAR_PX = 16

    /** 封面边长。360 而不是满宽 1080：满宽会让歌词区只剩 800px 高，7 行歌词必然被压到最小字号。 */
    const val COVER_SIZE_PX = 360

    /** 顶部色条到封面/标题的距离。 */
    const val HEADER_TOP_PX = 96

    /** 封面与右侧标题之间的间距。 */
    const val COVER_TEXT_GAP_PX = 48

    /** 没有封面时表头块的固定高度（标题最多 2 行 + 歌手 1 行 + 行距）。 */
    const val TEXT_ONLY_HEADER_PX = 176

    /** 表头块与歌词区之间的间距。 */
    const val HEADER_LYRICS_GAP_PX = 72

    /** 页脚高度（署名行 + 与歌词区的间距）。 */
    const val FOOTER_HEIGHT_PX = 168

    /** 歌词区与页脚之间至少留的空白，避免最后一行贴着署名。 */
    const val LYRICS_BOTTOM_GAP_PX = 24

    /**
     * 字号候选档（px，**从大到小**）。出图时选「第一档放得下的」。
     *
     * 10 档覆盖 1~20 行：20 行时约 40px（在 1080 宽上仍然清晰），
     * 3 行时能顶到 80px（大字报效果，符合「分享一句歌词」的用法）。
     */
    val TEXT_SIZES = intArrayOf(80, 72, 64, 56, 48, 42, 36, 32, 28)

    /**
     * 单行字符上限。超过就截断并补 `…`。
     *
     * 为什么需要它：`StaticLayout` 会把长行折成多行，于是一句 300 字的「歌词」
     * （数据源里真实存在 —— 有人把整段念白塞进一行）会把歌词区吃光、把字号压到最小值，
     * 整张图因此不可读。截断发生在**排版之前**，所以它必须是纯逻辑。
     * 72 个字符在 48px 字号下约等于 2.5 行，留得住一行完整的长句。
     */
    const val MAX_LINE_CHARS = 72

    /**
     * 版式方案（单位 px；[scaled] 可整体缩到 720×1280 档）。
     *
     * @param coverSizePx 0 表示这张图**没有封面**（此时 [titleLeftPx] == [MARGIN_PX]，标题占满宽度）
     * @param lyricsHeightPx 歌词区的可用高度；[chooseTextSize] 就是拿它当上限
     */
    data class Plan(
        val widthPx: Int,
        val heightPx: Int,
        val accentBarPx: Int,
        val coverLeftPx: Int,
        val coverTopPx: Int,
        val coverSizePx: Int,
        val titleLeftPx: Int,
        val titleTopPx: Int,
        val titleWidthPx: Int,
        val lyricsLeftPx: Int,
        val lyricsTopPx: Int,
        val lyricsWidthPx: Int,
        val lyricsHeightPx: Int,
        val footerTopPx: Int,
    ) {
        /** 有没有封面（渲染侧据此决定是否画占位框）。 */
        val hasCover: Boolean get() = coverSizePx > 0
    }

    /**
     * 算出整张图的版式。
     *
     * @param hasCover 封面是否可用 —— **降级路径也走这里**：拿不到封面时不是把封面位置
     *                 留空，而是把标题挪到左边缘、歌词区上移，图因此不会有一块难看的空洞。
     */
    fun plan(hasCover: Boolean): Plan {
        val coverSize = if (hasCover) COVER_SIZE_PX else 0
        val titleLeft = if (hasCover) {
            MARGIN_PX + COVER_SIZE_PX + COVER_TEXT_GAP_PX
        } else {
            MARGIN_PX
        }
        val titleWidth = WIDTH_PX - MARGIN_PX - titleLeft
        val headerHeight = if (hasCover) COVER_SIZE_PX else TEXT_ONLY_HEADER_PX
        val headerBottom = HEADER_TOP_PX + headerHeight
        val lyricsTop = headerBottom + HEADER_LYRICS_GAP_PX
        val footerTop = HEIGHT_PX - ACCENT_BAR_PX - FOOTER_HEIGHT_PX
        val lyricsHeight = (footerTop - LYRICS_BOTTOM_GAP_PX - lyricsTop).coerceAtLeast(0)
        return Plan(
            widthPx = WIDTH_PX,
            heightPx = HEIGHT_PX,
            accentBarPx = ACCENT_BAR_PX,
            coverLeftPx = MARGIN_PX,
            coverTopPx = HEADER_TOP_PX,
            coverSizePx = coverSize,
            titleLeftPx = titleLeft,
            // 标题块顶端与封面顶端对齐（视觉上是同一个信息块），再让首行基线与封面留一点呼吸。
            titleTopPx = HEADER_TOP_PX + 12,
            titleWidthPx = titleWidth,
            lyricsLeftPx = MARGIN_PX,
            lyricsTopPx = lyricsTop,
            lyricsWidthPx = WIDTH_PX - 2 * MARGIN_PX,
            lyricsHeightPx = lyricsHeight,
            footerTopPx = footerTop,
        )
    }

    /**
     * 从 [candidates]（不必有序，函数内部会降序排）里挑**第一档放得下的**字号；
     * 全放不下就返回最小档（调用方负责再截断行数，见 [LyricPosterRenderer]）。
     *
     * `measure` 是「这个字号下整块歌词排版后有多少 px 高」——由渲染侧用 `StaticLayout` 实现。
     * 把它做成参数，是为了让本判定在 JVM 上可测：`measure = { it * lineCount }` 就够了。
     */
    fun chooseTextSize(
        candidates: IntArray = TEXT_SIZES,
        availableHeightPx: Int,
        measure: (Int) -> Int,
    ): Int {
        val sorted = candidates.toList().sortedDescending()
        if (sorted.isEmpty()) return 0
        for (size in sorted) {
            if (measure(size) <= availableHeightPx) return size
        }
        return sorted.last()
    }

    /**
     * 单行截断：超过 [maxChars] 就截到 `maxChars - 1` 再补 `…`，
     * 并把截断点前的空白去掉（避免出现「字 …」这种空一格的效果）。
     *
     * 代理对保护：截断点正好落在 emoji 的高位代理上时再退一个字符 ——
     * 否则会输出一个孤立代理，画出来是豆腐块。
     */
    fun truncateLine(text: String, maxChars: Int = MAX_LINE_CHARS): String {
        if (maxChars <= 1) return if (text.isEmpty()) text else "…"
        if (text.length <= maxChars) return text
        var cut = maxChars - 1
        if (cut > 0 && Character.isHighSurrogate(text[cut - 1])) cut -= 1
        val head = text.substring(0, cut).trimEnd()
        return "$head…"
    }

    /** 把 px 值按 [scale] 缩放（出图降级档用）。 */
    fun scaled(value: Int, scale: Float): Int = Math.round(value * scale)

    /**
     * 整体缩放的版式：[FALLBACK_SCALE] 档（720×1280）走它。
     *
     * 逐字段缩放而不是「画完再缩放位图」：后者在 JPEG/PNG 上是二次重采样，
     * 文字边缘会糊；前者只是把每个数字变小，文字仍然是原生渲染。
     */
    fun scalePlan(plan: Plan, scale: Float): Plan = Plan(
        widthPx = scaled(plan.widthPx, scale),
        heightPx = scaled(plan.heightPx, scale),
        accentBarPx = scaled(plan.accentBarPx, scale),
        coverLeftPx = scaled(plan.coverLeftPx, scale),
        coverTopPx = scaled(plan.coverTopPx, scale),
        coverSizePx = scaled(plan.coverSizePx, scale),
        titleLeftPx = scaled(plan.titleLeftPx, scale),
        titleTopPx = scaled(plan.titleTopPx, scale),
        titleWidthPx = scaled(plan.titleWidthPx, scale),
        lyricsLeftPx = scaled(plan.lyricsLeftPx, scale),
        lyricsTopPx = scaled(plan.lyricsTopPx, scale),
        lyricsWidthPx = scaled(plan.lyricsWidthPx, scale),
        lyricsHeightPx = scaled(plan.lyricsHeightPx, scale),
        footerTopPx = scaled(plan.footerTopPx, scale),
    )
}
