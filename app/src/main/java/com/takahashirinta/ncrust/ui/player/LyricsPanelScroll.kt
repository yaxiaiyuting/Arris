/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.ui.player

/**
 * v2.0.1 —— 歌词面板滚动定位的**纯计算**（无 Compose 依赖，JVM 可单测）。
 *
 * 抽出来的理由与 [PlayerLayout] 一样：本版修的 bug 就是「这几个数字算错时，第一句会被推出短视口」，
 * 它必须能在没有真机、没有 Compose 的情况下被断言（见 `LyricsPanelScrollTest`）。
 *
 * ## 面板的滚动结构
 *
 * ```
 * item(0)      = "top_spacer"   顶部留白
 * item(1..n)   = 歌词行（第 i 句 = item i+1）
 * item(n+1)    = "bottom_spacer" 底部留白
 * ```
 *
 * 定位语义（v1.5.0 起未变）：**当前行落在视口 [LEAD_FRACTION] 高处** ——
 * `animateScrollToItem(i + 1, leadOffsetPx(vh))`，负 offset = 目标行出现在视口上方 36% 处。
 *
 * ## 本版修的两个数字
 *
 * **① 顶部留白原本是固定 200dp**（v1.5.0 从 Kanesumi 面板整体搬入时，按竖屏全屏面板
 * 「约 700dp 高」定稿的）。大屏模式右栏的歌词面板只有约 280dp 高（PCL110 实测
 * 800×363dp 窗口 − 控制条），200dp 留白占掉 72% ⇒ **「列表在最顶端」直接等于
 * 「第一句被推到面板底边之外」**（会被面板裁掉，还整条落在底部渐隐带里）。
 * 竖屏 700dp 面板下同样的 200dp 只占 29%，第一句完整可见 —— 这就是
 * 「只有大屏模式出现」的原因。
 *
 * 改成 `min(200dp, LEAD_FRACTION × 视口)` 之后有两条不变量：
 *
 *  1. `topSpacer <= LEAD_FRACTION × 视口`（[topSpacerHeightPx]）⇒ **「列表在最顶端」与
 *     「第一句落在 36% 处」是同一个位置**（两者相差 `max(0, spacer − 36%×视口) = 0`），
 *     所以"回顶"与"定位"不再互相打架，第一句开唱时也不会再跳一下；
 *  2. 第一句恒定落在**顶部渐隐带下方** —— 渐隐高 = `min(100dp, 30% × 视口)`
 *     （见 `LyricsView`），而 `0.30 < 0.36` 对任何视口都成立；
 *  3. 竖屏（视口 ≥ 200dp / 0.36 ≈ 556dp）下结果仍是 200dp ⇒ 与 v2.0.0 逐像素一致。
 *
 * **② 视口高度未知时一律回落基准值**：首帧 `maxHeight` 拿不到 / 父级约束无界时，
 * 用 200dp 而不是 0 —— 0 会让第一句贴到面板顶边，比 200dp 更难看，而且竖屏下
 * 200dp 本来就是正确值。
 */
internal object LyricsPanelScroll {

    /**
     * 当前行在视口里的目标高度比例：0.36。
     *
     * 与 v1.5.0 起的定位语义完全一致（原实现写死 `0.36f`），只是提出来当唯一定义 ——
     * 顶部留白的上限与定位 offset 必须用同一个数，否则不变量 1 不成立。
     *
     * **竖屏用这个值**（黄金分割上方：当前行偏上，下方留出接下来的几句）。
     */
    const val LEAD_FRACTION = 0.36f

    /**
     * v2.3.0 · E：**横屏 / 大屏右栏**用的目标比例 —— 视口正中。
     *
     * ## 为什么横屏要换成 0.5 而不是继续用 0.36（真机实测几何）
     *
     * v2.3.0 的探针在大屏模式（S6 / SM-G9209，2560×1440px，density 640）上量到：
     *
     * | | 面板尺寸 | 0.36 对应的当前行位置 | 面板下方剩余 |
     * |---|---|---|---|
     * | 横屏 | 1274 × **928px = 232.0dp** | `0.36 × 232 = 83.5dp` = 334px | ≈ 148dp（约 1.4 行） |
     * | 竖屏 | 1280 × 816px = 204.0dp | `0.36 × 204 = 73.4dp` | ≈ 131dp |
     *
     * 横屏一屏只放得下 **3 行**（探针 dump：`frac_itemtop` 依次为 −0.04 / 0.12 / **0.36** / 0.74）。
     * 当前行落在 36% 时，它上方只露出一行的一半、下方只有一行半 —— 视觉重心明显偏上，
     * 这就是用户说的「不正」。0.5 让上下余量对称，是 3 行视口下唯一不偏的目标。
     *
     * ## 这是**有意的行为改变**，只在横屏生效
     *
     * 探针同时证明 0.36 的**自动定位本身是精确的**（6 次独立基线全部 `frac_itemtop = 0.3599`），
     * 所以这不是「修一个算错的数」，而是「横屏改用另一个目标」。竖屏一个像素都不动
     * （[LEAD_FRACTION] 仍是 0.36，探针竖屏实测 0.3591）。
     *
     * 证据：`docs/verification/v2.3.0/probe-lyric-landscape.md`、
     * `probe-raw/lyric-panel-measurements.txt`、`probe-raw/landscape-device-notes.md`。
     */
    const val CENTER_FRACTION = 0.5f

