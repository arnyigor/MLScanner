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
