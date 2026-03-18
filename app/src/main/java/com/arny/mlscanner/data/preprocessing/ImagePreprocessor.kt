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
    val points: Array<Point>,
    val isValid: Boolean = points.size == 4
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Quadrilateral

        if (isValid != other.isValid) return false
        if (!points.contentEquals(other.points)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isValid.hashCode()
        result = 31 * result + points.contentHashCode()
        return result
    }
}

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
        // --- Константы ---
        private const val BRIGHTNESS_DARK_THRESHOLD = 100.0
        private const val BILATERAL_FILTER_DIAMETER = 5
        private const val BILATERAL_SIGMA_COLOR = 75.0
        private const val BILATERAL_SIGMA_SPACE = 75.0
        private const val CANNY_THRESHOLD1 = 50.0
        private const val CANNY_THRESHOLD2 = 150.0
        private const val HOUGH_LINES_THRESHOLD = 100
        private const val MAX_SKEW_ANGLE_DEG = 45.0
        private const val MIN_SKEW_ANGLE_DEG = 0.5

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

    protected fun applyFiltersInternal(baseBitmap: Bitmap, settings: ScanSettings): Bitmap {
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

                // --- [CRITICAL FIX] Логика инверсии ---
                // 1. Сначала определяем, нужна ли инверсия, и ЗАПОМИНАЕМ это состояние.
                val needsInversion = shouldInvertForOcr(gray)

                if (needsInversion) {
                    Log.i(TAG, "Dark background detected. Inverting image for OCR.")
                    // 2. Выполняем инверсию. Теперь изображение станет СВЕТЛЫМ.
                    Core.bitwise_not(gray, gray)
                }
                // --------------------------------------

                // Денойзинг
                if (settings.denoiseEnabled) {
                    val temp = Mat()
                    try {
                        Imgproc.bilateralFilter(
                            gray, temp, BILATERAL_FILTER_DIAMETER,
                            BILATERAL_SIGMA_COLOR, BILATERAL_SIGMA_SPACE
                        )
                        temp.copyTo(gray)
                    } finally {
                        temp.release()
                    }
                }

                // Контраст / Яркость
                val contrast = settings.contrastLevel ?: 1.0f
                val brightness = settings.brightnessLevel ?: 0f
                if (contrast != 1.0f || brightness != 0f) {
                    gray.convertTo(gray, -1, contrast.toDouble(), brightness.toDouble())
                }

                // Резкость
                val sharpenLevel = settings.sharpenLevel ?: 0f
                if (sharpenLevel > 0f) {
                    sharpen(gray, sharpenLevel)
                }

                // Бинаризация
                // ВАЖНО: Используем сохраненный флаг needsInversion.
                // Если бы мы вызвали shouldInvertForOcr(gray) здесь снова, он вернул бы false,
                // так как изображение уже инвертировано (стало светлым).
                val forcedBinarization = needsInversion
                val userWantsBinarization = settings.binarizationEnabled

                if (userWantsBinarization || forcedBinarization) {
                    Imgproc.threshold(
                        gray, gray, 0.0, 255.0,
                        Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU
                    )
                }

                // Конвертация обратно в Bitmap
                val result = Bitmap.createBitmap(gray.cols(), gray.rows(), Bitmap.Config.ARGB_8888)
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
     * Проверяет среднюю яркость. Если она низкая (тёмный фон), возвращает true.
     */
    protected fun shouldInvertForOcr(grayMat: Mat): Boolean {
        val meanBrightness = Core.mean(grayMat).`val`[0]
        return meanBrightness < BRIGHTNESS_DARK_THRESHOLD
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