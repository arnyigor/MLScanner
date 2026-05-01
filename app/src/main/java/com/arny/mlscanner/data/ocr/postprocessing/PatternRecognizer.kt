package com.arny.mlscanner.data.ocr.postprocessing

import java.util.regex.Pattern

/**
 * Распознаватель специальных паттернов в тексте (телефоны, ссылки, email и т.д.)
 */
object PatternRecognizer {
    
    /**
     * Типы распознанных паттернов
     */
    enum class PatternType {
        PHONE,      // Телефонный номер
        EMAIL,      // Email адрес
        URL,        // Веб-ссылка
        DATE,       // Дата
        TIME,       // Время
        MONEY,      // Денежная сумма
        INN,        // ИНН (российский)
        CARD       // Номер карты
    }
    
    /**
     * Распознанный паттерн
     */
    data class RecognizedPattern(
        val type: PatternType,
        val value: String,
        val start: Int,
        val end: Int,
        val formatted: String = value
    )
    
    // Улучшенные паттерны для телефонов (поддерживают множество форматов)
    private val PHONE_PATTERNS = listOf(
        // +7 (900) 123-45-67
        Pattern.compile("\\+7\\s*\\(?\\d{3}\\)?[\\s\\-]?\\d{3}[\\s\\-]?\\d{2}[\\s\\-]?\\d{2}"),
        // 8 (900) 123-45-67
        Pattern.compile("8\\s*\\(?\\d{3}\\)?[\\s\\-]?\\d{3}[\\s\\-]?\\d{2}[\\s\\-]?\\d{2}"),
        // 8 9000 23-23-45 (с пробелами)
        Pattern.compile("8\\s+\\d{4}\\s+\\d{2}[\\s\\-]?\\d{2}[\\s\\-]?\\d{2}"),
        // 9001234567 (без префикса)
        Pattern.compile("(?<!\\d)9\\d{9}(?!\\d)"),
        // 7 900 123 45 67
        Pattern.compile("7\\s+\\d{3}\\s+\\d{3}\\s+\\d{2}\\s+\\d{2}"),
        // (900) 123-45-67
        Pattern.compile("\\(?\\d{3}\\)?[\\s\\-]?\\d{3}[\\s\\-]?\\d{2}[\\s\\-]?\\d{2}")
    )
    
    private val DATE_PATTERN = Pattern.compile(
        "\\b(\\d{1,2})[./-](\\d{1,2})[./-](\\d{2,4})\\b"
    )
    
    private val TIME_PATTERN = Pattern.compile(
        "\\b(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\b"
    )
    
    private val MONEY_PATTERN = Pattern.compile(
        "\\b(\\d+(?:[.,]\\d{2})?)\\s*(?:руб|₽|rub|р\\.?|dollars?|\\$|€|euro)\\b",
        Pattern.CASE_INSENSITIVE
    )
    
    private val INN_PATTERN = Pattern.compile(
        "\\b\\d{10}\\b|\\b\\d{12}\\b"
    )
    
    private val CARD_PATTERN = Pattern.compile(
        "\\b\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}[\\s-]?\\d{4}\\b"
    )

    private val EMAIL_PATTERN = Pattern.compile(
        """(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,63}\b"""
    )

    private val EXPLICIT_URL_PATTERN = Pattern.compile(
        """(?i)\bhttps?://[A-Za-z0-9АаЕеОоРрСсХхУуКкМмТтПп._~:/?#@!$&()*+,;=%\-]+"""
    )

    private val WWW_URL_PATTERN = Pattern.compile(
        """(?i)(?<![@\w])www\.[^\s<>"'{}|\\^`\[\]]+"""
    )

private val BARE_DOMAIN_PATTERN = Pattern.compile(
        """(?i)(?<![@\w\.])  # Не начинаем с @, буквы или точки
            (?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+  # Поддомены (至少有 один subdomain)
            (?:[a-z]{2,63}|xn--[a-z0-9-]{2,59})  # TLD
            # Ключевое: после TLD должен быть либо /, либо конец строки, либо НЕ пробел
            # Проверяем что перед точкой в TLD не было пробела (т.е. это не "слово . слово")
            (?:/[^\s<>"'{}|\\^`\[\]]*)?  # Путь (опционально)
            (?=\s|$|[.,;:!?])  # После домена пробел, конец строки или знак препинания
        """,
        Pattern.COMMENTS
    )

    private val COMMON_TLDS = setOf(
        "ru", "рф", "com", "org", "net", "edu", "gov", "io", "ai", "app", "dev",
        "info", "biz", "me", "tv", "co", "uk", "de", "fr", "it", "es", "nl",
        "pl", "ua", "by", "kz", "cn", "jp", "kr"
    )
    
