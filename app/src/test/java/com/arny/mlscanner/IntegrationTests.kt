package com.arny.mlscanner

import android.graphics.Bitmap
import com.arny.mlscanner.data.matching.MatchingEngine
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
import com.arny.mlscanner.domain.usecases.OcrRepository
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
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class IntegrationTests {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private lateinit var testDispatcher: TestDispatcher
    
    @Mock
    private lateinit var mockOcrRepository: OcrRepository
    
    @Mock
    private lateinit var mockImagePreprocessor: ImagePreprocessor
    
    @Mock
    private lateinit var mockPdfRedactionEngine: PdfRedactionEngine
    
    @Mock
    private lateinit var mockMatchingEngine: MatchingEngine
    
    private lateinit var viewModel: AdvancedScanViewModel
    private lateinit var useCase: AdvancedRecognizeTextUseCase

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        testDispatcher = StandardTestDispatcher()
        
        useCase = AdvancedRecognizeTextUseCase(
            ocrRepository = mockOcrRepository,
            imagePreprocessor = mockImagePreprocessor,
            ioDispatcher = testDispatcher
        )
        viewModel = AdvancedScanViewModel(
            advancedRecognizeTextUseCase = useCase,
            pdfRedactionEngine = mockPdfRedactionEngine,
            matchingEngine = mockMatchingEngine
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `full document scanning workflow should work end to end`() = runTest {
        whenever(mockOcrRepository.initialize()).thenReturn(emptyMap())
        
        val inputBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val preprocessedBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val ocrResult = createSampleOcrResult()

        whenever(mockImagePreprocessor.prepareBaseImage(any(), any()))
            .thenReturn(preprocessedBitmap)
        whenever(mockOcrRepository.recognizeWith(eq(preprocessedBitmap), eq("HYBRID"), any()))
            .thenReturn(ocrResult)

        viewModel.recognizeText(inputBitmap, ScanSettings())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.ocrResult)
    }

    @Test
    fun `sensitive data detection and redaction workflow should work`() = runTest {
        whenever(mockOcrRepository.initialize()).thenReturn(emptyMap())
            
        val inputBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val ocrResult = createSampleOcrResultWithSensitiveData()

        whenever(mockImagePreprocessor.prepareBaseImage(any(), any()))
            .thenReturn(inputBitmap)
        whenever(mockOcrRepository.recognizeWith(any(), any(), any()))
            .thenReturn(ocrResult)

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
        whenever(mockPdfRedactionEngine.redactAndSavePdf(any(), any(), any(), any()))
            .thenReturn(expectedPdf)

        viewModel.createRedactedPdf("in.jpg", ocrResult.textBoxes, mask, "out.pdf")
        advanceUntilIdle()

        assertNotNull(viewModel.uiState.value.redactionResult)
    }

    @Test
    fun `data matching workflow should connect OCR with reference data`() = runTest {
        whenever(mockOcrRepository.initialize()).thenReturn(emptyMap())
            
        val inputBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val ocrResult = createSampleOcrResultWithSKUs()

        whenever(mockImagePreprocessor.prepareBaseImage(any(), any()))
            .thenReturn(inputBitmap)
        whenever(mockOcrRepository.recognizeWith(any(), any(), any()))
            .thenReturn(ocrResult)

        val matchResult = MatchingResult(
            matchedItems = listOf(
                MatchedItem("ABC123DEF", null, 0.9f, ocrResult.textBoxes[0].boundingBox)
            ),
            unmatchedTexts = emptyList(),
            confidenceThreshold = 0.8f,
            timestamp = System.currentTimeMillis()
        )
        whenever(mockMatchingEngine.batchMatch(anyList(), any(), anyInt()))
            .thenReturn(matchResult)

        viewModel.recognizeText(inputBitmap, ScanSettings())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertNotNull(state.matchingResult)
        assertEquals(matchResult, state.matchingResult)
    }

    @Test
    fun `error handling across components should be consistent`() = runTest {
        whenever(mockOcrRepository.initialize()).thenReturn(emptyMap())
            
        val inputBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val errorMsg = "Simulated Preprocessing Error"

        whenever(mockImagePreprocessor.prepareBaseImage(any(), any()))
            .thenThrow(RuntimeException(errorMsg))

        viewModel.recognizeText(inputBitmap, ScanSettings())
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.ocrResult)
        assertEquals(errorMsg, state.errorMessage)
    }

    private fun createSampleOcrResult() = OcrResult(
        blocks = listOf(
            com.arny.mlscanner.domain.models.TextBlock(
                text = "Sample document text",
                boundingBox = BoundingBox(100f, 100f, 300f, 150f),
                lines = emptyList(),
                confidence = 0.85f
            ),
            com.arny.mlscanner.domain.models.TextBlock(
                text = "More sample text",
                boundingBox = BoundingBox(100f, 160f, 350f, 200f),
                lines = emptyList(),
                confidence = 0.92f
            )
        ),
        fullText = "Sample document text\nMore sample text"
    )

    private fun createSampleOcrResultWithSensitiveData() = OcrResult(
        blocks = listOf(
            com.arny.mlscanner.domain.models.TextBlock(
                text = "Email: test@example.com",
                boundingBox = BoundingBox(10f, 10f, 100f, 20f),
                lines = emptyList(),
                confidence = 0.95f
            )
        ),
        fullText = "Email: test@example.com"
    )

    private fun createSampleOcrResultWithSKUs() = OcrResult(
        blocks = listOf(
            com.arny.mlscanner.domain.models.TextBlock(
                text = "Product SKU: ABC123DEF",
                boundingBox = BoundingBox(100f, 100f, 300f, 150f),
                lines = emptyList(),
                confidence = 0.9f
            )
        ),
        fullText = "Product SKU: ABC123DEF"
    )
}

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
