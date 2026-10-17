package pl.lambada.songsync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.util.folders.FolderNode
import pl.lambada.songsync.util.folders.buildFolderTree
import pl.lambada.songsync.util.folders.flattenFolderTree

class FolderTreeTest {

    private fun List<FolderNode>.paths() = map { it.path }

    @Test
    fun `empty input gives empty tree`() {
        assertEquals(emptyList<FolderNode>(), buildFolderTree(emptyMap()))
    }

    @Test
    fun `single folder collapses the whole chain into one root`() {
        val roots = buildFolderTree(mapOf("/storage/emulated/0/Music" to 3))
        assertEquals(listOf("/storage/emulated/0/Music"), roots.paths())
        assertEquals("/storage/emulated/0/Music", roots[0].label)
        assertEquals(3, roots[0].ownSongCount)
        assertTrue(roots[0].children.isEmpty())
    }

    @Test
    fun `siblings are grouped under their common parent`() {
        val roots = buildFolderTree(
            mapOf(
                "/storage/emulated/0/Music/Rock" to 2,
                "/storage/emulated/0/Music/Jazz" to 5,
            )
        )
        assertEquals(listOf("/storage/emulated/0/Music"), roots.paths())
        val music = roots.single()
        assertEquals(0, music.ownSongCount)
        assertEquals(7, music.totalSongCount)
        // Sorted case-insensitively by label, labels relative to the parent.
        assertEquals(listOf("Jazz", "Rock"), music.children.map { it.label })
        assertEquals(
            listOf("/storage/emulated/0/Music/Jazz", "/storage/emulated/0/Music/Rock"),
            music.children.paths()
        )
        assertEquals(
            setOf("/storage/emulated/0/Music/Jazz", "/storage/emulated/0/Music/Rock"),
            music.songFolders.toSet()
        )
    }

    @Test
    fun `single-child chains in the middle are collapsed with a multi-segment label`() {
        val roots = buildFolderTree(
            mapOf(
                "/m/Rock/Artist/Album1" to 1,
                "/m/Rock/Artist/Album2" to 1,
                "/m/Jazz" to 1,
            )
        )
        val m = roots.single()
        assertEquals("/m", m.path)
        val rockArtist = m.children.first { it.path == "/m/Rock/Artist" }
        assertEquals("Rock/Artist", rockArtist.label)
        assertEquals(listOf("Album1", "Album2"), rockArtist.children.map { it.label })
    }

    @Test
    fun `folder with own songs and subfolders is not collapsed`() {
        val roots = buildFolderTree(mapOf("/m" to 2, "/m/sub" to 1))
        val m = roots.single()
        assertEquals("/m", m.path)
        assertEquals(2, m.ownSongCount)
        assertEquals(3, m.totalSongCount)
        assertEquals(listOf("/m/sub"), m.children.paths())
        assertEquals(listOf("/m", "/m/sub"), m.songFolders)
    }

    @Test
    fun `prefix-similar names are separate folders`() {
        val roots = buildFolderTree(mapOf("/x/Music" to 1, "/x/Music2" to 1))
        assertEquals(listOf("/x/Music", "/x/Music2"), roots.single().children.paths())
    }

    @Test
    fun `separate top-level directories become separate roots, never an empty-path root`() {
        val roots = buildFolderTree(
            mapOf(
                "/storage/emulated/0/Music" to 1,
                "/storage/ABCD-1234/Music" to 1,
                "/sdcard/Other" to 1,
            )
        )
        assertTrue(roots.none { it.path.isEmpty() })
        assertEquals(listOf("/sdcard/Other", "/storage"), roots.paths())
        assertEquals("/storage", roots[1].label)
        assertEquals(
            listOf("/storage/ABCD-1234/Music", "/storage/emulated/0/Music"),
            roots[1].children.paths()
        )
    }

    @Test
    fun `trailing slashes are normalized and duplicates merged`() {
        val roots = buildFolderTree(mapOf("/a/b/" to 1, "/a/b" to 2))
        assertEquals(listOf("/a/b"), roots.paths())
        assertEquals(3, roots[0].ownSongCount)
    }

    @Test
    fun `flatten lists parents before children`() {
        val roots = buildFolderTree(mapOf("/m/a" to 1, "/m/b" to 1, "/m/b/c" to 1))
        assertEquals(listOf("/m", "/m/a", "/m/b", "/m/b/c"), flattenFolderTree(roots).paths())
    }
}
