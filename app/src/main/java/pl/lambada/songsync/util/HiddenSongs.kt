package pl.lambada.songsync.util

/**
 * Pure helpers for the per-song "hide from list" feature (issue #18). Hidden songs are identified by their file
 * path and persisted as a DataStore string set (so paths containing commas are safe — no joining/splitting).
 * Generic over the item type so the logic is unit-testable without constructing Android-backed [Song]s.
 */
object HiddenSongs {

    /**
     * Drops every item whose path is in [hidden]. Returns [items] itself when nothing is hidden, so users who never
     * use the feature get the exact same list instance (no extra work, no behavior change).
     */
    fun <T> exclude(items: List<T>, hidden: Set<String>, pathOf: (T) -> String?): List<T> =
        if (hidden.isEmpty()) items else items.filter { pathOf(it) !in hidden }

    /** [current] plus [paths], ignoring blanks. */
    fun hide(current: Set<String>, paths: Collection<String>): Set<String> =
        current + paths.filter { it.isNotBlank() }

    /** [current] minus [paths]. */
    fun unhide(current: Set<String>, paths: Collection<String>): Set<String> = current - paths.toSet()

    /**
     * Pairs every hidden path with its library item (null when the file is no longer in the library, e.g. it was
     * deleted or moved — it can still be unhidden to clean up the entry), ordered by path so songs from the same
     * folder sit together.
     */
    fun <T> resolve(hidden: Set<String>, library: List<T>, pathOf: (T) -> String?): List<Pair<String, T?>> {
        if (hidden.isEmpty()) return emptyList()
        val byPath = library.filter { pathOf(it) in hidden }.associateBy { pathOf(it) }
        return hidden.sorted().map { it to byPath[it] }
    }
}
