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

    /** Pull "feat./ft." artists out of a title, returning the cleaned title and the extracted names. */
    private fun extractFeatured(title: String): Pair<String, List<String>> {
        val m = TextMatch.featRegex.find(title) ?: return title to emptyList()
        val names = m.groupValues[1]
            .split(Regex("""\s*(?:,|&|x|and|\+)\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val cleaned = title.removeRange(m.range).trim().trim('-', '|', '/').trim()
        return (cleaned.ifBlank { title }) to names
    }

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

                add(candidate(artistPart, titlePart, MatchStrategy.FILENAME_TITLE_ARTIST)) // reversed
            }
            add(candidate(cleaned, null, MatchStrategy.FILENAME_TITLE_ONLY))
            val loosePlain = loosenTitle(cleaned)
            if (!loosePlain.equals(cleaned, ignoreCase = true) && loosePlain.isNotBlank())
                add(candidate(loosePlain, null, MatchStrategy.FILENAME_TITLE_ONLY))
        }

        // 1) Trust tags first when they look real.
        val title = tagTitle?.takeIf { it.isNotBlank() && it != "<unknown>" }
        val artist = tagArtist?.takeIf { it.isNotBlank() && it != "<unknown>" }
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
