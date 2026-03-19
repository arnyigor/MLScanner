// ============================================================
// data/ocr/engine/HybridEngine.kt — Tesseract приоритетный
// ============================================================
package com.arny.mlscanner.data.ocr.engine

import android.graphics.Bitmap
import android.util.Log
import com.arny.mlscanner.domain.models.OcrResult

/**
 * Гибридный OCR-движок.
 *
 * ▶ КЛЮЧЕВОЕ ИЗМЕНЕНИЕ:
 * Tesseract ПЕРВЫЙ (он умеет rus+eng),
 * ML Kit ТОЛЬКО как fallback (и только для латиницы).
 *
 * ML Kit Latin model НЕ распознаёт кириллицу —
 * она превращает «Письмо» в «IluCbMO».
 * Поэтому для русского текста Tesseract единственный вариант.
 */
class HybridEngine(
    private val mlkit: OcrEngine,
    private val tesseract: OcrEngine
) : OcrEngine {

    override val name = "Hybrid (Tesseract + ML Kit)"

    companion object {
        private const val TAG = "HybridEngine"
        private const val MIN_CONFIDENCE = 0.40f
        private const val MIN_TEXT_LENGTH = 3
    }

    override suspend fun initialize(): Boolean {
        return tesseract.isReady() || mlkit.isReady()
    }

    override fun isReady(): Boolean = tesseract.isReady() || mlkit.isReady()

    override suspend fun recognize(bitmap: Bitmap): OcrResult {
        val totalStart = System.currentTimeMillis()

        // ▶ ШАГИ ИЗМЕНЕНЫ: Tesseract ПЕРВЫЙ

        // Шаг 1: Tesseract (основной для русского)
        var tessResult: OcrResult? = null
        if (tesseract.isReady()) {
            try {
                tessResult = tesseract.recognize(bitmap)
                Log.d(TAG, "Tesseract: conf=${tessResult.averageConfidence}, " +
                    "words=${tessResult.wordCount}, " +
                    "text=${tessResult.fullText.take(50)}...")

                // Если Tesseract дал хороший результат — сразу возвращаем
                if (isResultAcceptable(tessResult)) {
                    val totalTime = System.currentTimeMillis() - totalStart
                    return tessResult.copy(
                        processingTimeMs = totalTime,
                        engineName = "Hybrid → Tesseract"
                    )
                }

                Log.d(TAG, "Tesseract result not great, trying ML Kit as supplement...")
            } catch (e: Exception) {
                Log.w(TAG, "Tesseract failed", e)
            }
        }

        // Шаг 2: ML Kit (fallback — только если Tesseract совсем плох)
        var mlkitResult: OcrResult? = null
        if (mlkit.isReady()) {
            try {
                mlkitResult = mlkit.recognize(bitmap)
                Log.d(TAG, "ML Kit: conf=${mlkitResult.averageConfidence}, " +
                    "words=${mlkitResult.wordCount}")
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit failed", e)
            }
        }

        // Шаг 3: Выбор лучшего
        val best = selectBest(tessResult, mlkitResult)
        val totalTime = System.currentTimeMillis() - totalStart

        return (best ?: OcrResult.EMPTY).copy(
            processingTimeMs = totalTime,
            engineName = "Hybrid → ${best?.engineName ?: "None"}"
        )
    }

    /**
     * Проверка: приемлем ли результат Tesseract.
     * Порог ниже чем раньше — Tesseract уже даёт русский текст.
     */
    private fun isResultAcceptable(result: OcrResult): Boolean {
        if (result.isEmpty) return false
        if (result.fullText.trim().length < MIN_TEXT_LENGTH) return false
        if (result.averageConfidence < MIN_CONFIDENCE) return false
        return true
    }

    /**
     * Выбор лучшего результата.
     *
     * ▶ КЛЮЧЕВОЕ ИЗМЕНЕНИЕ:
     * Если в результате есть кириллица — отдаём предпочтение Tesseract,
     * потому что ML Kit Latin превращает кириллицу в мусор.
     */
    private fun selectBest(tess: OcrResult?, mlkit: OcrResult?): OcrResult? {
        if (tess == null && mlkit == null) return null
        if (tess == null) return mlkit
        if (mlkit == null) return tess
        if (tess.isEmpty && !mlkit.isEmpty) return mlkit
        if (mlkit.isEmpty && !tess.isEmpty) return tess

        // Если Tesseract нашёл кириллицу — однозначно он
        val hasCyrillic = tess.fullText.any { it in '\u0400'..'ӿ' }
        if (hasCyrillic) {
            Log.d(TAG, "Cyrillic detected in Tesseract → using Tesseract")
            return tess
        }

        // Если ML Kit нашёл подозрительный "транслит" — используем Tesseract
        val mlkitLooksLikeTranslit = detectTranslit(mlkit.fullText)
        if (mlkitLooksLikeTranslit) {
            Log.d(TAG, "ML Kit looks like translit → using Tesseract")
            return tess
        }

        // Для чисто латинского текста — сравниваем по score
        val tScore = score(tess)
        val mScore = score(mlkit)

        Log.d(TAG, "Score: Tess=$tScore, MLKit=$mScore")
        return if (tScore >= mScore) tess else mlkit
    }

    /**
     * Детекция "транслита" — когда ML Kit выдаёт латиницу
     * вместо кириллицы.
     *
     * Признаки транслита:
     * - Необычное смешение регистров: «IluCbMO», «MaTeMaTHKa»
     * - Много заглавных букв в середине слова
     * - Специфические паттерны: «Kb», «bI», «IO»
     */
    private fun detectTranslit(text: String): Boolean {
        if (text.isBlank()) return false

        // Считаем слова с необычным смешением регистров
        val words = text.split("\\s+".toRegex()).filter { it.length >= 3 }
        if (words.isEmpty()) return false

        var suspiciousWords = 0
        for (word in words) {
            val hasLower = word.any { it.isLowerCase() }
            val hasUpper = word.any { it.isUpperCase() }

            if (hasLower && hasUpper) {
                // Заглавная НЕ только первая буква
                val upperInMiddle = word.drop(1).count { it.isUpperCase() }
                if (upperInMiddle >= 2) {
                    suspiciousWords++
                }
            }
        }

        val ratio = suspiciousWords.toFloat() / words.size

        // Специфические паттерны кириллицы→латиницы
        val translitPatterns = listOf(
            "bI", "Kb", "IO", "bl", "3e", "3a",
            "YI", "Hl", "Hb", "CTp", "lO"
        )
        val hasTranslitPatterns = translitPatterns.any { text.contains(it) }

        val isSuspicious = ratio > 0.3f || hasTranslitPatterns
        if (isSuspicious) {
            Log.d(TAG, "Translit detection: ratio=$ratio, " +
                "patterns=$hasTranslitPatterns → SUSPICIOUS")
        }

        return isSuspicious
    }

    private fun score(result: OcrResult): Float {
        val confScore = result.averageConfidence * 0.5f
        val wordScore = (result.wordCount.coerceAtMost(50) / 50f) * 0.2f

        val cleanChars = result.fullText.count {
            it.isLetterOrDigit() || it.isWhitespace() || it in ",.;:!?()-\"'«»—–/"
        }
        val cleanScore = if (result.fullText.isNotEmpty()) {
            (cleanChars.toFloat() / result.fullText.length) * 0.15f
        } else 0f

        val cyrillicChars = result.fullText.count { it in '\u0400'..'ӿ' }
        val cyrillicScore = if (result.fullText.isNotEmpty()) {
            (cyrillicChars.toFloat() / result.fullText.length)
                .coerceAtMost(1f) * 0.15f
        } else 0f

        return confScore + wordScore + cleanScore + cyrillicScore
    }

    override fun release() {
        Log.d(TAG, "Hybrid released")
    }
}
