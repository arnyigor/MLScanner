package com.arny.mlscanner.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrTextSelectorTest {

    @Test
    fun prefersRawTesseractTextWhenBoxedTextHasWrongOrder() {
        val rawText = """
            29 января
            Письмо: стр.41 Тренажёр, прописать слова.
            Математика: стр. 7 - Петерсон № 1.
            Азбука: стр. 63. Выучить скороговорки
            Окр мир:
            https://yandex.ru/video/preview/16042109160516050498
            Откуда в дом приходит электричество
        """.trimIndent()

        val boxedWrongOrder = """
            поставить слова ударение подчеркнуть
            29 января
            Азбука: стр. 63
            Письмо: стр.41
            Математика: стр. 7
        """.trimIndent()

        val selected = OcrTextSelector.chooseBestText(rawText, boxedWrongOrder)

        assertEquals(rawText, selected)
    }

    @Test
    fun usesBoxedTextWhenRawTextIsBlank() {
        val boxed = "Письмо: стр.41 Тренажёр"

        val selected = OcrTextSelector.chooseBestText("", boxed)

        assertEquals(boxed, selected)
    }

    @Test
    fun usesRawTextWhenBoxedTextIsBlank() {
        val raw = "Письмо: стр.41 Тренажёр"

        val selected = OcrTextSelector.chooseBestText(raw, "")

        assertEquals(raw, selected)
    }

    @Test
    fun prefersBoxedTextWhenItHasSignificantlyMoreWords() {
        val rawText = "Письмо стр 41"

        val boxedText = """
            Письмо: стр.41 Тренажёр, прописать слова, поставить ударение,
            подчеркнуть зелёной ручкой опасные места, разделить слова для переноса.
        """.trimIndent()

        val selected = OcrTextSelector.chooseBestText(rawText, boxedText)

        assertEquals(boxedText, selected)
    }

    @Test
    fun prefersRawTextWhenWordCountIsClose() {
        val rawText = "Письмо: стр.41 Тренажёр прописать слова поставить ударение"
        val boxedText = "Письмо стр 41 Тренажёр прописать слова"

        val selected = OcrTextSelector.chooseBestText(rawText, boxedText)

        assertEquals(rawText, selected)
    }
}
