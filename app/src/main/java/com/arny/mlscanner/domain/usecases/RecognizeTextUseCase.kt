package com.arny.mlscanner.domain.usecases

import android.graphics.Bitmap
import com.arny.mlscanner.data.ocr.OcrEngine
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.LineInfo
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.RecognizedText
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.models.TextBlockInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RecognizeTextUseCase(
    private val ocrEngine: OcrEngine,
    private val imagePreprocessor: ImagePreprocessor
) {

    suspend fun execute(
        bitmap: Bitmap,
        settings: ScanSettings
    ): Result<RecognizedText> = withContext(Dispatchers.Default) {
        try {
            // 1. Инициализация движка (если еще не готов)
            if (!ocrEngine.initialize()) {
                return@withContext Result.failure(IllegalStateException("Failed to initialize OCR Engine"))
            }

            // 2. Препроцессинг (Только геометрия и фильтры, полный цикл)
            val preprocessedBitmap = imagePreprocessor.prepareBaseImage(bitmap, settings)

            // 3. Распознавание (ONNX)
            val ocrResult = ocrEngine.recognize(preprocessedBitmap)

            // 4. Маппинг результата (OcrResult -> RecognizedText)
            val mappedResult = mapToDomainModel(ocrResult)

            Result.success(mappedResult)
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    /**
     * Превращает сырые боксы ONNX в красивую структуру для UI
     */
    private fun mapToDomainModel(ocrResult: OcrResult): RecognizedText {
        val boxes = ocrResult.textBoxes

        if (boxes.isEmpty()) {
            return RecognizedText(
                originalText = "",
                formattedText = "",
                blocks = emptyList(),
                confidence = 0f,
                detectedLanguage = "unknown"
            )
        }

        // 1. Сортируем боксы сверху-вниз, затем слева-направо (простая эвристика чтения)
        // Допускаем погрешность в 10 пикселей по Y, чтобы считать что это одна строка
        val sortedBoxes = boxes.sortedWith(Comparator { b1, b2 ->
            val yDiff = b1.boundingBox.top - b2.boundingBox.top
            if (Math.abs(yDiff) < 10) {
                (b1.boundingBox.left - b2.boundingBox.left).toInt()
            } else {
                yDiff.toInt()
            }
        })

        // 2. Собираем полный текст
        val fullText = sortedBoxes.joinToString("\n") { it.text }

        // 3. Считаем среднюю уверенность
        val avgConfidence = sortedBoxes.map { it.confidence }.average().toFloat()

        // 4. Конвертируем в структуру блоков для UI
        // В ONNX каждый TextBox - это обычно строка или слово.
        // Для простоты мапим 1 TextBox = 1 LineInfo внутри 1 TextBlockInfo
        val blocks = sortedBoxes.map { box ->
            val rect = box.boundingBox.toRect()

            TextBlockInfo(
                text = box.text,
                boundingBox = rect,
                lines = listOf(
                    LineInfo(
                        text = box.text,
                        boundingBox = rect,
                        indentLevel = 0,
                        confidence = box.confidence
                    )
                )
            )
        }

        return RecognizedText(
            originalText = fullText,
            formattedText = fullText, // Здесь можно добавить логику сохранения отступов
            blocks = blocks,
            confidence = avgConfidence,
            detectedLanguage = detectLanguage(fullText)
        )
    }

    // Простая эвристика языка
    private fun detectLanguage(text: String): String {
        val cyrillicCount = text.count { it in 'А'..'я' || it in 'Ё'..'ё' }
        val latinCount = text.count { it in 'A'..'z' }
        return if (cyrillicCount > latinCount) "ru" else "en"
    }

    // Для превью во ViewModel
    fun preprocessImage(source: Bitmap, scanSettings: ScanSettings): Bitmap =
        imagePreprocessor.prepareBaseImage(source, scanSettings)
}