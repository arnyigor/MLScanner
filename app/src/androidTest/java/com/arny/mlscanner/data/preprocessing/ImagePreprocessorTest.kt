package com.arny.mlscanner.data.preprocessing

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Test
import org.junit.Before
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations

class ImagePreprocessorTest {
    
    @Mock
    private lateinit var mockDocumentDetector: DocumentDetector
    
    private lateinit var imagePreprocessor: ImagePreprocessor
    
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        imagePreprocessor = ImagePreprocessor(mockDocumentDetector)
    }
    
    @Test
    fun `should denoise image properly`() {
        // Тестирование шумоподавления
        val noisyBitmap = createNoisyTestBitmap()
        
        val denoisedBitmap = imagePreprocessor.denoise(noisyBitmap)
        
        assertNotNull("Denoised bitmap should not be null", denoisedBitmap)
        assertEquals("Denoised bitmap should have same dimensions", 
            noisyBitmap.width, denoisedBitmap.width)
        assertEquals("Denoised bitmap should have same dimensions", 
            noisyBitmap.height, denoisedBitmap.height)
    }
    
    @Test
    fun `should adjust brightness correctly`() {
        // Тестирование коррекции яркости
        val darkBitmap = createDarkTestBitmap()
        
        val brightenedBitmap = imagePreprocessor.adjustBrightness(darkBitmap, 50f)
        
        assertNotNull("Brightened bitmap should not be null", brightenedBitmap)
        
        // Проверим, что яркость действительно изменилась
        val originalPixel = darkBitmap.getPixel(darkBitmap.width/2, darkBitmap.height/2)
        val adjustedPixel = brightenedBitmap.getPixel(brightenedBitmap.width/2, brightenedBitmap.height/2)
        
        val originalBrightness = calculateBrightness(originalPixel)
        val adjustedBrightness = calculateBrightness(adjustedPixel)
        
        assertTrue("Adjusted image should be brighter", adjustedBrightness > originalBrightness)
    }
    
    @Test
    fun `should enhance contrast properly`() {
        // Тестирование повышения контрастности
        val lowContrastBitmap = createLowContrastTestBitmap()
        
        val enhancedBitmap = imagePreprocessor.enhanceContrast(lowContrastBitmap)
        
        assertNotNull("Enhanced bitmap should not be null", enhancedBitmap)
    }
    
    @Test
    fun `should sharpen image correctly`() {
        // Тестирование повышения резкости
        val blurryBitmap = createBlurryTestBitmap()
        
        val sharpenedBitmap = imagePreprocessor.sharpen(blurryBitmap)
        
        assertNotNull("Sharpened bitmap should not be null", sharpenedBitmap)
    }
    
    @Test
    fun `should binarize image with adaptive threshold`() {
        // Тестирование бинаризации с адаптивным порогом
        val grayBitmap = createGrayTestBitmap()
        
        val binaryBitmap = imagePreprocessor.binarize(grayBitmap)
        
        assertNotNull("Binary bitmap should not be null", binaryBitmap)
        
        // Проверим, что бинаризованный образ состоит только из черного и белого
        for (x in 0 until binaryBitmap.width step maxOf(1, binaryBitmap.width/10)) {
            for (y in 0 until binaryBitmap.height step maxOf(1, binaryBitmap.height/10)) {
                val pixel = binaryBitmap.getPixel(x, y)
                val red = Color.red(pixel)
                val green = Color.green(pixel)
                val blue = Color.blue(pixel)
                
                // После бинаризации цвета должны быть либо полностью черными, либо белыми
                assertTrue("Pixel should be either black or white after binarization",
                    (red == 0 && green == 0 && blue == 0) || (red == 255 && green == 255 && blue == 255))
            }
        }
    }
    
    @Test
    fun `should deskew image properly`() {
        // Тестирование автоповорота (коррекции перспективы)
        val skewedBitmap = createSkewedTestBitmap()
        
        val correctedBitmap = imagePreprocessor.deskew(skewedBitmap)
        
        assertNotNull("Corrected bitmap should not be null", correctedBitmap)
        assertEquals("Corrected bitmap should have same dimensions", 
            skewedBitmap.width, correctedBitmap.width)
        assertEquals("Corrected bitmap should have same dimensions", 
            skewedBitmap.height, correctedBitmap.height)
    }
    
    @Test
    fun `should apply document detection workflow`() {
        // Тестирование полного рабочего процесса предварительной обработки
        val originalBitmap = createTestDocumentBitmap()
        
        val processedBitmap = imagePreprocessor.preprocessForOcr(originalBitmap)
        
        assertNotNull("Processed bitmap should not be null", processedBitmap)
        assertEquals("Processed bitmap should have same dimensions", 
            originalBitmap.width, processedBitmap.width)
        assertEquals("Processed bitmap should have same dimensions", 
            originalBitmap.height, processedBitmap.height)
    }
    
    @Test
    fun `should handle very small images`() {
        // Тестирование обработки очень маленьких изображений
        val tinyBitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        
        val result = imagePreprocessor.preprocessForOcr(tinyBitmap)
        
        assertNotNull("Should handle tiny images", result)
        assertEquals(10, result.width)
        assertEquals(10, result.height)
    }
    
    @Test
    fun `should handle very large images`() {
        // Тестирование обработки очень больших изображений
        val largeBitmap = Bitmap.createBitmap(4000, 4000, Bitmap.Config.ARGB_8888)
        
        val result = imagePreprocessor.preprocessForOcr(largeBitmap)
        
        assertNotNull("Should handle large images", result)
        assertEquals(4000, result.width)
        assertEquals(4000, result.height)
    }
    
    @Test
    fun `should maintain aspect ratio during preprocessing`() {
        // Тестирование сохранения соотношения сторон
        val rectangularBitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        
        val processedBitmap = imagePreprocessor.preprocessForOcr(rectangularBitmap)
        
        assertNotNull("Processed bitmap should not be null", processedBitmap)
        
        // Проверим, что соотношение сторон сохранено (или очень близко к оригиналу)
        val originalRatio = rectangularBitmap.width.toFloat() / rectangularBitmap.height.toFloat()
        val processedRatio = processedBitmap.width.toFloat() / processedBitmap.height.toFloat()
        
        assertEquals("Aspect ratio should be maintained", originalRatio, processedRatio, 0.01f)
    }
    
    @Test
    fun `should handle completely black or white images`() {
        // Тестирование обработки полностью черных или белых изображений
        val blackColor = Color.rgb(0, 0, 0)
        val whiteColor = Color.rgb(255, 255, 255)
        
        val blackBitmap = createSolidColorBitmap(100, 100, blackColor)
        val whiteBitmap = createSolidColorBitmap(100, 100, whiteColor)
        
        val processedBlack = imagePreprocessor.preprocessForOcr(blackBitmap)
        val processedWhite = imagePreprocessor.preprocessForOcr(whiteBitmap)
        
        assertNotNull("Should handle black image", processedBlack)
        assertNotNull("Should handle white image", processedWhite)
    }
    
    @Test
    fun `should preserve text readability after preprocessing`() {
        // Тестирование сохранения читаемости текста после предварительной обработки
        val textBitmap = createTextBitmap()
        
        val processedBitmap = imagePreprocessor.preprocessForOcr(textBitmap)
        
        assertNotNull("Processed bitmap should not be null", processedBitmap)
        
        // Хотя мы не можем проверить читаемость текста напрямую в unit-тесте,
        // мы можем проверить, что изображение не было искажено критически
        assertTrue("Processed image should have valid dimensions", 
            processedBitmap.width > 0 && processedBitmap.height > 0)
    }
    
    // Вспомогательные методы для создания тестовых изображений
    private fun createNoisyTestBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        
        // Создаем немного зашумленное изображение
        for (x in 0 until 100) {
            for (y in 0 until 100) {
                val noise = (Math.random() * 50).toInt()
                val color = Color.rgb(100 + noise, 120 + noise, 140 + noise)
                bitmap.setPixel(x, y, color)
            }
        }
        
        return bitmap
    }
    
    private fun createDarkTestBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        
        // Создаем темное изображение
        for (x in 0 until 100) {
            for (y in 0 until 100) {
                val color = Color.rgb(20, 30, 40)
                bitmap.setPixel(x, y, color)
            }
        }
        
        return bitmap
    }
    
    private fun createLowContrastTestBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        
        // Создаем изображение с низким контрастом
        for (x in 0 until 100) {
            for (y in 0 until 100) {
                val grayValue = 120 + (Math.random() * 20).toInt() // Диапазон 120-140
                val color = Color.rgb(grayValue, grayValue, grayValue)
                bitmap.setPixel(x, y, color)
            }
        }
        
        return bitmap
    }
    
    private fun createBlurryTestBitmap(): Bitmap {
        return Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            // Имитируем размытое изображение
            for (x in 0 until 100) {
                for (y in 0 until 100) {
                    val color = Color.rgb(100, 100, 100)
                    setPixel(x, y, color)
                }
            }
        }
    }
    
    private fun createGrayTestBitmap(): Bitmap {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        
        // Создаем серое изображение с различными оттенками
        for (x in 0 until 100) {
            for (y in 0 until 100) {
                val grayValue = (x * 255 / 100) // Градиент от черного к белому
                val color = Color.rgb(grayValue, grayValue, grayValue)
                bitmap.setPixel(x, y, color)
            }
        }
        
        return bitmap
    }
    
    private fun createSkewedTestBitmap(): Bitmap {
        return Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply {
            // Создаем изображение, которое нужно "повернуть"
            for (x in 0 until 100) {
                for (y in 0 until 100) {
                    val color = if ((x + y) % 20 < 10) Color.BLACK else Color.WHITE
                    setPixel(x, y, color)
                }
            }
        }
    }
    
    private fun createTestDocumentBitmap(): Bitmap {
        return Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply {
            // Создаем имитацию документа с текстом
            for (x in 0 until 800) {
                for (y in 0 until 600) {
                    // Фон документа
                    val backgroundColor = Color.rgb(240, 240, 240)
                    
                    // Добавим несколько "страниц" текста
                    var finalColor = backgroundColor
                    if (y in 50..80 || y in 120..150 || y in 200..230 || y in 300..330) {
                        // Горизонтальные линии текста
                        if (x % 20 < 15) {
                            finalColor = Color.BLACK
                        }
                    }
                    
                    setPixel(x, y, finalColor)
                }
            }
        }
    }
    
    private fun createSolidColorBitmap(width: Int, height: Int, color: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until width) {
                for (y in 0 until height) {
                    setPixel(x, y, color)
                }
            }
        }
    }
    
    private fun createTextBitmap(): Bitmap {
        return Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            // Создаем изображение с "текстом" для проверки читаемости
            for (x in 0 until 200) {
                for (y in 0 until 100) {
                    val bgColor = Color.WHITE
                    
                    // Создаем несколько "букв"
                    var textColor = bgColor
                    if ((x in 20..40 && y in 20..40) || 
                        (x in 60..80 && y in 20..60) ||
                        (x in 100..120 && y in 30..50)) {
                        textColor = Color.BLACK
                    }
                    
                    setPixel(x, y, textColor)
                }
            }
        }
    }
    
    private fun calculateBrightness(color: Int): Float {
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        
        // Используем стандартную формулу для яркости
        return 0.299f * r + 0.587f * g + 0.114f * b
    }
}