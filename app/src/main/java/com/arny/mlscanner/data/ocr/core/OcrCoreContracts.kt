package com.arny.mlscanner.data.ocr.core

/**
 * Возможности OCR-движка.
 */
data class OcrEngineCapabilities(
    val engineId: String,
    val displayName: String,
    val isOffline: Boolean,
    val isExperimental: Boolean,
    val requiresGooglePlayServices: Boolean = false,
    val requiresHmsCore: Boolean = false,
    val supportedLanguages: Set<String>,
    val supportedScripts: Set<OcrScript>,
    val supportedTasks: Set<OcrTaskType>,
    val speedTier: OcrSpeedTier,
    val accuracyTier: OcrAccuracyTier
)

/**
 * Скрипты письменности.
 */
enum class OcrScript {
    LATIN,
    CYRILLIC,
    CHINESE,
    JAPANESE,
    KOREAN,
    ARABIC,
    DEVANAGARI,
    MIXED
}

/**
 * Уровень скорости.
 */
enum class OcrSpeedTier {
    VERY_FAST,  // < 100ms
    FAST,       // 100-500ms
    MEDIUM,     // 500-1500ms
    SLOW        // > 1500ms
}

/**
 * Уровень точности.
 */
enum class OcrAccuracyTier {
    BASIC,      // Базовая точность
    GOOD,       // Хорошая для печатного текста
    EXCELLENT,  // Отличная для документов
    BEST        // Лучшая для критичных задач
}

/**
 * Состояние движка.
 */
sealed class OcrEngineState {
    object NotInitialized : OcrEngineState()
    object Initializing : OcrEngineState()
    data class Ready(val capabilities: OcrEngineCapabilities) : OcrEngineState()
    data class Error(val message: String, val cause: Throwable? = null) : OcrEngineState()
    object Unavailable : OcrEngineState()
}

/**
 * Контракт OCR-движка для core pipeline.
 */
interface IOcrEngine {
    val id: String
    val name: String
    val capabilities: OcrEngineCapabilities

    suspend fun initialize(): OcrEngineState
    fun isReady(): Boolean
    suspend fun recognize(request: PreparedOcrRequest): OcrCandidate
    fun release()
}

/**
 * Контракт постпроцессора.
 */
interface IOcrPostProcessor {
    val id: String
    val name: String
    
    fun supports(request: OcrRequest): Boolean
    fun process(text: String, request: OcrRequest): OcrPostProcessResult
}

/**
 * Контракт анализатора качества.
 */
interface IOcrQualityAnalyzer {
    fun analyze(candidate: OcrCandidate, processedText: String, request: OcrRequest): OcrQualityReport
}

/**
 * Контракт селектора результатов.
 */
interface IOcrResultSelector {
    fun selectBest(candidates: List<OcrCandidate>, request: OcrRequest): OcrFinalResult
}

/**
 * Контракт стратегии распознавания.
 */
interface IOcrStrategy {
    val id: String
    val name: String
    
    suspend fun recognize(request: PreparedOcrRequest): List<OcrCandidate>
}

/**
 * Контракт селектора стратегий.
 */
interface IOcrStrategySelector {
    fun select(request: OcrRequest): IOcrStrategy
}

/**
 * Контракт препроцессора изображений.
 */
interface IOcrPreprocessor {
    val id: String
    
    fun supports(engineId: String, taskType: OcrTaskType): Boolean
    suspend fun prepare(bitmap: android.graphics.Bitmap, request: OcrRequest): PreparedOcrRequest
}
