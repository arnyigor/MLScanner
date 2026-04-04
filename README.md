# MLScanner

Android-приложение для интеллектуального распознавания текста (OCR) с поддержкой русского и английского языков, редактированием конфиденциальных данных и созданием searchable PDF.

## Возможности

### Основной функционал

- **Мультиязычное OCR** — распознавание текста на русском и английском языках
- **Гибридный движок** — комбинация ML Kit и Tesseract 4 LSTM для максимальной точности
- **Предобработка изображений** — контраст, яркость, резкость, бинаризация, автоповорот
- **Ручное кадрирование** — интерактивное выделение области распознавания
- **Режим рукописного текста** — оптимизация для распознавания handwriting
- **Сканирование штрих-кодов** — поддержка QR, EAN, UPC и других форматов

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
- **Экспорт в PDF** — searchable PDF с невидимым текстовым слоем
- **Бенчмарк OCR** — сравнительное тестирование движков

## Архитектура

### Технологический стек

| Компонент | Технология | Версия |
|-----------|-----------|--------|
| Язык | Kotlin | 2.1.10 |
| UI | Jetpack Compose BOM | 2025.11.00 |
| DI | Koin | 4.0.2 |
| OCR | ML Kit Text Recognition + Tesseract 4 | 16.0.1 / 4.7.0 |
| Камера | CameraX | 1.3.1 |
| Обработка изображений | OpenCV + Kotlin CPU | 4.9.0 |
| ML Runtime | ONNX Runtime | 1.23.2 |
| База данных | Room + SQLCipher | 2.6.1 / 4.12.0 |
| PDF | Apache PDFBox Android | 2.0.27.0 |
| Сетевой слой | Ktor Client | 3.3.3 |
| Barcode | ML Kit + ZXing | 17.3.0 / 4.3.0 |

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

### DI модули (`di/`)

- `AppModule` — инициализация приложения, Koin startup
- `DataModule` — OCR engines, repositories, preprocessing
- `DomainModule` — use cases
- `NetworkModule` — Ktor client, API endpoints
- `ViewModelsModule` — screen ViewModels
- `BarcodeModule` — barcode scanning engines

## Ключевые компоненты

### OCR Engine (`data/ocr/engine/`)

| Класс | Описание |
|-------|----------|
| `OcrEngine` | Базовый интерфейс для OCR движков |
| `MLKitEngine` | Google ML Kit (быстрый, латиница, кириллица с language hints) |
| `TesseractEngine` | Tesseract 4 LSTM (русский, офлайн, точнее для кириллицы) |
| `HybridEngine` | Интеллектуальный выбор между движками |

**Логика HybridEngine:**
1. Сначала запускается ML Kit с анализом результата
2. Проверяется средняя уверенность (threshold 0.6)
3. Анализируется соотношение кириллицы/латиницы
4. Если ML Kit даёт уверенный результат → возвращаем его
5. Иначе используется Tesseract как более надёжный для русского текста

### Barcode Engine (`data/barcode/engine/`)

| Класс | Описание |
|-------|----------|
| `BarcodeEngine` | Базовый интерфейс для сканеров штрих-кодов |
| `MLKitBarcodeEngine` | ML Kit (быстрый, поддерживает 20+ форматов) |
| `ZXingBarcodeEngine` | ZXing (offline fallback) |
| `HybridBarcodeEngine` | Комбинированный движок |

### Предобработка изображений (`data/preprocessing/`)

| Компонент | Описание |
|-----------|----------|
| `ImagePreprocessor` | Основной pipeline (deskew, grayscale, фильтры) |
| `AdvancedDocumentPreprocessor` | CPU-оптимизированный pipeline (Kotlin) |
| `DocumentDetector` | Автоматическое нахождение границ документа (OpenCV Canny) |
| `YuvConverter` | Быстрая конвертация YUV_420_888 → RGB без RenderScript |

**Режимы AdvancedDocumentPreprocessor:**
- `GENERAL` — общий случай
- `WHITE_PAPER` — документы на белой бумаге (с удалением фона)
- `RECEIPT` — чеки и квитанции (адаптивная бинаризация)
- `SIGN` — вывески и таблички (шумоподавление)
- `SCREENSHOT` — скриншоты экрана

### Пост-обработка текста (`data/postprocessing/`)

| Процессор | Функция |
|-----------|---------|
| `LineBreakRestorer` | Восстановление переносов строк |
| `OcrErrorCorrector` | Коррекция типичных OCR-ошибок |
| `PunctuationFixer` | Исправление пунктуации |
| `WhitespaceNormalizer` | Нормализация пробелов |

### Безопасность (`data/security/`)

