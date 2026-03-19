// ============================================================
// domain/models/OcrResult.kt
// Единая модель результата OCR — заменяет OcrResult + RecognizedText
// ============================================================
package com.arny.mlscanner.domain.models

/**
 * Результат распознавания текста.
 *
 * Единая модель для всех движков (ML Kit, Tesseract, Hybrid).
 * Содержит как сырые данные (блоки с координатами), так и
 * отформатированный текст для UI.
 *
 * @property blocks         Текстовые блоки с координатами и уверенностью
 * @property fullText        Полный текст (строки через \n, абзацы через \n\n)
 * @property formattedText   Текст после постобработки (URL-фикс, форматирование)
 * @property detectedLanguage Определённый язык текста (RUS/ENG/RUS+ENG/UNKNOWN)
 * @property averageConfidence Средняя уверенность (0.0–1.0)
 * @property processingTimeMs Время обработки в миллисекундах
 * @property engineName      Какой движок использовался
 * @property imageWidth      Ширина обработанного изображения
 * @property imageHeight     Высота обработанного изображения
 */
 data class OcrResult(
     val blocks: List<TextBlock>,
     val fullText: String,
     val formattedText: String = fullText,
     // detectedLanguage вычисляется автоматически
     val averageConfidence: Float = 0f,
    val processingTimeMs: Long = 0L,
    val engineName: String = "unknown",
    val imageWidth: Int = 0,
    val imageHeight: Int = 0
) {
    /** Результат пуст? */
    val isEmpty: Boolean
        get() = blocks.isEmpty() || fullText.isBlank()

    /** Количество слов */
    val wordCount: Int
        get() = fullText.split("\\s+".toRegex())
            .count { it.isNotBlank() }

    /** Количество блоков */
    val blockCount: Int get() = blocks.size

    /** Количество строк */
    val lineCount: Int
        get() = blocks.sumOf { it.lines.size }

     /** Краткая сводка для логов */
    fun summary(): String = buildString {
        append("OCR[$engineName]: ")
        append("${wordCount} words, ")
        append("${blockCount} blocks, ")
        append("${(averageConfidence * 100).toInt()}% conf, ")
        append("${processingTimeMs}ms, ")
        append("lang=${detectedLanguage}")
    }

    /** Определяет язык текста автоматически */
    val detectedLanguage: String
        get() = OcrLanguage.detectFromText(fullText).tesseractCode

    companion object {
        /** Пустой результат */
        val EMPTY = OcrResult(
            blocks = emptyList(),
            fullText = "",
            formattedText = ""
        )
    }
}

/**
 * Блок текста (абзац или логически связанная область).
 */
data class TextBlock(
    val text: String,
    val boundingBox: BoundingBox,
    val lines: List<TextLine>,
    val confidence: Float
)

/**
 * Строка текста внутри блока.
 */
data class TextLine(
    val text: String,
    val boundingBox: BoundingBox,
    val words: List<TextWord>,
    val confidence: Float
)

/**
 * Отдельное слово.
 */
data class TextWord(
    val text: String,
    val boundingBox: BoundingBox,
    val confidence: Float
)