package com.arny.mlscanner.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

private fun dummyBitmap(): Bitmap =
    createBitmap(200, 200).apply { eraseColor(android.graphics.Color.LTGRAY) }

@androidx.compose.ui.tooling.preview.Preview
@Composable
fun CameraScreenPreview() {
    CameraScreen(
        onImageCaptured = { },
        onError = {},
        onBack = {}
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onImageCaptured: (Bitmap) -> Unit,
    onError: (Exception) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val tempFile = saveUriToCacheFile(context, it)
                val bitmap = rotateBitmapIfNeeded(
                    BitmapFactory.decodeFile(tempFile.absolutePath),
                    tempFile.absolutePath
                )
                onImageCaptured(bitmap)
                tempFile.delete()
            } catch (e: Exception) {
                onError(e)
            }
        }
    }

    // Camera state
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var flashEnabled by remember { mutableStateOf(false) }
    var isCapturing by remember { mutableStateOf(false) }

    // ImageCapture use case reference
    var imageCaptureUseCase by remember { mutableStateOf<ImageCapture?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Text") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.5f),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (hasCameraPermission) {
                // Camera Preview
                CameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    lensFacing = lensFacing,
                    flashEnabled = flashEnabled,
                    onImageCaptureReady = { imageCapture ->
                        imageCaptureUseCase = imageCapture
                    },
                    onError = onError,
                    lifecycleOwner = lifecycleOwner
                )

                // Flip camera button
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.TopEnd
                ) {
                    FilledIconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.5f),
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.FlipCameraAndroid,
                            contentDescription = "Flip camera"
                        )
                    }
                }

                // Camera Controls Overlay
                CameraControls(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 32.dp),
                    isCapturing = isCapturing,
                    flashEnabled = flashEnabled,
                    onCaptureClick = {
                        imageCaptureUseCase?.let { imageCapture ->
                            isCapturing = true
                            captureImage(
                                imageCapture = imageCapture,
                                context = context,
                                onImageCaptured = { bitmap ->
                                    isCapturing = false
                                    onImageCaptured(bitmap)
                                },
                                onError = { error ->
                                    isCapturing = false
                                    onError(error)
                                }
                            )
                        }
                    },
                    onFlashToggle = { flashEnabled = !flashEnabled },
                    onGalleryClick = { galleryLauncher.launch("image/*") }
                )
            } else {
                // Permission denied UI
                PermissionDeniedContent(
                    modifier = Modifier.align(Alignment.Center),
                    onRequestPermission = {
                        permissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                )
            }
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    lensFacing: Int,
    flashEnabled: Boolean,
    onImageCaptureReady: (ImageCapture) -> Unit,
    onError: (Exception) -> Unit,
    lifecycleOwner: LifecycleOwner
) {
    val context = LocalContext.current
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(lensFacing, flashEnabled) {
        val cameraProvider = suspendCancellableCoroutine<ProcessCameraProvider> { continuation ->
            val listenableFuture = ProcessCameraProvider.getInstance(context)
            listenableFuture.addListener({
                continuation.resume(listenableFuture.get())
            }, ContextCompat.getMainExecutor(context))
        }

        try {
            cameraProvider.unbindAll()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setFlashMode(
                    if (flashEnabled) ImageCapture.FLASH_MODE_ON
                    else ImageCapture.FLASH_MODE_OFF
                )
                .build()

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )

            // ⭐ Передаем imageCapture наружу
            onImageCaptureReady(imageCapture)

        } catch (e: Exception) {
            onError(e)
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

@Composable
fun CameraControls(
    modifier: Modifier = Modifier,
    isCapturing: Boolean,
    flashEnabled: Boolean,
    onCaptureClick: () -> Unit,
    onFlashToggle: () -> Unit,
    onGalleryClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Gallery button
        IconButton(
            onClick = onGalleryClick,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = "Choose from gallery",
                tint = Color.White,
                modifier = Modifier.size(32.dp)
            )
        }

        // Capture button
        FilledIconButton(
            onClick = onCaptureClick,
            modifier = Modifier.size(72.dp),
            enabled = !isCapturing,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White,
                contentColor = Color.Black
            ),
            shape = CircleShape
        ) {
            if (isCapturing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color.Black
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Camera,
                    contentDescription = "Capture",
                    modifier = Modifier.size(40.dp)
                )
            }
        }

        // Flash toggle
        IconButton(
            onClick = onFlashToggle,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(
                imageVector = if (flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = "Toggle flash",
                tint = if (flashEnabled) Color.Yellow else Color.White,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun PermissionDeniedContent(
    modifier: Modifier = Modifier,
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CameraAlt,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(64.dp)
        )
        Text(
            text = "Camera Permission Required",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Text(
            text = "To scan text, please grant camera permission",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.7f)
        )
        Button(onClick = onRequestPermission) {
            Text("Grant Permission")
        }
    }
}

// Helper functions
private fun captureImage(
    imageCapture: ImageCapture,
    context: Context,
    onImageCaptured: (Bitmap) -> Unit,
    onError: (Exception) -> Unit
) {
    val outputFile = File(
        context.cacheDir,
        "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
    )

    val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

    imageCapture.takePicture(
        outputOptions,
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                try {
                    // Гарантируем ARGB_8888 для совместимости с OpenCV
                    val options = BitmapFactory.Options()
                        .apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                    val bitmap = BitmapFactory.decodeFile(outputFile.absolutePath, options)
                    // Поворачиваем, если нужно, используя путь к файлу
                    val rotatedBitmap = rotateBitmapIfNeeded(bitmap, outputFile.absolutePath)
                    onImageCaptured(rotatedBitmap)
                    outputFile.delete()
                } catch (e: Exception) {
                    onError(e)
                }
            }

            override fun onError(exception: ImageCaptureException) {
                onError(exception)
            }
        }
    )
}

// Вспомогательная функция для сохранения URI во временный файл для последующей обработки EXIF
private fun saveUriToCacheFile(context: Context, uri: Uri): File {
    val tempFile = File(context.cacheDir, "gallery_temp_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(tempFile).use { output ->
            input.copyTo(output)
        }
    } ?: throw Exception("Failed to create temporary file for URI: $uri")
    return tempFile
}

// Универсальная функция для поворота Bitmap на основе EXIF-данных файла
private fun rotateBitmapIfNeeded(bitmap: Bitmap, path: String): Bitmap {
    val exif = ExifInterface(path)
    val orientation = exif.getAttributeInt(
        ExifInterface.TAG_ORIENTATION,
        ExifInterface.ORIENTATION_NORMAL
    )

    val matrix = Matrix()
    when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
        ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
        ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
    }

    // Если поворот не требуется, возвращаем исходный Bitmap
    if (matrix.isIdentity) {
        return bitmap
    } else {
        // Создаем новый Bitmap с примененным поворотом
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}

private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap {
    val inputStream = context.contentResolver.openInputStream(uri)
    return BitmapFactory.decodeStream(inputStream)
}
