package com.arny.mlscanner.data.ocr.core.strategy

import android.util.Log
import com.arny.mlscanner.data.ocr.core.IOcrStrategy
import com.arny.mlscanner.data.ocr.core.OcrCandidate
import com.arny.mlscanner.data.ocr.core.OcrEngineRegistry
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
 * Стратегия: гибридная (ML Kit + Tesseract fallback).
 */
class HybridStrategy(
    engineRegistry: OcrEngineRegistry
) : BaseOcrStrategy(engineRegistry) {
    
    override val id: String = "hybrid"
    override val name: String = "Hybrid (ML Kit + Tesseract)"
    
    override suspend fun recognize(request: PreparedOcrRequest): List<OcrCandidate> {
        val candidates = mutableListOf<OcrCandidate>()
        
        // 1. Сначала Google ML Kit (быстрый)
        val mlkitCandidate = recognizeWithEngine("google_mlkit", request)
        if (mlkitCandidate != null) {
            candidates.add(mlkitCandidate)
        }
        
        // 2. Если результат подозрительный или пустой, добавляем Tesseract
        val needsFallback = mlkitCandidate == null || 
                           mlkitCandidate.rawText.isBlank() ||
                           mlkitCandidate.rawText.split("\\s+".toRegex()).size < 3
        
        if (needsFallback) {
            val tessCandidate = recognizeWithEngine("tesseract", request)
            if (tessCandidate != null) {
                candidates.add(tessCandidate)
            }
        }
        
        return candidates
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
