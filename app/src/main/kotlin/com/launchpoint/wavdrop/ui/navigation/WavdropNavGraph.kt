package com.launchpoint.wavdrop.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.launchpoint.wavdrop.ui.screen.home.HomeScreen

sealed class Screen(val route: String) {
    data object Home : Screen("home")
}

@Composable
fun WavdropNavGraph(
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController    = navController,
        startDestination = Screen.Home.route,
    ) {
        composable(Screen.Home.route) {
            HomeScreen()
        }
    }
}
