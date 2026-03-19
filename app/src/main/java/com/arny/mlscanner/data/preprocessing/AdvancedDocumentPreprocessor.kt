package com.arny.mlscanner.data.preprocessing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.util.Log
import kotlin.math.sqrt
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap

object AdvancedDocumentPreprocessor {
    private const val TAG = "DocPreprocessor"

    enum class DocumentType {
        GENERAL,
        WHITE_PAPER,
        RECEIPT,
        SIGN,
        SCREENSHOT
    }

    data class ProcessingOptions(
        val enhanceContrast: Boolean = true,
        val contrastFactor: Float = 1.5f,
        val sharpen: Boolean = true,
        val denoise: Boolean = false,
        val binarize: Boolean = false,
        val binarizeThreshold: Int = 128,
        val adaptiveBinarize: Boolean = false,
        val removeBackground: Boolean = false,
        val upscaleIfSmall: Boolean = true,
        val minDimension: Int = 800,
        val maxDimension: Int = 2048,
        val grayscale: Boolean = false
    )

    fun optionsForType(type: DocumentType): ProcessingOptions = when (type) {
        DocumentType.GENERAL -> ProcessingOptions(
            enhanceContrast = true,
            contrastFactor = 1.4f,
            sharpen = true
        )
        DocumentType.WHITE_PAPER -> ProcessingOptions(
            enhanceContrast = true,
            contrastFactor = 1.6f,
            sharpen = true,
            removeBackground = true
        )
        DocumentType.RECEIPT -> ProcessingOptions(
            enhanceContrast = true,
            contrastFactor = 1.8f,
            sharpen = true,
            adaptiveBinarize = true,
            upscaleIfSmall = true,
            minDimension = 1200
        )
        DocumentType.SIGN -> ProcessingOptions(
            enhanceContrast = true,
            contrastFactor = 1.3f,
            sharpen = true,
            denoise = true
        )
        DocumentType.SCREENSHOT -> ProcessingOptions(
            enhanceContrast = false,
            sharpen = false,
            upscaleIfSmall = true
        )
    }

