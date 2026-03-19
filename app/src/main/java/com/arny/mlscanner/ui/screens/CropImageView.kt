package com.arny.mlscanner.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import kotlin.math.min
import kotlin.math.roundToInt

@Preview
@Composable
fun CropImageViewPreview() {
    CropImageView(
        bitmap = createBitmap(200, 200).asImageBitmap(),
        modifier = Modifier,
        onCropChanged = {}
    )
}

/**
 * @param bitmap         Текущее изображение (может меняться при фильтрации)
 * @param imageId        Уникальный ID исходного изображения.
 *                        Меняется ТОЛЬКО при новом фото/галерее.
 *                        НЕ меняется при применении фильтров.
 *                        Рамка сбрасывается только при смене imageId.
 * @param minCropSize    Минимальный размер рамки в пикселях bitmap
 * @param onCropChanged  Callback при изменении рамки
 */
@Composable
fun CropImageView(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    imageId: Long = 0L,
    minCropSize: Float = 20f,
    onCropChanged: (CropRect) -> Unit
) {
    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val maskColor = Color.Black.copy(alpha = 0.6f)

    // Минимальный размер кропа (в пикселях bitmap)
    val minTouchSize = with(density) { 48.dp.toPx() }

    // Размер Canvas на экране
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Состояние кропа в координатах БИТМАПА (0..width, 0..height)
    // Инициализируем полным размером картинки
    // ▶ FIX: cropState привязан к imageId, НЕ к bitmap
    // Сбрасывается только когда пользователь делает НОВОЕ фото
    var cropState by remember(imageId) {
        mutableStateOf(
            Rect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        )
    }

    // Сообщаем родителю об изменении
    LaunchedEffect(cropState) {
        onCropChanged(
            CropRect(cropState.left, cropState.top, cropState.width, cropState.height)
        )
    }

    // Вычисляем параметры отображения (FitCenter)
    val layoutInfo = remember(canvasSize, bitmap) {
        if (canvasSize == IntSize.Zero) null else calculateLayoutInfo(canvasSize, IntSize(bitmap.width, bitmap.height))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                canvasSize = coordinates.size
            }
    ) {
        layoutInfo?.let { info ->
            // Конвертация Bitmap Rect -> Screen Rect
            val screenRect = Rect(
                left = info.offset.x + cropState.left * info.scale,
                top = info.offset.y + cropState.top * info.scale,
                right = info.offset.x + cropState.right * info.scale,
                bottom = info.offset.y + cropState.bottom * info.scale
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                // 1. Рисуем картинку
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(info.offset.x.roundToInt(), info.offset.y.roundToInt()),
                    dstSize = IntSize(info.scaledWidth.roundToInt(), info.scaledHeight.roundToInt())
                )

                // 2. Затемнение (Mask)
                // Верх
                drawRect(maskColor, Offset(0f, 0f), Size(size.width, screenRect.top))
                // Низ
                drawRect(maskColor, Offset(0f, screenRect.bottom), Size(size.width, size.height - screenRect.bottom))
                // Лево
                drawRect(maskColor, Offset(0f, screenRect.top), Size(screenRect.left, screenRect.height))
                // Право
                drawRect(maskColor, Offset(screenRect.right, screenRect.top), Size(size.width - screenRect.right, screenRect.height))

                // 3. Рамка
                drawRect(
                    color = primaryColor,
                    topLeft = screenRect.topLeft,
                    size = screenRect.size,
                    style = Stroke(width = 3.dp.toPx())
                )

                // 4. Сетка
                val thirdW = screenRect.width / 3
                val thirdH = screenRect.height / 3
                if (screenRect.width > 20 && screenRect.height > 20) {
                    drawLine(primaryColor.copy(0.5f), Offset(screenRect.left + thirdW, screenRect.top), Offset(screenRect.left + thirdW, screenRect.bottom))
                    drawLine(primaryColor.copy(0.5f), Offset(screenRect.left + thirdW * 2, screenRect.top), Offset(screenRect.left + thirdW * 2, screenRect.bottom))
                    drawLine(primaryColor.copy(0.5f), Offset(screenRect.left, screenRect.top + thirdH), Offset(screenRect.right, screenRect.top + thirdH))
                    drawLine(primaryColor.copy(0.5f), Offset(screenRect.left, screenRect.top + thirdH * 2), Offset(screenRect.right, screenRect.top + thirdH * 2))
                }
            }

            // --- HANDLES (Управление) ---
            // Функция обновления координат в БИТМАПЕ
            fun updateCropInBitmapCoords(newRect: Rect) {
                // Ограничиваем границами битмапа
                val clamped = Rect(
                    left = newRect.left.coerceIn(0f, bitmap.width.toFloat()),
                    top = newRect.top.coerceIn(0f, bitmap.height.toFloat()),
                    right = newRect.right.coerceIn(0f, bitmap.width.toFloat()),
                    bottom = newRect.bottom.coerceIn(0f, bitmap.height.toFloat())
                )
                // ▶ FIX: Минимальный размер рамки уменьшен до 20px
                val minW = minCropSize
                val minH = minCropSize

                if (clamped.width >= minW && clamped.height >= minH) {
                    cropState = clamped
                }
            }

            // Top-Left Handle
            CropHandle(offset = screenRect.topLeft) { drag ->
                // drag в пикселях экрана -> делим на scale -> пиксели битмапа
                val dx = drag.x / info.scale
                val dy = drag.y / info.scale
                updateCropInBitmapCoords(
                    Rect(cropState.left + dx, cropState.top + dy, cropState.right, cropState.bottom)
                )
            }

            // Top-Right Handle
            CropHandle(offset = screenRect.topRight) { drag ->
                val dx = drag.x / info.scale
                val dy = drag.y / info.scale
                updateCropInBitmapCoords(
                    Rect(cropState.left, cropState.top + dy, cropState.right + dx, cropState.bottom)
                )
            }

            // Bottom-Left Handle
            CropHandle(offset = screenRect.bottomLeft) { drag ->
                val dx = drag.x / info.scale
                val dy = drag.y / info.scale
                updateCropInBitmapCoords(
                    Rect(cropState.left + dx, cropState.top, cropState.right, cropState.bottom + dy)
                )
            }

            // Bottom-Right Handle
            CropHandle(offset = screenRect.bottomRight) { drag ->
                val dx = drag.x / info.scale
                val dy = drag.y / info.scale
                updateCropInBitmapCoords(
                    Rect(cropState.left, cropState.top, cropState.right + dx, cropState.bottom + dy)
                )
            }

            // ▶ НОВОЕ: Edge handles (середина стороны) для точной настройки
            // Top edge
            CropHandle(
                offset = Offset((screenRect.left + screenRect.right) / 2, screenRect.top)
            ) { drag ->
                val dy = drag.y / info.scale
                updateCropInBitmapCoords(Rect(cropState.left, cropState.top + dy,
                    cropState.right, cropState.bottom))
            }

            // Bottom edge
            CropHandle(
                offset = Offset((screenRect.left + screenRect.right) / 2, screenRect.bottom)
            ) { drag ->
                val dy = drag.y / info.scale
                updateCropInBitmapCoords(Rect(cropState.left, cropState.top,
                    cropState.right, cropState.bottom + dy))
            }

            // Left edge
            CropHandle(
                offset = Offset(screenRect.left, (screenRect.top + screenRect.bottom) / 2)
            ) { drag ->
                val dx = drag.x / info.scale
                updateCropInBitmapCoords(Rect(cropState.left + dx, cropState.top,
                    cropState.right, cropState.bottom))
            }

            // Right edge
            CropHandle(
                offset = Offset(screenRect.right, (screenRect.top + screenRect.bottom) / 2)
            ) { drag ->
                val dx = drag.x / info.scale
                updateCropInBitmapCoords(Rect(cropState.left, cropState.top,
                    cropState.right + dx, cropState.bottom))
            }
        }
    }
}

