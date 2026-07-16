package pl.lambada.songsync.data.remote.lyrics_providers.others

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import pl.lambada.songsync.domain.model.lyrics_providers.others.LyricsPlusLine
import pl.lambada.songsync.domain.model.lyrics_providers.others.LyricsPlusResponse
import pl.lambada.songsync.domain.model.lyrics_providers.others.LyricsPlusWord
import pl.lambada.songsync.util.ext.toLrcTimestamp
import pl.lambada.songsync.util.networking.Ktor.client
import pl.lambada.songsync.util.networking.Ktor.json

/**
 * LyricsPlus provider (github.com/ibratabian17/YouLyPlus backend): word-by-word (enhanced LRC) and line-synced
 * lyrics behind a direct title/artist/duration query — no search/offset pagination. Several community mirrors
 * serve the same API; we walk them in order and remember the last one that answered, so a dead mirror is only
 * paid for once per run.
 */
class LyricsPlusAPI {

    companion object {
        // Mirror list from the YouLyPlus project (see issue #4). The Cloudflare-workers and old Vercel
        // deployments are intentionally last: one is capped at 100k requests/day, the other is often disabled.
        private val BASE_URLS = listOf(
            "https://lyricsplus.prjktla.my.id",   // main server
            "https://lyricsplus.binimum.org",     // binimum's alternate server
            "https://lyricsplus.atomix.one",      // meow's mirror
            "https://lyricsplus-seven.vercel.app",// jigen's mirror
            "https://lyricsplus.prjktla.workers.dev",
            "https://lyrics-plus-backend.vercel.app",
        )

        @Volatile
        private var lastWorkingServer: String? = null
    }

    private fun prioritizedServers(): List<String> {
        val last = lastWorkingServer
        return if (last != null && last in BASE_URLS) listOf(last) + BASE_URLS.filter { it != last }
        else BASE_URLS
    }

    /**
     * Fetches lyrics for [title]/[artist] (optionally narrowed by [durationSec] and [album]) and returns them
     * as LRC — enhanced (word-by-word) when the service has a "Word" body, plain line-synced otherwise.
     * Returns null when no mirror has a match.
     */
    suspend fun getSyncedLyrics(
        title: String,
        artist: String,
        durationSec: Int? = null,
        album: String? = null,
        multiPersonWordByWord: Boolean = false,
    ): String? {
        if (title.isBlank() || artist.isBlank()) return null

        for (baseUrl in prioritizedServers()) {
            val response = runCatching {
                val res = client.get("$baseUrl/v2/lyrics/get") {
                    parameter("title", title)
                    parameter("artist", artist)
                    if (durationSec != null && durationSec > 0) parameter("duration", durationSec)
                    if (!album.isNullOrBlank()) parameter("album", album)
                }
                val body = res.bodyAsText(Charsets.UTF_8)
                if (res.status.value !in 200..299 || body.isBlank()) null
                else json.decodeFromString<LyricsPlusResponse>(body)
            }.getOrNull()

            if (response?.lyrics.isNullOrEmpty()) continue
            lastWorkingServer = baseUrl
            return convertToLrc(response!!, multiPersonWordByWord)
        }
        return null
    }

    /**
     * Converts a LyricsPlus response into the app's LRC dialect: `[mm:ss.SSS]` line stamps, and for "Word"
     * bodies the same `<begin>word <end>` inline syllable timing that the Apple/QQ providers emit (see
     * [pl.lambada.songsync.data.remote.PaxMusicHelper]), so the player renders it word-by-word out of the box.
     */
    private fun convertToLrc(response: LyricsPlusResponse, multiPersonWordByWord: Boolean): String? {
        val lyrics = response.lyrics?.takeIf { it.isNotEmpty() } ?: return null
        val wordSync = response.type.equals("Word", ignoreCase = true)

        // Only tag voices when the song actually alternates between more than one singer.
        val singers = lyrics.mapNotNull { it.element?.singer?.lowercase() }.distinct()
        val tagVoices = multiPersonWordByWord && singers.size > 1
        val primarySinger = singers.firstOrNull()

        val sb = StringBuilder(lyrics.size * 64)
        for (line in lyrics) {
            val mainWords = line.syllabus?.filter { !it.isBackground }.orEmpty()
            val bgWords = line.syllabus?.filter { it.isBackground }.orEmpty()

            val hasMain = if (wordSync && line.syllabus != null) mainWords.isNotEmpty() else line.text.isNotBlank()
            if (hasMain) {
                sb.append("[${line.time.toInt().toLrcTimestamp()}]")
                if (tagVoices) {
                    sb.append(if (line.element?.singer?.lowercase() == primarySinger) "v1:" else "v2:")
                }
                if (wordSync && mainWords.isNotEmpty()) appendWordByWord(sb, mainWords)
                else sb.append(line.text.trim())
                sb.append('\n')
            }

            // Background vocals only make sense in the multi-person word-by-word format (mirrors PaxMusicHelper).
            if (bgWords.isNotEmpty() && multiPersonWordByWord && wordSync) {
                // Rewrite the trailing newline so the [bg:...] block attaches to its main line.
                if (sb.endsWith("\n")) sb.setLength(sb.length - 1)
                sb.append("\n[bg:")
                appendWordByWord(sb, bgWords)
                sb.append("]\n")
            }
        }
        return sb.toString().trimEnd().ifBlank { null }
    }

    /** Appends `<begin>word <end>` blocks, deduplicating an end stamp that equals the next word's begin. */
    private fun appendWordByWord(sb: StringBuilder, words: List<LyricsPlusWord>) {
        for (word in words) {
            val text = word.text.trim()
            if (text.isEmpty()) continue
            val begin = "<${word.time.toInt().toLrcTimestamp()}>"
            val end = "<${(word.time + word.duration).toInt().toLrcTimestamp()}>"
            if (!sb.endsWith(begin)) sb.append(begin)
            sb.append(text).append(' ')
            sb.append(end)
        }
    }
}
