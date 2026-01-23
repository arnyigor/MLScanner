package com.arny.mlscanner.data.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.arny.mlscanner.data.ocr.OcrEngine
import com.arny.mlscanner.domain.models.OcrResult
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
    private val rotationMatrix = Matrix()

    // Метод будет вызван в фоновом потоке, который мы передадим в setAnalyzer
    override fun analyze(imageProxy: ImageProxy) {
        // Если обработка занята, пропускаем кадр
        if (isProcessing.get()) {
            imageProxy.close()
            return
        }

        try {
            isProcessing.set(true)

            // 1. Конвертация (тяжелая операция)
            val bitmap = imageProxy.toBitmap()

            // 2. Поворот (тяжелая операция)
            val rotation = imageProxy.imageInfo.rotationDegrees
            val correctedBitmap = if (rotation != 0) {
                rotateBitmap(bitmap, rotation)
            } else {
                bitmap
            }

            // Proxy больше не нужен, закрываем его, чтобы CameraX могла слать новые кадры
            imageProxy.close()

            // 3. Распознавание
            val result = ocrEngine.recognize(correctedBitmap)
            onOcrResult(result)

        } catch (e: Exception) {
            Log.e(TAG, "Analysis failed", e)
            onError(e)
        } finally {
            isProcessing.set(false)
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
