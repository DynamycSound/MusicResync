package pl.lambada.songsync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.data.parseDisabledProviders
import pl.lambada.songsync.data.parseProviderOrder

/** Round-trip/robustness tests for the persisted provider fallback order (issue #6). */
class ProviderOrderParsingTest {

    @Test
    fun `empty value yields the default order`() {
        assertEquals(defaultProviderFallbackOrder, parseProviderOrder(""))
    }

    @Test
    fun `stored order is preserved and missing providers fill in`() {
        val parsed = parseProviderOrder("QQMUSIC,LRCLIB")
        // The user's own providers keep their relative order.
        assertTrue(parsed.indexOf(Providers.QQMUSIC) < parsed.indexOf(Providers.LRCLIB))
        // Everything else is still present exactly once.
        assertEquals(Providers.entries.size, parsed.size)
        assertEquals(parsed.size, parsed.distinct().size)
    }

    @Test
    fun `a provider added in an update takes its default slot in a saved order`() {
        // An order saved before Deezer existed: Deezer leads the default order, so it goes first.
        val saved = defaultProviderFallbackOrder.filter { it != Providers.DEEZER }.reversed()
        val parsed = parseProviderOrder(saved.joinToString(",") { it.name })
        assertEquals(listOf(Providers.DEEZER) + saved, parsed)
    }

    @Test
    fun `a missing provider goes in after its default predecessor`() {
        // LYRICSPLUS follows LRCLIB in the default order, so it lands right behind the user's LRCLIB.
        val parsed = parseProviderOrder("QQMUSIC,LRCLIB,DEEZER")
        assertEquals(Providers.LYRICSPLUS, parsed[parsed.indexOf(Providers.LRCLIB) + 1])
        assertEquals(Providers.QQMUSIC, parsed[0])
    }

    @Test
    fun `unknown names are dropped`() {
        val parsed = parseProviderOrder("MUSIXMATCH,LRCLIB,GARBAGE")
        assertEquals(Providers.entries.size, parsed.size)
        assertEquals(parsed.size, parsed.distinct().size)
    }

    @Test
    fun `disabled providers parse and ignore junk`() {
        assertEquals(setOf(Providers.SPOTIFY, Providers.QQMUSIC), parseDisabledProviders("SPOTIFY,QQMUSIC,NOPE"))
        assertTrue(parseDisabledProviders("").isEmpty())
    }
}
