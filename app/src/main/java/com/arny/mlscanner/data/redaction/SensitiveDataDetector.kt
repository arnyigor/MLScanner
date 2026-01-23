package com.arny.mlscanner.data.redaction

import android.util.Log
import com.arny.mlscanner.domain.models.BoundingBox
import java.util.regex.Pattern

/**
 * Детектор чувствительных данных
 * В соответствии с требованиями TECH.md:
 * - Распознавание номеров кредитных карт
 * - Распознавание паспортов РФ
 * - Распознавание электронной почты
 * - Распознавание номеров телефонов
 * - Распознавание дат
 * - Распознавание ИНН/СНИЛС/КПП
 * - Пользовательские шаблоны
 */
class SensitiveDataDetector {
    companion object {
        private const val TAG = "SensitiveDataDetector"

        // Регулярные выражения для различных типов чувствительных данных
        private val CREDIT_CARD_PATTERN = Pattern.compile(
            "(?:\\d[ -]*?){13,19}",
            Pattern.CASE_INSENSITIVE
        )

        private val PASSPORT_RF_PATTERN = Pattern.compile(
            "[0-9]{4}\\s?[0-9]{6}",
            Pattern.CASE_INSENSITIVE
        )

        private val EMAIL_PATTERN = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
            Pattern.CASE_INSENSITIVE
        )

        private val PHONE_RF_PATTERN = Pattern.compile(
            "(\\+7|8)[\\s-]?[(]?\\d{3}[)]?[\\s-]?\\d{3}[\\s-]?\\d{2}[\\s-]?\\d{2}",
            Pattern.CASE_INSENSITIVE
        )

        private val DATE_PATTERN = Pattern.compile(
            "\\b\\d{1,2}[./\\-]\\d{1,2}[./\\-]\\d{2,4}\\b",
            Pattern.CASE_INSENSITIVE
        )

        private val INN_PATTERN = Pattern.compile(
            "\\b\\d{10,12}\\b",
            Pattern.CASE_INSENSITIVE
        )

        private val SNILS_PATTERN = Pattern.compile(
            "\\b\\d{3}[\\s-]?\\d{3}[\\s-]?\\d{3}[\\s-]?\\d{2}\\b",
            Pattern.CASE_INSENSITIVE
        )

