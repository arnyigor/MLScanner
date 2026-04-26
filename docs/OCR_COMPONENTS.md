# OCR Optimization Components

Документация по новым компонентам оптимизации OCR в MLScanner.

## Компоненты

### 1. RussianPostProcessor

**Назначение:** Постобработка русского текста после OCR для исправления типичных ошибок Tesseract.

**Основные функции:**
- Замена латинских символов на кириллические в словах с преимущественно кириллицей
- Исправление цифр, перепутанных с буквами (l→1, O→0, I→1, S→5, Z→2)
- Нормализация пробелов и пунктуации
- Удаление мусорных символов

**Использование:**
```kotlin
val rawText = "Пpивeт миp"  // p, e, p - латинские
val processed = RussianPostProcessor.process(rawText)
// Result: "Привет мир"

// Анализ качества
val metrics = RussianPostProcessor.analyzeQuality(rawText, processed)
println(metrics)  // Fixed: 3 chars, Cyrillic: 100.0%, Length: 10 → 10
```

**Важно:** Не привязывается к конкретным словам, использует только универсальные паттерны.

---

### 2. ShadowRemovalPreprocessor

**Назначение:** Продвинутая предобработка изображений для улучшения качества OCR.

**Основные методы:**

#### removeShadows()
Удаляет тени и неравномерное освещение через division method.
```kotlin
val cleaned = ShadowRemovalPreprocessor.removeShadows(bitmap)
```

#### applyAdvancedCLAHE()
Применяет адаптивное улучшение контраста с автоматическим выбором параметров.
```kotlin
val enhanced = ShadowRemovalPreprocessor.applyAdvancedCLAHE(
    bitmap = bitmap,
    clipLimit = 3.0
)
```

#### reduceNoise()
Применяет шумоподавление (Non-Local Means Denoising).
```kotlin
val denoised = ShadowRemovalPreprocessor.reduceNoise(
    bitmap = bitmap,
    strength = 10f
)
```

#### preprocessComplex()
Комбинированная предобработка для сложных документов.
```kotlin
// Последовательно: Shadow Removal → Noise Reduction → CLAHE
val result = ShadowRemovalPreprocessor.preprocessComplex(bitmap)
```

#### preprocessSimple()
Быстрая предобработка (только CLAHE).
```kotlin
val result = ShadowRemovalPreprocessor.preprocessSimple(bitmap)
```

#### needsComplexPreprocessing()
Анализирует изображение и определяет необходимость сложной предобработки.
```kotlin
val needsComplex = ShadowRemovalPreprocessor.needsComplexPreprocessing(bitmap)

val preprocessed = if (needsComplex) {
    ShadowRemovalPreprocessor.preprocessComplex(bitmap)
} else {
    ShadowRemovalPreprocessor.preprocessSimple(bitmap)
}
```

---

### 3. AdaptiveProfileSelector

**Назначение:** Автоматический выбор оптимального профиля OCR на основе анализа изображения.

**Анализируемые параметры:**
- Размер и соотношение сторон
- Контраст и яркость (mean, stddev)
- Наличие теней (неравномерность освещения)
- Плотность текста (процент чёрных пикселей)

**Использование:**
```kotlin
// Автоматический выбор профилей
val profiles = AdaptiveProfileSelector.selectProfiles(
    bitmap = imageBitmap,
    language = TesseractLanguage.RUS_ONLY
)

println("Selected ${profiles.size} profiles:")
profiles.forEach { profile ->
    println("  - ${profile.name}")
}

// Использование с TesseractEngine
val result = engine.recognizeMultiPass(imageBitmap, profiles)
```

**Логика выбора PSM:**
- `SINGLE_LINE` - для очень узких/широких изображений (ratio < 0.15 или > 8)
- `SINGLE_BLOCK` - для узких или маленьких (ratio < 0.4 или > 3.5, minDim < 400)
- `SPARSE_TEXT` - для низкой плотности текста (< 0.3)
- `AUTO` - для стандартных случаев

**Логика выбора режимов предобработки:**
- `ORIGINAL` - всегда первым
- `CONTRAST_ENHANCED` - при низком контрасте (std < 45 или contrast < 150)
- `ADAPTIVE_THRESHOLD` - при наличии теней (score > 0.3)
- `UPSCALE_2X` - для маленьких изображений (minDim < 500)
- `SHARPEN_LIGHT` - для хорошего качества

---

### 4. TesseractEngine - Новые методы

#### recognizeAdaptive()
Распознавание с автоматическим выбором профилей.
```kotlin
val result = engine.recognizeAdaptive(
    bitmap = imageBitmap,
    language = TesseractLanguage.RUS_ONLY
)

println("Text: ${result.result.fullText}")
println("Confidence: ${result.confidence}")
println("Profile: ${result.profile.name}")
println("Score: ${result.score}")
```

