package pl.lambada.songsync.util.matching

import java.io.File

/** How a query candidate was derived — drives the fallback ladder ordering and logging. */
enum class MatchStrategy(val label: String) {
    TAGS("metadata tags"),
    FILENAME_ARTIST_TITLE("filename: Artist - Title"),
    FILENAME_PRIMARY_ARTIST("filename: primary artist"),
    FILENAME_TITLE_ARTIST("filename: Title - Artist"),
    FILENAME_LOOSE("filename: loosened"),
    FILENAME_TITLE_ONLY("filename: title only"),
}

/**
 * One (artist?, title) guess plus the [strategy] that produced it. [featuredArtists] are pulled out of the
 * title so the title query is clean while the artists can still inform the artist similarity score.
 */
data class QueryCandidate(
    val title: String,
    val artist: String?,
    val strategy: MatchStrategy,
    val featuredArtists: List<String> = emptyList()
) {
    /** A readable "query" string for providers that take a single free-text field. */
    fun asSearchString(): String = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title).joinToString(" ").trim()
}

/**
 * Turns whatever we know about a local file (possibly-wrong tags + messy filename) into an ordered list of
 * query candidates, best-guess first. This is the heart of the "bad metadata" handling: when tags are wrong
 * or missing, the filename is parsed several different ways and all are tried.
 */
object FilenameParser {

    /** Collaboration separators on the artist side: " x ", " × ", " & ", ", ", "feat", "ft", "vs". */
    private val collabSeparator = Regex(
        """\s+(?:x|×|&|vs\.?|feat\.?|ft\.?|featuring)\s+|,\s+""",
        RegexOption.IGNORE_CASE
    )

    /** Trailing version markers we strip for a looser query: "(... Remix)", "(... Version)", "(... Edit)", etc. */
    private val versionSuffix = Regex(
        """\s*[(\[][^()\[\]]*\b(?:remix|version|edit|bootleg|flip|mix|cover|live|acoustic|instrumental|sped\s*up|slowed)\b[^()\[\]]*[)\]]\s*$""",
        RegexOption.IGNORE_CASE
    )

    /** First/primary artist from a collaboration string ("$uicideboy$ x Travis Barker" -> "$uicideboy$"). */
    private fun primaryArtist(artist: String): String =
        collabSeparator.split(artist).firstOrNull { it.isNotBlank() }?.trim() ?: artist

    private fun loosenTitle(title: String): String = versionSuffix.replace(title, "").trim()

