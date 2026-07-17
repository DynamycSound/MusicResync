package pl.lambada.songsync.data.remote.lyrics_providers.others

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import pl.lambada.songsync.util.networking.Ktor.client
import pl.lambada.songsync.util.networking.Ktor.json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * MusicResync "last resort" helper. This is NOT a user-facing provider — it never appears in the providers list.
 * Its job is to rescue songs the real providers (Apple/LRCLib/Spotify/QQ/Netease) failed on, which in practice
 * are the weird/garbled SnapTube-style files whose metadata is too messy to query directly.
 *
 * It uses iTunes' and Deezer's fuzzy search to *canonicalize* a messy query into proper "Artist — Title" pairs
 * (and grabs their album art on the way). The canonical names are then re-queried against the real lyric
 * databases by the caller, which is what lets a track that was unfindable under its junk filename finally match.
 * Both endpoints are public, key-less and return clean JSON. Every call is best-effort and failure-tolerant.
 */
class LastResortAPI {

    /** A canonicalized identity for a messy query: a clean title/artist plus, when available, a cover URL. */
    data class Meta(val title: String, val artist: String, val coverUrl: String?)

    @Serializable
    private data class ITunesResp(val results: List<ITunesItem> = emptyList())

    @Serializable
    private data class ITunesItem(
        val trackName: String? = null,
        val artistName: String? = null,
        val artworkUrl100: String? = null,
    )

    @Serializable
    private data class DeezerResp(val data: List<DeezerItem> = emptyList())

    @Serializable
    private data class DeezerItem(
        val title: String? = null,
        val artist: DeezerArtist? = null,
        val album: DeezerAlbum? = null,
    )

    @Serializable
    private data class DeezerArtist(val name: String? = null)

    @Serializable
    private data class DeezerAlbum(val cover_big: String? = null, val cover_xl: String? = null)

    private suspend fun enc(s: String): String = withContext(Dispatchers.IO) {
        URLEncoder.encode(s.trim(), StandardCharsets.UTF_8.toString())
    }

    /** iTunes 100px artwork upscaled to a large square (their CDN honours arbitrary sizes in the path). */
    private fun upscaleITunes(url: String): String = url.replace("100x100bb", "600x600bb")

    private suspend fun itunes(query: String, limit: Int): List<ITunesItem> = runCatching {
        val resp = client.get("https://itunes.apple.com/search?term=${enc(query)}&entity=song&limit=$limit")
        val body = resp.bodyAsText()
        if (resp.status.value !in 200..299 || body.isBlank()) return emptyList()
        json.decodeFromString<ITunesResp>(body).results
    }.getOrDefault(emptyList())

    private suspend fun deezer(query: String, limit: Int): List<DeezerItem> = runCatching {
        val resp = client.get("https://api.deezer.com/search?q=${enc(query)}&limit=$limit")
        val body = resp.bodyAsText()
        if (resp.status.value !in 200..299 || body.isBlank()) return emptyList()
        json.decodeFromString<DeezerResp>(body).data
    }.getOrDefault(emptyList())

    /**
     * Resolves a messy [query] (already cleaned of junk by FilenameParser) into up to [limit] canonical
     * title/artist pairs, iTunes first then Deezer, de-duplicated by normalized "artist|title".
     */
    suspend fun canonicalize(query: String, limit: Int = 3): List<Meta> {
        if (query.isBlank()) return emptyList()
        // iTunes and Deezer are independent services — query both at once (issue #9: sequential waits stack,
        // and one slow endpoint used to delay the other's answer for nothing). iTunes results still win ties.
        val (itunesItems, deezerItems) = coroutineScope {
            val i = async { itunes(query, limit) }
            val d = async { deezer(query, limit) }
            i.await() to d.await()
        }
        val out = LinkedHashMap<String, Meta>()

        for (item in itunesItems) {
            val title = item.trackName?.takeIf { it.isNotBlank() } ?: continue
            val artist = item.artistName?.takeIf { it.isNotBlank() } ?: continue
            val cover = item.artworkUrl100?.takeIf { it.isNotBlank() }?.let(::upscaleITunes)
            out.putIfAbsent("${artist.lowercase()}|${title.lowercase()}", Meta(title, artist, cover))
        }
        for (item in deezerItems) {
            val title = item.title?.takeIf { it.isNotBlank() } ?: continue
            val artist = item.artist?.name?.takeIf { it.isNotBlank() } ?: continue
            val cover = item.album?.cover_xl?.takeIf { it.isNotBlank() } ?: item.album?.cover_big
            out.putIfAbsent("${artist.lowercase()}|${title.lowercase()}", Meta(title, artist, cover))
        }
        return out.values.toList()
    }

    /** Extra album-cover candidates for the thumbnail picker, beyond what Apple/Spotify already provided. */
    suspend fun covers(title: String, artist: String, max: Int = 4): List<String> {
        val query = "$artist $title"
        val urls = LinkedHashSet<String>()
        itunes(query, max).forEach { it.artworkUrl100?.takeIf { u -> u.isNotBlank() }?.let { u -> urls.add(upscaleITunes(u)) } }
        deezer(query, max).forEach { (it.album?.cover_xl ?: it.album?.cover_big)?.takeIf { u -> u.isNotBlank() }?.let { u -> urls.add(u) } }
        return urls.toList()
    }
}
