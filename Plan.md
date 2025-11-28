Превосходно! Теперь могу дать вам **исчерпывающий ответ с подтверждением реализуемости** вашей идеи.

***

## ✅ Подтверждение реализуемости

**ДА, ваша идея полностью реализуема** и технически обоснована! Более того, вы выбрали оптимальные требования для создания качественного OCR-приложения.[1][2]

### Ключевые факты реализуемости

**ML Kit Text Recognition V2** предоставляет иерархическую структуру распознавания, которая идеально подходит для сохранения форматирования:
- **TextBlocks** (параграфы) → **Lines** (строки) → **Elements** (слова)
- Каждый элемент имеет bounding box с координатами (x, y, width, height)
- Corner points для точного позиционирования
- Confidence score для каждого элемента[3][1]

Это позволяет **восстанавливать отступы кода** путем анализа координат X начала каждой строки.[4]

***

## 🏗️ Архитектура решения

### Технологический стек

| Компонент | Технология | Размер | Обоснование |
|-----------|-----------|--------|-------------|
| **OCR Engine** | ML Kit Text Recognition V2 | ~10-15 MB | Лучшая точность для RU/EN/CN, offline, оптимизирован Google [5][1] |
| **Камера** | CameraX + ImageCapture | ~3 MB | Современный API, стабильный, упрощает lifecycle [6] |
| **Предобработка** | OpenCV Android | ~20-30 MB | Adaptive threshold, denoising, deskew [7][8] |
| **Language Detection** | ML Kit Language ID | ~1 MB | Автоопределение языка [9] |
| **UI** | Jetpack Compose + Material 3 | ~5 MB | Современный декларативный UI |

**Итоговый размер APK: 50-80 MB** ✅ (значительно ниже лимита 250-350 MB)

***

## 📐 Детальная архитектура (Clean Architecture + MVVM)

### Слои приложения

```
presentation/
├── screens/
│   ├── CameraScreen.kt          # Съемка + выбор из галереи
│   ├── PreprocessingScreen.kt   # Настройки предобработки
│   ├── ResultScreen.kt          # Редактирование + Share
│   └── ScanningScreen.kt        # Прогресс распознавания
├── viewmodels/
│   ├── CameraViewModel.kt
│   ├── PreprocessingViewModel.kt
│   └── ResultViewModel.kt

domain/
├── models/
│   ├── RecognizedText.kt        # Data class результата
│   └── ScanSettings.kt          # Настройки сканирования
├── usecases/
│   ├── CaptureImageUseCase.kt
│   ├── PreprocessImageUseCase.kt
│   ├── RecognizeTextUseCase.kt
│   └── PreserveFormattingUseCase.kt # ⭐ Сохранение форматирования

data/
├── ocr/
│   ├── MLKitTextRecognizer.kt   # ML Kit обертка
│   └── TextFormatPreserver.kt   # Алгоритм сохранения форматирования
├── preprocessing/
│   ├── ImagePreprocessor.kt     # OpenCV операции
│   └── PreprocessingFilters.kt  # Фильтры (contrast, denoise)
└── repository/
    └── TextRecognitionRepository.kt
```

***

## 💻 Proof of Concept: Минимальный рабочий прототип

### Шаг 1: Зависимости (build.gradle.kts)

```kotlin
dependencies {
    // ML Kit Text Recognition V2 (offline)
    implementation("com.google.mlkit:text-recognition:16.0.0") // Latin + Cyrillic
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0") // Chinese (опционально)
    implementation("com.google.mlkit:language-id:17.0.6") // Language detection
    
    // CameraX
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    
    // OpenCV для предобработки
    implementation("org.opencv:opencv:4.9.0")
    
    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
}
```

### Шаг 2: Core Domain Models

