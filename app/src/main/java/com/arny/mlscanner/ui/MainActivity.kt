package com.arny.mlscanner.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.compose.rememberNavController
import com.arny.mlscanner.ui.navigation.AppNavigation
import com.arny.mlscanner.ui.screens.ScanViewModel
import com.arny.mlscanner.ui.theme.AndroidComposeTemplateTheme
import org.koin.androidx.compose.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidComposeTemplateTheme {
                MainScreen()
            }
        }
    }
}

@Composable
private fun MainScreen() {
    val navController = rememberNavController()
    val viewModel: ScanViewModel = koinViewModel()

    AppNavigation(
        navController = navController,
        viewModel = viewModel
    )
}
