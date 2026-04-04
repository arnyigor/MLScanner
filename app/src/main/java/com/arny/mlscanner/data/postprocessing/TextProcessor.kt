package com.arny.mlscanner.data.postprocessing

interface TextProcessor {
    val name: String
    fun isApplicable(context: DocumentContext): Boolean = true
    fun process(text: String, context: DocumentContext): ProcessorResult
}

data class ProcessorResult(
    val text: String,
    val changes: List<TextChange> = emptyList()
)

data class TextChange(
    val processorName: String,
    val original: String,
    val replacement: String,
    val position: Int,
    val confidence: Float = 1.0f,
    val reason: String = ""
)

data class DocumentContext(
    val documentType: DocumentType = DocumentType.GENERAL,
    val primaryLanguage: String = "ru",
    val detectedLanguages: List<String> = listOf("ru"),
    val ocrConfidence: Float = 0.0f,
    val imageSource: ImageSource = ImageSource.CAMERA,
    val metadata: Map<String, Any> = emptyMap()
)

enum class DocumentType {
    GENERAL,
    RECEIPT,
    INVOICE,
    PASSPORT,
    CONTRACT,
    TABLE,
    HANDWRITTEN,
    SIGN,
    SCREENSHOT,
    BUSINESS_CARD
}

enum class ImageSource {
    CAMERA,
    GALLERY,
    PDF,
    SCREENSHOT
}

data class PostProcessingConfig(
    val spellCheckEnabled: Boolean = true,
    val numberFormattingEnabled: Boolean = true,
    val autoCorrectMinConfidence: Float = 0.85f,
    val maxEditDistance: Int = 2,
    val structureRestorationEnabled: Boolean = true,
    val spellCheckLanguage: String = "ru"
)

data class PostProcessingResult(
    val originalText: String,
    val processedText: String,
    val changes: List<TextChange>,
    val statistics: PostProcessingStatistics
) {
    val hasChanges: Boolean get() = changes.isNotEmpty()
    val changeCount: Int get() = changes.size
}

data class PostProcessingStatistics(
    val totalChanges: Int,
    val changesByProcessor: Map<String, Int>,
    val originalLength: Int,
    val processedLength: Int,
    val processingTimeMs: Long = 0
)

internal fun calculateStatistics(original: String, processed: String, changes: List<TextChange>): PostProcessingStatistics {
    return PostProcessingStatistics(
        totalChanges = changes.size,
        changesByProcessor = changes.groupBy { it.processorName }.mapValues { it.value.size },
        originalLength = original.length,
        processedLength = processed.length
    )
}