    /**
     * Распознаёт все паттерны в тексте
     */
    fun recognizeAll(text: String): List<RecognizedPattern> {
        val patterns = mutableListOf<RecognizedPattern>()
        
        // Телефоны
        patterns.addAll(recognizePhones(text))
        
        // Email
        patterns.addAll(recognizeEmails(text))
        
        // URL
        patterns.addAll(recognizeUrls(text))
        
        // Даты
        patterns.addAll(recognizeDates(text))
        
        // Время
        patterns.addAll(recognizeTimes(text))
        
        // Деньги
        patterns.addAll(recognizeMoney(text))
        
        // ИНН
        patterns.addAll(recognizeInn(text))
        
        // Карты
        patterns.addAll(recognizeCards(text))
        
        // Удаляем дубликаты и перекрывающиеся паттерны
        return removeOverlaps(text, patterns)
    }
    
    /**
     * Удаляет дубликаты и перекрывающиеся паттерны
     */
    private fun removeOverlaps(
        text: String,
        patterns: List<RecognizedPattern>
    ): List<RecognizedPattern> {
        if (patterns.isEmpty()) return emptyList()

        val occupied = BooleanArray(text.length)
        val result = mutableListOf<RecognizedPattern>()
        val sorted = patterns.sortedWith(
            compareBy<RecognizedPattern> { it.start }
                .thenBy { priority(it.type) }
                .thenByDescending { it.end - it.start }
        )

        for (pattern in sorted) {
            val start = pattern.start.coerceIn(0, text.length)
            val end = pattern.end.coerceIn(start, text.length)
            if ((start until end).any { occupied[it] }) continue

            for (index in start until end) {
                occupied[index] = true
            }
            result += pattern.copy(start = start, end = end)
        }

        return result.sortedBy { it.start }
    }

    private fun priority(type: PatternType): Int {
        return when (type) {
            PatternType.URL -> 0
            PatternType.EMAIL -> 1
            PatternType.CARD -> 2
            PatternType.PHONE -> 3
            PatternType.INN -> 4
            PatternType.DATE -> 5
            PatternType.TIME -> 6
            PatternType.MONEY -> 7
        }
    }
    
    /**
     * Распознаёт телефонные номера (улучшенная версия)
     */
    fun recognizePhones(text: String): List<RecognizedPattern> {
        val patterns = mutableListOf<RecognizedPattern>()
        
        // Нормализуем текст для лучшего распознавания:
        // Ищем паттерны типа "8 " или "+7 " с последующими цифрами
        // и склеиваем их для распознавания
        val normalizedText = normalizePhoneNumbers(text)
        
        // Пробуем все паттерны на нормализованном тексте
        for (pattern in PHONE_PATTERNS) {
            val matcher = pattern.matcher(normalizedText)
            
            while (matcher.find()) {
                val raw = matcher.group()
                
                // Проверяем, что это действительно похоже на телефон
                val digits = raw.replace(Regex("[^0-9]"), "")
                if (isValidPhoneDigits(digits)) {
                    // Находим оригинальную позицию в исходном тексте
                    val originalStart = findOriginalPosition(text, normalizedText, matcher.start())
                    val originalEnd = findOriginalPosition(text, normalizedText, matcher.end())
                    
                    val formatted = formatPhone(raw)
                    
                    patterns.add(
                        RecognizedPattern(
                            type = PatternType.PHONE,
                            value = raw,
                            start = originalStart,
                            end = originalEnd,
                            formatted = formatted
                        )
                    )
                }
            }
        }
        
        return patterns
    }
    
