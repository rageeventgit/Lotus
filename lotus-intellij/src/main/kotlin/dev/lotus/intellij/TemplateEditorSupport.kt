package dev.lotus.intellij

import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionConfidence
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiIdentifier
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiLiteralExpression
import com.intellij.psi.PsiMethod
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ThreeState

private fun templateLiteralAt(file: PsiFile, offset: Int): PsiLiteralExpression? {
    val leaf = file.findElementAt(offset) ?: file.findElementAt(offset - 1) ?: return null
    val literal = PsiTreeUtil.getParentOfType(leaf, PsiLiteralExpression::class.java, false) ?: return null
    return if (LotusTemplates.isStringLiteral(literal) && LotusTemplates.templatesAllowed(literal)) literal else null
}

/** Pops up completion after typing `$`, `${` or `.` inside a string template. */
class TemplateTypedHandler : TypedHandlerDelegate() {
    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (file !is PsiJavaFile || (charTyped != '$' && charTyped != '{' && charTyped != '.')) return Result.CONTINUE
        val offset = editor.caretModel.offset
        val literal = templateLiteralAt(file, offset) ?: return Result.CONTINUE
        val text = editor.document.charsSequence
        val trigger = when (charTyped) {
            '$' -> true
            '{' -> offset > 0 && text[offset - 1] == '$'
            else -> LotusTemplates.segmentAt(literal, offset - literal.textRange.startOffset) != null
        }
        if (trigger) AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        return Result.CONTINUE
    }
}

/** Java normally refuses to auto-popup completion inside strings; allow it inside templates. */
class TemplateCompletionConfidence : CompletionConfidence() {
    override fun shouldSkipAutopopup(editor: Editor, contextElement: PsiElement, psiFile: PsiFile, offset: Int): ThreeState {
        val literal = templateLiteralAt(psiFile, offset) ?: return ThreeState.UNSURE
        val inTemplate = LotusTemplates.segmentAt(literal, offset - literal.textRange.startOffset) != null
        return if (inTemplate) ThreeState.NO else ThreeState.UNSURE
    }
}

/** Gutter icons linking `@Extension` classes and methods to the type they extend. */
class LotusLineMarkerProvider : RelatedItemLineMarkerProvider() {
    override fun collectNavigationMarkers(element: PsiElement, result: MutableCollection<in RelatedItemLineMarkerInfo<*>>) {
        if (element !is PsiIdentifier) return
        val owner = element.parent
        val extensionClass = when (owner) {
            is PsiClass -> owner.takeIf { it.nameIdentifier == element }
            is PsiMethod -> owner.takeIf { it.nameIdentifier == element && LotusPsi.isExtensionCandidate(it) }?.containingClass
            else -> null
        } ?: return
        if (!LotusPsi.isExtensionClass(extensionClass)) return
        val target = LotusPsi.extensionTarget(extensionClass) ?: return
        val kind = if (owner is PsiMethod) (if (LotusPsi.isInstanceExtension(owner)) "Instance extension" else "Static extension") else "Extends"
        result += NavigationGutterIconBuilder.create(LotusIcons.Lotus)
            .setTargets(target)
            .setTooltipText("$kind of ${target.qualifiedName}")
            .createLineMarkerInfo(element)
    }
}
