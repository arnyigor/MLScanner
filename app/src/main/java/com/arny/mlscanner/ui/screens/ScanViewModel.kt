package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.usecases.RecognizeTextUseCase
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

class ScanViewModel(
    private val recognizeTextUseCase: RecognizeTextUseCase,
    private val imagePreprocessor: ImagePreprocessor
) : ViewModel() {

    companion object {
        private const val TAG = "ScanViewModel"
        private const val PREVIEW_MAX_DIMENSION = 1280
        private const val OCR_MIN_DIMENSION = 2000
        private const val OCR_MAX_DIMENSION = 3000
        private const val FILTER_DEBOUNCE_MS = 150L
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

    fun onTextEdited(newText: String) {
        _uiState.update { state ->
            state.copy(
                recognizedText = state.recognizedText?.copy(formattedText = newText)
            )
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

    // Backward compatibility
    fun setCapturedImage(bitmap: Bitmap) = onImageCaptured(bitmap)
    
    fun applyCropAndScan(cropRect: CropRect?, settings: ScanSettings) {
        cropRect?.let { onCropChanged(it) }
        onStartScanning()
    }

    val capturedBitmap: Bitmap? get() = originalBitmap
    val previewImage: StateFlow<Bitmap?> get() = MutableStateFlow(previewSourceBitmap).also { /* preserve for backward compat */ }
    val recognizedText: StateFlow<com.arny.mlscanner.domain.models.RecognizedText?> get() = MutableStateFlow(_uiState.value.recognizedText)
    val isScanning: StateFlow<Boolean> get() = MutableStateFlow(_uiState.value.isScanning)
    val error: StateFlow<String?> get() = MutableStateFlow(_uiState.value.error?.message)

    fun updateSettings(settings: ScanSettings) = onSettingsChanged(settings)
    fun startScanning() = onStartScanning()
    fun clear() = onNewScan()

    // Private

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
        try {
            val state = _uiState.value
            val settings = state.settings

            updateProgress(0.1f, "Cropping image...")
            val cropped = applyCrop(originalBitmap, state.cropRect)

            updateProgress(0.2f, "Optimizing resolution...")
            val (scaled, needsForceBinarize) = scaleForOcr(cropped)

            updateProgress(0.3f, "Applying filters...")
            val actualSettings = if (needsForceBinarize && !settings.binarizationEnabled) {
                settings.copy(binarizationEnabled = true)
            } else {
                settings
            }
            val processed = imagePreprocessor.applyFiltersOnly(scaled, actualSettings)

            updateProgress(0.5f, "Recognizing text...")
            val result = recognizeTextUseCase.execute(processed, actualSettings)

            if (result.isSuccess) {
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
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                _uiState.update {
                    it.copy(
                        step = ScanStep.PREPROCESSING,
                        isScanning = false,
                        error = ScanError.OcrFailed(errorMsg)
                    )
                }
            }

            if (cropped !== originalBitmap) safeRecycle(cropped)
            if (scaled !== cropped) safeRecycle(scaled)
            if (processed !== scaled) safeRecycle(processed)

        } catch (_: CancellationException) {
            _uiState.update { it.copy(step = ScanStep.PREPROCESSING, isScanning = false) }
        } catch (e: Exception) {
            Log.e(TAG, "Scanning error", e)
            _uiState.update {
                it.copy(
                    step = ScanStep.PREPROCESSING,
                    isScanning = false,
                    error = ScanError.OcrFailed(e.message ?: "Unknown")
                )
            }
        }
    }

    private fun applyCrop(source: Bitmap, crop: CropRect?): Bitmap {
        if (crop == null) return source

        val left = crop.left.toInt().coerceIn(0, source.width - 1)
        val top = crop.top.toInt().coerceIn(0, source.height - 1)
        val width = crop.width.toInt().coerceAtMost(source.width - left)
        val height = crop.height.toInt().coerceAtMost(source.height - top)

        if (width <= 10 || height <= 10) {
            Log.w(TAG, "Crop too small ($width x $height), using full image")
            return source
        }

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

        if (maxSide == targetMaxSide) return bitmap

        val ratio = targetMaxSide.toFloat() / maxSide
        val newW = (w * ratio).toInt().coerceAtLeast(1)
        val newH = (h * ratio).toInt().coerceAtLeast(1)

        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)

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
        originalBitmap = null
        previewSourceBitmap = null
    }

    override fun onCleared() {
        super.onCleared()
        clearBitmaps()
    }
}
