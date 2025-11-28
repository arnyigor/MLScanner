package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.arny.mlscanner.domain.models.ScanSettings

private fun dummyBitmap(): Bitmap = createBitmap(200, 200).apply { eraseColor(Color.LTGRAY) }

@Preview(showBackground = true, name = "PreprocessingScreen – default")
@Composable
fun PreprocessingScreenPreview() {
    PreprocessingScreen(
        capturedImage = dummyBitmap(),
        onStartScan = { /* ничего не делаем */ },
        onBack = { /* ничего не делаем */ }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreprocessingScreen(
    capturedImage: Bitmap,
    onStartScan: (ScanSettings) -> Unit,
    onBack: () -> Unit
) {
    var contrastLevel by remember { mutableFloatStateOf(1.0f) }
    var brightnessLevel by remember { mutableFloatStateOf(0f) }
    var sharpenLevel by remember { mutableFloatStateOf(0f) }
    var denoiseEnabled by remember { mutableStateOf(true) }
    var binarizationEnabled by remember { mutableStateOf(true) }
    var autoRotateEnabled by remember { mutableStateOf(true) }

    val settings = ScanSettings(
        contrastLevel = contrastLevel,
        brightnessLevel = brightnessLevel,
        sharpenLevel = sharpenLevel,
        denoiseEnabled = denoiseEnabled,
        autoRotateEnabled = autoRotateEnabled,
        binarizationEnabled = binarizationEnabled
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Preprocessing Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        },
        bottomBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()   // <-- важное изменение
            ) {
                FilledTonalButton(
                    onClick = { onStartScan(settings) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.DocumentScanner, "Scan", modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Scanning")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // Image Preview
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .padding(16.dp)
            ) {
                Image(
                    bitmap = capturedImage.asImageBitmap(),
                    contentDescription = "Captured image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }

            Text(
                text = "Image Enhancement",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Contrast Slider
            SliderControl(
                label = "Contrast",
                value = contrastLevel,
                onValueChange = { contrastLevel = it },
                valueRange = 0.5f..2.0f,
                valueDisplay = "${(contrastLevel * 100).toInt()}%"
            )

            // Brightness Slider
            SliderControl(
                label = "Brightness",
                value = brightnessLevel,
                onValueChange = { brightnessLevel = it },
                valueRange = -100f..100f,
                valueDisplay = brightnessLevel.toInt().toString()
            )

            // Sharpen Slider
            SliderControl(
                label = "Sharpness",
                value = sharpenLevel,
                onValueChange = { sharpenLevel = it },
                valueRange = 0f..2.0f,
                valueDisplay = "${(sharpenLevel * 100).toInt()}%"
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Processing Options",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Toggle switches
            SwitchControl(
                label = "Noise Reduction",
                description = "Remove image noise for better accuracy",
                checked = denoiseEnabled,
                onCheckedChange = { denoiseEnabled = it }
            )

            SwitchControl(
                label = "Binarization",
                description = "Convert to black & white for better text detection",
                checked = binarizationEnabled,
                onCheckedChange = { binarizationEnabled = it }
            )

            SwitchControl(
                label = "Auto-Rotate",
                description = "Automatically correct image orientation",
                checked = autoRotateEnabled,
                onCheckedChange = { autoRotateEnabled = it }
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun SliderControl(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    valueDisplay: String
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = valueDisplay,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

@Composable
fun SwitchControl(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
