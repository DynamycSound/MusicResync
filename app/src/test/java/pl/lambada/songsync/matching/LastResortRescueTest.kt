package pl.lambada.songsync.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.data.remote.lyrics_providers.RescueCandidate
import pl.lambada.songsync.data.remote.lyrics_providers.selectBestRescue
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.ProviderResult

/**
 * M2: the last-resort rescue must score canonicalized hits against the original local track (via ConfidenceScorer)
 * rather than blindly accepting a same-artist / wrong-length guess. These exercise the pure ranking step.
 */
class LastResortRescueTest {

    private val synced = "[00:01.00]a\n[00:02.00]b\n"

    @Test
    fun `dirty query that canonicalizes to a different song by same artist is rejected`() {
        // Local: a $uicideboy$ track, ~180s. Canonicalization returned a DIFFERENT $uicideboy$ song (~240s).
        val local = LocalTrack(title = "\$uicideboy\$ - Kill Yourself Part III", artist = "\$uicideboy\$", durationSec = 180.0)
        val wrong = RescueCandidate(
            result = ProviderResult("Carrollton", "\$uicideboy\$", durationSec = 240.0, hasSyncedLyrics = true),
            syncedLyrics = synced, plainLyrics = null, coverUrl = null,
        )
        assertNull("wrong-length same-artist guess must not be rescued", selectBestRescue(listOf(local), listOf(wrong)))
    }

    @Test
    fun `weird-filename rescue still works when title and duration line up`() {
        val local = LocalTrack(
            title = "🔥 Ed Sheeran - 🍕Shape of You🍕 [FREE DOWNLOAD](MP3_320K)",
            artist = "Ed Sheeran",
            durationSec = 233.0,
        )
        val right = RescueCandidate(
            result = ProviderResult("Shape of You", "Ed Sheeran", durationSec = 234.0, hasSyncedLyrics = true),
            syncedLyrics = synced, plainLyrics = null, coverUrl = "http://cover",
        )
        val hit = selectBestRescue(listOf(local), listOf(right))
        assertTrue("legitimate rescue should be accepted", hit != null)
        assertEquals("Shape of You", hit!!.title)
        assertTrue(hit.synced)
    }

    @Test
    fun `synced is preferred over plain when both clear the bar`() {
        val local = LocalTrack(title = "Blinding Lights", artist = "The Weeknd", durationSec = 200.0)
        val plain = RescueCandidate(
            result = ProviderResult("Blinding Lights", "The Weeknd", durationSec = 200.0, hasSyncedLyrics = false),
            syncedLyrics = null, plainLyrics = "plain words", coverUrl = null,
        )
        val syncedHit = RescueCandidate(
            result = ProviderResult("Blinding Lights", "The Weeknd", durationSec = 201.0, hasSyncedLyrics = true),
            syncedLyrics = synced, plainLyrics = null, coverUrl = null,
        )
        val hit = selectBestRescue(listOf(local), listOf(plain, syncedHit))
        assertTrue(hit!!.synced)
    }

    @Test
    fun `no candidates returns null`() {
        val local = LocalTrack(title = "x", artist = "y", durationSec = 100.0)
        assertNull(selectBestRescue(listOf(local), emptyList()))
    }

    @Test
    fun `unknown local duration falls back to title-artist similarity and rejects unrelated`() {
        val local = LocalTrack(title = "Some Random Track", artist = "Artist A", durationSec = null)
        val unrelated = RescueCandidate(
            result = ProviderResult("Completely Different Song", "Artist B", durationSec = 200.0, hasSyncedLyrics = true),
            syncedLyrics = synced, plainLyrics = null, coverUrl = null,
        )
        assertNull(selectBestRescue(listOf(local), listOf(unrelated)))
    }

    @Test
    fun `candidate-derived cleaned title can rescue even when raw local tag is garbage`() {
        val raw = LocalTrack(title = "download 07 track", artist = "Unknown", durationSec = 142.0)
        val cleanedView = LocalTrack(title = "Audubon", artist = "\$uicideboy\$", durationSec = 142.0)
        val right = RescueCandidate(
            result = ProviderResult("Audubon", "\$uicideboy\$", durationSec = 142.0, hasSyncedLyrics = true),
            syncedLyrics = synced, plainLyrics = null, coverUrl = null,
        )
        val hit = selectBestRescue(listOf(raw, cleanedView), listOf(right))
        assertEquals("Audubon", hit?.title)
    }
}
