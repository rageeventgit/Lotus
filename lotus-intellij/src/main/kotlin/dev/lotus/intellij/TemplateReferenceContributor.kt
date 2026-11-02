package dev.lotus.intellij

import com.intellij.codeInsight.completion.util.ParenthesesInsertHandler
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceExpression
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.PsiVariable
import com.intellij.psi.ResolveState
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.scope.util.PsiScopesUtil
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext

/**
 * Gives every identifier inside a string template a real reference, so Ctrl+Click, Find Usages,
 * Rename, highlighting of usages and completion all work inside `"Hello ${player.getName()}"`.
 */
class TemplateReferenceContributor : PsiReferenceContributor() {
    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(PlatformPatterns.psiElement(PsiLiteralExpression::class.java), TemplateReferenceProvider())
    }
}

private class TemplateReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val literal = element as? PsiLiteralExpression ?: return PsiReference.EMPTY_ARRAY
        val segments = LotusTemplates.segments(literal)
        if (segments.isEmpty()) return PsiReference.EMPTY_ARRAY
        val references = mutableListOf<PsiReference>()
        for (segment in segments) {
            val fragment = segment.fragment ?: continue
            for (ref in PsiTreeUtil.collectElementsOfType(fragment, PsiJavaCodeReferenceElement::class.java)) {
                val nameElement = ref.referenceNameElement ?: continue
                val range = segment.literalRangeOf(nameElement) ?: continue
                references += TemplateReference(literal, range, ref)
            }
        }
        return references.toTypedArray()
    }
}

class TemplateReference(
    literal: PsiLiteralExpression,
    range: TextRange,
    private val fragmentReference: PsiJavaCodeReferenceElement,
) : PsiReferenceBase<PsiLiteralExpression>(literal, range, true) {

    override fun resolve(): PsiElement? {
        val target = fragmentReference.advancedResolve(false).element
        // Navigate to the original static method rather than the synthetic member.
        return if (target is LotusExtensionMethod) target.extension else target
    }

    override fun getVariants(): Array<Any> {
        val qualifier = (fragmentReference as? PsiReferenceExpression)?.qualifierExpression
        return if (qualifier == null) scopeVariants() else memberVariants(qualifier.type as? PsiClassType)
    }

    /** Variables and methods visible where the literal is written. */
    private fun scopeVariants(): Array<Any> {
        val result = LinkedHashMap<String, LookupElement>()
        PsiScopesUtil.treeWalkUp(object : PsiScopeProcessor {
            override fun execute(element: PsiElement, state: ResolveState): Boolean {
                when (element) {
                    is PsiVariable -> element.name?.let { result.putIfAbsent(it, LookupElementBuilder.create(element).withTypeText(element.type.presentableText)) }
                    is PsiMethod -> if (!element.isConstructor) result.putIfAbsent(element.name + "()", methodLookup(element))
                }
                return true
            }
        }, element, null)
        return result.values.toTypedArray()
    }

    /** Members of the qualifier's type, including extension methods. */
    private fun memberVariants(type: PsiClassType?): Array<Any> {
        val psiClass: PsiClass = type?.resolve() ?: return emptyArray()
        val helper = JavaPsiFacade.getInstance(element.project).resolveHelper
        val fields = psiClass.allFields.filter { !it.hasModifierProperty(PsiModifier.STATIC) && helper.isAccessible(it, element, null) }
            .map { field: PsiField -> LookupElementBuilder.create(field).withTypeText(field.type.presentableText) }
        val methods = psiClass.allMethods.filter { !it.isConstructor && !it.hasModifierProperty(PsiModifier.STATIC) && helper.isAccessible(it, element, null) }
            .distinctBy { it.name + it.parameterList.parametersCount }
            .map { methodLookup(it) }
        return (fields + methods).toTypedArray()
    }

    private fun methodLookup(method: PsiMethod): LookupElement {
        val params = method.parameterList.parameters.joinToString(", ", "(", ")") { it.type.presentableText }
        return LookupElementBuilder.create(method, method.name)
            .withTailText(params, true)
            .withTypeText(method.returnType?.presentableText)
            .withItemTextItalic(method is LotusExtensionMethod)
            .withInsertHandler(ParenthesesInsertHandler.getInstance(method.parameterList.parametersCount > 0))
    }
}
