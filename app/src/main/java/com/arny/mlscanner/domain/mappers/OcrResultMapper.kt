// ============================================================
// domain/mappers/OcrResultMapper.kt
// Маппинг между внутренними моделями
// ============================================================
package com.arny.mlscanner.domain.mappers

import com.arny.mlscanner.domain.formatters.TextFormatter
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.LanguageDetector
import com.arny.mlscanner.domain.models.OcrLanguage
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
    private val textFormatter: TextFormatter = TextFormatter()
) {

    /**
     * OcrResult → RecognizedText (для UI слоя).
     *
     * Применяет форматирование текста и определяет язык.
     */
    fun toRecognizedText(ocrResult: OcrResult): RecognizedText {
        val formattedText = textFormatter.format(ocrResult.fullText)

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
            formattedText = formattedText,
            blocks = blockInfos,
            confidence = ocrResult.averageConfidence,
            detectedLanguage = ocrResult.detectedLanguage
        )
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