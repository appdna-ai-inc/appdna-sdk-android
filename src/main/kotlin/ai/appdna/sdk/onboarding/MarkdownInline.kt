package ai.appdna.sdk.onboarding

/**
 * Inline markdown drawn as iOS draws it, one set of rules for both platforms.
 *
 * iOS draws `rich_text` and an agreement's copy through Foundation's markdown parser (cmark-gfm),
 * inline syntax only, one line at a time. [draw] follows the same rules here, so the same authored
 * text reads the same on both:
 *
 * - emphasis by CommonMark's delimiter rules: `*a*` and `_a_` italic, `**a**` and `__a__` bold, an
 *   `_` inside a word (`snake_case`) left as text, the rule of three, and the earliest closer pairing
 *   with the nearest opener before it;
 * - `~a~` and `~~a~~` struck through (the opener and closer the same length; three or more is text);
 * - a code span drawn as its text, in a monospaced face, with nothing inside it parsed;
 * - a backslash escape drawn as the character it escapes, and an entity reference (`&amp;`, `&#35;`)
 *   as its characters — never inside a code span;
 * - an inline HTML tag, comment or declaration drawn as its characters, nothing inside it parsed;
 * - every link form [MarkdownLinks] finds, its label's own emphasis drawn; a link whose URL Foundation
 *   cannot make draws its label (an autolink its address) as plain text, without its own emphasis;
 * - an image draws its alt text, plain; an image with no alt text draws nothing;
 * - `++a++` underlined, on `rich_text` only ([underlineMarkers]): iOS finds the markers in the text it
 *   has already drawn, pairing each `++` with the next, so they can span anything.
 *
 * Two of Foundation's own habits are followed as well, because they are what a reader sees: emphasis
 * nested inside the same emphasis ends at the inner closer (`*a *b* c*` draws ` c` upright), and the
 * flanking rules count Unicode punctuation, not symbols (`*€a€*` is italic).
 */
internal object MarkdownInline {

    /** One run of drawn text and how it is drawn; [url] is the link a tap opens, if any. */
    data class Run(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
        val url: String? = null,
    )

