package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.ui.navigation.Screen
import org.koin.androidx.compose.koinViewModel

@Preview(showBackground = true, name = "PreprocessingScreen – default")
@Composable
fun PreprocessingScreenPreview() {
    PreprocessingScreen(
        previewBitmap = null,
        onStartScan = { _, _ -> },
        onUpdateSettings = { },
        onBack = { }
    )
}

// Переименовали в Route, чтобы устранить конфликт сигнатур
@Composable
fun PreprocessingRoute(
    viewModel: ScanViewModel = koinViewModel(),
    navController: NavHostController
) {
    val uiState by viewModel.uiState.collectAsState()
    val previewBitmap = uiState.previewBitmap

    if (previewBitmap != null) {
        PreprocessingScreen(
            previewBitmap = previewBitmap,
            settings = uiState.settings,
            isApplyingFilters = uiState.isApplyingFilters,
            onUpdateSettings = viewModel::onSettingsChanged,
            onCropChanged = viewModel::onCropChanged,
            onStartScan = { _, cropRect ->
                if (cropRect != null) {
                    viewModel.onCropChanged(cropRect)
                }
                viewModel.onStartScanning()
                navController.navigate(Screen.Scanning.route)
            },
            onBack = { navController.popBackStack() }
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Processing image...", modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreprocessingScreen(
    previewBitmap: Bitmap?,
    settings: ScanSettings = ScanSettings.DEFAULT,
    isApplyingFilters: Boolean = false,
    onUpdateSettings: (ScanSettings) -> Unit = {},
    onCropChanged: (CropRect) -> Unit = {},
    onStartScan: (ScanSettings, CropRect?) -> Unit = { _, _ -> },
    onBack: () -> Unit = {}
) {
    var contrastLevel by remember(settings) { mutableFloatStateOf(settings.contrastLevel) }
    var brightnessLevel by remember(settings) { mutableFloatStateOf(settings.brightnessLevel) }
    var sharpenLevel by remember(settings) { mutableFloatStateOf(settings.sharpenLevel) }
    var denoiseEnabled by remember(settings) { mutableStateOf(settings.denoiseEnabled) }
    var binarizationEnabled by remember(settings) { mutableStateOf(settings.binarizationEnabled) }
    var autoRotateEnabled by remember(settings) { mutableStateOf(settings.autoRotateEnabled) }
    var cropRect by remember { mutableStateOf<CropRect?>(null) }

    fun emitSettings() {
        onUpdateSettings(
            ScanSettings(
                contrastLevel = contrastLevel,
                brightnessLevel = brightnessLevel,
                sharpenLevel = sharpenLevel,
                denoiseEnabled = denoiseEnabled,
                autoRotateEnabled = autoRotateEnabled,
                binarizationEnabled = binarizationEnabled
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Image Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        contrastLevel = 1.0f
                        brightnessLevel = 0f
                        sharpenLevel = 0f
                        denoiseEnabled = false
                        binarizationEnabled = false
                        autoRotateEnabled = true
                        emitSettings()
                    }) {
                        Icon(Icons.Default.Refresh, "Reset")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .padding(16.dp)
            ) {
                if (previewBitmap != null && !previewBitmap.isRecycled) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        CropImageView(
                            bitmap = previewBitmap.asImageBitmap(),
                            modifier = Modifier.fillMaxSize(),
                            onCropChanged = {
                                cropRect = it
                                onCropChanged(it)
                            }
                        )

                        androidx.compose.animation.AnimatedVisibility(
                            visible = isApplyingFilters,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.padding(8.dp).size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "No image available",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            FilledTonalButton(
                onClick = {
                    val currentSettings = ScanSettings(
                        contrastLevel = contrastLevel,
                        brightnessLevel = brightnessLevel,
                        sharpenLevel = sharpenLevel,
                        denoiseEnabled = denoiseEnabled,
                        autoRotateEnabled = autoRotateEnabled,
                        binarizationEnabled = binarizationEnabled
                    )
                    onStartScan(currentSettings, cropRect)
                },
                enabled = previewBitmap != null && !isApplyingFilters,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Icon(
                    Icons.Default.DocumentScanner, "Scan",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Start Scanning")
            }

            Text(
                text = "Image Enhancement",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            SliderControl(
                label = "Contrast",
                value = contrastLevel,
                onValueChange = { contrastLevel = it },
                onValueChangeFinished = { emitSettings() },
                valueRange = 0.5f..2.0f,
                valueDisplay = "${(contrastLevel * 100).toInt()}%"
            )

            SliderControl(
                label = "Brightness",
                value = brightnessLevel,
                onValueChange = { brightnessLevel = it },
                onValueChangeFinished = { emitSettings() },
                valueRange = -100f..100f,
                valueDisplay = brightnessLevel.toInt().toString()
            )

            SliderControl(
                label = "Sharpness",
                value = sharpenLevel,
                onValueChange = { sharpenLevel = it },
                onValueChangeFinished = { emitSettings() },
                valueRange = 0f..2.0f,
                valueDisplay = "${(sharpenLevel * 100).toInt()}%"
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                text = "Processing Options",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            SwitchControl(
                label = "Noise Reduction",
                description = "Remove image noise for better accuracy",
                checked = denoiseEnabled,
                onCheckedChange = {
                    denoiseEnabled = it
                    emitSettings()
                }
            )

            SwitchControl(
                label = "Binarization",
                description = "Convert to black & white for documents",
                checked = binarizationEnabled,
                onCheckedChange = {
                    binarizationEnabled = it
                    emitSettings()
                }
            )

            SwitchControl(
                label = "Auto-Rotate",
                description = "Automatically correct orientation",
                checked = autoRotateEnabled,
                onCheckedChange = {
                    autoRotateEnabled = it
                    emitSettings()
                }
            )

            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
fun SliderControl(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
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
            onValueChangeFinished = onValueChangeFinished,
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