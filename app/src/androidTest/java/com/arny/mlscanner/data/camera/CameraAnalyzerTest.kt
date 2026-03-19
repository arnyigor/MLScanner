package com.arny.mlscanner.data.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy
import androidx.test.core.app.ApplicationProvider
import com.arny.mlscanner.data.ocr.OcrEngine
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.TextBlock
import com.arny.mlscanner.domain.models.TextLine
import com.arny.mlscanner.domain.models.TextWord
import com.arny.mlscanner.ui.screens.MainCoroutineRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.Rule
import org.mockito.ArgumentMatchers.any
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.whenever
import org.mockito.verify

@ExperimentalCoroutinesApi
class CameraAnalyzerTest {

    @get:Rule
    val mainCoroutineRule = MainCoroutineRule()

    private lateinit var testDispatcher: TestDispatcher

    @Mock
    private lateinit var mockOcrEngine: OcrEngine

    private lateinit var cameraAnalyzer: CameraAnalyzer
    private var capturedResult: OcrResult? = null

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        testDispatcher = StandardTestDispatcher()
        Dispatchers.setMain(testDispatcher)

        val context: Context = ApplicationProvider.getApplicationContext()
        cameraAnalyzer = CameraAnalyzer(
            context = context,
            ocrEngine = mockOcrEngine,
            onOcrResult = { capturedResult = it },
            onError = { /* ignore */ }
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `should analyze image proxy and trigger OCR`() = runTest {
        // Тестирование анализа изображения из камеры и запуска OCR
        val testBitmap = createTestBitmap()
        val ocrResult = createTestOcrResult()

        whenever(mockOcrEngine.recognize(any())).thenReturn(ocrResult)

        var capturedResult: OcrResult? = null
        val callback: (OcrResult) -> Unit = { result -> capturedResult = result }

        cameraAnalyzer.analyze(mockImageProxy, callback)

        advanceUntilIdle()

        verify(mockOcrEngine, times(1)).recognize(any())
        assertNotNull("OCR result should be captured", capturedResult)
        assertEquals(ocrResult, capturedResult)
    }

    @Test
    fun `should handle null image gracefully`() = runTest {
        // Тестирование корректной обработки null изображения
        whenever(mockImageProxy.image).thenReturn(null)

        var callbackCalled = false
        val callback: (OcrResult) -> Unit = { callbackCalled = true }

        cameraAnalyzer.analyze(mockImageProxy, callback)

        advanceUntilIdle()

        assertFalse("Callback should not be called with null image", callbackCalled)
    }

    @Test
    fun `should process images with different orientations`() = runTest {
        // Тестирование обработки изображений с разной ориентацией
        val orientations = listOf(0, 90, 180, 270)

        for (orientation in orientations) {
            val testBitmap = createTestBitmap()
            val ocrResult = createTestOcrResult()

            whenever(mockImageProxy.image).thenReturn(mockImage)
            whenever(mockImageProxy.width).thenReturn(640)
            whenever(mockImageProxy.height).thenReturn(480)
            whenever(mockImageProxy.rotationDegrees).thenReturn(orientation)
            whenever(mockImageProxy.planes).thenReturn(arrayOf())
            whenever(mockOcrEngine.recognize(any())).thenReturn(ocrResult)

            var capturedResult: OcrResult? = null
            val callback: (OcrResult) -> Unit = { result -> capturedResult = result }

            cameraAnalyzer.analyze(mockImageProxy, callback)

            advanceUntilIdle()

            verify(mockOcrEngine, times(1)).recognize(any())
            assertNotNull("Should process image with orientation $orientation", capturedResult)
        }
    }

    @Test
    fun `should handle OCR processing errors`() = runTest {
        // Тестирование обработки ошибок OCR процессинга
        whenever(mockImageProxy.image).thenReturn(mockImage)
        whenever(mockImageProxy.width).thenReturn(640)
        whenever(mockImageProxy.height).thenReturn(480)
        whenever(mockImageProxy.planes).thenReturn(arrayOf())
        whenever(mockOcrEngine.recognize(any())).thenThrow(RuntimeException("OCR failed"))

        var errorOccurred = false
        val callback: (OcrResult) -> Unit = {
            // В случае ошибки результат не должен быть передан
        }

        try {
            cameraAnalyzer.analyze(mockImageProxy, callback)
            advanceUntilIdle()
        } catch (e: Exception) {
            errorOccurred = true
            assertTrue("Should handle OCR error gracefully", e is RuntimeException)
        }

        assertTrue("Error should have occurred", errorOccurred)
    }

    @Test
    fun `should maintain performance with continuous analysis`() = runTest {
        // Тестирование производительности при непрерывном анализе
        val testBitmap = createTestBitmap()
        val ocrResult = createTestOcrResult()

        whenever(mockImageProxy.image).thenReturn(mockImage)
        whenever(mockImageProxy.width).thenReturn(640)
        whenever(mockImageProxy.height).thenReturn(480)
        whenever(mockImageProxy.planes).thenReturn(arrayOf())
        whenever(mockOcrEngine.recognize(any())).thenReturn(ocrResult)

        val analysisCount = 10
        var resultCount = 0
        val callback: (OcrResult) -> Unit = { result -> resultCount++ }

        val startTime = System.currentTimeMillis()

        repeat(analysisCount) {
            cameraAnalyzer.analyze(mockImageProxy, callback)
        }

        advanceUntilIdle()

        val endTime = System.currentTimeMillis()
        val totalTime = endTime - startTime

        assertEquals("Should process all analyses", analysisCount, resultCount)
        assertTrue("Should process efficiently (average < 100ms per analysis)", totalTime < 1000) // 100ms * 10
    }

    @Test
    fun `should handle different image formats`() = runTest {
        // Тестирование обработки разных форматов изображений
        val formats = listOf(android.graphics.ImageFormat.YUV_420_888, android.graphics.ImageFormat.JPEG, android.graphics.ImageFormat.RGB_565)

        for (format in formats) {
            whenever(mockImageProxy.image).thenReturn(mockImage)
            whenever(mockImageProxy.width).thenReturn(640)
            whenever(mockImageProxy.height).thenReturn(480)
            whenever(mockImageProxy.format).thenReturn(format)
            whenever(mockImageProxy.planes).thenReturn(arrayOf())
            whenever(mockOcrEngine.recognize(any())).thenReturn(createTestOcrResult())

            var capturedResult: OcrResult? = null
            val callback: (OcrResult) -> Unit = { result -> capturedResult = result }

            cameraAnalyzer.analyze(mockImageProxy, callback)

            advanceUntilIdle()

            assertNotNull("Should handle image format $format", capturedResult)
        }
    }

    @Test
    fun `should handle high resolution images`() = runTest {
        // Тестирование обработки изображений высокого разрешения
        val highResWidth = 1920
        val highResHeight = 1080

        whenever(mockImageProxy.image).thenReturn(mockImage)
        whenever(mockImageProxy.width).thenReturn(highResWidth)
        whenever(mockImageProxy.height).thenReturn(highResHeight)
        whenever(mockImageProxy.planes).thenReturn(arrayOf())
        whenever(mockOcrEngine.recognize(any())).thenReturn(createTestOcrResult())

        var capturedResult: OcrResult? = null
        val callback: (OcrResult) -> Unit = { result -> capturedResult = result }

        val startTime = System.currentTimeMillis()
        cameraAnalyzer.analyze(mockImageProxy, callback)
        val processingTime = System.currentTimeMillis() - startTime

        advanceUntilIdle()

        assertNotNull("Should handle high resolution images", capturedResult)
        assertTrue("Processing should complete within reasonable time", processingTime < 3000) // 3 seconds
    }

    @Test
    fun `should handle rapid succession of image analyses`() = runTest {
        // Тестирование обработки быстрой последовательности анализов изображений
        val testBitmap = createTestBitmap()
        val ocrResult = createTestOcrResult()

        whenever(mockImageProxy.image).thenReturn(mockImage)
        whenever(mockImageProxy.width).thenReturn(640)
        whenever(mockImageProxy.height).thenReturn(480)
        whenever(mockImageProxy.planes).thenReturn(arrayOf())
        whenever(mockOcrEngine.recognize(any())).thenReturn(ocrResult)

        val callbackResults = mutableListOf<OcrResult>()
        val callback: (OcrResult) -> Unit = { result -> callbackResults.add(result) }

        // Симулируем быструю последовательность анализов
        repeat(5) { index ->
            cameraAnalyzer.analyze(mockImageProxy, callback)
            // Имитируем короткую задержку между кадрами
            kotlinx.coroutines.delay(50)
        }

        advanceUntilIdle()

        assertTrue("Should handle rapid succession of analyses", callbackResults.size >= 1)
    }

    @Test
    fun `should manage resources properly during analysis`() = runTest {
        // Тестирование правильного управления ресурсами во время анализа
        val testBitmap = createTestBitmap()
        val ocrResult = createTestOcrResult()

        whenever(mockImageProxy.image).thenReturn(mockImage)
        whenever(mockImageProxy.width).thenReturn(640)
        whenever(mockImageProxy.height).thenReturn(480)
        whenever(mockImageProxy.planes).thenReturn(arrayOf())
        whenever(mockOcrEngine.recognize(any())).thenReturn(ocrResult)

        var capturedResult: OcrResult? = null
        val callback: (OcrResult) -> Unit = { result -> capturedResult = result }

        cameraAnalyzer.analyze(mockImageProxy, callback)

        advanceUntilIdle()

        // Проверяем, что изображение было закрыто (в реальной реализации)
        verify(mockImageProxy, times(1)).close()

        assertNotNull("Result should be captured", capturedResult)
    }

    @Test
    fun `should handle concurrent image analysis safely`() = runTest {
        // Тестирование безопасной обработки параллельного анализа изображений
        val testBitmap = createTestBitmap()
        val ocrResult = createTestOcrResult()

        whenever(mockImageProxy.image).thenReturn(mockImage)
        whenever(mockImageProxy.width).thenReturn(640)
        whenever(mockImageProxy.height).thenReturn(480)
        whenever(mockImageProxy.planes).thenReturn(arrayOf())
        whenever(mockOcrEngine.recognize(any())).thenReturn(ocrResult)

        val results = mutableListOf<OcrResult>()
        val lock = Object()

        val callback: (OcrResult) -> Unit = { result ->
            synchronized(lock) {
                results.add(result)
            }
        }

        // Запускаем несколько анализов одновременно
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        repeat(3) {
            val job = kotlinx.coroutines.launch {
                cameraAnalyzer.analyze(mockImageProxy, callback)
            }
            jobs.add(job)
        }

        jobs.forEach { it.join() }

        assertEquals("Should handle concurrent analyses", 3, results.size)
        results.forEach { assertNotNull("Each result should not be null", it) }
    }

    // Вспомогательные методы
    private fun createTestBitmap(): android.graphics.Bitmap {
        return android.graphics.Bitmap.createBitmap(640, 480, android.graphics.Bitmap.Config.ARGB_8888)
    }

    private fun createTestOcrResult(): OcrResult {
        return OcrResult(
            blocks = listOf(
                TextBlock(
                    text = "Test text from camera",
                    boundingBox = BoundingBox(100f, 100f, 300f, 150f),
                    lines = listOf(
                        TextLine(
                            text = "Test text from camera",
                            boundingBox = BoundingBox(100f, 100f, 300f, 150f),
                            words = listOf(
                                TextWord(
                                    text = "Test text from camera",
                                    boundingBox = BoundingBox(100f, 100f, 300f, 150f),
                                    confidence = 0.85f
                                )
                            ),
                            confidence = 0.85f
                        )
                    ),
                    confidence = 0.85f
                )
            ),
            fullText = "Test text from camera"
        )
    }

    private val mockImageProxy: ImageProxy = mock()
    private val mockImage: android.media.Image = mock()
}