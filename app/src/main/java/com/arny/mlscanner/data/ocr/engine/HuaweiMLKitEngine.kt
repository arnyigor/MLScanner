package com.arny.mlscanner.data.ocr.engine

import android.graphics.Bitmap
import android.util.Log
import com.arny.mlscanner.data.ocr.mapper.EngineResultMapper
import com.arny.mlscanner.domain.models.BoundingBox
import com.arny.mlscanner.domain.models.OcrResult
import com.arny.mlscanner.domain.models.TextBlock
import com.arny.mlscanner.domain.models.TextLine
import com.arny.mlscanner.domain.models.TextWord
import com.huawei.hms.mlsdk.MLAnalyzerFactory
import com.huawei.hms.mlsdk.common.MLFrame
import com.huawei.hms.mlsdk.text.MLLocalTextSetting
import com.huawei.hms.mlsdk.text.MLText
import com.huawei.hms.mlsdk.text.MLTextAnalyzer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Huawei ML Kit OCR Engine (PoC)
 * 
 * Проверяет:
 * 1. Работает ли на non-Huawei устройствах без HMS Core
 * 2. Реально ли распознаёт русский offline
 * 3. Скорость vs Google ML Kit
 * 4. Качество на кириллице
 * 
 * Документация:
 * https://developer.huawei.com/consumer/en/doc/development/hiai-Guides/text-recognition-0000001050040053
 */
class HuaweiMLKitEngine : OcrEngine {

    override val name = "Huawei ML Kit (PoC)"

    companion object {
        private const val TAG = "HuaweiMLKitEngine"
    }

    private var analyzer: MLTextAnalyzer? = null
    private var ready = false
    private val mutex = Mutex()

    override suspend fun initialize(): Boolean {
        return mutex.withLock {
            if (ready) return@withLock true
            try {
                // On-device text recognition
                // Поддерживает: Latin, Chinese, Japanese, Korean
                // Русский: проверяем экспериментально
                val setting = MLLocalTextSetting.Factory()
                    .setOCRMode(MLLocalTextSetting.OCR_DETECT_MODE)
                    .setLanguage("ru") // Пробуем русский
                    .create()

                analyzer = MLAnalyzerFactory.getInstance().getLocalTextAnalyzer(setting)
                ready = true
                Log.i(TAG, "Huawei ML Kit initialized (on-device, language=ru)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Huawei ML Kit init failed: ${e.message}", e)
                // Возможные причины:
                // - HMS Core не установлен
                // - Модель не поддерживается на устройстве
                // - Язык 'ru' не поддерживается on-device
                false
            }
        }
    }

    override fun isReady(): Boolean = ready

    override suspend fun recognize(bitmap: Bitmap, handwrittenMode: Boolean): OcrResult {
        val ana = analyzer
        if (ana == null || !ready) {
            Log.w(TAG, "Huawei ML Kit not ready")
            return OcrResult.EMPTY.copy(engineName = name)
        }

        val startTime = System.currentTimeMillis()

        return try {
            val frame = MLFrame.fromBitmap(bitmap)
            val mlText = processFrame(ana, frame)
            val elapsed = System.currentTimeMillis() - startTime
            
            Log.d(TAG, "Recognition completed in ${elapsed}ms, text length: ${mlText.stringValue.length}")
            
            mapToOcrResult(mlText, elapsed, bitmap.width, bitmap.height)
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.e(TAG, "Huawei ML Kit recognition error: ${e.message}", e)
            OcrResult.EMPTY.copy(
                processingTimeMs = elapsed,
                engineName = name
            )
        }
    }

    private suspend fun processFrame(
        analyzer: MLTextAnalyzer,
        frame: MLFrame
    ): MLText = suspendCancellableCoroutine { cont ->
        val task = analyzer.asyncAnalyseFrame(frame)
        
        task.addOnSuccessListener { text ->
            if (cont.isActive) {
                cont.resume(text)
            }
        }.addOnFailureListener { e ->
            if (cont.isActive) {
                cont.resumeWithException(e)
            }
        }
        
        cont.invokeOnCancellation {
            // Huawei Task не имеет cancel(), просто игнорируем результат
        }
    }

    private fun mapToOcrResult(
        mlText: MLText,
        elapsedMs: Long,
        imgW: Int,
        imgH: Int
    ): OcrResult {
        val blocks = mlText.blocks.mapNotNull { mlBlock ->
            try {
                val lines = mlBlock.contents.mapNotNull { mlLine ->
                    try {
                        val words = mlLine.contents.mapNotNull { mlWord ->
                            try {
                                TextWord(
                                    text = mlWord.stringValue ?: "",
                                    boundingBox = BoundingBox.EMPTY, // Упрощаем для PoC
                                    confidence = mlWord.possibility ?: 0f
                                )
                            } catch (e: Exception) {
                                Log.w(TAG, "Error mapping word: ${e.message}")
                                null
                            }
                        }

                        TextLine(
                            text = mlLine.stringValue ?: "",
                            boundingBox = BoundingBox.EMPTY, // Упрощаем для PoC
                            words = words,
                            confidence = if (words.isNotEmpty()) {
                                words.map { it.confidence }.average().toFloat()
                            } else 0f
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Error mapping line: ${e.message}")
                        null
                    }
                }

                TextBlock(
                    text = mlBlock.stringValue ?: "",
                    boundingBox = BoundingBox.EMPTY, // Упрощаем для PoC
                    lines = lines,
                    confidence = mlBlock.possibility ?: 0f
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error mapping block: ${e.message}")
                null
            }
        }

        val fullText = EngineResultMapper.buildFullTextFromBlocks(blocks)
        val avgConf = EngineResultMapper.calculateAverageConfidence(blocks)

        return OcrResult(
            blocks = blocks,
            fullText = fullText,
            formattedText = fullText,
            averageConfidence = avgConf,
            processingTimeMs = elapsedMs,
            engineName = name,
            imageWidth = imgW,
            imageHeight = imgH
        )
    }

    override fun release() {
        try {
            analyzer?.stop()
            analyzer = null
            ready = false
            Log.d(TAG, "Huawei ML Kit released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing Huawei ML Kit: ${e.message}", e)
        }
    }
}
