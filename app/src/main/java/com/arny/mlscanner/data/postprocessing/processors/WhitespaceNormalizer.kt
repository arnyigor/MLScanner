package com.arny.mlscanner.data.postprocessing

class WhitespaceNormalizer : TextProcessor {

    override val name: String = "Whitespace Normalizer"

    override fun process(text: String, context: DocumentContext): ProcessorResult {
        val changes = mutableListOf<TextChange>()
        var result = text

        val specialSpaces = Regex("[\\u00A0\\u2000-\\u200B\\u202F\\u205F\\u3000]")
        result = specialSpaces.replace(result, " ")

        val multipleSpaces = Regex("(?<=\\S) {2,}")
        result = multipleSpaces.replace(result) { match ->
            changes.add(TextChange(name, match.value, " ", match.range.first, 1.0f, "Множественные пробелы"))
            " "
        }

        result = result.lines().joinToString("\n") { it.trimEnd() }

        return ProcessorResult(result, changes)
    }
}