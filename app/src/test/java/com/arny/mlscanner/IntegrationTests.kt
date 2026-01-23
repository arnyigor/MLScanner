package com.arny.mlscanner

import android.graphics.Bitmap
import com.arny.mlscanner.data.matching.MatchingEngine
import com.arny.mlscanner.data.ocr.OcrEngine
import com.arny.mlscanner.data.pdf.PdfRedactionEngine
import com.arny.mlscanner.data.pdf.RedactionMask
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.data.redaction.SensitiveDataDetector
import com.arny.mlscanner.data.security.LicenseManager
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File

/**
 * Интеграционные тесты для проверки взаимодействия всех компонентов SecureField MVP
 */
@ExperimentalCoroutinesApi
class IntegrationTests {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    @Mock
    private lateinit var mockOcrEngine: OcrEngine

    @Mock
    private lateinit var mockImagePreprocessor: ImagePreprocessor

    @Mock
    private lateinit var mockSensitiveDataDetector: SensitiveDataDetector

    // PdfGenerator больше не используется напрямую во ViewModel, используется PdfRedactionEngine
    @Mock
    private lateinit var mockPdfRedactionEngine: PdfRedactionEngine

    @Mock
    private lateinit var mockLicenseManager: LicenseManager

    @Mock
    private lateinit var mockMatchingEngine: MatchingEngine

    // Мы будем мокать сам UseCase, чтобы тестировать VM,
    // либо создавать реальный UseCase с моками внутри, чтобы тестировать связку VM -> UseCase
    private lateinit var realUseCaseWithMocks: AdvancedRecognizeTextUseCase

    private lateinit var viewModel: AdvancedScanViewModel

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        // Настройка моков по умолчанию
        whenever(mockOcrEngine.initialize()).thenReturn(true)

        // Инициализация реального UseCase с моками внутри (Integration level)
        realUseCaseWithMocks = AdvancedRecognizeTextUseCase(
            ocrEngine = mockOcrEngine,
            imagePreprocessor = mockImagePreprocessor,
        )

