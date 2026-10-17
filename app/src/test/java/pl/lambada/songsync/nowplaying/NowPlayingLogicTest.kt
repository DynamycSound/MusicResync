package pl.lambada.songsync.nowplaying

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.domain.model.Song

class NowPlayingLogicTest {

    private fun song(title: String?, artist: String?, path: String, durationMs: Long? = null) =
        Song(title = title, artist = artist, imgUri = null, filePath = path, durationMs = durationMs)

    // --- metadata field mapping -------------------------------------------------------------------------------

    @Test
    fun `first non-blank metadata field wins and is trimmed`() {
        assertEquals("Artist", NowPlayingLogic.firstNonBlank(null, "  ", " Artist ", "Other"))
        assertNull(NowPlayingLogic.firstNonBlank(null, "", " "))
    }

    @Test
    fun `only playing or buffering sessions count`() {
        assertTrue(NowPlayingLogic.isPlaying(PlaybackState.STATE_PLAYING))
        assertTrue(NowPlayingLogic.isPlaying(PlaybackState.STATE_BUFFERING))
        assertFalse(NowPlayingLogic.isPlaying(PlaybackState.STATE_PAUSED))
        assertFalse(NowPlayingLogic.isPlaying(PlaybackState.STATE_STOPPED))
        assertFalse(NowPlayingLogic.isPlaying(null))
    }

    private data class FakeSession(val name: String, val state: Int?, val title: String?)

    @Test
    fun `picks the highest priority session that is playing and has a title`() {
        val sessions = listOf(
            FakeSession("paused", PlaybackState.STATE_PAUSED, "Old song"),
            FakeSession("no title", PlaybackState.STATE_PLAYING, null),
            FakeSession("spotify", PlaybackState.STATE_PLAYING, "Song"),
            FakeSession("youtube", PlaybackState.STATE_PLAYING, "Video"),
        )
        assertEquals("spotify", NowPlayingLogic.pickPlaying(sessions, { it.state }, { it.title })?.name)
    }

    @Test
    fun `nothing playing picks nothing`() {
        val sessions = listOf(FakeSession("paused", PlaybackState.STATE_PAUSED, "Song"))
        assertNull(NowPlayingLogic.pickPlaying(sessions, { it.state }, { it.title }))
        assertNull(NowPlayingLogic.pickPlaying(emptyList<FakeSession>(), { it.state }, { it.title }))
    }

    // --- query cleanup -------------------------------------------------------------------------------------------

    @Test
    fun `clean music app metadata passes through`() {
        assertEquals(NowPlayingQuery("Blinding Lights", "The Weeknd"), NowPlayingLogic.searchQuery("Blinding Lights", "The Weeknd"))
    }

    @Test
    fun `video title with VEVO channel is split into artist and title`() {
        assertEquals(
            // Same cleanup as the matching engine (TextMatch.cleanTitleArtist), which also drops the trailing period.
            NowPlayingQuery("HUMBLE", "Kendrick Lamar"),
            NowPlayingLogic.searchQuery("Kendrick Lamar - HUMBLE. (Official Video)", "KendrickLamarVEVO"),
        )
    }

    @Test
    fun `topic channel artist loses its suffix`() {
        assertEquals(NowPlayingQuery("Paradise", "Coldplay"), NowPlayingLogic.searchQuery("Paradise", "Coldplay - Topic"))
    }

    @Test
    fun `dash title without artist is split`() {
        assertEquals(NowPlayingQuery("Lucid Dreams", "Juice WRLD"), NowPlayingLogic.searchQuery("Juice WRLD - Lucid Dreams", null))
    }

    @Test
    fun `dash inside a real title is kept when the left side is not the artist`() {
        assertEquals(
            NowPlayingQuery("Let It Be - Remastered 2009", "The Beatles"),
            NowPlayingLogic.searchQuery("Let It Be - Remastered 2009", "The Beatles"),
        )
    }

    @Test
    fun `third party uploader channel is not mistaken for the artist`() {
        // The channel is a video producer, not the singer: the artist is the part before the dash.
        val q = NowPlayingLogic.searchQuery("Juice WRLD - Lucid Dreams (Dir. by @_ColeBennett_)", "Lyrical Lemonade")
        assertEquals("Juice WRLD", q.artist)
        assertTrue(q.title.startsWith("Lucid Dreams"))
    }

    @Test
    fun `label channel is not mistaken for the artist`() {
        assertEquals(
            NowPlayingQuery("HILTON HOTEL BARÇA", "Nucci"),
            NowPlayingLogic.searchQuery("Nucci - HILTON HOTEL BARÇA (Official Video)", "Generacija Zed"),
        )
    }

