package com.arny.mlscanner.data.ocr.core

import android.util.Log

/**
 * Центральный pipeline для OCR.
 * Оркестрирует весь процесс: preprocessing → engines → postprocessing → quality → selection.
 */
class OcrPipeline(
    private val engineRegistry: OcrEngineRegistry,
    private val strategySelector: IOcrStrategySelector,
    private val postProcessors: List<IOcrPostProcessor>,
    private val qualityAnalyzer: IOcrQualityAnalyzer,
    private val resultSelector: IOcrResultSelector
) {
    
    companion object {
        private const val TAG = "OcrPipeline"
    }
    
    /**
     * Главный метод распознавания.
     */
    suspend fun recognize(request: OcrRequest): OcrFinalResult {
        Log.d(TAG, "Starting OCR pipeline: task=${request.taskType}, " +
                "policy=${request.enginePolicy}, language=${request.language}")
        
        val startTime = System.currentTimeMillis()
        
        try {
            // 1. Выбор стратегии
            val strategy = strategySelector.select(request)
            Log.d(TAG, "Selected strategy: ${strategy.name}")
            
            // 2. Подготовка изображения (делается внутри стратегии)
            // 3. Запуск движков через стратегию
            val candidates = strategy.recognize(
                PreparedOcrRequest(
                    bitmap = request.bitmap,
                    originalRequest = request,
                    preprocessingApplied = emptyList()
                )
            )
            
            if (candidates.isEmpty()) {
                Log.w(TAG, "No candidates from strategy")
                return OcrFinalResult(
                    selectedCandidate = createEmptyCandidate(),
                    allCandidates = emptyList(),
                    strategy = strategy.id,
                    fallbackUsed = false,
                    warnings = listOf("No recognition results from any engine")
                )
            }
            
            // 4. Постобработка каждого кандидата
            val processed = candidates.map { candidate ->
                processCandidate(candidate, request)
            }
            
            // 5. Анализ качества
            val analyzed = processed.map { candidate ->
                val quality = qualityAnalyzer.analyze(candidate, candidate.processedText, request)
                candidate.copy(quality = quality)
            }
            
            // 6. Выбор лучшего результата
            val result = resultSelector.selectBest(analyzed, request)
            
            val totalTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Pipeline completed in ${totalTime}ms: " +
                    "engine=${result.selectedCandidate.engineName}, " +
                    "score=${"%.2f".format(result.quality?.score ?: 0f)}, " +
                    "words=${result.quality?.wordCount ?: 0}")
            
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "Pipeline error", e)
            return OcrFinalResult(
                selectedCandidate = createEmptyCandidate(),
                allCandidates = emptyList(),
                strategy = "error",
                fallbackUsed = false,
                warnings = listOf("Pipeline error: ${e.message}")
            )
        }
    }
    
    /**
     * Постобработка одного кандидата.
     */
    private fun processCandidate(candidate: OcrCandidate, request: OcrRequest): OcrCandidate {
        var text = candidate.rawText
        val allChanges = mutableListOf<OcrTextChange>()
        val allWarnings = mutableListOf<String>()
        
        // Применяем все подходящие постпроцессоры
        for (processor in postProcessors) {
            if (processor.supports(request)) {
                try {
                    val result = processor.process(text, request)
                    text = result.text
                    allChanges.addAll(result.changes)
                    allWarnings.addAll(result.warnings)
                    
                    if (result.changes.isNotEmpty()) {
                        Log.d(TAG, "${processor.id}: applied ${result.changes.size} changes")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Postprocessor ${processor.id} failed", e)
                    allWarnings.add("${processor.id} failed: ${e.message}")
                }
            }
        }
        
        return candidate.copy(
            processedText = text,
            metadata = candidate.metadata + mapOf(
                "postprocessing_changes" to allChanges.size.toString(),
                "postprocessing_warnings" to allWarnings.size.toString()
            )
        )
    }
    
    private fun createEmptyCandidate(): OcrCandidate {
        return OcrCandidate(
            engineId = "none",
            engineName = "None",
            rawText = "",
            blocks = emptyList(),
            confidence = null,
            processingTimeMs = 0,
            processedText = ""
        )
    }
}
