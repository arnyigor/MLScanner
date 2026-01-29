// java\com\arny\mlscanner\domain\usecases\RecognizeTextUseCase.kt

package com.arny.mlscanner.domain.usecases

import android.graphics.Bitmap
import com.arny.mlscanner.data.ocr.TesseractEngine
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class RecognizeTextUseCase(
    private val preprocessor: ImagePreprocessor,
    private val tesseractEngine: TesseractEngine // ИНЖЕКТИРУЕМ
) {
    // Tesseract должен быть инициализирован один раз
    init {
        tesseractEngine.init()
    }

    suspend fun execute(
        bitmap: Bitmap,
        settings: ScanSettings
    ): Result<RecognizedText> = withContext(Dispatchers.Default) {
        try {
            // 1. Предобработка изображения
            val preprocessedBitmap = preprocessor.prepareBaseImage(bitmap, settings)

            // 2. Распознавание текста с помощью Tesseract
            val ocrResult = tesseractEngine.recognize(preprocessedBitmap)

            // 3. Пост-обработка и маппинг в доменную модель
            val recognizedText = mapTesseractResultToDomainModel(ocrResult)

            Result.success(recognizedText)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun mapTesseractResultToDomainModel(ocrResult: OcrResult): RecognizedText {
        val boxes = ocrResult.textBoxes
        if (boxes.isEmpty()) {
            return RecognizedText("", "", emptyList(), 0f, "unknown")
        }

        // 1. Собираем полный текст, чтобы найти URL-адреса
        val allTextRaw = boxes.joinToString(" ") { it.text }

        // 2. ПОСТ-ОБРАБОТКА URL (ФИКС СЛИПАНИЯ)
        // Regex для URL (должен быть достаточно широким)
        val urlRegex = Regex("https?://[a-zA-Z0-9./-]+", RegexOption.IGNORE_CASE)

        // Удаляем все пробелы, которые Tesseract мог добавить в URL
        val finalFormattedText = urlRegex.replace(allTextRaw) { match ->
            match.value.replace(" ", "")
        }

        // 3. Считаем среднюю уверенность
        val avgConf = boxes.map { it.confidence }.average().toFloat()

        // 4. Маппинг в блоки (для UI)
        val lineInfos = boxes.map { box ->
            LineInfo(
                text = box.text,
                boundingBox = box.boundingBox.toRect(),
                indentLevel = 0,
                confidence = box.confidence
            )
        }

        val blocks = listOf(
            TextBlockInfo(
                text = finalFormattedText,
                boundingBox = lineInfos.first().boundingBox, // Берем первый BBox для всего блока
                lines = lineInfos
            )
        )

        // Простая эвристика для языка (чтобы UI показывал ENG/RUS)
        val detectedLang = if (allTextRaw.count { it in 'a'..'z' || it in 'A'..'Z' } > allTextRaw.count { it in 'А'..'Я' || it in 'а'..'я' }) "eng" else "rus"

        return RecognizedText(
            originalText = allTextRaw,
            formattedText = finalFormattedText,
            blocks = blocks,
            confidence = avgConf,
            detectedLanguage = detectedLang.uppercase(Locale.ROOT)
        )
    }

    fun preprocessImage(source: Bitmap, scanSettings: ScanSettings): Bitmap =
        preprocessor.prepareBaseImage(source, scanSettings)
}