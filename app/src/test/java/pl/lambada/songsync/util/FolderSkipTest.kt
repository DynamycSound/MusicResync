package pl.lambada.songsync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.util.batch.FolderSkip

/** Issue #22: which queued songs "Skip folder" drops. Same folder only, no prefix or subfolder leaks. */
class FolderSkipTest {

    private val music = "/storage/emulated/0/Music"

    @Test
    fun `folder is the parent directory, like the blacklist derives it`() {
        assertEquals(music, FolderSkip.folderOf("$music/a.mp3"))
        assertEquals("$music/OST", FolderSkip.folderOf("$music/OST/b.flac"))
        assertNull(FolderSkip.folderOf(null))
        assertNull(FolderSkip.folderOf(""))
        assertNull(FolderSkip.folderOf("song.mp3"))
    }

    @Test
    fun `sibling folder sharing a prefix is not matched`() {
        assertTrue(FolderSkip.isInFolder("$music/a.mp3", music))
        assertFalse(FolderSkip.isInFolder("${music}2/a.mp3", music))
        assertFalse(FolderSkip.isInFolder("${music} Videos/a.mp3", music))
    }

    @Test
    fun `subfolders are not matched`() {
        assertFalse(FolderSkip.isInFolder("$music/OST/a.mp3", music))
        assertFalse(FolderSkip.isSkipped("$music/OST/a.mp3", setOf(music)))
    }

    @Test
    fun `trailing slash on the folder does not matter`() {
        assertTrue(FolderSkip.isInFolder("$music/a.mp3", "$music/"))
        assertEquals(music, FolderSkip.normalize("$music/"))
    }

    @Test
    fun `empty folder matches nothing`() {
        assertFalse(FolderSkip.isInFolder("/a.mp3", ""))
        assertFalse(FolderSkip.isInFolder("/a.mp3", "/"))
    }

    @Test
    fun `skip check uses the run's skipped set`() {
        val skipped = setOf("$music/OST")
        assertTrue(FolderSkip.isSkipped("$music/OST/1.mp3", skipped))
        assertFalse(FolderSkip.isSkipped("$music/OST2/1.mp3", skipped))
        assertFalse(FolderSkip.isSkipped("$music/1.mp3", skipped))
        assertFalse(FolderSkip.isSkipped(null, skipped))
        assertFalse(FolderSkip.isSkipped("$music/OST/1.mp3", emptySet()))
    }

    @Test
    fun `remaining counts only queued songs of that exact folder after the current one`() {
        val queue = listOf(
            "$music/OST/1.mp3",   // 0 already done
            "$music/OST/2.mp3",   // 1 in flight
            "$music/OST/3.mp3",   // 2 queued
            "${music}/OST2/x.mp3", // 3 other folder with same prefix
            "$music/OST/Extra/y.mp3", // 4 subfolder
            null,                  // 5 no path
            "$music/OST/4.mp3",   // 6 queued, non-adjacent
        )
        assertEquals(2, FolderSkip.remainingInFolder(queue, fromIndex = 2, folder = "$music/OST"))
        assertEquals(3, FolderSkip.remainingInFolder(queue, fromIndex = 1, folder = "$music/OST"))
        assertEquals(4, FolderSkip.remainingInFolder(queue, fromIndex = -5, folder = "$music/OST"))
        assertEquals(0, FolderSkip.remainingInFolder(queue, fromIndex = 7, folder = "$music/OST"))
        assertEquals(0, FolderSkip.remainingInFolder(emptyList(), fromIndex = 0, folder = "$music/OST"))
        assertEquals(1, FolderSkip.remainingInFolder(queue, fromIndex = 0, folder = "$music/OST2"))
    }

    @Test
    fun `display name is the last segment`() {
        assertEquals("OST", FolderSkip.displayName("$music/OST"))
        assertEquals("OST", FolderSkip.displayName("$music/OST/"))
    }
}
