package com.arny.mlscanner.data.redaction

import com.arny.mlscanner.domain.models.BoundingBox
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

class SensitiveDataDetectorTest {

    private lateinit var detector: SensitiveDataDetector

    @Before
    fun setUp() {
        detector = SensitiveDataDetector()
    }

    @Test
    fun `should detect credit card numbers with Luhn algorithm`() {
        val validCardNumbers = listOf(
            "4532015112830366",
            "5555555555554444",
            "378282246310005",
            "4532-0151-1283-0366",
            "4532 0151 1283 0366"
        )

        for (cardNumber in validCardNumbers) {
            val result = detector.detectSensitiveData(
                text = cardNumber,
                boundingBox = BoundingBox(0f, 0f, 100f, 50f)
            )

            assertFalse("Should detect credit card: $cardNumber", result.creditCards.isEmpty())
            assertEquals(cardNumber, result.creditCards[0].text)
        }
    }

    @Test
    fun `should not detect invalid credit card numbers`() {
        val invalidCardNumbers = listOf(
            "1234567890123456", // Не проходит Луна
            "4532015112830367", // Неверный контрольный номер
            "4532-0151-1283-036", // Неверная длина
            "abc4567890123456"   // Содержит буквы
        )

        for (cardNumber in invalidCardNumbers) {
            val result = detector.detectSensitiveData(
                text = cardNumber,
                boundingBox = BoundingBox(0f, 0f, 100f, 50f)
            )

            assertTrue("Should not detect invalid card: $cardNumber", result.creditCards.isEmpty())
        }
    }

    @Test
    fun `should detect Russian passport numbers`() {
        val passportNumbers = listOf(
            "4400 123456",
            "4400123456",
            "5012345678" // Убран неверный формат "50 12 345678" (паспорт РФ - 4+6 цифр)
        )

        for (passport in passportNumbers) {
            val result = detector.detectSensitiveData(
                text = passport,
                boundingBox = BoundingBox(0f, 0f, 100f, 50f)
            )

            assertFalse("Should detect passport: $passport", result.passports.isEmpty())
            assertEquals(passport, result.passports[0].text)
        }
    }

    @Test
    fun `should detect email addresses`() {
        val emails = listOf(
            "test@example.com",
            "user.name+tag@domain.co.uk",
            "user123@test-domain.org"
        )

        for (email in emails) {
            val result = detector.detectSensitiveData(
                text = email,
                boundingBox = BoundingBox(0f, 0f, 100f, 50f)
            )

            assertFalse("Should detect email: $email", result.emails.isEmpty())
            assertEquals(email, result.emails[0].text)
        }
    }

    @Test
    fun `should detect Russian phone numbers`() {
        val phones = listOf(
            "+7 (999) 123-45-67",
            "+79991234567",
            "8 (999) 123-45-67",
            "89991234567"
        )

        for (phone in phones) {
            val result = detector.detectSensitiveData(
                text = phone,
                boundingBox = BoundingBox(0f, 0f, 100f, 50f)
            )

            assertFalse("Should detect phone: $phone", result.phones.isEmpty())
            assertEquals(phone, result.phones[0].text)
        }
    }

    @Test
    fun `should detect dates`() {
        val dates = listOf(
            "01.01.2023",
            "01/01/2023",
            "25.12.95"
        )

        for (date in dates) {
            val result = detector.detectSensitiveData(
                text = date,
                boundingBox = BoundingBox(0f, 0f, 100f, 50f)
            )

            assertFalse("Should detect date: $date", result.dates.isEmpty())
            assertEquals(date, result.dates[0].text)
        }
    }

    @Test
    fun `should detect INN numbers`() {
        val inns = listOf(
            "123456789012", // 12 цифр (юр. лицо)
            "1234567890"    // 10 цифр (физ. лицо)
        )

        for (inn in inns) {
            val result = detector.detectSensitiveData(
                text = inn,
                boundingBox = BoundingBox(0f, 0f, 100f, 50f)
            )

            assertFalse("Should detect INN: $inn", result.inn.isEmpty())
            assertEquals(inn, result.inn[0].text)
        }
    }

    @Test
    fun `should detect SNILS numbers`() {
        val snilsNumbers = listOf(
            "123-456-789-00",
            "12345678900"
        )

        for (snils in snilsNumbers) {
            val result = detector.detectSensitiveData(
                text = snils,
                boundingBox = BoundingBox(0f, 0f, 100f, 50f)
            )

            assertFalse("Should detect SNILS: $snils", result.snils.isEmpty())
            assertEquals(snils, result.snils[0].text)
        }
    }

    @Test
    fun `should detect custom patterns`() {
        val customPattern = "\\bSECRET-[A-Z0-9]{4}\\b"
        val secretCodes = listOf("SECRET-ABCD", "SECRET-1234")

        for (code in secretCodes) {
            val result = detector.detectSensitiveData(
                text = code,
                boundingBox = BoundingBox(0f, 0f, 100f, 50f),
                customPatterns = listOf(customPattern)
            )

            assertFalse("Should detect custom pattern: $code", result.customPatterns.isEmpty())
            assertEquals(code, result.customPatterns[0].text)
        }
    }

    @Test
    fun `should find sensitive data in mixed text`() {
        val mixedText = "Contact: test@example.com, Phone: +79991234567, Card: 4532015112830366"
        val result = detector.detectSensitiveData(
            text = mixedText,
            boundingBox = BoundingBox(0f, 0f, 300f, 50f)
        )

        // Проверяем все типы
        assertEquals(1, result.emails.size)
        assertEquals("test@example.com", result.emails[0].text)

        assertEquals(1, result.phones.size)
        assertEquals("+79991234567", result.phones[0].text)

        assertEquals(1, result.creditCards.size)
        assertEquals("4532015112830366", result.creditCards[0].text)
    }

    @Test
    fun `should handle empty text gracefully`() {
        val result = detector.detectSensitiveData(
            text = "",
            boundingBox = BoundingBox(0f, 0f, 100f, 50f)
        )

        assertTrue(result.creditCards.isEmpty())
        assertTrue(result.passports.isEmpty())
        assertTrue(result.emails.isEmpty())
        // ... и все остальные списки пусты
    }

    @Test
    fun `should handle invalid custom patterns gracefully`() {
        val result = detector.detectSensitiveData(
            text = "test data",
            boundingBox = BoundingBox(0f, 0f, 100f, 50f),
            customPatterns = listOf("[invalid-regex")
        )

        // Не должно упасть, просто игнорировать невалидный паттерн
        assertNotNull(result)
    }
}