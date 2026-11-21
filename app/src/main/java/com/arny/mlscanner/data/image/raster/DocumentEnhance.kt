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

import java.util.Arrays
import java.util.stream.IntStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The "Enhanced" document cleanup on ARGB pixels: lifts the paper to white per block (which also
 * removes shadows and colour casts), stretches the darkest ink to black, and sharpens the luma by
 * unsharp masking. Ported from makeacopy's Enhance (Apache-2.0).
 */
object DocumentEnhance {
    private const val BLOCKS = 40
    private const val MIN_BLOCK = 16

    /** Share of the brightest pixels of a block that count as its paper. */
    private const val PAPER_SHARE = 0.25

    /** A block is paper when its paper luma reaches this share of the typical block. */
    private const val PAPER_MIN = 0.7

    /** A uniform (flat) block counts as paper in shadow down to this share. */
    private const val SHADOW_MIN = 0.3

    private const val FLAT = 6f

    /** Paper is lifted slightly above white so that it clips to 255. */
    private const val PAPER_LIFT = 1.05f

    private const val MAX_GAIN = 3.0
    private const val BLACK_SHARE = 0.005
    private const val MAX_BLACK = 64

    /** How much mid tones are darkened towards the ink. */
    private const val INK = 0.6

    private val KERNEL = intArrayOf(1, 6, 15, 20, 15, 6, 1)
    private const val KERNEL_SUM = 64
    private const val AMOUNT = 0.8
    private const val THRESHOLD = 3

    private const val BAND = 64

    fun luma(r: Int, g: Int, b: Int): Int = (77 * r + 150 * g + 29 * b) shr 8

    fun luma(p: Int): Int = luma(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF)

    fun apply(argb: IntArray, width: Int, height: Int) {
        check(argb, width, height)
        if (width * height == 0) return
        whitenPaper(argb, width, height)
        blackenInk(argb, width, height)
        sharpen(argb, width, height)
    }

    /** The paper and ink steps of [apply] without sharpening, as black and white starts. */
    fun flatten(argb: IntArray, width: Int, height: Int) {
        check(argb, width, height)
        if (width * height == 0) return
        whitenPaper(argb, width, height)
        blackenInk(argb, width, height)
    }

    private fun check(argb: IntArray, width: Int, height: Int) {
        require(width >= 0 && height >= 0 && argb.size >= width * height) {
            "${argb.size} pixels for $width x $height"
        }
    }

    private fun inBands(count: Int, rows: (Int, Int) -> Unit) {
        val bands = (count + BAND - 1) / BAND
        IntStream.range(0, bands).parallel().forEach { b ->
            rows(b * BAND, min(count, (b + 1) * BAND))
        }
    }

    private fun whitenPaper(argb: IntArray, width: Int, height: Int) {
        val size = max(MIN_BLOCK, (max(width, height) + BLOCKS - 1) / BLOCKS)
        val bw = (width + size - 1) / size
        val bh = (height + size - 1) / size
        val paper = Array(3) { FloatArray(bw * bh) }
        val lumas = FloatArray(bw * bh)
        val flat = BooleanArray(bw * bh)
        IntStream.range(0, bh).parallel().forEach { by ->
            val histogram = IntArray(256)
            for (bx in 0 until bw) {
                paperOfBlock(argb, width, height, size, bx, by, bw, histogram, paper, lumas, flat)
            }
        }
        val sorted = lumas.clone()
        Arrays.sort(sorted)
        val typical = sorted[sorted.size / 2]
        val valid = BooleanArray(bw * bh)
        for (i in valid.indices) {
            valid[i] = lumas[i] >= typical * PAPER_MIN || (flat[i] && lumas[i] >= typical * SHADOW_MIN)
        }
        fillFromNeighbours(paper, valid, bw, bh, typical)
        repeat(2) { for (c in paper) boxBlur(c, bw, bh) }

        val gains = Array(3) { FloatArray(bw * bh) }
        for (c in 0..2) {
            for (i in 0 until bw * bh) {
                val g = PAPER_LIFT * 255f / max(1f, paper[c][i])
                gains[c][i] = max(1f, min(MAX_GAIN.toFloat(), g))
            }
        }
        val left = IntArray(width)
        val right = IntArray(width)
        val towards = FloatArray(width)
        for (x in 0 until width) {
            val fx = clamp((x + 0.5f) / size - 0.5f, 0f, (bw - 1).toFloat())
            left[x] = fx.toInt()
            right[x] = min(bw - 1, left[x] + 1)
            towards[x] = fx - left[x]
        }
        inBands(height) { from, until ->
            val rowR = FloatArray(bw)
            val rowG = FloatArray(bw)
            val rowB = FloatArray(bw)
            for (y in from until until) {
                val fy = clamp((y + 0.5f) / size - 0.5f, 0f, (bh - 1).toFloat())
                val top = fy.toInt() * bw
                val bottom = min(bh - 1, fy.toInt() + 1) * bw
                val ty = fy - fy.toInt()
                gainRow(gains[0], top, bottom, ty, rowR)
                gainRow(gains[1], top, bottom, ty, rowG)
                gainRow(gains[2], top, bottom, ty, rowB)
                whitenRow(argb, y * width, width, left, right, towards, rowR, rowG, rowB)
            }
        }
    }