    fun draw(markdown: String, underlineMarkers: Boolean): List<Run> {
        val runs = mutableListOf<Run>()
        var lineStart = 0
        while (true) {
            val nl = markdown.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) markdown.length else nl
            val atoms = MarkdownLinks.atoms(markdown, lineStart, lineEnd)
            val nodes = parse(markdown, lineStart, lineEnd, Line(lineStart, lineEnd), atoms, links = true)
            flatten(nodes, Style(), null, runs)
            if (nl < 0) break
            runs += Run("\n")
            lineStart = nl + 1
        }
        return merge(if (underlineMarkers) underline(merge(runs)) else runs)
    }

    // ── Parsing ──────────────────────────────────────────────────────────────────────────────────

    private class Line(val start: Int, val end: Int)

    private sealed class Node {
        class Text(val s: String) : Node()
        /** A run of `*`, `_` or `~` not yet paired: what is left of it draws as text. */
        class Delim(val ch: Char, var count: Int) : Node()
        class Code(val s: String) : Node()
        /** Emphasis: `i` italic, `b` bold, `s` struck through. */
        class Wrap(val kind: Char, val children: List<Node>) : Node()
        class Anchor(val url: String, val children: List<Node>) : Node()
        /** Characters drawn with the styles around them and none of their own. */
        class Plain(val s: String) : Node()
    }

    private class Delimiter(val node: Node.Delim, val canOpen: Boolean, val canClose: Boolean, val original: Int)

    /**
     * The text `[from, to)` as nodes, emphasis paired. [atoms] are the units on this line (only those
     * inside the range are drawn here); with [links] false (a link's label) a link inside draws as text.
     */
    private fun parse(t: String, from: Int, to: Int, line: Line, atoms: List<MarkdownLinks.Atom>, links: Boolean): MutableList<Node> {
        val nodes = mutableListOf<Node>()
        val delimiters = mutableListOf<Delimiter>()
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) { nodes += Node.Text(text.toString()); text.clear() }
        }
        val starts = HashMap<Int, MarkdownLinks.Atom>()
        for (a in atoms) {
            if (a.start < from || a.end > to) continue
            if (!links && a is MarkdownLinks.Atom.Anchor) continue
            if (starts.keys.any { it < a.start && starts[it]!!.end > a.start }) continue
            starts[a.start] = a
        }
        var i = from
        while (i < to) {
            val atom = starts[i]
            if (atom != null) {
                flush()
                nodes += atomNode(t, atom, line, atoms)
                i = atom.end
                continue
            }
            val c = t[i]
            if (c == '\\' && i + 1 < to && MarkdownLinks.isEscapable(t[i + 1])) {
                text.append(t[i + 1])
                i += 2
                continue
            }
            if (c == '&') {
                val entity = MarkdownLinks.entityAt(t, i, to)
                if (entity != null) {
                    // Drawn as its characters, and never a delimiter (`&#42;` is a star, not emphasis).
                    text.append(entity.first)
                    i = entity.second
                    continue
                }
            }
            if (c == '*' || c == '_' || c == '~') {
                var e = i
                while (e < to && t[e] == c && (e == i || starts[e] == null)) e++
                val n = e - i
                val before = if (i > line.start) Character.codePointBefore(t, i) else -1
                val after = if (e < line.end) Character.codePointAt(t, e) else -1
                val left = !isSpace(after) && (!isPunct(after) || isSpace(before) || isPunct(before))
                val right = !isSpace(before) && (!isPunct(before) || isSpace(after) || isPunct(after))
                val (canOpen, canClose) = when (c) {
                    '_' -> (left && (!right || isPunct(before))) to (right && (!left || isPunct(after)))
                    else -> left to right
                }
                flush()
                val node = Node.Delim(c, n)
                nodes += node
                val pushed = if (c == '~') n <= 2 && (left || right) else canOpen || canClose
                if (pushed) delimiters += Delimiter(node, canOpen, canClose, n)
                i = e
                continue
            }
            text.append(c)
            i++
        }
        flush()
        pairEmphasis(nodes, delimiters)
        return nodes
    }

    private fun atomNode(t: String, atom: MarkdownLinks.Atom, line: Line, atoms: List<MarkdownLinks.Atom>): Node = when (atom) {
        is MarkdownLinks.Atom.Code -> Node.Code(atom.text)
        is MarkdownLinks.Atom.Html -> Node.Text(t.substring(atom.start, atom.end))
        is MarkdownLinks.Atom.Image -> Node.Plain(plain(labelNodes(t, atom.labelStart, atom.labelEnd, line)))
        is MarkdownLinks.Atom.Anchor -> {
            val link = atom.link
            val label = link.label
            when {
                label != null -> {
                    val nodes = labelNodes(t, link.start + 1, link.start + 1 + label.length, line)
                    if (link.url.isNotEmpty()) Node.Anchor(link.url, nodes) else Node.Plain(plain(nodes))
                }
                link.url.isNotEmpty() -> Node.Anchor(link.url, listOf(Node.Text(link.text)))
                else -> Node.Plain(link.text)
            }
        }
    }

    /** A label's nodes: its own code spans and images drawn, a link inside it drawn as text. */
    private fun labelNodes(t: String, from: Int, to: Int, line: Line): List<Node> =
        parse(t, from, to, line, MarkdownLinks.atoms(t, from, to), links = false)

    private fun plain(nodes: List<Node>): String = buildString {
        for (n in nodes) when (n) {
            is Node.Text -> append(n.s)
            is Node.Delim -> repeat(n.count) { append(n.ch) }
            is Node.Code -> append(n.s)
            is Node.Wrap -> append(plain(n.children))
            is Node.Anchor -> append(plain(n.children))
            is Node.Plain -> append(n.s)
        }
    }

    /** CommonMark's emphasis pairing (and cmark-gfm's strikethrough) over one run of nodes. */
    private fun pairEmphasis(nodes: MutableList<Node>, stack: MutableList<Delimiter>) {
        var ci = 0
        while (ci < stack.size) {
            val closer = stack[ci]
            if (!closer.canClose) { ci++; continue }
            var oi = ci - 1
            while (oi >= 0) {
                val o = stack[oi]
                if (o.canOpen && o.node.ch == closer.node.ch) {
                    val oddMatch = (closer.canOpen || o.canClose) && closer.original % 3 != 0 &&
                        (o.original + closer.original) % 3 == 0
                    if (!oddMatch) break
                }
                oi--
            }
            if (oi < 0) {
                if (!closer.canOpen) stack.removeAt(ci) else ci++
                continue
            }
            val opener = stack[oi]
            if (closer.node.ch == '~') {
                if (opener.node.count == closer.node.count) {
                    wrap(nodes, opener.node, closer.node, 's')
                    nodes.remove(opener.node)
                    nodes.remove(closer.node)
                }
                for (k in ci downTo oi) stack.removeAt(k)
                ci = oi
                continue
            }
            val use = if (opener.node.count >= 2 && closer.node.count >= 2) 2 else 1
            opener.node.count -= use
            closer.node.count -= use
            wrap(nodes, opener.node, closer.node, if (use == 2) 'b' else 'i')
            for (k in ci - 1 downTo oi + 1) stack.removeAt(k)
            ci = oi + 1
            if (opener.node.count == 0) {
                nodes.remove(opener.node)
                stack.removeAt(oi)
                ci--
            }
            if (closer.node.count == 0) {
                nodes.remove(closer.node)
                stack.removeAt(ci)
            }
        }
    }

    /** Everything strictly between [open] and [close] wrapped as [kind]. */
    private fun wrap(nodes: MutableList<Node>, open: Node, close: Node, kind: Char) {
        val a = nodes.indexOf(open)
        val b = nodes.indexOf(close)
        val inner = nodes.subList(a + 1, b)
        val wrapped = Node.Wrap(kind, inner.toList())
        inner.clear()
        nodes.add(a + 1, wrapped)
    }

    /** cmark's whitespace; -1 (the edge of the line) counts as whitespace. */
    private fun isSpace(cp: Int) = cp == -1 || cp == 9 || cp == 10 || cp == 12 || cp == 13 || cp == 32 ||
        cp == 0xA0 || cp == 0x1680 || cp in 0x2000..0x200A || cp == 0x202F || cp == 0x205F || cp == 0x3000

    /** cmark's punctuation: ASCII punctuation, and Unicode's punctuation categories (not its symbols). */
    private fun isPunct(cp: Int): Boolean {
        if (cp < 0) return false
        if (cp < 0x80) return MarkdownLinks.isEscapable(cp.toChar())
        return when (Character.getType(cp).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
            Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    // ── Drawing ──────────────────────────────────────────────────────────────────────────────────

    /** The styles in force. Foundation keeps one flag per style, so an inner close clears an outer one. */
    private class Style {
        var bold = false
        var italic = false
        var strike = false
    }

    private fun flatten(nodes: List<Node>, style: Style, url: String?, out: MutableList<Run>) {
        for (n in nodes) when (n) {
            is Node.Text -> out += Run(n.s, style.bold, style.italic, false, style.strike, false, url)
            is Node.Delim -> out += Run(n.ch.toString().repeat(n.count), style.bold, style.italic, false, style.strike, false, url)
            is Node.Code -> out += Run(n.s, style.bold, style.italic, false, style.strike, true, url)
            is Node.Plain -> out += Run(n.s, style.bold, style.italic, false, style.strike, false, url)
            is Node.Anchor -> flatten(n.children, style, n.url, out)
            is Node.Wrap -> {
                when (n.kind) { 'b' -> style.bold = true; 'i' -> style.italic = true; else -> style.strike = true }
                flatten(n.children, style, url, out)
                when (n.kind) { 'b' -> style.bold = false; 'i' -> style.italic = false; else -> style.strike = false }
            }
        }
    }

    /** `++a++` as iOS finds it: in the drawn text, each `++` paired with the next; the markers removed. */
    private fun underline(runs: List<Run>): List<Run> {
        val chars = StringBuilder()
        val owner = mutableListOf<Int>()
        runs.forEachIndexed { r, run -> for (ch in run.text) { chars.append(ch); owner += r } }
        val s = chars.toString()
        val marks = mutableListOf<Pair<Int, Int>>()
        var cursor = 0
        while (cursor < s.length) {
            val open = s.indexOf("++", cursor)
            if (open < 0 || open + 2 >= s.length) break
            val close = s.indexOf("++", open + 2)
            if (close < 0) break
            marks += open to close
            cursor = close + 2
        }
        if (marks.isEmpty()) return runs
        val underlined = BooleanArray(s.length)
        val removed = BooleanArray(s.length)
        for ((open, close) in marks) {
            for (k in open + 2 until close) underlined[k] = true
            removed[open] = true; removed[open + 1] = true; removed[close] = true; removed[close + 1] = true
        }
        val out = mutableListOf<Run>()
        for (k in s.indices) {
            if (removed[k]) continue
            val run = runs[owner[k]]
            out += run.copy(text = s[k].toString(), underline = run.underline || underlined[k])
        }
        return out
    }

    /** Adjacent runs drawn the same way joined, and empty runs dropped. */
    private fun merge(runs: List<Run>): List<Run> {
        val out = mutableListOf<Run>()
        for (r in runs) {
            if (r.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.copy(text = "") == r.copy(text = "")) out[out.size - 1] = last.copy(text = last.text + r.text)
            else out += r
        }
        return out
    }
}
