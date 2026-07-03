package pl.lambada.songsync.util

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.kyant.taglib.Picture
import com.kyant.taglib.TagLib
import pl.lambada.songsync.R
import pl.lambada.songsync.data.remote.lyrics_providers.LyricsProviderService
import pl.lambada.songsync.data.remote.lyrics_providers.MatchConfig
import pl.lambada.songsync.data.remote.lyrics_providers.ScoredHit
import pl.lambada.songsync.data.remote.lyrics_providers.SmartLyricsMatcher
import pl.lambada.songsync.data.UserSettingsController
import pl.lambada.songsync.domain.model.Song
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.util.ext.sanitize
import pl.lambada.songsync.util.ext.toLrcFile
import pl.lambada.songsync.util.matching.FilenameParser
import pl.lambada.songsync.util.matching.LocalTrack
import pl.lambada.songsync.util.matching.LrcPrescan
import pl.lambada.songsync.util.matching.LyricState
import pl.lambada.songsync.util.matching.MatchTier
import pl.lambada.songsync.util.matching.SongMatchInfo
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileNotFoundException
import kotlin.coroutines.coroutineContext

/**
 * Normalizes every line ending to CRLF. Many external LRC players (and several stock Android music apps)
 * require `\r\n` and render an `\n`-only file as a single unsynced line — which is exactly the "O PANA!" bug
 * (it looked fine in our player, which reads with lineSequence(), but broke everywhere else). Idempotent.
 */
fun String.toCrlf(): String = replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n")

fun generateLrcContent(
    song: SongInfo,
    lyrics: String,
    generatedUsingString: String,
    offset: Int = 0,
    directOffset: Boolean
): String {
    val offsetSign = if (offset >= 0) "+" else ""
    val offsetStr = if (!directOffset) "[offset:${offsetSign}${offset}]\n" else ""
    val lyrics = if (directOffset && offset != 0) applyOffsetToLyrics(lyrics, offset) else lyrics

    return ("[ti:${song.songName}]\n" +
        "[ar:${song.artistName}]\n" +
        offsetStr +
        "[by:$generatedUsingString]\n" +
        lyrics).toCrlf()
}

fun newLyricsFilePath(filePath: String?, song: SongInfo): File {
    return if (filePath == null || filePath.isEmpty()) {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "MusicResync/${song.songName} - ${song.artistName}.lrc"
        ).sanitize()
    } else {
        filePath.toLrcFile()!!
    }
}

fun writeLyricsToFile(
    file: File?,
    lrcContent: String,
    context: Context,
    song: Song,
    sdCardPath: String?
) {
    try {
        file?.writeText(lrcContent.toCrlf())
    } catch (e: FileNotFoundException) {
        handleFileNotFoundException(context, song, file, lrcContent, sdCardPath)
    }
}

/**
 * Persists [lrcContent] for [song] per the user's choices and returns whether at least one target actually
 * succeeded. [writeLyricsToFile] throws on a hard write failure (caught by the caller), while [embedLyricsInFile]
 * returns false WITHOUT throwing when the tag write fails or is denied — so "no exception" is not the same as
 * "saved". Callers must use this boolean instead of runCatching{...}.isSuccess, which masked failed embeds as
 * successes (showing a green/synced row for a song where nothing was written).
 */
fun persistLyrics(
    context: Context,
    song: Song,
    lrcFile: File?,
    lrcContent: String,
    saveLrc: Boolean,
    embedLyrics: Boolean,
    sdCardPath: String?,
): Boolean {
    var wroteLrc = false
    if (saveLrc || !embedLyrics) {
        writeLyricsToFile(lrcFile, lrcContent, context, song, sdCardPath)
        wroteLrc = true
    }
    var embedded = false
    if (embedLyrics) {
        embedded = embedLyricsInFile(context, song.filePath ?: error("File path is null"), lrcContent)
    }
    return wroteLrc || embedded
}