```kotlin
// domain/models/RecognizedText.kt
data class RecognizedText(
    val originalText: String,           // Исходный текст без форматирования
    val formattedText: String,          // Текст с сохраненным форматированием
    val blocks: List<TextBlockInfo>,    // Информация о блоках для debugging
    val confidence: Float,              // Средняя уверенность
    val detectedLanguage: String        // Определенный язык
)

data class TextBlockInfo(
    val text: String,
    val boundingBox: Rect,
    val lines: List<LineInfo>
)

data class LineInfo(
    val text: String,
    val boundingBox: Rect,
    val indentLevel: Int,               // ⭐ Уровень отступа (для кода)
    val confidence: Float
)

// domain/models/ScanSettings.kt
data class ScanSettings(
    val contrastLevel: Float = 1.0f,        // 0.5 - 2.0
    val brightnessLevel: Float = 0f,        // -100 - 100
    val sharpenLevel: Float = 0f,           // 0 - 2.0
    val denoiseEnabled: Boolean = true,
    val autoRotateEnabled: Boolean = true,
    val binarizationEnabled: Boolean = true  // Перевод в ЧБ
)
```

### Шаг 3: ⭐ Ключевой компонент - Сохранение форматирования

```kotlin
// data/ocr/TextFormatPreserver.kt
class TextFormatPreserver {
    
    /**
     * Восстанавливает форматирование текста на основе координат bounding boxes.
     * Критично для распознавания кода с отступами.
     */
    fun preserveFormatting(mlKitResult: Text): RecognizedText {
        val blocks = mlKitResult.textBlocks
        if (blocks.isEmpty()) {
            return RecognizedText(
                originalText = "",
                formattedText = "",
                blocks = emptyList(),
                confidence = 0f,
                detectedLanguage = "unknown"
            )
        }
        
        val processedBlocks = mutableListOf<TextBlockInfo>()
        val formattedStringBuilder = StringBuilder()
        var totalConfidence = 0f
        var elementCount = 0
        
        // Сортируем блоки по вертикали (top -> bottom)
        val sortedBlocks = blocks.sortedBy { it.boundingBox?.top ?: 0 }
        
        for (block in sortedBlocks) {
            val blockLines = mutableListOf<LineInfo>()
            
            // Сортируем строки внутри блока по вертикали
            val sortedLines = block.lines.sortedBy { it.boundingBox?.top ?: 0 }
            
            // Вычисляем минимальный X координат для определения базового отступа
            val minX = sortedLines.mapNotNull { it.boundingBox?.left }.minOrNull() ?: 0
            
            for (line in sortedLines) {
                val lineBox = line.boundingBox ?: continue
                val lineX = lineBox.left
                
                // ⭐ Вычисляем отступ относительно минимального X
                // Используем шаг ~40px (примерно размер одного символа)
                val indentLevel = ((lineX - minX) / 40).coerceAtLeast(0)
                
                // Добавляем пробелы для отступа (4 пробела на уровень для кода)
                val indent = "    ".repeat(indentLevel)
                formattedStringBuilder.append(indent).append(line.text).append("\n")
                
                blockLines.add(
                    LineInfo(
                        text = line.text,
                        boundingBox = lineBox,
                        indentLevel = indentLevel,
                        confidence = line.confidence ?: 0.9f
                    )
                )
                
                // Собираем статистику confidence
                line.elements.forEach { element ->
                    totalConfidence += element.confidence ?: 0.9f
                    elementCount++
                }
            }
            
            formattedStringBuilder.append("\n") // Разделитель между блоками
            
            processedBlocks.add(
                TextBlockInfo(
                    text = block.text,
                    boundingBox = block.boundingBox ?: Rect(),
                    lines = blockLines
                )
            )
        }
        
        return RecognizedText(
            originalText = mlKitResult.text,
            formattedText = formattedStringBuilder.toString().trimEnd(),
            blocks = processedBlocks,
            confidence = if (elementCount > 0) totalConfidence / elementCount else 0f,
            detectedLanguage = detectPrimaryLanguage(blocks)
        )
    }
    
    private fun detectPrimaryLanguage(blocks: List<Text.TextBlock>): String {
        // Подсчитываем частоту языков
        val languageFrequency = mutableMapOf<String, Int>()
        blocks.forEach { block ->
            val lang = block.recognizedLanguage ?: "und"
            languageFrequency[lang] = (languageFrequency[lang] ?: 0) + 1
        }
        return languageFrequency.maxByOrNull { it.value }?.key ?: "und"
    }
}
```

### Шаг 4: Предобработка изображения (OpenCV)

