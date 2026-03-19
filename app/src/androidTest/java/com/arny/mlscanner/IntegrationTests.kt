package com.arny.mlscanner

import android.graphics.Bitmap
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.RecognizedText
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.models.TextBlock
import com.arny.mlscanner.domain.models.TextLine
import com.arny.mlscanner.domain.models.TextWord
import com.arny.mlscanner.domain.usecases.OcrRepository
import com.arny.mlscanner.domain.usecases.RecognizeTextUseCase
import com.arny.mlscanner.ui.screens.ScanViewModel
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
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
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

    private lateinit var viewModel: ScanViewModel
    private lateinit var recognizeTextUseCase: RecognizeTextUseCase

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        testDispatcher = StandardTestDispatcher()

        recognizeTextUseCase = RecognizeTextUseCase(
            ocrRepository = mockOcrRepository
        )
        viewModel = ScanViewModel(
            recognizeTextUseCase = recognizeTextUseCase,
            imagePreprocessor = mockImagePreprocessor
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `basic OCR workflow should work`() = runTest {
        // Arrange
        val inputBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val preprocessedBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val sampleText = "Hello World"
        val word = TextWord(
            text = sampleText,
            boundingBox = BoundingBox(0f, 0f, 50f, 20f),
            confidence = 0.95f
        )
        val line = TextLine(
            text = sampleText,
            boundingBox = BoundingBox(0f, 0f, 50f, 20f),
            words = listOf(word),
            confidence = 0.95f
        )
        val block = TextBlock(
            text = sampleText,
            boundingBox = BoundingBox(0f, 0f, 50f, 20f),
            lines = listOf(line),
            confidence = 0.95f
        )
        val ocrResult = OcrResult(
            blocks = listOf(block),
            fullText = sampleText
        )

        // Mock the preprocessing to return our preprocessed bitmap
        whenever(mockImagePreprocessor.prepareBaseImage(any(), any()))
            .thenReturn(preprocessedBitmap)
        // Mock the OCR repository to return our sample result
        whenever(mockOcrRepository.recognizeWith(eq(preprocessedBitmap), any(), any()))
            .thenReturn(ocrResult)

        // Act
        viewModel.onImageCaptured(inputBitmap)
        viewModel.onStartScanning()
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertFalse(state.isScanning)
        assertNotNull(state.recognizedText)
        assertEquals(sampleText, state.recognizedText?.formattedText)
    }

    @Test
    fun `error handling in OCR should propagate to UI`() = runTest {
        // Arrange
        val inputBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val errorMessage = "OCR failed"

        // Mock the preprocessing to return a bitmap
        val preprocessedBitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        whenever(mockImagePreprocessor.prepareBaseImage(any(), any()))
            .thenReturn(preprocessedBitmap)
        // Mock the OCR repository to throw an exception
        whenever(mockOcrRepository.recognizeWith(any(), any(), any()))
            .thenThrow(RuntimeException(errorMessage))

        // Act
        viewModel.onImageCaptured(inputBitmap)
        viewModel.onStartScanning()
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertFalse(state.isScanning)
        assertNull(state.recognizedText)
        assertTrue(state.error?.message == errorMessage)
    }
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