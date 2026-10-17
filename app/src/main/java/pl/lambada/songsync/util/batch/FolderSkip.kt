package pl.lambada.songsync.util.batch

/**
 * Pure helpers behind "Skip this folder" on the batch screen (issue #22). No Android types, so they are
 * unit-testable on the JVM.
 *
 * A song's folder is its parent directory, derived the same way the Home folder filter / blacklist derives it
 * (everything before the last '/'). A folder skipped here and a folder hidden through the blacklist therefore
 * always mean the same set of songs, which is what makes the "Also hide this folder" checkbox honest.
 *
 * Matching is the folder ITSELF only, never its subfolders. The blacklist is exact-match as well, so including
 * subfolders here would make "skip" and "hide" disagree, and the dialog names and counts one folder. Comparing
 * whole parent paths (instead of `startsWith`) also avoids the prefix trap where skipping `/Music` would catch
 * `/Music2/song.mp3`.
 */
object FolderSkip {

    /** Parent folder of [filePath] without a trailing slash, or null when there is none to speak of. */
    fun folderOf(filePath: String?): String? {
        if (filePath.isNullOrEmpty()) return null
        val slash = filePath.lastIndexOf('/')
        if (slash <= 0) return null
        return normalize(filePath.substring(0, slash))
    }

    /** "/storage/Music/" and "/storage/Music" are the same folder. */
    fun normalize(folder: String): String = folder.trimEnd('/')

    /** True when the song at [filePath] sits directly in [folder] (not in a subfolder of it). */
    fun isInFolder(filePath: String?, folder: String): Boolean {
        val target = normalize(folder)
        if (target.isEmpty()) return false
        return folderOf(filePath) == target
    }

    /** True when the song at [filePath] sits directly in one of [skippedFolders] (already normalized). */
    fun isSkipped(filePath: String?, skippedFolders: Set<String>): Boolean {
        if (skippedFolders.isEmpty()) return false
        val folder = folderOf(filePath) ?: return false
        return folder in skippedFolders
    }

    /**
     * How many songs of [folder] are still waiting in the run's queue: those at index [fromIndex] or later.
     * The in-flight song is before [fromIndex], so it is not counted (it finishes normally).
     */
    fun remainingInFolder(queuePaths: List<String?>, fromIndex: Int, folder: String): Int {
        val start = fromIndex.coerceAtLeast(0)
        if (start >= queuePaths.size) return 0
        var count = 0
        for (i in start until queuePaths.size) {
            if (isInFolder(queuePaths[i], folder)) count++
        }
        return count
    }

    /** Last path segment, for showing the folder in the UI ("/storage/emulated/0/Music/OST" -> "OST"). */
    fun displayName(folder: String): String {
        val normalized = normalize(folder)
        return normalized.substringAfterLast('/').ifEmpty { normalized }
    }
}
