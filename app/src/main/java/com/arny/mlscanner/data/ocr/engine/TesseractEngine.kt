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

class TesseractEngine(private val context: Context) : OcrEngine {

    override val name = "Tesseract"

    companion object {
        private const val TAG = "TesseractEngine"
        private const val TESSDATA_DIR = "tessdata"
        private const val LANGUAGES = "rus+eng"

        private val GARBAGE_PATTERN = Regex("[|\\[\\]{}~`^\\\\]{2,}")

        private const val MIN_WORD_LENGTH = 1

        private const val MIN_WORD_CONFIDENCE = 20f
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

                configureApi(api)

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

    private fun configureApi(api: TessBaseAPI) {
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO

        api.setVariable("preserve_interword_spaces", "1")

        api.setVariable("textord_heavy_nr", "1")

        api.setVariable("textord_min_xheight", "6")

        api.setVariable("paragraph_text_based", "1")

        api.setVariable("load_system_dawg", "0")
        api.setVariable("load_freq_dawg", "0")
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

            Log.d(TAG, "Raw words: ${rawWords.size}, " +
                "cleaned: ${cleanedWords.size}, " +
                "conf=$meanConf, text='${fullText.take(60)}...'")

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

    private fun postProcessWords(words: List<WordWithConf>): List<WordWithConf> {
        return words
            .filter { it.confidence >= MIN_WORD_CONFIDENCE }
            .filter { word ->
                val text = word.text.trim()
                if (text.isEmpty()) return@filter false
                if (text.length == 1 && !text[0].isLetterOrDigit()) return@filter false
                true
            }
            .filter { word ->
                !GARBAGE_PATTERN.containsMatchIn(word.text)
            }
            .map { word ->
                word.copy(text = cleanWordText(word.text))
            }
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
                        BoundingBox(
                            rect[0].toFloat(), rect[1].toFloat(),
                            rect[2].toFloat(), rect[3].toFloat()
                        )
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
            compareBy<WordWithConf> { it.box.top }
                .thenBy { it.box.left }
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
            )
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
            val textWords = lineWords.map { w ->
                TextWord(w.text, w.box, w.confidence / 100f)
            }
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

    private fun selectPsm(bitmap: Bitmap): Int {
        val ratio = bitmap.width.toFloat() / bitmap.height
        return when {
            ratio > 6f || ratio < 0.16f -> TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            maxOf(bitmap.width, bitmap.height) < 800 ->
                TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            else -> TessBaseAPI.PageSegMode.PSM_AUTO
        }
    }

    private fun ensureOptimalSize(bitmap: Bitmap): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        val minSide = minOf(bitmap.width, bitmap.height)

        return when {
            minSide < 600 -> {
                val scale = 1800f / minSide
                Log.d(TAG, "Upscaling: ${bitmap.width}x${bitmap.height} x${"%.1f".format(scale)}")
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt(),
                    (bitmap.height * scale).toInt(),
                    true
                )
            }
            maxSide > 4000 -> {
                val scale = 3000f / maxSide
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
