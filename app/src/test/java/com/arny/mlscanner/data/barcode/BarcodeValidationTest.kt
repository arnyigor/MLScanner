package com.arny.mlscanner.data.barcode

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class BarcodeValidationTest {

    @Test
    @DisplayName("Валидация EAN-13: корректный штрихкод")
    fun `valid EAN-13`() {
        assertTrue(validateEan("4600000000003"))
        assertTrue(validateEan("5901234123457"))
    }

    @Test
    @DisplayName("Валидация EAN-13: некорректная контрольная цифра")
    fun `invalid EAN-13 check digit`() {
        assertFalse(validateEan("4600000000001"))
        assertFalse(validateEan("5901234123450"))
    }

    @Test
    @DisplayName("Определение страны по EAN-префиксу")
    fun `country by EAN prefix`() {
        assertEquals("Россия", getCountryByPrefix("460"))
        assertEquals("Германия", getCountryByPrefix("400"))
        assertEquals("Китай", getCountryByPrefix("690"))
        assertNull(getCountryByPrefix("999"))
    }

    @Test
    @DisplayName("Валидация EAN-8")
    fun `valid EAN-8`() {
        assertTrue(validateEan("96385074"))
    }

    private fun validateEan(code: String): Boolean {
        val digits = code.filter { it.isDigit() }
        if (digits.length !in listOf(8, 12, 13, 14)) return false
        val check = digits.last().digitToInt()
        val payload = digits.dropLast(1)
        var sum = 0
        for (i in payload.indices) {
            val d = payload[payload.length - 1 - i].digitToInt()
            sum += if (i % 2 == 0) d * 3 else d
        }
        return (10 - (sum % 10)) % 10 == check
    }

    private fun getCountryByPrefix(prefix: String): String? {
        val p = prefix.take(3).toIntOrNull() ?: return null
        return when (p) {
            in 460..469 -> "Россия"
            in 400..440 -> "Германия"
            in 300..379 -> "Франция"
            in 690..695 -> "Китай"
            in 0..19, in 30..39, in 60..139 -> "США/Канада"
            in 500..509 -> "Великобритания"
            else -> null
        }
    }
}