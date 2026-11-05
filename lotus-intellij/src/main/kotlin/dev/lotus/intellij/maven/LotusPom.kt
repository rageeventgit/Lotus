package dev.lotus.intellij.maven

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.codeInsight.intention.preview.IntentionPreviewInfo
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.Annotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import dev.lotus.intellij.LotusBuildSetup
import org.jetbrains.idea.maven.model.MavenPlugin
import org.jetbrains.idea.maven.project.MavenProjectsManager

/**
 * Reads and repairs the Lotus plugin declaration in a `pom.xml`.
 *
 * The classic mistake is putting `<goals>` directly under `<plugin>`: Maven accepts it without a word and
 * never runs the goals, so templates compile as plain strings.
 */
object LotusPom {
    const val GROUP_ID = "dev.lotus"
    const val PLUGIN_ID = "lotus-maven-plugin"
    const val ANNOTATIONS_ID = "lotus-annotations"
    const val MAIN_GOAL = "transform"
    const val TEST_GOAL = "transform-test"
    private val GOALS = listOf(MAIN_GOAL, TEST_GOAL)

    /** The Lotus `<plugin>` under `<build><plugins>` (not `<pluginManagement>`), if this POM declares one. */
    fun pluginTag(pom: XmlFile): XmlTag? =
        pom.rootTag?.findFirstSubTag("build")?.findFirstSubTag("plugins")?.findSubTags("plugin")?.firstOrNull(::isLotusPlugin)

    fun isLotusPlugin(tag: XmlTag): Boolean =
        tag.name == "plugin" && tag.getSubTagText("groupId")?.trim() == GROUP_ID && tag.getSubTagText("artifactId")?.trim() == PLUGIN_ID

    /** Goals that actually run: those inside `<executions><execution><goals>`. */
    fun boundGoals(plugin: XmlTag): Set<String> =
        plugin.findFirstSubTag("executions")?.findSubTags("execution").orEmpty()
            .flatMap { it.findFirstSubTag("goals")?.findSubTags("goal").orEmpty().asList() }
            .map { it.value.trimmedText }
            .toSet()

    fun isBound(plugin: MavenPlugin, goal: String): Boolean = plugin.executions.any { goal in it.goals }

    /** Moves stray plugin-level `<goals>` into an execution and adds whichever Lotus goals are missing. */
    fun bind(plugin: XmlTag) {
        val stray = plugin.findFirstSubTag("goals")
        val strayGoals = stray?.findSubTags("goal")?.map { it.value.trimmedText }.orEmpty()
        stray?.delete()
        val missing = (GOALS + strayGoals).distinct() - boundGoals(plugin)
        if (missing.isNotEmpty()) {
            // Reuse an existing execution: a second one without an <id> would clash with it ("default").
            val executions = plugin.findFirstSubTag("executions") ?: plugin.addSubTag(child(plugin, "executions"), false)
            val execution = executions.findFirstSubTag("execution") ?: executions.addSubTag(child(executions, "execution"), false)
            val goals = execution.findFirstSubTag("goals") ?: execution.addSubTag(child(execution, "goals"), false)
            missing.forEach { goals.addSubTag(child(goals, "goal", it), false) }
        }
        CodeStyleManager.getInstance(plugin.project).reformat(plugin)
    }

    /** Adds a bound Lotus plugin to `<build><plugins>`, matching the `lotus-annotations` version. */
    fun addPlugin(pom: XmlFile): XmlTag? {
        val root = pom.rootTag ?: return null
        val build = root.findFirstSubTag("build") ?: root.addSubTag(child(root, "build"), false)
        val plugins = build.findFirstSubTag("plugins") ?: build.addSubTag(child(build, "plugins"), false)
        val plugin = plugins.addSubTag(child(plugins, "plugin"), false)
        plugin.addSubTag(child(plugin, "groupId", GROUP_ID), false)
        plugin.addSubTag(child(plugin, "artifactId", PLUGIN_ID), false)
        plugin.addSubTag(child(plugin, "version", annotationsVersion(pom) ?: lotusVersion()), false)
        bind(plugin)
        CodeStyleManager.getInstance(pom.project).reformat(build)
        return plugin
    }

    private fun annotationsVersion(pom: XmlFile): String? =
        pom.rootTag?.findFirstSubTag("dependencies")?.findSubTags("dependency")
            ?.firstOrNull { it.getSubTagText("groupId")?.trim() == GROUP_ID && it.getSubTagText("artifactId")?.trim() == ANNOTATIONS_ID }
            ?.getSubTagText("version")?.trim()

    private fun lotusVersion(): String =
        LotusPom::class.java.getResourceAsStream("/dev/lotus/intellij/lotus.properties")
            ?.use { java.util.Properties().apply { load(it) }.getProperty("version") } ?: "1.1.0"

