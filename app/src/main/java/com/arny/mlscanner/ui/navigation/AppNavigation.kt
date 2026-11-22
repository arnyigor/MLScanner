// ============================================================
// ui/navigation/AppNavigation.kt — ИСПРАВЛЕННЫЙ
// ============================================================
package com.arny.mlscanner.ui.navigation

import com.arny.mlscanner.ScanTrace
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.exifinterface.media.ExifInterface
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.map
import com.arny.mlscanner.ui.screens.HistoryScreen
import com.arny.mlscanner.ui.screens.HistoryViewModel
import com.arny.mlscanner.ui.screens.EnhanceScreen
import org.koin.androidx.compose.koinViewModel
import com.arny.mlscanner.ui.screens.CameraScreen
import com.arny.mlscanner.ui.screens.HomeScreen
import com.arny.mlscanner.ui.screens.DocumentEditorRoute
import com.arny.mlscanner.ui.screens.PreprocessingRoute
import com.arny.mlscanner.ui.screens.ResultScreen
import com.arny.mlscanner.ui.screens.ScanStep
import com.arny.mlscanner.ui.screens.ScanUiEvent
import com.arny.mlscanner.ui.screens.ScanViewModel
import com.arny.mlscanner.ui.screens.ScanningScreen
import com.arny.mlscanner.ui.screens.BarcodeScannerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

sealed class Screen(val route: String) {
    data object Home : Screen("home")
    data object Camera : Screen("camera")
    data object Preprocessing : Screen("preprocessing")
    data object DocumentEditor : Screen("document_editor")
    data object Scanning : Screen("scanning")
    data object Result : Screen("result")
    data object BarcodeScanner : Screen("barcode_scanner")
    data object History : Screen("history")
    data object Enhance : Screen("enhance")
}

