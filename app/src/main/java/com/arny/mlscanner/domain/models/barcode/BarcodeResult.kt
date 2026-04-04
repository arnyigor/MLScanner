package com.arny.mlscanner.domain.models.barcode

import android.graphics.Rect

data class BarcodeResult(
    val rawValue: String,
    val format: BarcodeFormat,
    val contentType: BarcodeContentType,
    val parsedContent: ParsedBarcodeContent? = null,
    val boundingBox: Rect? = null,
    val confidence: Float = 1.0f,
    val engineSource: String = "unknown",
    val timestamp: Long = System.currentTimeMillis()
) {
    val displayTitle: String
        get() = when (contentType) {
            BarcodeContentType.URL -> "Ссылка"
            BarcodeContentType.WIFI -> "WiFi сеть"
            BarcodeContentType.CONTACT_VCARD -> "Контакт"
            BarcodeContentType.EMAIL -> "Email"
            BarcodeContentType.PHONE -> "Телефон"
            BarcodeContentType.SMS -> "SMS"
            BarcodeContentType.GEO -> "Геолокация"
            BarcodeContentType.PRODUCT -> "Товар (${format.displayName})"
            BarcodeContentType.TEXT -> "Текст"
            BarcodeContentType.UNKNOWN -> format.displayName
        }
}