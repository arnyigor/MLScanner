package com.arny.mlscanner.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.arny.mlscanner.data.ocr.core.*
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.OcrEngineType
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.usecases.OcrRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Новая реализация OcrRepository на базе OCR Core Pipeline.
 * 
 * Теперь это тонкий фасад над универсальным ядром.
 * Вся логика оркестрации вынесена в OcrPipeline.
 */
class OcrRepositoryImplV2(
    context: Context,
    private val imagePreprocessor: ImagePreprocessor
) : OcrRepository {

    companion object {
        private const val TAG = "OcrRepositoryV2"
    }

    private val factory = OcrCoreFactory(context)
    private val pipeline: OcrPipeline = factory.createPipeline()
    private val engineRegistry: OcrEngineRegistry = factory.getEngineRegistry()
    
    private var initialized = false
    private val initMutex = Mutex()

    /**
     * Инициализация всех движков через реестр.
     */
    override suspend fun initialize(): Map<String, Boolean> {
        return initMutex.withLock {
            if (initialized) {
                return@withLock getInitializationStatus()
            }

            Log.i(TAG, "Initializing OCR Core Pipeline...")
            
            val states = engineRegistry.initializeAll()
            initialized = states.values.any { it is OcrEngineState.Ready }
            
            val results = states.mapValues { (_, state) ->
                state is OcrEngineState.Ready
            }
            
            Log.i(TAG, "Initialization complete: $results")
            results
        }
    }

    /**
     * Распознавание через pipeline.
     */
    override suspend fun recognize(
        bitmap: Bitmap,
        settings: ScanSettings
    ): OcrResult {
        if (!initialized) {
            initialize()
        }

        val processed = prepareImage(bitmap, settings)

        try {
            val request = settings.toOcrRequest(processed)
        
            Log.d(TAG, "Starting recognition: task=${request.taskType}, " +
                    "policy=${request.enginePolicy}, language=${request.language}")

            val finalResult = pipeline.recognize(request)
        
            logResult(finalResult)
        
            return finalResult.toOcrResult()
        } finally {
            if (processed !== bitmap && !processed.isRecycled) {
                processed.recycle()
            }
        }
    }

    /**
     * Распознавание конкретным движком (для бенчмарка).
     */
    override suspend fun recognizeWith(
        bitmap: Bitmap,
        engineName: String,
        settings: ScanSettings
    ): OcrResult {
        if (!initialized) {
            initialize()
        }

        val engineId = when (engineName.uppercase()) {
            "MLKIT" -> "google_mlkit"
            "TESSERACT" -> "tesseract"
            "HUAWEI" -> "huawei_mlkit"
            else -> "google_mlkit"
        }

        val engine = engineRegistry.getEngine(engineId)
        if (engine == null || !engine.isReady()) {
            Log.w(TAG, "Engine $engineId not available")
            return OcrResult.EMPTY
        }

        val processed = imagePreprocessor.prepareBaseImage(bitmap, settings)
        val request = settings.toOcrRequest(processed)
        val preparedRequest = PreparedOcrRequest(
            bitmap = processed,
            originalRequest = request,
            preprocessingApplied = emptyList()
        )

        val candidate = try {
            engine.recognize(preparedRequest)
        } finally {
            if (processed !== bitmap && !processed.isRecycled) {
                processed.recycle()
            }
        }
        
        return OcrResult(
            blocks = candidate.blocks,
            fullText = candidate.rawText,
            formattedText = candidate.rawText,
            averageConfidence = candidate.confidence ?: 0f,
            processingTimeMs = candidate.processingTimeMs,
            engineName = candidate.engineName
        )
    }

    override fun isReady(): Boolean = initialized

    override fun release() {
        engineRegistry.releaseAll()
        initialized = false
        Log.d(TAG, "OCR Core Pipeline released")
    }

    private fun prepareImage(bitmap: Bitmap, settings: ScanSettings): Bitmap {
        return when (settings.engineType) {
            OcrEngineType.TESSERACT,
            OcrEngineType.HYBRID -> imagePreprocessor.prepareForTesseract(bitmap, settings)
            OcrEngineType.ML_KIT,
            OcrEngineType.HUAWEI_ML_KIT -> imagePreprocessor.prepareBaseImage(bitmap, settings)
            OcrEngineType.BARCODE -> throw IllegalStateException("Use ScanBarcodeUseCase for barcode scanning")
        }
    }
    
    /**
     * Получить статус инициализации всех движков.
     */
    private fun getInitializationStatus(): Map<String, Boolean> {
        return engineRegistry.getAllEngines().associate { engine ->
            engine.name to engine.isReady()
        }
    }
    
    /**
     * Логирование результата распознавания.
     */
    private fun logResult(result: OcrFinalResult) {
        val quality = result.quality
        val candidate = result.selectedCandidate
        
        Log.d(TAG, buildString {
            append("Recognition complete: ")
            append("engine=${candidate.engineName}, ")
            append("time=${candidate.processingTimeMs}ms, ")
            append("words=${quality?.wordCount ?: 0}, ")
            append("score=${"%.2f".format(quality?.score ?: 0f)}, ")
            append("confidence=${"%.2f".format(candidate.confidence ?: 0f)}")
            
            if (result.fallbackUsed) {
                append(", fallback=true")
            }
            
            if (result.warnings.isNotEmpty()) {
                append(", warnings=${result.warnings.size}")
            }
        })
        
        if (quality?.suspiciousReasons?.isNotEmpty() == true) {
            Log.w(TAG, "Quality issues: ${quality.suspiciousReasons.joinToString()}")
        }
        
        if (result.warnings.isNotEmpty()) {
            Log.w(TAG, "Warnings: ${result.warnings.joinToString()}")
        }
    }
}
