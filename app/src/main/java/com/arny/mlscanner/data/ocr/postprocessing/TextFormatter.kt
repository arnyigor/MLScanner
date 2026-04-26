package com.arny.mlscanner.data.ocr.postprocessing

/**
 * Форматтер текста для разных режимов отображения
 */
object TextFormatter {
    
    /**
     * Режимы форматирования
     */
    enum class FormatMode {
        RAW,        // Сырой текст как есть
        MARKDOWN    // Форматированный текст с распознанными паттернами
    }
    
    /**
     * Форматированный результат
     */
    data class FormattedResult(
        val text: String,
        val patterns: List<PatternRecognizer.RecognizedPattern>,
        val mode: FormatMode
    )
    
    /**
     * Форматирует текст в зависимости от режима
     * ВАЖНО: всегда работает с актуальным текстом (например, из EditText)
     */
    fun format(text: String, mode: FormatMode): FormattedResult {
        return when (mode) {
            FormatMode.RAW -> formatRaw(text)
            FormatMode.MARKDOWN -> formatMarkdown(text)
        }
    }
    
    /**
     * Raw режим - текст как есть, но с распознанными паттернами
     */
    private fun formatRaw(text: String): FormattedResult {
        // В RAW режиме тоже распознаём паттерны для кликабельности
        val normalizedText = normalizeInteractiveText(text)
        val patterns = PatternRecognizer.recognizeAll(normalizedText)
        
        return FormattedResult(
            text = text,
            patterns = patterns,
            mode = FormatMode.RAW
        )
    }
    
    /**
     * Markdown режим - форматированный текст с распознанными паттернами
     */
    private fun formatMarkdown(text: String): FormattedResult {
        val normalizedText = normalizeInteractiveText(text)
        val patterns = PatternRecognizer.recognizeAll(normalizedText)
        val cleanedText = cleanText(normalizedText)
        
        if (patterns.isEmpty()) {
            return FormattedResult(
                text = cleanedText,
                patterns = emptyList(),
                mode = FormatMode.MARKDOWN
            )
        }
        
        val formatted = buildString {
            append("### Detected\n")
            patterns.forEach { pattern ->
                append("- ")
                append(formatPattern(pattern))
                append('\n')
            }
            append("\n### Text\n")
            append(cleanedText)
        }
        
        return FormattedResult(
            text = formatted.trim(),
            patterns = patterns,
            mode = FormatMode.MARKDOWN
        )
    }
    
    /**
     * Форматирует распознанный паттерн
     */
    private fun formatPattern(pattern: PatternRecognizer.RecognizedPattern): String {
        return when (pattern.type) {
            PatternRecognizer.PatternType.PHONE -> {
                "📞 ${pattern.formatted}"
            }
            PatternRecognizer.PatternType.EMAIL -> {
                "📧 ${pattern.value}"
            }
            PatternRecognizer.PatternType.URL -> {
                "🔗 ${pattern.value}"
            }
            PatternRecognizer.PatternType.DATE -> {
                "📅 ${pattern.formatted}"
            }
            PatternRecognizer.PatternType.TIME -> {
                "🕐 ${pattern.value}"
            }
            PatternRecognizer.PatternType.MONEY -> {
                "💰 ${pattern.formatted}"
            }
            PatternRecognizer.PatternType.INN -> {
                "🏢 ИНН: ${pattern.value}"
            }
            PatternRecognizer.PatternType.CARD -> {
                "💳 ${pattern.formatted}"
            }
        }
    }
    
    /**
     * Очищает текст от лишних символов и пробелов
     */
    private fun cleanText(text: String): String {
        var result = text
        
        // Удаляем множественные пробелы
        result = result.replace(Regex(" {2,}"), " ")
        
        // Удаляем множественные переносы строк
        result = result.replace(Regex("\n{3,}"), "\n\n")
        
        // Удаляем пробелы в начале и конце строк
        result = result.lines().joinToString("\n") { it.trim() }
        
        // Удаляем мусорные символы
        result = result.replace(Regex("[|\\[\\]{}~`^\\\\]{2,}"), "")
        
        return result
    }

    private fun normalizeInteractiveText(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("""(?i)\b(https?)[ \t]*:[ \t]*/[ \t]*/[ \t]*"""), "$1://")
            .replace(Regex("""(?i)\bwww[ \t]*\.[ \t]*"""), "www.")
            .replace(Regex("""(?<=\w)[ \t]*@[ \t]*(?=\w)"""), "@")
            .replace(Regex("""(?<=\w)[ \t]*\.[ \t]*(?=\w)"""), ".")
            .replace(Regex("""(?<=\w)[ \t]*/[ \t]*(?=\w)"""), "/")
    }
    
    /**
     * Создаёт кликабельные элементы для UI
     */
    data class ClickableElement(
        val type: PatternRecognizer.PatternType,
        val value: String,
        val displayText: String,
        val action: ClickAction
    )
    
    /**
     * Действия при клике на элемент
     */
    sealed class ClickAction {
        data class Call(val phoneNumber: String) : ClickAction()
        data class SendEmail(val email: String) : ClickAction()
        data class OpenUrl(val url: String) : ClickAction()
        data class CopyToClipboard(val text: String) : ClickAction()
    }
    
    /**
     * Создаёт кликабельные элементы из распознанных паттернов
     */
    fun createClickableElements(patterns: List<PatternRecognizer.RecognizedPattern>): List<ClickableElement> {
        return patterns.mapNotNull { pattern ->
            when (pattern.type) {
                PatternRecognizer.PatternType.PHONE -> {
                    val digits = pattern.value.replace(Regex("[^0-9]"), "")
                    ClickableElement(
                        type = pattern.type,
                        value = digits,
                        displayText = pattern.formatted,
                        action = ClickAction.Call(digits)
                    )
                }
                PatternRecognizer.PatternType.EMAIL -> {
                    ClickableElement(
                        type = pattern.type,
                        value = pattern.value,
                        displayText = pattern.value,
                        action = ClickAction.SendEmail(pattern.value)
                    )
                }
                PatternRecognizer.PatternType.URL -> {
                    val url = if (!pattern.value.startsWith("http", ignoreCase = true)) {
                        "https://${pattern.value}"
                    } else {
                        pattern.value
                    }
                    ClickableElement(
                        type = pattern.type,
                        value = url,
                        displayText = pattern.value,
                        action = ClickAction.OpenUrl(url)
                    )
                }
                PatternRecognizer.PatternType.INN,
                PatternRecognizer.PatternType.CARD,
                PatternRecognizer.PatternType.DATE,
                PatternRecognizer.PatternType.TIME,
                PatternRecognizer.PatternType.MONEY -> {
                    ClickableElement(
                        type = pattern.type,
                        value = pattern.value,
                        displayText = pattern.formatted,
                        action = ClickAction.CopyToClipboard(pattern.formatted)
                    )
                }
            }
        }
    }
}
