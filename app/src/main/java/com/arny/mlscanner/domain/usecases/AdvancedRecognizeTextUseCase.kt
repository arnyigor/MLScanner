package com.arny.mlscanner.domain.usecases

import android.graphics.Bitmap
import com.arny.mlscanner.data.ocr.TesseractEngine
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.OcrResult
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
    private val ocrEngine: TesseractEngine,
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
        val preprocessedBitmap = imagePreprocessor.prepareBaseImage(bitmap, settings)

        // 2. Распознавание текста с использованием ONNX Runtime
        ocrEngine.recognize(preprocessedBitmap)
    }

    /**
     * Инициализация OCR-движка
     */
    fun initializeOcrEngine(): Boolean {
        ocrEngine.init()
        return true
    }

    /**
     * Освобождение ресурсов OCR-движка
     */
    fun cleanup() {
        ocrEngine.close()
    }

    /**
     * Предобработка изображения
     */
    fun preprocessImage(source: Bitmap, scanSettings: ScanSettings): Bitmap =
        imagePreprocessor.prepareBaseImage(source, scanSettings)
}