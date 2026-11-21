// ============================================================
// data/preprocessing/ImagePreprocessor.kt — ИСПРАВЛЕННАЯ ВЕРСИЯ
// ============================================================
package com.arny.mlscanner.data.preprocessing

import com.arny.mlscanner.ScanTrace
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PointF
import android.util.Log
import androidx.core.graphics.createBitmap
import com.arny.mlscanner.domain.models.ScanSettings
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
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
    private val documentDetector by lazy { DocumentDetector() }

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
     * Полный pipeline для OCR: moire removal + deskew + grayscale + фильтры.
     * Результат — оптимизированный для Tesseract (grayscale).
     * НЕ используется для preview!
     */
    fun prepareBaseImage(
        bitmap: Bitmap,
        settings: ScanSettings,
        autoCrop: Boolean = true,
    ): Bitmap {
        ScanTrace.log { "prep.start openCv=$openCvInitialized autoCrop=$autoCrop autoRotate=${settings.autoRotateEnabled} filters=${settings.hasFiltersApplied}" }
        if (!openCvInitialized) return traceImageStage("filters", bitmap) { applyPreviewFilters(bitmap, settings) }

        // Проверенная последовательная цепочка (кроп -> ориентация -> деворп). Сводная карта
        // давала резче край на синтетике, но на живых фото результат стал хуже — откатлено.
        val cropped = traceImageStage("crop", bitmap) {
            if (autoCrop) cropDocumentIfNeeded(bitmap) else bitmap
        }
        val oriented = traceImageStage("orientation", cropped) {
            if (autoCrop) orientIfNeeded(cropped, settings) else cropped
        }
        val geometry = traceImageStage("dewarp", oriented) { dewarpIfNeeded(oriented) }
        val filtered = traceImageStage("filters", geometry) { applyPreviewFilters(geometry, settings) }
        val output = traceImageStage("ocrPolarity", filtered) { applyOcrFilters(filtered) }
        listOf(cropped, oriented, geometry, filtered).distinct().forEach {
            if (it !== bitmap && it !== output) it.recycle()
        }
        return output
    }


    private inline fun traceImageStage(name: String, input: Bitmap, block: () -> Bitmap): Bitmap {
        val started = ScanTrace.now()
        return block().also { output ->
            ScanTrace.log {
                "prep.$name input=${input.width}x${input.height} output=${output.width}x${output.height} newBitmap=${output !== input} elapsedMs=${ScanTrace.elapsed(started)}"
            }
        }
    }

    /**
     * Только геометрия (деворп), без фотометрических фильтров.
     *
     * Нужна там, где фильтры применяются отдельно: multi-pass Tesseract подбирает свои
     * профили, но выпрямление страницы от профиля не зависит и не должно теряться.
     */
    fun dewarpGeometry(bitmap: Bitmap): Bitmap = dewarpIfNeeded(bitmap)

    /**
     * Выпрямление перспективы по заданному пользователем контуру (4 угла).
     * В отличие от [cropDocumentIfNeeded] ничего не детектирует: углы задал человек.
     */
    fun rectifyPerspective(
        bitmap: Bitmap,
        quad: com.arny.mlscanner.domain.models.Quadrilateral
    ): Bitmap? = try {
        documentDetector.correctPerspective(bitmap, quad)
    } catch (e: Throwable) {
        Log.e(TAG, "perspective rectify failed", e)
        null
    }

    /** Контур листа для предзаполнения редактора обрезки (подсказка, не применяется сама). */
    fun detectDocumentQuad(bitmap: Bitmap): com.arny.mlscanner.domain.models.Quadrilateral? =
        try {
            documentDetector.detectDocumentQuadrilateral(bitmap)
        } catch (e: Throwable) {
            Log.e(TAG, "quad detection failed", e)
            null
        }

    /**
     * Программный апскейл маленького фрагмента до [targetLongSide] по длинной стороне.
     *
     * Только классический CV (без нейросети): bicubic-ресайз + CLAHE по яркости (LAB)
     * + unsharp mask. Восстанавливает читаемость мелкого текста; качество близко к
     * FSRCNN, но без contrib-модуля dnn_superres, которого нет в org.opencv:opencv 4.9.0.
     * Если OpenCV-натива нет — обычный bicubic через Bitmap.scale.
     */
    fun upscaleSmallRegion(bitmap: Bitmap, targetLongSide: Int): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide >= targetLongSide) return bitmap

        val factor = (targetLongSide.toFloat() / maxSide).coerceIn(1f, 3f)
        val newW = (bitmap.width * factor).toInt().coerceAtLeast(1)
        val newH = (bitmap.height * factor).toInt().coerceAtLeast(1)

        if (!openCvInitialized) {
            return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        }

        val src = Mat()
        val lab = Mat()
        val up = Mat()
        try {
            Utils.bitmapToMat(bitmap, src)
            // Bicubic ресайз
            Imgproc.resize(src, up, org.opencv.core.Size(newW.toDouble(), newH.toDouble()), 0.0, 0.0, Imgproc.INTER_CUBIC)
            // CLAHE по L-каналу (усиление локального контраста без раскраски шума)
            Imgproc.cvtColor(up, lab, Imgproc.COLOR_RGBA2RGB)
            Imgproc.cvtColor(lab, lab, Imgproc.COLOR_RGB2Lab)
            val channels = ArrayList<Mat>(3)
            Core.split(lab, channels)
            val clahe = Imgproc.createCLAHE(2.0, org.opencv.core.Size(8.0, 8.0))
            clahe.apply(channels[0], channels[0])
            Core.merge(channels, lab)
            Imgproc.cvtColor(lab, up, Imgproc.COLOR_Lab2RGB)
            // Unsharp mask: up*1.5 - blur*0.5
            val blurred = Mat()
            Imgproc.GaussianBlur(up, blurred, org.opencv.core.Size(0.0, 0.0), 1.2)
            Core.addWeighted(up, 1.5, blurred, -0.5, 0.0, up)
            blurred.release()

            val result = createBitmap(newW, newH)
            Utils.matToBitmap(up, result)
            return result
        } catch (e: Exception) {
            Log.e(TAG, "upscaleSmallRegion failed", e)
            return Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        } finally {
            src.release()
            lab.release()
            up.release()
        }
    }

    private fun cropDocumentIfNeeded(bitmap: Bitmap): Bitmap {
        return try {
            val quad = documentDetector.detectDocumentQuadrilateral(bitmap) ?: return bitmap
            val area = quadArea(quad) / (bitmap.width.toDouble() * bitmap.height)
            // Page already fills the frame: cropping would only resample it.
            if (area > 0.95) return bitmap
            val cropped = documentDetector.correctPerspective(bitmap, quad) ?: return bitmap
            Log.d(TAG, "crop: ${bitmap.width}x${bitmap.height} -> " +
                "${cropped.width}x${cropped.height} (quad %.2f of frame)".format(area))
            cropped
        } catch (e: Throwable) {
            Log.e(TAG, "crop failed, keeping the original", e)
            bitmap
        }
    }

    private fun quadArea(q: com.arny.mlscanner.domain.models.Quadrilateral): Double {
        val p = q.points
        var sum = 0.0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % p.size]
            sum += a.x.toDouble() * b.y - b.x.toDouble() * a.y
        }
        return Math.abs(sum) / 2.0
    }

    /**
     * Поворот листа, лежащего на боку, по нативному детектору ориентации.
     *
     * Учитывает [ScanSettings.autoRotateEnabled]; при отказе или неубедительном решении
     * возвращает исходный Bitmap. 180° детектор не отличает — признак только направление
     * строк, а ошибочный поворот хуже его отсутствия.
     */
    private fun orientIfNeeded(bitmap: Bitmap, settings: ScanSettings): Bitmap {
        if (!settings.autoRotateEnabled || !NativeScan.isAvailable) return bitmap
        return try {
            val rgba = Mat()
            Utils.bitmapToMat(bitmap, rgba)
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val w = gray.cols()
            val h = gray.rows()
            val bytes = ByteArray(w * h)
            gray.get(0, 0, bytes)
            rgba.release()
            gray.release()

            val degrees = NativeScan.bestRotationDegrees(bytes, w, h)
            if (degrees == 0) {
                bitmap
            } else {
                val m = android.graphics.Matrix()
                m.postRotate(degrees.toFloat())
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
                Log.d(TAG, "orientation: rotated ${bitmap.width}x${bitmap.height} by $degrees -> " +
                    "${rotated.width}x${rotated.height}")
                rotated
            }
        } catch (e: Throwable) {
            Log.e(TAG, "orientation failed, keeping the original", e)
            bitmap
        }
    }

    /**
     * Выпрямление изогнутой страницы нативным модулем (libml_scanner_jni).
     *
     * Возвращает исходный Bitmap без изменений, если страница уже ровная (нативный код
     * сам решает это по независимой метрике) или если библиотека недоступна.
     */
    private fun dewarpIfNeeded(bitmap: Bitmap): Bitmap {
        if (!NativeScan.isAvailable) return bitmap
        val startNs = System.nanoTime()
        return try {
            val rgba = Mat()
            Utils.bitmapToMat(bitmap, rgba)
            val gray = Mat()
            Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
            val w = gray.cols()
            val h = gray.rows()
            val bytes = ByteArray(w * h)
            gray.get(0, 0, bytes)
            rgba.release()
            gray.release()

            val field = NativeScan.dewarpField(bytes, w, h)
            if (field == null) {
                bitmap
            } else {
                val fixed = NativeScan.dewarpApply(bytes, w, h, field)
                if (fixed == null || fixed.isEmpty()) {
                    bitmap
                } else {
                    val outH = field[1].toInt()
                    val out = Mat(outH, w, CvType.CV_8UC1)
                    out.put(0, 0, fixed)
                    val rgb = Mat()
                    Imgproc.cvtColor(out, rgb, Imgproc.COLOR_GRAY2RGBA)
                    val result = createBitmap(out.cols(), out.rows())
                    Utils.matToBitmap(rgb, result)
                    out.release()
                    rgb.release()
                    Log.d(TAG, "dewarp applied: ${w}x$h -> ${result.width}x${result.height} " +
                        "in ${(System.nanoTime() - startNs) / 1_000_000} ms")
                    result
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "dewarp failed, keeping the original", e)
            bitmap
        }
    }

/**
     * Preprocessing для Tesseract.
     * 
     * Tesseract хорошо работает с:
     * - Grayscale
     * - Инверсией тёмного фона
     * - Агрессивным контрастом
     * - Adaptive threshold
     */
    fun prepareForTesseract(
        bitmap: Bitmap,
        settings: ScanSettings,
        autoCrop: Boolean = true,
    ): Bitmap =
        prepareBaseImage(bitmap, settings, autoCrop)

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
            
            // Для больших вертикальных изображений (книжные обложки) с тёмными областями
            // не инвертируем автоматически, т.к. текст может быть светлым на тёмном фоне
            val ratio = baseBitmap.width.toFloat() / baseBitmap.height.coerceAtLeast(1)
            val isVerticalImage = ratio < 0.8f
            val isLargeImage = minOf(baseBitmap.width, baseBitmap.height) > 800
            
            val needsInversion = mean < BRIGHTNESS_DARK_THRESHOLD && 
                                 !(isVerticalImage && isLargeImage && mean < 30)

            if (needsInversion) {
                Log.d(TAG, "OCR: dark bg (mean=${"%.0f".format(mean)}), inverting")
                Core.bitwise_not(gray, gray)
                Imgproc.cvtColor(gray, mat, Imgproc.COLOR_GRAY2RGBA)
            } else if (mean < BRIGHTNESS_DARK_THRESHOLD) {
                Log.d(TAG, "OCR: dark bg (mean=${"%.0f".format(mean)}), but skipping inversion (vertical large image)")
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
