# OCR Optimization - Quick Start

Быстрый старт для использования оптимизированного OCR в MLScanner.

## 🚀 Быстрый старт

### Базовое использование

```kotlin
// 1. Инициализация
val engine = TesseractEngine(context)
engine.initialize()

// 2. Распознавание с автоматическим выбором профиля
val result = engine.recognizeAdaptive(
    bitmap = imageBitmap,
    language = TesseractLanguage.RUS_ONLY
)

// 3. Получение результата
println("Текст: ${result.result.fullText}")
println("Точность: ${result.confidence}")
println("Профиль: ${result.profile.name}")
```

## 📦 Компоненты

### RussianPostProcessor
Исправляет типичные ошибки OCR в русском тексте.

```kotlin
val processed = RussianPostProcessor.process(rawText)
```

### ShadowRemovalPreprocessor
Улучшает качество изображения перед OCR.

```kotlin
// Автоматический выбор
val needsComplex = ShadowRemovalPreprocessor.needsComplexPreprocessing(bitmap)
val preprocessed = if (needsComplex) {
    ShadowRemovalPreprocessor.preprocessComplex(bitmap)
} else {
    ShadowRemovalPreprocessor.preprocessSimple(bitmap)
}
```

### AdaptiveProfileSelector
Автоматически выбирает оптимальные настройки OCR.

```kotlin
val profiles = AdaptiveProfileSelector.selectProfiles(bitmap, TesseractLanguage.RUS_ONLY)
val result = engine.recognizeMultiPass(bitmap, profiles)
```

## 🎯 Сценарии использования

### Максимальная точность (медленно)
```kotlin
val result = engine.recognizeAdaptive(bitmap, TesseractLanguage.RUS_ONLY)
```

### Быстрое распознавание (без multi-pass)
```kotlin
val result = engine.recognize(bitmap, handwrittenMode = false)
val processed = RussianPostProcessor.process(result.fullText)
```

### Документ с тенями
```kotlin
val preprocessed = ShadowRemovalPreprocessor.preprocessComplex(bitmap)
val result = engine.recognize(preprocessed, handwrittenMode = false)
```

### Чек или квитанция
```kotlin
val result = engine.recognizeMultiPass(
    bitmap = bitmap,
    profiles = listOf(TesseractProfile.RECEIPT_PROFILE)
)
```

## 📊 Производительность

| Режим | Время | Точность |
|-------|-------|----------|
| Быстрый | ~2-3 сек | Средняя |
| Стандартный | ~3-5 сек | Хорошая |
| Максимальный | ~10-15 сек | Отличная |

## 📚 Документация

Полная документация: [docs/OCR_COMPONENTS.md](docs/OCR_COMPONENTS.md)

Отчёт по оптимизации: [OCR_OPTIMIZATION_REPORT.md](OCR_OPTIMIZATION_REPORT.md)

## ✅ Что реализовано

- ✅ RussianPostProcessor - исправление ошибок кириллицы
- ✅ ShadowRemovalPreprocessor - удаление теней и улучшение контраста
- ✅ AdaptiveProfileSelector - автоматический выбор профиля
- ✅ Multi-pass OCR - выбор лучшего результата
- ✅ Unit-тесты
- ✅ Документация

## 🔧 Требования

- Android 5.0+ (API 21+)
- OpenCV 4.x
- Tesseract 4.x (tessdata_best)
- Минимум 2GB RAM

## 🐛 Известные проблемы

1. Multi-pass может быть медленным на старых устройствах
2. Complex preprocessing требует много памяти
3. ARM-translation эмуляторы не поддерживаются

## 📝 Changelog

### 2026-04-26
- Добавлены все компоненты оптимизации
- Интегрированы в TesseractEngine
- Добавлена документация и тесты

---

**Статус:** Готово к тестированию  
**Версия:** 1.0.0  
**Дата:** 2026-04-26
