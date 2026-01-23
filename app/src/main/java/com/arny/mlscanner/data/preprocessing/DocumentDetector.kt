package com.arny.mlscanner.data.preprocessing

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import androidx.core.graphics.createBitmap

/**
 * Детектор документов и коррекции перспективы
 * В соответствии с требованиями TECH.md:
 * - Детекция краев документа
 * - Выпрямление перспективы (Document De-Skew)
 * - Поддержка разных форматов документов
 */
class DocumentDetector {
    companion object {
        private const val TAG = "DocumentDetector"
        
        init {
            // Загружаем OpenCV нативные библиотеки
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "OpenCV initialization failed")
            }
        }
    }

    /**
     * Детекция четырехугольника документа на изображении
     */
    fun detectDocumentQuadrilateral(bitmap: Bitmap): Quadrilateral? {
        try {
            // Конвертируем Bitmap в Mat для обработки OpenCV
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            
            // Создаем копию для обработки
            val processedMat = Mat()
            Imgproc.cvtColor(mat, processedMat, Imgproc.COLOR_RGBA2GRAY)
            
            // Повышаем контрастность
            processedMat.convertTo(processedMat, -1, 1.5, 30.0) // alpha (contrast), beta (brightness)
            
            // Применяем размытие для уменьшения шума
            Imgproc.GaussianBlur(processedMat, processedMat, Size(5.0, 5.0), 0.0)
            
            // Применяем Canny edge detector
            val edges = Mat()
            Imgproc.Canny(processedMat, edges, 50.0, 150.0)
            
            // Находим контуры
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            
            // Находим наибольший четырехугольный контур
            var largestContour: MatOfPoint? = null
            var maxArea = 0.0
            
            for (contour in contours) {
                val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.01 * peri, true)
                
                // Проверяем, является ли контур четырехугольником
                if (approx.toArray().size == 4) {
                    val area = Imgproc.contourArea(contour)
                    if (area > maxArea) {
                        maxArea = area
                        largestContour = contour
                    }
                }
            }
            
            // Если найден подходящий контур, возвращаем его
            if (largestContour != null) {
                val points = largestContour.toArray()
                // Сортируем точки по углам (верх-лево, верх-право, низ-право, низ-лево)
                val sortedPoints = sortPoints(points)
                return Quadrilateral(sortedPoints)
            }
            
            processedMat.release()
            mat.release()
            hierarchy.release()
            edges.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting document quadrilateral", e)
        }
        
        return null
    }

    /**
     * Коррекция перспективы документа
     */
    fun correctPerspective(bitmap: Bitmap, quadrilateral: Quadrilateral?): Bitmap? {
        if (quadrilateral == null) return bitmap
        
        try {
            val srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)
            
            // Определяем размеры выходного изображения
            val width = bitmap.width
            val height = bitmap.height
            
            // Создаем точки назначения (прямоугольник)
            val dstPoints = MatOfPoint2f(
                Point(0.0, 0.0),           // верх-лево
                Point(width.toDouble(), 0.0), // верх-право
                Point(width.toDouble(), height.toDouble()), // низ-право
                Point(0.0, height.toDouble())  // низ-лево
            )
            
            // Создаем матрицу преобразования перспективы
            val srcPoints = MatOfPoint2f(*quadrilateral.points.map { Point(it.x, it.y) }.toTypedArray())
            val transformMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            
            // Применяем преобразование перспективы
            val dstMat = Mat()
            Imgproc.warpPerspective(srcMat, dstMat, transformMatrix, Size(width.toDouble(), height.toDouble()))
            
            // Конвертируем обратно в Bitmap
            val resultBitmap = createBitmap(dstMat.cols(), dstMat.rows())
            Utils.matToBitmap(dstMat, resultBitmap)
            
            // Освобождаем ресурсы
            srcMat.release()
            dstMat.release()
            transformMatrix.release()
            srcPoints.release()
            dstPoints.release()
            
            return resultBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error correcting perspective", e)
            return null
        }
    }

    /**
     * Сортировка точек по углам (верх-лево, верх-право, низ-право, низ-лево)
     */
    private fun sortPoints(points: Array<Point>): Array<Point> {
        val sorted = points.sortedBy { it.x + it.y } // верх-лево будет первым
        val p1 = sorted[0] // верх-лево
        val p4 = sorted[3] // низ-право
        
        // Остальные две точки
        val remaining = sorted.subList(1, 3).sortedBy { it.x }
        val p2 = remaining[0] // верх-право или низ-лево (в зависимости от x)
        val p3 = remaining[1] // другая точка
        
        // Правильная сортировка: верх-лево, верх-право, низ-право, низ-лево
        return arrayOf(p1, p2, p4, p3)
    }

    /* ------------------------------------------------------------------ */
    /*  Методы для тестирования                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Детекция всех возможных четырехугольников на изображении
     */
    fun detectQuadrilaterals(bitmap: Bitmap): List<List<org.opencv.core.Point>> {
        val quadrilaterals = mutableListOf<List<org.opencv.core.Point>>()
        try {
            // Конвертируем Bitmap в Mat для обработки OpenCV
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            
            // Создаем копию для обработки
            val processedMat = Mat()
            Imgproc.cvtColor(mat, processedMat, Imgproc.COLOR_RGBA2GRAY)
            
            // Повышаем контрастность
            processedMat.convertTo(processedMat, -1, 1.5, 30.0) // alpha (contrast), beta (brightness)
            
            // Применяем размытие для уменьшения шума
            Imgproc.GaussianBlur(processedMat, processedMat, Size(5.0, 5.0), 0.0)
            
            // Применяем Canny edge detector
            val edges = Mat()
            Imgproc.Canny(processedMat, edges, 50.0, 150.0)
            
            // Находим контуры
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            
            // Находим все четырехугольные контуры
            for (contour in contours) {
                val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.01 * peri, true)
                
                // Проверяем, является ли контур четырехугольником
                if (approx.toArray().size == 4) {
                    quadrilaterals.add(approx.toList())
                }
            }
            
            processedMat.release()
            mat.release()
            hierarchy.release()
            edges.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting quadrilaterals", e)
        }
        
        return quadrilaterals
    }

    /**
     * Нахождение углов документа
     */
    fun findDocumentCorners(bitmap: Bitmap): List<org.opencv.core.Point> {
        val quadrilateral = detectDocumentQuadrilateral(bitmap)
        return if (quadrilateral != null) {
            quadrilateral.points.toList()
        } else {
            emptyList()
        }
    }

    /**
     * Получение границ документа
     */
    fun getDocumentBoundary(bitmap: Bitmap): Rect {
        val quadrilateral = detectDocumentQuadrilateral(bitmap)
        return if (quadrilateral != null && quadrilateral.isValid) {
            // Находим минимальные и максимальные координаты
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE
            
            for (point in quadrilateral.points) {
                minX = minOf(minX, point.x.toFloat())
                minY = minOf(minY, point.y.toFloat())
                maxX = maxOf(maxX, point.x.toFloat())
                maxY = maxOf(maxY, point.y.toFloat())
            }
            
            Rect(minX.toInt(), minY.toInt(), maxX.toInt(), maxY.toInt())
        } else {
            // Если не удалось найти документ, возвращаем границы всего изображения
            Rect(0, 0, bitmap.width, bitmap.height)
        }
    }
}

/**
 * Класс для представления четырехугольника документа
 */
data class Quadrilateral(
    val points: Array<org.opencv.core.Point>,
    val isValid: Boolean = points.size == 4
) {
    data class Point(val x: Float, val y: Float)
    
    fun getTopLeft() = if (points.isNotEmpty()) points[0] else Point(0f, 0f)
    fun getTopRight() = if (points.size > 1) points[1] else Point(0f, 0f)
    fun getBottomRight() = if (points.size > 2) points[2] else Point(0f, 0f)
    fun getBottomLeft() = if (points.size > 3) points[3] else Point(0f, 0f)
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