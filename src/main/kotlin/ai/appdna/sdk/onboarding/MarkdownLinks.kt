package ai.appdna.sdk.onboarding

/**
 * The links markdown text turns into, one set for both platforms.
 *
 * iOS draws `rich_text` and an agreement's copy through Foundation's markdown parser, which follows
 * every form below; [find] draws the same set here, so a line taps through to the same URL on both:
 *
 * - an inline link `[label](destination)`, its destination bare (balanced parentheses and backslash
 *   escapes allowed, an optional title after it) or in angle brackets (`<…>`, spaces allowed);
 * - an autolink in angle brackets: `<scheme:…>` or `<user@host>`;
 * - a bare URL: `http://`, `https://` or `ftp://` (any case), or `www.`; and a bare email address
 *   (`mailto:` and `xmpp:` prefixes included).
 *
 * Nothing inside a code span (`` `…` ``) is a link.
 *
 * [destinationsInTemplate] answers the other question, on a TEMPLATE before its tokens are
 * substituted: where could a substituted value land inside a link destination? A `{{…}}` token stands
 * for any text without whitespace, and every span that could become a destination is covered — more
 * rather than less, since a token covered there resolves to what the user typed, which is never
 * Google's content. The Swift twin is `MarkdownLinks.destinations(inTemplate:)`.
 */
internal object MarkdownLinks {

    /** One link in drawn text: its span `[start, end)`, the text it draws, and the URL it opens. */
    data class Link(val start: Int, val end: Int, val text: String, val url: String)

    private fun isSpace(c: Char) = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    // ── Drawn text ───────────────────────────────────────────────────────────────────────────────

    /** Every link in [text], in order, none overlapping. */
    fun find(text: String): List<Link> {
        val out = mutableListOf<Link>()
        val n = text.length
        var i = 0
        var wordStart = true
        while (i < n) {
            val c = text[i]
            if (c == '\\' && i + 1 < n) { i += 2; wordStart = false; continue }
            if (c == '`') {
                // A code span: the same run of backticks closes it; nothing inside links.
                var run = 0
                while (i + run < n && text[i + run] == '`') run++
                val close = text.indexOf("`".repeat(run), i + run)
                i = if (close >= 0) close + run else i + run
                wordStart = false
                continue
            }
            val link = when {
                c == '[' -> inline(text, i)
                c == '<' -> angle(text, i)
                wordStart -> bare(text, i)
                else -> null
            }
            if (link != null) { out += link; i = link.end; wordStart = false; continue }
            wordStart = isSpace(c) || c in "(*_~\"'[]>"
            i++
        }
        return out
    }

    /** `[label](destination "title")` starting at [open] (a `[`). */
    private fun inline(t: String, open: Int): Link? {
        val n = t.length
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
        val dest = StringBuilder()
        if (j < n && t[j] == '<') {
            j++
            while (j < n && t[j] != '>') {
                if (t[j] == '<' || t[j] == '\n') return null
                if (t[j] == '\\' && j + 1 < n && isEscapable(t[j + 1])) { dest.append(t[j + 1]); j += 2; continue }
                dest.append(t[j]); j++
            }
            if (j >= n) return null
            j++
        } else {
            var parens = 0
            while (j < n) {
                val c = t[j]
                if (c == '\\' && j + 1 < n && isEscapable(t[j + 1])) { dest.append(t[j + 1]); j += 2; continue }
                if (c == '(') parens++
                if (c == ')') { if (parens == 0) break; parens-- }
                if (isSpace(c) || c.code < 0x20) break
                dest.append(c); j++
            }
            if (parens != 0) return null
        }
        val close = closeOfInline(t, j) ?: return null
        return Link(open, close + 1, unescape(label), encode(dest.toString()))
    }

