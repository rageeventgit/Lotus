package dev.lotus.intellij

import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassObjectAccessExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.util.PsiUtil

object LotusNames {
    const val EXTENSION = "dev.lotus.Extension"
    const val THIS = "dev.lotus.This"
    const val DISABLE_TEMPLATES = "dev.lotus.DisableTemplates"
}

object LotusPsi {

    /** The class extended by an `@Extension` class, or null if [extensionClass] isn't one. */
    fun extensionTarget(extensionClass: PsiClass): PsiClass? {
        val annotation = extensionClass.getAnnotation(LotusNames.EXTENSION) ?: return null
        val value = annotation.findAttributeValue("value") as? PsiClassObjectAccessExpression ?: return null
        return PsiUtil.resolveClassInType(value.operand.type)
    }

    fun isExtensionClass(psiClass: PsiClass): Boolean = psiClass.hasAnnotation(LotusNames.EXTENSION)

    /** Every `public static` method of an `@Extension` class is an extension. */
    fun isExtensionCandidate(method: PsiMethod): Boolean =
        !method.isConstructor &&
            method.hasModifierProperty(PsiModifier.STATIC) &&
            method.hasModifierProperty(PsiModifier.PUBLIC)

    /** True when the method's first parameter is the `@This` receiver. */
    fun isInstanceExtension(method: PsiMethod): Boolean =
        method.parameterList.parameters.firstOrNull()?.hasAnnotation(LotusNames.THIS) == true
}
