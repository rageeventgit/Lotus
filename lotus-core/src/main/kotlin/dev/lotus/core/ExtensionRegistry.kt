package dev.lotus.core

/**
 * One extension method, as seen by call sites.
 *
 * @property owner canonical name of the `@Extension` class that declares the method
 * @property target canonical name of the type being extended
 * @property parameterCount number of parameters a call site passes (receiver excluded)
 * @property instance true for `@This` receiver methods, false for static extensions
 */
data class ExtensionMethod(
    val owner: String,
    val name: String,
    val target: String,
    val parameterCount: Int,
    val varArgs: Boolean,
    val instance: Boolean,
) {
    fun acceptsArguments(count: Int): Boolean =
        if (varArgs) count >= parameterCount - 1 else count == parameterCount
}

class ExtensionRegistry(methods: Collection<ExtensionMethod>) {

    private val byName: Map<String, List<ExtensionMethod>> = methods.distinct().groupBy { it.name }

    val all: List<ExtensionMethod> get() = byName.values.flatten()

    val isEmpty: Boolean get() = byName.isEmpty()

    fun hasName(name: String): Boolean = name in byName

    fun named(name: String): List<ExtensionMethod> = byName[name].orEmpty()

    fun candidates(name: String, instance: Boolean, argumentCount: Int): List<ExtensionMethod> =
        byName[name].orEmpty().filter { it.instance == instance && it.acceptsArguments(argumentCount) }

    /**
     * Picks the extension that applies to a receiver of type [typeName], whose supertypes
     * (nearest first) are [supertypes]. Like Kotlin, the most specific target wins.
     */
    fun select(candidates: List<ExtensionMethod>, typeName: String, supertypes: List<String>): ExtensionMethod? {
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.target == typeName }?.let { return it }
        for (supertype in supertypes) {
            candidates.firstOrNull { it.target == supertype }?.let { return it }
        }
        return null
    }
}
