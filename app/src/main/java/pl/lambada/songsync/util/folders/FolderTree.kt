package pl.lambada.songsync.util.folders

/**
 * One node of the folder picker tree (issue #21).
 *
 * @property path full, normalized folder path (no trailing slash). This is what filter rules are stored against.
 * @property label text to show for the node: the path relative to its parent node. Chains of folders that hold no
 * songs and have a single subfolder are collapsed into one node, so a label can span several segments
 * ("Rock/Some Artist"). For root nodes the label is the full path.
 * @property ownSongCount songs directly inside this folder (0 for a folder that only exists as a parent).
 */
class FolderNode(
    val path: String,
    val label: String,
    val ownSongCount: Int,
    val children: List<FolderNode>,
) {
    /** Every folder at or below this node that directly contains songs. Drives the tri-state checkbox. */
    val songFolders: List<String> by lazy {
        buildList {
            if (ownSongCount > 0) add(path)
            children.forEach { addAll(it.songFolders) }
        }
    }

    /** Songs in this folder and all of its subfolders. */
    val totalSongCount: Int by lazy { ownSongCount + children.sumOf { it.totalSongCount } }

    override fun toString(): String = "FolderNode($path, label=$label, own=$ownSongCount, children=$children)"
}

/**
 * Builds the folder tree shown by the picker from "song folder -> number of songs" counts.
 *
 * Folders are grouped by parent. Intermediate folders without songs are created as needed (so "/Music/A" and
 * "/Music/B" end up under a "/Music" parent), and any folder without songs that has exactly one subfolder is
 * merged into it, so long single-child chains like "/storage/emulated/0/Music" become one node instead of four
 * levels the user has to expand. Children are sorted case-insensitively by label.
 */
fun buildFolderTree(songCounts: Map<String, Int>): List<FolderNode> {
    class Mutable(val path: String, val segment: String) {
        var songs = 0
        val children = LinkedHashMap<String, Mutable>()
    }

    val root = Mutable(path = "", segment = "")
    songCounts.forEach { (rawPath, count) ->
        val path = normalizeFolder(rawPath)
        if (path.isEmpty()) return@forEach
        var node = root
        // "/a/b".split("/") = ["", "a", "b"]: the leading "" stands for the filesystem root, so child paths are
        // rebuilt by joining with "/" and absolute paths keep their leading slash.
        path.split("/").forEachIndexed { index, segment ->
            val childPath = if (index == 0) segment else node.path + "/" + segment
            node = node.children.getOrPut(segment) { Mutable(childPath, segment) }
        }
        node.songs += count
    }

    fun freeze(node: Mutable, labelPrefix: String): FolderNode {
        val label = labelPrefix + node.segment
        // Collapse a song-less folder with a single subfolder into that subfolder.
        if (node.songs == 0 && node.children.size == 1) {
            return freeze(node.children.values.single(), "$label/")
        }
        val children = node.children.values
            .map { freeze(it, "") }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        return FolderNode(node.path, label, node.songs, children)
    }

    return root.children.values
        .map { freeze(it, "") }
        // The filesystem root ("") only survives collapsing when songs sit in several top-level directories.
        // A node with an empty path can't carry a filter rule, so show its children as the roots instead.
        .flatMap { if (it.path.isEmpty()) it.children else listOf(it) }
        // Roots are labelled with their full path (e.g. "/storage/emulated/0/Music"), not just the last segment.
        .map { FolderNode(it.path, it.path, it.ownSongCount, it.children) }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.path })
}

/** All nodes of the tree, parents before their children (depth-first). */
fun flattenFolderTree(roots: List<FolderNode>): List<FolderNode> = buildList {
    fun walk(node: FolderNode) {
        add(node)
        node.children.forEach(::walk)
    }
    roots.forEach(::walk)
}
