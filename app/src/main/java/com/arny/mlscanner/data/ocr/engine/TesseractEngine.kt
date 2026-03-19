package com.arny.mlscanner.data.ocr.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.TextBlock
import com.arny.mlscanner.domain.models.TextLine
import com.arny.mlscanner.domain.models.TextWord
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap

class TesseractEngine(private val context: Context) : OcrEngine {

    override val name = "Tesseract"

    companion object {
        private const val TAG = "TesseractEngine"
        private const val TESSDATA_DIR = "tessdata"
        private const val LANGUAGES = "rus+eng"

        private val GARBAGE_PATTERN = Regex("[|\\[\\]{}~`^\\\\]{2,}")
        private const val MIN_WORD_CONFIDENCE = 15f

        private const val MAX_SIDE = 3000           // Даунскейл выше этого
        private const val MAX_PIXELS = 8_000_000L   // 8MP макс (увеличен для апскейла)
        private const val MIN_SHORT_SIDE = 400      // Апскейл если короткая < этого
        private const val TARGET_SHORT_SIDE = 600   // Апскейлим до этого
        private const val MAX_UPSCALE = 3f          // Макс множитель апскейла
    }

    private var tessApi: TessBaseAPI? = null
    private var ready = false
    private val mutex = Mutex()

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (ready) return@withContext true

