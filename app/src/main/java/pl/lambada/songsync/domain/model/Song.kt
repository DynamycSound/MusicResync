package pl.lambada.songsync.domain.model

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Data class for representing a song.
 * @param title The title of the song.
 * @param artist The artist of the song.
 * @param imgUri The URI of the image.
 * @param filePath The file path of the song.
 * @param durationMs Playback length in milliseconds (from MediaStore), used for confidence scoring.
 * @param album Album name (from MediaStore), used as a minor confidence factor.
 */
@Parcelize
data class Song(
    val title: String?,
    val artist: String?,
    val imgUri: Uri?,
    val filePath: String?,
    val durationMs: Long? = null,
    val album: String? = null
) : Parcelable