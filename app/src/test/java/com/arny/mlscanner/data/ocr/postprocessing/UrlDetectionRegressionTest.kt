package com.arny.mlscanner.data.ocr.postprocessing

import com.arny.mlscanner.domain.models.RecognizedText
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlDetectionRegressionTest {

    @Test
    fun `detect url with cyrillic confusable in host`() {
        val text = "Link:\nhttps://yaпdex.ru/video/preview/16042109160516050498"

        val urls = clickableUrls(text)

        assertEquals(listOf("https://yandex.ru/video/preview/16042109160516050498"), urls)
    }

    @Test
    fun `detect url when path separators are recognized as spaces`() {
        val text = "Link:\nhttps://yandex.ru video preview 160421091605 16050498\nNext russian words"

        val urls = clickableUrls(text)

        assertEquals(listOf("https://yandex.ru/video/preview/16042109160516050498"), urls)
    }

    @Test
    fun `join numeric url segment split by space`() {
        val text = "https://yandex.ru/video/preview/160421091605 16050498"

        val urls = clickableUrls(text)

        assertEquals(listOf("https://yandex.ru/video/preview/16042109160516050498"), urls)
    }

    @Test
    fun `do not append following words to url path`() {
        val text = "https://yandex.ru/video/preview/16042109160516050498\nNext russian words"

        val urls = clickableUrls(text)

        assertEquals(listOf("https://yandex.ru/video/preview/16042109160516050498"), urls)
    }

    @Test
    fun `do not append same line prose without url anchor`() {
        val text = "Open https://example.com next words"

        val urls = clickableUrls(text)

        assertEquals(listOf("https://example.com"), urls)
    }

    @Test
    fun `manual raw text edit refreshes detected links`() {
        val recognizedText = RecognizedText.EMPTY.updateRawText(
            "https://yandex.ru/video/preview/160421091605 16050498"
        )

        val urls = recognizedText.clickableElements.map { it.value }

        assertEquals(listOf("https://yandex.ru/video/preview/16042109160516050498"), urls)
    }

    private fun clickableUrls(text: String): List<String> {
        return TextFormatter.format(text, TextFormatter.FormatMode.RAW)
            .patterns
            .filter { it.type == PatternRecognizer.PatternType.URL }
            .let(TextFormatter::createClickableElements)
            .map { it.value }
    }
}
