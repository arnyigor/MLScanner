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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import android.graphics.PointF
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Редактор обрезки с четырьмя НЕЗАВИСИМЫМИ углами (как в CamScanner).
 *
 * Каждый угол тянется отдельно, поэтому контур можно положить точно на края листа
 * на наклонном снимке — из этого контура затем выпрямляется перспектива.
 * При перетаскивании над пальцем показывается лупа с увеличением.
 */
@Composable
fun CropImageView(
    bitmap: ImageBitmap,
    modifier: Modifier = Modifier,
    imageId: Long = 0L,
    initialQuad: CropQuad? = null,
    minCropSize: Float = 20f,
    onCropChanged: (CropQuad) -> Unit
) {
    val density = LocalDensity.current
    val primaryColor = MaterialTheme.colorScheme.primary
    val maskColor = Color.Black.copy(alpha = 0.6f)

    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    // Контур в координатах BITMAP. Сбрасывается только на новый кадр (imageId).
    var quad by remember(imageId) {
        mutableStateOf(initialQuad ?: CropQuad.fullFrame(bitmap.width, bitmap.height))
    }
    // Пользователь уже двигал углы — поздняя подсказка детектора их не перебивает.
    var userEdited by remember(imageId) { mutableStateOf(false) }

    // Начальный контур и подсказка детектора публикуются родителю; поздняя подсказка
    // ручную правку не перебивает. Во время самого перетаскивания родителя не дёргаем —
    // итог уйдёт на onDragEnd.
    LaunchedEffect(initialQuad, imageId) {
        if (!userEdited) {
            if (initialQuad != null) quad = initialQuad
            onCropChanged(quad)
        }
    }

    // Какой угол сейчас тянут (для лупы).
    var activeCorner by remember { mutableStateOf(-1) }
    // Какая сторона сейчас тянется.
    var activeEdge by remember { mutableStateOf(-1) }

    val layoutInfo = remember(canvasSize, bitmap) {
        if (canvasSize == IntSize.Zero) null
        else calculateLayoutInfo(canvasSize, IntSize(bitmap.width, bitmap.height))
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { canvasSize = it.size }
    ) {
        layoutInfo?.let { info ->
            // bitmap -> screen
            fun toScreen(p: android.graphics.PointF) = Offset(
                info.offset.x + p.x * info.scale,
                info.offset.y + p.y * info.scale
            )

            val screenPoints = quad.points.map { toScreen(it) }

            // Сторона (линия) тянется целиком: оба её угла едут на один вектор.
            fun onEdgeDrag(edge: Int, dragPx: Offset) {
                val w = bitmap.width.toFloat()
                val h = bitmap.height.toFloat()
                val delta = Offset(dragPx.x / info.scale, dragPx.y / info.scale)
                val pts = quad.points.toMutableList()
                val i1 = edge
                val i2 = (edge + 1) % 4
                pts[i1] = android.graphics.PointF(
                    (pts[i1].x + delta.x).coerceIn(0f, w),
                    (pts[i1].y + delta.y).coerceIn(0f, h)
                )
                pts[i2] = android.graphics.PointF(
                    (pts[i2].x + delta.x).coerceIn(0f, w),
                    (pts[i2].y + delta.y).coerceIn(0f, h)
                )
                val candidate = CropQuad(pts[0], pts[1], pts[2], pts[3])
                val minEdge = minCropSize / 2f
                val sidesOk =
                    CropQuad.distance(candidate.topLeft, candidate.bottomLeft) >= minEdge &&
                    CropQuad.distance(candidate.topRight, candidate.bottomRight) >= minEdge &&
                    CropQuad.distance(candidate.topLeft, candidate.topRight) >= minEdge &&
                    CropQuad.distance(candidate.bottomLeft, candidate.bottomRight) >= minEdge
                if (sidesOk && candidate.isValid(w * h)) {
                    quad = candidate
                }
            }

            Canvas(modifier = Modifier.fillMaxSize()) {
                // dens — плотность DrawScope: локальный val density (Density из LocalDensity)
                // в объёме композабла перекрывает DrawScope.density.
                val dens = this.density
                drawImage(
                    image = bitmap,
                    dstOffset = IntOffset(info.offset.x.roundToInt(), info.offset.y.roundToInt()),
                    dstSize = IntSize(info.scaledWidth.roundToInt(), info.scaledHeight.roundToInt())
                )

                val path = Path().apply {
                    moveTo(screenPoints[0].x, screenPoints[0].y)
                    lineTo(screenPoints[1].x, screenPoints[1].y)
                    lineTo(screenPoints[2].x, screenPoints[2].y)
                    lineTo(screenPoints[3].x, screenPoints[3].y)
                    close()
                }

                // Затемняем всё вне контура (вырез по четырёхугольнику, не по прямоугольнику).
                val outer = Path().apply {
                    addRect(androidx.compose.ui.geometry.Rect(Offset.Zero, size))
                    op(this, path, androidx.compose.ui.graphics.PathOperation.Difference)
                }
                drawPath(outer, maskColor)

                // Контур: толстая линия, как в CamScanner.
                drawPath(path, primaryColor, style = Stroke(width = 3f * dens))

                // Ручки сторон — белые «таблетки», повёрнутые вдоль стороны.
                val halfTab = 14f * dens
                val tabHalfH = 6f * dens
                for (e in 0 until 4) {
                    val a = screenPoints[e]
                    val b = screenPoints[(e + 1) % 4]
                    val mid = Offset((a.x + b.x) / 2f, (a.y + b.y) / 2f)
                    val angle = (atan2(b.y - a.y, b.x - a.x) * (180.0 / PI)).toFloat()
                    drawContext.canvas.save()
                    drawContext.canvas.translate(mid.x, mid.y)
                    drawContext.canvas.rotate(angle)
                    val tabTopLeft = Offset(-halfTab, -tabHalfH)
                    val tabSize = Size(2f * halfTab, 2f * tabHalfH)
                    drawRoundRect(
                        color = Color.White,
                        topLeft = tabTopLeft,
                        size = tabSize,
                        cornerRadius = CornerRadius(tabHalfH, tabHalfH)
                    )
                    drawRoundRect(
                        color = primaryColor,
                        topLeft = tabTopLeft,
                        size = tabSize,
                        cornerRadius = CornerRadius(tabHalfH, tabHalfH),
                        style = Stroke(width = 2f * dens)
                    )
                    drawContext.canvas.restore()
                }

                // Углы — белые круги с цветным кольцом (поверх сторон).
                val cornerR = 9f * dens
                for (pt in screenPoints) {
                    drawCircle(Color.White, radius = cornerR, center = pt)
                    drawCircle(
                        primaryColor,
                        radius = cornerR,
                        center = pt,
                        style = Stroke(width = 2f * dens)
                    )
                }
            }

            // Единый жестовый слой (как в CamScanner): угол берётся радиусом вблизи
            // точки, сторона — вблизи любой её точки, а не только середины.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(info, bitmap) {
                        val cornerHitPx = with(density) { 28.dp.toPx() }
                        val edgeHitPx = with(density) { 20.dp.toPx() }
                        val fw = bitmap.width.toFloat()
                        val fh = bitmap.height.toFloat()
                        var kind = -1          // 0 — угол, 1 — сторона
                        var index = -1
                        var quadAtStart = quad
                        var lastValid = quad
                        var accumulated = Offset.Zero
                        detectDragGestures(
                            onDragStart = { down ->
                                accumulated = Offset.Zero
                                quadAtStart = quad
                                lastValid = quad
                                val sp = quadAtStart.points.map { toScreen(it) }
                                // Сначала углы: на коротких сторонах середина попадает под угол.
                                var best = -1
                                var bestDist = cornerHitPx * cornerHitPx
                                sp.forEachIndexed { idx, pt ->
                                    val dist = (down - pt).getDistanceSquared()
                                    if (dist <= bestDist) {
                                        bestDist = dist
                                        best = idx
                                    }
                                }
                                if (best >= 0) {
                                    kind = 0
                                    index = best
                                    userEdited = true
                                    activeCorner = best
                                } else {
                                    var bestEdge = -1
                                    var bestEdgeDist = edgeHitPx
                                    for (e in 0 until 4) {
                                        val dpt = distanceToSegment(down, sp[e], sp[(e + 1) % 4])
                                        if (dpt < bestEdgeDist) {
                                            bestEdgeDist = dpt
                                            bestEdge = e
                                        }
                                    }
                                    if (bestEdge >= 0) {
                                        kind = 1
                                        index = bestEdge
                                        userEdited = true
                                        activeEdge = bestEdge
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (kind >= 0) {
                                    // Жест считается ОТ точки старта: отброшенный шаг не «съедает»
                                    // движение, палец не отстаёт от ручки.
                                    accumulated += dragAmount
                                    val delta = PointF(
                                        accumulated.x / info.scale,
                                        accumulated.y / info.scale
                                    )
                                    val next = if (kind == 0) {
                                        val startPt = quadAtStart.corner(index)
                                        quadAtStart.moveCorner(
                                            index,
                                            PointF(startPt.x + delta.x, startPt.y + delta.y),
                                            fw, fh, minCropSize
                                        )
                                    } else {
                                        quadAtStart.moveEdge(index, delta, fw, fh, minCropSize / 2f)
                                    }
                                    if (next != null) {
                                        lastValid = next
                                        quad = next
                                    } else {
                                        // Дальше шаг вырождает контур — стоим на последнем
                                        // допустимом, жест при этом не обрывается.
                                        quad = lastValid
                                    }
                                }
                            },
                            onDragEnd = {
                                if (kind >= 0) onCropChanged(quad)
                                kind = -1
                                index = -1
                                activeCorner = -1
                                activeEdge = -1
                            },
                            onDragCancel = {
                                quad = lastValid
                                kind = -1
                                index = -1
                                activeCorner = -1
                                activeEdge = -1
                            }
                        )
                    }
            )

            // Лупа: увеличенный фрагмент вокруг перетаскиваемого угла.
            if (activeCorner >= 0) {
                val cornerPt = quad.corner(activeCorner)
                Magnifier(
                    bitmap = bitmap,
                    info = info,
                    cornerBitmap = cornerPt,
                    anchorScreen = toScreen(cornerPt),
                    canvasSize = canvasSize
                )
            }
        }
    }
}

