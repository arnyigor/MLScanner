package com.arny.mlscanner.data.ocr.postprocessing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternRecognizerTest {

    @Test
    fun `recognize explicit https url with path and query as one url`() {
        val text = "Open https://example.com/path/to/page?utm_source=ocr&next=https://other.example/a#section"

        val urls = PatternRecognizer.recognizeUrls(text)

        assertEquals(1, urls.size)
        assertEquals(
            "https://example.com/path/to/page?utm_source=ocr&next=https://other.example/a#section",
            urls.single().value
        )
    }

    @Test
    fun `clickable url keeps explicit https value`() {
        val text = "Link https://example.com/a/b?x=1&y=2"

        val clickable = TextFormatter.format(text, TextFormatter.FormatMode.RAW)
            .patterns
            .let(TextFormatter::createClickableElements)
            .single()

        assertEquals("https://example.com/a/b?x=1&y=2", clickable.value)
        assertTrue(clickable.action is TextFormatter.ClickAction.OpenUrl)
        assertEquals(
            "https://example.com/a/b?x=1&y=2",
            (clickable.action as TextFormatter.ClickAction.OpenUrl).url
        )
    }

    @Test
    fun `normalize OCR spaces around protocol before url recognition`() {
        val text = "Link https : / / example.com / a / b"

        val clickable = TextFormatter.format(text, TextFormatter.FormatMode.RAW)
            .patterns
            .let(TextFormatter::createClickableElements)
            .single()

        assertEquals("https://example.com/a/b", clickable.value)
    }

    @Test
    fun `email is not duplicated as url`() {
        val text = "Email test@example.com"

        val patterns = PatternRecognizer.recognizeAll(text)

        assertEquals(1, patterns.size)
        assertEquals(PatternRecognizer.PatternType.EMAIL, patterns.single().type)
        assertEquals("test@example.com", patterns.single().value)
    }

    @Test
    fun `detect yandex homework url from real sample text`() {
        val text = """
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

        val clickableUrls = TextFormatter.format(text, TextFormatter.FormatMode.RAW)
            .patterns
            .filter { it.type == PatternRecognizer.PatternType.URL }
            .let(TextFormatter::createClickableElements)

        assertTrue(clickableUrls.isNotEmpty())
        assertEquals(
            "https://yandex.ru/video/preview/16042109160516050498",
            clickableUrls.single().value
        )
    }

    @Test
    fun `ignore OCR garbage that looks like unknown bare domain before real url`() {
        val text = """
            i SD.gsov os еее
            https://yandex.ru/video/preview/16042109160516050498
        """.trimIndent()

        val urls = TextFormatter.format(text, TextFormatter.FormatMode.RAW)
            .patterns
            .filter { it.type == PatternRecognizer.PatternType.URL }

        assertEquals(1, urls.size)
        assertEquals("https://yandex.ru/video/preview/16042109160516050498", urls.single().value)
    }
}