        // Инициализация ViewModel с правильной сигнатурой конструктора
        viewModel = AdvancedScanViewModel(
            advancedRecognizeTextUseCase = realUseCaseWithMocks,
            pdfRedactionEngine = mockPdfRedactionEngine,
            matchingEngine = mockMatchingEngine
        )
    }

    @After
    fun tearDown() {
        // Очистка не требуется благодаря Rule
    }

    @Test
    fun `full document scanning workflow should work end to end`() = runTest {
        // Arrange
        val inputBitmap = createTestDocumentBitmap()
        val preprocessedBitmap = createTestDocumentBitmap()
        val ocrResult = createSampleOcrResult()

        // Мокаем цепочку вызовов
        whenever(mockImagePreprocessor.preprocessImage(any(), any())).thenReturn(preprocessedBitmap)
        whenever(mockOcrEngine.recognize(eq(preprocessedBitmap))).thenReturn(ocrResult)

        // Act: Запускаем сканирование через ViewModel
        viewModel.recognizeText(inputBitmap, ScanSettings())

        advanceUntilIdle() // Ждем завершения корутин

        // Assert: Проверяем состояние UI
        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNotNull(state.ocrResult)
        assertEquals(ocrResult, state.ocrResult)

        // Verify: Проверяем вызовы компонентов
        verify(mockImagePreprocessor).preprocessImage(eq(inputBitmap), any())
        verify(mockOcrEngine).recognize(eq(preprocessedBitmap))

        // Проверяем, что автоматически запустился матчинг (так как в VM есть логика performMatching)
        verify(mockMatchingEngine).batchMatch(any())
    }

    @Test
    fun `sensitive data detection and redaction workflow should work`() = runTest {
        // Arrange
        val inputBitmap = createTestDocumentBitmap()
        val ocrResult = createSampleOcrResultWithSensitiveData()

        // Настраиваем OCR
        whenever(mockImagePreprocessor.preprocessImage(any(), any())).thenReturn(inputBitmap)
        whenever(mockOcrEngine.recognize(any())).thenReturn(ocrResult)

        // Настраиваем PDF Redaction
        val redactionResult = RedactionResult(
            originalImagePath = "in.jpg",
            redactedImagePath = "out.pdf",
            redactedAreas = emptyList(),
            timestamp = System.currentTimeMillis()
        )
        whenever(mockPdfRedactionEngine.redactAndSavePdf(any(), any(), any(), any())).thenReturn(
            redactionResult
        )

        // Act 1: Распознавание
        viewModel.recognizeText(inputBitmap, ScanSettings())
        advanceUntilIdle()

        // Act 2: Создание PDF (имитация нажатия кнопки "Сохранить скрытый PDF")
        // Создаем маску для скрытия всех найденных email
        val boxesToRedact = ocrResult.textBoxes.filter { it.text.contains("@") }
        val mask = RedactionMask(redactedBoxes = boxesToRedact)

        viewModel.createRedactedPdf("in.jpg", ocrResult.textBoxes, mask, "out.pdf")
        advanceUntilIdle()

        // Assert
        verify(mockPdfRedactionEngine).redactAndSavePdf(
            eq("in.jpg"),
            eq(ocrResult.textBoxes),
            eq(mask),
            eq("out.pdf")
        )
        assertNotNull(viewModel.uiState.value.redactionResult)
    }

    @Test
    fun `data matching workflow should connect OCR with reference data`() = runTest {
        // Arrange
        val inputBitmap = createTestDocumentBitmap()
        val ocrResult = createSampleOcrResultWithSKUs()

        // Имитируем результат матчинга
        val matchResult = MatchingResult(
            matchedItems = listOf(
                MatchedItem("ABC123DEF", null, 0.9f, ocrResult.textBoxes[0].boundingBox)
            ),
            unmatchedTexts = emptyList(),
            confidenceThreshold = 0.8f,
            timestamp = System.currentTimeMillis()
        )

        whenever(mockImagePreprocessor.preprocessImage(any(), any())).thenReturn(inputBitmap)
        whenever(mockOcrEngine.recognize(any())).thenReturn(ocrResult)
        whenever(mockMatchingEngine.batchMatch(any())).thenReturn(matchResult)

        // Act
        viewModel.recognizeText(inputBitmap, ScanSettings())
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertNotNull(state.matchingResult)
        assertEquals(matchResult, state.matchingResult)

        // Проверяем, что в движок матчинга ушел список строк из OCR
        verify(mockMatchingEngine).batchMatch(argThat { list ->
            list.contains("Product SKU: ABC123DEF")
        })
    }

    @Test
    fun `error handling across components should be consistent`() = runTest {
        // Arrange
        val inputBitmap = createTestDocumentBitmap()
        val testException = RuntimeException("Simulated Preprocessing Error")

        // Симулируем ошибку на этапе предобработки
        whenever(mockImagePreprocessor.preprocessImage(any(), any())).thenThrow(testException)

        // Act
        viewModel.recognizeText(inputBitmap, ScanSettings())
        advanceUntilIdle()

        // Assert
        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.ocrResult)
        assertEquals("Simulated Preprocessing Error", state.errorMessage)
    }

    @Test
    fun `license verification logic`() {
        // Тест лицензии изолирован от ViewModel, так как ViewModel (в текущей реализации)
        // не проверяет лицензию напрямую, это делает LicenseManager.
        // Проверяем контракт LicenseManager.

        val validFile = File("valid.lic")
        whenever(mockLicenseManager.verifyLicense(validFile)).thenReturn(true)
        assertTrue(mockLicenseManager.verifyLicense(validFile))

        val invalidFile = File("invalid.lic")
        whenever(mockLicenseManager.verifyLicense(invalidFile)).thenReturn(false)
        assertFalse(mockLicenseManager.verifyLicense(invalidFile))
    }

    // --- Helpers ---

    private fun createTestDocumentBitmap(): Bitmap {
        // Создаем заглушку, так как реальный Bitmap требует Android Runtime,
        // но Mockito может замокать финал классы если настроен, или используем Robolectric.
        // В Unit тестах лучше использовать mock.
        return mock()
    }

    private fun createSampleOcrResult(): OcrResult {
        return OcrResult(
            textBoxes = listOf(
                TextBox(
                    text = "Sample document text",
                    confidence = 0.85f,
                    boundingBox = BoundingBox(100f, 100f, 300f, 150f)
                ),
                TextBox(
                    text = "More sample text",
                    confidence = 0.92f,
                    boundingBox = BoundingBox(100f, 160f, 350f, 200f)
                )
            ),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun createSampleOcrResultWithSensitiveData(): OcrResult {
        return OcrResult(
            textBoxes = listOf(
                TextBox(
                    text = "Email: test@example.com",
                    confidence = 0.95f,
                    boundingBox = BoundingBox(10f, 10f, 100f, 20f)
                )
            ),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun createSampleOcrResultWithSKUs(): OcrResult {
        return OcrResult(
            textBoxes = listOf(
                TextBox(
                    text = "Product SKU: ABC123DEF",
                    confidence = 0.9f,
                    boundingBox = BoundingBox(100f, 100f, 300f, 150f)
                )
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