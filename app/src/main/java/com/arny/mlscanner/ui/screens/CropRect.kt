package com.arny.mlscanner.ui.screens

import androidx.compose.ui.geometry.Rect

// Represents the current crop rectangle in *image* coordinates.
data class CropRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    fun toRect() = Rect(left, top, right, bottom)
}