package com.arny.mlscanner.data.preprocessing

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.arny.mlscanner.domain.models.ScanSettings
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

class ImagePreprocessor {

    /**
     * Применяет набор предобработок для улучшения качества OCR
     */
    fun preprocessImage(
        bitmap: Bitmap,
        settings: ScanSettings
    ): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)

        // 1. Конвертация в grayscale
        val grayMat = Mat()
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)

        if (settings.denoiseEnabled) {
            val denoisedMat = Mat()
            // Bilateral Filter - лучший выбор для OCR, сохраняет четкие края букв
            Imgproc.bilateralFilter(
                grayMat,     // src - исходное изображение
                denoisedMat, // dst - результат
                9,           // d - диаметр каждого пикселя (5-9 оптимально)
                75.0,        // sigmaColor - фильтр в цветовом пространстве
                75.0         // sigmaSpace - фильтр в координатном пространстве
            )
            denoisedMat.copyTo(grayMat)
            denoisedMat.release()
        }

        // 3. Коррекция яркости и контраста
        if (settings.contrastLevel != 1.0f || settings.brightnessLevel != 0f) {
            grayMat.convertTo(
                grayMat,
                -1,
                settings.contrastLevel.toDouble(),  // alpha (contrast)
                settings.brightnessLevel.toDouble() // beta (brightness)
            )
        }

        // 4. Повышение резкости (если включено)
        if (settings.sharpenLevel > 0) {
            applySharpen(grayMat, settings.sharpenLevel)
        }

        // 5. Adaptive Thresholding (бинаризация) - критично для точности!
        if (settings.binarizationEnabled) {
            Imgproc.adaptiveThreshold(
                grayMat,
                grayMat,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                11,  // blockSize (должен быть нечетным)
                2.0  // C constant
            )
        }

        // 6. Автоповорот (deskew) - опционально
        if (settings.autoRotateEnabled) {
            deskewImage(grayMat)
        }

        // Конвертация обратно в Bitmap
        val resultBitmap = createBitmap(grayMat.cols(), grayMat.rows())
        Utils.matToBitmap(grayMat, resultBitmap)

        // Очистка памяти
        mat.release()
        grayMat.release()

        return resultBitmap
    }

    /**
     * Применяет фильтр резкости
     */
    private fun applySharpen(mat: Mat, sharpenLevel: Float) {
        val kernel = Mat(3, 3, CvType.CV_32F)
        val kernelData = floatArrayOf(
            0f, -1f, 0f,
            -1f, 5f + sharpenLevel, -1f,
            0f, -1f, 0f
        )
        kernel.put(0, 0, kernelData)

        Imgproc.filter2D(mat, mat, -1, kernel)
        kernel.release()
    }

    /**
     * Исправление перекоса изображения
     */
    private fun deskewImage(mat: Mat) {
        val edges = Mat()
        Imgproc.Canny(mat, edges, 50.0, 150.0)

        val lines = Mat()
        Imgproc.HoughLines(edges, lines, 1.0, Math.PI / 180, 100)

        if (lines.rows() > 0) {
            var angleSum = 0.0
            val linesToCheck = lines.rows().coerceAtMost(10)

            for (i in 0 until linesToCheck) {
                val line = lines.get(i, 0)
                val theta = line[1]
                angleSum += theta
            }

            val avgAngle = angleSum / linesToCheck
            val angleDegrees = Math.toDegrees(avgAngle) - 90

            // Поворачиваем изображение только если угол значительный
            if (Math.abs(angleDegrees) > 0.5 && Math.abs(angleDegrees) < 45) {
                val center = Point(mat.cols() / 2.0, mat.rows() / 2.0)
                val rotMatrix = Imgproc.getRotationMatrix2D(center, angleDegrees, 1.0)
                Imgproc.warpAffine(mat, mat, rotMatrix, mat.size())
                rotMatrix.release()
            }
        }

        edges.release()
        lines.release()
    }
}

