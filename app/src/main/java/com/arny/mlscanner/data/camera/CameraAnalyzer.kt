package com.arny.mlscanner.data.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.arny.mlscanner.data.ocr.OcrEngine
import com.arny.mlscanner.domain.models.OcrResult
import kotlinx.coroutines.cancel
import java.util.concurrent.atomic.AtomicBoolean

class CameraAnalyzer(
    private val ocrEngine: OcrEngine,
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

    // Метод будет вызван в фоновом потоке, который мы передадим в setAnalyzer
    override fun analyze(imageProxy: ImageProxy) {
        // Skip frame if already processing
        if (isProcessing.getAndSet(true)) {
            imageProxy.close()
            return
        }

        scope.launch {
            try {
                // Heavy work in IO dispatcher
                val correctedBitmap = withContext(ioDispatcher) {
                    val bitmap = imageProxy.toBitmap()
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    if (rotation != 0) rotateBitmap(bitmap, rotation) else bitmap
                }

                // Close proxy after conversion & rotation to free buffer
                imageProxy.close()

                // OCR recognition on IO dispatcher
                val result = withContext(ioDispatcher) {
                    ocrEngine.recognize(correctedBitmap)
                }

                // Deliver result on Main thread
                withContext(Dispatchers.Main) {
                    onOcrResult(result)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed", e)
                withContext(Dispatchers.Main) {
                    onError(e)
                }
            } finally {
                isProcessing.set(false)
            }
        }
    }

    fun shutdown() = scope.cancel()

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        rotationMatrix.reset()
        rotationMatrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, rotationMatrix, true
        )
    }
}
