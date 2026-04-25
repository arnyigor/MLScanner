package com.arny.mlscanner.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.OcrEngineType
import com.arny.mlscanner.domain.models.OcrLanguage
import com.arny.mlscanner.domain.models.ScanSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrRealSamplesIntegrationTest {

    @Test
    fun printedHomeworkImageRecognizesStableAnchorsInReadingOrder() {
        assumeFalse(
            "Tesseract disabled on ARM-translation emulator",
            isArmTranslationEmulator()
        )

        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = createInitializedRepository(context)
            val bitmap = loadAssetBitmap(context, "ocr_samples/test_image.jpg")

        try {
            val result = repository.recognize(bitmap, tesseractSettings(OcrLanguage.RUSSIAN))
            val actual = result.fullText

            assertTrue(
                """
                OCR returned too little text for test_image.jpg.
                words=${result.wordCount}
                confidence=${result.averageConfidence}
                text:
                $actual
                """.trimIndent(),
                result.wordCount >= 35 && actual.length >= 180
            )

            assertAnchorsInOrder(
                text = actual,
                anchors = listOf(
                    Anchor("words task", "поставить", "ударение"),
                    Anchor("green pen task", "зелёной", "зеленой"),
                    Anchor("math", "математика"),
                    Anchor("peterson", "петерсон"),
                    Anchor("counting", "двойками", "40"),
                    Anchor("azbuka", "азбука"),
                    Anchor("okr world", "окр", "мир"),
                    Anchor("url", "yandex", "video"),
                    Anchor("electricity", "электричество")
                )
            )

            assertExpectedWordHits(
                expected = HOMEWORK_TEXT,
                actual = actual,
                minHits = 18
            )
            } finally {
                bitmap.recycle()
                repository.release()
            }
        }
    }

    @Test
    fun handwrittenStoryImageIsProcessedWithoutCrashAndHasSomeExpectedWords() {
        assumeFalse(
            "Tesseract disabled on ARM-translation emulator",
            isArmTranslationEmulator()
        )

        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = createInitializedRepository(context)
            val bitmap = loadAssetBitmap(context, "ocr_samples/007.jpg")

            try {
                val result = repository.recognize(bitmap, tesseractSettings(OcrLanguage.RUSSIAN))
                val actual = result.fullText

                assertTrue(
                    """
                    OCR returned empty text for handwritten 007.jpg.
                    words=${result.wordCount}
                    confidence=${result.averageConfidence}
                    text:
                    $actual
                    """.trimIndent(),
                    result.wordCount >= 20 && actual.length >= 120
                )

                assertExpectedWordHits(
                    expected = HANDWRITTEN_STORY_TEXT,
                    actual = actual,
                    minHits = 2
                )
            } finally {
                bitmap.recycle()
                repository.release()
            }
        }
    }

    @Test
    fun driverLicenseImageRecognizesCoreIdentityFieldsWithTesseract() {
        // Skip на ARM-translation эмуляторах, где Tesseract отключён
        assumeFalse(
            "Tesseract disabled on ARM-translation emulator",
            isArmTranslationEmulator()
        )

        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val repository = createInitializedRepository(context)
            val original = loadAssetBitmap(context, "ocr_samples/003.jpg")
            val bitmap = original.scaledToMaxSide(900)

            try {
                val result = repository.recognize(bitmap, tesseractSettings(OcrLanguage.RUSSIAN_ENGLISH))
                val actual = result.fullText

                assertTrue(
                    """
                    OCR returned too little text for driver license 003.jpg.
                    words=${result.wordCount}
                    confidence=${result.averageConfidence}
                    text:
                    $actual
                    """.trimIndent(),
                    result.wordCount >= 10 && actual.length >= 60
                )

                assertExpectedWordHits(
                    expected = DRIVER_LICENSE_TEXT,
                    actual = actual,
                    minHits = 4
                )

                val nonBlankLines = actual.lines().filter { it.isNotBlank() }
                assertTrue(
                    """
                    OCR collapsed driver license fields into too few lines.
                    lines=${nonBlankLines.size}
                    text:
                    $actual
                    """.trimIndent(),
                    nonBlankLines.size >= 6
                )

                assertContainsAny(actual, "kulenko", "куленко")
                assertContainsAny(actual, "kristina", "кристина")
                assertContainsAny(actual, "02.09.1994", "02091994", "02 09 1994")
                assertContainsAny(actual, "414035")
            } finally {
                bitmap.recycle()
                if (original !== bitmap && !original.isRecycled) original.recycle()
                repository.release()
            }
        }
    }

    private suspend fun createInitializedRepository(context: Context): OcrRepositoryImpl {
        val repository = OcrRepositoryImpl(context, ImagePreprocessor())
        val init = repository.initialize()
        assertTrue("At least one OCR engine must initialize: $init", init.values.any { it })
        return repository
    }

    private fun tesseractSettings(language: OcrLanguage): ScanSettings {
        return ScanSettings(
            contrastLevel = 1.0f,
            brightnessLevel = 0.0f,
            sharpenLevel = 0.0f,
            denoiseEnabled = false,
            binarizationEnabled = false,
            autoRotateEnabled = true,
            handwrittenMode = false,
            language = language,
            engineType = OcrEngineType.TESSERACT,
            confidenceThreshold = 0.0f,
            useMultiPass = false
        )
    }

    private fun assertAnchorsInOrder(text: String, anchors: List<Anchor>) {
        val normalized = normalizeLoose(text)
        var fromIndex = 0

        anchors.forEach { anchor ->
            val found = anchor.variants
                .map { normalizeLoose(it) }
                .mapNotNull { variant ->
                    val index = normalized.indexOf(variant, fromIndex)
                    if (index >= 0) index to variant else null
                }
                .minByOrNull { it.first }

            assertTrue(
                """
                Missing anchor or wrong reading order: ${anchor.name}
                fromIndex=$fromIndex

                OCR:
                $text

                Normalized:
                $normalized
                """.trimIndent(),
                found != null
            )

            fromIndex = found!!.first + found.second.length
        }
    }

    private fun assertExpectedWordHits(expected: String, actual: String, minHits: Int) {
        val expectedWords = significantWords(expected)
        val actualWords = significantWords(actual)
        val hits = expectedWords.intersect(actualWords)

        assertTrue(
            """
            OCR matched too few expected words.
            hits=${hits.size}, min=$minHits
            matched=${hits.sorted()}

            OCR:
            $actual
            """.trimIndent(),
            hits.size >= minHits
        )
    }

    private fun assertContainsAny(text: String, vararg variants: String) {
        val normalized = normalizeLoose(text)
        assertTrue(
            """
            OCR does not contain any of: ${variants.toList()}

            OCR:
            $text
            """.trimIndent(),
            variants.any { normalizeLoose(it) in normalized }
        )
    }

    private fun significantWords(value: String): Set<String> {
        return normalizeLoose(value)
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { it.length >= 4 }
            .toSet()
    }

    private fun normalizeLoose(value: String): String {
        return value
            .lowercase()
            .replace('ё', 'е')
            .replace(Regex("""https?\s*:?\s*/?\s*/?"""), " ")
            .replace(Regex("""(?<=\d)\s+(?=\d)"""), "")
            .replace(Regex("""[«»"'`,.;:!?()\[\]{}№+\-—–/\\|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun loadAssetBitmap(context: Context, assetPath: String): Bitmap {
        return context.assets.open(assetPath).use { input ->
            BitmapFactory.decodeStream(input)
        } ?: error("Cannot decode asset: $assetPath")
    }

    private fun Bitmap.scaledToMaxSide(maxSide: Int): Bitmap {
        val currentMaxSide = maxOf(width, height)
        if (currentMaxSide <= maxSide) return this

        val scale = maxSide.toFloat() / currentMaxSide
        val newWidth = (width * scale).toInt().coerceAtLeast(1)
        val newHeight = (height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(this, newWidth, newHeight, true)
    }

    private data class Anchor(
        val name: String,
        val variants: List<String>
    ) {
        constructor(name: String, vararg variants: String) : this(name, variants.toList())
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

    private companion object {
        private val HOMEWORK_TEXT = """
            29 января

            Письмо: стр.41 «Тренажёр», прописать слова, поставить ударение, подчеркнуть зелёной ручкой опасные места, разделить слова для переноса.

            Математика: стр. 7 - Петерсон № 1(в кружочке)+ счет двойками до 40.

            Азбука: стр. 63. Выучить скороговорки

            Окр мир:
            https://yandex.ru/video/preview/160421091605
            16050498

            Откуда в дом приходит электричество
        """.trimIndent()

        private val HANDWRITTEN_STORY_TEXT = """
            Сильный ветер шумел в вершинах островов, и
            вместе с шумом деревьев доносилось беспокойное кря-
            канье озябших уток. Уже в течение двух часов плот
            несло по быстрине, и не видно было ни берегов, ни
            неба. Подняв воротник кожаной куртки, Аня сиде-
            ла на личиках и, сжимаясь от холода, смотрела
            в темноту, где давно исчезли огоньки города.
        """.trimIndent()

        private val DRIVER_LICENSE_TEXT = """
            RUS
            ВОДИТЕЛЬСКОЕ УДОСТОВЕРЕНИЕ

            1. КУЛЕНКО
            KULENKO

            2. КРИСТИНА СЕРГЕЕВНА
            KRISTINA SERGEEVNA

            3. 02.09.1994
            РОСТОВСКАЯ ОБЛ.
            ROSTOVSKAIA OBLAST'

            4a) 24.01.2018    4b) 24.01.2028

            4c) ГИБДД 6100
            GIBDD 6100

            5. 61 35 414035

            8. РОСТОВСКАЯ ОБЛ.
            ROSTOVSKAIA OBL.

            9. B B1 M
        """.trimIndent()
    }
}
