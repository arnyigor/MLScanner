# MLScanner

Android-приложение для интеллектуального распознавания текста (OCR) с поддержкой русского и английского языков, редактированием конфиденциальных данных и созданием searchable PDF.

## 🚀 Возможности

### Основной функционал
- **Мультиязычное OCR** — распознавание текста на русском и английском языках
- **Гибридный движок** — комбинация ML Kit и Tesseract 4 LSTM для максимальной точности
- **Предобработка изображений** — контраст, яркость, резкость, бинаризация, автоповорот
- **Ручное кадрирование** — интерактивное выделение области распознавания
- **Режим рукописного текста** — оптимизация для распознавания handwriting

### Безопасность и соответствие требованиям
- **Автоматическое обнаружение чувствительных данных**:
  - Номера кредитных карт (с валидацией по алгоритму Луна)
  - Паспорта РФ (серия и номер)
  - Email-адреса
  - Российские номера телефонов
  - ИНН, СНИЛС, КПП
  - Даты
  - Пользовательские регулярные выражения

- **Редактирование (Redaction)**:
  - Создание PDF с закрытыми (зачернёнными) областями
  - Невидимый текстовый слой для поиска (Ctrl+F)
  - Защита от копирования текста
  - Шифрование временных файлов

### Продвинутые функции
- **Поиск по справочнику** — сопоставление распознанных SKU с базой данных товаров
- **Fuzzy-поиск** — нахождение похожих артикулов даже при ошибках OCR
- **Экспорт в PDF/A** — архивно-стойкий формат документов
- **Бенчмарк OCR** — сравнительное тестирование движков

## 🏗️ Архитектура

### Технологический стек

| Компонент | Технология |
|-----------|-----------|
| Язык | Kotlin 2.x |
| UI | Jetpack Compose |
| DI | Koin |
| OCR | ML Kit Text Recognition + Tesseract 4 |
| Камера | CameraX |
| ML/Обработка | OpenCV 4.x, ONNX Runtime |
| База данных | Room + SQLCipher (шифрование) |
| PDF | Apache PDFBox Android |
| Сетевой слой | Ktor Client (подготовлен) |

### Слоистая архитектура (Clean Architecture)

```
┌─────────────────────────────────────┐
│           Presentation              │
│    (Compose UI + ViewModels)        │
├─────────────────────────────────────┤
│            Domain                   │
│  (UseCases, Models, Repository      │
│          Interfaces)                │
├─────────────────────────────────────┤
│             Data                    │
│  (OCR Engines, DB, Prefs, PDF,      │
│   Preprocessing, Security)          │
└─────────────────────────────────────┘
```

### Ключевые компоненты

#### OCR Engine (`data/ocr/engine/`)
- `OcrEngine` — базовый интерфейс
- `MLKitEngine` — Google ML Kit (быстрый, латиница)
- `TesseractEngine` — Tesseract 4 LSTM (русский, офлайн)
- `HybridEngine` — интеллектуальный выбор между движками

**Логика HybridEngine:**
1. Первым запускается Tesseract (лучше с русским)
2. Если обнаружена кириллица → сразу возвращаем результат Tesseract
3. Для латиницы сравниваем с ML Kit и выбираем лучший
4. Детекция "транслит-мусора" (когда ML Kit пытается читать кириллицу)

#### Предобработка изображений (`data/preprocessing/`)
- `ImagePreprocessor` — основной pipeline (deskew, grayscale, фильтры)
- `AdvancedDocumentPreprocessor` — специализированные пресеты:
  - `GENERAL` — общий случай
  - `WHITE_PAPER` — документы на белой бумаге
  - `RECEIPT` — чеки и квитанции
  - `SIGN` — вывески и таблички
  - `SCREENSHOT` — скриншоты экрана

- `DocumentDetector` — автоматическое нахождение границ документа (OpenCV)
- `YuvConverter` — быстрая конвертация YUV_420_888 → RGB без RenderScript

#### Безопасность (`data/security/`)
- `LicenseManager` — проверка цифровых лицензий (RSA + AES-GCM)
- `DeviceIdentityProvider` — криптографическая привязка к устройству (AndroidKeyStore)
- `SecurePrefs` — зашифрованные SharedPreferences (EncryptedSharedPreferences)
- `RootChecker` — обнаружение root-прав и отладчиков

#### Работа с PDF (`data/pdf/`)
- `PdfGenerator` — создание searchable PDF с невидимым текстовым слоем
- `PdfRedactionEngine` — редактор конфиденциальных данных
  - Деструктивное редактирование (перманентное удаление пикселей)
  - Растеризация существующих PDF
  - Координатная синхронизация OCR → PDF

## 📋 Требования

### Минимальные
- Android 7.0 (API 24)
- 2 GB RAM
- 100 MB свободного места

### Рекомендуемые
- Android 10+ (API 29+)
- 4 GB RAM
- Камера с автофокусом

