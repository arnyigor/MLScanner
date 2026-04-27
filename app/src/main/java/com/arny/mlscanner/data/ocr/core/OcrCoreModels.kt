package com.arny.mlscanner.data.ocr.core

import android.graphics.Bitmap
import com.arny.mlscanner.domain.models.OcrLanguage
import com.arny.mlscanner.domain.models.TextBlock

/**
 * Запрос на распознавание текста.
 * Отделён от UI-настроек (ScanSettings).
 */
data class OcrRequest(
    val bitmap: Bitmap,
    val language: OcrLanguage = OcrLanguage.RUSSIAN,
    val taskType: OcrTaskType = OcrTaskType.GENERAL_TEXT,
    val enginePolicy: OcrEnginePolicy = OcrEnginePolicy.AUTO,
    val qualityMode: OcrQualityMode = OcrQualityMode.BALANCED,
    val handwrittenMode: Boolean = false,
    val allowCloud: Boolean = false,
    val allowExperimental: Boolean = false,
    val preprocessingProfile: OcrPreprocessingProfile = OcrPreprocessingProfile.AUTO
)

/**
 * Тип задачи OCR.
 */
enum class OcrTaskType {
    GENERAL_TEXT,
    DOCUMENT,
    RECEIPT,
    DRIVER_LICENSE,
    PASSPORT,
    PDF_PAGE,
    BARCODE,
    HANDWRITING,
    SINGLE_LINE,
    SECURE_FIELD
}

/**
 * Политика выбора движка.
 */
enum class OcrEnginePolicy {
    AUTO,           // Автоматический выбор по задаче
    FAST,           // Приоритет скорости
    ACCURATE,       // Приоритет точности
    GOOGLE_MLKIT_ONLY,
    TESSERACT_ONLY,
    HUAWEI_ONLY,
    ONNX_ONLY,
    HYBRID          // ML Kit + Tesseract fallback
}

/**
 * Режим качества.
 */
enum class OcrQualityMode {
    FAST,           // Быстро, один проход
    BALANCED,       // Баланс скорости и качества
    ACCURATE        // Максимальная точность, multi-pass
}

/**
 * Профиль предобработки.
 */
enum class OcrPreprocessingProfile {
    AUTO,           // Автоматический выбор
    MINIMAL,        // Минимальная обработка (для ML Kit)
    AGGRESSIVE,     // Агрессивная (для Tesseract)
    DOCUMENT,       // Для документов (perspective correction)
    RECEIPT,        // Для чеков (sparse text)
    HANDWRITING     // Для рукописи (line segmentation)
}

/**
 * Кандидат результата от одного движка.
 */
data class OcrCandidate(
    val engineId: String,
    val engineName: String,
    val rawText: String,
    val blocks: List<TextBlock>,
    val confidence: Float?,
    val processingTimeMs: Long,
    val metadata: Map<String, String> = emptyMap(),
    // После постобработки
    var processedText: String = rawText,
    var quality: OcrQualityReport? = null
)

/**
 * Финальный результат после выбора лучшего кандидата.
 */
data class OcrFinalResult(
    val selectedCandidate: OcrCandidate,
    val allCandidates: List<OcrCandidate>,
    val strategy: String,
    val fallbackUsed: Boolean,
    val warnings: List<String> = emptyList()
) {
    val text: String get() = selectedCandidate.processedText
    val blocks: List<TextBlock> get() = selectedCandidate.blocks
    val confidence: Float? get() = selectedCandidate.confidence
    val quality: OcrQualityReport? get() = selectedCandidate.quality
}

/**
 * Отчёт о качестве распознавания.
 */
data class OcrQualityReport(
    val score: Float,                    // Общая оценка 0..1
    val confidence: Float?,              // Confidence от движка
    val charCount: Int,
    val wordCount: Int,
    val lineCount: Int,
    val cyrillicRatio: Float,
    val latinRatio: Float,
    val digitRatio: Float,
    val garbageRatio: Float,
    val mixedScriptWordCount: Int,
    val suspiciousReasons: List<String> = emptyList()
) {
    val isGood: Boolean get() = score >= 0.7f && suspiciousReasons.isEmpty()
    val isSuspicious: Boolean get() = score < 0.5f || suspiciousReasons.isNotEmpty()
}

/**
 * Изменение текста при постобработке.
 */
data class OcrTextChange(
    val position: Int,
    val original: String,
    val replacement: String,
    val reason: String
)

/**
 * Результат постобработки.
 */
data class OcrPostProcessResult(
    val text: String,
    val changes: List<OcrTextChange>,
    val warnings: List<String> = emptyList()
)

/**
 * Подготовленное изображение после preprocessing.
 */
data class PreparedOcrRequest(
    val bitmap: Bitmap,
    val originalRequest: OcrRequest,
    val preprocessingApplied: List<String>
)
