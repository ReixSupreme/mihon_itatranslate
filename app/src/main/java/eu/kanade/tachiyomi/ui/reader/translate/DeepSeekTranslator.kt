package eu.kanade.tachiyomi.ui.reader.translate

import eu.kanade.tachiyomi.ui.reader.translate.TranslationStore.Companion.COMMON
import eu.kanade.tachiyomi.ui.reader.translate.TranslationStore.Companion.normalize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Translates bubble texts to Italian with DeepSeek. Sentences already in the memory cost nothing, and the texts of
 * pages loaded close together go out in one request so the instructions are paid once.
 */
class DeepSeekTranslator(
    private val store: TranslationStore,
    private val apiKey: () -> String,
    private val model: () -> String,
) {

    private val client = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val batches = mutableMapOf<String, Batch>()

    /** DeepSeek charges for reasoning tokens, which translation doesn't need; dropped if the model rejects it. */
    @Volatile
    private var thinkingParamOk = true

    private class Item(val texts: List<String>, val result: CompletableDeferred<List<String>>)

    private class Batch {
        val items = mutableListOf<Item>()
        var textCount = 0
        var timer: Job? = null
    }

    suspend fun translate(series: String, texts: List<String>): List<String> {
        val memory = store.memory(series)
        val common = store.memory(COMMON)
        val out = MutableList(texts.size) { "" }
        val missing = mutableListOf<Int>()
        synchronized(store) {
            texts.forEachIndexed { i, t ->
                val hit = memory[normalize(t)] ?: common[normalize(t)]
                if (hit != null) out[i] = hit.it else missing += i
            }
        }
        if (missing.isEmpty()) return out
        val result = CompletableDeferred<List<String>>()
        enqueue(series, Item(missing.map { texts[it] }, result))
        result.await().forEachIndexed { k, it -> out[missing[k]] = it }
        return out
    }

    private suspend fun enqueue(series: String, item: Item) {
        lock.withLock {
            val batch = batches.getOrPut(series) { Batch() }
            batch.items += item
            batch.textCount += item.texts.size
            if (batch.items.size >= BATCH_MAX_PAGES || batch.textCount >= BATCH_MAX_TEXTS) {
                batches.remove(series)
                batch.timer?.cancel()
                scope.launch { flush(series, batch) }
            } else if (batch.timer == null) {
                batch.timer = scope.launch {
                    delay(BATCH_WAIT_MS)
                    lock.withLock { if (batches[series] === batch) batches.remove(series) else return@launch }
                    flush(series, batch)
                }
            }
        }
    }

    private fun flush(series: String, batch: Batch) {
        try {
            val unique = batch.items.flatMap { it.texts }.distinctBy { normalize(it) }
            val glossary = store.glossary(series)
            val joined = normalize(unique.joinToString(" "))
            val relevant = synchronized(store) {
                glossary.filter { (term, _) -> normalize(term).let { it.length >= 2 && it in joined } }
                    .mapValues { it.value.it }
            }
            val (translations, terms) = request(unique, relevant)

            synchronized(store) {
                val memory = store.memory(series)
                val common = store.memory(COMMON)
                val now = System.currentTimeMillis()
                unique.forEachIndexed { i, src ->
                    val entry = MemoryEntry(src, translations[i], now)
                    memory[normalize(src)] = entry
                    if (isCommon(src, entry.it)) common[normalize(src)] = entry
                }
                terms.forEach { (term, it) ->
                    if (term.isBlank() || it.isBlank() || term.length > 60) return@forEach
                    val old = glossary[term]
                    glossary[term] = old?.copy(n = old.n + 1) ?: GlossaryEntry(it.trim(), t = now)
                }
                relevant.keys.forEach { term -> glossary[term]?.let { glossary[term] = it.copy(n = it.n + 1) } }
                store.save(series)
                store.save(COMMON)
            }

            val byKey = unique.withIndex().associate { normalize(it.value) to translations[it.index] }
            batch.items.forEach { item -> item.result.complete(item.texts.map { byKey[normalize(it)].orEmpty() }) }
        } catch (e: Exception) {
            batch.items.forEach { it.result.completeExceptionally(e) }
        }
    }

    private fun isCommon(src: String, it: String): Boolean {
        val words = src.trim().split(Regex("\\s+")).size
        return it.isEmpty() || (normalize(src).length <= 18 && words <= 3)
    }

    /** Returns the translations, in order, and the new names and terms DeepSeek found. */
    private fun request(texts: List<String>, glossary: Map<String, String>): Pair<List<String>, Map<String, String>> {
        val user = buildJsonObject {
            putJsonObject("g") { glossary.forEach { (k, v) -> put(k, v) } }
            putJsonObject("t") { texts.forEachIndexed { i, t -> put((i + 1).toString(), t) } }
        }
        fun body(withThinking: Boolean) = buildJsonObject {
            put("model", model().ifBlank { DEFAULT_MODEL })
            putJsonArray("messages") {
                add(
                    buildJsonObject {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    },
                )
                add(
                    buildJsonObject {
                        put("role", "user")
                        put("content", user.toString())
                    },
                )
            }
            put("temperature", 1.0)
            putJsonObject("response_format") { put("type", "json_object") }
            put("stream", false)
            if (withThinking) putJsonObject("thinking") { put("type", "disabled") }
        }.toString()

        fun call(withThinking: Boolean) = client.newCall(
            Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer ${apiKey()}")
                .post(body(withThinking).toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute()

        var response = call(thinkingParamOk)
        if (response.code == 400 && thinkingParamOk) {
            val text = response.body.string()
            if ("thinking" in text) {
                thinkingParamOk = false
                response = call(false)
            } else {
                throw IOException("DeepSeek: errore 400: ${text.take(200)}")
            }
        }
        val text = response.use { it.body.string() }
        when (response.code) {
            200 -> Unit
            401 -> throw IOException("Chiave API DeepSeek non valida")
            402 -> throw IOException("Credito DeepSeek esaurito")
            429 -> throw IOException("Troppe richieste a DeepSeek, riprova tra poco")
            else -> throw IOException("DeepSeek: errore ${response.code}: ${text.take(200)}")
        }
        val content = json.parseToJsonElement(text).jsonObject["choices"]!!.jsonArray[0]
            .jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
            .trim().removePrefix("```json").removePrefix("```").removeSuffix("```")
        val out = json.parseToJsonElement(content).jsonObject
        val t = out["t"] as? JsonObject
        val translations = texts.indices.map { i ->
            (t?.get((i + 1).toString()) as? JsonPrimitive)?.contentOrNull.orEmpty()
        }
        val terms = (out["n"] as? JsonObject).orEmpty()
            .mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }
            .toMap()
        return translations to terms
    }

    companion object {
        const val DEFAULT_MODEL = "deepseek-flash"
        private const val BATCH_MAX_PAGES = 5
        private const val BATCH_MAX_TEXTS = 40
        private const val BATCH_WAIT_MS = 2500L

        // Short and in English to spend fewer tokens; identical on every call so DeepSeek can serve it from cache.
        private const val SYSTEM_PROMPT =
            "Translate manhwa/webtoon speech bubbles from English into natural, colloquial Italian: short " +
                "comic-style lines, no notes. Input JSON: {\"g\": glossary {source: Italian}, always use these exact " +
                "renderings; \"t\": {id: OCR text}}. OCR may have errors or missing spaces (e.g. \"OFTHE\"), and may " +
                "misread digits as look-alike letters (4→H/A, 0→O/D, 1→I/l, 5→S, 8→B): infer the intended text " +
                "and keep numbers exact. Use \"\" for noise, signatures, watermarks, site credits. Reply with JSON " +
                "only: {\"t\": {id: Italian}, \"n\": {source term: Italian}}. \"n\" = only NEW proper nouns/special " +
                "terms (people, families, clans, techniques, places, items, titles) not in g, max 8, keep person " +
                "names unchanged; {} if none."
    }
}
