package com.arny.mlscanner.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.TextBox
import com.googlecode.tesseract.android.TessBaseAPI
import org.opencv.core.Mat
import java.io.File
import java.io.FileOutputStream

class TesseractEngine(private val context: Context) {
    private val tessApi = TessBaseAPI()
    private var initialized = false
    private val lock = Any() // Объект для синхронизации
    // Кэш для переиспользования Mat
    private val reusableMat = Mat()

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
            tessApi.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT

            initialized = true
            Log.d(TAG, "Tesseract initialized with rus+eng. PSM_SPARSE_TEXT.")
        } catch (e: Exception) {
            Log.e(TAG, "Tesseract initialization error", e)
            initialized = false
        }
    }

    /**
     * Основной метод распознавания.
     */
    fun recognize(bitmap: Bitmap): OcrResult = synchronized(lock) { // 1. СИНХРОНИЗАЦИЯ
        if (!initialized) init()
        if (!initialized) return OcrResult(emptyList(), System.currentTimeMillis())
        // Защита: гарантированная конвертация в безопасный формат
        val safeBitmap = ensureSafeBitmap(bitmap)

        try {
            tessApi.setImage(safeBitmap)

            // 1. ЗАПУСК РАСПОЗНАВАНИЯ
            val fullText = tessApi.utF8Text.orEmpty()

            if (fullText.isBlank()) {
                Log.w(TAG, "Tesseract returned EMPTY fullText. Skipping box parsing.")
                // tessApi.clear() вызовется в finally
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

                    // Важно: иногда getBoundingBox возвращает null или массив неправильного размера
                    val rect = iterator.getBoundingBox(level)
                    val conf = iterator.confidence(level)

                    if (!word.isNullOrBlank() && rect != null && rect.size >= 4) {
                        val left = rect[0].toFloat()
                        val top = rect[1].toFloat()
                        val right = rect[2].toFloat()
                        val bottom = rect[3].toFloat()

                        boxes.add(
                            TextBox(
                                text = word,
                                confidence = conf / 100f,
                                boundingBox = BoundingBox(left, top, right, bottom)
                            )
                        )
                    }

                } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))

                // Обязательно удаляем итератор, это нативный объект
                iterator.delete()
            }

            return OcrResult(boxes, System.currentTimeMillis())

        } catch (e: Exception) {
            Log.e(TAG, "Error during native recognition", e)
            return OcrResult(emptyList(), System.currentTimeMillis())
        } finally {
            // ВАЖНО: Очищаем изображение после распознавания
            // Делаем это в finally, чтобы выполнилось даже при ошибке
            tessApi.clear()

            // Если мы создавали копию битмапа (safeBitmap != bitmap), её нужно освободить,
            // чтобы не забивать память, так как GC может не успеть за камерой.
            if (safeBitmap !== bitmap) {
                safeBitmap.recycle()
            }
        }
    }

    private fun ensureSafeBitmap(source: Bitmap): Bitmap {
        // Проверяем все опасные условия
        val needsCopy = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    source.config == Bitmap.Config.HARDWARE -> true
            source.config != Bitmap.Config.ARGB_8888 -> true
            !source.isMutable -> true  // Tesseract может модифицировать
            else -> false
        }

        return if (needsCopy) {
            source.copy(Bitmap.Config.ARGB_8888, true)
        } else source
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