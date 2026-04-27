package com.arny.mlscanner.data.ocr.postprocessing

/**
 * Форматтер текста для разных режимов отображения
 */
object TextFormatter {
    private const val DEBUG = false

    private fun log(tag: String, msg: String) {
        if (DEBUG) {
            android.util.Log.d("TextFormatter", "[$tag] $msg")
        }
    }

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
        log("formatRaw", "Input text: ${text.replace("\n", "\\n")}")
        // В RAW режиме тоже распознаём паттерны для кликабельности
        val normalizedText = normalizeInteractiveText(text)
        log("formatRaw", "Normalized text: ${normalizedText.replace("\n", "\\n")}")
        val patterns = PatternRecognizer.recognizeAll(normalizedText)
        log("formatRaw", "Found ${patterns.size} patterns: ${patterns.map { it.value }}")

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
        log("normalizeInteractiveText", "Input: ${text.replace("\n", "\\n")}")

        var normalized = text
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace(Regex("""(?i)\b(https?)[ \t]*:[ \t]*/[ \t]*/[ \t]*"""), "$1://")
            .replace(Regex("""(?i)\bwww[ \t]*\.[ \t]*"""), "www.")
            .replace(Regex("""(?<=\w)[ \t]*@[ \t]*(?=\w)"""), "@")
            .replace(Regex("""(?<=\w)[ \t]*\.[ \t]*(?=\w)"""), ".")
            .replace(Regex("""(?<=\w)[ \t]*/[ \t]*(?=\w)"""), "/")

        log("normalizeInteractiveText", "After basic normalization: ${normalized.replace("\n", "\\n")}")

        // Склеиваем URL разбитые переносами строк (до разбиения на строки!)
        // Обрабатывает: "https://yandex.ru/video/preview/160421091605\n16050498" -> "https://yandex.ru/video/preview/16042109160516050498"
        normalized = mergeBrokenUrls(normalized)
        log("normalizeInteractiveText", "After mergeBrokenUrls: ${normalized.replace("\n", "\\n")}")

        val result = normalized.lines().joinToString("\n") { line ->
            // Проверяем наличие URL-подобного текста без протокола
            val preprocessed = preprocessUrlLikeLine(line)
            normalizeUrlLikeLine(preprocessed)
        }
        log("normalizeInteractiveText", "Final result: ${result.replace("\n", "\\n")}")
        return result
    }

    /**
     * Склеивает URL разбитые переносами строк.
     * Например: "https://yandex.ru/video/preview/160421091605\n16050498" -> "https://yandex.ru/video/preview/16042109160516050498"
     */
    private fun mergeBrokenUrls(text: String): String {
        log("mergeBrokenUrls", "Input: ${text.replace("\n", "\\n")}")

        // Ищем все потенциальные URL с переносами строк
        // Паттерн: любой URL-подобный текст заканчивающийся на цифры/символы, затем \n, затем цифры
        val urlWithNewlinePattern = Regex("""(?i)([^\s]*\d+)(\n)(\d+)""")
        val matches = urlWithNewlinePattern.findAll(text).toList()
        log("mergeBrokenUrls", "Found ${matches.size} potential broken URL patterns")

        var result = text
        for (match in matches) {
            val urlPart = match.groupValues[1]
            val newline = match.groupValues[2]
            val nextPart = match.groupValues[3]

            log("mergeBrokenUrls", "Match: urlPart='$urlPart', nextPart='$nextPart'")

            // Проверяем что urlPart похож на URL (содержит домен или /)
            val looksLikeUrl = urlPart.contains("yandex") || urlPart.contains("google") ||
                              urlPart.contains("/") || urlPart.contains(".")

            if (looksLikeUrl) {
                // Проверяем что nextPart - продолжение (только цифры)
                if (nextPart.all(Char::isDigit)) {
                    val merged = "$urlPart$nextPart"
                    log("mergeBrokenUrls", "MERGED: $merged")
                    result = result.replace(match.value, merged)
                }
            }
        }

        log("mergeBrokenUrls", "Output: ${result.replace("\n", "\\n")}")
        return result
    }

    /**
     * Предобработка строки для поиска URL-подобных паттернов без протокола.
     * Например: "https://yandex.ru/video/preview/160421091605\n16050498"
     * Превращается в: "https://yandex.ru/video/preview/16042109160516050498"
     */
