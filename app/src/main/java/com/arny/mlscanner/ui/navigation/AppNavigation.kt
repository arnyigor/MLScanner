package com.arny.mlscanner.ui.navigation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.arny.mlscanner.ui.screens.CameraScreen
import com.arny.mlscanner.ui.screens.PreprocessingRoute
import com.arny.mlscanner.ui.screens.ResultScreen
import com.arny.mlscanner.ui.screens.ScanStep
import com.arny.mlscanner.ui.screens.ScanUiEvent
import com.arny.mlscanner.ui.screens.ScanViewModel
import com.arny.mlscanner.ui.screens.ScanningScreen
import kotlinx.coroutines.flow.collectLatest

sealed class Screen(val route: String) {
    data object Camera : Screen("camera")
    data object Preprocessing : Screen("preprocessing")
    data object Scanning : Screen("scanning")
    data object Result : Screen("result")
}

@Composable
fun AppNavigation(
    navController: NavHostController,
    viewModel: ScanViewModel
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.step) {
        val targetRoute = when (uiState.step) {
            ScanStep.CAMERA -> Screen.Camera.route
            ScanStep.PREPROCESSING -> Screen.Preprocessing.route
            ScanStep.SCANNING -> Screen.Scanning.route
            ScanStep.RESULT -> Screen.Result.route
        }

        val currentRoute = navController.currentDestination?.route
        if (currentRoute != targetRoute) {
            when (uiState.step) {
                ScanStep.RESULT -> {
                    navController.navigate(targetRoute) {
                        popUpTo(Screen.Scanning.route) { inclusive = true }
                    }
                }
                ScanStep.CAMERA -> {
                    navController.navigate(targetRoute) {
                        popUpTo(0) { inclusive = true }
                    }
                }
                else -> {
                    navController.navigate(targetRoute)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is ScanUiEvent.ShowToast -> {
                    Toast.makeText(context, event.message, Toast.LENGTH_SHORT).show()
                }
                is ScanUiEvent.CopiedToClipboard -> {
                    val text = uiState.recognizedText?.formattedText ?: return@collectLatest
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("OCR", text))
                    Toast.makeText(context, "Copied!", Toast.LENGTH_SHORT).show()
                }
                is ScanUiEvent.ShareText -> {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, event.text)
                    }
                    context.startActivity(Intent.createChooser(intent, "Share via"))
                }
                is ScanUiEvent.NavigateTo -> { }
                is ScanUiEvent.NavigateBack -> navController.popBackStack()
            }
        }
    }

    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
            viewModel.onErrorDismissed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Camera.route
    ) {
        composable(Screen.Camera.route) {
            CameraScreen(
                onImageCaptured = { bitmap ->
                    viewModel.onImageCaptured(bitmap)
                },
                onError = { e ->
                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                },
                onBack = { }
            )
        }

        composable(Screen.Preprocessing.route) {
            // Вызываем переименованный Route
            PreprocessingRoute(
                viewModel = viewModel,
                navController = navController
            )
        }

        composable(Screen.Scanning.route) {
            ScanningScreen(
                viewModel = viewModel,
                onCancel = { }
            )
        }

        composable(Screen.Result.route) {
            val recognizedText = uiState.recognizedText
            if (recognizedText != null) {
                ResultScreen(
                    recognizedText = recognizedText,
                    onBack = { viewModel.onNewScan() },
                    onNewScan = { viewModel.onNewScan() },
                    onCopy = { viewModel.onCopyText() },
                    onShare = { viewModel.onShareText() },
                    onTextEdited = { viewModel.onTextEdited(it) }
                )
            }
        }
    }
}
