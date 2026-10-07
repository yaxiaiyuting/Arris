/*
 * Ncrust —— ncm 第三方客户端
 * 原始代码 Copyright (c) 2026 Takahashi_Rinta，以 MIT 许可发布（全文见仓库根目录 LICENSE-MIT）。
 *
 * 本文件属于本 Fork（Arris，https://github.com/yaxiaiyuting/Arris）的修改部分，
 * Copyright (c) 2026 yaxiaiyuting，以 GPLv3 许可分发；本 Fork 整体以 GPLv3 分发。
 */

package com.takahashirinta.ncrust.share

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.takahashirinta.ncrust.network.CoverUrls
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 歌词海报出图 —— `android.graphics.Canvas` + [Bitmap] + [StaticLayout] 手绘。
 *
 * ## 为什么不是 Compose 离屏渲染
 *
 * - 本仓库的 Compose 是 BOM 2024.12.01 / compiler 1.5.14，`rememberGraphicsLayer`
 *   那套 `GraphicsLayer` 离屏 API 是 1.7 才有的（本仓库**没有**，也不能为一个功能升 BOM）；
 * - `ComposeView` + `drawToBitmap` 在 API 24 上要额外走一遍 `View` 的测量/布局，
 *   且必须在主线程完成 —— 出图是 8MB 级的分配，放主线程就是 ANR 风险；
 * - Canvas 手绘没有以上任何问题，且**零新依赖**（`StaticLayout` 是 framework 类）。
 *
 * ## 视觉复用（需求里点名要说明的部分）
 *
 * | 播放器里的东西 | 海报里怎么处理 |
 * |---|---|
 * | 当前行 `primary`、过去行 `onBackground@0.6`、未来行 `onSurfaceVariant@0.4`（`NcrustLyricsPanel` 的取色） | **可复用**：颜色本身就是 `Int`，[LyricPosterColors] 原样接过去 |
 * | 逐字高亮 `drawWithContent` + `TextLayoutResult.getPathForRange` | **不可复用**：海报是静态图，没有播放位置，逐字没有意义 |
 * | `MetroText` / `LocalMetroColors` / `LocalStrings` | **不可复用**：`CompositionLocal` 只在组合期可读，离屏渲染没有 Composition。所以颜色与文案都以**普通参数**传进 [LyricPosterSpec] |
 * | 面板的 32sp/42sp 字号与 `fontScale` | **不复用**：海报按行数自适应（[LyricPosterLayout.chooseTextSize]），面板是固定字号 |
 *
 * ## 降级链（有界，绝不死循环）
 *
 * [render] 由调用方包在 `try/catch(Throwable)` 里，两级降级写在 [LyricSharePoster]：
 * ① 1080×1920 + 封面 → ② 1080×1920 无封面 → ③ 720×1280 无封面 → ④ 放弃出图、退回纯文本分享。
 * 每一级只试一次，没有重试。
 */
object LyricPosterRenderer {

    /** 出图规格。所有依赖 Compose 运行时的东西都在这里被「拍平」成普通值。 */
    data class LyricPosterSpec(
        val track: LyricShareTrack,
        val lines: List<LyricShareLine>,
        /** [lines] 里哪一行是正在唱的（-1 = 没有）。 */
        val currentIndex: Int,
        val colors: LyricPosterColors,
        val showTranslation: Boolean,
        val showRomanization: Boolean,
        /** 页脚署名行（i18n 文案，空串则只画色块标记）。 */
        val credit: String,
        /** 已解码的封面；null = 走无封面版式。 */
        val cover: Bitmap? = null,
        /** 版式缩放（1f = 1080×1920，[LyricPosterLayout.FALLBACK_SCALE] = 720×1280）。 */
        val scale: Float = 1f,
    )

