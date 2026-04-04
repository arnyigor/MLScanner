package com.arny.mlscanner.domain.usecases.barcode

import android.graphics.Bitmap
import com.arny.mlscanner.data.barcode.engine.BarcodeEngine
import com.arny.mlscanner.domain.models.barcode.BarcodeResult
import com.arny.mlscanner.domain.models.barcode.BarcodeScanConfig

class ScanBarcodeUseCase(
    private val barcodeEngine: BarcodeEngine
) {
    suspend operator fun invoke(
        bitmap: Bitmap,
        config: BarcodeScanConfig = BarcodeScanConfig()
    ): List<BarcodeResult> {
        return barcodeEngine.scan(bitmap, config)
    }
}