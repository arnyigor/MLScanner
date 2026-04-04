// ============================================================
// ui/navigation/AppNavigation.kt — ИСПРАВЛЕННЫЙ
// ============================================================
package com.arny.mlscanner.ui.navigation

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
import com.arny.mlscanner.ui.screens.CameraScreen
import com.arny.mlscanner.ui.screens.HomeScreen
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
    data object Scanning : Screen("scanning")
    data object Result : Screen("result")
    data object BarcodeScanner : Screen("barcode_scanner")
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
                    val bitmap = loadBitmapFromUri(context, it)
                    viewModel.onImageCaptured(bitmap)
                    // ▶ FIX: Навигация ЯВНО после захвата
                    navController.navigate(Screen.Preprocessing.route)
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

    // ▶ FIX: State-driven навигация ТОЛЬКО для Scanning→Result
    // Preprocessing навигируется явно из callback
    LaunchedEffect(uiState.step) {
        val currentRoute = navController.currentDestination?.route

        when (uiState.step) {
            ScanStep.RESULT -> {
                if (currentRoute != Screen.Result.route) {
                    navController.navigate(Screen.Result.route) {
                        popUpTo(Screen.Scanning.route) { inclusive = true }
                    }
                }
            }
            ScanStep.SCANNING -> {
                if (currentRoute != Screen.Scanning.route) {
                    navController.navigate(Screen.Scanning.route)
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
                is ScanUiEvent.NavigateTo -> {}
                is ScanUiEvent.NavigateBack -> navController.popBackStack()
            }
        }
    }

    // Errors
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
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
            HomeScreen(
                onCameraClick = {
                    navController.navigate(Screen.Camera.route)
                },
                onGalleryClick = {
                    galleryLauncher.launch("image/*")
                }
            )
        }

        // ─── Camera ───
        composable(Screen.Camera.route) {
            CameraScreen(
                onImageCaptured = { bitmap ->
                    viewModel.onImageCaptured(bitmap)
                    // ▶ FIX: Навигация ЯВНО после фото
                    navController.navigate(Screen.Preprocessing.route)
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

        // ─── Scanning ───
        composable(Screen.Scanning.route) {
            ScanningScreen(
                viewModel = viewModel,
                onCancel = {
                    navController.popBackStack()
                }
            )
        }

        // ─── Result ───
        composable(Screen.Result.route) {
            val recognizedText = uiState.recognizedText
            if (recognizedText != null) {
                ResultScreen(
                    recognizedText = recognizedText,
                    resultBitmap = uiState.resultBitmap,
                    onBack = {
                        viewModel.onReturnToPreprocessing()
                        navController.popBackStack()
                    },
                    onNewScan = {
                        viewModel.onNewScan()
                        navController.navigate(Screen.Home.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onCopy = { viewModel.onCopyText() },
                    onShare = { viewModel.onShareText() },
                    onTextEdited = { viewModel.onTextEdited(it) }
                )
            }
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

    if (rotated !== bitmap) {
        bitmap.recycle()
    }

    return rotated
}