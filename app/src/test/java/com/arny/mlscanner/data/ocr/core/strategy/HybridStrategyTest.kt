package com.arny.mlscanner.data.ocr.core.strategy

import android.graphics.Bitmap
import com.arny.mlscanner.data.ocr.core.IOcrEngine
import com.arny.mlscanner.data.ocr.core.OcrAccuracyTier
import com.arny.mlscanner.data.ocr.core.OcrCandidate
import com.arny.mlscanner.data.ocr.core.OcrEngineCapabilities
import com.arny.mlscanner.data.ocr.core.OcrEngineRegistry
import com.arny.mlscanner.data.ocr.core.OcrEngineState
import com.arny.mlscanner.data.ocr.core.OcrRequest
import com.arny.mlscanner.data.ocr.core.OcrScript
import com.arny.mlscanner.data.ocr.core.OcrSpeedTier
import com.arny.mlscanner.data.ocr.core.OcrTaskType
import com.arny.mlscanner.data.ocr.core.PreparedOcrRequest
import com.arny.mlscanner.domain.models.OcrLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Умное распознавание: порядок Huawei → Tesseract и критерий остановки.
 * Robolectric нужен ради android.graphics.Bitmap в PreparedOcrRequest.
 */
@RunWith(RobolectricTestRunner::class)
class HybridStrategyTest {

    private class FakeEngine(
        override val id: String,
        private val text: String
    ) : IOcrEngine {

        var calls = 0

        override val name = id

        override val capabilities = OcrEngineCapabilities(
            engineId = id,
            displayName = id,
            isOffline = true,
            isExperimental = false,
            supportedLanguages = setOf("ru", "en"),
            supportedScripts = setOf(OcrScript.CYRILLIC, OcrScript.LATIN),
            supportedTasks = setOf(OcrTaskType.GENERAL_TEXT),
            speedTier = OcrSpeedTier.MEDIUM,
            accuracyTier = OcrAccuracyTier.GOOD
        )

        override suspend fun initialize(): OcrEngineState = OcrEngineState.Ready(capabilities)

        override fun isReady(): Boolean = true

        override suspend fun recognize(request: PreparedOcrRequest): OcrCandidate {
            calls++
            return OcrCandidate(
                engineId = id,
                engineName = id,
                rawText = text,
                blocks = emptyList(),
                confidence = 0.9f,
                processingTimeMs = 1L,
                processedText = text
            )
        }

        override fun release() = Unit
    }

    private fun preparedRequest(): PreparedOcrRequest {
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        return PreparedOcrRequest(
            bitmap = bitmap,
            originalRequest = OcrRequest(bitmap = bitmap, language = OcrLanguage.RUSSIAN_ENGLISH),
            preprocessingApplied = emptyList()
        )
    }

    @Test
    fun goodHuaweiResultDoesNotRunTesseract() = runBlocking {
        val registry = OcrEngineRegistry()
        val huawei = FakeEngine(
            id = "huawei_mlkit",
            text = "Договор оказания услуг номер двенадцать от марта"
        )
        val tesseract = FakeEngine(id = "tesseract", text = "")
        registry.register(huawei)
        registry.register(tesseract)

        val candidates = HybridStrategy(registry).recognize(preparedRequest())

        assertEquals("Huawei должен стоять первым", 1, huawei.calls)
        assertEquals("при хорошем результате Tesseract не запускается", 0, tesseract.calls)
        assertEquals(1, candidates.size)
        assertEquals("huawei_mlkit", candidates.first().engineId)
    }

    @Test
    fun emptyHuaweiResultFallsBackToTesseract() = runBlocking {
        val registry = OcrEngineRegistry()
        val huawei = FakeEngine(id = "huawei_mlkit", text = "  ")
        val tesseract = FakeEngine(id = "tesseract", text = "Распознанный текст резервным движком")
        registry.register(huawei)
        registry.register(tesseract)

        val candidates = HybridStrategy(registry).recognize(preparedRequest())

        assertEquals(1, huawei.calls)
        assertEquals(1, tesseract.calls)
        assertTrue("слабый кандидат Huawei остаётся, резервный добавлен", candidates.size == 2)
        assertEquals("tesseract", candidates.last().engineId)
    }

    @Test
    fun garbageHuaweiResultFallsBackToTesseract() = runBlocking {
        val registry = OcrEngineRegistry()
        val huawei = FakeEngine(id = "huawei_mlkit", text = "@@@ ### %%% $$$")
        val tesseract = FakeEngine(id = "tesseract", text = "Распознанный текст резервным движком")
        registry.register(huawei)
        registry.register(tesseract)

        val candidates = HybridStrategy(registry).recognize(preparedRequest())

        assertEquals(1, tesseract.calls)
        assertEquals(2, candidates.size)
    }

    @Test
    fun missingHuaweiEngineUsesTesseract() = runBlocking {
        val registry = OcrEngineRegistry()
        val tesseract = FakeEngine(id = "tesseract", text = "Текст")
        registry.register(tesseract)

        val candidates = HybridStrategy(registry).recognize(preparedRequest())

        assertEquals(1, tesseract.calls)
        assertEquals(listOf("tesseract"), candidates.map { it.engineId })
    }

    @Test
    fun googleMlKitIsNotPartOfSmartChain() = runBlocking {
        val registry = OcrEngineRegistry()
        val google = FakeEngine(id = "google_mlkit", text = "Какой-то текст от Google")
        registry.register(google)

        val candidates = HybridStrategy(registry).recognize(preparedRequest())

        assertEquals("Google ML Kit не должен вызываться в умной цепочке", 0, google.calls)
        assertTrue(candidates.isEmpty())
    }
}
