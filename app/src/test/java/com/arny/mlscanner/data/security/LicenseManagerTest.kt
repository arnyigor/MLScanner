package com.arny.mlscanner.data.security

import android.content.Context
import android.content.res.AssetManager
import android.os.Build
import android.util.Base64
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Date
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Тесты для {@link LicenseManager}.
 *
 * В тестовой среде заменяем:
 * 1) `masterKeySupplier` – возвращает один и тот же JDK‑AES ключ,
 * 2) `deviceIdProvider` – фиксированный строковый ID.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.O_MR1])
class LicenseManagerTest {

    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var mockAssetManager: AssetManager

    private lateinit var licenseManager: LicenseManager

    /* ------------------------------------------------------------- */
    /*  Ключи для подписи и проверки                                 */
    /* ------------------------------------------------------------- */

    private val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
    private val keyPair by lazy(kpg::generateKeyPair)
    private val publicKey get() = keyPair.public

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)

        // Создаём один AES‑ключ и фиксируем лямбду‑поставщик
        val aesKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val aesSupplier: () -> SecretKey = { aesKey }

        licenseManager = LicenseManager(
            context = mockContext,
            masterKeySupplier = aesSupplier
        )

        `when`(mockContext.assets).thenReturn(mockAssetManager)
    }

    /* ------------------------------------------------------------- */
    /*  Тесты – проверка корректности ID устройства                  */
    /* ------------------------------------------------------------- */

    @Test
    fun `should return mocked device ID from provider`() {
        val customId = "test-device-id-12345"
        licenseManager.deviceIdProvider = { customId }
        assertEquals(customId, licenseManager.currentDeviceId())
    }

    /* ------------------------------------------------------------- */
    /*  Тесты – шифрование/расшифровка                                 */
    /* ------------------------------------------------------------- */

    @Test
    fun `should encrypt and decrypt license data securely`() {
        val secret = "secret-license-info"
        val enc = licenseManager.encrypt(secret)
        assertNotEquals(secret, enc) // не должно быть открытым текстом
        val dec = licenseManager.decrypt(enc)
        assertEquals(secret, dec)
    }

    /* ------------------------------------------------------------- */
    /*  Тесты – проверка лицензии (логика unchanged)                  */
    /* ------------------------------------------------------------- */

    @Test
    fun `should verify valid license successfully`() {
        licenseManager.deviceIdProvider = { "DEVICE123" }
        licenseManager.publicKeyLoader = { publicKey }

        val encryptedDeviceId = licenseManager.encrypt("DEVICE123")
        val dataToSign = "$encryptedDeviceId|2030-12-31|scan,redact"
        val signature = signData(dataToSign, keyPair.private)

        val file = createTempLicenseFile(
            encryptedDeviceId = encryptedDeviceId,
            signature = signature,
            expiryDate = "2030-12-31",
            features = "scan,redact"
        )

        assertTrue(licenseManager.verifyLicense(file))
        file.delete()
    }

    @Test
    fun `should reject expired license`() {
        licenseManager.deviceIdProvider = { "DEVICE123" }
        licenseManager.publicKeyLoader = { publicKey }

        val encryptedDeviceId = licenseManager.encrypt("DEVICE123")
        val dataToSign = "$encryptedDeviceId|2020-01-01|scan"
        val signature = signData(dataToSign, keyPair.private)

        val file = createTempLicenseFile(
            encryptedDeviceId = encryptedDeviceId,
            signature = signature,
            expiryDate = "2020-01-01",
            features = "scan"
        )

        assertFalse(licenseManager.verifyLicense(file))
        file.delete()
    }

    @Test
    fun `should reject license with wrong device ID`() {
        licenseManager.deviceIdProvider = { "REAL_DEVICE" }
        licenseManager.publicKeyLoader = { publicKey }

        val encryptedDeviceId = licenseManager.encrypt("WRONG_DEVICE")
        val dataToSign = "$encryptedDeviceId|2030-12-31|scan"
        val signature = signData(dataToSign, keyPair.private)

        val file = createTempLicenseFile(
            encryptedDeviceId = encryptedDeviceId,
            signature = signature,
            expiryDate = "2030-12-31",
            features = "scan"
        )

        assertFalse(licenseManager.verifyLicense(file))
        file.delete()
    }

    @Test
    fun `should validate RSA signature correctly`() {
        val data = "test-data"
        val sig = signData(data, keyPair.private)
        val isValid = licenseManager.validateSignature(
            data,
            publicKey.encoded,
            sig
        )
        assertTrue(isValid)
    }

    @Test
    fun `should verify license expiration date`() {
        val future = Date(System.currentTimeMillis() + 86400000) // +1 день
        val past = Date(System.currentTimeMillis() - 86400000)   // -1 день

        assertTrue(licenseManager.isDateValid(future))
        assertFalse(licenseManager.isDateValid(past))
    }

    /* ------------------------------------------------------------- */
    /*  Вспомогательные методы для тестов                           */
    /* ------------------------------------------------------------- */

    private fun signData(data: String, privKey: java.security.PrivateKey): String {
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(privKey)
        sig.update(data.toByteArray())
        return Base64.encodeToString(sig.sign(), Base64.NO_WRAP)
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
