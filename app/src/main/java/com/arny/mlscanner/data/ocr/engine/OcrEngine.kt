package com.arny.mlscanner.data.ocr.engine

import android.graphics.Bitmap
import com.arny.mlscanner.domain.models.OcrResult

/**
 * Контракт для OCR-движка в data слое.
 *
 * Каждый движок (ML Kit, Tesseract) реализует этот интерфейс.
 * OcrRepositoryImpl оркестрирует их.
 */
interface OcrEngine {
    /** Человекочитаемое имя */
    val name: String

    /** Инициализация (может быть долгой) */
    suspend fun initialize(): Boolean

    /** Готов ли к работе */
    fun isReady(): Boolean

    /** Распознать текст */
    suspend fun recognize(bitmap: Bitmap, handwrittenMode: Boolean = false): OcrResult

    /** Освободить ресурсы */
    fun release()
}