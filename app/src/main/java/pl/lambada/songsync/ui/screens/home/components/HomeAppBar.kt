package pl.lambada.songsync.ui.screens.home.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeAppBar(
    showing: Boolean,
    scrollBehavior: TopAppBarScrollBehavior,
    onSelectedClearAction: () -> Unit,
    onNavigateToSettingsSectionRequest: () -> Unit,
    onBatchDownloadRequest: () -> Unit,
    onSelectAllSongsRequest: () -> Unit,
    onInvertSongSelectionRequest: () -> Unit,
    cachedSize: Int,
    onHideSelectedRequest: (() -> Unit)? = null,
) {
    MediumTopAppBar(
        navigationIcon = {
            if (showing) {
                IconButton(onClick = onSelectedClearAction) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.back)
                    )
                }
            }
        },
        title = {
            if (showing) {
                // The count rolls up or down as songs are picked, instead of the whole title blinking.
                AnimatedContent(
                    targetState = cachedSize,
                    transitionSpec = {
                        val up = targetState > initialState
                        (slideInVertically { if (up) it else -it } + fadeIn()) togetherWith
                            (slideOutVertically { if (up) -it else it } + fadeOut()) using SizeTransform(clip = false)
                    },
                    label = "selectedCount",
                ) { size ->
                    Text(
                        modifier = Modifier.padding(start = 6.dp),
                        text = stringResource(id = R.string.selected_count, size),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            } else {
                Text(
                    modifier = Modifier.padding(start = 6.dp),
                    text = stringResource(R.string.app_name)
                )
            }
        },
        actions = {
            if (showing) {
                SelectionActions(
                    onSelectAll = {
                        onSelectedClearAction()
                        onSelectAllSongsRequest()
                    },
                    onInvert = onInvertSongSelectionRequest,
                    onHide = onHideSelectedRequest,
                    onBatchDownload = onBatchDownloadRequest,
                )
            } else {
                HomeTopAppBarDropDown(
                    onNavigateToSettingsSectionRequest = onNavigateToSettingsSectionRequest,
                )
            }
        },
        // Both modes sit on the page background, so picking songs doesn't drop a grey slab over the list.
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            scrolledContainerColor = MaterialTheme.colorScheme.surface,
        ),
        scrollBehavior = scrollBehavior,
    )
}

/**
 * The selection actions as one rounded group: select all, invert and hide, then batch download for the picked songs
 * as the highlighted button (it used to sit alone in an overflow menu).
 */
@Composable
private fun SelectionActions(
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
    onHide: (() -> Unit)?,
    onBatchDownload: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Filled.SelectAll, contentDescription = stringResource(R.string.select_all))
            }
            IconButton(onClick = onInvert) {
                Icon(Icons.Filled.Deselect, contentDescription = stringResource(R.string.invert_selection))
            }
            // Issue #18: hide the selected song(s) from the list.
            if (onHide != null) {
                IconButton(onClick = onHide) {
                    Icon(Icons.Outlined.VisibilityOff, contentDescription = stringResource(R.string.hide_from_list))
                }
            }
            FilledIconButton(onClick = onBatchDownload, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Download,
                    contentDescription = stringResource(R.string.batch_download_lyrics),
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}
