package com.arny.mlscanner.data.barcode.engine

import android.graphics.Bitmap
import com.arny.mlscanner.domain.models.barcode.BarcodeResult
import com.arny.mlscanner.domain.models.barcode.BarcodeScanConfig

interface BarcodeEngine {
    val name: String
    suspend fun isAvailable(): Boolean
    suspend fun scan(bitmap: Bitmap, config: BarcodeScanConfig): List<BarcodeResult>
    fun release()
}