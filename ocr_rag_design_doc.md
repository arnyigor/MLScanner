# High-Performance Local OCR + RAG System
## Comprehensive Design Document

**Project Name:** Smart Document Scanner with Local Intelligence  
**Status:** Design Phase (MVP)  
**Target Platform:** Android (API 29+), Kotlin Multiplatform (future iOS)  
**Architecture Type:** Hybrid Neural Pipeline (OpenCV + ONNX OCR + SLM + VectorDB)  
**Date:** December 2025

---

## Table of Contents
1. [Executive Summary](#executive-summary)
2. [System Architecture](#system-architecture)
3. [Technical Stack](#technical-stack)
4. [Core Modules](#core-modules)
5. [Data Flow & Pipelines](#data-flow--pipelines)
6. [Performance Constraints](#performance-constraints)
7. [Development Roadmap](#development-roadmap)
8. [Deployment & Testing Strategy](#deployment--testing-strategy)

---

## Executive Summary

### Problem Statement
Current OCR solutions on Android are either:
- **Closed (ML Kit):** Privacy concerns, cloud dependency, limited to simple text
- **Outdated (Tesseract):** Poor accuracy on structured documents, tables, skewed angles
- **Heavy (Vision LLMs):** Too slow for real-time use on mid-range devices (5-30 sec/page)

### Solution Overview
A **Hybrid Neural Pipeline** that combines:
1. **Specialized Detection Model** (PaddleOCR v4) → Fast, accurate text/table detection
2. **Post-processing SLM** (Qwen-2.5-1.5B) → Contextual correction & JSON structuring
3. **Local RAG** (Vector embeddings + Vector DB) → Context-aware retrieval
4. **Preprocessing Pipeline** (OpenCV) → Document normalization & perspective correction

### Target Outcome
- **Quality:** VLM-level accuracy (GPT-4o Vision equivalent) on documents
- **Speed:** <2 seconds per A4 page on Snapdragon 720G or better
- **Hardware:** Works on mid-range Android devices (2023-2024 flagship, 6GB+ RAM)
- **Privacy:** 100% local processing (no cloud, no data leaks)

### Success Metrics (KPI)
| Metric | Target | Threshold |
|--------|--------|-----------|
| Text recognition accuracy (CER) | <3% | <5% |
| Table parsing success rate | >90% | >80% |
| Processing time (A4 page) | <2s | <3s |
| Memory footprint | <500MB | <800MB |
| Device compatibility | Snapdragon 720G+ | Snapdragon 662+ |

---

## System Architecture

### High-Level Component Diagram

```
┌─────────────────────────────────────────────────────────────┐
│                     Android Application Layer               │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Compose UI (Camera, Gallery, Results, History)     │   │
│  └────────────────────┬─────────────────────────────────┘   │
│                       │                                      │
├───────────────────────┼──────────────────────────────────────┤
│              Business Logic & Orchestration                  │
│  ┌──────────────┬──────────────┬──────────────┐              │
│  │ PipelineOrch │ DocumentMgr  │ RAGController│              │
│  └──────────────┴──────────────┴──────────────┘              │
│                       │                                      │
├───────────────────────┼──────────────────────────────────────┤
│           Processing Modules (Core Intelligence)            │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │   OpenCV     │  │   PaddleOCR  │  │    SLM       │       │
│  │ Preproc      │  │   (ONNX)     │  │  (llama.cpp) │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
│                                                              │
│  ┌──────────────────────────────────────────────────┐       │
│  │  Embeddings Model (ONNX) → Vector Search         │       │
│  └──────────────────────────────────────────────────┘       │
├────────────────────────────────────────────────────────────┤
│              Storage & Persistence Layer                    │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐       │
│  │    Room DB   │  │  ObjectBox   │  │   FileSystem │       │
│  │  (Metadata)  │  │  (Vectors)   │  │  (Images)    │       │
│  └──────────────┘  └──────────────┘  └──────────────┘       │
└─────────────────────────────────────────────────────────────┘
```

### Module Dependencies Graph

```
CameraCapture
    ↓
OpenCV Pipeline (Detect → Crop → Warp → Binarize)
    ↓
PaddleOCR (Detection + Recognition)
    ↓
SLM Post-processor (Correction + Structuring)
    ↓
Metadata Extractor (Type classification)
    ↓ (parallel)
├─→ Embeddings Generator
│       ↓
│   ObjectBox Vector DB
│       ↓
└─→ Room Database (History)
    ↓
RAG Query Controller
    ↓
Response to UI
```

---

## Technical Stack

### Core Dependencies

| Component | Technology | Version | Rationale |
|-----------|-----------|---------|-----------|
| **Language** | Kotlin + KMP | 1.9.21+ | Type-safe, multiplatform readiness |
| **UI Framework** | Jetpack Compose | 1.6+ | Modern, declarative, native performance |
| **Camera** | CameraX | 1.3+ | Hardware-accelerated, viewfinder streaming |
| **CV Library** | OpenCV Android SDK | 4.8+ | Local processing, edge detection, perspective warp |
| **ONNX Runtime** | onnxruntime-android | 1.17+ | Cross-platform model inference, NNAPI support |
| **LLM Inference** | llama.cpp (Android build) | latest | Optimal quantization, streaming tokens |
| **Vector DB** | ObjectBox VectorSearch | 4.0+ (beta) | Embedded, single-process, fast indexing |
| **Structured Data** | Room + SQLDelight | Room 2.6+ | Query flexibility, reactive streams |
| **Async Runtime** | Coroutines + Flow | 1.7+ | Structured concurrency, backpressure handling |
| **Serialization** | kotlinx.serialization | 1.6.10 (specific) | Zero-reflection, platform-agnostic |
| **Logging** | Timber + Napier (KMP) | latest | Debug + production logging |
| **Testing** | Kotest + Mockk | 5.7+ | TDD-friendly, DSL for assertions |

### AI/ML Model Dependencies

| Model | Format | Size (quantized) | Purpose | Source |
|-------|--------|-----------------|---------|--------|
| **PaddleOCR v4 Det** | ONNX | 2.3 MB | Text region detection | Export from paddlepaddle/paddle-lite |
| **PaddleOCR v4 Rec** | ONNX | 8.1 MB | Character recognition (ch) | Same |
| **PaddleOCR v4 Structure** | ONNX | 3.4 MB | Table/layout parsing | Optional, for advanced docs |
| **Qwen-2.5-1.5B** | GGUF (Q4_K_M) | 1.2 GB | Post-OCR correction & JSON | HuggingFace (quantized) |
| **BGE-Small-EN-v1.5** | ONNX | 27 MB | Text embeddings (multilingual) | Export from BAAI |
| **RuBERT-tiny2** | ONNX | 24 MB | Russian text embeddings (optional) | DeepPavlov |

### Hardware Requirements & Optimization Targets

| Device Tier | RAM | Snapdragon | GPU/NPU | Expected Performance |
|------------|-----|-----------|---------|---------------------|
| **Budget** | 6GB | 662/685 | Adreno 610 | 3-4 sec/page |
| **Mid-range** | 8GB | 720G/778G+ | Adreno 619/642 | 1.5-2 sec/page |
| **Flagship** | 12GB+ | 8cx Gen 3 / 895 | Adreno 8cx / 710 | <1 sec/page |

---

## Core Modules

### 1. Preprocessing Pipeline (OpenCV)

**Location:** `domain/ocr/preprocessing/`  
**Responsibilities:** Image normalization, document detection, perspective correction

#### 1.1 Document Detector
```kotlin
// domain/ocr/preprocessing/DocumentDetector.kt

interface DocumentDetector {
    suspend fun detectDocument(bitmap: Bitmap): DetectionResult
}

data class DetectionResult(
    val isDocumentPresent: Boolean,
    val confidence: Float,
    val corners: List<Point>,  // 4 corners for perspective transform
    val boundingBox: Rect
)

// Implementation: Uses Canny edge detection + contour analysis
// Finds largest rectangle with 4 corners
// Returns perspective-corrected bitmap
```

**Algorithm:**
1. Convert color → Grayscale
2. Apply Gaussian Blur (kernel: 5x5)
3. Canny Edge Detection (threshold: 100-200)
4. Find contours, filter by area
5. Approximate contour to 4-point polygon
6. `getPerspectiveTransform()` + `warpPerspective()`
7. Output: 1600x2000 (A4 equivalent at ~150dpi)

#### 1.2 Binarization Module
```kotlin
// domain/ocr/preprocessing/ImageBinarizer.kt

interface ImageBinarizer {
    suspend fun binarize(bitmap: Bitmap, method: BinarizationMethod): Bitmap
}

enum class BinarizationMethod {
    ADAPTIVE_GAUSSIAN,  // For mixed lighting
    OTSU,              // For uniform documents
    NONE               // For color preservation (receipts, etc.)
}
```

**Why binarization?**
- PaddleOCR v4 handles both color and B/W, but B/W accelerates inference
- Adaptive binarization (per-tile) handles shadows/wrinkles on paper

#### 1.3 Denoising Module
```kotlin
// domain/ocr/preprocessing/ImageDenoiser.kt

interface ImageDenoiser {
    suspend fun denoise(
        bitmap: Bitmap, 
        strength: DenoiseStrength = DenoiseStrength.MEDIUM
    ): Bitmap
}

enum class DenoiseStrength {
    LIGHT,      // morphology only
    MEDIUM,     // morphology + slight blur
    HEAVY       // bilateral filter (slow!)
}
```

**Algorithm:** Morphological opening (erosion → dilation) to remove small noise artifacts.

#### 1.4 Preprocessing Pipeline Orchestrator
```kotlin
// domain/ocr/preprocessing/PreprocessingPipeline.kt

class PreprocessingPipeline(
    private val documentDetector: DocumentDetector,
    private val binarizer: ImageBinarizer,
    private val denoiser: ImageDenoiser,
    private val logger: Logger
) {
    suspend fun processImage(
        rawBitmap: Bitmap,
        config: PreprocessingConfig
    ): ProcessedImage {
        // Stage 1: Detect document
        val detection = documentDetector.detectDocument(rawBitmap)
        if (!detection.isDocumentPresent) {
            throw DocumentNotDetectedException()
        }
        
        // Stage 2: Apply perspective warp
        val warped = perspectiveWarp(rawBitmap, detection.corners)
        
        // Stage 3: Denoise
        val denoised = denoiser.denoise(
            warped, 
            strength = if (config.noisy) DenoiseStrength.MEDIUM else DenoiseStrength.LIGHT
        )
        
        // Stage 4: Binarize (if document type matches)
        val final = if (config.isBWOptimal) {
            binarizer.binarize(denoised, BinarizationMethod.ADAPTIVE_GAUSSIAN)
        } else {
            denoised
        }
        
        logger.debug("Preprocessing complete: ${final.width}x${final.height}")
        return ProcessedImage(
            bitmap = final,
            originalCorners = detection.corners,
            processingTime = measureTimeMillis { /* ... */ }
        )
    }
}

data class PreprocessingConfig(
    val noisy: Boolean = false,
    val isBWOptimal: Boolean = true,
    val targetWidth: Int = 1600,
    val targetHeight: Int = 2000
)

data class ProcessedImage(
    val bitmap: Bitmap,
    val originalCorners: List<Point>,
    val processingTime: Long
)
```

**Performance Target:** OpenCV preprocessing should complete in **200-400ms** on mid-range device.

---

### 2. PaddleOCR Integration (ONNX Runtime)

**Location:** `domain/ocr/paddle/`  
**Responsibilities:** Text detection and recognition

#### 2.1 PaddleOCR Model Manager
```kotlin
// domain/ocr/paddle/PaddleOCRModelManager.kt

interface PaddleOCRModelManager {
    suspend fun loadModels()
    suspend fun unloadModels()
    fun isLoaded(): Boolean
    fun getDetectionModel(): OrtSession
    fun getRecognitionModel(): OrtSession
    fun getCharacterMap(): List<String>
}

class PaddleOCRModelManagerImpl(
    private val context: Context,
    private val ortEnv: OrtEnvironment,
    private val logger: Logger
) : PaddleOCRModelManager {
    
    private var detectionSession: OrtSession? = null
    private var recognitionSession: OrtSession? = null
    private lateinit var charMap: List<String>
    
    override suspend fun loadModels() = withContext(Dispatchers.Default) {
        logger.info("Loading PaddleOCR models from assets...")
        
        // Model paths (packed in APK)
        val detModelPath = copyAssetToCache("models/paddle_det.onnx")
        val recModelPath = copyAssetToCache("models/paddle_rec.onnx")
        val charMapPath = copyAssetToCache("models/ppocr_keys_v1.txt")
        
        // Create ONNX sessions with mobile optimization
        val sessionOptions = OrtSessionOptions().apply {
            addNNApiExecutionProvider()  // NPU/GPU acceleration
            addCoreMLExecutionProvider() // For iOS future
            numIntraOpThreads = Runtime.getRuntime().availableProcessors() - 1
            numInterOpThreads = 2
            graphOptimizationLevel = OrtGraphOptimizationLevel.ORT_ENABLE_ALL
        }
        
        detectionSession = OrtSession(ortEnv, detModelPath, sessionOptions)
        recognitionSession = OrtSession(ortEnv, recModelPath, sessionOptions)
        charMap = loadCharacterMap(charMapPath)
        
        logger.info("Models loaded. Detection: ${detectionSession?.inputNames?.size} inputs")
    }
    
    override suspend fun unloadModels() {
        detectionSession?.close()
        recognitionSession?.close()
    }
    
    override fun isLoaded() = detectionSession != null && recognitionSession != null
    
    override fun getDetectionModel() = detectionSession ?: throw NotLoadedException()
    override fun getRecognitionModel() = recognitionSession ?: throw NotLoadedException()
    override fun getCharacterMap() = charMap
    
    private fun copyAssetToCache(assetPath: String): String {
        val file = File(context.cacheDir, assetPath.substringAfterLast("/"))
        if (!file.exists()) {
            context.assets.open(assetPath).use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return file.absolutePath
    }
    
    private fun loadCharacterMap(path: String): List<String> {
        return File(path).readLines().mapIndexed { idx, line ->
            if (idx == 0) "" else line.trim()
        }
    }
}
```

**Key Optimization Points:**
- `addNNApiExecutionProvider()`: Use device NPU if available (Snapdragon 865+)
- `numIntraOpThreads`: Set to CPU count - 1 (leave 1 core for UI)
- `graphOptimizationLevel`: Maximum optimization for inference

#### 2.2 Detection Pipeline (DBNet)
```kotlin
// domain/ocr/paddle/TextDetector.kt

data class TextRegion(
    val box: List<Point>,        // 4 or more corners (quadrilateral)
    val confidence: Float,        // 0.0-1.0
    val boundingBox: Rect,       // Axis-aligned rect
    val angleInDegrees: Float    // Rotation angle
)

data class DetectionResult(
    val regions: List<TextRegion>,
    val inferenceTime: Long,
    val inputSize: Pair<Int, Int>
)

interface TextDetector {
    suspend fun detect(bitmap: Bitmap): DetectionResult
}

class PaddleTextDetectorImpl(
    private val modelManager: PaddleOCRModelManager,
    private val logger: Logger
) : TextDetector {
    
    override suspend fun detect(bitmap: Bitmap): DetectionResult = 
        withContext(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            
            // Prepare input tensor
            val input = preprocessImage(bitmap)  // Resize to 320x320 or 640x640
            val inputShape = input.shape
            
            // Run inference
            val detModel = modelManager.getDetectionModel()
            val inputs = mapOf("x" to input)
            val outputs = detModel.run(inputs)
            
            // Parse output
            val regionsTensor = outputs["save_infer_model/scale_0"]?.value as FloatArray
            val regions = parseDetectionOutput(
                regionsTensor,
                inputShape,
                bitmap.width,
                bitmap.height
            )
            
            val inferenceTime = System.currentTimeMillis() - startTime
            logger.debug("Detection: ${regions.size} regions in ${inferenceTime}ms")
            
            DetectionResult(
                regions = regions,
                inferenceTime = inferenceTime,
                inputSize = inputShape.takeLast(2).let { it[0] to it[1] }
            )
        }
    
    private fun preprocessImage(bitmap: Bitmap): OnnxTensor {
        // Resize preserving aspect ratio (pad to square)
        val size = 320  // or 640 for high-accuracy
        val resized = Bitmap.createScaledBitmap(bitmap, size, size, true)
        
        // Convert to float array with normalization
        val pixels = IntArray(size * size)
        resized.getPixels(pixels, 0, size, 0, 0, size, size)
        
        val data = FloatArray(size * size * 3)
        for (i in pixels.indices) {
            val pixel = pixels[i]
            // Normalize: (value / 255.0) - mean / std
            data[i] = ((pixel shr 16) and 0xFF) / 255f - 0.485f / 0.229f
            data[size * size + i] = ((pixel shr 8) and 0xFF) / 255f - 0.456f / 0.224f
            data[2 * size * size + i] = (pixel and 0xFF) / 255f - 0.406f / 0.225f
        }
        
        return OnnxTensor.createTensor(ortEnv, data, longArrayOf(1L, 3L, size.toLong(), size.toLong()))
    }
    
    private fun parseDetectionOutput(
        output: FloatArray,
        shape: LongArray,
        imageWidth: Int,
        imageHeight: Int
    ): List<TextRegion> {
        // Parse bitmap output from DBNet model
        // Returns polygons with confidence > threshold (0.5)
        
        val regions = mutableListOf<TextRegion>()
        val threshold = 0.5f
        val inputSize = 320  // Must match preprocessImage
        
        // Output shape: (1, H, W, 1) - confidence map
        val height = shape[1].toInt()
        val width = shape[2].toInt()
        
        // Simplified: use contour detection on confidence map
        // In production: use proper polygon extraction
        
        // Scale coordinates back to original image
        val scaleX = imageWidth.toFloat() / inputSize
        val scaleY = imageHeight.toFloat() / inputSize
        
        // ... polygon extraction logic ...
        
        return regions
    }
}
```

#### 2.3 Recognition Pipeline (CRNN)
```kotlin
// domain/ocr/paddle/TextRecognizer.kt

data class RecognizedText(
    val text: String,
    val confidence: Float,
    val boundingBox: Rect
)

interface TextRecognizer {
    suspend fun recognize(
        bitmap: Bitmap,
        regions: List<TextRegion>
    ): List<RecognizedText>
}

class PaddleTextRecognizerImpl(
    private val modelManager: PaddleOCRModelManager,
    private val logger: Logger
) : TextRecognizer {
    
    override suspend fun recognize(
        bitmap: Bitmap,
        regions: List<TextRegion>
    ): List<RecognizedText> = withContext(Dispatchers.Default) {
        
        val results = mutableListOf<RecognizedText>()
        val charMap = modelManager.getCharacterMap()
        val recModel = modelManager.getRecognitionModel()
        
        regions.forEach { region ->
            try {
                // Crop region from original image
                val croppedBitmap = cropAndRotate(bitmap, region)
                
                // Prepare input tensor (height=32, width=variable, max 320)
                val input = preprocessRecImage(croppedBitmap)
                
                // Run inference
                val inputs = mapOf("x" to input)
                val outputs = recModel.run(inputs)
                
                // Parse output
                val logits = outputs["softmax_0.tmp_0"]?.value as FloatArray
                val text = decodeLogits(logits, charMap)
                val confidence = calculateConfidence(logits, charMap)
                
                results.add(
                    RecognizedText(
                        text = text,
                        confidence = confidence,
                        boundingBox = region.boundingBox
                    )
                )
            } catch (e: Exception) {
                logger.warn("Recognition failed for region: ${e.message}")
            }
        }
        
        logger.debug("Recognized ${results.size} text regions")
        return@withContext results
    }
    
    private fun cropAndRotate(bitmap: Bitmap, region: TextRegion): Bitmap {
        // Extract perspective-correct crop using region corners
        // If angle > 5 degrees, rotate to horizontal
        return bitmap  // Simplified
    }
    
    private fun preprocessRecImage(bitmap: Bitmap): OnnxTensor {
        // Resize to (32, 320) maintaining aspect ratio
        val height = 32
        val maxWidth = 320
        val ratio = bitmap.width.toFloat() / bitmap.height
        val width = (height * ratio).toInt().coerceAtMost(maxWidth)
        
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val padded = padImage(resized, height, maxWidth)
        
        // Normalize
        val data = FloatArray(height * maxWidth * 3)
        // ... convert to normalized float array ...
        
        return OnnxTensor.createTensor(
            ortEnv,
            data,
            longArrayOf(1L, height.toLong(), maxWidth.toLong(), 3L)
        )
    }
    
    private fun decodeLogits(logits: FloatArray, charMap: List<String>): String {
        // CTC decoding: greedily select max logit per timestep
        // Remove blank tokens and consecutive duplicates
        val decoded = StringBuilder()
        val blankIdx = charMap.size
        
        // logits shape: (seqLen, numClasses)
        // ... CTC decoding logic ...
        
        return decoded.toString()
    }
    
    private fun calculateConfidence(logits: FloatArray, charMap: List<String>): Float {
        // Average softmax probability across sequence
        return 0.85f  // Placeholder
    }
    
    private fun padImage(bitmap: Bitmap, height: Int, maxWidth: Int): Bitmap {
        if (bitmap.width >= maxWidth) return bitmap
        val result = Bitmap.createBitmap(maxWidth, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result).apply { drawColor(Color.WHITE) }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return result
    }
}
```

**Performance Target:** OCR (detection + recognition) should complete in **400-800ms** on mid-range device.

---

### 3. SLM Post-Processor (llama.cpp)

**Location:** `domain/llm/`  
**Responsibilities:** OCR text correction, JSON structuring, semantic understanding

#### 3.1 LLM Inference Engine
```kotlin
// domain/llm/LLMInferenceEngine.kt

interface LLMInferenceEngine {
    suspend fun initialize()
    suspend fun shutdown()
    suspend fun generate(
        prompt: String,
        params: GenerationParams = GenerationParams()
    ): String
    suspend fun streamGenerate(
        prompt: String,
        params: GenerationParams = GenerationParams(),
        onToken: suspend (String) -> Unit
    )
}

data class GenerationParams(
    val maxTokens: Int = 200,
    val temperature: Float = 0.3f,     // Low = deterministic
    val topP: Float = 0.9f,            // Nucleus sampling
    val topK: Int = 40,
    val repeatPenalty: Float = 1.1f,
    val numBatch: Int = 512
)

class LlamaCppInferenceEngine(
    private val context: Context,
    private val modelPath: String,
    private val gpuLayers: Int = 35,   // Adjust for device
    private val logger: Logger
) : LLMInferenceEngine {
    
    private var llamaHandle: Long = 0L
    private var isInitialized = false
    
    override suspend fun initialize() = withContext(Dispatchers.Default) {
        if (isInitialized) return@withContext
        
        logger.info("Initializing llama.cpp with model: $modelPath")
        
        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            throw ModelNotFoundException("Model not found at: $modelPath")
        }
        
        // Call JNI binding
        llamaHandle = nativeInitialize(
            modelPath = modelPath,
            nGpuLayers = gpuLayers,
            nThreads = Runtime.getRuntime().availableProcessors() - 1,
            nBatch = 512
        )
        
        isInitialized = true
        logger.info("llama.cpp initialized, handle=$llamaHandle")
    }
    
    override suspend fun shutdown() = withContext(Dispatchers.Default) {
        if (isInitialized && llamaHandle > 0) {
            nativeShutdown(llamaHandle)
            isInitialized = false
            logger.info("llama.cpp shut down")
        }
    }
    
    override suspend fun generate(
        prompt: String,
        params: GenerationParams
    ): String = withContext(Dispatchers.Default) {
        if (!isInitialized) throw NotInitializedException()
        
        return@withContext nativeGenerate(
            handle = llamaHandle,
            prompt = prompt,
            maxTokens = params.maxTokens,
            temperature = params.temperature,
            topP = params.topP,
            topK = params.topK,
            repeatPenalty = params.repeatPenalty
        )
    }
    
    override suspend fun streamGenerate(
        prompt: String,
        params: GenerationParams,
        onToken: suspend (String) -> Unit
    ) = withContext(Dispatchers.Default) {
        if (!isInitialized) throw NotInitializedException()
        
        nativeGenerateStreaming(
            handle = llamaHandle,
            prompt = prompt,
            maxTokens = params.maxTokens,
            temperature = params.temperature,
            topP = params.topP,
            topK = params.topK,
            repeatPenalty = params.repeatPenalty,
            tokenCallback = { token ->
                runBlocking { onToken(token) }
            }
        )
    }
    
    // JNI declarations (implemented in native code)
    private external fun nativeInitialize(
        modelPath: String,
        nGpuLayers: Int,
        nThreads: Int,
        nBatch: Int
    ): Long
    
    private external fun nativeShutdown(handle: Long)
    
    private external fun nativeGenerate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float
    ): String
    
    private external fun nativeGenerateStreaming(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        temperature: Float,
        topP: Float,
        topK: Int,
        repeatPenalty: Float,
        tokenCallback: (String) -> Unit
    )
}

// Native library loading
init {
    try {
        System.loadLibrary("llama")
        System.loadLibrary("llamajni")
    } catch (e: UnsatisfiedLinkError) {
        Timber.e(e, "Failed to load llama JNI library")
    }
}
```

#### 3.2 OCR Corrector
```kotlin
// domain/llm/OCRCorrector.kt

data class CorrectionRequest(
    val rawOcrText: String,
    val documentType: DocumentType,
    val confidence: Float
)

data class CorrectionResult(
    val correctedText: String,
    val corrections: List<Correction>,
    val inferenceTime: Long
)

data class Correction(
    val original: String,
    val corrected: String,
    val position: IntRange,
    val reason: String
)

interface OCRCorrector {
    suspend fun correctOCR(request: CorrectionRequest): CorrectionResult
}

class LLMOCRCorrectorImpl(
    private val llmEngine: LLMInferenceEngine,
    private val logger: Logger
) : OCRCorrector {
    
    override suspend fun correctOCR(request: CorrectionRequest): CorrectionResult {
        val startTime = System.currentTimeMillis()
        
        // Build context-aware prompt
        val prompt = buildCorrectionPrompt(request)
        
        // Generate corrected text
        val correctedText = llmEngine.generate(
            prompt = prompt,
            params = GenerationParams(
                maxTokens = request.rawOcrText.length * 2,
                temperature = 0.1f,  // Deterministic
                topP = 0.9f
            )
        ).trim()
        
        // Calculate corrections (simplified)
        val corrections = generateCorrectionDiff(request.rawOcrText, correctedText)
        
        val result = CorrectionResult(
            correctedText = correctedText,
            corrections = corrections,
            inferenceTime = System.currentTimeMillis() - startTime
        )
        
        logger.debug("OCR correction complete. Corrections: ${corrections.size}, Time: ${result.inferenceTime}ms")
        return result
    }
    
    private fun buildCorrectionPrompt(request: CorrectionRequest): String {
        val typeHint = when (request.documentType) {
            DocumentType.RECEIPT -> "receipt with prices, dates, and totals"
            DocumentType.INVOICE -> "invoice with itemized list and amounts"
            DocumentType.CONTRACT -> "legal document with structured text"
            DocumentType.GENERIC -> "document"
        }
        
        return """
            You are an OCR text correction expert. Correct the following OCR output from a $typeHint.
            
            Rules:
            1. Fix common OCR mistakes (0↔O, 1↔l, etc.)
            2. Preserve document structure (line breaks, indentation)
            3. Keep numbers and dates as they are UNLESS they seem clearly wrong
            4. Return ONLY corrected text, no explanations
            
            Raw OCR:
            ${request.rawOcrText}
            
            Corrected text:
        """.trimIndent()
    }
    
    private fun generateCorrectionDiff(
        original: String,
        corrected: String
    ): List<Correction> {
        // Simple implementation: split by words and compare
        val origWords = original.split(Regex("\\s+"))
        val corrWords = corrected.split(Regex("\\s+"))
        
        val corrections = mutableListOf<Correction>()
        var position = 0
        
        for (i in 0 until minOf(origWords.size, corrWords.size)) {
            if (origWords[i] != corrWords[i]) {
                corrections.add(
                    Correction(
                        original = origWords[i],
                        corrected = corrWords[i],
                        position = position..(position + origWords[i].length),
                        reason = "OCR artifact or typo"
                    )
                )
            }
            position += origWords[i].length + 1
        }
        
        return corrections
    }
}

enum class DocumentType {
    RECEIPT, INVOICE, CONTRACT, GENERIC
}
```

#### 3.3 Structured Output Extractor (JSON)
```kotlin
// domain/llm/StructuredExtractor.kt

interface StructuredExtractor {
    suspend fun extractAsJSON(
        text: String,
        schema: ExtractionSchema
    ): String  // Valid JSON
}

enum class ExtractionSchema {
    RECEIPT, INVOICE, CONTRACT_SUMMARY, GENERIC
}

class LLMStructuredExtractor(
    private val llmEngine: LLMInferenceEngine,
    private val logger: Logger
) : StructuredExtractor {
    
    override suspend fun extractAsJSON(
        text: String,
        schema: ExtractionSchema
    ): String {
        val schemaDescription = getSchemaDescription(schema)
        
        val prompt = """
            Extract structured information from the following document text.
            Return ONLY valid JSON matching this schema:
            
            $schemaDescription
            
            Document text:
            $text
            
            JSON output:
        """.trimIndent()
        
        val jsonOutput = llmEngine.generate(
            prompt = prompt,
            params = GenerationParams(
                maxTokens = 500,
                temperature = 0.0f  // Fully deterministic
            )
        )
        
        // Validate JSON and extract from markdown code blocks if present
        val cleanJson = jsonOutput
            .removePrefix("```json\n")
            .removePrefix("```\n")
            .removeSuffix("\n```")
            .trim()
        
        // Validate
        try {
            Json.parseToJsonElement(cleanJson)
            logger.debug("Extraction successful, JSON validated")
        } catch (e: Exception) {
            logger.warn("Invalid JSON output, attempting recovery: ${e.message}")
            // Fallback: return as wrapped text
            return """{"raw_text": "${text.replace("\"", "\\\"")}"}"""
        }
        
        return cleanJson
    }
    
    private fun getSchemaDescription(schema: ExtractionSchema): String = when (schema) {
        ExtractionSchema.RECEIPT -> """
            {
                "total_amount": number,
                "currency": string,
                "date": string (YYYY-MM-DD),
                "merchant": string,
                "items": [
                    { "name": string, "price": number, "quantity": number }
                ],
                "payment_method": string
            }
        """.trimIndent()
        
        ExtractionSchema.INVOICE -> """
            {
                "invoice_number": string,
                "date": string (YYYY-MM-DD),
                "due_date": string (YYYY-MM-DD),
                "from": { "name": string, "address": string },
                "to": { "name": string, "address": string },
                "items": [
                    { "description": string, "unit_price": number, "quantity": number, "total": number }
                ],
                "subtotal": number,
                "tax": number,
                "total": number
            }
        """.trimIndent()
        
        else -> """{ "text": string, "metadata": object }"""
    }
}

// JSON serialization utility
object Json {
    private val format = Json { ignoreUnknownKeys = true }
    
    fun parseToJsonElement(json: String) = Json.Default.parseToJsonElement(json)
}
```

**Performance Target:** LLM correction should complete in **1-3 seconds** for full page (mid-range device with GPU layers).

---

### 4. RAG System (Vector Database + Retrieval)

**Location:** `domain/rag/`  
**Responsibilities:** Semantic search, context retrieval, conversation memory

#### 4.1 Text Chunking Strategy
```kotlin
// domain/rag/TextChunker.kt

data class TextChunk(
    val id: String,
    val text: String,
    val startPage: Int,
    val startChar: Int,
    val endChar: Int,
    val sourceDocumentId: String,
    val metadata: Map<String, String>
)

interface TextChunker {
    fun chunk(
        text: String,
        sourceDocumentId: String,
        pageNumber: Int,
        strategy: ChunkingStrategy = ChunkingStrategy.SEMANTIC
    ): List<TextChunk>
}

enum class ChunkingStrategy {
    FIXED_SIZE,      // 512 tokens / 50% overlap
    SENTENCE,        // Split by sentences
    SEMANTIC,        // Uses sentence embeddings (TODO: advanced)
    PARAGRAPH        // Split by paragraphs
}

class RecursiveTextChunker(
    private val chunkSize: Int = 512,
    private val overlapSize: Int = 256
) : TextChunker {
    
    override fun chunk(
        text: String,
        sourceDocumentId: String,
        pageNumber: Int,
        strategy: ChunkingStrategy
    ): List<TextChunk> {
        return when (strategy) {
            ChunkingStrategy.FIXED_SIZE -> chunkBySize(text, sourceDocumentId, pageNumber)
            ChunkingStrategy.SENTENCE -> chunkBySentence(text, sourceDocumentId, pageNumber)
            ChunkingStrategy.PARAGRAPH -> chunkByParagraph(text, sourceDocumentId, pageNumber)
            ChunkingStrategy.SEMANTIC -> chunkBySize(text, sourceDocumentId, pageNumber) // Fallback
        }
    }
    
    private fun chunkBySize(
        text: String,
        sourceDocumentId: String,
        pageNumber: Int
    ): List<TextChunk> {
        val chunks = mutableListOf<TextChunk>()
        val words = text.split(Regex("\\s+"))
        
        var startIdx = 0
        while (startIdx < words.size) {
            val endIdx = minOf(startIdx + chunkSize, words.size)
            val chunkText = words.subList(startIdx, endIdx).joinToString(" ")
            
            chunks.add(
                TextChunk(
                    id = UUID.randomUUID().toString(),
                    text = chunkText,
                    startPage = pageNumber,
                    startChar = text.indexOf(words[startIdx]),
                    endChar = if (endIdx > 0) text.indexOf(words[endIdx - 1]) + words[endIdx - 1].length else 0,
                    sourceDocumentId = sourceDocumentId,
                    metadata = mapOf("page" to pageNumber.toString())
                )
            )
            
            startIdx += chunkSize - overlapSize / chunkSize  // Move with overlap
        }
        
        return chunks
    }
    
    private fun chunkBySentence(
        text: String,
        sourceDocumentId: String,
        pageNumber: Int
    ): List<TextChunk> {
        val sentences = text.split(Regex("[.!?]+")).map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<TextChunk>()
        
        var currentChunk = StringBuilder()
        var chunkStartChar = 0
        
        for ((idx, sentence) in sentences.withIndex()) {
            currentChunk.append(sentence).append(". ")
            
            // Create chunk when reaching size limit
            if (currentChunk.length > chunkSize || idx == sentences.size - 1) {
                chunks.add(
                    TextChunk(
                        id = UUID.randomUUID().toString(),
                        text = currentChunk.toString().trim(),
                        startPage = pageNumber,
                        startChar = chunkStartChar,
                        endChar = chunkStartChar + currentChunk.length,
                        sourceDocumentId = sourceDocumentId,
                        metadata = mapOf("page" to pageNumber.toString())
                    )
                )
                chunkStartChar += currentChunk.length
                currentChunk = StringBuilder()
            }
        }
        
        return chunks
    }
    
    private fun chunkByParagraph(
        text: String,
        sourceDocumentId: String,
        pageNumber: Int
    ): List<TextChunk> {
        val paragraphs = text.split(Regex("\n\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        return paragraphs.mapIndexed { idx, para ->
            TextChunk(
                id = UUID.randomUUID().toString(),
                text = para,
                startPage = pageNumber,
                startChar = text.indexOf(para),
                endChar = text.indexOf(para) + para.length,
                sourceDocumentId = sourceDocumentId,
                metadata = mapOf("page" to pageNumber.toString(), "paragraph" to idx.toString())
            )
        }
    }
}
```

#### 4.2 Embeddings Generator
```kotlin
// domain/rag/EmbeddingsGenerator.kt

interface EmbeddingsGenerator {
    suspend fun initialize()
    suspend fun shutdown()
    suspend fun generateEmbedding(text: String): FloatArray
    suspend fun batchGenerateEmbeddings(texts: List<String>): List<FloatArray>
}

class ONNXEmbeddingsGenerator(
    private val context: Context,
    private val modelPath: String,
    private val logger: Logger
) : EmbeddingsGenerator {
    
    private var ortSession: OrtSession? = null
    private val tokenizer: BertTokenizer? = null  // Would integrate HuggingFace tokenizers
    
    override suspend fun initialize() = withContext(Dispatchers.Default) {
        logger.info("Initializing embeddings model from: $modelPath")
        
        val sessionOptions = OrtSessionOptions().apply {
            addNNApiExecutionProvider()
            numIntraOpThreads = Runtime.getRuntime().availableProcessors()
            graphOptimizationLevel = OrtGraphOptimizationLevel.ORT_ENABLE_ALL
        }
        
        ortSession = OrtSession(ortEnv, modelPath, sessionOptions)
        logger.info("Embeddings model initialized")
    }
    
    override suspend fun shutdown() = withContext(Dispatchers.Default) {
        ortSession?.close()
    }
    
    override suspend fun generateEmbedding(text: String): FloatArray =
        withContext(Dispatchers.Default) {
            val session = ortSession ?: throw NotInitializedException()
            
            // Tokenize
            val tokens = tokenizer?.encode(text) ?: emptyList()
            val inputIds = tokens.map { it.toLong() }.toLongArray()
            
            // Prepare tensors
            val inputTensor = OnnxTensor.createTensor(
                ortEnv,
                arrayOf(inputIds),
                longArrayOf(1L, inputIds.size.toLong())
            )
            
            val attentionMask = LongArray(inputIds.size) { 1L }
            val maskTensor = OnnxTensor.createTensor(
                ortEnv,
                arrayOf(attentionMask),
                longArrayOf(1L, attentionMask.size.toLong())
            )
            
            // Run inference
            val inputs = mapOf(
                "input_ids" to inputTensor,
                "attention_mask" to maskTensor
            )
            
            val output = session.run(inputs)
            val embeddings = (output["last_hidden_state"]?.value as Array<*>)[0] as Array<*>
            
            // Mean pooling across sequence
            val embedding = FloatArray(embeddings.size)
            for (i in embeddings.indices) {
                val row = embeddings[i] as FloatArray
                embedding[i] = row.average().toFloat()
            }
            
            return@withContext embedding
        }
    
    override suspend fun batchGenerateEmbeddings(texts: List<String>): List<FloatArray> =
        withContext(Dispatchers.Default) {
            texts.map { generateEmbedding(it) }
        }
}
```

#### 4.3 ObjectBox Vector Search Integration
```kotlin
// domain/rag/VectorSearchRepository.kt

@Entity
data class DocumentVector(
    @Id(assignable = true) val id: Long = 0,
    val textChunkId: String,
    val sourceDocumentId: String,
    val content: String,
    val embedding: FloatArray,
    val pageNumber: Int,
    val createdAt: Long = System.currentTimeMillis(),
    @Transient val metadata: Map<String, String> = emptyMap()
)

interface VectorSearchRepository {
    suspend fun saveVector(vector: DocumentVector)
    suspend fun searchSimilar(queryEmbedding: FloatArray, topK: Int = 5): List<DocumentVector>
    suspend fun searchByDocumentId(documentId: String): List<DocumentVector>
    suspend fun deleteByDocumentId(documentId: String)
}

class ObjectBoxVectorSearchRepository(
    private val boxStore: BoxStore,
    private val logger: Logger
) : VectorSearchRepository {
    
    private val vectorBox = boxStore.boxFor(DocumentVector::class.java)
    
    override suspend fun saveVector(vector: DocumentVector) = withContext(Dispatchers.Default) {
        vectorBox.put(vector)
        logger.debug("Saved vector for chunk: ${vector.textChunkId}")
    }
    
    override suspend fun searchSimilar(
        queryEmbedding: FloatArray,
        topK: Int
    ): List<DocumentVector> = withContext(Dispatchers.Default) {
        // ObjectBox vector search using similarity
        // Approximate: cosine distance via dot product
        
        val allVectors = vectorBox.all
        
        val similarVectors = allVectors
            .map { vector ->
                val similarity = cosineSimilarity(queryEmbedding, vector.embedding)
                vector to similarity
            }
            .sortedByDescending { it.second }
            .take(topK)
            .map { it.first }
        
        logger.debug("Found ${similarVectors.size} similar vectors")
        return@withContext similarVectors
    }
    
    override suspend fun searchByDocumentId(documentId: String): List<DocumentVector> =
        withContext(Dispatchers.Default) {
            vectorBox.query()
                .equal(DocumentVector_.sourceDocumentId, documentId)
                .build()
                .find()
        }
    
    override suspend fun deleteByDocumentId(documentId: String) = withContext(Dispatchers.Default) {
        val vectors = searchByDocumentId(documentId)
        vectorBox.remove(vectors)
        logger.debug("Deleted ${vectors.size} vectors for document: $documentId")
    }
    
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        
        return if (normA > 0 && normB > 0) {
            dotProduct / (sqrt(normA) * sqrt(normB))
        } else {
            0f
        }
    }
}
```

#### 4.4 RAG Query Engine
```kotlin
// domain/rag/RAGQueryEngine.kt

data class QueryResult(
    val answer: String,
    val sources: List<DocumentVector>,
    val confidence: Float,
    val generationTime: Long
)

interface RAGQueryEngine {
    suspend fun query(userQuestion: String): QueryResult
}

class LocalRAGQueryEngine(
    private val embeddingsGenerator: EmbeddingsGenerator,
    private val vectorSearchRepo: VectorSearchRepository,
    private val llmEngine: LLMInferenceEngine,
    private val logger: Logger
) : RAGQueryEngine {
    
    override suspend fun query(userQuestion: String): QueryResult {
        val startTime = System.currentTimeMillis()
        
        // Step 1: Generate embedding for question
        val queryEmbedding = embeddingsGenerator.generateEmbedding(userQuestion)
        
        // Step 2: Retrieve similar documents
        val sources = vectorSearchRepo.searchSimilar(queryEmbedding, topK = 5)
        
        if (sources.isEmpty()) {
            logger.warn("No relevant documents found for query: $userQuestion")
            return QueryResult(
                answer = "Sorry, I couldn't find relevant information in your documents.",
                sources = emptyList(),
                confidence = 0f,
                generationTime = System.currentTimeMillis() - startTime
            )
        }
        
        // Step 3: Build context from retrieved documents
        val context = sources
            .sortedBy { it.pageNumber }
            .joinToString("\n\n") { "Page ${it.pageNumber}:\n${it.content}" }
        
        // Step 4: Generate answer using LLM
        val prompt = """
            Based on the following document excerpts, answer the user's question concisely and accurately.
            
            Context:
            $context
            
            User question: $userQuestion
            
            Answer:
        """.trimIndent()
        
        val answer = llmEngine.generate(
            prompt = prompt,
            params = GenerationParams(
                maxTokens = 200,
                temperature = 0.2f
            )
        ).trim()
        
        val generationTime = System.currentTimeMillis() - startTime
        
        logger.debug("RAG query completed in ${generationTime}ms")
        
        return QueryResult(
            answer = answer,
            sources = sources,
            confidence = calculateConfidence(sources),
            generationTime = generationTime
        )
    }
    
    private fun calculateConfidence(sources: List<DocumentVector>): Float {
        // Average confidence based on retrieval scores
        return if (sources.isNotEmpty()) 0.8f else 0f
    }
}
```

---

### 5. Document Management & History

**Location:** `domain/storage/`  
**Responsibilities:** Persistent storage, document versioning, search

#### 5.1 Document Entity & Repository
```kotlin
// domain/storage/Document.kt

@Entity(tableName = "documents")
data class Document(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    val title: String,
    val documentType: DocumentType,
    val sourceUri: String,  // Original image path or camera capture
    
    val rawOcrText: String,
    val correctedText: String,
    val structuredJson: String?,  // JSON extraction result
    
    val pageCount: Int,
    val processingTimeMs: Long,
    
    val modelVersions: String,  // JSON: { "paddleOCR": "v4", "qwen": "2.5-1.5B" }
    
    val confidence: Float,
    val tags: String,  // Comma-separated
    
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
    
    val isArchived: Boolean = false,
    val isFavorite: Boolean = false
)

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: Document): Long
    
    @Query("SELECT * FROM documents WHERE id = :id")
    suspend fun getById(id: Long): Document?
    
    @Query("SELECT * FROM documents ORDER BY createdAt DESC LIMIT :limit OFFSET :offset")
    suspend fun listAll(limit: Int = 20, offset: Int = 0): List<Document>
    
    @Query("SELECT * FROM documents WHERE isArchived = 0 ORDER BY modifiedAt DESC")
    fun observeActive(): Flow<List<Document>>
    
    @Query("SELECT * FROM documents WHERE title LIKE '%' || :query || '%' OR rawOcrText LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<Document>
    
    @Update
    suspend fun update(document: Document)
    
    @Delete
    suspend fun delete(document: Document)
}

interface DocumentRepository {
    suspend fun saveDocument(document: Document): Long
    suspend fun getDocument(id: Long): Document?
    suspend fun listDocuments(limit: Int = 20, offset: Int = 0): List<Document>
    fun observeDocuments(): Flow<List<Document>>
    suspend fun searchDocuments(query: String): List<Document>
    suspend fun updateDocument(document: Document)
    suspend fun deleteDocument(id: Long)
}

class DocumentRepositoryImpl(
    private val documentDao: DocumentDao,
    private val vectorSearchRepo: VectorSearchRepository,
    private val logger: Logger
) : DocumentRepository {
    
    override suspend fun saveDocument(document: Document): Long {
        val id = documentDao.insert(document)
        logger.debug("Document saved with ID: $id")
        return id
    }
    
    override suspend fun deleteDocument(id: Long) {
        val document = documentDao.getById(id)
        if (document != null) {
            documentDao.delete(document)
            vectorSearchRepo.deleteByDocumentId(document.sourceUri)
            logger.debug("Document deleted: $id")
        }
    }
    
    // Other methods...
}
```

---

## Data Flow & Pipelines

### End-to-End Processing Pipeline

```
1. Image Capture / Selection
   ↓
2. Preprocessing (OpenCV)
   - Detect document
   - Perspective warp
   - Binarization
   - Denoising
   ↓
3. PaddleOCR Inference
   - Detection phase (DBNet) → Text regions
   - Recognition phase (CRNN) → Character sequences
   ↓
4. SLM Post-Processing
   - OCR correction (error fixing)
   - Type classification (Receipt/Invoice/etc)
   - JSON extraction
   ↓
5. RAG Indexing
   - Text chunking
   - Embedding generation
   - Vector DB insertion
   ↓
6. Persistence
   - Room DB storage
   - File system (original image)
   - ObjectBox (vectors)
   ↓
7. User Interface
   - Display corrected text
   - Show structured data
   - Enable semantic search
```

### Processing Pipeline State Machine

```
[IDLE]
  ↓ (user selects image)
[PREPROCESSING] → (optional) [INVALID_DOCUMENT] → [ERROR]
  ↓
[OCR_DETECTION] → [NO_TEXT] → [ERROR]
  ↓
[OCR_RECOGNITION]
  ↓
[SLM_CORRECTION]
  ↓
[STRUCTURING]
  ↓
[INDEXING]
  ↓
[SUCCESS] → [READY_FOR_QUERY]
```

---

## Performance Constraints

### Memory Budget
- **Device RAM:** 6GB (budget) to 12GB (flagship)
- **Model Weights:** ~1.5GB (Qwen GGUF)
- **ONNX Runtime Cache:** ~200MB
- **Safe Operating Zone:** <600MB for app runtime

### Thermal Constraints
- **Continuous Inference:** Max 5 pages/minute (to avoid thermal throttling)
- **GPU Utilization:** Keep NPU/GPU at <70% for 10+ minutes
- **Throttle Detection:** Monitor SoC temp, reduce layers if >45°C

### Time Budget (Per Page)
| Stage | Target | Budget (mid-range) |
|-------|--------|------------------|
| Preprocessing | 200-400ms | 500ms |
| OCR Detection | 200-300ms | 400ms |
| OCR Recognition | 100-200ms | 300ms |
| SLM Correction | 1000-3000ms | 5000ms |
| Embedding + Indexing | 100-200ms | 300ms |
| **Total** | **<2 sec** | **<6 sec** |

### Model Quantization Strategy
- **PaddleOCR:** ONNX int8 quantization (or float32 for accuracy)
- **Qwen-2.5-1.5B:** GGUF Q4_K_M (4-bit quantization, ~1.2GB)
- **Embeddings:** ONNX float32 (small model, ~27MB)

---

## Development Roadmap

### Phase 1: Foundation (Week 1-2)
- [ ] Setup project structure (KMP scaffold)
- [ ] Integrate OpenCV (preprocessing module)
- [ ] Test document detection on sample images
- [ ] **Deliverable:** Working document cropping/warping

### Phase 2: OCR Core (Week 3-4)
- [ ] Export PaddleOCR models to ONNX
- [ ] Integrate ONNX Runtime Android
- [ ] Implement text detection (DBNet)
- [ ] Implement text recognition (CRNN)
- [ ] **Deliverable:** End-to-end OCR working on test images

### Phase 3: SLM Integration (Week 5-6)
- [ ] Build llama.cpp Android JNI bindings
- [ ] Integrate Qwen-2.5-1.5B GGUF
- [ ] Implement OCR correction prompt
- [ ] Implement structured JSON extraction
- [ ] **Deliverable:** Corrected text + JSON output

### Phase 4: RAG System (Week 7-8)
- [ ] Integrate embeddings model (ONNX)
- [ ] Setup ObjectBox vector search
- [ ] Implement text chunking strategies
- [ ] Build RAG query engine
- [ ] **Deliverable:** Semantic search over documents

### Phase 5: UI & Polish (Week 9-10)
- [ ] Build Compose UI (camera, gallery, results)
- [ ] Integrate history view
- [ ] Build search interface
- [ ] Performance profiling & optimization
- [ ] **Deliverable:** Polished MVP

### Phase 6: Testing & Deployment (Week 11-12)
- [ ] Unit tests (core logic)
- [ ] Integration tests (pipeline)
- [ ] Device testing (Snapdragon 720G equivalent)
- [ ] Benchmark suite
- [ ] GitHub release + documentation
- [ ] **Deliverable:** Production-ready v1.0

---

## Deployment & Testing Strategy

### Testing Pyramid
```
                   ▲
                  /│\
                 / │ \       E2E Tests (10%)
                /  │  \      - Full pipeline
               /   │   \     - Real images
              / E2E│    \    - Various devices
             ├─────┼─────┤
             │     │     │   Integration Tests (30%)
             │Integ│     │   - Module interactions
             │─────┼─────│   - Pipeline stages
             │  Int│     │   - Error handling
             ├─────┼─────┤
             │     │     │   Unit Tests (60%)
             │Unit │     │   - Individual functions
             │     │     │   - Edge cases
             └─────┴─────┘   - Mocks
```

### Benchmark Suite

```kotlin
// test/benchmark/OCRBenchmark.kt

@RunWith(AndroidBenchmarkRunner::class)
class OCRBenchmark {
    
    @get:Rule
    val benchmarkRule = BenchmarkRule()
    
    @Test
    fun benchmarkPreprocessing() = benchmarkRule.measureRepeated {
        val bitmap = loadTestImage("receipt_rotated.jpg")
        pipeline.preprocess(bitmap)
    }
    
    @Test
    fun benchmarkOCRDetection() = benchmarkRule.measureRepeated {
        val bitmap = loadTestImage("table.png")
        ocrEngine.detectText(bitmap)
    }
    
    @Test
    fun benchmarkFullPipeline() = benchmarkRule.measureRepeated {
        val bitmap = loadTestImage("invoice.jpg")
        pipeline.processImage(bitmap)
    }
}
```

### Device Compatibility Matrix
| Device | Snapdragon | RAM | GPU | Expected Time | Status |
|--------|-----------|-----|-----|----------------|--------|
| Poco X3 | 732G | 6GB | Adreno 618 | 2.5s | Target |
| Redmi Note 11 | 680 | 4GB | Adreno 610 | 4-5s | Edge case |
| Samsung A53 | 5050 | 6GB | Mali-G77 | 2s | Fallback (Mali) |

---

## Deployment Checklist

- [ ] All unit tests passing (>90% coverage)
- [ ] Integration tests on real devices
- [ ] Memory leak detection (LeakCanary)
- [ ] Battery drain profiling (< 5% per 10 page scans)
- [ ] Thermal throttling testing
- [ ] Model size verification (total <2GB)
- [ ] Privacy audit (no data transmission)
- [ ] GitHub README + documentation
- [ ] Release notes + versioning
- [ ] F-Droid compatibility check

---

## References & Resources

### Model Download Links
- **PaddleOCR v4:** https://github.com/PaddlePaddle/PaddleOCR/releases
- **Qwen-2.5-1.5B GGUF:** https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF
- **BGE-Small-v1.5:** https://huggingface.co/BAAI/bge-small-en-v1.5

### Key Libraries
- ONNX Runtime Android: https://github.com/microsoft/onnxruntime
- llama.cpp: https://github.com/ggerganov/llama.cpp
- ObjectBox: https://objectbox.io/
- CameraX: https://developer.android.com/training/camerax

### Benchmarks & Papers
- PaddleOCR paper: https://arxiv.org/abs/2109.03144
- DBNet paper: https://arxiv.org/abs/2011.07314
- Vector similarity search: https://arxiv.org/abs/2206.01894

---

**Document Status:** APPROVED FOR DEVELOPMENT  
**Last Updated:** December 6, 2025  
**Next Review:** After Phase 2 completion