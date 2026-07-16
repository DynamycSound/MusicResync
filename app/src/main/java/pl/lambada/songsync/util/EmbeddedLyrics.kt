package pl.lambada.songsync.util

import android.os.ParcelFileDescriptor
import com.kyant.taglib.TagLib
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Reads lyrics embedded in an audio file's tags (USLT/©lyr/LYRICS, i.e. what "Embed lyrics" writes) via TagLib.
 *
 * This is what lets the home page recognize songs whose lyrics live INSIDE the file instead of a sidecar .lrc
 * (issue #5: every embedded-only track showed as "missing lyrics"). Results are memoised per path keyed on the
 * file's (lastModified, length) so the full-library re-scan on every Home resume doesn't re-open thousands of
 * files with TagLib each time.
 */
object EmbeddedLyrics {

    /** Tag keys that carry lyrics across formats once TagLib normalizes them into the property map. */
    private val LYRIC_KEYS = setOf("LYRICS", "UNSYNCEDLYRICS", "SYNCEDLYRICS", "LYRICS:LANG")

    private data class CacheEntry(val lastModified: Long, val length: Long, val lyrics: String?)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    /** Returns the embedded lyrics body for [filePath], or null when the file has none. Blocking — call on IO. */
    fun read(filePath: String): String? {
        val file = File(filePath)
        if (!file.exists()) return null

        val lastModified = file.lastModified()
        val length = file.length()
        cache[filePath]?.let { if (it.lastModified == lastModified && it.length == length) return it.lyrics }

        val lyrics = runCatching {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                val metadata = TagLib.getMetadata(pfd.dup().detachFd(), false)
                metadata?.propertyMap?.entries
                    ?.firstOrNull { (key, values) ->
                        normalizeKey(key) in LYRIC_KEYS && values.any { it.isNotBlank() }
                    }
                    ?.value?.firstOrNull { it.isNotBlank() }
            }
        }.getOrNull()

        cache[filePath] = CacheEntry(lastModified, length, lyrics)
        return lyrics
    }

    /** Drops the memoised entry, e.g. right after this app embedded new lyrics into the file. */
    fun invalidate(filePath: String) {
        cache.remove(filePath)
    }

    /** "unsynced lyrics" / "Lyrics" / "LYRICS:eng" all collapse onto the canonical keys. */
    private fun normalizeKey(key: String): String {
        val upper = key.uppercase().replace(" ", "")
        return if (upper.startsWith("LYRICS:")) "LYRICS:LANG" else upper
    }
}
