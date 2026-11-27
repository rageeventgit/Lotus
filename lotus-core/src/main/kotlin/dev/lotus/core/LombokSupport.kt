package dev.lotus.core

import com.github.javaparser.ParseResult
import com.github.javaparser.ParserConfiguration
import com.github.javaparser.Processor
import com.github.javaparser.ast.CompilationUnit
import com.github.javaparser.ast.Modifier
import com.github.javaparser.ast.Node
import com.github.javaparser.ast.NodeList
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration
import com.github.javaparser.ast.body.ConstructorDeclaration
import com.github.javaparser.ast.body.EnumDeclaration
import com.github.javaparser.ast.body.FieldDeclaration
import com.github.javaparser.ast.body.MethodDeclaration
import com.github.javaparser.ast.body.Parameter
import com.github.javaparser.ast.body.RecordDeclaration
import com.github.javaparser.ast.body.TypeDeclaration
import com.github.javaparser.ast.expr.AnnotationExpr
import com.github.javaparser.ast.expr.BooleanLiteralExpr
import com.github.javaparser.ast.expr.NormalAnnotationExpr
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr
import com.github.javaparser.ast.expr.StringLiteralExpr
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations
import com.github.javaparser.ast.stmt.BlockStmt
import com.github.javaparser.ast.type.ClassOrInterfaceType
import com.github.javaparser.ast.type.PrimitiveType
import com.github.javaparser.ast.type.Type
import com.github.javaparser.ast.type.TypeParameter
import com.github.javaparser.ast.type.VoidType

/**
 * Adds the members Lombok generates (getters, setters, withers, constructors, builders) to parsed classes.
 *
 * javac sees them because Lombok runs inside it, but JavaParser doesn't, so without this a call such as
 * `EnchantProvider.of().killed((source, target, data) -> source.ext())`, where `killed` is a Lombok fluent
 * setter, can't be resolved, the lambda's parameter types stay unknown and `source.ext()` isn't rewritten.
 *
 * Only the tree used for resolution gets these members; Lotus prints a plain parse of the file instead.
 */
internal object LombokSupport {

    /** Runs [addGeneratedMembers] on every compilation unit a parser produces. */
    val processor: Processor = object : Processor() {
        override fun postProcess(result: ParseResult<out Node>, configuration: ParserConfiguration) {
            (result.result.orElse(null) as? CompilationUnit)?.let(::addGeneratedMembers)
        }
    }

    fun addGeneratedMembers(cu: CompilationUnit) {
        if (cu.imports.none { it.nameAsString == "lombok" || it.nameAsString.startsWith("lombok.") } &&
            !cu.findFirst(AnnotationExpr::class.java) { it.nameAsString.startsWith("lombok.") }.isPresent
        ) return
        // Snapshot first: builders add nested types while we go.
        for (type in cu.findAll(TypeDeclaration::class.java).toList()) {
            when (type) {
                is ClassOrInterfaceDeclaration -> if (!type.isInterface) addToClass(cu, type)
                is EnumDeclaration -> addToClass(cu, type)
                is RecordDeclaration -> addToRecord(cu, type)
            }
        }
    }

    // ------------------------------------------------------------------ classes and enums

    private class Field(val name: String, val type: Type, val declaration: FieldDeclaration, val initialized: Boolean)

