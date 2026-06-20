package pl.lambada.songsync.util.matching

import java.util.Locale

/**
 * String normalization and fuzzy-similarity helpers shared by the filename parser and the confidence scorer.
 * Pure Kotlin (no Android deps) so it is unit-testable on the JVM.
 */
object TextMatch {

    /** Noise tokens commonly appended by SnapTube/YouTube rips that hurt lyric matching. */
    private val noiseBrackets = Regex(
        """[(\[]\s*(?:""" +
            "mp3[_ ]?\\d{2,3}k?|" +
            "\\d{2,3}\\s*kbps|" +
            "\\d{3,4}p?|" +
            "official\\s*(?:music\\s*)?(?:video|audio|lyric\\s*video|visualizer)?|" +
            "[^)\\]]*\\bvideo\\b[^)\\]]*|" + // any "(... Video)": "Drift Music Video", "WSHH ... Video", etc.
            "lyric[s]?\\s*video|lyric[s]?|" +
            "video\\s*oficial|audio|visualizer|" +
            "hd|hq|4k|remaster(?:ed)?(?:\\s*\\d{4})?|explicit(?:\\s*version)?|" +
            "closed\\s*captioned|wshh\\s*exclusive|getovarijante|" +
            "prod\\.?[^)\\]]*" +
            """)\s*[)\]]""",
        RegexOption.IGNORE_CASE
    )

    /** "feat. X", "ft. X", "featuring X" up to a closing bracket or end. Captured group 1 = the featured names. */
    val featRegex = Regex("""[\s(\[]*(?:feat\.?|ft\.?|featuring)\s+([^()\[\]]+?)\s*[)\]]?$""", RegexOption.IGNORE_CASE)

    /** Leading track number like "07.", "05 - ", "1) ", "12_". */
    private val leadingTrackNo = Regex("""^\s*\d{1,2}\s*[.)\-_]\s*""")

    /**
     * Light cleanup that keeps the human-readable shape: strips noise brackets, collapses the SnapTube
     * underscore-for-punctuation convention into spaces, and trims leading track numbers.
     */
    fun cleanTitleArtist(raw: String): String {
        var s = raw
        s = noiseBrackets.replace(s, " ")
        s = leadingTrackNo.replace(s, "")
        s = s.replace('_', ' ')
        s = s.replace(Regex("""[\s]+"""), " ").trim()
        // Drop dangling separators/brackets left behind by removals.
        s = s.trim(' ', '-', '|', '/', '(', ')', '[', ']', '.', ',')
        return s.trim()
    }

    /** Aggressive normalization used only for *comparison* (not for display or queries). */
    fun normalizeForCompare(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.lowercase(Locale.ROOT)
        s = noiseBrackets.replace(s, " ")
        s = s.replace('_', ' ')
        s = featRegex.replace(s, " ")
        // strip accents
        s = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        // keep alphanumerics and spaces only
        s = s.replace(Regex("""[^a-z0-9 ]"""), " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        return s
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /** Levenshtein ratio in [0,1] on the raw (already-normalized) strings. */
    private fun levRatio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    /** Jaccard overlap of word tokens — robust to word reordering / extra words. */
    private fun tokenOverlap(a: String, b: String): Double {
        val ta = a.split(' ').filter { it.isNotBlank() }.toSet()
        val tb = b.split(' ').filter { it.isNotBlank() }.toSet()
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.intersect(tb).size.toDouble()
        return inter / ta.union(tb).size.toDouble()
    }

    /**
     * Combined similarity in [0,1] between two free-text fields (titles or artists). Normalizes both, then
     * blends edit-distance ratio with token overlap and rewards full containment (e.g. "blinding lights"
     * inside "blinding lights (remix)").
     */
    fun similarity(a: String?, b: String?): Double {
        val na = normalizeForCompare(a)
        val nb = normalizeForCompare(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val containment = if (na.contains(nb) || nb.contains(na)) 0.92 else 0.0
        val blended = 0.6 * levRatio(na, nb) + 0.4 * tokenOverlap(na, nb)
        return maxOf(blended, containment)
    }
}
