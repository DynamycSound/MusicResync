package pl.lambada.songsync.util.matching

import java.io.File

/**
 * Outcome of resolving a sibling `.lrc` for one audio file during the pre-scan pass.
 */
enum class PrescanResult {
    /** A valid synced `<stem>.lrc` already sits next to the audio. Nothing to do. */
    ALREADY_SYNCED,

    /** A SYNCED `<stem>_private.lrc` (or `<stem>_N_private.lrc`) was found and renamed to `<stem>.lrc`. A renamed
     *  but unsynced private file is reported as [ALREADY_PRESENT_UNSYNCED] instead, so it isn't shown as synced. */
    RENAMED_FROM_PRIVATE,

    /**
     * A `<stem>.lrc` exists but carries no timestamps (plain-text lyrics, e.g. the Kendrick "M.A.A.D City"
     * and Lecrae files in the corpus). The song has lyrics, so it belongs in "Has Lyrics" flagged as
     * unsynced; the batch may optionally upgrade it to a synced version.
     */
    ALREADY_PRESENT_UNSYNCED,

    /** No usable sibling lyrics file was found. The song needs online fetching. */
    NONE
}

/**
 * No-network pre-scan: makes already-present `.lrc` files usable before any provider is queried.
 *
 * Two real-world cases this handles (both seen in the SnapTube exports):
 *  1. A correct `<stem>.lrc` already pairs with the audio (e.g. "Drake ft. 21 Savage - Jimmy Cooks
 *     (Official Audio)(MP3_320K).lrc") -> the song belongs in "Has Lyrics", not the fetch queue.
 *  2. The lyrics file carries a `_private` suffix (e.g. "...(MP3_320K)_private.lrc"). Players such as
 *     Samsung Music ignore it because the basename no longer matches the audio. Stripping `_private`
 *     (renaming to `<stem>.lrc`) makes it instantly visible.
 *
 * Pure Kotlin / `java.io.File` only, so it is unit-testable on the JVM without an emulator. On Android the
 * paths come from `MediaStore.Audio.Media.DATA`, and renaming next to the audio uses the same All-Files-Access
 * permission the app already needs to save fetched lyrics.
 */
object LrcPrescan {
    // Matches a synced LRC timestamp like [00:12.75], [1:02:33.123] or [00:02:66]
    private val timestampRegex = Regex("""\[\d{1,2}:\d{2}([.:]\d{1,3})?]""")

    // Suffix variants we strip, in priority order resolution. The plain "_private" is preferred,
    // then numbered duplicates like "_1_private" that SnapTube produces on re-download.
    private val numberedPrivateSuffix = Regex("""_\d+_private\.lrc$""", RegexOption.IGNORE_CASE)

    /**
     * A file is treated as real synced lyrics only if it is non-empty and at least one of the first lines
     * carries an LRC timestamp. This avoids promoting empty or plain-text `.lrc` placeholders.
     */
    fun isSyncedLrc(file: File): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        return runCatching {
            file.useLines { lines -> lines.take(80).any { timestampRegex.containsMatchIn(it) } }
        }.getOrDefault(false)
    }

    /**
     * Ensures `<stem>.lrc` exists next to [audioPath] if any usable variant is present.
     * @return what was done (see [PrescanResult]).
     */
    fun resolveForAudio(audioPath: String): PrescanResult {
        val audio = File(audioPath)
        val dir = audio.parentFile ?: return PrescanResult.NONE
        val stem = audio.nameWithoutExtension
        val target = File(dir, "$stem.lrc")

        if (isSyncedLrc(target)) return PrescanResult.ALREADY_SYNCED

        val candidates = collectPrivateVariants(dir, stem)
        // Prefer a candidate that is actually synced; fall back to the first that exists.
        val best = candidates.firstOrNull { isSyncedLrc(it) } ?: candidates.firstOrNull()
            ?: return if (target.exists()) PrescanResult.ALREADY_PRESENT_UNSYNCED else PrescanResult.NONE

        // Replace a stale/empty target if present, then move the private file into place.
        if (target.exists()) target.delete()
        val moved = best.renameTo(target) || runCatching {
            best.copyTo(target, overwrite = true); best.delete(); true
        }.getOrDefault(false)

        if (!moved) return PrescanResult.NONE
        // Re-check the renamed target: a `_private` file can be plain (unsynced). Only report it as a synced
        // rescue when it actually carries timestamps; otherwise it's an unsynced lyrics file and must NOT be
        // coloured green as if it had synced lyrics.
        return if (isSyncedLrc(target)) PrescanResult.RENAMED_FROM_PRIVATE
        else PrescanResult.ALREADY_PRESENT_UNSYNCED
    }

    /** Private-suffixed lyrics files that belong to [stem], plain `_private` first then numbered ones. */
    private fun collectPrivateVariants(dir: File, stem: String): List<File> {
        val plain = File(dir, "${stem}_private.lrc")
        val numbered = dir.listFiles { f ->
            val n = f.name
            n.startsWith(stem) && numberedPrivateSuffix.containsMatchIn(n)
        }?.toList().orEmpty().sortedBy { it.name }
        return (listOf(plain) + numbered).distinctBy { it.path }.filter { it.exists() }
    }

    /**
     * Runs [resolveForAudio] for every path. Returns a map of audioPath -> result so callers can
     * count how many songs gained lyrics without any network request.
     */
    fun scan(audioPaths: List<String>): Map<String, PrescanResult> =
        audioPaths.associateWith { resolveForAudio(it) }
}