    /** 主色条 / 色块用（直角，无圆角，无阴影）。 */
    private fun fillPaint(color: Int) = Paint().apply {
        this.color = color
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    /** 文本用：`TextPaint` 是 `StaticLayout` 要求的类型。 */
    private fun textPaint(color: Int, sizePx: Float, bold: Boolean = false) = TextPaint().apply {
        this.color = color
        textSize = sizePx
        isAntiAlias = true
        typeface = if (bold) Typeface.create(Typeface.DEFAULT, Typeface.BOLD) else Typeface.DEFAULT
    }

    /** 按比例给颜色叠一层不透明度（保留原 alpha 与 RGB）。 */
    private fun withAlpha(color: Int, fraction: Float): Int {
        val a = ((color ushr 24) and 0xFF) * fraction.coerceIn(0f, 1f)
        return (color and 0x00FFFFFF) or ((a.toInt().coerceIn(0, 255)) shl 24)
    }

    /**
     * 画一张海报。**可能 OOM**（调用方负责捕获与降级），也**必须在后台线程调用**。
     */
    fun render(spec: LyricPosterSpec): Bitmap {
        val basePlan = LyricPosterLayout.plan(hasCover = spec.cover != null)
        val plan = if (spec.scale == 1f) basePlan else LyricPosterLayout.scalePlan(basePlan, spec.scale)
        val scale = spec.scale

        val bitmap = Bitmap.createBitmap(plan.widthPx, plan.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(spec.colors.background)

        // ── 上下主色条（全图唯一的大面积主色）────────────────────────────────
        val barPaint = fillPaint(spec.colors.primary)
        canvas.drawRect(0f, 0f, plan.widthPx.toFloat(), plan.accentBarPx.toFloat(), barPaint)
        canvas.drawRect(
            0f,
            (plan.heightPx - plan.accentBarPx).toFloat(),
            plan.widthPx.toFloat(),
            plan.heightPx.toFloat(),
            barPaint,
        )

        // ── 封面（直角 + 1px 描边，不裁圆角）─────────────────────────────────
        if (spec.cover != null && plan.hasCover) {
            val dst = RectF(
                plan.coverLeftPx.toFloat(),
                plan.coverTopPx.toFloat(),
                (plan.coverLeftPx + plan.coverSizePx).toFloat(),
                (plan.coverTopPx + plan.coverSizePx).toFloat(),
            )
            canvas.drawBitmap(
                spec.cover,
                Rect(0, 0, spec.cover.width, spec.cover.height),
                dst,
                Paint().apply {
                    isAntiAlias = true
                    isFilterBitmap = true
                },
            )
            canvas.drawRect(
                dst,
                Paint().apply {
                    color = spec.colors.divider
                    style = Paint.Style.STROKE
                    strokeWidth = (2 * scale).coerceAtLeast(1f)
                    isAntiAlias = false
                },
            )
        }

        // ── 表头：歌名（最多 2 行，超出省略）+ 歌手 ──────────────────────────
        val titlePaint = textPaint(spec.colors.onBackground, 46f * scale, bold = true)
        val titleLayout = layoutOf(
            text = spec.track.name.trim().ifEmpty { spec.track.artist.trim() },
            paint = titlePaint,
            width = plan.titleWidthPx,
            maxLines = 2,
            ellipsize = true,
        )
        canvas.save()
        canvas.translate(plan.titleLeftPx.toFloat(), plan.titleTopPx.toFloat())
        titleLayout.draw(canvas)
        canvas.restore()

        val artistPaint = textPaint(spec.colors.onSurfaceVariant, 28f * scale)
        val artistBaseline = plan.titleTopPx + titleLayout.height + 18f * scale + artistPaint.textSize
        val artist = spec.track.artist.trim()
        val artistText = buildString {
            append(artist)
            val album = spec.track.album.trim()
            if (album.isNotEmpty()) {
                if (isNotEmpty()) append("  ·  ")
                append(album)
            }
        }
        if (artistText.isNotEmpty()) {
            canvas.drawText(
                TextUtils.ellipsize(artistText, artistPaint, plan.titleWidthPx.toFloat(), TextUtils.TruncateAt.END).toString(),
                plan.titleLeftPx.toFloat(),
                artistBaseline,
                artistPaint,
            )
        }

        // ── 歌词正文（字号随行数自适应；放不下先降字号、再截尾）──────────────
        drawLyrics(canvas, spec, plan, scale)

        // ── 页脚：分隔线 + 主色小方块 + 署名 ─────────────────────────────────
        canvas.drawRect(
            plan.lyricsLeftPx.toFloat(),
            plan.footerTopPx.toFloat(),
            (plan.widthPx - LyricPosterLayout.scaled(LyricPosterLayout.MARGIN_PX, scale)).toFloat(),
            plan.footerTopPx + (1 * scale).coerceAtLeast(1f),
            fillPaint(spec.colors.divider),
        )
        val markTop = plan.footerTopPx + LyricPosterLayout.scaled(52, scale)
        val markSize = LyricPosterLayout.scaled(26, scale)
        canvas.drawRect(
            plan.lyricsLeftPx.toFloat(),
            markTop.toFloat(),
            (plan.lyricsLeftPx + markSize).toFloat(),
            (markTop + markSize).toFloat(),
            barPaint,
        )
        val credit = spec.credit.trim()
        if (credit.isNotEmpty()) {
            val creditPaint = textPaint(spec.colors.onSurfaceVariant, 28f * scale)
            canvas.drawText(
                credit,
                (plan.lyricsLeftPx + markSize + 16f * scale),
                markTop + markSize - 2f * scale,
                creditPaint,
            )
        }

        return bitmap
    }

    // ---------------------------------------------------------------- 歌词区

    /** 一行歌词排版后的三块（主行 / 译文 / 音译）与它们在画布上的总高。 */
    private class LineBlock(
        val main: StaticLayout,
        val translation: StaticLayout?,
        val romanization: StaticLayout?,
        val gapAfter: Int,
    ) {
        val height: Int
            get() = main.height + (translation?.height ?: 0) + (romanization?.height ?: 0) + gapAfter
    }

    private fun drawLyrics(
        canvas: Canvas,
        spec: LyricPosterSpec,
        plan: LyricPosterLayout.Plan,
        scale: Float,
    ) {
        if (spec.lines.isEmpty()) return
        val available = plan.lyricsHeightPx
        // 每个候选字号只排版一次：chooseTextSize 的 measure 会回调多次，
        // 命中缓存就直接取，避免 9 档 × 20 行重复构建 StaticLayout。
        val built = HashMap<Int, Pair<List<LineBlock>, Int>>()

        fun blocksFor(sizePx: Int): Pair<List<LineBlock>, Int> =
            built.getOrPut(sizePx) {
                val blocks = spec.lines.map { line ->
                    buildBlock(line, spec, sizePx, plan.lyricsWidthPx, scale)
                }
                blocks to blocks.sumOf { it.height }
            }

        val size = LyricPosterLayout.chooseTextSize(
            candidates = LyricPosterLayout.TEXT_SIZES,
            availableHeightPx = available,
            measure = { candidate -> blocksFor((candidate * scale).toInt()).second },
        )
        val chosen = blocksFor((size * scale).toInt())
        var blocks = chosen.first
        var total = chosen.second

        // 最小档仍然放不下 ⇒ 从**尾部**丢行，永远保住当前行（[currentIndex]）。
        // 循环受 blocks.size 约束，最多丢 N 行，不存在"丢不完"的死循环。
        if (total > available) {
            val keepCurrent = spec.currentIndex.coerceAtLeast(0)
            var end = blocks.size
            while (total > available && end > 1 && end - 1 > keepCurrent) {
                end -= 1
                blocks = blocks.subList(0, end)
                total = blocks.sumOf { it.height }
            }
        }

        val onBackground = spec.colors.onBackground
        val primary = spec.colors.primary
        val past = withAlpha(onBackground, 0.6f)
        val future = withAlpha(spec.colors.onSurfaceVariant, 0.75f)

        canvas.save()
        canvas.translate(plan.lyricsLeftPx.toFloat(), plan.lyricsTopPx.toFloat())
        var y = 0f
        blocks.forEachIndexed { index, block ->
            // 与原表比对：截尾之后下标仍然是原表的前缀，所以可以直接用 spec.currentIndex。
            val color = when {
                spec.currentIndex < 0 -> onBackground
                index < spec.currentIndex -> past
                index == spec.currentIndex -> primary
                else -> future
            }
            block.main.paint.color = color
            canvas.save()
            canvas.translate(0f, y)
            block.main.draw(canvas)
            var dy = block.main.height.toFloat()
            block.translation?.let {
                it.paint.color = withAlpha(spec.colors.onSurfaceVariant, if (index == spec.currentIndex) 1f else 0.75f)
                canvas.save()
                canvas.translate(0f, dy)
                it.draw(canvas)
                canvas.restore()
                dy += it.height
            }
            block.romanization?.let {
                it.paint.color = withAlpha(spec.colors.onSurfaceVariant, 0.7f)
                canvas.save()
                canvas.translate(0f, dy)
                it.draw(canvas)
                canvas.restore()
                dy += it.height
            }
            canvas.restore()
            y += dy + block.gapAfter
        }
        canvas.restore()
    }

    private fun buildBlock(
        line: LyricShareLine,
        spec: LyricPosterSpec,
        sizePx: Int,
        widthPx: Int,
        scale: Float,
    ): LineBlock {
        val mainText = LyricPosterLayout.truncateLine(line.text.trim())
        val mainPaint = textPaint(spec.colors.onBackground, sizePx.toFloat())
        val main = layoutOf(mainText, mainPaint, widthPx, maxLines = Int.MAX_VALUE, ellipsize = false)
        val translation = subtitleLayout(
            main = mainText,
            sub = line.translation,
            enabled = spec.showTranslation,
            sizePx = sizePx * 0.55f,
            widthPx = widthPx,
        )
        val romanization = subtitleLayout(
            main = mainText,
            sub = line.romanization,
            enabled = spec.showRomanization,
            sizePx = sizePx * 0.45f,
            widthPx = widthPx,
        )
        return LineBlock(main, translation, romanization, gapAfter = (sizePx * 0.5f).toInt())
    }

    private fun subtitleLayout(
        main: String,
        sub: String,
        enabled: Boolean,
        sizePx: Float,
        widthPx: Int,
    ): StaticLayout? {
        if (!enabled) return null
        val text = sub.trim()
        // 与 [LyricShareText] 同一口径：空译文、以及「译文 == 原文」的脏数据都不画。
        if (text.isEmpty() || text == main) return null
        return layoutOf(
            text = LyricPosterLayout.truncateLine(text),
            paint = textPaint(0xFFFFFFFF.toInt(), sizePx),
            width = widthPx,
            maxLines = 2,
            ellipsize = true,
        )
    }

    private fun layoutOf(
        text: String,
        paint: TextPaint,
        width: Int,
        maxLines: Int,
        ellipsize: Boolean,
    ): StaticLayout {
        val builder = StaticLayout.Builder
            .obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.15f)
            .setMaxLines(maxLines)
        if (ellipsize) builder.setEllipsize(TextUtils.TruncateAt.END)
        return builder.build()
    }
}

/**
 * 封面加载 —— 复用仓库既有的 Coil 方案（`coil-compose:2.6.0`，与 `PlaybackService.loadArtwork`
 * 同一个 `ImageLoader` 单例），**不引入第二个图片库**。
 *
 * 两个容易踩的点：
 * 1. **必须 `allowHardware(false)`** —— 硬件位图画进软件 `Canvas` 会直接抛
 *    `IllegalArgumentException: Software rendering doesn't support hardware bitmaps`；
 * 2. **必须有超时** —— 出图是用户点一下就要发生的事，封面 CDN 卡住不能把 UI 挂在那里。
 *    4 秒拿不到就按「没有封面」出图（降级路径见 [LyricSharePoster]）。
 */
object LyricPosterCover {

    /** 封面加载超时：到点就当作没有封面。 */
    const val TIMEOUT_MS = 4_000L

    /** 海报里的封面边长 360px，取 640px 的图源（`CoverUrls.small`）已经 1.8× 余量。 */
    private const val TARGET_PX = 640

    /**
     * 取封面位图。失败 / 超时 / 无 URL 一律返回 null（**不抛异常**）——
     * 调用方据此走无封面版式，而不是把整个分享流程判死。
     */
    suspend fun load(context: Context, url: String?): Bitmap? {
        val target = CoverUrls.small(url) ?: return null
        val result = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                Coil.imageLoader(context).execute(
                    ImageRequest.Builder(context)
                        .data(target)
                        .size(TARGET_PX)
                        .allowHardware(false)
                        .build(),
                )
            }.getOrNull()
        }
        val drawable = (result as? SuccessResult)?.drawable
        return (drawable as? BitmapDrawable)?.bitmap
    }
}
