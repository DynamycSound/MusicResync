package pl.lambada.songsync.domain.model.lyrics_providers.others

import kotlinx.serialization.Serializable

/**
 * Response models for the LyricsPlus API (`/v2/lyrics/get`). The service aggregates Apple/Musixmatch-grade
 * word-by-word lyrics behind a simple title/artist/duration query and is served from several community mirrors.
 */
@Serializable
data class LyricsPlusResponse(
    val type: String? = null,
    val metadata: LyricsPlusMetadata? = null,
    val lyrics: List<LyricsPlusLine>? = null,
)

@Serializable
data class LyricsPlusMetadata(
    val title: String? = null,
    val language: String? = null,
)

@Serializable
data class LyricsPlusLine(
    /** Line start, milliseconds. */
    val time: Long = 0,
    /** Line duration, milliseconds. */
    val duration: Long = 0,
    val text: String = "",
    /** Word-level timing (only present for type == "Word"). */
    val syllabus: List<LyricsPlusWord>? = null,
    val element: LyricsPlusLineElement? = null,
)

@Serializable
data class LyricsPlusWord(
    /** Word start, milliseconds. */
    val time: Long = 0,
    /** Word duration, milliseconds. */
    val duration: Long = 0,
    val text: String = "",
    val isBackground: Boolean = false,
)

@Serializable
data class LyricsPlusLineElement(
    val key: String? = null,
    /** Singer alias, e.g. "v1" / "v2" — used for the multi-person word-by-word format. */
    val singer: String? = null,
)
