package pl.lambada.songsync.nowplaying

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Colours for the live lyrics page, taken from the song's artwork: [vivid] (fully saturated, for the scrubber,
 * thumb glow and play button), [normal] (the artwork's own tone, for the second wave layer and the page tint) and
 * [deep] (a dark shade for the lower part of the page gradient). All are ARGB ints.
 */
data class ArtworkPalette(val vivid: Int, val normal: Int, val deep: Int) {
    companion object {
        private const val HUE_BINS = 24

        /**
         * Picks the artwork's most prominent colourful hue: pixels are grouped by hue and weighted by how colourful
         * and bright they are, so a small bright logo on a grey photo still wins over the grey, while the plain
         * average of all pixels (which turns most artwork into mud) is never used. Null when the artwork is
         * greyscale, so the page falls back to the theme colour.
         */
        fun from(pixels: IntArray): ArtworkPalette? {
            val weight = DoubleArray(HUE_BINS)
            val sumS = DoubleArray(HUE_BINS)
            val sumV = DoubleArray(HUE_BINS)
            val hsv = FloatArray(3)
            for (pixel in pixels) {
                if ((pixel ushr 24) < 128) continue
                toHsv(pixel, hsv)
                val (h, s, v) = Triple(hsv[0], hsv[1], hsv[2])
                if (s < 0.25f || v < 0.2f) continue
                val bin = ((h / 360f) * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
                val w = (s * s * v).toDouble()
                weight[bin] += w
                sumS[bin] += s * w
                sumV[bin] += v * w
            }
            // Let neighbouring bins add up, so a hue split across a bin edge isn't outvoted.
            val smoothed = DoubleArray(HUE_BINS) { i ->
                weight[i] + 0.5 * (weight[(i + 1) % HUE_BINS] + weight[(i + HUE_BINS - 1) % HUE_BINS])
            }
            val best = smoothed.indices.maxByOrNull { smoothed[it] } ?: return null
            // Too little colour overall (a few stray pixels): treat as greyscale.
            if (weight[best] < pixels.size * 0.004) return null

            val hue = (best + 0.5f) * 360f / HUE_BINS
            val s = (sumS[best] / weight[best]).toFloat()
            val v = (sumV[best] / weight[best]).toFloat()
            return ArtworkPalette(
                vivid = fromHsv(hue, max(s, 0.8f), max(v, 0.92f)),
                normal = fromHsv(hue, s.coerceIn(0.45f, 0.85f), v.coerceIn(0.55f, 0.8f)),
                deep = fromHsv(hue, s.coerceIn(0.5f, 0.9f), 0.3f),
            )
        }

        internal fun toHsv(argb: Int, out: FloatArray) {
            val r = ((argb shr 16) and 0xFF) / 255f
            val g = ((argb shr 8) and 0xFF) / 255f
            val b = (argb and 0xFF) / 255f
            val maxC = max(r, max(g, b))
            val minC = min(r, min(g, b))
            val delta = maxC - minC
            val h = when {
                delta == 0f -> 0f
                maxC == r -> 60f * (((g - b) / delta) % 6f)
                maxC == g -> 60f * ((b - r) / delta + 2f)
                else -> 60f * ((r - g) / delta + 4f)
            }
            out[0] = if (h < 0f) h + 360f else h
            out[1] = if (maxC == 0f) 0f else delta / maxC
            out[2] = maxC
        }

        internal fun fromHsv(h: Float, s: Float, v: Float): Int {
            val c = v * s
            val x = c * (1 - abs((h / 60f) % 2f - 1))
            val m = v - c
            val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
                0 -> Triple(c, x, 0f)
                1 -> Triple(x, c, 0f)
                2 -> Triple(0f, c, x)
                3 -> Triple(0f, x, c)
                4 -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }
            fun channel(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
    }
}
