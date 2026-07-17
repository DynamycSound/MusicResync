package pl.lambada.songsync.util.networking

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object Ktor {
    val client = HttpClient(CIO.create {
        requestTimeout = 12_000
    }) {
        // Bound every request so a slow/down provider (e.g. Spotify) can't make the search hang forever -- it
        // fails fast, the retry/backoff handles transient blips, and the matcher moves on. A healthy lyrics
        // provider answers well under these bounds; tighter limits are what keeps the worst case short (#9).
        install(HttpTimeout) {
            connectTimeoutMillis = 6_000
            requestTimeoutMillis = 12_000
            socketTimeoutMillis = 12_000
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            })
        }
    }

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
}