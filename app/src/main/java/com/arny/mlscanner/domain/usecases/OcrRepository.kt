package com.arny.mlscanner.domain.usecases

import android.graphics.Bitmap
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.ScanSettings

/**
 * Репозиторий для распознавания текста (OCR).
 *
 * Единственная точка доступа к OCR из UseCase и ViewModel.
 * Инкапсулирует выбор движка, инициализацию и lifecycle.
 */
interface OcrRepository {
    /**
     * Инициализация OCR-движков.
     * @return Карта статусов инициализации каждого движка
     */
    suspend fun initialize(): Map<String, Boolean>

    /**
     * Распознать текст с использованием настроек.
     * Автоматически выбирает лучший движок (Hybrid стратегия).
     */
    suspend fun recognize(
        bitmap: Bitmap,
        settings: ScanSettings
    ): OcrResult

    /**
     * Распознать конкретным движком (для бенчмарков).
     */
    suspend fun recognizeWith(
        bitmap: Bitmap,
        engineName: String,
        settings: ScanSettings
    ): OcrResult

    /** Готов ли репозиторий к работе? */
    fun isReady(): Boolean

    /**
     * Освобождение ресурсов.
     * Вызывается при завершении приложения или при необходимости перезапуска.
     */
    fun release()
}