package com.arny.mlscanner.data.preprocessing

import android.util.Log

/**
 * Тонкая JNI-обёртка над libml_scanner_jni.so.
 *
 * Деворп разделён на два вызова намеренно:
 *  - [dewarpField] считает поле правки и возвращает его (null — страница уже ровная),
 *    поэтому поле можно залогировать и решить, нужен ли remap;
 *  - [dewarpApply] применяет поле, полученное первым вызовом.
 *
 * Формат поля (FloatArray): [outW, outH, t0..t{w-1}, b0..b{w-1}, hc, residual0..residual{w-1}].
 * t/b — границы содержимого, hc — медианная высота, residual — уточняющий сдвиг.
 *
 * Изображения передаются как 8-битный grayscale (ByteArray длины width * height), без копий
 * через Bitmap: конвертацию делает вызывающий код.
 */
object NativeScan {

    private const val TAG = "NativeScan"

    val isAvailable: Boolean = try {
        System.loadLibrary("ml_scanner_jni")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.e(TAG, "libml_scanner_jni not loaded", e)
        false
    }

    /** Например: "ml_scanner_jni abi=arm64-v8a cpp=201703". */
    external fun nativeBuildInfo(): String

    private external fun nativeDewarpField(gray: ByteArray, width: Int, height: Int): FloatArray

    private external fun nativeBestRotation(gray: ByteArray, width: Int, height: Int): Int

    /**
     * Поворот (0/90/270), после которого строки текста горизонтальны.
     *
     * 0 — если лист и так лежит правильно или решение неубедительно: 180° этим признаком не
     * отличаются, а ошибочный поворот хуже отсутствия поворота.
     */
    fun bestRotationDegrees(gray: ByteArray, width: Int, height: Int): Int {
        if (!isAvailable || width <= 0 || height <= 0 ||
            gray.size.toLong() < width.toLong() * height
        ) return 0
        return try {
            nativeBestRotation(gray, width, height)
        } catch (e: Throwable) {
            Log.e(TAG, "nativeBestRotation failed", e)
            0
        }
    }

    private external fun nativeDewarpApply(
        gray: ByteArray,
        width: Int,
        height: Int,
        field: FloatArray,
    ): ByteArray

    fun buildInfo(): String = if (isAvailable) {
        try {
            nativeBuildInfo()
        } catch (e: Throwable) {
            Log.e(TAG, "nativeBuildInfo failed", e)
            "unavailable"
        }
    } else {
        "unavailable"
    }

    /** Поле правки или null, если страница уже ровная (или библиотека недоступна). */
    fun dewarpField(gray: ByteArray, width: Int, height: Int): FloatArray? {
        if (!isAvailable || width <= 0 || height <= 0 || gray.size < width * height) return null
        return try {
            nativeDewarpField(gray, width, height).takeIf { it.size >= 2 }
        } catch (e: Throwable) {
            Log.e(TAG, "nativeDewarpField failed", e)
            null
        }
    }

    /** Исправленная страница (outH * width байт) или null при ошибке. */
    fun dewarpApply(gray: ByteArray, width: Int, height: Int, field: FloatArray): ByteArray? {
        if (!isAvailable || width <= 0 || height <= 0 ||
            gray.size.toLong() < width.toLong() * height ||
            field.size.toLong() != 3L + 3L * width
        ) return null
        return try {
            nativeDewarpApply(gray, width, height, field).takeIf { it.isNotEmpty() }
        } catch (e: Throwable) {
            Log.e(TAG, "nativeDewarpApply failed", e)
            null
        }
    }
}
