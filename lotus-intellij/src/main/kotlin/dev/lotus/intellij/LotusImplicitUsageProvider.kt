package dev.lotus.intellij

import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiLocalVariable
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiParameter
import com.intellij.psi.PsiVariable
import com.intellij.psi.util.PsiTreeUtil

/**
 * Keeps "unused" inspections quiet about things Lotus uses:
 * - `@Extension` classes and their methods (call sites point at the synthetic member)
 * - variables that are only read from string templates
 */
class LotusImplicitUsageProvider : ImplicitUsageProvider {

    override fun isImplicitUsage(element: PsiElement): Boolean = when (element) {
        is PsiClass -> LotusPsi.isExtensionClass(element)
        is PsiMethod -> element.containingClass?.let(LotusPsi::isExtensionClass) == true && LotusPsi.isExtensionCandidate(element)
        else -> isReadFromTemplate(element)
    }

    override fun isImplicitRead(element: PsiElement): Boolean = isReadFromTemplate(element)

    override fun isImplicitWrite(element: PsiElement): Boolean = false

    private fun isReadFromTemplate(element: PsiElement): Boolean {
        val variable = element as? PsiVariable ?: return false
        val name = variable.name ?: return false
        val searchRoot: PsiElement = when (variable) {
            is PsiParameter -> variable.declarationScope // the whole method, lambda, catch or for-each
            is PsiLocalVariable -> variable.parent?.parent ?: return false // the enclosing code block
            is PsiField -> variable.containingClass ?: return false
            else -> return false
        }
        var found = false
        PsiTreeUtil.processElements(searchRoot) {
            if (it is PsiLiteralExpression && LotusTemplates.mentions(it, name)) found = true
            !found
        }
        return found
    }
}