/** Круглая лупа над точкой перетаскивания, как в CamScanner. */
@Composable
private fun Magnifier(
    bitmap: ImageBitmap,
    info: LayoutInfo,
    cornerBitmap: android.graphics.PointF,
    anchorScreen: Offset,
    canvasSize: IntSize
) {
    val density = LocalDensity.current
    val lensSize = 120.dp
    val lensPx = with(density) { lensSize.toPx() }
    val zoom = 2.5f

    // Ставим лупу над пальцем, не выходя за пределы экрана.
    var left = anchorScreen.x - lensPx / 2
    var top = anchorScreen.y - lensPx - 40f
    if (top < 0f) top = anchorScreen.y + 40f
    left = left.coerceIn(0f, (canvasSize.width - lensPx).coerceAtLeast(0f))
    top = top.coerceIn(0f, (canvasSize.height - lensPx).coerceAtLeast(0f))

    Canvas(
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(lensSize)
    ) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val lens = Path().apply { addOval(androidx.compose.ui.geometry.Rect(center, radius)) }

        clipPath(lens) {
            // Фрагмент вокруг угла, растянутый zoom-кратно.
            val srcW = (size.width / zoom / info.scale).coerceAtMost(bitmap.width.toFloat())
            val srcH = (size.height / zoom / info.scale).coerceAtMost(bitmap.height.toFloat())
            val srcLeft = (cornerBitmap.x - srcW / 2).coerceIn(0f, (bitmap.width - srcW).coerceAtLeast(0f))
            val srcTop = (cornerBitmap.y - srcH / 2).coerceIn(0f, (bitmap.height - srcH).coerceAtLeast(0f))
            drawImage(
                image = bitmap,
                srcOffset = IntOffset(srcLeft.roundToInt(), srcTop.roundToInt()),
                srcSize = IntSize(srcW.roundToInt().coerceAtLeast(1), srcH.roundToInt().coerceAtLeast(1)),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt())
            )
        }
        drawCircle(Color.White, radius = radius, center = center, style = Stroke(width = 3f))
        // Перекрестие в центре линзы
        drawLine(Color.Red.copy(alpha = 0.8f), Offset(center.x - 12f, center.y), Offset(center.x + 12f, center.y), 2f)
        drawLine(Color.Red.copy(alpha = 0.8f), Offset(center.x, center.y - 12f), Offset(center.x, center.y + 12f), 2f)
    }
}

