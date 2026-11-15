package eu.kanade.tachiyomi.ui.reader.translate

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class MemoryEntry(val src: String, val it: String, val t: Long)

@Serializable
data class GlossaryEntry(val it: String, val locked: Boolean = false, val n: Int = 1, val t: Long = 0)

/**
 * Translations already paid for, kept per manga so names stay consistent across chapters, plus a memory shared by
 * every manga for short exclamations and site credits.
 */
class TranslationStore(context: Context) {

    private val dir = File(context.filesDir, "translator").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val memories = mutableMapOf<String, MutableMap<String, MemoryEntry>>()
    private val glossaries = mutableMapOf<String, MutableMap<String, GlossaryEntry>>()

    @Synchronized
    fun memory(series: String): MutableMap<String, MemoryEntry> =
        memories.getOrPut(series) { read(file("mem", series)) }

    @Synchronized
    fun glossary(series: String): MutableMap<String, GlossaryEntry> =
        glossaries.getOrPut(series) { read(file("gloss", series)) }

    @Synchronized
    fun save(series: String) {
        memories[series]?.let { write(file("mem", series), trim(it, MEMORY_MAX)) }
        glossaries[series]?.let { write(file("gloss", series), it) }
    }

    private fun trim(map: MutableMap<String, MemoryEntry>, max: Int): MutableMap<String, MemoryEntry> {
        if (map.size > max) {
            map.entries.sortedBy { it.value.t }.take(map.size - max).forEach { map.remove(it.key) }
        }
        return map
    }

    private fun file(kind: String, series: String) =
        File(dir, "$kind-${series.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    private inline fun <reified T> read(file: File): MutableMap<String, T> =
        runCatching { json.decodeFromString<Map<String, T>>(file.readText()).toMutableMap() }
            .getOrElse { mutableMapOf() }

    private inline fun <reified T> write(file: File, map: Map<String, T>) {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.encodeToString(map))
        tmp.renameTo(file)
    }

    companion object {
        const val COMMON = "*common"
        private const val MEMORY_MAX = 8000

        /** Key that matches the same sentence despite case, spacing and punctuation. */
        fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }
}
