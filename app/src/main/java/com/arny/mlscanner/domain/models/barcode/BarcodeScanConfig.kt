package com.arny.mlscanner.domain.models.barcode

data class BarcodeScanConfig(
    val allowedFormats: Set<BarcodeFormat> = emptySet(),
    val scanMode: ScanMode = ScanMode.SINGLE,
    val vibrationEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val autoOpenUrls: Boolean = false,
    val matchWithCatalog: Boolean = true,
    val timeoutMs: Long = 0,
    val torchEnabled: Boolean = false,
    val preferredEngine: PreferredEngine = PreferredEngine.AUTO
) {
    enum class ScanMode {
        SINGLE,
        CONTINUOUS,
        BATCH
    }

    enum class PreferredEngine {
        AUTO,
        ML_KIT,
        ZXING
    }
}