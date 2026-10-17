package pl.lambada.songsync.nowplaying

import android.media.session.PlaybackState
import pl.lambada.songsync.domain.model.Song
import pl.lambada.songsync.util.matching.LyricState
import pl.lambada.songsync.util.matching.TextMatch
import kotlin.math.abs

/** States the Home list treats as "has lyrics" (tap opens the player instead of a new search). */
val lyricStatesWithLyrics = setOf(LyricState.HAS_LYRICS, LyricState.SYNCED, LyricState.REVIEW)

/** Title/artist to search lyrics with, derived from what the player reports. */
data class NowPlayingQuery(val title: String, val artist: String)

/**
 * Pure (no Android runtime calls) helpers behind the "Now playing" card, kept separate so they are unit-testable
 * on the JVM.
 */
object NowPlayingLogic {

    /** Players fill different metadata keys (TITLE vs DISPLAY_TITLE, ARTIST vs ALBUM_ARTIST…): take the first real one. */
    fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }?.trim()

    /** Only a session that is actually producing sound counts as "now playing"; paused/stopped ones are hidden. */
    fun isPlaying(state: Int?): Boolean =
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING

    /**
     * Picks the session to show. [sessions] come from MediaSessionManager in priority order (most important first),
     * so the first one that is playing and has a title wins.
     */
    fun <T> pickPlaying(sessions: List<T>, stateOf: (T) -> Int?, titleOf: (T) -> String?): T? =
        sessions.firstOrNull { isPlaying(stateOf(it)) && !titleOf(it).isNullOrBlank() }

    private val vevoSuffix = Regex("""\s*VEVO$""", RegexOption.IGNORE_CASE)
    private val dashSplit = Regex("""\s+[-–—]\s+""")

    /** Letters/digits only, accent- and Cyrillic-folded: "KendrickLamar" == "Kendrick Lamar". */
    private fun collapse(s: String): String = TextMatch.normalizeForCompare(s).replace(" ", "")

    /**
     * Words that mark the part after a dash as a version of the song ("Let It Be - Remastered 2009",
     * "Love Story - Taylor's Version", "Song - Live at Wembley") rather than the title of an "Artist - Title" video.
     */
    private val versionWords = Regex(
        """\b(?:remaster(?:ed)?|live|remix|mix|edit|version|mono|stereo|acoustic|demo|instrumental|radio|extended|""" +
            """bonus|single|unplugged|session|slowed|sped|reverb|from|feat|ft|\d{4})\b""",
        RegexOption.IGNORE_CASE
    )
    private val bracketed = Regex("""[(\[][^)\]]*[)\]]""")

    /** A producer credit trailing a video title outside brackets: "MARTINI Prod. by Jhinsen". */
    private val trailingProducer = Regex("""\s+(?:prod\.|prod\s+by|produced\s+by)\s*\S.*$""", RegexOption.IGNORE_CASE)

    private fun isVersionSuffix(s: String): Boolean = versionWords.containsMatchIn(bracketed.replace(s, " "))

    /**
     * Turns player metadata into a lyrics query. Music apps usually report clean tags and pass through unchanged
     * (apart from noise like "(Official Video)"). Video apps report "Artist - Title (Official Video)" with the
     * uploading channel as the artist: "ArtistVEVO", "Artist - Topic", but just as often a label or a third-party
     * channel ("Generacija Zed", "Lyrical Lemonade"). So an "X - Y" title is split into artist X / title Y unless Y
     * reads like a version suffix of a song by the reported artist ("Let It Be - Remastered 2009").
     */
    fun searchQuery(title: String, artist: String?): NowPlayingQuery {
        val rawArtist = artist?.trim().orEmpty()
        val channelless = TextMatch.stripChannelSuffix(vevoSuffix.replace(rawArtist, "")).trim()
        val cleanArtist = if (TextMatch.isJunkArtist(channelless)) "" else channelless
        val cleanTitle = TextMatch.cleanTitleArtist(title).ifBlank { title.trim() }
            .let { t -> trailingProducer.replace(t, "").ifBlank { t } }

        val parts = cleanTitle.split(dashSplit, limit = 2).map { it.trim() }
        if (parts.size == 2 && parts.all { it.isNotBlank() }) {
            val (left, right) = parts
            val leftC = collapse(left)
            val artistC = collapse(cleanArtist)
            val leftIsArtist = cleanArtist.isBlank() ||
                (leftC.isNotEmpty() && artistC.isNotEmpty() && (
                    leftC == artistC ||
                        (minOf(leftC.length, artistC.length) >= 3 && (leftC.contains(artistC) || artistC.contains(leftC)))
                    )) ||
                !isVersionSuffix(right)
            if (leftIsArtist) {
                return NowPlayingQuery(title = TextMatch.cleanTitleArtist(right).ifBlank { right }, artist = left)
            }
        }
        return NowPlayingQuery(title = cleanTitle, artist = cleanArtist)
    }

    /**
     * Where playback is right now. Players report a position only when it jumps (play, pause, seek, new track),
     * stamped with [lastUpdateMs] on the elapsed-realtime clock; while playing it advances at [speed] from there.
     * Returns null when the player doesn't report a position at all.
     */
    fun livePositionMs(playing: Boolean, positionMs: Long, lastUpdateMs: Long, speed: Float, nowMs: Long): Long? {
        if (positionMs < 0) return null
        if (!playing || lastUpdateMs <= 0) return positionMs
        return (positionMs + ((nowMs - lastUpdateMs) * speed).toLong()).coerceAtLeast(0)
    }

    /** Max difference between the player's and the file's duration for them to count as the same recording. */
    private const val DURATION_TOLERANCE_MS = 5_000L

    private fun tokens(s: String?): Set<String> =
        TextMatch.normalizeForCompare(s).split(' ').filter { it.isNotBlank() }.toSet()

    private fun artistsCompatible(playing: String?, local: String?): Boolean {
        if (TextMatch.isJunkArtist(playing) || TextMatch.isJunkArtist(local)) return true
        val p = tokens(playing)
        val l = tokens(local)
        if (p.isEmpty() || l.isEmpty()) return true
        // "Drake" vs "Drake, Future": one credit list containing the other is the same release.
        if (l.containsAll(p) || p.containsAll(l)) return true
        return TextMatch.similarity(playing, local) >= 0.85
    }

    private fun durationsCompatible(playingMs: Long?, localMs: Long?): Boolean =
        playingMs == null || playingMs <= 0 || localMs == null || abs(playingMs - localMs) <= DURATION_TOLERANCE_MS

    /**
     * Finds the library file a local player is playing, so the lyrics get saved next to it (and its duration helps
     * matching). Returns null when nothing matches or the match is ambiguous: the search then runs as a
     * non-library track instead of writing a .lrc next to the wrong file.
     */
    fun findLocalMatch(title: String, artist: String?, durationMs: Long?, songs: List<Song>): Song? {
        val wanted = TextMatch.normalizeForCompare(title)
        if (wanted.isEmpty()) return null
        val matches = songs.filter { song ->
            song.filePath != null &&
                TextMatch.normalizeForCompare(song.title) == wanted &&
                artistsCompatible(artist, song.artist) &&
                durationsCompatible(durationMs, song.durationMs)
        }
        if (matches.size <= 1) return matches.firstOrNull()

        // Several files share the title: only a known duration can tell them apart.
        val knownMs = durationMs?.takeIf { it > 0 } ?: return null
        val ranked = matches
            .filter { it.durationMs != null }
            .sortedBy { abs(it.durationMs!! - knownMs) }
        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)
        return if (runnerUp != null && abs(runnerUp.durationMs!! - knownMs) == abs(best.durationMs!! - knownMs)) null
        else best
    }
}
