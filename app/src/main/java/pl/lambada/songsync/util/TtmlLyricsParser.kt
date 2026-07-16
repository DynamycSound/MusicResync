package pl.lambada.songsync.util

import org.w3c.dom.Element
import org.w3c.dom.Node
import pl.lambada.songsync.util.ext.toLrcTimestamp
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal TTML (Timed Text Markup Language) lyrics parser used by the BetterLyrics provider. Apple-style TTML
 * carries one `<p begin end>` per line, with optional `<span begin end>` children for word-level timing,
 * `ttm:agent` for the singer and `ttm:role="x-bg"` spans for background vocals.
 *
 * Pure Kotlin + javax.xml (present on both Android and the JVM), so it's unit-testable without an emulator.
 */
object TtmlLyricsParser {

    data class Word(val text: String, val beginMs: Long, val endMs: Long)

    /** A raw `<span>` plus whether whitespace followed it — syllable spans with no gap merge into one word. */
    private data class SpanInfo(val text: String, val beginMs: Long, val endMs: Long, val trailingSpace: Boolean)

    data class Line(
        val text: String,
        val beginMs: Long,
        val words: List<Word>,
        val agent: String?,
        val backgroundWords: List<Word>,
    )

    /** Namespace-tolerant attribute lookup: matches `agent`, `ttm:agent` or any `*:agent`. */
    private fun Element.attributeByLocalName(localName: String): String {
        getAttribute(localName).takeIf { it.isNotEmpty() }?.let { return it }
        getAttribute("ttm:$localName").takeIf { it.isNotEmpty() }?.let { return it }
        val attrs = attributes
        for (i in 0 until attrs.length) {
            val attr = attrs.item(i)
            val name = attr.nodeName ?: continue
            if (name == localName || name.endsWith(":$localName")) return attr.nodeValue.orEmpty()
        }
        return ""
    }

    fun parse(ttml: String): List<Line> = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // Defensive: never resolve external entities from a lyrics payload.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }
        val doc = factory.newDocumentBuilder().parse(ttml.byteInputStream())
        val out = mutableListOf<Line>()

