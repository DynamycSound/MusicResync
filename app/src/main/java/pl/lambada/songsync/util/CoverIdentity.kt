package pl.lambada.songsync.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lambada.songsync.data.remote.lyrics_providers.others.LastResortAPI
import kotlin.math.abs

/**
 * Cover-driven identification for files whose metadata carries no usable artist at all (title tag "Bounce",
 * artist "Unknown", filename "Bounce.mp3"). Such a file is indistinguishable from every other same-titled
 * song by text alone — but its embedded album art isn't. This searches iTunes/Deezer widely with the bare
 * title, hashes each result's cover, and treats a perceptual match against the file's own art (plus a
 * compatible runtime) as the file's real identity — recovering the artist the tags never had.
 *
 * The discovered identity is used in two ways by the matcher:
 *  - as a leading query candidate ("Voyage Bounce" instead of just "Bounce"), and
 *  - as a trusted artist guess, so a same-titled song by someone else can finally be vetoed.
 */
object CoverIdentity {

    data class Discovered(val title: String, val artist: String, val distance: Int)

    /** Cap on cover-image downloads per song — identification must stay cheap even for common titles. */
    private const val MAX_COVER_FETCHES = 12

    /** Runtime window for identity candidates. Lenient on purpose: the cover is the primary evidence. */
    private const val DURATION_WINDOW_SEC = 15.0

    suspend fun discover(
        context: Context,
        filePath: String?,
        titleQueries: List<String>,
        durationSec: Double?,
        api: LastResortAPI = LastResortAPI(),
    ): Discovered? = withContext(Dispatchers.IO) {
        if (filePath == null) return@withContext null
        val localHash = CoverArtCompare.localCoverHash(context, filePath) ?: return@withContext null
        val seenUrls = HashSet<String>()
        var fetches = 0
        var best: Discovered? = null
        for (query in titleQueries.filter { it.isNotBlank() }.distinct().take(2)) {
            val rows = runCatching { api.identityCandidates(query) }.getOrDefault(emptyList())
            val plausible = rows.filter { r ->
                durationSec == null || r.durationSec == null || abs(r.durationSec - durationSec) <= DURATION_WINDOW_SEC
            }
            for (row in plausible) {
                if (fetches >= MAX_COVER_FETCHES) break
                val url = row.coverUrl ?: continue
                if (!seenUrls.add(url)) continue
                fetches++
                val remote = CoverArtCompare.remoteCoverHash(url) ?: continue
                val dist = CoverArtCompare.hammingDistance(localHash, remote)
                if (dist <= CoverArtCompare.MATCH_MAX_DISTANCE && dist < (best?.distance ?: Int.MAX_VALUE))
                    best = Discovered(row.title, row.artist, dist)
            }
            if (best != null) break
        }
        best
    }
}