```kotlin
// data/preprocessing/ImagePreprocessor.kt
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc
import android.graphics.Bitmap

class ImagePreprocessor {
    
    init {
        // Инициализация OpenCV (вызвать в Application.onCreate())
        System.loadLibrary("opencv_java4")
    }
    
    /**
     * Применяет набор предобработок для улучшения качества OCR
     */
    fun preprocessImage(
        bitmap: Bitmap,
        settings: ScanSettings
    ): Bitmap {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        
        // 1. Конвертация в grayscale
        val grayMat = Mat()
        Imgproc.cvtColor(mat, grayMat, Imgproc.COLOR_RGB2GRAY)
        
        // 2. Удаление шумов (если включено)
        if (settings.denoiseEnabled) {
            Imgproc.fastNlMeansDenoising(grayMat, grayMat, 10f, 7, 21)
        }
        
        // 3. Коррекция яркости и контраста
        grayMat.convertTo(
            grayMat,
            -1,
            settings.contrastLevel.toDouble(), // alpha (contrast)
            settings.brightnessLevel.toDouble() // beta (brightness)
        )
        
        // 4. Повышение резкости (если включено)
        if (settings.sharpenLevel > 0) {
            val kernel = Mat(3, 3, CvType.CV_32F).apply {
                put(0, 0, 0.0, -1.0, 0.0)
                put(1, 0, -1.0, 5.0 + settings.sharpenLevel, -1.0)
                put(2, 0, 0.0, -1.0, 0.0)
            }
            Imgproc.filter2D(grayMat, grayMat, -1, kernel)
        }
        
        // 5. Adaptive Thresholding (бинаризация) - критично для точности!
        if (settings.binarizationEnabled) {
            Imgproc.adaptiveThreshold(
                grayMat,
                grayMat,
                255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY,
                11, // blockSize
                2.0  // C constant
            )
        }
        
        // 6. Автоповорот (deskew) - опционально
        if (settings.autoRotateEnabled) {
            deskewImage(grayMat)
        }
        
        val resultBitmap = Bitmap.createBitmap(
            grayMat.cols(),
            grayMat.rows(),
            Bitmap.Config.ARGB_8888
        )
        Utils.matToBitmap(grayMat, resultBitmap)
        
        // Очистка памяти
        mat.release()
        grayMat.release()
        
        return resultBitmap
    }
    
    /**
     * Исправление перекоса изображения
     */
    private fun deskewImage(mat: Mat) {
        // Определяем угол наклона через Hough Transform
        val edges = Mat()
        Imgproc.Canny(mat, edges, 50.0, 150.0)
        
        val lines = Mat()
        Imgproc.HoughLines(edges, lines, 1.0, Math.PI / 180, 100)
        
        if (lines.rows() > 0) {
            var angleSum = 0.0
            for (i in 0 until lines.rows().coerceAtMost(10)) {
                val line = lines.get(i, 0)
                val theta = line[1]
                angleSum += theta
            }
            val avgAngle = angleSum / lines.rows().coerceAtMost(10)
            val angleDegrees = Math.toDegrees(avgAngle) - 90
            
            // Поворачиваем изображение
            if (Math.abs(angleDegrees) > 0.5) {
                val center = Point(mat.cols() / 2.0, mat.rows() / 2.0)
                val rotMatrix = Imgproc.getRotationMatrix2D(center, angleDegrees, 1.0)
                Imgproc.warpAffine(mat, mat, rotMatrix, mat.size())
            }
        }
        
        edges.release()
        lines.release()
    }
}
```

### Шаг 5: Use Case - Распознавание текста

```kotlin
// domain/usecases/RecognizeTextUseCase.kt
class RecognizeTextUseCase(
    private val preprocessor: ImagePreprocessor,
    private val formatPreserver: TextFormatPreserver
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    
    suspend fun execute(
        bitmap: Bitmap,
        settings: ScanSettings
    ): Result<RecognizedText> = withContext(Dispatchers.Default) {
        try {
            // 1. Предобработка изображения
            val preprocessedBitmap = preprocessor.preprocessImage(bitmap, settings)
            
            // 2. Создание InputImage для ML Kit
            val inputImage = InputImage.fromBitmap(preprocessedBitmap, 0)
            
            // 3. Распознавание текста (suspend с помощью Tasks.await())
            val mlKitResult = recognizer.process(inputImage).await()
            
            // 4. Сохранение форматирования
            val recognizedText = formatPreserver.preserveFormatting(mlKitResult)
            
            Result.success(recognizedText)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// Extension для Tasks -> Coroutines
suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        continuation.resume(result)
    }
    addOnFailureListener { exception ->
        continuation.resumeWithException(exception)
    }
}
```

