package pl.lambada.songsync.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.util.matching.ConfidenceScorer
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.MatchTier
import pl.lambada.songsync.util.matching.ProviderResult

class ConfidenceScorerTest {

    @Test
    fun `exact match auto-accepts`() {
        val b = ConfidenceScorer.score(
            LocalTrack("Blinding Lights", "The Weeknd", 200.0),
            ProviderResult("Blinding Lights", "The Weeknd", 200.0)
        )
        assertEquals(MatchTier.AUTO_ACCEPT, b.tier)
        assertTrue(b.score > 0.95)
    }

    @Test
    fun `wrong artist drops to review or reject`() {
        val b = ConfidenceScorer.score(
            LocalTrack("Blinding Lights", "Bad Bunny", 200.0),
            ProviderResult("Blinding Lights", "The Weeknd", 320.0)
        )
        assertTrue("expected below auto-accept, got ${b.score}", b.tier != MatchTier.AUTO_ACCEPT)
    }

    @Test
    fun `missing local artist does not penalize - title and duration carry it`() {
        // tag-less file: only a parsed title + the file's real duration are known
        val b = ConfidenceScorer.score(
            LocalTrack(title = "It Was A Good Day", artist = null, durationSec = 271.0),
            ProviderResult("It Was a Good Day", "Ice Cube", 271.0)
        )
        assertEquals(MatchTier.AUTO_ACCEPT, b.tier)
    }

    @Test
    fun `duration tiebreak rescues ambiguous artist when length matches exactly`() {
        // strong title, unknown artist, exact duration -> should be nudged to auto-accept
        val b = ConfidenceScorer.score(
            LocalTrack(title = "The Box", artist = "wrongtags", durationSec = 196.0),
            ProviderResult("The Box", "Roddy Ricch", 196.0)
        )
        assertTrue("duration tiebreak should engage", b.durationMatched)
        assertEquals(MatchTier.AUTO_ACCEPT, b.tier)
    }

    @Test
    fun `bad duration prevents false auto-accept on common title`() {
        val b = ConfidenceScorer.score(
            LocalTrack(title = "Lights", artist = null, durationSec = 240.0),
            ProviderResult("Lights", "Ellie Goulding", 200.0) // 40s off
        )
        assertTrue(b.tier != MatchTier.AUTO_ACCEPT)
    }

    @Test
    fun `completely different song rejects`() {
        val b = ConfidenceScorer.score(
            LocalTrack("Septembar", "Coby", 180.0),
            ProviderResult("Thrift Shop", "Macklemore", 235.0)
        )
        assertEquals(MatchTier.REJECT, b.tier)
    }
}
