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
        viewModel = AdvancedScanViewModel(mockUseCase, mockPdfEngine, mockMatchingEngine)
    }

    @After
    fun tearDown() {
        // Resources imply cleared by Rules
    }

    @Test
    fun `should initialize engine on creation`() = runTest {
        createViewModel()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isOcrEngineInitialized)
    }

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

        // <‑‑ КОРРЕКТНОЕ МОКИРОВАНИЕ suspend‑функции
        whenever(mockUseCase.execute(any(), any())).thenReturn(ocrResult)
        whenever(mockMatchingEngine.batchMatch(any())).thenReturn(matchingResult)

        // Act
        viewModel.recognizeText(mockBitmap, settings)
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(ocrResult, state.ocrResult)
        assertEquals(matchingResult, state.matchingResult)
    }

    @Test
    fun `createRedactedPdf should flow through loading to success`() = runTest {
        // Arrange
        createViewModel()
        val boxes = listOf<TextBox>()

        // FIX: Создаем реальный объект RedactionMask вместо несуществующего Enum
        val mask = RedactionMask(redactedBoxes = emptyList())

        val inputPath = "in.jpg"
        val outputPath = "out.pdf"

        // FIX: Корректный конструктор RedactionResult (строки вместо File)
        val redactionResult = RedactionResult(
            originalImagePath = inputPath,
            redactedImagePath = outputPath,
            redactedAreas = emptyList(),
            timestamp = System.currentTimeMillis()
        )

        whenever(mockPdfEngine.redactAndSavePdf(any(), any(), any(), any())).thenReturn(
            redactionResult
        )

        // Act
        viewModel.createRedactedPdf(inputPath, boxes, mask, outputPath)
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertFalse(state.isCreatingPdf)
        assertEquals(redactionResult, state.redactionResult)
    }

    @Test
    fun `createRedactedPdf should handle exceptions`() = runTest {
        // Arrange
        createViewModel()
        whenever(mockPdfEngine.redactAndSavePdf(any(), any(), any(), any()))
            .thenThrow(RuntimeException("PDF Error"))

        // FIX: Создаем реальный объект RedactionMask
        val mask = RedactionMask(redactedBoxes = emptyList())

        // Act
        viewModel.createRedactedPdf("in", emptyList(), mask, "out")
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertFalse(state.isCreatingPdf)
        assertTrue(state.errorMessage?.contains("PDF Error") == true)
    }

    private fun createTestOcrResult(): OcrResult {
        return OcrResult(
            textBoxes = listOf(
                TextBox("Test", 0.9f, mock())
            ),
            timestamp = System.currentTimeMillis()
        )
    }
}

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