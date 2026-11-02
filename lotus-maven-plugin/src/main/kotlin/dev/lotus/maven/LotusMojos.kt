package dev.lotus.maven

import com.github.javaparser.ParserConfiguration.LanguageLevel
import dev.lotus.core.LotusTransformer
import org.apache.maven.plugin.AbstractMojo
import org.apache.maven.plugin.MojoExecutionException
import org.apache.maven.plugin.MojoFailureException
import org.apache.maven.plugins.annotations.LifecyclePhase
import org.apache.maven.plugins.annotations.Mojo
import org.apache.maven.plugins.annotations.Parameter
import org.apache.maven.plugins.annotations.ResolutionScope
import org.apache.maven.project.MavenProject
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * Shared logic: read the current source roots, lower Lotus syntax into [outputDirectory],
 * and point the compiler at the lowered sources instead of the originals.
 */
abstract class AbstractLotusMojo : AbstractMojo() {

    @field:Parameter(defaultValue = "\${project}", readonly = true, required = true)
    protected lateinit var project: MavenProject

    /** Expand `${expr}` and `$name` templates inside string literals. */
    @field:Parameter(property = "lotus.stringTemplates", defaultValue = "true")
    protected var stringTemplates: Boolean = true

    /** Rewrite calls to `@Extension` methods. */
    @field:Parameter(property = "lotus.extensions", defaultValue = "true")
    protected var extensions: Boolean = true

    /** Java language level for parsing, e.g. `17`, `21`, `JAVA_21`. Defaults to the newest JavaParser supports. */
    @field:Parameter(property = "lotus.languageLevel")
    protected var languageLevel: String? = null

    @field:Parameter(property = "lotus.skip", defaultValue = "false")
    protected var skip: Boolean = false

    protected abstract val outputDirectory: File
    protected abstract val sourceRoots: List<String>
    protected abstract val classpath: List<String>

    /** Adds [root] to the source roots this mojo manages. */
    protected abstract fun addSourceRoot(root: String)

    /** Name of the `MavenProject.removeXxxSourceRoot` method (Maven 3.9.10+ / 4), used reflectively. */
    protected abstract val removeMethodName: String

    /** The directory this module compiles into; excluded from extension scanning because it may be stale. */
    protected abstract val ownOutputDirectory: String

    override fun execute() {
        if (skip) {
            log.info("Lotus: skipped")
            return
        }
        // `mvn compile test-compile` (which IntelliJ's delegated build runs) walks the lifecycle twice in
        // one session. The source roots already point at our output then, so there is nothing to redo.
        val doneKey = "dev.lotus.transformed:" + outputDirectory.absolutePath
        if (project.getContextValue(doneKey) == true) {
            log.debug("Lotus: already transformed in this build")
            return
        }

        val roots = sourceRoots.map { Paths.get(it) }.filter { it.exists() && !it.isSameDirectory(outputDirectory.toPath()) }
        if (roots.isEmpty()) return

        val out = outputDirectory.toPath()
        @OptIn(kotlin.io.path.ExperimentalPathApi::class)
        if (out.exists()) out.deleteRecursively()

        val cp = classpath.map { Paths.get(it) }
        val transformer = LotusTransformer(
            sourceRoots = roots,
            classpath = cp,
            options = LotusTransformer.Options(
                stringTemplates = stringTemplates,
                extensions = extensions,
                languageLevel = parseLanguageLevel(),
                extensionScanPath = cp.filter { it != Paths.get(ownOutputDirectory) },
            ),
            log = object : LotusTransformer.Log {
                override fun info(message: String) = log.info(message)
                override fun warn(message: String) = log.warn(message)
            },
        )
        val result = try {
            transformer.transform(out)
        } catch (e: Exception) {
            throw MojoExecutionException("Lotus transformation failed: ${e.message}", e)
        }
        if (result.errors.isNotEmpty()) {
            result.errors.forEach { log.error(it.toString()) }
            throw MojoFailureException("Lotus found ${result.errors.size} error(s)")
        }
        log.info("Lotus: processed ${result.filesWritten} file(s), rewrote ${result.filesRewritten}")

        // Compile the lowered sources instead of the originals.
        sourceRoots.toList().forEach { removeSourceRoot(it) }
        addSourceRoot(outputDirectory.absolutePath)
        project.setContextValue(doneKey, true)
    }

    private fun Path.isSameDirectory(other: Path): Boolean =
        toAbsolutePath().normalize().toString().equals(other.toAbsolutePath().normalize().toString(), ignoreCase = true)

    private fun removeSourceRoot(root: String) {
        val remover = runCatching { MavenProject::class.java.getMethod(removeMethodName, String::class.java) }.getOrNull()
        if (remover != null) {
            remover.invoke(project, root)
        } else {
            // Older Maven 3 only exposes the live list.
            (sourceRoots as MutableList<String>).remove(root)
        }
    }

    private fun parseLanguageLevel(): LanguageLevel {
        val raw = languageLevel?.trim().orEmpty()
        if (raw.isEmpty()) return LanguageLevel.BLEEDING_EDGE
        val name = if (raw.first().isDigit()) "JAVA_" + raw.replace('.', '_') else raw.uppercase()
        return runCatching { LanguageLevel.valueOf(name) }.getOrElse {
            throw MojoExecutionException("Unknown lotus.languageLevel '$raw'")
        }
    }
}

/** Lowers `src/main/java` before `compile`. */
@Mojo(
    name = "transform",
    defaultPhase = LifecyclePhase.PROCESS_SOURCES,
    requiresDependencyResolution = ResolutionScope.COMPILE,
    threadSafe = true,
)
class TransformMojo : AbstractLotusMojo() {

    @field:Parameter(defaultValue = "\${project.build.directory}/lotus/main", required = true)
    private lateinit var output: File

    override val outputDirectory get() = output
    override val sourceRoots: List<String> get() = project.compileSourceRoots
    override val classpath: List<String> get() = project.compileClasspathElements
    override val ownOutputDirectory: String get() = project.build.outputDirectory
    override val removeMethodName = "removeCompileSourceRoot"
    override fun addSourceRoot(root: String) = project.addCompileSourceRoot(root)
}

/** Lowers `src/test/java` before `test-compile`; main-source extensions are visible to tests. */
@Mojo(
    name = "transform-test",
    defaultPhase = LifecyclePhase.PROCESS_TEST_SOURCES,
    requiresDependencyResolution = ResolutionScope.TEST,
    threadSafe = true,
)
class TransformTestMojo : AbstractLotusMojo() {

    @field:Parameter(defaultValue = "\${project.build.directory}/lotus/test", required = true)
    private lateinit var output: File

    override val outputDirectory get() = output
    override val sourceRoots: List<String> get() = project.testCompileSourceRoots
    override val classpath: List<String> get() = project.testClasspathElements
    override val ownOutputDirectory: String get() = project.build.testOutputDirectory
    override val removeMethodName = "removeTestCompileSourceRoot"
    override fun addSourceRoot(root: String) = project.addTestCompileSourceRoot(root)
}
