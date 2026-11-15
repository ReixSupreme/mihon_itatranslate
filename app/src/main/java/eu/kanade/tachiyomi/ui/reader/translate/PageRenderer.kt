package eu.kanade.tachiyomi.ui.reader.translate

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Erases the original text of each bubble and writes the Italian in its place. */
object PageRenderer {

    private val typeface: Typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)

    private class Plan(
        val group: TextGroup,
        val text: String,
        val pad: Float,
        val box: RectF,
        val background: Background,
    )

    private class Background(val color: Int, val spread: Float)

    fun draw(bitmap: Bitmap, groups: List<TextGroup>, translations: List<String>) {
        val canvas = Canvas(bitmap)
        val w = bitmap.width
        val h = bitmap.height
        // Sample every background before erasing anything, so one bubble's fill doesn't leak into another's sample.
        val plans = groups.mapIndexedNotNull { i, g ->
            val text = translations.getOrNull(i)?.trim().orEmpty()
            if (text.isEmpty()) return@mapIndexedNotNull null
            val pad = max(3f, g.lineHeight * 0.3f)
            val box = RectF(
                max(0f, g.x - pad),
                max(0f, g.y - pad),
                min(w.toFloat(), g.x + g.w + pad),
                min(h.toFloat(), g.y + g.h + pad),
            )
            Plan(g, text, pad, box, sampleBackground(bitmap, box))
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@PageRenderer.typeface
            textAlign = Paint.Align.CENTER
        }
        for (p in plans) {
            val bg = p.background.color
            val luminance = Color.red(bg) * 0.299f + Color.green(bg) * 0.587f + Color.blue(bg) * 0.114f
            // Text over artwork rather than a flat bubble: rebuild the art under each line, then write white on black
            // like scanlation captions.
            val overArt = p.background.spread > 45
            val textColor: Int
            val outline: Int
            if (overArt) {
                for (line in p.group.lines) {
                    val px = line.h * 0.25f
                    val py = line.h * 0.15f
                    inpaint(bitmap, RectF(line.x - px, line.y - py, line.x + line.w + px, line.y + line.h + py))
                }
                textColor = Color.WHITE
                outline = Color.BLACK
            } else {
                fill.color = bg
                val r = min(p.pad * 1.5f, p.box.height() / 2)
                canvas.drawRoundRect(p.box, r, r, fill)
                textColor = if (luminance > 128) Color.rgb(17, 17, 17) else Color.WHITE
                outline = bg
            }

            val maxW = max(p.box.width() - p.pad * 0.6f, p.group.lineHeight * 4)
            val maxH = p.box.height() - p.pad * 0.4f
            val start = min(p.group.lineHeight * 0.8f, 64f)
            val minSize = max(9f, min(start, p.group.lineHeight * 0.4f))
            val (size, lines) = fit(textPaint, p.text, maxW, maxH, start, minSize)
            textPaint.textSize = size
            val lineH = size * 1.15f
            val cx = p.box.centerX()
            val metrics = textPaint.fontMetrics
            val baselineShift = -(metrics.ascent + metrics.descent) / 2
            var cy = p.box.centerY() - (lines.size - 1) * lineH / 2
            for (line in lines) {
                textPaint.style = Paint.Style.STROKE
                textPaint.strokeJoin = Paint.Join.ROUND
                textPaint.strokeWidth = max(2f, size * if (overArt) 0.28f else 0.18f)
                textPaint.color = outline
                canvas.drawText(line, cx, cy + baselineShift, textPaint)
                textPaint.style = Paint.Style.FILL
                textPaint.color = textColor
                canvas.drawText(line, cx, cy + baselineShift, textPaint)
                cy += lineH
            }
        }
    }

    private fun wrap(paint: Paint, text: String, maxW: Float): List<String> {
        val lines = mutableListOf<String>()
        var cur = ""
        for (word in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val test = if (cur.isEmpty()) word else "$cur $word"
            if (paint.measureText(test) <= maxW || cur.isEmpty()) {
                cur = test
            } else {
                lines += cur
                cur = word
            }
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    private fun fit(
        paint: Paint,
        text: String,
        maxW: Float,
        maxH: Float,
        start: Float,
        min: Float,
    ): Pair<Float, List<String>> {
        var size = start.roundToInt()
        while (size >= min) {
            paint.textSize = size.toFloat()
            val lines = wrap(paint, text, maxW)
            val widest = lines.maxOf { paint.measureText(it) }
            if (lines.size * size * 1.15f <= maxH && widest <= maxW) return size.toFloat() to lines
            size--
        }
        paint.textSize = min
        return min to wrap(paint, text, maxW)
    }

    /** Median colour of a thin ring around [box], and how much the ring varies (high over artwork). */
    private fun sampleBackground(bitmap: Bitmap, box: RectF): Background {
        val pad = 4
        val x0 = max(0, floor(box.left).toInt() - pad)
        val y0 = max(0, floor(box.top).toInt() - pad)
        val x1 = min(bitmap.width, ceil(box.right).toInt() + pad)
        val y1 = min(bitmap.height, ceil(box.bottom).toInt() + pad)
        val rw = max(1, x1 - x0)
        val rh = max(1, y1 - y0)
        val px = IntArray(rw * rh)
        bitmap.getPixels(px, 0, rw, x0, y0, rw, rh)
        val ring = mutableListOf<Int>()
        for (x in 0 until rw step 2) {
            ring += px[x]
            ring += px[(rh - 1) * rw + x]
        }
        for (y in 0 until rh step 2) {
            ring += px[y * rw]
            ring += px[y * rw + rw - 1]
        }
        fun lum(c: Int) = Color.red(c) * 0.299f + Color.green(c) * 0.587f + Color.blue(c) * 0.114f
        val sorted = ring.sortedBy { lum(it) }
        val median = sorted[sorted.size / 2]
        val spread =
            lum(sorted[(sorted.size * 0.8).toInt().coerceAtMost(sorted.size - 1)]) -
                lum(sorted[(sorted.size * 0.2).toInt()])
        return Background(median, spread)
    }

    /**
     * Fills [rect] from the pixels around it with push-pull: average the known pixels down a resolution pyramid,
     * then pull the averages back up into the hole.
     */
    private fun inpaint(bitmap: Bitmap, rect: RectF) {
        val m = 6
        val x0 = max(0, floor(rect.left).toInt() - m)
        val y0 = max(0, floor(rect.top).toInt() - m)
        val x1 = min(bitmap.width, ceil(rect.right).toInt() + m)
        val y1 = min(bitmap.height, ceil(rect.bottom).toInt() + m)
        val rw = x1 - x0
        val rh = y1 - y0
        if (rw < 2 || rh < 2) return
        val px = IntArray(rw * rh)
        bitmap.getPixels(px, 0, rw, x0, y0, rw, rh)

        class Level(val w: Int, val h: Int, val col: FloatArray, val wt: FloatArray)
        val base = Level(rw, rh, FloatArray(rw * rh * 3), FloatArray(rw * rh))
        for (j in 0 until rh) {
            for (i in 0 until rw) {
                val inside = x0 + i >= rect.left && x0 + i < rect.right && y0 + j >= rect.top && y0 + j < rect.bottom
                if (inside) continue
                val k = j * rw + i
                base.wt[k] = 1f
                base.col[k * 3] = Color.red(px[k]).toFloat()
                base.col[k * 3 + 1] = Color.green(px[k]).toFloat()
                base.col[k * 3 + 2] = Color.blue(px[k]).toFloat()
            }
        }
        val levels = mutableListOf(base)
        while (levels.last().w > 1 || levels.last().h > 1) {
            val f = levels.last()
            val nw = max(1, (f.w + 1) / 2)
            val nh = max(1, (f.h + 1) / 2)
            val c = Level(nw, nh, FloatArray(nw * nh * 3), FloatArray(nw * nh))
            for (j in 0 until f.h) {
                for (i in 0 until f.w) {
                    val k = j * f.w + i
                    val wk = f.wt[k]
                    if (wk == 0f) continue
                    val q = (j / 2) * nw + i / 2
                    c.wt[q] += wk
                    for (ch in 0 until 3) c.col[q * 3 + ch] += f.col[k * 3 + ch] * wk
                }
            }
            for (q in 0 until nw * nh) {
                if (c.wt[q] > 0f) {
                    for (ch in 0 until 3) c.col[q * 3 + ch] /= c.wt[q]
                    c.wt[q] = min(1f, c.wt[q])
                }
            }
            levels += c
        }
        for (l in levels.size - 2 downTo 0) {
            val f = levels[l]
            val c = levels[l + 1]
            for (j in 0 until f.h) {
                for (i in 0 until f.w) {
                    val k = j * f.w + i
                    val wk = f.wt[k]
                    if (wk >= 1f) continue
                    val fx = ((i - 0.5f) / 2).coerceIn(0f, (c.w - 1).toFloat())
                    val fy = ((j - 0.5f) / 2).coerceIn(0f, (c.h - 1).toFloat())
                    val ix = fx.toInt()
                    val iy = fy.toInt()
                    val ax = fx - ix
                    val ay = fy - iy
                    val ix1 = min(c.w - 1, ix + 1)
                    val iy1 = min(c.h - 1, iy + 1)
                    for (ch in 0 until 3) {
                        val top = c.col[(iy * c.w + ix) * 3 + ch] * (1 - ax) + c.col[(iy * c.w + ix1) * 3 + ch] * ax
                        val bottom =
                            c.col[(iy1 * c.w + ix) * 3 + ch] * (1 - ax) + c.col[(iy1 * c.w + ix1) * 3 + ch] * ax
                        val v = top * (1 - ay) + bottom * ay
                        f.col[k * 3 + ch] = f.col[k * 3 + ch] * wk + v * (1 - wk)
                    }
                    f.wt[k] = 1f
                }
            }
        }
        for (k in 0 until rw * rh) {
            px[k] = Color.rgb(
                base.col[k * 3].roundToInt().coerceIn(0, 255),
                base.col[k * 3 + 1].roundToInt().coerceIn(0, 255),
                base.col[k * 3 + 2].roundToInt().coerceIn(0, 255),
            )
        }
        bitmap.setPixels(px, 0, rw, x0, y0, rw, rh)
    }
}
