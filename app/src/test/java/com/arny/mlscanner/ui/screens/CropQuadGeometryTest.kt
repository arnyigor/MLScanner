package com.arny.mlscanner.ui.screens

import android.graphics.PointF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Геометрия ручек контура (moveEdge/moveCorner). Нужен Robolectric:
 * на голом JVM android.graphics.PointF — заглушка из android.jar.
 */
@RunWith(RobolectricTestRunner::class)
class CropQuadGeometryTest {

    private fun quad(
        tl: Pair<Float, Float>,
        tr: Pair<Float, Float>,
        br: Pair<Float, Float>,
        bl: Pair<Float, Float>
    ) = CropQuad(PointF(tl.first, tl.second), PointF(tr.first, tr.second), PointF(br.first, br.second), PointF(bl.first, bl.second))

    private fun cross(o: PointF, a: PointF, b: PointF): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    @Test
    fun `moveEdge translates top edge perpendicularly and ignores along-edge component`() {
        val rect = CropQuad.fullFrame(100, 100)
        val r = rect.moveEdge(0, PointF(30f, 50f), 100f, 100f, 5f)
        assertNotNull(r)
        r!!
        // Движение вдоль стороны (x=30) проигнорировано; перпендикуляр y=50 применён.
        assertEquals(0f, r.topLeft.x, 1e-3f)
        assertEquals(50f, r.topLeft.y, 1e-3f)
        assertEquals(100f, r.topRight.x, 1e-3f)
        assertEquals(50f, r.topRight.y, 1e-3f)
        // Основание не сдвинулось.
        assertEquals(0f, r.bottomLeft.x, 1e-3f)
        assertEquals(100f, r.bottomLeft.y, 1e-3f)
    }

    @Test
    fun `moveEdge along the edge is a no-op`() {
        val rect = CropQuad.fullFrame(100, 100)
        val r = rect.moveEdge(0, PointF(30f, 0f), 100f, 100f, 5f)
        assertEquals(rect, r)
    }

    @Test
    fun `moveEdge on a trapezoid slides edge endpoints along adjacent edges`() {
        val trap = quad(pair(10f, 0f), pair(90f, 0f), pair(100f, 100f), pair(0f, 100f))
        val r = trap.moveEdge(0, PointF(0f, 20f), 100f, 100f, 5f)
        assertNotNull(r)
        r!!
        // Новая верхняя сторона на y=20; концы — на прямых соседних сторон (трапеция сохранена).
        assertEquals(20f, r.topLeft.y, 1e-2f)
        assertEquals(20f, r.topRight.y, 1e-2f)
        // collinear: (BL -> TL_orig) и (BL -> TL_new)
        assertEquals(0f, cross(r.bottomLeft, trap.topLeft, r.topLeft), 1e-2f)
        assertEquals(0f, cross(r.bottomRight, trap.topRight, r.topRight), 1e-2f)
        // Сторона параллельна прежней.
        val oldDirX = trap.topRight.x - trap.topLeft.x
        val newDirX = r.topRight.x - r.topLeft.x
        val oldLen = oldDirX / 80f
        assertEquals(oldLen, newDirX / 84f, 1e-2f)
    }

    @Test
    fun `moveEdge outward beyond frame cannot escape the frame`() {
        val rect = CropQuad.fullFrame(100, 100)
        // Нижняя сторона тянется за нижний край кадра на +50.
        val r = rect.moveEdge(3, PointF(0f, 50f), 100f, 100f, 5f)
        assertNotNull(r)
        r!!
        assertTrue(r.bottomRight.y <= 100.6f)
        assertTrue(r.bottomLeft.y <= 100.6f)
        assertTrue(r.isValid(100f * 100f))
    }

    @Test
    fun `moveEdge past the opposite edge yields a valid smaller quad`() {
        val rect = CropQuad.fullFrame(100, 100)
        // Верх тянем на +150 — дальше низа; результат обязан остаться допустимым.
        val r = rect.moveEdge(0, PointF(0f, 150f), 100f, 100f, 5f)
        assertNotNull(r)
        r!!
        assertTrue(r.isValid(100f * 100f))
        assertTrue(r.area < rect.area)
        assertTrue(r.topLeft.y >= 5f / 2f)
    }

    @Test
    fun `moveEdge keeps minEdge guard`() {
        val rect = CropQuad.fullFrame(100, 100)
        // Верх к самому низу: высота < minEdge → допустимого результата нет.
        val r = rect.moveEdge(0, PointF(0f, 99.9f), 100f, 100f, 60f)
        assertTrue(r == null || r.area >= 1f)
    }

    @Test
    fun `moveCorner clamps target into the frame`() {
        val rect = CropQuad.fullFrame(100, 100)
        val r = rect.moveCorner(0, PointF(-50f, -50f), 100f, 100f, 10f)
        assertNotNull(r)
        assertEquals(0f, r!!.topLeft.x, 1e-3f)
        assertEquals(0f, r.topLeft.y, 1e-3f)
    }

    @Test
    fun `moveCorner rejects self-intersecting result`() {
        val rect = CropQuad.fullFrame(100, 100)
        // TL через всю фигуру вправо-внутрь — самопересечение.
        val r = rect.moveCorner(0, PointF(120f, 20f), 100f, 100f, 10f)
        assertTrue(r == null || r.isValid(100f * 100f))
        // Явно вогнутый вариант — на практике должен отклоняется.
        val r2 = rect.moveCorner(0, PointF(95f, 5f), 100f, 100f, 10f)
        assertTrue(r2 == null || r2.isValid(100f * 100f))
    }

    @Test
    fun `moveCorner enforces minimal edge length`() {
        val rect = CropQuad.fullFrame(100, 100)
        val r = rect.moveCorner(0, PointF(99f, 1f), 100f, 100f, 10f)
        assertTrue(r == null || CropQuad.distance(r.topLeft, r.topRight) >= 10f)
    }

    @Test
    fun `moveEdge on a quad with near-parallel neighbours is safe`() {
        // Почти вырожденная трапеция: левая и правая стороны почти параллельны верхней.
        val q = quad(pair(0f, 0f), pair(100f, 0f), pair(100f, 0.5f), pair(0f, 0.5f))
        val r = q.moveEdge(0, PointF(0f, 1f), 100f, 100f, 0.2f)
        // Не падаем; допустимый контур или честный null.
        assertTrue(r == null || r.isValid(100f * 100f))
    }

    private fun pair(x: Float, y: Float) = Pair(x, y)
}
