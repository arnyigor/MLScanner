package com.arny.mlscanner.data.ocr.core.strategy

import com.arny.mlscanner.data.ocr.core.*

/**
 * Селектор стратегий OCR.
 * Выбирает подходящую стратегию на основе запроса.
 */
class OcrStrategySelector(
    private val engineRegistry: OcrEngineRegistry
) : IOcrStrategySelector {
    
    override fun select(request: OcrRequest): IOcrStrategy {
        // Сначала проверяем явную политику движка
        return when (request.enginePolicy) {
            OcrEnginePolicy.GOOGLE_MLKIT_ONLY -> GoogleMLKitOnlyStrategy(engineRegistry)
            OcrEnginePolicy.TESSERACT_ONLY -> TesseractOnlyStrategy(engineRegistry)
            OcrEnginePolicy.HUAWEI_ONLY -> HuaweiMLKitOnlyStrategy(engineRegistry)
            OcrEnginePolicy.FAST -> FastStrategy(engineRegistry)
            OcrEnginePolicy.ACCURATE -> AccurateStrategy(engineRegistry)
            OcrEnginePolicy.HYBRID -> HybridStrategy(engineRegistry)
            OcrEnginePolicy.AUTO -> selectByTask(request)
            OcrEnginePolicy.ONNX_ONLY -> {
                // TODO: Implement ONNX strategy when ready
                AutoStrategy(engineRegistry)
            }
        }
    }
    
    /**
     * Выбор стратегии по типу задачи.
     */
    private fun selectByTask(request: OcrRequest): IOcrStrategy {
        return when (request.taskType) {
            OcrTaskType.GENERAL_TEXT -> {
                // Для общего текста: быстрый ML Kit с fallback
                HybridStrategy(engineRegistry)
            }
            
            OcrTaskType.DOCUMENT -> {
                // Для документов: точность важнее скорости
                AccurateStrategy(engineRegistry)
            }
            
            OcrTaskType.RECEIPT -> {
                // Для чеков: гибридный подход
                ReceiptStrategy(engineRegistry)
            }
            
            OcrTaskType.DRIVER_LICENSE,
            OcrTaskType.PASSPORT -> {
                // Для документов с ID: точность критична
                AccurateStrategy(engineRegistry)
            }
            
            OcrTaskType.PDF_PAGE -> {
                // Для PDF: документная стратегия
                AccurateStrategy(engineRegistry)
            }
            
            OcrTaskType.BARCODE -> {
                // Для баркодов: специальная обработка (не OCR)
                // TODO: Implement barcode strategy
                FastStrategy(engineRegistry)
            }
            
            OcrTaskType.HANDWRITING -> {
                // Для рукописи: ONNX HTR или Tesseract
                HandwritingStrategy(engineRegistry)
            }
            
            OcrTaskType.SINGLE_LINE -> {
                // Для одной строки: быстрый подход
                FastStrategy(engineRegistry)
            }
            
            OcrTaskType.SECURE_FIELD -> {
                // Для защищённых полей: точность + безопасность
                AccurateStrategy(engineRegistry)
            }
        }
    }
}

/**
 * Стратегия для чеков.
 */
class ReceiptStrategy(
    engineRegistry: OcrEngineRegistry
) : BaseOcrStrategy(engineRegistry) {
    
    override val id: String = "receipt"
    override val name: String = "Receipt (Sparse Text)"
    
    override suspend fun recognize(request: PreparedOcrRequest): List<OcrCandidate> {
        val candidates = mutableListOf<OcrCandidate>()
        
        // Для чеков лучше работает ML Kit (sparse text)
        val mlkitCandidate = recognizeWithEngine("google_mlkit", request)
        if (mlkitCandidate != null) {
            candidates.add(mlkitCandidate)
        }
        
        // Если результат слабый, пробуем Tesseract
        if (mlkitCandidate == null || mlkitCandidate.rawText.length < 50) {
            val tessCandidate = recognizeWithEngine("tesseract", request)
            if (tessCandidate != null) {
                candidates.add(tessCandidate)
            }
        }
        
        return candidates
    }
}

/**
 * Стратегия для рукописи.
 */
class HandwritingStrategy(
    engineRegistry: OcrEngineRegistry
) : BaseOcrStrategy(engineRegistry) {
    
    override val id: String = "handwriting"
    override val name: String = "Handwriting (ONNX HTR)"
    
    override suspend fun recognize(request: PreparedOcrRequest): List<OcrCandidate> {
        val candidates = mutableListOf<OcrCandidate>()
        
        // TODO: Сначала пробуем ONNX HTR когда будет готов
        // val onnxCandidate = recognizeWithEngine("onnx_htr", request)
        
        // Пока используем Tesseract как fallback
        val tessCandidate = recognizeWithEngine("tesseract", request)
        if (tessCandidate != null) {
            candidates.add(tessCandidate)
        }
        
        return candidates
    }
}
