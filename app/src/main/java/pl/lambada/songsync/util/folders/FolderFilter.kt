package pl.lambada.songsync.util.folders

/** How folders the user never touched (e.g. ones added after the filter was set up) are treated. Issue #21. */
enum class FolderFilterMode(val key: String) {
    /** Default, and the only behavior before #21: every folder is shown except the ones the user unchecked. */
    SHOW_ALL_EXCEPT_UNCHECKED("all_except_unchecked"),

    /**
     * Whitelist: only folders inside a checked folder are shown. Checking a parent includes all of its subfolders,
     * including ones added later; new folders anywhere else stay hidden.
     */
    ONLY_CHECKED("only_checked");

    companion object {
        /** Unknown or missing values fall back to the default, so existing installs keep their behavior. */
        fun fromKey(key: String?): FolderFilterMode = entries.find { it.key == key } ?: SHOW_ALL_EXCEPT_UNCHECKED
    }
}

/** Normalizes a folder path for comparisons: "/Music/" and "/Music" are the same folder. */
fun normalizeFolder(path: String): String = path.trimEnd('/')

/** The folder a song file lives in ("/a/b/song.mp3" -> "/a/b"). */
fun folderOf(filePath: String): String = filePath.substring(0, filePath.lastIndexOf('/').coerceAtLeast(0))

/**
 * True if [path] is [ancestor] itself or lies anywhere below it. Segment-aware, so "/Music2" is NOT under "/Music".
 * Both arguments must already be normalized. An empty ancestor matches nothing.
 */
fun isAtOrUnder(path: String, ancestor: String): Boolean =
    ancestor.isNotEmpty() && (path == ancestor || path.startsWith("$ancestor/"))

/**
 * The folder filter: which song folders are visible on the home list (and therefore in batch downloads).
 *
 * Data model:
 * - [hidden]: exact folders the user hid. This is the pre-#21 blacklist, unchanged: it matches a folder exactly
 *   (not its subfolders) and applies in BOTH modes, so "hide this folder" (updateBlacklistedFolders) always works.
 * - [included] / [excluded]: whitelist rules, used only in [FolderFilterMode.ONLY_CHECKED]. Both apply to the
 *   folder and everything below it; the deepest matching rule wins and a folder with no matching rule is hidden.
 *   So checking "/Music" shows every current and future folder under it, unchecking "/Music/Podcasts" inside it
 *   hides that whole branch (again including future subfolders), and anything outside "/Music" stays hidden.
 *
 * All paths are kept normalized (see [normalizeFolder]); build instances with [create].
 */