    private fun paperOfBlock(
        argb: IntArray,
        width: Int,
        height: Int,
        size: Int,
        bx: Int,
        by: Int,
        bw: Int,
        histogram: IntArray,
        paper: Array<FloatArray>,
        lumas: FloatArray,
        flat: BooleanArray
    ) {
        val x0 = bx * size
        val y0 = by * size
        val x1 = min(width, x0 + size)
        val y1 = min(height, y0 + size)
        Arrays.fill(histogram, 0)
        for (y in y0 until y1) {
            var i = y * width + x0
            for (x in x0 until x1) histogram[luma(argb[i++])]++
        }
        val count = (x1 - x0) * (y1 - y0)
        var median = 0
        var below = histogram[0]
        while (median < 255 && below < (count + 1) / 2) below += histogram[++median]
        var cut = 255
        var above = histogram[255]
        while (cut > 0 && above < count * PAPER_SHARE) above += histogram[--cut]
        var r = 0L
        var g = 0L
        var b = 0L
        var k = 0
        for (y in y0 until y1) {
            var i = y * width + x0
            for (x in x0 until x1) {
                val p = argb[i++]
                if (luma(p) < cut) continue
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
                k++
            }
        }
        val i = by * bw + bx
        paper[0][i] = r.toFloat() / k
        paper[1][i] = g.toFloat() / k
        paper[2][i] = b.toFloat() / k
        lumas[i] = luma(paper[0][i].roundToInt(), paper[1][i].roundToInt(), paper[2][i].roundToInt()).toFloat()
        flat[i] = lumas[i] - median <= FLAT
    }

    private fun gainRow(gains: FloatArray, top: Int, bottom: Int, ty: Float, out: FloatArray) {
        for (bx in out.indices) {
            out[bx] = gains[top + bx] + (gains[bottom + bx] - gains[top + bx]) * ty
        }
    }

    private fun whitenRow(
        argb: IntArray,
        start: Int,
        width: Int,
        left: IntArray,
        right: IntArray,
        towards: FloatArray,
        rowR: FloatArray,
        rowG: FloatArray,
        rowB: FloatArray
    ) {
        var i = start
        for (x in 0 until width) {
            val a = left[x]
            val b = right[x]
            val t = towards[x]
            val p = argb[i]
            val r = min(255, ((p shr 16 and 0xFF) * (rowR[a] + (rowR[b] - rowR[a]) * t) + 0.5f).toInt())
            val g = min(255, ((p shr 8 and 0xFF) * (rowG[a] + (rowG[b] - rowG[a]) * t) + 0.5f).toInt())
            val bl = min(255, ((p and 0xFF) * (rowB[a] + (rowB[b] - rowB[a]) * t) + 0.5f).toInt())
            argb[i] = (p and -0x1000000) or (r shl 16) or (g shl 8) or bl
            i++
        }
    }

