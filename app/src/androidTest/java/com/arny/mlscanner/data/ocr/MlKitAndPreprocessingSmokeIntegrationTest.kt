package com.arny.mlscanner.data.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arny.mlscanner.data.ocr.engine.MLKitEngine
import com.arny.mlscanner.data.preprocessing.DocumentDetector
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.ScanSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MlKitAndPreprocessingSmokeIntegrationTest {

    @Test
    fun mlKitRecognizesSyntheticEnglishText() = runBlocking {
        val engine = MLKitEngine()
        assertTrue("ML Kit should initialize", engine.initialize())
        val armTranslatedX86Runtime = isArmTranslatedX86Runtime()
        if (armTranslatedX86Runtime) {
            engine.release()
        }
        assumeFalse(
            "ML Kit recognition crashes inside ndk_translation on the API 30 x86_64 ARM-translation emulator",
            armTranslatedX86Runtime
        )

        val bitmap = createTextBitmap("Hello ML Kit\nInvoice 12345")

        try {
            val result = engine.recognize(bitmap, handwrittenMode = false)
            val normalized = result.fullText.lowercase()

            assertFalse("ML Kit result should not be empty", result.isEmpty)
            assertTrue("Expected text, got: ${result.fullText}", normalized.contains("hello"))
            assertTrue("Expected digits, got: ${result.fullText}", normalized.contains("12345"))
            assertEquals("ML Kit", result.engineName)
        } finally {
            bitmap.recycle()
            engine.release()
        }
    }

    @Test
    fun imagePreprocessorInvertsDarkBackgroundForTesseract() {
        assertTrue("OpenCV should initialize", ImagePreprocessor.ensureOpenCvInitialized())

        val source = createBitmap(240, 120).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.BLACK)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 36f
            }
            canvas.drawText("TEST 123", 18f, 70f, paint)
        }

        val result = ImagePreprocessor().prepareForTesseract(source, ScanSettings.DEFAULT)

        try {
            assertEquals(source.width, result.width)
            assertEquals(source.height, result.height)
            assertTrue(
                "Dark background should be inverted for OCR",
                Color.red(result.getPixel(4, 4)) > 180
            )
        } finally {
            source.recycle()
            if (result !== source) result.recycle()
        }
    }

    @Test
    fun documentDetectorFindsSyntheticPageCorners() {
        val bitmap = createBitmap(420, 320).also { image ->
            val canvas = Canvas(image)
            canvas.drawColor(Color.rgb(24, 24, 24))
            val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                style = Paint.Style.FILL
            }
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            val page = RectF(58f, 42f, 360f, 278f)
            canvas.drawRect(page, fill)
            canvas.drawRect(page, stroke)
        }

        try {
            val quadrilateral = DocumentDetector().detectDocumentQuadrilateral(bitmap)

            assertNotNull("Document quadrilateral should be detected", quadrilateral)
            assertTrue("Document quadrilateral should be valid", quadrilateral!!.isValid)
        } finally {
            bitmap.recycle()
        }
    }

    private fun createTextBitmap(text: String): Bitmap {
        return createBitmap(900, 260).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 54f
            }

            var y = 85f
            text.lines().forEach { line ->
                canvas.drawText(line, 36f, y, paint)
                y += 72f
            }
        }
    }

    private fun isArmTranslatedX86Runtime(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeLibDir = context.applicationInfo.nativeLibraryDir.orEmpty()
        return Build.SUPPORTED_ABIS.any { it == "x86_64" || it == "x86" } &&
                (nativeLibDir.contains("arm64") || nativeLibDir.contains("arm"))
    }
}
