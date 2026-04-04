// ============================================================
// domain/models/ScanSettings.kt
// Единые настройки сканирования — без nullable, с defaults
// ============================================================
package com.arny.mlscanner.domain.models

/**
 * Настройки предобработки и сканирования.
 *
 * Все поля NON-NULL с разумными defaults.
 * Обеспечивает единый контракт между UI, UseCase и Engine.
 *
 * @property contrastLevel     Множитель контраста. 1.0 = без изменений.
 *                              Диапазон: [0.5, 2.0]
 * @property brightnessLevel   Сдвиг яркости. 0 = без изменений.
 *                              Диапазон: [-100, 100]
 * @property sharpenLevel      Уровень резкости. 0 = без изменений.
 *                              Диапазон: [0.0, 2.0]
 * @property denoiseEnabled    Включить шумоподавление
 * @property binarizationEnabled Включить бинаризацию (Ч/Б)
 * @property autoRotateEnabled Автоматическая коррекция ориентации
 * @property handwrittenMode   Режим рукописного текста
 * @property language          Язык OCR
 * @property confidenceThreshold Минимальная уверенность для включения
 *                              блока в результат. Диапазон: [0.0, 1.0]
 */
 data class ScanSettings(
     val contrastLevel: Float = DEFAULT_CONTRAST,
     val brightnessLevel: Float = DEFAULT_BRIGHTNESS,
     val sharpenLevel: Float = DEFAULT_SHARPEN,
     val denoiseEnabled: Boolean = false,
     val binarizationEnabled: Boolean = false,
     val autoRotateEnabled: Boolean = true,
     // ▶ НОВОЕ: Режим рукописного текста
      val handwrittenMode: Boolean = false,
      val language: OcrLanguage = OcrLanguage.DEFAULT,
      val engineType: OcrEngineType = OcrEngineType.ML_KIT,
      val confidenceThreshold: Float = DEFAULT_CONFIDENCE_THRESHOLD
 ) {
    init {
        require(contrastLevel in 0.1f..5.0f) {
            "contrastLevel must be in [0.1, 5.0], got $contrastLevel"
        }
        require(brightnessLevel in -255f..255f) {
            "brightnessLevel must be in [-255, 255], got $brightnessLevel"
        }
        require(sharpenLevel in 0f..5.0f) {
            "sharpenLevel must be in [0.0, 5.0], got $sharpenLevel"
        }
        require(confidenceThreshold in 0f..1f) {
            "confidenceThreshold must be in [0.0, 1.0], got $confidenceThreshold"
        }
    }

    /** Есть ли хоть какие-то фильтры включены? */
    val hasFiltersApplied: Boolean
        get() = contrastLevel != DEFAULT_CONTRAST ||
                brightnessLevel != DEFAULT_BRIGHTNESS ||
                sharpenLevel != DEFAULT_SHARPEN ||
                denoiseEnabled ||
                binarizationEnabled

    /** Принудительное включение бинаризации (для апскейла) */
    fun withForcedBinarization(): ScanSettings {
        return if (binarizationEnabled) this
        else copy(binarizationEnabled = true)
    }

    companion object {
        const val DEFAULT_CONTRAST = 1.0f
        const val DEFAULT_BRIGHTNESS = 0f
        const val DEFAULT_SHARPEN = 0f
        const val DEFAULT_CONFIDENCE_THRESHOLD = 0f

        /** Дефолтные настройки (идентичны конструктору без аргументов) */
        val DEFAULT = ScanSettings()

        /** Настройки для документов на белой бумаге */
        val DOCUMENT = ScanSettings(
            contrastLevel = 1.5f,
            sharpenLevel = 0.5f,
            denoiseEnabled = true
        )

        /** Настройки для чеков */
        val RECEIPT = ScanSettings(
            contrastLevel = 1.8f,
            sharpenLevel = 0.5f,
            denoiseEnabled = true,
            binarizationEnabled = true
        )

        /** Настройки для скриншотов */
        val SCREENSHOT = ScanSettings(
            contrastLevel = 1.0f,
            sharpenLevel = 0f,
            denoiseEnabled = false
        )
    }
}