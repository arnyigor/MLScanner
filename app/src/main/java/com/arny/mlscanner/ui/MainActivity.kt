package com.arny.mlscanner.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import com.arny.mlscanner.ui.navigation.AppNavigation
import com.arny.mlscanner.ui.navigation.Screen
import com.arny.mlscanner.ui.screens.CameraScreen
import com.arny.mlscanner.ui.screens.PreprocessingScreen
import com.arny.mlscanner.ui.screens.ResultScreen
import com.arny.mlscanner.ui.screens.ScanViewModel
import com.arny.mlscanner.ui.screens.ScanningScreen
import com.arny.mlscanner.ui.theme.AndroidComposeTemplateTheme
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidComposeTemplateTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val navController = rememberNavController()
    val viewModel: ScanViewModel = koinViewModel()
    AppNavigation(
        navController = navController,
        cameraScreen = {
            CameraScreen(
                onImageCaptured = { bitmap ->
                    viewModel.setCapturedImage(bitmap)
                    navController.navigate(Screen.Preprocessing.route)
                },
                onError = { exception ->

                },
                onBack = { }
            )
        },
        preprocessingScreen = {
            PreprocessingScreen(
                viewModel = viewModel,
                navController = navController,
            )
        },
        scanScreen = {
            LaunchedEffect(Unit) {
                viewModel.startScanning()
            }

            val recognizedText by viewModel.recognizedText.collectAsState()
            val error by viewModel.error.collectAsState()

            // Если текст распознан -> идем на экран результата
            LaunchedEffect(recognizedText) {
                if (recognizedText != null) {
                    navController.navigate(Screen.Result.route) {
                        // Убираем ScanningScreen из backstack, чтобы Back не возвращал на загрузку
                        popUpTo(Screen.Scanning.route) { inclusive = true }
                    }
                }
            }

            // Если ошибка -> возвращаемся или показываем тост
            LaunchedEffect(error) {
                if (error != null) {
                    // Тут можно показать Snackbar
                    navController.popBackStack()
                }
            }

            ScanningScreen(
                progressMessage = "Recognizing text...",
                onCancel = {
                    // Отмена (можно добавить job.cancel() в ViewModel)
                    navController.popBackStack()
                }
            )
        },
        resultScreen = {
            val result by viewModel.recognizedText.collectAsState()
            result?.let { text ->
                ResultScreen(
                    recognizedText = text,
                    onBack = {
                        viewModel.clear()
                        navController.popBackStack(Screen.Camera.route, inclusive = false)
                    },
                    onNewScan = {
                        viewModel.clear()
                        navController.popBackStack(Screen.Camera.route, inclusive = false)
                    }
                )
            }
        }
    )
}
