package com.arny.mlscanner.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------------------
// 1️⃣  Full‑screen preview of the scanning flow
// ---------------------------------------------------------------------------
@Preview(
    name = "ScanningScreen – Default",
    showBackground = true,
    widthDp = 360, heightDp = 640
)
@Composable
fun PreviewScanningScreen() {
    MaterialTheme {
        ScanningScreen()
    }
}

@Composable
fun ScanningScreen(
    progressMessage: String = "Scanning text...",
    onCancel: () -> Unit = {}
) {
    // Animated rotation for scanner icon
    val infiniteTransition = rememberInfiniteTransition(label = "scanner")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Animated scanner icon
            Icon(
                imageVector = Icons.Default.DocumentScanner,
                contentDescription = null,
                modifier = Modifier
                    .size(80.dp)
                    .rotate(rotation),
                tint = MaterialTheme.colorScheme.primary
            )

            // Progress indicator
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp)
            )

            // Status text
            Text(
                text = progressMessage,
                style = MaterialTheme.typography.titleMedium
            )

            // Steps info
            Column(
                horizontalAlignment = Alignment.Start,
                modifier = Modifier.padding(horizontal = 32.dp)
            ) {
                ScanningStep("Preprocessing image", true)
                ScanningStep("Detecting text blocks", true)
                ScanningStep("Recognizing characters", true)
                ScanningStep("Preserving formatting", false)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Cancel button (optional)
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun ScanningStep(
    text: String,
    isCompleted: Boolean
) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (isCompleted) {
            Icon(
                imageVector = Icons.Default.DocumentScanner,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isCompleted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
    }
}
