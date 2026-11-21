package com.arny.mlscanner.data.ocr.core.strategy

import android.util.Log
import com.arny.mlscanner.data.ocr.core.IOcrQualityAnalyzer
import com.arny.mlscanner.data.ocr.core.IOcrStrategy
import com.arny.mlscanner.data.ocr.core.OcrCandidate
import com.arny.mlscanner.data.ocr.core.OcrEngineRegistry
import com.arny.mlscanner.data.ocr.core.OcrQualityAnalyzer
import com.arny.mlscanner.data.ocr.core.PreparedOcrRequest

/**
 * Базовая стратегия: запускает один движок.
 */
abstract class BaseOcrStrategy(
    protected val engineRegistry: OcrEngineRegistry
) : IOcrStrategy {
    
    companion object {
        private const val TAG = "BaseOcrStrategy"
    }
    
    protected suspend fun recognizeWithEngine(
        engineId: String,
        request: PreparedOcrRequest
    ): OcrCandidate? {
        val engine = engineRegistry.getEngine(engineId)
        
        if (engine == null) {
            Log.w(TAG, "Engine $engineId not found")
            return null
        }
        
        if (!engine.isReady()) {
            Log.w(TAG, "Engine $engineId not ready")
            return null
        }
        
        return try {
            val startTime = System.currentTimeMillis()
            val candidate = engine.recognize(request)
            val elapsed = System.currentTimeMillis() - startTime
            
            Log.d(TAG, "Engine $engineId: ${candidate.rawText.length} chars in ${elapsed}ms")
            
            candidate.copy(processingTimeMs = elapsed)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Отмена сканирования — не «слабый результат»: пробрасываем,
            // иначе стратегия гоняет резервный движок после отмены.
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Engine $engineId failed", e)
            null
        }
    }
}

/**
 * Стратегия: только Google ML Kit.
 */
class GoogleMLKitOnlyStrategy(
    engineRegistry: OcrEngineRegistry
) : BaseOcrStrategy(engineRegistry) {
    
    override val id: String = "google_mlkit_only"
    override val name: String = "Google ML Kit Only"
    
    override suspend fun recognize(request: PreparedOcrRequest): List<OcrCandidate> {
        val candidate = recognizeWithEngine("google_mlkit", request)
        return listOfNotNull(candidate)
    }
}

/**
 * Стратегия: только Tesseract.
 */
class TesseractOnlyStrategy(
    engineRegistry: OcrEngineRegistry
) : BaseOcrStrategy(engineRegistry) {
    
    override val id: String = "tesseract_only"
    override val name: String = "Tesseract Only"
    
    override suspend fun recognize(request: PreparedOcrRequest): List<OcrCandidate> {
        val candidate = recognizeWithEngine("tesseract", request)
        return listOfNotNull(candidate)
    }
}

/**
 * Стратегия: только Huawei ML Kit.
 */
class HuaweiMLKitOnlyStrategy(
    engineRegistry: OcrEngineRegistry
) : BaseOcrStrategy(engineRegistry) {
    
    override val id: String = "huawei_mlkit_only"
    override val name: String = "Huawei ML Kit Only"
    
    override suspend fun recognize(request: PreparedOcrRequest): List<OcrCandidate> {
        val candidate = recognizeWithEngine("huawei_mlkit", request)
        return listOfNotNull(candidate)
    }
}

/**
 * Стратегия: быстрая (приоритет скорости).
 */
class FastStrategy(
    engineRegistry: OcrEngineRegistry
) : BaseOcrStrategy(engineRegistry) {
    
    override val id: String = "fast"
    override val name: String = "Fast (ML Kit)"
    
    override suspend fun recognize(request: PreparedOcrRequest): List<OcrCandidate> {
        // Сначала пробуем самый быстрый движок
        val fastestEngine = engineRegistry.getFastestEngine()
        
        if (fastestEngine != null) {
            val candidate = recognizeWithEngine(fastestEngine.id, request)
            if (candidate != null) {
                return listOf(candidate)
            }
        }
        
        // Fallback на Google ML Kit
        val candidate = recognizeWithEngine("google_mlkit", request)
        return listOfNotNull(candidate)
    }
}

/**
 * Стратегия: точная (приоритет качества).
 */
