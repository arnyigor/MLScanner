package com.arny.mlscanner.domain.models

/**
 * Результат редактирования PDF.
 */
data class RedactionResult(
    val originalImagePath: String,
    val redactedImagePath: String,
    val redactedAreas: List<BoundingBox>,
    val timestamp: Long
)