    /**
     * Нормализует текст для лучшего распознавания телефонов.
     * 
     * Примеры:
     * "8 9 0 0 0 2 3 - 2 3 - 4 5" -> "89000232345"
     * "+ 7 9 0 0 1 2 3 4 5 6 7" -> "+79001234567"
     * "8  (  900  )  123  -  45  -  67" -> "89001234567"
     */
    private fun normalizePhoneNumbers(text: String): String {
        var normalized = text
        
        // Паттерн: начало номера (8, +7, 7) с последующими цифрами через пробелы/дефисы/скобки
        val phoneStartPattern = Pattern.compile(
            "(\\+?[78])\\s*([0-9\\s\\-()]+)"
        )
        
        val matcher = phoneStartPattern.matcher(text)
        val replacements = mutableListOf<Pair<IntRange, String>>()
        
        while (matcher.find()) {
            val prefix = matcher.group(1) ?: continue // "8" или "+7" или "7"
            val rest = matcher.group(2) ?: continue   // остальные цифры с пробелами
            
            // ПОЛНОСТЬЮ удаляем все пробелы, дефисы, скобки - оставляем только цифры
            val digitsOnly = rest.replace(Regex("[^0-9]"), "")
            
            // Проверяем, что после префикса достаточно цифр
            if (digitsOnly.length >= 10) {
                // Склеиваем БЕЗ пробелов: "8 9000 23-23-45" -> "89000232345"
                val normalized = prefix.replace(Regex("[^+0-9]"), "") + digitsOnly
                replacements.add(matcher.start()..matcher.end() to normalized)
            }
        }
        
        // Применяем замены в обратном порядке (чтобы не сбить индексы)
        for ((range, replacement) in replacements.reversed()) {
            normalized = normalized.substring(0, range.first) + 
                        replacement + 
                        normalized.substring(range.last)
        }
        
        return normalized
    }
    
    /**
     * Находит оригинальную позицию в исходном тексте после нормализации.
     */
    private fun findOriginalPosition(original: String, normalized: String, normalizedPos: Int): Int {
        // Простая эвристика: если тексты одинаковой длины, позиция не изменилась
        if (original.length == normalized.length) {
            return normalizedPos
        }
        
        // Иначе пытаемся найти соответствующую позицию
        // (это упрощённая версия, для полной корректности нужен diff-алгоритм)
        return normalizedPos.coerceIn(0, original.length)
    }
    
    /**
     * Проверяет, что количество цифр подходит для телефона
     */
    private fun isValidPhoneDigits(digits: String): Boolean {
        return when (digits.length) {
            10 -> digits.startsWith("9") // 9001234567
            11 -> digits.startsWith("7") || digits.startsWith("8") // 79001234567 или 89001234567
            else -> false
        }
    }
    
    /**
     * Распознаёт email адреса
     */
    fun recognizeEmails(text: String): List<RecognizedPattern> {
        val patterns = mutableListOf<RecognizedPattern>()
        val matcher = EMAIL_PATTERN.matcher(text)
        
        while (matcher.find()) {
            val email = matcher.group()
            
            patterns.add(
                RecognizedPattern(
                    type = PatternType.EMAIL,
                    value = email,
                    start = matcher.start(),
                    end = matcher.end()
                )
            )
        }
        
        return patterns
    }
    
    /**
     * Распознаёт URL
     */
    fun recognizeUrls(text: String): List<RecognizedPattern> {
        val patterns = mutableListOf<RecognizedPattern>()
        val occupied = BooleanArray(text.length)

        listOf(EXPLICIT_URL_PATTERN, WWW_URL_PATTERN, BARE_DOMAIN_PATTERN).forEach { pattern ->
            val matcher = pattern.matcher(text)

            while (matcher.find()) {
                val rawUrl = matcher.group()
                val url = normalizeUrlValue(rawUrl)
                if (!isValidUrlCandidate(url)) continue

                val start = matcher.start()
                val end = matcher.end().coerceAtMost(text.length)
                if ((start until end).any { occupied[it] }) continue

                patterns.add(
                    RecognizedPattern(
                        type = PatternType.URL,
                        value = url,
                        start = start,
                        end = end
                    )
                )

                for (index in start until end) {
                    occupied[index] = true
                }
            }
        }
        
        return patterns
    }

