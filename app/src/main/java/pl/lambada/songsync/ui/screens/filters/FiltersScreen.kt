package pl.lambada.songsync.ui.screens.filters

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.lambada.songsync.R
import pl.lambada.songsync.ui.components.SettingsHeadLabel
import pl.lambada.songsync.ui.components.SettingsIconTile
import pl.lambada.songsync.ui.components.SwitchItem
import pl.lambada.songsync.ui.screens.home.HomeViewModel
import pl.lambada.songsync.ui.screens.home.components.FolderPickerDialog
import pl.lambada.songsync.util.folders.FolderFilter
import pl.lambada.songsync.util.folders.FolderFilterMode

/**
 * Full-screen Home filters page (replaces the old Filters popup). Every change applies to the Home list right
 * away, through the same HomeViewModel calls the popup used; there is no Apply button. The folder picker opens on
 * top of this page, hidden songs get their own page ([HiddenSongsScreen]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltersScreen(
    viewModel: HomeViewModel,
    onNavigateBack: () -> Unit,
    onOpenHiddenSongs: () -> Unit,
) {
    val context = LocalContext.current
    val settings = viewModel.userSettingsController
    // Cached in the view model after the first call, so this is cheap on recomposition.
    val folderSongCounts = viewModel.getFolderSongCounts(context)
    val hiddenCount = settings.hiddenSongs.size
    var showFolders by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            MediumTopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                title = { Text(modifier = Modifier.padding(start = 6.dp), text = stringResource(R.string.filters)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = paddingValues) {
            item { SettingsHeadLabel(label = stringResource(R.string.filters_section_lyrics)) }
            item {
                SwitchItem(
                    label = stringResource(R.string.no_lyrics_only),
                    description = stringResource(R.string.no_lyrics_only_desc),
                    icon = Icons.Outlined.Lyrics,
                    selected = settings.hideLyrics,
                ) {
                    viewModel.onHideLyricsChange(!settings.hideLyrics)
                    viewModel.filterSongs()
                }
            }

            item { SettingsHeadLabel(label = stringResource(R.string.filters_section_library)) }
            item {
                FilterNavigationRow(
                    icon = Icons.Outlined.Folder,
                    title = stringResource(R.string.folders),
                    summary = folderSummary(folderSongCounts, settings.folderFilter),
                    onClick = { showFolders = true },
                )
            }
            item {
                FilterNavigationRow(
                    icon = Icons.Outlined.VisibilityOff,
                    title = stringResource(R.string.hidden_songs),
                    summary = if (hiddenCount == 0) stringResource(R.string.hidden_songs_how_to_hide)
                    else pluralStringResource(R.plurals.songs_hidden, hiddenCount, hiddenCount),
                    onClick = onOpenHiddenSongs,
                )
            }
        }
    }

    if (showFolders) {
        FolderPickerDialog(
            folderSongCounts = folderSongCounts,
            folderFilter = settings.folderFilter,
            onSetFolderVisible = viewModel::setFolderVisible,
            onSetAllFoldersVisible = viewModel::setAllFoldersVisible,
            onModeChange = viewModel::setFolderFilterMode,
            onDismiss = { showFolders = false },
        )
    }
}

@Composable
private fun folderSummary(folderSongCounts: Map<String, Int>, folderFilter: FolderFilter): String {
    val total = folderSongCounts.size
    val shown = folderSongCounts.keys.count { folderFilter.isVisible(it) }
    return when {
        total == 0 -> stringResource(R.string.no_folders_found)
        folderFilter.mode == FolderFilterMode.ONLY_CHECKED ->
            stringResource(R.string.folders_only_checked_summary, shown, total)
        shown == total -> pluralStringResource(R.plurals.folders_all_shown, total, total)
        else -> stringResource(R.string.folders_shown_summary, shown, total)
    }
}

/** A settings-style row that opens another page: icon tile, title, one-line summary and a chevron. */
@Composable
private fun FilterNavigationRow(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIconTile(icon = icon)
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title)
            Text(
                text = summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