@Composable
fun AppNavigation(
    navController: NavHostController,
    viewModel: ScanViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                try {
                    val started = ScanTrace.now()
                    val bitmap = loadBitmapFromUri(context, it)
                    ScanTrace.log { "import.ready input=${bitmap.width}x${bitmap.height} elapsedMs=${ScanTrace.elapsed(started)}" }
                    viewModel.onImageCaptured(bitmap, source = "file")
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        "Failed to load image: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // Переходы управляются состоянием. Ключ — кадр + шаг: выбор нового файла
    // с того же Home при неизменном шаге тоже должен провести навигацию.
    LaunchedEffect(uiState.step, uiState.previewBitmap) {
        val currentRoute = navController.currentDestination?.route

        ScanTrace.log { "ui.step value=${uiState.step} from=$currentRoute" }
        when (uiState.step) {
            ScanStep.PREPROCESSING -> {
                // CamScanner-флоу: съёмка/файл → экран предобработки.
                // Из редактора не уходим: поворот/«Применить» там меняют кадр, но экран остаётся.
                if (currentRoute != Screen.Preprocessing.route &&
                    currentRoute != Screen.DocumentEditor.route &&
                    uiState.previewBitmap != null
                ) {
                    navController.navigate(Screen.Preprocessing.route) {
                        when (currentRoute) {
                            Screen.Camera.route -> popUpTo(Screen.Camera.route) { inclusive = true }
                            Screen.Scanning.route -> popUpTo(Screen.Scanning.route) { inclusive = true }
                            else -> Unit
                        }
                        launchSingleTop = true
                    }
                }
            }
            ScanStep.ENHANCE -> if (currentRoute == Screen.Result.route) {
                navController.navigate(Screen.Enhance.route) { launchSingleTop = true }
            }
            ScanStep.RESULT -> {
                if (currentRoute == Screen.Enhance.route) {
                    navController.popBackStack(Screen.Result.route, false)
                } else if (currentRoute != Screen.Result.route) {
                    navController.navigate(Screen.Result.route) {
                        popUpTo(Screen.Scanning.route) { inclusive = true }
                    }
                }
            }
            ScanStep.SCANNING -> {
                if (currentRoute != Screen.Scanning.route) {
                    navController.navigate(Screen.Scanning.route) {
                        when (currentRoute) {
                            Screen.Preprocessing.route ->
                                popUpTo(Screen.Preprocessing.route) { inclusive = true }
                            Screen.DocumentEditor.route ->
                                popUpTo(Screen.DocumentEditor.route) { inclusive = true }
                            else -> Unit
                        }
                        launchSingleTop = true
                    }
                }
            }
            // CAMERA и PREPROCESSING — навигируются явно
            else -> {}
        }
    }

    // One-time events
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is ScanUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is ScanUiEvent.ShowError -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                    navigateToPreprocessing(navController)
                    viewModel.onErrorDismissed()
                }
                is ScanUiEvent.CopiedToClipboard -> {
                    val text = uiState.recognizedText?.formattedText
                        ?: return@collectLatest
                    val clipboard = context.getSystemService(
                        Context.CLIPBOARD_SERVICE
                    ) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("OCR", text)
                    )
                    Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                }
                is ScanUiEvent.ShareText -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, event.text)
                    }
                    context.startActivity(
                        Intent.createChooser(intent, "Share via")
                    )
                }
                is ScanUiEvent.CopyToClipboard -> {
                    copyToClipboard(context, event.text)
                    Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                }
                is ScanUiEvent.CallPhone -> {
                    val intent = Intent(
                        Intent.ACTION_DIAL,
                        Uri.parse("tel:${Uri.encode(event.phoneNumber)}")
                    )
                    startActivitySafely(context, intent)
                }
                is ScanUiEvent.SendEmail -> {
                    val intent = Intent(
                        Intent.ACTION_SENDTO,
                        Uri.parse("mailto:${Uri.encode(event.email)}")
                    )
                    startActivitySafely(context, intent)
                }
                is ScanUiEvent.OpenUrl -> {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(event.url))
                    startActivitySafely(context, intent)
                }
                is ScanUiEvent.NavigateTo -> {
                    when (event.step) {
                        ScanStep.PREPROCESSING -> navigateToPreprocessing(navController)
                        ScanStep.ENHANCE -> navController.navigate(Screen.Enhance.route) {
                            launchSingleTop = true
                        }
                        ScanStep.SCANNING -> navController.navigate(Screen.Scanning.route) {
                            launchSingleTop = true
                        }
                        ScanStep.RESULT -> navController.navigate(Screen.Result.route) {
                            launchSingleTop = true
                        }
                        ScanStep.CAMERA -> navController.navigate(Screen.Camera.route) {
                            launchSingleTop = true
                        }
                    }
                }
                is ScanUiEvent.NavigateBack -> navController.popBackStack()
            }
        }
    }

    // Errors
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
            navigateToPreprocessing(navController)
            viewModel.onErrorDismissed()
        }
    }

    // ═══ NavHost ═══
    NavHost(
        navController = navController,
        startDestination = Screen.Home.route
    ) {
        // ─── Home ───
        composable(Screen.Home.route) {
            val historyViewModel: HistoryViewModel = koinViewModel()
            val historyCount by historyViewModel.scans
                .map { it.size }
                .collectAsStateWithLifecycle(initialValue = 0)
            HomeScreen(
                onCameraClick = {
                    navController.navigate(Screen.Camera.route)
                },
                onGalleryClick = {
                    galleryLauncher.launch("image/*")
                },
                onHistoryClick = {
                    navController.navigate(Screen.History.route)
                },
                historyCount = historyCount
            )
        }

        // ─── Camera ───
        composable(Screen.Camera.route) {
            CameraScreen(
                onImageCaptured = { bitmap ->
                    viewModel.onImageCaptured(bitmap, source = "camera")
                },
                onError = { e ->
                    Toast.makeText(
                        context,
                        "Camera error: ${e.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onBack = {
                    navController.popBackStack()
                }
            )
        }

        // ─── Preprocessing ───
        composable(Screen.Preprocessing.route) {
            PreprocessingRoute(
                viewModel = viewModel,
                navController = navController
            )
        }

        // ─── Выравнивание и улучшение цвета (редактор контура + фильтры) ───
        composable(Screen.DocumentEditor.route) {
            DocumentEditorRoute(
                viewModel = viewModel,
                navController = navController
            )
        }

        // ─── Scanning ───
        composable(Screen.Scanning.route) {
            ScanningScreen(
                viewModel = viewModel,
                onCancel = { navigateToPreprocessing(navController) }
            )
        }

// ─── Result ───
        composable(Screen.Result.route) {
            val recognizedText = uiState.recognizedText
            if (recognizedText != null || uiState.qrCodes.isNotEmpty()) {
                ResultScreen(
                    recognizedText = recognizedText,
                    resultBitmap = uiState.resultBitmap,
                    codes = uiState.qrCodes,
                    onBack = {
                        // ▶ FIX: Возврат на Preprocessing с сохранением состояния
                        viewModel.onReturnToPreprocessing()
                        // Явно переходим на Preprocessing, а не просто popBackStack
                        navController.navigate(Screen.Preprocessing.route) {
                            // Удаляем Result из back stack
                            popUpTo(Screen.Result.route) { inclusive = true }
                            // Избегаем создания дубликатов
                            launchSingleTop = true
                        }
                    },
                    onNewScan = {
                        viewModel.onNewScan()
                        navController.navigate(Screen.Home.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onTextEdited = { viewModel.onTextEdited(it) },
                    onApplyTextChanges = { viewModel.onApplyTextChanges(it) },
                    onToggleFormatMode = { viewModel.onToggleFormatMode() },
                    onPatternClick = { viewModel.onPatternClick(it) },
                    onOpenEnhance = { viewModel.onOpenEnhance() },
                    scanSource = sourceLabel(uiState.scanSource),
                    scannedAt = uiState.scannedAt,
                    originalBitmap = uiState.originalBitmap
                )
            }
        }

        // ─── Enhance (обработка изображения) ───
        composable(Screen.Enhance.route) {
            val source = uiState.enhancedSource ?: uiState.resultBitmap
            if (source == null) {
                androidx.compose.runtime.LaunchedEffect(Unit) { navController.popBackStack() }
            } else {
                EnhanceScreen(
                    source = source,
                    selected = uiState.enhanceFilter,
                    filtered = uiState.enhancedBitmap,
                    isApplying = uiState.isApplyingFilters,
                    onFilterChanged = { viewModel.onEnhanceFilterChanged(it) },
                    onConfirm = viewModel::onConfirmEnhance,
                    onCancel = viewModel::onCancelEnhance
                )
            }
        }

        // ─── History ───
        composable(Screen.History.route) {
            val historyViewModel: HistoryViewModel = koinViewModel()
            HistoryScreen(
                viewModel = historyViewModel,
                onOpen = { entry ->
                    viewModel.openHistoryScan(entry)
                    navController.navigate(Screen.Result.route) {
                        popUpTo(Screen.Home.route)
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        // ─── Barcode Scanner ───
        composable(Screen.BarcodeScanner.route) {
            BarcodeScannerScreen(
                onNavigateBack = { navController.popBackStack() },
                onResultClick = { result ->
                    Toast.makeText(context, "Scanned: ${result.rawValue}", Toast.LENGTH_SHORT).show()
                }
            )
        }
    }
}

// ═══ Утилиты ═══

private fun sourceLabel(source: String): String = when (source) {
    "camera" -> "Камера"
    "file" -> "Файл"
    else -> ""
}


private fun navigateToPreprocessing(navController: NavHostController) {
    val currentRoute = navController.currentDestination?.route
    if (currentRoute == Screen.Preprocessing.route) return

    navController.navigate(Screen.Preprocessing.route) {
        if (currentRoute == Screen.Scanning.route) {
            popUpTo(Screen.Scanning.route) { inclusive = true }
        }
        launchSingleTop = true
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("OCR", text))
}

private fun startActivitySafely(context: Context, intent: Intent) {
    com.arny.mlscanner.ui.screens.openSafely(context, intent, "text.open")
}

private suspend fun loadBitmapFromUri(
    context: Context,
    uri: Uri
): Bitmap = withContext(Dispatchers.IO) {
    val tempFile = File(
        context.cacheDir,
        "gallery_${System.currentTimeMillis()}.jpg"
    )

    try {
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw Exception("Cannot open URI: $uri")

        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath, options)
            ?: throw Exception("Cannot decode image")

        applyExifRotation(bitmap, tempFile.absolutePath)
    } finally {
        tempFile.delete()
    }
}

private fun applyExifRotation(bitmap: Bitmap, path: String): Bitmap {
    val exif = ExifInterface(path)
    val orientation = exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    )

    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> return bitmap
    }

    val matrix = Matrix().apply { postRotate(degrees) }
    val rotated = Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
    )

    ScanTrace.log { "import.exif rotation=$degrees" }
    if (rotated !== bitmap) {
        bitmap.recycle()
    }

    return rotated
}
