package com.arny.mlscanner.data.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.util.Log
import androidx.annotation.OptIn
import androidx.camera.core.ExperimentalGetImage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.arny.mlscanner.data.ocr.engine.TesseractEngine
import com.arny.mlscanner.domain.models.OcrResult
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean

class CameraAnalyzer(
    private val context: android.content.Context,
    private val ocrEngine: TesseractEngine,
    private val onOcrResult: (OcrResult) -> Unit,
    private val onError: (Throwable) -> Unit
) : ImageAnalysis.Analyzer {

    companion object {
        private const val TAG = "CameraAnalyzer"
    }

    private val isProcessing = AtomicBoolean(false)
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val rotationMatrix = Matrix()
    private val yuvConverter = YuvConverter

    override fun analyze(imageProxy: ImageProxy) {
        if (isProcessing.getAndSet(true)) {
            imageProxy.close()
            return
        }

        scope.launch {
            try {
                // Безопасная конвертация с учётом формата
                val bitmap = withContext(Dispatchers.Default) {
                    convertImageProxyToBitmap(imageProxy)
                }

                imageProxy.close() // Освобождаем сразу после конвертации

                val rotation = imageProxy.imageInfo.rotationDegrees
                val corrected = if (rotation != 0) rotateBitmap(bitmap, rotation) else bitmap

                val result = withContext(Dispatchers.IO) {
                    ocrEngine.recognize(corrected)
                }

                withContext(Dispatchers.Main) {
                    onOcrResult(result)
                }

                // Очистка если создавали копии
                if (corrected !== bitmap) corrected.recycle()

            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                withContext(Dispatchers.Main) { onError(e) }
            } finally {
                isProcessing.set(false)
            }
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun convertImageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        val image = imageProxy.image ?: throw IllegalStateException("Image is null")

        return when (image.format) {
            ImageFormat.YUV_420_888 -> {
                // Используем новый YuvConverter (без RenderScript)
                yuvConverter.yuvToRgb(image)
            }
            ImageFormat.JPEG -> {
                val buffer = image.planes[0].buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IllegalStateException("Failed to decode JPEG")
            }
            else -> throw IllegalArgumentException("Unsupported format: ${image.format}")
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        rotationMatrix.reset()
        rotationMatrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, rotationMatrix, true
        )
    }
}
