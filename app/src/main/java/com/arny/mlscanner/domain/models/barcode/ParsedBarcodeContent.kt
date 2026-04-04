package com.arny.mlscanner.domain.models.barcode

sealed class ParsedBarcodeContent {
    data class Url(
        val url: String,
        val title: String? = null
    ) : ParsedBarcodeContent()

    data class Wifi(
        val ssid: String,
        val password: String?,
        val encryptionType: WifiEncryption = WifiEncryption.WPA
    ) : ParsedBarcodeContent() {
        enum class WifiEncryption { OPEN, WEP, WPA, WPA2 }
    }

    data class Contact(
        val name: String?,
        val organization: String? = null,
        val title: String? = null,
        val phones: List<String> = emptyList(),
        val emails: List<String> = emptyList(),
        val addresses: List<String> = emptyList(),
        val urls: List<String> = emptyList(),
        val rawVCard: String? = null
    ) : ParsedBarcodeContent()

    data class Email(
        val address: String,
        val subject: String? = null,
        val body: String? = null
    ) : ParsedBarcodeContent()

    data class Phone(
        val number: String
    ) : ParsedBarcodeContent()

    data class Sms(
        val phoneNumber: String,
        val message: String? = null
    ) : ParsedBarcodeContent()

    data class Geo(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double? = null,
        val label: String? = null
    ) : ParsedBarcodeContent()

    data class Product(
        val barcode: String,
        val barcodeFormat: BarcodeFormat,
        val isValid: Boolean = false,
        val country: String? = null,
        val matchedSku: String? = null,
        val matchedName: String? = null
    ) : ParsedBarcodeContent()

    data class PlainText(
        val text: String
    ) : ParsedBarcodeContent()
}