    /** After a destination at [from]: optional spaces, an optional title, optional spaces, then `)`. */
    private fun closeOfInline(t: String, from: Int): Int? {
        val n = t.length
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
    private fun angle(t: String, open: Int): Link? {
        val close = t.indexOf('>', open + 1).takeIf { it > open + 1 } ?: return null
        val inner = t.substring(open + 1, close)
        if (SCHEME.matches(inner)) return Link(open, close + 1, inner, encode(inner))
        if (EMAIL.matches(inner)) return Link(open, close + 1, inner, "mailto:$inner")
        return null
    }

    private val BARE_SCHEMES = listOf("https://", "http://", "ftp://")

    /** A bare URL, `www.` address or email address starting at a word's first character [start]. */
    private fun bare(t: String, start: Int): Link? {
        val rest = t.substring(start)
        val lower = rest.lowercase()
        val scheme = BARE_SCHEMES.firstOrNull { lower.startsWith(it) }
        if (scheme != null || rest.startsWith("www.")) {
            val prefix = scheme?.length ?: 0
            var end = start
            while (end < t.length && !isSpace(t[end]) && t[end] != '<') end++
            end = trimTrailing(t, start, end)
            if (end - start <= prefix || t.substring(start + prefix, end).let { it.isEmpty() || it.startsWith(".") }) return null
            val shown = t.substring(start, end)
            return Link(start, end, shown, encode(if (scheme == null) "http://$shown" else shown))
        }
        // An email address: the local part, `@`, then a dotted domain.
        val prefix = listOf("mailto:", "xmpp:").firstOrNull { lower.startsWith(it) }?.length ?: 0
        var end = start + prefix
        while (end < t.length && (isAsciiAlnum(t[end]) || t[end] in ".+-_")) end++
        if (end >= t.length || t[end] != '@' || end == start + prefix) return null
        var d = end + 1
        while (d < t.length && (isAsciiAlnum(t[d]) || t[d] in ".-_")) d++
        while (d > end + 1 && t[d - 1] == '.') d--
        val domain = t.substring(end + 1, d)
        if (!domain.contains('.') || domain.endsWith("-") || domain.endsWith("_")) return null
        val shown = t.substring(start, d)
        return Link(start, d, shown, if (prefix > 0) shown else "mailto:$shown")
    }

    /** GFM's trailing-punctuation rule for a bare URL: `?!.,:*_~` and an unmatched `)` are not part of it. */
    private fun trimTrailing(t: String, start: Int, endIn: Int): Int {
        var end = endIn
        while (end > start) {
            val c = t[end - 1]
            if (c in "?!.,:*_~\"'") { end--; continue }
            if (c == ')') {
                val s = t.substring(start, end)
                if (s.count { it == ')' } > s.count { it == '(' }) { end--; continue }
            }
            if (c == ';') {
                // An entity-like `&name;` at the end is not part of it.
                val amp = t.lastIndexOf('&', end - 1)
                if (amp >= start && t.substring(amp + 1, end - 1).all { it.isLetterOrDigit() }) { end = amp; continue }
            }
            break
        }
        return end
    }

    private fun isAsciiAlnum(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    private fun isEscapable(c: Char) = c in "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length && isEscapable(s[i + 1])) { out.append(s[i + 1]); i += 2; continue }
            out.append(s[i]); i++
        }
        return out.toString()
    }

    /** A space and every non-ASCII character percent-encoded (UTF-8), as Foundation's URL parsing does. */
    private fun encode(url: String): String {
        val out = StringBuilder()
        for (ch in url) {
            if (ch == ' ' || ch.code > 0x7E) {
                for (b in ch.toString().toByteArray(Charsets.UTF_8)) out.append('%').append(String.format(java.util.Locale.ROOT, "%02X", b.toInt() and 0xFF))
            } else {
                out.append(ch)
            }
        }
        return out.toString()
    }

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
                    if (text[j] == '\\') j++
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
            if (c == '\\') { i += 2; continue }
            if (c == ']' && i + 1 < n && text[i + 1] == '(') {
                var j = i + 2
                while (j < n && isBlank(text[j])) j++
                var end = -1
                if (j < n && text[j] == '<') {
                    var k = j + 1
                    var ok = false
                    while (k < n) {
                        val e = tokenEnd(k)
                        if (e >= 0) { k = e; continue }
                        val d = text[k]
                        if (d == '\\') { k += 2; continue }
                        if (d == '>') { ok = true; break }
                        if (d == '<' || d == '\n') break
                        k++
                    }
                    if (ok) end = closeParen(k + 1)
                } else {
                    var k = j
                    var parens = 0
                    while (k < n) {
                        val e = tokenEnd(k)
                        if (e >= 0) { k = e; continue }
                        val d = text[k]
                        if (d == '\\') { k += 2; continue }
                        if (d == '(') parens++
                        if (d == ')') { if (parens == 0) break; parens-- }
                        if (isSpace(d) || d.code < 0x20) break
                        k++
                    }
                    if (parens == 0) end = closeParen(minOf(k, n))
                }
                if (end >= 0) { out += j until end; i = end + 1; continue }
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
                plain.append(text[w]); at += w; w++
            }
            if (!token) continue
            val p = plain.toString()
            val lower = p.lowercase()
            val starts = mutableListOf<Int>()
            BARE_URL_IN_WORD.find(lower)?.let { starts += it.groups[2]!!.range.first }
            lower.indexOf("www.").takeIf { it >= 0 }?.let { starts += it }
            listOf("mailto:", "xmpp:").forEach { m -> lower.indexOf(m).takeIf { it >= 0 }?.let { starts += it } }
            val a = p.indexOf('@')
            if (a > 0 && a < p.length - 1 && p.substring(a + 1).let { it.contains('.') || it.contains(TOKEN) }) {
                var s = a
                while (s > 0 && (isEmailChar(p[s - 1]) || p[s - 1] == TOKEN)) s--
                if (s < a) starts += s
            }
            val start = starts.minOrNull() ?: continue
            out += at[start] until w
        }
        return out
    }

    private fun isEmailChar(c: Char) = isAsciiAlnum(c) || c in ".+-_"

    /** What a token stands for in a word: any text without whitespace (a private-use placeholder). */
    private const val TOKEN = '\uE000'

    /** A scheme (or a token standing for one) followed by `://`, at the start of a word or after punctuation. */
    private val BARE_URL_IN_WORD = Regex("(^|[^A-Za-z0-9])([A-Za-z][A-Za-z0-9+.\\-]*|\uE000)://")
}
