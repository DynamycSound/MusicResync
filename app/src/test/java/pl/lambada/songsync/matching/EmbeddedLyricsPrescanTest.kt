package pl.lambada.songsync.matching

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.lambada.songsync.util.matching.LrcPrescan
import pl.lambada.songsync.util.matching.PrescanResult
import java.io.File

/**
 * Tests for the embedded-lyrics fallback in [LrcPrescan] (issue #5: songs whose lyrics live in the audio tags
 * instead of a sidecar .lrc showed as "missing lyrics" on the home page). The tag reader is injected as a
 * lambda so the tests stay JVM-only (no TagLib native code).
 */
class EmbeddedLyricsPrescanTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val syncedBody = "[ti:Song]\n[00:00.57] line one\n[00:02.66] line two\n"
    private val plainBody = "just some words\nwithout any timestamps\n"

    private fun audio(name: String): File = tmp.newFile("$name.mp3").apply { writeText("fake") }

    @Test
    fun `embedded synced lyrics are detected when no sidecar exists`() {
        val song = audio("embedded-synced")
        val result = LrcPrescan.resolveForAudio(song.path, null) { syncedBody }
        assertEquals(PrescanResult.EMBEDDED_SYNCED, result)
    }

    @Test
    fun `embedded plain lyrics are detected as unsynced`() {
        val song = audio("embedded-plain")
        val result = LrcPrescan.resolveForAudio(song.path, null) { plainBody }
        assertEquals(PrescanResult.EMBEDDED_UNSYNCED, result)
    }

    @Test
    fun `no sidecar and no embedded lyrics is NONE`() {
        val song = audio("nothing")
        val result = LrcPrescan.resolveForAudio(song.path, null) { null }
        assertEquals(PrescanResult.NONE, result)
    }

    @Test
    fun `sidecar synced lrc wins without consulting the tag reader`() {
        val song = audio("sidecar")
        File(tmp.root, "sidecar.lrc").writeText(syncedBody)
        val result = LrcPrescan.resolveForAudio(song.path, null) { error("must not be called") }
        assertEquals(PrescanResult.ALREADY_SYNCED, result)
    }

    @Test
    fun `embedded synced outranks a plain sidecar`() {
        val song = audio("plain-sidecar")
        File(tmp.root, "plain-sidecar.lrc").writeText(plainBody)
        val result = LrcPrescan.resolveForAudio(song.path, null) { syncedBody }
        assertEquals(PrescanResult.EMBEDDED_SYNCED, result)
    }

    @Test
    fun `plain sidecar still reported when embedded is also plain`() {
        val song = audio("both-plain")
        File(tmp.root, "both-plain.lrc").writeText(plainBody)
        val result = LrcPrescan.resolveForAudio(song.path, null) { plainBody }
        assertEquals(PrescanResult.ALREADY_PRESENT_UNSYNCED, result)
    }

    @Test
    fun `scan passes the reader through`() {
        val withEmbedded = audio("scan-embedded")
        val without = audio("scan-none")
        val results = LrcPrescan.scan(listOf(withEmbedded.path, without.path)) { path ->
            if (path == withEmbedded.path) syncedBody else null
        }
        assertEquals(PrescanResult.EMBEDDED_SYNCED, results[withEmbedded.path])
        assertEquals(PrescanResult.NONE, results[without.path])
    }
}
