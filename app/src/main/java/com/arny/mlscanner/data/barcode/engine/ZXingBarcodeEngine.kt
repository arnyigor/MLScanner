package com.arny.mlscanner.data.barcode.engine

import android.graphics.Bitmap
import com.google.zxing.Binarizer
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.arny.mlscanner.domain.models.barcode.BarcodeContentType
import com.arny.mlscanner.domain.models.barcode.BarcodeFormat
import com.arny.mlscanner.domain.models.barcode.BarcodeResult
import com.arny.mlscanner.domain.models.barcode.BarcodeScanConfig
import com.arny.mlscanner.domain.models.barcode.ParsedBarcodeContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ZXingBarcodeEngine : BarcodeEngine {

    override val name: String = "ZXing"

    private val hints = mapOf(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.CHARACTER_SET to "UTF-8",
        DecodeHintType.ALSO_INVERTED to true
    )

    override suspend fun isAvailable(): Boolean = true

    override suspend fun scan(
        bitmap: Bitmap,
        config: BarcodeScanConfig
    ): List<BarcodeResult> = withContext(Dispatchers.Default) {
        val results = mutableListOf<BarcodeResult>()

        try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source: LuminanceSource = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            val decodeHints = hints.toMutableMap()
            if (config.allowedFormats.isNotEmpty()) {
                decodeHints[DecodeHintType.POSSIBLE_FORMATS] =
                    config.allowedFormats.mapNotNull { it.toZXingFormat() }
            }

            try {
                val reader = MultiFormatReader()
                val result = reader.decode(binaryBitmap, decodeHints)
                convertResult(result)?.let { results.add(it) }
            } catch (e: NotFoundException) {
                // Not found - this is normal
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        results
    }

    override fun release() {}

    private fun convertResult(result: Result): BarcodeResult? {
        val rawValue = result.text ?: return null
        val format = BarcodeFormat.fromZXing(result.barcodeFormat)
        val contentType = detectContentType(rawValue, format)
        val parsedContent = parseZXingContent(rawValue, format, contentType)

        val boundingBox = result.resultPoints?.let { points ->
            if (points.size >= 2) {
                val xs = points.map { it.x.toInt() }
                val ys = points.map { it.y.toInt() }
                android.graphics.Rect(
                    xs.min(), ys.min(),
                    xs.max(), ys.max()
                )
            } else null
        }

        return BarcodeResult(
            rawValue = rawValue,
            format = format,
            contentType = contentType,
            parsedContent = parsedContent,
            boundingBox = boundingBox,
            confidence = 0.9f,
            engineSource = name
        )
    }

    private fun detectContentType(rawValue: String, format: BarcodeFormat): BarcodeContentType {
        return when {
            rawValue.startsWith("http://") || rawValue.startsWith("https://") -> BarcodeContentType.URL
            rawValue.startsWith("WIFI:") -> BarcodeContentType.WIFI
            rawValue.startsWith("BEGIN:VCARD") || rawValue.startsWith("MECARD:") -> BarcodeContentType.CONTACT_VCARD
            rawValue.startsWith("mailto:") || rawValue.matches(Regex("^[\\w.+-]+@[\\w-]+\\.[\\w.]+$")) -> BarcodeContentType.EMAIL
            rawValue.startsWith("tel:") || rawValue.startsWith("TEL:") -> BarcodeContentType.PHONE
            rawValue.startsWith("smsto:") || rawValue.startsWith("SMSTO:") || rawValue.startsWith("sms:") -> BarcodeContentType.SMS
            rawValue.startsWith("geo:") -> BarcodeContentType.GEO
            format in listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E) -> BarcodeContentType.PRODUCT
            else -> BarcodeContentType.TEXT
        }
    }

    private fun parseZXingContent(rawValue: String, format: BarcodeFormat, contentType: BarcodeContentType): ParsedBarcodeContent? {
        return when (contentType) {
            BarcodeContentType.URL -> ParsedBarcodeContent.Url(url = rawValue)
            BarcodeContentType.WIFI -> parseWifi(rawValue)
            BarcodeContentType.CONTACT_VCARD -> parseVCard(rawValue)
            BarcodeContentType.EMAIL -> ParsedBarcodeContent.Email(address = rawValue.removePrefix("mailto:"))
            BarcodeContentType.PHONE -> ParsedBarcodeContent.Phone(number = rawValue.removePrefix("tel:").removePrefix("TEL:"))
            BarcodeContentType.SMS -> parseSms(rawValue)
            BarcodeContentType.GEO -> parseGeo(rawValue)
            BarcodeContentType.PRODUCT -> ParsedBarcodeContent.Product(
                barcode = rawValue,
                barcodeFormat = format,
                isValid = validateEan(rawValue),
                country = getCountryPrefix(rawValue)
            )
            else -> ParsedBarcodeContent.PlainText(text = rawValue)
        }
    }

    private fun parseWifi(raw: String): ParsedBarcodeContent.Wifi {
        val params = mutableMapOf<String, String>()
        val content = raw.removePrefix("WIFI:")
        val regex = Regex("([TSPH]):([^;]*)")
        regex.findAll(content).forEach { match ->
            params[match.groupValues[1]] = match.groupValues[2]
        }
        return ParsedBarcodeContent.Wifi(
            ssid = params["S"] ?: "",
            password = params["P"],
            encryptionType = when (params["T"]?.uppercase()) {
                "WEP" -> ParsedBarcodeContent.Wifi.WifiEncryption.WEP
                "WPA" -> ParsedBarcodeContent.Wifi.WifiEncryption.WPA
                "WPA2" -> ParsedBarcodeContent.Wifi.WifiEncryption.WPA2
                "nopass", "" -> ParsedBarcodeContent.Wifi.WifiEncryption.OPEN
                else -> ParsedBarcodeContent.Wifi.WifiEncryption.WPA
            }
        )
    }

    private fun parseVCard(raw: String): ParsedBarcodeContent.Contact {
        val name = Regex("FN:(.+)").find(raw)?.groupValues?.get(1)
        val org = Regex("ORG:(.+)").find(raw)?.groupValues?.get(1)
        val phones = Regex("TEL[^:]*:(.+)").findAll(raw).map { it.groupValues[1].trim() }.toList()
        val emails = Regex("EMAIL[^:]*:(.+)").findAll(raw).map { it.groupValues[1].trim() }.toList()
        return ParsedBarcodeContent.Contact(name = name, organization = org, phones = phones, emails = emails, rawVCard = raw)
    }

    private fun parseSms(raw: String): ParsedBarcodeContent.Sms {
        val cleaned = raw.removePrefix("smsto:").removePrefix("SMSTO:").removePrefix("sms:")
        val parts = cleaned.split(":", limit = 2)
        return ParsedBarcodeContent.Sms(phoneNumber = parts.getOrElse(0) { "" }, message = parts.getOrNull(1))
    }

    private fun parseGeo(raw: String): ParsedBarcodeContent.Geo {
        val cleaned = raw.removePrefix("geo:")
        val parts = cleaned.split(",")
        return ParsedBarcodeContent.Geo(
            latitude = parts.getOrNull(0)?.toDoubleOrNull() ?: 0.0,
            longitude = parts.getOrNull(1)?.toDoubleOrNull() ?: 0.0
        )
    }

    private fun validateEan(code: String): Boolean {
        val digits = code.filter { it.isDigit() }
        if (digits.length !in listOf(8, 12, 13, 14)) return false
        val check = digits.last().digitToInt()
        val payload = digits.dropLast(1)
        var sum = 0
        for (i in payload.indices) {
            val d = payload[payload.length - 1 - i].digitToInt()
            sum += if (i % 2 == 0) d * 3 else d
        }
        return (10 - (sum % 10)) % 10 == check
    }

    private fun getCountryPrefix(code: String): String? {
        if (code.length < 3) return null
        val prefix = code.take(3).toIntOrNull() ?: return null
        return when (prefix) {
            in 460..469 -> "Россия"
            in 400..440 -> "Германия"
            in 300..379 -> "Франция"
            in 690..695 -> "Китай"
            in 0..19 -> "США/Канада"
            in 30..39 -> "США/Канада"
            in 60..139 -> "США/Канада"
            in 500..509 -> "Великобритания"
            else -> null
        }
    }

    private fun BarcodeFormat.toZXingFormat(): com.google.zxing.BarcodeFormat? {
        return when (this) {
            BarcodeFormat.QR_CODE -> com.google.zxing.BarcodeFormat.QR_CODE
            BarcodeFormat.AZTEC -> com.google.zxing.BarcodeFormat.AZTEC
            BarcodeFormat.DATA_MATRIX -> com.google.zxing.BarcodeFormat.DATA_MATRIX
            BarcodeFormat.PDF_417 -> com.google.zxing.BarcodeFormat.PDF_417
            BarcodeFormat.EAN_13 -> com.google.zxing.BarcodeFormat.EAN_13
            BarcodeFormat.EAN_8 -> com.google.zxing.BarcodeFormat.EAN_8
            BarcodeFormat.UPC_A -> com.google.zxing.BarcodeFormat.UPC_A
            BarcodeFormat.UPC_E -> com.google.zxing.BarcodeFormat.UPC_E
            BarcodeFormat.CODE_128 -> com.google.zxing.BarcodeFormat.CODE_128
            BarcodeFormat.CODE_39 -> com.google.zxing.BarcodeFormat.CODE_39
            BarcodeFormat.CODE_93 -> com.google.zxing.BarcodeFormat.CODE_93
            BarcodeFormat.CODABAR -> com.google.zxing.BarcodeFormat.CODABAR
            BarcodeFormat.ITF -> com.google.zxing.BarcodeFormat.ITF
            BarcodeFormat.UNKNOWN -> null
        }
    }
}