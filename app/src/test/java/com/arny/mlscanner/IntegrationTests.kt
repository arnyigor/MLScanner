package com.arny.mlscanner

import android.graphics.Bitmap
import com.arny.mlscanner.data.matching.MatchMode
import com.arny.mlscanner.data.matching.MatchingEngine
import com.arny.mlscanner.data.ocr.OcrEngine
import com.arny.mlscanner.data.pdf.PdfRedactionEngine
import com.arny.mlscanner.data.pdf.RedactionMask
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.MatchedItem
import com.arny.mlscanner.domain.models.MatchingResult
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.RedactionResult
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.models.TextBox
import com.arny.mlscanner.domain.usecases.AdvancedRecognizeTextUseCase
import com.arny.mlscanner.ui.screens.AdvancedScanViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Интеграционные тесты для SecureField MVP */
@ExperimentalCoroutinesApi
class IntegrationTests {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    @Mock
    private lateinit var mockOcrEngine: OcrEngine

    @Mock
    private lateinit var mockImagePreprocessor: ImagePreprocessor

    @Mock
    private lateinit var mockPdfRedactionEngine: PdfRedactionEngine

    @Mock
    private lateinit var mockMatchingEngine: MatchingEngine

    private lateinit var viewModel: AdvancedScanViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        whenever(mockOcrEngine.initialize()).thenReturn(true)