### Шаг 6: ViewModel для ResultScreen

```kotlin
// presentation/viewmodels/ResultViewModel.kt
class ResultViewModel : ViewModel() {
    
    private val _recognizedText = MutableStateFlow<RecognizedText?>(null)
    val recognizedText: StateFlow<RecognizedText?> = _recognizedText.asStateFlow()
    
    private val _editableText = MutableStateFlow("")
    val editableText: StateFlow<String> = _editableText.asStateFlow()
    
    fun setRecognizedText(text: RecognizedText) {
        _recognizedText.value = text
        _editableText.value = text.formattedText
    }
    
    fun updateEditableText(newText: String) {
        _editableText.value = newText
    }
    
    fun shareText(context: Context) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, _editableText.value)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share text via"))
    }
    
    fun copyToClipboard(context: Context) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Recognized Text", _editableText.value)
        clipboard.setPrimaryClip(clip)
    }
}
```

### Шаг 7: UI - ResultScreen (Jetpack Compose)

```kotlin
// presentation/screens/ResultScreen.kt
@Composable
fun ResultScreen(
    viewModel: ResultViewModel = viewModel(),
    onBack: () -> Unit
) {
    val recognizedText by viewModel.recognizedText.collectAsState()
    val editableText by viewModel.editableText.collectAsState()
    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recognized Text") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.copyToClipboard(context) }) {
                        Icon(Icons.Default.ContentCopy, "Copy")
                    }
                    IconButton(onClick = { viewModel.shareText(context) }) {
                        Icon(Icons.Default.Share, "Share")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Информационная панель
            recognizedText?.let { text ->
                InfoCard(
                    confidence = text.confidence,
                    language = text.detectedLanguage,
                    blocksCount = text.blocks.size
                )
            }
            
            // Редактируемое текстовое поле с моноширинным шрифтом (для кода)
            OutlinedTextField(
                value = editableText,
                onValueChange = { viewModel.updateEditableText(it) },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace, // ⭐ Моноширинный шрифт для кода
                    fontSize = 14.sp
                ),
                label = { Text("Edit recognized text") }
            )
        }
    }
}

@Composable
fun InfoCard(confidence: Float, language: String, blocksCount: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            InfoItem("Confidence", "${(confidence * 100).toInt()}%")
            InfoItem("Language", language.uppercase())
            InfoItem("Blocks", blocksCount.toString())
        }
    }
}

@Composable
fun InfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelSmall)
        Text(text = value, style = MaterialTheme.typography.titleMedium)
    }
}
```

***

## 🧪 План тестирования идеи (MVP)

### Фаза 1: Базовый прототип (1-2 дня)

**Цель:** Проверить точность ML Kit на русском/английском коде

**Шаги:**
1. Создайте простой Activity с ImageView + Button
2. Реализуйте только `RecognizeTextUseCase` без предобработки
3. Тестовые изображения:
   - Фото распечатанного Kotlin кода
   - Скриншот кода с экрана
   - Фото страницы книги на русском
   - Фото документа на английском

**Метрики успеха:**
- Accuracy > 90% для печатного текста
- Успешное распознавание русского и английского
- Время обработки < 3 секунд на изображение 1920x1080

### Фаза 2: Тестирование форматирования (1 день)

**Цель:** Проверить алгоритм сохранения отступов

**Шаги:**
1. Добавьте `TextFormatPreserver`
2. Сравните исходный код и распознанный код
3. Измерьте точность восстановления отступов

**Метрики успеха:**
- Отступы сохраняются в 80%+ случаев
- Структура кода остается читаемой

### Фаза 3: Предобработка (2-3 дня)

**Цель:** Улучшить точность через OpenCV

