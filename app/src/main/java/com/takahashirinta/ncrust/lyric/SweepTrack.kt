/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.lyric

import kotlin.math.abs
import kotlin.math.min

/**
 * v1.5.2 · 逐字扫过的「扫过轨道」——把 yrc 的词时间轴翻译成一条**连续的时间 → 光标位置**折线。
 *
 * ## 为什么需要它
 *
 * v1.5.1 的渐变横扫是**按词量化**的：\`LyricsView\` 的按需唤醒循环只在「行时间戳 ∪ 词起始时间」
 * 这些时刻写一次 \`displayPosition\`，而绘制阶段读到的永远是「刚跨过某个词边界那一刻」的位置，
 * 于是 \`playedCutCharCount\` 里那段「词内按时间线性插值」在真机上**从来没有被喂到中间值** ——
 * 光标每唱完一个词才跳一格，软边只是把每次跳变磨圆，看上去仍然是「一顿一顿」的。
 *
 * 轨道把「时间 → 位置」这件事从帧调度里剥离出来：只要有人按帧把当前位置喂进来，
 * [sample] 就能给出**任意时刻**的连续光标位置，词内线性、词间连续、跨行自动折返。
 *
 * ## 与 AMLL（Apple Music-like Lyrics，SPlayer 的逐字歌词实现）的关系
 *
 * SPlayer 的逐字渐变来自 AMLL，其遮罩位置正是「单词内按 \`宽度 / 时长\` 匀速推进、
 * 停顿区间不动」的分段线性轨道（见 AMLL \`packages/core/src/lyric-player/dom/animation/mask/animator-web.ts\`
 * 的 \`advancePlainWordTimeline\` / \`advancePauseTimeline\`）。本文件采用同一模型，并做两点收敛：
 *
 *  1. **停顿区间不「停住再跳」而是继续匀速走完**。AMLL 在停顿里把遮罩位置冻住、到下一个词起始
 *     时刻瞬时平移一个词间距；本实现让折线直接连过去，速度虽然慢但**位置处处连续**，
 *     不会出现「顿一下再弹一格」。观感差异只在词间距（约 0.25 em）上，肉眼不可辨。
 *  2. **首尾各外扩半个渐变带**。AMLL 给首词额外 1.5 倍、末词 0.5 倍渐变带位移，本质是让渐变带
 *     完整地「滑入 / 滑出」整行。这里用对称的半个渐变带达到同一效果，且首尾对称。
 *
 * ## 坐标与方向
 *
 * 全部是**该文本块局部像素坐标**（与 \`TextLayoutResult\` 同一坐标系）。方向语义一律按**阅读顺序**
 * 交给 [SweepGeometry]：LTR 下「前缘」是字符左沿，RTL 下是右沿。轨道本身因此与书写方向无关，
 * 只有渲染层需要按 [SweepSample.rtl] 决定渐变带哪一侧是「已唱」。
 */
interface SweepGeometry {

    /** 该段文字是否从右往左排版。 */
    val rtl: Boolean

    /** 字符 [charIndex] 落在第几个**排版行**（软换行后的视觉行，0 起）。 */
    fun lineForChar(charIndex: Int): Int

    /** 第 [lineIndex] 行在阅读顺序上的**起点**（LTR：左沿；RTL：右沿）。 */
    fun lineStart(lineIndex: Int): Float

    /** 第 [lineIndex] 行在阅读顺序上的**终点**（LTR：右沿；RTL：左沿）。 */
    fun lineEnd(lineIndex: Int): Float

    /** 字符 [charIndex] 在阅读顺序上的**前缘**。 */
    fun charStart(charIndex: Int): Float

    /** 字符 [charIndex] 在阅读顺序上的**后缘**。 */
    fun charEnd(charIndex: Int): Float
}

/**
 * 词内插值的缓动。默认 [LINEAR]。
 *
 * 为什么默认线性：逐字歌词的**唯一**硬指标是「光标与音频对齐」，而词内线性 = 匀速 = 与 yrc 给
 * 的 \`(起始 ms, 时长 ms)\` 逐点吻合。给每个词单独套 ease-out / smoothstep 会让每个词的
 * 首尾速度归零，连续唱词时反而变成「一顿一顿」的脉冲感 —— 那是把「缓动」用错了地方。
 * 真正的顺滑来自**渐变带宽度**（软边本身就是对位置曲线的一阶低通），不是来自缓动函数。
 */
