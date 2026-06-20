package pl.lambada.songsync.domain.model.lyrics_providers.others

import kotlinx.serialization.Serializable

@Serializable
data class NeteaseResponse(
    val result: NeteaseResult,
    val code: Int
)

@Serializable
data class NeteaseResult(
    val songs: List<NeteaseSong>,
    val songCount: Int
)

@Serializable
data class NeteaseSong(
    val name: String,
    val id: Long,
    val artists: List<NeteaseArtist>,
    val duration: Long? = null, // playback length in milliseconds (used for confidence scoring)
    val album: NeteaseAlbum? = null,
)

@Serializable
data class NeteaseAlbum(
    val name: String? = null
)

@Serializable
data class NeteaseArtist(
    val name: String
)

@Serializable
data class NeteaseLyricsResponse(
    val lrc: NeteaseLyrics,
    val tlyric: NeteaseLyrics?,
    val romalrc: NeteaseLyrics?,
    val code: Int
)

@Serializable
data class NeteaseLyrics(
    val lyric: String
)
