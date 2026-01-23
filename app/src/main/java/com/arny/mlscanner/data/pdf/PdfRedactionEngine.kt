package com.arny.mlscanner.data.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.RedactionResult
import com.arny.mlscanner.domain.models.TextBox
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory

/**
 * Engine for creating a redacted PDF from an image.
 */
class PdfRedactionEngine {
    companion object {
        private const val TAG = "PdfRedactionEngine"
        // A4 size in points (72 DPI)
        private val PAGE_SIZE = PDRectangle.A4
    }

    fun redactAndSavePdf(
        sourceImagePath: String,
        textBoxes: List<TextBox>,
        redactionMask: RedactionMask,
        outputPath: String
    ): RedactionResult? {
        var sourceBitmap: Bitmap? = null
        var doc: PDDocument? = null
        return try {
            // Decode mutable bitmap
            val options = BitmapFactory.Options().apply { inMutable = true }
            sourceBitmap = BitmapFactory.decodeFile(sourceImagePath, options)
                ?: throw IllegalArgumentException("Cannot decode file: $sourceImagePath")

            // Draw black rectangles on the bitmap for redaction
            val canvas = Canvas(sourceBitmap!!)
            val paint = Paint().apply {
                color = android.graphics.Color.BLACK
                style = Paint.Style.FILL
            }
            for (box in redactionMask.redactedBoxes) {
                val rect = box.boundingBox.toRect()
                canvas.drawRect(rect, paint)
            }

            doc = PDDocument()
            val page = PDPage(PAGE_SIZE)
            doc.addPage(page)
            val contentStream = PDPageContentStream(doc, page)

            // Calculate scaling to fit the image on the page
            val pageWidth = PAGE_SIZE.width
            val pageHeight = PAGE_SIZE.height
            val imageWidth = sourceBitmap.width.toFloat()
            val imageHeight = sourceBitmap.height.toFloat()
            val scale = kotlin.math.min(pageWidth / imageWidth, pageHeight / imageHeight)
            val drawnWidth = imageWidth * scale
            val drawnHeight = imageHeight * scale
            val startX = (pageWidth - drawnWidth) / 2f
            val startY = pageHeight - drawnHeight

            // Draw the redacted image onto the PDF
            val pdImage = LosslessFactory.createFromImage(doc, sourceBitmap)
            contentStream.drawImage(pdImage, startX, startY, drawnWidth, drawnHeight)

            // Add non‑redacted text layers
            val font = PDType1Font.HELVETICA
            contentStream.setFont(font, 12f * scale)
            for (textBox in textBoxes) {
                if (!redactionMask.isRedacted(textBox)) {
                    val pdfRect = mapBoxToPdfCoordinates(
                        textBox.boundingBox,
                        imageHeight,
                        startX,
                        startY,
                        scale
                    )
                    contentStream.beginText()
                    contentStream.newLineAtOffset(pdfRect.lowerLeftX, pdfRect.lowerLeftY)
                    val safeText = textBox.text.replace(Regex("[\\r\\n]"), " ")
                    try {
                        contentStream.showText(safeText)
                    } catch (e: Exception) {
                        Log.w(TAG, "Skipping text due to font encoding issues: $safeText")
                    }
                    contentStream.endText()
                }
            }

            contentStream.close()
            doc.save(outputPath)

            RedactionResult(
                originalImagePath = sourceImagePath,
                redactedImagePath = outputPath,
                redactedAreas = redactionMask.redactedBoxes.map { it.boundingBox },
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during PDF redaction", e)
            null
        } finally {
            doc?.close()
            sourceBitmap?.recycle()
        }
    }

    private fun mapBoxToPdfCoordinates(
        box: BoundingBox,
        originalImageHeight: Float,
        offsetX: Float,
        offsetY: Float,
        scale: Float
    ): PDRectangle {
        val x = box.left
        val y = box.top
        val w = box.right - box.left
        val h = box.bottom - box.top
        val pdfX = offsetX + (x * scale)
        val pdfY = offsetY + ((originalImageHeight - (y + h)) * scale)
        return PDRectangle(pdfX, pdfY, w * scale, h * scale)
    }
}

data class RedactionMask(val redactedBoxes: List<TextBox>) {
    fun isRedacted(targetBox: TextBox): Boolean =
        redactedBoxes.any { it.boundingBox.intersects(targetBox.boundingBox) }
}
