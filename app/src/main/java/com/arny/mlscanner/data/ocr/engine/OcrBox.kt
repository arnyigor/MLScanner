package com.arny.mlscanner.data.ocr.engine

data class OcrBox(
    val text: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)
