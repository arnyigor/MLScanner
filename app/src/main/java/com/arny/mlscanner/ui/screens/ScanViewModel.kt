package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arny.mlscanner.domain.models.RecognizedText
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.usecases.RecognizeTextUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ScanViewModel(
    private val recognizeTextUseCase: RecognizeTextUseCase
) : ViewModel() {

    var capturedBitmap: Bitmap? = null
        private set

    var scanSettings: ScanSettings = ScanSettings()
        private set

    private val _recognizedText = MutableStateFlow<RecognizedText?>(null)
    val recognizedText = _recognizedText.asStateFlow()

    private val _previewImage = MutableStateFlow<Bitmap?>(null)
    val previewImage: StateFlow<Bitmap?> = _previewImage.asStateFlow()

    // Добавляем состояние для отслеживания процесса
    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun setCapturedImage(bitmap: Bitmap) {
        capturedBitmap = bitmap
        // Immediately generate a first‑pass preview (no filters yet)
        _previewImage.value = bitmap
    }

    fun updateSettings(settings: ScanSettings) {
        scanSettings = settings
        regeneratePreview()
    }

    private fun regeneratePreview() {
        val source = capturedBitmap ?: return
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val processed = recognizeTextUseCase.preprocessImage(source, scanSettings)
                _previewImage.emit(processed)
            } catch (e: Exception) {
                // ignore – preview is best‑effort
            }
        }
    }

    /**
     * Применяет кроп к изображению и обновляет настройки перед сканированием
     */
    fun applyCropAndScan(cropRect: CropRect?, settings: ScanSettings) {
        updateSettings(settings)

        // Если кроп задан -> обрезаем capturedBitmap
        if (cropRect != null) {
            capturedBitmap?.let { original ->
                try {
                    // Проверяем границы, чтобы не вылететь с исключением
                    val safeX = cropRect.left.toInt().coerceIn(0, original.width)
                    val safeY = cropRect.top.toInt().coerceIn(0, original.height)
                    val safeWidth = cropRect.width.toInt().coerceAtMost(original.width - safeX)
                    val safeHeight = cropRect.height.toInt().coerceAtMost(original.height - safeY)

                    if (safeWidth > 0 && safeHeight > 0) {
                        val croppedBitmap = Bitmap.createBitmap(
                            original,
                            safeX,
                            safeY,
                            safeWidth,
                            safeHeight
                        )
                        // Обновляем главное изображение во ViewModel
                        setCapturedImage(croppedBitmap)
                    }
                } catch (e: Exception) {
                    e.printStackTrace() // Логируем ошибку кропа
                }
            }
        }
    }

    fun clear() {
        capturedBitmap = null
        _recognizedText.value = null
        _error.value = null
        _isScanning.value = false
    }

    fun startScanning() {
        val bitmap = capturedBitmap
        if (bitmap == null) {
            _error.value = "Image not found"
            return
        }

        // Если уже идет сканирование, не запускаем снова
        if (_isScanning.value) return

        viewModelScope.launch {
            _isScanning.value = true
            _error.value = null

            try {
                // Вызов UseCase
                recognizeTextUseCase.execute(bitmap, scanSettings)
                    .onSuccess { result ->
                        _recognizedText.value = result
                    }
                    .onFailure { e ->
                        _error.value = e.message ?: "Unknown error"
                    }
            } catch (e: Exception) {
                _error.value = e.message
            } finally {
                _isScanning.value = false
            }
        }
    }
}
