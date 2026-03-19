package com.arny.mlscanner.domain.models

/**
 * Детекция языка по тексту.
 * 
 * Единственное место определения языка — 
 * вызывается из RecognizeTextUseCase и OcrResultMapper.
 */
object LanguageDetector {

    /**
     * Определение языка текста.
     * 
     * Алгоритм:
     * 1. Берём только буквенные символы (без цифр, пробелов, знаков)
     * 2. Считаем долю кириллицы и латиницы
     * 3. Определяем язык по порогам
     *
     * @return "RUS", "ENG", "RUS+ENG" или "UNKNOWN"
     */
    fun detect(text: String): String {
        if (text.isBlank()) return "UNKNOWN"

        // Берём ТОЛЬКО буквы — цифры и спецсимволы не учитываем
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return "UNKNOWN"

        // Кириллица: весь блок Unicode (заглавные + строчные + ё/Ё)
        val cyrillicCount = letters.count { char ->
            char in '\u0400'..'\u04FF' // Весь кириллический блок Unicode
        }

        // Латиница
        val latinCount = letters.count { char ->
            char in 'A'..'Z' || char in 'a'..'z'
        }

        val total = letters.length.toFloat()
        val cyrillicRatio = cyrillicCount / total
        val latinRatio = latinCount / total

        return when {
            // Более 60% кириллицы → русский
            cyrillicRatio > 0.6f -> "RUS"
            // Более 60% латиницы → английский
            latinRatio > 0.6f -> "ENG"
            // Оба присутствуют значительно → смешанный
            cyrillicRatio > 0.15f && latinRatio > 0.15f -> "RUS+ENG"
            // Кириллицы больше
            cyrillicRatio > latinRatio -> "RUS"
            // Латиницы больше
            latinRatio > cyrillicRatio -> "ENG"
            // Непонятно
            else -> "UNKNOWN"
        }
    }

    /**
     * Человекочитаемое название языка для UI.
     */
    fun displayName(langCode: String): String = when (langCode) {
        "RUS" -> "Русский"
        "ENG" -> "English"
        "RUS+ENG" -> "Русский + English"
        else -> "Unknown"
    }
}
