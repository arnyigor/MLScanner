package com.arny.mlscanner.data.ocr.core.adapter

import com.arny.mlscanner.data.ocr.core.IOcrEngine
import com.arny.mlscanner.data.ocr.core.OcrAccuracyTier
import com.arny.mlscanner.data.ocr.core.OcrCandidate
import com.arny.mlscanner.data.ocr.core.OcrEngineCapabilities
import com.arny.mlscanner.data.ocr.core.OcrEngineState
import com.arny.mlscanner.data.ocr.core.OcrScript
import com.arny.mlscanner.data.ocr.core.OcrSpeedTier
import com.arny.mlscanner.data.ocr.core.OcrTaskType
import com.arny.mlscanner.data.ocr.core.PreparedOcrRequest
import com.arny.mlscanner.data.ocr.engine.MLKitEngine
import com.arny.mlscanner.data.ocr.engine.TesseractProfile
import com.arny.mlscanner.domain.models.OcrLanguage

/**
 * Адаптер для Google ML Kit Engine в новый core pipeline.
 */
class GoogleMLKitEngineAdapter(
    private val mlkitEngine: MLKitEngine
) : IOcrEngine {

    override val id: String = "google_mlkit"
    override val name: String = "Google ML Kit"

    override val capabilities: OcrEngineCapabilities = OcrEngineCapabilities(
        engineId = id,
        displayName = name,
        isOffline = true,
        isExperimental = false,
        requiresGooglePlayServices = false, // Bundled model
        requiresHmsCore = false,
        supportedLanguages = setOf("eng", "rus", "deu", "fra", "spa", "ita"),
        supportedScripts = setOf(OcrScript.LATIN, OcrScript.CYRILLIC),
        supportedTasks = setOf(
            OcrTaskType.GENERAL_TEXT,
            OcrTaskType.DOCUMENT,
            OcrTaskType.RECEIPT,
            OcrTaskType.SINGLE_LINE
        ),
        speedTier = OcrSpeedTier.VERY_FAST,
        accuracyTier = OcrAccuracyTier.GOOD
    )

    override suspend fun initialize(): OcrEngineState {
        return try {
            val success = mlkitEngine.initialize()
            if (success) {
                OcrEngineState.Ready(capabilities)
            } else {
                OcrEngineState.Error("ML Kit initialization failed")
            }
        } catch (e: Exception) {
            OcrEngineState.Error("ML Kit initialization error", e)
        }
    }

    override fun isReady(): Boolean {
        return mlkitEngine.isReady()
    }

    override suspend fun recognize(request: PreparedOcrRequest): OcrCandidate {
        val startTime = System.currentTimeMillis()

        val result = mlkitEngine.recognize(
            bitmap = request.bitmap,
            handwrittenMode = request.originalRequest.handwrittenMode
        )

        val elapsed = System.currentTimeMillis() - startTime

        return OcrCandidate(
            engineId = id,
            engineName = name,
            rawText = result.fullText,
            blocks = result.blocks,
            confidence = result.averageConfidence,
            processingTimeMs = elapsed,
            metadata = mapOf(
                "engine_version" to "ml_kit_v2",
                "image_width" to result.imageWidth.toString(),
                "image_height" to result.imageHeight.toString()
            )
        )
    }

    override fun release() {
        mlkitEngine.release()
    }
}

/**
 * Адаптер для Tesseract Engine в новый core pipeline.
 */
