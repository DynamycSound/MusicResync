package pl.lambada.songsync.data.remote.lyrics_providers.others

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import pl.lambada.songsync.util.TtmlLyricsParser
import pl.lambada.songsync.util.networking.Ktor.client
import pl.lambada.songsync.util.networking.Ktor.json

/**
 * BetterLyrics provider (lyrics-api.boidu.dev): serves Apple-style TTML with word-level timing behind a direct
 * title/artist(/duration/album) query — no search/offset pagination. The TTML body is converted to the app's
 * enhanced-LRC dialect by [TtmlLyricsParser].
 */
class BetterLyricsAPI {
    private val baseURL = "https://lyrics-api.boidu.dev"

    @Serializable
    private data class TtmlResponse(val ttml: String? = null)

    /** Fetches lyrics and returns them in the app's LRC dialect, or null when nothing matched. */
    suspend fun getSyncedLyrics(
        title: String,
        artist: String,
        durationSec: Int? = null,
        album: String? = null,
        multiPersonWordByWord: Boolean = false,
    ): String? {
        if (title.isBlank() || artist.isBlank()) return null

        // Exact title/artist on purpose: the service does its own matching, and normalizing here can land on a
        // different edition (radio edit vs original) whose timing won't line up with the local file.
        val response = client.get("$baseURL/getLyrics") {
            parameter("s", title)
            parameter("a", artist)
            if (durationSec != null && durationSec > 0) parameter("d", durationSec)
            if (!album.isNullOrBlank()) parameter("al", album)
        }
        val body = response.bodyAsText(Charsets.UTF_8)
        if (response.status.value !in 200..299 || body.isBlank()) return null

        val ttml = runCatching { json.decodeFromString<TtmlResponse>(body).ttml }.getOrNull()
            ?: return null
        val lines = TtmlLyricsParser.parse(ttml)
        return TtmlLyricsParser.toLrc(lines, multiPersonWordByWord)
    }
}
