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

        return OcrResult(boxes, System.currentTimeMillis())
    }

    private fun copyTessDataToStorage(context: Context): String {
        // Tesseract ищет файлы в <path>/tessdata/rus.traineddata.back
        // Поэтому мы возвращаем путь к ПАПКЕ, В КОТОРОЙ лежит папка tessdata
        val tessDir = File(context.filesDir, "tessdata")
        if (!tessDir.exists()) tessDir.mkdirs()

        val rus = "rus.traineddata"
        val dataFile = File(tessDir, rus)
        if (!dataFile.exists()) {
            context.assets.open("tessdata/${rus}").use { input ->
                FileOutputStream(dataFile).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return context.filesDir.absolutePath // Возвращаем родителя tessdata
    }

    fun close() {
        tessApi.recycle() // Освобождаем нативную память
    }
}
