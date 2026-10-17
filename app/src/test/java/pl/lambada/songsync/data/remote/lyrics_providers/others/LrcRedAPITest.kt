package pl.lambada.songsync.data.remote.lyrics_providers.others

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pl.lambada.songsync.data.remote.lyrics_providers.others.LrcRedAPI.Hit

class LrcRedAPITest {
    // The first hits lrc.red's search returns for "bohemian rhapsody" (2026-10-17).
    private val bohemian = listOf(
        Hit("GBUM71100986", "Bohemian Rhapsody (Operatic Section / 2011 A Cappella Mix)", "Queen", duration = 64.969),
        Hit("GBUM71029604", "Bohemian Rhapsody (Remastered 2011)", "Queen", duration = 356.52),
        Hit("GBUM71805978", "I Want To Break Free (Remastered 2018)", "Queen", duration = 259.0),
    )

    @Test
    fun `remaster of the same song is accepted, a cappella mix above it is not`() {
        assertEquals("GBUM71029604", LrcRedAPI.best(bohemian, "Bohemian Rhapsody", "Queen", 355)?.isrc)
    }

    @Test
    fun `no duration still needs the same title and version`() {
        assertEquals("GBUM71029604", LrcRedAPI.best(bohemian, "Bohemian Rhapsody", "Queen", null)?.isrc)
    }

    @Test
    fun `a different edit length is refused`() {
        assertNull(LrcRedAPI.best(bohemian, "Bohemian Rhapsody", "Queen", 300))
    }

    @Test
    fun `another artist's song of the same name is refused`() {
        assertNull(LrcRedAPI.best(bohemian, "Bohemian Rhapsody", "Panic! At The Disco", 355))
    }

    @Test
    fun `remix and original never stand in for each other`() {
        val hits = listOf(
            Hit("USUG11500737", "Waiting For Love", "Avicii", duration = 230.0),
            Hit("USUG11500999", "Waiting For Love (Marshmello Remix)", "Avicii, Marshmello", duration = 231.0),
        )
        assertEquals("USUG11500999", LrcRedAPI.best(hits, "Waiting For Love - Marshmello Remix", "Avicii", 231)?.isrc)
        assertEquals("USUG11500737", LrcRedAPI.best(hits, "Waiting For Love", "Avicii", 231)?.isrc)
        assertNull(LrcRedAPI.best(hits.take(1), "Waiting For Love (Marshmello Remix)", "Avicii", 230))
    }

    @Test
    fun `accents and featured artists are matched loosely`() {
        val hits = listOf(Hit("RSA3U2600001", "Šotovi u nizu", "Đorđe feat. Someone", duration = 180.0))
        assertEquals("RSA3U2600001", LrcRedAPI.best(hits, "SOTOVI U NIZU", "Dorde", 181)?.isrc)
    }

    @Test
    fun `hits without a valid isrc are skipped`() {
        val hits = listOf(Hit("../../etc", "Bohemian Rhapsody", "Queen", duration = 355.0))
        assertNull(LrcRedAPI.best(hits, "Bohemian Rhapsody", "Queen", 355))
    }
}
