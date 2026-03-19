// ============================================================
// domain/models/RecognizedText.kt
// UI-ориентированная модель результата — очищена
// ============================================================
package com.arny.mlscanner.domain.models

/**
 * Результат распознавания для UI слоя.
 *
 * Содержит отформатированный текст и метаданные.
 * Создаётся из OcrResult через OcrResultMapper.
 *
 * @property originalText   Сырой текст (как вернул OCR-движок)
 * @property formattedText  Текст после постобработки
 * @property blocks         Блоки для визуализации
 * @property confidence     Средняя уверенность 0.0–1.0
 * @property detectedLanguage Определённый язык (для отображения)
 */
data class RecognizedText(
    val originalText: String,
    val formattedText: String,
    val blocks: List<TextBlockInfo>,
    val confidence: Float,
    val detectedLanguage: String
) {
    /** Пустой результат */
    val isEmpty: Boolean get() = formattedText.isBlank()

    /** Количество слов */
    val wordCount: Int
        get() = formattedText.split("\\s+".toRegex())
            .count { it.isNotBlank() }

    companion object {
        val EMPTY = RecognizedText(
            originalText = "",
            formattedText = "",
            blocks = emptyList(),
            confidence = 0f,
            detectedLanguage = "unknown"
        )
    }
}

/**
 * Информация о блоке текста для UI.
 * Использует android.graphics.Rect для совместимости с Canvas.
 */
data class TextBlockInfo(
    val text: String,
    val boundingBox: android.graphics.Rect?,
    val lines: List<LineInfo>
)

/**
 * Информация о строке текста.
 */
data class LineInfo(
    val text: String,
    val boundingBox: android.graphics.Rect?,
    val indentLevel: Int = 0,
    val confidence: Float = 0f
)