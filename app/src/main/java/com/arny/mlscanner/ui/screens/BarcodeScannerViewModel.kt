package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arny.mlscanner.domain.models.barcode.BarcodeResult
import com.arny.mlscanner.domain.models.barcode.BarcodeScanConfig
import com.arny.mlscanner.domain.usecases.barcode.ScanBarcodeUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BarcodeScannerViewModel(
    private val scanBarcodeUseCase: ScanBarcodeUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(BarcodeScannerUiState())
    val uiState: StateFlow<BarcodeScannerUiState> = _uiState.asStateFlow()

    private val _scannedResults = MutableStateFlow<List<BarcodeResult>>(emptyList())
    val scannedResults: StateFlow<List<BarcodeResult>> = _scannedResults.asStateFlow()

    private var config = BarcodeScanConfig()

    fun onBarcodeDetected(results: List<BarcodeResult>) {
        if (results.isEmpty()) return

        when (config.scanMode) {
            BarcodeScanConfig.ScanMode.SINGLE -> {
                _uiState.update { it.copy(isPaused = true, lastResult = results.first()) }
                _scannedResults.value = results.take(1)
            }
            BarcodeScanConfig.ScanMode.CONTINUOUS -> {
                _uiState.update { it.copy(lastResult = results.first()) }
                _scannedResults.value = results
            }
            BarcodeScanConfig.ScanMode.BATCH -> {
                val existing = _scannedResults.value.map { it.rawValue }.toSet()
                val newResults = results.filter { it.rawValue !in existing }
                if (newResults.isNotEmpty()) {
                    _scannedResults.value = _scannedResults.value + newResults
                    _uiState.update { it.copy(batchCount = _scannedResults.value.size, lastResult = newResults.first()) }
                }
            }
        }
    }

    fun scanImage(bitmap: Bitmap) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            try {
                val results = scanBarcodeUseCase(bitmap, config)
                _scannedResults.value = results
                _uiState.update { it.copy(isLoading = false, lastResult = results.firstOrNull()) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    fun updateConfig(newConfig: BarcodeScanConfig) {
        config = newConfig
    }

    fun resumeScanning() {
        _uiState.update { it.copy(isPaused = false) }
    }

    fun clearResults() {
        _scannedResults.value = emptyList()
        _uiState.update { it.copy(batchCount = 0, lastResult = null) }
    }

    fun toggleTorch() {
        _uiState.update { it.copy(torchEnabled = !it.torchEnabled) }
    }
}

data class BarcodeScannerUiState(
    val isLoading: Boolean = false,
    val isPaused: Boolean = false,
    val error: String? = null,
    val lastResult: BarcodeResult? = null,
    val batchCount: Int = 0,
    val torchEnabled: Boolean = false
)