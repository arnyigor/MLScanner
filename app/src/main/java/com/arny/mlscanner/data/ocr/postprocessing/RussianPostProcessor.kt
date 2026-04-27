package com.arny.mlscanner.data.ocr.postprocessing

import android.util.Log

/**
 * Постобработка русского текста после OCR.
 * 
 * Исправляет типичные ошибки Tesseract:
 * - Замена латинских символов на кириллические (a→а, e→е, o→о, p→р, c→с, x→х)
 * - Исправление распространенных опечаток
 * - Нормализация пробелов и пунктуации
 */
object RussianPostProcessor {
    
    private const val TAG = "RussianPostProcessor"
    
    // Латинские символы, которые часто путаются с кириллическими
    private val LATIN_TO_CYRILLIC = mapOf(
        'a' to 'а', 'A' to 'А',
        'e' to 'е', 'E' to 'Е',
        'o' to 'о', 'O' to 'О',
        'p' to 'р', 'P' to 'Р',
        'c' to 'с', 'C' to 'С',
        'x' to 'х', 'X' to 'Х',
        'y' to 'у', 'Y' to 'У',
        'k' to 'к', 'K' to 'К',
        'B' to 'В',
        'H' to 'Н',
        'M' to 'М',
        'T' to 'Т'
    )
    
    // Паттерны для исправления цифр, которые OCR путает с буквами
    private val DIGIT_PATTERNS = mapOf(
        // Исправление в числовых последовательностях (минимум 2 цифры)
        Regex("(?<=\\d)[lI]") to "1",  // l,I после цифры
        Regex("[lI](?=\\d)") to "1",  // l,I перед цифрой
        Regex("(?<=\\d)[Oo]") to "0",  // O,o после цифры
        Regex("[Oo](?=\\d)") to "0",  // O,o перед цифрой
        Regex("(?<=\\d)S(?=\\d)") to "5",  // S между цифрами
        Regex("(?<=\\d)Z(?=\\d)") to "2",   // Z между цифрами
        // Специальные паттерны для дат (формат DD.MM.YYYY)
        Regex("\\b(\\d{1,2})\\.[Oo](\\d)") to "$1.0$2",  // O после точки в дате
        Regex("\\b(\\d{1,2}\\.\\d{1,2})\\.([Oo12])[Oo](\\d{2})") to "$1.20$3"  // 2O26 -> 2026
    )
    
    /**
     * Обрабатывает текст, исправляя типичные ошибки OCR.
     */
    fun process(text: String): String {
        if (text.isBlank()) return text
        
        var result = text
        
        // 1. Исправление латинских символов в кириллических словах
        result = fixLatinInCyrillic(result)
        
        // 2. Исправление цифр, перепутанных с буквами
        result = fixDigitPatterns(result)
        
        // 3. Нормализация пробелов
        result = normalizeSpaces(result)
        
        // 4. Исправление пунктуации
        result = fixPunctuation(result)
        
        return result
    }
    
    /**
     * Заменяет латинские символы на кириллические в словах,
     * которые содержат преимущественно кириллицу.
     */
    private fun fixLatinInCyrillic(text: String): String {
        val words = text.split(Regex("\\b"))
        
        return words.joinToString("") { word ->
            if (shouldFixWord(word)) {
                fixWordLatinToCyrillic(word)
            } else {
                word
            }
        }
    }
    
    /**
     * Проверяет, нужно ли исправлять слово (содержит ли оно кириллицу).
     */
    private fun shouldFixWord(word: String): Boolean {
        if (word.length < 2) return false
        
        val cyrillicCount = word.count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
        val latinCount = word.count { it in 'A'..'Z' || it in 'a'..'z' }
        
        // Исправляем только если есть кириллица И есть латиница
        // Чисто латинские слова не трогаем (это может быть английский текст)
        return cyrillicCount > 0 && latinCount > 0
    }
    
    /**
     * Заменяет латинские символы на кириллические в слове.
     */
    private fun fixWordLatinToCyrillic(word: String): String {
        return word.map { char ->
            LATIN_TO_CYRILLIC[char] ?: char
        }.joinToString("")
    }
    
    /**
     * Исправляет цифры, которые OCR перепутал с буквами.
     */
    private fun fixDigitPatterns(text: String): String {
        var result = text
        
        for ((pattern, replacement) in DIGIT_PATTERNS) {
            result = result.replace(pattern, replacement)
        }
        
        return result
    }
    
    /**
     * Нормализует пробелы (удаляет лишние, исправляет переносы).
     */
    private fun normalizeSpaces(text: String): String {
        var result = text
        
        // Удаление множественных пробелов
        result = result.replace(Regex(" {2,}"), " ")
        
        // Удаление пробелов перед знаками препинания
        result = result.replace(Regex(" +([.,;:!?])"), "$1")
        
        // Добавление пробела после знаков препинания (если его нет)
        // НО НЕ в датах, числах, URL и email (например, 26.04.2026, 1500.00, example.com, test@mail.ru)
        // Проверяем, что после точки/запятой идёт буква, но это не часть URL/email
        result = result.replace(Regex("([.,;:!?])(?=[А-Яа-яA-Za-z])(?![A-Za-z0-9@./:-])"), "$1 ")
        
        // Удаление пробелов в начале и конце строк
        result = result.lines().joinToString("\n") { it.trim() }
        
        return result
    }
    
    /**
     * Исправляет пунктуацию (удаляет мусорные символы).
     */
    private fun fixPunctuation(text: String): String {
        var result = text
        
        // Удаление мусорных символов
        result = result.replace(Regex("[|\\[\\]{}~`^\\\\]+"), "")
        
        // Исправление множественных знаков препинания
        result = result.replace(Regex("([.,;:!?])\\1+"), "$1")
        
        return result
    }
    
    /**
     * Анализирует качество текста после обработки.
     */
    fun analyzeQuality(original: String, processed: String): QualityMetrics {
        val originalCyrillic = original.count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
        val processedCyrillic = processed.count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
        
        val originalLatin = original.count { it in 'A'..'Z' || it in 'a'..'z' }
        val processedLatin = processed.count { it in 'A'..'Z' || it in 'a'..'z' }
        
        val fixedChars = originalLatin - processedLatin
        val cyrillicRatio = if (processed.isNotEmpty()) {
            processedCyrillic.toFloat() / processed.count { it.isLetter() }
        } else 0f
        
        return QualityMetrics(
            fixedChars = fixedChars,
            cyrillicRatio = cyrillicRatio,
            originalLength = original.length,
            processedLength = processed.length
        )
    }
    
    data class QualityMetrics(
        val fixedChars: Int,
        val cyrillicRatio: Float,
        val originalLength: Int,
        val processedLength: Int
    ) {
        override fun toString(): String {
            return "Fixed: $fixedChars chars, Cyrillic: ${"%.1f".format(cyrillicRatio * 100)}%, " +
                   "Length: $originalLength → $processedLength"
        }
    }
}
