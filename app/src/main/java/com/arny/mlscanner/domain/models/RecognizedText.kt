// ============================================================
// domain/models/RecognizedText.kt
// UI-ориентированная модель результата — очищена
// ============================================================
package com.arny.mlscanner.domain.models

import com.arny.mlscanner.data.ocr.postprocessing.PatternRecognizer
import com.arny.mlscanner.data.ocr.postprocessing.TextFormatter

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
 * @property recognizedPatterns Распознанные паттерны (телефоны, ссылки и т.д.)
 * @property formatMode     Режим форматирования (RAW/MARKDOWN)
 */
data class RecognizedText(
    val originalText: String,
    val formattedText: String,
    val blocks: List<TextBlockInfo>,
    val confidence: Float,
    val detectedLanguage: String,
    val recognizedPatterns: List<PatternRecognizer.RecognizedPattern> = emptyList(),
    val formatMode: TextFormatter.FormatMode = TextFormatter.FormatMode.RAW
) {
    /** Пустой результат */
    val isEmpty: Boolean get() = formattedText.isBlank()

    /** Количество слов */
    val wordCount: Int
        get() = formattedText.split("\\s+".toRegex())
            .count { it.isNotBlank() }
    
    /** Получить кликабельные элементы */
    val clickableElements: List<TextFormatter.ClickableElement>
        get() = TextFormatter.createClickableElements(recognizedPatterns)
    
    /** Переключить режим форматирования */
    fun toggleFormatMode(): RecognizedText {
        val newMode = when (formatMode) {
            TextFormatter.FormatMode.RAW -> TextFormatter.FormatMode.MARKDOWN
            TextFormatter.FormatMode.MARKDOWN -> TextFormatter.FormatMode.RAW
        }
        
        val formatted = TextFormatter.format(originalText, newMode)
        
        return copy(
            formattedText = formatted.text,
            recognizedPatterns = formatted.patterns,
            formatMode = newMode
        )
    }
    
    /** Обновить сырой текст без тяжёлого пересчёта на каждый символ. */
    fun updateRawText(newText: String): RecognizedText {
        val formatted = TextFormatter.format(newText, TextFormatter.FormatMode.RAW)

        return copy(
            originalText = newText,
            formattedText = newText,
            recognizedPatterns = formatted.patterns,
            formatMode = TextFormatter.FormatMode.RAW
        )
    }

/** Применить сырой текст и пересчитать форматированный вывод/паттерны. */
    fun applyRawText(newText: String): RecognizedText {
        // Всегда используем RAW режим для распознавания паттернов при редактировании
        val formatted = TextFormatter.format(newText, TextFormatter.FormatMode.RAW)

        return copy(
            originalText = newText,
            formattedText = newText, // Сохраняем как есть в RAW режиме
            recognizedPatterns = formatted.patterns,
            formatMode = TextFormatter.FormatMode.RAW // Явно устанавливаем RAW режим
        )
    }

    companion object {
        val EMPTY = RecognizedText(
            originalText = "",
            formattedText = "",
            blocks = emptyList(),
            confidence = 0f,
            detectedLanguage = "unknown",
            recognizedPatterns = emptyList(),
            formatMode = TextFormatter.FormatMode.RAW
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