fun handleFileNotFoundException(
    context: Context,
    song: Song,
    file: File?,
    lrc: String,
    sdCardPath: String?
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && !song.filePath!!.contains("/storage/emulated/0")) {
        val sd = context.externalCacheDirs[1].absolutePath.substring(
            0,
            context.externalCacheDirs[1].absolutePath.indexOf("/Android/data")
        )
        val path = file?.absolutePath?.substringAfter(sd)?.split("/")?.dropLast(1)
        var sdCardFiles = DocumentFile.fromTreeUri(context, Uri.parse(sdCardPath))
        for (element in path!!) {
            for (sdCardFile in sdCardFiles!!.listFiles()) {
                if (sdCardFile.name == element) {
                    sdCardFiles = sdCardFile
                }
            }
        }
        sdCardFiles?.listFiles()?.forEach {
            if (it.name == file.name) {
                it.delete()
                return@forEach
            }
        }
        sdCardFiles?.createFile("text/lrc", file.name)?.let {
            val outputStream = context.contentResolver.openOutputStream(it.uri)
            outputStream?.write(lrc.toCrlf().toByteArray())
            outputStream?.close()
        }
    } else {
        error("Unable to handle FileNotFoundException")
    }
}

@SuppressLint("Range")
fun getFileDescriptorFromPath(
    context: Context, filePath: String, mode: String = "r"
): ParcelFileDescriptor? {
    val resolver = context.contentResolver
    val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(MediaStore.Files.FileColumns._ID)
    val selection = "${MediaStore.Files.FileColumns.DATA}=?"
    val selectionArgs = arrayOf(filePath)

    return resolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val fileId = cursor.getInt(cursor.getColumnIndex(MediaStore.Files.FileColumns._ID))
            if (fileId != -1) {
                val fileUri = Uri.withAppendedPath(uri, fileId.toString())
                try {
                    resolver.openFileDescriptor(fileUri, mode)
                } catch (e: FileNotFoundException) {
                    Log.e("LyricsFetchViewModel", "File not found: ${e.message}")
                    null
                }
            } else null
        } else null
    }
}

fun embedLyricsInFile(
    context: Context,
    filePath: String,
    lyrics: String,
    securityExceptionHandler: (PendingIntent) -> Unit = {}
): Boolean {
    return try {
        val fd = getFileDescriptorFromPath(context, filePath, mode = "w")
            ?: throw IllegalStateException("File descriptor is null")

        val fileDescriptor = fd.dup().detachFd()
        val metadata = TagLib.getMetadata(fileDescriptor, false) ?: error("Metadata is null")

        TagLib.savePropertyMap(
            fd.dup().detachFd(),
            propertyMap = metadata.propertyMap.apply { put("LYRICS", arrayOf(lyrics.toCrlf())) }
        )

        true
    } catch (securityException: SecurityException) {
        handleSecurityException(securityException, securityExceptionHandler)
        false
    } catch (e: Exception) {
        Log.e("LyricsFetchViewModel", "Error embedding lyrics: ${e.message}")
        false
    }
}

/**
 * Downloads [imageUrl] and writes it into the audio file as the front-cover picture (replacing any existing
 * art) via TagLib. Used by the thumbnail picker. Network + tag write — call off the main thread. Best-effort:
 * returns false on any failure instead of throwing.
 */
fun embedCoverInFile(context: Context, filePath: String, imageUrl: String): Boolean {
    return try {
        val bytes = java.net.URL(imageUrl).openStream().use { it.readBytes() }
        if (bytes.isEmpty()) return false
        val mime = if (imageUrl.substringBefore('?').endsWith(".png", ignoreCase = true)) "image/png" else "image/jpeg"

        val fd = getFileDescriptorFromPath(context, filePath, mode = "w")
            ?: throw IllegalStateException("File descriptor is null")
        val picture = Picture(bytes, "Cover", "Front Cover", mime)
        TagLib.savePictures(fd.dup().detachFd(), arrayOf(picture))
    } catch (e: Exception) {
        Log.e("MusicResync", "Error embedding cover: ${e.message}")
        false
    }
}

/**
 * MusicResync: writes the matched title/artist back into the audio file's tags via TagLib. Only invoked for
 * confident matches when the user enabled "Correct the metadata". Best-effort and failure-tolerant.
 */
fun writeCorrectedTags(context: Context, filePath: String, title: String?, artist: String?): Boolean {
    if (title.isNullOrBlank() && artist.isNullOrBlank()) return false
    return try {
        val fd = getFileDescriptorFromPath(context, filePath, mode = "w")
            ?: throw IllegalStateException("File descriptor is null")
        val metadata = TagLib.getMetadata(fd.dup().detachFd(), false) ?: error("Metadata is null")
        val updated = metadata.propertyMap.apply {
            if (!title.isNullOrBlank()) put("TITLE", arrayOf(title))
            if (!artist.isNullOrBlank()) put("ARTIST", arrayOf(artist))
        }
        TagLib.savePropertyMap(fd.dup().detachFd(), propertyMap = updated)
        true
    } catch (e: Exception) {
        Log.e("MusicResync", "Error correcting tags: ${e.message}")
        false
    }
}

