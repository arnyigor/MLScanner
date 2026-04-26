package com.arny.mlscanner.data.ocr.preprocessing

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Удаление муара (интерференционных полос) с фотографий экранов.
 * 
 * Муар возникает при фотографировании экранов из-за интерференции
 * между пикселями камеры и пикселями экрана.
 */
object MoireRemovalPreprocessor {
    
    private const val TAG = "MoireRemoval"
    
    /**
     * Определяет, есть ли на изображении муар.
     * 
     * Признаки муара:
     * - Высокочастотные периодические паттерны
     * - Полосы в FFT спектре
     * - Высокая дисперсия в высокочастотной области
     */
    fun detectMoire(bitmap: Bitmap): Boolean {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        
        try {
            // Конвертируем в grayscale
            val gray = Mat()
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            
            // Уменьшаем для быстрого анализа
            val small = Mat()
            Imgproc.resize(gray, small, Size(256.0, 256.0))
            
            // Вычисляем градиенты
            val gradX = Mat()
            val gradY = Mat()
            Imgproc.Sobel(small, gradX, CvType.CV_32F, 1, 0, 3)
            Imgproc.Sobel(small, gradY, CvType.CV_32F, 0, 1, 3)
            
            // Вычисляем магнитуду градиента
            val magnitude = Mat()
            Core.magnitude(gradX, gradY, magnitude)
            
            // Вычисляем стандартное отклонение высокочастотных компонент
            val meanMat = MatOfDouble()
            val stdDevMat = MatOfDouble()
            Core.meanStdDev(magnitude, meanMat, stdDevMat)
            
            val meanValue = meanMat.toArray().firstOrNull() ?: 0.0
            val stdDevValue = stdDevMat.get(0, 0)[0]
            
            // Для обычного документа с текстом градиенты локальны. У фото экрана
            // мелкий периодический шум заметен почти по всей площади.
            val hasMoire = meanValue > 8.0 && stdDevValue > 25.0
            
            Log.d(TAG, "Moire detection: mean=$meanValue, stdDev=$stdDevValue, hasMoire=$hasMoire")
            
            gradX.release()
            gradY.release()
            magnitude.release()
            meanMat.release()
            stdDevMat.release()
            small.release()
            gray.release()
            
            return hasMoire
            
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting moire", e)
            return false
        } finally {
            mat.release()
        }
    }
    
    /**
     * Удаляет муар с изображения.
     * 
     * Методы:
     * 1. Gaussian blur для подавления высокочастотных компонент
     * 2. Bilateral filter для сохранения краёв
     * 3. Median filter для удаления периодических паттернов
     */
    fun removeMoire(bitmap: Bitmap): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        
        try {
            // Конвертируем в grayscale
            val gray = Mat()
            if (mat.channels() == 4) {
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            } else if (mat.channels() == 3) {
                Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
            } else {
                mat.copyTo(gray)
            }
            
            // 1. Bilateral filter - сохраняет края, но размывает текстуру
            val bilateral = Mat()
            Imgproc.bilateralFilter(gray, bilateral, 5, 50.0, 50.0)
            
            // 2. Median filter - удаляет периодические паттерны
            val median = Mat()
            Imgproc.medianBlur(bilateral, median, 3)
            
            // 3. Лёгкое повышение резкости для восстановления текста
            val sharpened = Mat()
            val kernel = Mat.ones(3, 3, CvType.CV_32F)
            kernel.put(1, 1, -8.0)
            val temp = Mat()
            Imgproc.filter2D(median, temp, -1, kernel)
            Core.addWeighted(median, 1.5, temp, -0.5, 0.0, sharpened)
            
            // Конвертируем обратно в Bitmap
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val rgba = Mat()
            Imgproc.cvtColor(sharpened, rgba, Imgproc.COLOR_GRAY2RGBA)
            Utils.matToBitmap(rgba, result)
            
            Log.d(TAG, "Moire removed: ${bitmap.width}x${bitmap.height}")
            
            kernel.release()
            temp.release()
            sharpened.release()
            median.release()
            bilateral.release()
            gray.release()
            rgba.release()
            
            return result
            
        } catch (e: Exception) {
            Log.e(TAG, "Error removing moire", e)
            return bitmap
        } finally {
            mat.release()
        }
    }
    
    /**
     * Автоматически определяет и удаляет муар если нужно.
     */
    fun processIfNeeded(bitmap: Bitmap): Bitmap {
        return if (detectMoire(bitmap)) {
            Log.d(TAG, "Moire detected, applying removal")
            removeMoire(bitmap)
        } else {
            Log.d(TAG, "No moire detected")
            bitmap
        }
    }
}
