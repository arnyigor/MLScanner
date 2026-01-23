package com.arny.mlscanner.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets.UTF_8
import android.util.Base64

/**
 * Singleton‑обёртка над EncryptedSharedPreferences.
 *
 * @param context applicationContext, чтобы не держать ссылку на Activity/Fragment.
 */
class SecurePrefs private constructor(context: Context) {

    /* ------------------------------------------------------------------ */
    /*  Внутренние детали (private + PublishedApi)                      */
    /* ------------------------------------------------------------------ */

    // Создание или получение мастер‑ключа
    @PublishedApi
    internal val masterKeyAlias =
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    // Инициализация EncryptedSharedPreferences
    @PublishedApi
    internal val prefs = EncryptedSharedPreferences.create(
        "PreferencesFilename",
        masterKeyAlias,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /* ------------------------------------------------------------------ */
    /*  Публичные типобезопасные методы                                 */
    /* ------------------------------------------------------------------ */

    fun getInt(key: String, defaultValue: Int = 0): Int =
        prefs.getInt(key, defaultValue)

    fun getLong(key: String, defaultValue: Long = 0L): Long =
        prefs.getLong(key, defaultValue)

    fun getFloat(key: String, defaultValue: Float = 0f): Float =
        prefs.getFloat(key, defaultValue)

    fun getDouble(key: String, defaultValue: Double = 0.0): Double =
        java.lang.Double.longBitsToDouble(
            prefs.getLong(key, java.lang.Double.doubleToRawLongBits(defaultValue))
        )

    fun getString(key: String, defaultValue: String? = null): String? =
        prefs.getString(key, defaultValue)

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean =
        prefs.getBoolean(key, defaultValue)

    /* ------------------------------------------------------------------ */
    /*  Публичные методы записи (apply() – асинхронно)                   */
    /* ------------------------------------------------------------------ */

    fun putInt(key: String, value: Int) {
        prefs.edit { putInt(key, value) }
    }

    fun putLong(key: String, value: Long) {
        prefs.edit { putLong(key, value) }
    }

    fun putFloat(key: String, value: Float) {
        prefs.edit { putFloat(key, value) }
    }

    fun putDouble(key: String, value: Double) {
        prefs.edit { putDouble(key, value) }   // расширение ниже
    }

    fun putString(key: String, value: String?) {
        prefs.edit { putString(key, value) }
    }

    fun putBoolean(key: String, value: Boolean) {
        prefs.edit { putBoolean(key, value) }
    }

    /* ------------------------------------------------------------------ */
    /*  Удаление и получение всех пар ключ‑значение                      */
    /* ------------------------------------------------------------------ */

    /** Возвращает immutable Map с текущими значениями. */
    fun getAll(): Map<String, *> = prefs.all

    /** Удаляет один или несколько ключей. */
    fun remove(vararg keys: String) {
        prefs.edit { keys.forEach { remove(it) } }
    }

    /* ------------------------------------------------------------------ */
    /*  Additional secure methods (now simplified, using EncryptedSharedPreferences) */
    /* ------------------------------------------------------------------ */

    /**
     * Saves a secure string value (now simplified - just use putString)
     */
    fun saveSecureString(key: String, value: String) {
        prefs.edit { putString(key, value) }
    }

    /**
     * Gets a secure string value (now simplified - just use getString)
     */
    fun getSecureString(key: String, defaultValue: String = ""): String {
        return prefs.getString(key, defaultValue) ?: defaultValue
    }

    /**
     * Saves a secure boolean value (now simplified - just use putBoolean)
     */
    fun saveSecureBoolean(key: String, value: Boolean) {
        prefs.edit { putBoolean(key, value) }
    }

    /**
     * Gets a secure boolean value (now simplified - just use getBoolean)
     */
    fun getSecureBoolean(key: String, defaultValue: Boolean = false): Boolean {
        return prefs.getBoolean(key, defaultValue)
    }

    /**
     * Saves a secure int value (now simplified - just use putInt)
     */
    fun saveSecureInt(key: String, value: Int) {
        prefs.edit { putInt(key, value) }
    }

    /**
     * Gets a secure int value (now simplified - just use getInt)
     */
    fun getSecureInt(key: String, defaultValue: Int = 0): Int {
        return prefs.getInt(key, defaultValue)
    }

    /**
     * Saves a secure float value (now simplified - just use putFloat)
     */
    fun saveSecureFloat(key: String, value: Float) {
        prefs.edit { putFloat(key, value) }
    }

    /**
     * Gets a secure float value (now simplified - just use getFloat)
     */
    fun getSecureFloat(key: String, defaultValue: Float = 0f): Float {
        return prefs.getFloat(key, defaultValue)
    }

    /**
     * Saves a secure long value (now simplified - just use putLong)
     */
    fun saveSecureLong(key: String, value: Long) {
        prefs.edit { putLong(key, value) }
    }

    /**
     * Gets a secure long value (now simplified - just use getLong)
     */
    fun getSecureLong(key: String, defaultValue: Long = 0L): Long {
        return prefs.getLong(key, defaultValue)
    }

    /**
     * Checks for the presence of a secure key
     */
    fun containsSecureKey(key: String): Boolean {
        return prefs.contains(key)
    }

    /**
     * Clears all secure preferences
     */
    fun clearSecurePrefs() {
        prefs.edit { clear() }
    }

    /* ------------------------------------------------------------------ */
    /*  Singleton‑обёртка (double-checked locking)                       */
    /* ------------------------------------------------------------------ */

    companion object {
        @Volatile
        private var INSTANCE: SecurePrefs? = null

        /**
         * Возвращает единственный экземпляр. Инициализируется при первом вызове.
         *
         * @param context Context нужен только для создания EncryptedSharedPreferences,
         *               но хранится как applicationContext, чтобы избежать утечки памяти.
         */
        fun getInstance(context: Context): SecurePrefs =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecurePrefs(context.applicationContext).also { INSTANCE = it }
            }
    }

}

/* -------------------------------------------------------------------------- */
/*  Расширения для работы с Double в SharedPreferences                       */
/* -------------------------------------------------------------------------- */

fun SharedPreferences.Editor.putDouble(
    key: String?,
    value: Double
): SharedPreferences.Editor =
    putLong(key, java.lang.Double.doubleToRawLongBits(value))
