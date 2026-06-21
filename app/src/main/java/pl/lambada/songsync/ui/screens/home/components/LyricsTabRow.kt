package pl.lambada.songsync.ui.screens.home.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import pl.lambada.songsync.ui.screens.home.LyricsTab

/**
 * A pill-style segmented control for the All / Has Lyrics / No Lyrics tabs, each showing a live count. Pinned
 * as a sticky header so it stays reachable while scrolling the song list. The selected segment animates its
 * background; counts animate their width as songs move between states during a batch run.
 */
@Composable
fun LyricsTabRow(
    selected: LyricsTab,
    countFor: (LyricsTab) -> Int,
    onSelect: (LyricsTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LyricsTab.entries.forEach { tab ->
            val isSelected = tab == selected
            val bg by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f),
                label = "tabBg"
            )
            val fg by animateColorAsState(
                if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "tabFg"
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp)
                    .animateContentSize(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "${stringResource(tab.titleRes)}  ${countFor(tab)}",
                    color = fg,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
