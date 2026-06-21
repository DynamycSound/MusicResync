package pl.lambada.songsync.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.lambada.songsync.R
import pl.lambada.songsync.data.remote.lyrics_providers.spotify.SpotifySecrets
import java.util.concurrent.TimeUnit

/**
 * Tells the user whether Spotify's rotating TOTP secrets are current, so they understand why the
 * Spotify provider might be failing, and lets them force a refresh. Secrets rotate roughly every two
 * days, so anything older than ~48h is flagged as possibly stale.
 */
@Composable
fun SpotifySecretsStatus() {
    val scope = rememberCoroutineScope()
    var fetchedAt by remember { mutableStateOf(SpotifySecrets.lastFetchedAt()) }
    var refreshing by remember { mutableStateOf(false) }

    val now = System.currentTimeMillis()
    val ageMs = fetchedAt?.let { now - it }
    val staleThreshold = TimeUnit.HOURS.toMillis(48)

    val statusText = when {
        ageMs == null -> stringResource(R.string.spotify_secrets_bundled)
        ageMs < staleThreshold -> stringResource(R.string.spotify_secrets_fresh, formatAgo(ageMs))
        else -> stringResource(R.string.spotify_secrets_stale, formatAgo(ageMs))
    }
    val statusColor = when {
        ageMs == null || ageMs >= staleThreshold -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }

    Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
        Text(
            text = stringResource(R.string.spotify_secrets_title),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.padding(top = 2.dp))
        Text(
            text = stringResource(R.string.spotify_secrets_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.padding(top = 6.dp))
        Row {
            Text(
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = statusColor,
                modifier = Modifier
                    .weight(1f)
                    .padding(top = 8.dp)
            )
            TextButton(
                enabled = !refreshing,
                onClick = {
                    refreshing = true
                    scope.launch {
                        withContext(Dispatchers.IO) { SpotifySecrets.refresh(force = true) }
                        fetchedAt = SpotifySecrets.lastFetchedAt()
                        refreshing = false
                    }
                }
            ) {
                Text(
                    stringResource(
                        if (refreshing) R.string.spotify_secrets_refreshing
                        else R.string.spotify_secrets_refresh
                    )
                )
            }
        }
    }
}

/** Compact "2h" / "3d" style age, falling back to minutes for very recent fetches. */
private fun formatAgo(ms: Long): String {
    val days = TimeUnit.MILLISECONDS.toDays(ms)
    if (days >= 1) return "${days}d"
    val hours = TimeUnit.MILLISECONDS.toHours(ms)
    if (hours >= 1) return "${hours}h"
    val minutes = TimeUnit.MILLISECONDS.toMinutes(ms)
    return "${minutes.coerceAtLeast(0)}m"
}
