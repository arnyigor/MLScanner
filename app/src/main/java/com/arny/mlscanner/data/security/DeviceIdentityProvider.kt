package com.arny.mlscanner.data.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.*
import java.security.spec.X509EncodedKeySpec
import java.util.*

/**
 * Provides a stable device‑specific identifier backed by AndroidKeyStore.
 */
class DeviceIdentityProvider(private val context: Context) {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val prefs = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE)

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "securefield_device_binding"
        private const val PREFS_KEY_DEVICE_ID = "device_id_v2"
    }

    /**
     * Returns a stable device ID derived from the RSA public key stored in AndroidKeyStore.
     */
    fun getDeviceId(): String {
        prefs.getString(PREFS_KEY_DEVICE_ID, null)?.let { return it }

        val keyPair: KeyPair = if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as KeyStore.PrivateKeyEntry
            KeyPair(entry.certificate.publicKey, entry.privateKey)
        } else {
            generateKeyPair()
        }

        val publicKeyBytes = keyPair.public.encoded
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        val deviceId = Base64.encodeToString(hash, Base64.URL_SAFE or Base64.NO_WRAP)

        prefs.edit().putString(PREFS_KEY_DEVICE_ID, deviceId).apply()
        return deviceId
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_RSA,
            ANDROID_KEYSTORE
        )
        val params = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).apply {
            setKeySize(2048)
            setUserAuthenticationRequired(false)
            setRandomizedEncryptionRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    setIsStrongBoxBacked(true)
                } catch (_: Exception) {
                    // StrongBox not available – fallback to TEE
                }
            }
        }.build()
        generator.initialize(params)
        return generator.generateKeyPair()
    }

    /** Checks whether the key pair still exists in the keystore. */
    fun isKeyValid(): Boolean = keyStore.containsAlias(KEY_ALIAS)
}
