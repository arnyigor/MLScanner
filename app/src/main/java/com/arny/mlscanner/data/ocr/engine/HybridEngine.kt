// ============================================================
// data/ocr/engine/HybridEngine.kt
// Параллельный запуск движков с выбором лучшего результата
// ============================================================
package com.arny.mlscanner.data.ocr.engine

import android.graphics.Bitmap
import android.util.Log
import com.arny.mlscanner.domain.models.OcrResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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

        return coroutineScope {
            val tessDeferred = async {
                if (tesseract.isReady()) {
                    try {
                        tesseract.recognize(bitmap, handwrittenMode)
                    } catch (e: Exception) {
                        Log.w(TAG, "Tesseract failed", e)
                        null
                    }
                } else null
            }

            val mlkitDeferred = async {
                if (mlkit.isReady() && !bitmap.isRecycled) {
                    try {
                        mlkit.recognize(bitmap, handwrittenMode)
                    } catch (e: Exception) {
                        Log.w(TAG, "ML Kit failed", e)
                        null
                    }
                } else null
            }

            val tessResult = tessDeferred.await()
            val mlkitResult = mlkitDeferred.await()

            Log.d(TAG, "Tesseract: words=${tessResult?.wordCount}, conf=${tessResult?.averageConfidence}")
            Log.d(TAG, "ML Kit: words=${mlkitResult?.wordCount}, conf=${mlkitResult?.averageConfidence}")

            val best = when {
                tessResult == null || tessResult.isEmpty -> mlkitResult
                mlkitResult == null || mlkitResult.isEmpty -> tessResult
                else -> selectBest(tessResult, mlkitResult)
            }

            val totalTime = System.currentTimeMillis() - totalStart
            (best ?: OcrResult.EMPTY).copy(
                processingTimeMs = totalTime,
                engineName = "Hybrid → ${best?.engineName ?: "None"}"
            )
        }
    }

    private fun selectBest(tess: OcrResult, mlkit: OcrResult): OcrResult {
        // Сравниваем по количеству слов и confidence
        val tessScore = tess.wordCount * tess.averageConfidence
        val mlkitScore = mlkit.wordCount * mlkit.averageConfidence

        return if (tessScore >= mlkitScore) tess else mlkit
    }

    override fun release() {
        Log.d(TAG, "Hybrid released")
    }
}
