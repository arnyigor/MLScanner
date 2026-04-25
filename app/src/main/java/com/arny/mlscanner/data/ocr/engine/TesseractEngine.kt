package com.arny.mlscanner.data.ocr.engine

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.TextBlock
import com.arny.mlscanner.domain.models.TextLine
import com.arny.mlscanner.domain.models.TextWord
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap
import com.arny.mlscanner.data.ocr.postprocessing.TextPostProcessor

class TesseractEngine(private val context: Context) : OcrEngine {

    override val name = "Tesseract"

    companion object {
        private const val TAG = "TesseractEngine"
        private const val TESSDATA_DIR = "tessdata"
        private const val LANGUAGES = "rus+eng"

        private val GARBAGE_PATTERN = Regex("[|\\[\\]{}~`^\\\\]{2,}")
        private const val MIN_WORD_CONFIDENCE = 15f

        private const val MAX_SIDE = 3000           // Даунскейл выше этого
        private const val MAX_PIXELS = 8_000_000L   // 8MP макс (увеличен для апскейла)
        private const val MIN_SHORT_SIDE = 400      // Апскейл если короткая < этого
        private const val TARGET_SHORT_SIDE = 600   // Апскейлим до этого
        private const val MAX_UPSCALE = 3f          // Макс множитель апскейла

        // Scoring weights
        private const val WEIGHT_CONFIDENCE = 1.2f
        private const val WEIGHT_WORDS = 1.5f
        private const val WEIGHT_CYRILLIC_RATIO = 20f
        private const val WEIGHT_GARBAGE_PENALTY = 30f
    }

    private var tessApi: TessBaseAPI? = null
    private var ready = false
    private val mutex = Mutex()

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (ready) return@withContext true

            // Детект ARM-translation эмулятора (x86_64 с ARM libs)
            if (isArmTranslationEmulator()) {
                Log.w(TAG, "ARM-translation emulator detected, Tesseract disabled")
                return@withContext false
            }