        val pElements = doc.getElementsByTagName("p")
        for (i in 0 until pElements.length) {
            val p = pElements.item(i) as? Element ?: continue
            val begin = p.getAttribute("begin")
            if (begin.isNullOrEmpty()) continue
            val beginMs = parseTimeMs(begin) ?: continue

            val spans = mutableListOf<SpanInfo>()
            val bgWords = mutableListOf<Word>()
            val children = p.childNodes
            for (j in 0 until children.length) {
                val node = children.item(j)
                if (node.nodeType != Node.ELEMENT_NODE) continue
                val span = node as? Element ?: continue
                if (!span.tagName.endsWith("span", ignoreCase = true)) continue

                when (span.attributeByLocalName("role")) {
                    "x-bg" -> collectWordSpans(span, bgWords)
                    "x-translation", "x-roman" -> Unit // decoration, skip
                    else -> addWordSpan(span, spans)
                }
            }
            val words = mergeSyllables(spans)

            val text = if (words.isNotEmpty()) words.joinToString(" ") { it.text }
            else directText(p).trim()
            if (text.isEmpty() && bgWords.isEmpty()) continue

            out.add(
                Line(
                    text = text,
                    beginMs = beginMs,
                    words = words,
                    agent = p.attributeByLocalName("agent").ifEmpty { null },
                    backgroundWords = bgWords,
                )
            )
        }
        out
    }.getOrDefault(emptyList())

    private fun addWordSpan(span: Element, into: MutableList<SpanInfo>) {
        val begin = parseTimeMs(span.getAttribute("begin")) ?: return
        val end = parseTimeMs(span.getAttribute("end")) ?: return
        val text = span.textContent?.trim().orEmpty()
        if (text.isEmpty()) return
        // Whitespace between this span and the next marks a word boundary; its absence means the spans are
        // syllables of ONE word (Apple-style TTML) and must be glued back together.
        val next = span.nextSibling
        val trailingSpace = next?.nodeType == Node.TEXT_NODE && next.textContent?.any { it.isWhitespace() } == true
        into.add(SpanInfo(text, begin, end, trailingSpace))
    }

    /** Glues syllable spans (no whitespace between them) into whole words spanning first-begin..last-end. */
    private fun mergeSyllables(spans: List<SpanInfo>): List<Word> {
        val words = mutableListOf<Word>()
        var text = StringBuilder()
        var begin = 0L
        var end = 0L
        spans.forEach { span ->
            if (text.isEmpty()) {
                begin = span.beginMs
            }
            text.append(span.text)
            end = span.endMs
            if (span.trailingSpace) {
                words.add(Word(text.toString(), begin, end))
                text = StringBuilder()
            }
        }
        if (text.isNotEmpty()) words.add(Word(text.toString(), begin, end))
        return words
    }

    /** Collects the timed inner spans of a background-vocal wrapper span, merged into words. */
    private fun collectWordSpans(wrapper: Element, into: MutableList<Word>) {
        val spans = mutableListOf<SpanInfo>()
        val children = wrapper.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val span = node as? Element ?: continue
            if (!span.tagName.endsWith("span", ignoreCase = true)) continue
            val role = span.attributeByLocalName("role")
            if (role == "x-translation" || role == "x-roman") continue
            addWordSpan(span, spans)
        }
        // A background span may itself be a single timed word with no children.
        if (spans.isEmpty()) addWordSpan(wrapper, spans)
        into.addAll(mergeSyllables(spans))
    }

    /** Text directly inside the element (word spans included, bg/translation spans excluded). */
    private fun directText(element: Element): String {
        val sb = StringBuilder()
        val children = element.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            when (node.nodeType) {
                Node.TEXT_NODE -> sb.append(node.textContent)
                Node.ELEMENT_NODE -> {
                    val el = node as? Element ?: continue
                    val role = el.attributeByLocalName("role")
                    if (role != "x-bg" && role != "x-translation" && role != "x-roman") {
                        sb.append(el.textContent.orEmpty())
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * Parses a TTML time expression to milliseconds: `hh:mm:ss.fff`, `mm:ss.fff`, plain seconds (`12.34`)
     * and the `12.34s` / `1234ms` offset forms. Returns null for anything unrecognized.
     */
    fun parseTimeMs(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        return runCatching {
            when {
                s.contains(':') -> {
                    val parts = s.split(':')
                    val seconds = when (parts.size) {
                        2 -> parts[0].toDouble() * 60 + parts[1].toDouble()
                        3 -> parts[0].toDouble() * 3600 + parts[1].toDouble() * 60 + parts[2].toDouble()
                        else -> return null
                    }
                    (seconds * 1000).toLong()
                }
                s.endsWith("ms") -> s.dropLast(2).toDouble().toLong()
                s.endsWith("s") -> (s.dropLast(1).toDouble() * 1000).toLong()
                else -> (s.toDouble() * 1000).toLong()
            }
        }.getOrNull()
    }

    /**
     * Formats parsed lines as the app's LRC dialect (same shape as the Apple/QQ output in
     * [pl.lambada.songsync.data.remote.PaxMusicHelper]): `[mm:ss.SSS]` line stamps with `<begin>word <end>`
     * inline timing when word-level data exists, `v1:`/`v2:` voice prefixes for multi-singer songs, and
     * `[bg:...]` background blocks — both only when [multiPersonWordByWord] is on.
     */
    fun toLrc(lines: List<Line>, multiPersonWordByWord: Boolean = false): String? {
        if (lines.isEmpty()) return null
        val agents = lines.mapNotNull { it.agent }.distinct()
        val tagVoices = multiPersonWordByWord && agents.size > 1
        val primaryAgent = agents.firstOrNull()

        val sb = StringBuilder(lines.size * 64)
        for (line in lines) {
            if (line.text.isNotBlank() || line.words.isNotEmpty()) {
                sb.append("[${line.beginMs.toInt().toLrcTimestamp()}]")
                if (tagVoices) sb.append(if (line.agent == null || line.agent == primaryAgent) "v1:" else "v2:")
                if (line.words.isNotEmpty()) appendWordByWord(sb, line.words)
                else sb.append(line.text.trim())
                sb.append('\n')
            }

            if (line.backgroundWords.isNotEmpty() && multiPersonWordByWord) {
                if (sb.endsWith("\n")) sb.setLength(sb.length - 1)
                sb.append("\n[bg:")
                appendWordByWord(sb, line.backgroundWords)
                sb.append("]\n")
            }
        }
        return sb.toString().trimEnd().ifBlank { null }
    }

    private fun appendWordByWord(sb: StringBuilder, words: List<Word>) {
        for (word in words) {
            val begin = "<${word.beginMs.toInt().toLrcTimestamp()}>"
            val end = "<${word.endMs.toInt().toLrcTimestamp()}>"
            if (!sb.endsWith(begin)) sb.append(begin)
            sb.append(word.text).append(' ')
            sb.append(end)
        }
    }
}
