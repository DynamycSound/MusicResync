package pl.lambada.songsync.ui.screens.settings.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.lambada.songsync.R

/**
 * Attribution for this fork: the current developer, and the original project (SongSync) this app is based on.
 * Each row links out — the developer to their profile, "Sync Music" to the original author.
 */
@Composable
fun ContributorsSection(uriHandler: UriHandler) {
    Column {
        ContributorRow(
            name = "DynamycSound",
            subtitle = stringResource(R.string.developer),
            url = "https://github.com/DynamycSound",
            uriHandler = uriHandler,
        )
        ContributorRow(
            name = "Sync Music",
            subtitle = stringResource(R.string.original_project),
            url = "https://github.com/Lambada10",
            uriHandler = uriHandler,
        )
    }
}

@Composable
private fun ContributorRow(name: String, subtitle: String, url: String, uriHandler: UriHandler) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { uriHandler.openUri(url) }
            .padding(horizontal = 22.dp, vertical = 16.dp)
    ) {
        Text(text = name)
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.outline,
            fontSize = 12.sp
        )
    }
}
