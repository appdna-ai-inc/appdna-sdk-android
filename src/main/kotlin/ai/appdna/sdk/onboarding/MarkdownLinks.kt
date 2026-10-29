package ai.appdna.sdk.onboarding

/**
 * The links markdown text turns into, one set for both platforms.
 *
 * iOS draws `rich_text` and an agreement's copy through Foundation's markdown parser (cmark-gfm with
 * its autolink extension), one line at a time; [find] follows the same rules here, so a line taps
 * through to the same URL on both:
 *
 * - an inline link `[label](destination)`, its destination bare (balanced parentheses and backslash
 *   escapes allowed, an optional title after it) or in angle brackets (`<…>`, spaces allowed); its
 *   label's own emphasis is drawn ([Link.label]); an image `![alt](…)` is no link;
 * - an autolink in angle brackets: `<scheme:…>` or `<user@host>`;
 * - a bare `http://`, `https://` or `ftp://` URL (any case), its scheme the whole run of letters
 *   before `://`, so it can follow a digit or any punctuation; and a `www.` address after a space,
 *   `*`, `_`, `~` or `(`. Neither inside an open `[` (a bracket not yet closed by `]`). The domain may
 *   not carry `_` in its last two segments, and trailing `?!.,:*_~'"`, an unmatched `)`, a `;` and an
 *   entity-like `&name;` are not part of the link;
 * - a bare email address, after any character outside `[A-Za-z0-9.+-_]` (`mailto:` and `xmpp:`
 *   prefixes included): its domain needs a dot followed by a letter or digit and must end in a letter.
 *
 * Nothing inside a code span (`` `…` ``) or a link's label is a link of its own.
 *
 * [destinationsInTemplate] answers the other question, on a TEMPLATE before its tokens are
 * substituted: where could a substituted value land inside a link destination? A `{{…}}` token stands
 * for any text without whitespace, and every span that could become a destination is covered — more
 * rather than less, since a token covered there resolves to what the user typed, which is never
 * Google's content. The Swift twin is `MarkdownLinks.destinations(inTemplate:)`.
 */
internal object MarkdownLinks {

    /**
     * One link in drawn text: its span `[start, end)`, the text it draws, and the URL it opens. [label]
     * is an inline link's label as written, whose own emphasis is drawn; null for every other form.
     */
    data class Link(val start: Int, val end: Int, val text: String, val url: String, val label: String? = null)

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    /** cmark's whitespace: where a bare link ends. */
    private fun isCmarkSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u000B' || c == '\u000C'

    // ── Drawn text ───────────────────────────────────────────────────────────────────────────────

