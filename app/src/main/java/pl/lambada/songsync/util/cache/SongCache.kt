package pl.lambada.songsync.util.cache

import android.content.Context
import android.util.Log
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.matching.LyricState
import pl.lambada.songsync.util.matching.SongMatchInfo
import java.io.File

/**
 * One persisted record per audio file. Keyed by the audio [filePath] in [SongCache].
 *
 * @param state last-known lyric state (so launch can colour rows without a disk scan).
 * @param chosenProvider displayName of the provider that produced the saved lyrics (re-tried first next time).
 * @param failedProviders displayNames of providers that had no synced lyrics for this song.
 * @param offsetMs cumulative timing offset (ms) already baked into the .lrc — remembered so the slider reopens
 *        at the saved value instead of snapping to 0.
 * @param lastCheckedAt epoch millis of the last fresh scan/fetch.
 */
@kotlinx.serialization.Serializable
data class SongCacheEntry(
    val state: LyricState,
    val chosenProvider: String? = null,
    val failedProviders: List<String> = emptyList(),
    val offsetMs: Int = 0,
    val lastCheckedAt: Long = 0L,
)

/**
 * Persistent per-song cache (a single JSON file in the app's `filesDir`). Lets the home list render its
 * green/red notes instantly on launch from the last-known state, while a fresh disk scan refreshes in the
 * background. Also the home for offset memory and which-provider-was-used / which-failed memory.
 *
 * Thread-safe; all access is synchronized. Interactive single-song writes persist immediately; bulk batch
 * updates go through [setStateDeferred] + [flush] so a long batch doesn't rewrite the file per song.
 */
object SongCache {
    private const val TAG = "SongCache"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private var file: File? = null
    private val map = LinkedHashMap<String, SongCacheEntry>()
    private var loaded = false

    /** Resolve the backing file (once) and load it into memory. Safe to call repeatedly. */
    @Synchronized
    fun init(context: Context) {
        if (file == null) file = File(context.filesDir, "song_cache.json")
        if (!loaded) load()
    }

    @Synchronized
    private fun load() {
        loaded = true
        val f = file ?: return
        runCatching {
            if (f.exists()) {
                val text = f.readText()
                if (text.isNotBlank()) {
                    val parsed = json.decodeFromString<Map<String, SongCacheEntry>>(text)
                    map.clear()
                    map.putAll(parsed)
                }
            }
        }.onFailure { Log.w(TAG, "failed to load cache: ${it.message}") }
    }

    @Synchronized
    fun get(path: String): SongCacheEntry? = map[path]

    /** Immediate, persisted upsert (for interactive single-song actions). */
    @Synchronized
    fun put(path: String, entry: SongCacheEntry) {
        map[path] = entry
        flush()
    }

    /** Read-modify-write upsert that preserves untouched fields; persisted immediately. */
    @Synchronized
    fun update(path: String, block: (SongCacheEntry?) -> SongCacheEntry) {
        map[path] = block(map[path])
        flush()
    }

    /** Memory-only state update — used during a batch; pair with [flush] at the end. Keeps offset/provider. */
    @Synchronized
    fun setStateDeferred(path: String, info: SongMatchInfo) {
        val prev = map[path]
        map[path] = (prev ?: SongCacheEntry(info.state)).copy(
            state = info.state,
            chosenProvider = info.provider?.displayName ?: prev?.chosenProvider,
            // Remember which providers came up empty for this song (drives "found / no match / not tried"
            // in the song's provider list). Only replace the remembered set when this run actually tried some.
            failedProviders = info.failedProviders.map { it.displayName }
                .ifEmpty { prev?.failedProviders ?: emptyList() },
            lastCheckedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Bulk-replace states from a fresh disk scan while preserving each song's remembered offset/provider.
     * Persisted once at the end.
     */
    @Synchronized
    fun replaceStates(states: Map<String, LyricState>) {
        val now = System.currentTimeMillis()
        var changed = false
        states.forEach { (path, st) ->
            val prev = map[path]
            // A disk scan can only observe "no .lrc here" (NONE -> NO_LYRICS); it can't tell WHY. Keep the
            // remembered FAILED state (a real fetch/save error) instead of flattening it into plain
            // "no lyrics", so failed songs stay identifiable across restarts.
            val effective = if (st == LyricState.NO_LYRICS && prev?.state == LyricState.FAILED) LyricState.FAILED else st
            if (prev?.state != effective) {
                map[path] = (prev ?: SongCacheEntry(effective)).copy(state = effective, lastCheckedAt = now)
                changed = true
            }
        }
        // Skip the (large) JSON rewrite entirely when the scan found nothing new — this runs on every Home
        // resume, and serializing thousands of entries each time was pure waste.
        if (changed) flush()
    }

    @Synchronized
    fun remove(path: String) {
        if (map.remove(path) != null) flush()
    }

    /** A [SongMatchInfo] view of the cached entry (so the home list can seed instantly without disk I/O). */
    @Synchronized
    fun matchInfo(path: String): SongMatchInfo? {
        val e = map[path] ?: return null
        val provider = e.chosenProvider?.let { name -> Providers.entries.find { it.displayName == name } }
        val failed = e.failedProviders.mapNotNull { name -> Providers.entries.find { it.displayName == name } }
        return SongMatchInfo(state = e.state, provider = provider, failedProviders = failed)
    }

    /** Persist the in-memory map atomically (write to a temp file, then rename). */
    @Synchronized
    fun flush() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(json.encodeToString<Map<String, SongCacheEntry>>(map))
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "failed to persist cache: ${it.message}") }
    }
}
