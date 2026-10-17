package pl.lambada.songsync.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.R
import pl.lambada.songsync.ui.components.SwitchItem
import pl.lambada.songsync.util.folders.FolderFilter
import pl.lambada.songsync.util.folders.FolderFilterMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FiltersDialog(
    hideLyrics: Boolean,
    folderSongCounts: Map<String, Int>,
    folderFilter: FolderFilter,
    onDismiss: () -> Unit,
    onFilterChange: () -> Unit,
    onHideLyricsChange: (Boolean) -> Unit,
    onSetFolderVisible: (String, Boolean) -> Unit,
    onSetAllFoldersVisible: (Boolean) -> Unit,
    onFolderFilterModeChange: (FolderFilterMode) -> Unit,
) {
    var showFolders by rememberSaveable { mutableStateOf(false) }

    BasicAlertDialog(onDismissRequest = { onDismiss() }) {
        Surface(
            modifier = Modifier
                .wrapContentWidth()
                .wrapContentHeight(),
            shape = MaterialTheme.shapes.large,
            color = AlertDialogDefaults.containerColor,
            tonalElevation = AlertDialogDefaults.TonalElevation
        ) {
            Column {
                Text(
                    text = stringResource(R.string.filters),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 24.dp, start = 24.dp, bottom = 16.dp)
                )
                SwitchItem(
                    label = stringResource(R.string.no_lyrics_only),
                    selected = hideLyrics,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(20f)),
                    innerPaddingValues = PaddingValues(
                        top = 8.dp,
                        start = 8.dp,
                        end = 10.dp,
                        bottom = 8.dp
                    )
                ) {
                    onHideLyricsChange(!hideLyrics)
                    onFilterChange()
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .padding(start = 24.dp, end = 26.dp)
                        .clip(RoundedCornerShape(100))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable { showFolders = true }
                        .padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.folders))
                        Text(
                            text = stringResource(
                                R.string.folders_shown_summary,
                                folderSongCounts.keys.count { folderFilter.isVisible(it) },
                                folderSongCounts.size
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { showFolders = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                        )
                    }
                }
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = { onDismiss() }
                    ) {
                        Text(stringResource(R.string.close))
                    }
                }
            }
        }
    }

    if (showFolders) {
        FolderPickerDialog(
            folderSongCounts = folderSongCounts,
            folderFilter = folderFilter,
            onSetFolderVisible = onSetFolderVisible,
            onSetAllFoldersVisible = onSetAllFoldersVisible,
            onModeChange = onFolderFilterModeChange,
            onDismiss = { showFolders = false },
        )
    }
}
