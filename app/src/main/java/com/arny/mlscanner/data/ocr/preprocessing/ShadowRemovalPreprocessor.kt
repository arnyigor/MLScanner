package com.arny.mlscanner.data.ocr.preprocessing

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Продвинутая предобработка изображений для OCR.
 * 
 * Включает:
 * - Shadow Removal (удаление теней через division)
 * - Advanced CLAHE (адаптивное улучшение контраста)
 * - Noise Reduction (шумоподавление)
 * - Deskew (выравнивание наклона)
 */
object ShadowRemovalPreprocessor {
    
    private const val TAG = "ShadowRemoval"
    
    /**
     * Удаляет тени и неравномерное освещение с изображения.
     * 
     * Использует метод division: original / blurred = normalized
     * Это эффективно убирает градиенты освещения.
     */
    fun removeShadows(bitmap: Bitmap): Bitmap {
        return try {
            val src = Mat()
            Utils.bitmapToMat(bitmap, src)
            
            // Конвертируем в grayscale
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            
            // Создаём сильно размытую версию (фон освещения)
            val blurred = Mat()
            val kernelSize = calculateOptimalKernelSize(bitmap.width, bitmap.height)
            Imgproc.GaussianBlur(gray, blurred, Size(kernelSize, kernelSize), 0.0)
            
            // Нормализуем: original / blurred * 255
            val normalized = Mat()
            Core.divide(gray, blurred, normalized, 255.0)
            
            // Конвертируем обратно в 8-bit
            val result = Mat()
            normalized.convertTo(result, gray.type())
            
            // CLAHE для улучшения контраста после нормализации
            val clahe = Imgproc.createCLAHE(2.5, Size(8.0, 8.0))
            val enhanced = Mat()
            clahe.apply(result, enhanced)
            
            val output = Bitmap.createBitmap(enhanced.cols(), enhanced.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(enhanced, output)
            
            // Cleanup
            src.release()
            gray.release()
            blurred.release()
            normalized.release()
            result.release()
            enhanced.release()
            
            Log.d(TAG, "Shadow removal applied (kernel=$kernelSize)")
            output
        } catch (e: Exception) {
            Log.w(TAG, "Shadow removal failed, using original", e)
            bitmap
        }
    }
    
    /**
     * Вычисляет оптимальный размер ядра для размытия.
     * Должен быть нечётным и пропорциональным размеру изображения.
     */
    private fun calculateOptimalKernelSize(width: Int, height: Int): Double {
        val minDim = minOf(width, height)
        val size = when {
            minDim < 500 -> 31
            minDim < 1000 -> 51
            minDim < 2000 -> 71
            else -> 91
        }
        return size.toDouble()
    }
    
    /**
     * Применяет продвинутую CLAHE с адаптивными параметрами.
     */
    fun applyAdvancedCLAHE(bitmap: Bitmap, clipLimit: Double = 3.0): Bitmap {
        return try {
            val src = Mat()
            Utils.bitmapToMat(bitmap, src)
            
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            
            // Адаптивный размер тайла в зависимости от размера изображения
            val tileSize = calculateOptimalTileSize(bitmap.width, bitmap.height)
            
            val clahe = Imgproc.createCLAHE(clipLimit, Size(tileSize, tileSize))
            val enhanced = Mat()
            clahe.apply(gray, enhanced)
            
            val output = Bitmap.createBitmap(enhanced.cols(), enhanced.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(enhanced, output)
            
            src.release()
            gray.release()
            enhanced.release()
            
            Log.d(TAG, "Advanced CLAHE applied (clip=$clipLimit, tile=$tileSize)")
            output
        } catch (e: Exception) {
            Log.w(TAG, "Advanced CLAHE failed, using original", e)
            bitmap
        }
    }
    
    /**
     * Вычисляет оптимальный размер тайла для CLAHE.
     */
    private fun calculateOptimalTileSize(width: Int, height: Int): Double {
        val minDim = minOf(width, height)
        val size = when {
            minDim < 500 -> 4.0
            minDim < 1000 -> 8.0
            minDim < 2000 -> 12.0
            else -> 16.0
        }
        return size
    }
    
    /**
     * Применяет шумоподавление (Non-Local Means Denoising).
     */
    fun reduceNoise(bitmap: Bitmap, strength: Float = 10f): Bitmap {
        return try {
            val src = Mat()
            Utils.bitmapToMat(bitmap, src)
            
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            
            val denoised = Mat()
            // fastNlMeansDenoising для grayscale изображений
            org.opencv.photo.Photo.fastNlMeansDenoising(gray, denoised, strength, 7, 21)
            
            val output = Bitmap.createBitmap(denoised.cols(), denoised.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(denoised, output)
            
            src.release()
            gray.release()
            denoised.release()
            
            Log.d(TAG, "Noise reduction applied (strength=$strength)")
            output
        } catch (e: Exception) {
            Log.w(TAG, "Noise reduction failed, using original", e)
            bitmap
        }
    }
    
    /**
     * Применяет морфологические операции для очистки текста.
     */
    fun morphologicalCleanup(bitmap: Bitmap): Bitmap {
        return try {
            val src = Mat()
            Utils.bitmapToMat(bitmap, src)
            
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            
            // Бинаризация
            val binary = Mat()
            Imgproc.threshold(gray, binary, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            
            // Морфологическое закрытие (убирает мелкие дыры в буквах)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(2.0, 2.0))
            val closed = Mat()
            Imgproc.morphologyEx(binary, closed, Imgproc.MORPH_CLOSE, kernel)
            
            val output = Bitmap.createBitmap(closed.cols(), closed.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(closed, output)
            
            src.release()
            gray.release()
            binary.release()
            closed.release()
            kernel.release()
            
            Log.d(TAG, "Morphological cleanup applied")
            output
        } catch (e: Exception) {
            Log.w(TAG, "Morphological cleanup failed, using original", e)
            bitmap
        }
    }
    
    /**
     * Комбинированная предобработка для сложных документов.
     * 
     * Применяет последовательно:
     * 1. Shadow removal
     * 2. Noise reduction
     * 3. Advanced CLAHE
     */
    fun preprocessComplex(bitmap: Bitmap): Bitmap {
        var result = bitmap
        
        try {
            // 1. Удаление теней
            val shadowRemoved = removeShadows(result)
            if (shadowRemoved !== result) {
                result = shadowRemoved
            }
            
            // 2. Шумоподавление (лёгкое)
            val denoised = reduceNoise(result, strength = 7f)
            if (denoised !== result && denoised !== bitmap) {
                if (result !== bitmap) result.recycle()
                result = denoised
            }
            
            // 3. Улучшение контраста
            val enhanced = applyAdvancedCLAHE(result, clipLimit = 2.5)
            if (enhanced !== result && enhanced !== bitmap) {
                if (result !== bitmap) result.recycle()
                result = enhanced
            }
            
            Log.d(TAG, "Complex preprocessing completed")
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Complex preprocessing failed", e)
            if (result !== bitmap && !result.isRecycled) {
                result.recycle()
            }
            return bitmap
        }
    }
    
    /**
     * Быстрая предобработка для простых документов.
     * 
     * Применяет только CLAHE.
     */
    fun preprocessSimple(bitmap: Bitmap): Bitmap {
        return applyAdvancedCLAHE(bitmap, clipLimit = 2.0)
    }
    
    /**
     * Анализирует изображение и определяет, нужна ли сложная предобработка.
     */
    fun needsComplexPreprocessing(bitmap: Bitmap): Boolean {
        return try {
            val src = Mat()
            Utils.bitmapToMat(bitmap, src)
            
            val gray = Mat()
            Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGBA2GRAY)
            
            // Вычисляем стандартное отклонение яркости
            val meanMat = org.opencv.core.MatOfDouble()
            val stddevMat = org.opencv.core.MatOfDouble()
            Core.meanStdDev(gray, meanMat, stddevMat)
            
            val stdValue = stddevMat.get(0, 0)[0]
            
            src.release()
            gray.release()
            meanMat.release()
            stddevMat.release()
            
            // Если стандартное отклонение низкое, значит контраст плохой
            // или есть неравномерное освещение
            val needsComplex = stdValue < 50.0
            
            Log.d(TAG, "Image analysis: stddev=${"%.1f".format(stdValue)}, " +
                      "needsComplex=$needsComplex")
            
            needsComplex
        } catch (e: Exception) {
            Log.w(TAG, "Image analysis failed", e)
            false
        }
    }
}
