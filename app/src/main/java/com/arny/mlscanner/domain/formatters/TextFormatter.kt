// ============================================================
// domain/formatters/TextFormatter.kt
// Постобработка и форматирование распознанного текста
// ============================================================
package com.arny.mlscanner.domain.formatters

/**
 * Форматирование и постобработка распознанного текста.
 *
 * Выделен из UseCase в отдельный класс для:
 * - Единственной ответственности (SRP)
 * - Тестируемости (чистые функции, без зависимостей)
 * - Расширяемости (легко добавить новые правила)
 */
class TextFormatter {

    /**
     * Полный пайплайн постобработки.
     */
    fun format(rawText: String): String {
        if (rawText.isBlank()) return ""

        return rawText
            .let(::fixUrls)
            .let(::fixExtraSpaces)
            .let(::fixLineBreaks)
            .let(::fixCommonOcrMistakes)
            .trim()
    }

    /**
     * Исправление URL-адресов.
     *
     * Tesseract часто добавляет пробелы внутри URL:
     * "https :// example. com / path" → "https://example.com/path"
     */
    fun fixUrls(text: String): String {
        // Паттерн для URL с возможными пробелами
        val urlPattern = Regex(
            """(https?\s*:\s*//\s*[a-zA-Z0-9\s._\-/]+)""",
            RegexOption.IGNORE_CASE
        )

        return urlPattern.replace(text) { match ->
            match.value.replace("\\s+".toRegex(), "")
        }
    }

    /**
     * Удаление лишних пробелов.
     *
     * "Hello   world   !" → "Hello world !"
     */
    fun fixExtraSpaces(text: String): String {
        return text
            // Множественные пробелы → один
            .replace(Regex(" {2,}"), " ")
            // Пробел перед знаком препинания
            .replace(Regex(""" +([.,;:!?)])"""), "$1")
            // Пробел после открывающей скобки
            .replace(Regex("""(\() +"""), "$1")
    }

    /**
     * Нормализация переносов строк.
     *
     * Убирает лишние пустые строки, нормализует переносы.
     */
    fun fixLineBreaks(text: String): String {
        return text
            // Windows переносы → Unix
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            // 3+ пустых строки → 2
            .replace(Regex("\n{3,}"), "\n\n")
            // Пробелы в начале/конце строк
            .split("\n")
            .joinToString("\n") { it.trim() }
    }

    /**
     * Исправление частых ошибок OCR.
     *
     * Список расширяется по мере обнаружения паттернов.
     */
    fun fixCommonOcrMistakes(text: String): String {
        var result = text

        // Распространённые замены для русского текста
        //val replacements = mapOf(
        //    // Цифра 0 вместо буквы О (в контексте слов)
        //    "Regex" to "fix" // placeholder — заполнить реальными паттернами
        //)

        // Исправление: l (L маленькая) → 1 (один) в числовом контексте
        result = result.replace(Regex("""(\d)l(\d)"""), "$1" + "1" + "$2")

        // Исправление: O (буква) → 0 (ноль) в числовом контексте
        result = result.replace(Regex("""(\d)O(\d)"""), "$1" + "0" + "$2")

        return result
    }

    /**
     * Объединение текстовых блоков с учётом пространственного расположения.
     *
     * Если блоки на одной "строке" (Y-координаты пересекаются) →
     * объединяем через пробел.
     * Если блоки на разных строках → объединяем через \n.
     */
    fun joinBlocks(
        blocks: List<Pair<String, com.arny.mlscanner.domain.models.BoundingBox>>
    ): String {
        if (blocks.isEmpty()) return ""
        if (blocks.size == 1) return blocks.first().first

        val sorted = blocks.sortedWith(
            compareBy<Pair<String, com.arny.mlscanner.domain.models.BoundingBox>> {
                it.second.top
            }.thenBy { it.second.left }
        )

        val result = StringBuilder()
        var prevBottom = sorted.first().second.bottom
        var prevTop = sorted.first().second.top

        for ((i, block) in sorted.withIndex()) {
            val (text, box) = block

            if (i == 0) {
                result.append(text)
                continue
            }

            // Проверяем: на одной ли "строке" с предыдущим блоком
            val lineHeight = prevBottom - prevTop
            val verticalOverlap = minOf(prevBottom, box.bottom) - maxOf(prevTop, box.top)
            val isOnSameLine = verticalOverlap > lineHeight * 0.5f

            if (isOnSameLine) {
                result.append(" ")
            } else {
                // Большой разрыв → абзац
                val gap = box.top - prevBottom
                if (gap > lineHeight * 1.5f) {
                    result.append("\n\n")
                } else {
                    result.append("\n")
                }
            }

            result.append(text)
            prevBottom = box.bottom
            prevTop = box.top
        }

        return result.toString()
    }
}