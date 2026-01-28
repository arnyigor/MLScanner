package com.arny.mlscanner.ui.screens

/**
 * Простая модель прямоугольника для обрезки в пикселях.
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)