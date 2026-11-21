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

    private const val SIDE = 256
    private const val BAND_LOW = 0.05    // cycles/px -> period <= 20 px
    private const val BAND_HIGH = 0.225  // cycles/px -> period >= 4.4 px
    private const val MOIRE_THRESHOLD = 8.0

    /**
     * Сила периодической составляющей в полосе частот, где живёт муар экрана.
     *
     * Прежний признак (mean/stdDev магнитуды Собеля) не различал ничего: у обычного текста
     * градиенты на штрихах дают mean≈155…190 и stdDev≈220…265 при порогах 8/25, то есть
     * вычитание муара применялось к каждой странице и портило тонкие штрихи.
     *
     * Здесь считается отношение пика к среднему по спектру в кольце 0.05…0.225 цикл/пиксель
     * (периоды 4…20 px). Пиксельная сетка экрана и её биения дают там узкий пик; строчный
     * текст даёт широкий спектр с максимумом на межстрочном интервале (период 30…60 px,
     * то есть вне полосы).
     */
    fun moireScore(bitmap: Bitmap): Double {
        val mat = Mat()
        val gray = Mat()
        val small = Mat()
        val f = Mat()
        val grayF = Mat()
        val spec = Mat()
        val planes = ArrayList<Mat>()
        try {
            Utils.bitmapToMat(bitmap, mat)
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.resize(gray, small, Size(SIDE.toDouble(), SIDE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            small.convertTo(f, CvType.CV_32F)

            // Hann window against border leakage into the band.
            val win = Mat.zeros(SIDE, SIDE, CvType.CV_32F)
            for (y in 0 until SIDE) {
                val wy = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * y / (SIDE - 1))
                for (x in 0 until SIDE) {
                    val wx = 0.5 - 0.5 * Math.cos(2.0 * Math.PI * x / (SIDE - 1))
                    win.put(y, x, wy * wx)
                }
            }
            Core.multiply(f, win, grayF)
            win.release()

            Core.dft(grayF, spec, Core.DFT_COMPLEX_OUTPUT)
            Core.split(spec, planes)
            val mag = Mat()
            Core.magnitude(planes[0], planes[1], mag)

            var peak = 0.0
            var sum = 0.0
            var count = 0
            val half = SIDE / 2
            for (y in 0 until SIDE) {
                val iy = if (y < half) y else y - SIDE   // wrap the upper half to negative
                val fy = iy.toDouble() / SIDE
                for (x in 0 until SIDE) {
                    val ix = if (x < half) x else x - SIDE
                    val fx = ix.toDouble() / SIDE
                    val r = Math.hypot(fx, fy)
                    if (r < BAND_LOW || r > BAND_HIGH) continue
                    val v = mag.get(y, x)[0]
                    if (v > peak) peak = v
                    sum += v
                    count++
                }
            }
            mag.release()
            if (count == 0) return 1.0
            return peak / (sum / count + 1e-9)
        } catch (e: Exception) {
            Log.e(TAG, "moireScore failed", e)
            return 1.0
        } finally {
            for (m in planes) m.release()
            spec.release()
            grayF.release()
            f.release()
            small.release()
            gray.release()
            mat.release()
        }
    }

    
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
        // Отключено по результатам замеров на устройстве (OnePlus 6T, Android 11):
        //
        //  - прежний признак (mean/stdDev магнитуды Собеля) срабатывал на любой странице с
        //    текстом: mean=155…190, stdDev=220…265 при порогах 8/25;
        //  - спектральный признак тоже не разделяет: обычная страница даёт 84.8, фотография
        //    экрана с муаром — 84.2 (буквенный шаг попадает в ту же полосу частот);
        //  - само вычитание (bilateral σ=50 + median 3 + unsharp) ухудшает распознавание:
        //    кириллица CER 0.038 → 0.167; на реальном фото экрана Tesseract 105 → 76 знаков,
        //    Huawei ML Kit 306 → 193.
        //
        // Пока нет образца, на котором фильтр доказанно помогает, вычитание не применяется:
        // страница отдаётся без изменений. removeMoire остаётся доступным для явного вызова.
        return bitmap
    }
}