            try {
                val dataPath = copyTessDataSafe()

                // ▶ FIX: Пробуем инициализировать и тестировать
                val api = TessBaseAPI()

                if (!api.init(dataPath, LANGUAGES)) {
                    Log.e(TAG, "Tesseract init failed for $LANGUAGES")
                    return@withContext false
                }

                // Базовые настройки
                api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                api.setVariable("preserve_interword_spaces", "1")
                api.setVariable("textord_heavy_nr", "1")
                api.setVariable("textord_min_xheight", "6")

                // ▶ FIX: Тестируем работоспособность
                val testBitmap = createTestBitmap()
                api.setImage(testBitmap)
                val testText = api.utF8Text.orEmpty()
                api.clear()
                testBitmap.recycle()

                if (testText.isBlank()) {
                    Log.w(TAG, "Tesseract test failed WITH dictionaries, " +
                            "trying WITHOUT")
                    // Словари не работают (tessdata_fast) → отключаем
                    api.setVariable("load_system_dawg", "0")
                    api.setVariable("load_freq_dawg", "0")

                    // Повторный тест
                    val testBitmap2 = createTestBitmap()
                    api.setImage(testBitmap2)
                    val testText2 = api.utF8Text.orEmpty()
                    api.clear()
                    testBitmap2.recycle()

                    if (testText2.isBlank()) {
                        Log.e(TAG, "Tesseract test failed even WITHOUT " +
                                "dictionaries!")
                        // Может быть проблема с traineddata
                        api.recycle()
                        return@withContext false
                    }

                    Log.i(TAG, "Tesseract works WITHOUT dictionaries: " +
                            "'${testText2.trim()}'")
                } else {
                    Log.i(TAG, "Tesseract works WITH dictionaries: " +
                            "'${testText.trim()}'")
                }

                tessApi = api
                ready = true
                Log.i(TAG, "Tesseract initialized: $LANGUAGES")
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Tesseract init error", e)
                return@withContext false
            }
        }
    }

    /**
     * Создаёт тестовое изображение "Test Тест 123"
     * для проверки работоспособности Tesseract.
     */
    private fun createTestBitmap(): Bitmap {
        val width = 400
        val height = 60
        val bitmap = createBitmap(width, height)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 36f
            isAntiAlias = true
        }
        canvas.drawText("Test Тест 123", 10f, 42f, paint)
        return bitmap
    }

    private fun configureApi(api: TessBaseAPI) {
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO

        api.setVariable("preserve_interword_spaces", "1")

        api.setVariable("textord_heavy_nr", "1")

        api.setVariable("textord_min_xheight", "6")
    }

    override fun isReady(): Boolean = ready

    override suspend fun recognize(
        bitmap: Bitmap,
        handwrittenMode: Boolean
    ): OcrResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            recognizeInternal(bitmap, handwrittenMode)
        }
    }

    private fun recognizeInternal(
        bitmap: Bitmap,
        handwrittenMode: Boolean
    ): OcrResult {
        val api = tessApi
        if (api == null || !ready) {
            Log.w(TAG, "Tesseract not ready")
            return OcrResult.EMPTY
        }

        val startTime = System.currentTimeMillis()
        val optimized = ensureOptimalSize(bitmap)
        val safeBitmap = ensureSafeBitmap(optimized)

        try {
            if (handwrittenMode) {
                api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            } else {
                api.pageSegMode = selectPsm(safeBitmap)
            }

            api.setImage(safeBitmap)

            val rawFullText = api.utF8Text.orEmpty()

            if (rawFullText.isBlank()) {
                Log.w(TAG, "Tesseract returned empty text")
                api.clear()
                return OcrResult.EMPTY.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    engineName = name
                )
            }

            val meanConf = api.meanConfidence()

            val rawWords = extractWords(api)

            api.clear()

            val cleanedWords = postProcessWords(rawWords)
            val blocks = buildBlocksFromWords(cleanedWords)
            val fullText = buildFullTextFromBlocks(blocks)
            val avgConf = if (cleanedWords.isNotEmpty()) {
                cleanedWords.map { it.confidence / 100f }.average().toFloat()
            } else meanConf / 100f

            Log.d(TAG, "Raw: ${rawWords.size}, clean: ${cleanedWords.size}, " +
                "conf=$meanConf, text='${fullText.take(80)}...'")

            val elapsed = System.currentTimeMillis() - startTime

            return OcrResult(
                blocks = blocks,
                fullText = fullText,
                formattedText = fullText,
                averageConfidence = avgConf,
                processingTimeMs = elapsed,
                engineName = name,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )

        } catch (e: Exception) {
            Log.e(TAG, "Recognition error", e)
            api.clear()
            return OcrResult.EMPTY.copy(
                processingTimeMs = System.currentTimeMillis() - startTime,
                engineName = name
            )
        } finally {
            if (safeBitmap !== bitmap && safeBitmap !== optimized) safeBitmap.recycle()
            if (optimized !== bitmap) optimized.recycle()
        }
    }

    /**
     * Масштабирование до оптимального размера для Tesseract.
     *
     * Tesseract требует минимум ~30-40px высоты символа.
     * Для надёжного распознавания нужно минимум 400px по меньшей стороне.
     *
     * ПРАВИЛА:
     * 1. Если minSide < 400 → апскейл до 600px по короткой стороне
     *    (это главное исправление — узкие полоски увеличиваются)
     * 2. Если maxSide > 3000 → даунскейл
     * 3. Общее количество пикселей ≤ 6MP
     * 4. Максимальный апскейл ×3
     */
    private fun ensureOptimalSize(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val maxSide = maxOf(w, h)
        val minSide = minOf(w, h)

        // ▶ Приоритет 1: Апскейл по короткой стороне
        if (minSide < MIN_SHORT_SIDE) {
            val scale = (TARGET_SHORT_SIDE.toFloat() / minSide)
                .coerceAtMost(MAX_UPSCALE)
            val newW = (w * scale).toInt()
            val newH = (h * scale).toInt()

            if (newW.toLong() * newH.toLong() <= MAX_PIXELS) {
                Log.d(TAG, "Upscale: ${w}x${h} → ${newW}x${newH} (minSide $minSide→${minOf(newW,newH)})")
                return bitmap.scale(newW, newH)
            }
        }

        // Приоритет 2: Даунскейл по длинной стороне
        if (maxSide > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / maxSide
            val newW = (w * scale).toInt().coerceAtLeast(1)
            val newH = (h * scale).toInt().coerceAtLeast(1)
            Log.d(TAG, "Downscale: ${w}x${h} → ${newW}x${newH}")
            return bitmap.scale(newW, newH)
        }

        Log.d(TAG, "Size OK: ${w}x${h}")
        return bitmap
    }

    private fun selectPsm(bitmap: Bitmap): Int {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val ratio = w / h

        return when {
            // Очень вытянутое → скорее всего одна строка
            ratio !in 0.08f..12f -> {
                Log.d(TAG, "PSM: SINGLE_LINE (ratio=${"%.1f".format(ratio)})")
                TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            }
            // Умеренно вытянутое → несколько строк, один блок
            ratio !in 0.25f..4f -> {
                Log.d(TAG, "PSM: SINGLE_BLOCK (ratio=${"%.1f".format(ratio)})")
                TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            }
            // Маленькое
            maxOf(bitmap.width, bitmap.height) < 500 -> {
                Log.d(TAG, "PSM: SINGLE_BLOCK (small)")
                TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            }
            // Стандартное
            else -> {
                Log.d(TAG, "PSM: AUTO (ratio=${"%.1f".format(ratio)})")
                TessBaseAPI.PageSegMode.PSM_AUTO
            }
        }
    }

    private fun postProcessWords(words: List<WordWithConf>): List<WordWithConf> {
        return words
            .filter { it.confidence >= MIN_WORD_CONFIDENCE }
            .filter { word ->
                val text = word.text.trim()
                text.isNotEmpty() && !(text.length == 1 && !text[0].isLetterOrDigit())
            }
            .filter { !GARBAGE_PATTERN.containsMatchIn(it.text) }
            .map { it.copy(text = cleanWordText(it.text)) }
            .filter { it.text.isNotBlank() }
    }

    private fun cleanWordText(text: String): String {
        var result = text.trim()
        result = result.trimStart { it in "|\\[]{}~`^" }
        result = result.trimEnd { it in "|\\[]{}~`^" }
        result = result.replace(Regex("(?<=\\d)l(?=\\d)"), "1")
        result = result.replace(Regex("(?<=\\d)O(?=\\d)"), "0")
        return result
    }

    private fun extractWords(api: TessBaseAPI): List<WordWithConf> {
        val words = mutableListOf<WordWithConf>()
        try {
            val iterator = api.resultIterator ?: return words
            iterator.begin()
            do {
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                val wordText = iterator.getUTF8Text(level)?.trim()
                if (!wordText.isNullOrBlank()) {
                    val conf = iterator.confidence(level)
                    val rect = iterator.getBoundingBox(level)
                    val box = if (rect != null && rect.size >= 4) {
                        BoundingBox(rect[0].toFloat(), rect[1].toFloat(),
                            rect[2].toFloat(), rect[3].toFloat())
                    } else BoundingBox.EMPTY
                    words.add(WordWithConf(wordText, conf, box))
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
            iterator.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Word extraction error", e)
        }
        return words
    }

    private fun buildBlocksFromWords(words: List<WordWithConf>): List<TextBlock> {
        if (words.isEmpty()) return emptyList()

        val sorted = words.sortedWith(
            compareBy<WordWithConf> { it.box.top }.thenBy { it.box.left }
        )

        val lines = mutableListOf<MutableList<WordWithConf>>()
        var currentLine = mutableListOf<WordWithConf>()

        for (word in sorted) {
            if (currentLine.isEmpty()) {
                currentLine.add(word)
                continue
            }
            val lastWord = currentLine.last()
            val lineHeight = maxOf(
                word.box.bottom - word.box.top,
                lastWord.box.bottom - lastWord.box.top
            ).coerceAtLeast(1f)
            val verticalGap = word.box.top - lastWord.box.top

            if (kotlin.math.abs(verticalGap) > lineHeight * 0.5f) {
                lines.add(currentLine)
                currentLine = mutableListOf(word)
            } else {
                currentLine.add(word)
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)

        val textLines = lines.map { lineWords ->
            val lineText = lineWords.joinToString(" ") { it.text }
            val lineBox = lineWords.map { it.box }.reduce { acc, b -> acc.union(b) }
            val lineConf = lineWords.map { it.confidence / 100f }.average().toFloat()
            val textWords = lineWords.map { w -> TextWord(w.text, w.box, w.confidence / 100f) }
            TextLine(lineText, lineBox, textWords, lineConf)
        }

        if (textLines.isEmpty()) return emptyList()

        val blockText = textLines.joinToString("\n") { it.text }
        val blockBox = textLines.map { it.boundingBox }.reduce { acc, b -> acc.union(b) }
        val blockConf = textLines.map { it.confidence }.average().toFloat()

        return listOf(TextBlock(blockText, blockBox, textLines, blockConf))
    }

    private fun buildFullTextFromBlocks(blocks: List<TextBlock>): String {
        if (blocks.isEmpty()) return ""
        return blocks.joinToString("\n\n") { block ->
            block.lines.joinToString("\n") { it.text }
        }
    }

    private fun ensureSafeBitmap(source: Bitmap): Bitmap {
        val needsCopy = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                source.config == Bitmap.Config.HARDWARE -> true
            source.config != Bitmap.Config.ARGB_8888 -> true
            else -> false
        }
        return if (needsCopy) source.copy(Bitmap.Config.ARGB_8888, true) else source
    }

    private fun copyTessDataSafe(): String {
        val dataDir = File(context.filesDir, TESSDATA_DIR)
        if (!dataDir.exists()) dataDir.mkdirs()

        for (lang in listOf("rus", "eng")) {
            val fileName = "$lang.traineddata"
            val destFile = File(dataDir, fileName)
            if (destFile.exists() && destFile.length() > 100_000) continue
            if (destFile.exists()) destFile.delete()
            try {
                context.assets.open("$TESSDATA_DIR/$fileName").use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, 8192)
                        output.flush()
                    }
                }
                Log.i(TAG, "$fileName copied (${destFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy $fileName", e)
            }
        }
        return context.filesDir.absolutePath
    }

    override fun release() {
        try { tessApi?.recycle() } catch (_: Exception) {}
        tessApi = null
        ready = false
    }

    data class WordWithConf(
        val text: String,
        val confidence: Float,
        val box: BoundingBox
    )
}
