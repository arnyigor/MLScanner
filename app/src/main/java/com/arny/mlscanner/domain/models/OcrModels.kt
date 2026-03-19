// ============================================================
// domain/models/OcrModels.kt
// Единые модели для OCR — обратная совместимость + новые форматы
// ============================================================
package com.arny.mlscanner.domain.models

/**
 * Текстовый блок (старый формат) — для обратной совместимости.
 */
data class TextBox(
    val text: String,
    val confidence: Float,
    val boundingBox: BoundingBox
)

/**
 * Модели для MatchingEngine.
 */
data class MatchedItem(
    val originalText: String,
    val matchedItem: ProductItem?,
    val confidence: Float,
    val boundingBox: BoundingBox
)

data class MatchingResult(
    val matchedItems: List<MatchedItem>,
    val unmatchedTexts: List<String>,
    val confidenceThreshold: Float,
    val timestamp: Long
)
