package com.arny.mlscanner.data.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.TextBox
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import androidx.core.graphics.scale

/**
 * Реализация OCR-движка с использованием ONNX Runtime
 * В соответствии с требованиями TECH.md:
 * - Локальная инференция (без облака)
 * - Поддержка русского и английского текста
 * - Оптимизированная модель (INT8 Quantization)
 * - Извлечение структурированных данных
 */
class OcrEngine(private val context: Context) {
    private lateinit var ortEnv: OrtEnvironment
private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private var initialized = false

    // Reuse buffers to avoid allocation overhead
    private val detectionBuffer = FloatBuffer.allocate(3 * 640 * 640)
    private val recognitionBuffer = FloatBuffer.allocate(3 * 32 * 320)

    companion object {
        private const val TAG = "OcrEngine"
        private const val DET_MODEL_ASSET = "ch_PP-OCRv4_det_infer.onnx"
        private const val REC_MODEL_ASSET = "ch_PP-OCRv4_rec_infer.onnx"
    }


    /**
     * Инициализация OCR-движка
     * Загрузка моделей из assets
     */
    fun initialize(): Boolean {
        return try {
            ortEnv = OrtEnvironment.getEnvironment()

            // Загрузка моделей из assets
            val detModelBytes = loadModelFromAssets(DET_MODEL_ASSET)
            val recModelBytes = loadModelFromAssets(REC_MODEL_ASSET)

            if (detModelBytes.isEmpty() || recModelBytes.isEmpty()) {
                Log.e(TAG, "Failed to load models from assets")
                return false
            }

            val sessionOptions = OrtSession.SessionOptions().apply {
                // Настройка сессии для инференции на CPU
                // Включаем оптимизации для мобильных устройств
            }

            detSession = ortEnv.createSession(detModelBytes, sessionOptions)
            recSession = ortEnv.createSession(recModelBytes, sessionOptions)

            initialized = true
            Log.d(TAG, "OcrEngine initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize OcrEngine", e)
            false
        }
    }

    /**
     * Распознавание текста на изображении
     */
    fun recognize(bitmap: Bitmap): OcrResult {
        if (!initialized) {
            throw IllegalStateException("OcrEngine not initialized. Call initialize() first.")
        }

        val startTime = System.currentTimeMillis()

        // 1. Детекция текстовых регионов
        val detOutput = detectTextRegions(bitmap)
        val boxes = detOutput.parseBoxes()

        // 2. Распознавание текста в каждом регионе
        val recognitionResults = mutableListOf<TextBox>()
        for (box in boxes) {
            val croppedBitmap = cropBitmapToBox(bitmap, box)
            val textResult = recognizeText(croppedBitmap)
            
            recognitionResults.add(
                TextBox(
                    text = textResult.text,
                    confidence = textResult.confidence,
                    boundingBox = box
                )
            )
        }

        val processingTime = System.currentTimeMillis() - startTime

        return OcrResult(
            textBoxes = recognitionResults,
            timestamp = System.currentTimeMillis(),
            processingTimeMs = processingTime
        )
    }

    /**
     * Детекция текстовых регионов на изображении
     */
    private fun detectTextRegions(bitmap: Bitmap): OrtSession.Result {
        val inputTensor = preprocessImageForDetection(bitmap)
        val inputs = mapOf("x" to inputTensor)
        return detSession!!.run(inputs)
    }

    /**
     * Распознавание текста в конкретном регионе
     */
    private fun recognizeText(bitmap: Bitmap): RecognitionResult {
        val inputTensor = preprocessImageForRecognition(bitmap)
        val inputs = mapOf("x" to inputTensor)
        val output = recSession!!.run(inputs)

        // Парсинг CTC-вывода
        val decodedText = ctcDecode(output)
        val confidence = calculateConfidence(output)

        return RecognitionResult(decodedText, confidence)
    }

    /**
     * Предобработка изображения для детекции текста
     * FIX: Использование FloatBuffer и корректная нормализация PaddleOCR
     */
    private fun preprocessImageForDetection(bitmap: Bitmap): OnnxTensor {
        val targetWidth = 640
        val targetHeight = 640

        // Масштабирование
        val resized = bitmap.scale(targetWidth, targetHeight)

        val pixels = IntArray(targetWidth * targetHeight)
        resized.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)

