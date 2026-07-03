package pl.lambada.songsync.ui.screens.home

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults.Indicator
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import pl.lambada.songsync.R
import pl.lambada.songsync.domain.model.Song
import pl.lambada.songsync.ui.LyricsFetchScreen
import pl.lambada.songsync.ui.ScreenSettings
import pl.lambada.songsync.ui.screens.home.components.BatchDownloadLyrics
import pl.lambada.songsync.ui.screens.home.components.FilterAndSongCount
import pl.lambada.songsync.ui.screens.home.components.FiltersDialog
import pl.lambada.songsync.ui.screens.home.components.HomeAppBar
import pl.lambada.songsync.ui.screens.home.components.HomeSearchBar
import pl.lambada.songsync.ui.screens.home.components.HomeSearchThing
import pl.lambada.songsync.ui.screens.home.components.LyricsTabRow
import pl.lambada.songsync.ui.screens.home.components.SongItem
import pl.lambada.songsync.ui.screens.home.components.SortDialog
import pl.lambada.songsync.util.ext.BackPressHandler
import pl.lambada.songsync.util.ext.lowercaseWithLocale
import pl.lambada.songsync.util.ui.SearchFABBoundsTransform

/**
 * Composable function representing the home screen.
 *
 * @param viewModel The [HomeViewModel] instance.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun HomeScreen(
    navController: NavHostController,
    viewModel: HomeViewModel = viewModel(),
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var isBatchDownload by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // ensureSongsLoaded (not a raw reload): this effect re-fires every time Home re-enters composition (back
    // gesture from Settings/fetch/player), and unconditionally dropping the cache here re-ran the full
    // MediaStore query + disk re-scan on every back navigation, freezing the UI on large libraries.
    LaunchedEffect(viewModel.userSettingsController.sortBy to viewModel.userSettingsController.sortOrder) {
        viewModel.ensureSongsLoaded(context, viewModel.userSettingsController.sortBy, viewModel.userSettingsController.sortOrder)
    }

    // Re-derive row colours from disk whenever Home comes back to the foreground, so a song lyric'd on the
    // fetch screen (or a .lrc added/removed outside the app) is reflected without a manual refresh.
    LifecycleResumeEffect(Unit) {
        // Fast path first: reflect anything the player/fetch screen just wrote to the cache (e.g. lyrics removed
        // -> red note, offset saved -> green) with no disk scan, then verify against disk in the background.
        viewModel.reseedFromCache()
        viewModel.refreshLyricStatesFromDisk()
        onPauseOrDispose { }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            var cachedSize by remember { mutableIntStateOf(1) }
            if (viewModel.selectedSongs.size > 0) {
                // keep displaying "1 selected" during fade-out, don't say "0 selected"
                cachedSize = viewModel.selectedSongs.size
            }

            BackPressHandler(
                enabled = viewModel.selectedSongs.size > 0,
                onBackPressed = { viewModel.selectedSongs.clear() }
            )

            Crossfade(
                targetState = viewModel.selectedSongs.size > 0,
                label = ""
            ) { showing ->
                HomeAppBar(
                    showing = showing,
                    scrollBehavior = scrollBehavior,
                    onSelectedClearAction = viewModel.selectedSongs::clear,
                    onNavigateToSettingsSectionRequest = { navController.navigate(ScreenSettings) },
                    onProviderSelectRequest = viewModel.userSettingsController::updateSelectedProviders,
                    onBatchDownloadRequest = { isBatchDownload = true },
                    selectedProvider = viewModel.userSettingsController.selectedProvider,
                    onSelectAllSongsRequest = viewModel::selectAllDisplayingSongs,
                    onInvertSongSelectionRequest = viewModel::invertSongSelection,
                    cachedSize = cachedSize
                )
            }
        },
        // The standalone "empty search" FAB was removed: every song row already routes to search/play on tap,
        // so a blank manual-search entry point is redundant.
        bottomBar = { Spacer(Modifier.navigationBarsPadding()) } // fixing broken edge to edge here
    ) { paddingValues ->
        Crossfade(viewModel.allSongs == null || viewModel.waitingForInitialLyricScan, label = "") { loading ->
            if (loading)
                LoadingScreen()
            else
                HomeScreenLoaded(
                    navController = navController,
                    viewModel = viewModel,
                    selected = viewModel.selectedSongs,
                    scaffoldPadding = paddingValues,
                    isBatchDownload = isBatchDownload,
                    onBatchDownloadState = { onBatchDownload -> isBatchDownload = onBatchDownload },
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope
                )
        }
    }
}

/**
 * Composable function representing the loading screen.
 */
