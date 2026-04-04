// ============================================================
// data/ocr/engine/HybridEngine.kt
// Параллельный запуск движков с выбором лучшего результата
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

        if (mlkit.isReady() && !bitmap.isRecycled) {
            try {
                val mlkitResult = mlkit.recognize(bitmap, handwrittenMode)

                if (!mlkitResult.isEmpty) {
                    val cyrillicCount = mlkitResult.fullText.count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
                    val lettersCount = mlkitResult.fullText.count { it.isLetter() }
                    val cyrillicRatio = if (lettersCount > 0) cyrillicCount.toFloat() / lettersCount else 0f

                    val isConfident = mlkitResult.averageConfidence > 0.6f
                    val isLanguageValid = cyrillicRatio > 0.2f || lettersCount < 5

                    if (isConfident && isLanguageValid) {
                        return mlkitResult.copy(
                            processingTimeMs = System.currentTimeMillis() - totalStart,
                            engineName = "Hybrid → ML Kit"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit failed", e)
            }
        }

        if (tesseract.isReady() && !bitmap.isRecycled) {
            try {
                val tessResult = tesseract.recognize(bitmap, handwrittenMode)
                return tessResult.copy(
                    processingTimeMs = System.currentTimeMillis() - totalStart,
                    engineName = "Hybrid → Tesseract"
                )
            } catch (e: Exception) {
                Log.w(TAG, "Tesseract failed", e)
            }
        }

        return OcrResult.EMPTY.copy(processingTimeMs = System.currentTimeMillis() - totalStart)
    }

    override fun release() {
        Log.d(TAG, "Hybrid released")
    }
}
