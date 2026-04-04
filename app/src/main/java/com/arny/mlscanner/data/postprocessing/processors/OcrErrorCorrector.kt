package com.arny.mlscanner.data.postprocessing

class OcrErrorCorrector : TextProcessor {

    override val name: String = "OCR Error Corrector"

    private val charSubstitutions = mapOf(
        "  " to " ",
        " ," to ",",
        " ." to ".",
        ",," to ","
    )

    private val cyrillicLatinConfusions = mapOf(
        'A' to 'А', 'B' to 'В', 'C' to 'С', 'E' to 'Е',
        'H' to 'Н', 'K' to 'К', 'M' to 'М', 'O' to 'О',
        'P' to 'Р', 'T' to 'Т', 'X' to 'Х',
        'a' to 'а', 'c' to 'с', 'e' to 'е', 'o' to 'о',
        'p' to 'р', 'x' to 'х', 'y' to 'у'
    )

    private val latinCyrillicConfusions = cyrillicLatinConfusions.entries.associate { (k, v) -> v to k }

    override fun process(text: String, context: DocumentContext): ProcessorResult {
        var result = text
        val changes = mutableListOf<TextChange>()

        result = fixMixedAlphabets(result, changes)
        result = fixDigitLetterConfusion(result, changes)
        result = applyCharSubstitutions(result, changes)

        return ProcessorResult(result, changes)
    }

    private fun fixMixedAlphabets(text: String, changes: MutableList<TextChange>): String {
        val wordRegex = Regex("[\\p{L}\\d]+")
        val result = StringBuilder()
        var lastEnd = 0

        for (match in wordRegex.findAll(text)) {
            result.append(text.substring(lastEnd, match.range.first))
            val word = match.value
            val fixed = fixMixedWord(word)

            if (fixed != word) {
                changes.add(TextChange(name, word, fixed, match.range.first, 0.9f, "Смешанный алфавит"))
            }
            result.append(fixed)
            lastEnd = match.range.last + 1
        }

        result.append(text.substring(lastEnd))
        return result.toString()
    }

    private fun fixMixedWord(word: String): String {
        val cyrCount = word.count { it.isCyrillic() }
        val latCount = word.count { it.isLatinLetter() }

        if (cyrCount == 0 || latCount == 0) return word

        val dominantIsCyrillic = cyrCount >= latCount

        return buildString {
            for (ch in word) {
                if (dominantIsCyrillic && ch.isLatinLetter()) {
                    append(cyrillicLatinConfusions[ch] ?: ch)
                } else if (!dominantIsCyrillic && ch.isCyrillic()) {
                    append(latinCyrillicConfusions[ch] ?: ch)
                } else {
                    append(ch)
                }
            }
        }
    }

    private fun fixDigitLetterConfusion(text: String, changes: MutableList<TextChange>): String {
        val chars = text.toCharArray()
        var modified = false

        for (i in chars.indices) {
            val ch = chars[i]
            val prevIsLetter = i > 0 && chars[i - 1].isLetter()
            val nextIsLetter = i < chars.size - 1 && chars[i + 1].isLetter()
            val prevIsDigit = i > 0 && chars[i - 1].isDigit()
            val nextIsDigit = i < chars.size - 1 && chars[i + 1].isDigit()

            if (ch.isDigit() && prevIsLetter && nextIsLetter) {
                val replacement = digitToLetterInContext(ch, chars, i)
                if (replacement != null && replacement != ch) {
                    changes.add(TextChange(name, ch.toString(), replacement.toString(), i, 0.85f, "Цифра внутри слова"))
                    chars[i] = replacement
                    modified = true
                }
            }

            if (ch.isLetter() && prevIsDigit && nextIsDigit) {
                val replacement = letterToDigitInContext(ch)
                if (replacement != null && replacement != ch) {
                    changes.add(TextChange(name, ch.toString(), replacement.toString(), i, 0.85f, "Буква внутри числа"))
                    chars[i] = replacement
                    modified = true
                }
            }
        }

        return if (modified) String(chars) else text
    }

    private fun digitToLetterInContext(digit: Char, chars: CharArray, index: Int): Char? {
        val nearbyChars = chars.filterIndexed { i, _ -> i in (index - 3)..(index + 3) && i != index }
        val isCyrContext = nearbyChars.count { it.isCyrillic() } > nearbyChars.count { it.isLatinLetter() }

        return when (digit) {
            '0' -> if (isCyrContext) 'О' else 'O'
            '3' -> if (isCyrContext) 'З' else null
            '1' -> if (isCyrContext) null else 'l'
            else -> null
        }
    }

    private fun letterToDigitInContext(letter: Char): Char? {
        return when (letter) {
            'О', 'о', 'O', 'o' -> '0'
            'З', 'з' -> '3'
            'I', 'l', 'і' -> '1'
            'S', 's' -> '5'
            'B' -> '8'
            'б' -> '6'
            else -> null
        }
    }

    private fun applyCharSubstitutions(text: String, changes: MutableList<TextChange>): String {
        var result = text
        for ((from, to) in charSubstitutions) {
            var index = result.indexOf(from)
            while (index >= 0) {
                changes.add(TextChange(name, from, to, index, 0.7f, "OCR-артефакт"))
                result = result.replaceRange(index, index + from.length, to)
                index = result.indexOf(from, index + to.length)
            }
        }
        return result
    }

    private fun Char.isCyrillic(): Boolean = this in '\u0400'..'\u04FF' || this in '\u0500'..'\u052F'
    private fun Char.isLatinLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'
}