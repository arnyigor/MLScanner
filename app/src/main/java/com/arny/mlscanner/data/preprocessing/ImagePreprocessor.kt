package com.arny.mlscanner.data.preprocessing

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.arny.mlscanner.domain.models.ScanSettings
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc

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

    fun preprocessImage(bitmap: Bitmap, settings: ScanSettings): Bitmap {
        // 1️⃣ Document detection & perspective correction (heavy op)
        val processedBitmap = if (settings.detectDocument) {
            detectAndCorrectDocument(bitmap)
        } else {
            bitmap
        }

        // 2️⃣ Convert to Mat – will be released in finally‑blocks below.
        val mat = Mat()
        try {
            Utils.bitmapToMat(processedBitmap, mat)

            // 3️⃣ Grayscale conversion (required by most ops)
            val grayMat = Mat()
            try {
                Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)

                // 4️⃣ Optional denoising
                if (settings.denoiseEnabled) {
                    val denoisedMat = Mat()
                    try {
                        // Bilateral filter – tune d to keep speed.
                        Imgproc.bilateralFilter(grayMat, denoisedMat, 5, 75.0, 75.0)
                        grayMat.release()          // free old buffer
                        denoisedMat.copyTo(grayMat) // copy result back
                    } finally {
                        denoisedMat.release()
                    }
                }

                // 5️⃣ Contrast & brightness – safe handling of nulls.
                val contrast = settings.contrastLevel ?: 1.0f   // 1.0 → no change
                val brightness = settings.brightnessLevel ?: 0f
                if (contrast != 1.0f || brightness != 0f) {
                    grayMat.convertTo(grayMat, -1, contrast.toDouble(), brightness.toDouble())
                }

                // 6️⃣ Optional sharpening.
                val sharpen = settings.sharpenLevel ?: 0f
                if (sharpen > 0f) {
                    applySharpen(grayMat, sharpen)
                }

                // 7️⃣ Adaptive binarisation – optional.
                if (settings.binarizationEnabled) {
                    val blockSize = 31          // odd, reasonable for typical DPI
                    Imgproc.adaptiveThreshold(
                        grayMat,
                        grayMat,            // inplace
                        255.0,
                        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY,
                        blockSize,
                        10.0                // C constant – mitigates noise
                    )
                }

                // 8️⃣ Deskew (auto‑rotate) – optional.
                if (settings.autoRotateEnabled) {
                    deskewImage(grayMat)
                }

                // 9️⃣ Final bitmap – keep ARGB_8888 for ML Kit
                val resultConfig = Bitmap.Config.ARGB_8888
                val resultBitmap = createBitmap(grayMat.cols(), grayMat.rows(), resultConfig)
                Utils.matToBitmap(grayMat, resultBitmap)

                return resultBitmap
            } finally {
                grayMat.release()
            }
        } finally {
            mat.release()
        }
    }

    /* --------------------------- helpers -------------------------------- */

    private fun detectAndCorrectDocument(bitmap: Bitmap): Bitmap {
        val quadrilateral = documentDetector.detectDocumentQuadrilateral(bitmap)
        return documentDetector.correctPerspective(bitmap, quadrilateral) ?: bitmap
    }

    /** Convolution kernel for sharpening.  Value >0 increases edge strength. */
    private fun applySharpen(mat: Mat, sharpenLevel: Float) {
        val kernel = Mat(3, 3, CvType.CV_32F)
        try {
            val data = floatArrayOf(
                0f, -1f, 0f,
                -1f, 5f + sharpenLevel, -1f,
                0f, -1f, 0f
            )
            kernel.put(0, 0, data)
            Imgproc.filter2D(mat, mat, -1, kernel)
        } finally {
            kernel.release()
        }
    }

    /** Rotate image to remove small skew angles (≈±45°). */
    private fun deskewImage(mat: Mat) {
        val edges = Mat()
        try {
            Imgproc.Canny(mat, edges, 50.0, 150.0)

            val lines = Mat()
            try {
                Imgproc.HoughLines(edges, lines, 1.0, Math.PI / 180, 100)
                if (lines.rows() > 0) {
                    var angleSum = 0.0
                    val count = lines.rows().coerceAtMost(10)

                    for (i in 0 until count) {
                        val line = lines.get(i, 0)   // [rho, theta]
                        angleSum += line[1]
                    }

                    val avgTheta = angleSum / count
                    val angleDeg = Math.toDegrees(avgTheta) - 90.0

                    if (Math.abs(angleDeg) > 0.5 && Math.abs(angleDeg) < 45) {
                        val center = Point(mat.cols() / 2.0, mat.rows() / 2.0)
                        val rotMat = Imgproc.getRotationMatrix2D(center, angleDeg, 1.0)
                        try {
                            Imgproc.warpAffine(mat, mat, rotMat, mat.size())
                        } finally {
                            rotMat.release()
                        }
                    }
                }
            } finally {
                lines.release()
            }
        } finally {
            edges.release()
        }
    }

    /* --------------------------- test helpers -------------------------------- */

    fun preprocessForOcr(bitmap: Bitmap): Bitmap = preprocessImage(
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

    fun denoise(bitmap: Bitmap): Bitmap = preprocessImage(
        bitmap, ScanSettings(denoiseEnabled = true)
    )

    fun adjustBrightness(bitmap: Bitmap, brightness: Float): Bitmap = preprocessImage(
        bitmap, ScanSettings(brightnessLevel = brightness)
    )

    fun enhanceContrast(bitmap: Bitmap): Bitmap = preprocessImage(
        bitmap, ScanSettings(contrastLevel = 1.2f)
    )

    fun sharpen(bitmap: Bitmap): Bitmap = preprocessImage(
        bitmap, ScanSettings(sharpenLevel = 1.0f)
    )

    fun binarize(bitmap: Bitmap): Bitmap = preprocessImage(
        bitmap, ScanSettings(binarizationEnabled = true)
    )

    fun deskew(bitmap: Bitmap): Bitmap = preprocessImage(
        bitmap, ScanSettings(autoRotateEnabled = true)
    )
}
