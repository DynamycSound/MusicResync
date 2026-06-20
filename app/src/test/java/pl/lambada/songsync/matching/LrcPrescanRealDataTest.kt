package pl.lambada.songsync.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pl.lambada.songsync.util.matching.LrcPrescan
import pl.lambada.songsync.util.matching.PrescanResult
import java.io.File

/**
 * Runs the pre-scan against the *real* SnapTube `.lrc` filenames the user provided, but on COPIES inside a
 * temp folder so the originals are never touched. Skipped automatically if the corpus isn't present.
 */
class LrcPrescanRealDataTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val realDir = File("""C:\Apps\MusicResync\snaptube\download\SnapTube Audio""")

    @Test
    fun `real snaptube lrc files all resolve to bare lrc`() {
        assumeTrue("real corpus not present, skipping", realDir.isDirectory)
        val realLrcs = realDir.listFiles { f -> f.extension.equals("lrc", true) }.orEmpty()
        assumeTrue("no lrc files found", realLrcs.isNotEmpty())

        // Copy each real .lrc into tmp and synthesize the matching .mp3 path (its content is irrelevant
        // to the pre-scan, which only inspects the lyrics file).
        val audioPaths = mutableSetOf<String>()
        for (lrc in realLrcs) {
            val stem = lrc.name
                .removeSuffix(".lrc")
                .replace(Regex("""_\d+_private$"""), "")
                .removeSuffix("_private")
            File(tmp.root, lrc.name).writeText(lrc.readText(Charsets.UTF_8))
            audioPaths += File(tmp.root, "$stem.mp3").path
        }

        val results = LrcPrescan.scan(audioPaths.toList())
        val resolved = results.values.count { it != PrescanResult.NONE }
        val byKind = results.values.groupingBy { it }.eachCount()

        println("[Prescan/real] audio stems=${audioPaths.size} -> $byKind")
        results.filterValues { it == PrescanResult.NONE }.keys.forEach { println("[Prescan/real] NONE: ${File(it).name}") }

        // Every stem that shipped with a real lyrics file must end up with a usable bare .lrc next to the
        // audio (synced, renamed-from-private, or unsynced) -- none should be lost to the No-Lyrics queue.
        assertEquals("no stem should resolve to NONE; got $byKind", audioPaths.size, resolved)
        audioPaths.forEach { p ->
            val bare = File(p.removeSuffix(".mp3") + ".lrc")
            assertTrue("bare lrc missing for ${File(p).name}", bare.exists())
        }
    }
}
