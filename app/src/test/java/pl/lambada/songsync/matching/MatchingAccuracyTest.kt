package pl.lambada.songsync.matching

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import pl.lambada.songsync.domain.model.lyrics_providers.others.LRCLibResponse
import pl.lambada.songsync.util.matching.ConfidenceScorer
import pl.lambada.songsync.util.matching.FilenameParser
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.MatchTier
import pl.lambada.songsync.util.matching.ProviderResult
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * End-to-end accuracy check against the *real* SnapTube filenames hitting the live LRCLib API. Scores use only
 * the parsed title+artist (no local duration, which on-device only improves things), so this is a conservative
 * floor. Verifies the user's headline requirement: at least half of the corpus auto-accepts (>= 0.85).
 *
 * Skipped automatically when the corpus or network is unavailable.
 */
class MatchingAccuracyTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val folders = listOf("Download", "English", "SnapTube Audio", "Songs")
        .map { File("""C:\Apps\MusicResync\snaptube\download\$it""") }

    private fun lrclibSearch(query: String): List<LRCLibResponse> {
        val url = URL("https://lrclib.net/api/search?q=" + URLEncoder.encode(query, "UTF-8"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MusicResync/1.0 (https://github.com/local/MusicResync)")
            connectTimeout = 10_000; readTimeout = 10_000
        }
        return try {
            if (conn.responseCode !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (body.isBlank() || body == "[]") emptyList() else json.decodeFromString(body)
        } catch (e: Exception) {
            emptyList()
        } finally {
            conn.disconnect()
        }
    }

    /** Best confidence achievable for one file, walking the candidate ladder and stopping at first auto-accept. */
    private fun bestScoreFor(path: String): Pair<Double, String> {
        var best = 0.0
        var how = "no match"
        for (cand in FilenameParser.candidates(null, null, path)) {
            val results = lrclibSearch(cand.asSearchString()).filter { !it.syncedLyrics.isNullOrBlank() }
            for (r in results) {
                val b = ConfidenceScorer.score(
                    LocalTrack(cand.title, cand.artist),
                    ProviderResult(r.trackName, r.artistName, r.duration, r.albumName, !r.syncedLyrics.isNullOrBlank())
                )
                if (b.score > best) { best = b.score; how = "${cand.strategy.label} -> ${r.trackName} - ${r.artistName} (${b.percent()}%)" }
            }
            Thread.sleep(120) // be gentle with the public API
            if (best >= ConfidenceScorer.AUTO_ACCEPT_THRESHOLD) break
        }
        return best to how
    }

    @Test
    fun `at least half of the real corpus auto-accepts via LRCLib`() {
        assumeTrue("corpus not present", folders.any { it.isDirectory })
        assumeTrue("no network to lrclib", lrclibSearch("blinding lights").isNotEmpty())

        // Sample up to 5 mp3s per folder for a representative, bounded run.
        val sample = folders.flatMap { dir ->
            dir.listFiles { f -> f.extension.equals("mp3", true) }?.sortedBy { it.name }?.take(5).orEmpty()
        }
        assumeTrue("no mp3s sampled", sample.isNotEmpty())

        var auto = 0; var review = 0; var fail = 0
        println("=== MusicResync matching accuracy (LRCLib, title+artist only) ===")
        for (f in sample) {
            val (score, how) = bestScoreFor(f.path)
            val tier = ConfidenceScorer.tierFor(score)
            when (tier) {
                MatchTier.AUTO_ACCEPT -> auto++
                MatchTier.REVIEW -> review++
                MatchTier.REJECT -> fail++
            }
            val mark = when (tier) { MatchTier.AUTO_ACCEPT -> "OK "; MatchTier.REVIEW -> "REV"; MatchTier.REJECT -> "XXX" }
            println("[$mark ${(score * 100).toInt()}%] ${f.name}  ::  $how")
        }
        val n = sample.size
        println("=== auto=$auto review=$review fail=$fail / total=$n (auto rate=${auto * 100 / n}%) ===")

        assertTrue(
            "expected >= 50% auto-accept, got $auto/$n. review=$review fail=$fail",
            auto.toDouble() / n >= 0.50
        )
    }
}
