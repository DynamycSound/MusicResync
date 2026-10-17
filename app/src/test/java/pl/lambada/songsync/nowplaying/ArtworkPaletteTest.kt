package pl.lambada.songsync.nowplaying

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPaletteTest {
    private fun hueOf(argb: Int): Float = FloatArray(3).also { ArtworkPalette.toHsv(argb, it) }[0]
    private fun satOf(argb: Int): Float = FloatArray(3).also { ArtworkPalette.toHsv(argb, it) }[1]

    @Test
    fun `greyscale artwork has no palette`() {
        val pixels = IntArray(400) { i -> val g = (i % 256); (0xFF shl 24) or (g shl 16) or (g shl 8) or g }
        assertNull(ArtworkPalette.from(pixels))
    }

    @Test
    fun `small bright logo beats a large grey photo`() {
        val grey = 0xFF6E6A66.toInt()
        val red = 0xFFE0201A.toInt()
        val pixels = IntArray(1000) { if (it < 60) red else grey }
        val palette = ArtworkPalette.from(pixels)
        assertNotNull(palette)
        palette!!
        val hue = hueOf(palette.vivid)
        assertTrue("hue $hue should be red", hue < 20f || hue > 340f)
        assertTrue(satOf(palette.vivid) >= 0.8f)
    }

    @Test
    fun `dominant colourful hue wins over a smaller one`() {
        val blue = 0xFF1E5AD2.toInt()
        val orange = 0xFFE08A1E.toInt()
        val pixels = IntArray(1000) { if (it < 700) blue else orange }
        val hue = hueOf(ArtworkPalette.from(pixels)!!.normal)
        assertTrue("hue $hue should be blue", hue in 200f..240f)
    }

    @Test
    fun `hsv round trip`() {
        val color = 0xFF3C82C8.toInt()
        val hsv = FloatArray(3).also { ArtworkPalette.toHsv(color, it) }
        assertEquals(color, ArtworkPalette.fromHsv(hsv[0], hsv[1], hsv[2]))
    }
}
