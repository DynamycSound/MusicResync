package pl.lambada.songsync.util

/** One timed line of synced lyrics. */
data class LyricLine(val timeMs: Long, val text: String)

private val lineTimestamp = Regex("""\[(\d{1,2}):(\d{2})[.:](\d{1,3})]""")

/** Enhanced-LRC word timestamps ("<00:40.490>word") — line-level display must not show them as text. */
private val wordTimestamp = Regex("""<(\d{1,2}):(\d{2})[.:](\d{1,3})>""")

/** Any "[tag:...]" header or timestamp, for turning lyrics into readable plain text. */
private val anyBracketTag = Regex("""\[[^\]]*]""")

private fun toMs(mm: String, ss: String, frac: String): Long =
    mm.toLong() * 60_000 + ss.toLong() * 1_000 + (frac.padEnd(3, '0').take(3)).toLong()

/**
 * Parses synced (LRC) lyrics into time-sorted lines. A line with several timestamps ("[00:10.00][01:20.00]chorus")
 * yields one entry per timestamp. Header tags ([ar:], [offset:]…) and plain lines are skipped.
 */
fun parseSyncedLrc(raw: String): List<LyricLine> {
    val out = ArrayList<LyricLine>()
    for (line in raw.lineSequence()) {
        val matches = lineTimestamp.findAll(line).toList()
        if (matches.isEmpty()) {
            // Word-by-word files may carry ONLY inline "<mm:ss.xx>" stamps on a line: use the first as the
            // line time so these files still play instead of parsing to nothing.
            val word = wordTimestamp.find(line) ?: continue
            val (mm, ss, frac) = word.destructured
            val text = wordTimestamp.replace(line, " ").replace(Regex("""\s+"""), " ").trim()
            out.add(LyricLine(toMs(mm, ss, frac), text))
            continue
        }
        // Strip inline word stamps from the display text — they belong to the timing model, not the lyrics
        // (they were being shown raw: "<00:40.490>I <00:40.666>was ...").
        val text = wordTimestamp.replace(line.substring(matches.last().range.last + 1), "")
            .replace(Regex("""\s+"""), " ").trim()
        for (m in matches) {
            val (mm, ss, frac) = m.destructured
            out.add(LyricLine(toMs(mm, ss, frac), text))
        }
    }
    return out.sortedBy { it.timeMs }
}

/** Lyrics text without any LRC tags or word stamps, for showing unsynced lyrics. */
fun lrcToPlainText(raw: String): String =
    raw.lineSequence()
        .map { wordTimestamp.replace(anyBracketTag.replace(it, ""), "").replace(Regex("""\s+"""), " ").trim() }
        .joinToString("\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

/** Index of the line being sung at [positionMs] (the last line that has started), or -1 before the first line. */
fun currentLineIndex(lines: List<LyricLine>, positionMs: Long): Int {
    if (lines.isEmpty() || positionMs < lines.first().timeMs) return -1
    var lo = 0
    var hi = lines.size - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (lines[mid].timeMs <= positionMs) lo = mid else hi = mid - 1
    }
    return lo
}
