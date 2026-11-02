package dev.lotus.intellij.maven

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.CompileContext
import com.intellij.openapi.compiler.CompileTask
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.task.ProjectTaskManager
import org.jetbrains.idea.maven.execution.MavenRunner
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * IntelliJ's own (JPS) compiler can't run the Lotus transformer, so a Lotus project has to be built
 * through Maven ("Delegate IDE build/run actions to Maven"). This makes that hard to miss.
 */
object LotusMavenSupport {

    private const val GROUP_ID = "dev.lotus"
    private const val PLUGIN_ID = "lotus-maven-plugin"

    fun usesLotus(project: Project): Boolean {
        val manager = MavenProjectsManager.getInstance(project)
        return manager.isMavenizedProject && manager.projects.any { it.findPlugin(GROUP_ID, PLUGIN_ID) != null }
    }

    fun isDelegated(project: Project): Boolean = MavenRunner.getInstance(project).settings.isDelegateBuildToMaven

    fun needsDelegation(project: Project): Boolean = usesLotus(project) && !isDelegated(project)

    fun notify(project: Project, rebuildAfterEnabling: Boolean) {
        NotificationGroupManager.getInstance().getNotificationGroup("Lotus")
            .createNotification(
                "Lotus needs Maven to build this project",
                "IntelliJ's built-in compiler doesn't run the Lotus transformer, so extension methods and " +
                    "string templates fail with \"cannot find symbol\". Delegate IDE build/run actions to Maven to fix this.",
                NotificationType.WARNING,
            )
            .addAction(NotificationAction.createSimpleExpiring("Delegate build to Maven") {
                MavenRunner.getInstance(project).settings.isDelegateBuildToMaven = true
                if (rebuildAfterEnabling) ProjectTaskManager.getInstance(project).buildAllModules()
            })
            .notify(project)
    }
}

/** Stops a JPS build of a Lotus project early with an explanation instead of a wall of "cannot find symbol". */
class LotusBuildCheck : CompileTask {
    override fun execute(context: CompileContext): Boolean {
        val project = context.project
        if (!LotusMavenSupport.needsDelegation(project)) return true
        context.addMessage(
            CompilerMessageCategory.ERROR,
            "Lotus: this project uses the Lotus Maven plugin, which IntelliJ's built-in compiler can't run. " +
                "Enable Settings | Build, Execution, Deployment | Build Tools | Maven | Runner | " +
                "'Delegate IDE build/run actions to Maven', then build again.",
            null, -1, -1,
        )
        ApplicationManager.getApplication().invokeLater({ LotusMavenSupport.notify(project, rebuildAfterEnabling = true) }, project.disposed)
        return false
    }
}

/** Warns once on project open, before the first build fails. */
class LotusStartupCheck : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (LotusMavenSupport.needsDelegation(project)) LotusMavenSupport.notify(project, rebuildAfterEnabling = false)
    }
}
