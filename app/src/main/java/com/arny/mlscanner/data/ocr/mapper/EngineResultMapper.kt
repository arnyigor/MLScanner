package com.arny.mlscanner.data.ocr.mapper

import com.arny.mlscanner.domain.models.TextBlock

/**
 * Общие утилиты для маппинга результатов OCR-движков.
 * Используется из MLKitEngine и TesseractEngine.
 */
object EngineResultMapper {

    /**
     * Сборка fullText из блоков с учётом структуры.
     *
     * Блоки разделяются \n\n, строки внутри блока — \n.
     */
    fun buildFullTextFromBlocks(blocks: List<TextBlock>): String {
        if (blocks.isEmpty()) return ""

        return blocks.joinToString("\n\n") { block ->
            block.lines.joinToString("\n") { line ->
                line.text
            }
        }
    }

    /**
     * Расчёт средней уверенности по всем словам.
     * Если слов нет — берется среднее по блокам.
     */
    fun calculateAverageConfidence(blocks: List<TextBlock>): Float {
        val allConfidences = blocks.flatMap { block ->
            block.lines.flatMap { line ->
                line.words.map { it.confidence }
            }.ifEmpty { listOf(block.confidence) }
        }

        return if (allConfidences.isNotEmpty()) {
            allConfidences.average().toFloat()
        } else 0f
    }
}