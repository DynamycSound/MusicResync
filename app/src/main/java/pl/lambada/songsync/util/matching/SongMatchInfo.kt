package pl.lambada.songsync.util.matching

import pl.lambada.songsync.util.Providers

/** Per-song lyric state, used to colour rows and split the All / Has Lyrics / No Lyrics tabs. */
enum class LyricState {
    /** A synced .lrc already exists next to the audio (pre-scan or a previous run). */
    HAS_LYRICS,

    /** Auto-accepted match (confidence >= 0.85) just downloaded. Green. */
    SYNCED,

    /** Downloaded but low-confidence (0.60-0.84) — user should verify. Yellow. */
    REVIEW,

    /** Plain-text .lrc present but not time-synced. Counts as "has lyrics", flagged. */
    UNSYNCED,

    /** Currently being matched/fetched. */
    FETCHING,

    /** No confident match found — goes to the manual queue. Red. */
    NO_LYRICS,

    /** A network/IO error (not a "not found"). Red, retryable. */
    FAILED,
}

/**
 * The outcome of matching one song, surfaced to the UI for the confidence badge, status colour and the manual
 * suggestion list.
 */
data class SongMatchInfo(
    val state: LyricState,
    val confidencePercent: Int? = null,
    val provider: Providers? = null,
    val matchedTitle: String? = null,
    val matchedArtist: String? = null,
    val durationMatched: Boolean = false,
) {
    val hasLyrics: Boolean
        get() = state == LyricState.HAS_LYRICS || state == LyricState.SYNCED ||
            state == LyricState.REVIEW
}
