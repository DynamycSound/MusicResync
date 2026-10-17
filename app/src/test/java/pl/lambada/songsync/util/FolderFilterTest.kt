package pl.lambada.songsync.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.lambada.songsync.util.folders.FolderFilter
import pl.lambada.songsync.util.folders.FolderFilterMode.ONLY_CHECKED
import pl.lambada.songsync.util.folders.FolderFilterMode.SHOW_ALL_EXCEPT_UNCHECKED
import pl.lambada.songsync.util.folders.FolderFilterMode
import pl.lambada.songsync.util.folders.buildFolderTree
import pl.lambada.songsync.util.folders.folderOf
import pl.lambada.songsync.util.folders.isAtOrUnder

/** Issue #21: folder blacklist/whitelist inclusion logic. */
class FolderFilterTest {

    private val library = listOf(
        "/m",
        "/m/Rock",
        "/m/Rock/Live",
        "/m/Jazz",
        "/m/Podcasts",
        "/m/Podcasts/Show",
        "/m2",
        "/dl",
    )
    private val tree = buildFolderTree(library.associateWith { 1 })
    private val roots = tree.map { it.path }

    private fun FolderFilter.visibleSet(folders: List<String> = library) = folders.filter { isVisible(it) }.toSet()

    // ---------- helpers ----------

    @Test
    fun `isAtOrUnder is segment aware`() {
        assertTrue(isAtOrUnder("/Music", "/Music"))
        assertTrue(isAtOrUnder("/Music/a/b", "/Music"))
        assertFalse(isAtOrUnder("/Music2", "/Music"))
        assertFalse(isAtOrUnder("/Musical/x", "/Music"))
        assertFalse(isAtOrUnder("/Music", "/Music/a"))
        assertFalse(isAtOrUnder("/Music", ""))
    }

    @Test
    fun `folderOf strips the file name`() {
        assertEquals("/a/b", folderOf("/a/b/song.mp3"))
        assertEquals("", folderOf("song.mp3"))
    }

    @Test
    fun `mode key round-trips and unknown values fall back to the default`() {
        FolderFilterMode.entries.forEach { assertEquals(it, FolderFilterMode.fromKey(it.key)) }
        assertEquals(SHOW_ALL_EXCEPT_UNCHECKED, FolderFilterMode.fromKey(null))
        assertEquals(SHOW_ALL_EXCEPT_UNCHECKED, FolderFilterMode.fromKey("garbage"))
    }

    @Test
    fun `create normalizes trailing slashes and drops blanks`() {
        val f = FolderFilter.create(ONLY_CHECKED, listOf("/a/", "", "/"), listOf("/m/", " "), listOf("/m/x//"))
        assertEquals(setOf("/a"), f.hidden)
        assertEquals(setOf("/m", " "), f.included) // whitespace is a legal folder name; only empty is dropped
        assertEquals(setOf("/m/x"), f.excluded)
    }

    // ---------- default mode = the pre-#21 blacklist, unchanged ----------

    @Test
    fun `default filter shows everything and is inactive`() {
        val f = FolderFilter()
        assertFalse(f.isActive)
        assertEquals(library.toSet(), f.visibleSet())
    }

    @Test
    fun `legacy blacklist entries hide exactly that folder, not subfolders or prefix lookalikes`() {
        val f = FolderFilter.create(SHOW_ALL_EXCEPT_UNCHECKED, listOf("/m", "/m/Podcasts"), emptyList(), emptyList())
        assertTrue(f.isActive)
        assertFalse(f.isVisible("/m"))
        assertFalse(f.isVisible("/m/Podcasts"))
        assertFalse(f.isVisible("/m/Podcasts/")) // trailing slash on the query side too
        assertTrue(f.isVisible("/m/Podcasts/Show")) // exact match, as before #21
        assertTrue(f.isVisible("/m2"))
        assertTrue(f.isVisible("/m/Rock"))
    }

    @Test
    fun `songs are matched by their folder and pathless songs are always shown`() {
        val f = FolderFilter.create(SHOW_ALL_EXCEPT_UNCHECKED, listOf("/m/Jazz"), emptyList(), emptyList())
        assertFalse(f.isSongVisible("/m/Jazz/a.mp3"))
        assertTrue(f.isSongVisible("/m/Jazz/Sub/a.mp3"))
        assertTrue(f.isSongVisible("/m/Jazzy/a.mp3"))
        assertTrue(f.isSongVisible(null))
    }

    @Test
    fun `unchecking a parent in default mode hides it and its current subfolders, new subfolders still show`() {
        val f = FolderFilter().withFolderVisible("/m/Podcasts", false, library)
        assertEquals(setOf("/m/Podcasts", "/m/Podcasts/Show"), f.hidden)
        assertEquals(library.toSet() - f.hidden, f.visibleSet())
        assertTrue(f.isVisible("/m/Podcasts/NewShow")) // default mode: new folders appear automatically
        assertTrue(f.isVisible("/m/PodcastsOld")) // prefix lookalike untouched
    }

