package pl.lambada.songsync.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.CombinedModifier
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import pl.lambada.songsync.R
import pl.lambada.songsync.util.openFileFromPath

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.SongCard(
    filePath: String?,
    animateText: Boolean,
    songName: String,
    artists: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    animatedVisibilityScope: AnimatedVisibilityScope,
) {
    val unknownArtistString = stringResource(R.string.unknown)
    val context = LocalContext.current

    OutlinedCard(
        shape = RoundedCornerShape(10.dp),
        modifier = CombinedModifier(
            outer = Modifier
                .fillMaxWidth()
                .clickable(filePath != null) {
                    openFileFromPath(context, filePath!!.replace(".nowplaying", ""))
                },
            inner = modifier
        )
    ) {
        // heightIn(min) instead of a hard height: the card keeps its 72dp baseline but can grow a touch when a
        // larger font scale needs it, so the artist line can't be clipped against a fixed bottom edge.
        Row(modifier = Modifier.heightIn(min = 72.dp)) {
            if (coverUrl != null) {
                val painter = rememberAsyncImagePainter(
                    ImageRequest.Builder(LocalContext.current).data(data = coverUrl)
                        .apply {
                            placeholder(R.drawable.ic_song)
                            error(R.drawable.ic_song)
                        }.build(),
                    imageLoader = LocalContext.current.imageLoader
                )
                Image(
                    painter = painter,
                    contentDescription = stringResource(R.string.album_cover),
                    modifier = Modifier
                        .sharedBounds(
                            sharedContentState = rememberSharedContentState(key = "cover$filePath"),
                            animatedVisibilityScope = animatedVisibilityScope,
                            clipInOverlayDuringTransition = OverlayClip(
                                RoundedCornerShape(
                                    topStart = 30f,
                                    bottomStart = 30f,
                                    topEnd = 0f,
                                    bottomEnd = 0f
                                )
                            )
                        )
                        .height(72.dp)
                        .aspectRatio(1f)
                        .clip(
                            RoundedCornerShape(
                                topStart = 30f,
                                bottomStart = 30f,
                                topEnd = 0f,
                                bottomEnd = 0f
                            )
                        )
                )
            }
            Spacer(modifier = Modifier.width(2.dp))
            Column(
                // weight(1f) gives the text the full remaining width so a long artist isn't clipped to a
                // narrow column (marquee/ellipsis then operates over the whole card width). Centre the two lines
                // vertically with a small fixed gap instead of pushing the artist flush to the bottom with a
                // weighted spacer, which used to clip the artist's descenders against the bottom padding.
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center
            ) {
                AnimatedText(
                    text = songName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    animate = animateText,
                    modifier = Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "title$filePath"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                AnimatedText(
                    text = artists.ifBlank { unknownArtistString },
                    fontSize = 14.sp,
                    animate = animateText,
                    modifier = Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(key = "artist$filePath"),
                        animatedVisibilityScope = animatedVisibilityScope
                    )
                )
            }
        }
    }
}