package com.arny.mlscanner.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import com.arny.mlscanner.domain.models.RecognizedText

@Preview(showBackground = true)
@Composable
fun ResultContentPreview() {
    val dummyText = RecognizedText(
        formattedText = "Hello World\nSecond line",
        confidence = 0.92f,
        detectedLanguage = "en",
        originalText = "Hello World",
        blocks = emptyList()
    )

    ResultScreen(
        recognizedText = dummyText,
        resultBitmap = null,
        onBack = { },
        onNewScan = { }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    recognizedText: RecognizedText,
    resultBitmap: Bitmap?,
    onBack: () -> Unit,
    onNewScan: () -> Unit,
    onCopy: () -> Unit = {},
    onShare: () -> Unit = {},
    onTextEdited: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var editableText by remember { mutableStateOf(recognizedText.formattedText) }
    var showOriginal by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Result") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        copyToClipboard(context, editableText)
                        onCopy()
                    }) {
                        Icon(Icons.Default.ContentCopy, "Copy")
                    }

                    IconButton(onClick = {
                        shareText(context, editableText)
                        onShare()
                    }) {
                        Icon(Icons.Default.Share, "Share")
                    }

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
            StatsCard(
                confidence = recognizedText.confidence,
                language = recognizedText.detectedLanguage,
                blocksCount = recognizedText.blocks.size,
                modifier = Modifier.padding(16.dp)
            )

            if (resultBitmap != null && !resultBitmap.isRecycled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Image(
                        bitmap = resultBitmap.asImageBitmap(),
                        contentDescription = "Scanned Image",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            OutlinedTextField(
                value = editableText,
                onValueChange = {
                    editableText = it
                    onTextEdited(it)
                },
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
                placeholder = { Text("No text recognized") }
            )

            Spacer(Modifier.height(80.dp))
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
