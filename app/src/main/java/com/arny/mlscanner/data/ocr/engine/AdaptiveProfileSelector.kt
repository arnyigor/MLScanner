package com.arny.mlscanner.data.ocr.engine

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Автоматический выбор оптимального профиля OCR на основе анализа изображения.
 * 
 * Анализирует:
 * - Размер и соотношение сторон
 * - Контраст и яркость
 * - Наличие теней
 * - Плотность текста
 * - Качество изображения
 */
object AdaptiveProfileSelector {
    
    private const val TAG = "ProfileSelector"
    
    /**
     * Выбирает оптимальные профили для изображения.
     * Возвращает список профилей в порядке приоритета.
     */
    fun selectProfiles(
        bitmap: Bitmap,
        language: TesseractLanguage = TesseractLanguage.RUS_ONLY
    ): List<TesseractProfile> {
        val metrics = analyzeImage(bitmap)
        
        Log.d(TAG, "Image metrics: $metrics")
        
        val profiles = mutableListOf<TesseractProfile>()
        
        // Выбор PSM на основе соотношения сторон
        val psm = selectPSM(metrics)
        
        // Выбор режима предобработки на основе качества
        val preprocessModes = selectPreprocessModes(metrics)
        
        // Генерируем профили
        for (mode in preprocessModes) {
            profiles.add(
                TesseractProfile(
                    name = "${language.code}_${psmName(psm)}_${mode.name.lowercase()}",
                    language = language,
                    psm = psm,
                    preprocessMode = mode
                )
            )
        }
        
        Log.d(TAG, "Selected ${profiles.size} profiles: ${profiles.map { it.name }}")
        
        return profiles
    }
    
    /**
     * Анализирует изображение и возвращает метрики.
     */
    private fun analyzeImage(bitmap: Bitmap): ImageMetrics {
        return try {
            val src = Mat()
            Utils.bitmapToMat(bitmap, src)
            
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            
            // Вычисляем статистику яркости
            val meanMat = org.opencv.core.MatOfDouble()
            val stddevMat = org.opencv.core.MatOfDouble()
            Core.meanStdDev(gray, meanMat, stddevMat)
            
            val meanValue = meanMat.get(0, 0)[0]
            val stdValue = stddevMat.get(0, 0)[0]
            
            // Анализ контраста
            val minMax = Core.minMaxLoc(gray)
            val contrast = minMax.maxVal - minMax.minVal
            
            // Анализ теней (неравномерность освещения)
            val shadowScore = analyzeShadows(gray)
            
            // Анализ плотности текста
            val textDensity = analyzeTextDensity(gray)
            
            // Соотношение сторон
            val aspectRatio = bitmap.width.toFloat() / bitmap.height
            
            src.release()
            gray.release()
            meanMat.release()
            stddevMat.release()
            
            ImageMetrics(
                width = bitmap.width,
                height = bitmap.height,
                aspectRatio = aspectRatio,
                meanBrightness = meanValue,
                stdDevBrightness = stdValue,
                contrast = contrast,
                shadowScore = shadowScore,
                textDensity = textDensity
            )
        } catch (e: Exception) {
            Log.w(TAG, "Image analysis failed, using defaults", e)
            ImageMetrics(
                width = bitmap.width,
                height = bitmap.height,
                aspectRatio = bitmap.width.toFloat() / bitmap.height,
                meanBrightness = 128.0,
                stdDevBrightness = 50.0,
                contrast = 200.0,
                shadowScore = 0.0,
                textDensity = 0.5f
            )
        }
    }
    
    /**
     * Анализирует наличие теней (неравномерность освещения).
     * Возвращает score от 0 (нет теней) до 1 (сильные тени).
     */
    private fun analyzeShadows(gray: Mat): Double {
        return try {
            // Разбиваем изображение на блоки и вычисляем среднюю яркость каждого
            val blockSize = 50
            val rows = gray.rows() / blockSize
            val cols = gray.cols() / blockSize
            
            if (rows < 2 || cols < 2) return 0.0
            
            val blockMeans = mutableListOf<Double>()
            
            for (i in 0 until rows) {
                for (j in 0 until cols) {
                    val roi = gray.submat(
                        i * blockSize,
                        minOf((i + 1) * blockSize, gray.rows()),
                        j * blockSize,
                        minOf((j + 1) * blockSize, gray.cols())
                    )
                    val mean = Core.mean(roi).`val`[0]
                    blockMeans.add(mean)
                    roi.release()
                }
            }
            
            // Вычисляем стандартное отклонение средних яркостей блоков
            val meanOfMeans = blockMeans.average()
            val variance = blockMeans.map { (it - meanOfMeans) * (it - meanOfMeans) }.average()
            val stdDevOfMeans = kotlin.math.sqrt(variance)
            
            // Нормализуем к [0, 1]
            (stdDevOfMeans / 50.0).coerceIn(0.0, 1.0)
        } catch (e: Exception) {
            Log.w(TAG, "Shadow analysis failed", e)
            0.0
        }
    }
    
