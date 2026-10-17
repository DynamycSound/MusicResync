package pl.lambada.songsync.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.IconButtonColors
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import pl.lambada.songsync.nowplaying.ArtworkPalette

/** The artwork colours a page passes on to its scrubber and play button. */
data class ArtworkColors(val vivid: Color, val normal: Color)

/**
 * A page tinted by its song's artwork: a gradient from the artwork's colour behind everything, and the artwork
 * colour standing in for the theme accent inside (titles, icons, spinner, tonal buttons). On a light theme the dark
 * shade is used for text so it stays readable. A null [palette] (greyscale or no artwork) falls back to the theme,
 * and colours fade over when the song changes.
 */
@Composable
fun ArtworkBackdrop(palette: ArtworkPalette?, content: @Composable (ArtworkColors) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val vivid by animateColorAsState(palette?.let { Color(it.vivid) } ?: scheme.primary, tween(700), label = "vivid")
    val normal by animateColorAsState(palette?.let { Color(it.normal) } ?: scheme.tertiary, tween(700), label = "normal")
    val deep by animateColorAsState(palette?.let { Color(it.deep) } ?: scheme.primaryContainer, tween(700), label = "deep")
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.surface)
            .background(
                if (dark) Brush.verticalGradient(
                    0f to normal.copy(alpha = 0.7f),
                    0.4f to deep.copy(alpha = 0.55f),
                    1f to deep.copy(alpha = 0.3f),
                ) else Brush.verticalGradient(
                    0f to normal.copy(alpha = 0.45f),
                    0.5f to normal.copy(alpha = 0.14f),
                    1f to vivid.copy(alpha = 0.06f),
                )
            )
    ) {
        MaterialTheme(
            colorScheme = scheme.copy(
                primary = if (dark) lerp(vivid, Color.White, 0.2f) else deep,
                secondaryContainer = lerp(scheme.surfaceContainerHigh, normal, 0.35f),
                onSecondaryContainer = scheme.onSurface,
            ),
        ) {
            content(ArtworkColors(vivid, normal))
        }
    }
}

/** The big play button in the artwork colour, with a readable icon on top. */
@Composable
fun artworkPlayButtonColors(accent: Color): IconButtonColors = IconButtonDefaults.filledIconButtonColors(
    containerColor = accent,
    contentColor = if (accent.luminance() > 0.5f) Color.Black else Color.White,
)

/** The artwork's colours (see [ArtworkPalette]). Null for greyscale art or a bitmap that can't be read in software. */
fun artworkPalette(bitmap: Bitmap): ArtworkPalette? = try {
    val readable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && bitmap.config == Bitmap.Config.HARDWARE) {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
    } else bitmap
    val small = Bitmap.createScaledBitmap(readable, 48, 48, true)
    val pixels = IntArray(48 * 48).also { small.getPixels(it, 0, 48, 0, 0, 48, 48) }
    ArtworkPalette.from(pixels)
} catch (e: IllegalArgumentException) {
    null // recycled or unreadable bitmap: the page uses the theme colour instead
} catch (e: IllegalStateException) {
    null
}

/**
 * A small bitmap of an embedded cover image ([bytes] as stored in the tags), decoded at a fraction of its size since
 * it's only shown as a thumbnail and read for colours. Null when the bytes aren't an image.
 */
fun decodeCoverThumbnail(bytes: ByteArray, targetPx: Int = 256): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}
