package dev.lotus.core.template

/**
 * Splits the raw (still escaped) contents of a Java string literal into text and
 * template expressions, Kotlin style:
 *
 *  - `$name`     → the value of `name`
 *  - `${expr}`   → the value of any Java expression
 *
 * All offsets are relative to the raw literal contents (without the surrounding quotes),
 * so both the Maven transformer and the IntelliJ plugin can map them back to source.
 *
 * This file has no dependencies so it can be shared verbatim with the IntelliJ plugin.
 */
object TemplateParser {

    sealed interface Part {
        /** Start offset (inclusive) of this part in the raw literal contents. */
        val start: Int

        /** End offset (exclusive) of this part in the raw literal contents. */
        val end: Int
    }

    /** Plain string text, still escaped exactly as written in the source. */
    data class Text(val raw: String, override val start: Int, override val end: Int) : Part

    /**
     * A `$name` or `${expr}` template.
     *
     * [exprStart]/[exprEnd] delimit the expression source inside the literal.
     * [closed] is false for a `${` that never finds its `}`.
     */
    data class Expression(
        val raw: String,
        override val start: Int,
        override val end: Int,
        val exprStart: Int,
        val exprEnd: Int,
        val braced: Boolean,
        val closed: Boolean = true,
    ) : Part {
        /** The expression as Java source, with string-literal escapes (`\"`, `\\`) undone. */
        val source: String get() = unescape(raw).trim()

        /** True when the raw expression text maps 1:1 onto the Java expression source. */
        val hasEscapes: Boolean get() = raw.contains('\\')
    }

    /** Quick check used to skip the vast majority of literals without allocating. */
    fun hasTemplates(raw: String): Boolean {
        var i = raw.indexOf('$')
        while (i >= 0 && i < raw.length - 1) {
            val next = raw[i + 1]
            if (next == '{' || isIdentStart(next)) return true
            i = raw.indexOf('$', i + 1)
        }
        return false
    }

    /** Returns the parts of [raw], or an empty list when it contains no templates. */
    fun parse(raw: String): List<Part> {
        if (!hasTemplates(raw)) return emptyList()
        val parts = mutableListOf<Part>()
        val text = StringBuilder()
        var textStart = 0
        var i = 0

        fun flushText(endExclusive: Int) {
            if (text.isNotEmpty()) parts += Text(text.toString(), textStart, endExclusive)
            text.setLength(0)
        }

        while (i < raw.length) {
            val c = raw[i]
            // Skip over escape sequences so that "\\$" etc. stay intact.
            if (c == '\\' && i + 1 < raw.length) {
                if (text.isEmpty()) textStart = i
                text.append(c).append(raw[i + 1])
                i += 2
                continue
            }
            if (c == '$' && i + 1 < raw.length) {
                val next = raw[i + 1]
                if (next == '{') {
                    flushText(i)
                    val close = findClosingBrace(raw, i + 2)
                    if (close < 0) {
                        parts += Expression(raw.substring(i + 2), i, raw.length, i + 2, raw.length, braced = true, closed = false)
                        return parts
                    }
                    parts += Expression(raw.substring(i + 2, close), i, close + 1, i + 2, close, braced = true)
                    i = close + 1
                    textStart = i
                    continue
                }
                if (isIdentStart(next)) {
                    flushText(i)
                    var j = i + 1
                    while (j < raw.length && isIdentPart(raw[j])) j++
                    parts += Expression(raw.substring(i + 1, j), i, j, i + 1, j, braced = false)
                    i = j
                    textStart = i
                    continue
                }
            }
            if (text.isEmpty()) textStart = i
            text.append(c)
            i++
        }
        flushText(raw.length)
        return parts
    }

    /** Finds the `}` matching an opened `${`, ignoring braces inside nested (escaped) strings. */
    private fun findClosingBrace(raw: String, from: Int): Int {
        var depth = 0
        var inString = false
        var i = from
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                // An escaped quote opens/closes a string literal inside the expression.
                if (raw[i + 1] == '"') inString = !inString
                i += 2
                continue
            }
            if (!inString) {
                when (c) {
                    '{' -> depth++
                    '}' -> if (depth == 0) return i else depth--
                }
            }
            i++
        }
        return -1
    }

    fun unescape(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length) {
                val n = raw[i + 1]
                if (n == '"' || n == '\\' || n == '\'') {
                    sb.append(n)
                    i += 2
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun isIdentStart(c: Char) = c != '$' && Character.isJavaIdentifierStart(c)
    private fun isIdentPart(c: Char) = c != '$' && Character.isJavaIdentifierPart(c)
}
