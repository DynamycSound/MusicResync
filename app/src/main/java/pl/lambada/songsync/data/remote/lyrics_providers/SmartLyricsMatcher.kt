package pl.lambada.songsync.data.remote.lyrics_providers

import android.util.Log
import kotlinx.coroutines.delay
import pl.lambada.songsync.data.remote.lyrics_providers.others.BetterLyricsAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.LRCLibAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.LastResortAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.LyricsPlusAPI
import pl.lambada.songsync.data.remote.lyrics_providers.others.NeteaseAPI
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.util.Providers
import pl.lambada.songsync.util.matching.ConfidenceBreakdown
import pl.lambada.songsync.util.matching.ConfidenceScorer
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.MatchStrategy
import pl.lambada.songsync.util.matching.MatchTier
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
    /** Optional: enables Apple/Musixmatch/Spotify/QQ as scored fallbacks in the single-song flow. */
    private val providerService: LyricsProviderService? = null,
    /** Internal "last resort" canonicalizer (iTunes/Deezer) — not a user-facing provider. */
    private val lastResortApi: LastResortAPI = LastResortAPI(),
    /** Direct-fetch providers (no search endpoint): word-by-word/TTML sources added for issue #4. */
    private val lyricsPlus: LyricsPlusAPI = LyricsPlusAPI(),
    private val betterLyrics: BetterLyricsAPI = BetterLyricsAPI(),
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
        onAttempt: (provider: Providers) -> Unit = {},
    ): List<ScoredHit> {
        val displayName = listOfNotNull(local.artist, local.title).joinToString(" - ").ifBlank { "<unknown>" }
        log("[match] \"$displayName\"  dur=${local.durationSec?.let { "${it.toInt()}s" } ?: "?"}  candidates=${candidates.size}")

        val hits = LinkedHashMap<String, ScoredHit>() // dedupe by provider+normalized result
        var best = 0.0

        outer@ for (provider in config.providerOrder) {
            onAttempt(provider)
            for (cand in candidates) {
                val query = cand.asSearchString()
                if (query.isBlank()) continue

                val found = when (provider) {
                    Providers.LRCLIB -> searchLrcLib(query, local, cand, config, log)
                    Providers.NETEASE -> searchNetease(query, local, cand, config, log)
                    Providers.LYRICSPLUS, Providers.BETTERLYRICS -> searchDirect(provider, local, cand, config, log)
                    else -> searchGeneric(provider, local, cand, config, log)
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

    /** A best-effort PLAIN (unsynced) lyrics hit, used only when no provider has synced lyrics. */
    data class PlainHit(val provider: Providers, val result: ProviderResult, val plainLyrics: String)

    /**
     * Best-effort PLAIN (unsynced) lyrics from LRCLib, used as a fallback when no provider returned synced
     * lyrics and the user opted in. Scores candidates the same way as the synced search so a wrong result is
     * still rejected; returns the best non-rejected plain match or null.
     */
    suspend fun fetchPlainLyrics(
        local: LocalTrack,
        candidates: List<QueryCandidate>,
        config: MatchConfig = MatchConfig(),
        log: (String) -> Unit = { Log.i(TAG, it) },
    ): PlainHit? {
        for (cand in candidates) {
            val query = cand.asSearchString()
            if (query.isBlank()) continue
            val results = runCatching {
                withRetry(config.maxRetries) { lrcLib.searchCandidates(query) }
            }.onFailure { log("  [LRCLib/plain] search failed: ${it.message}") }.getOrDefault(emptyList())

            val best = results
                .filter { !it.plainLyrics.isNullOrBlank() }
                .map { r ->
                    val pr = ProviderResult(r.trackName, r.artistName, r.duration, r.albumName, false)
                    Triple(pr, scoreFor(local, pr, cand.strategy), r.plainLyrics!!)
                }
                .sortedByDescending { it.second.score }
                .firstOrNull { it.second.tier != MatchTier.REJECT }

            if (best != null) {
                log("  [plain fallback] ${best.first.title} - ${best.first.artist} (${best.second.percent()}%)")
                return PlainHit(Providers.LRCLIB, best.first, best.third)
            }
            delay(config.requestDelayMs)
        }
        return null
    }

    /** A rescued result from the last-resort path: lyrics (synced or plain) under a canonical title/artist. */
    data class LastResortHit(
        val title: String,
        val artist: String,
        val lyrics: String,
        val synced: Boolean,
        val coverUrl: String? = null,
    )

    /**
     * The genuine last resort, tried only after every real provider failed. The usual reason for that failure is
     * a garbled filename whose query never matched anything; here we canonicalize it through iTunes/Deezer
     * ("🔥 Ed Sheeran - 🍕Shape of You🍕 [FREE]" -> "Ed Sheeran / Shape of You") and re-query LRCLib under the
     * clean name, which routinely finds the track that was invisible before. Prefers synced lyrics, falls back to
     * plain. Because the local tags are (by definition) unreliable here, we don't reject on them — we lean on
     * duration when we have it and otherwise trust the canonical top hit. Returns null if nothing turns up.
     */
    suspend fun lastResort(
        local: LocalTrack,
        candidates: List<QueryCandidate>,
        config: MatchConfig = MatchConfig(),
        log: (String) -> Unit = { Log.i(TAG, it) },
    ): LastResortHit? {
        // Try several reasonable candidate queries, not just the first — a single garbled query often
        // canonicalizes badly, while the loosened/filename variants land the right canonical name.
        val queries = candidates.map { it.asSearchString() }.filter { it.isNotBlank() }.distinct().take(3)
        if (queries.isEmpty()) return null

        // Collect every rescued LRCLib hit (across all queries/canonical names) WITHOUT accepting any yet, then
        // hand them to the scorer-based validator. The old code accepted the closest-duration hit (or just the
        // first when duration was unknown) with no similarity check, so a wrong song by the same artist could be
        // rescued. selectBestRescue rescps each hit against the original local track via ConfidenceScorer.
        val rescue = ArrayList<RescueCandidate>()
        for (query in queries) {
            val metas = runCatching { lastResortApi.canonicalize(query) }
                .onFailure { log("  [last-resort] canonicalize failed: ${it.message}") }
                .getOrDefault(emptyList())
            for (meta in metas) {
                val results = runCatching { lrcLib.searchCandidates("${meta.artist} ${meta.title}") }
                    .getOrDefault(emptyList())
                results.forEach { r ->
                    rescue.add(
                        RescueCandidate(
                            result = ProviderResult(
                                title = r.trackName,
                                artist = r.artistName,
                                durationSec = r.duration,
                                album = r.albumName,
                                hasSyncedLyrics = !r.syncedLyrics.isNullOrBlank(),
                            ),
                            syncedLyrics = r.syncedLyrics,
                            plainLyrics = r.plainLyrics,
                            coverUrl = meta.coverUrl,
                        )
                    )
                }
                delay(config.requestDelayMs)
            }
        }

        val localViews = buildList {
            add(local)
            candidates.forEach { c -> add(LocalTrack(c.title, c.artist, local.durationSec, local.album)) }
        }.distinct()
        val hit = selectBestRescue(localViews, rescue)
        if (hit == null) log("  [last-resort] no believable rescue cleared the confidence bar (${rescue.size} candidates)")
        else log("  [last-resort] ${if (hit.synced) "synced" else "plain"} rescue -> ${hit.artist} - ${hit.title}")
        return hit
    }

    /** Resolves the synced LRC body for a chosen hit (inline for LRCLib/generic, second fetch for Netease). */
    suspend fun fetchLyrics(hit: ScoredHit, config: MatchConfig = MatchConfig(), log: (String) -> Unit = { Log.i(TAG, it) }): String? {
        return when (hit.provider) {
            Providers.NETEASE -> hit.neteaseId?.let { id ->
                runCatching { withRetry(config.maxRetries) { netease.getSyncedLyrics(id) } }
                    .onFailure { log("  [Netease] lyric fetch failed: ${it.message}") }
                    .getOrNull()
            }
            else -> hit.inlineLyrics // LRCLib + generic providers carry the lyrics inline
        }?.takeIf { it.isNotBlank() }
    }

    /**
     * Direct-fetch path for LyricsPlus/BetterLyrics: these services take title/artist(/duration/album) and do
     * their own matching server-side, returning lyrics or nothing — there are no search results to score. The
     * returned hit therefore echoes the candidate we asked for; because that makes a perfect score
     * self-fulfilling, the hit is only allowed to AUTO_ACCEPT when the local duration was sent along (the
     * service verified the length) — otherwise it is capped at REVIEW like other unverifiable matches.
     */
    private suspend fun searchDirect(
        provider: Providers, local: LocalTrack, cand: QueryCandidate, config: MatchConfig, log: (String) -> Unit
    ): List<ScoredHit> {
        val artist = cand.artist?.takeIf { it.isNotBlank() } ?: local.artist ?: return emptyList()
        val durationSec = local.durationSec?.toInt()?.takeIf { it > 0 }

        val lyrics = runCatching {
            withRetry(config.maxRetries, onRetry = { a, d, e ->
                log("  [${provider.displayName}] retry $a in ${d}ms (${e.message})")
            }) {
                when (provider) {
                    Providers.LYRICSPLUS -> lyricsPlus.getSyncedLyrics(cand.title, artist, durationSec, local.album)
                    else -> betterLyrics.getSyncedLyrics(cand.title, artist, durationSec, local.album)
                }
            }
        }.onFailure { log("  [${provider.displayName}] failed: ${it.message}") }.getOrNull()
        if (lyrics.isNullOrBlank()) return emptyList()

        val pr = ProviderResult(
            title = cand.title,
            artist = artist,
            durationSec = if (durationSec != null) local.durationSec else null,
            album = local.album,
            hasSyncedLyrics = true,
        )
        var conf = scoreFor(local, pr, cand.strategy)
        if (durationSec == null && conf.tier == MatchTier.AUTO_ACCEPT) {
            conf = conf.copy(score = 0.80, tier = MatchTier.REVIEW)
        }
        return listOf(ScoredHit(provider, cand.strategy, pr, conf, lyrics, null))
    }

    /**
     * Scored multi-result path for providers that don't expose a full search endpoint here (Apple, Spotify, QQ).
     * We walk a few result offsets, score each candidate, and keep the believable ones. This fixes cases where a
     * provider's *first* result is a different song (e.g. a short title like "Au" for "Audubon"): the bad top
     * hit is rejected by the scorer and later offsets still get a chance to match. Lyrics are only fetched when
     * the metadata score is already at least review-grade.
     */
    private suspend fun searchGeneric(
        provider: Providers, local: LocalTrack, cand: QueryCandidate, config: MatchConfig, log: (String) -> Unit
    ): List<ScoredHit> {
        val service = providerService ?: return emptyList()
        val hits = LinkedHashMap<String, ScoredHit>()

        for (offset in 0 until config.maxCandidatesPerProvider) {
            val info = runCatching {
                withRetry(config.maxRetries, onRetry = { a, d, e ->
                    log("  [${provider.displayName}] retry $a in ${d}ms (${e.message})")
                }) {
                    service.getSongInfo(SongInfo(cand.title, cand.artist), offset, provider)
                }
            }.onFailure {
                log("  [${provider.displayName}] failed: ${it.message}")
            }.getOrNull() ?: break

            val pr = ProviderResult(info.songName, info.artistName, null, null, true)
            val conf = scoreFor(local, pr, cand.strategy)
            val lyrics = if (conf.score >= ConfidenceScorer.REVIEW_THRESHOLD) {
                runCatching { withRetry(config.maxRetries) { service.getSyncedLyrics(info, provider) } }.getOrNull()
            } else null

            val hit = ScoredHit(provider, cand.strategy, pr, conf, lyrics, null)
            val key = "${hit.provider}|${hit.result.title}|${hit.result.artist}|${hit.result.durationSec}"
            val existing = hits[key]
            if (existing == null || hit.confidence.score > existing.confidence.score) hits[key] = hit
            if (hit.confidence.score >= ConfidenceScorer.AUTO_ACCEPT_THRESHOLD) break
        }
        return hits.values.sortedByDescending { it.confidence.score }
    }

    /**
     * Scores a hit, then caps remix/version base-song fallbacks at REVIEW. When the only match we could find is
     * the BASE song for a remix (via the loosened candidate), its lyrics are usually right but the *timing*
     * differs (a remix changes tempo), so we never silently auto-accept it -- the user verifies and nudges the
     * offset in the player instead.
     */
    private fun scoreFor(local: LocalTrack, pr: ProviderResult, strategy: MatchStrategy): ConfidenceBreakdown {
        val conf = ConfidenceScorer.score(local, pr)
        return if (strategy == MatchStrategy.FILENAME_LOOSE && conf.tier == MatchTier.AUTO_ACCEPT)
            conf.copy(score = 0.80, tier = MatchTier.REVIEW)
        else conf
    }

    private suspend fun searchLrcLib(
        query: String, local: LocalTrack, cand: QueryCandidate, config: MatchConfig, log: (String) -> Unit
    ): List<ScoredHit> {
        val results = runCatching {
            withRetry(config.maxRetries, onRetry = { a, d, e -> log("  [LRCLib] retry $a in ${d}ms (${e.message})") }) {
                lrcLib.searchCandidates(query)
            }
        }.onFailure { log("  [LRCLib] search failed: ${it.message}") }.getOrDefault(emptyList())

        // Score ALL synced hits, then keep the best few. A title-only query can return 20 results where the
        // right track (matched by duration) is well down the relevance list -- scoring before the cap lets it
        // survive instead of being truncated away by LRCLib's ordering.
        return results
            .filter { !it.syncedLyrics.isNullOrBlank() } // batch needs synced lyrics
            .map { r ->
                val pr = ProviderResult(r.trackName, r.artistName, r.duration, r.albumName, true)
                ScoredHit(Providers.LRCLIB, cand.strategy, pr, scoreFor(local, pr, cand.strategy), r.syncedLyrics, null)
            }
            .sortedByDescending { it.confidence.score }
            .take(config.maxCandidatesPerProvider)
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
            ScoredHit(Providers.NETEASE, cand.strategy, pr, scoreFor(local, pr, cand.strategy), null, s.id)
        }
    }
}

/** A raw rescued lyrics candidate (a canonicalized provider result + its lyrics) awaiting confidence validation. */
data class RescueCandidate(
    val result: ProviderResult,
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val coverUrl: String?,
)

/**
 * Validates and ranks last-resort [candidates] against several views of the local track (the raw tags plus the
 * cleaned filename-derived candidates projected into [LocalTrack]s). This lets a rescue be judged against the
 * *best* available local title/artist guess instead of whichever raw tag happened to be on disk, while still
 * rejecting a canonicalization that guessed a different song by the same artist.
 *
 * Acceptance bar:
 *  - confidence tier must be at least REVIEW (>= [ConfidenceScorer.REVIEW_THRESHOLD]);
 *  - when the local duration is known, the candidate must earn some duration credit (different-length tracks are
 *    rejected even if the artist matches);
 *  - and the best title similarity must clear a modest floor unless we have an exact duration match. This blocks
 *    same-artist wrong-song rescues that used to sneak through on artist similarity alone.
 *
 * Synced hits are preferred over plain, then higher confidence wins. Rescued hits remain REVIEW-grade for the
 * caller (we never auto-accept a last-resort guess). Pure and Android-free so it is unit-testable on the JVM.
 */
fun selectBestRescue(localViews: List<LocalTrack>, candidates: List<RescueCandidate>): SmartLyricsMatcher.LastResortHit? {
    val durationView = localViews.firstOrNull { it.durationSec != null && it.durationSec > 0 }
    val localHasDuration = durationView?.durationSec != null

    data class Scored(val c: RescueCandidate, val synced: Boolean, val conf: ConfidenceBreakdown)

    val scored = candidates.mapNotNull { c ->
        val synced = !c.syncedLyrics.isNullOrBlank()
        val body = if (synced) c.syncedLyrics else c.plainLyrics
        if (body.isNullOrBlank()) return@mapNotNull null

        val conf = localViews
            .map { ConfidenceScorer.score(it, c.result) }
            .maxByOrNull { it.score } ?: return@mapNotNull null

        if (conf.tier == MatchTier.REJECT) return@mapNotNull null
        // Duration sanity: if we know the local length, a candidate that earns zero duration credit is a
        // different song — don't let it ride in on a similar title/artist alone.
        if (localHasDuration && conf.duration <= 0.0) return@mapNotNull null
        // Last-resort still needs some title evidence. This blocks same-artist false rescues like
        // "$uicideboy$ - Every Day I Pimp" -> "All of My Problems Always Involve Me" while still allowing a
        // rescue through when the best cleaned candidate title really matches or the duration is exact.
        if (!conf.durationMatched && conf.title < 0.55) return@mapNotNull null

        Scored(c, synced, conf)
    }

    val best = scored.sortedWith(
        compareByDescending<Scored> { it.synced }.thenByDescending { it.conf.score }
    ).firstOrNull() ?: return null

    val body = if (best.synced) best.c.syncedLyrics!! else best.c.plainLyrics!!
    return SmartLyricsMatcher.LastResortHit(
        title = best.c.result.title.orEmpty(),
        artist = best.c.result.artist.orEmpty(),
        lyrics = body,
        synced = best.synced,
        coverUrl = best.c.coverUrl,
    )
}
