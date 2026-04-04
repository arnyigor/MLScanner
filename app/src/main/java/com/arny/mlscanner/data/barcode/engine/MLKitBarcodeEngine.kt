package com.arny.mlscanner.data.barcode.engine

import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.arny.mlscanner.domain.models.barcode.BarcodeContentType
import com.arny.mlscanner.domain.models.barcode.BarcodeFormat
import com.arny.mlscanner.domain.models.barcode.BarcodeResult
import com.arny.mlscanner.domain.models.barcode.BarcodeScanConfig
import com.arny.mlscanner.domain.models.barcode.ParsedBarcodeContent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MLKitBarcodeEngine : BarcodeEngine {

    override val name: String = "MLKit Barcode"

    override suspend fun isAvailable(): Boolean = true

    override suspend fun scan(
        bitmap: Bitmap,
        config: BarcodeScanConfig
    ): List<BarcodeResult> {
        val options = buildOptions(config)
        val scanner = BarcodeScanning.getClient(options)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        return suspendCancellableCoroutine { continuation ->
            scanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    val results = barcodes.mapNotNull { barcode ->
                        convertBarcode(barcode)
                    }
                    scanner.close()
                    continuation.resume(results)
                }
                .addOnFailureListener { exception ->
                    scanner.close()
                    continuation.resumeWithException(exception)
                }

            continuation.invokeOnCancellation {
                scanner.close()
            }
        }
    }

    override fun release() {}

    private fun buildOptions(config: BarcodeScanConfig): BarcodeScannerOptions {
        val builder = BarcodeScannerOptions.Builder()

        if (config.allowedFormats.isNotEmpty()) {
            val mlKitFormats = config.allowedFormats.map { it.toMLKitFormat() }
            if (mlKitFormats.size == 1) {
                builder.setBarcodeFormats(mlKitFormats.first())
            } else {
                builder.setBarcodeFormats(
                    mlKitFormats.first(),
                    *mlKitFormats.drop(1).toIntArray()
                )
            }
        } else {
            builder.setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
        }

        return builder.build()
    }

    private fun convertBarcode(barcode: Barcode): BarcodeResult? {
        val rawValue = barcode.rawValue ?: return null
        val format = BarcodeFormat.fromMLKit(barcode.format)
        val contentType = determineContentType(barcode)
        val parsedContent = parseContent(barcode)

        return BarcodeResult(
            rawValue = rawValue,
            format = format,
            contentType = contentType,
            parsedContent = parsedContent,
            boundingBox = barcode.boundingBox,
            confidence = 1.0f,
            engineSource = name
        )
    }

    private fun determineContentType(barcode: Barcode): BarcodeContentType {
        return when (barcode.valueType) {
            Barcode.TYPE_URL -> BarcodeContentType.URL
            Barcode.TYPE_WIFI -> BarcodeContentType.WIFI
            Barcode.TYPE_CONTACT_INFO -> BarcodeContentType.CONTACT_VCARD
            Barcode.TYPE_EMAIL -> BarcodeContentType.EMAIL
            Barcode.TYPE_PHONE -> BarcodeContentType.PHONE
            Barcode.TYPE_SMS -> BarcodeContentType.SMS
            Barcode.TYPE_GEO -> BarcodeContentType.GEO
            Barcode.TYPE_PRODUCT -> BarcodeContentType.PRODUCT
            Barcode.TYPE_TEXT -> BarcodeContentType.TEXT
            else -> BarcodeContentType.UNKNOWN
        }
    }

    private fun parseContent(barcode: Barcode): ParsedBarcodeContent? {
        return when (barcode.valueType) {
            Barcode.TYPE_URL -> {
                barcode.url?.let { url ->
                    ParsedBarcodeContent.Url(
                        url = url.url ?: barcode.rawValue ?: "",
                        title = url.title
                    )
                }
            }
            Barcode.TYPE_WIFI -> {
                barcode.wifi?.let { wifi ->
                    ParsedBarcodeContent.Wifi(
                        ssid = wifi.ssid ?: "",
                        password = wifi.password,
                        encryptionType = when (wifi.encryptionType) {
                            Barcode.WiFi.TYPE_OPEN -> ParsedBarcodeContent.Wifi.WifiEncryption.OPEN
                            Barcode.WiFi.TYPE_WEP -> ParsedBarcodeContent.Wifi.WifiEncryption.WEP
                            Barcode.WiFi.TYPE_WPA -> ParsedBarcodeContent.Wifi.WifiEncryption.WPA
                            else -> ParsedBarcodeContent.Wifi.WifiEncryption.WPA2
                        }
                    )
                }
            }
            Barcode.TYPE_CONTACT_INFO -> {
                barcode.contactInfo?.let { contact ->
                    ParsedBarcodeContent.Contact(
                        name = contact.name?.formattedName,
                        organization = contact.organization,
                        title = contact.title,
                        phones = contact.phones.mapNotNull { it.number },
                        emails = contact.emails.mapNotNull { it.address },
                        addresses = contact.addresses.mapNotNull { it.addressLines.joinToString(", ") },
                        urls = contact.urls ?: emptyList()
                    )
                }
            }
            Barcode.TYPE_EMAIL -> {
                barcode.email?.let { email ->
                    ParsedBarcodeContent.Email(
                        address = email.address ?: "",
                        subject = email.subject,
                        body = email.body
                    )
                }
            }
            Barcode.TYPE_PHONE -> {
                barcode.phone?.let { phone ->
                    ParsedBarcodeContent.Phone(number = phone.number ?: "")
                }
            }
            Barcode.TYPE_SMS -> {
                barcode.sms?.let { sms ->
                    ParsedBarcodeContent.Sms(
                        phoneNumber = sms.phoneNumber ?: "",
                        message = sms.message
                    )
                }
            }
            Barcode.TYPE_GEO -> {
                barcode.geoPoint?.let { geo ->
                    ParsedBarcodeContent.Geo(
                        latitude = geo.lat,
                        longitude = geo.lng
                    )
                }
            }
            Barcode.TYPE_PRODUCT -> {
                ParsedBarcodeContent.Product(
                    barcode = barcode.rawValue ?: "",
                    barcodeFormat = BarcodeFormat.fromMLKit(barcode.format),
                    isValid = validateProductBarcode(barcode.rawValue ?: ""),
                    country = getCountryByEanPrefix(barcode.rawValue ?: "")
                )
            }
            else -> ParsedBarcodeContent.PlainText(text = barcode.rawValue ?: "")
        }
    }

    private fun validateProductBarcode(code: String): Boolean {
        val digits = code.filter { it.isDigit() }
        if (digits.length !in listOf(8, 12, 13, 14)) return false
        val checkDigit = digits.last().digitToInt()
        val payload = digits.dropLast(1)
        var sum = 0
        for (i in payload.indices) {
            val digit = payload[payload.length - 1 - i].digitToInt()
            sum += if (i % 2 == 0) digit * 3 else digit
        }
        val calculated = (10 - (sum % 10)) % 10
        return calculated == checkDigit
    }

    private fun getCountryByEanPrefix(code: String): String? {
        if (code.length < 3) return null
        val prefix = code.take(3).toIntOrNull() ?: return null
        return when (prefix) {
            in 460..469 -> "Россия"
            in 400..440 -> "Германия"
            in 300..379 -> "Франция"
            in 450..459 -> "Япония"
            in 490..499 -> "Япония"
            in 690..695 -> "Китай"
            in 0..19 -> "США/Канада"
            in 30..39 -> "США/Канада"
            in 60..139 -> "США/Канада"
            in 500..509 -> "Великобритания"
            in 800..839 -> "Италия"
            in 840..849 -> "Испания"
            in 870..879 -> "Нидерланды"
            in 471..471 -> "Тайвань"
            in 880..880 -> "Южная Корея"
            in 885..885 -> "Таиланд"
            in 890..890 -> "Индия"
            else -> null
        }
    }

    private fun BarcodeFormat.toMLKitFormat(): Int {
        return when (this) {
            BarcodeFormat.QR_CODE -> Barcode.FORMAT_QR_CODE
            BarcodeFormat.AZTEC -> Barcode.FORMAT_AZTEC
            BarcodeFormat.DATA_MATRIX -> Barcode.FORMAT_DATA_MATRIX
            BarcodeFormat.PDF_417 -> Barcode.FORMAT_PDF417
            BarcodeFormat.EAN_13 -> Barcode.FORMAT_EAN_13
            BarcodeFormat.EAN_8 -> Barcode.FORMAT_EAN_8
            BarcodeFormat.UPC_A -> Barcode.FORMAT_UPC_A
            BarcodeFormat.UPC_E -> Barcode.FORMAT_UPC_E
            BarcodeFormat.CODE_128 -> Barcode.FORMAT_CODE_128
            BarcodeFormat.CODE_39 -> Barcode.FORMAT_CODE_39
            BarcodeFormat.CODE_93 -> Barcode.FORMAT_CODE_93
            BarcodeFormat.CODABAR -> Barcode.FORMAT_CODABAR
            BarcodeFormat.ITF -> Barcode.FORMAT_ITF
            BarcodeFormat.UNKNOWN -> Barcode.FORMAT_ALL_FORMATS
        }
    }
}