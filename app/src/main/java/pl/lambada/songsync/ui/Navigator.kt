package pl.lambada.songsync.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import pl.lambada.songsync.data.UserSettingsController
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsProviderService
import pl.lambada.songsync.ui.common.animatedComposable
import pl.lambada.songsync.ui.screens.batch.BatchOptionsScreen
import pl.lambada.songsync.ui.screens.batch.BatchProgressScreen
import pl.lambada.songsync.ui.screens.home.HomeScreen
import pl.lambada.songsync.util.batch.BatchDownloadController
import pl.lambada.songsync.ui.screens.home.HomeViewModel
import pl.lambada.songsync.ui.screens.init.InitScreen
import pl.lambada.songsync.ui.screens.init.InitScreenViewModel
import pl.lambada.songsync.ui.screens.lyricsFetch.LyricsFetchScreen
import pl.lambada.songsync.ui.screens.lyricsFetch.LyricsFetchViewModel
import pl.lambada.songsync.ui.screens.player.SyncedLyricsPlayerScreen
import pl.lambada.songsync.ui.screens.settings.SettingsScreen
import pl.lambada.songsync.ui.screens.settings.SettingsViewModel

/**
 * Composable function for handling navigation within the app.
 *
 * @param navController The navigation controller.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Navigator(
    navController: NavHostController,
    userSettingsController: UserSettingsController,
    lyricsProviderService: LyricsProviderService
) {
    // Tapping the batch notification (or any other requester) asks for the batch screen here. The activity is
    // singleTask, so the running task is reused and this only pushes the destination once.
    LaunchedEffect(BatchDownloadController.openRequests) {
        if (BatchDownloadController.openRequests > 0 && userSettingsController.passedInit) {
            navController.navigate(ScreenBatchProgress) { launchSingleTop = true }
        }
    }

    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = if (userSettingsController.passedInit) ScreenHome else InitScreen,
        ) {
            animatedComposable<InitScreen> {
                InitScreen(
                    navController = navController,
                    viewModel = viewModel {
                        InitScreenViewModel(userSettingsController)
                    },
                )
            }
            animatedComposable<ScreenHome> {
                HomeScreen(
                    navController = navController,
                    viewModel = viewModel {
                        HomeViewModel(userSettingsController, lyricsProviderService)
                    },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this,
                )
            }

            animatedComposable<LyricsFetchScreen>() {
                val args = it.toRoute<LyricsFetchScreen>()

                LyricsFetchScreen(
                    viewModel = viewModel {
                        LyricsFetchViewModel(
                            args.source(),
                            userSettingsController,
                            lyricsProviderService
                        )
                    },
                    navController = navController,
                    animatedVisibilityScope = this,
                )
            }
            animatedComposable<ScreenBatchOptions> { entry ->
                // The options page operates on the Home screen's selection/filter state, so it reuses the
                // HomeViewModel scoped to the Home back-stack entry instead of creating its own.
                val homeEntry = remember(entry) { navController.getBackStackEntry(ScreenHome) }
                BatchOptionsScreen(
                    viewModel = viewModel(viewModelStoreOwner = homeEntry) {
                        HomeViewModel(userSettingsController, lyricsProviderService)
                    },
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToProgress = {
                        // Replace the options page with the progress view, so Back from progress returns Home.
                        navController.navigate(ScreenBatchProgress) {
                            popUpTo<ScreenHome>()
                        }
                    },
                )
            }
            animatedComposable<ScreenBatchProgress> {
                BatchProgressScreen(onNavigateBack = { navController.popBackStack() })
            }
            animatedComposable<ScreenSettings> {
                SettingsScreen(
                    viewModel = viewModel { SettingsViewModel() },
                    userSettingsController,
                    navController = navController
                )
            }
            animatedComposable<PlayerScreen> {
                val args = it.toRoute<PlayerScreen>()
                SyncedLyricsPlayerScreen(
                    filePath = args.filePath,
                    songName = args.songName,
                    artists = args.artists,
                    initialLyrics = args.lyrics,
                    onRequestCovers = { lyricsProviderService.getCoverCandidates(args.songName, args.artists, args.filePath) },
                    // Preview mode (arrived from "Adjust timing & preview") should return to Home after Apply/Back,
                    // not to the intermediate search result screen that launched it.
                    onBack = {
                        if (args.lyrics != null) navController.popBackStack(navController.graph.startDestinationId, false)
                        else navController.popBackStack()
                    },
                    onFindOnline = {
                        navController.navigate(
                            LyricsFetchScreen(
                                songName = args.songName,
                                artists = args.artists,
                                filePath = args.filePath
                            )
                        )
                    }
                )
            }
        }
    }
}

@Serializable
object InitScreen

@Serializable
object ScreenHome

@Serializable
data class LyricsFetchScreen(
    private val songName: String? = null,
    private val artists: String? = null,
    private val coverUri: String? = null,
    private val filePath: String? = null,
) {
    fun source() = if (songName != null && artists != null && filePath != null) {
        LocalSong(songName, artists, coverUri, filePath)
    } else null
}

@Serializable
data class LocalSong(
    val songName: String,
    val artists: String,
    val coverUri: String?,
    val filePath: String,
)

@Serializable
object ScreenSettings

@Serializable
object ScreenBatchOptions

@Serializable
object ScreenBatchProgress

@Serializable
data class PlayerScreen(
    val filePath: String,
    val songName: String,
    val artists: String,
    /** Optional unsaved lyrics passed from "Adjust timing" — the player writes them only on the checkmark. */
    val lyrics: String? = null,
)