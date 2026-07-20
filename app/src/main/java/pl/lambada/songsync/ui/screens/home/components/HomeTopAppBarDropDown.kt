package pl.lambada.songsync.ui.screens.home.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.R
import pl.lambada.songsync.ui.components.dropdown.AnimatedDropdownMenu

/**
 * The home screen's overflow menu. Only Settings lives here now (issue #12): batch download has its own
 * always-visible button at the bottom of the list, and the lyrics-provider order is configured in Settings —
 * duplicating both in this menu was redundant.
 */
@Composable
fun HomeTopAppBarDropDown(
    onNavigateToSettingsSectionRequest: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    IconButton(onClick = { expanded = true }) {
        Icon(
            imageVector = Icons.Default.MoreVert,
            contentDescription = "More"
        )
    }
    AnimatedDropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false }
    ) {
        Column {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(id = R.string.settings),
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                },
                onClick = {
                    expanded = false
                    onNavigateToSettingsSectionRequest()
                }
            )
        }
    }
}
