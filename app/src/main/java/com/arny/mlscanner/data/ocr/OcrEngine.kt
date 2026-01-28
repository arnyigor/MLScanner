package com.arny.mlscanner.data.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.TextBox
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap

class OcrEngine(private val context: Context) {

    private var ortEnv: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var initialized = false
    private var vocabulary: List<String> = emptyList()

    private val detectionBuffer = FloatBuffer.allocate(3 * 640 * 640)
    private val recognitionBuffer = FloatBuffer.allocate(3 * 48 * 320)

    companion object {
        private const val TAG = "OcrDebug" // <-- Специальный тег для фильтрации
        private const val DET_MODEL = "ch_PP-OCRv4_det_infer.onnx"
        private const val REC_MODEL = "cyrillic_PP-OCRv3_rec_infer.onnx"
        private const val DICT_FILE = "cyrillic_dict.txt"

        // Размеры для v3/v4
        private const val REC_H = 48
        private const val REC_W = 320
    }

    fun initialize(): Boolean {
        if (initialized) return true
        Log.d(TAG, "=== INITIALIZING OCR ENGINE ===")
        return try {
            ortEnv = OrtEnvironment.getEnvironment()

            // 1. Словарь
            vocabulary = context.assets.open(DICT_FILE).bufferedReader().readLines().map { it.trim() }
            Log.d(TAG, "Vocabulary loaded. Size: ${vocabulary.size}")
            if (vocabulary.isNotEmpty()) {
                Log.d(TAG, "First 5 words: ${vocabulary.take(5)}")
                Log.d(TAG, "Last 5 words: ${vocabulary.takeLast(5)}")
            }

            // 2. Модели
            val detBytes = context.assets.open(DET_MODEL).use { it.readBytes() }
            val recBytes = context.assets.open(REC_MODEL).use { it.readBytes() }
            Log.d(TAG, "Models loaded. DET: ${detBytes.size} bytes, REC: ${recBytes.size} bytes")

            val opts = OrtSession.SessionOptions()
            detSession = ortEnv?.createSession(detBytes, opts)
            recSession = ortEnv?.createSession(recBytes, opts)

            initialized = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL INIT ERROR", e)
            false
        }
    }

    fun recognize(bitmap: Bitmap): OcrResult {
        if (!initialized) initialize()
        Log.d(TAG, "--- START RECOGNITION ---")
        Log.d(TAG, "Input Bitmap: ${bitmap.width}x${bitmap.height}, Config: ${bitmap.config}")

        // DEBUG: Сохраняем входное изображение
        saveBitmapForDebug(bitmap, "input_full.png")

        // 1. Detection
        val boxes = detectText(bitmap)
        Log.d(TAG, "Detection finished. Found ${boxes.size} boxes.")

        // Если боксов нет - попробуем распознать ВСЮ картинку (fallback debug)
        val boxesToProcess = if (boxes.isEmpty()) {
            Log.w(TAG, "No boxes detected! Trying full image recognition as fallback.")
            listOf(BoundingBox(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()))
        } else {
            boxes
        }

        val results = mutableListOf<TextBox>()

        // 2. Recognition
        boxesToProcess.forEachIndexed { index, box ->
            val cropped = cropBitmapToBox(bitmap, box)

            // DEBUG: Сохраняем первые 3 кропа, чтобы проверить качество
            if (index < 3) saveBitmapForDebug(cropped, "crop_$index.png")

            val recResult = recognizeText(cropped)
            Log.d(TAG, "Box #$index result: '${recResult.text}' (conf: ${recResult.confidence})")

            if (recResult.text.isNotBlank()) {
                results.add(TextBox(recResult.text, recResult.confidence, box))
            }
        }

        return OcrResult(results, System.currentTimeMillis())
    }

    private fun detectText(bitmap: Bitmap): List<BoundingBox> {
        return try {
            val tensor = preprocessForDet(bitmap)
            val res = detSession!!.run(mapOf("x" to tensor))
            val output = res[0].value

            // ЛОГИРУЕМ ТИП ВЫХОДА
            // Log.d(TAG, "Det Output type: ${output.javaClass.simpleName}")

            // !!! ВАЖНО !!!
            // Если вы используете модель RapidOCR, она часто экспортирована БЕЗ постпроцессинга.
            // Она возвращает Heatmap [1, 1, 640, 640].
            // Для MVP без сложного C++ кода мы здесь сэмулируем "Весь экран - это текст",
            // чтобы проверить хотя бы Распознавание (Rec).
            // Если Det вернет 0 боксов, fallback в методе recognize() сработает.

            // TODO: Реализовать DB-PostProcess (bitmap threshold -> contours)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Detection failed", e)
            emptyList()
        }
    }