#### recognizeMultiPass()
Multi-pass распознавание с выбором лучшего результата.
```kotlin
val profiles = listOf(
    TesseractProfile.RECEIPT_PROFILE,
    TesseractProfile.RUSSIAN_PROFILES[0],
    TesseractProfile.RUSSIAN_PROFILES[3]
)

val result = engine.recognizeMultiPass(
    bitmap = imageBitmap,
    profiles = profiles
)

println("Best result from ${profiles.size} profiles")
println("Winner: ${result.profile.name}")
```

---

## Рекомендуемые сценарии использования

### Сценарий 1: Простой документ с хорошим освещением
```kotlin
val result = engine.recognize(bitmap, handwrittenMode = false)
val processed = RussianPostProcessor.process(result.fullText)
```

### Сценарий 2: Документ с тенями или плохим освещением
```kotlin
val needsComplex = ShadowRemovalPreprocessor.needsComplexPreprocessing(bitmap)
val preprocessed = if (needsComplex) {
    ShadowRemovalPreprocessor.preprocessComplex(bitmap)
} else {
    ShadowRemovalPreprocessor.preprocessSimple(bitmap)
}

val result = engine.recognize(preprocessed, handwrittenMode = false)
val processed = RussianPostProcessor.process(result.fullText)
```

### Сценарий 3: Неизвестное качество - максимальная точность
```kotlin
val result = engine.recognizeAdaptive(
    bitmap = imageBitmap,
    language = TesseractLanguage.RUS_ONLY
)

// Результат уже включает постобработку
println("Text: ${result.result.fullText}")
println("Confidence: ${result.confidence}")
```

### Сценарий 4: Чек или специфичный документ
```kotlin
val profiles = listOf(
    TesseractProfile.RECEIPT_PROFILE,
    TesseractProfile(
        name = "custom_receipt",
        language = TesseractLanguage.RUS_ONLY,
        psm = TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK,
        preprocessMode = PreprocessMode.ADAPTIVE_THRESHOLD
    )
)

val result = engine.recognizeMultiPass(bitmap, profiles)
```

---

## Производительность

### Время обработки (OnePlus 6T, Android 11)

| Операция | Время | Примечание |
|----------|-------|------------|
| Shadow Removal | ~200-400ms | Зависит от размера |
| Advanced CLAHE | ~100-200ms | Быстрее чем shadow removal |
| Noise Reduction | ~300-500ms | Самая медленная |
| Complex Preprocessing | ~600-1000ms | Все три операции |
| Simple Preprocessing | ~100-200ms | Только CLAHE |
| Russian Post-Processing | ~5-20ms | Очень быстро |
| Adaptive Profile Selection | ~50-100ms | Анализ изображения |
| Single OCR Pass | ~2000-5000ms | Tesseract |
| Multi-Pass (3 profiles) | ~6000-15000ms | 3x Tesseract |

### Рекомендации по оптимизации

1. **Используйте кэширование** - если обрабатываете одно изображение несколько раз
2. **Ограничьте количество профилей** - 2-3 профиля обычно достаточно
3. **Используйте простую предобработку** - если качество изображения хорошее
4. **Показывайте прогресс** - multi-pass может занять 10-15 секунд

---

## Тестирование

Запуск unit-тестов:
```bash
./gradlew test --tests "RussianPostProcessorTest"
```

Все тесты находятся в:
```
app/src/test/java/com/arny/mlscanner/data/ocr/postprocessing/RussianPostProcessorTest.kt
```

---

## Changelog

### 2026-04-26
- ✅ Добавлен RussianPostProcessor
- ✅ Добавлен ShadowRemovalPreprocessor
- ✅ Добавлен AdaptiveProfileSelector
- ✅ Интегрированы новые компоненты в TesseractEngine
- ✅ Добавлены unit-тесты
- ✅ Обновлена документация

---

## Известные ограничения

1. **OpenCV требуется** - все компоненты предобработки требуют OpenCV
2. **Производительность** - complex preprocessing может быть медленным на старых устройствах
3. **Память** - multi-pass создаёт несколько копий изображения
4. **Язык** - RussianPostProcessor оптимизирован только для русского языка

---

## Дальнейшие улучшения

- [ ] Параллельное выполнение multi-pass OCR
- [ ] Кэширование результатов анализа изображений
- [ ] Поддержка других языков в постобработке
- [ ] Специализированные профили для паспортов, водительских прав
- [ ] Детекция и исправление перспективных искажений
- [ ] UI для визуализации выбранного профиля и предобработки
