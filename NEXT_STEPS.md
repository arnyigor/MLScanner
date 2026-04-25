# Следующие шаги для исправления reading order

## ✅ Завершено

1. Рефакторинг тестов (сокращение с 1253 до ~700 строк)
2. Создание `OcrTextNormalizer` для нормализации текста
3. Создание `OcrTextSelector` для выбора между rawText и boxedText
4. Unit-тесты проходят успешно

## 🔄 Текущая задача: Интеграция OcrTextSelector в TesseractEngine

### Файл для изменения
`app/src/main/java/com/arny/mlscanner/data/ocr/engine/TesseractEngine.kt`

### Что нужно сделать

В методе `recognizeWithProfile()` после получения `rawFullText` и построения `blocks`:

```kotlin
// Текущий код (примерно строка 150-180):
val rawFullText = api.utF8Text.orEmpty()
val rawWords = extractWords(api)
val cleanedWords = postProcessWords(rawWords)
val blocks = buildBlocksFromWords(cleanedWords)
val fullText = buildFullTextFromBlocks(blocks)  // ❌ Может ломать порядок

// Новый код:
val rawFullText = api.utF8Text.orEmpty()
val rawWords = extractWords(api)
val cleanedWords = postProcessWords(rawWords)
val blocks = buildBlocksFromWords(cleanedWords)
val boxedText = buildFullTextFromBlocks(blocks)

// ✅ Выбираем лучший текст
val finalText = OcrTextSelector.chooseBestText(rawFullText, boxedText)

// ✅ Применяем нормализацию
val normalizedText = OcrTextNormalizer.normalize(finalText)

return OcrResult(
    fullText = normalizedText,
    // ... остальные поля
)
```

### Импорты для добавления

```kotlin
import com.arny.mlscanner.data.ocr.OcrTextNormalizer
import com.arny.mlscanner.data.ocr.OcrTextSelector
```

## 📋 План действий

1. [ ] Открыть `TesseractEngine.kt`
2. [ ] Найти метод `recognizeWithProfile()`
3. [ ] Добавить импорты `OcrTextSelector` и `OcrTextNormalizer`
4. [ ] Заменить прямое использование `buildFullTextFromBlocks()` на:
   - Сохранить результат в `boxedText`
   - Вызвать `OcrTextSelector.chooseBestText(rawFullText, boxedText)`
   - Применить `OcrTextNormalizer.normalize()`
5. [ ] Пересобрать проект
6. [ ] Запустить unit-тесты: `./gradlew :app:testDebugUnitTest`
7. [ ] Подключить устройство стабильно
8. [ ] Запустить regression-тест: `./gradlew connectedDebugAndroidTest`
9. [ ] Проверить что OCR теперь идет сверху вниз

## 🎯 Ожидаемый результат

**До фикса:**
```
"поставить слова ударение подчеркнуть зелёной ручкой..."
```

**После фикса:**
```
"29 января

Письмо: стр.41 «Тренажёр», прописать слова, поставить ударение...
Математика: стр. 7 - Петерсон № 1...
Азбука: стр. 63. Выучить скороговорки
Окр мир:
https://yandex.ru/video/preview/16042109160516050498
Откуда в дом приходит электричество"
```

## 🐛 Если что-то пойдет не так

1. Проверить что `rawFullText` не пустой
2. Проверить что `boxedText` строится корректно
3. Добавить логирование для отладки:
   ```kotlin
   Log.d("TesseractEngine", "rawFullText length: ${rawFullText.length}")
   Log.d("TesseractEngine", "boxedText length: ${boxedText.length}")
   Log.d("TesseractEngine", "Selected: ${if (finalText == rawFullText) "raw" else "boxed"}")
   ```

## 📚 Документация

- Тесты: `TESTS_REFACTORING_COMPLETE.md`
- Анализ проблемы: см. предыдущие обсуждения о reading order
- Unit-тесты: `app/src/test/java/com/arny/mlscanner/data/ocr/`

---

**Дата:** 2026-04-25
**Статус:** Готово к интеграции
