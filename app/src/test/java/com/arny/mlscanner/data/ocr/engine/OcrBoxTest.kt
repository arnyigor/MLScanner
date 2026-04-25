package com.arny.mlscanner.data.ocr.engine

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit тесты для OcrBox data class.
 */
class OcrBoxTest {

    @Test
    fun `test OcrBox creation with valid data`() {
        val box = OcrBox(
            text = "Test text",
            score = 0.95f,
            left = 10f,
            top = 20f,
            right = 100f,
            bottom = 50f
        )

        assertEquals("Test text", box.text)
        assertEquals(0.95f, box.score, 0.001f)
        assertEquals(10f, box.left, 0.001f)
        assertEquals(20f, box.top, 0.001f)
        assertEquals(100f, box.right, 0.001f)
        assertEquals(50f, box.bottom, 0.001f)
    }

    @Test
    fun `test OcrBox with empty text`() {
        val box = OcrBox(
            text = "",
            score = 0.5f,
            left = 0f,
            top = 0f,
            right = 10f,
            bottom = 10f
        )

        assertTrue(box.text.isEmpty())
    }

    @Test
    fun `test OcrBox with zero confidence`() {
        val box = OcrBox(
            text = "Low confidence",
            score = 0f,
            left = 0f,
            top = 0f,
            right = 10f,
            bottom = 10f
        )

        assertEquals(0f, box.score, 0.001f)
    }

    @Test
    fun `test OcrBox with maximum confidence`() {
        val box = OcrBox(
            text = "High confidence",
            score = 1f,
            left = 0f,
            top = 0f,
            right = 10f,
            bottom = 10f
        )

        assertEquals(1f, box.score, 0.001f)
    }

    @Test
    fun `test OcrBox equality`() {
        val box1 = OcrBox("text", 0.9f, 10f, 20f, 100f, 50f)
        val box2 = OcrBox("text", 0.9f, 10f, 20f, 100f, 50f)
        val box3 = OcrBox("different", 0.9f, 10f, 20f, 100f, 50f)

        assertEquals(box1, box2)
        assertNotEquals(box1, box3)
    }

    @Test
    fun `test OcrBox copy`() {
        val original = OcrBox("text", 0.9f, 10f, 20f, 100f, 50f)
        val copy = original.copy(text = "modified")

        assertEquals("modified", copy.text)
        assertEquals(original.score, copy.score, 0.001f)
        assertEquals(original.left, copy.left, 0.001f)
    }

    @Test
    fun `test OcrBox with negative coordinates`() {
        val box = OcrBox(
            text = "Negative coords",
            score = 0.8f,
            left = -10f,
            top = -20f,
            right = 100f,
            bottom = 50f
        )

        assertEquals(-10f, box.left, 0.001f)
        assertEquals(-20f, box.top, 0.001f)
    }

    @Test
    fun `test OcrBox with large coordinates`() {
        val box = OcrBox(
            text = "Large image",
            score = 0.85f,
            left = 0f,
            top = 0f,
            right = 4096f,
            bottom = 2160f
        )

        assertEquals(4096f, box.right, 0.001f)
        assertEquals(2160f, box.bottom, 0.001f)
    }

    @Test
    fun `test OcrBox toString contains all fields`() {
        val box = OcrBox("test", 0.9f, 10f, 20f, 100f, 50f)
        val string = box.toString()

        assertTrue(string.contains("test"))
        assertTrue(string.contains("0.9"))
        assertTrue(string.contains("10"))
        assertTrue(string.contains("20"))
        assertTrue(string.contains("100"))
        assertTrue(string.contains("50"))
    }
}
