package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.navigation.NavHostController
import com.arny.mlscanner.data.image.raster.DocumentFilters
import com.arny.mlscanner.data.image.raster.EnhanceFilter
import com.arny.mlscanner.domain.models.OcrEngineType
import com.arny.mlscanner.domain.models.OcrLanguage
import com.arny.mlscanner.domain.models.ScanSettings
import com.arny.mlscanner.ui.navigation.Screen
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

/**
 * Редактор «Выравнивание и улучшение цвета» (открывается кнопкой с экрана предобработки).
 * Экран предобработки в два шага, как в CamScanner:
 * 1. «Положение» — полноэкранный контур с тянущимися углами/сторонами + Влево/Вправо/Все/Далее;
 * 2. «Улучшение» — выпрямленный кадр, лента фильтров, «До/После», «Повернуть».
 * Кнопка «Далее»/фильтры применяются до распознавания.
 */
@Composable
fun DocumentEditorRoute(
    viewModel: ScanViewModel = koinViewModel(),
    navController: NavHostController
) {
    val uiState by viewModel.uiState.collectAsState()
    val previewBitmap = uiState.previewBitmap

    if (previewBitmap == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Text("Обработка изображения…", modifier = Modifier.padding(top = 16.dp))
            }
        }
        return
    }

    // Запоминаем положение кадра на входе: «Назад» без «Применить» вернёт его.
    LaunchedEffect(Unit) { viewModel.onEditorOpened() }
    val scope = rememberCoroutineScope()
    var applying by remember { mutableStateOf(false) }

    DocumentEditorScreen(
        previewBitmap = previewBitmap,
        settings = uiState.settings,
        suggestedQuad = uiState.suggestedQuad,
        imageVersion = uiState.imageVersion,
        initialFilter = uiState.preScanFilter,
        isApplying = applying,
        // Контур живёт в редакторе до «Применить»: во ViewModel его не пишем,
        // иначе «Назад» оставил бы невидимый кроп на экране предобработки.
        onCropChanged = {},
        onRectifyPreview = viewModel::rectifyPreviewLocal,
        onRotate = { degrees -> viewModel.rotateImage(degrees) },
        onUpdateSettings = viewModel::onSettingsChanged,
        onApply = { quad, filter, turns ->
            if (!applying) {
                applying = true
                scope.launch {
                    val ok = try {
                        viewModel.applyEditorResult(quad, filter, turns)
                    } finally {
                        applying = false
                    }
                    if (ok) navController.popBackStack()
                }
            }
        },
        onBack = {
            viewModel.onEditorCancelled()
            navController.popBackStack()
        },
        onReturnToResult = uiState.resultBitmap?.let {
            {
                viewModel.onReturnToResult()
                navController.navigate(Screen.Result.route) {
                    popUpTo(Screen.Preprocessing.route) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    )
}

@Composable
fun DocumentEditorScreen(
    previewBitmap: Bitmap?,
    settings: ScanSettings = ScanSettings.DEFAULT,
    suggestedQuad: CropQuad? = null,
    imageVersion: Long = 0L,
    initialFilter: EnhanceFilter = EnhanceFilter.ORIGINAL,
    isApplying: Boolean = false,
    onCropChanged: (CropQuad) -> Unit = {},
    onRectifyPreview: suspend (CropQuad) -> Bitmap? = { null },
    onRotate: (Float) -> Unit = {},
    onUpdateSettings: (ScanSettings) -> Unit = {},
    onApply: (quad: CropQuad?, filter: EnhanceFilter, quarterTurns: Int) -> Unit = { _, _, _ -> },
    onBack: () -> Unit = {},
    onReturnToResult: (() -> Unit)? = null
) {
    var tab by remember(imageVersion) { mutableIntStateOf(0) } // 0 = Положение, 1 = Улучшение
    // Контур, поднятый из вкладки «Положение»: им же выпрямляем кадр во вкладке «Улучшение».
    var lastQuad by remember(imageVersion) { mutableStateOf<CropQuad?>(null) }

    if (tab == 0) {
        PositionTab(
            previewBitmap = previewBitmap,
            suggestedQuad = suggestedQuad,
            imageVersion = imageVersion,
            settings = settings,
            onUpdateSettings = onUpdateSettings,
            onCropChanged = {
                lastQuad = it
                onCropChanged(it)
            },
            onRotate = onRotate,
            onNext = { tab = 1 },
            onBack = onBack
        )
    } else {
        EnhanceTab(
            previewBitmap = previewBitmap,
            imageVersion = imageVersion,
            quad = lastQuad,
            settings = settings,
            initialFilter = initialFilter,
            isApplying = isApplying,
            onBack = { tab = 0 },
            onRectifyPreview = onRectifyPreview,
            onUpdateSettings = onUpdateSettings,
            onApply = { filter, turns -> onApply(lastQuad, filter, turns) },
            onReturnToResult = onReturnToResult
        )
    }
}

// ══════════════════════ Вкладка 1: Положение ══════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PositionTab(
    previewBitmap: Bitmap?,
    suggestedQuad: CropQuad?,
    imageVersion: Long,
    settings: ScanSettings,
    onUpdateSettings: (ScanSettings) -> Unit,
    onCropChanged: (CropQuad) -> Unit,
    onRotate: (Float) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    val bg = Color.Black
    var currentQuad by remember(imageVersion) { mutableStateOf<CropQuad?>(null) }
    var showLanguages by remember { mutableStateOf(false) }
    // «Все» — осознанный выбор пользователя: поздняя подсказка детектора его не перебивает.
    var suppressSuggestion by remember(imageVersion) { mutableStateOf(false) }
    BackHandler(onBack = onBack)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
    ) {
        Column(Modifier.fillMaxSize()) {
            // Верхняя панель: назад + «Языки: X»
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Color.White)
                }
                Spacer(Modifier.width(32.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text("Языки: ", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = Color.White,
                        modifier = Modifier
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { showLanguages = true }
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                    ) {
                        Text(settings.language.displayName, color = Color.Black)
                    }
                    Icon(
                        Icons.Default.ArrowDropDown,
                        null,
                        tint = Color.White
                    )
                }
                Spacer(Modifier.width(48.dp))
            }

            // Превью с контуром на всю оставшуюся высоту
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (previewBitmap != null && !previewBitmap.isRecycled) {
                    CropImageView(
                        bitmap = previewBitmap.asImageBitmap(),
                        modifier = Modifier.fillMaxSize(),
                        imageId = imageVersion,
                        initialQuad = if (suppressSuggestion) null else suggestedQuad,
                        minCropSize = 40f,
                        onCropChanged = {
                            currentQuad = it
                            onCropChanged(it)
                        }
                    )
                }
                // Подсказка, как в CamScanner
                Surface(
                    color = Color(0x99000000),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        "Перетащите точки по краям страницы",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                    )
                }
            }

            // Нижняя панель CamScanner: Влево | Вправо | Все | Далее
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF202124))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ToolButton("Влево", "Повернуть влево") { onRotate(-90f) }
                ToolButton("Вправо", "Повернуть вправо") { onRotate(90f) }
                ToolButton("Все", "Выбрать весь кадр") {
                    suppressSuggestion = true
                    previewBitmap?.let {
                        val full = CropQuad.fullFrame(it.width, it.height)
                        currentQuad = full
                        onCropChanged(full)
                    }
                }
                ToolButton("Далее", "К улучшению") { onNext() }
            }
        }
    }

    if (showLanguages) {
        SettingsDialog(
            settings = settings,
            onApply = { s2 -> onUpdateSettings(s2); showLanguages = false },
            onDismiss = { showLanguages = false }
        )
    }
}

