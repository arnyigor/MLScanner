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
import androidx.core.graphics.scale

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

    var scanSettings: ScanSettings = ScanSettings(
        denoiseEnabled = false,
        binarizationEnabled = false,
        contrastLevel = 1.0f,
        brightnessLevel = 0f,
        sharpenLevel = 0f
    )
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

            // 2. Апскейл (если cropped маленький)
            val upscaled = if (maxOf(cropped.width, cropped.height) < 2000) {
                scaleBitmapUp(cropped, 2000)
            } else cropped

            // 2. Применяем фильтры к полному разрешению
            val processedFull = imagePreprocessor.applyFiltersOnly(upscaled, settings)

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

    // В ScanViewModel.kt

    private fun startScanningInternal(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            if (_isScanning.value) return@launch

            // 1. Умное масштабирование
            val minDimension = 2000
            val maxDimension = 3000
            val maxSide = maxOf(bitmap.width, bitmap.height)

            var processedBitmap = bitmap
            var needsBinarization = scanSettings.binarizationEnabled // Сохраняем пользовательскую настройку

            // Если картинка мелкая -> апскейлим И принудительно включаем бинаризацию
            if (maxSide < minDimension) {
                Log.d("ScanViewModel", "Upscaling bitmap from $maxSide to $minDimension")
                processedBitmap = scaleBitmapUp(bitmap, minDimension)
                // Для размытых после апскейла картинок бинаризация обязательна,
                // иначе Tesseract сходит с ума на градиентах
                needsBinarization = true
            } else if (maxSide > maxDimension) {
                Log.d("ScanViewModel", "Downscaling bitmap from $maxSide to $maxDimension")
                processedBitmap = scaleBitmapDown(bitmap, maxDimension)
            }

            // 2. Формируем настройки для этого прогона
            // Если мы апскейлили, мы ОБЯЗАНЫ включить бинаризацию в препроцессоре,
            // чтобы Tesseract получил четкое Ч/Б изображение, а не "мыло".
            val actualSettings = if (needsBinarization && !scanSettings.binarizationEnabled) {
                scanSettings.copy(binarizationEnabled = true)
            } else {
                scanSettings
            }

            _isScanning.value = true
            _error.value = null

            try {
                // Tesseract получит уже подготовленную (увеличенную + бинаризованную) картинку
                val result = recognizeTextUseCase.execute(processedBitmap, actualSettings)

                if (result.isSuccess) {
                    _recognizedText.value = result.getOrNull()
                } else {
                    _error.value = result.exceptionOrNull()?.message ?: "Unknown error"
                }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isScanning.value = false
                // Если мы создавали новый битмап при скейле, его лучше бы освободить,
                // но в Kotlin это сделает GC. Главное не ресайклить capturedBitmap.
            }
        }
    }

    private fun scaleBitmapUp(bitmap: Bitmap, targetMinSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val ratio = targetMinSide.toFloat() / maxOf(w, h)

        val newW = (w * ratio).toInt()
        val newH = (h * ratio).toInt()

        // 1. Создаем увеличенный битмап (он будет сглаженным/размытым)
        val scaled = bitmap.scale(newW, newH)

        // 2. КРИТИЧЕСКИ ВАЖНО: Копируем в чистый ARGB_8888.
        // Tesseract часто падает на битмапах, полученных из createScaledBitmap напрямую,
        // если система решила использовать Hardware config или другой stride.
        return if (scaled.config != Bitmap.Config.ARGB_8888 || !scaled.isMutable) {
            scaled.copy(Bitmap.Config.ARGB_8888, true)
        } else {
            scaled
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