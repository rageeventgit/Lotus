package dev.lotus.core

import com.github.javaparser.JavaParser
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.StaticJavaParser
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.EnumConstantDeclaration
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
import com.github.javaparser.ast.expr.LambdaExpr
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
import com.github.javaparser.resolution.logic.FunctionalInterfaceLogic
import com.github.javaparser.resolution.types.ResolvedReferenceType
import com.github.javaparser.resolution.types.ResolvedType
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

    /** Parses at the project's language level and adds Lombok-generated members (see [LombokSupport]). */
    private fun parserConfiguration() = ParserConfiguration()
        .setLanguageLevel(options.languageLevel)
        .also { it.processors.add { LombokSupport.processor } }

    private val typeSolver = CombinedTypeSolver().apply {
        add(ReflectionTypeSolver(false))
        // Declarations from other source files sometimes resolve through their own nodes (record accessors
        // do), so those files need a symbol resolver too.
        val solver = this
        sourceRoots.filter { it.isDirectory() }.forEach {
            add(JavaParserTypeSolver(it, parserConfiguration().setSymbolResolver(JavaSymbolSolver(solver))))
        }
        classpath.filter { it.isRegularFile() && it.extension == "jar" }.forEach {
            runCatching { add(JarTypeSolver(it)) }.onFailure { e -> log.warn("Lotus: cannot index $it: ${e.message}") }
        }
        val dirs = classpath.filter { it.isDirectory() }
        if (dirs.isNotEmpty()) {
            add(ClassLoaderTypeSolver(URLClassLoader(dirs.map { it.toUri().toURL() }.toTypedArray(), javaClass.classLoader)))
        }
    }

    private val parser = JavaParser(parserConfiguration().setSymbolResolver(JavaSymbolSolver(typeSolver)))

    /** For the copy that gets printed: no generated members, which the lexical-preserving printer can't handle. */
    private val plainParser = JavaParser(ParserConfiguration().setLanguageLevel(options.languageLevel))

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

        // `cu` carries Lombok's generated members, which resolution needs but which have no source position,
        // so it can't be printed lexically. Every edit is made to both `cu` and a plain parse of the same file
        // (the "mirror"), and the mirror is printed. The generated members contain no calls, literals or method
        // references, so both trees list those nodes in the same order.
        val mirror = plainParser.parse(file).result.orElse(null) ?: return null
        LexicalPreservingPrinter.setup(mirror)
        var changed = false
        if (wantsTemplates) changed = expandTemplates(file, cu, mirror) or changed
        // Checked after template expansion: `"${e.tag()}"` only becomes a call once the template is expanded.
        if (wantsExtensions()) {
            changed = rewriteExtensionCalls(file, cu, mirror, registry) or changed
            changed = rewriteMethodReferences(file, cu, mirror, registry) or changed
        }
        if (!changed) return null

        return try {
            LexicalPreservingPrinter.print(mirror)
        } catch (e: Exception) {
            log.warn("Lotus: lexical printing failed for $file (${e.message}); falling back to pretty printing, line numbers may shift")
            mirror.toString()
        }
    }

    /** Nodes of [type] in [cu] matched with the same nodes in [mirror], or null (with a warning) if they don't line up. */
    private fun <T : Node> paired(file: Path, cu: Node, mirror: Node, type: Class<T>, filter: (T) -> Boolean): List<Pair<T, T>>? {
        val nodes = cu.findAll(type) { filter(it) }
        val twins = mirror.findAll(type) { filter(it) }
        if (nodes.size != twins.size) {
            log.warn("Lotus: $file: internal mismatch while rewriting ${type.simpleName}s; skipped them")
            return null
        }
        return nodes.zip(twins)
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

    private fun rewriteExtensionCalls(file: Path, cu: CompilationUnit, mirror: CompilationUnit, registry: ExtensionRegistry): Boolean {
        var changed = false
        // Reverse pre-order visits receivers before the calls made on them, so that in
        // `a.ext1().ext2()` the inner call is rewritten first and `ext2`'s receiver type resolves.
        val calls = paired(file, cu, mirror, MethodCallExpr::class.java) { registry.hasName(it.nameAsString) } ?: return false
        for ((call, twin) in calls.asReversed()) {
            val extension = findExtension(file, call, registry) ?: continue
            lower(call, extension)
            lower(twin, extension)
            changed = true
        }
        return changed
    }

    private fun lower(call: MethodCallExpr, extension: ExtensionMethod) {
        val ownerExpr = StaticJavaParser.parseExpression<Expression>(extension.owner)
        if (extension.instance) {
            val receiver = if (call.scope.isPresent) call.scope.get() else ThisExpr()
            call.setScope(ownerExpr)
            call.arguments.add(0, receiver)
        } else {
            call.setScope(ownerExpr)
        }
    }

    private fun findExtension(file: Path, call: MethodCallExpr, registry: ExtensionRegistry): ExtensionMethod? {
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
            val type = receiverType(scope)
            if (type != null) {
                if (!type.isReferenceType) return null
                val ref = type.asReferenceType()
                val decl = ref.typeDeclaration.orElse(null)
                if (decl != null && hasMember(decl, call.nameAsString, argc, staticOnly = false)) return null
                return registry.select(instanceCandidates, ref.qualifiedName, ancestors(ref))
            }
        }

        if (staticCandidates.isNotEmpty() && (scope is NameExpr || scope is FieldAccessExpr)) {
            val decl = resolveTypeName(scope)
            if (decl != null) {
                if (hasMember(decl, call.nameAsString, argc, staticOnly = true)) return null
                val supertypes = runCatching { decl.getAllAncestors().map { it.qualifiedName } }.getOrDefault(emptyList())
                return registry.select(staticCandidates, decl.qualifiedName, supertypes)
            }
        }
        if (instanceCandidates.isNotEmpty()) {
            // javac would only say "cannot find symbol" in the lowered copy; name the real cause instead.
            log.warn(
                "Lotus: $file:${call.line()}: can't work out the type of `$scope`, so `${call.nameAsString}(...)` " +
                    "was left as is. If it's an extension call, javac will report it as missing.",
            )
        }
        return null
    }

    /**
     * `String::shout` → `StringExtensions::shout`. An unbound reference to an instance extension
     * has exactly the shape of a reference to the static method that takes the receiver first.
     */
    private fun rewriteMethodReferences(file: Path, cu: CompilationUnit, mirror: CompilationUnit, registry: ExtensionRegistry): Boolean {
        var changed = false
        val refs = paired(file, cu, mirror, MethodReferenceExpr::class.java) { registry.hasName(it.identifier) } ?: return false
        for ((ref, twin) in refs) {
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
                val type = receiverType(scope)?.takeIf { it.isReferenceType }?.asReferenceType()
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
            twin.setScope(TypeExpr(StaticJavaParser.parseClassOrInterfaceType(extension.owner)))
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

    /** The type of a call's receiver, including implicitly typed lambda parameters JavaParser can't infer itself. */
    private fun receiverType(scope: Expression): ResolvedType? {
        val type = runCatching { scope.calculateResolvedType() }.getOrNull() ?: lambdaParameterType(scope)
        return type?.let(::unwrap)
    }

    /** Lambda parameters come back as `? super Player`-style constraints or wildcards; the receiver has the bound. */
    private fun unwrap(type: ResolvedType): ResolvedType? = when {
        type.isConstraint -> unwrap(type.asConstraintType().bound)
        type.isWildcard -> type.asWildcard().takeIf { it.isBounded }?.boundedType?.let(::unwrap)
        else -> type
    }

    /**
     * JavaParser can't type the parameters of a lambda passed to a constructor or an enum constant
     * (`new Holder(p -> ...)`, `RANSOM(p -> ...)`). Takes them from the constructor parameter's functional interface.
     */
    private fun lambdaParameterType(scope: Expression): ResolvedType? {
        val name = (scope as? NameExpr)?.nameAsString ?: return null
        val lambda = generateSequence(scope.parentNode.orElse(null)) { it.parentNode.orElse(null) }
            .filterIsInstance<LambdaExpr>()
            .firstOrNull { l -> l.parameters.any { it.nameAsString == name } } ?: return null
        val index = lambda.parameters.indexOfFirst { it.nameAsString == name }
        if (!lambda.parameters[index].type.isUnknownType) return null

        val target: ResolvedType = runCatching {
            when (val parent = lambda.parentNode.orElse(null)) {
                is ObjectCreationExpr -> {
                    val position = parent.arguments.indexOf(lambda)
                    val constructor = runCatching { parent.resolve() }.getOrNull()
                        ?: parent.type.resolve().asReferenceType().typeDeclaration.orElse(null)
                            ?.constructors?.singleOrNull { it.numberOfParams == parent.arguments.size }
                    constructor?.getParam(position)?.type
                }
                is EnumConstantDeclaration -> {
                    val position = parent.arguments.indexOf(lambda)
                    val enum = parent.parentNode.orElse(null) as? EnumDeclaration
                    enum?.resolve()?.constructors?.singleOrNull { it.numberOfParams == parent.arguments.size }?.getParam(position)?.type
                }
                else -> null
            }
        }.getOrNull() ?: return null

        val method = FunctionalInterfaceLogic.getFunctionalMethod(target).orElse(null) ?: return null
        if (index >= method.noParams) return null
        val parameter = method.getParamType(index)
        // `Consumer<Player>.accept(T)`: put the interface's type arguments in.
        return if (target.isReferenceType) target.asReferenceType().useThisTypeParametersOnTheGivenType(parameter) else parameter
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

    /** Expands the templates under [root], and the same ones under [mirror] when given. */
    private fun expandTemplates(file: Path, root: Node, mirror: Node?): Boolean {
        val hasTemplates = { literal: StringLiteralExpr -> TemplateParser.hasTemplates(literal.value) }
        val literals: List<Pair<StringLiteralExpr, StringLiteralExpr?>> = if (mirror != null) {
            paired(file, root, mirror, StringLiteralExpr::class.java, hasTemplates) ?: return false
        } else {
            root.findAll(StringLiteralExpr::class.java) { hasTemplates(it) }.map { it to null }
        }
        var changed = false
        for ((literal, twin) in literals) {
            if (!templatesAllowed(literal)) continue
            val replacement = buildTemplate(file, literal) ?: continue
            literal.replace(replacement)
            // Template expressions can themselves contain templated strings.
            expandTemplates(file, replacement, null)
            twin?.replace(replacement.clone())
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
