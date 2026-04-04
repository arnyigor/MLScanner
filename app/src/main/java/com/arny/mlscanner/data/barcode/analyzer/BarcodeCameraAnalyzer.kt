package com.arny.mlscanner.data.barcode.analyzer

import android.graphics.Bitmap
import android.graphics.ImageFormat
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.arny.mlscanner.data.barcode.engine.BarcodeEngine
import com.arny.mlscanner.data.camera.YuvConverter
import com.arny.mlscanner.domain.models.barcode.BarcodeResult
import com.arny.mlscanner.domain.models.barcode.BarcodeScanConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.atomic.AtomicBoolean

class BarcodeCameraAnalyzer(
    private val engine: BarcodeEngine,
    private val config: BarcodeScanConfig = BarcodeScanConfig(),
    private val deduplicationWindowMs: Long = 2000L,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob())
) : ImageAnalysis.Analyzer {

    companion object {
        private const val THROTTLE_TIMEOUT_MS = 250L // 4 FPS - оптимально для штрихкодов
    }

    private val _results = MutableSharedFlow<List<BarcodeResult>>(replay = 0, extraBufferCapacity = 10)
    val results: SharedFlow<List<BarcodeResult>> = _results

    private val _errors = MutableSharedFlow<Exception>(replay = 0, extraBufferCapacity = 5)
    val errors: SharedFlow<Exception> = _errors

    private val isProcessing = AtomicBoolean(false)
    private val recentCodes = mutableMapOf<String, Long>()
    private var lastAnalyzedTimestamp = 0L

    @Volatile
    var isPaused: Boolean = false

    override fun analyze(imageProxy: ImageProxy) {
        val currentTimestamp = System.currentTimeMillis()

        if (currentTimestamp - lastAnalyzedTimestamp < THROTTLE_TIMEOUT_MS) {
            imageProxy.close()
            return
        }

        if (isPaused || !isProcessing.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }

        lastAnalyzedTimestamp = currentTimestamp

        scope.launch {
            try {
                val bitmap = imageProxyToBitmap(imageProxy)
                if (bitmap != null) {
                    val scanResults = engine.scan(bitmap, config)
                    bitmap.recycle()
                    val filteredResults = deduplicateResults(scanResults)
                    if (filteredResults.isNotEmpty()) {
                        _results.emit(filteredResults)
                    }
                }
            } catch (e: Exception) {
                _errors.emit(e)
            } finally {
                isProcessing.set(false)
                imageProxy.close()
            }
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            when (imageProxy.format) {
                ImageFormat.YUV_420_888 -> {
                    val image = imageProxy.image
                    if (image != null) YuvConverter.yuvToRgb(image) else null
                }
                else -> {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                        imageProxy.toBitmap()
                    } else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun deduplicateResults(results: List<BarcodeResult>): List<BarcodeResult> {
        val now = System.currentTimeMillis()
        recentCodes.entries.removeAll { (_, timestamp) -> now - timestamp > deduplicationWindowMs }

        return results.filter { result ->
            val key = "${result.format}:${result.rawValue}"
            val lastSeen = recentCodes[key]
            if (lastSeen == null || now - lastSeen > deduplicationWindowMs) {
                recentCodes[key] = now
                true
            } else false
        }
    }

    fun release() {
        scope.cancel()
        engine.release()
        recentCodes.clear()
    }
}