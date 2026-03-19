package com.arny.mlscanner.ui.utils

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/* ------------------------------------------------------------------ */
/*  Context‑extensions – UI / system helpers                         */
/* ------------------------------------------------------------------ */

/**
 * Resolve a color from the current theme.
 *
 * @param attrRes The attribute id (e.g. `android.R.attr.colorPrimary`).
 * @return The resolved color integer.
 * @throws IllegalArgumentException if the attribute is missing in the theme.
 */
@ColorInt
fun Context.getColorFromAttr(@AttrRes attrRes: Int): Int {
    val typedValue = TypedValue()
    return if (theme.resolveAttribute(attrRes, typedValue, true)) {
        typedValue.data
    } else {
        throw IllegalArgumentException("Attribute not found in theme: $attrRes")
    }
}

/**
 * Hide the soft keyboard from any composable.
 *
 * Uses the window token of the current activity that owns the context.
 */
@SuppressLint("ServiceCast")
fun Context.hideKeyboard() {
    val imm =
        getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
    val activity = this.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.Activity
    activity?.window?.decorView?.rootView?.let { root ->
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
    }
}

/**
 * Share plain text via an ACTION_SEND intent.
 *
 * @param text   The string to share (may be null).
 * @param title  Chooser dialog title (can be null).
 */
fun Context.share(text: String?, title: String?) {
    Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }.also { intent ->
        startActivity(Intent.createChooser(intent, title))
    }
}

/**
 * Share a file via ACTION_SEND using FileProvider.
 *
 * @param file   The file to share.
 * @param title  Chooser dialog title (can be null).
 */
fun Context.shareFile(file: File, title: String?) {
    Intent(Intent.ACTION_SEND).apply {
        type = "text/*" // change if you know the MIME type
        putExtra(
            Intent.EXTRA_STREAM,
            FileProvider.getUriForFile(this@shareFile, "$packageName.provider", file)
        )
    }.also { intent ->
        startActivity(Intent.createChooser(intent, title))
    }
}

/**
 * Return current available memory information.
 */
fun Context.getAvailableMemory(): ActivityManager.MemoryInfo {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return ActivityManager.MemoryInfo().apply { activityManager.getMemoryInfo(this) }
}

/**
 * Simple helper to check if the device is running Oreo (API 26+) or newer.
 */
fun Context.isOreoPlus(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

/**
 * Send a foreground‑service intent. The caller decides whether it will
 * start a foreground service or a normal one based on API level.
 *
 * @param intent  Intent to send (must target a Service).
 * @param action  Action string for the intent.
 * @param extras  Optional bundle configuration.
 */
fun Context.sendMessageService(
    intent: Intent,
    action: String,
    extras: Bundle.() -> Unit = {}
) {
    intent.apply {
        this.action = action
        putExtras(Bundle().apply(extras))
        if (isOreoPlus()) {
            startForegroundService(this)
        } else {
            startService(this)
        }
    }
}

/**
 * Bind to a service. Returns true if the binding succeeded.
 */
fun Context.bind(connection: ServiceConnection, cls: Class<Any>): Boolean {
    val intent = Intent(this, cls)
    return bindService(intent, connection, Context.BIND_AUTO_CREATE)
}

/**
 * Unbind from a previously bound service.
 */
fun Context.unbind(connection: ServiceConnection) = unbindService(connection)

/**
 * Send a broadcast with optional extras.
 *
 * @param action  Intent action string.
 * @param extras  Optional bundle configuration.
 */
fun Context.sendBroadcast(action: String, extras: Bundle.() -> Unit = {}) {
    val intent = Intent(action).apply { putExtras(Bundle().apply(extras)) }
    applicationContext.sendBroadcast(intent)
}

/**
 * Check if the device supports Picture‑in‑Picture mode.
 */
fun Context.isPiPAvailable(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

/* ------------------------------------------------------------------ */
/*  Bundle helpers – useful for debugging                               */
/* ------------------------------------------------------------------ */

/**
 * Pretty‑print the bundle contents to Logcat (or console).
 */
fun Bundle.printContents(indent: String = "") {
    println(toFormattedString(indent))
}

/**
 * Convert a bundle into an indented string representation.
 */
fun Bundle.toFormattedString(indent: String = ""): String {
    val sb = StringBuilder()
    for (key in keySet()) {
        val value = this[key]
        sb.append("$indent$key: ")
        when (value) {
            null -> sb.append("null\n")
            is Bundle -> {
                sb.append("Bundle {\n")
                sb.append(value.toFormattedString("$indent    "))
                sb.append("$indent}\n")
            }

            is Array<*> -> sb.append("Array(${value.size}) ${value.contentToString()}\n")
            is Collection<*> -> sb.append("Collection(${value.size}) $value\n")
            else -> sb.append("${value::class.simpleName} = $value\n")
        }
    }
    return sb.toString()
}

/* ------------------------------------------------------------------ */
/*  Misc helpers                                                        */
/* ------------------------------------------------------------------ */

/**
 * Resolve a Parcelable from a bundle in a type‑safe way.
 *
 * @receiver The Bundle instance.
 * @param key   Key of the parcelable.
 * @return The parcelable value or null if missing / wrong type.
 */
inline fun <reified T : android.os.Parcelable> Bundle.getParcelableCompat(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(key) as? T
    }

/**
 * Check whether the app has a particular permission granted.
 */
fun Context.checkPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
