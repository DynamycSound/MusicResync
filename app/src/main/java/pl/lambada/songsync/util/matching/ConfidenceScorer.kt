package pl.lambada.songsync.util.matching

import kotlin.math.abs

/** Confidence band a match falls into, driving auto-accept / verify / manual behavior and UI color. */
enum class MatchTier { AUTO_ACCEPT, REVIEW, REJECT }

/** What we know about the local song. Any field may be null (the whole point of bad-metadata handling). */
data class LocalTrack(
    val title: String?,
    val artist: String?,
    val durationSec: Double? = null,
    val album: String? = null,
)

/** A candidate result returned by a provider, normalized to a common shape for scoring. */
data class ProviderResult(
    val title: String?,
    val artist: String?,
    val durationSec: Double? = null,
    val album: String? = null,
    val hasSyncedLyrics: Boolean = true,
)

/** Per-factor breakdown so the UI and logs can explain *why* a score is what it is. */
data class ConfidenceBreakdown(
    val score: Double,
    val title: Double,
    val artist: Double,
    val duration: Double,
    val album: Double,
    val tier: MatchTier,
    val durationMatched: Boolean,
) {
    fun percent(): Int = (score * 100).toInt()
}

/**
 * Scores how well a [ProviderResult] matches a [LocalTrack] in [0,1].
 *
 * Weights (per spec): title 40%, artist 30%, duration 20%, album 10%. Factors whose data is missing on either
 * side are dropped and the remaining weights are renormalized, so a tag-less file isn't unfairly penalized for
 * having no artist/album to compare. Duration is the strong disambiguator the user asked for: when title/artist
 * are weak but the runtime matches within tolerance, it both boosts the score and flags [durationMatched] so
 * the manual UI can surface "same length as your file" suggestions.
 */
object ConfidenceScorer {
    const val AUTO_ACCEPT_THRESHOLD = 0.85
    const val REVIEW_THRESHOLD = 0.60

    private const val W_TITLE = 0.40
    private const val W_ARTIST = 0.30
    private const val W_DURATION = 0.20
    private const val W_ALBUM = 0.10

    private const val DURATION_EXACT_SEC = 2.0   // <= 2s difference -> perfect
    private const val DURATION_ZERO_SEC = 12.0   // >= 12s difference -> no credit

    fun tierFor(score: Double): MatchTier = when {
        score >= AUTO_ACCEPT_THRESHOLD -> MatchTier.AUTO_ACCEPT
        score >= REVIEW_THRESHOLD -> MatchTier.REVIEW
        else -> MatchTier.REJECT
    }

    /** Linear duration similarity: 1.0 within [DURATION_EXACT_SEC], decaying to 0 at [DURATION_ZERO_SEC]. */
    private fun durationSimilarity(a: Double, b: Double): Double {
        val diff = abs(a - b)
        return when {
            diff <= DURATION_EXACT_SEC -> 1.0
            diff >= DURATION_ZERO_SEC -> 0.0
            else -> 1.0 - (diff - DURATION_EXACT_SEC) / (DURATION_ZERO_SEC - DURATION_EXACT_SEC)
        }
    }

    fun score(local: LocalTrack, result: ProviderResult): ConfidenceBreakdown {
        val titleSim = TextMatch.similarity(local.title, result.title)

        var weightSum = W_TITLE
        var weighted = W_TITLE * titleSim

        var artistSim = 0.0
        if (!local.artist.isNullOrBlank() && !result.artist.isNullOrBlank()) {
            artistSim = TextMatch.similarity(local.artist, result.artist)
            weightSum += W_ARTIST
            weighted += W_ARTIST * artistSim
        }

        var durationSim = 0.0
        var durationMatched = false
        if (local.durationSec != null && result.durationSec != null && local.durationSec > 0 && result.durationSec > 0) {
            durationSim = durationSimilarity(local.durationSec, result.durationSec)
            durationMatched = abs(local.durationSec - result.durationSec) <= DURATION_EXACT_SEC
            weightSum += W_DURATION
            weighted += W_DURATION * durationSim
        }

        var albumSim = 0.0
        if (!local.album.isNullOrBlank() && !result.album.isNullOrBlank()) {
            albumSim = TextMatch.similarity(local.album, result.album)
            weightSum += W_ALBUM
            weighted += W_ALBUM * albumSim
        }

        var score = if (weightSum > 0) weighted / weightSum else 0.0

        // Whether we could actually compare artists (both sides had one). When true, a low artistSim is real
        // disagreement, not just missing data.
        val artistComparable = !local.artist.isNullOrBlank() && !result.artist.isNullOrBlank()
        val artistDisagrees = artistComparable && artistSim < 0.50

        // Duration tiebreak: a strong title plus an exact-length match is almost certainly the right track,
        // even when the artist tag is unknown or garbage. Exact duration is trusted here, so this still fires
        // for the messy-filename case (junk artist tag) the app targets.
        if (durationMatched && titleSim >= 0.80 && score >= REVIEW_THRESHOLD && score < AUTO_ACCEPT_THRESHOLD) {
            score = AUTO_ACCEPT_THRESHOLD
        }

        // A same-artist but different-song result can still look deceptively decent on the weighted average when
        // the artist matches exactly and the title shares a generic word (e.g. local="Every day i pimp" vs
        // result="Every Dog Has His Day"). Unless the runtime matches exactly, require some minimum title
        // evidence before a candidate is even review-grade.
        if (!durationMatched && titleSim < 0.55 && score >= REVIEW_THRESHOLD) {
            score = REVIEW_THRESHOLD - 0.01
        }

        var tier = tierFor(score)
        // Right title, wrong singer: when we DO know the local artist and it clearly disagrees, and there's no
        // exact-duration confirmation, this is almost always a different recording (a cover, or two unrelated
        // songs that share a name). Saving its lyrics gives the user the wrong words, so reject it outright and
        // let the song stay unmatched. We only trust a disagreeing artist when the runtime matches exactly.
        if (tier != MatchTier.REJECT && artistDisagrees && !durationMatched) {
            tier = MatchTier.REJECT
        }

        return ConfidenceBreakdown(
            score = score.coerceIn(0.0, 1.0),
            title = titleSim,
            artist = artistSim,
            duration = durationSim,
            album = albumSim,
            tier = tier,
            durationMatched = durationMatched,
        )
    }
}
