package com.arny.mlscanner.data.ocr.core

import android.util.Log

/**
 * Анализатор качества OCR-результатов.
 * Вынесен из TesseractEngine для универсального использования.
 */
class OcrQualityAnalyzer : IOcrQualityAnalyzer {
    
    companion object {
        private const val TAG = "OcrQualityAnalyzer"
        
        // Пороги для оценки качества
        private const val MIN_WORD_LENGTH = 2
        private const val MAX_GARBAGE_RATIO = 0.15f
        private const val MIN_CYRILLIC_RATIO_FOR_RUSSIAN = 0.6f
        private const val MAX_MIXED_SCRIPT_RATIO = 0.2f
    }
    
    override fun analyze(
        candidate: OcrCandidate,
        processedText: String,
        request: OcrRequest
    ): OcrQualityReport {
        val text = processedText.ifBlank { candidate.rawText }
        
        if (text.isBlank()) {
            return OcrQualityReport(
                score = 0f,
                confidence = candidate.confidence,
                charCount = 0,
                wordCount = 0,
                lineCount = 0,
                cyrillicRatio = 0f,
                latinRatio = 0f,
                digitRatio = 0f,
                garbageRatio = 0f,
                mixedScriptWordCount = 0,
                suspiciousReasons = listOf("Empty text")
            )
        }
        
        val charCount = text.length
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val wordCount = words.size
        val lineCount = text.lines().count { it.isNotBlank() }
        
        // Подсчёт символов по типам
        val cyrillicCount = text.count { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
        val latinCount = text.count { it in 'A'..'Z' || it in 'a'..'z' }
        val digitCount = text.count { it.isDigit() }
        val letterCount = cyrillicCount + latinCount
        
        val cyrillicRatio = if (letterCount > 0) cyrillicCount.toFloat() / letterCount else 0f
        val latinRatio = if (letterCount > 0) latinCount.toFloat() / letterCount else 0f
        val digitRatio = if (charCount > 0) digitCount.toFloat() / charCount else 0f
        
        // Подсчёт мусорных символов
        val garbageCount = text.count { char ->
            !char.isLetterOrDigit() && 
            !char.isWhitespace() && 
            char !in setOf('.', ',', '!', '?', '-', ':', ';', '(', ')', '"', '\'', '/', '+', '@', '#', '№')
        }
        val garbageRatio = if (charCount > 0) garbageCount.toFloat() / charCount else 0f
        
        // Подсчёт слов со смешанными скриптами
        val mixedScriptWordCount = words.count { word ->
            val hasCyrillic = word.any { it in 'А'..'я' || it == 'Ё' || it == 'ё' }
            val hasLatin = word.any { it in 'A'..'Z' || it in 'a'..'z' }
            hasCyrillic && hasLatin
        }
        
        // Сбор подозрительных причин
        val suspiciousReasons = mutableListOf<String>()
        
        if (wordCount < 3) {
            suspiciousReasons.add("Too few words: $wordCount")
        }
        
        if (garbageRatio > MAX_GARBAGE_RATIO) {
            suspiciousReasons.add("High garbage ratio: ${"%.1f".format(garbageRatio * 100)}%")
        }
        
        // Проверка для русского текста
        if (request.language.tesseractCode.contains("rus")) {
            if (cyrillicRatio < MIN_CYRILLIC_RATIO_FOR_RUSSIAN && latinRatio > 0.3f) {
                suspiciousReasons.add("Low Cyrillic ratio for Russian: ${"%.1f".format(cyrillicRatio * 100)}%")
            }
            
            if (mixedScriptWordCount > wordCount * MAX_MIXED_SCRIPT_RATIO) {
                suspiciousReasons.add("Too many mixed-script words: $mixedScriptWordCount")
            }
        }
        
        // Проверка слишком коротких слов
        val shortWords = words.count { it.length < MIN_WORD_LENGTH }
        if (shortWords > wordCount * 0.5f) {
            suspiciousReasons.add("Too many short words: $shortWords/$wordCount")
        }
        
        // Проверка одиночных символов
        val singleChars = words.count { it.length == 1 }
        if (singleChars > wordCount * 0.3f) {
            suspiciousReasons.add("Too many single characters: $singleChars")
        }
        
        // Вычисление общего score
        val score = calculateScore(
            confidence = candidate.confidence,
            wordCount = wordCount,
            cyrillicRatio = cyrillicRatio,
            latinRatio = latinRatio,
            garbageRatio = garbageRatio,
            mixedScriptWordCount = mixedScriptWordCount,
            totalWords = wordCount,
            request = request
        )
        
        return OcrQualityReport(
            score = score,
            confidence = candidate.confidence,
            charCount = charCount,
            wordCount = wordCount,
            lineCount = lineCount,
            cyrillicRatio = cyrillicRatio,
            latinRatio = latinRatio,
            digitRatio = digitRatio,
            garbageRatio = garbageRatio,
            mixedScriptWordCount = mixedScriptWordCount,
            suspiciousReasons = suspiciousReasons
        )
    }
    
    private fun calculateScore(
        confidence: Float?,
        wordCount: Int,
        cyrillicRatio: Float,
        latinRatio: Float,
        garbageRatio: Float,
        mixedScriptWordCount: Int,
        totalWords: Int,
        request: OcrRequest
    ): Float {
        var score = 0f
        var weight = 0f
        
        // Confidence от движка (вес 0.3)
        if (confidence != null && confidence > 0) {
            score += confidence * 0.3f
            weight += 0.3f
        }
        
        // Количество слов (вес 0.2)
        val wordScore = when {
            wordCount >= 10 -> 1.0f
            wordCount >= 5 -> 0.8f
            wordCount >= 3 -> 0.6f
            wordCount >= 1 -> 0.4f
            else -> 0f
        }
        score += wordScore * 0.2f
        weight += 0.2f
        
        // Соответствие языку (вес 0.3)
        val languageScore = when {
            request.language.tesseractCode.contains("rus") -> {
                // Для русского: высокий процент кириллицы = хорошо
                cyrillicRatio
            }
            request.language.tesseractCode.contains("eng") -> {
                // Для английского: высокий процент латиницы = хорошо
                latinRatio
            }
            else -> {
                // Для смешанного: любой скрипт ок
                maxOf(cyrillicRatio, latinRatio)
            }
        }
        score += languageScore * 0.3f
        weight += 0.3f
        
        // Отсутствие мусора (вес 0.1)
        val cleanScore = (1f - garbageRatio).coerceIn(0f, 1f)
        score += cleanScore * 0.1f
        weight += 0.1f
        
        // Отсутствие смешанных слов (вес 0.1)
        val mixedRatio = if (totalWords > 0) mixedScriptWordCount.toFloat() / totalWords else 0f
        val mixedScore = (1f - mixedRatio).coerceIn(0f, 1f)
        score += mixedScore * 0.1f
        weight += 0.1f
        
        return if (weight > 0) (score / weight).coerceIn(0f, 1f) else 0f
    }
}
