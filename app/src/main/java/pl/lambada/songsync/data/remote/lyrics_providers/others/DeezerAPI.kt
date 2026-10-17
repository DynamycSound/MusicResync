package pl.lambada.songsync.data.remote.lyrics_providers.others

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.util.networking.Ktor.client
import pl.lambada.songsync.util.networking.Ktor.json

/**
 * Deezer: its public search (title, artist, length) plus the synced lyrics its web player shows, which it serves
 * to signed-out visitors too. Strong on music the other catalogues miss, Balkan rap included. No key or account:
 * the player's anonymous session token is fetched on demand and reused until shortly before it expires.
 */
class DeezerAPI {

    /** A search hit: what the scorer needs, and the id the lyrics are fetched by. */
    data class Track(val id: Long, val title: String, val artist: String, val album: String?, val durationSec: Double?)

    @Serializable
    private data class SearchResponse(val data: List<SearchTrack> = emptyList())

    @Serializable
    private data class SearchTrack(
        val id: Long,
        val title: String,
        val duration: Int? = null,
        val artist: Named? = null,
        val album: Album? = null,
    )

    @Serializable
    private data class Named(val name: String)

    @Serializable
    private data class Album(val title: String? = null)

    @Serializable
    private data class AnonymousLogin(val jwt: String? = null)

    /** Up to [limit] tracks for a free-text [query]. Empty on any failure. */
    suspend fun searchCandidates(query: String, limit: Int = 8): List<Track> {
        if (query.isBlank()) return emptyList()
        return runCatching {
            val response = client.get("$API/search") {
                parameter("q", query)
                parameter("limit", limit)
            }
            if (response.status.value !in 200..299) return@runCatching emptyList()
            json.decodeFromString<SearchResponse>(response.bodyAsText(Charsets.UTF_8)).data.map {
                Track(it.id, it.title, it.artist?.name.orEmpty(), it.album?.title, it.duration?.toDouble())
            }
        }.getOrDefault(emptyList())
    }

    /** The [offset]-th search hit for a manual search, as a [SongInfo] carrying its Deezer id. */
    suspend fun getSongInfo(query: SongInfo, offset: Int = 0): SongInfo? {
        val search = listOfNotNull(query.songName, query.artistName).joinToString(" ").trim()
        val track = searchCandidates(search, limit = offset + 1).getOrNull(offset) ?: return null
        return SongInfo(songName = track.title, artistName = track.artist, deezerID = track.id)
    }

    /** The track's lyrics as LRC, or null when Deezer has no timed lyrics for it. */
    suspend fun getSyncedLyrics(id: Long): String? {
        val body = buildJsonObject {
            put("operationName", "SynchronizedTrackLyrics")
            putJsonObject("variables") { put("trackId", id.toString()) }
            put("query", LYRICS_QUERY)
        }.toString()

        // A token can be refused before its stated expiry; one fresh token is worth a single retry.
        repeat(2) { attempt ->
            val token = token(forceNew = attempt > 0) ?: return null
            val response = client.post(PIPE) {
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(body)
            }
            if (response.status.value == 401) return@repeat
            if (response.status.value !in 200..299) return null
            val lines = runCatching {
                json.parseToJsonElement(response.bodyAsText(Charsets.UTF_8)).jsonObject["data"]
                    ?.jsonObject?.get("track")?.jsonObjectOrNull()
                    ?.get("lyrics")?.jsonObjectOrNull()
                    ?.get("synchronizedLines")?.jsonArray
            }.getOrNull() ?: return null
            return toLrc(lines)
        }
        return null
    }

    private suspend fun token(forceNew: Boolean): String? = tokenLock.withLock {
        val now = System.currentTimeMillis()
        if (!forceNew && cachedToken != null && now < tokenExpiresAt) return cachedToken
        val response = client.get(AUTH) {
            parameter("jo", "p")
            parameter("rto", "c")
            parameter("i", "c")
        }
        if (response.status.value !in 200..299) return null
        val jwt = runCatching {
            json.decodeFromString<AnonymousLogin>(response.bodyAsText(Charsets.UTF_8)).jwt
        }.getOrNull() ?: return null
        cachedToken = jwt
        tokenExpiresAt = now + TOKEN_REUSE_MS
        jwt
    }

    companion object {
        private const val API = "https://api.deezer.com"
        private const val PIPE = "https://pipe.deezer.com/api"
        private const val AUTH = "https://auth.deezer.com/login/anonymous"

        /** The anonymous token lives six minutes; renewing at five leaves room for slow requests. */
        private const val TOKEN_REUSE_MS = 5 * 60 * 1000L

        private const val LYRICS_QUERY =
            "query SynchronizedTrackLyrics(\$trackId: String!) { track(trackId: \$trackId) { lyrics { synchronizedLines { lrcTimestamp line } } } }"

        private val TIMESTAMP = Regex("""^\[\d{1,3}:\d{2}(\.\d{1,3})?]$""")

        // Shared across instances: the service and the matcher each make their own DeezerAPI.
        private val tokenLock = Mutex()
        private var cachedToken: String? = null
        private var tokenExpiresAt = 0L

        /**
         * Deezer's `synchronizedLines` as LRC: each line's ready-made `[mm:ss.xx]` stamp and its text. Lines
         * without a usable stamp are dropped; null when nothing timed is left (plain-only lyrics come back empty).
         */
        internal fun toLrc(lines: JsonArray): String? {
            val out = lines.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val stamp = obj["lrcTimestamp"]?.jsonPrimitive?.contentOrNull?.trim() ?: return@mapNotNull null
                if (!TIMESTAMP.matches(stamp)) return@mapNotNull null
                stamp + obj["line"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
            return out.takeIf { it.isNotEmpty() }?.joinToString("\n")
        }

        private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject
    }
}
