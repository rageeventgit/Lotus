package dev.lotus.core

import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.streams.toList
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

/**
 * Finds `@Extension` classes in compiled code (jars and class directories), so that
 * extension libraries can be shared between projects as ordinary dependencies.
 */
object ClasspathExtensionScanner {

    private const val EXTENSION_DESC = "Ldev/lotus/Extension;"
    private const val THIS_DESC = "Ldev/lotus/This;"

    fun scan(classpath: Collection<Path>): List<ExtensionMethod> = classpath.flatMap { scanElement(it) }

    private fun scanElement(element: Path): List<ExtensionMethod> = when {
        element.isDirectory() -> Files.walk(element).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "class" }
                .toList()
                .flatMap { file -> Files.newInputStream(file).use { readClass(it.readBytes()) } }
        }
        element.isRegularFile() && element.extension == "jar" -> JarFile(element.toFile()).use { jar ->
            jar.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
                .toList()
                .flatMap { entry -> jar.getInputStream(entry).use { readClass(it.readBytes()) } }
        }
        else -> emptyList()
    }

    fun readClass(bytes: ByteArray): List<ExtensionMethod> {
        // Cheap pre-check before running a full ASM pass over every class on the classpath.
        if (!containsAscii(bytes, "dev/lotus/Extension")) return emptyList()
        val collector = Collector()
        ClassReader(bytes).accept(collector, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
        return collector.result()
    }

    private class Collector : ClassVisitor(Opcodes.ASM9) {
        var owner = ""
        var target: String? = null
        val methods = mutableListOf<PendingMethod>()

        override fun visit(version: Int, access: Int, name: String, signature: String?, superName: String?, interfaces: Array<out String>?) {
            owner = canonical(name)
        }

        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            if (descriptor != EXTENSION_DESC) return null
            return object : AnnotationVisitor(Opcodes.ASM9) {
                override fun visit(name: String?, value: Any?) {
                    if (name == "value" && value is Type) target = canonical(value.internalName)
                }
            }
        }

        override fun visitMethod(access: Int, name: String, descriptor: String, signature: String?, exceptions: Array<out String>?): MethodVisitor? {
            val isPublicStatic = access and Opcodes.ACC_PUBLIC != 0 && access and Opcodes.ACC_STATIC != 0
            if (!isPublicStatic || access and Opcodes.ACC_SYNTHETIC != 0 || name.startsWith("<")) return null
            val pending = PendingMethod(
                name = name,
                parameterCount = Type.getArgumentTypes(descriptor).size,
                varArgs = access and Opcodes.ACC_VARARGS != 0,
            )
            methods += pending
            return object : MethodVisitor(Opcodes.ASM9) {
                override fun visitParameterAnnotation(parameter: Int, descriptor: String, visible: Boolean): AnnotationVisitor? {
                    if (parameter == 0 && descriptor == THIS_DESC) pending.hasReceiver = true
                    return null
                }
            }
        }

        fun result(): List<ExtensionMethod> {
            val target = target ?: return emptyList()
            return methods.map {
                ExtensionMethod(
                    owner = owner,
                    name = it.name,
                    target = target,
                    parameterCount = if (it.hasReceiver) it.parameterCount - 1 else it.parameterCount,
                    varArgs = it.varArgs,
                    instance = it.hasReceiver,
                )
            }
        }
    }

    private class PendingMethod(val name: String, val parameterCount: Int, val varArgs: Boolean) {
        var hasReceiver = false
    }

    private fun canonical(internalName: String) = internalName.replace('/', '.').replace('$', '.')

    private fun containsAscii(haystack: ByteArray, needle: String): Boolean {
        val n = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..haystack.size - n.size) {
            for (j in n.indices) if (haystack[i + j] != n[j]) continue@outer
            return true
        }
        return false
    }
}
