package com.arny.mlscanner.data.barcode.engine

import android.graphics.Bitmap
import com.arny.mlscanner.domain.models.barcode.BarcodeResult
import com.arny.mlscanner.domain.models.barcode.BarcodeScanConfig

class HybridBarcodeEngine(
    private val mlKitEngine: MLKitBarcodeEngine,
    private val zxingEngine: ZXingBarcodeEngine
) : BarcodeEngine {

    override val name: String = "Hybrid Barcode"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun scan(bitmap: Bitmap, config: BarcodeScanConfig): List<BarcodeResult> {
        return when (config.preferredEngine) {
            BarcodeScanConfig.PreferredEngine.ML_KIT -> mlKitEngine.scan(bitmap, config)
            BarcodeScanConfig.PreferredEngine.ZXING -> zxingEngine.scan(bitmap, config)
            BarcodeScanConfig.PreferredEngine.AUTO -> scanHybrid(bitmap, config)
        }
    }

    private suspend fun scanHybrid(bitmap: Bitmap, config: BarcodeScanConfig): List<BarcodeResult> {
        val mlKitResults = try {
            mlKitEngine.scan(bitmap, config)
        } catch (e: Exception) {
            emptyList()
        }

        if (mlKitResults.isNotEmpty()) {
            return mlKitResults
        }

        return try {
            zxingEngine.scan(bitmap, config)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun release() {
        mlKitEngine.release()
        zxingEngine.release()
    }
}