    fun process(
        source: Bitmap,
        options: ProcessingOptions = ProcessingOptions()
    ): Bitmap {
        var bitmap = ensureArgb8888(source)
        val startTime = System.currentTimeMillis()

        if (options.upscaleIfSmall) {
            val old = bitmap
            bitmap = scaleToOptimal(bitmap, options.minDimension, options.maxDimension)
            if (old !== bitmap && old !== source) old.recycle()
        }

        if (options.grayscale) {
            val old = bitmap
            bitmap = toGrayscale(bitmap)
            if (old !== source) old.recycle()
        }

        if (options.removeBackground) {
            val old = bitmap
            bitmap = removeBackground(bitmap)
            if (old !== source) old.recycle()
        }

        if (options.enhanceContrast) {
            val old = bitmap
            bitmap = enhanceContrast(bitmap, options.contrastFactor)
            if (old !== source) old.recycle()
        }

        if (options.denoise) {
            val old = bitmap
            bitmap = denoise(bitmap)
            if (old !== source) old.recycle()
        }

        if (options.sharpen) {
            val old = bitmap
            bitmap = sharpen(bitmap)
            if (old !== source) old.recycle()
        }

        if (options.adaptiveBinarize) {
            val old = bitmap
            bitmap = adaptiveThreshold(bitmap)
            if (old !== source) old.recycle()
        } else if (options.binarize) {
            val old = bitmap
            bitmap = globalThreshold(bitmap, options.binarizeThreshold)
            if (old !== source) old.recycle()
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Processing done in ${elapsed}ms")

        return bitmap
    }

    private fun ensureArgb8888(bitmap: Bitmap): Bitmap {
        return if (bitmap.config != Bitmap.Config.ARGB_8888) {
            bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } else bitmap
    }

    private fun scaleToOptimal(source: Bitmap, minDim: Int, maxDim: Int): Bitmap {
        val w = source.width
        val h = source.height
        val currentMin = minOf(w, h)
        val currentMax = maxOf(w, h)

        val scale = when {
            currentMin < minDim -> minDim.toFloat() / currentMin
            currentMax > maxDim -> maxDim.toFloat() / currentMax
            else -> return source
        }

        return source.scale((w * scale).toInt(), (h * scale).toInt())
    }

    private fun toGrayscale(source: Bitmap): Bitmap {
        val result = createBitmap(source.width, source.height)
        val canvas = Canvas(result)
        val paint = Paint().apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix().apply { setSaturation(0f) }
            )
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private fun removeBackground(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h) { i ->
            val p = pixels[i]
            (0.299 * ((p shr 16) and 0xFF) +
                    0.587 * ((p shr 8) and 0xFF) +
                    0.114 * (p and 0xFF)).toInt()
        }

        val blurred = boxBlur(gray, w, h, radius = 30)

        val output = IntArray(w * h)
        for (i in pixels.indices) {
            val diff = (blurred[i] - gray[i]).coerceIn(-255, 255)
            val value = (255 + diff).coerceIn(0, 255)
            val v = (255 - value + gray[i]).coerceIn(0, 255)
            output[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        val result = createBitmap(w, h)
        result.setPixels(output, 0, w, 0, 0, w, h)
        return result
    }

    private fun enhanceContrast(source: Bitmap, factor: Float): Bitmap {
        val result = createBitmap(source.width, source.height)
        val canvas = Canvas(result)
        val t = (1f - factor) / 2f * 255f
        val cm = ColorMatrix(floatArrayOf(
            factor, 0f, 0f, 0f, t,
            0f, factor, 0f, 0f, t,
            0f, 0f, factor, 0f, t,
            0f, 0f, 0f, 1f, 0f
        ))
        canvas.drawBitmap(source, 0f, 0f, Paint().apply {
            colorFilter = ColorMatrixColorFilter(cm)
        })
        return result
    }

    private fun denoise(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val output = IntArray(w * h)

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val rValues = IntArray(9)
                val gValues = IntArray(9)
                val bValues = IntArray(9)
                var idx = 0

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val p = pixels[(y + dy) * w + (x + dx)]
                        rValues[idx] = (p shr 16) and 0xFF
                        gValues[idx] = (p shr 8) and 0xFF
                        bValues[idx] = p and 0xFF
                        idx++
                    }
                }

                rValues.sort()
                gValues.sort()
                bValues.sort()

                output[y * w + x] = (0xFF shl 24) or
                        (rValues[4] shl 16) or
                        (gValues[4] shl 8) or
                        bValues[4]
            }
        }

        for (x in 0 until w) {
            output[x] = pixels[x]
            output[(h - 1) * w + x] = pixels[(h - 1) * w + x]
        }
        for (y in 0 until h) {
            output[y * w] = pixels[y * w]
            output[y * w + w - 1] = pixels[y * w + w - 1]
        }

        val result = createBitmap(w, h)
        result.setPixels(output, 0, w, 0, 0, w, h)
        return result
    }

    private fun sharpen(source: Bitmap): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)
        val output = pixels.copyOf()

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                var r = 0
                var g = 0
                var b = 0
                val kernel = intArrayOf(0, -1, 0, -1, 5, -1, 0, -1, 0)
                var ki = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val p = pixels[(y + dy) * w + (x + dx)]
                        val kv = kernel[ki++]
                        r += ((p shr 16) and 0xFF) * kv
                        g += ((p shr 8) and 0xFF) * kv
                        b += (p and 0xFF) * kv
                    }
                }
                output[y * w + x] = (0xFF shl 24) or
                        (r.coerceIn(0, 255) shl 16) or
                        (g.coerceIn(0, 255) shl 8) or
                        b.coerceIn(0, 255)
            }
        }

        val result = createBitmap(w, h)
        result.setPixels(output, 0, w, 0, 0, w, h)
        return result
    }

    private fun globalThreshold(source: Bitmap, threshold: Int): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val p = pixels[i]
            val gray = (0.299 * ((p shr 16) and 0xFF) +
                    0.587 * ((p shr 8) and 0xFF) +
                    0.114 * (p and 0xFF)).toInt()
            val v = if (gray > threshold) 255 else 0
            pixels[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }

        val result = createBitmap(w, h)
        result.setPixels(pixels, 0, w, 0, 0, w, h)
        return result
    }

    private fun adaptiveThreshold(source: Bitmap, windowSize: Int = 25, k: Float = 0.08f): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h) { i ->
            val p = pixels[i]
            (0.299 * ((p shr 16) and 0xFF) +
                    0.587 * ((p shr 8) and 0xFF) +
                    0.114 * (p and 0xFF)).toInt()
        }

        val integral = LongArray(w * h)
        val integralSq = LongArray(w * h)

        for (y in 0 until h) {
            var rowSum = 0L
            var rowSumSq = 0L
            for (x in 0 until w) {
                val idx = y * w + x
                rowSum += gray[idx]
                rowSumSq += gray[idx].toLong() * gray[idx]

                integral[idx] = rowSum + if (y > 0) integral[(y - 1) * w + x] else 0
                integralSq[idx] = rowSumSq + if (y > 0) integralSq[(y - 1) * w + x] else 0
            }
        }

        val halfW = windowSize / 2
        val output = IntArray(w * h)

        for (y in 0 until h) {
            for (x in 0 until w) {
                val x1 = maxOf(0, x - halfW)
                val y1 = maxOf(0, y - halfW)
                val x2 = minOf(w - 1, x + halfW)
                val y2 = minOf(h - 1, y + halfW)

                val count = (x2 - x1 + 1) * (y2 - y1 + 1)

                var sum = integral[y2 * w + x2]
                var sumSq = integralSq[y2 * w + x2]
                if (x1 > 0) {
                    sum -= integral[y2 * w + (x1 - 1)]
                    sumSq -= integralSq[y2 * w + (x1 - 1)]
                }
                if (y1 > 0) {
                    sum -= integral[(y1 - 1) * w + x2]
                    sumSq -= integralSq[(y1 - 1) * w + x2]
                }
                if (x1 > 0 && y1 > 0) {
                    sum += integral[(y1 - 1) * w + (x1 - 1)]
                    sumSq += integralSq[(y1 - 1) * w + (x1 - 1)]
                }

                val mean = sum.toFloat() / count
                val variance = (sumSq.toFloat() / count) - (mean * mean)
                val stddev = sqrt(maxOf(0f, variance))

                val threshold = mean * (1f + k * (stddev / 128f - 1f))

                val v = if (gray[y * w + x] > threshold) 255 else 0
                output[y * w + x] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }

        val result = createBitmap(w, h)
        result.setPixels(output, 0, w, 0, 0, w, h)
        return result
    }

    private fun boxBlur(gray: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val output = IntArray(w * h)
        val temp = IntArray(w * h)

        for (y in 0 until h) {
            var sum = 0
            var count = 0
            for (x in 0 until minOf(radius, w)) {
                sum += gray[y * w + x]
                count++
            }
            for (x in 0 until w) {
                if (x + radius < w) {
                    sum += gray[y * w + x + radius]
                    count++
                }
                if (x - radius - 1 >= 0) {
                    sum -= gray[y * w + x - radius - 1]
                    count--
                }
                temp[y * w + x] = sum / count
            }
        }

        for (x in 0 until w) {
            var sum = 0
            var count = 0
            for (y in 0 until minOf(radius, h)) {
                sum += temp[y * w + x]
                count++
            }
            for (y in 0 until h) {
                if (y + radius < h) {
                    sum += temp[(y + radius) * w + x]
                    count++
                }
                if (y - radius - 1 >= 0) {
                    sum -= temp[(y - radius - 1) * w + x]
                    count--
                }
                output[y * w + x] = sum / count
            }
        }

        return output
    }
}