enum class SweepEasing {
    /** 词内匀速。[AMLL] 同款，默认。 */
    LINEAR,

    /** smoothstep：\`3u²-2u³\`。词首词尾速度为 0，适合慢歌，快歌会显脉冲。 */
    SMOOTH,

    /** 三次缓出：\`1-(1-u)³\`。词首快、词尾慢。 */
    EASE_OUT;

    fun apply(u: Float): Float {
        val t = u.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> t
            SMOOTH -> t * t * (3f - 2f * t)
            EASE_OUT -> 1f - (1f - t) * (1f - t) * (1f - t)
        }
    }
}

/**
 * 逐字扫过的可调参数（v1.5.2）。
 *
 * 全部有确定默认值；[LyricsDisplayPrefs] 允许用 SharedPreferences 覆盖（见那边的键名注释），
 * 这样调参不需要重新构建，探针可以先在真机上扫一遍参数再定稿。
 */
data class LyricsSweepConfig(
    /**
     * 渐变带宽度，单位是**字号**的倍数。
     *
     * 这是与 v1.5.1 最关键的差别之一：v1.5.1 用「整行宽度的 12%」（\`SWEEP_EDGE_FRACTION\`），
     * 于是同一个参数在 4 个字的短行和 20 个字的长行上观感完全不同，宽屏上还会糊成一片。
     * 绑字号之后，渐变带 ≈ 0.65 个字宽，与 AMLL 的 \`wordFadeWidth = 0.5 × 行高\` 基本等效
     * （本应用 32sp 字号 / 42sp 行高 ⇒ 0.5 × 42 / 32 ≈ 0.66）。
     */
    val fadeEm: Float = 0.65f,

    /** 未唱部分相对已唱部分的透明度。AMLL 当前行取 0.4，这里沿用同一档。 */
    val inactiveAlpha: Float = 0.4f,

    /** 词内缓动。 */
    val easing: SweepEasing = SweepEasing.LINEAR,

    /**
     * 是否用离屏层画软边（\`saveLayer\` + \`DstIn\`）。
     * false = 纯 \`clipRect\` 硬边扫过，零离屏分配，低端机降级用。
     */
    val softEdge: Boolean = true,
) {
    companion object {
        /** 字号取不到时的兜底渐变带宽度（dp）。 */
        const val DEFAULT_FADE_DP = 20f

        /** 词内插值默认线性；只有显式配了才换。 */
        val DEFAULT = LyricsSweepConfig()

        /** 低端降级档：硬边 + 略微收窄渐变带（硬边下 fadeEm 无意义，保留仅为可读）。 */
        val LOW_END = LyricsSweepConfig(softEdge = false)
    }
}

/**
 * 一次采样：光标落在哪一**排版行**、该行的局部 x、以及书写方向。
 *
 * [x] 是**阅读顺序**上的位置（LTR 越大越靠右、RTL 越大越靠左）—— 与 [SweepTrack] 的折线同轴。
 */
class SweepSample(val lineIndex: Int, val x: Float, val rtl: Boolean) {

    /**
     * 把光标换算成**视觉坐标**上的渐变带：\`[fadeStart, fadeEnd]\` 是软边过渡区，
     * [litOnLeft] 指出「已唱实心区」在过渡区的哪一侧（LTR 为 true，RTL 为 false）。
     *
     * 渐变带**以光标为中心**，保证「光标位置 == 50% 透明度处」——
     * 这正是 AMLL 的遮罩语义（ramp 中点即光标），也是「与播放进度精确同步」的判据。
     */
    fun band(fadePx: Float): SweepBand {
        val half = (if (fadePx.isFinite()) fadePx else 0f) * 0.5f
        return SweepBand(fadeStart = x - half, fadeEnd = x + half, litOnLeft = !rtl)
    }
}

/** 视觉坐标下的渐变带。见 [SweepSample.band]。 */
class SweepBand(val fadeStart: Float, val fadeEnd: Float, val litOnLeft: Boolean) {
    val width: Float get() = fadeEnd - fadeStart
}

