package com.arny.mlscanner.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arny.mlscanner.data.ocr.postprocessing.PatternRecognizer
import com.arny.mlscanner.data.ocr.postprocessing.TextFormatter
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.OcrEngineType
import com.arny.mlscanner.domain.models.OcrLanguage
import com.arny.mlscanner.domain.models.ScanSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrRepositoryImplV2IntegrationTest {

    @Test
    fun printedHomeworkImageRecognizesUrlWithV2Pipeline() {
        assumeFalse(
            "Tesseract disabled on ARM-translation emulator",
            isArmTranslationEmulator()
        )

        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = OcrRepositoryImplV2(context, ImagePreprocessor())
            val bitmap = loadAssetBitmap(context, "ocr_samples/test_image.jpg")

            try {
                val init = repository.initialize()
                assertTrue("At least one OCR engine must initialize: $init", init.values.any { it })

                val result = repository.recognize(bitmap, tesseractSettings())
                val actual = result.fullText

                assertTrue(
                    """
                    V2 OCR returned too little text for test_image.jpg.
                    words=${result.wordCount}
                    confidence=${result.averageConfidence}
                    text:
                    $actual
                    """.trimIndent(),
                    result.wordCount >= 25 && actual.length >= 120
                )

                assertContainsLoose(actual, "yandex")
                assertContainsLoose(actual, "video")

                val urls = TextFormatter.format(actual, TextFormatter.FormatMode.RAW)
                    .patterns
                    .filter { it.type == PatternRecognizer.PatternType.URL }
                    .let(TextFormatter::createClickableElements)

                assertTrue(
                    """
                    V2 OCR text did not produce a clickable URL.
                    text:
                    $actual
                    """.trimIndent(),
                    urls.isNotEmpty()
                )

                assertEquals(
                    "https://yandex.ru/video/preview/16042109160516050498",
                    urls.first().value
                )
            } finally {
                bitmap.recycle()
                repository.release()
            }
        }
    }

    private fun tesseractSettings(): ScanSettings {
        return ScanSettings(
            contrastLevel = 1.0f,
            brightnessLevel = 0.0f,
            sharpenLevel = 0.0f,
            denoiseEnabled = false,
            binarizationEnabled = false,
            autoRotateEnabled = true,
            handwrittenMode = false,
            language = OcrLanguage.RUSSIAN,
            engineType = OcrEngineType.TESSERACT,
            confidenceThreshold = 0.0f,
            useMultiPass = false
        )
    }

    private fun assertContainsLoose(text: String, expected: String) {
        assertTrue(
            """
            OCR text does not contain '$expected'.
            text:
            $text
            """.trimIndent(),
            expected.lowercase() in text.lowercase()
        )
    }

    private fun loadAssetBitmap(context: Context, assetPath: String): Bitmap {
        return context.assets.open(assetPath).use { input ->
            BitmapFactory.decodeStream(input)
        } ?: error("Cannot decode asset: $assetPath")
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
}
