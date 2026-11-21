package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import android.util.Log
import com.arny.mlscanner.ScanTrace
import com.arny.mlscanner.data.history.ScanHistoryRepository
import kotlinx.coroutines.asContextElement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.arny.mlscanner.data.ocr.postprocessing.TextFormatter
import com.arny.mlscanner.data.preprocessing.ImagePreprocessor
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.domain.models.OcrEngineType
import com.arny.mlscanner.domain.models.errors.OcrError
import com.arny.mlscanner.domain.usecases.RecognizeTextUseCase
import com.arny.mlscanner.domain.usecases.barcode.ScanBarcodeUseCase
import com.arny.mlscanner.domain.models.RecognizedText
import com.arny.mlscanner.domain.models.barcode.BarcodeFormat
import com.arny.mlscanner.domain.models.barcode.BarcodeResult
import com.arny.mlscanner.domain.models.barcode.BarcodeScanConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.core.graphics.scale

class ScanViewModel(
    private val recognizeTextUseCase: RecognizeTextUseCase,
    private val imagePreprocessor: ImagePreprocessor,
    private val scanBarcodeUseCase: ScanBarcodeUseCase? = null,
    private val history: ScanHistoryRepository? = null
) : ViewModel() {

    companion object {
        private const val TAG = "ScanViewModel"
        private const val PREVIEW_MAX_DIMENSION = 1280
        private const val OCR_MIN_DIMENSION = 800
        private const val OCR_MAX_DIMENSION = 2048
        private const val FILTER_DEBOUNCE_MS = 350L
    }

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val _events = Channel<ScanUiEvent>(Channel.BUFFERED)
    val events: Flow<ScanUiEvent> = _events.receiveAsFlow()

private var originalBitmap: Bitmap? = null
    private var previewSourceBitmap: Bitmap? = null

    /** Исходник для фильтров обработки: после скана — вход OCR, до него — preview. */
    private var enhancedSource: Bitmap? = null

    /** Фильтр, применённый в редакторе «Выравнивание и улучшение» (живёт в состоянии). */
    private val preScanFilter: com.arny.mlscanner.data.image.raster.EnhanceFilter
        get() = _uiState.value.preScanFilter

    /** Базовый кадр для поворотов: новый снимок или результат «Применить» в редакторе. */
    private var editBase: Bitmap? = null
    /** Кадр уже выпрямлен редактором — автокроп OCR повторно его не режет. */
    private var editorApplied = false
    /** Угол на момент открытия редактора: «Назад» без «Применить» возвращает его. */
    private var editorEntryRotation = 0f
    private var suggestJob: Job? = null

    /** Результат обработки — кадр после OCR-подготовки, вход для ленты фильтров. */
    private fun setEnhancedSource(bitmap: Bitmap?) {
        // Не рециклим здесь: этот же экземпляр может быть resultBitmap в состоянии, и экран
        // в этот момент его рисует. Освобождение отдаём GC — Bitmap с API 26 собирается сборщиком.
        enhancedSource = bitmap
    }
    // ▶ FIX: Сохраняем исходный preview для возможности восстановления
    private var originalPreviewBitmap: Bitmap? = null

    private var filterJob: Job? = null
    private var scanJob: Job? = null
    private val scanMutex = Mutex()

    /** Кадр как пришёл с камеры/из галереи — точка сброса поворота. */
    private var originalBitmapUntouched: Bitmap? = null
    /** Угол рабочего кадра относительно [editBase], 0..360. */
    private var rotationDegrees = 0f

    // Public API

    fun onImageCaptured(bitmap: Bitmap, source: String = "unknown") {
        val receivedAt = ScanTrace.now()
        filterJob?.cancel()
        scanJob?.cancel()
        Log.d(TAG, "Image captured: ${bitmap.width}x${bitmap.height}")
        originalBitmap = bitmap
        originalBitmapUntouched = bitmap
        editBase = bitmap
        editorApplied = false
        rotationDegrees = 0f
        suggestJob?.cancel()
        val preview = if (maxOf(bitmap.width, bitmap.height) > PREVIEW_MAX_DIMENSION) {
            scaleBitmapSafe(bitmap, PREVIEW_MAX_DIMENSION)
        } else bitmap
        previewSourceBitmap = preview
        // ▶ FIX: Сохраняем оригинальный preview для восстановления
        originalPreviewBitmap = preview

        Log.d(TAG, "onImageCaptured: preview=${preview.width}x${preview.height}")

        detectSuggestedQuad(preview)
        _uiState.update {
            it.copy(
                previewBitmap = preview,
                cropQuad = null,
                suggestedQuad = null,
                qrCodes = emptyList(),
                resultBitmap = null,
                isApplyingFilters = false,
                originalImageSize = ImageSize(bitmap.width, bitmap.height),
                error = null,
                recognizedText = null,
                preScanFilter = com.arny.mlscanner.data.image.raster.EnhanceFilter.ORIGINAL,
                imageVersion = it.imageVersion + 1
            )
        }
        // Как в CamScanner: после съёмки/выбора файла — экран предобработки
        // (углы + улучшение), распознавание только по кнопке «Сохранить».
        _uiState.update { it.copy(step = ScanStep.PREPROCESSING) }
    }

    fun onSettingsChanged(settings: ScanSettings) {
        _uiState.update { it.copy(settings = settings) }
        applyFiltersDebounced(settings)
    }

    /** Выбор фильтра на ленте обработки (как в CamScanner). */
    /** Открыть экран обработки (лента фильтров) из результата. */
    fun onOpenEnhance() {
        if (_uiState.value.resultBitmap == null) return
        _uiState.update { it.copy(step = ScanStep.ENHANCE) }
        ScanTrace.log { "enhance.open" }
    }

    fun onCancelEnhance() {
        _uiState.update {
            it.copy(
                step = ScanStep.RESULT,
                enhancedBitmap = null,
                enhanceFilter = com.arny.mlscanner.data.image.raster.EnhanceFilter.ORIGINAL
            )
        }
        ScanTrace.log { "enhance.cancel" }
    }

    fun onConfirmEnhance() {
        val filtered = _uiState.value.enhancedBitmap
        if (filtered == null) {
            onCancelEnhance()
            return
        }
        // Фильтрованный кадр становится результатом; enhanced-состояние сбрасываем.
        setEnhancedSource(null)
        _uiState.update {
            it.copy(
                step = ScanStep.RESULT,
                resultBitmap = filtered,
                enhancedBitmap = null,
                enhanceFilter = com.arny.mlscanner.data.image.raster.EnhanceFilter.ORIGINAL
            )
        }
        ScanTrace.log { "enhance.confirm size=${filtered.width}x${filtered.height}" }
        // Обновляем и в истории: сохранённый скан показывает обработанную версию.
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            history?.replaceResultImage(filtered)
        }
    }

    fun onEnhanceFilterChanged(filter: com.arny.mlscanner.data.image.raster.EnhanceFilter) {
        val source = enhancedSource ?: previewSourceBitmap ?: return
        val started = ScanTrace.now()
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            val filtered = com.arny.mlscanner.data.image.raster.DocumentFilters.apply(source, filter)
            _uiState.update {
                it.copy(
                    enhanceFilter = filter,
                    enhancedBitmap = if (filter ==
                        com.arny.mlscanner.data.image.raster.EnhanceFilter.ORIGINAL
                    ) null else filtered
                )
            }
            ScanTrace.log {
                "enhance.filter filter=$filter input=${source.width}x${source.height} " +
                    "out=${filtered.width}x${filtered.height} elapsedMs=${ScanTrace.elapsed(started)}"
            }
        }
    }

    /** Предзаполняет углы редактора найденным контуром листа (как стартовый контур в CamScanner). */
    private fun detectSuggestedQuad(preview: Bitmap) {
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch {
            val quad = withContext(Dispatchers.Default) {
                imagePreprocessor.detectDocumentQuad(preview)
            } ?: return@launch
            // Запоздавший результат для прежнего кадра (до поворота/нового фото) — отбрасываем.
            if (previewSourceBitmap !== preview) return@launch
            val suggested = CropQuad.fromQuadrilateral(quad)
            if (!suggested.fitsIn(preview.width, preview.height)) return@launch
            // Не перебиваем уже отредактированный пользователем контур.
            _uiState.update { if (it.cropQuad == null) it.copy(suggestedQuad = suggested) else it }
        }
    }

    /**
     * Выпрямляет превью по контуру для экрана улучшения (до сканирования).
     * Контур задан в координатах превью — пересчитываем в координаты оригинала.
     */
    suspend fun rectifyPreview(cropQuad: CropQuad): Bitmap? = withContext(Dispatchers.Default) {
        val originalBmp = originalBitmap ?: return@withContext null
        val started = ScanTrace.now()
        val out = if (cropQuad.coversFrame(originalBmp.width, originalBmp.height) ||
            !cropQuad.isValid(originalBmp.width * originalBmp.height.toFloat())
        ) {
            originalBmp.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            imagePreprocessor.rectifyPerspective(originalBmp, cropQuad.toQuadrilateral())
                ?: originalBmp.copy(Bitmap.Config.ARGB_8888, false)
        }
        ScanTrace.log {
            "edit.rectify ${originalBmp.width}x${originalBmp.height} -> ${out.width}x${out.height} " +
                "elapsedMs=${ScanTrace.elapsed(started)}"
        }
        out
    }

    /**
     * Выпрямление превью по контуру в координатах превью — быстрый вкладочный предпросмотр
     * «как получится» при перетаскивании углов и экране улучшения.
     */
    suspend fun rectifyPreviewLocal(cropQuad: CropQuad): Bitmap? = withContext(Dispatchers.Default) {
        val preview = previewSourceBitmap ?: return@withContext null
        if (cropQuad.coversFrame(preview.width, preview.height) ||
            !cropQuad.isValid(preview.width * preview.height.toFloat())
        ) {
            preview.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            imagePreprocessor.rectifyPerspective(preview, cropQuad.toQuadrilateral())
                ?: preview.copy(Bitmap.Config.ARGB_8888, false)
        }
    }

    /** Углы превью -> углы полного кадра. */
    private fun toOriginalQuad(cropQuad: CropQuad, previewBmp: Bitmap, originalBmp: Bitmap): CropQuad {
        val scaleX = originalBmp.width.toFloat() / previewBmp.width
        val scaleY = originalBmp.height.toFloat() / previewBmp.height
        fun scale(p: android.graphics.PointF) = android.graphics.PointF(p.x * scaleX, p.y * scaleY)
        return CropQuad(
            topLeft = scale(cropQuad.topLeft),
            topRight = scale(cropQuad.topRight),
            bottomRight = scale(cropQuad.bottomRight),
            bottomLeft = scale(cropQuad.bottomLeft)
        )
    }

    /** Редактор открыт: запоминаем угол, чтобы «Назад» мог его вернуть. */
    fun onEditorOpened() {
        editorEntryRotation = rotationDegrees
    }

    /** «Назад» из редактора без «Применить»: правки отменяются, поворот возвращается. */
    fun onEditorCancelled() {
        if (rotationDegrees != editorEntryRotation) rotateTo(editorEntryRotation, "editor.cancel")
        ScanTrace.log { "editor.cancel" }
    }

    /**
     * «Применить» в редакторе: выпрямляет полноразмерный кадр по контуру (координаты превью),
     * поворачивает на [quarterTurns]×90° и делает результат рабочим кадром. Фильтр не «вжигается»
     * в пиксели: он идёт на превью и в OCR, а QR ищется по кадру без фильтра.
     */
    suspend fun applyEditorResult(
        previewQuad: CropQuad?,
        filter: com.arny.mlscanner.data.image.raster.EnhanceFilter,
        quarterTurns: Int
    ): Boolean {
        val base = originalBitmap ?: return false
        val previewSrc = previewSourceBitmap ?: return false
        val settings = _uiState.value.settings
        val started = ScanTrace.now()
        val (page, preview, shown) = withContext(Dispatchers.Default) {
            val quad = previewQuad?.let { toOriginalQuad(it, previewSrc, base) }
            val rectified = if (quad == null || quad.coversFrame(base.width, base.height) ||
                !quad.isValid(base.width * base.height.toFloat())
            ) base else imagePreprocessor.rectifyPerspective(base, quad.toQuadrilateral()) ?: base
            val turned = rotateQuarterTurns(rectified, quarterTurns)
            val pv = if (maxOf(turned.width, turned.height) > PREVIEW_MAX_DIMENSION) {
                scaleBitmapSafe(turned, PREVIEW_MAX_DIMENSION)
            } else turned
            Triple(turned, pv, previewWithFilters(pv, filter, settings))
        }
        filterJob?.cancel()
        suggestJob?.cancel()
        originalBitmap = page
        editBase = page
        rotationDegrees = 0f
        editorEntryRotation = 0f
        editorApplied = true
        previewSourceBitmap = preview
        originalPreviewBitmap = preview
        _uiState.update {
            it.copy(
                previewBitmap = shown,
                cropQuad = null,
                suggestedQuad = null,
                preScanFilter = filter,
                isApplyingFilters = false,
                originalImageSize = ImageSize(page.width, page.height),
                imageVersion = it.imageVersion + 1
            )
        }
        ScanTrace.log {
            "editor.apply ${base.width}x${base.height} -> ${page.width}x${page.height} " +
                "filter=$filter turns=$quarterTurns quad=${previewQuad != null} elapsedMs=${ScanTrace.elapsed(started)}"
        }
        return true
    }

    /** Фильтр редактора, затем ползунки экрана предобработки — для превью. */
    private fun previewWithFilters(
        source: Bitmap,
        filter: com.arny.mlscanner.data.image.raster.EnhanceFilter,
        settings: ScanSettings
    ): Bitmap {
        val filtered = if (filter == com.arny.mlscanner.data.image.raster.EnhanceFilter.ORIGINAL) source
        else com.arny.mlscanner.data.image.raster.DocumentFilters.apply(source, filter)
        return imagePreprocessor.applyFiltersOnly(filtered, settings)
    }

    fun onCropChanged(cropQuad: CropQuad) {
        val previewBmp = previewSourceBitmap ?: return
        val originalBmp = originalBitmap ?: return
        val originalQuad = toOriginalQuad(cropQuad, previewBmp, originalBmp)
        ScanTrace.log {
            "edit.crop preview=${previewBmp.width}x${previewBmp.height} " +
                "quad=${originalQuad.points.joinToString { "(${it.x.toInt()},${it.y.toInt()})" }}"
        }

        _uiState.update { it.copy(cropQuad = originalQuad) }
    }

    fun onStartScanning(
        // Как в прежней логике: обычный скан ищет и текст, и QR одновременно.
        autoDetectQr: Boolean = true,
        source: String = "editor",
        startedAt: Long = ScanTrace.now()
    ) {
        val id = ScanTrace.nextId()
        val original = originalBitmap
        if (original == null) {
            _uiState.update { it.copy(error = ScanError.NoImage) }
            return
        }

        _uiState.update {
            it.copy(
                step = ScanStep.SCANNING,
                isScanning = true,
                error = null,
                recognizedText = null,
                resultBitmap = null,
                qrCodes = emptyList(),
                processingProgress = 0f,
                processingMessage = "Подготовка изображения…"
            )
        }

        scanJob?.cancel()
        ScanTrace.log(id) { "scan.start source=$source input=${original.width}x${original.height} autoQr=$autoDetectQr previewMs=${ScanTrace.elapsed(startedAt)}" }
        scanJob = viewModelScope.launch(ScanTrace.current.asContextElement(id)) {
            try {
                scanMutex.withLock {
                    ScanTrace.log { "scan.processing elapsedMs=${ScanTrace.elapsed(startedAt)}" }
                    performScanning(original, autoDetectQr, startedAt, source)
                }
            } catch (e: CancellationException) {
                ScanTrace.log { "scan.cancelled elapsedMs=${ScanTrace.elapsed(startedAt)}" }
                throw e
            }
        }
    }

    fun onCancelScanning() {
        scanJob?.cancel()
        _uiState.update {
            it.copy(
                step = ScanStep.PREPROCESSING,
                isScanning = false,
                processingProgress = 0f
            )
        }
    }

    fun resetRotation() {
        if (rotationDegrees == 0f) return
        rotateTo(0f, "reset")
    }

    /** Пересобрать preview из текущего рабочего кадра. */
    private fun refreshPreview() {
        val source = originalBitmap ?: return
        val scaled = if (maxOf(source.width, source.height) > PREVIEW_MAX_DIMENSION) {
            scaleBitmapSafe(source, PREVIEW_MAX_DIMENSION)
        } else source
        previewSourceBitmap = scaled
        originalPreviewBitmap = scaled
        _uiState.update {
            it.copy(
                previewBitmap = scaled,
                cropQuad = it.cropQuad?.takeIf { _ -> false },
                originalImageSize = ImageSize(source.width, source.height)
            )
        }
        applyFiltersDebounced(_uiState.value.settings)
    }

    fun onNewScan() {
        clearBitmaps()
        _uiState.value = ScanUiState()
    }

    /** Вернуться к последнему результату после правки. */
    /** Открыть сохранённый скан из истории. */
    fun openHistoryScan(entry: com.arny.mlscanner.data.history.ScanWithCodes) {
        viewModelScope.launch {
            val result = history?.loadImage(entry.scan, original = false)
            val text = if (entry.scan.text.isBlank()) null else com.arny.mlscanner.domain.models.RecognizedText(
                originalText = entry.scan.text,
                formattedText = entry.scan.text,
                blocks = emptyList(),
                confidence = entry.scan.confidence ?: 0f,
                detectedLanguage = entry.scan.language.ifBlank { "?" }
            )
            _uiState.update {
                it.copy(
                    step = if (text != null || entry.codes.isNotEmpty()) ScanStep.RESULT else ScanStep.CAMERA,
                    recognizedText = text,
                    resultBitmap = result,
                    qrCodes = entry.codes.map { code ->
                        com.arny.mlscanner.domain.models.barcode.BarcodeResult(
                            rawValue = code.rawValue,
                            format = BarcodeFormat.valueOf(code.format),
                            contentType = com.arny.mlscanner.domain.models.barcode.BarcodeContentType.valueOf(code.contentType)
                        )
                    }
                )
            }
            ScanTrace.log { "history.open id=${entry.scan.id} textChars=${entry.scan.text.length} codes=${entry.codes.size}" }
        }
    }

    fun onReturnToResult() {
        val text = _uiState.value.recognizedText
        val codes = _uiState.value.qrCodes
        if (text == null && codes.isEmpty()) {
            ScanTrace.log { "result.return skipped reason=nothing" }
            return
        }
        _uiState.update { it.copy(step = ScanStep.RESULT) }
        ScanTrace.log { "result.return text=${text != null} codes=${codes.size}" }
    }

