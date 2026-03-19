package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import com.arny.mlscanner.domain.models.RecognizedText
import com.arny.mlscanner.domain.models.ScanSettings

/**
 * Единый sealed-state для всего процесса сканирования.
 * Вместо множества отдельных StateFlow — один источник истины.
 */
data class ScanUiState(
    val step: ScanStep = ScanStep.CAMERA,
    val settings: ScanSettings = ScanSettings.DEFAULT,
    val previewBitmap: Bitmap? = null,
    val processingProgress: Float = 0f,
    val processingMessage: String = "",
    val recognizedText: RecognizedText? = null,
    val isApplyingFilters: Boolean = false,
    val isScanning: Boolean = false,
    val error: ScanError? = null,
    val cropRect: CropRect? = null,
    val originalImageSize: ImageSize? = null
)

enum class ScanStep {
    CAMERA,
    PREPROCESSING,
    SCANNING,
    RESULT
}

data class ImageSize(val width: Int, val height: Int)

/**
 * Типизированные ошибки вместо String
 */
sealed class ScanError(val message: String) {
    data object NoImage : ScanError("Изображение не найдено")
    data object CameraFailed : ScanError("Ошибка камеры")
    data class OcrFailed(val cause: String) : ScanError("Ошибка OCR: $cause")
    data class PreprocessingFailed(val cause: String) : ScanError("Ошибка обработки: $cause")
    data object PermissionDenied : ScanError("Нет разрешения камеры")
    data class Unknown(val cause: String) : ScanError(cause)
}

/**
 * One-time события (тосты, навигация, share)
 */
sealed interface ScanUiEvent {
    data class ShowToast(val message: String) : ScanUiEvent
    data class ShareText(val text: String) : ScanUiEvent
    data object CopiedToClipboard : ScanUiEvent
    data class NavigateTo(val step: ScanStep) : ScanUiEvent
    data object NavigateBack : ScanUiEvent
}