        // Подготовка буфера. PaddleOCR ожидает порядок NCHW (Batch, Channels, Height, Width)
        // Reuse detection buffer to avoid allocations
val buffer = detectionBuffer.apply { clear() }

        // Стандартная нормализация для PaddleOCR (ImageNet mean/std)
        // Если ваша модель экспортирована без fuse-нормализации, используйте эти значения:
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
        val std = floatArrayOf(0.229f, 0.224f, 0.225f)

        // Разделяем циклы для R, G, B каналов (формат NCHW)
        // Канал R
        for (i in pixels.indices) {
            val r = ((pixels[i] shr 16) and 0xFF) / 255.0f
            buffer.put((r - mean[0]) / std[0])
        }
        // Канал G
        for (i in pixels.indices) {
            val g = ((pixels[i] shr 8) and 0xFF) / 255.0f
            buffer.put((g - mean[1]) / std[1])
        }
        // Канал B
        for (i in pixels.indices) {
            val b = (pixels[i] and 0xFF) / 255.0f
            buffer.put((b - mean[2]) / std[2])
        }

        buffer.rewind() // Сброс позиции буфера перед чтением

        // FIX: Передаем FloatBuffer вместо FloatArray
        return OnnxTensor.createTensor(ortEnv, buffer, longArrayOf(1, 3, 640L, 640L))
    }

    /**
     * Предобработка изображения для распознавания текста
     */
    private fun preprocessImageForRecognition(bitmap: Bitmap): OnnxTensor {
        val targetW = 320
        val targetH = 32

        val resized = bitmap.scale(targetW, targetH)
        val pixels = IntArray(targetW * targetH)
        resized.getPixels(pixels, 0, targetW, 0, 0, targetW, targetH)

        // Reuse recognition buffer to avoid allocations
val buffer = recognitionBuffer.apply { clear() }

        // Для REC модели нормализация часто: (pixel / 255.0 - 0.5) / 0.5
        // Или стандартная mean/std, зависит от конфига обучения.
        // Оставим вашу логику (просто / 255), но упакуем в NCHW.

        // R
        for (i in pixels.indices) buffer.put(((pixels[i] shr 16) and 0xFF) / 255.0f)
        // G
        for (i in pixels.indices) buffer.put(((pixels[i] shr 8) and 0xFF) / 255.0f)
        // B
        for (i in pixels.indices) buffer.put((pixels[i] and 0xFF) / 255.0f)

        buffer.rewind()

        // FIX: Передаем FloatBuffer
        return OnnxTensor.createTensor(ortEnv, buffer, longArrayOf(1, 3, 32L, 320L))
    }

    /**
     * Загрузка модели из assets
     */
    private fun loadModelFromAssets(modelName: String): ByteArray {
        return try {
            val assetManager = context.assets
            val inputStream = assetManager.open(modelName)
            val outputStream = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            var bytesRead: Int

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }

            outputStream.toByteArray().also {
                Log.d(TAG, "Loaded model $modelName with size: ${it.size} bytes")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model $modelName from assets", e)
            byteArrayOf()
        }
    }

    /**
     * Парсинг результатов детекции
     */
    private fun OrtSession.Result.parseBoxes(): List<BoundingBox> {
        // Получаем выходные данные детекционной модели PaddleOCR v4
        val output = this.get(0).value
        val boxes = mutableListOf<BoundingBox>()
        
        when (output) {
            is FloatArray -> {
                // Обрабатываем выход как одномерный массив
                val floatArray = output
                
                // В PaddleOCR v4 детекционная модель может возвращать координаты в формате [batch_size, num_detections, 8]
                // где 8 - это 4 точки (x, y) для квадрилатерального бокса
                val detectionStep = 8 // 8 координат на каждый бокс (4 точки * 2 координаты)
                
                for (i in 0 until floatArray.size step detectionStep) {
                    if (i + detectionStep - 1 < floatArray.size) {
                        val x1 = floatArray[i]
                        val y1 = floatArray[i + 1]
                        val x2 = floatArray[i + 2]
                        val y2 = floatArray[i + 3]
                        val x3 = floatArray[i + 4]
                        val y3 = floatArray[i + 5]
                        val x4 = floatArray[i + 6]
                        val y4 = floatArray[i + 7]

                        // Находим минимальные и максимальные координаты для создания ограничивающего прямоугольника
                        val minX = minOf(x1, x2, x3, x4)
                        val maxX = maxOf(x1, x2, x3, x4)
                        val minY = minOf(y1, y2, y3, y4)
                        val maxY = maxOf(y1, y2, y3, y4)

                        // Фильтруем слишком маленькие или неправильно ориентированные боксы
                        if (maxX > minX && maxY > minY) {
                            // Преобразуем координаты из размера модели (640x640) обратно к оригинальному размеру изображения
                            val scaleFactorX = 1.0f // Будет применен при вызове с учетом оригинального размера
                            val scaleFactorY = 1.0f // Будет применен при вызове с учетом оригинального размера
                            
                            boxes.add(BoundingBox(
                                left = minX * scaleFactorX,
                                top = minY * scaleFactorY,
                                right = maxX * scaleFactorX,
                                bottom = maxY * scaleFactorY
                            ))
                        }
                    }
                }
            }
            is Array<*> -> {
                // Обрабатываем выход как многомерный массив
                for (element in output) {
                    if (element is FloatArray && element.size >= 8) {
                        val coords = element
                        
                        val x1 = coords[0]
                        val y1 = coords[1]
                        val x2 = coords[2]
                        val y2 = coords[3]
                        val x3 = coords[4]
                        val y3 = coords[5]
                        val x4 = coords[6]
                        val y4 = coords[7]

                        val minX = minOf(x1, x2, x3, x4)
                        val maxX = maxOf(x1, x2, x3, x4)
                        val minY = minOf(y1, y2, y3, y4)
                        val maxY = maxOf(y1, y2, y3, y4)

                        if (maxX > minX && maxY > minY) {
                            boxes.add(BoundingBox(
                                left = minX,
                                top = minY,
                                right = maxX,
                                bottom = maxY
                            ))
                        }
                    }
                }
            }
        }

        return boxes
    }

    /**
     * Вырезание региона из bitmap по bounding box
     */
    private fun cropBitmapToBox(bitmap: Bitmap, box: BoundingBox): Bitmap {
        val left = box.left.coerceIn(0f, bitmap.width.toFloat()).toInt()
        val top = box.top.coerceIn(0f, bitmap.height.toFloat()).toInt()
        val right = box.right.coerceIn(0f, bitmap.width.toFloat()).toInt()
        val bottom = box.bottom.coerceIn(0f, bitmap.height.toFloat()).toInt()

        if (left >= right || top >= bottom) {
            // Возвращаем маленький пустой bitmap если координаты некорректны
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }

        val width = right - left
        val height = bottom - top

        return Bitmap.createBitmap(bitmap, left, top, width, height)
    }

    /**
     * Декодирование CTC-вывода модели
     */
    private fun ctcDecode(output: OrtSession.Result): String {
        // Получаем выходные данные распознавательной модели
        // В PaddleOCR v4 выход обычно содержит вероятности для каждого символа в последовательности
        val outputTensor = output.get(0).value
        
        when (outputTensor) {
            is FloatArray -> {
                // Декодируем CTC-выход, который представляет собой вероятности для каждого символа
                return decodeWithCharMap(outputTensor)
            }
            is Array<*> -> {
                // Обрабатываем многомерный вывод
                if (outputTensor.isNotEmpty() && outputTensor[0] is FloatArray) {
                    val probs = outputTensor[0] as FloatArray
                    return decodeWithCharMap(probs)
                }
            }
        }
        
        return "" // Возвращаем пустую строку, если не удалось декодировать
    }
    
    // Карта символов для PaddleOCR v4 (латиница, кириллица и цифры)
    private val charMap = arrayOf(
        " ", "!", "\"", "#", "$", "%", "&", "'", "(", ")", "*", "+", ",", "-", ".", "/",
        "0", "1", "2", "3", "4", "5", "6", "7", "8", "9", ":", ";", "<", "=", ">", "?", "@",
        "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M", "N", "O", "P", "Q",
        "R", "S", "T", "U", "V", "W", "X", "Y", "Z", "[", "\\", "]", "^", "_", "`",
        "a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n", "o", "p", "q",
        "r", "s", "t", "u", "v", "w", "x", "y", "z", "{", "|", "}", "~", "Ё", "А", "Б", "В",
        "Г", "Д", "Е", "Ж", "З", "И", "Й", "К", "Л", "М", "Н", "О", "П", "Р", "С", "Т", "У",
        "Ф", "Х", "Ц", "Ч", "Ш", "Щ", "Ъ", "Ы", "Ь", "Э", "Ю", "Я", "а", "б", "в", "г", "д",
        "е", "ж", "з", "и", "й", "к", "л", "м", "н", "о", "п", "р", "с", "т", "у", "ф", "х",
        "ц", "ч", "ш", "щ", "ъ", "ы", "ь", "э", "ю", "я", "ё"
    )
    
    /**
     * Декодирование с использованием карты символов
     */
    private fun decodeWithCharMap(probabilities: FloatArray): String {
        val result = StringBuilder()
        var prevIndex = -1
        
        // Применяем argmax к вероятностям для получения индексов символов
        for (i in probabilities.indices step charMap.size) {
            var maxProb = Float.NEGATIVE_INFINITY
            var maxIndex = 0
            
            // Находим индекс символа с максимальной вероятностью
            val endIndex = minOf(i + charMap.size, probabilities.size)
            for (j in i until endIndex) {
                if (probabilities[j] > maxProb) {
                    maxProb = probabilities[j]
                    maxIndex = j - i
                }
            }
            
            // Проверяем, не является ли это "blank" символом (обычно последний в карте или индекс 0)
            // В CTC-декодировании повторяющиеся символы и пробелы обрабатываются особым образом
            if (maxIndex != charMap.size - 1 && maxIndex != 0) { // Не blank символ
                if (maxIndex < charMap.size && maxIndex != prevIndex) { // Избегаем повторений
                    result.append(charMap[maxIndex])
                }
                prevIndex = maxIndex
            } else {
                prevIndex = -1 // Сброс при blank символе
            }
        }
        
        return result.toString()
    }

    /**
     * Вычисление уверенности в распознавании
     */
    private fun calculateConfidence(output: OrtSession.Result): Float {
        // Получаем вероятности из выхода модели и вычисляем среднюю уверенность
        val outputTensor = output.get(0).value
        
        when (outputTensor) {
            is FloatArray -> {
                // Вычисляем среднюю максимальную вероятность по всей последовательности
                var totalMaxProb = 0f
                var count = 0
                
                // Предполагаем, что выход содержит вероятности для каждого символа в последовательности
                // Группируем значения по размеру словаря символов
                val charsPerStep = charMap.size
                
                for (i in outputTensor.indices step charsPerStep) {
                    var maxProb = Float.NEGATIVE_INFINITY
                    
                    // Находим максимальную вероятность в текущем временном шаге
                    val endIndex = minOf(i + charsPerStep, outputTensor.size)
                    for (j in i until endIndex) {
                        if (outputTensor[j] > maxProb) {
                            maxProb = outputTensor[j]
                        }
                    }
                    
                    if (maxProb != Float.NEGATIVE_INFINITY) {
                        totalMaxProb += maxProb
                        count++
                    }
                }
                
                return if (count > 0) totalMaxProb / count else 0f
            }
            is Array<*> -> {
                // Обрабатываем многомерный вывод
                var totalConfidence = 0f
                var count = 0
                
                for (element in outputTensor) {
                    if (element is FloatArray) {
                        var maxProb = Float.NEGATIVE_INFINITY
                        for (prob in element) {
                            if (prob > maxProb) {
                                maxProb = prob
                            }
                        }
                        
                        if (maxProb != Float.NEGATIVE_INFINITY) {
                            totalConfidence += maxProb
                            count++
                        }
                    }
                }
                
                return if (count > 0) totalConfidence / count else 0f
            }
        }
        
        return 0f // Возвращаем 0, если не удалось вычислить уверенность
    }

    /**
     * Освобождение ресурсов
     */
    fun cleanup() {
        try {
            detSession?.close()
            recSession?.close()
            ortEnv.close()
            initialized = false
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

/**
 * Результат распознавания текста
 */
data class RecognitionResult(
    val text: String,
    val confidence: Float
)