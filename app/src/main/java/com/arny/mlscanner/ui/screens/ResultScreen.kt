package com.arny.mlscanner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.arny.mlscanner.domain.models.RecognizedText

@Preview(showBackground = true)
@Composable
fun ResultContentPreview() {
    // --- фиктивные данные ---------------------------------------
    val dummyText = RecognizedText(
        formattedText = "Hello World\nSecond line",
        confidence = 0.92f,
        detectedLanguage = "en",
        originalText = "Hello World",
        blocks = emptyList()
    )

    // --- preview без ViewModel -----------------------------------
    ResultScreen(
        recognizedText = dummyText,          // в preview никаких событий
        onBack = {},
        onNewScan = {}       // ничего не делаем при редактировании
    )
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    recognizedText: RecognizedText,
    onBack: () -> Unit,
    onNewScan: () -> Unit
) {
    val context = LocalContext.current
    var editableText by remember { mutableStateOf(recognizedText.formattedText) }
    var showOriginal by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Result") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Copy button
                    IconButton(onClick = {
                        copyToClipboard(context, editableText)
                    }) {
                        Icon(Icons.Default.ContentCopy, "Copy")
                    }

                    // Share button
                    IconButton(onClick = {
                        shareText(context, editableText)
                    }) {
                        Icon(Icons.Default.Share, "Share")
                    }

                    // More options
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { expanded = true }) {
                            Icon(Icons.Default.MoreVert, "More")
                        }
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(if (showOriginal) "Show formatted" else "Show original") },
                                onClick = {
                                    showOriginal = !showOriginal
                                    editableText = if (showOriginal) {
                                        recognizedText.originalText
                                    } else {
                                        recognizedText.formattedText
                                    }
                                    expanded = false
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.SwapHoriz, null)
                                }
                            )
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNewScan,
                icon = { Icon(Icons.Default.Camera, "New scan") },
                text = { Text("New Scan") }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Stats card
            StatsCard(
                confidence = recognizedText.confidence,
                language = recognizedText.detectedLanguage,
                blocksCount = recognizedText.blocks.size,
                modifier = Modifier.padding(16.dp)
            )

            // Editable text field
            OutlinedTextField(
                value = editableText,
                onValueChange = { editableText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp
                ),
                label = { Text("Recognized Text") },
                placeholder = { Text("Edit text here...") }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun StatsCard(
    confidence: Float,
    language: String,
    blocksCount: Int,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatItem(
                label = "Confidence",
                value = "${(confidence * 100).toInt()}%",
                icon = Icons.Default.CheckCircle
            )
            StatItem(
                label = "Language",
                value = language.uppercase(),
                icon = Icons.Default.Language
            )
            StatItem(
                label = "Blocks",
                value = blocksCount.toString(),
                icon = Icons.Default.GridOn
            )
        }
    }
}

@Composable
fun StatItem(
    label: String,
    value: String,
    icon: ImageVector
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Helper functions
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Recognized Text", text)
    clipboard.setPrimaryClip(clip)
}

private fun shareText(context: Context, text: String) {
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(shareIntent, "Share text via"))
}
