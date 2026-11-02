package dev.lotus.intellij

import com.intellij.ide.highlighter.JavaHighlightingColors
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiMethodCallExpression
import com.intellij.psi.PsiMethodReferenceExpression
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiTypes
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.util.TypeConversionUtil

/**
 * - colors extension calls so they're distinguishable from real members
 * - highlights and type-checks `${...}` string templates
 * - validates `@Extension` classes
 */
class LotusAnnotator : Annotator {

    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        when (element) {
            is PsiMethodCallExpression -> annotateCall(element, holder)
            is PsiMethodReferenceExpression -> annotateMethodReference(element, holder)
            is PsiLiteralExpression -> annotateTemplates(element, holder)
            is PsiMethod -> annotateExtensionMethod(element, holder)
            is PsiClass -> annotateExtensionClass(element, holder)
        }
    }

    // ------------------------------------------------------------------ extension calls

    private fun annotateCall(call: PsiMethodCallExpression, holder: AnnotationHolder) {
        val method = call.resolveMethod() as? LotusExtensionMethod ?: return
        val nameElement = call.methodExpression.referenceNameElement ?: return
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
            .range(nameElement)
            .textAttributes(LotusColors.EXTENSION_CALL)
            .tooltip("Extension method from ${method.extension.containingClass?.qualifiedName}")
            .create()
    }

    private fun annotateMethodReference(ref: PsiMethodReferenceExpression, holder: AnnotationHolder) {
        val method = ref.resolve() as? LotusExtensionMethod ?: return
        val nameElement = ref.referenceNameElement ?: return
        if (method.instance && ref.qualifierExpression != null && !isTypeQualifier(ref)) {
            holder.newAnnotation(HighlightSeverity.ERROR, "Bound method references to extension methods are not supported; use a lambda or Type::${method.name}")
                .range(nameElement)
                .create()
            return
        }
        holder.newSilentAnnotation(HighlightSeverity.INFORMATION).range(nameElement).textAttributes(LotusColors.EXTENSION_CALL).create()
    }

    private fun isTypeQualifier(ref: PsiMethodReferenceExpression): Boolean {
        val qualifier = ref.qualifierExpression as? PsiReferenceExpression ?: return ref.qualifierType != null
        return qualifier.resolve() is PsiClass
    }

    // ------------------------------------------------------------------ string templates

    private fun annotateTemplates(literal: PsiLiteralExpression, holder: AnnotationHolder) {
        val segments = LotusTemplates.segments(literal)
        if (segments.isEmpty()) return
        val base = literal.textRange.startOffset
        for (segment in segments) {
            val part = segment.part
            val whole = segment.range.shiftRight(base)
            val expression = segment.expressionRange.shiftRight(base)

            // `$` / `${` and `}` delimiters, then the expression itself in "code" colors.
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(TextRange(whole.startOffset, expression.startOffset))
                .textAttributes(LotusColors.TEMPLATE_ENTRY).create()
            if (part.braced && part.closed) {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(TextRange(expression.endOffset, whole.endOffset))
                    .textAttributes(LotusColors.TEMPLATE_ENTRY).create()
            }
            val fragment = segment.fragment
            if (fragment != null) {
                highlightCode(segment, fragment, base, holder)
            } else if (!expression.isEmpty) {
                // Not parseable: at least make it look like code rather than string text.
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(expression).textAttributes(LotusColors.TEMPLATE_EXPRESSION).create()
            }

            when {
                !part.closed -> holder.newAnnotation(HighlightSeverity.ERROR, "Unclosed string template: missing '}'")
                    .range(whole).create()
                part.source.isEmpty() -> holder.newAnnotation(HighlightSeverity.ERROR, "Empty string template")
                    .range(whole).create()
                segment.fragment == null -> holder.newAnnotation(HighlightSeverity.ERROR, "Invalid expression in string template")
                    .range(expression).create()
                else -> checkFragment(segment, segment.fragment, base, expression, holder)
            }
        }
    }

    /**
     * Java syntax highlighting for the code inside a template, using the editor's own Java colors:
     * keywords, numbers, strings and operators by token, and names by what they resolve to (local,
     * parameter, field, method, extension method, class).
     *
     * The colors are applied as complete attributes rather than by key alone: many schemes give locals,
     * calls or operators no foreground of their own, which would otherwise leave them string-colored.
     */
    private fun highlightCode(segment: LotusTemplates.Segment, fragment: PsiExpression, base: Int, holder: AnnotationHolder) {
        val scheme = EditorColorsManager.getInstance().globalScheme
        for ((range, key) in codeTokens(segment, fragment)) {
            holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                .range(range.shiftRight(base))
                .enforcedTextAttributes(codeAttributes(scheme, key))
                .create()
        }
    }

    companion object {
        /** Each token of the template code in [literal] with the color key it is drawn with; ranges are literal-relative. */
        internal fun codeTokens(literal: PsiLiteralExpression): List<Pair<TextRange, TextAttributesKey>> =
            LotusTemplates.segments(literal).flatMap { segment -> segment.fragment?.let { codeTokens(segment, it) }.orEmpty() }

        private fun codeTokens(segment: LotusTemplates.Segment, fragment: PsiExpression): List<Pair<TextRange, TextAttributesKey>> {
            val highlighter = SyntaxHighlighterFactory.getSyntaxHighlighter(JavaLanguage.INSTANCE, fragment.project, null)
            val result = mutableListOf<Pair<TextRange, TextAttributesKey>>()
            for (leaf in SyntaxTraverser.psiTraverser(fragment).filter { it.firstChild == null && it.textLength > 0 }) {
                if (leaf is PsiWhiteSpace || leaf is PsiErrorElement) continue
                val key = (if (leaf is PsiIdentifier) nameKey(leaf) else null)
                    ?: highlighter?.getTokenHighlights(leaf.node.elementType)?.lastOrNull()
                    ?: LotusColors.TEMPLATE_EXPRESSION
                result += (segment.literalRangeOf(leaf) ?: continue) to key
            }
            return result
        }

        /** The semantic color for a name inside a template, the same way Java highlights it outside strings. */
        private fun nameKey(identifier: PsiIdentifier): TextAttributesKey? {
            val reference = identifier.parent as? PsiJavaCodeReferenceElement ?: return null
            return when (val target = reference.advancedResolve(false).element) {
                is LotusExtensionMethod -> LotusColors.EXTENSION_CALL
                is PsiMethod -> when {
                    target.isConstructor -> JavaHighlightingColors.CONSTRUCTOR_CALL_ATTRIBUTES
                    target.hasModifierProperty(PsiModifier.STATIC) -> JavaHighlightingColors.STATIC_METHOD_ATTRIBUTES
                    else -> JavaHighlightingColors.METHOD_CALL_ATTRIBUTES
                }
                is PsiLocalVariable -> JavaHighlightingColors.LOCAL_VARIABLE_ATTRIBUTES
                is PsiParameter -> JavaHighlightingColors.PARAMETER_ATTRIBUTES
                is PsiField -> when {
                    !target.hasModifierProperty(PsiModifier.STATIC) -> JavaHighlightingColors.INSTANCE_FIELD_ATTRIBUTES
                    target.hasModifierProperty(PsiModifier.FINAL) -> JavaHighlightingColors.STATIC_FINAL_FIELD_ATTRIBUTES
                    else -> JavaHighlightingColors.STATIC_FIELD_ATTRIBUTES
                }
                is PsiClass -> if (target.isInterface) JavaHighlightingColors.INTERFACE_NAME_ATTRIBUTES else JavaHighlightingColors.CLASS_NAME_ATTRIBUTES
                else -> null
            }
        }

        /** [key]'s attributes, with "Template expression" (default text color) filling in anything it leaves unset. */
        internal fun codeAttributes(scheme: EditorColorsScheme, key: TextAttributesKey): TextAttributes {
            val base = scheme.getAttributes(LotusColors.TEMPLATE_EXPRESSION)?.clone() ?: TextAttributes()
            base.backgroundColor = null // keep the current-line and selection backgrounds visible
            // Some schemes leave plain text without an explicit color; never fall through to the string's green.
            if (base.foregroundColor == null) base.foregroundColor = scheme.defaultForeground
            val own = scheme.getAttributes(key) ?: return base
            return TextAttributes.merge(base, own)
        }
    }

    private fun checkFragment(segment: LotusTemplates.Segment, fragment: PsiExpression, base: Int, fallback: TextRange, holder: AnnotationHolder) {
        fun rangeOf(element: PsiElement): TextRange = segment.literalRangeOf(element)?.shiftRight(base) ?: fallback

        for (ref in PsiTreeUtil.collectElementsOfType(fragment, PsiJavaCodeReferenceElement::class.java)) {
            if (ref.advancedResolve(false).element != null || ref.multiResolve(false).isNotEmpty()) continue
            val name = ref.referenceNameElement ?: ref
            holder.newAnnotation(HighlightSeverity.ERROR, "Cannot resolve symbol '${ref.referenceName}'")
                .range(rangeOf(name)).create()
            return
        }
        for (call in PsiTreeUtil.collectElementsOfType(fragment, PsiMethodCallExpression::class.java)) {
            if (call.resolveMethod() == null) {
                holder.newAnnotation(HighlightSeverity.ERROR, "Cannot resolve method '${call.methodExpression.referenceName}'")
                    .range(rangeOf(call.methodExpression.referenceNameElement ?: call)).create()
                return
            }
        }
        if (fragment.type == PsiTypes.voidType()) {
            holder.newAnnotation(HighlightSeverity.ERROR, "A void expression cannot be used in a string template")
                .range(rangeOf(fragment)).create()
        }
    }

    // ------------------------------------------------------------------ @Extension classes

    private fun annotateExtensionClass(psiClass: PsiClass, holder: AnnotationHolder) {
        val annotation = psiClass.getAnnotation(LotusNames.EXTENSION) ?: return
        if (LotusPsi.extensionTarget(psiClass) == null) {
            holder.newAnnotation(HighlightSeverity.ERROR, "@Extension needs a class literal, e.g. @Extension(Player.class)")
                .range(annotation).create()
        }
    }

    private fun annotateExtensionMethod(method: PsiMethod, holder: AnnotationHolder) {
        val owner = method.containingClass ?: return
        if (!LotusPsi.isExtensionClass(owner)) return
        val target = LotusPsi.extensionTarget(owner) ?: return
        val nameElement = method.nameIdentifier ?: return
        val parameters = method.parameterList.parameters

        parameters.drop(1).firstOrNull { it.hasAnnotation(LotusNames.THIS) }?.let {
            holder.newAnnotation(HighlightSeverity.ERROR, "@This must annotate the first parameter").range(it).create()
        }
        if (!LotusPsi.isInstanceExtension(method)) return

        if (!method.hasModifierProperty(PsiModifier.STATIC)) {
            holder.newAnnotation(HighlightSeverity.ERROR, "Extension methods must be static").range(nameElement).create()
        } else if (!method.hasModifierProperty(PsiModifier.PUBLIC)) {
            holder.newAnnotation(HighlightSeverity.WARNING, "Only public static methods become extensions").range(nameElement).create()
        }
        val receiver = parameters[0]
        val receiverClass = com.intellij.psi.util.PsiUtil.resolveClassInType(receiver.type)
        if (receiverClass != null && receiverClass != target && !target.isInheritor(receiverClass, true)) {
            holder.newAnnotation(
                HighlightSeverity.ERROR,
                "@This parameter must accept ${target.name}, but its type is ${TypeConversionUtil.erasure(receiver.type).presentableText}",
            ).range(receiver.typeElement ?: receiver).create()
        }
    }
}
