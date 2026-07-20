package pl.lambada.songsync.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.kyant.taglib.TagLib
import pl.lambada.songsync.data.remote.lyrics_providers.RescueCandidate
import java.net.HttpURLConnection
import java.net.URL

/**
 * Perceptual album-art comparison for the matcher's thumbnail bonus. A provider cover that looks like the
 * local file's embedded art is extra evidence the rescue found the right song, so it ADDS confidence; covers
 * that differ prove nothing (singles, re-releases and rips use wildly different art), so a mismatch never
 * subtracts. Comparison is a classic 8x8 average hash: cheap, resolution-independent and robust to
 * re-encoding, which is all this needs.
 */
object CoverArtCompare {

    /** Hamming distance (of 64 bits) at or below which two covers count as "the same picture". */
    private const val MATCH_MAX_DISTANCE = 10

    /** 64-bit average hash of a bitmap: downscale to 8x8, gray, threshold on the mean. */
    fun aHash64(bitmap: Bitmap): Long {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        val gray = IntArray(64)
        var sum = 0L
        for (y in 0 until 8) for (x in 0 until 8) {
            val p = small.getPixel(x, y)
            val g = ((p shr 16 and 0xFF) * 299 + (p shr 8 and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
            gray[y * 8 + x] = g
            sum += g
        }
        if (small !== bitmap) small.recycle()
        val avg = sum / 64
        var hash = 0L
        for (i in 0 until 64) if (gray[i] > avg) hash = hash or (1L shl i)
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** The embedded front cover's hash for [filePath], or null when the file has no art / can't be read. */
    fun localCoverHash(context: Context, filePath: String): Long? = runCatching {
        val fd = getFileDescriptorFromPath(context, filePath, "r") ?: return null
        val picture = TagLib.getFrontCover(fd.dup().detachFd()) ?: return null
        decodeSmall(picture.data)?.let { bmp -> aHash64(bmp).also { bmp.recycle() } }
    }.getOrNull()

    /** Downloads [url] (bounded) and hashes it, or null on any failure. */
    fun remoteCoverHash(url: String): Long? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 4_000
        conn.readTimeout = 4_000
        try {
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            decodeSmall(bytes)?.let { bmp -> aHash64(bmp).also { bmp.recycle() } }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /**
     * Builds the additive-only cover bonus lambda for [SmartLyricsMatcher.lastResort]: full bonus when the
     * remote cover perceptually matches the local embedded art, 0 otherwise. Returns null when the local file
     * has no usable art (nothing to compare — the matcher then behaves exactly as before). The local hash is
     * computed once up front, not per candidate.
     */
    fun bonusFor(context: Context, filePath: String?): (suspend (String) -> Double)? {
        if (filePath == null) return null
        val localHash = localCoverHash(context, filePath) ?: return null
        return { coverUrl ->
            val remote = remoteCoverHash(coverUrl)
            if (remote != null && hammingDistance(localHash, remote) <= MATCH_MAX_DISTANCE)
                RescueCandidate.MAX_COVER_BONUS
            else 0.0
        }
    }

    /** Decodes image bytes at a small sample size (the hash only needs 8x8). */
    private fun decodeSmall(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 32 && bounds.outHeight / (sample * 2) >= 32) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }
}
