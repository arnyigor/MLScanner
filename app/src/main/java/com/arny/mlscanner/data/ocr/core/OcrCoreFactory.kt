package com.arny.mlscanner.data.ocr.core

import android.content.Context
import android.util.Log
import com.arny.mlscanner.data.ocr.core.adapter.GoogleMLKitEngineAdapter
import com.arny.mlscanner.data.ocr.core.adapter.HuaweiMLKitEngineAdapter
import com.arny.mlscanner.data.ocr.core.adapter.TesseractEngineAdapter
import com.arny.mlscanner.data.ocr.core.postprocessing.*
import com.arny.mlscanner.data.ocr.core.strategy.OcrStrategySelector
import com.arny.mlscanner.data.ocr.engine.HuaweiMLKitEngine
import com.arny.mlscanner.data.ocr.engine.MLKitEngine
import com.arny.mlscanner.data.ocr.engine.TesseractEngine

/**
 * Фабрика для создания OCR Core Pipeline.
 * Инкапсулирует всю сложность инициализации.
 */
class OcrCoreFactory(private val context: Context) {
    
    companion object {
        private const val TAG = "OcrCoreFactory"
    }

    private val registry: OcrEngineRegistry by lazy {
        createEngineRegistry()
    }
    
    /**
     * Создаёт полностью настроенный OCR Pipeline.
     */
    fun createPipeline(): OcrPipeline {
        Log.d(TAG, "Creating OCR Core Pipeline...")
        
        // 1. Создаём постпроцессоры
        val postProcessors = createPostProcessors()
        
        // 2. Создаём анализатор качества
        val qualityAnalyzer = OcrQualityAnalyzer()
        
        // 3. Создаём селектор результатов
        val resultSelector = OcrResultSelector()
        
        // 4. Создаём селектор стратегий
        val strategySelector = OcrStrategySelector(registry)
        
        // 5. Собираем pipeline
        val pipeline = OcrPipeline(
            engineRegistry = registry,
            strategySelector = strategySelector,
            postProcessors = postProcessors,
            qualityAnalyzer = qualityAnalyzer,
            resultSelector = resultSelector
        )
        
        Log.d(TAG, "OCR Core Pipeline created successfully")
        return pipeline
    }
    
    /**
     * Создаёт и регистрирует все движки.
     */
    private fun createEngineRegistry(): OcrEngineRegistry {
        val registry = OcrEngineRegistry()
        
        // Google ML Kit
        val mlkitEngine = MLKitEngine()
        val mlkitAdapter = GoogleMLKitEngineAdapter(mlkitEngine)
        registry.register(mlkitAdapter)
        Log.d(TAG, "Registered: Google ML Kit")
        
        // Tesseract
        val tesseractEngine = TesseractEngine(context)
        val tesseractAdapter = TesseractEngineAdapter(tesseractEngine)
        registry.register(tesseractAdapter)
        Log.d(TAG, "Registered: Tesseract")
        
        // Huawei ML Kit (optional, experimental)
        try {
            val huaweiEngine = HuaweiMLKitEngine()
            val huaweiAdapter = HuaweiMLKitEngineAdapter(huaweiEngine)
            registry.register(huaweiAdapter)
            Log.d(TAG, "Registered: Huawei ML Kit (experimental)")
        } catch (e: Exception) {
            Log.w(TAG, "Huawei ML Kit not available: ${e.message}")
        }
        
        return registry
    }
    
/**
     * Создаёт список постпроцессоров в правильном порядке.
     */
    private fun createPostProcessors(): List<IOcrPostProcessor> {
        return listOf(
            // 1. Сначала исправляем визуальные замены (Latin→Cyrillic)
            RussianPostProcessorAdapter(),

            // 2. Восстанавливаем латиницу в URL после RussianPostProcessor
            UrlLatinRestorer(),

            // 3. Нормализуем даты и суммы
            DateNormalizer(),
            AmountNormalizer(),

            // 4. Нормализуем пробелы и переносы
            WhitespaceNormalizer(),
            LineBreakNormalizer()
        )
    }
    
    /**
     * Получить реестр движков для прямого доступа (например, для benchmark).
     */
    fun getEngineRegistry(): OcrEngineRegistry {
        return registry
    }
}

/**
 * Конвертер из OcrRequest в ScanSettings (для обратной совместимости).
 */
fun OcrRequest.toScanSettings(): com.arny.mlscanner.domain.models.ScanSettings {
    return com.arny.mlscanner.domain.models.ScanSettings(
        language = this.language,
        engineType = when (this.enginePolicy) {
            OcrEnginePolicy.GOOGLE_MLKIT_ONLY -> com.arny.mlscanner.domain.models.OcrEngineType.ML_KIT
            OcrEnginePolicy.TESSERACT_ONLY -> com.arny.mlscanner.domain.models.OcrEngineType.TESSERACT
            OcrEnginePolicy.HUAWEI_ONLY -> com.arny.mlscanner.domain.models.OcrEngineType.HUAWEI_ML_KIT
            OcrEnginePolicy.HYBRID -> com.arny.mlscanner.domain.models.OcrEngineType.HYBRID
            else -> com.arny.mlscanner.domain.models.OcrEngineType.HYBRID
        },
        handwrittenMode = this.handwrittenMode,
        useMultiPass = this.qualityMode == OcrQualityMode.ACCURATE
    )
}

/**
 * Конвертер из ScanSettings в OcrRequest (для обратной совместимости).
 */
fun com.arny.mlscanner.domain.models.ScanSettings.toOcrRequest(
    bitmap: android.graphics.Bitmap
): OcrRequest {
    return OcrRequest(
        bitmap = bitmap,
        language = this.language,
        taskType = OcrTaskType.GENERAL_TEXT,
        enginePolicy = when (this.engineType) {
            com.arny.mlscanner.domain.models.OcrEngineType.ML_KIT -> OcrEnginePolicy.GOOGLE_MLKIT_ONLY
            com.arny.mlscanner.domain.models.OcrEngineType.TESSERACT -> OcrEnginePolicy.TESSERACT_ONLY
            com.arny.mlscanner.domain.models.OcrEngineType.HUAWEI_ML_KIT -> OcrEnginePolicy.HUAWEI_ONLY
            com.arny.mlscanner.domain.models.OcrEngineType.HYBRID -> OcrEnginePolicy.HYBRID
            com.arny.mlscanner.domain.models.OcrEngineType.BARCODE -> OcrEnginePolicy.FAST
        },
        qualityMode = if (this.useMultiPass) OcrQualityMode.ACCURATE else OcrQualityMode.BALANCED,
        handwrittenMode = this.handwrittenMode,
        allowCloud = false,
        allowExperimental = false,
        preprocessingProfile = OcrPreprocessingProfile.AUTO
    )
}

/**
 * Конвертер из OcrFinalResult в OcrResult (для обратной совместимости).
 */
fun OcrFinalResult.toOcrResult(): com.arny.mlscanner.domain.models.OcrResult {
    val candidate = this.selectedCandidate
    
    return com.arny.mlscanner.domain.models.OcrResult(
        blocks = candidate.blocks,
        fullText = candidate.processedText,
        formattedText = candidate.processedText,
        averageConfidence = candidate.confidence ?: 0f,
        processingTimeMs = candidate.processingTimeMs,
        engineName = candidate.engineName,
        imageWidth = 0,
        imageHeight = 0
    )
}
