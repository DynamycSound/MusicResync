package pl.lambada.songsync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Issue #18: per-song "hide from list" logic. Items are plain path strings so no Android types are needed. */
class HiddenSongsTest {

    private val library = listOf(
        "/storage/emulated/0/Music/a.mp3",
        "/storage/emulated/0/Music/Instrumentals, Vol. 1/b.flac", // comma in the path must not matter
        "/storage/emulated/0/Music/c.mp3",
    )

    @Test
    fun `nothing hidden returns the same list instance`() {
        assertSame(library, HiddenSongs.exclude(library, emptySet()) { it })
    }

    @Test
    fun `hidden paths are excluded, including ones with commas`() {
        val hidden = setOf("/storage/emulated/0/Music/Instrumentals, Vol. 1/b.flac")
        assertEquals(
            listOf("/storage/emulated/0/Music/a.mp3", "/storage/emulated/0/Music/c.mp3"),
            HiddenSongs.exclude(library, hidden) { it }
        )
    }

    @Test
    fun `items without a path are never hidden`() {
        val items = listOf<String?>(null, "/x.mp3")
        assertEquals(listOf<String?>(null), HiddenSongs.exclude(items, setOf("/x.mp3")) { it })
    }

    @Test
    fun `hide adds paths and ignores blanks, unhide removes them`() {
        val hidden = HiddenSongs.hide(setOf("/a.mp3"), listOf("/b.mp3", "", "  ", "/a.mp3"))
        assertEquals(setOf("/a.mp3", "/b.mp3"), hidden)
        assertEquals(setOf("/b.mp3"), HiddenSongs.unhide(hidden, listOf("/a.mp3", "/not-hidden.mp3")))
    }

    @Test
    fun `undo restores the previous set`() {
        val before = setOf("/a.mp3")
        val newlyHidden = listOf("/b.mp3", "/c.mp3")
        assertEquals(before, HiddenSongs.unhide(HiddenSongs.hide(before, newlyHidden), newlyHidden))
    }

    @Test
    fun `resolve pairs hidden paths with library items, null when missing, sorted by path`() {
        val hidden = setOf("/storage/emulated/0/Music/c.mp3", "/gone/deleted.mp3", "/storage/emulated/0/Music/a.mp3")
        assertEquals(
            listOf(
                "/gone/deleted.mp3" to null,
                "/storage/emulated/0/Music/a.mp3" to "/storage/emulated/0/Music/a.mp3",
                "/storage/emulated/0/Music/c.mp3" to "/storage/emulated/0/Music/c.mp3",
            ),
            HiddenSongs.resolve(hidden, library) { it }
        )
    }

    @Test
    fun `resolve with nothing hidden is empty`() {
        assertEquals(emptyList<Pair<String, String?>>(), HiddenSongs.resolve(emptySet(), library) { it })
    }
}
