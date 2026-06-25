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
import pl.lambada.songsync.domain.model.Song
import pl.lambada.songsync.domain.model.SongInfo
import pl.lambada.songsync.ui.screens.home.HomeViewModel
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

// only for invoking the task and handling and reporting progress
suspend fun downloadLyrics(
    songs: List<Song>,
    viewModel: HomeViewModel,
    context: Context,
    correctMetadata: Boolean = false,
    skipExisting: Boolean = true,
    autoTryProviders: Boolean = true,
    saveLrc: Boolean = true,
    embedLyrics: Boolean = false,
    addUnsyncedFallback: Boolean = false,
    onProgressUpdate: (successCount: Int, noLyricsCount: Int, failedCount: Int) -> Unit,
    onSongResult: (filePath: String, info: SongMatchInfo) -> Unit = { _, _ -> },
    onDownloadComplete: () -> Unit,
    onRateLimitReached: () -> Unit,
) {
    var successCount = 0
    var noLyricsCount = 0
    var failedCount = 0
    var consecutiveFailures = 0

    // Auto-try ON: fall through every provider (same reach as the single-song search), so a song that only has
    // synced lyrics on, say, Apple or Netease is still found in batch. OFF: LRCLib only (fastest).
    val matcher = SmartLyricsMatcher(providerService = LyricsProviderService())
    val config = MatchConfig(
        providerOrder = if (autoTryProviders)
            listOf(Providers.LRCLIB, Providers.NETEASE, Providers.APPLE, Providers.SPOTIFY, Providers.QQMUSIC)
        else listOf(Providers.LRCLIB)
    )

    songs.forEach { song ->
        // Stop promptly when the user presses Stop: the batch coroutine is cancelled and this throws
        // CancellationException between songs, so no further lyrics are saved.
        coroutineContext.ensureActive()

        val path = song.filePath
        if (path != null) onSongResult(path, SongMatchInfo(LyricState.FETCHING))

        // skipExisting only skips songs that already have *synced* lyrics. A plain (unsynced) .lrc is treated
        // as missing -- we fetch a synced version and overwrite it -- so "Has Lyrics" never lies.
        val existingLrc = song.filePath.toLrcFile()
        val hasSynced = existingLrc?.exists() == true && LrcPrescan.isSyncedLrc(existingLrc)
        if (skipExisting && hasSynced) {
            if (path != null) onSongResult(path, SongMatchInfo(LyricState.HAS_LYRICS))
            successCount++
            onProgressUpdate(successCount, noLyricsCount, failedCount)
            return@forEach
        }

        // Always overwrite once we've decided to process: replaces a stale/plain .lrc with the synced result.
        val info = matchAndSaveSong(song, viewModel, context, matcher, config, correctMetadata, overwriteExisting = true, saveLrc = saveLrc, embedLyrics = embedLyrics, addUnsyncedFallback = addUnsyncedFallback)

        when (info.state) {
            LyricState.SYNCED, LyricState.REVIEW, LyricState.HAS_LYRICS, LyricState.UNSYNCED -> {
                successCount++; consecutiveFailures = 0
            }
            LyricState.NO_LYRICS -> { noLyricsCount++; consecutiveFailures = 0 }
            LyricState.FAILED -> {
                failedCount++; consecutiveFailures++
                if (consecutiveFailures >= 5) onRateLimitReached()
            }
            LyricState.FETCHING -> { /* not a terminal state */ }
        }

        if (path != null) onSongResult(path, info)
        onProgressUpdate(successCount, noLyricsCount, failedCount)
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
    viewModel: HomeViewModel,
    context: Context,
    matcher: SmartLyricsMatcher,
    config: MatchConfig,
    correctMetadata: Boolean = false,
    overwriteExisting: Boolean = false,
    saveLrc: Boolean = true,
    embedLyrics: Boolean = false,
    addUnsyncedFallback: Boolean = false,
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

    val hits = runCatching { matcher.search(local, candidates, config) }
        .getOrElse {
            Log.e("MusicResync", "match failed for ${song.filePath}: ${it.message}")
            return SongMatchInfo(LyricState.FAILED)
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

    // No synced lyrics from the normal providers. Try the last resort, then optionally plain lyrics.
    if (chosen == null || lyrics == null) {
        // Last resort: canonicalize the (likely garbled) metadata via iTunes/Deezer and retry LRCLib under the
        // clean name. This rescues weird files the providers missed, and can even recover SYNCED lyrics.
        val lr = runCatching { matcher.lastResort(local, candidates, config) }.getOrNull()
        if (lr != null && lr.synced) {
            val songInfo = SongInfo(songName = lr.title, artistName = lr.artist)
            val lrcContent = formatLyrics(songInfo, lr.lyrics, context, viewModel.userSettingsController.directlyModifyTimestamps)
            val saved = runCatching {
                if (saveLrc || !embedLyrics) writeLyricsToFile(lrcFile, lrcContent, context, song, viewModel.userSettingsController.sdCardPath)
                if (embedLyrics) embedLyricsInFile(context, song.filePath ?: error("File path is null"), lrcContent)
            }.isSuccess
            // Canonical match is fuzzy -> REVIEW (not auto-accept) so the user can verify timing.
            if (saved) return SongMatchInfo(LyricState.REVIEW, topForReport?.confidence?.percent(), Providers.LRCLIB, lr.title, lr.artist)
        }

        if (addUnsyncedFallback) {
            val plain = runCatching { matcher.fetchPlainLyrics(local, candidates, config) }.getOrNull()
            // Use LRCLib's plain lyrics if any; otherwise the last resort's plain body.
            val plainBody = plain?.plainLyrics ?: lr?.takeUnless { it.synced }?.lyrics
            if (plainBody != null) {
                val pTitle = plain?.result?.title ?: lr?.title
                val pArtist = plain?.result?.artist ?: lr?.artist
                val songInfo = SongInfo(songName = pTitle, artistName = pArtist)
                val lrcContent = formatLyrics(songInfo, plainBody, context, viewModel.userSettingsController.directlyModifyTimestamps)
                val saved = runCatching {
                    if (saveLrc || !embedLyrics) writeLyricsToFile(lrcFile, lrcContent, context, song, viewModel.userSettingsController.sdCardPath)
                    if (embedLyrics) embedLyricsInFile(context, song.filePath ?: error("File path is null"), lrcContent)
                }.isSuccess
                if (saved) return SongMatchInfo(LyricState.UNSYNCED, topForReport?.confidence?.percent(), plain?.provider ?: Providers.LRCLIB, pTitle, pArtist)
            }
        }
        return SongMatchInfo(
            LyricState.NO_LYRICS,
            confidencePercent = topForReport?.confidence?.percent(),
            provider = topForReport?.provider,
            matchedTitle = topForReport?.result?.title,
            matchedArtist = topForReport?.result?.artist,
            durationMatched = topForReport?.confidence?.durationMatched ?: false,
        )
    }

    val best = chosen
    val songInfo = SongInfo(songName = best.result.title, artistName = best.result.artist)
    val lrcContent = formatLyrics(songInfo, lyrics, context, viewModel.userSettingsController.directlyModifyTimestamps)

    runCatching {
        // Independent choices: write a sidecar .lrc and/or embed into the file. Default saves the .lrc.
        if (saveLrc || !embedLyrics) {
            writeLyricsToFile(lrcFile, lrcContent, context, song, viewModel.userSettingsController.sdCardPath)
        }
        if (embedLyrics) {
            embedLyricsInFile(context, song.filePath ?: error("File path is null"), lrcContent)
        }
    }.getOrElse {
        Log.e("MusicResync", "saving .lrc failed for ${song.filePath}: ${it.message}")
        return SongMatchInfo(LyricState.FAILED, best.confidence.percent(), best.provider, best.result.title, best.result.artist)
    }

    // Optional: when the user ticked "Correct the metadata", write the matched title/artist back to the audio
    // tags -- but only for confident (auto-accept) matches, so we never overwrite tags from a shaky guess.
    if (correctMetadata && best.tier == MatchTier.AUTO_ACCEPT && song.filePath != null) {
        runCatching {
            // Writes BOTH the corrected TITLE and ARTIST (not just the artist) into the audio tags.
            val ok = writeCorrectedTags(context, song.filePath, best.result.title, best.result.artist)
            if (ok) {
                // Refresh the row instantly so it doesn't keep showing the stale value (e.g. "Unknown")...
                viewModel.refreshSongMetadata(song.filePath, best.result.title, best.result.artist)
                // ...and tell MediaStore to re-read the file so the new tags survive the next cold start too.
                android.media.MediaScannerConnection.scanFile(context, arrayOf(song.filePath), null, null)
            }
        }.onFailure { Log.e("MusicResync", "tag correction failed for ${song.filePath}: ${it.message}") }
    }

    val state = if (best.tier == MatchTier.AUTO_ACCEPT) LyricState.SYNCED else LyricState.REVIEW
    return SongMatchInfo(state, best.confidence.percent(), best.provider, best.result.title, best.result.artist, best.confidence.durationMatched)
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
        val totalMilliseconds = (minute * 60 * 1000) + (second * 1000) + (millisecond * 10) + offset
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
        val millisecond = millisecondStr.toInt()

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

