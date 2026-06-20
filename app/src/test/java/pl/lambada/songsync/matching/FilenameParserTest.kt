package pl.lambada.songsync.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.util.matching.FilenameParser
import pl.lambada.songsync.util.matching.MatchStrategy
import pl.lambada.songsync.util.matching.TextMatch

class FilenameParserTest {

    private fun titlesFor(path: String, tagTitle: String? = null, tagArtist: String? = null) =
        FilenameParser.candidates(tagTitle, tagArtist, path)

    @Test
    fun `tags are used first when present`() {
        val c = titlesFor("/m/whatever.mp3", tagTitle = "Blinding Lights", tagArtist = "The Weeknd").first()
        assertEquals(MatchStrategy.TAGS, c.strategy)
        assertEquals("Blinding Lights", c.title)
        assertEquals("The Weeknd", c.artist)
    }

    @Test
    fun `unknown tags are ignored`() {
        val c = titlesFor("/m/Ice Cube - It Was A Good Day.mp3", tagTitle = "<unknown>", tagArtist = "<unknown>")
        assertTrue(c.none { it.strategy == MatchStrategy.TAGS })
    }

    @Test
    fun `artist - title filename parses both orientations`() {
        val cands = titlesFor("/m/07. Ice Cube  - It Was A Good Day(MP3_320K).mp3")
        val at = cands.first { it.strategy == MatchStrategy.FILENAME_ARTIST_TITLE }
        assertEquals("Ice Cube", at.artist)
        assertEquals("It Was A Good Day", at.title)
        // reversed orientation also offered
        assertTrue(cands.any { it.strategy == MatchStrategy.FILENAME_TITLE_ARTIST && it.title == "Ice Cube" })
    }

    @Test
    fun `noise tokens and track numbers are stripped`() {
        val c = titlesFor("/m/6LACK - PRBLMS [Official Music Video](MP3_320K).mp3")
            .first { it.strategy == MatchStrategy.FILENAME_ARTIST_TITLE }
        assertEquals("6LACK", c.artist)
        assertEquals("PRBLMS", c.title)
    }

    @Test
    fun `featured artists are extracted out of the title`() {
        val c = titlesFor("/m/Drake ft. 21 Savage - Jimmy Cooks (Official Audio)(MP3_320K).mp3")
            .first { it.strategy == MatchStrategy.FILENAME_ARTIST_TITLE }
        assertEquals("Jimmy Cooks", c.title)
        assertEquals("Drake", c.artist)
    }

    @Test
    fun `title-only fallback exists for merged names`() {
        val cands = titlesFor("/m/G-Eazy I Mean It(MP3_320K).mp3")
        assertTrue(cands.any { it.strategy == MatchStrategy.FILENAME_TITLE_ONLY })
    }

    @Test
    fun `clean handles underscores and quality tags`() {
        assertEquals("Me Myself I", TextMatch.cleanTitleArtist("Me_ Myself _ I(MP3_320K)"))
    }

    @Test
    fun `bare video parenthetical is stripped and primary artist split on x`() {
        val cands = titlesFor("/m/\$UICIDEBOY\$ x TRAVIS BARKER - ALIENS ARE GHOSTS (Drift Music Video).mp3")
        val primary = cands.first { it.strategy == MatchStrategy.FILENAME_PRIMARY_ARTIST }
        assertEquals("\$UICIDEBOY\$", primary.artist)
        assertEquals("ALIENS ARE GHOSTS", primary.title) // (Drift Music Video) removed
    }
}
