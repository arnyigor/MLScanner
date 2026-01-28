package com.arny.mlscanner.data.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import com.arny.mlscanner.domain.models.RedactionResult
import com.arny.mlscanner.domain.models.TextBox
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.File

/**
 * Движок для создания и редактирования PDF.
 * Использует библиотеку com.tom_roush:pdfbox-android
 */
class PdfRedactionEngine(private val context: Context) {

    init {
        // Важно: инициализация загрузчика ресурсов PDFBox
        // Обычно это делается в Application классе, но добавим сюда проверку
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
    }

    companion object {
        private const val TAG = "PdfRedactionEngine"
        private val PAGE_SIZE = PDRectangle.A4
    }

    /**
     * Создает PDF из изображения, накладывая черные прямоугольники (redaction)
     * и невидимый текстовый слой (для поиска).
     */
    fun redactAndSavePdf(
        sourceImagePath: String,
        textBoxes: List<TextBox>,
        redactionMask: RedactionMask,
        outputPath: String
    ): RedactionResult? {
        var sourceBitmap: Bitmap? = null
        var doc: PDDocument? = null

        return try {
            // 1. Загружаем исходное изображение (mutable, чтобы рисовать на нем)
            val options = BitmapFactory.Options().apply { inMutable = true }
            sourceBitmap = BitmapFactory.decodeFile(sourceImagePath, options)
                ?: throw IllegalArgumentException("Cannot decode file: $sourceImagePath")

            // 2. Рисуем черные прямоугольники ПРЯМО НА БИТМАПЕ (Destructive redaction)
            // Это гарантирует, что под черным квадратом нет пикселей оригинала
            val canvas = Canvas(sourceBitmap)
            val paint = Paint().apply {
                color = android.graphics.Color.BLACK
                style = Paint.Style.FILL
            }

            for (box in redactionMask.redactedBoxes) {
                // Конвертируем BoundingBox в Rect
                val rect = android.graphics.Rect(
                    box.boundingBox.left.toInt(),
                    box.boundingBox.top.toInt(),
                    box.boundingBox.right.toInt(),
                    box.boundingBox.bottom.toInt()
                )
                canvas.drawRect(rect, paint)
            }

            // 3. Создаем PDF документ
            doc = PDDocument()
            val page = PDPage(PAGE_SIZE)
            doc.addPage(page)

            val contentStream = PDPageContentStream(doc, page)

            // 4. Масштабируем изображение под размер страницы A4
            val pageWidth = PAGE_SIZE.width
            val pageHeight = PAGE_SIZE.height
            val imageWidth = sourceBitmap.width.toFloat()
            val imageHeight = sourceBitmap.height.toFloat()

            // Scale to fit (сохраняя пропорции)
            val scale = kotlin.math.min(pageWidth / imageWidth, pageHeight / imageHeight)
            val drawnWidth = imageWidth * scale
            val drawnHeight = imageHeight * scale

            // Центрируем
            val startX = (pageWidth - drawnWidth) / 2f
            val startY =
                (pageHeight - drawnHeight) // PDF координаты начинаются снизу-слева, но image рисуется от top-left если не трансформировать

            // 5. Вставляем уже отредактированное изображение в PDF
            // Используем LosslessFactory для качества или JPEGFactory для сжатия
            val pdImage: PDImageXObject = LosslessFactory.createFromImage(doc, sourceBitmap)

            // В PDFBox 2.0 drawImage рисует с нижнего левого угла, если не использовать Matrix
            // Простейший способ:
            contentStream.drawImage(
                pdImage,
                startX,
                pageHeight - drawnHeight - 20f,
                drawnWidth,
                drawnHeight
            ) // Отступ снизу

            // 6. Добавляем НЕВИДИМЫЙ текстовый слой (OCR)
            // Исключаем текст, который попал под маску редактирования
            contentStream.setNonStrokingColor(
                255,
                255,
                255,
                0
            ) // Полностью прозрачный (если поддерживается) или белый
            // PDFBox Android иногда плохо работает с прозрачностью текста, поэтому используем режим рендеринга
            contentStream.setRenderingMode(com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode.NEITHER) // Невидимый текст

            contentStream.setFont(PDType1Font.HELVETICA, 10f * scale) // Примерный масштаб шрифта

            for (textBox in textBoxes) {
                // Если текст НЕ должен быть скрыт
                if (!redactionMask.isRedacted(textBox)) {
                    val box = textBox.boundingBox

                    // Конвертация координат из Image Space в PDF Space
                    // Y в PDF идет снизу вверх, в Android сверху вниз
                    val pdfX = startX + (box.left * scale)
                    val pdfY =
                        (pageHeight - drawnHeight - 20f) + (drawnHeight - (box.bottom * scale))

                    contentStream.beginText()
                    contentStream.newLineAtOffset(pdfX, pdfY)

                    // Санитизация текста (PDFBox не любит переводы строк в showText)
                    val safeText = textBox.text.replace(Regex("[\\r\\n]"), " ")
                    try {
                        contentStream.showText(safeText)
                    } catch (e: Exception) {
                        Log.w(TAG, "Font encoding issue with text: $safeText")
                    }
                    contentStream.endText()
                }
            }

            contentStream.close()

            // Сохраняем
            doc.save(outputPath)

            RedactionResult(
                originalImagePath = sourceImagePath,
                redactedImagePath = outputPath,
                redactedAreas = redactionMask.redactedBoxes.map { it.boundingBox },
                timestamp = System.currentTimeMillis()
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error in redactAndSavePdf", e)
            null
        } finally {
            try {
                doc?.close()
                sourceBitmap?.recycle()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Редактирует СУЩЕСТВУЮЩИЙ PDF.
     * Метод: Растеризация (превращение страниц в картинки) -> Закрашивание -> Сборка нового PDF.
     * Это гарантирует удаление скрытого текста и векторных данных.
     */
    fun redactExistingPdf(
        sourcePath: String,
        redactionMask: RedactionMask,
        outputPath: String
    ): Boolean {
        var sourceDoc: PDDocument? = null
        var destDoc: PDDocument? = null

        return try {
            val file = File(sourcePath)
            if (!file.exists()) throw IllegalArgumentException("File not found: $sourcePath")

            sourceDoc = PDDocument.load(file)
            destDoc = PDDocument()

            val renderer = PDFRenderer(sourceDoc)

            // Проходим по всем страницам
            for (pageIndex in 0 until sourceDoc.numberOfPages) {
                // 1. Рендерим страницу в Bitmap (300 DPI для качества)
                // Для MVP можно меньше, например 150-200, чтобы не забить память
                val renderedBitmap = renderer.renderImageWithDPI(pageIndex, 200f)

                // 2. Рисуем черные квадраты на битмапе
                val canvas = Canvas(renderedBitmap)
                val paint = Paint().apply {
                    color = android.graphics.Color.BLACK
                    style = Paint.Style.FILL
                }

                // ВАЖНО: Координаты redactionMask здесь должны совпадать с координатами PDF.
                // В реальном приложении координаты маски обычно привязаны к координатам страницы.
                // Здесь мы предполагаем, что маска передана в координатах PDF-страницы,
                // поэтому их нужно масштабировать к размеру Bitmap.

                val pdfPage = sourceDoc.getPage(pageIndex)
                val pdfWidth = pdfPage.mediaBox.width
                val pdfHeight = pdfPage.mediaBox.height

                val scaleX = renderedBitmap.width / pdfWidth
                val scaleY = renderedBitmap.height / pdfHeight

                for (box in redactionMask.redactedBoxes) {
                    // Конвертируем координаты PDF -> Bitmap
                    // Учтите, что box.boundingBox скорее всего в координатах скана OCR (пиксели),
                    // а не PDF point. Этот момент требует синхронизации в ViewModel.
                    // Для примера считаем, что box уже в нужной проекции или просто маскируем

                    val rect = android.graphics.Rect(
                        (box.boundingBox.left * scaleX).toInt(),
                        (box.boundingBox.top * scaleY).toInt(),
                        (box.boundingBox.right * scaleX).toInt(),
                        (box.boundingBox.bottom * scaleY).toInt()
                    )
                    canvas.drawRect(rect, paint)
                }

                // 3. Создаем новую страницу в целевом документе
                val newPage = PDPage(PDRectangle(pdfWidth, pdfHeight))
                destDoc.addPage(newPage)

                val contentStream = PDPageContentStream(destDoc, newPage)

                // Вставляем отредактированную картинку
                val pdImage = LosslessFactory.createFromImage(destDoc, renderedBitmap)
                contentStream.drawImage(pdImage, 0f, 0f, pdfWidth, pdfHeight)

                contentStream.close()
                renderedBitmap.recycle()
            }

            destDoc.save(outputPath)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in redactExistingPdf", e)
            false
        } finally {
            try {
                sourceDoc?.close()
                destDoc?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

data class RedactionMask(val redactedBoxes: List<TextBox>) {
    fun isRedacted(targetBox: TextBox): Boolean =
        redactedBoxes.any { it.boundingBox.intersects(targetBox.boundingBox) }
}