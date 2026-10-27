package com.streambd.iptv.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streambd.iptv.ui.screens.HomeMenuScreen
import com.streambd.iptv.ui.screens.activation.ActivationScreen
import com.streambd.iptv.ui.screens.guide.GuideScreen
import com.streambd.iptv.ui.screens.live.LiveScreen
import com.streambd.iptv.ui.screens.search.SearchScreen
import com.streambd.iptv.ui.screens.settings.SettingsScreen
import com.streambd.iptv.ui.screens.splash.SplashScreen
import com.streambd.iptv.ui.screens.vod.VodScreen

object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val LIVE_BASE = "live"
    const val LIVE = "live?channelId={channelId}"
    fun live(channelId: Long = -1L) = "live?channelId=$channelId"
    const val MOVIES = "vod/MOVIE"
    const val SERIES = "vod/SERIES"
    const val VOD_PATTERN = "vod/{kind}?playId={playId}"
    fun vodPlay(kind: String, channelId: Long) = "vod/$kind?playId=$channelId"
    const val GUIDE = "guide"
    const val SEARCH = "search"
    const val SETTINGS = "settings"
    const val ACTIVATION = "activation"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()

    NavHost(navController = nav, startDestination = Routes.SPLASH) {
        composable(Routes.SPLASH) {
            SplashScreen(
                onToActivation = {
                    nav.navigate(Routes.ACTIVATION) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
                onToHome = {
                    nav.navigate(Routes.HOME) {
                        popUpTo(Routes.SPLASH) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            val activity = LocalContext.current as? ComponentActivity
            HomeMenuScreen(
                onOpen = { route ->
                    if (route == Routes.LIVE) nav.navigate(Routes.live()) else nav.navigate(route)
                },
                onExit = { activity?.finish() },
            )
        }
        composable(
            route = Routes.LIVE,
            arguments = listOf(navArgument("channelId") {
                type = NavType.LongType
                defaultValue = -1L
            }),
        ) { entry ->
            LiveScreen(
                preselectChannelId = entry.arguments?.getLong("channelId") ?: -1L,
                onBack = { nav.popBackStack() },
            )
        }
        composable(
            route = Routes.VOD_PATTERN,
            arguments = listOf(
                navArgument("kind") { type = NavType.StringType },
                navArgument("playId") { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { entry ->
            VodScreen(
                onBack = { nav.popBackStack() },
                directPlayId = entry.arguments?.getLong("playId")?.takeIf { it > 0L },
            )
        }
        composable(Routes.GUIDE) { GuideScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SEARCH) {
            SearchScreen(
                onBack = { nav.popBackStack() },
                onPickLive = { channel -> nav.navigate(Routes.live(channel.id)) },
                onPlayVod = { channel -> nav.navigate(Routes.vodPlay(channel.kind, channel.id)) },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                onBack = { nav.popBackStack() },
                onResetComplete = {
                    nav.navigate(Routes.ACTIVATION) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.ACTIVATION) {
            ActivationScreen(
                onActivated = {
                    nav.navigate(Routes.SPLASH) {
                        popUpTo(Routes.ACTIVATION) { inclusive = true }
                    }
                },
            )
        }
    }
}
