package com.arny.mlscanner.data.preprocessing

import android.graphics.Bitmap
import android.graphics.Color
import com.arny.mlscanner.domain.models.OcrEngineType
import com.arny.mlscanner.domain.models.ScanSettings
import kotlin.math.sqrt

/**
 * Selects conservative initial scan settings from image luminance statistics.
 *
 * The tuner is intentionally mild: destructive choices such as binarization stay
 * off by default because they often hurt ML Kit/Huawei and some photographed pages.
 */
class ScanSettingsAutoTuner {

    fun recommend(bitmap: Bitmap, base: ScanSettings = ScanSettings.DEFAULT): ScanSettings {
        if (bitmap.width <= 0 || bitmap.height <= 0 || bitmap.isRecycled) return base

        val stats = analyze(bitmap)
        val isLargePhoto = maxOf(bitmap.width, bitmap.height) >= LARGE_IMAGE_SIDE

        val tuned = when {
            stats.mean < DARK_MEAN -> base.copy(
                contrastLevel = 1.35f,
                brightnessLevel = 20f,
                sharpenLevel = 0.35f,
                denoiseEnabled = isLargePhoto
            )

            stats.stdDev < LOW_CONTRAST_STDDEV -> base.copy(
                contrastLevel = 1.55f,
                brightnessLevel = 0f,
                sharpenLevel = 0.45f,
                denoiseEnabled = true
            )

            stats.mean > BRIGHT_MEAN && stats.stdDev >= DOCUMENT_STDDEV -> base.copy(
                contrastLevel = 1.25f,
                brightnessLevel = 0f,
                sharpenLevel = 0.30f,
                denoiseEnabled = isLargePhoto
            )

            else -> base.copy(
                contrastLevel = 1.15f,
                brightnessLevel = 0f,
                sharpenLevel = 0.20f,
                denoiseEnabled = isLargePhoto && stats.stdDev < NOISY_STDDEV
            )
        }

        return tuned.copy(
            engineType = if (base.engineType == OcrEngineType.BARCODE) OcrEngineType.BARCODE else base.engineType,
            binarizationEnabled = false,
            autoRotateEnabled = true
        )
    }

    private fun analyze(bitmap: Bitmap): LuminanceStats {
        val stepX = (bitmap.width / SAMPLE_GRID).coerceAtLeast(1)
        val stepY = (bitmap.height / SAMPLE_GRID).coerceAtLeast(1)

        var count = 0
        var sum = 0.0
        var sumSquares = 0.0

        var y = stepY / 2
        while (y < bitmap.height) {
            var x = stepX / 2
            while (x < bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val lum = luminance(color)
                sum += lum
                sumSquares += lum * lum
                count++
                x += stepX
            }
            y += stepY
        }

        if (count == 0) return LuminanceStats(mean = 128.0, stdDev = 0.0)

        val mean = sum / count
        val variance = (sumSquares / count) - (mean * mean)
        return LuminanceStats(mean = mean, stdDev = sqrt(variance.coerceAtLeast(0.0)))
    }

    private fun luminance(color: Int): Double {
        return 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
    }

    private data class LuminanceStats(
        val mean: Double,
        val stdDev: Double
    )

    private companion object {
        private const val SAMPLE_GRID = 80
        private const val LARGE_IMAGE_SIDE = 1400
        private const val DARK_MEAN = 95.0
        private const val BRIGHT_MEAN = 145.0
        private const val LOW_CONTRAST_STDDEV = 30.0
        private const val DOCUMENT_STDDEV = 35.0
        private const val NOISY_STDDEV = 55.0
    }
}
