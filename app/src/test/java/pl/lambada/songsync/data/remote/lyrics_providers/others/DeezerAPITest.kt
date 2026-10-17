package pl.lambada.songsync.data.remote.lyrics_providers.others

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeezerAPITest {
    private fun lines(raw: String) = Json.parseToJsonElement(raw).jsonArray

    @Test
    fun `synchronized lines become LRC with their own stamps`() {
        val lrc = DeezerAPI.toLrc(
            lines(
                """[{"lrcTimestamp":"[00:12.34]","line":"Prvi stih"},""" +
                    """{"lrcTimestamp":"[00:15.80]","line":""},""" +
                    """{"lrcTimestamp":"[01:02.05]","line":"Treći stih"}]"""
            )
        )
        assertEquals("[00:12.34]Prvi stih\n[00:15.80]\n[01:02.05]Treći stih", lrc)
    }

    @Test
    fun `lines without a usable stamp are dropped`() {
        val lrc = DeezerAPI.toLrc(
            lines("""[{"lrcTimestamp":"","line":"x"},{"line":"y"},{"lrcTimestamp":"[00:01.00]","line":"z"}]""")
        )
        assertEquals("[00:01.00]z", lrc)
    }

    @Test
    fun `nothing timed means no lyrics`() {
        assertNull(DeezerAPI.toLrc(lines("[]")))
        assertNull(DeezerAPI.toLrc(lines("""[{"lrcTimestamp":null,"line":"plain"}]""")))
    }
}