    private fun addToClass(cu: CompilationUnit, type: TypeDeclaration<*>) {
        val value = has(cu, type, "Value")
        val data = has(cu, type, "Data")
        val fields = type.fields.filter { !it.isStatic }.flatMap { declaration ->
            declaration.variables.map { Field(it.nameAsString, it.type, declaration, it.initializer.isPresent) }
        }
        val classAccessors = accessors(cu, type)
        val self = selfType(type)

        for (field in fields) {
            val accessors = accessors(cu, field.declaration).orElse(classAccessors)
            val fluent = accessors.fluent ?: false
            val chain = accessors.chain ?: fluent
            val final = field.declaration.isFinal || value
            val capitalized = field.name.replaceFirstChar(Char::uppercaseChar)

            if (generates(cu, type, field.declaration, "Getter", classWide = data || value)) {
                val prefix = if (field.type == PrimitiveType.booleanType()) "is" else "get"
                addMethod(type, if (fluent) field.name else prefix + capitalized, field.type)
            }
            if (!final && generates(cu, type, field.declaration, "Setter", classWide = data)) {
                addMethod(type, if (fluent) field.name else "set$capitalized", if (chain) self else VoidType(), field)
            }
            if (generates(cu, type, field.declaration, "With", classWide = false)) {
                addMethod(type, "with$capitalized", self, field)
            }
        }

        if (type is ClassOrInterfaceDeclaration || type is EnumDeclaration) {
            val all = fields.filter { !(it.declaration.isFinal && it.initialized) }
            val required = fields.filter {
                !it.initialized && (it.declaration.isFinal || value || it.declaration.annotations.any { a -> a.nameAsString.endsWith("NonNull") })
            }
            val explicit = type.constructors.isNotEmpty()
            val noArgs = annotation(cu, type, "NoArgsConstructor")
            val allArgs = annotation(cu, type, "AllArgsConstructor")
            val requiredArgs = annotation(cu, type, "RequiredArgsConstructor")
            noArgs?.let { addConstructor(type, emptyList(), staticName(it), self) }
            allArgs?.let { addConstructor(type, all, staticName(it), self) }
            requiredArgs?.let { addConstructor(type, required, staticName(it), self) }
            if (!explicit && noArgs == null && allArgs == null && requiredArgs == null) {
                if (value) addConstructor(type, all, null, self) else if (data) addConstructor(type, required, null, self)
            }
        }

        if (type is ClassOrInterfaceDeclaration && has(cu, type, "Builder")) {
            addBuilder(cu, type, fields.filter { !(it.declaration.isFinal && it.initialized) }.map { it.name to it.type })
        }
    }

    // ------------------------------------------------------------------ records

    private fun addToRecord(cu: CompilationUnit, record: RecordDeclaration) {
        val components = record.parameters.map { it.nameAsString to it.type }
        val self = selfType(record)
        for ((name, type) in components) {
            val component = record.parameters.first { it.nameAsString == name }
            if (has(cu, record, "With") || component.annotations.any { isLombok(cu, it, "With") }) {
                addMethod(record, "with" + name.replaceFirstChar(Char::uppercaseChar), self, type, name)
            }
        }
        if (has(cu, record, "Builder")) addBuilder(cu, record, components)
    }

    // ------------------------------------------------------------------ builders

    private fun addBuilder(cu: CompilationUnit, type: TypeDeclaration<*>, properties: List<Pair<String, Type>>) {
        val builder = annotation(cu, type, "Builder") ?: return
        val typeParameters = typeParameters(type)
        val builderName = stringMember(builder, "builderClassName") ?: (type.nameAsString + "Builder")
        val builderType = ClassOrInterfaceType(null, builderName).apply {
            if (typeParameters.isNotEmpty()) setTypeArguments(NodeList(typeParameters.map { ClassOrInterfaceType(null, it.nameAsString) }))
        }
        val self = selfType(type)

        if (type.members.none { it is ClassOrInterfaceDeclaration && it.nameAsString == builderName }) {
            val builderClass = ClassOrInterfaceDeclaration(NodeList(Modifier.publicModifier(), Modifier.staticModifier()), false, builderName)
            builderClass.setTypeParameters(NodeList(typeParameters.map { it.clone() }))
            for ((name, propertyType) in properties) addMethod(builderClass, name, builderType, propertyType, name)
            addMethod(builderClass, "build", self)
            type.addMember(builderClass)
        }
        val builderMethod = stringMember(builder, "builderMethodName") ?: "builder"
        addMethod(type, builderMethod, builderType, static = true, typeParameters = typeParameters)
        if (booleanMember(builder, "toBuilder") == true) addMethod(type, "toBuilder", builderType)
    }

    // ------------------------------------------------------------------ declarations

    private fun addMethod(type: TypeDeclaration<*>, name: String, returns: Type, field: Field) =
        addMethod(type, name, returns, field.type, field.name)

    private fun addMethod(
        type: TypeDeclaration<*>,
        name: String,
        returns: Type,
        parameterType: Type? = null,
        parameterName: String = "value",
        static: Boolean = false,
        typeParameters: List<TypeParameter> = emptyList(),
    ) {
        val arity = if (parameterType == null) 0 else 1
        // A hand-written method with the same name and arity replaces Lombok's, as in Lombok itself.
        if (type.getMethodsByName(name).any { it.parameters.size == arity }) return
        val method = MethodDeclaration(NodeList(Modifier.publicModifier()), returns.clone(), name)
        if (static) method.addModifier(Modifier.Keyword.STATIC)
        method.setTypeParameters(NodeList(typeParameters.map { it.clone() }))
        parameterType?.let { method.addParameter(Parameter(it.clone(), parameterName)) }
        method.setBody(BlockStmt())
        type.addMember(method)
    }

