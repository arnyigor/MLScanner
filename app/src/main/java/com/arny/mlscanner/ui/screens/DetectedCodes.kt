package com.arny.mlscanner.ui.screens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arny.mlscanner.ScanTrace
import com.arny.mlscanner.domain.models.barcode.BarcodeContentType
import com.arny.mlscanner.domain.models.barcode.BarcodeResult
import com.arny.mlscanner.domain.models.barcode.ParsedBarcodeContent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DetectedCodes(codes: List<BarcodeResult>) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("Найденные коды", style = MaterialTheme.typography.titleMedium)
        codes.forEach { code ->
            val action = codeAction(code)
            Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(code.displayTitle, style = MaterialTheme.typography.labelMedium)
                    Text(code.rawValue, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (action != null) {
                            androidx.compose.material3.Button(onClick = {
                                ScanTrace.log { "code.action type=${code.contentType}" }
                                openSafely(context, action.second, "code.open")
                            }) { Text(action.first) }
                        }
                        TextButton(onClick = {
                            ScanTrace.log { "code.copy type=${code.contentType}" }
                            copyToClipboardWithToast(context, code.rawValue, code.format.displayName)
                        }) { Text("Копировать") }
                    }
                }
            }
        }
    }
}

/** Кнопка и intent для кода; null — действия нет, остаётся только копирование. Открытие — только по нажатию. */
internal fun codeAction(code: BarcodeResult): Pair<String, Intent>? {
    val raw = code.rawValue.trim()
    return when (val parsed = code.parsedContent) {
        is ParsedBarcodeContent.Url -> "Открыть" to Intent(Intent.ACTION_VIEW, Uri.parse(withScheme(parsed.url)))
        is ParsedBarcodeContent.Phone -> "Позвонить" to Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(parsed.number)}"))
        is ParsedBarcodeContent.Email -> "Написать" to Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(parsed.address)}"))
        is ParsedBarcodeContent.Sms -> "SMS" to Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${Uri.encode(parsed.phoneNumber)}"))
        is ParsedBarcodeContent.Geo -> "На карте" to Intent(Intent.ACTION_VIEW, Uri.parse("geo:${parsed.latitude},${parsed.longitude}"))
        else -> when (code.contentType) {
            BarcodeContentType.URL -> "Открыть" to Intent(Intent.ACTION_VIEW, Uri.parse(withScheme(raw)))
            BarcodeContentType.PHONE -> "Позвонить" to Intent(Intent.ACTION_DIAL, Uri.parse(if (raw.startsWith("tel:", true)) raw else "tel:$raw"))
            BarcodeContentType.EMAIL -> "Написать" to Intent(Intent.ACTION_SENDTO, Uri.parse(if (raw.startsWith("mailto:", true)) raw else "mailto:$raw"))
            else -> if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
                "Открыть" to Intent(Intent.ACTION_VIEW, Uri.parse(raw))
            } else null
        }
    }
}

private fun withScheme(url: String): String =
    if (url.contains("://")) url else "https://$url"

/** Запуск внешнего приложения с логом результата (без самого адреса). */
internal fun openSafely(context: Context, intent: Intent, tag: String) {
    try {
        context.startActivity(intent)
        ScanTrace.log { "$tag.started action=${intent.action} scheme=${intent.data?.scheme}" }
    } catch (e: ActivityNotFoundException) {
        ScanTrace.log { "$tag.failed action=${intent.action} scheme=${intent.data?.scheme} error=no_app" }
        Toast.makeText(context, "Нет приложения для этого действия", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        ScanTrace.log { "$tag.failed action=${intent.action} error=${e.javaClass.simpleName}" }
        Toast.makeText(context, "Не удалось открыть", Toast.LENGTH_SHORT).show()
    }
}

/** Копирование с видимым подтверждением: Android 11+ своё уведомление не показывает. */
internal fun copyToClipboardWithToast(context: Context, text: String, label: String = "OCR") {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    val summary = if (text.length > 40) "${text.take(40)}…" else text
    Toast.makeText(context, "Скопировано: $summary", Toast.LENGTH_SHORT).show()
    ScanTrace.log { "clipboard.copied label=$label chars=${text.length}" }
}

/**
 * Полноэкранный просмотр кадра: зум щипком, перемещение, двойной тап — сброс.
 * Сравнивать оригинал и обработку можно кнопками вверху.
 */
@Composable
internal fun ImageViewerDialog(
    original: android.graphics.Bitmap?,
    processed: android.graphics.Bitmap?,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var showOriginal by remember { mutableStateOf(false) }
        var scale by remember { mutableFloatStateOf(1f) }
        var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val image = if (showOriginal) original else processed

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (original != null && processed != null) {
                    TextButton(onClick = {
                        showOriginal = !showOriginal
                        scale = 1f
                        offset = androidx.compose.ui.geometry.Offset.Zero
                        ScanTrace.log { "viewer.switch show=${if (showOriginal) "original" else "processed"}" }
                    }) { Text(if (showOriginal) "Оригинал" else "Обработанный", color = Color.White) }
                }
                TextButton(onClick = onDismiss) {
                    androidx.compose.material3.Icon(Icons.Default.Close, contentDescription = "Закрыть", tint = Color.White)
                }
            }

            image?.let {
                androidx.compose.foundation.Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Просмотр скана",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y
                        )
                        .pointerInput(showOriginal) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 8f)
                                offset = if (scale > 1f) {
                                    androidx.compose.ui.geometry.Offset(
                                        offset.x + pan.x,
                                        offset.y + pan.y
                                    )
                                } else androidx.compose.ui.geometry.Offset.Zero
                            }
                        }
                )
            }

            Text(
                "Зум до 8x; свернуть — кнопкой вверху",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
            )
        }
    }
}

