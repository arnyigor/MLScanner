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
    /** Пользовательский контур обрезки (4 независимых угла). null — обрезка по детектору. */
    val cropQuad: CropQuad? = null,
    /** Контур, найденный детектором на превью: стартовая позиция углов в редакторе. */
    val suggestedQuad: CropQuad? = null,
    val originalImageSize: ImageSize? = null,
    val resultBitmap: Bitmap? = null,
    val qrCodes: List<com.arny.mlscanner.domain.models.barcode.BarcodeResult> = emptyList(),
    /** Откуда пришёл кадр: camera / file / editor. Для подписи на экране результата. */
    val scanSource: String = "",
    /** Момент завершения скана, epoch millis. */
    val scannedAt: Long = 0L,
    /** Оригинальный кадр для сравнения в просмотрщике (тот же экземпляр, что в history.save). */
    val originalBitmap: Bitmap? = null,
    /** Выбранный фильтр обработки (лента как в CamScanner). */
    val enhanceFilter: com.arny.mlscanner.data.image.raster.EnhanceFilter =
        com.arny.mlscanner.data.image.raster.EnhanceFilter.ORIGINAL,
    /** Результат применения выбранного фильтра; перекрывает resultBitmap на экране результата. */
    val enhancedBitmap: Bitmap? = null,
    /** Исходник для ленты фильтров: кадр, который получил OCR. */
    val enhancedSource: Bitmap? = null,
    /** Версия рабочего кадра: растёт при новом фото, повороте и «Применить» в редакторе. */
    val imageVersion: Long = 0L,
    /** Фильтр, применённый в редакторе: идёт на превью и в OCR (QR ищется без него). */
    val preScanFilter: com.arny.mlscanner.data.image.raster.EnhanceFilter =
        com.arny.mlscanner.data.image.raster.EnhanceFilter.ORIGINAL
)

enum class ScanStep {
    CAMERA,
    PREPROCESSING,
    ENHANCE,
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
    data class ShowError(val message: String) : ScanUiEvent
    data class ShareText(val text: String) : ScanUiEvent
    data object CopiedToClipboard : ScanUiEvent
    data class CopyToClipboard(val text: String) : ScanUiEvent
    data class NavigateTo(val step: ScanStep) : ScanUiEvent
    data object NavigateBack : ScanUiEvent
    
    // Новые события для паттернов
    data class CallPhone(val phoneNumber: String) : ScanUiEvent
    data class SendEmail(val email: String) : ScanUiEvent
    data class OpenUrl(val url: String) : ScanUiEvent
}
