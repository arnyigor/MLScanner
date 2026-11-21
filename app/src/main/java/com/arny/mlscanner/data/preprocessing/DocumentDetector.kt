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
import com.arny.mlscanner.domain.models.Quadrilateral

class DocumentDetector {
    companion object {
        private const val TAG = "DocumentDetector"
        
        init {
            if (!OpenCVLoader.initDebug()) {
                Log.e(TAG, "OpenCV initialization failed")
            }
        }
    }

    /**
     * Ищет квад страницы в кадре.
     *
     * Работает по уменьшенной копии (скорость), принимает кандидата только если он выпуклый и
     * занимает не меньше [minAreaFraction] кадра. Возвращает координаты исходного битмапа.
     */
    fun detectDocumentQuadrilateral(bitmap: Bitmap, minAreaFraction: Double = 0.25): Quadrilateral? {
        if (bitmap.width < 32 || bitmap.height < 32) return null
        val kMaxSide = 800.0
        val scale = minOf(1.0, kMaxSide / maxOf(bitmap.width, bitmap.height))

        val mat = Mat()
        val processed = Mat()
        val edges = Mat()
        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        try {
            Utils.bitmapToMat(bitmap, mat)
            Imgproc.cvtColor(mat, processed, Imgproc.COLOR_RGBA2GRAY)
            if (scale < 1.0) {
                Imgproc.resize(
                    processed, processed,
                    Size(bitmap.width * scale, bitmap.height * scale), 0.0, 0.0, Imgproc.INTER_AREA
                )
            }
            Imgproc.GaussianBlur(processed, processed, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(processed, edges, 50.0, 150.0)
            Imgproc.findContours(
                edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE
            )

            val frameArea = (processed.cols() * processed.rows()).toDouble()
            var bestArea = 0.0
            var best: Array<Point>? = null
            for (contour in contours) {
                val curve = MatOfPoint2f(*contour.toArray())
                val approx = MatOfPoint2f()
                try {
                    val peri = Imgproc.arcLength(curve, true)
                    if (peri < 4 * kMaxSide / 20) continue  // too small to be a page edge
                    // Two tolerances: page edges are often slightly rounded or noisy.
                    for (eps in doubleArrayOf(0.02, 0.05)) {
                        Imgproc.approxPolyDP(curve, approx, eps * peri, true)
                        val pts = approx.toArray()
                        if (pts.size != 4) continue
                        if (!Imgproc.isContourConvex(MatOfPoint(*pts))) continue
                        val area = Math.abs(Imgproc.contourArea(MatOfPoint2f(*pts)))
                        if (area < minAreaFraction * frameArea) continue
                        if (area > bestArea) {
                            bestArea = area
                            best = pts
                        }
                        break
                    }
                } finally {
                    curve.release()
                    approx.release()
                }
            }

            val ordered = best ?: return null
            val p = orderCorners(ordered).map {
                android.graphics.PointF(
                    (it.x / scale).toFloat(), (it.y / scale).toFloat()
                )
            }
            return Quadrilateral(p[0], p[1], p[2], p[3])
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting document quadrilateral", e)
            return null
        } finally {
            for (c in contours) c.release()
            hierarchy.release()
            edges.release()
            processed.release()
            mat.release()
        }
    }

    /**
     * Вырезает квад и разворачивает его в прямоугольник.
     *
     * Размер результата — из длин сторон квада (как у сканера), а не размер исходного кадра:
     * прежняя версия растягивала страницу обратно на весь кадр, то есть не обрезала вовсе.
     */
    fun correctPerspective(bitmap: Bitmap, quadrilateral: Quadrilateral?): Bitmap? {
        if (quadrilateral == null || !quadrilateral.isValid) return null

        val srcMat = Mat()
        val dstMat = Mat()
        val srcPoints = MatOfPoint2f()
        val dstPoints = MatOfPoint2f()
        val transform = Mat()
        try {
            Utils.bitmapToMat(bitmap, srcMat)

            val tl = quadrilateral.topLeft
            val tr = quadrilateral.topRight
            val br = quadrilateral.bottomRight
            val bl = quadrilateral.bottomLeft
            val width = maxOf(dist(tl, tr), dist(bl, br))
            val height = maxOf(dist(tl, bl), dist(tr, br))
            if (width < 16f || height < 16f) return null

            srcPoints.fromArray(
                Point(tl.x.toDouble(), tl.y.toDouble()),
                Point(tr.x.toDouble(), tr.y.toDouble()),
                Point(br.x.toDouble(), br.y.toDouble()),
                Point(bl.x.toDouble(), bl.y.toDouble())
            )
            dstPoints.fromArray(
                Point(0.0, 0.0),
                Point(width.toDouble() - 1, 0.0),
                Point(width.toDouble() - 1, height.toDouble() - 1),
                Point(0.0, height.toDouble() - 1)
            )
            Imgproc.getPerspectiveTransform(srcPoints, dstPoints).copyTo(transform)
            Imgproc.warpPerspective(srcMat, dstMat, transform, Size(width.toDouble(), height.toDouble()))
            if (dstMat.empty()) return null

            return createBitmap(dstMat.cols(), dstMat.rows()).also { Utils.matToBitmap(dstMat, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error correcting perspective", e)
            return null
        } finally {
            transform.release()
            dstPoints.release()
            srcPoints.release()
            dstMat.release()
            srcMat.release()
        }
    }

    private fun dist(a: android.graphics.PointF, b: android.graphics.PointF): Float =
        kotlin.math.hypot(a.x - b.x, a.y - b.y)

    /** Угловой порядок по часовой стрелке от верхнего левого угла — устойчив к повороту кадра. */
    private fun orderCorners(points: Array<Point>): List<Point> {
        val cx = points.sumOf { it.x } / points.size
        val cy = points.sumOf { it.y } / points.size
        val sorted = points.sortedBy { Math.atan2(it.y - cy, it.x - cx) }
        // atan2 order starts at the left-middle; rotate so the top-left-most corner comes first
        val start = sorted.indices.minByOrNull { sorted[it].x + sorted[it].y } ?: 0
        return List(sorted.size) { sorted[(start + it) % sorted.size] }
    }

    fun detectQuadrilaterals(bitmap: Bitmap): List<List<org.opencv.core.Point>> {
        val quadrilaterals = mutableListOf<List<org.opencv.core.Point>>()
        try {
            val mat = Mat()
            Utils.bitmapToMat(bitmap, mat)
            
            val processedMat = Mat()
            Imgproc.cvtColor(mat, processedMat, Imgproc.COLOR_RGBA2GRAY)
            
            processedMat.convertTo(processedMat, -1, 1.5, 30.0) 
            Imgproc.GaussianBlur(processedMat, processedMat, Size(5.0, 5.0), 0.0)
            
            val edges = Mat()
            Imgproc.Canny(processedMat, edges, 50.0, 150.0)
            
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            
            for (contour in contours) {
                val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.01 * peri, true)
                
                if (approx.toArray().size == 4) {
                    val pointsArray = approx.toArray()
                    val pointList = pointsArray.map { it as org.opencv.core.Point }
                    quadrilaterals.add(pointList)
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

    fun findDocumentCorners(bitmap: Bitmap): List<org.opencv.core.Point> {
        val quadrilateral = detectDocumentQuadrilateral(bitmap)
        return if (quadrilateral != null) {
            quadrilateral.points.map { Point(it.x.toDouble(), it.y.toDouble()) }
        } else {
            emptyList()
        }
    }

    fun getDocumentBoundary(bitmap: Bitmap): Rect {
        val quadrilateral = detectDocumentQuadrilateral(bitmap)
        return if (quadrilateral != null && quadrilateral.isValid) {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = Float.MIN_VALUE
            var maxY = Float.MIN_VALUE
            
            for (point in quadrilateral.points) {
                minX = minOf(minX, point.x)
                minY = minOf(minY, point.y)
                maxX = maxOf(maxX, point.x)
                maxY = maxOf(maxY, point.y)
            }
            
            Rect(minX.toInt(), minY.toInt(), maxX.toInt(), maxY.toInt())
        } else {
            Rect(0, 0, bitmap.width, bitmap.height)
        }
    }
}
