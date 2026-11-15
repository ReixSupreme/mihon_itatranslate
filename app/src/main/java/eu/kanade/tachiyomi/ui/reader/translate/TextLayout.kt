package eu.kanade.tachiyomi.ui.reader.translate

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Lines of one speech bubble or caption, in reading order. */
class TextGroup(val lines: List<OcrLine>) {
    val x = lines.minOf { it.x }
    val y = lines.minOf { it.y }
    val w = lines.maxOf { it.x + it.w } - x
    val h = lines.maxOf { it.y + it.h } - y
    val lineHeight = lines.map { it.h }.sorted()[lines.size / 2]
    val text = lines.joinToString(" ") { it.text.trim() }.replace(Regex("\\s+"), " ")
}

object TextLayout {

    /**
     * Vertical ranges of a long strip to read one at a time, cut at the emptiest row near every [target] pixels so a
     * cut rarely goes through text.
     */
    fun slices(bitmap: Bitmap, target: Int = 1200, search: Int = 300): List<IntRange> {
        val w = bitmap.width
        val h = bitmap.height
        if (h <= target * 1.3) return listOf(0 until h)
        val step = max(1, w / 200)
        val row = IntArray(w)
        fun busy(y: Int): Float {
            bitmap.getPixels(row, 0, w, 0, y, w, 1)
            var lo = 255f
            var hi = 0f
            var sum = 0f
            var sum2 = 0f
            var n = 0
            for (x in 0 until w step step) {
                val p = row[x]
                val l = Color.red(p) * 0.299f + Color.green(p) * 0.587f + Color.blue(p) * 0.114f
                sum += l
                sum2 += l * l
                n++
                lo = min(lo, l)
                hi = max(hi, l)
            }
            val mean = sum / n
            return sum2 / n - mean * mean + (hi - lo)
        }
        val ranges = mutableListOf<IntRange>()
        var start = 0
        while (h - start > target * 1.3) {
            val ideal = start + target
            var best = ideal
            var bestScore = Float.MAX_VALUE
            var y = max(start + 200, ideal - search)
            while (y < min(h - 1, ideal + search)) {
                val score = busy(y) + abs(y - ideal) * 0.02f
                if (score < bestScore) {
                    bestScore = score
                    best = y
                }
                y += 2
            }
            ranges += start until best
            start = best
        }
        ranges += start until h
        return ranges
    }

    fun isUseful(line: OcrLine): Boolean {
        val t = line.text.trim()
        return line.confidence >= 55 && Regex("\\p{L}{2,}").containsMatchIn(t)
    }

    /** Joins lines close to each other into the bubbles they belong to. */
    fun group(lines: List<OcrLine>): List<TextGroup> {
        val parent = IntArray(lines.size) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            parent[i] = r
            return r
        }
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                val a = lines[i]
                val b = lines[j]
                val lh = min(a.h, b.h)
                val gapY = max(0f, max(a.y, b.y) - min(a.y + a.h, b.y + b.h))
                val gapX = max(0f, max(a.x, b.x) - min(a.x + a.w, b.x + b.w))
                val similarSize = max(a.h, b.h) / max(1f, lh) < 2.2f
                if (similarSize && gapY <= lh * 0.9f && gapX <= lh * 0.9f) parent[find(i)] = find(j)
            }
        }
        return lines.indices.groupBy { find(it) }.values
            .map { idx ->
                val ls = idx.map { lines[it] }.sortedWith { a, b ->
                    if (abs(a.y - b.y) < min(a.h, b.h) * 0.5f) a.x.compareTo(b.x) else a.y.compareTo(b.y)
                }
                fixNumbers(ls)
                TextGroup(ls)
            }
            .filter { g -> g.text.count { it.isLetterOrDigit() } >= 2 }
            .sortedWith(compareBy({ it.y }, { it.x }))
    }

    private val lookalike = mapOf(
        "O" to "0", "o" to "0", "D" to "0", "Q" to "0", "I" to "1", "l" to "1", "|" to "1", "i" to "1",
        "Z" to "2", "z" to "2", "H" to "4", "A" to "4", "h" to "4", "S" to "5", "s" to "5", "G" to "6",
        "b" to "6", "T" to "7", "B" to "8", "g" to "9", "q" to "9",
    )

    private val units = (
        "years year yrs days day months month weeks week hours hour hrs minutes minute mins seconds second secs " +
            "times people men women soldiers decades decade centuries century generations meters metres meter " +
            "metre km kilometers kilometres miles mile feet ft kg kilograms pounds lbs percent levels level floors " +
            "floor stars coins gold silver won dollars points steps rounds moves blows strikes kills lives souls " +
            "cultivators disciples elders li cun zhang jin taels"
        ).split(" ").toSet()

    /**
     * Comic fonts make the OCR read digits as look-alike letters ("40 YEARS" as "HO YEARS"). A short word before a
     * unit, or a word mixing digits and letters, becomes a number when every letter of it has a digit among the
     * OCR's candidates or is a look-alike of one.
     */
    fun fixNumbers(lines: List<OcrLine>) {
        class Word(val line: OcrLine, val idx: List<Int>)
        val words = mutableListOf<Word>()
        for (line in lines) {
            var cur: MutableList<Int>? = null
            line.chars.forEachIndexed { i, ch ->
                if (ch.c.isBlank()) {
                    cur = null
                } else {
                    val list = cur ?: mutableListOf<Int>().also {
                        cur = it
                        words += Word(line, it)
                    }
                    list += i
                }
            }
        }
        fun letters(w: Word) = w.idx.joinToString("") { w.line.chars[it].c }
        val changed = mutableMapOf<OcrLine, MutableList<OcrChar>>()
        words.forEachIndexed { k, w ->
            val core = letters(w).filter { it.isLetterOrDigit() }
            if (core.isEmpty() || core.length > 4) return@forEachIndexed
            val hasDigit = core.any { it.isDigit() }
            val hasLetter = core.any { it.isLetter() }
            val next = words.getOrNull(k + 1)?.let { n -> letters(n).filter { it.isLetter() || it == '%' }.lowercase() }
            val unitNext = next != null && (next in units || next == "%")
            if (!(hasDigit && hasLetter) && !(unitNext && !hasDigit)) return@forEachIndexed
            val chars = changed.getOrPut(w.line) { w.line.chars.toMutableList() }
            val repl = w.idx.map { i ->
                val ch = chars[i]
                if (!ch.c.any { it.isLetterOrDigit() }) return@map null
                val digit = if (ch.c.all { it.isDigit() }) {
                    ch.c
                } else {
                    ch.candidates.firstOrNull {
                        it.length == 1 &&
                            it[0].isDigit()
                    }
                }
                digit
                    ?: lookalike[ch.c].takeIf { hasDigit || ch.candidates.any { c -> c.any { it.isDigit() } } }
                    ?: return@forEachIndexed
            }
            w.idx.forEachIndexed { j, i -> repl[j]?.let { chars[i] = OcrChar(it, chars[i].candidates) } }
        }
        changed.forEach { (line, chars) -> line.chars = chars }
    }
}
