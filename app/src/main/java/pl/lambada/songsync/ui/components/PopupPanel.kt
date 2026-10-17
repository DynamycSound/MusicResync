package pl.lambada.songsync.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.lambada.songsync.R
import kotlin.math.abs
import kotlin.math.roundToInt

enum class PanelSide { Start, End }

/**
 * A small card that pops up from the bottom corner of a lyrics view, above the button at that end of the player
 * controls that opened it. Keeps rarely used controls (timing, lyrics source) off the lyrics until they are wanted.
 * Tapping outside the card closes it.
 */
@Composable
fun BoxScope.PopupPanel(
    side: PanelSide,
    visible: Boolean,
    onDismiss: () -> Unit,
    icon: ImageVector,
    title: String,
    headerAction: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val start = side == PanelSide.Start
    if (visible) {
        // Catches taps outside the card to close it, without the ripple a normal click would draw.
        Box(
            Modifier
                .matchParentSize()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )
    }
    val origin = TransformOrigin(if (start) 0.1f else 0.9f, 1f)
    AnimatedVisibility(
        visible = visible,
        enter = scaleIn(tween(220), initialScale = 0.85f, transformOrigin = origin) + fadeIn(tween(180)),
        exit = scaleOut(tween(160), targetScale = 0.9f, transformOrigin = origin) + fadeOut(tween(140)),
        modifier = Modifier
            .align(if (start) Alignment.BottomStart else Alignment.BottomEnd)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            modifier = Modifier.width(300.dp),
        ) {
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    headerAction?.invoke()
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                    }
                }
                Column(Modifier.padding(end = 12.dp)) { content() }
            }
        }
    }
}

/**
 * The button at either end of the player controls that opens a [PopupPanel]. Tonal while its panel is open or
 * [highlighted] (its setting is changed from the default); [badge] (e.g. the current offset) shows under the icon.
 */
@Composable
fun PanelButton(
    icon: ImageVector,
    contentDescription: String,
    open: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    highlighted: Boolean = badge != null,
    enabled: Boolean = true,
) {
    // The badge overlaps the button's lower edge instead of sitting under it, so the control row keeps its height
    // (and the play button stays put) when an offset appears.
    Box(modifier, contentAlignment = Alignment.Center) {
        if (open || highlighted) {
            FilledTonalIconButton(onClick = onClick, enabled = enabled) {
                Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
            }
        } else {
            IconButton(
                onClick = onClick,
                enabled = enabled,
                colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
            ) {
                Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
            }
        }
        if (badge != null) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 10.dp),
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** "+0.4s", "-1.2s", "0.0s". */
fun formatOffsetSeconds(ms: Float): String {
    val tenths = (ms / 100f).roundToInt()
    if (tenths == 0) return "0.0s"
    return (if (tenths > 0) "+" else "-") + "%d.%ds".format(abs(tenths) / 10, abs(tenths) % 10)
}

