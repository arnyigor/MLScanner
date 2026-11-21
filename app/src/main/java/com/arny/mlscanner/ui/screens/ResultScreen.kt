package com.arny.mlscanner.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.selection.selectable
import com.arny.mlscanner.ScanTrace
import com.arny.mlscanner.data.ocr.postprocessing.PatternRecognizer
import com.arny.mlscanner.data.ocr.postprocessing.TextFormatter
import com.arny.mlscanner.domain.models.RecognizedText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ResultScreenPreviewPlaceholder() {}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    recognizedText: RecognizedText?,
    resultBitmap: Bitmap?,
    onBack: () -> Unit,
    onNewScan: () -> Unit,
    onCopy: () -> Unit = {},
    onShare: () -> Unit = {},
    onTextEdited: (String) -> Unit = {},
    onApplyTextChanges: (String) -> Unit = {},
    onToggleFormatMode: () -> Unit = {},
    onOpenEnhance: () -> Unit = {},
    onPatternClick: (TextFormatter.ClickAction) -> Unit = {},
    codes: List<com.arny.mlscanner.domain.models.barcode.BarcodeResult> = emptyList(),
    /** Источник кадра и время скана — только подпись, без логики. */
    scanSource: String = "",
    scannedAt: Long = 0L,
    /** Оригинальный кадр для сравнения в просмотрщике. */
    originalBitmap: Bitmap? = null
) {
    val context = LocalContext.current
    var editableText by remember { mutableStateOf(recognizedText?.formattedText.orEmpty()) }
    var viewerOpen by remember { mutableStateOf(false) }
    val modes = listOf(
        TextFormatter.FormatMode.RAW to "Raw",
        TextFormatter.FormatMode.MARKDOWN to "Markdown"
    )

    LaunchedEffect(recognizedText?.formattedText, recognizedText?.formatMode) {
        editableText = recognizedText?.formattedText.orEmpty()
    }

    val timeLabel = if (scannedAt > 0L) {
        SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(scannedAt))
    } else ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Результат")
                        if (timeLabel.isNotEmpty()) {
                            Text(
                                listOf(scanSource, timeLabel).filter { it.isNotBlank() }
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Править") }
                },
                actions = {
                    if (resultBitmap != null) IconButton(onClick = onOpenEnhance) {
                        Icon(Icons.Default.AutoFixHigh, "Обработка изображения")
                    }
                    if (recognizedText != null) IconButton(onClick = {
                        copyToClipboardWithToast(context, editableText)
                        onCopy()
                    }) {
                        Icon(Icons.Default.ContentCopy, "Копировать текст")
                    }
                    if (recognizedText != null) IconButton(onClick = {
                        shareText(context, editableText)
                        onShare()
                    }) {
                        Icon(Icons.Default.Share, "Поделиться")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewScan,
                icon = { Icon(Icons.Default.CameraAlt, "Новый скан") },
                text = { Text("Новый скан") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ═══ Коды первыми: они результат, а не приложение к тексту ═══
            if (codes.isNotEmpty()) {
                DetectedCodes(codes)
            }

            // ═══ Статистика одной строкой, без отдельной карточки ═══
            if (recognizedText != null) {
                Text(
                    text = "%d%% · %s · %d блок(а/ов)".format(
                        (recognizedText.confidence * 100).toInt(),
                        recognizedText.detectedLanguage.uppercase(),
                        recognizedText.blocks.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // ═══ Снимок: открыть на весь экран для сравнения ═══
            if (resultBitmap != null && !resultBitmap.isRecycled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .clickable {
                            ScanTrace.log { "viewer.open hasOriginal=${originalBitmap != null}" }
                            viewerOpen = true
                        },
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column {
                        androidx.compose.foundation.Image(
                            bitmap = resultBitmap.asImageBitmap(),
                            contentDescription = "Скан, нажмите чтобы рассмотреть",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentScale = ContentScale.Crop
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Image,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                "Нажмите, чтобы рассмотреть" +
                                    if (originalBitmap != null) " и сравнить с оригиналом" else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            if (recognizedText == null) return@Column

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        selected = recognizedText.formatMode == mode,
                        onClick = {
                            if (recognizedText.formatMode != mode) {
                                onToggleFormatMode()
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = modes.size
                        )
                    ) {
                        Text(label)
                    }
                }
            }

            // ═══ Заметная кнопка копирования: то, что сейчас на экране ═══
            TextButton(
                onClick = {
                    copyToClipboardWithToast(context, editableText)
                    onCopy()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Копировать текст (${editableText.length} симв.)")
            }

            val clickableElements = recognizedText.clickableElements
            if (clickableElements.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "Найдено в тексте",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    clickableElements.sortedBy {
                        when (it.type) {
                            PatternRecognizer.PatternType.URL -> 0
                            PatternRecognizer.PatternType.PHONE,
                            PatternRecognizer.PatternType.EMAIL -> 1
                            else -> 2
                        }
                    }.take(6).forEach { element ->
                        TextButton(
                            onClick = { onPatternClick(element.action) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${patternLabel(element.type)}: ${element.displayText}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }

            // ═══ Текстовое поле или Markdown ═══
            if (recognizedText.formatMode == TextFormatter.FormatMode.RAW) {
                androidx.compose.material3.OutlinedTextField(
                    value = editableText,
                    onValueChange = {
                        editableText = it
                        onTextEdited(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp
                    ),
                    label = { Text("Текст (можно править)") },
                    placeholder = { Text("Текст не распознан") },
                    trailingIcon = {
                        IconButton(onClick = { onApplyTextChanges(editableText) }) {
                            Icon(Icons.Default.Refresh, "Пересчитать")
                        }
                    },
                    minLines = 10,
                    maxLines = 30
                )
            } else {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = editableText,
                                style = TextStyle(
                                    fontFamily = FontFamily.Default,
                                    fontSize = 16.sp,
                                    lineHeight = 24.sp
                                )
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(96.dp))
        }
    }

    if (viewerOpen && resultBitmap != null) {
        ImageViewerDialog(
            original = originalBitmap,
            processed = resultBitmap,
            onDismiss = { viewerOpen = false }
        )
    }
}

private fun patternLabel(type: PatternRecognizer.PatternType): String {
    return when (type) {
        PatternRecognizer.PatternType.PHONE -> "Телефон"
        PatternRecognizer.PatternType.EMAIL -> "Email"
        PatternRecognizer.PatternType.URL -> "Ссылка"
        PatternRecognizer.PatternType.DATE -> "Дата"
        PatternRecognizer.PatternType.TIME -> "Время"
        PatternRecognizer.PatternType.MONEY -> "Сумма"
        PatternRecognizer.PatternType.INN -> "ИНН"
        PatternRecognizer.PatternType.CARD -> "Карта"
    }
}

private fun shareText(context: android.content.Context, text: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Поделиться текстом"))
}