@Composable
private fun ToolButton(label: String, description: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val icon = when (label) {
            "Влево" -> Icons.AutoMirrored.Filled.ArrowBack
            "Вправо" -> Icons.AutoMirrored.Filled.ArrowForward
            "Все" -> Icons.Default.FullscreenExit
            else -> Icons.AutoMirrored.Filled.ArrowForward
        }
        IconButton(onClick = onClick) {
            Icon(icon, description, tint = Color.White, modifier = Modifier.size(28.dp))
        }
        Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

// ══════════════════════ Вкладка 2: Улучшение ══════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EnhanceTab(
    previewBitmap: Bitmap?,
    imageVersion: Long,
    quad: CropQuad?,
    settings: ScanSettings,
    initialFilter: EnhanceFilter,
    isApplying: Boolean,
    onBack: () -> Unit,
    onRectifyPreview: suspend (CropQuad) -> Bitmap?,
    onUpdateSettings: (ScanSettings) -> Unit,
    onApply: (filter: EnhanceFilter, quarterTurns: Int) -> Unit,
    onReturnToResult: (() -> Unit)?
) {
    // Выбор фильтра и поворот живут локально до «Применить»; «Назад» их отбрасывает.
    var filter by remember(imageVersion) { mutableStateOf(initialFilter) }
    var turns by remember(imageVersion) { mutableIntStateOf(0) }
    var compare by remember { mutableStateOf(false) } // «До/После»
    var showRotate by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var rectified by remember { mutableStateOf<Bitmap?>(null) }
    var turned by remember { mutableStateOf<Bitmap?>(null) }
    var filtered by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(true) }

    BackHandler(onBack = onBack)

    // Выпрямленный по контуру кадр (тот же контур, что тянули на вкладке «Положение»).
    // Ручной recycle не делаем: кадр может ещё рисоваться — освобождает GC.
    LaunchedEffect(previewBitmap, quad) {
        val preview = previewBitmap ?: return@LaunchedEffect
        busy = true
        val target = quad ?: CropQuad.fullFrame(preview.width, preview.height)
        rectified = onRectifyPreview(target) // уже на Dispatchers.Default
    }

    // Локальный поворот (кратно 90°) — вне главного потока.
    LaunchedEffect(rectified, turns) {
        val rect = rectified ?: return@LaunchedEffect
        busy = true
        turned = withContext(Dispatchers.Default) { rotateQuarter(rect, turns) }
    }

    // Фильтр — вне главного потока; один эффект на одно изменение.
    LaunchedEffect(turned, filter) {
        val src = turned ?: return@LaunchedEffect
        busy = true
        filtered = if (filter == EnhanceFilter.ORIGINAL) null
        else withContext(Dispatchers.Default) { DocumentFilters.apply(src, filter) }
        busy = false
    }

    val shown = if (compare) turned else (filtered ?: turned)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            // Верх: Назад | Название фильтра | До/После
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = Color.White)
                    Text("Назад", color = Color.White)
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    filter.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1
                )
                // До/После (после «.» как в CamScanner)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A2A))
                        .padding(2.dp)
                ) {
                    TogglePill("До", compare) { compare = true }
                    TogglePill("После", !compare) { compare = false }
                }
            }

            // Кадр
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (shown != null && !shown.isRecycled) {
                    Image(
                        bitmap = shown.asImageBitmap(),
                        contentDescription = "Результат улучшения",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
                if (busy) CircularProgressIndicator(color = Color.White)
            }

            // Нижняя панель CamScanner: Настройки | Повернуть | Сохранить+сканировать
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF202124))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ToolButtonDark("Языки и движок") { showSettings = true }
                ToolButtonDark("Повернуть") { showRotate = true }
                Button(
                    onClick = { onApply(filter, turns) },
                    enabled = !busy && !isApplying && turned != null
                ) {
                    if (isApplying) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Save, null, tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("Применить")
                }
            }

            // Лента фильтров — как в CamScanner
            FilterNameStrip(
                selected = filter,
                onSelected = { filter = it }
            )
        }
    }

    // Всплывающий «Повернуть» (Влево/Вправо), как в CamScanner
    if (showRotate) {
        AlertDialog(
            onDismissRequest = { showRotate = false },
            title = { Text("Повернуть") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            showRotate = false
                            turns = (turns + 3) % 4
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Влево")
                        Text("Влево")
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable {
                            showRotate = false
                            turns = (turns + 1) % 4
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Вправо")
                        Text("Вправо")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showRotate = false }) { Text("Отмена") }
            }
        )
    }

    // Диалог движка и языка
    if (showSettings) {
        SettingsDialog(
            settings = settings,
            onApply = { s -> onUpdateSettings(s); showSettings = false },
            onDismiss = { showSettings = false }
        )
    }
}

