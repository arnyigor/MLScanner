package com.arny.mlscanner.data.ocr.core.postprocessing

import android.util.Log
import com.arny.mlscanner.data.ocr.core.IOcrPostProcessor
import com.arny.mlscanner.data.ocr.core.OcrPostProcessResult
import com.arny.mlscanner.data.ocr.core.OcrRequest
import com.arny.mlscanner.data.ocr.core.OcrTextChange
import com.arny.mlscanner.data.ocr.postprocessing.RussianPostProcessor

/**
 * Адаптер для RussianPostProcessor в новый pipeline.
 */
class RussianPostProcessorAdapter : IOcrPostProcessor {

    companion object {
        private const val TAG = "RussianPostProcessor"
    }

    override val id: String = "russian_visual_fixer"
    override val name: String = "Russian Visual Substitution Fixer"

    override fun supports(request: OcrRequest): Boolean {
        // Применяем только для русского языка
        return request.language.tesseractCode.contains("rus")
    }

    override fun process(text: String, request: OcrRequest): OcrPostProcessResult {
        if (text.isBlank()) {
            return OcrPostProcessResult(text = text, changes = emptyList())
        }

        Log.d(TAG, "=== RussianPostProcessor INPUT ===")
        Log.d(TAG, "Input text: ${text.take(300)}")

        val processed = RussianPostProcessor.process(text)

        Log.d(TAG, "Output text: ${processed.take(300)}")

        // Анализируем изменения
        val metrics = RussianPostProcessor.analyzeQuality(text, processed)

        Log.d(TAG, "Quality metrics: $metrics")

        val changes = if (metrics.fixedChars > 0) {
            listOf(
                OcrTextChange(
                    position = 0,
                    original = text,
                    replacement = processed,
                    reason = "Fixed ${metrics.fixedChars} Latin→Cyrillic substitutions"
                )
            )
        } else {
            emptyList()
        }

        return OcrPostProcessResult(
            text = processed,
            changes = changes,
            warnings = emptyList()
        )
    }
}

/**
 * Нормализатор пробелов.
 */
class WhitespaceNormalizer : IOcrPostProcessor {
    
    override val id: String = "whitespace_normalizer"
    override val name: String = "Whitespace Normalizer"
    
    override fun supports(request: OcrRequest): Boolean = true
    
    override fun process(text: String, request: OcrRequest): OcrPostProcessResult {
        if (text.isBlank()) {
            return OcrPostProcessResult(text = text, changes = emptyList())
        }
        
        var result = text
        val changes = mutableListOf<OcrTextChange>()
        
        // Удаление множественных пробелов
        val beforeMultiple = result
        result = result.replace(Regex(" {2,}"), " ")
        if (result != beforeMultiple) {
            changes.add(OcrTextChange(0, beforeMultiple, result, "Removed multiple spaces"))
        }
        
        // Удаление пробелов в начале/конце строк
        val beforeTrim = result
        result = result.lines().joinToString("\n") { it.trim() }
        if (result != beforeTrim) {
            changes.add(OcrTextChange(0, beforeTrim, result, "Trimmed line spaces"))
        }
        
        return OcrPostProcessResult(
            text = result,
            changes = changes
        )
    }
}

/**
 * Нормализатор переносов строк.
 */
class LineBreakNormalizer : IOcrPostProcessor {
    
    override val id: String = "linebreak_normalizer"
    override val name: String = "Line Break Normalizer"
    
    override fun supports(request: OcrRequest): Boolean = true
    
    override fun process(text: String, request: OcrRequest): OcrPostProcessResult {
        if (text.isBlank()) {
            return OcrPostProcessResult(text = text, changes = emptyList())
        }
        
        var result = text
        val changes = mutableListOf<OcrTextChange>()
        
        // Удаление множественных переносов строк (больше 2)
        val beforeMultiple = result
        result = result.replace(Regex("\n{3,}"), "\n\n")
        if (result != beforeMultiple) {
            changes.add(OcrTextChange(0, beforeMultiple, result, "Normalized line breaks"))
        }
        
        return OcrPostProcessResult(
            text = result,
            changes = changes
        )
    }
}

/**
 * Нормализатор дат.
 */
class DateNormalizer : IOcrPostProcessor {
    
    override val id: String = "date_normalizer"
    override val name: String = "Date Normalizer"
    
    override fun supports(request: OcrRequest): Boolean {
        // Применяем для чеков, документов, водительских
        return request.taskType.name.contains("RECEIPT") ||
               request.taskType.name.contains("DOCUMENT") ||
               request.taskType.name.contains("LICENSE") ||
               request.taskType.name.contains("PASSPORT")
    }
    
    override fun process(text: String, request: OcrRequest): OcrPostProcessResult {
        if (text.isBlank()) {
            return OcrPostProcessResult(text = text, changes = emptyList())
        }
        
        var result = text
        val changes = mutableListOf<OcrTextChange>()
        
        // Исправление O → 0 в датах (DD.MM.YYYY)
        val datePattern = Regex("\\b(\\d{1,2})\\.[Oo](\\d)")
        val beforeDate = result
        result = result.replace(datePattern) { match ->
            "${match.groupValues[1]}.0${match.groupValues[2]}"
        }
        if (result != beforeDate) {
            changes.add(OcrTextChange(0, beforeDate, result, "Fixed O→0 in dates"))
        }
        
        // Исправление 2O26 → 2026
        val yearPattern = Regex("\\b([12])[Oo](\\d{2})\\b")
        val beforeYear = result
        result = result.replace(yearPattern) { match ->
            "${match.groupValues[1]}0${match.groupValues[2]}"
        }
        if (result != beforeYear) {
            changes.add(OcrTextChange(0, beforeYear, result, "Fixed O→0 in years"))
        }
        
        return OcrPostProcessResult(
            text = result,
            changes = changes
        )
    }
}

