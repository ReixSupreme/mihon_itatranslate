package eu.kanade.tachiyomi.ui.reader.translate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A character read by the OCR, with its most likely alternatives (best first). */
class OcrChar(val c: String, val candidates: List<String>)

/** A line of text on a page, in page pixels. */
class OcrLine(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    var chars: List<OcrChar>,
    val confidence: Float,
) {
    val text: String get() = chars.joinToString("") { it.c }
}

/** PaddleOCR PP-OCRv4 detection and recognition models run with ONNX Runtime. */
class PaddleOcr(context: Context) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val det: OrtSession
    private val rec: OrtSession
    private val dict: List<String>

    init {
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(1, 4))
        }
        val assets = context.assets
        det = env.createSession(assets.open("translator/det.onnx").use { it.readBytes() }, options)
        rec = env.createSession(assets.open("translator/rec.onnx").use { it.readBytes() }, options)
        // Index 0 is the CTC blank and the model's last class is a space.
        dict = listOf("") + assets.open("translator/keys.txt").bufferedReader().readLines() + listOf(" ")
    }

    /** Text boxes inside [region] of [bitmap], in bitmap pixels. */
    fun detect(bitmap: Bitmap, region: Rect, maxSide: Int = 1280): List<Rect> {
        val rw = region.width()
        val rh = region.height()
        val ratio = min(1f, maxSide.toFloat() / max(rw, rh))
        val w = max(32, (rw * ratio / 32).roundToInt() * 32)
        val h = max(32, (rh * ratio / 32).roundToInt() * 32)
        val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(scaled).drawBitmap(bitmap, region, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        scaled.recycle()

        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        val input = FloatBuffer.allocate(3 * w * h)
        for (ch in 0 until 3) {
            for (i in 0 until w * h) {
                val p = pixels[i]
                val v = when (ch) {
                    0 -> Color.red(p)
                    1 -> Color.green(p)
                    else -> Color.blue(p)
                }
                input.put((v / 255f - mean[ch]) / std[ch])
            }
        }
        input.rewind()

        val prob = OnnxTensor.createTensor(env, input, longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            det.run(mapOf(det.inputNames.first() to tensor)).use { result ->
                val out = (result[0] as OnnxTensor).floatBuffer
                FloatArray(out.remaining()).also { out.get(it) }
            }
        }
        return components(prob, w, h).map { box ->
            val sx = rw.toFloat() / w
            val sy = rh.toFloat() / h
            Rect(
                region.left + (box.left * sx).toInt(),
                region.top + (box.top * sy).toInt(),
                region.left + ceil(box.right * sx).toInt(),
                region.top + ceil(box.bottom * sy).toInt(),
            )
        }
    }

    /** Boxes of connected areas of the probability map, grown back to the full text size. */
    private fun components(prob: FloatArray, w: Int, h: Int): List<Rect> {
        val threshold = 0.3f
        val label = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val boxes = mutableListOf<Rect>()
        for (start in 0 until w * h) {
            if (prob[start] <= threshold || label[start]) continue
            var top = 0
            stack[top++] = start
            label[start] = true
            var minX = w
            var minY = h
            var maxX = 0
            var maxY = 0
            var sum = 0f
            var n = 0
            while (top > 0) {
                val q = stack[--top]
                val qx = q % w
                val qy = q / w
                sum += prob[q]
                n++
                minX = min(minX, qx)
                maxX = max(maxX, qx)
                minY = min(minY, qy)
                maxY = max(maxY, qy)
                for (nb in intArrayOf(q - 1, q + 1, q - w, q + w)) {
                    if (nb < 0 || nb >= w * h) continue
                    if ((nb == q - 1 && qx == 0) || (nb == q + 1 && qx == w - 1)) continue
                    if (!label[nb] && prob[nb] > threshold) {
                        label[nb] = true
                        stack[top++] = nb
                    }
                }
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            if (n < 10 || min(bw, bh) < 3 || sum / n < 0.5f) continue
            // The detector marks a shrunk core of each line; grow it as PaddleOCR's "unclip" does.
            val d = bw * bh * 1.6f / (2 * (bw + bh))
            boxes += Rect(
                max(0f, minX - d).toInt(),
                max(0f, minY - d).toInt(),
                min(w.toFloat(), maxX + 1 + d).toInt(),
                min(h.toFloat(), maxY + 1 + d).toInt(),
            )
        }
        return boxes
    }

    /** Reads the line in [box] of [bitmap]. */
    fun recognize(bitmap: Bitmap, box: Rect): OcrLine {
        val h = 48
        val w = (ceil(h.toFloat() * box.width() / box.height()).toInt()).coerceIn(16, 1600)
        val scaled = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(scaled).drawBitmap(bitmap, box, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        scaled.recycle()

        val input = FloatBuffer.allocate(3 * w * h)
        // The recognition model takes BGR.
        for (ch in 0 until 3) {
            for (i in 0 until w * h) {
                val p = pixels[i]
                val v = when (ch) {
                    0 -> Color.blue(p)
                    1 -> Color.green(p)
                    else -> Color.red(p)
                }
                input.put((v / 255f - 0.5f) / 0.5f)
            }
        }
        input.rewind()

        return OnnxTensor.createTensor(env, input, longArrayOf(1, 3, h.toLong(), w.toLong())).use { tensor ->
            rec.run(mapOf(rec.inputNames.first() to tensor)).use { result ->
                val out = result[0] as OnnxTensor
                val shape = out.info.shape
                val steps = shape[1].toInt()
                val classes = shape[2].toInt()
                val buf = out.floatBuffer
                val data = FloatArray(buf.remaining()).also { buf.get(it) }
                decode(data, steps, classes, box)
            }
        }
    }

    /** Greedy CTC decoding that keeps the top candidates of each emitted character. */
    private fun decode(data: FloatArray, steps: Int, classes: Int, box: Rect): OcrLine {
        val chars = mutableListOf<OcrChar>()
        var last = 0
        var confSum = 0f
        for (t in 0 until steps) {
            val base = t * classes
            var best = 0
            var bestP = -1f
            for (k in 0 until classes) {
                if (data[base + k] > bestP) {
                    bestP = data[base + k]
                    best = k
                }
            }
            if (best != 0 && best != last) {
                val top = (1 until classes).sortedByDescending { data[base + it] }.take(4)
                chars += OcrChar(dict.getOrElse(best) { "" }, top.map { dict.getOrElse(it) { "" } })
                confSum += bestP
            }
            last = best
        }
        return OcrLine(
            x = box.left.toFloat(),
            y = box.top.toFloat(),
            w = box.width().toFloat(),
            h = box.height().toFloat(),
            chars = chars,
            confidence = if (chars.isEmpty()) 0f else confSum / chars.size * 100,
        )
    }

    override fun close() {
        det.close()
        rec.close()
    }
}
