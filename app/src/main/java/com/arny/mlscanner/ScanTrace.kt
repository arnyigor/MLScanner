package com.arny.mlscanner

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/** Debug-only scan telemetry. Pass only metadata, never document text, URLs or file paths. */
internal object ScanTrace {
    private val sequence = AtomicInteger()
    val current = ThreadLocal<Int>()

    fun nextId(): Int = sequence.incrementAndGet()
    fun now(): Long = SystemClock.elapsedRealtime()
    fun elapsed(start: Long): Long = now() - start

    inline fun log(id: Int? = current.get(), message: () -> String) {
        if (BuildConfig.DEBUG) Log.d("ScanFlow", "scan=${id ?: "-"} ${message()}")
    }
}
