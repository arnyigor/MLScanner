package com.arny.mlscanner.data.ocr.core

import android.util.Log

/**
 * Селектор лучшего результата из кандидатов.
 */
class OcrResultSelector : IOcrResultSelector {
    
    companion object {
        private const val TAG = "OcrResultSelector"
    }
    
    override fun selectBest(
        candidates: List<OcrCandidate>,
        request: OcrRequest
    ): OcrFinalResult {
        if (candidates.isEmpty()) {
            Log.w(TAG, "No candidates to select from")
            return OcrFinalResult(
                selectedCandidate = createEmptyCandidate(),
                allCandidates = emptyList(),
                strategy = "none",
                fallbackUsed = false,
                warnings = listOf("No recognition results")
            )
        }
        
        if (candidates.size == 1) {
            return OcrFinalResult(
                selectedCandidate = candidates.first(),
                allCandidates = candidates,
                strategy = "single",
                fallbackUsed = false
            )
        }
        
        // Сортируем кандидатов по качеству
        val sorted = candidates.sortedByDescending { candidate ->
            calculateCandidateScore(candidate, request)
        }
        
        val best = sorted.first()
        val warnings = mutableListOf<String>()
        
        // Проверяем, использовался ли fallback
        val primaryEngine = candidates.firstOrNull()?.engineId
        val fallbackUsed = best.engineId != primaryEngine
        
        if (fallbackUsed) {
            warnings.add("Fallback to ${best.engineName} (primary: $primaryEngine)")
        }
        
        // Проверяем качество лучшего результата
        if (best.quality?.isSuspicious == true) {
            warnings.add("Best result has suspicious quality: ${best.quality?.suspiciousReasons?.joinToString()}")
        }
        
        Log.d(TAG, "Selected: ${best.engineName} (score: ${"%.2f".format(best.quality?.score ?: 0f)})")
        
        return OcrFinalResult(
            selectedCandidate = best,
            allCandidates = sorted,
            strategy = "quality-based",
            fallbackUsed = fallbackUsed,
            warnings = warnings
        )
    }
    
    /**
     * Вычисляет общий score кандидата для сравнения.
     */
    private fun calculateCandidateScore(candidate: OcrCandidate, request: OcrRequest): Float {
        val quality = candidate.quality ?: return 0f
        
        var score = quality.score
        
        // Бонус за высокий confidence
        if (quality.confidence != null && quality.confidence > 0.8f) {
            score += 0.1f
        }
        
        // Бонус за большое количество слов
        if (quality.wordCount >= 10) {
            score += 0.05f
        }
        
        // Штраф за подозрительные причины
        score -= quality.suspiciousReasons.size * 0.05f
        
        // Штраф за высокий garbage ratio
        if (quality.garbageRatio > 0.1f) {
            score -= quality.garbageRatio * 0.2f
        }
        
        return score.coerceIn(0f, 1f)
    }
    
    private fun createEmptyCandidate(): OcrCandidate {
        return OcrCandidate(
            engineId = "none",
            engineName = "None",
            rawText = "",
            blocks = emptyList(),
            confidence = null,
            processingTimeMs = 0,
            processedText = "",
            quality = null
        )
    }
}
