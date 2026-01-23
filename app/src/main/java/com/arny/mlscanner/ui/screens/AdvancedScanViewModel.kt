package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arny.mlscanner.data.matching.FieldMapping
import com.arny.mlscanner.data.matching.MatchingEngine
import com.arny.mlscanner.data.pdf.PdfRedactionEngine
import com.arny.mlscanner.data.pdf.RedactionMask
import com.arny.mlscanner.domain.models.MatchingResult
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.RedactionResult
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.models.TextBox
import com.arny.mlscanner.domain.usecases.AdvancedRecognizeTextUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Расширенная ViewModel для сканирования с использованием всех компонентов SecureField MVP
 * В соответствии с требованиями TECH.md:
 * - OCR Engine
 * - Smart Redaction
 * - Data Matching
 * - Безопасность и лицензирование
 */
class AdvancedScanViewModel(
    private val advancedRecognizeTextUseCase: AdvancedRecognizeTextUseCase,
    private val pdfRedactionEngine: PdfRedactionEngine,
    private val matchingEngine: MatchingEngine
) : ViewModel() {

    private val _uiState = MutableStateFlow(AdvancedScanUiState())
    val uiState: StateFlow<AdvancedScanUiState> = _uiState

    init {
        // Инициализация OCR-движка при создании ViewModel
        viewModelScope.launch {
            val initialized = advancedRecognizeTextUseCase.initializeOcrEngine()
            _uiState.value = _uiState.value.copy(isOcrEngineInitialized = initialized)
        }
    }

    /**
     * Выполнение OCR-распознавания
     */
    fun recognizeText(bitmap: Bitmap, settings: ScanSettings) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

            try {
                val result = advancedRecognizeTextUseCase.execute(bitmap, settings)

                _uiState.value = _uiState.value.copy(
                    ocrResult = result,
                    isLoading = false
                )

                // Автоматически выполнить сопоставление, если есть данные
                if (result.textBoxes.isNotEmpty()) {
                    performMatching(result)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    errorMessage = e.message ?: "Unknown error occurred"
                )
            }
        }
    }

    /**
     * Выполнение сопоставления с базой данных
     */
    private fun performMatching(ocrResult: OcrResult) {
        viewModelScope.launch {
            val texts = ocrResult.textBoxes.map { it.text }
            val matchingResult = matchingEngine.batchMatch(texts)

            _uiState.value = _uiState.value.copy(
                matchingResult = matchingResult
            )
        }
    }

    /**
     * Создание защищенного PDF с маскированием
     */
    fun createRedactedPdf(
        sourceImagePath: String,
        textBoxes: List<TextBox>,
        redactionMask: RedactionMask,
        outputPath: String
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCreatingPdf = true)

            try {
                val result = pdfRedactionEngine.redactAndSavePdf(
                    sourceImagePath,
                    textBoxes,
                    redactionMask,
                    outputPath
                )

                _uiState.value = _uiState.value.copy(
                    redactionResult = result,
                    isCreatingPdf = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isCreatingPdf = false,
                    errorMessage = "Error creating redacted PDF: ${e.message}"
                )
            }
        }
    }

    /**
     * Импорт справочника из CSV
     */
    suspend fun importFromCsv(csvFile: File, mapping: FieldMapping): Boolean {
        return matchingEngine.importFromCsv(csvFile, mapping)
    }

    /**
     * Импорт справочника из JSON
     */
    suspend fun importFromJson(jsonFile: File, mapping: FieldMapping): Boolean {
        return matchingEngine.importFromJson(jsonFile, mapping)
    }

    /**
     * Получение количества элементов в базе сопоставления
     */
    suspend fun getItemCount(): Int = matchingEngine.getItemCount()

    override fun onCleared() {
        super.onCleared()
        // Освобождение ресурсов OCR-движка
        advancedRecognizeTextUseCase.cleanup()
    }
}

/**
 * Состояние UI для AdvancedScanViewModel
 */
data class AdvancedScanUiState(
    val isLoading: Boolean = false,
    val isCreatingPdf: Boolean = false,
    val isOcrEngineInitialized: Boolean = false,
    val ocrResult: OcrResult? = null,
    val matchingResult: MatchingResult? = null,
    val redactionResult: RedactionResult? = null,
    val errorMessage: String? = null
)