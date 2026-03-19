// ============================================================
// data/ocr/engine/HybridEngine.kt — УПРОЩЁННЫЙ
// Tesseract всегда первый, ML Kit только как проверка
// ============================================================
package com.arny.mlscanner.data.ocr.engine

import android.graphics.Bitmap
import android.util.Log
import com.arny.mlscanner.domain.models.OcrResult

class HybridEngine(
    private val mlkit: OcrEngine,
    private val tesseract: OcrEngine
) : OcrEngine {

    override val name = "Hybrid"

    companion object {
        private const val TAG = "HybridEngine"
    }

    override suspend fun initialize(): Boolean {
        return tesseract.isReady() || mlkit.isReady()
    }

    override fun isReady(): Boolean = tesseract.isReady() || mlkit.isReady()

    override suspend fun recognize(
        bitmap: Bitmap,
        handwrittenMode: Boolean
    ): OcrResult {
        val totalStart = System.currentTimeMillis()

        // Шаг 1: Tesseract (rus+eng)
        var tessResult: OcrResult? = null
        if (tesseract.isReady()) {
            try {
                tessResult = tesseract.recognize(bitmap, handwrittenMode)
                Log.d(TAG, "Tesseract: words=${tessResult.wordCount}, " +
                    "conf=${tessResult.averageConfidence}, " +
                    "text='${tessResult.fullText.take(50)}...'")

                if (!tessResult.isEmpty && tessResult.fullText.trim().length >= 2) {
                    val totalTime = System.currentTimeMillis() - totalStart
                    return tessResult.copy(
                        processingTimeMs = totalTime,
                        engineName = "Hybrid → Tesseract"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tesseract failed", e)
            }
        }

        // Шаг 2: ML Kit fallback
        if (mlkit.isReady() && !bitmap.isRecycled) {
            try {
                val mlkitResult = mlkit.recognize(bitmap, handwrittenMode)
                Log.d(TAG, "ML Kit fallback: words=${mlkitResult.wordCount}, " +
                    "text='${mlkitResult.fullText.take(50)}...'")

                if (!mlkitResult.isEmpty) {
                    val totalTime = System.currentTimeMillis() - totalStart

                    // Если Tesseract пуст — принимаем ML Kit как есть
                    if (tessResult == null || tessResult.isEmpty) {
                        Log.d(TAG, "Tesseract empty → using ML Kit")
                        return mlkitResult.copy(
                            processingTimeMs = totalTime,
                            engineName = "Hybrid → ML Kit (tess empty)"
                        )
                    }

                    // Если Tesseract дал хоть что-то — сравниваем
                    val best = if (tessResult.wordCount > mlkitResult.wordCount) {
                        tessResult
                    } else {
                        mlkitResult
                    }

                    return best.copy(
                        processingTimeMs = totalTime,
                        engineName = "Hybrid → ${best.engineName}"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit fallback failed", e)
            }
        }

        // Шаг 3: Возвращаем что есть
        val totalTime = System.currentTimeMillis() - totalStart
        return (tessResult ?: OcrResult.EMPTY).copy(
            processingTimeMs = totalTime,
            engineName = "Hybrid → ${tessResult?.engineName ?: "None"}"
        )
    }

    override fun release() {
        Log.d(TAG, "Hybrid released")
    }
}
