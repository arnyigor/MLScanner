package com.arny.mlscanner.data.ocr

object OcrTextNormalizer {

    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""

        var text = raw
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("\"", "\u00AB")
            .replace("<<", "\u00AB")
            .replace(">>", "\u00BB")

        text = normalizeUrl(text)

        val lines = text
            .lines()
            .map { cleanLine(it) }
            .filter { it.isNotBlank() }

        text = joinWrappedLines(lines).joinToString("\n")
        text = restoreStructuralBreaks(text)
        text = fixCommonConfusions(text)

        return text
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun normalizeUrl(text: String): String {
        return text
            .replace(Regex("""(?i)\b(https?)\s*:\s*/\s*/""")) { match ->
                "${match.groupValues[1].lowercase()}://"
            }
            .replace(Regex("""(?i)(https?://[^\s.]+)\s*\.\s*([A-Za-z0-9-]+)"""), "$1.$2")
            .replace(Regex("""(?i)(https?://[^\s/]+)/\s+"""), "$1/")
            .replace(Regex("""(?<=\d)\s*\n\s*(?=\d)"""), "")
    }

    private fun cleanLine(line: String): String {
        return line
            .trim()
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex("""([\u00AB(])\s+"""), "$1")
            .replace(Regex("""\s+([\u00BB)])"""), "$1")
    }

    private fun joinWrappedLines(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()

        val result = mutableListOf<String>()

        for (line in lines) {
            if (result.isEmpty()) {
                result += line
                continue
            }

            val previous = result.last()
            val newLine =
                isDateHeader(line) ||
                    isLabeledLine(line) ||
                    isStructuredDocumentLine(line) ||
                    isUrl(line) ||
                    previous.endsWith(".") ||
                    previous.endsWith(":") ||
                    previous.endsWith("!") ||
                    previous.endsWith("?") ||
                    isUrl(previous)

            if (newLine) {
                result += line
            } else {
                result[result.lastIndex] = previous + " " + line
            }
        }

        return result
    }

    private fun restoreStructuralBreaks(text: String): String {
        return text
            .replace(Regex("""\s+(?=[\p{L}\p{N}][\p{L}\p{N}\s]{1,40}:\s*)"""), "\n")
            .replace(Regex("""([^\n:]{1,80}:\s*)\s+(?=https?://)"""), "$1\n")
            .replace(Regex("""(https?://\S+)\s+(?=\p{Lu}|\d)"""), "$1\n")
    }

    private fun fixCommonConfusions(text: String): String {
        return text
            .replace(Regex("""(?<=\d)l(?=\d)"""), "1")
            .replace(Regex("""(?<=\d)O(?=\d)"""), "0")
            .replace(Regex("""\bN\s*([0-9])"""), "\u2116 $1")
            .replace(Regex("""\bNo\s*([0-9])"""), "\u2116 $1")
    }

    private fun isDateHeader(line: String): Boolean {
        return line.matches(Regex("""\d{1,2}\s+[\p{L}]{3,}""", RegexOption.IGNORE_CASE))
    }

    private fun isLabeledLine(line: String): Boolean {
        return line.matches(Regex("""^[\p{L}\p{N}][\p{L}\p{N}\s]{1,40}:\s*.*"""))
    }

    private fun isStructuredDocumentLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.matches(Regex("""^\d+[a-zA-Z\p{L}]?[.)]\s+.+"""))) return true
        if (trimmed.matches(Regex("""^\d{2}\.\d{2}\.\d{4}(\s+.*)?"""))) return true
        if (trimmed.matches(Regex("""^\d{2}\s+\d{2}\s+\d{4}(\s+.*)?"""))) return true
        return trimmed.matches(Regex("""^[\p{Lu}\d]{2,}(?:[\s.'-]+[\p{Lu}\d]{1,})*$"""))
    }

    private fun isUrl(line: String): Boolean {
        return line.startsWith("http://", ignoreCase = true) ||
            line.startsWith("https://", ignoreCase = true)
    }
}
