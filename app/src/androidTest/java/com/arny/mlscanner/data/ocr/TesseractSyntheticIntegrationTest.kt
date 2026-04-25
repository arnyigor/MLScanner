package com.arny.mlscanner.data.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arny.mlscanner.data.ocr.engine.TesseractEngine
import com.arny.mlscanner.data.ocr.engine.TesseractProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke-тест для Tesseract на синтетических изображениях.
 * Проверяет базовую работоспособность без зависимости от качества реальных фото.
 */
@RunWith(AndroidJUnit4::class)
class TesseractSyntheticIntegrationTest {

    @Test
    fun recognizesSyntheticRussianText() {
        assumeFalse(
            "Tesseract disabled on ARM-translation emulator",
            isArmTranslationEmulator()
        )

        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val engine = TesseractEngine(context)

            assertTrue("Tesseract should initialize", engine.initialize())

        val bitmap = createTextBitmap(
            """
            Привет мир
            Проверка распознавания
            Русский текст 123
            """.trimIndent()
        )

        try {
            val result = engine.recognizeMultiPass(
                bitmap,
                TesseractProfile.RUSSIAN_PROFILES
            )

            val normalized = result.text.lowercase()

            assertTrue(
                "Expected Russian text, got: ${result.text}",
                normalized.contains("привет") || normalized.contains("мир")
            )
            assertTrue(
                "Expected digits, got: ${result.text}",
                normalized.contains("123")
            )
            } finally {
                bitmap.recycle()
                engine.release()
            }
        }
    }

    @Test
    fun recognizesSyntheticEnglishText() {
        assumeFalse(
            "Tesseract disabled on ARM-translation emulator",
            isArmTranslationEmulator()
        )

        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val engine = TesseractEngine(context)

            assertTrue("Tesseract should initialize", engine.initialize())

        val bitmap = createTextBitmap(
            """
            Hello World
            Testing OCR
            English text 456
            """.trimIndent()
        )

        try {
            val result = engine.recognizeMultiPass(
                bitmap,
                TesseractProfile.ENGLISH_PROFILES
            )

            val normalized = result.text.lowercase()

            assertTrue(
                "Expected English text, got: ${result.text}",
                normalized.contains("hello") || normalized.contains("world")
            )
            assertTrue(
                "Expected digits, got: ${result.text}",
                normalized.contains("456")
            )
            } finally {
                bitmap.recycle()
                engine.release()
            }
        }
    }

    @Test
    fun recognizesSyntheticReceiptNumbers() {
        assumeFalse(
            "Tesseract disabled on ARM-translation emulator",
            isArmTranslationEmulator()
        )

        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val engine = TesseractEngine(context)

            assertTrue("Tesseract should initialize", engine.initialize())

        val bitmap = createTextBitmap(
            """
            RECEIPT
            TOTAL: 123.45
            CASH: 150.00
            CHANGE: 26.55
            """.trimIndent()
        )

        try {
            val result = engine.recognizeMultiPass(
                bitmap,
                TesseractProfile.ENGLISH_PROFILES
            )

            val normalized = result.text.lowercase()

            assertTrue(
                "Expected receipt text, got: ${result.text}",
                normalized.contains("receipt") || normalized.contains("total")
            )
            assertTrue(
                "Expected numbers, got: ${result.text}",
                result.text.contains("123") || result.text.contains("150")
            )
            } finally {
                bitmap.recycle()
                engine.release()
            }
        }
    }

    private fun isArmTranslationEmulator(): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return false
        val isX86 = abi.contains("x86")
        val hasArmLibs = Build.SUPPORTED_ABIS.any { it.contains("arm") }
        val isEmulator = Build.FINGERPRINT.contains("generic") ||
                         Build.FINGERPRINT.contains("emulator") ||
                         Build.MODEL.contains("Emulator") ||
                         Build.MODEL.contains("Android SDK")
        return isX86 && hasArmLibs && isEmulator
    }

    private fun createTextBitmap(text: String): Bitmap {
        return createBitmap(900, 260).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            val paint = Paint().apply {
                color = Color.BLACK
                textSize = 42f
                isAntiAlias = true
            }

            var y = 60f
            text.lines().forEach { line ->
                canvas.drawText(line, 30f, y, paint)
                y += 60f
            }
        }
    }
}
