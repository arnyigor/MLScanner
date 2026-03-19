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

    override suspend fun recognize(bitmap: Bitmap, handwrittenMode: Boolean): OcrResult {
        val totalStart = System.currentTimeMillis()

        // ═══ ШАГ 1: Всегда запускаем Tesseract (основной для русского) ═══
        var tessResult: OcrResult? = null
        if (tesseract.isReady()) {
            try {
                tessResult = tesseract.recognize(bitmap, handwrittenMode)
                Log.d(TAG, "Tesseract: words=${tessResult.wordCount}, " +
                    "conf=${tessResult.averageConfidence}, " +
                    "script=${detectScript(tessResult.fullText)}, " +
                    "text='${tessResult.fullText.take(60)}...'")

                // Если Tesseract дал кириллицу — сразу возвращаем
                // НЕ тратим время на ML Kit (он всё равно не умеет русский)
                if (hasCyrillic(tessResult.fullText)) {
                    Log.i(TAG, "Cyrillic detected → using Tesseract directly")
                    val totalTime = System.currentTimeMillis() - totalStart
                    return tessResult.copy(
                        processingTimeMs = totalTime,
                        engineName = "Hybrid → Tesseract (cyrillic)"
                    )
                }

                // Если Tesseract дал хороший результат (даже без кириллицы)
                if (isResultAcceptable(tessResult)) {
                    // Проверяем: может ML Kit даст лучше для латиницы?
                    var mlkitResult: OcrResult? = null
                    if (mlkit.isReady()) {
                        try {
                            mlkitResult = mlkit.recognize(bitmap, handwrittenMode)
                        } catch (e: Exception) {
                            Log.w(TAG, "ML Kit failed", e)
                        }
                    }

                    // Если ML Kit выдал транслит-мусор — однозначно Tesseract
                    if (mlkitResult != null && isTranslitGarbage(mlkitResult.fullText)) {
                        Log.i(TAG, "ML Kit produced translit garbage → using Tesseract")
                        val totalTime = System.currentTimeMillis() - totalStart
                        return tessResult.copy(
                            processingTimeMs = totalTime,
                            engineName = "Hybrid → Tesseract (anti-translit)"
                        )
                    }

                    // Если оба результата нормальные — выбираем лучший
                    val best = selectBest(tessResult, mlkitResult)
                    val totalTime = System.currentTimeMillis() - totalStart
                    return (best ?: tessResult).copy(
                        processingTimeMs = totalTime,
                        engineName = "Hybrid → ${best?.engineName ?: "Tesseract"}"
                    )
                }

                Log.d(TAG, "Tesseract result not great, trying ML Kit as fallback...")
            } catch (e: Exception) {
                Log.w(TAG, "Tesseract failed", e)
            }
        }

        // ═══ ШАГ 2: Tesseract не дал результата — пробуем ML Kit как fallback ═══
        if (mlkit.isReady()) {
            try {
                val mlkitResult = mlkit.recognize(bitmap, handwrittenMode)
                if (!mlkitResult.isEmpty && !isTranslitGarbage(mlkitResult.fullText)) {
                    val totalTime = System.currentTimeMillis() - totalStart
                    return mlkitResult.copy(
                        processingTimeMs = totalTime,
                        engineName = "Hybrid → ML Kit (fallback)"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "ML Kit fallback failed", e)
            }
        }

        // ═══ Ничего не сработало ═══
        val totalTime = System.currentTimeMillis() - totalStart
        return (tessResult ?: OcrResult.EMPTY).copy(
            processingTimeMs = totalTime,
            engineName = "Hybrid → ${tessResult?.engineName ?: "None"}"
        )
    }

    /**
     * Проверка: приемлем ли результат Tesseract.
     */
    private fun isResultAcceptable(result: OcrResult): Boolean {
        if (result.isEmpty) return false
        if (result.fullText.trim().length < MIN_TEXT_LENGTH) return false
        if (result.averageConfidence < MIN_CONFIDENCE) return false
        return true
    }

    /**
     * Есть ли в тексте кириллические символы.
     */
    private fun hasCyrillic(text: String): Boolean {
        return text.any { it in '\u0400'..'\u04FF' }
    }

    /**
     * Определение "скрипта" текста.
     */
    private fun detectScript(text: String): String {
        val letters = text.filter { it.isLetter() }
        if (letters.isEmpty()) return "none"
        val cyr = letters.count { it in '\u0400'..'\u04FF' }
        val lat = letters.count { it in 'A'..'Z' || it in 'a'..'z' }
        return when {
            cyr > lat -> "cyrillic"
            lat > cyr -> "latin"
            else -> "mixed"
        }
    }

    /**
     * Детекция "транслит-мусора" — когда ML Kit Latin model
     * пытается прочитать кириллицу и выдаёт псевдо-латиницу.
     *
     * Признаки:
     * 1. Много заглавных букв ВНУТРИ слов: "MaTeMaTHKa", "IluCbMO"
     * 2. Характерные биграммы: "bI", "Kb", "IO", "3e", "CTp", "HK"
     * 3. Необычное распределение регистров
     * 4. Высокая доля согласных без гласных
     */
    private fun isTranslitGarbage(text: String): Boolean {
        if (text.isBlank()) return false
        if (text.length < 3) return false

        val words = text.split("\\s+".toRegex())
            .filter { it.length >= 3 && it.any { c -> c.isLetter() } }
        if (words.isEmpty()) return false

        var totalScore = 0f

        for (word in words) {
            var wordScore = 0f

            // Проверка 1: Заглавные буквы в середине слова
            // "MaTeMaTHKa" → M,T,M,T,H,K — 6 заглавных внутри
            val letterPart = word.filter { it.isLetter() }
            if (letterPart.length >= 3) {
                val middle = letterPart.substring(1, letterPart.length - 1)
                val upperInMiddle = middle.count { it.isUpperCase() }
                val upperRatio = upperInMiddle.toFloat() / middle.length

                if (upperRatio > 0.4f && middle.length >= 2) {
                    wordScore += 0.5f
                }
            }

            // Проверка 2: Характерные транслит-биграммы
            val translitBigrams = listOf(
                "bI", "Kb", "IO", "bl", "3e", "3a", "YI", "Hl",
                "Hb", "CTp", "lO", "HK", "Bb", "Tb", "Cb",
                "Pb", "nb", "ib", "ob", "yb", "eb",
                "bM", "bH", "bC", "bK", "bT",
                "cT", "cK", "cM", "cH",
                "KO", "KA", "KY", "KH",
                "HO", "HA", "HY"
            )
            val upperWord = word.uppercase()
            val bigramHits = translitBigrams.count { bigram ->
                word.contains(bigram) || upperWord.contains(bigram.uppercase())
            }
            if (bigramHits >= 1) {
                wordScore += 0.3f * bigramHits.coerceAtMost(3)
            }

            // Проверка 3: Слово выглядит как "не-английское" латинское
            // Нет гласных или очень мало (русские согласные маппятся в латинские согласные)
            if (letterPart.length >= 4) {
                val vowels = letterPart.lowercase().count { it in "aeiouy" }
                val vowelRatio = vowels.toFloat() / letterPart.length
                if (vowelRatio < 0.15f) {
                    wordScore += 0.4f // Очень мало гласных для латиницы
                }
            }

            totalScore += wordScore
        }

        val avgScore = totalScore / words.size
        val isGarbage = avgScore > 0.3f

        if (isGarbage) {
            Log.d(TAG, "Translit garbage detected (score=$avgScore): " +
                "'${text.take(50)}...'")
        }

        return isGarbage
    }

    /**
     * Выбор лучшего результата (только для НЕ-транслитного текста).
     */
    private fun selectBest(tess: OcrResult?, mlkit: OcrResult?): OcrResult? {
        if (tess == null && mlkit == null) return null
        if (tess == null) return mlkit
        if (mlkit == null) return tess
        if (tess.isEmpty && !mlkit.isEmpty) return mlkit
        if (mlkit.isEmpty && !tess.isEmpty) return tess

        val tScore = score(tess)
        val mScore = score(mlkit)

        Log.d(TAG, "Score: Tess=$tScore, MLKit=$mScore")
        return if (tScore >= mScore) tess else mlkit
    }

    private fun score(result: OcrResult): Float {
        val wordScore = (result.wordCount.coerceAtMost(30) / 30f) * 0.3f
        val cleanChars = result.fullText.count {
            it.isLetterOrDigit() || it.isWhitespace() || it in ".,;:!?()-\"'«»—–/"
        }
        val cleanScore = if (result.fullText.isNotEmpty()) {
            (cleanChars.toFloat() / result.fullText.length) * 0.4f
        } else 0f
        val lengthScore = (result.fullText.length.coerceAtMost(200) / 200f) * 0.3f

        return wordScore + cleanScore + lengthScore
    }

    override fun release() {
        Log.d(TAG, "Hybrid released")
    }
}
