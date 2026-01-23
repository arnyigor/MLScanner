package com.arny.mlscanner.domain.models

/**
 * Результат OCR-распознавания
 */
data class OcrResult(
    val textBoxes: List<TextBox>,
    val timestamp: Long,
    val processingTimeMs: Long = 0
)

/**
 * Текстовый блок с информацией о координатах и уверенности
 */
data class TextBox(
    val text: String,
    val confidence: Float,
    val boundingBox: BoundingBox
)

/**
 * Ограничивающая рамка (bounding box) текстового блока
 */
data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun toRect() = android.graphics.Rect(
        left.toInt(),
        top.toInt(),
        right.toInt(),
        bottom.toInt()
    )

    /**
     * Проверка пересечения с другой bounding box
     */
    fun intersects(other: BoundingBox): Boolean {
        return !(right < other.left || left > other.right ||
                bottom < other.top || top > other.bottom)
    }
}

/**
 * Результат обработки документа
 */
data class DocumentProcessingResult(
    val ocrResult: OcrResult,
    val redactionResult: RedactionResult? = null,
    val matchingResult: MatchingResult? = null
)

/**
 * Результат маскирования (редактирования)
 */
data class RedactionResult(
    val originalImagePath: String,
    val redactedImagePath: String,
    val redactedAreas: List<BoundingBox>,
    val timestamp: Long
)

/**
 * Результат сопоставления с базой данных
 */
data class MatchingResult(
    val matchedItems: List<MatchedItem>,
    val unmatchedTexts: List<String>,
    val confidenceThreshold: Float,
    val timestamp: Long
)

/**
 * Сопоставленный элемент
 */
data class MatchedItem(
    val originalText: String,
    val matchedItem: ProductItem?, // ProductItem будет определен в модуле сопоставления
    val confidence: Float,
    val boundingBox: BoundingBox
)

/**
 * Элемент продукта (для модуля сопоставления)
 */
data class ProductItem(
    val id: String = "",
    val sku: String,
    val name: String,
    val price: Double? = null,
    val category: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)