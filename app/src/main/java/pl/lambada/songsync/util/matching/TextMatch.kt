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

    /**
     * A bracketed feat clause ANYWHERE in the string — "Harli Kvin (feat. AV47) 420" keeps trailing junk after
     * the clause, which the end-anchored [featRegex] can't reach. Captured group 1 = the featured names.
     */
    val parenFeatRegex = Regex("""[(\[]\s*(?:feat\.?|ft\.?|featuring)\s+([^()\[\]]+?)\s*[)\]]""", RegexOption.IGNORE_CASE)

    /**
     * Placeholder "artist" values MediaStore/rippers write when the real artist is unknown. Treating these as a
     * genuine artist poisons both the query and the scorer (a result by the real artist "disagrees" with
     * "Unknown" and gets rejected), so callers null them out before matching.
     */
    private val junkArtists = setOf(
        "unknown", "unknown artist", "<unknown>", "various artists", "va", "n/a", "not available", "artist",
    )

    /** True when [raw] is a placeholder rather than a real artist name ("Unknown", "<unknown>", …). */
    fun isJunkArtist(raw: String?): Boolean =
        raw.isNullOrBlank() || raw.trim().lowercase(Locale.ROOT) in junkArtists

    /** Leading track number like "07.", "05 - ", "1) ", "12_". */
    private val leadingTrackNo = Regex("""^\s*\d{1,2}\s*[.)\-_]\s*""")

    /**
     * Runs of '?' or U+FFFD left where a filesystem/tagger couldn't represent the original characters
     * ("DEVITO - FLEX ????" was "FLEX" plus emoji/Cyrillic). They poison provider queries; the words around
     * them are what's searchable.
     */
    private val garbledChars = Regex("""\?{2,}|�+""")

    /**
     * YouTube channel-name decorations that are not part of the real artist ("WAV3POP - Topic",
     * "Crni Cerak TV", "La Kojot Official"). Only used to derive an EXTRA artist reading — the raw value
     * always stays as a candidate too, so a real artist that happens to end in one of these loses nothing.
     */
    private val channelSuffix = Regex(
        """\s*(?:-\s*Topic|TV|Official(?:\s+(?:Music|Channel))?|Music|Records|Media)\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** Strips channel decorations off a tag artist; returns "" when nothing real remains. */
    fun stripChannelSuffix(raw: String): String {
        var s = raw.trim()
        repeat(2) { s = channelSuffix.replace(s, "").trim(' ', '-', '_') }
        return s
    }

    /**
     * Light cleanup that keeps the human-readable shape: strips noise brackets, collapses the SnapTube
     * underscore-for-punctuation convention into spaces, and trims leading track numbers.
     */
    fun cleanTitleArtist(raw: String): String {
        var s = raw
        s = noiseBrackets.replace(s, " ")
        s = leadingTrackNo.replace(s, "")
        s = garbledChars.replace(s, " ")
        // Double quotes around a title ('Summer Cem - "TMM TMM"') defeat provider search — drop them.
        // Apostrophes are kept: they are part of words ("don't").
        s = s.replace(Regex("""["“”„«»]"""), " ")
        s = s.replace('_', ' ')
        s = s.replace(Regex("""[\s]+"""), " ").trim()
        // Drop dangling separators/brackets left behind by removals.
        s = s.trim(' ', '-', '|', '/', '(', ')', '[', ']', '.', ',')
        return s.trim()
    }

    /**
     * Serbian/Russian Cyrillic -> Latin, aligned with what the accent-strip does to Latin diacritics
     * (ж->z like ž->z, ш->s like š->s), so "Секси" compares equal to "Seksi" and a Cyrillic-indexed
     * provider entry stops looking like a stranger to a Latin-tagged file.
     */
    private val cyrillicMap = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'ђ' to "dj", 'е' to "e", 'ё' to "e",
        'ж' to "z", 'з' to "z", 'и' to "i", 'й' to "j", 'ј' to "j", 'к' to "k", 'л' to "l", 'љ' to "lj",
        'м' to "m", 'н' to "n", 'њ' to "nj", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'ћ' to "c", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "c", 'ч' to "c", 'џ' to "dz", 'ш' to "s",
        'щ' to "sh", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "ju", 'я' to "ja",
    )

    private fun transliterateCyrillic(s: String): String =
        if (s.none { it in 'Ѐ'..'ӿ' }) s
        else buildString { for (c in s) append(cyrillicMap[c] ?: c.toString()) }

    /** Aggressive normalization used only for *comparison* (not for display or queries). */
    fun normalizeForCompare(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.lowercase(Locale.ROOT)
        s = transliterateCyrillic(s)
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
     * Whole-phrase containment on token boundaries, not arbitrary substrings inside a word.
     * Very short one-word titles (e.g. "Au", "Every") are deliberately NOT granted the big containment bonus,
     * otherwise they falsely look almost exact inside a different longer title ("Audubon", "Every Dog Has His
     * Day") and can beat the correct track. Exact equality is still handled earlier in [similarity].
     */
    private fun containsPhrase(longer: String, shorter: String): Boolean {
        if (longer.isEmpty() || shorter.isEmpty()) return false
        val shorterTokens = shorter.split(' ').filter { it.isNotBlank() }
        if (shorterTokens.size < 2 && shorter.length < 7) return false
        return (" $longer ").contains(" $shorter ")
    }

    /**
     * Combined similarity in [0,1] between two free-text fields (titles or artists). Normalizes both, then
     * blends edit-distance ratio with token overlap and rewards full phrase containment (e.g. "blinding lights"
     * inside "blinding lights remix"). The containment bonus is intentionally word-boundary based, so a very
     * short unrelated title like "Au" does NOT get promoted just because it is a substring of "Audubon".
     */
    fun similarity(a: String?, b: String?): Double {
        val na = normalizeForCompare(a)
        val nb = normalizeForCompare(b)
        if (na.isEmpty() || nb.isEmpty()) return 0.0
        if (na == nb) return 1.0
        val containment = if (containsPhrase(na, nb) || containsPhrase(nb, na)) 0.92 else 0.0
        val blended = 0.6 * levRatio(na, nb) + 0.4 * tokenOverlap(na, nb)
        return maxOf(blended, containment)
    }
}
