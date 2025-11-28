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

@Composable
fun CropImageView(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    onCropChanged: (CropRect) -> Unit
) {
    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    // Полупрозрачный черный для затемнения (Overlay)
    val maskColor = Color.Black.copy(alpha = 0.6f)

    val minCropSizePx = with(density) { 48.dp.toPx() }

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val imageSize = IntSize(bitmap.width, bitmap.height)

    // Rect в координатах VIEW (экрана)
    var viewCropRect by remember { mutableStateOf<Rect?>(null) }

    // 1. Вычисляем параметры отображения картинки (Fit Center)
    val layoutInfo = remember(canvasSize, imageSize) {
        if (canvasSize == IntSize.Zero) null else {
            calculateLayoutInfo(canvasSize, imageSize)
        }
    }

    // 2. Инициализация кропа ТОЛЬКО ОДИН РАЗ при первом появлении картинки
    LaunchedEffect(layoutInfo) {
        if (viewCropRect == null && layoutInfo != null) {
            // Изначально выделяем всю картинку
            viewCropRect = Rect(
                offset = layoutInfo.offset,
                size = Size(layoutInfo.scaledWidth, layoutInfo.scaledHeight)
            )
            // Сообщаем об этом наружу
            onCropChanged(
                CropRect(0f, 0f, imageSize.width.toFloat(), imageSize.height.toFloat())
            )
        }
    }

    // 3. Функция обновления координат при драге
    fun updateCrop(newRect: Rect) {
        viewCropRect = newRect
        layoutInfo?.let { info ->
            // Конвертация View Coordinates -> Bitmap Coordinates
            val bitmapLeft = (newRect.left - info.offset.x) / info.scale
            val bitmapTop = (newRect.top - info.offset.y) / info.scale
            val bitmapWidth = newRect.width / info.scale
            val bitmapHeight = newRect.height / info.scale

            onCropChanged(
                CropRect(
                    left = bitmapLeft.coerceIn(0f, imageSize.width.toFloat()),
                    top = bitmapTop.coerceIn(0f, imageSize.height.toFloat()),
                    width = bitmapWidth.coerceAtLeast(1f),
                    height = bitmapHeight.coerceAtLeast(1f)
                )
            )
        }
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onGloballyPositioned { coordinates ->
                canvasSize = coordinates.size
            }
    ) {
        // Слой рисования: Картинка + Затемнение + Рамка
        Canvas(modifier = Modifier.fillMaxSize()) {
            layoutInfo?.let { info ->
                // A. Рисуем картинку
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(info.offset.x.roundToInt(), info.offset.y.roundToInt()),
                    dstSize = IntSize(info.scaledWidth.roundToInt(), info.scaledHeight.roundToInt())
                )

                viewCropRect?.let { rect ->
                    // B. Рисуем затемнение вокруг прямоугольника (4 прямоугольника)
                    // Это решает проблему с BlendMode.Clear, который стирал картинку

                    // Верх
                    drawRect(
                        color = maskColor,
                        topLeft = Offset(0f, 0f),
                        size = Size(size.width, rect.top)
                    )
                    // Низ
                    drawRect(
                        color = maskColor,
                        topLeft = Offset(0f, rect.bottom),
                        size = Size(size.width, size.height - rect.bottom)
                    )
                    // Лево (между верхом и низом)
                    drawRect(
                        color = maskColor,
                        topLeft = Offset(0f, rect.top),
                        size = Size(rect.left, rect.height)
                    )
                    // Право (между верхом и низом)
                    drawRect(
                        color = maskColor,
                        topLeft = Offset(rect.right, rect.top),
                        size = Size(size.width - rect.right, rect.height)
                    )

                    // C. Рисуем рамку кропа
                    drawRect(
                        color = primaryColor,
                        topLeft = rect.topLeft,
                        size = rect.size,
                        style = Stroke(width = 2.dp.toPx())
                    )

                    // D. Рисуем сетку (Grid) 3x3
                    val thirdW = rect.width / 3
                    val thirdH = rect.height / 3

                    // Вертикальные линии
                    drawLine(
                        primaryColor.copy(0.5f),
                        Offset(rect.left + thirdW, rect.top),
                        Offset(rect.left + thirdW, rect.bottom)
                    )
                    drawLine(
                        primaryColor.copy(0.5f),
                        Offset(rect.left + thirdW * 2, rect.top),
                        Offset(rect.left + thirdW * 2, rect.bottom)
                    )
                    // Горизонтальные линии
                    drawLine(
                        primaryColor.copy(0.5f),
                        Offset(rect.left, rect.top + thirdH),
                        Offset(rect.right, rect.top + thirdH)
                    )
                    drawLine(
                        primaryColor.copy(0.5f),
                        Offset(rect.left, rect.top + thirdH * 2),
                        Offset(rect.right, rect.top + thirdH * 2)
                    )
                }
            }
        }

        // Слой управления (Handles)
        viewCropRect?.let { rect ->
            layoutInfo?.let { info ->
                val bounds = Rect(info.offset, Size(info.scaledWidth, info.scaledHeight))

                fun safeClamp(value: Float, min: Float, max: Float): Float {
                    return if (min > max) min else value.coerceIn(min, max)
                }

                // Top Left
                CropHandle(
                    offset = rect.topLeft,
                    onDrag = { change ->
                        val newLeft =
                            safeClamp(rect.left + change.x, bounds.left, rect.right - minCropSizePx)
                        val newTop =
                            safeClamp(rect.top + change.y, bounds.top, rect.bottom - minCropSizePx)
                        updateCrop(
                            Rect(
                                left = newLeft,
                                top = newTop,
                                right = rect.right,
                                bottom = rect.bottom
                            )
                        )
                    }
                )

                // Top Right
                CropHandle(
                    offset = rect.topRight,
                    onDrag = { change ->
                        val newRight = safeClamp(
                            rect.right + change.x,
                            rect.left + minCropSizePx,
                            bounds.right
                        )
                        val newTop =
                            safeClamp(rect.top + change.y, bounds.top, rect.bottom - minCropSizePx)
                        updateCrop(
                            Rect(
                                left = rect.left,
                                top = newTop,
                                right = newRight,
                                bottom = rect.bottom
                            )
                        )
                    }
                )

                // Bottom Left
                CropHandle(
                    offset = rect.bottomLeft,
                    onDrag = { change ->
                        val newLeft =
                            safeClamp(rect.left + change.x, bounds.left, rect.right - minCropSizePx)
                        val newBottom = safeClamp(
                            rect.bottom + change.y,
                            rect.top + minCropSizePx,
                            bounds.bottom
                        )
                        updateCrop(
                            Rect(
                                left = newLeft,
                                top = rect.top,
                                right = rect.right,
                                bottom = newBottom
                            )
                        )
                    }
                )

                // Bottom Right
                CropHandle(
                    offset = rect.bottomRight,
                    onDrag = { change ->
                        val newRight = safeClamp(
                            rect.right + change.x,
                            rect.left + minCropSizePx,
                            bounds.right
                        )
                        val newBottom = safeClamp(
                            rect.bottom + change.y,
                            rect.top + minCropSizePx,
                            bounds.bottom
                        )
                        updateCrop(
                            Rect(
                                left = rect.left,
                                top = rect.top,
                                right = newRight,
                                bottom = newBottom
                            )
                        )
                    }
                )
            }
        }
    }
}

@Composable
fun CropHandle(
    offset: Offset,
    onDrag: (Offset) -> Unit
) {
    val hitSize = 48.dp // Увеличил область нажатия
    val dotSize = 16.dp
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
                .size(dotSize)
                .background(MaterialTheme.colorScheme.primary, CircleShape)
                .background(Color.White.copy(0.2f), CircleShape)
        )
    }
}

// Helpers
data class LayoutInfo(
    val offset: Offset,
    val scale: Float,
    val scaledWidth: Float,
    val scaledHeight: Float
)

fun calculateLayoutInfo(canvasSize: IntSize, imageSize: IntSize): LayoutInfo {
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
