package com.arny.mlscanner.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arny.mlscanner.ScanTrace
import com.arny.mlscanner.data.history.ScanHistoryRepository
import com.arny.mlscanner.data.history.ScanWithCodes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Список сохранённых сканов. Данные только на телефоне, облако исключено. */
class HistoryViewModel(
    private val history: ScanHistoryRepository
) : ViewModel() {

    val scans: StateFlow<List<ScanWithCodes>> = history.scans
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(scanId: Long) {
        viewModelScope.launch { history.delete(scanId) }
    }

    fun clearAll() {
        viewModelScope.launch { history.clearAll() }
    }

    fun formatRelative(createdMillis: Long): String {
        val age = System.currentTimeMillis() - createdMillis
        return when {
            age < 60_000L -> "только что"
            age < 3_600_000L -> "${age / 60_000} мин назад"
            age < 86_400_000L -> "${age / 3_600_000} ч назад"
            else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
                .format(java.util.Date(createdMillis))
        }
    }

    fun sourceLabel(source: String): String = when (source) {
        "camera" -> "Камера"
        "file" -> "Файл"
        "editor" -> "Правка"
        else -> ""
    }

    fun textPreview(text: String): String =
        text.trim().replace('\n', ' ').take(90).let { if (text.length > 90) "$it…" else it }
}
