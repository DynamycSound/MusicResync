package pl.lambada.songsync.matching

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import pl.lambada.songsync.data.remote.lyrics_providers.SmartLyricsMatcher
import pl.lambada.songsync.util.matching.FilenameParser
import pl.lambada.songsync.util.matching.LocalTrack
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live test for the "last resort" rescue path. These are deliberately weird/garbled filenames (emoji, [FREE],
 * download markers, fancy unicode) of well-known songs that the normal candidate queries can struggle with.
 * The last resort canonicalizes them via iTunes/Deezer and re-queries LRCLib, which should then find lyrics.
 *
 * Hits the real network; skipped automatically when offline. Run in isolation if it flakes under rate-limiting:
 *   gradlew testDebugUnitTest --tests "pl.lambada.songsync.matching.LastResortMatchTest"
 */
class LastResortMatchTest {
    // Pass a no-op logger so we never touch android.util.Log (unmocked in plain JVM unit tests).
    private val matcher = SmartLyricsMatcher()

    private fun online(): Boolean = try {
        val c = (URL("https://itunes.apple.com/search?term=adele&entity=song&limit=1")
            .openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000 }
        (c.responseCode in 200..299).also { c.disconnect() }
    } catch (e: Exception) { false }

    private fun rescue(filename: String): SmartLyricsMatcher.LastResortHit? = runBlocking {
        val candidates = FilenameParser.candidates(null, null, filename)
        matcher.lastResort(local = LocalTrack(null, null, null), candidates = candidates, log = { println("  $it") })
    }

    @Test
    fun `weird files are rescued by the last resort`() {
        assumeTrue("no network", online())

        val weirdFiles = listOf(
            """🔥🎤 Ed Sheeran - 🍕Shape of You🍕 [FREE] 🔥🎶.mp3""",
            """Adele - Hello (Live at MTV Awards) 320kbps YouTube Download.mp3""",
            """Imagine Dragons - Believer.mp3""",
        )

        var rescued = 0
        for (f in weirdFiles) {
            val hit = rescue(f)
            println("[last-resort] $f -> ${hit?.let { "${it.artist} / ${it.title} (synced=${it.synced}, ${it.lyrics.length} chars)" } ?: "NOTHING"}")
            if (hit != null && hit.lyrics.isNotBlank()) rescued++
        }

        // At least the majority of these very-findable songs should be rescued. Lenient by one to tolerate a
        // transient provider blip without failing the whole build.
        assertTrue("last resort rescued only $rescued/${weirdFiles.size} weird files", rescued >= 2)
    }
}