    /**
     * Анализирует плотность текста на изображении.
     * Возвращает значение от 0 (нет текста) до 1 (плотный текст).
     */
    private fun analyzeTextDensity(gray: Mat): Float {
        return try {
            // Применяем бинаризацию
            val binary = Mat()
            Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            
            // Считаем процент чёрных пикселей
            val blackPixels = Core.countNonZero(binary)
            val totalPixels = binary.rows() * binary.cols()
            val blackRatio = 1.0f - (blackPixels.toFloat() / totalPixels)
            
            binary.release()
            
            // Текст обычно занимает 10-30% площади
            when {
                blackRatio < 0.05f -> 0.1f  // Очень мало текста
                blackRatio < 0.15f -> 0.5f  // Средняя плотность
                blackRatio < 0.35f -> 0.8f  // Высокая плотность
                else -> 0.3f                // Слишком много чёрного (плохое качество)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Text density analysis failed", e)
            0.5f
        }
    }
    
    /**
     * Выбирает оптимальный PSM на основе метрик.
     * 
     * ВАЖНО: PSM_SPARSE_TEXT не гарантирует reading order (Tesseract docs).
     * Используем его только для хаотичных документов (чеки, билеты).
     */
    private fun selectPSM(metrics: ImageMetrics): Int {
        val ratio = metrics.aspectRatio
        val minDim = minOf(metrics.width, metrics.height)
        
        return when {
            // Очень узкое или широкое → одна строка
            ratio < 0.15f || ratio > 8f -> {
                Log.d(TAG, "PSM: SINGLE_LINE (ratio=${"%.2f".format(ratio)})")
                com.googlecode.tesseract.android.TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            }
            
            // Узкое или маленькое → один блок
            ratio < 0.4f || ratio > 3.5f || minDim < 400 -> {
                Log.d(TAG, "PSM: SINGLE_BLOCK (ratio=${"%.2f".format(ratio)}, minDim=$minDim)")
                com.googlecode.tesseract.android.TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            }
            
            // Стандартный случай: PSM_AUTO для многострочных документов
            // PSM_SPARSE_TEXT больше не используется по умолчанию,
            // т.к. он ломает reading order
            else -> {
                Log.d(TAG, "PSM: AUTO (density=${"%.2f".format(metrics.textDensity)})")
                com.googlecode.tesseract.android.TessBaseAPI.PageSegMode.PSM_AUTO
            }
        }
    }
    
    /**
     * Выбирает режимы предобработки на основе метрик.
     * Возвращает список в порядке приоритета.
     */
    private fun selectPreprocessModes(metrics: ImageMetrics): List<PreprocessMode> {
        val modes = mutableListOf<PreprocessMode>()
        
        // Всегда пробуем оригинал
        modes.add(PreprocessMode.ORIGINAL)
        
        // Низкий контраст → CLAHE
        if (metrics.stdDevBrightness < 45.0 || metrics.contrast < 150.0) {
            modes.add(PreprocessMode.CONTRAST_ENHANCED)
            Log.d(TAG, "Low contrast detected (std=${"%.1f".format(metrics.stdDevBrightness)}, " +
                      "contrast=${"%.1f".format(metrics.contrast)})")
        }
        
        // Тени → adaptive threshold
        if (metrics.shadowScore > 0.3) {
            modes.add(PreprocessMode.ADAPTIVE_THRESHOLD)
            Log.d(TAG, "Shadows detected (score=${"%.2f".format(metrics.shadowScore)})")
        }
        
        // Маленькое изображение → upscale
        val minDim = minOf(metrics.width, metrics.height)
        if (minDim < 500) {
            modes.add(PreprocessMode.UPSCALE_2X)
            Log.d(TAG, "Small image detected (minDim=$minDim)")
        }
        
        // Хорошее качество но нужна резкость
        if (metrics.stdDevBrightness > 50.0 && metrics.contrast > 180.0) {
            modes.add(PreprocessMode.SHARPEN_LIGHT)
        }
        
        return modes.distinct()
    }
    
    /**
     * Возвращает короткое имя PSM для профиля.
     */
    private fun psmName(psm: Int): String {
        return when (psm) {
            com.googlecode.tesseract.android.TessBaseAPI.PageSegMode.PSM_SINGLE_LINE -> "line"
            com.googlecode.tesseract.android.TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK -> "block"
            com.googlecode.tesseract.android.TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT -> "sparse"
            else -> "auto"
        }
    }
    
    /**
     * Метрики изображения для анализа.
     */
    data class ImageMetrics(
        val width: Int,
        val height: Int,
        val aspectRatio: Float,
        val meanBrightness: Double,
        val stdDevBrightness: Double,
        val contrast: Double,
        val shadowScore: Double,
        val textDensity: Float
    ) {
        override fun toString(): String {
            return "ImageMetrics(${width}x${height}, ratio=${"%.2f".format(aspectRatio)}, " +
                   "brightness=${"%.1f".format(meanBrightness)}±${"%.1f".format(stdDevBrightness)}, " +
                   "contrast=${"%.1f".format(contrast)}, shadows=${"%.2f".format(shadowScore)}, " +
                   "density=${"%.2f".format(textDensity)})"
        }
    }
}
