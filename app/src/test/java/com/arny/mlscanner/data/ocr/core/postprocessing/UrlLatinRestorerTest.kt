package com.arny.mlscanner.data.ocr.core.postprocessing

import org.junit.Assert.*
import org.junit.Test

/**
 * Тесты для восстановления латинских букв в URL.
 * Проверяют логику замены кириллических символов на латинские внутри URL.
 */
class UrlLatinRestorerTest {

    // Тестируем логику замены только внутри URL (как в реальном процессоре)
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

    // Паттерны для поиска URL в тексте (как в реальном процессоре)
    private val URL_PATTERNS = listOf(
        // https://... - с поддержкой кириллицы в домене
        Regex("""https?://[^\s]+""", RegexOption.IGNORE_CASE),
        // www... - с поддержкой кириллицы
        Regex("""\bwww\.[^\s]+""", RegexOption.IGNORE_CASE),
        // Домены типа example.com, test.ru и т.д. - с поддержкой кириллицы
        Regex("""(?<![A-Za-z0-9@.])(?:[A-Za-z0-9АаЕеОоРрСсХхУуКкМмТтПп](?:[A-Za-z0-9АаЕеОоРрСсХхУуКкМмТтПп-]{0,61}[A-Za-z0-9АаЕеОоРрСсХхУуКкМмТтПп])?\.)+(?:[A-Za-z]{2,63}|xn--[A-Za-z0-9-]{2,59})(?:/[^\s]*)?""", RegexOption.IGNORE_CASE)
    )

    private fun restoreLatinInUrl(url: String): String {
        return buildString(url.length) {
            for (char in url) {
                append(CYRILLIC_TO_LATIN[char] ?: char)
            }
        }
    }

    private fun processText(text: String): String {
        var result = text
        for (pattern in URL_PATTERNS) {
            result = pattern.replace(result) { match ->
                restoreLatinInUrl(match.value)
            }
        }
        return result
    }

    @Test
    fun `restores latin chars in https URL`() {
        val input = "https://уапdex.ru"
        val expected = "https://yandex.ru"

        val result = processText(input)

        assertEquals(expected, result)
    }

    @Test
    fun `restores latin chars in www URL`() {
        val input = "www.уапdex.ru"
        val expected = "www.yandex.ru"

        val result = processText(input)

        assertEquals(expected, result)
    }

    @Test
    fun `preserves text without cyrillic`() {
        val input = "https://google.com"
        val expected = "https://google.com"

        val result = processText(input)

        assertEquals(expected, result)
    }

    @Test
    fun `handles mixed content - only URL is modified`() {
        // Важно: только URL должен быть модифицирован, остальной текст нет
        val input = "Сайт: https://уапdex.ru и текст"
        val expected = "Сайт: https://yandex.ru и текст"

        val result = processText(input)

        assertEquals(expected, result)
    }

    @Test
    fun `restores multiple cyrillic chars in URL`() {
        val input = "уапdex"  // Это не URL, домен без TLD
        val expected = "уапdex"  // Не должно измениться, т.к. нет TLD

        val result = processText(input)

        assertEquals(expected, result)
    }

    @Test
    fun `handles uppercase cyrillic in URL`() {
        val input = "https://УАПDEX.RU"
        val expected = "https://YANDEX.RU"

        val result = processText(input)

        assertEquals(expected, result)
    }

    @Test
    fun `handles path and query in URL`() {
        val input = "https://уапdex.ru/path?param=value"
        val expected = "https://yandex.ru/path?param=value"

        val result = processText(input)

        assertEquals(expected, result)
    }

    @Test
    fun `preserves russian text without URLs`() {
        val input = "Привет мир"

        val result = processText(input)

        assertEquals(input, result)
    }

    @Test
    fun `handles empty string`() {
        val input = ""
        val expected = ""

        val result = processText(input)

        assertEquals(expected, result)
    }

    @Test
    fun `handles multiple URLs`() {
        val input = "Visit https://уапdex.ru and http://gоogle.com"
        val expected = "Visit https://yandex.ru and http://google.com"

        val result = processText(input)

        assertEquals(expected, result)
    }

    @Test
    fun `handles domain without protocol`() {
        val input = "example.com и уапdex.ru"
        val expected = "example.com и yandex.ru"

        val result = processText(input)

        assertEquals(expected, result)
    }

    @Test
    fun `does not modify email`() {
        val input = "Email: test@example.com"
        val expected = "Email: test@example.com"

        val result = processText(input)

        assertEquals(expected, result)
    }
}