    private fun recognizeText(bitmap: Bitmap): RecognitionResult {
        return try {
            val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val targetW = (REC_H * ratio).toInt().coerceAtMost(REC_W)

            val inputBitmap = createBitmap(REC_W, REC_H)
            val canvas = Canvas(inputBitmap)
            canvas.drawColor(Color.BLACK) // Лучше черный фон для паддинга
            val scaled = bitmap.scale(targetW, REC_H)
            canvas.drawBitmap(scaled, 0f, 0f, null)

            val tensor = preprocessForRec(inputBitmap)
            val res = recSession!!.run(mapOf("x" to tensor))
            val output = res[0].value as Array<*>
            val seq = output[0] as Array<FloatArray>

            // !!! ВАЖНЫЙ ЛОГ !!!
            // Vocab Size модели. Если он ~6626, а у нас файл 163 строки — это причина ошибки.
            val modelVocabSize = seq[0].size
            Log.w(TAG, "Model expects vocab size: $modelVocabSize. Loaded vocab size: ${vocabulary.size}")

            val rawIndices = seq.take(10).map { probs ->
                probs.indices.maxByOrNull { probs[it] } ?: -1
            }
            Log.v(TAG, "Raw indices: $rawIndices")

            ctcDecode(seq)
        } catch (e: Exception) {
            Log.e(TAG, "Rec failed", e)
            RecognitionResult("", 0f)
        }
    }

    private fun preprocessForRec(bitmap: Bitmap): OnnxTensor {
        val pixels = IntArray(REC_W * REC_H)
        bitmap.getPixels(pixels, 0, REC_W, 0, 0, REC_W, REC_H)
        val buffer = recognitionBuffer.apply { clear() }

        // Normalization (x/255 - 0.5)/0.5
        for (c in 0..2) {
            for (p in pixels) {
                val v = when (c) {
                    0 -> (p shr 16) and 0xFF
                    1 -> (p shr 8) and 0xFF
                    else -> p and 0xFF
                }
                buffer.put((v / 255.0f - 0.5f) / 0.5f)
            }
        }
        buffer.rewind()
        return OnnxTensor.createTensor(ortEnv, buffer, longArrayOf(1, 3, REC_H.toLong(), REC_W.toLong()))
    }

    // Заглушка для Det препроцессинга (чтобы компилировалось)
    private fun preprocessForDet(bitmap: Bitmap): OnnxTensor {
        val buffer = FloatBuffer.allocate(3*640*640)
        return OnnxTensor.createTensor(ortEnv, buffer, longArrayOf(1, 3, 640, 640))
    }

    private fun ctcDecode(sequence: Array<FloatArray>): RecognitionResult {
        val sb = StringBuilder()
        val blankIdx = sequence[0].size - 1
        var lastIdx = -1
        var scoreSum = 0f
        var count = 0

        for (probs in sequence) {
            val maxIdx = probs.indices.maxByOrNull { probs[it] } ?: -1
            val maxVal = probs[maxIdx]

            if (maxIdx != blankIdx && maxIdx != lastIdx) {
                if (maxIdx in vocabulary.indices) {
                    sb.append(vocabulary[maxIdx])
                    scoreSum += maxVal
                    count++
                } else {
                    Log.w(TAG, "Index $maxIdx out of bounds (Vocab: ${vocabulary.size})")
                }
            }
            lastIdx = maxIdx
        }
        return RecognitionResult(sb.toString(), if (count > 0) scoreSum/count else 0f)
    }

    private fun cropBitmapToBox(bitmap: Bitmap, box: BoundingBox): Bitmap {
        // Упрощенный кроп для теста
        return if (box.right > box.left) Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height) else bitmap
    }

    private fun saveBitmapForDebug(bitmap: Bitmap, name: String) {
        try {
            val file = File(context.externalCacheDir, name)
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d(TAG, "Saved debug image: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save debug image", e)
        }
    }

    fun cleanup() { detSession?.close(); recSession?.close(); ortEnv?.close() }
}

/**
 * Вспомогательный класс для результатов распознавания
 */
data class RecognitionResult(
    val text: String,
    val confidence: Float
)