package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.arny.mlscanner.domain.models.OcrEngineType
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
            imageVersion = uiState.imageVersion,
            onUpdateSettings = viewModel::onSettingsChanged,
            quadActive = uiState.cropQuad?.let { q ->
                !q.coversFrame(previewBitmap.width, previewBitmap.height)
            } == true,
            onCropChanged = { rect ->
                // Старый прямоугольник становится контуром: единый кроп для предпросмотра и скана.
                viewModel.onCropChanged(rect.toQuad(previewBitmap.width, previewBitmap.height))
            },
            onStartScan = { _, cropRect ->
                if (cropRect != null) {
                    viewModel.onCropChanged(cropRect.toQuad(previewBitmap.width, previewBitmap.height))
                }
                // QR и текст распознаются вместе (autoDetectQr по умолчанию); навигация — за step.
                viewModel.onStartScanning()
            },
            onRotate = { degrees ->
                viewModel.rotateImage(degrees)
            },
            onOpenEditor = {
                navController.navigate(Screen.DocumentEditor.route)
            },
            onBack = {
                viewModel.onReturnToPreprocessing()
                navController.popBackStack()
            }
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

/** Прямоугольник кропа -> четырёхугольник в пикселях кадра (с ограничением рамками). */
private fun CropRect.toQuad(width: Int, height: Int): CropQuad {
    val l = left.coerceIn(0f, width.toFloat())
    val t = top.coerceIn(0f, height.toFloat())
    val r = (left + this.width).coerceIn(l, width.toFloat())
    val b = (top + this.height).coerceIn(t, height.toFloat())
    return CropQuad(
        android.graphics.PointF(l, t),
        android.graphics.PointF(r, t),
        android.graphics.PointF(r, b),
        android.graphics.PointF(l, b)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreprocessingScreen(
    previewBitmap: Bitmap?,
    settings: ScanSettings = ScanSettings.DEFAULT,
    isApplyingFilters: Boolean = false,
    onUpdateSettings: (ScanSettings) -> Unit = {},
    quadActive: Boolean = false,
    imageVersion: Long = 0L,
    onCropChanged: (CropRect) -> Unit = {},
    onStartScan: (ScanSettings, CropRect?) -> Unit = { _, _ -> },
    onRotate: (Float) -> Unit = {},
    onOpenEditor: () -> Unit = {},
    onBack: () -> Unit = {}
) {
    var contrastLevel by remember(settings) { mutableFloatStateOf(settings.contrastLevel) }
    var brightnessLevel by remember(settings) { mutableFloatStateOf(settings.brightnessLevel) }
    var sharpenLevel by remember(settings) { mutableFloatStateOf(settings.sharpenLevel) }
    var denoiseEnabled by remember(settings) { mutableStateOf(settings.denoiseEnabled) }
    var binarizationEnabled by remember(settings) { mutableStateOf(settings.binarizationEnabled) }
    var autoRotateEnabled by remember(settings) { mutableStateOf(settings.autoRotateEnabled) }
    var handwrittenMode by remember(settings) { mutableStateOf(settings.handwrittenMode) }
    var engineType by remember(settings) { mutableStateOf(settings.engineType) }
    // Рамка в координатах конкретного кадра: новая версия кадра — рамка сбрасывается.
    var cropRect by remember(imageVersion) { mutableStateOf<CropRect?>(null) }

    // ▶ НОВОЕ: Режим превью: компактный или полноэкранный
    var expandedPreview by remember { mutableStateOf(false) }

    // Версия кадра из ViewModel: растёт при новом фото, повороте и «Применить» в редакторе.
    val imageId = imageVersion

    fun emitSettings() {
        onUpdateSettings(
            ScanSettings(
                contrastLevel = contrastLevel,
                brightnessLevel = brightnessLevel,
                sharpenLevel = sharpenLevel,
                denoiseEnabled = denoiseEnabled,
                autoRotateEnabled = autoRotateEnabled,
                binarizationEnabled = binarizationEnabled,
                handwrittenMode = handwrittenMode,
                engineType = engineType
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
                    // Полный экран для превью
                    IconButton(onClick = { expandedPreview = !expandedPreview }) {
                        Icon(
                            if (expandedPreview) Icons.Default.CropFree
                            else Icons.Default.Fullscreen,
                            "Toggle preview size"
                        )
                    }
                    // Reset
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
            // ═══ Превью изображения ═══
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // ▶ FIX: Динамическая высота превью
                    .height(if (expandedPreview) 500.dp else 300.dp)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                if (previewBitmap != null && !previewBitmap.isRecycled) {
                    CropRectImageView(
                        bitmap = previewBitmap.asImageBitmap(),
                        modifier = Modifier.fillMaxSize(),
                        imageId = imageId,
                        minCropSize = 20f, // ▶ FIX: Минимум 20px
                        onCropChanged = { cropRect = it }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
            }

            // ═══ Кнопки поворота ═══
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    onRotate(-90f)
                }) {
                    Icon(Icons.Default.RotateLeft, "Rotate left 90°")
                }
                Text(
                    "Rotate",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                IconButton(onClick = {
                    onRotate(90f)
                }) {
                    Icon(Icons.Default.RotateRight, "Rotate right 90°")
                }
            }

            // ═══ Выбор режима сканирования ═══
            Text(
                "Scan Mode",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                OcrEngineType.entries.forEachIndexed { index, engine ->
                    SegmentedButton(
                        selected = engineType == engine,
                        onClick = {
                            engineType = engine
                            emitSettings()
                        },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = OcrEngineType.entries.size
                        )
                    ) {
                        Text(
                            engine.displayName,
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
            }

            // ═══ Выравнивание и улучшение цвета (отдельный редактор) ═══
            FilledTonalButton(
                onClick = onOpenEditor,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Tune, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Выравнивание и улучшение цвета")
            }

            if (quadActive) {
                Text(
                    "Действует контур из «Выравнивания» — он же пойдёт на распознавание",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            // ═══ Кнопка сканирования ═══
            FilledTonalButton(
                onClick = { onStartScan(settings, cropRect) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Icon(
                    if (engineType == OcrEngineType.BARCODE) Icons.Default.QrCodeScanner
                    else Icons.Default.DocumentScanner,
                    "Scan",
                    Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (engineType == OcrEngineType.BARCODE) "Scan Barcode"
                    else "Start Scanning"
                )
            }

            // Скрываем фильтры в режиме Barcode
            if (engineType != OcrEngineType.BARCODE) {
                Text(
                    "Image Enhancement",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )

                SliderControl(
                    label = "Contrast",
                    value = contrastLevel,
                    onValueChange = { newValue -> contrastLevel = newValue },
                    onValueChangeFinished = { emitSettings() },
                    valueRange = 0.5f..2.0f,
                    valueDisplay = "${(contrastLevel * 100).toInt()}%"
                )

                SliderControl(
                    label = "Brightness",
                    value = brightnessLevel,
                    onValueChange = { newValue -> brightnessLevel = newValue },
                    onValueChangeFinished = { emitSettings() },
                    valueRange = -100f..100f,
                    valueDisplay = brightnessLevel.toInt().toString()
                )

                SliderControl(
                    label = "Sharpness",
                    value = sharpenLevel,
                    onValueChange = { newValue -> sharpenLevel = newValue },
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
                    onCheckedChange = { newValue ->
                        denoiseEnabled = newValue
                        emitSettings()
                    }
                )

                SwitchControl(
                    label = "Binarization",
                    description = "Convert to black & white for documents",
                    checked = binarizationEnabled,
                    onCheckedChange = { newValue ->
                        binarizationEnabled = newValue
                        emitSettings()
                    }
                )

                // ▶ НОВОЕ: Рукописный текст
                SwitchControl(
                    label = "Handwritten Text",
                    description = "Optimize for handwritten text recognition",
                    checked = handwrittenMode,
                    onCheckedChange = { newValue ->
                        handwrittenMode = newValue
                        if (newValue) {
                            contrastLevel = 1.8f
                            binarizationEnabled = true
                        }
                        emitSettings()
                    }
                )

                SwitchControl(
                    label = "Auto-Rotate",
                    description = "Automatically correct orientation",
                    checked = autoRotateEnabled,
                    onCheckedChange = { newValue ->
                        autoRotateEnabled = newValue
                        emitSettings()
                    }
                )
            }

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
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(valueDisplay, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
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
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}