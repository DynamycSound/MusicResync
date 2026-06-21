package pl.lambada.songsync.ui.screens.home.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import pl.lambada.songsync.R
import pl.lambada.songsync.domain.model.Song
import pl.lambada.songsync.ui.components.AnimatedText
import pl.lambada.songsync.util.matching.LyricState


@OptIn(ExperimentalFoundationApi::class, ExperimentalSharedTransitionApi::class)
@Composable
fun SongItem(
    filePath: String,
    selected: Boolean,
    quickSelect: Boolean,
    onSelectionChanged: (Boolean) -> Unit,
    onNavigateToSongRequest: () -> Unit,
    song: Song,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    disableMarquee: Boolean = true,
    showPath: Boolean,
    lyricState: LyricState = LyricState.NO_LYRICS,
    confidencePercent: Int? = null,
) {
    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(LocalContext.current).data(data = song.imgUri).apply {
            placeholder(R.drawable.ic_song)
            error(R.drawable.ic_song)
        }.build(), imageLoader = LocalContext.current.imageLoader
    )
    val songName = song.title ?: stringResource(id = R.string.unknown)
    val artists = song.artist ?: stringResource(id = R.string.unknown)
    val bgColor = if (selected) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (showPath) 100.dp else 80.dp)
            .background(bgColor)
            .combinedClickable(
                onClick = {
                    if (quickSelect)
                        onSelectionChanged(!selected)
                    else
                        onNavigateToSongRequest()
                },
                onLongClick = { onSelectionChanged(!selected) }
            )
            .padding(vertical = 12.dp, horizontal = 24.dp)
    ) {
        with(sharedTransitionScope) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxHeight(if (showPath) 0.7f else 1f),
                ) {
                    Image(
                        painter = painter,
                        contentDescription = stringResource(id = R.string.album_cover),
                        modifier = Modifier
                            .sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "cover$filePath"),
                                animatedVisibilityScope = animatedVisibilityScope,
                                clipInOverlayDuringTransition = sharedTransitionScope.OverlayClip(
                                    RoundedCornerShape(20f)
                                )
                            )
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(20f))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.fillMaxHeight(),
                        verticalArrangement = Arrangement.SpaceAround
                    ) {
                        AnimatedText(
                            animate = !disableMarquee,
                            text = songName,
                            fontSize = 16.sp,
                            color = MaterialTheme.colorScheme.contentColorFor(bgColor),
                            modifier = Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "title$filePath"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        )
                        AnimatedText(
                            animate = !disableMarquee,
                            text = artists,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.contentColorFor(bgColor),
                            modifier = Modifier.sharedBounds(
                                sharedContentState = rememberSharedContentState(key = "artist$filePath"),
                                animatedVisibilityScope = animatedVisibilityScope
                            )
                        )
                    }
                }
                if (showPath) {
                    Spacer(modifier = Modifier.height(4.dp))
                    AnimatedText(
                        animate = !disableMarquee,
                        text = filePath.replace(".nowplaying", ""),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.contentColorFor(bgColor),
                        modifier = Modifier.sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "path$filePath"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    )
                }
            }
        }

        LyricStatusBadge(
            state = lyricState,
            confidencePercent = confidencePercent,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
    }
}

/**
 * Compact status chip on the trailing edge of a song row: a colour-coded dot (green = synced, amber = verify,
 * red = missing) plus the confidence percentage when known. Animates colour changes as a song moves between
 * states during a batch run.
 */
@Composable
fun LyricStatusBadge(
    state: LyricState,
    confidencePercent: Int?,
    modifier: Modifier = Modifier,
) {
    if (state == LyricState.FETCHING) {
        CircularProgressIndicator(
            modifier = modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        return
    }

    val (dot, label) = when (state) {
        LyricState.SYNCED, LyricState.HAS_LYRICS -> Color(0xFF2E7D32) to "✓"
        LyricState.REVIEW -> Color(0xFFF9A825) to "⚠"
        LyricState.UNSYNCED -> Color(0xFF9E9E9E) to "≈"
        LyricState.NO_LYRICS -> Color(0xFFC62828) to "✗"
        LyricState.FAILED -> Color(0xFFC62828) to "!"
        LyricState.FETCHING -> Color(0xFF9E9E9E) to ""
    }
    val animatedDot by animateColorAsState(dot, label = "badgeColor")

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(animatedDot.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(50))
                .background(animatedDot)
        )
        Text(
            text = confidencePercent?.let { "$it%" } ?: label,
            fontSize = 12.sp,
            color = animatedDot
        )
    }
}