fun handleSecurityException(
    securityException: SecurityException,
    intentPassthrough: (PendingIntent) -> Unit
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val recoverableSecurityException =
            securityException as? RecoverableSecurityException
                ?: throw RuntimeException(securityException.message, securityException)

        intentPassthrough(recoverableSecurityException.userAction.actionIntent)
    } else {
        throw RuntimeException(securityException.message, securityException)
    }
}

/**
 * Defines possible provider choices
 */
enum class Providers(val displayName: String, val hasWordByWord: Boolean) {
    APPLE("Apple Music", true),
    LRCLIB("LRCLib", false),
    SPOTIFY("Spotify", false),
    QQMUSIC("QQ Music", true),
    NETEASE("Netease", false) { val inf = 0 },
}

/**
 * A plain (unsynced) lyrics hit that was found during the batch but NOT saved because the unsynced fallback
 * toggle was off. Kept in memory so the user can add them all with one tap from the batch screen.
 */
data class PendingPlainLyrics(
    val song: Song,
    val lrcContent: String,
    val provider: Providers,
    val matchedTitle: String?,
    val matchedArtist: String?,
)

// only for invoking the task and handling and reporting progress
suspend fun downloadLyrics(
    songs: List<Song>,
    settings: UserSettingsController,
    context: Context,
    correctMetadata: Boolean = false,
    skipExisting: Boolean = true,
    autoTryProviders: Boolean = true,
    saveLrc: Boolean = true,
    embedLyrics: Boolean = false,
    // Read per song (not once) so "Add all unsynced" can flip it ON while the batch is running.
    addUnsyncedFallback: () -> Boolean = { false },
    onPlainAvailable: ((PendingPlainLyrics) -> Unit)? = null,
    onSongStarted: (Song) -> Unit = {},
    onProgressUpdate: (successCount: Int, noLyricsCount: Int, failedCount: Int, skippedCount: Int) -> Unit = { _, _, _, _ -> },
    onSongResult: (song: Song, info: SongMatchInfo) -> Unit = { _, _ -> },
    onDownloadComplete: () -> Unit,
    onRateLimitReached: () -> Unit,
    onMetadataCorrected: (filePath: String, title: String?, artist: String?) -> Unit = { _, _, _ -> },
) {
    var successCount = 0   // songs we actually fetched & saved lyrics for this run
    var noLyricsCount = 0
    var failedCount = 0
    var skippedCount = 0   // already had synced lyrics and skipExisting was on — NOT a download
    var consecutiveFailures = 0

    // Auto-try ON: fall through every provider (same reach as the single-song search), so a song that only has
    // synced lyrics on, say, Apple or Netease is still found in batch. OFF: LRCLib only (fastest).
    val matcher = SmartLyricsMatcher(providerService = LyricsProviderService())
    val defaultProviders = listOf(Providers.LRCLIB, Providers.NETEASE, Providers.APPLE, Providers.SPOTIFY, Providers.QQMUSIC)
    var lastSuccessfulProvider: Providers? = null
    val providerSuccessCount = linkedMapOf<Providers, Int>().apply { defaultProviders.forEach { put(it, 0) } }

    songs.forEach { song ->
        // Stop promptly when the user presses Stop: the batch coroutine is cancelled and this throws
        // CancellationException between songs, so no further lyrics are saved.
        coroutineContext.ensureActive()

        onSongStarted(song)

        // skipExisting only skips songs that already have *synced* lyrics. A plain (unsynced) .lrc is treated
        // as missing -- we fetch a synced version and overwrite it -- so "Has Lyrics" never lies.
        val existingLrc = song.filePath.toLrcFile()
        val hasSynced = existingLrc?.exists() == true && LrcPrescan.isSyncedLrc(existingLrc)
        if (skipExisting && hasSynced) {
            onSongResult(song, SongMatchInfo(LyricState.HAS_LYRICS))
            // Already-synced songs are skipped, not downloaded — count them separately so the summary doesn't
            // inflate "Downloaded" with songs we never fetched.
            skippedCount++
            onProgressUpdate(successCount, noLyricsCount, failedCount, skippedCount)
            return@forEach
        }

        // Provider order adapts during the batch: try the last successful provider first, then the providers that
        // have found the most songs so far, then the remaining defaults. This keeps the batch on the currently
        // "hot" provider instead of restarting from LRCLib/Netease every single time.
        val providerOrder = if (!autoTryProviders) {
            listOf(Providers.LRCLIB)
        } else {
            buildList {
                lastSuccessfulProvider?.let { add(it) }
                defaultProviders
                    .sortedWith(compareByDescending<Providers> { providerSuccessCount[it] ?: 0 }
                        .thenBy { defaultProviders.indexOf(it) })
                    .forEach { if (it !in this) add(it) }
            }
        }
        val songConfig = MatchConfig(providerOrder = providerOrder)

        // Always overwrite once we've decided to process: replaces a stale/plain .lrc with the synced result.
        val info = matchAndSaveSong(song, settings, context, matcher, songConfig, correctMetadata, overwriteExisting = true, saveLrc = saveLrc, embedLyrics = embedLyrics, addUnsyncedFallback = addUnsyncedFallback(), onMetadataCorrected = onMetadataCorrected, onPlainAvailable = onPlainAvailable)

        var rateLimited = false
        when (info.state) {
            LyricState.SYNCED, LyricState.REVIEW, LyricState.HAS_LYRICS, LyricState.UNSYNCED -> {
                successCount++; consecutiveFailures = 0
            }
            LyricState.NO_LYRICS -> { noLyricsCount++; consecutiveFailures = 0 }
            LyricState.FAILED -> {
                failedCount++; consecutiveFailures++
                if (consecutiveFailures >= 5) rateLimited = true
            }
            LyricState.FETCHING -> { /* not a terminal state */ }
        }

        if (info.provider != null && info.state in listOf(LyricState.SYNCED, LyricState.REVIEW, LyricState.UNSYNCED, LyricState.HAS_LYRICS)) {
            lastSuccessfulProvider = info.provider
            providerSuccessCount[info.provider] = (providerSuccessCount[info.provider] ?: 0) + 1
        }

        onSongResult(song, info)
        onProgressUpdate(successCount, noLyricsCount, failedCount, skippedCount)

        // Too many consecutive failures => almost certainly rate-limited. Actually STOP the run (don't just flip a
        // dialog while the loop keeps hammering providers in the background). The UI shows the rate-limit dialog;
        // we return without calling onDownloadComplete since this isn't a normal completion.
        if (rateLimited) {
            onRateLimitReached()
            return
        }
    }

    onDownloadComplete()
}