/** Расстояние от точки до отрезка — hit-тест стороны по ближайшей её точке. */
private fun distanceToSegment(point: Offset, a: Offset, b: Offset): Float {
    val abx = b.x - a.x
    val aby = b.y - a.y
    val len2 = abx * abx + aby * aby
    if (len2 < 1e-3f) return (point - a).getDistance()
    val t = (((point.x - a.x) * abx + (point.y - a.y) * aby) / len2).coerceIn(0f, 1f)
    return (point - Offset(a.x + abx * t, a.y + aby * t)).getDistance()
}

private fun calculateLayoutInfo(canvasSize: IntSize, imageSize: IntSize): LayoutInfo {
    // Поле 28px по краям: ручки углов не должны обрезаться экраном (как в CamScanner).
    val pad = 28f
    val availW = (canvasSize.width - pad * 2).coerceAtLeast(1f)
    val availH = (canvasSize.height - pad * 2).coerceAtLeast(1f)
    val scaleX = availW / imageSize.width
    val scaleY = availH / imageSize.height
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

data class LayoutInfo(
    val offset: Offset,
    val scale: Float,
    val scaledWidth: Float,
    val scaledHeight: Float
)

@Preview
@Composable
fun CropImageViewPreviewPlaceholder() {
    // Превью без ресурса: контур и ручки проверяются на устройстве.
    Box(modifier = Modifier.fillMaxSize().background(Color.DarkGray))
}