            try {
                val dataPath = copyTessDataSafe()

                // ▶ FIX: Пробуем инициализировать и тестировать
                val api = TessBaseAPI()

                if (!api.init(dataPath, LANGUAGES)) {
                    Log.e(TAG, "Tesseract init failed for $LANGUAGES")
                    return@withContext false
                }

                // Базовые настройки
                api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
                api.setVariable("preserve_interword_spaces", "1")
                api.setVariable("textord_heavy_nr", "1")
                api.setVariable("textord_min_xheight", "6")

                // ▶ FIX: Тестируем работоспособность
                val testBitmap = createTestBitmap()
                api.setImage(testBitmap)
                val testText = api.utF8Text.orEmpty()
                api.clear()
                testBitmap.recycle()

                if (testText.isBlank()) {
                    Log.w(TAG, "Tesseract test failed WITH dictionaries, " +
                            "trying WITHOUT")
                    // Словари не работают (tessdata_fast) → отключаем
                    api.setVariable("load_system_dawg", "0")
                    api.setVariable("load_freq_dawg", "0")

                    // Повторный тест
                    val testBitmap2 = createTestBitmap()
                    api.setImage(testBitmap2)
                    val testText2 = api.utF8Text.orEmpty()
                    api.clear()
                    testBitmap2.recycle()

                    if (testText2.isBlank()) {
                        Log.e(TAG, "Tesseract test failed even WITHOUT " +
                                "dictionaries!")
                        // Может быть проблема с traineddata
                        api.recycle()
                        return@withContext false
                    }

                    Log.i(TAG, "Tesseract works WITHOUT dictionaries: " +
                            "'${testText2.trim()}'")
                } else {
                    Log.i(TAG, "Tesseract works WITH dictionaries: " +
                            "'${testText.trim()}'")
                }

                tessApi = api
                ready = true
                Log.i(TAG, "Tesseract initialized: $LANGUAGES")
                return@withContext true
            } catch (e: Exception) {
                Log.e(TAG, "Tesseract init error", e)
                return@withContext false
            }
        }
    }

    /**
     * Создаёт тестовое изображение "Test Тест 123"
     * для проверки работоспособности Tesseract.
     */
    private fun createTestBitmap(): Bitmap {
        val width = 400
        val height = 60
        val bitmap = createBitmap(width, height)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)

        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.BLACK
            textSize = 36f
            isAntiAlias = true
        }
        canvas.drawText("Test Тест 123", 10f, 42f, paint)
        return bitmap
    }

    /**
     * Детектит ARM-translation эмулятор (x86_64 с ARM native libs).
     * На таких эмуляторах Tesseract падает с SIGILL в ndk_translation.
     */
    private fun isArmTranslationEmulator(): Boolean {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return false
        val isX86 = abi.contains("x86")
        val hasArmLibs = Build.SUPPORTED_ABIS.any { it.contains("arm") }
        val isEmulator = Build.FINGERPRINT.contains("generic") ||
                         Build.FINGERPRINT.contains("emulator") ||
                         Build.MODEL.contains("Emulator") ||
                         Build.MODEL.contains("Android SDK")
        
        val result = isX86 && hasArmLibs && isEmulator
        if (result) {
            Log.w(TAG, "ARM-translation detected: abi=$abi, " +
                "supported=${Build.SUPPORTED_ABIS.joinToString()}, " +
                "model=${Build.MODEL}")
        }
        return result
    }

    private fun configureApi(api: TessBaseAPI) {
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO

        api.setVariable("preserve_interword_spaces", "1")

        api.setVariable("textord_heavy_nr", "1")

        api.setVariable("textord_min_xheight", "6")
    }

    override fun isReady(): Boolean = ready

    override suspend fun recognize(
        bitmap: Bitmap,
        handwrittenMode: Boolean
    ): OcrResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            recognizeInternal(bitmap, handwrittenMode)
        }
    }

    /**
     * Multi-pass распознавание с выбором лучшего результата.
     * 
     * Запускает несколько профилей параллельно и выбирает лучший по scoring.
     */
    suspend fun recognizeMultiPass(
        bitmap: Bitmap,
        profiles: List<TesseractProfile>
    ): OcrCandidate = withContext(Dispatchers.Default) {
        if (profiles.isEmpty()) {
            val defaultResult = recognize(bitmap, false)
            return@withContext OcrCandidate(
                profile = TesseractProfile.RUSSIAN_PROFILES.first(),
                result = defaultResult,
                score = scoreResult(defaultResult)
            )
        }

        val candidates = profiles.map { profile ->
            val result = recognizeWithProfile(bitmap, profile)
            val score = scoreResult(result)
            OcrCandidate(profile, result, score)
        }

        val best = candidates.maxByOrNull { it.score } ?: candidates.first()
        
        Log.d(TAG, "Multi-pass: ${candidates.size} profiles, best='${best.profile.name}' " +
                "score=${best.score}, conf=${best.confidence}, words=${best.wordCount}")
        
        best
    }

    /**
     * Распознавание с конкретным профилем.
     */
    private suspend fun recognizeWithProfile(
        bitmap: Bitmap,
        profile: TesseractProfile
    ): OcrResult = mutex.withLock {
        withContext(Dispatchers.Default) {
            val api = tessApi
            if (api == null || !ready) {
                Log.w(TAG, "Tesseract not ready")
                return@withContext OcrResult.EMPTY
            }

            val startTime = System.currentTimeMillis()
            
            // Применяем preprocessing согласно профилю
            val preprocessed = applyPreprocessing(bitmap, profile.preprocessMode)
            val optimized = ensureOptimalSize(preprocessed)
            val safeBitmap = ensureSafeBitmap(optimized)

            try {
                // Устанавливаем язык
                if (api.init(context.filesDir.absolutePath, profile.language.code)) {
                    api.pageSegMode = profile.psm
                    api.setVariable("preserve_interword_spaces", "1")
                    api.setVariable("textord_heavy_nr", "1")
                    api.setVariable("textord_min_xheight", "6")
                }

                try {
                    api.setImage(safeBitmap)
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Native crash in setImage, profile ${profile.name}", e)
                    api.clear()
                    return@withContext OcrResult.EMPTY.copy(
                        processingTimeMs = System.currentTimeMillis() - startTime,
                        engineName = "$name[${profile.name}:CRASHED]"
                    )
                } catch (e: Error) {
                    Log.e(TAG, "Fatal error in setImage, profile ${profile.name}", e)
                    api.clear()
                    return@withContext OcrResult.EMPTY.copy(
                        processingTimeMs = System.currentTimeMillis() - startTime,
                        engineName = "$name[${profile.name}:ERROR]"
                    )
                }

                val rawFullText = try {
                    api.utF8Text.orEmpty()
                } catch (e: UnsatisfiedLinkError) {
                    Log.e(TAG, "Native crash in utF8Text, profile ${profile.name}", e)
                    api.clear()
                    return@withContext OcrResult.EMPTY.copy(
                        processingTimeMs = System.currentTimeMillis() - startTime,
                        engineName = "$name[${profile.name}:CRASHED]"
                    )
                } catch (e: Error) {
                    Log.e(TAG, "Fatal error in utF8Text, profile ${profile.name}", e)
                    api.clear()
                    return@withContext OcrResult.EMPTY.copy(
                        processingTimeMs = System.currentTimeMillis() - startTime,
                        engineName = "$name[${profile.name}:ERROR]"
                    )
                }

                if (rawFullText.isBlank()) {
                    api.clear()
                    return@withContext OcrResult.EMPTY.copy(
                        processingTimeMs = System.currentTimeMillis() - startTime,
                        engineName = "$name[${profile.name}]"
                    )
                }

                val meanConf = api.meanConfidence()
                val rawWords = extractWords(api)
                api.clear()

                val cleanedWords = postProcessWords(rawWords)
                val blocks = buildBlocksFromWords(cleanedWords)
                
                // Используем rawFullText как основной источник (сохраняет reading order)
                val rawText = TextPostProcessor.normalizeTesseractText(rawFullText)
                val boxedText = TextPostProcessor.normalizeTesseractText(buildFullTextFromBlocks(blocks))
                val fullText = chooseBestText(rawText, boxedText)
                
                val avgConf = if (cleanedWords.isNotEmpty()) {
                    cleanedWords.map { it.confidence / 100f }.average().toFloat()
                } else meanConf / 100f

                val elapsed = System.currentTimeMillis() - startTime

                return@withContext OcrResult(
                    blocks = blocks,
                    fullText = fullText,
                    formattedText = fullText,
                    averageConfidence = avgConf,
                    processingTimeMs = elapsed,
                    engineName = "$name[${profile.name}]",
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height
                )

            } catch (e: Exception) {
                Log.e(TAG, "Recognition error with profile ${profile.name}", e)
                api.clear()
                return@withContext OcrResult.EMPTY.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    engineName = "$name[${profile.name}]"
                )
            } finally {
                if (safeBitmap !== bitmap && safeBitmap !== optimized && safeBitmap !== preprocessed) {
                    safeBitmap.recycle()
                }
                if (optimized !== bitmap && optimized !== preprocessed) {
                    optimized.recycle()
                }
                if (preprocessed !== bitmap) {
                    preprocessed.recycle()
                }
            }
        }
    }

    /**
     * Scoring результата OCR.
     * 
     * Учитывает:
     * - Confidence
     * - Количество слов
     * - Соотношение кириллицы (для русского)
     * - Наличие мусорных символов
     * - Структурные паттерны (даты, метки, URL)
     */
    private fun scoreResult(result: OcrResult): Float {
        val text = result.fullText
        if (text.isBlank()) return 0f

        val words = text.split(Regex("\\s+")).count { it.length > 1 }
        val letters = text.count { it.isLetter() }.coerceAtLeast(1)
        val cyrillicCount = text.count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
        val garbageCount = text.count { it == '�' || it == '|' || it == '~' }

        val cyrillicRatio = cyrillicCount.toFloat() / letters
        val garbageRatio = garbageCount.toFloat() / text.length.coerceAtLeast(1)
        val structureBonus = documentStructureBonus(text)

        return result.averageConfidence * 100f * WEIGHT_CONFIDENCE +
                words * WEIGHT_WORDS +
                cyrillicRatio * WEIGHT_CYRILLIC_RATIO -
                garbageRatio * WEIGHT_GARBAGE_PENALTY +
                structureBonus
    }

    /**
     * Бонус за наличие структурных паттернов в документе.
     * Использует ТОЛЬКО универсальные паттерны, не привязывается к конкретным словам.
     */
    private fun documentStructureBonus(text: String): Float {
        var bonus = 0f

        // Паттерн: дата (число + месяц)
        if (text.contains(Regex("""\d{1,2}\s+[а-яё]+""", RegexOption.IGNORE_CASE))) {
            bonus += 5f
        }

        // Паттерн: метки с двоеточием ("Задание:", "Примечание:", etc)
        val labelCount = Regex("""[А-ЯЁA-Z][а-яёa-z\s]+:""").findAll(text).count()
        bonus += labelCount * 3f

        // Паттерн: URL
        if (text.contains(Regex("""https?://"""))) {
            bonus += 8f
        }

        // Паттерн: номера (№)
        if (text.contains(Regex("""№\s*\d+"""))) {
            bonus += 3f
        }

        // Паттерн: нумерованные списки
        val listItemCount = Regex("""^\s*[0-9]+[.)]""", RegexOption.MULTILINE).findAll(text).count()
        bonus += listItemCount * 2f

        return bonus.coerceAtMost(40f) // Ограничиваем максимальный бонус
    }

    /**
     * Выбирает лучший текст между raw и boxed.
     * Raw text обычно лучше сохраняет reading order.
     */
    private fun chooseBestText(rawText: String, boxedText: String): String {
        if (rawText.isBlank()) return boxedText
        if (boxedText.isBlank()) return rawText

        val rawWords = countWords(rawText)
        val boxedWords = countWords(boxedText)

        // Raw text предпочтительнее если он не сильно короче
        return if (rawWords >= boxedWords * 0.85f) {
            rawText
        } else {
            boxedText
        }
    }

    private fun countWords(text: String): Int {
        return text.split(Regex("\\s+")).count { it.length > 1 }
    }

    /**
     * Применяет preprocessing согласно режиму.
     */
    private fun applyPreprocessing(bitmap: Bitmap, mode: PreprocessMode): Bitmap {
        return when (mode) {
            PreprocessMode.ORIGINAL -> bitmap
            PreprocessMode.CONTRAST_ENHANCED -> applyContrastEnhancement(bitmap)
            PreprocessMode.ADAPTIVE_THRESHOLD -> applyAdaptiveThreshold(bitmap)
            PreprocessMode.UPSCALE_2X -> bitmap.scale(bitmap.width * 2, bitmap.height * 2)
            PreprocessMode.SHARPEN_LIGHT -> applySharpen(bitmap, 0.5f)
        }
    }

    private fun applyContrastEnhancement(bitmap: Bitmap): Bitmap {
        // Простое усиление контраста через ColorMatrix
        val result = createBitmap(bitmap.width, bitmap.height)
        val canvas = android.graphics.Canvas(result)
        val paint = android.graphics.Paint()
        
        val cm = android.graphics.ColorMatrix(floatArrayOf(
            1.5f, 0f, 0f, 0f, -64f,
            0f, 1.5f, 0f, 0f, -64f,
            0f, 0f, 1.5f, 0f, -64f,
            0f, 0f, 0f, 1f, 0f
        ))
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(cm)
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return result
    }

    private fun applyAdaptiveThreshold(bitmap: Bitmap): Bitmap {
        // Заглушка - требует OpenCV, пока возвращаем оригинал
        return bitmap
    }

    private fun applySharpen(bitmap: Bitmap, level: Float): Bitmap {
        // Заглушка - требует OpenCV, пока возвращаем оригинал
        return bitmap
    }

    private fun recognizeInternal(
        bitmap: Bitmap,
        handwrittenMode: Boolean
    ): OcrResult {
        val api = tessApi
        if (api == null || !ready) {
            Log.w(TAG, "Tesseract not ready")
            return OcrResult.EMPTY
        }

        val startTime = System.currentTimeMillis()
        val optimized = ensureOptimalSize(bitmap)
        val safeBitmap = ensureSafeBitmap(optimized)

        try {
            if (handwrittenMode) {
                api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            } else {
                api.pageSegMode = selectPsm(safeBitmap)
            }

            try {
                api.setImage(safeBitmap)
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native crash in setImage (ARM-translation issue)", e)
                api.clear()
                return OcrResult.EMPTY.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    engineName = "$name[CRASHED]"
                )
            } catch (e: Error) {
                Log.e(TAG, "Fatal error in setImage", e)
                api.clear()
                return OcrResult.EMPTY.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    engineName = "$name[ERROR]"
                )
            }

            val rawFullText = try {
                api.utF8Text.orEmpty()
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Native crash in utF8Text (ARM-translation issue)", e)
                api.clear()
                return OcrResult.EMPTY.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    engineName = "$name[CRASHED]"
                )
            } catch (e: Error) {
                Log.e(TAG, "Fatal error in utF8Text", e)
                api.clear()
                return OcrResult.EMPTY.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    engineName = "$name[ERROR]"
                )
            }

            if (rawFullText.isBlank()) {
                Log.w(TAG, "Tesseract returned empty text")
                api.clear()
                return OcrResult.EMPTY.copy(
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    engineName = name
                )
            }

            val meanConf = api.meanConfidence()

            val rawWords = extractWords(api)

            api.clear()

            val cleanedWords = postProcessWords(rawWords)
            val blocks = buildBlocksFromWords(cleanedWords)
            
            // Используем rawFullText как основной источник (сохраняет reading order)
            val rawText = TextPostProcessor.normalizeTesseractText(rawFullText)
            val boxedText = TextPostProcessor.normalizeTesseractText(buildFullTextFromBlocks(blocks))
            val fullText = chooseBestText(rawText, boxedText)
            
            val avgConf = if (cleanedWords.isNotEmpty()) {
                cleanedWords.map { it.confidence / 100f }.average().toFloat()
            } else meanConf / 100f

            Log.d(TAG, "Raw: ${rawWords.size}, clean: ${cleanedWords.size}, " +
                "conf=$meanConf, text='${fullText.take(80)}...'")

            val elapsed = System.currentTimeMillis() - startTime

            val baseResult = OcrResult(
                blocks = blocks,
                fullText = fullText,
                formattedText = fullText,
                averageConfidence = avgConf,
                processingTimeMs = elapsed,
                engineName = name,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height
            )

            return improveStructuredDocumentResult(api, safeBitmap, baseResult, startTime)

        } catch (e: Exception) {
            Log.e(TAG, "Recognition error", e)
            api.clear()
            return OcrResult.EMPTY.copy(
                processingTimeMs = System.currentTimeMillis() - startTime,
                engineName = name
            )
        } finally {
            if (safeBitmap !== bitmap && safeBitmap !== optimized) safeBitmap.recycle()
            if (optimized !== bitmap) optimized.recycle()
        }
    }

    /**
     * Масштабирование до оптимального размера для Tesseract.
     *
     * Tesseract требует минимум ~30-40px высоты символа.
     * Для надёжного распознавания нужно минимум 400px по меньшей стороне.
     *
     * ПРАВИЛА:
     * 1. Если minSide < 400 → апскейл до 600px по короткой стороне
     *    (это главное исправление — узкие полоски увеличиваются)
     * 2. Если maxSide > 3000 → даунскейл
     * 3. Общее количество пикселей ≤ 6MP
     * 4. Максимальный апскейл ×3
     */
    private fun improveStructuredDocumentResult(
        api: TessBaseAPI,
        bitmap: Bitmap,
        baseResult: OcrResult,
        startTime: Long
    ): OcrResult {
        if (!shouldTryStructuredDocumentPass(bitmap, baseResult.fullText)) {
            return baseResult
        }

        val topCrop = cropRelative(bitmap, 0.08f, 0.04f, 0.88f, 0.18f)
        val textCrop = cropRelative(bitmap, 0.34f, 0.13f, 0.62f, 0.78f)

        return try {
            val topText = recognizeTextVariant(
                api = api,
                bitmap = topCrop,
                psm = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            )
            val textBlock = recognizeTextVariant(
                api = api,
                bitmap = textCrop,
                psm = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            )
            val textSparse = recognizeTextVariant(
                api = api,
                bitmap = textCrop,
                psm = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
            )

            val bestTextArea = listOf(textBlock, textSparse)
                .maxByOrNull { structuredDocumentScore(it) }
                .orEmpty()
            val candidateText = mergeStructuredTexts(topText, bestTextArea, baseResult.fullText)

            val baseScore = structuredDocumentScore(baseResult.fullText)
            val candidateScore = structuredDocumentScore(candidateText)

            if (candidateText.isNotBlank() && candidateScore > baseScore + 4f) {
                Log.d(TAG, "Structured document pass selected: base=$baseScore, candidate=$candidateScore")
                baseResult.copy(
                    fullText = candidateText,
                    formattedText = candidateText,
                    processingTimeMs = System.currentTimeMillis() - startTime,
                    engineName = "$name[structured]"
                )
            } else {
                Log.d(TAG, "Structured document pass skipped: base=$baseScore, candidate=$candidateScore")
                baseResult
            }
        } catch (e: Exception) {
            Log.w(TAG, "Structured document pass failed", e)
            try {
                api.clear()
            } catch (_: Exception) {
            }
            baseResult
        } finally {
            if (topCrop !== bitmap && !topCrop.isRecycled) topCrop.recycle()
            if (textCrop !== bitmap && !textCrop.isRecycled) textCrop.recycle()
        }
    }

    private fun shouldTryStructuredDocumentPass(bitmap: Bitmap, text: String): Boolean {
        if (isArmTranslationRuntime()) return false

        val ratio = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
        if (ratio !in 1.15f..2.35f) return false

        val dateCount = Regex("""\d{2}[.\s]\d{2}[.\s]\d{4}""").findAll(text).count()
        val fieldCount = Regex("""(?i)(?:^|\s)\d+[a-z]?[.)]?""").findAll(text).count()
        val hasLatin = text.any { it in 'A'..'Z' || it in 'a'..'z' }
        val hasCyrillic = text.any { it.code in 0x0400..0x04FF }

        return dateCount >= 2 || fieldCount >= 3 || (dateCount >= 1 && hasLatin && hasCyrillic)
    }

    private fun isArmTranslationRuntime(): Boolean {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir.orEmpty()
        val isX86System = Build.SUPPORTED_ABIS.any { it == "x86_64" || it == "x86" }
        val runsArmLibs = nativeLibDir.contains("arm64") || nativeLibDir.contains("arm")
        return isX86System && runsArmLibs
    }

    private fun recognizeTextVariant(
        api: TessBaseAPI,
        bitmap: Bitmap,
        psm: Int
    ): String {
        api.pageSegMode = psm
        api.setVariable("preserve_interword_spaces", "1")
        api.setVariable("textord_heavy_nr", "1")
        api.setVariable("textord_min_xheight", "6")

        api.setImage(bitmap)
        val rawText = api.utF8Text.orEmpty()
        api.clear()

        return TextPostProcessor.normalizeTesseractText(rawText)
    }

    private fun cropRelative(
        bitmap: Bitmap,
        left: Float,
        top: Float,
        width: Float,
        height: Float
    ): Bitmap {
        val x = (bitmap.width * left).toInt().coerceIn(0, bitmap.width - 1)
        val y = (bitmap.height * top).toInt().coerceIn(0, bitmap.height - 1)
        val right = (bitmap.width * (left + width)).toInt().coerceIn(x + 1, bitmap.width)
        val bottom = (bitmap.height * (top + height)).toInt().coerceIn(y + 1, bitmap.height)
        return Bitmap.createBitmap(bitmap, x, y, right - x, bottom - y)
    }

    private fun mergeStructuredTexts(vararg texts: String): String {
        val seen = linkedSetOf<String>()
        val lines = mutableListOf<String>()

        for (text in texts) {
            text.lines()
                .map { it.trim() }
                .filter { it.length >= 2 }
                .forEach { line ->
                    val key = line
                        .lowercase()
                        .replace(Regex("""[^\p{L}\p{N}]+"""), "")
                    if (key.length >= 2 && seen.add(key)) {
                        lines += line
                    }
                }
        }

        return lines.joinToString("\n").trim()
    }

    private fun structuredDocumentScore(text: String): Float {
        if (text.isBlank()) return 0f

        val dateCount = Regex("""\d{2}[.\s]\d{2}[.\s]\d{4}""").findAll(text).count()
        val fieldCount = Regex("""(?i)(?:^|\s)\d+[a-z]?[.)]?""").findAll(text).count()
        val latinUpperWords = Regex("""\b[A-Z]{2,}\b""").findAll(text).count()
        val cyrillicUpperWords = Regex("""\b[\u0400-\u04FF]{2,}\b""").findAll(text).count()
        val lineCount = text.lines().count { it.isNotBlank() }
        val garbageCount = text.count { it == '[' || it == ']' || it == '|' || it == '~' }

        return dateCount * 8f +
            fieldCount * 4f +
            latinUpperWords.coerceAtMost(12) * 1.2f +
            cyrillicUpperWords.coerceAtMost(12) * 1.2f +
            lineCount.coerceAtMost(16) * 1.5f -
            garbageCount * 2f
    }

    private fun ensureOptimalSize(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val maxSide = maxOf(w, h)
        val minSide = minOf(w, h)

        // ▶ Приоритет 1: Апскейл по короткой стороне
        if (minSide < MIN_SHORT_SIDE) {
            val scale = (TARGET_SHORT_SIDE.toFloat() / minSide)
                .coerceAtMost(MAX_UPSCALE)
            val newW = (w * scale).toInt()
            val newH = (h * scale).toInt()

            if (newW.toLong() * newH.toLong() <= MAX_PIXELS) {
                Log.d(TAG, "Upscale: ${w}x${h} → ${newW}x${newH} (minSide $minSide→${minOf(newW,newH)})")
                return bitmap.scale(newW, newH)
            }
        }

        // Приоритет 2: Даунскейл по длинной стороне
        if (maxSide > MAX_SIDE) {
            val scale = MAX_SIDE.toFloat() / maxSide
            val newW = (w * scale).toInt().coerceAtLeast(1)
            val newH = (h * scale).toInt().coerceAtLeast(1)
            Log.d(TAG, "Downscale: ${w}x${h} → ${newW}x${newH}")
            return bitmap.scale(newW, newH)
        }

        Log.d(TAG, "Size OK: ${w}x${h}")
        return bitmap
    }

    private fun selectPsm(bitmap: Bitmap): Int {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val ratio = w / h

        return when {
            // Очень вытянутое → скорее всего одна строка
            ratio !in 0.08f..12f -> {
                Log.d(TAG, "PSM: SINGLE_LINE (ratio=${"%.1f".format(ratio)})")
                TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
            }
            // Умеренно вытянутое → несколько строк, один блок
            ratio !in 0.25f..4f -> {
                Log.d(TAG, "PSM: SINGLE_BLOCK (ratio=${"%.1f".format(ratio)})")
                TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            }
            // Маленькое
            maxOf(bitmap.width, bitmap.height) < 500 -> {
                Log.d(TAG, "PSM: SINGLE_BLOCK (small)")
                TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK
            }
            // Стандартное
            else -> {
                Log.d(TAG, "PSM: AUTO (ratio=${"%.1f".format(ratio)})")
                TessBaseAPI.PageSegMode.PSM_AUTO
            }
        }
    }

    private fun postProcessWords(words: List<WordWithConf>): List<WordWithConf> {
        return words
            .filter { it.confidence >= MIN_WORD_CONFIDENCE }
            .filter { word ->
                val text = word.text.trim()
                text.isNotEmpty() && !(text.length == 1 && !text[0].isLetterOrDigit())
            }
            .filter { !GARBAGE_PATTERN.containsMatchIn(it.text) }
            .map { it.copy(text = cleanWordText(it.text)) }
            .filter { it.text.isNotBlank() }
    }

    private fun cleanWordText(text: String): String {
        var result = text.trim()
        result = result.trimStart { it in "|\\[]{}~`^" }
        result = result.trimEnd { it in "|\\[]{}~`^" }
        result = result.replace(Regex("(?<=\\d)l(?=\\d)"), "1")
        result = result.replace(Regex("(?<=\\d)O(?=\\d)"), "0")
        return result
    }

    private fun extractWords(api: TessBaseAPI): List<WordWithConf> {
        val words = mutableListOf<WordWithConf>()
        try {
            val iterator = api.resultIterator ?: return words
            iterator.begin()
            do {
                val level = TessBaseAPI.PageIteratorLevel.RIL_WORD
                val wordText = iterator.getUTF8Text(level)?.trim()
                if (!wordText.isNullOrBlank()) {
                    val conf = iterator.confidence(level)
                    val rect = iterator.getBoundingBox(level)
                    val box = if (rect != null && rect.size >= 4) {
                        BoundingBox(rect[0].toFloat(), rect[1].toFloat(),
                            rect[2].toFloat(), rect[3].toFloat())
                    } else BoundingBox.EMPTY
                    words.add(WordWithConf(wordText, conf, box))
                }
            } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
            iterator.delete()
        } catch (e: Exception) {
            Log.w(TAG, "Word extraction error", e)
        }
        return words
    }

    private fun buildBlocksFromWords(words: List<WordWithConf>): List<TextBlock> {
        if (words.isEmpty()) return emptyList()

        val sorted = words.sortedWith(
            compareBy<WordWithConf> { it.box.top }.thenBy { it.box.left }
        )

        val lines = mutableListOf<MutableList<WordWithConf>>()
        var currentLine = mutableListOf<WordWithConf>()

        for (word in sorted) {
            if (currentLine.isEmpty()) {
                currentLine.add(word)
                continue
            }
            val lastWord = currentLine.last()
            val lineHeight = maxOf(
                word.box.bottom - word.box.top,
                lastWord.box.bottom - lastWord.box.top
            ).coerceAtLeast(1f)
            val verticalGap = word.box.top - lastWord.box.top

            if (kotlin.math.abs(verticalGap) > lineHeight * 0.5f) {
                lines.add(currentLine)
                currentLine = mutableListOf(word)
            } else {
                currentLine.add(word)
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine)

        val textLines = lines.map { lineWords ->
            val lineText = lineWords.joinToString(" ") { it.text }
            val lineBox = lineWords.map { it.box }.reduce { acc, b -> acc.union(b) }
            val lineConf = lineWords.map { it.confidence / 100f }.average().toFloat()
            val textWords = lineWords.map { w -> TextWord(w.text, w.box, w.confidence / 100f) }
            TextLine(lineText, lineBox, textWords, lineConf)
        }

        if (textLines.isEmpty()) return emptyList()

        val blockText = textLines.joinToString("\n") { it.text }
        val blockBox = textLines.map { it.boundingBox }.reduce { acc, b -> acc.union(b) }
        val blockConf = textLines.map { it.confidence }.average().toFloat()

        return listOf(TextBlock(blockText, blockBox, textLines, blockConf))
    }

    private fun buildFullTextFromBlocks(blocks: List<TextBlock>): String {
        if (blocks.isEmpty()) return ""
        return blocks.joinToString("\n\n") { block ->
            block.lines.joinToString("\n") { it.text }
        }
    }

    private fun ensureSafeBitmap(source: Bitmap): Bitmap {
        val needsCopy = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                source.config == Bitmap.Config.HARDWARE -> true
            source.config != Bitmap.Config.ARGB_8888 -> true
            else -> false
        }
        return if (needsCopy) source.copy(Bitmap.Config.ARGB_8888, true) else source
    }

    private fun copyTessDataSafe(): String {
        val dataDir = File(context.filesDir, TESSDATA_DIR)
        if (!dataDir.exists()) dataDir.mkdirs()

        for (lang in listOf("rus", "eng")) {
            val fileName = "$lang.traineddata"
            val destFile = File(dataDir, fileName)
            if (destFile.exists() && destFile.length() > 100_000) continue
            if (destFile.exists()) destFile.delete()
            try {
                context.assets.open("$TESSDATA_DIR/$fileName").use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output, 8192)
                        output.flush()
                    }
                }
                Log.i(TAG, "$fileName copied (${destFile.length()} bytes)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy $fileName", e)
            }
        }
        return context.filesDir.absolutePath
    }

    override fun release() {
        try { tessApi?.recycle() } catch (_: Exception) {}
        tessApi = null
        ready = false
    }

    data class WordWithConf(
        val text: String,
        val confidence: Float,
        val box: BoundingBox
    )
}
