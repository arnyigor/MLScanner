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

    fun detectDocumentQuadrilateral(bitmap: Bitmap): Quadrilateral? {
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
            
            var largestContour: MatOfPoint? = null
            var maxArea = 0.0
            
            for (contour in contours) {
                val peri = Imgproc.arcLength(MatOfPoint2f(*contour.toArray()), true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(MatOfPoint2f(*contour.toArray()), approx, 0.01 * peri, true)
                
                if (approx.toArray().size == 4) {
                    val area = Imgproc.contourArea(contour)
                    if (area > maxArea) {
                        maxArea = area
                        largestContour = contour
                    }
                }
            }
            
            if (largestContour != null) {
                val points = largestContour.toArray()
                val sortedPoints = sortPoints(points)
                return Quadrilateral(
                    topLeft = android.graphics.PointF(sortedPoints[0].x.toFloat(), sortedPoints[0].y.toFloat()),
                    topRight = android.graphics.PointF(sortedPoints[1].x.toFloat(), sortedPoints[1].y.toFloat()),
                    bottomRight = android.graphics.PointF(sortedPoints[2].x.toFloat(), sortedPoints[2].y.toFloat()),
                    bottomLeft = android.graphics.PointF(sortedPoints[3].x.toFloat(), sortedPoints[3].y.toFloat())
                )
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

    fun correctPerspective(bitmap: Bitmap, quadrilateral: Quadrilateral?): Bitmap? {
        if (quadrilateral == null) return bitmap
        
        try {
            val srcMat = Mat()
            Utils.bitmapToMat(bitmap, srcMat)
            
            val w: Double = bitmap.width.toDouble()
            val h: Double = bitmap.height.toDouble()
            
            val dstPoints = MatOfPoint2f(
                Point(0.0, 0.0),
                Point(w, 0.0),
                Point(w, h),
                Point(0.0, h)
            )
            
            val mappedPoints = quadrilateral.points.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray()
            val srcPoints = MatOfPoint2f(*mappedPoints)
            
            val transformMatrix = Imgproc.getPerspectiveTransform(srcPoints, dstPoints)
            
            val dstMat = Mat()
            Imgproc.warpPerspective(srcMat, dstMat, transformMatrix, Size(w, h))
            
            val resultBitmap = createBitmap(dstMat.cols(), dstMat.rows())
            Utils.matToBitmap(dstMat, resultBitmap)
            
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

    private fun sortPoints(points: Array<Point>): Array<Point> {
        val sorted = points.sortedBy { it.x + it.y } 
        val p1 = sorted[0] 
        val p4 = sorted[3] 
        
        val remaining = sorted.subList(1, 3).sortedBy { it.x }
        val p2 = remaining[0] 
        val p3 = remaining[1] 
        
        return arrayOf(p1, p2, p4, p3)
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
