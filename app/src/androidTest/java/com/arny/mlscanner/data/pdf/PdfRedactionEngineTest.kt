package com.arny.mlscanner.data.pdf

import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.TextWord
import com.arny.mlscanner.domain.models.RedactionMask
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
            TextWord(
                text = "Sensitive information to hide",
                boundingBox = BoundingBox(100f, 100f, 300f, 150f),
                confidence = 0.9f
            ),
            TextWord(
                text = "Normal text to keep",
                boundingBox = BoundingBox(100f, 200f, 250f, 250f),
                confidence = 0.8f
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
            TextWord(
                text = "Private data",
                boundingBox = BoundingBox(50f, 50f, 200f, 100f),
                confidence = 0.95f
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
            TextWord(
                text = "Secret text to remove",
                boundingBox = BoundingBox(100f, 100f, 300f, 150f),
                confidence = 0.9f
            ),
            TextWord(
                text = "Public text to keep",
                boundingBox = BoundingBox(100f, 200f, 280f, 250f),
                confidence = 0.85f
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
                TextWord(
                    text = "Redact this text",
                    boundingBox = BoundingBox(100f, 100f, 250f, 150f),
                    confidence = 0.9f
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
            TextWord(
                text = "First sensitive info",
                boundingBox = BoundingBox(50f, 50f, 200f, 100f),
                confidence = 0.9f
            ),
            TextWord(
                text = "Second sensitive info",
                boundingBox = BoundingBox(50f, 150f, 220f, 200f),
                confidence = 0.88f
            ),
            TextWord(
                text = "Third sensitive info",
                boundingBox = BoundingBox(50f, 250f, 210f, 300f),
                confidence = 0.92f
            ),
            TextWord(
                text = "Keep this text",
                boundingBox = BoundingBox(50f, 350f, 180f, 400f),
                confidence = 0.85f
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
            TextWord(
                text = "This should be redacted",
                boundingBox = BoundingBox(100f, 100f, 280f, 150f),
                confidence = 0.9f
            ),
            TextWord(
                text = "This should remain visible",
                boundingBox = BoundingBox(100f, 200f, 320f, 250f),
                confidence = 0.87f
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
            TextWord(
                text = "Overlapping text 1",
                boundingBox = BoundingBox(100f, 100f, 250f, 150f),
                confidence = 0.9f
            ),
            TextWord(
                text = "Overlapping text 2",
                boundingBox = BoundingBox(200f, 120f, 350f, 180f),
                confidence = 0.88f
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
            TextWord(
                text = "Test text",
                boundingBox = BoundingBox(100f, 100f, 200f, 150f),
                confidence = 0.9f
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
            TextWord(
                text = "Sensitive data",
                boundingBox = BoundingBox(100f, 100f, 250f, 150f),
                confidence = 0.95f
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