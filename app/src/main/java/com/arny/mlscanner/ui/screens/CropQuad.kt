package com.arny.mlscanner.ui.screens

import android.graphics.PointF
import com.arny.mlscanner.domain.models.Quadrilateral
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Четыре независимых угла обрезки в пикселях bitmap — как в CamScanner.
 * Порядок углов: TL, TR, BR, BL. В отличие от [CropRect] углы перемещаются
 * независимо, поэтому контур можно положить точно на края листа и выпрямить
 * перспективу, а не вырезать прямоугольник из наклонного снимка.
 */
data class CropQuad(
    val topLeft: PointF,
    val topRight: PointF,
    val bottomRight: PointF,
    val bottomLeft: PointF
) {
    val points: List<PointF> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun withCorner(index: Int, point: PointF): CropQuad = when (index) {
        0 -> copy(topLeft = point)
        1 -> copy(topRight = point)
        2 -> copy(bottomRight = point)
        else -> copy(bottomLeft = point)
    }

    fun corner(index: Int): PointF = when (index) {
        0 -> topLeft
        1 -> topRight
        2 -> bottomRight
        else -> bottomLeft
    }

    /** Площадь по формуле шнуровки. */
    val area: Float
        get() {
            val p = points
            var sum = 0f
            for (i in p.indices) {
                val a = p[i]
                val b = p[(i + 1) % p.size]
                sum += a.x * b.y - b.x * a.y
            }
            return abs(sum) / 2f
        }

    /** Не вырожден: площадь заметна и выпуклый (иначе гомография выворачивает кадр). */
    fun isValid(frameArea: Float): Boolean =
        area > max(100f, frameArea * 0.01f) && isConvex()

    private fun isConvex(): Boolean {
        val p = points
        var sign = 0
        for (i in p.indices) {
            val a = p[i]
            val b = p[(i + 1) % 4]
            val c = p[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-3f) continue
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return sign != 0
    }

    fun toQuadrilateral(): Quadrilateral =
        Quadrilateral(topLeft, topRight, bottomRight, bottomLeft)

    /**
     * Параллельный сдвиг стороны на ПЕРПЕНДИКУЛЯРНУЮ составляющую вектора (как в CamScanner):
     * концы стороны скользят по прямым соседних сторон, поэтому форма контура сохраняется.
     * Движение вдоль стороны игнорируется. Если полный сдвиг выводит углы за кадр или
     * вырождает контур, сдвиг уменьшается; если и это невозможно — null (сторона стоит).
     */
    fun moveEdge(edge: Int, delta: PointF, frameW: Float, frameH: Float, minEdge: Float): CropQuad? {
        val p = points
        val i = ((edge % 4) + 4) % 4
        val a = p[i]
        val b = p[(i + 1) % 4]
        val ex = b.x - a.x
        val ey = b.y - a.y
        val edgeLen = sqrt(ex * ex + ey * ey)
        if (edgeLen < 1e-3f) return null
        val nx = ey / edgeLen
        val ny = -ex / edgeLen
        val d0 = delta.x * nx + delta.y * ny
        if (abs(d0) < 1e-3f) return this

        fun inFrame(pt: PointF) =
            pt.x >= -0.5f && pt.x <= frameW + 0.5f && pt.y >= -0.5f && pt.y <= frameH + 0.5f

        fun intersect(p0: PointF, dir: PointF, p1: PointF, seg: PointF): PointF? {
            val det = dir.x * seg.y - dir.y * seg.x
            if (abs(det) < 1e-3f) return null // почти параллельно — сдвиг невозможен
            val t = ((p1.x - p0.x) * seg.y - (p1.y - p0.y) * seg.x) / det
            return PointF(p0.x + t * dir.x, p0.y + t * dir.y)
        }

        fun build(d: Float): CropQuad? {
            // Новая сторона — прямая (a+d·n, b+d·n) с направлением ex,ey.
            val shiftedA = PointF(a.x + nx * d, a.y + ny * d)
            val shiftedB = PointF(b.x + nx * d, b.y + ny * d)
            val prev = p[(i + 3) % 4]
            val next2 = p[(i + 2) % 4]
            // Концы новой стороны — пересечения с соседними сторонами.
            val newA = intersect(shiftedA, PointF(ex, ey), prev, PointF(a.x - prev.x, a.y - prev.y))
                ?: return null
            val newB = intersect(shiftedB, PointF(ex, ey), b, PointF(next2.x - b.x, next2.y - b.y))
                ?: return null
            if (!inFrame(newA) || !inFrame(newB)) return null
            val moved = p.mapIndexed { idx, pt ->
                when (idx) {
                    i -> newA
                    (i + 1) % 4 -> newB
                    else -> pt
                }
            }
            val q = CropQuad(moved[0], moved[1], moved[2], moved[3])
            return q.takeIf { it.isValidSides(frameW * frameH, minEdge) }
        }

        var d = d0
        build(d)?.let { return it }
        // Сдвиг за кадр/в вырождение — уменьшаем его, а не обрезаем координаты:
        // сторона обязана остаться параллельной.
        repeat(12) {
            d /= 2f
            build(d)?.let { return it }
        }
        return null
    }

    /** Перемещение угла в target (клампится в кадр). null — если контур вырождается/вогнутый. */
    fun moveCorner(index: Int, target: PointF, frameW: Float, frameH: Float, minEdge: Float): CropQuad? {
        val i = ((index % 4) + 4) % 4
        val moved = withCorner(
            i,
            PointF(target.x.coerceIn(0f, frameW), target.y.coerceIn(0f, frameH))
        )
        return moved.takeIf { it.isValidSides(frameW * frameH, minEdge) }
    }

    /** Выпуклый, невырожденный и все стороны не короче minEdge. */
    private fun isValidSides(frameArea: Float, minEdge: Float): Boolean =
        isValid(frameArea) &&
            distance(topLeft, topRight) >= minEdge &&
            distance(topRight, bottomRight) >= minEdge &&
            distance(bottomRight, bottomLeft) >= minEdge &&
            distance(bottomLeft, topLeft) >= minEdge

    /** Все углы лежат в кадре [width]×[height] (с допуском) — контур относится к этому кадру. */
    fun fitsIn(width: Int, height: Int, tolerance: Float = 2f): Boolean =
        points.all { p ->
            p.x >= -tolerance && p.y >= -tolerance &&
                p.x <= width + tolerance && p.y <= height + tolerance
        }

    /** Заполнен ли кадр целиком — тогда обрезка не нужна (ни warp, ни потерь). */
    fun coversFrame(width: Int, height: Int, tolerance: Float = 2f): Boolean {
        val w = width.toFloat()
        val h = height.toFloat()
        return points.all { p ->
            (p.x <= tolerance || p.x >= w - tolerance) && (p.y <= tolerance || p.y >= h - tolerance)
        }
    }

    companion object {
        fun fullFrame(width: Int, height: Int): CropQuad {
            val w = width.toFloat()
            val h = height.toFloat()
            return CropQuad(
                PointF(0f, 0f),
                PointF(w, 0f),
                PointF(w, h),
                PointF(0f, h)
            )
        }

        fun fromQuadrilateral(q: Quadrilateral): CropQuad =
            CropQuad(q.topLeft, q.topRight, q.bottomRight, q.bottomLeft)

        /** Длина стороны — размеры результата после выпрямления. */
        fun distance(a: PointF, b: PointF): Float {
            val dx = a.x - b.x
            val dy = a.y - b.y
            return sqrt(dx * dx + dy * dy)
        }
    }
}
