package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.RecognizedText
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.usecases.RecognizeTextUseCase
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScanViewModel(
    private val recognizeTextUseCase: RecognizeTextUseCase,
    private val imagePreprocessor: ImagePreprocessor
) : ViewModel() {

    // --- State ---

    // Исправление: Делаем поле публичным для чтения, чтобы PreprocessingScreen видел его
    var capturedBitmap: Bitmap? = null
        private set

    // Кэш уменьшенной копии (для быстрого UI)
    private var cachedGeometryBitmap: Bitmap? = null

    var scanSettings: ScanSettings = ScanSettings()
        private set

    private val _previewImage = MutableStateFlow<Bitmap?>(null)
    val previewImage: StateFlow<Bitmap?> = _previewImage.asStateFlow()

    private val _recognizedText = MutableStateFlow<RecognizedText?>(null)
    val recognizedText: StateFlow<RecognizedText?> = _recognizedText.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Job для отмены устаревших фильтраций
    private var filterJob: Job? = null

    // --- Methods ---

    fun setCapturedImage(bitmap: Bitmap) {
        capturedBitmap = bitmap // Сохраняем оригинал

        // Создаем thumbnail для UI (макс 1280px), чтобы фильтры не лагали
        val scaled = scaleBitmapDown(bitmap, 1280)

        cachedGeometryBitmap = scaled
        // Сразу показываем картинку (без фильтров пока)
        _previewImage.value = scaled

        // Применяем дефолтные настройки
        applyFiltersToPreview()
    }

    fun updateSettings(settings: ScanSettings) {
        scanSettings = settings
        applyFiltersToPreview()
    }

    private fun applyFiltersToPreview() {
        val source = cachedGeometryBitmap ?: return

        // Отменяем предыдущую обработку, если пользователь быстро двигает слайдер
        filterJob?.cancel()

        filterJob = viewModelScope.launch(Dispatchers.Default) {
            // Debounce: ждем 100мс, прежде чем начать тяжелую обработку
            delay(100)

            try {
                // ВАЖНО: Вызываем метод только для фильтров, без геометрии
                val filtered = imagePreprocessor.applyFiltersOnly(source, scanSettings)

                if (isActive) {
                    _previewImage.value = filtered
                }
            } catch (_: CancellationException) {
                // Игнорируем отмену
            }
        }
    }

    fun applyCropAndScan(cropRect: CropRect?, settings: ScanSettings) {
        viewModelScope.launch(Dispatchers.Default) {
            val original = capturedBitmap ?: return@launch

            // 1. Кропаем ОРИГИНАЛ (высокое разрешение)
            val cropped = cropBitmap(original, cropRect) ?: original

            // 2. Применяем фильтры к полному разрешению
            // Здесь используем prepareBaseImage или applyFiltersOnly - зависит от того,
            // нужно ли еще выпрямление. Если кроп ручной, авто-выпрямление обычно не нужно.
            val processedFull = imagePreprocessor.applyFiltersOnly(cropped, settings)

            // 3. Запускаем OCR
            startScanningInternal(processedFull)
        }
    }

    fun startScanning() {
        val bitmap = capturedBitmap ?: run {
            _error.value = "Image not found"
            return
        }
        startScanningInternal(bitmap)
    }

    private fun startScanningInternal(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            if (_isScanning.value) return@launch

            _isScanning.value = true
            _error.value = null

            try {
                val result = recognizeTextUseCase.execute(bitmap, scanSettings)
                if (result.isSuccess) {
                    Log.i(this::class.java.simpleName, "Result Text: $result")
                    _recognizedText.value = result.getOrNull()
                } else {
                    _error.value = result.exceptionOrNull()?.message ?: "Unknown error"
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun clear() {
        capturedBitmap = null
        cachedGeometryBitmap = null
        _previewImage.value = null
        _recognizedText.value = null
        _error.value = null
        _isScanning.value = false
    }

    private fun scaleBitmapDown(bitmap: Bitmap, maxDimension: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= maxDimension && h <= maxDimension) return bitmap

        val ratio = if (w > h) maxDimension.toFloat() / w else maxDimension.toFloat() / h
        val newW = (w * ratio).toInt()
        val newH = (h * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
    }

    private fun cropBitmap(source: Bitmap, rect: CropRect?): Bitmap? {
        if (rect == null) return source
        // Защита от вылета за границы
        val left = rect.left.toInt().coerceIn(0, source.width)
        val top = rect.top.toInt().coerceIn(0, source.height)
        val width = rect.width.toInt().coerceAtMost(source.width - left)
        val height = rect.height.toInt().coerceAtMost(source.height - top)

        if (width <= 0 || height <= 0) return null
        return Bitmap.createBitmap(source, left, top, width, height)
    }
}