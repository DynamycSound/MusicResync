package pl.lambada.songsync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the offset timing model (M1): parsing/stripping/upserting the `[offset:]` tag, normalising either
 * timing model into neutral lyrics, and proving reopen→save round-trips never double-shift in tag mode or
 * direct-shift mode.
 */
class LrcOffsetUtilsTest {

    private val neutral =
        "[ti:Song]\n[ar:Artist]\n[by:MusicResync]\n[00:10.00]line one\n[00:20.00]line two\n"

    @Test
    fun `parses negative offset tag`() {
        assertEquals(-3900, parseOffsetTagMs("[offset:-3900]\n[00:01.00]x"))
    }

    @Test
    fun `parses explicit plus and zero`() {
        assertEquals(0, parseOffsetTagMs("[offset:+0]\n[00:01.00]x"))
        assertEquals(1500, parseOffsetTagMs("[ar:A]\n[offset:1500]\n[00:01.00]x"))
    }

    @Test
    fun `missing offset tag parses as zero`() {
        assertEquals(0, parseOffsetTagMs("[ti:t]\n[00:01.00]x"))
    }

    @Test
    fun `strip removes offset tag but keeps other metadata and lyrics`() {
        val withTag = "[ti:Song]\n[ar:Artist]\n[offset:-3900]\n[00:10.00]line one\n"
        val stripped = stripOffsetTag(withTag)
        assertFalse("offset tag should be gone", stripped.contains("offset"))
        assertTrue(stripped.contains("[ti:Song]"))
        assertTrue(stripped.contains("[ar:Artist]"))
        assertTrue(stripped.contains("[00:10.00]line one"))
    }

    @Test
    fun `upsert inserts exactly one offset tag after header tags`() {
        val out = upsertOffsetTag(neutral, -3900)
        assertEquals("exactly one offset tag", 1, Regex("\\[offset:").findAll(out).count())
        assertEquals(-3900, parseOffsetTagMs(out))
        // It should sit with the header, before the first timestamped line.
        assertTrue(out.indexOf("[offset:") < out.indexOf("[00:10.00]"))
    }

    @Test
    fun `upsert replaces an existing offset tag rather than appending`() {
        val once = upsertOffsetTag(neutral, -3900)
        val twice = upsertOffsetTag(once, 1200)
        assertEquals("still exactly one offset tag", 1, Regex("\\[offset:").findAll(twice).count())
        assertEquals(1200, parseOffsetTagMs(twice))
    }

    @Test
    fun `neutral lrc for tag-mode file strips tag and leaves timestamps unchanged`() {
        val tagFile = upsertOffsetTag(neutral, -3900)
        val result = buildNeutralLrc(tagFile, baseAppliedOffsetMs = -3900, fileUsesOffsetTag = true)
        assertEquals(0, parseOffsetTagMs(result))
        assertTrue("timestamps untouched", result.contains("[00:10.000]line one") || result.contains("[00:10.00]line one"))
    }

    @Test
    fun `neutral lrc for direct-shifted file undoes the baked shift`() {
        // Simulate a direct-shifted save: bake -3900 into the timestamps, no tag.
        val shifted = applyOffsetToLyrics(neutral, -3900)
        assertEquals("no tag in direct mode", 0, parseOffsetTagMs(shifted))
        // 10.000s - 3.9s = 6.100s
        assertTrue(shifted.contains("[00:06.100]line one"))

        val result = buildNeutralLrc(shifted, baseAppliedOffsetMs = -3900, fileUsesOffsetTag = false)
        // back to the original neutral timing
        assertTrue("recovered neutral timing", result.contains("[00:10.000]line one"))
        assertTrue(result.contains("[00:20.000]line two"))
    }

    @Test
    fun `tag-mode reopen then resave is idempotent (no double shift)`() {
        // First save in tag mode.
        val saved1 = upsertOffsetTag(neutral, -3900)
        // Reopen: recover neutral.
        val reopenedNeutral = buildNeutralLrc(saved1, baseAppliedOffsetMs = -3900, fileUsesOffsetTag = true)
        // Resave with the same offset.
        val saved2 = upsertOffsetTag(reopenedNeutral, -3900)
        assertEquals(parseOffsetTagMs(saved1), parseOffsetTagMs(saved2))
        assertEquals(saved1.trim(), saved2.trim())
    }

    @Test
    fun `direct-mode reopen then resave is idempotent (no double shift)`() {
        val saved1 = applyOffsetToLyrics(neutral, -3900)
        val reopenedNeutral = buildNeutralLrc(saved1, baseAppliedOffsetMs = -3900, fileUsesOffsetTag = false)
        val saved2 = applyOffsetToLyrics(reopenedNeutral, -3900)
        assertEquals("re-save must match first save, not double-shift", saved1, saved2)
        assertTrue(saved2.contains("[00:06.100]line one"))
    }

    @Test
    fun `applyOffsetToLyrics is safe to apply to its own millisecond output`() {
        // Regression: 3-digit ms output used to be re-read as centiseconds (x10). Applying +0 must be a no-op.
        val shifted = applyOffsetToLyrics(neutral, 500)        // .000 -> .500 (3-digit ms)
        val again = applyOffsetToLyrics(shifted, 0)            // must NOT change timing
        assertEquals(shifted, again)
        assertTrue(shifted.contains("[00:10.500]line one"))
    }
}