        // Тестовый dispatcher для useCase – заменяет real Default‑dispatcher
        val testDispatcher: TestDispatcher = StandardTestDispatcher()
        val useCase = AdvancedRecognizeTextUseCase(
            ocrEngine = mockOcrEngine,
            imagePreprocessor = mockImagePreprocessor,
            ioDispatcher = testDispatcher          // ← NEW
        )
        viewModel = AdvancedScanViewModel(
            advancedRecognizeTextUseCase = useCase,
            pdfRedactionEngine = mockPdfRedactionEngine,
            matchingEngine = mockMatchingEngine
        )
    }

    @After
    fun tearDown() {
        // Rule очищает диспетчеры
    }

    /* ------------------------------------------------------------------ */
    /*  ТЕСТЫ                                                             */
    /* ------------------------------------------------------------------ */

    /** Полный поток сканирования документа */
    @Test
    fun `full document scanning workflow should work end to end`() = runTest {
        val inputBitmap = createTestDocumentBitmap()
        val preprocessedBitmap = createTestDocumentBitmap()
        val ocrResult = createSampleOcrResult()

        whenever(mockImagePreprocessor.preprocessImage(any<Bitmap>(), any())).thenReturn(
            preprocessedBitmap
        )
        whenever(mockOcrEngine.recognize(eq(preprocessedBitmap))).thenReturn(ocrResult)

        viewModel.recognizeText(inputBitmap, ScanSettings())
        advanceUntilIdle()   // ждём завершения всех корутин

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.ocrResult)
        assertEquals(ocrResult, state.ocrResult)

        verify(mockMatchingEngine).batchMatch(
            anyList(),
            any<MatchMode>(),
            anyInt()
        )
    }

    /** Проверяем работу с чувствительными данными и PDF‑маскировку */
    @Test
    fun `sensitive data detection and redaction workflow should work`() = runTest {
        val inputBitmap = createTestDocumentBitmap()
        val ocrResult = createSampleOcrResultWithSensitiveData()

        whenever(
            mockImagePreprocessor.preprocessImage(
                any<Bitmap>(),
                any()
            )
        ).thenReturn(inputBitmap)
        whenever(mockOcrEngine.recognize(any())).thenReturn(ocrResult)

        viewModel.recognizeText(inputBitmap, ScanSettings())
        advanceUntilIdle()

        val mask = RedactionMask(
            redactedBoxes = ocrResult.textBoxes.filter { it.text.contains("@") }
        )

        val expectedPdf = RedactionResult(
            originalImagePath = "in.jpg",
            redactedImagePath = "out.pdf",
            redactedAreas = emptyList(),
            timestamp = System.currentTimeMillis()
        )
        whenever(
            mockPdfRedactionEngine.redactAndSavePdf(
                eq("in.jpg"), eq(ocrResult.textBoxes), eq(mask), eq("out.pdf")
            )
        ).thenReturn(expectedPdf)

        viewModel.createRedactedPdf("in.jpg", ocrResult.textBoxes, mask, "out.pdf")
        advanceUntilIdle()

        verify(mockPdfRedactionEngine).redactAndSavePdf(
            eq("in.jpg"), eq(ocrResult.textBoxes), eq(mask), eq("out.pdf")
        )
        assertNotNull(viewModel.uiState.value.redactionResult)
    }

    /** Тест связывания OCR‑текста с справочником */
    @Test
    fun `data matching workflow should connect OCR with reference data`() = runTest {
        val inputBitmap = createTestDocumentBitmap()
        val ocrResult = createSampleOcrResultWithSKUs()

        whenever(
            mockImagePreprocessor.preprocessImage(
                any<Bitmap>(),
                any()
            )
        ).thenReturn(inputBitmap)
        whenever(mockOcrEngine.recognize(any())).thenReturn(ocrResult)

        // 1️⃣ Имитируем результат поиска
        val matchResult = MatchingResult(
            matchedItems = listOf(
                MatchedItem("ABC123DEF", null, 0.9f, ocrResult.textBoxes[0].boundingBox)
            ),
            unmatchedTexts = emptyList(),
            confidenceThreshold = 0.8f,
            timestamp = System.currentTimeMillis()
        )
        whenever(
            mockMatchingEngine.batchMatch(
                anyList(), any<MatchMode>(), anyInt()
            )
        ).thenReturn(matchResult)

        viewModel.recognizeText(inputBitmap, ScanSettings())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.matchingResult)
        assertEquals(matchResult, state.matchingResult)

        verify(mockMatchingEngine).batchMatch(
            argThat<List<String>> { list -> list.contains("Product SKU: ABC123DEF") },
            any<MatchMode>(),
            anyInt()
        )
    }

    /** Тест обработки ошибок в цепочке компонентов */
    @Test
    fun `error handling across components should be consistent`() = runTest {
        val inputBitmap = createTestDocumentBitmap()
        val errorMsg = "Simulated Preprocessing Error"

        whenever(mockImagePreprocessor.preprocessImage(any<Bitmap>(), any()))
            .thenThrow(RuntimeException(errorMsg))

        viewModel.recognizeText(inputBitmap, ScanSettings())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.ocrResult)
        assertEquals(errorMsg, state.errorMessage)
    }

    /* ------------------------------------------------------------------ */
    /*  Вспомогательные методы                                            */
    /* ------------------------------------------------------------------ */

    private fun createTestDocumentBitmap(): Bitmap = mock()

    private fun createSampleOcrResult() = OcrResult(
        textBoxes = listOf(
            TextBox("Sample document text", 0.85f, BoundingBox(100f, 100f, 300f, 150f)),
            TextBox("More sample text", 0.92f, BoundingBox(100f, 160f, 350f, 200f))
        ),
        timestamp = System.currentTimeMillis()
    )

    private fun createSampleOcrResultWithSensitiveData() = OcrResult(
        textBoxes = listOf(
            TextBox("Email: test@example.com", 0.95f, BoundingBox(10f, 10f, 100f, 20f))
        ),
        timestamp = System.currentTimeMillis()
    )

    private fun createSampleOcrResultWithSKUs() = OcrResult(
        textBoxes = listOf(
            TextBox(
                "Product SKU: ABC123DEF", 0.9f,
                BoundingBox(100f, 100f, 300f, 150f)
            )
        ),
        timestamp = System.currentTimeMillis()
    )
}

/** Тестовый Rule для работы с MainDispatcher */
@ExperimentalCoroutinesApi
class MainCoroutineRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description?) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description?) {
        Dispatchers.resetMain()
    }
}
