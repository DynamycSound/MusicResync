package pl.lambada.songsync.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.lambada.songsync.util.matching.LrcPrescan
import pl.lambada.songsync.util.matching.PrescanResult
import java.io.File

/**
 * Hermetic tests for [LrcPrescan] using fixtures that mirror the real SnapTube exports:
 *  - an already-correct `.lrc` (Drake / Kendrick style)
 *  - a `_private` suffixed `.lrc` (Logic / THCF style)
 *  - a numbered `_1_private` duplicate ($uicideboy$ MATTE BLACK style)
 *  - an mp3 with no lyrics at all
 *  - a stale empty `.lrc` placeholder that should be replaced by a real private variant
 */
class LrcPrescanTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val synced = "[00:00.57] line one\n[00:02.66] line two\n"

    private fun audio(name: String): File = tmp.newFile("$name.mp3").apply { writeText("fake") }
    private fun lrc(name: String, content: String = synced): File =
        File(tmp.root, "$name.lrc").apply { writeText(content) }

    @Test
    fun `already correct lrc is detected as already synced`() {
        val stem = "Drake ft. 21 Savage - Jimmy Cooks (Official Audio)(MP3_320K)"
        val mp3 = audio(stem)
        lrc(stem)

        assertEquals(PrescanResult.ALREADY_SYNCED, LrcPrescan.resolveForAudio(mp3.path))
    }

    @Test
    fun `private suffix is stripped and renamed to bare lrc`() {
        val stem = "THCF - ROZE ZLATO (OFFICIAL VIDEO)(MP3_320K)"
        val mp3 = audio(stem)
        lrc("${stem}_private")

        val result = LrcPrescan.resolveForAudio(mp3.path)

        assertEquals(PrescanResult.RENAMED_FROM_PRIVATE, result)
        assertTrue("bare .lrc should now exist", File(tmp.root, "$stem.lrc").exists())
        assertFalse("_private file should be gone", File(tmp.root, "${stem}_private.lrc").exists())
    }

    @Test
    fun `numbered private duplicate is used when plain private absent`() {
        val stem = "_UICIDEBOY_ - MATTE BLACK (Lyric Video)(MP3_320K)"
        val mp3 = audio(stem)
        lrc("${stem}_1_private")

        val result = LrcPrescan.resolveForAudio(mp3.path)

        assertEquals(PrescanResult.RENAMED_FROM_PRIVATE, result)
        assertTrue(File(tmp.root, "$stem.lrc").exists())
    }

    @Test
    fun `song with no lyrics returns NONE`() {
        val mp3 = audio("Coby - Septembar")
        assertEquals(PrescanResult.NONE, LrcPrescan.resolveForAudio(mp3.path))
    }

    @Test
    fun `empty placeholder lrc is replaced by real private variant`() {
        val stem = "Low Life (Official Music Video)(MP3_320K)"
        val mp3 = audio(stem)
        lrc(stem, content = "")                 // stale empty placeholder, not synced
        lrc("${stem}_private", content = synced) // the real lyrics

        val result = LrcPrescan.resolveForAudio(mp3.path)

        assertEquals(PrescanResult.RENAMED_FROM_PRIVATE, result)
        assertTrue(LrcPrescan.isSyncedLrc(File(tmp.root, "$stem.lrc")))
    }

    @Test
    fun `unsynced plain-text lrc is not treated as synced`() {
        val f = File(tmp.root, "plain.lrc").apply { writeText("just some lyrics with no timestamps\n") }
        assertFalse(LrcPrescan.isSyncedLrc(f))
    }

    @Test
    fun `scan counts results across many songs`() {
        audio("a"); lrc("a")
        audio("b"); lrc("b_private")
        audio("c")

        val results = LrcPrescan.scan(listOf(File(tmp.root, "a.mp3").path, File(tmp.root, "b.mp3").path, File(tmp.root, "c.mp3").path))

        assertEquals(PrescanResult.ALREADY_SYNCED, results[File(tmp.root, "a.mp3").path])
        assertEquals(PrescanResult.RENAMED_FROM_PRIVATE, results[File(tmp.root, "b.mp3").path])
        assertEquals(PrescanResult.NONE, results[File(tmp.root, "c.mp3").path])
    }
}