    private fun fillFromNeighbours(paper: Array<FloatArray>, valid: BooleanArray, bw: Int, bh: Int, typical: Float) {
        var any = false
        for (v in valid) any = any or v
        if (!any) {
            for (c in paper) Arrays.fill(c, typical)
            return
        }
        while (!allTrue(valid)) {
            val next = valid.clone()
            for (i in valid.indices) {
                if (valid[i]) continue
                val x = i % bw
                val y = i / bw
                val sum = FloatArray(3)
                var k = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || nx >= bw || ny < 0 || ny >= bh || !valid[ny * bw + nx]) continue
                        for (c in 0..2) sum[c] += paper[c][ny * bw + nx]
                        k++
                    }
                }
                if (k == 0) continue
                for (c in 0..2) paper[c][i] = sum[c] / k
                next[i] = true
            }
            System.arraycopy(next, 0, valid, 0, valid.size)
        }
    }

    private fun allTrue(a: BooleanArray): Boolean {
        for (v in a) if (!v) return false
        return true
    }

    private fun boxBlur(m: FloatArray, w: Int, h: Int) {
        val src = m.clone()
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                var k = 0
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                        s += src[ny * w + nx]
                        k++
                    }
                }
                m[y * w + x] = s / k
            }
        }
    }

    private fun blackenInk(argb: IntArray, width: Int, height: Int) {
        val n = width * height
        val histogram = IntArray(256)
        inBands(height) { from, until ->
            val local = IntArray(256)
            for (y in from until until) {
                for (i in y * width until (y + 1) * width) local[luma(argb[i])]++
            }
            synchronized(histogram) {
                for (v in 0 until 256) histogram[v] += local[v]
            }
        }
        var black = 0
        var count = 0
        while (black < 255 && count + histogram[black] <= n * BLACK_SHARE) count += histogram[black++]
        val ink = min(black, MAX_BLACK)
        val map = IntArray(256)
        for (v in 0 until 256) {
            val t = max(0, v - ink) / (255.0 - ink)
            map[v] = clamp((255.0 * (t - INK * t * (1 - t) * (1 - t))).roundToInt(), 0, 255)
        }
        inBands(height) { from, until ->
            for (i in from * width until until * width) {
                val p = argb[i]
                argb[i] =
                    (p and -0x1000000) or
                    (map[p shr 16 and 0xFF] shl 16) or
                    (map[p shr 8 and 0xFF] shl 8) or
                    map[p and 0xFF]
            }
        }
    }

    private fun sharpen(argb: IntArray, width: Int, height: Int) {
        val n = width * height
        val lum = ByteArray(n)
        val tmp = ByteArray(n)
        inBands(height) { from, until ->
            for (y in from until until) {
                val row = y * width
                for (i in row until row + width) lum[i] = luma(argb[i]).toByte()
                for (x in 0 until width) tmp[row + x] = blur(lum, row, x, 1, width).toByte()
            }
        }
        inBands(height) { from, until ->
            for (y in from until until) {
                val row = y * width
                for (x in 0 until width) {
                    val i = row + x
                    val diff = (lum[i].toInt() and 0xFF) - blur(tmp, x, y, width, height)
                    if (diff < THRESHOLD && diff > -THRESHOLD) continue
                    val d = (AMOUNT * diff).roundToInt()
                    val p = argb[i]
                    val red = clamp((p shr 16 and 0xFF) + d, 0, 255)
                    val green = clamp((p shr 8 and 0xFF) + d, 0, 255)
                    val blue = clamp((p and 0xFF) + d, 0, 255)
                    argb[i] = (p and -0x1000000) or (red shl 16) or (green shl 8) or blue
                }
            }
        }
    }

    /** 7-tap binomial blur of [v] at position [at] along a line with [stride]. */
    private fun blur(v: ByteArray, start: Int, at: Int, stride: Int, count: Int): Int {
        val c = start + at * stride
        val s: Int = if (at >= 3 && at < count - 3) {
            (v[c - 3 * stride].toInt() and 0xFF) + (v[c + 3 * stride].toInt() and 0xFF) +
                6 * ((v[c - 2 * stride].toInt() and 0xFF) + (v[c + 2 * stride].toInt() and 0xFF)) +
                15 * ((v[c - stride].toInt() and 0xFF) + (v[c + stride].toInt() and 0xFF)) +
                20 * (v[c].toInt() and 0xFF)
        } else {
            var acc = 0
            for (k in -3..3) {
                acc += KERNEL[k + 3] * (v[start + clamp(at + k, 0, count - 1) * stride].toInt() and 0xFF)
            }
            acc
        }
        return (s + KERNEL_SUM / 2) / KERNEL_SUM
    }

    private fun clamp(v: Float, lo: Float, hi: Float): Float = max(lo, min(hi, v))

    private fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(hi, v))
}
