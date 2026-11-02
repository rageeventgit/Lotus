package dev.lotus;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as a container of extension methods for {@link #value()}.
 *
 * <pre>{@code
 * @Extension(Player.class)
 * public final class PlayerExtension {
 *     // instance extension: player.heal(5)
 *     public static void heal(@This Player player, double amount) { ... }
 *
 *     // static extension: Player.create("Steve")
 *     public static Player create(String name) { ... }
 * }
 * }</pre>
 *
 * Every {@code public static} method in the class becomes an extension. A method whose first
 * parameter is annotated with {@link This} becomes an instance extension; any other method
 * becomes a static extension on the target type. Like Kotlin, a real member always wins over
 * an extension with the same name and arity.
 *
 * <p>Retention is CLASS so that extension libraries shipped as jars are still discovered by the
 * Maven plugin and the IntelliJ plugin.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface Extension {
    /** The type being extended. */
    Class<?> value();
}