### Разрешения
```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
```

## 🔧 Сборка

### Предварительные требования
- Android Studio Ladybug | 2024.2.1+
- JDK 17
- NDK (для OpenCV и Tesseract)

### Локальные настройки (`local.properties`)
```properties
# API ключи (опционально)
API_SECRET_KEY=your_secret_key_here

# Подпись релиза (опционально)
SIGNING_KEY_STORE_PATH=release.keystore
SIGNING_KEY_STORE_PASSWORD=password
SIGNING_KEY_ALIAS=release
SIGNING_KEY_PASSWORD=password
```

### Сборка APK
```bash
# Debug
./gradlew :app:assembleDebug

# Release с версией из version.properties
./gradlew :app:assembleRelease -PversionName=1.2.3
```

### Управление версиями
Версия определяется по приоритету:
1. Gradle property `versionName`
2. Environment variable `VERSION_NAME`
3. Файл `version.properties` (формат: `version=1.2.3`)
4. Fallback: `0.1.0`

## 🧪 Тестирование

### Структура тестов
```
src/test/           # Unit-тесты (JUnit 5 + Mockito)
src/androidTest/    # Инструментальные тесты (AndroidJUnit4)
```

### Запуск тестов
```bash
# Unit-тесты
./gradlew :app:test

# Инструментальные тесты
./gradlew :app:connectedAndroidTest

# С отчётом о покрытии
./gradlew :app:jacocoTestReport
```

### Основные тестовые сценарии
- `MatchingEngineTest` — импорт CSV/JSON, fuzzy-поиск, нормализация SKU
- `SensitiveDataDetectorTest` — детекция кредиток, паспортов, email
- `LicenseManagerTest` — шифрование, подпись, валидация лицензий
- `IntegrationTests` — сквозные сценарии OCR → Redaction → Matching

## 📦 Зависимости

### OCR и ML
```kotlin
// ML Kit
implementation("com.google.mlkit:text-recognition:16.0.0")

// Tesseract
implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")

// OpenCV
implementation("org.opencv:opencv-android:4.9.0")

// ONNX Runtime
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")
```

### Безопасность и хранение
```kotlin
// SQLCipher для шифрования БД
implementation("net.zetetic:sqlcipher-android:4.12.0")

// EncryptedSharedPreferences
implementation("androidx.security:security-crypto:1.1.0-alpha06")
```

### PDF и документы
```kotlin
// Apache PDFBox для Android
implementation("com.tom-roush:pdfbox-android:2.0.27.0")

// OpenCSV для импорта
implementation("com.opencsv:opencsv:5.8")

// FuzzyWuzzy для нечёткого поиска
implementation("me.xdrop:fuzzywuzzy:1.4.0")
```

## 🔄 Workflow приложения

```
┌─────────┐    ┌─────────────┐    ┌──────────────┐    ┌─────────┐
│  Camera │───→│ Preprocessing│───→│    OCR       │───→│ Result  │
│ Screen  │    │   Screen     │    │   Screen     │    │ Screen  │
└─────────┘    └─────────────┘    └──────────────┘    └─────────┘
     │                │                   │                 │
     ▼                ▼                   ▼                 ▼
  Захват фото    Настройка фильтров   Распознавание    Редактирование
  или галерея    Кадрирование         (async)          текста
                 Поворот                               Экспорт PDF
```

## 🛡️ Безопасность данных

### Шифрование базы данных
- SQLCipher с AES-256
- Ключ генерируется случайно и хранится в EncryptedSharedPreferences
- Поддержка миграции с перешифрованием

### Защита лицензий
- Асимметричное шифрование (RSA-2048)
- Привязка к устройству через AndroidKeyStore
- Проверка подписи лицензионного файла
- Срок действия и список функций в лицензии

### Обработка чувствительных данных
- Все операции redaction выполняются локально
- Временные файлы шифруются
- Автоматическая очистка кэша

## 🌐 Локализация

Поддерживаемые языки интерфейса:
- Русский
- Английский

Языки OCR:
- Русский (Tesseract traineddata `rus`)
- Английский (Tesseract traineddata `eng`)
- Автоопределение смешанных текстов

## 📄 Лицензия

Проприетарное ПО. Требуется лицензионный файл для активации.

### Сторонние компоненты
- Tesseract OCR — Apache License 2.0
- OpenCV — BSD 3-Clause
- Apache PDFBox — Apache License 2.0
- ML Kit — Google Terms of Service

## 👥 Авторы

Разработано для корпоративного использования с акцентом на:
- Соответствие 152-ФЗ (обработка персональных данных)
- ГОСТ Р ИСО/МЭК 27001
- Требованиям безопасности банковской сферы

## 📞 Поддержка

Для технической поддержки и получения лицензий:
- Email: support@example.com
- Внутренний тикет-трекер: [ссылка]

---

*Версия документа: 1.0*
*Последнее обновление: 2024*