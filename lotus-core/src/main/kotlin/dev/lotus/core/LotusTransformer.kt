package dev.lotus.core

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.BinaryExpr
import com.github.javaparser.ast.expr.ClassExpr
import com.github.javaparser.ast.expr.EnclosedExpr
import com.github.javaparser.ast.expr.Expression
import com.github.javaparser.ast.expr.FieldAccessExpr
import com.github.javaparser.ast.expr.LiteralExpr
import com.github.javaparser.ast.expr.MethodCallExpr
import com.github.javaparser.ast.expr.MethodReferenceExpr
import com.github.javaparser.ast.expr.NameExpr
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.ast.expr.ObjectCreationExpr
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.expr.ThisExpr
import com.github.javaparser.ast.expr.TypeExpr
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations
import com.github.javaparser.ast.stmt.SwitchEntry
import com.github.javaparser.printer.lexicalpreservation.LexicalPreservingPrinter
import com.github.javaparser.resolution.declarations.ResolvedReferenceTypeDeclaration
import com.github.javaparser.resolution.types.ResolvedReferenceType
import com.github.javaparser.symbolsolver.JavaSymbolSolver
import com.github.javaparser.symbolsolver.javaparsermodel.JavaParserFactory
import com.github.javaparser.symbolsolver.resolution.typesolvers.ClassLoaderTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JarTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver
import com.github.javaparser.symbolsolver.resolution.typesolvers.ReflectionTypeSolver
import dev.lotus.core.template.TemplateParser
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.relativeTo
import kotlin.io.path.writeText
import kotlin.streams.toList

/**
 * Lowers Lotus syntax to plain Java, source to source:
 *
 *  - `player.heal(5)`          → `com.example.PlayerExtension.heal(player, 5)`
 *  - `Player.create("Steve")`  → `com.example.PlayerExtension.create("Steve")`
 *  - `"Hi ${p.getName()}!"`     → `("Hi " + p.getName() + "!")`
 *
 * Rewrites are done in place with JavaParser's lexical-preserving printer, so untouched code
 * keeps its exact formatting and line numbers (stack traces and debuggers stay accurate).
 */
