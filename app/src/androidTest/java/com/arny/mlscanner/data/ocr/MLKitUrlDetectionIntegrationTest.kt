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
import org.junit.Assert.*
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Интеграционный тест для проверки URL распознавания в ML Kit.
 */
@RunWith(AndroidJUnit4::class)
class MLKitUrlDetectionIntegrationTest {

    @Test
    fun mlKitRecognizesUrlFromTestImage() {
        assumeFalse(
            "ML Kit recognition crashes inside ndk_translation on the API 30 x86_64 ARM-translation emulator",
            isArmTranslatedX86Runtime()
        )

        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = OcrRepositoryImplV2(context, ImagePreprocessor())
            val bitmap = loadAssetBitmap(context, "ocr_samples/test_image.jpg")

            try {
                val init = repository.initialize()
                assertTrue("At least one OCR engine must initialize: $init", init.values.any { it })

                // Тестируем ML Kit
                val result = repository.recognize(bitmap, mlKitSettings())
                val actual = result.fullText

                println("=== ML Kit Result ===")
                println("Text: $actual")
                println("Word count: ${result.wordCount}")
                println("Confidence: ${result.averageConfidence}")
                println("Engine: ${result.engineName}")

                // Проверяем что текст получен
                assertTrue(
                    "ML Kit should return text, got: ${actual.take(100)}",
                    actual.isNotBlank()
                )

                // Проверяем что "yandex" присутствует
                assertTrue(
                    "Text should contain 'yandex' (case insensitive)",
                    actual.lowercase().contains("yandex")
                )

                // Проверяем URL паттерны
                val urls = TextFormatter.format(actual, TextFormatter.FormatMode.RAW)
                    .patterns
                    .filter { it.type == PatternRecognizer.PatternType.URL }

                println("=== Detected URLs ===")
                urls.forEach { println("URL: ${it.value}") }

                // Проверяем что URL найден
                assertTrue(
                    """
                    ML Kit text should produce a clickable URL.
                    text:
                    $actual
                    """.trimIndent(),
                    urls.isNotEmpty()
                )

                // Проверяем конкретный URL
                val expectedUrl = "https://yandex.ru/video/preview/16042109160516050498"
                val foundUrl = urls.firstOrNull()?.value

                println("Expected URL: $expectedUrl")
                println("Found URL: $foundUrl")

                assertEquals(
                    "URL should match expected",
                    expectedUrl,
                    foundUrl
                )

            } finally {
                bitmap.recycle()
                repository.release()
            }
        }
    }

    private fun mlKitSettings(): ScanSettings {
        return ScanSettings(
            contrastLevel = 1.0f,
            brightnessLevel = 0.0f,
            sharpenLevel = 0.0f,
            denoiseEnabled = false,
            binarizationEnabled = false,
            autoRotateEnabled = true,
            handwrittenMode = false,
            language = OcrLanguage.RUSSIAN,
            engineType = OcrEngineType.ML_KIT,
            confidenceThreshold = 0.0f,
            useMultiPass = false
        )
    }

    private fun loadAssetBitmap(context: Context, assetPath: String): Bitmap {
        return context.assets.open(assetPath).use { input ->
            BitmapFactory.decodeStream(input)
        } ?: error("Cannot decode asset: $assetPath")
    }

    private fun isArmTranslatedX86Runtime(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeLibDir = context.applicationInfo.nativeLibraryDir.orEmpty()
        return Build.SUPPORTED_ABIS.any { it == "x86_64" || it == "x86" } &&
                (nativeLibDir.contains("arm64") || nativeLibDir.contains("arm"))
    }
}