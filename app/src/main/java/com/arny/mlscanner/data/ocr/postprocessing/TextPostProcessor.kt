package com.arny.mlscanner.data.ocr.postprocessing

/**
 * Постобработка текста после Tesseract OCR.
 * 
 * Использует ТОЛЬКО универсальные паттерны и шаблоны.
 * НЕ привязывается к конкретным словам или доменной логике.
 */
object TextPostProcessor {

    /**
     * Нормализует текст от Tesseract.
     * Сохраняет reading order из raw text.
     */
    fun normalizeTesseractText(raw: String): String {
        if (raw.isBlank()) return ""

        var text = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        // Нормализация кавычек
        text = text
            .replace('"', '«')
            .replace('"', '»')
            .replace("<<", "«")
            .replace(">>", "»")

        // Нормализация дефисов и тире
        text = text.replace(Regex("[‐‒–—]"), "-")

        // Исправление разорванных URL
        text = fixUrlBreaks(text)

        // Очистка строк
        val cleanedLines = text
            .lines()
            .map { cleanLine(it) }
            .filter { it.isNotBlank() }

        // Склеивание неправильно разорванных строк
        text = joinWrappedLines(cleanedLines)
            .joinToString("\n")

        // Финальная очистка
        return text
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun cleanLine(line: String): String {
        return line
            .trim()
            // Множественные пробелы → один пробел
            .replace(Regex("[ \\t]+"), " ")
            // Пробел перед пунктуацией
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            // Пробел после открывающих скобок/кавычек
            .replace(Regex("([«(\\[{])\\s+"), "$1")
            // Пробел перед закрывающими скобками/кавычками
            .replace(Regex("\\s+([»)\\]}])"), "$1")
            // Нормализация " . " → ". "
            .replace(Regex("\\s+\\.\\s+"), ". ")
            .trim()
    }

    /**
     * Склеивает разорванные URL.
     * Tesseract часто разрывает длинные URL на несколько строк.
     */
    private fun fixUrlBreaks(text: String): String {
        var result = text

        // Паттерн: URL с числовым ID на следующей строке
        // Пример: "https://example.com/video/\n12345678" → "https://example.com/video/12345678"
        result = result.replace(
            Regex("""(https?://[^\s]+)(?:\s*\n\s*)([0-9]{4,})""")
        ) { match ->
            match.groupValues[1] + match.groupValues[2]
        }

        // Склеивание разорванных частей URL (без lookbehind)
        result = result
            .replace(Regex("""https?://\s+"""), "https://")
            .replace(Regex("""(https?://[^\s]*)/\s+"""), "$1/")

        return result
    }

    /**
     * Склеивает строки, которые были неправильно разорваны.
     * Использует эвристики на основе пунктуации и структуры.
     */
    private fun joinWrappedLines(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()

        val result = mutableListOf<String>()

        for (line in lines) {
            if (result.isEmpty()) {
                result += line
                continue
            }

            val previous = result.last()

            // Новая строка начинается если:
            val shouldStartNewLine =
                isDateLine(line) ||
                isLabeledLine(line) ||
                isStructuredDocumentLine(line) ||
                isUrl(line) ||
                previous.endsWith(".") ||
                previous.endsWith(":") ||
                previous.endsWith("!") ||
                previous.endsWith("?") ||
                isUrl(previous) ||
                isNumericListItem(line)

            if (shouldStartNewLine) {
                result += line
            } else {
                // Склеиваем с предыдущей строкой
                result[result.lastIndex] = previous.trimEnd() + " " + line.trimStart()
            }
        }

        return result
    }

    /**
     * Проверяет, является ли строка датой.
     * Паттерн: число + месяц (кириллица)
     */
    private fun isDateLine(line: String): Boolean {
        return line.matches(Regex("""\d{1,2}\s+[а-яё]+""", RegexOption.IGNORE_CASE))
    }

    /**
     * Проверяет, начинается ли строка с метки (label).
     * Паттерн: Слово с заглавной буквы + двоеточие
     */
    private fun isLabeledLine(line: String): Boolean {
        return line.matches(Regex("""^[А-ЯЁA-Z][а-яёa-z\s]+:\s*.*"""))
    }

    /**
     * Проверяет, является ли строка URL.
     */
    private fun isUrl(line: String): Boolean {
        return line.startsWith("http://") || line.startsWith("https://")
    }

    /**
     * Проверяет, является ли строка элементом нумерованного списка.
     * Паттерны: "1.", "2)", "а)", "A.", etc
     */
    private fun isStructuredDocumentLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false

        if (trimmed.matches(Regex("""^\d+[a-zA-Zа-яА-Я]?[.)]\s+.+"""))) return true
        if (trimmed.matches(Regex("""^\d{2}\.\d{2}\.\d{4}(\s+.*)?"""))) return true
        if (trimmed.matches(Regex("""^\d{2}\s+\d{2}\s+\d{4}(\s+.*)?"""))) return true
        if (trimmed.matches(Regex("""^[A-ZА-ЯЁ]{2,}(?:[\s.'-]+[A-ZА-ЯЁ0-9]{1,})*$"""))) return true
        return false
    }

    private fun isNumericListItem(line: String): Boolean {
        return line.matches(Regex("""^[0-9]+[.)].*""")) ||
               line.matches(Regex("""^[а-яa-z][.)].*""", RegexOption.IGNORE_CASE))
    }
}
