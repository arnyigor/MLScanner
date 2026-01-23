package com.arny.mlscanner.data.security

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaDrm
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Менеджер лицензий.
 *
 * В production‑режиме использует `AndroidKeystore` для хранения мастер‑ключа,
 * но в тестах можно задать свою реализацию через `masterKeySupplier`
 * и переопределить загрузчик публичного ключа (`publicKeyLoader`).
 */
class LicenseManager(
    private val context: Context,
    private val masterKeySupplier: () -> SecretKey = { DefaultKeyProvider.getOrCreateMasterKey() }
) {

    /* ------------------------------------------------------------------ */
    /*  Внутренние поля (публичные в тестах)                              */
    /* ------------------------------------------------------------------ */

    /** Поставщик уникального ID устройства – можно переопределить в тестах. */
    internal var deviceIdProvider: () -> String = { generateDeviceId() }

    /**
     * Поставщик публичного RSA‑ключа.
     *
     * По умолчанию использует загрузку из assets, но в тестах
     * можно задать любой `PublicKey`.
     */
    internal var publicKeyLoader: () -> PublicKey = { loadPublicKeyFromAssets() }

    companion object {
        private const val TAG = "LicenseManager"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val PUBLIC_KEY_PATH = "public_key.pem"
    }

    /* ------------------------------------------------------------------ */
    /*  Уникальный ID устройства                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Публичный API, который использует `deviceIdProvider`.
     *
     * @return текущее уникальное устройство‑ID
     */
    fun currentDeviceId(): String = deviceIdProvider()

    @SuppressLint("HardwareIds")
    private fun generateDeviceId(): String {
        return try {
            // Widevine UUID, который можно использовать на всех версиях ≥ Lollipop.
            val widevineUuid =
                UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

            val idBytes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                MediaDrm(widevineUuid).use { drm ->
                    drm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
                }
            } else {
                // Fallback‑путь для SDK < 28.
                // Android‑ID считается достаточным уникальным идентификатором
                // (и присутствует уже с API 3). Если вдруг он не существует,
                // генерируем случайный UUID и используем его как резервный вариант.
                val androidId = Settings.Secure
                    .getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                (androidId ?: UUID.randomUUID().toString())
                    .toByteArray(Charset.defaultCharset())
            }

            // Хэшируем, чтобы получить фиксированную длину и не раскрывать
            // оригинальный идентификатор.
            MessageDigest.getInstance("SHA-256")
                .digest(idBytes)
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            // Бэкапы:
            // 1. Android‑ID, если MediaDrm недоступен или бросил исключение.
            // 2. Фиксированная строка «unknown», чтобы не возвращать пустую
            //    или некорректную ID в продакшн‑кода.
            Settings.Secure
                .getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: "unknown"
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Шифрование / Расшифровка                                          */
    /* ------------------------------------------------------------------ */

    /**
     * Зашифровать строку с помощью AES‑GCM.
     *
     * @param plain открытый текст
     * @return Base64‑строка, содержащая IV + ciphertext
     */
    fun encrypt(plain: String): String {
        val key = masterKeySupplier()
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key)
            val iv = iv
            val ct = doFinal(plain.toByteArray(Charset.defaultCharset()))
            Base64.encodeToString(iv + ct, Base64.NO_WRAP)
        }
    }

    /**
     * Расшифровать строку, зашифрованную `encrypt`.
     *
     * @param ciphered Base64‑строка IV+ciphertext
     * @return открытый текст или пустая строка при ошибке
     */
    internal fun decrypt(ciphered: String): String {
        return try {
            val combined = Base64.decode(ciphered, Base64.NO_WRAP)
            val iv = combined.sliceArray(0 until GCM_IV_LENGTH)
            val ct = combined.sliceArray(GCM_IV_LENGTH until combined.size)

            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(
                    Cipher.DECRYPT_MODE,
                    masterKeySupplier(),
                    GCMParameterSpec(GCM_TAG_LENGTH, iv)
                )
                String(doFinal(ct), Charset.defaultCharset())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting data", e)
            ""
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Проверка лицензии                                                 */
    /* ------------------------------------------------------------------ */

    fun verifyLicense(licenseFile: File): Boolean {
        return try {
            val json = JSONObject(licenseFile.readText())
            val encryptedDeviceId = json.getString("device_id")
            val signature = json.getString("signature")
            val expiryDate = json.getString("expiry_date")
            val features = if (json.has("features")) json.getString("features") else ""

            // 1. Проверка срока действия
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val expiry = dateFormat.parse(expiryDate)
            if (expiry == null || System.currentTimeMillis() > expiry.time) {
                Log.e(TAG, "License has expired or invalid date")
                return false
            }

            // 2. Проверка подписи
            val publicKey = publicKeyLoader()
            val dataToSign = "$encryptedDeviceId|$expiryDate|$features"
            if (!verifySignature(dataToSign, signature, publicKey)) {
                Log.e(TAG, "License signature verification failed")
                return false
            }

            // 3. Проверка ID устройства
            val currentDeviceId = currentDeviceId()
            val decryptedDeviceId = decrypt(encryptedDeviceId)
            if (decryptedDeviceId != currentDeviceId) {
                Log.e(TAG, "Device ID mismatch")
                return false
            }

            Log.d(TAG, "License verified successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying license", e)
            false
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Верификация подписи                                               */
    /* ------------------------------------------------------------------ */

    internal fun verifySignature(
        data: String,
        signature: String,
        publicKey: PublicKey
    ): Boolean {
        return try {
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(publicKey)
            sig.update(data.toByteArray())
            sig.verify(Base64.decode(signature, Base64.NO_WRAP))
        } catch (e: Exception) {
            Log.e(TAG, "Error during signature verification", e)
            false
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Загрузка публичного ключа                                         */
    /* ------------------------------------------------------------------ */

    @Throws(Exception::class)
    private fun loadPublicKeyFromAssets(): PublicKey {
        val keyFactory = KeyFactory.getInstance("RSA")
        val keyText =
            context.assets.open(PUBLIC_KEY_PATH).bufferedReader().use { it.readText() }
        val keyBytes = Base64.decode(
            keyText
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s+".toRegex(), ""),
            Base64.DEFAULT
        )
        val spec = X509EncodedKeySpec(keyBytes)
        return keyFactory.generatePublic(spec)
    }

    /* ------------------------------------------------------------------ */
    /*  Дополнительные методы для тестов                                 */
    /* ------------------------------------------------------------------ */

    fun isDateValid(date: Date): Boolean =
        System.currentTimeMillis() <= date.time

    @SuppressLint("MissingPermission")
    fun getLicenseFeatures(licenseFile: File): Set<String> {
        return try {
            val json = JSONObject(licenseFile.readText())
            val featuresStr = json.optString("features", "[]")
            val featuresArray =
                JSONObject(featuresStr.replace("'", "\"")).getJSONArray("features")
            (0 until featuresArray.length()).map { featuresArray.getString(it) }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun isTrialActive(): Boolean = true

    @RequiresApi(Build.VERSION_CODES.O)
    fun validateSignature(
        data: String,
        publicKeyBytes: ByteArray,
        signatureBase64: String
    ): Boolean {
        return try {
            val keyFactory = KeyFactory.getInstance("RSA")
            val pubKey =
                keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes))
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(pubKey)
            sig.update(data.toByteArray())
            sig.verify(java.util.Base64.getDecoder().decode(signatureBase64))
        } catch (e: Exception) {
            Log.e(TAG, "Error during signature verification", e)
            false
        }
    }
}

/**
 * Вспомогательный объект для получения/создания мастер‑ключа из `AndroidKeyStore`.
 *
 * Делается отдельным объектом, чтобы не мешать ссылке по умолчанию в конструкторе.
 */
object DefaultKeyProvider {
    private const val MASTER_KEY_ALIAS = "SecureField_MasterKey"

    fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        return if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
        } else {
            val spec = android.security.keystore.KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            ).apply { init(spec) }.generateKey()
        }
    }
}
