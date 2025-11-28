package com.arny.mlscanner.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arny.mlscanner.domain.models.RecognizedText
import com.arny.mlscanner.domain.models.strings.StringHolder
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


data class ResultUiState(
    val recognizedText: RecognizedText? = null,
    val editableText: String = ""
)

// ------------------------------------------------------------------
// 3️⃣ UI‑Events – one‑time actions (toast, navigation …)
// ------------------------------------------------------------------
sealed interface ResultUiEvent {
    data class ShowError(val message: StringHolder) : ResultUiEvent
}

class ResultViewModel : ViewModel() {

    // --- StateFlow для UI‑стейта ---------------------------------
    private val _uiState = MutableStateFlow(ResultUiState())
    val uiState: StateFlow<ResultUiState> get() = _uiState.asStateFlow()

    // --- Channel для one‑time событий -----------------------------
    private val _eventChannel = Channel<ResultUiEvent>(Channel.BUFFERED)
    val eventFlow: Flow<ResultUiEvent> = _eventChannel.receiveAsFlow()

    fun setRecognizedText(text: RecognizedText) {
        _uiState.update { it.copy(recognizedText = text, editableText = text.formattedText) }
    }

    fun updateEditableText(newText: String) {
        _uiState.update { it.copy(editableText = newText) }
    }

    // Пример helper‑метода для показа ошибки
    private fun showError(message: String) {
        viewModelScope.launch { _eventChannel.send(ResultUiEvent.ShowError(StringHolder.Text(message))) }
    }
}