        private val KPP_PATTERN = Pattern.compile(
            "\\b\\d{4}[\\s-]?\\d{2}[\\s-]?\\d{3}\\b",
            Pattern.CASE_INSENSITIVE
        )
    }

    /**
     * Результат обнаружения чувствительных данных
     */
    data class DetectionResult(
        val creditCards: List<DetectionItem> = emptyList(),
        val passports: List<DetectionItem> = emptyList(),
        val emails: List<DetectionItem> = emptyList(),
        val phones: List<DetectionItem> = emptyList(),
        val dates: List<DetectionItem> = emptyList(),
        val inn: List<DetectionItem> = emptyList(),
        val snils: List<DetectionItem> = emptyList(),
        val kpp: List<DetectionItem> = emptyList(),
        val customPatterns: List<DetectionItem> = emptyList()
    )

    /**
     * Элемент обнаруженных данных
     */
    data class DetectionItem(
        val text: String,
        val boundingBox: BoundingBox,
        val confidence: Float = 1.0f // Для регулярных выражений всегда 100% уверенность
    )

    /**
     * Обнаружение чувствительных данных в тексте с координатами
     */
    fun detectSensitiveData(
        text: String,
        boundingBox: BoundingBox,
        customPatterns: List<String> = emptyList()
    ): DetectionResult {
        // ИСПРАВЛЕНИЕ 2: Раздельные списки вместо одной кучи
        // Это предотвратит ситуацию, когда "обрубок" от одного паттерна
        // попадает в чужой список при фильтрации
        val creditCards = mutableListOf<DetectionItem>()
        val passports = mutableListOf<DetectionItem>()
        val emails = mutableListOf<DetectionItem>()
        val phones = mutableListOf<DetectionItem>()
        val dates = mutableListOf<DetectionItem>()
        val inn = mutableListOf<DetectionItem>()
        val snils = mutableListOf<DetectionItem>()
        val kpp = mutableListOf<DetectionItem>()
        val customResults = mutableListOf<DetectionItem>()

        // 1. Кредитки
        val creditCardMatcher = CREDIT_CARD_PATTERN.matcher(text)
        while (creditCardMatcher.find()) {
            val matchedText = creditCardMatcher.group()
            // Валидация Луна
            if (isValidCreditCard(matchedText.replace(Regex("[\\s-]"), ""))) {
                creditCards.add(DetectionItem(matchedText, boundingBox))
            }
        }

        // Проверяем на паспорта РФ
        val passportMatcher = PASSPORT_RF_PATTERN.matcher(text)
        while (passportMatcher.find()) {
            passports.add(DetectionItem(passportMatcher.group(), boundingBox))
        }

        // Проверяем на email
        val emailMatcher = EMAIL_PATTERN.matcher(text)
        while (emailMatcher.find()) {
            emails.add(DetectionItem(emailMatcher.group(), boundingBox))
        }

        // Проверяем на телефоны РФ
        val phoneMatcher = PHONE_RF_PATTERN.matcher(text)
        while (phoneMatcher.find()) {
            phones.add(DetectionItem(phoneMatcher.group(), boundingBox))
        }

        // Проверяем на даты
        val dateMatcher = DATE_PATTERN.matcher(text)
        while (dateMatcher.find()) {
            dates.add(DetectionItem(dateMatcher.group(), boundingBox))
        }

        // Проверяем на ИНН
        val innMatcher = INN_PATTERN.matcher(text)
        while (innMatcher.find()) {
            inn.add(DetectionItem(innMatcher.group(), boundingBox))
        }

        // Проверяем на СНИЛС
        val snilsMatcher = SNILS_PATTERN.matcher(text)
        while (snilsMatcher.find()) {
            snils.add(DetectionItem(snilsMatcher.group(), boundingBox))
        }

        // Проверяем на КПП
        val kppMatcher = KPP_PATTERN.matcher(text)
        while (kppMatcher.find()) {
            kpp.add(DetectionItem(kppMatcher.group(), boundingBox))
        }

        for (patternStr in customPatterns) {
            try {
                val customPattern = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE)
                val customMatcher = customPattern.matcher(text)
                while (customMatcher.find()) {
                    customResults.add(DetectionItem(customMatcher.group(), boundingBox))
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return DetectionResult(
            creditCards = creditCards,
            passports = passports,
            emails = emails,
            phones = phones,
            dates = dates,
            inn = inn,
            snils = snils,
            kpp = kpp,
            customPatterns = customResults
        )
    }

    /**
     * Проверка, является ли текст номером кредитной карты (с дополнительной проверкой по алгоритму Луна)
     */
    private fun isValidCreditCard(cardNumber: String): Boolean {
        // Проверяем, что строка содержит только цифры и имеет правильную длину
        if (!cardNumber.matches(Regex("\\d{13,19}"))) {
            return false
        }

        // Алгоритм Луна для проверки валидности номера кредитной карты
        var sum = 0
        var isEven = false

        for (i in cardNumber.length - 1 downTo 0) {
            var digit = Character.getNumericValue(cardNumber[i])

            if (isEven) {
                digit *= 2
                if (digit > 9) {
                    digit -= 9
                }
            }

            sum += digit
            isEven = !isEven
        }

        return sum % 10 == 0
    }

    private fun isCreditCardItem(text: String): Boolean = CREDIT_CARD_PATTERN.matcher(text).find()
    private fun isPassportItem(text: String): Boolean = PASSPORT_RF_PATTERN.matcher(text).find()
    private fun isEmailItem(text: String): Boolean = EMAIL_PATTERN.matcher(text).find()
    private fun isPhoneItem(text: String): Boolean = PHONE_RF_PATTERN.matcher(text).find()
    private fun isDateItem(text: String): Boolean = DATE_PATTERN.matcher(text).find()
    private fun isInnItem(text: String): Boolean = INN_PATTERN.matcher(text).find()
    private fun isSnilsItem(text: String): Boolean = SNILS_PATTERN.matcher(text).find()
    private fun isKppItem(text: String): Boolean = KPP_PATTERN.matcher(text).find()
}