    private fun child(parent: XmlTag, name: String, value: String? = null): XmlTag =
        parent.createChildTag(name, parent.namespace, value, false)
}

/** Binds (or adds) the Lotus plugin in [pomFile]; offered from the POM itself and from unconverted templates. */
class FixLotusPomAction(private val pomFile: VirtualFile, private val adding: Boolean) : IntentionAction {
    override fun getText() = if (adding) "Add lotus-maven-plugin to pom.xml" else "Fix lotus-maven-plugin in pom.xml"
    override fun getFamilyName() = "Lotus"
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?) = pomFile.isValid
    override fun startInWriteAction() = false

    override fun generatePreview(project: Project, editor: Editor, file: PsiFile): IntentionPreviewInfo =
        IntentionPreviewInfo.Html("Puts the Lotus <code>transform</code> and <code>transform-test</code> goals inside " +
            "<code>&lt;executions&gt;</code> in <code>pom.xml</code>, so Maven runs them.")

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        val pom = PsiManager.getInstance(project).findFile(pomFile) as? XmlFile ?: return
        var offset = 0
        WriteCommandAction.writeCommandAction(project, pom).withName(text).run<RuntimeException> {
            val plugin = LotusPom.pluginTag(pom)?.also(LotusPom::bind) ?: LotusPom.addPlugin(pom)
            offset = plugin?.textOffset ?: 0
        }
        if (file?.virtualFile != pomFile) OpenFileDescriptor(project, pomFile, offset).navigate(true)
    }
}

/** Warns in `pom.xml` when the Lotus plugin is declared but none of its executions runs `transform`. */
class LotusPomAnnotator : Annotator {
    override fun annotate(element: PsiElement, holder: AnnotationHolder) {
        val plugin = element as? XmlTag ?: return
        if (!LotusPom.isLotusPlugin(plugin)) return
        val pom = plugin.containingFile as? XmlFile ?: return
        if (pom.rootTag?.name != "project" || LotusPom.pluginTag(pom) != plugin) return
        if (LotusPom.MAIN_GOAL in LotusPom.boundGoals(plugin)) return

        // Executions may also come from a parent POM's <pluginManagement>; trust the imported model on that.
        val pomFile = pom.virtualFile ?: pom.originalFile.virtualFile
        val model = pomFile?.let { MavenProjectsManager.getInstance(pom.project).findProject(it) }
        if (model?.findPlugin(LotusPom.GROUP_ID, LotusPom.PLUGIN_ID)?.let { LotusPom.isBound(it, LotusPom.MAIN_GOAL) } == true) return

        val message = if (plugin.findFirstSubTag("goals") != null) {
            "Lotus never runs: Maven ignores <goals> placed directly under <plugin>. Move them into <executions><execution>."
        } else {
            "Lotus never runs: lotus-maven-plugin has no execution with the 'transform' goal."
        }
        holder.newAnnotation(HighlightSeverity.WARNING, message)
            .range(plugin.findFirstSubTag("artifactId") ?: plugin)
            .apply { pomFile?.let { withFix(FixLotusPomAction(it, adding = false)) } }
            .create()
    }
}

/** Tells the template annotator when this module's Maven build won't run Lotus. */
class LotusMavenBuildSetup : LotusBuildSetup {
    override fun problem(file: PsiFile): LotusBuildSetup.Problem? {
        val project = file.project
        val vFile = file.virtualFile ?: file.originalFile.virtualFile ?: return null
        val manager = MavenProjectsManager.getInstance(project)
        if (!manager.isMavenizedProject) return null
        val module = ModuleUtilCore.findModuleForFile(vFile, project) ?: return null
        val maven = manager.findProject(module) ?: return null

        val goal = if (ProjectFileIndex.getInstance(project).isInTestSourceContent(vFile)) LotusPom.TEST_GOAL else LotusPom.MAIN_GOAL
        val plugin = maven.findPlugin(LotusPom.GROUP_ID, LotusPom.PLUGIN_ID)
        if (plugin == null && maven.findDependencies(LotusPom.GROUP_ID, LotusPom.ANNOTATIONS_ID).isEmpty()) return null // not a Lotus module
        if (plugin != null && LotusPom.isBound(plugin, goal)) return null

        // The model only changes on a Maven reload; don't keep warning once pom.xml itself is fixed.
        val pom = PsiManager.getInstance(project).findFile(maven.file) as? XmlFile
        val tag = pom?.let(LotusPom::pluginTag)
        if (tag != null && goal in LotusPom.boundGoals(tag)) return null

        val message = if (plugin == null && tag == null) {
            "pom.xml has no lotus-maven-plugin"
        } else {
            "lotus-maven-plugin in pom.xml never runs (its goals must be inside <executions><execution>)"
        }
        return LotusBuildSetup.Problem(message, pom?.let { FixLotusPomAction(maven.file, adding = tag == null) })
    }
}
