package com.arny.mlscanner.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable

sealed class Screen(val route: String) {
    data object Camera : Screen("camera")
    data object Preprocessing : Screen("preprocessing")
    data object Scanning : Screen("scanning")
    data object Result : Screen("result")
}

@Composable
fun AppNavigation(
    navController: NavHostController,
    cameraScreen: @Composable () -> Unit,
    preprocessingScreen: @Composable () -> Unit,
    scanScreen: @Composable () -> Unit,
    resultScreen: @Composable () -> Unit,
) {
    NavHost(
        navController = navController, startDestination = Screen.Camera.route
    ) {
        composable(Screen.Camera.route) { cameraScreen() }
        composable(Screen.Preprocessing.route) { preprocessingScreen() }
        composable(Screen.Scanning.route) { scanScreen() }
        composable(Screen.Result.route) { resultScreen() }
    }
}
