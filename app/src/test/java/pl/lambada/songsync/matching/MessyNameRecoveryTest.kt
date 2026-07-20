package pl.lambada.songsync.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.data.remote.lyrics_providers.artistDisagreesWithAllGuesses
import pl.lambada.songsync.data.remote.lyrics_providers.artistGuessesFor
import pl.lambada.songsync.util.matching.FilenameParser
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.TextMatch

/**
 * v1.6.4: recovery of real-world messy names verified against the live catalogues —
 *  - "DEVITO - FLEX ????"  (garbled chars; the real track "Taj flex" by Devito needs the query "DEVITO FLEX")
 *  - "CRNI CERAK - CC #2 (JUŽNI VETAR 2022)"  (unknown bracket junk; LRCLib has "CC #2" by Crni Cerak)
 *  - "GRCA X FOX - BELE ŠARE"  (indexed under Fox with GRCA as guest — the second collab part matters)
 *  - "CHECK" by channel artist "WAV3POP - Topic"  (the stripped channel name is the real artist)
 */
class MessyNameRecoveryTest {

    @Test
    fun `garbled question-mark runs are stripped from queries`() {
        val cands = FilenameParser.candidates("DEVITO - FLEX ????", "La_Kojot Official", null)
        assertTrue(
            "no clean DEVITO/FLEX candidate in: ${cands.map { "${it.artist} / ${it.title}" }}",
            cands.any { it.title.equals("FLEX", true) && it.artist.equals("DEVITO", true) },
        )
        // A single legitimate '?' ("A TI?") must survive cleaning.
        val single = FilenameParser.candidates("UKIC - A TI?", null, null)
        assertTrue(single.any { it.title.equals("A TI?", true) })
    }

    @Test
    fun `unknown bracket junk falls back to a bare-title candidate`() {
        val cands = FilenameParser.candidates("CRNI CERAK - CC #2 (JUŽNI VETAR 2022)", "Južni vetar", null)
        assertTrue(
            "no bare CC #2 candidate in: ${cands.map { "${it.artist} / ${it.title}" }}",
            cands.any { it.title.equals("CC #2", true) && it.artist.equals("CRNI CERAK", true) },
        )
    }

    @Test
    fun `second collab artist becomes a candidate and a veto guess`() {
        val local = LocalTrack("GRCA X FOX - BELE ŠARE", null, 122.0)
        val cands = FilenameParser.candidates("GRCA X FOX - BELE ŠARE (OFFICIAL VIDEO)", "GRCA", null)
        assertTrue(
            "FOX not among candidates: ${cands.map { "${it.artist} / ${it.title}" }}",
            cands.any { it.artist.equals("FOX", true) },
        )
        // The real provider entry is "BELE ŠARE (feat. GRCA)" by Fox — Fox must NOT count as a stranger.
        val guesses = artistGuessesFor(local, cands)
        assertFalse(artistDisagreesWithAllGuesses(guesses, "Fox"))
    }

    @Test
    fun `channel decorations are stripped into an extra artist reading`() {
        assertEquals("WAV3POP", TextMatch.stripChannelSuffix("WAV3POP - Topic"))
        assertEquals("Crni Cerak", TextMatch.stripChannelSuffix("Crni Cerak TV"))
        assertEquals("La Kojot", TextMatch.stripChannelSuffix("La Kojot Official"))
        // Nothing real left -> blank, caller keeps the original.
        assertEquals("", TextMatch.stripChannelSuffix("Official Music"))

        val cands = FilenameParser.candidates("CHECK", "WAV3POP - Topic", null)
        assertTrue(cands.any { it.artist.equals("WAV3POP", true) })
        // "The Boy" (the wrong singer the escape let through) still disagrees with every reading.
        val guesses = artistGuessesFor(LocalTrack("CHECK", "WAV3POP - Topic", 140.0), cands)
        assertTrue(artistDisagreesWithAllGuesses(guesses, "The Boy"))
        assertFalse(artistDisagreesWithAllGuesses(guesses, "WAV3POP"))
    }

    @Test
    fun `quoted titles are searchable`() {
        // 'Summer Cem - "TMM TMM" (official 4K)': the quotes defeated provider search even though LRCLib has
        // the song synced under "TMM TMM".
        val cands = FilenameParser.candidates("Summer Cem - “TMM TMM” (official 4K)", "BangerChannel", null)
        assertTrue(
            "no clean TMM TMM candidate in: ${cands.map { "${it.artist} / ${it.title}" }}",
            cands.any { it.title.equals("TMM TMM", true) && it.artist.equals("Summer Cem", true) },
        )
    }

    @Test
    fun `Cyrillic artists compare as their Latin transliteration`() {
        // "Секси" IS "Seksi" — a Cyrillic-indexed provider entry must not look like a stranger...
        assertTrue(TextMatch.similarity("Секси", "Seksi") > 0.9)
        assertFalse(artistDisagreesWithAllGuesses(listOf("Seksi"), "Секси"))
        // ...while a genuinely different Cyrillic act still disagrees ("200" by Tveth/Sevnz/Лора vs Ourmoney).
        assertTrue(artistDisagreesWithAllGuesses(listOf("Ourmoney - Topic", "Ourmoney"), "Tveth, Sevnz, Лора"))
    }

    @Test
    fun `no artist anywhere means no guesses and no veto`() {
        // "Bounce" / Unknown: identification is the cover's job — text alone must neither guess nor veto.
        val local = LocalTrack("Bounce", null, 139.0)
        val cands = FilenameParser.candidates("Bounce", "Unknown", "/m/Bounce.mp3")
        assertTrue(artistGuessesFor(local, cands).isEmpty())
        assertFalse(artistDisagreesWithAllGuesses(emptyList(), "essaluv feat. Экси"))
    }
}
