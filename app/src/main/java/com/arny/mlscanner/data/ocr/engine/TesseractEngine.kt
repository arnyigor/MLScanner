package com.arny.mlscanner.data.ocr.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.arny.mlscanner.data.ocr.mapper.EngineResultMapper
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.TextBlock
import com.arny.mlscanner.domain.models.TextLine
import com.arny.mlscanner.domain.models.TextWord
import com.googlecode.tesseract.android.ResultIterator
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Tesseract 4 LSTM с безопасной синхронизацией.
 *
 * ИСПРАВЛЕНИЯ:
 * - Mutex вместо synchronized (не блокирует потоки)
 * - PSM_AUTO вместо PSM_SPARSE_TEXT (лучше для документов)
 * - Безопасное копирование traineddata с проверкой размера
 * - Корректное освобождение ресурсов
 */
class TesseractEngine(private val context: Context) : OcrEngine {

    override val name = "Tesseract 4 LSTM"

    companion object {
        private const val TAG = "TesseractEngine"
        private const val TESSDATA_DIR = "tessdata"
        private const val LANGUAGES = "rus+eng"
    }

    private var tessApi: TessBaseAPI? = null
    private var ready = false
    private val mutex = Mutex()

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (ready) return@withContext true

            try {
                val dataPath = copyTessDataSafe()
                val api = TessBaseAPI()

                if (!api.init(dataPath, LANGUAGES)) {
                    Log.e(TAG, "Tesseract init failed for $LANGUAGES")
                    return@withContext false
                }

                 api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                api.setVariable("preserve_interword_spaces", "1")

                // Улучшение распознавания на сложных изображениях
                api.setVariable("textord_heavy_nr", "1")

                // Не отбрасывать мелкие символы
                api.setVariable("textord_min_xheight", "6")

                // Улучшение для низкого качества
                api.setVariable("edges_max_children_per_outline", "40")

                // Не группировать цифры отдельно
                api.setVariable("classify_bln_numeric_mode", "0")

                // Порог уверенности для вывода (0 = выводить всё)
                api.setVariable("tessedit_char_blacklist", "")

                // Улучшить для смешанных скриптов
                api.setVariable("paragraph_text_based", "1")

                tessApi = api
                ready = true
                Log.i(TAG, "Tesseract initialized: $LANGUAGES, PSM_AUTO")
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Tesseract init error", e)
                return@withContext false
            }
        }
    }

    override fun isReady(): Boolean = ready

    override suspend fun recognize(bitmap: Bitmap, handwrittenMode: Boolean): OcrResult = 
        mutex.withLock { withContext(Dispatchers.Default) { recognizeInternal(bitmap, handwrittenMode) } }

    private fun recognizeInternal(bitmap: Bitmap, handwrittenMode: Boolean = false): OcrResult {
        val api = tessApi
        if (api == null || !ready) {
            Log.w(TAG, "Tesseract not ready")
            return OcrResult.EMPTY
        }

        val startTime = System.currentTimeMillis()

        val optimizedBitmap = optimizeForOcr(bitmap)
        val safeBitmap = ensureSafeBitmap(optimizedBitmap)

        try {
            // ▶ УЛУЧШЕНИЕ: Адаптивный PSM
            val psm = if (handwrittenMode) {
                // Для рукописного всегда SINGLE_BLOCK
                Log.d(TAG, "Handwritten mode: PSM_SINGLE_BLOCK")
                TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            } else {
                selectOptimalPsm(safeBitmap)
            }
            api.pageSegMode = psm

            // Настройки для рукописного текста (ПОСЛЕ установки PSM)
            if (handwrittenMode) {
                api.setVariable("classify_bln_numeric_mode", "0")
                api.setVariable("textord_heavy_nr", "1")
                api.setVariable("textord_force_make_prop_words", "0")
                api.setVariable("tessedit_char_whitelist", "")
                Log.d(TAG, "Handwritten mode: relaxed settings applied")
            }

            api.setImage(safeBitmap)

            val fullText = api.utF8Text.orEmpty()
            if (fullText.isBlank()) {
                Log.w(TAG, "Tesseract returned empty text")
                return OcrResult.EMPTY.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    engineName = name
                )
            }

            val meanConf = api.meanConfidence() / 100f
            val blocks = extractBlocks(api)
            val elapsed = System.currentTimeMillis() - startTime

            val resultFullText = EngineResultMapper.buildFullTextFromBlocks(blocks)

            return OcrResult(
                blocks = blocks,
                fullText = resultFullText.ifBlank { fullText },
                formattedText = resultFullText.ifBlank { fullText },
                averageConfidence = meanConf,
                processingTimeMs = elapsed,
                engineName = name,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )
        } catch (e: Exception) {
            Log.e(TAG, "Tesseract recognition error", e)
            return OcrResult.EMPTY.copy(
                processingTimeMs = System.currentTimeMillis() - startTime,
                engineName = name
            )
        } finally {
            api.clear()
            if (safeBitmap !== bitmap && safeBitmap !== optimizedBitmap) safeBitmap.recycle()
            if (optimizedBitmap !== bitmap) optimizedBitmap.recycle()
        }
    }

    /**
     * Извлечение структурированных блоков из Tesseract результата.
     */
    private fun extractBlocks(api: TessBaseAPI): List<TextBlock> {
        val blocks = mutableListOf<TextBlock>()

        try {
            val iterator = api.resultIterator ?: return blocks

            val currentWords = mutableListOf<TextWord>()
            val currentLines = mutableListOf<TextLine>()
            var currentLineText = StringBuilder()
            var currentBlockText = StringBuilder()

            iterator.begin()

            do {
                // Начало нового блока
                if (iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_BLOCK)) {
                    finishBlock(
                        currentBlockText, currentLines, currentLineText,
                        currentWords, blocks
                    )
                    currentBlockText = StringBuilder()
                    currentLines.clear()
                    currentLineText = StringBuilder()
                    currentWords.clear()
                }

                // Начало новой строки
                if (iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) {
                    finishLine(currentLineText, currentWords, currentLines, iterator)
                    currentLineText = StringBuilder()
                    currentWords.clear()
                }

                // Слово
                val wordText = iterator.getUTF8Text(
                    TessBaseAPI.PageIteratorLevel.RIL_WORD
                )?.trim()

                if (!wordText.isNullOrBlank()) {
                    val conf = iterator.confidence(
                        TessBaseAPI.PageIteratorLevel.RIL_WORD
                    ) / 100f

                    val rect = iterator.getBoundingBox(
                        TessBaseAPI.PageIteratorLevel.RIL_WORD
                    )

                    val box = if (rect != null && rect.size >= 4) {
                        BoundingBox(
                            rect[0].toFloat(), rect[1].toFloat(),
                            rect[2].toFloat(), rect[3].toFloat()
                        )
                    } else BoundingBox.EMPTY

                    currentWords.add(TextWord(wordText, box, conf))

                    if (currentLineText.isNotEmpty()) currentLineText.append(" ")
                    currentLineText.append(wordText)

                    if (currentBlockText.isNotEmpty()) {
                        if (iterator.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) {
                            currentBlockText.append("\n")
                        } else {
                            currentBlockText.append(" ")
                        }
                    }
                    currentBlockText.append(wordText)
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))

            // Последний блок
            finishBlock(
                currentBlockText, currentLines, currentLineText,
                currentWords, blocks
            )

            iterator.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting blocks (non-fatal)", e)
        }

        return blocks
    }

    private fun finishLine(
        lineText: StringBuilder,
        words: MutableList<TextWord>,
        lines: MutableList<TextLine>,
        iterator: ResultIterator
    ) {
        if (lineText.isEmpty()) return

        val lineConf = if (words.isNotEmpty()) {
            words.map { it.confidence }.average().toFloat()
        } else 0f

        val lineBox = if (words.isNotEmpty()) {
            words.map { it.boundingBox }
                .reduce { acc, box -> acc.union(box) }
        } else BoundingBox.EMPTY

        lines.add(TextLine(lineText.toString(), lineBox, words.toList(), lineConf))
    }

    private fun finishBlock(
        blockText: StringBuilder,
        lines: MutableList<TextLine>,
        lineText: StringBuilder,
        words: MutableList<TextWord>,
        blocks: MutableList<TextBlock>
    ) {
        if (lineText.isNotEmpty() && words.isNotEmpty()) {
            val lineConf = words.map { it.confidence }.average().toFloat()
            val lineBox = words.map { it.boundingBox }.reduce { acc, b -> acc.union(b) }
            lines.add(TextLine(lineText.toString(), lineBox, words.toList(), lineConf))
        }

        if (blockText.isNotEmpty() && lines.isNotEmpty()) {
            val blockConf = lines.map { it.confidence }.average().toFloat()
            val blockBox = lines.map { it.boundingBox }.reduce { acc, b -> acc.union(b) }
            blocks.add(TextBlock(blockText.toString(), blockBox, lines.toList(), blockConf))
        }
    }

    private fun ensureSafeBitmap(source: Bitmap): Bitmap {
        val needsCopy = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                source.config == Bitmap.Config.HARDWARE -> true
            source.config != Bitmap.Config.ARGB_8888 -> true
            else -> false
        }
        return if (needsCopy) source.copy(Bitmap.Config.ARGB_8888, true)
        else source
    }

    /**
     * Безопасное копирование traineddata.
     * Проверяет размер файла (защита от частичного копирования).
     */
    private fun copyTessDataSafe(): String {
        val dataDir = File(context.filesDir, TESSDATA_DIR)
        if (!dataDir.exists()) dataDir.mkdirs()

        val langs = LANGUAGES.split("+")
        for (lang in langs) {
            val fileName = "$lang.traineddata"
            val destFile = File(dataDir, fileName)

            if (destFile.exists() && destFile.length() > 100_000) {
                Log.d(TAG, "$fileName OK (${destFile.length()} bytes)")
                continue
            }

            if (destFile.exists()) {
                destFile.delete()
                Log.w(TAG, "Deleted corrupted $fileName")
            }

            try {
                context.assets.open("$TESSDATA_DIR/$fileName").use { input ->
                    FileOutputStream(destFile).use { output ->
                        val buffer = ByteArray(8192)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
                Log.i(TAG, "$fileName copied (${destFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy $fileName", e)
                throw RuntimeException(
                    "$TESSDATA_DIR/$fileName not found in assets. " +
                    "Download from https://github.com/tesseract-ocr/tessdata_best"
                )
            }
        }

        return context.filesDir.absolutePath
    }

    override fun release() {
        try { tessApi?.recycle() } catch (_: Exception) {}
        tessApi = null
        ready = false
        Log.d(TAG, "Tesseract released")
    }

    /**
     * Оптимизация изображения для Tesseract.
     *
     * Tesseract лучше всего работает при ~300 DPI.
     * Если изображение слишком маленькое — увеличиваем.
     * Если слишком большое — уменьшаем.
     */
    private fun optimizeForOcr(bitmap: Bitmap): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val minSide = minOf(bitmap.width, bitmap.height)

        return when {
            // Слишком маленькое — увеличиваем для лучшего распознавания
            minSide < 500 -> {
                val scale = 1500f / minSide
                Log.d(TAG, "Upscaling small image: ${bitmap.width}x${bitmap.height} " +
                    "→ scale=$scale")
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            }
            // Слишком большое — уменьшаем для скорости
            maxSide > 4000 -> {
                val scale = 3000f / maxSide
                Log.d(TAG, "Downscaling large image: ${bitmap.width}x${bitmap.height} " +
                    "→ scale=$scale")
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            }
            else -> bitmap
        }
    }

    /**
     * Выбор оптимального Page Segmentation Mode.
     *
     * PSM_AUTO — хорош для документов с несколькими блоками
     * PSM_SINGLE_BLOCK — лучше для одного блока текста (фото вывески)
     * PSM_SINGLE_LINE — для одной строки
     */
    private fun selectOptimalPsm(bitmap: Bitmap): Int {
        val aspectRatio = bitmap.width.toFloat() / bitmap.height

        return when {
            // Очень вытянутое горизонтально — скорее всего одна строка
            aspectRatio > 5f -> {
                Log.d(TAG, "PSM: SINGLE_LINE (aspect=$aspectRatio)")
                TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            }
            // Небольшое изображение — скорее всего один блок
            maxOf(bitmap.width, bitmap.height) < 1000 -> {
                Log.d(TAG, "PSM: SINGLE_BLOCK (small image)")
                TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            }
            // По умолчанию — автоматический
            else -> {
                Log.d(TAG, "PSM: AUTO")
                TessBaseAPI.PageSegMode.PSM_AUTO
            }
        }
    }
}