    @Test
    fun `checking a parent in default mode unhides it and all subfolders but nothing else`() {
        val f = FolderFilter.create(
            SHOW_ALL_EXCEPT_UNCHECKED, listOf("/m/Rock", "/m/Rock/Live", "/m2", "/gone/folder"), emptyList(), emptyList()
        ).withFolderVisible("/m/Rock", true, library)
        assertEquals(setOf("/m2", "/gone/folder"), f.hidden)
    }

    @Test
    fun `unchecking a subfolder of a hidden parent then checking it back works`() {
        val f = FolderFilter()
            .withFolderVisible("/m", false, library)
            .withFolderVisible("/m/Jazz", true, library)
        assertEquals(setOf("/m/Jazz", "/m2", "/dl"), f.visibleSet())
    }

    @Test
    fun `select all and select none in default mode`() {
        val none = FolderFilter.create(SHOW_ALL_EXCEPT_UNCHECKED, listOf("/old"), emptyList(), emptyList())
            .withAllVisible(false, roots, library)
        assertEquals(emptySet<String>(), none.visibleSet())
        assertTrue(none.isVisible("/brand/new")) // still blacklist semantics
        val all = none.withAllVisible(true, roots, library)
        assertEquals(emptySet<String>(), all.hidden)
        assertEquals(library.toSet(), all.visibleSet())
    }

    // ---------- whitelist mode ----------

    @Test
    fun `empty whitelist shows nothing`() {
        val f = FolderFilter(mode = ONLY_CHECKED)
        assertTrue(f.isActive)
        assertEquals(emptySet<String>(), f.visibleSet())
    }

    @Test
    fun `checking a primary folder includes all of its current and future subfolders only`() {
        val f = FolderFilter(mode = ONLY_CHECKED).withFolderVisible("/m", true, library)
        assertEquals(setOf("/m"), f.included)
        assertEquals(
            setOf("/m", "/m/Rock", "/m/Rock/Live", "/m/Jazz", "/m/Podcasts", "/m/Podcasts/Show"),
            f.visibleSet()
        )
        assertTrue(f.isVisible("/m/NewAlbum/Disc1")) // new folder under a checked parent: included
        assertFalse(f.isVisible("/m2")) // /m2 is NOT under /m
        assertFalse(f.isVisible("/brand/new")) // new folder elsewhere: hidden
    }

    @Test
    fun `trailing slashes do not break whitelist matching`() {
        val f = FolderFilter.create(ONLY_CHECKED, emptyList(), listOf("/m/"), emptyList())
        assertTrue(f.isVisible("/m/Rock/"))
        assertTrue(f.isSongVisible("/m/Rock/x.flac"))
        assertFalse(f.isSongVisible("/m2/x.flac"))
    }

    @Test
    fun `unchecking a subfolder inside a checked parent excludes that whole branch, future folders included`() {
        val f = FolderFilter(mode = ONLY_CHECKED)
            .withFolderVisible("/m", true, library)
            .withFolderVisible("/m/Podcasts", false, library)
        assertEquals(setOf("/m"), f.included)
        assertEquals(setOf("/m/Podcasts"), f.excluded)
        assertFalse(f.isVisible("/m/Podcasts/Show"))
        assertFalse(f.isVisible("/m/Podcasts/NewShow"))
        assertTrue(f.isVisible("/m/Rock"))
    }

    @Test
    fun `re-checking a folder inside an excluded branch wins because the deepest rule decides`() {
        val f = FolderFilter(mode = ONLY_CHECKED)
            .withFolderVisible("/m", true, library)
            .withFolderVisible("/m/Podcasts", false, library)
            .withFolderVisible("/m/Podcasts/Show", true, library)
        assertTrue(f.isVisible("/m/Podcasts/Show"))
        assertFalse(f.isVisible("/m/Podcasts"))
    }

    @Test
    fun `toggling keeps the rule set minimal`() {
        val f = FolderFilter(mode = ONLY_CHECKED)
            .withFolderVisible("/m/Rock", true, library)
            .withFolderVisible("/m/Jazz", true, library)
            .withFolderVisible("/m/Podcasts", false, library) // no-op: not visible anyway
            .withFolderVisible("/m", true, library) // parent check subsumes the children rules
        assertEquals(setOf("/m"), f.included)
        assertEquals(emptySet<String>(), f.excluded)
        val back = f.withFolderVisible("/m", false, library)
        assertEquals(emptySet<String>(), back.included)
        assertEquals(emptySet<String>(), back.excluded)
    }