    /**
     * 顶部／底部留白的基准值（dp）。
     *
     * 200dp 是竖屏全屏面板下的定稿值：**不要**为了修大屏去改它 ——
     * 改了会移动竖屏第一句的位置，而竖屏从来没有出过问题（见 [topSpacerHeightPx] 的夹取）。
     */
    const val BASE_SPACER_DP = 200f

    /**
     * 顶部留白高度（px）：`min(基准, leadFraction × 视口)`。
     *
     * [viewportHeightPx] <= 0（首帧 / 无界约束）⇒ 返回基准值。
     *
     * v2.3.0 · E 起 [leadFraction] 可传入（横屏传 [CENTER_FRACTION]）。
     * **默认值就是原来的 0.36**，所以既有调用点与既有单测（`LyricsPanelScrollTest`）
     * 的行为逐字节不变。
     */
    fun topSpacerHeightPx(
        viewportHeightPx: Int,
        baseSpacerPx: Float,
        leadFraction: Float = LEAD_FRACTION,
    ): Float {
        if (viewportHeightPx <= 0) return baseSpacerPx
        return minOf(baseSpacerPx, viewportHeightPx * leadFraction)
    }

    /**
     * 定位 offset（px）：负值 = 目标行落在视口 [leadFraction] 高处。
     *
     * 视口未知（<= 0）时返回 0，与 v1.5.0 起的兜底行为一致（此时列表还没排版，
     * 这个 offset 也不会被真正用上）。
     */
    fun leadOffsetPx(viewportHeightPx: Int, leadFraction: Float = LEAD_FRACTION): Int =
        if (viewportHeightPx > 0) -(viewportHeightPx * leadFraction).toInt() else 0

