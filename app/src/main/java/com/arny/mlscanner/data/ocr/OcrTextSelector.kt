package com.arny.mlscanner.data.ocr

object OcrTextSelector {

    fun chooseBestText(rawText: String, boxedText: String): String {
        if (rawText.isBlank()) return boxedText
        if (boxedText.isBlank()) return rawText

        val rawWords = countWords(rawText)
        val boxedWords = countWords(boxedText)

        return if (rawWords >= boxedWords * 0.85f) {
            rawText
        } else {
            boxedText
        }
    }

    private fun countWords(text: String): Int {
        return text.split(Regex("\\s+")).count { it.length > 1 }
    }
}
