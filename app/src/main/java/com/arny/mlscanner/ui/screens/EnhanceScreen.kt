package com.arny.mlscanner.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.arny.mlscanner.data.image.raster.DocumentFilters
import com.arny.mlscanner.data.image.raster.EnhanceFilter

/**
 * Экран «Обработка изображения» как в CamScanner: лента фильтров снизу с миниатюрами,
 * кнопка «Сравнить» с зажатием показывает исходник.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnhanceScreen(
    source: Bitmap,
    selected: EnhanceFilter,
    filtered: Bitmap?,
    isApplying: Boolean,
    onFilterChanged: (EnhanceFilter) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    var compare by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Обработка изображения") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    // «Сравнить» работает по зажатию: пока палец держится — показываем оригинал.
                IconButton(
                    onClick = {},
                    modifier = Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                compare = true
                                try { awaitRelease() }
                                finally { compare = false }
                            }
                        )
                    }
                ) {
                        Icon(
                            Icons.Default.Compare,
                            contentDescription = "Сравнить с оригиналом",
                            tint = if (compare) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        },
        bottomBar = {
            // Кнопки — в одном Column с лентой: никаких магических отступов поверх контента.
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.padding(end = 8.dp)
                    ) { Text("Отмена") }
                    Button(onClick = onConfirm, enabled = !isApplying) {
                        Icon(Icons.Default.Check, contentDescription = null)
                        Text("Готово")
                    }
                }
                FilterStrip(
                    source = source,
                    selected = selected,
                    onFilterChanged = onFilterChanged
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            val shown = if (compare) source else filtered
            if (shown != null) {
                Image(
                    bitmap = shown.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            }
            if (compare) {
                Text(
                    "Оригинал",
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .background(Color(0x99000000), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    color = Color.White
                )
            }
            if (isApplying) {
                CircularProgressIndicator()
            }
        }
    }

}

@Composable
private fun FilterStrip(
    source: Bitmap,
    selected: EnhanceFilter,
    onFilterChanged: (EnhanceFilter) -> Unit
) {
    // Миниатюры считаются один раз на появление экрана.
    val thumbs = remember(source) {
        EnhanceFilter.entries.associateWith { DocumentFilters.applyThumbnail(source, it) }
    }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(EnhanceFilter.entries.toList()) { filter ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { onFilterChanged(filter) }
            ) {
                val thumb = thumbs[filter]
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (filter == selected) Modifier.border(
                                2.dp,
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(8.dp)
                            ) else Modifier
                        )
                ) {
                    if (thumb != null) {
                        Image(
                            bitmap = thumb.asImageBitmap(),
                            contentDescription = filter.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                Text(
                    filter.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (filter == selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
