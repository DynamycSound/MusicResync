package pl.lambada.songsync.ui.screens.filters

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import pl.lambada.songsync.R
import pl.lambada.songsync.domain.model.Song
import pl.lambada.songsync.ui.screens.home.HomeViewModel

/**
 * Full-screen list of the songs hidden from the Home list (issue #18), replacing the old popup. Each row looks
 * like a Home song row and has its own Unhide button; "Unhide all" sits in the top bar. When nothing is hidden
 * (or the last song is unhidden) the page shows an empty state explaining how to hide songs, instead of closing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HiddenSongsScreen(
    viewModel: HomeViewModel,
    onNavigateBack: () -> Unit,
) {
    val hidden = viewModel.userSettingsController.hiddenSongs
    // Re-resolved only when the hidden set changes (it matches paths against the whole library).
    val entries = remember(hidden) { viewModel.hiddenSongEntries() }
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
                title = {
                    Text(modifier = Modifier.padding(start = 6.dp), text = stringResource(R.string.hidden_songs))
                },
                actions = {
                    AnimatedVisibility(visible = entries.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                        TextButton(onClick = { viewModel.unhideSongs(entries.map { it.first }) }) {
                            Text(stringResource(R.string.unhide_all))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Crossfade(targetState = entries.isEmpty(), label = "hiddenSongsEmpty") { empty ->
            if (empty) {
                HiddenSongsEmptyState(modifier = Modifier.padding(paddingValues))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = paddingValues) {
                    item(key = "description") {
                        Text(
                            text = stringResource(R.string.hidden_songs_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
                        )
                    }
                    items(entries, key = { it.first }) { (path, song) ->
                        HiddenSongRow(
                            path = path,
                            song = song,
                            onUnhide = { viewModel.unhideSongs(listOf(path)) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }
}

/** One hidden song, laid out like a Home list row: cover, title, artist, and a trailing Unhide button. */
@Composable
private fun HiddenSongRow(
    path: String,
    song: Song?,
    onUnhide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cover = if (song != null) {
        rememberAsyncImagePainter(
            ImageRequest.Builder(LocalContext.current).data(data = song.imgUri).apply {
                placeholder(R.drawable.ic_song)
                error(R.drawable.ic_song)
            }.build(),
            imageLoader = LocalContext.current.imageLoader,
        )
    } else {
        painterResource(R.drawable.ic_song)
    }
    // A missing file has no tags left, so fall back to its file name.
    val title = if (song == null) path.substringAfterLast('/') else song.title ?: stringResource(R.string.unknown)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(80.dp)
            .padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = cover,
            contentDescription = stringResource(R.string.album_cover),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(20f)),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceAround,
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (song != null) song.artist ?: stringResource(R.string.unknown)
                else stringResource(R.string.song_not_in_library),
                fontSize = 14.sp,
                fontStyle = if (song == null) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        FilledTonalButton(
            onClick = onUnhide,
            contentPadding = PaddingValues(horizontal = 16.dp),
        ) {
            Text(stringResource(R.string.unhide))
        }
    }
}

@Composable
private fun HiddenSongsEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.VisibilityOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(52.dp),
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.no_hidden_songs),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.no_hidden_songs_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // Nudge the group slightly above centre so it doesn't feel like it sinks under the large top bar.
        Spacer(modifier = Modifier.height(48.dp))
    }
}
