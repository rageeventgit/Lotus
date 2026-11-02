package dev.lotus.intellij

import com.intellij.openapi.util.TextRange
import com.intellij.psi.CommonClassNames
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiCaseLabelElementList
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiModifierListOwner
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.IncorrectOperationException
import dev.lotus.core.template.TemplateParser

/** Template parsing on top of PSI: the same rules as the Maven transformer, plus offsets for the editor. */
object LotusTemplates {

    /**
     * One `$name` / `${expr}` inside a literal. Ranges are relative to the literal element.
     *
     * [fragment] is the expression parsed in the literal's scope, so it resolves locals, fields,
     * methods (and extension methods) exactly like code written outside the string would.
     */
    class Segment(
        val part: TemplateParser.Expression,
        val range: TextRange,
        val expressionRange: TextRange,
        val fragment: PsiExpression?,
        /** For each char of the unescaped expression (plus one past the end), its offset in the raw text. */
        private val rawOffsets: IntArray,
        /** Whitespace trimmed from the start of the unescaped expression before parsing [fragment]. */
        private val leading: Int,
    ) {
        /**
         * Where [element] (part of [fragment]) sits in the literal. Escapes are accounted for, so the
         * string `"no"` in `${ok ? \"yes\" : \"no\"}` maps onto the source text `\"no\"`.
         */
        fun literalRangeOf(element: PsiElement): TextRange? {
            val root = fragment ?: return null
            val start = element.textRange.startOffset - root.textRange.startOffset + leading
            val end = start + element.textLength
            if (start < 0 || end >= rawOffsets.size) return null
            val base = expressionRange.startOffset
            return TextRange(base + rawOffsets[start], base + rawOffsets[end])
        }
    }

    fun isStringLiteral(literal: PsiLiteralExpression): Boolean {
        if (literal.isTextBlock) return false
        val text = literal.text
        return text.length >= 2 && text.startsWith('"') && text.endsWith('"') &&
            literal.type?.equalsToText(CommonClassNames.JAVA_LANG_STRING) == true
    }

    /** Mirrors the transformer: no templates in annotations, `case` labels, or under `@DisableTemplates`. */
    fun templatesAllowed(literal: PsiLiteralExpression): Boolean {
        if (PsiTreeUtil.getParentOfType(literal, PsiAnnotation::class.java) != null) return false
        if (PsiTreeUtil.getParentOfType(literal, PsiCaseLabelElementList::class.java) != null) return false
        var owner = PsiTreeUtil.getParentOfType(literal, PsiModifierListOwner::class.java)
        while (owner != null) {
            if (owner.hasAnnotation(LotusNames.DISABLE_TEMPLATES)) return false
            owner = PsiTreeUtil.getParentOfType(owner, PsiModifierListOwner::class.java)
        }
        return true
    }

    fun segments(literal: PsiLiteralExpression): List<Segment> =
        CachedValuesManager.getCachedValue(literal) {
            CachedValueProvider.Result.create(compute(literal), PsiModificationTracker.getInstance(literal.project))
        }

    private fun compute(literal: PsiLiteralExpression): List<Segment> {
        if (!isStringLiteral(literal)) return emptyList()
        val raw = literal.text.let { it.substring(1, it.length - 1) }
        if (!TemplateParser.hasTemplates(raw) || !templatesAllowed(literal)) return emptyList()
        val factory = JavaPsiFacade.getElementFactory(literal.project)
        return TemplateParser.parse(raw).filterIsInstance<TemplateParser.Expression>().map { part ->
            val source = part.source
            val fragment = if (part.closed && source.isNotEmpty()) {
                try {
                    factory.createExpressionFromText(source, literal)
                } catch (_: IncorrectOperationException) {
                    null
                }
            } else {
                null
            }
            val (unescaped, rawOffsets) = unescapeWithOffsets(part.raw)
            Segment(
                part = part,
                range = TextRange(part.start + 1, part.end + 1),
                expressionRange = TextRange(part.exprStart + 1, part.exprEnd + 1),
                fragment = fragment,
                rawOffsets = rawOffsets,
                leading = unescaped.length - unescaped.trimStart().length,
            )
        }
    }

    /** [TemplateParser.unescape], also recording where each output char came from in [raw]. */
    private fun unescapeWithOffsets(raw: String): Pair<String, IntArray> {
        val text = StringBuilder(raw.length)
        val offsets = ArrayList<Int>(raw.length + 1)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c == '\\' && i + 1 < raw.length && raw[i + 1].let { it == '"' || it == '\\' || it == '\'' }) {
                offsets += i
                text.append(raw[i + 1])
                i += 2
                continue
            }
            offsets += i
            text.append(c)
            i++
        }
        offsets += raw.length
        return text.toString() to offsets.toIntArray()
    }

    /** The segment whose expression contains [offsetInLiteral] (inclusive of its end, for completion). */
    fun segmentAt(literal: PsiLiteralExpression, offsetInLiteral: Int): Segment? =
        segments(literal).firstOrNull { offsetInLiteral >= it.expressionRange.startOffset && offsetInLiteral <= it.expressionRange.endOffset }

    /** Whether any template in [literal] mentions the identifier [name]. Cheap, text based. */
    fun mentions(literal: PsiLiteralExpression, name: String): Boolean {
        val text = literal.text
        if (!text.contains(name) || !text.contains('$')) return false
        val pattern = Regex("(?<![\\w$.])${Regex.escape(name)}(?![\\w$])")
        return segments(literal).any { pattern.containsMatchIn(it.part.source) }
    }
}