/**
 * 一条不可变的扫过轨道。构造用 [build]，采样用 [sample]。
 *
 * 内部是三条平行数组（时间 / 位置 / 行号），按时间升序。之所以不用 \`List<Node>\`：
 * [sample] 会在**每一帧的绘制阶段**被调用，平行数组的二分查找不产生任何装箱与迭代器分配。
 */
class SweepTrack private constructor(
    private val times: LongArray,
    private val xs: FloatArray,
    private val lines: IntArray,
    /**
     * 每个节点的「行尾保持位」：当**下一个节点在另一个视觉行**时，光标必须停在这一行的
     * 阅读顺序行尾（再多走半个渐变带，让渐变整条滑出行外），**绝不能**把 x 从上一行的行尾
     * 直接插值到下一行的行首 —— 那等于让高亮倒着扫回行首。
     */
    private val holds: FloatArray,
    val rtl: Boolean,
    val endMs: Long?,
    private val easing: SweepEasing,
) {

    val nodeCount: Int get() = times.size

    /**
     * 采样 [positionMillis] 时刻的光标。
     *
     * - 早于第一个词 → null（这一行还没开口，高亮层整层不画）；
     * - 晚于最后一个节点 → 停在末位置（唱完了就停在行尾，不再移动）。
     */
    fun sample(positionMillis: Long): SweepSample? {
        val n = times.size
        if (n == 0) return null
        if (positionMillis < times[0]) return null
        val last = n - 1
        if (positionMillis >= times[last]) return SweepSample(lines[last], xs[last], rtl)
        var lo = 0
        var hi = last
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (times[mid] <= positionMillis) lo = mid else hi = mid - 1
        }
        if (lo >= last) return SweepSample(lines[last], xs[last], rtl)
        // 跨视觉行（软换行）的区间：**保持**在上一行的行尾，不做插值。
        // 折行处两个节点分属不同视觉行，x 分别接近「上一行行尾」与「下一行行首」，
        // 一旦插值就等于整行高亮从行尾倒着扫回行首 —— 用户看到的是「这句话的特效又从头播了一遍」。
        if (lines[lo] != lines[lo + 1]) {
            return SweepSample(lines[lo], holds[lo], rtl)
        }
        val t0 = times[lo]
        val t1 = times[lo + 1]
        val span = t1 - t0
        val u = if (span <= 0L) 1f else ((positionMillis - t0).toFloat() / span.toFloat())
        val e = easing.apply(u)
        return SweepSample(lines[lo], xs[lo] + (xs[lo + 1] - xs[lo]) * e, rtl)
    }

    companion object {

        /** 末词唱完后，光标最多再走这么久就贴到行尾（兜住行尾标点 / 尾音留白）。 */
        const val TERMINAL_MAX_MS = 700L

        /**
         * 由一行的逐字数据构建轨道。
         *
         * @param words yrc 的词，字符区间已经对齐到 [textLength] 的那份展示文本上
         * @param textLength 展示文本的字符数
         * @param endMs 整行结束时刻（yrc 的行时长）；null = 未知，则末词唱完即停在末位置
         * @param geo 字形几何
         * @param fadePx 渐变带宽度（像素），用于首尾外扩半个带
         * @return 没有任何有效词时返回 null —— 调用方据此退回整行渲染
         */
        fun build(
            words: List<LrcWord>,
            textLength: Int,
            endMs: Long?,
            geo: SweepGeometry,
            fadePx: Float,
            easing: SweepEasing = SweepEasing.LINEAR,
        ): SweepTrack? {
            if (words.isEmpty() || textLength <= 0) return null
            val half = (if (fadePx.isFinite()) abs(fadePx) else 0f) * 0.5f
            val dir = if (geo.rtl) -1f else 1f

            // yrc 实测段起始单调不减，但坏数据不该让整行错位：排一次序（时间，再字符序号）。
            val ordered = words.sortedWith(compareBy({ it.startMs }, { it.charStart }))

            // ① 先筛出**有效词**（字符区间非空且落在文本内）。
            //    首/末外扩必须知道谁是有效的首词与末词，所以这一步不能和推节点混在一起做。
            val valid = ArrayList<LrcWord>(ordered.size)
            for (w in ordered) {
                val s = w.charStart.coerceIn(0, textLength)
                val e = w.charEndExclusive.coerceIn(s, textLength)
                if (e > s) valid.add(w)
            }
            if (valid.isEmpty()) return null
            val lastWordIndex = valid.size - 1

            val times = ArrayList<Long>(valid.size * 2 + 2)
            val xs = ArrayList<Float>(valid.size * 2 + 2)
            val lines = ArrayList<Int>(valid.size * 2 + 2)
            val holds = ArrayList<Float>(valid.size * 2 + 2)

            fun push(t: Long, x: Float, line: Int) {
                val n = times.size
                if (n > 0) {
                    val prev = times[n - 1]
                    // 时间倒退：丢弃（保持折线在时间轴上严格单调，二分查找才有意义）。
                    if (t < prev) return
                    // 同一时刻：后者覆盖前者。x 沿阅读顺序单调不减，取后者等于「瞬时跳到更靠后的位置」。
                    if (t == prev) {
                        xs[n - 1] = x
                        lines[n - 1] = line
                        holds[n - 1] = geo.lineEnd(line) + dir * half
                        return
                    }
                }
                times.add(t)
                xs.add(x)
                lines.add(line)
                holds.add(geo.lineEnd(line) + dir * half)
            }

            valid.forEachIndexed { index, w ->
                val s = w.charStart.coerceIn(0, textLength)
                val e = w.charEndExclusive.coerceIn(s, textLength)
                val firstChar = s
                val lastChar = e - 1
                val l0 = geo.lineForChar(firstChar)
                val l1 = geo.lineForChar(lastChar)
                val startTime = w.startMs
                // 时长非正视为瞬时点亮：两个节点同刻，push 会保留后者（即词的末位置）。
                val endTime = startTime + w.durationMs.coerceAtLeast(0L)

                // ② **只有整行的首词向左、末词向右各外扩半个渐变带**，让渐变完整地滑入 / 滑出整行。
                //    中间词绝不能外扩 —— 否则 w_i 的末节点是 charEnd_i + 半带、w_{i+1} 的首节点是
                //    charStart_{i+1} - 半带，相邻词之间会凭空多出一段「后退一个渐变带宽度」的倒扫，
                //    表现为每唱完一个词高亮就倒着缩回去一点。折行处词间隙更长，倒扫更明显，
                //    看上去就像「这句话的特效又从头播了一遍」。
                val x0 = geo.charStart(firstChar) - (if (index == 0) dir * half else 0f)
                val x1 = geo.charEnd(lastChar) +
                    (if (index == lastWordIndex) dir * half else 0f)

                if (l0 == l1) {
                    push(startTime, x0, l0)
                    push(endTime, x1, l0)
                } else {
                    // 词被软换行劈成两截：前半截把渐变带送出上一行行尾，后半截从下一行行首外滑入。
                    // 折返发生在同一个时刻，用 +1ms 让它在时间轴上仍然严格递增（视觉上是一帧内完成）。
                    val mid = startTime + (endTime - startTime) / 2
                    push(startTime, x0, l0)
                    push(mid, geo.lineEnd(l0) + dir * half, l0)
                    push(mid + 1, geo.lineStart(l1) - dir * half, l1)
                    push(endTime, x1, l1)
                }
            }

            if (times.isEmpty()) return null

            // 行尾收束：yrc 的词不一定铺满展示文本（LRC 那份可能多出结尾标点 / 尾音留白）。
            // 用一个封顶的终止节点把光标匀速送到行尾，避免「最后一个词唱完就永远停在半路」。
            val tailTime = times[times.size - 1]
            val tailLine = lines[lines.size - 1]
            if (endMs != null && endMs > tailTime) {
                val terminal = min(endMs, tailTime + TERMINAL_MAX_MS)
                push(terminal, geo.lineEnd(tailLine) + dir * half, tailLine)
            }

            if (times.isEmpty()) return null
            return SweepTrack(
                times = times.toLongArray(),
                xs = FloatArray(xs.size) { xs[it] },
                lines = lines.toIntArray(),
                holds = FloatArray(holds.size) { holds[it] },
                rtl = geo.rtl,
                endMs = endMs,
                easing = easing,
            )
        }
    }
}
