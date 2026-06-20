package pl.lambada.songsync.data.remote.lyrics_providers

import android.util.Log
import kotlinx.coroutines.delay
import pl.lambada.songsync.data.remote.lyrics_providers.others.LRCLibAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.NeteaseAPI
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.matching.ConfidenceBreakdown
import pl.lambada.songsync.util.matching.ConfidenceScorer
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.MatchStrategy
import pl.lambada.songsync.util.matching.ProviderResult
import pl.lambada.songsync.util.matching.QueryCandidate
import pl.lambada.songsync.util.networking.withRetry

private const val TAG = "MusicResync"

/** A scored provider hit: enough to rank it, show it in the manual UI, and later fetch its lyrics. */
data class ScoredHit(
    val provider: Providers,
    val strategy: MatchStrategy,
    val result: ProviderResult,
    val confidence: ConfidenceBreakdown,
    val inlineLyrics: String?,   // LRCLib returns synced lyrics inline with search
    val neteaseId: Long?,        // Netease needs a second fetch by id
) {
    val tier get() = confidence.tier
}

/** Tunables surfaced in Settings ("never run into rate limits"). */
data class MatchConfig(
    val providerOrder: List<Providers> = listOf(Providers.LRCLIB, Providers.NETEASE),
    val maxRetries: Int = 3,
    val requestDelayMs: Long = 200,
    val maxCandidatesPerProvider: Int = 4,
)

/**
 * The brain of MusicResync's batch fetch. For one local track it walks the candidate ladder (best-guess query
 * first) across the configured providers, scores every hit with [ConfidenceScorer], and returns the hits sorted
 * by confidence. The top hit is the auto/verify/manual decision; the rest feed the manual-search suggestions
 * (including "same length as your file" duration matches). Every request is wrapped in [withRetry] with
 * exponential backoff and a polite inter-request delay, and all failures are logged and swallowed so a single
 * bad request can never crash the batch.
 *
 * LRCLib is the fast default (no auth, one request returns metadata + synced lyrics + duration). Netease adds
 * catalogue depth for non-English tracks. Other providers stay available through the manual/single-fetch UI.
 */
class SmartLyricsMatcher(
    private val lrcLib: LRCLibAPI = LRCLibAPI(),
    private val netease: NeteaseAPI = NeteaseAPI(),
) {

    /**
     * @return all scored hits, highest confidence first (may be empty). The first element, if any, is the best
     * match; inspect [ScoredHit.tier] to decide auto-accept / verify / manual.
     */
    suspend fun search(
        local: LocalTrack,
        candidates: List<QueryCandidate>,
        config: MatchConfig = MatchConfig(),
        log: (String) -> Unit = { Log.i(TAG, it) },
    ): List<ScoredHit> {
        val displayName = listOfNotNull(local.artist, local.title).joinToString(" - ").ifBlank { "<unknown>" }
        log("[match] \"$displayName\"  dur=${local.durationSec?.let { "${it.toInt()}s" } ?: "?"}  candidates=${candidates.size}")

        val hits = LinkedHashMap<String, ScoredHit>() // dedupe by provider+normalized result
        var best = 0.0

        outer@ for (provider in config.providerOrder) {
            for (cand in candidates) {
                val query = cand.asSearchString()
                if (query.isBlank()) continue

                val found = when (provider) {
                    Providers.LRCLIB -> searchLrcLib(query, local, cand, config, log)
                    Providers.NETEASE -> searchNetease(query, local, cand, config, log)
                    else -> emptyList() // other providers handled via the manual/single-fetch screen
                }

                for (hit in found) {
                    val key = "${hit.provider}|${hit.result.title}|${hit.result.artist}|${hit.result.durationSec}"
                    val existing = hits[key]
                    if (existing == null || hit.confidence.score > existing.confidence.score) hits[key] = hit
                    if (hit.confidence.score > best) {
                        best = hit.confidence.score
                        log("  [${provider.displayName}] ${cand.strategy.label} -> ${hit.result.title} - ${hit.result.artist} (${hit.confidence.percent()}%${if (hit.confidence.durationMatched) ", duration✓" else ""})")
                    }
                }

                delay(config.requestDelayMs)
                if (best >= ConfidenceScorer.AUTO_ACCEPT_THRESHOLD) {
                    log("  [auto-accept] reached ${(best * 100).toInt()}% — stopping early")
                    break@outer
                }
            }
        }

        val ranked = hits.values.sortedByDescending { it.confidence.score }
        if (ranked.isEmpty()) log("  [no match] nothing found across ${config.providerOrder.joinToString { it.displayName }}")
        return ranked
    }

    /** Resolves the synced LRC body for a chosen hit (inline for LRCLib, second fetch for Netease). */
    suspend fun fetchLyrics(hit: ScoredHit, config: MatchConfig = MatchConfig(), log: (String) -> Unit = { Log.i(TAG, it) }): String? {
        return when (hit.provider) {
            Providers.LRCLIB -> hit.inlineLyrics
            Providers.NETEASE -> hit.neteaseId?.let { id ->
                runCatching { withRetry(config.maxRetries) { netease.getSyncedLyrics(id) } }
                    .onFailure { log("  [Netease] lyric fetch failed: ${it.message}") }
                    .getOrNull()
            }
            else -> null
        }?.takeIf { it.isNotBlank() }
    }

    private suspend fun searchLrcLib(
        query: String, local: LocalTrack, cand: QueryCandidate, config: MatchConfig, log: (String) -> Unit
    ): List<ScoredHit> {
        val results = runCatching {
            withRetry(config.maxRetries, onRetry = { a, d, e -> log("  [LRCLib] retry $a in ${d}ms (${e.message})") }) {
                lrcLib.searchCandidates(query)
            }
        }.onFailure { log("  [LRCLib] search failed: ${it.message}") }.getOrDefault(emptyList())

        return results
            .filter { !it.syncedLyrics.isNullOrBlank() } // batch needs synced lyrics
            .take(config.maxCandidatesPerProvider)
            .map { r ->
                val pr = ProviderResult(r.trackName, r.artistName, r.duration, r.albumName, true)
                ScoredHit(Providers.LRCLIB, cand.strategy, pr, ConfidenceScorer.score(local, pr), r.syncedLyrics, null)
            }
    }

    private suspend fun searchNetease(
        query: String, local: LocalTrack, cand: QueryCandidate, config: MatchConfig, log: (String) -> Unit
    ): List<ScoredHit> {
        val songs = runCatching {
            withRetry(config.maxRetries, onRetry = { a, d, e -> log("  [Netease] retry $a in ${d}ms (${e.message})") }) {
                netease.searchCandidates(query, config.maxCandidatesPerProvider)
            }
        }.onFailure { log("  [Netease] search failed: ${it.message}") }.getOrDefault(emptyList())

        return songs.map { s ->
            val pr = ProviderResult(
                title = s.name,
                artist = s.artists.joinToString(", ") { it.name },
                durationSec = s.duration?.let { it / 1000.0 },
                album = s.album?.name,
                hasSyncedLyrics = true,
            )
            ScoredHit(Providers.NETEASE, cand.strategy, pr, ConfidenceScorer.score(local, pr), null, s.id)
        }
    }
}
