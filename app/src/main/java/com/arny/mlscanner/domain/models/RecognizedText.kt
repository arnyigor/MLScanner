package com.arny.mlscanner.domain.models

import android.graphics.Rect
import com.google.mlkit.vision.text.Text

data class RecognizedText(
    val originalText: String,
    val formattedText: String,
    val blocks: List<TextBlockInfo>,
    val confidence: Float,
    val detectedLanguage: String
)

data class TextBlockInfo(
    val text: String,
    val boundingBox: Rect?,
    val lines: List<LineInfo>
)

data class LineInfo(
    val text: String,
    val boundingBox: Rect?,
    val indentLevel: Int,
    val confidence: Float
)