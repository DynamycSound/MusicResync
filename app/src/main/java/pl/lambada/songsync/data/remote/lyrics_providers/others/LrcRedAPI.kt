package pl.lambada.songsync.data.remote.lyrics_providers.others

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import pl.lambada.songsync.util.networking.Ktor.client
import pl.lambada.songsync.util.networking.Ktor.json
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/**
 * lrc.red: the open catalogue BetterLyrics answers from (about 30 million recordings, Apple-style TTML filed by
 * ISRC, no key needed). BetterLyrics' own endpoint only serves songs someone already looked up without a key, so
 * [BetterLyricsAPI] falls back to this for the rest.
 *
 * The search is free text and ranks things like "Bohemian Rhapsody (Operatic Section / A Cappella Mix)" above the
 * song itself, so hits are never taken in order: [best] only accepts one that is plainly the same recording.
 */
object LrcRedAPI {
    private const val BASE = "https://lrc.red"

    /** How far a hit's length may be from the local file's; past this it's a different edit. */
    private const val DURATION_TOLERANCE_SEC = 3.0

    private val ISRC = Regex("""[A-Z]{2}[A-Z0-9]{3}\d{7}""")

    @Serializable
    data class Hit(
        val isrc: String? = null,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        /** Seconds, fractional. */
        val duration: Double? = null,
    )

    @Serializable
    private data class SearchResponse(val hits: List<Hit>? = null)

    /** The TTML for [title]/[artist], or null when lrc.red has no timed lyrics for that exact recording. */
    suspend fun getTtml(title: String, artist: String, durationSec: Int? = null): String? {
        val response = client.get("$BASE/search.json") { parameter("q", "$title $artist".trim()) }
        if (response.status.value !in 200..299) return null
        val hits = runCatching {
            json.decodeFromString<SearchResponse>(response.bodyAsText(Charsets.UTF_8)).hits
        }.getOrNull() ?: return null
        val hit = best(hits, title, artist, durationSec) ?: return null

        val document = client.get("$BASE/s/${hit.isrc}.ttml")
        if (document.status.value !in 200..299) return null
        // Plain (untimed) entries are TTML too; they'd turn into a sheet of 00:00 lines.
        return document.bodyAsText(Charsets.UTF_8).takeUnless { it.isBlank() || UNTIMED.containsMatchIn(it) }
    }

    /**
     * The hit that is this recording, or null if none surely is. Every test is a requirement, not a score, since
     * a miss just lets the next provider try while wrong words in time with the right song can't be undone:
     *  - the title outside brackets and any " - " suffix is the same;
     *  - the words in those parts that make it a different recording (remix, live, acoustic, sped up…) match on
     *    both sides, so a remix never gets the original's lyrics or the other way round ("Remastered" is fine);
     *  - at least one credited artist is shared;
     *  - the length is within [DURATION_TOLERANCE_SEC] when it's known.
     * Of those that pass the closest length wins, then the search's own order.
     */
    fun best(hits: List<Hit>, title: String, artist: String, durationSec: Int?): Hit? {
        val wantedTitle = coreOf(title)
        val wantedVersion = versionOf(title)
        val wantedArtists = artistsOf(artist)

        fun distance(hit: Hit): Double =
            if (durationSec != null && durationSec > 0 && hit.duration != null) abs(hit.duration - durationSec) else 0.0

        return hits
            .filter { hit ->
                val name = hit.title ?: return@filter false
                hit.isrc != null && ISRC.matches(hit.isrc) &&
                    coreOf(name) == wantedTitle &&
                    versionOf(name) == wantedVersion &&
                    (wantedArtists.isEmpty() || artistsOf(hit.artist.orEmpty()).any { it in wantedArtists }) &&
                    distance(hit) <= DURATION_TOLERANCE_SEC
            }
            .minByOrNull(::distance)
    }

    /** "Song (Live) - 2011 Remaster" → "song". */
    private fun coreOf(title: String): String =
        normalized(title.replace(BRACKETED, " ").substringBefore(" - ")).ifEmpty { normalized(title) }

    private fun versionOf(title: String): Set<String> {
        val extras = BRACKETED.findAll(title).joinToString(" ") { it.value } + " " + title.substringAfter(" - ", "")
        return normalized(extras).split(' ').filter { it in VERSION_WORDS }.toSet()
    }

    private fun artistsOf(artist: String): Set<String> =
        artist.split(ARTIST_SEPARATORS).map(::normalized).filter { it.isNotEmpty() }.toSet()

    /** Lower case, accents off ("Šotovi" is "sotovi"), letters and digits only. */
    private fun normalized(value: String): String =
        Normalizer.normalize(value.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase(Locale.ROOT)
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .replace(WHITESPACE, " ")
            .trim()

    private val UNTIMED = Regex("""timing="None"""")
    private val BRACKETED = Regex("""[(\[][^)\]]*[)\]]""")
    private val COMBINING_MARKS = Regex("""\p{Mn}+""")
    private val WHITESPACE = Regex("""\s+""")
    private val ARTIST_SEPARATORS = Regex(
        """\s*(?:,|&|;|/|\s+and\s+|\s+x\s+|\s+with\s+|\s+feat\.?\s+|\s+ft\.?\s+)\s*""",
        RegexOption.IGNORE_CASE,
    )

    /** Words that make it a different recording, not just a different release. */
    private val VERSION_WORDS = setOf(
        "live", "remix", "remixed", "mix", "acoustic", "unplugged", "instrumental", "karaoke", "cappella",
        "acapella", "demo", "edit", "version", "cover", "sped", "slowed", "reverb", "nightcore", "lofi",
        "orchestral", "extended", "vip", "bootleg", "flip",
    )
}