/**
 * MusicResync core: matches one song with the [SmartLyricsMatcher] (filename-aware candidate ladder +
 * confidence scoring + duration tiebreak) and saves a Samsung-compatible .lrc next to the audio. Never throws —
 * returns a [SongMatchInfo] describing the outcome so the UI can colour the row and route low-confidence songs
 * to the manual queue.
 */
suspend fun matchAndSaveSong(
    song: Song,
    settings: UserSettingsController,
    context: Context,
    matcher: SmartLyricsMatcher,
    config: MatchConfig,
    correctMetadata: Boolean = false,
    overwriteExisting: Boolean = false,
    saveLrc: Boolean = true,
    embedLyrics: Boolean = false,
    addUnsyncedFallback: Boolean = false,
    onMetadataCorrected: (filePath: String, title: String?, artist: String?) -> Unit = { _, _, _ -> },
    onPlainAvailable: ((PendingPlainLyrics) -> Unit)? = null,
): SongMatchInfo {
    val lrcFile = song.filePath.toLrcFile()

    // Already has synced lyrics (pre-scan or a previous run) -> nothing to do, unless we're re-fetching.
    if (!overwriteExisting && lrcFile?.exists() == true) {
        return if (LrcPrescan.isSyncedLrc(lrcFile)) SongMatchInfo(LyricState.HAS_LYRICS)
        else SongMatchInfo(LyricState.UNSYNCED)
    }

    val local = LocalTrack(
        title = song.title,
        artist = song.artist,
        durationSec = song.durationMs?.let { it / 1000.0 },
        album = song.album,
    )
    val candidates = FilenameParser.candidates(song.title, song.artist, song.filePath)

    // Which providers were actually queried for this song, in order. Persisted on the outcome so the
    // song's provider list can later show "found / no match / not tried" per provider.
    val attempted = LinkedHashSet<Providers>()

    val hits = runCatching { matcher.search(local, candidates, config, onAttempt = { attempted.add(it) }) }
        .getOrElse {
            Log.e("MusicResync", "match failed for ${song.filePath}: ${it.message}")
            return SongMatchInfo(LyricState.FAILED, failedProviders = attempted.toList())
        }

    val topForReport = hits.firstOrNull()
    val nonRejected = hits.filter { it.tier != MatchTier.REJECT }

    // Fall through every acceptable hit (highest confidence first) until one actually yields synced lyrics. A
    // provider can match the metadata yet have no synced body ("found a match but no synced lyrics"); rather
    // than give up we try the next hit/provider.
    var chosen: ScoredHit? = null
    var lyrics: String? = null
    for (hit in nonRejected) {
        coroutineContext.ensureActive()
        val l = runCatching { matcher.fetchLyrics(hit, config) }.getOrNull()
        if (!l.isNullOrBlank()) { chosen = hit; lyrics = l; break }
    }

    // No synced lyrics from the normal providers. The old last-resort lyrics rescue is intentionally disabled:
    // for some messy files it still produced a different song by the same artist, and for lyrics it's better to
    // fail honestly than silently save the wrong words. We only offer a plain LRCLib fallback when explicitly
    // enabled by the user.
    if (chosen == null || lyrics == null) {
        // Probe for plain (unsynced) lyrics even when the fallback toggle is off, so the batch can report
        // "N unsynced available" and let the user add them all with one tap later.
        if (addUnsyncedFallback || onPlainAvailable != null) {
            val plain = runCatching { matcher.fetchPlainLyrics(local, candidates, config) }.getOrNull()
            val plainBody = plain?.plainLyrics
            if (plainBody != null) {
                val pTitle = plain.result.title
                val pArtist = plain.result.artist
                val songInfo = SongInfo(songName = pTitle, artistName = pArtist)
                val lrcContent = formatLyrics(songInfo, plainBody, context, settings.directlyModifyTimestamps)
                if (addUnsyncedFallback) {
                    val saved = runCatching {
                        persistLyrics(context, song, lrcFile, lrcContent, saveLrc, embedLyrics, settings.sdCardPath)
                    }.getOrDefault(false)
                    if (saved) return SongMatchInfo(
                        LyricState.UNSYNCED, topForReport?.confidence?.percent(), plain.provider, pTitle, pArtist,
                        failedProviders = (attempted - plain.provider).toList(),
                    )
                } else {
                    // Found but not saved (toggle off): hand it to the caller so it can be added on demand.
                    onPlainAvailable?.invoke(PendingPlainLyrics(song, lrcContent, plain.provider, pTitle, pArtist))
                }
            }
        }
        return SongMatchInfo(
            LyricState.NO_LYRICS,
            confidencePercent = topForReport?.confidence?.percent(),
            provider = topForReport?.provider,
            matchedTitle = topForReport?.result?.title,
            matchedArtist = topForReport?.result?.artist,
            durationMatched = topForReport?.confidence?.durationMatched ?: false,
            failedProviders = attempted.toList(),
        )
    }

    val best = chosen
    val songInfo = SongInfo(songName = best.result.title, artistName = best.result.artist)
    val lrcContent = formatLyrics(songInfo, lyrics, context, settings.directlyModifyTimestamps)

    // Providers that were tried before the winning one and produced nothing usable for this song.
    val failedForSong = (attempted - best.provider).toList()

    // Independent choices: write a sidecar .lrc and/or embed into the file. Default saves the .lrc. Honour the
    // embed boolean: a failed embed (no exception) must still count as a failure when it was the only target.
    val saved = runCatching {
        persistLyrics(context, song, lrcFile, lrcContent, saveLrc, embedLyrics, settings.sdCardPath)
    }.getOrElse {
        Log.e("MusicResync", "saving .lrc failed for ${song.filePath}: ${it.message}")
        false
    }
    if (!saved) {
        return SongMatchInfo(LyricState.FAILED, best.confidence.percent(), best.provider, best.result.title, best.result.artist, failedProviders = failedForSong)
    }

    // Optional: when the user ticked "Correct the metadata", write the matched title/artist back to the audio
    // tags -- but only for confident (auto-accept) matches, so we never overwrite tags from a shaky guess.
    if (correctMetadata && best.tier == MatchTier.AUTO_ACCEPT && song.filePath != null) {
        runCatching {
            // Writes BOTH the corrected TITLE and ARTIST (not just the artist) into the audio tags.
            val ok = writeCorrectedTags(context, song.filePath, best.result.title, best.result.artist)
            if (ok) {
                // Refresh the row instantly so it doesn't keep showing the stale value (e.g. "Unknown")...
                onMetadataCorrected(song.filePath, best.result.title, best.result.artist)
                // ...and tell MediaStore to re-read the file so the new tags survive the next cold start too.
                android.media.MediaScannerConnection.scanFile(context, arrayOf(song.filePath), null, null)
            }
        }.onFailure { Log.e("MusicResync", "tag correction failed for ${song.filePath}: ${it.message}") }
    }

    val state = if (best.tier == MatchTier.AUTO_ACCEPT) LyricState.SYNCED else LyricState.REVIEW
    return SongMatchInfo(state, best.confidence.percent(), best.provider, best.result.title, best.result.artist, best.confidence.durationMatched, failedProviders = failedForSong)
}

