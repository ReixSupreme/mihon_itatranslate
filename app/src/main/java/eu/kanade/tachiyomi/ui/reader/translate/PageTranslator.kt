package eu.kanade.tachiyomi.ui.reader.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import okio.HashingSink
import okio.blackholeSink
import okio.buffer
import okio.sink
import okio.source
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import java.io.File
import java.io.InputStream

/** Turns a page image into the same page with its bubbles in Italian: OCR, DeepSeek, then redrawing. */
class PageTranslator private constructor(context: Context, private val preferences: ReaderPreferences) {

    private val appContext = context.applicationContext
    private val ocr by lazy { PaddleOcr(appContext) }

    // One page at a time: the models already use several cores, and parallel pages would only multiply memory.
    private val ocrLock = Mutex()
    private val translator = DeepSeekTranslator(
        store = TranslationStore(appContext),
        apiKey = { preferences.deepSeekApiKey.get() },
        model = { preferences.deepSeekModel.get() },
    )

    /**
     * Finished pages, named after the hash of the original image, so a chapter translated while downloading opens
     * translated without a connection. `.none` marks a page with nothing to translate.
     */
    private val pageCache = File(appContext.filesDir, "translator/pages").apply { mkdirs() }
    private var writesSinceTrim = 0

    val isEnabled: Boolean
        get() = preferences.translateToItalian.get() && preferences.deepSeekApiKey.get().isNotBlank()

    @Volatile
    private var lastError: String? = null

    /**
     * [translate] for the reader: on failure the page stays as it is, and the user hears about each distinct error
     * once instead of on every page.
     */
    suspend fun translateForReader(image: BufferedSource, mangaId: Long): BufferedSource? = try {
        translate(image, mangaId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logcat(LogPriority.WARN, e)
        val message = e.message ?: e.javaClass.simpleName
        if (message != lastError) {
            lastError = message
            withContext(Dispatchers.Main) {
                appContext.toast(appContext.stringResource(MR.strings.translation_failed, message))
            }
        }
        null
    }

    /** The page as already translated, e.g. while downloading, or null if it isn't. */
    suspend fun fromCache(image: BufferedSource): BufferedSource? =
        lookup(cacheKey(image)).takeIf { it !== NO_TEXT }

    /** The translated page, or null when it has no text to translate. */
    suspend fun translate(image: BufferedSource, mangaId: Long): BufferedSource? {
        val key = cacheKey(image)
        lookup(key)?.let { return it.takeIf { it !== NO_TEXT } }
        val cached = File(pageCache, "$key.jpg")
        val nothingToTranslate = File(pageCache, "$key.none")

        val translated = render(image, mangaId)
        withContext(Dispatchers.IO) {
            if (translated == null) {
                nothingToTranslate.createNewFile()
            } else {
                val tmp = File(pageCache, "$key.tmp")
                tmp.sink().buffer().use { it.writeAll(translated.peek()) }
                tmp.renameTo(cached)
            }
            if (++writesSinceTrim >= 20) {
                writesSinceTrim = 0
                trimPageCache()
            }
        }
        return translated
    }

    /** Translates the pages of a chapter as it finishes downloading, so it can later be read offline. */
    suspend fun pretranslate(pages: List<() -> InputStream>, mangaId: Long) = coroutineScope {
        // As many pages at a time as DeepSeek takes in one request, so a chapter costs few requests
        val slots = Semaphore(5)
        pages.map { open ->
            async {
                slots.withPermit {
                    translate(Buffer().also { it.writeAll(open().source()) }, mangaId)
                }
            }
        }.awaitAll()
    }

    private suspend fun cacheKey(image: BufferedSource): String = withContext(Dispatchers.IO) {
        HashingSink.sha256(blackholeSink()).use { sink ->
            image.peek().readAll(sink)
            sink.hash.hex()
        }
    }

    /** The cached page, [NO_TEXT] for a page known to have no text, or null if it was never translated. */
    private suspend fun lookup(key: String): Buffer? = withContext(Dispatchers.IO) {
        val cached = File(pageCache, "$key.jpg")
        when {
            File(pageCache, "$key.none").exists() -> NO_TEXT
            !cached.exists() -> null
            else -> {
                cached.setLastModified(System.currentTimeMillis())
                Buffer().also { it.writeAll(cached.source()) }
            }
        }
    }

    private fun trimPageCache() {
        val files = pageCache.listFiles().orEmpty().sortedByDescending { it.lastModified() }
        var total = 0L
        for (file in files) {
            total += file.length()
            if (total > PAGE_CACHE_MAX_BYTES) file.delete()
        }
    }

    private suspend fun render(image: BufferedSource, mangaId: Long): BufferedSource? {
        val bitmap = withContext(Dispatchers.IO) {
            BitmapFactory.decodeStream(
                image.peek().inputStream(),
                null,
                BitmapFactory.Options().apply {
                    inMutable = true
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                },
            )
        } ?: return null
        try {
            val groups = ocrLock.withLock { withContext(Dispatchers.Default) { read(bitmap) } }
            if (groups.isEmpty()) return null
            val translations = translator.translate(mangaId.toString(), groups.map { it.text })
            if (translations.all { it.isBlank() }) return null
            return withContext(Dispatchers.Default) {
                PageRenderer.draw(bitmap, groups, translations)
                Buffer().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it.outputStream()) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun read(bitmap: Bitmap): List<TextGroup> {
        val lines = TextLayout.slices(bitmap).flatMap { range ->
            ocr.detect(bitmap, Rect(0, range.first, bitmap.width, range.last + 1))
                .filter { it.width() >= 8 && it.height() >= 8 }
                .map { ocr.recognize(bitmap, it) }
                .filter(TextLayout::isUseful)
        }
        return TextLayout.group(lines)
    }

    companion object {
        private const val PAGE_CACHE_MAX_BYTES = 1L shl 30
        private val NO_TEXT = Buffer()

        @Volatile
        private var instance: PageTranslator? = null

        fun get(context: Context, preferences: ReaderPreferences): PageTranslator =
            instance ?: synchronized(this) {
                instance ?: PageTranslator(context, preferences).also { instance = it }
            }
    }
}
