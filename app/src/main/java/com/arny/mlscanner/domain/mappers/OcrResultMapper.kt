// ============================================================
// domain/mappers/OcrResultMapper.kt
// Маппинг между внутренними моделями
// ============================================================
package com.arny.mlscanner.domain.mappers

import android.util.Log
import com.arny.mlscanner.data.ocr.postprocessing.PatternRecognizer
import com.arny.mlscanner.data.ocr.postprocessing.TextFormatter as DisplayTextFormatter
import com.arny.mlscanner.domain.formatters.TextFormatter as BlockTextFormatter
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.RecognizedText
import com.arny.mlscanner.domain.models.TextBlock
import com.arny.mlscanner.domain.models.TextBlockInfo
import com.arny.mlscanner.domain.models.LineInfo

/**
 * Маппинг между моделями данных OCR.
 *
 * Единственное место преобразования:
 * - OcrResult (domain) → RecognizedText (UI)
 * - Raw engine output → OcrResult (domain)
 */
class OcrResultMapper(
    private val textFormatter: BlockTextFormatter = BlockTextFormatter()
) {

    companion object {
        private const val TAG = "OcrResultMapper"
    }

    /**
     * OcrResult → RecognizedText (для UI слоя).
     *
     * Применяет форматирование текста и определяет язык.
     */
    fun toRecognizedText(ocrResult: OcrResult): RecognizedText {
        Log.d(TAG, "=== OcrResultMapper INPUT ===")
        Log.d(TAG, "fullText: ${ocrResult.fullText.take(300)}")
        Log.d(TAG, "engine: ${ocrResult.engineName}")

        // Форматируем текст и распознаём паттерны
        val formatted = DisplayTextFormatter.format(
            ocrResult.fullText,
            DisplayTextFormatter.FormatMode.RAW
        )

        Log.d(TAG, "Detected patterns: ${formatted.patterns.size}")
        formatted.patterns.forEach { pattern ->
            Log.d(TAG, "  Pattern: ${pattern.type} = '${pattern.value}'")
        }

        val blockInfos = ocrResult.blocks.map { block ->
            TextBlockInfo(
                text = block.text,
                boundingBox = block.boundingBox.toAndroidRect(),
                lines = block.lines.map { line ->
                    LineInfo(
                        text = line.text,
                        boundingBox = line.boundingBox.toAndroidRect(),
                        indentLevel = 0,
                        confidence = line.confidence
                    )
                }
            )
        }

return RecognizedText(
            originalText = ocrResult.fullText,
            formattedText = formatted.text,
            blocks = blockInfos,
            confidence = ocrResult.averageConfidence,
            detectedLanguage = ocrResult.detectedLanguage,
            recognizedPatterns = formatted.patterns,  // Используем паттерны из formatted
            formatMode = formatted.mode
        ).also {
            Log.d(TAG, "=== OcrResultMapper OUTPUT ===")
            Log.d(TAG, "recognizedPatterns: ${it.recognizedPatterns.size}")
        }
    }

    /**
     * Сборка fullText из блоков с учётом пространственного расположения.
     */
    fun buildFullText(blocks: List<TextBlock>): String {
        if (blocks.isEmpty()) return ""

        val pairs = blocks.map { it.text to it.boundingBox }
        return textFormatter.joinBlocks(pairs)
    }

    /**
     * Вычисление средней уверенности.
     */
    fun calculateAverageConfidence(blocks: List<TextBlock>): Float {
        if (blocks.isEmpty()) return 0f

        val allConfidences = blocks.flatMap { block ->
            block.lines.flatMap { line ->
                line.words.map { it.confidence }
            }.ifEmpty {
                listOf(block.confidence)
            }
        }

        return if (allConfidences.isNotEmpty()) {
            allConfidences.average().toFloat()
        } else 0f
    }
}

/**
 * Extension для конвертации BoundingBox → android.graphics.Rect.
 * Расположен здесь, т.к. используется только в маппере.
 */
private fun BoundingBox.toAndroidRect(): android.graphics.Rect {
    return android.graphics.Rect(
        left.toInt(),
        top.toInt(),
        right.toInt(),
        bottom.toInt()
    )
}
