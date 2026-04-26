# Tesseract OCR - Оптимизация

## Что сделано

### 1. Использованы лучшие модели
На основе benchmark-тестов на OnePlus 6T (Android 11) выбраны **tessdata_best** модели:
- Самые быстрые (43 сек vs 66 сек у tessdata_fast)
- Качество идентичное всем наборам
- Размер: 30MB (rus 15MB + eng 15MB)

### 2. Упрощена архитектура
- Удалён enum TesseractDataPack
- Удалено версионирование (не требуется для одного набора)
- Упрощена инициализация
- Удалены benchmark-тесты (результаты получены)

### 3. Результаты benchmark

| Набор | Avg Time | Confidence | Words | Score |
|-------|----------|------------|-------|-------|
| tessdata_fast | 66.4 сек | 64.6% | 53 | 188.2 |
| tessdata (standard) | 47.5 сек | 64.6% | 53 | 188.2 |
| **tessdata_best** ✅ | **43.0 сек** | 64.6% | 53 | 188.2 |

**Вывод:** tessdata_best на 35% быстрее tessdata_fast при идентичном качестве.

## Структура проекта

```
app/src/main/assets/
└── tessdata/                    30MB (best модели)
    ├── rus.traineddata         15MB
    └── eng.traineddata         15MB

app/src/main/java/.../ocr/engine/
├── TesseractEngine.kt          (упрощён)
└── TesseractProfile.kt         (удалён TesseractDataPack)

app/src/androidTest/java/.../ocr/
├── TesseractSyntheticIntegrationTest.kt
├── OcrRealSamplesIntegrationTest.kt
└── MlKitAndPreprocessingSmokeIntegrationTest.kt
```

## Следующие шаги для улучшения качества

### Приоритет 1: OpenCV Preprocessing
Реализовать реальные фильтры вместо заглушек:

```kotlin
// CLAHE для слабого контраста
private fun applyClahe(bitmap: Bitmap): Bitmap {
    val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0))
    clahe.apply(gray, enhanced)
    return enhanced
}

// Adaptive threshold для теней
private fun applyAdaptiveThreshold(bitmap: Bitmap): Bitmap {
    Imgproc.adaptiveThreshold(
        gray, result, 255.0,
        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        Imgproc.THRESH_BINARY,
        31, 11.0
    )
    return result
}

// Shadow removal для неравномерного освещения
private fun applyShadowRemoval(bitmap: Bitmap): Bitmap {
    Imgproc.GaussianBlur(gray, background, Size(0.0, 0.0), 21.0)
    Core.divide(gray, background, normalized, 255.0)
    return normalized
}
```

### Приоритет 2: Постобработка русского текста
Исправление типовых ошибок Tesseract:

```kotlin
object RussianOcrPostProcessor {
    // Латинские подмены в русских словах
    fun fixLatinSubstitutions(text: String): String {
        return text.replace('a', 'а')  // только внутри кириллических слов
            .replace('e', 'е')
            .replace('o', 'о')
            .replace('p', 'р')
            .replace('c', 'с')
            .replace('x', 'х')
    }
    
    // Типовые ошибки
    fun fixCommonErrors(text: String): String {
        return text
            .replace("l", "1")  // в числах
            .replace("O", "0")  // в числах
    }
}
```

### Приоритет 3: Адаптивный выбор профилей
Выбирать профили по характеристикам изображения:

```kotlin
fun selectProfiles(bitmap: Bitmap): List<TesseractProfile> {
    val ratio = bitmap.width.toFloat() / bitmap.height
    val minSide = minOf(bitmap.width, bitmap.height)
    
    return when {
        // Узкая полоска → одна строка
        ratio > 5f || ratio < 0.2f -> listOf(SINGLE_LINE_PROFILE)
        
        // Мелкий текст → upscale
        minSide < 600 -> listOf(
            rus_auto_upscale,
            rus_block_upscale,
            rus_auto_original
        )
        
        // Стандартный документ
        else -> RUSSIAN_PROFILES
    }
}
```

## Известные ограничения

1. **Tesseract не для рукописи**
   - Для свободной рукописи нужны HTR-модели (TrOCR, PARSeq)
   - Tesseract хорош для печатного текста и аккуратной рукописи

2. **Preprocessing заглушки**
   - ADAPTIVE_THRESHOLD и SHARPEN_LIGHT пока возвращают оригинал
   - Требуется реализация через OpenCV

3. **Размер APK**
   - +30MB для tessdata_best
   - Можно оптимизировать через dynamic feature modules

## Рекомендации

**Для печатного текста:**
1. ✅ Используем tessdata_best (уже сделано)
2. ⏳ Реализовать OpenCV preprocessing
3. ⏳ Добавить постобработку русского текста
4. ⏳ Fine-tuning только для специфичного домена

**Для рукописи:**
- Рассмотреть HTR-модели или коммерческие API

---

**Дата:** 2026-04-26  
**Статус:** Оптимизировано, готово к улучшению preprocessing
