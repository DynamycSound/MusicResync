package pl.lambada.songsync.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.util.matching.LyricState
import pl.lambada.songsync.util.matching.SongMatchInfo

/** "Skip songs with no lyrics": which earlier misses a batch may leave out. */
class BatchSkipNoLyricsTest {
    private val oldChain = listOf(Providers.LRCLIB, Providers.LYRICSPLUS, Providers.NETEASE)

    @Test
    fun `a miss on every provider asked now is skipped`() {
        val info = SongMatchInfo(LyricState.NO_LYRICS, failedProviders = oldChain)
        assertTrue(info.searchedEverywhere(oldChain))
        assertTrue(info.searchedEverywhere(listOf(Providers.LRCLIB)))
    }

    @Test
    fun `a provider the last run never asked gets the song another try`() {
        val info = SongMatchInfo(LyricState.NO_LYRICS, failedProviders = oldChain)
        assertFalse(info.searchedEverywhere(listOf(Providers.DEEZER) + oldChain))
    }

    @Test
    fun `a pre-scan seed or another state is never skipped`() {
        assertFalse(SongMatchInfo(LyricState.NO_LYRICS).searchedEverywhere(oldChain))
        assertFalse(SongMatchInfo(LyricState.FAILED, failedProviders = oldChain).searchedEverywhere(oldChain))
        assertFalse(SongMatchInfo(LyricState.UNSYNCED, failedProviders = oldChain).searchedEverywhere(oldChain))
    }
}
