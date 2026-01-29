package com.arny.mlscanner.ui

import android.graphics.BitmapFactory
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.rememberNavController
import com.arny.mlscanner.data.ocr.TesseractEngine
import com.arny.mlscanner.ui.navigation.AppNavigation
import com.arny.mlscanner.ui.navigation.Screen
import com.arny.mlscanner.ui.screens.CameraScreen
import com.arny.mlscanner.ui.screens.PreprocessingScreen
import com.arny.mlscanner.ui.screens.ResultScreen
import com.arny.mlscanner.ui.screens.ScanViewModel
import com.arny.mlscanner.ui.screens.ScanningScreen
import com.arny.mlscanner.ui.theme.AndroidComposeTemplateTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    private fun showSecurityBlockScreen(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        if (RootChecker.isDeviceRooted() || RootChecker.isDebuggerConnected()) {
//            showSecurityBlockScreen("Root-доступ обнаружен. Приложение не может работать на модифицированных устройствах.")
//            return
//        }
        enableEdgeToEdge()
        setContent {
            AndroidComposeTemplateTheme {
                MainScreen()
            }
        }
        runMyOcr()
    }

    private fun runMyOcr() {
        // 1. Инициализируем движок
        val myEngine = TesseractEngine(this) // передаем Context

        lifecycleScope.launch(Dispatchers.Default) {
            Log.d("OCR_TEST", "Starting TesseractEngine inference...")

            // 2. Загружаем картинку из Assets
            val assetManager = assets
            val inputStream = assetManager.open("test_image.jpg")
            val bitmap = BitmapFactory.decodeStream(inputStream)

            // 3. Запускаем ВАШ метод recognize
            // Внутри него уже есть логика: Detect -> Crop -> Recognize
            val result = myEngine.recognize(bitmap)

            // 4. Выводим результат в UI поток
            withContext(Dispatchers.Main) {
                Log.d("OCR_TEST", "=== TesseractEngine RESULT ===")
                if (result.textBoxes.isEmpty()) {
                    Log.w("OCR_TEST", "Ничего не найдено (проверьте логи OcrDebug)")
                } else {
                    result.textBoxes.forEach { block ->
                        Log.i("OCR_TEST", "Text: [${block.text}] Conf: ${block.confidence}")
                    }
                }
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
