package com.stenograf.app.ui

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.stenograf.app.AppContainer
import com.stenograf.app.RecoApp
import com.stenograf.app.ui.home.HomeScreen
import com.stenograf.app.ui.transcript.TranscriptScreen

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as RecoApp).container

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(
        navController = nav,
        startDestination = "home",
        enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 10 } },
        exitTransition = { fadeOut(tween(180)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(260)) { it / 10 } },
    ) {
        composable("home") {
            HomeScreen(onOpenTranscript = { id -> nav.navigate("transcript/$id") })
        }
        composable("transcript/{id}") { entry ->
            TranscriptScreen(id = entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}
