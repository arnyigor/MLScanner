package com.arny.mlscanner.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager

class Prefs private constructor(context: Context) {
    val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context.applicationContext)

    /* ------------------------------------------------------------------ */
    /*  Публичные методы доступа к SharedPreferences                     */
    /* ------------------------------------------------------------------ */

    inline fun <reified T> get(key: String, defaultValue: T? = null): T? {
        return when (T::class.java) {
            Int::class.java -> prefs.getInt(key, defaultValue as? Int ?: 0) as? T
            Long::class.java -> prefs.getLong(key, defaultValue as? Long ?: 0L) as? T
            Float::class.java -> prefs.getFloat(key, defaultValue as? Float ?: 0f) as? T
            Boolean::class.java -> prefs.getBoolean(key, defaultValue as? Boolean ?: false) as? T
            String::class.java -> prefs.getString(key, defaultValue as? String) as? T
            Double::class.java -> getDouble(key, defaultValue as? Double ?: 0.0) as? T
            else -> null
        }
    }

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

    fun remove(vararg keys: String) {
        prefs.edit(commit = true) {
            keys.forEach { remove(it) }
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Специфические утилиты для Double                                 */
    /* ------------------------------------------------------------------ */

    private fun SharedPreferences.Editor.putDouble(
        key: String?,
        value: Double
    ): SharedPreferences.Editor {
        // Храним двойку как long‑битовую репрезентацию
        return putLong(key, java.lang.Double.doubleToRawLongBits(value))
    }

    fun getDouble(key: String, defaultValue: Double = 0.0): Double =
        java.lang.Double.longBitsToDouble(
            prefs.getLong(key, java.lang.Double.doubleToRawLongBits(defaultValue))
        )

    /* ------------------------------------------------------------------ */
    /*  Синглтон‑обёртка (double‑checked locking)                       */
    /* ------------------------------------------------------------------ */

    companion object {
        @Volatile
        private var INSTANCE: Prefs? = null

        fun getInstance(context: Context): Prefs =
            INSTANCE ?: synchronized(this) {
                // Второй check внутри synchronized, чтобы гарантировать однократную инициализацию
                INSTANCE ?: Prefs(context).also { INSTANCE = it }
            }
    }
}
