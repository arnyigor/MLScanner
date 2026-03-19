// ============================================================
// data/preprocessing/ImagePreprocessor.kt — ИСПРАВЛЕННАЯ ВЕРСИЯ
// ============================================================
package com.arny.mlscanner.data.preprocessing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import androidx.core.graphics.createBitmap
import com.arny.mlscanner.domain.models.ScanSettings
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

class ImagePreprocessor {

    companion object {
        private const val TAG = "ImagePreprocessor"
        private const val BRIGHTNESS_DARK_THRESHOLD = 80.0
        private const val MIN_SKEW_ANGLE = 0.5
        private const val MAX_SKEW_ANGLE = 45.0
        private const val HOUGH_THRESHOLD = 100

        private var openCvInitialized = false

        fun ensureOpenCvInitialized(): Boolean {
            if (openCvInitialized) return true
            return try {
                val result = org.opencv.android.OpenCVLoader.initLocal()
                Log.i(TAG, "OpenCV initialized: $result")
                openCvInitialized = result
                result
            } catch (e: Exception) {
                try {
                    @Suppress("DEPRECATION")
                    val result = org.opencv.android.OpenCVLoader.initDebug()
                    Log.i(TAG, "OpenCV initDebug: $result")
                    openCvInitialized = result
                    result
                } catch (e2: Exception) {
                    Log.e(TAG, "OpenCV init failed completely", e2)
                    openCvInitialized = false
                    false
                }
            }
        }
    }

    init {
        ensureOpenCvInitialized()
    }

    // ================================================================
    // PUBLIC API
    // ================================================================

