package com.arny.mlscanner.data.postprocessing

class PunctuationFixer : TextProcessor {

    override val name: String = "Punctuation Fixer"

    override fun process(text: String, context: DocumentContext): ProcessorResult {
        var result = text
        val changes = mutableListOf<TextChange>()

        result = Regex(" +([,\\.;:!?])").replace(result) { match ->
            changes.add(TextChange(name, match.value, match.groupValues[1], match.range.first, 0.8f, "Пробел перед знаком"))
            match.groupValues[1]
        }

        result = Regex("([,;:!?])([\\p{L}\\d])").replace(result) { match ->
            val replacement = "${match.groupValues[1]} ${match.groupValues[2]}"
            changes.add(TextChange(name, match.value, replacement, match.range.first, 0.8f, "Пробел после знака"))
            replacement
        }

        if (context.primaryLanguage == "ru") {
            result = fixRussianQuotes(result, changes)
        }

        result = Regex("(\\p{L}) - (\\p{L})").replace(result) { match ->
            val replacement = "${match.groupValues[1]} — ${match.groupValues[2]}"
            changes.add(TextChange(name, match.value, replacement, match.range.first, 0.7f, "Дефис → тире"))
            replacement
        }

        return ProcessorResult(result, changes)
    }

    private fun fixRussianQuotes(text: String, changes: MutableList<TextChange>): String {
        var isOpen = true
        val result = StringBuilder()

        for ((i, ch) in text.withIndex()) {
            if (ch == '"') {
                val replacement = if (isOpen) '«' else '»'
                changes.add(TextChange(name, "\"", replacement.toString(), i, 0.8f, "Типографские кавычки"))
                result.append(replacement)
                isOpen = !isOpen
            } else {
                result.append(ch)
            }
        }

        return result.toString()
    }
}