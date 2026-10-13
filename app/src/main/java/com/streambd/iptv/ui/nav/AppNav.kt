package com.streambd.iptv.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.streambd.iptv.ui.screens.HomeMenuScreen
import com.streambd.iptv.ui.screens.PlaceholderScreen

object Routes {
    const val HOME     = "home"
    const val LIVE     = "live"
    const val MOVIES   = "movies"
    const val SERIES   = "series"
    const val GUIDE    = "guide"
    const val SEARCH   = "search"
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
            PlaceholderScreen(title = "Live") { nav.popBackStack() }
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
            PlaceholderScreen(title = "Settings") { nav.popBackStack() }
        }
        composable(Routes.ACTIVATION) {
            PlaceholderScreen(title = "Activation") { nav.popBackStack() }
        }
    }
}
