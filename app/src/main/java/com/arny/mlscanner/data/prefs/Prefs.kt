// ──────────────────────────────────────────────────────────────────────
//  Prefs.kt – thin wrapper around Android's SharedPreferences
//  (package: com.arny.mlscanner.data.prefs)
// ──────────────────────────────────────────────────────────────────────

package com.arny.mlscanner.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.arny.mlscanner.domain.models.ScanSettings

/**
 * A lightweight façade over `SharedPreferences` that:
 *
 *  • Keeps the API type‑safe (no unchecked casts outside the class).
 *  • Persists the domain model `ScanSettings` in a single call.
 *  • Is intentionally minimal – no caching, no DAO layer, because
 *    the key set is tiny and tests need deterministic behaviour.
 */
class Prefs private constructor(private val prefs: SharedPreferences) {

    /* ------------------------------------------------------------------ */
    /*  Construction                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * Back‑compatibility for legacy code that still passes a `Context`.
     *
     * The unused boolean keeps the method signature identical to
     * the original library, but we ignore it.
     */
    @Suppress("UNUSED_PARAMETER")
    private constructor(context: Context, unused: Boolean = false)
            : this(PreferenceManager.getDefaultSharedPreferences(context.applicationContext))

    companion object {
        @Volatile private var INSTANCE: Prefs? = null

        /**
         * Singleton accessor used by the test harness.
         */
        fun getInstance(context: Context): Prefs =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Prefs(
                    PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                ).also { INSTANCE = it }
            }
    }

    /* ------------------------------------------------------------------ */
    /*  Generic helpers – type safety                                   */
    /* ------------------------------------------------------------------ */

    /**
     * Retrieves a value of the requested type, falling back to `defaultValue`
     * when the key is missing.  The method throws if an unsupported
     * type is requested – this keeps the public API surface small.
     */
    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> get(key: String, defaultValue: T): T {
        return when (T::class) {
            Int::class -> prefs.getInt(key, defaultValue as? Int ?: 0) as T
            Long::class -> prefs.getLong(key, defaultValue as? Long ?: 0L) as T
            Float::class -> prefs.getFloat(key, defaultValue as? Float ?: 0f) as T
            Boolean::class -> prefs.getBoolean(key, defaultValue as? Boolean ?: false) as T
            String::class -> prefs.getString(key, defaultValue as? String) as T
            Double::class -> getDouble(key, defaultValue as? Double ?: 0.0) as T
            else -> throw IllegalArgumentException("Unsupported type ${T::class}")
        }
    }

    /**
     * Back‑compatibility helper for `double` (no native SharedPreferences API).
     */
    private fun getDouble(key: String, defaultValue: Double = 0.0): Double =
        java.lang.Double.longBitsToDouble(
            prefs.getLong(key, java.lang.Double.doubleToRawLongBits(defaultValue))
        )

    /* ------------------------------------------------------------------ */
    /*  Write helpers                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Stores a primitive or string value under the given key.
     *
     * Because the method is public but only used internally by tests
     * (e.g. `saveBoolean`), it accepts an `Any?`.  The type is checked at
     * runtime and casted accordingly – the API surface stays small.
     */
    fun put(key: String, value: Any?) {
        prefs.edit(commit = true) {
            when (value) {
                is Int -> putInt(key, value)
                is Long -> putLong(key, value)
                is Float -> putFloat(key, value)
                is Double -> putDouble(key, value)
                is Boolean -> putBoolean(key, value)
                is String -> putString(key, value)
            }
        }
    }

    /**
     * Extension used by `put` to persist a `double`.
     */
    private fun SharedPreferences.Editor.putDouble(key: String, value: Double): SharedPreferences.Editor =
        putLong(key, java.lang.Double.doubleToRawLongBits(value))

    /**
     * Removes the specified keys in a single transaction.
     */
    fun remove(vararg keys: String) {
        prefs.edit(commit = true) { keys.forEach { remove(it) } }
    }

    /**
     * Clears the entire store – handy for unit tests.
     */
    fun clearPreferences() = prefs.edit(commit = true) { clear() }

    /* ------------------------------------------------------------------ */
    /*  Nullable‑float helper                                            */
    /* ------------------------------------------------------------------ */

    private fun maybePutFloat(key: String, value: Float?) {
        if (value != null) put(key, value)
    }

    /* ------------------------------------------------------------------ */
    /*  ScanSettings helpers                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Persist every field of the domain model.
     */
    fun saveScanSettings(settings: ScanSettings) {
        put("denoise_enabled", settings.denoiseEnabled)
        put("brightness_level", settings.brightnessLevel)
        put("contrast_level", settings.contrastLevel)
        put("sharpen_level", settings.sharpenLevel)
        put("binarization_enabled", settings.binarizationEnabled)
        put("auto_rotate_enabled", settings.autoRotateEnabled)
    }

    /**
     * Reconstruct the domain model from persistent storage.
     */
    fun getScanSettings(): ScanSettings = ScanSettings(
        denoiseEnabled = get("denoise_enabled", false),
        brightnessLevel = get("brightness_level", 0f),
        contrastLevel = get("contrast_level", 1f),
        sharpenLevel = get("sharpen_level", 0f),
        binarizationEnabled = get("binarization_enabled", false),
        autoRotateEnabled = get("auto_rotate_enabled", true)
    )

    /* ------------------------------------------------------------------ */
    /*  License‑key helpers                                              */
    /* ------------------------------------------------------------------ */

    fun saveLicenseKey(key: String) = put("license_key", key)
    fun getLicenseKey(): String = get("license_key", "") as String

    /* ------------------------------------------------------------------ */
    /*  Generic helpers used directly by unit tests                      */
    /* ------------------------------------------------------------------ */

    // Boolean
    fun saveBoolean(key: String, value: Boolean) = put(key, value)
    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        get(key, defaultValue)

    // String
    fun saveString(key: String, value: String) = put(key, value)
    fun getString(key: String, defaultValue: String): String? =
        get(key, defaultValue)

    // Int
    fun saveInt(key: String, value: Int) = put(key, value)
    fun getInt(key: String, defaultValue: Int): Int =
        get(key, defaultValue)

    // Float
    fun saveFloat(key: String, value: Float) = put(key, value)
    fun getFloat(key: String, defaultValue: Float): Float =
        get(key, defaultValue)

    // Long
    fun saveLong(key: String, value: Long) = put(key, value)
    fun getLong(key: String, defaultValue: Long): Long =
        get(key, defaultValue)
}