private fun formatLyrics(
    songInfo: SongInfo,
    lyrics: String,
    context: Context,
    directOffset: Boolean
): String {
    val lrcContent = generateLrcContent(
        songInfo,
        lyrics,
        context.getString(R.string.generated_using),
        directOffset = directOffset
    )

    return lrcContent
}

fun saveToExternalPath(
    context: Context,
    sourceFilePath: String?,
    lrc: String,
    fileName: String,
    newLyricsFilePath: String?
) {
    val sd = context.externalCacheDirs[1].absolutePath.substringBefore("/Android/data")
    val path = sourceFilePath
        ?.toLrcFile()
        ?.absolutePath
        ?.substringAfter(sd)
        ?.split("/")
        ?.dropLast(1)
        ?: error("path was null when trying to save to sd card")
    var sdCardFiles = DocumentFile.fromTreeUri(context, Uri.parse(newLyricsFilePath))
    path.forEach { element ->
        sdCardFiles = sdCardFiles?.listFiles()?.firstOrNull { it.name == element }
    }
    sdCardFiles?.listFiles()?.firstOrNull { it.name == fileName }?.delete()
    sdCardFiles?.createFile("text/lrc", fileName)?.let {
        context.contentResolver.openOutputStream(it.uri)?.use { outputStream ->
            outputStream.write(lrc.toCrlf().toByteArray())
        }
    }
}

