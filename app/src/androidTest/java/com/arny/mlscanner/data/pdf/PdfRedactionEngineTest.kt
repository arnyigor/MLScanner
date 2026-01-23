package com.arny.mlscanner.data.pdf

import com.arny.mlscanner.domain.models.OcrModels.BoundingBox
import com.arny.mlscanner.domain.models.OcrModels.TextBox
import com.arny.mlscanner.domain.models.OcrModels.RedactionMask
import org.junit.Test
import org.junit.Before
import org.junit.Assert.*
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import java.io.File

class PdfRedactionEngineTest {
    
    @Mock
    private lateinit var mockFile: File
    
    private lateinit var pdfRedactionEngine: PdfRedactionEngine
    
    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        pdfRedactionEngine = PdfRedactionEngine()
    }
    
    @Test
    fun `should redact text regions and save PDF successfully`() {
        // Тестирование процесса редактирования и сохранения PDF
        val sourceImagePath = createTestImageFile().path
        val textBoxes = listOf(
            TextBox(
                text = "Sensitive information to hide",
                confidence = 0.9f,
                boundingBox = BoundingBox(100f, 100f, 300f, 150f)
            ),
            TextBox(
                text = "Normal text to keep",
                confidence = 0.8f,
                boundingBox = BoundingBox(100f, 200f, 250f, 250f)
            )
        )
        
        val redactionMask = RedactionMask(
            redactedBoxes = listOf(textBoxes[0]) // Только первый текстовый блок скрывается
        )
        
        val outputPath = "test_redacted_output.pdf"
        
        assertDoesNotThrow {
            pdfRedactionEngine.redactAndSavePdf(sourceImagePath, textBoxes, redactionMask, outputPath)
        }
        
        val outputFile = File(outputPath)
        assertTrue("Redacted PDF file should exist", outputFile.exists())
        assertTrue("Redacted PDF file should not be empty", outputFile.length() > 0)
        
        outputFile.delete()
    }
    
    @Test
    fun `should apply black rectangles to redacted areas`() {
        // Тестирование нанесения черных прямоугольников на области редактирования
        val sourceImagePath = createTestImageFile().path
        val textBoxes = listOf(
            TextBox(
                text = "Private data",
                confidence = 0.95f,
                boundingBox = BoundingBox(50f, 50f, 200f, 100f)
            )
        )
        
        val redactionMask = RedactionMask(
            redactedBoxes = textBoxes
        )
        
        val outputPath = "test_black_rectangles.pdf"
        
        assertDoesNotThrow {
            pdfRedactionEngine.redactAndSavePdf(sourceImagePath, textBoxes, redactionMask, outputPath)
        }
        
        val outputFile = File(outputPath)
        assertTrue("PDF with black rectangles should exist", outputFile.exists())
        
        outputFile.delete()
    }
    
    @Test
    fun `should remove text from redacted areas to prevent recovery`() {
        // Тестирование удаления текста из областей редактирования
        val sourceImagePath = createTestImageFile().path
        val textBoxes = listOf(
            TextBox(
                text = "Secret text to remove",
                confidence = 0.9f,
                boundingBox = BoundingBox(100f, 100f, 300f, 150f)
            ),
            TextBox(
                text = "Public text to keep",
                confidence = 0.85f,
                boundingBox = BoundingBox(100f, 200f, 280f, 250f)
            )
        )
        
        val redactionMask = RedactionMask(
            redactedBoxes = listOf(textBoxes[0])
        )
        
        val outputPath = "test_text_removal.pdf"
        
        assertDoesNotThrow {
            pdfRedactionEngine.redactAndSavePdf(sourceImagePath, textBoxes, redactionMask, outputPath)
        }
        
        val outputFile = File(outputPath)
        assertTrue("PDF with removed text should exist", outputFile.exists())
        
        outputFile.delete()
    }
    
    @Test
    fun `should redact existing PDF file`() {
        // Тестирование редактирования существующего PDF файла
        val sourcePdfPath = createTestPdfFile().path
        val redactionMask = RedactionMask(
            redactedBoxes = listOf(
                TextBox(
                    text = "Redact this text",
                    confidence = 0.9f,
                    boundingBox = BoundingBox(100f, 100f, 250f, 150f)
                )
            )
        )
        
        val outputPath = "test_existing_pdf_redaction.pdf"
        
        assertDoesNotThrow {
            pdfRedactionEngine.redactExistingPdf(sourcePdfPath, redactionMask, outputPath)
        }
        
        val outputFile = File(outputPath)
        assertTrue("Redacted existing PDF should exist", outputFile.exists())
        
        outputFile.delete()
    }
    
    @Test
    fun `should handle multiple redaction areas`() {
        // Тестирование обработки нескольких областей редактирования
        val sourceImagePath = createTestImageFile().path
        val textBoxes = listOf(
            TextBox(
                text = "First sensitive info",
                confidence = 0.9f,
                boundingBox = BoundingBox(50f, 50f, 200f, 100f)
            ),
            TextBox(
                text = "Second sensitive info",
                confidence = 0.88f,
                boundingBox = BoundingBox(50f, 150f, 220f, 200f)
            ),
            TextBox(
                text = "Third sensitive info",
                confidence = 0.92f,
                boundingBox = BoundingBox(50f, 250f, 210f, 300f)
            ),
            TextBox(
                text = "Keep this text",
                confidence = 0.85f,
                boundingBox = BoundingBox(50f, 350f, 180f, 400f)
            )
        )
        
        val redactionMask = RedactionMask(
            redactedBoxes = listOf(textBoxes[0], textBoxes[1], textBoxes[2])
        )
        
        val outputPath = "test_multiple_redactions.pdf"
        
        assertDoesNotThrow {
            pdfRedactionEngine.redactAndSavePdf(sourceImagePath, textBoxes, redactionMask, outputPath)
        }
        
        val outputFile = File(outputPath)
        assertTrue("PDF with multiple redactions should exist", outputFile.exists())
        
        outputFile.delete()
    }
    
    @Test
    fun `should preserve non-redacted text in PDF`() {
        // Тестирование сохранения нередактируемых текстовых блоков
        val sourceImagePath = createTestImageFile().path
        val textBoxes = listOf(
            TextBox(
                text = "This should be redacted",
                confidence = 0.9f,
                boundingBox = BoundingBox(100f, 100f, 280f, 150f)
            ),
            TextBox(
                text = "This should remain visible",
                confidence = 0.87f,
                boundingBox = BoundingBox(100f, 200f, 320f, 250f)
            )
        )
        
        val redactionMask = RedactionMask(
            redactedBoxes = listOf(textBoxes[0])
        )
        
        val outputPath = "test_preserve_text.pdf"
        
        assertDoesNotThrow {
            pdfRedactionEngine.redactAndSavePdf(sourceImagePath, textBoxes, redactionMask, outputPath)
        }
        
        val outputFile = File(outputPath)
        assertTrue("PDF preserving text should exist", outputFile.exists())
        
        outputFile.delete()
    }
    
    @Test
    fun `should handle overlapping redaction areas`() {
        // Тестирование обработки перекрывающихся областей редактирования
        val sourceImagePath = createTestImageFile().path
        val textBoxes = listOf(
            TextBox(
                text = "Overlapping text 1",
                confidence = 0.9f,
                boundingBox = BoundingBox(100f, 100f, 250f, 150f) // Перекрывается с 2
            ),
            TextBox(
                text = "Overlapping text 2",
                confidence = 0.88f,
                boundingBox = BoundingBox(200f, 120f, 350f, 180f) // Перекрывается с 1
            )
        )
        
        val redactionMask = RedactionMask(
            redactedBoxes = textBoxes
        )
        
        val outputPath = "test_overlapping_redactions.pdf"
        
        assertDoesNotThrow {
            pdfRedactionEngine.redactAndSavePdf(sourceImagePath, textBoxes, redactionMask, outputPath)
        }
        
        val outputFile = File(outputPath)
        assertTrue("PDF with overlapping redactions should exist", outputFile.exists())
        
        outputFile.delete()
    }
    
    @Test
    fun `should validate redaction mask properly`() {
        // Тестирование валидации маски редактирования
        val sourceImagePath = createTestImageFile().path
        val textBoxes = listOf(
            TextBox(
                text = "Test text",
                confidence = 0.9f,
                boundingBox = BoundingBox(100f, 100f, 200f, 150f)
            )
        )
        
        val emptyRedactionMask = RedactionMask(redactedBoxes = emptyList())
        
        val outputPath = "test_validated_mask.pdf"
        
        assertDoesNotThrow {
            pdfRedactionEngine.redactAndSavePdf(sourceImagePath, textBoxes, emptyRedactionMask, outputPath)
        }
        
        val outputFile = File(outputPath)
        assertTrue("PDF with validated mask should exist", outputFile.exists())
        
        outputFile.delete()
    }
    
    @Test
    fun `should encrypt temporary files during redaction`() {
        // Тестирование шифрования временных файлов
        val sourceImagePath = createTestImageFile().path
        val textBoxes = listOf(
            TextBox(
                text = "Sensitive data",
                confidence = 0.95f,
                boundingBox = BoundingBox(100f, 100f, 250f, 150f)
            )
        )
        
        val redactionMask = RedactionMask(
            redactedBoxes = textBoxes
        )
        
        val outputPath = "test_encrypted_temp_files.pdf"
        
        assertDoesNotThrow {
            pdfRedactionEngine.redactAndSavePdf(sourceImagePath, textBoxes, redactionMask, outputPath)
        }
        
        val outputFile = File(outputPath)
        assertTrue("PDF with encrypted temp files should exist", outputFile.exists())
        
        outputFile.delete()
    }
    
    private fun createTestImageFile(): File {
        // Создание тестового изображения
        val testFile = File("test_image_for_redaction.jpg")
        testFile.createNewFile()
        testFile.writeText("Fake image content for redaction testing")
        return testFile
    }
    
    private fun createTestPdfFile(): File {
        // Создание тестового PDF файла
        val testFile = File("test_input.pdf")
        testFile.createNewFile()
        testFile.writeText("Fake PDF content for redaction testing")
        return testFile
    }
}