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
 * Менеджер лицензирования
 */
class LicenseManager(private val context: Context) {
    companion object {
        private const val TAG = "LicenseManager"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val MASTER_KEY_ALIAS = "SecureField_MasterKey"
        private const val PUBLIC_KEY_PATH = "public_key.pem"
    }

    // Для тестов — можно переопределить
    internal var deviceIdProvider: () -> String = { generateDeviceId() }
    internal var publicKeyLoader: () -> PublicKey = { loadPublicKeyFromAssets() }

    /**
     * Генерация уникального ID устройства
     */
    @SuppressLint("HardwareIds")
    fun generateDeviceId(): String {
        return try {
            val widevineUuid = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
            val mediaDrm = MediaDrm(widevineUuid)
            val deviceId = mediaDrm.getPropertyByteArray(MediaDrm.PROPERTY_DEVICE_UNIQUE_ID)
            mediaDrm.release()
            MessageDigest.getInstance("SHA-256").digest(deviceId).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
        }
    }

    /**
     * Проверка лицензии
     */
    fun verifyLicense(licenseFile: File): Boolean {
        return try {
            val licenseContent = licenseFile.readText()
            val json = JSONObject(licenseContent)

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
            val currentDeviceId = deviceIdProvider()
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

    /**
     * Шифрование данных (для тестов и генерации лицензий)
     */
    fun encrypt(data: String): String {
        val key = getOrCreateMasterKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(data.toByteArray(Charset.defaultCharset()))
        val combined = iv + ciphertext
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * Расшифровка данных
     */
    internal fun decrypt(encryptedData: String): String {
        try {
            val combined = Base64.decode(encryptedData, Base64.NO_WRAP)
            val iv = combined.sliceArray(0 until GCM_IV_LENGTH)
            val ciphertext = combined.sliceArray(GCM_IV_LENGTH until combined.size)

            val key = getOrCreateMasterKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val decrypted = cipher.doFinal(ciphertext)
            return String(decrypted, Charset.defaultCharset())
        } catch (e: Exception) {
            Log.e(TAG, "Error decrypting data", e)
            return ""
        }
    }

    /**
     * Проверка подписи
     */
    internal fun verifySignature(data: String, signature: String, publicKey: PublicKey): Boolean {
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

    /**
     * Загрузка публичного ключа из assets
     */
    @Throws(Exception::class)
    internal fun loadPublicKeyFromAssets(): PublicKey {
        val keyFactory = KeyFactory.getInstance("RSA")
        val keyText = context.assets.open(PUBLIC_KEY_PATH).bufferedReader().use { it.readText() }
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

    /**
     * Получение или создание мастер-ключа
     */
    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        return if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
            keyStore.getKey(MASTER_KEY_ALIAS, null) as SecretKey
        } else {
            val keyGenParameterSpec = android.security.keystore.KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or android.security.keystore.KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            val keyGenerator = KeyGenerator.getInstance(
                android.security.keystore.KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
    }

    // === Дополнительные методы для тестов ===

    fun isDateValid(date: Date): Boolean {
        return System.currentTimeMillis() <= date.time
    }

    fun getLicenseFeatures(licenseFile: File): Set<String> {
        return try {
            val json = JSONObject(licenseFile.readText())
            val featuresStr = json.optString("features", "[]")
            val featuresArray = JSONObject(featuresStr.replace("'", "\"")).getJSONArray("features")
            val features = mutableSetOf<String>()
            for (i in 0 until featuresArray.length()) {
                features.add(featuresArray.getString(i))
            }
            features
        } catch (e: Exception) {
            emptySet()
        }
    }

    fun isTrialActive(): Boolean {
        // Простая заглушка: пробный период 7 дней с установки
        return true
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun validateSignature(data: String, publicKeyBytes: ByteArray, signatureBase64: String): Boolean {
        return try {
            val keyFactory = KeyFactory.getInstance("RSA")
            val publicKey = keyFactory.generatePublic(X509EncodedKeySpec(publicKeyBytes))
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(publicKey)
            sig.update(data.toByteArray())
            // Decode Base64 signature
            sig.verify(java.util.Base64.getDecoder().decode(signatureBase64))
        } catch (e: Exception) {
            Log.e(TAG, "Error during signature verification", e)
            false
        }
    }
}