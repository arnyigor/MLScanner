package com.arny.mlscanner.domain.models

data class ScanSettings(
    val contrastLevel: Float = 1.0f,
    val brightnessLevel: Float = 0f,
    val sharpenLevel: Float = 0f,
    val denoiseEnabled: Boolean = true,
    val autoRotateEnabled: Boolean = true,
    val binarizationEnabled: Boolean = true
)