package com.arny.mlscanner.data.postprocessing

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class TextPostProcessorTest {

    private val processor = TextPostProcessor(
        processors = listOf(
            OcrErrorCorrector(),
            LineBreakRestorer(),
            WhitespaceNormalizer(),
            PunctuationFixer()
        )
    )

    @Test
    @DisplayName("Полный pipeline обработки русского текста")
    fun `full pipeline russian text`() {
        val input = """
            Д0кумент  номер 123
            от 01 01 2024 года .
            Настоящим п0дтверждаeтся ,что
            работы выпoлнены в полном
            объёме .
        """.trimIndent()

        val result = processor.process(input, DocumentContext(primaryLanguage = "ru"))

        assertNotEquals(input, result.processedText)
        assertTrue(result.hasChanges)
        assertFalse(result.processedText.contains("  "))
        assertFalse(result.processedText.contains(" ."))
    }

    @Test
    @DisplayName("Pipeline не ломает чистый текст")
    fun `pipeline does not break clean text`() {
        val input = "Корректный текст без ошибок."
        val result = processor.process(input, DocumentContext())

        assertTrue(result.processedText.contains("Корректный"))
        assertTrue(result.processedText.contains("ошибок"))
    }

    @Test
    @DisplayName("Статистика содержит информацию об изменениях")
    fun `statistics are calculated`() {
        val input = "Д0кумент  с  ошибkами ."
        val result = processor.process(input, DocumentContext())

        assertTrue(result.statistics.totalChanges > 0)
        assertTrue(result.statistics.changesByProcessor.isNotEmpty())
    }
}