fun onReturnToPreprocessing() {
        android.util.Log.d("ScanViewModel", "onReturnToPreprocessing: previewBitmap=${_uiState.value.previewBitmap != null}, previewSourceBitmap=${previewSourceBitmap != null}, originalPreviewBitmap=${originalPreviewBitmap != null}")
        // ▶ FIX: Убедимся что previewBitmap восстановлен из резервных копий если он null
        val currentPreview = _uiState.value.previewBitmap
        val restoredPreview = when {
            currentPreview != null -> currentPreview
            previewSourceBitmap != null -> previewSourceBitmap
            originalPreviewBitmap != null -> originalPreviewBitmap
            else -> null
        }

        android.util.Log.d("ScanViewModel", "Restored preview: ${restoredPreview != null}")

        _uiState.update {
            it.copy(
                step = ScanStep.PREPROCESSING,
                previewBitmap = restoredPreview,
                isScanning = false,
                processingProgress = 0f
            )
        }
    }

    fun onTextEdited(newText: String) {
        _uiState.update { state ->
            state.copy(
                recognizedText = state.recognizedText?.updateRawText(newText)
            )
        }
    }

    fun onApplyTextChanges(newText: String) {
        _uiState.update { state ->
            state.copy(
                recognizedText = state.recognizedText?.applyRawText(newText)
            )
        }
    }
    
    fun onToggleFormatMode() {
        _uiState.update { state ->
            state.copy(
                recognizedText = state.recognizedText?.toggleFormatMode()
            )
        }
    }
    
    fun onPatternClick(action: TextFormatter.ClickAction) {
        ScanTrace.log { "text.pattern.click action=${action.javaClass.simpleName}" }
        viewModelScope.launch {
            when (action) {
                is TextFormatter.ClickAction.Call -> {
                    _events.send(ScanUiEvent.CallPhone(action.phoneNumber))
                }
                is TextFormatter.ClickAction.SendEmail -> {
                    _events.send(ScanUiEvent.SendEmail(action.email))
                }
                is TextFormatter.ClickAction.OpenUrl -> {
                    _events.send(ScanUiEvent.OpenUrl(action.url))
                }
                is TextFormatter.ClickAction.CopyToClipboard -> {
                    _events.send(ScanUiEvent.CopyToClipboard(action.text))
                }
            }
        }
    }

    fun onCopyText() {
        viewModelScope.launch {
            _events.send(ScanUiEvent.CopiedToClipboard)
        }
    }

    fun onShareText() {
        val text = _uiState.value.recognizedText?.formattedText ?: return
        viewModelScope.launch {
            _events.send(ScanUiEvent.ShareText(text))
        }
    }

    fun onErrorDismissed() {
        _uiState.update { it.copy(error = null) }
    }

    private fun applyFiltersDebounced(settings: ScanSettings) {
        val source = previewSourceBitmap ?: return

        filterJob?.cancel()
        filterJob = viewModelScope.launch(Dispatchers.Default) {
            delay(FILTER_DEBOUNCE_MS)
            _uiState.update { it.copy(isApplyingFilters = true) }

            try {
                val filtered = previewWithFilters(source, preScanFilter, settings)
                if (isActive && previewSourceBitmap === source) {
                    _uiState.update {
                        it.copy(previewBitmap = filtered, isApplyingFilters = false)
                    }
                }
            } catch (_: CancellationException) {
                // Normal - user is moving slider
            } catch (e: Exception) {
                Log.e(TAG, "Filter error", e)
                _uiState.update { it.copy(isApplyingFilters = false) }
            }
        }
    }

    private suspend fun performScanning(source: Bitmap, autoDetectQr: Boolean, startedAt: Long, sourceName: String = "editor") = coroutineScope {
        val state = _uiState.value
        val settings = state.settings
        // «Штрихкод» — режим сам по себе: текстовый OCR в нём не запускается и
        // «Текст не найден» пользователю не показывается.
        val barcodeOnly = settings.engineType == OcrEngineType.BARCODE
        // Кадр, выпрямленный в редакторе, автокроп OCR повторно не режет.
        val autoCrop = state.cropQuad == null && !editorApplied
        ScanTrace.log { "scan.options engine=${settings.engineType} language=${settings.language} barcodeOnly=$barcodeOnly manualCrop=${state.cropQuad != null} editorApplied=$editorApplied filter=${state.preScanFilter} autoRotate=${settings.autoRotateEnabled}" }

        // Единый выровненный кадр для QR и текста: выпрямление по контуру (и фильтр
        // «Улучшение», если он выбран) — распознаётся ровно то, что видел пользователь.
        val cropped = withContext(Dispatchers.Default) { rectifyCrop(source, state.cropQuad) }
        val enhanced = if (!barcodeOnly &&
            preScanFilter != com.arny.mlscanner.data.image.raster.EnhanceFilter.ORIGINAL
        ) {
            withContext(Dispatchers.Default) {
                com.arny.mlscanner.data.image.raster.DocumentFilters.apply(cropped, preScanFilter)
            }
        } else cropped

        var recognized: RecognizedText? = null
        var failure: Throwable? = null
        var ocrInput: Bitmap? = null
        try {
        // Код ищем всегда (умный режим RU+EN+QR). Вход для кода — выровненный кадр
        // БЕЗ улучшающего фильтра: фильтр помогает глазам и OCR, но может убить код.
        val qr = async(Dispatchers.Default) {
            detectCodes(cropped, qrOnly = !barcodeOnly)
        }

        // Own the QR job in the same session as OCR. Publish results while OCR is still running.
        val publishCodes = qr?.let { pending ->
            launch {
                val codes = pending.await()
                ensureActive()
                _uiState.update { it.copy(qrCodes = codes) }
                ScanTrace.log { "codes.published count=${codes.size} elapsedMs=${ScanTrace.elapsed(startedAt)}" }
            }
        }
            try {
            if (!barcodeOnly) {
                updateProgress(0.2f, "Распознаём текст…")
                val result = withContext(Dispatchers.Default) {
                    val ocrSettings = if (settings.engineType == OcrEngineType.BARCODE) {
                        settings.copy(engineType = ScanSettings.DEFAULT.engineType)
                    } else settings
                    val scaled = scaleForOcr(enhanced)
                    ScanTrace.log { "ocr.start engine=${ocrSettings.engineType} filter=$preScanFilter input=${scaled.width}x${scaled.height}" }
                    try {
                        recognizeTextUseCase.execute(scaled, ocrSettings, autoCrop = autoCrop) {
                            ScanTrace.log { "ocr.input size=${it.width}x${it.height} elapsedMs=${ScanTrace.elapsed(startedAt)}" }
                            ocrInput = it.copy(Bitmap.Config.ARGB_8888, false)
                        }
                    } finally {
                        if (scaled !== enhanced && scaled !== source) scaled.recycle()
                    }
                }
                recognized = result.getOrNull()
                failure = result.exceptionOrNull()
            }
        } catch (e: CancellationException) {
            ocrInput?.recycle()
            throw e
        } catch (e: Exception) {
            failure = e
        }
        try {
            publishCodes?.join()
            ensureActive()
        } catch (e: CancellationException) {
            ocrInput?.recycle()
            throw e
        }
        } finally {
            // Кадры-входы больше не нужны: QR и OCR закончены (или отменены).
            if (enhanced !== cropped) enhanced.recycle()
            if (cropped !== source) cropped.recycle()
        }

        val hasResult = recognized != null || _uiState.value.qrCodes.isNotEmpty()
        ScanTrace.log {
            "scan.finish result=$hasResult textChars=${recognized?.formattedText?.length ?: 0} " +
                "codes=${_uiState.value.qrCodes.size} error=${failure?.javaClass?.simpleName ?: "none"} " +
                "elapsedMs=${ScanTrace.elapsed(startedAt)}"
        }
        _uiState.update {
            it.copy(
                step = if (hasResult) ScanStep.RESULT else ScanStep.PREPROCESSING,
                recognizedText = recognized,
                resultBitmap = if (hasResult) ocrInput ?: source else null,
                enhancedBitmap = null,
                enhanceFilter = com.arny.mlscanner.data.image.raster.EnhanceFilter.ORIGINAL,
                enhancedSource = null,
                scanSource = sourceName,
                scannedAt = if (hasResult) System.currentTimeMillis() else 0L,
                originalBitmap = if (hasResult) source else null,
                isScanning = false,
                processingProgress = if (hasResult) 1f else 0f,
                error = if (hasResult) null else ScanError.OcrFailed(
                    when {
                        barcodeOnly -> "Штрихкод или QR-код не найден"
                        failure == OcrError.NoTextFound -> "Текст не найден, код тоже не обнаружен"
                        else -> "Не удалось распознать текст или найти код"
                    }
                )
            )
        }
        if (hasResult) {
            // Лента фильтров работает от кадра, который получил OCR.
            setEnhancedSource(ocrInput ?: source)
            _uiState.update { it.copy(enhancedSource = _uiState.value.enhancedSource) }
            // История: пишем после публикации состояния, не задерживая показ.
            launch {
                history?.save(
                    source = sourceName,
                    text = recognized,
                    codes = _uiState.value.qrCodes,
                    resultBitmap = ocrInput,
                    originalBitmap = source
                )
            }
        } else {
            ocrInput?.recycle()
        }
    }

    private suspend fun detectCodes(bitmap: Bitmap, qrOnly: Boolean): List<BarcodeResult> {
        val useCase = scanBarcodeUseCase ?: return emptyList()
        val config = BarcodeScanConfig(
            allowedFormats = if (qrOnly) setOf(BarcodeFormat.QR_CODE) else emptySet()
        )
        val started = ScanTrace.now()
        ScanTrace.log { "codes.start qrOnly=$qrOnly input=${bitmap.width}x${bitmap.height}" }
        return try {
            val decoded = withTimeoutOrNull(8_000) {
                val maxSide = maxOf(bitmap.width, bitmap.height)
                val found = ArrayList<BarcodeResult>()

                suspend fun attempt(name: String, candidate: Bitmap) {
                    if (found.isNotEmpty()) return
                    ScanTrace.log { "codes.attempt pass=$name input=${candidate.width}x${candidate.height}" }
                    try {
                        found += useCase(candidate, config)
                    } finally {
                        if (candidate !== bitmap) candidate.recycle()
                    }
                }

                // pass 1: большой кадр уменьшаем до 1600 (проверенный режим).
                val ratio = minOf(1f, 1600f / maxSide)
                val down = bitmap.scale(
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1)
                )
                attempt("1-down1600", down)

                if (found.isEmpty() && (maxSide < 900 || maxSide > 1600)) {
                    // pass 2: масштаб, ОТЛИЧНЫЙ от pass 1 (иначе это тот же кадр):
                    // мелкий апскейлим, огромный — но не выше 3072.
                    val native = when {
                        maxSide < 900 -> scaleBitmapSafe(bitmap, 1800)
                        maxSide in 1601..3072 -> bitmap
                        maxSide > 3072 -> scaleBitmapSafe(bitmap, 3072)
                        else -> bitmap
                    }
                    attempt("2-native", native)
                }

                if (found.isEmpty()) {
                    // pass 3: серое с повышенной контрастностью — бинаризатору
                    // проще на кадре экрана/плёнки с бликами.
                    val src = if (maxSide < 900) scaleBitmapSafe(bitmap, 1800) else bitmap
                    try {
                        attempt("3-gray", grayscaleContrast(src))
                    } finally {
                        if (src !== bitmap) src.recycle()
                    }
                }
                found
            }
            ScanTrace.log { "codes.finish count=${decoded?.size ?: 0} timedOut=${decoded == null} elapsedMs=${ScanTrace.elapsed(started)}" }
            decoded.orEmpty().filter { !qrOnly || it.format == BarcodeFormat.QR_CODE }
                .distinctBy { it.format to it.rawValue }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ScanTrace.log { "codes.error type=${e.javaClass.simpleName} elapsedMs=${ScanTrace.elapsed(started)}" }
            emptyList()
        }
    }

    /** Серое + контраст: одна операция над пикселями, без ML-моделей. */
    private fun grayscaleContrast(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        val paint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
        paint.colorFilter = android.graphics.ColorMatrixColorFilter(
            android.graphics.ColorMatrix(
                floatArrayOf(
                    0.299f, 0.587f, 0.114f, 0f, -55f,
                    0.299f, 0.587f, 0.114f, 0f, -55f,
                    0.299f, 0.587f, 0.114f, 0f, -55f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        canvas.drawBitmap(src, 0f, 0f, paint)
        return out
    }

    private fun rectifyCrop(source: Bitmap, quad: CropQuad?): Bitmap {
        if (quad == null) return source
        // Контур на весь кадр — режем без изменений: warp не нужен.
        if (quad.coversFrame(source.width, source.height)) return source
        if (!quad.isValid(source.width * source.height.toFloat())) {
            Log.w(TAG, "Manual quad invalid, keeping source")
            return source
        }
        // Настоящее выпрямление перспективы по четырём углам (не прямоугольный вырез).
        val rectified = imagePreprocessor.rectifyPerspective(source, quad.toQuadrilateral())
        return rectified ?: source
    }

    private fun scaleForOcr(bitmap: Bitmap): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        return when {
            maxSide < OCR_MIN_DIMENSION -> scaleBitmapSafe(bitmap, OCR_MIN_DIMENSION)
            maxSide > OCR_MAX_DIMENSION -> scaleBitmapSafe(bitmap, OCR_MAX_DIMENSION)
            else -> bitmap
        }
    }

    private fun updateProgress(progress: Float, message: String) {
        _uiState.update {
            it.copy(processingProgress = progress, processingMessage = message)
        }
    }

    private fun scaleBitmapSafe(bitmap: Bitmap, targetMaxSide: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val maxSide = maxOf(w, h)
        if (maxSide == targetMaxSide) return bitmap

        val ratio = targetMaxSide.toFloat() / maxSide
        val newW = (w * ratio).toInt().coerceAtLeast(1)
        val newH = (h * ratio).toInt().coerceAtLeast(1)

        val scaled = bitmap.scale(newW, newH)

        return if (scaled.config != Bitmap.Config.ARGB_8888) {
            val copy = scaled.copy(Bitmap.Config.ARGB_8888, false)
            if (scaled !== bitmap) scaled.recycle()
            copy
        } else {
            scaled
        }
    }

    private fun clearBitmaps() {
        filterJob?.cancel()
        scanJob?.cancel()
        originalBitmap = null
        originalBitmapUntouched = null
        editBase = null
        suggestJob?.cancel()
        rotationDegrees = 0f
        previewSourceBitmap = null
        // ▶ FIX: Очищаем originalPreviewBitmap
        originalPreviewBitmap = null
        setEnhancedSource(null)
    }

    /**
     * Поворот рабочего кадра на [degrees] относительно текущего положения.
     * Угол считается от [editBase] (новый снимок или результат «Применить»), а не от уже
     * повёрнутого кадра — поэтому +90/−90 возвращают исходник без накопления ошибок.
     */
    fun rotateImage(degrees: Float) {
        if (editBase == null) {
            ScanTrace.log { "edit.rotate skipped reason=no_base degrees=$degrees" }
            return
        }
        if (degrees == 0f) {
            ScanTrace.log { "edit.rotate skipped reason=no_change" }
            return
        }
        rotateTo(rotationDegrees + degrees, "edit.rotate degrees=$degrees")
    }

    /** Ставит рабочий кадр в положение [targetDegrees] относительно [editBase]. */
    private fun rotateTo(targetDegrees: Float, reason: String) {
        val base = editBase ?: return
        val started = ScanTrace.now()
        val total = ((targetDegrees % 360f) + 360f) % 360f
        val multiple = total % 90f == 0f
        val rotated = when {
            total == 0f -> base
            multiple -> rotateQuarterTurns(base, (total / 90f).toInt())
            else -> {
                val matrix = android.graphics.Matrix().apply { postRotate(total) }
                val turned = Bitmap.createBitmap(base, 0, 0, base.width, base.height, matrix, true)
                // Не кратный 90° поворот даёт прозрачные углы — для OCR заливаем белым.
                val out = Bitmap.createBitmap(turned.width, turned.height, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(out).apply {
                    drawColor(android.graphics.Color.WHITE)
                    drawBitmap(turned, 0f, 0f, null)
                }
                if (turned !== base) turned.recycle()
                out
            }
        }
        // Прежний рабочий кадр не рециклим: он может ещё отображаться; освобождает GC.
        originalBitmap = rotated
        rotationDegrees = total

        val scaled = if (maxOf(rotated.width, rotated.height) > PREVIEW_MAX_DIMENSION) {
            scaleBitmapSafe(rotated, PREVIEW_MAX_DIMENSION)
        } else rotated
        previewSourceBitmap = scaled
        originalPreviewBitmap = scaled

        // Контур и подсказка прежнего положения недействительны.
        _uiState.update {
            it.copy(
                previewBitmap = scaled,
                cropQuad = null,
                suggestedQuad = null,
                originalImageSize = ImageSize(rotated.width, rotated.height),
                imageVersion = it.imageVersion + 1
            )
        }
        ScanTrace.log {
            "$reason total=$total multiple=$multiple base=${base.width}x${base.height} -> " +
                "${rotated.width}x${rotated.height} preview=${scaled.width}x${scaled.height} " +
                "elapsedMs=${ScanTrace.elapsed(started)}"
        }
        detectSuggestedQuad(scaled)
        applyFiltersDebounced(_uiState.value.settings)
    }

    /** Поворот на [turns]×90° без пересжатия; при 0 возвращает сам [src]. */
    private fun rotateQuarterTurns(src: Bitmap, turns: Int): Bitmap {
        val n = ((turns % 4) + 4) % 4
        if (n == 0) return src
        val matrix = android.graphics.Matrix().apply { postRotate(n * 90f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    override fun onCleared() {
        super.onCleared()
        clearBitmaps()
    }
}
