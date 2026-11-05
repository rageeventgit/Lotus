package dev.lotus.intellij

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiFile

/**
 * Lets a build-tool integration (Maven) report that Lotus won't run for a file, so its templates
 * would silently compile as plain strings.
 */
interface LotusBuildSetup {
    /** Why Lotus won't transform [file], or null if its build is fine or isn't one this integration knows. */
    fun problem(file: PsiFile): Problem?

    class Problem(val message: String, val fix: IntentionAction?)

    companion object {
        val EP: ExtensionPointName<LotusBuildSetup> = ExtensionPointName.create("dev.lotus.buildSetup")

        fun problem(file: PsiFile): Problem? = EP.extensionList.firstNotNullOfOrNull { it.problem(file) }
    }
}
