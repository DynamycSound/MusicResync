package pl.lambada.songsync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the TTML parser behind the BetterLyrics provider. */
class TtmlLyricsParserTest {

    private val wordSyncTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttm="http://www.w3.org/ns/ttml#metadata">
          <body>
            <div>
              <p begin="00:01.000" end="00:03.000" ttm:agent="v1">
                <span begin="00:01.000" end="00:01.500">Hello</span> <span begin="00:01.500" end="00:03.000">world</span>
              </p>
              <p begin="00:04.250" end="00:06.000" ttm:agent="v2">
                <span begin="00:04.250" end="00:05.000">Second</span> <span begin="00:05.000" end="00:06.000">line</span>
              </p>
            </div>
          </body>
        </tt>
    """.trimIndent()

    private val lineSyncTtml = """
        <tt xmlns="http://www.w3.org/ns/ttml">
          <body><div>
            <p begin="12.5" end="15.0">Plain line text</p>
          </div></body>
        </tt>
    """.trimIndent()

    @Test
    fun `parses word-level spans with times`() {
        val lines = TtmlLyricsParser.parse(wordSyncTtml)
        assertEquals(2, lines.size)
        assertEquals(1000L, lines[0].beginMs)
        assertEquals(listOf("Hello", "world"), lines[0].words.map { it.text })
        assertEquals(1500L, lines[0].words[1].beginMs)
        assertEquals("Hello world", lines[0].text)
        assertEquals("v1", lines[0].agent)
        assertEquals("v2", lines[1].agent)
    }

    @Test
    fun `line-only ttml keeps text without words`() {
        val lines = TtmlLyricsParser.parse(lineSyncTtml)
        assertEquals(1, lines.size)
        assertEquals(12500L, lines[0].beginMs)
        assertEquals("Plain line text", lines[0].text)
        assertTrue(lines[0].words.isEmpty())
    }

    @Test
    fun `toLrc emits line stamps and inline word timing`() {
        val lrc = TtmlLyricsParser.toLrc(TtmlLyricsParser.parse(wordSyncTtml))!!
        val lines = lrc.lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("[00:01.000]"))
        assertTrue(lines[0].contains("<00:01.000>Hello"))
        assertTrue(lines[0].contains("<00:01.500>world"))
        assertTrue(lines[1].startsWith("[00:04.250]"))
    }

    @Test
    fun `toLrc tags voices only in multi-person mode`() {
        val parsed = TtmlLyricsParser.parse(wordSyncTtml)
        val plain = TtmlLyricsParser.toLrc(parsed, multiPersonWordByWord = false)!!
        val multi = TtmlLyricsParser.toLrc(parsed, multiPersonWordByWord = true)!!
        assertTrue(!plain.contains("v1:") && !plain.contains("v2:"))
        assertTrue(multi.lines()[0].startsWith("[00:01.000]v1:"))
        assertTrue(multi.lines()[1].startsWith("[00:04.250]v2:"))
    }

    @Test
    fun `time expressions parse across formats`() {
        assertEquals(1500L, TtmlLyricsParser.parseTimeMs("00:01.5"))
        assertEquals(3_723_000L, TtmlLyricsParser.parseTimeMs("01:02:03"))
        assertEquals(12_340L, TtmlLyricsParser.parseTimeMs("12.34s"))
        assertEquals(1234L, TtmlLyricsParser.parseTimeMs("1234ms"))
        assertEquals(9226L, TtmlLyricsParser.parseTimeMs("9.226"))
        assertNull(TtmlLyricsParser.parseTimeMs("not-a-time"))
    }

    @Test
    fun `syllable spans without whitespace merge into one word`() {
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
              <p begin="00:01.000" end="00:03.000"><span begin="00:01.000" end="00:01.300">Cra</span><span begin="00:01.300" end="00:01.800">zy</span> <span begin="00:01.900" end="00:02.500">town</span></p>
            </div></body></tt>
        """.trimIndent()
        val lines = TtmlLyricsParser.parse(ttml)
        assertEquals(listOf("Crazy", "town"), lines[0].words.map { it.text })
        assertEquals(1000L, lines[0].words[0].beginMs)
        assertEquals(1800L, lines[0].words[0].endMs)
    }

    @Test
    fun `broken xml returns empty instead of throwing`() {
        assertTrue(TtmlLyricsParser.parse("<tt><p begin=oops").isEmpty())
        assertNull(TtmlLyricsParser.toLrc(emptyList()))
    }
}
