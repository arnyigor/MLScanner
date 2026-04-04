package com.arny.mlscanner.data.ocr.engine

import android.graphics.Bitmap
import android.util.Log
import com.arny.mlscanner.data.ocr.mapper.EngineResultMapper
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.TextBlock
import com.arny.mlscanner.domain.models.TextLine
import com.arny.mlscanner.domain.models.TextWord
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MLKitEngine : OcrEngine {

    override val name = "ML Kit"

    companion object {
        private const val TAG = "MLKitEngine"
    }

    private var recognizer: TextRecognizer? = null
    private var ready = false
    private val mutex = Mutex()

    override suspend fun initialize(): Boolean {
        return mutex.withLock {
            if (ready) return@withLock true
            try {
                // ML Kit v2 - uses bundled model for offline OCR
                // Supports Latin, Cyrillic, Chinese, Japanese, Korean, Devanagari scripts
                // https://developers.google.com/ml-kit/vision/text-recognition/v2/android
                recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                ready = true
                Log.i(TAG, "ML Kit initialized (bundled model)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "ML Kit init failed", e)
                false
            }
        }
    }

    override fun isReady(): Boolean = ready

    override suspend fun recognize(bitmap: Bitmap, handwrittenMode: Boolean): OcrResult {
        val rec = recognizer
        if (rec == null || !ready) {
            Log.w(TAG, "ML Kit not ready, returning empty")
            return OcrResult.EMPTY
        }

        val startTime = System.currentTimeMillis()
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        return try {
            val visionText = processImage(rec, inputImage)
            val elapsed = System.currentTimeMillis() - startTime
            mapToOcrResult(visionText, elapsed, bitmap.width, bitmap.height, handwrittenMode)
        } catch (e: Exception) {
            Log.e(TAG, "ML Kit recognition error", e)
            OcrResult.EMPTY.copy(
                processingTimeMs = System.currentTimeMillis() - startTime,
                engineName = name
            )
        }
    }

    private suspend fun processImage(
        recognizer: TextRecognizer,
        image: InputImage
    ): Text = suspendCancellableCoroutine { cont ->
        recognizer.process(image)
            .addOnSuccessListener { text ->
                if (cont.isActive) cont.resume(text)
            }
            .addOnFailureListener { e ->
                if (cont.isActive) cont.resumeWithException(e)
            }
    }

    private fun mapToOcrResult(
        text: Text,
        elapsedMs: Long,
        imgW: Int,
        imgH: Int,
        handwrittenMode: Boolean = false
    ): OcrResult {
        val blocks = text.textBlocks.map { mlBlock ->
            val lines = mlBlock.lines.map { mlLine ->
                val words = mlLine.elements.map { mlElement ->
                    // Для рукописного текста — снижаем confidence (он менее уверенный)
                    val wordConf = if (handwrittenMode) {
                        estimateConfidence(mlElement.text) * 0.7f
                    } else {
                        estimateConfidence(mlElement.text)
                    }
                TextWord(
                    text = mlElement.text,
                    boundingBox = mlElement.boundingBox?.let {
                        BoundingBox(
                            it.left.toFloat(),
                            it.top.toFloat(),
                            it.right.toFloat(),
                            it.bottom.toFloat()
                        )
                    } ?: BoundingBox.EMPTY,
                    confidence = wordConf
                )
                }

                TextLine(
                    text = mlLine.text,
                    boundingBox = mlLine.boundingBox?.let {
                        BoundingBox(
                            it.left.toFloat(), it.top.toFloat(),
                            it.right.toFloat(), it.bottom.toFloat()
                        )
                    } ?: BoundingBox.EMPTY,
                    words = words,
                    confidence = if (words.isNotEmpty()) {
                        words.map { it.confidence }.average().toFloat()
                    } else 0f
                )
            }

             TextBlock(
                text = mlBlock.text,
                boundingBox = mlBlock.boundingBox?.let {
                    BoundingBox(
                        it.left.toFloat(), it.top.toFloat(),
                        it.right.toFloat(), it.bottom.toFloat()
                    )
                } ?: BoundingBox.EMPTY,
                lines = lines,
                confidence = if (handwrittenMode) {
                    // Рукописный текст — снижаем confidence блока
                    estimateConfidence(mlBlock.text) * 0.7f
                } else {
                    estimateConfidence(mlBlock.text)
                }
            )
        }

        val fullText = EngineResultMapper.buildFullTextFromBlocks(blocks)
        val avgConf = EngineResultMapper.calculateAverageConfidence(blocks)

        return OcrResult(
            blocks = blocks,
            fullText = fullText,
            formattedText = fullText,
            averageConfidence = avgConf,
            processingTimeMs = elapsedMs,
            engineName = name,
            imageWidth = imgW,
            imageHeight = imgH
        )
    }

    /**
     * Оценка confidence для ML Kit Latin model.
     * 
     * ML Kit Latin не возвращает per-word confidence,
     * поэтому оцениваем эвристически:
     * - Длинные слова из букв/цифр → высокая уверенность
     * - Короткие слова с спецсимволами → низкая
     */
    private fun estimateConfidence(text: String): Float {
        if (text.isBlank()) return 0f

        val letters = text.count { it.isLetterOrDigit() }
        val total = text.length

        // Доля "нормальных" символов
        val cleanRatio = letters.toFloat() / total

        // Бонус за длину (длинные слова обычно распознаны правильно)
        val lengthBonus = when {
            total >= 5 -> 0.1f
            total >= 3 -> 0.05f
            else -> 0f
        }

        // Базовый confidence для ML Kit: ~0.85 (обычно хорошо распознаёт)
        val base = 0.85f

        return (base * cleanRatio + lengthBonus).coerceIn(0f, 1f)
    }

    override fun release() {
        recognizer?.close()
        recognizer = null
        ready = false
        Log.d(TAG, "ML Kit released")
    }
}