class TesseractEngineAdapter(
    private val tesseractEngine: com.arny.mlscanner.data.ocr.engine.TesseractEngine
) : IOcrEngine {

    override val id: String = "tesseract"
    override val name: String = "Tesseract"

    override val capabilities: OcrEngineCapabilities = OcrEngineCapabilities(
        engineId = id,
        displayName = name,
        isOffline = true,
        isExperimental = false,
        requiresGooglePlayServices = false,
        requiresHmsCore = false,
        supportedLanguages = setOf("rus", "eng", "rus+eng"),
        supportedScripts = setOf(OcrScript.LATIN, OcrScript.CYRILLIC),
        supportedTasks = setOf(
            OcrTaskType.GENERAL_TEXT,
            OcrTaskType.DOCUMENT,
            OcrTaskType.RECEIPT,
            OcrTaskType.DRIVER_LICENSE,
            OcrTaskType.PASSPORT,
            OcrTaskType.PDF_PAGE
        ),
        speedTier = OcrSpeedTier.SLOW,
        accuracyTier = OcrAccuracyTier.EXCELLENT
    )

    override suspend fun initialize(): OcrEngineState {
        return try {
            val success = tesseractEngine.initialize()
            if (success) {
                OcrEngineState.Ready(capabilities)
            } else {
                OcrEngineState.Error("Tesseract initialization failed")
            }
        } catch (e: Exception) {
            OcrEngineState.Error("Tesseract initialization error", e)
        }
    }

    override fun isReady(): Boolean {
        return tesseractEngine.isReady()
    }

    override suspend fun recognize(request: PreparedOcrRequest): OcrCandidate {
        val startTime = System.currentTimeMillis()

        val result = if (request.originalRequest.qualityMode == com.arny.mlscanner.data.ocr.core.OcrQualityMode.ACCURATE) {
            val profiles = when (request.originalRequest.language) {
                OcrLanguage.RUSSIAN -> TesseractProfile.RUSSIAN_DOCUMENT_PROFILES
                OcrLanguage.ENGLISH -> TesseractProfile.ENGLISH_PROFILES
                OcrLanguage.RUSSIAN_ENGLISH -> TesseractProfile.MIXED_PROFILES
            }
            tesseractEngine.recognizeMultiPass(request.bitmap, profiles).result
        } else {
            tesseractEngine.recognize(
                bitmap = request.bitmap,
                handwrittenMode = request.originalRequest.handwrittenMode
            )
        }

        val elapsed = System.currentTimeMillis() - startTime

        return OcrCandidate(
            engineId = id,
            engineName = name,
            rawText = result.fullText,
            blocks = result.blocks,
            confidence = result.averageConfidence,
            processingTimeMs = elapsed,
            metadata = mapOf(
                "engine_version" to "tesseract_4.7.0",
                "image_width" to result.imageWidth.toString(),
                "image_height" to result.imageHeight.toString()
            )
        )
    }

    override fun release() {
        tesseractEngine.release()
    }
}

/**
 * Адаптер для Huawei ML Kit Engine в новый core pipeline.
 */
class HuaweiMLKitEngineAdapter(
    private val huaweiEngine: com.arny.mlscanner.data.ocr.engine.HuaweiMLKitEngine
) : IOcrEngine {

    override val id: String = "huawei_mlkit"
    override val name: String = "Huawei ML Kit"

    override val capabilities: OcrEngineCapabilities = OcrEngineCapabilities(
        engineId = id,
        displayName = name,
        isOffline = true,
        isExperimental = true, // Пока экспериментальный, нужен benchmark
        requiresGooglePlayServices = false,
        requiresHmsCore = true,
        supportedLanguages = setOf("rus", "eng", "zh", "ja", "ko"),
        supportedScripts = setOf(OcrScript.LATIN, OcrScript.CYRILLIC, OcrScript.CHINESE),
        supportedTasks = setOf(
            OcrTaskType.GENERAL_TEXT,
            OcrTaskType.DOCUMENT
        ),
        speedTier = OcrSpeedTier.FAST,
        accuracyTier = OcrAccuracyTier.GOOD // Нужно подтвердить benchmark-ом
    )

    override suspend fun initialize(): OcrEngineState {
        return try {
            val success = huaweiEngine.initialize()
            if (success) {
                OcrEngineState.Ready(capabilities)
            } else {
                OcrEngineState.Unavailable // HMS Core может быть недоступен
            }
        } catch (e: Exception) {
            OcrEngineState.Unavailable
        }
    }

    override fun isReady(): Boolean {
        return huaweiEngine.isReady()
    }

    override suspend fun recognize(request: PreparedOcrRequest): OcrCandidate {
        val startTime = System.currentTimeMillis()

        val result = huaweiEngine.recognize(
            bitmap = request.bitmap,
            handwrittenMode = request.originalRequest.handwrittenMode
        )

        val elapsed = System.currentTimeMillis() - startTime

        return OcrCandidate(
            engineId = id,
            engineName = name,
            rawText = result.fullText,
            blocks = result.blocks,
            confidence = result.averageConfidence,
            processingTimeMs = elapsed,
            metadata = mapOf(
                "engine_version" to "huawei_mlkit",
                "image_width" to result.imageWidth.toString(),
                "image_height" to result.imageHeight.toString(),
                "experimental" to "true"
            )
        )
    }

    override fun release() {
        huaweiEngine.release()
    }
}
