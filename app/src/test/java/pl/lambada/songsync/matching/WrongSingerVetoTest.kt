package pl.lambada.songsync.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.data.remote.lyrics_providers.RescueCandidate
import pl.lambada.songsync.data.remote.lyrics_providers.artistGuessesFor
import pl.lambada.songsync.data.remote.lyrics_providers.scoreHitAgainstViews
import pl.lambada.songsync.data.remote.lyrics_providers.selectBestRescue
import pl.lambada.songsync.data.remote.lyrics_providers.wrongSingerVetoed
import pl.lambada.songsync.util.matching.ConfidenceBreakdown
import pl.lambada.songsync.util.matching.FilenameParser
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.MatchTier
import pl.lambada.songsync.util.matching.ProviderResult

/**
 * v1.6.3 hotfix regressions: songs with a short generic title were saving lyrics of a completely different
 * track by a different artist ("PETROV - RARI" -> "Rari" by lil doggo, "Numero - BMW" -> "BMW" by Cecilio G.,
 * "UKIC X PETROV - A TI?" -> "A Ti" by Dyango, "QuESt - Automatic" -> "Automatic (Live)" by 宇多田ヒカル).
 * All rode in on artist-less views of the local track, which cannot object to a wrong singer. The wrong-singer
 * veto rejects such hits whenever the file gives us any artist guess that the provider's artist disagrees with,
 * keeping the exact-duration escape the scorer has always trusted.
 */
class WrongSingerVetoTest {

    private val someLrc = "[00:01.00] la la la"

    private fun conf(durationMatched: Boolean) = ConfidenceBreakdown(
        score = 0.9, title = 1.0, artist = 0.0, duration = 0.5, album = 0.0,
        tier = MatchTier.REVIEW, durationMatched = durationMatched,
    )

    @Test
    fun `Petrov - Rari rescue by lil doggo is vetoed`() {
        val views = listOf(
            LocalTrack("PETROV - RARI (OFFICIAL VIDEO)", null, 195.0),
            LocalTrack("RARI", "PETROV", 195.0),
            LocalTrack("Rari", null, 195.0),
        )
        val wrong = RescueCandidate(
            ProviderResult("Rari", "lil doggo", 190.0, null, true), someLrc, null, null,
        )
        assertNull(selectBestRescue(views, listOf(wrong)))
    }

    @Test
    fun `Petrov - Rari rescue by the right artist still passes`() {
        val views = listOf(
            LocalTrack("PETROV - RARI (OFFICIAL VIDEO)", null, 195.0),
            LocalTrack("RARI", "PETROV", 195.0),
            LocalTrack("Rari", null, 195.0),
        )
        val right = RescueCandidate(
            ProviderResult("Rari", "PETROV", 190.0, null, true), someLrc, null, null,
        )
        val hit = selectBestRescue(views, listOf(right))
        assertNotNull(hit)
        assertEquals("PETROV", hit!!.artist)
    }

    @Test
    fun `Numero - BMW rescue by Cecilio G is vetoed`() {
        val views = listOf(
            LocalTrack("Numero - BMW", null, 200.0),
            LocalTrack("BMW", "Numero", 200.0),
            LocalTrack("BMW", null, 200.0),
        )
        val wrong = RescueCandidate(
            ProviderResult("BMW", "Cecilio G., Anti", 205.0, null, true), someLrc, null, null,
        )
        assertNull(selectBestRescue(views, listOf(wrong)))
    }

    @Test
    fun `exact runtime match still overrides a disagreeing artist`() {
        // The scorer has always trusted an exact duration over a disagreeing artist (that trust is what makes
        // junk-artist rips like "xope87" matchable at all) — the veto must keep that escape.
        val views = listOf(LocalTrack("RARI", "PETROV", 195.0))
        val sameLength = RescueCandidate(
            ProviderResult("Rari", "lil doggo", 195.5, null, true), someLrc, null, null,
        )
        assertNotNull(selectBestRescue(views, listOf(sameLength)))
    }

    @Test
    fun `A Ti by a different singer is vetoed in the main search path`() {
        // The raw title view ("UKIC X PETROV - A TI? ..." contains the phrase "A Ti") scored this REVIEW-grade
        // even though we clearly knew the artists; the veto is applied on top of scoreHitAgainstViews.
        val local = LocalTrack("UKIC X PETROV -  A TI? (Prod.by Papapedro)", null, 180.0)
        val candidates = FilenameParser.candidates("UKIC X PETROV -  A TI? (Prod.by Papapedro)", "Unknown", null)
        val guesses = artistGuessesFor(local, candidates)
        assertTrue("expected artist guesses from the parsed title", guesses.isNotEmpty())

        val wrong = ProviderResult("A Ti", "Dyango", 174.0, null, true)
        val survives = candidates.any { cand ->
            val c = scoreHitAgainstViews(local, wrong, cand)
            c.tier != MatchTier.REJECT && !wrongSingerVetoed(guesses, wrong.artist, c)
        }
        assertFalse("wrong-singer hit survived the veto", survives)
    }

    @Test
    fun `cross-script provider artist counts as disagreeing`() {
        // "QuESt - Automatic" matched "Automatic (Live)" by 宇多田ヒカル: the CJK name normalizes to nothing our
        // Latin guess could ever equal, so it must count as a disagreement (duration escape still applies).
        assertTrue(wrongSingerVetoed(listOf("QuESt"), "宇多田ヒカル", conf(durationMatched = false)))
        assertFalse(wrongSingerVetoed(listOf("QuESt"), "宇多田ヒカル", conf(durationMatched = true)))
    }

    @Test
    fun `veto stays out of the way when it cannot judge`() {
        // No artist from the provider, or no guesses from the file: nothing to disagree with.
        assertFalse(wrongSingerVetoed(listOf("PETROV"), null, conf(durationMatched = false)))
        assertFalse(wrongSingerVetoed(listOf("PETROV"), " ", conf(durationMatched = false)))
        assertFalse(wrongSingerVetoed(emptyList(), "lil doggo", conf(durationMatched = false)))
        // A guess that normalizes to nothing comparable (pure symbols) is not usable evidence either.
        assertFalse(wrongSingerVetoed(listOf("???"), "lil doggo", conf(durationMatched = false)))
    }

    @Test
    fun `featured-artist orderings are not false positives`() {
        // Provider lists the collab differently than the filename: max similarity across guesses must clear it.
        val guesses = listOf("Prti Bee Gee ft. Ajs Nigrutin", "Prti Bee Gee", "Ajs Nigrutin")
        assertFalse(wrongSingerVetoed(guesses, "Ajs Nigrutin, Prti Bee Gee", conf(durationMatched = false)))
    }
}
