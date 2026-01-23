package com.arny.mlscanner.domain.usecases

import android.graphics.Bitmap
import com.arny.mlscanner.data.ocr.OcrEngine
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.models.TextBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class AdvancedRecognizeTextUseCaseTest {

    @Mock
    private lateinit var mockOcrEngine: OcrEngine

    @Mock
    private lateinit var mockImagePreprocessor: ImagePreprocessor

    private lateinit var useCase: AdvancedRecognizeTextUseCase

    // Заменяем @Before → @BeforeEach
    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        Dispatchers.setMain(StandardTestDispatcher())
        useCase = AdvancedRecognizeTextUseCase(mockOcrEngine, mockImagePreprocessor)
    }

    // Заменяем @After → @AfterEach
    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `should preprocess image with provided settings and run OCR`() = runTest {
        // Given
        val originalBitmap = createTestBitmap()
        val preprocessedBitmap = createTestBitmap()
        val settings = ScanSettings(
            detectDocument = true,
            denoiseEnabled = true,
            contrastLevel = 1.2f,
            brightnessLevel = 10f,
            sharpenLevel = 0.5f,
            binarizationEnabled = false,
            autoRotateEnabled = true
        )
        val ocrResult = createTestOcrResult()

        whenever(mockImagePreprocessor.preprocessImage(eq(originalBitmap), eq(settings)))
            .thenReturn(preprocessedBitmap)
        whenever(mockOcrEngine.recognize(eq(preprocessedBitmap)))
            .thenReturn(ocrResult)

        // When
        val result = useCase.execute(originalBitmap, settings)

        // Then
        verify(mockImagePreprocessor).preprocessImage(eq(originalBitmap), eq(settings))
        verify(mockOcrEngine).recognize(eq(preprocessedBitmap))
        assertEquals(ocrResult, result)
    }

    @Test
    fun `should propagate preprocessing exception`() = runTest {
        val bitmap = createTestBitmap()
        val settings = ScanSettings()
        val exception = RuntimeException("Preprocessing failed")

        whenever(mockImagePreprocessor.preprocessImage(any(), any())).thenThrow(exception)

        val thrown = assertThrows<RuntimeException> {
            useCase.execute(bitmap, settings)
        }

        assertEquals(exception.message, thrown.message)
    }

    @Test
    fun `should propagate OCR engine exception`() = runTest {
        val bitmap = createTestBitmap()
        val settings = ScanSettings()
        val preprocessed = createTestBitmap()
        val exception = IllegalStateException("OcrEngine not initialized")

        whenever(mockImagePreprocessor.preprocessImage(any(), any())).thenReturn(preprocessed)
        whenever(mockOcrEngine.recognize(any())).thenThrow(exception)

        val thrown = assertThrows<IllegalStateException> {
            useCase.execute(bitmap, settings)
        }

        assertEquals(exception.message, thrown.message)
    }

    @Test
    fun `should handle different image sizes correctly`() = runTest {
        val sizes = listOf(Pair(320, 240), Pair(1920, 1080))
        val settings = ScanSettings()

        for ((w, h) in sizes) {
            val input = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val preprocessed = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val result = createTestOcrResult()

            whenever(mockImagePreprocessor.preprocessImage(eq(input), eq(settings))).thenReturn(
                preprocessed
            )
            whenever(mockOcrEngine.recognize(eq(preprocessed))).thenReturn(result)

            val output = useCase.execute(input, settings)

            Assertions.assertNotNull(output)
            assertEquals(result.textBoxes.size, output.textBoxes.size)
        }
    }

    @Test
    fun `should maintain bounding box validity`() = runTest {
        val bitmap = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val preprocessed = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
        val box = BoundingBox(50f, 60f, 200f, 100f)
        val ocrResult = OcrResult(
            textBoxes = listOf(TextBox("text", 0.9f, box)),
            timestamp = System.currentTimeMillis(),
            processingTimeMs = 100
        )

        whenever(mockImagePreprocessor.preprocessImage(any(), any())).thenReturn(preprocessed)
        whenever(mockOcrEngine.recognize(any())).thenReturn(ocrResult)

        val result = useCase.execute(bitmap, ScanSettings())

        assertTrue(result.textBoxes.isNotEmpty())
        val outBox = result.textBoxes[0].boundingBox
        assertTrue(outBox.left >= 0f)
        assertTrue(outBox.top >= 0f)
        assertTrue(outBox.right <= bitmap.width)
        assertTrue(outBox.bottom <= bitmap.height)
    }

    // Вспомогательные методы
    private fun createTestBitmap(): Bitmap {
        return Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888)
    }

    private fun createTestOcrResult(): OcrResult {
        return OcrResult(
            textBoxes = listOf(
                TextBox("Hello world", 0.95f, BoundingBox(10f, 10f, 100f, 30f))
            ),
            timestamp = System.currentTimeMillis(),
            processingTimeMs = 150
        )
    }
}