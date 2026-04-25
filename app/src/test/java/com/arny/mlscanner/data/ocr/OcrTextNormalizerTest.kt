package com.arny.mlscanner.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrTextNormalizerTest {

    @Test
    fun normalizesHomeworkTextAndKeepsOrder() {
        val raw = """
            29 января

            Письмо: стр.41 «Тренажёр», прописать
            слова, поставить ударение, подчеркнуть
            зелёной ручкой опасные места, разделить
            слова для переноса.
            Математика: стр. 7 - Петерсон № 1(в
            кружочке)+ счет двойками до 40.
            Азбука: стр. 63. Выучить скороговорки
            Окр мир:
            https://yandex.ru/video/preview/160421091605
            16050498
            Откуда в дом приходит электричество
        """.trimIndent()

        val actual = OcrTextNormalizer.normalize(raw)

        assertContainsInOrder(
            actual,
            listOf(
                "29 января",
                "Письмо",
                "Тренажер",
                "прописать слова",
                "поставить ударение",
                "Математика",
                "Петерсон",
                "Азбука",
                "скороговорки",
                "Окр мир",
                "yandex.ru",
                "16042109160516050498",
                "Откуда",
                "электричество"
            )
        )
    }

    @Test
    fun joinsBrokenUrlDigits() {
        val raw = """
            https://yandex.ru/video/preview/160421091605
            16050498
        """.trimIndent()

        val actual = OcrTextNormalizer.normalize(raw)

        assertTrue(actual.contains("https://yandex.ru/video/preview/16042109160516050498"))
    }

    @Test
    fun normalizesCommonNumericOcrConfusions() {
        val raw = "Receipt N 1\nCode 2O4 and 3l5"

        val actual = OcrTextNormalizer.normalize(raw)

        assertTrue(actual.contains("№ 1"))
        assertTrue(actual.contains("204"))
        assertTrue(actual.contains("315"))
    }

    @Test
    fun cleansExtraWhitespace() {
        val raw = "Письмо:   стр.  41   Тренажёр"

        val actual = OcrTextNormalizer.normalize(raw)

        assertEquals("Письмо: стр. 41 Тренажёр", actual)
    }

    @Test
    fun fixesNumberSymbols() {
        val raw = "Математика: N 1 и No 2"

        val actual = OcrTextNormalizer.normalize(raw)

        assertTrue(actual.contains("№ 1"))
        assertTrue(actual.contains("№ 2"))
    }

    private fun assertContainsInOrder(text: String, anchors: List<String>) {
        val normalized = normalizeLoose(text)
        var fromIndex = 0

        for (anchor in anchors) {
            val needle = normalizeLoose(anchor)
            val index = normalized.indexOf(needle, fromIndex)

            assertTrue(
                """
                Missing anchor or wrong order: "$anchor"

                Text:
                $text

                Normalized:
                $normalized
                """.trimIndent(),
                index >= 0
            )

            fromIndex = index + needle.length
        }
    }

    private fun normalizeLoose(value: String): String {
        return value
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("""(?<=\d)\s+(?=\d)"""), "")
            .replace(Regex("""[«»"'`,.;:!?()\[\]{}№+\-—–]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
