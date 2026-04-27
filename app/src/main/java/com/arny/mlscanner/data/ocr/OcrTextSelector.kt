package com.arny.mlscanner.data.ocr

object OcrTextSelector {

    /**
     * Выбирает лучший текст между raw (Tesseract UTF8) и boxed (собранный из bounding boxes).
     * 
     * Raw text обычно лучше сохраняет reading order, но может пропускать слова.
     * Boxed text может содержать больше слов, но может ломать порядок строк.
     * 
     * @param rawText Текст из api.utF8Text (сохраняет reading order)
     * @param boxedText Текст, собранный из bounding boxes
     * @param expectedAnchors Опциональные якорные слова для проверки порядка
     */
    fun chooseBestText(
        rawText: String,
        boxedText: String,
        expectedAnchors: List<String> = emptyList()
    ): String {
        if (rawText.isBlank()) return boxedText
        if (boxedText.isBlank()) return rawText

        val rawScore = scoreText(rawText, expectedAnchors)
        val boxedScore = scoreText(boxedText, expectedAnchors)

        // Предпочитаем rawText если он не сильно хуже (90% порог)
        // Это важно для сохранения reading order
        return if (rawScore >= boxedScore * 0.90f) {
            rawText
        } else {
            boxedText
        }
    }

    /**
     * Оценивает качество текста с учетом:
     * - Количества слов
     * - Количества строк
     * - Соотношения кириллицы
     * - Наличия мусорных символов
     * - Структурных паттернов
     * - Якорных слов (если заданы)
     */
    private fun scoreText(text: String, expectedAnchors: List<String>): Float {
        if (text.isBlank()) return 0f

        val words = countWords(text)
        val lines = text.lines().count { it.isNotBlank() }
        val letters = text.count { it.isLetter() }.coerceAtLeast(1)
        val cyrillicCount = text.count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
        val latinLookalikeCount = text.count { it in "aAeEoOpPcCxXyY" }
        
        // Мусорные символы
        val garbageCount = text.count { it in "|{}[]~`\\" }
        val garbagePatterns = Regex("""[|{}\[\]~`\\]{2,}""").findAll(text).count()
        
        // Слишком длинные строки (склеенные)
        val longLines = text.lines().count { it.length > 140 }
        
        // Структурные паттерны
        val structureBonus = documentStructureBonus(text)
        
        // Якорные слова
        val anchorBonus = expectedAnchors.count { anchor ->
            text.contains(anchor, ignoreCase = true)
        } * 20f
        
        val cyrillicRatio = cyrillicCount.toFloat() / letters
        
        return words * 2f +
                lines * 3f +
                cyrillicRatio * 20f +
                structureBonus +
                anchorBonus -
                latinLookalikeCount * 0.3f -
                garbageCount * 2f -
                garbagePatterns * 10f -
                longLines * 10f
    }

    /**
     * Бонус за наличие структурных паттернов в документе.
     */
    private fun documentStructureBonus(text: String): Float {
        var bonus = 0f

        // Паттерн: дата (число + месяц)
        if (text.contains(Regex("""\d{1,2}\s+[а-яё]+""", RegexOption.IGNORE_CASE))) {
            bonus += 5f
        }

        // Паттерн: метки с двоеточием ("Задание:", "Примечание:", etc)
        val labelCount = Regex("""[А-ЯЁA-Z][а-яёa-z\s]+:""").findAll(text).count()
        bonus += labelCount * 3f

        // Паттерн: URL
        if (text.contains(Regex("""https?://"""))) {
            bonus += 8f
        }

        // Паттерн: номера (№)
        if (text.contains(Regex("""№\s*\d+"""))) {
            bonus += 3f
        }

        // Паттерн: нумерованные списки
        val listItemCount = Regex("""^\s*[0-9]+[.)]""", RegexOption.MULTILINE).findAll(text).count()
        bonus += listItemCount * 2f

        return bonus.coerceAtMost(40f)
    }

    private fun countWords(text: String): Int {
        return text.split(Regex("""\s+""")).count { it.length > 1 }
    }
}
