package pl.lambada.songsync.data.remote.lyrics_providers

import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
    /** Delay before the first retry of a failed request; doubles per attempt (500ms -> 1s -> 2s). */
    val retryBaseDelayMs: Long = 500,
    /** Hard wall-clock budget for ONE provider's whole candidate walk (search + retries). Issue #9: without
     *  this, a single unresponsive provider could burn retries × timeouts × candidates before the next provider
     *  even got a chance. */
    val providerTimeoutMs: Long = 25_000,
) {
    companion object {
        /**
         * "Fast mode" for the single-song search: only the top [maxProviders] providers, one retry, a short
         * per-provider budget and minimal politeness delay. Trades a little reliability for speed — the regular
         * config stays the default for batch runs.
         */
        fun fast(providerOrder: List<Providers>, maxProviders: Int = 2) = MatchConfig(
            providerOrder = providerOrder.take(maxProviders),
            maxRetries = 1,
            requestDelayMs = 50,
            maxCandidatesPerProvider = 3,
            retryBaseDelayMs = 250,
            providerTimeoutMs = 8_000,
        )
    }
}

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
     *
     * All providers are queried CONCURRENTLY (issue #9): the old strictly sequential ladder could stack
     * 7 providers × retries × 20s network timeouts into a multi-minute wait before ever reaching the provider
     * that actually had the lyrics. Requests to any single provider remain sequential with a polite delay, so
     * per-provider rate limiting is unchanged — only the waiting overlaps. Each provider is additionally
     * hard-bounded by [MatchConfig.providerTimeoutMs], so one hung host can never stall the whole search.
     *
     * [onSkipped] fires for providers whose in-flight search was cancelled by the early auto-accept stop —
     * they were never really tried, so callers should not paint them as "no lyrics".
     */
    suspend fun search(
        local: LocalTrack,
        candidates: List<QueryCandidate>,
        config: MatchConfig = MatchConfig(),
        log: (String) -> Unit = { Log.i(TAG, it) },
        onAttempt: (provider: Providers) -> Unit = {},
        onSkipped: (provider: Providers) -> Unit = {},
    ): List<ScoredHit> = coroutineScope {
        val displayName = listOfNotNull(local.artist, local.title).joinToString(" - ").ifBlank { "<unknown>" }
        log("[match] \"$displayName\"  dur=${local.durationSec?.let { "${it.toInt()}s" } ?: "?"}  candidates=${candidates.size}  providers=${config.providerOrder.size} (parallel)")

        val jobs = config.providerOrder.map { provider ->
            onAttempt(provider)
            async {
                withTimeoutOrNull(config.providerTimeoutMs) {
                    searchProvider(provider, local, candidates, config, log)
                } ?: emptyList<ScoredHit>().also {
                    log("  [${provider.displayName}] no answer within ${config.providerTimeoutMs / 1000}s — skipped")
                }
            }
        }

        // Collect in the user's configured priority order so the early-stop semantics match the old ladder:
        // once the chain so far holds an auto-accept match, lower-priority providers still in flight are
        // cancelled. Ones that already finished are merged anyway — free extra manual-search suggestions.
        val hits = LinkedHashMap<String, ScoredHit>() // dedupe by provider+normalized result
        var best = 0.0
        var stopped = false
        for ((index, deferred) in jobs.withIndex()) {
            val provider = config.providerOrder[index]
            if (stopped && !deferred.isCompleted) {
                deferred.cancel()
                onSkipped(provider)
                continue
            }
            for (hit in deferred.await()) {
                val key = "${hit.provider}|${hit.result.title}|${hit.result.artist}|${hit.result.durationSec}"
                val existing = hits[key]
                if (existing == null || hit.confidence.score > existing.confidence.score) hits[key] = hit
                if (hit.confidence.score > best) {
                    best = hit.confidence.score
                    log("  [${provider.displayName}] ${hit.strategy.label} -> ${hit.result.title} - ${hit.result.artist} (${hit.confidence.percent()}%${if (hit.confidence.durationMatched) ", duration✓" else ""})")
                }
            }
            if (!stopped && best >= ConfidenceScorer.AUTO_ACCEPT_THRESHOLD) {
                log("  [auto-accept] reached ${(best * 100).toInt()}% — cancelling providers still in flight")
                stopped = true
            }
        }

        val ranked = hits.values.sortedByDescending { it.confidence.score }
        if (ranked.isEmpty()) log("  [no match] nothing found across ${config.providerOrder.joinToString { it.displayName }}")
        ranked
    }

    /**
     * One provider's full candidate walk. Within a provider the requests stay sequential and politely delayed
     * (rate-limit friendly); stops as soon as this provider produced an auto-accept-grade hit — weaker
     * candidates can't beat it.
     */
    private suspend fun searchProvider(
        provider: Providers,
        local: LocalTrack,
        candidates: List<QueryCandidate>,
        config: MatchConfig,
        log: (String) -> Unit,
    ): List<ScoredHit> {
        val providerHits = LinkedHashMap<String, ScoredHit>()
        var providerBest = 0.0
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
                val existing = providerHits[key]
                if (existing == null || hit.confidence.score > existing.confidence.score) providerHits[key] = hit
                if (hit.confidence.score > providerBest) providerBest = hit.confidence.score
            }
            if (providerBest >= ConfidenceScorer.AUTO_ACCEPT_THRESHOLD) break
            delay(config.requestDelayMs)
        }
        return providerHits.values.toList()
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
    ): PlainHit? = withTimeoutOrNull(config.providerTimeoutMs) {
        // Same hard time budget as a regular provider (issue #9): this fallback runs after a failed search, so
        // letting its retries stack would just re-create the long wait we removed.
        for (cand in candidates) {
            val query = cand.asSearchString()
            if (query.isBlank()) continue
            val results = runCatching {
                withRetry(config.maxRetries, config.retryBaseDelayMs) { lrcLib.searchCandidates(query) }
            }.onFailure { log("  [LRCLib/plain] search failed: ${it.message}") }.getOrDefault(emptyList())

            val best = results
                .filter { !it.plainLyrics.isNullOrBlank() }
                .map { r ->
                    val pr = ProviderResult(r.trackName, r.artistName, r.duration, r.albumName, false)
                    Triple(pr, scoreFor(local, pr, cand), r.plainLyrics!!)
                }
                .sortedByDescending { it.second.score }
                .firstOrNull { it.second.tier != MatchTier.REJECT }

            if (best != null) {
                log("  [plain fallback] ${best.first.title} - ${best.first.artist} (${best.second.percent()}%)")
                return@withTimeoutOrNull PlainHit(Providers.LRCLIB, best.first, best.third)
            }
            delay(config.requestDelayMs)
        }
        null
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
        /**
         * Optional album-art comparer: given a candidate's cover URL, returns a bonus in
         * [0, RescueCandidate.MAX_COVER_BONUS] when it visually matches the local file's embedded art
         * (0 when it differs or can't be compared). Additive-only — see [RescueCandidate.coverBonus].
         */
        coverBonus: (suspend (coverUrl: String) -> Double)? = null,
    ): LastResortHit? {
        // Try several reasonable candidate queries, not just the first — a single garbled query often
        // canonicalizes badly, while the loosened/filename variants land the right canonical name.
        val queries = candidates.map { it.asSearchString() }.filter { it.isNotBlank() }.distinct().take(3)
        if (queries.isEmpty()) return null

        // Collect every rescued LRCLib hit (across all queries/canonical names) WITHOUT accepting any yet, then
        // hand them to the scorer-based validator. The old code accepted the closest-duration hit (or just the
        // first when duration was unknown) with no similarity check, so a wrong song by the same artist could be
        // rescued. selectBestRescue rescps each hit against the original local track via ConfidenceScorer.
        // Each cleaned query is rescued CONCURRENTLY (the same issue-#9 treatment as the main search) and each
        // gets the regular per-provider time budget; on timeout its partial haul is still scored. The rescue
        // only runs after everything already failed, so it must never stretch the total wait by minutes again.
        val rescue = coroutineScope {
            queries.map { query ->
                async {
                    val collected = ArrayList<RescueCandidate>()
                    withTimeoutOrNull(config.providerTimeoutMs) {
                        val metas = runCatching { lastResortApi.canonicalize(query) }
                            .onFailure { log("  [last-resort] canonicalize failed: ${it.message}") }
                            .getOrDefault(emptyList())
                        for (meta in metas) {
                            val results = runCatching { lrcLib.searchCandidates("${meta.artist} ${meta.title}") }
                                .getOrDefault(emptyList())
                            results.forEach { r ->
                                collected.add(
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
                    } ?: log("  [last-resort] \"$query\" ran out of time — scoring its ${collected.size} partial candidates")
                    collected
                }
            }.flatMap { it.await() }
        }

        // Optional thumbnail check: compare each distinct rescued cover against the local file's embedded art
        // once, then stamp the (additive-only) bonus onto the candidates that carry that cover.
        val withBonus = if (coverBonus == null) rescue else {
            val bonusByUrl = rescue.mapNotNull { it.coverUrl }.distinct().associateWith { url ->
                runCatching { coverBonus(url) }.getOrDefault(0.0)
            }
            rescue.map { c ->
                val b = c.coverUrl?.let { bonusByUrl[it] } ?: 0.0
                if (b > 0.0) c.copy(coverBonus = b) else c
            }
        }

        val localViews = buildList {
            add(local)
            candidates.forEach { c -> add(LocalTrack(c.title, c.artist, local.durationSec, local.album)) }
        }.distinct()
        val hit = selectBestRescue(localViews, withBonus)
        if (hit == null) log("  [last-resort] no believable rescue cleared the confidence bar (${rescue.size} candidates)")
        else log("  [last-resort] ${if (hit.synced) "synced" else "plain"} rescue -> ${hit.artist} - ${hit.title}")
        return hit
    }

    /** Resolves the synced LRC body for a chosen hit (inline for LRCLib/generic, second fetch for Netease). */
    suspend fun fetchLyrics(hit: ScoredHit, config: MatchConfig = MatchConfig(), log: (String) -> Unit = { Log.i(TAG, it) }): String? {
        return when (hit.provider) {
            Providers.NETEASE -> hit.neteaseId?.let { id ->
                runCatching { withRetry(config.maxRetries, config.retryBaseDelayMs) { netease.getSyncedLyrics(id) } }
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
            withRetry(config.maxRetries, config.retryBaseDelayMs, onRetry = { a, d, e ->
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
        var conf = scoreFor(local, pr, cand)
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
                withRetry(config.maxRetries, config.retryBaseDelayMs, onRetry = { a, d, e ->
                    log("  [${provider.displayName}] retry $a in ${d}ms (${e.message})")
                }) {
                    service.getSongInfo(SongInfo(cand.title, cand.artist), offset, provider)
                }
            }.onFailure {
                log("  [${provider.displayName}] failed: ${it.message}")
            }.getOrNull() ?: break

            val pr = ProviderResult(info.songName, info.artistName, null, null, true)
            val conf = scoreFor(local, pr, cand)
            val lyrics = if (conf.score >= ConfidenceScorer.REVIEW_THRESHOLD) {
                runCatching { withRetry(config.maxRetries, config.retryBaseDelayMs) { service.getSyncedLyrics(info, provider) } }.getOrNull()
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
     * Scores a hit against BOTH the raw local tags and the candidate's own parse of the messy tags/filename,
     * keeping the better view. The raw tags are often garbage exactly when the filename parse is right (title
     * "Ariana Grande - Focus" with artist "Unknown": the raw view sees a 30%-similar title and a disagreeing
     * artist and rejects the correct hit, while the parsed view — title "Focus", artist "Ariana Grande" —
     * matches it perfectly). Single-song search rescued these via the slow last-resort path; scoring the parsed
     * view makes the primary providers accept them directly, in batch too.
     *
     * Precision guard: a view without an artist (title-only candidate) cannot veto a wrong singer, so it only
     * counts when the runtime matches exactly — otherwise an identically-titled song by a different artist
     * would score 1.0 on title alone.
     *
     * Remix/version base-song fallbacks (the loosened candidate) stay capped at REVIEW: their lyrics are
     * usually right but the *timing* differs (a remix changes tempo), so we never silently auto-accept one --
     * the user verifies and nudges the offset in the player instead.
     */
    private fun scoreFor(local: LocalTrack, pr: ProviderResult, cand: QueryCandidate): ConfidenceBreakdown =
        scoreHitAgainstViews(local, pr, cand)

    private suspend fun searchLrcLib(
        query: String, local: LocalTrack, cand: QueryCandidate, config: MatchConfig, log: (String) -> Unit
    ): List<ScoredHit> {
        val results = runCatching {
            withRetry(config.maxRetries, config.retryBaseDelayMs, onRetry = { a, d, e -> log("  [LRCLib] retry $a in ${d}ms (${e.message})") }) {
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
                ScoredHit(Providers.LRCLIB, cand.strategy, pr, scoreFor(local, pr, cand), r.syncedLyrics, null)
            }
            .sortedByDescending { it.confidence.score }
            .take(config.maxCandidatesPerProvider)
    }

    private suspend fun searchNetease(
        query: String, local: LocalTrack, cand: QueryCandidate, config: MatchConfig, log: (String) -> Unit
    ): List<ScoredHit> {
        val songs = runCatching {
            withRetry(config.maxRetries, config.retryBaseDelayMs, onRetry = { a, d, e -> log("  [Netease] retry $a in ${d}ms (${e.message})") }) {
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
            ScoredHit(Providers.NETEASE, cand.strategy, pr, scoreFor(local, pr, cand), null, s.id)
        }
    }
}

/**
 * Scores [pr] against BOTH the raw local tags and [cand]'s own parse of the messy tags/filename, keeping the
 * better view (see the call-site docs on SmartLyricsMatcher.scoreFor). A view without an artist can't veto a
 * wrong singer, so it only counts when the runtime matches exactly. Pure and Android-free — unit-testable.
 */
internal fun scoreHitAgainstViews(local: LocalTrack, pr: ProviderResult, cand: QueryCandidate): ConfidenceBreakdown {
    var best = ConfidenceScorer.score(local, pr)
    val view = LocalTrack(cand.title, cand.artist, local.durationSec, local.album)
    if (view.title != local.title || view.artist != local.artist) {
        val viewConf = ConfidenceScorer.score(view, pr)
        val viewTrustable = !cand.artist.isNullOrBlank() || viewConf.durationMatched
        if (viewTrustable && viewConf.score > best.score) best = viewConf
    }
    return if (cand.strategy == MatchStrategy.FILENAME_LOOSE && best.tier == MatchTier.AUTO_ACCEPT)
        best.copy(score = 0.80, tier = MatchTier.REVIEW)
    else best
}

/** A raw rescued lyrics candidate (a canonicalized provider result + its lyrics) awaiting confidence validation. */
data class RescueCandidate(
    val result: ProviderResult,
    val syncedLyrics: String?,
    val plainLyrics: String?,
    val coverUrl: String?,
    /**
     * Extra confidence in [0, MAX_COVER_BONUS] earned by the candidate's album art visually matching the local
     * file's embedded art. Strictly additive: art that differs (or can't be compared) contributes 0 and never
     * subtracts — covers legitimately vary between releases of the same song.
     */
    val coverBonus: Double = 0.0,
) {
    companion object {
        const val MAX_COVER_BONUS = 0.05
    }
}

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

    data class Scored(val c: RescueCandidate, val synced: Boolean, val conf: ConfidenceBreakdown, val ranking: Double)

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

        // Album-art agreement is additive-only: it lifts the ranking of a candidate whose cover matches the
        // local file's embedded art, but a mismatch never pushes a candidate below the acceptance gates above.
        val ranking = (conf.score + c.coverBonus.coerceIn(0.0, RescueCandidate.MAX_COVER_BONUS)).coerceAtMost(1.0)
        Scored(c, synced, conf, ranking)
    }

    val best = scored.sortedWith(
        compareByDescending<Scored> { it.synced }.thenByDescending { it.ranking }
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