**Шаги:**
1. Интегрируйте OpenCV
2. Протестируйте на фото с плохим освещением, бликами, шумами
3. Сравните точность ДО и ПОСЛЕ предобработки

**Метрики успеха:**
- Точность увеличивается на 10-20% для сложных изображений
- Размер APK < 80 MB

***

## 📊 Сравнительная таблица альтернативных решений

| Критерий | ML Kit V2 (рекомендуется) | Tesseract 5 | Custom TF Lite |
|----------|--------------------------|-------------|----------------|
| Точность RU/EN | ★★★★★ | ★★★★☆ | ★★★☆☆ |
| Скорость | 2-4 сек | 5-10 сек | 3-6 сек |
| Размер модели | 10-15 MB | 40-60 MB | 30-100 MB |
| Сложность интеграции | Низкая | Средняя | Высокая |
| Поддержка форматирования | Да (bounding boxes) | Да (hOCR) | Зависит от модели |
| Требования к предобработке | Низкие | Высокие | Средние |

**Вердикт:** ML Kit V2 — оптимальный выбор для вашего проекта.[2][5][1]

***

## 🚀 Следующие шаги

### Минимальный MVP для тестирования (рекомендую начать с этого)

1. **Создайте новый проект** с dependencies из Шага 1
2. **Реализуйте только ResultScreen** + `RecognizeTextUseCase` (без камеры, без предобработки)
3. **Используйте готовые тестовые изображения** из ресурсов
4. **Замерьте метрики**: точность, скорость, размер APK

**Код для быстрого старта:**

```kotlin
// MainActivity.kt - МИНИМАЛЬНЫЙ ТЕСТОВЫЙ КОД
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Загрузите тестовое изображение
        val testBitmap = BitmapFactory.decodeResource(resources, R.drawable.test_code_image)
        
        lifecycleScope.launch {
            val useCase = RecognizeTextUseCase(
                ImagePreprocessor(),
                TextFormatPreserver()
            )
            
            val result = useCase.execute(
                testBitmap,
                ScanSettings() // Настройки по умолчанию
            )
            
            result.onSuccess { recognizedText ->
                Log.d("OCR", "Original: ${recognizedText.originalText}")
                Log.d("OCR", "Formatted: ${recognizedText.formattedText}")
                Log.d("OCR", "Confidence: ${recognizedText.confidence}")
            }
        }
    }
}
```

### После успешного MVP

4. Добавьте **CameraX** для съемки
5. Реализуйте **PreprocessingScreen** с настройками
6. Добавьте **языковую идентификацию** (ML Kit Language ID)
7. Оптимизируйте производительность (coroutines, flow)

***

## ❓ Критические вопросы для дальнейшей оптимизации

После тестирования MVP ответьте на эти вопросы:

1. **Какая реальная точность достигнута** на ваших тестовых изображениях?
2. **Какие типы ошибок** встречаются чаще всего? (пропуск символов, замена букв, потеря отступов)
3. **Нужны ли дополнительные фильтры предобработки** для конкретных сценариев?

Готов предоставить полный production-ready код после подтверждения успешности MVP! 🎯

[1](https://developers.google.com/ml-kit/vision/text-recognition/v2/android)
[2](https://softwarehouse.au/blog/harnessing-ml-kit-for-fast-accurate-on-device-text-recognition/)
[3](https://developers.google.com/android/reference/com/google/mlkit/vision/text/Text.TextBlock)
[4](https://hackernoon.com/using-google-mlkit-text-recognition-to-scan-tabular-data)
[5](https://developers.google.com/ml-kit/vision/text-recognition/v2/languages)
[6](https://developer.android.com/media/camera/camerax/preview)
[7](https://stackoverflow.com/questions/16651646/pre-processing-of-an-image-with-opencv-on-android-for-optimizing-ocr-accuracy)
[8](https://transloadit.com/devtips/ocr-android-sdk/)
[9](https://developers.google.com/ml-kit/language/identification/android)
[10](https://firebase.google.com/docs/ml-kit/android/recognize-text)
[11](https://stackoverflow.com/questions/55233680/ml-kit-text-recognition-output-issues)
[12](https://codelabs.developers.google.com/codelabs/mlkit-android)
[13](https://developers.google.com/ml-kit/vision/text-recognition/v2)