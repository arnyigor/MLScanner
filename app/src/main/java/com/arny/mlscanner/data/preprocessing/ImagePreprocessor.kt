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

/**
 * Image pre‑processing pipeline – OCR friendly transformations.
 *
 * All settings are optional (`Float?`) in [ScanSettings].  The pipeline
 * substitutes sensible defaults when a value is `null` and performs the
 * corresponding transformation only if the actual value differs from that
 * default (e.g. no sharpening when `sharpenLevel == null || 0f`).
 */
class ImagePreprocessor(
    private val documentDetector: DocumentDetector = DocumentDetector()
) {

    companion object {
        private const val TAG = "ImagePreprocessor"
    }

    /* ------------------------------------------------------------------- *
     *  Public API                                                          *
     * ------------------------------------------------------------------- */

    /**
     * Полный pipeline, объединяющий геометрию и фильтрацию.
     *
     * @param bitmap Исходное изображение (может быть использовано в UI).
     * @param settings Параметры обработки. Все поля nullable – при `null`
     *                 используются значения по умолчанию.
     * @return Bitmap, готовый к OCR.
     */
    fun prepareBaseImage(bitmap: Bitmap, settings: ScanSettings): Bitmap {
        if (bitmap.width <= 0 || bitmap.height <= 0) return bitmap

        // Создаём копию, чтобы не мутировать исходный объект,
        // используемый в UI. Если bitmap уже mutable – просто
        // переиспользуем его.
        val sourceBitmap =
            if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)

        val processed: Bitmap = try {
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

        // Фильтрация
        return try {
            applyFilters(processed, settings)
        } catch (e: Exception) {
            Log.e(TAG, "Filtering stage failed", e)
            processed
        }
    }

    /**
     * Упрощённый вызов для типичного OCR‑pipeline.
     *
     * @param bitmap Исходное изображение.
     * @return Bitmap, обработанный под OCR.
     */
    fun preprocessForOcr(bitmap: Bitmap): Bitmap = prepareBaseImage(
        bitmap,
        ScanSettings(
            detectDocument = true,
            denoiseEnabled = true,
            contrastLevel = 1.0f,
            brightnessLevel = 0f,
            sharpenLevel = 0f,
            binarizationEnabled = false,
            autoRotateEnabled = true
        )
    )

    /** Только шумоподавление. */
    fun denoise(bitmap: Bitmap): Bitmap = prepareBaseImage(
        bitmap, ScanSettings(denoiseEnabled = true)
    )

    /** Параметризация яркости. */
    fun adjustBrightness(bitmap: Bitmap, brightness: Float): Bitmap =
        prepareBaseImage(bitmap, ScanSettings(brightnessLevel = brightness))

    /** Усиление контраста. */
    fun enhanceContrast(bitmap: Bitmap): Bitmap =
        prepareBaseImage(bitmap, ScanSettings(contrastLevel = 1.2f))

    /** Резкая обработка. */
    fun sharpen(bitmap: Bitmap): Bitmap =
        prepareBaseImage(bitmap, ScanSettings(sharpenLevel = 1.0f))

    /** Бинаризация. */
    fun binarize(bitmap: Bitmap): Bitmap =
        prepareBaseImage(bitmap, ScanSettings(binarizationEnabled = true))

    /* ------------------------------------------------------------------- *
     *  Внутренние helpers                                                *
     * ------------------------------------------------------------------- */

    /**
     * Детектирует прямоугольник документа и корректирует перспективу.
     * При ошибке возвращает исходный bitmap.
     */
    private fun correctPerspective(source: Bitmap): Bitmap {
        return try {
            val quadrilateral = documentDetector.detectDocumentQuadrilateral(source)
            documentDetector.correctPerspective(source, quadrilateral) ?: source
        } catch (e: Exception) {
            Log.w(TAG, "Perspective correction failed", e)
            source
        }
    }

    /**
     * Удаляет небольшие искажения наклона.
     *
     * @return Новый bitmap с исправленным углом. Если коррекция не требуется –
     *         возвращается исходный bitmap.
     */
    private fun deskew(source: Bitmap): Bitmap {
        val mat = Mat()
        try {
            Utils.bitmapToMat(source, mat)
            // Порог по умолчанию – 0°, максимум ~45°
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


    // Переменные для хранения состояния угла наклона между вызовами.
    private var currentAngleDeg = 0.0
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
                    val rhoTheta = lines.get(i, 0) // [rho, theta]
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
     * Полный фильтр‑pipeline.
     *
     * @param baseBitmap Bitmap после геометрической коррекции.
     * @param settings Параметры обработки.
     * @return Отфильтрованное изображение.
     */
    fun applyFilters(baseBitmap: Bitmap, settings: ScanSettings): Bitmap {
        val mat = Mat()
        try {
            Utils.bitmapToMat(baseBitmap, mat)

            // Перевод в градации серого (если уже 1‑канальный – просто копируем)
            val gray = Mat()
            try {
                if (mat.channels() == 1) {
                    mat.copyTo(gray)
                } else {
                    Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
                }

                // Шаги фильтрации
                if (settings.denoiseEnabled) denoise(gray)

                // Контраст/яркость
                val contrast = settings.contrastLevel ?: 1.0f
                val brightness = settings.brightnessLevel ?: 0f
                if (contrast != 1.0f || brightness != 0f) {
                    gray.convertTo(gray, -1, contrast.toDouble(), brightness.toDouble())
                }

                // Резкость
                val sharpenLevel = settings.sharpenLevel ?: 0f
                if (sharpenLevel > 0f) sharpen(gray, sharpenLevel)

                // Бинаризация
                if (settings.binarizationEnabled) {
                    val blockSize = 31          // разумный для типичного DPI
                    Imgproc.adaptiveThreshold(
                        gray,
                        gray,
                        255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY,
                        blockSize,
                        10.0
                    )
                }

                // Итоговый bitmap
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

    /** Шумоподавление – билинейный фильтр. */
    private fun denoise(mat: Mat) {
        val dst = Mat()
        try {
            Imgproc.bilateralFilter(mat, dst, 5, 75.0, 75.0)
            mat.release()
            dst.copyTo(mat)
        } finally {
            dst.release()
        }
    }

    /** Резкая фильтрация – линейный kernel. */
    private fun sharpen(mat: Mat, level: Float) {
        val kernel = Mat(3, 3, CvType.CV_32F)
        try {
            val data = floatArrayOf(
                0f, -1f, 0f,
                -1f, 5f + level, -1f,
                0f, -1f, 0f
            )
            kernel.put(0, 0, data)
            Imgproc.filter2D(mat, mat, -1, kernel)
        } finally {
            kernel.release()
        }
    }
}
