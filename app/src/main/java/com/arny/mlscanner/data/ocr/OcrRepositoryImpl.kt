package com.arny.mlscanner.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.arny.mlscanner.data.ocr.engine.HybridEngine
import com.arny.mlscanner.data.ocr.engine.MLKitEngine
import com.arny.mlscanner.data.ocr.engine.OcrEngine
import com.arny.mlscanner.data.ocr.engine.TesseractEngine
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.OcrEngineType
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
 * Доступные движки: ML Kit, Tesseract, Hybrid
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
     * Инициализация всех движков параллельно.
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
            Log.i(TAG, "Init results: $results")
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

        // Multi-pass режим для Tesseract
        if (settings.engineType == OcrEngineType.TESSERACT && settings.useMultiPass) {
            return recognizeWithMultiPass(bitmap, settings)
        }

        // Preprocessing для разных движков
        val processed = when (settings.engineType) {
            OcrEngineType.TESSERACT -> imagePreprocessor.prepareForTesseract(bitmap, settings)
            OcrEngineType.HYBRID -> imagePreprocessor.prepareForTesseract(bitmap, settings)
            OcrEngineType.ML_KIT -> imagePreprocessor.prepareBaseImage(bitmap, settings)
            OcrEngineType.BARCODE -> throw IllegalStateException("Use ScanBarcodeUseCase for barcode scanning")
        }

        val engine: OcrEngine = when (settings.engineType) {
            OcrEngineType.ML_KIT -> mlkitEngine
            OcrEngineType.TESSERACT -> tesseractEngine
            OcrEngineType.HYBRID -> hybridEngine
            OcrEngineType.BARCODE -> throw IllegalStateException("Use ScanBarcodeUseCase for barcode scanning")
        }

        Log.d(TAG, "Using engine: ${settings.engineType.name}")
        val result = engine.recognize(processed, settings.handwrittenMode)

        if (processed !== bitmap && !processed.isRecycled) {
            processed.recycle()
        }

        return result
    }

    /**
     * Multi-pass распознавание для Tesseract.
     * Запускает несколько профилей и выбирает лучший результат.
     */
    private suspend fun recognizeWithMultiPass(
        bitmap: Bitmap,
        settings: ScanSettings
    ): OcrResult {
        val profiles = when (settings.language) {
            com.arny.mlscanner.domain.models.OcrLanguage.RUSSIAN -> 
                com.arny.mlscanner.data.ocr.engine.TesseractProfile.RUSSIAN_PROFILES
            com.arny.mlscanner.domain.models.OcrLanguage.ENGLISH -> 
                com.arny.mlscanner.data.ocr.engine.TesseractProfile.ENGLISH_PROFILES
            com.arny.mlscanner.domain.models.OcrLanguage.RUSSIAN_ENGLISH -> 
                com.arny.mlscanner.data.ocr.engine.TesseractProfile.MIXED_PROFILES
        }

        // Не делаем preprocessing здесь — каждый профиль применит свой
        val bestCandidate = tesseractEngine.recognizeMultiPass(bitmap, profiles)

        Log.d(TAG, "Multi-pass completed: profile='${bestCandidate.profile.name}', " +
                "score=${bestCandidate.score}, conf=${bestCandidate.confidence}")

        return bestCandidate.result
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

        val result = engine.recognize(processed, settings.handwrittenMode)

        if (processed !== bitmap && !processed.isRecycled) {
            processed.recycle()
        }

        return result
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