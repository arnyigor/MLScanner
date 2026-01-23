package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.arny.mlscanner.data.matching.MatchingEngine
import com.arny.mlscanner.data.pdf.PdfRedactionEngine
import com.arny.mlscanner.data.pdf.RedactionMask
import com.arny.mlscanner.domain.models.MatchingResult
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.RedactionResult
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.models.TextBox
import com.arny.mlscanner.domain.usecases.AdvancedRecognizeTextUseCase
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
import org.junit.Assert.assertTrue
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class AdvancedScanViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()
    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    @Mock
    private lateinit var mockUseCase: AdvancedRecognizeTextUseCase
    @Mock
    private lateinit var mockPdfEngine: PdfRedactionEngine
    @Mock
    private lateinit var mockMatchingEngine: MatchingEngine

    @Mock
    private lateinit var mockBitmap: Bitmap

    private lateinit var viewModel: AdvancedScanViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        whenever(mockUseCase.initializeOcrEngine()).thenReturn(true)
    }

    private fun createViewModel() {
        viewModel = AdvancedScanViewModel(
            advancedRecognizeTextUseCase = mockUseCase,
            pdfRedactionEngine = mockPdfEngine,
            matchingEngine = mockMatchingEngine
        )
    }

    @After
    fun tearDown() {
        // Resources are released by Rules
    }

    /** 1. Проверка инициализации OCR‑движка */
    @Test
    fun `should initialize engine on creation`() = runTest {
        createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isOcrEngineInitialized)
    }

    /** 2. Проверка распознавания текста + сопоставления */
    @Test
    fun `recognizeText should update state to success and perform matching`() = runTest {
        // Arrange
        createViewModel()
        val settings = ScanSettings()

        val ocrResult = createTestOcrResult()
        val matchingResult = MatchingResult(
            matchedItems = emptyList(),
            unmatchedTexts = emptyList(),
            confidenceThreshold = 0.8f,
            timestamp = System.currentTimeMillis()
        )

        // КОРРЕКТНОЕ МОКИРОВАНИЕ suspend‑функций
        whenever(mockUseCase.execute(any(), any())).thenReturn(ocrResult)
        // batchMatch имеет три параметра → все они должны быть матчерами
        whenever(
            mockMatchingEngine.batchMatch(anyList(), any(), anyInt())
        ).thenReturn(matchingResult)

        // Act
        viewModel.recognizeText(mockBitmap, settings)
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(ocrResult, state.ocrResult)
        assertEquals(matchingResult, state.matchingResult)
    }

    /** 3. Создание защищённого PDF */
    @Test
    fun `createRedactedPdf should flow through loading to success`() = runTest {
        // Arrange
        createViewModel()
        val boxes = listOf<TextBox>()

        val mask = RedactionMask(redactedBoxes = emptyList())

        val inputPath = "in.jpg"
        val outputPath = "out.pdf"

        val redactionResult = RedactionResult(
            originalImagePath = inputPath,
            redactedImagePath = outputPath,
            redactedAreas = emptyList(),
            timestamp = System.currentTimeMillis()
        )

        whenever(mockPdfEngine.redactAndSavePdf(any(), any(), any(), any()))
            .thenReturn(redactionResult)

        // Act
        viewModel.createRedactedPdf(inputPath, boxes, mask, outputPath)
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertFalse(state.isCreatingPdf)
        assertEquals(redactionResult, state.redactionResult)
    }

    /** 4. Обработка исключения при создании PDF */
    @Test
    fun `createRedactedPdf should handle exceptions`() = runTest {
        // Arrange
        createViewModel()
        whenever(mockPdfEngine.redactAndSavePdf(any(), any(), any(), any()))
            .thenThrow(RuntimeException("PDF Error"))

        val mask = RedactionMask(redactedBoxes = emptyList())

        // Act
        viewModel.createRedactedPdf("in", emptyList(), mask, "out")
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertFalse(state.isCreatingPdf)
        assertTrue(state.errorMessage?.contains("PDF Error") == true)
    }

    private fun createTestOcrResult(): OcrResult =
        OcrResult(
            textBoxes = listOf(TextBox("Test", 0.9f, mock())),
            timestamp = System.currentTimeMillis()
        )
}

/** Тестовый Rule для работы с MainDispatcher */
@ExperimentalCoroutinesApi
class MainCoroutineRule(
    val dispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
