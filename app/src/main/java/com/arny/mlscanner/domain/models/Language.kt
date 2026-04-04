// ============================================================
// domain/models/Language.kt
// Типизированные языки вместо магических строк
// ============================================================
package com.arny.mlscanner.domain.models

/**
 * Поддерживаемые языки OCR.
 *
 * Вместо строк "eng", "rus", "en", "ru"
 * используем типобезопасный enum.
 *
 * @property displayName Название для UI
 * @property tesseractCode Код для Tesseract ("rus", "eng")
 * @property bcp47Code BCP-47 код ("ru", "en")
 */
enum class OcrLanguage(
    val displayName: String,
    val tesseractCode: String,
    val bcp47Code: String
) {
    RUSSIAN("Русский", "rus", "ru"),
    ENGLISH("English", "eng", "en"),
    RUSSIAN_ENGLISH("Рус + Eng", "rus+eng", "ru");

    companion object {
        val DEFAULT = RUSSIAN

        /**
         * Определение языка по тексту (улучшенная эвристика).
         */
        fun detectFromText(text: String): OcrLanguage {
            if (text.isBlank()) return DEFAULT

            val letters = text.filter { it.isLetter() }
            if (letters.isEmpty()) return DEFAULT

            val cyrillicCount = letters.count { it in '\u0400'..'\u04FF' }
            val latinCount = letters.count { it in 'A'..'Z' || it in 'a'..'z' }

            val cyrillicRatio = cyrillicCount.toFloat() / letters.length
            val latinRatio = latinCount.toFloat() / letters.length

            return when {
                cyrillicRatio > 0.7f -> RUSSIAN
                latinRatio > 0.7f -> ENGLISH
                cyrillicRatio > 0.2f && latinRatio > 0.2f -> RUSSIAN_ENGLISH
                cyrillicRatio > latinRatio -> RUSSIAN
                else -> ENGLISH
            }
        }
    }
}

enum class OcrEngineType(val displayName: String) {
    ML_KIT("ML Kit"),
    TESSERACT("Tesseract"),
    HYBRID("Hybrid")
}