/** Поворот на [turns]×90° без пересжатия; при 0 — сам [src]. */
private fun rotateQuarter(src: Bitmap, turns: Int): Bitmap {
    val n = ((turns % 4) + 4) % 4
    if (n == 0) return src
    val m = android.graphics.Matrix().apply { postRotate(n * 90f) }
    return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
}

@Composable
private fun TogglePill(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Color(0xFF3DDC84) else Color.Transparent)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (active) Color.Black else Color.White,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun ToolButtonDark(label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onClick() }) {
        Icon(
            Icons.Default.OpenWith,
            null,
            tint = Color.White,
            modifier = Modifier.size(26.dp)
        )
        Text(label, color = Color.White, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Лента фильтров подписями (как в CamScanner), вместо миниатюр: быстрее и не считает
 * фильтры при каждом открытии.
 */
@Composable
private fun FilterNameStrip(
    selected: EnhanceFilter,
    onSelected: (EnhanceFilter) -> Unit
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF202124))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp)
    ) {
        items(EnhanceFilter.entries.toList()) { filter ->
            val isSel = filter == selected
            Text(
                filter.displayName,
                color = if (isSel) Color(0xFF3DDC84) else Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { onSelected(filter) }
            )
        }
    }
}

@Composable
private fun SettingsDialog(
    settings: ScanSettings,
    onApply: (ScanSettings) -> Unit,
    onDismiss: () -> Unit
) {
    var engine by remember { mutableStateOf(settings.engineType) }
    var language by remember { mutableStateOf(settings.language) }
    var autoRotate by remember { mutableStateOf(settings.autoRotateEnabled) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Язык и дополнительно") },
        text = {
            Column {
                Text("Язык распознавания", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OcrLanguage.entries.forEach { lang ->
                        FilterChip(
                            selected = language == lang,
                            onClick = { language = lang },
                            label = { Text(lang.displayName) }
                        )
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = autoRotate, onCheckedChange = { autoRotate = it })
                    Text("Автоповорот страницы", Modifier.padding(start = 8.dp))
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                // Обычно не трогается: умная цепочка сама выбирает движок.
                Text("Движок вручную (для отладки)", style = MaterialTheme.typography.labelLarge)
                OcrEngineType.entries.forEach { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = engine == e, onClick = { engine = e })
                        Text(e.displayName, Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onApply(
                    settings.copy(
                        engineType = engine,
                        language = language,
                        autoRotateEnabled = autoRotate
                    )
                )
            }) { Text("Применить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