| Компонент | Описание |
|-----------|----------|
| `LicenseManager` | Проверка цифровых лицензий (RSA + AES-GCM) |
| `DeviceIdentityProvider` | Криптографическая привязка к устройству (AndroidKeyStore) |
| `SecurePrefs` | Зашифрованные SharedPreferences (EncryptedSharedPreferences) |
| `RootChecker` | Обнаружение root-прав и отладчиков |

### Работа с PDF (`data/pdf/`)

| Компонент | Описание |
|-----------|----------|
| `PdfGenerator` | Создание searchable PDF с невидимым текстовым слоем |
| `PdfRedactionEngine` | Редактор конфиденциальных данных |
| | — Деструктивное редактирование (перманентное удаление пикселей) |
| | — Растеризация существующих PDF |
| | — Координатная синхронизация OCR → PDF |

### Детектор чувствительных данных (`data/redaction/`)

| Паттерн | Регулярное выражение | Валидация |
|---------|---------------------|-----------|
| Кредитные карты | `(?:\d[ -]*?){13,19}` | Алгоритм Луна |
| Паспорт РФ | `[0-9]{4}\s?[0-9]{6}` | 10 цифр |
| Email | `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}` | — |
| Телефон РФ | `(\+7|8)[\s-]?[(]?\d{3}[)]?[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}` | — |
| Даты | `\b\d{1,2}[./\-]\d{1,2}[./\-]\d{2,4}\b` | — |
| ИНН | `\b\d{10,12}\b` | 10-12 цифр |
| СНИЛС | `\b\d{3}[\s-]?\d{3}[\s-]?\d{3}[\s-]?\d{2}\b` | 11 цифр |
| КПП | `\b\d{4}[\s-]?\d{2}[\s-]?\d{3}\b` | 9 цифр |

### UI Слои (`ui/`)

| Экран | Компонент |
|-------|-----------|
| `HomeScreen` | Главный экран (камера, галерея, сканер штрих-кодов) |
| `CameraScreen` | Захват изображения через CameraX |
| `PreprocessingScreen` | Предобработка (фильтры, кадрирование, поворот) |
| `ScanningScreen` | Экран процесса OCR |
| `ResultScreen` | Результат с редактированием, копированием, PDF-экспортом |
| `BarcodeScannerScreen` | Сканер штрих-кодов |
| `ScanViewModel` | Основной ViewModel с UI state management |
| `AppNavigation` | Compose Navigation с state-driven навигацией |

## Требования

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

## Сборка

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

### Версия APK
```
MLScanner-{variant}-v{version}.apk
```

## Тестирование

### Структура тестов
```
src/test/           # Unit-тесты (JUnit 5 + Mockito)
src/androidTest/    # Инструментальные тесты (AndroidJUnit4 + Compose)
```

### Запуск тестов
```bash
# Unit-тесты
./gradlew :app:test

# Инstrumentальные тесты
./gradlew :app:connectedAndroidTest

# С отчётом о покрытии
./gradlew :app:jacocoTestReport
```

### Основные тестовые сценарии

**Unit-тесты (`src/test/`):**
- `BarcodeValidationTest` — валидация штрих-кодов
- `OcrErrorCorrectorTest` — коррекция OCR-ошибок
- `TextPostProcessorTest` — пост-обработка текста
- `SensitiveDataDetectorTest` — детекция кредиток, паспортов, email
- `LicenseManagerTest` — шифрование, подпись, валидация лицензий

**Инструментальные тесты (`src/androidTest/`):**
- `CameraAnalyzerTest` — тестирование CameraX анализатора
- `ImagePreprocessorTest` — тестирование pipeline предобработки
- `DocumentDetectorTest` — тестирование детекции границ документа
- `PdfGeneratorTest` — тестирование генерации PDF
- `PdfRedactionEngineTest` — тестирование редактирования PDF
- `IntegrationTests` — сквозные сценарии OCR → Redaction → Matching

## Зависимости

### OCR и ML
```kotlin
// ML Kit Text Recognition
implementation("com.google.mlkit:text-recognition:16.0.1")

// ML Kit Language ID (автоопределение языка)
implementation("com.google.mlkit:language-id:17.0.6")

// Tesseract 4 (rus + eng)
implementation("cz.adaptech.tesseract4android:tesseract4android:4.7.0")

// OpenCV для детекции границ документа
implementation("org.opencv:opencv:4.9.0")

// ONNX Runtime для дополнительных ML-моделей
implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
```

### Barcode Scanning
```kotlin
// ML Kit Barcode Scanning
implementation("com.google.mlkit:barcode-scanning:17.3.0")

// ZXing (offline fallback)
implementation("com.journeyapps:zxing-android-embedded:4.3.0")
```

