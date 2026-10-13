package com.streambd.iptv.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.streambd.iptv.ui.screens.HomeMenuScreen
import com.streambd.iptv.ui.screens.PlaceholderScreen
import com.streambd.iptv.ui.screens.live.LiveScreen
import com.streambd.iptv.ui.screens.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val LIVE = "live"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val GUIDE = "guide"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val ACTIVATION = "activation"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeMenuScreen(onOpen = { route -> nav.navigate(route) })
        }
        composable(Routes.LIVE) {
            LiveScreen()
        }
        composable(Routes.MOVIES) {
            PlaceholderScreen(title = "Movies") { nav.popBackStack() }
        }
        composable(Routes.SERIES) {
            PlaceholderScreen(title = "Series") { nav.popBackStack() }
        }
        composable(Routes.GUIDE) {
            PlaceholderScreen(title = "TV Guide") { nav.popBackStack() }
        }
        composable(Routes.SEARCH) {
            PlaceholderScreen(title = "Search") { nav.popBackStack() }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onResetComplete = {
                    nav.popBackStack(Routes.HOME, inclusive = false)
                    nav.navigate(Routes.ACTIVATION)
                },
            )
        }
        composable(Routes.ACTIVATION) {
            PlaceholderScreen(title = "Activation") { nav.popBackStack() }
        }
    }
}