private fun preprocessUrlLikeLine(line: String): String {
        log("preprocessUrlLikeLine", "Input line: $line")
        // Паттерн: строка с URL-like доменом и цифрами после переноса
        // Например: "yandex.ru/video/preview/160421091605" на одной строке
        // и "16050498" на следующей - это один URL

        // Если строка содержит домен и путь но не протокол, добавляем https://
        val domainUrlPattern = Regex("""
            (?i)                       # Case insensitive
            (                          # Группа 1: префикс с протоколом (опционально)
              (https?://)?             # Протокол (опционально)
              (www\.)?                 # www (опционально)
            )
            ([a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.[a-z]{2,})  # Домен
            (/[^\s]*)?                 # Путь (опционально, до первого пробела/переноса)
        """.trimIndent())

        val urlMatch = domainUrlPattern.find(line)
        if (urlMatch != null) {
            val prefix = urlMatch.groups[1]?.value ?: ""
            val domain = urlMatch.groups[4]?.value ?: ""
            val path = urlMatch.groups[5]?.value ?: ""
            log("preprocessUrlLikeLine", "Found URL-like: prefix='$prefix', domain='$domain', path='$path'")

            // Если протокола нет, добавляем https://
            if (prefix.isEmpty() || !prefix.contains("://")) {
                val start = urlMatch.range.first
                val beforeUrl = line.substring(0, start)
                val afterUrl = line.substring(urlMatch.range.last + 1)

                // Склеиваем: убираем пробелы между доменом/путём и цифрами после
                val reconstructedUrl = "https://${domain}${path}"
                log("preprocessUrlLikeLine", "Reconstructed URL: $reconstructedUrl")
                return beforeUrl + reconstructedUrl + afterUrl
            }
        }

        return line
    }

    private fun normalizeUrlLikeLine(line: String): String {
        log("normalizeUrlLikeLine", "Input line: $line")
        val match = Regex("""(?i)\bhttps?://\S+""").find(line) ?: return line
        val prefix = line.substring(0, match.range.first)
        val tail = line.substring(match.range.first)
        val tokens = tail.split(Regex("""\s+""")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return line

        val candidateTokens = mutableListOf<String>()
        for (token in tokens) {
            val normalized = normalizeUrlToken(token)
            if (candidateTokens.isEmpty()) {
                if (!normalized.startsWith("http://", ignoreCase = true) &&
                    !normalized.startsWith("https://", ignoreCase = true)
                ) {
                    return line
                }
                candidateTokens += normalized
                continue
            }

            if (!isUrlContinuationToken(normalized)) break
            candidateTokens += normalized
        }

        if (candidateTokens.size == 1) return line

        val urlTokens = trimNonUrlTrailingTokens(candidateTokens)
        if (urlTokens.size == 1) return line

        val suffixTokens = tokens.drop(urlTokens.size)
        val suffix = if (suffixTokens.isEmpty()) "" else " " + suffixTokens.joinToString(" ")
        return prefix + buildUrlFromTokens(urlTokens) + suffix
    }

    private fun normalizeUrlToken(token: String): String {
        return token
            .trim(' ', '\t', '.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')
            .map { char ->
                when (char) {
                    'а', 'А' -> 'a'
                    'е', 'Е' -> 'e'
                    'о', 'О' -> 'o'
                    'р', 'Р' -> 'p'
                    'с', 'С' -> 'c'
                    'х', 'Х' -> 'x'
                    'у', 'У' -> 'y'
                    'к', 'К' -> 'k'
                    'м', 'М' -> 'm'
                    'т', 'Т' -> 't'
                    'п', 'П' -> 'n'
                    else -> char
                }
            }
            .joinToString("")
    }

private fun isUrlContinuationToken(token: String): Boolean {
        if (token.length > 80) return false
        // Нормализуем токен (конвертируем кириллицу в латиницу) перед проверкой
        val normalized = normalizeUrlToken(token)
        
        // Проверяем что нормализованный токен соответствует URL символам
        if (!normalized.matches(Regex("""[A-Za-z0-9._~?#@!$&()*+,;=%-а-яА-ЯёЁ]+"""))) {
            return false
        }
        
        // Разрешаем продолжение если токен:
        // 1. Содержит спецсимволы URL (. ~ ? # @ ! $ & ( ) * + , ; = % - / :)
        // 2. Является числовым сегментом
        // 3. Содержит ТОЛЬКО буквы (может быть словом пути) - но это обрабатывается контекстно
        
        val hasSpecificUrlChars = normalized.any { it in "._~?#@!$&()*+,;=%-:/-" }
        val isNumericSegment = normalized.all(Char::isDigit)
        val isWordSegment = normalized.all { it in 'A'..'Z' || it in 'a'..'z' || it in 'а'..'я' || it in 'А'..'Я' }
        
        // Буквенные токены (video, preview, next) разрешаем,
        // но только если предыдущий токен заканчивается на / или numeric path segment
        // Это позволяет: "https://yandex.ru video 123" -> "https://yandex.ru/video/123"
        // Но запрещает: "https://example.com next words" -> "https://example.com"
        // Проверка контекста будет в normalizeUrlLikeLine через previous token
        
        return hasSpecificUrlChars || isNumericSegment || isWordSegment
    }

private fun trimNonUrlTrailingTokens(tokens: List<String>): List<String> {
        // Находим последний токен, который содержит URL-специфичные символы или цифры
        // Исключаем токены с ТОЛЬКО буквами (обычные слова)
        val lastAnchorIndex = tokens.indexOfLast { token ->
            // Нормализуем для проверки
            val normalized = normalizeUrlToken(token)
            // Принимаем если есть спецсимволы ИЛИ цифры
            // Отклоняем если только буквы (обычные слова типа "next", "words")
            val hasUrlChars = normalized.any { it in "._~?#@!$&()*+,;=%-:/-" }
            val hasDigits = normalized.any(Char::isDigit)
            hasUrlChars || hasDigits
        }

        return if (lastAnchorIndex <= 0) {
            // Если нет явных URL токенов, берём только первый токен (URL)
            listOf(tokens.first())
        } else {
            tokens.take(lastAnchorIndex + 1)
        }
    }

    private fun buildUrlFromTokens(tokens: List<String>): String {
        return buildString {
            append(tokens.first())
            var previous = tokens.first()
            tokens.drop(1).forEach { token ->
                val normalizedToken = normalizeUrlToken(token)
                // Если токен содержит только цифры И предыдущий токен заканчивается на числовой сегмент,
                // добавляем токен напрямую (склеиваем числа без разделителя)
                if (normalizedToken.all(Char::isDigit) && hasTrailingNumericPathSegment(previous)) {
                    append(normalizedToken)
                } else {
                    // Добавляем разделитель / между токенами
                    if (!endsWith("/")) {
                        append('/')
                    }
                    append(normalizedToken)
                }
                previous = token // Используем оригинальный токен для проверки
            }
        }
    }

    private fun hasTrailingNumericPathSegment(token: String): Boolean {
        val segment = token.substringAfterLast('/')
        return segment.isNotEmpty() && segment.all(Char::isDigit)
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
