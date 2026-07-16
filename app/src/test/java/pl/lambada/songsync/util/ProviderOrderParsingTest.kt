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
    fun `stored order is preserved and new providers are appended`() {
        val stored = "QQMUSIC,LRCLIB"
        val parsed = parseProviderOrder(stored)
        assertEquals(Providers.QQMUSIC, parsed[0])
        assertEquals(Providers.LRCLIB, parsed[1])
        // Everything else is still present exactly once.
        assertEquals(Providers.entries.size, parsed.size)
        assertEquals(parsed.size, parsed.distinct().size)
    }

    @Test
    fun `unknown names are dropped`() {
        val parsed = parseProviderOrder("MUSIXMATCH,LRCLIB,GARBAGE")
        assertEquals(Providers.LRCLIB, parsed[0])
        assertEquals(Providers.entries.size, parsed.size)
    }

    @Test
    fun `disabled providers parse and ignore junk`() {
        assertEquals(setOf(Providers.SPOTIFY, Providers.QQMUSIC), parseDisabledProviders("SPOTIFY,QQMUSIC,NOPE"))
        assertTrue(parseDisabledProviders("").isEmpty())
    }
}