@Composable
fun LoadingScreen() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.please_wait),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreenLoaded(
    selected: SnapshotStateList<String>,
    navController: NavHostController,
    viewModel: HomeViewModel,
    scaffoldPadding: PaddingValues,
    isBatchDownload: Boolean,
    onBatchDownloadState: (isBatchDownload: Boolean) -> Unit,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val refreshState = rememberPullToRefreshState()

    if (isBatchDownload) {
        BatchDownloadLyrics(
            viewModel = viewModel,
            onDone = { onBatchDownloadState(false) })
    }

    Box(modifier = Modifier.fillMaxSize()) {
    PullToRefreshBox(
        isRefreshing = viewModel.isRefreshing,
        state = refreshState,
        onRefresh = {
            viewModel.isRefreshing = true
            scope.launch {
                viewModel.cachedSongs = null
                // Tie the spinner to the actual reload (MediaStore query + disk lyric re-scan) by joining the
                // job, instead of a fixed delay(1000) that lied about completion.
                viewModel.updateAllSongs(context, viewModel.userSettingsController.sortBy, viewModel.userSettingsController.sortOrder).join()
                viewModel.isRefreshing = false
            }
        },
        indicator = {
            Indicator(
                state = refreshState,
                isRefreshing = viewModel.isRefreshing,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = scaffoldPadding.calculateTopPadding()),
            )
        }
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = scaffoldPadding
        ) {
            item {
                Column(
                    modifier = Modifier.padding(
                        top = 5.dp,
                        bottom = 5.dp,
                        start = 22.dp,
                        end = 4.dp
                    ),
                ) {
                    HomeSearchThing(
                        showingSearch = viewModel.showingSearch,
                        searchBar = {
                            HomeSearchBar(
                                query = viewModel.searchQuery,
                                onQueryChange = { newQuery ->
                                    viewModel.searchQuery = newQuery
                                    viewModel.updateSearchResults(newQuery.lowercaseWithLocale())
                                    viewModel.showingSearch = true
                                },
                                showSearch = viewModel.showSearch,
                                onShowSearchChange = { viewModel.showSearch = it },
                                showingSearch = viewModel.showingSearch,
                                onShowingSearchChange = { viewModel.showingSearch = it }
                            )
                        },
                        filterBar = {
                            FilterAndSongCount(
                                displaySongsCount = viewModel.displaySongs.size,
                                onFilterClick = { viewModel.showFilters = true },
                                onSortClick = { viewModel.showSort = true },
                                onSearchClick = {
                                    viewModel.showSearch = true
                                    viewModel.showingSearch = true
                                }
                            )
                        }
                    )

                    if (viewModel.showSort) {
                        SortDialog(
                            userSettingsController = viewModel.userSettingsController,
                            onDismiss = { viewModel.showSort = false },
                            onSortOrderChange = {
                                viewModel.userSettingsController.updateSortOrder(
                                    it
                                )
                            },
                            onSortByChange = { viewModel.userSettingsController.updateSortBy(it) }
                        )
                    }

                    if (viewModel.showFilters) {
                        FiltersDialog(
                            hideLyrics = viewModel.userSettingsController.hideLyrics,
                            folders = viewModel.getSongFolders(context),
                            blacklistedFolders = viewModel.userSettingsController.blacklistedFolders,
                            onDismiss = { viewModel.showFilters = false },
                            onFilterChange = { viewModel.filterSongs() },
                            onHideLyricsChange = viewModel::onHideLyricsChange,
                            onToggleFolderBlacklist = viewModel::onToggleFolderBlacklist
                        )
                    }
                }
            }

            item {
                LyricsTabRow(
                    selected = viewModel.selectedTab,
                    countFor = viewModel::countFor,
                    onSelect = { viewModel.selectedTab = it }
                )
            }

            val tabSongs = viewModel.tabFilteredSongs
            items(tabSongs.size) { index ->
                val song = tabSongs[index]

                SongItem(
                    filePath = song.filePath
                        ?: error("a song in the list of files did not have a file path"),
                    selected = selected.contains(song.filePath),
                    quickSelect = selected.size > 0,
                    onSelectionChanged = { newValue ->
                        viewModel.selectSong(song, newValue)
                    },
                    onNavigateToSongRequest = {
                        val hasLyrics = viewModel.lyricStateFor(song).let {
                            it == pl.lambada.songsync.util.matching.LyricState.HAS_LYRICS ||
                            it == pl.lambada.songsync.util.matching.LyricState.SYNCED ||
                            it == pl.lambada.songsync.util.matching.LyricState.REVIEW
                        }
                        if (hasLyrics) {
                            // Has lyrics already -> open the synced-lyrics player to view/tune offset, not re-fetch.
                            navController.navigate(
                                pl.lambada.songsync.ui.PlayerScreen(
                                    filePath = song.filePath,
                                    songName = song.title ?: "",
                                    artists = song.artist ?: ""
                                )
                            )
                        } else {
                            navController.navigate(
                                LyricsFetchScreen(
                                    songName = song.title ?: error("song.title was null"),
                                    artists = song.artist ?: "",
                                    coverUri = song.imgUri.toString(),
                                    filePath = song.filePath
                                )
                            )
                        }
                    },
                    song = song,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    disableMarquee = viewModel.userSettingsController.disableMarquee,
                    showPath = viewModel.userSettingsController.showPath,
                    lyricState = viewModel.lyricStateFor(song),
                    confidencePercent = viewModel.songMatchStatus[song.filePath]?.confidencePercent,
                    onPlay = if (viewModel.lyricStateFor(song).let {
                            it == pl.lambada.songsync.util.matching.LyricState.HAS_LYRICS ||
                            it == pl.lambada.songsync.util.matching.LyricState.SYNCED ||
                            it == pl.lambada.songsync.util.matching.LyricState.REVIEW
                        }) {
                        {
                            navController.navigate(
                                pl.lambada.songsync.ui.PlayerScreen(
                                    filePath = song.filePath,
                                    songName = song.title ?: "",
                                    artists = song.artist ?: ""
                                )
                            )
                        }
                    } else null,
                )
            }

            item { Spacer(modifier = Modifier.height(96.dp)) } // room for the batch FAB
        }
    }

        // Primary action: one tap opens the smart batch (fallback ladder + correct-metadata option). Hidden
        // on the "Has Lyrics" tab, where there is nothing left to fetch.
        if (viewModel.selectedTab != LyricsTab.HAS_LYRICS) {
            ExtendedFloatingActionButton(
                onClick = { onBatchDownloadState(true) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp),
                icon = { Icon(Icons.Default.LibraryMusic, contentDescription = null) },
                text = { Text(stringResource(id = R.string.batch_download_lyrics)) },
            )
        }
    }
}