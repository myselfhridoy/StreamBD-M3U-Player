package com.streambd.iptv.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streambd.iptv.data.activation.ActivationRepository
import com.streambd.iptv.data.activation.ActivationState
import com.streambd.iptv.player.PlayerManager
import com.streambd.iptv.ui.screens.HomeMenuScreen
import com.streambd.iptv.ui.screens.activation.ActivationScreen
import com.streambd.iptv.ui.screens.ftp.FtpScreen
import com.streambd.iptv.ui.screens.guide.GuideScreen
import com.streambd.iptv.ui.screens.live.LiveScreen
import com.streambd.iptv.ui.screens.providers.ProvidersScreen
import com.streambd.iptv.ui.screens.search.SearchScreen
import com.streambd.iptv.ui.screens.settings.SettingsScreen
import com.streambd.iptv.ui.screens.splash.SplashScreen
import com.streambd.iptv.ui.screens.vod.VodScreen
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

object Routes {
    const val SPLASH = "splash"
    const val HOME = "home"
    const val PROVIDERS = "providers"
    const val FTP = "ftp"
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

@HiltViewModel
class AppNavViewModel @Inject constructor(
    val activationRepository: ActivationRepository,
    val playerManager: PlayerManager
) : ViewModel() {
    // ★ নতুন: Nullable StateFlow তৈরি করা হলো, যাতে initial = null দেওয়া যায়
    val activationState: StateFlow<ActivationState?> = activationRepository.state
        .map { it as ActivationState? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val navViewModel: AppNavViewModel = hiltViewModel()
    
    // ★ নতুন: initial = null এর বদলে সরাসরি nullable StateFlow কালেক্ট করা হচ্ছে
    val activationState by navViewModel.activationState.collectAsStateWithLifecycle()

    // গ্লোবাল চেক: অ্যাক্টিভেশন না থাকলে বা এক্সপায়ার হলে সরাসরি Activation স্ক্রিনে পাঠাবে
    LaunchedEffect(activationState) {
        // ★ নতুন: এখনো লোড হয়নি (null), তাই কিছুই করবে না
        val state = activationState ?: return@LaunchedEffect
        
        val currentRoute = nav.currentBackStackEntry?.destination?.route
        val isExpired = (state is ActivationState.Active) && 
                        navViewModel.activationRepository.isExpired(state)

        if ((state is ActivationState.NotActivated || isExpired) && 
            currentRoute != Routes.ACTIVATION && 
            currentRoute != Routes.SPLASH) {
            
            // চলমান প্লেয়ার বন্ধ করে দেওয়া
            navViewModel.playerManager.stopAndClear()
            
            nav.navigate(Routes.ACTIVATION) {
                popUpTo(0) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = nav, 
        startDestination = Routes.SPLASH,
        enterTransition = { fadeIn(animationSpec = tween(250)) },
        exitTransition = { fadeOut(animationSpec = tween(250)) },
        popEnterTransition = { fadeIn(animationSpec = tween(250)) },
        popExitTransition = { fadeOut(animationSpec = tween(250)) }
    ) {
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
                onToLive = { channelId ->
                    nav.navigate(Routes.HOME) { 
                        popUpTo(Routes.SPLASH) { inclusive = true } 
                    }
                    nav.navigate(Routes.live(channelId))
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
        
        composable(Routes.PROVIDERS) {
            ProvidersScreen(
                onBack = { nav.popBackStack() }
            )
        }

        composable(Routes.FTP) {
            FtpScreen(
                onBack = { nav.popBackStack() },
                onOpenProviders = { nav.navigate(Routes.PROVIDERS) }
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