    /** Lowercased alphanumerics only — collapses "$uicideboy$"/"_UICIDEBOY_" to the single token "uicideboy". */
    private fun collapse(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** Splits a captured feat-clause value into individual artist names. */
    private fun splitFeaturedNames(value: String): List<String> = value
        .split(Regex("""\s*(?:,|&|x|and|\+)\s*""", RegexOption.IGNORE_CASE))
        .map { it.trim() }
        .filter { it.isNotBlank() }

    /**
     * Pull "feat./ft." artists out of a title, returning the cleaned title and the extracted names. Handles
     * both a bracketed clause anywhere in the string ("Harli Kvin (feat. AV47) 420" — trailing junk after the
     * clause used to defeat the end-anchored regex) and an unbracketed clause at the end.
     */
    private fun extractFeatured(title: String): Pair<String, List<String>> {
        val names = mutableListOf<String>()
        var cleaned = TextMatch.parenFeatRegex.replace(title) { m ->
            names += splitFeaturedNames(m.groupValues[1]); " "
        }
        TextMatch.featRegex.find(cleaned)?.let { m ->
            names += splitFeaturedNames(m.groupValues[1])
            cleaned = cleaned.removeRange(m.range)
        }
        cleaned = cleaned.replace(Regex("""\s+"""), " ").trim().trim('-', '|', '/').trim()
        if (names.isEmpty()) return title to emptyList()
        return (cleaned.ifBlank { title }) to names
    }

    /** Trailing orphan number left behind by SnapTube-style "_420" suffixes ("Harli Kvin 420" -> "Harli Kvin"). */
    private val trailingJunkNumber = Regex("""\s+\d{2,4}$""")

    private fun stripTrailingJunkNumber(s: String): String = trailingJunkNumber.replace(s, "").trim()

    private fun candidate(title: String, artist: String?, strategy: MatchStrategy): QueryCandidate? {
        val cleanTitle = TextMatch.cleanTitleArtist(title)
        if (cleanTitle.isBlank()) return null
        val (finalTitle, titleFeat) = extractFeatured(cleanTitle)
        // The "feat." can sit in either half ("Drake ft. 21 Savage - Jimmy Cooks"); strip it from the artist
        // too so the artist query is the primary act, and keep the featured names for scoring.
        val (finalArtist, artistFeat) = artist?.let { TextMatch.cleanTitleArtist(it) }
            ?.takeIf { it.isNotBlank() }
            ?.let { extractFeatured(it) } ?: (null to emptyList())
        return QueryCandidate(finalTitle, finalArtist?.takeIf { it.isNotBlank() }, strategy, titleFeat + artistFeat)
    }

    /**
     * @param tagTitle title from MediaStore/ID3 (null or "<unknown>" if absent)
     * @param tagArtist artist from tags
     * @param filePath full path; only the base filename is parsed
     */
    fun candidates(tagTitle: String?, tagArtist: String?, filePath: String?): List<QueryCandidate> {
        val out = LinkedHashMap<String, QueryCandidate>() // de-dupe by normalized signature, keep first/best

        fun add(c: QueryCandidate?) {
            if (c == null) return
            val key = TextMatch.normalizeForCompare(c.title) + "|" + TextMatch.normalizeForCompare(c.artist)
            out.putIfAbsent(key, c)
        }

        // Splits a "Artist - Title" string into the family of candidates (artist-title, primary-artist,
        // remix-loosened, reversed, plain title). Used for BOTH the filename and a tag-title that looks like
        // "Artist - Title" -- the latter fixes dirty tags such as title="$UICIDEBOY$ - Audubon" / artist=Unknown,
        // which otherwise only matched a mislabeled upload that copied the whole string into its track name.
        fun addDashCandidates(raw: String) {
            val cleaned = TextMatch.cleanTitleArtist(raw)
            val dashParts = cleaned.split(Regex("""\s+-\s+"""), limit = 2).map { it.trim() }.filter { it.isNotBlank() }
            if (dashParts.size == 2) {
                val (artistPart, titlePart) = dashParts[0] to dashParts[1]
                add(candidate(titlePart, artistPart, MatchStrategy.FILENAME_ARTIST_TITLE)) // exact (keeps remix tag)

                val primary = primaryArtist(artistPart)
                if (!primary.equals(artistPart, ignoreCase = true))
                    add(candidate(titlePart, primary, MatchStrategy.FILENAME_PRIMARY_ARTIST))

                // Loosened: drop "(... Remix)/(... Version)" so the BASE track can be found as a fallback.
                val loose = loosenTitle(titlePart)
                if (!loose.equals(titlePart, ignoreCase = true) && loose.isNotBlank())
                    add(candidate(loose, primary, MatchStrategy.FILENAME_LOOSE))

                // A trailing orphan number is usually ripper junk (an underscore suffix like "_420" turned into
                // " 420" by the cleanup). Try the title without it — as a LOOSE fallback, so a genuine numeric
                // title still gets its exact query first and a stripped-title match stays review-grade.
                val noJunkNo = stripTrailingJunkNumber(titlePart)
                if (!noJunkNo.equals(titlePart, ignoreCase = true) && noJunkNo.isNotBlank())
                    add(candidate(noJunkNo, artistPart, MatchStrategy.FILENAME_LOOSE))

                // Collapsed artist (alphanumerics only, no spaces) for stylized names whose decorations get
                // mangled on disk: "_UICIDEBOY_" / "$uicideboy$" -> "uicideboy", "P!nk" -> "pnk", "deadmau5".
                // LRCLib indexes these as one token, so a spaced/decorated query misses them entirely.
                val collapsed = collapse(artistPart)
                if (collapsed.length >= 3 && collapsed != artistPart.lowercase())
                    add(candidate(titlePart, collapsed, MatchStrategy.FILENAME_PRIMARY_ARTIST))

                add(candidate(artistPart, titlePart, MatchStrategy.FILENAME_TITLE_ARTIST)) // reversed

                // Pure title-only ("Loot"): lets the provider's title search + duration tiebreak find the track
                // when the artist string can't be matched at all.
                add(candidate(titlePart, null, MatchStrategy.FILENAME_TITLE_ONLY))
            }
            add(candidate(cleaned, null, MatchStrategy.FILENAME_TITLE_ONLY))
            val loosePlain = loosenTitle(cleaned)
            if (!loosePlain.equals(cleaned, ignoreCase = true) && loosePlain.isNotBlank())
                add(candidate(loosePlain, null, MatchStrategy.FILENAME_TITLE_ONLY))
            val noJunkPlain = stripTrailingJunkNumber(cleaned)
            if (!noJunkPlain.equals(cleaned, ignoreCase = true) && noJunkPlain.isNotBlank())
                add(candidate(noJunkPlain, null, MatchStrategy.FILENAME_TITLE_ONLY))
        }

        // 1) Trust tags first when they look real. Placeholder artists ("Unknown", "Various Artists"…) are
        // treated as absent — querying and scoring against them only hurts (a result by the real artist would
        // "disagree" with the placeholder and be rejected).
        val title = tagTitle?.takeIf { it.isNotBlank() && it != "<unknown>" }
        val artist = tagArtist?.takeIf { !TextMatch.isJunkArtist(it) }
        if (title != null) {
            add(candidate(title, artist, MatchStrategy.TAGS))
            // If the tag title itself is "Artist - Title", split it too.
            if (title.contains(Regex("""\s+-\s+"""))) addDashCandidates(title)
        }

        // 2) Parse the filename several ways.
        val base = filePath?.let { File(it).nameWithoutExtension }
        if (base != null) addDashCandidates(base)

        return out.values.toList()
    }
}
