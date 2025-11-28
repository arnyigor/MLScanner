package com.arny.mlscanner.data.ocr

import android.graphics.Rect
import com.arny.mlscanner.domain.models.LineInfo
import com.arny.mlscanner.domain.models.RecognizedText
import com.arny.mlscanner.domain.models.TextBlockInfo
import com.google.mlkit.vision.text.Text

// data/ocr/TextFormatPreserver.kt
class TextFormatPreserver {

    /**
     * Восстанавливает форматирование текста на основе координат bounding boxes.
     * Критично для распознавания кода с отступами.
     */
    fun preserveFormatting(mlKitResult: Text): RecognizedText {
        val blocks = mlKitResult.textBlocks
        if (blocks.isEmpty()) {
            return RecognizedText(
                originalText = "",
                formattedText = "",
                blocks = emptyList(),
                confidence = 0f,
                detectedLanguage = "unknown"
            )
        }

        val processedBlocks = mutableListOf<TextBlockInfo>()
        val formattedStringBuilder = StringBuilder()
        var totalConfidence = 0f
        var elementCount = 0

        // Сортируем блоки по вертикали (top -> bottom)
        val sortedBlocks = blocks.sortedBy { it.boundingBox?.top ?: 0 }

        for (block in sortedBlocks) {
            val blockLines = mutableListOf<LineInfo>()

            // Сортируем строки внутри блока по вертикали
            val sortedLines = block.lines.sortedBy { it.boundingBox?.top ?: 0 }

            // Вычисляем минимальный X координат для определения базового отступа
            val minX = sortedLines.mapNotNull { it.boundingBox?.left }.minOrNull() ?: 0

            for (line in sortedLines) {
                val lineBox = line.boundingBox ?: continue
                val lineX = lineBox.left

                // ⭐ Вычисляем отступ относительно минимального X
                // Используем шаг ~40px (примерно размер одного символа)
                val indentLevel = ((lineX - minX) / 40).coerceAtLeast(0)

                // Добавляем пробелы для отступа (4 пробела на уровень для кода)
                val indent = "    ".repeat(indentLevel)
                formattedStringBuilder.append(indent).append(line.text).append("\n")

                blockLines.add(
                    LineInfo(
                        text = line.text,
                        boundingBox = lineBox,
                        indentLevel = indentLevel,
                        confidence = line.confidence ?: 0.9f
                    )
                )

                // Собираем статистику confidence
                line.elements.forEach { element ->
                    totalConfidence += element.confidence ?: 0.9f
                    elementCount++
                }
            }

            formattedStringBuilder.append("\n") // Разделитель между блоками

            processedBlocks.add(
                TextBlockInfo(
                    text = block.text,
                    boundingBox = block.boundingBox ?: Rect(),
                    lines = blockLines
                )
            )
        }

        return RecognizedText(
            originalText = mlKitResult.text,
            formattedText = formattedStringBuilder.toString().trimEnd(),
            blocks = processedBlocks,
            confidence = if (elementCount > 0) totalConfidence / elementCount else 0f,
            detectedLanguage = detectPrimaryLanguage(blocks)
        )
    }

    private fun detectPrimaryLanguage(blocks: List<Text.TextBlock>): String {
        // Подсчитываем частоту языков
        val languageFrequency = mutableMapOf<String, Int>()
        blocks.forEach { block ->
            val lang = block.recognizedLanguage
            languageFrequency[lang] = (languageFrequency[lang] ?: 0) + 1
        }
        return languageFrequency.maxByOrNull { it.value }?.key ?: "und"
    }
}
