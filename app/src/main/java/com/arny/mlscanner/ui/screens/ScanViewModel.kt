package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import com.arny.mlscanner.domain.models.RecognizedText
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.usecases.RecognizeTextUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
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

    // Добавляем состояние для отслеживания процесса
    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun setCapturedImage(bitmap: Bitmap) {
        capturedBitmap = bitmap
    }

    fun updateSettings(settings: ScanSettings) {
        scanSettings = settings
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
