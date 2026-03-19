package com.arny.mlscanner.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.arny.mlscanner.data.ocr.engine.HybridEngine
import com.arny.mlscanner.data.ocr.engine.MLKitEngine
import com.arny.mlscanner.data.ocr.engine.OcrEngine
import com.arny.mlscanner.data.ocr.engine.TesseractEngine
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.usecases.OcrRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Реализация OcrRepository.
 *
 * Управляет lifecycle OCR-движков и предобработкой.
 * Единственная точка доступа к OCR из domain слоя.
 *
 * ▶ FIX: Движки создаются ОДИН раз и переиспользуются
 *   (HybridEngine получает ссылки на те же MLKit и Tesseract)
 */
class OcrRepositoryImpl(
    private val context: Context,
    private val imagePreprocessor: ImagePreprocessor
) : OcrRepository {

    companion object {
        private const val TAG = "OcrRepository"
    }

    // Движки создаются один раз
    private val mlkitEngine: MLKitEngine = MLKitEngine()
    private val tesseractEngine: TesseractEngine = TesseractEngine(context)

    // Hybrid использует ТЕ ЖЕ экземпляры
    private val hybridEngine: HybridEngine = HybridEngine(mlkitEngine, tesseractEngine)

    private var initialized = false
    private val initMutex = Mutex()

    /**
     * Инициализация обоих движков параллельно.
     */
    override suspend fun initialize(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        initMutex.withLock {
            if (initialized) {
                return@withContext mapOf(
                    "ML Kit" to mlkitEngine.isReady(),
                    "Tesseract" to tesseractEngine.isReady()
                )
            }

            Log.i(TAG, "Initializing OCR engines...")

            val mlkitResult = async { mlkitEngine.initialize() }
            val tessResult = async { tesseractEngine.initialize() }

            val results = mapOf(
                "ML Kit" to mlkitResult.await(),
                "Tesseract" to tessResult.await()
            )

            initialized = results.values.any { it }
            Log.i(TAG, "Init: $results")
            results
        }
    }

    override suspend fun recognize(
        bitmap: Bitmap,
        settings: ScanSettings
    ): OcrResult {
        if (!initialized) {
            initialize()
        }

        val processed = imagePreprocessor.prepareBaseImage(bitmap, settings)

        val result = hybridEngine.recognize(processed, settings.handwrittenMode)

        if (processed !== bitmap && !processed.isRecycled) {
            processed.recycle()
        }

        return result
    }

    /**
     * Распознавание конкретным движком (для бенчмарка).
     */
    override suspend fun recognizeWith(
        bitmap: Bitmap,
        engineName: String,
        settings: ScanSettings
    ): OcrResult {
        if (!initialized) initialize()

        val processed = imagePreprocessor.prepareBaseImage(bitmap, settings)
        val engine: OcrEngine = when (engineName.uppercase()) {
            "MLKIT" -> mlkitEngine
            "TESSERACT" -> tesseractEngine
            "HYBRID" -> hybridEngine
            else -> hybridEngine
        }

        return try {
            engine.recognize(processed, settings.handwrittenMode)
        } finally {
            if (processed !== bitmap) processed.recycle()
        }
    }

    override fun isReady(): Boolean = initialized

    override fun release() {
        mlkitEngine.release()
        tesseractEngine.release()
        // hybridEngine.release() — не нужно, т.к. он не владеет движками
        initialized = false
        Log.d(TAG, "All engines released")
    }
}