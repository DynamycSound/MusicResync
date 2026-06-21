package pl.lambada.songsync.data.remote.lyrics_providers.spotify

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.encodeToString
import pl.lambada.songsync.util.dataStore
import pl.lambada.songsync.util.get
import pl.lambada.songsync.util.networking.Ktor.client
import pl.lambada.songsync.util.networking.Ktor.json
import pl.lambada.songsync.util.set

/**
 * Resilient store for Spotify's rotating TOTP secrets.
 *
 * Spotify rotates these roughly every two days. Relying on a single remote source is fragile -- one
 * cease-and-desist (as happened to Thereallo1026/spotify-secrets) takes the whole provider down. So:
 *  - fetch from several mirrors on *independent* infrastructure, first good response wins;
 *  - cache the newest set in DataStore so the app keeps working when every mirror is unreachable;
 *  - ship a bundled copy as a first-install last resort (goes stale once Spotify rotates past it).
 *
 * Nothing here ever blocks app launch or throws to the UI -- Spotify is one optional provider.
 */
object SpotifySecrets {
    // Ordered by preference. The first two are byte-array form, the third is the dict form
    // (version -> bytes) -- same data, different shape -- and lives on different infra (Gitea).
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretBytes.json",
        "https://code.thetadev.de/ThetaDev/spotify-secrets/raw/branch/main/secrets/secretBytes.json",
        "https://code.thetadev.de/ThetaDev/spotify-secrets/raw/branch/main/secrets/secretDict.json",
    )

    // Bundled last resort (captured 2026-06). Lets a fresh install work before its first successful
    // fetch; will stop working once Spotify rotates past version 61, at which point a fetch is needed.
    private val BUNDLED = listOf(
        SecretData(listOf(123,105,79,70,110,59,52,125,60,49,80,70,89,75,80,86,63,53,123,37,117,49,52,93,77,62,47,86,48,104,68,72), 59),
        SecretData(listOf(79,109,69,123,90,65,46,74,94,34,58,48,70,71,92,85,122,63,91,64,87,87), 60),
        SecretData(listOf(44,55,47,42,70,40,34,114,76,74,50,111,120,97,75,76,94,102,43,69,49,120,118,80,64,78), 61),
    )

    private const val MAX_AGE_MS = 24L * 60 * 60 * 1000 // re-fetch at most once per day

    private val secretsJsonKey = stringPreferencesKey("spotify_secrets_json")
    private val fetchedAtKey = longPreferencesKey("spotify_secrets_fetched_at")

    @Volatile
    private var appContext: Context? = null

    /** Wire up app context once (from MainActivity). Uses application context, so no leak. */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** Freshest secret set obtainable with zero network: cached -> bundled. Never empty. */
    fun current(): List<SecretData> {
        val ctx = appContext ?: return BUNDLED
        val raw = ctx.dataStore.get(secretsJsonKey, "")
        if (raw.isBlank()) return BUNDLED
        return runCatching { json.decodeFromString<List<SecretData>>(raw) }
            .getOrDefault(BUNDLED)
            .ifEmpty { BUNDLED }
    }

    /** Epoch ms of the last successful fetch, or null when only bundled secrets have ever been used. */
    fun lastFetchedAt(): Long? {
        val ctx = appContext ?: return null
        return ctx.dataStore.get(fetchedAtKey, 0L).takeIf { it > 0 }
    }

    private fun isFresh(): Boolean {
        val t = lastFetchedAt() ?: return false
        return System.currentTimeMillis() - t < MAX_AGE_MS
    }

    /**
     * Tries each mirror in order and caches the first good response. Never throws. Skips the network
     * entirely when the cache is younger than [MAX_AGE_MS] unless [force]. Returns true if updated.
     */
    suspend fun refresh(force: Boolean = false): Boolean {
        if (!force && isFresh()) return false
        for (url in SOURCES) {
            val list = runCatching { fetch(url) }.getOrNull()
            if (!list.isNullOrEmpty()) {
                persist(list)
                Log.d("SpotifySecrets", "Updated secrets from $url (latest v${list.last().version})")
                return true
            }
        }
        Log.w(
            "SpotifySecrets",
            "All secret mirrors unreachable; falling back to ${if (lastFetchedAt() != null) "cached" else "bundled"} secrets"
        )
        return false
    }

    private suspend fun fetch(url: String): List<SecretData> {
        val body = client.get(url).bodyAsText(Charsets.UTF_8).trim()
        // Two shapes exist in the wild: [{ "version": 59, "secret": [..] }, ..]
        //                           and: { "59": [..], "60": [..] }
        return if (body.startsWith("[")) {
            json.decodeFromString<List<SecretData>>(body)
        } else {
            json.decodeFromString<Map<String, List<Int>>>(body)
                .map { (version, secret) -> SecretData(secret, version.toInt()) }
        }.sortedBy { it.version }
    }

    private fun persist(list: List<SecretData>) {
        val ctx = appContext ?: return
        ctx.dataStore.set(secretsJsonKey, json.encodeToString(list))
        ctx.dataStore.set(fetchedAtKey, System.currentTimeMillis())
    }
}
