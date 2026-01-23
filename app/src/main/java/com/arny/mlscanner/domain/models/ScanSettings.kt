package com.arny.mlscanner.domain.models

/**
 * Domain model describing every setting that can be persisted.
 *
 * All fields are immutable – the data class guarantees a value‑semantic type,
 * which is handy for passing around the settings in an event‑driven architecture
 * (e.g., as part of an `AppSettingsUpdated` event).
 */
data class ScanSettings(
    // Image pre‑processing flags
    val detectDocument: Boolean = false,

    // Filter/transform toggles
    val denoiseEnabled: Boolean = false,
    val brightnessLevel: Float? = null,   // 0f … 2f, null means “no change”
    val contrastLevel: Float? = null,
    val sharpenLevel: Float? = null,
    val binarizationEnabled: Boolean = false,
    val autoRotateEnabled: Boolean = false,

    // OCR configuration
    val ocrLanguage: String = "en",
    val confidenceThreshold: Float = 0f
)