@Composable
fun CropHandle(
    offset: Offset,
    onDrag: (Offset) -> Unit
) {
    val hitSize = 48.dp
    val density = LocalDensity.current
    val hitSizePx = with(density) { hitSize.toPx() }

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (offset.x - hitSizePx / 2).roundToInt(),
                    (offset.y - hitSizePx / 2).roundToInt()
                )
            }
            .size(hitSize)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .background(Color.White.copy(0.5f), CircleShape)
        )
    }
}

// Повтор хелпера (можно оставить старый, если он есть)
private fun calculateLayoutInfo(canvasSize: IntSize, imageSize: IntSize): LayoutInfo {
    val scaleX = canvasSize.width.toFloat() / imageSize.width
    val scaleY = canvasSize.height.toFloat() / imageSize.height
    val scale = min(scaleX, scaleY)

    val scaledWidth = imageSize.width * scale
    val scaledHeight = imageSize.height * scale

    val offsetX = (canvasSize.width - scaledWidth) / 2f
    val offsetY = (canvasSize.height - scaledHeight) / 2f

    return LayoutInfo(
        offset = Offset(offsetX, offsetY),
        scale = scale,
        scaledWidth = scaledWidth,
        scaledHeight = scaledHeight
    )
}

// Helpers
data class LayoutInfo(
    val offset: Offset,
    val scale: Float,
    val scaledWidth: Float,
    val scaledHeight: Float
)