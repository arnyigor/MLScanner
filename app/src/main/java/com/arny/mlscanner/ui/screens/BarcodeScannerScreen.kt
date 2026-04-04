package com.arny.mlscanner.ui.screens

import android.Manifest
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arny.mlscanner.data.barcode.analyzer.BarcodeCameraAnalyzer
import com.arny.mlscanner.data.barcode.engine.HybridBarcodeEngine
import com.arny.mlscanner.data.barcode.engine.MLKitBarcodeEngine
import com.arny.mlscanner.data.barcode.engine.ZXingBarcodeEngine
import com.arny.mlscanner.domain.models.barcode.BarcodeContentType
import com.arny.mlscanner.domain.models.barcode.BarcodeResult
import com.arny.mlscanner.domain.models.barcode.BarcodeScanConfig
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel
import java.util.concurrent.Executors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeScannerScreen(
    onNavigateBack: () -> Unit,
    onResultClick: (BarcodeResult) -> Unit,
    viewModel: BarcodeScannerViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val results by viewModel.scannedResults.collectAsState()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Сканер штрихкодов") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.toggleTorch() }) {
                        Icon(
                            imageVector = if (uiState.torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Фонарик"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                CameraPreviewWithOverlay(
                    torchEnabled = uiState.torchEnabled,
                    isPaused = uiState.isPaused,
                    onBarcodeDetected = { detectedResults ->
                        viewModel.onBarcodeDetected(detectedResults)
                    }
                )

                ScanOverlay()

                uiState.lastResult?.let { result ->
                    BarcodeResultBanner(
                        result = result,
                        onClick = { onResultClick(result) },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    )
                }
            }

            if (results.size > 1) {
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxWidth()
                        .padding(8.dp)
                ) {
                    items(results) { result ->
                        BarcodeResultCard(result = result, onClick = { onResultClick(result) })
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                if (uiState.isPaused) {
                    Button(onClick = { viewModel.resumeScanning() }) {
                        Icon(Icons.Default.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Продолжить")
                    }
                }

                if (results.isNotEmpty()) {
                    OutlinedButton(onClick = { viewModel.clearResults() }) {
                        Icon(Icons.Default.Clear, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Очистить (${results.size})")
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreviewWithOverlay(
    torchEnabled: Boolean,
    isPaused: Boolean,
    onBarcodeDetected: (List<BarcodeResult>) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val analyzer = remember {
        BarcodeCameraAnalyzer(
            engine = HybridBarcodeEngine(MLKitBarcodeEngine(), ZXingBarcodeEngine()),
            config = BarcodeScanConfig()
        )
    }

    LaunchedEffect(analyzer) {
        analyzer.results.collect { results ->
            onBarcodeDetected(results)
        }
    }

    LaunchedEffect(isPaused) {
        analyzer.isPaused = isPaused
    }

    DisposableEffect(Unit) {
        onDispose { analyzer.release() }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(1280, 720))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(Executors.newSingleThreadExecutor(), analyzer) }

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                try {
                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
                    camera.cameraControl.enableTorch(torchEnabled)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }, ctx.mainExecutor)

            previewView
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun ScanOverlay() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val scanAreaWidth = size.width * 0.75f
        val scanAreaHeight = size.height * 0.35f
        val left = (size.width - scanAreaWidth) / 2
        val top = (size.height - scanAreaHeight) / 2

        drawRect(color = Color.Black.copy(alpha = 0.5f), size = size)

        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(scanAreaWidth, scanAreaHeight),
            cornerRadius = CornerRadius(16.dp.toPx()),
            blendMode = BlendMode.Clear
        )

        drawRoundRect(
            color = Color.White,
            topLeft = Offset(left, top),
            size = Size(scanAreaWidth, scanAreaHeight),
            cornerRadius = CornerRadius(16.dp.toPx()),
            style = Stroke(width = 3.dp.toPx())
        )

        val cornerLength = 30.dp.toPx()
        val cornerWidth = 4.dp.toPx()
        val accentColor = Color(0xFF4CAF50)

        listOf(
            Offset(left, top + cornerLength) to Offset(left, top),
            Offset(left, top) to Offset(left + cornerLength, top),
            Offset(left + scanAreaWidth - cornerLength, top) to Offset(left + scanAreaWidth, top),
            Offset(left + scanAreaWidth, top) to Offset(left + scanAreaWidth, top + cornerLength),
            Offset(left, top + scanAreaHeight - cornerLength) to Offset(left, top + scanAreaHeight),
            Offset(left, top + scanAreaHeight) to Offset(left + cornerLength, top + scanAreaHeight),
            Offset(left + scanAreaWidth - cornerLength, top + scanAreaHeight) to Offset(left + scanAreaWidth, top + scanAreaHeight),
            Offset(left + scanAreaWidth, top + scanAreaHeight - cornerLength) to Offset(left + scanAreaWidth, top + scanAreaHeight)
        ).forEach { (start, end) ->
            drawLine(accentColor, start, end, strokeWidth = cornerWidth)
        }
    }
}

@Composable
fun BarcodeResultBanner(
    result: BarcodeResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when (result.contentType) {
                    BarcodeContentType.URL -> Icons.Default.Link
                    BarcodeContentType.WIFI -> Icons.Default.Wifi
                    BarcodeContentType.CONTACT_VCARD -> Icons.Default.Person
                    BarcodeContentType.EMAIL -> Icons.Default.Email
                    BarcodeContentType.PHONE -> Icons.Default.Phone
                    BarcodeContentType.PRODUCT -> Icons.Default.ShoppingCart
                    else -> Icons.Default.QrCode
                },
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = result.displayTitle, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(
                    text = result.rawValue.take(50) + if (result.rawValue.length > 50) "…" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
            Icon(Icons.Default.ChevronRight, null)
        }
    }
}

@Composable
fun BarcodeResultCard(result: BarcodeResult, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = result.displayTitle, fontWeight = FontWeight.Bold)
                Text(
                    text = result.rawValue,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                    text = "${result.format.displayName} • ${result.engineSource}",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                )
            }
            Icon(Icons.Default.ContentCopy, null)
        }
    }
}