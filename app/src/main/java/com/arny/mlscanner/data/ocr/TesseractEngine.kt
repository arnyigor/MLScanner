package com.arny.mlscanner.data.ocr // Ваш пакет

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.TextBox
import com.arny.mlscanner.domain.models.BoundingBox
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

class TesseractEngine(private val context: Context) {
    private val tessApi = TessBaseAPI()

    fun init() {
        val dataPath = copyTessDataToStorage(context)
        // Инициализация (путь к ПАПКЕ, где лежит папка tessdata)
        if (!tessApi.init(dataPath, "rus+eng")) {
            Log.e("Tess", "Init failed")
            throw RuntimeException("Tesseract init failed")
        }
        // Включаем сохранение координат слов
        tessApi.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
    }

    fun recognize(bitmap: Bitmap): OcrResult {
        if (tessApi.utF8Text == null) init() // Ленивая инициализация

        tessApi.setImage(bitmap)
        val fullText = tessApi.utF8Text // Сначала нужно получить текст, чтобы запустить анализ


        val boxes = mutableListOf<TextBox>()
        val iterator = tessApi.resultIterator

        if (iterator != null) {
            iterator.begin()
            do {
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                val word = iterator.getUTF8Text(level)

// Метод возвращает int[] или null, если бокса нет
                val rect = iterator.getBoundingBox(level)

                if (word != null && rect != null && rect.size >= 4) {
                    // Координаты лежат в массиве по индексам 0, 1, 2, 3
                    val left = rect[0].toFloat()
                    val top = rect[1].toFloat()
                    val right = rect[2].toFloat()
                    val bottom = rect[3].toFloat()

                    val conf = iterator.confidence(level)

                    boxes.add(TextBox(
                        text = word,
                        confidence = conf / 100f,
                        boundingBox = BoundingBox(left, top, right, bottom)
                    ))
                }

            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        }

        // Очистка (важно для памяти!)
        tessApi.clear()

        val ocrResult = OcrResult(boxes, System.currentTimeMillis())
        ocrResult.textBoxes.forEach { block ->
            Log.d(this::class.java.simpleName, "Text: [${block.text}] Conf: ${block.confidence}")
        }
        return ocrResult
    }

    // В TesseractEngine.kt
    private fun copyTessDataToStorage(context: Context): String {
        val dataDir = File(context.filesDir, "tessdata")
        if (!dataDir.exists()) dataDir.mkdirs()

        // ВАЖНО: Копируем rus И eng
        listOf("rus.traineddata", "eng.traineddata").forEach { langFile ->
            val dataFile = File(dataDir, langFile)
            if (!dataFile.exists()) {
                try {
                    // ПРОВЕРЬТЕ, что в assets/tessdata лежат ОБА файла
                    context.assets.open("tessdata/$langFile").use { input ->
                        FileOutputStream(dataFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(this::class.java.simpleName, "$langFile loaded")
                } catch (e: Exception) {
                    // Если нет файла, логируем ошибку
                    Log.e(this::class.java.simpleName, "Failed to copy $langFile from assets", e)
                }
            }
        }
        return context.filesDir.absolutePath
    }

    fun close() {
        tessApi.recycle() // Освобождаем нативную память
    }
}
