package com.arny.mlscanner.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.arny.mlscanner.ui.navigation.AppNavigation
import com.arny.mlscanner.ui.theme.AndroidComposeTemplateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidComposeTemplateTheme {
                AppNavigation()
            }
        }
    }
}