class LotusTransformer(
    private val sourceRoots: List<Path>,
    private val classpath: List<Path>,
    private val options: Options = Options(),
    private val log: Log = Log.NONE,
) {

    data class Options(
        val stringTemplates: Boolean = true,
        val extensions: Boolean = true,
        val languageLevel: ParserConfiguration.LanguageLevel = ParserConfiguration.LanguageLevel.BLEEDING_EDGE,
        /** Classpath entries that may contain extension classes; defaults to the whole classpath. */
        val extensionScanPath: List<Path>? = null,
    )

    interface Log {
        fun info(message: String) {}
        fun warn(message: String) {}

        companion object {
            val NONE = object : Log {}
        }
    }

    data class Diagnostic(val file: Path, val line: Int, val message: String) {
        override fun toString() = "$file:$line: $message"
    }

    class Result(val filesWritten: Int, val filesRewritten: Int, val extensions: List<ExtensionMethod>, val errors: List<Diagnostic>)

    private val typeSolver = CombinedTypeSolver().apply {
        add(ReflectionTypeSolver(false))
        sourceRoots.filter { it.isDirectory() }.forEach { add(JavaParserTypeSolver(it)) }
        classpath.filter { it.isRegularFile() && it.extension == "jar" }.forEach {
            runCatching { add(JarTypeSolver(it)) }.onFailure { e -> log.warn("Lotus: cannot index $it: ${e.message}") }
        }
        val dirs = classpath.filter { it.isDirectory() }
        if (dirs.isNotEmpty()) {
            add(ClassLoaderTypeSolver(URLClassLoader(dirs.map { it.toUri().toURL() }.toTypedArray(), javaClass.classLoader)))
        }
    }

    private val parser = JavaParser(
        ParserConfiguration()
            .setLanguageLevel(options.languageLevel)
            .setSymbolResolver(JavaSymbolSolver(typeSolver)),
    )

    private val errors = mutableListOf<Diagnostic>()

    fun transform(outputDirectory: Path): Result {
        val sources = sourceRoots.filter { it.isDirectory() }.flatMap { root ->
            Files.walk(root).use { paths -> paths.filter { it.isRegularFile() && it.extension == "java" }.toList() }
                .map { root to it }
        }

        val parsed = sources.associateWith { (_, file) ->
            val result = parser.parse(file)
            if (!result.isSuccessful) {
                result.problems.forEach { log.warn("Lotus: $file: ${it.verboseMessage}") }
            }
            result.result.orElse(null)
        }

        val extensions = buildList {
            if (options.extensions) {
                parsed.forEach { (source, cu) -> if (cu != null) addAll(collectSourceExtensions(source.second, cu)) }
                val fromSources = map { it.owner }.toSet()
                addAll(ClasspathExtensionScanner.scan(options.extensionScanPath ?: classpath).filter { it.owner !in fromSources })
            }
        }
        val registry = ExtensionRegistry(extensions)
        log.info("Lotus: ${registry.all.size} extension method(s) available")

        var rewritten = 0
        for ((source, cu) in parsed) {
            val (root, file) = source
            val target = outputDirectory.resolve(file.relativeTo(root).toString())
            target.parent.createDirectories()
            if (cu == null) {
                Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                continue
            }
            val text = rewrite(file, cu, registry)
            if (text != null) rewritten++
            target.writeText(text ?: file.readText())
        }
        return Result(parsed.size, rewritten, registry.all, errors.toList())
    }

    /** Returns the rewritten source, or null when nothing changed. */
    private fun rewrite(file: Path, cu: CompilationUnit, registry: ExtensionRegistry): String? {
        val wantsTemplates = options.stringTemplates &&
            cu.findFirst(StringLiteralExpr::class.java) { TemplateParser.hasTemplates(it.value) }.isPresent
        fun wantsExtensions() = !registry.isEmpty && (
            cu.findFirst(MethodCallExpr::class.java) { registry.hasName(it.nameAsString) }.isPresent ||
                cu.findFirst(MethodReferenceExpr::class.java) { registry.hasName(it.identifier) }.isPresent
            )
        if (!wantsTemplates && !wantsExtensions()) return null

        LexicalPreservingPrinter.setup(cu)
        var changed = false
        if (wantsTemplates) changed = expandTemplates(file, cu) or changed
        // Checked after template expansion: `"${e.tag()}"` only becomes a call once the template is expanded.
        if (wantsExtensions()) {
            changed = rewriteExtensionCalls(cu, registry) or changed
            changed = rewriteMethodReferences(file, cu, registry) or changed
        }
        if (!changed) return null

        return try {
            LexicalPreservingPrinter.print(cu)
        } catch (e: Exception) {
            log.warn("Lotus: lexical printing failed for $file (${e.message}); falling back to pretty printing, line numbers may shift")
            cu.toString()
        }
    }

    // ---------------------------------------------------------------- extension discovery

    private fun collectSourceExtensions(file: Path, cu: CompilationUnit): List<ExtensionMethod> {
        val result = mutableListOf<ExtensionMethod>()
        for (type in cu.findAll(TypeDeclaration::class.java)) {
            val annotation = type.annotations.firstOrNull { isLotusAnnotation(it, cu, "Extension") } ?: continue
            val owner = type.fullyQualifiedName.orElse(null) ?: continue
            val classExpr = when (annotation) {
                is SingleMemberAnnotationExpr -> annotation.memberValue as? ClassExpr
                is NormalAnnotationExpr -> annotation.pairs.firstOrNull { it.nameAsString == "value" }?.value as? ClassExpr
                else -> null
            }
            val target = classExpr?.let {
                runCatching { it.type.resolve().describe().substringBefore('<') }.getOrNull()
            }
            if (target == null) {
                errors += Diagnostic(file, annotation.line(), "Cannot resolve @Extension target of $owner")
                continue
            }
            for (method in type.methods) {
                if (!method.isStatic || !method.isPublic) continue
                val params = method.parameters
                val instance = params.isNotEmpty() && params[0].annotations.any { isLotusAnnotation(it, cu, "This") }
                result += ExtensionMethod(
                    owner = owner,
                    name = method.nameAsString,
                    target = target,
                    parameterCount = if (instance) params.size - 1 else params.size,
                    varArgs = params.lastOrNull()?.isVarArgs == true,
                    instance = instance,
                )
            }
        }
        return result
    }

    // ---------------------------------------------------------------- extension call rewriting

    private fun rewriteExtensionCalls(cu: CompilationUnit, registry: ExtensionRegistry): Boolean {
        var changed = false
        // Reverse pre-order visits receivers before the calls made on them, so that in
        // `a.ext1().ext2()` the inner call is rewritten first and `ext2`'s receiver type resolves.
        val calls = cu.findAll(MethodCallExpr::class.java).filter { registry.hasName(it.nameAsString) }.asReversed()
        for (call in calls) {
            val extension = findExtension(call, registry) ?: continue
            val ownerExpr = StaticJavaParser.parseExpression<Expression>(extension.owner)
            if (extension.instance) {
                val receiver = if (call.scope.isPresent) call.scope.get() else ThisExpr()
                call.setScope(ownerExpr)
                call.arguments.add(0, receiver)
            } else {
                call.setScope(ownerExpr)
            }
            changed = true
        }
        return changed
    }

    private fun findExtension(call: MethodCallExpr, registry: ExtensionRegistry): ExtensionMethod? {
        val argc = call.arguments.size
        val instanceCandidates = registry.candidates(call.nameAsString, instance = true, argc)
        val staticCandidates = registry.candidates(call.nameAsString, instance = false, argc)
        if (instanceCandidates.isEmpty() && staticCandidates.isEmpty()) return null

        // A real member always wins over an extension, exactly like in Kotlin.
        if (runCatching { call.resolve() }.isSuccess) return null

        val scope = call.scope.orElse(null)
        if (scope == null) {
            if (instanceCandidates.isEmpty()) return null
            val enclosing = enclosingType(call) ?: return null
            if (call.findAncestor(MethodDeclaration::class.java).map { it.isStatic }.orElse(false)) return null
            val decl = runCatching { enclosing.resolve() }.getOrNull() ?: return null
            if (hasMember(decl, call.nameAsString, argc, staticOnly = false)) return null
            val supertypes = runCatching { decl.getAllAncestors().map { it.qualifiedName } }.getOrDefault(emptyList())
            return registry.select(instanceCandidates, decl.qualifiedName, supertypes)
        }

        if (instanceCandidates.isNotEmpty()) {
            val type = runCatching { scope.calculateResolvedType() }.getOrNull()
            if (type != null) {
                if (!type.isReferenceType) return null
                val ref = type.asReferenceType()
                val decl = ref.typeDeclaration.orElse(null)
                if (decl != null && hasMember(decl, call.nameAsString, argc, staticOnly = false)) return null
                return registry.select(instanceCandidates, ref.qualifiedName, ancestors(ref))
            }
        }

        if (staticCandidates.isNotEmpty() && (scope is NameExpr || scope is FieldAccessExpr)) {
            val decl = resolveTypeName(scope) ?: return null
            if (hasMember(decl, call.nameAsString, argc, staticOnly = true)) return null
            val supertypes = runCatching { decl.getAllAncestors().map { it.qualifiedName } }.getOrDefault(emptyList())
            return registry.select(staticCandidates, decl.qualifiedName, supertypes)
        }
        return null
    }

    /**
     * `String::shout` → `StringExtensions::shout`. An unbound reference to an instance extension
     * has exactly the shape of a reference to the static method that takes the receiver first.
     */
    private fun rewriteMethodReferences(file: Path, cu: CompilationUnit, registry: ExtensionRegistry): Boolean {
        var changed = false
        for (ref in cu.findAll(MethodReferenceExpr::class.java)) {
            val candidates = registry.named(ref.identifier)
            if (candidates.isEmpty()) continue
            val scope = ref.scope
            val decl = when (scope) {
                is TypeExpr -> runCatching { scope.type.resolve() }.getOrNull()
                    ?.takeIf { it.isReferenceType }?.asReferenceType()?.typeDeclaration?.orElse(null)
                is NameExpr, is FieldAccessExpr -> resolveTypeName(scope)
                else -> null
            }
            if (decl == null) {
                // Bound reference (`player::isAlive`): lowering it would need a lambda of unknown arity.
                val type = runCatching { scope.calculateResolvedType() }.getOrNull()?.takeIf { it.isReferenceType }?.asReferenceType()
                val memberDecl = type?.typeDeclaration?.orElse(null)
                if (type != null && memberDecl != null && memberDecl.allMethods.none { it.name == ref.identifier } &&
                    registry.select(candidates, type.qualifiedName, ancestors(type)) != null
                ) {
                    errors += Diagnostic(file, ref.line(), "Bound method references to extension methods are not supported; use a lambda or Type::${ref.identifier}")
                }
                continue
            }
            if (runCatching { decl.allMethods.any { it.name == ref.identifier } }.getOrDefault(true)) continue
            val supertypes = runCatching { decl.getAllAncestors().map { it.qualifiedName } }.getOrDefault(emptyList())
            val extension = registry.select(candidates, decl.qualifiedName, supertypes) ?: continue
            ref.setScope(TypeExpr(StaticJavaParser.parseClassOrInterfaceType(extension.owner)))
            changed = true
        }
        return changed
    }

    private fun resolveTypeName(scope: Expression): ResolvedReferenceTypeDeclaration? {
        // `player.foo()` with a variable named player must not be treated as a type.
        if (scope is NameExpr && runCatching { scope.resolve() }.isSuccess) return null
        val name = scope.toString()
        val viaContext = runCatching {
            val ref = JavaParserFactory.getContext(scope, typeSolver).solveType(name, emptyList())
            if (ref.isSolved) ref.correspondingDeclaration as? ResolvedReferenceTypeDeclaration else null
        }.getOrNull()
        if (viaContext != null) return viaContext
        val direct = runCatching { typeSolver.tryToSolveType(name) }.getOrNull()
        return if (direct != null && direct.isSolved) direct.correspondingDeclaration else null
    }

    private fun ancestors(type: ResolvedReferenceType): List<String> =
        runCatching { type.allAncestors.map { it.qualifiedName } }.getOrDefault(emptyList())

    private fun hasMember(decl: ResolvedReferenceTypeDeclaration, name: String, argc: Int, staticOnly: Boolean): Boolean =
        runCatching {
            decl.allMethods.any { usage ->
                val m = usage.declaration
                if (m.name != name || (staticOnly && !m.isStatic)) return@any false
                val n = m.numberOfParams
                if (m.hasVariadicParameter()) argc >= n - 1 else argc == n
            }
        }.getOrDefault(false)

    /** The class `this` refers to at [node], or null inside anonymous classes. */
    private fun enclosingType(node: Node): TypeDeclaration<*>? {
        var current = node.parentNode.orElse(null)
        while (current != null) {
            when (current) {
                is ObjectCreationExpr -> if (current.anonymousClassBody.isPresent) return null
                is ClassOrInterfaceDeclaration, is EnumDeclaration, is RecordDeclaration -> return current as TypeDeclaration<*>
            }
            current = current.parentNode.orElse(null)
        }
        return null
    }

    // ---------------------------------------------------------------- string templates

    private fun expandTemplates(file: Path, root: Node): Boolean {
        var changed = false
        for (literal in root.findAll(StringLiteralExpr::class.java)) {
            if (!TemplateParser.hasTemplates(literal.value) || !templatesAllowed(literal)) continue
            val replacement = buildTemplate(file, literal) ?: continue
            literal.replace(replacement)
            // Template expressions can themselves contain templated strings.
            expandTemplates(file, replacement)
            changed = true
        }
        return changed
    }

    private fun buildTemplate(file: Path, literal: StringLiteralExpr): Expression? {
        val parts = TemplateParser.parse(literal.value)
        if (parts.isEmpty()) return null
        val operands = mutableListOf<Expression>()
        if (parts.first() !is TemplateParser.Text) operands += StringLiteralExpr("")
        for (part in parts) {
            operands += when (part) {
                is TemplateParser.Text -> StringLiteralExpr(part.raw)
                is TemplateParser.Expression -> {
                    if (!part.closed) {
                        errors += Diagnostic(file, literal.line(), "Unclosed string template '\${' (use @DisableTemplates for a literal '\${')")
                        return null
                    }
                    if (part.source.isEmpty()) {
                        errors += Diagnostic(file, literal.line(), "Empty string template '\${}'")
                        return null
                    }
                    val expr = runCatching { StaticJavaParser.parseExpression<Expression>(part.source) }.getOrElse {
                        errors += Diagnostic(file, literal.line(), "Invalid template expression '${part.source}'")
                        return null
                    }
                    if (expr is NameExpr || expr is MethodCallExpr || expr is FieldAccessExpr || expr is LiteralExpr) expr else EnclosedExpr(expr)
                }
            }
        }
        val concatenation = operands.drop(1).fold(operands.first()) { acc, e -> BinaryExpr(acc, e, BinaryExpr.Operator.PLUS) }
        return EnclosedExpr(concatenation)
    }

    private fun templatesAllowed(literal: StringLiteralExpr): Boolean {
        if (literal.findAncestor(AnnotationExpr::class.java).isPresent) return false
        val switchEntry = literal.findAncestor(SwitchEntry::class.java).orElse(null)
        if (switchEntry != null && switchEntry.labels.any { it.isAncestorOf(literal) || it === literal }) return false
        val cu = literal.findCompilationUnit().orElse(null)
        var current: Node? = literal
        while (current != null) {
            if (current is NodeWithAnnotations<*> && current.annotations.any { isLotusAnnotation(it, cu, "DisableTemplates") }) return false
            current = current.parentNode.orElse(null)
        }
        return true
    }

    // ---------------------------------------------------------------- helpers

    /** Syntactic check that [annotation] is `dev.lotus.<simpleName>`, without needing the annotations jar. */
    private fun isLotusAnnotation(annotation: AnnotationExpr, cu: CompilationUnit?, simpleName: String): Boolean {
        val name = annotation.nameAsString
        if (name == "dev.lotus.$simpleName") return true
        if (name != simpleName) return false
        if (cu == null) return true
        if (cu.packageDeclaration.map { it.nameAsString == "dev.lotus" }.orElse(false)) return true
        return cu.imports.any { !it.isStatic && (it.nameAsString == "dev.lotus.$simpleName" || (it.isAsterisk && it.nameAsString == "dev.lotus")) }
    }

    private fun Node.line(): Int = range.map { it.begin.line }.orElse(0)
}
