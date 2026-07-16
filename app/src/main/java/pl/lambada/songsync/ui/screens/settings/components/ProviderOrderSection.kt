package pl.lambada.songsync.ui.screens.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.R
import pl.lambada.songsync.util.Providers

/**
 * Settings section for the provider fallback chain (issue #6): every provider is listed in the order it will
 * be tried during a search/batch; the arrows rearrange the chain and the checkbox removes a provider from it.
 */
@Composable
fun ProviderOrderSection(
    order: List<Providers>,
    disabled: Set<Providers>,
    onMove: (Providers, Int) -> Unit,
    onToggle: (Providers, Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.provider_order_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
        )
        order.forEachIndexed { index, provider ->
            val enabled = provider !in disabled
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = { onToggle(provider, it) },
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "${index + 1}. ${provider.displayName}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    if (provider.hasWordByWord) {
                        Text(
                            text = stringResource(R.string.provider_word_by_word),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = { onMove(provider, -1) },
                    enabled = index > 0,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.move_up))
                }
                IconButton(
                    onClick = { onMove(provider, +1) },
                    enabled = index < order.lastIndex,
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.move_down))
                }
            }
        }
    }
}
