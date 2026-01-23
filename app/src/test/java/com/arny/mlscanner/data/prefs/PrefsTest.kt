// ──────────────────────────────────────────────────────────────────────
//  Prefs.kt – a tiny, test‑friendly wrapper over Android's SharedPreferences
//  (package: com.arny.mlscanner.data.prefs)
// ──────────────────────────────────────────────────────────────────────

package com.arny.mlscanner.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.arny.mlscanner.domain.models.ScanSettings

/**
 * The implementation deliberately keeps the public API small and test‑friendly:
 *
 *  • Every “save… / get…” method performs a single `apply()`/`clear()`,
 *    matching the expectations of the unit tests.
 *  • All writes are batched inside one editor instance per operation,
 *    so that Mockito can verify exactly one call to `apply()`.
 *  • The class is a simple façade – no caching, no DAO layer.
 */
class Prefs private constructor(private val prefs: SharedPreferences) {

    /* ------------------------------------------------------------------ */
    /*  Construction                                                     */
    /* ------------------------------------------------------------------ */

    /** Legacy ctor for callers that still pass a Context. */
    @Suppress("UNUSED_PARAMETER")
    private constructor(context: Context, unused: Boolean = false)
            : this(PreferenceManager.getDefaultSharedPreferences(context.applicationContext))

    companion object {
        @Volatile private var INSTANCE: Prefs? = null

        fun getInstance(context: Context): Prefs =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Prefs(
                    PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
                ).also { INSTANCE = it }
            }
    }

    /* ------------------------------------------------------------------ */
    /*  Internal helpers – all writes are batched in a single editor      */
    /* ------------------------------------------------------------------ */

    private fun putToEditor(editor: SharedPreferences.Editor, key: String, value: Any?) {
        when (value) {
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Float -> editor.putFloat(key, value)
            is Double -> editor.putDouble(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is String -> editor.putString(key, value)
        }
    }

    /* ------------------------------------------------------------------ */
    /*  ScanSettings helpers                                             */
    /* ------------------------------------------------------------------ */

    /** Persist the entire domain model in one transaction. */
    fun saveScanSettings(settings: ScanSettings) {
        val editor = prefs.edit()
        putToEditor(editor, "enable_denoising", settings.denoiseEnabled)
        putToEditor(editor, "enable_brightness_correction",
            if (settings.brightnessLevel != null) settings.brightnessLevel else null
        )
        putToEditor(editor,
            "enable_contrast_enhancement",
            if (settings.contrastLevel != null) settings.contrastLevel else null
        )
        putToEditor(editor,
            "enable_sharpening",
            if (settings.sharpenLevel != null) settings.sharpenLevel else null
        )
        putToEditor(editor, "enable_binarization", settings.binarizationEnabled)
        putToEditor(editor, "enable_deskew", settings.autoRotateEnabled)

        // OCR / confidence – stored as non‑nullable primitives
        editor.putString("ocr_language", settings.ocrLanguage)
        editor.putFloat("confidence_threshold", settings.confidenceThreshold)
        editor.apply()
    }

    /** Recreate the domain object from the persisted store. */
    fun getScanSettings(): ScanSettings {
        return ScanSettings(
            denoiseEnabled = prefs.getBoolean("enable_denoising", false),
            brightnessLevel = if (prefs.contains("enable_brightness_correction"))
                prefs.getFloat("enable_brightness_correction", 0f) else null,
            contrastLevel = if (prefs.contains("enable_contrast_enhancement"))
                prefs.getFloat("enable_contrast_enhancement", 0f) else null,
            sharpenLevel = if (prefs.contains("enable_sharpening"))
                prefs.getFloat("enable_sharpening", 0f) else null,
            binarizationEnabled = prefs.getBoolean("enable_binarization", false),
            autoRotateEnabled = prefs.getBoolean("enable_deskew", false),
            ocrLanguage = prefs.getString("ocr_language", "en") ?: "en",
            confidenceThreshold = prefs.getFloat("confidence_threshold", 0f)
        )
    }

    /* ------------------------------------------------------------------ */
    /*  License‑key helpers                                              */
    /* ------------------------------------------------------------------ */

    fun saveLicenseKey(key: String) {
        val editor = prefs.edit()
        editor.putString("license_key", key).apply()
    }

    fun getLicenseKey(): String =
        prefs.getString("license_key", "") ?: ""

    /* ------------------------------------------------------------------ */
    /*  Generic helpers used directly by unit tests                      */
    /* ------------------------------------------------------------------ */

    // Boolean
    fun saveBoolean(key: String, value: Boolean) {
        val editor = prefs.edit()
        editor.putBoolean(key, value).apply()
    }

    fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    // String
    fun saveString(key: String, value: String) {
        val editor = prefs.edit()
        editor.putString(key, value).apply()
    }

    fun getString(key: String, defaultValue: String): String? =
        prefs.getString(key, defaultValue)

    // Int
    fun saveInt(key: String, value: Int) {
        val editor = prefs.edit()
        editor.putInt(key, value).apply()
    }

    fun getInt(key: String, defaultValue: Int): Int =
        prefs.getInt(key, defaultValue)

    // Float
    fun saveFloat(key: String, value: Float) {
        val editor = prefs.edit()
        editor.putFloat(key, value).apply()
    }

    fun getFloat(key: String, defaultValue: Float): Float =
        prefs.getFloat(key, defaultValue)

    // Long
    fun saveLong(key: String, value: Long) {
        val editor = prefs.edit()
        editor.putLong(key, value).apply()
    }

    fun getLong(key: String, defaultValue: Long): Long =
        prefs.getLong(key, defaultValue)

    /* ------------------------------------------------------------------ */
    /*  Clear all – used by tests                                        */
    /* ------------------------------------------------------------------ */

    fun clearPreferences() {
        val editor = prefs.edit()
        editor.clear().apply()
    }
}
