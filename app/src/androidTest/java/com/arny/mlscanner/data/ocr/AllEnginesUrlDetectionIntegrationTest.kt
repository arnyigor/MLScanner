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
 * Интеграционный тест для проверки URL распознавания во всех OCR движках.
 */
@RunWith(AndroidJUnit4::class)
class AllEnginesUrlDetectionIntegrationTest {

    @Test
    fun tesseractRecognizesUrlFromTestImage() {
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

                println("=== Tesseract Result ===")
                println("Text: $actual")
                println("Word count: ${result.wordCount}")
                println("Engine: ${result.engineName}")

                // Проверяем URL
                val urls = TextFormatter.format(actual, TextFormatter.FormatMode.RAW)
                    .patterns
                    .filter { it.type == PatternRecognizer.PatternType.URL }

                println("=== Tesseract URLs ===")
                urls.forEach { println("URL: ${it.value}") }

                assertTrue("Tesseract should detect URL", urls.isNotEmpty())
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

                val result = repository.recognize(bitmap, mlKitSettings())
                val actual = result.fullText

                println("=== ML Kit Result ===")
                println("Text: $actual")
                println("Word count: ${result.wordCount}")
                println("Engine: ${result.engineName}")

                // Проверяем URL
                val urls = TextFormatter.format(actual, TextFormatter.FormatMode.RAW)
                    .patterns
                    .filter { it.type == PatternRecognizer.PatternType.URL }

                println("=== ML Kit URLs ===")
                urls.forEach { println("URL: ${it.value}") }

                assertTrue("ML Kit should detect URL", urls.isNotEmpty())
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

    @Test
    fun huaweiMlKitRecognizesUrlFromTestImage() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = OcrRepositoryImplV2(context, ImagePreprocessor())
            val bitmap = loadAssetBitmap(context, "ocr_samples/test_image.jpg")

            try {
                val init = repository.initialize()
                val huaweiReady = init["huawei_mlkit"] ?: false

                if (!huaweiReady) {
                    println("=== Huawei ML Kit not available, skipping ===")
                    return@runBlocking
                }

                val result = repository.recognize(bitmap, huaweiSettings())
                val actual = result.fullText

                println("=== Huawei ML Kit Result ===")
                println("Text: $actual")
                println("Word count: ${result.wordCount}")
                println("Engine: ${result.engineName}")

                // Проверяем URL
                val urls = TextFormatter.format(actual, TextFormatter.FormatMode.RAW)
                    .patterns
                    .filter { it.type == PatternRecognizer.PatternType.URL }

                println("=== Huawei URLs ===")
                urls.forEach { println("URL: ${it.value}") }

                assertTrue("Huawei ML Kit should detect URL", urls.isNotEmpty())
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

    private fun tesseractSettings(): ScanSettings = ScanSettings(
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

    private fun mlKitSettings(): ScanSettings = ScanSettings(
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

    private fun huaweiSettings(): ScanSettings = ScanSettings(
        contrastLevel = 1.0f,
        brightnessLevel = 0.0f,
        sharpenLevel = 0.0f,
        denoiseEnabled = false,
        binarizationEnabled = false,
        autoRotateEnabled = true,
        handwrittenMode = false,
        language = OcrLanguage.RUSSIAN,
        engineType = OcrEngineType.HUAWEI_ML_KIT,
        confidenceThreshold = 0.0f,
        useMultiPass = false
    )

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

    private fun isArmTranslatedX86Runtime(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeLibDir = context.applicationInfo.nativeLibraryDir.orEmpty()
        return Build.SUPPORTED_ABIS.any { it == "x86_64" || it == "x86" } &&
                (nativeLibDir.contains("arm64") || nativeLibDir.contains("arm"))
    }
}