    private fun trimUrlBoundary(rawUrl: String): String {
        return rawUrl.trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}', '"', '\'')
    }

    private fun normalizeUrlValue(rawUrl: String): String {
        return fixUrlOcrConfusables(trimUrlBoundary(rawUrl))
    }

    private fun fixUrlOcrConfusables(value: String): String {
        return buildString(value.length) {
            for (char in value) {
                append(
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
                )
            }
        }
    }

    private fun isValidUrlCandidate(url: String): Boolean {
        if (url.isBlank() || url.contains("@")) return false

        val lower = url.lowercase()
        val hostAndPath = when {
            lower.startsWith("http://") -> url.substringAfter("://")
            lower.startsWith("https://") -> url.substringAfter("://")
            else -> url
        }
        val host = hostAndPath.substringBefore('/').substringBefore('?').substringBefore('#')
        val tld = host.substringAfterLast('.', "")

        return host.contains('.') && tld in COMMON_TLDS
    }
    
    /**
     * Распознаёт даты
     */
    fun recognizeDates(text: String): List<RecognizedPattern> {
        val patterns = mutableListOf<RecognizedPattern>()
        val matcher = DATE_PATTERN.matcher(text)
        
        while (matcher.find()) {
            val date = matcher.group()
            val formatted = formatDate(date)
            
            patterns.add(
                RecognizedPattern(
                    type = PatternType.DATE,
                    value = date,
                    start = matcher.start(),
                    end = matcher.end(),
                    formatted = formatted
                )
            )
        }
        
        return patterns
    }
    
    /**
     * Распознаёт время
     */
    fun recognizeTimes(text: String): List<RecognizedPattern> {
        val patterns = mutableListOf<RecognizedPattern>()
        val matcher = TIME_PATTERN.matcher(text)
        
        while (matcher.find()) {
            val time = matcher.group()
            
            patterns.add(
                RecognizedPattern(
                    type = PatternType.TIME,
                    value = time,
                    start = matcher.start(),
                    end = matcher.end()
                )
            )
        }
        
        return patterns
    }
    
    /**
     * Распознаёт денежные суммы
     */
    fun recognizeMoney(text: String): List<RecognizedPattern> {
        val patterns = mutableListOf<RecognizedPattern>()
        val matcher = MONEY_PATTERN.matcher(text)
        
        while (matcher.find()) {
            val money = matcher.group()
            val formatted = formatMoney(money)
            
            patterns.add(
                RecognizedPattern(
                    type = PatternType.MONEY,
                    value = money,
                    start = matcher.start(),
                    end = matcher.end(),
                    formatted = formatted
                )
            )
        }
        
        return patterns
    }
    
    /**
     * Распознаёт ИНН
     */
    fun recognizeInn(text: String): List<RecognizedPattern> {
        val patterns = mutableListOf<RecognizedPattern>()
        val matcher = INN_PATTERN.matcher(text)
        
        while (matcher.find()) {
            val inn = matcher.group()
            
            // Проверяем, что это действительно похоже на ИНН (не просто случайные цифры)
            if (isLikelyInn(text, matcher.start(), matcher.end())) {
                patterns.add(
                    RecognizedPattern(
                        type = PatternType.INN,
                        value = inn,
                        start = matcher.start(),
                        end = matcher.end()
                    )
                )
            }
        }
        
        return patterns
    }
    
    /**
     * Распознаёт номера карт
     */
    fun recognizeCards(text: String): List<RecognizedPattern> {
        val patterns = mutableListOf<RecognizedPattern>()
        val matcher = CARD_PATTERN.matcher(text)
        
        while (matcher.find()) {
            val card = matcher.group()
            val formatted = formatCard(card)
            
            patterns.add(
                RecognizedPattern(
                    type = PatternType.CARD,
                    value = card,
                    start = matcher.start(),
                    end = matcher.end(),
                    formatted = formatted
                )
            )
        }
        
        return patterns
    }
    
    // Форматирование
    
    private fun formatPhone(phone: String): String {
        val digits = phone.replace(Regex("[^0-9]"), "")
        
        return when {
            digits.length == 11 && digits.startsWith("8") -> {
                "+7 (${digits.substring(1, 4)}) ${digits.substring(4, 7)}-${digits.substring(7, 9)}-${digits.substring(9)}"
            }
            digits.length == 11 && digits.startsWith("7") -> {
                "+7 (${digits.substring(1, 4)}) ${digits.substring(4, 7)}-${digits.substring(7, 9)}-${digits.substring(9)}"
            }
            digits.length == 10 && digits.startsWith("9") -> {
                "+7 (${digits.substring(0, 3)}) ${digits.substring(3, 6)}-${digits.substring(6, 8)}-${digits.substring(8)}"
            }
            else -> phone
        }
    }
    
    private fun formatDate(date: String): String {
        // Нормализуем разделители
        return date.replace(Regex("[/-]"), ".")
    }
    
    private fun formatMoney(money: String): String {
        // Нормализуем запятые на точки
        return money.replace(",", ".")
    }
    
    private fun formatCard(card: String): String {
        val digits = card.replace(Regex("[^0-9]"), "")
        return if (digits.length == 16) {
            "${digits.substring(0, 4)} ${digits.substring(4, 8)} ${digits.substring(8, 12)} ${digits.substring(12)}"
        } else {
            card
        }
    }
    
    private fun isLikelyInn(text: String, start: Int, end: Int): Boolean {
        // Проверяем контекст - есть ли слово "ИНН" рядом
        val contextStart = maxOf(0, start - 20)
        val contextEnd = minOf(text.length, end + 20)
        val context = text.substring(contextStart, contextEnd).lowercase()
        
        return context.contains("инн") || context.contains("inn")
    }
}