    private fun addConstructor(type: TypeDeclaration<*>, fields: List<Field>, staticName: String?, self: Type) {
        if (staticName != null) {
            if (type.getMethodsByName(staticName).any { it.parameters.size == fields.size }) return
            val factory = MethodDeclaration(NodeList(Modifier.publicModifier(), Modifier.staticModifier()), self.clone(), staticName)
            factory.setTypeParameters(NodeList(typeParameters(type).map { it.clone() }))
            fields.forEach { factory.addParameter(Parameter(it.type.clone(), it.name)) }
            factory.setBody(BlockStmt())
            type.addMember(factory)
            return
        }
        if (type.constructors.any { it.parameters.size == fields.size }) return
        val constructor = ConstructorDeclaration(NodeList(Modifier.publicModifier()), type.nameAsString)
        fields.forEach { constructor.addParameter(Parameter(it.type.clone(), it.name)) }
        type.addMember(constructor)
    }

    private fun selfType(type: TypeDeclaration<*>): ClassOrInterfaceType = ClassOrInterfaceType(null, type.nameAsString).apply {
        val parameters = typeParameters(type)
        if (parameters.isNotEmpty()) setTypeArguments(NodeList(parameters.map { ClassOrInterfaceType(null, it.nameAsString) }))
    }

    private fun typeParameters(type: TypeDeclaration<*>): List<TypeParameter> = when (type) {
        is ClassOrInterfaceDeclaration -> type.typeParameters
        is RecordDeclaration -> type.typeParameters
        else -> emptyList()
    }

    // ------------------------------------------------------------------ annotations

    private class Accessors(val fluent: Boolean?, val chain: Boolean?) {
        fun orElse(other: Accessors) = Accessors(fluent ?: other.fluent, chain ?: other.chain)
    }

    private fun accessors(cu: CompilationUnit, node: NodeWithAnnotations<*>): Accessors {
        val annotation = node.annotations.firstOrNull { isLombok(cu, it, "Accessors") } ?: return Accessors(null, null)
        return Accessors(booleanMember(annotation, "fluent"), booleanMember(annotation, "chain"))
    }

    /** Whether Lombok generates the [kind] accessor for [field]: by the field's own annotation or a class-wide one. */
    private fun generates(cu: CompilationUnit, type: TypeDeclaration<*>, field: FieldDeclaration, kind: String, classWide: Boolean): Boolean {
        val own = field.annotations.firstOrNull { isLombok(cu, it, kind) }
        if (own != null) return !own.toString().contains("NONE")
        val onClass = annotation(cu, type, kind)
        return (onClass != null && !onClass.toString().contains("NONE")) || classWide
    }

    private fun has(cu: CompilationUnit, type: TypeDeclaration<*>, simpleName: String) = annotation(cu, type, simpleName) != null

    private fun annotation(cu: CompilationUnit, type: TypeDeclaration<*>, simpleName: String): AnnotationExpr? =
        type.annotations.firstOrNull { isLombok(cu, it, simpleName) }

    private fun isLombok(cu: CompilationUnit, annotation: AnnotationExpr, simpleName: String): Boolean {
        val name = annotation.nameAsString
        if (name == "lombok.$simpleName" || name == "lombok.experimental.$simpleName") return true
        if (name != simpleName) return false
        return cu.imports.any { import ->
            val imported = import.nameAsString
            if (import.isAsterisk) imported == "lombok" || imported == "lombok.experimental"
            else imported == "lombok.$simpleName" || imported == "lombok.experimental.$simpleName"
        }
    }

    private fun staticName(annotation: AnnotationExpr): String? = stringMember(annotation, "staticName")?.takeIf { it.isNotEmpty() }

    private fun member(annotation: AnnotationExpr, name: String) = when (annotation) {
        is NormalAnnotationExpr -> annotation.pairs.firstOrNull { it.nameAsString == name }?.value
        is SingleMemberAnnotationExpr -> if (name == "value") annotation.memberValue else null
        else -> null
    }

    private fun stringMember(annotation: AnnotationExpr, name: String): String? = (member(annotation, name) as? StringLiteralExpr)?.asString()

    private fun booleanMember(annotation: AnnotationExpr, name: String): Boolean? = (member(annotation, name) as? BooleanLiteralExpr)?.value
}