    /**
     * Полный pipeline для OCR: deskew + grayscale + фильтры.
     * Результат — оптимизированный для Tesseract (grayscale).
     * НЕ используется для preview!
     */
    fun prepareBaseImage(bitmap: Bitmap, settings: ScanSettings): Bitmap {
        if (bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        if (!openCvInitialized) return bitmap

        val source = if (bitmap.isMutable) bitmap
        else bitmap.copy(Bitmap.Config.ARGB_8888, true)

        var processed = source
        if (settings.autoRotateEnabled) {
            try {
                processed = deskew(source)
            } catch (e: Exception) {
                Log.w(TAG, "Deskew failed", e)
            }
        }

        // Для OCR — только grayscale + инверсия, БЕЗ пользовательских настроек
        return applyOcrFilters(processed)
    }

    /**
     * Фильтры для LIVE PREVIEW.
     *
     * КЛЮЧЕВОЕ ОТЛИЧИЕ от prepareBaseImage:
     * - Работает с ЦВЕТНЫМ изображением (не grayscale)
     * - Использует Android ColorMatrix (быстрее OpenCV для простых операций)
     * - НЕ делает инверсию тёмного фона
     * - НЕ делает бинаризацию (для preview бессмысленно)
     *
     * Это гарантирует что preview выглядит предсказуемо
     * и пользователь видит эффект фильтров на ЦВЕТНОМ изображении.
     */
    fun applyFiltersOnly(baseBitmap: Bitmap, settings: ScanSettings): Bitmap {
        return applyPreviewFilters(baseBitmap, settings)
    }

    // ================================================================
    // PREVIEW FILTERS — цветные, быстрые, предсказуемые
    // ================================================================

    /**
     * Фильтры для preview — работают с ЦВЕТНЫМ изображением.
     * Используют Android Canvas/ColorMatrix — быстро и предсказуемо.
     */
    private fun applyPreviewFilters(source: Bitmap, settings: ScanSettings): Bitmap {
        val contrast = settings.contrastLevel ?: 1.0f
        val brightness = settings.brightnessLevel ?: 0f
        val sharpen = settings.sharpenLevel ?: 0f

        // Если все параметры дефолтные — возвращаем source как есть
        val hasChanges = contrast != 1.0f ||
                brightness != 0f ||
                sharpen > 0f ||
                settings.denoiseEnabled ||
                settings.binarizationEnabled

        if (!hasChanges) return source

        var result = source

        // Шаг 1: Контраст + Яркость (ColorMatrix — быстро и безопасно)
        if (contrast != 1.0f || brightness != 0f) {
            result = applyContrastBrightness(result, contrast, brightness)
        }

        // Шаг 2: Резкость (OpenCV если доступен, иначе пропускаем)
        if (sharpen > 0f && openCvInitialized) {
            val sharpened = applyColorSharpen(result, sharpen)
            if (sharpened != null) {
                if (result !== source) result.recycle()
                result = sharpened
            }
        }

        // Шаг 3: Бинаризация для preview (если включена)
        if (settings.binarizationEnabled && openCvInitialized) {
            val binarized = applyPreviewBinarization(result)
            if (binarized != null) {
                if (result !== source) result.recycle()
                result = binarized
            }
        }

        return result
    }

    /**
     * Контраст + Яркость через Android ColorMatrix.
     * Работает с ЦВЕТНЫМ изображением.
     * Всегда возвращает НОВЫЙ bitmap.
     */
    private fun applyContrastBrightness(
        source: Bitmap,
        contrast: Float,
        brightness: Float
    ): Bitmap {
        val result = createBitmap(source.width, source.height)
        val canvas = Canvas(result)

        // ColorMatrix формула:
        // R' = contrast * R + translate
        // G' = contrast * G + translate
        // B' = contrast * B + translate
        // translate = brightness + (1 - contrast) * 128
        //
        // При contrast=1.0, brightness=0 → идентичное преобразование
        // При contrast=1.5 → усиление контраста (тёмное темнее, светлое светлее)
        // При contrast=0.5 → ослабление контраста (всё к серому)
        val translate = brightness + (1f - contrast) * 128f

        val cm = ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, translate,
            0f, contrast, 0f, 0f, translate,
            0f, 0f, contrast, 0f, translate,
            0f, 0f, 0f, 1f, 0f
        ))

        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        }

        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    /**
     * Резкость на ЦВЕТНОМ изображении через OpenCV.
     */
    private fun applyColorSharpen(source: Bitmap, level: Float): Bitmap? {
        val mat = Mat()
        val kernel = Mat(3, 3, CvType.CV_32F)

        return try {
            Utils.bitmapToMat(source, mat)

            val center = 5f + level
            kernel.put(0, 0, floatArrayOf(
                0f, -1f, 0f,
                -1f, center, -1f,
                0f, -1f, 0f
            ))
            Imgproc.filter2D(mat, mat, -1, kernel)

            val result = createBitmap(mat.cols(), mat.rows())
            Utils.matToBitmap(mat, result)
            result
        } catch (e: Exception) {
            Log.w(TAG, "Color sharpen failed", e)
            null
        } finally {
            mat.release()
            kernel.release()
        }
    }

    /**
     * Бинаризация для preview — конвертирует в ч/б.
     */
    private fun applyPreviewBinarization(source: Bitmap): Bitmap? {
        val mat = Mat()
        val gray = Mat()

        return try {
            Utils.bitmapToMat(source, mat)
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.threshold(
                gray, gray, 0.0, 255.0,
                Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU
            )

            val result = createBitmap(gray.cols(), gray.rows())
            Utils.matToBitmap(gray, result)
            result
        } catch (e: Exception) {
            Log.w(TAG, "Preview binarization failed", e)
            null
        } finally {
            mat.release()
            gray.release()
        }
    }

    // ================================================================
    // OCR FILTERS — grayscale, оптимизированные для Tesseract
    // ================================================================

    /**
     * Полный pipeline для OCR (grayscale + все оптимизации).
     */
    private fun applyOcrFilters(baseBitmap: Bitmap): Bitmap {
        if (!openCvInitialized) return baseBitmap

        val mat = Mat()
        val gray = Mat()

        try {
            Utils.bitmapToMat(baseBitmap, mat)

            // Определяем нужна ли инверсия
            if (mat.channels() > 1) {
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            } else {
                mat.copyTo(gray)
            }

            val mean = Core.mean(gray).`val`[0]
            val needsInversion = mean < BRIGHTNESS_DARK_THRESHOLD

            if (needsInversion) {
                Log.d(TAG, "OCR: dark bg (mean=${"%.0f".format(mean)}), inverting")
                Core.bitwise_not(mat, mat)  // Инвертируем ЦВЕТНОЙ mat
            }

            // Возвращаем ЦВЕТНОЙ bitmap — Tesseract сам сделает grayscale
            val result = createBitmap(mat.cols(), mat.rows())
            Utils.matToBitmap(mat, result)

            Log.d(TAG, "OCR prep: ${baseBitmap.width}x${baseBitmap.height}, " +
                    "inv=$needsInversion")

            return result

        } catch (e: Exception) {
            Log.e(TAG, "OCR filter failed", e)
            return baseBitmap
        } finally {
            gray.release()
            mat.release()
        }
    }

    private fun isDarkBackground(grayMat: Mat): Boolean {
        val mean = Core.mean(grayMat).`val`[0]
        return mean < BRIGHTNESS_DARK_THRESHOLD
    }

    private fun applyGraySharpen(mat: Mat, level: Float) {
        val kernel = Mat(3, 3, CvType.CV_32F)
        try {
            val center = 5f + level
            kernel.put(0, 0, floatArrayOf(
                0f, -1f, 0f,
                -1f, center, -1f,
                0f, -1f, 0f
            ))
            Imgproc.filter2D(mat, mat, -1, kernel)
        } finally {
            kernel.release()
        }
    }

    // ================================================================
    // DESKEW
    // ================================================================

    private fun deskew(source: Bitmap): Bitmap {
        val mat = Mat()
        try {
            Utils.bitmapToMat(source, mat)
            val angle = detectSkewAngle(mat) ?: return source

            val center = Point(mat.cols() / 2.0, mat.rows() / 2.0)
            val rotMat = Imgproc.getRotationMatrix2D(center, angle, 1.0)
            try {
                Imgproc.warpAffine(mat, mat, rotMat, mat.size())
            } finally {
                rotMat.release()
            }

            val result = createBitmap(
                mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(mat, result)
            return result
        } catch (e: Exception) {
            Log.w(TAG, "Deskew error", e)
            return source
        } finally {
            mat.release()
        }
    }

    private fun detectSkewAngle(mat: Mat): Double? {
        val edges = Mat()
        val lines = Mat()
        try {
            Imgproc.Canny(mat, edges, 50.0, 150.0)
            Imgproc.HoughLines(
                edges, lines, 1.0, Math.PI / 180, HOUGH_THRESHOLD
            )

            if (lines.rows() == 0) return null

            val angles = mutableListOf<Double>()
            val count = lines.rows().coerceAtMost(20)
            for (i in 0 until count) {
                val rhoTheta = lines.get(i, 0)
                val thetaDeg = Math.toDegrees(rhoTheta[1]) - 90.0
                if (abs(thetaDeg) < MAX_SKEW_ANGLE) {
                    angles.add(thetaDeg)
                }
            }

            if (angles.isEmpty()) return null

            val sorted = angles.sorted()
            val median = sorted[sorted.size / 2]

            return if (abs(median) > MIN_SKEW_ANGLE) median else null
        } finally {
            edges.release()
            lines.release()
        }
    }
}