data class FolderFilter(
    val mode: FolderFilterMode = FolderFilterMode.SHOW_ALL_EXCEPT_UNCHECKED,
    val hidden: Set<String> = emptySet(),
    val included: Set<String> = emptySet(),
    val excluded: Set<String> = emptySet(),
) {
    /** False when nothing can be hidden, so the song list can skip filtering entirely. */
    val isActive: Boolean
        get() = mode == FolderFilterMode.ONLY_CHECKED || hidden.isNotEmpty()

    fun isVisible(folder: String): Boolean {
        val f = normalizeFolder(folder)
        if (f in hidden) return false
        return mode == FolderFilterMode.SHOW_ALL_EXCEPT_UNCHECKED || rulesInclude(f)
    }

    /** Songs without a path can't be placed in a folder, so they're never filtered out (pre-#21 behavior). */
    fun isSongVisible(filePath: String?): Boolean = filePath == null || isVisible(folderOf(filePath))

    /** Whitelist rule lookup: the deepest included/excluded ancestor decides; exclude wins a tie; no rule = hidden. */
    private fun rulesInclude(folder: String): Boolean {
        val deepestInclude = included.filter { isAtOrUnder(folder, it) }.maxOfOrNull { it.length } ?: -1
        val deepestExclude = excluded.filter { isAtOrUnder(folder, it) }.maxOfOrNull { it.length } ?: -1
        return deepestInclude > deepestExclude
    }

    /**
     * Checks ([visible] = true) or unchecks a folder in the picker, together with everything below it.
     *
     * @param knownFolders the song folders currently in the library; in "show all except unchecked" mode the
     * hidden list is exact-match, so unchecking a parent has to list its current subfolders explicitly.
     */
    fun withFolderVisible(folder: String, visible: Boolean, knownFolders: Collection<String>): FolderFilter {
        val target = normalizeFolder(folder)
        if (target.isEmpty()) return this
        fun Set<String>.withoutSubtree() = filterNot { isAtOrUnder(it, target) }.toSet()

        return when (mode) {
            FolderFilterMode.SHOW_ALL_EXCEPT_UNCHECKED ->
                if (visible) copy(hidden = hidden.withoutSubtree())
                else copy(
                    hidden = hidden + target +
                        knownFolders.map(::normalizeFolder).filter { isAtOrUnder(it, target) }
                )

            FolderFilterMode.ONLY_CHECKED -> {
                // Drop every rule at or below the target, then add at most one rule for the target itself, so the
                // rule set stays minimal no matter how often the user toggles. Checking also clears exact hides
                // below it (the user explicitly asked to see that branch); unchecking leaves them alone.
                val base = copy(
                    hidden = if (visible) hidden.withoutSubtree() else hidden,
                    included = included.withoutSubtree(),
                    excluded = excluded.withoutSubtree(),
                )
                val inheritedInclude = base.rulesInclude(target)
                when {
                    visible && !inheritedInclude -> base.copy(included = base.included + target)
                    !visible && inheritedInclude -> base.copy(excluded = base.excluded + target)
                    else -> base
                }
            }
        }
    }

    /**
     * "Select all" / "Select none".
     * @param roots top-level folders of the picker tree (see [buildFolderTree]); "select all" in whitelist mode
     * checks these.
     */
    fun withAllVisible(visible: Boolean, roots: Collection<String>, knownFolders: Collection<String>): FolderFilter =
        when (mode) {
            FolderFilterMode.SHOW_ALL_EXCEPT_UNCHECKED ->
                if (visible) copy(hidden = emptySet())
                else copy(hidden = hidden + knownFolders.map(::normalizeFolder))

            FolderFilterMode.ONLY_CHECKED ->
                if (visible) copy(
                    hidden = emptySet(),
                    included = roots.map(::normalizeFolder).filter { it.isNotEmpty() }.toSet(),
                    excluded = emptySet(),
                )
                else copy(included = emptySet(), excluded = emptySet())
        }

    /**
     * Switches mode WITHOUT changing which of the current folders are visible; only the handling of folders that
     * appear later changes. That way flipping the switch never makes the library suddenly vanish or reappear.
     *
     * - to [FolderFilterMode.ONLY_CHECKED]: the topmost folders with anything visible become included roots, and
     *   fully hidden branches below them become exclusions. The exact hides stay as they are (they keep applying).
     * - to [FolderFilterMode.SHOW_ALL_EXCEPT_UNCHECKED]: every current folder the whitelist hides is added to the
     *   exact hide list and the whitelist rules are cleared.
     */
    fun withMode(newMode: FolderFilterMode, roots: List<FolderNode>, knownFolders: Collection<String>): FolderFilter {
        if (newMode == mode) return this
        return when (newMode) {
            FolderFilterMode.SHOW_ALL_EXCEPT_UNCHECKED -> FolderFilter(
                mode = newMode,
                hidden = hidden + knownFolders.map(::normalizeFolder).filterNot { isVisible(it) },
            )

            FolderFilterMode.ONLY_CHECKED -> {
                val include = mutableSetOf<String>()
                val exclude = mutableSetOf<String>()
                fun walk(node: FolderNode, parentIncluded: Boolean) {
                    val anyVisible = node.songFolders.any { normalizeFolder(it) !in hidden }
                    when {
                        anyVisible -> {
                            if (!parentIncluded) include += node.path
                            node.children.forEach { walk(it, true) }
                        }
                        parentIncluded -> exclude += node.path
                    }
                }
                roots.forEach { walk(it, false) }
                copy(mode = newMode, included = include, excluded = exclude)
            }
        }
    }

    companion object {
        /** Builds a filter from stored values, normalizing paths and dropping blank entries. */
        fun create(
            mode: FolderFilterMode,
            hidden: Collection<String>,
            included: Collection<String>,
            excluded: Collection<String>,
        ): FolderFilter {
            fun Collection<String>.clean() = map(::normalizeFolder).filter { it.isNotEmpty() }.toSet()
            return FolderFilter(mode, hidden.clean(), included.clean(), excluded.clean())
        }
    }
}
