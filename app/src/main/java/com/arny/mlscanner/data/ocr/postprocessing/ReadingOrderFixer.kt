package com.arny.mlscanner.data.ocr.postprocessing

import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.TextBlock
import com.arny.mlscanner.domain.models.TextLine
import com.arny.mlscanner.domain.models.TextWord
import kotlin.math.abs

/**
 * Исправляет reading order при сборке текста из bounding boxes.
 * 
 * Проблема: простая сортировка по (top, left) ломается на:
 * - наклонённых строках
 * - разной высоте букв
 * - рукописи
 * - документах с колонками
 * 
 * Решение: группируем слова в строки по vertical overlap,
 * затем сортируем строки по centerY.
 */
object ReadingOrderFixer {

    /**
     * Строит текст из слов с правильным reading order.
     * 
     * @param words Список слов с bounding boxes
     * @param imageWidth Ширина изображения (для детекции колонок)
     * @param imageHeight Высота изображения
     * @return Текст с правильным порядком строк
     */
    fun buildText(
        words: List<OcrWordBox>,
        imageWidth: Int,
        imageHeight: Int
    ): String {
        if (words.isEmpty()) return ""
        
        val filtered = words.filter { it.text.isNotBlank() }
        if (filtered.isEmpty()) return ""
        
        val lines = groupWordsIntoLines(filtered)
        val blocks = groupLinesIntoBlocks(lines, imageWidth)
        
        return blocks.joinToString("\n\n") { block ->
            block.lines.joinToString("\n") { line ->
                line.words
                    .sortedBy { word -> word.boundingBox.left }
                    .joinToString(" ") { word -> word.text }
            }
        }
    }

    /**
     * Строит TextBlock из слов с правильным reading order.
     */
    fun buildBlocks(
        words: List<OcrWordBox>,
        imageWidth: Int,
        imageHeight: Int
    ): List<TextBlock> {
        if (words.isEmpty()) return emptyList()
        
        val filtered = words.filter { it.text.isNotBlank() }
        if (filtered.isEmpty()) return emptyList()
        
        val lines = groupWordsIntoLines(filtered)
        return groupLinesIntoBlocks(lines, imageWidth)
    }

    /**
     * Группирует слова в строки по vertical overlap.
     * 
     * Два слова в одной строке, если:
     * - verticalOverlap >= 0.40
     * - или abs(centerY1 - centerY2) < 0.55 * lineHeight
     */
    private fun groupWordsIntoLines(words: List<OcrWordBox>): List<OcrLineBox> {
        val sorted = words.sortedWith(
            compareBy<OcrWordBox> { it.centerY }.thenBy { it.box.left }
        )

        val lines = mutableListOf<MutableList<OcrWordBox>>()

        for (word in sorted) {
            val targetLine = lines.firstOrNull { line ->
                val lineTop = line.minOf { it.box.top }
                val lineBottom = line.maxOf { it.box.bottom }
                val overlap = verticalOverlap(
                    word.box.top,
                    word.box.bottom,
                    lineTop,
                    lineBottom
                )

                val lineHeight = (lineBottom - lineTop).coerceAtLeast(1f)
                val centerY = (lineTop + lineBottom) / 2f

                overlap >= 0.40f ||
                    abs(word.centerY - centerY) < lineHeight * 0.55f
            }

            if (targetLine != null) {
                targetLine += word
            } else {
                lines += mutableListOf(word)
            }
        }

        return lines
            .map { OcrLineBox(it.sortedBy { word -> word.box.left }) }
            .sortedBy { it.centerY }
    }

    /**
     * Вычисляет vertical overlap между двумя прямоугольниками.
     * 
     * @return Значение от 0.0 до 1.0
     */
    private fun verticalOverlap(
        top1: Float,
        bottom1: Float,
        top2: Float,
        bottom2: Float
    ): Float {
        val overlap = minOf(bottom1, bottom2) - maxOf(top1, top2)
        if (overlap <= 0) return 0f

        val h1 = bottom1 - top1
        val h2 = bottom2 - top2
        return overlap / minOf(h1, h2).coerceAtLeast(1f)
    }

    /**
     * Группирует строки в блоки.
     * 
     * Для простоты пока создаём один блок.
     * В будущем можно добавить детекцию колонок и параграфов.
     */
    private fun groupLinesIntoBlocks(
        lines: List<OcrLineBox>,
        imageWidth: Int
    ): List<TextBlock> {
        if (lines.isEmpty()) return emptyList()

        // Конвертируем в TextLine
        val textLines = lines.map { lineBox ->
            val lineText = lineBox.words.joinToString(" ") { it.text }
            val lineConf = lineBox.words.map { it.confidence }.average().toFloat()
            val textWords = lineBox.words.map { w ->
                TextWord(w.text, w.box, w.confidence)
            }
            TextLine(lineText, lineBox.boundingBox, textWords, lineConf)
        }

        // Создаём один блок
        val blockText = textLines.joinToString("\n") { it.text }
        val blockBox = textLines.map { it.boundingBox }.reduce { acc, b -> acc.union(b) }
        val blockConf = textLines.map { it.confidence }.average().toFloat()

        return listOf(TextBlock(blockText, blockBox, textLines, blockConf))
    }
}

/**
 * Слово с bounding box для ReadingOrderFixer.
 */
data class OcrWordBox(
    val text: String,
    val box: BoundingBox,
    val confidence: Float
) {
    val centerX: Float get() = (box.left + box.right) / 2f
    val centerY: Float get() = (box.top + box.bottom) / 2f
    val height: Float get() = (box.bottom - box.top)
    val width: Float get() = (box.right - box.left)
}

/**
 * Строка с bounding box для ReadingOrderFixer.
 */
data class OcrLineBox(
    val words: List<OcrWordBox>
) {
    val top: Float get() = words.minOf { it.box.top }
    val bottom: Float get() = words.maxOf { it.box.bottom }
    val left: Float get() = words.minOf { it.box.left }
    val right: Float get() = words.maxOf { it.box.right }
    val centerY: Float get() = (top + bottom) / 2f
    val medianHeight: Float get() = words.map { it.height }.sorted().let { 
        if (it.isEmpty()) 0f else it[it.size / 2] 
    }
    val boundingBox: BoundingBox get() = BoundingBox(
        left,
        top,
        right,
        bottom
    )
}
