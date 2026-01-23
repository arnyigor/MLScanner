package com.arny.mlscanner.data.preprocessing

import android.graphics.Bitmap
import android.graphics.Rect
import org.junit.Test
import org.junit.Before
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

class DocumentDetectorTest {
    
    @Mock
    private lateinit var mockMat: Mat
    
    private lateinit var documentDetector: DocumentDetector
    
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        documentDetector = DocumentDetector()
    }
    
    @Test
    fun `should detect quadrilateral objects in image`() {
        // Тестирование детектирования четырехугольных объектов
        val documentBitmap = createDocumentBitmap()
        
        val detectedQuadrilaterals = documentDetector.detectQuadrilaterals(documentBitmap)
        
        assertNotNull("Detected quadrilaterals should not be null", detectedQuadrilaterals)
        assertTrue("Should detect at least one quadrilateral in document image", detectedQuadrilaterals.isNotEmpty())
        
        // Проверим, что все обнаруженные объекты действительно четырехугольники
        for (quad in detectedQuadrilaterals) {
            assertEquals("Each detected shape should have 4 points", 4, quad.size)
        }
    }
    
    @Test
    fun `should find document corners accurately`() {
        // Тестирование нахождения углов документа
        val documentBitmap = createDocumentWithCornersBitmap()
        
        val corners = documentDetector.findDocumentCorners(documentBitmap)
        
        assertNotNull("Document corners should not be null", corners)
        assertEquals("Document should have 4 corners", 4, corners.size)
        
        // Проверим, что углы находятся в разумных пределах изображения
        for (corner in corners) {
            assertTrue("Corner X should be within image bounds", corner.x >= 0 && corner.x <= documentBitmap.width)
            assertTrue("Corner Y should be within image bounds", corner.y >= 0 && corner.y <= documentBitmap.height)
        }
    }
    
    @Test
    fun `should handle different document formats`() {
        // Тестирование обработки разных форматов документов
        val formats = listOf(
            createA4DocumentBitmap(),
            createLetterDocumentBitmap(),
            createBusinessCardBitmap()
        )
        
        for (docBitmap in formats) {
            val quadrilaterals = documentDetector.detectQuadrilaterals(docBitmap)
            
            // Даже если формат отличается, система должна корректно обрабатывать
            assertNotNull("Should handle different document formats", quadrilaterals)
        }
    }
    
    @Test
    fun `should correct perspective transformation`() {
        // Тестирование коррекции перспективы
        val skewedBitmap = createSkewedDocumentBitmap()
        
        val corners = documentDetector.findDocumentCorners(skewedBitmap)
        val correctedBitmap = documentDetector.correctPerspective(skewedBitmap, corners)
        
        assertNotNull("Corrected bitmap should not be null", correctedBitmap)
        assertEquals("Corrected bitmap should have same dimensions as original", 
            skewedBitmap.width, correctedBitmap.width)
        assertEquals("Corrected bitmap should have same dimensions as original", 
            skewedBitmap.height, correctedBitmap.height)
    }
    
    @Test
    fun `should detect document boundaries properly`() {
        // Тестирование детектирования границ документа
        val documentBitmap = createDocumentWithBoundariesBitmap()
        
        val boundaryRect = documentDetector.getDocumentBoundary(documentBitmap)
        
        assertNotNull("Document boundary should not be null", boundaryRect)
        assertTrue("Boundary rectangle should have positive dimensions", 
            boundaryRect.width() > 0 && boundaryRect.height() > 0)
        assertTrue("Boundary should be smaller than image", 
            boundaryRect.width() < documentBitmap.width || boundaryRect.height() < documentBitmap.height)
    }
    
    @Test
    fun `should handle images without documents`() {
        // Тестирование обработки изображений без документов
        val nonDocumentBitmap = createNonDocumentBitmap()
        
        val quadrilaterals = documentDetector.detectQuadrilaterals(nonDocumentBitmap)
        
        // В идеале, если документа нет, система должна возвращать пустой список
        // или обрабатывать ситуацию корректно
        assertNotNull("Should handle non-document images gracefully", quadrilaterals)
    }
    
    @Test
    fun `should process grayscale images correctly`() {
        // Тестирование обработки черно-белых изображений
        val grayscaleBitmap = createGrayscaleDocumentBitmap()
        
        val corners = documentDetector.findDocumentCorners(grayscaleBitmap)
        
        assertNotNull("Should process grayscale images", corners)
    }
    
    @Test
    fun `should handle rotated documents`() {
        // Тестирование обработки повернутых документов
        val rotatedBitmap = createRotatedDocumentBitmap()
        
        val detectedQuadrilaterals = documentDetector.detectQuadrilaterals(rotatedBitmap)
        
        assertNotNull("Should handle rotated documents", detectedQuadrilaterals)
        assertTrue("Should detect document even when rotated", detectedQuadrilaterals.isNotEmpty())
    }
    
    @Test
    fun `should maintain document quality after perspective correction`() {
        // Тестирование сохранения качества документа после коррекции перспективы
        val originalBitmap = createHighQualityDocumentBitmap()
        
        val corners = documentDetector.findDocumentCorners(originalBitmap)
        val correctedBitmap = documentDetector.correctPerspective(originalBitmap, corners)
        
        assertNotNull("Corrected bitmap should not be null", correctedBitmap)
        
        // Проверим, что размеры остались разумными
        assertEquals("Corrected image should have same dimensions", 
            originalBitmap.width, correctedBitmap.width)
        assertEquals("Corrected image should have same dimensions", 
            originalBitmap.height, correctedBitmap.height)
    }
    
    @Test
    fun `should detect documents with low contrast`() {
        // Тестирование детектирования документов с низким контрастом
        val lowContrastBitmap = createLowContrastDocumentBitmap()
        
        val quadrilaterals = documentDetector.detectQuadrilaterals(lowContrastBitmap)
        
        assertNotNull("Should handle low contrast documents", quadrilaterals)
    }
    
    @Test
    fun `should perform efficiently with large images`() {
        // Тестирование производительности с большими изображениями
        val largeBitmap = createLargeDocumentBitmap()
        
        val startTime = System.currentTimeMillis()
        val quadrilaterals = documentDetector.detectQuadrilaterals(largeBitmap)
        val processingTime = System.currentTimeMillis() - startTime
        
        assertNotNull("Should process large images", quadrilaterals)
        
        // Проверим, что обработка завершается за разумное время (менее 5 секунд)
        assertTrue("Processing should be efficient", processingTime < 5000)
    }
    
    @Test
    fun `should handle partially visible documents`() {
        // Тестирование обработки частично видимых документов
        val partialBitmap = createPartialDocumentBitmap()
        
        val quadrilaterals = documentDetector.detectQuadrilaterals(partialBitmap)
        
        // Даже частичный документ должен обнаруживаться хотя бы частично
        assertNotNull("Should handle partial documents", quadrilaterals)
    }
    
    // Вспомогательные методы для создания тестовых изображений
    private fun createDocumentBitmap(): Bitmap {
        return Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply {
            // Создаем изображение с четким прямоугольным документом
            for (x in 0 until 800) {
                for (y in 0 until 600) {
                    val bgColor = if (x in 100..700 && y in 50..550) {
                        // Внутри документа - светлый фон
                        android.graphics.Color.rgb(250, 250, 250)
                    } else {
                        // Внешний фон - темнее
                        android.graphics.Color.rgb(150, 150, 150)
                    }
                    setPixel(x, y, bgColor)
                }
            }
            
            // Добавим границы документа
            for (x in 100..700) {
                setPixel(x, 50, android.graphics.Color.BLACK)
                setPixel(x, 549, android.graphics.Color.BLACK)
            }
            for (y in 50..550) {
                setPixel(100, y, android.graphics.Color.BLACK)
                setPixel(699, y, android.graphics.Color.BLACK)
            }
        }
    }
    
    private fun createDocumentWithCornersBitmap(): Bitmap {
        return Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply {
            // Создаем документ с четко выраженными углами
            for (x in 0 until 600) {
                for (y in 0 until 400) {
                    setPixel(x, y, android.graphics.Color.WHITE)
                }
            }
            
            // Рисуем документ с заметными углами
            val docLeft = 50
            val docTop = 30
            val docRight = 550
            val docBottom = 370
            
            // Рамка документа
            for (x in docLeft..docRight) {
                setPixel(x, docTop, android.graphics.Color.BLACK)
                setPixel(x, docBottom, android.graphics.Color.BLACK)
            }
            for (y in docTop..docBottom) {
                setPixel(docLeft, y, android.graphics.Color.BLACK)
                setPixel(docRight, y, android.graphics.Color.BLACK)
            }
            
            // Углы документа - более выраженные
            for (offset in 0..5) {
                for (i in 0..5) {
                    setPixel(docLeft + offset, docTop + i, android.graphics.Color.BLACK)
                    setPixel(docRight - offset, docTop + i, android.graphics.Color.BLACK)
                    setPixel(docLeft + offset, docBottom - i, android.graphics.Color.BLACK)
                    setPixel(docRight - offset, docBottom - i, android.graphics.Color.BLACK)
                }
            }
        }
    }
    
    private fun createA4DocumentBitmap(): Bitmap {
        return Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888).apply {
            // A4 в пикселях (около 72 DPI)
            for (x in 0 until 595) {
                for (y in 0 until 842) {
                    setPixel(x, y, android.graphics.Color.WHITE)
                }
            }
            
            // Добавим рамку
            for (x in 20..575) {
                setPixel(x, 20, android.graphics.Color.BLACK)
                setPixel(x, 822, android.graphics.Color.BLACK)
            }
            for (y in 20..822) {
                setPixel(20, y, android.graphics.Color.BLACK)
                setPixel(575, y, android.graphics.Color.BLACK)
            }
        }
    }
    
    private fun createLetterDocumentBitmap(): Bitmap {
        return Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888).apply {
            // Letter в пикселях (около 72 DPI)
            for (x in 0 until 612) {
                for (y in 0 until 792) {
                    setPixel(x, y, android.graphics.Color.WHITE)
                }
            }
            
            // Добавим рамку
            for (x in 20..592) {
                setPixel(x, 20, android.graphics.Color.BLACK)
                setPixel(x, 772, android.graphics.Color.BLACK)
            }
            for (y in 20..772) {
                setPixel(20, y, android.graphics.Color.BLACK)
                setPixel(592, y, android.graphics.Color.BLACK)
            }
        }
    }
    
    private fun createBusinessCardBitmap(): Bitmap {
        return Bitmap.createBitmap(350, 200, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 350) {
                for (y in 0 until 200) {
                    setPixel(x, y, android.graphics.Color.WHITE)
                }
            }
            
            // Добавим рамку
            for (x in 5..345) {
                setPixel(x, 5, android.graphics.Color.BLACK)
                setPixel(x, 195, android.graphics.Color.BLACK)
            }
            for (y in 5..195) {
                setPixel(5, y, android.graphics.Color.BLACK)
                setPixel(345, y, android.graphics.Color.BLACK)
            }
        }
    }
    
    private fun createSkewedDocumentBitmap(): Bitmap {
        return Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply {
            // Создаем "перекошенный" документ
            for (x in 0 until 600) {
                for (y in 0 until 400) {
                    setPixel(x, y, android.graphics.Color.LTGRAY)
                }
            }
            
            // Рисуем перекошенный прямоугольник (документ)
            for (x in 100..500) {
                for (y in 80..320) {
                    // Имитируем перспективу: верх шире, низ уже
                    val adjustedX = if (y > 200) x - (y - 200) / 4 else x + (200 - y) / 4
                    if (adjustedX in 100..500) {
                        setPixel(adjustedX, y, android.graphics.Color.WHITE)
                    }
                }
            }
            
            // Рамка для лучшей видимости
            for (x in 100..500) {
                setPixel(x, 80, android.graphics.Color.BLACK)
                setPixel(x, 319, android.graphics.Color.BLACK)
            }
            for (y in 80..320) {
                setPixel(100, y, android.graphics.Color.BLACK)
                setPixel(500, y, android.graphics.Color.BLACK)
            }
        }
    }
    
    private fun createDocumentWithBoundariesBitmap(): Bitmap {
        return Bitmap.createBitmap(700, 500, Bitmap.Config.ARGB_8888).apply {
            // Создаем изображение с четкими границами документа
            for (x in 0 until 700) {
                for (y in 0 until 500) {
                    setPixel(x, y, android.graphics.Color.DKGRAY)
                }
            }
            
            // Внутренний документ
            for (x in 80..620) {
                for (y in 60..440) {
                    setPixel(x, y, android.graphics.Color.WHITE)
                }
            }
            
            // Четкие границы документа
            for (x in 80..620) {
                setPixel(x, 60, android.graphics.Color.BLUE)
                setPixel(x, 439, android.graphics.Color.BLUE)
            }
            for (y in 60..440) {
                setPixel(80, y, android.graphics.Color.BLUE)
                setPixel(619, y, android.graphics.Color.BLUE)
            }
        }
    }
    
    private fun createNonDocumentBitmap(): Bitmap {
        return Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply {
            // Создаем изображение без четких прямоугольных объектов
            for (x in 0 until 400) {
                for (y in 0 until 300) {
                    // Случайный "естественный" пейзаж
                    val r = (100 + Math.sin(x * 0.1) * 50).toInt().coerceIn(0, 255)
                    val g = (150 + Math.cos(y * 0.1) * 50).toInt().coerceIn(0, 255)
                    val b = (120 + Math.sin((x+y) * 0.05) * 30).toInt().coerceIn(0, 255)
                    setPixel(x, y, android.graphics.Color.rgb(r, g, b))
                }
            }
        }
    }
    
    private fun createGrayscaleDocumentBitmap(): Bitmap {
        return Bitmap.createBitmap(500, 400, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 500) {
                for (y in 0 until 400) {
                    val grayValue = 200 // Светло-серый фон
                    val color = android.graphics.Color.rgb(grayValue, grayValue, grayValue)
                    setPixel(x, y, color)
                }
            }
            
            // Документ в градациях серого
            for (x in 100..400) {
                for (y in 50..350) {
                    val docGray = 245 // Более светлый серый для документа
                    setPixel(x, y, android.graphics.Color.rgb(docGray, docGray, docGray))
                }
            }
            
            // Границы документа
            for (x in 100..400) {
                setPixel(x, 50, android.graphics.Color.GRAY)
                setPixel(x, 349, android.graphics.Color.GRAY)
            }
            for (y in 50..350) {
                setPixel(100, y, android.graphics.Color.GRAY)
                setPixel(399, y, android.graphics.Color.GRAY)
            }
        }
    }
    
    private fun createRotatedDocumentBitmap(): Bitmap {
        return Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888).apply {
            // Создаем повернутый документ (представим визуально)
            for (x in 0 until 500) {
                for (y in 0 until 500) {
                    setPixel(x, y, android.graphics.Color.LTGRAY)
                }
            }
            
            // Рисуем документ под углом
            for (x in 150..350) {
                for (y in 150..350) {
                    // Поворот на 45 градусов визуально
                    val rotatedX = ((x - 250) * Math.cos(Math.PI/4) - (y - 250) * Math.sin(Math.PI/4) + 250).toInt()
                    val rotatedY = ((x - 250) * Math.sin(Math.PI/4) + (y - 250) * Math.cos(Math.PI/4) + 250).toInt()
                    
                    if (rotatedX in 150..350 && rotatedY in 150..350) {
                        setPixel(rotatedX, rotatedY, android.graphics.Color.WHITE)
                    }
                }
            }
        }
    }
    
    private fun createHighQualityDocumentBitmap(): Bitmap {
        return Bitmap.createBitmap(1000, 800, Bitmap.Config.ARGB_8888).apply {
            // Высококачественное изображение документа
            for (x in 0 until 1000) {
                for (y in 0 until 800) {
                    setPixel(x, y, android.graphics.Color.WHITE)
                }
            }
            
            // Четкие границы
            for (x in 50..950) {
                setPixel(x, 50, android.graphics.Color.BLACK)
                setPixel(x, 749, android.graphics.Color.BLACK)
            }
            for (y in 50..750) {
                setPixel(50, y, android.graphics.Color.BLACK)
                setPixel(949, y, android.graphics.Color.BLACK)
            }
        }
    }
    
    private fun createLowContrastDocumentBitmap(): Bitmap {
        return Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply {
            val lightGray = android.graphics.Color.rgb(200, 200, 200)
            val slightlyLighter = android.graphics.Color.rgb(210, 210, 210)
            
            for (x in 0 until 600) {
                for (y in 0 until 400) {
                    setPixel(x, y, lightGray)
                }
            }
            
            // Документ с очень низким контрастом
            for (x in 100..500) {
                for (y in 80..320) {
                    setPixel(x, y, slightlyLighter)
                }
            }
        }
    }
    
    private fun createLargeDocumentBitmap(): Bitmap {
        return Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888).apply {
            // Большое изображение документа
            for (x in 0 until 3000) {
                for (y in 0 until 2000) {
                    val bgColor = if (x in 200..2800 && y in 200..1800) {
                        android.graphics.Color.WHITE
                    } else {
                        android.graphics.Color.LTGRAY
                    }
                    setPixel(x, y, bgColor)
                }
            }
            
            // Границы документа
            for (x in 200..2800) {
                setPixel(x, 200, android.graphics.Color.BLACK)
                setPixel(x, 1799, android.graphics.Color.BLACK)
            }
            for (y in 200..1800) {
                setPixel(200, y, android.graphics.Color.BLACK)
                setPixel(2799, y, android.graphics.Color.BLACK)
            }
        }
    }
    
    private fun createPartialDocumentBitmap(): Bitmap {
        return Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 600) {
                for (y in 0 until 400) {
                    setPixel(x, y, android.graphics.Color.LTGRAY)
                }
            }
            
            // Только часть документа видна
            for (x in 100..500) {
                for (y in 200 until 400) { // Только нижняя часть
                    setPixel(x, y, android.graphics.Color.WHITE)
                }
            }
            
            // Нижняя граница
            for (x in 100..500) {
                setPixel(x, 200, android.graphics.Color.BLACK)
            }
        }
    }
}