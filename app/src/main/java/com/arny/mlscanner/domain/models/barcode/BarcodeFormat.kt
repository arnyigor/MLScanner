package com.arny.mlscanner.domain.models.barcode

enum class BarcodeFormat(val displayName: String) {
    QR_CODE("QR Code"),
    AZTEC("Aztec"),
    DATA_MATRIX("Data Matrix"),
    PDF_417("PDF 417"),
    EAN_13("EAN-13"),
    EAN_8("EAN-8"),
    UPC_A("UPC-A"),
    UPC_E("UPC-E"),
    CODE_128("Code 128"),
    CODE_39("Code 39"),
    CODE_93("Code 93"),
    CODABAR("Codabar"),
    ITF("ITF (Interleaved 2 of 5)"),
    UNKNOWN("Неизвестный");

    companion object {
        fun fromMLKit(mlKitFormat: Int): BarcodeFormat {
            return when (mlKitFormat) {
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_QR_CODE -> QR_CODE
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_AZTEC -> AZTEC
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_DATA_MATRIX -> DATA_MATRIX
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_PDF417 -> PDF_417
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13 -> EAN_13
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_8 -> EAN_8
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_A -> UPC_A
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_E -> UPC_E
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_128 -> CODE_128
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_39 -> CODE_39
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODE_93 -> CODE_93
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_CODABAR -> CODABAR
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_ITF -> ITF
                else -> UNKNOWN
            }
        }

        fun fromZXing(zxingFormat: com.google.zxing.BarcodeFormat): BarcodeFormat {
            return when (zxingFormat) {
                com.google.zxing.BarcodeFormat.QR_CODE -> QR_CODE
                com.google.zxing.BarcodeFormat.AZTEC -> AZTEC
                com.google.zxing.BarcodeFormat.DATA_MATRIX -> DATA_MATRIX
                com.google.zxing.BarcodeFormat.PDF_417 -> PDF_417
                com.google.zxing.BarcodeFormat.EAN_13 -> EAN_13
                com.google.zxing.BarcodeFormat.EAN_8 -> EAN_8
                com.google.zxing.BarcodeFormat.UPC_A -> UPC_A
                com.google.zxing.BarcodeFormat.UPC_E -> UPC_E
                com.google.zxing.BarcodeFormat.CODE_128 -> CODE_128
                com.google.zxing.BarcodeFormat.CODE_39 -> CODE_39
                com.google.zxing.BarcodeFormat.CODE_93 -> CODE_93
                com.google.zxing.BarcodeFormat.CODABAR -> CODABAR
                com.google.zxing.BarcodeFormat.ITF -> ITF
                else -> UNKNOWN
            }
        }
    }
}

enum class BarcodeContentType {
    URL,
    WIFI,
    CONTACT_VCARD,
    EMAIL,
    PHONE,
    SMS,
    GEO,
    PRODUCT,
    TEXT,
    UNKNOWN
}