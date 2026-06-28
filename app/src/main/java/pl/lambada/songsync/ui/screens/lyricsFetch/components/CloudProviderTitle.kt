package pl.lambada.songsync.ui.screens.lyricsFetch.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.ui.screens.lyricsFetch.ProviderProbe
import pl.lambada.songsync.util.Providers

/**
 * The provider label next to the cloud icon. Tapping it opens a dropdown anchored right below the icon that
 * lists every provider with its result for this song — a green check if it had synced lyrics, a red X if it
 * was tried and had none, and a spinner while a tapped provider is being re-tried.
 */
@Composable
fun CloudProviderTitle(
    selectedProvider: Providers,
    probes: Map<Providers, ProviderProbe>,
    onRetryProvider: (Providers) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable { expanded = true }
                .padding(horizontal = 4.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Cloud,
                contentDescription = null,
                Modifier.padding(end = 5.dp)
            )
            Text(text = selectedProvider.displayName)
        }

        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Providers.entries.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.displayName) },
                    trailingIcon = { ProbeStatusIcon(probes[provider]) },
                    // Tapping re-tries just this provider; the menu stays open so the spinner -> result is visible.
                    onClick = { onRetryProvider(provider) }
                )
            }
        }
    }
}

@Composable
private fun ProbeStatusIcon(probe: ProviderProbe?) {
    when (probe) {
        ProviderProbe.LOADING -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        ProviderProbe.HAS_SYNCED -> Icon(
            Icons.Filled.Check, contentDescription = "Has lyrics",
            tint = Color(0xFF2E7D32), modifier = Modifier.size(20.dp)
        )
        ProviderProbe.NONE -> Icon(
            Icons.Filled.Close, contentDescription = "No lyrics",
            tint = Color(0xFFC62828), modifier = Modifier.size(20.dp)
        )
        ProviderProbe.UNTRIED -> Text(
            text = "?",
            color = Color(0xFF9E9E9E), // muted — "not tried yet", explicitly not a failure
        )
        null -> {} // no info yet — no marker
    }
}
