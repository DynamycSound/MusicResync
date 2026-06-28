package pl.lambada.songsync.util

/**
 * Pure helpers that reconcile the two LRC timing models the app has to deal with:
 *
 *  - **tag mode**: an `[offset:N]` metadata line (ms). Timestamps are left untouched; a compliant player adds
 *    N at runtime. This is what [generateLrcContent] writes when `directlyModifyTimestamps == false`.
 *  - **direct-shift mode**: no offset tag — the shift is baked straight into every `[mm:ss.xx]` timestamp
 *    (via [applyOffsetToLyrics]). This is what it writes when `directlyModifyTimestamps == true`.
 *
 * The player previously mixed the two (it always baked a delta into the timestamps yet never removed/updated the
 * `[offset:...]` tag, and the preview/slider ignored the tag), so a reopened file could be off by the tag amount
 * or double-shifted. To make all the preview/seek/save math assume a single model, the player normalises either
 * kind into a **neutral** LRC — zero applied offset, no offset tag — via [buildNeutralLrc], applies the user's
 * absolute offset on top, and writes back in whichever model the setting selects.
 */

/** Matches a single `[offset:N]` metadata line (case-insensitive), capturing the signed millisecond value. */
private val OFFSET_TAG = Regex("""(?im)^[ \t]*\[offset:[ \t]*([+-]?\d+)[ \t]*][ \t]*\r?\n?""")

/** Matches a leading metadata tag line such as `[ti:...]`, `[ar:...]`, `[by:...]` (letter-led, not a timestamp). */
private val HEADER_TAG = Regex("""^[ \t]*\[[A-Za-z]+:.*]\s*$""")

/** Reads the `[offset:N]` tag (milliseconds) from [raw], or 0 when absent/unparseable. The first tag wins. */
fun parseOffsetTagMs(raw: String): Int {
    val m = OFFSET_TAG.find(raw) ?: return 0
    return m.groupValues[1].toIntOrNull() ?: 0
}

/** Removes every `[offset:N]` tag line, leaving all other content (timestamps, `[ti:]`, `[ar:]`, …) intact. */
fun stripOffsetTag(raw: String): String = raw.replace(OFFSET_TAG, "")

/**
 * Returns [rawWithoutOffset] carrying exactly one `[offset:offsetMs]` tag. Any pre-existing offset tags are
 * removed first (so repeated calls don't accumulate tags), then the new tag is inserted after the run of leading
 * metadata tags (`[ti:]`, `[ar:]`, …) — or at the very top when there are none — so it sits with the header
 * rather than in the middle of the lyrics.
 */
fun upsertOffsetTag(rawWithoutOffset: String, offsetMs: Int): String {
    val stripped = stripOffsetTag(rawWithoutOffset)
    val sign = if (offsetMs >= 0) "+" else ""
    val tag = "[offset:$sign$offsetMs]"

    val lines = stripped.split("\n").toMutableList()
    var insertAt = 0
    while (insertAt < lines.size && HEADER_TAG.matches(lines[insertAt])) insertAt++
    lines.add(insertAt, tag)
    return lines.joinToString("\n")
}

/**
 * Normalises [raw] into a **neutral** LRC: zero applied offset and no `[offset:]` tag.
 *
 * @param raw the on-disk LRC text.
 * @param baseAppliedOffsetMs the offset currently considered "applied" to this file (the file's own tag value if
 *        it has one, otherwise the cached/remembered offset).
 * @param fileUsesOffsetTag true when the file carries an `[offset:]` tag (tag mode), false for direct-shift mode.
 *
 * Tag-mode files already have neutral timestamps, so we only strip the tag. Direct-shift files have the offset
 * baked into the timestamps, so we undo it with `applyOffsetToLyrics(..., -baseAppliedOffsetMs)`. When there is
 * no offset at all the result is just the (tag-stripped) input.
 */
fun buildNeutralLrc(raw: String, baseAppliedOffsetMs: Int, fileUsesOffsetTag: Boolean): String {
    val stripped = stripOffsetTag(raw)
    return when {
        fileUsesOffsetTag -> stripped                                   // timestamps already neutral
        baseAppliedOffsetMs != 0 -> applyOffsetToLyrics(stripped, -baseAppliedOffsetMs) // undo baked shift
        else -> stripped
    }
}
