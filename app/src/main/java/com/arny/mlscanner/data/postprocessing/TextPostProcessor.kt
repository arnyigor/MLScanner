package com.arny.mlscanner.data.postprocessing

class TextPostProcessor(
    private val processors: List<TextProcessor> = defaultProcessors(),
    private val config: PostProcessingConfig = PostProcessingConfig()
) {
    fun process(rawText: String, context: DocumentContext = DocumentContext()): PostProcessingResult {
        var text = rawText
        val changes = mutableListOf<TextChange>()

        for (processor in processors) {
            if (!processor.isApplicable(context)) continue
            val result = processor.process(text, context)
            if (result.text != text) {
                changes.addAll(result.changes)
                text = result.text
            }
        }

        return PostProcessingResult(
            originalText = rawText,
            processedText = text,
            changes = changes,
            statistics = calculateStatistics(rawText, text, changes)
        )
    }

    companion object {
        fun defaultProcessors(): List<TextProcessor> = listOf(
            OcrErrorCorrector(),
            LineBreakRestorer(),
            WhitespaceNormalizer(),
            PunctuationFixer()
        )
    }
}