    @Test
    fun `version suffixes after a dash stay part of the title`() {
        assertEquals(
            NowPlayingQuery("Love Story - Taylor's Version", "Taylor Swift"),
            NowPlayingLogic.searchQuery("Love Story - Taylor's Version", "Taylor Swift"),
        )
        assertEquals(
            NowPlayingQuery("Yellow - Live in Buenos Aires", "Coldplay"),
            NowPlayingLogic.searchQuery("Yellow - Live in Buenos Aires", "Coldplay"),
        )
    }

    @Test
    fun `brackets on a video title are kept whole`() {
        assertEquals(
            NowPlayingQuery("Šabac je ceo (Remix)", "Seksi"),
            NowPlayingLogic.searchQuery("Šabac je ceo (Remix)", "Seksi - Topic"),
        )
    }

    // --- live position ----------------------------------------------------------------------------------------

    @Test
    fun `live position advances from the last report while playing`() {
        assertEquals(96_900L, NowPlayingLogic.livePositionMs(true, 0, 1_000, 1f, 97_900))
        assertEquals(10_000L + 2_000, NowPlayingLogic.livePositionMs(true, 10_000, 5_000, 2f, 6_000))
    }

    @Test
    fun `live position is frozen while paused and unknown when not reported`() {
        assertEquals(42_000L, NowPlayingLogic.livePositionMs(false, 42_000, 1_000, 1f, 99_000))
        assertNull(NowPlayingLogic.livePositionMs(true, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1_000, 1f, 2_000))
    }

    @Test
    fun `noise brackets are stripped from the title`() {
        assertEquals(NowPlayingQuery("Blinding Lights", "The Weeknd"), NowPlayingLogic.searchQuery("Blinding Lights (Official Audio)", "The Weeknd"))
    }

    @Test
    fun `placeholder artist becomes blank`() {
        assertEquals(NowPlayingQuery("Song", ""), NowPlayingLogic.searchQuery("Song", "<unknown>"))
    }

    // --- library matching --------------------------------------------------------------------------------------

    @Test
    fun `matches the library file by title and artist ignoring case and accents`() {
        val songs = listOf(
            song("Other", "Someone", "/m/other.mp3"),
            song("Café del Mar", "Energy 52", "/m/cafe.mp3"),
        )
        assertEquals("/m/cafe.mp3", NowPlayingLogic.findLocalMatch("cafe DEL mar", "energy 52", null, songs)?.filePath)
    }

    @Test
    fun `different artist with the same title is not a match`() {
        val songs = listOf(song("Hello", "Adele", "/m/hello.mp3"))
        assertNull(NowPlayingLogic.findLocalMatch("Hello", "Lionel Richie", null, songs))
    }

    @Test
    fun `credit list containing the playing artist matches`() {
        val songs = listOf(song("Life Is Good", "Future, Drake", "/m/life.mp3"))
        assertEquals("/m/life.mp3", NowPlayingLogic.findLocalMatch("Life Is Good", "Drake", null, songs)?.filePath)
    }

    @Test
    fun `duration far off rejects the match`() {
        val songs = listOf(song("Song", "Artist", "/m/song.mp3", durationMs = 200_000))
        assertNull(NowPlayingLogic.findLocalMatch("Song", "Artist", 260_000, songs))
        assertEquals("/m/song.mp3", NowPlayingLogic.findLocalMatch("Song", "Artist", 202_000, songs)?.filePath)
    }

    @Test
    fun `duplicate titles are told apart by duration`() {
        val songs = listOf(
            song("Intro", null, "/a/intro.mp3", durationMs = 60_000),
            song("Intro", null, "/b/intro.mp3", durationMs = 95_000),
        )
        assertEquals("/b/intro.mp3", NowPlayingLogic.findLocalMatch("Intro", "Anyone", 96_000, songs)?.filePath)
    }

    @Test
    fun `ambiguous duplicates without a duration give no match`() {
        val songs = listOf(song("Intro", null, "/a/intro.mp3"), song("Intro", null, "/b/intro.mp3"))
        assertNull(NowPlayingLogic.findLocalMatch("Intro", null, null, songs))
    }

    @Test
    fun `songs without a path or title never match`() {
        val songs = listOf(
            Song(title = "Song", artist = "Artist", imgUri = null, filePath = null),
            song(null, "Artist", "/m/x.mp3"),
        )
        assertNull(NowPlayingLogic.findLocalMatch("Song", "Artist", null, songs))
        assertNull(NowPlayingLogic.findLocalMatch("  ", "Artist", null, songs))
    }
}
