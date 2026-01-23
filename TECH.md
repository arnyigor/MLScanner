# 📋 Техническое задание (ТЗ) для разработки SecureField MVP

**Версия:** 1.0  
**Дата:** 23 января 2026  
**Статус:** В разработке  
**Приоритет:** Критический

***

# Содержание

1. [Общие сведения](#1-общие-сведения)
2. [Требования функциональные](#2-требования-функциональные)
3. [Требования нефункциональные](#3-требования-нефункциональные)
4. [Детальные спецификации модулей](#4-детальные-спецификации-модулей)
5. [Тестирование и валидация](#5-тестирование-и-валидация)
6. [План развертывания](#6-план-развертывания)
7. [Риски и смягчение](#7-риски-и-смягчение)

***

# 1. Общие сведения

## 1.1 Назначение системы

**SecureField** — приложение для сканирования, безопасного редактирования и сопоставления конфиденциальных документов (счета, накладные, паспорта) с внутренней базой данных компании. Основан на локальной обработке (OCR на устройстве, шифрованное хранилище) без отправки данных в облако.

## 1.2 Целевой рынок

- **Первичная:** B2B, малые и средние предприятия в России (складские операции, таможня, логистика)
- **Вторичная:** Банки, страховые компании (сверка документов)

## 1.3 Целевая платформа

- **ОС:** Android 8.0+ (API 26, minSdkVersion = 26)
- **Форм-фактор:** Смартфоны (5-7 дюймов), планшеты (8-12 дюймов)
- **Архитектура:** ARM64 (основная), ARM32 (поддержка)

## 1.4 Ограничения MVP

- Только Android (Web и iOS — на второй релиз)
- Работа с документами только внутри приложения (нет синхронизации с облаком)
- Лицензирование привязано к устройству (Device-based)
- Справочники < 100,000 записей (> нужна архитектура с индексацией)

***

# 2. Требования функциональные

## 2.1 Модуль сканирования (Secure Scan)

### 2.1.1 Общая функциональность

| Требование | Описание | Критерий приема |
|---|---|---|
| **FS-SCAN-001** | Захват фото документа через камеру устройства | Приложение открывает CameraX Live Preview в режиме FRONT/BACK (на выбор пользователя) |
| **FS-SCAN-002** | Реальная обработка OCR на экране (preview) | Во время превью показывает "найденные регионы текста" зелеными рамками (Bounding Boxes) |
| **FS-SCAN-003** | Детекция краев документа | Автоматическое выделение прямоугольника документа с рамкой; пользователь может вручную корректировать углы (4-точка на перспективе) |
| **FS-SCAN-004** | Выпрямление перспективы (Document De-Skew) | При захвате фото документ автоматически выпрямляется к плоскому виду (2D warping) перед сохранением в памяти |
| **FS-SCAN-005** | Сохранение исходного фото + OCR-координаты | Вместе с Bitmap сохраняются JSON-структура с координатами (BoundingBox, Confidence, Text) каждого слова |
| **FS-SCAN-006** | Подготовка к редактированию | После захвата пользователь видит превью отредактированного PDF и переходит на экран "Smart Redaction" |

### 2.1.2 OCR Engine (PaddleOCR v4 ONNX INT8)

| Требование | Описание | Критерий приема |
|---|---|---|
| **FS-OCR-001** | Поддержка русского и английского текста | Модель детектирует и распознает тексты на обоих языках с точностью ≥ 90% для печатного текста |
| **FS-OCR-002** | Локальная инференция (без облака) | Все вычисления выполняются на CPU устройства; сетевой доступ не требуется |
| **FS-OCR-003** | Оптимизированная модель (INT8 Quantization) | Модели весят < 10 MB (в сумме det + rec), инференция занимает < 3 сек на Snapdragon 680 |
| **FS-OCR-004** | Извлечение структурированных данных | Для каждого слова возвращается: текст, левый-верхний-правый-нижний координаты (в пиксельных единицах относительно исходного фото), confidence score (0-1) |
| **FS-OCR-005** | Обработка поврежденных изображений | Если текст нечеткий, модель вывдает Confidence < 0.5; приложение помечает такие боксы как "Low Quality" (желтая рамка в UI) |

### 2.1.3 PDF Generation

| Требование | Описание | Критерий приема |
|---|---|---|
| **FS-PDF-001** | Создание searchable PDF из фото + OCR | Генерируется PDF с встроенным изображением документа и невидимым текстовым слоем (для поиска) |
| **FS-PDF-002** | Поддержка поиска по содержимому (Ctrl+F) | Пользователь может открыть PDF в Adobe Reader и найти текст, который был на фото |
| **FS-PDF-003** | Предотвращение копирования текста из PDF | Пользователь не может Copy-Paste из невидимого текстового слоя (реализуется через метаданные PDF / защиту) |
| **FS-PDF-004** | Сохранение в формате PDF/A-2B | PDF совместим с архивированием и долгосрочным хранением |

***

## 2.2 Модуль Smart Redaction (Маскирование)

### 2.2.1 Автоматическое обнаружение чувствительных данных

| Требование | Описание | Критерий приема |
|---|---|---|
| **FS-REDACT-001** | Распознавание номеров кредитных карт | Regex: `[0-9]{4}[\s-]?[0-9]{4}[\s-]?[0-9]{4}[\s-]?[0-9]{4}` + проверка по Luhn Algorithm; Confidence = High |
| **FS-REDACT-002** | Распознавание паспортов РФ | Regex: `[0-9]{4}[\s]?[0-9]{6}` (серия-номер) + MRZ (Machine Readable Zone) парсинг; Confidence = High |
| **FS-REDACT-003** | Распознавание электронной почты | Regex: `[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}` |
| **FS-REDACT-004** | Распознавание номеров телефонов | Regex для РФ: `\+7[\s-]?[\(]?\d{3}[\)]?[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}` или `8[\s-]?\d{3}[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}` |
| **FS-REDACT-005** | Распознавание дат | Regex: `\d{1,2}[./\-]\d{1,2}[./\-]\d{2,4}` (для DD.MM.YYYY, MM/DD/YYYY) |
| **FS-REDACT-006** | Распознавание ИНН/СНИЛС/КПП | Regex: ИНН `\d{10,12}`, СНИЛС `\d{11}`, КПП `\d{9}` |
| **FS-REDACT-007** | Пользовательские шаблоны (Custom Patterns) | Пользователь может добавить собственный Regex через Settings; применяется ко всем новым скан-сессиям |

### 2.2.2 Визуальный редактор маскирования

| Требование | Описание | Критерий приема |
|---|---|---|
| **FS-REDACT-008** | Отображение найденных данных на фото | Все обнаруженные чувствительные участки подчеркиваются красной рамкой с иконкой "Eye" (видно/скрыто) |
| **FS-REDACT-009** | Ручное выделение области (свободное рисование) | Пользователь может нарисовать любой прямоугольник пальцем на экране (Touch Draw) и пометить его как "Redact" |
| **FS-REDACT-010** | Toggle "Hide/Show" для каждого найденного блока | Нажатие на рамку -> Глаз открыт/закрыт; если закрыт, то в финальном PDF эта область будет закрашена черным |
| **FS-REDACT-011** | Предпросмотр финального PDF | До экспорта пользователь видит, какие области будут скрыты (черные прямоугольники наложены на фото) |
| **FS-REDACT-012** | Undo/Redo для операций редактирования | История последних 10 действий (Add Redact, Remove Redact, Custom Draw) |

### 2.2.3 Деструктивное сохранение (Anti-Recovery)

| Требование | Описание | Критерий приема |
|---|---|---|
| **FS-REDACT-013** | Замена пикселей в финальном PDF | Все пиксели в области "Redact" переписываются на черный (RGB 0,0,0); невозможно восстановить через "Undo" или инструменты типа Photoshop |
| **FS-REDACT-014** | Удаление OCR-слоя в зоне редактирования | Если в PDF встроен текстовый слой, все операторы (TJ/Tj) в границах Redact-прямоугольника удаляются; поиск (Ctrl+F) не находит закрытый текст |
| **FS-REDACT-015** | Шифрование временных файлов на диске | Все промежуточные файлы (temp JPEG, temp PDF) сохраняются в зашифрованном виде в приватной директории приложения (`/data/data/com.securefield/files/`) |
| **FS-REDACT-016** | Удаление истории редактирования после экспорта | После успешного экспорта все временные файлы (промежуточные фото, JSON координаты, черновики PDF) безвозвратно удаляются (SecureDelete) |

***

## 2.3 Модуль Data Matching (Сверка с базой)

### 2.3.1 Импорт справочников

| Требование | Описание | Критерий приема |
|---|---|---|
| **FS-MATCH-001** | Поддержка CSV-формата | Парсинг файла с кодировкой UTF-8 или Windows-1251; детектирование разделителя (`,`, `;`, `\t`) автоматически |
| **FS-MATCH-002** | Поддержка JSON-формата | Импорт массива объектов: `[{"sku": "123", "name": "Product"}, ...]`; валидация схемы (должны быть обязательные поля) |
| **FS-MATCH-003** | Маппинг полей при импорте | UI диалог: Пользователь выбирает из dropdown, какая колонка в CSV соответствует "SKU", "Наименование", "Цена" и т.д. |
| **FS-MATCH-004** | Преобразование Excel в CSV | При выборе файла `.xlsx` приложение автоматически конвертирует первый лист в CSV (используется `com.opencsv:opencsv`) и затем импортирует |
| **FS-MATCH-005** | Валидация данных перед импортом | Проверка целостности (дублей, пустых ячеек); пользователю показывается кол-во ошибок и предложение удалить неполные строки |
| **FS-MATCH-006** | Кэширование справочника в памяти | После импорта размещает часто используемые записи в HashMap для быстрого доступа (O(1)) |

### 2.3.2 Моторе сопоставления (Matching Engine)

| Требование | Описание | Критерий приема |
|---|---|---|
| **FS-MATCH-007** | Режим "Exact Match" | Простое сравнение строк с нормализацией (trim, UPPERCASE); совпадение = 100% идентичные коды SKU |
| **FS-MATCH-008** | Режим "Fuzzy Match" | Использование алгоритма Token Set Ratio (из библиотеки `me.xdrop:fuzzywuzzy`) с порогом **≥ 90%** |
| **FS-MATCH-009** | Обработка опечаток | Fuzzy Match должен находить артикулы с 1-2 опечатками (e.g., "ABC123" vs "AВС123" — латиница vs кириллица) |
| **FS-MATCH-010** | Учет регистра и разделителей | Нормализация: "SKU-123-XYZ" == "sku_123_xyz" |
| **FS-MATCH-011** | Результат с confidence score | Для каждого найденного совпадения выводится % уверенности (70-100%). Пользователь видит Top-N результатов (Top-5) |
| **FS-MATCH-012** | Ручная проверка результатов | Пользователь видит список найденных артикулов, может выбрать правильный или отклонить все (Manual Override) |

### 2.3.3 Интеграция с AR-превью

| Требование | Описание | Критерий приема |
|---|---|---|
| **FS-MATCH-013** | Визуализация совпадений в камере | Во время Live Preview показывают рамки вокруг найденного текста: Зеленая = Найдено в базе, Красная = Не найдено |
| **FS-MATCH-014** | Стабилизация рамок (Temporal Smoothing) | Рамки не дергаются от кадра к кадру (простой фильтр: позиция боящихся следует за позицией боящихся текущим кадром с коэффициентом 0.7) |
| **FS-MATCH-015** | Показ информации о найденном товаре | Tap на зеленую рамку -> Всплывает карточка с SKU, Наименованием, Ценой (если есть в справочнике) |
| **FS-MATCH-016** | Экспорт результатов сверки в JSON | Пользователь может выгрузить список найденных артикулов в JSON с полями: OCR-Text, FoundSKU, Confidence, Coordinates |

***

## 2.4 Модуль автоматического заполнения форм (Form Filler)

| Требование | Описание | Критерий приема |
|---|---|---|
| **FS-FORM-001** | Обнаружение структурированных полей в документе | Алгоритм ищет паттерны: "Фамилия:", "ФИО:", "Артикул:", "Дата:", "Подпись:" (в том числе подчеркнутые / с правой выравниванием) |
| **FS-FORM-002** | Заполнение полей OCR-данными | Автоматически связывает найденный текст с полем формы (ML классификатор или простой Regex-маппер); показывает предложение заполнения |
| **FS-FORM-003** | Ручное редактирование значений | Пользователь видит форму с предзаполненными полями и может изменить любое значение |
| **FS-FORM-004** | Экспорт заполненной формы в JSON/CSV | Выгрузка структурированных данных для загрузки в ERP-систему (Возможна интеграция через REST API) |
| **FS-FORM-005** | Сохранение шаблонов форм | Пользователь может сохранить "шаблон": какие поля есть в типичном документе, в каком порядке; при следующем сканировании шаблон применяется автоматически |

***

## 2.5 Модуль библиотеки документов (Document Library)

| Требование | Описание | Критерий приема |
|---|---|---|
| **FS-LIB-001** | Локальное хранилище сканов | Все отредактированные PDF сохраняются в зашифрованной БД (Room + SQLCipher) с метаданными |
| **FS-LIB-002** | Список документов с фильтрацией | UI: Таблица с колонками "Дата", "Тип", "Статус редактирования", "Результат сверки" |
| **FS-LIB-003** | Полнотекстовый поиск по названию/дате | Поиск по фрагменту названия, дате или по найденному SKU-коду (FTS4/FTS5 в SQLite) |
| **FS-LIB-004** | Экспорт документов из приложения | Пользователь может выгрузить PDF и результаты сверки (JSON) на ПК через USB или Email (защищено паролем) |
| **FS-LIB-005** | Удаление документа с перезаписью | При удалении файл перезаписывается случайными данными 3 раза перед окончательным удалением (NIST SP 800-88) |
| **FS-LIB-006** | Архивирование старых скан-сессий | Возможность упаковать документы старше N дней в защищенный ZIP и переместить на SD-карту (для экономии памяти) |

***

## 2.6 Модуль безопасности и лицензирования (Security & Licensing)

| Требование | Описание | Критерий приема |
|---|---|---|
| **FS-SEC-001** | Флаг FLAG_SECURE для всех Activity | Запрещение захвата экрана (скриншоты, запись видео) через `window.setFlags(FLAG_SECURE, FLAG_SECURE)` |
| **FS-SEC-002** | Запрет Copy-Paste в компонентах с данными | Override `ActionMode.Callback` в EditText/TextView для предотвращения копирования |
| **FS-SEC-003** | Шифрование локальной БД | Использование SQLCipher с ключом, хранящимся в KeyStore (EncryptedSharedPreferences для master key) |
| **FS-SEC-004** | Очистка памяти после операций | После завершения OCR сессии все промежуточные Bitmap-объекты явно обнуляются (`bitmap.recycle()`) |
| **FS-SEC-005** | Верификация целостности приложения | Контроль подписи APK при запуске (PackageManager.GET_SIGNING_CERTIFICATES) |
| **FS-LIC-001** | Device-Based Licensing | Генерация уникального Device ID из ANDROID_ID + SERIAL + MAC; проверка при каждом запуске |
| **FS-LIC-002** | Лицензионный файл (`.lic`) | Файл содержит: DeviceID (зашифрован), Expiration Date, Features (JSON); подписано RSA-ключом поставщика |
| **FS-LIC-003** | Верификация подписи лицензии | Приложение проверяет подпись файла `.lic` публичным RSA-ключом (2048-бит), встроенным в APK |
| **FS-LIC-004** | Блокировка функций при отсутствии лицензии | Все основные функции (Scan, Redact, Match) заблокированы; пользователь видит экран "Введите лицензию" |
| **FS-LIC-005** | Защита от обхода лицензирования | Проверка лицензии не должна быть в откомпилированном исходнике; использование ProGuard/R8 для обфускации логики проверки |
| **FS-LIC-006** | Режим Trial (опционально) | На 30 дней предоставляется Trial лицензия без ввода ключа; по истечении требуется коммерческая лицензия |

***

## 2.7 Интеграция с внешними системами

| Требование | Описание | Критерий приема |
|---|---|---|
| **FS-API-001** | REST API для отправки результатов | Приложение может отправить JSON с результатами сверки на внешний сервер (URL, Auth Token настраиваются в Settings) |
| **FS-API-002** | Webhook уведомление при завершении сверки | Опциональный режим: при нажатии "Export", отправляется POST на URL вида `https://client.local/api/scans` с JSON-телом |
| **FS-API-003** | Обработка ошибок при отправке | Если сервер недоступен, результаты сохраняются локально и переправляются при восстановлении соединения (Queue) |

***

# 3. Требования нефункциональные

## 3.1 Производительность

| Требование | Описание | Критерий приема |
|---|---|---|
| **NFR-PERF-001** | OCR инференция для фото A4 @ 300 DPI | < 3 сек на Snapdragon 680 (худший случай — мелкий печатный текст) |
| **NFR-PERF-002** | Fuzzy Match для 10,000 артикулов | < 200 мс для одного поиска; если больше, внедрить FTS4/FTS5 в SQLite |
| **NFR-PERF-003** | Генерация PDF | < 1 сек для стандартного документа (фото + невидимый текст) |
| **NFR-PERF-004** | UI responsiveness | Frame rate ≥ 55 FPS в режиме Live Preview (CameraX + overlay рисование) |
| **NFR-PERF-005** | Загрузка справочника в памяти | < 500 мс для загрузки БД с 10,000 записями при стартовом запуске приложения |

## 3.2 Память и диск

| Требование | Описание | Критерий приема |
|---|---|---|
| **NFR-MEM-001** | Размер установленного приложения | < 150 MB (включая модели ONNX, ресурсы); рекомендуется ~ 80-100 MB |
| **NFR-MEM-002** | Использование оперативной памяти при OCR | Peak RAM < 300 MB во время инференции; базовое потребление < 50 MB в неактивном режиме |
| **NFR-MEM-003** | Кэш временных файлов | Все временные файлы сохраняются в `app_cache_dir` и автоматически очищаются при завершении сессии; лимит < 500 MB |
| **NFR-MEM-004** | Внутреннее хранилище БД | Шифрованная БД может расти до 2 GB (примерно 100K записей справочника + 10K отредактированных PDF); пользователь может выполнить архивирование |

## 3.3 Безопасность

| Требование | Описание | Критерий приема |
|---|---|---|
| **NFR-SEC-001** | Шифрование транспортного уровня | Все REST API вызовы используют HTTPS с проверкой сертификатов (Certificate Pinning опционально) |
| **NFR-SEC-002** | Шифрование БД | SQLCipher с ключом в KeyStore; ключ не хранится в SharedPreferences открытым текстом |
| **NFR-SEC-003** | Шифрование временных файлов | Все файлы в `app_cache_dir` шифруются перед сохранением и расшифровываются при чтении (использование EncryptedFile из AndroidX Security) |
| **NFR-SEC-004** | Размер ключей криптографии | RSA ≥ 2048 бит для лицензирования; AES-256 для шифрования БД и файлов |
| **NFR-SEC-005** | Отсутствие hard-coded секретов | Все ключи, токены и endpoints хранятся в BuildConfig (не в исходном коде) или KeyStore; public RSA ключ встроен в APK |
| **NFR-SEC-006** | Обфускация кода | Использование R8/ProGuard для обфускации бизнес-логики (особенно лицензирования); все строки с sensitive данными переводятся в констант-фолдинг |
| **NFR-SEC-007** | Защита от root-доступа | Рекомендуется добавить проверку наличия root прав при старте (SafetyNet/Play Integrity API); если обнаружен root, функции отключаются |

## 3.4 Совместимость

| Требование | Описание | Критерий приема |
|---|---|---|
| **NFR-COMPAT-001** | Минимальная версия ОС | Android 8.0 (API 26); максимальная тестирование на Android 15 |
| **NFR-COMPAT-002** | Рабочие конфигурации экрана | Портрет (primary) и Ландшафт (secondary) режимы; адаптивный UI для экранов 5"-12" |
| **NFR-COMPAT-003** | Поддержка различных DPI | ldpi, mdpi, hdpi, xhdpi, xxhdpi, xxxhdpi; ресурсы масштабируются автоматически |
| **NFR-COMPAT-004** | Архитектуры процессора | ARM64 (основная), ARM32 (поддержка); x86/x86_64 не требуются в MVP |
| **NFR-COMPAT-005** | Совместимость с SDN и правилами Google Play | Приложение соответствует Google Play Policies (no root detection обязательно, FLAG_SECURE, правильная обработка permissions) |

## 3.5 Надежность

| Требование | Описание | Критерий приема |
|---|---|---|
| **NFR-REL-001** | Обработка ошибок OCR | Если модель не может распознать текст (confidence < 0.3), выводится предупреждение пользователю "Низкое качество текста, результат может быть неточным" |
| **NFR-REL-002** | Recovery при сбое | Если приложение крашится во время редактирования PDF, при следующем запуске предлагается восстановить последнюю сессию из кэша |
| **NFR-REL-003** | Graceful shutdown | Приложение корректно завершает все потоки (Coroutines) при нажатии "Back" или завершении процесса; нет утечек памяти |
| **NFR-REL-004** | Логирование и диагностика | Все критические операции логируются в локальный файл (rotatable log, max 10 MB); logs доступны для экспорта через Settings (для отладки) |
| **NFR-REL-005** | Анализ сбоев (Crash Reporting) | Опционально: сбои отправляются на сервер разработчика (Firebase Crashlytics) для анализа; пользователь может отключить в Settings |

***

# 4. Детальные спецификации модулей

## 4.1 Модуль CameraX + OCR (Scan Engine)

### 4.1.1 Архитектура

```
CameraFragment
    ├── CameraProvider (CameraX)
    │   ├── CameraSelector (FRONT/BACK)
    │   ├── Preview (real-time display)
    │   ├── ImageAnalysis (для OCR)
    │   └── ImageCapture (для фото)
    │
    ├── OcrEngine (ONNX Runtime)
    │   ├── ModelLoader (загрузка det/rec моделей из assets)
    │   ├── ImagePreprocessor (resize, normalize)
    │   ├── OnnxSession (инференция)
    │   └── OutputParser (парсинг координат + confidence)
    │
    └── DocumentDetector (edge detection + perspective correction)
        ├── Canny Edge Detector
        └── Perspective Transform (4-point warping)
```

### 4.1.2 Детали реализации

**OcrEngine.kt:**
```kotlin
class OcrEngine(private val context: Context) {
    private lateinit var ortEnv: OrtEnvironment
    private lateinit var detSession: OrtSession
    private lateinit var recSession: OrtSession
    
    fun initialize() {
        // Загрузить модели из assets
        val detModelBytes = loadModelFromAssets("ch_PP-OCRv4_det_infer.onnx")
        val recModelBytes = loadModelFromAssets("ch_PP-OCRv4_rec_infer.onnx")
        
        ortEnv = OrtEnvironment.getEnvironment()
        
        // Настройка сессии для инференции на CPU (NNAPI опционально)
        val sessionOptions = OrtSession.SessionOptions().apply {
            // addCoremlExecutionProvider() // for iPhone
            // addTensorrtExecutionProvider() // for NVIDIA GPU
        }
        
        detSession = ortEnv.createSession(detModelBytes, sessionOptions)
        recSession = ortEnv.createSession(recModelBytes, sessionOptions)
    }
    
    fun recognize(bitmap: Bitmap): OcrResult {
        val detOutput = detectTextRegions(bitmap)
        val boxes = detOutput.parseBoxes()
        
        val recognitionResults = mutableListOf<TextBox>()
        for (box in boxes) {
            val croppedBitmap = cropBitmapToBox(bitmap, box)
            val text = recognizeText(croppedBitmap)
            recognitionResults.add(
                TextBox(
                    text = text.text,
                    confidence = text.confidence,
                    boundingBox = box
                )
            )
        }
        
        return OcrResult(recognitionResults, System.currentTimeMillis())
    }
    
    private fun detectTextRegions(bitmap: Bitmap): OrtSession.Result {
        // Preprocessing: resize to 640x640, normalize
        val inputTensor = preprocessImage(bitmap, 640, 640)
        val inputs = mapOf("x" to inputTensor)
        return detSession.run(inputs)
    }
    
    private fun recognizeText(bitmap: Bitmap): RecognitionResult {
        val inputTensor = preprocessImage(bitmap, 32, 320) // rec input size
        val inputs = mapOf("x" to inputTensor)
        val output = recSession.run(inputs)
        
        // Парсинг CTC-вывода
        val decodedText = ctcDecode(output)
        val confidence = calculateConfidence(output)
        
        return RecognitionResult(decodedText, confidence)
    }
    
    private fun preprocessImage(bitmap: Bitmap, height: Int, width: Int): OnnxTensor {
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val normalized = FloatArray(3 * width * height)
        
        resized.getPixels(IntArray(width * height), 0, width, 0, 0, width, height)
        // Нормализация: subtract mean, divide by std
        
        return OnnxTensor.createTensor(ortEnv, normalized, longArrayOf(1, 3, height.toLong(), width.toLong()))
    }
}

data class OcrResult(
    val textBoxes: List<TextBox>,
    val timestamp: Long
)

data class TextBox(
    val text: String,
    val confidence: Float,
    val boundingBox: BoundingBox
)

data class BoundingBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    fun toRect() = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
}
```

### 4.1.3 UI/Canvas для отрисовки боксов

```kotlin
class OcrOverlayView(context: Context) : View(context) {
    private var textBoxes: List<TextBox> = emptyList()
    private val paint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.GREEN
    }
    
    fun setTextBoxes(boxes: List<TextBox>) {
        textBoxes = boxes
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        for (box in textBoxes) {
            val rect = box.boundingBox.toRect()
            // Масштабирование к размеру View
            val scaledRect = scaleRectToView(rect)
            
            // Выбор цвета по confidence
            paint.color = when {
                box.confidence > 0.8f -> Color.GREEN
                box.confidence > 0.5f -> Color.YELLOW
                else -> Color.RED
            }
            
            canvas.drawRect(scaledRect.toRectF(), paint)
            
            // Рисование текста
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 12f
                color = Color.WHITE
                setShadowLayer(2f, 1f, 1f, Color.BLACK)
            }
            canvas.drawText(
                box.text,
                scaledRect.left.toFloat(),
                (scaledRect.top - 5).toFloat(),
                textPaint
            )
        }
    }
    
    private fun scaleRectToView(rect: Rect): Rect {
        val scaleX = width.toFloat() / IMAGE_WIDTH
        val scaleY = height.toFloat() / IMAGE_HEIGHT
        return Rect(
            (rect.left * scaleX).toInt(),
            (rect.top * scaleY).toInt(),
            (rect.right * scaleX).toInt(),
            (rect.bottom * scaleY).toInt()
        )
    }
}
```

***

## 4.2 Модуль Smart Redaction (Redaction Engine)

### 4.2.1 Интеграция с PDFBox

```kotlin
class PdfRedactionEngine {
    fun redactAndSavePdf(
        sourceImagePath: String,
        textBoxes: List<TextBox>,
        redactionMask: RedactionMask,
        outputPath: String
    ) {
        // 1. Создать новый PDF с фото + OCR слой
        val doc = PDDocument()
        val page = PDPage(PDRectangle.LETTER)
        doc.addPage(page)
        
        val contentStream = PDPageContentStream(doc, page)
        
        // 2. Вставить фото
        val image = PDImageXImage.createFromFile(sourceImagePath, doc)
        contentStream.drawImage(image, 50f, 200f, 500f, 500f)
        
        // 3. Нарисовать черные прямоугольники для редактирования
        contentStream.setNonStrokingColor(0f, 0f, 0f) // Black
        for (box in redactionMask.redactedBoxes) {
            val rect = box.boundingBox
            contentStream.addRect(
                rect.left,
                rect.top,
                rect.right - rect.left,
                rect.bottom - rect.top
            )
            contentStream.fill()
        }
        
        // 4. Добавить невидимый текстовый слой для поиска
        // (только для незакрытых текстов)
        contentStream.setNonStrokingColor(1f, 1f, 1f) // White (invisible)
        contentStream.setFont(PDType1Font.HELVETICA, 10f)
        
        for (box in textBoxes) {
            if (!redactionMask.isRedacted(box)) {
                contentStream.beginText()
                contentStream.setTextMatrix(
                    1f, 0f,
                    0f, 1f,
                    box.boundingBox.left,
                    box.boundingBox.top
                )
                contentStream.showText(box.text)
                contentStream.endText()
            }
        }
        
        contentStream.close()
        
        // 5. Сохранить с защитой (опционально)
        doc.save(outputPath)
        doc.close()
    }
    
    fun redactExistingPdf(
        sourcePdfPath: String,
        redactionMask: RedactionMask,
        outputPath: String
    ) {
        val doc = PDDocument.load(File(sourcePdfPath))
        val page = doc.getPage(0) as PDPage
        val content = page.contentStream
        
        // Парсинг контент-стрима и удаление операторов текста в зоне редактирования
        val modifiedStream = removeTextInRedactionZones(content, redactionMask)
        page.setContents(modifiedStream)
        
        // Добавить черные прямоугольники
        val contentStream = PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true)
        contentStream.setNonStrokingColor(0f, 0f, 0f)
        
        for (box in redactionMask.redactedBoxes) {
            val rect = box.boundingBox
            contentStream.addRect(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
            contentStream.fill()
        }
        
        contentStream.close()
        doc.save(outputPath)
        doc.close()
    }
    
    private fun removeTextInRedactionZones(
        stream: PDStream,
        mask: RedactionMask
    ): PDStream {
        // Сложная операция парсинга PDF контент-стрима
        // Для MVP можно упростить: просто добавить черный прямоугольник поверх
        return stream
    }
}

data class RedactionMask(
    val redactedBoxes: List<TextBox>
) {
    fun isRedacted(box: TextBox): Boolean {
        return redactedBoxes.any { it.boundingBox.intersects(box.boundingBox) }
    }
}

// Extension функция
fun BoundingBox.intersects(other: BoundingBox): Boolean {
    return !(right < other.left || left > other.right ||
            bottom < other.top || top > other.bottom)
}
```

***

## 4.3 Модуль Matching (Matching Engine)

### 4.3.1 Data Access Layer (Room + SQLCipher)

```kotlin
// Entity
@Entity(tableName = "product_items")
data class ProductItem(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "sku")
    val sku: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "price")
    val price: Double? = null,
    @ColumnInfo(name = "category")
    val category: String? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis()
)

// DAO
@Dao
interface ProductItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<ProductItem>)
    
    @Query("SELECT * FROM product_items WHERE sku = :sku")
    suspend fun findBySku(sku: String): ProductItem?
    
    @Query("SELECT * FROM product_items WHERE sku LIKE '%' || :pattern || '%'")
    suspend fun findBySkyPattern(pattern: String): List<ProductItem>
    
    @Query("SELECT COUNT(*) FROM product_items")
    suspend fun count(): Int
    
    @Query("DELETE FROM product_items")
    suspend fun deleteAll()
}

// Database
@Database(entities = [ProductItem::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productItemDao(): ProductItemDao
}
```

### 4.3.2 Matching Service

```kotlin
class MatchingService(
    private val dao: ProductItemDao,
    private val encryptionManager: EncryptionManager
) {
    private val memoryCache = mutableMapOf<String, ProductItem>()
    private val fuzzyMatcher = FuzzyMatcher()
    
    suspend fun importFromCsv(csvFile: File, mapping: FieldMapping) {
        val reader = CSVReader(FileReader(csvFile))
        val records = reader.readAll()
        
        val items = records.drop(1).mapNotNull { record ->
            try {
                ProductItem(
                    sku = record[mapping.skuIndex],
                    name = record[mapping.nameIndex],
                    price = record.getOrNull(mapping.priceIndex)?.toDoubleOrNull(),
                    category = record.getOrNull(mapping.categoryIndex)
                )
            } catch (e: Exception) {
                null
            }
        }
        
        dao.deleteAll()
        dao.insertAll(items)
        
        // Load into cache
        memoryCache.clear()
        for (item in items) {
            memoryCache[item.sku] = item
        }
    }
    
    suspend fun importFromJson(jsonFile: File, mapping: FieldMapping) {
        val json = jsonFile.readText()
        val items = JSONArray(json).let { array ->
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                ProductItem(
                    sku = obj.getString(mapping.skuFieldName),
                    name = obj.getString(mapping.nameFieldName),
                    price = obj.optDouble(mapping.priceFieldName),
                    category = obj.optString(mapping.categoryFieldName)
                )
            }
        }
        
        dao.deleteAll()
        dao.insertAll(items)
        memoryCache.putAll(items.associateBy { it.sku })
    }
    
    suspend fun match(text: String, mode: MatchMode = MatchMode.AUTO): List<MatchResult> {
        val normalized = text.normalize() // trim, uppercase
        
        // Точный поиск в кэше
        memoryCache[normalized]?.let {
            return listOf(MatchResult.Exact(it, confidence = 1.0f))
        }
        
        // Fuzzy поиск
        if (mode == MatchMode.AUTO || mode == MatchMode.FUZZY) {
            val results = memoryCache.values
                .mapNotNull { item ->
                    val score = fuzzyMatcher.tokenSetRatio(text, item.sku)
                    if (score >= FUZZY_THRESHOLD) {
                        MatchResult.Fuzzy(item, confidence = score / 100f)
                    } else null
                }
                .sortedByDescending { it.confidence }
                .take(5)
            
            if (results.isNotEmpty()) {
                return results
            }
        }
        
        // Fallback: поиск в БД (более медленно)
        val dbResults = dao.findBySkyPattern(normalized)
        return dbResults.map { MatchResult.Exact(it, confidence = 0.5f) }
    }
    
    private fun String.normalize(): String {
        return this.trim().uppercase(Locale.getDefault())
    }
    
    companion object {
        private const val FUZZY_THRESHOLD = 90
    }
}

enum class MatchMode {
    EXACT, FUZZY, AUTO
}

sealed class MatchResult(
    open val item: ProductItem,
    open val confidence: Float
) {
    data class Exact(override val item: ProductItem, override val confidence: Float) : MatchResult(item, confidence)
    data class Fuzzy(override val item: ProductItem, override val confidence: Float) : MatchResult(item, confidence)
}

data class FieldMapping(
    val skuIndex: Int,
    val nameIndex: Int,
    val priceIndex: Int? = null,
    val categoryIndex: Int? = null,
    val skuFieldName: String = "sku",
    val nameFieldName: String = "name",
    val priceFieldName: String = "price",
    val categoryFieldName: String = "category"
)
```

### 4.3.3 Fuzzy Matcher

```kotlin
class FuzzyMatcher {
    /**
     * Token Set Ratio from FuzzyWuzzy
     * Нормирует строки, разбивает на токены и сравнивает
     */
    fun tokenSetRatio(s1: String, s2: String): Float {
        val tokens1 = s1.tokenize().sorted()
        val tokens2 = s2.tokenize().sorted()
        
        val intersection = tokens1.intersect(tokens2.toSet()).size
        val union = tokens1.union(tokens2).size
        
        if (union == 0) return 100f
        return (intersection.toFloat() / union * 100).coerceIn(0f, 100f)
    }
    
    private fun String.tokenize(): List<String> {
        return this.lowercase(Locale.getDefault())
            .replace(Regex("[^а-яa-z0-9]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
    }
}
```

***

## 4.4 Модуль Security & Licensing

### 4.4.1 License Manager

```kotlin
class LicenseManager(private val context: Context) {
    private val keyStore = KeyStore.getInstance("AndroidKeyStore")
    private val encryptor = EncryptionManager(context)
    
    fun generateDeviceId(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val hwid = Build.SERIAL + Build.DEVICE
        val mac = getWiFiMacAddress()
        
        val combined = "$androidId|$hwid|$mac"
        return MessageDigest.getInstance("SHA-256")
            .digest(combined.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .substring(0, 32)
    }
    
    fun verifyLicense(licenseFile: File): Boolean {
        val licenseContent = licenseFile.readText()
        val json = JSONObject(licenseContent)
        
        val encryptedDeviceId = json.getString("device_id")
        val signature = json.getString("signature")
        val expiryDate = json.getString("expiry_date")
        
        // 1. Проверка срока
        val expiry = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(expiryDate)
        if (System.currentTimeMillis() > expiry.time) {
            return false // Лицензия истекла
        }
        
        // 2. Проверка подписи
        val publicKey = loadPublicKeyFromAssets()
        val isSignatureValid = verifySignature(
            encryptedDeviceId,
            signature.decodeBase64(),
            publicKey
        )
        
        // 3. Проверка Device ID
        val currentDeviceId = generateDeviceId()
        val decryptedDeviceId = encryptor.decrypt(encryptedDeviceId)
        
        return isSignatureValid && (decryptedDeviceId == currentDeviceId)
    }
    
    private fun verifySignature(data: String, signature: ByteArray, publicKey: PublicKey): Boolean {
        return try {
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initVerify(publicKey)
            sig.update(data.toByteArray())
            sig.verify(signature)
        } catch (e: Exception) {
            false
        }
    }
    
    private fun loadPublicKeyFromAssets(): PublicKey {
        val keyFactory = KeyFactory.getInstance("RSA")
        val keyBytes = context.assets.open("public_key.pem").readBytes()
        val spec = X509EncodedKeySpec(keyBytes)
        return keyFactory.generatePublic(spec)
    }
    
    private fun getWiFiMacAddress(): String {
        return try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val connectionInfo = wifiManager.connectionInfo
            connectionInfo?.macAddress ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }
}

fun String.decodeBase64(): ByteArray {
    return Base64.getDecoder().decode(this)
}
```

### 4.4.2 Encryption Manager

```kotlin
class EncryptionManager(private val context: Context) {
    private val masterKeyAlias = "SecureField_MasterKey"
    private val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    
    fun encrypt(plaintext: String): String {
        val key = getOrCreateMasterKey()
        val iv = ByteArray(12) // GCM IV
        Random().nextBytes(iv)
        
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        
        val encrypted = cipher.doFinal(plaintext.toByteArray())
        val combined = iv + encrypted
        
        return Base64.getEncoder().encodeToString(combined)
    }
    
    fun decrypt(encrypted: String): String {
        val key = getOrCreateMasterKey()
        val combined = Base64.getDecoder().decode(encrypted)
        
        val iv = combined.take(12).toByteArray()
        val ciphertext = combined.drop(12).toByteArray()
        
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        
        val decrypted = cipher.doFinal(ciphertext)
        return String(decrypted)
    }
    
    private fun getOrCreateMasterKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)
        
        return if (keyStore.containsAlias(masterKeyAlias)) {
            keyStore.getKey(masterKeyAlias, null) as SecretKey
        } else {
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                masterKeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        }
    }
}
```

***

# 5. Тестирование и валидация

## 5.1 Unit Tests

| Тест ID | Модуль | Сценарий | Критерий |
|---------|--------|----------|----------|
| **UT-MATCH-001** | MatchingService | Exact match known SKU | `match("ABC123")` вернет ProductItem с confidence = 1.0 |
| **UT-MATCH-002** | FuzzyMatcher | Fuzzy match с опечаткой | `tokenSetRatio("ABC123", "AВС123")` ≥ 90% (латиница vs кириллица) |
| **UT-REDACT-001** | PdfRedactionEngine | Redaction на черный квадрат | Сгенерированный PDF имеет черный прямоугольник в области редактирования |
| **UT-LICENSE-001** | LicenseManager | Verify valid license | Валидный `.lic` файл проходит проверку |
| **UT-LICENSE-002** | LicenseManager | Reject expired license | Истекшая лицензия отклоняется |
| **UT-ENCRYPT-001** | EncryptionManager | Encrypt/Decrypt round-trip | `encrypt(plaintext)` → `decrypt(result)` == plaintext |

## 5.2 Integration Tests

| Тест ID | Сценарий | Критерий |
|---------|----------|----------|
| **IT-SCAN-001** | Полный цикл сканирования: фото → OCR → PDF | PDF создается успешно и содержит невидимый текст |
| **IT-REDACT-001** | Редактирование PDF: загрузка → маскирование → сохранение | Выходной PDF не содержит текста в области маскирования |
| **IT-MATCH-001** | Импорт справочника → Сверка артикула | Найденные артикулы соответствуют справочнику |
| **IT-PERF-001** | OCR на Snapdragon 680 | Время < 3 сек |
| **IT-SECURITY-001** | Проверка лицензии при старте | Приложение блокируется без валидной лицензии |

## 5.3 Security Audit

| Пункт | Проверка | Результат |
|-------|----------|-----------|
| **SA-001** | FLAG_SECURE включен | Скриншоты блокированы (Screen record не работает) |
| **SA-002** | SQLCipher шифрует БД | БД файл не читается без пароля |
| **SA-003** | Temporary files шифруются | Файлы в cache_dir не читаются без ключа |
| **SA-004** | Hard-coded секреты отсутствуют | BuildConfig не содержит API ключей, tokens в KeyStore |
| **SA-005** | Proguard обфускирует код | Decompiled APK не содержит читаемой логики лицензирования |
| **SA-006** | Нет root-access | Приложение закрывает доступ при обнаружении root (опционально) |

## 5.4 User Acceptance Testing (UAT)

| Сценарий | Описание | Критерий |
|----------|----------|----------|
| **UAT-001** | Первый запуск, ввод лицензии | Экран ввода отображается, приложение функционирует после ввода ключа |
| **UAT-002** | Сканирование счета с реальной камеры | Текст детектируется правильно, фото выпрямляется |
| **UAT-003** | Редактирование: скрытие реквизитов | Пользователь видит фото с рамками, может отметить области для скрытия |
| **UAT-004** | Импорт CSV, сверка артикулов | Артикулы находятся, показываются зеленые рамки |
| **UAT-005** | Экспорт PDF | Файл сохраняется, открывается в Adobe Reader, не содержит скрытого текста в поиске |

***

# 6. План развертывания

## 6.1 Этапы разработки (8 недель)

| Неделя | Этап | Задачи | Deliverables |
|--------|------|--------|--------------|
| 1-2 | **Фаза 1: Сканирование** | Настройка проекта, CameraX, ONNX Runtime, OCR инженирование | APK "SecureScanner" с функцией "сканировать документ" |
| 3-4 | **Фаза 2: Редактирование** | Реализация маскирования, PDFBox интеграция, деструктивное сохранение | APK с модулем Smart Redaction |
| 5-6 | **Фаза 3: Сверка** | Import CSV/JSON, Matching Engine, AR overlay, Storage (Room+SQLCipher) | APK с модулем Data Matching |
| 7-8 | **Фаза 4: Лицензирование** | Licensing Manager, FLAG_SECURE, полировка UI, тестирование | Production-ready APK |

## 6.2 Версионирование

- **v0.1.0-alpha:** Фаза 1 (сканирование)
- **v0.2.0-alpha:** Фаза 2 (редактирование)
- **v0.3.0-beta:** Фаза 3 (сверка)
- **v1.0.0:** Фаза 4 (release) + Security audit + UAT

## 6.3 Дистрибуция

- **Internal Testing:** Google Play Internal Test Track (для QA)
- **Beta:** Google Play Beta Channel (ограничено 100 тестерам)
- **Production:** Google Play Release Channel (ограничено по странам; первично РФ)

## 6.4 CI/CD Pipeline

```
GitHub (+ branch protection)
    ↓
GitHub Actions (on push to main)
    ├── Compile & Unit Tests
    ├── Lint (detekt, Android Lint)
    ├── Security scan (ProGuard check)
    └── Build APK
        ↓
    Upload to Firebase App Distribution (internal) / Google Play (beta/release)
```

***

# 7. Риски и смягчение

| Риск ID | Описание | Вероятность | Воздействие | Стратегия смягчения |
|---------|----------|-----------|-----------|-------|
| **R-001** | Лицензирование обойдено (破解) | Средняя | Критическое | Обфускация ProGuard R8, проверка целостности APK, API за лицензией (опционально) |
| **R-002** | OCR модель весит > 150 MB | Низкая | Среднее | Использование более легких моделей (MobileNet-based), динамическая загрузка из облака |
| **R-003** | PDF редактирование сложнее, чем ожидается (парсинг контент-стрима) | Средняя | Среднее | Для MVP достаточно просто рисовать черный квадрат; полное удаление текста — на v2 |
| **R-004** | Fuzzy Match дает false positives (похожие артикулы) | Средняя | Среднее | Предоставить пользователю ручную проверку (Top-5 результатов), увеличить порог до 95% |
| **R-005** | SQLCipher overhead замедляет поиск > 200 мс | Низкая | Среднее | Внедрить FTS4/FTS5 в SQLite для быстрого поиска текста |
| **R-006** | Устройства без камеры (планшет без линзы) | Низкая | Низкое | Добавить режим "Load from Photo" (импорт фото из галереи) |
| **R-007** | Permissions (CAMERA, READ_EXTERNAL_STORAGE) могут быть заблокированы | Средняя | Среднее | Graceful degradation: предложить пользователю разрешения, покажите экран "Why we need this" |
| **R-008** | Google Play Policy нарушения (safety, privacy) | Низкая | Критическое | Тщательный review перед публикацией, no root detection обязательно, privacy policy в наличии |

***

## Итоговое резюме ТЗ

**SecureField MVP** — это защищенное приложение для сканирования, редактирования и сверки конфиденциальных документов с базой данных компании.

**Ключевые компоненты:**
1. ✅ **OCR Engine** (PaddleOCR v4 ONNX INT8) — локальная инференция
2. ✅ **Smart Redaction** — маскирование и деструктивное удаление данных
3. ✅ **Data Matching** — Fuzzy search артикулов + AR визуализация
4. ✅ **Secure Storage** — Room + SQLCipher шифрованные данные
5. ✅ **Licensing** — Device-based, подписанные `.lic` файлы

**Сроки:** 8 недель  
**Бюджет:** ~15K EUR (в зависимости от команды)  
**Статус:** Готово к разработке

***

Документ готов для передачи разработчикам. Он содержит все необходимое для начала реализации. 💪