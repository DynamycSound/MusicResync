package pl.lambada.songsync.data.remote.lyrics_providers.others

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.domain.model.lyrics_providers.others.LRCLibResponse
import pl.lambada.songsync.util.EmptyQueryException
import pl.lambada.songsync.util.networking.Ktor.client
import pl.lambada.songsync.util.networking.Ktor.json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class LRCLibAPI {
    private val baseURL = "https://lrclib.net/api/"
    private val userAgent = "MusicResync v1.0 (https://github.com/Lambada10/SongSync)"

    /**
     * MusicResync: returns the *full* list of search hits (each already carries duration, album and synced
     * lyrics inline), so the confidence scorer can rank them instead of blindly trusting the first result.
     * A single request yields both metadata and lyrics, which is why LRCLib is the fast default provider.
     */
    suspend fun searchCandidates(query: String): List<LRCLibResponse> {
        val enc = withContext(Dispatchers.IO) {
            URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.toString())
        }
        if (enc.isBlank() || enc == "+") return emptyList()

        val response = client.get(baseURL + "search?q=$enc") { header("User-Agent", userAgent) }
        val body = response.bodyAsText(Charsets.UTF_8)
        if (response.status.value !in 200..299 || body.isBlank() || body == "[]") return emptyList()

        return runCatching { json.decodeFromString<List<LRCLibResponse>>(body) }.getOrDefault(emptyList())
    }

    /**
     * Searches for synced lyrics using the song name and artist name.
     * @param query The SongInfo object with songName and artistName fields filled.
     * @return Search result as a SongInfo object.
     */
    suspend fun getSongInfo(query: SongInfo, offset: Int = 0): SongInfo? {
        val search = withContext(Dispatchers.IO) {
            URLEncoder.encode(
                "${query.songName} ${query.artistName}",
                StandardCharsets.UTF_8.toString()
            )
        }

        if (search == "+")
            throw EmptyQueryException()

        val response = client.get(
            baseURL + "search?q=$search"
        )
        val responseBody = response.bodyAsText(Charsets.UTF_8)

        if (responseBody == "[]" || response.status.value !in 200..299)
            return null

        val json = json.decodeFromString<List<LRCLibResponse>>(responseBody)

        val song = try {
            json[offset]
        } catch (e: IndexOutOfBoundsException) {
            return null
        }

        return SongInfo(
            songName = song.trackName,
            artistName = song.artistName,
            lrcLibID = song.id
        )
    }

    /**
     * Searches for synced lyrics using the song name and artist name.
     * @param id The ID of the song from search results.
     * @return The synced lyrics as a string.
     */
    suspend fun getSyncedLyrics(id: Int): String? {
        val response = client.get(
            baseURL + "get/$id"
        )
        val responseBody = response.bodyAsText(Charsets.UTF_8)

        if (response.status.value !in 200..299 || responseBody == "[]")
            return null

        val json = json.decodeFromString<LRCLibResponse>(responseBody)
        return json.syncedLyrics
    }
}