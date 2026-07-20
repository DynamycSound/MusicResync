package pl.lambada.songsync.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.data.remote.lyrics_providers.scoreHitAgainstViews
import pl.lambada.songsync.util.matching.FilenameParser
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.MatchStrategy
import pl.lambada.songsync.util.matching.MatchTier
import pl.lambada.songsync.util.matching.ProviderResult
import pl.lambada.songsync.util.matching.TextMatch

/**
 * Regression tests for the user-reported "single finds it, batch doesn't" songs. The fix scores each provider
 * hit against the candidate's parsed view of the track in addition to the raw (often junk) tags, so the
 * primary providers accept these directly instead of relying on the last-resort rescue.
 */
class BadMetadataMatchTest {

    private fun candFor(tagTitle: String?, tagArtist: String?, path: String?, title: String, artist: String?) =
        FilenameParser.candidates(tagTitle, tagArtist, path)
            .first { it.title.equals(title, true) && (artist == null || it.artist.equals(artist, true)) }

    @Test
    fun `Ariana Grande - Focus with Unknown artist auto-accepts the right hit`() {
        // Title tag carries the whole "Artist - Title" string; artist tag is the "Unknown" placeholder.
        val local = LocalTrack("Ariana Grande - Focus", null, 211.0)
        val cand = candFor("Ariana Grande - Focus", "Unknown", null, "Focus", "Ariana Grande")
        val hit = ProviderResult("Focus", "Ariana Grande", 211.0, null, true)

        val conf = scoreHitAgainstViews(local, hit, cand)
        assertEquals(MatchTier.AUTO_ACCEPT, conf.tier)
    }

    @Test
    fun `junk artist tag is not treated as a real artist`() {
        assertTrue(TextMatch.isJunkArtist("Unknown"))
        assertTrue(TextMatch.isJunkArtist("<unknown>"))
        assertTrue(TextMatch.isJunkArtist("Various Artists"))
        assertTrue(!TextMatch.isJunkArtist("Ariana Grande"))
        // The TAGS candidate must not carry the placeholder as an artist.
        val cands = FilenameParser.candidates("Ariana Grande - Focus", "Unknown", null)
        val tags = cands.first { it.strategy == MatchStrategy.TAGS }
        assertEquals(null, tags.artist)
    }

    @Test
    fun `D-Devils 6th Gate rip matches its provider entry`() {
        val local = LocalTrack("D-Devils - 6th Gate", "xope87", 320.0)
        val cand = candFor(null, null, "/m/D-Devils - 6th Gate(MP3_320K).mp3", "6th Gate", "D-Devils")
        val hit = ProviderResult("The 6th Gate (Dance With The Devil)", "D-Devils", 320.0, null, true)

        val conf = scoreHitAgainstViews(local, hit, cand)
        assertTrue("expected at least REVIEW, got ${conf.tier} (${conf.percent()}%)", conf.tier != MatchTier.REJECT)
        assertTrue(conf.score >= 0.80)
    }

    @Test
    fun `Tyler The Creator - Tamale rip auto-accepts`() {
        val local = LocalTrack("Tyler, The Creator - Tamale", null, 173.0)
        val cand = candFor(
            "Tyler, The Creator - Tamale", "Unknown",
            "/m/Tyler_ The Creator - Tamale(MP3_320K).mp3", "Tamale", null
        )
        val hit = ProviderResult("Tamale", "Tyler, The Creator", 173.0, null, true)

        val conf = scoreHitAgainstViews(local, hit, cand)
        assertEquals(MatchTier.AUTO_ACCEPT, conf.tier)
    }

    @Test
    fun `Biba - Harli Kvin filename yields a clean feat-stripped candidate`() {
        val cands = FilenameParser.candidates(
            null, null, "/m/Biba - Harli Kvin (feat. AV47) [Official Audio] _420(MP3_320K).mp3"
        )
        // The bracketed feat clause sits mid-string (junk "_420" follows it) — it must still be extracted, and
        // the orphan "420" must be droppable, leaving a clean "Biba / Harli Kvin" query.
        val clean = cands.firstOrNull {
            it.title.equals("Harli Kvin", true) && it.artist.equals("Biba", true)
        }
        assertTrue("no clean candidate in: ${cands.map { "${it.artist} / ${it.title}" }}", clean != null)
        assertTrue(clean!!.featuredArtists.any { it.equals("AV47", true) })
    }

    @Test
    fun `title-only view without duration proof cannot auto-accept a wrong artist`() {
        // Precision guard: an identically-titled song by a different artist must not ride in on the
        // artist-less title-only view when the runtime doesn't confirm it.
        val local = LocalTrack("Focus", "H.E.R.", 220.0)
        val cand = FilenameParser.candidates(null, null, "/m/Focus.mp3")
            .first { it.strategy == MatchStrategy.FILENAME_TITLE_ONLY }
        val wrong = ProviderResult("Focus", "Ariana Grande", 211.0, null, true)

        val conf = scoreHitAgainstViews(local, wrong, cand)
        assertEquals(MatchTier.REJECT, conf.tier)
    }

    @Test
    fun `trailing junk number variant stays review-grade at best`() {
        // "Song 420" stripped to "Song" is a LOOSE candidate: even a perfect hit must not silently auto-accept.
        val local = LocalTrack("Harli Kvin 420", "Biba", null)
        val cand = FilenameParser.candidates(null, null, "/m/Biba - Harli Kvin _420.mp3")
            .first { it.strategy == MatchStrategy.FILENAME_LOOSE && it.title.equals("Harli Kvin", true) }
        val hit = ProviderResult("Harli Kvin", "Biba", null, null, true)

        val conf = scoreHitAgainstViews(local, hit, cand)
        assertTrue(conf.tier != MatchTier.AUTO_ACCEPT)
        assertTrue(conf.tier != MatchTier.REJECT)
    }
}
