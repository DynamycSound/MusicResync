package pl.lambada.songsync.nowplaying

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lambada.songsync.R
import pl.lambada.songsync.domain.model.Song
import pl.lambada.songsync.ui.LyricsFetchScreen
import pl.lambada.songsync.ui.ScreenLiveLyrics
import pl.lambada.songsync.ui.components.SwitchItem
import pl.lambada.songsync.util.rememberPreference

/**
 * Optional "Now playing" entry at the top of Home (issue #19). Without notification access it is either nothing or,
 * once, a small dismissible card offering to enable it. With access it shows the track another app is playing;
 * tapping it opens live, scrolling lyrics for it, or the lyrics search when it is a library song still missing lyrics.
 *
 * @param librarySongs the loaded library, used to recognise a song a local player is playing.
 * @param hasLyrics whether a library song already has (synced) lyrics, as the Home list decides it.
 */
@Composable
fun NowPlayingHomeSection(
    librarySongs: List<Song>,
    hasLyrics: (Song) -> Boolean,
    navController: NavController,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by rememberPreference(nowPlayingEnabledKey, true)
    var promptDismissed by rememberPreference(nowPlayingPromptDismissedKey, false)

    // Re-checked on every resume: the user grants/revokes access in system settings and comes back.
    var granted by remember { mutableStateOf(NowPlayingAccess.isGranted(context)) }
    var askedForAccess by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        granted = NowPlayingAccess.isGranted(context)
        onPauseOrDispose { }
    }

    var track by remember { mutableStateOf<NowPlayingTrack?>(null) }
    val observing = enabled && granted
    if (observing) {
        // Only listen while Home is visible; the observer re-reads the current session on every start.
        LifecycleStartEffect(Unit) {
            val observer = NowPlayingObserver(context) { track = it }
            if (!observer.start()) granted = false
            onStopOrDispose { observer.stop() }
        }
    }

    AnimatedVisibility(
        visible = enabled && !granted && !promptDismissed,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        EnableNowPlayingCard(
            showRestrictedHint = askedForAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            onEnable = {
                askedForAccess = true
                NowPlayingAccess.openSettings(context)
            },
            onOpenAppInfo = { NowPlayingAccess.openAppInfo(context) },
            onDismiss = { promptDismissed = true },
        )
    }

    var opening by remember { mutableStateOf(false) }
    val shown = track.takeIf { observing }
    AnimatedVisibility(
        visible = shown != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        // Keep the last track while the exit animation runs.
        val current = remember { mutableStateOf(shown) }
        if (shown != null) current.value = shown
        current.value?.let { playing ->
            NowPlayingCard(
                track = playing,
                onClick = {
                    if (opening) return@NowPlayingCard
                    opening = true
                    scope.launch {
                        try {
                            openLyricsFor(playing, librarySongs, hasLyrics, navController)
                        } finally {
                            opening = false
                        }
                    }
                },
            )
        }
    }
}

/**
 * Routes a tap on the card. A library song without lyrics opens the lyrics search for that file, exactly like
 * tapping its row, so its lyrics can be saved. Everything else (streamed songs, videos, library songs that already
 * have lyrics) opens the live lyrics view, which follows the playing app and saves nothing.
 */
private suspend fun openLyricsFor(
    track: NowPlayingTrack,
    librarySongs: List<Song>,
    hasLyrics: (Song) -> Boolean,
    navController: NavController,
) {
    val query = NowPlayingLogic.searchQuery(track.title, track.artist)
    val local = withContext(Dispatchers.Default) {
        NowPlayingLogic.findLocalMatch(track.title, track.artist, track.durationMs, librarySongs)
            ?: NowPlayingLogic.findLocalMatch(query.title, query.artist, track.durationMs, librarySongs)
    }
    val localPath = local?.filePath
    if (local != null && localPath != null && !hasLyrics(local)) {
        navController.navigate(
            LyricsFetchScreen(
                songName = local.title ?: track.title,
                artists = local.artist ?: "",
                coverUri = local.imgUri?.toString(),
                filePath = localPath,
            )
        )
        return
    }
    navController.navigate(ScreenLiveLyrics)
}

@Composable
private fun NowPlayingCard(track: NowPlayingTrack, onClick: () -> Unit) {
    val label = track.appLabel?.let { stringResource(R.string.now_playing_in_app, it) }
        ?: stringResource(R.string.now_playing)
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track.artwork)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = track.artist ?: stringResource(R.string.unknown),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = stringResource(R.string.now_playing_get_lyrics),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun Artwork(artwork: Bitmap?) {
    val shape = RoundedCornerShape(10.dp)
    val image = remember(artwork) { artwork?.asImageBitmap() }
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = stringResource(R.string.album_cover),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .clip(shape),
        )
    } else {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EnableNowPlayingCard(
    showRestrictedHint: Boolean,
    onEnable: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onDismiss: () -> Unit,
) {
    OutlinedCard(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        text = stringResource(R.string.now_playing_prompt_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.now_playing_prompt_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showRestrictedHint) {
                Text(
                    text = stringResource(R.string.now_playing_restricted_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 36.dp, top = 6.dp, end = 8.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (showRestrictedHint) {
                    TextButton(onClick = onOpenAppInfo) { Text(stringResource(R.string.now_playing_app_info)) }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.now_playing_not_now)) }
                TextButton(onClick = onEnable) { Text(stringResource(R.string.now_playing_enable)) }
            }
        }
    }
}

/**
 * Settings > Now playing: the master switch. Turning it on asks for notification access if it is missing; turning
 * it off stops all session reading (access itself can only be revoked by the user in system settings).
 */
@Composable
fun NowPlayingSettingItem() {
    val context = LocalContext.current
    var enabled by rememberPreference(nowPlayingEnabledKey, true)
    var granted by remember { mutableStateOf(NowPlayingAccess.isGranted(context)) }
    var askedForAccess by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        granted = NowPlayingAccess.isGranted(context)
        onPauseOrDispose { }
    }

    val on = enabled && granted
    Column {
        SwitchItem(
            label = stringResource(R.string.now_playing_setting_title),
            description = if (enabled && !granted) stringResource(R.string.now_playing_setting_needs_access)
            else stringResource(R.string.now_playing_setting_description),
            selected = on,
            onClick = {
                if (on) {
                    enabled = false
                } else {
                    enabled = true
                    if (!granted) {
                        askedForAccess = true
                        NowPlayingAccess.openSettings(context)
                    }
                }
            },
        )
        if (askedForAccess && !granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Row(
                modifier = Modifier.padding(start = 22.dp, end = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.now_playing_restricted_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { NowPlayingAccess.openAppInfo(context) }) {
                    Text(stringResource(R.string.now_playing_app_info))
                }
            }
        }
    }
}
