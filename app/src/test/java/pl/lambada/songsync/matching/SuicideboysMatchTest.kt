package pl.lambada.songsync.matching

import kotlinx.serialization.json.Json
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import pl.lambada.songsync.domain.model.lyrics_providers.others.LRCLibResponse
import pl.lambada.songsync.util.matching.FilenameParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Regression for the "$uicideboy$" case: files arrive on disk as "_uicide boys - Loot" / "_UICIDEBOY_ -
 * BURGUNDY" (the `$` decorations mangled to `_`). LRCLib *has* synced lyrics, but only under the stylized
 * "$uicideboy$" token, which a spaced/decorated query can't reach. The collapsed-artist candidate
 * ("uicideboy …") plus the pure title-only candidate must now retrieve a synced hit.
 *
 * Live test against LRCLib; skipped automatically when offline.
 */
class SuicideboysMatchTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun lrclibSearch(query: String): List<LRCLibResponse> {
        val url = URL("https://lrclib.net/api/search?q=" + URLEncoder.encode(query, "UTF-8"))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", "MusicResync/1.0 (test)")
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

    /** True if any candidate query returns a synced LRCLib hit by $uicideboy$ for [expectedTrack]. */
    private fun findsSynced(path: String, expectedTrack: String): String? {
        for (cand in FilenameParser.candidates(null, null, path)) {
            val hit = lrclibSearch(cand.asSearchString())
                .filter { !it.syncedLyrics.isNullOrBlank() }
                .firstOrNull {
                    it.artistName.lowercase().replace(Regex("[^a-z0-9]"), "").contains("uicideboy") &&
                        it.trackName.lowercase().contains(expectedTrack.lowercase())
                }
            if (hit != null) return "${cand.strategy.label} :: q='${cand.asSearchString()}' -> ${hit.artistName} / ${hit.trackName}"
            Thread.sleep(120)
        }
        return null
    }

    @Test
    fun `loot is retrievable with synced lyrics`() {
        assumeTrue("no network", lrclibSearch("blinding lights").isNotEmpty())
        val how = findsSynced("""C:\Apps\MusicResync\snaptube\download\_uicide boys - Loot(MP3_320K).mp3""", "loot")
        println("[Loot] $how")
        assertTrue("no synced \$uicideboy\$ Loot hit found via any candidate", how != null)
    }

    @Test
    fun `burgundy is retrievable with synced lyrics`() {
        assumeTrue("no network", lrclibSearch("blinding lights").isNotEmpty())
        val how = findsSynced("""C:\Apps\MusicResync\snaptube\download\_UICIDEBOY_ - BURGUNDY(MP3_320K).mp3""", "burgundy")
        println("[Burgundy] $how")
        assertTrue("no synced \$uicideboy\$ Burgundy hit found via any candidate", how != null)
    }
}