/**
 * Нормализатор сумм для чеков.
 */
class AmountNormalizer : IOcrPostProcessor {
    
    override val id: String = "amount_normalizer"
    override val name: String = "Amount Normalizer"
    
    override fun supports(request: OcrRequest): Boolean {
        return request.taskType.name.contains("RECEIPT")
    }
    
    override fun process(text: String, request: OcrRequest): OcrPostProcessResult {
        if (text.isBlank()) {
            return OcrPostProcessResult(text = text, changes = emptyList())
        }
        
        var result = text
        val changes = mutableListOf<OcrTextChange>()
        
        // Исправление O → 0 в суммах (1500.OO → 1500.00)
        val amountPattern = Regex("(\\d+)\\.[Oo]{1,2}\\b")
        val beforeAmount = result
        result = result.replace(amountPattern) { match ->
            "${match.groupValues[1]}.00"
        }
        if (result != beforeAmount) {
            changes.add(OcrTextChange(0, beforeAmount, result, "Fixed O→0 in amounts"))
        }
        
// Исправление l/I → 1 в числах
        val digitPattern = Regex("(?<=\\d)[lI](?=\\d|\\b)")
        val beforeDigit = result
        result = result.replace(digitPattern, "1")
        if (result != beforeDigit) {
            changes.add(OcrTextChange(0, beforeDigit, result, "Fixed l/I→1 in numbers"))
        }
        
        return OcrPostProcessResult(
            text = result,
            changes = changes
        )
    }
}

/**
 * Пост-процессор для восстановления латинских букв в URL.
 * RussianPostProcessor заменяет латиницу на кириллицу (a→а, e→е и т.д.)
 * Этот процессор восстанавливает латиницу в URL после RussianPostProcessor.
 */
class UrlLatinRestorer : IOcrPostProcessor {

    companion object {
        private const val TAG = "UrlLatinRestorer"

        // Кириллические буквы, которые часто путаются с латинскими в URL
        private val CYRILLIC_TO_LATIN = mapOf(
            'а' to 'a', 'А' to 'A',
            'е' to 'e', 'Е' to 'E',
            'о' to 'o', 'О' to 'O',
            'р' to 'p', 'Р' to 'P',
            'с' to 'c', 'С' to 'C',
            'х' to 'x', 'Х' to 'X',
            'у' to 'y', 'У' to 'Y',
            'к' to 'k', 'К' to 'K',
            'м' to 'm', 'М' to 'M',
            'т' to 't', 'Т' to 'T',
            'п' to 'n', 'П' to 'N'
        )

        // Паттерны для поиска URL в тексте
        private val URL_PATTERNS = listOf(
            // https://... - с поддержкой кириллицы в домене
            Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE),
            // www... - с поддержкой кириллицы
            Regex("""\bwww\.[^\s]+""", RegexOption.IGNORE_CASE),
            // Домены типа example.com, test.ru и т.д. - с поддержкой кириллицы
            Regex("""(?<![A-Za-z0-9@.])(?:[A-Za-z0-9АаЕеОоРрСсХхУуКкМмТтПп](?:[A-Za-z0-9АаЕеОоРрСсХхУуКкМмТтПп-]{0,61}[A-Za-z0-9АаЕеОоРрСсХхУуКкМмТтПп])?\.)+(?:[A-Za-z]{2,63}|xn--[A-Za-z0-9-]{2,59})(?:/[^\s]*)?""", RegexOption.IGNORE_CASE)
        )
    }

    override val id: String = "url_latin_restorer"
    override val name: String = "URL Latin Character Restorer"

    override fun supports(request: OcrRequest): Boolean = true

    override fun process(text: String, request: OcrRequest): OcrPostProcessResult {
        if (text.isBlank()) {
            return OcrPostProcessResult(text = text, changes = emptyList())
        }

        Log.d(TAG, "=== UrlLatinRestorer INPUT ===")
        Log.d(TAG, "Input text: ${text.take(300)}")

        var result = text
        val changes = mutableListOf<OcrTextChange>()

        for (pattern in URL_PATTERNS) {
            val before = result
            result = pattern.replace(result) { match ->
                val original = match.value
                val restored = restoreLatinInUrl(original)
                if (original != restored) {
                    Log.d(TAG, "URL restored: '$original' -> '$restored'")
                }
                restored
            }
            if (result != before) {
                changes.add(OcrTextChange(0, before, result, "Restored Latin in URLs"))
                break // Обрабатываем за один проход
            }
        }

        Log.d(TAG, "Output text: ${result.take(300)}")
        Log.d(TAG, "=== UrlLatinRestorer END ===")

        return OcrPostProcessResult(
            text = result,
            changes = changes
        )
    }

    private fun restoreLatinInUrl(url: String): String {
        return buildString(url.length) {
            for (char in url) {
                append(CYRILLIC_TO_LATIN[char] ?: char)
            }
        }
    }
}