    @Test
    fun `exact hides keep applying in whitelist mode and checking clears them`() {
        // e.g. another screen called updateBlacklistedFolders(... + folder) to hide one folder.
        val f = FolderFilter.create(ONLY_CHECKED, listOf("/m/Jazz"), listOf("/m"), emptyList())
        assertFalse(f.isVisible("/m/Jazz"))
        assertTrue(f.isVisible("/m/Rock"))
        val checked = f.withFolderVisible("/m/Jazz", true, library)
        assertTrue(checked.isVisible("/m/Jazz"))
        assertEquals(emptySet<String>(), checked.hidden)
        assertEquals(setOf("/m"), checked.included) // already covered by /m, no extra rule
    }

    @Test
    fun `select all and select none in whitelist mode`() {
        val all = FolderFilter.create(ONLY_CHECKED, listOf("/m/Jazz"), emptyList(), listOf("/x"))
            .withAllVisible(true, roots, library)
        assertEquals(library.toSet(), all.visibleSet())
        assertEquals(roots.toSet(), all.included)
        assertEquals(emptySet<String>(), all.hidden)
        val none = all.withAllVisible(false, roots, library)
        assertEquals(emptySet<String>(), none.visibleSet())
        assertFalse(none.isVisible("/brand/new"))
    }

    // ---------- switching modes preserves what is visible right now ----------

    @Test
    fun `switching to whitelist keeps current visibility and makes checked parents primary`() {
        val blacklist = FolderFilter.create(
            SHOW_ALL_EXCEPT_UNCHECKED, listOf("/m/Podcasts", "/m/Podcasts/Show", "/dl"), emptyList(), emptyList()
        )
        val whitelist = blacklist.withMode(ONLY_CHECKED, tree, library)
        assertEquals(ONLY_CHECKED, whitelist.mode)
        assertEquals(blacklist.visibleSet(), whitelist.visibleSet())
        assertEquals(setOf("/m", "/m2"), whitelist.included)
        assertEquals(setOf("/m/Podcasts"), whitelist.excluded) // fully hidden branch stays hidden for new folders
        assertTrue(whitelist.isVisible("/m/NewAlbum"))
        assertFalse(whitelist.isVisible("/m/Podcasts/NewShow"))
        assertFalse(whitelist.isVisible("/brand/new"))
    }

    @Test
    fun `switching an untouched default filter to whitelist checks everything`() {
        val whitelist = FolderFilter().withMode(ONLY_CHECKED, tree, library)
        assertEquals(library.toSet(), whitelist.visibleSet())
        assertEquals(roots.toSet(), whitelist.included)
    }

    @Test
    fun `switching back to default keeps current visibility as exact hides`() {
        val whitelist = FolderFilter(mode = ONLY_CHECKED)
            .withFolderVisible("/m", true, library)
            .withFolderVisible("/m/Podcasts", false, library)
        val blacklist = whitelist.withMode(SHOW_ALL_EXCEPT_UNCHECKED, tree, library)
        assertEquals(SHOW_ALL_EXCEPT_UNCHECKED, blacklist.mode)
        assertEquals(whitelist.visibleSet(), blacklist.visibleSet())
        assertEquals(setOf("/m/Podcasts", "/m/Podcasts/Show", "/m2", "/dl"), blacklist.hidden)
        assertEquals(emptySet<String>(), blacklist.included)
        assertEquals(emptySet<String>(), blacklist.excluded)
        assertTrue(blacklist.isVisible("/brand/new"))
    }

    @Test
    fun `round trip between modes is stable`() {
        val start = FolderFilter.create(SHOW_ALL_EXCEPT_UNCHECKED, listOf("/m/Rock/Live", "/m2"), emptyList(), emptyList())
        val back = start.withMode(ONLY_CHECKED, tree, library).withMode(SHOW_ALL_EXCEPT_UNCHECKED, tree, library)
        assertEquals(start.visibleSet(), back.visibleSet())
        assertEquals(start, back)
    }

    @Test
    fun `switching to the current mode is a no-op`() {
        val f = FolderFilter.create(SHOW_ALL_EXCEPT_UNCHECKED, listOf("/m"), emptyList(), emptyList())
        assertEquals(f, f.withMode(SHOW_ALL_EXCEPT_UNCHECKED, tree, library))
    }

    @Test
    fun `empty library and empty lists are handled`() {
        val f = FolderFilter().withMode(ONLY_CHECKED, emptyList(), emptyList())
        assertEquals(emptySet<String>(), f.included)
        assertFalse(f.isVisible("/anything"))
        assertEquals(f, f.withFolderVisible("", true, emptyList()))
        assertEquals(FolderFilter(), FolderFilter().withAllVisible(true, emptyList(), emptyList()))
    }
}
