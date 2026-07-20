package pl.lambada.songsync.ui.screens.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import pl.lambada.songsync.R
import pl.lambada.songsync.ui.components.SwitchItem


@Composable
fun FastModeSwitch(selected: Boolean, onToggle: (Boolean) -> Unit) {
    SwitchItem(
        label = stringResource(R.string.fast_mode),
        description = stringResource(R.string.fast_mode_desc),
        selected = selected,
        onClick = { onToggle(!selected) }
    )
}
