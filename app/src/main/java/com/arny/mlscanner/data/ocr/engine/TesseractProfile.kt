package com.arny.mlscanner.data.ocr.engine

import com.googlecode.tesseract.android.TessBaseAPI

/**
 * Профиль распознавания для Tesseract.
 *
 * Определяет язык, PSM, режим предобработки.
 * Используется для multi-pass OCR с выбором лучшего результата.
 */
data class TesseractProfile(
    val name: String,
    val language: TesseractLanguage,
    val psm: Int,
    val preprocessMode: PreprocessMode
) {
    companion object {
        /**
         * Профили для русского текста (документы, чеки, страницы).
         */
        val RUSSIAN_PROFILES = listOf(
            TesseractProfile(
                name = "rus_auto_original",
                language = TesseractLanguage.RUS_ONLY,
                psm = TessBaseAPI.PageSegMode.PSM_AUTO,
                preprocessMode = PreprocessMode.ORIGINAL
            ),
            TesseractProfile(
                name = "rus_block_original",
                language = TesseractLanguage.RUS_ONLY,
                psm = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
                preprocessMode = PreprocessMode.ORIGINAL
            ),
            TesseractProfile(
                name = "rus_auto_upscale",
                language = TesseractLanguage.RUS_ONLY,
                psm = TessBaseAPI.PageSegMode.PSM_AUTO,
                preprocessMode = PreprocessMode.UPSCALE_2X
            ),
            TesseractProfile(
                name = "rus_block_enhanced",
                language = TesseractLanguage.RUS_ONLY,
                psm = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
                preprocessMode = PreprocessMode.CONTRAST_ENHANCED
            ),
            TesseractProfile(
                name = "rus_sparse_adaptive",
                language = TesseractLanguage.RUS_ONLY,
                psm = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
                preprocessMode = PreprocessMode.ADAPTIVE_THRESHOLD
            )
        )

        /**
         * Профили для английского текста.
         */
        val ENGLISH_PROFILES = listOf(
            TesseractProfile(
                name = "eng_auto_original",
                language = TesseractLanguage.ENG_ONLY,
                psm = TessBaseAPI.PageSegMode.PSM_AUTO,
                preprocessMode = PreprocessMode.ORIGINAL
            ),
            TesseractProfile(
                name = "eng_block_enhanced",
                language = TesseractLanguage.ENG_ONLY,
                psm = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
                preprocessMode = PreprocessMode.CONTRAST_ENHANCED
            )
        )

        /**
         * Профили для смешанного текста (рус+eng).
         */
        val MIXED_PROFILES = listOf(
            TesseractProfile(
                name = "mixed_auto_original",
                language = TesseractLanguage.RUS_ENG,
                psm = TessBaseAPI.PageSegMode.PSM_AUTO,
                preprocessMode = PreprocessMode.ORIGINAL
            ),
            TesseractProfile(
                name = "mixed_block_enhanced",
                language = TesseractLanguage.RUS_ENG,
                psm = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
                preprocessMode = PreprocessMode.CONTRAST_ENHANCED
            )
        )

        /**
         * Профиль для чеков (агрессивная предобработка).
         */
        val RECEIPT_PROFILE = TesseractProfile(
            name = "receipt_block_threshold",
            language = TesseractLanguage.RUS_ONLY,
            psm = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
            preprocessMode = PreprocessMode.ADAPTIVE_THRESHOLD
        )

        /**
         * Профиль для одной строки.
         */
        val SINGLE_LINE_PROFILE = TesseractProfile(
            name = "single_line",
            language = TesseractLanguage.RUS_ONLY,
            psm = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE,
            preprocessMode = PreprocessMode.ORIGINAL
        )
    }
}

/**
 * Язык для Tesseract.
 */
enum class TesseractLanguage(val code: String) {
    RUS_ONLY("rus"),
    ENG_ONLY("eng"),
    RUS_ENG("rus+eng");

    companion object {
        fun fromOcrLanguage(lang: com.arny.mlscanner.domain.models.OcrLanguage): TesseractLanguage {
            return when (lang) {
                com.arny.mlscanner.domain.models.OcrLanguage.RUSSIAN -> RUS_ONLY
                com.arny.mlscanner.domain.models.OcrLanguage.ENGLISH -> ENG_ONLY
                com.arny.mlscanner.domain.models.OcrLanguage.RUSSIAN_ENGLISH -> RUS_ENG
            }
        }
    }
}

/**
 * Режим предобработки изображения перед OCR.
 */
enum class PreprocessMode {
    /** Без изменений (только deskew + grayscale) */
    ORIGINAL,

    /** Усиленный контраст + grayscale */
    CONTRAST_ENHANCED,

    /** Adaptive threshold (бинаризация) */
    ADAPTIVE_THRESHOLD,

    /** Upscale 2x для мелкого текста */
    UPSCALE_2X,

    /** Лёгкая резкость */
    SHARPEN_LIGHT
}

/**
 * Кандидат результата OCR для scoring.
 */
data class OcrCandidate(
    val profile: TesseractProfile,
    val result: com.arny.mlscanner.domain.models.OcrResult,
    val score: Float
) {
    val text: String get() = result.fullText
    val confidence: Float get() = result.averageConfidence
    val wordCount: Int get() = result.fullText.split(Regex("\\s+")).count { it.length > 1 }
}