class AccurateStrategy(
    engineRegistry: OcrEngineRegistry
) : BaseOcrStrategy(engineRegistry) {
    
    override val id: String = "accurate"
    override val name: String = "Accurate (Tesseract Best)"
    
    override suspend fun recognize(request: PreparedOcrRequest): List<OcrCandidate> {
        // Используем самый точный движок
        val accurateEngine = engineRegistry.getMostAccurateEngine()
        
        if (accurateEngine != null) {
            val candidate = recognizeWithEngine(accurateEngine.id, request)
            if (candidate != null) {
                return listOf(candidate)
            }
        }
        
        // Fallback на Tesseract
        val candidate = recognizeWithEngine("tesseract", request)
        return listOfNotNull(candidate)
    }
}

/**
 * Стратегия: умное распознавание.
 *
 * Huawei ML Kit — основной движок (лучше читает кириллицу), Tesseract `rus+eng` —
 * резерв, если результат Huawei слабый. "Слабый" решает [OcrQualityAnalyzer],
 * а не количество слов: короткий, но чистый текст остановки не требует.
 * Google ML Kit в обычной цепочке не участвует (только по явной политике).
 */
class HybridStrategy(
    engineRegistry: OcrEngineRegistry,
    private val qualityAnalyzer: IOcrQualityAnalyzer = OcrQualityAnalyzer()
) : BaseOcrStrategy(engineRegistry) {

    companion object {
        private const val TAG = "HybridStrategy"

        /** Меньше этого числа слов считаем, что Huawei почти ничего не прочитал. */
        private const val MIN_WORDS = 2

        /** Доля "мусорных" символов, после которой результат считаем нечитаемым. */
        private const val MAX_GARBAGE_RATIO = 0.2f
    }

    override val id: String = "hybrid"
    override val name: String = "Smart (Huawei → Tesseract)"

    override suspend fun recognize(request: PreparedOcrRequest): List<OcrCandidate> {
        val candidates = mutableListOf<OcrCandidate>()

        // 1. Основной движок: Huawei ML Kit.
        val huaweiCandidate = recognizeWithEngine("huawei_mlkit", request)
        if (huaweiCandidate != null) {
            candidates.add(huaweiCandidate)
            if (isReadable(huaweiCandidate, request)) {
                return candidates
            }
            Log.d(TAG, "Huawei result is weak, falling back to Tesseract")
        }

        // 2. Резерв: Tesseract (rus+eng). Слабый результат Huawei остаётся кандидатом —
        // если Tesseract ничего не даст, покажем хотя бы частичный текст.
        val tessCandidate = recognizeWithEngine("tesseract", request)
        if (tessCandidate != null) {
            candidates.add(tessCandidate)
        }

        return candidates
    }

    /** Годится ли результат, чтобы не гонять резервный движок. */
    private fun isReadable(candidate: OcrCandidate, request: PreparedOcrRequest): Boolean {
        val text = candidate.rawText.trim()
        if (text.isEmpty()) return false

        val quality = qualityAnalyzer.analyze(candidate, text, request.originalRequest)
        return quality.wordCount >= MIN_WORDS && quality.garbageRatio <= MAX_GARBAGE_RATIO
    }
}

/**
 * Стратегия: автоматический выбор по задаче.
 */
class AutoStrategy(
    engineRegistry: OcrEngineRegistry
) : BaseOcrStrategy(engineRegistry) {
    
    override val id: String = "auto"
    override val name: String = "Auto"
    
    override suspend fun recognize(request: PreparedOcrRequest): List<OcrCandidate> {
        // Выбираем движки по типу задачи
        val engines = engineRegistry.getEnginesForTask(request.originalRequest.taskType)
        
        if (engines.isEmpty()) {
            Log.w("AutoStrategy", "No engines for task ${request.originalRequest.taskType}")
            return emptyList()
        }
        
        val candidates = mutableListOf<OcrCandidate>()
        
        // Запускаем первый подходящий движок
        for (engine in engines.take(2)) {
            val candidate = recognizeWithEngine(engine.id, request)
            if (candidate != null) {
                candidates.add(candidate)
                
                // Если результат хороший, не запускаем остальные
                if (candidate.rawText.split("\\s+".toRegex()).size >= 5) {
                    break
                }
            }
        }
        
        return candidates
    }
}
