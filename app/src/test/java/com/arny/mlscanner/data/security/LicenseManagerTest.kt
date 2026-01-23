package com.arny.mlscanner.data.security

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.text.SimpleDateFormat
import java.util.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.O_MR1]) // Требуется для KeyStore
class LicenseManagerTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockAssetManager: AssetManager

    private lateinit var licenseManager: LicenseManager

    private lateinit var keyPair: java.security.KeyPair
    private lateinit var publicKey: PublicKey

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        licenseManager = LicenseManager(mockContext)

        // Генерация ключей для тестов
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        keyPair = kpg.generateKeyPair()
        publicKey = keyPair.public

        // Мок assets
        `when`(mockContext.assets).thenReturn(mockAssetManager)
    }

    // === Тесты ===

    @Test
    fun `should generate unique device ID correctly`() {
        // Мокаем ANDROID_ID как fallback
        `when`(mockContext.contentResolver).thenReturn(null)
        licenseManager.deviceIdProvider = { "test-device-id-12345" }

        val deviceId = licenseManager.generateDeviceId()
        assertEquals("test-device-id-12345", deviceId)
    }

    @Test
    fun `should verify valid license successfully`() {
        // Подготовка данных
        licenseManager.deviceIdProvider = { "DEVICE123" }
        licenseManager.publicKeyLoader = { publicKey }

        val encryptedDeviceId = licenseManager.encrypt("DEVICE123")
        val dataToSign = "$encryptedDeviceId|2030-12-31|scan,redact"
        val signature = signData(dataToSign, keyPair.private)

        val licenseFile = createTempLicenseFile(
            encryptedDeviceId = encryptedDeviceId,
            signature = signature,
            expiryDate = "2030-12-31",
            features = "scan,redact"
        )

        assertTrue(licenseManager.verifyLicense(licenseFile))
        licenseFile.delete()
    }

    @Test
    fun `should reject expired license`() {
        licenseManager.deviceIdProvider = { "DEVICE123" }
        licenseManager.publicKeyLoader = { publicKey }

        val encryptedDeviceId = licenseManager.encrypt("DEVICE123")
        val dataToSign = "$encryptedDeviceId|2020-01-01|scan"
        val signature = signData(dataToSign, keyPair.private)

        val licenseFile = createTempLicenseFile(
            encryptedDeviceId = encryptedDeviceId,
            signature = signature,
            expiryDate = "2020-01-01",
            features = "scan"
        )

        assertFalse(licenseManager.verifyLicense(licenseFile))
        licenseFile.delete()
    }

    @Test
    fun `should reject license with wrong device ID`() {
        licenseManager.deviceIdProvider = { "REAL_DEVICE" }
        licenseManager.publicKeyLoader = { publicKey }

        val encryptedDeviceId = licenseManager.encrypt("WRONG_DEVICE")
        val dataToSign = "$encryptedDeviceId|2030-12-31|scan"
        val signature = signData(dataToSign, keyPair.private)

        val licenseFile = createTempLicenseFile(
            encryptedDeviceId = encryptedDeviceId,
            signature = signature,
            expiryDate = "2030-12-31",
            features = "scan"
        )

        assertFalse(licenseManager.verifyLicense(licenseFile))
        licenseFile.delete()
    }

    @Test
    fun `should validate RSA signature correctly`() {
        val data = "test-data"
        val signature = signData(data, keyPair.private)
        val isValid = licenseManager.validateSignature(data, publicKey.encoded, signature)
        assertTrue(isValid)
    }

    @Test
    fun `should encrypt and decrypt license data securely`() {
        val testData = "secret-license-info"
        val encrypted = licenseManager.encrypt(testData)
        val decrypted = licenseManager.decrypt(encrypted)
        assertEquals(testData, decrypted)
    }

    @Test
    fun `should verify license expiration date`() {
        val future = Date(System.currentTimeMillis() + 86400000) // +1 день
        val past = Date(System.currentTimeMillis() - 86400000)   // -1 день

        assertTrue(licenseManager.isDateValid(future))
        assertFalse(licenseManager.isDateValid(past))
    }

    // === Вспомогательные методы ===

    private fun signData(data: String, privateKey: java.security.PrivateKey): String {
        val signature = java.security.Signature.getInstance("SHA256withRSA")
        signature.initSign(privateKey)
        signature.update(data.toByteArray())
        return android.util.Base64.encodeToString(signature.sign(), android.util.Base64.NO_WRAP)
    }

    private fun createTempLicenseFile(
        encryptedDeviceId: String,
        signature: String,
        expiryDate: String,
        features: String
    ): File {
        val json = """
            {
                "device_id": "$encryptedDeviceId",
                "signature": "$signature",
                "expiry_date": "$expiryDate",
                "features": "$features"
            }
        """.trimIndent()

        return Files.createTempFile("license_", ".lic").toFile().apply {
            writeText(json)
            deleteOnExit()
        }
    }
}