package com.arny.mlscanner.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.arny.mlscanner.ui.screens.CameraScreen
import com.arny.mlscanner.ui.screens.PreprocessingScreen
import com.arny.mlscanner.ui.screens.ResultScreen
import com.arny.mlscanner.ui.screens.ScanViewModel
import com.arny.mlscanner.ui.screens.ScanningScreen
import org.koin.androidx.compose.koinViewModel

sealed class Screen(val route: String) {
    object Camera : Screen("camera")
    object Preprocessing : Screen("preprocessing")
    object Scanning : Screen("scanning")
    object Result : Screen("result")
}

@Composable
fun AppNavigation(
    viewModel: ScanViewModel = koinViewModel()
) {
    val navController = rememberNavController()

    NavHost(
        navController = navController, startDestination = Screen.Camera.route
    ) {
        composable(Screen.Camera.route) {
            CameraScreen(
                onImageCaptured = { bitmap ->
                    viewModel.setCapturedImage(bitmap)
                    navController.navigate(Screen.Preprocessing.route)
                },
                onError = { exception ->
                    // Handle error
                },
                onBack = { /* Handle back */ }
            )
        }

        composable(Screen.Preprocessing.route) {
            val capturedBitmap = viewModel.capturedBitmap
            capturedBitmap?.let { bitmap ->
                PreprocessingScreen(
                    capturedImage = bitmap,
                    onStartScan = { settings ->
                        viewModel.updateSettings(settings)
                        navController.navigate(Screen.Scanning.route)
                    },
                    onBack = { navController.popBackStack() }
                )
            }
        }

        // ui/navigation/AppNavigation.kt

        composable(Screen.Scanning.route) {
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
        }


        composable(Screen.Result.route) {
            val result by viewModel.recognizedText.collectAsState()
            result?.let { text ->
                ResultScreen(recognizedText = text, onBack = {
                    viewModel.clear()
                    navController.popBackStack(Screen.Camera.route, inclusive = false)
                }, onNewScan = {
                    viewModel.clear()
                    navController.popBackStack(Screen.Camera.route, inclusive = false)
                })
            }
        }
    }
}
