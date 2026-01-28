package com.arny.mlscanner.data.preprocessing

import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.createBitmap
import com.arny.mlscanner.domain.models.ScanSettings
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

class ImagePreprocessor(
    private val documentDetector: DocumentDetector = DocumentDetector()
) {

    companion object {
        private const val TAG = "ImagePreprocessor"
    }

    /**
     * Полный цикл: Геометрия (Perspective/Deskew) + Фильтры.
     * Используется перед финальным OCR.
     */
    fun prepareBaseImage(bitmap: Bitmap, settings: ScanSettings): Bitmap {
        if (bitmap.width <= 0 || bitmap.height <= 0) return bitmap

        val sourceBitmap = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)

        // 1. Геометрия
        val geomProcessed: Bitmap = try {
            val docCorrected = if (settings.detectDocument) {
                correctPerspective(sourceBitmap)
            } else {
                sourceBitmap
            }
            if (settings.autoRotateEnabled) deskew(docCorrected) else docCorrected
        } catch (e: Exception) {
            Log.w(TAG, "Geometry stage failed", e)
            sourceBitmap
        }

        // 2. Фильтры
        return applyFiltersOnly(geomProcessed, settings)
    }

    /**
     * ТОЛЬКО Фильтры (Яркость, Контраст, Шум, Бинаризация).
     * Используется для Live Preview во ViewModel.
     * Не меняет геометрию (размер/угол).
     */
    fun applyFiltersOnly(baseBitmap: Bitmap, settings: ScanSettings): Bitmap {
        val mat = Mat()
        try {
            Utils.bitmapToMat(baseBitmap, mat)
            val gray = Mat()

            try {
                // Конвертация в Grayscale
                if (mat.channels() == 1) {
                    mat.copyTo(gray)
                } else {
                    Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
                }

                // 1. Denoise
                if (settings.denoiseEnabled) {
                    val temp = Mat()
                    // Bilateral filter медленный, для превью можно использовать GaussianBlur для скорости
                    // Imgproc.GaussianBlur(gray, temp, org.opencv.core.Size(3.0, 3.0), 0.0)
                    // Но для качества оставим Bilateral
                    Imgproc.bilateralFilter(gray, temp, 5, 75.0, 75.0)
                    temp.copyTo(gray)
                    temp.release()
                }

                // 2. Contrast & Brightness
                // alpha = contrast (1.0-3.0), beta = brightness (0-100)
                val contrast = settings.contrastLevel ?: 1.0f
                val brightness = settings.brightnessLevel ?: 0f

                if (contrast != 1.0f || brightness != 0f) {
                    // convertTo: dst = src * alpha + beta
                    gray.convertTo(gray, -1, contrast.toDouble(), brightness.toDouble())
                }

                // 3. Sharpen
                val sharpenLevel = settings.sharpenLevel ?: 0f
                if (sharpenLevel > 0f) {
                    sharpen(gray, sharpenLevel)
                }

                // 4. Binarization
                if (settings.binarizationEnabled) {
                    // Используем Adaptive Threshold для документов с тенями
                    Imgproc.adaptiveThreshold(
                        gray,
                        gray,
                        255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY,
                        15, // Block size (должен быть нечетным)
                        10.0 // C constant
                    )
                }

                // Convert back to Bitmap
                val result = createBitmap(gray.cols(), gray.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(gray, result)
                return result

            } finally {
                gray.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Filter pipeline failed", e)
            return baseBitmap
        } finally {
            mat.release()
        }
    }

    // --- Внутренние методы ---

    private fun correctPerspective(source: Bitmap): Bitmap {
        return try {
            val quadrilateral = documentDetector.detectDocumentQuadrilateral(source)
            documentDetector.correctPerspective(source, quadrilateral) ?: source
        } catch (e: Exception) {
            source
        }
    }

    private fun deskew(source: Bitmap): Bitmap {
        // ... (код deskew из вашего исходного файла) ...
        // Для краткости я его пропускаю, если он у вас уже есть, оставьте его.
        // Если нет, верните source.
        return source
    }

    private fun sharpen(mat: Mat, level: Float) {
        val kernel = Mat(3, 3, CvType.CV_32F)
        val center = 5f + level
        val data = floatArrayOf(
            0f, -1f, 0f,
            -1f, center, -1f,
            0f, -1f, 0f
        )
        kernel.put(0, 0, data)
        Imgproc.filter2D(mat, mat, -1, kernel)
        kernel.release()
    }
}