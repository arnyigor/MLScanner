package com.arny.mlscanner.data.image.raster

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Фильтры обработки документа, как в CamScanner: Оригинал / Улучшить цвет / Оттенки серого /
 * Чёрно-белый / Доска и др. Все фильтры чистые функции — не меняют вход.
 *
 * «Улучшить» и «Ч-б» основаны на DocumentEnhance / DocumentBlackWhite из makeacopy (Apache-2.0):
 * блоковое выбеливание бумаги + чернение чернил; «Ч-б» добавляет порог Sauvola.
 */
enum class EnhanceFilter(val displayName: String) {
    ORIGINAL("Оригинал"),
    ENHANCE("Улучшить цвет"),
    GRAYSCALE("Оттенки серого"),
    BLACK_WHITE("Чёрно-белый"),
    LIGHTEN("Светлее"),
    DESHADOW("Без теней"),
    BLACKBOARD("Доска");
}

object DocumentFilters {

    /** Применяет [filter] к [src] и возвращает новый Bitmap; вход не меняется. */
    fun apply(src: Bitmap, filter: EnhanceFilter, preserveColor: Boolean = true): Bitmap {
        if (filter == EnhanceFilter.ORIGINAL) return src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
        val w = src.width
        val h = src.height
        if (w <= 0 || h <= 0) return src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)

        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        when (filter) {
            EnhanceFilter.LIGHTEN -> lighten(pixels)
            EnhanceFilter.ENHANCE -> {
                DocumentEnhance.apply(pixels, w, h)
                if (!preserveColor) toGray(pixels)
            }
            EnhanceFilter.DESHADOW -> DocumentEnhance.flatten(pixels, w, h)
            EnhanceFilter.BLACK_WHITE -> DocumentBlackWhite.apply(pixels, w, h)
            EnhanceFilter.GRAYSCALE -> toGray(pixels)
            EnhanceFilter.BLACKBOARD -> {
                // Доска: инвертированные оттенки серого — светлый текст на тёмном фоне.
                toGray(pixels)
                for (i in pixels.indices) {
                    val v = 255 - (pixels[i] and 0xFF)
                    pixels[i] = -0x1000000 or (v shl 16) or (v shl 8) or v
                }
            }
            EnhanceFilter.ORIGINAL -> Unit
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    /** Миниатюра для ленты фильтров: тот же фильтр на уменьшенной копии. */
    fun applyThumbnail(src: Bitmap, filter: EnhanceFilter, maxSide: Int = 240): Bitmap {
        val scale = max(1f, max(src.width, src.height).toFloat() / maxSide)
        val tw = (src.width / scale).toInt().coerceAtLeast(1)
        val th = (src.height / scale).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, tw, th, true)
        val out = apply(small, filter)
        if (out !== small) small.recycle()
        return out
    }

    /** Гамма-кривая: ярче середины, край не клиппится. */
    private fun lighten(pixels: IntArray) {
        // Гамма 1/1.35, применённая через LUT.
        val lut = IntArray(256) { v ->
            val g = (255.0 * (v / 255.0).pow(1.0 / 1.35)).roundToInt()
            min(255, g)
        }
        val alphaMask = 0xFF shl 24
        for (i in pixels.indices) {
            val p = pixels[i]
            pixels[i] = (p and alphaMask) or
                (lut[p shr 16 and 0xFF] shl 16) or
                (lut[p shr 8 and 0xFF] shl 8) or
                lut[p and 0xFF]
        }
    }

    private fun toGray(pixels: IntArray) {
        for (i in pixels.indices) {
            val v = DocumentEnhance.luma(pixels[i])
            pixels[i] = -0x1000000 or (v shl 16) or (v shl 8) or v
        }
    }
}
