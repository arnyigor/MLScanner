package com.arny.mlscanner.domain.usecases

import android.graphics.Bitmap
import com.arny.mlscanner.data.ocr.OcrEngine
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.RecognizedText
import com.arny.mlscanner.domain.models.ScanSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Улучшенный UseCase для распознавания текста.
 *
 * Добавлен параметр `ioDispatcher` – позволяет в тестах заменить
 * реальный Default‑dispatcher на Test‑dispatcher.
 */
class AdvancedRecognizeTextUseCase(
    private val ocrEngine: OcrEngine,
    private val imagePreprocessor: ImagePreprocessor,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default  // ← NEW
) {
    /**
     * Выполнение распознавания текста с использованием нового OCR-движка
     */
    suspend fun execute(
        bitmap: Bitmap,
        settings: ScanSettings
    ): OcrResult = withContext(ioDispatcher) {
        // 1. Предобработка изображения
        val preprocessedBitmap = imagePreprocessor.preprocessImage(bitmap, settings)

        // 2. Распознавание текста с использованием ONNX Runtime
        ocrEngine.recognize(preprocessedBitmap)
    }

    /**
     * Инициализация OCR-движка
     */
    fun initializeOcrEngine(): Boolean {
        return ocrEngine.initialize()
    }

    /**
     * Освобождение ресурсов OCR-движка
     */
    fun cleanup() {
        ocrEngine.cleanup()
    }

    /**
     * Предобработка изображения
     */
    fun preprocessImage(source: Bitmap, scanSettings: ScanSettings): Bitmap =
        imagePreprocessor.preprocessImage(source, scanSettings)
}