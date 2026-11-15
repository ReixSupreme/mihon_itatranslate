package eu.kanade.tachiyomi.ui.reader.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import logcat.LogPriority
import okio.Buffer
import okio.BufferedSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

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

    /** The translated page, or null when it has no text to translate. */
    suspend fun translate(image: BufferedSource, mangaId: Long): BufferedSource? {
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
        @Volatile
        private var instance: PageTranslator? = null

        fun get(context: Context, preferences: ReaderPreferences): PageTranslator =
            instance ?: synchronized(this) {
                instance ?: PageTranslator(context, preferences).also { instance = it }
            }
    }
}
