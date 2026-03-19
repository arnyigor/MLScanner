// ============================================================
// domain/models/errors/OcrError.kt
// OCR-специфичные ошибки
// ============================================================
package com.arny.mlscanner.domain.models.errors

import com.arny.mlscanner.domain.models.strings.StringHolder

/**
 * Ошибки, специфичные для OCR-процесса.
 *
 * Каждый тип ошибки содержит:
 * - Человекочитаемое сообщение (StringHolder)
 * - Техническую причину (cause)
 *
 * Использование:
 * ```
 * when (error) {
 *     is OcrError.EngineNotInitialized -> showRetryDialog()
 *     is OcrError.RecognitionFailed -> showErrorMessage(error.displayMessage)
 *     is OcrError.ImageTooSmall -> suggestHigherResolution()
 * }
 * ```
 */
sealed class OcrError(
    val displayMessage: StringHolder,
    override val cause: Throwable? = null
) : Exception(displayMessage.toString(), cause) {

    /**
     * OCR-движок не инициализирован.
     * Возможные причины: traineddata не найден, нехватка памяти.
     */
    data class EngineNotInitialized(
        val engineName: String,
        val reason: String,
        override val cause: Throwable? = null
    ) : OcrError(
        displayMessage = StringHolder.Text(
            "OCR engine '$engineName' not initialized: $reason"
        ),
        cause = cause
    )

    /**
     * Ошибка распознавания текста.
     */
    data class RecognitionFailed(
        val reason: String,
        override val cause: Throwable? = null
    ) : OcrError(
        displayMessage = StringHolder.Text("Recognition failed: $reason"),
        cause = cause
    )

    /**
     * Ошибка предобработки изображения.
     */
    data class PreprocessingFailed(
        val step: String,
        override val cause: Throwable? = null
    ) : OcrError(
        displayMessage = StringHolder.Text("Image preprocessing failed at: $step"),
        cause = cause
    )

    /**
     * Изображение слишком маленькое для распознавания.
     */
    data class ImageTooSmall(
        val width: Int,
        val height: Int,
        val minRequired: Int
    ) : OcrError(
        displayMessage = StringHolder.Text(
            "Image too small (${width}x${height}). Minimum: ${minRequired}px"
        )
    )

    /**
     * Текст не найден на изображении.
     */
    data object NoTextFound : OcrError(
        displayMessage = StringHolder.Text("No text found in image")
    )

    /**
     * Таймаут обработки.
     */
    data class Timeout(
        val timeoutMs: Long
    ) : OcrError(
        displayMessage = StringHolder.Text(
            "OCR processing timed out after ${timeoutMs}ms"
        )
    )

    /**
     * Отмена пользователем.
     */
    data object Cancelled : OcrError(
        displayMessage = StringHolder.Text("OCR cancelled by user")
    )

    /**
     * Неизвестная ошибка.
     */
    data class Unknown(
        val reason: String,
        override val cause: Throwable? = null
    ) : OcrError(
        displayMessage = StringHolder.Text(reason),
        cause = cause
    )
}