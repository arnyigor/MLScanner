package com.arny.mlscanner.data.postprocessing

class LineBreakRestorer : TextProcessor {

    override val name: String = "Line Break Restorer"

    override fun isApplicable(context: DocumentContext): Boolean {
        return context.documentType !in listOf(DocumentType.TABLE, DocumentType.RECEIPT)
    }

    override fun process(text: String, context: DocumentContext): ProcessorResult {
        val lines = text.lines()
        if (lines.size <= 1) return ProcessorResult(text)

        val changes = mutableListOf<TextChange>()
        val result = StringBuilder()
        var i = 0

        while (i < lines.size) {
            val currentLine = lines[i].trimEnd()

            if (currentLine.isBlank()) {
                result.appendLine()
                i++
                continue
            }

            if (isListItem(currentLine)) {
                result.appendLine(currentLine)
                i++
                continue
            }

            if (isHeading(currentLine, lines.getOrNull(i + 1))) {
                result.appendLine(currentLine)
                i++
                continue
            }

            val nextLine = lines.getOrNull(i + 1)?.trimStart()

            if (nextLine != null && shouldMergeLines(currentLine, nextLine)) {
                val merged = if (currentLine.endsWith("-") && nextLine.firstOrNull()?.isLowerCase() == true) {
                    changes.add(TextChange(name, "${currentLine}\n${nextLine}", "${currentLine.dropLast(1)}${nextLine}", result.length, 0.9f, "Восстановление переноса"))
                    currentLine.dropLast(1) + nextLine
                } else {
                    changes.add(TextChange(name, "${currentLine}\n${nextLine}", "${currentLine} ${nextLine}", result.length, 0.8f, "Объединение строк"))
                    "$currentLine $nextLine"
                }

                result.append(merged)
                i += 2
            } else {
                result.appendLine(currentLine)
                i++
            }
        }

        return ProcessorResult(result.toString().trimEnd(), changes)
    }

    private fun shouldMergeLines(current: String, next: String): Boolean {
        if (current.isBlank() || next.isBlank()) return false

        val lastChar = current.trimEnd().lastOrNull() ?: return false
        if (lastChar in ".!?:;") return false

        if (next.startsWith("  ") || next.startsWith("\t")) return false
        if (isListItem(next)) return false

        val firstNextChar = next.firstOrNull() ?: return false
        if (firstNextChar.isLowerCase()) return true

        if (current.endsWith("-")) return true

        val trimmed = current.trimEnd()
        if (trimmed.endsWith(",") || trimmed.endsWith(" и") || trimmed.endsWith(" или") ||
            trimmed.endsWith(" а") || trimmed.endsWith(" но")) return true

        return false
    }

    private fun isListItem(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.matches(Regex("^\\d+[.)>]\\s.*")) ||
                trimmed.matches(Regex("^[-–—•*]\\s.*")) ||
                trimmed.matches(Regex("^[а-яa-z][.)>]\\s.*"))
    }

    private fun isHeading(line: String, nextLine: String?): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < 60 && trimmed.uppercase() == trimmed && trimmed.any { it.isLetter() }) return true
        if (trimmed.length < 80 && nextLine?.isBlank() == true) return true
        return false
    }
}