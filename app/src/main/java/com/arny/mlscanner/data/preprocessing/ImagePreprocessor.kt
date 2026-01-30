package com.arny.mlscanner.data.preprocessing

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.core.graphics.createBitmap
import com.arny.mlscanner.domain.models.ScanSettings
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

// --- DUMMY CLASSES ДЛЯ ИЗОЛЯЦИИ СБОЕВ DocumentDetector ---
data class Quadrilateral(
    val points: Array<org.opencv.core.Point>,
    val isValid: Boolean = points.size == 4
)

class DocumentDetector {
    fun detectDocumentQuadrilateral(bitmap: Bitmap): Quadrilateral? {
        return null
    }

    fun correctPerspective(bitmap: Bitmap, quadrilateral: Quadrilateral?): Bitmap? {
        return null
    }
}
// --- КОНЕЦ DUMMY CLASSES ---

class ImagePreprocessor(
    private val context: Context,
    private val documentDetector: DocumentDetector = DocumentDetector()
) {

    private var currentAngleDeg = 0.0

    companion object {
        private const val TAG = "ImagePreprocessor"

        init {
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "OpenCV initialization failed")
            }
        }

        private fun saveBitmapForDebug(context: Context, bitmap: Bitmap, name: String) {
            try {
                // Используем filesDir, чтобы избежать проблем с внешним хранилищем
                val file = File(context.filesDir, name)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                Log.d(TAG, "Saved debug image: ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save debug image: $name", e)
            }
        }
    }

    /* ------------------------------------------------------------------- *
     *  Public API                                                         *
     * ------------------------------------------------------------------- */

    /**
     * Полный pipeline, объединяющий геометрию (изолированную) и фильтрацию.
     */
    fun prepareBaseImage(bitmap: Bitmap, settings: ScanSettings): Bitmap {
        if (bitmap.width <= 0 || bitmap.height <= 0) return bitmap

        val sourceBitmap =
            if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)

        saveBitmapForDebug(context, sourceBitmap, "01_original_camera.png")
        Log.d(TAG, "ImagePreprocessor: Original Size: ${sourceBitmap.width}x${sourceBitmap.height}")
        // 1. Геометрия (DocumentDetector изолирован, чтобы не падать)
        val geomProcessed: Bitmap = try {
            val docCorrected = sourceBitmap // Пропускаем коррекцию перспективы

            saveBitmapForDebug(context, docCorrected, "02_after_geometry_skip.png")

            val deskewed = if (settings.autoRotateEnabled) deskew(docCorrected) else docCorrected
            deskewed
        } catch (e: Exception) {
            Log.e(TAG, "FATAL GEOMETRY CRASH - Check OpenCV Init/Permissions", e)
            return sourceBitmap
        }
        saveBitmapForDebug(context, geomProcessed, "03_after_deskew_isolation.png")

        // 2. Фильтрация
        val finalProcessedBitmap = applyFiltersInternal(geomProcessed, settings)
        saveBitmapForDebug(context, finalProcessedBitmap, "04_final_preprocessed.png")
        return finalProcessedBitmap
    }

    /**
     * Только фильтры (для Live Preview во ViewModel).
     */
    fun applyFiltersOnly(baseBitmap: Bitmap, settings: ScanSettings): Bitmap {
        return applyFiltersInternal(baseBitmap, settings)
    }

    /* ------------------------------------------------------------------- *
     *  Внутренние Helpers (Core Logic)                                   *
     * ------------------------------------------------------------------- */

    private fun applyFiltersInternal(baseBitmap: Bitmap, settings: ScanSettings): Bitmap {
        Log.d(
            TAG,
            "applyFiltersInternal received Bitmap Size: ${baseBitmap.width}x${baseBitmap.height}"
        )
        val mat = Mat()
        try {
            Utils.bitmapToMat(baseBitmap, mat)
            val gray = Mat()

            try {
                if (mat.channels() == 1) {
                    mat.copyTo(gray)
                } else {
                    Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
                }

                // --- НОВАЯ ЛОГИКА: АВТО-ИНВЕРСИЯ ---
                // Вычисляем среднюю яркость пикселей
                val meanBrightness = Core.mean(gray).`val`[0]
                Log.d(TAG, "Image Mean Brightness: $meanBrightness")

                // Если яркость < 100 (из 255), считаем, что фон черный
                // Tesseract требует ЧЕРНЫЙ текст на БЕЛОМ фоне.
                if (meanBrightness < 100) {
                    Log.i(TAG, "Dark background detected ($meanBrightness). Inverting image for OCR.")
                    Core.bitwise_not(gray, gray) // Инверсия (255 - pixel)
                }
                // -------------------------------------

                if (settings.denoiseEnabled) {
                    val temp = Mat()
                    Imgproc.bilateralFilter(gray, temp, 5, 75.0, 75.0)
                    temp.copyTo(gray)
                    temp.release()
                }

                val contrast = settings.contrastLevel ?: 1.0f
                val brightness = settings.brightnessLevel ?: 0f

                if (contrast != 1.0f || brightness != 0f) {
                    // dst = src * alpha + beta
                    gray.convertTo(gray, -1, contrast.toDouble(), brightness.toDouble())
                }

                val sharpenLevel = settings.sharpenLevel ?: 0f
                if (sharpenLevel > 0f) {
                    sharpen(gray, sharpenLevel)
                }

                val userWantsBinarization = settings.binarizationEnabled
                val forcedBinarization = meanBrightness < 100 // Если была инверсия, бинаризация обязательна

                if (userWantsBinarization || forcedBinarization) {
                    // Вариант 1: OTSU (Глобальный умный порог) - Идеально для логотипов/заголовков на однородном фоне
                    Imgproc.threshold(
                        gray,
                        gray,
                        0.0,
                        255.0,
                        Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU
                    )
                }

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

    /**
     * Коррекция наклона (Deskew)
     */
    private fun deskew(source: Bitmap): Bitmap {
        val mat = Mat()
        try {
            Utils.bitmapToMat(source, mat)
            if (detectSkew(mat)) {
                val center = Point(mat.cols() / 2.0, mat.rows() / 2.0)
                val rotMat = Imgproc.getRotationMatrix2D(center, currentAngleDeg, 1.0)
                try {
                    Imgproc.warpAffine(mat, mat, rotMat, mat.size())
                } finally {
                    rotMat.release()
                }
            }
            val result = createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, result)
            return result
        } catch (e: Exception) {
            Log.w(TAG, "Deskew failed", e)
            return source
        } finally {
            mat.release()
        }
    }

    /**
     * Детекция угла наклона
     */
    private fun detectSkew(mat: Mat): Boolean {
        val edges = Mat()
        try {
            Imgproc.Canny(mat, edges, 50.0, 150.0)
            val lines = Mat()
            try {
                Imgproc.HoughLines(edges, lines, 1.0, Math.PI / 180, 100)
                if (lines.rows() == 0) return false
                var angleSum = 0.0
                val count = lines.rows().coerceAtMost(10)
                for (i in 0 until count) {
                    val rhoTheta = lines.get(i, 0)
                    angleSum += rhoTheta[1]
                }
                val avgThetaRad = angleSum / count
                currentAngleDeg = Math.toDegrees(avgThetaRad) - 90.0
                return abs(currentAngleDeg) > 0.5 && abs(currentAngleDeg) < 45
            } finally {
                lines.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Skew detection failed", e)
            return false
        } finally {
            edges.release()
        }
    }

    /**
     * Резкость (Sharpen)
     */
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