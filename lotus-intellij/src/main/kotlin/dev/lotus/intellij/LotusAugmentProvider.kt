package dev.lotus.intellij

import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiClassType
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifier
import com.intellij.psi.PsiSubstitutor
import com.intellij.psi.PsiTypeParameter
import com.intellij.psi.augment.PsiAugmentProvider
import com.intellij.psi.impl.light.LightMethodBuilder
import com.intellij.psi.impl.light.LightParameter

/**
 * Adds every `@Extension` method to the class it extends, as a real member. IntelliJ's resolve,
 * completion, type inference, parameter info, quick documentation and "go to declaration" all work
 * on these members exactly as on hand-written ones.
 *
 * Because members are inherited, an extension on `Collection` also shows up on `List`, `Set`, ...
 */
class LotusAugmentProvider : PsiAugmentProvider() {

    override fun <Psi : PsiElement> getAugments(element: PsiElement, type: Class<Psi>, nameHint: String?): List<Psi> {
        if (type != PsiMethod::class.java || element !is PsiClass || element is PsiTypeParameter) return emptyList()
        val qualifiedName = element.qualifiedName ?: return emptyList()
        val entries = LotusExtensionIndex.extensionsFor(element.project, qualifiedName)
        if (entries.isEmpty()) return emptyList()
        @Suppress("UNCHECKED_CAST")
        return entries
            .filter { (nameHint == null || it.method.name == nameHint) && it.method.isValid }
            .map { LotusExtensionMethod(element, it.method, it.instance) as Psi }
    }
}

/**
 * The member view of an extension method: `PlayerExtension.heal(@This Player p, double a)` becomes
 * `Player.heal(double a)`. Navigation goes to the original static method.
 */
class LotusExtensionMethod(
    target: PsiClass,
    val extension: PsiMethod,
    val instance: Boolean,
) : LightMethodBuilder(target.manager, JavaLanguage.INSTANCE, extension.name) {

    init {
        setContainingClass(target)
        setNavigationElement(extension)
        setOriginInfo("extension from ${extension.containingClass?.name}")
        addModifier(PsiModifier.PUBLIC)
        if (!instance) addModifier(PsiModifier.STATIC)

        val substitutor = receiverSubstitutor(target)
        for (typeParameter in extension.typeParameters) {
            if (substitutor.substitutionMap[typeParameter] == null) addTypeParameter(typeParameter)
        }
        extension.returnType?.let { setMethodReturnType(substitutor.substitute(it)) }
        val parameters = extension.parameterList.parameters.let { if (instance) it.drop(1) else it.toList() }
        for (parameter in parameters) {
            addParameter(LightParameter(parameter.name, substitutor.substitute(parameter.type), this, JavaLanguage.INSTANCE, parameter.isVarArgs))
        }
        for (thrown in extension.throwsList.referencedTypes) addException(thrown)
    }

    /**
     * Maps the extension's own type parameters onto the target's: for
     * `<T> T firstOrNull(@This Collection<T> c)` on `Collection<E>`, `T` becomes `E`, so calling it on a
     * `List<String>` returns `String`.
     */
    private fun receiverSubstitutor(target: PsiClass): PsiSubstitutor {
        if (!instance) return PsiSubstitutor.EMPTY
        val receiverType = extension.parameterList.parameters.firstOrNull()?.type as? PsiClassType ?: return PsiSubstitutor.EMPTY
        val targetParameters = target.typeParameters
        val factory = JavaPsiFacade.getElementFactory(extension.project)
        var substitutor = PsiSubstitutor.EMPTY
        receiverType.parameters.forEachIndexed { index, argument ->
            val typeParameter = (argument as? PsiClassType)?.resolve() as? PsiTypeParameter ?: return@forEachIndexed
            if (typeParameter.owner == extension && index < targetParameters.size) {
                substitutor = substitutor.put(typeParameter, factory.createType(targetParameters[index]))
            }
        }
        return substitutor
    }

    override fun isValid(): Boolean = extension.isValid && containingClass?.isValid == true

    override fun isEquivalentTo(another: PsiElement?): Boolean =
        another === this || another == extension || (another is LotusExtensionMethod && another.extension == extension)

    override fun equals(other: Any?): Boolean =
        other is LotusExtensionMethod && other.extension == extension && other.containingClass == containingClass

    override fun hashCode(): Int = 31 * extension.hashCode() + (containingClass?.hashCode() ?: 0)
}
