package com.arny.mlscanner.data.preprocessing

import android.content.Context
import android.graphics.Bitmap
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

class ImagePreprocessor(private val context: Context) {

    companion object {
        private const val TAG = "ImagePreprocessor"
        private const val BRIGHTNESS_DARK_THRESHOLD = 100.0
        private const val BILATERAL_DIAMETER = 5
        private const val BILATERAL_SIGMA = 75.0
        private const val MIN_SKEW_ANGLE = 0.5
        private const val MAX_SKEW_ANGLE = 45.0
        private const val HOUGH_THRESHOLD = 100

        private var openCvInitialized = false

        fun ensureOpenCvInitialized(): Boolean {
            if (openCvInitialized) return true
            return try {
                org.opencv.android.OpenCVLoader.initLocal()
                Log.i(TAG, "OpenCV initialized successfully")
                openCvInitialized = true
                true
            } catch (e: Exception) {
                Log.e(TAG, "OpenCV init failed", e)
                openCvInitialized = false
                false
            }
        }
    }

    init {
        ensureOpenCvInitialized()
    }

    fun prepareBaseImage(bitmap: Bitmap, settings: ScanSettings): Bitmap {
        if (bitmap.width <= 0 || bitmap.height <= 0) return bitmap
        if (!openCvInitialized) return bitmap

        val source = if (bitmap.isMutable) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, true)

        var deskewed = source
        if (settings.autoRotateEnabled) {
            try {
                deskewed = deskew(source)
            } catch (e: Exception) {
                Log.w(TAG, "Deskew failed, using original", e)
            }
        }

        return applyFilters(deskewed, settings)
    }

    fun applyFiltersOnly(baseBitmap: Bitmap, settings: ScanSettings): Bitmap {
        if (!openCvInitialized) return baseBitmap
        return applyFilters(baseBitmap, settings)
    }

    private fun applyFilters(baseBitmap: Bitmap, settings: ScanSettings): Bitmap {
        val mat = Mat()
        val gray = Mat()

        try {
            Utils.bitmapToMat(baseBitmap, mat)

            if (mat.channels() == 1) mat.copyTo(gray)
            else Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGBA2GRAY)

            val needsInversion = isDarkBackground(gray)
            if (needsInversion) {
                Core.bitwise_not(gray, gray)
            }

            if (settings.denoiseEnabled) {
                val temp = Mat()
                try {
                    Imgproc.bilateralFilter(
                        gray, temp, BILATERAL_DIAMETER,
                        BILATERAL_SIGMA, BILATERAL_SIGMA
                    )
                    temp.copyTo(gray)
                } finally {
                    temp.release()
                }
            }

            val contrast = settings.contrastLevel
            val brightness = settings.brightnessLevel
            if (contrast != 1.0f || brightness != 0f) {
                gray.convertTo(gray, -1, contrast.toDouble(), brightness.toDouble())
            }

            if (settings.sharpenLevel > 0f) {
                applySharpen(gray, settings.sharpenLevel)
            }

            val forceBinarize = needsInversion
            if (settings.binarizationEnabled || forceBinarize) {
                Imgproc.threshold(
                    gray, gray, 0.0, 255.0,
                    Imgproc.THRESH_BINARY or Imgproc.THRESH_OTSU
                )
            }

            val result = Bitmap.createBitmap(
                gray.cols(), gray.rows(), Bitmap.Config.ARGB_8888
            )
            Utils.matToBitmap(gray, result)
            return result

        } catch (e: Exception) {
            Log.e(TAG, "Filter pipeline failed", e)
            return baseBitmap
        } finally {
            gray.release()
            mat.release()
        }
    }

    private fun isDarkBackground(grayMat: Mat): Boolean {
        return Core.mean(grayMat).`val`[0] < BRIGHTNESS_DARK_THRESHOLD
    }

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

            val result = createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
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
            Imgproc.HoughLines(edges, lines, 1.0, Math.PI / 180, HOUGH_THRESHOLD)

            if (lines.rows() == 0) return null

            val horizontalAngles = mutableListOf<Double>()
            val count = lines.rows().coerceAtMost(20)
            for (i in 0 until count) {
                val rhoTheta = lines.get(i, 0)
                val thetaDeg = Math.toDegrees(rhoTheta[1]) - 90.0
                if (abs(thetaDeg) < MAX_SKEW_ANGLE) {
                    horizontalAngles.add(thetaDeg)
                }
            }

            if (horizontalAngles.isEmpty()) return null

            val sorted = horizontalAngles.sorted()
            val median = sorted[sorted.size / 2]

            return if (abs(median) > MIN_SKEW_ANGLE) median else null
        } finally {
            edges.release()
            lines.release()
        }
    }

    private fun applySharpen(mat: Mat, level: Float) {
        val kernel = Mat(3, 3, CvType.CV_32F)
        try {
            val center = 5f + level
            kernel.put(
                0, 0, floatArrayOf(
                    0f, -1f, 0f,
                    -1f, center, -1f,
                    0f, -1f, 0f
                )
            )
            Imgproc.filter2D(mat, mat, -1, kernel)
        } finally {
            kernel.release()
        }
    }
}