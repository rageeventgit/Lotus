package dev.lotus.intellij

import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.RecursionManager
import com.intellij.psi.PsiClass
import com.intellij.psi.impl.source.PsiExtensibleClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiModifierList
import com.intellij.psi.impl.java.stubs.index.JavaAnnotationIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker

/**
 * All extension methods visible in a project (sources and libraries), keyed by the
 * qualified name of the class they extend.
 */
object LotusExtensionIndex {

    class Entry(val method: PsiMethod, val target: PsiClass, val instance: Boolean)

    /**
     * Returns the extensions for [qualifiedName], or an empty list while indexing or when
     * called re-entrantly (building the index resolves classes, which can ask for augments).
     */
    fun extensionsFor(project: Project, qualifiedName: String): List<Entry> {
        if (DumbService.isDumb(project)) return emptyList()
        val all = RecursionManager.doPreventingRecursion(project, false) { byTarget(project) } ?: return emptyList()
        return all[qualifiedName].orEmpty()
    }

    private fun byTarget(project: Project): Map<String, List<Entry>> =
        CachedValuesManager.getManager(project).getCachedValue(project) {
            CachedValueProvider.Result.create(compute(project), PsiModificationTracker.getInstance(project))
        }

    private fun compute(project: Project): Map<String, List<Entry>> {
        val scope = GlobalSearchScope.allScope(project)
        val result = HashMap<String, MutableList<Entry>>()
        val annotations = JavaAnnotationIndex.getInstance().getAnnotations("Extension", project, scope)
        for (annotation in annotations) {
            if (!annotation.hasQualifiedName(LotusNames.EXTENSION)) continue
            val owner = (annotation.parent as? PsiModifierList)?.parent as? PsiClass ?: continue
            val target = LotusPsi.extensionTarget(owner) ?: continue
            val targetName = target.qualifiedName ?: continue
            // ownMethods, not methods: getMethods() asks augment providers (us) and would recurse.
            val declared = (owner as? PsiExtensibleClass)?.ownMethods ?: owner.methods.toList()
            for (method in declared) {
                if (!LotusPsi.isExtensionCandidate(method)) continue
                result.getOrPut(targetName) { mutableListOf() } += Entry(method, target, LotusPsi.isInstanceExtension(method))
            }
        }
        return result
    }
}
