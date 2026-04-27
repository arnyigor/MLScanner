package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arny.mlscanner.data.ocr.postprocessing.TextFormatter
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.models.OcrEngineType
import com.arny.mlscanner.domain.models.errors.OcrError
import com.arny.mlscanner.domain.usecases.RecognizeTextUseCase
import com.arny.mlscanner.domain.usecases.barcode.ScanBarcodeUseCase
import com.arny.mlscanner.domain.models.RecognizedText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.core.graphics.scale

class ScanViewModel(
    private val recognizeTextUseCase: RecognizeTextUseCase,
    private val imagePreprocessor: ImagePreprocessor,
    private val scanBarcodeUseCase: ScanBarcodeUseCase? = null
) : ViewModel() {

    companion object {
        private const val TAG = "ScanViewModel"
        private const val PREVIEW_MAX_DIMENSION = 1280
        private const val OCR_MIN_DIMENSION = 800
        private const val OCR_MAX_DIMENSION = 2048
        private const val FILTER_DEBOUNCE_MS = 350L
    }

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val _events = Channel<ScanUiEvent>(Channel.BUFFERED)
    val events: Flow<ScanUiEvent> = _events.receiveAsFlow()

    private var originalBitmap: Bitmap? = null
    private var previewSourceBitmap: Bitmap? = null

    private var filterJob: Job? = null
    private var scanJob: Job? = null
    private val scanMutex = Mutex()

    // Public API

    fun onImageCaptured(bitmap: Bitmap) {
        Log.d(TAG, "Image captured: ${bitmap.width}x${bitmap.height}")
        originalBitmap = bitmap
        val preview = scaleBitmapSafe(bitmap, PREVIEW_MAX_DIMENSION)
        previewSourceBitmap = preview

        _uiState.update {
            it.copy(
                step = ScanStep.PREPROCESSING,
                previewBitmap = preview,
                originalImageSize = ImageSize(bitmap.width, bitmap.height),
                error = null,
                recognizedText = null
            )
        }
    }

    fun onSettingsChanged(settings: ScanSettings) {
        _uiState.update { it.copy(settings = settings) }
        applyFiltersDebounced(settings)
    }

    fun onCropChanged(cropRect: CropRect) {
        val previewBmp = previewSourceBitmap ?: return
        val originalBmp = originalBitmap ?: return

        val scaleX = originalBmp.width.toFloat() / previewBmp.width
        val scaleY = originalBmp.height.toFloat() / previewBmp.height

        val originalCrop = CropRect(
            left = cropRect.left * scaleX,
            top = cropRect.top * scaleY,
            width = cropRect.width * scaleX,
            height = cropRect.height * scaleY
        )

        _uiState.update { it.copy(cropRect = originalCrop) }
    }

    fun onStartScanning() {
        val original = originalBitmap
        if (original == null) {
            _uiState.update { it.copy(error = ScanError.NoImage) }
            return
        }

        _uiState.update {
            it.copy(
                step = ScanStep.SCANNING,
                isScanning = true,
                error = null,
                processingProgress = 0f,
                processingMessage = "Preparing image..."
            )
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch(Dispatchers.Default) {
            scanMutex.withLock {
                performScanning(original)
            }
        }
    }

    fun onCancelScanning() {
        scanJob?.cancel()
        _uiState.update {
            it.copy(
                step = ScanStep.PREPROCESSING,
                isScanning = false,
                processingProgress = 0f
            )
        }
    }

    fun onNewScan() {
        clearBitmaps()
        _uiState.value = ScanUiState()
    }

    fun onReturnToPreprocessing() {
        _uiState.update { it.copy(step = ScanStep.PREPROCESSING) }
    }

    fun onTextEdited(newText: String) {
        _uiState.update { state ->
            state.copy(
                recognizedText = state.recognizedText?.updateRawText(newText)
            )
        }
    }

    fun onApplyTextChanges(newText: String) {
        _uiState.update { state ->
            state.copy(
                recognizedText = state.recognizedText?.applyRawText(newText)
            )
        }
    }
    
    fun onToggleFormatMode() {
        _uiState.update { state ->
            state.copy(
                recognizedText = state.recognizedText?.toggleFormatMode()
            )
        }
    }
    
    fun onPatternClick(action: TextFormatter.ClickAction) {
        viewModelScope.launch {
            when (action) {
                is TextFormatter.ClickAction.Call -> {
                    _events.send(ScanUiEvent.CallPhone(action.phoneNumber))
                }
                is TextFormatter.ClickAction.SendEmail -> {
                    _events.send(ScanUiEvent.SendEmail(action.email))
                }
                is TextFormatter.ClickAction.OpenUrl -> {
                    _events.send(ScanUiEvent.OpenUrl(action.url))
                }
                is TextFormatter.ClickAction.CopyToClipboard -> {
                    _events.send(ScanUiEvent.CopyToClipboard(action.text))
                }
            }
        }
    }

    fun onCopyText() {
        viewModelScope.launch {
            _events.send(ScanUiEvent.CopiedToClipboard)
        }
    }

    fun onShareText() {
        val text = _uiState.value.recognizedText?.formattedText ?: return
        viewModelScope.launch {
            _events.send(ScanUiEvent.ShareText(text))
        }
    }

    fun onErrorDismissed() {
        _uiState.update { it.copy(error = null) }
    }

    private fun applyFiltersDebounced(settings: ScanSettings) {
        val source = previewSourceBitmap ?: return

        filterJob?.cancel()
        filterJob = viewModelScope.launch(Dispatchers.Default) {
            delay(FILTER_DEBOUNCE_MS)
            _uiState.update { it.copy(isApplyingFilters = true) }

            try {
                val filtered = imagePreprocessor.applyFiltersOnly(source, settings)
                if (isActive) {
                    _uiState.update {
                        it.copy(previewBitmap = filtered, isApplyingFilters = false)
                    }
                }
            } catch (_: CancellationException) {
                // Normal - user is moving slider
            } catch (e: Exception) {
                Log.e(TAG, "Filter error", e)
                _uiState.update { it.copy(isApplyingFilters = false) }
            }
        }
    }

    private suspend fun performScanning(originalBitmap: Bitmap) {
        Log.d(TAG, "Starting performScanning process")
        try {
            val state = _uiState.value
            val settings = state.settings

            updateProgress(0.1f, "Cropping image...")
            Log.d(TAG, "Applying crop rect: ${state.cropRect}")
            val cropped = applyCrop(originalBitmap, state.cropRect)

            _uiState.update { it.copy(resultBitmap = cropped) }

            if (settings.engineType == OcrEngineType.BARCODE) {
                Log.i(TAG, "Engine type is BARCODE. Scanning for barcodes...")
                updateProgress(0.5f, "Scanning for barcodes...")
                val barcodeUseCase = scanBarcodeUseCase
                if (barcodeUseCase == null) {
                    Log.w(TAG, "Barcode scanning not available: barcodeUseCase is null")
                    _uiState.update {
                        it.copy(
                            step = ScanStep.PREPROCESSING,
                            isScanning = false,
                            error = ScanError.OcrFailed("Barcode scanning not configured")
                        )
                    }
                    return
                }

                try {
                    val barcodes = barcodeUseCase(cropped)
                    Log.d(TAG, "Barcode scan completed. Found: ${barcodes.size} barcodes")

                    if (barcodes.isNotEmpty()) {
                        val barcode = barcodes.first()

                        val formattedText = buildString {
                            append("Формат: ${barcode.format.name}\n\n")
                            append("Результат:\n${barcode.rawValue}")
                            barcode.parsedContent?.let { content ->
                                append("\n\nТип: ${barcode.contentType.name}")
                            }
                        }

                        val patterns = com.arny.mlscanner.data.ocr.postprocessing.PatternRecognizer.recognizeAll(barcode.rawValue)

                        val recognized = RecognizedText(
                            originalText = barcode.rawValue,
                            formattedText = formattedText,
                            blocks = emptyList(),
                            confidence = barcode.confidence,
                            detectedLanguage = "N/A",
                            recognizedPatterns = patterns,
                            formatMode = com.arny.mlscanner.data.ocr.postprocessing.TextFormatter.FormatMode.RAW
                        )

                        Log.i(TAG, "Barcode successfully recognized and formatted")
                        updateProgress(1.0f, "Done!")
                        _uiState.update {
                            it.copy(
                                step = ScanStep.RESULT,
                                recognizedText = recognized,
                                isScanning = false,
                                processingProgress = 1f
                            )
                        }
                    } else {
                        Log.w(TAG, "No barcodes found in the cropped image")
                        _uiState.update {
                            it.copy(
                                step = ScanStep.PREPROCESSING,
                                isScanning = false,
                                error = ScanError.OcrFailed("Barcode not found")
                            )
                        }
                        _events.send(ScanUiEvent.ShowError("Баркод не найден"))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during barcode scanning execution", e)
                    _uiState.update {
                        it.copy(
                            step = ScanStep.PREPROCESSING,
                            isScanning = false,
                            error = ScanError.OcrFailed(e.message ?: "Barcode scan failed")
                        )
                    }
                    _events.send(ScanUiEvent.ShowError("Ошибка сканирования баркода: ${e.message}"))
                }
                return
            }

            // --- OCR PATH ---
            Log.i(TAG, "Engine type is OCR. Starting text recognition pipeline")
            updateProgress(0.2f, "Optimizing resolution...")
            val (scaled, needsForceBinarize) = scaleForOcr(cropped)
            Log.d(TAG, "Image scaled. needsForceBinarize: $needsForceBinarize")

            updateProgress(0.3f, "Applying filters...")
            val actualSettings = if (needsForceBinarize && !settings.binarizationEnabled) {
                Log.d(TAG, "Forcing binarization due to scale requirements")
                settings.copy(binarizationEnabled = true)
            } else {
                settings
            }
            var processed = imagePreprocessor.applyFiltersOnly(scaled, actualSettings)
            Log.d(TAG, "Filters applied using settings: $actualSettings")

            updateProgress(0.5f, "Recognizing text...")
            Log.d(TAG, "Executing recognizeTextUseCase first attempt")
            var result = recognizeTextUseCase.execute(processed, actualSettings)

            if (result.exceptionOrNull() is OcrError.NoTextFound) {
                Log.i(TAG, "No text found. Attempting retry with stronger filters...")
                updateProgress(0.65f, "Retrying with stronger filters...")

                val retrySettings = actualSettings.copy(
                    contrastLevel = maxOf(actualSettings.contrastLevel, 1.8f),
                    sharpenLevel = maxOf(actualSettings.sharpenLevel, 0.8f),
                    denoiseEnabled = true,
                    binarizationEnabled = true
                )

                val retryProcessed = imagePreprocessor.applyFiltersOnly(scaled, retrySettings)

                if (processed !== scaled && processed !== originalBitmap) {
                    safeRecycle(processed)
                }

                processed = retryProcessed
                Log.d(TAG, "Executing recognizeTextUseCase second attempt with retrySettings")
                result = recognizeTextUseCase.execute(processed, retrySettings)
            }

            if (result.isSuccess) {
                Log.i(TAG, "OCR successfully recognized text")
                val recognized = result.getOrNull()
                updateProgress(1.0f, "Done!")

                _uiState.update {
                    it.copy(
                        step = ScanStep.RESULT,
                        recognizedText = recognized,
                        isScanning = false,
                        processingProgress = 1f
                    )
                }
            } else {
                val exception = result.exceptionOrNull()
                val errorMsg = when (exception) {
                    is com.arny.mlscanner.domain.models.errors.OcrError -> {
                        buildString {
                            append(exception.displayMessage.toString())
                            exception.cause?.let { cause ->
                                append("\n\nDetails:\n")
                                append(cause.javaClass.simpleName)
                                append(": ")
                                append(cause.message ?: "No message")

                                val stackTrace = cause.stackTrace.take(5).joinToString("\n") {
                                    "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                                }
                                if (stackTrace.isNotEmpty()) {
                                    append("\n")
                                    append(stackTrace)
                                }
                            }
                        }
                    }
                    else -> exception?.message ?: "Unknown error"
                }

                Log.e(TAG, "OCR failed: $errorMsg", exception)

                _uiState.update {
                    it.copy(
                        step = ScanStep.PREPROCESSING,
                        isScanning = false,
                        processingProgress = 0f,
                        error = ScanError.OcrFailed(errorMsg)
                    )
                }
            }

            // Final memory cleanup
            Log.v(TAG, "Cleaning up bitmaps. scaled: $scaled, processed: $processed")
            if (scaled !== cropped && scaled !== originalBitmap) safeRecycle(scaled)
            if (processed !== scaled && processed !== originalBitmap) safeRecycle(processed)

        } catch (ce: CancellationException) {
            Log.i(TAG, "Scanning cancelled by coroutine cancellation")
            _uiState.update { it.copy(step = ScanStep.PREPROCESSING, isScanning = false) }
            throw ce // Важно пробросить CancellationException
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected scanning error", e)

            val detailedError = buildString {
                append(e.javaClass.simpleName)
                append(": ")
                append(e.message ?: "No message")
                append("\n\nStacktrace:\n")
                append(e.stackTrace.take(10).joinToString("\n") {
                    "  at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                })
            }

            _uiState.update {
                it.copy(
                    step = ScanStep.PREPROCESSING,
                    isScanning = false,
                    processingProgress = 0f,
                    error = ScanError.OcrFailed(detailedError)
                )
            }
        }
    }


    private fun applyCrop(source: Bitmap, crop: CropRect?): Bitmap {
        if (crop == null) return source

        // Добавляем padding вокруг crop для лучшего OCR
        // Tesseract работает лучше, когда есть белые поля вокруг текста
        val PADDING_PERCENT = 0.05f // 5% padding с каждой стороны
        
        val paddingX = (crop.width * PADDING_PERCENT).toInt()
        val paddingY = (crop.height * PADDING_PERCENT).toInt()
        
        val left = (crop.left - paddingX).toInt().coerceIn(0, source.width - 1)
        val top = (crop.top - paddingY).toInt().coerceIn(0, source.height - 1)
        val right = (crop.left + crop.width + paddingX).toInt().coerceAtMost(source.width)
        val bottom = (crop.top + crop.height + paddingY).toInt().coerceAtMost(source.height)
        
        val width = right - left
        val height = bottom - top

        if (width <= 10 || height <= 10) {
            Log.w(TAG, "Crop too small ($width x $height), using full image")
            return source
        }
        
        Log.d(TAG, "Crop with padding: ${crop.width.toInt()}x${crop.height.toInt()} → ${width}x${height}")

        return Bitmap.createBitmap(source, left, top, width, height)
    }

    private fun scaleForOcr(bitmap: Bitmap): Pair<Bitmap, Boolean> {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        var needsBinarize = false

        val result = when {
            maxSide < OCR_MIN_DIMENSION -> {
                Log.d(TAG, "Upscaling: $maxSide → $OCR_MIN_DIMENSION")
                needsBinarize = true
                scaleBitmapSafe(bitmap, OCR_MIN_DIMENSION)
            }
            maxSide > OCR_MAX_DIMENSION -> {
                Log.d(TAG, "Downscaling: $maxSide → $OCR_MAX_DIMENSION")
                scaleBitmapSafe(bitmap, OCR_MAX_DIMENSION)
            }
            else -> bitmap
        }

        return Pair(result, needsBinarize)
    }

    private fun updateProgress(progress: Float, message: String) {
        _uiState.update {
            it.copy(processingProgress = progress, processingMessage = message)
        }
    }

    private fun scaleBitmapSafe(bitmap: Bitmap, targetMaxSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val maxSide = maxOf(w, h)
        val minSide = minOf(w, h)

        // Если минимальная сторона слишком маленькая - апскейлим
        val MIN_SIDE_FOR_OCR = 600
        if (minSide < MIN_SIDE_FOR_OCR) {
            val upscaleRatio = MIN_SIDE_FOR_OCR.toFloat() / minSide
            val newW = (w * upscaleRatio).toInt().coerceAtLeast(1)
            val newH = (h * upscaleRatio).toInt().coerceAtLeast(1)
            
            Log.d(TAG, "Upscaling narrow image: ${w}x${h} → ${newW}x${newH} (minSide $minSide→${minOf(newW, newH)})")
            
            val scaled = bitmap.scale(newW, newH)
            return if (scaled.config != Bitmap.Config.ARGB_8888) {
                val copy = scaled.copy(Bitmap.Config.ARGB_8888, false)
                if (scaled !== bitmap) scaled.recycle()
                copy
            } else {
                scaled
            }
        }

        if (maxSide == targetMaxSide) return bitmap

        val ratio = targetMaxSide.toFloat() / maxSide
        val newW = (w * ratio).toInt().coerceAtLeast(1)
        val newH = (h * ratio).toInt().coerceAtLeast(1)

        val scaled = bitmap.scale(newW, newH)

        return if (scaled.config != Bitmap.Config.ARGB_8888) {
            val copy = scaled.copy(Bitmap.Config.ARGB_8888, false)
            if (scaled !== bitmap) scaled.recycle()
            copy
        } else {
            scaled
        }
    }

    private fun safeRecycle(bitmap: Bitmap) {
        if (!bitmap.isRecycled && bitmap !== originalBitmap && bitmap !== previewSourceBitmap) {
            bitmap.recycle()
        }
    }

    private fun clearBitmaps() {
        filterJob?.cancel()
        scanJob?.cancel()
        val state = _uiState.value
        if (state.resultBitmap != null && 
            state.resultBitmap !== originalBitmap && 
            !state.resultBitmap.isRecycled) {
            state.resultBitmap.recycle()
        }
        originalBitmap = null
        previewSourceBitmap = null
    }

    /**
     * Поворот исходного изображения.
     * Сбрасывает preview и пересчитывает фильтры.
     */
    fun rotateImage(degrees: Float) {
        val original = originalBitmap ?: return

        val matrix = android.graphics.Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(
            original, 0, 0, original.width, original.height, matrix, true
        )

        // Заменяем оригинал
        originalBitmap = rotated

        // Пересоздаём preview
        val scaled = scaleBitmapSafe(rotated, PREVIEW_MAX_DIMENSION)
        previewSourceBitmap = scaled

        // Применяем текущие фильтры
        applyFiltersDebounced(_uiState.value.settings)
    }

    override fun onCleared() {
        super.onCleared()
        clearBitmaps()
    }
}