### Безопасность и хранение
```kotlin
// SQLCipher для шифрования базы данных
implementation("net.zetetic:sqlcipher-android:4.12.0")

// EncryptedSharedPreferences
implementation("androidx.security:security-crypto:1.0.0")
```

### PDF и документы
```kotlin
// Apache PDFBox для Android
implementation("com.tom-roush:pdfbox-android:2.0.27.0")

// OpenCSV для импорта справочников
implementation("com.opencsv:opencsv:5.12.0")

// FuzzyWuzzy для нечёткого поиска артикулов
implementation("me.xdrop:fuzzywuzzy:1.4.0")
```

### Text Post-Processing
```kotlin
// Apache Commons Text (для Levenshtein distance)
implementation("org.apache.commons:commons-text:1.12.0")

// ICU4J (Unicode normalization)
implementation("com.ibm.icu:icu4j:75.1")
```

## Workflow приложения

```
┌─────────┐    ┌─────────────┐    ┌──────────────┐    ┌─────────┐
│  Home   │───→│   Camera    │───→│Preprocessing │───→│Scanning │
│ Screen  │    │   Screen    │    │   Screen     │    │ Screen  │
└─────────┘    └─────────────┘    └──────────────┘    └─────────┘
      │                │                   │                 │
      ▼                ▼                   ▼                 ▼
   Выбор            Захват фото       Настройка фильтров   Распознавание
   источника       или галерея       Кадрирование         (async)
                                       Поворот
                                                            ▼
                                                      ┌─────────┐
                                                      │ Result  │
                                                      │ Screen  │
                                                      └─────────┘
                                                           │
                                                           ▼
                                                      Редактирование
                                                      текста
                                                      Копирование/шаринг
                                                      Экспорт PDF
                                                      Redaction
```

## Безопасность данных

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
- Соответствие 152-ФЗ (обработка персональных данных)

## Локализация

Поддерживаемые языки интерфейса:
- Русский
- Английский

Языки OCR:
- Русский (Tesseract traineddata `rus`)
- Английский (Tesseract traineddata `eng`)
- Автоопределение смешанных текстов

## Структура проекта

```
app/src/
├── main/
│   ├── java/com/arny/mlscanner/
│   │   ├── MlScannerApp.kt           # Application class
│   │   ├── di/                        # Koin modules
│   │   │   ├── AppModule.kt
│   │   │   ├── BarcodeModule.kt
│   │   │   ├── DataModule.kt
│   │   │   ├── DomainModule.kt
│   │   │   ├── NetworkModule.kt
│   │   │   └── ViewModelsModule.kt
│   │   ├── data/
│   │   │   ├── barcode/              # Barcode scanning
│   │   │   │   ├── analyzer/
│   │   │   │   └── engine/
│   │   │   ├── camera/               # Camera handling
│   │   │   ├── ocr/                  # OCR engines
│   │   │   │   ├── benchmark/
│   │   │   │   ├── engine/
│   │   │   │   ├── mapper/
│   │   │   │   └── OcrRepositoryImpl.kt
│   │   │   ├── pdf/                   # PDF generation
│   │   │   ├── postprocessing/       # Text post-processing
│   │   │   │   └── processors/
│   │   │   ├── prefs/                # SharedPreferences
│   │   │   ├── preprocessing/        # Image preprocessing
│   │   │   ├── redaction/             # Sensitive data detection
│   │   │   └── security/             # License, encryption
│   │   ├── domain/
│   │   │   ├── formatters/
│   │   │   ├── mappers/
│   │   │   ├── models/
│   │   │   │   ├── barcode/
│   │   │   │   ├── errors/
│   │   │   │   └── strings/
│   │   │   └── usecases/
│   │   │       └── barcode/
│   │   └── ui/
│   │       ├── components/
│   │       ├── navigation/
│   │       ├── screens/
│   │       ├── theme/
│   │       └── utils/
│   ├── assets/
│   │   └── tessdata/                  # Tesseract trained data
│   │       ├── eng.traineddata
│   │       └── rus.traineddata
│   └── res/
├── androidTest/                        # Instrumented tests
└── test/                               # Unit tests
```

## Лицензия

Проприетарное ПО. Требуется лицензионный файл для активации.

### Сторонние компоненты
- Tesseract OCR — Apache License 2.0
- OpenCV — BSD 3-Clause
- Apache PDFBox — Apache License 2.0
- ML Kit — Google Terms of Service

## Соответствие требованиям

Приложение разработано с учётом требований:
- **152-ФЗ** — обработка персональных данных
- **ГОСТ Р ИСО/МЭК 27001** — информационная безопасность
- **Требования безопасности банковской сферы** — PCI DSS-ready для работы с платёжными данными

---

*Версия документа: 1.2*
*Последнее обновление: апрель 2026*