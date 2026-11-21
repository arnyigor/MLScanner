/*
 * Copyright 2026 Christian Kierdorf
 * Portions Copyright 2026 MLScanner port
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.arny.mlscanner.data.image.raster

import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Black and white for documents: [DocumentEnhance.flatten] evens out paper and shadows, then a
 * Sauvola threshold over a window of about a text line decides each pixel. Ported from
 * makeacopy's BlackWhite (Apache-2.0).
 */
object DocumentBlackWhite {
    private const val BAND = 64

    /** Sauvola's sensitivity: higher keeps less of faint strokes. */
    private const val K = 0.2

    /** Dynamic range of the standard deviation for 8-bit images. */
    private const val R = 128.0

    /** Window side as a share of the shorter image side, and its bounds in pixels. */
    private const val WINDOW_SHARE = 1.0 / 40.0

    private const val MIN_WINDOW = 15
    private const val MAX_WINDOW = 101

    /** Pixels at least this bright are paper whatever the neighbourhood says. */
    private const val PAPER = 230

    private const val WHITE = -0x1
    private const val BLACK = -0x1000000

    private val PARALLELISM = Runtime.getRuntime().availableProcessors()

    /** Turns [argb] into opaque black and white, in place. */
    fun apply(argb: IntArray, width: Int, height: Int) {
        if (width * height == 0) return
        DocumentEnhance.flatten(argb, width, height)
        threshold(argb, width, height)
    }

    /**
     * Sauvola's threshold over a square window (clipped at the borders), in place. The window sums
     * come from column sums that slide down the rows.
     */
    private fun threshold(argb: IntArray, width: Int, height: Int) {
        val n = width * height
        val lum = ByteArray(n)
        for (i in 0 until n) lum[i] = DocumentEnhance.luma(argb[i]).toByte()

        var window = (min(width, height) * WINDOW_SHARE).roundToInt()
        window = max(MIN_WINDOW, min(MAX_WINDOW, window)) or 1
        val half = window / 2
        val bands = max(1, min((height + BAND - 1) / BAND, 4 * PARALLELISM))
        val rowsPerBand = (height + bands - 1) / bands
        IntStream.range(0, bands).parallel().forEach { band ->
            val from = band * rowsPerBand
            val until = min(height, from + rowsPerBand)
            if (from < until) thresholdRows(argb, lum, width, height, half, from, until)
        }
    }

    private fun thresholdRows(
        argb: IntArray,
        lum: ByteArray,
        width: Int,
        height: Int,
        half: Int,
        from: Int,
        until: Int
    ) {
        val colSum = LongArray(width)
        val colSq = LongArray(width)
        val sum = LongArray(width + 1)
        val sumSq = LongArray(width + 1)
        var top = max(0, from - half)
        var bottom = top
        for (y in from until until) {
            val y0 = max(0, y - half)
            val y1 = min(height, y + half + 1)
            while (bottom < y1) addRow(lum, width, bottom++, colSum, colSq, 1)
            while (top < y0) addRow(lum, width, top++, colSum, colSq, -1)
            for (x in 0 until width) {
                sum[x + 1] = sum[x] + colSum[x]
                sumSq[x + 1] = sumSq[x] + colSq[x]
            }
            val rows = y1 - y0
            for (x in 0 until width) {
                val i = y * width + x
                val v = lum[i].toInt() and 0xFF
                if (v >= PAPER) {
                    argb[i] = WHITE
                    continue
                }
                val x0 = max(0, x - half)
                val x1 = min(width, x + half + 1)
                val count = (x1 - x0).toDouble() * rows
                val s = sum[x1] - sum[x0].toDouble()
                val sq = sumSq[x1] - sumSq[x0].toDouble()
                val mean = s / count
                val deviation = sqrt(max(0.0, sq / count - mean * mean))
                val threshold = mean * (1 + K * (deviation / R - 1))
                argb[i] = if (v > threshold) WHITE else BLACK
            }
        }
    }

    private fun addRow(lum: ByteArray, width: Int, y: Int, colSum: LongArray, colSq: LongArray, sign: Int) {
        val row = y * width
        for (x in 0 until width) {
            val v = lum[row + x].toInt() and 0xFF
            colSum[x] += sign.toLong() * v
            colSq[x] += (sign.toLong() * v * v)
        }
    }
}