    /**
     * Every link in [text], in order, none overlapping. Each line is read on its own, as iOS reads
     * rich text one line at a time.
     */
    fun find(text: String): List<Link> {
        val out = mutableListOf<Link>()
        var lineStart = 0
        while (lineStart <= text.length) {
            val nl = text.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) text.length else nl
            findInLine(text, lineStart, lineEnd, out)
            if (nl < 0) break
            lineStart = nl + 1
        }
        return out
    }

    /**
     * What the text `[from, to)` of one line draws as a unit, in order: a code span, an image, and every
     * link, including one whose URL Foundation cannot make ([Link.url] empty) — it draws its text, but
     * does not link. [MarkdownInline] draws the rest (emphasis, escapes) around them.
     */
    sealed class Atom(val start: Int, val end: Int) {
        /** A code span; [text] is what it draws (one space stripped from each side when both have one). */
        class Code(start: Int, end: Int, val text: String) : Atom(start, end)
        /** An image `![alt](…)`; its alt text is `[labelStart, labelEnd)`. */
        class Image(start: Int, end: Int, val labelStart: Int, val labelEnd: Int) : Atom(start, end)
        /** A link; an inline link's label is `[link.start + 1, link.start + 1 + label.length)`. */
        class Anchor(val link: Link) : Atom(link.start, link.end)
        /** An inline HTML tag, comment, declaration or CDATA section: drawn as its characters, nothing inside parsed. */
        class Html(start: Int, end: Int) : Atom(start, end)
    }

    fun atoms(t: String, from: Int, to: Int): List<Atom> {
        val atoms = mutableListOf<Atom>()
        findInLine(t, from, to, mutableListOf(), atoms)
        return atoms.sortedBy { it.start }
    }

    private fun findInLine(t: String, from: Int, to: Int, out: MutableList<Link>, atoms: MutableList<Atom>? = null) {
        val found = mutableListOf<Link>()
        // Spans no email may be found in: code spans, images, and every link found first.
        val opaque = mutableListOf<IntRange>()
        var i = from
        var brackets = 0
        while (i < to) {
            val c = t[i]
            if (c == '\\' && i + 1 < to && isEscapable(t[i + 1])) { i += 2; continue }
            if (c == '`') {
                // A code span: a run of exactly as many backticks closes it; nothing inside links.
                var run = 0
                while (i + run < to && t[i + run] == '`') run++
                var k = i + run
                var close = -1
                while (k < to) {
                    if (t[k] != '`') { k++; continue }
                    var r = 0
                    while (k + r < to && t[k + r] == '`') r++
                    if (r == run) { close = k; break }
                    k += r
                }
                if (close >= 0) {
                    opaque += i until close + run
                    atoms?.add(Atom.Code(i, close + run, codeText(t.substring(i + run, close))))
                    i = close + run
                } else {
                    i += run
                }
                continue
            }
            if (c == '!' && i + 1 < to && t[i + 1] == '[') {
                val image = inline(t, i + 1, to)
                if (image != null) {
                    opaque += i until image.end
                    atoms?.add(Atom.Image(i, image.end, i + 2, i + 2 + (image.label?.length ?: 0)))
                    i = image.end
                    continue
                }
                brackets++
                i += 2
                continue
            }
            if (c == '[') {
                val link = inline(t, i, to)
                if (link != null) { found += link; i = link.end; continue }
                brackets++
                i++
                continue
            }
            if (c == ']') { if (brackets > 0) brackets--; i++; continue }
            if (c == '<') {
                val link = angle(t, i, to)
                if (link != null) { found += link; i = link.end; continue }
                val html = htmlEnd(t, i, to)
                if (html > i) {
                    opaque += i until html
                    atoms?.add(Atom.Html(i, html))
                    i = html
                    continue
                }
            }
            if (brackets == 0) {
                val floor = maxOf(from, found.lastOrNull()?.end ?: from, opaque.lastOrNull()?.let { it.last + 1 } ?: from)
                val link = when (c) {
                    ':' -> bareUrl(t, i, floor, to)
                    'w' -> www(t, i, from, to)
                    else -> null
                }
                if (link != null) { found += link; i = link.end; continue }
            }
            i++
        }
        // Emails: in the runs of text between everything found above, as cmark finds them after the
        // rest — and a pair of `_` that makes emphasis ends a run, as it ends cmark's text node.
        val taken = (found.map { it.start until it.end } + opaque).sortedBy { it.first }
        val emphasis = underscoreEmphasis(t, from, to, taken)
        val runs = mutableListOf<IntRange>()
        var gap = from
        for (span in taken) {
            if (span.first > gap) runs += gap until span.first
            gap = maxOf(gap, span.last + 1)
        }
        if (gap < to) runs += gap until to
        for (run in runs) {
            var s = run.first
            for (k in run) {
                if (k in emphasis) { if (k > s) emailsInRun(t, s, k, found); s = k + 1 }
            }
            if (s <= run.last) emailsInRun(t, s, run.last + 1, found)
        }
        // A link whose URL Foundation cannot make, or whose label draws nothing, draws no link; its
        // text is consumed all the same.
        out += found.filter { it.url.isNotEmpty() && it.text.isNotEmpty() }.sortedBy { it.start }
        atoms?.addAll(found.map { Atom.Anchor(it) })
    }

    /** A code span's content as it draws: one space stripped from each side when both have one and it is not all spaces. */
    private fun codeText(s: String): String =
        if (s.length >= 2 && s.first() == ' ' && s.last() == ' ' && s.any { it != ' ' }) s.substring(1, s.length - 1) else s

    /**
     * The emails in one run of text `[from, to)`, read as cmark reads its text node: with backslash
     * escapes resolved (`\@` is an `@`).
     */
    private fun emailsInRun(t: String, from: Int, to: Int, out: MutableList<Link>) {
        if (t.indexOf('@', from).let { it < 0 || it >= to }) return
        val plain = StringBuilder()
        val at = IntArray(to - from + 1)
        var n = 0
        var i = from
        while (i < to) {
            if (t[i] == '\\' && i + 1 < to && isEscapable(t[i + 1])) i++
            at[n++] = i
            plain.append(t[i])
            i++
        }
        at[n] = to
        val found = mutableListOf<Link>()
        emails(plain.toString(), 0, n, found)
        for (l in found) out += Link(at[l.start], if (l.end == n) to else at[l.end], l.text, l.url)
    }

    /**
     * Every `_` that pairs into emphasis on the line `[from, to)` outside [taken] (CommonMark's
     * flanking rules and its rule of three). An unpaired `_` stays text.
     */
    private fun underscoreEmphasis(t: String, from: Int, to: Int, taken: List<IntRange>): Set<Int> {
        class Run(val start: Int, var length: Int, val canOpen: Boolean, val canClose: Boolean, val original: Int) {
            var head = start
            var tail = start + length
        }
        fun isWs(c: Char?) = c == null || Character.isWhitespace(c) || Character.isSpaceChar(c)
        fun isPunct(c: Char?) = c != null && (if (c.code < 0x80) isEscapable(c) else when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
            Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> true
            else -> false
        })
        val runs = mutableListOf<Run>()
        var i = from
        while (i < to) {
            if (t[i] == '\\' && i + 1 < to && isEscapable(t[i + 1])) { i += 2; continue }
            if (t[i] != '_' || taken.any { i in it }) { i++; continue }
            var e = i
            while (e < to && t[e] == '_' && taken.none { e in it }) e++
            val before = if (i > from) t[i - 1] else null
            val after = if (e < to) t[e] else null
            val left = !isWs(after) && (!isPunct(after) || isWs(before) || isPunct(before))
            val right = !isWs(before) && (!isPunct(before) || isWs(after) || isPunct(after))
            runs += Run(i, e - i, left && (!right || isPunct(before)), right && (!left || isPunct(after)), e - i)
            i = e
        }
        val consumed = mutableSetOf<Int>()
        val openers = mutableListOf<Run>()
        for (closer in runs) {
            if (closer.canClose) {
                var k = openers.size - 1
                while (closer.length > 0 && k >= 0) {
                    val opener = openers[k]
                    val oddMatch = (opener.canClose || closer.canOpen) &&
                        (opener.original + closer.original) % 3 == 0 &&
                        !(opener.original % 3 == 0 && closer.original % 3 == 0)
                    if (opener.length == 0 || oddMatch) { k--; continue }
                    val use = if (opener.length >= 2 && closer.length >= 2) 2 else 1
                    repeat(use) { consumed += --opener.tail; consumed += closer.head++ }
                    opener.length -= use
                    closer.length -= use
                    // Openers between the pair can no longer match.
                    while (openers.size > k + 1) openers.removeAt(openers.size - 1)
                    if (opener.length == 0) { openers.removeAt(k); k-- }
                }
            }
            if (closer.canOpen && closer.length > 0) openers += closer
        }
        return consumed
    }

    /** `[label](destination "title")` starting at [open] (a `[`), within the line ending at [to]. */
    private fun inline(t: String, open: Int, to: Int): Link? {
        val n = to
        // The label: brackets nest, backslash escapes.
        var depth = 0
        var k = open
        while (k < n) {
            val c = t[k]
            if (c == '\\') { k += 2; continue }
            if (c == '[') depth++
            if (c == ']') { depth--; if (depth == 0) break }
            k++
        }
        if (k >= n || k + 1 >= n || t[k + 1] != '(') return null
        val label = t.substring(open + 1, k)
        var j = k + 2
        while (j < n && (t[j] == ' ' || t[j] == '\t')) j++
        // The destination as written; its entity references are decoded first, then its backslash escapes.
        val dest = StringBuilder()
        if (j < n && t[j] == '<') {
            j++
            while (j < n && t[j] != '>') {
                if (t[j] == '<') return null
                if (t[j] == '\\' && j + 1 < n && isEscapable(t[j + 1])) { dest.append(t, j, j + 2); j += 2; continue }
                dest.append(t[j]); j++
            }
            if (j >= n) return null
            j++
        } else {
            var parens = 0
            while (j < n) {
                val c = t[j]
                if (c == '\\' && j + 1 < n && isEscapable(t[j + 1])) { dest.append(t, j, j + 2); j += 2; continue }
                if (c == '(') parens++
                if (c == ')') { if (parens == 0) break; parens-- }
                if (isSpace(c) || c.code < 0x20) break
                dest.append(c); j++
            }
            if (parens != 0) return null
        }
        val close = closeOfInline(t, j, n) ?: return null
        return Link(open, close + 1, unescape(label), encode(unescape(decodeEntities(dest.toString()))) ?: "", label)
    }

    /** After a destination at [from]: optional spaces, an optional title, optional spaces, then `)`. */
    private fun closeOfInline(t: String, from: Int, n: Int): Int? {
        var j = from
        while (j < n && (t[j] == ' ' || t[j] == '\t')) j++
        if (j < n && (t[j] == '"' || t[j] == '\'' || t[j] == '(') && j > from) {
            val closer = if (t[j] == '(') ')' else t[j]
            j++
            while (j < n && t[j] != closer) { if (t[j] == '\\') j++; j++ }
            if (j >= n) return null
            j++
            while (j < n && (t[j] == ' ' || t[j] == '\t')) j++
        }
        return if (j < n && t[j] == ')') j else null
    }

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.\\-]{1,31}:[^\\s<>\\u0000-\\u001F]*$")
    private val EMAIL = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~\\-]+@[A-Za-z0-9](?:[A-Za-z0-9\\-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9\\-]{0,61}[A-Za-z0-9])?)*$")

    /** `<scheme:…>` or `<user@host>` starting at [open] (a `<`). */
    private fun angle(t: String, open: Int, to: Int): Link? {
        val close = t.indexOf('>', open + 1).takeIf { it in (open + 2) until to } ?: return null
        val raw = t.substring(open + 1, close)
        // The address draws, and opens, with its entity references decoded (`&amp;` is `&`).
        val inner = decodeEntities(raw)
        if (SCHEME.matches(raw)) return Link(open, close + 1, inner, encode(inner) ?: "")
        if (EMAIL.matches(raw)) return Link(open, close + 1, inner, "mailto:$inner")
        return null
    }

    private val BARE_SCHEMES = listOf("http://", "https://", "ftp://")

    /**
     * A bare URL whose `://` starts at [colon]. Its scheme is the whole run of letters before the
     * colon (never reaching back past [floor]), and must be `http`, `https` or `ftp`; the character
     * after `://` must not be whitespace or punctuation.
     */
    private fun bareUrl(t: String, colon: Int, floor: Int, to: Int): Link? {
        if (to - colon < 4 || t[colon + 1] != '/' || t[colon + 2] != '/') return null
        var start = colon
        while (start > floor && isAsciiAlpha(t[start - 1])) start--
        val scheme = t.substring(start, colon + 3)
        if (BARE_SCHEMES.none { it.equals(scheme, ignoreCase = true) }) return null
        if (!isValidHostChar(t[colon + 3])) return null
        val domain = checkDomain(t, colon + 3, to, allowShort = true)
        if (domain == 0) return null
        var end = colon + 3 + domain
        while (end < to && !isCmarkSpace(t[end]) && t[end] != '<') end++
        end = colon + autolinkDelim(t, colon, end - colon)
        val shown = t.substring(start, end)
        return Link(start, end, shown, encode(shown) ?: "")
    }

    /** A `www.` address at [at]: after the line's start, whitespace, `*`, `_`, `~` or `(`. */
    private fun www(t: String, at: Int, lineStart: Int, to: Int): Link? {
        if (at > lineStart && t[at - 1] !in "*_~(" && !isCmarkSpace(t[at - 1])) return null
        if (to - at < 4 || !t.startsWith("www.", at)) return null
        val domain = checkDomain(t, at, to, allowShort = false)
        if (domain == 0) return null
        var end = at + domain
        while (end < to && !isCmarkSpace(t[end]) && t[end] != '<') end++
        end = at + autolinkDelim(t, at, end - at)
        if (end == at) return null
        val shown = t.substring(at, end)
        return Link(at, end, shown, encode("http://$shown") ?: "")
    }

    /**
     * How much of the text at [from] reads as a domain (0: none). A `_` in either of the last two
     * segments refuses it; without [allowShort] it needs a dot. The first character is not examined,
     * and the scan stops at the first character outside ASCII, as cmark's byte-wise scan does.
     */
    private fun checkDomain(t: String, from: Int, to: Int, allowShort: Boolean): Int {
        val size = to - from
        var i = 1
        var np = 0
        var underscoresBefore = 0
        var underscores = 0
        while (i < size - 1) {
            if (t[from + i] == '\\' && i < size - 2) i++
            val c = t[from + i]
            if (c == '_') {
                underscores++
            } else if (c == '.') {
                underscoresBefore = underscores
                underscores = 0
                np++
            } else if (c.code >= 0x80) {
                // One character outside ASCII ends the scan: after it when it can be part of a host.
                if (isValidHostChar(c)) i++
                break
            } else if (!isValidHostChar(c) && c != '-') {
                break
            }
            i++
        }
        if (underscoresBefore > 0 || underscores > 0) return 0
        return if (allowShort || np > 0) i else 0
    }

    /** Neither whitespace nor punctuation (ASCII punctuation and Unicode's P* categories). */
    private fun isValidHostChar(c: Char): Boolean {
        if (c.code < 0x80) return !isCmarkSpace(c) && !isEscapable(c)
        if (Character.isWhitespace(c) || Character.isSpaceChar(c)) return false
        return when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> false
            else -> true
        }
    }

    /**
     * Where a bare link of [length] characters from [base] really ends: at a `<`; without trailing
     * `?!.,:*_~'"`, an unmatched `)`, a `;`, or an entity-like `&name;`.
     */
    private fun autolinkDelim(t: String, base: Int, length: Int): Int {
        var end = length
        for (k in 0 until end) if (t[base + k] == '<') { end = k; break }
        while (end > 0) {
            val c = t[base + end - 1]
            if (c in "?!.,:*_~'\"") {
                end--
            } else if (c == ';') {
                var ne = end - 2
                while (ne > 0 && isAsciiAlpha(t[base + ne])) ne--
                end = if (ne >= 0 && ne < end - 2 && t[base + ne] == '&') ne else end - 1
            } else if (c == ')') {
                var opening = 0
                var closing = 0
                for (k in 0 until end) {
                    if (t[base + k] == '(') opening++ else if (t[base + k] == ')') closing++
                }
                if (closing <= opening) break
                end--
            } else {
                break
            }
        }
        return end
    }

    /**
     * Every bare email address in the text `[from, to)`, as cmark finds them in a text run: from each
     * `@`, back over `[A-Za-z0-9.+-_]` (and a `mailto:` / `xmpp:` that does not follow a letter or
     * digit), and on over letters, digits, `-`, `_` and dots followed by a letter or digit.
     */
    private fun emails(t: String, from: Int, to: Int, out: MutableList<Link>) {
        var start = from
        var offset = 0
        outer@ while (true) {
            val remaining = to - start
            if (offset >= remaining) break
            val atPos = t.indexOf('@', start + offset).takeIf { it in 0 until to } ?: break
            var maxRewind = atPos - (start + offset)
            var autoMailto = true
            var isXmpp = false
            var np = 0
            var rewind: Int
            var linkEnd: Int
            while (true) {
                val base = start + offset
                val at = base + maxRewind
                rewind = 0
                while (rewind < maxRewind) {
                    val c = t[at - rewind - 1]
                    if (isAsciiAlnum(c) || c in ".+-_") { rewind++; continue }
                    if (c == ':') {
                        if (validProtocol(t, "mailto:", at, rewind, maxRewind)) { autoMailto = false; rewind++; continue }
                        if (validProtocol(t, "xmpp:", at, rewind, maxRewind)) { autoMailto = false; isXmpp = true; rewind++; continue }
                    }
                    break
                }
                if (rewind == 0) { offset += maxRewind + 1; continue@outer }
                linkEnd = 1
                var again = false
                while (linkEnd < remaining - offset - maxRewind) {
                    val c = t[at + linkEnd]
                    if (isAsciiAlnum(c)) { linkEnd++; continue }
                    if (c == '@') {
                        // Another `@`: start again from the text after the first.
                        offset += maxRewind + 1
                        maxRewind = linkEnd - 1
                        again = true
                        break
                    } else if (c == '.' && linkEnd < remaining - offset - maxRewind - 1 && isAsciiAlnum(t[at + linkEnd + 1])) {
                        np++
                    } else if (c == '/' && isXmpp) {
                        // An xmpp resource.
                    } else if (c != '-' && c != '_') {
                        break
                    }
                    linkEnd++
                }
                if (!again) break
            }
            val at = start + offset + maxRewind
            val last = t[at + linkEnd - 1]
            if (linkEnd < 2 || np == 0 || (!isAsciiAlpha(last) && last != '.')) {
                offset += maxRewind + linkEnd
                continue
            }
            linkEnd = autolinkDelim(t, at, linkEnd)
            if (linkEnd == 0) { offset += maxRewind + 1; continue }
            val s = at - rewind
            val e = at + linkEnd
            val shown = t.substring(s, e)
            out += Link(s, e, shown, encode(if (autoMailto) "mailto:$shown" else shown) ?: "")
            start += offset + maxRewind + linkEnd
            offset = 0
        }
    }

    /** `mailto:` / `xmpp:` ending just before the [rewind] characters before [at], not after a letter or digit. */
    private fun validProtocol(t: String, protocol: String, at: Int, rewind: Int, maxRewind: Int): Boolean {
        val len = protocol.length
        if (len > maxRewind - rewind) return false
        if (!t.startsWith(protocol, at - rewind - len)) return false
        if (len == maxRewind - rewind) return true
        return !isAsciiAlnum(t[at - rewind - len - 1])
    }

    private fun isAsciiAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun isAsciiAlpha(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    fun isEscapable(c: Char) = c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    /**
     * The entity reference at [i] (a `&`) as cmark decodes it — its characters and the index after it — or
     * null where there is none: `&name;` for every HTML5 named reference ([ai.appdna.sdk.generated.HtmlEntities]),
     * `&#NN;` and `&#xHH;` (a code point of 0, a surrogate or past U+10FFFF draws U+FFFD). A name without its
     * `;` (`&copy`) is not one.
     */
    fun entityAt(t: String, i: Int, to: Int): Pair<String, Int>? {
        if (i + 2 >= to || t[i] != '&') return null
        if (t[i + 1] == '#') {
            val hex = t[i + 2] == 'x' || t[i + 2] == 'X'
            var k = if (hex) i + 3 else i + 2
            val digitsStart = k
            var cp = 0
            while (k < to) {
                val c = t[k]
                val d = when {
                    c in '0'..'9' -> c - '0'
                    hex && c in 'a'..'f' -> c - 'a' + 10
                    hex && c in 'A'..'F' -> c - 'A' + 10
                    else -> break
                }
                cp = minOf(cp * (if (hex) 16 else 10) + d, 0x110000)
                k++
            }
            if (k == digitsStart || k >= to || t[k] != ';') return null
            if (cp == 0 || cp in 0xD800..0xDFFF || cp >= 0x110000) cp = 0xFFFD
            return String(Character.toChars(cp)) to k + 1
        }
        var k = i + 1
        while (k < to && k - i - 1 < ai.appdna.sdk.generated.HtmlEntities.MAX_NAME_LENGTH && isAsciiAlnum(t[k])) k++
        if (k == i + 1 || k >= to || t[k] != ';') return null
        val chars = ai.appdna.sdk.generated.HtmlEntities.byName[t.substring(i + 1, k)] ?: return null
        return chars to k + 1
    }

    /** [s] with its entity references decoded ([entityAt]). */
    fun decodeEntities(s: String): String {
        if (!s.contains('&')) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val e = if (s[i] == '&') entityAt(s, i, s.length) else null
            if (e != null) { out.append(e.first); i = e.second; continue }
            out.append(s[i]); i++
        }
        return out.toString()
    }

    private const val HTML_SPACE = " \t\n\r\u000B\u000C"
    private val HTML_TAG = java.util.regex.Pattern.compile(
        "<[A-Za-z][A-Za-z0-9-]*" +
            "(?:[$HTML_SPACE]+[A-Za-z_:][A-Za-z0-9_.:-]*(?:[$HTML_SPACE]*=[$HTML_SPACE]*(?:[^$HTML_SPACE\"'=<>`]+|'[^']*'|\"[^\"]*\"))?)*" +
            "[$HTML_SPACE]*/?>" +
            "|</[A-Za-z][A-Za-z0-9-]*[$HTML_SPACE]*>" +
            "|<!--->|<!-->|<!--.*?-->" +
            "|<\\?.*?\\?>" +
            "|<![A-Za-z][^>]*>" +
            "|<!\\[CDATA\\[.*?]]>",
        java.util.regex.Pattern.DOTALL,
    )

    /**
     * Where an inline HTML tag starting at [open] (a `<`) ends, or [open] where there is none: an open or closing
     * tag, a comment, a processing instruction, a declaration or a CDATA section, as CommonMark defines them.
     */
    private fun htmlEnd(t: String, open: Int, to: Int): Int {
        val m = HTML_TAG.matcher(t).region(open, to)
        return if (m.lookingAt()) m.end() else open
    }

    /** [s] with its backslash escapes resolved. */
    fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length && isEscapable(s[i + 1])) { out.append(s[i + 1]); i += 2; continue }
            out.append(s[i]); i++
        }
        return out.toString()
    }

    /**
     * The URL a tap opens, as Foundation's URL parsing makes it, or null where Foundation makes none
     * (the text is then drawn, but does not link): a colon before any `/` must end a valid scheme; a
     * host is converted to IDNA (punycode) and may hold only letters, digits, `-._~!$&'()*+,;=` and
     * `%XX`; a port only digits.
     *
     * A URL whose every part is valid as written is kept exactly as written. Otherwise each part that
     * is not (the user part, the path, the query, the fragment) is percent-encoded whole (UTF-8): a
     * space, a control character, every character outside ASCII, `"<>\\^`{|}[]` and every `%`, an
     * `@` in the user part and a `#` in the fragment too; an empty port is dropped with its colon.
     */
    private fun encode(url: String): String? {
        val colon = url.indexOf(':')
        if (colon >= 0) {
            val head = url.substring(0, colon)
            val schemeLike = head.isNotEmpty() && isAsciiAlpha(head[0]) && head.all { isAsciiAlnum(it) || it in "+.-" }
            if (!schemeLike && head.none { it in "/?#" }) return null
        }
        val hierarchical = colon > 0 && url.startsWith("//", colon + 1) && url.substring(0, colon).all { isAsciiAlnum(it) || it in "+.-" }
        if (!hierarchical) {
            val parts = splitTail(url)
            return if (parts.all { validPart(it.first, it.second) }) url else parts.joinToString("") { encodePart(it.first, it.second) }
        }
        val hostStart = colon + 3
        val authorityEnd = url.indexOfAny(charArrayOf('/', '?', '#'), hostStart).let { if (it < 0) url.length else it }
        val authority = url.substring(hostStart, authorityEnd)
        val userEnd = authority.lastIndexOf('@') + 1
        val hostPort = authority.substring(userEnd)
        var host: String
        val port: String?
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            if (close < 0) return null
            host = hostPort.substring(0, close + 1)
            val rest = hostPort.substring(close + 1)
            if (rest.isNotEmpty() && !rest.startsWith(":")) return null
            port = rest.takeIf { it.isNotEmpty() }?.substring(1)
        } else {
            val c = hostPort.lastIndexOf(':')
            host = if (c < 0) hostPort else hostPort.substring(0, c)
            port = if (c < 0) null else hostPort.substring(c + 1)
            if (host.any { it.code > 0x7E }) host = toAsciiHost(host) ?: return null
            var k = 0
            while (k < host.length) {
                val ch = host[k]
                if (ch == '%') {
                    if (k + 2 >= host.length || !isHex(host[k + 1]) || !isHex(host[k + 2])) return null
                    k += 3
                    continue
                }
                if (!(isAsciiAlnum(ch) || ch in "-._~!$&'()*+,;=")) return null
                k++
            }
        }
        if (port != null && !port.all { it in '0'..'9' }) return null
        val user = if (userEnd > 0) authority.substring(0, userEnd - 1) else null
        val tail = splitTail(url.substring(authorityEnd))
        val hostKept = hostPort.startsWith(host)
        if (hostKept && (user == null || validPart(user, 'u')) && tail.all { validPart(it.first, it.second) }) return url
        return url.substring(0, hostStart) +
            (if (user != null) encodePart(user, 'u') + "@" else "") +
            host +
            (if (!port.isNullOrEmpty()) ":$port" else "") +
            tail.joinToString("") { encodePart(it.first, it.second) }
    }

    /**
     * A host holding any character outside ASCII, as Foundation's URL parsing converts it, or null where
     * it makes no URL: the WHOLE host through UTS #46, non-transitional, with hyphens, joiners and bidi
     * checked, and any error refusing it.
     *
     * - `ß` and `ς` are kept and encoded (`straße` -> `xn--strae-oqa`), never mapped to `ss` and `σ`;
     * - a zero-width joiner or non-joiner is kept where its context allows it (after a virama, between
     *   joining letters) and refuses the host anywhere else;
     * - every label is mapped, so an ASCII label beside an international one is lowercased
     *   (`WWW.straße.de` -> `www.xn--strae-oqa.de`), and `。` separates labels as `.` does;
     * - a label that starts or ends with `-`, or that is empty before the last, refuses the host.
     *
     * `java.net.IDN` is IDNA2003, which maps `ß` to `ss` and drops a joiner — a different host.
     */
    private fun toAsciiHost(host: String): String? {
        val out = StringBuilder()
        val info = android.icu.text.IDNA.Info()
        try {
            idna.nameToASCII(host, out, info)
        } catch (_: Exception) {
            return null
        }
        return if (info.hasErrors()) null else out.toString()
    }

    private val idna: android.icu.text.IDNA by lazy {
        android.icu.text.IDNA.getUTS46Instance(
            android.icu.text.IDNA.CHECK_BIDI or android.icu.text.IDNA.CHECK_CONTEXTJ or
                android.icu.text.IDNA.NONTRANSITIONAL_TO_ASCII or android.icu.text.IDNA.NONTRANSITIONAL_TO_UNICODE,
        )
    }

    /** [s] cut into its path (`p`), query (`q`, from the first `?`) and fragment (`f`, from the first `#`). */
    private fun splitTail(s: String): List<Pair<String, Char>> {
        val hash = s.indexOf('#').let { if (it < 0) s.length else it }
        val question = s.indexOf('?').let { if (it < 0 || it > hash) hash else it }
        return listOf(s.substring(0, question) to 'p', s.substring(question, hash) to 'q', s.substring(hash) to 'f')
            .filter { it.first.isNotEmpty() }
    }

    private fun needsEscape(ch: Char) = ch == ' ' || ch.code > 0x7E || ch.code < 0x20 || ch in "\"<>\\^`{|}[]"

    /** Part [s] of kind [kind] needs nothing escaped. A fragment's own leading `#` is its delimiter. */
    private fun validPart(s: String, kind: Char): Boolean {
        for (i in s.indices) {
            val ch = s[i]
            if (needsEscape(ch)) return false
            if (ch == '%' && !(i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2]))) return false
            if (kind == 'u' && ch == '@') return false
            if (kind == 'f' && ch == '#' && i > 0) return false
        }
        return true
    }

    /** Part [s] as it is rebuilt: kept when valid, otherwise percent-encoded whole. */
    private fun encodePart(s: String, kind: Char): String {
        if (validPart(s, kind)) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            val escape = needsEscape(ch) || ch == '%' || (kind == 'u' && ch == '@') || (kind == 'f' && ch == '#' && i > 0)
            if (escape) {
                val chars = Character.charCount(Character.codePointAt(s, i))
                for (b in s.substring(i, i + chars).toByteArray(Charsets.UTF_8)) {
                    out.append('%').append(String.format(java.util.Locale.ROOT, "%02X", b.toInt() and 0xFF))
                }
                i += chars
                continue
            }
            out.append(ch)
            i++
        }
        return out.toString()
    }

    private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    // ── Templates ────────────────────────────────────────────────────────────────────────────────

    /**
     * Every span of the template [text] a substituted value could reach inside a link destination, as
     * index ranges: an inline link's destination (and its title), an angle-bracket autolink, and any
     * whitespace-delimited word that is or holds a bare URL or email address. A `{{…}}` token is opaque
     * and stands for any text without whitespace.
     */
    fun destinationsInTemplate(text: String): List<IntRange> {
        if (!text.contains("{{")) return emptyList()
        val n = text.length
        val out = mutableListOf<IntRange>()
        fun tokenEnd(i: Int): Int {
            if (i + 1 >= n || text[i] != '{' || text[i + 1] != '{') return -1
            val close = text.indexOf("}}", i + 2)
            return if (close >= 0) close + 2 else -1
        }
        // A backslash before a token escapes only the first character of its value: the token is
        // still read.
        fun skipEscape(k: Int): Int = if (tokenEnd(k + 1) >= 0) k + 1 else k + 2
        fun isBlank(c: Char) = c == ' ' || c == '\t'
        // After a destination: optional spaces, an optional title, optional spaces, then `)`.
        fun closeParen(from: Int): Int {
            var j = from
            while (j < n && isBlank(text[j])) j++
            if (j < n && (text[j] == '"' || text[j] == '\'' || text[j] == '(')) {
                val closer = if (text[j] == '(') ')' else text[j]
                j++
                while (j < n && text[j] != closer) {
                    val e = tokenEnd(j)
                    if (e >= 0) { j = e; continue }
                    if (text[j] == '\\' && tokenEnd(j + 1) < 0) j++
                    j++
                }
                if (j >= n) return -1
                j++
                while (j < n && isBlank(text[j])) j++
            }
            return if (j < n && text[j] == ')') j else -1
        }

        var i = 0
        while (i < n) {
            val te = tokenEnd(i)
            if (te >= 0) { i = te; continue }
            val c = text[i]
            if (c == '\\') { i = skipEscape(i); continue }
            if (c == ']' && i + 1 < n && text[i + 1] == '(') {
                var j = i + 2
                while (j < n && isBlank(text[j])) j++
                var end = -1
                var k = j
                var token = false
                if (j < n && text[j] == '<') {
                    k = j + 1
                    var ok = false
                    while (k < n) {
                        val e = tokenEnd(k)
                        if (e >= 0) { token = true; k = e; continue }
                        val d = text[k]
                        if (d == '\\') { k = skipEscape(k); continue }
                        if (d == '>') { ok = true; break }
                        if (d == '<' || d == '\n') break
                        k++
                    }
                    if (ok) end = closeParen(k + 1)
                } else {
                    var parens = 0
                    while (k < n) {
                        val e = tokenEnd(k)
                        if (e >= 0) { token = true; k = e; continue }
                        val d = text[k]
                        if (d == '\\') { k = skipEscape(k); continue }
                        if (d == '(') parens++
                        if (d == ')') { if (parens == 0) break; parens-- }
                        if (isSpace(d) || d.code < 0x20) break
                        k++
                    }
                    if (parens == 0) end = closeParen(minOf(k, n))
                }
                if (end >= 0) { out += j until end; i = end + 1; continue }
                // Nothing as written closes it, but a token's value can (a `)` or `>` inside it): the
                // destination then runs from its start through the token.
                if (token) { out += j until minOf(k, n); i = minOf(k, n); continue }
            }
            if (c == '<') {
                var k = i + 1
                var token = false
                var marker = false
                var ok = false
                while (k < n) {
                    val e = tokenEnd(k)
                    if (e >= 0) { token = true; k = e; continue }
                    val d = text[k]
                    if (d == '>') { ok = true; break }
                    if (isSpace(d) || d == '<' || d.code < 0x20) break
                    if (d == ':' || d == '@') marker = true
                    k++
                }
                if (ok && k > i + 1 && (token || marker)) { out += (i + 1) until k; i = k + 1; continue }
                // No `>` closes it as written, but a token's value can.
                if (!ok && token) { out += (i + 1) until minOf(k, n); i = minOf(k, n); continue }
            }
            i++
        }

        // Words that are, or hold, a bare URL or email address: from where the link can begin (its
        // scheme, `www.`, the email's local part) to where it can end (the word's end).
        var w = 0
        while (w < n) {
            if (isSpace(text[w]) || text[w] == '<') { w++; continue }
            val plain = StringBuilder()
            val at = mutableListOf<Int>() // the template index of each plain character
            var token = false
            while (w < n) {
                val e = tokenEnd(w)
                if (e >= 0) { token = true; plain.append(TOKEN); at += w; w = e; continue }
                if (isSpace(text[w]) || text[w] == '<') break
                // An escaped character reads as itself (`\@` is an `@`).
                if (text[w] == '\\' && w + 1 < n && isEscapable(text[w + 1]) && tokenEnd(w + 1) < 0) w++
                plain.append(text[w]); at += w; w++
            }
            if (!token) continue
            // Every index below is an index into `plain` (no case folding: a lowercase copy can be
            // longer than the text it came from, `İ` being two characters lowercased).
            val p = plain.toString()
            val starts = mutableListOf<Int>()
            // A bare URL's scheme: the whole run of letters (or tokens) before the first `://` that
            // has one, wherever it sits — a digit or punctuation before it does not stop it.
            var colon = p.indexOf("://")
            while (colon >= 0) {
                var s = colon
                while (s > 0 && (isAsciiAlpha(p[s - 1]) || p[s - 1] == TOKEN)) s--
                if (s < colon) { starts += s; break }
                colon = p.indexOf("://", colon + 1)
            }
            // A `<` inside a token's value opens an autolink that a `>` after it closes.
            val gt = p.lastIndexOf('>')
            if (gt > 0) p.indexOf(TOKEN).takeIf { it in 0 until gt }?.let { starts += it }
            for (marker in listOf("www.", "mailto:", "xmpp:")) {
                indexOfAscii(p, marker).takeIf { it >= 0 }?.let { starts += it }
            }
            // An email address may begin at any `@` with a local part before it: a later `@` starts
            // one when the first has none (`@{{…}}@host`).
            for (a in p.indices) {
                if (p[a] != '@' || a == 0 || a == p.length - 1) continue
                if (!p.substring(a + 1).let { it.contains('.') || it.contains(TOKEN) }) continue
                var s = a
                // Back over a `mailto:` / `xmpp:` too, and whatever precedes it.
                while (s > 0 && (isEmailChar(p[s - 1]) || p[s - 1] == ':' || p[s - 1] == TOKEN)) s--
                if (s < a) { starts += s; break }
            }
            val start = starts.minOrNull() ?: continue
            out += at[start] until w
        }
        return out
    }

    /**
     * The first index of [marker] in [s], ASCII letters compared without case and nothing else, as the
     * markdown parser compares them: a dotless `ı` or a dotted `İ` is not an `i`. (`indexOf` with
     * `ignoreCase` folds every script, so it read `maılto:` as `mailto:`.)
     */
    private fun indexOfAscii(s: String, marker: String): Int {
        fun fold(c: Char) = if (c in 'A'..'Z') c + 32 else c
        outer@ for (i in 0..s.length - marker.length) {
            for (k in marker.indices) if (fold(s[i + k]) != fold(marker[k])) continue@outer
            return i
        }
        return -1
    }

    private fun isEmailChar(c: Char) = isAsciiAlnum(c) || c in ".+-_"

    /** What a token stands for in a word: any text without whitespace (a private-use placeholder). */
    private const val TOKEN = '\uE000'
}
