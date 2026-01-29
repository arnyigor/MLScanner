package com.arny.mlscanner.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.TextBox
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.io.FileOutputStream

class TesseractEngine(private val context: Context) {
    private val tessApi = TessBaseAPI()
    private var initialized = false

    companion object {
        private const val TAG = "TesseractEngine"
    }

    /**
     * Инициализация Tesseract (теперь потокобезопасная).
     */
    fun init() {
        if (initialized) return

        try {
            val dataPath = copyTessDataToStorage(context)

            // Инициализация с обоими языками (rus+eng)
            if (!tessApi.init(dataPath, "rus+eng")) {
                Log.e(TAG, "Init failed: rus+eng")
                throw RuntimeException("Tesseract init failed")
            }

            // PSM_AUTO - лучший режим для общего документа
            tessApi.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO

            initialized = true
            Log.d(TAG, "Tesseract initialized with rus+eng. PSM_AUTO.")
        } catch (e: Exception) {
            Log.e(TAG, "Tesseract initialization error", e)
            initialized = false
        }
    }

    /**
     * Основной метод распознавания.
     */
    fun recognize(bitmap: Bitmap): OcrResult {
        if (!initialized) init()
        if (!initialized) {
            Log.e(TAG, "Engine not initialized, skipping recognition.")
            return OcrResult(emptyList(), System.currentTimeMillis())
        }

        tessApi.setImage(bitmap)

        // 1. ЗАПУСК РАСПОЗНАВАНИЯ (это ключевой вызов)
        val fullText = tessApi.utF8Text ?: ""

        if (fullText.isBlank()) {
            Log.w(TAG, "Tesseract returned EMPTY fullText. Skipping box parsing.")
            tessApi.clear()
            return OcrResult(emptyList(), System.currentTimeMillis())
        }

        // --- ДЛЯ ОТЛАДКИ ---
        Log.i(TAG, "Full Recognized Text (Tesseract):\n${fullText.trim()}")
        // --- КОНЕЦ ОТЛАДКИ ---

        val boxes = mutableListOf<TextBox>()
        val iterator = tessApi.resultIterator

        if (iterator != null) {
            iterator.begin()
            do {
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                val word = iterator.getUTF8Text(level)?.trim()
                val rect = iterator.getBoundingBox(level)
                val conf = iterator.confidence(level)

                if (word != null && word.isNotBlank() && rect != null && rect.size >= 4) {
                    val left = rect[0].toFloat()
                    val top = rect[1].toFloat()
                    val right = rect[2].toFloat()
                    val bottom = rect[3].toFloat()

                    // [ЛОГ]: Выводим BBox и Conf для каждого слова (для отладки)
                    // Log.d(TAG, "WORD: $word, Conf: ${conf / 100f}, Box: $left,$top,$right,$bottom")

                    boxes.add(
                        TextBox(
                            text = word,
                            confidence = conf / 100f,
                            boundingBox = BoundingBox(left, top, right, bottom)
                        )
                    )
                }

            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
            iterator.delete()
        }

        tessApi.clear()
        return OcrResult(boxes, System.currentTimeMillis())
    }

    /**
     * Копирует traineddata файлы из assets в приватную папку
     */
    private fun copyTessDataToStorage(context: Context): String {
        val dataDir = File(context.filesDir, "tessdata")
        if (!dataDir.exists()) dataDir.mkdirs()

        // Копируем rus и eng
        listOf("rus.traineddata", "eng.traineddata").forEach { langFile ->
            val dataFile = File(dataDir, langFile)
            if (!dataFile.exists()) {
                try {
                    context.assets.open("tessdata/$langFile").use { input ->
                        FileOutputStream(dataFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "$langFile loaded")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to copy $langFile from assets", e)
                }
            }
        }
        return context.filesDir.absolutePath
    }

    /**
     * Освобождение нативных ресурсов
     */
    fun close() {
        tessApi.recycle()
        initialized = false
        Log.d(TAG, "Tesseract resources recycled.")
    }
}