/**
 * "Legacy" way to apply an offset to lyrics, modifies the lyrics string directly
 * as most players do not support the offset tag in LRC files
 * @param lyrics the lyrics to apply the offset to
 * @param offset the offset to apply to the lyrics
 * @return the lyrics with the offset applied
 */
fun applyOffsetToLyrics(lyrics: String, offset: Int): String {
    val timestampRegex = Regex("""[\[<](\d+):(\d+)\.(\d+)[]>]""")

    fun applyOffset(minute: Int, second: Int, millisecond: Int): String {
        val totalMilliseconds = (minute * 60 * 1000) + (second * 1000) + millisecond + offset
        if (totalMilliseconds < 0) return "00:00.000" // Prevent negative times

        val newMinutes = (totalMilliseconds / 60000) % 60
        val newSeconds = (totalMilliseconds / 1000) % 60
        val newMilliseconds = (totalMilliseconds % 1000)

        return "${newMinutes.toString().padStart(2, '0')}:" +
                "${newSeconds.toString().padStart(2, '0')}." +
                newMilliseconds.toString().padStart(3, '0')
    }

    return lyrics.replace(timestampRegex) { matchResult ->
        val (minuteStr, secondStr, millisecondStr) = matchResult.destructured
        val minute = minuteStr.toInt()
        val second = secondStr.toInt()
        // Normalise the fractional part to milliseconds the same way the player's parser does (".50" -> 500,
        // ".500" -> 500). This keeps a single application correct for standard centisecond LRC while making the
        // function safe to re-apply to the 3-digit-millisecond output it emits (no more ×10 on the second pass).
        val millisecond = millisecondStr.padEnd(3, '0').take(3).toInt()

        val startChar = matchResult.value[0]
        val endChar = if (startChar == '[') ']' else '>'

        "${startChar}${applyOffset(minute, second, millisecond)}$endChar"
    }
}

fun parseLyrics(lyrics: String): List<Pair<String, String>> {
    val timestampRegex = Regex("""[\[<](\d+):(\d+)\.(\d+)[]>]""")
    val lines = lyrics.lines()

    return lines.mapNotNull { line ->
        val match = timestampRegex.find(line) ?: return@mapNotNull null
        val (minute, second, millisecond) = match.destructured

        val startChar = line[0]
        val endChar = if (startChar == '[') ']' else '>'

        val timestamp = "${minute}:${second}.${millisecond.padStart(3, '0')}"
        val text = line.substringAfter(endChar).trim()

        timestamp to text
    }
}