    /**
     * 歌词行下标 → LazyColumn item 下标（第 0 项是顶部留白，所以恒 +1）。
     *
     * v2.0.1：**-1（还没到第一句）按第 0 句处理** —— 前奏 / 拖回开头时面板要摆的是"第一句"，
     * 不是"列表顶端"（在短面板里这两者曾经相差 124dp，正是本 bug 的表象）。
     * [lineCount] <= 0 时返回 0（调用方本来就有 `lines.isEmpty()` 早退，这里只是不让它抛
     * `coerceIn(1, 0)`）。
     */
    fun targetItemIndex(currentIndex: Int, lineCount: Int): Int {
        if (lineCount <= 0) return 0
        return (currentIndex.coerceAtLeast(0) + 1).coerceIn(1, lineCount)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // v2.5.2：把「一整条歌词」摆正，而不是只摆它的顶边
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 上下渐隐带的高度比例：视口的 **30%**。
     *
     * v1.7.0 · P1 引入（`LyricsView` 里内联），v2.5.2 抽到这里成为**唯一落点** ——
     * 因为从本版起，**面板自己**也要用这个数（它决定「整条歌词最高能摆到哪」，
     * 见 [blockTopPx] 的 `minTopPx`）。两处各写一份 `min(100dp, 0.3 × vh)` 就是第二处真相：
     * 渐隐带调窄了而定位没跟着调，歌词就会被压在渐隐里发灰。
     */
    const val FADE_FRACTION = 0.30f

    /** 渐隐带基准高度（dp）。竖屏全屏面板（约 700dp）下就是这个值。 */
    const val BASE_FADE_DP = 100f

    /** 渐隐带高度下限（dp）：极短视口下也不允许渐隐把整块歌词糊掉。 */
    const val MIN_FADE_DP = 24f

    /**
     * 渐隐带高度（px）：`clamp(min(基准, 30% × 视口), 下限, +∞)`。
     *
     * 与 `LyricsView` 里画出来的那两条渐隐 **必须**是同一个数（同一函数算出）——
     * 见 [FADE_FRACTION] 的说明。
     */
    fun fadeHeightPx(viewportHeightPx: Int, baseFadePx: Float, minFadePx: Float): Float {
        if (viewportHeightPx <= 0) return baseFadePx
        return minOf(baseFadePx, viewportHeightPx * FADE_FRACTION).coerceAtLeast(minFadePx)
    }

    /**
     * v2.5.2：**一整条歌词**（原句 + 译文 + 音译，含折行）的顶边目标位置（px）。
     *
     * ## 这一版修的是什么（用户报的原话：「自动居中只是把第一行居中」）
     *
     * v2.5.1 及以前，定位的语义是「**条目顶边**落在视口 [leadFraction] 处」
     * （[leadOffsetPx]）。在只有一行的时候这没问题；但一个 LazyColumn 条目实际是
     * `Box(padding 10dp) { Column { 原句、译文、音译 } }`：
     *
     * | 条目内容 | 高度（大屏 42/26/24sp + 20dp padding） |
     * |---|---|
     * | 只有原句 | ≈ 62dp |
     * | 原句 + 译文 | ≈ 88dp |
     * | 原句 + 译文 + 音译 | ≈ 112dp |
     *
     * 大屏模式右栏的视口只有约 232dp：把**顶边**放在正中（116dp）时，
     * 三行条目会一直铺到 228dp —— 视觉中点在 74% 处，也就是用户说的
     * 「第一行居中了，其他行全掉在下面」。**行数越多、偏得越狠**，而带翻译的歌
     * 恰好行数最多，所以那条反馈点名了「带有翻译的歌曲」。
     *
     * ## 新语义：整条的中点落在 [leadFraction] 处
     *
     * `top = 视口 × leadFraction − 条目高 / 2`，再做两道夹取：
     *
     * 1. **不高于渐隐带**（`top ≥ minTopPx`，调用方传 [fadeHeightPx]）。极高的条目
     *    （三行 + 折行）在 232dp 视口里若强行居中，顶边会落到 60dp —— 正好钻进
     *    渐隐带里发灰。此时「尽量居中且不被渐隐吃掉」比「数学上严格居中」重要；
     * 2. **不高于视口顶边**（`top ≥ 0`）：条目比视口还高时退化成顶对齐，
     *    这是滚动容器的通用行为，也保证第一行永远可读。
     *
     * ## 与旧行为的关系
     *
     * - `itemHeightPx <= 0`（**还没排版过、量不到高度**）⇒ 返回 [leadOffsetPx]，
     *   **逐字节等于 v2.5.1**。调用方拿到高度后会在同一帧内纠正（见
     *   `NcrustLyricsPanel.placeLine`），所以这只是「还没量到」的中间态；
     * - 单行条目下两者只差 `高/2 ≈ 31dp`，这正是「把这一行本身摆在目标处」
     *   与「把这一条的顶边摆在目标处」的差别 —— 前者才是用户说的「居中」。
     *
     * @param viewportHeightPx 面板视口高度；<= 0（首帧 / 无界约束）⇒ 返回 0，与旧行为一致
     * @param leadFraction 目标高度比例（竖屏 [LEAD_FRACTION]，横屏 [CENTER_FRACTION]）
     * @param itemHeightPx 该条目的**实测**高度（含 10dp 上下 padding 与所有折行）；<= 0 = 未知
     * @param minTopPx 顶边下限（通常传 [fadeHeightPx] 的结果）
     */
    fun blockTopPx(
        viewportHeightPx: Int,
        leadFraction: Float,
        itemHeightPx: Int,
        minTopPx: Float,
    ): Float {
        val centered = viewportHeightPx * leadFraction - itemHeightPx / 2f
        return centered.coerceAtLeast(minTopPx).coerceAtLeast(0f)
    }

    /**
     * 定位 offset（px）—— [blockTopPx] 的「直接可用」版本（负值 = 内容上移）。
     *
     * 高度未知时回落 [leadOffsetPx]（v2.5.1 的顶边语义），调用方据此先摆一次、
     * 量到高度后再纠正。
     */
    fun blockOffsetPx(
        viewportHeightPx: Int,
        leadFraction: Float,
        itemHeightPx: Int,
        minTopPx: Float,
    ): Int {
        if (viewportHeightPx <= 0) return 0
        if (itemHeightPx <= 0) return leadOffsetPx(viewportHeightPx, leadFraction)
        return -blockTopPx(viewportHeightPx, leadFraction, itemHeightPx, minTopPx).toInt()
    }

    /**
     * 已经把条目摆到 [currentTopPx] 之后，还需要再滚多少（正数 = 继续上移）。
     *
     * 用于「先按旧行为摆一次 → 量到高度 → 同一帧纠正」这条路径。返回 0 表示
     * 位置已经正确（或高度/视口未知），调用方不该做无意义的滚动 ——
     * 无意义滚动会把 `isScrollInProgress` 翻起来，进而误触发「用户滚动了」的旗子。
     */
    fun blockCorrectionPx(
        viewportHeightPx: Int,
        leadFraction: Float,
        itemHeightPx: Int,
        minTopPx: Float,
        currentTopPx: Int,
    ): Int {
        if (viewportHeightPx <= 0 || itemHeightPx <= 0) return 0
        val desired = blockTopPx(viewportHeightPx, leadFraction, itemHeightPx, minTopPx).toInt()
        return currentTopPx - desired
    }
}
